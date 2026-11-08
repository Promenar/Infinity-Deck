package com.promenar.infinity.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.promenar.infinity.ui.theme.LocalInfinityPressCorner
import com.promenar.infinity.ui.theme.indicationCornerRadius
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.window.PopupProperties
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlin.math.roundToInt
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** 菜单可用的竖向范围（窗口坐标，像素）、横向边距与和锚点的间隔。 */
internal data class InfinityMenuLimits(
    val marginPx: Int,
    val topLimitPx: Int,
    val bottomLimitPx: Int,
    val gapPx: Int,
)

/** 菜单左上角位置与缩放起点（0 为左/上，1 为右/下）。 */
internal data class InfinityMenuPlacement(val offset: IntOffset, val originX: Float, val originY: Float)

/**
 * 菜单位置：给出 [touch]（长按点，窗口坐标）时从按住的位置长出，默认向右下，放不下时向左或向上；
 * 否则贴着 [anchor] 放在下方、放不下时翻到上方，横向按锚点所在半边对齐，总朝屏幕中部展开。
 * 两侧都放不下时贴着空间较大的一侧，始终留在安全区内。
 */
internal fun infinityMenuPlacement(
    anchor: IntRect,
    touch: IntOffset?,
    menu: IntSize,
    window: IntSize,
    limits: InfinityMenuLimits,
    ltr: Boolean,
): InfinityMenuPlacement {
    val minX = limits.marginPx
    val maxX = (window.width - menu.width - limits.marginPx).coerceAtLeast(minX)
    val growRight: Boolean
    val preferredX: Int
    if (touch != null) {
        val fitsRight = touch.x + menu.width <= window.width - limits.marginPx
        val fitsLeft = touch.x - menu.width >= limits.marginPx
        growRight = if (ltr) fitsRight || !fitsLeft else !fitsLeft && fitsRight
        preferredX = if (growRight) touch.x else touch.x - menu.width
    } else {
        val inLeftHalf = anchor.center.x * 2 < window.width
        growRight = inLeftHalf
        preferredX = if (growRight) anchor.left else anchor.right - menu.width
    }
    val x = preferredX.coerceIn(minX, maxX)

    val top = touch?.y ?: (anchor.top - limits.gapPx)
    val bottom = touch?.y ?: (anchor.bottom + limits.gapPx)
    val spaceBelow = limits.bottomLimitPx - bottom
    val spaceAbove = top - limits.topLimitPx
    val below = when {
        menu.height <= spaceBelow -> true
        menu.height <= spaceAbove -> false
        else -> spaceBelow >= spaceAbove
    }
    val rawY = if (below) bottom else top - menu.height
    val maxY = (limits.bottomLimitPx - menu.height).coerceAtLeast(limits.topLimitPx)
    val y = rawY.coerceIn(limits.topLimitPx, maxY)
    return InfinityMenuPlacement(IntOffset(x, y), originX = if (growRight) 0f else 1f, originY = if (below) 0f else 1f)
}

/**
 * 长按菜单的按住位置：给触发长按的元素加 [Modifier.infinityMenuTouch]，每次按下记下手指的窗口坐标，
 * 交给 [InfinityDropdownMenu] 的 `touch` 后菜单从按住的位置长出。无障碍操作等没有按下的打开方式仍贴着锚点。
 */
@Stable
class InfinityMenuTouch {
    internal var windowPoint by mutableStateOf<IntOffset?>(null)
    internal var coordinates: LayoutCoordinates? = null

    /** 菜单关闭后清掉，下次由键盘或无障碍打开时不沿用旧位置。 */
    fun clear() {
        windowPoint = null
    }
}

@Composable
fun rememberInfinityMenuTouch(): InfinityMenuTouch = remember { InfinityMenuTouch() }

/**
 * 菜单这一轮打开时的按住点：只在从关到开的那一刻取，开着时再按别处不跟着走；
 * 关闭后沿用，退场动画在原位播完。若关闭时丢掉按住点，位置会退回贴锚点的算法，
 * 整行宽的列表行会让菜单右对齐到行尾，退场那一瞬闪到列表右侧。
 */
internal class InfinityMenuOpenPoint {
    private var open = false
    private var point: IntOffset? = null

    fun resolve(expanded: Boolean, current: IntOffset?): IntOffset? {
        if (expanded && !open) point = current
        open = expanded
        return point
    }
}

fun Modifier.infinityMenuTouch(touch: InfinityMenuTouch): Modifier = this
    .onGloballyPositioned { touch.coordinates = it }
    .pointerInput(touch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val window = touch.coordinates?.takeIf { it.isAttached }?.localToWindow(down.position) ?: return@awaitEachGesture
            touch.windowPoint = IntOffset(window.x.roundToInt(), window.y.roundToInt())
        }
    }

