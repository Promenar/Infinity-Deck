package com.promenar.infinity.data.exec

import com.promenar.infinity.domain.tool.ToolOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BuiltinCommandRequest(
    /** 在新进程组内执行的程序与参数。 */
    val argv: List<String>,
    val workingDirectory: File,
    /** 完整环境；不继承主进程环境。 */
    val environment: Map<String, String>,
    val stdin: String? = null,
    val timeoutSeconds: Int,
    val terminal: Boolean = false,
    val outputLimitOverride: Int? = null,
    val outputTransform: CommandOutputTransform? = null,
)

data class BuiltinCommandOutput(
    /** 超时或被我们结束时为 null。 */
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
    val stdoutOriginalLength: Long,
    val stderrOriginalLength: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    /** 进程被非本端发出的 SIGKILL 结束（常见于系统内存回收或冻结）。 */
    val killedExternally: Boolean = false,
    /** 用户经 [BuiltinCommandControl.requestStop] 结束了命令。 */
    val stoppedByUser: Boolean = false,
    /** 等待期间本进程被系统暂停（后台冻结或设备休眠）的累计毫秒数；不计入超时。 */
    val pausedMillis: Long = 0L,
)

/** 在进入日志、实时输出和有界收集器之前处理完整分块。 */
class CommandOutputTransform(
    val accept: (ToolOutputStream, String) -> String,
    val finish: (ToolOutputStream) -> String,
    val snapshot: (String) -> String,
    /** 终端规范化可能重新拼出秘密，因此日志使用独立流状态。 */
    val normalized: (() -> CommandOutputTransform)? = null,
    val normalizationTailChars: Int = 0,
)

/** 运行中命令的外部控制；可在任意线程调用。 */
class BuiltinCommandControl {
    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile internal var outputEvents: ((ToolOutputStream, String) -> Unit)? = null
    @Volatile var logStartedAtMillis: Long? = null
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile var timeoutSecondsOverride: Int? = null
    @Volatile var yieldAfterSeconds: Int = 0
    @Volatile var yieldedTimeoutSeconds: Int = 120 * 60
    val yielded = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile internal var terminalText: com.promenar.infinity.data.linux.LinuxExecTerminalText? = null
    @Volatile internal var input: ((String, Boolean) -> Unit)? = null
    @Volatile internal var inputBytes: ((ByteArray, Boolean) -> Unit)? = null
    @Volatile internal var outputSanitizer: ((String) -> String)? = null
    val outputSnapshot: BuiltinCommandOutput? get() = terminalText?.snapshot()?.let { value ->
        outputSanitizer?.let { value.copy(stdout = it(value.stdout), stderr = it(value.stderr)) } ?: value
    }
    internal fun writeInput(bytes: ByteArray, eof: Boolean) {
        (inputBytes ?: throw java.io.IOException("任务没有可写的交互输入"))(bytes, eof)
    }
    internal fun writeInput(text: String, eof: Boolean) {
        (input ?: throw java.io.IOException("任务没有可写的交互输入"))(text, eof)
    }

    fun requestYield() {
        timeoutSecondsOverride = yieldedTimeoutSeconds
        yielded.complete(Unit)
    }

    fun requestStop() {
        stopRequested = true
    }
}

/** 向整个进程组发送信号；进程组已不存在时静默返回。 */
fun interface ProcessGroupSignaller {
    fun signal(processGroupId: Int, signal: Int)
}

/**
 * 在独立进程组中启动命令：[groupLauncher]（Android 为 toybox `setsid`）让命令成为新会话首进程，
 * 包装 sh 把自身 pid（即进程组号）写入临时文件后 exec 目标程序。超时或取消时先 SIGTERM 整个进程组，
 * [killGraceMillis] 后 SIGKILL；命令正常结束后同组残留的后台进程也会被 SIGKILL，避免在释放工作区写锁后继续改动文件。
 *
 * 等待以 [pollMillis] 为步长轮询，期间响应用户停止。相邻两次轮询的间隔超出步长 [pauseThresholdMillis] 以上，
 * 说明本进程被系统暂停（ColorOS 等后台冻结、无唤醒锁时的深度休眠），超出部分计入 [BuiltinCommandOutput.pausedMillis]
 * 而不消耗超时；[clockNanos] 在 Android 上应包含深度休眠时间（`SystemClock.elapsedRealtimeNanos`）。
 */
