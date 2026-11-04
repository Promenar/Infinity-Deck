package com.promenar.infinity.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

/*
 * 资源管理器的对话框：新建、重命名、删除确认、压缩命名、粘贴为文件与详情。
 * 都基于全站唯一的 InfinityConfirmDialog；名称输入使用 .forInput() 换行策略。
 */

/** 组件内一次只开一个对话框。 */
internal sealed interface FileManagerDialog {
    data object NewFolder : FileManagerDialog
    data object NewTextFile : FileManagerDialog
    data object PickerNewFolder : FileManagerDialog
    data class Rename(val item: FileItem) : FileManagerDialog
    data class Delete(val items: List<FileItem>, val fromSelection: Boolean) : FileManagerDialog
    data class Compress(val items: List<FileItem>, val fromSelection: Boolean) : FileManagerDialog
    data class PasteAsFile(val text: String, val defaultName: String) : FileManagerDialog
    data class Details(val items: List<FileItem>) : FileManagerDialog
    data object FolderDetails : FileManagerDialog
}

@Composable
internal fun FileManagerDialogHost(
    dialog: FileManagerDialog?,
    state: FileManagerUiState,
    actions: FileManagerUiActions,
    onDismiss: () -> Unit,
) {
    val controller = actions.controller
    when (dialog) {
        null -> Unit
        FileManagerDialog.NewFolder -> FileNameDialog(
            title = stringResource(R.string.fm_new_folder),
            initial = stringResource(R.string.fm_default_folder_name),
            selectBaseName = false,
            confirmText = stringResource(R.string.fm_create),
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                controller.createFolder(name)
            },
        )
        FileManagerDialog.NewTextFile -> FileNameDialog(
            title = stringResource(R.string.fm_new_text_file),
            initial = stringResource(R.string.fm_default_file_name),
            selectBaseName = true,
            confirmText = stringResource(R.string.fm_create),
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                controller.createTextFile(name)
            },
        )
        FileManagerDialog.PickerNewFolder -> FileNameDialog(
            title = stringResource(R.string.fm_new_folder),
            initial = stringResource(R.string.fm_default_folder_name),
            selectBaseName = false,
            confirmText = stringResource(R.string.fm_create),
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                controller.pickerCreateFolder(name)
            },
        )
        is FileManagerDialog.Rename -> FileNameDialog(
            title = stringResource(R.string.fm_rename),
            initial = dialog.item.name,
            selectBaseName = !dialog.item.isDirectory,
            confirmText = stringResource(R.string.fm_rename_confirm),
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                if (name != dialog.item.name) controller.rename(dialog.item, name)
            },
        )
        is FileManagerDialog.Compress -> {
            val single = dialog.items.singleOrNull()
            val base = single?.name?.let { name -> name.take(renameSelectionEnd(name, single.isDirectory)) }
                ?: state.currentFolderTitle().ifBlank { stringResource(R.string.fm_default_archive_name) }
            FileNameDialog(
                title = stringResource(R.string.fm_compress),
                initial = "$base.zip",
                selectBaseName = true,
                confirmText = stringResource(R.string.fm_compress_confirm),
                onDismiss = onDismiss,
                onConfirm = { name ->
                    onDismiss()
                    val archive = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
                    controller.compress(if (dialog.fromSelection) emptyList() else dialog.items, archive)
                },
            )
        }
        is FileManagerDialog.PasteAsFile -> FileNameDialog(
            title = stringResource(R.string.fm_paste_as_file),
            initial = dialog.defaultName,
            selectBaseName = true,
            confirmText = stringResource(R.string.fm_create),
            message = pluralStringResource(R.plurals.fm_paste_length, dialog.text.length, dialog.text.length),
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                controller.importText(dialog.text, name)
            },
        )
        is FileManagerDialog.Delete -> {
            val count = dialog.items.size
            InfinityConfirmDialog(
                title = if (count == 1) {
                    stringResource(R.string.fm_delete_one_title, dialog.items.first().name)
                } else {
                    pluralStringResource(R.plurals.fm_delete_many_title, count, count)
                },
                message = stringResource(R.string.fm_delete_message),
                confirmText = stringResource(R.string.fm_delete_confirm),
                cancelText = stringResource(R.string.fm_cancel),
                isDestructive = true,
                onConfirm = {
                    onDismiss()
                    controller.delete(if (dialog.fromSelection) emptyList() else dialog.items)
                },
                onCancel = onDismiss,
                confirmButtonModifier = Modifier.testTag(UiTags.FM_DIALOG_CONFIRM),
            )
        }
        is FileManagerDialog.Details -> FileDetailsDialog(
            title = dialog.items.singleOrNull()?.name ?: pluralStringResource(R.plurals.fm_selected_count, dialog.items.size, dialog.items.size),
            rows = detailsRows(dialog.items, state),
            onDismiss = onDismiss,
        )
        FileManagerDialog.FolderDetails -> FileDetailsDialog(
            title = state.currentFolderTitle(),
            rows = folderDetailsRows(state),
            onDismiss = onDismiss,
        )
    }
}

