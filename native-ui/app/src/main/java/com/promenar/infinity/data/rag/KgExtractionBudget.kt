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

/** processedChunks 与 totalChunks 分别表示已完成及全部图谱窗口数。 */
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
    val windows: Int = calls,
)

/** 预算作用于单次运行，进程恢复时沿用同一运行的账本。 */
data class KgExtractionBudget(
    val maxCalls: Int = 64,
    val maxOutputTokens: Int = 262_144,
    val requestMaxOutputTokens: Int = 16_384,
    val maxDurationMs: Long = 600_000,
    val largeDocumentConfirmCalls: Int = 16,
) {
    init {
        require(maxCalls > 0 && maxOutputTokens > 0 && requestMaxOutputTokens > 0 && maxDurationMs > 0 && largeDocumentConfirmCalls > 0) {
            "知识图谱预算必须大于 0"
        }
    }

    fun estimate(text: String, windowChars: Int = 16000, gleaningPasses: Int = 1,
                 modelLimits: KgModelLimits? = null, systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT): KgExtractionEstimate {
        val limit = KgWindowing.effectiveOutputLimit(requestMaxOutputTokens, modelLimits)
        val effectiveWindow = KgWindowing.effectiveWindowChars(windowChars, modelLimits, limit, systemPrompt)
        val windows = KgWindowing.split(text, effectiveWindow)
        val passes = gleaningPasses.coerceIn(0, 2)
        val calls = windows.size * (1 + passes)
        val input = windows.sumOf { window ->
            (0..passes).sumOf { pass ->
                window.length.toLong() + systemPrompt.length + pass.toLong() * (limit + GraphExtractor.GLEANING_PROMPT.length)
            }
        }
        return KgExtractionEstimate(
            calls = calls,
            inputTokens = input,
            outputTokenLimit = calls.toLong() * limit,
            budgetedCalls = minOf(calls, maxCalls),
            requiresConfirmation = calls > largeDocumentConfirmCalls,
            windows = windows.size,
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

        fun chunkCount(text: String, windowChars: Int): Int = KgWindowing.split(text, windowChars).size
    }
}

@Serializable
internal data class GraphExtractionBudgetState(
    val calls: Int = 0,
    val outputTokens: Long = 0,
    val elapsedMs: Long = 0,
    val usage: KgTokenUsage = KgTokenUsage(),
)
