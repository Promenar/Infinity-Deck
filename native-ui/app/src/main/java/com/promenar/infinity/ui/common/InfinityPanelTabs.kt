package com.promenar.infinity.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 面板二级页签的一项。[count] 以弱化的等宽数字跟在名称后（如“6”“2/4”“99+”）；
 * [dot] 非空时名称右侧挂一个该颜色的状态圆点（运行中、需要处理等）。
 */
@Immutable
data class InfinityPanelTab<T>(
    val value: T,
    val label: String,
    val count: String? = null,
    val dot: Color? = null,
    val testTag: String? = null,
)

/** 页签文字两侧的内边距：首项文字与内容边距对齐时，行首要少留这么多。 */
private val PanelTabPadding = 12.dp
private val PanelTabIndicatorHeight = 3.dp
private val PanelTabFadeWidth = 28.dp
private val PanelTabPressShape = RoundedCornerShape(10.dp)

/**
 * 面板与页面内部切换同级内容视图的二级页签：名称左对齐，主色指示条随选中项滑动，底部一条通栏细线。
 *
 * - 放在面板标题下、所切换内容的正上方；2 到 5 项固定视图。表单取值与列表筛选用 [InfinitySegmentedControl]。
 * - 文字单行；整行放不下时横向滚动，滚不到的一侧边缘渐隐，不截断也不换行；选中项自动滚入视野。
 * - 只放名称、计数与状态圆点，不放图标；触控高度 48dp，读屏角色为页签。
 * - [contentInset] 是首个页签文字到组件左缘的距离，默认与页面内容边距一致。
 *   父容器已有水平内边距时传入 [bleed]：页签行与底线向两侧伸出这段距离，贴到父容器边缘。
 * - 配合 [HorizontalPager] 使用时传入 [pagerState]：指示条在绘制阶段按拖动进度实时跟手插值，不引发重组。
 */
@Composable
fun <T> InfinityPanelTabs(
    tabs: List<InfinityPanelTab<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentInset: Dp = InfinitySpacing.ScreenHorizontal,
    bleed: Dp = 0.dp,
    scrollState: ScrollState = rememberScrollState(),
    pagerState: PagerState? = null,
) {
    if (tabs.isEmpty()) return
    val haptics = LocalInfinityHaptics.current
    val density = LocalDensity.current
    val edge = (contentInset - PanelTabPadding).coerceAtLeast(0.dp)
    val edgePx = with(density) { edge.roundToPx() }
    // 各页签在滚动行内的起点与宽度（像素），用来摆放指示条和把选中项滚入视野。
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    var viewport by remember { mutableIntStateOf(0) }
    val selectedIndex = tabs.indexOfFirst { it.value == selected }
    val activeIndex = pagerState?.currentPage?.takeIf { it in tabs.indices } ?: selectedIndex
    val target = bounds[activeIndex]
    val padPx = with(density) { PanelTabPadding.roundToPx() }

    val indicatorX = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    LaunchedEffect(pagerState, target) {
        if (pagerState != null) return@LaunchedEffect
        val (x, width) = target ?: return@LaunchedEffect
        val labelX = (x + padPx).toFloat()
        val labelWidth = (width - padPx * 2).coerceAtLeast(0).toFloat()
        if (indicatorWidth.value == 0f) {
            indicatorX.snapTo(labelX); indicatorWidth.snapTo(labelWidth)
            return@LaunchedEffect
        }
        val motion = tween<Float>(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)
        coroutineScope {
            launch { indicatorX.animateTo(labelX, motion) }
            launch { indicatorWidth.animateTo(labelWidth, motion) }
        }
    }
    LaunchedEffect(activeIndex, target, viewport) {
        val (x, width) = target ?: return@LaunchedEffect
        panelTabScrollTarget(x, x + width, scrollState.value, viewport, edgePx)?.let { scrollState.animateScrollTo(it) }
    }

    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val indicatorColor = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .bleed(bleed)
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width },
    ) {
        HorizontalDivider(Modifier.align(Alignment.BottomStart), color = divider)
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalFadingEdges(
                    start = scrollState.canScrollBackward,
                    end = scrollState.canScrollForward,
                    length = PanelTabFadeWidth,
                )
                .horizontalScroll(scrollState),
        ) {
            Row(
                Modifier
                    .selectableGroup()
                    .drawWithContent {
                        drawContent()
                        // 配合 HorizontalPager 时直接在绘制阶段读取连续页位；否则沿用点选动画坐标，首帧未初始化时落到选中页签。
                        val indicator = if (pagerState != null) {
                            val maxPage = (minOf(tabs.size, pagerState.pageCount) - 1).coerceAtLeast(0)
                            val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, maxPage.toFloat())
                            panelTabIndicatorBounds(bounds, position, padPx)
                        } else {
                            val placed = bounds[activeIndex]
                            val animated = indicatorWidth.value > 0f
                            val x = if (animated) indicatorX.value else placed?.let { (it.first + padPx).toFloat() }
                            val width = if (animated) indicatorWidth.value else placed?.let { (it.second - padPx * 2).coerceAtLeast(0).toFloat() }
                            if (x != null && width != null) x to width else null
                        }
                        if (indicator != null && indicator.second > 0f) {
                            val height = PanelTabIndicatorHeight.toPx()
                            drawRoundRect(
                                color = indicatorColor,
                                topLeft = Offset(indicator.first, size.height - height),
                                size = Size(indicator.second, height * 2),
                                cornerRadius = CornerRadius(height, height),
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(edge))
                tabs.forEachIndexed { index, tab ->
                    PanelTab(
                        tab = tab,
                        selected = index == activeIndex,
                        onClick = {
                            haptics.select(index == activeIndex)
                            if (index != activeIndex) onSelect(tab.value)
                        },
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            bounds[index] = coordinates.positionInParent().x.roundToInt() to coordinates.size.width
                        },
                    )
                }
                Spacer(Modifier.width(edge))
            }
        }
    }
}

