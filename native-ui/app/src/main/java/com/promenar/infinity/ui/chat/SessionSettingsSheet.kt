package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.params.sampling
import com.promenar.infinity.ui.common.params.ParameterSchemeSection
import com.promenar.infinity.ui.common.params.SAMPLING_PARAM_ORDER
import com.promenar.infinity.ui.common.params.SamplingEditorActions
import com.promenar.infinity.ui.common.params.SamplingEditorState
import com.promenar.infinity.ui.common.params.SamplingParamLayout
import com.promenar.infinity.ui.common.params.SamplingParamRows
import com.promenar.infinity.ui.common.params.samplingParamLayout
import com.promenar.infinity.domain.usecase.resolveKeepRecentTurns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Build
import com.promenar.infinity.ui.skills.SkillCatalogViewModel
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.promenar.infinity.ui.common.InfinityCapsuleTabRow
import com.promenar.infinity.ui.common.InfinityTabItem
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import com.promenar.infinity.ui.common.ModelSelectionListItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.promenar.infinity.data.manager.ProviderManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.remote.mcp.McpEndpointPolicy
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.common.network.NetworkLightsRow
import com.promenar.infinity.ui.common.network.NetworkLightsState
import com.promenar.infinity.ui.common.network.badgeFor
import com.promenar.infinity.ui.common.network.rememberNetworkLightsState
import com.promenar.infinity.ui.common.network.rememberTailscaleRetry
import com.promenar.infinity.ui.testing.UiTags
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun filterLocalInferenceModels(
    models: List<ModelInfo>,
    localProviderIds: Set<String>,
    localInferenceAvailable: Boolean,
): List<ModelInfo> {
    if (localInferenceAvailable || localProviderIds.isEmpty()) return models
    return models.filterNot { model ->
        val providerId = model.providerId ?: model.id.substringBefore("::").takeIf { "::" in model.id }
        providerId in localProviderIds
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    chatViewModel: ChatViewModel,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    /** 打开时所在的标签页：0 模型、1 参数、2 工具、3 设置。 */
    initialPage: Int = 0,
) {
    if (!show) return

    val visibleProviderModels = rememberVisibleProviderModels()
    
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val compressionState by chatViewModel.compressionState.collectAsStateWithLifecycle()
    val session = uiState.session
    
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 3), pageCount = { 4 })
    val tabTitles = listOf(
        stringResource(R.string.sheet_tab_model),
        stringResource(R.string.sheet_tab_thinking),
        stringResource(R.string.sheet_tab_tools),
        stringResource(R.string.sheet_tab_settings)
    )

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Full) {
        Text(
            stringResource(R.string.sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val sessionTabs = remember(tabTitles) {
                tabTitles.map { InfinityTabItem(title = it) }
            }
            InfinityCapsuleTabRow(
                tabs = sessionTabs,
                selectedIndex = pagerState.currentPage,
                onTabSelected = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Small),
            )

            Spacer(modifier = Modifier.height(InfinitySpacing.Small))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> ModelPanel(
                        selectedModelId = session?.modelId ?: "",
                        onSelect = { modelId ->
                            chatViewModel.updateModelId(modelId)
                            onDismiss()
                        },
                        allModels = visibleProviderModels,
                        networkLights = rememberNetworkLightsState(),
                        onOpenTailscaleSettings = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
                    )
                    1 -> ParamsPanel(
                        session = session,
                        chatViewModel = chatViewModel
                    )
                    2 -> ToolsPanel(chatViewModel = chatViewModel, session = session, models = visibleProviderModels, onDismiss = onDismiss)
                    3 -> SettingsPanel(
                        session = session,
                        chatViewModel = chatViewModel,
                        isGenerating = uiState.isGenerating,
                        isCompressing = compressionState.isCompressing,
                        onCompactNow = {
                            chatViewModel.compactNow()
                            onDismiss()
                        },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

/** 会话可切换的服务商模型；本机推理不可用时去掉本地服务商的模型。 */
@Composable
private fun rememberVisibleProviderModels(): List<ModelInfo> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providerModels by settingsViewModel.providerModels.collectAsStateWithLifecycle()
    val providers by settingsViewModel.providers.collectAsStateWithLifecycle()
    return remember(providerModels, providers) {
        filterLocalInferenceModels(
            models = providerModels,
            localProviderIds = providers.filter { it.protocolType is ProtocolType.Local }.mapTo(mutableSetOf()) { it.id },
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        )
    }
}

/**
 * 宽窗口下点输入区模型按钮弹出的模型速选：与会话设置“模型”页同一列表，选中即关闭；
 * 底部“更多会话设置”打开完整会话设置（参数页）。
 */
@Composable
internal fun ModelQuickPickPopover(
    anchor: InfinityPopoverAnchor,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onMoreSettings: () -> Unit,
    onOpenTailscaleSettings: (() -> Unit)? = null,
) {
    val models = rememberVisibleProviderModels()
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    InfinityPopover(
        show = true,
        anchor = anchor,
        onDismissRequest = onDismiss,
        side = InfinityPopoverSide.Above,
        align = InfinityPopoverAlign.Start,
        width = ModelQuickPickWidth,
        title = stringResource(R.string.chat_model_quick_pick_title),
        modifier = Modifier.testTag(UiTags.CHAT_MODEL_QUICK_PICK),
    ) {
        Box(modifier = Modifier.weight(1f, fill = false).heightIn(max = ModelQuickPickListMaxHeight)) {
            ModelPanel(
                selectedModelId = uiState.session?.modelId ?: "",
                onSelect = { modelId ->
                    chatViewModel.updateModelId(modelId)
                    onDismiss()
                },
                allModels = models,
                networkLights = rememberNetworkLightsState(),
                onOpenTailscaleSettings = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .clickable(role = Role.Button, onClick = onMoreSettings)
                .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small)
                .testTag(UiTags.CHAT_MODEL_QUICK_PICK_MORE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.chat_model_quick_pick_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ModelQuickPickWidth = 360.dp
private val ModelQuickPickListMaxHeight = 480.dp

@Composable
internal fun ModelPanel(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    allModels: List<ModelInfo>,
    networkLights: NetworkLightsState? = null,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    onRetryTailscale: (() -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    val modelItems = remember(allModels, selectedModelId) {
        sessionSelectableModels(allModels, selectedModelId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal)
            .padding(top = 16.dp)
    ) {
        InfinitySearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.common_model_picker_search)
        )

        if (networkLights != null) {
            NetworkLightsRow(
                state = networkLights,
                onOpenTailscaleSettings = onOpenTailscaleSettings,
                onRetryTailscale = onRetryTailscale ?: rememberTailscaleRetry(),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        val filtered = remember(searchQuery, modelItems) {
            if (searchQuery.isBlank()) modelItems else modelItems.filter {
                it.displayName.contains(searchQuery, true)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.CHAT_MODEL_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(filtered, key = { _, it -> it.selectionId }) { index, model ->
                val isSelected = model.selectionId == selectedModelId
                ModelSelectionListItem(
                    model = model,
                    selected = isSelected,
                    onClick = { onSelect(model.selectionId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.chatModelOption(model.selectionId)),
                    networkBadge = networkLights?.badgeFor(model),
                )
            }
        }
    }
}

/** 会话可选模型：已启用、可走对话接口，或是当前已选模型。 */
internal fun sessionSelectableModels(
    models: List<ModelInfo>,
    selectedModelId: String,
): List<ModelSelectionUiModel> = filterSessionSelectionModels(
    models = models.filter { it.enabled }.map { it.toModelSelectionUiModel() },
    selectedModelId = selectedModelId,
)

internal fun filterSessionSelectionModels(
    models: List<ModelSelectionUiModel>,
    selectedModelId: String,
): List<ModelSelectionUiModel> = models.filter { model ->
    model.selectionId == selectedModelId ||
        model.isChatSelectionCandidate()
}

@Composable
private fun ParamsPanel(
    session: com.promenar.infinity.data.model.Session?,
    chatViewModel: ChatViewModel,
) {
    val params = session?.inferenceParams ?: com.promenar.infinity.data.model.InferenceParams()
    val agentDefaults by androidx.compose.runtime.produceState<com.promenar.infinity.data.model.InferenceParams?>(
        initialValue = null, session?.agentId,
    ) { value = chatViewModel.agentInferenceDefaults() }
    val providerManager = ProviderManager.getInstance()
    val providers by providerManager.providers.collectAsStateWithLifecycle()
    val providerModels by providerManager.providerModels.collectAsStateWithLifecycle()
    val modelKey = session?.modelId?.takeIf(String::isNotBlank)
    val schemesFlow = remember(modelKey) {
        modelKey?.let(chatViewModel::parameterSchemes) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val schemes by schemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile = remember(modelKey, providers, providerModels) {
        sessionModelParameterProfile(modelKey, providers, providerModels)
    }
    val storedEffort = com.promenar.infinity.data.model.params.ReasoningEffort.fromWireName(
        params.reasoningEffort ?: agentDefaults?.reasoningEffort,
    )
    val editor = sessionSamplingEditorState(params, agentDefaults, samplingParamLayout(profile, storedEffort), schemes)
    val actions = remember(chatViewModel, modelKey, editor) {
        SamplingEditorActions(
            onApplyModelDefault = chatViewModel::applyModelDefaultSampling,
            onApplyScheme = chatViewModel::applyParameterScheme,
            onParamChange = chatViewModel::updateSamplingParam,
            onSaveScheme = { name -> modelKey?.let { chatViewModel.saveParameterScheme(it, name, editor.savableValues) } },
            onOverwriteScheme = { scheme -> chatViewModel.overwriteParameterScheme(scheme, editor.savableValues) },
            onRenameScheme = chatViewModel::renameParameterScheme,
            onDeleteScheme = chatViewModel::deleteParameterScheme,
        )
    }
    SessionParamsContent(
        params = params,
        editor = editor,
        modelName = modelKey?.let { resolveModelDisplayName(it, providerModels) },
        actions = actions,
    )
}

/** 会话参数页的编辑器数据；未识别的模型显示全部参数。 */
internal fun sessionSamplingEditorState(
    params: com.promenar.infinity.data.model.InferenceParams,
    agentDefaults: com.promenar.infinity.data.model.InferenceParams?,
    layout: SamplingParamLayout,
    schemes: List<com.promenar.infinity.data.model.params.ParameterScheme>,
) = SamplingEditorState(
    own = params.sampling,
    inherited = agentDefaults?.sampling ?: com.promenar.infinity.data.model.params.SamplingValues(),
    followsScheme = params.parameterSchemeId != null,
    baseSchemeId = params.parameterSchemeId,
    layout = layout,
    schemes = schemes,
)

@Composable
internal fun SessionParamsContent(
    params: com.promenar.infinity.data.model.InferenceParams,
    editor: SamplingEditorState = sessionSamplingEditorState(params, null, SamplingParamLayout(SAMPLING_PARAM_ORDER), emptyList()),
    modelName: String? = null,
    actions: SamplingEditorActions = SamplingEditorActions(),
    initialExpanded: com.promenar.infinity.data.model.params.SamplingParam? = null,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = InfinitySpacing.ScreenHorizontal), state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 24.dp)) {
        item {
            ParameterSchemeSection(editor, actions, modelName, scrollState = listState)
        }
        item {
            InfinitySettingsSection(title = stringResource(R.string.params_section_sampling)) {
                SamplingParamRows(editor, actions, ownLabel = stringResource(R.string.params_source_session), initialExpanded = initialExpanded)
            }
        }
    }
}

@Composable
private fun ToolsPanel(
    chatViewModel: ChatViewModel,
    session: com.promenar.infinity.data.model.Session?,
    models: List<ModelInfo>,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val skillsViewModel: SkillCatalogViewModel = viewModel(
        factory = SkillCatalogViewModel.factory(context.applicationContext as android.app.Application),
    )
    LaunchedEffect(session?.id) { skillsViewModel.bind(session?.id) }
    val skillsState by skillsViewModel.state.collectAsStateWithLifecycle()
    val skillsError by skillsViewModel.error.collectAsStateWithLifecycle()
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(skillsError) {
        skillsError?.let { message ->
            android.widget.Toast.makeText(context, resources.getString(R.string.skills_runtime_error, message), android.widget.Toast.LENGTH_SHORT).show()
            skillsViewModel.consumeError()
        }
    }
    val navigation = LocalSessionSettingsNavigation.current
    val mcpServers by chatViewModel.availableSessionMcpServers.collectAsStateWithLifecycle()
    val mcpSettings = (context.applicationContext as? InfinityApplication)?.mcpConnectionSettings
    val mcpConnectionRevision = mcpSettings?.revision?.collectAsStateWithLifecycle()?.value
    val options = session?.options ?: com.promenar.infinity.data.model.SessionOptions()
    SessionToolsContent(
        options = options,
        executionMode = session?.executionMode?.ifEmpty { "workspace" } ?: "workspace",
        toolCallingSupported = remember(session?.modelId, models) { modelSupportsToolCalling(session?.modelId, models) },
        skillsSummary = sessionSkillsSummary(skillsState),
        onToolToggle = chatViewModel::toggleTool, onExecutionModeChange = chatViewModel::updateExecutionMode,
        onOpenSkills = navigation?.let { nav -> { onDismiss(); nav.openSkills() } },
        additionalSections = {
        val selectableMcpServers = remember(mcpServers, mcpConnectionRevision) {
            mcpServers.filter { server ->
                server.enabled && (mcpSettings?.isAllowed(server.id, server.type, server.url)
                    ?: McpEndpointPolicy.isAllowed(server.type, server.url))
            }
        }
        if (selectableMcpServers.isNotEmpty()) {
            InfinitySettingsSection(
                title = stringResource(R.string.sheet_tool_mcp_servers),
                footer = UnavailableReasons.executionMode(options.toolsEnabled)?.let { stringResource(it) },
            ) {
                selectableMcpServers.forEach { server ->
                    SettingsToggle(
                        title = server.name,
                        icon = Icons.Rounded.Sync,
                        checked = server.id in session?.activeMcpServerIds.orEmpty(),
                        enabled = options.toolsEnabled,
                        onCheckedChange = { chatViewModel.toggleSessionMcpServer(server.id) },
                    )
                }
            }
        }

        },
    )
}

/**
 * “工具”标签：工具使用（总开关、关闭时保留联网搜索、执行模式）置顶，其后是技能只读摘要与 MCP，最后是会话选项。
 * 工具关闭时执行模式、技能与 MCP 置灰，页脚说明本会话还剩什么（S1–S4）。
 */
@Composable
internal fun SessionToolsContent(
    options: com.promenar.infinity.data.model.SessionOptions = com.promenar.infinity.data.model.SessionOptions(),
    executionMode: String = "workspace",
    /** 当前模型不支持工具调用时隐藏“关闭工具时保留联网搜索”。 */
    toolCallingSupported: Boolean = true,
    /** 技能摘要；首次扫描完成前为 null，不显示技能分组。 */
    skillsSummary: SessionSkillsSummary? = SessionSkillsSummary(enabledUserSkills = 0, projectSkills = null, workspaceName = null),
    onToolToggle: (String, Boolean) -> Unit = { _, _ -> },
    onExecutionModeChange: (String) -> Unit = {},
    /** 跳转全局技能包；为 null 时摘要行不可点。 */
    onOpenSkills: (() -> Unit)? = null,
    additionalSections: @Composable () -> Unit = {},
) {
    val selectedExecutionMode = ExecutionMode.valueOf(
        com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(executionMode).name,
    )
    var showModePicker by remember { mutableStateOf(false) }
    val modePickerAnchor = rememberInfinityPopoverAnchor()
    val toolsEnabled = options.toolsEnabled
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        InfinitySettingsSection(
            title = stringResource(R.string.sheet_tool_section_usage),
            footer = when (toolsOffState(options, toolCallingSupported)) {
                ToolsOffState.WebSearchOnly -> stringResource(R.string.settings_ia_tools_off_web_only)
                ToolsOffState.NoTools -> stringResource(R.string.settings_ia_tools_off_none)
                null -> null
            },
        ) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_tools_enabled),
                checked = toolsEnabled,
                icon = Icons.Rounded.Build,
                onCheckedChange = { onToolToggle("toolsEnabled", it) },
                modifier = Modifier.testTag("session_tools_enabled"),
            )
            val keepWebSearchReason = UnavailableReasons.keepWebSearch(toolCallingSupported)
            SettingsToggle(
                title = stringResource(R.string.settings_ia_keep_web_search),
                description = stringResource(keepWebSearchReason ?: R.string.settings_ia_keep_web_search_desc),
                checked = options.keepWebSearchWhenToolsOff,
                leadingSpacer = true,
                enabled = keepWebSearchReason == null,
                onCheckedChange = { onToolToggle("keepWebSearchWhenToolsOff", it) },
                modifier = Modifier.testTag("session_keep_web_search"),
            )
            val executionModeReason = UnavailableReasons.executionMode(toolsEnabled)
            InfinityValueRow(
                title = stringResource(R.string.sheet_tool_execution_mode),
                value = stringResource(selectedExecutionMode.labelRes),
                subtitle = executionModeReason?.let { stringResource(it) },
                onClick = { if (!modePickerAnchor.consumeRecentDismiss()) showModePicker = true },
                enabled = executionModeReason == null,
                modifier = Modifier.infinityPopoverAnchor(modePickerAnchor).testTag("session_execution_mode_picker"),
            )
        }
        skillsSummary?.let { summary ->
            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_skills_section),
                footer = stringResource(R.string.settings_ia_skills_footer),
            ) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = pluralStringResource(R.plurals.settings_ia_skills_enabled, summary.enabledUserSkills, summary.enabledUserSkills),
                    subtitle = summary.projectSkills?.let { count ->
                        val projectLabel = pluralStringResource(R.plurals.settings_ia_project_skills, count, count)
                        summary.workspaceName?.let { stringResource(R.string.settings_ia_project_skills_in, projectLabel, it) } ?: projectLabel
                    },
                    onClick = { onOpenSkills?.invoke() },
                    enabled = toolsEnabled && onOpenSkills != null,
                    showChevron = onOpenSkills != null,
                    modifier = Modifier.testTag("session_skills_summary"),
                )
            }
        }
        additionalSections()
        InfinitySettingsSection(title = stringResource(R.string.settings_ia_session_options)) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_time_injection),
                checked = options.enableTimeInjection,
                icon = Icons.Rounded.Timer,
                onCheckedChange = { onToolToggle("timeInjection", it) },
            )
        }
    }
    InfinityBottomSheet(show = showModePicker, onDismiss = { showModePicker = false },
        title = stringResource(R.string.sheet_tool_execution_mode), scrollContent = true,
        popover = InfinityPopoverSpec(modePickerAnchor)) {
        ExecutionMode.entries.forEach { mode ->
            val label = stringResource(mode.labelRes)
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = if (mode == ExecutionMode.WORKSPACE) stringResource(R.string.numeric_default_value, label) else label,
                subtitle = stringResource(mode.descriptionRes),
                subtitleMaxLines = 3,
                role = Role.RadioButton,
                selected = mode == selectedExecutionMode,
                modifier = Modifier.testTag("session_execution_mode_${mode.name.lowercase()}"),
                onClick = { onExecutionModeChange(mode.name.lowercase()); showModePicker = false },
                trailingContent = {
                    if (mode == selectedExecutionMode) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    session: com.promenar.infinity.data.model.Session?,
    chatViewModel: ChatViewModel,
    isGenerating: Boolean,
    isCompressing: Boolean,
    onCompactNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val navigation = LocalSessionSettingsNavigation.current
    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsStateWithLifecycle()
    val memoryPolicy by androidx.compose.runtime.produceState(
        initialValue = com.promenar.infinity.data.agent.AgentMemoryPolicy(), session?.agentId,
    ) { value = chatViewModel.agentMemoryPolicy() }
    val reachStats by androidx.compose.runtime.produceState(
        initialValue = emptyMap<com.promenar.infinity.data.model.ConversationScope, com.promenar.infinity.data.rag.ConversationReachStats>(),
        session?.id, memoryPolicy,
    ) { value = runCatching { chatViewModel.conversationReachStats() }.getOrDefault(emptyMap()) }
    val graphQueue by chatViewModel.sessionGraphQueue.collectAsStateWithLifecycle()
    val ragOptions = session?.ragOptions ?: com.promenar.infinity.data.model.RagOptions()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var graphRefresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val graphData by androidx.compose.runtime.produceState<com.promenar.infinity.data.rag.SessionGraphBindingData?>(
        initialValue = null, session?.id, ragOptions.graphBinding, ragOptions.enableKnowledgeGraph, graphRefresh,
    ) { value = runCatching { chatViewModel.sessionGraphBinding() }.getOrNull() }
    val sharedTitle = stringResource(R.string.kg_spaces_shared_collection_title)
    val ownTitle = session?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.ks_graph_group_own)
    val graphChoices = androidx.compose.runtime.remember(graphData, sharedTitle, ownTitle) {
        graphData?.collections.orEmpty().map { it.toGraphChoice(sharedTitle, ownTitle) }
    }
    val graphBinding = androidx.compose.runtime.remember(graphData, graphChoices) {
        graphData?.let { data ->
            graphChoices.firstOrNull { it.id == data.binding.collectionId }?.let { current ->
                SessionGraphBindingUi(current, readOnly = !data.binding.writable, contribution = data.contribution)
            }
        }
    }
    SessionContextPanelContent(
        params = session?.inferenceParams ?: com.promenar.infinity.data.model.InferenceParams(),
        ragOptions = ragOptions,
        memoryPolicy = memoryPolicy,
        reachStats = reachStats,
        graphQueue = graphQueue,
        graphBinding = graphBinding,
        graphChoices = graphChoices,
        libraryCollections = graphData?.collections.orEmpty().filter { it.kind != "session" },
        onGraphRebind = { choice -> chatViewModel.updateGraphBinding(choice.id, readOnly = graphBinding?.readOnly ?: false) },
        onGraphReadOnlyChange = { readOnly -> chatViewModel.updateGraphBinding(graphBinding?.current?.id, readOnly) },
        onGraphRetract = {
            val target = graphBinding?.current?.id
            if (target != null) scope.launch {
                runCatching { chatViewModel.retractSessionGraph(target) }
                graphRefresh++
            }
        },
        fontSize = session?.options?.fontSize ?: 15,
        isRerankAvailable = presetRerankModel.isNotBlank(), isGenerating = isGenerating, isCompressing = isCompressing,
        onParamsChange = chatViewModel::updateInferenceParams,
        onRagOptionsChange = { update -> chatViewModel.updateRagOptions(update(chatViewModel.currentRagOptions.value)) },
        onFontSizeChange = { chatViewModel.updateFontSizeLocally(it); chatViewModel.updateFontSize(it) },
        onCompactNow = onCompactNow,
        onOpenAgentSettings = navigation?.let { nav ->
            session?.agentId?.takeIf(String::isNotBlank)?.let { agentId -> { onDismiss(); nav.openAgentEdit(agentId) } }
        },
    )
}

@Composable
internal fun SessionContextPanelContent(
    params: com.promenar.infinity.data.model.InferenceParams = com.promenar.infinity.data.model.InferenceParams(),
    ragOptions: com.promenar.infinity.data.model.RagOptions = com.promenar.infinity.data.model.RagOptions(),
    memoryPolicy: com.promenar.infinity.data.agent.AgentMemoryPolicy = com.promenar.infinity.data.agent.AgentMemoryPolicy(),
    /** 三档对话范围各覆盖的会话数；为空时不显示。 */
    reachStats: Map<com.promenar.infinity.data.model.ConversationScope, com.promenar.infinity.data.rag.ConversationReachStats> = emptyMap(),
    /** 会话图谱后台抽取队列；全局开关关闭时为 null，不显示。 */
    graphQueue: com.promenar.infinity.data.rag.SessionGraphQueueStatus? = null,
    /** 会话绑定的图谱；读取中为 null。 */
    graphBinding: SessionGraphBindingUi? = null,
    graphChoices: List<GraphChoice> = emptyList(),
    libraryCollections: List<com.promenar.infinity.data.rag.KgCollectionSummary> = emptyList(),
    onGraphRebind: (GraphChoice) -> Unit = {},
    onGraphReadOnlyChange: (Boolean) -> Unit = {},
    onGraphRetract: () -> Unit = {},
    fontSize: Int = 15,
    isRerankAvailable: Boolean = true,
    isGenerating: Boolean = false,
    isCompressing: Boolean = false,
    onParamsChange: (com.promenar.infinity.data.model.InferenceParams) -> Unit = {},
    onRagOptionsChange: ((com.promenar.infinity.data.model.RagOptions) -> com.promenar.infinity.data.model.RagOptions) -> Unit = {},
    onFontSizeChange: (Int) -> Unit = {},
    onCompactNow: () -> Unit = {},
    /** 检索范围页脚“智能体设置”的去向；为 null 时页脚不可点。 */
    onOpenAgentSettings: (() -> Unit)? = null,
) {
    val retainedTurns = resolveKeepRecentTurns(params.keepRecentTurns, params.activeContextWindow)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp)) {
        SessionContextSettingsSection(params.autoSummaryThreshold, retainedTurns, isGenerating, isCompressing,
            onThresholdChange = { onParamsChange(params.copy(autoSummaryThreshold = it)) },
            onKeepRecentTurnsChange = { onParamsChange(params.copy(keepRecentTurns = it)) }, onCompactNow = onCompactNow)
        val fullyDisabled = !ragOptions.enableMemory && !ragOptions.enableDocs && ragOptions.enableKnowledgeGraph != true
        val retrievalFooter = buildList {
            if (!isRerankAvailable) add(stringResource(R.string.sheet_settings_rerank_unavailable))
            if (fullyDisabled) {
                add(stringResource(R.string.sheet_settings_rag_disabled))
                add(stringResource(R.string.sheet_settings_rag_disabled_hint))
            }
        }.joinToString("\n").ifBlank { null }
        InfinitySettingsSection(title = stringResource(R.string.session_retrieval_section), footer = retrievalFooter) {
            SettingsToggle(stringResource(R.string.sheet_settings_memory), checked = ragOptions.enableMemory, icon = Icons.Rounded.History,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Memory, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_docs), checked = ragOptions.enableDocs, icon = Icons.Rounded.Description,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Documents, it) } })
            SettingsToggle(stringResource(R.string.settings_ia_rerank), checked = isRerankAvailable && ragOptions.enableRerank,
                icon = Icons.Rounded.Sync, enabled = isRerankAvailable,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Rerank, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_kg), checked = ragOptions.enableKnowledgeGraph ?: false, icon = Icons.Rounded.Psychology,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.KnowledgeGraph, it) } })
        }
        SessionScopeSection(ragOptions, memoryPolicy, onRagOptionsChange, reachStats, onOpenAgentSettings,
            collections = libraryCollections)
        SessionGraphBindingSection(
            enabled = ragOptions.enableKnowledgeGraph == true,
            binding = graphBinding,
            choices = graphChoices,
            onRebind = onGraphRebind,
            onReadOnlyChange = onGraphReadOnlyChange,
            onRetract = onGraphRetract,
            graphQueue = graphQueue,
        )
        InfinitySettingsSection(
            title = stringResource(R.string.sheet_settings_ui_title),
            footer = stringResource(R.string.settings_ia_message_font_footer),
        ) {
            InfinityStepperSetting(title = stringResource(R.string.settings_ia_message_font),
                description = stringResource(R.string.settings_ia_message_font_desc), value = fontSize.toString(),
                canDecrease = fontSize > 10, canIncrease = fontSize < 18,
                onDecrease = { onFontSizeChange((fontSize - 1).coerceAtLeast(10)) },
                onIncrease = { onFontSizeChange((fontSize + 1).coerceAtMost(18)) })
        }
    }
}

