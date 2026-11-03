package com.promenar.infinity.ui.hub

import androidx.compose.animation.Crossfade
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import com.promenar.infinity.ui.common.GenerationDots
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.generationMotionPhase
import com.promenar.infinity.ui.common.motion.rememberGenerationMotion
import com.promenar.infinity.ui.theme.InfinityMotion

import androidx.compose.runtime.key

import androidx.compose.animation.core.tween

import androidx.compose.animation.animateContentSize

import androidx.compose.animation.AnimatedContent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.PresetAgentDisplay
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.domain.usecase.AgentCreationType
import com.promenar.infinity.domain.usecase.template
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.rememberInfinityMenuTouch
import com.promenar.infinity.ui.common.infinityMenuTouch
import com.promenar.infinity.ui.common.infinityMenuHost
import com.promenar.infinity.ui.common.InfinityMenuDivider

// =====================================================================================
// Route —— 拥有 ViewModel、localized preset display 映射与 overlay 状态
// =====================================================================================

@Composable
fun AgentHubScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit,
    onOpenLinuxEnvironment: () -> Unit = {},
    /** 内屏三列：点会话改为在右侧会话列选中；为 null 时打开整页会话。 */
    threePane: HubThreePaneHost? = null,
) {
    val context = LocalContext.current
    val deleteScope = rememberCoroutineScope()
    val viewModel: AgentHubViewModel = viewModel(
        factory = AgentHubViewModel.factory(context.applicationContext as android.app.Application),
    )
    val sessionListViewModel: SessionListViewModel = viewModel(
        factory = SessionListViewModel.factory(context.applicationContext as android.app.Application),
    )
    LaunchedEffect(Unit) {
        // 全量刷新会话目录，供“置顶”“最近”、各助手会话计数与展开列表使用
        sessionListViewModel.loadSessions()
    }
    val operationFailed by sessionListViewModel.operationFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedMessage = stringResource(R.string.sessions_operation_failed)
    LaunchedEffect(operationFailed) {
        if (operationFailed) {
            snackbarHostState.showSnackbar(operationFailedMessage)
            sessionListViewModel.dismissOperationFailure()
        }
    }
    val agents by viewModel.agents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val allSessions by sessionListViewModel.sessions.collectAsState()
    val activeGeneration by (context.applicationContext as com.promenar.infinity.InfinityApplication)
        .generationCoordinator.sessions.collectAsState()
    val sessionsByAgent = remember(allSessions) {
        allSessions.groupBy { it.agentId }
    }
    val displayAgents = agents.map { agent ->
        val (title, subtitle) = resolveAgentDisplay(agent)
        AgentDisplayItem(agent, title, subtitle)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    val selectedSessionId = threePane?.state?.hubSessionId
    val sessionActivity = hubSessionActivities(activeGeneration, allSessions)
    val createSession: (String, (String) -> Unit) -> Unit = { agentId, open ->
        sessionListViewModel.createSession(agentId) { sessionId -> open(sessionId) }
    }
    // 列表本身与它的弹窗：三列时放在列表列里，点会话在会话列选中。
    @Composable
    fun HubListContent(openChat: (String) -> Unit, selectedSessionId: String?, closeSessions: (Set<String>) -> Unit = {}) {
        SideEffect {
            viewModel.bindSessionCloser { ids ->
                closeSessions(ids)
                threePane?.state?.let { if (it.hubSessionId in ids) it.restoreHubStack(emptyList()) }
            }
        }
        var agentToDelete by remember { mutableStateOf<String?>(null) }
        var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
        var searchActive by rememberSaveable { mutableStateOf(false) }

        val state = AgentHubScreenState(
            displayAgents = displayAgents,
            searchQuery = searchQuery,
            searchActive = searchActive,
            showAddDialog = showAddDialog,
            pendingDeleteAgentId = agentToDelete,
            pendingDeleteSessionId = pendingDeleteSessionId,
            sessionsByAgent = sessionsByAgent,
            sessionActivity = sessionActivity,
            sessionGeneration = activeGeneration,
            selectedSessionId = selectedSessionId,
        )
        val actions = AgentHubScreenActions(
            onSearch = viewModel::updateSearchQuery,
            onSearchActiveChange = { searchActive = it },
            onRequestAdd = { showAddDialog = true },
            onCancelAdd = { showAddDialog = false },
            onCreateAgent = { name, type, model, systemPrompt ->
                viewModel.createAgent(name, type, model, systemPrompt)
                showAddDialog = false
            },
            onRequestDelete = { agentToDelete = it },
            onCancelDelete = { agentToDelete = null },
            onConfirmDelete = { id ->
                viewModel.deleteAgent(id)
                agentToDelete = null
            },
            onTogglePin = viewModel::togglePin,
            onEdit = onNavigateToAgentEdit,
            onOpenChat = openChat,
            onCreateSession = { agentId -> createSession(agentId, openChat) },
            onToggleSessionPin = sessionListViewModel::pinSession,
            onRequestDeleteSession = { sessionId ->
                pendingDeleteSessionId = sessionId
            },
            onCancelDeleteSession = {
                pendingDeleteSessionId = null
            },
            onDeleteSession = { sessionId, keepFiles, removeWorktree ->
                closeSessions(setOf(sessionId))
                pendingDeleteSessionId = null
                val branchApp = context.applicationContext as? com.promenar.infinity.InfinityApplication
                if (removeWorktree && branchApp != null) {
                    deleteScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            com.promenar.infinity.ui.workspace.removeBranchWorktree(context, branchApp.branchControls, sessionId)
                        }
                        sessionListViewModel.deleteSession(sessionId, keepFiles)
                    }
                } else {
                    sessionListViewModel.deleteSession(sessionId, keepFiles)
                }
            },
            loadWorkspaceSummary = (LocalContext.current.applicationContext as? com.promenar.infinity.InfinityApplication)
                ?.let { app -> app::privateWorkspaceSummary },
            loadWorktreeBranch = (LocalContext.current.applicationContext as? com.promenar.infinity.InfinityApplication)
                ?.let { app -> com.promenar.infinity.ui.workspace.worktreeBranchLoader(app) },
        )

        // 启动自检问题：照常进入，列表顶部提示受影响的范围，点开处置或交给 Agent 诊断。
        val app = LocalContext.current.applicationContext as? com.promenar.infinity.InfinityApplication
        val startupIssues = app?.startupIssues?.collectAsState()?.value.orEmpty()
        var showStartupIssues by remember { mutableStateOf(false) }
        // 启动时的非阻断提示：Linux 不可用时列出受影响的 Linux 会话数；环境状态变化后重新统计。
        val linuxState = app?.linux?.runtime?.state?.collectAsState()?.value
        var linuxAffected by remember { mutableStateOf(0) }
        LaunchedEffect(app, linuxState) {
            linuxAffected = if (app == null || !com.promenar.infinity.distribution.DistributionCapabilities.linuxRuntimeAvailable) 0 else runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.sessionRunModes.startupCheck().affectedSessionCount }
            }.getOrDefault(0)
        }
        AgentHubScreenContent(
            state = state,
            actions = actions,
            snackbarHostState = snackbarHostState,
            startupBanner = {
                if (startupIssues.isNotEmpty()) {
                    com.promenar.infinity.ui.startup.StartupIssuesBanner(startupIssues.size, onOpen = { showStartupIssues = true })
                }
                if (linuxAffected > 0) {
                    com.promenar.infinity.ui.startup.LinuxUnavailableBanner(linuxAffected, onOpen = onOpenLinuxEnvironment)
                }
            },
        )
        if (showStartupIssues && app != null) {
            com.promenar.infinity.ui.startup.StartupIssuesSheet(
                onDismiss = { showStartupIssues = false },
                diagnosisAgentId = { issues ->
                    issues.firstNotNullOfOrNull { issue ->
                        allSessions.firstOrNull { it.id == issue.scope.id }?.agentId
                    } ?: displayAgents.firstOrNull()?.agent?.id
                },
                onOpenSession = openChat,
            )
        }

        if (state.showAddDialog) {
            AddAgentDialog(
                onDismiss = actions.onCancelAdd,
                onConfirm = actions.onCreateAgent,
            )
        }
    }

    if (threePane != null) {
        HubThreePane(
            host = threePane,
            strip = hubStripEntries(displayAgents, sessionsByAgent, selectedSessionId),
            activity = sessionActivity,
            empty = { open ->
                HubPaneEmpty(
                    agents = displayAgents,
                    onCreateSession = { agentId -> createSession(agentId, open) },
                    onRequestAdd = { showAddDialog = true },
                )
            },
            list = { open, close -> HubListContent(open, selectedSessionId, close) },
        )
    } else {
        HubListContent(onNavigateToChat, null)
    }
}

