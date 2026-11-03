package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.common.providerDisplayName
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelCapabilityBadges
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.common.verticalFadingEdges
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.ProvideGroupedPress

internal data class ProviderModelsScreenState(
    val providerName: String,
    val providerId: String,
    val isFetching: Boolean,
    val syncNotice: UiStatusNotice?,
    val models: List<ModelInfo>,
    val modelTestStates: Map<String, ModelTestState>,
    val catalogStatus: com.promenar.infinity.data.model.catalog.CatalogUpdateStatus? = null,
    /** 上次成功同步模型的时间；从未同步为 null。 */
    val lastSyncAtMillis: Long? = null,
)

internal data class ProviderModelsScreenActions(
    val onRefresh: () -> Unit,
    val onAdd: (id: String, name: String) -> Boolean,
    val onDisableAll: () -> Unit,
    val onDeleteAll: () -> Unit,
    val onUpdate: (ModelInfo) -> Unit,
    val onToggle: (String) -> Unit,
    val onTest: (String) -> Unit,
    val onCancelTest: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onClearNotice: () -> Unit,
    val onRefreshCatalog: () -> Unit = {},
    val onEditProvider: () -> Unit = {},
)

@Composable
fun ProviderModelsScreen(
    providerName: String = "",
    providerId: String = "",
    onNavigateBack: () -> Unit,
    onNavigateToSecondary: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val models by viewModel.providerModels.collectAsState()
    val modelTestStates by viewModel.modelTestStates.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val isFetching by viewModel.isFetchingModels.collectAsState()
    val syncNotice by viewModel.modelSyncNotice.collectAsState()
    val catalogStatus by viewModel.modelCatalogStatus.collectAsState()
    val providerStatuses by viewModel.providerStatuses.collectAsState()

    val provider = remember(providers, providerId) {
        providers.find { it.id == providerId }
    }
    // 提供商在别处（编辑页的“删除提供商”）被删掉后，模型页不再有归属，自动返回。
    var providerSeen by remember(providerId) { mutableStateOf(false) }
    LaunchedEffect(provider != null) {
        if (provider != null) {
            providerSeen = true
        } else if (providerSeen) {
            onNavigateBack()
        }
    }
    val effectiveTitle = provider?.let { providerDisplayName(it.name, it.protocolType) }
        ?: providerDisplayName(providerName, protocol = null)

    // 归属只依据稳定 providerId，禁止用可重名的展示名称猜测。
    val scopedModels = remember(models, providerId) {
        models.filter { it.providerId == providerId }
    }

    val state = ProviderModelsScreenState(
        providerName = effectiveTitle,
        providerId = providerId,
        isFetching = isFetching,
        syncNotice = syncNotice,
        models = scopedModels,
        modelTestStates = modelTestStates,
        catalogStatus = catalogStatus,
        lastSyncAtMillis = providerStatuses[providerId]?.lastModelSyncAtMillis,
    )

    val actions = ProviderModelsScreenActions(
        onRefresh = { viewModel.refreshProviderModels(providerId) },
        onAdd = { id, name -> viewModel.addCustomModel(providerId, id, name) },
        onDisableAll = { viewModel.disableAllModels(providerId) },
        onDeleteAll = { viewModel.deleteAllModels(providerId) },
        onUpdate = { viewModel.updateUserModel(it) },
        onToggle = { modelId -> viewModel.toggleModel(modelId) },
        onTest = { modelId -> viewModel.testModel(modelId) },
        onCancelTest = { modelId -> viewModel.cancelModelTest(modelId) },
        onDelete = { modelId -> viewModel.deleteModel(modelId) },
        onClearNotice = { viewModel.clearSyncNotice() },
        onRefreshCatalog = { viewModel.refreshModelCatalog() },
        onEditProvider = { onNavigateToSecondary(NavDestinations.providerForm(providerId)) },
    )

    ProviderModelsScreenContent(
        state = state,
        actions = actions,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * 模型列表与模型编辑页同屏切换（沿 X 轴共享切换）：编辑页打开时系统返回与预测性返回先关闭编辑页；
 * 同步移除了正在编辑的模型时自动退出编辑。
 */
@Composable
internal fun ProviderModelsScreenContent(
    state: ProviderModelsScreenState,
    actions: ProviderModelsScreenActions,
    onNavigateBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchQuery by rememberSaveable(state.providerId) { mutableStateOf("") }
    var selectedModelId by rememberSaveable(state.providerId) { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    // 列表滚动位置提到切换容器之外，从编辑页返回时停在原处。
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var completion by remember { mutableStateOf<SpecLibraryCompletion?>(null) }
    val specLibrary = rememberSpecLibraryUi(state.catalogStatus) { completion = it }
    completion?.let { done ->
        val message = specLibraryCompletionText(done)
        LaunchedEffect(done) {
            completion = null
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // 选中项始终从最新同步结果派生，删除或同步移除后不会继续编辑旧快照。
    val selectedModel = remember(state.models, selectedModelId) {
        state.models.firstOrNull { it.id == selectedModelId }
    }
    LaunchedEffect(selectedModelId, selectedModel) {
        if (selectedModelId != null && selectedModel == null) {
            selectedModelId = reconcileEditingModelId(selectedModelId, state.models)
        }
        if (selectedModelId == null) backProgress = 0f
    }
    PredictiveBackHandler(enabled = selectedModel != null) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            selectedModelId = null
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }

    val filteredModels = remember(state.models, searchQuery) {
        if (searchQuery.isBlank()) state.models
        else state.models.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.remoteModelId.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedModelId,
            transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState != null) },
            contentAlignment = Alignment.TopStart,
            label = "provider_models_editor_switch",
        ) { editingId ->
            val editing = editingId?.let { id -> state.models.firstOrNull { it.id == id } }
            if (editing != null) {
                val density = LocalDensity.current
                ModelEditorScreen(
                    model = editing,
                    testState = state.modelTestStates[editing.id] ?: ModelTestState.Idle,
                    onClose = { selectedModelId = null },
                    onUpdate = actions.onUpdate,
                    onTest = { actions.onTest(editing.id) },
                    onCancel = { actions.onCancelTest(editing.id) },
                    onDelete = {
                        actions.onDelete(editing.id)
                        selectedModelId = null
                    },
                    // 预测性返回：随手势右移并略微缩小，松手后由切换动效退回列表。
                    modifier = Modifier.graphicsLayer {
                        val p = backProgress
                        translationX = with(density) { 32.dp.toPx() } * p
                        scaleX = 1f - 0.06f * p
                        scaleY = 1f - 0.06f * p
                    },
                )
            } else {
                ProviderModelsListPage(
                    state = state,
                    actions = actions,
                    filteredModels = filteredModels,
                    searchQuery = searchQuery,
                    onSearchQuery = { searchQuery = it },
                    listState = listState,
                    onNavigateBack = onNavigateBack,
                    onAdd = { showAddDialog = true },
                    onDeleteAll = { showDeleteAllDialog = true },
                    onRowClick = { model ->
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        selectedModelId = model.id
                    },
                    specLibrary = specLibrary,
                )
            }
        }
        // 规格库更新完成的提示放在切换容器之外，编辑页打开时同样可见。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data ->
            InfinitySnackbar(InfinitySnackbarData(data.visuals.message, SnackbarType.SUCCESS))
        }
    }

    if (showDeleteAllDialog) {
        InfinityConfirmDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG),
            title = stringResource(R.string.provider_models_delete_all_title),
            message = stringResource(R.string.provider_models_delete_all_message),
            confirmText = stringResource(R.string.shared_btn_delete),
            confirmButtonModifier = Modifier.testTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON),
            isDestructive = true,
            onConfirm = {
                actions.onDeleteAll()
                showDeleteAllDialog = false
            },
            onCancel = { showDeleteAllDialog = false },
        )
    }

    if (showAddDialog) {
        ProviderModelsAddSheet(
            onDismiss = { showAddDialog = false },
            onSubmit = { id, name ->
                val added = actions.onAdd(id, name)
                if (added) {
                    showAddDialog = false
                }
                added
            },
            onAdded = { showAddDialog = false },
        )
    }
}

