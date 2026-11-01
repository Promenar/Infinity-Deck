package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.GenerationTimingRecord
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 与浮卡一致：最宽 340dp，窄屏随宽度收缩。 */
private val PopupWidth = Modifier.widthIn(max = 340.dp).fillMaxWidth()

private const val T0 = 1_760_000_000_000L

private val SingleRound = listOf(
    Message(
        "single", MessageRole.ASSISTANT, "回答",
        tokens = TokenUsage(
            input = 48_213, output = 1_286, total = 49_499,
            cacheReadInput = 40_960, cacheWriteInput = 2_048, reasoningOutput = 412,
            timing = GenerationTimingRecord(T0, T0 + 820, T0 + 21_540),
        ),
    ),
)

private val MultiRound = listOf(
    Message(
        "round-1", MessageRole.ASSISTANT, "",
        tokens = TokenUsage(12_480, 96, 12_576, cacheReadInput = 8_192, timing = GenerationTimingRecord(T0, T0 + 1_240, T0 + 2_100)),
        executionSteps = listOf(
            ExecutionStep("call-1", "tool_call", "web_search", "{}", "call-1", timestamp = T0 + 2_200),
            ExecutionStep("res-1", "tool_result", "web_search", toolCallId = "call-1", content = "ok", timestamp = T0 + 5_600),
        ),
    ),
    Message("tool-1", MessageRole.TOOL, "搜索结果"),
    Message(
        "round-2", MessageRole.ASSISTANT, "",
        tokens = TokenUsage(15_902, 141, 16_043, timing = GenerationTimingRecord(T0 + 5_900, T0 + 6_700, T0 + 7_600)),
        executionSteps = listOf(
            ExecutionStep("call-2", "tool_call", "read_file", "{}", "call-2", timestamp = T0 + 7_700),
            ExecutionStep("res-2", "tool_result", "read_file", toolCallId = "call-2", content = "ok", timestamp = T0 + 8_150),
        ),
    ),
    Message(
        "round-3", MessageRole.ASSISTANT, "最终回答",
        tokens = TokenUsage(17_366, 1_034, 18_400, cacheReadInput = 15_872, timing = GenerationTimingRecord(T0 + 8_400, T0 + 9_300, T0 + 24_800)),
    ),
)

private val LegacyRound = listOf(Message("legacy", MessageRole.ASSISTANT, "旧回答", tokens = TokenUsage(3_210, 845, 4_055)))

private val EstimatedLocal = listOf(
    Message(
        "local", MessageRole.ASSISTANT, "本地回答",
        tokens = TokenUsage(1_024, 356, 1_380, estimated = true, timing = GenerationTimingRecord(T0, T0 + 2_350, T0 + 14_200)),
    ),
)

private fun card(name: String, provider: String?, messages: List<Message>) = GenerationStatsCardModel(
    modelName = name,
    providerName = provider,
    generatedAt = "2026-10-20 14:35:08",
    summary = summarizeGenerationStats(messages),
)

@Composable
private fun StatsFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "生成统计 单轮 缓存推理 分布展开 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun generationStatsSingleRoundDarkPreview() = StatsFrame(dark = true) {
    GenerationStatsCard(card("Claude Sonnet 4.5", "Anthropic", SingleRound), PopupWidth, initiallyExpandedBreakdown = true)
}

@PreviewTest
@Preview(name = "生成统计 多轮合计 分布收起 浅中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun generationStatsMultiRoundLightPreview() = StatsFrame(dark = false) {
    GenerationStatsCard(card("DeepSeek V3.2", "DeepSeek", MultiRound), PopupWidth)
}

@PreviewTest
@Preview(name = "Generation stats multi round English 360dp font130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun generationStatsMultiRoundEnglishLargeFontPreview() = StatsFrame(dark = true) {
    GenerationStatsCard(card("Gemini 3 Flash Preview", "Google Vertex AI", MultiRound), PopupWidth, initiallyExpandedBreakdown = true)
}

@PreviewTest
@Preview(name = "生成统计 旧消息缺计时与本地估算 浅中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun generationStatsLegacyAndEstimatedLightPreview() = StatsFrame(dark = false) {
    GenerationStatsCard(card("GPT-4o", "OpenAI", LegacyRound), PopupWidth)
    GenerationStatsCard(card("Qwen3 4B", null, EstimatedLocal), PopupWidth)
}

@PreviewTest
@Preview(name = "Generation stats legacy English 411dp dark", widthDp = 411, heightDp = 480, locale = "en")
@Composable
fun generationStatsLegacyEnglishDarkPreview() = StatsFrame(dark = true) {
    GenerationStatsCard(card("GPT-4o", "OpenAI", LegacyRound), PopupWidth)
}