// =====================================================================================
// AddAgentDialog —— overlay，依赖 ProviderManager / SettingsViewModel，由 Route 持有
// =====================================================================================

@Composable
private fun AddAgentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, AgentCreationType, String, String) -> Unit
) {
    val pm = ProviderManager.getInstance()
    val defaultModel by pm.summaryModelId.collectAsState()

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AgentCreationType.CHAT) }
    var model by remember { mutableStateOf(defaultModel) }
    var systemPrompt by remember { mutableStateOf("") }
    var showPromptEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )
    val allModels by settingsViewModel.providerModels.collectAsState()
    val resolver = ModelCatalogRuntime.resolver
    val modelItems = remember(allModels, resolver) {
        allModels.filter { it.enabled }.map { it.toModelSelectionUiModel(resolver) }
    }

    UnifiedPromptEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        onSave = {
            systemPrompt = it
            Result.success(Unit)
        },
        title = stringResource(R.string.hub_dialog_label_prompt),
        initialText = systemPrompt,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder),
        mode = EditorMode.DIALOG
    )

    ModelPicker(
        show = showModelPicker,
        onDismiss = { showModelPicker = false },
        filterTag = "chat",
        models = modelItems,
        onSelect = { modelId, modelName ->
            model = modelId
            showModelPicker = false
        },
        currentModelId = model
    )

    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.hub_dialog_add_title),
        message = "",
        confirmText = stringResource(R.string.shared_btn_add),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(name, type, model, systemPrompt) },
        onCancel = onDismiss,
        confirmEnabled = name.isNotBlank(),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                AgentTypeSelector(type = type, onSelect = { type = it })

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showModelPicker = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.hub_dialog_label_model),
                                style = InfinityTypography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = model.ifBlank { stringResource(R.string.hub_dialog_placeholder_select_model) },
                                style = InfinityTypography.bodyMedium,
                                color = if (model.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showPromptEditor = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.ux2_agent_dialog_prompt_optional),
                            style = InfinityTypography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                            style = InfinityTypography.bodyMedium,
                            color = if (systemPrompt.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
    )
}

