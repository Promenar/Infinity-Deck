package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.proxy.BuiltInProxyController
import com.promenar.infinity.data.proxy.BuiltInProxyPhase
import com.promenar.infinity.data.proxy.BuiltInProxySettings
import com.promenar.infinity.data.proxy.BuiltInProxyState
import com.promenar.infinity.data.proxy.ProxyLatencyResult
import com.promenar.infinity.data.proxy.ProxyMode
import com.promenar.infinity.data.proxy.ProxyNodeItem
import com.promenar.infinity.data.proxy.ProxyNodeSummary
import com.promenar.infinity.data.proxy.ProxySubscription
import com.promenar.infinity.data.proxy.ProxyYamlValidation
import com.promenar.infinity.data.proxy.formatReadableProxyYaml
import com.promenar.infinity.ui.common.PromptSaveConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `新增订阅首次拉取失败时自动回滚空订阅并暴露错误`() = runTest {
        val controller = FakeBuiltInProxyController(failRefresh = true)
        val viewModel = ProxyViewModel(controller)
        var succeeded = false

        viewModel.addAndRefreshSubscription("主订阅", "https://sub.example.com/feed") {
            succeeded = true
        }
        advanceUntilIdle()

        assertThat(succeeded).isFalse()
        assertThat(controller.removedSubscriptionIds).containsExactly("sub-1")
        assertThat(viewModel.uiState.value.importing).isFalse()
        assertThat(viewModel.uiState.value.importError).contains("拉取失败")
    }

    @Test
    fun `一键测速超过 32 个启用节点时自动分批执行并合并结果`() = runTest {
        val nodes = (1..35).map { idx ->
            ProxyNodeItem(
                id = "node-$idx",
                name = "Node-$idx",
                type = "vless",
                server = "s$idx.example.com",
                port = 443,
                enabled = true,
            )
        }
        val controller = FakeBuiltInProxyController(initialNodes = nodes)
        val viewModel = ProxyViewModel(controller)
        advanceUntilIdle()

        viewModel.testAllLatency()
        advanceUntilIdle()

        assertThat(controller.latencyBatches.map { it.size }).containsExactly(32, 3).inOrder()
        assertThat(viewModel.uiState.value.latencies).hasSize(35)
        assertThat(viewModel.uiState.value.testingLatency).isFalse()
        assertThat(viewModel.uiState.value.notice).isEqualTo(ProxyNotice.LatencyDone)
    }

    @Test
    fun `保存无效高级 YAML 时以冲突异常阻断关闭并在横幅保留内核报错`() = runTest {
        val controller = FakeBuiltInProxyController(
            yamlValidationResult = ProxyYamlValidation(valid = false, error = "line 2: missing server"),
        )
        val viewModel = ProxyViewModel(controller)

        val result = viewModel.saveAdvancedYaml("proxies:\n  - name: bad")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(PromptSaveConflictException::class.java)
        assertThat(viewModel.uiState.value.yamlValidation?.valid).isFalse()
        assertThat(viewModel.uiState.value.yamlValidation?.error).isEqualTo("line 2: missing server")
    }

    @Test
    fun `可读 YAML 导出保留关键字段顺序、嵌套结构与特殊值引号`() {
        val node = ProxyNodeItem(
            id = "n1",
            name = "HK-01: Main",
            type = "vless",
            server = "hk.example.com",
            port = 443,
        )
        val config = JsonObject(
            mapOf(
                "type" to JsonPrimitive("vless"),
                "server" to JsonPrimitive("hk.example.com"),
                "port" to JsonPrimitive(443),
                "tls" to JsonPrimitive(true),
                "uuid" to JsonPrimitive("12345"),
                "alpn" to JsonArray(listOf(JsonPrimitive("h2"), JsonPrimitive("http/1.1"))),
                "reality-opts" to JsonObject(mapOf("public-key" to JsonPrimitive("abc+def"))),
            ),
        )

        val yaml = formatReadableProxyYaml(listOf(node), mapOf("n1" to config))

        assertThat(yaml).startsWith(
            """
            proxies:
              - name: "HK-01: Main"
                type: vless
                server: hk.example.com
                port: 443
            """.trimIndent(),
        )
        assertThat(yaml).contains("uuid: \"12345\"")
        assertThat(yaml).contains("tls: true")
        assertThat(yaml).contains("reality-opts:\n      public-key: abc+def")
    }

    @Test
    fun `协议展示名规范化常见简称`() {
        assertThat(formatProtocolLabel("ss")).isEqualTo("Shadowsocks")
        assertThat(formatProtocolLabel("vless")).isEqualTo("VLESS")
        assertThat(formatProtocolLabel("hysteria2")).isEqualTo("Hysteria2")
        assertThat(formatProtocolLabel("trojan")).isEqualTo("Trojan")
    }
}

