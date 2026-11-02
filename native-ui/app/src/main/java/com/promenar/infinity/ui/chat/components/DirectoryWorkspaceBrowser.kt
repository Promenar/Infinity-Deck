package com.promenar.infinity.ui.chat.components

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.ui.common.FolderBreadcrumbsBar
import com.promenar.infinity.ui.common.FolderCrumb
import com.promenar.infinity.ui.common.FolderEntriesList
import com.promenar.infinity.ui.common.InfinityNavigationHeader
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.workspace.WorkspaceNameDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.breakableIdentifier

/** 由规范路径生成面包屑；crumb id 即该级目录的路径。 */
internal fun directoryCrumbs(path: String): List<FolderCrumb> {
    val segments = path.split('/').filter(String::isNotEmpty)
    return segments.mapIndexed { index, name -> FolderCrumb("/" + segments.take(index + 1).joinToString("/"), name) }
}

/**
 * 目录工作区的穿透式浏览：每次一级，面包屑以工作区名为根，系统返回先关闭文件再回到上级。
 * 图片、视频、音频交给 [onOpenMedia] 在会话内查看或播放；其它文件只做有界文本预览，或经私有只读副本交给其它应用打开。
 * 提供 [fileActions] 时可长按多选后复制、移动、重命名、删除，并新建文件夹。
 */
@Composable
internal fun DirectoryWorkspaceBrowser(
    rootTitle: String,
    state: DirectoryBrowserState,
    onNavigate: (String) -> Unit,
    onOpenFile: (ExternalFolderEntry) -> Unit,
    onCloseFile: () -> Unit,
    onRefresh: () -> Unit,
    readFile: suspend (path: String, maxBytes: Long) -> ByteArray,
    modifier: Modifier = Modifier,
    onOpenMedia: ((ExternalFolderEntry) -> Unit)? = null,
    rootHeader: @Composable () -> Unit = {},
    fileActions: DirectoryFileActions? = null,
    /** 追加在工具栏末尾的入口，例如回收站。 */
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    val selected = state.selectedFile
    val parent = parentDirectoryPath(state.path)
    val selecting = state.selection.isNotEmpty()
    BackHandler(enabled = selected != null || selecting || parent != null) {
        when {
            selected != null -> onCloseFile()
            selecting -> fileActions?.onClearSelection?.invoke()
            else -> parent?.let(onNavigate)
        }
    }
    AnimatedContent(
        targetState = state,
        // 目录之间的切换由列表自己做层级过渡，页头保持不动；打开与关闭文件整页推入推出。
        contentKey = { it.selectedFile?.path?.let { path -> "file:$path" } ?: "list" },
        transitionSpec = { hierarchicalTransition { if (it.selectedFile != null) 1 else 0 } },
        modifier = modifier,
        label = "directory_workspace_page",
    ) { current ->
        val file = current.selectedFile
        if (file != null) {
            DirectoryFileView(
                entry = file,
                preview = current.preview ?: ExternalTextPreview.Loading,
                previewError = current.previewError,
                onBack = onCloseFile,
                readFile = readFile,
                modifier = Modifier,
            )
        } else {
            DirectoryListView(
                rootTitle = rootTitle,
                state = current,
                onNavigate = onNavigate,
                onOpenFile = { entry ->
                    if (onOpenMedia != null && isPreviewableMediaName(entry.name)) onOpenMedia(entry) else onOpenFile(entry)
                },
                onRefresh = onRefresh,
                modifier = Modifier,
                rootHeader = rootHeader,
                fileActions = fileActions,
                extraActions = extraActions,
            )
        }
    }
}

