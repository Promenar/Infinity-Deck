package com.promenar.infinity.ui.chat.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.storage.ExternalRecycleItem
import com.promenar.infinity.domain.storage.IExternalFolderManager
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.ui.common.FolderStatusMessage
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 目录工作区的文件操作回调；为 null 时浏览器保持只读。 */
internal data class DirectoryFileActions(
    val onToggleSelect: (String) -> Unit,
    val onClearSelection: () -> Unit,
    val onCopy: (cut: Boolean) -> Unit,
    val onPaste: () -> Unit,
    val onCancelClipboard: () -> Unit,
    val onDelete: () -> Unit,
    val onCreateFolder: (name: String, onResult: (DirectoryFileOpCode?) -> Unit) -> Unit,
    val onRename: (path: String, name: String, onResult: (DirectoryFileOpCode?) -> Unit) -> Unit,
    val onDismissOutcome: () -> Unit,
    val onDeletePermanently: () -> Unit = {},
    val onKeepTooLarge: () -> Unit = {},
)

/** 私有工作区文件页的长按操作回调；条目以 UUID 标识，为 null 时文件页保持只读。 */
internal data class PrivateFileActions(
    val onToggleSelect: (FileEntry) -> Unit,
    val onClearSelection: () -> Unit,
    val onCut: () -> Unit,
    val onPaste: (targetParentUuid: String) -> Unit,
    val onCancelClipboard: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: (uuid: String, name: String, onResult: (DirectoryFileOpCode?) -> Unit) -> Unit,
    val onDismissOutcome: () -> Unit,
)

@Composable
internal fun directoryOpCodeText(code: DirectoryFileOpCode): String = stringResource(
    when (code) {
        DirectoryFileOpCode.NAME_INVALID -> R.string.directory_op_name_invalid
        DirectoryFileOpCode.NAME_EXISTS -> R.string.directory_op_name_exists
        DirectoryFileOpCode.NOT_FOUND -> R.string.directory_op_not_found
        DirectoryFileOpCode.TARGET_INSIDE_SOURCE -> R.string.directory_op_inside_source
        DirectoryFileOpCode.READ_ONLY -> R.string.directory_op_read_only
        DirectoryFileOpCode.WORKSPACE_UNAVAILABLE -> R.string.directory_op_unavailable
        DirectoryFileOpCode.SESSION_BUSY -> R.string.directory_op_busy
        DirectoryFileOpCode.TOO_LARGE -> R.string.directory_op_too_large
        DirectoryFileOpCode.IO -> R.string.directory_op_failed
    },
)

@Composable
internal fun directoryOutcomeSegments(outcome: DirectoryOpOutcome): List<String> {
    val done = when (outcome.op) {
        DirectoryOp.COPY -> pluralStringResource(R.plurals.directory_op_copied, outcome.succeeded, outcome.succeeded)
        DirectoryOp.MOVE -> pluralStringResource(R.plurals.directory_op_moved, outcome.succeeded, outcome.succeeded)
        DirectoryOp.DELETE -> pluralStringResource(R.plurals.directory_op_deleted, outcome.succeeded, outcome.succeeded)
    }
    if (outcome.failed == 0) return listOf(done)
    val count = pluralStringResource(R.plurals.directory_op_failed_count, outcome.failed, outcome.failed)
    val failed = outcome.reason?.let { stringResource(R.string.directory_op_failed_reason, count, directoryOpCodeText(it)) } ?: count
    return if (outcome.succeeded == 0) listOf(failed) else listOf(done, failed)
}

/**
 * 选择期间的底部操作栏；重命名只对单个条目可用，[onCopy] 为 null 的来源不显示复制。
 *
 * 各来源在 [topActions]（计数行右侧，如全选、权限）与 [extraActions]（删除前，如压缩、在终端打开）补充自身能力。
 */
@Composable
internal fun DirectorySelectionBar(
    count: Int,
    busy: Boolean,
    onCopy: (() -> Unit)?,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable RowScope.() -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) = Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
        Row(Modifier.fillMaxWidth().padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.directory_selected_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            topActions()
            IconButton(onClick = onClear, modifier = Modifier.testTag("directory_selection_clear")) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_btn_cancel))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XSmall)) {
            if (onCopy != null) DirectorySelectionAction(Icons.Rounded.ContentCopy, R.string.directory_action_copy, !busy, onCopy, "directory_action_copy")
            DirectorySelectionAction(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.directory_action_move, !busy, onMove, "directory_action_move")
            DirectorySelectionAction(Icons.Rounded.DriveFileRenameOutline, R.string.directory_action_rename, !busy && count == 1, onRename, "directory_action_rename")
            extraActions()
            DirectorySelectionAction(Icons.Rounded.DeleteOutline, R.string.directory_action_delete, !busy, onDelete, "directory_action_delete", danger = true)
        }
    }
}

