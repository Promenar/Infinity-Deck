package com.promenar.infinity.ui.rag

import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.theme.InfinityTypography
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.model.Folder
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.rag.components.IndexingProgressBar
import com.promenar.infinity.ui.rag.components.RagDocItem
import com.promenar.infinity.ui.rag.components.RagStatus
import com.promenar.infinity.ui.testing.UiTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityRetryButton

internal enum class RagFolderContentState { Loading, Error, Empty, Content }

internal fun resolveRagFolderContentState(
    hasLoaded: Boolean,
    hasLoadError: Boolean,
    documentCount: Int,
): RagFolderContentState = when {
    hasLoadError -> RagFolderContentState.Error
    !hasLoaded -> RagFolderContentState.Loading
    documentCount == 0 -> RagFolderContentState.Empty
    else -> RagFolderContentState.Content
}

internal fun isAllRagFolderDocumentsSelected(documentCount: Int, selectedCount: Int): Boolean =
    documentCount > 0 && selectedCount == documentCount

internal fun shouldShowRagDocumentCheckbox(): Boolean = true

internal enum class RagFolderIndexAction {
    Upload,
    Move,
    Delete,
    RetryFailed,
    RetryPending,
    None,
}

internal fun resolveRagFolderIndexAction(
    noticeCode: String?,
    hasSelection: Boolean,
    canRetryLastFailedIndex: Boolean,
    canRetryPendingIndex: Boolean,
): RagFolderIndexAction = when {
    noticeCode == IndexingNotice.CODE_IMPORT_FAILED -> RagFolderIndexAction.Upload
    noticeCode == IndexingNotice.CODE_MOVE_FAILED && hasSelection -> RagFolderIndexAction.Move
    noticeCode == IndexingNotice.CODE_DELETE_FAILED && hasSelection -> RagFolderIndexAction.Delete
    noticeCode in setOf(IndexingNotice.CODE_FAILED, IndexingNotice.CODE_PARTIAL) &&
        canRetryLastFailedIndex -> RagFolderIndexAction.RetryFailed
    noticeCode != null -> RagFolderIndexAction.None
    canRetryPendingIndex -> RagFolderIndexAction.RetryPending
    else -> RagFolderIndexAction.None
}

internal data class RagFolderScreenState(
    val title: String,
    val workspaceRootUuid: String?,
    val documents: List<FileEntry>,
    val folders: List<Folder>,
    val currentFolderId: String,
    val selectedIds: MutableList<String>,
    val contentState: RagFolderContentState,
    val isIndexing: Boolean = false,
    val indexingProgress: Float = 0f,
    val indexingNotice: UiStatusNotice? = null,
    val canRetryLastFailedIndex: Boolean = false,
    val isRetryingLastFailedIndex: Boolean = false,
    val isMovingDocuments: Boolean = false,
    val isDeletingDocuments: Boolean = false,
    val canRetryPendingRenameIndex: Boolean = false,
    val isRetryingPendingRenameIndex: Boolean = false,
)

internal data class RagFolderScreenActions(
    val onBack: () -> Unit = {},
    val onUpload: () -> Unit = {},
    val onOpenDocument: (String, String) -> Unit = { _, _ -> },
    val onRetryLoad: () -> Unit = {},
    val onRetryIndex: () -> Unit = {},
    val onRetryPendingRenameIndex: () -> Unit = {},
    val onDismissIndexNotice: () -> Unit = {},
    val onReindex: (Collection<String>) -> Unit = {},
    val onMove: (Collection<String>, String, (Boolean, List<String>) -> Unit) -> Unit = { _, _, _ -> },
    val onDelete: (Collection<String>, (Boolean, List<String>) -> Unit) -> Unit = { _, _ -> },
)

