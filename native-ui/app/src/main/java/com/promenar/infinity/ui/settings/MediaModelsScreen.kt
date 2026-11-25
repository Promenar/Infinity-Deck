package com.promenar.infinity.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection

@Composable
internal fun MediaModelsScreenContent(
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onMoveUp: (MediaGroup, String) -> Unit = { _, _ -> },
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
                    if (index > 0) InfinityGroupDivider()
                    val canOpen = row.modelId != null ||
                        row.templateId != null ||
                        (row.workflowHostId != null && row.workflowId != null)
                    InfinitySettingsItem(
                        title = row.name,
                        subtitleSegments = channelDescriptionSegments(row),
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        showChevron = canOpen,
                        enabled = canOpen,
                        onClick = { onOpen(row) },
                        trailingContent = if (row.isDefault) ({
                            com.promenar.infinity.ui.production.ProductionStatusChip(
                                stringResource(R.string.media_settings_default),
                                com.promenar.infinity.ui.production.ProductionTone.INFO,
                            )
                        }) else null,
                    )
                }
            }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_providers_and_hosts)) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.media_settings_providers_and_hosts),
                onClick = onProviders,
                showChevron = true,
            )
        }
    }
}
