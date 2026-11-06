package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

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
