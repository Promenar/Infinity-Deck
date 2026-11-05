package com.promenar.infinity.data.rag

import kotlinx.serialization.Serializable

@Serializable
data class KgTokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimated: Boolean = false,
) {
    operator fun plus(other: KgTokenUsage) = KgTokenUsage(
        inputTokens + other.inputTokens, outputTokens + other.outputTokens, estimated || other.estimated,
    )
}

@Serializable
enum class KgExtractionStopReason { CALLS, OUTPUT_TOKENS, DURATION, CONFIRMATION_REQUIRED }

@Serializable
data class KgExtractionProgress(
    val processedChunks: Int,
    val totalChunks: Int,
    val reason: KgExtractionStopReason? = null,
    val calls: Int = 0,
    val elapsedMs: Long = 0,
) {
    val isPartial: Boolean get() = reason != null || processedChunks < totalChunks
    val description: String get() = "已处理 $processedChunks / $totalChunks 分块"
}

data class KgExtractionEstimate(
    val calls: Int,
    val inputTokens: Long,
    val outputTokenLimit: Long,
    val budgetedCalls: Int,
    val requiresConfirmation: Boolean,
)

/** 预算作用于同一文档版本；断点恢复沿用已发生的调用、输出与活跃耗时。 */
data class KgExtractionBudget(
    val maxCalls: Int = 32,
    val maxOutputTokens: Int = 32_768,
    val requestMaxOutputTokens: Int = 4_096,
    val maxDurationMs: Long = 180_000,
    val largeDocumentConfirmCalls: Int = 16,
) {
    init {
        require(maxCalls > 0 && maxOutputTokens > 0 && requestMaxOutputTokens > 0 && maxDurationMs > 0 && largeDocumentConfirmCalls > 0) {
            "知识图谱预算必须大于 0"
        }
    }

    fun estimate(text: String, chunkSize: Int, chunkOverlap: Int, systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT): KgExtractionEstimate {
        val calls = chunkCount(text, chunkSize, chunkOverlap)
        val repeatedCharacters = text.length.toLong() + (calls - 1).coerceAtLeast(0).toLong() * chunkOverlap + systemPrompt.length.toLong() * calls
        return KgExtractionEstimate(
            calls = calls,
            // 字符估算不依赖提供商分词器；确认界面明确标为估算。
            inputTokens = (repeatedCharacters + 1) / 2,
            outputTokenLimit = calls.toLong() * requestMaxOutputTokens,
            budgetedCalls = minOf(calls, maxCalls),
            requiresConfirmation = calls > largeDocumentConfirmCalls,
        )
    }

    companion object {
        const val CONFIRMED_STRATEGY = "full_confirmed"

        fun from(config: RagConfiguration) = KgExtractionBudget(
            maxCalls = config.kgMaxCalls.coerceAtLeast(1),
            maxOutputTokens = config.kgMaxOutputTokens.coerceAtLeast(1),
            requestMaxOutputTokens = config.kgRequestMaxOutputTokens.coerceAtLeast(1),
            maxDurationMs = config.kgMaxDurationSeconds.coerceAtLeast(1) * 1_000L,
            largeDocumentConfirmCalls = config.kgLargeDocumentConfirmCalls.coerceAtLeast(1),
        )

        fun chunkCount(text: String, chunkSize: Int, chunkOverlap: Int): Int {
            require(chunkSize > 0 && chunkOverlap in 0 until chunkSize) { "知识图谱切块参数无效" }
            if (text.isBlank()) return 0
            if (text.length <= chunkSize) return 1
            val step = chunkSize - chunkOverlap
            return 1 + ((text.length.toLong() - chunkSize + step - 1) / step).toInt()
        }
    }
}

@Serializable
internal data class GraphExtractionBudgetState(
    val calls: Int = 0,
    val outputTokens: Long = 0,
    val elapsedMs: Long = 0,
    val usage: KgTokenUsage = KgTokenUsage(),
)
