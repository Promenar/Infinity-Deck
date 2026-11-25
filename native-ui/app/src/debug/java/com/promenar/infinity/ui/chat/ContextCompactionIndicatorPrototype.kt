package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading

/*
 * 上下文压缩时间线锚点高保真隔离原型 v3（居中极简分割线，供用户确认后接入正式实现）：
 * 1. 锚定在消息流中触发压缩的精确时间线位置（位于被压缩的旧消息下方、后续新消息上方），不悬浮、不随新消息下漂；
 * 2. 压缩进行中：居中细分割线 + [LiveStepDots] + 正在压缩上下文… + m:ss 计时；
 * 3. 压缩成功：原地淡换为永久时间线分割线（─── ✓ 上下文已压缩 · 155.1k → 76.9k ───），无关闭按钮、零高度跳变；
 * 4. 无需压缩：居中极简分割线（─── 仅保留最近 4 轮对话，暂无需压缩 ───）+ 轻量关闭按钮；
 * 5. 压缩失败：轻量内联错误行 + 具体原因 + 紧凑「重试」胶囊按钮 + 关闭按钮，确保 360dp / 1.3x 零裁切；
 * 6. 彻底移除浮岛上方多余的 [记忆] / [摘要] PostProcessChip 浮标。
 */

private sealed interface CompactionTimelineProtoState {
    data class Running(val elapsedSeconds: Long) : CompactionTimelineProtoState
    data class Anchored(val title: String, val delta: String) : CompactionTimelineProtoState
    data class NoOp(val message: String) : CompactionTimelineProtoState
    data class Failed(val title: String, val reason: String) : CompactionTimelineProtoState
}

