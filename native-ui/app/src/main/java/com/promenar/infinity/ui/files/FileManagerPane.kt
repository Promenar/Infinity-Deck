package com.promenar.infinity.ui.files

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.ui.common.FolderStatusMessage
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.LoadingSize
import com.promenar.infinity.ui.files.state.FileManagerController
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.files.state.FileSearchState
import com.promenar.infinity.ui.files.state.FileViewMode
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/*
 * 全站统一资源管理器。组件只看自身宽度：小于 560dp 单列（点文件在组件内推入预览页），
 * 不小于 560dp 双栏（左列表、右预览）。不做三列，不做目录树。
 * 状态与动作全部经 FileManagerController，本文件只持有菜单、对话框与 Snackbar 等纯界面状态。
 */

/** 双栏布局的最小组件宽度。 */
val FileManagerDualPaneMinWidth = 560.dp
private val ListMinWidth = 280.dp
private val ListMaxWidth = 340.dp
private const val ListWidthFraction = 0.44f
private val FabClearance = 96.dp

/** 组件内部各部件共用的动作入口：控制器、宿主声明与需要 Context 的系统动作。 */
@Stable
internal class FileManagerUiActions(
    val controller: FileManagerController,
    val host: FileManagerHostSpec,
    val openDialog: (FileManagerDialog) -> Unit,
    val openWith: (FileItem) -> Unit,
    val share: (List<FileItem>) -> Unit,
    val importFromDevice: (() -> Unit)?,
    val pasteClipboard: () -> Unit,
)

/**
 * 资源管理器组件。宿主只提供 [controller] 与 [host]，组件按自身宽度在单列与双栏之间切换。
 *
 * ```
 * FileManagerPane(
 *     controller = viewModel.files,
 *     host = FileManagerHostSpec(
 *         leading = FileManagerLeading.Expand(onExpand = state::toggleLibraryExpanded),
 *         hostActions = listOf(addToLibraryAction),
 *         showPin = true,
 *     ),
 * )
 * ```
 */
