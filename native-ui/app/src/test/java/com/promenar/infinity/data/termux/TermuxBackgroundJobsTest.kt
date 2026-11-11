package com.promenar.infinity.data.termux

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

abstract class TermuxBackgroundJobsTestFixture {
    protected lateinit var root: File
    protected lateinit var home: File
    protected lateinit var bin: File
    protected lateinit var jobs: TermuxBackgroundJobs
    protected val handles = mutableListOf<TermuxJobHandle>()

    @Before fun prepare() {
        assumeTrue(File("/bin/bash").canExecute())
        root = Files.createTempDirectory("termux-jobs-").toFile()
        home = File(root, "home").apply { mkdirs() }
        bin = File(root, "bin").apply { mkdirs() }
        // macOS 没有 setsid 命令，使用真实系统调用建立独立会话。
        val setsid = File(bin, "setsid").apply {
            writeText("#!/usr/bin/env python3\nimport os, sys\nos.setsid()\nos.execvp(sys.argv[1], sys.argv[1:])\n")
            setExecutable(true)
        }
        warmUp(setsid, "true")
        val identityAvailable = runCatching {
            val probe = ProcessBuilder("ps", "-p", ProcessHandle.current().pid().toString(), "-o", "lstart=").start()
            probe.inputStream.bufferedReader().readText().isNotBlank() && probe.waitFor() == 0
        }.getOrDefault(false)
        if (!identityAvailable) {
            // 受限 macOS 沙箱禁止 ps；仅替换身份时钟，进程组和信号仍走真实系统调用。
            val ps = File(bin, "ps").apply {
                writeText("#!/bin/bash\nkill -0 \"\$2\" 2>/dev/null || exit 1\nprintf 'test-start:%s\\n' \"\$2\"\n")
                setExecutable(true)
            }
            warmUp(ps, "-p", ProcessHandle.current().pid().toString(), "-o", "lstart=")
        }
        jobs = TermuxBackgroundJobs(::execute)
    }

    @After fun cleanup() = runBlocking {
        if (::jobs.isInitialized) handles.forEach { runCatching { jobs.stop(it) } }
        if (::root.isInitialized) root.deleteRecursively()
    }

    protected suspend fun awaitFinished(handle: TermuxJobHandle): TermuxJobSnapshot {
        repeat(100) {
            val result = jobs.inspect(handle)
            if (result.exitCode != null && !result.alive) return result
            Thread.sleep(100)
        }
        error("后台任务未在测试期限内结束")
    }

    protected suspend fun execute(request: TermuxCommandRequest): TermuxCommandResult {
        val process = ProcessBuilder("/bin/bash", "-c", request.command).apply {
            environment()["HOME"] = home.path
            environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
        }.start()
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        check(process.waitFor(12, TimeUnit.SECONDS)) { "RUN_COMMAND 测试调用超时" }
        return TermuxCommandResult(stdout = stdout, stderr = stderr, exitCode = process.exitValue())
    }

    /**
     * macOS 首次执行新建的可执行脚本前要排队做系统安全评估，单次约 0.3 秒，并发构建时可达数秒，
     * 会吃掉启动回执等待并误报退出码 73；Termux 的 setsid、ps 是原生程序，没有这段开销。
     * 因此在准备阶段先执行一次，让评估落在不受回执时限约束的位置。
     */
    protected fun warmUp(tool: File, vararg args: String) {
        val process = ProcessBuilder(tool.path, *args).redirectErrorStream(true).start()
        process.outputStream.close()
        process.inputStream.readBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "测试工具 ${tool.name} 预热失败" }
    }

    protected fun processExists(pid: Int): Boolean =
        ProcessBuilder("/bin/kill", "-0", pid.toString()).start().waitFor() == 0
}

