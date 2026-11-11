package com.promenar.infinity.ui.chat

import androidx.compose.runtime.Immutable
import com.promenar.infinity.data.browser.BrowserMode

/**
 * 会话功能面板统一入口的一个条目。窄窗口的顶栏入口与弹出列表、宽窗口检视器的底部页签条与收起后的边缘条
 * 都只读这一份列表，条目来源、排序与可用性在两种形态下一致。
 *
 * 新增条目：
 * 1. [ChatPage] 增加一项，声明位置即可用条目之间的固定顺序；
 * 2. [panelHubAgentCreated] 决定它是否属于“由 Agent 创建内容”的条目；
 * 3. [ChatPanelHubSignals] 增加该面板需要的状态，[chatPanelHubEntries] 增加对应分支；
 * 4. 界面层在 `ChatPanelHubUi.kt` 的图标、名称与摘要里补上该页，在会话页给出两种形态的打开动作与检视器内容。
 */
@Immutable
internal data class PanelHubEntry(
    val page: ChatPage,
    /** 当前内容的一句话摘要；为 null 时只显示名称。不可用的条目在界面上统一显示“未使用”。 */
    val summary: String?,
    /** 需要用户处理的数量，挂在条目与入口按钮上。 */
    val pending: Int,
    /** 有正在进行的内容：没有待处理时入口按钮显示运行圆点。 */
    val running: Boolean,
    /** 不可用时灰调显示、不可点按，排到队尾。 */
    val available: Boolean,
    /** 打开该面板；由会话页按当前形态给出（窄窗口整页或面板，宽窗口切换检视器页签）。 */
    val onOpen: () -> Unit,
)

/** 入口按钮上的标记：待处理总数优先，其次运行圆点。 */
internal sealed interface PanelHubBadge {
    data object None : PanelHubBadge
    data object Running : PanelHubBadge
    data class Pending(val count: Int) : PanelHubBadge
}

/** 由 Agent 创建内容的条目：本会话中 Agent 从未调用过时不可用。 */
internal fun panelHubAgentCreated(page: ChatPage): Boolean = when (page) {
    ChatPage.Canvas, ChatPage.State, ChatPage.Production -> true
    ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Remote -> false
}

/**
 * 排序：可用条目在前、不可用条目在后，两组内部都按 [ChatPage] 声明顺序。
 * 只看可用性与固定顺序，待处理数、运行状态与摘要的刷新不会让条目换位。
 */
internal fun panelHubOrder(entries: List<PanelHubEntry>): List<PanelHubEntry> =
    entries.sortedWith(compareBy<PanelHubEntry>({ !it.available }, { it.page.ordinal }))

/** 入口按钮的标记只汇总可用条目。 */
internal fun panelHubBadge(entries: List<PanelHubEntry>): PanelHubBadge {
    val available = entries.filter { it.available }
    val pending = available.sumOf { it.pending.coerceAtLeast(0) }
    return when {
        pending > 0 -> PanelHubBadge.Pending(pending)
        available.any { it.running } -> PanelHubBadge.Running
        else -> PanelHubBadge.None
    }
}

/** Agent 调用过的条目一经可用，本会话内保持可用：内容被清空也不再回到“未使用”。 */
internal fun panelHubUsage(used: Set<ChatPage>, active: Set<ChatPage>): Set<ChatPage> =
    if (active.all { it in used }) used else used + active

internal fun savePanelHubUsage(used: Set<ChatPage>): String = saveChatPages(used.sortedBy { it.ordinal })

internal fun restorePanelHubUsage(token: String): Set<ChatPage> = restoreChatPages(token).toSet()

/** 会话页汇总给入口的状态；与界面文字无关，摘要由界面层另行给出。 */
internal data class ChatPanelHubSignals(
    val generating: Boolean = false,
    val pendingApprovals: Int = 0,
    val runningJobs: Int = 0,
    val browser: BrowserMode? = null,
    val remote: RemoteDot? = null,
    val canvases: Int = 0,
    /** Agent 写出、等待用户认领或忽略的产物。 */
    val canvasCandidates: Int = 0,
    val hasStateBoard: Boolean = false,
    /** 制作面板：会话曾绑定项目时可用，待审数挂角标，有镜头在生成时显示运行圆点。 */
    val production: com.promenar.infinity.ui.production.ProductionEntryState = com.promenar.infinity.ui.production.ProductionEntryState.Disabled,
    /** 本会话中 Agent 调用过的条目（见 [panelHubUsage]）。 */
    val used: Set<ChatPage> = emptySet(),
)

/** 当前状态下 Agent 已在使用的条目，并入 [ChatPanelHubSignals.used]。 */
internal fun panelHubActive(canvases: Int, canvasCandidates: Int, hasStateBoard: Boolean, productionBound: Boolean = false): Set<ChatPage> = buildSet {
    if (canvases > 0 || canvasCandidates > 0) add(ChatPage.Canvas)
    if (hasStateBoard) add(ChatPage.State)
    if (productionBound) add(ChatPage.Production)
}

/**
 * 按会话状态生成全部条目并排序。待处理：运行为待批准数，浏览器为需要用户接手，远程为需要登录或连接失败，
 * 产物为待认领数；运行中：生成或后台任务、Agent 正在操作浏览器或使用 SSH。
 */
internal fun chatPanelHubEntries(
    signals: ChatPanelHubSignals,
    summary: (ChatPage) -> String?,
    onOpen: (ChatPage) -> Unit,
): List<PanelHubEntry> {
    val used = panelHubUsage(signals.used, panelHubActive(signals.canvases, signals.canvasCandidates, signals.hasStateBoard, signals.production.enabled))
    return panelHubOrder(
        ChatPage.entries.map { page ->
            val (pending, running) = when (page) {
                ChatPage.Run -> signals.pendingApprovals to (signals.generating || signals.runningJobs > 0)
                ChatPage.Browser -> (if (signals.browser == BrowserMode.HANDOFF) 1 else 0) to (signals.browser == BrowserMode.AGENT)
                ChatPage.Remote -> (if (signals.remote == RemoteDot.ATTENTION) 1 else 0) to (signals.remote == RemoteDot.AGENT)
                ChatPage.Canvas -> signals.canvasCandidates to false
                ChatPage.Production -> signals.production.pending to signals.production.running
                ChatPage.Explorer, ChatPage.Git, ChatPage.State -> 0 to false
            }
            PanelHubEntry(
                page = page,
                summary = summary(page),
                pending = pending.coerceAtLeast(0),
                running = running,
                available = !panelHubAgentCreated(page) || page in used,
                onOpen = { onOpen(page) },
            )
        },
    )
}
