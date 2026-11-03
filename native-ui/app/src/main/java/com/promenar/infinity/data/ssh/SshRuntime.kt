package com.promenar.infinity.data.ssh

import com.promenar.infinity.data.exec.redacted
import com.promenar.infinity.data.exec.retainSecretEnvironment
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

// 连接资源归属于生成任务或用户终端，禁止按模型参数创建任意主机连接。
data class SshExecOutput(
    val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean, val timedOut: Boolean,
    /** 遮蔽后的原始长度（UTF-16）；字节采集已截断时无法得知，为 null。 */
    val stdoutOriginalLength: Long? = stdout.length.toLong(),
    val stderrOriginalLength: Long? = stderr.length.toLong(),
    /** 用户在命令结束前停止并断开了通道；远端进程可能仍在运行。 */
    val stoppedByUser: Boolean = false,
)

/**
 * 在 stdout 与 stderr 之间分配输出上限：两者合计不超限时全部保留；
 * 否则一方不足一半时把余量让给另一方，双方都超过一半时各取一半，避免大量 stdout 挤掉 stderr。
 */
internal fun splitOutputBudget(stdoutLength: Int, stderrLength: Int, limit: Int): Pair<Int, Int> {
    val stderrKeep = minOf(stderrLength, maxOf(limit / 2, limit - stdoutLength))
    return minOf(stdoutLength, limit - stderrKeep) to stderrKeep
}
data class SshFilePage(val content: String, val nextOffset: Long, val eof: Boolean)
data class SshRemoteEntry(val name: String, val size: Long, val directory: Boolean)
data class SshDirectoryPage(val entries: List<SshRemoteEntry>, val truncated: Boolean)
data class SshSessionInfo(val sessionId: String, val host: String)
data class SshSessionOutput(val output: String, val closed: Boolean, val truncated: Boolean)

internal fun secretSshCommand(source: String, environment: Map<String, String>): String =
    environment.entries.joinToString("") { (name, value) ->
        require(com.promenar.infinity.domain.secret.SecretIoLimits.SLOT_NAME.matches(name))
        "export $name='${value.replace("'", "'\\''") }'; "
    } + source

internal fun sshCaptureResult(stdout: String, stderr: String, credentialFilter: (String) -> String): Pair<String, String> =
    stdout to credentialFilter(stderr)

