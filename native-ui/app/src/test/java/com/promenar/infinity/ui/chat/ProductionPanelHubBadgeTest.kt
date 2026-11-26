package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.production.ProductionEntryState
import org.junit.Test

class ProductionPanelHubBadgeTest {
    @Test fun `制作入口角标分离待审镜头警示数与未发送待发数`() {
        val both = chatPanelHubEntries(
            ChatPanelHubSignals(production = ProductionEntryState(true, true, 3, false, unsent = 2)),
            summary = { null },
            onOpen = {},
        )
        val entry = both.first { it.page == ChatPage.Production }
        assertThat(entry.pending).isEqualTo(3)
        assertThat(entry.secondaryPending).isEqualTo(2)
        assertThat(panelHubBadge(both)).isEqualTo(PanelHubBadge.Pending(3))

        val sendOnly = chatPanelHubEntries(
            ChatPanelHubSignals(production = ProductionEntryState(true, true, 0, false, unsent = 2)),
            summary = { null },
            onOpen = {},
        )
        val sendEntry = sendOnly.first { it.page == ChatPage.Production }
        assertThat(sendEntry.pending).isEqualTo(0)
        assertThat(sendEntry.secondaryPending).isEqualTo(2)
        assertThat(panelHubBadge(sendOnly)).isEqualTo(PanelHubBadge.SecondaryPending(2))

        val unbound = chatPanelHubEntries(
            ChatPanelHubSignals(production = ProductionEntryState(true, false, 3, true, unsent = 2)),
            summary = { null },
            onOpen = {},
        )
        val unboundEntry = unbound.first { it.page == ChatPage.Production }
        assertThat(unboundEntry.pending).isEqualTo(0)
        assertThat(unboundEntry.secondaryPending).isEqualTo(0)
        assertThat(panelHubBadge(unbound)).isEqualTo(PanelHubBadge.None)
    }
}
