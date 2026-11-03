package com.promenar.infinity.data.generation

data class ContextUsageEstimate(
    val systemTokens: Int,
    val summaryTokens: Int,
    val conversationTokens: Int,
    val ragTokens: Int,
    val foldedToolResults: Int,
    val modelContextTokens: Int,
    /** 当前实际请求的分层估算；父项包含全部子项，界面仅对同层求和。 */
    val categories: List<ContextUsageCategory> = emptyList(),
) {
    val totalTokens: Int get() = (systemTokens.toLong() + summaryTokens + conversationTokens + ragTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

data class ContextUsageCategory(
    val id: String,
    val tokens: Int,
    val children: List<ContextUsageCategory> = emptyList(),
)

/** 构建器提供真实段落边界，避免把项目规则或用户指令中的同名标题当成系统段落。 */
data class ContextPromptSection(val id: String, val content: String)
