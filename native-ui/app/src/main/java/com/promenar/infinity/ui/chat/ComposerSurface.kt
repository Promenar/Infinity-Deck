package com.promenar.infinity.ui.chat

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BackdropSource
import com.promenar.infinity.ui.common.FrostedBackdrop
import com.promenar.infinity.ui.common.backdropSource
import com.promenar.infinity.ui.common.rememberBackdropSource
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.breath
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.evenBlink
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.motion.slowBlink
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.roundToInt

/** 毛玻璃材质下外圈光效的强度系数：沿用所选流光样式，但减弱。 */
internal const val FrostedGlowScale = 0.6f

/** 毛玻璃浮岛的背景来源：会话列表把自己录进图层，浮岛在自身范围内模糊后垫在底下。 */
internal typealias ComposerBackdrop = BackdropSource

/** 当前浮岛可用的背景来源；没有提供时毛玻璃只叠半透明色，不模糊。 */
internal val LocalComposerBackdrop = staticCompositionLocalOf<ComposerBackdrop?> { null }

/** 只在毛玻璃材质下创建背景来源；静态预览不录制图层。 */
@Composable
internal fun rememberComposerBackdrop(surface: ComposerSurfaceStyle): ComposerBackdrop? =
    rememberBackdropSource(enabled = surface == ComposerSurfaceStyle.FROSTED)

/** 挂在浮岛背后的内容（会话列表）上；[backdrop] 为 null 时不做任何事，环境光材质没有额外开销。 */
internal fun Modifier.composerBackdropSource(backdrop: ComposerBackdrop?): Modifier = backdropSource(backdrop)

/**
 * 浮岛表面层：铺在浮岛内容最底下、已被浮岛形状裁剪。
 * 熏黑透视玻璃先画实时模糊背景与熏黑半透明遮罩，再叠加从点阵屏漫开的内部阶段光散射；
 * 环境光材质不模糊背景，仅画内部柔光。空闲时两种材质都不画额外柔光，纯装饰不进入无障碍树。
 */
@Composable
internal fun ComposerIslandSurface(state: ComposerGlowState, modifier: Modifier = Modifier) {
    when (LocalInfinityMotionStyles.current.surface) {
        ComposerSurfaceStyle.FROSTED -> {
            val tint = composerIslandMaterial().frostedTint ?: Color.Transparent
            Box(modifier.clearAndSetSemantics {}) {
                FrostedBackdrop(
                    source = LocalComposerBackdrop.current,
                    tint = tint,
                    modifier = Modifier.matchParentSize(),
                )
                AmbientWash(state, frosted = true, modifier = Modifier.matchParentSize())
            }
        }
        ComposerSurfaceStyle.AMBIENT -> AmbientWash(state, frosted = false, modifier = modifier.clearAndSetSemantics {})
    }
}

/**
 * 环境光柔光的中心（像素）：运行坞点阵屏在显示时取它的中心 [scopeCenter]；没有点阵屏时取输入行右侧
 * （距右缘 [ComposerWashFallbackEndDp]，距底边 [ComposerInputRowCenterDp]），中心不高于浮岛 30% 高度。
 */
internal fun composerWashAnchor(scopeCenter: Offset, width: Float, height: Float, dp: Float): Offset {
    if (scopeCenter.isSpecified) return Offset(scopeCenter.x.coerceIn(0f, width), scopeCenter.y.coerceIn(0f, height))
    val x = (width - ComposerWashFallbackEndDp * dp).coerceAtLeast(width * 0.5f)
    val y = (height - ComposerInputRowCenterDp * dp).coerceAtLeast(height * 0.3f)
    return Offset(x, y)
}

/** 没有运行坞点阵屏时，环境光柔光中心距浮岛右缘的距离。 */
internal const val ComposerWashFallbackEndDp = 90f

/** 各阶段柔光的颜色序号（0 主色、1 第三色）与亮度 0..1。 */
private fun washLevel(phase: GenerationMotionPhase, t: Float, act: Float): Float = when (phase) {
    GenerationMotionPhase.WAIT -> 0.35f + 0.35f * breath(t, 3.2f)
    GenerationMotionPhase.STREAM -> 0.55f + 0.45f * act
    GenerationMotionPhase.TOOL -> 0.5f + 0.4f * breath(t, 1.2f)
    GenerationMotionPhase.APPROVAL -> 0.35f + 0.6f * slowBlink(t)
    GenerationMotionPhase.RETRY -> 0.15f + 0.3f * evenBlink(t)
}

private fun washUsesTertiary(phase: GenerationMotionPhase): Boolean =
    phase == GenerationMotionPhase.TOOL || phase == GenerationMotionPhase.APPROVAL

@Composable
private fun AmbientWash(state: ComposerGlowState, frosted: Boolean, modifier: Modifier) {
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val domain = MaterialTheme.infinityDomainColors
    val errorColor = domain.signalDown
    val peak = if (palette.dark) 0.38f else 0.24f
    Spacer(
        modifier.drawWithCache {
            val rx = size.width * 0.78f
            val ry = maxOf(size.height * 0.95f, 56.dp.toPx())
            fun gradient(color: Color) = RadialGradient(0f, 0f, rx, color.toArgb(), color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
            val shaders = arrayOf(gradient(palette.a), gradient(palette.b), gradient(errorColor))
            val rimPad = 16.dp.toPx()
            val rimHeight = 1.5.dp.toPx()
            fun rimGradient(color: Color): Shader {
                val edge = Color.Transparent.toArgb()
                val peakCol = color.toArgb()
                return LinearGradient(rimPad, 0f, (size.width - rimPad).coerceAtLeast(rimPad + 1f), 0f,
                    intArrayOf(edge, edge, peakCol, edge), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP)
            }
            val rimShaders = if (frosted) arrayOf(rimGradient(palette.a), rimGradient(palette.b), rimGradient(errorColor)) else null
            val local = Matrix()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            onDrawBehind {
                val gen = state.generating.value
                val error = state.error.value
                if (gen <= 0f && error <= 0f) return@onDrawBehind
                val anchor = composerWashAnchor(state.scopeCenter, size.width, size.height, density)
                local.setScale(1f, ry / rx)
                local.postTranslate(anchor.x, anchor.y)
                shaders.forEach { it.setLocalMatrix(local) }
                val time = state.timeSeconds
                fun wash(index: Int, alpha: Float) {
                    if (alpha <= 0.004f) return
                    paint.shader = shaders[index]
                    paint.alpha = (255 * clamp01(alpha)).roundToInt()
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                    if (rimShaders != null && size.width > rimPad * 2f) {
                        paint.shader = rimShaders[index]
                        paint.alpha = (255 * clamp01(alpha * 1.45f)).roundToInt()
                        drawIntoCanvas { it.nativeCanvas.drawRect(rimPad, 0f, size.width - rimPad, rimHeight, paint) }
                    }
                }
                if (gen > 0f) for (phase in GenerationMotionPhase.entries) {
                    val weight = motion.weight(phase)
                    if (weight <= 0.004f) continue
                    wash(if (washUsesTertiary(phase)) 1 else 0, peak * gen * weight * washLevel(phase, time, state.act))
                }
                // 出错的整圈泛色在内部柔光与顶边受光线上同样平滑退去。
                wash(2, peak * 1.3f * error)
            }
        },
    )
}