internal enum class PanelTabPagerSyncAction { NONE, SNAP, ANIMATE_AND_SETTLE }

internal const val PANEL_TAB_PAGER_OFFSET_EPSILON = 0.001f

internal fun panelTabPagerSyncAction(
    tabsChanged: Boolean,
    animateSelection: Boolean,
    currentPage: Int,
    targetPage: Int,
    currentPageOffsetFraction: Float,
    desiredPage: Int,
): PanelTabPagerSyncAction {
    val offCenter = kotlin.math.abs(currentPageOffsetFraction) > PANEL_TAB_PAGER_OFFSET_EPSILON
    if (currentPage == desiredPage && !offCenter) return PanelTabPagerSyncAction.NONE
    if (tabsChanged || !animateSelection) return PanelTabPagerSyncAction.SNAP
    if (currentPage == desiredPage) return PanelTabPagerSyncAction.SNAP
    return if (targetPage == desiredPage) PanelTabPagerSyncAction.NONE else PanelTabPagerSyncAction.ANIMATE_AND_SETTLE
}

internal fun shouldSnapIdlePagerOffset(
    isScrollInProgress: Boolean,
    pageSize: Int,
    currentPageOffsetFraction: Float,
): Boolean = !isScrollInProgress && pageSize > 0 &&
    kotlin.math.abs(currentPageOffsetFraction) > PANEL_TAB_PAGER_OFFSET_EPSILON

/**
 * 为 [InfinityPanelTabs] + [HorizontalPager] 提供双向同步的状态器：
 * - 点击页签或外部代码改动 [selected] 时平滑滚动到目标页（[tabs] 列表结构变化或 [animateSelection] 为 false 时直接定位，避免与容器宽度动画竞态）；
 * - 用户左右滑动切换并在滚动静止后回调 [onSelect] 同步宿主状态（中途抓回取消时同样以落定页为准）；
 * - 视口宽度变化或动画中断导致静止态残留非零偏移时，自动对齐回目标页零偏移。
 */
