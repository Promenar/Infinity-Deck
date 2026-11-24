package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.WorkspaceDeleteSummary
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.fullDisplayPath
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuHeader
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.files.FileDetailsDialog
import com.promenar.infinity.ui.files.FileNameDialog
import com.promenar.infinity.ui.files.formatFileSize
import com.promenar.infinity.ui.files.locationQuickDetailsRows
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.breakableIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 资料页位置三分类结果：内置固定入口、应用内工作区、外部路径。 */
internal data class CategorizedLibraryLocations(
    val builtin: List<FileLocation>,
    val appWorkspaces: List<FileLocation>,
    val externalPaths: List<FileLocation>,
)

/**
 * 将资料页位置严格分为三组（已固定快捷入口由独立的 `pins` 列表承载）：
 * - [CategorizedLibraryLocations.builtin]：存储空间、会话工作区、Linux 主目录（Linux 未就绪时数据源不产出）；
 * - [CategorizedLibraryLocations.appWorkspaces]：应用内项目工作区、保留的会话文件夹、分支 worktree；
 * - [CategorizedLibraryLocations.externalPaths]：手机共享存储工作区（KIND_SHARED）与外部授权挂载文件夹。
 */
internal fun categorizeLibraryLocations(locations: List<FileLocation>): CategorizedLibraryLocations {
    val builtin = mutableListOf<FileLocation>()
    val appWorkspaces = mutableListOf<FileLocation>()
    val externalPaths = mutableListOf<FileLocation>()
    locations.forEach { location ->
        when (location.storageKind) {
            FileLocationStorageKind.BUILTIN,
            FileLocationStorageKind.LINUX_HOME,
            -> builtin += location

            FileLocationStorageKind.APP_PROJECT,
            FileLocationStorageKind.RETAINED_SESSION,
            FileLocationStorageKind.BRANCH_WORKTREE,
            -> appWorkspaces += location

            FileLocationStorageKind.SHARED_STORAGE,
            FileLocationStorageKind.EXTERNAL_MOUNT,
            -> externalPaths += location
        }
    }
    return CategorizedLibraryLocations(builtin, appWorkspaces, externalPaths)
}

internal sealed interface LibraryLocationDialog {
    data class RenameWorkspace(val workspaceId: String, val currentName: String) : LibraryLocationDialog
    data class DeleteWorkspace(val summary: WorkspaceDeleteSummary, val onRemoved: () -> Unit) : LibraryLocationDialog
    data class RemoveMount(val mountId: String, val title: String, val displayPath: String?, val onRemoved: () -> Unit) : LibraryLocationDialog
    data class Details(val title: String, val location: FileLocation, val relativePath: String = "/") : LibraryLocationDialog
}

/** 资料页位置与快捷固定管理控制器：长按列表行与进入目录右上角菜单共用。 */
@Stable
internal class LibraryLocationActions(
    private val viewModel: LibraryViewModel,
    private val navigator: LibraryNavigator,
    private val scope: CoroutineScope,
) {
    var dialog by mutableStateOf<LibraryLocationDialog?>(null)
        private set

    fun dismiss() {
        dialog = null
    }

    fun openDetails(location: FileLocation, title: String = location.title, relativePath: String = "/") {
        dialog = LibraryLocationDialog.Details(title = title, location = location, relativePath = relativePath)
    }

    fun openRenameWorkspace(workspaceId: String, currentName: String) {
        dialog = LibraryLocationDialog.RenameWorkspace(workspaceId, currentName)
    }

    fun requestRemoveLocation(location: FileLocation) {
        val afterRemoved: () -> Unit = { leaveDeletedLocation(location.id) }
        val workspaceId = location.workspaceId
        if (workspaceId != null) {
            scope.launch {
                val summary = viewModel.inspectWorkspaceDelete(workspaceId) ?: return@launch
                if (summary.referenceSessions > 0 || summary.referenceAgents > 0) {
                    viewModel.deleteWorkspace(workspaceId, deleteProjectFiles = false)
                } else {
                    dialog = LibraryLocationDialog.DeleteWorkspace(summary, afterRemoved)
                }
            }
            return
        }
        val mountId = location.mountId ?: return
        dialog = LibraryLocationDialog.RemoveMount(mountId, location.title, location.displayPath, afterRemoved)
    }

    fun unpin(ref: FileRef) {
        viewModel.unpinFolder(ref)
    }

    fun confirmRename(workspaceId: String, newName: String) {
        dialog = null
        viewModel.renameWorkspace(workspaceId, newName)
    }

    fun confirmDeleteWorkspace(summary: WorkspaceDeleteSummary, onRemoved: () -> Unit) {
        dialog = null
        val deleteFiles = summary.kind == WorkspaceEntity.KIND_PROJECT
        viewModel.deleteWorkspace(summary.workspaceId, deleteProjectFiles = deleteFiles, onRemoved = onRemoved)
    }

    fun confirmRemoveMount(mountId: String, onRemoved: () -> Unit) {
        dialog = null
        viewModel.removeExternalMount(mountId, onRemoved = onRemoved)
    }

    private fun leaveDeletedLocation(locationId: String) {
        val current = navigator.stack.lastOrNull()?.let(LibraryPageKeys::parse)
        if (current is LibraryPage.Browse && current.ref.locationId == locationId) {
            if (!navigator.back()) navigator.openRoot(LibraryPageKeys.OVERVIEW)
        }
    }
}

@Composable
internal fun rememberLibraryLocationActions(
    viewModel: LibraryViewModel,
    navigator: LibraryNavigator,
): LibraryLocationActions {
    val scope = rememberCoroutineScope()
    return remember(viewModel, navigator, scope) { LibraryLocationActions(viewModel, navigator, scope) }
}

