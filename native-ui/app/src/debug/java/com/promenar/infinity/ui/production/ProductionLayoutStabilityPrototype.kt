package com.promenar.infinity.ui.production

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 第二部分全站「非常驻 UI 与布局跳变」治理 — 批次 1（制作工作台 ui/production/ A1–A6）前后对比高保真原型 v1。
 * 隔离在 debug 源集，供 Android Compose Preview 可视化审阅；未修改任何 src/main/ 正式 UI 代码。
 */

@Composable
private fun PrototypeBoardSurface(
    dark: Boolean,
    fontScale: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PrototypeBoardHeader(
    batchBadge: String,
    title: String,
    subtitle: String,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.primaryContainer,
        ) {
            Text(
                text = batchBadge,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComparisonBlock(
    code: String,
    title: String,
    sourceRef: String,
    beforeSummary: String,
    afterSummary: String,
    beforeContent: @Composable ColumnScope.() -> Unit,
    afterContent: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = scheme.secondaryContainer) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = sourceRef,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ComparisonColumnCard(
                    badge = "现状 Before",
                    note = beforeSummary,
                    isAfter = false,
                    badgeContainer = scheme.errorContainer,
                    badgeContent = scheme.onErrorContainer,
                    borderAccent = scheme.error.copy(alpha = 0.35f),
                    modifier = Modifier.weight(1f),
                    content = beforeContent,
                )
                ComparisonColumnCard(
                    badge = "建议方案 After",
                    note = afterSummary,
                    isAfter = true,
                    badgeContainer = domain.successContainer,
                    badgeContent = domain.onSuccessContainer,
                    borderAccent = domain.success.copy(alpha = 0.35f),
                    modifier = Modifier.weight(1f),
                    content = afterContent,
                )
            }
        }
    }
}

@Composable
private fun ComparisonColumnCard(
    badge: String,
    note: String,
    isAfter: Boolean,
    badgeContainer: androidx.compose.ui.graphics.Color,
    badgeContent: androidx.compose.ui.graphics.Color,
    borderAccent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(0.8.dp, if (isAfter) borderAccent else scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = badgeContainer) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeContent,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAfter) MaterialTheme.infinityDomainColors.success else scheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            content()
        }
    }
}

@Composable
private fun StateTransitionBanner(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// =====================================================================
// A1: ProductionTaskRow + ProductionProgressUi + ProductionActionRow
// =====================================================================

@Composable
private fun SampleBeforeTaskRow(running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SC01-03 过肩镜头 · 视频", // wrap-check: allow 原型单行省略样例
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfinityMetaLine(
                        segments = listOf("Hailuo 2.3", "1080p"),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                ProductionStatusChip(
                    text = if (running) "生成中 42%" else "已排队",
                    tone = if (running) ProductionTone.INFO else ProductionTone.NEUTRAL,
                )
            }
            if (running) {
                ProductionProgressBar(0.42f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InfinityPillButton(
                    text = "取消",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    modifier = Modifier.width(168.dp),
                )
            }
        }
    }
}

