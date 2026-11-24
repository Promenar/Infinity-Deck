package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class TailnetManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work: File = Files.createTempDirectory("tailnet-manager").toFile()
    private val preferences = MemoryPreferences()
    private val secrets = MemorySecretStore()
    private val store = TailnetSettingsStore(preferences, secrets)
    private val children = CopyOnWriteArrayList<FakeTailnetChild>()
    private val now = AtomicLong(1_000_000)
    private var launchFailure: IOException? = null

    private val launcher = TailnetProcessLauncher {
        launchFailure?.let { throw it }
        FakeTailnetChild().also(children::add)
    }

    private var systemRoutesTailnet = false

    private fun manager(launcher: TailnetProcessLauncher? = this.launcher) = TailnetManager(
        settingsStore = store,
        launcher = launcher,
        stateDirectory = File(work, "tailnet"),
        hostname = "infinity-test",
        scope = scope,
        clock = now::get,
        systemRoutesTailnet = { systemRoutesTailnet },
    )

    @After
    fun tearDown() {
        scope.cancel()
        children.forEach { it.destroyForcibly() }
        work.deleteRecursively()
    }

    private suspend fun TailnetManager.awaitPhase(phase: TailnetPhase): TailnetState =
        withTimeout(5_000) { state.first { it.phase == phase } }

    @Test
    fun `已缓存的上游先于端点就绪并在退出后重新应用`() = runBlocking<Unit> {
        val configuring = CompletableDeferred<Pair<FakeTailnetChild, Long>>()
        val launchCount = AtomicLong()
        val manager = manager(TailnetProcessLauncher {
            val first = launchCount.incrementAndGet() == 1L
            FakeTailnetChild(handler = { child, request ->
                if (request["op"]!!.jsonPrimitive.content != TailnetOps.SET_UPSTREAM) return@FakeTailnetChild false
                val id = request["id"]!!.jsonPrimitive.long
                if (first) configuring.complete(child to id) else child.reply(id)
                true
            }).also(children::add)
        })
        assertThat(manager.setUpstreamProxy(9801, "test-user", "test-pass")).isFalse()
        assertThat(children).isEmpty()

        store.setEnabled(true)
        manager.start()
        val (first, id) = withTimeout(5_000) { configuring.await() }
        assertThat(manager.currentEndpoint()).isNull()
        assertThat(manager.state.value.phase).isEqualTo(TailnetPhase.STARTING)
        assertThat(first.received).hasSize(1)
        first.reply(id)
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        assertThat(manager.currentEndpoint()).isNotNull()
        first.crash()
        manager.awaitPhase(TailnetPhase.FAILED)
        assertThat(manager.currentEndpoint()).isNull()

        assertThat(manager.ensureStarted(force = true, timeoutMillis = 5_000)).isTrue()
        val second = children.last()
        assertThat(second).isNotSameInstanceAs(first)
        val requests = second.received.take(2).map { TailnetCodec.json.parseToJsonElement(it).jsonObject }
        assertThat(requests.map { it["op"]!!.jsonPrimitive.content })
            .containsExactly("set_upstream", "start").inOrder()
        assertThat(requests.first()["upstream"]!!.jsonObject["address"]!!.jsonPrimitive.content)
            .isEqualTo("127.0.0.1:9801")
    }

    @Test
    fun `并发清空等待上一次更新应答且关闭重启后保持直连`() = runBlocking<Unit> {
        val updateReceived = CompletableDeferred<Pair<FakeTailnetChild, Long>>()
        val manager = manager(TailnetProcessLauncher {
            FakeTailnetChild(handler = { child, request ->
                if (request["op"]!!.jsonPrimitive.content != TailnetOps.SET_UPSTREAM) return@FakeTailnetChild false
                val id = request["id"]!!.jsonPrimitive.long
                if (request["upstream"] == JsonNull) child.reply(id) else updateReceived.complete(child to id)
                true
            }).also(children::add)
        })
        store.setEnabled(true)
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)

        val update = async { manager.setUpstreamProxy(9802, "test-user", "test-pass") }
        val (child, id) = withTimeout(5_000) { updateReceived.await() }
        val clear = async(start = CoroutineStart.UNDISPATCHED) { manager.setUpstreamProxy(null) }
        assertThat(clear.isCompleted).isFalse()
        assertThat(child.received.count { "\"op\":\"set_upstream\"" in it }).isEqualTo(1)
        child.reply(id)
        assertThat(withTimeout(5_000) { update.await() }).isTrue()
        assertThat(withTimeout(5_000) { clear.await() }).isTrue()
        val cleared = TailnetCodec.json.parseToJsonElement(child.received.last()).jsonObject
        assertThat(cleared["upstream"]).isEqualTo(JsonNull)

        manager.setEnabled(false)
        manager.awaitPhase(TailnetPhase.DISABLED)
        assertThat(child.stdinClosed).isTrue()
        manager.setEnabled(true)
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        assertThat(children.last().received).hasSize(1)
        assertThat(children.last().received.single()).contains("\"op\":\"start\"")
    }

    @Test
    fun `清空被拒绝时关闭旧子进程且重试使用直连`() = runBlocking<Unit> {
        val manager = manager(TailnetProcessLauncher {
            FakeTailnetChild(handler = { child, request ->
                if (request["op"]!!.jsonPrimitive.content != TailnetOps.SET_UPSTREAM) return@FakeTailnetChild false
                val id = request["id"]!!.jsonPrimitive.long
                if (request["upstream"] == JsonNull) child.fail(id, TailnetErrors.BAD_REQUEST) else child.reply(id)
                true
            }).also(children::add)
        })
        assertThat(manager.setUpstreamProxy(9803, "test-user", "test-pass")).isFalse()
        store.setEnabled(true)
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)

        assertThat(manager.setUpstreamProxy(null)).isFalse()
        assertThat(children.single().stdinClosed).isTrue()
        assertThat(manager.currentEndpoint()).isNull()
        assertThat(manager.state.value.phase).isEqualTo(TailnetPhase.FAILED)
        assertThat(manager.ensureStarted(force = true, timeoutMillis = 5_000)).isTrue()
        assertThat(children.last().received.single()).contains("\"op\":\"start\"")
    }

    @Test
    fun `停用设置先到达时仍清空待关闭子进程的上游`() = runBlocking<Unit> {
        val manager = manager(TailnetProcessLauncher {
            FakeTailnetChild(handler = { child, request ->
                if (request["op"]!!.jsonPrimitive.content != TailnetOps.SET_UPSTREAM) return@FakeTailnetChild false
                child.reply(request["id"]!!.jsonPrimitive.long)
                true
            }).also(children::add)
        })
        store.setEnabled(true)
        assertThat(manager.ensureStarted(force = true, timeoutMillis = 5_000)).isTrue()
        assertThat(manager.setUpstreamProxy(9804, "test-user", "test-pass")).isTrue()

        // 延迟订阅设置，以复现已停用但子进程的关闭尚未执行的窗口。
        manager.setEnabled(false)
        assertThat(manager.currentEndpoint()).isNull()
        assertThat(manager.setUpstreamProxy(null)).isTrue()
        val child = children.single()
        val request = TailnetCodec.json.parseToJsonElement(child.received.last()).jsonObject
        assertThat(request["upstream"]).isEqualTo(JsonNull)
        manager.start()
        manager.awaitPhase(TailnetPhase.DISABLED)
        assertThat(child.stdinClosed).isTrue()
    }

    @Test
    fun `单路IO首次刷新可以挂起等待子进程启动`() = runBlocking<Unit> {
        store.setEnabled(true)
        val io = Dispatchers.IO.limitedParallelism(1)
        val manager = TailnetManager(
            settingsStore = store,
            launcher = launcher,
            stateDirectory = File(work, "limited-io"),
            hostname = "infinity-test",
            scope = scope,
            ioDispatcher = io,
            clock = now::get,
        )
        val state = withTimeout(2_000) { kotlinx.coroutines.withContext(io) { manager.refresh() } }
        assertThat(state.phase).isEqualTo(TailnetPhase.NEEDS_LOGIN)
        assertThat(children).hasSize(1)
    }

    @Test
    fun `未启用时一律保持原有路径且不启动子进程`(): Unit = runBlocking {
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.DISABLED)
        assertThat(manager.agentProxy()).isNull()
        assertThat(manager.isTailnetHost("100.64.0.5")).isFalse()
        assertThat(manager.proxySelector.select(URI("https://100.64.0.5/"))).containsExactly(Proxy.NO_PROXY)
        assertThat(manager.proxySelector.select(URI("https://nas.tail1.ts.net/"))).containsExactly(Proxy.NO_PROXY)
        assertThat(manager.jschProxy("100.64.0.5")).isNull()
        assertThat(manager.subprocessEnvironment()).isEmpty()
        assertThat(children).isEmpty()
    }

    @Test
    fun `关闭时只有明确的 tailnet 目标且系统无 tailnet 路由才判为不可达`(): Unit = runBlocking {
        val manager = manager()
        assertThat(manager.isUnreachableWhileDisabled("gpu.tail1.ts.net")).isTrue()
        assertThat(manager.isUnreachableWhileDisabled("100.101.7.42")).isTrue()
        // 不含点的短名可能是局域网主机，关闭时不拦截。
        assertThat(manager.isUnreachableWhileDisabled("nas")).isFalse()
        assertThat(manager.isUnreachableWhileDisabled("api.deepseek.com")).isFalse()

        systemRoutesTailnet = true
        assertThat(manager.isUnreachableWhileDisabled("gpu.tail1.ts.net")).isFalse()

        systemRoutesTailnet = false
        store.setEnabled(true)
        assertThat(manager.isUnreachableWhileDisabled("gpu.tail1.ts.net")).isFalse()
    }

    @Test
    fun `未打包可执行文件时报告不可用`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager(launcher = null)
        manager.start()
        manager.awaitPhase(TailnetPhase.UNAVAILABLE)
        assertThat(manager.available).isFalse()
        assertThat(manager.subprocessEnvironment()).isEmpty()
        assertThat(manager.login()).isNull()
    }

    @Test
    fun `启用后启动子进程，代理凭据与 auth key 只经 stdin 传入`(): Unit = runBlocking {
        store.setAuthKey("tskey-auth-SECRETVALUE".toCharArray())
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        val state = manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        assertThat(state.summary?.authUrl).isEqualTo("https://login.example.test/a/first")
        val child = children.single()
        val start = child.received.single { "\"op\":\"start\"" in it }
        assertThat(start).contains("tskey-auth-SECRETVALUE")
        assertThat(start).contains("\"hostname\":\"infinity-test\"")
        assertThat(start).contains(File(work, "tailnet").absolutePath)

        val env = manager.subprocessEnvironment()
        assertThat(env["NO_PROXY"]).isEqualTo("localhost,127.0.0.1,::1")
        val proxyUrl = env.getValue("https_proxy")
        val credentials = proxyUrl.removePrefix("http://").substringBefore("@")
        assertThat(credentials.substringBefore(":")).hasLength(TailnetManager.PROXY_SECRET_LENGTH)
        assertThat(credentials.substringAfter(":")).hasLength(TailnetManager.PROXY_SECRET_LENGTH)
        assertThat(start).contains(credentials.substringAfter(":"))
        assertThat(manager.state.value.toString()).doesNotContain(credentials.substringAfter(":"))
        assertThat(manager.state.value.toString()).doesNotContain("SECRETVALUE")
        assertThat(proxyUrl).endsWith("@127.0.0.1:${proxyUrl.substringAfterLast(":")}")

        val proxy = manager.proxySelector.select(URI("https://100.64.0.5/")).single()
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP)
        assertThat(manager.proxySelector.select(URI("https://api.openai.com/"))).containsExactly(Proxy.NO_PROXY)
        assertThat(manager.jschProxy("nas.tail1.ts.net")).isNotNull()
        assertThat(manager.jschProxy("example.org")).isNull()
    }

    @Test
    fun `在线后读取节点列表，短名按已知节点判定`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        // 未登录：节点列表为空，局域网短名不进代理。
        assertThat(manager.isTailnetHost("printer")).isFalse()
        children.single().goOnline()
        val running = withTimeout(5_000) { manager.state.first { it.phase == TailnetPhase.RUNNING && it.peers.isNotEmpty() } }
        assertThat(manager.agentProxy()?.type()).isEqualTo(Proxy.Type.HTTP)
        assertThat(running.summary?.selfName).isEqualTo("phone")
        assertThat(running.peers.map { it.name }).containsExactly("nas", "laptop")
        assertThat(manager.isTailnetHost("nas")).isTrue()
        assertThat(manager.isTailnetHost("printer")).isFalse()
        assertThat(manager.isTailnetHost("nas.tail1.ts.net")).isTrue()
        val refreshed = manager.refresh()
        assertThat(refreshed.phase).isEqualTo(TailnetPhase.RUNNING)
    }

    @Test
    fun `控制服务器变化重启并清空状态目录`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)

        val marker = File(File(work, "tailnet").apply { mkdirs() }, "tailscaled.state").apply { writeText("old") }
        assertThat(manager.setControlUrl("ftp://hs.example.org")).isFalse()
        assertThat(manager.setControlUrl("https://hs.example.org/")).isTrue()
        withTimeout(5_000) { while (children.size < 2 || children.last().received.isEmpty()) kotlinx.coroutines.delay(20) }
        assertThat(children.first().stdinClosed).isTrue()
        assertThat(marker.exists()).isFalse()
        assertThat(children.last().received.first()).contains("\"controlUrl\":\"https://hs.example.org\"")
    }

    @Test
    fun `停用时关闭 stdin 并回到原有路径`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        manager.setEnabled(false)
        manager.awaitPhase(TailnetPhase.DISABLED)
        assertThat(manager.agentProxy()).isNull()
        assertThat(children.single().stdinClosed).isTrue()
        assertThat(manager.subprocessEnvironment()).isEmpty()
        assertThat(manager.proxySelector.select(URI("https://100.64.0.5/"))).containsExactly(Proxy.NO_PROXY)
    }

    @Test
    fun `进程退出后按需重启，失败退避期内不重试`(): Unit = runBlocking {
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        children.single().crash()
        val failed = manager.awaitPhase(TailnetPhase.FAILED)
        assertThat(failed.failure).isEqualTo(TailnetFailure.EXITED)
        assertThat(manager.subprocessEnvironment()).isEmpty()
        assertThat(children).hasSize(1)

        now.addAndGet(TailnetManager.RETRY_BACKOFF_MS + 1)
        assertThat(manager.subprocessEnvironment()["NO_PROXY"]).isEqualTo("localhost,127.0.0.1,::1")
        assertThat(children).hasSize(2)
    }

    @Test
    fun `启动失败报告失败原因，重试后恢复`(): Unit = runBlocking {
        launchFailure = IOException("无法执行")
        store.setEnabled(true)
        val manager = manager()
        manager.start()
        val failed = manager.awaitPhase(TailnetPhase.FAILED)
        assertThat(failed.failure).isEqualTo(TailnetFailure.LAUNCH_FAILED)
        assertThat(manager.awaitEndpoint(100)).isNull()
        launchFailure = null
        manager.retry()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        assertThat(manager.awaitEndpoint(5_000)).isNotNull()
    }

    @Test
    fun `浏览器登录返回登录链接，退出登录删除 auth key`(): Unit = runBlocking {
        store.setEnabled(true)
        store.setAuthKey("tskey-auth-SECRETVALUE".toCharArray())
        val manager = manager()
        manager.start()
        manager.awaitPhase(TailnetPhase.NEEDS_LOGIN)
        assertThat(manager.login()).isEqualTo("https://login.example.test/a/first")
        children.single().goOnline()
        manager.awaitPhase(TailnetPhase.RUNNING)
        assertThat(manager.login()).isNull()
        assertThat(manager.logout()).isTrue()
        assertThat(store.current.hasAuthKey).isFalse()
        assertThat(secrets.values).isEmpty()
    }

    @Test
    fun `发送关口按需拉起子进程：未登录立即提示登录，上线后放行`(): Unit = runBlocking {
        val disabled = manager()
        assertThat(disabled.ensureStarted(force = true, timeoutMillis = 1_000)).isFalse()
        assertThat(TailnetConnectGate(disabled).await("nas.tail1.ts.net"))
            .isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.DISABLED))
        systemRoutesTailnet = true
        assertThat(TailnetConnectGate(disabled).await("nas.tail1.ts.net")).isEqualTo(TailnetConnectResult.NotRequired)
        systemRoutesTailnet = false

        store.setEnabled(true)
        val manager = manager()
        val gate = TailnetConnectGate(manager, timeoutMillis = 5_000)
        assertThat(gate.await("nas.tail1.ts.net", force = true))
            .isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.NEEDS_LOGIN))
        assertThat(children).hasSize(1)
        children.single().goOnline()
        manager.awaitPhase(TailnetPhase.RUNNING)
        assertThat(gate.await("nas.tail1.ts.net")).isEqualTo(TailnetConnectResult.Ready(waited = false))
        assertThat(children).hasSize(1)
    }
}
