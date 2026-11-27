package com.promenar.infinity.data.proxy

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.inference.ModelDownloader
import com.promenar.infinity.data.model.catalog.PagesCatalogTransport
import com.promenar.infinity.data.remote.webdav.createDefaultWebDavOkHttpClient
import com.promenar.infinity.data.update.createAppUpdateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Address
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
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
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OptionalBuiltInProxyHttpTest {
    private val fallbackProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, 22083))
    private val fallback = object : ProxySelector() {
        override fun select(uri: URI?) = listOf(fallbackProxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
    private val routing = MutableRouting(BuiltInProxyEndpoint(22082, "catalog-u", "catalog-p"))

    @Test fun `公网域名随内置代理启停切换路由而本机私网与Tailnet始终绕过`() {
        val client = OkHttpClient.Builder()
            .applyOptionalBuiltInProxy(routing = { routing }, publicTargetOnly = true, fallback = fallback)
            .build()
        val endpoint = requireNotNull(routing.endpoint.value)
        for (publicUrl in listOf(
            "https://promenar.github.io/Infinity-Deck/model-catalog/v1/manifest.json",
            "https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest",
            "https://huggingface.co/model.gguf",
            "https://infinityware.work/skills/index.json",
        )) {
            assertThat(client.proxySelector.select(URI(publicUrl))).containsExactly(endpoint.proxy)
        }
        for (bypassHost in listOf(
            "localhost", "sub.localhost", "nas.local", "router.lan", "svc.internal",
            "host.home.arpa", "node.tail6046d5.ts.net", "intranet",
            "127.0.0.1", "[::1]", "10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "[fd7a:115c:a1e0::1]",
        )) {
            assertThat(client.proxySelector.select(URI("https://$bypassHost/"))).containsExactly(fallbackProxy)
        }
        routing.endpoint.value = null
        assertThat(client.proxySelector.select(URI("https://promenar.github.io/"))).containsExactly(fallbackProxy)

        val restarted = endpoint.copy(port = 22089)
        routing.endpoint.value = restarted
        assertThat(client.proxySelector.select(URI("https://promenar.github.io/"))).containsExactly(restarted.proxy)
    }

    @Test fun `407代理鉴权仅应答匹配端点且附带公网目标头而不泄漏给源站或陌生代理`() {
        val client = OkHttpClient.Builder()
            .applyOptionalBuiltInProxy(routing = { routing }, publicTargetOnly = true, fallback = fallback)
            .build()
        val endpoint = requireNotNull(routing.endpoint.value)
        val expectedAuth = Credentials.basic("catalog-u", "catalog-p")

        val preemptive = authenticate(client, endpoint.proxy, "OkHttp-Preemptive", preemptive = true)
        assertThat(preemptive?.header("Proxy-Authorization")).isEqualTo(expectedAuth)
        assertThat(preemptive?.header("X-Infinity-Public-Target")).isEqualTo("1")

        val realRealm = authenticate(client, endpoint.proxy, "Basic realm=\"infinity-proxy-catalog-u\"")
        assertThat(realRealm?.header("Proxy-Authorization")).isEqualTo(expectedAuth)
        assertThat(realRealm?.header("X-Infinity-Public-Target")).isEqualTo("1")

        // 源站 HTTPS GET 返回 407、错误 realm 或陌生代理均拒绝发送凭据。
        assertThat(authenticate(client, endpoint.proxy, "Basic realm=\"infinity-proxy-catalog-u\"", method = "GET")).isNull()
        assertThat(authenticate(client, endpoint.proxy, "Basic realm=\"wrong-realm\"")).isNull()
        assertThat(authenticate(client, fallbackProxy, "Basic realm=\"infinity-proxy-catalog-u\"")).isNull()
        assertThat(authenticate(client, endpoint.proxy, "OkHttp-Preemptive", preemptive = false)).isNull()
    }

    @Test fun `规格库更新器在内置代理开启时向代理端点发起带公网认证头的CONNECT`() = runTest {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 5_000
            val liveRouting = MutableRouting(BuiltInProxyEndpoint(server.localPort, "cat-user", "cat-pass"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val headersFuture = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val headers = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
                        socket.getOutputStream().write(
                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        headers
                    }
                }
                val transport = PagesCatalogTransport(
                    builtInProxy = { liveRouting },
                    baseUrl = "https://promenar.github.io/Infinity-Deck/model-catalog/v1",
                )
                runCatching { transport.read("manifest.json", 64 * 1024) }
                val headers = headersFuture.get(5, TimeUnit.SECONDS)
                assertThat(headers.first()).startsWith("CONNECT promenar.github.io:443")
                assertThat(headers.any { it.equals("Proxy-Authorization: ${Credentials.basic("cat-user", "cat-pass")}", ignoreCase = true) }).isTrue()
                assertThat(headers.any { it.equals("X-Infinity-Public-Target: 1", ignoreCase = true) }).isTrue()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun `应用更新检查器、本地模型下载器与WebDAV客户端均接入按需内置代理`() {
        val updateClient = createAppUpdateClient { routing }
        val downloadClient = ModelDownloader.createDefaultModelDownloadClient { routing }
        val webDavClient = createDefaultWebDavOkHttpClient { routing }
        val endpoint = requireNotNull(routing.endpoint.value)
        assertThat(updateClient.proxySelector.select(URI("https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest")))
            .containsExactly(endpoint.proxy)
        assertThat(downloadClient.proxySelector.select(URI("https://huggingface.co/test/model.gguf")))
            .containsExactly(endpoint.proxy)
        assertThat(webDavClient.proxySelector.select(URI("https://dav.example.com/backups/")))
            .containsExactly(endpoint.proxy)
        assertThat(webDavClient.proxySelector.select(URI("https://192.168.1.10:5006/backups/")))
            .doesNotContain(endpoint.proxy)
        routing.endpoint.value = null
        assertThat(updateClient.proxySelector.select(URI("https://api.github.com/"))).doesNotContain(endpoint.proxy)
        assertThat(downloadClient.proxySelector.select(URI("https://huggingface.co/"))).doesNotContain(endpoint.proxy)
        assertThat(webDavClient.proxySelector.select(URI("https://dav.example.com/backups/"))).doesNotContain(endpoint.proxy)
    }

    private fun authenticate(
        client: OkHttpClient,
        proxy: Proxy,
        challenge: String,
        method: String = "CONNECT",
        preemptive: Boolean = false,
    ): Request? {
        val url = "https://promenar.github.io/Infinity-Deck/model-catalog/v1/manifest.json"
        val request = Request.Builder().url(url).method(method, null).build()
        val address = Address(
            uriHost = "promenar.github.io",
            uriPort = 443,
            dns = Dns.SYSTEM,
            socketFactory = javax.net.SocketFactory.getDefault(),
            sslSocketFactory = null,
            hostnameVerifier = null,
            certificatePinner = null,
            proxyAuthenticator = okhttp3.Authenticator.NONE,
            proxy = proxy,
            protocols = listOf(Protocol.HTTP_1_1),
            connectionSpecs = listOf(okhttp3.ConnectionSpec.MODERN_TLS),
            proxySelector = fallback,
        )
        val route = Route(address, proxy, InetSocketAddress(LOOPBACK, 443))
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(407)
            .message("Proxy Auth Required")
            .header("Proxy-Authenticate", challenge)
            .sentRequestAtMillis(if (preemptive) -1L else 100L)
            .receivedResponseAtMillis(if (preemptive) -1L else 200L)
            .build()
        return client.proxyAuthenticator.authenticate(route, response)
    }

    private class MutableRouting(initial: BuiltInProxyEndpoint?) : BuiltInProxyRouting {
        override val endpoint = MutableStateFlow(initial)
    }

    companion object {
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
