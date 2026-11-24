package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.proxy.BuiltInProxyPhase
import com.promenar.infinity.data.proxy.BuiltInProxyState
import com.promenar.infinity.data.proxy.ProxyLatencyResult
import com.promenar.infinity.data.proxy.ProxyMode
import com.promenar.infinity.data.proxy.ProxyNodeItem
import com.promenar.infinity.data.proxy.ProxySubscription
import com.promenar.infinity.data.proxy.ProxyYamlValidation
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.UnifiedPromptEditor
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class ProxyImportTab { SUBSCRIPTION, URI }

internal data class ProxySettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onModeChange: (ProxyMode) -> Unit = {},
    val onSelectNode: (String) -> Unit = {},
    val onNodeEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
    val onMoveNode: (String, Int) -> Unit = { _, _ -> },
    val onRemoveNode: (String) -> Unit = {},
    val onTestAllLatency: () -> Unit = {},
    val onTestSingleLatency: (String) -> Unit = {},
    val onRefreshSubscription: (String) -> Unit = {},
    val onRemoveSubscription: (String) -> Unit = {},
    val onOpenImport: () -> Unit = {},
    val onOpenAdvancedYaml: () -> Unit = {},
    val onSyncAdvancedYaml: () -> Unit = {},
)

/** 设置首页“执行环境与连接”内置代理入口的副标题文案。 */
@Composable
internal fun builtInProxySummarySubtitle(state: BuiltInProxyState): String {
    val activeName = state.activeNode?.let { id -> state.nodes.firstOrNull { it.id == id }?.name }
    return when {
        state.phase == BuiltInProxyPhase.UNAVAILABLE -> stringResource(R.string.proxy_phase_unavailable)
        !state.enabled -> stringResource(R.string.proxy_phase_disabled)
        state.phase == BuiltInProxyPhase.STARTING -> stringResource(R.string.proxy_phase_starting)
        state.phase == BuiltInProxyPhase.RUNNING && activeName != null ->
            listOf(stringResource(R.string.proxy_phase_running), activeName).joinToString(" · ") // wrap-check: allow 单行入口副标题省略
        state.phase == BuiltInProxyPhase.RUNNING -> stringResource(R.string.proxy_phase_running)
        state.phase == BuiltInProxyPhase.ERROR -> stringResource(R.string.proxy_phase_error)
        state.nodes.none { it.enabled } -> stringResource(R.string.proxy_phase_no_nodes)
        else -> stringResource(R.string.proxy_phase_stopped)
    }
}

@Composable
fun ProxySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: ProxyViewModel =
        viewModel(factory = ProxyViewModel.factory(context.applicationContext as Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noticeHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var showYamlEditor by rememberSaveable { mutableStateOf(false) }
    var yamlInitialText by remember { mutableStateOf("") }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        noticeHost.currentSnackbarData?.dismiss()
        val message = when (notice) {
            is ProxyNotice.Imported -> resources.getString(R.string.proxy_notice_imported, notice.count)
            is ProxyNotice.SubscriptionRefreshed -> resources.getString(R.string.proxy_notice_sub_refreshed, notice.count)
            ProxyNotice.YamlSaved -> resources.getString(R.string.proxy_notice_yaml_saved)
            ProxyNotice.YamlSynced -> resources.getString(R.string.proxy_notice_yaml_synced)
            ProxyNotice.LatencyDone -> resources.getString(R.string.proxy_notice_latency_done)
            is ProxyNotice.Failure -> notice.message?.takeIf(String::isNotBlank)
                ?: resources.getString(R.string.proxy_notice_operation_failed)
        }
        noticeHost.showSnackbar(message)
    }

    val openYamlEditor: () -> Unit = {
        scope.launch {
            try {
                yamlInitialText = viewModel.loadEditableYaml()
                viewModel.clearYamlValidation()
                showYamlEditor = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                noticeHost.showSnackbar(
                    error.message?.takeIf(String::isNotBlank)
                        ?: resources.getString(R.string.proxy_notice_operation_failed),
                )
            }
        }
    }

    val enabledCount = state.settings.nodes.count { it.enabled }

    InfinitySettingsPageLayout(
        title = stringResource(R.string.proxy_title),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = viewModel::testAllLatency,
                enabled = enabledCount > 0 && !state.testingLatency,
                modifier = Modifier.testTag(ProxyTags.ACTION_TEST_LATENCY),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = stringResource(R.string.proxy_action_test_latency),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = {
                    viewModel.clearImportError()
                    showImportSheet = true
                },
                modifier = Modifier.testTag(ProxyTags.ACTION_ADD_NODES),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.proxy_action_add_nodes),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            ProxySettingsContent(
                state = state,
                actions = ProxySettingsActions(
                    onEnabledChange = viewModel::setEnabled,
                    onModeChange = viewModel::setMode,
                    onSelectNode = viewModel::selectNode,
                    onNodeEnabledChange = viewModel::setNodeEnabled,
                    onMoveNode = viewModel::moveNode,
                    onRemoveNode = viewModel::removeNode,
                    onTestAllLatency = viewModel::testAllLatency,
                    onTestSingleLatency = viewModel::testSingleLatency,
                    onRefreshSubscription = viewModel::refreshSubscription,
                    onRemoveSubscription = viewModel::removeSubscription,
                    onOpenImport = {
                        viewModel.clearImportError()
                        showImportSheet = true
                    },
                    onOpenAdvancedYaml = openYamlEditor,
                    onSyncAdvancedYaml = viewModel::syncAdvancedYaml,
                ),
                contentPadding = contentPadding,
            )
            SnackbarHost(
                hostState = noticeHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = InfinitySpacing.Large)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }

    ProxyImportSheet(
        show = showImportSheet,
        importing = state.importing,
        errorMessage = state.importError,
        onDismiss = {
            showImportSheet = false
            viewModel.clearImportError()
        },
        onClearError = viewModel::clearImportError,
        onImportSubscription = { name, url ->
            viewModel.addAndRefreshSubscription(name, url) {
                showImportSheet = false
            }
        },
        onImportNodes = { content ->
            viewModel.importNodes(content) {
                showImportSheet = false
            }
        },
    )

    ProxyAdvancedYamlEditor(
        show = showYamlEditor,
        initialYaml = yamlInitialText,
        hasAdvancedYaml = state.settings.hasAdvancedYaml,
        validating = state.yamlValidating,
        validation = state.yamlValidation,
        onValidate = viewModel::validateYaml,
        onSave = viewModel::saveAdvancedYaml,
        onSyncManaged = {
            viewModel.syncAdvancedYaml()
            showYamlEditor = false
        },
        onDismiss = {
            showYamlEditor = false
            viewModel.clearYamlValidation()
        },
    )
}

