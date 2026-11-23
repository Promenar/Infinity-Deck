package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 检视器面板统一坐标常量：A 档纯吸顶 TAB 顶距 4dp（y=4..52dp），B 档轻页头顶距 4dp + 高 56dp（y=4..60dp，其下 TAB 锁死 y=60..108dp）。 */
val InspectorPanelTopGap = 4.dp
val InspectorPanelHeaderHeight = 56.dp

/**
 * 检视器与竖屏上滑抽屉共用的 B 档统一 56dp 轻页头：
 * - 顶部固定 [InspectorPanelTopGap] (4dp)，行高固定 [InspectorPanelHeaderHeight] (56dp)；
 * - 无返回键时标题左起严格对齐 [InfinitySpacing.ScreenHorizontal] (16dp)；
 * - 有返回键（子页推入或独立整页）时左侧 [InfinityBackButton] 在 4dp，后接 4dp，标题落在 56dp；
 * - 右侧操作图标区统一留 4dp 尾边距。
 */
@Composable
fun InfinityInspectorTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    meta: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val inInspector = LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (inInspector) InspectorPanelTopGap else 0.dp)
            .heightIn(min = InspectorPanelHeaderHeight)
            .padding(
                start = if (onBack != null) InfinitySpacing.XSmall else InfinitySpacing.ScreenHorizontal,
                end = InfinitySpacing.XSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            InfinityBackButton(onClick = onBack)
            Spacer(Modifier.width(InfinitySpacing.XSmall))
        }
        InfinityPaneHeaderTitle(
            title = title,
            titleStyle = titleStyle,
            modifier = Modifier.weight(1f),
            meta = meta,
        )
        actions()
    }
}

/**
 * 双栏右列（次级栏）页头的标题块：16sp 标题，可带一行 12sp 信息行。
 * 版式与会话页顶栏一致：带信息行时标题行 32dp、信息行 24dp，整块在页头行里垂直居中；
 * 只有标题时单行居中。标题与信息行都单行横滑，不换行、不省略。
 */
@Composable
fun InfinityPaneHeaderTitle(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    meta: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    if (meta == null) {
        InfinityHeaderTitle(text = title, style = titleStyle, color = colors.onSurface, modifier = modifier)
        return
    }
    Column(modifier) {
        InfinityHeaderTitle(
            text = title,
            style = titleStyle,
            color = colors.onSurface,
            modifier = Modifier.fillMaxWidth().heightIn(min = PaneHeaderTitleHeight),
        )
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.labelMedium,
        ) {
            InfinityScrollingLine(Modifier.fillMaxWidth().heightIn(min = PaneHeaderMetaHeight), content = meta)
        }
    }
}

/** 与会话页顶栏相同：标题行 32dp + 状态行 24dp。 */
private val PaneHeaderTitleHeight = 32.dp
private val PaneHeaderMetaHeight = 24.dp
