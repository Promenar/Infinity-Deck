package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.common.inspectorTopGap
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.promenar.infinity.ui.common.InfinityIdentifierText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityRetryButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ui.chat.components.WorkspaceFolderBrowser
import com.promenar.infinity.ui.chat.components.RecycleBinPanel
import com.promenar.infinity.ui.chat.components.ResourceExplorerViewModel
import com.promenar.infinity.ui.chat.components.ResourceExplorerWorkspaceMode
import com.promenar.infinity.ui.chat.components.DirectoryFileActions
import com.promenar.infinity.ui.chat.components.PrivateFileActions
import com.promenar.infinity.ui.chat.components.DirectoryRecycleList
import com.promenar.infinity.ui.chat.components.DirectoryWorkspaceBrowser
import com.promenar.infinity.ui.chat.components.ExternalFoldersPanel
import com.promenar.infinity.ui.chat.components.rememberWorkspaceMediaOpener
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityNavigationHeader
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.common.InfinityPageSearch
import com.promenar.infinity.ui.common.InfinityToolbarSearch
import com.promenar.infinity.ui.git.GitEntrySummary
import com.promenar.infinity.ui.git.GitWorkspaceEntry
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/**
 * 资源管理器二级页面：由 [com.promenar.infinity.ui.common.InfinityAdaptivePaneHost] 承载，窄窗口整页、宽窗口停靠侧栏。
 * Git 变更作为上一层压在本页之上打开，返回后本页的目录位置保持不变。
 */
