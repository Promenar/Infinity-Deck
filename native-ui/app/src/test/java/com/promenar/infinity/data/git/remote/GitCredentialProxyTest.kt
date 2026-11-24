package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

class GitCredentialProxyTest {
    private val token = "nxt_SyntheticProxyToken0123456789"
    private val basic = Credentials.basic("octo", token)
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val secrets = MemorySecrets()
    private val store = SecretGitCredentialStore(secrets)
    private val nonces = GitNonceRegistry()
    private val upstream = FakeUpstream()
    private val mapping = TestUpstream()
    private lateinit var proxy: GitCredentialProxy
    private var port = 0
    private val responses = mutableListOf<RawResponse>()

    @Before fun setUp() {
        store.save("git.test", "octo", token.toByteArray())
        store.save("down.test", "octo", token.toByteArray())
        mapping.ports["git.test"] = upstream.port
        mapping.ports["down.test"] = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        proxy = GitCredentialProxy(store, nonces, OkHttpClient(), mapping, diagnostics::add)
        port = proxy.start()
    }

    @After fun tearDown() {
        proxy.close()
        upstream.close()
        // 任何响应与诊断都不含令牌、Basic 头或 nonce。
        val leaks = responses.map { it.raw } + diagnostics
        leaks.forEach { text ->
            assertThat(text).doesNotContain(token)
            assertThat(text).doesNotContain(basic.removePrefix("Basic "))
        }
    }

    @Test fun `只监听127点0点0点1`() {
        assertThat(proxy.boundAddress).isEqualTo(LOOPBACK)
        assertThat(proxy.boundAddress!!.hostAddress).isEqualTo("127.0.0.1")
    }

    @Test fun `代理认证头名称符合HTTP字段语法`() {
        listOf(GitCredentialProxy.NONCE_HEADER, GitCredentialProxy.ERROR_HEADER).forEach { name ->
            okhttp3.Headers.Builder().add(name, "synthetic").build()
        }
    }