@Composable
private fun ProviderModelsListPage(
    state: ProviderModelsScreenState,
    actions: ProviderModelsScreenActions,
    filteredModels: List<ModelInfo>,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onNavigateBack: () -> Unit,
    onAdd: () -> Unit,
    onDeleteAll: () -> Unit,
    onRowClick: (ModelInfo) -> Unit,
    specLibrary: SpecLibraryUi?,
) {
    InfinitySettingsPageLayout(
        title = state.providerName,
        onBack = onNavigateBack,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SCREEN_ROOT),
        actions = {
            ProviderModelsTopActions(
                onAdd = onAdd,
                onEditProvider = actions.onEditProvider,
                onDisableAll = actions.onDisableAll,
                onDeleteAll = onDeleteAll,
                specLibrary = specLibrary,
                onUpdateSpecLibrary = actions.onRefreshCatalog,
            )
        },
    ) { contentPadding ->
        // 固定搜索栏、同步栏与规格库失败提示；状态条与模型随同一列表滚动，列表底部延伸到系统导航栏后方。
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    top = contentPadding.calculateTopPadding(),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                )
                .imePadding(),
        ) {
            InfinitySearchBar(
                value = searchQuery,
                onValueChange = onSearchQuery,
                placeholder = stringResource(R.string.provider_models_search),
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD),
            )
            ProviderModelsSyncBar(
                enabledCount = state.models.count { it.enabled },
                totalCount = state.models.size,
                isFetching = state.isFetching,
                lastSyncAtMillis = state.lastSyncAtMillis,
                onRefresh = actions.onRefresh,
            )
            val failure = specLibrary?.failure
            AnimatedVisibility(visible = failure != null) {
                // 收起动画期间沿用最后一次的失败内容。
                val shown = remember { mutableStateOf<SpecLibraryUi?>(null) }
                if (failure != null) shown.value = specLibrary
                shown.value?.let { ui ->
                    ModelSpecLibraryFailureNotice(
                        failure = ui.failure ?: return@let,
                        kept = ui.kept,
                        onRetry = actions.onRefreshCatalog,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            val reducedState = reduceProviderModelsListState(
                isLoading = state.isFetching,
                modelCount = state.models.size,
                filteredCount = filteredModels.size,
                searchQuery = searchQuery,
                notice = state.syncNotice,
            )
            // 同步反馈：据 severity 着色，据 code+args 经 stringResource 双语格式化；列表已表达的状态不重复提示。
            val effectiveSyncNotice = visibleSyncNotice(state.syncNotice, reducedState)
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = contentPadding.calculateBottomPadding()),
                modifier = Modifier
                    .weight(1f)
                    .verticalFadingEdges(top = listState.canScrollBackward)
                    .testTag(UiTags.PROVIDER_MODELS_LIST),
            ) {
                if (effectiveSyncNotice != null) {
                    item("notice") {
                        Box(modifier = Modifier.animateItem().padding(bottom = 12.dp).testTag(UiTags.PROVIDER_MODELS_NOTICE)) {
                            ModelSyncNoticeBanner(
                                notice = effectiveSyncNotice,
                                dismissEnabled = !state.isFetching,
                                onDismiss = actions.onClearNotice,
                            )
                        }
                    }
                }

                when (reducedState) {
                    ProviderModelsListState.Loading -> item("loading") {
                        Box(Modifier.animateItem()) { ProviderModelsSkeleton() }
                    }

                    ProviderModelsListState.Error -> item("error") {
                        Box(Modifier.animateItem()) {
                            ProviderModelsListMessage(
                                message = stringResource(R.string.provider_models_sync_failed),
                                tag = UiTags.PROVIDER_MODELS_STATE_ERROR,
                                assertive = true,
                                primaryLabel = stringResource(R.string.provider_models_retry),
                                onPrimary = actions.onRefresh,
                                onAdd = onAdd,
                            )
                        }
                    }

                    ProviderModelsListState.Empty -> item("empty") {
                        Box(Modifier.animateItem()) {
                            ProviderModelsListMessage(
                                message = stringResource(R.string.provider_models_sync_no_models),
                                tag = UiTags.PROVIDER_MODELS_STATE_EMPTY,
                                primaryLabel = stringResource(R.string.provider_models_sync_action),
                                onPrimary = actions.onRefresh,
                                onAdd = onAdd,
                            )
                        }
                    }

                    ProviderModelsListState.SearchEmpty -> item("search-empty") {
                        Box(Modifier.animateItem()) {
                            ProviderModelsListMessage(
                                message = stringResource(R.string.provider_models_search_empty, searchQuery),
                                tag = UiTags.PROVIDER_MODELS_STATE_SEARCH_EMPTY,
                            )
                        }
                    }

                    ProviderModelsListState.Content -> itemsIndexed(
                        filteredModels,
                        key = { _, model -> model.id },
                    ) { index, model ->
                        // 搜索过滤、启用全部、删除时模型行平滑补位。
                        Box(Modifier.animateItem()) {
                            ProviderModelsModelRow(
                                model = model,
                                groupIndex = index,
                                groupSize = filteredModels.size,
                                onRowClick = { onRowClick(model) },
                                onToggle = { actions.onToggle(model.id) },
                            )
                        }
                    }
                }

            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsAddSheet(
    onDismiss: () -> Unit,
    onSubmit: (id: String, name: String) -> Boolean,
    onAdded: () -> Unit,
) {
    InfinityModalSheet(
        onDismissRequest = onDismiss,
        height = InfinitySheetHeight.Content,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ADD_SHEET),
    ) {
        Text(
            text = stringResource(R.string.provider_models_add_custom),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        AddCustomModelForm(onSubmit = onSubmit, onAdded = onAdded)
    }
}

@Composable
internal fun AddCustomModelForm(
    onSubmit: (id: String, name: String) -> Boolean,
    onAdded: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var submissionFailed by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .testTag(UiTags.PROVIDER_MODELS_ADD_FORM_LIST),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("model-id") {
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = modelId,
                onValueChange = {
                    modelId = it
                    submissionFailed = false
                },
                label = { Text(stringResource(R.string.provider_models_field_model_id)) },
                placeholder = { Text(stringResource(R.string.provider_models_placeholder_model_id)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.PROVIDER_MODELS_ADD_ID_FIELD),
            )
        }
        item("model-name") {
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = modelName,
                onValueChange = {
                    modelName = it
                    submissionFailed = false
                },
                label = { Text(stringResource(R.string.provider_models_field_display_name)) },
                placeholder = { Text(stringResource(R.string.provider_models_placeholder_optional)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD),
            )
        }
        if (submissionFailed) {
            item("submission-error") {
                val failureMessage = stringResource(R.string.generation_failure_invalid_request)
                Text(
                    text = failureMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        stateDescription = failureMessage
                    },
                )
            }
        }
        item("submit") {
            Button(
                onClick = {
                    if (modelId.isNotBlank()) {
                        if (onSubmit(modelId, modelName)) {
                            onAdded()
                        } else {
                            submissionFailed = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON),
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
        }
    }
}

/** 顶栏动作：+ 添加模型；⋮ 内为编辑提供商、全部禁用、更新规格库、全部删除。 */
@Composable
internal fun ProviderModelsTopActions(
    onAdd: () -> Unit,
    onEditProvider: () -> Unit,
    onDisableAll: () -> Unit,
    onDeleteAll: () -> Unit,
    specLibrary: SpecLibraryUi? = null,
    onUpdateSpecLibrary: () -> Unit = {},
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = onAdd,
        modifier = Modifier.size(48.dp).testTag(UiTags.PROVIDER_MODELS_ACTION_ADD),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.provider_models_add))
    }
    Box {
        IconButton(
            onClick = { overflowExpanded = true },
            modifier = Modifier.size(48.dp).testTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_cd_options))
        }
        InfinityDropdownMenu(
            expanded = overflowExpanded,
            onDismissRequest = { overflowExpanded = false },
        ) {
            InfinityMenuItem(
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.provider_models_edit_provider)) },
                onClick = {
                    overflowExpanded = false
                    onEditProvider()
                },
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ACTION_EDIT_PROVIDER),
            )
            InfinityMenuItem(
                leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.provider_models_disable_all)) },
                onClick = {
                    overflowExpanded = false
                    onDisableAll()
                },
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL),
            )
            if (specLibrary != null) {
                InfinityMenuDivider()
                ModelSpecLibraryMenuItem(specLibrary, onUpdate = onUpdateSpecLibrary)
            }
            InfinityMenuDivider()
            InfinityMenuItem(
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.provider_models_delete_all)) },
                onClick = {
                    overflowExpanded = false
                    onDeleteAll()
                },
                destructive = true,
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL),
            )
        }
    }
}

