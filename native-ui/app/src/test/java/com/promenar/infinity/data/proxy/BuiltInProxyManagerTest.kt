package com.promenar.infinity.data.proxy

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.MemoryPreferences
import com.promenar.infinity.data.tailnet.MemorySecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BuiltInProxyManagerTest {
    @Test
    fun `验证和导入只短时启动子进程，启用后支持热重载和清空上游`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val children = CopyOnWriteArrayList<Child>()
        val upstream = CopyOnWriteArrayList<BuiltInProxyEndpoint?>()
        val dns = MutableStateFlow(listOf("192.0.2.53"))
        val manager = BuiltInProxyManager(
            BuiltInProxyStore(MemoryPreferences(), MemorySecretStore()),
            BuiltInProxyProcessLauncher { Child().also(children::add) },
            scope,
            readDns = { dns.value },
            dnsChanges = dns,
            setUpstream = { upstream += it },
        )
        try {
            manager.start()
            manager.setEnabled(true)
            assertThat(children).isEmpty()
            assertThat(manager.validateYaml("proxies: []").valid).isTrue()
            assertThat(children.single().alive).isFalse()
            val item = manager.importNodes("测试节点").single()
            val endpoint = checkNotNull(manager.endpoint.value)
            assertThat(endpoint.proxyPassword.toByteArray().size).isAtLeast(16)
            assertThat(manager.state.value.activeNode).isEqualTo(item.id)
            val running = children.last()
            assertThat(running.methods).containsExactly("start", "status").inOrder()

            manager.setMode(ProxyMode.FALLBACK)
            assertThat(running.methods).contains("reload")
            assertThat(manager.endpoint.value).isSameInstanceAs(endpoint)
            assertThat(manager.state.value.mode).isEqualTo(ProxyMode.FALLBACK)
            dns.value = listOf("192.0.2.54")
            withTimeout(5_000) { running.dns.first { it == dns.value } }

            manager.setNodeEnabled(item.id, false)
            assertThat(manager.endpoint.value).isNull()
            assertThat(manager.state.value.phase).isEqualTo(BuiltInProxyPhase.STOPPED)
            assertThat(running.alive).isFalse()
            assertThat(upstream.toList()).containsExactly(endpoint, null).inOrder()
        } finally {
            manager.setEnabled(false)
            scope.cancel()
        }
    }

    @Test
    fun `运行子进程意外退出时撤销端点与链式上游`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val children = CopyOnWriteArrayList<Child>()
        val upstream = CopyOnWriteArrayList<BuiltInProxyEndpoint?>()
        val manager = BuiltInProxyManager(BuiltInProxyStore(MemoryPreferences(), MemorySecretStore()), {
            Child().also(children::add)
        }, scope, setUpstream = { upstream += it })
        try {
            manager.importNodes("节点")
            manager.setEnabled(true)
            val endpoint = checkNotNull(manager.endpoint.value)
            children.last().destroyForcibly()
            withTimeout(5_000) { manager.state.first { it.phase == BuiltInProxyPhase.ERROR } }
            assertThat(manager.endpoint.value).isNull()
            assertThat(upstream.toList()).containsExactly(endpoint, null).inOrder()
        } finally {
            manager.setEnabled(false)
            scope.cancel()
        }
    }

    @Test
    fun `关闭时控制写入阻塞仍能强制结束进程`() = runBlocking {
        val output = Pipe.open()
        val stopped = CountDownLatch(1)
        val child = object : BuiltInProxyProcess {
            override val stdout = Channels.newInputStream(output.source())
            override val alive: Boolean get() = stopped.count != 0L
            override val stdin = object : OutputStream() {
                override fun write(value: Int) {
                    while (alive) {
                        try {
                            stopped.await()
                        } catch (_: InterruptedException) {
                            // 模拟系统管道没有因线程中断解除阻塞。
                        }
                    }
                    throw IOException("子进程已关闭")
                }
            }

            override fun waitFor(timeoutMillis: Long) = stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun destroyForcibly() {
                stopped.countDown()
                output.sink().close()
            }
        }
        val client = BuiltInProxyClient(child)
        withTimeout(4_000) { client.close() }
        assertThat(child.alive).isFalse()
    }

    private class Child : BuiltInProxyProcess {
        private val inputPipe = Pipe.open()
        private val outputPipe = Pipe.open()
        override val stdin = Channels.newOutputStream(inputPipe.sink())
        override val stdout = Channels.newInputStream(outputPipe.source())
        val methods = CopyOnWriteArrayList<String>()
        val dns = MutableStateFlow(emptyList<String>())
        private var config = JsonObject(emptyMap())
        private val worker = thread(isDaemon = true) {
            try {
                Channels.newInputStream(inputPipe.source()).bufferedReader().use { reader ->
                    Channels.newOutputStream(outputPipe.sink()).bufferedWriter().use { writer ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val request = proxyJson.parseToJsonElement(line).jsonObject
                            val method = request.getValue("method").jsonPrimitive.content
                            val params = request.getValue("params").jsonObject
                            methods.add(method)
                            val result = when (method) {
                                "start", "reload" -> {
                                    config = params
                                    buildJsonObject { put("proxyPort", 42309); put("nodeCount", 1) }
                                }
                                "status" -> buildJsonObject {
                                    put("running", true)
                                    put("mode", config.getValue("mode"))
                                    put("activeNode", config.getValue("activeNode"))
                                    put("healthyNodeCount", 1)
                                }
                                "set_dns" -> {
                                    dns.value = (params.getValue("dnsServers") as JsonArray).map { it.jsonPrimitive.content }
                                    JsonObject(emptyMap())
                                }
                                "validate_yaml" -> buildJsonObject { put("valid", true) }
                                "parse_import" -> proxyJson.parseToJsonElement(
                                    """{"nodes":[{"name":"测试","type":"http","server":"example.org","port":80}],"nodeConfigs":[{"name":"测试","type":"http","server":"example.org","port":80}]}""",
                                )
                                else -> JsonObject(emptyMap())
                            }
                            writer.write(buildJsonObject {
                                put("id", request.getValue("id"))
                                put("result", result)
                            }.toString())
                            writer.newLine()
                            writer.flush()
                            if (method == "shutdown") break
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // 测试模拟子进程退出时关闭管道。
            }
        }

        override val alive: Boolean get() = worker.isAlive
        override fun waitFor(timeoutMillis: Long): Boolean {
            worker.join(timeoutMillis)
            return !worker.isAlive
        }

        override fun destroyForcibly() {
            inputPipe.sink().close()
            outputPipe.sink().close()
        }
    }
}
