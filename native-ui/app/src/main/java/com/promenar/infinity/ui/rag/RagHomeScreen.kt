package com.promenar.infinity.ui.rag

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.animation.animateContentSize

import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.InfinityMotion

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.chat.components.ExternalFoldersPanel
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.theme.forReading
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import com.promenar.infinity.data.repository.CompositeWorkspaceConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.domain.model.Folder
import com.promenar.infinity.domain.repository.MemoryVectorRecord
import com.promenar.infinity.ui.chat.components.FileBatchOperationResult
import com.promenar.infinity.ui.chat.components.FilesPanel
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.KgStatus
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.rag.components.IndexingProgressBar
import com.promenar.infinity.ui.rag.components.resolveIndexingProgressAccessibility
import com.promenar.infinity.ui.common.FolderBreadcrumbsBar
import com.promenar.infinity.ui.common.FolderCrumb
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import java.text.SimpleDateFormat
import com.promenar.infinity.ui.common.InfinityMenuItem

internal enum class PortalTab { DOCUMENTS, MEMORY, GRAPH }

internal fun ragIndexFallbackStatusResource(canRetryPendingIndex: Boolean): Int =
    if (canRetryPendingIndex) R.string.rag_index_retry_hint else R.string.rag_index_phase_unknown

internal fun shouldShowRagIndexActions(
    hasNotice: Boolean,
    canRetryPendingIndex: Boolean,
): Boolean = hasNotice || canRetryPendingIndex

internal enum class RagHomeIndexAction { RetryFailed, RetryPending, None }

internal fun resolveRagHomeIndexAction(
    noticeCode: String?,
    canRetryLastFailedIndex: Boolean,
    canRetryPendingIndex: Boolean,
): RagHomeIndexAction = when {
    noticeCode in setOf(IndexingNotice.CODE_FAILED, IndexingNotice.CODE_PARTIAL) &&
        canRetryLastFailedIndex -> RagHomeIndexAction.RetryFailed
    noticeCode != null -> RagHomeIndexAction.None
    canRetryPendingIndex -> RagHomeIndexAction.RetryPending
    else -> RagHomeIndexAction.None
}

internal fun shouldShowRagIndexSection(
    isIndexing: Boolean,
    hasNotice: Boolean,
    canRetryPendingIndex: Boolean,
): Boolean = isIndexing || hasNotice || canRetryPendingIndex

/**
 * 搜索结果不会呈现原文件列表中的选择项；进入非空搜索时立即清空旧选择，
 * 避免底部批量操作继续作用于当前不可见的文档。
 */
internal fun applyRagSearchSelectionPolicy(
    selectedIds: MutableList<String>,
    query: String,
) {
    if (query.isNotBlank()) selectedIds.clear()
}

internal data class RagHomeScreenState(
    val currentTab: PortalTab,
    val searchQuery: String,
    val searchState: RagSearchUiState = RagSearchUiState.Idle,
    val selectedIds: MutableList<String>,
    val workspaceRootUuid: String?,
    val folders: List<Folder>,
    val folderStats: Map<String, Int>,
    val stats: RagStats,
    val memoryVectors: List<MemoryVectorRecord>,
    val isIndexing: Boolean,
    val indexingProgress: Float,
    val indexingNotice: com.promenar.infinity.ui.common.status.UiStatusNotice? = null,
    val canRetryLastFailedIndex: Boolean,
    val isRetryingLastFailedIndex: Boolean,
    val indexingFileIds: Set<String>,
    val kgExtractionStates: Map<String, KgStatus>,
    val canRetryPendingRenameIndex: Boolean = false,
    val isRetryingPendingRenameIndex: Boolean = false,
)

internal data class RagHomeScreenActions(
    val onSearch: (String) -> Unit = {},
    val onChangeTab: (PortalTab) -> Unit = {},
    val onLoadMemory: () -> Unit = {},
    val onOpenConfig: () -> Unit = {},
    val onOpenGraph: () -> Unit = {},
    val onOpenFilePicker: () -> Unit = {},
    val onCreateFolder: (String) -> Unit = {},
    val onRetryLastFailedIndex: () -> Unit = {},
    val onRetryPendingRenameIndex: () -> Unit = {},
    val onDismissQueueError: () -> Unit = {},
    val onNavigateToDocEditor: (String, String) -> Unit = { _, _ -> },
    val onReindexFile: (String) -> Unit = {},
    val onReindexDocuments: (Collection<String>) -> Unit = {},
    val onDeleteDocuments: (Collection<String>, (Boolean, List<String>) -> Unit) -> Unit = { _, _ -> },
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onMoveFile: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val onMoveDocuments: (Collection<String>, String, (Boolean, List<String>) -> Unit) -> Unit = { _, _, _ -> },
    val onExtractKG: (String) -> Unit = {},
    val onCopyFile: (String) -> Unit = {},
    val onDeleteMemory: (String) -> Unit = {},
    val onCancelIndexing: () -> Unit = {},
)

private data class PendingDocumentDelete(
    val ids: List<String>,
    val completion: (FileBatchOperationResult) -> Unit,
)

/**
 * 将批量操作结果同步回选择态：成功项移除，失败项继续保留以便用户重试。
 * 移动与删除共用同一语义，避免任一失败项被错误清空。
 */
