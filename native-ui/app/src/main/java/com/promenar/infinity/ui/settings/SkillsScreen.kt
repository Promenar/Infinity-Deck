package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.common.excludeFromAutofill
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.promenar.infinity.ui.common.InfinityIdentifierText
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.remote.mcp.McpEndpointPolicy
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.InfinityNumericSetting
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.common.InfinityStepperSetting
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SnackbarType
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor

/** 技能编辑器的输入；name 为 null 表示新建。 */
private data class AgentSkillEditorState(
    val name: String?,
    val description: String,
    val body: String,
    val allowedTools: List<String>,
)

/** “智能体运行”下的四个页面，共用技能与工具的视图模型与面板。 */
enum class AgentRuntimeSection { Budget, BuiltinTools, SkillPacks, Mcp }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit,
    section: AgentRuntimeSection = AgentRuntimeSection.SkillPacks,
    onOpenStore: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val storeUpdateCount by (context.applicationContext as com.promenar.infinity.InfinityApplication)
        .skillStore.updateCount.collectAsState()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val agentSkillsViewModel: AgentSkillsViewModel =
        viewModel(factory = AgentSkillsViewModel.factory(context.applicationContext as android.app.Application))
    val skillCatalogViewModel: com.promenar.infinity.ui.skills.SkillCatalogViewModel =
        viewModel(factory = com.promenar.infinity.ui.skills.SkillCatalogViewModel.factory(context.applicationContext as android.app.Application))
    // 通用设置页不绑定具体项目：只扫描内置与用户级技能，界面也不渲染项目组。
    androidx.compose.runtime.LaunchedEffect(Unit) { agentSkillsViewModel.bindWorkspace(null) }
    val skillGroups by agentSkillsViewModel.groups.collectAsState()
    val skillBatchResult by agentSkillsViewModel.batchResult.collectAsState()
    val skillCatalogError by skillCatalogViewModel.error.collectAsState()
    val agentSkillEvent by agentSkillsViewModel.event.collectAsState()
    var agentSkillEditor by remember { mutableStateOf<AgentSkillEditorState?>(null) }
    val agentSkillScope = androidx.compose.runtime.rememberCoroutineScope()
    // 单项操作仍由技能目录视图模型执行；结束后（含失败）重扫分组，列表始终显示磁盘上的真实状态。
    val refreshSkillGroupsAfter: (kotlinx.coroutines.Job) -> Unit = { job ->
        agentSkillScope.launch {
            job.join()
            agentSkillsViewModel.refreshGroups()
        }
    }
    val importSkillLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { agentSkillsViewModel.import(it) } }

    val presetSkills by viewModel.skills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val loopLimit by viewModel.loopLimit.collectAsState()
    val tokenLimit by viewModel.taskTokenLimit.collectAsState()
    val repeatLimit by viewModel.toolLoopRepeatLimit.collectAsState()
    val retryLimit by viewModel.streamRetryLimit.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    // 同一提示条既用于成功提示，也用于批量切换的部分失败；显示前设置对应类型。
    var snackbarType by remember { mutableStateOf(SnackbarType.SUCCESS) }
    val resetMessage = stringResource(R.string.settings_consent_reset)

    var showAddMcp by remember { mutableStateOf(false) }
    var showSearchConfig by remember { mutableStateOf(false) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    InfinitySettingsPageLayout(
        title = stringResource(
            when (section) {
                AgentRuntimeSection.Budget -> R.string.settings_execution_budget
                AgentRuntimeSection.BuiltinTools -> R.string.settings_ia_builtin_tools_title
                AgentRuntimeSection.SkillPacks -> R.string.settings_ia_skills_title
                AgentRuntimeSection.Mcp -> R.string.settings_ia_mcp_title
            },
        ),
        onBack = onNavigateBack,
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                when (section) {
                    AgentRuntimeSection.Budget -> AgentRuntimeSettingsCard(
                        state = SkillsSettingsVisualState(loopLimit, tokenLimit, repeatLimit, retryLimit = retryLimit),
                        actions = SkillsSettingsVisualActions(
                            onLoopLimitChange = viewModel::updateLoopLimit,
                            onTokenLimitChange = viewModel::updateTaskTokenLimit,
                            onRepeatLimitChange = viewModel::updateToolLoopRepeatLimit,
                            onRetryLimitChange = viewModel::updateStreamRetryLimit,
                            onResetConsent = {
                                viewModel.resetUnlimitedAutoConsent()
                                snackbarType = SnackbarType.SUCCESS
                                scope.launch { snackbarHost.showSnackbar(resetMessage) }
                            },
                        ),
                    )
                    AgentRuntimeSection.BuiltinTools -> BuiltinToolsSections(
                        tools = presetSkills,
                        onToggle = viewModel::toggleSkill,
                        onConfigureSearch = { showSearchConfig = true },
                    )
                    AgentRuntimeSection.SkillPacks -> AgentSkillsSection(
                        groups = skillGroups,
                        onSetGroupEnabled = { skillScope, enabled -> agentSkillsViewModel.setGroupEnabled(skillScope, enabled) },
                        actions = com.promenar.infinity.ui.skills.SkillCatalogActions(
                            onToggle = { skill, enabled -> refreshSkillGroupsAfter(skillCatalogViewModel.setEnabled(skill, enabled)) },
                            onAcknowledge = { skill -> refreshSkillGroupsAfter(skillCatalogViewModel.acknowledgeModified(skill)) },
                            onDelete = { skill -> refreshSkillGroupsAfter(skillCatalogViewModel.delete(skill)) },
                            loadDocument = skillCatalogViewModel::loadDocument,
                            onEdit = { skill ->
                                agentSkillScope.launch {
                                    val document = agentSkillsViewModel.loadDocument(skill.name) ?: return@launch
                                    agentSkillEditor = AgentSkillEditorState(
                                        name = skill.name,
                                        description = document.metadata.description,
                                        body = document.body,
                                        allowedTools = document.metadata.allowedTools,
                                    )
                                }
                            },
                        ),
                        onImport = {
                            importSkillLauncher.launch(
                                arrayOf("application/zip", "text/markdown", "text/plain", "application/octet-stream"),
                            )
                        },
                        onCreate = { agentSkillEditor = AgentSkillEditorState(null, "", "", emptyList()) },
                        onOpenStore = onOpenStore,
                        storeUpdateCount = storeUpdateCount,
                    )
                    AgentRuntimeSection.Mcp -> McpServersSection(
                        servers = mcpServers,
                        expandedServerId = expandedServerId,
                        onToggleExpand = { id -> expandedServerId = if (expandedServerId == id) null else id },
                        onToggleEnabled = viewModel::toggleMcpServer,
                        onDelete = viewModel::deleteMcpServer,
                        onSync = viewModel::syncMcpServer,
                        onAdd = { showAddMcp = true },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            ) { data -> InfinitySnackbar(InfinitySnackbarData(data.visuals.message, snackbarType)) }
        }
    }

    if (showAddMcp) {
        var mcpName by remember { mutableStateOf("") }
        var mcpUrl by remember { mutableStateOf("") }
        var mcpBearer by remember { mutableStateOf("") }
        var mcpSaving by remember { mutableStateOf(false) }
        val configError by viewModel.mcpConfigError.collectAsState()
        val canSave = mcpName.isNotBlank() && !mcpSaving && McpEndpointPolicy.isAllowed(
            "http", mcpUrl.trim(),
        )
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.clearMcpConfigError() }
        InfinityModalSheet(onDismissRequest = { if (!mcpSaving) showAddMcp = false }, height = InfinitySheetHeight.Full) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.skills_add_mcp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    enabled = !mcpSaving,
                    label = { Text(stringResource(R.string.skills_mcp_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    enabled = !mcpSaving,
                    label = { Text(stringResource(R.string.skills_mcp_url_placeholder)) },
                    supportingText = com.promenar.infinity.ui.common.cleartextUrlHelp(mcpUrl)?.let { hint -> { Text(hint) } },
                    modifier = Modifier.fillMaxWidth()
                )

                com.promenar.infinity.ui.common.SecretTextInput {
                    OutlinedTextField(
                        textStyle = LocalTextStyle.current.forInput(),
                        value = mcpBearer,
                        onValueChange = { mcpBearer = it },
                        enabled = !mcpSaving,
                        label = { Text(stringResource(R.string.mcp_bearer_token)) },
                        supportingText = { Text(stringResource(R.string.mcp_bearer_token_storage)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = com.promenar.infinity.ui.common.SecretKeyboardOptions,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().excludeFromAutofill(),
                    )
                }
                val errorMessage = when (configError) {
                    McpConfigError.INVALID_ENDPOINT -> R.string.mcp_invalid_endpoint
                    McpConfigError.BEARER_REQUIRED -> R.string.mcp_bearer_required
                    McpConfigError.SAVE_FAILED -> R.string.mcp_config_save_failed
                    null -> null
                }
                if (errorMessage != null) {
                    Text(
                        stringResource(errorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        mcpSaving = true
                        viewModel.addMcpServer(mcpName, mcpUrl, "http", mcpBearer) { saved ->
                            mcpSaving = false
                            if (saved) {
                                mcpBearer = ""
                                showAddMcp = false
                            }
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (mcpSaving) R.string.mcp_config_saving else R.string.skills_mcp_add_btn))
                }
            }
        }
    }

    agentSkillEditor?.let { editor ->
        AgentSkillEditorSheet(
            initialName = editor.name,
            initialDescription = editor.description,
            initialBody = editor.body,
            allowedTools = editor.allowedTools,
            onDismiss = { agentSkillEditor = null },
            onSave = { name, description, body ->
                agentSkillsViewModel.save(editor.name, name, description, body)
                agentSkillEditor = null
            },
        )
    }

    when (val event = agentSkillEvent) {
        is AgentSkillEvent.Conflict -> InfinityConfirmDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            title = stringResource(R.string.agent_skills_conflict_title),
            message = stringResource(R.string.agent_skills_conflict_message, event.name),
            confirmText = stringResource(R.string.agent_skills_replace),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                agentSkillsViewModel.consumeEvent()
                event.retry()
            },
            onCancel = agentSkillsViewModel::consumeEvent,
        )
        // 无独立标题的纯提示：错误文案作标题，正文留空。
        is AgentSkillEvent.Failed -> InfinityConfirmDialog(
            onDismissRequest = agentSkillsViewModel::consumeEvent,
            title = "",
            message = stringResource(R.string.agent_skills_import_failed, event.message),
            confirmText = stringResource(R.string.shared_btn_close),
            cancelText = null,
            onConfirm = agentSkillsViewModel::consumeEvent,
            onCancel = agentSkillsViewModel::consumeEvent,
        )
        // 导入与应用内编辑完成后，视图模型已在同一次操作结束时重扫分组，这里只需消费事件。
        is AgentSkillEvent.Installed -> androidx.compose.runtime.LaunchedEffect(event) {
            agentSkillsViewModel.consumeEvent()
        }
        null -> Unit
    }

    // 批量切换允许部分成功：有失败项时提示一行数量；跳过的内置、损坏或待信任项不提示。
    skillBatchResult?.let { result ->
        val failedCount = result.failures.size
        val failedMessage = if (failedCount > 0) {
            pluralStringResource(R.plurals.skills_group_batch_failed, failedCount, failedCount)
        } else null
        androidx.compose.runtime.LaunchedEffect(result) {
            // 提示在页面作用域内显示：消费结果会移除本效应，不能让它带着提示一起取消。
            if (failedMessage != null) {
                snackbarType = SnackbarType.ERROR
                scope.launch { snackbarHost.showSnackbar(failedMessage) }
            }
            agentSkillsViewModel.consumeBatchResult()
        }
    }

    skillCatalogError?.let { message ->
        InfinityConfirmDialog(
            onDismissRequest = skillCatalogViewModel::consumeError,
            title = "",
            message = stringResource(R.string.skills_runtime_error, message),
            confirmText = stringResource(R.string.shared_btn_close),
            cancelText = null,
            onConfirm = skillCatalogViewModel::consumeError,
            onCancel = skillCatalogViewModel::consumeError,
        )
    }

    if (showSearchConfig) {
        SearchConfigBottomSheet(onDismiss = { showSearchConfig = false })
    }
}