/** 一级位置或已固定项的长按菜单内容（复用全站 L3 黑曜石 InfinityDropdownMenu）。 */
@Composable
internal fun ColumnScope.LibraryLocationContextMenuItems(
    entryTitle: String,
    location: FileLocation?,
    pinRef: FileRef?,
    actions: LibraryLocationActions,
    dismiss: () -> Unit,
) {
    InfinityMenuHeader(entryTitle)
    if (pinRef != null) {
        InfinityMenuItem(
            text = { Text(stringResource(R.string.library_location_action_unpin)) },
            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                dismiss()
                actions.unpin(pinRef)
            },
        )
        if (location != null) {
            InfinityMenuItem(
                text = { Text(stringResource(R.string.library_location_action_details)) },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    dismiss()
                    actions.openDetails(location, title = entryTitle, relativePath = pinRef.path)
                },
            )
        }
        return
    }
    val target = location ?: return
    val workspaceId = target.workspaceId
    if (workspaceId != null) {
        InfinityMenuItem(
            text = { Text(stringResource(R.string.library_location_action_rename)) },
            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                dismiss()
                actions.openRenameWorkspace(workspaceId, target.title)
            },
        )
    }
    InfinityMenuItem(
        text = { Text(stringResource(R.string.library_location_action_details)) },
        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = {
            dismiss()
            actions.openDetails(target)
        },
    )
    val removable = workspaceId != null || target.mountId != null
    if (removable) {
        val deletesFiles = target.storageKind == FileLocationStorageKind.APP_PROJECT ||
            target.storageKind == FileLocationStorageKind.RETAINED_SESSION
        InfinityMenuDivider()
        InfinityMenuItem(
            text = {
                Text(
                    stringResource(
                        if (deletesFiles) R.string.library_location_action_delete_workspace
                        else R.string.library_location_action_remove_list,
                    ),
                )
            },
            leadingIcon = {
                Icon(
                    if (deletesFiles) Icons.Rounded.DeleteOutline else Icons.Rounded.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            destructive = true,
            onClick = {
                dismiss()
                actions.requestRemoveLocation(target)
            },
        )
    }
}

/** 渲染工作区重命名、一步确认删除/移出与完整路径详情对话框。 */
@Composable
internal fun LibraryLocationActionDialogHost(actions: LibraryLocationActions) {
    when (val active = actions.dialog) {
        null -> Unit
        is LibraryLocationDialog.RenameWorkspace -> FileNameDialog(
            title = stringResource(R.string.library_location_action_rename_workspace),
            initial = active.currentName,
            selectBaseName = false,
            confirmText = stringResource(R.string.fm_rename_confirm),
            onDismiss = actions::dismiss,
            onConfirm = { newName ->
                if (newName != active.currentName) actions.confirmRename(active.workspaceId, newName)
                else actions.dismiss()
            },
        )

        is LibraryLocationDialog.DeleteWorkspace -> {
            val summary = active.summary
            val deletesFiles = summary.kind == WorkspaceEntity.KIND_PROJECT
            val title = stringResource(
                if (deletesFiles) R.string.library_workspace_delete_title else R.string.library_workspace_unlink_title,
            )
            val message = if (deletesFiles) {
                if (summary.fileCount > 0) {
                    stringResource(
                        R.string.library_workspace_delete_message_files,
                        summary.displayName,
                        summary.fileCount,
                        formatFileSize(summary.totalBytes),
                    )
                } else {
                    stringResource(R.string.library_workspace_delete_message_empty, summary.displayName)
                }
            } else {
                stringResource(R.string.library_workspace_unlink_message, summary.displayName)
            }
            InfinityConfirmDialog(
                title = title,
                message = message,
                confirmText = stringResource(
                    if (deletesFiles) R.string.library_workspace_delete_confirm else R.string.library_workspace_unlink_confirm,
                ),
                cancelText = stringResource(R.string.fm_cancel),
                isDestructive = true,
                icon = if (deletesFiles) Icons.Rounded.WarningAmber else Icons.Rounded.LinkOff,
                onConfirm = { actions.confirmDeleteWorkspace(summary, active.onRemoved) },
                onCancel = actions::dismiss,
                content = summary.displayPath.takeIf { it.isNotBlank() }?.let { path ->
                    { LocationPathChip(path) }
                },
            )
        }

        is LibraryLocationDialog.RemoveMount -> InfinityConfirmDialog(
            title = stringResource(R.string.library_workspace_unlink_title),
            message = stringResource(R.string.library_workspace_unlink_message, active.title),
            confirmText = stringResource(R.string.library_workspace_unlink_confirm),
            cancelText = stringResource(R.string.fm_cancel),
            isDestructive = true,
            icon = Icons.Rounded.LinkOff,
            onConfirm = { actions.confirmRemoveMount(active.mountId, active.onRemoved) },
            onCancel = actions::dismiss,
            content = active.displayPath?.takeIf { it.isNotBlank() }?.let { path ->
                { LocationPathChip(path) }
            },
        )

        is LibraryLocationDialog.Details -> FileDetailsDialog(
            title = active.title,
            detailRows = locationQuickDetailsRows(active.location, active.relativePath),
            copyPath = active.location.fullDisplayPath(active.relativePath),
            onDismiss = actions::dismiss,
        )
    }
}

@Composable
private fun LocationPathChip(path: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        color = if (dark) InfinityObsidianDialogChip else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = path.breakableIdentifier(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}