/** 选择栏中的一项操作：各项等宽，长标签最多两行。 */
@Composable
internal fun RowScope.DirectorySelectionAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(tag),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** 剪贴板中有条目时的粘贴栏；目标是被剪切条目本身、其子目录或原目录时不能粘贴。 */
@Composable
internal fun DirectoryPasteBar(
    clipboard: DirectoryClipboard,
    canPaste: Boolean,
    busy: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) = Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Text(
            pluralStringResource(
                if (clipboard.cut) R.plurals.directory_clipboard_cut else R.plurals.directory_clipboard_copy,
                clipboard.paths.size,
                clipboard.paths.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InfinityPillRow(modifier = Modifier.fillMaxWidth()) {
            InfinityPillButton(
                text = stringResource(R.string.common_btn_cancel),
                onClick = onCancel,
                enabled = !busy,
            )
            InfinityPillButton(
                text = stringResource(R.string.directory_paste_here),
                onClick = onPaste,
                tone = InfinityPillTone.PRIMARY,
                enabled = canPaste && !busy,
                icon = Icons.Rounded.ContentPaste,
                modifier = Modifier.testTag("directory_paste"),
            )
        }
    }
}

@Composable
internal fun DirectoryOutcomeLine(outcome: DirectoryOpOutcome) {
    InfinityMetaLine(
        directoryOutcomeSegments(outcome),
        style = MaterialTheme.typography.bodySmall,
        color = if (outcome.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("directory_outcome"),
    )
}

@Composable
internal fun DirectoryDeleteDialog(count: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    InfinityConfirmDialog(
        onDismissRequest = onCancel,
        title = pluralStringResource(R.plurals.directory_delete_title, count, count),
        message = stringResource(R.string.directory_delete_message),
        confirmText = stringResource(R.string.directory_action_delete),
        onConfirm = onConfirm,
        onCancel = onCancel,
        isDestructive = true,
        confirmButtonModifier = Modifier.testTag("directory_delete_confirm"),
    )
}

/** 超出回收站容量的条目无法备份，只能永久删除或保留。 */
@Composable
internal fun DirectoryPermanentDeleteDialog(count: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    InfinityConfirmDialog(
        onDismissRequest = onCancel,
        title = pluralStringResource(R.plurals.directory_permanent_delete_title, count, count),
        message = stringResource(R.string.directory_permanent_delete_message),
        confirmText = stringResource(R.string.directory_permanent_delete_confirm),
        onConfirm = onConfirm,
        onCancel = onCancel,
        isDestructive = true,
        confirmButtonModifier = Modifier.testTag("directory_permanent_delete_confirm"),
    )
}

/** 目录工作区的回收站：列出本会话挂载回收区中的删除副本，可逐项恢复回原位置。 */
@Composable
internal fun DirectoryRecycleList(
    sessionId: String,
    manager: IExternalFolderManager,
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(sessionId) { mutableStateOf<List<ExternalRecycleItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(manager, sessionId, reload) {
        items = try {
            manager.recycleItems(sessionId).sortedByDescending { it.deletedAt }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            emptyList()
        }
    }
    val current = items
    Column(modifier.fillMaxSize().testTag("directory_recycle_list")) {
        if (failed) DirectoryOutcomeError(stringResource(R.string.mount_operation_error))
        when {
            current == null -> Unit
            current.isEmpty() -> FolderStatusMessage(
                text = stringResource(R.string.mount_recycle_empty),
                icon = Icons.Rounded.RestoreFromTrash,
            )
            else -> InfinitySettingsGroup(
                footer = stringResource(R.string.directory_recycle_footer),
                modifier = Modifier.padding(horizontal = InfinitySpacing.Small),
            ) {
                current.forEach { item ->
                    key(item.id) {
                        InfinitySettingsRowLayout(
                            title = item.originalPath,
                            subtitle = Formatter.formatShortFileSize(context, item.size),
                            subtitleIsIdentifier = false,
                            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                            trailingContent = {
                                IconButton(
                                    enabled = !busy,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    onClick = {
                                        busy = true
                                        failed = false
                                        scope.launch {
                                            try {
                                                manager.restore(sessionId, item.id)
                                                onRestored()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                failed = true
                                            } finally {
                                                busy = false
                                                reload++
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.RestoreFromTrash, contentDescription = stringResource(R.string.mount_restore))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryOutcomeError(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
)
