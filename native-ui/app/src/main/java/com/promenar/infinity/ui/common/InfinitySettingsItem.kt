package com.promenar.infinity.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.breakableIdentifier

@Composable
fun InfinitySettingsItem(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    titleTextStyle: TextStyle? = null,
    subtitleIsIdentifier: Boolean = false,
    enabled: Boolean = true,
    leadingSpacer: Boolean = false,
    titleColor: Color? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    subtitleModifier: Modifier = Modifier,
    role: Role = Role.Button,
    selected: Boolean? = null,
    horizontalPadding: Dp = InfinitySpacing.RowHorizontal,
    subtitleMaxLines: Int = 1,
    /** 多段元数据副标题：非空时以 [InfinityMetaLine] 排版并取代 [subtitle]。 */
    subtitleSegments: List<String>? = null,
    /** 自定义副标题：非空时取代 [subtitle] 与 [subtitleSegments]，由调用方固定行数以保持列表等高。 */
    subtitleContent: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalInfinityHaptics.current
    // 单选行在选中项变化时轻振；按钮行是导航或打开面板，不振动。
    val radioSelected = selected.takeIf { role == Role.RadioButton }
    InfinitySettingsRowLayout(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = modifier.semantics { if (selected != null) this.selected = selected }
            .clickable(enabled = enabled, role = role, onClickLabel = onClickLabel) {
                radioSelected?.let(haptics::select)
                onClick()
            },
        enabled = enabled,
        leadingSpacer = leadingSpacer,
        titleTextStyle = titleTextStyle,
        titleColor = titleColor,
        subtitleIsIdentifier = subtitleIsIdentifier,
        leadingContent = leadingContent,
        subtitleModifier = subtitleModifier,
        horizontalPadding = horizontalPadding,
        subtitleMaxLines = subtitleMaxLines,
        subtitleSegments = subtitleSegments,
        subtitleContent = subtitleContent,
        trailingContent = when {
            trailingContent != null -> trailingContent
            showChevron -> ({ Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
            else -> null
        },
    )
}

/** 所有设置行共享几何布局；交互与状态语义由包装控件提供。 */
@Composable
internal fun InfinitySettingsRowLayout(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    leadingSpacer: Boolean = false,
    titleTextStyle: TextStyle? = null,
    titleColor: Color? = null,
    subtitleIsIdentifier: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingMaxWidthFraction: Float = 1f,
    subtitleModifier: Modifier = Modifier,
    horizontalPadding: Dp = InfinitySpacing.RowHorizontal,
    // 副标题默认单行；列表项需要完整说明时可放宽到两行。
    subtitleMaxLines: Int = 1,
    /** 标题始终单行（含窄屏与大字号），列表行不会因长标题变成两行。 */
    singleLineTitle: Boolean = false,
    /** 多段元数据副标题：非空时以 [InfinityMetaLine] 排版并取代 [subtitle]，整段换行、分隔符不落行首行尾。 */
    subtitleSegments: List<String>? = null,
    /** 自定义副标题（如文字后跟状态标签）：非空时取代 [subtitle] 与 [subtitleSegments]。 */
    subtitleContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val segments = subtitleSegments?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() }
    val hasSubtitle = subtitle != null || segments != null || subtitleContent != null
    // 同组有双行项时，单行项也取双行高，标题在行内垂直居中。
    val rowHeight = uniformRowMinHeight(hasSubtitle, InfinitySpacing.RowSingleLineHeight, InfinitySpacing.RowTwoLineHeight)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)) {
        val trailingMaxWidth = maxWidth * trailingMaxWidthFraction
        val expandTitle = maxWidth < 280.dp || LocalDensity.current.fontScale > 1f
        Row(
            Modifier.fillMaxWidth()
                .then(rowHeight)
                .padding(horizontal = horizontalPadding, vertical = if (!hasSubtitle) 0.dp else InfinitySpacing.RowTwoLineVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || leadingContent != null || leadingSpacer) {
                Box(Modifier.width(InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap), contentAlignment = Alignment.CenterStart) {
                    if (leadingContent != null) {
                        Box(Modifier.size(InfinitySpacing.RowIconSize), contentAlignment = Alignment.Center) { leadingContent() }
                    } else if (icon != null) {
                        Icon(icon, null, Modifier.size(InfinitySpacing.RowIconSize), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                val titleStyle = titleTextStyle ?: MaterialTheme.typography.bodyLarge
                val titleTextColor = titleColor ?: MaterialTheme.colorScheme.onSurface
                val titleLines = when {
                    singleLineTitle -> 1
                    expandTitle -> Int.MAX_VALUE
                    !hasSubtitle -> 2
                    else -> 1
                }
                // 单行标题放不下时横滑查看、右侧渐隐，不用省略号。
                if (titleLines == 1) {
                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                        Text(title, style = titleStyle, color = titleTextColor, maxLines = 1, softWrap = false)
                    }
                } else {
                    Text(title, style = titleStyle, color = titleTextColor, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                }
                if (subtitleContent != null) {
                    Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                    subtitleContent()
                } else if (segments != null) {
                    Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                    InfinityMetaLine(
                        segments = segments,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = subtitleModifier,
                        maxLines = subtitleMaxLines,
                    )
                } else if (subtitle != null) {
                    Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                    if (subtitleMaxLines == 1) {
                        InfinityScrollingLine(subtitleModifier.fillMaxWidth()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, softWrap = false)
                        }
                    } else {
                        Text(if (subtitleIsIdentifier) subtitle.breakableIdentifier() else subtitle,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = subtitleModifier,
                            maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.widthIn(max = trailingMaxWidth), contentAlignment = Alignment.Center) { trailingContent() }
            }
        }
    }
}