/**
 * 菜单打开期间触发它的行铺一层浅色高亮，看得出菜单属于哪一行；不缩放，避免在分组卡片里顶出边界。
 * 圆角与按压反馈一致（[LocalInfinityPressCorner]），长按松手后高亮不会从圆角跳成直角。
 */
@Composable
fun Modifier.infinityMenuHost(active: Boolean): Modifier {
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(InfinityMotion.DurationShort),
        label = "menu_host_tint",
    )
    val maxCorner = LocalInfinityPressCorner.current
    val shape = remember(maxCorner) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                Outline.Rounded(RoundRect(size.toRect(), CornerRadius(indicationCornerRadius(size, with(density) { maxCorner.toPx() }))))
        }
    }
    return this.background(tint, shape)
}

/**
 * Infinity Deck 全站统一悬浮菜单：
 * - 同心圆角：外圆角 14dp = 内衬 6dp + 菜单项 8dp；
 * - 浅色用最亮的容器色，深色用比 surfaceContainerHigh 更亮一档的底色并加 0.5dp 顶部高光；0.5dp 描边与两层阴影；
 * - 菜单项用 [InfinityMenuItem]，分组用 [InfinityMenuDivider]，可选分组小标题 [InfinityMenuHeader]；宽度上限 280dp；
 * - 放在触发控件所在的 Box 内，贴着控件弹出；长按触发时传 [touch]，从按住的位置长出；
 * - 入场：弹出窗口画出 [InfinityMotion.MenuEnterWaitFrames] 帧后开始，透明度 90ms 先到位，缩放 0.92→1 交给弹簧，
 *   菜单项每项错峰 18ms 淡入上移；退场 100ms 淡出并缩到 0.96，播完再移除。
 * [offset] 在算好的位置上再平移（与 Material 下拉菜单的偏移含义一致）。
 */
@Composable
fun InfinityDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = 160.dp,
    properties: PopupProperties = PopupProperties(focusable = true),
    touch: InfinityMenuTouch? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 弹出窗口挂载、取得焦点与菜单首次组合都在打开后的头几帧里，直接起动画会在这几帧掉帧。
    // 所以先以透明状态挂上并量好菜单，等弹出窗口画出若干帧后再开始入场；关闭时播完退场再移除。
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(InfinityMotion.MenuEnterScale) }
    // 入场时钟（毫秒）：菜单项按各自序号从这条时钟上取进度，读在绘制层，不触发重组。
    // 每次打开或关闭换一个实例：序号从 0 重新领取；关闭时新实例的时钟停在终点，菜单项保持完整可见。
    val stagger = remember(expanded) { InfinityMenuStagger() }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
        } else if (mounted) {
            coroutineScope {
                launch { alpha.animateTo(0f, tween(InfinityMotion.MenuExitMillis, easing = FastOutLinearInEasing)) }
                launch {
                    scale.animateTo(
                        InfinityMotion.MenuExitScale,
                        tween(InfinityMotion.MenuExitMillis, easing = FastOutLinearInEasing),
                    )
                }
            }
            mounted = false
        }
    }
    if (!expanded && !mounted) return

    val density = LocalDensity.current
    val bounds = rememberInfinityPopoverBounds()
    val limits = InfinityMenuLimits(
        marginPx = bounds.marginPx,
        topLimitPx = bounds.topLimitPx,
        bottomLimitPx = bounds.bottomLimitPx,
        gapPx = with(density) { InfinitySpacing.XSmall.roundToPx() },
    )
    val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val openPoint = remember { InfinityMenuOpenPoint() }
    val touchPoint = openPoint.resolve(expanded, touch?.windowPoint)
    val provider = remember(limits, shift, touchPoint, stagger) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val placement = infinityMenuPlacement(
                    anchor = anchorBounds,
                    touch = touchPoint,
                    menu = popupContentSize,
                    window = windowSize,
                    limits = limits,
                    ltr = layoutDirection == LayoutDirection.Ltr,
                )
                origin = TransformOrigin(placement.originX, placement.originY)
                // 菜单朝上展开时，菜单项错峰从靠近锚点的底部开始。
                stagger.reversed = placement.originY == 1f
                // 从按住点长出时偏移已无意义，只对贴锚点的菜单生效。
                return placement.offset + if (touchPoint == null) shift else IntOffset.Zero
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            touch?.clear()
            onDismissRequest()
        },
        properties = properties,
    ) {
        if (expanded) {
            LaunchedEffect(stagger) {
                val fresh = alpha.value == 0f
                if (fresh) {
                    scale.snapTo(InfinityMotion.MenuEnterScale)
                    stagger.clock.snapTo(0f)
                    repeat(InfinityMotion.MenuEnterWaitFrames) { withFrameNanos {} }
                } else {
                    // 退场途中再次打开：菜单项已完整可见，不重播错峰。
                    stagger.clock.snapTo(MenuStaggerEndMillis)
                }
                coroutineScope {
                    launch {
                        alpha.animateTo(1f, tween(InfinityMotion.MenuEnterFadeMillis, easing = LinearOutSlowInEasing))
                    }
                    launch { scale.animateTo(1f, MenuEnterSpring) }
                    if (fresh) {
                        launch {
                            stagger.clock.animateTo(
                                MenuStaggerEndMillis,
                                tween(MenuStaggerEndMillis.roundToInt(), easing = LinearEasing),
                            )
                        }
                    }
                }
            }
        }
        val colors = infinityMenuColors()
        val shape = MenuShape
        CompositionLocalProvider(LocalInfinityMenuStagger provides stagger) {
            Surface(
                shape = shape,
                color = colors.container,
                border = BorderStroke(0.5.dp, colors.edge),
                shadowElevation = MenuKeyShadowElevation,
                modifier = modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                        scaleX = scale.value
                        scaleY = scale.value
                        transformOrigin = origin
                    }
                    // 第二层贴身阴影，让菜单边缘在浅色背景上也清楚。
                    .shadow(
                        elevation = MenuAmbientShadowElevation,
                        shape = shape,
                        clip = false,
                        ambientColor = colors.shadow,
                        spotColor = colors.shadow,
                    )
                    .testTag(InfinityMenuTags.MENU),
            ) {
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            // 深色下的顶部高光：0.5dp，沿上缘圆角内侧。
                            if (colors.highlight.alpha > 0f) {
                                val stroke = 0.5.dp.toPx()
                                val inset = MenuCornerRadius.toPx()
                                drawLine(
                                    color = colors.highlight,
                                    start = Offset(inset, stroke / 2f),
                                    end = Offset(size.width - inset, stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            }
                        }
                        .width(IntrinsicSize.Max)
                        .widthIn(min = minWidth, max = MenuMaxWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(MenuInnerPadding),
                    content = content,
                )
            }
        }
    }
}