@Composable
private fun CompactionTimelineProtoV3(
    state: CompactionTimelineProtoState,
    english: Boolean,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is CompactionTimelineProtoState.Running -> {
            CompactionTimelineDividerFrame(modifier = modifier) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LiveStepDots(
                        phase = GenerationMotionPhase.WAIT,
                        dotSize = 3.5.dp,
                    )
                    Text(
                        text = if (english) "Compacting context…" else "正在压缩上下文…",
                        style = InfinityTypography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = liveClockText(state.elapsedSeconds),
                        style = InfinityTypography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
            }
        }

        is CompactionTimelineProtoState.Anchored -> {
            CompactionTimelineDividerFrame(modifier = modifier) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier.size(13.dp),
                    )
                    InfinityMetaLine(
                        segments = listOf(state.title, state.delta),
                        style = InfinityTypography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                        maxLines = 1,
                    )
                }
            }
        }

        is CompactionTimelineProtoState.NoOp -> {
            CompactionTimelineDividerFrame(
                modifier = modifier,
                trailing = {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = if (english) "Dismiss" else "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
            ) {
                Text(
                    text = state.message,
                    style = InfinityTypography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is CompactionTimelineProtoState.Failed -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = state.title,
                        style = InfinityTypography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.reason,
                        style = InfinityTypography.labelSmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InfinityPillButton(
                    text = if (english) "Retry" else "重试",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.Refresh,
                    compact = true,
                )
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = if (english) "Dismiss" else "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextCompactionTimelineShowcase(english: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (english) "Compaction Timeline Anchor v3" else "上下文压缩时间线锚点 · 居中极简分割线原型 v3",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = if (english) {
                    listOf("Anchored in timeline", "Centered hairline", "Zero floating chip")
                } else {
                    listOf("锚定触发位置不漂移", "居中极简细分割线", "移除底部记忆浮标")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 真实会话流上下文：旧消息 → 居中压缩分割线锚点 → 后续新消息
        SectionLabel(if (english) "In-stream placement (between old & new turns)" else "真实时间线位置（锚定在旧回复与新消息之间）")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (english) {
                    "Current versions in /mnt/skills match the repository archive byte-for-byte."
                } else {
                    "当前版本均在 /mnt/skills 正常存在，且与仓库归档逐字节一致，功能不受影响。"
                },
                style = InfinityTypography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (english) "Saved to memory · DeepSeek V4.1 Flash · 10:05" else "已记入记忆 · DeepSeek V4.1 Flash · 10:05",
                style = InfinityTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CompactionTimelineProtoV3(
            state = CompactionTimelineProtoState.Anchored(
                title = if (english) "Context compacted" else "上下文已压缩",
                delta = "155.1k → 76.9k",
            ),
            english = english,
        )

        // 模拟后续发出的新用户气泡（验证分割线留在原位，不随新消息下漂）
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (english) {
                        "Can you retrieve the compacted history via tools?"
                    } else {
                        "很好，你能查到被压缩掉的历史会话吗？"
                    },
                    style = InfinityTypography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel(if (english) "1. In progress (same centered track, zero height jump)" else "1. 压缩进行中（同居中细线轨道，完成后原地等高淡换）")
        CompactionTimelineProtoV3(
            state = CompactionTimelineProtoState.Running(elapsedSeconds = 8L),
            english = english,
        )

        SectionLabel(if (english) "2. Nothing to compact (dismissible)" else "2. 无需压缩（可关闭）")
        CompactionTimelineProtoV3(
            state = CompactionTimelineProtoState.NoOp(
                message = if (english) {
                    "Recent 4 turns kept; nothing earlier to compact"
                } else {
                    "仅保留最近 4 轮对话，暂无需压缩"
                },
            ),
            english = english,
        )

        SectionLabel(if (english) "3. Failed (reason + Retry pill + dismiss)" else "3. 压缩失败（具体原因 + 重试按钮 + 关闭）")
        CompactionTimelineProtoV3(
            state = CompactionTimelineProtoState.Failed(
                title = if (english) "Compaction failed" else "上下文压缩失败",
                reason = if (english) {
                    "Request timed out · full history kept"
                } else {
                    "请求超时，完整记录已保留"
                },
            ),
            english = english,
        )

        Spacer(Modifier.height(4.dp))
        // 纯净浮岛（上方不再有 [记忆] 浮标）
        val composerShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(composerShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), composerShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = if (english) "Message Infinity" else "给 Infinity 发消息",
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
    )
}

@Composable
private fun ProtoTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

@Preview(name = "深色 · 外屏 360dp · v3", widthDp = 360, heightDp = 700)
@Composable
fun ContextCompactionIndicatorProtoDarkPhone() {
    ProtoTheme(dark = true) { ContextCompactionTimelineShowcase(english = false) }
}

@Preview(name = "浅色 · 标准 411dp · v3", widthDp = 411, heightDp = 700)
@Composable
fun ContextCompactionIndicatorProtoLightPhone() {
    ProtoTheme(dark = false) { ContextCompactionTimelineShowcase(english = false) }
}

@Preview(name = "深色 · 大字号 1.3x · 360dp · v3", widthDp = 360, heightDp = 820, fontScale = 1.3f)
@Composable
fun ContextCompactionIndicatorProtoLargeFont() {
    ProtoTheme(dark = true) { ContextCompactionTimelineShowcase(english = false) }
}

@Preview(name = "英文 · 展开内屏 680dp · v3", widthDp = 680, heightDp = 660)
@Composable
fun ContextCompactionIndicatorProtoEnglishWide() {
    ProtoTheme(dark = true) { ContextCompactionTimelineShowcase(english = true) }
}

@Preview(name = "正式组件核验 · 深色 360dp · v3", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun ContextCompactionProductionV3DarkPhone() {
    ProtoTheme(dark = true) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLiveStepNow provides 8_000L) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel("正式组件 · 时间线锚定分割线（永久，无关闭按钮）")
                ContextCompactionTimelineDivider(delta = "155.1k → 76.9k")

                SectionLabel("正式组件 · 进行中（ContextCompactionFeedback）")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        isCompressing = true,
                        detail = "正在压缩上下文…",
                        startedAtMs = 0L,
                    ),
                    onDismiss = {},
                )

                SectionLabel("正式组件 · 无需压缩（可关闭）")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        progress = 1f,
                        detail = "仅保留最近 4 轮对话，暂无需压缩",
                        isNoOp = true,
                    ),
                    onDismiss = {},
                )

                SectionLabel("正式组件 · 压缩失败（原因 + 重试 + 关闭）")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        detail = "请求超时，完整记录已保留",
                        failureReason = com.promenar.infinity.data.generation.ContextCompactionFailureReason.TIMEOUT,
                    ),
                    onDismiss = {},
                    onRetry = {},
                )
            }
        }
    }
}
