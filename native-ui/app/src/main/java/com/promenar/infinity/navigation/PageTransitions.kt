package com.promenar.infinity.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.promenar.infinity.ui.theme.InfinityMotion

/**
 * 一次换页的种类：迁移初始层级时直接出现；栏内换根页面为同级叠化；其余为视差推入的前进与返回。
 * [PREDICTIVE_POP] 是系统返回手势拖动中的返回：位移与 [POP] 相同，按手势进度跟手，松手后完成或回弹。
 */
internal enum class PageChange { INSTANT, SAME_LEVEL, PUSH, POP, PREDICTIVE_POP }

/** 换页中页面所处的层：上层带左缘阴影，下层随进度压暗；叠化与无动画时都不画。 */
internal enum class PageLayer { NONE, TOP, UNDER }

/** 返回一律是视差返回；前进时 [instant] 优先于 [sameLevel]。 */
internal fun pageChange(pop: Boolean, instant: Boolean, sameLevel: Boolean): PageChange = when {
    pop -> PageChange.POP
    instant -> PageChange.INSTANT
    sameLevel -> PageChange.SAME_LEVEL
    else -> PageChange.PUSH
}

/** 换页双方的层（目标页在前、离开页在后）：前进时新页在上；返回时被弹出的页在上，下层页从压暗中恢复。 */
internal fun PageChange.layers(): Pair<PageLayer, PageLayer> = when (this) {
    PageChange.PUSH -> PageLayer.TOP to PageLayer.UNDER
    PageChange.POP, PageChange.PREDICTIVE_POP -> PageLayer.UNDER to PageLayer.TOP
    PageChange.INSTANT, PageChange.SAME_LEVEL -> PageLayer.NONE to PageLayer.NONE
}

/** 条目在最近一次换页中的层，以及那次换页的种类。 */
internal data class PageRole(val layer: PageLayer, val change: PageChange)

/**
 * 压暗进度跟随哪次换页的位移曲线：只有下层页需要压暗，曲线与该次换页的位移一致
 * （前进时被推入盖住，返回或跟手返回时从下层回来）；其余页面不压暗，返回 null（立即到位，不拉长整个换页）。
 */
internal fun pageDimFollows(role: PageRole): PageChange? = role.change.takeIf { role.layer == PageLayer.UNDER }

private val NoRole = PageRole(PageLayer.NONE, PageChange.INSTANT)

/**
 * 一个 NavHost 内各条目当前所处的层：转场回调决定动效时登记，目的地外框在组合与绘制时读取。
 * 同一条目先后作为上层和下层时以最近一次换页为准；条目离开组合时清除。
 * 跟手返回时导航库按手势进度反复定位同一次转场，登记结果不变；取消后被拉出的下层页离开组合即清除，
 * 留在原位的上层页静止时既不画阴影也不压暗，下一次换页会重新登记两页。
 */
@Stable
internal class PageLayers {
    private val roles = mutableStateMapOf<String, PageRole>()

    fun roleOf(entryId: String): PageRole = roles[entryId] ?: NoRole

    fun layerOf(entryId: String): PageLayer = roleOf(entryId).layer

    fun record(targetId: String, initialId: String, change: PageChange) {
        val (target, initial) = change.layers()
        set(targetId, PageRole(target, change))
        set(initialId, PageRole(initial, change))
    }

    fun clear(entryId: String) {
        roles.remove(entryId)
    }

    // 转场回调在组合期间执行，同值不重复写入，避免无谓的重组。
    private fun set(entryId: String, role: PageRole) {
        if (roles[entryId] != role) roles[entryId] = role
    }
}

private val LocalPageLayers = staticCompositionLocalOf<PageLayers?> { null }

/**
 * 页面级 NavHost：层级换页用视差推入（见 [InfinityMotion.pageEnter]），并裁剪到自身边界，
 * 栏内的位移、阴影与压暗不会盖到相邻列表栏或分隔线上。
 * [isSameLevel] 判定前进的目标页是否与当前页同级（栏内换根页面），是则改为叠化；
 * [isInstant] 判定的目标页（开合屏迁移的初始层级）直接出现。
 * 系统返回手势拖动时同样是视差返回，按手势进度跟手（见 [InfinityMotion.pagePredictiveEnter]）；
 * 栈里只有一页时导航库不拦截返回，交给外层处理。
 * 目的地须用 [composablePage] 注册，才有不透明底色、左缘阴影与压暗。
 */
