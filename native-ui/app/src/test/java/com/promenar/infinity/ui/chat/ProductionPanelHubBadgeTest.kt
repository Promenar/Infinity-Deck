package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.production.ProductionEntryState
import org.junit.Test

class ProductionPanelHubBadgeTest {
    @Test fun `制作入口角标相加待审镜头与待批准任务`() {
        val entries = chatPanelHubEntries(ChatPanelHubSignals(production = ProductionEntryState(true, true, 3, false, pendingApprovals = 2)), summary = { null }, onOpen = {})
        assertThat(entries.first { it.page == ChatPage.Production }.pending).isEqualTo(5)
        assertThat(panelHubBadge(entries)).isEqualTo(PanelHubBadge.Pending(5))
    }
}