@Composable
private fun DirectoryListView(
    rootTitle: String,
    state: DirectoryBrowserState,
    onNavigate: (String) -> Unit,
    onOpenFile: (ExternalFolderEntry) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    rootHeader: @Composable () -> Unit,
    fileActions: DirectoryFileActions?,
    extraActions: @Composable RowScope.() -> Unit,
) = Column(modifier.fillMaxSize()) {
    val crumbs = directoryCrumbs(state.path)
    var naming by remember { mutableStateOf<DirectoryNaming?>(null) }
    var namingError by remember { mutableStateOf<DirectoryFileOpCode?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    FolderBreadcrumbsBar(
        folderStack = crumbs,
        rootTitle = rootTitle,
        onNavigateBack = { onNavigate(crumbs.dropLast(1).lastOrNull()?.id ?: "/") },
        onNavigateToCrumb = { index -> onNavigate(if (index <= 0) "/" else crumbs[index - 1].id) },
        modifier = Modifier
            .padding(horizontal = InfinitySpacing.Small)
            .testTag(UiTags.RESOURCE_EXPLORER_BREADCRUMBS),
        backButtonTag = UiTags.RESOURCE_EXPLORER_FOLDER_BACK,
        showRootTitle = false,
        actions = {
            if (fileActions != null && state.selection.isEmpty()) {
                IconButton(
                    onClick = { namingError = null; naming = DirectoryNaming.Create },
                    enabled = !state.operating && state.error == null,
                    modifier = Modifier.testTag("directory_new_folder"),
                ) { Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.workspace_picker_new_folder)) }
            }
            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.resource_explorer_directory_refresh))
            }
            extraActions()
        },
    )
    // 根部：外部文件夹入口卡与下方列表卡同宽对齐，中间是“工作区 · 名称”分组标题。
    AnimatedVisibility(visible = crumbs.isEmpty(), enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        Column(Modifier.padding(horizontal = InfinitySpacing.Small)) {
            rootHeader()
            FilesPanelGroupLabel(stringResource(R.string.files_panel_group_workspace_named, rootTitle))
        }
    }
    val error = state.error
    // MediaStore 回收区改名条目只在此处隐藏显示，选择、复制等操作仍以完整目录为准。
    val trash = remember(state.entries) { partitionSystemTrash(state.entries) { it.name } }
    Column(
        Modifier
            // 有操作栏时占满剩余空间，操作栏贴底；列表卡按内容收拢，隐藏计数紧跟其下。
            .weight(1f, fill = fileActions != null)
            .padding(horizontal = InfinitySpacing.Small),
    ) {
        FolderEntriesList(
            path = state.path,
            depth = crumbs.size,
            entries = trash.visible,
            loading = state.isLoading,
            error = error?.let {
                it.reason?.let { reason -> stringResource(R.string.resource_explorer_directory_load_failed, reason) }
                    ?: stringResource(R.string.resource_explorer_directory_load_failed_generic)
            },
            emptyText = stringResource(R.string.resource_explorer_directory_empty),
            onEntryClick = { entry ->
                when {
                    fileActions != null && state.selection.isNotEmpty() -> fileActions.onToggleSelect(entry.path)
                    entry.isDirectory -> onNavigate(entry.path)
                    else -> onOpenFile(entry)
                }
            },
            selection = state.selection.takeIf { fileActions != null },
            onEntryLongClick = fileActions?.let { actions -> { entry -> actions.onToggleSelect(entry.path) } },
            onRetry = onRefresh,
            listTag = UiTags.RESOURCE_EXPLORER_DIRECTORY_LIST,
            statusTag = UiTags.RESOURCE_EXPLORER_DIRECTORY_STATUS,
            retryTag = UiTags.RESOURCE_EXPLORER_RETRY_LOAD,
            dividers = true,
            modifier = Modifier.weight(1f, fill = false),
        )
        HiddenSystemTrashFooter(trash.hiddenCount)
    }
    if (fileActions == null) return@Column
    AnimatedVisibility(visible = state.operating, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large))
    }
    state.outcome?.let { outcome ->
        DirectoryOutcomeLine(outcome)
        LaunchedEffect(outcome) {
            delay(OUTCOME_VISIBLE_MS)
            fileActions.onDismissOutcome()
        }
    }
    val clipboard = state.clipboard
    AnimatedContent(
        targetState = when {
            state.selection.isNotEmpty() -> 1
            clipboard != null -> 2
            else -> 0
        },
        transitionSpec = { InfinityMotion.fadeThrough() },
        label = "directory_action_bar",
    ) { bar ->
        when (bar) {
            1 -> DirectorySelectionBar(
                count = state.selection.size,
                busy = state.operating,
                onCopy = { fileActions.onCopy(false) },
                onMove = { fileActions.onCopy(true) },
                onRename = {
                    namingError = null
                    naming = state.entries.firstOrNull { it.path in state.selection }?.let { DirectoryNaming.Rename(it.path, it.name) }
                },
                onDelete = { confirmingDelete = true },
                onClear = fileActions.onClearSelection,
            )
            2 -> clipboard?.let {
                DirectoryPasteBar(
                    clipboard = it,
                    canPaste = it.canPasteInto(state.path),
                    busy = state.operating,
                    onPaste = fileActions.onPaste,
                    onCancel = fileActions.onCancelClipboard,
                )
            }
            else -> Spacer(Modifier.fillMaxWidth())
        }
    }
    naming?.let { current ->
        WorkspaceNameDialog(
            title = stringResource(if (current is DirectoryNaming.Rename) R.string.directory_action_rename else R.string.workspace_picker_new_folder),
            label = stringResource(R.string.workspace_picker_folder_name),
            confirmLabel = stringResource(if (current is DirectoryNaming.Rename) R.string.directory_action_rename else R.string.workspace_picker_create),
            initialValue = (current as? DirectoryNaming.Rename)?.name.orEmpty(),
            error = namingError?.let { directoryOpCodeText(it) },
            busy = state.operating,
            onValueChange = { namingError = null },
            onDismiss = { naming = null },
            onConfirm = { name ->
                val done: (DirectoryFileOpCode?) -> Unit = { code -> if (code == null) naming = null else namingError = code }
                when (current) {
                    DirectoryNaming.Create -> fileActions.onCreateFolder(name, done)
                    is DirectoryNaming.Rename -> if (name == current.name) naming = null else fileActions.onRename(current.path, name, done)
                }
            },
        )
    }
    if (confirmingDelete) {
        DirectoryDeleteDialog(
            count = state.selection.size,
            onConfirm = {
                confirmingDelete = false
                fileActions.onDelete()
            },
            onCancel = { confirmingDelete = false },
        )
    }
    if (state.permanentDeleteCandidates.isNotEmpty()) {
        DirectoryPermanentDeleteDialog(
            count = state.permanentDeleteCandidates.size,
            onConfirm = fileActions.onDeletePermanently,
            onCancel = fileActions.onKeepTooLarge,
        )
    }
}

