package com.promenar.infinity.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.promenar.infinity.ui.theme.InfinityIndication

/**
 * 触控区比可见形状大时的按压反馈：外层用 `clickable(interactionSource, indication = null)` 撑够触控区，
 * 可见的胶囊、圆点或滑块挂本修饰符，反馈裁在它自己的形状内，不会溢出成更大的方块。
 */
fun Modifier.pressFeedback(interactionSource: InteractionSource, shape: Shape): Modifier =
    clip(shape).indication(interactionSource, InfinityIndication)

/**
 * 没有可见容器的入口（无底色的文字、图标、动画头部、图片）：按下时整体变淡，
 * 不画会溢出内容的状态层。需配合 `clickable(interactionSource, indication = null)`。
 */
@Composable
fun Modifier.dimOnPress(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.5f else 1f, tween(if (pressed) 60 else 160), label = "dimOnPress")
    return graphicsLayer { this.alpha = alpha }
}
