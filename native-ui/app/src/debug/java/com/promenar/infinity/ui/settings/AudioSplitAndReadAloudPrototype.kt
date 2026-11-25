package com.promenar.infinity.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.ModelBrandTile
import com.promenar.infinity.ui.common.ModelCapabilityBadge
import com.promenar.infinity.ui.common.ModelCapabilityBadges
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 音频类型拆分（语音 TTS / 音乐 / 实时）与会话气泡“朗读”按钮高保真原型（v2，隔离于 debug 源码）：
 * 1. 会话助手气泡底部操作栏“朗读”全状态（空闲 / 合成中 / 朗读中）+ 全站统一顶部熏黑微光通知胶囊（InfinityNoticeHost）；
 * 2. Google AI Studio 模型列表行分类修复预览（语音 / 音乐 / 实时 + Google 品牌图标）；
 * 3. 「默认模型」与「媒体模型」目录页：音频拆分为独立“语音 (TTS)”与“音乐”分组；
 * 4. 「模型编辑」类型分段与「语音媒体设置」默认音色配置。
 */

private enum class ProtoReadAloudState { IDLE, LOADING, PLAYING }

@Composable
private fun ProtoAudioFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

@Composable
private fun ProtoAssistantReadAloudRow(
    state: ProtoReadAloudState,
    modelName: String,
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val metaStyle = InfinityTypography.labelSmall.copy(color = scheme.onSurfaceVariant)
    Box(modifier = modifier.fillMaxWidth().height(36.dp)) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .layout { measurable, constraints ->
                    val bleed = 17.5.dp.roundToPx()
                    val row = measurable.measure(
                        constraints.copy(minWidth = constraints.maxWidth + bleed, maxWidth = constraints.maxWidth + bleed),
                    )
                    layout(constraints.maxWidth, row.height) { row.placeRelative(-bleed, 0) }
                }
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ContentCopy, "复制", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                when (state) {
                    ProtoReadAloudState.IDLE -> Icon(
                        Icons.AutoMirrored.Rounded.VolumeUp,
                        "朗读",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    ProtoReadAloudState.LOADING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    ProtoReadAloudState.PLAYING -> Icon(
                        Icons.Rounded.StopCircle,
                        "停止朗读",
                        tint = scheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Refresh, "重新生成", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.MoreHoriz, "更多操作", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelName,
                    style = metaStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ", style = metaStyle)
                Text(text = timestamp, style = metaStyle, maxLines = 1)
            }
        }
    }
}

