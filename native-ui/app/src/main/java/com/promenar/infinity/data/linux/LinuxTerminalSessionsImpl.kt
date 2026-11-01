package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.ExecRunInfo
import com.promenar.infinity.data.exec.ExecRunKind
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalInfo
import com.promenar.infinity.domain.linux.LinuxTerminalLocation
import com.promenar.infinity.domain.linux.LinuxTerminalSessions
import com.promenar.infinity.domain.linux.LinuxUnavailableException
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.UUID
import java.io.File
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 应用级会话所有者；界面只切换客户端，不持有进程生命周期。 */
internal class LinuxTerminalSessionsImpl(
    private val runtime: LinuxRuntime,
    private val scope: CoroutineScope,
    private val host: LinuxHostServices,
    private val factory: LinuxPtyFactory = LinuxPtyFactory(::LinuxPtySession),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionHome: suspend (String) -> File? = host.sessionHome,
    private val reconcileSession: suspend (String) -> Unit = host.reconcileSession,
    private val wakeInbox: suspend (String) -> File? = host.wakeInbox,
) : LinuxTerminalSessions {
    private class Entry(val infoAtStart: LinuxTerminalInfo, initialCommand: String?) {
        val id get() = infoAtStart.id
        var info = infoAtStart
        lateinit var client: LinuxTerminalClient
        lateinit var pty: LinuxPty
        val ended = CompletableDeferred<Unit>()
        val inputSubmitted = CompletableDeferred<Unit>().apply { if (initialCommand == null) complete(Unit) }
        val marker = "infinity-ready-$id"
        var pendingCommand = initialCommand
        var initialWriting = false
        var stopping = false
        var visible = false
        var discarded = false
        var finishMutation: suspend () -> Unit = {}
    }

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val state = MutableStateFlow<List<LinuxTerminalInfo>>(emptyList())
    override val sessions = state.asStateFlow()
    private var nextOrdinal = 1
    private var environmentEpoch = 0L

    init {
        scope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                runtime.state.collect { environment ->
                    if (environment !is LinuxRuntimeState.Ready) {
                        val active = synchronized(lock) { environmentEpoch++; entries.values.toList() }
                        active.forEach(::stop)
                    }
                }
            } finally {
                synchronized(lock) { entries.values.toList() }.forEach(::stop)
            }
        }
    }

    override suspend fun open(location: LinuxTerminalLocation, initialCommand: String?): String {
        scope.coroutineContext.ensureActive()
        require(initialCommand?.contains('\u0000') != true) { "Terminal command contains NUL" }
        val epoch = synchronized(lock) { requireReady(); environmentEpoch }
        var finishMutation: suspend () -> Unit = {}
        var mutationStarted = false
        var opened: Entry? = null
        try {
            val launch = withContext(ioDispatcher) {
                val spec = when (location) {
                    is LinuxTerminalLocation.SessionWorkspace -> {
                        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = location.sessionId)
                        val home = sessionHome(location.sessionId)
                            ?: error("Session home is unavailable for session ${location.sessionId}")
                        val workspace = try {
                            host.sessionWorkspaceRoot(location.sessionId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            throw IllegalStateException("Cannot resolve workspace for session ${location.sessionId}", error)
                        } ?: error("Workspace is unavailable for session ${location.sessionId}")
                        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = location.sessionId, path = workspace.canonicalPath)
                        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = location.sessionId, path = home.canonicalPath)
                        val finishHome = host.beginSessionMutation(location.sessionId)
                        val finishWorkspace = try { com.promenar.infinity.data.repository.WorkspaceExternalMutationCoordinator.begin(workspace.toPath()) }
                        catch (failure: Throwable) { withContext(NonCancellable) { finishHome() }; throw failure }
                        val closed = java.util.concurrent.atomic.AtomicBoolean()
                        finishMutation = {
                            if (closed.compareAndSet(false, true)) {
                                try { finishHome() } finally { finishWorkspace() }
                            }
                        }
                        mutationStarted = true
                        reconcileSession(location.sessionId)
                        LinuxLaunchSpec(emptyList(), workspace, workingDirectory = "/workspace", sessionHome = home,
                            wakeInbox = wakeInbox(location.sessionId))
                    }
                    is LinuxTerminalLocation.Directory -> {
                        require(location.path.startsWith('/') && '\u0000' !in location.path) { "Terminal directory must be absolute" }
                        val directory = LinuxFilePaths(runtime.paths.rootfs).resolve(location.path)
                        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(path = directory.toString())
                        LinuxLaunchSpec(emptyList(), workingDirectory = location.path)
                    }
                }
                runtime.prepareLaunch(spec)
            }
            return withContext(mainDispatcher) {
                val entry = synchronized(lock) {
                    requireReady(epoch)
                    val ordinal = nextOrdinal
                    nextOrdinal = Math.incrementExact(nextOrdinal)
                    Entry(LinuxTerminalInfo(UUID.randomUUID().toString(), ordinal, null, location, now(), true), initialCommand)
                }
                entry.finishMutation = finishMutation
                entry.client = LinuxTerminalClient({ onTitle(entry) }, { finished(entry) })
                entry.pty = factory.create(launch, entry.client)
                opened = entry
                synchronized(lock) { entries[entry.id] = entry }
                val acquired = CompletableDeferred<Unit>()
                scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val directory = when (location) {
                            is LinuxTerminalLocation.SessionWorkspace -> "/workspace"
                            is LinuxTerminalLocation.Directory -> location.path
                        }
                        val run = ExecRunInfo(entry.id, ExecRunKind.TERMINAL,
                            ExecRunInfo.summarize("PTY ${entry.info.ordinal} · $directory"), 0, entry.info.startedAtMs)
                        host.keepAlive.hold(run, { close(entry.id) }) {
                            acquired.complete(Unit)
                            entry.ended.await()
                        }
                    } catch (error: Exception) {
                        acquired.completeExceptionally(error)
                    } finally {
                        if (!entry.ended.isCompleted) stop(entry)
                    }
                }
                // 保活失败、取消或重置不能留下已经启动却无人管理的 PTY。
                acquired.await()
                currentCoroutineContext().ensureActive()
                scope.coroutineContext.ensureActive()
                synchronized(lock) {
                    requireReady(epoch)
                    check(!entry.stopping) { "Terminal session was closed during startup" }
                    // 与通知停止和环境失效互斥，不能在已执行清理后才创建子进程。
                    entry.visible = initialCommand == null
                    entry.pty.start()
                    publish()
                }
                if (initialCommand != null) {
                    // 终端回显只含字面反斜杠；只有 shell 执行 printf 后才会产生真正的 OSC 标记。
                    // 同一输出随后清屏并清空滚动区，擦掉握手命令的两次回显（tty 与行编辑器）。
                    write(entry, " printf '\\033]0;${entry.marker}\\007\\033]0;\\007\\033[H\\033[2J\\033[3J'\r")
                    // 初始命令提交前不暴露 TerminalSession，避免页面输入与自动输入拼接。
                    withTimeout(30_000) { entry.inputSubmitted.await() }
                }
                entry.id
            }
        } catch (error: Throwable) {
            opened?.let { discard(it) }
            if (opened == null && mutationStarted) withContext(NonCancellable + ioDispatcher) {
                try { finishMutation() } finally {
                    (location as? LinuxTerminalLocation.SessionWorkspace)?.let { reconcileSession(it.sessionId) }
                }
            }
            throw error
        }
    }

    override fun session(id: String): TerminalSession? = synchronized(lock) {
        entries[id]?.takeIf { it.visible }?.pty?.session
    }

    override fun attachClient(id: String, client: TerminalSessionClient?) {
        synchronized(lock) { entries[id]?.takeIf { it.visible }?.client?.delegate = client ?: LinuxTerminalSilentClient }
    }

    override fun close(id: String) {
        synchronized(lock) { entries[id] }?.let(::discard)
    }

    private fun discard(entry: Entry) {
        synchronized(lock) {
            entry.visible = false
            entry.discarded = true
            entry.client.delegate = LinuxTerminalSilentClient
            if (entry.ended.isCompleted) entries.remove(entry.id)
            publish()
        }
        stop(entry)
    }

    private fun stop(entry: Entry) {
        synchronized(lock) {
            if (entry.stopping || entry.ended.isCompleted) return
            entry.stopping = true
            entry.pendingCommand = null
            entry.inputSubmitted.completeExceptionally(IllegalStateException("Terminal session was stopped during startup"))
        }
        // 结束应用作用域也必须先清理进程；terminate 自身有界，不依赖页面的生命周期。
        scope.launch(NonCancellable + ioDispatcher) {
            try {
                entry.pty.terminate()
                finished(entry)
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!entry.ended.isCompleted) {
                        // 不能把终止失败当成已退出；保留通知与会话，让停止入口能够重试。
                        entry.stopping = false
                        entry.discarded = false
                        entry.visible = true
                        publish()
                    }
                }
            }
        }
    }

    private fun finished(entry: Entry) {
        synchronized(lock) {
            if (entry.ended.isCompleted) return
            entry.pendingCommand = null
            entry.info = entry.info.copy(running = false, exitCode = entry.pty.exitCode)
            if (!entry.initialWriting) entry.inputSubmitted.completeExceptionally(IllegalStateException("Terminal exited before initial input"))
            if (entry.discarded) entries.remove(entry.id)
            publish()
            entry.ended.complete(Unit)
        }
        (entry.infoAtStart.location as? LinuxTerminalLocation.SessionWorkspace)?.let { location ->
            scope.launch(NonCancellable + ioDispatcher) {
                try { try { entry.finishMutation() } finally { reconcileSession(location.sessionId) } }
                catch (failure: Exception) { InfinityLogger.logError("LinuxTerminal.reconcile", failure) }
            }
        }
    }

    private fun onTitle(entry: Entry): Boolean {
        val title = entry.pty.title
        synchronized(lock) {
            if (title == entry.marker) {
                val command = entry.pendingCommand
                entry.pendingCommand = null
                if (command != null && !entry.stopping && !entry.ended.isCompleted) {
                    scope.launch(ioDispatcher) {
                        try {
                            synchronized(lock) {
                                if (entry.stopping || entry.ended.isCompleted) {
                                    entry.inputSubmitted.completeExceptionally(IllegalStateException("Terminal exited before initial input"))
                                    return@launch
                                }
                                entry.initialWriting = true
                            }
                            entry.pty.write("$command\r")
                            withContext(mainDispatcher) {
                                synchronized(lock) {
                                    if (!entry.discarded) { entry.visible = true; publish() }
                                    entry.inputSubmitted.complete(Unit)
                                }
                            }
                        } catch (error: Exception) {
                            entry.inputSubmitted.completeExceptionally(error)
                            stop(entry)
                        }
                    }
                }
                return false
            }
            entry.info = entry.info.copy(title = title?.takeIf(String::isNotEmpty))
            publish()
        }
        return true
    }

    private fun write(entry: Entry, text: String) {
        scope.launch(ioDispatcher) {
            try {
                if (synchronized(lock) { !entry.stopping && !entry.ended.isCompleted }) entry.pty.write(text)
            } catch (error: Exception) {
                entry.inputSubmitted.completeExceptionally(error)
                stop(entry)
            }
        }
    }

    private fun requireReady(epoch: Long = environmentEpoch) {
        val environment = runtime.state.value
        if (environment !is LinuxRuntimeState.Ready) throw LinuxUnavailableException(environment)
        check(epoch == environmentEpoch) { "Linux environment changed during terminal startup" }
    }

    /** 仅在 lock 内发布，保持退出、关闭和新建列表的顺序一致。 */
    private fun publish() { state.value = entries.values.filter { it.visible }.map { it.info } }
}
