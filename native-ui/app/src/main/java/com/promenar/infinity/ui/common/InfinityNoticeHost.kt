package com.promenar.infinity.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 顶部悬浮通知默认距离状态栏底部的偏移（常规 64dp 顶栏 + 8dp 呼吸间距）。 */
internal val DEFAULT_NOTICE_TOP_OFFSET: Dp = 72.dp

/** 顶部悬浮通知语义分级。 */
enum class InfinityNoticeSeverity {
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
}

/** 通用状态条语义到全局悬浮通知语义的统一映射。 */
fun InfinityStatusKind.toNoticeSeverity(): InfinityNoticeSeverity = when (this) {
    InfinityStatusKind.Success -> InfinityNoticeSeverity.SUCCESS
    InfinityStatusKind.Info -> InfinityNoticeSeverity.INFO
    InfinityStatusKind.Warning -> InfinityNoticeSeverity.WARNING
    InfinityStatusKind.Error -> InfinityNoticeSeverity.ERROR
}

/** 悬浮通知胶囊内的可选动作。 */
@Immutable
data class InfinityNoticeAction(
    val label: String,
    val onClick: () -> Unit,
)

/** 一条全局悬浮通知消息；[id] 用于去重与按标识关闭。 */
@Immutable
data class InfinityNoticeMessage(
    val id: Long,
    val severity: InfinityNoticeSeverity,
    val message: String,
    val subtitle: String? = null,
    val action: InfinityNoticeAction? = null,
    val durationMillis: Long = defaultNoticeDuration(severity, action != null),
)

internal fun defaultNoticeDuration(severity: InfinityNoticeSeverity, hasAction: Boolean): Long = when (severity) {
    InfinityNoticeSeverity.SUCCESS, InfinityNoticeSeverity.INFO -> if (hasAction) 4_200L else 2_800L
    InfinityNoticeSeverity.WARNING, InfinityNoticeSeverity.ERROR -> if (hasAction) 5_500L else 4_200L
}

/** 全局通知胶囊测试标识。 */
object InfinityNoticeTags {
    const val CAPSULE = "infinity_notice_capsule"
    const val ACTION = "infinity_notice_action"
    const val DISMISS = "infinity_notice_dismiss"
}

/** 有序避让注册表：按注册 token 精确更新与注销，防止页面切换退场时误删新页面上报的偏移。 */
@Stable
internal class NoticeClearanceStack(private val fallback: Dp) {
    private data class Entry(val token: Long, val value: Dp)

    private val entries = mutableStateListOf<Entry>()
    private var nextToken = 1L

    val active: Dp
        get() = entries.lastOrNull()?.value ?: fallback

    fun register(initial: Dp): Long {
        val token = nextToken++
        entries.add(Entry(token, initial.coerceAtLeast(0.dp)))
        return token
    }

    fun update(token: Long, value: Dp) {
        val clamped = value.coerceAtLeast(0.dp)
        val index = entries.indexOfFirst { it.token == token }
        if (index >= 0 && entries[index].value != clamped) {
            entries[index] = Entry(token, clamped)
        }
    }

    fun unregister(token: Long) {
        entries.removeAll { it.token == token }
    }
}

/** 悬浮通知胶囊水平可用区间与最大宽度计算结果。 */
internal data class NoticeHorizontalPlacement(
    val startInset: Dp,
    val endInset: Dp,
    val maxCapsuleWidth: Dp,
)

/**
 * 计算悬浮通知胶囊在当前屏幕宽度与两侧面板避让下的落位：
 * 仅当扣除左侧导航条与右侧悬浮检视器后剩余聊天区宽度 >= 320dp 时让位右侧检视器；
 * 否则回退为扣除左侧栏后的全宽居中，防止大屏竖屏或窄窗下胶囊被过度挤压。
 */
