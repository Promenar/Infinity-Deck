package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationRetry
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 会话流里的状态行：同一格式“[动画] 名称 m:ss … [标签]”，单行固定高度。
// 自上而下：等待模型 0:12、等待模型 0:48（超过 30 秒仍保持中性计时）、重试退避、重试后重新连接、重试后持续等待（保持重试标签）、限流退避、准备工具调用、等待审批、等待回答。
// 全部状态保持中性色调，停止操作统一由输入框既有停止按钮承载。
@PreviewTest
@Preview(name = "状态行 深中411", widthDp = 411, heightDp = 400, locale = "zh-rCN")
@Composable
fun chatLiveStatusRowsDarkPreview() = LiveStatusRows(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "状态行 浅英360 大字号", widthDp = 360, heightDp = 480, locale = "en", fontScale = 1.3f)
@Composable
fun chatLiveStatusRowsLightPreview() = LiveStatusRows(InfinityThemeMode.LIGHT)

private const val NOW = 1_800_000_000_000L

private fun retry(attempt: Int, code: GenerationFailureCode, delayMs: Long, scheduledAgoMs: Long) =
    LiveRetry(GenerationRetry(attempt, 10, code, delayMs), scheduledAt = NOW - scheduledAgoMs)

@Composable
private fun LiveStatusRows(mode: InfinityThemeMode) {
    val waiting = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, startedAt = NOW - 12_000)
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLiveStepNow provides NOW) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LiveWaitingRow(waiting)
                    LiveWaitingRow(waiting.copy(startedAt = NOW - 48_000))
                    LiveWaitingRow(waiting.copy(startedAt = NOW - 96_000, retry = retry(3, GenerationFailureCode.NETWORK, 4_000, 500)))
                    LiveWaitingRow(waiting.copy(startedAt = NOW - 96_000, retry = retry(3, GenerationFailureCode.NETWORK, 4_000, 7_000)))
                    LiveWaitingRow(waiting.copy(startedAt = NOW - 96_000, retry = retry(7, GenerationFailureCode.SERVER, 4_000, 35_000)))
                    LiveWaitingRow(waiting.copy(startedAt = NOW - 96_000, retry = retry(2, GenerationFailureCode.RATE_LIMIT, 20_000, 2_000)))
                    LiveWaitingRow(
                        waiting.copy(kind = LiveStepKind.PREPARING_TOOL, motionPhase = GenerationMotionPhase.STREAM, startedAt = NOW - 3_000),
                    )
                    LiveWaitBadge(LiveStep(LiveStepKind.APPROVAL, GenerationMotionPhase.APPROVAL, startedAt = NOW - 23_000))
                    LiveWaitBadge(LiveStep(LiveStepKind.QUESTION, GenerationMotionPhase.APPROVAL, startedAt = NOW - 65_000))
                }
            }
        }
    }
}
