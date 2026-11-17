package com.promenar.infinity.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/*
 * 同一列表内的行等高：
 * - 组内任一行带副标题时，单行项也按双行项高度（64dp）排版；
 * - 组内任一行声明多行副标题（subtitleMaxLines > 1）时，组内所有行统一按 76dp 最小高度排版，
 *   避免同一卡片或选项组内因某项 1 行、邻项 2 行而出现行高参差。
 */

/** 一组列表行的等高登记表；[forced] 为真时整组固定按双行排版。 */
@Stable
class RowHeightGroup internal constructor(private val forced: Boolean) {
    private var twoLineRows by mutableIntStateOf(0)
    private var multiLineSubtitleRows by mutableIntStateOf(0)

    /** 组内是否按双行高排版；读取它的行随登记变化重组。 */
    val twoLine: Boolean get() = forced || twoLineRows > 0

    /** 组内是否存在需要多行副标题（2 行）预留空间的条目。 */
    val hasMultiLineSubtitle: Boolean get() = multiLineSubtitleRows > 0

    internal fun add(multiLineSubtitle: Boolean = false) {
        twoLineRows++
        if (multiLineSubtitle) multiLineSubtitleRows++
    }

    internal fun remove(multiLineSubtitle: Boolean = false) {
        twoLineRows = (twoLineRows - 1).coerceAtLeast(0)
        if (multiLineSubtitle) {
            multiLineSubtitleRows = (multiLineSubtitleRows - 1).coerceAtLeast(0)
        }
    }
}

val LocalRowHeightGroup = staticCompositionLocalOf<RowHeightGroup?> { null }

/**
 * 为 [content] 内的行新建一组等高登记表。
 * 惰性列表看不到屏幕外的行，应由调用方按完整数据算出 [twoLine] 传入；
 * 普通 Column 传 null，由已组合的行自行登记。
 */
@Composable
fun ProvideUniformRowHeight(twoLine: Boolean? = null, content: @Composable () -> Unit) {
    val group = remember(twoLine) { RowHeightGroup(forced = twoLine == true) }
    CompositionLocalProvider(LocalRowHeightGroup provides group, content = content)
}

/**
 * 行的最小高度：
 * - 同组内存在多行副标题项或自身为多行副标题时取 [multiLineHeight]（默认 76dp）；
 * - 同组有副标题或自身有副标题时取 [twoLine]（64dp）；
 * - 其余单行项取 [singleLine]（48dp）。
 */
@Composable
fun uniformRowMinHeight(
    hasSubtitle: Boolean,
    singleLine: Dp,
    twoLine: Dp,
    multiLineSubtitle: Boolean = false,
    multiLineHeight: Dp = InfinitySpacing.RowMultiLineSubtitleHeight,
): Modifier {
    val group = LocalRowHeightGroup.current
    val countMultiLine = hasSubtitle && multiLineSubtitle
    if (group != null && hasSubtitle) {
        DisposableEffect(group, countMultiLine) {
            group.add(countMultiLine)
            onDispose { group.remove(countMultiLine) }
        }
    }
    return Modifier.layout { measurable, constraints ->
        val minDp = when {
            countMultiLine || group?.hasMultiLineSubtitle == true -> multiLineHeight
            hasSubtitle || group?.twoLine == true -> twoLine
            else -> singleLine
        }
        val min = minDp.roundToPx()
            .coerceAtLeast(constraints.minHeight)
            .coerceAtMost(constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = min))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