internal fun computeNoticeHorizontalPlacement(
    maxWidth: Dp,
    startInset: Dp,
    rawEndInset: Dp,
): NoticeHorizontalPlacement {
    val safeStart = startInset.coerceAtLeast(0.dp)
    val safeEnd = rawEndInset.coerceAtLeast(0.dp)
    val effectiveEnd = if (safeEnd > 0.dp && (maxWidth - safeStart - safeEnd) >= 320.dp) safeEnd else 0.dp
    val availableWidth = (maxWidth - safeStart - effectiveEnd - 32.dp).coerceAtLeast(180.dp)
    val maxCapsuleWidth = minOf(
        if (maxWidth >= 600.dp && effectiveEnd == 0.dp) 420.dp else 380.dp,
        availableWidth,
    )
    return NoticeHorizontalPlacement(
        startInset = safeStart,
        endInset = effectiveEnd,
        maxCapsuleWidth = maxCapsuleWidth,
    )
}

/**
 * 全站顶部悬浮通知控制器：
 * - 单条替换队列：新提示直接顶替旧提示，空白消息忽略并返回 -1L；
 * - 维护顶部、左侧、右侧三组有序避让栈，脱离文档流锚定于当前活跃顶栏下沿 + 8dp，
 *   并在展开内屏开启右侧检视器时自动于左侧对话区水平居中。
 */
@Stable
class InfinityNoticeController {
    var current by mutableStateOf<InfinityNoticeMessage?>(null)
        private set

    private var nextMessageId = 1L
    private val topOffsets = NoticeClearanceStack(DEFAULT_NOTICE_TOP_OFFSET)
    private val startOffsets = NoticeClearanceStack(0.dp)
    private val endOffsets = NoticeClearanceStack(0.dp)

    val activeTopOffset: Dp
        get() = topOffsets.active

    val activeStartOffset: Dp
        get() = startOffsets.active

    val activeEndOffset: Dp
        get() = endOffsets.active

    fun show(
        severity: InfinityNoticeSeverity,
        message: String,
        subtitle: String? = null,
        action: InfinityNoticeAction? = null,
        durationMillis: Long? = null,
    ): Long {
        val normalized = message.trim()
        if (normalized.isEmpty()) return -1L
        val id = nextMessageId++
        val resolvedDuration = (durationMillis ?: defaultNoticeDuration(severity, action != null)).coerceAtLeast(500L)
        current = InfinityNoticeMessage(
            id = id,
            severity = severity,
            message = normalized,
            subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
            action = action,
            durationMillis = resolvedDuration,
        )
        return id
    }

    fun showStatus(
        kind: InfinityStatusKind,
        message: String,
        subtitle: String? = null,
        action: InfinityNoticeAction? = null,
        durationMillis: Long? = null,
    ): Long = show(
        severity = kind.toNoticeSeverity(),
        message = message,
        subtitle = subtitle,
        action = action,
        durationMillis = durationMillis,
    )

    fun dismiss(id: Long? = null) {
        val active = current ?: return
        if (id == null || active.id == id) {
            current = null
        }
    }

    internal fun registerTopOffset(value: Dp): Long = topOffsets.register(value)
    internal fun updateTopOffset(token: Long, value: Dp) = topOffsets.update(token, value)
    internal fun unregisterTopOffset(token: Long) = topOffsets.unregister(token)

    internal fun registerStartOffset(value: Dp): Long = startOffsets.register(value)
    internal fun updateStartOffset(token: Long, value: Dp) = startOffsets.update(token, value)
    internal fun unregisterStartOffset(token: Long) = startOffsets.unregister(token)

    internal fun registerEndOffset(value: Dp): Long = endOffsets.register(value)
    internal fun updateEndOffset(token: Long, value: Dp) = endOffsets.update(token, value)
    internal fun unregisterEndOffset(token: Long) = endOffsets.unregister(token)
}

/** 全站唯一顶部悬浮通知控制器入口，由根宿主提供。 */
val LocalInfinityNoticeController = staticCompositionLocalOf<InfinityNoticeController?> { null }