@Composable
fun ResourceExplorerPage(
    /** 为空时（检视器根层）不画顶栏：页签已写明所在页，由收起按钮离开。 */
    onBack: (() -> Unit)?,
    onOpenGit: () -> Unit,
    /** Git 页关闭时递增；本页据此刷新 Git 入口的分支与改动数。 */
    gitRefreshKey: Int,
    sessionId: String,
    chatViewModel: ChatViewModel,
    /** 打开后定位到的工作区虚拟目录；不存在时定位到最近的已存在上级。 */
    initialFolderPath: String? = null,
    viewModel: ResourceExplorerViewModel = viewModel(
        factory = ResourceExplorerViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val externalFolderManager = (LocalContext.current.applicationContext as? InfinityApplication)?.externalFolderManager
    DisposableEffect(sessionId, viewModel) {
        viewModel.loadSession(sessionId)
        onDispose { viewModel.deactivateSession() }
    }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recycleBinCount by viewModel.recycleBinCount.collectAsStateWithLifecycle()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsStateWithLifecycle()
    val rootFiles by viewModel.rootFiles.collectAsStateWithLifecycle()
    val recycledFiles by viewModel.recycledFiles.collectAsStateWithLifecycle()
    val recycleOperationState by viewModel.recycleOperationState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val importItems by viewModel.importItems.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val gitEntry by viewModel.gitEntry.collectAsStateWithLifecycle()
    val workspaceMode by viewModel.workspaceMode.collectAsStateWithLifecycle()
    val directory by viewModel.directory.collectAsStateWithLifecycle()
    val privateFiles by viewModel.privateFiles.collectAsStateWithLifecycle()
    val privateFileActions = remember(viewModel) {
        if (!viewModel.privateFileOpsAvailable) null else PrivateFileActions(
            onToggleSelect = viewModel::togglePrivateSelection,
            onClearSelection = viewModel::clearPrivateSelection,
            onCut = viewModel::cutPrivateSelection,
            onPaste = viewModel::pastePrivateClipboard,
            onCancelClipboard = viewModel::clearPrivateClipboard,
            onDelete = viewModel::deletePrivateSelection,
            onRename = viewModel::renamePrivateEntry,
            onDismissOutcome = viewModel::dismissPrivateOutcome,
        )
    }
    var showRecycle by rememberSaveable(sessionId) { mutableStateOf(false) }
    var searchActive by rememberSaveable(sessionId) { mutableStateOf(searchQuery.isNotEmpty()) }
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    // 工作区在顶栏状态行切换；本页打开期间工作区变化时重新加载文件。会话尚未读出时为 null，不算变化。
    val boundWorkspace = chatState.session?.let { it.workspaceId.orEmpty() }
    val shownWorkspace = remember(sessionId) { arrayOfNulls<String>(1) }
    LaunchedEffect(boundWorkspace) {
        if (boundWorkspace == null) return@LaunchedEffect
        val previous = shownWorkspace[0]
        shownWorkspace[0] = boundWorkspace
        if (previous != null && previous != boundWorkspace) viewModel.retryLoadSession()
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    val initialGitRefreshKey = remember { gitRefreshKey }
    LaunchedEffect(gitRefreshKey) {
        if (gitRefreshKey != initialGitRefreshKey) viewModel.refreshGitEntry()
    }

    InfinityPageLayout(
        title = stringResource(R.string.resource_explorer_title),
        onBack = onBack,
        showTopBar = onBack != null,
        scrollable = false,
        bodyPadding = PaddingValues(
            start = InfinitySpacing.ScreenHorizontal,
            end = InfinitySpacing.ScreenHorizontal,
            top = if (onBack == null) inspectorTopGap() else 0.dp,
            bottom = InfinitySpacing.Large,
        ),
    ) {
        ResourceExplorerSheetContent(
            state = ResourceExplorerSheetState(
                showRecycle = showRecycle,
                searchQuery = searchQuery,
                searchActive = searchActive,
                recycleBinCount = recycleBinCount,
                workspaceReady = workspaceRootUuid != null,
                importItems = importItems,
                isImporting = isImporting,
                isLoading = isLoading,
                hasLoadError = loadError != null,
                gitEntry = gitEntry,
                workspaceMode = workspaceMode,
            ),
            actions = ResourceExplorerSheetActions(
                onOpenGitPanel = onOpenGit,
                onSearchQueryChanged = viewModel::updateSearchQuery,
                onSearchActiveChange = { searchActive = it },
                onImportFiles = {
                    filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray())
                },
                onRetryImport = viewModel::retryImport,
                onClearImportResults = viewModel::clearImportResults,
                onOpenRecycle = { showRecycle = true },
                onCloseRecycle = { showRecycle = false },
                onRetryLoad = viewModel::retryLoadSession,
            ),
            filesContent = {
                val directoryMode = workspaceMode as? ResourceExplorerWorkspaceMode.Directory
                if (directoryMode != null) {
                    // 目录工作区内的路径以目录根为 `/`，与 Agent 和媒体解析器看到的虚拟路径一致。
                    val openMedia = rememberWorkspaceMediaOpener(sessionId, directoryMode.displayName)
                    DirectoryWorkspaceBrowser(
                        rootTitle = directoryMode.displayName,
                        state = directory,
                        extraActions = { ResourceExplorerRecycleAction(count = null, onClick = { showRecycle = true }) },
                        onNavigate = viewModel::openDirectory,
                        onOpenFile = viewModel::openDirectoryFile,
                        onCloseFile = viewModel::closeDirectoryFile,
                        onRefresh = viewModel::refreshDirectory,
                        readFile = viewModel::readDirectoryFile,
                        onOpenMedia = openMedia?.let { open -> { entry -> open(entry.path) } },
                        fileActions = remember(viewModel) {
                            DirectoryFileActions(
                                onToggleSelect = viewModel::toggleDirectorySelection,
                                onClearSelection = viewModel::clearDirectorySelection,
                                onCopy = viewModel::copyDirectorySelection,
                                onPaste = viewModel::pasteDirectoryClipboard,
                                onCancelClipboard = viewModel::clearDirectoryClipboard,
                                onDelete = viewModel::deleteDirectorySelection,
                                onCreateFolder = viewModel::createDirectoryFolder,
                                onRename = viewModel::renameDirectoryEntry,
                                onDismissOutcome = viewModel::dismissDirectoryOutcome,
                                onDeletePermanently = viewModel::deleteTooLargePermanently,
                                onKeepTooLarge = viewModel::keepTooLarge,
                            )
                        },
                        rootHeader = {
                            // 目录工作区下 /mnt 挂载仍对 Agent 生效，其回收区也承接目录内被删除文件的备份。
                            externalFolderManager?.let { manager ->
                                ExternalFoldersPanel(
                                    sessionId = sessionId,
                                    workspaceRootUuid = null,
                                    manager = manager,
                                    onReindex = null,
                                )
                            }
                        },
                    )
                } else WorkspaceFolderBrowser(
                    workspaceRootUuid = workspaceRootUuid,
                    workspaceRepo = viewModel.workspaceRepo,
                    sessionId = sessionId,
                    externalFolderManager = externalFolderManager,
                    searchQuery = searchQuery,
                    rootFiles = rootFiles,
                    isLoading = isLoading,
                    hasLoadError = loadError != null,
                    onRetryLoad = viewModel::retryLoadSession,
                    onClearSearch = {
                        viewModel.updateSearchQuery("")
                        searchActive = false
                    },
                    initialFolderPath = initialFolderPath,
                    onImport = { filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) }
                        .takeIf { workspaceRootUuid != null && !isImporting },
                    fileOps = privateFiles,
                    fileActions = privateFileActions,
                )
            },
            recycleBinContent = {
                RecycleBinPanel(
                    files = recycledFiles,
                    operationState = recycleOperationState,
                    onRestoreFiles = viewModel::restoreRecycledFiles,
                    onPermanentlyDeleteFiles = viewModel::permanentlyDeleteRecycledFiles,
                    onEmptyRecycleBin = viewModel::emptyRecycleBin,
                    onRetryOperation = viewModel::retryFailedRecycleOperation,
                    onClearOperationState = viewModel::clearRecycleOperationState,
                )
            },
            directoryRecycleContent = {
                if (externalFolderManager != null && workspaceMode is ResourceExplorerWorkspaceMode.Directory) {
                    DirectoryRecycleList(sessionId, externalFolderManager, onRestored = viewModel::refreshDirectory)
                } else {
                    DirectoryRecycleInfo()
                }
            },
        )
    }
}

