package com.promenar.infinity.data.git.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/** 上游地址映射：生产固定为 `https://<主机键>`；测试可映射到本机明文服务。 */
interface GitUpstream {
    fun url(hostKey: String): HttpUrl
    /** [url] 对应的主机键；不属于任何上游时返回 null（用于改写重定向）。 */
    fun hostKey(url: HttpUrl): String?

    object Https : GitUpstream {
        override fun url(hostKey: String): HttpUrl = requireNotNull("https://$hostKey/".toHttpUrlOrNull())
        override fun hostKey(url: HttpUrl): String? = if (url.scheme == "https") GitCredentialHost.of(url.host, url.port) else null
    }
}

/**
 * Git HTTPS 凭据注入代理：监听 127.0.0.1 随机端口，接受 `http://127.0.0.1:<端口>/<主机键>/<仓库路径>` 形式的请求。
 *
 * - 请求头 [NONCE_HEADER] 必须是当前仍在执行的命令的 nonce；
 * - 只放行 git 智能协议：`GET …/info/refs?service=git-upload-pack|git-receive-pack`、`POST …/git-upload-pack|git-receive-pack`；
 * - 主机键必须已在 Git 凭据中配置，代理按该主机的用户名与令牌加 `Authorization: Basic` 后经 HTTPS 转发；
 * - 请求与响应体都流式转发，响应以 chunked 编码返回，每个连接只处理一个请求；
 * - 重定向到已配置主机时改写为代理地址，指向其它主机时拒绝，避免 nonce 随重定向发给第三方。
 *
 * 代理自身拒绝的请求与上游认证失败带 [ERROR_HEADER] 响应头与中文说明，由 infinity_git 转成错误信息；任何响应、日志与异常都不含令牌。
 */