class TermuxBackgroundJobsTest : TermuxBackgroundJobsTestFixture() {
    @Test fun `字节计数之后追加日志时快照仍返回对应固定范围`() = runBlocking {
        val handle = jobs.start("snapshot-race", "printf before", null, null, 120, null).also(handles::add)
        awaitFinished(handle)
        val log = File(jobs.logPath(handle))
        val quoted = "'" + log.path.replace("'", "'\\''") + "'"
        File(bin, "wc").apply {
            writeText("#!/bin/bash\n/usr/bin/wc \"\$@\"\nprintf after >> $quoted\n")
            setExecutable(true)
        }
        val snapshot = jobs.inspect(handle)
        assertThat(snapshot.logBytes).isEqualTo(6)
        assertThat(snapshot.logTail).isEqualTo("before")
        assertThat(log.readText()).isEqualTo("beforeafter")
    }

    @Test fun `更名前私有日志目录中的任务可恢复且不会重建相同任务号`() = runBlocking {
        val handle = jobs.start("legacy", "printf legacy-output", null, null, 120, null).also(handles::add)
        awaitFinished(handle)
        File(home, ".infinity").renameTo(File(home, ".nexara")).also { assertThat(it).isTrue() }
        val recovered = jobs.recover("legacy", null)!!
        handles.clear(); handles += recovered
        assertThat(recovered.directory).isEqualTo(File(home, ".nexara/jobs/legacy").canonicalPath)
        assertThat(jobs.inspect(recovered).logTail).isEqualTo("legacy-output")
        assertThat(runCatching { jobs.start("legacy", "true", null, null, 120, null) }.isFailure).isTrue()
    }

    @Test fun `启动立即返回且保留引号换行美元符号输入与工作目录`() = runBlocking {
        val workspace = File(root, "shared ' \$ workspace").apply { mkdirs() }
        val workdir = File(root, "working ' \$\nline").apply { mkdirs() }
        val input = "输入 ' \" \$HOME §\n第二行\n"
        val started = System.nanoTime()
        val handle = jobs.start(
            "quoted", "printf '%s\\n' \"\$PWD\"; cat; printf '%s\\n' 'literal '\"'\"' \$HOME'; sleep 2; exit 7",
            workdir.path, input, 120, workspace,
        ).also(handles::add)
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_800)
        assertThat(jobs.logPath(handle)).isEqualTo(File(home, ".infinity/jobs/quoted.log").canonicalPath)
        assertThat(jobs.inspect(handle).alive).isTrue()
        val result = awaitFinished(handle)
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.logTail).isEqualTo("${workdir.path}\n${input}literal ' \$HOME\n")
        assertThat(result.truncated).isFalse()
        assertThat(result.timedOut).isFalse()
    }

    @Test fun `私有日志目录被解析且新适配器能恢复查询`() = runBlocking {
        val handle = jobs.start("private", "printf result", null, null, 120, null).also(handles::add)
        assertThat(handle.directory).isEqualTo(File(home, ".infinity/jobs/private").canonicalPath)
        val recovered = TermuxBackgroundJobs(::execute).recover("private", null)
        assertThat(recovered).isEqualTo(handle)
        assertThat(TermuxBackgroundJobs(::execute).inspect(recovered!!).alive).isTrue()
        awaitFinished(handle)
        val restored = TermuxBackgroundJobs(::execute).inspect(handle.copy())
        assertThat(restored.exitCode).isEqualTo(0)
        assertThat(restored.logTail).isEqualTo("result")
        assertThat(restored.alive).isFalse()
    }

    @Test fun `旧共享目录任务退出后安全迁移且重复运行保持日志`() = runBlocking {
        val workspace = File(root, "legacy-shared").apply { mkdirs() }
        val handle = jobs.start("legacy", "printf original-log", null, null, 120, workspace)
        awaitFinished(handle)
        val original = File(handle.directory)
        val legacy = File(workspace, "jobs/legacy").apply { parentFile!!.mkdirs() }
        Files.move(original.toPath(), legacy.toPath())
        listOf(".log", ".exit").forEach { suffix -> Files.move(File(handle.directory + suffix).toPath(), File(legacy.path + suffix).toPath()) }
        val old = jobs.recover("legacy", workspace)!!
        assertThat(old.directory).isEqualTo(legacy.canonicalPath)
        val migrated = jobs.migrateCompleted("legacy", workspace, old).also(handles::add)
        assertThat(migrated.directory).isEqualTo(handle.directory)
        assertThat(jobs.inspect(migrated).logTail).isEqualTo("original-log")
        assertThat(File(legacy.path + ".log").exists()).isFalse()
        assertThat(jobs.migrateCompleted("legacy", workspace, migrated)).isEqualTo(migrated)
    }

    @Test fun `旧任务进程已消失且缺退出文件时仍迁移日志`() = runBlocking {
        val workspace = File(root, "legacy-failed").apply { mkdirs() }
        val handle = jobs.start("failed-old", "printf interrupted-log", null, null, 120, workspace)
        awaitFinished(handle)
        val legacy = File(workspace, "jobs/failed-old").apply { parentFile!!.mkdirs() }
        Files.move(File(handle.directory).toPath(), legacy.toPath())
        Files.move(File(handle.directory + ".log").toPath(), File(legacy.path + ".log").toPath())
        File(handle.directory + ".exit").delete()
        val old = jobs.recover("failed-old", workspace)!!
        assertThat(jobs.inspect(old).alive).isFalse()
        val migrated = jobs.migrateCompleted("failed-old", workspace, old).also(handles::add)
        assertThat(migrated.directory).isEqualTo(handle.directory)
        assertThat(jobs.inspect(migrated).logTail).isEqualTo("interrupted-log")
        assertThat(legacy.exists()).isFalse()
    }

}

