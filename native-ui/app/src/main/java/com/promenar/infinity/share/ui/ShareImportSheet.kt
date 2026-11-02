package com.promenar.infinity.share.ui

import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareIndexStatus
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareLinuxAvailability
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.share.core.ShareTargetKind
import com.promenar.infinity.ui.common.InfinityFullscreenDialog
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillMenuButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.linux.files.LinuxDirectoryPicker
import com.promenar.infinity.ui.linux.files.LinuxFilesScreen
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.util.Locale

/** 会话列表、资料库文件夹与 Linux 目录三个选择器的回调；预览与截图测试传空实现。 */
data class ShareImportPickerActions(
    val onOpenSessionPicker: () -> Unit = {},
    val onPickSession: (ShareSessionChoice) -> Unit = {},
    val onDismissSessionPicker: () -> Unit = {},
    val onOpenFolderPicker: () -> Unit = {},
    val onEnterFolder: (ShareKnowledgeFolder) -> Unit = {},
    val onFolderUp: () -> Unit = {},
    val onConfirmFolder: () -> Unit = {},
    val onDismissFolderPicker: () -> Unit = {},
    val onPickLinuxDirectory: (String) -> Unit = {},
    /** Linux 未安装时前往“Linux 环境”页面安装。 */
    val onInstallLinux: () -> Unit = {},
)

/** Linux 模式会话里本会话文件夹的路径，写在“当前会话”一项的说明里。 */
private const val LINUX_SESSION_FOLDER = "/home/session"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportSheet(
    state: ShareImportUiState,
    onSelectTarget: (ShareTargetKind) -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit = {},
    pickers: ShareImportPickerActions = ShareImportPickerActions(),
) {
    if (!state.visible) return
    var confirmCancel by remember { mutableStateOf(false) }
    var linuxPickerOpen by rememberSaveable { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.share_import_cancel_title)) },
            text = { Text(stringResource(R.string.share_import_cancel_message)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onCancel() }) {
                    Text(stringResource(R.string.share_import_cancel_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.share_import_cancel_back))
                }
            },
        )
    }
    InfinityModalSheet(
        onDismissRequest = onClose,
        height = InfinitySheetHeight.Content,
        modifier = Modifier
            .testTag(UiTags.SHARE_IMPORT_SHEET)
            .semantics { testTagsAsResourceId = true },
    ) {
        ShareImportSheetContent(
            state = state,
            onSelectTarget = onSelectTarget,
            onImport = onImport,
            onRetry = onRetry,
            onClose = onClose,
            onRequestCancel = { confirmCancel = true },
            pickers = pickers,
            onOpenLinuxPicker = { linuxPickerOpen = true },
        )
    }
    state.sessionPicker?.let { ShareSessionPickerSheet(it, pickers) }
    state.folderPicker?.let { ShareFolderPickerSheet(it, pickers) }
    if (linuxPickerOpen && state.linuxReady) {
        InfinityFullscreenDialog(
            onDismissRequest = { linuxPickerOpen = false },
            systemBarColor = MaterialTheme.colorScheme.background,
        ) {
            LinuxFilesScreen(
                onBack = { linuxPickerOpen = false },
                onOpenTerminal = { _, _ -> },
                onOpenEnvironment = { linuxPickerOpen = false; pickers.onInstallLinux() },
                initialPath = state.linuxDirectory ?: state.options?.linuxDefaultDirectory ?: "/root",
                picker = LinuxDirectoryPicker(
                    title = stringResource(R.string.share_import_ext_linux_picker_title),
                    confirmLabel = stringResource(R.string.share_import_ext_picker_use),
                    onPick = { path ->
                        linuxPickerOpen = false
                        pickers.onPickLinuxDirectory(path)
                    },
                ),
            )
        }
    }
}