private sealed interface DirectoryNaming {
    data object Create : DirectoryNaming
    data class Rename(val path: String, val name: String) : DirectoryNaming
}

/** 操作结果提示的停留时长，私有工作区文件页共用。 */
internal const val OUTCOME_VISIBLE_MS = 4_000L

@Composable
private fun DirectoryFileView(
    entry: ExternalFolderEntry,
    preview: ExternalTextPreview,
    previewError: String?,
    onBack: () -> Unit,
    readFile: suspend (path: String, maxBytes: Long) -> ByteArray,
    modifier: Modifier,
) = Column(modifier.fillMaxSize()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.mount_open_external)
    var opening by remember(entry.path) { mutableStateOf(false) }
    var openNotice by remember(entry.path) { mutableStateOf<Int?>(null) }
    val openExternal: () -> Unit = open@{
        if (entry.size > EXTERNAL_OPEN_BYTES) {
            openNotice = R.string.mount_open_limit
            return@open
        }
        opening = true
        openNotice = null
        scope.launch {
            try {
                val intent = prepareExternalFileOpen(context, entry.name) { maxBytes -> readFile(entry.path, maxBytes) }
                context.startActivity(
                    Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                openNotice = R.string.mount_open_failed
            } finally {
                opening = false
            }
        }
    }
    InfinityNavigationHeader(
        title = entry.name,
        onBack = onBack,
        backButtonTag = UiTags.RESOURCE_EXPLORER_FOLDER_BACK,
        modifier = Modifier.padding(horizontal = InfinitySpacing.Small),
        supporting = {
            InfinityMetaLine(
                segments = listOf(entry.path.breakableIdentifier(), Formatter.formatShortFileSize(context, entry.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        },
        actions = {
            // 用外部应用打开是文件页的唯一操作，放在页头行尾。
            IconButton(
                onClick = openExternal,
                enabled = !opening,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_OPEN_EXTERNAL),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = chooserTitle)
            }
        },
    )
    AnimatedVisibility(visible = opening, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    AnimatedVisibility(visible = openNotice != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        // 退出动画期间提示已清空，保留空文本让区域平滑收起。
        Text(
            openNotice?.let { stringResource(it) }.orEmpty(),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    FileTextPreviewPanel(
        preview = preview,
        failedText = stringResource(R.string.workspace_kb_preview_failed, previewError.orEmpty()),
        binaryText = stringResource(R.string.mount_preview_binary),
        tooLargeText = stringResource(R.string.workspace_kb_preview_too_large),
        previewTag = UiTags.RESOURCE_EXPLORER_DIRECTORY_PREVIEW,
        modifier = Modifier
            .weight(1f)
            .padding(top = InfinitySpacing.Medium),
    )
}
