package com.promenar.infinity.data.model

import kotlinx.serialization.Serializable

/** 最近单次主模型请求的输入基线；只保存计数与指纹，不保存提示词或附件内容。 */
@Serializable
data class PromptUsageBaseline(
    val sessionId: String,
    val modelKey: String,
    val summaryHash: String,
    val toolsHash: String,
    val inputTokens: Int,
    val messages: List<PromptMessageBaseline>,
    val version: Int = 1,
)

@Serializable
data class PromptMessageBaseline(
    val bodyHash: String,
    val mediaHashes: Map<String, List<String>> = emptyMap(),
)
