package com.promenar.infinity.data.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** 谁在操作浏览器：Agent 动作只能在 [IDLE] 或 [AGENT] 下执行。 */
enum class BrowserMode { IDLE, AGENT, USER, HANDOFF }

enum class BrowserActionKind { OPEN, SNAPSHOT, CLICK, TYPE }

enum class BrowserHandoffReason { LOGIN, PAYMENT, VERIFICATION }

data class BrowserAgentAction(val kind: BrowserActionKind, val ref: String? = null, val label: String = "")

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val mode: BrowserMode = BrowserMode.IDLE,
    val action: BrowserAgentAction? = null,
    val handoff: BrowserHandoffReason? = null,
    val displayMode: BrowserDisplayMode = BrowserDisplayMode.MOBILE,
    val tabs: List<BrowserTabState> = emptyList(),
    val currentTabId: String? = null,
    /** 界面控制失败的稳定错误码，成功操作后清空。 */
    val lastErrorCode: String? = null,
)

/** 浏览器操作权的状态机；页面信息由宿主经 [updatePage] 写入。 */
class BrowserControl(initial: BrowserUiState = BrowserUiState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    fun updatePage(transform: (BrowserUiState) -> BrowserUiState) = _state.update(transform)

    /** 用户接手或填写敏感字段期间挂起；超时返回 false。 */
    suspend fun awaitAgentTurn(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { _state.first { it.mode == BrowserMode.IDLE || it.mode == BrowserMode.AGENT } } != null

    fun agentStarted(action: BrowserAgentAction) = _state.update {
        if (it.mode == BrowserMode.IDLE || it.mode == BrowserMode.AGENT) it.copy(mode = BrowserMode.AGENT, action = action, handoff = null) else it
    }

    /** Agent 一段时间没有新动作后回到空闲。 */
    fun agentIdle() = _state.update { if (it.mode == BrowserMode.AGENT) it.copy(mode = BrowserMode.IDLE, action = null) else it }

    /** 用户点“接手”或在 Agent 操作时触碰页面；空闲时触碰不改变状态。 */
    fun userTakeOver(explicit: Boolean) = _state.update {
        when {
            it.mode == BrowserMode.AGENT -> it.copy(mode = BrowserMode.USER, handoff = null)
            explicit && it.mode == BrowserMode.IDLE -> it.copy(mode = BrowserMode.USER, action = null)
            else -> it
        }
    }

    fun requestHandoff(reason: BrowserHandoffReason, action: BrowserAgentAction) =
        _state.update { it.copy(mode = BrowserMode.HANDOFF, handoff = reason, action = action) }

    /** 用户交回：从接手或交接态回到 Agent。 */
    fun handBack() = _state.update {
        if (it.mode == BrowserMode.USER || it.mode == BrowserMode.HANDOFF) it.copy(mode = BrowserMode.AGENT, handoff = null) else it
    }

    /** 交接等待被取消或超时时退出交接态，不影响用户主动接手。 */
    fun endHandoff() = _state.update {
        if (it.mode == BrowserMode.HANDOFF) it.copy(mode = BrowserMode.IDLE, handoff = null, action = null) else it
    }

    suspend fun awaitHandBack(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { _state.first { it.mode != BrowserMode.HANDOFF } } != null
}

sealed interface BrowserAvailability {
    data object Supported : BrowserAvailability
    /** 系统 WebView 不支持独立配置文件；[currentVersion] 为空表示没有可用 WebView。 */
    data class Unsupported(val currentVersion: String?) : BrowserAvailability
}

/** 浏览器工具使用的宿主接口；实现负责线程切换、操作权等待与页面加载等待。 */
interface BrowserController {
    fun availability(): BrowserAvailability
    suspend fun open(sessionId: String, url: String): BrowserSnapshot
    suspend fun snapshot(sessionId: String, includeText: Boolean, textOffset: Int): BrowserSnapshot
    suspend fun describe(sessionId: String, ref: String): BrowserElement?
    suspend fun click(sessionId: String, element: BrowserElement): BrowserSnapshot
    suspend fun type(sessionId: String, element: BrowserElement, text: String, submit: Boolean): BrowserSnapshot
    suspend fun evaluate(sessionId: String, code: String): String = unsupportedBrowserOperation()
    suspend fun console(sessionId: String, levels: Set<String>, sinceLastRead: Boolean): List<BrowserConsoleEntry> = unsupportedBrowserOperation()
    suspend fun dom(sessionId: String, selector: String, limit: Int, maxChars: Int, textOnly: Boolean): String = unsupportedBrowserOperation()
    suspend fun network(sessionId: String): List<BrowserNetworkEntry> = unsupportedBrowserOperation()
    suspend fun screenshot(sessionId: String): ByteArray = unsupportedBrowserOperation()
    suspend fun setDisplayMode(sessionId: String, mode: BrowserDisplayMode): BrowserUiState = unsupportedBrowserOperation()
    suspend fun listTabs(sessionId: String): BrowserUiState = unsupportedBrowserOperation()
    suspend fun newTab(sessionId: String, url: String): BrowserUiState = unsupportedBrowserOperation()
    suspend fun switchTab(sessionId: String, tabId: String): BrowserUiState = unsupportedBrowserOperation()
    suspend fun closeTab(sessionId: String, tabId: String): BrowserUiState = unsupportedBrowserOperation()
    /** 把页面交给用户填写敏感字段，直到用户交回后返回新快照。 */
    suspend fun handOff(sessionId: String, element: BrowserElement, reason: BrowserHandoffReason): BrowserSnapshot
}

private fun unsupportedBrowserOperation(): Nothing = throw BrowserActionException("UNSUPPORTED_OPERATION", "浏览器宿主不支持此操作。")