@Composable
private fun SampleAfterTaskRow(running: Boolean, showFooterAfterComplete: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SC01-03 过肩镜头 · 视频", // wrap-check: allow 原型单行省略样例
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfinityMetaLine(
                        segments = listOf("Hailuo 2.3", "1080p"),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                ProductionStatusChip(
                    text = when {
                        showFooterAfterComplete -> "已完成"
                        running -> "生成中 42%"
                        else -> "已排队"
                    },
                    tone = when {
                        showFooterAfterComplete -> ProductionTone.SUCCESS
                        running -> ProductionTone.INFO
                        else -> ProductionTone.NEUTRAL
                    },
                )
                if (!showFooterAfterComplete) {
                    OutlinedIconButton(
                        onClick = {},
                        modifier = Modifier.size(36.dp),
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "取消任务",
                            modifier = Modifier.size(16.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (running && !showFooterAfterComplete) {
                ProductionProgressBar(0.42f)
            }
        }
    }
}

// =====================================================================
// A2: ProductionReviewUi 统一审核弹窗撤回按钮跳动
// =====================================================================

@Composable
private fun SampleReviewModalCard(
    selected: ReviewKind,
    retractVisible: Boolean,
    fixedSlotDisabledMode: Boolean,
    fixedSlotLabel: String,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("修改审阅", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                ProductionStatusChip("已有待发建议", ProductionTone.INFO)
            }
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(ReviewKind.APPROVE, "批准", leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(domain.success)) }),
                    InfinitySegment(ReviewKind.REJECT, "否决", leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error)) }),
                    InfinitySegment(ReviewKind.SUGGEST, "建议", leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary)) }),
                ),
                selected = selected,
                onSelect = {},
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = if (selected == ReviewKind.SUGGEST) "调整推轨速度，保留人物眼神光" else "",
                onValueChange = {},
                label = { Text(if (selected == ReviewKind.APPROVE) "备注（可选）" else "建议内容（必填）") },
                textStyle = LocalTextStyle.current.forInput(),
                minLines = 2,
                maxLines = 2,
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (retractVisible) {
                val active = !fixedSlotDisabledMode
                OutlinedButton(
                    onClick = {},
                    enabled = active,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, if (active) scheme.error.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (active) scheme.error else scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = fixedSlotLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.weight(1f))
                InfinityPillButton("保存", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

// =====================================================================
// A3: ProductionPanel ManifestBanner + 页签栏下跳
// =====================================================================

@Composable
private fun SamplePanelHeaderWithBanner(
    showBanner: Boolean,
    afterEqualWidthAnimated: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurface)
                Column(Modifier.weight(1f)) {
                    Text("雾港短片", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
                    InfinityMetaLine(segments = listOf("电影", "6 镜", "16:9"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                InfinityPillButton("发送 2", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                Icon(Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            }
            if (showBanner) {
                if (afterEqualWidthAnimated) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.infinityDomainColors.warningContainer.copy(alpha = 0.55f),
                        border = BorderStroke(0.5.dp, MaterialTheme.infinityDomainColors.onWarningContainer.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.infinityDomainColors.onWarningContainer)
                                Text(
                                    text = "外部已修改清单文件，进入/离开改用 AnimatedVisibility 折叠过渡。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.infinityDomainColors.onWarningContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InfinityPillButton("采用文件", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.weight(1f))
                                InfinityPillButton("覆盖磁盘", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        InfinityStatusStrip(
                            kind = InfinityStatusKind.Warning,
                            message = "清单已被外部进程改写，请先处理冲突再继续。",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfinityPillButton("采用文件修改", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                            InfinityPillButton("覆盖磁盘文件", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        }
                    }
                }
            }
            InfinityPanelTabs(
                tabs = listOf(
                    InfinityPanelTab("o", "总览"),
                    InfinityPanelTab("s", "剧本"),
                    InfinityPanelTab("a", "资产"),
                    InfinityPanelTab("b", "分镜"),
                    InfinityPanelTab("out", "出片"),
                    InfinityPanelTab("c", "剪辑"),
                ),
                selected = "o",
                onSelect = {},
                contentInset = 10.dp,
            )
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.3f))
            Text(
                text = if (afterEqualWidthAnimated) "制作流程卡片区…（随横幅平滑伸缩）" else "制作流程卡片区…（随上方横幅瞬跳）",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

// =====================================================================
// A4: ProductionVoiceWaveform 异步解码 0dp -> 48dp 跳变
// =====================================================================

private val SamplePeaks = listOf(
    0.18f, 0.35f, 0.62f, 0.88f, 0.48f, 0.72f, 0.94f, 0.55f, 0.31f, 0.67f,
    0.85f, 0.42f, 0.25f, 0.59f, 0.78f, 0.92f, 0.61f, 0.38f, 0.54f, 0.79f,
    0.45f, 0.22f, 0.66f, 0.84f, 0.51f, 0.30f, 0.73f, 0.49f, 0.27f, 0.16f,
)

@Composable
private fun SampleVoiceCard(
    waveformState: Int, // 0 = 解码中, 1 = 已完成, 2 = 不可用
    beforeZeroHeightWhenLoading: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val skeleton = scheme.onSurfaceVariant.copy(alpha = 0.22f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(scheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onTertiaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("陆昭 · S1E01 旁白 v2", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    InfinityMetaLine(segments = listOf("WAV", "18.4 秒"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                ProductionStatusChip("待审", ProductionTone.WARNING)
            }
            if (!beforeZeroHeightWhenLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerHighest.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (waveformState) {
                        0 -> Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                            val n = SamplePeaks.size
                            val step = size.width / n
                            for (i in 0 until n) {
                                val h = size.height * (0.25f + (i % 3) * 0.15f)
                                val x = (i + 0.5f) * step
                                drawLine(skeleton, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = (step * 0.45f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
                            }
                        }
                        1 -> Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                            val step = size.width / SamplePeaks.size
                            SamplePeaks.forEachIndexed { i, peak ->
                                val h = (peak * size.height).coerceAtLeast(3f)
                                val x = (i + 0.5f) * step
                                drawLine(primary, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = (step * 0.55f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
                            }
                        }
                        else -> Text("波形暂不可用", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            } else if (waveformState == 1) {
                Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                    val step = size.width / SamplePeaks.size
                    SamplePeaks.forEachIndexed { i, peak ->
                        val h = (peak * size.height).coerceAtLeast(3f)
                        val x = (i + 0.5f) * step
                        drawLine(primary, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = (step * 0.55f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfinityPillButton("批准", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
                InfinityPillButton("否决", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

// =====================================================================
// A5 + A6: 镜头规划切换引导卡 + 剪辑失败行 + 360dp 浅色大字号核验
// =====================================================================

@Composable
private fun SampleShotPlanningComparison(afterAnimatedSteady: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProductionSection("渠道与模式") {
            ProductionRow("生成模式", value = "参考图视频", chevron = false)
            ProductionRow("指定渠道", value = "Vidu Q2（不支持该模式）", chevron = false)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (afterAnimatedSteady) domain.warningContainer.copy(alpha = 0.72f) else domain.warningContainer,
                border = BorderStroke(0.5.dp, domain.onWarningContainer.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp), tint = domain.onWarningContainer)
                        Text(
                            text = if (afterAnimatedSteady) "通道不支持该模式，平滑展开切换引导" else "切换模式后整卡硬插并把下方入参卡片骤然推下",
                            style = MaterialTheme.typography.bodySmall,
                            color = domain.onWarningContainer,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfinityPillButton("切至文生视频", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        InfinityPillButton("切至首尾帧", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (afterAnimatedSteady) 72.dp else 44.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (afterAnimatedSteady) "入参规划（加载保留定高最小区）" else "入参规划（加载前坍缩至 44dp 再骤扩）",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(if (afterAnimatedSteady) "平滑过渡" else "高度骤跳", if (afterAnimatedSteady) ProductionTone.SUCCESS else ProductionTone.WARNING)
            }
        }
    }
}

// =====================================================================
// 三组 Compose Previews（全量可视化审阅）
// =====================================================================

@Preview(name = "B1-1 任务行与审核弹窗前后对比 (深色)", widthDp = 820, heightDp = 1520, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun productionBatch1TasksAndReviewDarkPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 1 · 1/3",
            title = "制作工作台布局稳定性原型 · 任务行 & 统一审核弹窗",
            subtitle = "左：现状硬插跳变 ｜ 右：建议方案（请逐项确认通过 / 调整 / 保留）",
        )
        ComparisonBlock(
            code = "A1",
            title = "生成任务行 + 进行中链条 + ProductionActionRow 单按钮",
            sourceRef = "ProductionManagement.kt:493 · ProductionProgressUi.kt:284 · ProductionPanel.kt:850",
            beforeSummary = "运行态硬插进度，行底 168dp 单按钮靠右孤悬（+68dp）",
            afterSummary = "取消钮收至状态尾 36dp 图标；单底钮全宽、进度过渡",
            beforeContent = {
                StateTransitionBanner("状态 A：已排队")
                SampleBeforeTaskRow(running = false)
                StateTransitionBanner("状态 B：运行中 42%（进度栏硬插 + 底部孤立取消）")
                SampleBeforeTaskRow(running = true)
            },
            afterContent = {
                StateTransitionBanner("状态 A/B：行头固定 36dp 关闭钮 + AnimatedVisibility 进度")
                SampleAfterTaskRow(running = true)
                StateTransitionBanner("状态 C：完成后平稳收起，不再占底行（单底钮场景亦铺满）")
                SampleAfterTaskRow(running = false, showFooterAfterComplete = true)
                InfinityPillButton("重新提交任务（单动作占满行宽）", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.fillMaxWidth())
            },
        )
        ComparisonBlock(
            code = "A2",
            title = "统一审核弹窗：切换「批准 / 否决 / 建议」时撤回按钮跳动",
            sourceRef = "ProductionReviewUi.kt:496-520",
            beforeSummary = "从「建议」切「批准」直接销毁 44dp 撤回按钮，底栏上跳",
            afterSummary = "有待发记录时 44dp 撤回槽常驻，切态弹窗高度 0dp 跳变",
            beforeContent = {
                StateTransitionBanner("处于「建议」页签：显示撤回按钮")
                SampleReviewModalCard(
                    selected = ReviewKind.SUGGEST,
                    retractVisible = true,
                    fixedSlotDisabledMode = false,
                    fixedSlotLabel = "撤回建议",
                )
                StateTransitionBanner("切到「批准」页签：按钮销毁，取消/保存上跳 56dp")
                SampleReviewModalCard(
                    selected = ReviewKind.APPROVE,
                    retractVisible = false,
                    fixedSlotDisabledMode = false,
                    fixedSlotLabel = "",
                )
            },
            afterContent = {
                StateTransitionBanner("处于「建议」页签：可直接撤回待发建议")
                SampleReviewModalCard(
                    selected = ReviewKind.SUGGEST,
                    retractVisible = true,
                    fixedSlotDisabledMode = false,
                    fixedSlotLabel = "撤回待发建议",
                )
                StateTransitionBanner("切到「批准」页签：等高 44dp 槽位留驻，零高度跳变")
                SampleReviewModalCard(
                    selected = ReviewKind.APPROVE,
                    retractVisible = true,
                    fixedSlotDisabledMode = true,
                    fixedSlotLabel = "当前已有待发建议（切回建议可改）",
                )
            },
        )
    }
}

@Preview(name = "B1-2 顶部冲突横幅与音频波形前后对比 (深色)", widthDp = 820, heightDp = 1360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun productionBatch1BannersAndWaveformDarkPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 1 · 2/3",
            title = "制作工作台布局稳定性原型 · 清单横幅 & 音频波形槽",
            subtitle = "左：现状无过渡硬插与高度从 0 突跳 ｜ 右：建议方案",
        )
        ComparisonBlock(
            code = "A3",
            title = "顶部清单冲突横幅 ManifestBanner & 报错条",
            sourceRef = "ProductionPanel.kt:467-479, 793-826",
            beforeSummary = "裸插在页头与页签之间，解决冲突后页签突跳 ~116dp",
            afterSummary = "AnimatedVisibility + 退场缓存过渡，等宽双按钮对齐",
            beforeContent = {
                StateTransitionBanner("出现外部变更冲突时（按钮宽窄不齐靠左）")
                SamplePanelHeaderWithBanner(showBanner = true, afterEqualWidthAnimated = false)
                StateTransitionBanner("解决冲突后（上方整块突消，页签瞬间上跳）")
                SamplePanelHeaderWithBanner(showBanner = false, afterEqualWidthAnimated = false)
            },
            afterContent = {
                StateTransitionBanner("AnimatedVisibility 展开统一卡片 + 等宽操作栏")
                SamplePanelHeaderWithBanner(showBanner = true, afterEqualWidthAnimated = true)
                StateTransitionBanner("AnimatedVisibility 收起（带 LatestValue 防空白）")
                SamplePanelHeaderWithBanner(showBanner = false, afterEqualWidthAnimated = true)
            },
        )
        ComparisonBlock(
            code = "A4",
            title = "音频/台词波形异步解码 0dp → 48dp 顶跳",
            sourceRef = "ProductionVoiceWaveform.kt:55-62",
            beforeSummary = "解码完成前 0dp，解码完毕瞬间插入 48dp 顶开审批卡",
            afterSummary = "首帧即常驻 48dp 槽位，Crossfade 原地切骨架 / 波形 / 兜底",
            beforeContent = {
                StateTransitionBanner("解码完成前：0dp 无占位，按钮上浮")
                SampleVoiceCard(waveformState = 0, beforeZeroHeightWhenLoading = true)
                StateTransitionBanner("解码完成后：突然撑开 +56dp，按钮下推")
                SampleVoiceCard(waveformState = 1, beforeZeroHeightWhenLoading = false)
            },
            afterContent = {
                StateTransitionBanner("解码中：常驻 48dp 骨架波条（位置锁定）")
                SampleVoiceCard(waveformState = 0, beforeZeroHeightWhenLoading = false)
                StateTransitionBanner("解码完成：Crossfade 原地显现波形（0dp 高度变化）")
                SampleVoiceCard(waveformState = 1, beforeZeroHeightWhenLoading = false)
            },
        )
    }
}

@Preview(name = "B1-3 镜头规划/失败过渡与窄屏 360dp 浅色 1.3x 验证", widthDp = 820, heightDp = 1480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun productionBatch1PlanningAndNarrowLightPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 1 · 3/3",
            title = "镜头规划 A5 · 剪辑失败行 A6 · 360dp 浅色 1.3x 大字号验证",
            subtitle = "上部为 A5/A6 深色前后对比，下部为真实 360dp 窄屏浅色大字号无截断验收",
        )
        ComparisonBlock(
            code = "A5+A6",
            title = "镜头规划引导卡 / 阻塞条 + 剪辑失败行过渡",
            sourceRef = "ProductionShotPlanning.kt:261-312 · ProductionCutPage.kt:542-548",
            beforeSummary = "引导卡/加载/剪辑失败行无 AnimatedVisibility 骤跳",
            afterSummary = "AnimatedVisibility + animateContentSize + 加载稳态最低高度",
            beforeContent = {
                SampleShotPlanningComparison(afterAnimatedSteady = false)
                ProductionSection("剪辑输出状态（失败行裸插）") {
                    ProductionRow("导出状态", value = "编码超时（裸插）", chip = ProductionChipSpec("失败", ProductionTone.ERROR), chevron = false)
                }
            },
            afterContent = {
                SampleShotPlanningComparison(afterAnimatedSteady = true)
                ProductionSection("剪辑输出状态（同进度条过渡）") {
                    ProductionRow("导出状态", value = "编码超时（平滑过渡）", chip = ProductionChipSpec("失败", ProductionTone.ERROR), chevron = false)
                }
            },
        )
        // 下半区：模拟真实 360dp 窄屏 + 浅色模式 + 1.3x 字体放大
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "窄屏 360dp · 浅色主题 · 1.3x 大字号适配列（A1–A4 改造后）",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                InfinityTheme(
                    preferences = InfinityThemePreferences(
                        mode = InfinityThemeMode.LIGHT,
                        colorSource = InfinityColorSource.INFINITY,
                    ),
                ) {
                    val baseDensity = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, 1.3f)) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(360.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SampleAfterTaskRow(running = true)
                                SampleVoiceCard(waveformState = 2, beforeZeroHeightWhenLoading = false)
                                SampleReviewModalCard(
                                    selected = ReviewKind.APPROVE,
                                    retractVisible = true,
                                    fixedSlotDisabledMode = true,
                                    fixedSlotLabel = "当前已有待发建议",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