/** 无应用依赖的内置代理设置正文，供真实页面与截图基线共用。 */
@Composable
internal fun ProxySettingsContent(
    state: ProxySettingsUiState,
    actions: ProxySettingsActions = ProxySettingsActions(),
    contentPadding: PaddingValues = PaddingValues(InfinitySpacing.ScreenHorizontal),
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val settings = state.settings
    val proxyState = state.proxyState
    val fallbackMode = settings.mode == ProxyMode.FALLBACK
    val enabledNodes = remember(settings.nodes) { settings.nodes.filter { it.enabled } }
    val subscriptionNames = remember(settings.subscriptions) {
        settings.subscriptions.associate { it.id to it.name }
    }
    var pendingDeleteSub by remember { mutableStateOf<ProxySubscription?>(null) }
    var pendingDeleteNode by remember { mutableStateOf<ProxyNodeItem?>(null) }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val reorderState = remember { ProxyNodeReorderState() }
    val latestNodes by rememberUpdatedState(settings.nodes)
    val latestOnMoveNode by rememberUpdatedState(actions.onMoveNode)
    val dividerHeightPx = with(density) { 1.dp.toPx() }
    LaunchedEffect(dividerHeightPx) { reorderState.updateDividerHeight(dividerHeightPx) }
    LaunchedEffect(settings.nodes) { reorderState.reconcilePersisted(settings.nodes) }
    LaunchedEffect(fallbackMode) {
        if (!fallbackMode) {
            reorderState.cancelDrag()
            reorderState.clearPendingOrder()
        }
    }
    LaunchedEffect(reorderState.pendingOrderIds) {
        if (reorderState.pendingOrderIds != null) {
            delay(600L)
            reorderState.clearPendingOrder()
        }
    }

    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var listBottomInWindow by remember { mutableFloatStateOf(0f) }
    var draggedRowBaseTopInWindow by remember { mutableFloatStateOf(0f) }
    var draggedRowHeightPx by remember { mutableFloatStateOf(0f) }

    // 拖近视口顶底热区时平滑自动滚动外层列表，并同步推进换位，支持长订阅列表跨屏排序。
    LaunchedEffect(reorderState.draggingId) {
        val activeDragId = reorderState.draggingId ?: return@LaunchedEffect
        val edgeZonePx = with(density) { 72.dp.toPx() }
        val maxStepPx = with(density) { 14.dp.toPx() }
        while (reorderState.draggingId == activeDragId) {
            withFrameNanos { }
            val viewportHeight = listBottomInWindow - listTopInWindow
            if (viewportHeight <= 0f || draggedRowHeightPx <= 0f) continue
            val visualTop = draggedRowBaseTopInWindow + reorderState.dragOffsetY
            val visualBottom = visualTop + draggedRowHeightPx
            val step = when {
                visualTop < listTopInWindow + edgeZonePx &&
                    listState.canScrollBackward &&
                    reorderState.canDragUp(activeDragId) -> {
                    val ratio = ((listTopInWindow + edgeZonePx - visualTop) / edgeZonePx).coerceIn(0.15f, 1f)
                    -maxStepPx * ratio
                }
                visualBottom > listBottomInWindow - edgeZonePx &&
                    listState.canScrollForward &&
                    reorderState.canDragDown(activeDragId) -> {
                    val ratio = ((visualBottom - (listBottomInWindow - edgeZonePx)) / edgeZonePx).coerceIn(0.15f, 1f)
                    maxStepPx * ratio
                }
                else -> 0f
            }
            if (step != 0f) {
                val consumed = listState.scrollBy(step)
                if (consumed != 0f && reorderState.onDragDelta(consumed)) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInWindow()
                listTopInWindow = rect.top
                listBottomInWindow = rect.bottom
            }
            .testTag(ProxyTags.LIST),
        contentPadding = contentPadding,
    ) {
        item("service") {
            InfinitySettingsSection(
                title = stringResource(R.string.proxy_section_service),
                footer = stringResource(R.string.proxy_service_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.proxy_enable),
                    description = stringResource(
                        if (state.available) R.string.proxy_enable_desc else R.string.proxy_unavailable_desc,
                    ),
                    checked = state.available && settings.enabled,
                    enabled = state.available,
                    onCheckedChange = actions.onEnabledChange,
                    icon = Icons.Rounded.Public,
                    modifier = Modifier.testTag(ProxyTags.ENABLE_SWITCH),
                )
            }
        }

        item("status_card") {
            ProxyStatusCard(
                state = state,
                enabledCount = enabledNodes.size,
                onTestAllLatency = actions.onTestAllLatency,
                onOpenAdvancedYaml = actions.onOpenAdvancedYaml,
                modifier = Modifier.padding(bottom = InfinitySpacing.SectionGap),
            )
        }

        item("mode") {
            InfinitySettingsSection(
                title = stringResource(R.string.proxy_section_mode),
                footer = stringResource(
                    if (fallbackMode) R.string.proxy_mode_fallback_footer else R.string.proxy_mode_single_footer,
                ),
            ) {
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment(ProxyMode.SINGLE, stringResource(R.string.proxy_mode_single), tag = ProxyTags.MODE_SINGLE),
                        InfinitySegment(ProxyMode.FALLBACK, stringResource(R.string.proxy_mode_fallback), tag = ProxyTags.MODE_FALLBACK),
                    ),
                    selected = settings.mode,
                    onSelect = actions.onModeChange,
                    fillWidth = true,
                    contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                    modifier = Modifier.testTag(ProxyTags.MODE_CONTROL),
                )
            }
        }

        item("subscriptions") {
            InfinitySettingsSection(title = stringResource(R.string.proxy_section_subscriptions)) {
                if (settings.hasAdvancedYaml) {
                    ProxyAdvancedModeBanner(onSyncManaged = actions.onSyncAdvancedYaml)
                    InfinityGroupDivider()
                }
                settings.subscriptions.forEach { subscription ->
                    ProxySubscriptionRow(
                        subscription = subscription,
                        refreshing = subscription.id in state.refreshingSubscriptionIds,
                        onRefresh = { actions.onRefreshSubscription(subscription.id) },
                        onDelete = { pendingDeleteSub = subscription },
                    )
                    InfinityGroupDivider()
                }
                InfinitySettingsItem(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.proxy_import_entry),
                    subtitle = stringResource(R.string.proxy_import_entry_desc),
                    subtitleMaxLines = 2,
                    onClick = actions.onOpenImport,
                    showChevron = true,
                    modifier = Modifier.testTag(ProxyTags.IMPORT_ENTRY),
                )
            }
        }

        item("nodes") {
            val sectionTitle = stringResource(
                if (fallbackMode) R.string.proxy_section_nodes_fallback else R.string.proxy_section_nodes_single,
                enabledNodes.size,
            )
            val sectionFooter = if (fallbackMode && settings.nodes.isNotEmpty()) {
                stringResource(R.string.proxy_nodes_fallback_footer)
            } else {
                null
            }
            InfinitySettingsSection(title = sectionTitle, footer = sectionFooter) {
                if (settings.nodes.isEmpty()) {
                    ProxyEmptyNodesCard(onImport = actions.onOpenImport)
                } else {
                    val displayedNodes = reorderState.displayNodes(settings.nodes)
                    val isAnyDragging = reorderState.isDragging
                    var rank = 0
                    displayedNodes.forEachIndexed { index, node ->
                        val isDragging = reorderState.draggingId == node.id
                        val prevDragging = index > 0 && reorderState.draggingId == displayedNodes[index - 1].id
                        val nodeRank = if (node.enabled) ++rank else null
                        val isSelected = if (fallbackMode) {
                            if (isAnyDragging) {
                                nodeRank == 1
                            } else {
                                proxyState.activeNode == node.id || (proxyState.activeNode == null && nodeRank == 1)
                            }
                        } else {
                            settings.activeNode == node.id
                        }
                        key(node.id) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragging) 2f else 0f),
                            ) {
                                if (index > 0) {
                                    // 保持分隔线恒定占位，拖拽相邻项时仅隐去可见度，避免容器总高跳变。
                                    Box(Modifier.alpha(if (isDragging || prevDragging) 0f else 1f)) {
                                        InfinityGroupDivider()
                                    }
                                }
                                ProxyNodeRow(
                                    node = node,
                                    rank = nodeRank,
                                    index = index,
                                    totalCount = displayedNodes.size,
                                    fallbackMode = fallbackMode,
                                    selected = isSelected,
                                    hasAdvancedYaml = settings.hasAdvancedYaml,
                                    sourceLabel = resolveNodeSourceLabel(node, subscriptionNames, settings.hasAdvancedYaml),
                                    latency = state.latencies[node.id],
                                    testing = node.id in state.testingNodeIds,
                                    isDragging = isDragging,
                                    isAnyDragging = isAnyDragging,
                                    dragOffsetY = if (isDragging) reorderState.dragOffsetY else 0f,
                                    onMeasuredHeight = { heightPx -> reorderState.updateRowHeight(node.id, heightPx) },
                                    onDraggedLayout = { topY, heightPx ->
                                        draggedRowBaseTopInWindow = topY
                                        draggedRowHeightPx = heightPx
                                    },
                                    onDragStart = {
                                        if (!reorderState.isDragging && reorderState.startDrag(node.id, latestNodes)) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDragDelta = { deltaY ->
                                        if (reorderState.draggingId == node.id && reorderState.onDragDelta(deltaY)) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onDragEnd = {
                                        if (reorderState.draggingId == node.id) {
                                            reorderState.finishDrag()?.let { (movedId, delta) ->
                                                latestOnMoveNode(movedId, delta)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        if (reorderState.draggingId == node.id) {
                                            reorderState.cancelDrag()
                                        }
                                    },
                                    onSelect = { if (node.enabled) actions.onSelectNode(node.id) },
                                    onEnabledChange = { actions.onNodeEnabledChange(node.id, it) },
                                    onMoveTop = { if (index > 0) latestOnMoveNode(node.id, -index) },
                                    onMoveUp = { if (index > 0) latestOnMoveNode(node.id, -1) },
                                    onMoveDown = { if (index < displayedNodes.lastIndex) latestOnMoveNode(node.id, 1) },
                                    onTestLatency = { actions.onTestSingleLatency(node.id) },
                                    onDelete = { pendingDeleteNode = node },
                                )
                            }
                        }
                    }
                }
            }
        }

        item("scope") {
            InfinitySettingsSection(title = stringResource(R.string.proxy_section_scope)) {
                InfinitySettingsRowLayout(
                    icon = Icons.Rounded.Dns,
                    title = stringResource(R.string.proxy_scope_providers_title),
                    subtitle = stringResource(R.string.proxy_scope_providers_desc),
                    subtitleMaxLines = 2,
                )
                InfinityGroupDivider()
                InfinitySettingsRowLayout(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.proxy_scope_browser_title),
                    subtitle = stringResource(R.string.proxy_scope_browser_desc),
                    subtitleMaxLines = 2,
                )
                InfinityGroupDivider()
                InfinitySettingsRowLayout(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.proxy_scope_linux_title),
                    subtitle = stringResource(R.string.proxy_scope_linux_desc),
                    subtitleMaxLines = 2,
                )
            }
        }

        item("bottom_space") { Spacer(Modifier.height(24.dp)) }
    }

    pendingDeleteSub?.let { sub ->
        InfinityConfirmDialog(
            title = stringResource(R.string.proxy_subscription_delete_title),
            message = stringResource(R.string.proxy_subscription_delete_message, sub.name),
            confirmText = stringResource(R.string.settings_btn_delete),
            onConfirm = {
                actions.onRemoveSubscription(sub.id)
                pendingDeleteSub = null
            },
            onCancel = { pendingDeleteSub = null },
            isDestructive = true,
        )
    }

    pendingDeleteNode?.let { node ->
        InfinityConfirmDialog(
            title = stringResource(R.string.proxy_node_delete_title),
            message = stringResource(R.string.proxy_node_delete_message, node.name),
            confirmText = stringResource(R.string.settings_btn_delete),
            onConfirm = {
                actions.onRemoveNode(node.id)
                pendingDeleteNode = null
            },
            onCancel = { pendingDeleteNode = null },
            isDestructive = true,
        )
    }
}

