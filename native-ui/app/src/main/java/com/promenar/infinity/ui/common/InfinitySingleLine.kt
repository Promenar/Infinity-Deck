package com.promenar.infinity.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单行横滑：内容永不换行、不省略，超出宽度时可横向拖动查看，被裁掉的一侧渐隐。
 * [alignEnd] 为 true 时停在末尾（路径：始终露出当前位置），否则停在开头（标题）。
 * 停靠位置即滚动值 0：停在末尾用反向滚动实现，首帧即在末尾，内容加长时保持停在末尾；
 * 内容未超出宽度时两种方式都从开头排起。内容或可视宽度变化时，以动画回到停靠位置。
 */
@Composable
fun InfinityScrollingLine(
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    fadeWidth: Dp = 24.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) {
        if (scroll.value != 0) scroll.animateScrollTo(0)
    }
    Row(
        modifier = modifier
            .edgeFade(scroll, reversed = alignEnd, fadeWidth = fadeWidth)
            .horizontalScroll(scroll, reverseScrolling = alignEnd),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** 页头标题：单行横滑，停在开头，超出部分在右侧渐隐。 */
@Composable
fun InfinityHeaderTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    alignEnd: Boolean = false,
    heading: Boolean = true,
) {
    InfinityScrollingLine(modifier = modifier, alignEnd = alignEnd) {
        Text(
            text = text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            modifier = if (heading) Modifier.semantics { heading() } else Modifier,
        )
    }
}

/** 内容超出可视宽度（需要渐隐遮罩）。首次测量前 maxValue 为 Int.MAX_VALUE，视为未超出。 */
private val ScrollState.overflows: Boolean get() = maxValue in 1 until Int.MAX_VALUE

/**
 * 被横向裁掉的一侧按已滚出的距离渐隐；两侧都没有隐藏内容时不绘制遮罩。
 * 只有超出宽度时才开离屏层供遮罩混合，未超出的标题按原样绘制。
 */
private fun Modifier.edgeFade(scroll: ScrollState, reversed: Boolean, fadeWidth: Dp): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (scroll.overflows) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        if (!scroll.overflows) return@drawWithContent
        val width = fadeWidth.toPx().coerceAtMost(size.width / 3f)
        if (width <= 0f) return@drawWithContent
        // 反向滚动时滚动值表示距末尾的距离。
        val startPx = if (reversed) scroll.maxValue - scroll.value else scroll.value
        val endPx = scroll.maxValue - startPx
        val hiddenStart = (startPx / width).coerceIn(0f, 1f)
        val hiddenEnd = (endPx / width).coerceIn(0f, 1f)
        if (hiddenStart > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 1f - hiddenStart),
                    1f to Color.Black,
                    startX = 0f,
                    endX = width,
                ),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (hiddenEnd > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    1f to Color.Black.copy(alpha = 1f - hiddenEnd),
                    startX = size.width - width,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