/** 面板正文：分享来的文件、四个导入位置、结果说明与操作按钮；截图测试直接渲染这一层。 */
@Composable
internal fun ColumnScope.ShareImportSheetContent(
    state: ShareImportUiState,
    onSelectTarget: (ShareTargetKind) -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onRequestCancel: () -> Unit,
    pickers: ShareImportPickerActions,
    onOpenLinuxPicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .imePadding()
            .padding(vertical = InfinitySpacing.Medium),
    ) {
        Text(
            stringResource(R.string.share_import_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(InfinitySpacing.Small))
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        ) {
            items(state.items, key = { it.uri.toString() }) { item -> ShareImportItemRow(item) }
            item(key = "target_label") {
                Text(
                    stringResource(R.string.share_import_target_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = InfinitySpacing.Medium),
                )
            }
            item(key = "targets") {
                ShareImportTargets(state, onSelectTarget, pickers, onOpenLinuxPicker)
            }
            item(key = "note") { ShareImportNote(state) }
        }
        state.error?.let { error ->
            Text(
                text = stringResource(error.stringResource()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = InfinitySpacing.Small),
            )
        }
        // 全部导入成功后请求已确认，“稍后处理 / 取消导入”不再有意义，只留“完成”（索引失败时另有“重试失败项”）。
        val actions = shareImportActions(state)
        val finished = ShareImportAction.DONE in actions
        val retryButton: @Composable () -> Unit = {
            InfinityPillButton(
                text = stringResource(R.string.share_import_retry),
                onClick = onRetry,
                enabled = !state.importing && (
                    state.canImportTo ||
                        state.error in setOf(
                            ShareImportErrorCode.PRESENT_FAILED,
                            ShareImportErrorCode.POSTPONE_FAILED,
                            ShareImportErrorCode.CANCEL_FAILED,
                        )
                    ),
            )
        }
        Spacer(Modifier.height(InfinitySpacing.Medium))
        if (finished) {
            val doneButton: @Composable () -> Unit = {
                InfinityPillButton(
                    text = stringResource(R.string.share_import_done),
                    onClick = onClose,
                    tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_DONE),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (ShareImportAction.RETRY in actions) {
                    InfinityPillRow(modifier = Modifier.weight(1f)) {
                        retryButton()
                        doneButton()
                    }
                } else {
                    doneButton()
                }
            }
        } else {
            // “稍后处理 / 取消导入”低频且后者会删除暂存副本，收进“⋯”菜单；重试与导入留在操作行，主操作在最右。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfinityPillMenuButton(
                    contentDescription = stringResource(R.string.share_import_more_cd),
                    enabled = !state.importing,
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_MORE),
                ) { dismiss ->
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.share_import_later)) },
                        onClick = { dismiss(); onClose() },
                        leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                        modifier = Modifier.testTag(UiTags.SHARE_IMPORT_LATER),
                    )
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.share_import_cancel)) },
                        onClick = { dismiss(); onRequestCancel() },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        destructive = true,
                        modifier = Modifier.testTag(UiTags.SHARE_IMPORT_CANCEL),
                    )
                }
                InfinityPillRow(modifier = Modifier.weight(1f)) {
                    if (ShareImportAction.RETRY in actions) retryButton()
                    InfinityPillButton(
                        text = if (state.importing) stringResource(R.string.share_import_importing)
                        else stringResource(R.string.share_import_action),
                        onClick = onImport,
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !state.importing && state.canImportTo &&
                            state.items.any { it.status == ShareImportStatus.Pending },
                        modifier = Modifier.testTag(UiTags.SHARE_IMPORT_ACTION),
                    )
                }
            }
        }
    }
}

