package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.roundToInt

/*
 * 全站 Header 栏与内屏悬浮侧边栏熏黑毛玻璃对齐原型（v1，隔离于 debug 源码）：
 * 1. 全站共用一套黑曜石熏黑材质参数表（resolveObsidianChromeSpec），以输入浮岛为视觉基准：
 *    - 熏黑透视玻璃（FROSTED）：深色统一以 InfinityObsidianGlassTint (#050508) 76%~82% 沉底、1dp 玻璃亮边与顶缘内高光；
 *      浅色统一以 InfinityPureWhite (#FFFFFF) 78%~82% 冷白磨砂、浅描边与 92% 顶缘内高光；
 *    - 环境光（AMBIENT）：全部同步降级为无模糊省电实底（深色 InfinityObsidianSolidContainer #070709 / 芯片 #111217；浅色 surfaceContainerLowest）。
 * 2. 覆盖全量目标形态：
 *    - 右侧悬浮检视器（ChatInspectorCard，悬浮态 + 内部半透次级卡片与底栏页签）
 *    - 右缘收起胶囊条（ChatInspectorStrip）
 *    - 左侧展开态悬浮会话抽屉（HubThreePaneLayout 256dp 抽屉）
 *    - 全站 Header（ChatTopBar & 二级页 FrostedTopBar）
 *    - 底部输入浮岛（基准对照）
 */

internal enum class ObsidianChromeRole {
    COMPOSER_ISLAND,
    HEADER_BAR,
    FLOATING_INSPECTOR,
    FLOATING_STRIP,
    FLOATING_DRAWER,
}

internal data class ObsidianChromeSpec(
    val surface: Color,
    val frostedTint: Color?,
    val border: BorderStroke,
    val topHighlight: Color?,
    val innerCard: Color,
    val innerCardBorder: BorderStroke,
    val bottomEdge: Color,
)

internal fun resolveObsidianChromeSpec(
    role: ObsidianChromeRole,
    scheme: ColorScheme,
    dark: Boolean,
    surfaceStyle: ComposerSurfaceStyle,
): ObsidianChromeSpec {
    fun hairline(color: Color, alpha: Float, width: Dp = 1.dp) = BorderStroke(width, color.copy(alpha = alpha))

    if (surfaceStyle == ComposerSurfaceStyle.FROSTED) {
        return when (role) {
            ObsidianChromeRole.COMPOSER_ISLAND, ObsidianChromeRole.FLOATING_STRIP -> if (dark) {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.78f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.14f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.06f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
                    bottomEdge = InfinityPureWhite.copy(alpha = 0.08f),
                )
            } else {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.78f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    bottomEdge = scheme.outlineVariant.copy(alpha = 0.45f),
                )
            }

            ObsidianChromeRole.HEADER_BAR -> if (dark) {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.76f),
                    border = BorderStroke(0.dp, Color.Transparent),
                    topHighlight = null,
                    innerCard = InfinityPureWhite.copy(alpha = 0.07f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.10f, 0.5.dp),
                    bottomEdge = InfinityPureWhite.copy(alpha = 0.10f),
                )
            } else {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.78f),
                    border = BorderStroke(0.dp, Color.Transparent),
                    topHighlight = null,
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    bottomEdge = scheme.outlineVariant.copy(alpha = 0.45f),
                )
            }

            ObsidianChromeRole.FLOATING_INSPECTOR -> if (dark) {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.82f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.14f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.07f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
                    bottomEdge = InfinityPureWhite.copy(alpha = 0.10f),
                )
            } else {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.82f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.05f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.42f, 0.5.dp),
                    bottomEdge = scheme.outlineVariant.copy(alpha = 0.45f),
                )
            }

            ObsidianChromeRole.FLOATING_DRAWER -> if (dark) {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityObsidianGlassTint.copy(alpha = 0.82f),
                    border = hairline(InfinityPureWhite, 0.12f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.10f),
                    innerCard = InfinityPureWhite.copy(alpha = 0.08f),
                    innerCardBorder = hairline(InfinityPureWhite, 0.09f, 0.5.dp),
                    bottomEdge = InfinityPureWhite.copy(alpha = 0.12f),
                )
            } else {
                ObsidianChromeSpec(
                    surface = Color.Transparent,
                    frostedTint = InfinityPureWhite.copy(alpha = 0.82f),
                    border = hairline(scheme.outlineVariant, 0.45f),
                    topHighlight = InfinityPureWhite.copy(alpha = 0.92f),
                    innerCard = scheme.onSurface.copy(alpha = 0.06f),
                    innerCardBorder = hairline(scheme.outlineVariant, 0.45f, 0.5.dp),
                    bottomEdge = scheme.outlineVariant.copy(alpha = 0.45f),
                )
            }
        }
    }

    // AMBIENT 环境光（省电无模糊档）：统一同步降级为熏黑实底与高对比微光边线。
    val isHeader = role == ObsidianChromeRole.HEADER_BAR
    return if (dark) {
        ObsidianChromeSpec(
            surface = InfinityObsidianSolidContainer,
            frostedTint = null,
            border = if (isHeader) BorderStroke(0.dp, Color.Transparent) else hairline(InfinityPureWhite, 0.13f),
            topHighlight = if (isHeader) null else InfinityPureWhite.copy(alpha = 0.14f),
            innerCard = InfinityObsidianSolidChip,
            innerCardBorder = hairline(InfinityPureWhite, 0.08f, 0.5.dp),
            bottomEdge = InfinityPureWhite.copy(alpha = 0.12f),
        )
    } else {
        ObsidianChromeSpec(
            surface = scheme.surfaceContainerLowest,
            frostedTint = null,
            border = if (isHeader) BorderStroke(0.dp, Color.Transparent) else hairline(scheme.outlineVariant, 0.56f),
            topHighlight = null,
            innerCard = scheme.surfaceContainerLow,
            innerCardBorder = hairline(scheme.outlineVariant, 0.45f, 0.5.dp),
            bottomEdge = scheme.outlineVariant.copy(alpha = 0.50f),
        )
    }
}

