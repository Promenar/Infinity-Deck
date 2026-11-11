package com.promenar.infinity.ui.linux.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxTerminalSessions
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityMonospaceTypeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 终端视图的宿主侧控制：同时充当 [TerminalViewClient]（视图输入回调）与 [TerminalSessionClient]（会话输出回调）。
 * 视图重建（旋转、切换页签）时由 [LocalTerminalPane] 重新绑定，会话本身不受影响。
 */
class TerminalBridge(private val context: Context) : TerminalViewClient, TerminalSessionClient {
    var view: TerminalView? = null
        internal set

    /** 视图停在最新输出处（未向上翻看历史）。 */
    var atBottom by mutableStateOf(true)
        private set

    var readControl: () -> Boolean = { false }
    var readAlt: () -> Boolean = { false }
    var textSizeSp: Float = DEFAULT_TEXT_SIZE_SP
        private set
    internal var onTextSizeChanged: (Float) -> Unit = {}

    /** 由 [LocalTerminalPane] 按当前主题写入；会话颜色表在创建、复位和主题切换时套用。 */
    internal var themeColors: TerminalThemeColors? = null

    /** 返回颜色表是否发生变化。 */
    internal fun applyTheme(emulator: TerminalEmulator?): Boolean {
        val theme = themeColors ?: return false
        val colors = emulator?.mColors ?: return false
        return TerminalThemeApplier.apply(colors, theme)
    }

    fun scrollToBottom() {
        val target = view ?: return
        target.topRow = 0
        target.invalidate()
        atBottom = true
    }

    fun showKeyboard() {
        val target = view ?: return
        target.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 让终端视图持有键盘焦点，不弹出软键盘：外接键盘与 adb 按键直接进入终端，
     * 不会走 Compose 的焦点遍历落到页头按钮上。视图尚未挂到窗口时等挂上后再请求。
     */
    fun focusTerminal() {
        val target = view ?: return
        target.post { if (view === target && !target.hasFocus()) target.requestFocus() }
    }

    /**
     * 页面上其它控件（例如页头返回）持有键盘焦点时收到的硬件按键：焦点交还终端并把按键转给它，
     * 回车因此不会点中页头按钮。返回是否已处理；系统键（返回、音量等）照常交给页面。
     */
    fun redirectKey(event: KeyEvent): Boolean {
        val target = view ?: return false
        if (!shouldRedirectTerminalKey(event.isSystem, target.hasFocus(), target.isAttachedToWindow)) return false
        if (!target.requestFocus()) return false
        return target.dispatchKeyEvent(event)
    }

    fun hideKeyboard() {
        val target = view ?: return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    internal fun refreshScrollState() {
        val top = view?.topRow ?: 0
        val bottom = top >= 0
        if (bottom != atBottom) atBottom = bottom
    }

    // ── TerminalSessionClient ──
    override fun onTextChanged(changedSession: TerminalSession) {
        view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
        refreshScrollState()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        view?.invalidate()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        applyTheme(session.emulator)
        view?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // ── TerminalViewClient ──
    /** 双指缩放调整字号；超过阈值后应用一次并把累计比例复位为 1。 */
    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        val next = (textSizeSp + if (scale > 1f) 1f else -1f).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (next != textSizeSp) {
            textSizeSp = next
            onTextSizeChanged(next)
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** 与 SSH 终端一致：按字符输入，关闭联想，避免输入法组合文本被重复发送。 */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = readControl()

    override fun readAltKey(): Boolean = readAlt()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        applyTheme(view?.mEmulator)
        view?.invalidate()
    }

    // 终端库的诊断日志可能包含终端文本，全部静默。
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    companion object {
        /** 与 SSH 终端相同的默认字号。 */
        const val DEFAULT_TEXT_SIZE_SP = 14f
        const val MIN_TEXT_SIZE_SP = 9f
        const val MAX_TEXT_SIZE_SP = 24f
    }
}

@Composable
fun rememberTerminalBridge(): TerminalBridge {
    val context = LocalContext.current
    return remember(context) { TerminalBridge(context) }
}

/**
 * 把 termux [TerminalView] 绑定到 [sessionId] 对应的会话。视图销毁或切换会话时解绑回调，会话继续运行。
 */
@Composable
fun LocalTerminalPane(
    sessionId: String,
    terminals: LinuxTerminalSessions,
    bridge: TerminalBridge,
    readControl: () -> Boolean,
    readAlt: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentReadControl by rememberUpdatedState(readControl)
    val currentReadAlt by rememberUpdatedState(readAlt)
    var textSizeSp by remember { mutableFloatStateOf(bridge.textSizeSp) }
    bridge.readControl = { currentReadControl() }
    bridge.readAlt = { currentReadAlt() }
    bridge.onTextSizeChanged = { textSizeSp = it }
    val textSizePx = with(density) { textSizeSp.sp.roundToPx() }
    val themeColors = TerminalThemeColors.from(MaterialTheme.colorScheme)
    bridge.themeColors = themeColors

    DisposableEffect(sessionId, bridge) {
        terminals.attachClient(sessionId, bridge)
        onDispose { terminals.attachClient(sessionId, null) }
    }

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isSaveEnabled = false
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                contentDescription = context.getString(R.string.linux_terminal_accessibility)
                setTerminalViewClient(bridge)
                setTextSize(textSizePx)
                // setTypeface 复用已有渲染器的字号，必须在 setTextSize 之后；之后改字号会沿用此字体。
                setTypeface(infinityMonospaceTypeface(context))
                tag = textSizePx
                val listener = ViewTreeObserver.OnDrawListener { bridge.refreshScrollState() }
                viewTreeObserver.addOnDrawListener(listener)
                addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) = Unit
                    override fun onViewDetachedFromWindow(v: android.view.View) {
                        v.viewTreeObserver.removeOnDrawListener(listener)
                    }
                })
                bridge.view = this
            }
        },
        update = { view ->
            bridge.view = view
            if (view.tag != textSizePx) {
                view.setTextSize(textSizePx)
                view.tag = textSizePx
            }
            val session = terminals.session(sessionId)
            if (session != null && view.currentSession !== session) {
                view.attachSession(session)
                view.onScreenUpdated()
                bridge.refreshScrollState()
            }
            // 引用 themeColors 让主题切换时重新执行本块；会话尚未建立终端时由 onEmulatorSet 补上。
            bridge.themeColors = themeColors
            if (bridge.applyTheme(view.mEmulator)) view.invalidate()
        },
        onRelease = { view ->
            if (bridge.view === view) bridge.view = null
        },
        modifier = modifier.testTag(UiTags.LINUX_TERMINAL_VIEW),
    )
    // 打开终端页、切到另一个终端页签时，终端默认持有键盘焦点。
    LaunchedEffect(sessionId, bridge) { bridge.focusTerminal() }
}
