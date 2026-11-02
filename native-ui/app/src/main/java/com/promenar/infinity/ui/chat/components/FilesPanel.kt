package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.repository.CompositeWorkspaceConstants
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.IExternalFolderManager
import com.promenar.infinity.ui.common.FileIndexStatus
import com.promenar.infinity.ui.common.IndexStatusBadge
import com.promenar.infinity.ui.common.KgStatus
import com.promenar.infinity.ui.common.KgStatusIcon
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.rememberInfinityMenuTouch
import com.promenar.infinity.ui.common.infinityMenuTouch
import com.promenar.infinity.ui.common.infinityMenuHost

internal enum class FileNodeActivation {
    ToggleSelection,
    ToggleExpansion,
    NavigateFolder,
    OpenFile,
}

internal fun resolveFileNodeActivation(
    isMultiSelectMode: Boolean,
    isDirectory: Boolean,
    hasFolderClick: Boolean,
    hasFileClick: Boolean,
): FileNodeActivation? = when {
    isMultiSelectMode -> FileNodeActivation.ToggleSelection
    isDirectory && hasFolderClick -> FileNodeActivation.NavigateFolder
    isDirectory -> FileNodeActivation.ToggleExpansion
    hasFileClick -> FileNodeActivation.OpenFile
    else -> null
}

internal fun supportsFileMultiSelect(
    hasReindex: Boolean,
    hasDelete: Boolean,
): Boolean = hasReindex || hasDelete

/** 选择由调用方接管（工作区浏览器自带选择栏）时，面板不再叠出读内部选择集的批量栏。 */
internal fun showsFilesPanelBatchBar(
    showSelectionOverlay: Boolean,
    selectionOwnedExternally: Boolean,
    isMultiSelectMode: Boolean,
): Boolean = showSelectionOverlay && !selectionOwnedExternally && isMultiSelectMode

internal fun hasFileNodeMenuActions(
    isDirectory: Boolean,
    hasReindex: Boolean,
    hasDelete: Boolean,
    hasRename: Boolean,
    hasMove: Boolean,
    hasExtractKG: Boolean,
    hasViewKG: Boolean,
    hasCopy: Boolean,
): Boolean = hasDelete || hasRename || hasViewKG ||
    (!isDirectory && (hasReindex || hasMove || hasExtractKG || hasCopy))

