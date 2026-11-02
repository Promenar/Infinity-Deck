package com.promenar.infinity.domain.model

/**
 * Agent 可按字段写入的列。编辑页与 `agents_write` 都只写自己改过的字段，未列出的列沿用数据库现值。
 *
 * [toolKey] 非空的字段可由 `agents_write` 写入，键名即工具参数 `fields` 中的名称；
 * 为空的字段（头像、置顶、检索与记忆设置、权限档）只能由用户在界面修改。
 * [NAME]、[DESCRIPTION] 同时携带对应的 `*Customized` 标记。
 */
enum class AgentField(val toolKey: String?) {
    NAME("name"),
    DESCRIPTION("description"),
    SYSTEM_PROMPT("systemPrompt"),
    MODEL_ID("modelId"),
    TEMPERATURE("temperature"),
    TOP_P("topP"),
    MAX_TOKENS("maxTokens"),
    TOP_K("topK"),
    FREQUENCY_PENALTY("frequencyPenalty"),
    PRESENCE_PENALTY("presencePenalty"),
    REPETITION_PENALTY("repetitionPenalty"),
    REASONING_EFFORT("reasoningEffort"),
    MCP_SERVER_IDS("mcpServerIds"),
    DEFAULT_WORKSPACE_ID("defaultWorkspaceId"),
    TOOLS_ENABLED_BY_DEFAULT("toolsEnabledByDefault"),
    ICON(null),
    COLOR(null),
    AVATAR_PATH(null),
    IS_PINNED(null),
    RETRIEVAL_CONFIG(null),
    EXECUTION_MODE(null),
    ;

    /** 该字段在 [agent] 中的值；用于比较是否改动，不用于展示。 */
    fun read(agent: Agent): Any? = when (this) {
        NAME -> agent.name to agent.nameCustomized
        DESCRIPTION -> agent.description to agent.descriptionCustomized
        SYSTEM_PROMPT -> agent.systemPrompt
        MODEL_ID -> agent.modelId
        TEMPERATURE -> agent.temperature
        TOP_P -> agent.topP
        MAX_TOKENS -> agent.maxTokens
        TOP_K -> agent.topK
        FREQUENCY_PENALTY -> agent.frequencyPenalty
        PRESENCE_PENALTY -> agent.presencePenalty
        REPETITION_PENALTY -> agent.repetitionPenalty
        REASONING_EFFORT -> agent.reasoningEffort
        MCP_SERVER_IDS -> agent.mcpServerIds
        DEFAULT_WORKSPACE_ID -> agent.defaultWorkspaceId
        TOOLS_ENABLED_BY_DEFAULT -> agent.toolsEnabledByDefault
        ICON -> agent.icon
        COLOR -> agent.color
        AVATAR_PATH -> agent.avatarPath
        IS_PINNED -> agent.isPinned
        RETRIEVAL_CONFIG -> agent.retrievalConfig
        EXECUTION_MODE -> agent.executionMode
    }

    /** 把 [from] 中该字段的值写入 [target]，其余字段保持 [target] 原值。 */
    fun copy(target: Agent, from: Agent): Agent = when (this) {
        NAME -> target.copy(name = from.name, nameCustomized = from.nameCustomized)
        DESCRIPTION -> target.copy(description = from.description, descriptionCustomized = from.descriptionCustomized)
        SYSTEM_PROMPT -> target.copy(systemPrompt = from.systemPrompt)
        MODEL_ID -> target.copy(modelId = from.modelId)
        TEMPERATURE -> target.copy(temperature = from.temperature)
        TOP_P -> target.copy(topP = from.topP)
        MAX_TOKENS -> target.copy(maxTokens = from.maxTokens)
        TOP_K -> target.copy(topK = from.topK)
        FREQUENCY_PENALTY -> target.copy(frequencyPenalty = from.frequencyPenalty)
        PRESENCE_PENALTY -> target.copy(presencePenalty = from.presencePenalty)
        REPETITION_PENALTY -> target.copy(repetitionPenalty = from.repetitionPenalty)
        REASONING_EFFORT -> target.copy(reasoningEffort = from.reasoningEffort)
        MCP_SERVER_IDS -> target.copy(mcpServerIds = from.mcpServerIds)
        DEFAULT_WORKSPACE_ID -> target.copy(defaultWorkspaceId = from.defaultWorkspaceId)
        TOOLS_ENABLED_BY_DEFAULT -> target.copy(toolsEnabledByDefault = from.toolsEnabledByDefault)
        ICON -> target.copy(icon = from.icon)
        COLOR -> target.copy(color = from.color)
        AVATAR_PATH -> target.copy(avatarPath = from.avatarPath)
        IS_PINNED -> target.copy(isPinned = from.isPinned)
        RETRIEVAL_CONFIG -> target.copy(retrievalConfig = from.retrievalConfig)
        EXECUTION_MODE -> target.copy(executionMode = from.executionMode)
    }

    companion object {
        /** `agents_write` 的 `create_agent` / `update_agent` 可写字段。 */
        val toolWritable: List<AgentField> = entries.filter { it.toolKey != null }

        fun byToolKey(key: String): AgentField? = toolWritable.firstOrNull { it.toolKey == key }

        /** [base] 与 [other] 之间值不同的字段。 */
        fun changed(base: Agent, other: Agent): Set<AgentField> =
            entries.filterTo(linkedSetOf()) { it.read(base) != it.read(other) }
    }
}

/** 字段级写入的结果。 */
sealed interface AgentFieldUpdate {
    /** 写入成功；[agent] 为写入后的数据库值。 */
    data class Updated(val agent: Agent) : AgentFieldUpdate

    data object NotFound : AgentFieldUpdate

    /** 给出 `expected` 时，其中涉及的字段已被他人修改；未写入任何列。[current] 为数据库现值。 */
    data class Conflict(val current: Agent, val fields: Set<AgentField>) : AgentFieldUpdate
}