internal data class ResourceExplorerSheetState(
    /** 回收站是当前工作区的子页，从文件页工具栏进入。 */
    val showRecycle: Boolean = false,
    val searchQuery: String = "",
    /** 工具行是否展开为搜索框；有查询时保持展开。 */
    val searchActive: Boolean = false,
    val recycleBinCount: Int = 0,
    val workspaceReady: Boolean = false,
    val importItems: List<ShareImportItem> = emptyList(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
    /** 会话工作区是 Git 仓库时显示分支与改动数入口。 */
    val gitEntry: GitEntrySummary = GitEntrySummary.Hidden,
    /** 文件区展示的工作区；目录与不可用状态下停用搜索、导入与会话回收站。 */
    val workspaceMode: ResourceExplorerWorkspaceMode = ResourceExplorerWorkspaceMode.Private,
)

internal data class ResourceExplorerSheetActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onImportFiles: () -> Unit = {},
    val onRetryImport: (android.net.Uri) -> Unit = {},
    val onClearImportResults: () -> Unit = {},
    val onOpenRecycle: () -> Unit = {},
    val onCloseRecycle: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onOpenGitPanel: () -> Unit = {},
)

@Composable
internal fun ResourceExplorerSheetContent(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
    recycleBinContent: @Composable () -> Unit,
    directoryRecycleContent: @Composable () -> Unit = { DirectoryRecycleInfo() },
) {
    BackHandler(enabled = state.showRecycle, onBack = actions.onCloseRecycle)
    AnimatedContent(
        targetState = state.showRecycle,
        transitionSpec = { hierarchicalTransition { if (it) 1 else 0 } },
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_ROOT),
        label = "resource_explorer_page",
    ) { recycle ->
        if (recycle) {
            ResourceExplorerRecyclePage(state, actions, recycleBinContent, directoryRecycleContent)
        } else {
            ResourceExplorerFilesPage(state = state, actions = actions, filesContent = filesContent)
        }
    }
}

@Composable
private fun ResourceExplorerRecyclePage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    recycleBinContent: @Composable () -> Unit,
    directoryRecycleContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_PAGE)) {
        InfinityNavigationHeader(
            title = stringResource(R.string.resource_explorer_recycle_bin),
            onBack = actions.onCloseRecycle,
            backButtonTag = UiTags.RESOURCE_EXPLORER_RECYCLE_BACK,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small),
        )
        // 目录工作区说明、加载、失败与回收站列表之间交叉淡入。
        val recyclePhase = when {
            state.workspaceMode is ResourceExplorerWorkspaceMode.Directory ||
                state.workspaceMode is ResourceExplorerWorkspaceMode.Unavailable -> 0
            state.isLoading -> 1
            state.hasLoadError -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = recyclePhase,
            transitionSpec = { InfinityMotion.fadeThrough() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "resource_explorer_recycle_phase",
        ) { phase ->
            when (phase) {
                0 -> directoryRecycleContent()
                1 -> ResourceExplorerSessionState(isLoading = true)
                2 -> ResourceExplorerSessionState(
                    isLoading = false,
                    onRetry = actions.onRetryLoad,
                )
                else -> recycleBinContent()
            }
        }
    }
}

/** 工具栏中的回收站入口；[count] 为 null 时不显示数量。 */
@Composable
internal fun ResourceExplorerRecycleAction(count: Int?, onClick: () -> Unit) {
    val label = if (count != null && count > 0) {
        stringResource(R.string.resource_explorer_recycle_bin_count, count)
    } else {
        stringResource(R.string.resource_explorer_recycle_bin)
    }
    IconButton(onClick = onClick, modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_ENTRY)) {
        BadgedBox(badge = { if (count != null && count > 0) Badge { Text(count.toString()) } }) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = label)
        }
    }
}

