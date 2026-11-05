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

/*
 * 同一列表内的行等高：组内任一行带副标题时，单行项也按双行项的高度排版，
 * 避免同一张卡片里行高参差。分组容器提供登记表，共用行布局在组合时登记自己是否为双行。
 */

/** 一组列表行的等高登记表；[forced] 为真时整组固定按双行排版。 */
@Stable
class RowHeightGroup internal constructor(private val forced: Boolean) {
    private var twoLineRows by mutableIntStateOf(0)

    /** 组内是否按双行高排版；读取它的行随登记变化重组。 */
    val twoLine: Boolean get() = forced || twoLineRows > 0

    internal fun add() {
        twoLineRows++
    }

    internal fun remove() {
        twoLineRows = (twoLineRows - 1).coerceAtLeast(0)
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
 * 行的最小高度：自身有第二行时取 [twoLine]；同组有双行项时单行项也取 [twoLine]，否则取 [singleLine]。
 * 双行项在组合落地时登记，组内高度在布局阶段读取，首帧即为最终高度，不会先矮后高跳一下。
 * 不在任何分组内时只看自身。
 */
@Composable
fun uniformRowMinHeight(hasSubtitle: Boolean, singleLine: Dp, twoLine: Dp): Modifier {
    val group = LocalRowHeightGroup.current
    if (group != null && hasSubtitle) {
        DisposableEffect(group) {
            group.add()
            onDispose { group.remove() }
        }
    }
    return Modifier.layout { measurable, constraints ->
        val tall = hasSubtitle || group?.twoLine == true
        val min = (if (tall) twoLine else singleLine).roundToPx()
            .coerceAtLeast(constraints.minHeight)
            .coerceAtMost(constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = min))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
