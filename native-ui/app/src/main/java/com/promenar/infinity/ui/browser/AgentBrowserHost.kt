package com.promenar.infinity.ui.browser

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.promenar.infinity.data.browser.BrowserActionException
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserController
import com.promenar.infinity.data.browser.BrowserElement
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserSnapshot
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.data.browser.BrowserConsoleEntry
import com.promenar.infinity.data.browser.BrowserDisplayMode
import com.promenar.infinity.data.browser.BrowserNetworkEntry
import com.promenar.infinity.data.browser.BrowserPagePool
import com.promenar.infinity.data.browser.BrowserSessionEpochs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 应用级 Agent 浏览器宿主：会话保存标签元数据，全局最多保留 [MAX_WEBVIEWS] 个 WebView。
 * WebView 只在主线程创建与操作；页面不随进程持久化，Cookie 保存在独立的 Agent 浏览器配置文件。
 */
class AgentBrowserHost(private val app: Context) : BrowserController {
    private val main = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val sessions = LinkedHashMap<String, AgentBrowserSession>(4, 0.75f, true)
    private val pool = BrowserPagePool(MAX_WEBVIEWS)
    private val epochs = BrowserSessionEpochs()
    private val _sessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 已打开浏览器的聊天会话，界面据此显示浏览器入口。 */
    val sessionIds: StateFlow<Set<String>> = _sessionIds.asStateFlow()
    private val scripts = scope.async(Dispatchers.IO) {
        app.assets.open("browser/agent-page.js").bufferedReader().use { it.readText() } to
            app.assets.open("browser/developer-page.js").bufferedReader().use { it.readText() }
    }
    private var loadedScripts: Pair<String, String>? = null
    private val availability by lazy { browserAvailability(app) }

    override fun availability(): BrowserAvailability = availability

    /** 仅主线程调用；会话未打开过浏览器时为空。 */
    fun session(sessionId: String): AgentBrowserSession? = sessions[sessionId]

    fun state(sessionId: String): StateFlow<BrowserUiState>? = sessions[sessionId]?.control?.state

    fun close(sessionId: String) {
        epochs.close(sessionId)
        sessions.remove(sessionId)?.destroy()
        _sessionIds.update { it - sessionId }
    }

    /** 用户从地址栏打开网址，会话还没有浏览器时一并创建；仅主线程调用。 */
    fun openByUser(sessionId: String, url: String) = userAction(sessionId) { it.userOpen(url) }

    override suspend fun open(sessionId: String, url: String): BrowserSnapshot = withContext(main) { obtainReady(sessionId).open(url) }

    override suspend fun snapshot(sessionId: String, includeText: Boolean, textOffset: Int): BrowserSnapshot =
        withContext(main) { existing(sessionId).snapshot(includeText, textOffset) }

    override suspend fun describe(sessionId: String, ref: String): BrowserElement? = withContext(main) { existing(sessionId).describe(ref) }

    override suspend fun click(sessionId: String, element: BrowserElement): BrowserSnapshot =
        withContext(main) { existing(sessionId).click(element) }

    override suspend fun type(sessionId: String, element: BrowserElement, text: String, submit: Boolean): BrowserSnapshot =
        withContext(main) { existing(sessionId).type(element, text, submit) }

    override suspend fun handOff(sessionId: String, element: BrowserElement, reason: BrowserHandoffReason): BrowserSnapshot =
        withContext(main) { existing(sessionId).handOff(element, reason) }

