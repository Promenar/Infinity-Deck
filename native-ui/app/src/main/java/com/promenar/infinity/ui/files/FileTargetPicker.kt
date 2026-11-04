package com.promenar.infinity.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.files.state.FileTargetPickerState
import com.promenar.infinity.ui.files.state.FileTransferMode
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing

/*
 * “复制到 / 移动到”目标选择：位置切换换行排列，面包屑逐级可点，下面是当前目标的子文件夹。
 * 窄窗口为底部面板，宽窗口由 InfinityModalSheet 改为居中对话框。
 */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FileTargetPickerSheet(
    picker: FileTargetPickerState,
    locations: List<FileLocation>,
    actions: FileManagerUiActions,
) {
    val controller = actions.controller
    InfinityModalSheet(onDismissRequest = controller::dismissTargetPicker, height = InfinitySheetHeight.Content) {
        FileTargetPickerContent(
            picker = picker,
            locations = locations,
            onLocation = controller::pickerOpenLocation,
            onGo = controller::pickerGoTo,
            onNewFolder = { actions.openDialog(FileManagerDialog.PickerNewFolder) },
            onCancel = controller::dismissTargetPicker,
            onConfirm = controller::confirmTargetPicker,
            modifier = Modifier.padding(horizontal = InfinitySpacing.XLarge).padding(bottom = InfinitySpacing.XLarge),
        )
    }
}

/** 目标选择面板的内容本身，不含面板外壳；截图测试直接渲染它。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileTargetPickerContent(
    picker: FileTargetPickerState,
    locations: List<FileLocation>,
    onLocation: (String) -> Unit,
    onGo: (String) -> Unit,
    onNewFolder: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = picker.sources.size
    val targetLocation = locations.firstOrNull { it.id == picker.locationId }
    val writable = targetLocation?.capabilities?.writable ?: true
    Column(modifier.fillMaxWidth().testTag(UiTags.FM_TARGET_SHEET)) {
        Text(
            text = pluralStringResource(
                if (picker.mode == FileTransferMode.MOVE) R.plurals.fm_target_title_move else R.plurals.fm_target_title_copy,
                count,
                count,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(InfinitySpacing.Small))
        if (locations.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                locations.forEach { location ->
                    FilterChip(
                        selected = location.id == picker.locationId,
                        onClick = { if (location.id != picker.locationId) onLocation(location.id) },
                        label = { Text(location.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(locationKindIcon(location.kind), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        enabled = location.capabilities.writable,
                        modifier = Modifier.testTag(UiTags.fmTargetLocation(location.id)),
                    )
                }
            }
        }
        FileBreadcrumbs(
            crumbs = withRootCrumb(picker.crumbs, targetLocation?.title.orEmpty(), picker.path),
            onGo = onGo,
            tagPrefix = UiTags.FM_TARGET_CRUMB,
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 320.dp)) {
            when {
                picker.loading -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    InfinityLoadingIndicator()
                }
                picker.folders.isEmpty() -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.fm_target_no_folders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(picker.folders, key = { it.ref.listKey() }) { folder ->
                        TargetFolderRow(folder, onClick = { onGo(folder.ref.path) })
                    }
                }
            }
        }
        Spacer(Modifier.height(InfinitySpacing.Medium))
        InfinityPillRow {
            InfinityPillButton(
                text = stringResource(R.string.fm_new_folder),
                onClick = onNewFolder,
                enabled = writable && !picker.loading,
                icon = Icons.Rounded.CreateNewFolder,
                modifier = Modifier.testTag(UiTags.FM_TARGET_NEW_FOLDER),
            )
            InfinityPillButton(
                text = stringResource(R.string.fm_cancel),
                onClick = onCancel,
                modifier = Modifier.testTag(UiTags.FM_TARGET_CANCEL),
            )
            InfinityPillButton(
                text = stringResource(if (picker.mode == FileTransferMode.MOVE) R.string.fm_target_move_here else R.string.fm_target_copy_here),
                onClick = onConfirm,
                enabled = writable && !picker.loading,
                tone = InfinityPillTone.PRIMARY,
                modifier = Modifier.testTag(UiTags.FM_TARGET_CONFIRM),
            )
        }
    }
}

@Composable
private fun TargetFolderRow(folder: FileItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = InfinitySpacing.XSmall)
            .testTag(UiTags.fmTargetFolder(folder.ref.path)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeBadge(folder, size = 36.dp)
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