/** 四个导入位置，单选；选中的“其他会话 / Linux / 资料库”下方显示已选的会话或目录与更换按钮。 */
@Composable
private fun ShareImportTargets(
    state: ShareImportUiState,
    onSelectTarget: (ShareTargetKind) -> Unit,
    pickers: ShareImportPickerActions,
    onOpenLinuxPicker: () -> Unit,
) {
    val options = state.options
    if (options == null) {
        Text(
            stringResource(R.string.share_import_no_target),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = InfinitySpacing.Small),
        )
        return
    }
    val enabled = !state.importing
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        options.currentSession?.let { session ->
            ShareTargetRow(
                title = stringResource(R.string.share_import_ext_target_current),
                subtitle = stringResource(R.string.share_import_ext_target_current_sub, session.title, LINUX_SESSION_FOLDER),
                selected = state.selectedKind == ShareTargetKind.CurrentSession,
                enabled = enabled,
                onClick = { onSelectTarget(ShareTargetKind.CurrentSession) },
                tag = UiTags.SHARE_IMPORT_TARGET_CURRENT,
            )
        }
        ShareTargetRow(
            title = stringResource(R.string.share_import_ext_target_other),
            subtitle = stringResource(R.string.share_import_ext_target_other_sub),
            selected = state.selectedKind == ShareTargetKind.OtherSession,
            enabled = enabled,
            onClick = { onSelectTarget(ShareTargetKind.OtherSession) },
            tag = UiTags.SHARE_IMPORT_TARGET_OTHER,
        )
        if (state.selectedKind == ShareTargetKind.OtherSession) {
            SharePathBox(
                value = state.otherSession?.title ?: stringResource(R.string.share_import_ext_session_unselected),
                action = stringResource(
                    if (state.otherSession == null) R.string.share_import_ext_choose_session else R.string.share_import_ext_change
                ),
                enabled = enabled,
                onAction = pickers.onOpenSessionPicker,
            )
        }
        val linuxReady = options.linux == ShareLinuxAvailability.Ready
        ShareTargetRow(
            title = stringResource(R.string.share_import_ext_target_linux),
            subtitle = stringResource(
                when (options.linux) {
                    ShareLinuxAvailability.Ready -> R.string.share_import_ext_target_linux_sub
                    ShareLinuxAvailability.NeedsInstall -> R.string.share_import_ext_target_linux_needs_install
                    ShareLinuxAvailability.Unsupported -> R.string.share_import_ext_target_linux_unsupported
                }
            ),
            selected = state.selectedKind == ShareTargetKind.Linux,
            enabled = enabled && linuxReady,
            onClick = { onSelectTarget(ShareTargetKind.Linux) },
            tag = UiTags.SHARE_IMPORT_TARGET_LINUX,
        )
        if (options.linux == ShareLinuxAvailability.NeedsInstall) {
            InfinityPillButton(
                text = stringResource(R.string.share_import_ext_linux_install),
                onClick = pickers.onInstallLinux,
                enabled = enabled,
                icon = Icons.Rounded.Download,
                modifier = Modifier
                    .padding(start = 52.dp)
                    .testTag(UiTags.SHARE_IMPORT_LINUX_INSTALL),
            )
        }
        if (state.selectedKind == ShareTargetKind.Linux && linuxReady) {
            SharePathBox(
                value = state.linuxDirectory ?: options.linuxDefaultDirectory,
                action = stringResource(R.string.share_import_ext_choose_folder),
                enabled = enabled,
                monospace = true,
                onAction = onOpenLinuxPicker,
            )
        }
        ShareTargetRow(
            title = stringResource(R.string.share_import_ext_target_kb),
            subtitle = stringResource(R.string.share_import_ext_target_kb_sub),
            selected = state.selectedKind == ShareTargetKind.KnowledgeBase,
            enabled = enabled,
            onClick = { onSelectTarget(ShareTargetKind.KnowledgeBase) },
            tag = UiTags.SHARE_IMPORT_KNOWLEDGE_BASE_TARGET,
        )
        if (state.selectedKind == ShareTargetKind.KnowledgeBase) {
            SharePathBox(
                value = state.knowledgeFolder?.let { knowledgeFolderLabel(it.path) }
                    ?: stringResource(R.string.share_import_ext_kb_root),
                action = stringResource(R.string.share_import_ext_choose_folder),
                enabled = enabled,
                onAction = pickers.onOpenFolderPicker,
            )
        }
    }
}

