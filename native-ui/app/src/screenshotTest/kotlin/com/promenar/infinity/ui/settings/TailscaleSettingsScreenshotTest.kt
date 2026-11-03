package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetPingResult
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.tailnet.TailnetStatusSummary
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "Tailscale 已连接 浅色", widthDp = 412, heightDp = 2000, locale = "en")
@Preview(name = "Tailscale 已连接 浅色 大字号130", widthDp = 412, heightDp = 2600, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleRunningLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.RUNNING)

@PreviewTest
@Preview(name = "Tailscale 已连接 深色", widthDp = 412, heightDp = 2000, locale = "zh-rCN")
@Preview(name = "Tailscale 已连接 深色 大字号130", widthDp = 412, heightDp = 2600, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailscaleRunningDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.RUNNING)

@PreviewTest
@Preview(name = "Tailscale 待登录 浅色", widthDp = 412, heightDp = 1600, locale = "en")
@Preview(name = "Tailscale 待登录 浅色 大字号130", widthDp = 412, heightDp = 2100, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleNeedsLoginLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.NEEDS_LOGIN)

@PreviewTest
@Preview(name = "Tailscale 待登录 深色", widthDp = 412, heightDp = 1600, locale = "zh-rCN")
@Preview(name = "Tailscale 待登录 深色 大字号130", widthDp = 412, heightDp = 2100, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailscaleNeedsLoginDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.NEEDS_LOGIN)

@PreviewTest
@Preview(name = "Tailscale 未启用 浅色", widthDp = 412, heightDp = 1400, locale = "en")
@Composable
fun tailscaleDisabledLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.DISABLED)

@PreviewTest
@Preview(name = "Tailscale 未启用 深色", widthDp = 412, heightDp = 1400, locale = "zh-rCN")
@Composable
fun tailscaleDisabledDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.DISABLED)

@PreviewTest
@Preview(name = "Tailscale 编辑面板 浅色", widthDp = 412, heightDp = 900, locale = "en")
@Preview(name = "Tailscale 编辑面板 浅色 大字号130", widthDp = 412, heightDp = 1150, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleEditorsLightPreview() = TailscaleEditorsPreview(dark = false)

@PreviewTest
@Preview(name = "Tailscale 编辑面板 深色", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun tailscaleEditorsDarkPreview() = TailscaleEditorsPreview(dark = true)

/** 两个编辑面板的内容（不含底部弹层外壳），其中授权密钥展示校验失败态。 */
@Composable
private fun TailscaleEditorsPreview(dark: Boolean) {
    PreviewTheme(dark) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                val state = previewState(TailnetPhase.RUNNING)
                Text(stringResource(R.string.tailscale_auth_key), style = MaterialTheme.typography.titleMedium)
                TailscaleAuthKeyEditor(state.copy(authKeyInvalid = true), TailscaleSettingsActions())
                HorizontalDivider()
                Text(stringResource(R.string.tailscale_control_url), style = MaterialTheme.typography.titleMedium)
                TailscaleControlUrlEditor(state, TailscaleSettingsActions())
            }
        }
    }
}

@Composable
private fun PreviewTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

@Composable
private fun TailscaleVisualPreview(dark: Boolean, phase: TailnetPhase) {
    PreviewTheme(dark) {
        Surface(color = MaterialTheme.colorScheme.background) {
            InfinitySettingsPageLayout(title = stringResource(R.string.tailscale_title), onBack = {}) { padding ->
                TailscaleSettingsContent(state = previewState(phase), contentPadding = padding)
            }
        }
    }
}

