package com.promenar.infinity.ui.common

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 毛玻璃背景模糊半径；饱和度略微提高，模糊后的正文颜色不发灰。 */
private val FrostedBlurRadius = 20.dp
private const val FrostedSaturation = 1.4f

/**
 * 毛玻璃的背景来源：被遮住的内容（会话列表、设置页正文）把自己录进 [layer] 再照常画出，
 * 毛玻璃表面在自身范围内把同一份内容模糊后垫在底下。内容每次重画后 [version] 加一，表面据此重录模糊层。
 */
@Stable
class BackdropSource internal constructor(internal val layer: GraphicsLayer) {
    /** 内容在根坐标中的左上角。 */
    internal var origin by mutableStateOf(Offset.Zero)
    internal var version by mutableIntStateOf(0)
    /** 背景来源后面的底色；未指定时用页面背景色。 */
    var background: Color = Color.Unspecified
}

/** 创建背景来源；[enabled] 为 false 或静态预览时返回 null，毛玻璃退化为半透明底色。 */
@Composable
fun rememberBackdropSource(enabled: Boolean = true): BackdropSource? {
    if (!enabled || LocalInspectionMode.current) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropSource(layer) }
}

/**
 * 挂在毛玻璃背后的内容上：内容先录进背景来源的图层再原样画出，不多画一遍内容本身。
 * [source] 为 null 时不做任何事。
 */
fun Modifier.backdropSource(source: BackdropSource?): Modifier =
    if (source == null) {
        this
    } else {
        this
            .onGloballyPositioned { source.origin = it.positionInRoot() }
            .drawWithContent {
                source.layer.record { this@drawWithContent.drawContent() }
                drawLayer(source.layer)
                // 只写不读：内容的绘制不订阅这个计数，不会因毛玻璃重画而反过来重画内容。
                Snapshot.withoutReadObservation { source.version++ }
            }
    }

/**
 * 毛玻璃表面：先铺底色，再画 [source] 在本区域内的模糊内容，最后叠 [tint]。
 * 没有背景来源时只有底色与 [tint]。纯装饰，不进入无障碍树。
 */
@Composable
fun FrostedBackdrop(source: BackdropSource?, tint: Color, modifier: Modifier = Modifier) {
    val background = source?.background?.takeOrElse { MaterialTheme.colorScheme.background } ?: MaterialTheme.colorScheme.background
    val blurLayer = if (source != null) rememberGraphicsLayer() else null
    val self = remember { arrayOf(Offset.Zero) }
    val selfVersion = remember { mutableIntStateOf(0) }
    Spacer(
        modifier
            .clearAndSetSemantics {}
            // 模糊层四周多录了一圈采样边，必须裁到自身范围，否则会在顶栏下方漏出一条暗带。
            .clipToBounds()
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
                    if (source != null && blurLayer != null) {
                        // 读取两个计数，内容重画或表面移动时重录模糊层。
                        source.version
                        selfVersion.intValue
                        val dx = source.origin.x - self[0].x + margin
                        val dy = source.origin.y - self[0].y + margin
                        blurLayer.renderEffect = effect
                        blurLayer.topLeft = IntOffset(-margin, -margin)
                        // 四周多录一圈模糊半径，表面边缘也能采到外面的内容，不会发白。
                        blurLayer.record(size = IntSize(size.width.roundToInt() + margin * 2, size.height.roundToInt() + margin * 2)) {
                            translate(dx, dy) { drawLayer(source.layer) }
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

/** 二级页顶栏叠在模糊正文上的底色不透明度：内容从顶栏下滚过时隐约可见，标题仍清晰。 */
private const val FrostedHeaderTintAlpha = 0.55f

/**
 * 二级页毛玻璃顶栏容器：顶栏本身用透明底色放在 [content] 里，背后画 [source] 的模糊正文与半透明页面底色。
 * 正文需挂 [backdropSource] 并从顶栏下方延伸到屏幕顶端，才能看到滚过的内容。
 */
@Composable
fun FrostedTopBar(source: BackdropSource?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        FrostedBackdrop(
            source = source,
            tint = MaterialTheme.colorScheme.background.copy(alpha = FrostedHeaderTintAlpha),
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}
