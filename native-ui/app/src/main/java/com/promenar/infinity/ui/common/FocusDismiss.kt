package com.promenar.infinity.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/*
 * 点按输入框以外的位置时退出输入：清除焦点、收起键盘，输入框回到未激活的外观。
 * 宿主在根部挂 [FocusDismissArea]，其中的输入框挂 [keepsFocusOnTap] 登记自己的范围；
 * 点按不被消费，列表、卡片等控件照常响应。
 */

@Stable
class FocusTapRegions {
    private val regions = mutableMapOf<Any, Rect>()

    fun update(key: Any, rect: Rect?) {
        if (rect == null) regions.remove(key) else regions[key] = rect
    }

    fun contains(position: Offset): Boolean = regions.values.any { it.contains(position) }
}

private val LocalFocusTapRegions = staticCompositionLocalOf<FocusTapRegions?> { null }

/** 点按判定：按下与抬起之间移动不超过这个距离（像素按触控阈值换算前的保守值）。 */
private const val TapSlopPx = 24f

/** 输入框以外的点按退出输入的区域；坐标统一取根布局坐标，区域内外的输入框都可登记。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusDismissArea(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val regions = remember { FocusTapRegions() }
    val focusManager = LocalFocusManager.current
    val origin = remember { arrayOf(Offset.Zero) }
    // 系统返回收起键盘时输入框仍保持焦点，这里一并退出。
    val imeVisible = WindowInsets.isImeVisible
    val imeWasVisible = remember { booleanArrayOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible[0] && !imeVisible) focusManager.clearFocus()
        imeWasVisible[0] = imeVisible
    }
    val area = modifier
        .onGloballyPositioned { origin[0] = it.boundsInRoot().topLeft }
        .pointerInput(regions, focusManager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() > TapSlopPx) return@awaitEachGesture
                if (!regions.contains(origin[0] + down.position)) focusManager.clearFocus()
            }
        }
    CompositionLocalProvider(LocalFocusTapRegions provides regions) { content(area) }
}

/** 登记输入框（含其清除按钮）的范围：在其中点按不退出输入。 */
@Composable
fun Modifier.keepsFocusOnTap(): Modifier {
    val regions = LocalFocusTapRegions.current ?: return this
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.update(key, null) } }
    return onGloballyPositioned { regions.update(key, it.boundsInRoot()) }
}
