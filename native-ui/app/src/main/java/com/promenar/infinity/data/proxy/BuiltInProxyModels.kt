package com.promenar.infinity.data.proxy

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
enum class ProxyMode {
    @SerialName("single") SINGLE,
    @SerialName("fallback") FALLBACK,
}

enum class BuiltInProxyPhase { STOPPED, STARTING, RUNNING, ERROR, UNAVAILABLE }

/** [id] 是稳定的节点标识，界面排序、选中和测速使用此值。 */
@Serializable
data class ProxyNodeItem(
    val id: String,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val enabled: Boolean = true,
    val subscriptionIds: Set<String> = emptySet(),
    val manuallyImported: Boolean = false,
)

/** 订阅地址可携带访问令牌，仅从控制器显式读取。 */
@Serializable
data class ProxySubscription(
    val id: String,
    val name: String,
    val nodeCount: Int = 0,
    val lastRefreshedAtMillis: Long? = null,
)

data class BuiltInProxySettings(
    val enabled: Boolean = false,
    val mode: ProxyMode = ProxyMode.SINGLE,
    val activeNode: String? = null,
    val nodes: List<ProxyNodeItem> = emptyList(),
    val subscriptions: List<ProxySubscription> = emptyList(),
    val hasAdvancedYaml: Boolean = false,
) {
    val fallbackOrder: List<String> get() = nodes.map { it.id }
}

data class BuiltInProxyState(
    val phase: BuiltInProxyPhase = BuiltInProxyPhase.STOPPED,
    val enabled: Boolean = false,
    val mode: ProxyMode = ProxyMode.SINGLE,
    val activeNode: String? = null,
    val healthyNodeCount: Int = 0,
    val fallbackReason: String? = null,
    val nodes: List<ProxyNodeItem> = emptyList(),
    val error: String? = null,
) {
    val running: Boolean get() = phase == BuiltInProxyPhase.RUNNING
}

@Serializable
data class ProxyNodeSummary(val name: String, val type: String, val server: String, val port: Int)

@Serializable
data class ProxyLatencyResult(
    val name: String,
    val ok: Boolean,
    val delayMs: Long? = null,
    val error: String? = null,
)

data class ProxyYamlValidation(
    val valid: Boolean,
    val error: String? = null,
    val nodes: List<ProxyNodeSummary> = emptyList(),
)

class BuiltInProxyUnavailableException(message: String = "内置代理不可用") : IOException(message)

/** 设置界面所需的后端入口。配置错误与运行状态分开返回。 */
interface BuiltInProxyController {
    val available: Boolean
    val settings: StateFlow<BuiltInProxySettings>
    val state: StateFlow<BuiltInProxyState>

    suspend fun start()
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setMode(mode: ProxyMode)
    suspend fun selectNode(id: String)
    suspend fun setNodeEnabled(id: String, enabled: Boolean)
    suspend fun moveNode(id: String, offset: Int)
    suspend fun removeNode(id: String)
    suspend fun importNodes(content: String): List<ProxyNodeItem>
    suspend fun addSubscription(name: String, url: String): ProxySubscription
    suspend fun readSubscriptionUrl(id: String): String?
    suspend fun refreshSubscription(id: String): ProxySubscription
    suspend fun removeSubscription(id: String)
    suspend fun validateYaml(yaml: String): ProxyYamlValidation
    suspend fun readAdvancedYaml(): String?
    suspend fun setAdvancedYaml(yaml: String?): ProxyYamlValidation
    suspend fun syncAdvancedYaml()
    suspend fun testLatency(nodeIds: List<String>, url: String? = null, timeoutMillis: Long = 5_000): List<ProxyLatencyResult>
}