@Composable
internal fun FilesPanel(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    sessionId: String? = null,
    externalFolderManager: IExternalFolderManager? = null,
    searchQuery: String = "",
    useScroll: Boolean = true,
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    onViewKG: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    folders: List<FileEntry> = emptyList(),
    rootFiles: List<FileEntry>? = null,
    initiallyExpandedIds: Set<String> = emptySet(),
    initialChildrenByParent: Map<String, List<FileEntry>> = emptyMap(),
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
    /** 根目录为空时在空状态中提供导入入口。 */
    onImport: (() -> Unit)? = null,
    externalSelectedIds: MutableList<String>? = null,
    showSelectionOverlay: Boolean = true,
    onFolderClick: ((String, String) -> Unit)? = null,
    onFileClick: ((String) -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis(),
    currentParentUuid: String? = null,
    allowTreeExpansion: Boolean = true,
    /** 点按图片、视频、音频文件时调用（[onFileClick] 为空时生效），其它文件行为不变。 */
    onMediaFileClick: ((FileEntry) -> Unit)? = null,
    /**
     * 提供时改用目录工作区式的长按多选：长按与多选期间的点按都切换选择，不弹出条目菜单，
     * 选择状态由调用方持有（[selectionKeys]），底部操作栏也由调用方提供。
     */
    onToggleSelection: ((FileEntry) -> Unit)? = null,
    selectionKeys: Set<String> = emptySet(),
    /** 列表卡上方的小号分组标题；null 时不显示。 */
    groupLabel: String? = null,
    /** 外部文件夹入口卡与列表卡共用的水平边距，保证两张卡同宽对齐。 */
    contentHorizontalPadding: Dp = 0.dp,
    /** 默认隐藏 MediaStore 回收区留下的 `.trashed-*`、`.pending-*` 条目，只影响显示。 */
    hideSystemTrash: Boolean = true,
) {
    val rootsFlow = remember(rootFiles, workspaceRootUuid, workspaceRepo) {
        if (rootFiles == null) {
            val parent = currentParentUuid ?: workspaceRootUuid
            workspaceRootUuid?.let { root ->
                parent?.let { p -> workspaceRepo.observeChildren(root, p) } ?: flowOf(emptyList())
            } ?: flowOf(emptyList())
        } else flowOf(rootFiles)
    }
    val loadedRoots by rootsFlow.collectAsState(initial = rootFiles.orEmpty())
    val rootPartition = partitionSystemTrashIf(hideSystemTrash, loadedRoots)
    val roots = rootPartition.visible

    val searchVisibleIds by produceState<Set<String>?>(
        initialValue = null,
        workspaceRootUuid,
        workspaceRepo,
        searchQuery,
    ) {
        val rootUuid = workspaceRootUuid
        if (rootUuid == null || searchQuery.isBlank()) {
            value = null
        } else {
            workspaceRepo.searchByName(rootUuid, searchQuery).collectLatest { matches ->
                value = resolveSearchVisibleIds(matches, rootUuid) { uuid ->
                    workspaceRepo.getByUuid(rootUuid, uuid)
                }
            }
        }
    }
    val filteredRoots = if (searchQuery.isBlank()) roots else {
        val visibleIds = searchVisibleIds.orEmpty()
        roots.filter { it.uuid in visibleIds }
    }
    val panelState = resolveFilesPanelUiState(
        workspaceReady = workspaceRootUuid != null,
        isLoading = isLoading,
        hasLoadError = hasLoadError,
        itemCount = roots.size,
        filteredItemCount = filteredRoots.size,
        searchQuery = searchQuery,
    )

    val childrenByParent = remember(workspaceRootUuid, workspaceRepo, initialChildrenByParent) {
        mutableStateMapOf<String, List<FileEntry>>().apply { putAll(initialChildrenByParent) }
    }
    var explicitlyExpandedIds by rememberSaveable(workspaceRootUuid, initiallyExpandedIds) {
        mutableStateOf(ArrayList(initiallyExpandedIds))
    }
    var explicitlyCollapsedIds by rememberSaveable(workspaceRootUuid) {
        mutableStateOf(arrayListOf<String>())
    }
    val expansionOverrides = remember(explicitlyExpandedIds, explicitlyCollapsedIds) {
        buildMap {
            explicitlyExpandedIds.forEach { put(it, true) }
            explicitlyCollapsedIds.forEach { put(it, false) }
        }
    }
    val childPartitions = childrenByParent.mapValues { (_, children) -> partitionSystemTrashIf(hideSystemTrash, children) }
    val shownChildren = childPartitions.mapValues { (_, partition) -> partition.visible }
    val projectedChildren = if (searchQuery.isBlank()) {
        shownChildren
    } else {
        val visibleIds = searchVisibleIds.orEmpty()
        shownChildren.mapValues { (_, children) -> children.filter { it.uuid in visibleIds } }
    }
    val forceExpandedIds = if (searchQuery.isBlank()) emptySet() else searchVisibleIds.orEmpty()
    val visibleNodes = projectVisibleFileNodes(
        roots = filteredRoots,
        childrenByParent = projectedChildren,
        expansionOverrides = expansionOverrides,
        forceExpandedIds = forceExpandedIds,
        allowTreeExpansion = allowTreeExpansion,
    )
    val composedNodeRefCounts = remember(workspaceRootUuid, workspaceRepo) {
        mutableStateMapOf<String, Int>()
    }
    val expandedDirectoryIds = if (!allowTreeExpansion) {
        emptySet()
    } else {
        visibleNodes.asSequence()
            .filter { node ->
                node.file.isDirectory && isFileNodeExpanded(
                    uuid = node.file.uuid,
                    depth = node.depth,
                    expansionOverrides = expansionOverrides,
                    forceExpandedIds = forceExpandedIds,
                )
            }
            .mapTo(linkedSetOf()) { it.file.uuid }
    }
    val hiddenSystemTrashCount = countHiddenSystemTrash(
        rootHidden = rootPartition.hiddenCount,
        hiddenByParent = childPartitions.mapValues { (_, partition) -> partition.hiddenCount },
        expandedVisibleDirectoryIds = expandedDirectoryIds,
    )
    val requiredDirectorySubscriptionIds = resolveRequiredDirectorySubscriptionIds(
        visibleNodes = visibleNodes,
        visibleNodeIds = composedNodeRefCounts.keys.toSet(),
        expandedDirectoryIds = expandedDirectoryIds,
    )
    requiredDirectorySubscriptionIds.forEach { parentUuid ->
        key(parentUuid) {
            LaunchedEffect(workspaceRootUuid, workspaceRepo, parentUuid) {
                val rootUuid = workspaceRootUuid ?: return@LaunchedEffect
                workspaceRepo.observeChildren(rootUuid, parentUuid).collectLatest { children ->
                    childrenByParent[parentUuid] = children
                }
            }
        }
    }

    // 多选状态
    val localSelectedIds = remember { mutableStateListOf<String>() }
    val selectedIds = externalSelectedIds ?: localSelectedIds
    val supportsMultiSelect = supportsFileMultiSelect(
        hasReindex = onReindex != null,
        hasDelete = onDelete != null,
    )
    val isMultiSelectMode = if (onToggleSelection != null) selectionKeys.isNotEmpty() else selectedIds.isNotEmpty() && supportsMultiSelect
    val showBatchBar = showsFilesPanelBatchBar(
        showSelectionOverlay = showSelectionOverlay,
        selectionOwnedExternally = onToggleSelection != null,
        isMultiSelectMode = isMultiSelectMode,
    )
    var deleteFailure by remember { mutableStateOf<FileBatchOperationResult?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    val requestDelete: (Collection<String>) -> Unit = request@{ ids ->
        val deleteAction = onDelete ?: return@request
        val attemptedIds = ids.distinct()
        if (attemptedIds.isEmpty() || isDeleting) return@request
        isDeleting = true
        deleteAction(attemptedIds) { result ->
            reduceSelectionAfterFileOperation(selectedIds, result)
            deleteFailure = result.takeIf { it.failedIds.isNotEmpty() }
            isDeleting = false
        }
    }

    val toggleExpanded: (VisibleFileNode) -> Unit = { node ->
        val currentlyExpanded = isFileNodeExpanded(
            uuid = node.file.uuid,
            depth = node.depth,
            expansionOverrides = expansionOverrides,
            forceExpandedIds = forceExpandedIds,
        )
        val id = node.file.uuid
        if (currentlyExpanded) {
            explicitlyExpandedIds = ArrayList(explicitlyExpandedIds).apply { remove(id) }
            explicitlyCollapsedIds = ArrayList(explicitlyCollapsedIds).apply { if (id !in this) add(id) }
        } else {
            explicitlyCollapsedIds = ArrayList(explicitlyCollapsedIds).apply { remove(id) }
            explicitlyExpandedIds = ArrayList(explicitlyExpandedIds).apply { if (id !in this) add(id) }
        }
    }

    val content = @Composable { node: VisibleFileNode ->
        val expanded = isFileNodeExpanded(
            uuid = node.file.uuid,
            depth = node.depth,
            expansionOverrides = expansionOverrides,
            forceExpandedIds = forceExpandedIds,
        )
        DisposableEffect(node.file.uuid, workspaceRootUuid, workspaceRepo) {
            composedNodeRefCounts[node.file.uuid] = (composedNodeRefCounts[node.file.uuid] ?: 0) + 1
            onDispose {
                val remaining = (composedNodeRefCounts[node.file.uuid] ?: 1) - 1
                if (remaining > 0) {
                    composedNodeRefCounts[node.file.uuid] = remaining
                } else {
                    composedNodeRefCounts.remove(node.file.uuid)
                }
            }
        }
        FileTreeRow(
            node = node,
            expanded = expanded,
            onToggleExpanded = { toggleExpanded(node) },
            workspaceRootUuid = workspaceRootUuid!!,
            workspaceRepo = workspaceRepo,
            onReindex = onReindex,
            onDelete = onDelete?.let { { id -> requestDelete(listOf(id)) } },
            onRename = onRename,
            onMove = onMove,
            onExtractKG = onExtractKG,
            onViewKG = onViewKG,
            onCopy = onCopy,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            selectedIds = selectedIds,
            isMultiSelectMode = isMultiSelectMode,
            onFolderClick = onFolderClick,
            onFileClick = onFileClick ?: onMediaFileClick
                ?.takeIf { isPreviewableMediaEntry(node.file) }
                ?.let { openMedia -> { _: String -> openMedia(node.file) } },
            folders = folders,
            nowMillis = nowMillis,
            allowTreeExpansion = allowTreeExpansion,
            onToggleSelection = onToggleSelection,
            selectionKeys = selectionKeys,
            childCount = if (node.file.isDirectory) loadedChildCount(node.file.uuid, shownChildren) else null,
        )
    }

    Box(modifier = if (useScroll) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        Column(
            modifier = (if (useScroll) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(bottom = if (showBatchBar) 128.dp else 0.dp),
        ) {
            val bodyModifier = if (useScroll) Modifier.weight(1f) else Modifier.fillMaxWidth()
            externalFolderManager?.let { manager ->
                ExternalFoldersPanel(
                    sessionId = sessionId,
                    workspaceRootUuid = workspaceRootUuid,
                    manager = manager,
                    onReindex = onReindex,
                    modifier = Modifier
                        .padding(horizontal = contentHorizontalPadding)
                        .padding(top = InfinitySpacing.XSmall),
                )
            }
            val lastDeleteFailure = remember { arrayOfNulls<FileBatchOperationResult>(1) }
            deleteFailure?.let { lastDeleteFailure[0] = it }
            AnimatedVisibility(visible = deleteFailure != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                // 收起动画期间沿用最后一次失败信息。
                val failure = lastDeleteFailure[0] ?: return@AnimatedVisibility
                val failedLabel = stringResource(R.string.common_cd_failed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = failedLabel
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(failedLabel, style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.error)
                    val failureActionColors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = { requestDelete(failure.failedIds) },
                        enabled = !isDeleting,
                        colors = failureActionColors,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.shared_btn_retry))
                    }
                    IconButton(
                        onClick = { deleteFailure = null },
                        enabled = !isDeleting,
                        colors = failureActionColors,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss))
                    }
                }
            }
            groupLabel?.let { label ->
                FilesPanelGroupLabel(label, Modifier.padding(horizontal = contentHorizontalPadding))
            }
            // 加载、空、错误与文件列表之间交叉淡入，不再整块跳变。
            val stateModifier = if (useScroll) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            AnimatedContent(
                targetState = panelState,
                transitionSpec = { InfinityMotion.fadeThrough() },
                modifier = bodyModifier,
                label = "files_panel_state",
            ) { shownState -> when (shownState) {
                FilesPanelUiState.Loading -> FilesPanelLoadingState(stateModifier)
                FilesPanelUiState.Error -> FilesPanelErrorState(
                    modifier = stateModifier,
                    onRetry = onRetryLoad,
                )
                FilesPanelUiState.Empty -> Column(stateModifier) {
                    EmptyFilesState(if (useScroll) Modifier.weight(1f) else Modifier, onImport)
                    HiddenSystemTrashFooter(hiddenSystemTrashCount, Modifier.padding(horizontal = contentHorizontalPadding))
                }
                FilesPanelUiState.SearchEmpty -> SearchEmptyFilesState(stateModifier)
                // 列表卡按内容收拢高度（最多占满剩余空间），隐藏计数紧跟在卡片下方。
                FilesPanelUiState.Content -> Column(stateModifier.padding(horizontal = contentHorizontalPadding)) {
                    Surface(
                        modifier = (if (useScroll) Modifier.weight(1f, fill = false) else Modifier)
                            .fillMaxWidth()
                            .padding(top = if (groupLabel != null) 0.dp else InfinitySpacing.Small, bottom = InfinitySpacing.XSmall),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (useScroll) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("files_panel_tree_list"),
                            ) {
                                itemsIndexed(
                                    items = visibleNodes,
                                    key = { _, node -> node.file.uuid },
                                    contentType = { _, node -> if (node.file.isDirectory) "directory" else "file" },
                                ) { index, node ->
                                    Column(Modifier.animateItem()) {
                                        if (index > 0) FilesPanelRowDivider()
                                        content(node)
                                    }
                                }
                            }
                        } else {
                            // 外层页面拥有滚动时保持非滚动容器，但仍以 UUID key 渲染单层投影。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
                                    .testTag("files_panel_tree_list"),
                            ) {
                                visibleNodes.forEachIndexed { index, node ->
                                    key(node.file.uuid) {
                                        if (index > 0) FilesPanelRowDivider()
                                        content(node)
                                    }
                                }
                            }
                        }
                    }
                    HiddenSystemTrashFooter(hiddenSystemTrashCount)
                }
            } }
        }

        // 底部批量操作栏：统一在真实屏幕底部居中，正文已预留空间。
        FilesPanelSelectionOverlay(
            visible = showBatchBar,
            selectedCount = selectedIds.size,
            onClear = { selectedIds.clear() },
            onReindexAll = onReindex?.let { reindexFn ->
                { selectedIds.forEach { id -> reindexFn(id) }; selectedIds.clear() }
            },
            onDeleteAll = if (onDelete != null) {
                { requestDelete(selectedIds.toList()) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun BoxScope.FilesPanelSelectionOverlay(
    selectedCount: Int,
    onClear: () -> Unit,
    onReindexAll: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .testTag("files_panel_selection_bar"),
        enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(300)) +
            fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
        exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(200)) +
            fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
    ) {
        BatchActionBar(
            selectedCount = selectedCount,
            onClear = onClear,
            onReindexAll = onReindexAll,
            onDeleteAll = onDeleteAll,
        )
    }
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onReindexAll: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.95f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.files_selected_count, selectedCount),
                    style = InfinityTypography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 取消多选是退出选择，用行尾关闭图标。
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.files_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onReindexAll != null || onDeleteAll != null) {
                InfinityPillRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    reverseWhenStacked = false,
                ) {
                    if (onReindexAll != null) {
                        InfinityPillButton(
                            text = stringResource(R.string.files_reindex),
                            onClick = onReindexAll,
                            icon = Icons.Rounded.Refresh,
                        )
                    }
                    if (onDeleteAll != null) {
                        InfinityPillButton(
                            text = stringResource(R.string.shared_btn_move_to_recycle_bin),
                            onClick = onDeleteAll,
                            tone = InfinityPillTone.DANGER,
                            icon = Icons.Rounded.DeleteOutline,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: VisibleFileNode,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    workspaceRootUuid: String,
    workspaceRepo: IWorkspaceRepository,
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    onViewKG: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    selectedIds: MutableList<String> = mutableStateListOf(),
    isMultiSelectMode: Boolean = false,
    onFolderClick: ((String, String) -> Unit)? = null,
    onFileClick: ((String) -> Unit)? = null,
    folders: List<FileEntry> = emptyList(),
    nowMillis: Long,
    allowTreeExpansion: Boolean = true,
    onToggleSelection: ((FileEntry) -> Unit)? = null,
    selectionKeys: Set<String> = emptySet(),
    childCount: Int? = null,
) {
    val file = node.file
    var showMenu by rememberSaveable(file.uuid, "menu") { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    var showRenameDialog by rememberSaveable(file.uuid, "rename") { mutableStateOf(false) }
    var showMoveSheet by rememberSaveable(file.uuid, "move") { mutableStateOf(false) }
    val isSelected = if (onToggleSelection != null) file.uuid in selectionKeys else file.uuid in selectedIds
    val optionsLabel = stringResource(R.string.chat_cd_options)
    val isVirtualRoot = CompositeWorkspaceConstants.isVirtualSessionWorkspacesRoot(file.uuid)
    val isVirtualSession = CompositeWorkspaceConstants.isVirtualSessionFolder(file.uuid)
    val isVirtualNode = isVirtualRoot || isVirtualSession
    val displayName = fileDisplayName(file, isVirtualRoot)
    val supportsMultiSelect = supportsFileMultiSelect(
        hasReindex = onReindex != null,
        hasDelete = onDelete != null,
    )
    val hasMenuActions = !isVirtualRoot && (
        (!isVirtualSession && supportsMultiSelect) || hasFileNodeMenuActions(
            isDirectory = file.isDirectory,
            hasReindex = if (isVirtualSession) false else onReindex != null,
            hasDelete = if (isVirtualSession) false else onDelete != null,
            hasRename = onRename != null,
            hasMove = if (isVirtualSession) false else onMove != null,
            hasExtractKG = !isVirtualSession && onExtractKG != null && com.promenar.infinity.data.rag.DocumentReferenceExtractor.supports(file),
            hasViewKG = if (isVirtualSession) false else onViewKG != null,
            hasCopy = if (isVirtualSession) false else onCopy != null,
        )
    )
    val activation = resolveFileNodeActivation(
        isMultiSelectMode = isMultiSelectMode,
        isDirectory = file.isDirectory,
        hasFolderClick = onFolderClick != null,
        hasFileClick = onFileClick != null,
    )
    val haptics = LocalInfinityHaptics.current
    val handleActivate: (() -> Unit)? = activation?.let { resolvedActivation ->
        {
            when {
                resolvedActivation == FileNodeActivation.ToggleSelection -> {
                    haptics.toggle(!isSelected)
                    when {
                        onToggleSelection != null -> onToggleSelection(file)
                        isSelected -> selectedIds.remove(file.uuid)
                        else -> selectedIds.add(file.uuid)
                    }
                }
                resolvedActivation == FileNodeActivation.NavigateFolder -> onFolderClick?.invoke(file.uuid, displayName)
                isVirtualNode -> if (onFolderClick != null) onFolderClick.invoke(file.uuid, displayName) else onToggleExpanded()
                resolvedActivation == FileNodeActivation.ToggleExpansion -> onToggleExpanded()
                resolvedActivation == FileNodeActivation.OpenFile -> onFileClick?.invoke(file.uuid)
            }
        }
    }

    val isSessionFile = (file.workspaceRootUuid.isNotBlank() && file.workspaceRootUuid != workspaceRootUuid) ||
        CompositeWorkspaceConstants.isVirtualSessionFolder(file.parentUuid)
    // 外部选择模式下长按即选择，虚拟节点不参与。
    val selectable = onToggleSelection != null && !isVirtualNode
    val selectLabel = stringResource(R.string.files_multi_select)

    FileRow(
        file = file,
        depth = node.depth,
        expanded = expanded,
        workspaceRootUuid = workspaceRootUuid,
        indexingFileIds = indexingFileIds,
        kgExtractionStates = kgExtractionStates,
        isMultiSelectMode = isMultiSelectMode,
        isSelected = isSelected,
        onSelectionChange = { checked ->
            if (checked != isSelected) haptics.toggle(checked)
            if (onToggleSelection != null) {
                if (checked != isSelected) onToggleSelection(file)
            } else {
                if (checked && file.uuid !in selectedIds) selectedIds.add(file.uuid)
                if (!checked) selectedIds.remove(file.uuid)
            }
        },
        onOpenMenu = if (hasMenuActions) ({ menuTouch.clear(); showMenu = true }) else null,
        onToggleExpanded = onToggleExpanded,
        // 多选期间点按目录图标也只切换选择，不进入目录。
        onFolderClick = if (onToggleSelection != null && isMultiSelectMode) ({ _, _ -> haptics.toggle(!isSelected); onToggleSelection(file) }) else onFolderClick,
        allowTreeExpansion = allowTreeExpansion,
        childCount = childCount,
        modifier = Modifier
            .testTag("files_panel_node_${file.uuid}")
            .infinityMenuHost(showMenu && hasMenuActions)
            .infinityMenuTouch(menuTouch)
            .then(
                if (selectable) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClickLabel = displayName.takeIf { handleActivate != null },
                        onLongClickLabel = selectLabel,
                        onLongClick = { onToggleSelection?.invoke(file) },
                        onClick = handleActivate ?: {},
                    )
                } else if (handleActivate != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClickLabel = displayName,
                        onLongClickLabel = optionsLabel.takeIf { hasMenuActions },
                        onLongClick = if (hasMenuActions) ({ showMenu = true }) else null,
                        onClick = handleActivate,
                    )
                } else {
                    Modifier
                },
            ),
        nowMillis = nowMillis,
        menuContent = {
            InfinityDropdownMenu(
                expanded = showMenu && hasMenuActions,
                onDismissRequest = { showMenu = false },
                touch = menuTouch,
            ) {
                if (!file.isDirectory) {
                    if (onReindex != null && !isSessionFile) {
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.files_reindex), style = InfinityTypography.labelLarge) },
                            onClick = { showMenu = false; onReindex(file.uuid) },
                            modifier = Modifier.testTag(UiTags.fileNodeReindex(file.uuid)),
                        )
                    }
                    if (onExtractKG != null && !isSessionFile && com.promenar.infinity.data.rag.DocumentReferenceExtractor.supports(file)) {
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.files_extract_knowledge_graph), style = InfinityTypography.labelLarge) },
                            onClick = { showMenu = false; onExtractKG(file.uuid) },
                            modifier = Modifier.testTag("files_panel_extract_kg_action_${file.uuid}"),
                        )
                    }
                    if (onViewKG != null && !isSessionFile) {
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.AccountTree,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.files_view_graph), style = InfinityTypography.labelLarge) },
                            onClick = { showMenu = false; onViewKG(file.uuid) },
                            modifier = Modifier.testTag("files_panel_view_kg_action_${file.uuid}"),
                        )
                    }
                } else if (onViewKG != null && !isVirtualRoot && !isVirtualSession) {
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.AccountTree,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.files_view_folder_graph), style = InfinityTypography.labelLarge) },
                        onClick = { showMenu = false; onViewKG(file.uuid) },
                        modifier = Modifier.testTag("files_panel_view_kg_action_${file.uuid}"),
                    )
                }
                if (onRename != null && !isVirtualRoot) {
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.files_rename), style = InfinityTypography.labelLarge) },
                        onClick = { showMenu = false; showRenameDialog = true },
                        modifier = Modifier.testTag("files_panel_rename_action_${file.uuid}"),
                    )
                }
                if (onMove != null && !file.isDirectory && !isVirtualNode) {
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.files_move_to), style = InfinityTypography.labelLarge) },
                        onClick = { showMenu = false; showMoveSheet = true },
                        modifier = Modifier.testTag("files_panel_move_action_${file.uuid}"),
                    )
                }
                if (onCopy != null && !file.isDirectory) {
                    val isSessionCopy = file.workspaceRootUuid.isNotBlank() && file.workspaceRootUuid != workspaceRootUuid
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = if (isSessionCopy) Icons.Rounded.CloudUpload else Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                stringResource(
                                    if (isSessionCopy) R.string.files_transfer_to_knowledge_base
                                    else R.string.shared_btn_copy
                                ),
                                style = InfinityTypography.labelLarge,
                            )
                        },
                        onClick = { showMenu = false; onCopy(file.uuid) },
                        modifier = Modifier.testTag("files_panel_copy_action_${file.uuid}"),
                    )
                }
                if (!isMultiSelectMode && supportsMultiSelect && !isVirtualNode) {
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Checklist,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.files_multi_select), style = InfinityTypography.labelLarge) },
                        onClick = {
                            showMenu = false
                            if (file.uuid !in selectedIds) selectedIds.add(file.uuid)
                        },
                        modifier = Modifier.testTag(UiTags.fileNodeMultiSelect(file.uuid)),
                    )
                }
                if (onDelete != null && !isVirtualNode) {
                    InfinityMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                stringResource(R.string.shared_btn_move_to_recycle_bin),
                                style = InfinityTypography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onDelete(file.uuid) },
                        modifier = Modifier.testTag("files_panel_delete_action_${file.uuid}"),
                    )
                }
            }
        },
    )

    if (showRenameDialog) {
        RenameDialog(
            fileUuid = file.uuid,
            currentName = file.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename?.invoke(file.uuid, newName)
            },
        )
    }

    if (showMoveSheet) {
        MoveToSheet(
            folders = folders,
            workspaceRepo = workspaceRepo,
            workspaceRootUuid = workspaceRootUuid,
            onDismiss = { showMoveSheet = false },
            onSelect = { targetUuid ->
                showMoveSheet = false
                onMove?.invoke(file.uuid, targetUuid)
            },
        )
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    depth: Int,
    expanded: Boolean,
    workspaceRootUuid: String? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionChange: (Boolean) -> Unit,
    onOpenMenu: (() -> Unit)?,
    onToggleExpanded: (() -> Unit)? = null,
    onFolderClick: ((String, String) -> Unit)? = null,
    allowTreeExpansion: Boolean = true,
    /** 已加载的子项数；仅目录且有现成数据时显示在副标题。 */
    childCount: Int? = null,
    modifier: Modifier = Modifier,
    nowMillis: Long,
    menuContent: (@Composable () -> Unit)? = null,
) {
    val optionsLabel = stringResource(R.string.chat_cd_options)
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val isVirtualRoot = CompositeWorkspaceConstants.isVirtualSessionWorkspacesRoot(file.uuid)
    val isVirtualSession = CompositeWorkspaceConstants.isVirtualSessionFolder(file.uuid)
    val displayName = fileDisplayName(file, isVirtualRoot)
    val isSessionFile = (file.workspaceRootUuid.isNotBlank() && file.workspaceRootUuid != workspaceRootUuid) ||
        CompositeWorkspaceConstants.isVirtualSessionFolder(file.parentUuid)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (boundedFileTreeIndentLevel(depth) * 12).dp),
    ) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = containerColor),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfinityIdentifierText(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isVirtualRoot) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.files_tag_system),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else if (isVirtualSession) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.files_tag_session),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            supportingContent = if (file.isDirectory) {
                childCount?.let { count ->
                    {
                        Text(
                            text = if (count == 0) {
                                stringResource(R.string.files_panel_folder_empty)
                            } else {
                                pluralStringResource(R.plurals.files_panel_folder_child_count, count, count)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfinityMetaLine(
                            segments = fileMetadataSegments(file, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isSessionFile && showsIndexStatus(file)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IndexStatusBadge(status = resolveIndexStatus(file, indexingFileIds))
                                KgStatusIcon(status = resolveKgStatus(file, kgExtractionStates))
                            }
                        }
                    }
                }
            },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMultiSelectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = onSelectionChange,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        )
                    }
                    val folderModifier = if (file.isDirectory) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = {
                                if (onFolderClick != null) onFolderClick(file.uuid, displayName)
                                else onToggleExpanded?.invoke()
                            })
                            .padding(4.dp)
                    } else {
                        Modifier.padding(4.dp)
                    }
                    val iconVector = when {
                        isVirtualRoot -> if (expanded && allowTreeExpansion) Icons.Rounded.FolderOpen else Icons.Rounded.Folder
                        isVirtualSession -> Icons.Rounded.ChatBubble
                        file.isDirectory -> if (expanded && allowTreeExpansion) Icons.Rounded.FolderOpen else Icons.Rounded.Folder
                        else -> fileIcon(file)
                    }
                    val iconTint = when {
                        isVirtualRoot -> MaterialTheme.colorScheme.primary
                        isVirtualSession -> MaterialTheme.colorScheme.tertiary
                        file.isDirectory -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(24.dp)
                            .then(folderModifier),
                    )
                }
            },
            trailingContent = onOpenMenu?.let { openMenu ->
                {
                    Box {
                        IconButton(
                            onClick = openMenu,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag(UiTags.fileNodeOptions(file.uuid)),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = optionsLabel,
                            )
                        }
                        menuContent?.invoke()
                    }
                }
            },
        )
    }
}

