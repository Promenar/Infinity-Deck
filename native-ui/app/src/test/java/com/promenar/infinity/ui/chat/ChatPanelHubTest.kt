package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.ui.production.ProductionEntryState
import org.junit.Test

class ChatPanelHubTest {
    private fun entries(signals: ChatPanelHubSignals, opened: MutableList<ChatPage> = mutableListOf()) =
        chatPanelHubEntries(signals, summary = { null }, onOpen = { opened += it })

    private fun List<PanelHubEntry>.pages() = map { it.page }

    @Test fun `Agent 未调用过时画布、状态面板与制作面板不可用并排到队尾，常驻条目保持固定顺序`() {
        val list = entries(ChatPanelHubSignals())
        assertThat(list.pages()).containsExactly(
            ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Remote, ChatPage.Canvas, ChatPage.State, ChatPage.Production,
        ).inOrder()
        assertThat(list.filter { it.available }.pages())
            .containsExactly(ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Remote).inOrder()
        assertThat(list.filterNot { it.available }.pages()).containsExactly(ChatPage.Canvas, ChatPage.State, ChatPage.Production).inOrder()
    }

    @Test fun `只有状态面板被调用过时它排在画布之前，画布仍在队尾`() {
        val list = entries(ChatPanelHubSignals(hasStateBoard = true))
        assertThat(list.pages().takeLast(3)).containsExactly(ChatPage.State, ChatPage.Canvas, ChatPage.Production).inOrder()
        assertThat(list.first { it.page == ChatPage.State }.available).isTrue()
        assertThat(list.first { it.page == ChatPage.Canvas }.available).isFalse()
    }

    @Test fun `有产物或待认领产物即视为画布已被调用`() {
        assertThat(entries(ChatPanelHubSignals(canvases = 1)).first { it.page == ChatPage.Canvas }.available).isTrue()
        assertThat(entries(ChatPanelHubSignals(canvasCandidates = 2)).first { it.page == ChatPage.Canvas }.available).isTrue()
    }

    @Test fun `待处理数与运行状态变化不会让条目换位`() {
        val quiet = entries(ChatPanelHubSignals(hasStateBoard = true)).pages()
        val busy = entries(
            ChatPanelHubSignals(
                hasStateBoard = true,
                generating = true,
                pendingApprovals = 3,
                browser = BrowserMode.HANDOFF,
                remote = RemoteDot.ATTENTION,
            ),
        ).pages()
        assertThat(busy).isEqualTo(quiet)
    }

    @Test fun `条目只在可用性变化时换位`() {
        val before = entries(ChatPanelHubSignals()).pages()
        val after = entries(ChatPanelHubSignals(canvases = 1)).pages()
        assertThat(after).containsExactly(
            ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Remote, ChatPage.Canvas, ChatPage.State, ChatPage.Production,
        ).inOrder()
        // 画布本就在状态面板之前，变为可用后相对位置不变；状态面板与制作面板仍在队尾。
        assertThat(after).isEqualTo(before)
        assertThat(entries(ChatPanelHubSignals(hasStateBoard = true)).pages().indexOf(ChatPage.State))
            .isLessThan(entries(ChatPanelHubSignals(hasStateBoard = true)).pages().indexOf(ChatPage.Canvas))
    }

    @Test fun `待处理：待批准、浏览器需要接手、远程需要处理、待认领产物`() {
        val list = entries(
            ChatPanelHubSignals(
                pendingApprovals = 2,
                browser = BrowserMode.HANDOFF,
                remote = RemoteDot.ATTENTION,
                canvases = 1,
                canvasCandidates = 3,
            ),
        ).associateBy { it.page }
        assertThat(list.getValue(ChatPage.Run).pending).isEqualTo(2)
        assertThat(list.getValue(ChatPage.Browser).pending).isEqualTo(1)
        assertThat(list.getValue(ChatPage.Remote).pending).isEqualTo(1)
        assertThat(list.getValue(ChatPage.Canvas).pending).isEqualTo(3)
        assertThat(panelHubBadge(list.values.toList())).isEqualTo(PanelHubBadge.Pending(7))
    }