/** 类型两段式按钮、一行说明，以及该类型写入的三项参数；类型只是一次性模板。 */
@Composable
private fun AgentTypeSelector(
    type: AgentCreationType,
    onSelect: (AgentCreationType) -> Unit,
) {
    val template = type.template()
    val on = stringResource(R.string.ux2_agent_param_on)
    val off = stringResource(R.string.ux2_agent_param_off)
    val rows = listOf(
        stringResource(R.string.ux2_agent_param_tools) to if (template.toolsEnabledByDefault) on else off,
        stringResource(R.string.agent_edit_retrieval_mode) to stringResource(
            when (template.retrievalMode) {
                AgentRetrievalMode.AUTO -> R.string.agent_edit_retrieval_auto
                AgentRetrievalMode.TOOL -> R.string.agent_edit_retrieval_tool
                AgentRetrievalMode.BOTH -> R.string.agent_edit_retrieval_both
            },
        ),
        stringResource(R.string.agent_edit_file_access) to stringResource(
            when (template.knowledgeFileAccess) {
                KnowledgeFileAccess.OFF -> R.string.agent_edit_file_access_off
                KnowledgeFileAccess.ON_REQUEST -> R.string.agent_edit_file_access_on_request
                KnowledgeFileAccess.ALWAYS -> R.string.agent_edit_file_access_always
            },
        ),
    )
    Column(
        modifier = Modifier.testTag(UiTags.HUB_ADD_DIALOG_TYPE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ux2_agent_dialog_type),
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(AgentCreationType.CHAT, stringResource(R.string.ux2_agent_type_chat), tag = UiTags.HUB_ADD_DIALOG_TYPE_CHAT),
                InfinitySegment(AgentCreationType.AGENT, stringResource(R.string.ux2_agent_type_agent), tag = UiTags.HUB_ADD_DIALOG_TYPE_AGENT),
            ),
            selected = type,
            onSelect = onSelect,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                if (type == AgentCreationType.CHAT) R.string.ux2_agent_type_chat_desc else R.string.ux2_agent_type_agent_desc,
            ),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = value,
                        style = InfinityTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ux2_agent_type_params_footer),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// =====================================================================================
// 状态提升 seam —— Content 只消费显式 state/actions，不触碰 ViewModel / ProviderManager
// =====================================================================================

internal data class AgentHubScreenState(
    val displayAgents: List<AgentDisplayItem> = emptyList(),
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val showAddDialog: Boolean = false,
    val pendingDeleteAgentId: String? = null,
    val pendingDeleteSessionId: String? = null,
    val sessionsByAgent: Map<String, List<Session>> = emptyMap(),
    /** 正在生成或等待批准的会话，列表行以此替换时间显示状态。 */
    val sessionActivity: Map<String, HubSessionActivity> = emptyMap(),
    /** 应用级生成任务快照，生成中的会话行据此按阶段播放与会话界面相同的指示动效。 */
    val sessionGeneration: Map<String, GenerationTaskSnapshot> = emptyMap(),
    /** 内屏三列时会话列正在显示的会话，列表中高亮。 */
    val selectedSessionId: String? = null,
)

internal enum class HubSessionActivity { GENERATING, QUEUED, AWAITING_APPROVAL, AWAITING_ANSWER }

/**
 * 应用级生成任务投影到会话列表：进行中标为生成中，排队、等待批准与待回答各自标出，已结束的不标。
 * 待回答以会话持久化的 [LoopStatus.WAITING_FOR_INPUT] 为准，生成任务退出后仍保留。
 */
internal fun hubSessionActivities(
    active: Map<String, GenerationTaskSnapshot>,
    sessions: List<Session> = emptyList(),
): Map<String, HubSessionActivity> {
    val waiting = sessions.filter { it.loopStatus == LoopStatus.WAITING_FOR_INPUT }
        .associate { it.id to HubSessionActivity.AWAITING_ANSWER }
    return waiting + active.mapNotNull { (sessionId, snapshot) ->
        val activity = when (snapshot.phase) {
            GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED -> null
            GenerationPhase.WAITING_APPROVAL -> HubSessionActivity.AWAITING_APPROVAL
            GenerationPhase.WAITING_INPUT -> HubSessionActivity.AWAITING_ANSWER
            GenerationPhase.QUEUED -> HubSessionActivity.QUEUED
            else -> HubSessionActivity.GENERATING
        }
        activity?.let { sessionId to it }
    }
}

