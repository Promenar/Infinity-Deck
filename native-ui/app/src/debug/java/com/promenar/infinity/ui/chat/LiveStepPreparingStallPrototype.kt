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
 * 工具准备态（PREPARING_TOOL）超停无响应与停止行隔离高保真原型（Wave 1）：
 * 1. 当 EXECUTING_TOOLS 阶段尚未落库任何 toolCalls 时，实时状态行从误导性的「等待模型响应」修正为 PREPARING_TOOL（「正在准备工具调用」）；
 * 2. PREPARING_TOOL 超过 30 秒阈值同样触发 LiveStatusBadge.Stalled（「无响应」）与即时「停止」胶囊按钮，复用现有 LiveStatusRow 单行布局且不增高。
 */

@Composable
private fun LiveStepStallShowcaseContent(english: Boolean) {
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (english) "LiveStep Tool-Prepare Stall States" else "实时步骤：工具准备态超停与停止入口",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = if (english) {
                    listOf("EXECUTING_TOOLS before toolCalls", "30s stall threshold", "LiveStatusRow")
                } else {
                    listOf("工具入库前阶段", "30 秒无响应升级", "复核单行与停止按钮")
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
            Column(
                modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Text(
                    text = if (english) "1. Active PREPARING_TOOL (< 30s)" else "1. 正常工具准备态（30 秒内）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiveStatusRow(
                    line = LiveStatusLine(
                        labelRes = R.string.live_step_preparing_tool,
                        countdown = null,
                        clockSeconds = 4L,
                        badge = null,
                        motionPhase = GenerationMotionPhase.STREAM,
                    ),
                    onStop = {},
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                Text(
                    text = if (english) "2. Stalled PREPARING_TOOL (>= 30s, new Stop affordance)" else "2. 超停工具准备态（新增无响应徽章与停止按钮）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LiveStatusRow(
                    line = LiveStatusLine(
                        labelRes = R.string.live_step_preparing_tool,
                        countdown = null,
                        clockSeconds = 35L,
                        badge = LiveStatusBadge.Stalled,
                        motionPhase = GenerationMotionPhase.STREAM,
                    ),
                    onStop = {},
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                Text(
                    text = if (english) "3. Baseline stalled WAITING_MODEL (>= 30s)" else "3. 基线对照：等待模型超时无响应",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiveStatusRow(
                    line = LiveStatusLine(
                        labelRes = R.string.live_step_waiting_model,
                        countdown = null,
                        clockSeconds = 35L,
                        badge = LiveStatusBadge.Stalled,
                        motionPhase = GenerationMotionPhase.WAIT,
                    ),
                    onStop = {},
                )
            }
        }
    }
}

@Preview(name = "1. LiveStep 工具准备态超时深色中文", widthDp = 360, heightDp = 320, locale = "zh-rCN")
@Composable
fun liveStepPreparingStallDarkPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            LiveStepStallShowcaseContent(english = false)
        }
    }
}

@Preview(name = "2. LiveStep Tool-Prepare Stall 浅英大字1.3x", widthDp = 360, heightDp = 340, locale = "en", fontScale = 1.3f)
@Composable
fun liveStepPreparingStallLightLargePreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            LiveStepStallShowcaseContent(english = true)
        }
    }
}
