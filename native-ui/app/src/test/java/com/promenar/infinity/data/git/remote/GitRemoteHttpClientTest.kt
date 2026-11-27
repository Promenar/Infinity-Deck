package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.proxy.BuiltInProxyEndpoint
import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetEndpointSource
import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.data.tailnet.TailnetPreemptiveProxyAuthInterceptor
import com.promenar.infinity.data.tailnet.TailnetProxyAuthenticator
import com.promenar.infinity.data.tailnet.TailnetProxyEndpoint
import com.promenar.infinity.data.tailnet.TailnetProxySelector
import com.promenar.infinity.data.tailnet.TailnetRouting
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Address
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GitRemoteHttpClientTest {
    private val tailnet = TestTailnet()
    private val builtIn = TestBuiltIn(BuiltInProxyEndpoint(21082, "relay-test", "relay-test-password"))
    private val fallbackProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, 21083))
    private val fallback = object : ProxySelector() {
        override fun select(uri: URI?) = listOf(fallbackProxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
    private val client = GitRemoteHttpClient.create(tailnet, builtIn, fallback)
    private val publicUri = URI("https://github.com/")

    @Test fun `两种代理同时运行时 Tailnet 主机优先走 Tailscale`() {
        val expected = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, 21081))
        for (host in listOf("nas", "git.example.ts.net", "100.64.0.1", "[fd7a:115c:a1e0::1]", "[::ffff:100.64.0.1]")) {
            assertThat(client.proxySelector.select(URI("https://$host/"))).containsExactly(expected)
        }
    }

    @Test fun `Tailnet 字面量独立于 isTailnetHost 委托给 Tailnet 选择器`() {
        val routing = object : TailnetRouting by tailnet {
            override fun isTailnetHost(host: String) = false
            override val proxySelector = fallback
        }
        val selector = GitRemoteHttpClient.create(routing, builtIn).proxySelector
        assertThat(selector.select(URI("https://100.127.255.254/"))).containsExactly(fallbackProxy)
        assertThat(selector.select(URI("https://[fd7a:115c:a1e0::2]/"))).containsExactly(fallbackProxy)
    }

    @Test fun `本机与私网目标交给备用选择器`() {
        for (host in listOf(
            "127.0.0.1", "[::1]", "10.0.0.1", "172.31.0.1", "192.168.1.1", "169.254.1.1",
            "[fd00::1]", "[fe80::1]", "[::ffff:192.168.1.1]", "LOCALHOST.", "git.localhost", "nas.local.",
            "router.lan", "searxng.internal", "home.home.arpa", "intranet",
        )) {
            assertThat(client.proxySelector.select(URI("https://$host/"))).containsExactly(fallbackProxy)
        }
    }

    @Test fun `公网路由随内置代理启停更新`() {
        val selector = client.proxySelector
        val endpoint = requireNotNull(builtIn.endpoint.value)
        assertThat(selector.select(publicUri)).containsExactly(endpoint.proxy)
        assertThat(selector.select(URI("https://gitlab.com/"))).containsExactly(endpoint.proxy)

        builtIn.endpoint.value = null
        assertThat(selector.select(publicUri)).containsExactly(fallbackProxy)

        val restarted = endpoint.copy(port = 21084)
        builtIn.endpoint.value = restarted
        assertThat(selector.select(publicUri)).containsExactly(restarted.proxy)
    }

    @Test fun `407 仅对匹配的代理地址和 realm 应答`() {
        val tailnetProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, 21081))
        val builtInProxy = requireNotNull(builtIn.endpoint.value).proxy
        val tailnetChallenge = "Basic realm=\"infinity-tailnet-tail-test\""
        val builtInChallenge = "Basic realm=\"infinity-proxy-relay-test\""

        assertThat(authenticate(tailnetProxy, tailnetChallenge))
            .isEqualTo(Credentials.basic("tail-test", "tail-test-password"))
        assertThat(authenticate(builtInProxy, builtInChallenge))
            .isEqualTo(Credentials.basic("relay-test", "relay-test-password"))
        assertThat(authenticate(tailnetProxy, builtInChallenge)).isNull()
        assertThat(authenticate(builtInProxy, tailnetChallenge)).isNull()
        assertThat(authenticate(tailnetProxy, "Basic realm=\"unexpected\"")).isNull()
        assertThat(authenticate(builtInProxy, "Basic realm=\"unexpected\"")).isNull()
        assertThat(authenticate(fallbackProxy, tailnetChallenge)).isNull()
        assertThat(authenticate(fallbackProxy, builtInChallenge)).isNull()
        assertThat(authenticate(tailnetProxy, tailnetChallenge, method = "GET")).isNull()
        assertThat(authenticate(builtInProxy, builtInChallenge, method = "GET")).isNull()
    }

    @Test fun `预认证只接受 OkHttp 合成的 CONNECT 质询`() {
        val tailnetProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, 21081))
        val builtInProxy = requireNotNull(builtIn.endpoint.value).proxy
        val challenge = "OkHttp-Preemptive"

        assertThat(authenticate(tailnetProxy, challenge)).isNull()
        assertThat(authenticate(builtInProxy, challenge)).isNull()
        assertThat(authenticate(tailnetProxy, challenge, preemptive = true))
            .isEqualTo(Credentials.basic("tail-test", "tail-test-password"))
        assertThat(authenticate(builtInProxy, challenge, preemptive = true))
            .isEqualTo(Credentials.basic("relay-test", "relay-test-password"))
    }

    @Test fun `Git 凭据代理向内置代理发送带认证的 HTTPS CONNECT`() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 5_000
            builtIn.endpoint.value = BuiltInProxyEndpoint(server.localPort, "connect-test", "connect-test-password")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val recorded = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val headers = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
                        // 记录 CONNECT 后主动结束，测试全程只使用本机回环连接。
                        socket.getOutputStream().write(
                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        headers
                    }
                }
                val nonces = GitNonceRegistry()
                val outbound = client.newBuilder()
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            throw UnknownHostException("CONNECT 的目标应由代理解析")
                    })
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
                GitCredentialProxy(SecretGitCredentialStore(MemorySecrets()), nonces, outbound).use { gitProxy ->
                    val port = gitProxy.start()
                    nonces.issue().use { scope ->
                        Socket(LOOPBACK, port).use { socket ->
                            socket.soTimeout = 5_000
                            socket.getOutputStream().write(
                                ("GET /https/git.example.test/owner/repo.git/info/refs?service=git-upload-pack HTTP/1.1\r\n" +
                                    "Host: localhost\r\n${GitCredentialProxy.NONCE_HEADER}: ${scope.nonce}\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            assertThat(socket.getInputStream().bufferedReader().readLine()).startsWith("HTTP/1.1 502")
                        }
                    }
                }
                val request = recorded.get(5, TimeUnit.SECONDS)
                assertThat(request.first()).isEqualTo("CONNECT git.example.test:443 HTTP/1.1")
                assertThat(request.filter { it.startsWith("Proxy-Authorization:", ignoreCase = true) })
                    .containsExactly("Proxy-Authorization: ${Credentials.basic("connect-test", "connect-test-password")}")
                assertThat(request.none { it.startsWith("X-Infinity-Public-Target:", ignoreCase = true) }).isTrue()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun authenticate(
        proxy: Proxy,
        challenge: String,
        method: String = "CONNECT",
        preemptive: Boolean = false,
    ): String? {
        val url = "https://github.com/".toHttpUrl()
        val address = Address(
            url.host, url.port, client.dns, client.socketFactory, client.sslSocketFactory,
            client.hostnameVerifier, client.certificatePinner, client.proxyAuthenticator, null,
            client.protocols, client.connectionSpecs, client.proxySelector,
        )
        val route = Route(address, proxy, proxy.address() as InetSocketAddress)
        val response = Response.Builder()
            .request(Request.Builder().url(url).method(method, null).build())
            .protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required")
            .header("Proxy-Authenticate", challenge)
            .apply { if (preemptive) sentRequestAtMillis(-1L).receivedResponseAtMillis(-1L) }
            .build()
        return client.proxyAuthenticator.authenticate(route, response)?.header("Proxy-Authorization")
    }

    private class TestBuiltIn(initial: BuiltInProxyEndpoint) : BuiltInProxyRouting {
        override val endpoint = MutableStateFlow<BuiltInProxyEndpoint?>(initial)
    }

    private class TestTailnet : TailnetRouting by DisabledTailnetRouting, TailnetEndpointSource {
        private val endpoint = TailnetProxyEndpoint(21081, "tail-test", "tail-test-password")
        override fun isTailnetHost(host: String) = TailnetHosts.isTailnetHost(host, null, setOf("nas"))
        override fun currentEndpoint() = endpoint
        override fun awaitEndpoint(timeoutMillis: Long) = endpoint
        override val proxySelector = TailnetProxySelector(this)
        override val proxyAuthenticator = TailnetProxyAuthenticator(this)
        override val proxyAuthInterceptor = TailnetPreemptiveProxyAuthInterceptor(this)
    }
}
