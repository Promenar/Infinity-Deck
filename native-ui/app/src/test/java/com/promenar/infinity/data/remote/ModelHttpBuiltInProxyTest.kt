package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.proxy.BuiltInProxyEndpoint
import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.proxy.BuiltInProxyUnavailableException
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModelHttpBuiltInProxyTest {
    @After
    fun resetRouting() = ModelHttp.install({ DisabledTailnetRouting }) { null }

    @Test
    fun `启用代理的公网请求在端点缺失时拒绝且无需访问系统代理`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        ModelHttp.install({ tailnet { host -> seen.add(host); Proxy.NO_PROXY } }) { null }
        ModelHttp.client().use { client ->
            val error = assertThrows(BuiltInProxyUnavailableException::class.java) {
                runBlocking {
                    client.get("http://provider.invalid/models") {
                        routeViaBuiltInProxy(true, "provider-a")
                    }
                }
            }
            assertThat(error.message).contains("内置代理")
            assertThrows(BuiltInProxyUnavailableException::class.java) {
                runBlocking {
                    client.get("http://134744072/models") { routeViaBuiltInProxy(true, "provider-a") }
                }
            }
            assertThat(seen).isEmpty()
        }
    }

    @Test
    fun `HTTPS CONNECT 仅发送本机代理凭据且认证失败不走原路由`() = runBlocking {
        val fallback = CopyOnWriteArrayList<String>()
        val thread = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { proxy ->
                proxy.soTimeout = 5_000
                val tunnelRequest = thread.submit<List<String>> {
                    proxy.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        socket.getOutputStream().write(
                            ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                                "Proxy-Authenticate: Basic realm=\"test\"\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        lines
                    }
                }
                ModelHttp.install(
                    { tailnet { host -> fallback.add(host); Proxy.NO_PROXY } },
                    { null },
                    { proxyRouting(proxy.localPort) },
                )
                ModelHttp.client(useBuiltInProxy = true, providerId = "provider-a").use { client ->
                    assertThrows(BuiltInProxyUnavailableException::class.java) {
                        runBlocking {
                            client.get("https://provider.invalid/models") {
                                header("Authorization", "Bearer test-api-key")
                            }
                        }
                    }
                }
                val lines = tunnelRequest.get(5, TimeUnit.SECONDS)
                assertThat(lines.first()).isEqualTo("CONNECT provider.invalid:443 HTTP/1.1")
                assertThat(lines.any { it.equals("Proxy-Authorization: $TEST_PROXY_AUTH", ignoreCase = true) }).isTrue()
                assertThat(lines.any { it.equals("X-Infinity-Public-Target: 1", ignoreCase = true) }).isTrue()
                assertThat(lines.any { it.contains("test-api-key") }).isFalse()
                assertThat(fallback).isEmpty()
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `同一公网目标的代理与原路由及两个提供商各用独立连接`() = runBlocking {
        val builtInRequests = CopyOnWriteArrayList<Pair<Int, String?>>()
        serve { exchange ->
            builtInRequests.add(exchange.remoteAddress.port to exchange.requestHeaders.getFirst("Proxy-Authorization"))
            exchange.respond("built-in")
        }.use { builtIn ->
            serve { it.respond("system") }.use { system ->
                val routing = proxyRouting(builtIn.port)
                ModelHttp.install({ tailnet { system.proxy } }, { null }, { routing })
                ModelHttp.client(useBuiltInProxy = true, providerId = "other").use { client ->
                    suspend fun get(enabled: Boolean, providerId: String) =
                        client.get("http://provider.invalid/models") {
                            routeViaBuiltInProxy(enabled, providerId)
                        }.bodyAsText()
                    assertThat(get(true, "a")).isEqualTo("built-in")
                    assertThat(get(false, "a")).isEqualTo("system")
                    assertThat(get(true, "b")).isEqualTo("built-in")
                    assertThat(get(true, "a")).isEqualTo("built-in")
                    assertThat(builtInRequests.map { it.first }.distinct()).hasSize(2)
                    assertThat(builtInRequests[0].first).isEqualTo(builtInRequests[2].first)
                    assertThat(builtInRequests.map { it.second }).containsExactly(
                        TEST_PROXY_AUTH, TEST_PROXY_AUTH, TEST_PROXY_AUTH,
                    )
                    routing.endpoint.value = null
                    assertThrows(BuiltInProxyUnavailableException::class.java) {
                        runBlocking { get(true, "a") }
                    }
                    assertThat(get(false, "a")).isEqualTo("system")
                    assertThat(builtInRequests).hasSize(3)
                }
            }
        }
    }

    @Test
    fun `Tailnet 局域网和回环保持原路由且允许同源重定向`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        serve { exchange ->
            seen.add(exchange.requestURI.host ?: exchange.requestHeaders.getFirst("Host"))
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.set("Location", "/models")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                exchange.respond("old-route")
            }
        }.use { system ->
            ModelHttp.install(routing = { tailnet { system.proxy } }, gate = { null })
            ModelHttp.client(useBuiltInProxy = true, providerId = "private").use { client ->
                listOf("gpu.tailnet.ts.net", "100.64.10.8", "192.168.1.8", "gpu.local", "127.0.0.1")
                    .forEach { host ->
                        assertThat(client.get("http://$host/redirect").bodyAsText()).isEqualTo("old-route")
                    }
            }
            assertThat(seen).hasSize(10)
        }
    }

    @Test
    fun `开启代理时拒绝跨源重定向且不会向新地址转发自定义认证头`() = runBlocking {
        val redirected = CopyOnWriteArrayList<String>()
        serve { exchange ->
            redirected.add(exchange.requestHeaders.getFirst("x-api-key").orEmpty())
            exchange.respond("redirected")
        }.use { target ->
            serve { exchange ->
                exchange.responseHeaders.set("Location", "http://127.0.0.1:${target.port}/models")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }.use { builtIn ->
                ModelHttp.install({ DisabledTailnetRouting }, { null }, { proxyRouting(builtIn.port) })
                ModelHttp.client(useBuiltInProxy = true, providerId = "provider").use { client ->
                    val failure = assertThrows(IOException::class.java) {
                        runBlocking {
                            client.get("http://provider.invalid/redirect") {
                                header("x-api-key", "synthetic-provider-key")
                            }
                        }
                    }
                    assertThat(failure.message).contains("跨源重定向")
                    assertThat(redirected).isEmpty()
                }
            }
        }
    }

    @Test
    fun `同源重定向期间公网目标变为Tailnet时拒绝改走原路由`() = runBlocking {
        val nowTailnet = AtomicBoolean()
        val bypasses = CopyOnWriteArrayList<String>()
        val originalRouting = object : TailnetRouting by tailnet({ host ->
            bypasses.add(host)
            Proxy.NO_PROXY
        }) {
            override fun isTailnetHost(host: String): Boolean = nowTailnet.get()
        }
        serve { exchange ->
            nowTailnet.set(true)
            exchange.responseHeaders.set("Location", "/models")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { proxy ->
            ModelHttp.install({ originalRouting }, { null }, { proxyRouting(proxy.port) })
            ModelHttp.client(useBuiltInProxy = true, providerId = "provider").use { client ->
                assertThrows(BuiltInProxyUnavailableException::class.java) {
                    runBlocking { client.get("http://provider.invalid/redirect") }
                }
            }
        }
        assertThat(bypasses).isEmpty()
    }

    private fun tailnet(proxy: (String) -> Proxy): TailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
        override val proxySelector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(proxy(uri.host))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
    }

    private fun proxyRouting(port: Int) = object : BuiltInProxyRouting {
        override val endpoint = MutableStateFlow<BuiltInProxyEndpoint?>(BuiltInProxyEndpoint(
            port = port, proxyUser = "test-user", proxyPassword = "test-pass",
        ))
    }

    private fun serve(handler: (HttpExchange) -> Unit): TestServer =
        TestServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        })

    private fun HttpExchange.respond(text: String) {
        val bytes = text.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class TestServer(private val server: HttpServer) : AutoCloseable {
        val port: Int get() = server.address.port
        val proxy: Proxy get() = Proxy(Proxy.Type.HTTP, server.address)
        override fun close() = server.stop(0)
    }

    private companion object {
        val TEST_PROXY_AUTH = okhttp3.Credentials.basic("test-user", "test-pass", Charsets.UTF_8)
    }
}
