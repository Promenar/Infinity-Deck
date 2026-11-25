package com.promenar.infinity.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlin.math.roundToInt

/** 弹出层优先出现在锚点的哪一侧；放不下时换到另一侧。 */
enum class InfinityPopoverSide { Above, Below }

/**
 * 弹出层与锚点的起点或终点对齐（随布局方向镜像）。
 * [Auto] 按锚点所在的半边决定：锚点在窗口起点一半时与锚点起点对齐，否则与终点对齐，弹出层总朝窗口中部展开。
 */
enum class InfinityPopoverAlign { Start, End, Auto }

/** 把 [InfinityPopoverAlign.Auto] 换算成起点或终点对齐；其余取值原样返回。 */
fun resolveInfinityPopoverAlign(align: InfinityPopoverAlign, anchor: IntRect, windowWidthPx: Int, direction: LayoutDirection): InfinityPopoverAlign {
    if (align != InfinityPopoverAlign.Auto) return align
    val inLeftHalf = anchor.center.x * 2 < windowWidthPx
    return if (inLeftHalf == (direction == LayoutDirection.Ltr)) InfinityPopoverAlign.Start else InfinityPopoverAlign.End
}

/**
 * 触发控件的位置记录：在控件上加 [Modifier.infinityPopoverAnchor]，每次布局后记下它在窗口中的边界。
 * 弹出层可以渲染在离控件很远的地方（例如由路由统一渲染的面板），仍按记录的边界贴着控件定位。
 *
 * 弹出层获得焦点时，点外部会先关闭弹出层；若这一下正好点在触发控件上，控件随后的点按会再次打开它。
 * 触发控件的点按里先问 [consumeRecentDismiss]：刚被点外部关闭时返回 true，此时不再打开，实现“再点一次即关闭”。
 */
@Stable
class InfinityPopoverAnchor {
    var bounds by mutableStateOf<IntRect?>(null)
        internal set
    private var dismissedAt = 0L

    internal fun markDismissed() {
        dismissedAt = SystemClock.uptimeMillis()
    }

    /**
     * 一个面板由多个控件触发（例如每轮回复各有一个记忆状态）时，点按的那个控件把自己的位置交给面板共用的锚点。
     */
    fun pointAt(source: InfinityPopoverAnchor) {
        bounds = source.bounds
    }

    fun consumeRecentDismiss(): Boolean {
        val recent = SystemClock.uptimeMillis() - dismissedAt < RE_OPEN_GUARD_MS
        dismissedAt = 0L
        return recent
    }

    private companion object {
        const val RE_OPEN_GUARD_MS = 400L
    }
}

@Composable
fun rememberInfinityPopoverAnchor(): InfinityPopoverAnchor = remember { InfinityPopoverAnchor() }

fun Modifier.infinityPopoverAnchor(anchor: InfinityPopoverAnchor): Modifier = onGloballyPositioned { coordinates ->
    anchor.bounds = coordinates.boundsInWindow().toIntRect()
}

private fun Rect.toIntRect() = IntRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/**
 * 宽窗口下把短选择面板贴着触发控件弹出时的样式：[anchor] 是触发控件，[side] 与 [align] 决定首选位置，[width] 为弹出层宽度。
 * 交给 [InfinityBottomSheet] / [InfinityModalSheet] 后，窄窗口仍是底部上滑面板，宽窗口且量到锚点时改为弹出层。
 */
@Stable
data class InfinityPopoverSpec(
    val anchor: InfinityPopoverAnchor,
    val side: InfinityPopoverSide = InfinityPopoverSide.Below,
    val align: InfinityPopoverAlign = InfinityPopoverAlign.End,
    val width: Dp = InfinityPopoverDefaultWidth,
)

val InfinityPopoverDefaultWidth = 320.dp

/** 面板在当前窗口与锚点状态下的展示形式。 */
enum class InfinitySheetPresentation { BottomSheet, CenteredDialog, Popover }