/** 声明当前活跃界面要求悬浮通知避让的顶部偏移（相对状态栏底沿）。 */
@Composable
fun ReportInfinityNoticeTopOffset(
    topOffset: Dp,
    enabled: Boolean = true,
) {
    val controller = LocalInfinityNoticeController.current ?: return
    val latestTopOffset by rememberUpdatedState(topOffset)
    var activeToken by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(controller, enabled) {
        if (!enabled) {
            activeToken = null
            return@DisposableEffect onDispose {}
        }
        val token = controller.registerTopOffset(latestTopOffset)
        activeToken = token
        onDispose {
            if (activeToken == token) activeToken = null
            controller.unregisterTopOffset(token)
        }
    }
    LaunchedEffect(controller, activeToken, topOffset) {
        val token = activeToken ?: return@LaunchedEffect
        controller.updateTopOffset(token, topOffset)
    }
}

/** 声明当前活跃界面左右边缘已被导航轨或右侧浮动面板占用的水平避让宽度。 */
@Composable
fun ReportInfinityNoticeHorizontalClearance(
    startOffset: Dp? = null,
    endOffset: Dp? = null,
    enabled: Boolean = true,
) {
    val controller = LocalInfinityNoticeController.current ?: return
    if (startOffset != null) {
        val latestStart by rememberUpdatedState(startOffset)
        var startToken by remember { mutableStateOf<Long?>(null) }
        DisposableEffect(controller, enabled) {
            if (!enabled) {
                startToken = null
                return@DisposableEffect onDispose {}
            }
            val token = controller.registerStartOffset(latestStart)
            startToken = token
            onDispose {
                if (startToken == token) startToken = null
                controller.unregisterStartOffset(token)
            }
        }
        LaunchedEffect(controller, startToken, startOffset) {
            val token = startToken ?: return@LaunchedEffect
            controller.updateStartOffset(token, startOffset)
        }
    }
    if (endOffset != null) {
        val latestEnd by rememberUpdatedState(endOffset)
        var endToken by remember { mutableStateOf<Long?>(null) }
        DisposableEffect(controller, enabled) {
            if (!enabled) {
                endToken = null
                return@DisposableEffect onDispose {}
            }
            val token = controller.registerEndOffset(latestEnd)
            endToken = token
            onDispose {
                if (endToken == token) endToken = null
                controller.unregisterEndOffset(token)
            }
        }
        LaunchedEffect(controller, endToken, endOffset) {
            val token = endToken ?: return@LaunchedEffect
            controller.updateEndOffset(token, endOffset)
        }
    }
}

/**
 * 量取当前顶栏实际高度并在加上 [gap] 后上报给 [LocalInfinityNoticeController]，
 * 自动适配标准字号、大字号（`1.3x`）与双行状态顶栏，须挂在已扣除状态栏高度的顶栏内容节点上。
 */
@Composable
fun Modifier.reportInfinityNoticeHeaderBottom(
    gap: Dp = 8.dp,
    enabled: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    var measuredHeight by remember { mutableStateOf<Dp?>(null) }
    val validHeight = measuredHeight
    ReportInfinityNoticeTopOffset(
        topOffset = (validHeight ?: 64.dp) + gap,
        enabled = enabled && validHeight != null,
    )
    return if (!enabled) {
        this
    } else {
        onGloballyPositioned { coordinates ->
            val heightPx = coordinates.size.height
            if (heightPx > 0) {
                measuredHeight = with(density) { heightPx.toDp() }
            }
        }
    }
}

/**
 * 全站根级顶部悬浮通知胶囊宿主（方案 A：熏黑微光指示舱）：
 * - 脱离文档流置于安全区顶部居中，绝不引起正文高度跳变；
 * - 随活跃顶栏高度与左右避让动态调整落位；
 * - 仅在通知出现与退出动画期间请求 [onOverlayActiveChange] 激活背景模糊录制，平时零绘制开销。
 */