/**
 * 静态 Preview 专用的毛玻璃还原容器：
 * - FROSTED 模式：按相对宿主坐标裁切一份施加柔和模糊的底层场景，再叠加 [spec.frostedTint]、
 *   顶部内高光与玻璃描边（完整模拟真机 GraphicsLayer + RenderEffect 效果）；
 * - AMBIENT 模式：直接使用 [spec.surface] 实底覆盖，彻底遮挡底层内容，形成清晰对比。
 */
@Composable
private fun ProtoFrostedSurface(
    spec: ObsidianChromeSpec,
    shape: Shape,
    stageWidth: Dp,
    stageHeight: Dp,
    offsetX: Dp,
    offsetY: Dp,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    highlightHeight: Dp = 44.dp,
    rightEdgeOnlyBorder: Boolean = false,
    bottomHairline: Boolean = false,
    backdropScene: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tint = spec.frostedTint
    val containerColor = if (tint != null) Color.Transparent else spec.surface
    val surfaceBorder = if (rightEdgeOnlyBorder) BorderStroke(0.dp, Color.Transparent) else spec.border

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = surfaceBorder,
        shadowElevation = shadowElevation,
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            if (tint != null) {
                // 基础页面底打底，防止边缘过透发灰；接着叠放局部偏移模糊采样层，再盖上熏黑遮罩。
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background))
                if (backdropScene != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .layout { measurable, constraints ->
                                val fullW = stageWidth.roundToPx()
                                val fullH = stageHeight.roundToPx()
                                val placeable = measurable.measure(Constraints.fixed(fullW, fullH))
                                layout(constraints.maxWidth, constraints.maxHeight) {
                                    placeable.place(-offsetX.roundToPx(), -offsetY.roundToPx())
                                }
                            }
                            .blur(18.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                    ) {
                        backdropScene()
                    }
                }
                Box(Modifier.matchParentSize().background(tint))
            }

            // 顶部受光内高光与可选单边线装饰
            val topHighlight = spec.topHighlight
            val borderColor = (spec.border.brush as? androidx.compose.ui.graphics.SolidColor)?.value ?: Color.Transparent
            val borderWidth = spec.border.width
            val bottomEdgeColor = spec.bottomEdge
            Spacer(
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val hiPx = highlightHeight.toPx().coerceAtMost(size.height)
                        val hiBrush = topHighlight?.let {
                            Brush.verticalGradient(
                                0f to it,
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = hiPx,
                            )
                        }
                        val strokePx = borderWidth.toPx()
                        val hairlinePx = 0.75.dp.toPx()
                        onDrawBehind {
                            if (hiBrush != null) {
                                drawRect(brush = hiBrush, size = androidx.compose.ui.geometry.Size(size.width, hiPx))
                            }
                            if (rightEdgeOnlyBorder && strokePx > 0f) {
                                drawLine(
                                    color = borderColor,
                                    start = Offset(size.width - strokePx / 2f, 0f),
                                    end = Offset(size.width - strokePx / 2f, size.height),
                                    strokeWidth = strokePx,
                                )
                            }
                            if (bottomHairline) {
                                drawLine(
                                    color = bottomEdgeColor,
                                    start = Offset(0f, size.height - hairlinePx / 2f),
                                    end = Offset(size.width, size.height - hairlinePx / 2f),
                                    strokeWidth = hairlinePx,
                                )
                            }
                        }
                    },
            )

            content()
        }
    }
}

/* ─────────────────────────── 内屏场景总装 ─────────────────────────── */

