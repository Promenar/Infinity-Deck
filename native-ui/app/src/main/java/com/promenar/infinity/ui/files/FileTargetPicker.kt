package com.promenar.infinity.ui.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.hierarchicalTransition
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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

/** “会话工作区”汇总位置：只读，条目是各会话的根目录。 */
internal const val SESSIONS_LOCATION_ID = "sessions"

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
            onOpenFolder = controller::pickerOpenFolder,
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
    onOpenFolder: (FileItem) -> Unit = { onGo(it.ref.path) },
) {
    val count = picker.sources.size
    val listed = locations.firstOrNull { it.id == picker.locationId }
    val targetLocation = listed ?: picker.location
    // 从会话工作区进入的会话不在位置列表里，此时“会话工作区”保持选中。
    val selectedId = if (listed == null && picker.locationId.startsWith("session:")) SESSIONS_LOCATION_ID else picker.locationId
    val writable = targetLocation?.capabilities?.writable ?: true
    val haptics = LocalInfinityHaptics.current
    val go: (String) -> Unit = { path ->
        if (path != picker.path) haptics.perform(InfinityHapticEvent.SELECTION)
        onGo(path)
    }
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
        // 只列可作为目标的位置；会话工作区本身只读，但可以进入其中的会话作为目标。
        val targets = locations.filter { it.capabilities.writable || it.id == SESSIONS_LOCATION_ID || it.id == selectedId }
        if (targets.size > 1) {
            InfinitySegmentedControl(
                segments = targets.map { location ->
                    InfinitySegment(
                        value = location.id,
                        label = location.title,
                        tag = UiTags.fmTargetLocation(location.id),
                        leading = { Icon(locationKindIcon(location.kind), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                },
                selected = selectedId,
                onSelect = { id -> if (id != picker.locationId) onLocation(id) },
                compact = true,
            )
        }
        FileBreadcrumbs(
            crumbs = withRootCrumb(picker.crumbs, targetLocation?.title.orEmpty(), picker.path),
            onGo = go,
            tagPrefix = UiTags.FM_TARGET_CRUMB,
        )
        // 列表区固定高度：进出文件夹、加载与空文件夹之间切换时面板不随内容伸缩。
        AnimatedContent(
            targetState = rememberSettledPicker(picker),
            contentKey = { it.listingKey() },
            transitionSpec = {
                if (initialState.locationId != targetState.locationId) {
                    InfinityMotion.fadeThrough()
                } else {
                    hierarchicalTransition { it.crumbs.size }
                }
            },
            modifier = Modifier.fillMaxWidth().height(TargetListHeight).clipToBounds(),
            label = "fm_target_folder",
        ) { page ->
            Crossfade(
                targetState = when {
                    page.loading -> TargetListPhase.Loading
                    page.folders.isEmpty() -> TargetListPhase.Empty
                    else -> TargetListPhase.List
                },
                label = "fm_target_phase",
            ) { phase ->
                when (phase) {
                    TargetListPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InfinityLoadingIndicator()
                    }
                    TargetListPhase.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.fm_target_no_folders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TargetListPhase.List -> LazyColumn(Modifier.fillMaxSize()) {
                        items(page.folders, key = { it.ref.listKey() }) { folder ->
                            TargetFolderRow(folder, onClick = {
                                haptics.perform(InfinityHapticEvent.SELECTION)
                                onOpenFolder(folder)
                            })
                        }
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
                onClick = {
                    haptics.perform(InfinityHapticEvent.CONFIRM)
                    onConfirm()
                },
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
            // 会话工作区汇总根的条目路径都是 "/"，以位置区分。
            .testTag(UiTags.fmTargetFolder(if (folder.ref.path == "/") folder.ref.locationId else folder.ref.path)),
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

private enum class TargetListPhase { Loading, Empty, List }

private fun FileTargetPickerState.listingKey(): String = "$locationId\u0000$path"

/** 目标面板子文件夹列表的固定高度，约五行。 */
private val TargetListHeight = 280.dp

/**
 * 进入或返回文件夹时，新目录读取的这一小段里继续显示旧列表，读完再整页滑入，
 * 免得滑进来的是加载指示再闪成列表；读取超过 [TargetSettleMillis] 才带着加载指示切换。
 */
@Composable
private fun rememberSettledPicker(picker: FileTargetPickerState): FileTargetPickerState {
    val shown = remember { PickerHolder(picker) }
    val key = picker.listingKey()
    val pending = key != shown.value.listingKey() && picker.loading && picker.folders.isEmpty()
    var timedOut by remember(key) { mutableStateOf(false) }
    if (pending) {
        LaunchedEffect(key) {
            delay(TargetSettleMillis)
            timedOut = true
        }
    }
    val result = if (pending && !timedOut) shown.value else picker
    SideEffect { shown.value = result }
    return result
}

private class PickerHolder(var value: FileTargetPickerState)

private const val TargetSettleMillis = 200L
