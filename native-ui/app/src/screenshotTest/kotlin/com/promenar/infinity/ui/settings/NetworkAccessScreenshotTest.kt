package com.promenar.infinity.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.domain.network.AgentNetworkSnapshot
import com.promenar.infinity.domain.network.AgentNetworkTool
import com.promenar.infinity.domain.network.NetworkAccessCell
import com.promenar.infinity.domain.network.NetworkAccessRow
import com.promenar.infinity.domain.network.NetworkAccessState
import com.promenar.infinity.domain.network.NetworkTargetClass
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "网络访问 Tailscale 已连接 浅色", widthDp = 412, heightDp = 2200, locale = "en")
@Preview(name = "网络访问 Tailscale 已连接 浅色 大字号130", widthDp = 360, heightDp = 3000, locale = "en", fontScale = 1.3f)
@Composable
fun networkAccessConfiguredLightPreview() = NetworkAccessPreview(dark = false, configured = true)

@PreviewTest
@Preview(name = "网络访问 Tailscale 未启用 深色", widthDp = 412, heightDp = 2200, locale = "zh-rCN")
@Preview(name = "网络访问 Tailscale 未启用 深色 大字号130", widthDp = 360, heightDp = 3000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun networkAccessEmptyDarkPreview() = NetworkAccessPreview(dark = true, configured = false)

@Composable
private fun NetworkAccessPreview(dark: Boolean, configured: Boolean) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            InfinitySettingsPageLayout(title = stringResource(R.string.network_access_title), onBack = {}) { padding ->
                NetworkAccessSettingsContent(
                    snapshot = previewNetworkSnapshot(configured),
                    onOpenTailscale = {},
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** 与总表规则一致的预览数据；[configured] 为 true 时 tailnet 已连接。 */
internal fun previewNetworkSnapshot(configured: Boolean): AgentNetworkSnapshot {
    fun row(tool: AgentNetworkTool, vararg states: NetworkAccessState) =
        NetworkAccessRow(tool, NetworkTargetClass.entries.zip(states.toList()) { target, state -> NetworkAccessCell(target, state) })
    val guarded = arrayOf(NetworkAccessState.ALLOWED, NetworkAccessState.ALLOWED, NetworkAccessState.ALLOWED, NetworkAccessState.TAILNET_REQUIRED)
    val configuredOnly = Array(4) { NetworkAccessState.CONFIGURED_ONLY }
    return AgentNetworkSnapshot(
        tailnetAvailable = true,
        tailnetEnabled = configured,
        tailnetRunning = configured,
        rows = listOf(
            row(AgentNetworkTool.WEB_FETCH, *guarded),
            row(AgentNetworkTool.MEDIA_FETCH, *guarded),
            row(AgentNetworkTool.SCRIPT, *guarded),
            row(AgentNetworkTool.MCP, *configuredOnly),
            row(
                AgentNetworkTool.BUILTIN_PROCESS,
                NetworkAccessState.UNRESTRICTED, NetworkAccessState.UNRESTRICTED, NetworkAccessState.UNRESTRICTED, NetworkAccessState.TAILNET_REQUIRED,
            ),
            row(AgentNetworkTool.TERMUX, *Array(4) { NetworkAccessState.UNRESTRICTED }),
            row(AgentNetworkTool.SSH, *configuredOnly),
        ),
    )
}
