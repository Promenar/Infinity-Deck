package com.promenar.infinity.ui.chat

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

/** 毛玻璃背景模糊半径；饱和度略微提高，模糊后的正文颜色不发灰。 */
private val FrostedBlurRadius = 20.dp
private const val FrostedSaturation = 1.4f

/** 毛玻璃材质下外圈光效的强度系数：沿用所选流光样式，但减弱。 */
internal const val FrostedGlowScale = 0.6f

/**
 * 毛玻璃浮岛的背景来源：会话列表把自己录进 [layer] 再照常画出，浮岛在自身范围内把同一份内容模糊后垫在底下。
 * 只在毛玻璃材质下创建；列表每次重画后 [version] 加一，浮岛据此重录模糊层。
 */
@Stable
internal class ComposerBackdrop(internal val layer: GraphicsLayer) {
    /** 列表在根坐标中的左上角。 */
    internal var origin by mutableStateOf(Offset.Zero)
    internal var version by mutableIntStateOf(0)
    /** 背景来源后面的底色；未指定时用页面背景色。设置页预览放在卡片上，改用卡片色。 */
    internal var background: Color = Color.Unspecified
}

/** 当前浮岛可用的背景来源；没有提供时毛玻璃只叠半透明色，不模糊。 */
internal val LocalComposerBackdrop = staticCompositionLocalOf<ComposerBackdrop?> { null }

/** 只在毛玻璃材质下创建背景来源；静态预览不录制图层。 */
@Composable
internal fun rememberComposerBackdrop(surface: ComposerSurfaceStyle): ComposerBackdrop? {
    if (surface != ComposerSurfaceStyle.FROSTED || LocalInspectionMode.current) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { ComposerBackdrop(layer) }
}

/**
 * 挂在浮岛背后的内容（会话列表）上：内容先录进背景来源的图层再原样画出，不多画一遍内容本身。
 * [backdrop] 为 null 时不做任何事，环境光材质没有额外开销。
 */
internal fun Modifier.composerBackdropSource(backdrop: ComposerBackdrop?): Modifier =
    if (backdrop == null) {
        this
    } else {
        this
            .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
            .drawWithContent {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
                // 只写不读：列表的绘制不订阅这个计数，不会因浮岛重画而反过来重画列表。
                Snapshot.withoutReadObservation { backdrop.version++ }
            }
    }

/**
 * 浮岛表面层：铺在浮岛内容最底下、已被浮岛形状裁剪。毛玻璃材质画模糊后的背景与半透明色；
 * 环境光材质只在生成、出错或完成时从点阵屏一侧漫开一层阶段色柔光，空闲时什么都不画。纯装饰，不进入无障碍树。
 */
@Composable
internal fun ComposerIslandSurface(state: ComposerGlowState, modifier: Modifier = Modifier) {
    val styles = LocalInfinityMotionStyles.current
    when (styles.surface) {
        ComposerSurfaceStyle.FROSTED -> FrostedBackdrop(modifier.clearAndSetSemantics {})
        ComposerSurfaceStyle.AMBIENT -> AmbientWash(state, modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun FrostedBackdrop(modifier: Modifier) {
    val backdrop = LocalComposerBackdrop.current
    val tint = composerIslandMaterial().frostedTint ?: Color.Transparent
    val background = backdrop?.background?.takeOrElse { MaterialTheme.colorScheme.background } ?: MaterialTheme.colorScheme.background
    val blurLayer = if (backdrop != null) rememberGraphicsLayer() else null
    val self = remember { arrayOf(Offset.Zero) }
    val selfVersion = remember { mutableIntStateOf(0) }
    Spacer(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                if (position != self[0]) {
                    self[0] = position
                    selfVersion.intValue++
                }
            }
            .drawWithCache {
                val margin = FrostedBlurRadius.toPx().roundToInt()
                val effect = frostedRenderEffect(FrostedBlurRadius.toPx())
                onDrawBehind {
                    drawRect(background)
                    if (backdrop != null && blurLayer != null) {
                        // 读取两个计数，列表重画或浮岛移动时重录模糊层。
                        backdrop.version
                        selfVersion.intValue
                        val dx = backdrop.origin.x - self[0].x + margin
                        val dy = backdrop.origin.y - self[0].y + margin
                        blurLayer.renderEffect = effect
                        blurLayer.topLeft = IntOffset(-margin, -margin)
                        // 四周多录一圈模糊半径，浮岛边缘也能采到外面的内容，不会发白。
                        blurLayer.record(size = IntSize(size.width.roundToInt() + margin * 2, size.height.roundToInt() + margin * 2)) {
                            translate(dx, dy) { drawLayer(backdrop.layer) }
                        }
                        drawLayer(blurLayer)
                    }
                    drawRect(tint)
                }
            },
    )
}

/** 背景模糊加轻度提饱和；minSdk 31，RenderEffect 全机型可用。 */
private fun frostedRenderEffect(radiusPx: Float): RenderEffect {
    val saturate = ColorMatrix().apply { setSaturation(FrostedSaturation) }
    return android.graphics.RenderEffect.createChainEffect(
        android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
        android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP),
    ).asComposeRenderEffect()
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
private fun AmbientWash(state: ComposerGlowState, modifier: Modifier) {
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val domain = MaterialTheme.infinityDomainColors
    val errorColor = domain.signalDown
    val successColor = domain.signalOn
    // 深色底上柔光要更亮才看得出，浅色底压低避免发脏。
    val peak = if (palette.dark) 0.34f else 0.22f
    Spacer(
        modifier.drawWithCache {
            val rx = size.width * 0.78f
            val ry = maxOf(size.height * 0.95f, 56.dp.toPx())
            fun gradient(color: Color) = RadialGradient(0f, 0f, rx, color.toArgb(), color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
            val shaders = arrayOf(gradient(palette.a), gradient(palette.b), gradient(errorColor), gradient(successColor))
            val local = Matrix()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            onDrawBehind {
                val gen = state.generating.value
                val error = state.error.value
                val success = state.success.value
                if (gen <= 0f && error <= 0f && success <= 0f) return@onDrawBehind
                val anchor = composerWashAnchor(state.scopeCenter, size.width, size.height, density)
                local.setScale(1f, ry / rx)
                local.postTranslate(anchor.x, anchor.y)
                shaders.forEach { it.setLocalMatrix(local) }
                val time = state.timeSeconds
                fun wash(shader: Shader, alpha: Float) {
                    if (alpha <= 0.004f) return
                    paint.shader = shader
                    paint.alpha = (255 * clamp01(alpha)).roundToInt()
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                }
                if (gen > 0f) for (phase in GenerationMotionPhase.entries) {
                    val weight = motion.weight(phase)
                    if (weight <= 0.004f) continue
                    wash(shaders[if (washUsesTertiary(phase)) 1 else 0], peak * gen * weight * washLevel(phase, time, state.act))
                }
                // 出错与完成的整圈泛色在环境光材质下也收进浮岛内部。
                wash(shaders[2], peak * 1.3f * error)
                wash(shaders[3], peak * 1.1f * success)
            }
        },
    )
}
