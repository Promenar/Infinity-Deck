package com.promenar.infinity.data.linux

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.promenar.infinity.domain.linux.LinuxProcessLaunch
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 必须在主线程创建和初始化；Termux 的 Handler 与仿真器绑定主线程。 */
internal class LinuxPtySession(launch: LinuxProcessLaunch, client: TerminalSessionClient) : LinuxPty {
    init {
        require("--kill-on-exit" in launch.arguments) { "Terminal launch requires proot --kill-on-exit" }
    }

    private val memoryBudgetBytes = launch.memoryBudgetBytes

    override val session = TerminalSession(
        launch.executable,
        launch.hostWorkingDirectory.path,
        launch.command.toTypedArray(),
        launch.environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
        2_000,
        client,
    )
    override val title: String? get() = session.title
    override val exitCode: Int? get() = if (session.isRunning) null else session.exitStatus

    override fun start() {
        // 即使页面尚未挂载，也必须启动读取和缓冲；TerminalView 随后按实际尺寸重排。
        session.updateSize(80, 24)
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        // ByteQueue 有界且会阻塞，长命令不能在主线程等待 PTY 排空。
        writeLinuxPtyInput(text, { session.isRunning }, session::write)
    }

    override suspend fun watchMemory() {
        val watch = memoryBudgetBytes?.let(LinuxResourceGovernor::watch) ?: return
        val pid = processId()
        if (pid <= 1) return
        while (processId() == pid && session.isRunning) {
            if (watch.check(pid, android.os.SystemClock.elapsedRealtime())) {
                withContext(NonCancellable) {
                    signal(-pid, OsConstants.SIGTERM)
                    delay(500)
                    if (processId() == pid && session.isRunning) {
                        // 登录 shell 的作业可在独立进程组内，由 proot 清理所有 tracee。
                        signal(pid, OsConstants.SIGQUIT)
                        delay(250)
                    }
                    signal(-pid, OsConstants.SIGKILL)
                    if (processId() == pid && session.isRunning) session.finishIfRunning()
                }
                return
            }
            delay(250)
        }
    }

    override suspend fun terminate() = withContext(Dispatchers.IO) {
        val pid = processId()
        if (pid <= 0) return@withContext
        // proot 忽略 TERM/HUP；QUIT 的处理器会清理所有 tracee，包括已脱离前台进程组的作业。
        // 单独通知 leader 也覆盖 JNI fork 已返回、子进程尚未 setsid 的短暂窗口。
        signal(-pid, OsConstants.SIGQUIT)
        if (processId() == pid) signal(pid, OsConstants.SIGQUIT)
        repeat(40) {
            if (processId() != pid) return@withContext
            delay(25)
        }
        // 正常路径由 waiter 关闭 PTY 并上报退出码；卡住时才强制结束整个原始进程组。
        if (processId() == pid) {
            signal(-pid, OsConstants.SIGKILL)
            session.finishIfRunning()
            withTimeout(5_000) { while (processId() == pid) delay(25) }
        }
    }

    private fun processId(): Int = synchronized(session) { session.pid }

    private fun signal(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw error
        }
    }
}
