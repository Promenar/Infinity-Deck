package com.promenar.infinity.ui.common

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
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

/** 使用统一熏黑毛玻璃 / 环境光材质体系的界面角色。 */
enum class FrostedChromeRole {
    TOP_BAR,
    FLOATING_INSPECTOR,
    FLOATING_STRIP,
    FLOATING_DRAWER,
}

/**
 * 界面外框与顶栏的表面材质规格：
 * 在毛玻璃材质下提供熏黑（[InfinityObsidianGlassTint]）或冷白（[InfinityPureWhite]）半透遮罩、外描边、顶缘内高光与内嵌半透芯片；
 * 在环境光省电档下同步降级为熏黑实底（[InfinityObsidianSolidContainer]、芯片 [InfinityObsidianSolidChip]）。
 */
@Immutable
data class FrostedChromeMaterial(
    val container: Color,
    val frostedTint: Color?,
    val border: BorderStroke?,
    val topHighlight: Color?,
    val innerCard: Color,
    val innerCardBorder: BorderStroke?,
    val edgeDivider: Color,
    val isFrosted: Boolean,
)

/** 当前悬浮面板向下传递的外框材质；固定或全屏实底页面时为 null。 */
val LocalFrostedChromeMaterial = staticCompositionLocalOf<FrostedChromeMaterial?> { null }

/** 按角色与主题档位解析与输入浮岛视觉对齐的材质规格。 */
internal fun resolveFrostedChromeMaterial(
    role: FrostedChromeRole,
    scheme: ColorScheme,
    dark: Boolean,
    surface: ComposerSurfaceStyle,
): FrostedChromeMaterial {
    fun hairline(color: Color, alpha: Float, width: Dp = 1.dp) = BorderStroke(width, color.copy(alpha = alpha))

    if (surface == ComposerSurfaceStyle.FROSTED) {
        return when (role) {
            FrostedChromeRole.TOP_BAR -> if (dark) {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.76f),
                    border = null,
                    topHighlight = null,
                    innerCard = InfinityPureWhite.copy(alpha = 0.07f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.10f, 0.5.dp),
                    edgeDivider = InfinityPureWhite.copy(alpha = 0.10f),
                    isFrosted = true,
                )
            } else {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.78f),
                    border = null,
                    topHighlight = null,
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    edgeDivider = scheme.outlineVariant.copy(alpha = 0.45f),
                    isFrosted = true,
                )
            }

            FrostedChromeRole.FLOATING_INSPECTOR -> if (dark) {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.82f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.14f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.07f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
                    edgeDivider = InfinityPureWhite.copy(alpha = 0.10f),
                    isFrosted = true,
                )
            } else {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.82f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    edgeDivider = scheme.outlineVariant.copy(alpha = 0.45f),
                    isFrosted = true,
                )
            }

            FrostedChromeRole.FLOATING_STRIP -> if (dark) {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.78f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.14f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.06f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
                    edgeDivider = InfinityPureWhite.copy(alpha = 0.10f),
                    isFrosted = true,
                )
            } else {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.78f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    edgeDivider = scheme.outlineVariant.copy(alpha = 0.45f),
                    isFrosted = true,
                )
            }

            FrostedChromeRole.FLOATING_DRAWER -> if (dark) {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.82f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.10f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.08f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.09f, 0.5.dp),
                    edgeDivider = InfinityPureWhite.copy(alpha = 0.12f),
                    isFrosted = true,
                )
            } else {
                FrostedChromeMaterial(
                    container = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.82f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.06f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.45f, 0.5.dp),
                    edgeDivider = scheme.outlineVariant.copy(alpha = 0.45f),
                    isFrosted = true,
                )
            }
        }
    }

    val isHeader = role == FrostedChromeRole.TOP_BAR
    return if (dark) {
        FrostedChromeMaterial(
            container = InfinityObsidianSolidContainer,
            frostedTint = null,
            border = if (isHeader) null else hairline(InfinityPureWhite, 0.13f),
            topHighlight = if (isHeader) null else InfinityPureWhite.copy(alpha = 0.14f),
            innerCard = InfinityObsidianSolidChip,
            innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
            edgeDivider = InfinityPureWhite.copy(alpha = 0.12f),
            isFrosted = false,
        )
    } else {
        FrostedChromeMaterial(
            container = scheme.surfaceContainerLowest,
            frostedTint = null,
            border = if (isHeader) null else hairline(scheme.outlineVariant, 0.56f),
            topHighlight = null,
            innerCard = scheme.surfaceContainerLow,
            innerCardBorder = hairline(scheme.outlineVariant, 0.45f, 0.5.dp),
            edgeDivider = scheme.outlineVariant.copy(alpha = 0.50f),
            isFrosted = false,
        )
    }
}

@Composable
fun rememberFrostedChromeMaterial(role: FrostedChromeRole): FrostedChromeMaterial = resolveFrostedChromeMaterial(
    role = role,
    scheme = MaterialTheme.colorScheme,
    dark = MaterialTheme.infinityDomainColors.isDark,
    surface = LocalInfinityMotionStyles.current.surface,
)

