package com.promenar.infinity.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.foundation.layout.Arrangement
import com.promenar.infinity.ui.common.InfinityIdentifierText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.ui.common.FileIndexStatus
import com.promenar.infinity.ui.common.IndexStatusBadge
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun RecycleBinPanel(
    files: List<FileEntry>,
    operationState: RecycleOperationState,
    onRestoreFiles: (List<FileEntry>) -> Unit,
    onPermanentlyDeleteFiles: (List<FileEntry>) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onRetryOperation: () -> Unit,
    onClearOperationState: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
) {
    var showPermanentDeleteConfirm by remember { mutableStateOf<FileEntry?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    val operationRunning = operationState is RecycleOperationState.Running

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_LIST),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (operationState !is RecycleOperationState.Idle) {
            item(key = "operation-notice") {
                Box(Modifier.animateItem()) {
                    RecycleOperationNotice(
                        state = operationState,
                        onRetry = onRetryOperation,
                        onClear = onClearOperationState,
                    )
                }
            }
        }

        if (files.isEmpty()) {
            item(key = "empty-state") { Box(Modifier.animateItem()) { EmptyRecycleBinState() } }
        } else {
            item(key = "bulk-actions") {
                Box(Modifier.animateItem()) {
                    ActionBar(
                        itemCount = files.size,
                        enabled = !operationRunning,
                        onRestore = { onRestoreFiles(files) },
                        onPermanentDelete = { showEmptyConfirm = true },
                    )
                }
            }

            // 恢复或彻底删除后条目淡出，其余条目平滑补位。
            items(items = files, key = { it.uuid }) { file ->
                Column(Modifier.animateItem()) {
                    RecycleBinItem(
                        file = file,
                        enabled = !operationRunning,
                        nowMillis = nowMillis,
                        onRestore = { onRestoreFiles(listOf(file)) },
                        onPermanentDelete = { showPermanentDeleteConfirm = file },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            item(key = "cleanup-notice") {
                Text(
                    text = stringResource(R.string.recycle_bin_auto_cleanup),
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_CLEANUP_NOTICE)
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
        }
    }

    showPermanentDeleteConfirm?.let { target ->
        RecycleBinPermanentDeleteDialog(
            target = target,
            onConfirm = {
                onPermanentlyDeleteFiles(listOf(target))
                showPermanentDeleteConfirm = null
            },
            onDismiss = { showPermanentDeleteConfirm = null },
        )
    }

    if (showEmptyConfirm) {
        InfinityConfirmDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = stringResource(R.string.recycle_bin_empty_title),
            message = stringResource(R.string.recycle_bin_empty_message),
            confirmText = stringResource(R.string.recycle_bin_empty_confirm),
            onConfirm = {
                onEmptyRecycleBin()
                showEmptyConfirm = false
            },
            onCancel = { showEmptyConfirm = false },
            isDestructive = true,
            confirmButtonModifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_EMPTY_CONFIRM),
        )
    }
}

@Composable
internal fun RecycleBinPermanentDeleteDialog(
    target: FileEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.recycle_bin_permanent_delete_title),
        message = stringResource(R.string.recycle_bin_permanent_delete_message, target.name),
        confirmText = stringResource(R.string.recycle_bin_permanent_delete_title),
        onConfirm = onConfirm,
        onCancel = onDismiss,
        isDestructive = true,
        confirmButtonModifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_DELETE_CONFIRM),
    )
}

@Composable
private fun ActionBar(
    itemCount: Int,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        val stackActions = maxWidth < 480.dp || LocalDensity.current.fontScale >= 1.5f
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RestoreAllButton(enabled, onRestore, Modifier.fillMaxWidth())
                DeleteAllButton(itemCount, enabled, onPermanentDelete, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RestoreAllButton(enabled, onRestore, Modifier.weight(1f))
                DeleteAllButton(itemCount, enabled, onPermanentDelete, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RestoreAllButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_RESTORE_ALL),
    ) {
        Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.recycle_bin_restore_all), maxLines = 2)
    }
}

@Composable
private fun DeleteAllButton(
    itemCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_DELETE_ALL),
    ) {
        Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.recycle_bin_clear_count, itemCount), maxLines = 2)
    }
}