@Composable
private fun InnerScreenPrototypeStage(
    mode: InfinityThemeMode,
    surfaceStyle: ComposerSurfaceStyle,
    inspectorOpen: Boolean,
    drawerOpen: Boolean,
) {
    val stageWidth = 840.dp
    val stageHeight = 760.dp
    val stripWidth = 40.dp
    val detailX = stripWidth
    val detailWidth = stageWidth - stripWidth // 800.dp
    val headerHeight = 64.dp

    // 与正式实现一致：会话列表 maxWidth = 720dp 且输入浮岛水平外边距 12dp -> 浮岛宽 696dp，右缘在 detail 内 748dp（全局 x = 788dp）
    val chatMax = InfinitySpacing.ChatMaxWidth
    val composerWidth = chatMax - 24.dp // 696.dp
    val composerX = detailX + (detailWidth - composerWidth) / 2 // 92.dp
    val composerHeight = 132.dp
    val composerBottom = 12.dp
    val composerY = stageHeight - composerBottom - composerHeight // 616.dp

    val inspectorWidth = 360.dp
    val inspectorX = composerX + composerWidth - inspectorWidth // 428.dp（右缘与浮岛 788dp 严丝合缝对齐）
    val inspectorY = headerHeight + 8.dp // 72.dp
    val inspectorHeight = composerY - 8.dp - inspectorY // 536.dp

    val stripX = stageWidth - 8.dp - 48.dp // 784.dp
    val stripHeight = 296.dp
    val stripY = composerY - 8.dp - stripHeight // 312.dp

    val drawerWidth = 256.dp

    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
            motion = InfinityMotionStyles(surface = surfaceStyle),
        ),
    ) {
        val scheme = MaterialTheme.colorScheme
        val dark = MaterialTheme.infinityDomainColors.isDark
        val headerSpec = resolveObsidianChromeSpec(ObsidianChromeRole.HEADER_BAR, scheme, dark, surfaceStyle)
        val islandSpec = resolveObsidianChromeSpec(ObsidianChromeRole.COMPOSER_ISLAND, scheme, dark, surfaceStyle)
        val inspectorSpec = resolveObsidianChromeSpec(ObsidianChromeRole.FLOATING_INSPECTOR, scheme, dark, surfaceStyle)
        val stripSpec = resolveObsidianChromeSpec(ObsidianChromeRole.FLOATING_STRIP, scheme, dark, surfaceStyle)
        val drawerSpec = resolveObsidianChromeSpec(ObsidianChromeRole.FLOATING_DRAWER, scheme, dark, surfaceStyle)

        val chatBackdropContent: @Composable () -> Unit = {
            ProtoInnerBackdropLayer(
                stripWidth = stripWidth,
                detailWidth = detailWidth,
                stageWidth = stageWidth,
                stageHeight = stageHeight,
            )
        }

        Box(Modifier.size(stageWidth, stageHeight).background(scheme.background)) {
            // 1. 最底层：左侧 40dp 极简会话条 + 右侧 800dp 完整会话滚动层（向上穿透 Header、向右穿透检视器、向下穿透浮岛）
            chatBackdropContent()

            // 2. 顶部 Header（覆盖 detailWidth 800dp，位于 x=40dp, y=0dp）
            ProtoFrostedSurface(
                spec = headerSpec,
                shape = RectangleShape,
                stageWidth = stageWidth,
                stageHeight = stageHeight,
                offsetX = detailX,
                offsetY = 0.dp,
                highlightHeight = 0.dp,
                bottomHairline = true,
                backdropScene = chatBackdropContent,
                modifier = Modifier
                    .offset(x = detailX, y = 0.dp)
                    .size(detailWidth, headerHeight),
            ) {
                ProtoChatHeaderContent(
                    spec = headerSpec,
                    inspectorOpen = inspectorOpen,
                    drawerOpen = drawerOpen,
                )
            }

            // 3. 右侧悬浮检视器 或 收起态胶囊条
            if (inspectorOpen) {
                ProtoFrostedSurface(
                    spec = inspectorSpec,
                    shape = RoundedCornerShape(20.dp),
                    stageWidth = stageWidth,
                    stageHeight = stageHeight,
                    offsetX = inspectorX,
                    offsetY = inspectorY,
                    shadowElevation = 8.dp,
                    highlightHeight = 56.dp,
                    backdropScene = chatBackdropContent,
                    modifier = Modifier
                        .offset(x = inspectorX, y = inspectorY)
                        .size(inspectorWidth, inspectorHeight),
                ) {
                    ProtoFloatingInspectorContent(spec = inspectorSpec)
                }
            } else {
                ProtoFrostedSurface(
                    spec = stripSpec,
                    shape = RoundedCornerShape(24.dp),
                    stageWidth = stageWidth,
                    stageHeight = stageHeight,
                    offsetX = stripX,
                    offsetY = stripY,
                    shadowElevation = 6.dp,
                    highlightHeight = 36.dp,
                    backdropScene = chatBackdropContent,
                    modifier = Modifier
                        .offset(x = stripX, y = stripY)
                        .size(48.dp, stripHeight),
                ) {
                    ProtoInspectorStripContent(spec = stripSpec)
                }
            }

            // 4. 底部熏黑输入浮岛（全系列对齐基准）
            ProtoFrostedSurface(
                spec = islandSpec,
                shape = RoundedCornerShape(28.dp),
                stageWidth = stageWidth,
                stageHeight = stageHeight,
                offsetX = composerX,
                offsetY = composerY,
                shadowElevation = 8.dp,
                highlightHeight = 40.dp,
                backdropScene = chatBackdropContent,
                modifier = Modifier
                    .offset(x = composerX, y = composerY)
                    .size(composerWidth, composerHeight),
            ) {
                ProtoComposerIslandContent(spec = islandSpec)
            }

            // 5. 左侧 256dp 展开态悬浮抽屉（叠在最上层，带半透压暗 scrim + 熏黑毛玻璃抽屉本体）
            if (drawerOpen) {
                val scrimAlpha = if (surfaceStyle == ComposerSurfaceStyle.FROSTED) {
                    if (dark) 0.24f else 0.18f
                } else 0.32f
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scheme.scrim.copy(alpha = scrimAlpha)),
                )
                ProtoFrostedSurface(
                    spec = drawerSpec,
                    shape = RectangleShape,
                    stageWidth = stageWidth,
                    stageHeight = stageHeight,
                    offsetX = 0.dp,
                    offsetY = 0.dp,
                    shadowElevation = 10.dp,
                    highlightHeight = 48.dp,
                    rightEdgeOnlyBorder = true,
                    backdropScene = chatBackdropContent,
                    modifier = Modifier
                        .offset(x = 0.dp, y = 0.dp)
                        .size(drawerWidth, stageHeight),
                ) {
                    ProtoFloatingDrawerContent(spec = drawerSpec)
                }
            }
        }
    }
}

