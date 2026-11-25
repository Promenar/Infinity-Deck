package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
 * 上下文压缩轻量指示器高保真隔离原型 v2（待用户确认后接入 ChatScreen.kt / ChatInlineComponents.kt）：
 * 1. 所有状态（进行中 / 已完成 / 无需压缩 / 失败）统一复用同一行轻量内联指示器，无卡片框、无底板堆积、无摘要正文展开；
 * 2. 进行中：[LiveStepDots] + 正在压缩上下文… + m:ss 中性计时；
 * 3. 已完成：原地切换为 [CheckCircleOutline] + 上下文已压缩（附简短用量变化，如 48.2k → 11.4k）+ 轻量关闭按钮；
 * 4. 无需压缩：原地切换为 [Info] + 当前记录较短，暂无需压缩 + 轻量关闭按钮；
 * 5. 失败：原地切换为 [ErrorOutline] + 上下文压缩失败 + 具体原因（如 请求超时，完整记录已保留）+ 紧凑「重试」胶囊按钮 + 关闭按钮。
 */

private sealed interface CompactionIndicatorProtoState {
    data class Running(val elapsedSeconds: Long) : CompactionIndicatorProtoState
    data class Success(val message: String) : CompactionIndicatorProtoState
    data class NoOp(val message: String) : CompactionIndicatorProtoState
    data class Failed(val title: String, val reason: String) : CompactionIndicatorProtoState
}

@Composable
private fun CompactCompactionIndicatorProtoV2(
    state: CompactionIndicatorProtoState,
    english: Boolean,
    modifier: Modifier = Modifier,
) {
    // 统一单行无卡片轻量轨道，原地平滑切换状态，不造成布局高度跳变
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is CompactionIndicatorProtoState.Running -> {
                LiveStepDots(
                    phase = GenerationMotionPhase.WAIT,
                    dotSize = 4.dp,
                )
                Text(
                    text = if (english) "Compacting context…" else "正在压缩上下文…",
                    style = InfinityTypography.bodySmall.copy(
                        fontSize = 13.sp,
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
                        fontSize = 12.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }

            is CompactionIndicatorProtoState.Success -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = state.message,
                    style = InfinityTypography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = if (english) "Dismiss" else "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            is CompactionIndicatorProtoState.NoOp -> {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = state.message,
                    style = InfinityTypography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = if (english) "Dismiss" else "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            is CompactionIndicatorProtoState.Failed -> {
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
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.reason,
                        style = InfinityTypography.labelSmall.copy(fontSize = 12.sp),
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
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = if (english) "Dismiss" else "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextCompactionIndicatorShowcase(english: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (english) "Context Compaction Indicator v2" else "上下文压缩指示器 · 极简原地切换原型 v2",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = if (english) {
                    listOf("Zero card box", "In-place state morph", "No summary output")
                } else {
                    listOf("全状态无卡片框", "原地平滑切为成功/失败", "不展示摘要成果")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 模拟真实聊天气泡底部上下文语境
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

        SectionLabel(if (english) "1. In progress" else "1. 压缩进行中")
        CompactCompactionIndicatorProtoV2(
            state = CompactionIndicatorProtoState.Running(elapsedSeconds = 8L),
            english = english,
        )

        SectionLabel(if (english) "2. Completed (in-place morph, no card)" else "2. 压缩成功（原地转为单行消息，无卡片/无成果展开）")
        CompactCompactionIndicatorProtoV2(
            state = CompactionIndicatorProtoState.Success(
                message = if (english) {
                    "Context compacted · 48.2k → 11.4k"
                } else {
                    "上下文已压缩 · 48.2k → 11.4k"
                },
            ),
            english = english,
        )

        SectionLabel(if (english) "3. Nothing to compact" else "3. 无需压缩")
        CompactCompactionIndicatorProtoV2(
            state = CompactionIndicatorProtoState.NoOp(
                message = if (english) {
                    "Recent 4 turns kept; nothing earlier to compact"
                } else {
                    "仅保留最近 4 轮对话，暂无需压缩"
                },
            ),
            english = english,
        )

        SectionLabel(if (english) "4. Failed (in-place error + Retry)" else "4. 压缩失败（原地报错 + 原因 + 重试）")
        CompactCompactionIndicatorProtoV2(
            state = CompactionIndicatorProtoState.Failed(
                title = if (english) "Compaction failed" else "上下文压缩失败",
                reason = if (english) {
                    "Request timed out · full history kept"
                } else {
                    "请求超时，完整记录已保留"
                },
            ),
            english = english,
        )

        Spacer(Modifier.size(8.dp))
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

@Preview(name = "深色 · 外屏 360dp", widthDp = 360, heightDp = 640)
@Composable
fun ContextCompactionIndicatorProtoDarkPhone() {
    ProtoTheme(dark = true) { ContextCompactionIndicatorShowcase(english = false) }
}

@Preview(name = "浅色 · 标准 411dp", widthDp = 411, heightDp = 640)
@Composable
fun ContextCompactionIndicatorProtoLightPhone() {
    ProtoTheme(dark = false) { ContextCompactionIndicatorShowcase(english = false) }
}

@Preview(name = "深色 · 大字号 1.3x · 360dp", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
fun ContextCompactionIndicatorProtoLargeFont() {
    ProtoTheme(dark = true) { ContextCompactionIndicatorShowcase(english = false) }
}

@Preview(name = "英文 · 展开内屏 680dp", widthDp = 680, heightDp = 600)
@Composable
fun ContextCompactionIndicatorProtoEnglishWide() {
    ProtoTheme(dark = true) { ContextCompactionIndicatorShowcase(english = true) }
}

@Preview(name = "正式实现 1:1 对照 · 深色 360dp", widthDp = 360, heightDp = 640)
@Composable
fun ContextCompactionFeedbackProductionDarkPhone() {
    ProtoTheme(dark = true) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLiveStepNow provides 1_800_000_008_000L) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionLabel("1. 正式组件：进行中")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        isCompressing = true,
                        detail = "正在压缩上下文…",
                        startedAtMs = 1_800_000_000_000L,
                    ),
                    onDismiss = {},
                )
                SectionLabel("2. 正式组件：压缩完成")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        progress = 1f,
                        result = "48.2k → 11.4k",
                    ),
                    onDismiss = {},
                )
                SectionLabel("3. 正式组件：无需压缩")
                ContextCompactionFeedback(
                    state = ChatViewModel.CompressionState(
                        progress = 1f,
                        detail = "仅保留最近 4 轮对话，暂无需压缩",
                        isNoOp = true,
                    ),
                    onDismiss = {},
                )
                SectionLabel("4. 正式组件：压缩失败（带真实原因 + 重试）")
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
