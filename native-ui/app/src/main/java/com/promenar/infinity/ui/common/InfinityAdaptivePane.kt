package com.promenar.infinity.ui.common

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import com.promenar.infinity.navigation.PageLayer
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityPureBlack
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 二级内容的承载方式：窄窗口为整页覆盖主内容的二级页面，宽窗口为主内容自己的浮动检视器。 */
enum class InfinityPanePresentation { Page, Inspector }

/** 二级内容据此决定根层是否需要自己的顶栏：检视器的页签已写明所在页，根层不再重复标题与返回。 */
val LocalInfinityPanePresentation = staticCompositionLocalOf { InfinityPanePresentation.Page }

/**
 * 检视器根页首块内容距卡片顶边的留白：与左右页边距相同，三边等距；整页呈现时返回 [otherwise]。
 * 只用于没有顶栏的检视器根层，有顶栏的层级由顶栏承担上方留白。
 * 首块控件自身在顶部有透明留白（如分段控件的轨道内缩）时传入 [visualInset]，按可见边缘对齐。
 */
@Composable
fun inspectorTopGap(otherwise: Dp = 0.dp, visualInset: Dp = 0.dp): Dp =
    if (LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector) InfinitySpacing.ScreenHorizontal - visualInset else otherwise

/** 宿主判定的窗口形态：为 true 时宿主不再整页覆盖，二级内容交给主内容的浮动检视器承载。 */
val LocalInfinityInspectorLayout = staticCompositionLocalOf { false }

/** 折叠屏内屏（约 690–840dp）与平板都在此之上；横屏手机高度不足，仍按窄窗口处理。 */
internal val InfinityInspectorMinWindowWidth = 600.dp
internal val InfinityInspectorMinWindowHeight = 480.dp
internal val InfinityInspectorWidth = 360.dp
internal val InfinityInspectorWideWidth = 560.dp

/** 检视器距窗口右缘与距让位后主内容的间距之和。 */
internal val InfinityInspectorGutter = 24.dp

/** 让位后主内容至少保留的宽度（约一台手机的会话列）；折叠屏内屏默认宽度可让位，加宽后达不到时检视器浮在主内容之上。 */
internal val InfinityInspectorMinMainWidth = 320.dp

enum class InfinityInspectorPlacement { Shift, Float }

fun infinityInspectorAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth >= InfinityInspectorMinWindowWidth && windowHeight >= InfinityInspectorMinWindowHeight

/** 加宽时最多 560dp，并给左侧留出至少 48dp 的会话可见边缘。 */
fun infinityInspectorWidth(windowWidth: Dp, widened: Boolean): Dp =
    if (!widened) InfinityInspectorWidth else minOf(InfinityInspectorWideWidth, windowWidth - 48.dp).coerceAtLeast(InfinityInspectorWidth)

fun infinityInspectorPlacement(windowWidth: Dp, inspectorWidth: Dp): InfinityInspectorPlacement =
    if (windowWidth - inspectorWidth - InfinityInspectorGutter >= InfinityInspectorMinMainWidth) InfinityInspectorPlacement.Shift
    else InfinityInspectorPlacement.Float

object InfinityPaneTags {
    const val PAGE = "infinity_secondary_page"
    const val INSPECTOR = "infinity_inspector"
    const val INSPECTOR_STRIP = "infinity_inspector_strip"
    fun level(key: String) = "infinity_pane_level_$key"
}

/**
 * 主内容与二级内容的自适应宿主。
 * - 窄窗口：二级内容作为二级页面整页覆盖主内容；[coverSuspended] 为 true 时（如生成中需要用户处理的审批）暂时让出，层级保留；
 * - 宽窗口（[infinityInspectorAvailable]）：宿主不覆盖，经 [LocalInfinityInspectorLayout] 通知主内容，由主内容的浮动检视器承载同一份层级。
 *
 * 主内容始终在同一位置组合，折叠屏开合改变窗口宽度时不会重建主内容的状态。
 * 系统返回先交给最上层内容自身（详情页、子目录等），没有可后退的层级时调用 [onBack]。
 * 层级用 [InfinityPaneLevel] 叠放：下层保持组合、不接收返回，上层弹出后下层原样恢复。
 * 整页覆盖时系统返回手势跟手进行，手感与页面级换页一致（见 [InfinityPaneBackState]）；按键与按钮返回仍是原有的层级过渡。
 */