class TermuxBackgroundJobsLifecycleTest : TermuxBackgroundJobsTestFixture() {
    @Test fun `日志只返回末尾八千字符并标记截断`() = runBlocking {
        val handle = jobs.start("tail", "printf '%09000d' 0", null, null, 120, null).also(handles::add)
        val result = awaitFinished(handle)
        assertThat(result.logTail).hasLength(8_000)
        assertThat(result.truncated).isTrue()
    }

    @Test fun `停止向整组升级发送KILL且保留停止标记`() = runBlocking {
        val handle = jobs.start(
            "stop", "trap '' TERM; (trap '' TERM; while :; do sleep 1; done) & printf 'child:%s\\n' \"\$!\"; wait",
            null, null, 120, null,
        ).also(handles::add)
        Thread.sleep(250)
        val child = jobs.inspect(handle).logTail.trim().removePrefix("child:").toInt()
        val stopped = jobs.stop(handle)
        assertThat(stopped.alive).isFalse()
        assertThat(stopped.exitCode).isEqualTo(143)
        assertThat(stopped.stopped).isTrue()
        assertThat(stopped.timedOut).isFalse()
        repeat(20) { if (processExists(child)) Thread.sleep(50) }
        assertThat(processExists(child)).isFalse()
        assertThat(TermuxBackgroundJobs(::execute).inspect(handle).stopped).isTrue()
    }

    @Test fun `独立看门狗超时终止且无需Infinity Deck继续查询`() = runBlocking {
        val script = TermuxBackgroundJobScripts.start(
            "timeout", "trap '' TERM; while :; do sleep 1; done", null, null, 1, null,
        )
        val handle = TermuxBackgroundJobScripts.parseStarted(execute(TermuxCommandRequest(script)).stdout)
            .also(handles::add)
        val result = awaitFinished(handle)
        assertThat(result.exitCode).isEqualTo(124)
        assertThat(result.timedOut).isTrue()
        assertThat(result.stopped).isFalse()
        assertThat(result.alive).isFalse()
    }

    @Test fun `已结束任务停止保持原退出码且不能覆盖同名任务`() = runBlocking {
        val handle = jobs.start("duplicate", "exit 5", null, null, 120, null).also(handles::add)
        awaitFinished(handle)
        assertThat(jobs.stop(handle).exitCode).isEqualTo(5)
        assertThat(jobs.stop(handle).stopped).isFalse()
        var failed = false
        try { jobs.start("duplicate", "true", null, null, 120, null) }
        catch (_: IllegalStateException) { failed = true }
        assertThat(failed).isTrue()
    }

