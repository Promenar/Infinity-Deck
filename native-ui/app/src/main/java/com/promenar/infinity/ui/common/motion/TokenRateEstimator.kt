package com.promenar.infinity.ui.common.motion

/** 按 UTF-16 单元估算增量；权重可相加，分片边界不会改变结果。 */
internal fun estimateTokenDelta(text: CharSequence, start: Int = 0): Float {
    var units = 0
    for (i in start until text.length) {
        val c = text[i]
        units += when {
            c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff' ||
                c in '\u3040'..'\u30ff' || c in '\uac00'..'\ud7af' || c in '\u1100'..'\u11ff' -> 24
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> 10
            c.isWhitespace() -> 5
            c.code in 0x21..0x7e || Character.getType(c) in PunctuationTypes -> 20
            else -> 20
        }
    }
    return units / 40f
}

private val PunctuationTypes = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)

internal class TokenRateCalibration {
    private val factors = mutableMapOf<String, Float>()
    @Synchronized fun factor(model: String): Float = factors[model] ?: 1f
    @Synchronized fun observe(model: String, estimated: Float, actual: Int) {
        if (model.isBlank() || estimated <= 0f || actual <= 0) return
        val target = (actual / estimated).coerceIn(0.4f, 2.5f)
        factors[model] = (factor(model) * 0.8f + target * 0.2f).coerceIn(0.4f, 2.5f)
    }
}

internal object ProcessTokenRateCalibration {
    val value = TokenRateCalibration()
}

/** 分别累计正文与思考，文本回退时只重新建立基准。 */
internal class IncrementalTokenEstimate {
    private var contentLength = 0
    private var reasoningLength = 0
    var total = 0f
        private set

    fun resetTextBaseline() { contentLength = 0; reasoningLength = 0 }

    fun update(content: String, reasoning: String): Float {
        if (content.length >= contentLength) total += estimateTokenDelta(content, contentLength)
        if (reasoning.length >= reasoningLength) total += estimateTokenDelta(reasoning, reasoningLength)
        contentLength = content.length
        reasoningLength = reasoning.length
        return total
    }
}

internal data class TokenMotionInput(
    val content: String,
    val reasoning: String,
    val messageId: String?,
    val modelId: String?,
    val runStartedAt: Long?,
    val requestStartedAt: Long?,
    val firstOutputAt: Long?,
    val endedAt: Long?,
    val actualTokens: Int?,
    val eligibleUsage: Boolean,
)

/** 只为身份与时间均匹配、且只有一条助手消息的生成应用真实用量。 */
internal class TokenMotionTracker(private val calibration: TokenRateCalibration) {
    private var estimate = IncrementalTokenEstimate()
    private var active = false
    private var runStartedAt: Long? = null
    private var runModel: String? = null
    private var messageId: String? = null
    private var multipleMessages = false
    private var calibrated = false
    private var factor = 1f

    fun update(running: Boolean, input: TokenMotionInput, motion: GenerationMotion) {
        if (running && !active) {
            estimate = IncrementalTokenEstimate()
            runStartedAt = input.runStartedAt
            runModel = input.modelId
            messageId = null
            multipleMessages = false
            calibrated = false
            factor = input.modelId?.let(calibration::factor) ?: 1f
        }
        if (running || active || (!calibrated && messageId != null)) {
            if (runStartedAt == null) runStartedAt = input.runStartedAt
            val belongsToRun = runStartedAt != null && input.requestStartedAt != null && input.requestStartedAt >= runStartedAt!!
            if (belongsToRun && (input.content.isNotEmpty() || input.reasoning.isNotEmpty())) {
                if (messageId == null) {
                    messageId = input.messageId
                    runModel = input.modelId
                    factor = input.modelId?.let(calibration::factor) ?: 1f
                } else if (input.messageId != messageId) {
                    multipleMessages = true
                    messageId = input.messageId
                    estimate.resetTextBaseline()
                }
                estimate.update(input.content, input.reasoning)
            }
            motion.updateCurrentTokens(estimate.total * factor)
        }
        active = running
    }

    fun finish(input: TokenMotionInput, motion: GenerationMotion) {
        if (active || calibrated || multipleMessages || !input.eligibleUsage) return
        val id = messageId ?: return
        val model = runModel?.takeIf { it.isNotBlank() } ?: return
        val start = runStartedAt ?: return
        val request = input.requestStartedAt ?: return
        val first = input.firstOutputAt ?: return
        val end = input.endedAt ?: return
        val actual = input.actualTokens ?: return
        if (input.messageId != id || input.modelId != model || request < start || first < request || end <= first || actual <= 0 || estimate.total <= 0f) return
        calibration.observe(model, estimate.total, actual)
        motion.applyActualRate(actual, end - first)
        calibrated = true
    }
}