@Composable
fun InfinityAdaptivePaneHost(
    paneVisible: Boolean,
    onBack: () -> Unit,
    pane: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    coverSuspended: Boolean = false,
    content: @Composable () -> Unit,
) {
    val back = remember { InfinityPaneBackState() }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 跟手期间裁到自身边界：主内容视差左移、二级页面右移都不越出宿主。
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { clip = back.phase != InfinityPaneBackPhase.Idle }) {
        val inspector = infinityInspectorAvailable(maxWidth, maxHeight)
        val shown = !inspector && paneVisible && !coverSuspended
        val shownState = rememberUpdatedState(shown)
        CompositionLocalProvider(LocalInfinityInspectorLayout provides inspector) {
            // 只有根层时拖动整个二级页面，露出的主内容视差并压暗。
            Box(Modifier.fillMaxSize().paneBackEffect(back, dark) { paneBackContentLayer(back.phase, back.target) }) {
                // 整页覆盖时主内容不接收返回，避免其中后来出现的返回处理抢在二级页面之前。
                InfinityBackScope(active = !shown) { content() }
            }
        }
        AnimatedVisibility(
            visible = shown,
            enter = InfinityMotion.sharedAxisXEnter(forward = true),
            // 跟手松手后已移出窗口，不再播放退场。
            exit = if (back.dismissing(PANE_BACK_ROOT)) ExitTransition.None else InfinityMotion.sharedAxisXExit(forward = false),
            modifier = Modifier.fillMaxSize(),
        ) {
            DisposableEffect(back) { onDispose { back.dismissed(PANE_BACK_ROOT) } }
            PredictiveBackHandler { events ->
                val gesture = back.startGesture()
                try {
                    events.collect { event -> gesture.drag(event.progress) }
                } catch (e: CancellationException) {
                    gesture.cancel(scope)
                    throw e
                }
                gesture.release { currentOnBack() }
            }
            CompositionLocalProvider(
                LocalInfinityPanePresentation provides InfinityPanePresentation.Page,
                LocalInfinityPaneBack provides back,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .paneBackEffect(back, dark) {
                            paneBackLayer(back.phase, back.target, PANE_BACK_ROOT, visible = shownState.value, top = true)
                        }
                        .testTag(InfinityPaneTags.PAGE),
                    content = pane,
                )
            }
        }
    }
}

/**
 * 二级内容中的一层。层级按组合顺序叠放，后组合的在上；[top] 为 false 的层被上层遮住，不接收返回。
 * 默认推入时自右侧滑入，弹出时向右滑出；[enter]/[exit] 可按场景替换（如检视器换页签）。随宿主一起首次出现时不另做动画。
 * 在窄窗口整页二级页面中，多层时系统返回手势只拖动最上层，其下一层视差并压暗；检视器中不跟手。
 */
@Composable
fun BoxScope.InfinityPaneLevel(
    key: String,
    visible: Boolean,
    top: Boolean,
    enter: EnterTransition = InfinityMotion.sharedAxisXEnter(forward = true),
    exit: ExitTransition = InfinityMotion.sharedAxisXExit(forward = false),
    content: @Composable () -> Unit,
) {
    val back = LocalInfinityPaneBack.current
    if (back != null) {
        DisposableEffect(back, key, visible, top) {
            if (visible) back.levels[key] = top
            onDispose { back.levels.remove(key) }
        }
    }
    val visibleState = rememberUpdatedState(visible)
    val topState = rememberUpdatedState(top)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        // 跟手松手后已移出窗口，不再播放退场。
        exit = if (back.dismissing(key)) ExitTransition.None else exit,
        modifier = Modifier.matchParentSize(),
    ) {
        if (back != null) {
            DisposableEffect(back, key) { onDispose { back.dismissed(key) } }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (back == null) Modifier else Modifier.paneBackEffect(back, dark) {
                        paneBackLayer(back.phase, back.target, key, visibleState.value, topState.value)
                    },
                )
                .testTag(InfinityPaneTags.level(key)),
        ) {
            InfinityBackScope(active = top, content = content)
        }
    }
}

/** 整页二级页面跟手返回的阶段：拖动中、松手后走完、取消后回弹、已弹出（被拖动的层退场中）。 */
internal enum class InfinityPaneBackPhase { Idle, Dragging, Settling, Cancelling, Committed }

/** 被拖动的是整个二级页面（只有根层时），而非其中某一层。 */
internal const val PANE_BACK_ROOT = "infinity_pane_root"

/** 拖动、松手走完与取消回弹期间，各层随进度位移、压暗。 */
private val InfinityPaneBackPhase.tracking: Boolean
    get() = this == InfinityPaneBackPhase.Dragging || this == InfinityPaneBackPhase.Settling ||
        this == InfinityPaneBackPhase.Cancelling