fun applyBatchSelectionResult(
    selectedIds: MutableList<String>,
    movingIds: Set<String>,
    failedIds: Set<String>,
    succeeded: Boolean,
): Boolean {
    selectedIds.removeAll(movingIds)
    if (!succeeded) {
        val retainedIds = failedIds.ifEmpty { movingIds }
        retainedIds.filterNotTo(selectedIds) { it in selectedIds }
    }
    return succeeded
}

/** 保留给现有文件夹移动流程的语义化兼容入口。 */
fun applyMoveSelectionResult(
    selectedIds: MutableList<String>,
    movingIds: Set<String>,
    failedIds: Set<String>,
    succeeded: Boolean,
): Boolean = applyBatchSelectionResult(selectedIds, movingIds, failedIds, succeeded)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagHomeScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as android.app.Application)),
    onNavigateToFolder: (String, String) -> Unit = { _, _ -> },
    onNavigateToConfig: () -> Unit = {},
    onNavigateToGraph: () -> Unit = {},
    onNavigateToDocEditor: (String, String) -> Unit = { _, _ -> },
) {
    val externalFolderManager = (LocalContext.current.applicationContext as? InfinityApplication)?.externalFolderManager
    val stats by viewModel.stats.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val folderStats by viewModel.folderStats.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val indexingNotice by viewModel.indexingNotice.collectAsState()
    val canRetryLastFailedIndex by viewModel.canRetryLastFailedIndex.collectAsState()
    val isRetryingLastFailedIndex by viewModel.isRetryingLastFailedIndex.collectAsState()
    val pendingRenameIndexTargets by viewModel.pendingRenameIndexTargets.collectAsState()
    val isRetryingPendingRenameIndex by viewModel.isRetryingPendingRenameIndex.collectAsState()
    val memoryVectors by viewModel.memoryVectors.collectAsState()
    val kgExtractionStates by viewModel.kgExtractionStates.collectAsState()
    val kgConfirmation by viewModel.kgExtractionConfirmation.collectAsState()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val availableWorkspaceRoots by viewModel.availableWorkspaceRoots.collectAsState()
    val selectedWorkspaceSessionId by viewModel.selectedWorkspaceSessionId.collectAsState()
    val indexingFileIds by viewModel.indexingDocIds.collectAsState()
    val searchState by viewModel.searchState.collectAsState()

    kgConfirmation?.let { confirmation ->
        com.promenar.infinity.ui.common.ConfirmDialog(
            show = true,
            onDismiss = viewModel::dismissKnowledgeGraphConfirmation,
            onConfirm = viewModel::confirmKnowledgeGraphExtraction,
            title = stringResource(R.string.kg_extraction_confirm_title),
            description = stringResource(
                R.string.kg_extraction_confirm_description, confirmation.title, confirmation.estimate.calls,
                confirmation.estimate.inputTokens.toString(), confirmation.estimate.outputTokenLimit.toString(),
                confirmation.budget.maxCalls, confirmation.budget.maxOutputTokens.toString(), confirmation.budget.maxDurationMs / 1_000,
            ),
            destructive = false,
        )
    }

    var currentTab by rememberSaveable { mutableStateOf(PortalTab.DOCUMENTS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }

    val routeState = remember(
        currentTab,
        searchQuery,
        searchState,
        selectedIds,
        workspaceRootUuid,
        folders,
        folderStats,
        stats,
        memoryVectors,
        isIndexing,
        indexingProgress,
        indexingNotice,
        canRetryLastFailedIndex,
        isRetryingLastFailedIndex,
        pendingRenameIndexTargets,
        isRetryingPendingRenameIndex,
        indexingFileIds,
        kgExtractionStates,
    ) {
        RagHomeScreenState(
            currentTab = currentTab,
            searchQuery = searchQuery,
            searchState = searchState,
            selectedIds = selectedIds,
            workspaceRootUuid = workspaceRootUuid,
            folders = folders,
            folderStats = folderStats,
            stats = stats,
            memoryVectors = memoryVectors,
            isIndexing = isIndexing,
            indexingProgress = indexingProgress,
            indexingNotice = indexingNotice,
            canRetryLastFailedIndex = canRetryLastFailedIndex,
            isRetryingLastFailedIndex = isRetryingLastFailedIndex,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            canRetryPendingRenameIndex = pendingRenameIndexTargets.isNotEmpty(),
            isRetryingPendingRenameIndex = isRetryingPendingRenameIndex,
        )
    }

    var folderStack by rememberSaveable(workspaceRootUuid) {
        mutableStateOf(listOf<FolderCrumb>())
    }
    val activeFolderId = folderStack.lastOrNull()?.id ?: workspaceRootUuid

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val targetFolder = activeFolderId?.takeIf { it != workspaceRootUuid }
            viewModel.importDocuments(uris, folderId = targetFolder)
        }
    }

    val actions = RagHomeScreenActions(
        onSearch = { query ->
            searchQuery = query
            viewModel.search(query)
        },
        onChangeTab = { nextTab ->
            currentTab = nextTab
        },
        onLoadMemory = viewModel::loadMemoryVectors,
        onOpenConfig = onNavigateToConfig,
        onOpenGraph = onNavigateToGraph,
        onOpenFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) },
        onCreateFolder = { name -> viewModel.createFolder(name, parentFolderId = activeFolderId) },
        onRetryLastFailedIndex = viewModel::retryLastFailedIndex,
        onRetryPendingRenameIndex = viewModel::retryPendingRenameIndex,
        onDismissQueueError = viewModel::dismissQueueError,
        onNavigateToDocEditor = onNavigateToDocEditor,
        onReindexFile = viewModel::reindexFile,
        onReindexDocuments = viewModel::reindexDocuments,
        onDeleteDocuments = { ids, onComplete ->
            viewModel.deleteDocuments(ids) { _, failedIds ->
                onComplete(
                    failedIds.isEmpty(),
                    failedIds.toList(),
                )
            }
        },
        onRenameFolder = viewModel::renameFolder,
        onMoveFile = viewModel::moveFile,
        onMoveDocuments = { ids, targetFolderId, onComplete ->
            viewModel.moveDocuments(ids, targetFolderId) { succeeded, failedIds ->
                onComplete(succeeded, failedIds.toList())
            }
        },
        onExtractKG = viewModel::extractKG,
        onCopyFile = viewModel::copyFile,
        onDeleteMemory = viewModel::deleteMemoryVector,
        onCancelIndexing = viewModel::cancelIndexing,
    )

    val documentsContent: @Composable (
        Modifier,
        MutableList<String>,
        (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit,
    ) -> Unit = remember(
        onNavigateToFolder,
        onNavigateToGraph,
        onNavigateToDocEditor,
        workspaceRootUuid,
        activeFolderId,
        folderStack,
        searchQuery,
        searchState,
        indexingFileIds,
        kgExtractionStates,
        availableWorkspaceRoots,
        selectedWorkspaceSessionId,
        externalFolderManager,
    ) {
        { modifier, selectedDocumentIds, requestDelete ->
            BackHandler(enabled = folderStack.isNotEmpty() && searchQuery.isBlank()) {
                folderStack = folderStack.dropLast(1)
            }

            // 输入或清空搜索词时，目录浏览与搜索结果交叉淡入。
            AnimatedContent(
                targetState = searchQuery.isBlank(),
                transitionSpec = { InfinityMotion.fadeThrough() },
                label = "rag_documents_search",
            ) { browsing ->
                if (browsing) {
                    val folderSessionId = selectedWorkspaceSessionId?.takeUnless {
                        it == CompositeWorkspaceConstants.GLOBAL_KNOWLEDGE_SESSION_ID
                    }
                    Column(
                        modifier = modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = InfinitySpacing.XLarge),
                    ) {
                        // 根目录先列出“浏览”分组：自选工作区（不进 Room 树，只读浏览并按文件加入资料）、外部文件夹与知识图谱。
                        // 进入子目录与返回上级时，页头与文件列表一起按层级方向横向切换。
                        AnimatedContent(
                            targetState = folderStack,
                            contentKey = { it.lastOrNull()?.id },
                            transitionSpec = { hierarchicalTransition { it.size } },
                            label = "rag_folder_navigation",
                        ) { shownStack ->
                        val shownFolderId = shownStack.lastOrNull()?.id ?: workspaceRootUuid
                        Column {
                        if (shownStack.isEmpty()) {
                            InfinitySettingsSection(title = stringResource(R.string.rag_home_section_browse)) {
                                com.promenar.infinity.ui.workspace.KnowledgeWorkspacesGroup(
                                    knowledgeRootUuid = workspaceRootUuid,
                                    onImported = { actions.onReindexFile(it) },
                                    grouped = true,
                                )
                                externalFolderManager?.let { manager ->
                                    ExternalFoldersPanel(
                                        sessionId = folderSessionId,
                                        workspaceRootUuid = workspaceRootUuid,
                                        manager = manager,
                                        onReindex = { actions.onReindexFile(it) },
                                        grouped = true,
                                    )
                                }
                                RagGraphEntry(onClick = actions.onOpenGraph)
                            }
                            SettingsSectionHeader(
                                title = stringResource(R.string.rag_root_all_files),
                                titleStyle = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(
                                    start = InfinitySpacing.RowHorizontal,
                                    end = InfinitySpacing.RowHorizontal,
                                ),
                            )
                        } else {
                            FolderBreadcrumbsBar(
                                folderStack = shownStack,
                                rootTitle = stringResource(R.string.rag_root_all_files),
                                onNavigateBack = { folderStack = shownStack.dropLast(1) },
                                onNavigateToCrumb = { index ->
                                    folderStack = if (index == 0) emptyList() else shownStack.take(index)
                                },
                            )
                        }

                        key(workspaceRootUuid, shownFolderId) {
                            FilesPanel(
                                workspaceRootUuid = workspaceRootUuid,
                                workspaceRepo = viewModel.getWorkspaceRepo(),
                                sessionId = folderSessionId,
                                currentParentUuid = shownFolderId,
                                searchQuery = "",
                                // 整页随外层一起滚动，文件分组按内容高度收拢，不再撑满剩余空间。
                                useScroll = false,
                                onReindex = { actions.onReindexFile(it) },
                                onDelete = requestDelete,
                                onRename = { uuid, name -> actions.onRenameFolder(uuid, name) },
                                onMove = { uuid, targetId -> actions.onMoveFile(uuid, targetId) {} },
                                onExtractKG = actions.onExtractKG,
                                onViewKG = { onNavigateToGraph() },
                                onCopy = actions.onCopyFile,
                                indexingFileIds = indexingFileIds,
                                kgExtractionStates = kgExtractionStates,
                                externalSelectedIds = selectedDocumentIds,
                                showSelectionOverlay = false,
                                allowTreeExpansion = false,
                                onFolderClick = { folderId, folderName ->
                                    // 兼顾页面下钻与导航契约: onFolderClick = onNavigateToFolder
                                    folderStack = shownStack + FolderCrumb(folderId, folderName)
                                },
                                onFileClick = { docId ->
                                    workspaceRootUuid?.let { root ->
                                        actions.onNavigateToDocEditor(root, docId)
                                    }
                                },
                            )
                        }
                        }
                        }
                    }
                } else {
                    RagSearchResults(
                        state = searchState,
                        onOpenDocument = { docId ->
                            workspaceRootUuid?.let { root ->
                                actions.onNavigateToDocEditor(root, docId)
                            }
                        },
                        modifier = modifier,
                    )
                }
            }
        }
    }

    RagHomeScreenContent(
        state = routeState,
        actions = actions,
        documentsContent = documentsContent,
    )
}

