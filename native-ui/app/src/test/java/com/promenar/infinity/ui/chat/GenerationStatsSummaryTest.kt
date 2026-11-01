package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.GenerationTimingRecord
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TokenUsage
import org.junit.Test
import java.util.Locale

class GenerationStatsSummaryTest {
    private fun assistant(
        id: String,
        tokens: TokenUsage?,
        steps: List<ExecutionStep>? = null,
    ) = Message(id, MessageRole.ASSISTANT, "", tokens = tokens, executionSteps = steps)

    private fun timing(start: Long?, first: Long?, end: Long?) = GenerationTimingRecord(start, first, end)

    private fun toolSteps(id: String, start: Long, end: Long) = listOf(
        ExecutionStep("call-$id", "tool_call", "search", "{}", "call-$id", timestamp = start),
        ExecutionStep("res-$id", "tool_result", "search", toolCallId = "call-$id", content = "ok", timestamp = end),
    )

    @Test
    fun `单轮汇总 Token 首字耗时速度与时间分布`() {
        val summary = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(1_000, 200, 1_200, cacheReadInput = 600, reasoningOutput = 50, timing = timing(10_000, 10_500, 12_500))),
        ))

        assertThat(summary.rounds).isEqualTo(1)
        assertThat(summary.inputTokens).isEqualTo(1_000)
        assertThat(summary.outputTokens).isEqualTo(200)
        assertThat(summary.cache).isEqualTo(GenerationCacheSummary(readTokens = 600, writeTokens = 0, reportedInputTokens = 1_000, reportedRounds = 1, totalRounds = 1))
        assertThat(summary.cache!!.uncachedTokens).isEqualTo(400)
        assertThat(summary.cache!!.hitRate).isWithin(1e-9).of(0.6)
        assertThat(summary.reasoningTokens).isEqualTo(50)
        assertThat(summary.estimated).isFalse()
        assertThat(summary.timeToFirstTokenMs).isEqualTo(500)
        assertThat(summary.endToEndMs).isEqualTo(2_500)
        assertThat(summary.generationTokensPerSecond).isWithin(1e-9).of(100.0)
        assertThat(summary.endToEndTokensPerSecond).isWithin(1e-9).of(80.0)
        assertThat(summary.runtime).isEqualTo(GenerationRuntimeBreakdown(modelMs = 2_500, toolMs = 0, otherMs = 0))
    }

    @Test
    fun `多轮按气泡求和并扣除工具与其它时间`() {
        val messages = listOf(
            assistant("a", TokenUsage(1_000, 100, 1_100, cacheReadInput = 800, timing = timing(10_000, 10_400, 11_400)), toolSteps("1", 11_500, 13_500)),
            Message("t", MessageRole.TOOL, "结果"),
            assistant("b", TokenUsage(1_300, 300, 1_600, timing = timing(14_000, 14_600, 16_600))),
        )

        val summary = summarizeGenerationStats(messages)

        assertThat(summary.rounds).isEqualTo(2)
        assertThat(summary.inputTokens).isEqualTo(2_300)
        assertThat(summary.outputTokens).isEqualTo(400)
        // 只有第一轮报告了缓存：命中率只按已报告轮次计算，未报告的第二轮不当作 0。
        assertThat(summary.cache).isEqualTo(GenerationCacheSummary(readTokens = 800, writeTokens = 0, reportedInputTokens = 1_000, reportedRounds = 1, totalRounds = 2))
        assertThat(summary.cache!!.hitRate).isWithin(1e-9).of(0.8)
        assertThat(summary.cache!!.uncachedTokens).isEqualTo(200)
        assertThat(summary.reasoningTokens).isNull()
        assertThat(summary.timeToFirstTokenMs).isEqualTo(400)
        assertThat(summary.endToEndMs).isEqualTo(6_600)
        // 400 Token ÷ (1.0 s + 2.0 s)
        assertThat(summary.generationTokensPerSecond).isWithin(1e-9).of(400 / 3.0)
        assertThat(summary.endToEndTokensPerSecond).isWithin(1e-9).of(400 / 6.6)
        assertThat(summary.runtime).isEqualTo(GenerationRuntimeBreakdown(modelMs = 4_000, toolMs = 2_000, otherMs = 600))
    }

    @Test
    fun `旧消息缺计时与用量时返回 null 而不臆造`() {
        val noTokens = summarizeGenerationStats(listOf(assistant("a", null)))
        assertThat(noTokens.inputTokens).isNull()
        assertThat(noTokens.outputTokens).isNull()
        assertThat(noTokens.timeToFirstTokenMs).isNull()
        assertThat(noTokens.endToEndMs).isNull()
        assertThat(noTokens.generationTokensPerSecond).isNull()
        assertThat(noTokens.endToEndTokensPerSecond).isNull()
        assertThat(noTokens.runtime).isNull()

        val legacy = summarizeGenerationStats(listOf(assistant("a", TokenUsage(10, 5, 15))))
        assertThat(legacy.inputTokens).isEqualTo(10)
        // 服务商未报告缓存：保留缓存段但命中率为 null，界面显示“未报告”。
        assertThat(legacy.cache).isEqualTo(GenerationCacheSummary(0, 0, 0, reportedRounds = 0, totalRounds = 1))
        assertThat(legacy.cache!!.hitRate).isNull()
        assertThat(noTokens.cache).isNull()
        assertThat(legacy.generationTokensPerSecond).isNull()
        assertThat(legacy.runtime).isNull()
    }

    @Test
    fun `部分轮次缺计时时速度只用计时完整的轮次`() {
        val summary = summarizeGenerationStats(listOf(
            assistant("old", TokenUsage(100, 900, 1_000)),
            assistant("new", TokenUsage(100, 100, 200, timing = timing(5_000, 5_000, 6_000))),
        ))

        assertThat(summary.generationTokensPerSecond).isWithin(1e-9).of(100.0)
        assertThat(summary.timeToFirstTokenMs).isNull()
        assertThat(summary.endToEndMs).isNull()
        assertThat(summary.runtime).isNull()
    }

    @Test
    fun `估算标记与摘要用量扣除`() {
        val summary = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(150, 40, 190, estimated = true, summaryInput = 50, summaryOutput = 10, summaryTotal = 60)),
        ))

        assertThat(summary.estimated).isTrue()
        assertThat(summary.inputTokens).isEqualTo(100)
        assertThat(summary.outputTokens).isEqualTo(30)
        // 本地估算的用量不参与缓存统计。
        assertThat(summary.cache).isNull()
    }

    @Test
    fun `时钟回拨 除零与负值安全处理`() {
        val rollback = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(10, 10, 20, cacheReadInput = 50, timing = timing(5_000, 4_000, 3_000))),
        ))
        assertThat(rollback.timeToFirstTokenMs).isNull()
        assertThat(rollback.endToEndMs).isNull()
        assertThat(rollback.generationTokensPerSecond).isNull()
        assertThat(rollback.runtime).isNull()
        // 缓存读取大于输入属于统计异常，不计入已报告轮次。
        assertThat(rollback.cache).isEqualTo(GenerationCacheSummary(0, 0, 0, reportedRounds = 0, totalRounds = 1))

        val zeroDuration = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(10, 10, 20, timing = timing(1_000, 1_000, 1_000))),
        ))
        assertThat(zeroDuration.generationTokensPerSecond).isNull()
        assertThat(zeroDuration.endToEndTokensPerSecond).isNull()
        assertThat(zeroDuration.endToEndMs).isEqualTo(0)
        assertThat(zeroDuration.timeToFirstTokenMs).isEqualTo(0)

        val nonPositive = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(10, 10, 20, timing = timing(0, -5, 100))),
        ))
        assertThat(nonPositive.timeToFirstTokenMs).isNull()
        assertThat(nonPositive.endToEndMs).isNull()
    }

    @Test
    fun `其它时间下限为 0 且工具耗时未知时不给出`() {
        val overlap = summarizeGenerationStats(
            listOf(assistant("a", TokenUsage(10, 10, 20, timing = timing(1_000, 1_100, 2_000)))),
            toolDurationsMs = listOf(5_000),
        )
        assertThat(overlap.runtime).isEqualTo(GenerationRuntimeBreakdown(1_000, 5_000, 0))

        val unknownTool = summarizeGenerationStats(
            listOf(assistant("a", TokenUsage(10, 10, 20, timing = timing(1_000, 1_100, 2_000)))),
            toolDurationsMs = listOf(null),
        )
        assertThat(unknownTool.runtime).isEqualTo(GenerationRuntimeBreakdown(1_000, null, null))
    }

    @Test
    fun `缓存写入计入分母且摘要并入的输入不计入命中率`() {
        val summary = summarizeGenerationStats(listOf(
            assistant("a", TokenUsage(10_000, 100, 10_100, cacheReadInput = 0, cacheWriteInput = 9_000)),
            assistant("b", TokenUsage(10_500, 100, 10_600, summaryInput = 500, cacheReadInput = 9_000, cacheWriteInput = 400)),
        ))

        val cache = summary.cache!!
        assertThat(cache.reportedInputTokens).isEqualTo(20_000)
        assertThat(cache.readTokens).isEqualTo(9_000)
        assertThat(cache.writeTokens).isEqualTo(9_400)
        assertThat(cache.uncachedTokens).isEqualTo(1_600)
        // 两轮先求和再相除，不平均各轮百分比。
        assertThat(cache.hitRate).isWithin(1e-9).of(0.45)
    }

    @Test
    fun `命中率按本地化百分比保留一位小数`() {
        assertThat(com.promenar.infinity.ui.common.formatCacheHitRate(0.8504, Locale.US)).isEqualTo("85.0%")
        assertThat(com.promenar.infinity.ui.common.formatCacheHitRate(0.0, Locale.US)).isEqualTo("0.0%")
    }

    @Test
    fun `数字与耗时按本地化人性化显示`() {
        assertThat(formatStatsCount(1_234_567, Locale.US)).isEqualTo("1,234,567")
        assertThat(formatStatsRate(42.26, Locale.US)).isEqualTo("42.3")
        assertThat(formatStatsDuration(850, Locale.US)).isEqualTo(GenerationStatsText(R.string.generation_stats_duration_ms, listOf("850")))
        assertThat(formatStatsDuration(2_460, Locale.US)).isEqualTo(GenerationStatsText(R.string.generation_stats_duration_seconds, listOf("2.5")))
        assertThat(formatStatsDuration(125_000, Locale.US)).isEqualTo(GenerationStatsText(R.string.generation_stats_duration_minutes, listOf(2, 5)))
        assertThat(formatStatsDuration(-3, Locale.US)).isEqualTo(GenerationStatsText(R.string.generation_stats_duration_ms, listOf("0")))
    }

    @Test
    fun `工具调用按调用 ID 合并调用与结果计数`() {
        val messages = listOf(
            assistant(
                "a",
                TokenUsage(1_000, 100, 1_100, timing = timing(10_000, 10_400, 11_400)),
                toolSteps("1", 11_500, 12_000) + toolSteps("2", 12_000, 13_500),
            ),
            Message("t", MessageRole.TOOL, "结果"),
            assistant("b", TokenUsage(1_300, 300, 1_600, estimated = true, timing = timing(14_000, 14_600, 16_600)), toolSteps("3", 16_700, 16_800)),
            assistant("c", TokenUsage(200, 50, 250, timing = timing(17_000, 17_100, 18_000))),
        )

        assertThat(summarizeGenerationStats(messages).toolCalls).isEqualTo(3)
    }
}