private class FakeBuiltInProxyController(
    initialNodes: List<ProxyNodeItem> = emptyList(),
    private val failRefresh: Boolean = false,
    private val yamlValidationResult: ProxyYamlValidation = ProxyYamlValidation(
        valid = true,
        nodes = listOf(ProxyNodeSummary("Node-1", "socks5", "127.0.0.1", 1080)),
    ),
) : BuiltInProxyController {
    override val available: Boolean = true
    override val settings = MutableStateFlow(BuiltInProxySettings(nodes = initialNodes))
    override val state = MutableStateFlow(BuiltInProxyState(phase = BuiltInProxyPhase.STOPPED, nodes = initialNodes))

    val removedSubscriptionIds = mutableListOf<String>()
    val latencyBatches = mutableListOf<List<String>>()
    private var subCounter = 0

    override suspend fun start() = Unit
    override suspend fun setEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(enabled = enabled)
    }
    override suspend fun setMode(mode: ProxyMode) {
        settings.value = settings.value.copy(mode = mode)
    }
    override suspend fun selectNode(id: String) {
        settings.value = settings.value.copy(activeNode = id)
    }
    override suspend fun setNodeEnabled(id: String, enabled: Boolean) {
        settings.value = settings.value.copy(
            nodes = settings.value.nodes.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }
    override suspend fun moveNode(id: String, offset: Int) = Unit
    override suspend fun removeNode(id: String) {
        settings.value = settings.value.copy(nodes = settings.value.nodes.filterNot { it.id == id })
    }
    override suspend fun importNodes(content: String): List<ProxyNodeItem> = emptyList()
    override suspend fun addSubscription(name: String, url: String): ProxySubscription {
        val sub = ProxySubscription(id = "sub-${++subCounter}", name = name)
        settings.value = settings.value.copy(subscriptions = settings.value.subscriptions + sub)
        return sub
    }
    override suspend fun readSubscriptionUrl(id: String): String? = null
    override suspend fun refreshSubscription(id: String): ProxySubscription {
        if (failRefresh) throw IllegalStateException("订阅拉取失败")
        return settings.value.subscriptions.first { it.id == id }.copy(nodeCount = 2)
    }
    override suspend fun removeSubscription(id: String) {
        removedSubscriptionIds += id
        settings.value = settings.value.copy(subscriptions = settings.value.subscriptions.filterNot { it.id == id })
    }
    override suspend fun validateYaml(yaml: String): ProxyYamlValidation = yamlValidationResult
    override suspend fun readAdvancedYaml(): String? = null
    override suspend fun readEditableYaml(): String = "proxies: []\n"
    override suspend fun setAdvancedYaml(yaml: String?): ProxyYamlValidation = yamlValidationResult
    override suspend fun syncAdvancedYaml() {
        settings.value = settings.value.copy(hasAdvancedYaml = false)
    }
    override suspend fun testLatency(nodeIds: List<String>, url: String?, timeoutMillis: Long): List<ProxyLatencyResult> {
        latencyBatches += nodeIds
        return nodeIds.map { ProxyLatencyResult(name = it, ok = true, delayMs = 42L) }
    }
}
