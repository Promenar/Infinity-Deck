package com.promenar.infinity.ui.chat

/**
 * 会话的二级内容层级，与单一模态层队列相互独立：宽窗口下检视器展开时仍可继续对话，审批到来也不会把它收起。
 * - 资源管理器、Git 变更、Agent 浏览器、远程连接（[isPage]）：窄窗口由 [com.promenar.infinity.ui.common.InfinityAdaptivePaneHost] 整页承载，宽窗口在检视器中；
 * - 运行、画布、状态面板：只作为宽窗口检视器的页签；窄窗口仍用运行详情面板与画布列表，不进入层级。
 * - 制作面板：宽窗口为检视器页签；窄窗口以整屏上滑面板打开，不进入层级。
 * 宽窗口下层级的根即检视器当前页签，层级为空表示检视器收起。
 * 声明顺序即功能面板入口（[chatPanelHubEntries]）中可用条目的固定顺序，也是检视器换页签的过渡方向。
 */
enum class ChatPage(val token: String, val isPage: Boolean) {
    Run("run", isPage = false),
    Explorer("explorer", isPage = true),
    Git("git", isPage = true),
    Browser("browser", isPage = true),
    Remote("remote", isPage = true),
    Canvas("canvas", isPage = false),
    State("state", isPage = false),
    Production("production", isPage = false),
}

/** 从顶栏、检视器页签等会话入口打开：替换整个层级，以该页为根。 */
internal fun openChatPageRoot(page: ChatPage): List<ChatPage> = listOf(page)

/** 在当前最上层之上打开下一层；已在层级中的页移到最上层，不重复叠放。 */
internal fun pushChatPage(pages: List<ChatPage>, page: ChatPage): List<ChatPage> =
    if (pages.lastOrNull() == page) pages else pages.filterNot { it == page } + page

/** 弹出最上层；只剩根层时整体关闭。 */
internal fun popChatPage(pages: List<ChatPage>): List<ChatPage> = pages.dropLast(1)

internal fun saveChatPages(pages: List<ChatPage>): String = pages.joinToString(",") { it.token }

internal fun restoreChatPages(token: String): List<ChatPage> =
    token.split(',').mapNotNull { part -> ChatPage.entries.firstOrNull { it.token == part } }.distinct()

/**
 * 整页承载的层级：资源管理器、Git、浏览器、远程，窄窗口另加产物；任务只是检视器的页签。
 * 宽窗口的产物由检视器自己的页签承载，不进整页层级。
 */
internal fun chatPageStack(pages: List<ChatPage>, wideWindow: Boolean): List<ChatPage> =
    pages.filter { it.isPage || (it == ChatPage.Canvas && !wideWindow) }

/**
 * 宽窗口检视器的开合：已在该页签的根层时收起，否则以该页签为根打开。
 * 顶栏图标与边缘条共用，与桌面端侧栏按钮的开合习惯一致。
 */
internal fun toggleChatInspector(pages: List<ChatPage>, page: ChatPage): List<ChatPage> =
    if (pages == listOf(page)) emptyList() else openChatPageRoot(page)

/**
 * 展开检视器时的页签：沿用上次的页签；没有时有运行内容先看运行，否则看文件。
 * 上次的页签已不可用（[available] 不含它）时按没有上次处理。
 */
internal fun defaultChatInspectorRoot(last: ChatPage?, hasRunContent: Boolean, available: Set<ChatPage>? = null): ChatPage =
    last?.takeIf { available == null || it in available } ?: if (hasRunContent) ChatPage.Run else ChatPage.Explorer

/** 检视器根层内容的分组：组内换页签由页面层自己过渡，跨组由外层整体过渡，页面层始终只组合一份。 */
internal enum class InspectorContentGroup { Run, Canvas, State, Production, Pages }

internal val ChatPage.inspectorGroup: InspectorContentGroup
    get() = when (this) {
        ChatPage.Run -> InspectorContentGroup.Run
        ChatPage.Canvas -> InspectorContentGroup.Canvas
        ChatPage.State -> InspectorContentGroup.State
        ChatPage.Production -> InspectorContentGroup.Production
        ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Remote -> InspectorContentGroup.Pages
    }

/** 页签切换方向：目标在底栏中更靠后时向前，新内容自右侧进入。 */
internal fun inspectorTabForward(from: ChatPage, to: ChatPage): Boolean = to.ordinal > from.ordinal

/** 检视器根层换了页签时离开的页面与切换方向。 */
internal data class InspectorRootSwitch(val leaving: Set<ChatPage>, val forward: Boolean)

/** 根页没变（推入、弹出）或检视器整体开合时返回 null，各层沿用层级过渡。 */
internal fun inspectorRootSwitch(before: List<ChatPage>, after: List<ChatPage>): InspectorRootSwitch? {
    val from = before.firstOrNull() ?: return null
    val to = after.firstOrNull() ?: return null
    if (from == to) return null
    return InspectorRootSwitch(before.toSet() - after.toSet(), inspectorTabForward(from, to))
}
