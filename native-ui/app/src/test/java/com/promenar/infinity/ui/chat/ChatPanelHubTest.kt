package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.ui.production.ProductionEntryState
import org.junit.Test

class ChatPanelHubTest {
    private fun entries(signals: ChatPanelHubSignals, opened: MutableList<ChatPage> = mutableListOf()) =
        chatPanelHubEntries(signals, summary = { null }, onOpen = { opened += it })

    private fun List<PanelHubEntry>.pages() = map { it.page }

    @Test fun `Agent 未调用过时画布与状态面板不可用并排到队尾，制作面板与常驻条目始终可进入`() {
        val list = entries(ChatPanelHubSignals())
        assertThat(list.pages()).containsExactly(
            ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Production, ChatPage.Canvas, ChatPage.State,
        ).inOrder()
        assertThat(list.filter { it.available }.pages())
            .containsExactly(ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Production).inOrder()
        assertThat(list.filterNot { it.available }.pages()).containsExactly(ChatPage.Canvas, ChatPage.State).inOrder()
    }

    @Test fun `只有状态面板被调用过时它排在画布之前，画布仍在队尾`() {
        val list = entries(ChatPanelHubSignals(hasStateBoard = true))
        assertThat(list.pages().takeLast(2)).containsExactly(ChatPage.Production, ChatPage.Canvas).inOrder()
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
            ),
        ).pages()
        assertThat(busy).isEqualTo(quiet)
    }

    @Test fun `条目只在可用性变化时换位`() {
        val before = entries(ChatPanelHubSignals()).pages()
        val after = entries(ChatPanelHubSignals(canvases = 1)).pages()
        assertThat(after).containsExactly(
            ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Browser, ChatPage.Canvas, ChatPage.Production, ChatPage.State,
        ).inOrder()
        assertThat(before.last()).isEqualTo(ChatPage.State)
        assertThat(entries(ChatPanelHubSignals(hasStateBoard = true)).pages().indexOf(ChatPage.State))
            .isLessThan(entries(ChatPanelHubSignals(hasStateBoard = true)).pages().indexOf(ChatPage.Canvas))
    }

    @Test fun `待处理：待批准、浏览器需要接手、待认领产物`() {
        val list = entries(
            ChatPanelHubSignals(
                pendingApprovals = 2,
                browser = BrowserMode.HANDOFF,
                canvases = 1,
                canvasCandidates = 3,
            ),
        ).associateBy { it.page }
        assertThat(list.getValue(ChatPage.Run).pending).isEqualTo(2)
        assertThat(list.getValue(ChatPage.Browser).pending).isEqualTo(1)
        assertThat(list.getValue(ChatPage.Canvas).pending).isEqualTo(3)
        assertThat(panelHubBadge(list.values.toList())).isEqualTo(PanelHubBadge.Pending(6))
    }

    @Test fun `运行中：生成或后台任务、Agent 操作浏览器`() {
        assertThat(entries(ChatPanelHubSignals(generating = true)).first { it.page == ChatPage.Run }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(runningJobs = 1)).first { it.page == ChatPage.Run }.running).isTrue()
        assertThat(entries(ChatPanelHubSignals(browser = BrowserMode.AGENT)).first { it.page == ChatPage.Browser }.running).isTrue()
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

    @Test fun `只有画布与状态面板属于 Agent 创建后才亮起的条目`() {
        assertThat(ChatPage.entries.filter(::panelHubAgentCreated)).containsExactly(ChatPage.Canvas, ChatPage.State)
    }

    @Test fun `制作面板：绑定后显示角标与运行点，解绑后仍可进入空面板且清零角标`() {
        val bound = ProductionEntryState(enabled = true, hasProduction = true, pending = 2, running = true, title = "雾港")
        val list = entries(ChatPanelHubSignals(production = bound))
        val production = list.first { it.page == ChatPage.Production }
        assertThat(production.available).isTrue()
        assertThat(production.pending).isEqualTo(2)
        assertThat(production.running).isTrue()
        assertThat(list.pages().takeLast(2)).containsExactly(ChatPage.Canvas, ChatPage.State).inOrder()
        assertThat(panelHubBadge(list)).isEqualTo(PanelHubBadge.Pending(2))
        // 解绑后项目已不在，仍可进入空面板挂载，但角标与运行点清零。
        val unbound = entries(ChatPanelHubSignals(production = bound.copy(hasProduction = false, pending = 2, running = true, title = null)))
        assertThat(unbound.first { it.page == ChatPage.Production }.available).isTrue()
        assertThat(unbound.first { it.page == ChatPage.Production }.pending).isEqualTo(0)
        assertThat(unbound.first { it.page == ChatPage.Production }.running).isFalse()
    }

    @Test fun `制作面板从未绑定时可进入空面板，但角标与运行点不计入入口标记`() {
        val list = entries(ChatPanelHubSignals(production = ProductionEntryState.Disabled.copy(pending = 3, running = true)))
        assertThat(list.first { it.page == ChatPage.Production }.available).isTrue()
        assertThat(list.first { it.page == ChatPage.Production }.pending).isEqualTo(0)
        assertThat(list.first { it.page == ChatPage.Production }.running).isFalse()
        assertThat(panelHubBadge(list)).isEqualTo(PanelHubBadge.None)
        assertThat(restorePanelHubUsage(savePanelHubUsage(setOf(ChatPage.Production)))).containsExactly(ChatPage.Production)
    }

    @Test fun `待发送次级角标仅统计可用条目且优先级低于待审批高于运行圆点`() {
        val disabled = PanelHubEntry(ChatPage.State, summary = null, pending = 0, running = true, available = false, onOpen = {}, secondaryPending = 3)
        assertThat(panelHubBadge(listOf(disabled))).isEqualTo(PanelHubBadge.None)

        val readyToSend = PanelHubEntry(ChatPage.Production, summary = "雾港", pending = 0, running = true, available = true, onOpen = {}, secondaryPending = 2)
        assertThat(panelHubBadge(listOf(readyToSend))).isEqualTo(PanelHubBadge.SecondaryPending(2))

        val reviewAndSend = readyToSend.copy(pending = 1)
        assertThat(panelHubBadge(listOf(reviewAndSend))).isEqualTo(PanelHubBadge.Pending(1))
    }
}
