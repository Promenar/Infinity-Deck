package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 镜头规格弹窗视觉升级（方向 A vs 方向 B）与窄检视器节头防挤压修复原型 v1。
 */
@Composable
private fun PolishProtoFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            color = if (dark) scheme.scrim.copy(alpha = 0.82f) else scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

@Composable
private fun ObsidianDialogShell(
    title: String,
    subtitle: String,
    ratioBadge: String? = null,
    bottomSheetStyle: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val container = if (dark) InfinityObsidianSolidContainer else scheme.surfaceContainerLowest
    val shape = if (bottomSheetStyle) RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp) else RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = container,
        border = BorderStroke(1.dp, if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.56f)),
        shadowElevation = if (bottomSheetStyle) 4.dp else 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (bottomSheetStyle) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (bottomSheetStyle) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(if (dark) InfinityPureWhite.copy(alpha = 0.24f) else scheme.outlineVariant),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (ratioBadge != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = scheme.primaryContainer.copy(alpha = if (dark) 0.55f else 0.7f),
                        ) {
                            Text(
                                text = ratioBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                fontWeight = FontWeight.Medium,
                                color = scheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

private fun formatRatioLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return ""
    val ratio = width.toDouble() / height.toDouble()
    val known = listOf(
        16.0 / 9.0 to "16:9",
        9.0 / 16.0 to "9:16",
        4.0 / 3.0 to "4:3",
        3.0 / 4.0 to "3:4",
        21.0 / 9.0 to "21:9",
        3.0 / 2.0 to "3:2",
        2.0 / 3.0 to "2:3",
        1.0 to "1:1",
    )
    val matched = known.firstOrNull { abs(ratio - it.first) < 0.04 }?.second
    val base = matched ?: "${((ratio * 100.0).roundToInt() / 100.0)}:1"
    val orient = when {
        width > height -> "横屏"
        height > width -> "竖屏"
        else -> "方形"
    }
    return "$base $orient"
}

@Composable
private fun CompactPixelSlot(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (dark) scheme.surfaceContainerHighest.copy(alpha = 0.72f) else scheme.surfaceContainerHighest.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = {},
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = InfinityMonospace,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                ),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DirectionAComfyCard(
    customSelected: Boolean,
    widthText: String,
    heightText: String,
    defaultW: Int = 1344,
    defaultH: Int = 768,
    bottomSheetStyle: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val w = if (customSelected) widthText.toIntOrNull() ?: defaultW else defaultW
    val h = if (customSelected) heightText.toIntOrNull() ?: defaultH else defaultH
    val ratioText = formatRatioLabel(w, h)
    ObsidianDialogShell(
        title = "镜头规格",
        subtitle = "仅作用于当前镜头，不影响工作流默认设置",
        ratioBadge = ratioText,
        bottomSheetStyle = bottomSheetStyle,
    ) {
        BettboxListGroup {
            Column(Modifier.fillMaxWidth()) {
                // 选项 1：跟随默认
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AspectRatio,
                        contentDescription = null,
                        tint = if (!customSelected) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "跟随工作流默认",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (!customSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = scheme.onSurface,
                        )
                        InfinityMetaLine(
                            segments = listOf("${defaultW}×${defaultH}", "使用当前工作流预设"),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (!customSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(
                    color = scheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                // 选项 2：自定义分辨率（选中后在卡片内平滑展开紧凑输入带）
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.RadioButton, onClick = {})
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = if (customSelected) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "自定义分辨率",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (customSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = scheme.onSurface,
                            )
                            Text(
                                text = if (customSelected) "${widthText}×${heightText} 像素" else "手动指定当前镜头输出宽高",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (customSelected) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                        if (customSelected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (customSelected) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CompactPixelSlot("宽 (px)", widthText, Modifier.weight(1f))
                                Text(
                                    text = "×",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                                CompactPixelSlot("高 (px)", heightText, Modifier.weight(1f))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
                                ) {
                                    IconButton(onClick = {}, modifier = Modifier.size(44.dp)) {
                                        Icon(
                                            imageVector = Icons.Rounded.SwapHoriz,
                                            contentDescription = "互换宽高",
                                            tint = scheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "范围 64–8192 像素；按 16 像素网格自动对齐",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfinityPillButton(
                text = "取消",
                onClick = {},
                tone = InfinityPillTone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
            InfinityPillButton(
                text = "应用",
                onClick = {},
                tone = InfinityPillTone.PRIMARY,
                icon = Icons.Rounded.Check,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DirectionACloudCard(bottomSheetStyle: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    ObsidianDialogShell(
        title = "镜头规格",
        subtitle = "选择云端通道支持的输出分辨率，点按即生效",
        bottomSheetStyle = bottomSheetStyle,
    ) {
        BettboxListGroup {
            Column(Modifier.fillMaxWidth()) {
                val items = listOf(
                    Triple("跟随通道默认", "使用模型默认分辨率设置", false),
                    Triple("720p", "标准清晰度", false),
                    Triple("1080p", "高清输出", true),
                )
                items.forEachIndexed { idx, (label, desc, selected) ->
                    if (idx > 0) {
                        HorizontalDivider(
                            color = scheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.RadioButton, onClick = {})
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = scheme.onSurface,
                            )
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionBCard(
    customSelected: Boolean,
    widthText: String,
    heightText: String,
    defaultW: Int = 1344,
    defaultH: Int = 768,
    bottomSheetStyle: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    ObsidianDialogShell(
        title = "镜头规格",
        subtitle = "仅作用于当前镜头，不影响工作流默认设置",
        bottomSheetStyle = bottomSheetStyle,
    ) {
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(false, "跟随默认"),
                InfinitySegment(true, "自定义尺寸"),
            ),
            selected = customSelected,
            onSelect = {},
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!customSelected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "$defaultW × $defaultH",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = InfinityMonospace,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = scheme.onSurface,
                    )
                    InfinityMetaLine(
                        segments = listOf(formatRatioLabel(defaultW, defaultH), "来自当前工作流默认参数"),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.surfaceContainerHighest.copy(alpha = 0.72f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("宽度 (px)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                Text(
                                    text = widthText,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = InfinityMonospace,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    textAlign = TextAlign.Center,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "互换宽高", tint = scheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.surfaceContainerHighest.copy(alpha = 0.72f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("高度 (px)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                Text(
                                    text = heightText,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = InfinityMonospace,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    textAlign = TextAlign.Center,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        InfinityMetaLine(
                            segments = listOf(formatRatioLabel(1920, 1088), "16px 网格对齐"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
            }
        }
        InfinityPillButton(
            text = if (customSelected) "应用自定义规格" else "保持工作流默认",
            onClick = {},
            tone = InfinityPillTone.PRIMARY,
            icon = Icons.Rounded.Check,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 修复后的 ProductionSectionHeader 布局原型：左主标题优先保底 56% 宽度，右侧辅助说明自动占剩余空间并单行省略。 */
@Composable
private fun FixedProductionSectionHeaderProto(
    titleSegments: List<String>,
    trailing: String?,
) {
    val titleStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    val titleColor = MaterialTheme.colorScheme.primary
    val trailingColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gapPx = 8.dp
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 4.dp),
        content = {
            InfinityMetaLine(titleSegments, style = titleStyle, color = titleColor)
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = trailingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val gap = gapPx.roundToPx()
        val titleMeasurable = measurables[0]
        val trailingMeasurable = measurables.getOrNull(1)
        if (trailingMeasurable == null) {
            val titlePlaceable = titleMeasurable.measure(constraints.copy(minWidth = 0))
            layout(maxW, titlePlaceable.height) { titlePlaceable.placeRelative(0, 0) }
        } else {
            val trailingIntrinsic = trailingMeasurable.maxIntrinsicWidth(constraints.maxHeight)
            val titleIntrinsic = titleMeasurable.maxIntrinsicWidth(constraints.maxHeight)
            val available = (maxW - gap).coerceAtLeast(0)
            val minTitleBudget = (available * 0.56f).roundToInt()
            val titleMaxW = when {
                titleIntrinsic + trailingIntrinsic <= available -> titleIntrinsic
                titleIntrinsic <= minTitleBudget -> titleIntrinsic
                else -> minTitleBudget
            }.coerceIn(0, available)
            val titlePlaceable = titleMeasurable.measure(Constraints(minWidth = 0, maxWidth = titleMaxW))
            val trailingMaxW = (available - titlePlaceable.width).coerceAtLeast(0)
            val trailingPlaceable = trailingMeasurable.measure(Constraints(minWidth = 0, maxWidth = trailingMaxW))
            val height = maxOf(titlePlaceable.height, trailingPlaceable.height)
            layout(maxW, height) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                trailingPlaceable.placeRelative(maxW - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
            }
        }
    }
}

@Preview(name = "方向A 镜头规格 深色420", widthDp = 420, heightDp = 1240, locale = "zh-rCN")
@Composable
fun SpecsDirectionADarkPreview() = PolishProtoFrame(dark = true) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("方向 A（推荐）· 跟随默认态", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        DirectionAComfyCard(customSelected = false, widthText = "1344", heightText = "768")
        Text("方向 A · 自定义展开态（卡内平滑展开）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        DirectionAComfyCard(customSelected = true, widthText = "1920", heightText = "1088")
        Text("方向 A · 云端通道（即点即生效）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        DirectionACloudCard()
    }
}

@Preview(name = "方向A 镜头规格 浅色360底板", widthDp = 360, heightDp = 760, locale = "zh-rCN")
@Composable
fun SpecsDirectionALightSheetPreview() = PolishProtoFrame(dark = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("方向 A · 浅色窄屏底部面板（自定义态）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        DirectionAComfyCard(customSelected = true, widthText = "1920", heightText = "1088", bottomSheetStyle = true)
    }
}

@Preview(name = "方向B 参数盘 深色420", widthDp = 420, heightDp = 820, locale = "zh-rCN")
@Composable
fun SpecsDirectionBDarkPreview() = PolishProtoFrame(dark = true) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("方向 B · 极简分段参数盘（默认态）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        DirectionBCard(customSelected = false, widthText = "1344", heightText = "768")
        Text("方向 B · 极简分段参数盘（自定义态）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        DirectionBCard(customSelected = true, widthText = "1920", heightText = "1088")
    }
}

@Preview(name = "方向B 参数盘 浅色360底板", widthDp = 360, heightDp = 520, locale = "zh-rCN")
@Composable
fun SpecsDirectionBLightSheetPreview() = PolishProtoFrame(dark = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("方向 B · 浅色窄屏底部面板", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        DirectionBCard(customSelected = true, widthText = "1920", heightText = "1088", bottomSheetStyle = true)
    }
}

@Preview(name = "节头修复对照 窄检视器340", widthDp = 340, heightDp = 540, locale = "zh-rCN")
@Composable
fun SectionHeaderNarrowFixDarkPreview() = PolishProtoFrame(dark = true) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("修复前（真机 SC01-01 复现：右侧长通道名挤压左侧折成三行）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Column {
                ProductionSectionHeader(
                    title = "输入",
                    titleSegments = listOf("输入", "分张", "6 张"),
                    trailing = "按 H3 Ref2VA 参考图 768p Pika4+Veda 传入",
                )
                BettboxListGroup { ProductionMutedRow("P1 正面全身（图1）") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text("修复后（左侧单行稳固，右侧自动让渡单行省略）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Column {
                FixedProductionSectionHeaderProto(
                    titleSegments = listOf("输入", "分张", "6 张"),
                    trailing = "按 H3 Ref2VA 参考图 768p Pika4+Veda 传入",
                )
                BettboxListGroup { ProductionMutedRow("P1 正面全身（图1）") }
            }
            Column {
                FixedProductionSectionHeaderProto(
                    titleSegments = listOf("输入", "分张", "6 张", "2 个锁帧"),
                    trailing = "按 H3 Ref2VA 参考图 768p Pika4+Veda 传入",
                )
                BettboxListGroup { ProductionMutedRow("起手构图（锁帧 第 0 帧）") }
            }
            Column {
                FixedProductionSectionHeaderProto(
                    titleSegments = listOf("任务"),
                    trailing = "2 项进行中",
                )
                BettboxListGroup { ProductionMutedRow("正常短标题场景不受影响") }
            }
        }
    }
}

@Composable
private fun InlineSpecsCardProto(
    dark: Boolean,
    isComfy: Boolean,
    expanded: Boolean,
    customSelected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProductionSectionHeader("通道与模式")
        BettboxListGroup {
            Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
                ProductionRow("模式", value = "参考", onClick = {})
                ProductionRow(
                    "通道",
                    value = if (isComfy) "H3 Ref2VA 参考图 768p Pika..." else "Seedance 2.5",
                    onClick = {},
                )
                // 规格行：右侧用展开箭头代替右箭头，点按在卡片内原位展开/收起
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "规格",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    InfinityMetaLine(
                        segments = when {
                            isComfy && customSelected -> listOf("1920×1088", "124 帧")
                            isComfy -> listOf("1344×768", "124 帧")
                            else -> listOf("1080p", "16:9", "5 秒")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expanded) scheme.primary else scheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = if (expanded) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (expanded) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.72f) else scheme.surfaceContainer,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (isComfy) {
                                InfinitySegmentedControl(
                                    segments = listOf(
                                        InfinitySegment(false, "工作流默认"),
                                        InfinitySegment(true, "自定义"),
                                    ),
                                    selected = customSelected,
                                    onSelect = {},
                                    fillWidth = true,
                                    compact = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (customSelected) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        CompactPixelSlot("宽", "1920", Modifier.weight(1f))
                                        Text("×", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                        CompactPixelSlot("高", "1088", Modifier.weight(1f))
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
                                        ) {
                                            IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "互换宽高", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "16:9 横屏（16px 对齐）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        InfinityPillButton(
                                            text = "应用",
                                            onClick = {},
                                            tone = InfinityPillTone.PRIMARY,
                                            icon = Icons.Rounded.Check,
                                            compact = true,
                                        )
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        InfinityMetaLine(
                                            segments = listOf("1344×768", "16:9 横屏", "跟随工作流默认"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf("默认" to false, "720p" to false, "1080p" to true).forEach { (label, selected) ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                            border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)) else null,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 38.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (selected) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ProductionRow("预演素材", value = "无")
            }
        }
    }
}

@Preview(name = "方向C 面板内联展开 深色360", widthDp = 360, heightDp = 1040, locale = "zh-rCN")
@Composable
fun SpecsDirectionCInlineDarkPreview() = PolishProtoFrame(dark = true) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("方向 C · 面板原位内联（本地工作流自定义展开）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            InlineSpecsCardProto(dark = true, isComfy = true, expanded = true, customSelected = true)
            Text("方向 C · 面板原位内联（云端通道即点即选）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            InlineSpecsCardProto(dark = true, isComfy = false, expanded = true, customSelected = false)
        }
    }
}

@Preview(name = "方向C 面板内联展开 浅色360", widthDp = 360, heightDp = 560, locale = "zh-rCN")
@Composable
fun SpecsDirectionCInlineLightPreview() = PolishProtoFrame(dark = false) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("方向 C · 浅色面板原位内联（自定义展开）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            InlineSpecsCardProto(dark = false, isComfy = true, expanded = true, customSelected = true)
        }
    }
}