@Composable
fun InfinityNoticeHost(
    controller: InfinityNoticeController,
    backdrop: BackdropSource?,
    modifier: Modifier = Modifier,
    onOverlayActiveChange: (Boolean) -> Unit = {},
) {
    val currentMessage = controller.current
    var renderedMessage by remember { mutableStateOf<InfinityNoticeMessage?>(null) }
    if (currentMessage != null) {
        renderedMessage = currentMessage
    }

    val visibilityState = remember { MutableTransitionState(false) }
    LaunchedEffect(currentMessage) {
        visibilityState.targetState = currentMessage != null
    }

    val overlayActive = visibilityState.currentState || visibilityState.targetState
    LaunchedEffect(overlayActive) {
        onOverlayActiveChange(overlayActive)
    }
    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState, currentMessage) {
        if (visibilityState.isIdle && !visibilityState.currentState && currentMessage == null) {
            renderedMessage = null
        }
    }

    LaunchedEffect(currentMessage?.id) {
        val active = currentMessage ?: return@LaunchedEffect
        delay(active.durationMillis)
        controller.dismiss(active.id)
    }

    val animationsEnabled = rememberAnimationsEnabled() && !LocalInspectionMode.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val placement = computeNoticeHorizontalPlacement(
            maxWidth = maxWidth,
            startInset = controller.activeStartOffset,
            rawEndInset = controller.activeEndOffset,
        )
        val startInset = placement.startInset
        val effectiveEndInset = placement.endInset
        val maxCapsuleWidth = placement.maxCapsuleWidth
        val enterTransition = if (animationsEnabled) {
            slideInVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) { -it / 2 } +
                fadeIn(tween(InfinityMotion.DurationShort))
        } else {
            fadeIn(tween(InfinityMotion.DurationShort))
        }
        val exitTransition = if (animationsEnabled) {
            slideOutVertically(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedAccelerate)) { -it / 2 } +
                fadeOut(tween(InfinityMotion.DurationShort))
        } else {
            fadeOut(tween(InfinityMotion.DurationShort))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    top = controller.activeTopOffset,
                    start = startInset + 16.dp,
                    end = effectiveEndInset + 16.dp,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedVisibility(
                visibleState = visibilityState,
                enter = enterTransition,
                exit = exitTransition,
                label = "infinity_notice_capsule",
            ) {
                renderedMessage?.let { active ->
                    val minWidth = 180.dp.coerceAtMost(maxCapsuleWidth)
                    InfinityNoticeCapsule(
                        message = active,
                        backdrop = backdrop,
                        onDismiss = { controller.dismiss(active.id) },
                        modifier = Modifier.widthIn(min = minWidth, max = maxCapsuleWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfinityNoticeCapsule(
    message: InfinityNoticeMessage,
    backdrop: BackdropSource?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = domain.isDark
    val frosted = LocalInfinityMotionStyles.current.surface == ComposerSurfaceStyle.FROSTED
    val inspectionMode = LocalInspectionMode.current
    val density = LocalDensity.current
    val latestDismiss by rememberUpdatedState(onDismiss)

    var swipeOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }

    val accent = when (message.severity) {
        InfinityNoticeSeverity.SUCCESS -> domain.success
        InfinityNoticeSeverity.INFO -> scheme.primary
        InfinityNoticeSeverity.WARNING -> domain.warning
        InfinityNoticeSeverity.ERROR -> domain.ragError
    }
    val icon: ImageVector = when (message.severity) {
        InfinityNoticeSeverity.SUCCESS -> Icons.Rounded.CheckCircle
        InfinityNoticeSeverity.INFO -> Icons.Rounded.Info
        InfinityNoticeSeverity.WARNING -> Icons.Rounded.WarningAmber
        InfinityNoticeSeverity.ERROR -> Icons.Rounded.ErrorOutline
    }

    val useRealBlur = frosted && !inspectionMode && backdrop != null
    val frostedTint = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.88f) else InfinityPureWhite.copy(alpha = 0.90f)
    val containerColor = when {
        useRealBlur -> Color.Transparent
        dark && frosted && inspectionMode -> InfinityObsidianGlassTint
        dark && frosted -> InfinityObsidianGlassTint.copy(alpha = 0.94f)
        dark -> InfinityObsidianSolidContainer
        frosted && inspectionMode -> InfinityPureWhite
        frosted -> InfinityPureWhite.copy(alpha = 0.96f)
        else -> scheme.surfaceContainerLowest
    }
    val borderColor = if (dark) {
        InfinityPureWhite.copy(alpha = if (frosted) 0.16f else 0.14f)
    } else {
        scheme.outlineVariant.copy(alpha = if (frosted) 0.58f else 0.62f)
    }
    val topHighlight = if (dark) {
        InfinityPureWhite.copy(alpha = 0.14f)
    } else {
        InfinityPureWhite.copy(alpha = 0.92f)
    }
    val shape = RoundedCornerShape(24.dp)
    val dismissThresholdPx = with(density) { 28.dp.toPx() }

    Surface(
        modifier = modifier
            .offset { IntOffset(0, swipeOffsetPx.roundToInt().coerceAtMost(0)) }
            .pointerInput(message.id, dismissThresholdPx) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (swipeOffsetPx <= -dismissThresholdPx) {
                            latestDismiss()
                        } else {
                            swipeOffsetPx = 0f
                        }
                    },
                    onDragCancel = { swipeOffsetPx = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    val next = (swipeOffsetPx + dragAmount).coerceAtMost(0f)
                    swipeOffsetPx = next
                    if (next <= -dismissThresholdPx * 1.4f) {
                        latestDismiss()
                    }
                }
            }
            .semantics {
                liveRegion = if (message.severity == InfinityNoticeSeverity.ERROR || message.severity == InfinityNoticeSeverity.WARNING) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
            .testTag(InfinityNoticeTags.CAPSULE),
        shape = shape,
        color = containerColor,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 12.dp,
    ) {
        Box(Modifier.clip(shape)) {
            if (useRealBlur) {
                FrostedBackdrop(
                    source = backdrop,
                    tint = frostedTint,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                Modifier
                    .drawWithCache {
                        val hiPx = 18.dp.toPx().coerceAtMost(size.height)
                        val hiBrush = Brush.verticalGradient(
                            0f to topHighlight,
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = hiPx,
                        )
                        val center = Offset(26.dp.toPx().coerceAtMost(size.width * 0.25f), size.height / 2f)
                        val radius = maxOf(size.height * 2.2f, 110.dp.toPx())
                        val glowBrush = Brush.radialGradient(
                            0f to accent.copy(alpha = if (dark) 0.20f else 0.11f),
                            0.58f to accent.copy(alpha = if (dark) 0.06f else 0.03f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        )
                        onDrawBehind {
                            drawRect(brush = glowBrush)
                            drawRect(brush = hiBrush, size = Size(size.width, hiPx))
                        }
                    },
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = if (dark) 0.18f else 0.12f))
                            .border(0.5.dp, accent.copy(alpha = if (dark) 0.30f else 0.24f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = message.message.keepCjkTailTogether(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        message.subtitle?.let { sub ->
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    message.action?.let { action ->
                        Surface(
                            onClick = {
                                action.onClick()
                                onDismiss()
                            },
                            shape = CircleShape,
                            color = scheme.onSurface.copy(alpha = if (dark) 0.10f else 0.07f),
                            border = BorderStroke(0.5.dp, borderColor),
                            modifier = Modifier.heightIn(min = 30.dp).testTag(InfinityNoticeTags.ACTION),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = action.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = accent,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.size(36.dp).testTag(InfinityNoticeTags.DISMISS),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.status_strip_dismiss),
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