class GitCredentialProxy(
    private val credentials: GitCredentialStore,
    private val nonces: GitNonceRegistry,
    httpClient: OkHttpClient,
    private val upstream: GitUpstream = GitUpstream.Https,
    /** 只接收错误码与异常类名，供调试；不含请求头、令牌或 nonce。 */
    private val diagnostics: (String) -> Unit = {},
    maxConnections: Int = 16,
) : AutoCloseable {
    private val client: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val server = object : LoopbackServer(maxConnections) {
        override suspend fun handle(socket: Socket) = serve(socket)
        override fun rejectBusy(socket: Socket) {
            socket.soTimeout = 1_000
            BufferedOutputStream(socket.getOutputStream()).let { writeError(it, 503, "BUSY", "Git 凭据代理并发连接过多，请稍后重试。") }
        }
    }

    val port: Int? get() = server.port
    internal val boundAddress get() = server.boundAddress

    fun start(): Int = server.start()

    override fun close() = server.close()

    private fun serve(socket: Socket) {
        socket.soTimeout = HEAD_TIMEOUT_MILLIS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val head = readHead(input) ?: return writeError(output, 400, "BAD_REQUEST", "请求格式无效。")
        val scope = nonces.resolve(head.header(NONCE_HEADER))
            ?: return reject(output, 403, "NONCE_REJECTED", "nonce 缺失、无效或所属命令已结束；Git 凭据代理只服务正在执行的命令。")
        val closer = AutoCloseable { runCatching { socket.close() } }
        if (!scope.register(closer)) return
        try {
            val route = route(head) ?: return
            if (route is Route.Rejected) return reject(output, route.status, route.code, route.message)
            forward(route as Route.Accepted, head, input, output, socket, scope)
        } finally {
            scope.unregister(closer)
        }
    }

    private fun reject(output: OutputStream, status: Int, code: String, message: String) {
        diagnostics("git-proxy: $code")
        writeError(output, status, code, message)
    }

    private sealed interface Route {
        data class Accepted(val hostKey: String, val path: String, val query: String?, val post: Boolean) : Route
        data class Rejected(val status: Int, val code: String, val message: String) : Route
    }

    private fun route(head: RequestHead): Route? {
        val target = head.target
        if (!target.startsWith("/") || '#' in target) return Route.Rejected(400, "BAD_REQUEST", "请求地址无效。")
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?') else null
        val hostKey = path.removePrefix("/").substringBefore('/')
        val rest = path.removePrefix("/").let { if ('/' in it) "/" + it.substringAfter('/') else "" }
        if (GitCredentialHost.normalize(hostKey) != hostKey || hostKey !in credentials.hosts()) {
            return Route.Rejected(403, "HOST_NOT_CONFIGURED", "主机 ${hostKey.take(80)} 未在 Git 凭据中配置，代理不转发。")
        }
        if (!safePath(rest)) return Route.Rejected(403, "PATH_REJECTED", "只允许 git 智能协议路径。")
        val accepted = when (head.method) {
            "GET" -> query in SMART_QUERIES && rest.endsWith(INFO_REFS) && rest.length > INFO_REFS.length
            "POST" -> query == null && SMART_SERVICES.any { rest.endsWith("/$it") && rest.length > it.length + 1 }
            else -> false
        }
        if (!accepted) return Route.Rejected(403, "PATH_REJECTED", "只允许 git 智能协议路径（info/refs、git-upload-pack、git-receive-pack）。")
        return Route.Accepted(hostKey, rest, query, head.method == "POST")
    }

    private fun forward(
        route: Route.Accepted,
        head: RequestHead,
        input: InputStream,
        output: BufferedOutputStream,
        socket: Socket,
        scope: GitNonceRegistry.Scope,
    ) {
        val username = credentials.username(route.hostKey)
        val token = credentials.token(route.hostKey)
        if (username == null || token == null) {
            token?.fill(0)
            return reject(output, 403, "HOST_NOT_CONFIGURED", "主机 ${route.hostKey} 的 Git 凭据不可用，请在设置中重新保存。")
        }
        val authorization = try {
            Credentials.basic(username, String(token, Charsets.UTF_8), Charsets.UTF_8)
        } finally { token.fill(0) }
        val body = if (route.post) {
            val length = head.header("content-length")
            val chunked = head.header("transfer-encoding")?.lowercase()?.contains("chunked") == true
            val stream = when {
                chunked -> ChunkedInputStream(input)
                length != null -> BoundedInputStream(input, length.toLongOrNull()?.takeIf { it >= 0 }
                    ?: return reject(output, 400, "BAD_REQUEST", "Content-Length 无效。"))
                else -> return reject(output, 411, "LENGTH_REQUIRED", "请求体缺少长度。")
            }
            if (head.header("expect")?.equals("100-continue", ignoreCase = true) == true) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
            }
            StreamingBody(stream, head.header("content-type")?.toMediaTypeOrNull(), if (chunked) -1 else length!!.toLong())
        } else null
        val url = upstream.url(route.hostKey).newBuilder().encodedPath(route.path).encodedQuery(route.query).build()
        val request = Request.Builder().url(url).apply {
            FORWARDED_REQUEST_HEADERS.forEach { name -> head.header(name)?.let { header(name, it) } }
            header("Authorization", authorization)
            if (body != null) post(body) else get()
        }.build()
        // 读请求体与等待上游处理 pack 可能很久，读超时放宽到与上游一致。
        socket.soTimeout = BODY_TIMEOUT_MILLIS
        val call = client.newCall(request)
        val cancel = AutoCloseable { call.cancel() }
        if (!scope.register(cancel)) return
        try {
            val response = try { call.execute() } catch (failure: IOException) {
                diagnostics("git-proxy: UPSTREAM_FAILED ${failure.javaClass.simpleName}")
                return writeError(output, 502, "UPSTREAM_FAILED", "连接 ${route.hostKey} 失败（${failure.javaClass.simpleName}）。")
            }
            response.use {
                authFailure(route.hostKey, response.code)?.let { (status, code, message) -> return reject(output, status, code, message) }
                var location: String? = null
                if (response.isRedirect) {
                    val target = response.header("Location")?.let { response.request.url.resolve(it) }
                    val key = target?.let(upstream::hostKey)
                    if (target == null || key == null || key !in credentials.hosts()) {
                        return reject(output, 502, "REDIRECT_REJECTED", "${route.hostKey} 重定向到未配置 Git 凭据的地址，已拒绝。")
                    }
                    location = "http://127.0.0.1:${server.port}/$key${target.encodedPath}" +
                        (target.encodedQuery?.let { "?$it" } ?: "")
                }
                val headers = StringBuilder("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
                FORWARDED_RESPONSE_HEADERS.forEach { name ->
                    response.headers(name).forEach { value -> if (value.none(Char::isISOControl)) headers.append("$name: $value\r\n") }
                }
                location?.let { headers.append("Location: $it\r\n") }
                headers.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                output.write(headers.toString().toByteArray(Charsets.UTF_8))
                val source = response.body?.byteStream()
                val buffer = ByteArray(CHUNK_BYTES)
                if (source != null) {
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(Integer.toHexString(count).toByteArray(Charsets.US_ASCII))
                        output.write(CRLF)
                        output.write(buffer, 0, count)
                        output.write(CRLF)
                        output.flush()
                    }
                }
                output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
        } catch (failure: IOException) {
            // 响应头已发出后的中断只能断开连接，客户端据此报告传输失败。
            diagnostics("git-proxy: STREAM_FAILED ${failure.javaClass.simpleName}")
        } finally {
            scope.unregister(cancel)
        }
    }

    /**
     * 上游认证失败改由代理给出说明，告诉智能体该让用户去哪里处理，而不是反复重试：
     * 401 表示令牌已被拒绝（撤销、过期或长期未用被回收），同时标记该凭据，设置页据此提示重新登录或更换令牌；
     * GitHub 登录的凭据遇到 403 时多半是仓库权限或组织的 OAuth 应用访问限制，令牌本身仍有效，不做标记。
     */
    private fun authFailure(hostKey: String, status: Int): Triple<Int, String, String>? {
        val credential = credentials.credential(hostKey)
        return when {
            status == 401 -> {
                // 标记写入 Keystore 失败不影响把说明返回给智能体。
                runCatching { credentials.markRejected(hostKey) }
                Triple(401, "CREDENTIAL_REJECTED", if (credential?.fromGitHubLogin == true) {
                    "GitHub 授权已失效（HTTP 401），令牌可能已被撤销、超过一年未使用或被 GitHub 自动回收。" +
                        "请让用户在 $CREDENTIAL_SETTINGS 中重新登录 GitHub，不要重试这条命令。"
                } else {
                    "$hostKey 拒绝了已保存的令牌（HTTP 401），令牌可能已过期或被撤销。" +
                        "请让用户在 $CREDENTIAL_SETTINGS 中更换该主机的令牌，不要重试这条命令。"
                })
            }
            status == 403 && credential?.fromGitHubLogin == true -> Triple(403, "ACCESS_DENIED",
                "GitHub 拒绝访问该仓库（HTTP 403）：登录的账号可能没有这个仓库的读写权限，" +
                    "或仓库所属组织启用了 OAuth 应用访问限制，需要组织管理员批准 Infinity Deck。GitHub 授权本身仍然有效。")
            else -> null
        }
    }

    private class StreamingBody(private val stream: InputStream, private val type: MediaType?, private val length: Long) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) { sink.writeAll(stream.source()) }
    }

    internal class RequestHead(val method: String, val target: String, private val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    companion object {
        const val NONCE_HEADER = "X-Infinity-Git-Nonce"
        const val ERROR_HEADER = "X-Infinity-Git-Error"
        private const val CREDENTIAL_SETTINGS = "设置 → 执行环境与连接 → Git 凭据"
        private const val INFO_REFS = "/info/refs"
        private val SMART_SERVICES = listOf("git-upload-pack", "git-receive-pack")
        private val SMART_QUERIES = SMART_SERVICES.map { "service=$it" }.toSet()
        private val FORWARDED_REQUEST_HEADERS = listOf("Accept", "Content-Encoding", "Git-Protocol", "User-Agent")
        private val FORWARDED_RESPONSE_HEADERS = listOf("Content-Type", "Cache-Control", "Expires", "Pragma", "WWW-Authenticate")
        private const val HEAD_TIMEOUT_MILLIS = 30_000
        private const val BODY_TIMEOUT_MILLIS = 600_000
        private const val MAX_HEAD_BYTES = 16_384
        private const val CHUNK_BYTES = 65_536
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val SEGMENT = Regex("[A-Za-z0-9._~!$&'()*+,;=:@%-]+")
        private val FORBIDDEN_ESCAPE = Regex("%(2[eEfF]|5[cC]|[01][0-9a-fA-F]|7[fF])")

        /** 仓库路径：非空的 `/段` 序列，段内只允许 URL 安全字符，拒绝 `.`、`..` 与编码后的点、斜杠、反斜杠和控制字符。 */
        internal fun safePath(path: String): Boolean {
            if (path.isEmpty() || !path.startsWith("/") || path.length > 2048 || FORBIDDEN_ESCAPE.containsMatchIn(path)) return false
            return path.substring(1).split('/').all { segment -> segment != "." && segment != ".." && SEGMENT.matches(segment) }
        }

        internal fun readHead(input: InputStream): RequestHead? {
            var budget = MAX_HEAD_BYTES
            val requestLine = input.readAsciiLine(budget) ?: return null
            budget -= requestLine.length
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || parts[0].any { it !in 'A'..'Z' }) return null
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.readAsciiLine(budget) ?: return null
                budget -= line.length + 2
                if (budget <= 0) return null
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0 || line.any { it.isISOControl() && it != '\t' }) return null
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
            return RequestHead(parts[0], parts[1], headers)
        }

        internal fun writeError(output: OutputStream, status: Int, code: String, message: String) {
            val body = (message + "\n").toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status ${reason(status)}\r\n$ERROR_HEADER: $code\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            runCatching {
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"; 301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"; 307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            411 -> "Length Required"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}

/** 只读到 [remaining] 字节为止的请求体。 */
internal class BoundedInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        return input.read().also { if (it >= 0) remaining-- else throw IOException("请求体提前结束") }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw IOException("请求体提前结束")
        remaining -= count
        return count
    }
}

/** HTTP/1.1 chunked 请求体解码；忽略分块扩展与尾部字段。 */
internal class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var remaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (remaining == 0L) {
            val line = input.readAsciiLine(1024) ?: throw IOException("分块长度无效")
            remaining = line.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("分块长度无效")
            if (remaining == 0L) {
                while ((input.readAsciiLine(8192) ?: throw IOException("分块尾部无效")).isNotEmpty()) { /* 丢弃尾部字段 */ }
                finished = true
                return -1
            }
        }
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw IOException("请求体提前结束")
        remaining -= count
        if (remaining == 0L && input.readAsciiLine(2) != "") throw IOException("分块结尾无效")
        return count
    }
}
