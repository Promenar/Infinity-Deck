package com.promenar.infinity.data.agentconfig

import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 配置输出与审批摘要共用的字段投影，避免把消息或其它运行数据序列化给模型。 */
internal object AgentConfigValues {
    val samplingKeys = setOf("temperature", "topP", "maxTokens", "topK", "frequencyPenalty", "presencePenalty", "repetitionPenalty", "reasoningEffort")
    private val integers = setOf("maxTokens", "topK")
    private val arrays = setOf("mcpServerIds", "activeSkillIds", "activeMcpServerIds")
    private val nullableStrings = setOf("modelId", "defaultWorkspaceId", "customPrompt", "reasoningEffort")

    fun agent(agent: Agent): JsonObject = JsonObject(AgentField.toolWritable.associate { field ->
        field.toolKey!! to when (field) {
            AgentField.NAME -> JsonPrimitive(agent.name)
            AgentField.DESCRIPTION -> JsonPrimitive(agent.description)
            else -> value(field.read(agent))
        }
    })

    fun inference(params: InferenceParams?): JsonElement = params?.let { p -> buildJsonObject {
        put("temperature", value(p.temperature)); put("topP", value(p.topP)); put("maxTokens", value(p.maxTokens))
        put("topK", value(p.topK)); put("frequencyPenalty", value(p.frequencyPenalty)); put("presencePenalty", value(p.presencePenalty))
        put("repetitionPenalty", value(p.repetitionPenalty)); put("reasoningEffort", value(p.reasoningEffort))
    } } ?: JsonNull

    fun session(session: Session): JsonObject = buildJsonObject {
        put("title", session.title); put("customPrompt", value(session.customPrompt)); put("modelId", value(session.modelId))
        put("inferenceParams", inference(session.inferenceParams))
        put("activeSkillIds", value(session.activeSkillIds)); put("activeMcpServerIds", value(session.activeMcpServerIds))
    }

    fun validate(key: String, element: JsonElement) {
        when {
            key == "inferenceParams" -> {
                if (element == JsonNull) return
                require(element is JsonObject) { "$key 必须是对象或 null" }
                require(element.keys.all { it in samplingKeys }) { "inferenceParams 只允许采样参数与 reasoningEffort" }
                element.forEach { (nested, item) -> validate(nested, item) }
            }
            key in arrays -> require(element is JsonArray && element.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) {
                "$key 必须是非空字符串组成的数组"
            }
            key == "toolsEnabledByDefault" -> require(element is JsonPrimitive && !element.isString && element.booleanOrNull != null) { "$key 必须是 boolean" }
            key in samplingKeys && key != "reasoningEffort" -> {
                if (element == JsonNull) return
                require(element is JsonPrimitive && !element.isString && element.doubleOrNull?.isFinite() == true) { "$key 必须是有限 number 或 null" }
                if (key in integers) require(element.intOrNull != null) { "$key 必须是 Int 范围内的整数" }
            }
            else -> {
                if (element == JsonNull && key in nullableStrings) return
                require(element is JsonPrimitive && element.isString) { "$key 必须是 string${if (key in nullableStrings) " 或 null" else ""}" }
                if (key in setOf("name", "agentId", "modelId", "defaultWorkspaceId")) require(element.content.isNotBlank()) { "$key 不能为空" }
                if (key == "reasoningEffort") require(ReasoningEffort.entries.any { it.wireName == element.content }) { "reasoningEffort 不在支持列表中" }
            }
        }
    }

    fun applyAgent(base: Agent, fields: JsonObject): Agent = fields.entries.fold(base) { a, (key, v) ->
        val text = (v as? JsonPrimitive)?.contentOrNull
        val number = (v as? JsonPrimitive)?.doubleOrNull
        val integer = (v as? JsonPrimitive)?.intOrNull
        when (key) {
            "name" -> a.copy(name = text!!, nameCustomized = true)
            "description" -> a.copy(description = text!!, descriptionCustomized = true)
            "systemPrompt" -> a.copy(systemPrompt = text!!)
            "modelId" -> a.copy(modelId = text.orEmpty())
            "temperature" -> a.copy(temperature = number)
            "topP" -> a.copy(topP = number)
            "maxTokens" -> a.copy(maxTokens = integer)
            "topK" -> a.copy(topK = integer)
            "frequencyPenalty" -> a.copy(frequencyPenalty = number)
            "presencePenalty" -> a.copy(presencePenalty = number)
            "repetitionPenalty" -> a.copy(repetitionPenalty = number)
            "reasoningEffort" -> a.copy(reasoningEffort = text)
            "mcpServerIds" -> a.copy(mcpServerIds = strings(v))
            "defaultWorkspaceId" -> a.copy(defaultWorkspaceId = text)
            "toolsEnabledByDefault" -> a.copy(toolsEnabledByDefault = (v as JsonPrimitive).boolean)
            else -> error("未校验的 Agent 字段")
        }
    }

    fun applyInference(base: InferenceParams?, element: JsonElement): InferenceParams? {
        if (element == JsonNull) return base?.copy(temperature = null, topP = null, maxTokens = null, topK = null,
            frequencyPenalty = null, presencePenalty = null, repetitionPenalty = null, reasoningEffort = null)
        return (element as JsonObject).entries.fold(base ?: InferenceParams()) { p, (key, v) ->
            val number = (v as? JsonPrimitive)?.doubleOrNull
            val integer = (v as? JsonPrimitive)?.intOrNull
            when (key) {
                "temperature" -> p.copy(temperature = number)
                "topP" -> p.copy(topP = number)
                "maxTokens" -> p.copy(maxTokens = integer)
                "topK" -> p.copy(topK = integer)
                "frequencyPenalty" -> p.copy(frequencyPenalty = number)
                "presencePenalty" -> p.copy(presencePenalty = number)
                "repetitionPenalty" -> p.copy(repetitionPenalty = number)
                "reasoningEffort" -> p.copy(reasoningEffort = (v as? JsonPrimitive)?.contentOrNull)
                else -> error("未校验的推理参数")
            }
        }
    }

    fun sessionUpdates(base: Session, fields: JsonObject): Map<String, Any?> = fields.mapValues { (key, v) ->
        when (key) {
            "inferenceParams" -> applyInference(base.inferenceParams, v)
            "activeSkillIds", "activeMcpServerIds" -> strings(v)
            else -> (v as? JsonPrimitive)?.contentOrNull
        }
    }

    fun strings(element: JsonElement): List<String> = (element as JsonArray).map { (it as JsonPrimitive).content }

    fun value(raw: Any?): JsonElement = when (raw) {
        null -> JsonNull
        is String -> JsonPrimitive(raw)
        is Boolean -> JsonPrimitive(raw)
        is Number -> JsonPrimitive(raw)
        is List<*> -> JsonArray(raw.map { value(it) })
        else -> error("配置字段类型不支持")
    }

    fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
