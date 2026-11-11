package com.promenar.infinity.ui.skills.store

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.skills.store.StoreInstallState
import com.promenar.infinity.data.skills.store.StoreOperation
import com.promenar.infinity.data.skills.store.StoreSkillItem
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityButtonGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.workspace.WorkspaceTagChip

/** 技能详情的操作回调。 */
class SkillStoreDetailActions(
    val onBack: () -> Unit,
    val onInstall: () -> Unit,
    val onCancel: () -> Unit,
    val onUninstall: () -> Unit,
    val onViewSkillMd: () -> Unit,
)

/** 带 SKILL.md 查看面板与卸载确认的详情页。 */
@Composable
fun SkillStoreDetailScreen(
    item: StoreSkillItem,
    operation: StoreOperation?,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    loadSkillMarkdown: suspend () -> Result<String>,
) {
    var showDocument by remember(item.key) { mutableStateOf(false) }
    var confirmUninstall by remember(item.key) { mutableStateOf(false) }
    SkillStoreDetailContent(
        item = item,
        operation = operation,
        actions = SkillStoreDetailActions(
            onBack = onBack,
            onInstall = onInstall,
            onCancel = onCancel,
            onUninstall = { confirmUninstall = true },
            onViewSkillMd = { showDocument = true },
        ),
    )
    if (showDocument) {
        SkillMarkdownSheet(key = item.key, load = loadSkillMarkdown, onDismiss = { showDocument = false })
    }
    if (confirmUninstall) {
        InfinityConfirmDialog(
            title = stringResource(R.string.skill_store_confirm_uninstall_title, item.entry.title),
            message = stringResource(R.string.skill_store_confirm_uninstall_message, item.entry.name),
            confirmText = stringResource(R.string.skill_store_uninstall),
            onConfirm = {
                confirmUninstall = false
                onUninstall()
            },
            onCancel = { confirmUninstall = false },
            isDestructive = true,
            confirmButtonModifier = Modifier.testTag("skill_store_uninstall_confirm"),
        )
    }
}

/**
 * 详情：简介、标签、Play 版受限提示、属性表、操作按钮组与文件清单。
 * 文件清单逐行列出（可达上千项），整页用懒列表承载。
 */
