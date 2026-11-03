package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class TailnetManagerPingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = Files.createTempDirectory("tailnet-ping").toFile()
    private val store = TailnetSettingsStore(MemoryPreferences(), MemorySecretStore())
    private val children = CopyOnWriteArrayList<FakeTailnetChild>()

    @After
    fun tearDown() {
        scope.cancel()
        children.forEach { it.destroyForcibly() }
        work.deleteRecursively()
    }

    private fun manager(available: Boolean = true, handler: (FakeTailnetChild, JsonObject) -> Boolean = { _, _ -> false }) = TailnetManager(
        settingsStore = store,
        launcher = if (available) TailnetProcessLauncher { FakeTailnetChild(handler = handler).also(children::add) } else null,
        stateDirectory = File(work, "tailnet"),
        hostname = "infinity-test",
        scope = scope,
    )

    private suspend fun online(manager: TailnetManager): FakeTailnetChild {
        manager.start()
        withTimeout(5_000) { manager.state.first { it.phase == TailnetPhase.NEEDS_LOGIN } }
        val child = children.single()
        child.goOnline()
        withTimeout(5_000) { manager.state.first { it.phase == TailnetPhase.RUNNING && it.peers.isNotEmpty() } }
        return child
    }

    @Test
    fun `未启用不可用未登录离线和无地址时返回 null`(): Unit = runBlocking {
        val peer = TailnetPeer("nas", ips = listOf("100.64.0.5"), online = true)
        assertThat(manager().ping(peer)).isNull()
        assertThat(manager(available = false).ping(peer)).isNull()
        assertThat(children).isEmpty()
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        withTimeout(5_000) { manager.state.first { it.phase == TailnetPhase.NEEDS_LOGIN } }
        assertThat(manager.ping(peer)).isNull()
        children.single().goOnline()
        withTimeout(5_000) { manager.state.first { it.phase == TailnetPhase.RUNNING && it.peers.isNotEmpty() } }
        assertThat(manager.ping(peer.copy(online = false))).isNull()
        assertThat(manager.ping(peer.copy(ips = emptyList()))).isNull()
        assertThat(manager.ping(peer.copy(ips = listOf(" ")))).isNull()
        assertThat(children.single().received.none { "\"op\":\"ping\"" in it }).isTrue()
    }

    @Test
    fun `在线 ping 优先 IPv4 并在只有 IPv6 时使用首地址`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager(handler = { child, request ->
            if (request["op"]!!.jsonPrimitive.content != "ping") return@manager false
            child.reply(request["id"]!!.jsonPrimitive.long, """{"path":"direct","directLan":true,"latencyMs":1.25}""")
            true
        })
        val child = online(manager)
        val peer = TailnetPeer("nas", ips = listOf("fd7a:115c:a1e0::5", "100.64.0.5"), online = true)
        assertThat(manager.ping(peer)).isEqualTo(TailnetPingResult(path = TailnetPaths.DIRECT, directLan = true, latencyMs = 1.25))
        assertThat(manager.ping(peer.copy(ips = listOf("fd7a:115c:a1e0::5")))).isNotNull()
        val targets = child.received.filter { "\"op\":\"ping\"" in it }.map {
            TailnetCodec.json.parseToJsonElement(it).jsonObject["ping"]!!.jsonObject["target"]!!.jsonPrimitive.content
        }
        assertThat(targets).containsExactly("100.64.0.5", "fd7a:115c:a1e0::5").inOrder()
    }

    @Test
    fun `子进程错误码解码异常与退出均返回 null`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager(handler = { child, request ->
            if (request["op"]!!.jsonPrimitive.content != "ping") return@manager false
            val id = request["id"]!!.jsonPrimitive.long
            when (request["ping"]!!.jsonObject["target"]!!.jsonPrimitive.content) {
                "100.64.0.5" -> child.fail(id, TailnetErrors.BAD_REQUEST)
                "100.64.0.6" -> child.reply(id, """{"latencyMs":"invalid"}""")
                else -> child.crash()
            }
            true
        })
        online(manager)
        for (ip in listOf("100.64.0.5", "100.64.0.6", "100.64.0.7")) {
            assertThat(manager.ping(TailnetPeer("nas", ips = listOf(ip), online = true))).isNull()
        }
    }

    @Test
    fun `探测超时作为结果保留`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager(handler = { child, request ->
            if (request["op"]!!.jsonPrimitive.content != "ping") return@manager false
            child.reply(request["id"]!!.jsonPrimitive.long, """{"timedOut":true,"error":"Ping 超时"}""")
            true
        })
        online(manager)
        assertThat(manager.ping(TailnetPeer("nas", ips = listOf("100.64.0.5"), online = true)))
            .isEqualTo(TailnetPingResult(timedOut = true, error = "Ping 超时"))
    }
}
