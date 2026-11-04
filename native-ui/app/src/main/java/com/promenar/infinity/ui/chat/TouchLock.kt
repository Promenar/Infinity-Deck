package com.promenar.infinity.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.components.RunDockLabel
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTouchLockAccent
import com.promenar.infinity.ui.theme.InfinityTouchLockContent
import com.promenar.infinity.ui.theme.InfinityTouchLockScrim
import com.promenar.infinity.ui.theme.InfinityTouchLockWarning
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.TouchLockUnlock
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 锁定触摸按钮：生成中点按后锁定触摸。外屏放在顶栏 ⋮ 左侧，内屏放在检视器运行页。 */
@Composable
internal fun TouchLockButton(
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val haptics = LocalInfinityHaptics.current
    IconButton(
        onClick = {
            haptics.toggle(true)
            onLock()
        },
        modifier = modifier.testTag(UiTags.CHAT_TOUCH_LOCK_BUTTON),
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = stringResource(R.string.touch_lock_button_cd),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 触摸锁遮罩上显示的运行阶段。 */
internal enum class TouchLockPhase { RUNNING, APPROVAL, DONE }

/** 有待批准时优先提示批准；生成结束后显示已完成，锁保持到用户解锁。 */
internal fun touchLockPhase(isGenerating: Boolean, pendingApprovals: Int): TouchLockPhase = when {
    pendingApprovals > 0 -> TouchLockPhase.APPROVAL
    isGenerating -> TouchLockPhase.RUNNING
    else -> TouchLockPhase.DONE
}

/** 滑块拖到轨道九成以上才算解锁，避免口袋里的误触滑到一半就解开。 */
internal fun slideUnlockReached(offset: Float, max: Float): Boolean = max > 0f && offset >= max * 0.9f

/** 锁屏第二行只放具体步骤；“执行中”“需要你批准”这类通用状态已在状态行，不重复显示。 */
internal fun touchLockStepTitle(label: RunDockLabel?): String? = when (label) {
    is RunDockLabel.Step -> label.title
    is RunDockLabel.Paused -> label.step
    else -> null
}?.takeIf { it.isNotBlank() }

internal data class TouchLockUiState(
    val phase: TouchLockPhase,
    /** 当前计划步骤；没有具体步骤时为空串，只显示状态行。 */
    val label: String,
    /** 计划进度与已用时长；没有时为空串。 */
    val meta: String = "",
    val progress: Float? = null,
)

/**
 * 生成中锁定触摸：全屏对话框盖住整个窗口（含顶栏与检视器），只有解锁控件响应；
 * 返回键被吞掉，生成在后面照常进行。读屏用户可以直接双击解锁控件。
 */
@Composable
internal fun TouchLockDialog(state: TouchLockUiState, unlock: TouchLockUnlock, onUnlock: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 对话框窗口默认半透明遮暗，改为不遮暗，由内容自己画纯黑底。
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        TouchLockContent(state, unlock, onUnlock, Modifier.fillMaxSize())
    }
}

@Composable
internal fun TouchLockContent(
    state: TouchLockUiState,
    unlock: TouchLockUnlock,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(InfinityTouchLockScrim).testTag(UiTags.CHAT_TOUCH_LOCK),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp).widthIn(max = 360.dp),
        ) {
            val status = when (state.phase) {
                TouchLockPhase.RUNNING -> stringResource(R.string.touch_lock_running)
                TouchLockPhase.APPROVAL -> stringResource(R.string.touch_lock_waiting_approval)
                TouchLockPhase.DONE -> stringResource(R.string.touch_lock_done)
            }
            InfinityMetaLine(
                segments = listOf(status, state.meta),
                style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = if (state.phase == TouchLockPhase.APPROVAL) InfinityTouchLockWarning else InfinityTouchLockContent.copy(alpha = 0.7f),
            )
            if (state.label.isNotBlank()) Text(
                text = state.label,
                style = InfinityTypography.titleMedium,
                color = InfinityTouchLockContent,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            state.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(160.dp).height(2.dp),
                    color = InfinityTouchLockAccent,
                    trackColor = InfinityTouchLockContent.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.height(40.dp))
            when (unlock) {
                TouchLockUnlock.HOLD -> HoldToUnlock(onUnlock)
                TouchLockUnlock.SLIDE -> SlideToUnlock(onUnlock)
            }
            Text(
                text = stringResource(if (unlock == TouchLockUnlock.HOLD) R.string.touch_lock_hold_hint else R.string.touch_lock_slide_hint),
                style = InfinityTypography.labelMedium,
                color = InfinityTouchLockContent.copy(alpha = 0.7f),
            )
        }
    }
}

private const val HoldToUnlockMillis = 1_500

/** 按住锁按钮，圆环走满 1.5 秒后解锁；中途松手圆环退回。 */
@Composable
private fun HoldToUnlock(onUnlock: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalInfinityHaptics.current
    val action = stringResource(R.string.touch_lock_unlock_action)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .semantics {
                role = Role.Button
                contentDescription = action
                onClick(label = action) { onUnlock(); true }
            }
            .testTag(UiTags.CHAT_TOUCH_LOCK_UNLOCK)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    var hold: Job? = null
                    hold = scope.launch {
                        progress.animateTo(1f, tween(HoldToUnlockMillis, easing = LinearEasing))
                        haptics.toggle(false)
                        onUnlock()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                })
            }
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.matchParentSize(),
            color = InfinityTouchLockAccent,
            trackColor = InfinityTouchLockContent.copy(alpha = 0.18f),
            strokeWidth = 3.dp,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = InfinityTouchLockContent, modifier = Modifier.size(28.dp))
    }
}

private val SlideTrackWidth = 240.dp
private val SlideKnobSize = 52.dp
private val SlideTrackPadding = 4.dp

/** 把锁沿轨道拖到尽头解锁；不到九成松手时滑块弹回起点。从右到左布局时向左滑。 */
@Composable
private fun SlideToUnlock(onUnlock: () -> Unit) {
    val knob = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalInfinityHaptics.current
    val action = stringResource(R.string.touch_lock_unlock_action)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val maxOffset = with(LocalDensity.current) { (SlideTrackWidth - SlideKnobSize - SlideTrackPadding * 2).toPx() }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .width(SlideTrackWidth)
            .height(SlideKnobSize + SlideTrackPadding * 2)
            .clip(CircleShape)
            .background(InfinityTouchLockContent.copy(alpha = 0.12f))
            .semantics {
                role = Role.Button
                contentDescription = action
                onClick(label = action) { onUnlock(); true }
            }
            .testTag(UiTags.CHAT_TOUCH_LOCK_UNLOCK),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = SlideTrackPadding)
                .offset { IntOffset(knob.value.roundToInt(), 0) }
                .size(SlideKnobSize)
                .clip(CircleShape)
                .background(InfinityTouchLockContent)
                .pointerInput(maxOffset, direction) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (slideUnlockReached(knob.value, maxOffset)) {
                                haptics.toggle(false)
                                onUnlock()
                            } else {
                                scope.launch { knob.animateTo(0f, tween(200)) }
                            }
                        },
                        onDragCancel = { scope.launch { knob.animateTo(0f, tween(200)) } },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { knob.snapTo((knob.value + dragAmount * direction).coerceIn(0f, maxOffset)) }
                    }
                },
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = InfinityTouchLockScrim, modifier = Modifier.size(22.dp))
        }
    }
}