class SshRuntime(
    private val store: SshHostStore,
    private val idleTimeoutMs: Long = 600_000,
    private val onPersistentActivity: (Boolean) -> Unit = {},
    private val uiIdleTimeoutMs: () -> Long? = { null },
    private val liveOutput: ToolLiveOutputStore? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val vault: com.promenar.infinity.data.secret.SessionSecretVault? = null,
    redactor: com.promenar.infinity.data.secret.SecretOutputRedactor? = null,
) : AutoCloseable {
    private val redactor = redactor ?: vault?.let { com.promenar.infinity.data.secret.SecretOutputRedactor(it) }
    internal val hostStore: SshHostStore get() = store
    private class HostSnapshot(val host: SshHost) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HostSnapshot>
    }
    internal suspend fun hostSnapshot(alias: String): SshHost = host(alias)
    internal suspend fun sessionSnapshot(owner: String, id: String): SshHost = terminal(owner, id).connection.host
    internal suspend fun <T> withHostSnapshot(snapshot: SshHost?, operation: suspend () -> T): T =
        if (snapshot == null) operation() else withContext(HostSnapshot(snapshot)) { operation() }
    private data class PoolKey(val owner: String, val alias: String, val purpose: String = "")
    private class Connection(@Volatile var key: PoolKey, val host: SshHost, val session: Session, val filter: SshOutputFilter, time: Long) {
        val connectedAtMillis = System.currentTimeMillis()
        val lock = Mutex()
        @Volatile var used = time
    }
    private class Pty(val id: String, val owner: String, val connection: Connection, val channel: ChannelShell, val input: OutputStream, time: Long, val mirror: ToolLiveOutputSink?) {
        val openedAtMillis = System.currentTimeMillis()
        val liveOutputKey = "ssh_session:$id"
        val buffer = TextRing(65_536)
        val sendLock = Mutex()
        @Volatile var used = time
        var reader: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val connections = ConcurrentHashMap<PoolKey, Connection>()
    private val terminals = ConcurrentHashMap<String, Pty>()
    private val stopped = AtomicBoolean(false)
    private data class OwnedTemporary(val host: SshHost, val path: String)
    private val pendingCleanup = ConcurrentHashMap<String, OwnedTemporary>()
    private val _cleanupWarnings = MutableStateFlow<Set<String>>(emptySet())
    val cleanupWarnings: StateFlow<Set<String>> = _cleanupWarnings.asStateFlow()
    private data class RemoteJob(val sessionId: String, val id: String, val alias: String, val stop: () -> Unit, val timeLimit: () -> Unit = stop)
    private val remoteJobs = ConcurrentHashMap<String, RemoteJob>()
    private data class TransferReturn(val connection: Connection, val agentOwner: String, val control: SshExecControl, val deadlineNanos: Long)
    private val transferReturns = ConcurrentHashMap<String, TransferReturn>()
    private val _activity = MutableStateFlow(SshRuntimeActivity())
    val activity: StateFlow<SshRuntimeActivity> = _activity.asStateFlow()
    val hasPersistentResources: Boolean get() = connections.keys.any { it.owner == "ui" } || _forwards.value.isNotEmpty() || remoteJobs.isNotEmpty()
    private val _forwards = MutableStateFlow<Map<String, Int>>(emptyMap())
    val forwardStates: StateFlow<Map<String, Int>> = _forwards.asStateFlow()
    private val serviceEpoch = AtomicLong()
    private val pausedOwners = ConcurrentHashMap<String, Long>()
    fun registerForegroundService(): Long = serviceEpoch.incrementAndGet()
    private var persistent = false

    init {
        require(idleTimeoutMs > 0)
        scope.launch {
            store.observeHosts().collect {
                gate.withLock {
                    // 等锁期间数据库可能已更新；过期的 Flow 通知不能回收刚创建的新连接。
                    connections.values.toList().forEach { connection ->
                        val current = try { store.find(connection.host.alias)
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { null }
                        if (current != connection.host) drop(connection)
                    }
                    publishPersistent()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay((idleTimeoutMs / 2).coerceIn(25, 5_000))
                gate.withLock {
                    val time = now()
                    connections.values.filter { pausedOwners[it.key.owner]?.let { deadline -> time >= deadline } == true }.forEach(::drop)
                    terminals.values.filter { !it.connection.session.isConnected || it.channel.isClosed || idleExpired(it.owner, it.used, time) }.forEach { pty ->
                        removePty(pty)
                        if (!pty.connection.lock.isLocked && !remoteJobs.containsKey(pty.connection.key.owner) && terminals.values.none { it.connection === pty.connection }) drop(pty.connection)
                    }
                    connections.values.filter { connection ->
                        !connection.session.isConnected || connection.key.owner != FORWARD_OWNER &&
                            !connection.lock.isLocked && idleExpired(connection.key.owner, connection.used, time) && terminals.values.none { it.connection === connection }
                    }.forEach(::drop)
                    pausedOwners.entries.removeIf { time >= it.value }
                    publishPersistent()
                }
            }
        }
    }

    suspend fun probe(alias: String): SshHostKey {
        val host = host(alias)
        val verifier = PinnedSshHostKeys(host, probe = true)
        val client = SshKeyGenerator.configuredClient().apply { setHostKeyRepository(verifier) }
        val session = client.getSession(host.username, host.hostname, host.port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            // 与 connection() 一致：获取 tailnet 主机指纹也经内置 Tailscale 的本机 SOCKS5 代理。
            com.promenar.infinity.data.tailnet.InstalledTailnetRouting.routing.jschProxy(host.hostname)?.let { setProxy(it) }
        }
        return try {
            io({ session.disconnect() }) {
                try { session.connect(CONNECT_TIMEOUT) } catch (_: Exception) { }
                verifier.observed ?: throw SshFailure("CONNECTION_FAILED")
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { session.disconnect() } }
    }

    /**
     * 执行一条非交互命令。[live] 收到按连接凭据遮蔽后的增量输出，只用于展示；用户经 [live] 停止时断开通道，
     * 结果标记 [SshExecOutput.stoppedByUser]。
     */
    suspend fun exec(
        owner: String,
        alias: String,
        command: String,
        timeoutSeconds: Int = 60,
        maxOutputChars: Int = 32_000,
        live: ToolLiveOutputSink = ToolLiveOutputSink.None,
        control: SshExecControl? = null,
        secretSessionId: String? = null,
        captureRaw: Boolean = false,
    ): SshExecOutput {
        SshPolicy.timeout(timeoutSeconds); SshPolicy.outputLimit(maxOutputChars)
        val sessionId = secretSessionId ?: secretSession(owner)
        val environment = try { sessionId?.let { vault?.environment(it, command) }.orEmpty() }
        catch (_: IllegalStateException) { throw SshFailure("SECRET_SLOT_UNAVAILABLE") }
        val releaseSecrets = retainSecretEnvironment(sessionId, environment, redactor)
        try {
        val safeLive = if (captureRaw) ToolLiveOutputSink.None else sessionId?.let { redactor?.sink(it, live) } ?: live
        if (command.isBlank() || command.length > 65_536 || '\u0000' in command) throw SshFailure("INVALID_COMMAND")
        val stdout = ByteCapture(maxOutputChars * 4)
        val stderr = ByteCapture(maxOutputChars * 4)
        val ref = AtomicReference<ChannelExec?>()
        val usedConnection = AtomicReference<Connection?>()
        // 停止只置位，由轮询线程断开通道，界面线程不做网络 IO。
        val stopRequested = control?.stopRequested ?: AtomicBoolean(false)
        val stoppedByUser = AtomicBoolean(false)
        safeLive.onStop { if (control == null) stopRequested.set(true) else control.requestStop() }
        if (stopRequested.get()) return SshExecOutput(-1, "", "", false, false, stoppedByUser = true)
        val tees = mutableListOf<LiveTee>()
        val exit = try {
            withTimeoutOrNull((if (control == null) timeoutSeconds else if (control.background) control.maxRuntimeSeconds else maxOf(timeoutSeconds, control.maxRuntimeSeconds)) * 1000L) {
                withConnection(owner, alias, purpose = "exec") { connection ->
                    usedConnection.set(connection)
                    verifyCurrentConnection(connection)
                    // 排队等待连接锁期间已请求停止，则不再在远端启动命令。
                    if (stopRequested.get()) { stoppedByUser.set(true); return@withConnection -1 }
                    control?.promote = { jobId, sessionId -> promoteExec(connection, control, jobId, sessionId) }
                    io({ ref.get()?.disconnect() }) {
                        val channel = (connection.session.openChannel("exec") as ChannelExec).also(ref::set)
                        try {
                            channel.setCommand(secretSshCommand(command, environment))
                            channel.setInputStream(null)
                            fun sink(capture: ByteCapture, stream: ToolOutputStream): OutputStream =
                                if (captureRaw) capture
                                else {
                                    val filter = sessionId?.let { redactor?.stream(it) }
                                    LiveTee(capture, connection.filter.stream(), stream, safeLive, captureClean = true,
                                        secretFilter = { text, end -> filter?.let { it.accept(text) + if (end) it.finish() else "" } ?: text }).also(tees::add)
                                }
                            channel.setOutputStream(sink(stdout, ToolOutputStream.STDOUT), true)
                            channel.setErrStream(sink(stderr, ToolOutputStream.STDERR), true)
                            channel.connect(CONNECT_TIMEOUT)
                            control?.started?.complete(Unit)
                            val startedAt = now()
                            var expired = false
                            while (!channel.isClosed) {
                                if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
                                if (stopRequested.get()) { stoppedByUser.set(true); break }
                                val limit = if (control?.background == true) control.maxRuntimeSeconds else timeoutSeconds
                                if (now() - startedAt >= limit * 1000L) { expired = true; break }
                                connection.used = now()
                                Thread.sleep(10)
                            }
                            connection.used = now()
                            if (stopRequested.get()) stoppedByUser.set(true)
                            if (expired) null else channel.exitStatus
                        } finally { channel.disconnect(); tees.forEach(LiveTee::finish) }
                    }
                }
            }
        } catch (failure: Throwable) { stdout.wipe(); stderr.wipe(); throw failure
        } finally {
            control?.finished?.set(true)
            redactor?.finish(safeLive)
            withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() }
        }
        return io {
            try {
                val rawOut = stdout.text()
                val rawErr = stderr.text()
                val (filteredOut, filteredErr) = if (captureRaw) sshCaptureResult(rawOut, rawErr) {
                    usedConnection.get()?.filter?.whole(it) ?: ""
                } else rawOut to rawErr
                val cleanOut = if (captureRaw) filteredOut else sessionId?.let { redactor?.redact(it, filteredOut) } ?: filteredOut
                val cleanErr = sessionId?.let { redactor?.redact(it, filteredErr) } ?: filteredErr
                // 遮蔽依赖完整前文识别凭据与私钥块，因此截断在遮蔽之后进行，并保留开头。
                val (outKeep, errKeep) = splitOutputBudget(cleanOut.length, cleanErr.length, maxOutputChars)
                val out = cleanOut.take(outKeep)
                val err = cleanErr.take(errKeep)
                SshExecOutput(
                    exit ?: -1, out, err,
                    stdout.truncated || stderr.truncated || out.length < cleanOut.length || err.length < cleanErr.length,
                    exit == null,
                    stdoutOriginalLength = cleanOut.length.toLong().takeUnless { stdout.truncated },
                    stderrOriginalLength = cleanErr.length.toLong().takeUnless { stderr.truncated },
                    stoppedByUser = stoppedByUser.get(),
                )
            } finally { stdout.wipe(); stderr.wipe() }
        }
        } finally { releaseSecrets() }
    }

    /**
     * 流式执行：远端 stdout 原样写入 [stdout]（可为二进制，例如 git 的 pack），[stdin] 读到结尾时向远端发送 EOF。
     * stderr 先在内存中收集（上限 64 KiB），按连接的凭据遮蔽后在命令结束时一次写入 [stderr]。
     * 远端 channel 建立后调用 [onStarted]；返回远端退出码（远端未报告时为 -1）。
     * 不设整体超时，由调用方取消协程结束；取消时断开 channel，连接仍留在池中按空闲回收。
     */
    suspend fun execStreaming(
        owner: String,
        alias: String,
        command: String,
        stdin: java.io.InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        environment: Map<String, String> = emptyMap(),
        onStarted: () -> Unit = {},
    ): Int {
        if (command.isBlank() || command.length > 65_536 || '\u0000' in command) throw SshFailure("INVALID_COMMAND")
        val ref = AtomicReference<ChannelExec?>()
        val errors = ByteCapture(65_536)
        var usedConnection: Connection? = null
        try {
            val exit = withConnection(owner, alias) { connection ->
                usedConnection = connection
                verifyCurrentConnection(connection)
                io({ ref.get()?.disconnect() }) {
                    val channel = (connection.session.openChannel("exec") as ChannelExec).also(ref::set)
                    try {
                        channel.setCommand(command)
                        environment.forEach { (name, value) -> channel.setEnv(name, value) }
                        channel.setInputStream(stdin, true)
                        channel.setOutputStream(stdout, true)
                        channel.setErrStream(errors, true)
                        channel.connect(CONNECT_TIMEOUT)
                        onStarted()
                        while (!channel.isClosed) { if (stopped.get()) throw SshFailure("RUNTIME_CLOSED"); Thread.sleep(10) }
                        connection.used = now()
                        channel.exitStatus
                    } finally { channel.disconnect() }
                }
            }
            val text = errors.text()
            if (text.isNotEmpty()) {
                val bytes = checkNotNull(usedConnection).filter.whole(text).toByteArray(Charsets.UTF_8)
                try { withContext(Dispatchers.IO) { stderr.write(bytes); stderr.flush() } } finally { bytes.fill(0) }
            }
            return exit
        } finally {
            errors.wipe()
            withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() }
        }
    }

    internal fun redactOutput(sessionId: String, value: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
        redactor?.let { com.promenar.infinity.data.exec.redactCommandJson(sessionId, value, it) } ?: value

    private fun secretSession(owner: String): String? = when {
        owner.startsWith("agent:") -> owner.removePrefix("agent:")
        owner.startsWith("job:") -> remoteJobs[owner]?.sessionId
        else -> null
    }

    suspend fun capture(
        owner: String, alias: String, command: String, timeoutSeconds: Int,
        sessionId: String, capture: com.promenar.infinity.data.secret.SecretCapture,
        control: SshExecControl? = null,
    ): com.promenar.infinity.domain.tool.CommandToolResult {
        val secrets = vault ?: return com.promenar.infinity.domain.tool.CommandToolResult(errorCode = "SECRET_VAULT_UNAVAILABLE", guidance = "本会话保密库不可用，命令未执行。")
        val result = exec(owner, alias, command, timeoutSeconds,
            com.promenar.infinity.domain.secret.SecretIoLimits.MAX_SECRET_BYTES + 1, control = control,
            secretSessionId = sessionId, captureRaw = true).commandResult(timeoutSeconds)
        return capture.complete(sessionId, secrets, result) { path ->
            readSecretFile(owner, alias, path)
        }.redacted(sessionId, redactor)
    }

    suspend fun readSecretFile(owner: String, alias: String, path: String): ByteArray = sftp(owner, alias, path) { channel, _ ->
        val attributes = channel.lstat(path)
        val limit = com.promenar.infinity.domain.secret.SecretIoLimits.MAX_SECRET_BYTES
        if (!attributes.isReg || attributes.size > limit) throw SshFailure("SECRET_CAPTURE_FILE_INVALID")
        channel.get(path).use { input ->
            val buffer = ByteArray(limit + 1)
            try {
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > limit) throw SshFailure("SECRET_TOO_LARGE")
                buffer.copyOf(count)
            } finally { buffer.fill(0) }
        }
    }

    suspend fun list(owner: String, alias: String, path: String): SshDirectoryPage = sftp(owner, alias, path) { channel, connection ->
        val entries = mutableListOf<SshRemoteEntry>()
        var truncated = false
        channel.ls(path) { entry ->
            if (entries.size >= 512) { truncated = true; ChannelSftp.LsEntrySelector.BREAK }
            else {
                if (entry.filename != "." && entry.filename != "..") {
                    entries.add(SshRemoteEntry(connection.filter.whole(entry.filename.take(4096)), entry.attrs.size, entry.attrs.isDir))
                }
                ChannelSftp.LsEntrySelector.CONTINUE
            }
        }
        SshDirectoryPage(entries, truncated)
    }

    /** offset/limit 使用 UTF-16；扫描完整有界文本，任何位置出现二进制均拒绝。 */
    suspend fun read(owner: String, alias: String, path: String, offset: Long = 0, limit: Int = 16_384): SshFilePage {
        if (offset < 0 || offset > MAX_FILE_BYTES || limit !in 2..65_536) throw SshFailure("INVALID_READ_RANGE")
        return sftp(owner, alias, path) { channel, connection ->
            val attributes = channel.stat(path)
            if (attributes.isDir || attributes.size > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
            val result = StringBuilder()
            var position = 0L
            var readChars = 0L
            val filter = connection.filter.stream()
            fun collect(value: String) {
                value.forEach { char ->
                    if (position == offset && Character.isLowSurrogate(char)) throw SshFailure("INVALID_READ_RANGE")
                    if (position >= offset && result.length < limit) result.append(char)
                    position++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                InputStreamReader(channel.get(path), decoder).use { reader ->
                    val buffer = CharArray(4096)
                    try {
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            readChars += count
                            if (readChars > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
                            for (index in 0 until count) {
                                val char = buffer[index]
                                if (char.code < 32 && char != '\n' && char != '\r' && char != '\t' || char == '\u007f') throw SshFailure("BINARY_FILE")
                            }
                            collect(filter.feed(String(buffer, 0, count)))
                        }
                        collect(filter.feed("", end = true))
                    } finally { buffer.fill('\u0000') }
                }
            } catch (failure: SshFailure) { throw failure
            } catch (_: java.nio.charset.CharacterCodingException) { throw SshFailure("BINARY_FILE") }

            if (offset > position) throw SshFailure("INVALID_READ_RANGE")
            if (result.isNotEmpty() && Character.isHighSurrogate(result.last())) result.setLength(result.length - 1)
            val next = offset + result.length
            SshFilePage(result.toString(), next, next >= position)
        }
    }

    suspend fun write(owner: String, alias: String, path: String, content: String, expectedSha256: String? = null): String {
        if (content.length > 1_048_576) throw SshFailure("WRITE_TOO_LARGE")
        if (expectedSha256 != null && !Regex("[0-9a-fA-F]{64}").matches(expectedSha256)) throw SshFailure("INVALID_SHA256")
        if (pendingCleanup.size >= 128) throw SshFailure("CLEANUP_BACKLOG_LIMIT")
        val temporary = "$path.infinity-${UUID.randomUUID()}.tmp"
        var used: Connection? = null
        var published = false
        try {
            return sftp(owner, alias, path) { channel, connection ->
                used = connection
                val old = try { channel.lstat(path) } catch (failure: com.jcraft.jsch.SftpException) {
                    if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw failure
                }
                if (old != null && (old.isLink || old.isDir)) throw SshFailure("UNSUPPORTED_WRITE_TARGET")
                if (expectedSha256 != null) {
                    if (old == null || !fileHash(channel, path).equals(expectedSha256, ignoreCase = true)) throw SshFailure("SHA256_CONFLICT")
                }
                val bytes = content.toByteArray(Charsets.UTF_8)
                pendingCleanup[temporary] = OwnedTemporary(connection.host, temporary)
                try {
                    // 空文件阶段即收紧权限，认证用户之外的读者不能观察上传中的正文。
                    channel.put(ByteArrayInputStream(byteArrayOf()), temporary, ChannelSftp.OVERWRITE)
                    channel.chmod(384, temporary)
                    if (old != null) {
                        val temporaryAttributes = channel.stat(temporary)
                        if (temporaryAttributes.uId != old.uId) channel.chown(old.uId, temporary)
                        if (temporaryAttributes.gId != old.gId) channel.chgrp(old.gId, temporary)
                    }
                    channel.put(ByteArrayInputStream(bytes), temporary, ChannelSftp.OVERWRITE)
                    if (old != null) channel.chmod(old.permissions and 4095, temporary)
                    // SFTP 没有跨客户端原子 CAS，发布前复核能收窄外部写入窗口。
                    if (expectedSha256 != null && !fileHash(channel, path).equals(expectedSha256, ignoreCase = true)) throw SshFailure("SHA256_CONFLICT")
                    channel.rename(temporary, path)
                    published = true
                    pendingCleanup.remove(temporary)
                    sha(bytes)
                } finally { bytes.fill(0) }
            }
        } finally {
            if (!published && pendingCleanup.containsKey(temporary)) {
                withContext(NonCancellable + Dispatchers.IO) { used?.let { cleanupTemporary(it, temporary) } }
            }
        }
    }

    private suspend fun cleanupTemporary(connection: Connection, path: String) {
        val ref = AtomicReference<ChannelSftp?>()
        val cleaned = withTimeoutOrNull(5_000) {
            try {
                io({ ref.get()?.disconnect() }) {
                    val channel = (connection.session.openChannel("sftp") as ChannelSftp).also(ref::set)
                    try {
                        channel.connect(5_000)
                        try { channel.rm(path) } catch (failure: com.jcraft.jsch.SftpException) {
                            if (failure.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw failure
                        }
                    } finally { channel.disconnect() }
                }
                true
            } catch (_: Exception) { false }
        } == true
        ref.get()?.disconnect()
        if (cleaned) pendingCleanup.remove(path)
        _cleanupWarnings.value = pendingCleanup.values.mapTo(mutableSetOf()) { it.host.alias }
    }

    /** 只清理记录中同一主机身份、同一目标路径派生的临时名；换指纹后不跨身份删除。 */
    internal suspend fun recoverTransferTemporary(alias: String, target: String, path: String, identity: String): Boolean {
        if (!path.startsWith("$target.infinity-") || !Regex("[0-9a-f-]{36}\\.tmp").matches(path.removePrefix("$target.infinity-"))) return false
        val snapshot = hostSnapshot(alias)
        if (snapshot.executionIdentity().digest != identity) return false
        return withHostSnapshot(snapshot) {
            withConnection("sftp-cleanup", alias) { connection ->
                pendingCleanup[path] = OwnedTemporary(snapshot, path)
                cleanupTemporary(connection, path)
                !pendingCleanup.containsKey(path)
            }
        }
    }

    suspend fun transferStat(owner: String, alias: String, path: String): SftpRemoteStat? = sftp(owner, alias, path) { channel, _ ->
        transferAttributes(channel, path)?.let { SftpRemoteStat(it.size, it.mTime, it.isReg && !it.isLink) }
    }

    /** 二进制传输独占文件连接；停止后等待 IO 清理结束才向调用方报告终态。 */
    suspend fun transfer(
        owner: String, alias: String, remotePath: String, local: SftpLocalFile, upload: Boolean,
        overwrite: Boolean, control: SshExecControl,
        approvedLocal: SftpLocalExpectation? = null, approvedRemote: SftpRemoteExpectation? = null,
        agentOwner: String? = null,
        verifySnapshot: ((SftpLocalStat?, SftpRemoteStat?) -> Unit)? = null,
        onTemporary: suspend (String?) -> Unit = {},
        progress: suspend (Long, Long, Long?) -> Unit = { _, _, _ -> },
    ): SftpTransferResult = withContext(Dispatchers.IO) {
        SshPolicy.path(remotePath)
        val startedAt = System.nanoTime()
        val trace = currentCoroutineContext()[SftpTransferTrace] ?: SftpTransferTrace().apply { workerStarted() }
        trace.next("stat_verify")
        val localBefore = local.stat()
        if (approvedLocal != null && localBefore != approvedLocal.stat) throw SshFailure("TARGET_CONFLICT")
        if (upload && verifySnapshot == null) requireSftpSize(localBefore?.size ?: throw SshFailure("LOCAL_NOT_FOUND"))
        if (!upload && localBefore != null && !overwrite) throw SshFailure("TARGET_CONFLICT")
        if (pendingCleanup.size >= 128) throw SshFailure("CLEANUP_BACKLOG_LIMIT")
        trace.next("connection")
        withTransferConnection(owner, alias, agentOwner, control, startedAt + control.maxRuntimeSeconds * 1_000_000_000L, trace) { connection ->
            trace.next("stat_verify")
            verifyCurrentConnection(connection)
            trace.next("sftp_open")
            val channel = connection.session.openChannel("sftp") as ChannelSftp
            var temporary: String? = null
            val committed = AtomicReference<SftpTransferResult?>()
            fun result(bytes: Long, hash: String) = SftpTransferResult(bytes, hash, (System.nanoTime() - startedAt) / 1_000_000_000.0)
            val worker = scope.async(Dispatchers.IO) {
                try {
                    channel.connect(CONNECT_TIMEOUT)
                    control.started.complete(Unit)
                    val workerContext = currentCoroutineContext()
                    fun checkTransfer() {
                        workerContext.ensureActive()
                        if (control.stopRequested.get()) throw SshFailure(control.stopCause.get() ?: "STOPPED")
                        if ((System.nanoTime() - startedAt) / 1_000_000_000L >= control.maxRuntimeSeconds) throw SshFailure("TIMEOUT")
                        if (!channel.isConnected) throw SshFailure("CONNECTION_FAILED")
                    }
                    trace.next("stat_verify")
                    val old = transferAttributes(channel, remotePath)
                    val remoteBefore = old?.let { SftpRemoteStat(it.size, it.mTime, it.isReg && !it.isLink) }
                    if (approvedRemote != null && remoteBefore != approvedRemote.stat) throw SshFailure("TARGET_CONFLICT")
                    // 审批字段使用这次 LSTAT，在任何本地或远端写入之前完整比对。
                    verifySnapshot?.invoke(localBefore, remoteBefore)
                    if (old != null && (old.isDir || old.isLink || !old.isReg)) throw SshFailure("UNSUPPORTED_TRANSFER_TARGET")
                    val size = if (upload) localBefore?.size ?: throw SshFailure("LOCAL_NOT_FOUND") else old?.size ?: throw SshFailure("REMOTE_NOT_FOUND")
                    requireSftpSize(size)
                    checkTransfer()
                    var lastBytes = 0L
                    var lastTime = System.nanoTime()
                    suspend fun report(bytes: Long, force: Boolean = false) {
                        val time = System.nanoTime()
                        val delta = time - lastTime
                        if (force || delta >= 500_000_000L || bytes - lastBytes >= 1024 * 1024) {
                            val speed = if (delta > 0 && bytes > lastBytes) ((bytes - lastBytes) * 1_000_000_000L / delta) else null
                            progress(size, bytes, speed)
                            lastBytes = bytes; lastTime = time
                        }
                    }
                    report(0, true)
                    val notify: (Long) -> Unit = { bytes ->
                        if (bytes - lastBytes >= 1024 * 1024 || System.nanoTime() - lastTime >= 500_000_000L)
                            kotlinx.coroutines.runBlocking { report(bytes) }
                    }
                    val hash = if (upload) {
                        if (old != null && !overwrite) throw SshFailure("TARGET_CONFLICT")
                        if (old == null) {
                            // 新目标仍核对父目录类型：OPEN 能穿过父目录符号链接，不能代替 LSTAT 的拒绝语义。
                            val parent = remotePath.substringBeforeLast('/', ".").ifEmpty { "/" }
                            val attributes = transferAttributes(channel, parent)
                            if (attributes == null || !attributes.isDir) throw SshFailure("REMOTE_PARENT_MISSING")
                        }
                        val oldHash = old?.let { channel.get(remotePath).use { sftpHash(it, old.size, ::checkTransfer) } }
                        val path = "$remotePath.infinity-${UUID.randomUUID()}.tmp"
                        temporary = path
                        onTemporary(path)
                        pendingCleanup[path] = OwnedTemporary(connection.host, path)
                        checkTransfer()
                        trace.next("data_transfer")
                        val output = channel.put(path)
                        val sourceHash = output.use {
                            // JSch 2.28.8 首次 write 才初始化 ACK 计数，此时没有待确认写请求。
                            channel.chmod(384, path)
                            checkTransfer()
                            local.read { input -> copySftpBytes(input, it, size, ::checkTransfer, notify) }
                        }
                        trace.next("verify_readback")
                        if (local.stat() != localBefore) throw SshFailure("SOURCE_CHANGED")
                        val targetHash = channel.get(path).use { sftpHash(it, size, ::checkTransfer) }
                        if (targetHash != sourceHash) throw SshFailure("CHECKSUM_MISMATCH")
                        trace.next("publish")
                        if (old != null) {
                            val current = transferAttributes(channel, remotePath)
                            if (current == null || !current.isReg || current.isLink || current.size != old.size || current.mTime != old.mTime ||
                                channel.get(remotePath).use { sftpHash(it, old.size, ::checkTransfer) } != oldHash) throw SshFailure("TARGET_CONFLICT")
                            channel.chmod(old.permissions and 4095, path)
                        }
                        checkTransfer()
                        verifyCurrentConnection(connection)
                        if (old != null) channel.rename(path, remotePath)
                        else {
                            // hardlink 的新名字必须不存在，省去新建目标的发布前 LSTAT。
                            if (channel.getExtension("hardlink@openssh.com") != "1") throw SshFailure("ATOMIC_PUBLISH_UNSUPPORTED")
                            try { channel.hardlink(path, remotePath) } catch (failure: com.jcraft.jsch.SftpException) {
                                if (transferAttributes(channel, remotePath) != null) throw SshFailure("TARGET_CONFLICT")
                                throw failure
                            }
                        }
                        // 发布后立即登记；清理和最终进度期间收到停止仍返回提交结果。
                        committed.set(result(size, sourceHash))
                        if (old != null) { pendingCleanup.remove(path); temporary = null }
                        if (temporary == null) withContext(NonCancellable) { onTemporary(null) }
                        sourceHash
                    } else {
                        trace.next("data_transfer")
                        local.write(size, overwrite, localBefore, ::checkTransfer,
                            beforeCommit = { trace.next("publish") },
                            onCommitted = { hash -> committed.set(result(size, hash)) }) { output ->
                            val sourceHash = channel.get(remotePath).use { input -> copySftpBytes(input, output, size, ::checkTransfer, notify) }
                            trace.next("stat_verify")
                            val after = transferAttributes(channel, remotePath)
                            if (after == null || !after.isReg || after.isLink || after.size != old!!.size || after.mTime != old.mTime) throw SshFailure("SOURCE_CHANGED")
                            checkTransfer()
                            trace.next("verify_readback")
                            sourceHash
                        }
                    }
                    report(size, true)
                    committed.get() ?: result(size, hash)
                } finally {
                    trace.next("cleanup")
                    control.finished.set(true)
                    temporary?.let { path ->
                        // 首先复用传输通道；断网时保留清理登记，由同一主机的下次连接处理。
                        if (runCatching { channel.rm(path) }.isSuccess) pendingCleanup.remove(path)
                    }
                    channel.disconnect()
                    connection.used = now()
                    withContext(NonCancellable) {
                        temporary?.takeIf(pendingCleanup::containsKey)?.let { cleanupTemporary(connection, it) }
                        if (temporary != null && !pendingCleanup.containsKey(temporary)) onTemporary(null)
                    }
                    trace.next(null)
                }
            }
            control.cancelPending = { worker.cancel(); scope.launch(Dispatchers.IO) { channel.disconnect() }; Unit }
            control.promote = { _, _ -> if (control.finished.get()) false else { control.background = true; true } }
            val watcher = scope.launch(Dispatchers.IO) {
                while (!worker.isCompleted) {
                    if (control.stopRequested.get() || (System.nanoTime() - startedAt) / 1_000_000_000L >= control.maxRuntimeSeconds) {
                        worker.cancel()
                        channel.disconnect()
                        break
                    }
                    delay(50)
                }
            }
            try {
                worker.await()
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    channel.disconnect()
                    worker.cancelAndJoin()
                }
                committed.get()?.let { published ->
                    committedSftpResult(published, failure)?.let { return@withTransferConnection it }
                    throw classifySftpFailure(failure as? Exception ?: Exception())
                }
                if (control.stopRequested.get()) throw SshFailure(control.stopCause.get() ?: "STOPPED")
                if ((System.nanoTime() - startedAt) / 1_000_000_000L >= control.maxRuntimeSeconds) throw SshFailure("TIMEOUT")
                if (failure is CancellationException) throw failure
                throw classifySftpFailure(failure as? Exception ?: Exception())
            } finally {
                withContext(NonCancellable) { watcher.cancelAndJoin() }
                control.cancelPending = null
            }
        }.let { it.copy(timingsMs = trace.snapshot(), connectionSource = trace.connectionSource) }
    }

    private fun transferAttributes(channel: ChannelSftp, path: String): com.jcraft.jsch.SftpATTRS? =
        try { channel.lstat(path) } catch (failure: com.jcraft.jsch.SftpException) {
            if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw failure
        }

    suspend fun openSession(owner: String, alias: String, columns: Int = 80, rows: Int = 24): SshSessionInfo = withContext(Dispatchers.IO) {
        size(columns, rows)
        withConnection(owner, alias) { connection ->
            try { gate.withLock {
                // 连接锁覆盖 PTY 创建，不能在创建过程中被任务转交。
                verifyCurrentConnection(connection)
                if (owner == "ui") terminals.values.firstOrNull { it.owner == owner && it.connection === connection && !it.channel.isClosed }?.let {
                    return@withLock SshSessionInfo(it.id, alias)
                }
                if (terminals.size >= 8) throw SshFailure("SESSION_LIMIT")
                val ref = AtomicReference<ChannelShell?>()
                var created: Pty? = null
                try {
                    val pty = io({ ref.get()?.disconnect() }) {
                        val channel = (connection.session.openChannel("shell") as ChannelShell).also(ref::set)
                        try {
                            channel.setPty(true)
                            channel.setPtyType("xterm-256color", columns, rows, 0, 0)
                            val output = channel.inputStream
                            val input = channel.outputStream
                            channel.connect(CONNECT_TIMEOUT)
                            val id = UUID.randomUUID().toString()
                            val mirror = if (owner.startsWith("agent:")) liveOutput?.open("ssh_session:$id")?.also {
                                // 激活只读槽，不登记停止回调，误发的停止请求不会影响远端 PTY。
                                it.replace(ToolOutputStream.STDOUT, "")
                            } else null
                            Pty(id, owner, connection, channel, input, now(), mirror).also { pty ->
                                created = pty
                                val filter = connection.filter.stream()
                                val secretFilter = secretSession(owner)?.let { redactor?.stream(it) }
                                pty.reader = scope.launch {
                                    try {
                                        InputStreamReader(output, Charsets.UTF_8).use { reader ->
                                            val chars = CharArray(4096)
                                            try {
                                                while (isActive) {
                                                    val count = reader.read(chars)
                                                    if (count < 0) break
                                                    val filtered = filter.feed(String(chars, 0, count))
                                                    val clean = secretFilter?.accept(filtered) ?: filtered
                                                    pty.buffer.append(clean)
                                                    pty.mirror?.emit(ToolOutputStream.STDOUT, clean)
                                                    pty.used = now()
                                                    connection.used = pty.used
                                                }
                                            } finally { chars.fill('\u0000') }
                                        }
                                    } catch (_: Exception) { } finally {
                                        val filtered = filter.feed("", end = true)
                                        val tail = secretFilter?.let { it.accept(filtered) + it.finish() } ?: filtered
                                        pty.buffer.append(tail)
                                        pty.mirror?.emit(ToolOutputStream.STDOUT, tail)
                                        pty.buffer.closed = true
                                        pty.mirror?.let { liveOutput?.close(pty.liveOutputKey, it) }
                                        scope.launch { gate.withLock { publishPersistent() } }
                                    }
                                }
                            }
                        } catch (failure: Exception) { channel.disconnect(); throw failure }
                    }
                    terminals[pty.id] = pty
                    publishPersistent()
                    SshSessionInfo(pty.id, alias)
                } catch (failure: Throwable) {
                    created?.let { removePty(it) }
                    withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() }
                    if (terminals.values.none { it.connection === connection }) drop(connection)
                    throw failure
                }
            }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    gate.withLock {
                        if (terminals.values.none { it.connection === connection }) drop(connection)
                        publishPersistent()
                    }
                }
                throw failure
            }
        }
    }

    suspend fun sendSession(owner: String, id: String, input: String) {
        if (input.length > 65_536) throw SshFailure("INPUT_TOO_LARGE")
        val pty = terminal(owner, id)
        pty.sendLock.withLock {
            verifyCurrentConnection(pty.connection)
            io({ pty.channel.disconnect() }) {
                pty.input.write(input.toByteArray(Charsets.UTF_8)); pty.input.flush()
                pty.used = now()
                pty.connection.used = pty.used
            }
        }
    }
    suspend fun readSession(owner: String, id: String, waitMs: Int = 0): SshSessionOutput {
        if (waitMs !in 0..5_000) throw SshFailure("INVALID_WAIT")
        val pty = terminal(owner, id)
        val start = now()
        while (!pty.buffer.hasData() && !pty.buffer.closed && now() - start < waitMs) delay(minOf(25L, (waitMs - (now() - start)).coerceAtLeast(1)))
        val output = pty.buffer.drain()
        return output.copy(output = secretSession(owner)?.let { redactor?.redact(it, output.output) } ?: output.output)
    }
    suspend fun resizeSession(owner: String, id: String, columns: Int, rows: Int) {
        size(columns, rows)
        val pty = terminal(owner, id)
        io({ pty.channel.disconnect() }) { pty.channel.setPtySize(columns, rows, 0, 0) }
    }
    suspend fun closeSession(owner: String, id: String) = withContext(Dispatchers.IO) { gate.withLock {
        val pty = terminals[id]?.takeIf { it.owner == owner } ?: return@withLock
        removePty(pty)
        if (terminals.values.none { it.connection === pty.connection } && !remoteJobs.containsKey(pty.connection.key.owner)) drop(pty.connection)
        publishPersistent()
    }
    }
    fun sessionHost(owner: String, id: String): String? = terminals[id]?.takeIf { it.owner == owner }?.connection?.host?.alias
    suspend fun closeOwner(owner: String) = withContext(Dispatchers.IO) { gate.withLock {
        terminals.values.filter { it.owner == owner }.forEach(::removePty)
        connections.values.filter { it.key.owner == owner }.forEach(::drop)
        pausedOwners.remove(owner)
        publishPersistent()
    }
    }
    suspend fun startForward(alias: String): Int = withContext(Dispatchers.IO) {
        val connection = connection(FORWARD_OWNER, alias)
        val host = connection.host
        try { gate.withLock {
            val port = host.localForwardPort ?: run { drop(connection); throw SshFailure("FORWARD_NOT_CONFIGURED") }
            val targetPort = host.forwardPort ?: run { drop(connection); throw SshFailure("FORWARD_NOT_CONFIGURED") }
            if (host(alias) != host) { drop(connection); throw SshFailure("HOST_CONFIGURATION_CHANGED") }
            _forwards.value[alias]?.let { return@withLock it }
            try {
                val bound = io({ connection.session.disconnect() }) { connection.session.setPortForwardingL("127.0.0.1", port, host.forwardHost, targetPort, ForwardServerSocketFactory) }
                _forwards.value = _forwards.value + (alias to bound)
                publishPersistent()
                bound
            } catch (failure: Throwable) { drop(connection); publishPersistent(); throw failure }
        }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                gate.withLock {
                    if (!_forwards.value.containsKey(alias)) drop(connection)
                    publishPersistent()
                }
            }
            throw failure
        }
    }
    suspend fun stopForward(alias: String) = withContext(Dispatchers.IO) { gate.withLock {
        connections[PoolKey(FORWARD_OWNER, alias)]?.let(::drop)
        publishPersistent()
    }
    }
    suspend fun closeForwards() = closeOwner(FORWARD_OWNER)
    suspend fun closePersistent(expectedEpoch: Long, timeLimit: Boolean = false) = withContext(Dispatchers.IO) { gate.withLock {
        if (expectedEpoch != serviceEpoch.get()) return@withLock
        disconnectPersistentLocked(timeLimit)
    } }
    suspend fun pauseOwner(owner: String) {
        if (connections.keys.any { it.owner == owner }) pausedOwners[owner] = now() + idleTimeoutMs else pausedOwners.remove(owner)
    }
    suspend fun resumeOwner(owner: String) { pausedOwners.remove(owner) }
    override fun close() {
        stopped.set(true)
        connections.values.toList().forEach(::drop)
        scope.cancel()
        runCatching { publishPersistent() }
    }

    private fun now() = monotonicMillis()
    private fun idleExpired(owner: String, used: Long, time: Long): Boolean {
        if (owner == FORWARD_OWNER || remoteJobs.containsKey(owner)) return false
        val timeout = if (owner == "ui") uiIdleTimeoutMs() else idleTimeoutMs
        return timeout != null && time - used >= timeout
    }

    /** 等待执行锁后重新核对归属；先前命令让出时，后续操作必须取得新的 Agent 连接。 */
    private suspend fun <T> withConnection(owner: String, alias: String, purpose: String = "",
        source: (Boolean) -> Unit = {}, block: suspend (Connection) -> T): T {
        while (true) {
            val connection = connection(owner, alias, purpose, source)
            var locked = false
            while (connection.key.owner == owner && connections[connection.key] === connection) {
                if (connection.lock.tryLock()) { locked = true; break }
                // 只检查内存中的归属，等待期间不反复查询主机数据库。
                delay(25)
            }
            if (!locked) continue
            try {
                if (connection.key.owner == owner && connections[connection.key] === connection) return block(connection)
            } finally { connection.lock.unlock() }
        }
    }

    /** 仅借用同一会话的空闲文件槽；持锁改键后，Agent 回收与 PTY 创建均不能触及任务。 */
    private suspend fun <T> withTransferConnection(owner: String, alias: String, agentOwner: String?, control: SshExecControl,
        deadlineNanos: Long, trace: SftpTransferTrace, block: suspend (Connection) -> T): T {
        val returnOwner = agentOwner?.takeIf { it.startsWith("agent:") && owner.startsWith("job:") &&
            remoteJobs[owner]?.sessionId == it.removePrefix("agent:") }
        val key = PoolKey(owner, alias, "sftp-transfer")
        val borrowed = if (returnOwner == null) null else gate.withLock {
            val current = host(alias)
            val candidate = connections[PoolKey(returnOwner, alias)]
            if (control.stopRequested.get()) throw SshFailure(control.stopCause.get() ?: "STOPPED")
            if (connections[key] != null || candidate == null || candidate.host != current || !candidate.session.isConnected ||
                terminals.values.any { it.connection === candidate } || !candidate.lock.tryLock()) return@withLock null
            try {
                connections.remove(candidate.key, candidate)
                candidate.key = key
                connections[key] = candidate
                publishPersistent()
                candidate
            } catch (failure: Throwable) { drop(candidate); candidate.lock.unlock(); throw failure }
        }
        suspend fun use(connection: Connection): T {
            var succeeded = false
            try {
                return block(connection).also { succeeded = true }
            } finally {
                withContext(NonCancellable) {
                    gate.withLock {
                        if (succeeded && returnOwner != null && !control.stopRequested.get() && System.nanoTime() - deadlineNanos < 0 &&
                            connection.session.isConnected && connections[key] === connection && remoteJobs.containsKey(owner)) {
                            // finishJob 在同一个 gate 内检查停止状态并归还，消除归还与服务停止之间的窗口。
                            transferReturns[owner] = TransferReturn(connection, returnOwner, control, deadlineNanos)
                        } else if (!succeeded || control.stopRequested.get() || System.nanoTime() - deadlineNanos >= 0) {
                            drop(connection)
                            publishPersistent()
                        }
                    }
                }
            }
        }
        if (borrowed != null) {
            trace.connectionSource = "transferred"
            try { return use(borrowed) } finally { borrowed.lock.unlock() }
        }
        return withConnection(owner, alias, "sftp-transfer", { created -> trace.connectionSource = if (created) "new" else "reused" }) { use(it) }
    }

    internal suspend fun registerJob(jobId: String, sessionId: String, alias: String, stop: () -> Unit, timeLimit: () -> Unit = stop) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
            remoteJobs["job:$jobId"] = RemoteJob(sessionId, jobId, alias, stop, timeLimit)
            try { publishPersistent() } catch (failure: Throwable) { remoteJobs.remove("job:$jobId"); throw failure }
        }
    }

    private suspend fun promoteExec(connection: Connection, control: SshExecControl, jobId: String, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            if (control.finished.get() || control.stopRequested.get() || !connection.session.isConnected || connections[connection.key] !== connection) return@withLock false
            val key = PoolKey("job:$jobId", connection.host.alias, "exec")
            remoteJobs[key.owner] = RemoteJob(sessionId, jobId, key.alias, control::requestStop)
            connections.remove(connection.key, connection)
            connection.key = key
            connections[key] = connection
            control.background = true
            publishPersistent()
            true
        }
    }

    /** exec 使用独立用途槽，结束后台任务不会触及 Agent 的 PTY 与文件通道。 */
    internal suspend fun finishJob(jobId: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            val owner = "job:$jobId"
            transferReturns.remove(owner)?.let { lease ->
                val connection = lease.connection
                val agentKey = PoolKey(lease.agentOwner, connection.host.alias)
                val current = try { store.find(connection.host.alias) } catch (_: Exception) { null }
                if (!stopped.get() && !lease.control.stopRequested.get() && System.nanoTime() - lease.deadlineNanos < 0 &&
                    connection.session.isConnected && current == connection.host &&
                    connections[connection.key] === connection && !connection.lock.isLocked && connections[agentKey] == null &&
                    terminals.values.none { it.connection === connection }) {
                    connections.remove(connection.key, connection)
                    connection.key = agentKey
                    connection.used = now()
                    connections[agentKey] = connection
                }
            }
            remoteJobs.remove(owner)
            connections.values.filter { it.key.owner == owner }.forEach(::drop)
            publishPersistent()
        }
    }

    suspend fun disconnect(owner: String, alias: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            remoteJobs[owner]?.takeIf { it.alias == alias }?.stop?.invoke()
            connections.values.filter { it.key.owner == owner && it.host.alias == alias }.forEach(::drop)
            publishPersistent()
        }
    }

    suspend fun disconnectAll() = withContext(Dispatchers.IO) { gate.withLock { disconnectPersistentLocked() } }
    private fun disconnectPersistentLocked(timeLimit: Boolean = false) {
        remoteJobs.values.forEach { if (timeLimit) it.timeLimit() else it.stop() }
        connections.values.filter { it.key.owner == "ui" || it.key.owner == FORWARD_OWNER || remoteJobs.containsKey(it.key.owner) }.forEach(::drop)
        publishPersistent()
    }

    private suspend fun host(alias: String): SshHost {
        SshPolicy.alias(alias)
        if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
        return try {
            val current = store.find(alias) ?: throw SshFailure("HOST_NOT_FOUND")
            val approved = currentCoroutineContext()[HostSnapshot]?.host
            if (approved != null && current != approved) throw SshFailure("HOST_CONFIGURATION_CHANGED")
            current
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("HOST_UNAVAILABLE") }
    }
    private suspend fun connection(owner: String, alias: String, purpose: String = "", source: (Boolean) -> Unit = {}): Connection = withContext(Dispatchers.IO) {
        val host = host(alias)
        gate.withLock {
            if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
            if (host(alias) != host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
            val key = PoolKey(owner, alias, purpose)
            connections[key]?.let {
                if (it.host == host && it.session.isConnected) { it.used = now(); source(false); return@withLock it }
                drop(it)
            }
            if (connections.size >= 8) throw SshFailure("CONNECTION_LIMIT")
            val client = SshKeyGenerator.configuredClient()
            val verifier = PinnedSshHostKeys(host)
            client.setHostKeyRepository(verifier)
            val session = client.getSession(host.username, host.hostname, host.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setServerAliveInterval(15_000)
                setServerAliveCountMax(3)
                setDaemonThread(true)
                // tailnet 主机经内置 Tailscale 的本机 SOCKS5 代理（主机名由代理端解析）；其它主机直连不变。
                com.promenar.infinity.data.tailnet.InstalledTailnetRouting.routing.jschProxy(host.hostname)?.let { setProxy(it) }
            }
            var outputFilter: SshOutputFilter? = null
            try {
                source(true)
                io({ session.disconnect() }) {
                    val credential = store.credential(host) ?: throw SshFailure("CREDENTIAL_MISSING")
                    try {
                        when (host.authType) {
                            SshAuthType.PASSWORD -> {
                                if (credential.password.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                                session.setConfig("PreferredAuthentications", "password"); session.setPassword(credential.password)
                            }
                            SshAuthType.KEY -> {
                                if (credential.privateKey.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                                session.setConfig("PreferredAuthentications", "publickey")
                                SshPolicy.validatePrivateKey(credential.privateKey)
                                client.addIdentity("infinity", credential.privateKey, null, credential.passphrase)
                            }
                        }
                        try { session.connect(CONNECT_TIMEOUT)
                        } catch (failure: Exception) {
                            throw classifySshFailure(failure, verifier.rejection, "AUTH_OR_CONNECTION_FAILED")
                        }
                        outputFilter = SshOutputFilter(credential)
                    } finally { credential.close(); session.setPassword(null as ByteArray?); client.removeAllIdentity() }
                }
                if (host(alias) != host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
                Connection(key, host, session, requireNotNull(outputFilter), now()).also { connection ->
                    connections[key] = connection
                    pendingCleanup.values.filter { it.host == host }.forEach { cleanupTemporary(connection, it.path) }
                    publishPersistent()
                }
            } catch (failure: Throwable) {
                session.disconnect(); outputFilter?.close()
                connections[key]?.takeIf { it.session === session }?.let { connections.remove(key, it) }
                runCatching { publishPersistent() }
                throw failure
            }
        }
    }
    private suspend fun terminal(owner: String, id: String): Pty {
        val pty = terminals[id]?.takeIf { it.owner == owner } ?: throw SshFailure("SESSION_NOT_FOUND")
        if (host(pty.connection.host.alias) != pty.connection.host) {
            closeSession(owner, id)
            throw SshFailure("HOST_CONFIGURATION_CHANGED")
        }
        return pty
    }
    private suspend fun verifyCurrentConnection(connection: Connection) {
        if (host(connection.host.alias) != connection.host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
    }
    private suspend fun <T> sftp(owner: String, alias: String, path: String, block: (ChannelSftp, Connection) -> T): T {
        SshPolicy.path(path)
        val ref = AtomicReference<ChannelSftp?>()
        // 结果本身可以为 null（远端不存在），先装箱再判超时，避免把“不存在”误报成 TIMEOUT。
        return try {
            (withTimeoutOrNull(60_000) {
                withConnection(owner, alias) { connection ->
                    verifyCurrentConnection(connection)
                    io({ ref.get()?.disconnect() }) {
                        val channel = (connection.session.openChannel("sftp") as ChannelSftp).also(ref::set)
                        try { channel.connect(CONNECT_TIMEOUT); SftpCompleted(block(channel, connection))
                        } finally { channel.disconnect(); connection.used = now() }
                    }
                }
            } ?: throw SshFailure("TIMEOUT")).value
        } finally { withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() } }
    }
    private fun fileHash(channel: ChannelSftp, path: String): String {
        val attributes = channel.stat(path)
        if (attributes.isDir || attributes.size > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        channel.get(path).use { input ->
            val bytes = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    count += read
                    if (count > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
                    digest.update(bytes, 0, read)
                }
            } finally { bytes.fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun removePty(pty: Pty) {
        terminals.remove(pty.id, pty)
        pty.channel.disconnect(); pty.reader?.cancel(); pty.buffer.clear()
        pty.mirror?.let { liveOutput?.close(pty.liveOutputKey, it) }
    }
    private fun drop(connection: Connection) {
        connections.remove(connection.key, connection)
        terminals.values.filter { it.connection === connection }.forEach(::removePty)
        connection.session.disconnect()
        connection.filter.close()
        if (connection.key.owner == FORWARD_OWNER) _forwards.value = _forwards.value - connection.host.alias
    }
    private fun publishPersistent() {
        _activity.value = SshRuntimeActivity(
            connections.values.filter { it.session.isConnected }.map { connection ->
                val owner = connection.key.owner
                val job = remoteJobs[owner]
                SshActiveConnection(owner, connection.host.alias,
                    when { job != null -> SshConnectionKind.JOB; owner == "ui" -> SshConnectionKind.TERMINAL; owner == FORWARD_OWNER -> SshConnectionKind.FORWARD; else -> SshConnectionKind.AGENT },
                    job?.sessionId ?: owner.takeIf { it.startsWith("agent:") }?.removePrefix("agent:"),
                    job?.id, connection.connectedAtMillis)
            }.groupBy { it.owner to it.alias }.values.map { rows -> rows.minBy { it.connectedAtMillis } }
                .sortedWith(compareBy({ it.connectedAtMillis }, { it.owner }, { it.alias })),
            terminals.values.filter { it.owner.startsWith("agent:") && !it.buffer.closed }.map {
                SshAgentPtySession(it.id, it.owner.removePrefix("agent:"), it.connection.host.alias, it.liveOutputKey, it.openedAtMillis)
            }.sortedBy { it.openedAtMillis },
        )
        val active = hasPersistentResources
        if (persistent == active) return
        try { onPersistentActivity(active); persistent = active } catch (_: Exception) { throw SshFailure("FOREGROUND_SERVICE_UNAVAILABLE") }
    }
    private suspend fun <T> io(onCancel: () -> Unit = {}, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val worker = scope.launch {
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value) else onCancel()
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) continuation.resumeWithException(cancelled)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(classifySshFailure(failure))
            }
        }
        worker.invokeOnCompletion { if (worker.isCancelled) continuation.cancel(CancellationException("SSH_CANCELLED")) }
        continuation.invokeOnCancellation {
            CoroutineScope(NonCancellable + Dispatchers.IO).launch { runCatching(onCancel) }
            worker.cancel()
        }
    }
    private fun size(columns: Int, rows: Int) { if (columns !in 2..500 || rows !in 2..200) throw SshFailure("INVALID_TERMINAL_SIZE") }
    private companion object {
        const val CONNECT_TIMEOUT = 15_000
        const val MAX_FILE_BYTES = 16_777_216L
        const val FORWARD_OWNER = "forward"
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private class ByteCapture(private val limit: Int) : OutputStream() {
    private val bytes = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
    @Volatile var truncated = false
        private set
    @Synchronized override fun write(value: Int) { if (bytes.size() < limit) bytes.write(value) else truncated = true }
    @Synchronized override fun write(value: ByteArray, offset: Int, length: Int) {
        val size = minOf(length, limit - bytes.size())
        if (size > 0) bytes.write(value, offset, size)
        if (size < length) truncated = true
    }
    @Synchronized fun text(): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE)
        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    @Synchronized fun wipe() = bytes.wipe()
}

/**
 * 通道输出同时写入采集缓冲，并按连接凭据遮蔽后推送到界面；跨分片的 UTF-8 字节与可能的凭据前缀留到下一片。
 * 推送失败不影响采集。
 */
private class LiveTee(
    private val capture: ByteCapture,
    private val filter: SshOutputFilter.Stream,
    private val stream: ToolOutputStream,
    private val sink: ToolLiveOutputSink,
    private val captureClean: Boolean = false,
    private val secretFilter: (String, Boolean) -> String = { text, _ -> text },
) : OutputStream() {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private var finished = false

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Synchronized override fun write(value: ByteArray, offset: Int, length: Int) {
        if (!captureClean) capture.write(value, offset, length)
        if (finished) return
        val input = java.nio.ByteBuffer.wrap(pending + value.copyOfRange(offset, offset + length))
        val chars = java.nio.CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, chars, false)
        pending.fill(0)
        pending = ByteArray(input.remaining()).also(input::get)
        input.array().fill(0)
        chars.flip()
        publish(secretFilter(filter.feed(chars.toString()), false))
    }

    @Synchronized fun finish() {
        if (finished) return
        finished = true
        publish(secretFilter(filter.feed("", end = true), true))
        pending.fill(0)
    }

    private fun publish(text: String) {
        if (text.isNotEmpty()) {
            if (captureClean) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                try { capture.write(bytes) } finally { bytes.fill(0) }
            }
            runCatching { sink.emit(stream, text) }
        }
    }
}

private class TextRing(private val limit: Int) {
    private val text = StringBuilder()
    @Volatile var closed = false
    private var truncated = false
    @Synchronized fun append(value: String) {
        text.append(value)
        if (text.length > limit) { text.delete(0, text.length - limit); truncated = true }
    }
    @Synchronized fun hasData() = text.isNotEmpty()
    @Synchronized fun drain(): SshSessionOutput = SshSessionOutput(text.toString(), closed, truncated).also { text.setLength(0); truncated = false }
    @Synchronized fun clear() { for (index in text.indices) text.setCharAt(index, '\u0000'); text.setLength(0); closed = true }
}

/** SFTP 调用已完成的标记；value 允许为 null。 */
private class SftpCompleted<T>(val value: T)