internal data class AgentHubScreenActions(
    val onSearch: (String) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onRequestAdd: () -> Unit = {},
    val onCancelAdd: () -> Unit = {},
    val onCreateAgent: (String, AgentCreationType, String, String) -> Unit = { _, _, _, _ -> },
    val onRequestDelete: (String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: (String) -> Unit = {},
    val onTogglePin: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onOpenChat: (String) -> Unit = {},
    val onCreateSession: (String) -> Unit = {},
    val onToggleSessionPin: (String) -> Unit = {},
    val onRequestDeleteSession: (String) -> Unit = {},
    val onCancelDeleteSession: () -> Unit = {},
    val onDeleteSession: (sessionId: String, keepFiles: Boolean, removeWorktree: Boolean) -> Unit = { _, _, _ -> },
    val loadWorkspaceSummary: (suspend (String) -> com.promenar.infinity.domain.workspace.PrivateWorkspaceSummary?)? = null,
    val loadWorktreeBranch: (suspend (String) -> String?)? = null,
)

internal data class HubSessionEntry(val session: Session, val agentTitle: String)

/**
 * 分支紧跟在同一列表里的主会话之后，按创建先后排列，标为嵌套；主会话不在列表中（已删除、未置顶或不匹配搜索）时分支留在原位。
 */
internal fun <T> nestBranchSessions(items: List<T>, session: (T) -> Session): List<Pair<T, Boolean>> {
    val ids = items.mapTo(mutableSetOf()) { session(it).id }
    val children = items.filter { session(it).parentSessionId?.let(ids::contains) == true }
        .groupBy { session(it).parentSessionId }
        .mapValues { (_, branches) -> branches.sortedBy { session(it).createdAt } }
    return buildList {
        items.forEach { item ->
            if (session(item).parentSessionId?.let(ids::contains) == true) return@forEach
            add(item to false)
            children[session(item).id]?.forEach { add(it to true) }
        }
    }
}

internal data class HubAgentEntry(val item: AgentDisplayItem, val sessionCount: Int)

/**
 * 对话页分区：未搜索时为“置顶”“最近”“助手”，搜索时 [sessions] 为匹配的会话。
 * 只收录列表中存在的助手的会话，系统助手名下的资料会话等不会出现。
 */
internal data class HubSections(
    val pinnedSessions: List<HubSessionEntry>,
    val sessions: List<HubSessionEntry>,
    val agents: List<HubAgentEntry>,
) {
    val isEmpty: Boolean get() = pinnedSessions.isEmpty() && sessions.isEmpty() && agents.isEmpty()
}

internal const val HUB_RECENT_LIMIT = 5
internal const val HUB_PINNED_LIMIT = 20
internal const val HUB_SEARCH_SESSION_LIMIT = 50

internal fun buildHubSections(
    agents: List<AgentDisplayItem>,
    sessionsByAgent: Map<String, List<Session>>,
    query: String,
): HubSections {
    val agentTitles = agents.associate { it.agent.id to it.title }
    val sessions = agents.flatMap { sessionsByAgent[it.agent.id].orEmpty() }
    fun List<Session>.entries() = map { HubSessionEntry(it, agentTitles.getValue(it.agentId)) }
    val needle = query.trim()
    if (needle.isNotEmpty()) {
        val matches = sessions
            .filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.lastMessage?.contains(needle, ignoreCase = true) == true
            }
            .sortedWith(compareByDescending<Session> { it.isPinned }.thenByDescending { it.updatedAt })
            .take(HUB_SEARCH_SESSION_LIMIT)
        return HubSections(
            pinnedSessions = emptyList(),
            sessions = matches.entries(),
            agents = filterAgentDisplays(agents, needle).map { HubAgentEntry(it, sessionsByAgent[it.agent.id]?.size ?: 0) },
        )
    }
    val (pinned, others) = sessions.sortedByDescending { it.updatedAt }.partition { it.isPinned }
    return HubSections(
        pinnedSessions = pinned.take(HUB_PINNED_LIMIT).entries(),
        sessions = others.take(HUB_RECENT_LIMIT).entries(),
        agents = agents.map { HubAgentEntry(it, sessionsByAgent[it.agent.id]?.size ?: 0) },
    )
}