@Composable
private fun ProxyStatusCard(
    state: ProxySettingsUiState,
    enabledCount: Int,
    onTestAllLatency: () -> Unit,
    onOpenAdvancedYaml: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = MaterialTheme.infinityDomainColors
    val scheme = MaterialTheme.colorScheme
    val proxyState = state.proxyState
    val settings = state.settings
    val activeNodeId = proxyState.activeNode ?: settings.activeNode
    val activeNode = activeNodeId?.let { id -> settings.nodes.firstOrNull { it.id == id } }
    val activeLatency = activeNode?.let { state.latencies[it.id]?.takeIf { r -> r.ok }?.delayMs }

    val dotColor = when {
        proxyState.phase == BuiltInProxyPhase.RUNNING -> domain.signalOn
        proxyState.phase == BuiltInProxyPhase.STARTING -> domain.signalPending
        proxyState.phase == BuiltInProxyPhase.ERROR -> scheme.error
        settings.enabled && enabledCount == 0 -> domain.signalPending
        else -> scheme.outline
    }
    val statusLabel = when {
        proxyState.phase == BuiltInProxyPhase.UNAVAILABLE -> stringResource(R.string.proxy_phase_unavailable)
        proxyState.phase == BuiltInProxyPhase.RUNNING -> stringResource(R.string.proxy_phase_running)
        proxyState.phase == BuiltInProxyPhase.STARTING -> stringResource(R.string.proxy_phase_starting)
        proxyState.phase == BuiltInProxyPhase.ERROR -> stringResource(R.string.proxy_phase_error)
        settings.enabled && enabledCount == 0 -> stringResource(R.string.proxy_phase_no_nodes)
        else -> stringResource(R.string.proxy_phase_disabled)
    }
    val modeLabel = stringResource(
        if (settings.mode == ProxyMode.FALLBACK) R.string.proxy_mode_fallback else R.string.proxy_mode_single,
    )
    val detailSegments: List<String>? = when {
        proxyState.error != null -> null
        proxyState.running && activeNode != null && activeLatency != null -> listOf(
            stringResource(R.string.proxy_active_outbound, activeNode.name),
            stringResource(R.string.proxy_node_latency_ms, activeLatency.toInt()),
        )
        else -> null
    }
    val detailText = when {
        proxyState.error != null -> proxyState.error
        proxyState.running && activeNode != null ->
            stringResource(R.string.proxy_active_outbound, activeNode.name)
        proxyState.fallbackReason != null -> proxyState.fallbackReason
        activeNode != null -> stringResource(R.string.proxy_active_outbound, activeNode.name)
        else -> stringResource(R.string.proxy_nodes_empty_desc)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProxyTags.STATUS_CARD),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                        ProductionStatusChip(modeLabel, ProductionTone.INFO)
                        if (proxyState.running && settings.mode == ProxyMode.FALLBACK && proxyState.healthyNodeCount > 0) {
                            ProductionStatusChip(
                                stringResource(R.string.proxy_healthy_count, proxyState.healthyNodeCount),
                                ProductionTone.SUCCESS,
                            )
                        }
                    }
                    if (detailSegments != null) {
                        InfinityMetaLine(
                            segments = detailSegments,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else {
                        Text(
                            text = detailText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proxyState.error != null) scheme.error else scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = proxyState.running && proxyState.listenPort != null,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                proxyState.listenPort?.let { port ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (domain.isDark) InfinityObsidianDialogChip else scheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        InfinityMetaLine(
                            segments = listOf(
                                stringResource(R.string.proxy_loopback_addr, port),
                                stringResource(R.string.proxy_loopback_protocol),
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            InfinityPillRow {
                InfinityPillButton(
                    text = stringResource(
                        if (state.testingLatency) R.string.proxy_action_testing else R.string.proxy_action_test_latency,
                    ),
                    onClick = onTestAllLatency,
                    icon = Icons.Rounded.Speed,
                    compact = true,
                    enabled = enabledCount > 0 && !state.testingLatency,
                    modifier = Modifier.testTag(ProxyTags.BTN_TEST_LATENCY),
                )
                InfinityPillButton(
                    text = stringResource(R.string.proxy_action_advanced_yaml),
                    onClick = onOpenAdvancedYaml,
                    icon = Icons.Rounded.Code,
                    compact = true,
                    modifier = Modifier.testTag(ProxyTags.BTN_ADVANCED_YAML),
                )
            }
        }
    }
}

@Composable
private fun ProxyAdvancedModeBanner(onSyncManaged: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(ProxyTags.ADVANCED_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Code,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.proxy_advanced_banner_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.proxy_advanced_banner_desc),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        InfinityPillButton(
            text = stringResource(R.string.proxy_advanced_sync_action),
            onClick = onSyncManaged,
            icon = Icons.Rounded.Sync,
            compact = true,
            modifier = Modifier.testTag(ProxyTags.BTN_SYNC_MANAGED),
        )
    }
}

@Composable
private fun ProxySubscriptionRow(
    subscription: ProxySubscription,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val updatedLabel = formatSubscriptionUpdatedTime(subscription.lastRefreshedAtMillis)
    InfinitySettingsItem(
        icon = Icons.Rounded.RssFeed,
        title = subscription.name,
        subtitleSegments = listOf(
            stringResource(R.string.proxy_subscription_nodes_count, subscription.nodeCount),
            updatedLabel,
        ),
        onClick = onRefresh,
        modifier = Modifier.testTag(ProxyTags.subscription(subscription.id)),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.proxy_subscription_refresh_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.proxy_subscription_more_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    InfinityDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        alignToAnchorEnd = true,
                    ) {
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.proxy_subscription_refresh_cd)) },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            enabled = !refreshing,
                            onClick = {
                                menuExpanded = false
                                onRefresh()
                            },
                        )
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.proxy_subscription_delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            destructive = true,
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ProxyEmptyNodesCard(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag(ProxyTags.EMPTY_NODES),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.proxy_nodes_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.proxy_nodes_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        InfinityPillButton(
            text = stringResource(R.string.proxy_nodes_empty_action),
            onClick = onImport,
            tone = InfinityPillTone.PRIMARY,
            icon = Icons.Rounded.Add,
            compact = true,
        )
    }
}

