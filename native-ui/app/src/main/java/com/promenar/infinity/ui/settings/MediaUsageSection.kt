package com.promenar.infinity.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.queue.MediaUsageSummary
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection

/** 仅用于用量专区；币种和未知费用按账本原值展示。 */
@Composable
internal fun MediaUsageSection(rows: List<MediaUsageSummary>) {
    InfinitySettingsSection(title = stringResource(R.string.media_usage_title)) {
        rows.forEach { row ->
            InfinitySettingsRowLayout(
                title = row.providerId,
                titleSegments = listOf(row.providerId, row.modelId),
                subtitle = stringResource(R.string.media_usage_tasks, row.tasks),
                trailingContent = {
                    Text(row.knownCost?.let { "${it.amount.stripTrailingZeros().toPlainString()} ${it.currency}" }
                        ?: stringResource(R.string.media_usage_unknown), style = MaterialTheme.typography.bodyMedium)
                },
            )
        }
    }
}