/* ─────────────────────────── 底层丰富内容（提供真实穿底色彩与文字结构） ─────────────────────────── */

@Composable
private fun ProtoInnerBackdropLayer(
    stripWidth: Dp,
    detailWidth: Dp,
    stageWidth: Dp,
    stageHeight: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Row(Modifier.size(stageWidth, stageHeight).background(scheme.background)) {
        // 左侧 40dp 极简图形会话条（也作为抽屉背后的底景之一）
        Column(
            modifier = Modifier
                .width(stripWidth)
                .fillMaxHeight()
                .background(scheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.onSurface.copy(alpha = 0.06f))
                    .border(0.75.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ViewSidebar,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp).graphicsLayer { scaleX = -1f },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                color = scheme.outlineVariant.copy(alpha = 0.36f),
            )
            // 电路家族节点示意
            ProtoStripTopologyNodes()
        }

        // 右侧会话列表区（宽 800dp，垂直排布故意滑过顶部 Header、穿过检视器和浮岛）
        Box(
            modifier = Modifier
                .width(detailWidth)
                .fillMaxHeight()
                .background(scheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 与列表左侧分界线
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(scheme.outlineVariant.copy(alpha = 0.45f)),
            )

            Column(
                modifier = Modifier
                    .widthIn(max = InfinitySpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                // 0. 已滑到顶栏 Header 下方的上一条带色块消息与工具进度条（明显穿出 Header 0..64dp 区域）
                Row(
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .height(66.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        scheme.primary.copy(alpha = 0.72f),
                                        scheme.tertiary.copy(alpha = 0.65f),
                                    ),
                                ),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "已同步三视图造型与 ComfyUI H3 工作流节点，准备开始联调…",
                            style = InfinityTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onPrimary,
                            maxLines = 2,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .height(66.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(domain.warning.copy(alpha = 0.62f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "KeyFrame · 4 帧就绪",
                            style = InfinityTypography.labelMedium,
                            color = scheme.onPrimaryContainer,
                            maxLines = 2,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 1. 用户提示气泡（靠右，恰好一部分落入 Header 下沿之后、右边靠近检视器）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = scheme.primaryContainer,
                        modifier = Modifier.widthIn(max = 460.dp),
                    ) {
                        Text(
                            text = "请对比陆昭新神州飞鱼服的三视图纹理，并把街口糕点铺清晨戏份的色温调到暖金晨雾感。",
                            style = InfinityTypography.bodyMedium,
                            color = scheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2. 两张并行影视分镜 / 资产剧照渲染卡（横跨左右，右侧 2 张会真实透进右侧检视器熏黑毛玻璃里！）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProtoSceneVisualCard(
                        title = "S01 · 新神州承天街清晨（暖琥珀）",
                        subtitle = "KeyFrame 01 · ComfyUI H3 720p",
                        primaryGlow = domain.warning,
                        secondaryGlow = scheme.tertiary,
                        accentBadge = domain.signalOn,
                        modifier = Modifier.weight(1f).height(176.dp),
                    )
                    ProtoSceneVisualCard(
                        title = "陆昭 · 玄鳞飞鱼服 V3（冷青蓝）",
                        subtitle = "Asset Ref · 正/侧/背 3 视图",
                        primaryGlow = domain.info,
                        secondaryGlow = scheme.primary,
                        accentBadge = domain.ragIndexing,
                        modifier = Modifier.weight(1f).height(176.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 3. Assistant Markdown 回复 + 高亮代码块
                Text(
                    text = "三视图沿用已批准的 V3 造型基准，肩部金纹与风衣下摆保留冷黑质感；街口晨光戏改为低色温漫射暖光，压暗暗部避免与角色黑甲粘连。",
                    style = InfinityTypography.bodyMedium,
                    color = scheme.onSurface,
                )

                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "workflow_override.json",
                            style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = domain.codeComment,
                        )
                        Row {
                            Text("\"cfg_scale\": ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeKeyword)
                            Text("6.2,  ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeLiteral)
                            Text("\"lighting\": ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeKeyword)
                            Text("\"golden_morning_mist\"", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeString)
                        }
                        Row {
                            Text("\"motion_bucket\": ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeKeyword)
                            Text("128, ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeLiteral)
                            Text("\"ref_mode\": ", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeFunction)
                            Text("\"three_view_anchor\"", style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace), color = domain.codeString)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 4. 底部高饱和工具结果卡片（专门滑入底部输入浮岛与右侧检视器下方，确保底栏透视清晰可见！）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.tertiaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f).height(180.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "镜头 02 生成队列已就绪 · 19.8s",
                                style = InfinityTypography.titleSmall,
                                color = scheme.onTertiaryContainer,
                            )
                            Text(
                                "已完成首尾帧锚定与参考音轨对齐，待导演终审后发送执行节点。",
                                style = InfinityTypography.bodySmall,
                                color = scheme.onTertiaryContainer.copy(alpha = 0.88f),
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.primary.copy(alpha = 0.35f),
                        modifier = Modifier.weight(1f).height(180.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "工作区改动快照 · 20 处变更",
                                style = InfinityTypography.titleSmall,
                                color = scheme.onSurface,
                            )
                            Text(
                                "lu-zhao-morning-greeting-20s/shots/shot-02.json 已更新材质锚点与运镜描述。",
                                style = InfinityTypography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoStripTopologyNodes() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.onSurfaceVariant))
        }
        // 选中高亮节点
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.primaryContainer.copy(alpha = 0.55f))
                .border(1.dp, scheme.primary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(domain.warning))
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.outline))
        }
    }
}

@Composable
private fun ProtoSceneVisualCard(
    title: String,
    subtitle: String,
    primaryGlow: Color,
    secondaryGlow: Color,
    accentBadge: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.58f),
                        secondaryGlow.copy(alpha = 0.42f),
                        scheme.surfaceContainerHighest,
                    ),
                ),
            )
            .border(0.75.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        // 抽象电影构图光斑，使毛玻璃覆盖后的渐变色彩极具层次
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = primaryGlow.copy(alpha = 0.55f),
                radius = size.minDimension * 0.46f,
                center = Offset(size.width * 0.28f, size.height * 0.36f),
            )
            drawCircle(
                color = secondaryGlow.copy(alpha = 0.45f),
                radius = size.minDimension * 0.38f,
                center = Offset(size.width * 0.78f, size.height * 0.55f),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accentBadge))
            Text("参考已锁定", style = InfinityTypography.labelSmall, color = scheme.onSurface)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, scheme.surfaceContainerLowest.copy(alpha = 0.84f)),
                    ),
                )
                .padding(12.dp),
        ) {
            Text(title, style = InfinityTypography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/* ─────────────────────────── 玻璃表面内部组件 ─────────────────────────── */

@Composable
private fun ProtoChatHeaderContent(
    spec: ObsidianChromeSpec,
    inspectorOpen: Boolean,
    drawerOpen: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {}) {
            Icon(
                imageVector = Icons.Rounded.Start,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.AccountTree, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                Text(
                    text = "陆昭晨间问候 20s 测试片 · 分镜定稿",
                    style = InfinityTypography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = spec.innerCard,
                    border = spec.innerCardBorder,
                ) {
                    Text(
                        text = "Linux",
                        style = InfinityTypography.labelSmall,
                        color = domain.signalOn,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Icon(Icons.Rounded.FolderOpen, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                Text("Personal-Archive", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                Text("·", style = InfinityTypography.labelMedium, color = scheme.outline)
                Icon(Icons.Rounded.AccountTree, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                Text("main · 20 处改动", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (inspectorOpen && !drawerOpen) {
            Surface(
                shape = CircleShape,
                color = spec.innerCard,
                border = spec.innerCardBorder,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PushPin, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = {}) {
            Icon(Icons.Rounded.MoreVert, null, tint = scheme.onSurface)
        }
    }
}

@Composable
private fun ProtoFloatingInspectorContent(spec: ObsidianChromeSpec) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Column(Modifier.fillMaxSize()) {
        // 顶部制作面板标题栏 + 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "陆昭晨间问候 20s 测试片",
                    style = InfinityTypography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "电影 · 4 个镜头 · 16:9 · H3 多参",
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = CircleShape,
                color = scheme.primary,
                modifier = Modifier.height(30.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("发送 1", style = InfinityTypography.labelMedium, color = scheme.onPrimary)
                }
            }
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.MoreVert, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        // 页签条（总览 / 剧本 / 资产 / 分镜）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("总览" to false, "剧本" to true, "资产 5" to false, "分镜" to false, "出片" to false).forEach { (label, selected) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) spec.innerCard else Color.Transparent,
                    border = if (selected) spec.innerCardBorder else BorderStroke(0.dp, Color.Transparent),
                ) {
                    Text(
                        text = label,
                        style = InfinityTypography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = spec.bottomEdge.copy(alpha = 0.65f))

        // 主体半透卡片内容（不用实心厚底块，透出背后毛玻璃质地）
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 卡片 1：梗概半透卡
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = spec.innerCard,
                border = spec.innerCardBorder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("第一集 · 清晨巡街", style = InfinityTypography.labelMedium, color = scheme.primary)
                    Text(
                        "2099年清晨，承天市城东老街，灵调科组长陆昭巡街经过临街糕点铺，掌柜端出刚出锅的枣泥酥…",
                        style = InfinityTypography.bodySmall,
                        color = scheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 卡片 2：分镜审核卡（带批准/待审胶囊）
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = spec.innerCard,
                border = spec.innerCardBorder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("S01 · 糕点铺暖雾中景", style = InfinityTypography.labelLarge, color = scheme.onSurface)
                            Text("4.8s · 参考角色 V3 + 暖雾景别", style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = domain.warning.copy(alpha = 0.18f),
                            border = BorderStroke(0.5.dp, domain.warning.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                "待审",
                                style = InfinityTypography.labelSmall,
                                color = domain.warning,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("否决", style = InfinityTypography.labelMedium, color = scheme.error)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = spec.innerCard,
                            border = spec.innerCardBorder,
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("建议", style = InfinityTypography.labelMedium, color = scheme.onSurface)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.primary,
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("批准", style = InfinityTypography.labelMedium, color = scheme.onPrimary)
                            }
                        }
                    }
                }
            }

            // 卡片 3：后台同步进度提示
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = spec.innerCard,
                border = spec.innerCardBorder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = domain.signalOn, modifier = Modifier.size(16.dp))
                    Text(
                        "三视图正/侧/背面资产绑定完成",
                        style = InfinityTypography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text("100%", style = InfinityTypography.labelSmall, color = domain.signalOn)
                }
            }
        }

        HorizontalDivider(color = spec.bottomEdge.copy(alpha = 0.7f))

        // 底栏页签 + 加宽 / 收起
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProtoInspectorTabIcon(Icons.Rounded.Terminal, selected = false, spec = spec)
            ProtoInspectorTabIcon(Icons.Rounded.FolderOpen, selected = false, spec = spec)
            ProtoInspectorTabIcon(Icons.Rounded.AccountTree, selected = false, spec = spec)
            // 选中页签：使用 spec.innerCard + 细边框对齐玻璃质感
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = spec.innerCard,
                border = spec.innerCardBorder,
                modifier = Modifier.height(38.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.MovieCreation, null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                    Text("制作", style = InfinityTypography.labelLarge, color = scheme.onSurface)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.OpenInFull, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.KeyboardDoubleArrowRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProtoInspectorTabIcon(icon: ImageVector, selected: Boolean, spec: ObsidianChromeSpec) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) spec.innerCard else Color.Transparent,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ProtoInspectorStripContent(spec: ObsidianChromeSpec) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Icons.Rounded.Terminal to false,
                Icons.Rounded.FolderOpen to false,
                Icons.Rounded.AccountTree to false,
                Icons.Rounded.Public to false,
                Icons.Rounded.MovieCreation to true,
            ).forEach { (icon, badged) ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (badged) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(spec.innerCard)
                                .border(spec.innerCardBorder.width, spec.innerCardBorder.brush, CircleShape),
                        )
                    }
                    Icon(icon, null, tint = if (badged) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    if (badged) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(domain.warning),
                        )
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(modifier = Modifier.width(24.dp).padding(vertical = 4.dp), color = spec.bottomEdge)
            IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.KeyboardDoubleArrowLeft, null, tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProtoFloatingDrawerContent(spec: ObsidianChromeSpec) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 12.dp)) {
        // 抽屉页头
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("对话", style = InfinityTypography.titleLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Add, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = spec.bottomEdge.copy(alpha = 0.6f))

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "置顶会话",
                style = InfinityTypography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            ProtoDrawerSessionRow(
                title = "陆昭晨间问候 20s 测试片",
                meta = "分支 · 制作进行中",
                selected = true,
                statusColor = scheme.primary,
                spec = spec,
            )
            ProtoDrawerSessionRow(
                title = "华夏编年史 · 角色 Canon 总库",
                meta = "主会话 · 已锁定",
                selected = false,
                statusColor = domain.signalOn,
                spec = spec,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "近期会话",
                style = InfinityTypography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            ProtoDrawerSessionRow(
                title = "ComfyUI H3 多参节点排障",
                meta = "12 分钟前",
                selected = false,
                statusColor = domain.warning,
                spec = spec,
            )
            ProtoDrawerSessionRow(
                title = "内屏悬浮侧栏与毛玻璃方案",
                meta = "刚刚",
                selected = false,
                statusColor = scheme.outline,
                spec = spec,
            )
            ProtoDrawerSessionRow(
                title = "Seedance 云端模型入参对比",
                meta = "昨天",
                selected = false,
                statusColor = scheme.outline,
                spec = spec,
            )
        }

        // 底部固定按钮提示（还原三列）
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = spec.innerCard,
            border = spec.innerCardBorder,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ViewSidebar, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text("固定展开为三列布局", style = InfinityTypography.labelMedium, color = scheme.onSurface)
            }
        }
    }
}

