package com.promenar.infinity.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 全站动效基准，参照 Material 3 的时长与缓动分档：
 * - 页面级换页（NavHost）用视差推入：新页整页从右缘推入，下层页左移四分之一并压暗，返回反向；系统返回手势拖动时同样的位移跟手进行；
 * - 底部标签与导航轨切换按标签顺序整页横滑或纵滑，两页都不透明；
 * - 面板、弹层内的层级导航（进入子目录、面板内下一页）用共享 X 轴：前进从右侧滑入，返回从左侧滑入，滑动距离只取一小段并叠加淡入淡出；
 * - 同级内容替换（分段标签、加载/空/内容状态）用交叉淡入；
 * - 局部显隐（提示、进度、展开区）用纵向展开收起加淡入淡出。
 * 系统“移除动画”开启时 Compose 会把时长缩放到 0，这里不另做判断。
 */
object InfinityMotion {
    const val DurationShort = 150
    const val DurationMedium = 300
    /** 共享轴切换中旧内容先淡出的时长，新内容在其后淡入。 */
    const val DurationFadeOut = 90

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = FastOutSlowInEasing
    /** 返回与标签切换用的减速曲线：起步快、尾段长，没有回弹。 */
    val PageSlideEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ── 页面级换页：视差推入 ──────────────────────────────────────
    /** 前进换页时长；返回用 [DurationMedium]。 */
    const val DurationPagePush = 360
    /** 下层页随新页推入左移的比例：-25% 宽度。 */
    private const val PAGE_UNDER_SHIFT_DIVISOR = 4
    /** 下层页完全被盖住时的压暗程度（黑色不透明度）：浅色与深色主题分开。 */
    const val PageUnderDimLight = 0.12f
    const val PageUnderDimDark = 0.30f
    /** 上层页左缘阴影的宽度（dp）与最深处的黑色不透明度。 */
    const val PageEdgeShadowWidthDp = 16f
    const val PageEdgeShadowAlphaLight = 0.14f
    const val PageEdgeShadowAlphaDark = 0.36f

    /** 共享轴滑动距离占内容宽度的比例，约等于手机上的 30–40dp。 */
    private const val SHARED_AXIS_SLIDE_FRACTION = 10

    private fun <T> fadeInSpec(): FiniteAnimationSpec<T> =
        tween(DurationMedium - DurationFadeOut, delayMillis = DurationFadeOut, easing = LinearOutSlowInEasing)

    private fun <T> fadeOutSpec(): FiniteAnimationSpec<T> = tween(DurationFadeOut, easing = FastOutLinearInEasing)

    /** 层级导航的进入动画；[forward] 为 true 表示进入更深一层。 */
    fun sharedAxisXEnter(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(DurationMedium, easing = EmphasizedDecelerate)) { width ->
            (if (forward) 1 else -1) * width / SHARED_AXIS_SLIDE_FRACTION
        } + fadeIn(fadeInSpec())

    fun sharedAxisXExit(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(DurationMedium, easing = EmphasizedDecelerate)) { width ->
            (if (forward) -1 else 1) * width / SHARED_AXIS_SLIDE_FRACTION
        } + fadeOut(fadeOutSpec())

    /** 层级导航：新旧内容同时横向错位并交叉淡入，容器高度平滑过渡。 */
    fun sharedAxisX(forward: Boolean): ContentTransform =
        ContentTransform(sharedAxisXEnter(forward), sharedAxisXExit(forward), sizeTransform = sizeTransform())