/** 当天显示时刻，当年显示月/日，更早的带年份。 */
internal fun formatHubSessionTime(
    epochMillis: Long,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val pattern = when {
        time.toLocalDate() == now.toLocalDate() -> "HH:mm"
        time.year == now.year -> "M/d"
        else -> "yyyy/M/d"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(time)
}

@Composable
internal fun AgentHubScreenContent(
    state: AgentHubScreenState,
    actions: AgentHubScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initialExpandedAgentId: String? = null,
    /** 标题栏下方的启动自检提示；没有问题时不占位。 */
    startupBanner: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val searching = state.searchActive && state.searchQuery.isNotBlank()
    val sections = remember(state.displayAgents, state.sessionsByAgent, searching, state.searchQuery) {
        buildHubSections(state.displayAgents, state.sessionsByAgent, if (searching) state.searchQuery else "")
    }
    // 会话目录晚于助手加载：会话分组插到助手分组之前时，列表会按原首项锚定而停在中途，停在顶部附近时回到顶部。
    val sessionGroupsShown = sections.pinnedSessions.isNotEmpty() to sections.sessions.isNotEmpty()
    // 同一时间只展开一个助手，就地列出它的会话与“新建会话”。
    var expandedAgentId by rememberSaveable { mutableStateOf(initialExpandedAgentId) }
    val haptics = LocalInfinityHaptics.current
    LaunchedEffect(sessionGroupsShown) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    Scaffold(
        modifier = Modifier.testTag(UiTags.HUB_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                InfinityTabHeader(
                    title = stringResource(R.string.nav_tab_chat),
                    searchActive = state.searchActive,
                    query = state.searchQuery,
                    onQueryChange = actions.onSearch,
                    onSearchActiveChange = actions.onSearchActiveChange.takeIf { state.displayAgents.isNotEmpty() },
                    searchFieldTag = UiTags.HUB_SEARCH,
                ) {
                    HubNewChatAction(
                        agents = state.displayAgents,
                        onCreateSession = actions.onCreateSession,
                        onRequestAdd = actions.onRequestAdd,
                    )
                }
                startupBanner()
            }
        },
    ) { paddingValues ->
        ConfirmDialog(
            show = state.pendingDeleteAgentId != null,
            onDismiss = actions.onCancelDelete,
            onConfirm = {
                state.pendingDeleteAgentId?.let(actions.onConfirmDelete)
            },
            title = stringResource(R.string.agent_edit_delete_title),
            description = agentDeleteMessage(state.sessionsByAgent[state.pendingDeleteAgentId].orEmpty().size),
            confirmLabel = stringResource(R.string.agent_edit_delete_confirm),
            destructive = true
        )
        state.pendingDeleteSessionId?.let { sessionId ->
            com.promenar.infinity.ui.workspace.DeleteSessionDialog(
                sessionId = sessionId,
                loadSummary = actions.loadWorkspaceSummary,
                onConfirm = { keepFiles, removeWorktree -> actions.onDeleteSession(sessionId, keepFiles, removeWorktree) },
                loadWorktreeBranch = actions.loadWorktreeBranch,
                onCancel = actions.onCancelDeleteSession,
            )
        }

        // 无助手、搜索无结果与列表之间交叉淡入。
        AnimatedContent(
            targetState = when {
                state.displayAgents.isEmpty() -> 0
                searching && sections.isEmpty -> 1
                else -> 2
            },
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "hub_content_phase",
        ) { phase ->
            when (phase) {
                0 -> EmptyAgentState(
                    onCreateAgent = actions.onRequestAdd,
                    modifier = Modifier.padding(paddingValues),
                )

                1 -> HubSearchEmptyState(modifier = Modifier.padding(paddingValues))

                else -> ProvideGroupedPress {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(UiTags.HUB_AGENT_LIST),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding() + InfinitySpacing.Small,
                            bottom = paddingValues.calculateBottomPadding() + InfinitySpacing.XLarge,
                        ),
                    ) {
                        if (sections.pinnedSessions.isNotEmpty()) {
                            item(key = "pinned") {
                                HubSessionSection(
                                    modifier = Modifier.animateItem(),
                                    title = stringResource(R.string.hub_section_pinned),
                                    entries = sections.pinnedSessions,
                                    activity = state.sessionActivity,
                                    generation = state.sessionGeneration,
                                    selectedSessionId = state.selectedSessionId,
                                    actions = actions,
                                )
                            }
                        }
                        if (sections.sessions.isNotEmpty()) {
                            item(key = "sessions") {
                                HubSessionSection(
                                    modifier = Modifier.animateItem(),
                                    title = stringResource(if (searching) R.string.hub_section_sessions else R.string.hub_section_recent),
                                    entries = sections.sessions,
                                    activity = state.sessionActivity,
                                    generation = state.sessionGeneration,
                                    selectedSessionId = state.selectedSessionId,
                                    actions = actions,
                                )
                            }
                        }
                        if (sections.agents.isNotEmpty() || !searching) {
                            item(key = "agents") {
                                InfinitySettingsSection(
                                    title = stringResource(R.string.hub_section_agents),
                                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                                    modifier = Modifier.animateItem(),
                                ) {
                                    sections.agents.forEach { entry ->
                                        val agentId = entry.item.agent.id
                                        key(agentId) {
                                        val expanded = expandedAgentId == agentId
                                        HubAgentRow(
                                            entry = entry,
                                            expanded = expanded,
                                            onToggleExpand = {
                                                // 展开与收起分别使用开/关两档轻振，与开关控件的触感一致。
                                                haptics.toggle(!expanded)
                                                expandedAgentId = if (expanded) null else agentId
                                            },
                                            onTogglePin = { actions.onTogglePin(agentId) },
                                            onEdit = { actions.onEdit(agentId) },
                                            onDelete = { actions.onRequestDelete(agentId) },
                                        )
                                        AnimatedVisibility(visible = expanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                                            HubAgentSessions(
                                                sessions = state.sessionsByAgent[agentId].orEmpty(),
                                                activity = state.sessionActivity,
                                                generation = state.sessionGeneration,
                                                selectedSessionId = state.selectedSessionId,
                                                actions = actions,
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
        }
    }
}

@Composable
private fun HubSessionSection(
    title: String,
    entries: List<HubSessionEntry>,
    activity: Map<String, HubSessionActivity>,
    actions: AgentHubScreenActions,
    modifier: Modifier = Modifier,
    generation: Map<String, GenerationTaskSnapshot> = emptyMap(),
    selectedSessionId: String? = null,
) {
    InfinitySettingsSection(
        title = title,
        headerStartPadding = InfinitySpacing.ScreenHorizontal,
        modifier = modifier,
    ) {
        // 置顶、删除或新会话进入时，分组高度平滑变化而不是整块跳动。
        Column(Modifier.animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))) {
            val nestedEntries = remember(entries) { nestBranchSessions(entries) { it.session } }
            nestedEntries.forEach { (entry, nested) ->
                val sessionId = entry.session.id
                key(sessionId) {
                    HubSessionRow(
                        session = entry.session,
                        nested = nested,
                        subtitle = entry.agentTitle,
                        activity = activity[sessionId],
                        generation = generation[sessionId],
                        selected = sessionId == selectedSessionId,
                        onOpen = { actions.onOpenChat(sessionId) },
                        onTogglePin = { actions.onToggleSessionPin(sessionId) },
                        onRequestDelete = { actions.onRequestDeleteSession(sessionId) },
                    )
                }
            }
        }
    }
}

/** 页头“新对话”：选择助手后直接新建会话；还没有助手时转为添加助手。 */
@Composable
private fun HubNewChatAction(
    agents: List<AgentDisplayItem>,
    onCreateSession: (String) -> Unit,
    onRequestAdd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (agents.isEmpty()) onRequestAdd() else expanded = true },
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .testTag(UiTags.HUB_NEW_CHAT),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.hub_new_chat),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HubNewChatMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            agents = agents,
            onCreateSession = onCreateSession,
            onRequestAdd = onRequestAdd,
        )
    }
}

/** “新对话”的助手选择菜单：选择助手后直接新建会话，末项添加助手。页头与内屏会话列的新建入口共用。 */
@Composable
internal fun HubNewChatMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    agents: List<AgentDisplayItem>,
    onCreateSession: (String) -> Unit,
    onRequestAdd: () -> Unit,
) {
    InfinityDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        minWidth = 200.dp,
    ) {
        Text(
            text = stringResource(R.string.hub_new_chat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
        )
        agents.forEach { item ->
            InfinityMenuItem(
                leadingIcon = {
                    AgentAvatar(
                        icon = item.agent.icon,
                        color = item.agent.color,
                        seed = item.agent.id,
                        name = item.title,
                        photoUri = item.agent.avatarPath,
                        size = 28.dp,
                    )
                },
                text = {
                    Text(
                        text = item.title,
                        style = InfinityTypography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = {
                    onDismiss()
                    onCreateSession(item.agent.id)
                },
                modifier = Modifier.testTag(UiTags.hubNewChatAgent(item.agent.id)),
            )
        }
        InfinityMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { Text(stringResource(R.string.hub_btn_add_agent), style = InfinityTypography.labelLarge) },
            onClick = {
                onDismiss()
                onRequestAdd()
            },
            modifier = Modifier.testTag(UiTags.HUB_ADD_AGENT),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubSessionRow(
    session: Session,
    subtitle: String?,
    activity: HubSessionActivity?,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
    /** 该会话的生成任务；生成中的指示按它的阶段、重试与出字量变化。 */
    generation: GenerationTaskSnapshot? = null,
    selected: Boolean = false,
    tag: String = UiTags.hubSession(session.id),
    /** 分支排在主会话之下时缩进。 */
    nested: Boolean = false,
) {
    val isBranch = session.parentSessionId != null
    val branchState = stringResource(R.string.branch_label)
    var menuExpanded by remember { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    val pinLabel = stringResource(if (session.isPinned) R.string.common_cd_unpin else R.string.common_cd_pin)
    val deleteLabel = stringResource(R.string.shared_btn_delete)
    val pinnedState = stringResource(R.string.sessions_tag_pinned)
    val activityState = activity?.let { stringResource(hubSessionActivityLabel(it)) }
    val time = remember(session.updatedAt) {
        formatHubSessionTime(session.updatedAt, System.currentTimeMillis())
    }
    Box {
        InfinitySettingsRowLayout(
            title = session.title.ifBlank { stringResource(R.string.hub_session_untitled) },
            subtitle = subtitle,
            // 会话行固定单行：长标题缓慢滚动，不折成两行撑高列表。
            marqueeTitle = true,
            modifier = Modifier
                .testTag(tag)
                // 内屏三列：会话列正在显示的会话与设置目录的选中项同样高亮。
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .semantics {
                    if (selected) this.selected = true
                    listOfNotNull(branchState.takeIf { isBranch }, pinnedState.takeIf { session.isPinned }, activityState)
                        .takeIf { it.isNotEmpty() }?.let { stateDescription = it.joinToString("，") }
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                        CustomAccessibilityAction(deleteLabel) { onRequestDelete(); true },
                    )
                }
                .infinityMenuHost(menuExpanded)
                .infinityMenuTouch(menuTouch)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true },
                    onLongClickLabel = stringResource(R.string.hub_cd_session_actions),
                ),
            leadingContent = if (!isBranch) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (nested) Spacer(Modifier.width(InfinitySpacing.Medium))
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(InfinitySpacing.XSmall))
                    }
                    // 生成中以生成指示、等待批准以文字替换时间，回到首页一眼看出哪个会话还在跑。
                    Crossfade(targetState = activity, label = "hub_session_activity") { current ->
                        when (current) {
                            HubSessionActivity.GENERATING -> HubGenerationIndicator(
                                generation = generation,
                                modifier = Modifier.testTag(UiTags.hubSessionGenerating(tag)),
                            )
                            HubSessionActivity.AWAITING_APPROVAL -> Text(
                                text = stringResource(R.string.hub_session_awaiting_approval),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                            // 待回答与提问卡同用琥珀色；排队用中性色，表示尚未开始。
                            HubSessionActivity.AWAITING_ANSWER -> Text(
                                text = stringResource(R.string.ask_card_status_waiting),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = MaterialTheme.infinityDomainColors.warning,
                                maxLines = 1,
                                modifier = Modifier.testTag(UiTags.hubSessionAwaitingAnswer(tag)),
                            )
                            HubSessionActivity.QUEUED -> Text(
                                text = stringResource(R.string.ask_card_status_queued),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            null -> Text(
                                text = time,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
        )
        InfinityDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = androidx.compose.ui.unit.DpOffset(InfinitySpacing.RowHorizontal, 0.dp),
            touch = menuTouch,
        ) {
            HubPinMenuItem(isPinned = session.isPinned) {
                menuExpanded = false
                onTogglePin()
            }
            InfinityMenuDivider()
            HubDeleteMenuItem {
                menuExpanded = false
                onRequestDelete()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubAgentRow(
    entry: HubAgentEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val agent = entry.item.agent
    var menuExpanded by remember { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    val pinLabel = stringResource(if (agent.isPinned) R.string.common_cd_unpin else R.string.common_cd_pin)
    val editLabel = stringResource(R.string.shared_btn_edit)
    val deleteLabel = stringResource(R.string.shared_btn_delete)
    val rowState = listOfNotNull(
        stringResource(R.string.sessions_tag_pinned).takeIf { agent.isPinned },
        stringResource(if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed),
    ).joinToString(", ")
    Box {
        HubRowLayout(
            modifier = Modifier
                .testTag(UiTags.hubAgentCard(agent.id))
                .semantics {
                    stateDescription = rowState
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                        CustomAccessibilityAction(editLabel) { onEdit(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .infinityMenuHost(menuExpanded)
                .infinityMenuTouch(menuTouch)
                .combinedClickable(
                    onClick = onToggleExpand,
                    onLongClick = { menuExpanded = true },
                    onLongClickLabel = stringResource(R.string.hub_cd_agent_actions),
                ),
            leading = {
                Box(
                    modifier = if (agent.avatarPath != null) {
                        Modifier.testTag(UiTags.hubAgentCustomAvatar(agent.id))
                    } else {
                        Modifier
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    AgentAvatar(
                        icon = agent.icon,
                        color = agent.color,
                        seed = agent.id,
                        name = entry.item.title,
                        photoUri = agent.avatarPath,
                        size = 40.dp,
                    )
                }
            },
            title = entry.item.title,
            // 会话数放在副标题开头，右侧只留展开箭头，长名称与大字号下标题不被挤压。
            subtitle = listOf(
                pluralStringResource(R.plurals.hub_agent_session_count, entry.sessionCount, entry.sessionCount),
                entry.item.subtitle,
            ).filter { it.isNotBlank() }.joinToString(" · "),  // wrap-check: allow 含可能很长的智能体说明，按行数省略
            pinned = agent.isPinned,
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            },
        )
        InfinityDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = androidx.compose.ui.unit.DpOffset(InfinitySpacing.RowHorizontal, 0.dp),
            touch = menuTouch,
        ) {
            HubPinMenuItem(isPinned = agent.isPinned, modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_PIN)) {
                menuExpanded = false
                onTogglePin()
            }
            InfinityMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { Text(editLabel, style = InfinityTypography.labelLarge) },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
                modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_EDIT)
            )
            InfinityMenuDivider()
            HubDeleteMenuItem(modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_DELETE)) {
                menuExpanded = false
                onDelete()
            }
        }
    }
}

/**
 * 会话行的生成指示：与会话界面同一套动效，按该会话自己的生成阶段、重试倒计时与出字量驱动，
 * 首字前、流式、工具执行与重试各有节奏。
 */
@Composable
private fun HubGenerationIndicator(generation: GenerationTaskSnapshot?, modifier: Modifier = Modifier) {
    val dots = @Composable { GenerationDots(dotSize = 5.dp, gap = 4.dp, lift = 3.dp, modifier = modifier) }
    // 没有任务快照（静态预览）时沿用环境动效。
    if (generation == null) return dots()
    val motion = rememberGenerationMotion(
        phase = generationMotionPhase(generation.phase, awaitingUser = false),
        retry = generation.retry,
        outputLength = generation.generatedChars,
    )
    CompositionLocalProvider(LocalGenerationMotion provides motion, content = dots)
}

/** 助手展开区：该助手的全部会话（长按置顶/删除）；新建会话统一走页头“新建会话”菜单。 */
@Composable
private fun HubAgentSessions(
    sessions: List<Session>,
    activity: Map<String, HubSessionActivity>,
    actions: AgentHubScreenActions,
    generation: Map<String, GenerationTaskSnapshot> = emptyMap(),
    selectedSessionId: String? = null,
) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))) {
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.hub_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = InfinitySpacing.RowHorizontal,
                    end = InfinitySpacing.RowHorizontal,
                    top = InfinitySpacing.Small,
                    bottom = InfinitySpacing.Small,
                ),
            )
        }
        val nestedSessions = remember(sessions) { nestBranchSessions(sessions) { it } }
        nestedSessions.forEach { (session, nested) ->
            HubSessionRow(
                session = session,
                nested = nested,
                subtitle = null,
                activity = activity[session.id],
                generation = generation[session.id],
                selected = session.id == selectedSessionId,
                onOpen = { actions.onOpenChat(session.id) },
                onTogglePin = { actions.onToggleSessionPin(session.id) },
                onRequestDelete = { actions.onRequestDeleteSession(session.id) },
                tag = UiTags.hubAgentSession(session.id),
            )
        }
    }
}

/** 带头像的列表行：几何与设置行一致，头像位放大到 40dp。 */
@Composable
private fun HubRowLayout(
    modifier: Modifier,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    pinned: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // 放大字号时标题与副标题各放宽到两行，与设置行的大字号处理一致。
    val maxLines = if (LocalDensity.current.fontScale > 1f) 2 else 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(InfinitySpacing.XSmall))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(InfinitySpacing.Medium))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                content = trailing,
            )
        }
    }
}