@Composable
fun SkillStoreDetailContent(item: StoreSkillItem, operation: StoreOperation?, actions: SkillStoreDetailActions) {
    val entry = item.entry
    InfinitySettingsPageLayout(title = entry.title, onBack = actions.onBack) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("skill_store_detail"),
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium),
                ) {
                    if (entry.summary.isNotBlank()) {
                        Text(
                            entry.summary,
                            style = MaterialTheme.typography.bodyMedium.forReading(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val tags = storeTags(item)
                    if (tags.isNotEmpty()) {
                        InfinityTagScrollRow { tags.forEach { (label, tone) -> WorkspaceTagChip(label = label, tone = tone) } }
                    }
                    if (item.limitedOnChannel) {
                        InfinityStatusStrip(
                            kind = InfinityStatusKind.Warning,
                            message = stringResource(R.string.skill_store_play_limited),
                            modifier = Modifier.fillMaxWidth().testTag("skill_store_play_limited"),
                        )
                    }
                }
            }
            item(key = "properties") { StoreProperties(item) }
            item(key = "actions") {
                StoreDetailButtons(
                    item = item,
                    operation = operation,
                    actions = actions,
                    modifier = Modifier.padding(vertical = InfinitySpacing.Large),
                )
            }
            item(key = "files_header") {
                Text(
                    pluralStringResource(R.plurals.skill_store_file_count, entry.files.size, entry.files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(bottom = InfinitySpacing.SectionHeaderBottom),
                )
            }
            itemsIndexed(entry.files, key = { _, file -> "file:${file.path}" }) { index, file ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    modifier = Modifier
                        .bettboxListGroupItem(index, entry.files.size)
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                ) {
                    Text(
                        file.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        storeSizeText(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            item(key = "bottom") { Box(Modifier.padding(bottom = InfinitySpacing.XXLarge)) }
        }
    }
}

@Composable
private fun StoreProperties(item: StoreSkillItem) {
    val entry = item.entry
    BettboxListGroup(Modifier.fillMaxWidth()) { InfinityPropertySheet(Modifier.padding(vertical = InfinitySpacing.Small)) {
        InfinityPropertyRow(
            label = stringResource(R.string.skill_store_detail_source),
            value = sourceName(item.source),
            supporting = item.source.host.takeIf { !item.source.official },
        )
        InfinityPropertyRow(label = stringResource(R.string.skill_store_detail_version), value = entry.version)
        when (val state = item.installState) {
            is StoreInstallState.UpdateAvailable -> InfinityPropertyRow(
                label = stringResource(R.string.skill_store_detail_installed_version),
                value = state.installedVersion,
            )
            is StoreInstallState.Installed, StoreInstallState.NameTaken -> Unit
            StoreInstallState.NotInstalled -> InfinityPropertyRow(
                label = stringResource(R.string.skill_store_detail_after_install),
                value = stringResource(if (item.source.official) R.string.skill_store_state_enabled else R.string.skill_store_state_disabled),
            )
        }
        InfinityPropertyRow(label = stringResource(R.string.skill_store_detail_channels), value = storeChannelsText(entry.channels))
        InfinityPropertyRow(label = stringResource(R.string.skill_store_detail_author), value = entry.author)
        InfinityPropertyRow(label = stringResource(R.string.skill_store_detail_license), value = entry.license)
        InfinityPropertyRow(label = stringResource(R.string.skill_store_detail_size), value = storeSizeText(entry.size))
        InfinityPropertyRow(
            label = "SHA-256",
            valueContent = {
                SelectionContainer {
                    Text(
                        entry.sha256,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            },
        )
    } }
}

/** 查看 SKILL.md、安装或更新（进行中为进度与取消）、已安装时卸载；放不下一行时整组竖排。 */
@Composable
private fun StoreDetailButtons(
    item: StoreSkillItem,
    operation: StoreOperation?,
    actions: SkillStoreDetailActions,
    modifier: Modifier = Modifier,
) {
    val installed = item.installState is StoreInstallState.Installed || item.installState is StoreInstallState.UpdateAvailable
    InfinityButtonGroup(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(onClick = actions.onViewSkillMd, modifier = Modifier.testTag("skill_store_view_skill_md")) {
            Text(stringResource(R.string.skill_store_view_skill_md), maxLines = 1)
        }
        when {
            operation is StoreOperation.Downloading -> OutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier.testTag("skill_store_cancel"),
            ) {
                CircularProgressIndicator(
                    progress = {
                        if (operation.totalBytes > 0) (operation.downloadedBytes.toFloat() / operation.totalBytes).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    stringResource(R.string.skill_store_downloading, storeSizeText(operation.downloadedBytes), storeSizeText(operation.totalBytes)),
                    maxLines = 1,
                    modifier = Modifier.padding(start = InfinitySpacing.Small),
                )
            }
            operation == StoreOperation.Installing -> OutlinedButton(onClick = {}, enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.skill_store_installing), maxLines = 1, modifier = Modifier.padding(start = InfinitySpacing.Small))
            }
            item.requiresAppUpdate -> Unit
            item.installState is StoreInstallState.UpdateAvailable -> Button(
                onClick = actions.onInstall,
                modifier = Modifier.testTag("skill_store_detail_update"),
            ) { Text(stringResource(R.string.skill_store_update), maxLines = 1) }
            item.installState is StoreInstallState.Installed -> Unit
            else -> Button(
                onClick = actions.onInstall,
                modifier = Modifier.testTag("skill_store_detail_install"),
            ) { Text(stringResource(R.string.skill_store_install), maxLines = 1) }
        }
        if (installed && operation == null) {
            OutlinedButton(
                onClick = actions.onUninstall,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("skill_store_uninstall"),
            ) { Text(stringResource(R.string.skill_store_uninstall), maxLines = 1) }
        }
    }
}

/** SKILL.md 全文：先下载并校验技能包（有缓存时直接读取），读取中、失败与正文交叉淡入。 */
@Composable
private fun SkillMarkdownSheet(key: String, load: suspend () -> Result<String>, onDismiss: () -> Unit) {
    var result by remember(key) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(key) { result = load() }
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = "SKILL.md",
        height = InfinitySheetHeight.Full,
        scrollContent = true,
    ) {
        val phase = when {
            result == null -> 0
            result!!.isFailure -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "skill_store_document",
            modifier = Modifier.padding(bottom = InfinitySpacing.XLarge),
        ) { shown ->
            when (shown) {
                0 -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                1 -> Text(
                    stringResource(R.string.skill_store_document_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> SelectionContainer {
                    Text(
                        result?.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