@Composable
fun <T> rememberPanelTabPagerState(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    key: Any? = Unit,
    animateSelection: Boolean = true,
): PagerState {
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val latestTabs by rememberUpdatedState(tabs)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestAnimateSelection by rememberUpdatedState(animateSelection)
    val pagerState = key(key) {
        rememberPagerState(
            initialPage = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
            pageCount = { latestTabs.size },
        )
    }
    LaunchedEffect(pagerState) {
        var wasScrolling = false
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                wasScrolling = true
            } else if (wasScrolling) {
                wasScrolling = false
                val settled = latestTabs.getOrNull(pagerState.settledPage)
                if (settled != null && settled != latestSelected) {
                    latestOnSelect(settled)
                }
            }
        }
    }
    // 视口宽度变化（例如检视器放大态切换浏览器/非浏览器宽度）或手势/动画中断后，禁止静止态停留在半页偏移。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(
                pagerState.isScrollInProgress,
                pagerState.layoutInfo.pageSize,
                pagerState.currentPageOffsetFraction,
            )
        }.collect { (scrolling, pageSize, offsetFraction) ->
            if (shouldSnapIdlePagerOffset(scrolling, pageSize, offsetFraction)) {
                val count = latestTabs.size
                if (count > 0) {
                    val target = latestTabs.indexOf(latestSelected).coerceIn(0, count - 1)
                    pagerState.scrollToPage(target, 0f)
                }
            }
        }
    }
    var lastTabs by remember(key) { mutableStateOf(tabs) }
    LaunchedEffect(pagerState, selectedIndex, tabs) {
        if (tabs.isEmpty()) {
            lastTabs = tabs
            return@LaunchedEffect
        }
        val clamped = selectedIndex.coerceIn(0, tabs.lastIndex)
        val tabsChanged = lastTabs != tabs
        lastTabs = tabs
        when (
            panelTabPagerSyncAction(
                tabsChanged = tabsChanged,
                animateSelection = latestAnimateSelection,
                currentPage = pagerState.currentPage,
                targetPage = pagerState.targetPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                desiredPage = clamped,
            )
        ) {
            PanelTabPagerSyncAction.NONE -> Unit
            PanelTabPagerSyncAction.SNAP -> pagerState.scrollToPage(clamped, 0f)
            PanelTabPagerSyncAction.ANIMATE_AND_SETTLE -> {
                pagerState.animateScrollToPage(
                    page = clamped,
                    animationSpec = tween(InfinityMotion.DurationMedium, easing = InfinityMotion.PageSlideEasing),
                )
                if (pagerState.currentPage != clamped ||
                    kotlin.math.abs(pagerState.currentPageOffsetFraction) > PANEL_TAB_PAGER_OFFSET_EPSILON
                ) {
                    pagerState.scrollToPage(clamped, 0f)
                }
            }
        }
    }
    return pagerState
}

@Composable
private fun <T> PanelTab(tab: InfinityPanelTab<T>, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .then(tab.testTag?.let { Modifier.testTag(it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().padding(vertical = 6.dp).pressFeedback(interaction, PanelTabPressShape))
        Row(
            Modifier.padding(horizontal = PanelTabPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = tab.label,
                style = InfinityTypography.labelLarge.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            tab.count?.let {
                Text(
                    text = it,
                    style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = scheme.outline,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            tab.dot?.let { color ->
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
        }
    }
}

/** 父容器已有水平内边距时，让组件向两侧各伸出 [bleed]，贴到父容器边缘。 */
private fun Modifier.bleed(bleed: Dp): Modifier = if (bleed <= 0.dp) this else layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2),
    )
    layout(placeable.width - extra * 2, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * 选中页签需要滚到的位置；已在视野内时返回 null。
 * [left]、[right] 是页签在滚动行内的边界，[edge] 是行首尾留白，滚动后页签与视野边缘至少隔开这段留白。
 */
internal fun panelTabScrollTarget(left: Int, right: Int, scroll: Int, viewport: Int, edge: Int): Int? {
    if (viewport <= 0) return null
    return when {
        left - edge < scroll -> (left - edge).coerceAtLeast(0)
        right + edge > scroll + viewport -> right + edge - viewport
        else -> null
    }
}

/**
 * 根据连续页位 [position]（如 `currentPage + currentPageOffsetFraction`）在相邻两个已测量页签之间插值计算
 * 指示条的水平起点与宽度；邻侧尚未测量时回退到已测量的一侧。
 */
internal fun panelTabIndicatorBounds(
    bounds: Map<Int, Pair<Int, Int>>,
    position: Float,
    padPx: Int,
): Pair<Float, Float>? {
    if (bounds.isEmpty()) return null
    val maxIndex = bounds.keys.maxOrNull() ?: return null
    val clamped = position.coerceIn(0f, maxIndex.toFloat())
    val lower = floor(clamped).toInt()
    val upper = ceil(clamped).toInt()
    val lowerRect = bounds[lower]?.let { (x, w) -> (x + padPx).toFloat() to (w - padPx * 2).coerceAtLeast(0).toFloat() }
    val upperRect = bounds[upper]?.let { (x, w) -> (x + padPx).toFloat() to (w - padPx * 2).coerceAtLeast(0).toFloat() }
    return when {
        lowerRect == null -> upperRect
        upperRect == null || lower == upper -> lowerRect
        else -> {
            val fraction = (clamped - lower).coerceIn(0f, 1f)
            val x = lowerRect.first + (upperRect.first - lowerRect.first) * fraction
            val width = lowerRect.second + (upperRect.second - lowerRect.second) * fraction
            x to width
        }
    }
}