@Composable
private fun ResourceExplorerFilesPage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
) {
    when (val mode = state.workspaceMode) {
        ResourceExplorerWorkspaceMode.Resolving -> ResourceExplorerSessionState(
            isLoading = state.isLoading,
            onRetry = actions.onRetryLoad.takeIf { state.hasLoadError },
        )
        ResourceExplorerWorkspaceMode.Private -> ResourceExplorerPrivateFilesPage(state, actions, filesContent)
        is ResourceExplorerWorkspaceMode.Directory -> Column(modifier = Modifier.fillMaxSize()) {
            ResourceExplorerGitEntry(state, actions, leadingGap = false)
            Spacer(modifier = Modifier.height(InfinitySpacing.Small))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                filesContent()
            }
        }
        is ResourceExplorerWorkspaceMode.Unavailable -> Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceUnavailableState(mode, onRetry = actions.onRetryLoad)
            ResourceExplorerGitEntry(state, actions)
        }
    }
}

@Composable
private fun ResourceExplorerGitEntry(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    /** 入口排在页面最顶端时不留前置间隔，顶距由页面统一给出。 */
    leadingGap: Boolean = true,
) {
    // 收起期间沿用最后一次可见的摘要，避免入口在动画中途变空。
    val shownEntry = remember { arrayOfNulls<GitEntrySummary>(1) }
    if (state.gitEntry != GitEntrySummary.Hidden) shownEntry[0] = state.gitEntry
    AnimatedVisibility(
        visible = state.gitEntry != GitEntrySummary.Hidden,
        enter = InfinityMotion.expandIn,
        exit = InfinityMotion.shrinkOut,
    ) {
        Column {
            if (leadingGap) Spacer(modifier = Modifier.height(InfinitySpacing.Small))
            shownEntry[0]?.let { GitWorkspaceEntry(summary = it, onClick = actions.onOpenGitPanel) }
        }
    }
}

@Composable
private fun WorkspaceUnavailableState(
    mode: ResourceExplorerWorkspaceMode.Unavailable,
    onRetry: () -> Unit,
) {
    val reason = stringResource(R.string.workspace_unavailable_reason, mode.reason)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RESOURCE_EXPLORER_WORKSPACE_UNAVAILABLE)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                stateDescription = reason
            }
            .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        // 顶栏状态行只显示名称与警示色，原因在这里写明；无障碍即时播报原因。
        Text(
            text = reason,
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.resource_explorer_unavailable_help),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfinityRetryButton(
            onClick = onRetry,
            modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
        )
    }
}

@Composable
private fun DirectoryRecycleInfo() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_RECYCLE_INFO)
            .padding(InfinitySpacing.XXLarge),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = stringResource(R.string.resource_explorer_directory_recycle_info),
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResourceExplorerPrivateFilesPage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ResourceExplorerToolbar(state, actions)

        val shownImports = remember { arrayOfNulls<List<ShareImportItem>>(1) }
        if (state.importItems.isNotEmpty()) shownImports[0] = state.importItems
        AnimatedVisibility(visible = state.importItems.isNotEmpty(), enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            Column {
                Spacer(modifier = Modifier.height(InfinitySpacing.Small))
                ImportStatusList(
                    items = shownImports[0].orEmpty(),
                    isImporting = state.isImporting,
                    onRetry = actions.onRetryImport,
                    onClear = actions.onClearImportResults,
                )
            }
        }

        ResourceExplorerGitEntry(state, actions)

        Spacer(modifier = Modifier.height(InfinitySpacing.Small))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            filesContent()
        }
    }
}

/** 文件工具行：标题与导入、回收站；点放大镜后整行换成搜索框。 */
@Composable
private fun ResourceExplorerToolbar(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    InfinityToolbarSearch(
        search = InfinityPageSearch(
            query = state.searchQuery,
            active = state.searchActive || state.searchQuery.isNotEmpty(),
            onQueryChange = actions.onSearchQueryChanged,
            onActiveChange = actions.onSearchActiveChange,
            placeholder = stringResource(R.string.resource_explorer_search_placeholder),
            fieldTag = UiTags.RESOURCE_EXPLORER_SEARCH,
        ),
        title = stringResource(R.string.resource_explorer_files_heading),
        openButtonTag = UiTags.RESOURCE_EXPLORER_SEARCH_OPEN,
        modifier = Modifier.padding(start = InfinitySpacing.Small),
    ) {
        ResourceExplorerImportAction(state, actions)
        ResourceExplorerRecycleAction(count = state.recycleBinCount, onClick = actions.onOpenRecycle)
    }
}