    override suspend fun evaluate(sessionId: String, code: String): String = withContext(main) { existing(sessionId).evaluate(code) }
    override suspend fun console(sessionId: String, levels: Set<String>, sinceLastRead: Boolean): List<BrowserConsoleEntry> =
        withContext(main) { existing(sessionId).console(levels, sinceLastRead) }
    override suspend fun dom(sessionId: String, selector: String, limit: Int, maxChars: Int, textOnly: Boolean): String =
        withContext(main) { existing(sessionId).dom(selector, limit, maxChars, textOnly) }
    override suspend fun network(sessionId: String): List<BrowserNetworkEntry> = withContext(main) { existing(sessionId).network() }
    override suspend fun screenshot(sessionId: String): ByteArray = withContext(main) { existing(sessionId).screenshot() }
    override suspend fun setDisplayMode(sessionId: String, mode: BrowserDisplayMode): BrowserUiState = withContext(main) { obtainReady(sessionId).setDisplayMode(mode) }
    override suspend fun listTabs(sessionId: String): BrowserUiState = withContext(main) { obtainReady(sessionId).listTabs() }
    override suspend fun newTab(sessionId: String, url: String): BrowserUiState = withContext(main) { obtainReady(sessionId).newTab(url) }
    override suspend fun switchTab(sessionId: String, tabId: String): BrowserUiState = withContext(main) { existing(sessionId).switchTab(tabId) }
    override suspend fun closeTab(sessionId: String, tabId: String): BrowserUiState = withContext(main) { existing(sessionId).closeTab(tabId) }

    /** 界面入口返回 Job；脚本在 IO 预载，标签与模式结果从 state 观察。 */
    fun setDisplayModeByUser(sessionId: String, mode: BrowserDisplayMode) = userAction(sessionId) { it.setDisplayModeByUser(mode) }
    fun newTabByUser(sessionId: String, url: String = "") = userAction(sessionId) { it.newTabByUser(url) }
    fun switchTabByUser(sessionId: String, tabId: String) = userAction(sessionId) { it.switchTabByUser(tabId) }
    fun closeTabByUser(sessionId: String, tabId: String) = userAction(sessionId) { it.closeTabByUser(tabId) }

    private fun userAction(sessionId: String, action: (AgentBrowserSession) -> Unit): kotlinx.coroutines.Job {
        val epoch = epochs.capture(sessionId)
        return scope.launch {
            try {
                loadScripts()
                if (!epochs.isCurrent(sessionId, epoch)) return@launch
                val session = obtain(sessionId)
                action(session)
                session.control.updatePage { it.copy(lastErrorCode = null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: BrowserActionException) {
                sessions[sessionId]?.control?.updatePage { it.copy(lastErrorCode = failure.code) }
            } catch (_: Exception) {
                sessions[sessionId]?.control?.updatePage { it.copy(lastErrorCode = "BROWSER_FAILED") }
            }
        }
    }

    private suspend fun obtainReady(sessionId: String): AgentBrowserSession {
        val epoch = epochs.capture(sessionId)
        loadScripts()
        if (!epochs.isCurrent(sessionId, epoch)) throw BrowserActionException("NO_PAGE", "浏览器会话已关闭。")
        return obtain(sessionId)
    }

    private suspend fun loadScripts() {
        if (loadedScripts == null) loadedScripts = scripts.await()
    }

    private fun existing(sessionId: String): AgentBrowserSession =
        sessions[sessionId] ?: throw BrowserActionException("NO_PAGE", "No page is open in the Agent browser.")

    private fun obtain(sessionId: String): AgentBrowserSession {
        sessions[sessionId]?.let { return it }
        val source = loadedScripts ?: throw BrowserActionException("BROWSER_INITIALIZING", "浏览器脚本正在加载。")
        val session = AgentBrowserSession(app, source.first, source.second, scope, pool)
        sessions[sessionId] = session
        _sessionIds.update { it + sessionId }
        return session
    }

    companion object {
        const val MAX_WEBVIEWS = 6
        const val PROFILE_NAME = "infinity-agent-browser"
    }
}

/** 浏览器依赖 WebView 多配置文件隔离 Cookie 与存储；不支持时整个功能不可用。 */
fun browserAvailability(context: Context): BrowserAvailability {
    val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        ?: return BrowserAvailability.Unsupported(null)
    val supported = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    return if (supported) BrowserAvailability.Supported else BrowserAvailability.Unsupported(provider.versionName)
}
