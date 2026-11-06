package com.promenar.infinity.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 属性行最小高度；可点的行用 [InfinitySpacing.MinimumTouchTarget]。 */
private val PropertyRowMinHeight = 44.dp
private val PropertyRowVertical = 10.dp
/** 上下排列时名称与取值的间距。 */
private val PropertyStackGap = 2.dp
private val PropertyChevronSize = 18.dp

private const val LabelId = "label"
private const val ValueId = "value"
private const val TrailingId = "trailing"

/** 属性表登记的各行名称宽度；行在组合阶段登记，布局阶段读取，首帧即对齐。 */
@Stable
class InfinityPropertySheetState internal constructor() {
    internal val labelWidths = mutableStateMapOf<Any, Int>()
}

private val LocalInfinityPropertySheet = staticCompositionLocalOf<InfinityPropertySheetState?> { null }

/**
 * 属性表容器：测量表内所有 [InfinityPropertyRow] 的名称，取最长名称为统一的名称列宽（上限为行内容宽度的 40%）。
 * 表内用 [InfinityPropertyDivider] 分段，用 [InfinityPropertyValueAligned] 放与取值列对齐的附加内容。
 */
@Composable
fun InfinityPropertySheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { InfinityPropertySheetState() }
    CompositionLocalProvider(LocalInfinityPropertySheet provides state) {
        Column(modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 属性表的一行：名称与取值两列对齐，同为 bodyMedium；取值左对齐、可换行。
 * - [supporting]：取值下方一行补充说明；
 * - [onClick]：整行可点，行尾显示箭头；
 * - [trailing]：放在行尾的动作，例如按钮。
 * 名称在列宽上限内放不下时（英文、大字号），这一行改为名称在上、取值在下。
 */
@Composable
fun InfinityPropertyRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    InfinityPropertyRow(
        label = label,
        modifier = modifier,
        supporting = supporting,
        onClick = onClick,
        trailing = trailing,
        valueContent = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
        },
    )
}

/** 取值需要自定义内容（例如带进度指示）时使用；排版规则与文字取值的版本相同。 */
@Composable
fun InfinityPropertyRow(
    label: String,
    valueContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val labelStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val labelWidth = remember(label, labelStyle, measurer) {
        measurer.measure(label, labelStyle, softWrap = false, maxLines = 1).size.width
    }
    val sheet = LocalInfinityPropertySheet.current
    val key = remember { Any() }
    if (sheet != null) {
        SideEffect { if (sheet.labelWidths[key] != labelWidth) sheet.labelWidths[key] = labelWidth }
        DisposableEffect(sheet, key) { onDispose { sheet.labelWidths.remove(key) } }
    }
    val interaction = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val gap = InfinitySpacing.Medium
    Layout(
        content = {
            Box(Modifier.layoutId(LabelId)) {
                Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.layoutId(ValueId)) {
                valueContent()
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null || onClick != null) {
                Row(Modifier.layoutId(TrailingId), verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    if (onClick != null) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(PropertyChevronSize),
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(interaction)
            .heightIn(min = if (onClick != null) InfinitySpacing.MinimumTouchTarget else PropertyRowMinHeight)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = PropertyRowVertical),
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailingPlaceable = measurables.firstOrNull { it.layoutId == TrailingId }?.measure(loose)
        val reserved = trailingPlaceable?.let { it.width + gapPx } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelWidth + gapPx + reserved
        val cap = InfinityPropertyLayout.labelCap(width)
        val stacked = InfinityPropertyLayout.stacked(labelWidth, cap)
        // 布局阶段读取登记表：同一帧内各行登记的名称宽度都已写入。
        val column = sheet?.let { InfinityPropertyLayout.columnWidth(it.labelWidths.values, cap) }
            ?.coerceAtLeast(labelWidth)
            ?: labelWidth
        val labelMeasurable = measurables.first { it.layoutId == LabelId }
        val valueMeasurable = measurables.first { it.layoutId == ValueId }
        val textWidth = (width - reserved).coerceAtLeast(0)
        val labelPlaceable: Placeable
        val valuePlaceable: Placeable
        val valueX: Int
        val valueYOffset: Int
        val textHeight: Int
        if (stacked) {
            labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = textWidth))
            valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = textWidth))
            val stackGap = PropertyStackGap.roundToPx()
            valueX = 0
            valueYOffset = labelPlaceable.height + stackGap
            textHeight = labelPlaceable.height + stackGap + valuePlaceable.height
        } else {
            labelPlaceable = labelMeasurable.measure(Constraints(minWidth = column, maxWidth = column))
            valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = (textWidth - column - gapPx).coerceAtLeast(0)))
            valueX = column + gapPx
            valueYOffset = 0
            textHeight = maxOf(labelPlaceable.height, valuePlaceable.height)
        }
        val height = maxOf(textHeight, trailingPlaceable?.height ?: 0, constraints.minHeight)
        layout(width, height) {
            val textTop = (height - textHeight) / 2
            labelPlaceable.placeRelative(0, textTop)
            valuePlaceable.placeRelative(valueX, textTop + valueYOffset)
            trailingPlaceable?.placeRelative(width - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
        }
    }
}

/** 与取值列对齐的附加内容，例如实体标签；表内没有并排的行时从行首开始。 */
@Composable
fun InfinityPropertyValueAligned(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val sheet = LocalInfinityPropertySheet.current
    val gap = InfinitySpacing.Medium
    Layout(
        content = content,
        modifier = modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cap = InfinityPropertyLayout.labelCap(width)
        val column = sheet?.let { InfinityPropertyLayout.columnWidth(it.labelWidths.values, cap) } ?: 0
        val indent = if (column > 0) column + gap.roundToPx() else 0
        val childConstraints = Constraints(maxWidth = (width - indent).coerceAtLeast(0), maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(childConstraints) }
        val height = maxOf(placeables.maxOfOrNull { it.height } ?: 0, constraints.minHeight)
        layout(width, height) { placeables.forEach { it.placeRelative(indent, 0) } }
    }
}

/** 属性表的段间分隔线：左右缩进与行边距一致。 */
@Composable
fun InfinityPropertyDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.XSmall),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
