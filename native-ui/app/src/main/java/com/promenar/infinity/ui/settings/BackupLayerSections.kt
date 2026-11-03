package com.promenar.infinity.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupDegradedReference
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.backup.BackupRestoreResult
import com.promenar.infinity.data.backup.BackupResultSummary
import com.promenar.infinity.data.backup.BackupSkippedItem
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.skills.SkillRestoreNoticeContent

/** 层的显示名：资料、会话与密钥沿用原备份内容文案。 */
@Composable
internal fun backupLayerName(layer: BackupLayer): String = stringResource(
    when (layer) {
        BackupLayer.CORE -> R.string.backup_layer_core
        BackupLayer.KNOWLEDGE -> R.string.backup_content_library
        BackupLayer.SESSIONS -> R.string.backup_content_sessions
        BackupLayer.WORKSPACE_FILES -> R.string.backup_layer_files
        BackupLayer.SECRETS -> R.string.backup_content_keys
    },
)

@Composable
internal fun backupLayerNames(layers: Collection<BackupLayer>): String =
    BackupLayer.entries.filter { it in layers }.map { backupLayerName(it) }
        .joinToString(stringResource(R.string.backup_layer_separator))

@Composable
internal fun formatBackupBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** 已选层的预计总大小；还没有估算结果时返回 null。密钥层体积很小且估算需要读取密钥，不计入。 */
internal fun selectedEstimateBytes(state: BackupUiState): Long? =
    state.layerEstimates.takeIf { it.isNotEmpty() }
        ?.filterKeys { it in state.selectedLayers && it != BackupLayer.SECRETS }
        ?.values?.sumOf { it.sizeBytes }

/** 收起时的副标题：“核心、资料、会话 · 约 47.4 MB”；没有估算时只列层名。 */
@Composable
internal fun backupLayerSummary(state: BackupUiState): String {
    val names = backupLayerNames(state.selectedLayers)
    return selectedEstimateBytes(state)?.let { stringResource(R.string.backup_layer_summary, names, formatBackupBytes(it)) } ?: names
}

/** 层的副标题：内容说明一行，下一行是预计大小（核心层另注“始终包含”）。 */
@Composable
private fun layerDescription(state: BackupUiState, layer: BackupLayer, description: String): String {
    val size = when {
        layer == BackupLayer.SECRETS -> null
        state.estimating -> stringResource(R.string.backup_layer_estimating)
        state.estimateFailed -> stringResource(R.string.backup_layer_estimate_failed)
        else -> state.layerEstimates[layer]?.let { stringResource(R.string.backup_layer_size, formatBackupBytes(it.sizeBytes)) }
    }
    val always = if (layer == BackupLayer.CORE) stringResource(R.string.backup_layer_always) else null
    val meta = listOfNotNull(size, always).joinToString(" · ")
    return if (meta.isEmpty()) description else "$description\n$meta"
}

/** “备份内容”卡片展开后的分层开关。核心层常开不可关；密钥开启时加密锁定为开。 */
@Composable
internal fun ColumnScope.BackupLayerToggles(
    state: BackupUiState,
    onLayerChange: (BackupLayer, Boolean) -> Unit,
    onIncludeKeysChange: (Boolean) -> Unit,
    onEncryptBackupChange: (Boolean) -> Unit,
) {
    val rows = listOf(
        BackupLayer.CORE to R.string.backup_layer_core_desc,
        BackupLayer.KNOWLEDGE to R.string.backup_layer_knowledge_desc,
        BackupLayer.SESSIONS to R.string.backup_layer_sessions_desc,
        BackupLayer.WORKSPACE_FILES to R.string.backup_layer_files_desc,
    )
    rows.forEach { (layer, description) ->
        val tag = Modifier.testTag("backup_layer_${layer.name.lowercase()}")
        if (layer == BackupLayer.CORE) {
            // 核心层不可取消：用锁形图标代替灰显开关，灰显开关在深色下像是关闭状态。
            InfinitySettingsRowLayout(
                title = backupLayerName(layer),
                subtitle = layerDescription(state, layer, stringResource(description)),
                subtitleMaxLines = 6,
                modifier = tag,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.backup_layer_always),
                    tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            SettingsToggle(
                title = backupLayerName(layer),
                description = layerDescription(state, layer, stringResource(description)),
                checked = layer in state.selectedLayers,
                onCheckedChange = { onLayerChange(layer, it) },
                descriptionMaxLines = 6,
                modifier = tag,
            )
        }
    }
    SettingsToggle(
        title = stringResource(R.string.backup_content_keys),
        description = stringResource(R.string.backup_layer_keys_desc),
        checked = state.keysChecked,
        onCheckedChange = onIncludeKeysChange,
        descriptionMaxLines = 4,
    )
    SettingsToggle(
        title = stringResource(R.string.backup_encrypt_package),
        description = stringResource(if (state.keysChecked) R.string.backup_encrypt_required else R.string.settings_batch1_backup_encrypt_summary),
        checked = state.encryptBackup || state.keysChecked,
        onCheckedChange = onEncryptBackupChange,
        enabled = !state.keysChecked,
        descriptionMaxLines = 3,
    )
}