/** 手势开始时拖动谁：多层时只拖最上层（[levels] 为可见层 key → 是否最上层），只有根层或未登记时拖动整个二级页面。 */
internal fun paneBackTarget(levels: Map<String, Boolean>): String =
    levels.entries.firstOrNull { it.value }?.key?.takeIf { levels.size > 1 } ?: PANE_BACK_ROOT

/**
 * 二级页面中一层（或 [PANE_BACK_ROOT] 代表的整个二级页面）在跟手返回中的位置：
 * - 被拖动的为上层：随进度右移并带左缘阴影；松手弹出后到离开组合前保持移出，重新可见时回到原位；
 * - 拖动某一层时，其余可见且未在最上的层为下层：视差左移并压暗，被盖住的更低层位移相同，不会露出；
 * - 其余时刻不受影响。
 */
internal fun paneBackLayer(
    phase: InfinityPaneBackPhase,
    target: String?,
    key: String,
    visible: Boolean,
    top: Boolean,
): PageLayer = when {
    target == null -> PageLayer.NONE
    target == key -> if (phase.tracking || (phase == InfinityPaneBackPhase.Committed && !visible)) PageLayer.TOP else PageLayer.NONE
    phase.tracking && target != PANE_BACK_ROOT && visible && !top -> PageLayer.UNDER
    else -> PageLayer.NONE
}

/** 主内容只在拖动整个二级页面时露出，作为下层视差并压暗。 */
internal fun paneBackContentLayer(phase: InfinityPaneBackPhase, target: String?): PageLayer =
    if (phase.tracking && target == PANE_BACK_ROOT) PageLayer.UNDER else PageLayer.NONE

/**
 * 窄窗口整页二级页面的跟手返回状态：宿主在系统返回手势中写入进度与被拖动的层，各层与主内容只在绘制阶段读取，
 * 拖动的每一帧不触发重组与重新测量。阶段只在手势开始、松手、取消与弹出完成时变化。
 */
@Stable
internal class InfinityPaneBackState {
    var progress by mutableFloatStateOf(0f)
        private set
    var phase by mutableStateOf(InfinityPaneBackPhase.Idle)
        private set
    var target by mutableStateOf<String?>(null)
        private set

    /** 二级页面中当前可见的层：key → 是否最上层，由各层登记。 */
    val levels = mutableStateMapOf<String, Boolean>()

    private var reboundJob: Job? = null

    /** 一次系统返回开始：上一次松手还在走完时不再跟手，避免两次手势同时写入进度，这次返回照常弹出。 */
    fun startGesture(): Gesture = Gesture(follow = phase != InfinityPaneBackPhase.Settling)

    /**
     * 一次系统返回：手势拖动时跟手，松手后从当前进度走完、再弹出；取消时回弹到原位。
     * 按键、按钮等没有进度的返回直接弹出，保持原有的层级过渡。
     */
    inner class Gesture internal constructor(private val follow: Boolean) {
        private var dragging = false

        fun drag(value: Float) {
            if (!follow) return
            if (!dragging) {
                dragging = true
                begin()
            }
            progress = value
        }

        fun cancel(scope: CoroutineScope) {
            if (dragging) rebound(scope)
        }

        /** 松手即确定返回：走完与弹出不随后续手势或宿主离开组合而中断。 */
        suspend fun release(onBack: () -> Unit) {
            if (!dragging) {
                onBack()
                return
            }
            withContext(NonCancellable) {
                phase = InfinityPaneBackPhase.Settling
                animate(progress, 1f, animationSpec = InfinityMotion.pagePredictiveSettleSpec(progress)) { value, _ -> progress = value }
                phase = InfinityPaneBackPhase.Committed
                onBack()
            }
        }
    }

    /** 被拖动的层是否已由手势弹出、正在退场：退场不再播放动画。只在阶段变化时引起重组。 */
    fun isDismissing(key: String): Boolean = phase == InfinityPaneBackPhase.Committed && target == key

    /** 被手势弹出的层离开组合：本次返回结束，各层回到静止。 */
    fun dismissed(key: String) {
        if (isDismissing(key)) reset()
    }

    private fun begin() {
        reboundJob?.cancel()
        reboundJob = null
        target = paneBackTarget(levels)
        phase = InfinityPaneBackPhase.Dragging
    }

    private fun rebound(scope: CoroutineScope) {
        phase = InfinityPaneBackPhase.Cancelling
        val from = progress
        reboundJob = scope.launch {
            animate(from, 0f, animationSpec = InfinityMotion.pagePredictiveCancelSpec(from)) { value, _ -> progress = value }
            reset()
        }
    }