@Composable
fun FileManagerPane(
    controller: FileManagerController,
    host: FileManagerHostSpec,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<FileManagerDialog?>(null) }

    val message: (String) -> Unit = remember(scope, snackbar) {
        { text ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
    }

    // 预览与截图环境没有 ActivityResultRegistry 时不注册系统选择器，“从设备导入”随之隐藏。
    val importLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) controller.importUris(uris.map(Uri::toString))
        }
    } else {
        null
    }

    val actions = remember(controller, host, importLauncher, context, resources, message) {
        suspend fun uriOf(item: FileItem): String? = try {
            controller.shareUri(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileOpException) {
            message(resources.getString(R.string.fm_notice_failed, resources.getString(R.string.fm_kind_share), resources.getString(opCodeReason(failure.code))))
            null
        } catch (_: Exception) {
            message(resources.getString(R.string.fm_share_failed))
            null
        }
        FileManagerUiActions(
            controller = controller,
            host = host,
            openDialog = { dialog = it },
            openWith = { item ->
                scope.launch {
                    val uri = uriOf(item) ?: return@launch
                    if (!launchOpenWith(context, uri, item.mimeType, resources.getString(R.string.fm_open_with))) {
                        message(resources.getString(R.string.fm_no_app))
                    }
                }
            },
            share = { items ->
                scope.launch {
                    val uris = items.filterNot { it.isDirectory }.mapNotNull { item -> uriOf(item)?.let { it to item.mimeType } }
                    if (uris.isNotEmpty() && !launchShare(context, uris, resources.getString(R.string.fm_share))) {
                        message(resources.getString(R.string.fm_no_app))
                    }
                }
            },
            importFromDevice = importLauncher?.let { launcher -> { launcher.launch(arrayOf("*/*")) } },
            pasteClipboard = {
                val text = readClipboardText(context)
                if (text.isNullOrBlank()) {
                    message(resources.getString(R.string.fm_clipboard_empty))
                } else {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
                    dialog = FileManagerDialog.PasteAsFile(text, resources.getString(R.string.fm_paste_default_name, stamp))
                }
            },
        )
    }

    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            val text = fileNoticeText(resources, notice)
            controller.consumeNotice()
            message(text)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(UiTags.FM_ROOT)) {
        val dual = maxWidth >= FileManagerDualPaneMinWidth
        val compactPreview = !dual && state.compactPreviewItem() != null

        BackHandler(
            enabled = state.targetPicker == null &&
                (state.selecting || state.search != null || compactPreview || (host.backNavigatesUp && state.canGoUp)),
        ) {
            when {
                state.selecting -> controller.clearSelection()
                state.search != null -> controller.closeSearch()
                compactPreview -> controller.closePreview()
                else -> controller.goUp()
            }
        }

        when {
            // 退出中的一页保留自己的状态快照：关闭预览时预览页不会先变成加载态，打开预览时列表也不会被清空。
            !dual -> AnimatedContent(
                targetState = state,
                contentKey = { it.compactPreviewItem() != null },
                transitionSpec = { hierarchicalTransition { if (it.compactPreviewItem() != null) 1 else 0 } },
                modifier = Modifier.fillMaxSize(),
                label = "fm_compact_preview",
            ) { page ->
                val item = page.compactPreviewItem()
                if (item != null) {
                    FileCompactPreviewPage(item, page, actions, Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize()) {
                        FileManagerTopBar(page, actions)
                        FileListColumn(page, actions, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            else -> {
                val listWidth = (maxWidth * ListWidthFraction).coerceIn(ListMinWidth, ListMaxWidth)
                Column(Modifier.fillMaxSize()) {
                    FileManagerTopBar(state, actions)
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        FileListColumn(state, actions, Modifier.width(listWidth).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilePreviewPane(state, actions, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Large),
        )
    }

    state.targetPicker?.let { picker -> FileTargetPickerSheet(picker, state.locations, actions) }
    FileManagerDialogHost(dialog, state, actions, onDismiss = { dialog = null })
}

/** 列表列：面包屑 + 排序、Git 状态条、列表或网格，右下角 ＋。 */
@Composable
private fun FileListColumn(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            if (state.search == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.Small, end = InfinitySpacing.XSmall),
                    verticalAlignment = Alignment.Top,
                ) {
                    FileBreadcrumbs(crumbs = state.fullCrumbs(), onGo = controller::goTo, modifier = Modifier.weight(1f))
                    FileSortButton(
                        sort = state.sort,
                        onKey = controller::setSortKey,
                        onToggleDirection = controller::toggleSortDirection,
                        onToggleFoldersFirst = controller::toggleFoldersFirst,
                    )
                }
                GitStrip(state, actions)
            }
            AnimatedContent(
                targetState = rememberSettledListing(state),
                contentKey = { it.listingKey() },
                transitionSpec = {
                    if (initialState.search != null || targetState.search != null ||
                        initialState.location?.id != targetState.location?.id
                    ) {
                        InfinityMotion.fadeThrough()
                    } else {
                        hierarchicalTransition { it.pathDepth() }
                    }
                },
                // 共享轴只滑动一小段，裁在列表区内，双栏时不压到分隔线与预览。
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                label = "fm_folder",
            ) { page ->
                FileListBody(page, actions, Modifier.fillMaxSize())
            }
        }
        if (!state.selecting && state.search == null && state.location != null && state.writable()) {
            FileManagerFab(actions, Modifier.align(Alignment.BottomEnd).padding(InfinitySpacing.Large))
        }
    }
}

/** 根目录的 Git 状态条：分支与改动数；宿主给出 onOpenGit 时可点。 */
@Composable
private fun GitStrip(state: FileManagerUiState, actions: FileManagerUiActions) {
    val branch = state.location?.gitLabel() ?: return
    if (state.path != "/") return
    val onOpen = actions.host.onOpenGit
    val colors = MaterialTheme.infinityDomainColors
    val changes = state.location?.gitChangeCount
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.successContainer,
        contentColor = colors.onSuccessContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.XSmall)
            .clip(RoundedCornerShape(14.dp))
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .testTag(UiTags.FM_GIT_STRIP),
    ) {
        Row(
            modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(horizontal = InfinitySpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(InfinitySpacing.Small))
            InfinityMetaLine(
                segments = listOfNotNull(
                    branch,
                    changes?.let { pluralStringResource(R.plurals.fm_git_changes, it, it) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onOpen != null) {
                Text(stringResource(R.string.fm_git_view), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FileListBody(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val search = state.search
    val loadError = state.loadError
    val phase = when {
        search != null -> FileListPhase.Search
        loadError != null && state.items.isEmpty() -> FileListPhase.Error
        state.loading && state.items.isEmpty() -> FileListPhase.Loading
        state.location == null -> FileListPhase.Unavailable
        state.viewMode == FileViewMode.GRID -> FileListPhase.Grid
        else -> FileListPhase.List
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = { InfinityMotion.fadeThrough() },
        modifier = modifier,
        label = "fm_list_phase",
    ) { current ->
        FileListPhaseContent(current, state, actions, Modifier.fillMaxSize())
    }
}

private enum class FileListPhase { Search, Error, Loading, Unavailable, Grid, List }

@Composable
private fun FileListPhaseContent(phase: FileListPhase, state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val search = state.search
    val loadError = state.loadError
    when {
        phase == FileListPhase.Search && search != null -> SearchResults(state, search, actions, modifier)
        phase == FileListPhase.Error && loadError != null -> Box(modifier.testTag(UiTags.FM_LOAD_ERROR)) {
            FolderStatusMessage(
                text = stringResource(opCodeReason(loadError)),
                error = true,
                icon = Icons.Rounded.ErrorOutline,
                action = { InfinityPillButton(text = stringResource(R.string.fm_retry), onClick = controller::refresh) },
            )
        }
        phase == FileListPhase.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            InfinityLoadingIndicator()
        }
        phase == FileListPhase.Unavailable -> LocationUnavailable(modifier)
        phase == FileListPhase.Grid -> FileGrid(state, actions, modifier)
        phase == FileListPhase.List -> FileList(state, actions, modifier)
        // 退出中的一态可能已拿到新状态（如搜索刚关闭），留空等它淡出。
        else -> Box(modifier)
    }
}

private fun FileManagerUiState.showUpEntry(): Boolean = canGoUp && !selecting

/** 单栏下占满整屏的文件预览；文件夹不进预览。 */
private fun FileManagerUiState.compactPreviewItem(): FileItem? = previewItem?.takeIf { !it.isDirectory }

/** 列表内容的身份：搜索结果整体算一页，其余按位置与路径区分。 */
private fun FileManagerUiState.listingKey(): String =
    if (search != null) "search" else "${location?.id}\u0000$path"

private fun FileManagerUiState.pathDepth(): Int = path.split('/').count { it.isNotEmpty() }

/**
 * 进入或返回文件夹时，新目录还在读取的这一小段里继续显示旧列表，读完后再整页滑入，
 * 免得滑进来的是一页加载指示再闪成列表；读取超过 [ListingSettleMillis] 才带着加载指示切换。
 */
@Composable
private fun rememberSettledListing(state: FileManagerUiState): FileManagerUiState {
    val shown = remember { ListingHolder(state) }
    val key = state.listingKey()
    val pending = key != shown.value.listingKey() && state.search == null &&
        state.loading && state.items.isEmpty() && state.loadError == null
    var timedOut by remember(key) { mutableStateOf(false) }
    if (pending) {
        LaunchedEffect(key) {
            kotlinx.coroutines.delay(ListingSettleMillis)
            timedOut = true
        }
    }
    val result = if (pending && !timedOut) shown.value else state
    SideEffect { shown.value = result }
    return result
}

private class ListingHolder(var value: FileManagerUiState)

private const val ListingSettleMillis = 200L

private fun FileManagerController.openItem(item: FileItem) {
    if (item.isDirectory) open(item) else showPreview(item)
}

@Composable
private fun FileList(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val highlighted = state.previewItem?.ref
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = FabClearance)) {
        if (state.showUpEntry()) {
            item(key = "..") { FileUpRow(parentTitle = state.parentFolderTitle(), onClick = controller::goUp) }
        }
        if (state.items.isEmpty()) {
            item(key = "empty") { EmptyFolder() }
        }
        items(state.items, key = { it.ref.listKey() }) { item ->
            FileRow(
                item = item,
                highlighted = item.ref == highlighted,
                selecting = state.selecting,
                checked = item.ref.path in state.selection,
                onClick = { controller.openItem(item) },
                onToggleSelection = { controller.toggleSelection(item) },
                menu = { dismiss -> FileItemMenu(item, state, actions, dismiss) },
            )
        }
    }
}

@Composable
private fun FileGrid(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val highlighted = state.previewItem?.ref
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = InfinitySpacing.Medium, end = InfinitySpacing.Medium, top = InfinitySpacing.XSmall, bottom = FabClearance),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        if (state.showUpEntry()) {
            item(key = "..") { FileUpTile(onClick = controller::goUp) }
        }
        if (state.items.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyFolder() }
        }
        items(state.items, key = { it.ref.listKey() }) { item ->
            FileTile(
                item = item,
                highlighted = item.ref == highlighted,
                selecting = state.selecting,
                checked = item.ref.path in state.selection,
                onClick = { controller.openItem(item) },
                onToggleSelection = { controller.toggleSelection(item) },
            )
        }
    }
}

/** 宿主范围内没有可用位置（例如 Linux 主目录尚未就绪）。位置列表刚开始加载时先显示加载指示，避免一闪而过。 */
@Composable
private fun LocationUnavailable(modifier: Modifier) {
    val settled by produceState(initialValue = false) {
        kotlinx.coroutines.delay(LocationSettleMillis)
        value = true
    }
    Box(modifier.testTag(UiTags.FM_LOCATION_UNAVAILABLE), contentAlignment = Alignment.Center) {
        if (settled) {
            FolderStatusMessage(text = stringResource(R.string.fm_location_unavailable), icon = Icons.Rounded.ErrorOutline)
        } else {
            InfinityLoadingIndicator()
        }
    }
}

private const val LocationSettleMillis = 600L

@Composable
private fun EmptyFolder() {
    FolderStatusMessage(text = stringResource(R.string.fm_empty_folder), modifier = Modifier.testTag(UiTags.FM_EMPTY))
}

/** 搜索结果：副文字为所在路径，点结果定位到所在文件夹（文件同时打开预览）。 */
@Composable
private fun SearchResults(state: FileManagerUiState, search: FileSearchState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val locationTitle = state.location?.title.orEmpty()
    when {
        search.text.isBlank() && search.types.isEmpty() && search.modifiedAfter == null -> Box(modifier) {
            FolderStatusMessage(text = stringResource(R.string.fm_search_hint), icon = Icons.Rounded.SearchOff)
        }
        search.running && search.hits.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            InfinityLoadingIndicator()
        }
        search.hits.isEmpty() -> Box(modifier.testTag(UiTags.FM_EMPTY)) {
            FolderStatusMessage(text = stringResource(R.string.fm_search_empty), icon = Icons.Rounded.SearchOff)
        }
        else -> LazyColumn(modifier = modifier, contentPadding = PaddingValues(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XLarge)) {
            if (search.running) {
                item(key = "running") {
                    Box(Modifier.fillMaxWidth().padding(InfinitySpacing.Small), contentAlignment = Alignment.Center) {
                        InfinityLoadingIndicator(size = LoadingSize.SMALL)
                    }
                }
            }
            items(search.hits, key = { it.item.ref.listKey() }) { hit ->
                FileRow(
                    item = hit.item,
                    highlighted = false,
                    selecting = false,
                    checked = false,
                    onClick = { controller.reveal(hit) },
                    onToggleSelection = {},
                    location = (listOf(locationTitle) + pathCrumbs(hit.parentPath).map { it.title }).joinToString(" › "),
                )
            }
        }
    }
}

/** 右下角 ＋：新建文件夹、新建文件、导入、粘贴剪贴板为文件。 */
@Composable
private fun FileManagerFab(actions: FileManagerUiActions, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        FloatingActionButton(
            onClick = { open = true },
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            modifier = Modifier.size(56.dp).testTag(UiTags.FM_FAB),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.fm_fab))
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuAction(Icons.Rounded.CreateNewFolder, R.string.fm_new_folder, UiTags.FM_FAB_NEW_FOLDER) {
                open = false
                actions.openDialog(FileManagerDialog.NewFolder)
            }
            MenuAction(Icons.AutoMirrored.Rounded.NoteAdd, R.string.fm_new_file, UiTags.FM_FAB_NEW_FILE) {
                open = false
                actions.openDialog(FileManagerDialog.NewTextFile)
            }
            actions.importFromDevice?.let { launch ->
                MenuAction(Icons.Rounded.UploadFile, R.string.fm_import, UiTags.FM_FAB_IMPORT) {
                    open = false
                    launch()
                }
            }
            MenuAction(Icons.Rounded.ContentPaste, R.string.fm_paste_as_file, UiTags.FM_FAB_PASTE) {
                open = false
                actions.pasteClipboard()
            }
        }
    }
}
