package com.promenar.infinity.ui.files

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuHeader
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.files.state.FileSearchState
import com.promenar.infinity.ui.files.state.FileTransferMode
import com.promenar.infinity.ui.files.state.FileViewMode
import com.promenar.infinity.ui.hub.HubExpandButton
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

/*
 * 资源管理器页头：浏览、选择模式上下文栏与搜索三种形态，同一时刻只显示一种。
 * 浏览页头最多 4 个图标（左侧按钮、搜索、更多），新功能只加菜单项。
 */

private val HeaderMinHeight = 64.dp
private val MenuIconSize = 20.dp

@Composable
internal fun FileManagerTopBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier = Modifier) {
    when {
        state.selecting -> FileSelectionBar(state, actions, modifier)
        state.search != null -> FileSearchBar(state, state.search, actions, modifier)
        else -> FileBrowseBar(state, actions, modifier)
    }
}

/** 页头左侧按钮；展开 / 还原沿用对话页的 [HubExpandButton]。 */
@Composable
internal fun FileManagerLeadingButton(leading: FileManagerLeading) {
    when (leading) {
        is FileManagerLeading.Expand -> HubExpandButton(expanded = false, onClick = leading.onExpand)
        is FileManagerLeading.Restore -> HubExpandButton(expanded = true, onClick = leading.onRestore)
        is FileManagerLeading.Close -> IconButton(onClick = leading.onClose, modifier = Modifier.testTag(UiTags.FM_LEADING)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_close))
        }
        is FileManagerLeading.Leave -> IconButton(onClick = leading.onLeave, modifier = Modifier.testTag(UiTags.FM_LEADING)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_back))
        }
        FileManagerLeading.None -> Spacer(Modifier.width(InfinitySpacing.Small))
    }
}

@Composable
private fun FileBrowseBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val host = actions.host
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeaderMinHeight)
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall)
            .testTag(UiTags.FM_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileManagerLeadingButton(host.leading)
        FileHeaderTitle(state, actions, Modifier.weight(1f))
        IconButton(onClick = actions.controller::startSearch, modifier = Modifier.testTag(UiTags.FM_SEARCH)) {
            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.fm_search))
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_MORE)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
            }
            InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                FolderMoreMenu(state, actions) { open = false }
            }
        }
    }
}