    @Test fun `拒绝非法参数且不会调用RUN_COMMAND`() = runBlocking {
        var dispatched = false
        val adapter = TermuxBackgroundJobs { dispatched = true; TermuxCommandResult(exitCode = 0) }
        listOf("../escape", "with space", "", "a\n").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { adapter.start(id, "true", null, null, 120, null) }
            }
        }
        listOf(0, 1441).forEach { runtime ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { adapter.start("valid", "true", null, null, runtime, null) }
            }
        }
        assertThat(dispatched).isFalse()
    }

    @Test fun `无效回包与调用失败不会伪造任务或暴露命令`() = runBlocking {
        val failed = TermuxBackgroundJobs { TermuxCommandResult(errorCode = "TERMUX_PERMISSION_DENIED") }
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { failed.start("valid", "secret-command", null, null, 120, null) }
        }
        assertThat(failure.message).contains("TERMUX_PERMISSION_DENIED")
        assertThat(failure.message).doesNotContain("secret-command")
        assertThrows(IllegalStateException::class.java) {
            TermuxBackgroundJobScripts.parseStarted("unexpected\n123\n")
        }
        Unit
    }

}

class TermuxBackgroundJobsRecoveryTest : TermuxBackgroundJobsTestFixture() {
    @Test fun `PID身份不匹配时拒绝停止其它进程`() = runBlocking {
        val handle = jobs.start("identity", "sleep 30", null, null, 120, null).also(handles::add)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { jobs.stop(handle.copy(pid = handle.pid + 1)) }
        }
        assertThat(jobs.inspect(handle).alive).isTrue()
    }

    @Test fun `启动回执丢失后按确定性目录恢复控制`() = runBlocking {
        val lost = TermuxBackgroundJobs { request ->
            execute(request)
            TermuxCommandResult(errorCode = "TERMUX_TIMEOUT", timedOut = true)
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { lost.start("lost_ack", "sleep 30", null, null, 120, null) }
        }
        val recovered = jobs.recover("lost_ack", null)!!.also(handles::add)
        assertThat(jobs.inspect(recovered).alive).isTrue()
        assertThat(jobs.stop(recovered).stopped).isTrue()
    }

    @Test fun `恢复不存在目录返回空且不完整元数据失败关闭`() = runBlocking {
        assertThat(jobs.recover("missing", null)).isNull()
        File(home, ".infinity/jobs/incomplete").mkdirs()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { jobs.recover("incomplete", null) }
        }
        Unit
    }

    @Test fun `未确认停止留下墓碑且排队晚到的启动不会执行`() = runBlocking {
        val workspace = File(root, "cancel-shared").apply { mkdirs() }
        assertThat(jobs.stopUnconfirmed("late", workspace)).isNull()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                jobs.start("late", "printf should-not-run", null, null, 120, workspace)
            }
        }
        assertThat(File(home, ".infinity/jobs/late.cancelled").exists()).isTrue()
        assertThat(File(home, ".infinity/jobs/late.log").exists()).isFalse()
    }

    @Test fun `未确认停止也能找回已有任务并停止整个进程组`() = runBlocking {
        val handle = jobs.start("unconfirmed", "sleep 30", null, null, 120, null).also(handles::add)
        val result = TermuxBackgroundJobs(::execute).stopUnconfirmed("unconfirmed", null)!!
        assertThat(result.stopped).isTrue()
        assertThat(result.alive).isFalse()
        assertThat(jobs.inspect(handle).exitCode).isEqualTo(143)
    }

    @Test fun `目录创建后到达的停止标记仍阻止worker执行用户命令`() = runBlocking {
        val script = TermuxBackgroundJobScripts.start("worker_cancel", "printf should-not-run", null, null, 120, null)
            .replaceFirst("setsid nohup bash -c", ": > \"\$directory.cancelled\"\nsetsid nohup bash -c")
        val handle = TermuxBackgroundJobScripts.parseStarted(execute(TermuxCommandRequest(script)).stdout)
            .also(handles::add)
        val result = awaitFinished(handle)
        assertThat(result.exitCode).isEqualTo(143)
        assertThat(result.stopped).isTrue()
        assertThat(result.logTail).doesNotContain("should-not-run")
    }

}
