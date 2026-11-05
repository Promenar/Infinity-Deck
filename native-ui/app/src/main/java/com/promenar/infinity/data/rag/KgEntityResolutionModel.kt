package com.promenar.infinity.data.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 只在调用方明确运行解析轮时提交候选；使用图谱模型的现有请求与用量链路。 */
class KgEntityResolutionModel(
    private val client: KgRequestClient,
    private val timeoutMs: Long = 120_000,
) : KgResolutionJudge {
    override suspend fun decide(modelId: String, pairs: List<KgResolutionPair>, maxOutputTokens: Int): List<KgResolutionDecision> {
        require(pairs.isNotEmpty() && pairs.size <= 50)
        fun node(value: KgResolutionNode) = buildJsonObject {
            put("id", value.id); put("name", value.name); put("type", value.type)
            value.description?.let { put("description", it) }
            put("aliases", buildJsonArray { value.aliases.forEach { add(JsonPrimitive(it)) } })
            put("quotes", buildJsonArray { value.quotes.take(2).forEach { add(JsonPrimitive(it)) } })
        }
        val input = buildJsonArray { pairs.forEach { pair -> add(buildJsonObject {
            put("first", node(pair.first)); put("second", node(pair.second))
        }) } }.toString()
        var decisions: List<KgResolutionDecision>? = null
        val result = client.extract(modelId, PROMPT, input, timeoutMs, maxOutputTokens, responseParser = { content ->
            val parsed = runCatching {
                val first = content.indexOf('{'); val last = content.lastIndexOf('}')
                require(first >= 0 && last > first)
                val root = Json.parseToJsonElement(content.substring(first, last + 1)) as JsonObject
                (root["decisions"] as JsonArray).map { row ->
                    val obj = row as JsonObject
                    fun value(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    KgResolutionDecision(value("from"), value("to"), value("verdict"))
                }
            }.getOrNull()
            if (parsed == null) ExtractionResult.failure(KgExtractionStatus.PARSE_FAILURE, "实体判定响应格式无效")
            else { decisions = parsed; ExtractionResult(emptyList(), emptyList()) }
        })
        check(result.isSuccess) { result.error ?: "实体判定请求失败" }
        return requireNotNull(decisions)
    }

    companion object {
        const val PROMPT = """判定每对候选是否为同一个实体。只依据名称、类型、描述和原文，证据不足时使用 uncertain。每对各返回一项，保留输入 ID。输出 JSON：{"decisions":[{"from":"第一个实体ID","to":"第二个实体ID","verdict":"same、different 或 uncertain"}]}。"""
    }
}
