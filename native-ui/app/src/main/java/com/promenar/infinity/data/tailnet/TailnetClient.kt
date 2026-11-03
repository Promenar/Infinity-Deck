package com.promenar.infinity.data.tailnet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 子进程句柄；测试以内存管道实现。 */
interface TailnetProcessHandle {
    /** 子进程的 stdin。 */
    val input: OutputStream
    /** 子进程的 stdout。 */
    val output: InputStream
    val isAlive: Boolean
    fun waitFor(timeoutMillis: Long): Boolean
    fun destroyForcibly()
}

fun interface TailnetProcessLauncher {
    @Throws(IOException::class)
    fun launch(): TailnetProcessHandle
}

/**
 * 以空白环境启动 libnxtailnet.so：不继承主进程环境（其中没有、也不应传入 TS_AUTHKEY 等变量），
 * argv 只有可执行文件路径；auth key 与代理凭据只经 stdin 的 start 请求传入。stderr 读出后丢弃。
 */
class AndroidTailnetProcessLauncher(
    private val executable: File,
    private val homeDirectory: File,
    private val tempDirectory: File,
) : TailnetProcessLauncher {
    override fun launch(): TailnetProcessHandle {
        if (!executable.isFile) throw IOException("内置 Tailscale 未打包")
        homeDirectory.mkdirs()
        tempDirectory.mkdirs()
        val builder = ProcessBuilder(executable.path).directory(homeDirectory)
        builder.environment().apply {
            clear()
            put("HOME", homeDirectory.path)
            put("TMPDIR", tempDirectory.path)
        }
        val process = builder.start()
        thread(isDaemon = true, name = "infinity-tailnet-stderr") {
            runCatching {
                process.errorStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (stream.read(buffer) >= 0) {
                        // 丢弃 stderr：子进程不输出秘密，但也不需要记录。
                    }
                }
            }
        }
        return object : TailnetProcessHandle {
            override val input: OutputStream = process.outputStream
            override val output: InputStream = process.inputStream
            override val isAlive: Boolean get() = process.isAlive
            override fun waitFor(timeoutMillis: Long): Boolean = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun destroyForcibly() {
                process.destroyForcibly()
            }
        }
    }
}

/**
 * 一个 libnxtailnet.so 子进程的 JSON 行协议客户端。stdout 由守护线程逐行读取；
 * 进程退出（stdout 结束）时所有未完成的请求以 [IOException] 结束，并回调 [onExit]。
 * 无法识别的输出行直接丢弃，不写日志。
 */
class TailnetClient(
    private val process: TailnetProcessHandle,
    private val onState: (TailnetStatusSummary) -> Unit,
    private val onExit: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<TailnetMessage.Response>>()
    private val writer: BufferedWriter = process.input.bufferedWriter(Charsets.UTF_8)
    private val exited = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        thread(isDaemon = true, name = "infinity-tailnet-stdout") { readLoop() }
    }

    val isExited: Boolean get() = exited.get()

    private fun readLoop() {
        try {
            process.output.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when (val message = TailnetCodec.decode(line)) {
                        is TailnetMessage.Response -> pending.remove(message.id)?.complete(message)
                        is TailnetMessage.State -> runCatching { onState(message.state) }
                        TailnetMessage.Unknown -> Unit
                    }
                }
            }
        } catch (_: IOException) {
            // 管道被关闭：按进程退出处理。
        } finally {
            exited.set(true)
            val failure = IOException("内置 Tailscale 已退出")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
            runCatching { onExit() }
        }
    }

    /** 发送请求并等待应答；失败应答抛出 [TailnetCommandException]，进程退出或超时抛出 [IOException]。 */
    suspend fun call(
        op: String,
        start: TailnetStartOptions? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): JsonElement? {
        if (exited.get() || closed.get()) throw IOException("内置 Tailscale 未运行")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<TailnetMessage.Response>()
        pending[id] = deferred
        try {
            val line = TailnetCodec.encode(TailnetRequest(id = id, op = op, start = start))
            withContext(ioDispatcher) {
                synchronized(writer) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
            if (exited.get()) deferred.completeExceptionally(IOException("内置 Tailscale 已退出"))
            val response = try {
                withTimeout(timeoutMillis) { deferred.await() }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw IOException("内置 Tailscale 响应超时")
            }
            if (!response.ok) throw TailnetCommandException(response.error ?: TailnetErrors.INTERNAL, response.message)
            return response.result
        } finally {
            pending.remove(id)
        }
    }

    suspend fun <T> call(serializer: KSerializer<T>, op: String, start: TailnetStartOptions? = null, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): T {
        val result = call(op, start = start, timeoutMillis = timeoutMillis) ?: throw IOException("内置 Tailscale 应答缺少结果")
        return runCatching { TailnetCodec.json.decodeFromJsonElement(serializer, result) }
            .getOrElse { throw IOException("内置 Tailscale 应答格式错误") }
    }

    suspend fun start(options: TailnetStartOptions): TailnetStartResult =
        call(TailnetStartResult.serializer(), TailnetOps.START, start = options)

    suspend fun status(): TailnetStatusSummary = call(TailnetStatusSummary.serializer(), TailnetOps.STATUS)

    suspend fun peers(): List<TailnetPeer> = call(TailnetPeersResult.serializer(), TailnetOps.PEERS).peers

    suspend fun login() {
        call(TailnetOps.LOGIN, timeoutMillis = LOGIN_TIMEOUT_MS)
    }

    suspend fun logout() {
        call(TailnetOps.LOGOUT, timeoutMillis = LOGIN_TIMEOUT_MS)
    }

    /**
     * 关闭 stdin（子进程据此下线并退出），最多等待 [graceMillis]，仍未退出则强制结束。阻塞调用，须在 IO 线程。
     */
    fun close(graceMillis: Long = CLOSE_GRACE_MS) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { synchronized(writer) { writer.close() } }
        val exitedInTime = runCatching { process.waitFor(graceMillis) }.getOrDefault(false)
        if (!exitedInTime) runCatching { process.destroyForcibly() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val LOGIN_TIMEOUT_MS = 35_000L
        const val CLOSE_GRACE_MS = 6_000L
    }
}
