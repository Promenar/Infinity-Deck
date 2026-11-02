package com.promenar.infinity.domain.agentconfig

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `agents_read` / `agents_write` 的名称与审批目标格式；工具、策略与审批卡共用。 */
object AgentConfigTools {
    const val READ = "agents_read"
    const val WRITE = "agents_write"

    /**
     * `agents_write` 的审批目标以此开头：`agent-config:<op>:<目标 id 或 new>:<涉及字段现值摘要>`。
     * 执行时重新计算摘要，与批准时不同即判定目标已变化；审批卡不显示原始目标串。
     */
    const val TARGET_PREFIX = "agent-config:"

    fun isWriteTarget(target: String?): Boolean = target?.startsWith(TARGET_PREFIX) == true
}

enum class AgentConfigOp(val wireName: String) {
    CREATE_AGENT("create_agent"),
    UPDATE_AGENT("update_agent"),
    CREATE_SESSION("create_session"),
    UPDATE_SESSION("update_session"),
    ;

    val targetsAgent: Boolean get() = this == CREATE_AGENT || this == UPDATE_AGENT

    companion object {
        fun fromWire(raw: String?): AgentConfigOp? = entries.firstOrNull { it.wireName == raw }
    }
}

/**
 * 会话可由 `agents_write` 写入的字段键。Agent 字段键见 [com.promenar.infinity.domain.model.AgentField.toolKey]。
 * `inferenceParams` 的值是 [com.promenar.infinity.data.model.InferenceParams] 的 JSON 子集。
 */
object SessionConfigKeys {
    const val AGENT_ID = "agentId"
    const val TITLE = "title"
    const val CUSTOM_PROMPT = "customPrompt"
    const val MODEL_ID = "modelId"
    const val INFERENCE_PARAMS = "inferenceParams"
    const val ACTIVE_SKILL_IDS = "activeSkillIds"
    const val ACTIVE_MCP_SERVER_IDS = "activeMcpServerIds"

    val createSession: List<String> = listOf(AGENT_ID, TITLE, CUSTOM_PROMPT, MODEL_ID)
    val updateSession: List<String> = listOf(TITLE, CUSTOM_PROMPT, MODEL_ID, INFERENCE_PARAMS, ACTIVE_SKILL_IDS, ACTIVE_MCP_SERVER_IDS)
}

/**
 * `agents_write` 参数的结构层解析：只确认 `op`、`fields`、`reason` 的形状，不做字段白名单、类型与引用校验
 * （那些由工具在审批前完成）。审批卡用它读取要展示的字段。
 */
data class AgentsWriteRequest(
    val op: AgentConfigOp,
    /** 目标 Agent 或会话 id；省略时为当前 Agent / 当前会话（新建操作忽略）。 */
    val id: String?,
    val fields: JsonObject,
    val reason: String,
) {
    companion object {
        fun parse(args: JsonObject): AgentsWriteRequest? {
            val op = AgentConfigOp.fromWire(args.string("op")) ?: return null
            val fields = args["fields"] as? JsonObject ?: JsonObject(emptyMap())
            return AgentsWriteRequest(op, args.string("id")?.takeIf(String::isNotBlank), fields, args.string("reason").orEmpty())
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
