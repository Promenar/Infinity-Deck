package com.promenar.infinity.ui.browser

import android.content.Context
import android.os.Message
import android.webkit.WebView
import com.promenar.infinity.data.browser.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一个聊天的浏览器，操作权和显示模式由全部标签共享。仅在主线程调用。 */
class AgentBrowserSession internal constructor(
    private val app: Context,
    private val script: String,
    private val developerScript: String,
    private val scope: CoroutineScope,
    private val pool: BrowserPagePool,
) {
    val control = BrowserControl()
    private val tabs = BrowserTabs()
    private val pages = mutableMapOf<String, AgentBrowserTab>()
    private val mutex = Mutex()
    private val protectedPages = mutableSetOf<String>()
    private var destroyed = false
    private var idleJob: Job? = null
    private var lastExposedWebView: WebView? = null

    init { tabs.create(); publish() }

    /** 供现有界面挂载；切换标签会触发 control.state，界面可重新取得当前 WebView。 */
    val webView: WebView get() {
        // 兼容界面 onRelease 的 parent 查询；销毁后仅返回已经分离的旧实例，绝不重建。
        if (destroyed) return lastExposedWebView ?: throw BrowserActionException("NO_PAGE", "浏览器会话已关闭。")
        return currentPage().webView.also { lastExposedWebView = it }
    }
    val isAttached: Boolean get() = pages.values.any { it.isAttached }
    val waitingForUser: Boolean get() = control.state.value.mode in setOf(BrowserMode.USER, BrowserMode.HANDOFF)

    /** 配额全被保护时返回 null，界面显示容量提示并允许关闭标签后重试。 */
    fun currentWebViewOrNull(): WebView? {
        if (destroyed) return null
        return try {
            currentPage().webView.also {
                lastExposedWebView = it
                if (control.state.value.lastErrorCode == "BROWSER_CAPACITY") control.updatePage { state -> state.copy(lastErrorCode = null) }
            }
        } catch (failure: BrowserActionException) {
            if (failure.code == "BROWSER_CAPACITY") {
                control.updatePage { it.copy(lastErrorCode = failure.code) }
                null
            } else throw failure
        }
    }
    fun attach(activityContext: Context): WebView = currentPage().attach(activityContext)
    fun detach() { pages.values.filter { it.isAttached }.forEach { it.detach() } }
    fun takeOver() = control.userTakeOver(explicit = true)
    fun handBack() = control.handBack()
    fun goBack() = currentPage().goBack()
    fun goForward() = currentPage().goForward()
    fun reload() = currentPage().reload()
    fun userOpen(url: String) { currentPage().userOpen(url) }

    /** 界面入口会立即接手，挂起后续 Agent 动作；前台正在执行的回调仍可完成。 */
    fun setDisplayModeByUser(mode: BrowserDisplayMode) {
        control.userTakeOver(explicit = true)
        changeDisplayMode(mode)
    }
    fun newTabByUser(url: String = ""): String {
        control.userTakeOver(explicit = true)
        return createTab(url).id
    }
    fun switchTabByUser(tabId: String) {
        control.userTakeOver(explicit = true)
        selectTab(tabId)
    }
    fun closeTabByUser(tabId: String) {
        control.userTakeOver(explicit = true)
        removeTab(tabId)
    }

    suspend fun open(url: String): BrowserSnapshot = agent { currentPage().open(url) }
    suspend fun snapshot(includeText: Boolean, textOffset: Int): BrowserSnapshot = agent { readyPage().snapshot(includeText, textOffset) }
    suspend fun describe(ref: String): BrowserElement? = agent { readyPage().describe(ref)?.copy(browserTabId = tabs.currentId) }
    suspend fun click(element: BrowserElement): BrowserSnapshot = agent { checkedPage(element).click(element) }
    suspend fun type(element: BrowserElement, text: String, submit: Boolean): BrowserSnapshot = agent { checkedPage(element).type(element, text, submit) }
    suspend fun handOff(element: BrowserElement, reason: BrowserHandoffReason): BrowserSnapshot = agent { checkedPage(element).handOff(element, reason) }
    suspend fun evaluate(code: String): String = agent { readyPage().evaluate(code) }
    suspend fun console(levels: Set<String>, sinceLastRead: Boolean): List<BrowserConsoleEntry> = agent { readyPage().readConsole(levels, sinceLastRead) }
    suspend fun dom(selector: String, limit: Int, maxChars: Int, textOnly: Boolean): String = agent { readyPage().dom(selector, limit, maxChars, textOnly) }
    suspend fun network(): List<BrowserNetworkEntry> = agent { readyPage().readNetwork() }
    suspend fun screenshot(): ByteArray = agent { readyPage().screenshot() }
    suspend fun listTabs(): BrowserUiState = agent { control.state.value }
    suspend fun setDisplayMode(mode: BrowserDisplayMode): BrowserUiState = agent {
        changeDisplayMode(mode)
        control.state.value
    }
    suspend fun newTab(url: String): BrowserUiState = agent {
        createTab(url)
        control.state.value
    }
    suspend fun switchTab(tabId: String): BrowserUiState = agent {
        selectTab(tabId)
        control.state.value
    }
    suspend fun closeTab(tabId: String): BrowserUiState = agent {
        removeTab(tabId)
        control.state.value
    }

    private suspend fun <T> agent(block: suspend () -> T): T = mutex.withLock {
        if (destroyed) throw BrowserActionException("NO_PAGE", "浏览器会话已关闭。")
        if (!control.awaitAgentTurn(USER_WAIT_MS)) throw BrowserActionException("USER_TIMEOUT", "用户尚未交回浏览器操作权。")
        if (destroyed) throw BrowserActionException("NO_PAGE", "浏览器会话已关闭。")
        idleJob?.cancel()
        control.updatePage { it.copy(lastErrorCode = null) }
        control.agentStarted(BrowserAgentAction(BrowserActionKind.SNAPSHOT))
        tabs.currentId?.let { protectedPages += it }
        try { block() } finally {
            protectedPages.clear()
            if (!destroyed) idleJob = scope.launch { delay(6_000L); control.agentIdle() }
        }
    }

    private suspend fun checkedPage(element: BrowserElement): AgentBrowserTab {
        if (element.browserTabId != null && element.browserTabId != tabs.currentId) {
            throw BrowserActionException("STALE_REF", "当前标签已变化，请重新读取 browser_snapshot。")
        }
        return readyPage()
    }
    private suspend fun readyPage(): AgentBrowserTab = currentPage().also { it.awaitRestored() }
    private fun currentPage(): AgentBrowserTab = obtainPage(tabs.currentId ?: throw BrowserActionException("NO_PAGE", "未打开标签页。"))

    private fun obtainPage(id: String): AgentBrowserTab {
        if (destroyed) throw BrowserActionException("NO_PAGE", "浏览器会话已关闭。")
        pages[id]?.let { pool.touch(id); return it }
        val saved = tabs.require(id)
        pool.acquire(id, canRelease = {
            val page = pages[id]
            page != null && !page.isAttached && !page.busy && id !in protectedPages && !(waitingForUser && tabs.currentId == id)
        }, release = { releasePage(id) })
        try {
            val page = AgentBrowserTab(app, script, control, developerScript,
                onPageChanged = { updatePage(id, it) },
                assertCurrent = {
                    if (tabs.currentId != id) throw BrowserActionException("STALE_REF", "当前标签已变化，请重新读取 browser_snapshot。")
                },
                onNewWindow = ::createWindow,
                onRenderGone = { releasePage(id) })
            pages[id] = page
            page.applyDisplayMode(tabs.displayMode, reload = false)
            tabs.update(id) { it.copy(released = false) }
            if (saved.url.isNotBlank() && saved.url != "about:blank") page.restore(saved.url)
            publish()
            return page
        } catch (failure: Throwable) {
            pool.remove(id)
            throw failure
        }
    }

    private fun createTab(url: String): BrowserTabState {
        val previous = tabs.currentId
        val tab = tabs.create(url)
        detach()
        try {
            obtainPage(tab.id)
        } catch (failure: Throwable) {
            tabs.close(tab.id)
            previous?.let { tabs.select(it) }
            publish()
            throw failure
        }
        publish()
        return tabs.require(tab.id)
    }

    private fun selectTab(id: String) {
        tabs.require(id)
        val previous = tabs.currentId
        detach()
        tabs.select(id)
        try { obtainPage(id) } catch (failure: Throwable) {
            previous?.let { tabs.select(it) }
            publish()
            throw failure
        }
        publish()
    }

    private fun removeTab(id: String) {
        tabs.require(id)
        releasePage(id)
        tabs.close(id)
        // 保留一个空标签，现有 WebView 容器在最后一个页面关闭后仍有稳定挂载点。
        if (tabs.currentId == null) tabs.create()
        publish()
        currentPage()
    }

    private fun releasePage(id: String) {
        pages.remove(id)?.destroy()
        pool.remove(id)
        tabs.update(id) { it.copy(released = true, loading = false) }
        publish()
    }

    private fun changeDisplayMode(mode: BrowserDisplayMode) {
        if (tabs.displayMode == mode) return
        tabs.setDisplayMode(mode)
        // 非当前标签释放后按新模式恢复，避免同时重载全部页面。
        pages.keys.filter { it != tabs.currentId }.toList().forEach(::releasePage)
        pages[tabs.currentId]?.applyDisplayMode(mode)
        publish()
    }

    private fun createWindow(message: Message): Boolean {
        val transport = message.obj as? WebView.WebViewTransport ?: return false
        return try {
            val tab = createTab("")
            transport.webView = obtainPage(tab.id).webView
            message.sendToTarget()
            true
        } catch (_: BrowserActionException) { false }
    }

    private fun updatePage(id: String, page: BrowserUiState) {
        tabs.update(id) { it.copy(title = page.title, url = page.url, loading = page.loading, released = false) }
        publish()
    }

    private fun publish() {
        val page = pages[tabs.currentId]?.pageState
        val tab = tabs.currentId?.let { tabs.require(it) }
        control.updatePage { it.copy(
            url = tab?.url.orEmpty(), title = tab?.title.orEmpty(), loading = tab?.loading ?: false,
            progress = page?.progress ?: 0, canGoBack = page?.canGoBack ?: false, canGoForward = page?.canGoForward ?: false,
            tabs = tabs.list(), currentTabId = tabs.currentId, displayMode = tabs.displayMode,
        ) }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        idleJob?.cancel()
        pages.keys.toList().forEach(::releasePage)
        control.endHandoff()
    }

    private companion object { const val USER_WAIT_MS = 10 * 60_000L }
}