@Composable
private fun RenameDialog(
    fileUuid: String,
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.files_rename),
        message = "",
        confirmText = stringResource(R.string.files_confirm),
        cancelText = stringResource(R.string.files_cancel),
        onConfirm = { if (name.isNotBlank()) onConfirm(name.trim()) },
        onCancel = onDismiss,
        confirmEnabled = name.isNotBlank(),
        confirmButtonModifier = Modifier.testTag("files_panel_rename_confirm_$fileUuid"),
        content = {
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.files_new_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("files_panel_rename_input_$fileUuid"),
            )
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoveToSheet(
    folders: List<FileEntry>,
    workspaceRepo: IWorkspaceRepository,
    workspaceRootUuid: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val dirs by workspaceRepo.observeChildren(workspaceRootUuid, workspaceRootUuid)
        .collectAsState(initial = emptyList())
    val directoryList = folders.ifEmpty { dirs.filter { it.isDirectory } }

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Content) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.files_move_to), style = MaterialTheme.typography.headlineSmall)
            if (directoryList.isEmpty()) {
                Text(
                    stringResource(R.string.files_no_available_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .testTag("files_panel_move_list"),
            ) {
                item(key = workspaceRootUuid) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.files_root_directory)) },
                        leadingContent = {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(workspaceRootUuid) }
                            .testTag("files_panel_move_destination_$workspaceRootUuid"),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
                items(directoryList, key = { it.uuid }) { dir ->
                    ListItem(
                        headlineContent = {
                            Text(dir.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(dir.uuid) }
                            .testTag("files_panel_move_destination_${dir.uuid}"),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
            }
        }
    }
}