class BuiltinCommandRunner(
    private val groupLauncher: List<String>,
    private val shell: String,
    private val signaller: ProcessGroupSignaller,
    private val scratchDirectory: () -> File,
    private val outputLimit: Int = OUTPUT_LIMIT,
    private val killGraceMillis: Long = 2_000,
    private val drainTimeoutMillis: Long = 2_000,
    private val pollMillis: Long = 250,
    private val pauseThresholdMillis: Long = 2_000,
    private val clockNanos: () -> Long = System::nanoTime,
    private val processIdentity: (Int) -> String? = ::readLinuxProcessStartIdentity,
    private val bootIdentity: () -> String? = { runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull() },
    private val requireBackgroundIdentity: Boolean = false,
    private val runtimeDataMonitor: RuntimeDataMonitor? = null,
    private val terminalFactory: com.promenar.infinity.data.linux.LinuxExecPtyFactory = com.promenar.infinity.data.linux.LinuxExecNativePtyFactory,
) {
    suspend fun run(
        request: BuiltinCommandRequest,
        control: BuiltinCommandControl = BuiltinCommandControl(),
        /** 读取线程每读到一块输出即回调，只用于展示；不影响结果中的尾部截断。 */
        onOutput: ((ToolOutputStream, String) -> Unit)? = null,
    ): BuiltinCommandOutput = runInternal(request, control, onOutput)

    /** 非终端前台命令把进程身份附着到任务目录，保留分离的标准输出与错误。 */
    suspend fun runAttached(
        request: BuiltinCommandRequest,
        control: BuiltinCommandControl,
        processGroupFile: File?,
        onOutput: ((ToolOutputStream, String) -> Unit)? = null,
    ): BuiltinCommandOutput = runInternal(request, control, onOutput, processGroupFile = processGroupFile)

    suspend fun runTerminal(
        request: BuiltinCommandRequest,
        control: BuiltinCommandControl,
        onTerminalOutput: (String) -> Unit,
        processGroupFile: File? = null,
        terminalLog: File? = null,
    ): BuiltinCommandOutput = runInternal(request, control, null, terminalLog, processGroupFile, onTerminalOutput = onTerminalOutput)

    /** 后台日志由单条合并管道流式写盘，调用方必须使用应用级作用域。 */
    suspend fun runBackground(
        request: BuiltinCommandRequest,
        control: BuiltinCommandControl,
        logFile: File,
        processGroupFile: File,
        onStarted: () -> Unit = {},
        onOutput: ((ToolOutputStream, String) -> Unit)? = null,
        onTerminalOutput: ((String) -> Unit)? = null,
    ): BuiltinCommandOutput {
        val before = runtimeDataMonitor?.capture()
        try {
            return runInternal(request, control, onOutput, logFile, processGroupFile, onStarted, onTerminalOutput)
        } finally {
            if (before != null) withContext(NonCancellable) {
                val fields = runtimeDataMonitor.changesSince(before)
                // 检测文件不可写不改变进程退出码；查询工具会披露缺失的检测结果。
                try {
                    RuntimeDataMonitor.writeBackgroundResult(checkNotNull(processGroupFile.absoluteFile.parentFile), fields)
                } catch (_: java.io.IOException) {
                } catch (_: SecurityException) {
                }
            }
        }
    }

    /** 启动恢复仅清理持久化进程组；调用方负责记录中断。 */
    suspend fun interruptBackground(processGroupFile: File): Boolean = withContext(Dispatchers.IO) {
        val group=readProcessGroup(processGroupFile) ?: return@withContext true
        val saved = File(processGroupFile.path + ".identity").takeIf { it.isFile }?.readText()?.trim()
        val current=identityToken(group)
        if(saved==null || current==null) return@withContext false
        if(saved!=current) return@withContext true
        signaller.signal(group, SIGTERM)
        kotlinx.coroutines.delay(killGraceMillis)
        signaller.signal(group, SIGKILL)
        true
    }

    private suspend fun runInternal(
        request: BuiltinCommandRequest,
        control: BuiltinCommandControl,
        onOutput: ((ToolOutputStream, String) -> Unit)?,
        logFile: File? = null,
        processGroupFile: File? = null,
        onStarted: () -> Unit = {},
        onTerminalOutput: ((String) -> Unit)? = null,
    ): BuiltinCommandOutput = withContext(Dispatchers.IO) {
        require(request.argv.isNotEmpty()) { "命令不能为空" }
        require(request.timeoutSeconds in 1..(if (logFile == null) MAX_TIMEOUT_SECONDS else 1440 * 60)) { "timeout_seconds 必须为 1–$MAX_TIMEOUT_SECONDS" }
        // 启动前已请求停止（等待写锁或准备环境期间）则不再启动命令。
        if (control.stopRequested) {
            return@withContext BuiltinCommandOutput(
                exitCode = null, timedOut = false, stdout = "", stderr = "",
                stdoutOriginalLength = 0, stderrOriginalLength = 0, stdoutTruncated = false, stderrTruncated = false,
                stoppedByUser = true,
            )
        }
        val scratch = File(scratchDirectory(), "exec-${UUID.randomUUID()}").apply { mkdirs() }
        val pidFile = processGroupFile ?: File(scratch, "pgid")
        pidFile.parentFile?.mkdirs()
        val persistentIdentity = processGroupFile != null || logFile != null
        try {
            val builder = ProcessBuilder(launchCommand(request, pidFile, persistentIdentity && requireBackgroundIdentity))
                .directory(request.workingDirectory)
                .redirectErrorStream(logFile != null)
            builder.environment().clear()
            builder.environment().putAll(request.environment)
            val process = if (request.terminal) terminalFactory.start(
                launchCommand(request, pidFile, persistentIdentity && requireBackgroundIdentity).drop(groupLauncher.size),
                request.workingDirectory, request.environment + ("TERM" to "xterm-256color"),
            ) else builder.start()
            var settled=false
            try {
            if (persistentIdentity) {
                val group = awaitProcessGroup(pidFile)
                if(requireBackgroundIdentity) {
                    repeat(10) {
                        if(!File(pidFile.path+".identity").isFile && process.isAlive) runInterruptible { Thread.sleep(50) }
                    }
                    if(!File(pidFile.path+".identity").isFile) throw java.io.IOException("无法验证后台进程身份，命令未获准运行")
                } else if(!File("/proc/self/stat").isFile) {
                    group?.let { pid -> identityToken(pid)?.let { identity ->
                        val file=File(pidFile.path + ".identity")
                        val temporary=File(file.path + ".host-${UUID.randomUUID()}.tmp")
                        temporary.writeText(identity)
                        java.nio.file.Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    } }
                }
            }
            val limit = request.outputLimitOverride ?: outputLimit
            val normalized = request.outputTransform?.normalized?.invoke()
            val eventNormalizer = if (control.outputEvents != null) request.outputTransform?.normalized?.invoke() else null
            fun emitTerminalEvent(text: String) {
                val safe = eventNormalizer?.accept?.invoke(ToolOutputStream.STDOUT, text) ?: text
                if (safe.isNotEmpty()) control.outputEvents?.invoke(ToolOutputStream.STDOUT, safe)
            }
            fun clean(stream: ToolOutputStream, text: String) = request.outputTransform?.accept?.invoke(stream, text) ?: text
            fun tail(stream: ToolOutputStream) = request.outputTransform?.finish?.invoke(stream).orEmpty()
            fun normalizedText(text: String) = normalized?.accept?.invoke(ToolOutputStream.STDOUT, text) ?: text
            fun terminalSnapshot(text: String): String =
                (normalized?.snapshot?.invoke(text) ?: request.outputTransform?.snapshot?.invoke(text) ?: text)
                    .takeLast(limit).dropWhile { Character.isLowSurrogate(it) }
            val stdout = TailTextCollector(limit, onOutput?.let { emit -> { text -> emit(ToolOutputStream.STDOUT, text) } },
                { clean(ToolOutputStream.STDOUT, it) }, { tail(ToolOutputStream.STDOUT) })
            val stderr = TailTextCollector(limit, onOutput?.let { emit -> { text -> emit(ToolOutputStream.STDERR, text) } },
                { clean(ToolOutputStream.STDERR, it) }, { tail(ToolOutputStream.STDERR) })
            control.outputSanitizer = if (request.terminal) ::terminalSnapshot else request.outputTransform?.snapshot
            val logFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val logStartedAt = control.logStartedAtMillis ?: System.currentTimeMillis()
            // 为规范化后的匹配保留前文，遮蔽之后才裁成公开输出预算。
            val terminalLimit = (limit.toLong() + (request.outputTransform?.normalizationTailChars ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val terminalText = if (request.terminal) com.promenar.infinity.data.linux.LinuxExecTerminalText(terminalLimit).also { control.terminalText = it } else null
            val readers = if (terminalText != null) listOf(daemon("infinity-pty-output") {
                val log = logFile?.let { com.promenar.infinity.data.jobs.RotatingJobLog(it, startedAtMillis = logStartedAt) }
                try {
                    InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                        val chunk = CharArray(8192)
                        while (true) {
                            val count = try { reader.read(chunk) } catch (failure: java.io.IOException) {
                                // Linux PTY 在 slave 关闭后以 EIO 表示读取结束。
                                if (failure.message?.contains("EIO") == true || failure.message?.contains("I/O error", ignoreCase = true) == true || !process.isAlive) -1 else throw failure
                            }
                            if (count < 0) break
                            val committed = terminalText.accept(clean(ToolOutputStream.STDOUT, String(chunk, 0, count)), ::emitTerminalEvent)
                            answerTerminalQueries(process, terminalText)
                            val cleanCommitted = normalizedText(committed)
                            log?.write(cleanCommitted.toByteArray(Charsets.UTF_8))
                            val snapshot = terminalSnapshot(terminalText.snapshot().stdout)
                            if (onTerminalOutput != null) onTerminalOutput(snapshot)
                            else if (cleanCommitted.isNotEmpty()) onOutput?.invoke(ToolOutputStream.STDOUT, cleanCommitted)
                        }
                    }
                    val rest = terminalText.accept(tail(ToolOutputStream.STDOUT), ::emitTerminalEvent) + terminalText.finish()
                    eventNormalizer?.finish?.invoke(ToolOutputStream.STDOUT)?.takeIf { it.isNotEmpty() }
                        ?.let { control.outputEvents?.invoke(ToolOutputStream.STDOUT, it) }
                    val cleanRest = normalizedText(rest) + normalized?.finish?.invoke(ToolOutputStream.STDOUT).orEmpty()
                    log?.write(cleanRest.toByteArray(Charsets.UTF_8))
                    if (onTerminalOutput != null) onTerminalOutput(terminalSnapshot(terminalText.snapshot().stdout))
                    else if (cleanRest.isNotEmpty()) onOutput?.invoke(ToolOutputStream.STDOUT, cleanRest)
                } catch (failure: Throwable) { logFailure.set(failure); control.requestStop() }
                finally { log?.close() }
            }) else if (logFile != null) listOf(daemon("infinity-job-output") {
                try {
                com.promenar.infinity.data.jobs.RotatingJobLog(logFile, startedAtMillis = logStartedAt).use { log ->
                    InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                        val chunk = CharArray(8 * 1024)
                        while (true) {
                            val count = reader.read(chunk)
                            if (count < 0) break
                            val text = clean(ToolOutputStream.STDOUT, String(chunk, 0, count))
                            log.write(text.toByteArray(Charsets.UTF_8))
                            if (text.isNotEmpty()) control.outputEvents?.invoke(ToolOutputStream.STDOUT, text)
                            onOutput?.invoke(ToolOutputStream.STDOUT, text)
                        }
                        val rest = tail(ToolOutputStream.STDOUT)
                        log.write(rest.toByteArray(Charsets.UTF_8))
                        if (rest.isNotEmpty()) control.outputEvents?.invoke(ToolOutputStream.STDOUT, rest)
                        if (rest.isNotEmpty()) onOutput?.invoke(ToolOutputStream.STDOUT, rest)
                    }
                }
                } catch (failure: Throwable) {
                    logFailure.set(failure)
                    control.requestStop()
                }
            }) else listOf(
                daemon("infinity-exec-stdout") { stdout.consume(process.inputStream) },
                daemon("infinity-exec-stderr") { stderr.consume(process.errorStream) },
            )
            if (request.terminal && request.stdin == null) {
                control.inputBytes = { bytes, eof ->
                    synchronized(process.outputStream) {
                        if (!process.isAlive) throw java.io.IOException("任务已经结束")
                        process.outputStream.write(bytes)
                        if (eof) process.outputStream.write(byteArrayOf(4, 4))
                        process.outputStream.flush()
                    }
                }
                control.input = { text, eof ->
                    synchronized(process.outputStream) {
                        if (!process.isAlive) throw java.io.IOException("任务已经结束")
                        process.outputStream.write(text.toByteArray(Charsets.UTF_8))
                        if (eof) process.outputStream.write(byteArrayOf(4, 4))
                        process.outputStream.flush()
                    }
                }
            } else daemon("infinity-exec-stdin") {
                process.outputStream.use { output ->
                    request.stdin?.let { output.write(it.toByteArray(Charsets.UTF_8)) }
                    if (request.terminal) { output.write(byteArrayOf(4, 4)); output.flush() }
                }
            }
            onStarted()
            control.started.complete(Unit)
            val wait = try {
                runInterruptible { await(process, request.timeoutSeconds, control, logFile == null || request.terminal) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { if(request.terminal) process.destroy(); terminate(process, pidFile, logFile != null); settled=true }
                throw cancelled
            }
            val signalled = wait.outcome != WaitOutcome.EXITED
            if (signalled) {
                if (request.terminal) process.destroy()
                terminate(process, pidFile, logFile != null)
            } else {
                // 主进程已退出；同组后台进程不能跨过写锁继续运行。
                readProcessGroup(pidFile)?.let { runCatching { signaller.signal(it, SIGKILL) } }
            }
            settled=true
            readers.forEach { it.join(drainTimeoutMillis) }
            if (readers.any { it.isAlive }) {
                process.inputStream.close()
                process.errorStream.close()
                readers.forEach { it.join(drainTimeoutMillis) }
                throw java.io.IOException("命令输出未能完整读取")
            }
            logFailure.get()?.let { throw java.io.IOException("后台任务日志写入失败", it) }
            val exit = if (signalled) null else process.exitValue()
            val terminalSnapshot = terminalText?.snapshot()?.let { value ->
                value.copy(stdout = terminalSnapshot(value.stdout), stderr = terminalSnapshot(value.stderr))
            }
            BuiltinCommandOutput(
                exitCode = exit,
                timedOut = wait.outcome == WaitOutcome.TIMED_OUT,
                stdout = terminalSnapshot?.stdout ?: stdout.text(),
                stderr = terminalSnapshot?.stderr ?: stderr.text(),
                stdoutOriginalLength = terminalSnapshot?.stdoutOriginalLength ?: stdout.totalChars,
                stderrOriginalLength = terminalSnapshot?.stderrOriginalLength ?: stderr.totalChars,
                stdoutTruncated = terminalSnapshot?.stdoutTruncated ?: stdout.truncated,
                stderrTruncated = terminalSnapshot?.stderrTruncated ?: stderr.truncated,
                killedExternally = exit == SIGNAL_EXIT_BASE + SIGKILL,
                stoppedByUser = wait.outcome == WaitOutcome.STOPPED,
                pausedMillis = wait.pausedNanos / 1_000_000,
            )
            } catch (failure:Throwable) {
                if(!settled) withContext(NonCancellable) { if(request.terminal) process.destroy(); terminate(process,pidFile,logFile!=null) }
                throw failure
            } finally {
                control.input = null
                control.inputBytes = null
                (process as? AutoCloseable)?.close()
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** 包装脚本先于 proot 执行，宿主工具不能依赖 guest 的 PATH。 */
    internal fun launchCommand(request: BuiltinCommandRequest, pidFile: File, requireIdentity: Boolean): List<String> =
        groupLauncher + listOf(shell, "-c", WRAPPER, "infinity-exec", pidFile.path, bootIdentity().orEmpty(),
            if (requireIdentity) "1" else "0", hostUtility("cat"), hostUtility("mv"), hostUtility("printf")) + request.argv

    /** 把终端查询答复写回伪终端；输入端已关闭（传了 stdin）或进程已结束时放弃答复。 */
    private fun answerTerminalQueries(process: Process, terminal: com.promenar.infinity.data.linux.LinuxExecTerminalText) {
        val replies = terminal.takeReplies()
        if (replies.isEmpty()) return
        runCatching {
            synchronized(process.outputStream) {
                process.outputStream.write(replies.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
        }
    }

    private fun hostUtility(name: String): String =
        listOfNotNull(File(shell).parentFile, File("/system/bin"), File("/usr/bin"), File("/bin"))
            .map { File(it, name) }.firstOrNull(File::canExecute)?.path ?: File(File(shell).parentFile, name).path

    private enum class WaitOutcome { EXITED, TIMED_OUT, STOPPED }
    private class WaitResult(val outcome: WaitOutcome, val pausedNanos: Long)

    /** 阻塞等待进程结束、超时或用户停止；被系统暂停的时间不消耗超时预算。 */
    private fun await(process: Process, timeoutSeconds: Int, control: BuiltinCommandControl, excludePauses: Boolean): WaitResult {
        val step = TimeUnit.MILLISECONDS.toNanos(pollMillis)
        val pauseThreshold = TimeUnit.MILLISECONDS.toNanos(pauseThresholdMillis)
        var remaining = TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var budget = timeoutSeconds
        var consumed = 0L
        var paused = 0L
        var last = clockNanos()
        while (true) {
            val desired = control.timeoutSecondsOverride ?: timeoutSeconds
            if (desired != budget) { remaining += TimeUnit.SECONDS.toNanos((desired - budget).toLong()); budget = desired }
            if (control.stopRequested) return WaitResult(WaitOutcome.STOPPED, paused)
            if (process.waitFor(minOf(step, remaining.coerceAtLeast(1)), TimeUnit.NANOSECONDS)) {
                return WaitResult(WaitOutcome.EXITED, paused)
            }
            val now = clockNanos()
            val elapsed = (now - last).coerceAtLeast(0)
            last = now
            val excess = elapsed - step
            val charged = if (excludePauses && excess > pauseThreshold) {
                paused += excess
                step
            } else elapsed
            consumed += charged
            remaining -= charged
            val threshold = control.yieldAfterSeconds
            if (threshold > 0 && !control.yielded.isCompleted && consumed >= TimeUnit.SECONDS.toNanos(threshold.toLong())) {
                // 先在同一计时循环中扩大预算，再唤醒前台等待者，等值边界不会先被判成超时。
                val expanded = control.yieldedTimeoutSeconds
                remaining += TimeUnit.SECONDS.toNanos((expanded - budget).toLong())
                budget = expanded
                control.timeoutSecondsOverride = expanded
                control.yielded.complete(Unit)
            }
            if (remaining <= 0) {
                return if (control.stopRequested) WaitResult(WaitOutcome.STOPPED, paused) else WaitResult(WaitOutcome.TIMED_OUT, paused)
            }
        }
    }

    private suspend fun terminate(process: Process, pidFile: File, fullGrace: Boolean = false) {
        val group = readProcessGroup(pidFile) ?: awaitProcessGroup(pidFile)
        if (group == null) {
            process.destroyForcibly()
            runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
            return
        }
        runCatching { signaller.signal(group, SIGTERM) }
        val started = System.nanoTime()
        val exited = runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
        if (fullGrace) kotlinx.coroutines.delay((killGraceMillis - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
        runCatching { signaller.signal(group, SIGKILL) }
        if (!exited) {
            process.destroyForcibly()
            runInterruptible { process.waitFor(killGraceMillis, TimeUnit.MILLISECONDS) }
        }
    }

    /** 命令刚启动就超时或取消时，包装 sh 可能尚未写出进程组号。 */
    private suspend fun awaitProcessGroup(pidFile: File): Int? {
        repeat(10) {
            readProcessGroup(pidFile)?.let { return it }
            runInterruptible { Thread.sleep(50) }
        }
        return null
    }

    private fun identityToken(pid:Int):String? = processIdentity(pid)?.let { start ->
        listOfNotNull(bootIdentity()?.takeIf{it.isNotBlank()}, start).joinToString("\n")
    }

    private fun readProcessGroup(pidFile: File): Int? =
        runCatching { pidFile.readText().trim().toInt() }.getOrNull()?.takeIf { it > 1 }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread({ runCatching(body) }, name).apply { isDaemon = true; start() }

    companion object {
        const val OUTPUT_LIMIT = 32_000
        const val MAX_TIMEOUT_SECONDS = 600
        const val SIGKILL = 9
        const val SIGTERM = 15
        private const val SIGNAL_EXIT_BASE = 128
        /** $1 为进程组号文件，$4/$5/$6 为宿主 cat/mv/printf；写入失败时不执行命令。 */
        private const val WRAPPER = "echo \$\$ > \"\$1\" || exit 125; " +
            "if [ -r /proc/\$\$/stat ]; then " +
            "__infinity_stat=\$(\"\$4\" /proc/\$\$/stat) || exit 125; __infinity_fields=\${__infinity_stat##*) }; " +
            "(__infinity_printf=\$6; set -- \$__infinity_fields; case \"\${20}\" in ''|*[!0-9]*) exit 125;; esac; \"\$__infinity_printf\" '%s' \"\${20}\") > \"\$1.start\" || exit 125; " +
            "{ [ -z \"\$2\" ] || \"\$6\" '%s\\n' \"\$2\"; \"\$4\" \"\$1.start\"; } > \"\$1.identity.tmp\" && \"\$5\" \"\$1.identity.tmp\" \"\$1.identity\" || exit 125; " +
            "elif [ \"\$3\" = 1 ]; then exit 125; fi; shift 6; exec \"\$@\""
    }
}

/** 按 UTF-16 计数并只保留尾部；解码在读取线程完成，多字节字符不会在块边界被拆开。 */
internal class TailTextCollector(
    private val limit: Int,
    private val listener: ((String) -> Unit)? = null,
    private val transform: (String) -> String = { it },
    private val finishTransform: () -> String = { "" },
) {
    private val buffer = StringBuilder()
    @Volatile var totalChars: Long = 0L
        private set

    val truncated: Boolean get() = totalChars > limit

    fun consume(input: InputStream) {
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val chunk = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                val text = transform(String(chunk, 0, count))
                append(text.toCharArray(), text.length)
                listener?.let { notify -> runCatching { notify(text) } }
            }
            val rest = finishTransform()
            append(rest.toCharArray(), rest.length)
            if (rest.isNotEmpty()) listener?.invoke(rest)
        }
    }

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        buffer.appendRange(chars, 0, count)
        totalChars += count
        if (buffer.length > limit * 2) buffer.delete(0, buffer.length - limit)
    }

    @Synchronized
    fun text(): String {
        if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
        return if (truncated && buffer.isNotEmpty() && Character.isLowSurrogate(buffer[0])) buffer.substring(1) else buffer.toString()
    }
}

/** 同一开机周期与进程启动时钟共同识别进程，避免重启恢复时误杀复用的 PID。 */
private fun readLinuxProcessStartIdentity(pid: Int): String? = runCatching {
    val fields = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')
    if (fields[2].toInt() != pid) return@runCatching null
    fields[19]
}.getOrNull()