@Composable
internal fun SessionContextSettingsSection(
    autoCompactionThreshold: Double,
    keepRecentTurns: Int,
    isGenerating: Boolean,
    isCompressing: Boolean,
    onThresholdChange: (Double) -> Unit,
    onKeepRecentTurnsChange: (Int) -> Unit,
    onCompactNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thresholdPercent = (autoCompactionThreshold * 100).roundToInt().coerceIn(50, 95)
    val retainedTurns = keepRecentTurns.coerceIn(1, 20)
    val compressionUnavailableReason = when {
        isCompressing -> stringResource(R.string.chat_context_compress_unavailable_running)
        isGenerating -> stringResource(R.string.chat_context_compress_unavailable_generating)
        else -> null
    }
    InfinitySettingsSection(
        title = stringResource(R.string.sheet_settings_section_context),
        modifier = modifier.testTag("session_context_management"),
    ) {
        InfinityNumericSetting(
            title = stringResource(R.string.sheet_settings_summary_threshold), value = thresholdPercent,
            description = stringResource(R.string.sheet_settings_context_description, retainedTurns),
            spec = NumericSettingSpec(50.0, 95.0, step = 5.0, defaultValue = 80.0, unit = "%",
                presets = listOf(70.0, 80.0, 90.0).map { NumericSettingSpec.Preset(it) }),
            onValueChange = { onThresholdChange(it / 100.0) },
        )
        InfinityStepperSetting(
            title = stringResource(R.string.sheet_settings_keep_recent_turns), value = retainedTurns.toString(),
            canDecrease = retainedTurns > 1, canIncrease = retainedTurns < 20,
            onDecrease = { onKeepRecentTurnsChange((retainedTurns - 1).coerceAtLeast(1)) },
            onIncrease = { onKeepRecentTurnsChange((retainedTurns + 1).coerceAtMost(20)) },
        )
        InfinitySettingsItem(
            title = stringResource(R.string.chat_context_btn_compress),
            subtitle = when {
                isCompressing -> stringResource(R.string.session_compaction_running)
                isGenerating -> stringResource(R.string.session_compaction_generating)
                else -> null
            },
            onClick = onCompactNow, enabled = compressionUnavailableReason == null,
            modifier = Modifier.testTag("session_context_compact_now"),
        )
    }
}

/** 仓库图谱摘要转为会话设置的图谱选项。 */
private fun com.promenar.infinity.data.rag.KgCollectionSummary.toGraphChoice(sharedTitle: String, ownTitle: String) = GraphChoice(
    id = id,
    group = when (kind) {
        "session" -> GraphGroup.Session
        "shared" -> GraphGroup.Shared
        else -> GraphGroup.Collection
    },
    title = when (kind) {
        "shared" -> sharedTitle
        "session" -> ownTitle
        else -> title
    },
    documents = documentCount,
    entities = nodeCount,
)
