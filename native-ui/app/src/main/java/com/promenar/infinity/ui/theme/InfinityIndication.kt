package com.promenar.infinity.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/** 点按反馈的圆角上限：短边一半更小时取一半，细长标签呈胶囊形。 */
internal val IndicationMaxCorner = 12.dp

/**
 * 当前位置点按反馈的圆角上限。圆角分组列表内为 0：行的按压铺满整行，首尾圆角由分组外框裁出，
 * 相邻行之间不出现内凹圆角；菜单打开时的行底色也按同一圆角绘制。
 */
val LocalInfinityPressCorner = staticCompositionLocalOf<Dp> { IndicationMaxCorner }

/** 状态层与水波在按下时的透明度（叠加后中心约 0.12，与 Material 按压状态层接近）。 */
private const val PressLayerAlpha = 0.06f
private const val PressWaveAlpha = 0.06f
private const val FocusLayerAlpha = 0.10f

/**
 * Material 组件（Surface(onClick)、IconButton、Button 等）直接调用 ripple()，不走 [InfinityIndication]；
 * 由主题统一提供这份配置，使其按压浓度与 [InfinityIndication] 叠加后的中心浓度一致，颜色同取内容色。
 */
val InfinityRippleConfiguration = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = PressLayerAlpha + PressWaveAlpha,
        focusedAlpha = FocusLayerAlpha,
        hoveredAlpha = PressLayerAlpha,
        pressedAlpha = PressLayerAlpha + PressWaveAlpha,
    ),
)

/** 圆角半径：不超过上限，也不超过短边一半。 */
internal fun indicationCornerRadius(size: Size, maxCornerPx: Float): Float =
    min(maxCornerPx, size.minDimension / 2f).coerceAtLeast(0f)

/** 水波从按点扩散到最远角所需的半径。 */
internal fun indicationWaveRadius(size: Size, origin: Offset): Float = max(
    max(hypot(origin.x, origin.y), hypot(size.width - origin.x, origin.y)),
    max(hypot(origin.x, size.height - origin.y), hypot(size.width - origin.x, size.height - origin.y)),
)

/**
 * 全应用的默认点按反馈：从按点扩散的水波加整块状态层，统一裁在圆角矩形内。
 * 没有自身形状的点击区域（文字标签、步骤行、列表行）按下时不再露出直角矩形边框；
 * 自带形状裁剪的 Material 组件仍在各自形状内显示。颜色跟随当前内容色。
 */
internal object InfinityIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = InfinityIndicationNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class InfinityIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val pressed = Animatable(0f)
    private val wave = Animatable(0f)
    private var focused by mutableStateOf(false)
    private var origin = Offset.Unspecified
    private val clip = Path()
    private var clipSize = Size.Unspecified
    private var clipCorner = -1f

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        origin = interaction.pressPosition
                        launch {
                            wave.snapTo(0f)
                            wave.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                        }
                        launch { pressed.animateTo(1f, tween(90)) }
                    }
                    is PressInteraction.Release -> {
                        presses -= interaction.press
                        if (presses.isEmpty()) launch { fadeOut() }
                    }
                    is PressInteraction.Cancel -> {
                        presses -= interaction.press
                        if (presses.isEmpty()) launch { fadeOut() }
                    }
                    is FocusInteraction.Focus -> {
                        focuses += interaction
                        focused = true
                    }
                    is FocusInteraction.Unfocus -> {
                        focuses -= interaction.focus
                        focused = focuses.isNotEmpty()
                    }
                }
            }
        }
    }

    /** 快速轻点时先让水波走完再淡出，按下的位置看得见。 */
    private suspend fun fadeOut() {
        if (wave.value < 1f) wave.animateTo(1f, tween((320 * (1f - wave.value)).toInt(), easing = FastOutSlowInEasing))
        pressed.animateTo(0f, tween(220))
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val press = pressed.value
        val focus = if (focused) 1f else 0f
        if (press <= 0f && focus <= 0f) return
        val color = currentValueOf(LocalContentColor)
        val corner = indicationCornerRadius(size, currentValueOf(LocalInfinityPressCorner).toPx())
        if (size != clipSize || corner != clipCorner) {
            clip.reset()
            clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
            clipSize = size
            clipCorner = corner
        }
        clipPath(clip) {
            val layer = max(press * PressLayerAlpha, focus * FocusLayerAlpha)
            drawRect(color.copy(alpha = layer))
            if (press > 0f) {
                val center = if (origin.isSpecified) origin else Offset(size.width / 2f, size.height / 2f)
                val radius = indicationWaveRadius(size, center) * (0.25f + 0.75f * wave.value)
                drawCircle(color.copy(alpha = press * PressWaveAlpha), radius = radius, center = center)
            }
        }
    }
}
