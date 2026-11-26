package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * v0.79.3-beta 真机验收补完 — 高保真隔离 Compose Preview 原型（仅 debug 源集，不修改正式代码）。
 * 覆盖三项用户可见 UI 变更：
 *  1. 窄屏/外屏 InfinityModalSheet(Large) 93% 弹窗底边锚定修复（消除顶对齐与底部 183px 输入框外露）
 *  2. 聊天正文 / 产物面板 / 文件管理器 音频文件统一内联播放卡片（含宽窄屏与大字号适配）
 *  3. 语音/音乐生成工具专属状态行与展开徽章（含克制风格前置矢量微动效预览参考）
 */

// =====================================================================
// 原型 1：窄屏/外屏 InfinityModalSheet(Large) 93% 底边锚定修复对照
// =====================================================================

@Composable
private fun CoverScreenSheetMock(anchoredToBottom: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val sheetBg = infinitySheetContainerColor()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.background,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 底层聊天页示意：顶部状态栏 + 会话栏 + 底部输入框
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("09:55", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text("Codex CLI", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                    Text("5G 100%", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("DeepSeek V4.1 Flash", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        PrototypeChip("底层输入框", PrototypeChipTone.ERROR)
                    }
                }
            }

            // 暗幕
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))

            // 93% 弹窗本体：Before 顶对齐漏底 vs After 严格贴底留顶部 7% 暗幕
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = sheetBg,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(if (anchoredToBottom) Alignment.BottomCenter else Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.90f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(34.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(scheme.outlineVariant),
                        )
                    }
                    Text(
                        text = "会话设置",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("模型", style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                        Text("参数", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text("工具", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text("上下文", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            Text("搜索模型…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.primaryContainer.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("DeepSeek V4.1 Flash 1017", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer)
                                Text("1M 上下文 · 推理 · 视觉", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.primary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PrototypeChip(
                            text = if (anchoredToBottom) "底边贴齐 · 顶部 7% 暗幕层次" else "顶对齐 · 底部漏出 183px 输入框",
                            tone = if (anchoredToBottom) PrototypeChipTone.SUCCESS else PrototypeChipTone.ERROR,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetFixBoard(dark: Boolean) {
    PrototypeBoardSurface(dark = dark) {
        PrototypeBoardHeader(
            batchBadge = "原型 1 · P0",
            title = "窄屏 / 折叠屏外屏 93% 底部面板底边锚定修复",
            subtitle = "InfinityBottomSheet.kt · 高度约束移入内容槽，不再截断 ModalBottomSheet.draggableAnchors 全屏高度",
        )
        ComparisonBlock(
            code = "FIX-1",
            title = "InfinityModalSheet(Large) 在外屏 412dp 窗口定位",
            sourceRef = "InfinityBottomSheet.kt:135-178",
            beforeSummary = "fillMaxHeight(0.93f) 传给 ModalBottomSheet 外层，Expanded 锚点算成 0px → 面板顶对齐，底部漏出 183px 暗幕与聊天输入框",
            afterSummary = "外层保持全屏高度供 draggableAnchors 计算，93% 高度（扣减导航栏与拖动条）落在内容列 → 面板严丝合缝贴底，顶部留 7% 暗幕",
            beforeContent = { CoverScreenSheetMock(anchoredToBottom = false) },
            afterContent = { CoverScreenSheetMock(anchoredToBottom = true) },
        )
    }
}

@Preview(name = "P1 外屏93%底栏修复 浅色", widthDp = 820, heightDp = 760, locale = "zh-rCN")
@Composable
private fun V0793BottomSheetCoverFixLightPreview() = BottomSheetFixBoard(dark = false)

@Preview(name = "P1 外屏93%底栏修复 深色", widthDp = 820, heightDp = 760, locale = "zh-rCN")
@Composable
private fun V0793BottomSheetCoverFixDarkPreview() = BottomSheetFixBoard(dark = true)

// =====================================================================
// 原型 2：音频文件统一内联播放卡片（正文 / 产物面板 / 文件管理器复用）
// =====================================================================

@Composable
private fun LegacyNonPlayableAudioFileCard(
    title: String,
    metaSegments: List<String>,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AudioFile, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = InfinityTypography.bodyMedium, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(segments = metaSegments, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = {}) { Icon(Icons.Rounded.OpenInNew, contentDescription = "用其它应用打开", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Rounded.SaveAlt, contentDescription = "保存到设备", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Rounded.Share, contentDescription = "分享", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun ProposedInlineAudioPlayerCard(
    title: String,
    metaSegments: List<String>,
    playing: Boolean,
    progress: Float,
    showActions: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (playing) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints {
            val compactActions = maxWidth < 340.dp * fontScale
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = {},
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (playing) scheme.primary else scheme.primaryContainer,
                            contentColor = if (playing) scheme.onPrimary else scheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        InfinityMetaLine(
                            segments = metaSegments,
                            style = InfinityTypography.labelSmall,
                            color = if (playing) scheme.primary else scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (showActions && !compactActions) {
                        AudioTrailingActionRow()
                    }
                }
                Slider(
                    value = progress,
                    onValueChange = {},
                    colors = SliderDefaults.colors(
                        thumbColor = scheme.primary,
                        activeTrackColor = scheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 2.dp),
                )
                if (showActions && compactActions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AudioTrailingActionRow()
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTrailingActionRow() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = "保存到设备", tint = tint, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = "分享", tint = tint, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = "用其它应用打开", tint = tint, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun InlineAudioCardsBoard(dark: Boolean) {
    PrototypeBoardSurface(dark = dark) {
        PrototypeBoardHeader(
            batchBadge = "原型 2 · P0",
            title = "生成 TTS / 音乐与音频文件统一直接内联播放",
            subtitle = "WorkspaceMediaCards.kt + FileArtifactViewer.kt + FileManagerPreview.kt · 三端共用同一播放器组件",
        )
        ComparisonBlock(
            code = "FIX-2",
            title = "聊天正文音频链接卡片（[说明](media/...wav)）",
            sourceRef = "WorkspaceMediaCards.kt:184-209 & 412-534",
            beforeSummary = "asLink=true 强行路由到 WorkspaceFileCard，无播放键与进度条，只能外部打开/保存/分享",
            afterSummary = "音频类链接直出升级版 WorkspaceAudioCard：圆形播放/暂停键 + 展示标题 + 格式/大小/实时进度 + 保存/分享/外部打开，播放切换零高度跳变",
            beforeContent = {
                LegacyNonPlayableAudioFileCard(
                    title = "Gemini 3.8 Flash Lite TTS 配音",
                    metaSegments = listOf("WAV", "68.9 KB"),
                )
                LegacyNonPlayableAudioFileCard(
                    title = "雨夜古筝配乐（Lyria 3.5）",
                    metaSegments = listOf("MP3", "2.4 MB"),
                )
            },
            afterContent = {
                ProposedInlineAudioPlayerCard(
                    title = "Gemini 3.8 Flash Lite TTS 配音",
                    metaSegments = listOf("WAV", "68.9 KB", "00:01"),
                    playing = false,
                    progress = 0f,
                )
                ProposedInlineAudioPlayerCard(
                    title = "雨夜古筝配乐（Lyria 3.5）",
                    metaSegments = listOf("MP3", "2.4 MB", "00:14 / 00:30"),
                    playing = true,
                    progress = 0.47f,
                )
            },
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrototypeChip("产物面板 + 文件管理器", PrototypeChipTone.INFO)
                    Text(
                        "同步补齐产物查看与工作区文件预览的直接播放（不再弹出“无法预览，请用其它应用打开”）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProposedInlineAudioPlayerCard(
                    title = "gen-0932-b13b30.wav",
                    metaSegments = listOf("WAV", "68.9 KB", "00:01 / 00:01"),
                    playing = true,
                    progress = 0.85f,
                )
            }
        }
    }
}

@Preview(name = "P2 音频内联播放卡片 浅色", widthDp = 820, heightDp = 760, locale = "zh-rCN")
@Composable
private fun V0793InlineAudioLightPreview() = InlineAudioCardsBoard(dark = false)

@Preview(name = "P2 音频内联播放卡片 深色", widthDp = 820, heightDp = 760, locale = "zh-rCN")
@Composable
private fun V0793InlineAudioDarkPreview() = InlineAudioCardsBoard(dark = true)

@Preview(name = "P2 音频内联卡片 窄屏大字 360dp 1.3x", widthDp = 360, heightDp = 540, locale = "zh-rCN", fontScale = 1.3f)
@Composable
private fun V0793InlineAudioNarrowLargeFontPreview() {
    PrototypeBoardSurface(dark = false, fontScale = 1.3f) {
        PrototypeChip("360dp · 1.3x 窄屏大字自适应", PrototypeChipTone.INFO)
        ProposedInlineAudioPlayerCard(
            title = "Gemini 3.8 Flash Lite TTS 配音试听版",
            metaSegments = listOf("WAV", "68.9 KB", "00:01"),
            playing = false,
            progress = 0f,
        )
        ProposedInlineAudioPlayerCard(
            title = "雨夜古筝与弦乐主题配乐（Lyria 3.5）",
            metaSegments = listOf("MP3", "2.4 MB", "00:14 / 00:30"),
            playing = true,
            progress = 0.47f,
        )
    }
}

// =====================================================================
// 原型 3：媒体生成工具专属状态行、展开徽章与克制版前置图标参考
// =====================================================================

@Composable
private fun MediaToolStatusRowSample(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    elapsed: String?,
    badgeText: String,
    badgeTone: PrototypeChipTone,
    isRunning: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = if (isRunning) 0.16f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRunning) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) {
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            PrototypeChip(badgeText, badgeTone)
        }
    }
}

@Composable
private fun MediaToolStatusBoard(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    PrototypeBoardSurface(dark = dark) {
        PrototypeBoardHeader(
            batchBadge = "原型 3 · 分类 + 动效方向",
            title = "生图 / 语音 / 音乐专属工具分类与克制风格前置图标参考",
            subtitle = "ToolInvocationPresentation.kt + PipelineBubble.kt + ToolStepSummary.kt",
        )
        ComparisonBlock(
            code = "FIX-3",
            title = "语音与音乐工具不再回退「其它工具 / 正在运行工具…」",
            sourceRef = "ToolInvocationPresentation.kt:190-217",
            beforeSummary = "generate_speech 与 generate_music 未映射，运行中笼统显示「正在运行工具…」，展开详情徽章显示「其它工具」",
            afterSummary = "补齐 SPEECH / MUSIC 专属分类、运行中文案、完成计数与展开徽章；图示克制版前置小图标风格（保留轻量化，无重型占位卡）",
            beforeContent = {
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = scheme.onSurfaceVariant,
                    title = "正在运行工具…",
                    elapsed = "0:06",
                    badgeText = "其它工具",
                    badgeTone = PrototypeChipTone.NEUTRAL,
                    isRunning = true,
                )
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = scheme.onSurfaceVariant,
                    title = "已运行 2 个工具 · 11.4 秒",
                    elapsed = null,
                    badgeText = "其它工具",
                    badgeTone = PrototypeChipTone.NEUTRAL,
                    isRunning = false,
                )
            },
            afterContent = {
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = scheme.primary,
                    title = "正在生成图片…",
                    elapsed = "0:08",
                    badgeText = "生成图片",
                    badgeTone = PrototypeChipTone.INFO,
                    isRunning = true,
                )
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.GraphicEq,
                    iconTint = domain.info,
                    title = "正在合成语音…",
                    elapsed = "0:04",
                    badgeText = "合成语音",
                    badgeTone = PrototypeChipTone.INFO,
                    isRunning = true,
                )
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.LibraryMusic,
                    iconTint = domain.success,
                    title = "正在生成音乐…",
                    elapsed = "0:12",
                    badgeText = "生成音乐",
                    badgeTone = PrototypeChipTone.SUCCESS,
                    isRunning = true,
                )
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                MediaToolStatusRowSample(
                    icon = Icons.Rounded.GraphicEq,
                    iconTint = scheme.onSurfaceVariant,
                    title = "合成了 1 段语音、生成了 1 段音乐 · 16.2 秒",
                    elapsed = null,
                    badgeText = "已完成",
                    badgeTone = PrototypeChipTone.NEUTRAL,
                    isRunning = false,
                )
            },
        )
    }
}

@Preview(name = "P3 媒体工具分类与克制图标 浅色", widthDp = 820, heightDp = 620, locale = "zh-rCN")
@Composable
private fun V0793MediaToolStatusLightPreview() = MediaToolStatusBoard(dark = false)

@Preview(name = "P3 媒体工具分类与克制图标 深色", widthDp = 820, heightDp = 620, locale = "zh-rCN")
@Composable
private fun V0793MediaToolStatusDarkPreview() = MediaToolStatusBoard(dark = true)
