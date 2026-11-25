package com.promenar.infinity.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** 当前浮层/弹窗/工作表的关闭回调，供内部空搜索框在 Pre-IME 阶段按 `Esc` 时直接向上关闭外层容器。 */
internal val LocalInfinityOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 跨平台主快捷键修饰键判断：Android / Windows / Linux 默认使用 Ctrl，
 * 同时兼容外接苹果键盘或未来 macOS Desktop 的 Meta（Command）键。
 */
fun KeyEvent.isPrimaryShortcutPressed(): Boolean = isCtrlPressed || isMetaPressed

/** 是否为不带修饰键的 Escape 按下事件。 */
internal fun isPlainEscapeDown(
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
    isCtrlPressed: Boolean,
    isAltPressed: Boolean,
    isMetaPressed: Boolean,
): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.Escape &&
        !isShiftPressed && !isCtrlPressed && !isAltPressed && !isMetaPressed

internal fun isPlainEscapeUp(
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
    isCtrlPressed: Boolean,
    isAltPressed: Boolean,
    isMetaPressed: Boolean,
): Boolean =
    type == KeyEventType.KeyUp &&
        key == Key.Escape &&
        !isShiftPressed && !isCtrlPressed && !isAltPressed && !isMetaPressed

fun KeyEvent.isPlainEscapeDown(): Boolean = isPlainEscapeDown(
    key = key,
    type = type,
    isShiftPressed = isShiftPressed,
    isCtrlPressed = isCtrlPressed,
    isAltPressed = isAltPressed,
    isMetaPressed = isMetaPressed,
)

fun KeyEvent.isPlainEscapeUp(): Boolean = isPlainEscapeUp(
    key = key,
    type = type,
    isShiftPressed = isShiftPressed,
    isCtrlPressed = isCtrlPressed,
    isAltPressed = isAltPressed,
    isMetaPressed = isMetaPressed,
)

/**
 * 在软键盘拦截前（`onInterceptKeyBeforeSoftKeyboard`，防止微信输入法等中文 IME 在非组词态提前吞掉硬件
 * `Enter`/`Esc`）与常规预览阶段（`onPreviewKeyEvent`，覆盖无软键盘阶段与桌面端）挂接硬件快捷键。
 * 当 Pre-IME 阶段返回 `true` 时，事件即被消费，不会重复进入 `onPreviewKeyEvent` 或输入法。
 */
fun Modifier.infinityImeSafeKeyEvent(
    onKeyEvent: (KeyEvent) -> Boolean,
): Modifier = this
    .onInterceptKeyBeforeSoftKeyboard(onKeyEvent)
    .onPreviewKeyEvent(onKeyEvent)

/**
 * 在弹层、对话框、下拉菜单或搜索栏上挂接跨平台 `Esc` 关闭行为。
 * 仅消费纯 `Key.Escape` 按下事件，不干扰组合键或终端 `onPreviewKeyEvent`。
 */
fun Modifier.infinityDismissOnEscape(
    enabled: Boolean = true,
    onDismiss: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    onPreviewKeyEvent { event ->
        if (event.isPlainEscapeDown()) {
            onDismiss()
            true
        } else {
            false
        }
    }
}

/** 聊天主输入框键盘事件决策结果。 */
internal enum class ComposerKeyAction {
    /** 不拦截，交由输入框与 IME 原生处理（如 `Shift+Enter` 换行或输入法选词上屏）。 */
    PASS_THROUGH,
    /** 触发发送（或弹出未选模型提示）。 */
    SUBMIT,
    /** 拦截空白草稿下的裸 Enter 或已消费快捷键的配对抬起事件，避免 IME 误插空行。 */
    CONSUME_NOOP,
    /** 触发 `Esc` 退场（关闭附件菜单、清除错误态或移出输入框焦点）。 */
    DISMISS,
}