/** 窄窗口一律底部上滑面板；宽窗口有已量到位置的锚点时贴着锚点弹出，否则居中对话框。 */
fun infinitySheetPresentation(wideWindow: Boolean, anchorMeasured: Boolean): InfinitySheetPresentation = when {
    !wideWindow -> InfinitySheetPresentation.BottomSheet
    anchorMeasured -> InfinitySheetPresentation.Popover
    else -> InfinitySheetPresentation.CenteredDialog
}

/** 弹出层可用的竖向范围（窗口坐标，像素）、横向边距与和锚点的间隔。 */
data class InfinityPopoverBounds(
    val topLimitPx: Int,
    val bottomLimitPx: Int,
    val marginPx: Int,
    val gapPx: Int,
) {
    fun spaceAbove(anchor: IntRect): Int = (anchor.top - gapPx - topLimitPx).coerceAtLeast(0)
    fun spaceBelow(anchor: IntRect): Int = (bottomLimitPx - anchor.bottom - gapPx).coerceAtLeast(0)

    /** 弹出层最多可用的高度：锚点上下两侧中较大的一侧，保证不遮住锚点。 */
    fun availableHeight(anchor: IntRect): Int = maxOf(spaceAbove(anchor), spaceBelow(anchor))
}

/**
 * 优先放在锚点 [side] 一侧并按 [align] 对齐；首选一侧放不下时换到另一侧；两侧都放不下时放在空间较大的一侧，
 * 竖向始终处于 [bounds] 的安全范围内，横向保留边距。[anchorOverride] 不为空时用它代替 Popup 所在布局的边界。
 */
class InfinityAnchorPositionProvider(
    private val bounds: InfinityPopoverBounds,
    private val side: InfinityPopoverSide = InfinityPopoverSide.Above,
    private val align: InfinityPopoverAlign = InfinityPopoverAlign.End,
    private val anchorOverride: IntRect? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = anchorOverride ?: anchorBounds
        val margin = bounds.marginPx
        val resolved = resolveInfinityPopoverAlign(align, anchor, windowSize.width, layoutDirection)
        val alignRight = (resolved == InfinityPopoverAlign.End) == (layoutDirection == LayoutDirection.Ltr)
        val preferredX = if (alignRight) anchor.right - popupContentSize.width else anchor.left
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = preferredX.coerceIn(margin.coerceAtMost(maxX), maxX)
        val height = popupContentSize.height
        val above = anchor.top - bounds.gapPx - height
        val below = anchor.bottom + bounds.gapPx
        val spaceAbove = bounds.spaceAbove(anchor)
        val spaceBelow = bounds.spaceBelow(anchor)
        val clampedAbove = maxOf(bounds.topLimitPx, above)
        val clampedBelow = minOf(below, (bounds.bottomLimitPx - height).coerceAtLeast(bounds.topLimitPx))
        val y = when (side) {
            InfinityPopoverSide.Above -> when {
                height <= spaceAbove -> above
                height <= spaceBelow -> below
                spaceAbove >= spaceBelow -> clampedAbove
                else -> clampedBelow
            }
            InfinityPopoverSide.Below -> when {
                height <= spaceBelow -> below
                height <= spaceAbove -> above
                spaceBelow >= spaceAbove -> clampedBelow
                else -> clampedAbove
            }
        }
        return IntOffset(x, y)
    }
}

/** 当前窗口的弹出层安全范围：避开状态栏、导航栏、刘海与输入法，四周留 16dp。 */
@Composable
fun rememberInfinityPopoverBounds(): InfinityPopoverBounds {
    val density = LocalDensity.current
    val marginPx = with(density) { InfinitySpacing.Large.roundToPx() }
    val gapPx = with(density) { InfinitySpacing.XSmall.roundToPx() }
    val safeDrawing = WindowInsets.safeDrawing
    val rootHeightPx = LocalView.current.rootView.height
    return InfinityPopoverBounds(
        topLimitPx = safeDrawing.getTop(density) + marginPx,
        bottomLimitPx = rootHeightPx - safeDrawing.getBottom(density) - marginPx,
        marginPx = marginPx,
        gapPx = gapPx,
    )
}

