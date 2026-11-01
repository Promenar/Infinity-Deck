package com.promenar.infinity.ui.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** 仅桥接远端 PTY；不创建 Termux 本地会话或本地进程。 */
internal class RemoteTerminalBuffer(columns: Int = 80, rows: Int = 24) {
    var onInput: (String) -> Unit = {}
    var onChanged: (() -> Unit)? = null

    val emulator = TerminalEmulator(
        object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                onInput(String(data, offset, count, Charsets.UTF_8))
            }

            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

            // 远端 OSC 52 不获得系统剪贴板权限。
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() { onChanged?.invoke() }
        },
        columns,
        rows,
        1_500,
        SilentTerminalClient,
    )

    fun append(output: String) {
        val bytes = output.toByteArray(Charsets.UTF_8)
        try {
            emulator.append(bytes, bytes.size)
            onChanged?.invoke()
            emulator.clearScrollCounter()
        } finally {
            bytes.fill(0)
        }
    }

    fun clear() {
        onInput = {}
        onChanged = null
        emulator.reset()
        emulator.doDecSetOrReset(true, 1049)
        clearScreen()
        emulator.doDecSetOrReset(false, 1049)
        clearScreen()
        emulator.screen.clearTranscript()
        // reset() 不动光标，退出备用屏幕又会恢复旧光标；移回左上角，之后的写入才从首行首列开始。
        val home = "\u001B[H".toByteArray(Charsets.US_ASCII)
        emulator.append(home, home.size)
    }

    private fun clearScreen() {
        emulator.screen.blockSet(0, 0, emulator.mColumns, emulator.mRows, ' '.code, emulator.screen.getStyleAt(0, 0))
    }
}

/** 库的诊断回调可能包含终端文本，因此全部静默。 */
private object SilentTerminalClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession?) = Unit
    override fun onTitleChanged(changedSession: TerminalSession?) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession?) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession?) = Unit
    override fun onColorsChanged(session: TerminalSession?) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
    override fun logStackTrace(tag: String?, error: Exception?) = Unit
}
