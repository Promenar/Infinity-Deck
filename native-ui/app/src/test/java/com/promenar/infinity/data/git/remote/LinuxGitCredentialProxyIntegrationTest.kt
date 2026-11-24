package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.LinuxGitAuthGuidance
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinuxGitCredentialProxyIntegrationTest {
    private lateinit var root: File
    private lateinit var git: String
    private lateinit var bare: File
    private lateinit var upstream: CgiUpstream
    private lateinit var proxy: GitCredentialProxy
    private lateinit var scope: GitNonceRegistry.Scope
    private val store = SecretGitCredentialStore(MemorySecrets())
    private val nonces = GitNonceRegistry()
    private val sequence = AtomicInteger()
    private val basic = Credentials.basic("test-user", "SyntheticGitTestOnly123")

    @Before fun setUp() {
        root = Files.createTempDirectory("linux-git-proxy").toFile()
        val executable = listOf("/usr/bin/git", "git").firstOrNull { candidate ->
            runCatching { command(candidate, listOf("--version")).code == 0 }.getOrDefault(false)
        }
        assumeTrue("需要本机 git", executable != null)
        git = requireNotNull(executable)
        bare = File(root, "owner/repo.git")
        git("init", "--bare", "--initial-branch=main", bare.path).success()
        git("--git-dir", bare.path, "config", "http.receivepack", "true").success()
        listOf("git.test", "git.test:8443").forEach { host ->
            store.save(host, "test-user", "SyntheticGitTestOnly123".toByteArray())
        }
        store.saveGitHubLogin("test-user", "SyntheticGitTestOnly123".toByteArray())
        upstream = CgiUpstream()
        val mapping = object : GitUpstream {
            override fun url(hostKey: String): HttpUrl = upstream.url.toHttpUrl()
            override fun hostKey(url: HttpUrl): String? = null
        }
        // 测试的上游必须直连本机，不受执行测试的宿主代理配置影响。
        proxy = GitCredentialProxy(store, nonces, OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), mapping)
        proxy.start()
        scope = nonces.issue()
    }

    @After fun tearDown() {
        if (::scope.isInitialized) scope.close()
        if (::proxy.isInitialized) proxy.close()
        if (::upstream.isInitialized) upstream.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun lease(host: String = "git.test") = mapOf(
        GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:${proxy.port}",
        GitRemoteBroker.ENV_NONCE to scope.nonce,
        GitRemoteBroker.ENV_CREDENTIAL_HOSTS to host,
    )

    @Test fun `原生git通过凭据代理克隆提交推送且裸仓库可见`() {
        val environment = LinuxGitEnvironment.from(lease())
        val clone = File(root, "clone")
        git("clone", "https://git.test/owner/repo.git", clone.path, environment = environment).success()
        File(clone, "roundtrip.txt").writeText("Linux git round trip\n")
        git("-C", clone.path, "add", "roundtrip.txt").success()
        git("-C", clone.path, "commit", "-m", "凭据代理往返测试").success()
        git("-C", clone.path, "push", "origin", "main", environment = environment).success()
        assertThat(git("--git-dir", bare.path, "show", "main:roundtrip.txt").success().output)
            .isEqualTo("Linux git round trip\n")
        assertThat(upstream.requests.any { it.path.endsWith("/git-receive-pack") }).isTrue()
        assertAuthenticated()
    }

    @Test fun `带非443端口的主机同样可克隆`() {
        git("clone", "https://git.test:8443/owner/repo.git", File(root, "clone-port").path,
            environment = LinuxGitEnvironment.from(lease("git.test:8443"))).success()
        assertAuthenticated()
    }

    @Test fun `原生Git经通用HTTPS路由匿名读取仓库`() {
        val origin = "http://127.0.0.1:${proxy.port}"
        git(
            "-c", "url.$origin/https/.insteadOf=https://",
            "-c", "http.$origin/.extraHeader=${GitCredentialProxy.NONCE_HEADER}: ${scope.nonce}",
            "ls-remote", "https://public.test/public/repo.git",
        ).success()
        assertThat(upstream.failures).isEmpty()
        assertThat(upstream.requests).isNotEmpty()
        upstream.requests.forEach { request ->
            assertThat(request.authorization).isNull()
            assertThat(request.nonce).isNull()
        }
    }

    @Test fun `不可达环境代理不会劫持应用凭据代理`() {
        val environment = LinuxGitEnvironment.from(lease()) + mapOf(
            "HTTP_PROXY" to "http://127.0.0.1:1", "http_proxy" to "http://127.0.0.1:1",
            "HTTPS_PROXY" to "http://127.0.0.1:1", "https_proxy" to "http://127.0.0.1:1",
            "ALL_PROXY" to "http://127.0.0.1:1", "all_proxy" to "http://127.0.0.1:1",
            "NO_PROXY" to "", "no_proxy" to "",
        )
        git("clone", "https://git.test/owner/repo.git", File(root, "clone-no-proxy").path,
            environment = environment).success()
        assertAuthenticated()
    }

    @Test fun `访问非代理前缀时不发送nonce且代理不把nonce转发到上游`() {
        val environment = LinuxGitEnvironment.from(lease())
        git("ls-remote", "https://git.test/owner/repo.git", environment = environment).success()
        git("ls-remote", upstream.url + "public/repo.git", environment = environment).success()
        val direct = upstream.requests.filter { it.path.startsWith("/public/") }
        assertThat(direct).isNotEmpty()
        direct.forEach { request ->
            assertThat(request.nonce).isNull()
            assertThat(request.authorization).isNull()
        }
        assertThat(upstream.requests.none { it.nonce != null }).isTrue()
    }

    @Test fun `上游401迅速失败且得到重新授权引导`() {
        upstream.status = 401
        val started = System.nanoTime()
        val result = git("ls-remote", "https://git.test/owner/repo.git", environment = LinuxGitEnvironment.from(lease()))
        assertThat(result.code).isNotEqualTo(0)
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(8)
        assertThat(result.output).contains("terminal prompts disabled")
        assertThat(LinuxGitAuthGuidance.hint(result.code, result.output)).contains("重新登录 GitHub")
        assertThat(store.credential("git.test")!!.rejectedAtMillis).isNotNull()
        assertAuthenticated()
    }

    @Test fun `GitHub登录凭据403可见组织授权说明且不重复引导`() {
        upstream.status = 403
        val result = git("ls-remote", "https://github.com/owner/repo.git", environment = LinuxGitEnvironment.from(lease("github.com")))
        assertThat(result.code).isNotEqualTo(0)
        assertThat(result.output).contains("remote: ")
        assertThat(result.output).contains("组织管理员")
        assertThat(LinuxGitAuthGuidance.hint(result.code, result.output)).isNull()
        assertThat(store.credential("github.com")!!.rejectedAtMillis).isNull()
        assertAuthenticated()
    }

    @Test fun `已吊销nonce与未配置主机的代理拒绝正文可见且不访问上游`() {
        val environment = LinuxGitEnvironment.from(lease())
        scope.close()
        val revoked = git("ls-remote", "https://git.test/owner/repo.git", environment = environment)
        assertThat(revoked.code).isNotEqualTo(0)
        assertThat(revoked.output).contains("remote: ")
        assertThat(revoked.output).contains("Git 凭据代理只服务正在执行的命令")
        assertThat(LinuxGitAuthGuidance.hint(revoked.code, revoked.output)).isNull()

        scope = nonces.issue()
        val unconfigured = git("ls-remote", "https://missing.test/owner/repo.git",
            environment = LinuxGitEnvironment.from(lease("missing.test")))
        assertThat(unconfigured.code).isNotEqualTo(0)
        assertThat(unconfigured.output).contains("未在 Git 凭据中配置")
        assertThat(LinuxGitAuthGuidance.hint(unconfigured.code, unconfigured.output)).isNull()
        assertThat(upstream.requests).isEmpty()
    }

    private fun assertAuthenticated() {
        assertThat(upstream.failures).isEmpty()
        assertThat(upstream.requests).isNotEmpty()
        upstream.requests.forEach { request ->
            assertThat(request.authorization).isEqualTo(basic)
            assertThat(request.nonce).isNull()
        }
    }

    private data class Result(val code: Int, val output: String) {
        fun success(): Result {
            assertThat(code).isEqualTo(0)
            return this
        }
    }

    private fun git(vararg arguments: String, environment: Map<String, String> = emptyMap()) =
        command(git, arguments.toList(), environment)

    /** 所有子进程清空宿主环境，隔离 Git 配置、凭据助手、身份与提示；文件重定向避免输出管道死锁。 */
    private fun command(executable: String, arguments: List<String>, extra: Map<String, String> = emptyMap(), input: ByteArray = byteArrayOf()): Result {
        val id = sequence.incrementAndGet()
        val stdin = File(root, "stdin-$id").apply { writeBytes(input) }
        val stdout = File(root, "stdout-$id")
        val stderr = File(root, "stderr-$id")
        val process = ProcessBuilder(listOf(executable) + arguments).directory(root)
            .redirectInput(stdin).redirectOutput(stdout).redirectError(stderr).apply {
                environment().clear()
                environment().putAll(mapOf(
                    "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"), "HOME" to File(root, "home").apply { mkdirs() }.path,
                    "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_TERMINAL_PROMPT" to "0",
                    "GIT_AUTHOR_NAME" to "Test", "GIT_AUTHOR_EMAIL" to "test@example.test",
                    "GIT_COMMITTER_NAME" to "Test", "GIT_COMMITTER_EMAIL" to "test@example.test", "LC_ALL" to "C",
                    // 改写失效时也只能尝试回环代理，禁止测试触达真实主机。
                    "HTTP_PROXY" to "http://127.0.0.1:1", "http_proxy" to "http://127.0.0.1:1",
                    "HTTPS_PROXY" to "http://127.0.0.1:1", "https_proxy" to "http://127.0.0.1:1",
                    "ALL_PROXY" to "http://127.0.0.1:1", "all_proxy" to "http://127.0.0.1:1",
                    "NO_PROXY" to "127.0.0.1", "no_proxy" to "127.0.0.1",
                ) + extra)
            }.start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "git 子进程超时" }
            return Result(process.exitValue(), stdout.readText() + stderr.readText())
        } finally {
            stopProcessTree(process)
        }
    }

    private fun stopProcessTree(process: Process) {
        // Android 单测类路径没有 ProcessHandle；只结束父进程，git 派生的 remote-http 等随管道断开退出。
        if (!process.isAlive) return
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }

    private data class Request(val path: String, val authorization: String?, val nonce: String?)

    /** 测试上游仅监听回环，将 HTTP 请求转换为 git http-backend 的 CGI 输入。 */
    private inner class CgiUpstream : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "git-cgi-test").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/"
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<String>()
        @Volatile var status = 200

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try { exchange.use { serve(it) } }
                catch (failure: Exception) { failures += failure.javaClass.simpleName; exchange.close() }
            }
            server.start()
        }

        private fun serve(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val request = Request(path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst(GitCredentialProxy.NONCE_HEADER))
            requests += request
            val direct = path.startsWith("/public/")
            val code = if (!direct && request.authorization != basic) 401 else status
            if (code != 200) {
                val bytes = "测试上游拒绝请求\n".toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                return
            }
            val input = exchange.requestBody.readBytes()
            val id = sequence.incrementAndGet()
            val stdin = File(root, "cgi-in-$id").apply { writeBytes(input) }
            val stdout = File(root, "cgi-out-$id")
            val stderr = File(root, "cgi-err-$id")
            val process = ProcessBuilder(git, "http-backend").directory(root)
                .redirectInput(stdin).redirectOutput(stdout).redirectError(stderr).apply {
                    environment().clear()
                    environment().putAll(mapOf(
                        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"), "HOME" to File(root, "home").path,
                        "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null",
                        "GIT_PROJECT_ROOT" to root.path, "GIT_HTTP_EXPORT_ALL" to "1",
                        "REQUEST_METHOD" to exchange.requestMethod,
                        "PATH_INFO" to if (direct) path.replaceFirst("/public/", "/owner/") else path,
                        "QUERY_STRING" to (exchange.requestURI.rawQuery ?: ""),
                        "CONTENT_TYPE" to (exchange.requestHeaders.getFirst("Content-Type") ?: ""),
                        "CONTENT_LENGTH" to input.size.toString(),
                        "HTTP_CONTENT_ENCODING" to (exchange.requestHeaders.getFirst("Content-Encoding") ?: ""),
                        "GIT_PROTOCOL" to (exchange.requestHeaders.getFirst("Git-Protocol") ?: ""),
                        "REMOTE_USER" to "test-user",
                    ))
                }.start()
            val bytes = try {
                check(process.waitFor(8, TimeUnit.SECONDS)) { "CGI 子进程超时" }
                check(process.exitValue() == 0) { "CGI 子进程失败" }
                stdout.readBytes()
            } finally {
                stopProcessTree(process)
            }
            val separator = "\r\n\r\n".toByteArray()
            val offset = (0..bytes.size - separator.size).firstOrNull { index -> separator.indices.all { bytes[index + it] == separator[it] } }
                ?: error("CGI 响应缺少头部结束符")
            var responseCode = 200
            String(bytes, 0, offset, Charsets.UTF_8).split("\r\n").forEach { line ->
                val name = line.substringBefore(':')
                val value = line.substringAfter(':').trim()
                if (name.equals("Status", ignoreCase = true)) responseCode = value.substringBefore(' ').toInt()
                else exchange.responseHeaders.add(name, value)
            }
            val body = bytes.copyOfRange(offset + separator.size, bytes.size)
            exchange.sendResponseHeaders(responseCode, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(12, TimeUnit.SECONDS)
        }
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    }
}