private fun previewState(phase: TailnetPhase): TailscaleSettingsUiState {
    val running = phase == TailnetPhase.RUNNING
    val summary = when (phase) {
        TailnetPhase.RUNNING -> TailnetStatusSummary(
            backendState = TailnetStatusSummary.STATE_RUNNING,
            selfName = "infinity-pixel",
            selfDnsName = "infinity-pixel.example-tailnet.ts.net",
            selfIps = listOf("100.101.102.103", "fd7a:115c:a1e0::1"),
            online = true,
            magicDnsSuffix = "example-tailnet.ts.net",
            tailnetName = "example-tailnet.ts.net",
            health = listOf("Tailscale could not reach the coordination server; retrying."),
        )
        TailnetPhase.NEEDS_LOGIN -> TailnetStatusSummary(backendState = TailnetStatusSummary.STATE_NEEDS_LOGIN)
        else -> null
    }
    return TailscaleSettingsUiState(
        available = true,
        settings = TailnetSettings(
            enabled = phase != TailnetPhase.DISABLED,
            ephemeral = !running,
            hasAuthKey = running,
        ),
        tailnet = TailnetState(
            phase = phase,
            summary = summary,
            peers = if (running) {
                listOf(
                    TailnetPeer("nas", "nas.example-tailnet.ts.net", listOf("100.64.0.7"), "linux", online = true, path = TailnetPaths.DIRECT, directLan = true),
                    TailnetPeer(
                        "build-server-with-a-long-name",
                        "build-server-with-a-long-name.example-tailnet.ts.net",
                        listOf("100.64.0.8"),
                        "linux",
                        online = false,
                    ),
                    TailnetPeer(
                        "laptop", "laptop.example-tailnet.ts.net", listOf("100.64.0.9"), "macOS", online = true,
                        path = TailnetPaths.DERP, relayRegion = "hkg", relayRegionName = "Hong Kong",
                    ),
                    TailnetPeer("Desktop", "desktop.example-tailnet.ts.net", listOf("100.64.0.10"), "windows", online = true, path = TailnetPaths.PEER_RELAY),
                    TailnetPeer("pixel", "pixel.example-tailnet.ts.net", listOf("100.64.0.11"), "android", online = false),
                    TailnetPeer("muse", "muse.example-tailnet.ts.net", listOf("100.64.0.12"), null, online = true),
                )
            } else {
                emptyList()
            },
        ),
        // nas 本次 Ping 过，列表行带延迟；其余设备只显示被动路径。
        lastPing = if (running) mapOf("nas" to TailnetPingResult(path = TailnetPaths.DIRECT, directLan = true, latencyMs = 2.4)) else emptyMap(),
    )
}

@PreviewTest
@Preview(name = "Tailscale 路径面板 浅色", widthDp = 412, heightDp = 1100, locale = "en")
@Preview(name = "Tailscale 路径面板 浅色 大字号130", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun tailscalePathCardLightPreview() = TailscalePathCardsPreview(dark = false)

@PreviewTest
@Preview(name = "Tailscale 路径面板 深色", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun tailscalePathCardDarkPreview() = TailscalePathCardsPreview(dark = true)

/** 设备面板的路径卡片（不含底部弹层外壳）：检测中先中转、打通直连后结束、始终无响应三种状态。 */
@Composable
private fun TailscalePathCardsPreview(dark: Boolean) {
    val peer = TailnetPeer("workstation", "workstation.example-tailnet.ts.net", listOf("100.64.0.12"), "linux", online = true)
    val derp = TailnetPingResult(path = TailnetPaths.DERP, relayRegion = "hkg", relayRegionName = "Hong Kong", latencyMs = 92.0)
    val direct = TailnetPingResult(path = TailnetPaths.DIRECT, directLan = false, latencyMs = 38.6)
    val timeout = TailnetPingResult(timedOut = true)
    PreviewTheme(dark) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                BettboxListGroup { TailscalePathCard(peer, TailscalePingRun("workstation", listOf(derp, derp.copy(latencyMs = 88.0))), null, {}, {}) }
                BettboxListGroup {
                    TailscalePathCard(
                        peer,
                        TailscalePingRun("workstation", listOf(derp, derp.copy(latencyMs = 88.0), direct, direct.copy(latencyMs = 37.9)), TailscalePingFinish.DIRECT),
                        null, {}, {},
                    )
                }
                BettboxListGroup { TailscalePathCard(peer, TailscalePingRun("workstation", List(3) { timeout }, TailscalePingFinish.UNREACHABLE), null, {}, {}) }
            }
        }
    }
}