@Composable
private fun ShareTargetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled || selected) 1f else 0.42f)
            .padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.Small)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = InfinitySpacing.Small))
        Spacer(Modifier.width(InfinitySpacing.Small))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SharePathBox(
    value: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
    monospace: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 20.dp, bottom = InfinitySpacing.XSmall),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = InfinitySpacing.Medium),
        ) {
            Text(
                value,
                style = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace)
                else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            InfinityPillButton(
                text = action,
                onClick = onAction,
                enabled = enabled,
                compact = true,
                modifier = Modifier
                    .padding(end = InfinitySpacing.XSmall)
                    .testTag(UiTags.SHARE_IMPORT_TARGET_CHANGE),
            )
        }
    }
}

/** 选中位置下的结果说明：文件去哪里、谁能看到、会不会建索引。 */
@Composable
private fun ShareImportNote(state: ShareImportUiState) {
    val text = when (state.selectedKind) {
        ShareTargetKind.CurrentSession -> stringResource(R.string.share_import_ext_note_current)
        ShareTargetKind.OtherSession -> state.otherSession?.let {
            stringResource(R.string.share_import_ext_note_other, it.title)
        } ?: stringResource(R.string.share_import_ext_note_other_unselected)
        ShareTargetKind.Linux -> stringResource(
            R.string.share_import_ext_note_linux,
            state.linuxDirectory ?: state.options?.linuxDefaultDirectory.orEmpty(),
        )
        ShareTargetKind.KnowledgeBase -> stringResource(
            R.string.share_import_ext_note_kb,
            state.knowledgeFolder?.let { knowledgeFolderLabel(it.path) } ?: stringResource(R.string.share_import_ext_kb_root),
        )
        null -> return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = InfinitySpacing.Small)
            .testTag(UiTags.SHARE_IMPORT_NOTE),
    ) {
        Column(Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = 10.dp)) {
            Text(
                stringResource(R.string.share_import_ext_result_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 资料库路径按“资料 / 财务”的层级写法显示。 */
internal fun knowledgeFolderLabel(path: String): String =
    path.split('/').filter(String::isNotBlank).joinToString(" / ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSessionPickerSheet(picker: ShareSessionPickerState, pickers: ShareImportPickerActions) {
    InfinityModalSheet(
        onDismissRequest = pickers.onDismissSessionPicker,
        height = InfinitySheetHeight.Content,
        modifier = Modifier.testTag(UiTags.SHARE_IMPORT_SESSION_PICKER),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Medium)) {
            Text(
                stringResource(R.string.share_import_ext_session_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = InfinitySpacing.Small),
            )
            when {
                picker.loading -> Unit
                picker.failed -> SharePickerMessage(stringResource(R.string.share_import_error_present))
                picker.sessions.isEmpty() -> SharePickerMessage(stringResource(R.string.share_import_ext_session_picker_empty))
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(picker.sessions, key = { it.sessionId }) { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = InfinitySpacing.RowTwoLineHeight)
                                .selectable(selected = false, role = Role.Button) { pickers.onPickSession(session) }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .testTag(UiTags.shareImportSession(session.sessionId)),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                android.text.format.DateUtils.getRelativeTimeSpanString(session.updatedAt).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            InfinityPillButton(
                text = stringResource(R.string.share_import_ext_picker_cancel),
                onClick = pickers.onDismissSessionPicker,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 20.dp, vertical = InfinitySpacing.Small),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareFolderPickerSheet(picker: ShareFolderPickerState, pickers: ShareImportPickerActions) {
    InfinityModalSheet(
        onDismissRequest = pickers.onDismissFolderPicker,
        height = InfinitySheetHeight.Content,
        modifier = Modifier.testTag(UiTags.SHARE_IMPORT_FOLDER_PICKER),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Medium)) {
            Text(
                stringResource(R.string.share_import_ext_folder_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = InfinitySpacing.Small),
            )
            Text(
                picker.trail.lastOrNull()?.let { knowledgeFolderLabel(it.path) }
                    ?: stringResource(R.string.share_import_ext_kb_root),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = InfinitySpacing.Small))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (picker.trail.isNotEmpty()) {
                    item(key = "..") {
                        SharePickerRow(
                            text = stringResource(R.string.share_import_ext_picker_up),
                            enabled = !picker.loading,
                            tag = UiTags.SHARE_IMPORT_FOLDER_UP,
                            onClick = pickers.onFolderUp,
                        )
                    }
                }
                when {
                    picker.loading -> Unit
                    picker.failed -> item(key = "error") { SharePickerMessage(stringResource(R.string.share_import_ext_picker_error)) }
                    picker.folders.isEmpty() -> item(key = "empty") { SharePickerMessage(stringResource(R.string.share_import_ext_picker_empty)) }
                    else -> items(picker.folders, key = { it.uuid }) { folder ->
                        SharePickerRow(
                            text = folder.name,
                            enabled = true,
                            tag = UiTags.shareImportFolder(folder.uuid),
                            onClick = { pickers.onEnterFolder(folder) },
                        )
                    }
                }
            }
            InfinityPillRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = InfinitySpacing.Small),
            ) {
                InfinityPillButton(
                    text = stringResource(R.string.share_import_ext_picker_cancel),
                    onClick = pickers.onDismissFolderPicker,
                )
                InfinityPillButton(
                    text = stringResource(R.string.share_import_ext_picker_use),
                    onClick = pickers.onConfirmFolder,
                    tone = InfinityPillTone.PRIMARY,
                    enabled = !picker.loading && !picker.failed,
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_FOLDER_CONFIRM),
                )
            }
        }
    }
}

