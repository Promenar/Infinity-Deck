package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.media.MAX_WORKSPACE_MEDIA_BYTES
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import okio.BufferedSink
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 仅监听 `127.0.0.1` 随机端口的轻量一次性 HTTP 反向代理：
 * - 使用一次性路径令牌 `/p/<nonce>` 隔离其它本机进程探测；
 * - 所有上游请求经传入的 [client]（含应用内 Tailscale 路由）转发，并在内存中注入 `Authorization`；
 * - 任何令牌/密码绝不进入 Python 子进程环境、源码、命令行参数或日志。
 */
class ComfyLocalAuthProxy private constructor(
    private val host: ComfyHost,
    private val authorizationHeader: String?,
    client: OkHttpClient,
    private val tempDir: File,
) : Closeable {
    private val proxiedClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(minOf(host.timeoutSeconds, 30).toLong(), TimeUnit.SECONDS)
        .readTimeout(host.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(host.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val prefix = "/p/$nonce"
    private val closed = AtomicBoolean(false)
    private val requestCounter = AtomicInteger(0)
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "comfy-auth-proxy-worker").apply { isDaemon = true }
    }
    private val acceptThread: Thread = Thread(::acceptLoop, "comfy-auth-proxy-accept").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port$prefix"
    val forwardedCount: Int get() = requestCounter.get()

    private fun acceptLoop() {
        while (!closed.get() && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            if (closed.get()) {
                runCatching { socket.close() }
                break
            }
            try {
                workers.execute { handleConnection(socket) }
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = maxOf(host.timeoutSeconds, 30) * 1000
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val output = sock.getOutputStream()
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeSimpleError(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val headers = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers.getOrPut(key) { mutableListOf() }.add(value)
                }
            }

            if (rawTarget != prefix && !rawTarget.startsWith("$prefix/") && !rawTarget.startsWith("$prefix?")) {
                writeSimpleError(output, 403, "Forbidden")
                return
            }
            val stripped = rawTarget.removePrefix(prefix).let {
                when {
                    it.isEmpty() -> "/"
                    it.startsWith("?") -> "/$it"
                    else -> it
                }
            }
            if (!stripped.startsWith("/") || stripped.startsWith("//") || stripped.contains('\\')) {
                writeSimpleError(output, 400, "Bad Request")
                return
            }

            val isChunked = headers["transfer-encoding"]?.any { it.contains("chunked", ignoreCase = true) } == true
            val contentLength = headers["content-length"]?.firstOrNull()?.toLongOrNull() ?: 0L
            if (contentLength < 0 || contentLength > MAX_WORKSPACE_MEDIA_BYTES) {
                writeSimpleError(output, 413, "Payload Too Large")
                return
            }

            val contentType = headers["content-type"]?.firstOrNull()?.toMediaTypeOrNull()
            var spillFile: File? = null
            try {
                val requestBody: RequestBody? = when {
                    isChunked -> {
                        val loaded = readChunkedBody(input, tempDir)
                        spillFile = loaded.file
                        if (spillFile != null) spillFile.asRequestBody(contentType)
                        else loaded.bytes.toRequestBody(contentType)
                    }
                    contentLength > 0L -> {
                        val loaded = readFixedBody(input, contentLength, tempDir)
                        spillFile = loaded.file
                        if (spillFile != null) spillFile.asRequestBody(contentType)
                        else loaded.bytes.toRequestBody(contentType)
                    }
                    HttpMethod.requiresRequestBody(method) -> ByteArray(0).toRequestBody(contentType)
                    else -> null
                }

                val upstreamUrl = host.endpoint + stripped
                val builder = Request.Builder().url(upstreamUrl).method(method, requestBody)
                headers.forEach { (name, values) ->
                    if (name !in STRIPPED_REQUEST_HEADERS) {
                        values.forEach { v -> builder.addHeader(name, v) }
                    }
                }
                builder.header("Accept-Encoding", "identity")
                authorizationHeader?.let { builder.header("Authorization", it) }

                requestCounter.incrementAndGet()
                proxiedClient.newCall(builder.build()).execute().use { resp ->
                    forwardResponse(output, resp)
                }
            } catch (_: PayloadTooLargeException) {
                runCatching { writeSimpleError(output, 413, "Payload Too Large") }
            } catch (_: Exception) {
                runCatching { writeSimpleError(output, 502, "Bad Gateway") }
            } finally {
                spillFile?.delete()
            }
        }
    }

    private fun forwardResponse(output: OutputStream, response: okhttp3.Response) {
        val statusText = response.message.ifBlank { httpReason(response.code) }
        val respBody = response.body
        val declaredLen = respBody?.contentLength() ?: -1L
        val head = buildString {
            append("HTTP/1.1 ").append(response.code).append(' ').append(statusText).append("\r\n")
            val headers: Headers = response.headers
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                if (name.lowercase() !in STRIPPED_RESPONSE_HEADERS) {
                    append(name).append(": ").append(headers.value(i)).append("\r\n")
                }
            }
            if (declaredLen >= 0L) {
                append("Content-Length: ").append(declaredLen).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (respBody != null) {
            respBody.byteStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > MAX_WORKSPACE_MEDIA_BYTES) {
                        throw IOException("ComfyUI 响应体超过媒体上限")
                    }
                    output.write(buf, 0, read)
                }
            }
        }
        output.flush()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
            workers.shutdownNow()
            runCatching { acceptThread.join(300) }
        }
    }

    private class PayloadTooLargeException : IOException()
    private data class LoadedBody(val bytes: ByteArray, val file: File?)

    private fun readFixedBody(input: InputStream, length: Long, tempDir: File): LoadedBody {
        if (length <= IN_MEMORY_BODY_LIMIT) {
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val n = input.read(bytes, offset, bytes.size - offset)
                if (n < 0) throw IOException("请求体提前结束")
                offset += n
            }
            return LoadedBody(bytes, null)
        }
        tempDir.mkdirs()
        val spill = File.createTempFile("proxy_req_", ".bin", tempDir)
        spill.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw IOException("请求体提前结束")
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return LoadedBody(ByteArray(0), spill)
    }

    private fun readChunkedBody(input: InputStream, tempDir: File): LoadedBody {
        val mem = ByteArrayOutputStream()
        var spill: File? = null
        var spillOut: OutputStream? = null
        var total = 0L
        try {
            while (true) {
                val sizeLine = readAsciiLine(input) ?: throw IOException("chunked 请求提前结束")
                val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("无效 chunk 长度")
                if (chunkSize == 0L) {
                    // 消费末尾空行或 trailer。
                    while (true) {
                        val trailer = readAsciiLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                if (total + chunkSize > MAX_WORKSPACE_MEDIA_BYTES) throw PayloadTooLargeException()
                val buf = ByteArray(64 * 1024)
                var remaining = chunkSize
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) throw IOException("chunked 数据不完整")
                    total += n
                    if (spillOut == null && total <= IN_MEMORY_BODY_LIMIT) {
                        mem.write(buf, 0, n)
                    } else {
                        if (spillOut == null) {
                            tempDir.mkdirs()
                            spill = File.createTempFile("proxy_chunk_", ".bin", tempDir)
                            spillOut = spill.outputStream().also { it.write(mem.toByteArray()) }
                            mem.reset()
                        }
                        spillOut.write(buf, 0, n)
                    }
                    remaining -= n
                }
                readAsciiLine(input) // chunk 结束 CRLF
            }
            spillOut?.close()
            return LoadedBody(if (spill == null) mem.toByteArray() else ByteArray(0), spill)
        } catch (t: Throwable) {
            runCatching { spillOut?.close() }
            spill?.delete()
            throw t
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prevCr = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            }
            if (b == '\n'.code) {
                val raw = out.toByteArray()
                val len = if (prevCr && raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, len, Charsets.ISO_8859_1)
            }
            prevCr = (b == '\r'.code)
            out.write(b)
            if (out.size() > 64 * 1024) throw IOException("HTTP 头部行过长")
        }
    }

    private fun writeSimpleError(output: OutputStream, code: Int, message: String) {
        val text = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun httpReason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    companion object {
        private const val IN_MEMORY_BODY_LIMIT = 512 * 1024L
        private val STRIPPED_REQUEST_HEADERS = setOf(
            "authorization", "proxy-authorization", "host", "connection", "content-length", "transfer-encoding", "accept-encoding",
        )
        private val STRIPPED_RESPONSE_HEADERS = setOf(
            "connection", "keep-alive", "transfer-encoding", "content-length", "proxy-authenticate", "upgrade",
        )

        suspend fun open(
            host: ComfyHost,
            credentials: ComfyCredentials,
            client: OkHttpClient,
            tempDir: File,
        ): ComfyLocalAuthProxy {
            val auth = credentials.authorization(host)
            return ComfyLocalAuthProxy(host, auth, client, tempDir)
        }
    }
}
