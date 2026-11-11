package com.promenar.infinity.ui.rag

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Check
import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.KgDocumentOption
import com.promenar.infinity.data.rag.MAX_GRAPH_NODE_LIMIT
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPageSearch
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.infinity.ui.rag.canvas.InteractiveGraphCanvas
import com.promenar.infinity.ui.rag.canvas.KgCanvasHighlight
import com.promenar.infinity.ui.rag.canvas.KgEntityKind
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.common.ProvideGroupedPress

@Composable
fun KnowledgeGraphScreen(
    viewModel: KnowledgeGraphViewModel = viewModel(factory = KnowledgeGraphViewModel.factory(LocalContext.current.applicationContext as Application)),
    /** 从“本轮记忆”面板进入时预填的实体搜索，图谱首次加载完成后执行一次。 */
    initialQuery: String? = null,
    onNavigateBack: () -> Unit,
    /** 实体详情里点“打开”时进入对应会话。 */
    onOpenSession: (String) -> Unit = {},
) {
    val nodes by viewModel.nodes.collectAsState()
    val edges by viewModel.edges.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedDocumentId by viewModel.selectedDocumentId.collectAsState()
    val documentSelectionRequired by viewModel.documentSelectionRequired.collectAsState()
    val documentOptions by viewModel.documentOptions.collectAsState()
    val documentOptionsError by viewModel.documentOptionsError.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val sessionSelectionRequired by viewModel.sessionSelectionRequired.collectAsState()
    val sessionOptions by viewModel.sessionOptions.collectAsState()
    val sessionOptionsError by viewModel.sessionOptionsError.collectAsState()
    val sessionTurns by viewModel.sessionTurns.collectAsState()
    val selectedTurnId by viewModel.selectedTurnId.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val selectedEntity by viewModel.selectedEntity.collectAsState()
    val centerRequest by viewModel.centerRequest.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val graphState by viewModel.graphState.collectAsState()
    val totalNodeCount by viewModel.totalNodeCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchFailed by viewModel.searchFailed.collectAsState()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showDocumentSelector by remember { mutableStateOf(false) }
    var showSessionSelector by remember { mutableStateOf(false) }
    var showCollectionSelector by remember { mutableStateOf(false) }
    val collectionOptions by viewModel.collectionOptions.collectAsState()
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsState()
    val sharedGraphTitle = stringResource(R.string.kg_spaces_shared_collection_title)
    var showAccessibilityList by remember { mutableStateOf(false) }
    var resetKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 维持 Native 图谱物理仿真器的协程生命周期
    val scope = rememberCoroutineScope()
    val simulator = remember(scope) { GraphPhysicsSimulator(scope) }

    var initialQueryApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialQuery, isLoading) {
        if (!initialQueryApplied && !isLoading && !initialQuery.isNullOrBlank()) {
            initialQueryApplied = true
            searchActive = true
            viewModel.searchEntities(initialQuery)
        }
    }

    LaunchedEffect(nodes, edges) {
        simulator.setData(nodes, edges)
    }

    // 从合集设置等页面返回时，篇序可能已变：重读时间轴刻度。
    LaunchedEffect(viewModel) { viewModel.refreshTimelineOnShow() }

    // 导航离开或应用退到后台时立即暂停布局与动画。
    DisposableEffect(simulator, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            simulator.setPageVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        simulator.setPageVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            simulator.clear()
        }
    }

    val selectionRequired = documentSelectionRequired || sessionSelectionRequired
    val selectedTurn = sessionTurns.firstOrNull { it.messageId == selectedTurnId }
    val highlight = remember(selectedEntity?.nodeId, selectedTurn, typeFilter) {
        KgCanvasHighlight(
            selectedId = selectedEntity?.nodeId,
            turnNodeIds = selectedTurn?.nodeIds?.toSet(),
            newNodeIds = selectedTurn?.newNodeIds?.toSet().orEmpty(),
            typeFilter = typeFilter,
        )
    }
    val viewLabel = stringResource(
        when (viewMode) {
            KgViewMode.GLOBAL -> R.string.kg_view_global
            KgViewMode.DOCUMENT -> R.string.kg_view_document
            KgViewMode.SESSION -> R.string.kg_view_session
        },
    )
    val selectedSession = sessionOptions.firstOrNull { it.sessionId == selectedSessionId }
    val scopeLabel = when (viewMode) {
        KgViewMode.GLOBAL -> collectionOptions.firstOrNull { it.id == selectedCollectionId }
            ?.let { kgCollectionTitle(it, sharedGraphTitle) }
            ?: if (selectedCollectionId == "shared") sharedGraphTitle else stringResource(R.string.ks_graph_page_select)
        KgViewMode.DOCUMENT -> documentOptions.firstOrNull { it.docId == selectedDocumentId }?.title
            ?: stringResource(R.string.kg_select_document)
        KgViewMode.SESSION -> when {
            selectedSessionId == null -> stringResource(R.string.kg_select_session)
            selectedSession?.title.isNullOrBlank() -> stringResource(R.string.kg_session_untitled)
            else -> selectedSession!!.title
        }
    }
    val scopeMeta = buildList {
        if (selectionRequired) return@buildList
        add(
            when (viewMode) {
                KgViewMode.GLOBAL -> stringResource(R.string.kg_scope_meta_global, totalNodeCount, edges.size)
                KgViewMode.DOCUMENT -> stringResource(R.string.kg_scope_meta_document, totalNodeCount)
                KgViewMode.SESSION -> stringResource(R.string.kg_scope_meta_session, sessionTurns.size, totalNodeCount)
            },
        )
        // 子图有界读取：未全部显示时说明当前显示数。
        if (nodes.isNotEmpty() && nodes.size < totalNodeCount) {
            add(stringResource(R.string.kg_graph_displayed_count, nodes.size, totalNodeCount))
        }
    }

    InfinitySettingsPageLayout(
        title = stringResource(R.string.kg_title),
        onBack = onNavigateBack,
        horizontalContentPadding = 0.dp,
        search = if (selectionRequired) {
            null
        } else {
            InfinityPageSearch(
                query = searchQuery,
                active = searchActive,
                onQueryChange = viewModel::searchEntities,
                onActiveChange = { searchActive = it },
                placeholder = stringResource(R.string.kg_graph_search_entities),
            )
        },
        actions = {
            if (!searchActive) {
                IconButton(
                    onClick = { showAccessibilityList = true },
                    enabled = nodes.isNotEmpty(),
                    modifier = Modifier.testTag(UiTags.KG_LIST_ENTRY),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = stringResource(R.string.kg_open_accessibility_list))
                }
                IconButton(onClick = viewModel::refreshCurrentScope, modifier = Modifier.testTag(UiTags.KG_REFRESH)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.workbench_refresh))
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            KnowledgeGraphModeSelector(
                viewMode = viewMode,
                onModeSelected = { mode ->
                    searchActive = false
                    viewModel.setViewMode(mode)
                    if (mode == KgViewMode.DOCUMENT && selectedDocumentId == null) showDocumentSelector = true
                    if (mode == KgViewMode.SESSION && selectedSessionId == null && sessionOptions.isNotEmpty()) showSessionSelector = true
                },
            )
            KnowledgeGraphScopeRow(
                viewMode = viewMode,
                scopeLabel = scopeLabel,
                meta = scopeMeta,
                onPick = {
                    when (viewMode) {
                        KgViewMode.DOCUMENT -> showDocumentSelector = true
                        KgViewMode.SESSION -> showSessionSelector = true
                        KgViewMode.GLOBAL -> {
                            viewModel.loadCollectionOptions()
                            showCollectionSelector = true
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            )
            val typeCounts = remember(nodes) { kgTypeCounts(nodes) }
            AnimatedVisibility(visible = typeCounts.size >= 2, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                KnowledgeGraphTypeFilterRow(
                    counts = typeCounts,
                    selected = typeFilter,
                    onToggle = viewModel::toggleTypeFilter,
                )
            }
            // 合集至少两篇时显示时间轴：按篇目顺序看某一篇末尾的图谱。
            val ticks by viewModel.timelineTicks.collectAsState()
            val asOfOrdinal by viewModel.asOfOrdinal.collectAsState()
            AnimatedVisibility(
                visible = viewMode == KgViewMode.GLOBAL && ticks.size >= 2,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                if (ticks.size >= 2) {
                    KnowledgeGraphTimelineRow(
                        ticks = ticks,
                        asOfOrdinal = asOfOrdinal,
                        onCommit = viewModel::setAsOf,
                        // 与上方类型筛选标签拉开一档，读作独立的一行控件。
                        modifier = Modifier.padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Small),
                    )
                }
            }
            AnimatedVisibility(visible = nodes.size >= MAX_GRAPH_NODE_LIMIT, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Text(
                    stringResource(R.string.kg_graph_display_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
                )
            }
            AnimatedVisibility(visible = loadError != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(kgLoadErrorMessageRes(graphState.isStale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::retryLoad) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.shared_btn_retry),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 平移缩放后的连线不得画到上方的筛选控件上。
                    .clipToBounds(),
            ) {
                // 图谱画布、加载、需选范围与空图谱之间交叉淡入；画布阶段内数据增长不触发切换。
                val canvasPhase = when {
                    nodes.isNotEmpty() -> KgCanvasPhase.Graph
                    isLoading -> KgCanvasPhase.Loading
                    documentSelectionRequired -> KgCanvasPhase.DocumentRequired
                    sessionSelectionRequired -> KgCanvasPhase.SessionRequired
                    else -> KgCanvasPhase.Empty
                }
                AnimatedContent(
                    targetState = canvasPhase,
                    transitionSpec = { InfinityMotion.fadeThrough() },
                    modifier = Modifier.fillMaxSize(),
                    label = "kg_canvas_phase",
                ) { shown ->
                    when (shown) {
                        KgCanvasPhase.Graph -> Box(Modifier.fillMaxSize()) {
                            InteractiveGraphCanvas(
                                simulator = simulator,
                                edges = edges,
                                summary = stringResource(
                                    R.string.kg_canvas_summary,
                                    nodes.size,
                                    edges.size,
                                    viewLabel,
                                    stringResource(R.string.kg_canvas_gestures),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                resetKey = resetKey,
                                highlight = highlight,
                                centerOnNodeId = centerRequest.nodeId,
                                centerRequestKey = centerRequest.key,
                                // 详情面板盖住画布下半部分，选中实体移到上方三成处。
                                centerFraction = if (selectedEntity != null) 0.3f else 0.5f,
                                onNodeTap = { nodeId ->
                                    searchActive = false
                                    viewModel.tapEntity(nodeId)
                                },
                                onBackgroundTap = { viewModel.selectEntity(null) },
                            )
                            FilledTonalIconButton(
                                onClick = { resetKey++ },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(InfinitySpacing.Medium)
                                    .testTag(UiTags.KG_FIT_VIEW),
                            ) {
                                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = stringResource(R.string.kg_fit_view))
                            }
                        }
                        KgCanvasPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        KgCanvasPhase.DocumentRequired -> KnowledgeGraphCanvasMessage(
                            text = stringResource(R.string.kg_document_required),
                            actionLabel = stringResource(R.string.kg_select_document),
                            onAction = { showDocumentSelector = true },
                        )
                        KgCanvasPhase.SessionRequired -> if (sessionOptions.isEmpty() && sessionOptionsError == null) {
                            KnowledgeGraphCanvasMessage(text = stringResource(R.string.kg_session_options_empty))
                        } else {
                            KnowledgeGraphCanvasMessage(
                                text = stringResource(R.string.kg_session_required),
                                actionLabel = stringResource(R.string.kg_select_session),
                                onAction = { showSessionSelector = true },
                            )
                        }
                        KgCanvasPhase.Empty -> KnowledgeGraphCanvasMessage(text = stringResource(R.string.kg_empty_graph))
                    }
                }
                // 详情面板与轮次条叠在画布底部，图谱仍可平移缩放。
                val detail = selectedEntity
                val detailNode = detail?.let { selected -> nodes.firstOrNull { it.id == selected.nodeId } }
                KgOverlay(
                    visible = detail != null && detailNode != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    if (detail != null && detailNode != null) {
                        val relationCount = remember(detailNode.id, edges) {
                            edges.count { it.sourceId == detailNode.id || it.targetId == detailNode.id }
                        }
                        KnowledgeGraphEntityPanel(
                            name = detailNode.label,
                            kind = KgEntityKind.of(detailNode.type),
                            detail = detail,
                            relationCount = relationCount,
                            relations = remember(detailNode.id, nodes, edges) { kgRelationItems(detailNode.id, nodes, edges) },
                            onClose = { viewModel.selectEntity(null) },
                            onOpenSession = onOpenSession,
                            onPickEntity = viewModel::focusEntity,
                            modifier = Modifier.heightIn(max = 360.dp),
                        )
                    }
                }
                KgOverlay(
                    visible = searchActive && searchQuery.isNotBlank(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = InfinitySpacing.Medium),
                ) {
                    KnowledgeGraphSearchResults(
                        results = searchResults,
                        isSearching = isSearching,
                        failed = searchFailed,
                        onPick = { nodeId ->
                            searchActive = false
                            viewModel.focusEntity(nodeId)
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = viewMode == KgViewMode.SESSION && sessionTurns.isNotEmpty() && selectedEntity == null,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                KnowledgeGraphTurnStrip(
                    turns = sessionTurns,
                    selectedTurnId = selectedTurnId,
                    onSelectTurn = viewModel::selectTurn,
                )
            }
        }
    }

    if (showDocumentSelector) {
        InfinityBottomSheet(
            show = true,
            onDismiss = { showDocumentSelector = false },
            title = stringResource(R.string.kg_select_document),
        ) {
            KnowledgeGraphDocumentOptionsContent(
                options = documentOptions,
                selectedDocumentId = selectedDocumentId,
                loadError = documentOptionsError,
                onRetry = viewModel::retryDocumentOptions,
                onSelect = { docId ->
                    showDocumentSelector = false
                    viewModel.loadGraphByDoc(docId)
                },
            )
        }
    }
    if (showCollectionSelector) {
        InfinityBottomSheet(
            show = true,
            onDismiss = { showCollectionSelector = false },
            title = stringResource(R.string.ks_graph_page_select),
        ) {
            KnowledgeGraphCollectionOptionsContent(
                options = collectionOptions,
                selectedCollectionId = selectedCollectionId,
                onSelect = { collectionId ->
                    showCollectionSelector = false
                    viewModel.loadGraphByCollection(collectionId)
                },
            )
        }
    }
    if (showSessionSelector) {
        InfinityBottomSheet(
            show = true,
            onDismiss = { showSessionSelector = false },
            title = stringResource(R.string.kg_select_session),
        ) {
            KnowledgeGraphSessionOptionsContent(
                options = sessionOptions,
                selectedSessionId = selectedSessionId,
                loadError = sessionOptionsError,
                onRetry = viewModel::retrySessionOptions,
                onSelect = { sessionId ->
                    showSessionSelector = false
                    viewModel.loadGraphBySession(sessionId)
                },
            )
        }
    }
    if (showAccessibilityList) {
        KnowledgeGraphAccessibilitySheet(
            nodes = nodes,
            edges = edges,
            onDismiss = { showAccessibilityList = false },
            totalNodeCount = totalNodeCount,
            canExpand = !isLoading && nodes.size < MAX_GRAPH_NODE_LIMIT,
            onExpandNode = viewModel::expandNode,
        )
    }
}

/** 叠在画布上的面板；独立成函数以使用不带父布局作用域的展开动画。 */
@Composable
private fun KgOverlay(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        content()
    }
}

internal fun kgLoadErrorMessageRes(isStale: Boolean): Int =
    if (isStale) R.string.kg_load_failed_stale else R.string.kg_load_failed

@Composable
internal fun KnowledgeGraphDocumentOptionsContent(
    options: List<KgDocumentOption>,
    selectedDocumentId: String? = null,
    loadError: KgDocumentOptionsError?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ProvideGroupedPress {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .bettboxListGroup()
                .selectableGroup(),
        ) {
            when {
                loadError != null -> item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTags.KG_DOCUMENT_OPTIONS_ERROR)
                            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                    ) {
                        Text(
                            text = stringResource(R.string.kg_document_options_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (loadError.canRetry) {
                            InfinityRetryButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .padding(top = InfinitySpacing.Small)
                                    .testTag(UiTags.KG_DOCUMENT_OPTIONS_RETRY),
                            )
                        }
                    }
                }
                options.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.kg_document_options_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag(UiTags.KG_DOCUMENT_OPTIONS_EMPTY)
                            .padding(InfinitySpacing.Large),
                    )
                }
                else -> items(options, key = { it.docId }) { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                            .selectable(
                                selected = option.docId == selectedDocumentId,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.docId) },
                            )
                            .testTag(UiTags.kgDocumentOption(option.docId)),
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        // 与“选择图谱”面板同一写法：标题最多两行，选中项右侧打勾。
                        headlineContent = { Text(option.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = if (option.docId == selectedDocumentId) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

private enum class KgCanvasPhase { Graph, Loading, DocumentRequired, SessionRequired, Empty }