@Composable
private fun SharePickerRow(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget + 8.dp)
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp)
            .testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SharePickerMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = InfinitySpacing.Large),
    )
}

private fun ShareImportErrorCode.stringResource(): Int = when (this) {
    ShareImportErrorCode.PRESENT_FAILED -> R.string.share_import_error_present
    ShareImportErrorCode.IMPORT_FAILED -> R.string.share_import_error_import
    ShareImportErrorCode.INDEX_RETRY_FAILED -> R.string.share_import_error_index_retry
    ShareImportErrorCode.POSTPONE_FAILED -> R.string.share_import_error_postpone
    ShareImportErrorCode.CANCEL_FAILED -> R.string.share_import_error_cancel
}

@Composable
fun SharePendingBanner(
    pendingCount: Int,
    visible: Boolean,
    onOpen: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Text(
                text = stringResource(R.string.share_import_pending_banner, pendingCount),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun ShareImportItemRow(item: ShareImportItem) {
    Column(
        Modifier
            .testTag(UiTags.SHARE_IMPORT_ITEM)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = InfinitySpacing.XSmall),
    ) {
        Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
        InfinityMetaLine(
            segments = listOfNotNull(
                shareFileTypeLabel(item.mimeType, item.displayName),
                item.sizeBytes?.let(::formatShareBytes),
                if (item.status == ShareImportStatus.Pending) parseLabel(item) else statusLabel(item),
                // 导入后仍标出“原样保存”，说明这一项不会建索引。
                if (item.status == ShareImportStatus.Created && !item.parseable) parseLabel(item) else null,
            ),
            modifier = Modifier.testTag(UiTags.SHARE_IMPORT_STATUS),
        )
        item.reason?.let {
            Text(rejectReasonLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun parseLabel(item: ShareImportItem): String = stringResource(
    if (item.parseable) R.string.share_import_ext_parseable else R.string.share_import_ext_raw
)

internal fun compactMimeType(mimeType: String?): String? {
    if (mimeType == null) return null
    return when (mimeType.lowercase(Locale.ROOT)) {
        "application/pdf" -> "PDF"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
        "text/html" -> "HTML"
        "text/plain" -> "TXT"
        else -> mimeType
    }
}

/** 文件类型标签：常见格式用短名；其它格式优先用扩展名（SQLITE、EPUB），没有扩展名时退回 MIME。 */
internal fun shareFileTypeLabel(mimeType: String?, displayName: String): String? {
    val compact = compactMimeType(mimeType)
    if (compact != null && compact != mimeType) return compact
    val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 10 && it != displayName }
    return extension?.uppercase(Locale.ROOT) ?: compact
}

@Composable
private fun statusLabel(item: ShareImportItem): String = when (item.status) {
    ShareImportStatus.Pending -> stringResource(R.string.share_import_status_pending)
    ShareImportStatus.Importing -> stringResource(R.string.share_import_status_importing)
    ShareImportStatus.Created -> when (item.indexStatus) {
        ShareIndexStatus.Pending -> stringResource(R.string.share_import_status_index_pending)
        ShareIndexStatus.Partial -> stringResource(R.string.share_import_status_index_partial)
        ShareIndexStatus.Failed -> stringResource(R.string.share_import_status_index_failed)
        ShareIndexStatus.Completed -> stringResource(R.string.share_import_status_index_completed)
        null -> stringResource(R.string.share_import_status_created)
    }
    ShareImportStatus.Rejected -> stringResource(R.string.share_import_status_rejected)
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason): String = stringResource(
    when (reason) {
        ShareRejectReason.TargetRequired -> R.string.share_import_reason_target
        ShareRejectReason.UnsupportedMime -> R.string.share_import_reason_mime
        ShareRejectReason.MimeMismatch -> R.string.share_import_reason_mismatch
        ShareRejectReason.InvalidName -> R.string.share_import_reason_name
        ShareRejectReason.PermissionDenied -> R.string.share_import_reason_permission
        ShareRejectReason.EmptyFile -> R.string.share_import_reason_empty
        ShareRejectReason.ItemTooLarge -> R.string.share_import_reason_item_large
        ShareRejectReason.BatchTooLarge -> R.string.share_import_reason_batch_large
        ShareRejectReason.ReadFailed -> R.string.share_import_reason_read
        ShareRejectReason.WriteFailed -> R.string.share_import_reason_write
        ShareRejectReason.IndexScheduleFailed -> R.string.share_import_reason_index
        ShareRejectReason.LinuxUnavailable -> R.string.share_import_ext_reason_linux_unavailable
        ShareRejectReason.LinuxPathInvalid -> R.string.share_import_ext_reason_linux_path
    }
)

private fun formatShareBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun shareImportFinished(state: ShareImportUiState): Boolean =
    !state.importing && state.error == null && state.items.isNotEmpty() &&
        state.items.all { it.status == ShareImportStatus.Created }

/** 分享导入面板底部操作行的按钮（“⋯”菜单除外），按从左到右排列，主操作在最右。 */
internal enum class ShareImportAction { RETRY, IMPORT, DONE }

/** 全部导入后若有文件索引失败，“重试失败项”仍须与“完成”并列，状态文案“可重试”才有入口。 */
internal fun shareImportActions(state: ShareImportUiState): List<ShareImportAction> {
    val showRetry = state.error != null ||
        state.items.any { it.status == ShareImportStatus.Rejected && it.reason?.retryable == true } ||
        state.items.any { it.indexStatus in setOf(ShareIndexStatus.Failed, ShareIndexStatus.Partial) }
    val primary = if (shareImportFinished(state)) ShareImportAction.DONE else ShareImportAction.IMPORT
    return listOfNotNull(ShareImportAction.RETRY.takeIf { showRetry }, primary)
}
