package com.promenar.infinity.data.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface BuiltInProxyProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val alive: Boolean
    fun waitFor(timeoutMillis: Long): Boolean
    fun destroyForcibly()
}

fun interface BuiltInProxyProcessLauncher {
    fun launch(): BuiltInProxyProcess
}

/** 凭据只经标准输入传入。进程不继承应用的环境变量，stderr 由系统丢弃。 */
class AndroidBuiltInProxyProcessLauncher(
    private val executable: File,
    private val workingDirectory: File,
) : BuiltInProxyProcessLauncher {
    override fun launch(): BuiltInProxyProcess {
        try {
            check(executable.isFile && (workingDirectory.isDirectory || workingDirectory.mkdirs()))
            val builder = ProcessBuilder(executable.path)
                .directory(workingDirectory)
                .redirectError(File("/dev/null"))
            builder.environment().apply {
                clear()
                put("HOME", workingDirectory.path)
                put("TMPDIR", workingDirectory.path)
            }
            val process = builder.start()
            return object : BuiltInProxyProcess {
                override val stdin get() = process.outputStream
                override val stdout get() = process.inputStream
                override val alive get() = process.isAlive
                override fun waitFor(timeoutMillis: Long) = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                override fun destroyForcibly() { process.destroyForcibly() }
            }
        } catch (_: Exception) {
            throw BuiltInProxyUnavailableException("内置代理进程启动失败")
        }
    }
}

/** 读线程分别派发状态与响应，永远不记录子进程原始数据。 */
internal class BuiltInProxyClient(
    private val process: BuiltInProxyProcess,
    private val onState: (ProxyReplyState) -> Unit = {},
    private val onExit: () -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ProxyMessage.Response>>()
    private val nextId = AtomicLong()
    private val exited = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val writer = process.stdin.bufferedWriter(Charsets.UTF_8)

    val alive: Boolean get() = process.alive && !exited.get() && !closed.get()

    init {
        thread(name = "infinity-proxy-stdout", isDaemon = true) {
            try {
                process.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        when (val message = decodeProxyMessage(line)) {
                            is ProxyMessage.State -> onState(message.value)
                            is ProxyMessage.Response -> pending.remove(message.id)?.complete(message)
                            null -> Unit
                        }
                    }
                }
            } catch (_: IOException) {
                // 子进程结束或管道已关闭。
            } finally {
                exited.set(true)
                pending.values.forEach { it.completeExceptionally(BuiltInProxyUnavailableException("内置代理进程已退出")) }
                pending.clear()
                onExit()
            }
        }
    }

    suspend fun <T> call(
        method: String,
        params: JsonObject,
        serializer: KSerializer<T>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): T {
        val result = call(method, params, timeoutMillis)
        return try {
            proxyJson.decodeFromJsonElement(serializer, result ?: throw IOException())
        } catch (_: Exception) {
            throw BuiltInProxyUnavailableException("内置代理响应格式无效")
        }
    }

    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): JsonElement? = request(method, params, timeoutMillis, allowClosing = false)

    private suspend fun request(method: String, params: JsonObject, timeoutMillis: Long, allowClosing: Boolean): JsonElement? {
        if (exited.get() || (!allowClosing && closed.get())) {
            throw BuiltInProxyUnavailableException("内置代理进程已退出")
        }
        val id = nextId.incrementAndGet()
        val reply = CompletableDeferred<ProxyMessage.Response>()
        pending[id] = reply
        try {
            val response = withTimeoutOrNull(timeoutMillis) {
                val line = proxyJson.encodeToString(ProxyRequest.serializer(), ProxyRequest(id, method, params))
                try {
                    runInterruptible(Dispatchers.IO) {
                        synchronized(writer) {
                            if (exited.get() || (!allowClosing && closed.get())) throw IOException()
                            writer.write(line)
                            writer.newLine()
                            writer.flush()
                        }
                    }
                } catch (_: IOException) {
                    throw BuiltInProxyUnavailableException("内置代理请求发送失败")
                }
                if (exited.get() && !reply.isCompleted) throw BuiltInProxyUnavailableException("内置代理进程已退出")
                reply.await()
            } ?: throw BuiltInProxyUnavailableException("内置代理响应超时")
            if (response.failed) throw BuiltInProxyUnavailableException(
                when (method) {
                    ProxyOps.IMPORT -> "没有可导入的节点，或节点格式不受支持"
                    ProxyOps.RELOAD -> "内置代理配置重载失败"
                    ProxyOps.START -> "内置代理未能启动，请检查节点配置"
                    else -> "内置代理操作失败"
                },
            )
            return response.result
        } finally {
            pending.remove(id)
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        coroutineScope {
            // 控制管道可能阻塞在系统写入。独立的退出时限不等待写锁。
            val deadline = launch {
                delay(1_100)
                if (process.alive) process.destroyForcibly()
            }
            try {
                if (!exited.get()) runCatching { request(ProxyOps.STOP, JsonObject(emptyMap()), 750, allowClosing = true) }
            } finally {
                if (process.alive && !process.waitFor(300)) {
                    process.destroyForcibly()
                    process.waitFor(300)
                }
                deadline.cancel()
                runCatching { process.stdin.close() }
                runCatching { process.stdout.close() }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
    }
}