    @Test fun `运行中：生成或后台任务、Agent 操作浏览器、Agent 使用 SSH`() {
        assertThat(entries(ChatPanelHubSignals(generating = true)).first { it.page == ChatPage.Run }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(runningJobs = 1)).first { it.page == ChatPage.Run }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(browser = BrowserMode.AGENT)).first { it.page == ChatPage.Browser }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(remote = RemoteDot.AGENT)).first { it.page == ChatPage.Remote }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(browser = BrowserMode.IDLE)).none { it.running }).isTrue()
    }

    @Test fun `入口标记：有待处理显示总数，否则运行中显示圆点，都没有时不显示`() {
        assertThat(panelHubBadge(entries(ChatPanelHubSignals()))).isEqualTo(PanelHubBadge.None)
        assertThat(panelHubBadge(entries(ChatPanelHubSignals(generating = true)))).isEqualTo(PanelHubBadge.Running)
        assertThat(panelHubBadge(entries(ChatPanelHubSignals(generating = true, pendingApprovals = 1))))
            .isEqualTo(PanelHubBadge.Pending(1))
    }

    @Test fun `入口标记只汇总可用条目`() {
        val disabled = PanelHubEntry(ChatPage.State, summary = null, pending = 4, running = true, available = false, onOpen = {})
        assertThat(panelHubBadge(listOf(disabled))).isEqualTo(PanelHubBadge.None)
    }

    @Test fun `Agent 调用过的条目内容清空后仍可用`() {
        val used = panelHubUsage(emptySet(), panelHubActive(canvases = 1, canvasCandidates = 0, hasStateBoard = false))
        assertThat(used).containsExactly(ChatPage.Canvas)
        val later = entries(ChatPanelHubSignals(used = used))
        assertThat(later.first { it.page == ChatPage.Canvas }.available).isTrue()
        assertThat(restorePanelHubUsage(savePanelHubUsage(setOf(ChatPage.State, ChatPage.Canvas))))
            .containsExactly(ChatPage.Canvas, ChatPage.State)
        assertThat(restorePanelHubUsage("")).isEmpty()
    }

    @Test fun `打开动作带上条目对应的页`() {
        val opened = mutableListOf<ChatPage>()
        entries(ChatPanelHubSignals(), opened).first { it.page == ChatPage.Git }.onOpen()
        assertThat(opened).containsExactly(ChatPage.Git)
    }

    @Test fun `只有画布、状态面板与制作面板属于 Agent 创建内容的条目`() {
        assertThat(ChatPage.entries.filter(::panelHubAgentCreated)).containsExactly(ChatPage.Canvas, ChatPage.State, ChatPage.Production)
    }

    @Test fun `制作面板：曾绑定后可用并排进可用条目，待审数为角标，生成中为运行点`() {
        val bound = ProductionEntryState(enabled = true, hasProduction = true, pending = 2, running = true, title = "雾港")
        val list = entries(ChatPanelHubSignals(production = bound))
        val production = list.first { it.page == ChatPage.Production }
        assertThat(production.available).isTrue()
        assertThat(production.pending).isEqualTo(2)
        assertThat(production.running).isTrue()
        assertThat(list.pages().takeLast(2)).containsExactly(ChatPage.Canvas, ChatPage.State).inOrder()
        assertThat(panelHubBadge(list)).isEqualTo(PanelHubBadge.Pending(2))
        // 解绑后项目已不在，曾绑定仍保持可用。
        val unbound = entries(ChatPanelHubSignals(production = bound.copy(hasProduction = false, pending = 0, running = false, title = null)))
        assertThat(unbound.first { it.page == ChatPage.Production }.available).isTrue()
    }

    @Test fun `制作面板从未绑定时不可用，角标与运行点不计入入口标记`() {
        val list = entries(ChatPanelHubSignals(production = ProductionEntryState.Disabled.copy(pending = 3, running = true)))
        assertThat(list.first { it.page == ChatPage.Production }.available).isFalse()
        assertThat(panelHubBadge(list)).isEqualTo(PanelHubBadge.None)
        assertThat(restorePanelHubUsage(savePanelHubUsage(setOf(ChatPage.Production)))).containsExactly(ChatPage.Production)
    }
}