@Composable
private fun ProxyNodeRow(
    node: ProxyNodeItem,
    rank: Int?,
    index: Int,
    totalCount: Int,
    fallbackMode: Boolean,
    selected: Boolean,
    hasAdvancedYaml: Boolean,
    sourceLabel: String,
    latency: ProxyLatencyResult?,
    testing: Boolean,
    isDragging: Boolean,
    isAnyDragging: Boolean,
    dragOffsetY: Float,
    onMeasuredHeight: (Float) -> Unit,
    onDraggedLayout: (topInWindow: Float, heightPx: Float) -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onSelect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTestLatency: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    var menuExpanded by remember { mutableStateOf(false) }
    val canDrag = fallbackMode && totalCount > 1

    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)
    val latestOnMoveUp by rememberUpdatedState(onMoveUp)
    val latestOnMoveDown by rememberUpdatedState(onMoveDown)

    // 邻近行被拖拽挤位时做短促平滑滑入，避免瞬间闪跳；系统“移除动画”开启时 Compose 会自动将时长缩至 0。
    var measuredHeightPx by remember(node.id) { mutableFloatStateOf(0f) }
    var lastStableIndex by remember(node.id) { mutableIntStateOf(index) }
    val settleOffset = remember(node.id) { Animatable(0f) }
    LaunchedEffect(index, isDragging, isAnyDragging) {
        val prev = lastStableIndex
        lastStableIndex = index
        if (!isDragging && isAnyDragging && prev != index && measuredHeightPx > 0f) {
            val deltaPx = (prev - index) * measuredHeightPx
            settleOffset.snapTo(deltaPx)
            settleOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedDecelerate),
            )
        } else if (isDragging || !isAnyDragging) {
            settleOffset.snapTo(0f)
        }
    }

    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.01f else 1f,
        animationSpec = tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedDecelerate),
        label = "proxyNodeDragScale",
    )

    val latencyText = when {
        !node.enabled -> stringResource(R.string.proxy_node_latency_disabled)
        testing -> stringResource(R.string.proxy_action_testing)
        latency == null -> null
        latency.ok && latency.delayMs != null -> stringResource(R.string.proxy_node_latency_ms, latency.delayMs.toInt())
        else -> stringResource(R.string.proxy_node_latency_timeout)
    }
    val latencyColor = when {
        !node.enabled || testing -> scheme.onSurfaceVariant
        latency == null -> scheme.onSurfaceVariant
        !latency.ok -> scheme.error
        (latency.delayMs ?: Long.MAX_VALUE) < 100L -> domain.signalOn
        else -> domain.signalPending
    }

    val moveUpLabel = stringResource(R.string.proxy_node_move_up_cd)
    val moveDownLabel = stringResource(R.string.proxy_node_move_down_cd)
    val dragHandleLabel = stringResource(R.string.proxy_node_drag_handle_cd)
    val dragBg = if (domain.isDark) InfinityObsidianDialogChip else scheme.surfaceContainerHighest
    val rowShape = RoundedCornerShape(14.dp)
    val canUp = index > 0
    val canDown = index < totalCount - 1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                val h = size.height.toFloat()
                if (h > 0f) {
                    measuredHeightPx = h
                    onMeasuredHeight(h)
                }
            }
            .onGloballyPositioned { coords ->
                if (isDragging) {
                    onDraggedLayout(coords.positionInWindow().y, coords.size.height.toFloat())
                }
            }
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else settleOffset.value
                scaleX = dragScale
                scaleY = dragScale
            }
            .then(
                if (isDragging) {
                    Modifier
                        .padding(horizontal = 4.dp)
                        .shadow(elevation = 10.dp, shape = rowShape, clip = false)
                        .clip(rowShape)
                        .background(dragBg)
                        .border(1.dp, scheme.primary.copy(alpha = 0.72f), rowShape)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (canDrag) {
                        val a11yActions = mutableListOf<CustomAccessibilityAction>()
                        if (canUp) {
                            a11yActions += CustomAccessibilityAction(moveUpLabel) {
                                latestOnMoveUp()
                                true
                            }
                        }
                        if (canDown) {
                            a11yActions += CustomAccessibilityAction(moveDownLabel) {
                                latestOnMoveDown()
                                true
                            }
                        }
                        if (a11yActions.isNotEmpty()) {
                            customActions = a11yActions
                        }
                    }
                }
                .then(
                    when {
                        fallbackMode -> Modifier.clickable(
                            enabled = !isAnyDragging,
                            role = Role.Button,
                            onClick = { menuExpanded = true },
                        )
                        node.enabled -> Modifier.clickable(role = Role.RadioButton, onClick = onSelect)
                        else -> Modifier
                    },
                )
                .pointerInput(node.id, canDrag) {
                    if (!canDrag) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            menuExpanded = false
                            latestOnDragStart()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            latestOnDragDelta(dragAmount.y)
                        },
                        onDragEnd = { latestOnDragEnd() },
                        onDragCancel = { latestOnDragCancel() },
                    )
                }
                .alpha(if (node.enabled || isDragging) 1f else 0.58f)
                .padding(
                    start = 16.dp,
                    end = if (fallbackMode) 8.dp else 12.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                )
                .testTag(ProxyTags.node(node.id)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (fallbackMode) {
                val highlightRank = selected && node.enabled
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (highlightRank) scheme.primary else scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (rank != null && node.enabled) "#$rank" else "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (highlightRank) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    imageVector = if (selected && node.enabled) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected && node.enabled) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if ((selected && node.enabled) || isDragging) FontWeight.SemiBold else FontWeight.Normal,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    latencyText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = latencyColor,
                            maxLines = 1,
                        )
                    }
                }
                InfinityMetaLine(
                    segments = listOf(formatProtocolLabel(node.type), sourceLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (!fallbackMode) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.proxy_node_more_cd),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    InfinityDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        alignToAnchorEnd = true,
                    ) {
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.proxy_node_test_single)) },
                            leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            enabled = node.enabled && !testing,
                            onClick = {
                                menuExpanded = false
                                onTestLatency()
                            },
                        )
                        if (!hasAdvancedYaml) {
                            InfinityMenuItem(
                                text = { Text(stringResource(R.string.proxy_node_delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                destructive = true,
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }

            Switch(
                checked = node.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !isAnyDragging,
                modifier = Modifier.testTag(ProxyTags.nodeSwitch(node.id)),
            )

            if (fallbackMode) {
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDragging) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest.copy(alpha = 0f),
                        )
                        .alpha(if (canDrag) 1f else 0.35f)
                        .pointerInput(node.id, canDrag) {
                            if (!canDrag) return@pointerInput
                            detectVerticalDragGestures(
                                onDragStart = {
                                    menuExpanded = false
                                    latestOnDragStart()
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    latestOnDragDelta(dragAmount)
                                },
                                onDragEnd = { latestOnDragEnd() },
                                onDragCancel = { latestOnDragCancel() },
                            )
                        }
                        .semantics { contentDescription = dragHandleLabel }
                        .testTag(ProxyTags.nodeDragHandle(node.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = null,
                        tint = if (isDragging) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (fallbackMode) {
            InfinityDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                alignToAnchorEnd = true,
            ) {
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.proxy_node_test_single)) },
                    leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    enabled = node.enabled && !testing,
                    onClick = {
                        menuExpanded = false
                        onTestLatency()
                    },
                )
                if (canUp) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.proxy_node_move_top)) },
                        leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            onMoveTop()
                        },
                    )
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.proxy_node_move_up)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag(ProxyTags.nodeMoveUp(node.id)),
                        onClick = {
                            menuExpanded = false
                            onMoveUp()
                        },
                    )
                }
                if (canDown) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.proxy_node_move_down)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag(ProxyTags.nodeMoveDown(node.id)),
                        onClick = {
                            menuExpanded = false
                            onMoveDown()
                        },
                    )
                }
                if (!hasAdvancedYaml) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.proxy_node_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        destructive = true,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyImportSheet(
    show: Boolean,
    importing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onClearError: () -> Unit,
    onImportSubscription: (String, String) -> Unit,
    onImportNodes: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ProxyImportTab.SUBSCRIPTION) }
    var subName by rememberSaveable { mutableStateOf("") }
    var subUrl by rememberSaveable { mutableStateOf("") }
    var uriContent by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(show) {
        if (!show) {
            subName = ""
            subUrl = ""
            uriContent = ""
        }
    }

    InfinityBottomSheet(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.proxy_import_sheet_title),
        dismissEnabled = !importing,
        scrollContent = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProxyTags.IMPORT_SHEET),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(
                        ProxyImportTab.SUBSCRIPTION,
                        stringResource(R.string.proxy_import_tab_sub),
                        leading = { Icon(Icons.Rounded.RssFeed, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        tag = ProxyTags.IMPORT_TAB_SUB,
                    ),
                    InfinitySegment(
                        ProxyImportTab.URI,
                        stringResource(R.string.proxy_import_tab_uri),
                        leading = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        tag = ProxyTags.IMPORT_TAB_URI,
                    ),
                ),
                selected = tab,
                onSelect = {
                    tab = it
                    onClearError()
                },
                fillWidth = true,
            )

            if (tab == ProxyImportTab.SUBSCRIPTION) {
                OutlinedTextField(
                    value = subName,
                    onValueChange = {
                        subName = it
                        onClearError()
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    label = { Text(stringResource(R.string.proxy_import_sub_name_label)) },
                    placeholder = { Text(stringResource(R.string.proxy_import_sub_name_placeholder)) },
                    singleLine = true,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag(ProxyTags.IMPORT_SUB_NAME),
                )
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = {
                        subUrl = it
                        onClearError()
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    label = { Text(stringResource(R.string.proxy_import_sub_url_label)) },
                    supportingText = { Text(stringResource(R.string.proxy_import_sub_url_help)) },
                    maxLines = 3,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag(ProxyTags.IMPORT_SUB_URL),
                )
            } else {
                OutlinedTextField(
                    value = uriContent,
                    onValueChange = {
                        uriContent = it
                        onClearError()
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    label = { Text(stringResource(R.string.proxy_import_uri_label)) },
                    placeholder = { Text(stringResource(R.string.proxy_import_uri_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag(ProxyTags.IMPORT_URI_INPUT),
                )
            }

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                Text(
                    text = errorMessage?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.proxy_notice_operation_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            InfinityPillRow {
                InfinityPillButton(
                    text = stringResource(R.string.common_btn_cancel),
                    onClick = onDismiss,
                    tone = InfinityPillTone.NEUTRAL,
                    enabled = !importing,
                )
                if (tab == ProxyImportTab.SUBSCRIPTION) {
                    InfinityPillButton(
                        text = stringResource(R.string.proxy_import_sub_submit),
                        onClick = {
                            onImportSubscription(subName, subUrl)
                        },
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !importing && subName.isNotBlank() && subUrl.isNotBlank(),
                        modifier = Modifier.testTag(ProxyTags.IMPORT_SUBMIT),
                    )
                } else {
                    InfinityPillButton(
                        text = stringResource(R.string.proxy_import_uri_submit),
                        onClick = {
                            onImportNodes(uriContent)
                        },
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !importing && uriContent.isNotBlank(),
                        modifier = Modifier.testTag(ProxyTags.IMPORT_SUBMIT),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyAdvancedYamlEditor(
    show: Boolean,
    initialYaml: String,
    hasAdvancedYaml: Boolean,
    validating: Boolean,
    validation: ProxyYamlValidation?,
    onValidate: (String) -> Unit,
    onSave: suspend (String) -> Result<Unit>,
    onSyncManaged: () -> Unit,
    onDismiss: () -> Unit,
) {
    UnifiedPromptEditor(
        show = show,
        onDismiss = onDismiss,
        onSave = onSave,
        initialText = initialYaml,
        title = stringResource(R.string.proxy_yaml_editor_title),
        placeholder = stringResource(R.string.proxy_yaml_editor_placeholder),
        showPreviewTab = false,
        banner = { handle ->
            val domain = MaterialTheme.infinityDomainColors
            val scheme = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proxy_yaml_editor_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.proxy_yaml_validate),
                        onClick = { onValidate(handle.text) },
                        compact = true,
                        enabled = !validating && handle.text.isNotBlank(),
                        modifier = Modifier.testTag(ProxyTags.YAML_VALIDATE),
                    )
                    if (hasAdvancedYaml) {
                        InfinityPillButton(
                            text = stringResource(R.string.proxy_advanced_sync_action),
                            onClick = onSyncManaged,
                            compact = true,
                            tone = InfinityPillTone.NEUTRAL,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = validation != null,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    validation?.let { result ->
                        val bg = if (result.valid) domain.successContainer.copy(alpha = 0.55f) else scheme.errorContainer
                        val fg = if (result.valid) domain.onSuccessContainer else scheme.onErrorContainer
                        val iconTint = if (result.valid) domain.signalOn else scheme.error
                        Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (result.valid) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (result.valid) {
                                        stringResource(R.string.proxy_yaml_valid_summary, result.nodes.size)
                                    } else {
                                        result.error?.takeIf(String::isNotBlank)
                                            ?: stringResource(R.string.proxy_yaml_invalid_fallback)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = fg,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun resolveNodeSourceLabel(
    node: ProxyNodeItem,
    subscriptionNames: Map<String, String>,
    hasAdvancedYaml: Boolean,
): String {
    if (hasAdvancedYaml) return stringResource(R.string.proxy_node_source_yaml)
    val subName = node.subscriptionIds.firstNotNullOfOrNull(subscriptionNames::get)
    return subName ?: stringResource(R.string.proxy_node_source_manual)
}

@Composable
private fun formatSubscriptionUpdatedTime(lastRefreshedAtMillis: Long?): String {
    if (lastRefreshedAtMillis == null) return stringResource(R.string.proxy_subscription_not_refreshed)
    val elapsedMinutes = ((System.currentTimeMillis() - lastRefreshedAtMillis).coerceAtLeast(0L)) / 60_000L
    return when {
        elapsedMinutes < 1L -> stringResource(R.string.proxy_subscription_updated_just_now)
        elapsedMinutes < 60L -> stringResource(R.string.proxy_subscription_updated_minutes, elapsedMinutes.toInt())
        elapsedMinutes < 24L * 60L -> stringResource(R.string.proxy_subscription_updated_hours, (elapsedMinutes / 60L).toInt())
        else -> stringResource(R.string.proxy_subscription_updated_days, (elapsedMinutes / (24L * 60L)).toInt())
    }
}

internal fun formatProtocolLabel(rawType: String): String = when (rawType.trim().lowercase()) {
    "ss" -> "Shadowsocks"
    "ssr" -> "ShadowsocksR"
    "vmess" -> "VMess"
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "hysteria" -> "Hysteria"
    "hysteria2", "hy2" -> "Hysteria2"
    "tuic" -> "TUIC"
    "wireguard", "wg" -> "WireGuard"
    "socks5" -> "SOCKS5"
    "http" -> "HTTP"
    "snell" -> "Snell"
    "anytls" -> "AnyTLS"
    "ssh" -> "SSH"
    else -> rawType.ifBlank { "Proxy" }.uppercase()
}

internal object ProxyTags {
    const val LIST = "proxy_settings_list"
    const val ENABLE_SWITCH = "proxy_enable_switch"
    const val STATUS_CARD = "proxy_status_card"
    const val BTN_TEST_LATENCY = "proxy_btn_test_latency"
    const val BTN_ADVANCED_YAML = "proxy_btn_advanced_yaml"
    const val BTN_SYNC_MANAGED = "proxy_btn_sync_managed"
    const val ACTION_TEST_LATENCY = "proxy_action_test_latency"
    const val ACTION_ADD_NODES = "proxy_action_add_nodes"
    const val MODE_CONTROL = "proxy_mode_control"
    const val MODE_SINGLE = "proxy_mode_single"
    const val MODE_FALLBACK = "proxy_mode_fallback"
    const val ADVANCED_BANNER = "proxy_advanced_banner"
    const val IMPORT_ENTRY = "proxy_import_entry"
    const val EMPTY_NODES = "proxy_empty_nodes"
    const val IMPORT_SHEET = "proxy_import_sheet"
    const val IMPORT_TAB_SUB = "proxy_import_tab_sub"
    const val IMPORT_TAB_URI = "proxy_import_tab_uri"
    const val IMPORT_SUB_NAME = "proxy_import_sub_name"
    const val IMPORT_SUB_URL = "proxy_import_sub_url"
    const val IMPORT_URI_INPUT = "proxy_import_uri_input"
    const val IMPORT_SUBMIT = "proxy_import_submit"
    const val YAML_VALIDATE = "proxy_yaml_validate"
    fun subscription(id: String) = "proxy_subscription:$id"
    fun node(id: String) = "proxy_node:$id"
    fun nodeSwitch(id: String) = "proxy_node_switch:$id"
    fun nodeDragHandle(id: String) = "proxy_node_drag:$id"
    fun nodeMoveUp(id: String) = "proxy_node_up:$id"
    fun nodeMoveDown(id: String) = "proxy_node_down:$id"
}
