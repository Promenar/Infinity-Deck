package com.promenar.infinity.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.lab.catalog.LabCatalog
import com.promenar.infinity.ui.lab.catalog.LabVersionKind
import com.promenar.infinity.ui.lab.stage.LabStage
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 实验室版本的整页宿主：已注册场景的版本进入 [LabStage]，用真实组件渲染脚本数据；
 * 尚未接入场景的版本显示版本信息占位。
 */
@Composable
fun LabVersionScreen(
    versionId: String,
    record: Boolean,
    onNavigateBack: () -> Unit,
) {
    val scene = remember(versionId) { LabScenes.scene(versionId) }
    if (scene != null) {
        // 深链以单顶方式复用版本页，舞台状态按版本与录制标记重建，新深链的录制模式才会生效。
        key(versionId, record) {
            LabStage(scene = scene, record = record, onExit = onNavigateBack)
        }
        return
    }
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
