package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 截图预览里以静态浮层示意菜单展开状态：弹出窗口不进入截图，外观与 InfinityDropdownMenu 一致。 */
@Composable
fun StaticMenuPanel(modifier: Modifier = Modifier, minWidth: Dp = 240.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = infinityMenuColors()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.container,
        border = BorderStroke(0.5.dp, colors.edge),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = minWidth, max = 300.dp).padding(6.dp), content = content)
    }
}