/** 菜单底色、描边、高光与阴影色；深浅色按当前配色方案的明暗判断，跟随种子配色。 */
@Immutable
internal data class InfinityMenuColors(
    val container: Color,
    val edge: Color,
    val highlight: Color,
    val shadow: Color,
)

@Composable
internal fun infinityMenuColors(): InfinityMenuColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return if (dark) {
        InfinityMenuColors(
            container = scheme.surfaceContainerHighest,
            edge = scheme.onSurface.copy(alpha = 0.08f),
            highlight = scheme.onSurface.copy(alpha = 0.07f),
            shadow = scheme.scrim,
        )
    } else {
        InfinityMenuColors(
            container = scheme.surfaceContainerLowest,
            edge = scheme.onSurface.copy(alpha = 0.10f),
            highlight = Color.Transparent,
            shadow = scheme.scrim.copy(alpha = 0.55f),
        )
    }
}

/** 菜单项入场错峰：入场时钟与各项按组合顺序领取的序号。 */
@Stable
internal class InfinityMenuStagger {
    val clock = Animatable(MenuStaggerEndMillis)
    var reversed by mutableStateOf(false)
    private var count = 0
    fun nextIndex(): Int = count++
    val itemCount: Int get() = count
}

internal val LocalInfinityMenuStagger = staticCompositionLocalOf<InfinityMenuStagger?> { null }

/** 时钟走到这里时所有菜单项都已到位。 */
private val MenuStaggerEndMillis: Float = (
    InfinityMotion.MenuItemStaggerMillis * (InfinityMotion.MenuItemStaggerMaxItems - 1) +
        InfinityMotion.MenuItemEnterMillis
    ).toFloat()

/**
 * 第 [index] 个菜单项在入场时钟 [elapsedMillis] 时的进度（0 不可见，1 到位）。
 * 每项晚 [InfinityMotion.MenuItemStaggerMillis]，序号超过上限的项与最后一档同时出现。
 */
internal fun infinityMenuItemEnterProgress(elapsedMillis: Float, index: Int): Float {
    val slot = index.coerceIn(0, InfinityMotion.MenuItemStaggerMaxItems - 1)
    val start = slot * InfinityMotion.MenuItemStaggerMillis
    return ((elapsedMillis - start) / InfinityMotion.MenuItemEnterMillis).coerceIn(0f, 1f)
}

