package com.promenar.infinity.domain.script

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.script.ScriptHttpClient
import com.promenar.infinity.data.network.ConnectProxyFixture
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import java.net.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ScriptHttpClientTest {
    private fun args(url: String) = buildJsonObject { put("url", url) }

    @Test fun `重复响应头保留全部值并以逗号合并且不暴露Cookie`() = runBlocking {
        Fixture { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nX-Trace: first\r\nx-trace: second\r\nSet-Cookie: private=value\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        }.use { fixture ->
            val client = ScriptHttpClient() { arrayOf(InetAddress.getByName(it)) }
            val headers = client.fetch(args("http://127.0.0.1:${fixture.port}"), ScriptNetworkBudget())["headers"]!!.jsonObject
            assertThat(headers.entries.single { it.key.equals("x-trace", true) }.value).isEqualTo(JsonPrimitive("first, second"))
            assertThat(headers.keys.none { it.equals("set-cookie", true) }).isTrue()
        }
    }

    @Test fun `真实HTTP读取使用固定DNS且返回状态正文与请求头`() = runBlocking {
        Fixture { socket -> respond(socket, "hello") }.use { fixture ->
            var resolutions = 0
            val client = ScriptHttpClient() {
                assertThat(it).isEqualTo("lan.test")
                resolutions++
                arrayOf(InetAddress.getByName("127.0.0.1"))
            }
            val response = client.fetch(args("http://lan.test:${fixture.port}/test"), ScriptNetworkBudget())
            assertThat(response["status"]).isEqualTo(JsonPrimitive(200))
            assertThat(response["body"]).isEqualTo(JsonPrimitive("hello"))
            assertThat(resolutions).isEqualTo(1)
        }
    }

    @Test fun `重定向重新验证并拒绝组播地址`() = runBlocking {
        Fixture { socket -> socket.getOutputStream().apply { write("HTTP/1.1 302 Found\r\nLocation: http://224.0.0.1/secret\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() } }.use { fixture ->
            val client = ScriptHttpClient { arrayOf(InetAddress.getByName(it)) }
            try { client.fetch(args("http://127.0.0.1:${fixture.port}"), ScriptNetworkBudget()); error("应拒绝重定向") }
            catch (failure: ScriptFailure) { assertThat(failure.message).contains("ADDRESS_DENIED") }
        }
    }

    @Test fun `超大响应流在有界读取后拒绝`() = runBlocking {
        Fixture { socket -> respond(socket, "x".repeat(65537)) }.use { fixture ->
            val client = ScriptHttpClient() { arrayOf(InetAddress.getByName(it)) }
            try { client.fetch(args("http://127.0.0.1:${fixture.port}"), ScriptNetworkBudget()); error("应超限") }
            catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("NETWORK_LIMIT") }
        }
    }

    @Test fun `取消关闭阻塞响应体不等待网络超时`() = runBlocking {
        val headersSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture { socket ->
            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n".toByteArray()); flush() }
            headersSent.countDown()
            release.await(5, TimeUnit.SECONDS)
        }.use { fixture ->
            val client = ScriptHttpClient() { arrayOf(InetAddress.getByName(it)) }
            val pending = launch { client.fetch(args("http://127.0.0.1:${fixture.port}"), ScriptNetworkBudget()) }
            try {
                withContext(Dispatchers.IO) { check(headersSent.await(5, TimeUnit.SECONDS)) }
                withTimeout(2000) { pending.cancelAndJoin() }
                assertThat(pending.isCancelled).isTrue()
            } finally { release.countDown() }
        }
    }

    @Test fun `tailnet经真实本机CONNECT并调用代理认证且不解析名称`() = runBlocking {
        ConnectProxyFixture().use { fixture ->
            val client = ScriptHttpClient(tailnetRouting = fixture.routing, tailnetRunning = { true },
                resolver = { error("tailnet 不应调用系统 DNS") })
            try { client.fetch(args("https://main.tail.ts.net/test"), ScriptNetworkBudget()); error("代理应返回失败") }
            catch (failure: ScriptFailure) {
                assertThat(failure.code).isEqualTo("NETWORK_DENIED")
                assertThat(failure.message).contains("TAILNET_PROXY_ERROR")
            }
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT main.tail.ts.net:443 HTTP/1.1")
            assertThat(fixture.authentications.get()).isGreaterThan(0)
        }
    }

    @Test fun `tailnet代理回退直连或未运行均结构化拒绝`() = runBlocking {
        val routing = object : TailnetRouting by DisabledTailnetRouting {
            override fun isTailnetHost(host: String) = host == "main"
            override val proxySelector = ConnectProxyFixture.selector(listOf(Proxy.NO_PROXY))
        }
        for (running in listOf(false, true)) {
            val client = ScriptHttpClient(tailnetRouting = routing, tailnetRunning = { running },
                resolver = { error("tailnet 不应调用系统 DNS") })
            try { client.fetch(args("https://main/test"), ScriptNetworkBudget()); error("应拒绝直连") }
            catch (failure: ScriptFailure) {
                assertThat(failure.code).isEqualTo("NETWORK_DENIED")
                assertThat(failure.message).contains("TAILNET_OFFLINE")
                assertThat(failure.message).contains("Tailscale 设置中启用并连接")
            }
        }
    }

    private fun respond(socket: Socket, text: String) {
        val bytes = text.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private class Fixture(respond: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val port get() = server.localPort
        private val worker = thread(isDaemon = true, name = "script-http-test") {
            runCatching { server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (input.readLine()?.takeIf(String::isNotEmpty) != null) { }
                respond(socket)
            } }
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}