@Composable
private fun ProtoDrawerSessionRow(
    title: String,
    meta: String,
    selected: Boolean,
    statusColor: Color,
    spec: ObsidianChromeSpec,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) spec.innerCard else Color.Transparent,
        border = if (selected) spec.innerCardBorder else BorderStroke(0.dp, Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = InfinityTypography.bodyMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProtoComposerIslandContent(spec: ObsidianChromeSpec) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Column(Modifier.fillMaxSize()) {
        // 顶部精简运行坞（带绿色点阵微示意）
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(domain.signalOn))
            Spacer(Modifier.width(8.dp))
            Text(
                "工具执行完成 · 2.4s · 31% 上下文",
                style = InfinityTypography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 迷你点阵波形条
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(0.4f, 0.85f, 0.6f, 1.0f, 0.7f, 0.45f, 0.9f, 0.5f).forEach { h ->
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(14.dp * h)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(domain.signalOn.copy(alpha = 0.75f)),
                    )
                }
            }
        }
        HorizontalDivider(color = spec.bottomEdge.copy(alpha = 0.4f))

        // 输入占位
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("给 Infinity 发消息…", style = InfinityTypography.bodyLarge, color = scheme.onSurfaceVariant)
        }

        // 底部工具芯片行
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = spec.innerCard, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Surface(shape = CircleShape, color = spec.innerCard, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Shield, null, tint = domain.warning, modifier = Modifier.size(18.dp))
                }
            }
            Surface(shape = CircleShape, color = spec.innerCard, modifier = Modifier.height(34.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = scheme.primary, modifier = Modifier.size(15.dp))
                    Text("DeepSeek V4.1", style = InfinityTypography.labelMedium, color = scheme.onSurface)
                }
            }
            Surface(shape = CircleShape, color = spec.innerCard, modifier = Modifier.height(34.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Text("31%", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = scheme.onSurface, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowUpward, null, tint = scheme.surface, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/* ─────────────────────────── 二级页 / 设置页 Header 三态对照 ─────────────────────────── */

@Composable
private fun SubpageHeaderCompareBand(
    bandTitle: String,
    mode: InfinityThemeMode,
    surfaceStyle: ComposerSurfaceStyle,
    modifier: Modifier = Modifier,
) {
    val bandWidth = 411.dp
    val bandHeight = 266.dp
    val headerHeight = 56.dp

    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
            motion = InfinityMotionStyles(surface = surfaceStyle),
        ),
    ) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val headerSpec = resolveObsidianChromeSpec(ObsidianChromeRole.HEADER_BAR, scheme, domain.isDark, surfaceStyle)

        val backdrop: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .size(bandWidth, bandHeight)
                    .background(scheme.background)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 故意压在顶栏后方的高彩设定卡（模拟用户向上滚动穿透顶栏）
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    scheme.primaryContainer.copy(alpha = 0.85f),
                                    domain.warning.copy(alpha = 0.65f),
                                    scheme.tertiaryContainer.copy(alpha = 0.80f),
                                ),
                            ),
                        )
                        .padding(14.dp),
                ) {
                    Text(
                        "穿透顶栏滚动内容：外观 · 主题与熏黑玻璃材质卡片",
                        style = InfinityTypography.titleSmall,
                        color = scheme.onPrimaryContainer,
                    )
                }

                // 下方的正常设置分组卡片
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(bandTitle, style = InfinityTypography.titleSmall, color = scheme.onSurface)
                        Text(
                            "全站 FrostedTopBar（会话页 + 设置页 + 通用二级页）同步遵循同一黑曜石材质与环境光省电降级策略。",
                            style = InfinityTypography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.primaryContainer,
                            ) {
                                Text(
                                    text = if (surfaceStyle == ComposerSurfaceStyle.FROSTED) "FROSTED 熏黑玻璃" else "AMBIENT 省电实底",
                                    style = InfinityTypography.labelSmall,
                                    color = scheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = when {
                                        !domain.isDark -> "Light #FFFFFF"
                                        surfaceStyle == ComposerSurfaceStyle.FROSTED -> "Dark #050508 @ 76%"
                                        else -> "Dark Solid #070709"
                                    },
                                    style = InfinityTypography.labelSmall,
                                    color = scheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(modifier.size(bandWidth, bandHeight).clip(RectangleShape)) {
            backdrop()

            ProtoFrostedSurface(
                spec = headerSpec,
                shape = RectangleShape,
                stageWidth = bandWidth,
                stageHeight = bandHeight,
                offsetX = 0.dp,
                offsetY = 0.dp,
                highlightHeight = 0.dp,
                bottomHairline = true,
                backdropScene = backdrop,
                modifier = Modifier.fillMaxWidth().height(headerHeight),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = scheme.onSurface)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = bandTitle,
                            style = InfinityTypography.titleLarge,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Tune, null, tint = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.MoreVert, null, tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── 6 组隔离高保真 Preview 入口 ─────────────────────────── */

@Preview(name = "1. 内屏深色 熏黑毛玻璃主视图（Header+悬浮检视器+浮岛）", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun proto1InnerFloatingInspectorDarkPreview() {
    InnerScreenPrototypeStage(
        mode = InfinityThemeMode.DARK,
        surfaceStyle = ComposerSurfaceStyle.FROSTED,
        inspectorOpen = true,
        drawerOpen = false,
    )
}

@Preview(name = "2. 内屏深色 熏黑毛玻璃悬浮抽屉与右缘胶囊条", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun proto2InnerFloatingDrawerAndStripDarkPreview() {
    InnerScreenPrototypeStage(
        mode = InfinityThemeMode.DARK,
        surfaceStyle = ComposerSurfaceStyle.FROSTED,
        inspectorOpen = false,
        drawerOpen = true,
    )
}

@Preview(name = "3. 内屏深色 环境光省电档同步实底降级（#070709）", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun proto3InnerFloatingAmbientFallbackDarkPreview() {
    InnerScreenPrototypeStage(
        mode = InfinityThemeMode.DARK,
        surfaceStyle = ComposerSurfaceStyle.AMBIENT,
        inspectorOpen = true,
        drawerOpen = false,
    )
}

@Preview(name = "4. 内屏浅色 冷白磨砂主视图（Header+悬浮检视器+浮岛）", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun proto4InnerFloatingInspectorLightPreview() {
    InnerScreenPrototypeStage(
        mode = InfinityThemeMode.LIGHT,
        surfaceStyle = ComposerSurfaceStyle.FROSTED,
        inspectorOpen = true,
        drawerOpen = false,
    )
}

@Preview(name = "5. 内屏浅色 冷白磨砂悬浮抽屉与右缘胶囊条", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun proto5InnerFloatingDrawerLightPreview() {
    InnerScreenPrototypeStage(
        mode = InfinityThemeMode.LIGHT,
        surfaceStyle = ComposerSurfaceStyle.FROSTED,
        inspectorOpen = false,
        drawerOpen = true,
    )
}

@Preview(name = "6. 全站二级页 FrostedTopBar 三态对照（深熏黑/环境光/浅冷白）", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Composable
fun proto6SubpageFrostedHeaderComparePreview() {
    Column(modifier = Modifier.width(411.dp).height(820.dp)) {
        SubpageHeaderCompareBand(
            bandTitle = "外观 · 熏黑毛玻璃顶栏",
            mode = InfinityThemeMode.DARK,
            surfaceStyle = ComposerSurfaceStyle.FROSTED,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        SubpageHeaderCompareBand(
            bandTitle = "外观 · 环境光实底顶栏",
            mode = InfinityThemeMode.DARK,
            surfaceStyle = ComposerSurfaceStyle.AMBIENT,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        SubpageHeaderCompareBand(
            bandTitle = "Appearance · Frosted",
            mode = InfinityThemeMode.LIGHT,
            surfaceStyle = ComposerSurfaceStyle.FROSTED,
            modifier = Modifier.weight(1f),
        )
    }
}
