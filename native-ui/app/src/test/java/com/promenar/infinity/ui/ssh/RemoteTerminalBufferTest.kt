package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class RemoteTerminalBufferTest {
    @Test
    fun `远端状态查询响应经输入回调返回而不进入屏幕文字`() {
        val terminal = RemoteTerminalBuffer(20, 4)
        val sent = mutableListOf<String>()
        terminal.onInput = { sent += it }
        terminal.append("hello\u001B[6n")

        assertThat(sent).containsExactly("\u001B[1;6R")
        assertThat(terminal.emulator.screen.transcriptText.trim()).isEqualTo("hello")
    }

    @Test
    fun `远端分批UTF8文字和ANSI控制使用同一仿真状态`() {
        val terminal = RemoteTerminalBuffer(20, 4)
        terminal.append("中文\u001B[3")
        terminal.append("1m红\u001B[0m😀")

        assertThat(terminal.emulator.screen.transcriptText.trim()).isEqualTo("中文红😀")
        assertThat(terminal.emulator.cursorCol).isEqualTo(8)
    }

    @Test
    fun `未知模式及非法剪贴板控制串不把诊断变成崩溃`() {
        val terminal = RemoteTerminalBuffer(20, 4)
        terminal.append("\u001B[?9999\$p\u001B]52;c;!invalid!\u0007ok")

        assertThat(terminal.emulator.screen.transcriptText.trim()).isEqualTo("ok")
    }

    @Test
    fun `释放缓冲清除主备用屏幕历史并解除旧输入回调`() {
        val terminal = RemoteTerminalBuffer(20, 4)
        val sent = mutableListOf<String>()
        terminal.onInput = { sent += it }
        terminal.append("main\u001B[?1049halt")
        terminal.clear()

        assertThat(terminal.emulator.screen.transcriptText.trim()).isEmpty()
        terminal.append("\u001B[?1049l\u001B[6n")
        assertThat(terminal.emulator.screen.transcriptText.trim()).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `清空后光标回到左上角，再写入从首行首列开始`() {
        val terminal = RemoteTerminalBuffer(20, 4)
        terminal.append("one\r\ntwo\r\nprompt$ ")
        terminal.clear()

        assertThat(terminal.emulator.cursorRow).isEqualTo(0)
        assertThat(terminal.emulator.cursorCol).isEqualTo(0)
        terminal.append("again")
        assertThat(terminal.emulator.screen.getSelectedText(0, 0, 19, 0).trim()).isEqualTo("again")
    }
}