@Composable
private fun ResourceExplorerImportAction(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    IconButton(
        onClick = actions.onImportFiles,
        enabled = state.workspaceReady && !state.isImporting,
        modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_IMPORT),
    ) {
        if (state.isImporting) {
            val importing = stringResource(R.string.resource_explorer_importing)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = importing },
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Rounded.UploadFile, contentDescription = stringResource(R.string.resource_explorer_import_files))
        }
    }
}

@Composable
private fun ResourceExplorerSessionState(
    isLoading: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val message = stringResource(
        if (isLoading) R.string.shared_loading
        else R.string.resource_explorer_error_workspace_load_failed,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .semantics {
                liveRegion = if (isLoading) LiveRegionMode.Polite else LiveRegionMode.Assertive
                stateDescription = message
            }
            .padding(InfinitySpacing.XXLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
        }
        Text(
            text = message,
            style = InfinityTypography.bodyMedium,
            color = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (!isLoading && onRetry != null) {
            Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
            InfinityRetryButton(
                onClick = onRetry,
                modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
            )
        }
    }
}

@Composable
private fun ImportStatusList(
    items: List<ShareImportItem>,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.resource_explorer_import_status),
                    style = InfinityTypography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClear,
                    enabled = !isImporting,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.resource_explorer_clear_results))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    ImportStatusRow(
                        item = item,
                        isImporting = isImporting,
                        onRetry = onRetry,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ImportStatusRow(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val statusText = when (item.status) {
        ShareImportStatus.Pending -> stringResource(R.string.resource_explorer_status_pending)
        ShareImportStatus.Importing -> stringResource(R.string.resource_explorer_status_importing)
        ShareImportStatus.Created -> stringResource(R.string.resource_explorer_status_created)
        ShareImportStatus.Rejected -> stringResource(
            R.string.resource_explorer_status_rejected,
            rejectReasonLabel(item.reason),
        )
    }
    val statusColor = if (item.status == ShareImportStatus.Rejected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 行尾重试是 48dp 图标，任何宽度与字号下都与状态并排，不再整行堆叠。
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(
                imageVector = when (item.status) {
                    ShareImportStatus.Pending, ShareImportStatus.Importing -> Icons.Rounded.HourglassTop
                    ShareImportStatus.Created -> Icons.Rounded.CheckCircle
                    ShareImportStatus.Rejected -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                InfinityIdentifierText(
                    text = item.displayName,
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusText,
                    style = InfinityTypography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.testTag(
                        UiTags.resourceExplorerImportStatus(item.uri.toString()),
                    ),
                )
            }
        }
        if (shouldOfferImportRetry(item)) {
            ImportRetryAction(item, isImporting, onRetry)
        }
    }
}

@Composable
private fun ImportRetryAction(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val actionLabel = stringResource(R.string.resource_explorer_retry_document, item.displayName)
    IconButton(
        onClick = { onRetry(item.uri) },
        enabled = !isImporting,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        // 点击语义带上文档名，读屏能分清是哪一项的重试。
        modifier = Modifier
            .semantics { onClick(label = actionLabel) { if (!isImporting) onRetry(item.uri); !isImporting } }
            .testTag(UiTags.resourceExplorerImportRetry(item.uri.toString())),
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.shared_btn_retry))
    }
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason?): String = stringResource(
    when (reason) {
        ShareRejectReason.TargetRequired -> R.string.share_import_reason_target
        ShareRejectReason.UnsupportedMime -> R.string.share_import_reason_mime
        ShareRejectReason.MimeMismatch -> R.string.share_import_reason_mismatch
        ShareRejectReason.InvalidName -> R.string.share_import_reason_name
        ShareRejectReason.PermissionDenied -> R.string.share_import_reason_permission
        ShareRejectReason.EmptyFile -> R.string.share_import_reason_empty
        ShareRejectReason.ItemTooLarge -> R.string.share_import_reason_item_large
        ShareRejectReason.BatchTooLarge -> R.string.share_import_reason_batch_large
        ShareRejectReason.ReadFailed -> R.string.share_import_reason_read
        ShareRejectReason.WriteFailed -> R.string.share_import_reason_write
        ShareRejectReason.IndexScheduleFailed -> R.string.share_import_reason_index
        ShareRejectReason.LinuxUnavailable -> R.string.share_import_ext_reason_linux_unavailable
        ShareRejectReason.LinuxPathInvalid -> R.string.share_import_ext_reason_linux_path
        null -> R.string.resource_explorer_reason_unknown
    },
)

internal fun shouldOfferImportRetry(item: ShareImportItem): Boolean =
    item.status == ShareImportStatus.Rejected && item.reason?.retryable == true
