package com.promenar.infinity.data.git.remote

import okhttp3.MediaType
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
import java.util.concurrent.TimeUnit

/** 与监听方式无关的 gh HTTP/1.1 转发核心；调用方在 IO 线程提供连接与超时设置。 */
class GitHubApiForwarder(
    private val credentials: GitCredentialStore,
    private val nonces: GitNonceRegistry,
    httpClient: OkHttpClient,
    private val upstream: GitUpstream = GitUpstream.Https,
    private val diagnostics: (String) -> Unit = {},
) {
    private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .cache(null).readTimeout(BODY_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(BODY_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS).build()

    /** 首个有效请求绑定命令作用域；keep-alive 不能把连接转交给其它命令。 */
    fun serve(input: InputStream, output: OutputStream, connection: AutoCloseable = AutoCloseable {}, timeout: (Int) -> Unit = {}) {
        val lifetime = connection as? GitHttpConnection
        val source = BufferedInputStream(input)
        val sink = BufferedOutputStream(output)
        var owner: GitNonceRegistry.Scope? = null
        var responseStarted = false
        try {
            while (true) {
                responseStarted = false
                timeout(HEAD_TIMEOUT_MILLIS)
                val head = readHead(source) ?: return
                val host = head.headers["host"]?.lowercase()?.removeSuffix(":443")
                if (host !in API_HOSTS && host !in DOWNLOAD_HOSTS) return error(sink, 403, "目标主机不在 GitHub 代理白名单中。")
                val nonce = head.headers["authorization"]?.let { AUTHORIZATION.matchEntire(it)?.groupValues?.get(1) }
                val resolved = nonces.resolve(nonce)
                // gh 跟随跨主机下载跳转时不带 Authorization；下载主机从不注入凭据，无 nonce 时按匿名中继处理，
                // 已归属命令的连接沿用该命令的生命周期。带了 nonce 却无效仍视为授权失效。
                if (resolved == null && (nonce != null || host !in DOWNLOAD_HOSTS)) {
                    return error(sink, 401, "GitHub 代理授权已失效，请重新执行命令。")
                }
                if (resolved != null) {
                    if (owner == null) {
                        if (!resolved.register(connection)) return
                        owner = resolved
                    } else if (owner !== resolved) return error(sink, 401, "GitHub 代理连接所属命令不匹配。")
                }
                val scope = resolved ?: owner
                val bodyStream = head.body(source)
                val body = bodyStream?.let { StreamingBody(it, head.length ?: -1L) }
                    ?: if (head.method in BODY_METHODS) StreamingBody(ByteArrayInputStreamEmpty, 0) else null
                val url = upstream.url(requireNotNull(host)).newBuilder()
                    .encodedPath(head.target.substringBefore('?'))
                    .encodedQuery(head.target.substringAfter('?', "").takeIf { '?' in head.target }).build()
                val builder = Request.Builder().url(url)
                val excluded = hopHeaders(head.headers["connection"]) + setOf("host", "authorization", "expect", GitCredentialProxy.NONCE_HEADER.lowercase())
                head.headers.forEach { (name, value) -> if (name !in excluded) builder.header(name, value) }
                if (host in API_HOSTS) {
                    val token = credentials.token(GitCredentialStore.GITHUB_HOST)
                        ?: return error(sink, 401, "请在设置 › Git 凭据中登录 GitHub")
                    try {
                        if (!SecretGitCredentialStore.validToken(token)) return error(sink, 401, "请在设置 › Git 凭据中登录 GitHub")
                        builder.header("Authorization", "token ${String(token, Charsets.US_ASCII)}")
                    } finally { token.fill(0) }
                }
                val request = builder.method(head.method, body).build()
                timeout(BODY_TIMEOUT_MILLIS)
                if (head.headers["expect"]?.equals("100-continue", true) == true) {
                    sink.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)); sink.flush()
                }
                val call = client.newCall(request)
                val cancel = AutoCloseable { call.cancel() }
                if (lifetime?.attach(call) == false) return
                try {
                    if (scope != null && !scope.register(cancel)) return
                    call.execute().use { response ->
                        if (scope?.revoked == true) return
                        // 未消费完的请求体不能当作下一条请求；包含上游提前返回的情况。
                        bodyStream?.copyTo(DiscardOutput, BUFFER_BYTES)
                        val keepAlive = !head.headers["connection"].orEmpty().split(',').any { it.trim().equals("close", true) }
                        sink.write("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n".toByteArray(Charsets.US_ASCII))
                        responseStarted = true
                        val removed = hopHeaders(response.header("Connection"))
                        response.headers.forEach { (name, value) ->
                            if (name.lowercase() !in removed) sink.write("$name: $value\r\n".toByteArray(Charsets.UTF_8))
                        }
                        val hasBody = head.method != "HEAD" && response.code !in setOf(204, 205, 304) && response.code >= 200
                        if (hasBody) sink.write("Transfer-Encoding: chunked\r\n".toByteArray(Charsets.US_ASCII))
                        if (response.code == 205) sink.write("Content-Length: 0\r\n".toByteArray(Charsets.US_ASCII))
                        sink.write("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        sink.flush()
                        if (hasBody) {
                            val buffer = ByteArray(BUFFER_BYTES)
                            response.body?.byteStream()?.let { stream ->
                                while (true) {
                                    if (scope?.revoked == true) return
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    sink.write("${count.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                                    sink.write(buffer, 0, count); sink.write(CRLF); sink.flush()
                                }
                            }
                            sink.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); sink.flush()
                        }
                        if (!keepAlive) return
                    }
                } finally {
                    scope?.unregister(cancel)
                    lifetime?.detach(call)
                }
            }
        } catch (_: BadRequest) {
            diagnostics("gh-proxy: BAD_REQUEST")
            if (!responseStarted) error(sink, 400, "GitHub 代理请求格式无效。")
        } catch (_: Exception) {
            // 不传播异常原文或类名：上游、输入流和凭据后端都可能把敏感输入放进异常。
            diagnostics("gh-proxy: FORWARD_FAILED")
            if (!responseStarted && owner?.revoked != true) error(sink, 502, "GitHub 代理转发失败。")
        } finally { owner?.unregister(connection) }
    }

    private class Head(val method: String, val target: String, val headers: Map<String, String>, val length: Long?, val chunked: Boolean) {
        fun body(source: InputStream): InputStream? = when {
            chunked -> ChunkedBody(source)
            length != null && length > 0 -> BoundedInputStream(source, length)
            else -> null
        }
    }

    private class StreamingBody(private val stream: InputStream, private val length: Long) : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { sink.writeAll(stream.source()) }
    }

    /** 严格限制请求头、分块行与尾部字段，拒绝歧义长度和重复认证字段。 */
    private class BadRequest : IOException("GitHub 代理请求格式无效")
    private class ChunkedBody(private val source: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (finished) return -1
            if (remaining == 0L) {
                val line = line(source, 1024) ?: throw BadRequest()
                val number = line.substringBefore(';')
                if (number.isEmpty() || number.any { it.digitToIntOrNull(16) == null }) throw BadRequest()
                remaining = number.toLongOrNull(16) ?: throw BadRequest()
                if (remaining == 0L) {
                    var budget = MAX_HEAD_BYTES
                    while (true) {
                        val trailer = line(source, budget) ?: throw BadRequest()
                        budget -= trailer.length + 2
                        if (trailer.isEmpty()) break
                    }
                    finished = true
                    return -1
                }
            }
            val count = source.read(bytes, off, minOf(len.toLong(), remaining).toInt())
            if (count < 0) throw BadRequest()
            remaining -= count
            if (remaining == 0L && line(source, 2) != "") throw BadRequest()
            return count
        }
    }

    companion object {
        val API_HOSTS: Set<String> = setOf("api.github.com", "uploads.github.com")
        val DOWNLOAD_HOSTS: Set<String> = setOf("objects.githubusercontent.com", "codeload.github.com",
            "github-releases.githubusercontent.com", "release-assets.githubusercontent.com", "raw.githubusercontent.com")
        const val HEAD_TIMEOUT_MILLIS = 30_000
        const val BODY_TIMEOUT_MILLIS = 600_000
        private const val MAX_HEAD_BYTES = 16_384
        private const val BUFFER_BYTES = 65_536
        private val AUTHORIZATION = Regex("(?i)(?:token|bearer) ([A-Za-z0-9_-]{43})")
        private val FIELD = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val HOP_HEADERS = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "content-length")
        private val CRLF = byteArrayOf(13, 10)
        private val ByteArrayInputStreamEmpty = java.io.ByteArrayInputStream(byteArrayOf())
        private object DiscardOutput : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        private fun hopHeaders(connection: String?): Set<String> = HOP_HEADERS + connection.orEmpty().split(',').map { it.trim().lowercase() }
        private fun readHead(source: InputStream): Head? {
            val first = line(source, MAX_HEAD_BYTES) ?: return null
            val parts = first.split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1" || parts[0] !in METHODS) throw BadRequest()
            val target = parts[1]
            if (!target.startsWith('/') || target.startsWith("//") || target.any { it <= ' ' || it == '\u007f' || it == '#' || it == '\\' }) throw BadRequest()
            var budget = MAX_HEAD_BYTES - first.length - 2
            val headers = linkedMapOf<String, String>()
            while (true) {
                val value = line(source, budget) ?: throw BadRequest()
                budget -= value.length + 2
                if (value.isEmpty()) break
                val index = value.indexOf(':')
                if (index <= 0 || !FIELD.matches(value.substring(0, index))) throw BadRequest()
                val name = value.substring(0, index).lowercase()
                val content = value.substring(index + 1).trim(' ', '\t')
                if (content.any { it.isISOControl() && it != '\t' }) throw BadRequest()
                if (name in headers && name in setOf("host", "authorization", "content-length", "transfer-encoding")) throw BadRequest()
                headers[name] = headers[name]?.let { "$it, $content" } ?: content
            }
            val length = headers["content-length"]?.let {
                if (it.isEmpty() || it.any { char -> char !in '0'..'9' }) throw BadRequest()
                it.toLongOrNull() ?: throw BadRequest()
            }
            val encoding = headers["transfer-encoding"]
            if (encoding != null && (!encoding.equals("chunked", true) || length != null)) throw BadRequest()
            if (parts[0] in setOf("GET", "HEAD") && (encoding != null || length != null && length > 0)) throw BadRequest()
            if (headers["expect"] != null && !headers.getValue("expect").equals("100-continue", true)) throw BadRequest()
            return Head(parts[0], target, headers, length, encoding != null)
        }
        private fun line(source: InputStream, limit: Int): String? {
            if (limit <= 0) throw BadRequest()
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < limit) {
                val next = source.read()
                if (next < 0) { if (bytes.size() == 0) return null else throw BadRequest() }
                if (next == 13) {
                    if (source.read() != 10) throw BadRequest()
                    return bytes.toString("ISO-8859-1")
                }
                if (next == 10) throw BadRequest()
                bytes.write(next)
            }
            throw BadRequest()
        }
        private fun error(output: OutputStream, status: Int, message: String) {
            val body = "{\"message\":\"$message\"}\n".toByteArray(Charsets.UTF_8)
            runCatching {
                output.write(("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                output.write(body); output.flush()
            }
        }
        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 502 -> "Bad Gateway"; else -> "Status"
        }
    }
}
