package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.network.AgentNetworkSnapshot
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
fun NetworkAccessSettingsScreen(onBack: () -> Unit, onOpenTailscale: () -> Unit) {
    val access = (LocalContext.current.applicationContext as InfinityApplication).agentNetworkAccess
    val snapshot by access.snapshot.collectAsStateWithLifecycle()
    InfinitySettingsPageLayout(title = stringResource(R.string.network_access_title), onBack = onBack) { padding ->
        NetworkAccessSettingsContent(
            snapshot = snapshot,
            onOpenTailscale = onOpenTailscale,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun tailnetSummarySegments(snapshot: AgentNetworkSnapshot): List<String> {
    val phase = stringResource(
        when {
            !snapshot.tailnetAvailable -> R.string.network_tailnet_missing
            !snapshot.tailnetEnabled -> R.string.network_tailnet_disabled
            !snapshot.tailnetRunning -> R.string.network_tailnet_offline
            else -> R.string.network_tailnet_running
        },
    )
    return listOf(phase)
}

/** 设置页正文：显示 Tailscale 状态与按工具展开的网络路径总表。 */
@Composable
internal fun NetworkAccessSettingsContent(
    snapshot: AgentNetworkSnapshot,
    onOpenTailscale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
    ) {
        InfinitySettingsSection(
            title = stringResource(R.string.network_section_controls),
            footer = stringResource(R.string.network_section_controls_footer),
        ) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.network_tailnet_title),
                subtitleSegments = tailnetSummarySegments(snapshot),
                onClick = onOpenTailscale,
                showChevron = true,
                subtitleMaxLines = 2,
                modifier = Modifier.testTag("network_tailnet_entry"),
            )
        }
        InfinitySettingsSection(
            title = stringResource(R.string.network_section_matrix),
            footer = networkMatrixFooter(),
        ) {
            NetworkAccessRows(snapshot)
        }
    }
}