/**
 * 单字段名称输入：打开即聚焦，[selectBaseName] 为 true 时默认只选中扩展名之前的部分。
 * 名称为空或含路径分隔符时确认按钮不可用，并在输入框下方提示。
 */
@Composable
internal fun FileNameDialog(
    title: String,
    initial: String,
    selectBaseName: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    message: String = "",
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val end = if (selectBaseName) renameSelectionEnd(initial, isDirectory = false) else initial.length
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, end)))
    }
    val valid = isValidFileName(value.text)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    InfinityConfirmDialog(
        title = title,
        message = message,
        confirmText = confirmText,
        cancelText = stringResource(R.string.fm_cancel),
        onConfirm = { if (valid) onConfirm(value.text.trim()) },
        onCancel = onDismiss,
        confirmEnabled = valid,
        confirmButtonModifier = Modifier.testTag(UiTags.FM_DIALOG_CONFIRM),
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = LocalTextStyle.current.forInput(),
                singleLine = true,
                label = { Text(stringResource(R.string.fm_name_label)) },
                isError = value.text.isNotEmpty() && !valid,
                supportingText = if (value.text.isNotEmpty() && !valid) {
                    { Text(stringResource(R.string.fm_name_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(UiTags.FM_NAME_INPUT),
            )
        },
    )
}

/** 详情：标签与值两列，值可折行。 */
@Composable
internal fun FileDetailsDialog(title: String, rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    InfinityConfirmDialog(
        title = title,
        message = "",
        confirmText = stringResource(R.string.fm_close),
        cancelText = null,
        onConfirm = onDismiss,
        onCancel = onDismiss,
        confirmButtonModifier = Modifier.testTag(UiTags.FM_DIALOG_CONFIRM),
        content = { FileInfoRows(rows) },
    )
}

@Composable
internal fun FileInfoRows(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 条目所在位置：位置名 › 各级目录。 */
internal fun locationPathText(state: FileManagerUiState): String =
    state.fullCrumbs().joinToString(" › ") { it.title }

@Composable
private fun detailsRows(items: List<FileItem>, state: FileManagerUiState): List<Pair<String, String>> {
    val single = items.singleOrNull()
    val rows = mutableListOf(stringResource(R.string.fm_info_location) to locationPathText(state))
    if (single != null) {
        rows += stringResource(R.string.fm_info_type) to stringResource(
            if (single.isDirectory) R.string.fm_info_type_folder else R.string.fm_info_type_file,
        )
        if (single.isDirectory) {
            single.childCount?.let { rows += stringResource(R.string.fm_info_items) to pluralStringResource(R.plurals.fm_item_count, it, it) }
        } else {
            rows += stringResource(R.string.fm_info_size) to formatFileSize(single.sizeBytes)
        }
        rows += stringResource(R.string.fm_info_modified) to formatFileTime(single.lastModified)
        single.knowledge?.let { knowledge ->
            val status = stringResource(knowledgeStateLabel(knowledge.state))
            rows += stringResource(R.string.fm_info_knowledge) to (knowledge.failureReason?.let { "$status\n$it" } ?: status)
        }
    } else {
        rows += stringResource(R.string.fm_info_items) to pluralStringResource(R.plurals.fm_item_count, items.size, items.size)
        rows += stringResource(R.string.fm_info_size) to formatFileSize(items.filterNot { it.isDirectory }.sumOf { it.sizeBytes })
    }
    return rows
}

@Composable
private fun folderDetailsRows(state: FileManagerUiState): List<Pair<String, String>> {
    val folders = state.items.count { it.isDirectory }
    val files = state.items.size - folders
    val rows = mutableListOf(
        stringResource(R.string.fm_info_location) to locationPathText(state),
        stringResource(R.string.fm_info_folders) to pluralStringResource(R.plurals.fm_item_count, folders, folders),
        stringResource(R.string.fm_info_files) to pluralStringResource(R.plurals.fm_item_count, files, files),
        stringResource(R.string.fm_info_size) to formatFileSize(state.items.filterNot { it.isDirectory }.sumOf { it.sizeBytes }),
    )
    state.location?.freeBytes?.let { rows += stringResource(R.string.fm_info_free) to formatFileSize(it) }
    state.location?.gitBranch?.let { rows += stringResource(R.string.fm_info_git) to it }
    return rows
}