/** 轮次预设与自定义值共用已有偏好回调，0 表示不限。 */
@Composable
internal fun LoopLimitCard(
    loopLimit: Int,
    onChange: (Int) -> Unit,
    icon: ImageVector? = null,
) {
    InfinityNumericSetting(
        icon = icon,
        title = stringResource(R.string.skills_loop_limit),
        value = loopLimit,
        spec = ToolLoopLimitSpec,
        description = stringResource(R.string.skills_loop_limit_desc),
        onValueChange = { onChange(it.toInt()) },
    )
}

/** 列表内分隔线：从正文起点开始，与图标列对齐。 */
@Composable
internal fun SkillRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SkillTextStart, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 列表上方的小节标题与说明，放在卡片外，避免说明文字贴着卡片边缘。 */
@Composable
internal fun SectionCaption(title: String?, description: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 卡片内的空态：图标加居中说明。 */
@Composable
internal fun SkillsEmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 工具图标底座。 */
@Composable
internal fun SkillIconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(SkillIconSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** 正文与公共设置行的图标列对齐。 */
private val SkillIconSize = 20.dp
internal val SkillTextStart = 52.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchConfigBottomSheet(
    onDismiss: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val searchViewModel: SearchConfigViewModel = viewModel(
        factory = SearchConfigViewModel.factory(application)
    )
    val searchState by searchViewModel.uiState.collectAsState()

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Full) {
        SearchConfigContent(
            searchState = searchState,
            onSearchEngineChange = searchViewModel::updateSearchEngine,
            onResultCountChange = searchViewModel::updateResultCount,
            onSearchDepthChange = searchViewModel::updateSearchDepth,
            onSearXngUrlChange = searchViewModel::updateSearXngUrl,
            onIncludeDomainsChange = searchViewModel::setIncludeDomains,
            onExcludeDomainsChange = searchViewModel::setExcludeDomains,
            keenableSecretEditor = { KeenableSecretEditor(searchViewModel, searchState) },
            tavilySecretEditor = { TavilySecretEditor(searchViewModel, searchState) },
        )
    }
}

@Composable
internal fun SearchConfigContent(
    searchState: SearchConfigState = SearchConfigState(),
    onSearchEngineChange: (String) -> Unit = {},
    onResultCountChange: (Int) -> Unit = {},
    onSearchDepthChange: (String) -> Unit = {},
    onSearXngUrlChange: (String) -> Unit = {},
    onIncludeDomainsChange: (String) -> Unit = {},
    onExcludeDomainsChange: (String) -> Unit = {},
    keenableSecretEditor: @Composable () -> Unit = {},
    tavilySecretEditor: @Composable () -> Unit = {},
) {
    val depthAnchor = rememberInfinityPopoverAnchor()
    var editingDomains by remember { mutableStateOf<DomainListKind?>(null) }
    var showDepthPicker by remember(searchState.searchEngine) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = stringResource(R.string.search_sheet_web_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            InfinitySettingsSection(title = stringResource(R.string.search_engine_select)) {
                EngineOption("duckduckgo", stringResource(R.string.search_engine_duckduckgo), searchState.searchEngine == "duckduckgo") {
                    onSearchEngineChange("duckduckgo")
                }
                EngineOption("keenable", stringResource(R.string.search_engine_keenable), searchState.searchEngine == "keenable") {
                    onSearchEngineChange("keenable")
                }
                EngineOption("tavily", stringResource(R.string.search_engine_tavily), searchState.searchEngine == "tavily") {
                    onSearchEngineChange("tavily")
                }
                EngineOption("searxng", stringResource(R.string.search_engine_searxng), searchState.searchEngine == "searxng") {
                    onSearchEngineChange("searxng")
                }
            }

            InfinitySettingsSection(title = stringResource(R.string.settings_batch1_search_results)) {
            InfinityStepperSetting(
                title = stringResource(R.string.search_count_label),
                value = searchState.resultCount.toString(),
                canDecrease = searchState.resultCount > 1,
                canIncrease = searchState.resultCount < 20,
                onDecrease = { onResultCountChange(searchState.resultCount - 1) },
                onIncrease = { onResultCountChange(searchState.resultCount + 1) },
            )
            }

            InfinitySettingsSection(
                title = stringResource(R.string.settings_ia_domain_section),
                footer = stringResource(R.string.settings_ia_domain_footer),
            ) {
                InfinityValueRow(
                    title = stringResource(R.string.settings_ia_domain_include),
                    value = domainCountLabel(searchState.includeDomains, R.string.settings_ia_domain_any),
                    onClick = { editingDomains = DomainListKind.Include },
                    modifier = Modifier.testTag("search_domains_include"),
                )
                InfinityValueRow(
                    title = stringResource(R.string.settings_ia_domain_exclude),
                    value = domainCountLabel(searchState.excludeDomains, R.string.settings_ia_domain_none),
                    onClick = { editingDomains = DomainListKind.Exclude },
                    modifier = Modifier.testTag("search_domains_exclude"),
                )
            }

            if (searchState.searchEngine == "keenable") {
                InfinitySettingsSection(
                    title = stringResource(R.string.search_api_key_optional),
                    footer = stringResource(R.string.search_keenable_keyless_hint),
                ) { Column(Modifier.padding(16.dp)) { keenableSecretEditor() } }
            }

            if (searchState.searchEngine == "tavily") {
                InfinitySettingsSection(title = stringResource(R.string.search_api_key)) {
                    Column(Modifier.padding(16.dp)) { tavilySecretEditor() }
                }

                InfinitySettingsSection(title = stringResource(R.string.settings_batch1_search_options)) {
                    InfinityValueRow(
                        title = stringResource(R.string.search_depth_label),
                        value = when (searchState.searchDepth) {
                            "basic" -> stringResource(R.string.search_depth_basic)
                            "advanced" -> stringResource(R.string.search_depth_advanced)
                            else -> searchState.searchDepth
                        },
                        onClick = { if (!depthAnchor.consumeRecentDismiss()) showDepthPicker = true },
                        modifier = Modifier.infinityPopoverAnchor(depthAnchor).testTag("search_depth_picker"),
                    )
                }
            }

            if (searchState.searchEngine == "searxng") {
                OutlinedTextField(
                    label = { Text(stringResource(R.string.search_instance_url)) },
                    value = searchState.searXngUrl,
                    onValueChange = { onSearXngUrlChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = InfinityMonospace).forInput()
                )
            }
        }
    InfinityBottomSheet(
        show = showDepthPicker,
        scrollContent = true,
        title = stringResource(R.string.search_depth_label),
        onDismiss = { showDepthPicker = false },
        popover = InfinityPopoverSpec(depthAnchor),
    ) {
        DepthChip("basic", stringResource(R.string.search_depth_basic), searchState.searchDepth == "basic") {
            onSearchDepthChange("basic")
            showDepthPicker = false
        }
        DepthChip("advanced", stringResource(R.string.search_depth_advanced), searchState.searchDepth == "advanced") {
            onSearchDepthChange("advanced")
            showDepthPicker = false
        }
    }
    when (editingDomains) {
        DomainListKind.Include -> DomainListDialog(
            title = stringResource(R.string.settings_ia_domain_include),
            initial = searchState.includeDomains,
            onSave = onIncludeDomainsChange,
            onDismiss = { editingDomains = null },
        )
        DomainListKind.Exclude -> DomainListDialog(
            title = stringResource(R.string.settings_ia_domain_exclude),
            initial = searchState.excludeDomains,
            onSave = onExcludeDomainsChange,
            onDismiss = { editingDomains = null },
        )
        null -> Unit
    }

}

/** Web 搜索的两份域名名单。 */
private enum class DomainListKind { Include, Exclude }

@Composable
private fun domainCountLabel(domains: List<String>, emptyLabel: Int): String =
    if (domains.isEmpty()) stringResource(emptyLabel)
    else pluralStringResource(R.plurals.settings_ia_domain_count, domains.size, domains.size)

/** 每行一个域名的编辑对话框，保存时由视图模型整理名单。 */
@Composable
private fun DomainListDialog(title: String, initial: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    InfinityConfirmDialog(
        title = title,
        message = stringResource(R.string.settings_ia_domain_dialog_hint),
        confirmText = stringResource(R.string.shared_btn_save),
        onConfirm = { onSave(text); onDismiss() },
        onCancel = onDismiss,
        modifier = Modifier.testTag("search_domains_dialog"),
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontFamily = InfinityMonospace).forInput(),
                placeholder = { Text("example.com") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("search_domains_input"),
            )
        },
    )
}

@Composable
internal fun EngineOption(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    // 单选语义、触感与按压反馈都由 InfinitySettingsItem 给出，外层不再叠一层 selectable。
    InfinitySettingsItem(
        title = label,
        onClick = onClick,
        modifier = Modifier.testTag("search_engine_$id"),
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
    )
}

/** 搜索深度单选行；函数签名供既有无障碍测试复用。 */
@Composable
internal fun DepthChip(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    InfinitySettingsItem(
        title = label,
        horizontalPadding = 0.dp,
        onClick = onClick,
        modifier = Modifier.testTag("search_depth_$id"),
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
    )
}