private fun partitionSystemTrashIf(enabled: Boolean, entries: List<FileEntry>): SystemTrashPartition<FileEntry> =
    if (enabled) partitionSystemTrash(entries) { it.name } else SystemTrashPartition(entries, 0)

/** 列表卡上方的小号分组标题，左缘与卡内行文字对齐。 */
@Composable
internal fun FilesPanelGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = InfinityTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = InfinitySpacing.RowHorizontal,
                end = InfinitySpacing.RowHorizontal,
                top = InfinitySpacing.Large,
                bottom = InfinitySpacing.SectionHeaderBottom,
            )
            .testTag(TAG_FILES_PANEL_GROUP_LABEL),
    )
}

@Composable
private fun FilesPanelRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** 列表下方的一行小字，说明隐藏了多少系统回收区文件；为 0 时不占位。 */
@Composable
internal fun HiddenSystemTrashFooter(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Text(
        text = pluralStringResource(R.plurals.files_panel_hidden_system_trash, count, count),
        style = InfinityTypography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small)
            .testTag(TAG_FILES_PANEL_HIDDEN_TRASH),
    )
}

private const val TAG_FILES_PANEL_GROUP_LABEL = "files_panel_group_label"
private const val TAG_FILES_PANEL_HIDDEN_TRASH = "files_panel_hidden_system_trash"