    private fun reset() {
        phase = InfinityPaneBackPhase.Idle
        target = null
        progress = 0f
    }
}

private val LocalInfinityPaneBack = staticCompositionLocalOf<InfinityPaneBackState?> { null }

/** 仅当阶段或被拖动的层变化时重组，读不到状态（检视器中）时为 false。 */
@Composable
private fun InfinityPaneBackState?.dismissing(key: String): Boolean {
    if (this == null) return false
    val state = this
    return remember(state, key) { derivedStateOf { state.isDismissing(key) } }.value
}

/**
 * 按 [layer] 画跟手返回：上层随进度右移，并在左缘外侧画一道阴影；下层视差左移并叠一层黑色压暗。
 * 位移、阴影与压暗都在绘制阶段读取进度，参数与页面级换页的 PageFrame 一致；静止时不画任何东西。
 */
private fun Modifier.paneBackEffect(back: InfinityPaneBackState, dark: Boolean, layer: () -> PageLayer): Modifier = this
    .graphicsLayer {
        translationX = when (layer()) {
            PageLayer.TOP -> InfinityMotion.pagePredictiveTopOffset(back.progress, size.width)
            PageLayer.UNDER -> InfinityMotion.pagePredictiveUnderOffset(back.progress, size.width)
            PageLayer.NONE -> 0f
        }
    }
    .drawWithContent {
        val current = layer()
        if (current == PageLayer.TOP) {
            val width = InfinityMotion.PageEdgeShadowWidthDp.dp.toPx()
            val alpha = if (dark) InfinityMotion.PageEdgeShadowAlphaDark else InfinityMotion.PageEdgeShadowAlphaLight
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, InfinityPureBlack.copy(alpha = alpha)),
                    startX = -width,
                    endX = 0f,
                ),
                topLeft = Offset(-width, 0f),
                size = Size(width, size.height),
            )
        }
        drawContent()
        if (current == PageLayer.UNDER) {
            val maxDim = if (dark) InfinityMotion.PageUnderDimDark else InfinityMotion.PageUnderDimLight
            val amount = InfinityMotion.pagePredictiveUnderDim(back.progress)
            if (amount > 0f) drawRect(InfinityPureBlack.copy(alpha = maxDim * amount))
        }
    }

/**
 * 对话框与上滑面板是独立窗口，系统返回先派发给该窗口。[InfinityBackScope] 显式提供的 Activity 调度器会经组合局部值传进窗口内容，
 * 使内容里的 BackHandler 注册到收不到这次返回的 Activity 上，返回键直接关掉整个面板。这里改回当前窗口视图树上的调度器；
 * 视图树上取不到时保留传入的值。
 */
@Composable
fun InfinityWindowBackScope(content: @Composable () -> Unit) {
    val view = LocalView.current
    val owner = remember(view) { view.findViewTreeOnBackPressedDispatcherOwner() }
    val events = remember(view) { view.findViewTreeNavigationEventDispatcherOwner() }
    val provided = listOfNotNull(
        owner?.let { LocalOnBackPressedDispatcherOwner provides it },
        events?.let { LocalNavigationEventDispatcherOwner provides it },
    )
    CompositionLocalProvider(*provided.toTypedArray(), content = content)
}

/**
 * [active] 为 false 时，内容中的 BackHandler、PredictiveBackHandler 与 NavHost 改挂到不接收系统返回的调度器上；
 * 重新激活时按当前时刻重新注册，排在已有的返回处理之前。切换只换提供的值，不改变组合结构，内容状态保留。
 * BackHandler 优先取 [LocalNavigationEventDispatcherOwner]，取不到才用 [LocalOnBackPressedDispatcherOwner]，两者都要替换。
 */
@Composable
fun InfinityBackScope(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalOnBackPressedDispatcherOwner.current
    val parentEvents = LocalNavigationEventDispatcherOwner.current
    if (parent == null && parentEvents == null) {
        content()
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val inert = remember(lifecycleOwner) {
        // 两个调度器都是没有输入来源的根调度器，系统返回不会派发到这里。
        object : OnBackPressedDispatcherOwner, NavigationEventDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val navigationEventDispatcher = NavigationEventDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val provided = listOfNotNull(
        parent?.let { LocalOnBackPressedDispatcherOwner provides if (active) it else inert },
        parentEvents?.let { LocalNavigationEventDispatcherOwner provides if (active) it else inert },
    )
    CompositionLocalProvider(*provided.toTypedArray(), content = content)
}
