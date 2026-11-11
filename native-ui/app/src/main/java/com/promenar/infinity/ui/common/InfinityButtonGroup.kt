package com.promenar.infinity.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 按钮组：所有按钮按自然宽度排成一行放得下时横排靠左，否则整组竖排、每个按钮占满整行宽度。
 * 按钮文字始终单行，不会出现一行半的排布。
 */
@Composable
fun InfinityButtonGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = InfinitySpacing.Small,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gap = spacing.roundToPx()
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val rowWidth = buttonRowWidth(naturalWidths, gap)
        if (!constraints.hasBoundedWidth || rowWidth <= constraints.maxWidth) {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val width = (placeables.sumOf { it.width } + gap * (placeables.size - 1)).coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + gap
                }
            }
        } else {
            val width = constraints.maxWidth
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = (placeables.sumOf { it.height } + gap * (placeables.size - 1)).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/** 按钮按自然宽度横排时需要的总宽度。 */
internal fun buttonRowWidth(naturalWidths: List<Int>, gap: Int): Int =
    if (naturalWidths.isEmpty()) 0 else naturalWidths.sum() + gap * (naturalWidths.size - 1)
