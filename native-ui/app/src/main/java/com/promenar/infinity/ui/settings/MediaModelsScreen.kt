package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
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
) {
    MediaSettingsPage(stringResource(R.string.media_settings_title), onBack) {
        MediaOperationState(state, onRetry)
        val groups = state.groups
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
                            Text(stringResource(R.string.media_settings_default), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }) else null,
                    )
                }
            }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_providers)) {
            InfinitySettingsItem(title = stringResource(R.string.media_settings_providers), onClick = onProviders, showChevron = true)
        }
    }
}
