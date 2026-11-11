package com.promenar.infinity.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单行标签行：高度固定一行，放不下时横向滚动，可继续滚动的一侧边缘淡出。
 * 用于数量由数据决定的只读标签（实体、摘要、触发时间等），代替放不下就换行的排布。
 */
@Composable
fun InfinityTagScrollRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalFadingEdges(start = scroll.canScrollBackward, end = scroll.canScrollForward)
            .horizontalScroll(scroll)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
