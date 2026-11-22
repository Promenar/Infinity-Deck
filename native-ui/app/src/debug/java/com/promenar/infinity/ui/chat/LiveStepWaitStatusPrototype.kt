package com.promenar.infinity.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 会话流长等待状态行高保真隔离原型（待用户确认后进入 Phase B 正式改造 LiveStep.kt）：
 * 1. 移除 30 秒虚假“无响应”警示徽章与行尾冗余“停止”按钮（停止统一由输入框既有停止按钮承载）；
 * 2. WAITING_MODEL 与 PREPARING_TOOL 超过 30 秒仍保持平静单行 `[动画] 状态文案 m:ss` 中性计时；
 * 3. 保留真实网络/限流/服务端异常退避与重连期间的中性 `LiveStatusBadge.Retry` 状态，以及审批/回答等待行。
 */

private const val PROTOTYPE_NOW_MS = 1_800_000_000_000L

@Composable
private fun LiveStepWaitStatusPrototypeContent(english: Boolean) {
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (english) "Stream Wait & Retry States" else "实时步骤：长等待平静计时与真实异常重试",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = if (english) {
                    listOf("No false 30s stall badge", "No duplicate Stop pill", "Real Retry kept")
                } else {
                    listOf("移除30秒无响应误报", "移除行尾重复停止", "保留真实异常重试")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, cardShape),
        ) {
            CompositionLocalProvider(LocalLiveStepNow provides PROTOTYPE_NOW_MS) {
                Column(
                    modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (english) "Calm long-wait states (>30s remains neutral)" else "1. 常规等待与长等待（超 30s 仍保持中性计时）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // 1. 等待模型 0:12
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = R.string.live_step_waiting_model,
                            countdown = null,
                            clockSeconds = 12L,
                            badge = null,
                            motionPhase = GenerationMotionPhase.WAIT,
                        ),
                    )
                    // 2. 等待模型 1:15（高推理/慢响应超过原 30s 阈值）
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = R.string.live_step_waiting_model,
                            countdown = null,
                            clockSeconds = 75L,
                            badge = null,
                            motionPhase = GenerationMotionPhase.WAIT,
                        ),
                    )
                    // 3. 正在准备工具调用 0:04
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = R.string.live_step_preparing_tool,
                            countdown = null,
                            clockSeconds = 4L,
                            badge = null,
                            motionPhase = GenerationMotionPhase.STREAM,
                        ),
                    )
                    // 4. 正在准备工具调用 0:48（大工具参数流式输出超过原 30s 阈值）
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = R.string.live_step_preparing_tool,
                            countdown = null,
                            clockSeconds = 48L,
                            badge = null,
                            motionPhase = GenerationMotionPhase.STREAM,
                        ),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )

                    Text(
                        text = if (english) "Real transport retries & interactive waits" else "2. 真实异常自动重试与用户交互等待（均保留）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 5. 网络波动退避
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = null,
                            countdown = LiveRetryCountdown(R.string.chat_retry_reason_network, 3),
                            clockSeconds = null,
                            badge = LiveStatusBadge.Retry(3, 10),
                            motionPhase = GenerationMotionPhase.RETRY,
                        ),
                    )
                    // 6. 重试后重新连接等待
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = R.string.live_step_waiting_model,
                            countdown = null,
                            clockSeconds = 42L,
                            badge = LiveStatusBadge.Retry(3, 10),
                            motionPhase = GenerationMotionPhase.WAIT,
                        ),
                    )
                    // 7. 限流退避
                    LiveStatusRow(
                        line = LiveStatusLine(
                            labelRes = null,
                            countdown = LiveRetryCountdown(R.string.chat_retry_reason_rate_limit, 18),
                            clockSeconds = null,
                            badge = LiveStatusBadge.Retry(2, 10),
                            motionPhase = GenerationMotionPhase.RETRY,
                        ),
                    )
                    // 8. 等待审批 & 等待回答
                    LiveWaitBadge(
                        LiveStep(
                            LiveStepKind.APPROVAL,
                            GenerationMotionPhase.APPROVAL,
                            startedAt = PROTOTYPE_NOW_MS - 23_000,
                        ),
                    )
                    LiveWaitBadge(
                        LiveStep(
                            LiveStepKind.QUESTION,
                            GenerationMotionPhase.APPROVAL,
                            startedAt = PROTOTYPE_NOW_MS - 65_000,
                        ),
                    )
                }
            }
        }
    }
}

@Preview(name = "LiveStep 长等待状态行 深色中文", widthDp = 411, heightDp = 510, locale = "zh-rCN")
@Composable
fun liveStepWaitStatusDarkZhPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LiveStepWaitStatusPrototypeContent(english = false)
        }
    }
}

@Preview(name = "LiveStep Wait Status Light EN 1.3x", widthDp = 360, heightDp = 580, locale = "en", fontScale = 1.3f)
@Composable
fun liveStepWaitStatusLightEnLargePreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LiveStepWaitStatusPrototypeContent(english = true)
        }
    }
}