/** 标题与副标题；会话宿主可点标题区切换位置。 */
@Composable
private fun FileHeaderTitle(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val host = actions.host
    val title = host.title ?: state.currentFolderTitle()
    val switchable = host.allowLocationSwitch && state.locations.size > 1
    val locationTitle = state.location?.title.orEmpty()
    val freeBytes = state.location?.freeBytes
    val subtitle = when {
        switchable -> locationTitle
        state.path != "/" -> locationTitle
        freeBytes != null -> stringResource(R.string.fm_free_space, formatFileSize(freeBytes))
        else -> null
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (switchable) {
                        Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_switch_location)) { open = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = InfinitySpacing.Small)
                .testTag(UiTags.FM_TITLE),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (switchable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (switchable) {
                        Icon(
                            Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InfinityMenuHeader(stringResource(R.string.fm_locations_title))
            state.locations.forEach { location ->
                InfinityMenuItem(
                    text = { Text(location.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(locationKindIcon(location.kind), contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
                    selected = location.id == state.location?.id,
                    onClick = {
                        open = false
                        if (location.id != state.location?.id) actions.controller.openLocation(location.id)
                    },
                    modifier = Modifier.testTag(UiTags.fmLocation(location.id)),
                )
            }
        }
    }
}

/** 页头“更多”：新建、导入、选择、视图、隐藏文件、固定、文件夹详情与宿主的文件夹动作。 */
@Composable
private fun ColumnScope.FolderMoreMenu(state: FileManagerUiState, actions: FileManagerUiActions, dismiss: () -> Unit) {
    val controller = actions.controller
    val writable = state.writable()
    if (writable) {
        MenuAction(Icons.Rounded.CreateNewFolder, R.string.fm_new_folder, UiTags.FM_MENU_NEW_FOLDER) {
            dismiss()
            actions.openDialog(FileManagerDialog.NewFolder)
        }
        MenuAction(Icons.AutoMirrored.Rounded.NoteAdd, R.string.fm_new_text_file, UiTags.FM_MENU_NEW_FILE) {
            dismiss()
            actions.openDialog(FileManagerDialog.NewTextFile)
        }
        actions.importFromDevice?.let { launch ->
            MenuAction(Icons.Rounded.UploadFile, R.string.fm_import, UiTags.FM_MENU_IMPORT) {
                dismiss()
                launch()
            }
        }
        InfinityMenuDivider()
    }
    InfinityMenuItem(
        text = { Text(stringResource(R.string.fm_select)) },
        leadingIcon = { Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
        enabled = state.items.isNotEmpty(),
        onClick = {
            dismiss()
            val first = state.items.firstOrNull { !it.isDirectory } ?: state.items.firstOrNull()
            if (first != null) controller.toggleSelection(first)
        },
        modifier = Modifier.testTag(UiTags.FM_MENU_SELECT),
    )
    val grid = state.viewMode == FileViewMode.GRID
    MenuAction(
        if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
        if (grid) R.string.fm_view_list else R.string.fm_view_grid,
        UiTags.FM_MENU_VIEW,
    ) {
        dismiss()
        controller.setViewMode(if (grid) FileViewMode.LIST else FileViewMode.GRID)
    }
    MenuAction(
        if (state.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
        if (state.showHidden) R.string.fm_hide_hidden else R.string.fm_show_hidden,
        UiTags.FM_MENU_HIDDEN,
    ) {
        dismiss()
        controller.toggleShowHidden()
    }
    InfinityMenuDivider()
    if (actions.host.showPin) {
        MenuAction(Icons.Rounded.PushPin, if (state.pinned) R.string.fm_unpin else R.string.fm_pin, UiTags.FM_MENU_PIN) {
            dismiss()
            controller.togglePinCurrentFolder()
        }
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_folder_details, UiTags.FM_MENU_FOLDER_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.FolderDetails)
    }
    val locationId = state.location?.id
    if (locationId != null) {
        actions.host.folderActions.forEach { action ->
            InfinityMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
                onClick = {
                    dismiss()
                    action.onInvoke(locationId, state.path)
                },
                modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
            )
        }
    }
}

@Composable
internal fun MenuAction(
    icon: ImageVector,
    @StringRes label: Int,
    tag: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    InfinityMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
        enabled = enabled,
        destructive = destructive,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** 行尾 ⋮ 与预览页 ⋮ 共用的条目菜单。 */
@Composable
internal fun ColumnScope.FileItemMenu(item: FileItem, state: FileManagerUiState, actions: FileManagerUiActions, dismiss: () -> Unit) {
    val controller = actions.controller
    val writable = state.writable()
    InfinityMenuHeader(item.name)
    if (item.isDirectory) {
        MenuAction(Icons.Rounded.FolderOpen, R.string.fm_open, UiTags.FM_ITEM_OPEN) {
            dismiss()
            controller.open(item)
        }
    } else {
        MenuAction(Icons.AutoMirrored.Rounded.OpenInNew, R.string.fm_open_with, UiTags.FM_ITEM_OPEN) {
            dismiss()
            actions.openWith(item)
        }
    }
    if (writable) {
        MenuAction(Icons.Rounded.DriveFileRenameOutline, R.string.fm_rename, UiTags.FM_ITEM_RENAME) {
            dismiss()
            actions.openDialog(FileManagerDialog.Rename(item))
        }
    }
    MenuAction(Icons.Rounded.ContentCopy, R.string.fm_copy_to, UiTags.FM_ITEM_COPY) {
        dismiss()
        controller.openTargetPicker(FileTransferMode.COPY, listOf(item))
    }
    if (writable) {
        MenuAction(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.fm_move_to, UiTags.FM_ITEM_MOVE) {
            dismiss()
            controller.openTargetPicker(FileTransferMode.MOVE, listOf(item))
        }
    }
    if (!item.isDirectory) {
        MenuAction(Icons.Rounded.Share, R.string.fm_share, UiTags.FM_ITEM_SHARE) {
            dismiss()
            actions.share(listOf(item))
        }
    }
    actions.host.actionsFor(listOf(item)).forEach { action ->
        InfinityMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
            onClick = {
                dismiss()
                action.onInvoke(listOf(item))
            },
            modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
        )
    }
    if (item.isZip() && writable && state.location?.capabilities?.archive != false) {
        MenuAction(Icons.Rounded.FolderZip, R.string.fm_extract, UiTags.FM_ITEM_EXTRACT) {
            dismiss()
            controller.extract(item)
        }
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_details, UiTags.FM_ITEM_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.Details(listOf(item)))
    }
    if (state.deletable()) {
        InfinityMenuDivider()
        MenuAction(Icons.Rounded.DeleteOutline, R.string.fm_delete, UiTags.FM_ITEM_DELETE, destructive = true) {
            dismiss()
            actions.openDialog(FileManagerDialog.Delete(listOf(item), fromSelection = false))
        }
    }
}

/** 选择模式的上下文栏：关闭、已选 N、复制到、移动到、分享、删除与更多。 */
@Composable
private fun FileSelectionBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val selected = state.selectedItems()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = InfinitySpacing.XSmall)
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.primaryContainer)
            .heightIn(min = 56.dp)
            .padding(horizontal = InfinitySpacing.XSmall)
            .testTag(UiTags.FM_SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onPrimaryContainer) {
            IconButton(onClick = controller::clearSelection, modifier = Modifier.testTag(UiTags.FM_SELECTION_CLOSE)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_selection_close))
            }
            Text(
                text = pluralStringResource(R.plurals.fm_selected_count, state.selection.size, state.selection.size),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.XSmall),
            )
            IconButton(
                onClick = { controller.openTargetPicker(FileTransferMode.COPY) },
                modifier = Modifier.testTag(UiTags.FM_SELECTION_COPY),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.fm_copy_to))
            }
            if (state.writable()) {
                IconButton(
                    onClick = { controller.openTargetPicker(FileTransferMode.MOVE) },
                    modifier = Modifier.testTag(UiTags.FM_SELECTION_MOVE),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = stringResource(R.string.fm_move_to))
                }
            }
            IconButton(
                onClick = { actions.share(selected.filterNot { it.isDirectory }) },
                enabled = selected.any { !it.isDirectory },
                modifier = Modifier.testTag(UiTags.FM_SELECTION_SHARE),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.fm_share))
            }
            if (state.deletable()) {
                IconButton(
                    onClick = { actions.openDialog(FileManagerDialog.Delete(selected, fromSelection = true)) },
                    modifier = Modifier.testTag(UiTags.FM_SELECTION_DELETE),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.fm_delete))
                }
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_SELECTION_MORE)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
                }
                InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SelectionMoreMenu(state, selected, actions) { open = false }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SelectionMoreMenu(
    state: FileManagerUiState,
    selected: List<FileItem>,
    actions: FileManagerUiActions,
    dismiss: () -> Unit,
) {
    val controller = actions.controller
    val writable = state.writable()
    MenuAction(Icons.Rounded.SelectAll, R.string.fm_select_all, UiTags.FM_SELECTION_ALL) {
        dismiss()
        controller.selectAll()
    }
    if (writable) {
        MenuAction(Icons.Rounded.DriveFileRenameOutline, R.string.fm_rename, UiTags.FM_ITEM_RENAME, enabled = selected.size == 1) {
            dismiss()
            selected.singleOrNull()?.let { actions.openDialog(FileManagerDialog.Rename(it)) }
        }
        if (state.location?.capabilities?.archive != false) {
            MenuAction(Icons.Rounded.FolderZip, R.string.fm_compress, UiTags.FM_SELECTION_COMPRESS) {
                dismiss()
                actions.openDialog(FileManagerDialog.Compress(selected, fromSelection = true))
            }
        }
    }
    actions.host.actionsFor(selected).forEach { action ->
        InfinityMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
            onClick = {
                dismiss()
                action.onInvoke(selected)
            },
            modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
        )
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_details, UiTags.FM_ITEM_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.Details(selected))
    }
}