@Composable
internal fun InfinityPageNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    isSameLevel: (NavBackStackEntry) -> Boolean = { false },
    isInstant: (NavBackStackEntry) -> Boolean = { false },
    builder: NavGraphBuilder.() -> Unit,
) {
    val layers = remember(navController) { PageLayers() }
    val forward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> PageChange = {
        pageChange(pop = false, instant = isInstant(targetState), sameLevel = isSameLevel(targetState))
            .also { layers.record(targetState.id, initialState.id, it) }
    }
    val backward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> PageChange = {
        PageChange.POP.also { layers.record(targetState.id, initialState.id, it) }
    }
    val predictiveBackward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> PageChange = {
        PageChange.PREDICTIVE_POP.also { layers.record(targetState.id, initialState.id, it) }
    }
    CompositionLocalProvider(LocalPageLayers provides layers) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier.clipToBounds(),
            enterTransition = { pageEnterFor(forward()) },
            exitTransition = { pageExitFor(forward()) },
            popEnterTransition = { pageEnterFor(backward()) },
            popExitTransition = { pageExitFor(backward()) },
            // 不传时导航库在手势中改用淡入与缩小，这里换成同样的视差位移。
            predictivePopEnterTransition = { pageEnterFor(predictiveBackward()) },
            predictivePopExitTransition = { pageExitFor(predictiveBackward()) },
            builder = builder,
        )
    }
}

private fun pageEnterFor(change: PageChange): EnterTransition = when (change) {
    PageChange.INSTANT -> EnterTransition.None
    PageChange.SAME_LEVEL -> InfinityMotion.paneRootEnter
    PageChange.PUSH -> InfinityMotion.pageEnter(pop = false)
    PageChange.POP -> InfinityMotion.pageEnter(pop = true)
    PageChange.PREDICTIVE_POP -> InfinityMotion.pagePredictiveEnter()
}

private fun pageExitFor(change: PageChange): ExitTransition = when (change) {
    PageChange.INSTANT -> ExitTransition.None
    PageChange.SAME_LEVEL -> InfinityMotion.paneRootExit
    PageChange.PUSH -> InfinityMotion.pageExit(pop = false)
    PageChange.POP -> InfinityMotion.pageExit(pop = true)
    PageChange.PREDICTIVE_POP -> InfinityMotion.pagePredictiveExit()
}

/** 压暗进度用的曲线：与 [change] 的位移曲线一致，跟手返回时同样按手势进度。 */
private fun <T> pageSlideSpec(change: PageChange): FiniteAnimationSpec<T> = when (change) {
    PageChange.PUSH -> InfinityMotion.pageSpec(pop = false)
    PageChange.POP -> InfinityMotion.pageSpec(pop = true)
    PageChange.PREDICTIVE_POP -> InfinityMotion.pagePredictiveSpec()
    PageChange.INSTANT, PageChange.SAME_LEVEL -> snap()
}

/** 注册页面级目的地：与 [composable] 相同，另包一层 [PageFrame]。 */
internal fun NavGraphBuilder.composablePage(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, deepLinks) { entry ->
        val scope = this
        PageFrame(scope, entry.id) { scope.content(entry) }
    }
}

/**
 * 目的地外框：铺一层不透明背景，换页时两页叠在一起也不会透出下层；
 * 作为上层时在左缘外侧画一道阴影，作为下层时按位移进度叠一层黑色压暗。
 * 阴影只在换页进行中绘制，压暗在静止时为 0；系统移除动画时换页立即结束，两者都不残留。
 * 跟手返回时两者都挂在同一个转场上，随手势进度前进或回退，取消回弹后转场回到静止，同样不残留。
 */
