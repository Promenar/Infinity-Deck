package com.promenar.infinity.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.lab.catalog.LabCatalog
import com.promenar.infinity.ui.lab.catalog.LabVersionKind
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 实验室版本的整页宿主。第一期先给出版本信息占位；第二波起按 [versionId] 接入真实组件与脚本时间轴。
 */
@Composable
fun LabVersionScreen(
    versionId: String,
    onNavigateBack: () -> Unit,
) {
    val version = LabCatalog.version(versionId)
    InfinitySettingsPageLayout(
        title = version?.let { stringResource(it.title) } ?: stringResource(R.string.lab_title),
        onBack = onNavigateBack,
        modifier = Modifier.testTag("lab_version_root"),
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = InfinitySpacing.RowHorizontal),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            if (version == null) {
                Text(stringResource(R.string.lab_version_missing), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val kind = stringResource(if (version.kind == LabVersionKind.WORKFLOW) R.string.lab_kind_workflow else R.string.lab_kind_state)
            Text(
                text = listOfNotNull(kind, fitLabel(version.fit)).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(version.description), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.lab_version_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.lab_version_id, version.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
