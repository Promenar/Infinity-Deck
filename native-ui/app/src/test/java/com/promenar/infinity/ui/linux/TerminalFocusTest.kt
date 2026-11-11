package com.promenar.infinity.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.linux.terminal.shouldRedirectTerminalKey
import org.junit.Test

class TerminalFocusTest {
    @Test
    fun `焦点在页头等控件上时，硬件按键转交终端`() {
        assertThat(shouldRedirectTerminalKey(isSystemKey = false, terminalHasFocus = false, terminalAttached = true)).isTrue()
    }

    @Test
    fun `终端已持有焦点时按键照常进入终端，不重复转交`() {
        assertThat(shouldRedirectTerminalKey(isSystemKey = false, terminalHasFocus = true, terminalAttached = true)).isFalse()
    }

    @Test
    fun `系统键与终端未挂到窗口时交给页面`() {
        // 返回键等系统键仍由页面与导航处理。
        assertThat(shouldRedirectTerminalKey(isSystemKey = true, terminalHasFocus = false, terminalAttached = true)).isFalse()
        assertThat(shouldRedirectTerminalKey(isSystemKey = false, terminalHasFocus = false, terminalAttached = false)).isFalse()
    }
}
