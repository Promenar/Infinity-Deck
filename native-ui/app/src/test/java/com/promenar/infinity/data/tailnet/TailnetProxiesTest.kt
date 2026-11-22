package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TailnetProxiesTest {
    private val user = "UserUserUserUserUser0001"
    private val password = "PassPassPassPassPass0001"

    private class FakeSource(
        @Volatile var endpoint: TailnetProxyEndpoint?,
        private val tailnet: (String) -> Boolean = { TailnetHosts.isTailnetHost(it, "tail1.ts.net", setOf("nas")) },
    ) : TailnetEndpointSource {
        var awaited = 0
        override fun isTailnetHost(host: String) = tailnet(host)
        override fun currentEndpoint() = endpoint
        override fun awaitEndpoint(timeoutMillis: Long): TailnetProxyEndpoint? {
            awaited++
            return endpoint
        }
    }

    /** 记录收到的请求头；没有正确凭据时回复 407，有凭据时回复 200（CONNECT 之后直接断开）。 */
    private inner class FakeProxy {
        val server = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
        private val expected = Credentials.basic(user, password)
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val output = it.getOutputStream()
                            while (true) {
                                val requestLine = input.readLine() ?: break
                                var auth: String? = null
                                while (true) {
                                    val header = input.readLine() ?: break
                                    if (header.isEmpty()) break
                                    if (header.startsWith("Proxy-Authorization:", ignoreCase = true)) auth = header.substringAfter(":").trim()
                                }
                                requests += requestLine to auth
                                if (auth != expected) {
                                    output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"infinity-tailnet\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    output.flush()
                                    continue
                                }
                                if (requestLine.startsWith("CONNECT")) break
                                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                output.flush()
                                break
                            }
                        }
                    }
                }
            }
        }
        val endpoint get() = TailnetProxyEndpoint(server.localPort, user, password)
    }

    private val proxies = mutableListOf<FakeProxy>()

    @After
    fun tearDown() {
        proxies.forEach { it.server.close() }
    }

    @Test
    fun `未启用或非 tailnet 目标交给原有选择器`() {
        val direct = listOf(Proxy.NO_PROXY)
        val disabled = TailnetProxySelector(FakeSource(endpoint = null, tailnet = { false }), fallback = { direct })
        assertThat(disabled.select(URI("https://100.64.0.1/"))).isEqualTo(direct)
        assertThat(DisabledTailnetRouting.proxySelector.select(URI("https://api.openai.com/"))).containsExactly(Proxy.NO_PROXY)
        assertThat(DisabledTailnetRouting.subprocessEnvironment()).isEmpty()
        assertThat(DisabledTailnetRouting.jschProxy("100.64.0.1")).isNull()

        val source = FakeSource(TailnetProxyEndpoint(40001, user, password))
        val selector = TailnetProxySelector(source, fallback = { direct })
        assertThat(selector.select(URI("https://api.openai.com/v1"))).isEqualTo(direct)
        assertThat(selector.select(URI("http://192.168.1.2:8080/"))).isEqualTo(direct)
        assertThat(source.awaited).isEqualTo(0)
    }

    @Test
    fun `tailnet 目标返回本机 HTTP 代理，子进程未就绪时回落`() {
        val direct = listOf(Proxy.NO_PROXY)
        val source = FakeSource(TailnetProxyEndpoint(40002, user, password))
        val selector = TailnetProxySelector(source, fallback = { direct })
        for (uri in listOf("https://nas.tail1.ts.net/", "https://100.100.1.2:8443/", "https://nas/", "https://[fd7a:115c:a1e0::5]/")) {
            val proxy = selector.select(URI(uri)).single()
            assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP)
            val address = proxy.address() as InetSocketAddress
            assertThat(address.address.address).isEqualTo(byteArrayOf(127, 0, 0, 1))
            assertThat(address.port).isEqualTo(40002)
        }
        source.endpoint = null
        assertThat(selector.select(URI("https://nas.tail1.ts.net/"))).isEqualTo(direct)
    }

    @Test
    fun `OkHttp 经代理访问 tailnet 时只对本机代理补上本次凭据`() {
        val proxy = FakeProxy().also(proxies::add)
        val source = FakeSource(proxy.endpoint)
        val client = OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector(source, fallback = { listOf(Proxy.NO_PROXY) }))
            .proxyAuthenticator(TailnetProxyAuthenticator(source))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url("http://nas.tail1.ts.net:8080/status").build()).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.body!!.string()).isEqualTo("ok")
        }
        assertThat(proxy.requests.map { it.first }).contains("GET http://nas.tail1.ts.net:8080/status HTTP/1.1")
        assertThat(proxy.requests.last().second).isEqualTo(Credentials.basic(user, password))

        proxy.requests.clear()
        runCatching { client.newCall(Request.Builder().url("https://nas.tail1.ts.net/").build()).execute().close() }
        val connect = proxy.requests.first { it.first.startsWith("CONNECT") }
        assertThat(connect.first).isEqualTo("CONNECT nas.tail1.ts.net:443 HTTP/1.1")
        assertThat(connect.second).isEqualTo(Credentials.basic(user, password))
    }

    @Test
    fun `凭据错误时不无限重试，其它代理收不到凭据`() {
        val proxy = FakeProxy().also(proxies::add)
        val wrong = FakeSource(TailnetProxyEndpoint(proxy.server.localPort, user, "WrongWrongWrongWrong0000"))
        val client = OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector(wrong, fallback = { listOf(Proxy.NO_PROXY) }))
            .proxyAuthenticator(TailnetProxyAuthenticator(wrong))
            .build()
        client.newCall(Request.Builder().url("http://nas:8080/").build()).execute().use { response ->
            assertThat(response.code).isEqualTo(407)
        }
        assertThat(proxy.requests.size).isAtMost(2)

        // 系统代理等其它代理的 407：认证器返回 null，不泄露 tailnet 代理凭据。
        val other = FakeProxy().also(proxies::add)
        val elsewhere = FakeSource(TailnetProxyEndpoint(40003, user, password))
        val systemProxied = OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector(elsewhere, fallback = { listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(other.server.inetAddress, other.server.localPort))) }))
            .proxyAuthenticator(TailnetProxyAuthenticator(elsewhere))
            .build()
        systemProxied.newCall(Request.Builder().url("http://public.example.org/").build()).execute().use { response ->
            assertThat(response.code).isEqualTo(407)
        }
        assertThat(other.requests.map { it.second }.toSet()).containsExactly(null)
    }

    @Test
    fun `子进程环境与 JSch 代理携带本次凭据`() {
        val endpoint = TailnetProxyEndpoint(40004, user, password)
        val env = TailnetProxyConfig.environment(endpoint)
        assertThat(env["NO_PROXY"]).isEqualTo("localhost,127.0.0.1,::1")
        assertThat(env["no_proxy"]).isEqualTo("localhost,127.0.0.1,::1")
        assertThat(env["https_proxy"]).isEqualTo("http://$user:$password@127.0.0.1:40004")
        assertThat(env["HTTPS_PROXY"]).isEqualTo(env["https_proxy"])
        assertThat(env["http_proxy"]).isEqualTo(env["https_proxy"])
        assertThat(env["all_proxy"]).isEqualTo("socks5h://$user:$password@127.0.0.1:40004")
        assertThat(TailnetProxyConfig.jsch(endpoint)).isInstanceOf(com.jcraft.jsch.ProxySOCKS5::class.java)
    }

    @Test
    fun `明文 HTTP 经预认证拦截器首跳直通支持 isOneShot 上传且不泄漏给其它代理`() {
        val proxy = FakeProxy().also(proxies::add)
        val source = FakeSource(proxy.endpoint)
        val client = OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector(source, fallback = { listOf(Proxy.NO_PROXY) }))
            .proxyAuthenticator(TailnetProxyAuthenticator(source))
            .addNetworkInterceptor(TailnetPreemptiveProxyAuthInterceptor(source))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val oneShotBody = object : okhttp3.RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8("stream-payload")
            }
        }
        client.newCall(Request.Builder().url("http://nas.tail1.ts.net:8080/upload").post(oneShotBody).build())
            .execute().use { response ->
                assertThat(response.code).isEqualTo(200)
            }
        // 首跳即注入 Proxy-Authorization，不存在先 407 再重试的往返。
        assertThat(proxy.requests).hasSize(1)
        assertThat(proxy.requests.single().second).isEqualTo(Credentials.basic(user, password))

        // 非本机代理绝不收到 Tailnet 代理凭据。
        val other = FakeProxy().also(proxies::add)
        val elsewhere = FakeSource(TailnetProxyEndpoint(40003, user, password))
        val systemClient = OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector(elsewhere, fallback = { listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(other.server.inetAddress, other.server.localPort))) }))
            .proxyAuthenticator(TailnetProxyAuthenticator(elsewhere))
            .addNetworkInterceptor(TailnetPreemptiveProxyAuthInterceptor(elsewhere))
            .build()
        systemClient.newCall(Request.Builder().url("http://public.example.org/test").build()).execute().use { response ->
            assertThat(response.code).isEqualTo(407)
        }
        assertThat(other.requests.map { it.second }.toSet()).containsExactly(null)
    }

    @Test
    fun `端口必须有效`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TailnetProxyEndpoint(0, user, password) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TailnetProxyEndpoint(65536, user, password) }
    }
}