@Composable
private fun HubPinMenuItem(isPinned: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    InfinityMenuItem(
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        text = {
            Text(
                stringResource(if (isPinned) R.string.common_cd_unpin else R.string.common_cd_pin),
                style = InfinityTypography.labelLarge,
            )
        },
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HubDeleteMenuItem(modifier: Modifier = Modifier, onClick: () -> Unit) {
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
                stringResource(R.string.shared_btn_delete),
                style = InfinityTypography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        },
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HubSearchEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.common_search_no_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// =====================================================================================
// Empty State —— 使用 Material Icon，不使用 emoji
// =====================================================================================

@Composable
private fun EmptyAgentState(
    onCreateAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(UiTags.HUB_EMPTY_STATE),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.hub_empty_title),
                style = InfinityTypography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hub_empty_subtitle),
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onCreateAgent,
                modifier = Modifier.testTag(UiTags.HUB_EMPTY_ADD_AGENT),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.hub_btn_add_agent))
            }
        }
    }
}

// =====================================================================================
// 辅助函数
// =====================================================================================

/**
 * 显示叠加层解析：预置未定制 → 当前 Locale 资源；已定制/非预置 → DB 原文。
 * 实时求值，跟随语言切换重组，不写 DB。
 */
@Composable
private fun resolveAgentDisplay(
    agent: Agent,
): Pair<String, String> {
    val fields = PresetAgentDisplay.resolve(agent)
    @Composable
    fun resolve(source: PresetAgentDisplay.TextSource): String = when (source) {
        is PresetAgentDisplay.TextSource.Resource -> stringResource(source.res)
        is PresetAgentDisplay.TextSource.Literal -> source.value
    }
    return resolve(fields.name) to resolve(fields.description)
}

internal data class AgentDisplayItem(val agent: Agent, val title: String, val subtitle: String)

internal fun filterAgentDisplays(items: List<AgentDisplayItem>, query: String): List<AgentDisplayItem> =
    if (query.isBlank()) items else items.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.subtitle.contains(query, ignoreCase = true)
    }