@Composable
private fun FilesPanelLoadingState(modifier: Modifier = Modifier) {
    val loadingLabel = stringResource(R.string.shared_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = loadingLabel
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            loadingLabel,
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilesPanelErrorState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val errorLabel = stringResource(R.string.resource_explorer_error_workspace_load_failed)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                stateDescription = errorLabel
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            errorLabel,
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        onRetry?.let { retry ->
            Spacer(modifier = Modifier.height(12.dp))
            InfinityRetryButton(onClick = retry)
        }
    }
}

@Composable
private fun SearchEmptyFilesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.rag_home_search_results), style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.files_empty_title), style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyFilesState(modifier: Modifier = Modifier, onImport: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.FolderOpen,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.files_empty_title), style = InfinityTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.files_empty_subtitle),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onImport != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_EMPTY_IMPORT)) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.resource_explorer_import_files))
            }
        }
    }
}

/** 资源管理器中可直接预览或播放的文件：图片、视频、音频。 */
internal fun isPreviewableMediaEntry(file: FileEntry): Boolean {
    if (file.isDirectory) return false
    val topLevel = file.mimeType?.substringBefore('/')?.lowercase()
    if (topLevel == "image" || topLevel == "video" || topLevel == "audio") return true
    return isPreviewableMediaName(file.name)
}