/** “浏览”分组里的知识图谱入口，行样式与工作区、外部文件夹入口一致。 */
@Composable
private fun RagGraphEntry(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RAG_HOME_TAB_GRAPH)
            .clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(stringResource(R.string.rag_details_tab_knowledge_graph)) },
        supportingContent = {
            Text(
                text = stringResource(R.string.rag_home_graph_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Rounded.Hub, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 页头“添加”：上传文档或在当前目录新建文件夹。 */
@Composable
private fun RagAddAction(
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .testTag(UiTags.RAG_HOME_ADD),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.rag_home_add),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfinityDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            InfinityMenuItem(
                leadingIcon = { Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.rag_folder_upload), style = InfinityTypography.labelLarge) },
                onClick = {
                    expanded = false
                    onUpload()
                },
                modifier = Modifier.testTag(UiTags.RAG_HOME_UPLOAD),
            )
            InfinityMenuItem(
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.rag_home_new_folder_title), style = InfinityTypography.labelLarge) },
                onClick = {
                    expanded = false
                    onNewFolder()
                },
                modifier = Modifier.testTag(UiTags.RAG_HOME_NEW_FOLDER),
            )
        }
    }
}

internal object RecoveryRagUiTags {
    const val SOURCE_SELECTOR = "recovery_rag_source_selector"
    const val SOURCE_OPTION_PREFIX = "recovery_rag_source_option:"

