package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