/** 图片、音视频不在向量化支持范围内，显示“等待中”只会误导，资源管理器不为其显示索引与图谱状态。 */
internal fun showsIndexStatus(file: FileEntry): Boolean = !isPreviewableMediaEntry(file)

private fun fileIcon(file: FileEntry): ImageVector {
    if (file.isDirectory) return Icons.Rounded.Folder
    return when (file.mimeType?.substringBefore("/")?.lowercase()) {
        "image" -> Icons.Rounded.Image
        "audio" -> Icons.Rounded.AudioFile
        "video" -> Icons.Rounded.Movie
        "text", "application" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun resolveIndexStatus(file: FileEntry, indexingFileIds: Set<String> = emptySet()): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.uuid in indexingFileIds) return FileIndexStatus.INDEXING
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    if (file.updatedAt > (file.vectorizedAt ?: 0)) return FileIndexStatus.STALE
    return FileIndexStatus.INDEXED
}

internal fun resolveKgStatus(file: FileEntry, kgExtractionStates: Map<String, KgStatus>): KgStatus {
    val state = kgExtractionStates[file.uuid]
    if (state == KgStatus.IN_PROGRESS || state == KgStatus.FAILED || state == KgStatus.PARTIAL) return state
    if (file.kgStrategy?.takeIf { it.startsWith("partial:") }?.substringAfterLast(':')?.toLongOrNull() == file.updatedAt) return KgStatus.PARTIAL
    if ((file.kgExtractedAt ?: 0) >= file.updatedAt && file.kgExtractedAt != null) return KgStatus.COMPLETED
    return KgStatus.NOT_STARTED
}

@Composable
private fun fileMetadataSegments(file: FileEntry, nowMillis: Long): List<String> =
    listOf(formatFileSize(file.sizeBytes), formatRelativeTime(file.updatedAt, nowMillis))

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.0f".format(value)} ${units[digitGroups]}"
}

@Composable
private fun formatRelativeTime(timestamp: Long, nowMillis: Long): String {
    val diff = nowMillis - timestamp
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("MMM d", locale).format(Date(timestamp))
    }
}

/** 虚拟“会话工作区”根目录按当前界面语言显示；数据层生成的名称在运行中切换语言后不会更新。 */
@Composable
private fun fileDisplayName(file: FileEntry, isVirtualRoot: Boolean): String =
    if (isVirtualRoot) stringResource(R.string.files_session_workspaces_title) else file.name
