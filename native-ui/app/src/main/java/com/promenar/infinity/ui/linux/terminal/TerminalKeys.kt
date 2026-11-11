package com.promenar.infinity.ui.linux.terminal

import com.promenar.infinity.ui.ssh.TerminalInput

/**
 * 额外按键行的一次性修饰键：按下后作用于下一次按键（额外按键或系统键盘输入的下一个字符），
 * 再按一次取消；Ctrl 与 Alt 可同时生效。
 */
data class TerminalModifiers(val ctrl: Boolean = false, val alt: Boolean = false) {
    val any: Boolean get() = ctrl || alt

    fun toggleCtrl() = copy(ctrl = !ctrl)

    fun toggleAlt() = copy(alt = !alt)

    companion object {
        val NONE = TerminalModifiers()
    }
}

/** 额外按键行中除修饰键与“键盘”切换以外的按键。[id] 用于测试标签。 */
enum class TerminalExtraKey(val id: String) {
    ESC("esc"),
    TAB("tab"),
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    PIPE("pipe"),
    SLASH("slash"),
    DASH("dash"),
    TILDE("tilde"),
}

/** 把额外按键与修饰键编码为发给 PTY 的字节序列（xterm 约定）。 */
object TerminalKeyEncoder {
    private const val ESC = "\u001B"

    fun encode(key: TerminalExtraKey, modifiers: TerminalModifiers, cursorKeysApplicationMode: Boolean): String = when (key) {
        TerminalExtraKey.ESC -> if (modifiers.alt) ESC + ESC else ESC
        TerminalExtraKey.TAB -> (if (modifiers.alt) ESC else "") + "\t"
        TerminalExtraKey.UP -> arrow('A', modifiers, cursorKeysApplicationMode)
        TerminalExtraKey.DOWN -> arrow('B', modifiers, cursorKeysApplicationMode)
        TerminalExtraKey.RIGHT -> arrow('C', modifiers, cursorKeysApplicationMode)
        TerminalExtraKey.LEFT -> arrow('D', modifiers, cursorKeysApplicationMode)
        TerminalExtraKey.PIPE -> TerminalInput.encode("|", modifiers.ctrl, modifiers.alt)
        // Ctrl+/ 在 xterm 中发送 0x1F（与 Ctrl+_ 相同）。
        TerminalExtraKey.SLASH -> if (modifiers.ctrl) (if (modifiers.alt) ESC else "") + "\u001F" else TerminalInput.encode("/", false, modifiers.alt)
        TerminalExtraKey.DASH -> TerminalInput.encode("-", modifiers.ctrl, modifiers.alt)
        TerminalExtraKey.TILDE -> TerminalInput.encode("~", modifiers.ctrl, modifiers.alt)
    }

    /** 无修饰键时遵循光标键应用模式；带修饰键时使用 `ESC [ 1 ; m X`，m = 1 + Alt·2 + Ctrl·4。 */
    private fun arrow(direction: Char, modifiers: TerminalModifiers, applicationMode: Boolean): String {
        if (!modifiers.any) return TerminalInput.arrow(direction, applicationMode)
        val code = 1 + (if (modifiers.alt) 2 else 0) + (if (modifiers.ctrl) 4 else 0)
        return "$ESC[1;$code$direction"
    }
}

/**
 * 终端页收到的硬件按键是否转交终端视图：终端已挂到窗口、自身没有焦点（焦点在页头按钮等其它控件上），
 * 且不是系统键（返回、音量、主屏等）。
 */
internal fun shouldRedirectTerminalKey(isSystemKey: Boolean, terminalHasFocus: Boolean, terminalAttached: Boolean): Boolean =
    !isSystemKey && !terminalHasFocus && terminalAttached