/**
 * 在容器最底层绘制实时模糊底 + 熏黑/冷白半透遮罩（或环境光实底），并叠加与输入浮岛同源的 1dp 顶缘镜面高光细边
 * （不向容器内部铺白色渐变，保持熏黑玻璃纯粹感）、单侧右缘亮边（用于贴屏幕左缘抽屉）与底缘分割细线。纯装饰不进入无障碍树。
 */
@Composable
fun FrostedChromeBackdropLayer(
    source: BackdropSource?,
    material: FrostedChromeMaterial,
    modifier: Modifier = Modifier,
    highlightHeight: Dp = 48.dp,
    cornerRadius: Dp = 0.dp,
    rightEdgeBorder: Boolean = false,
    bottomHairlineAlpha: Float = 0f,
) {
    val bottomAlphaState = rememberUpdatedState(bottomHairlineAlpha.coerceIn(0f, 1f))
    val borderStroke = material.border
    val borderColor = (borderStroke?.brush as? SolidColor)?.value ?: Color.Transparent
    val borderWidth = borderStroke?.width ?: 0.dp
    val edgeDivider = material.edgeDivider

    Box(modifier.clearAndSetSemantics {}) {
        if (material.frostedTint != null) {
            FrostedBackdrop(
                source = source,
                tint = material.frostedTint,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Spacer(Modifier.matchParentSize().background(material.container))
        }
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val hiPx = highlightHeight.toPx().coerceAtMost(size.height)
                    val hiStrokePx = 1.dp.toPx()
                    val hiHalf = hiStrokePx / 2f
                    val cornerPx = cornerRadius.toPx().coerceAtLeast(0f)
                    val topHighlightColor = material.topHighlight
                    val hiBrush = topHighlightColor?.takeIf { hiPx > 0f }?.let {
                        Brush.verticalGradient(
                            0f to it,
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = hiPx,
                        )
                    }
                    val rightBorderPx = if (rightEdgeBorder) borderWidth.toPx() else 0f
                    val hairlinePx = 0.75.dp.toPx()
                    onDrawBehind {
                        if (hiBrush != null) {
                            when {
                                cornerPx > 0f -> {
                                    // 对齐输入浮岛 composerIslandGlow：仅沿圆角外轮廓画 1dp 顶缘高光描边，容器内部零白雾。
                                    val insetRadius = (cornerPx - hiHalf).coerceAtLeast(0f)
                                    drawRoundRect(
                                        brush = hiBrush,
                                        topLeft = Offset(hiHalf, hiHalf),
                                        size = Size(
                                            (size.width - hiStrokePx).coerceAtLeast(0f),
                                            (size.height - hiStrokePx).coerceAtLeast(0f),
                                        ),
                                        cornerRadius = CornerRadius(insetRadius, insetRadius),
                                        style = Stroke(hiStrokePx),
                                    )
                                }
                                rightEdgeBorder -> {
                                    drawLine(
                                        brush = hiBrush,
                                        start = Offset(size.width - hiHalf, 0f),
                                        end = Offset(size.width - hiHalf, hiPx),
                                        strokeWidth = hiStrokePx,
                                    )
                                }
                                else -> {
                                    drawLine(
                                        color = requireNotNull(topHighlightColor),
                                        start = Offset(0f, hiHalf),
                                        end = Offset(size.width, hiHalf),
                                        strokeWidth = hiStrokePx,
                                    )
                                }
                            }
                        }
                        if (rightBorderPx > 0f && borderColor.alpha > 0f) {
                            drawLine(
                                color = borderColor,
                                start = Offset(size.width - rightBorderPx / 2f, 0f),
                                end = Offset(size.width - rightBorderPx / 2f, size.height),
                                strokeWidth = rightBorderPx,
                            )
                        }
                        val bottomAlpha = bottomAlphaState.value
                        if (bottomAlpha > 0f) {
                            drawLine(
                                color = edgeDivider.copy(alpha = edgeDivider.alpha * bottomAlpha),
                                start = Offset(0f, size.height - hairlinePx / 2f),
                                end = Offset(size.width, size.height - hairlinePx / 2f),
                                strokeWidth = hairlinePx,
                            )
                        }
                    }
                },
        )
    }
}

/**
 * 全站毛玻璃顶栏容器：顶栏本身用透明底色放在 [content] 里，背后画与输入浮岛同源的熏黑/冷白磨砂层
 * （或在环境光省电档下降级为熏黑实底）；正文需挂 [backdropSource] 并从顶栏下方延伸到屏幕顶端。
 */
@Composable
fun FrostedTopBar(
    source: BackdropSource?,
    modifier: Modifier = Modifier,
    bottomEdgeAlpha: Float = 0f,
    content: @Composable () -> Unit,
) {
    val material = rememberFrostedChromeMaterial(FrostedChromeRole.TOP_BAR)
    Box(modifier) {
        FrostedChromeBackdropLayer(
            source = source,
            material = material,
            highlightHeight = 0.dp,
            bottomHairlineAlpha = bottomEdgeAlpha,
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}