/** 搜索页头：返回 + 自动聚焦的输入框，下方是范围、类型与修改时间筛选。 */
@Composable
private fun FileSearchBar(state: FileManagerUiState, search: FileSearchState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    var text by rememberSaveable { mutableStateOf(search.text) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(modifier.fillMaxWidth().testTag(UiTags.FM_SEARCH_BAR)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HeaderMinHeight)
                .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::closeSearch, modifier = Modifier.testTag(UiTags.FM_SEARCH_CLOSE)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_search_close))
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(end = InfinitySpacing.XSmall),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(start = InfinitySpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(InfinitySpacing.Small))
                    val placeholder = stringResource(R.string.fm_search_placeholder, state.location?.title.orEmpty())
                    BasicTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            controller.updateSearch { it.copy(text = value) }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface).forInput(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag(UiTags.FM_SEARCH_FIELD),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (text.isNotEmpty()) {
                        IconButton(onClick = {
                            text = ""
                            controller.updateSearch { it.copy(text = "") }
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_search_clear))
                        }
                    } else {
                        Spacer(Modifier.width(InfinitySpacing.Medium))
                    }
                }
            }
        }
        FileSearchFilters(search, actions)
    }
}

/** 修改时间筛选档位：不限、24 小时内、7 天内、30 天内。 */
private val ModifiedBuckets = listOf(
    R.string.fm_modified_any to null,
    R.string.fm_modified_day to 1,
    R.string.fm_modified_week to 7,
    R.string.fm_modified_month to 30,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileSearchFilters(search: FileSearchState, actions: FileManagerUiActions) {
    val controller = actions.controller
    var typeOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var bucket by rememberSaveable { mutableIntStateOf(0) }
    val activeBucket = if (search.modifiedAfter == null) 0 else bucket.coerceIn(1, ModifiedBuckets.lastIndex)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        FilterChip(
            selected = !search.withinCurrentFolder,
            onClick = { controller.updateSearch { it.copy(withinCurrentFolder = false) } },
            label = { Text(stringResource(R.string.fm_search_scope_location), maxLines = 1) },
            modifier = Modifier.testTag(UiTags.FM_SEARCH_SCOPE_ALL),
        )
        FilterChip(
            selected = search.withinCurrentFolder,
            onClick = { controller.updateSearch { it.copy(withinCurrentFolder = true) } },
            label = { Text(stringResource(R.string.fm_search_scope_folder), maxLines = 1) },
            modifier = Modifier.testTag(UiTags.FM_SEARCH_SCOPE_FOLDER),
        )
        Box {
            FilterChip(
                selected = search.types.isNotEmpty(),
                onClick = { typeOpen = true },
                label = {
                    Text(
                        text = if (search.types.isEmpty()) {
                            stringResource(R.string.fm_search_type)
                        } else {
                            stringResource(R.string.fm_search_type_count, search.types.size)
                        },
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(UiTags.FM_SEARCH_TYPE),
            )
            InfinityDropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                FileTypeFilter.entries.forEach { filter ->
                    InfinityMenuItem(
                        text = { Text(stringResource(typeFilterLabel(filter))) },
                        selected = filter in search.types,
                        onClick = {
                            controller.updateSearch {
                                it.copy(types = if (filter in it.types) it.types - filter else it.types + filter)
                            }
                        },
                    )
                }
                if (search.types.isNotEmpty()) {
                    InfinityMenuDivider()
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.fm_search_type_clear)) },
                        onClick = {
                            typeOpen = false
                            controller.updateSearch { it.copy(types = emptySet()) }
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = activeBucket != 0,
                onClick = { timeOpen = true },
                label = {
                    Text(
                        text = stringResource(if (activeBucket == 0) R.string.fm_search_modified else ModifiedBuckets[activeBucket].first),
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(UiTags.FM_SEARCH_MODIFIED),
            )
            InfinityDropdownMenu(expanded = timeOpen, onDismissRequest = { timeOpen = false }) {
                ModifiedBuckets.forEachIndexed { index, (label, days) ->
                    InfinityMenuItem(
                        text = { Text(stringResource(label)) },
                        selected = index == activeBucket,
                        onClick = {
                            timeOpen = false
                            bucket = index
                            val after = days?.let { System.currentTimeMillis() - it * DayMillis }
                            controller.updateSearch { it.copy(modifiedAfter = after) }
                        },
                    )
                }
            }
        }
    }
}

private const val DayMillis = 24L * 60 * 60 * 1000

internal fun FileManagerUiState.writable(): Boolean = location?.capabilities?.writable ?: true

/** 只有可写且支持删除的位置才提供删除操作。 */
internal fun FileManagerUiState.deletable(): Boolean =
    writable() && (location?.capabilities?.deletable ?: true)

internal fun FileManagerUiState.selectedItems(): List<FileItem> = items.filter { it.ref.path in selection }