/**
 * ⋮ 菜单的“更新规格库”：第二行是当前版本。点按后菜单不收起，原地显示进度，
 * 更新中再次点按无效；完成后由页面底部提示新版本。
 */
@Composable
internal fun ModelSpecLibraryMenuItem(specLibrary: SpecLibraryUi, onUpdate: () -> Unit) {
    val updating = specLibrary.updating
    val title = stringResource(if (updating) R.string.model_spec_updating else R.string.model_spec_update)
    val version = specLibraryVersionText(specLibrary.current)
    InfinityMenuItem(
        leadingIcon = {
            if (updating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Update, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                Text(
                    version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = { if (!updating) onUpdate() },
        haptic = if (updating) null else com.promenar.infinity.ui.common.InfinityHapticEvent.SELECTION,
        modifier = Modifier
            .semantics { if (updating) stateDescription = title }
            .testTag(UiTags.PROVIDER_MODELS_ACTION_SPEC_LIBRARY),
    )
}

/** 搜索栏下一行：左侧“x / y 个已启用”与上次同步时间，右侧同步按钮（同步中按钮内转圈）。 */
@Composable
internal fun ProviderModelsSyncBar(
    enabledCount: Int,
    totalCount: Int,
    isFetching: Boolean,
    lastSyncAtMillis: Long?,
    onRefresh: () -> Unit,
) {
    val now = rememberMinuteClock()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.provider_models_enabled_count, enabledCount, totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ENABLED_COUNT),
            )
            Text(
                text = lastSyncAtMillis?.let {
                    stringResource(R.string.provider_models_last_sync, relativeTimeLabel(it, now))
                } ?: stringResource(R.string.provider_models_never_synced),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_LAST_SYNC),
            )
        }
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !isFetching,
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .height(48.dp)
                .testTag(UiTags.PROVIDER_MODELS_ACTION_SYNC),
        ) {
            AnimatedContent(
                targetState = isFetching,
                transitionSpec = { InfinityMotion.fadeThrough() },
                contentAlignment = Alignment.Center,
                label = "provider_models_sync_icon",
            ) { fetching ->
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (isFetching) R.string.provider_models_syncing else R.string.provider_models_sync_action),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ProviderModelsModelRow(
    model: ModelInfo,
    onRowClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    groupIndex: Int = 0,
    groupSize: Int = 1,
) {
    val remoteModelId = remember(model.id, model.remoteModelId) {
        model.remoteModelId.ifBlank { model.id.substringAfter("::", model.id) }
    }
    val badges = modelCapabilityBadges(model)
    val capabilitiesDescription = if (badges.isEmpty()) {
        ""
    } else {
        stringResource(
            R.string.provider_models_capabilities_description,
            badges.joinToString(stringResource(R.string.model_editor_list_separator)) { it.label },
        )
    }
    val contextShort = formatTokenShort(model.contextLength)

    // 全部模型拼成一个连续分组；行间细线从正文起点开始。
    ProvideGroupedPress {
        Column(modifier = modifier.fillMaxWidth().bettboxListGroupItem(groupIndex, groupSize)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRowClick)
                    .testTag(UiTags.providerModelsModelCard(model.id))
                    .padding(start = InfinitySpacing.RowHorizontal, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(ModelTileSize)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ModelBrandIcon(modelId = remoteModelId, modifier = Modifier.size(20.dp), contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        remoteModelId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .testTag(UiTags.providerModelsRemoteId(model.id))
                            .semantics { contentDescription = remoteModelId },
                    )
                    ModelCapabilityBadges(
                        typeLabel = stringResource(modelTypeLabelRes(model.type)),
                        capabilities = badges,
                        contextLabel = contextShort.ifEmpty { null },
                        capabilitiesDescription = capabilitiesDescription,
                        contextDescription = contextShort.takeIf { it.isNotEmpty() }?.let {
                            stringResource(R.string.provider_models_context_description, it)
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                InfinitySwitch(
                    checked = model.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.providerModelsToggleAction(model.id)),
                )
            }
            if (groupIndex < groupSize - 1) {
                InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + ModelTileSize + 12.dp)
            }
        }
    }
}