/** 对齐 InfinityNoticeHost 正式熏黑微光顶部悬浮通知胶囊像素规范（正式代码直接调用 LocalInfinityNoticeController.current?.show）。 */
@Composable
private fun ProtoObsidianTopNoticeCapsule(
    severity: InfinityNoticeSeverity,
    message: String,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = domain.isDark
    val accent = when (severity) {
        InfinityNoticeSeverity.SUCCESS -> domain.success
        InfinityNoticeSeverity.INFO -> scheme.primary
        InfinityNoticeSeverity.WARNING -> domain.warning
        InfinityNoticeSeverity.ERROR -> domain.ragError
    }
    val icon = when (severity) {
        InfinityNoticeSeverity.SUCCESS -> Icons.Rounded.CheckCircle
        InfinityNoticeSeverity.INFO -> Icons.Rounded.Info
        InfinityNoticeSeverity.WARNING -> Icons.Rounded.WarningAmber
        InfinityNoticeSeverity.ERROR -> Icons.Rounded.ErrorOutline
    }
    val containerColor = if (dark) InfinityObsidianGlassTint else InfinityPureWhite
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant.copy(alpha = 0.58f)
    val topHighlight = if (dark) InfinityPureWhite.copy(alpha = 0.14f) else InfinityPureWhite.copy(alpha = 0.92f)
    val shape = RoundedCornerShape(24.dp)

    Surface(
        modifier = modifier.widthIn(min = 180.dp, max = 380.dp),
        shape = shape,
        color = containerColor,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 12.dp,
    ) {
        Box(
            Modifier
                .clip(shape)
                .drawWithCache {
                    val hiPx = 18.dp.toPx().coerceAtMost(size.height)
                    val hiBrush = Brush.verticalGradient(
                        0f to topHighlight,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = hiPx,
                    )
                    val center = Offset(26.dp.toPx().coerceAtMost(size.width * 0.25f), size.height / 2f)
                    val radius = maxOf(size.height * 2.2f, 110.dp.toPx())
                    val glowBrush = Brush.radialGradient(
                        0f to accent.copy(alpha = if (dark) 0.20f else 0.11f),
                        0.58f to accent.copy(alpha = if (dark) 0.06f else 0.03f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    )
                    onDrawBehind {
                        drawRect(brush = glowBrush)
                        drawRect(brush = hiBrush, size = Size(size.width, hiPx))
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = if (dark) 0.18f else 0.12f))
                        .border(0.5.dp, accent.copy(alpha = if (dark) 0.30f else 0.24f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (actionLabel != null) {
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = scheme.onSurface.copy(alpha = if (dark) 0.10f else 0.07f),
                        border = BorderStroke(0.5.dp, borderColor),
                        modifier = Modifier.heightIn(min = 30.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = actionLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoChatReadAloudBoard(mode: InfinityThemeMode) {
    ProtoAudioFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部模拟会话顶栏与全站熏黑顶部悬浮通知胶囊（InfinityNoticeHost）锚定落位
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "会话气泡“朗读”与顶部熏黑通知原型",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                ProtoObsidianTopNoticeCapsule(
                    severity = InfinityNoticeSeverity.INFO,
                    message = "尚未设置默认语音模型",
                    actionLabel = "去设置",
                )
            }

            // 模拟带折叠过程的完整助手回复气泡（仅朗读正文，不含思考与工具）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "已思考 1 次 · 调用 2 次工具（朗读时自动跳过）",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = "今晚的配乐方案已经整理完成。第一段采用清亮古筝铺底，后半段切入低音弦乐增强张力，适合作为雨夜剑舞的开场氛围。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                Text("1. 空闲态（默认）", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                ProtoAssistantReadAloudRow(ProtoReadAloudState.IDLE, "Gemini 3.8 Flash", "02:20")
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.25f))
                Text("2. 合成中（再次点按取消，行高零跳变）", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                ProtoAssistantReadAloudRow(ProtoReadAloudState.LOADING, "Gemini 3.8 Flash", "02:20")
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.25f))
                Text("3. 朗读中（点按立即停止，与媒体播放器互斥）", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                ProtoAssistantReadAloudRow(ProtoReadAloudState.PLAYING, "Gemini 3.8 Flash", "02:20")
            }

            Text(
                text = "其余顶部熏黑通知胶囊状态（InfinityNoticeHost）",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProtoObsidianTopNoticeCapsule(
                    severity = InfinityNoticeSeverity.INFO,
                    message = "该回复暂无可朗读的正文",
                )
                ProtoObsidianTopNoticeCapsule(
                    severity = InfinityNoticeSeverity.ERROR,
                    message = "语音合成失败：提供商密钥无效或配额不足",
                )
            }
        }
    }
}

private data class ProtoProviderModelItem(
    val name: String,
    val remoteId: String,
    val typeLabel: String,
    val enabled: Boolean,
    val badges: List<ModelCapabilityBadge> = emptyList(),
    val contextLabel: String? = null,
)

@Composable
private fun ProtoGoogleModelsBoard(mode: InfinityThemeMode) {
    val models = listOf(
        ProtoProviderModelItem("Gemini 3.8 Flash Lite TTS", "gemini-3.8-flash-lite-tts", "语音", true),
        ProtoProviderModelItem("Gemini 3.8 Flash TTS", "gemini-3.8-flash-tts", "语音", true),
        ProtoProviderModelItem("Gemini 3.8 Live", "gemini-3.8-live", "实时", false),
        ProtoProviderModelItem(
            "Lyria 3 Clip Preview",
            "lyria-3-clip-preview",
            "音乐",
            true,
            badges = listOf(ModelCapabilityBadge(Icons.Rounded.Visibility, "视觉参考")),
        ),
        ProtoProviderModelItem(
            "Lyria 3 Pro Preview",
            "lyria-3-pro-preview",
            "音乐",
            false,
            badges = listOf(ModelCapabilityBadge(Icons.Rounded.Visibility, "视觉参考")),
        ),
        ProtoProviderModelItem(
            "Lyria 3.5",
            "lyria-3.5",
            "音乐",
            true,
            badges = listOf(ModelCapabilityBadge(Icons.Rounded.Visibility, "视觉参考")),
        ),
        ProtoProviderModelItem("Lyria Realtime Experimental", "lyria-realtime-exp", "实时", false),
    )

    ProtoAudioFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        InfinitySettingsPageLayout(title = "Google AI Studio", onBack = {}) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "修复后的自动分类与品牌图标（Lyria 归入音乐、TTS 归入语音、Live/Realtime 归入实时）",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                models.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().bettboxListGroupItem(index, models.size)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = InfinitySpacing.RowHorizontal, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 正式代码将在 ModelBrandResolver 中新增 "lyria" -> "google"；原型直接展示目标 Google 品牌图标。
                            ModelBrandTile(
                                modelId = "gemini",
                                size = 36.dp,
                                shape = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.remoteId,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ModelCapabilityBadges(
                                    typeLabel = item.typeLabel,
                                    capabilities = item.badges,
                                    contextLabel = item.contextLabel,
                                    capabilitiesDescription = "",
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            InfinitySwitch(
                                checked = item.enabled,
                                onCheckedChange = {},
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        if (index < models.lastIndex) {
                            InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + 36.dp + 12.dp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProtoDefaultAndMediaSplitBoard(mode: InfinityThemeMode) {
    ProtoAudioFrame(mode) {
        InfinitySettingsPageLayout(title = "默认模型与媒体目录", onBack = {}) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
            ) {
                InfinitySettingsSection(
                    title = "云端媒体默认模型",
                    footer = "默认语音模型用于气泡朗读与语音合成工具；默认音乐模型用于音乐与配乐生成。",
                ) {
                    ProtoDefaultMediaRow(Icons.Rounded.Image, "云端默认图像模型", "Nano Banana 2.1 · Gemini", set = true)
                    InfinityGroupDivider()
                    ProtoDefaultMediaRow(Icons.Rounded.Movie, "云端默认视频模型", "未设置", set = false)
                    InfinityGroupDivider()
                    ProtoDefaultMediaRow(Icons.Rounded.RecordVoiceOver, "云端默认语音模型（TTS）", "Gemini 3.8 Flash TTS · Gemini", set = true)
                    InfinityGroupDivider()
                    ProtoDefaultMediaRow(Icons.Rounded.MusicNote, "云端默认音乐模型", "Lyria 3.5 · Gemini", set = true)
                }

                InfinitySettingsSection(title = "媒体目录 · 语音") {
                    InfinitySettingsItem(
                        title = "Gemini 3.8 Flash TTS",
                        subtitleSegments = listOf("Gemini", "语音合成", "音色 Kore"),
                        showChevron = true,
                        onClick = {},
                        trailingContent = { ProductionStatusChip("默认", ProductionTone.INFO) },
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = "Gemini 3.8 Flash Lite TTS",
                        subtitleSegments = listOf("Gemini", "语音合成", "音色 Puck"),
                        showChevron = true,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = "MiniMax Speech 2.8 HD",
                        subtitleSegments = listOf("MiniMax", "语音合成"),
                        showChevron = true,
                        onClick = {},
                    )
                }

                InfinitySettingsSection(title = "媒体目录 · 音乐") {
                    InfinitySettingsItem(
                        title = "Lyria 3.5",
                        subtitleSegments = listOf("Gemini", "音乐生成", "参考图 ≤4"),
                        showChevron = true,
                        onClick = {},
                        trailingContent = { ProductionStatusChip("默认", ProductionTone.INFO) },
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = "Lyria 3 Clip Preview",
                        subtitleSegments = listOf("Gemini", "音乐生成", "30s 片段"),
                        showChevron = true,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = "ACE-Step BGM",
                        subtitleSegments = listOf("ComfyUI", "MAIN", "音乐生成"),
                        showChevron = true,
                        onClick = {},
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ProtoDefaultMediaRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    set: Boolean,
) {
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        subtitleIsIdentifier = set,
        showChevron = !set,
        onClick = {},
        trailingContent = if (!set) null else {
            {
                IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ProtoSpeechEditorBoard(mode: InfinityThemeMode) {
    val typeSegments = listOf(
        Triple("chat", "对话", Icons.Rounded.ChatBubbleOutline),
        Triple("reasoning", "推理", Icons.Rounded.Psychology),
        Triple("image", "图像", Icons.Rounded.Image),
        Triple("video", "视频", Icons.Rounded.Movie),
        Triple("speech", "语音", Icons.Rounded.RecordVoiceOver),
        Triple("music", "音乐", Icons.Rounded.MusicNote),
        Triple("realtime", "实时", Icons.Rounded.GraphicEq),
        Triple("embedding", "嵌入", Icons.Rounded.ScatterPlot),
        Triple("rerank", "重排", Icons.AutoMirrored.Rounded.Sort),
    ).map { (key, label, icon) ->
        InfinitySegment(
            value = key,
            label = label,
            leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
    }
    val voices = listOf("Kore", "Puck", "Zephyr", "Aoede", "Charon", "Fenrir").map { voice ->
        InfinitySegment(value = voice, label = voice)
    }

    ProtoAudioFrame(mode) {
        InfinitySettingsPageLayout(title = "编辑模型与语音设置", onBack = {}) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
            ) {
                InfinitySettingsSection(
                    title = "模型类型（横向滑动分段）",
                    footer = "音频拆分为“语音（TTS）”、“音乐”与“实时”三类，实时模型自动排除出常规对话与单次媒体选择器。",
                ) {
                    InfinitySegmentedControl(
                        segments = typeSegments,
                        selected = "speech",
                        onSelect = {},
                        compact = true,
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                        modifier = Modifier.padding(vertical = InfinitySpacing.Small),
                    )
                }

                InfinitySettingsSection(
                    title = "云端媒体协议",
                    footer = "Gemini TTS 通过 generateContent（AUDIO 模态）同步输出高保真 WAV，低延迟适合朗读与配音。",
                ) {
                    InfinitySettingsItem(
                        title = "生成接口预设",
                        subtitle = "Gemini Flash TTS（Google AI Studio）",
                        icon = Icons.Rounded.RecordVoiceOver,
                        showChevron = true,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = "媒体设置",
                        subtitle = "默认音色、输出格式与计费",
                        icon = Icons.Rounded.Tune,
                        showChevron = true,
                        onClick = {},
                    )
                }

                InfinitySettingsSection(
                    title = "语音合成默认音色（媒体详情页）",
                    footer = "会话气泡朗读默认使用此处选定的音色；Agent 调用 generate_speech 时可按角色临时覆写。",
                ) {
                    InfinitySegmentedControl(
                        segments = voices,
                        selected = "Kore",
                        onSelect = {},
                        compact = true,
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                        modifier = Modifier.padding(vertical = InfinitySpacing.Small),
                    )
                }

                InfinitySettingsGroup {
                    InfinitySettingsItem(
                        title = "输出格式",
                        subtitle = "WAV（PCM 24kHz 无损封装）",
                        onClick = {},
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Preview(name = "1_ReadAloud_Dark", widthDp = 390, heightDp = 780, showBackground = true)
@Composable
fun ChatReadAloudBubbleDarkPreview() {
    ProtoChatReadAloudBoard(InfinityThemeMode.DARK)
}

@Preview(name = "1_ReadAloud_Narrow_LargeFont", widthDp = 360, heightDp = 840, fontScale = 1.25f, showBackground = true)
@Composable
fun ChatReadAloudBubbleNarrowLargeFontPreview() {
    ProtoChatReadAloudBoard(InfinityThemeMode.DARK)
}

@Preview(name = "2_GoogleModels_Dark", widthDp = 390, heightDp = 760, showBackground = true)
@Composable
fun GoogleAudioModelsDarkPreview() {
    ProtoGoogleModelsBoard(InfinityThemeMode.DARK)
}

@Preview(name = "2_GoogleModels_Light", widthDp = 390, heightDp = 760, showBackground = true)
@Composable
fun GoogleAudioModelsLightPreview() {
    ProtoGoogleModelsBoard(InfinityThemeMode.LIGHT)
}

@Preview(name = "3_DefaultAndMedia_Dark", widthDp = 390, heightDp = 860, showBackground = true)
@Composable
fun DefaultAndMediaSplitDarkPreview() {
    ProtoDefaultAndMediaSplitBoard(InfinityThemeMode.DARK)
}

@Preview(name = "4_SpeechEditorAndVoice_Dark", widthDp = 390, heightDp = 740, showBackground = true)
@Composable
fun SpeechModelEditorAndVoiceDarkPreview() {
    ProtoSpeechEditorBoard(InfinityThemeMode.DARK)
}
