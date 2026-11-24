package com.promenar.infinity.data.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal val proxyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

internal object ProxyOps {
    const val START = "start"
    const val RELOAD = "reload"
    const val DNS = "set_dns"
    const val STATUS = "status"
    const val LATENCY = "test_latency"
    const val VALIDATE = "validate_yaml"
    const val IMPORT = "parse_import"
    const val STOP = "shutdown"
}

@Serializable
internal class ProxyRequest(val id: Long, val method: String, val params: JsonObject) {
    override fun toString(): String = "ProxyRequest(id=$id, method=$method)"
}

@Serializable
internal data class ProxyReplyState(
    val running: Boolean = false,
    val mode: ProxyMode? = null,
    val activeNode: String? = null,
    val healthyNodeCount: Int = 0,
    val fallbackReason: String? = null,
    val nodes: List<ProxyNodeSummary> = emptyList(),
)

@Serializable
internal data class ProxyStartReply(val proxyPort: Int, val activeNode: String? = null, val nodeCount: Int = 0)

@Serializable
internal class ProxyImportReply(
    val nodes: List<ProxyNodeSummary> = emptyList(),
    val nodeConfigs: List<JsonObject> = emptyList(),
) {
    fun configs(): List<ProxyNodeConfig> {
        val configs = nodeConfigs.associateBy { (it["name"] as? JsonPrimitive)?.contentOrNull }
        return nodes.map { node ->
            ProxyNodeConfig(node, configs[node.name] ?: throw BuiltInProxyUnavailableException("内置代理节点数据不完整"))
        }
    }
}

@Serializable
internal class ProxyValidationReply(
    val valid: Boolean,
    val error: String? = null,
    val nodes: List<ProxyNodeSummary> = emptyList(),
    val nodeConfigs: List<JsonObject> = emptyList(),
) {
    fun configs(): List<ProxyNodeConfig> = ProxyImportReply(nodes, nodeConfigs).configs()
    fun summary(): ProxyYamlValidation = ProxyYamlValidation(valid, error, nodes)
}

@Serializable
internal data class ProxyLatencyReply(val nodes: List<ProxyLatencyResult> = emptyList())

internal sealed interface ProxyMessage {
    data class State(val value: ProxyReplyState) : ProxyMessage
    class Response(val id: Long, val result: JsonElement?, val failed: Boolean) : ProxyMessage
}

internal fun decodeProxyMessage(line: String): ProxyMessage? = runCatching {
    val objectValue = proxyJson.parseToJsonElement(line) as? JsonObject ?: return null
    if ((objectValue["event"] as? JsonPrimitive)?.contentOrNull == "state") {
        return ProxyMessage.State(proxyJson.decodeFromJsonElement(ProxyReplyState.serializer(), objectValue["state"] ?: return null))
    }
    val id = (objectValue["id"] as? JsonPrimitive)?.longOrNull ?: return null
    ProxyMessage.Response(id, objectValue["result"], objectValue["error"] is JsonObject)
}.getOrNull()