/**
 * 贴着 [anchor] 弹出的浮层：无遮罩，点外部或返回键关闭；高度不超过锚点上下较大一侧的可用空间与屏幕高度的 70%，
 * 超出时由内容自行滚动（外壳只限高，不加滚动，避免与内容里的懒加载列表冲突）。
 * [show] 变为 false 时先播放退场再移除。锚点尚未量到位置时不显示。
 */
@Composable
fun InfinityPopover(
    show: Boolean,
    anchor: InfinityPopoverAnchor,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    side: InfinityPopoverSide = InfinityPopoverSide.Below,
    align: InfinityPopoverAlign = InfinityPopoverAlign.End,
    width: Dp = InfinityPopoverDefaultWidth,
    title: String? = null,
    /** 为 false 时点外部与返回键都不关闭（例如面板内操作进行中）。 */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val anchorBounds = anchor.bounds
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show && anchorBounds != null
    if (anchorBounds == null || (!visibleState.currentState && !visibleState.targetState && visibleState.isIdle)) return

    val density = LocalDensity.current
    val bounds = rememberInfinityPopoverBounds()
    val provider = remember(bounds, side, align, anchorBounds) {
        InfinityAnchorPositionProvider(bounds, side, align, anchorBounds)
    }
    val screenCapPx = with(density) { (LocalConfiguration.current.screenHeightDp * 0.7f).dp.roundToPx() }
    val maxHeight = with(density) { bounds.availableHeight(anchorBounds).coerceAtMost(screenCapPx).toDp() }
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val popoverWidth = width.coerceAtMost(windowWidth - InfinitySpacing.Large * 2)
    val direction = LocalLayoutDirection.current
    val origin = infinityPopoverTransformOrigin(
        side,
        resolveInfinityPopoverAlign(align, anchorBounds, LocalView.current.rootView.width, direction),
        direction,
    )
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            anchor.markDismissed()
            onDismissRequest()
        },
        properties = PopupProperties(focusable = true, dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        CompositionLocalProvider(LocalRowHeightGroup provides null) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(InfinityMotion.DurationShort)) +
                    scaleIn(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedDecelerate), initialScale = 0.92f, transformOrigin = origin),
                exit = fadeOut(tween(PopoverExitMillis)) +
                    scaleOut(tween(PopoverExitMillis, easing = InfinityMotion.EmphasizedAccelerate), targetScale = 0.96f, transformOrigin = origin),
            ) {
                val scheme = MaterialTheme.colorScheme
                val dark = scheme.surface.luminance() < 0.5f
                Surface(
                    shape = PopoverShape,
                    color = if (dark) InfinityObsidianDialogContainer else scheme.surfaceContainerLowest,
                    contentColor = scheme.onSurface,
                    border = BorderStroke(
                        1.dp,
                        if (dark) InfinityPureWhite.copy(alpha = 0.13f) else scheme.outlineVariant.copy(alpha = 0.55f),
                    ),
                    shadowElevation = 12.dp,
                    modifier = modifier
                        .infinityDismissOnEscape(enabled = dismissible) {
                            anchor.markDismissed()
                            onDismissRequest()
                        }
                        .width(popoverWidth)
                        .heightIn(max = maxHeight)
                        .then(if (title != null) Modifier.semantics { paneTitle = title } else Modifier)
                        .testTag(InfinityPopoverTags.POPOVER),
                ) {
                    Column(
                        modifier = Modifier.obsidianTopGlint(
                            color = if (dark) InfinityPureWhite.copy(alpha = 0.15f) else Color.Transparent,
                            cornerRadius = 16.dp,
                        ),
                        content = content,
                    )
                }
            }
        }
    }
}

/** 缩放起点放在靠近锚点的那个角，看起来从触发控件里长出来。 */
internal fun infinityPopoverTransformOrigin(side: InfinityPopoverSide, align: InfinityPopoverAlign, direction: LayoutDirection): TransformOrigin {
    val right = (align == InfinityPopoverAlign.End) == (direction == LayoutDirection.Ltr)
    return TransformOrigin(if (right) 1f else 0f, if (side == InfinityPopoverSide.Above) 1f else 0f)
}

private val PopoverShape = RoundedCornerShape(16.dp)
private const val PopoverExitMillis = 100

object InfinityPopoverTags {
    const val POPOVER = "infinity_popover"
}