private val ModelTileSize = 36.dp

/** 规格库更新失败的浅色提示：原因、沿用的版本与“重试”。正常与更新中不显示。 */
@Composable
internal fun ModelSpecLibraryFailureNotice(
    failure: SpecLibraryFailure,
    kept: SpecLibraryVersion?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reason = specLibraryFailureText(failure)
    val keptText = specLibraryKeptText(kept)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.error.copy(alpha = 0.08f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
            .testTag(UiTags.PROVIDER_MODELS_SPEC_FAILURE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            Text(keptText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        IconButton(onClick = onRetry, modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SPEC_RETRY)) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.model_spec_retry),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 同步反馈：四色状态条，错误立即播报；同步进行中不可关闭。 */
@Composable
internal fun ModelSyncNoticeBanner(
    notice: UiStatusNotice,
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val message = notice.formatSyncMessage(
        unknownFallback = stringResource(R.string.provider_models_sync_failed),
    )
    InfinityStatusStrip(
        kind = notice.severity.statusKind(),
        message = message,
        modifier = Modifier.semantics { stateDescription = message },
        onDismiss = onDismiss,
        dismissEnabled = dismissEnabled,
        dismissModifier = Modifier.testTag(UiTags.PROVIDER_MODELS_NOTICE_DISMISS),
    )
}

/** 加载中：五行骨架代替单行“加载中”，读屏播报一次正在同步。 */
@Composable
private fun ProviderModelsSkeleton() {
    val loading = stringResource(R.string.provider_models_loading)
    val transition = rememberInfiniteTransition(label = "provider_models_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "provider_models_skeleton_alpha",
    )
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    BettboxListGroup(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.PROVIDER_MODELS_STATE_LOADING)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = loading
                contentDescription = loading
            },
    ) {
        Column(Modifier.testTag(UiTags.PROVIDER_MODELS_SKELETON).graphicsLayer { this.alpha = alpha }) {
            repeat(5) { index ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(ModelTileSize).background(block, RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.55f else 0.4f).height(12.dp).background(block, RoundedCornerShape(4.dp)))
                        Box(Modifier.fillMaxWidth(0.7f).height(10.dp).background(block, RoundedCornerShape(4.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(width = 40.dp, height = 24.dp).background(block, RoundedCornerShape(12.dp)))
                }
                if (index < 4) InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + ModelTileSize + 12.dp)
            }
        }
    }
}

