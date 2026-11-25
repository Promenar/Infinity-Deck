package com.promenar.infinity.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跨平台键鼠悬停手型光标：启用时悬停显示手型指针，禁用时保持默认指针；触屏无副作用。
 * 使用标准 Compose Multiplatform `pointerHoverIcon`，零平台专属依赖。
 */
fun Modifier.infinityPointerHand(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/**
 * 判断一次鼠标右键（辅助键）按下到释放是否构成有效的上下文菜单点击。
 * 要求：组件启用、拖拽距离不超过触摸容差，且释放点仍在组件可视边界内。
 */
internal fun shouldTriggerSecondaryClick(
    enabled: Boolean,
    downPosition: Offset,
    upPosition: Offset,
    size: IntSize,
    touchSlopPx: Float,
    movedBeyondSlop: Boolean,
): Boolean {
    if (!enabled || movedBeyondSlop) return false
    if (size.width <= 0 || size.height <= 0) return false
    if ((upPosition - downPosition).getDistance() > touchSlopPx) return false
    return upPosition.x in 0f..size.width.toFloat() && upPosition.y in 0f..size.height.toFloat()
}

/**
 * 将鼠标滚轮增量换算为横向滚动像素增量：优先使用横向分量（触控板/倾角滚轮），
 * 仅存在竖向滚轮时映射到横向滚动，让页签栏与横向分段条可直接用滚轮浏览。
 */
internal fun horizontalWheelScrollDeltaPx(
    scrollDelta: Offset,
    stepPx: Float,
): Float {
    val axis = if (abs(scrollDelta.x) > 0.001f) scrollDelta.x else scrollDelta.y
    if (abs(axis) <= 0.001f || stepPx <= 0f) return 0f
    return axis * stepPx
}

/**
 * 监听标准跨平台鼠标右键（辅助键）点击，并在释放时触发 [onSecondaryClick]（通常用于打开上下文菜单或切换多选）。
 *
 * - 仅响应纯右键（`isSecondaryPressed && !isPrimaryPressed`），按下与释放均显式消费事件，
 *   挂在 `combinedClickable` 内层（之后）时可保证右键绝不误触发短按与水波纹；
 * - 若传入 [touch]，同时更新其窗口坐标，使 [InfinityDropdownMenu] 直接从鼠标光标位置弹出。
 */
fun Modifier.infinitySecondaryClick(
    enabled: Boolean = true,
    touch: InfinityMenuTouch? = null,
    onSecondaryClick: () -> Unit,
): Modifier {
    if (!enabled) return this
    val positioned = if (touch != null) onGloballyPositioned { touch.coordinates = it } else this
    return positioned.pointerInput(touch, onSecondaryClick) {
        awaitPointerEventScope {
            val slop = viewConfiguration.touchSlop
            while (true) {
                val pressEvent = awaitPointerEvent(PointerEventPass.Main)
                if (pressEvent.type != PointerEventType.Press) continue
                if (!pressEvent.buttons.isSecondaryPressed || pressEvent.buttons.isPrimaryPressed) continue
                val downChange = pressEvent.changes.firstOrNull { !it.isConsumed && it.pressed && !it.previousPressed }
                    ?: continue

                val pointerId = downChange.id
                val downPos = downChange.position
                var movedBeyondSlop = false
                downChange.consume()
                touch?.coordinates?.takeIf { it.isAttached }?.localToWindow(downPos)?.let { windowPos ->
                    touch.windowPoint = IntOffset(windowPos.x.roundToInt(), windowPos.y.roundToInt())
                }

                while (true) {
                    val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                    val tracked = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                    if ((tracked.position - downPos).getDistance() > slop) {
                        movedBeyondSlop = true
                    }
                    if (!tracked.pressed || nextEvent.type == PointerEventType.Release) {
                        tracked.consume()
                        if (shouldTriggerSecondaryClick(
                                enabled = true,
                                downPosition = downPos,
                                upPosition = tracked.position,
                                size = size,
                                touchSlopPx = slop,
                                movedBeyondSlop = movedBeyondSlop,
                            )
                        ) {
                            onSecondaryClick()
                        }
                        break
                    }
                    tracked.consume()
                }
            }
        }
    }
}

/**
 * 统一「短按 + 长按 + 鼠标右键 + 悬停手型光标」的跨平台交互修饰符。
 *
 * 挂接顺序固定为：外层悬停手型光标 → 中层 `combinedClickable`（触屏点按/长按、键盘无障碍与 `InfinityIndication` 悬停/按压反馈）
 * → 内层 `infinitySecondaryClick`（`Main` 阶段优先于 `combinedClickable` 消费鼠标右键，确保右键打开上下文菜单而不误触短按）。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.infinityContextClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    touch: InfinityMenuTouch? = null,
    onClick: () -> Unit,
): Modifier = this
    .infinityPointerHand(enabled)
    .combinedClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick,
        onClick = onClick,
    )
    .then(
        if (onLongClick != null) {
            Modifier.infinitySecondaryClick(enabled = enabled, touch = touch, onSecondaryClick = onLongClick)
        } else {
            Modifier
        },
    )

/**
 * 带显式 [interactionSource] 与 [indication] 的统一上下文点击修饰符（常用于无需按压水波纹的消息气泡/诊断卡片）。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.infinityContextClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    touch: InfinityMenuTouch? = null,
    pointerHand: Boolean = true,
    onClick: () -> Unit,
): Modifier = this
    .then(if (pointerHand) Modifier.infinityPointerHand(enabled) else Modifier)
    .combinedClickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick,
        onClick = onClick,
    )
    .then(
        if (onLongClick != null) {
            Modifier.infinitySecondaryClick(enabled = enabled, touch = touch, onSecondaryClick = onLongClick)
        } else {
            Modifier
        },
    )

/**
 * 为横向可滚动栏（如页签栏、分段滑块）增加鼠标滚轮横向滚动支持。
 * 仅在当前容器存在横向滚动余量时消费滚轮事件，不影响父级列表竖向滚动。
 */
fun Modifier.infinityHorizontalWheelScroll(
    scrollState: ScrollState,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    return pointerInput(scrollState) {
        awaitPointerEventScope {
            val stepPx = 64f * density
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                if (!scrollState.canScrollBackward && !scrollState.canScrollForward) continue
                val totalDelta = event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
                val rawPx = horizontalWheelScrollDeltaPx(totalDelta, stepPx)
                if (rawPx == 0f) continue
                val consumed = scrollState.dispatchRawDelta(rawPx)
                if (abs(consumed) > 0.01f) {
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