    /** 标题、计数等短文本的纵向替换：数值或层级增加时自下而上，减少时自上而下。 */
    fun sharedAxisY(forward: Boolean): ContentTransform = ContentTransform(
        targetContentEnter = slideInVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) { height ->
            (if (forward) 1 else -1) * height / 2
        } + fadeIn(fadeInSpec()),
        initialContentExit = slideOutVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) { height ->
            (if (forward) -1 else 1) * height / 2
        } + fadeOut(fadeOutSpec()),
        sizeTransform = sizeTransform(),
    )

    /** 同级内容替换：旧内容先淡出、新内容随后淡入，避免两层文字叠在一起。 */
    fun fadeThrough(): ContentTransform =
        ContentTransform(fadeIn(fadeInSpec()), fadeOut(fadeOutSpec()), sizeTransform = sizeTransform())

    /** 视差推入的位移曲线；压暗进度也用它，与位移同步。[pop] 为 true 表示返回。 */
    fun <T> pageSpec(pop: Boolean): FiniteAnimationSpec<T> =
        if (pop) tween(DurationMedium, easing = PageSlideEasing) else tween(DurationPagePush, easing = EmphasizedDecelerate)

    /** 视差推入的进入：前进时新页从右缘整页滑入；返回时下层页从 -25% 回到原位。两页都不做淡入淡出。 */
    fun pageEnter(pop: Boolean): EnterTransition =
        slideInHorizontally(pageSpec(pop)) { width -> if (pop) -width / PAGE_UNDER_SHIFT_DIVISOR else width }

    /** 视差推入的退出：前进时旧页左移 25%（被新页盖住）；返回时被弹出的页整页向右滑出。 */
    fun pageExit(pop: Boolean): ExitTransition =
        slideOutHorizontally(pageSpec(pop)) { width -> if (pop) width else -width / PAGE_UNDER_SHIFT_DIVISOR }

    /**
     * 跟手返回（系统返回手势拖动中）的位移曲线：导航库按手势进度定位到转场的对应时刻，
     * 线性曲线让位移与进度一一对应，页面跟着手指走；松手后剩余部分按同一曲线走完，取消时由导航库回弹到起点。
     */
    fun <T> pagePredictiveSpec(): FiniteAnimationSpec<T> = tween(DurationMedium, easing = LinearEasing)

    /** 跟手返回的进入：下层页从 -25% 跟手回到原位，位移与 [pageEnter] 返回时相同。 */
    fun pagePredictiveEnter(): EnterTransition =
        slideInHorizontally(pagePredictiveSpec()) { width -> -width / PAGE_UNDER_SHIFT_DIVISOR }

    /** 跟手返回的退出：被弹出的页跟手向右滑出，位移与 [pageExit] 返回时相同。 */
    fun pagePredictiveExit(): ExitTransition = slideOutHorizontally(pagePredictiveSpec()) { width -> width }

    // 不走 NavHost 的整页二级页面（会话的资源管理器等）自行跟手时，按下面的映射与导航页保持一致。

    /** 跟手返回中被弹出的页的位移：随进度从原位移到整页宽度，与 [pagePredictiveExit] 相同。 */
    fun pagePredictiveTopOffset(progress: Float, width: Float): Float = width * progress.coerceIn(0f, 1f)

    /** 跟手返回中下层页的位移：从 -25% 随进度回到原位，与 [pagePredictiveEnter] 相同。 */
    fun pagePredictiveUnderOffset(progress: Float, width: Float): Float =
        width / PAGE_UNDER_SHIFT_DIVISOR * (progress.coerceIn(0f, 1f) - 1f)

    /** 跟手返回中下层页的压暗占完全盖住时的比例：随进度从 1 线性退到 0。 */
    fun pagePredictiveUnderDim(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

    /** 松手后从当前进度走完：与 [pagePredictiveSpec] 同为线性，时长按剩余进度折算，与导航库松手后的走完一致。 */
    fun pagePredictiveSettleSpec(progress: Float): FiniteAnimationSpec<Float> =
        tween((DurationMedium * (1f - progress.coerceIn(0f, 1f))).toInt(), easing = LinearEasing)

    /** 取消后回弹到原位：时长按已拖动的进度折算，标准曲线，与导航库取消时回到起点一致。 */
    fun pagePredictiveCancelSpec(progress: Float): FiniteAnimationSpec<Float> =
        tween((DurationMedium * progress.coerceIn(0f, 1f)).toInt(), easing = Standard)

    /** 栏内同级替换（换设置根页面、换会话）：新页盖在旧页上 150ms 淡入，旧页原地保留到淡入结束再移除。 */
    val paneRootEnter: EnterTransition get() = fadeIn(tween(DurationShort, easing = LinearOutSlowInEasing))
    // 旧页不变透明，只借一段同长的“淡出到 1”把它保留到新页淡入结束，中间不留空白帧。
    val paneRootExit: ExitTransition get() = fadeOut(tween(DurationShort), targetAlpha = 1f)

    /**
     * 标签切换：按标签顺序整页滑动，[direction] 为 1 表示目标在后（右侧或下方），-1 表示在前。
     * [vertical] 为 true 时（竖排导航轨）纵向滑动，否则（底栏）横向；两页都不透明，不做淡入淡出。
     */
    fun tabSlide(direction: Int, vertical: Boolean): ContentTransform {
        val spec = tween<IntOffset>(DurationMedium, easing = PageSlideEasing)
        val enter = if (vertical) slideInVertically(spec) { it * direction } else slideInHorizontally(spec) { it * direction }
        val exit = if (vertical) slideOutVertically(spec) { -it * direction } else slideOutHorizontally(spec) { -it * direction }
        return ContentTransform(enter, exit, sizeTransform = null)
    }

    /** 局部显隐的进入与退出。 */
    val expandIn: EnterTransition =
        expandVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) +
            fadeIn(tween(DurationMedium, easing = LinearOutSlowInEasing))
    val shrinkOut: ExitTransition =
        shrinkVertically(tween(DurationShort + DurationFadeOut, easing = EmphasizedAccelerate)) +
            fadeOut(tween(DurationShort, easing = FastOutLinearInEasing))

    // ── 悬浮菜单（InfinityDropdownMenu）──────────────────────────────
    /** 弹出窗口挂载后等待的帧数，再开始入场；默认 1 帧，以真机 gfxinfo framestats 对比结果为准。 */
    const val MenuEnterWaitFrames = 1
    /** 入场透明度时长：先于缩放到位，让菜单尽早可见。 */
    const val MenuEnterFadeMillis = 90
    /** 入场缩放起点与弹簧（阻尼比 0.8 回弹约 0.12%，肉眼不可见）。 */
    const val MenuEnterScale = 0.92f
    const val MenuEnterSpringDampingRatio = 0.8f
    const val MenuEnterSpringStiffness = 700f
    /** 菜单项错峰：每项晚 18ms，最多前 6 项错开，之后同时出现。 */
    const val MenuItemStaggerMillis = 18
    const val MenuItemStaggerMaxItems = 6
    /** 单个菜单项淡入与上移的时长、位移。 */
    const val MenuItemEnterMillis = 160
    const val MenuItemEnterOffsetDp = 4f
    /** 退场：淡出并缩到 0.96。 */
    const val MenuExitMillis = 100
    const val MenuExitScale = 0.96f

    /** 容器随内容增减改变高度（animateContentSize）：与层级切换的尺寸过渡同一曲线。 */
    fun contentSizeSpec(): FiniteAnimationSpec<IntSize> = tween(DurationMedium, easing = EmphasizedDecelerate)

    private fun sizeTransform(): SizeTransform =
        SizeTransform(clip = false) { _: IntSize, _: IntSize -> tween(DurationMedium, easing = EmphasizedDecelerate) }
}

/**
 * 按层级深度选择方向的共享 X 轴切换；深度相同（如刷新、同级跳转）时退化为交叉淡入。
 * 用于 AnimatedContent 的 transitionSpec。
 */
fun <S> AnimatedContentTransitionScope<S>.hierarchicalTransition(depth: (S) -> Int): ContentTransform {
    val from = depth(initialState)
    val to = depth(targetState)
    return if (from == to) InfinityMotion.fadeThrough() else InfinityMotion.sharedAxisX(forward = to > from)
}