    @Test fun `已配置主机的智能协议请求带Authorization转发且不转发nonce与客户端认证头`() {
        val scope = nonces.issue()
        upstream.respond { _, out -> out.ok("application/x-git-upload-pack-advertisement", "# service=git-upload-pack\n".toByteArray(), "Set-Cookie: s=1") }
        val response = send(
            "GET /git.test/owner/repo.git/info/refs?service=git-upload-pack HTTP/1.1",
            scope.nonce,
            "Authorization: Basic Zm9vOmJhcg==", "Cookie: a=b", "Git-Protocol: version=2", "User-Agent: git/dulwich",
        )
        assertThat(response.status).isEqualTo(200)
        assertThat(response.text).isEqualTo("# service=git-upload-pack\n")
        assertThat(response.header("content-type")).isEqualTo("application/x-git-upload-pack-advertisement")
        assertThat(response.header("set-cookie")).isNull()

        val recorded = upstream.requests.single()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.target).isEqualTo("/owner/repo.git/info/refs?service=git-upload-pack")
        assertThat(recorded.header("authorization")).isEqualTo(basic)
        assertThat(recorded.header("git-protocol")).isEqualTo("version=2")
        assertThat(recorded.header("cookie")).isNull()
        assertThat(recorded.header(GitCredentialProxy.NONCE_HEADER)).isNull()
        assertThat(recorded.rawHead).doesNotContain(scope.nonce)
        scope.close()
    }

    @Test fun `缺失错误或已吊销的nonce被拒绝且不访问上游`() {
        val target = "GET /git.test/owner/repo.git/info/refs?service=git-upload-pack HTTP/1.1"
        assertRejected(send(target, null), 403, "NONCE_REJECTED")
        assertRejected(send(target, "A".repeat(GitNonceRegistry.NONCE_LENGTH)), 403, "NONCE_REJECTED")
        val finished = nonces.issue().also { it.close() }
        assertRejected(send(target, finished.nonce), 403, "NONCE_REJECTED")
        // 另一条仍在执行的命令不受影响。
        val running = nonces.issue()
        upstream.respond { _, out -> out.ok("text/plain", "ok".toByteArray()) }
        assertThat(send(target, running.nonce).status).isEqualTo(200)
        assertThat(upstream.requests).hasSize(1)
        running.close()
        assertRejected(send(target, running.nonce), 403, "NONCE_REJECTED")
        assertThat(upstream.requests).hasSize(1)
    }

    @Test fun `未配置主机被拒绝`() {
        val scope = nonces.issue()
        listOf("/evil.test/o/r.git/info/refs?service=git-upload-pack", "/GIT.TEST/o/r.git/info/refs?service=git-upload-pack", "/127.0.0.1/o/r.git/info/refs?service=git-upload-pack")
            .forEach { assertRejected(send("GET $it HTTP/1.1", scope.nonce), 403, "HOST_NOT_CONFIGURED") }
        assertThat(upstream.requests).isEmpty()
        scope.close()
    }

    @Test fun `通用HTTPS路由支持匿名访问且过滤客户端认证信息`() {
        mapping.ports["public.test"] = upstream.port
        nonces.issue().use { scope ->
            val response = send(
                "GET /https/reader@public.test/o/r/info/refs?service=git-upload-pack HTTP/1.1",
                scope.nonce, "Authorization: Basic synthetic", "Cookie: sample=1",
            )
            assertThat(response.status).isEqualTo(200)
            val request = upstream.requests.single()
            assertThat(request.target).isEqualTo("/o/r/info/refs?service=git-upload-pack")
            assertThat(request.header("authorization")).isNull()
            assertThat(request.header("cookie")).isNull()
            assertThat(request.header(GitCredentialProxy.NONCE_HEADER)).isNull()
        }
    }

    @Test fun `通用HTTPS路由用主机匹配已存凭据并拒绝URL口令`() {
        nonces.issue().use { scope ->
            val path = "/o/r/info/refs?service=git-upload-pack"
            assertThat(send("GET /https/reader@git.test$path HTTP/1.1", scope.nonce).status).isEqualTo(200)
            assertThat(upstream.requests.single().header("authorization")).isEqualTo(basic)
            listOf("reader:sample", "reader%3asample", "reader%253asample", "a@reader").forEach { userInfo ->
                val rejected = send("GET /https/$userInfo@git.test$path HTTP/1.1", scope.nonce)
                assertRejected(rejected, 403, "HOST_REJECTED")
                assertThat(rejected.raw).doesNotContain(userInfo)
            }
            assertThat(upstream.requests).hasSize(1)
        }
    }

    @Test fun `通用HTTPS路由校验租约和智能协议路径`() {
        mapping.ports["public.test"] = upstream.port
        val base = "/https/public.test/o/r"
        assertRejected(send("GET $base/info/refs?service=git-upload-pack HTTP/1.1", null), 403, "NONCE_REJECTED")
        nonces.issue().use { scope ->
            assertRejected(send("GET $base/HEAD HTTP/1.1", scope.nonce), 403, "PATH_REJECTED")
            assertRejected(send("GET $base/../r/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce), 403, "PATH_REJECTED")
        }
        assertThat(upstream.requests).isEmpty()
    }

    @Test fun `匿名HTTPS跨主机重定向经代理且两个请求都不发送凭据`() {
        FakeUpstream().use { second ->
            mapping.ports["public.test"] = upstream.port
            mapping.ports["mirror.test"] = second.port
            val path = "/o/r/info/refs?service=git-upload-pack"
            upstream.respond { _, out ->
                out.raw("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${second.port}$path\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
            nonces.issue().use { scope ->
                val redirected = send("GET /https/public.test$path HTTP/1.1", scope.nonce)
                assertThat(redirected.status).isEqualTo(302)
                assertThat(redirected.header("location")).isEqualTo("http://127.0.0.1:$port/https/mirror.test$path")
                assertThat(second.requests).isEmpty()
                assertThat(send("GET /https/mirror.test$path HTTP/1.1", scope.nonce).status).isEqualTo(200)
                listOf(upstream.requests.single(), second.requests.single()).forEach { request ->
                    assertThat(request.header("authorization")).isNull()
                    assertThat(request.header(GitCredentialProxy.NONCE_HEADER)).isNull()
                }
                val authenticated = send("GET /https/git.test$path HTTP/1.1", scope.nonce)
                assertRejected(authenticated, 502, "REDIRECT_REJECTED")
                assertThat(authenticated.header("location")).isNull()
                assertThat(second.requests).hasSize(1)
            }
        }
    }

    @Test fun `匿名HTTPS重定向不能获得另一主机保存的凭据`() {
        FakeUpstream().use { second ->
            mapping.ports["public.test"] = upstream.port
            mapping.ports["git.test"] = second.port
            val path = "/o/r/info/refs?service=git-upload-pack"
            upstream.respond { _, out ->
                out.raw("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${second.port}$path\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
            nonces.issue().use { scope ->
                val response = send("GET /https/public.test$path HTTP/1.1", scope.nonce)
                assertRejected(response, 502, "REDIRECT_REJECTED")
                assertThat(response.header("location")).isNull()
                assertThat(second.requests).isEmpty()
            }
        }
    }

    @Test fun `匿名重定向在租约期间保持匿名且下一条命令可使用新凭据`() {
        FakeUpstream().use { second ->
            mapping.ports["public.test"] = upstream.port
            mapping.ports["mirror.test"] = second.port
            val path = "/o/r/info/refs?service=git-upload-pack"
            upstream.respond { _, out ->
                out.raw("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${second.port}$path\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
            nonces.issue().use { scope ->
                assertThat(send("GET /https/public.test$path HTTP/1.1", scope.nonce).status).isEqualTo(302)
                store.save("mirror.test", "octo", token.toByteArray())
                assertThat(send("GET /https/mirror.test$path HTTP/1.1", scope.nonce).status).isEqualTo(200)
                assertThat(second.requests.single().header("authorization")).isNull()
            }
            nonces.issue().use { scope ->
                assertThat(send("GET /https/mirror.test$path HTTP/1.1", scope.nonce).status).isEqualTo(200)
                assertThat(second.requests.last().header("authorization")).isEqualTo(basic)
            }
        }
    }

    @Test fun `非智能协议路径与路径穿越被拒绝`() {
        val scope = nonces.issue()
        listOf(
            "GET /git.test/o/r.git/HEAD",
            "GET /git.test/o/r.git/objects/info/packs",
            "GET /git.test/o/r.git/info/refs",
            "GET /git.test/o/r.git/info/refs?service=git-upload-archive",
            "GET /git.test/o/r.git/info/refs?service=git-upload-pack&x=1",
            "GET /git.test/info/refs?service=git-upload-pack",
            "GET /git.test/o/../r.git/info/refs?service=git-upload-pack",
            "GET /git.test/o/%2e%2e/r.git/info/refs?service=git-upload-pack",
            "GET /git.test/o%2fr.git/info/refs?service=git-upload-pack",
            "GET /git.test/o/r.git/git-upload-pack",
            "POST /git.test/o/r.git/info/refs?service=git-upload-pack",
            "POST /git.test/o/r.git/git-upload-pack?x=1",
            "POST /git.test/o/r.git/git-upload-archive",
            "PUT /git.test/o/r.git/git-receive-pack",
            "DELETE /git.test/o/r.git/git-receive-pack",
        ).forEach { line ->
            val response = send("$line HTTP/1.1", scope.nonce, "Content-Length: 0")
            assertRejected(response, 403, "PATH_REJECTED")
        }
        assertRejected(send("GET http://git.test/o/r.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce), 400, "BAD_REQUEST")
        assertThat(upstream.requests).isEmpty()
        scope.close()
    }

    @Test fun `请求体chunked与定长都流式转发且内容一致`() {
        val scope = nonces.issue()
        val payload = Random(7).nextBytes(3 * 1024 * 1024 + 17)
        val reply = Random(9).nextBytes(2 * 1024 * 1024 + 5)
        upstream.respond { _, out -> out.ok("application/x-git-receive-pack-result", reply) }

        val chunked = send(
            "POST /git.test/o/r.git/git-receive-pack HTTP/1.1", scope.nonce,
            "Content-Type: application/x-git-receive-pack-request", "Transfer-Encoding: chunked", "Expect: 100-continue",
            body = payload, chunked = true,
        )
        assertThat(chunked.status).isEqualTo(200)
        assertThat(sha(chunked.body)).isEqualTo(sha(reply))

        val fixed = send(
            "POST /git.test/o/r.git/git-upload-pack HTTP/1.1", scope.nonce,
            "Content-Type: application/x-git-upload-pack-request", "Content-Length: ${payload.size}",
            body = payload,
        )
        assertThat(fixed.status).isEqualTo(200)
        assertThat(sha(fixed.body)).isEqualTo(sha(reply))

        assertThat(upstream.requests.map { it.target }).containsExactly("/o/r.git/git-receive-pack", "/o/r.git/git-upload-pack").inOrder()
        upstream.requests.forEach {
            assertThat(it.method).isEqualTo("POST")
            assertThat(it.bodySha).isEqualTo(sha(payload))
            assertThat(it.bodySize).isEqualTo(payload.size.toLong())
            assertThat(it.header("authorization")).isEqualTo(basic)
        }
        assertThat(upstream.requests.first().header("content-type")).isEqualTo("application/x-git-receive-pack-request")
        scope.close()
    }

    @Test fun `POST缺少长度返回411`() {
        val scope = nonces.issue()
        assertRejected(send("POST /git.test/o/r.git/git-upload-pack HTTP/1.1", scope.nonce), 411, "LENGTH_REQUIRED")
        assertThat(upstream.requests).isEmpty()
        scope.close()
    }

    @Test fun `重定向到已配置主机改写为代理地址`() {
        val scope = nonces.issue()
        upstream.respond { _, out ->
            out.raw("HTTP/1.1 301 Moved Permanently\r\nLocation: http://127.0.0.1:${upstream.port}/new/repo.git/info/refs?service=git-upload-pack\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val response = send("GET /git.test/old/repo.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce)
        assertThat(response.status).isEqualTo(301)
        assertThat(response.header("location")).isEqualTo("http://127.0.0.1:$port/git.test/new/repo.git/info/refs?service=git-upload-pack")
        scope.close()
    }

    @Test fun `重定向到其它主机被拒绝`() {
        val scope = nonces.issue()
        upstream.respond { _, out ->
            out.raw("HTTP/1.1 302 Found\r\nLocation: https://evil.example/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val response = send("GET /git.test/o/r.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce)
        assertRejected(response, 502, "REDIRECT_REJECTED")
        assertThat(response.header("location")).isNull()
        scope.close()
    }

    @Test fun `上游401改为代理说明并标记凭据失效`() {
        val scope = nonces.issue()
        upstream.respond { _, out -> out.unauthorized() }
        val response = send("GET /git.test/o/r.git/info/refs?service=git-receive-pack HTTP/1.1", scope.nonce)
        assertRejected(response, 401, "CREDENTIAL_REJECTED")
        assertThat(response.text).contains("更换该主机的令牌")
        assertThat(response.text).doesNotContain("Bad credentials")
        assertThat(store.credential("git.test")!!.rejectedAtMillis).isNotNull()
        assertThat(store.credential("down.test")!!.rejectedAtMillis).isNull()
        scope.close()
    }

    @Test fun `GitHub登录凭据401提示重新登录，403提示组织授权且不标记`() {
        store.saveGitHubLogin("octo", token.toByteArray())
        mapping.ports["github.com"] = upstream.port
        val scope = nonces.issue()
        upstream.respond { _, out -> out.unauthorized() }
        val rejected = send("GET /github.com/o/r.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce)
        assertRejected(rejected, 401, "CREDENTIAL_REJECTED")
        assertThat(rejected.text).contains("重新登录 GitHub")

        store.saveGitHubLogin("octo", token.toByteArray())
        upstream.respond { _, out ->
            out.raw("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val denied = send("GET /github.com/o/r.git/info/refs?service=git-receive-pack HTTP/1.1", scope.nonce)
        assertRejected(denied, 403, "ACCESS_DENIED")
        assertThat(denied.text).contains("组织管理员")
        assertThat(store.credential("github.com")!!.rejectedAtMillis).isNull()
        scope.close()
    }

    @Test fun `手动令牌的上游403原样返回`() {
        val scope = nonces.issue()
        upstream.respond { _, out ->
            val body = "denied".toByteArray()
            out.raw("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            out.write(body)
        }
        val response = send("GET /git.test/o/r.git/info/refs?service=git-receive-pack HTTP/1.1", scope.nonce)
        assertThat(response.status).isEqualTo(403)
        assertThat(response.header(GitCredentialProxy.ERROR_HEADER)).isNull()
        assertThat(response.text).isEqualTo("denied")
        scope.close()
    }

    private fun OutputStream.unauthorized() {
        val body = "Bad credentials".toByteArray()
        raw("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"GitHub\"\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        write(body)
    }

    @Test fun `上游不可达返回502且只含异常类名`() {
        val scope = nonces.issue()
        val response = send("GET /down.test/o/r.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce)
        assertRejected(response, 502, "UPSTREAM_FAILED")
        assertThat(response.text).contains("down.test")
        assertThat(diagnostics.single { "UPSTREAM_FAILED" in it }).matches("git-proxy: UPSTREAM_FAILED [A-Za-z]+")
        scope.close()
    }

    @Test fun `命令结束时中断进行中的转发并取消上游调用`() {
        val scope = nonces.issue()
        val upstreamClosed = CountDownLatch(1)
        upstream.respond { _, out ->
            out.raw("HTTP/1.1 200 OK\r\nContent-Type: application/x-git-upload-pack-result\r\nConnection: close\r\n\r\n")
            try {
                repeat(2_000) {
                    out.write(ByteArray(1024) { 'A'.code.toByte() })
                    out.flush()
                    Thread.sleep(20)
                }
            } catch (_: IOException) {
                upstreamClosed.countDown()
            }
        }
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write(
                ("GET /git.test/o/r.git/info/refs?service=git-upload-pack HTTP/1.1\r\nHost: x\r\n" +
                    "${GitCredentialProxy.NONCE_HEADER}: ${scope.nonce}\r\n\r\n").toByteArray(),
            )
            val input = socket.getInputStream()
            assertThat(input.readAsciiLine(1024)).isEqualTo("HTTP/1.1 200 OK")
            while (input.readAsciiLine(4096)!!.isNotEmpty()) Unit
            val first = ByteArray(64)
            assertThat(input.read(first)).isGreaterThan(0)

            val started = System.nanoTime()
            scope.close()
            val rest = ByteArrayOutputStream()
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                }
            } catch (_: IOException) {
                // 连接被重置同样表示已中断。
            }
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10)
            // 没有收到正常结束的 chunked 终止块。
            assertThat(String(rest.toByteArray(), Charsets.ISO_8859_1)).doesNotContain("\r\n0\r\n\r\n")
        }
        assertThat(upstreamClosed.await(15, TimeUnit.SECONDS)).isTrue()
        assertThat(nonces.resolve(scope.nonce)).isNull()
    }

    @Test fun `请求头格式错误或超长被拒绝`() {
        val scope = nonces.issue()
        assertRejected(send("BROKEN", scope.nonce), 400, "BAD_REQUEST")
        assertRejected(send("get /git.test/o/r.git/info/refs?service=git-upload-pack HTTP/1.1", scope.nonce), 400, "BAD_REQUEST")
        fun head(text: String) = GitCredentialProxy.readHead(ByteArrayInputStream(text.toByteArray()))
        assertThat(head("GET / HTTP/1.1\r\nX-Pad: " + "a".repeat(20_000) + "\r\n\r\n")).isNull()
        assertThat(head("GET / HTTP/1.1\r\nBad\u0001: x\r\n\r\n")).isNull()
        assertThat(head("GET / HTTP/1.1\r\nNo-Colon\r\n\r\n")).isNull()
        assertThat(head("GET / HTTP/1.1\r\nA: 1\r\nA: 2\r\n\r\n")!!.header("a")).isEqualTo("1, 2")
        assertThat(upstream.requests).isEmpty()
        scope.close()
    }

    @Test fun `safePath只接受规范仓库路径`() {
        assertThat(GitCredentialProxy.safePath("/owner/repo.git/info/refs")).isTrue()
        assertThat(GitCredentialProxy.safePath("/~user/repo%20x.git/git-upload-pack")).isTrue()
        listOf("", "owner/repo", "/owner//repo", "/./repo", "/a/../b", "/a/%2E%2E/b", "/a%5cb", "/a%00b", "/a%7fb", "/a b", "/a\\b")
            .forEach { assertThat(GitCredentialProxy.safePath(it)).isFalse() }
    }

    private fun assertRejected(response: RawResponse, status: Int, code: String) {
        assertThat(response.status).isEqualTo(status)
        assertThat(response.header(GitCredentialProxy.ERROR_HEADER)).isEqualTo(code)
        assertThat(response.text).isNotEmpty()
    }

    private fun send(
        requestLine: String,
        nonce: String?,
        vararg headers: String,
        body: ByteArray? = null,
        chunked: Boolean = false,
    ): RawResponse = Socket(LOOPBACK, port).use { socket ->
        socket.soTimeout = 30_000
        val writer = thread(name = "proxy-test-writer") {
            runCatching {
                val out = socket.getOutputStream().buffered()
                val head = StringBuilder("$requestLine\r\nHost: 127.0.0.1:$port\r\n")
                nonce?.let { head.append("${GitCredentialProxy.NONCE_HEADER}: $it\r\n") }
                headers.forEach { head.append(it).append("\r\n") }
                head.append("\r\n")
                out.write(head.toString().toByteArray())
                if (body != null) {
                    if (chunked) {
                        for (offset in body.indices step 50_000) {
                            val size = minOf(50_000, body.size - offset)
                            out.write("${Integer.toHexString(size)}\r\n".toByteArray())
                            out.write(body, offset, size)
                            out.write("\r\n".toByteArray())
                        }
                        out.write("0\r\n\r\n".toByteArray())
                    } else {
                        out.write(body)
                    }
                }
                out.flush()
            }
        }
        val bytes = socket.getInputStream().readBytes()
        writer.join(10_000)
        RawResponse.parse(bytes).also { responses += it }
    }

    private class RawResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray, val raw: String) {
        val text: String get() = String(body, Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

        companion object {
            fun parse(bytes: ByteArray): RawResponse {
                val input = ByteArrayInputStream(bytes)
                while (true) {
                    val status = input.readAsciiLine(4096)!!.split(' ')[1].toInt()
                    val headers = linkedMapOf<String, MutableList<String>>()
                    while (true) {
                        val line = input.readAsciiLine(65_536)!!
                        if (line.isEmpty()) break
                        headers.getOrPut(line.substringBefore(':').trim().lowercase()) { mutableListOf() } += line.substringAfter(':').trim()
                    }
                    if (status == 100) continue
                    val body = when {
                        headers["transfer-encoding"]?.any { "chunked" in it } == true -> ChunkedInputStream(input).readBytes()
                        headers["content-length"] != null -> input.readNBytesCompat(headers.getValue("content-length").first().toInt())
                        else -> input.readBytes()
                    }
                    return RawResponse(status, headers, body, String(bytes, Charsets.ISO_8859_1))
                }
            }

            private fun InputStream.readNBytesCompat(count: Int): ByteArray {
                val out = ByteArray(count)
                var offset = 0
                while (offset < count) {
                    val read = read(out, offset, count - offset)
                    if (read < 0) break
                    offset += read
                }
                return out.copyOf(offset)
            }
        }
    }

    private class TestUpstream : GitUpstream {
        val ports = linkedMapOf<String, Int>()
        override fun url(hostKey: String): HttpUrl = "http://127.0.0.1:${ports.getValue(hostKey)}/".toHttpUrl()
        override fun hostKey(url: HttpUrl): String? =
            if (url.host == "127.0.0.1") ports.entries.firstOrNull { it.value == url.port }?.key else null
    }

    private class Recorded(val method: String, val target: String, val rawHead: String, private val headers: Map<String, String>, val bodySha: String, val bodySize: Long) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** 最小 HTTP/1.1 上游：记录请求头与请求体摘要，按 [respond] 写原始响应后关闭连接。 */
    private class FakeUpstream : AutoCloseable {
        private val server = ServerSocket(0, 50, LOOPBACK)
        val port: Int = server.localPort
        val requests = CopyOnWriteArrayList<Recorded>()
        @Volatile private var responder: (Recorded, OutputStream) -> Unit = { _, out -> out.ok("text/plain", "ok".toByteArray()) }

        init {
            thread(isDaemon = true, name = "fake-upstream") {
                while (true) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    thread(isDaemon = true) { socket.use { serve(it) } }
                }
            }
        }

        fun respond(block: (Recorded, OutputStream) -> Unit) { responder = block }

        private fun serve(socket: Socket) {
            socket.soTimeout = 30_000
            val input = socket.getInputStream().buffered()
            val requestLine = input.readAsciiLine(8192) ?: return
            val raw = StringBuilder(requestLine).append('\n')
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.readAsciiLine(65_536) ?: return
                if (line.isEmpty()) break
                raw.append(line).append('\n')
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val body: InputStream? = when {
                headers["transfer-encoding"]?.contains("chunked") == true -> ChunkedInputStream(input)
                headers["content-length"] != null -> BoundedInputStream(input, headers.getValue("content-length").toLong())
                else -> null
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            if (body != null) {
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = body.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
            val parts = requestLine.split(' ')
            val recorded = Recorded(parts[0], parts[1], raw.toString(), headers, digest.digest().hex(), size)
            requests += recorded
            val out = socket.getOutputStream().buffered()
            try {
                responder(recorded, out)
                out.flush()
            } catch (_: IOException) {
            }
        }

        override fun close() = server.close()
    }

    private companion object {
        fun OutputStream.raw(text: String) = write(text.toByteArray(Charsets.UTF_8))

        fun OutputStream.ok(type: String, body: ByteArray, vararg extra: String) {
            raw("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" + extra.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")
            write(body)
        }

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
}
