package com.promenar.infinity.data.network

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import kotlin.concurrent.thread
import javax.net.ssl.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentHttpRoutingTest {
    @TempDir lateinit var directory: File

    @Test fun `tailnetHTTP经内置代理且DNS派生目标保留原始Host`(): Unit = runBlocking {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 34567))
        val routing = object : TailnetRouting by DisabledTailnetRouting {
            override fun agentProxy() = proxy
        }
        for (port in listOf("", ":8080")) {
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("http://origin.test$port/file")
            val route = AgentHttpRouting.route(OkHttpClient(), endpoint, routing)
            val request = route.request(Request.Builder().url(endpoint.uri.toString()).build())
            assertThat(route.client.proxy).isEqualTo(proxy)
            assertThat(request.url.host).isEqualTo("100.64.0.9")
            assertThat(request.header("Host")).isEqualTo("origin.test$port")
            assertThat(request.header("X-Infinity-Origin-Host")).isEqualTo("origin.test$port")
        }
    }

    @Test fun `DNS派生tailnet固定CONNECT的IP并保留原始SNI证书与Host身份`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.serverContext).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://origin.test/file")
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust)
                .certificatePinner(CertificatePinner.Builder().add("origin.test", CertificatePinner.pin(identity.certificate)).build())
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = error("不应二次解析目标") }).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            try {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().use {
                    assertThat(it.code).isEqualTo(200)
                    assertThat(it.body!!.string()).isEqualTo("ok")
                }
            } catch (failure: java.io.IOException) {
                withTimeoutOrNull(2000) { fixture.workerFailure.await() }?.let(failure::addSuppressed)
                throw failure
            }
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT 100.64.0.9:443 HTTP/1.1")
            assertThat(withTimeout(2000) { fixture.host.await() }).isEqualTo("origin.test")
            val actualSni = withTimeoutOrNull(2000) { fixture.sni.await() }
            checkNotNull(actualSni) { "TLS 握手未记录 SNI；${fixture.serverInfo.await()}" }
            assertThat(actualSni).isEqualTo("origin.test")
        }
    }

    @Test fun `固定隧道IP不能绕过原始主机证书校验`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.serverContext).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://wrong.test/file")
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            assertThrows(SSLPeerUnverifiedException::class.java) {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().close()
            }
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT 100.64.0.9:443 HTTP/1.1")
            assertThat(fixture.host.isCompleted).isFalse()
        }
    }

    @Test fun `tailnet自签名证书归为证书错误而不是代理不可用`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.serverContext).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://origin.test/file")
            // 默认信任库不认识临时自签名证书，与真机访问 fnOS 自签名服务的情形一致。
            val defaultTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val defaultContext = SSLContext.getInstance("TLS", "SunJSSE").apply { init(null, arrayOf(defaultTrust), null) }
            val base = OkHttpClient.Builder().sslSocketFactory(defaultContext.socketFactory, defaultTrust).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            val error = assertThrows(java.io.IOException::class.java) {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().close()
            }
            val denied = AgentTailnetProxy.failure(error)
            assertThat(denied.code).isEqualTo("TAILNET_TLS_ERROR")
            assertThat(denied.message).doesNotContain("未运行")
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT 100.64.0.9:443 HTTP/1.1")
        }
    }

    @Test fun `tailnet连接失败保留真实原因`() {
        val denied = AgentTailnetProxy.failure(java.io.IOException("Unexpected response code for CONNECT: 502"))
        assertThat(denied.code).isEqualTo("TAILNET_PROXY_ERROR")
        assertThat(denied.message).contains("CONNECT: 502")
        assertThat(denied.message).doesNotContain("未运行")
    }

    @Test fun `回环优先的混合地址不能在失败重试时直连tailnet`(): Unit = runBlocking {
        val endpoint = AgentNetworkPolicy(resolver = {
            arrayOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("100.64.0.9"))
        }).validate("https://mixed.test/a")
        val route = AgentHttpRouting.route(OkHttpClient(), endpoint, DisabledTailnetRouting)
        assertThat(route.client.proxy).isEqualTo(Proxy.NO_PROXY)
        assertThat(route.client.dns.lookup("mixed.test")).containsExactly(InetAddress.getByName("127.0.0.1"))
    }

    @Test fun `混合公网与局域网保留所有已解析的直连地址`(): Unit = runBlocking {
        for (local in listOf("127.0.0.1", "192.168.1.2")) {
            val endpoint = AgentNetworkPolicy(resolver = {
                arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName(local))
            }).validate("https://mixed.test/a")
            val route = AgentHttpRouting.route(OkHttpClient(), endpoint, DisabledTailnetRouting)
            assertThat(route.client.dns.lookup("mixed.test")).containsExactly(InetAddress.getByName("93.184.216.34"), InetAddress.getByName(local))
        }
    }

    @Test fun `固定隧道IP仍执行原始主机的证书pin校验`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.serverContext).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://origin.test/file")
            val differentPin = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32))
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust)
                .certificatePinner(CertificatePinner.Builder().add("origin.test", differentPin).build()).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            assertThrows(SSLPeerUnverifiedException::class.java) {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().close()
            }
            assertThat(fixture.host.isCompleted).isFalse()
        }
    }

    private data class Identity(
        val context: SSLContext,
        val serverContext: SSLContext,
        val trust: X509TrustManager,
        val certificate: X509Certificate,
    )

    private fun identity(): Identity {
        // 临时生成测试证书；私钥与随机口令不进入源码、输出或报告。
        val storeFile = File(directory, "temporary.p12")
        val password = UUID.randomUUID().toString().toCharArray()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val command = ProcessBuilder(keytool, "-genkeypair", "-alias", "origin", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-dname", "CN=origin.test", "-ext", "SAN=DNS:origin.test", "-storetype", "PKCS12",
            "-keystore", storeFile.absolutePath, "-storepass:env", "INFINITY_TLS_TEST_PASSWORD")
        command.environment()["INFINITY_TLS_TEST_PASSWORD"] = String(password)
        val process = command.redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor() == 0) { "测试证书生成失败" }
        val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        password.fill('\u0000')
        // 两端都固定 SunJSSE：全局提供者顺序会被同进程其它测试改写，不能决定本测试的握手实现。
        val clientContext = SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, arrayOf(trust), null) }
        val serverContext = SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, arrayOf(trust), null) }
        return Identity(clientContext, serverContext, trust, store.getCertificate("origin") as X509Certificate)
    }

    private class TlsProxy(context: SSLContext) : AutoCloseable {
        // 不设 accept 超时：负载高时测试可能晚于 5 秒才连接；监听由 close() 关闭以结束 worker。
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val connect = CompletableDeferred<String>()
        val host = CompletableDeferred<String>()
        val sni = CompletableDeferred<String>()
        val serverInfo = CompletableDeferred<String>()
        val workerFailure = CompletableDeferred<Throwable>()
        val routing: TailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
            override fun agentProxy() = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort))
        }
        private val worker = thread(isDaemon = true, name = "测试TLS代理") {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    connect.complete(input.readLine())
                    while (input.readLine()?.takeIf(String::isNotEmpty) != null) { }
                    socket.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                    (context.socketFactory.createSocket(socket, "localhost", socket.port, true) as SSLSocket).use { tls ->
                        tls.useClientMode = false
                        tls.sslParameters = tls.sslParameters.apply {
                            sniMatchers = listOf(object : SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                                override fun matches(serverName: SNIServerName): Boolean {
                                    sni.complete((serverName as SNIHostName).asciiName)
                                    return true
                                }
                            })
                        }
                        tls.startHandshake()
                        val platform = runCatching {
                            Class.forName("okhttp3.internal.platform.Platform").getMethod("get").invoke(null).javaClass.name
                        }.getOrElse { it.javaClass.name }
                        serverInfo.complete("provider=${context.provider.name}; socket=${tls.javaClass.name}; " +
                            "session=${tls.session.javaClass.name}; okhttp=$platform")
                        val request = tls.inputStream.bufferedReader()
                        request.readLine()
                        while (true) {
                            val line = request.readLine()?.takeIf(String::isNotEmpty) ?: break
                            if (line.startsWith("Host:", true)) host.complete(line.substringAfter(':').trim())
                        }
                        tls.outputStream.apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); flush() }
                    }
                }
            }.onFailure { workerFailure.complete(it) }
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}