/** 空态与错误态：说明文字加主动作（同步 / 重试）与“手动添加”。 */
@Composable
private fun ProviderModelsListMessage(
    message: String,
    tag: String,
    assertive: Boolean = false,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    stateDescription = message
                },
            )
            val syncAction = if (primaryLabel != null && onPrimary != null) primaryLabel to onPrimary else null
            when {
                syncAction != null && onAdd != null -> InfinityPillRow(Modifier.fillMaxWidth()) {
                    ProviderModelsAddManuallyButton(onAdd)
                    ProviderModelsEmptySyncButton(syncAction.first, syncAction.second)
                }
                syncAction != null -> ProviderModelsEmptySyncButton(syncAction.first, syncAction.second)
                onAdd != null -> ProviderModelsAddManuallyButton(onAdd)
            }
        }
    }
}

@Composable
private fun ProviderModelsAddManuallyButton(onClick: () -> Unit) {
    InfinityPillButton(
        text = stringResource(R.string.provider_models_add_manually),
        onClick = onClick,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_EMPTY_ADD),
    )
}

@Composable
private fun ProviderModelsEmptySyncButton(label: String, onClick: () -> Unit) {
    InfinityPillButton(
        text = label,
        onClick = onClick,
        icon = Icons.Rounded.Sync,
        tone = InfinityPillTone.PRIMARY,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_EMPTY_SYNC),
    )
}

private fun NoticeSeverity.statusKind(): InfinityStatusKind = when (this) {
    NoticeSeverity.Success -> InfinityStatusKind.Success
    NoticeSeverity.Warning -> InfinityStatusKind.Warning
    NoticeSeverity.Error -> InfinityStatusKind.Error
    NoticeSeverity.Info -> InfinityStatusKind.Info
}

/**
 * 用 stringResource 把结构化 notice 格式化为本地化文案。
 * 未知 code 回落到 [unknownFallback] 安全通用文案，**不**直接泄漏 code 或裸串。
 */
@Composable
private fun UiStatusNotice.formatSyncMessage(unknownFallback: String): String {
    val template = ModelSyncNotice.template(this) ?: return unknownFallback
    return if (template.args.isEmpty()) {
        stringResource(template.resourceId)
    } else {
        stringResource(template.resourceId, *template.args.toTypedArray())
    }
}