/** 菜单项、分组线与小标题共用的错峰入场：淡入并上移 4dp（朝上展开时下移）。 */
@Composable
private fun Modifier.infinityMenuStaggerEnter(): Modifier {
    val stagger = LocalInfinityMenuStagger.current ?: return this
    val index = remember(stagger) { stagger.nextIndex() }
    return graphicsLayer {
        val order = if (stagger.reversed) (stagger.itemCount - 1 - index).coerceAtLeast(0) else index
        val p = infinityMenuItemEnterProgress(stagger.clock.value, order)
        alpha = LinearOutSlowInEasing.transform(p)
        val shift = InfinityMotion.MenuItemEnterOffsetDp * (1f - InfinityMotion.EmphasizedDecelerate.transform(p))
        translationY = shift.dp.toPx() * if (stagger.reversed) -1f else 1f
    }
}

/**
 * 菜单项：8dp 圆角按压高亮，图标与文字间距 14dp，文字 15sp 常规字重；[destructive] 时文字、图标与按压高亮用错误色。
 * [selected] 不为 null 时在右侧预留选中勾位，为 true 时显示主色勾（与 [trailingIcon] 同时给出时以勾为准）。
 * 参数与 Material 的 DropdownMenuItem 对齐，便于各处直接替换。
 * 点按时按 [haptic] 振动，默认轻 tick；复制等确认动作传确认档，不需要时传 null。
 */
@Composable
fun InfinityMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean? = null,
    contentPadding: PaddingValues = MenuItemPadding,
    haptic: InfinityHapticEvent? = InfinityHapticEvent.SELECTION,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalInfinityHaptics.current
    val textColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        destructive -> scheme.error
        else -> scheme.onSurface
    }
    val iconColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        destructive -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val trailing: (@Composable () -> Unit)? = when (selected) {
        null -> trailingIcon
        else -> {
            {
                Box(Modifier.size(MenuCheckSize), contentAlignment = Alignment.Center) {
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = if (enabled) scheme.primary else iconColor,
                            modifier = Modifier.size(MenuCheckSize),
                        )
                    }
                }
            }
        }
    }
    // 按压反馈跟随当前内容色：危险项按下时用错误色高亮。
    CompositionLocalProvider(LocalContentColor provides textColor) {
        Row(
            modifier = modifier
                .infinityMenuStaggerEnter()
                .fillMaxWidth()
                .sizeIn(minWidth = 112.dp, maxWidth = MenuMaxWidth, minHeight = MenuItemMinHeight)
                .clip(MenuItemShape)
                .then(
                    if (selected != null) {
                        Modifier.selectable(selected = selected, enabled = enabled, role = Role.Button) {
                            haptic?.let(haptics::perform)
                            onClick()
                        }
                    } else {
                        Modifier.clickable(enabled = enabled, role = Role.Button) {
                            haptic?.let(haptics::perform)
                            onClick()
                        }
                    },
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                CompositionLocalProvider(LocalContentColor provides iconColor) {
                    Box(Modifier.padding(end = 14.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                }
            }
            Box(Modifier.weight(1f)) {
                ProvideTextStyle(MenuItemTextStyle, text)
            }
            if (trailing != null) {
                CompositionLocalProvider(LocalContentColor provides iconColor) {
                    Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.Center) { trailing() }
                }
            }
        }
    }
}

/** 菜单内的分组线，左右与菜单项文字区对齐。 */
@Composable
fun InfinityMenuDivider() {
    Box(
        Modifier
            .infinityMenuStaggerEnter()
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 菜单内可选的分组小标题，读屏视为标题。 */
@Composable
fun InfinityMenuHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MenuHeaderTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .infinityMenuStaggerEnter()
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

object InfinityMenuTags {
    const val MENU = "infinity_menu"
}

private val MenuCornerRadius = 14.dp
private val MenuShape = RoundedCornerShape(MenuCornerRadius)
private val MenuItemShape = RoundedCornerShape(8.dp)
private val MenuInnerPadding = 6.dp
private val MenuMaxWidth = 280.dp
private val MenuItemMinHeight = 44.dp
private val MenuItemPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
private val MenuCheckSize = 18.dp
private val MenuKeyShadowElevation = 10.dp
private val MenuAmbientShadowElevation = 2.dp
private val MenuItemTextStyle = InfinityTypography.bodyLarge.copy(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
)
private val MenuHeaderTextStyle = InfinityTypography.labelSmall.copy(
    fontSize = 11.5.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
)
private val MenuEnterSpring = spring<Float>(
    dampingRatio = InfinityMotion.MenuEnterSpringDampingRatio,
    stiffness = InfinityMotion.MenuEnterSpringStiffness,
)