    fun sourceOption(sessionId: String): String = SOURCE_OPTION_PREFIX + sessionId
}

@Composable
internal fun RagWorkspaceSourceSelector(
    sources: List<RagWorkspaceSource>,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
) {
    if (sources.size <= 1) return
    val selected = sources.firstOrNull { it.sessionId == selectedSessionId } ?: sources.first()
    var expanded by remember(selected.sessionId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.XSmall),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Text(
            text = stringResource(R.string.rag_recovery_source_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RecoveryRagUiTags.SOURCE_SELECTOR),
                contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium),
            ) {
                Text(
                    text = selected.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InfinityDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                sources.forEach { source ->
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                text = source.title,
                                style = InfinityTypography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(source.sessionId)
                        },
                        modifier = Modifier.testTag(RecoveryRagUiTags.sourceOption(source.sessionId)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RagHomeScreenContent(
    state: RagHomeScreenState,
    actions: RagHomeScreenActions,
    documentsContent: @Composable (
        Modifier,
        MutableList<String>,
        (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit,
    ) -> Unit,
    initiallyExpandedMemoryId: String? = null,
    initialMemoryDeleteTargetId: String? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val sdf = remember(locale) { SimpleDateFormat("MMM d, HH:mm", locale) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var isMovingSelection by remember { mutableStateOf(false) }
    var isDeletingSelection by remember { mutableStateOf(false) }
    var pendingDocumentDelete by remember { mutableStateOf<PendingDocumentDelete?>(null) }
    var expandedMemoryId by remember(initiallyExpandedMemoryId) { mutableStateOf(initiallyExpandedMemoryId) }
    var memoryDeleteTargetId by remember(initialMemoryDeleteTargetId) {
        mutableStateOf(initialMemoryDeleteTargetId)
    }

    val requestDocumentDelete: (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit = { ids, completion ->
        val distinctIds = ids.distinct()
        if (distinctIds.isNotEmpty() && pendingDocumentDelete == null && !isDeletingSelection) {
            pendingDocumentDelete = PendingDocumentDelete(distinctIds, completion)
        }
    }

    LaunchedEffect(state.currentTab) {
        if (state.currentTab == PortalTab.MEMORY) actions.onLoadMemory()
    }

    var searchActive by rememberSaveable { mutableStateOf(state.searchQuery.isNotBlank()) }

    Scaffold(
        modifier = Modifier.testTag(UiTags.RAG_HOME_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            InfinityTabHeader(
                title = stringResource(R.string.rag_home_title),
                searchActive = searchActive,
                query = state.searchQuery,
                onQueryChange = { query ->
                    applyRagSearchSelectionPolicy(state.selectedIds, query)
                    actions.onSearch(query)
                },
                onSearchActiveChange = { searchActive = it },
                searchPlaceholder = stringResource(
                    if (state.currentTab == PortalTab.DOCUMENTS) {
                        R.string.rag_home_search
                    } else {
                        R.string.rag_home_memory_search
                    }
                ),
                searchFieldTag = UiTags.RAG_HOME_SEARCH,
            ) {
                if (state.currentTab == PortalTab.DOCUMENTS) {
                    RagAddAction(
                        onUpload = actions.onOpenFilePicker,
                        onNewFolder = { showNewFolderDialog = true },
                    )
                }
            }
        },
        floatingActionButton = {
            RagStatusFab(state = state, actions = actions)
        },
        bottomBar = {
            if (state.currentTab == PortalTab.DOCUMENTS && state.selectedIds.isNotEmpty()) {
                RagHomeSelectionBar(
                    selectedCount = state.selectedIds.size,
                    operationsEnabled = !isMovingSelection && !isDeletingSelection,
                    onClear = state.selectedIds::clear,
                    onMove = { showMoveSheet = true },
                    onReindex = {
                        actions.onReindexDocuments(state.selectedIds.toList())
                        state.selectedIds.clear()
                    },
                    onDelete = {
                        requestDocumentDelete(state.selectedIds.toList()) { result ->
                            applyBatchSelectionResult(
                                selectedIds = state.selectedIds,
                                movingIds = result.attemptedIds.toSet(),
                                failedIds = result.failedIds.toSet(),
                                succeeded = result.attemptedIds.isNotEmpty() && result.failedIds.isEmpty(),
                            )
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = InfinitySpacing.ScreenHorizontal),
            ) {
                RagPortalTabSwitcher(
                    selectedTab = state.currentTab,
                    onTabSelected = actions.onChangeTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.Large),
                )

                // 文档与记忆按标签顺序横向切换，和分段指示器的移动方向一致。
                AnimatedContent(
                    targetState = state.currentTab,
                    transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "rag_portal_tab",
                ) { tab ->
                    when (tab) {
                        PortalTab.DOCUMENTS -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(UiTags.RAG_HOME_DOCUMENTS_CONTENT)
                            ) {
                                documentsContent(
                                    Modifier.fillMaxSize(),
                                    state.selectedIds,
                                    requestDocumentDelete,
                                )
                            }

                        }

                        PortalTab.MEMORY -> {
                            val filteredMemories = remember(state.memoryVectors, state.searchQuery) {
                                if (state.searchQuery.isBlank()) {
                                    state.memoryVectors
                                } else {
                                    state.memoryVectors.filter {
                                        it.content.contains(state.searchQuery, ignoreCase = true)
                                    }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(UiTags.RAG_HOME_MEMORY_CONTENT),
                                contentPadding = PaddingValues(bottom = InfinitySpacing.XLarge),
                            ) {
                                if (state.memoryVectors.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 96.dp)
                                                .testTag("memory-empty"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Psychology,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(32.dp),
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Text(
                                                    text = stringResource(R.string.rag_home_memory_empty_title),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = stringResource(R.string.rag_home_memory_empty),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                } else if (filteredMemories.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 96.dp)
                                                .testTag("memory-search-empty"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Search,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(32.dp),
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Text(
                                                    text = stringResource(R.string.rag_home_memory_search_empty_title),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = stringResource(R.string.rag_home_memory_search_empty),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    itemsIndexed(filteredMemories, key = { _, memory -> memory.id }) { index, memory ->
                                        val isExpanded = expandedMemoryId == memory.id
                                        // 记忆逐条拼成一个连续分组，与设置页的分组面一致。
                                        ListItem(
                                            // 删除与筛选时条目平滑让位；展开全文时高度过渡。
                                            modifier = Modifier
                                                .animateItem()
                                                .fillMaxWidth()
                                                .bettboxListGroupItem(index, filteredMemories.size)
                                                .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
                                                .testTag("memory-item-${memory.id}"),
                                            headlineContent = {
                                                Text(
                                                    text = memory.content,
                                                    style = MaterialTheme.typography.bodyLarge.forReading(),
                                                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            supportingContent = {
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                                                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                                                ) {
                                                    memory.sessionId?.let {
                                                        Text(
                                                            "${stringResource(R.string.sessions_tag_session)}: ${it.take(8)}…",
                                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                                            color = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                    Text(
                                                        sdf.format(java.util.Date(memory.createdAt)),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            trailingContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(
                                                        onClick = {
                                                            expandedMemoryId = if (isExpanded) null else memory.id
                                                        },
                                                        modifier = Modifier
                                                            .sizeIn(
                                                                minWidth = InfinitySpacing.MinimumTouchTarget,
                                                                minHeight = InfinitySpacing.MinimumTouchTarget,
                                                            )
                                                            .testTag("memory-expand-${memory.id}"),
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isExpanded) {
                                                                Icons.Rounded.ExpandLess
                                                            } else {
                                                                Icons.Rounded.ExpandMore
                                                            },
                                                            contentDescription = stringResource(
                                                                if (isExpanded) {
                                                                    R.string.common_cd_collapse
                                                                } else {
                                                                    R.string.common_cd_expand
                                                                },
                                                            ),
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { memoryDeleteTargetId = memory.id },
                                                        modifier = Modifier
                                                            .sizeIn(
                                                                minWidth = InfinitySpacing.MinimumTouchTarget,
                                                                minHeight = InfinitySpacing.MinimumTouchTarget,
                                                            )
                                                            .testTag("memory-delete-${memory.id}"),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Delete,
                                                            contentDescription = stringResource(R.string.common_cd_delete),
                                                            tint = MaterialTheme.colorScheme.error,
                                                        )
                                                    }
                                                }
                                            },
                                            colors = ListItemDefaults.colors(
                                                containerColor = Color.Transparent,
                                            ),
                                        )
                                    }
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = InfinitySpacing.Medium),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_footer_count, filteredMemories.size),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        PortalTab.GRAPH -> {}
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        InfinityConfirmDialog(
            // 测试标签原挂在标题容器上，现挂在整个对话框卡片上。
            modifier = Modifier.testTag(UiTags.RAG_HOME_NEW_FOLDER_DIALOG),
            onDismissRequest = { showNewFolderDialog = false },
            title = stringResource(R.string.rag_home_new_folder_title),
            message = "",
            confirmText = stringResource(R.string.shared_btn_add),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                if (folderName.isNotBlank()) actions.onCreateFolder(folderName.trim())
                showNewFolderDialog = false
            },
            onCancel = { showNewFolderDialog = false },
            confirmEnabled = folderName.isNotBlank(),
            content = {
                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.rag_home_folder_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (showMoveSheet) {
        InfinityModalSheet(
            onDismissRequest = { if (!isMovingSelection) showMoveSheet = false },
            height = InfinitySheetHeight.Content,
            modifier = Modifier.testTag(UiTags.RAG_HOME_MOVE_SHEET),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rag-home-move-folder-list"),
                contentPadding = PaddingValues(
                    start = InfinitySpacing.Large,
                    top = InfinitySpacing.Medium,
                    end = InfinitySpacing.Large,
                    bottom = InfinitySpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                item {
                    Text(
                        stringResource(R.string.rag_home_move_to_folder),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = InfinitySpacing.Small),
                    )
                }
                items(state.folders, key = { it.id }) { folder ->
                    val moveLabel = stringResource(R.string.rag_home_move)
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(
                                enabled = !isMovingSelection,
                                onClickLabel = moveLabel,
                                role = Role.Button,
                            ) {
                                val movingIds = state.selectedIds.toList()
                                if (movingIds.isNotEmpty()) {
                                    isMovingSelection = true
                                    actions.onMoveDocuments(movingIds, folder.id) { succeeded, failedIds ->
                                        isMovingSelection = false
                                        if (applyBatchSelectionResult(
                                                selectedIds = state.selectedIds,
                                                movingIds = movingIds.toSet(),
                                                failedIds = failedIds.toSet(),
                                                succeeded = succeeded,
                                            )
                                        ) {
                                            showMoveSheet = false
                                        }
                                    }
                                }
                            },
                        headlineContent = {
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.rag_home_docs_count, state.folderStats[folder.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }
        }
    }

    pendingDocumentDelete?.let { pendingDelete ->
        val dismissDeleteConfirmation = {
            pendingDelete.completion(
                FileBatchOperationResult(
                    attemptedIds = emptyList(),
                    failedIds = emptyList(),
                ),
            )
            pendingDocumentDelete = null
        }
        InfinityConfirmDialog(
            onDismissRequest = { if (!isDeletingSelection) dismissDeleteConfirmation() },
            modifier = Modifier.testTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG),
            title = androidx.compose.ui.res.pluralStringResource(R.plurals.permanent_delete_title, pendingDelete.ids.size, pendingDelete.ids.size),
            message = stringResource(R.string.permanent_delete_message),
            confirmText = stringResource(R.string.permanent_delete_action),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                if (pendingDelete.ids.isNotEmpty()) {
                    isDeletingSelection = true
                    actions.onDeleteDocuments(pendingDelete.ids) { succeeded, failedIds ->
                        val normalizedFailedIds = if (!succeeded && failedIds.isEmpty()) {
                            pendingDelete.ids
                        } else {
                            failedIds
                        }
                        pendingDelete.completion(
                            FileBatchOperationResult(
                                attemptedIds = pendingDelete.ids,
                                failedIds = normalizedFailedIds,
                            ),
                        )
                        isDeletingSelection = false
                        pendingDocumentDelete = if (normalizedFailedIds.isEmpty()) {
                            null
                        } else {
                            pendingDelete.copy(ids = normalizedFailedIds.distinct())
                        }
                    }
                }
            },
            // 删除中取消按钮原为禁用；统一对话框无禁用态，改为删除期间忽略点击。
            onCancel = { dismissDeleteConfirmation() },
            cancelEnabled = !isDeletingSelection,
            isDestructive = true,
            confirmEnabled = pendingDelete.ids.isNotEmpty() && !isDeletingSelection,
            confirmButtonModifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .testTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON),
            cancelButtonModifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        )
    }

    if (memoryDeleteTargetId != null) {
        InfinityConfirmDialog(
            onDismissRequest = { memoryDeleteTargetId = null },
            modifier = Modifier.testTag("memory-delete-dialog"),
            title = stringResource(R.string.rag_home_memory_delete_confirm_title),
            message = stringResource(R.string.rag_home_memory_delete_confirm_msg),
            confirmText = stringResource(R.string.shared_btn_delete),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                memoryDeleteTargetId?.let(actions.onDeleteMemory)
                memoryDeleteTargetId = null
            },
            onCancel = { memoryDeleteTargetId = null },
            isDestructive = true,
            confirmButtonModifier = Modifier
                .sizeIn(
                    minWidth = InfinitySpacing.MinimumTouchTarget,
                    minHeight = InfinitySpacing.MinimumTouchTarget,
                )
                .testTag("memory-delete-confirm"),
        )
    }
}

@Composable
internal fun RagHomeSelectionBar(
    selectedCount: Int,
    operationsEnabled: Boolean,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.ScreenHorizontal)
                .padding(bottom = InfinitySpacing.Medium)
                .testTag(UiTags.RAG_HOME_SELECTION_BAR),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(InfinitySpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rag_home_selected_count, selectedCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 清除选择是退出多选，用行尾关闭图标，不再占一个文字按钮。
                    IconButton(
                        onClick = onClear,
                        enabled = operationsEnabled,
                        modifier = Modifier.testTag(UiTags.RAG_HOME_CLEAR_SELECTION),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.rag_home_clear_all))
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InfinityPillButton(
                        text = stringResource(R.string.rag_home_move),
                        onClick = onMove,
                        enabled = operationsEnabled,
                        icon = Icons.Rounded.Folder,
                        modifier = Modifier.testTag(UiTags.RAG_HOME_MOVE_SELECTION),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.rag_home_reindex),
                        onClick = onReindex,
                        enabled = operationsEnabled,
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.testTag(UiTags.RAG_HOME_REINDEX_SELECTION),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.shared_btn_delete),
                        onClick = onDelete,
                        tone = InfinityPillTone.DANGER,
                        enabled = operationsEnabled,
                        icon = Icons.Rounded.Delete,
                        modifier = Modifier.testTag(UiTags.RAG_HOME_DELETE_SELECTION),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RagPortalTabSwitcher(
    selectedTab: PortalTab,
    onTabSelected: (PortalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalInfinityHaptics.current
    val tabs = remember { listOf(PortalTab.DOCUMENTS, PortalTab.MEMORY) }
    val selectedIndex = if (selectedTab == PortalTab.MEMORY) 1 else 0

    BoxWithConstraints(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (selectedIndex == 0) 0.dp else tabWidth,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "portalTabIndicatorOffset",
        )

        // 胶囊滑动高亮底座 (与 Homebar 选中态风格一致)
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )

        // 选项文本与点击行
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 200),
                    label = "portalTabContentColor_$index",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (selectedTab != tab) {
                                    haptics.perform(InfinityHapticEvent.SELECTION)
                                    onTabSelected(tab)
                                }
                            },
                        )
                        .then(
                            when (tab) {
                                PortalTab.DOCUMENTS -> Modifier.testTag(UiTags.RAG_HOME_TAB_DOCUMENTS)
                                PortalTab.MEMORY -> Modifier.testTag(UiTags.RAG_HOME_TAB_MEMORY)
                                PortalTab.GRAPH -> Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (tab == PortalTab.DOCUMENTS) {
                                Icons.Rounded.Description
                            } else {
                                Icons.Rounded.Psychology
                            },
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                if (tab == PortalTab.DOCUMENTS) {
                                    R.string.rag_home_documents
                                } else {
                                    R.string.rag_home_memory
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RagStatusFab(
    state: RagHomeScreenState,
    actions: RagHomeScreenActions,
    modifier: Modifier = Modifier,
) {
    val isExtractingKg = remember(state.kgExtractionStates) {
        state.kgExtractionStates.values.any { it == KgStatus.IN_PROGRESS }
    }
    val hasKgFailed = remember(state.kgExtractionStates) {
        state.kgExtractionStates.values.any { it == KgStatus.FAILED }
    }
    val isError = state.indexingNotice?.severity == NoticeSeverity.Error ||
        state.canRetryLastFailedIndex ||
        state.canRetryPendingRenameIndex ||
        hasKgFailed
    val isVectorizing = (!isError && state.isIndexing) || state.indexingFileIds.isNotEmpty()
    val isRunning = isVectorizing || isExtractingKg
    val isBothRunning = isVectorizing && isExtractingKg

    var wasRunning by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, isError) {
        if (isRunning) {
            wasRunning = true
            showSuccess = false
        } else if (wasRunning && !isError) {
            wasRunning = false
            showSuccess = true
            delay(3500)
            showSuccess = false
        } else {
            wasRunning = false
        }
    }

    val isVisible = isRunning || isError || showSuccess

    var iconToggle by remember { mutableStateOf(false) }
    LaunchedEffect(isBothRunning) {
        if (isBothRunning) {
            while (isActive) {
                delay(1800)
                iconToggle = !iconToggle
            }
        } else {
            iconToggle = false
        }
    }

    var expanded by remember { mutableStateOf(false) }

    val resolvedNotice = state.indexingNotice?.let { IndexingNotice.template(it) }
    val noticeText = resolvedNotice?.let { resolved ->
        stringResource(resolved.resourceId, *resolved.args.toTypedArray())
    } ?: state.indexingNotice?.technical

    val titleText = when {
        isError -> stringResource(R.string.rag_status_failed)
        showSuccess -> stringResource(R.string.rag_status_completed)
        isBothRunning -> stringResource(R.string.rag_status_tasks_running)
        isExtractingKg -> stringResource(R.string.rag_status_kg_extracting)
        isVectorizing -> stringResource(R.string.rag_status_vectorizing)
        else -> stringResource(R.string.rag_status_ready)
    }

    val fabStatus = noticeText?.takeIf { it.isNotBlank() } ?: titleText
    val fabAccessibility = resolveIndexingProgressAccessibility(state.indexingProgress, isError)

    val indexAction = resolveRagHomeIndexAction(
        noticeCode = state.indexingNotice?.code,
        canRetryLastFailedIndex = state.canRetryLastFailedIndex,
        canRetryPendingIndex = state.canRetryPendingRenameIndex,
    )
    val showRetry = indexAction != RagHomeIndexAction.None
    val retrying = state.isRetryingLastFailedIndex || state.isRetryingPendingRenameIndex

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(250)) + scaleIn(tween(250)),
        exit = fadeOut(tween(200)) + scaleOut(tween(200)),
        modifier = modifier.testTag(UiTags.RAG_HOME_INDEXING_NOTICE),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.padding(bottom = 8.dp, end = 8.dp),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(250)) + expandHorizontally(tween(300), expandFrom = Alignment.End),
                exit = fadeOut(tween(200)) + shrinkHorizontally(tween(250), shrinkTowards = Alignment.End),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .widthIn(min = 220.dp, max = 290.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { expanded = false },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.common_dismiss),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (isVectorizing && state.indexingProgress >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.indexingProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .clearAndSetSemantics {},
                            )
                        }

                        if (!noticeText.isNullOrBlank()) {
                            Text(
                                text = noticeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isError) {
                            // 有可重试的索引时重试为主操作，否则去设置为主操作；主操作放最后。
                            InfinityPillRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                spacing = 8.dp,
                            ) {
                                InfinityPillButton(
                                    text = stringResource(R.string.common_dismiss),
                                    onClick = {
                                        actions.onDismissQueueError()
                                        expanded = false
                                    },
                                    enabled = !retrying,
                                    compact = true,
                                )
                                InfinityPillButton(
                                    text = stringResource(R.string.rag_status_go_to_settings),
                                    onClick = {
                                        expanded = false
                                        actions.onOpenConfig()
                                    },
                                    tone = if (showRetry) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                                    compact = true,
                                )
                                if (showRetry) {
                                    InfinityPillButton(
                                        text = stringResource(R.string.shared_btn_retry),
                                        onClick = {
                                            when (indexAction) {
                                                RagHomeIndexAction.RetryFailed -> actions.onRetryLastFailedIndex()
                                                RagHomeIndexAction.RetryPending -> actions.onRetryPendingRenameIndex()
                                                RagHomeIndexAction.None -> {}
                                            }
                                        },
                                        tone = InfinityPillTone.PRIMARY,
                                        enabled = !retrying,
                                        compact = true,
                                    )
                                }
                            }
                        } else if (isRunning) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                InfinityPillButton(
                                    text = stringResource(R.string.common_cancel),
                                    onClick = {
                                        actions.onCancelIndexing()
                                        expanded = false
                                    },
                                    tone = InfinityPillTone.DANGER,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }

            val containerColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.errorContainer
                    showSuccess -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                label = "FabContainerColor",
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    showSuccess -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                },
                label = "FabContentColor",
            )

            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        // 进度与状态由下方按钮统一发布，这里只是视觉圈。
                        modifier = Modifier.size(54.dp).clearAndSetSemantics {},
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                }

                Surface(
                    onClick = { expanded = !expanded },
                    shape = CircleShape,
                    color = containerColor,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    // 折叠时浮钮是唯一的无障碍事实来源：状态、实时播报与确定进度都发布在这里。
                    modifier = Modifier.size(46.dp).semantics {
                        stateDescription = fabStatus
                        liveRegion = if (fabAccessibility.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        if (isVectorizing && state.indexingProgress >= 0f && fabAccessibility.showProgress) {
                            progressBarRangeInfo = ProgressBarRangeInfo(current = fabAccessibility.progress, range = 0f..1f)
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = when {
                                isError -> 0
                                showSuccess -> 1
                                isBothRunning && iconToggle -> 2
                                isBothRunning && !iconToggle -> 3
                                isExtractingKg -> 2
                                else -> 3
                            },
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                            label = "FabIcon",
                        ) { target ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (target) {
                                    0 -> Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.rag_status_failed),
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    1 -> Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = stringResource(R.string.rag_status_completed),
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    2 -> Icon(
                                        imageVector = Icons.Rounded.Hub,
                                        contentDescription = stringResource(R.string.rag_status_kg_extracting),
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = stringResource(R.string.rag_status_vectorizing),
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