@Composable
fun RagFolderScreen(
    folderId: String,
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateToDocEditor: (String, String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val indexingNotice by viewModel.indexingNotice.collectAsState()
    val canRetryLastFailedIndex by viewModel.canRetryLastFailedIndex.collectAsState()
    val isRetryingLastFailedIndex by viewModel.isRetryingLastFailedIndex.collectAsState()
    val pendingRenameIndexTargets by viewModel.pendingRenameIndexTargets.collectAsState()
    val isRetryingPendingRenameIndex by viewModel.isRetryingPendingRenameIndex.collectAsState()
    val isMovingDocuments by viewModel.isMovingDocuments.collectAsState()
    val isDeletingDocuments by viewModel.isDeletingDocuments.collectAsState()
    val selectedIds = remember { mutableStateListOf<String>() }
    var folderEntries by remember(folderId) { mutableStateOf<List<FileEntry>?>(null) }
    var resolvedFolderName by remember(folderId) { mutableStateOf<String?>(null) }
    var folderLoadError by remember(folderId) { mutableStateOf(false) }
    var folderLoadAttempt by remember(folderId) { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris, folderId)
    }

    LaunchedEffect(folderId, workspaceRootUuid) {
        val root = workspaceRootUuid ?: return@LaunchedEffect
        resolvedFolderName = try {
            viewModel.getWorkspaceRepo().getByUuid(root, folderId)?.name?.takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    LaunchedEffect(folderId, workspaceRootUuid, folderLoadAttempt) {
        folderEntries = null
        folderLoadError = false
        val root = workspaceRootUuid ?: return@LaunchedEffect
        try {
            viewModel.getWorkspaceRepo().observeChildren(root, folderId).collect { entries ->
                folderEntries = entries.filterNot { it.isDirectory }
                selectedIds.retainAll(folderEntries.orEmpty().mapTo(mutableSetOf()) { it.uuid })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            folderLoadError = true
        }
    }

    val documents = folderEntries.orEmpty()
    RagFolderScreenContent(
        state = RagFolderScreenState(
            title = resolvedFolderName ?: stringResource(R.string.rag_home_documents),
            workspaceRootUuid = workspaceRootUuid,
            documents = documents,
            folders = folders,
            currentFolderId = folderId,
            selectedIds = selectedIds,
            contentState = resolveRagFolderContentState(folderEntries != null, folderLoadError, documents.size),
            isIndexing = isIndexing,
            indexingProgress = indexingProgress,
            indexingNotice = indexingNotice,
            canRetryLastFailedIndex = canRetryLastFailedIndex,
            isRetryingLastFailedIndex = isRetryingLastFailedIndex,
            isMovingDocuments = isMovingDocuments,
            isDeletingDocuments = isDeletingDocuments,
            canRetryPendingRenameIndex = pendingRenameIndexTargets.isNotEmpty(),
            isRetryingPendingRenameIndex = isRetryingPendingRenameIndex,
        ),
        actions = RagFolderScreenActions(
            onBack = onNavigateBack,
            onUpload = { picker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) },
            onOpenDocument = onNavigateToDocEditor,
            onRetryLoad = { folderLoadAttempt += 1 },
            onRetryIndex = viewModel::retryLastFailedIndex,
            onRetryPendingRenameIndex = viewModel::retryPendingRenameIndex,
            onDismissIndexNotice = viewModel::dismissQueueError,
            onReindex = viewModel::reindexDocuments,
            onMove = viewModel::moveDocuments,
            onDelete = viewModel::deleteDocuments,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RagFolderScreenContent(
    state: RagFolderScreenState,
    actions: RagFolderScreenActions,
    initialShowMoveSheet: Boolean = false,
    initialShowDeleteConfirm: Boolean = false,
) {
    var showMoveSheet by remember { mutableStateOf(initialShowMoveSheet) }
    var showDeleteConfirm by remember { mutableStateOf(initialShowDeleteConfirm) }
    var showTopMenu by remember { mutableStateOf(false) }
    val allSelected = isAllRagFolderDocumentsSelected(state.documents.size, state.selectedIds.size)
    val operationsEnabled = !state.isMovingDocuments && !state.isDeletingDocuments
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val toggleAll: () -> Unit = {
        state.selectedIds.clear()
        if (!allSelected) state.selectedIds.addAll(state.documents.map { it.uuid })
    }

    LaunchedEffect(state.selectedIds.isEmpty()) {
        if (state.selectedIds.isEmpty()) {
            showMoveSheet = false
            showDeleteConfirm = false
        }
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.RAG_FOLDER_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.title,
                        style = if (largeFont) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.testTag(UiTags.RAG_FOLDER_BACK),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_cd_back))
                    }
                },
                actions = {
                    if (largeFont) {
                        Box {
                            IconButton(
                                onClick = { showTopMenu = true },
                                enabled = state.documents.isNotEmpty(),
                                modifier = Modifier.testTag(UiTags.RAG_FOLDER_SELECT_ALL),
                            ) {
                                Icon(Icons.Rounded.MoreVert, stringResource(if (allSelected) R.string.rag_folder_deselect else R.string.rag_folder_select_all))
                            }
                            InfinityDropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                InfinityMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = stringResource(if (allSelected) R.string.rag_folder_deselect else R.string.rag_folder_select_all),
                                            style = InfinityTypography.labelLarge,
                                        )
                                    },
                                    modifier = Modifier.testTag(UiTags.RAG_FOLDER_SELECT_ALL_MENU_ITEM),
                                    onClick = {
                                        toggleAll()
                                        showTopMenu = false
                                    },
                                )
                            }
                        }
                    } else {
                        TextButton(
                            onClick = toggleAll,
                            enabled = state.documents.isNotEmpty(),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag(UiTags.RAG_FOLDER_SELECT_ALL),
                        ) {
                            Text(stringResource(if (allSelected) R.string.rag_folder_deselect else R.string.rag_folder_select_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (state.selectedIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .testTag(UiTags.RAG_FOLDER_SELECTION_BAR),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.rag_home_selected_count, state.selectedIds.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            // 清除选择是退出多选，用行尾关闭图标，不再占一个文字按钮。
                            IconButton(
                                onClick = state.selectedIds::clear,
                                enabled = operationsEnabled,
                                modifier = Modifier.testTag(UiTags.RAG_FOLDER_CLEAR_SELECTION),
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
                                onClick = { showMoveSheet = true },
                                enabled = operationsEnabled,
                                icon = Icons.Rounded.Folder,
                                modifier = Modifier.testTag(UiTags.RAG_FOLDER_MOVE_SELECTION),
                            )
                            InfinityPillButton(
                                text = stringResource(R.string.rag_home_reindex),
                                onClick = { actions.onReindex(state.selectedIds.toList()) },
                                enabled = operationsEnabled,
                                icon = Icons.Rounded.Refresh,
                                modifier = Modifier.testTag(UiTags.RAG_FOLDER_REINDEX_SELECTION),
                            )
                            InfinityPillButton(
                                text = stringResource(R.string.shared_btn_delete),
                                onClick = { showDeleteConfirm = true },
                                tone = InfinityPillTone.DANGER,
                                enabled = operationsEnabled,
                                icon = Icons.Rounded.Delete,
                                modifier = Modifier.testTag(UiTags.RAG_FOLDER_DELETE_SELECTION),
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(UiTags.RAG_FOLDER_CONTENT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shouldShowRagIndexSection(
                    isIndexing = state.isIndexing,
                    hasNotice = state.indexingNotice != null,
                    canRetryPendingIndex = state.canRetryPendingRenameIndex,
                )
            ) {
                val notice = state.indexingNotice?.let(IndexingNotice::template)
                val status = notice?.let { stringResource(it.resourceId, *it.args.toTypedArray()) }
                    ?: stringResource(
                        ragIndexFallbackStatusResource(state.canRetryPendingRenameIndex),
                    )
                val indexAction = resolveRagFolderIndexAction(
                    noticeCode = state.indexingNotice?.code,
                    hasSelection = state.selectedIds.isNotEmpty(),
                    canRetryLastFailedIndex = state.canRetryLastFailedIndex,
                    canRetryPendingIndex = state.canRetryPendingRenameIndex,
                )
                val retryAction: (() -> Unit)? = when (indexAction) {
                    RagFolderIndexAction.Upload -> actions.onUpload
                    RagFolderIndexAction.Move -> ({ showMoveSheet = true })
                    RagFolderIndexAction.Delete -> ({ showDeleteConfirm = true })
                    RagFolderIndexAction.RetryFailed -> actions.onRetryIndex
                    RagFolderIndexAction.RetryPending -> actions.onRetryPendingRenameIndex
                    RagFolderIndexAction.None -> null
                }
                Column {
                    val retrying = state.isRetryingLastFailedIndex ||
                        state.isRetryingPendingRenameIndex
                    IndexingProgressBar(
                        progress = state.indexingProgress,
                        statusText = status,
                        isError = state.indexingNotice?.severity == NoticeSeverity.Error,
                    )
                    if (shouldShowRagIndexActions(
                            hasNotice = state.indexingNotice != null,
                            canRetryPendingIndex = state.canRetryPendingRenameIndex,
                        )
                    ) {
                        if (retryAction != null) {
                            InfinityPillRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                InfinityPillButton(
                                    text = stringResource(R.string.common_dismiss),
                                    onClick = actions.onDismissIndexNotice,
                                    enabled = !retrying,
                                )
                                InfinityPillButton(
                                    text = stringResource(R.string.shared_btn_retry),
                                    onClick = retryAction,
                                    tone = InfinityPillTone.PRIMARY,
                                    enabled = !retrying,
                                )
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                InfinityPillButton(
                                    text = stringResource(R.string.common_dismiss),
                                    onClick = actions.onDismissIndexNotice,
                                    enabled = !retrying,
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.contentState) {
                    // 加载、失败、空与文档之间淡入淡出；增删文档时其余条目平滑补位。
                    RagFolderContentState.Loading -> item("loading") { Box(Modifier.animateItem()) { RagFolderLoadingState() } }
                    RagFolderContentState.Error -> item("error") { Box(Modifier.animateItem()) { RagFolderLoadErrorState(actions.onRetryLoad) } }
                    RagFolderContentState.Empty -> item("empty") { Box(Modifier.animateItem()) { RagFolderEmptyState() } }
                    RagFolderContentState.Content -> items(state.documents, key = { it.uuid }) { doc ->
                        Box(Modifier.animateItem()) { RagDocItem(
                            title = doc.name.ifBlank { doc.uuid },
                            status = if (doc.vectorizedAt != null) RagStatus.READY else RagStatus.PENDING,
                            isSelected = doc.uuid in state.selectedIds,
                            showCheckbox = shouldShowRagDocumentCheckbox(),
                            onCheckedChange = { checked ->
                                if (checked && doc.uuid !in state.selectedIds) state.selectedIds.add(doc.uuid)
                                else if (!checked) state.selectedIds.remove(doc.uuid)
                            },
                            fileSize = formatFileSize(doc.sizeBytes),
                            date = formatDate(doc.updatedAt),
                            onClick = { state.workspaceRootUuid?.let { actions.onOpenDocument(it, doc.uuid) } },
                        ) }
                    }
                }
            }
            Button(
                onClick = actions.onUpload,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag(UiTags.RAG_FOLDER_UPLOAD),
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                Text(stringResource(R.string.rag_folder_upload), Modifier.padding(start = 8.dp))
            }
        }
    }

    if (showMoveSheet) {
        InfinityModalSheet(
            onDismissRequest = { if (!state.isMovingDocuments) showMoveSheet = false },
            height = InfinitySheetHeight.Content,
            modifier = Modifier.testTag(UiTags.RAG_FOLDER_MOVE_SHEET),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.rag_home_move_to_folder), style = MaterialTheme.typography.titleLarge)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(UiTags.RAG_FOLDER_MOVE_LIST),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.folders.filterNot { it.id == state.currentFolderId },
                        key = { it.id },
                    ) { folder ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(
                                    enabled = !state.isMovingDocuments,
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.rag_folder_cd_move_to, folder.name),
                                ) {
                                    val movingIds = state.selectedIds.toList()
                                    actions.onMove(movingIds, folder.id) { succeeded, failedIds ->
                                        applyMoveSelectionResult(state.selectedIds, movingIds.toSet(), failedIds.toSet(), succeeded)
                                        if (succeeded) showMoveSheet = false
                                    }
                                },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            leadingContent = { Icon(Icons.Rounded.Folder, null) },
                            headlineContent = { Text(folder.name) },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        InfinityConfirmDialog(
            modifier = Modifier.testTag(UiTags.RAG_FOLDER_DELETE_CONFIRM_DIALOG),
            onDismissRequest = { if (!state.isDeletingDocuments) showDeleteConfirm = false },
            title = stringResource(R.string.rag_folder_delete_confirm_title, state.selectedIds.size),
            message = stringResource(R.string.shared_action_move_to_recycle_bin),
            confirmText = stringResource(R.string.shared_btn_move_to_recycle_bin),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                val deletingIds = state.selectedIds.toList()
                actions.onDelete(deletingIds) { succeeded, failedIds ->
                    applyMoveSelectionResult(state.selectedIds, deletingIds.toSet(), failedIds.toSet(), succeeded)
                    if (succeeded && failedIds.isEmpty()) showDeleteConfirm = false
                }
            },
            // 删除中取消按钮原为禁用；统一对话框无禁用态，改为删除期间忽略点击。
            onCancel = { showDeleteConfirm = false },
            cancelEnabled = !state.isDeletingDocuments,
            isDestructive = true,
            confirmEnabled = !state.isDeletingDocuments,
            confirmButtonModifier = Modifier.testTag(UiTags.RAG_FOLDER_DELETE_CONFIRM_BUTTON),
        )
    }
}

@Composable
private fun RagFolderEmptyState() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Description, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.rag_folder_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.rag_folder_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RagFolderLoadingState() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.shared_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RagFolderLoadErrorState(onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.common_cd_failed), color = MaterialTheme.colorScheme.error)
            InfinityRetryButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "${"%.1f".format(bytes / Math.pow(1024.0, group.toDouble()))} ${units[group]}"
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