/** 分区页脚：有估算时先写预计大小，再说明恢复只替换包内的层。 */
@Composable
internal fun backupLayersFooter(state: BackupUiState): String =
    selectedEstimateBytes(state)?.let { stringResource(R.string.backup_layers_footer_estimate, formatBackupBytes(it)) }
        ?: stringResource(R.string.backup_layers_footer)

@Composable
private fun skipReasonText(reason: String): String = when (reason) {
    BackupSkippedItem.REASON_FILE_UNAVAILABLE -> stringResource(R.string.backup_skip_reason_file)
    BackupSkippedItem.REASON_AVATAR_DANGLING -> stringResource(R.string.backup_skip_reason_avatar)
    else -> reason
}

@Composable
private fun degradedText(reference: BackupDegradedReference): String {
    val plural = when (reference.category) {
        "session_agent_unavailable" -> R.plurals.backup_degraded_session_agent
        "directory_workspace_unavailable" -> R.plurals.backup_degraded_directory_workspace
        "session_workspace_unavailable" -> R.plurals.backup_degraded_session_workspace
        "artifact_workspace_unavailable" -> R.plurals.backup_degraded_artifact_workspace
        "knowledge_document_unavailable" -> R.plurals.backup_degraded_knowledge_document
        "document_tag_file_unavailable" -> R.plurals.backup_degraded_document_tag
        else -> null
    }
    return plural?.let { pluralStringResource(it, reference.count, reference.count) } ?: "${reference.category}: ${reference.count}"
}

@Composable
private fun SkippedItems(items: List<BackupSkippedItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(backupLayerName(item.layer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(skipReasonText(item.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 导出或上传成功后的结果面板：各层大小，以及没有备份的项目和原因。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupExportResultSheet(summary: BackupResultSummary, onDismiss: () -> Unit) {
    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Content) {
        BackupExportResultContent(summary, onDismiss, Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun BackupExportResultContent(summary: BackupResultSummary, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("backup_export_result"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.backup_export_result_title), style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BackupLayer.entries.filter { it in summary.layers }.forEach { layer ->
                Row {
                    Text(backupLayerName(layer), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatBackupBytes(summary.layers.getValue(layer).sizeBytes), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (summary.skipped.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.backup_export_result_skipped, summary.skipped.size, summary.skipped.size),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            SelectionContainer { SkippedItems(summary.skipped) }
        }
        InfinityPillButton(
            text = stringResource(R.string.backup_result_done),
            onClick = onDismiss,
            tone = InfinityPillTone.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 重启恢复后首次进入应用时弹出：替换了哪些层，以及需要用户处理的降级引用、跳过项与技能仓库。 */
@Composable
fun BackupRestoreResultDialog(result: BackupRestoreResult, onDismiss: () -> Unit, onFillKeys: (() -> Unit)? = null) {
    val summary = result.summary
    val skills = result.skills?.takeIf { it.skippedGitRepositories.isNotEmpty() || it.skippedSymbolicLinks > 0 }
    val keysMissing = restoreResultMissingKeys(summary)
    val fillKeys = onFillKeys?.takeIf { keysMissing }
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_restore_result_title),
        message = "",
        confirmText = stringResource(if (fillKeys != null) R.string.onboarding_restore_result_fill_keys else R.string.backup_result_done),
        cancelText = if (fillKeys != null) stringResource(R.string.onboarding_restore_result_later) else null,
        onConfirm = {
            onDismiss()
            fillKeys?.invoke()
        },
        onCancel = onDismiss,
        content = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("backup_restore_result"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (summary.layers.isNotEmpty()) {
                        Text(stringResource(R.string.backup_restore_result_layers, backupLayerNames(summary.layers.keys)))
                    }
                    if (summary.degraded.isNotEmpty() || summary.skipped.isNotEmpty()) {
                        Text(stringResource(R.string.backup_restore_result_attention), style = MaterialTheme.typography.titleSmall)
                        summary.degraded.filter { it.count > 0 }.forEach { Text(degradedText(it), style = MaterialTheme.typography.bodyMedium) }
                        if (summary.skipped.isNotEmpty()) SkippedItems(summary.skipped)
                    }
                    if (keysMissing) {
                        Text(
                            stringResource(R.string.onboarding_restore_result_keys_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.infinityDomainColors.warning,
                        )
                    }
                    skills?.let { SkillRestoreNoticeContent(it) }
                }
            }
        },
    )
}

/** 分层备份没有密钥层时，恢复只保留本机已有的密钥，结果弹窗需要提示重新填写。旧版不分层的包不判断。 */
internal fun restoreResultMissingKeys(summary: BackupResultSummary): Boolean =
    summary.layers.isNotEmpty() && BackupLayer.SECRETS !in summary.layers