@Composable
private fun RecycleOperationNotice(
    state: RecycleOperationState,
    onRetry: () -> Unit,
    onClear: () -> Unit,
) {
    val message = recycleOperationMessage(state)
    val isRunning = state is RecycleOperationState.Running
    val hasFailure = state is RecycleOperationState.PartialFailure || state is RecycleOperationState.Failure
    val containerColor = if (hasFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (hasFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_OPERATION_NOTICE),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 进行中、失败、完成三种图标之间交叉淡入。
                AnimatedContent(
                    targetState = when {
                        isRunning -> 0
                        hasFailure -> 1
                        else -> 2
                    },
                    transitionSpec = { InfinityMotion.fadeThrough() },
                    label = "recycle_operation_icon",
                ) { phase ->
                    when (phase) {
                        0 -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).clearAndSetSemantics { },
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                        1 -> Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        else -> Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Text(
                    text = message,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_OPERATION_STATUS)
                        .clearAndSetSemantics {
                            liveRegion = if (hasFailure) LiveRegionMode.Assertive else LiveRegionMode.Polite
                            stateDescription = message
                        },
                    style = InfinityTypography.bodyMedium,
                    color = contentColor,
                )
            }

            AnimatedVisibility(visible = !isRunning, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                val dismissLabel = stringResource(R.string.recycle_bin_operation_dismiss)
                if (hasFailure) {
                    // 并排放不下时整组竖排，重试在上。
                    InfinityPillRow(modifier = Modifier.fillMaxWidth()) {
                        InfinityPillButton(
                            text = dismissLabel,
                            onClick = onClear,
                            modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_OPERATION_CLEAR),
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.recycle_bin_operation_retry),
                            onClick = onRetry,
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Refresh,
                            modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_OPERATION_RETRY),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        InfinityPillButton(
                            text = dismissLabel,
                            onClick = onClear,
                            modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_OPERATION_CLEAR),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun recycleOperationMessage(state: RecycleOperationState): String = when (state) {
    RecycleOperationState.Idle -> ""
    is RecycleOperationState.Running -> stringResource(
        when (state.operation) {
            RecycleOperation.Restore -> R.string.recycle_bin_status_restoring
            RecycleOperation.PermanentDelete -> R.string.recycle_bin_status_deleting
            RecycleOperation.Empty -> R.string.recycle_bin_status_emptying
        },
    )
    is RecycleOperationState.Success -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_success,
            state.processedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_success,
            state.processedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_success)
    }
    is RecycleOperationState.PartialFailure -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_partial,
            state.succeededItemUuids.size,
            state.failedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_partial,
            state.succeededItemUuids.size,
            state.failedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_failed)
    }
    is RecycleOperationState.Failure -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_failed,
            state.failedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_failed,
            state.failedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_failed)
    }
}

@Composable
private fun RecycleBinItem(
    file: FileEntry,
    enabled: Boolean,
    nowMillis: Long,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stack = maxWidth < 520.dp || LocalDensity.current.fontScale >= 1.5f
        if (stack) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FileIcon()
                    FileDetails(file, nowMillis, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    IndexStatusBadge(status = resolveIndexStatus(file))
                    Spacer(modifier = Modifier.weight(1f))
                    FileActions(file, enabled, onRestore, onPermanentDelete)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileIcon()
                FileDetails(file, nowMillis, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IndexStatusBadge(status = resolveIndexStatus(file))
                    FileActions(file, enabled, onRestore, onPermanentDelete)
                }
            }
        }
    }
}

@Composable
private fun FileIcon() {
    Icon(
        imageVector = Icons.Rounded.Description,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun FileDetails(file: FileEntry, nowMillis: Long, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfinityIdentifierText(
            text = file.name,
            style = InfinityTypography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        file.originalMaterializedPath?.let { path ->
            Text(
                text = stringResource(R.string.recycle_bin_original_path, path),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        file.recycledAt?.let { recycledAt ->
            Text(
                text = formatRecycledTime(recycledAt, nowMillis),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActions(
    file: FileEntry,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    val restoreLabel = stringResource(R.string.recycle_bin_restore_document, file.name)
    val deleteLabel = stringResource(R.string.recycle_bin_delete_document, file.name)
    val restoreSemantics = if (enabled) {
        Modifier.clearAndSetSemantics {
            role = Role.Button
            onClick(label = restoreLabel) { onRestore(); true }
        }
    } else {
        Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = restoreLabel
            disabled()
        }
    }
    val deleteSemantics = if (enabled) {
        Modifier.clearAndSetSemantics {
            role = Role.Button
            onClick(label = deleteLabel) { onPermanentDelete(); true }
        }
    } else {
        Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = deleteLabel
            disabled()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(
            modifier = Modifier.size(48.dp)
                .testTag(UiTags.resourceExplorerRecycleRestore(file.uuid))
                .then(restoreSemantics),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            ),
            onClick = onRestore,
        ) {
            Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        IconButton(
            modifier = Modifier.size(48.dp)
                .testTag(UiTags.resourceExplorerRecycleDelete(file.uuid))
                .then(deleteSemantics),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
            ),
            onClick = onPermanentDelete,
        ) {
            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptyRecycleBinState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_EMPTY)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.DeleteForever,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recycle_bin_empty_state),
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun resolveIndexStatus(file: FileEntry): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    return FileIndexStatus.INDEXED
}

@Composable
private fun formatRecycledTime(timestamp: Long, nowMillis: Long): String {
    val diff = (nowMillis - timestamp).coerceAtLeast(0L)
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("MMM d", locale).format(Date(timestamp))
    }
}
