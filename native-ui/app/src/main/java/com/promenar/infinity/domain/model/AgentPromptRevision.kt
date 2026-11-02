package com.promenar.infinity.domain.model

/** Agent 提示词的一个历史版本（`agent_rule_revisions` 表），每个 Agent 保留最近 20 版。 */
data class AgentPromptRevision(
    val id: String,
    val agentId: String,
    val content: String,
    val source: Source,
    val createdAt: Long,
) {
    /** 来源与表中 `source` 列一一对应。 */
    enum class Source(val storageKey: String) {
        /** 用户在编辑页保存，或首次被修改前记下的基线。 */
        USER("user"),
        /** `agents_write` 写入。 */
        AGENT("agent"),
        /** 从历史版本恢复。 */
        ROLLBACK("rollback"),
        ;

        companion object {
            fun fromStorage(raw: String): Source = entries.firstOrNull { it.storageKey == raw } ?: USER
        }
    }
}
