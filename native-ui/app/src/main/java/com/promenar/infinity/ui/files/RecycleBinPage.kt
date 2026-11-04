package com.promenar.infinity.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.RecycledItem
import com.promenar.infinity.ui.common.FolderStatusMessage
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuHeader
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.files.state.RecycleBinController
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.launch

/*
 * 回收站页：汇总所有位置的删除项，可恢复、彻底删除与清空。彻底删除与清空都先确认。
 */

private const val DayMillis = 24L * 60 * 60 * 1000

private sealed interface RecycleDialog {
    data class Purge(val item: RecycledItem) : RecycleDialog
    data object EmptyAll : RecycleDialog
}

/**
 * 回收站页。
 * - [leading] 与资源管理器同义：内屏为展开 / 还原，外屏为返回；
 * - [locationTitle] 把位置 id 换成位置名，显示在原位置前；未知时只显示路径；
 * - [now] 计算剩余天数的当前时间，截图与测试可固定。
 */
@Composable
fun RecycleBinPage(
    controller: RecycleBinController,
    modifier: Modifier = Modifier,
    leading: FileManagerLeading = FileManagerLeading.None,
    locationTitle: (String) -> String? = { null },
    now: Long = System.currentTimeMillis(),
) {
    val state by controller.state.collectAsState()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<RecycleDialog?>(null) }

    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            val text = fileNoticeText(resources, notice)
            controller.consumeNotice()
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
    }

    Box(modifier.fillMaxSize().testTag(UiTags.RECYCLE_BIN)) {
        Column(Modifier.fillMaxSize()) {
            RecycleBinHeader(
                leading = leading,
                count = state.items.size,
                enabled = state.items.isNotEmpty() && !state.running,
                onRestoreAll = { controller.restore(state.items) },
                onEmptyAll = { dialog = RecycleDialog.EmptyAll },
            )
            when {
                state.items.isEmpty() && state.running -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    InfinityLoadingIndicator()
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).testTag(UiTags.RECYCLE_EMPTY)) {
                    FolderStatusMessage(text = stringResource(R.string.fm_recycle_empty), icon = Icons.Rounded.RestoreFromTrash)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = InfinitySpacing.XLarge),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        RecycledRow(
                            item = item,
                            location = locationTitle(item.locationId),
                            now = now,
                            enabled = !state.running,
                            onRestore = { controller.restore(listOf(item)) },
                            onPurge = { dialog = RecycleDialog.Purge(item) },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(InfinitySpacing.Large),
        )
    }

    when (val current = dialog) {
        null -> Unit
        is RecycleDialog.Purge -> InfinityConfirmDialog(
            title = stringResource(R.string.fm_recycle_purge_title),
            message = stringResource(R.string.fm_recycle_purge_message, current.item.name),
            confirmText = stringResource(R.string.fm_recycle_purge_confirm),
            cancelText = stringResource(R.string.fm_cancel),
            isDestructive = true,
            onConfirm = {
                dialog = null
                controller.purge(listOf(current.item))
            },
            onCancel = { dialog = null },
            confirmButtonModifier = Modifier.testTag(UiTags.FM_DIALOG_CONFIRM),
        )
        RecycleDialog.EmptyAll -> InfinityConfirmDialog(
            title = stringResource(R.string.fm_recycle_empty_all_title),
            message = pluralStringResource(R.plurals.fm_recycle_empty_all_message, state.items.size, state.items.size),
            confirmText = stringResource(R.string.fm_recycle_empty_all_confirm),
            cancelText = stringResource(R.string.fm_cancel),
            isDestructive = true,
            onConfirm = {
                dialog = null
                controller.emptyAll()
            },
            onCancel = { dialog = null },
            confirmButtonModifier = Modifier.testTag(UiTags.FM_DIALOG_CONFIRM),
        )
    }
}

@Composable
private fun RecycleBinHeader(
    leading: FileManagerLeading,
    count: Int,
    enabled: Boolean,
    onRestoreAll: () -> Unit,
    onEmptyAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileManagerLeadingButton(leading)
        Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
            Text(
                text = stringResource(R.string.fm_recycle_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfinityMetaLine(
                segments = listOf(
                    pluralStringResource(R.plurals.fm_item_count, count, count),
                    stringResource(R.string.fm_recycle_retention),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(UiTags.RECYCLE_MORE)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
            }
            InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MenuAction(Icons.Rounded.Restore, R.string.fm_recycle_restore_all, UiTags.RECYCLE_RESTORE_ALL) {
                    open = false
                    onRestoreAll()
                }
                InfinityMenuDivider()
                MenuAction(Icons.Rounded.DeleteSweep, R.string.fm_recycle_empty_all, UiTags.RECYCLE_EMPTY_ALL, destructive = true) {
                    open = false
                    onEmptyAll()
                }
            }
        }
    }
}

@Composable
private fun RecycledRow(
    item: RecycledItem,
    location: String?,
    now: Long,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val parent = item.originalPath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
    val origin = listOfNotNull(location, parent.takeIf { it != "/" }?.trim('/')?.replace("/", " › ")).joinToString(" › ")
        .ifEmpty { location ?: "/" }
    val remaining = item.expiresAt?.let { ((it - now).coerceAtLeast(0) + DayMillis - 1) / DayMillis }?.toInt()
    // 徽标复用资源管理器的类型判定。
    val badgeItem = FileItem(
        ref = FileRef(item.locationId, item.originalPath),
        name = item.name,
        isDirectory = item.isDirectory,
        sizeBytes = item.sizeBytes,
        lastModified = item.deletedAt,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.XSmall)
            .testTag(UiTags.recycleRow(item.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeBadge(badgeItem)
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Column(Modifier.weight(1f).padding(vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.fm_recycle_origin, origin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfinityMetaLine(
                segments = listOfNotNull(
                    stringResource(R.string.fm_recycle_deleted_at, formatFileTime(item.deletedAt)),
                    remaining?.let { pluralStringResource(R.plurals.fm_recycle_days_left, it, it) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        IconButton(onClick = onRestore, enabled = enabled, modifier = Modifier.testTag(UiTags.recycleRestore(item.id))) {
            Icon(
                Icons.Rounded.Restore,
                contentDescription = stringResource(R.string.fm_recycle_restore_item, item.name),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Box {
            IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(UiTags.recycleRowMenu(item.id))) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.fm_item_actions, item.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                InfinityMenuHeader(item.name)
                MenuAction(Icons.Rounded.Restore, R.string.fm_recycle_restore, UiTags.recycleRestore(item.id) + ":menu") {
                    open = false
                    onRestore()
                }
                InfinityMenuDivider()
                MenuAction(Icons.Rounded.DeleteForever, R.string.fm_recycle_purge, UiTags.recyclePurge(item.id), destructive = true) {
                    open = false
                    onPurge()
                }
            }
        }
    }
}
