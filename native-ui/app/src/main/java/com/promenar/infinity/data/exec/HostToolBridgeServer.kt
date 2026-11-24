package com.promenar.infinity.data.exec

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import com.promenar.infinity.data.git.remote.IsolatedSocketServer
import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.LinuxResourceGovernor
import com.promenar.infinity.data.linux.LinuxToolBridgeConfig
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

internal interface HostToolConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    val peerUid: Int
    fun headerTimeout(millis: Int)
}

internal interface HostToolListener : Closeable {
    fun accept(): HostToolConnection
}

class HostToolBridgeServer internal constructor(
    private val cache: File,
    private val tools: PackagedHostTools,
    private val git: GitHostBroker,
    private val launch: (BuiltinCommandRequest) -> HostToolProcess,
    private val currentUid: () -> Int = Os::getuid,
    private val bind: (File) -> HostToolListener = AndroidToolListener::open,
) : Closeable {
    private val lock = Any()
    private val jobs = mutableMapOf<String, JobLease>()
    private val random = SecureRandom()
    private var socketServer: IsolatedSocketServer<HostToolConnection>? = null
    private var closed = false

    val available: Boolean get() = tools.isBridgeShimAvailable && tools.availableTools().isNotEmpty()
    val gitAvailable: Boolean get() = tools.isGitAvailable

    internal fun openJobLease(
        mounts: HostToolPathTranslator,
        gitLease: Map<String, String>,
        budget: LinuxResourceBudget,
        builtin: Boolean = false,
    ): JobLease = synchronized(lock) {
        check(!closed) { "宿主工具桥已关闭。" }
        check(available) { "宿主工具 shim 未打包。" }
        val socket = startLocked()
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val bin = LinuxToolBridgeConfig.prepareBin(cache, tools, builtin).takeIf { builtin }
        JobLease(nonce, socket, bin, mounts, gitLease.toMap(), budget).also { jobs[nonce] = it }
    }

    internal fun tryOpenJobLease(
        mounts: () -> HostToolPathTranslator,
        gitLease: Map<String, String>,
        budget: LinuxResourceBudget,
        builtin: Boolean = false,
    ): JobLease? {
        if (!available) return null
        return try { openJobLease(mounts(), gitLease, budget, builtin) } catch (failure: Exception) {
            logger.warning("宿主工具桥未能启动（${failure.javaClass.simpleName}）。")
            null
        }
    }

    private fun startLocked(): File {
        val socket = LinuxToolBridgeConfig.socket(cache)
        if (socketServer != null) return socket
        LinuxToolBridgeConfig.requireSocketPath(socket)
        LinuxToolBridgeConfig.prepareBridgeDirectory(cache)
        val listener = bind(socket)
        socketServer = IsolatedSocketServer(
            "infinity-tools", 12,
            accept = {
                // 未通过凭据检查的连接从不进入协议处理线程。
                while (true) {
                    val client = listener.accept()
                    if (runCatching { client.peerUid == currentUid() }.getOrDefault(false)) {
                        return@IsolatedSocketServer client
                    }
                    runCatching { client.close() }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IOException("宿主工具监听已关闭。")
            },
            stopAccepting = listener::close,
            handle = ::serve,
            reject = { reply(DataOutputStream(it.output), "TOOL_BUSY", "宿主工具连接已满。") },
        ).also { it.start() }
        return socket
    }

    inner class JobLease internal constructor(
        private val nonce: String,
        val socket: File,
        val builtinBin: File?,
        internal val mounts: HostToolPathTranslator,
        internal val gitLease: Map<String, String>,
        internal val budget: LinuxResourceBudget,
    ) : Closeable {
        private val processes = linkedMapOf<HostToolProcess, HostToolConnection>()
        private var closing: CompletableFuture<Unit>? = null

        val environment: Map<String, String> get() = buildMap {
            put(LinuxToolBridgeConfig.ENV_NONCE, nonce)
            if (builtinBin != null) put(LinuxToolBridgeConfig.ENV_SOCKET, socket.absolutePath)
        }

        @Synchronized
        internal fun start(request: BuiltinCommandRequest, client: HostToolConnection): HostToolProcess {
            requireHost(closing == null, "TOOL_LEASE", "宿主工具任务租约已失效。")
            return launch(request).also { processes[it] = client }
        }

        @Synchronized
        internal fun isRevoked(): Boolean = closing != null

        @Synchronized
        internal fun release(process: HostToolProcess) {
            if (closing == null && process in processes) {
                process.release()
                processes.remove(process)
            }
        }

        override fun close() {
            val result = CompletableFuture<Unit>()
            val active = synchronized(this) {
                val previous = closing
                if (previous == null) {
                    closing = result
                    processes.toMap().also { processes.clear() }
                } else null
            }
            if (active == null) {
                synchronized(this) { checkNotNull(closing) }.join()
                return
            }
            synchronized(lock) { jobs.remove(nonce, this) }
            try {
                active.keys.forEach { it.signal(15) }
                active.values.forEach { runCatching { it.close() } }
                if (active.isNotEmpty()) {
                    // 所有进程组共享一次宽限，所有关闭调用都等待同一回收结果。
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                    var interrupted = false
                    while (System.nanoTime() < deadline) {
                        try { TimeUnit.NANOSECONDS.sleep((deadline - System.nanoTime()).coerceAtLeast(0)) }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                    val failures = active.keys.mapNotNull { runCatching(it::release).exceptionOrNull() }
                    if (interrupted) Thread.currentThread().interrupt()
                    failures.firstOrNull()?.let { throw IOException("宿主工具租约回收未完成。", it) }
                }
                result.complete(Unit)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private fun serve(client: HostToolConnection) {
        val input = DataInputStream(BufferedInputStream(client.input))
        val output = DataOutputStream(client.output)
        val (lease, process, timeoutSeconds) = try {
            client.headerTimeout(5_000)
            val request = readHeader(input)
            client.headerTimeout(0)
            val lease = synchronized(lock) { jobs[request.nonce] }
                ?: throw HostToolException("TOOL_LEASE", "宿主工具任务租约已失效。")
            val executable = tools.availableTools()[request.tool]
                ?: throw HostToolException("TOOL_UNAVAILABLE", "该宿主工具未打包。")
            val command = if (request.tool == "git") git.request(lease.mounts, request.cwd, request.argv, lease.gitLease,
                lease.budget, MAX_TOOL_SECONDS, request.environment) else {
                val translated = lease.mounts.translate(request.tool, request.cwd, request.argv, lease.budget.effectiveThreads)
                val environment = hostToolEnvironment(request.environment).toMutableMap()
                lease.budget.applyTo(environment)
                BuiltinCommandRequest(listOf(executable.absolutePath) + translated.arguments, translated.directory,
                    environment, timeoutSeconds = MAX_TOOL_SECONDS, memoryWatch = { LinuxResourceGovernor.watch(lease.budget.jobMemoryBudgetBytes) })
            }
            Triple(lease, lease.start(command, client), command.timeoutSeconds)
        } catch (denied: HostToolException) {
            reply(output, denied.code, denied.message.orEmpty())
            return
        } catch (_: IOException) {
            reply(output, "TOOL_IO", "宿主工具请求或进程启动失败。")
            return
        } catch (_: IllegalArgumentException) {
            reply(output, "TOOL_ARGUMENT", "宿主工具请求或受管配置无效。")
            return
        }
        try {
            reply(output)
            relay(client, input, output, process, timeoutSeconds, lease::isRevoked) { lease.release(process) }
        } finally {
            lease.release(process)
        }
    }

    private fun relay(
        client: HostToolConnection, input: DataInputStream, output: DataOutputStream, child: HostToolProcess,
        timeoutSeconds: Int, revoked: () -> Boolean, settle: () -> Unit,
    ) {
        val process = child.process
        val disconnected = AtomicBoolean()
        fun fail() {
            disconnected.set(true)
            runCatching { client.close() }
        }
        fun worker(name: String, body: () -> Unit) = Thread({
            try { body() }
            catch (_: IOException) { fail() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }, name).apply { isDaemon = true; start() }

        fun send(type: Int, bytes: ByteArray, size: Int = bytes.size) = synchronized(output) {
            output.writeByte(type)
            output.writeInt(size)
            output.write(bytes, 0, size)
            output.flush()
        }
        fun pump(type: Int, stream: InputStream) = worker("infinity-tools-output-$type") {
            stream.use {
                val bytes = ByteArray(MAX_FRAME)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    send(type, bytes, count)
                }
            }
        }
        val stdout = pump(1, process.inputStream)
        val stderr = pump(2, process.errorStream)
        // 正文写入可阻塞，控制帧读线程不能永久等待子进程读取 stdin。
        val stdin = ArrayBlockingQueue<ByteArray>(16)
        val inputPipe = worker("infinity-tools-stdin") {
            try {
                process.outputStream.use { stream ->
                    while (true) {
                        val bytes = stdin.take()
                        if (bytes.isEmpty()) break
                        stream.write(bytes)
                        stream.flush()
                    }
                }
            } catch (_: IOException) {
                // 子进程可在 EOF 前关闭 stdin。控制帧与其输出仍可继续。
                stdin.clear()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val incoming = worker("infinity-tools-input") {
            fun enqueue(payload: ByteArray) {
                if (inputPipe.isAlive && !stdin.offer(payload, 250, TimeUnit.MILLISECONDS) && inputPipe.isAlive) {
                    throw IOException("宿主工具 stdin 持续阻塞。")
                }
            }
            var eof = false
            while (true) {
                val type = input.readUnsignedByte()
                val size = input.readInt()
                if (size !in 0..MAX_FRAME) throw IOException("宿主工具帧长度无效。")
                val payload = ByteArray(size).also(input::readFully)
                when (type) {
                    0 -> if (!eof && payload.isNotEmpty()) enqueue(payload)
                    4 -> {
                        if (size != 0) throw IOException("EOF 帧长度无效。")
                        eof = true
                        enqueue(byteArrayOf())
                    }
                    5 -> {
                        if (size != 4) throw IOException("信号帧长度无效。")
                        val signal = ByteBuffer.wrap(payload).int
                        if (signal !in setOf(1, 2, 15)) throw IOException("信号不受支持。")
                        child.signal(signal)
                    }
                    6 -> if (size != 4) throw IOException("窗口尺寸帧长度无效。")
                    else -> throw IOException("未知宿主工具帧。")
                }
            }
        }
        val start = System.nanoTime()
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (revoked()) return
                if (disconnected.get() || child.memoryExceeded() ||
                    System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())) {
                    child.signal(15)
                    Thread.sleep(300)
                    break
                }
            }
            if (revoked()) return
            // 首进程已退出时先终止同组残留，避免它们持续持有输出管道。
            child.signal(9)
            stdout.join(1_000)
            stderr.join(1_000)
            if (stdout.isAlive || stderr.isAlive) {
                fail()
            } else if (!disconnected.get()) {
                val exit = if (process.waitFor(1, TimeUnit.SECONDS)) process.exitValue() else 137
                settle()
                if (!revoked()) send(3, ByteBuffer.allocate(4).putInt(exit).array())
            }
        } finally {
            inputPipe.interrupt()
            incoming.interrupt()
        }
    }

    override fun close() {
        val (server, leases) = synchronized(lock) {
            if (closed) return
            closed = true
            (socketServer to jobs.values.toList()).also { socketServer = null }
        }
        try {
            val failures = leases.mapNotNull { runCatching(it::close).exceptionOrNull() }
            failures.firstOrNull()?.let { throw IOException("宿主工具监听关闭时回收未完成。", it) }
        } finally {
            server?.close()
        }
    }

    private data class Request(val nonce: String, val tool: String, val cwd: String, val argv: List<String>, val environment: Map<String, String>)

    companion object {
        private val logger = Logger.getLogger(HostToolBridgeServer::class.java.name)
        private const val MAX_TOOL_SECONDS = 24 * 60 * 60
        private const val MAX_HEADER = 256 * 1024
        private const val MAX_FRAME = 65_536

        private fun readHeader(input: DataInputStream): Request {
            val header = ByteArrayOutputStream()
            val start = System.nanoTime()
            while (header.size() < MAX_HEADER) {
                requireHost(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
                    "TOOL_HEADER", "宿主工具请求首行超时。")
                val byte = input.read()
                if (byte < 0) throw EOFException()
                if (byte == 10) return parseHeader(header.toByteArray())
                header.write(byte)
            }
            throw HostToolException("TOOL_HEADER", "宿主工具请求超过 256 KiB。")
        }

        private fun parseHeader(bytes: ByteArray): Request {
            try {
                val json = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()).jsonObject
                require(json.keys == setOf("v", "nonce", "tool", "cwd", "argv", "env", "tty"))
                require(json.getValue("v").jsonPrimitive.let { !it.isString && it.int == 1 })
                fun string(value: JsonElement): String = value.jsonPrimitive.let {
                    require(it.isString && '\u0000' !in it.content)
                    it.content
                }
                val tty = json.getValue("tty").jsonObject
                require(tty.keys == setOf("in", "out", "err", "cols", "rows"))
                for (key in listOf("in", "out", "err")) require(tty.getValue(key).jsonPrimitive.let { !it.isString && it.booleanOrNull != null })
                for (key in listOf("cols", "rows")) require(tty.getValue(key).jsonPrimitive.let { !it.isString && it.int in 0..65535 })
                return Request(string(json.getValue("nonce")), string(json.getValue("tool")), string(json.getValue("cwd")),
                    json.getValue("argv").jsonArray.map(::string), json.getValue("env").jsonObject.mapValues { string(it.value) })
            } catch (_: Exception) {
                throw HostToolException("TOOL_HEADER", "宿主工具请求格式无效。")
            }
        }

        private fun reply(output: DataOutputStream, code: String? = null, message: String = "") {
            val json = buildJsonObject {
                put("ok", code == null)
                if (code != null) {
                    put("code", code)
                    put("message", message)
                }
            }
            output.write("$json\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}

private class AndroidToolListener(
    private val path: File,
    private val identity: Pair<Long, Long>,
    private val bound: LocalSocket,
    private val listener: LocalServerSocket,
    private val ownership: FileChannel,
    private val ownershipLock: FileLock,
) : HostToolListener {
    override fun accept(): HostToolConnection {
        val client = listener.accept()
        return object : HostToolConnection {
            override val input: InputStream get() = client.inputStream
            override val output: OutputStream get() = client.outputStream
            override val peerUid: Int get() = client.peerCredentials.uid
            override fun headerTimeout(millis: Int) { client.soTimeout = millis }
            override fun close() { client.shutdownAndClose() }
        }
    }

    override fun close() {
        bound.shutdownAndClose()
        runCatching { listener.close() }
        try {
            runCatching { if (socketIdentity(path) == identity) Files.delete(path.toPath()) }
        } finally {
            try { ownershipLock.close() } finally { ownership.close() }
        }
    }

    companion object {
        private fun socketIdentity(path: File): Pair<Long, Long> {
            val stat = Os.lstat(path.absolutePath)
            require(android.system.OsConstants.S_ISSOCK(stat.st_mode)) { "宿主工具路径未指向 Unix socket。" }
            return stat.st_dev to stat.st_ino
        }

        fun open(path: File): HostToolListener {
            LinuxToolBridgeConfig.requireSocketPath(path)
            val socketPath = path.toPath()
            val ownership = FileChannel.open(socketPath.resolveSibling("listener.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            val bound = LocalSocket()
            var identity: Pair<Long, Long>? = null
            try {
                val ownershipLock = ownership.tryLock() ?: throw IOException("宿主工具监听已在运行。")
                if (Files.exists(socketPath, NOFOLLOW_LINKS)) {
                    socketIdentity(path)
                    Files.delete(socketPath)
                }
                bound.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                val ownedSocket = socketIdentity(path)
                identity = ownedSocket
                Os.chmod(path.path, 384) // 0600
                return AndroidToolListener(path, ownedSocket, bound, LocalServerSocket(bound.fileDescriptor), ownership, ownershipLock)
            } catch (failure: Exception) {
                bound.shutdownAndClose()
                if (identity != null) runCatching { if (socketIdentity(path) == identity) Files.delete(socketPath) }
                ownership.close()
                throw IOException("宿主工具监听无法启动。", failure)
            }
        }
    }
}

private fun LocalSocket.shutdownAndClose() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}
