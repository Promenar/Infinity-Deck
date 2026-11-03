package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetPeer
import org.junit.Test

class TailscalePeerPresentationTest {
    @Test
    fun `在线设备在前，同组按名称排序`() {
        val peers = listOf(
            TailnetPeer("rog-ally", online = false),
            TailnetPeer("laptop", online = true),
            TailnetPeer("Muse", online = true),
            TailnetPeer("desktop", online = true),
            TailnetPeer("archive", online = false),
        )
        assertThat(peers.sortedForDisplay().map { it.name })
            .containsExactly("desktop", "laptop", "Muse", "archive", "rog-ally").inOrder()
    }

    @Test
    fun `系统标识转成统一的显示名`() {
        assertThat(tailscaleOsLabel("windows")).isEqualTo("Windows")
        assertThat(tailscaleOsLabel("linux")).isEqualTo("Linux")
        assertThat(tailscaleOsLabel("macOS")).isEqualTo("macOS")
        assertThat(tailscaleOsLabel("ios")).isEqualTo("iOS")
        assertThat(tailscaleOsLabel("plan9")).isEqualTo("Plan9")
        assertThat(tailscaleOsLabel("  ")).isNull()
        assertThat(tailscaleOsLabel(null)).isNull()
    }

    @Test
    fun `按系统归类设备图标`() {
        assertThat(tailscalePeerKind("windows")).isEqualTo(TailscalePeerKind.DESKTOP)
        assertThat(tailscalePeerKind("macOS")).isEqualTo(TailscalePeerKind.LAPTOP)
        assertThat(tailscalePeerKind("android")).isEqualTo(TailscalePeerKind.PHONE)
        assertThat(tailscalePeerKind("linux")).isEqualTo(TailscalePeerKind.SERVER)
        assertThat(tailscalePeerKind(null)).isEqualTo(TailscalePeerKind.OTHER)
    }

    @Test
    fun `本机地址优先 IPv4`() {
        assertThat(preferredTailnetAddress(listOf("fd7a:115c:a1e0::2", "100.64.0.2"))).isEqualTo("100.64.0.2")
        assertThat(preferredTailnetAddress(listOf("fd7a:115c:a1e0::2"))).isEqualTo("fd7a:115c:a1e0::2")
        assertThat(preferredTailnetAddress(emptyList())).isNull()
    }
}
