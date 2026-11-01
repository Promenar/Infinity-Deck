package com.promenar.infinity.data.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 只允许真实用户消息携带轮次上下文，空字符串沿用无上下文的编码。 */
internal fun ProtocolMessage.userTurnContext(): String? =
    turnContext?.takeIf { role == "user" && it.isNotEmpty() }

/** 纯文本协议使用固定分隔符，保留上下文和正文的全部空白。 */
internal fun ProtocolMessage.textWithTurnContext(text: String = content): String =
    userTurnContext()?.let { "$it\n\n$text" } ?: text

/** 工具先按名称排序；MCP 参数对象递归排序字段，数组顺序保持不变。 */
internal fun List<ProtocolTool>.canonicalizedForRequest(): List<ProtocolTool> =
    sortedBy { it.function.name }.map { tool ->
        if (tool.sourceId != "mcp" && tool.mcpServerId == null && !tool.runtimeToolId.startsWith("mcp:")) {
            tool
        } else {
            val parameters = runCatching {
                Json.parseToJsonElement(tool.function.parameters).canonicalizedObjectKeys().toString()
            }.getOrNull()
            // 无效 JSON 沿用各协议原有的拒绝或降级处理。
            if (parameters == null || parameters == tool.function.parameters) tool
            else tool.copy(function = tool.function.copy(parameters = parameters))
        }
    }

private fun JsonElement.canonicalizedObjectKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { (key, value) -> key to value.canonicalizedObjectKeys() })
    is JsonArray -> JsonArray(map { it.canonicalizedObjectKeys() })
    else -> this
}
