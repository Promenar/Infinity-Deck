package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPingResult
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

    @Test
    fun `设备行路径：离线不显示，Ping 结果优先于被动路径，无近期连接显示空闲`() {
        val relayed = TailnetPeer("macbook", online = true, path = TailnetPaths.DERP, relayRegion = "hkg", relayRegionName = "Hong Kong")
        assertThat(relayed.copy(online = false).pathView()).isNull()
        assertThat(relayed.pathView()).isEqualTo(
            TailscalePathView(TailscalePathKind.DERP, region = "hkg", regionName = "Hong Kong"),
        )
        val pinged = TailnetPingResult(path = TailnetPaths.DIRECT, directLan = true, latencyMs = 2.4)
        assertThat(relayed.pathView(pinged)).isEqualTo(TailscalePathView(TailscalePathKind.DIRECT, lan = true, latencyMs = 2.4))
        // 超时的 Ping 不覆盖被动路径。
        assertThat(relayed.pathView(TailnetPingResult(timedOut = true))?.kind).isEqualTo(TailscalePathKind.DERP)
        assertThat(TailnetPeer("pixel", online = true).pathView()?.kind).isEqualTo(TailscalePathKind.IDLE)
        assertThat(TailnetPingResult(timedOut = true).pathView().kind).isEqualTo(TailscalePathKind.NO_REPLY)
        // 公网直连不标局域网；区域字段只属于 DERP。
        assertThat(TailnetPingResult(path = TailnetPaths.DIRECT, relayRegion = "hkg", latencyMs = 40.0).pathView())
            .isEqualTo(TailscalePathView(TailscalePathKind.DIRECT, latencyMs = 40.0))
    }

    @Test
    fun `延迟与中转区域的显示`() {
        assertThat(tailscaleLatencyText(2.36)).isEqualTo("2.4")
        assertThat(tailscaleLatencyText(85.6)).isEqualTo("86")
        assertThat(tailscaleRegionLabel("hkg", "Hong Kong", chinese = true)).isEqualTo("香港")
        assertThat(tailscaleRegionLabel("hkg", "Hong Kong", chinese = false)).isEqualTo("Hong Kong")
        assertThat(tailscaleRegionLabel("xyz", "Home DERP", chinese = true)).isEqualTo("Home DERP")
        assertThat(tailscaleRegionLabel("xyz", null, chinese = false)).isEqualTo("XYZ")
        assertThat(tailscaleRegionLabel(null, null, chinese = true)).isNull()
    }
}
