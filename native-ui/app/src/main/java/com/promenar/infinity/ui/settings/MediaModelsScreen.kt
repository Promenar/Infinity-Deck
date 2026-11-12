package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection

@Composable
internal fun MediaModelsScreenContent(
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onMoveUp: (MediaGroup, String) -> Unit = { _, _ -> },
    onOpen: (MediaChannelRow) -> Unit = {},
    onProviders: () -> Unit = {},
    onRetry: () -> Unit = {},
    onConfigure: (ComfyConfigureRequest) -> Unit = {},
) {
    MediaSettingsPage(stringResource(R.string.media_settings_title), onBack) {
        MediaOperationState(state, onRetry)
        val groups = state.groups
        val templateGroups = state.snapshot.templates.groupBy({ it.template.hostId }, { it.template.group }).mapValues { it.value.toSet() }
        MediaGroup.entries.forEach { group ->
            InfinitySettingsSection(title = mediaGroupLabel(group)) {
                val rows = groups[group].orEmpty()
                if (state.loaded && rows.isEmpty()) MediaNotice(stringResource(R.string.media_settings_empty))
                rows.forEachIndexed { index, row ->
                    InfinitySettingsItem(
                        title = row.name,
                        subtitle = channelDescription(row),
                        subtitleMaxLines = Int.MAX_VALUE,
                        titleMaxLines = 2,
                        onClick = { onOpen(row) },
                        leadingContent = {
                            IconButton(onClick = { onMoveUp(group, row.channel.id) }, enabled = index > 0 && !state.busy,
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.media_settings_move_up, row.name))
                            }
                        },
                        trailingContent = if (row.isDefault) ({
                            com.promenar.infinity.ui.production.ProductionStatusChip(stringResource(R.string.media_settings_default), com.promenar.infinity.ui.production.ProductionTone.INFO)
                        }) else null,
                    )
                }
                // 主机在该分组下还没有模板：每台主机一行，打开“交给智能体配置”并预填该分组。
                val groupLabel = mediaGroupLabel(group)
                comfyHostsMissingGroup(group, state.snapshot.hosts, templateGroups).forEach { host ->
                    InfinitySettingsItem(
                        icon = Icons.Rounded.TravelExplore,
                        title = stringResource(R.string.media_settings_configure_host, host.name),
                        titleMaxLines = 2,
                        onClick = { onConfigure(ComfyConfigureRequest(host.id, groupLabel)) },
                        enabled = !state.busy,
                        showChevron = true,
                    )
                }
            }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_providers)) {
            InfinitySettingsItem(icon = androidx.compose.material.icons.Icons.Rounded.Hub, title = stringResource(R.string.media_settings_providers), onClick = onProviders, showChevron = true)
        }
    }
}
