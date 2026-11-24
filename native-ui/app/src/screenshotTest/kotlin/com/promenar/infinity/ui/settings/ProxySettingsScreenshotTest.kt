package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.proxy.BuiltInProxyPhase
import com.promenar.infinity.data.proxy.BuiltInProxySettings
import com.promenar.infinity.data.proxy.BuiltInProxyState
import com.promenar.infinity.data.proxy.ProxyLatencyResult
import com.promenar.infinity.data.proxy.ProxyMode
import com.promenar.infinity.data.proxy.ProxyNodeItem
import com.promenar.infinity.data.proxy.ProxySubscription
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityIceBlueSeed
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "内置代理 顺序降级 深色", widthDp = 412, heightDp = 1600, locale = "zh-rCN")
@Preview(name = "内置代理 顺序降级 深色 大字号130", widthDp = 412, heightDp = 2100, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun proxyFallbackDarkPreview() = ProxyPreviewShell(dark = true, state = sampleProxyUiState(ProxyMode.FALLBACK))

@PreviewTest
@Preview(name = "内置代理 单节点 浅色", widthDp = 412, heightDp = 1600, locale = "en")
@Preview(name = "内置代理 单节点 浅色 大字号130", widthDp = 412, heightDp = 2100, locale = "en", fontScale = 1.3f)
@Composable
fun proxySingleLightPreview() = ProxyPreviewShell(dark = false, state = sampleProxyUiState(ProxyMode.SINGLE))

@PreviewTest
@Preview(name = "内置代理 空状态 深色", widthDp = 412, heightDp = 1280, locale = "zh-rCN")
@Composable
fun proxyEmptyDarkPreview() = ProxyPreviewShell(
    dark = true,
    state = ProxySettingsUiState(
        available = true,
        settings = BuiltInProxySettings(enabled = false, mode = ProxyMode.SINGLE),
        proxyState = BuiltInProxyState(phase = BuiltInProxyPhase.STOPPED, enabled = false),
    ),
)

@Composable
private fun ProxyPreviewShell(dark: Boolean, state: ProxySettingsUiState) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = InfinityIceBlueSeed.value.toLong(),
            schemeStyle = InfinitySchemeStyle.BRIGHT,
            pureBlack = dark,
        ),
    ) {
        InfinitySettingsPageLayout(title = stringResource(R.string.proxy_title), onBack = {}) { padding ->
            ProxySettingsContent(state = state, contentPadding = padding)
        }
    }
}

private fun sampleProxyUiState(mode: ProxyMode): ProxySettingsUiState {
    val sub = ProxySubscription(id = "sub-main", name = "Main Feed", nodeCount = 3, lastRefreshedAtMillis = null)
    val nodes = listOf(
        ProxyNodeItem(id = "n1", name = "HK-IPLC-01", type = "vless", server = "hk01.example.net", port = 443, enabled = true, subscriptionIds = setOf("sub-main")),
        ProxyNodeItem(id = "n2", name = "JP-Tokyo-02", type = "hysteria2", server = "jp02.example.net", port = 443, enabled = true, subscriptionIds = setOf("sub-main")),
        ProxyNodeItem(id = "n3", name = "SG-Direct-03", type = "trojan", server = "sg03.example.net", port = 443, enabled = true, manuallyImported = true),
        ProxyNodeItem(id = "n4", name = "US-West-Backup", type = "ss", server = "us04.example.net", port = 8388, enabled = false, subscriptionIds = setOf("sub-main")),
    )
    return ProxySettingsUiState(
        available = true,
        settings = BuiltInProxySettings(
            enabled = true,
            mode = mode,
            activeNode = "n1",
            nodes = nodes,
            subscriptions = listOf(sub),
        ),
        proxyState = BuiltInProxyState(
            phase = BuiltInProxyPhase.RUNNING,
            enabled = true,
            mode = mode,
            activeNode = "n1",
            healthyNodeCount = 3,
            nodes = nodes,
            listenPort = 41823,
        ),
        latencies = mapOf(
            "n1" to ProxyLatencyResult(name = "n1", ok = true, delayMs = 42),
            "n2" to ProxyLatencyResult(name = "n2", ok = true, delayMs = 68),
            "n3" to ProxyLatencyResult(name = "n3", ok = true, delayMs = 135),
        ),
    )
}
