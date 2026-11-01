package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TokenUsage

/**
 * 一个回复气泡（组内全部模型轮次）的生成统计。缺数据的项为 null，界面显示“—”或不显示。
 *
 * Token 只统计主模型：消息用量中并入的摘要模型部分（summary*）被扣除，避免混入其它模型。
 */
internal data class GenerationStatsSummary(
    val rounds: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    /** 主模型输入缓存；本组没有服务商实报的用量时为 null。 */
    val cache: GenerationCacheSummary?,
    val reasoningTokens: Long?,
    val estimated: Boolean,
    val timeToFirstTokenMs: Long?,
    val endToEndMs: Long?,
    /** 输出合计 ÷ Σ各轮(结束 − 首个输出)，单位 Token/秒。 */
    val generationTokensPerSecond: Double?,
    /** 输出合计 ÷ 端到端耗时，单位 Token/秒。 */
    val endToEndTokensPerSecond: Double?,
    val runtime: GenerationRuntimeBreakdown?,
    /** 组内全部工具调用次数（按调用 ID 合并调用与结果）。 */
    val toolCalls: Int = 0,
)

/**
 * 一组主模型请求的输入缓存统计。只有报告了缓存读取且数值正常（读取 + 写入不超过主模型输入）的轮次计入
 * [readTokens]、[writeTokens] 与 [reportedInputTokens]；未报告的轮次不当作 0。
 */
internal data class GenerationCacheSummary(
    val readTokens: Long,
    val writeTokens: Long,
    val reportedInputTokens: Long,
    val reportedRounds: Int,
    /** 有服务商实报用量的轮次数（不含本地估算）。 */
    val totalRounds: Int,
) {
    val uncachedTokens: Long get() = (reportedInputTokens - readTokens - writeTokens).coerceAtLeast(0)

    /** 命中率 0..1；没有已报告轮次或输入为 0 时为 null。 */
    val hitRate: Double? get() = if (reportedRounds > 0 && reportedInputTokens > 0) readTokens.toDouble() / reportedInputTokens else null
}

/** 运行时间分布；[toolMs] 与 [otherMs] 在工具耗时未知时为 null。 */
internal data class GenerationRuntimeBreakdown(
    val modelMs: Long,
    val toolMs: Long?,
    val otherMs: Long?,
)

private class RoundTiming(val start: Long?, val firstOutput: Long?, val end: Long?)

/** 组内每条消息的工具调用墙钟耗时；没有工具调用的消息不计入，耗时未知的为 null。 */
internal fun groupToolDurationsMs(messages: List<Message>): List<Long?> = messages.flatMap { message ->
    val invocations = toolInvocations(message.executionSteps.orEmpty())
    if (invocations.isEmpty()) emptyList() else listOf(toolGroupDurationMs(invocations))
}

internal fun summarizeGenerationStats(
    messages: List<Message>,
    toolDurationsMs: List<Long?> = groupToolDurationsMs(messages),
): GenerationStatsSummary {
    val rounds = messages.filter { it.role == MessageRole.ASSISTANT }
    val usages = rounds.mapNotNull { it.tokens }
    fun mainPart(total: Int, summary: Int): Long = (total.toLong() - summary.toLong().coerceAtLeast(0)).coerceAtLeast(0)
    val roundOutputs = rounds.map { msg -> msg.tokens?.let { mainPart(it.output, it.summaryOutput) } }
    val input = usages.takeIf { it.isNotEmpty() }?.sumOf { mainPart(it.input, it.summaryInput) }
    val output = usages.takeIf { it.isNotEmpty() }?.sumOf { mainPart(it.output, it.summaryOutput) }
    val reasoning = usages.mapNotNull { it.reasoningOutput?.toLong()?.coerceAtLeast(0) }.takeIf { it.isNotEmpty() }?.sum()
    val cache = summarizeCache(usages)

    val timings = rounds.map { msg ->
        val record = msg.tokens?.timing
        RoundTiming(
            start = record?.requestStartedAt?.takeIf { it > 0 },
            firstOutput = record?.firstOutputAt?.takeIf { it > 0 },
            end = record?.endedAt?.takeIf { it > 0 },
        )
    }
    fun span(from: Long?, to: Long?): Long? = if (from == null || to == null) null else (to - from).takeIf { it >= 0 }

    val ttft = timings.firstOrNull()?.let { span(it.start, it.firstOutput) }
    val endToEnd = span(timings.firstOrNull()?.start, timings.lastOrNull()?.end)

    // 只用计时完整且时长为正的轮次，分子分母取同一批轮次，避免旧消息缺计时导致速度虚高。
    var generationMs = 0L
    var generationOutput = 0L
    timings.forEachIndexed { index, timing ->
        val duration = span(timing.firstOutput, timing.end)
        val roundOutput = roundOutputs[index]
        if (duration != null && duration > 0 && roundOutput != null) {
            generationMs += duration
            generationOutput += roundOutput
        }
    }
    val generationTps = if (generationMs > 0) generationOutput * 1_000.0 / generationMs else null
    val endToEndTps = if (output != null && endToEnd != null && endToEnd > 0) output * 1_000.0 / endToEnd else null

    val modelSpans = timings.map { span(it.start, it.end) }
    val modelMs = if (modelSpans.isNotEmpty() && modelSpans.all { it != null }) modelSpans.sumOf { it!! } else null
    val toolMs = if (toolDurationsMs.all { it != null && it >= 0 }) toolDurationsMs.sumOf { it!! } else null
    val runtime = modelMs?.let { model ->
        GenerationRuntimeBreakdown(
            modelMs = model,
            toolMs = toolMs,
            otherMs = if (toolMs != null && endToEnd != null) (endToEnd - model - toolMs).coerceAtLeast(0) else null,
        )
    }

    return GenerationStatsSummary(
        rounds = rounds.size,
        inputTokens = input,
        outputTokens = output,
        cache = cache,
        reasoningTokens = reasoning,
        estimated = usages.any { it.estimated },
        timeToFirstTokenMs = ttft,
        endToEndMs = endToEnd,
        generationTokensPerSecond = generationTps,
        endToEndTokensPerSecond = endToEndTps,
        runtime = runtime,
        toolCalls = messages.sumOf { toolInvocations(it.executionSteps.orEmpty()).size },
    )
}

private fun summarizeCache(usages: List<TokenUsage>): GenerationCacheSummary? {
    val reportedUsages = usages.filterNot { it.estimated }
    if (reportedUsages.isEmpty()) return null
    var read = 0L
    var write = 0L
    var reportedInput = 0L
    var reportedRounds = 0
    reportedUsages.forEach { usage ->
        val cacheRead = usage.cacheReadInput?.toLong() ?: return@forEach
        val cacheWrite = usage.cacheWriteInput?.toLong()?.coerceAtLeast(0) ?: 0
        // 摘要模型并入的用量没有缓存字段，命中率只按主模型输入计算。
        val mainInput = (usage.input.toLong() - usage.summaryInput.toLong().coerceAtLeast(0)).coerceAtLeast(0)
        if (cacheRead < 0 || cacheRead + cacheWrite > mainInput) return@forEach
        read += cacheRead
        write += cacheWrite
        reportedInput += mainInput
        reportedRounds++
    }
    return GenerationCacheSummary(read, write, reportedInput, reportedRounds, reportedUsages.size)
}