/**
 * 聊天主输入框键盘快捷键判定（纯逻辑，跨 Android 硬件键盘与 Compose Desktop）：
 * - 输入法组合态（`isComposing == true`，如中文拼音选词中）一律放行；
 * - 裸 `Enter` / `NumPadEnter`：按下时有可发草稿则发送、空白草稿则吞掉；配对抬起一律吞掉防止中文 IME 补插换行；
 * - `Shift+Enter` / `Alt+Enter` / `Ctrl+Enter` / `Meta+Enter`：交由输入框换行；
 * - 裸 `Esc`：按下触发退场/失焦，抬起吞掉。
 */
internal fun resolveComposerKeyAction(
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
    isCtrlPressed: Boolean,
    isAltPressed: Boolean,
    isMetaPressed: Boolean,
    isComposing: Boolean,
    canSubmit: Boolean,
): ComposerKeyAction {
    if (isComposing) return ComposerKeyAction.PASS_THROUGH
    if (isPlainEscapeDown(key, type, isShiftPressed, isCtrlPressed, isAltPressed, isMetaPressed)) {
        return ComposerKeyAction.DISMISS
    }
    if (isPlainEscapeUp(key, type, isShiftPressed, isCtrlPressed, isAltPressed, isMetaPressed)) {
        return ComposerKeyAction.CONSUME_NOOP
    }
    if (key == Key.Enter || key == Key.NumPadEnter) {
        val hasModifier = isShiftPressed || isCtrlPressed || isAltPressed || isMetaPressed
        if (hasModifier) return ComposerKeyAction.PASS_THROUGH
        return when (type) {
            KeyEventType.KeyDown -> if (canSubmit) ComposerKeyAction.SUBMIT else ComposerKeyAction.CONSUME_NOOP
            KeyEventType.KeyUp -> ComposerKeyAction.CONSUME_NOOP
            else -> ComposerKeyAction.PASS_THROUGH
        }
    }
    return ComposerKeyAction.PASS_THROUGH
}

/** 历史消息内联编辑卡片键盘决策结果。 */
internal enum class InlineEditorKeyAction {
    PASS_THROUGH,
    SUBMIT,
    CANCEL,
    CONSUME_NOOP,
}

/**
 * 历史消息内联编辑卡片键盘快捷键判定：
 * - 多行改写默认 `Enter` 正常换行，`Ctrl+Enter` / `Cmd+Enter` 提交，`Esc` 取消编辑；
 * - 输入法组合态一律放行；已消费快捷键的配对抬起同样拦截，防止中文 IME 补插换行。
 */
internal fun resolveInlineEditorKeyAction(
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
    isCtrlPressed: Boolean,
    isAltPressed: Boolean,
    isMetaPressed: Boolean,
    isComposing: Boolean,
    canSubmit: Boolean,
): InlineEditorKeyAction {
    if (isComposing) return InlineEditorKeyAction.PASS_THROUGH
    if (isPlainEscapeDown(key, type, isShiftPressed, isCtrlPressed, isAltPressed, isMetaPressed)) {
        return InlineEditorKeyAction.CANCEL
    }
    if (isPlainEscapeUp(key, type, isShiftPressed, isCtrlPressed, isAltPressed, isMetaPressed)) {
        return InlineEditorKeyAction.CONSUME_NOOP
    }
    if ((key == Key.Enter || key == Key.NumPadEnter) && (isCtrlPressed || isMetaPressed) && !isAltPressed) {
        return when (type) {
            KeyEventType.KeyDown -> if (canSubmit) InlineEditorKeyAction.SUBMIT else InlineEditorKeyAction.PASS_THROUGH
            KeyEventType.KeyUp -> if (canSubmit) InlineEditorKeyAction.CONSUME_NOOP else InlineEditorKeyAction.PASS_THROUGH
            else -> InlineEditorKeyAction.PASS_THROUGH
        }
    }
    return InlineEditorKeyAction.PASS_THROUGH
}