@Composable
private fun PageFrame(scope: AnimatedContentScope, entryId: String, content: @Composable () -> Unit) {
    val layers = LocalPageLayers.current
    if (layers != null) {
        DisposableEffect(layers, entryId) { onDispose { layers.clear(entryId) } }
    }
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val maxDim = if (dark) InfinityMotion.PageUnderDimDark else InfinityMotion.PageUnderDimLight
    val shadowAlpha = if (dark) InfinityMotion.PageEdgeShadowAlphaDark else InfinityMotion.PageEdgeShadowAlphaLight
    val transition = scope.transition
    val dim = transition.animateFloat(
        transitionSpec = {
            val follows = layers?.let { pageDimFollows(it.roleOf(entryId)) }
            if (follows == null) snap() else pageSlideSpec(follows)
        },
        label = "page-under-dim",
    ) { state ->
        if (state == EnterExitState.Visible || layers?.layerOf(entryId) != PageLayer.UNDER) 0f else 1f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val layer = layers?.layerOf(entryId) ?: PageLayer.NONE
                if (layer == PageLayer.TOP && transition.currentState != transition.targetState) {
                    val width = InfinityMotion.PageEdgeShadowWidthDp.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = shadowAlpha)),
                            startX = -width,
                            endX = 0f,
                        ),
                        topLeft = Offset(-width, 0f),
                        size = Size(width, size.height),
                    )
                }
                drawContent()
                val amount = if (layer == PageLayer.UNDER) dim.value else 0f
                if (amount > 0f) drawRect(Color.Black.copy(alpha = maxDim * amount))
            }
            .background(background),
        contentAlignment = Alignment.TopStart,
        propagateMinConstraints = true,
    ) { content() }
}

/**
 * 不走 NavHost 的页面栈（资料页的单页栈与右列层级）：前进、返回与系统返回手势的跟手返回，
 * 位移、左缘阴影与压暗都与 [InfinityPageNavHost] 一致；根页面变化（左列切换分区）为同级叠化。
 * [pages] 为当前层级，末项为可见页。[popped] 非 null 时系统返回由本栈处理：手势跟手拖出顶层，
 * 松手后调用 [onPop]，宿主随后把 [pages] 更新为 [popped]；取消则回弹。
 */
@Composable
internal fun <T> InfinityPageStack(
    pages: List<T>,
    popped: List<T>?,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (List<T>) -> Unit,
) {
    val layers = remember { PageLayers() }
    val seek = remember { SeekableTransitionState(pages) }
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(false) }
    val currentOnPop by rememberUpdatedState(onPop)
    LaunchedEffect(pages) {
        if (seek.targetState != pages || seek.currentState != pages) seek.animateTo(pages)
        gesture = false
    }
    PredictiveBackHandler(enabled = popped != null) { events ->
        val target = popped ?: return@PredictiveBackHandler
        gesture = true
        try {
            seek.seekTo(0f, targetState = target)
            events.collect { event -> seek.seekTo(event.progress.coerceIn(0f, 1f)) }
        } catch (cancelled: CancellationException) {
            // 取消：按已拖动的进度回弹，再回到静止的原层级。
            scope.launch {
                val rebound = Animatable(seek.fraction)
                val follower = launch { snapshotFlow { rebound.value }.collect { seek.seekTo(it) } }
                rebound.animateTo(0f, InfinityMotion.pagePredictiveCancelSpec(rebound.value))
                follower.cancel()
                seek.snapTo(seek.currentState)
                gesture = false
            }
            throw cancelled
        }
        currentOnPop()
    }
    val transition = rememberTransition(seek, label = "page-stack")
    CompositionLocalProvider(LocalPageLayers provides layers) {
        transition.AnimatedContent(
            modifier = modifier.clipToBounds(),
            contentKey = { pageStackId(it) },
            transitionSpec = {
                val change = pageStackChange(initialState, targetState, predictive = gesture)
                layers.record(pageStackId(targetState), pageStackId(initialState), change)
                // 越深的页叠得越高：返回时被弹出的页留在上层滑出。
                ContentTransform(pageEnterFor(change), pageExitFor(change), targetContentZIndex = targetState.size.toFloat(), sizeTransform = null)
            },
        ) { state ->
            PageFrame(this, pageStackId(state)) { content(state) }
        }
    }
}

/** 页面栈中一页的标识：层级深度加末项，同一层级换页视为不同页。 */
internal fun pageStackId(pages: List<*>): String = "${pages.size}:${pages.lastOrNull()}"

/** 页面栈的换页种类：根页面变化为同级叠化；层级加深为前进，变浅为返回（手势中为跟手返回）。 */
internal fun pageStackChange(from: List<*>, to: List<*>, predictive: Boolean): PageChange = when {
    from.isNotEmpty() && to.isNotEmpty() && from.first() != to.first() -> PageChange.SAME_LEVEL
    to.size > from.size -> PageChange.PUSH
    to.size < from.size -> if (predictive) PageChange.PREDICTIVE_POP else PageChange.POP
    else -> PageChange.SAME_LEVEL
}
