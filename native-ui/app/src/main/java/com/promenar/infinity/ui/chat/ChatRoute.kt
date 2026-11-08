package com.promenar.infinity.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.promenar.infinity.ui.common.InfinityPaneLevel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.promenar.infinity.ui.workspace.toUi
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.map
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ui.startup.affectsSession
import com.promenar.infinity.R
import com.promenar.infinity.background.generation.GENERATION_NOTIFICATION_PERMISSION_ASKED
import com.promenar.infinity.background.generation.GENERATION_NOTIFICATION_PERMISSION_PREFS
import com.promenar.infinity.background.generation.NotificationPermissionOverlayState
import com.promenar.infinity.background.generation.NotificationPermissionPromptState
import com.promenar.infinity.background.generation.shouldExplainNotificationPermission
import com.promenar.infinity.background.generation.shouldShowNotificationPermissionDialog
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.ui.common.EditorMode
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPopoverAlign
import com.promenar.infinity.ui.common.InfinityPopoverSide
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.common.UnifiedPromptEditor
import com.promenar.infinity.ui.testing.UiTags
import androidx.compose.ui.res.pluralStringResource
import com.promenar.infinity.data.model.turnMemory
import kotlinx.coroutines.launch

internal sealed interface ChatOverlay {
    data object ModelSettings : ChatOverlay
    /** 宽窗口下贴着输入区模型按钮弹出的模型速选；窗口变窄时改为会话设置的“模型”页。 */
    data object ModelQuickPick : ChatOverlay
    /** 会话设置的“参数”页（模型速选的“更多会话设置”）。 */
    data object SessionParams : ChatOverlay
    /** 会话设置的“工具”页（斜杠命令 /mode 不带参数时打开）。 */
    data object SessionTools : ChatOverlay
    data object PromptEditor : ChatOverlay
    data object ClearHistory : ChatOverlay
    data object DeleteSession : ChatOverlay
    data object RenameSession : ChatOverlay
    /** 导出会话面板（会话菜单或不带参数的 /export）。 */
    data object ExportSession : ChatOverlay
    data class Truncate(val messageId: String) : ChatOverlay
    data class TurnMemory(val messageId: String) : ChatOverlay
}

internal data class ChatOverlayQueueState(
    val active: ChatOverlay? = null,
    val queued: ChatOverlay? = null,
)

/** 聊天页只展示一个模态层，冲突时保留最早的一个待展示请求。 */
internal fun enqueueChatOverlay(
    state: ChatOverlayQueueState,
    requested: ChatOverlay,
    hasPendingNotificationPermission: Boolean,
): ChatOverlayQueueState = when {
    state.active == null && !hasPendingNotificationPermission -> state.copy(active = requested)
    state.queued == null -> state.copy(queued = requested)
    else -> state
}

internal fun advanceChatOverlay(
    state: ChatOverlayQueueState,
    hasPendingNotificationPermission: Boolean,
): ChatOverlayQueueState = if (
    state.active == null && state.queued != null && !hasPendingNotificationPermission
) {
    ChatOverlayQueueState(active = state.queued)
} else {
    state
}

private const val TRUNCATE_OVERLAY_PREFIX = "truncate:"
private const val TURN_MEMORY_OVERLAY_PREFIX = "turn_memory:"
/** 运行中的 ssh_exec 只会出现在最近几条消息里；只扫描这一段，避免长会话每次重组都遍历全部步骤。 */
private const val REMOTE_USAGE_MESSAGE_WINDOW = 8

internal fun saveChatOverlay(overlay: ChatOverlay?): String? = when (overlay) {
    null -> null
    ChatOverlay.ModelSettings -> "model_settings"
    ChatOverlay.ModelQuickPick -> "model_quick_pick"
    ChatOverlay.SessionParams -> "session_params"
    ChatOverlay.SessionTools -> "session_tools"
    ChatOverlay.PromptEditor -> "prompt_editor"
    ChatOverlay.ClearHistory -> "clear_history"
    ChatOverlay.DeleteSession -> "delete_session"
    ChatOverlay.RenameSession -> "rename_session"
    ChatOverlay.ExportSession -> "export_session"
    is ChatOverlay.Truncate -> TRUNCATE_OVERLAY_PREFIX + overlay.messageId
    is ChatOverlay.TurnMemory -> TURN_MEMORY_OVERLAY_PREFIX + overlay.messageId
}

internal fun restoreChatOverlay(token: String?): ChatOverlay? = when {
    token == "model_settings" -> ChatOverlay.ModelSettings
    token == "model_quick_pick" -> ChatOverlay.ModelQuickPick
    token == "session_params" -> ChatOverlay.SessionParams
    token == "session_tools" -> ChatOverlay.SessionTools
    token == "prompt_editor" -> ChatOverlay.PromptEditor
    token == "clear_history" -> ChatOverlay.ClearHistory
    token == "delete_session" -> ChatOverlay.DeleteSession
    token == "rename_session" -> ChatOverlay.RenameSession
    token == "export_session" -> ChatOverlay.ExportSession
    token?.startsWith(TRUNCATE_OVERLAY_PREFIX) == true ->
        ChatOverlay.Truncate(token.removePrefix(TRUNCATE_OVERLAY_PREFIX))
    token?.startsWith(TURN_MEMORY_OVERLAY_PREFIX) == true ->
        ChatOverlay.TurnMemory(token.removePrefix(TURN_MEMORY_OVERLAY_PREFIX))
    else -> null
}

internal fun shouldShowGenerationFailureSnackbar(uiState: ChatUiState): Boolean =
    uiState.generationNotice != null &&
        uiState.generationFailureSurface !=
        com.promenar.infinity.data.generation.GenerationFailureSurface.INLINE_MESSAGE

internal enum class NotificationPermissionDisposition {
    GRANTED,
    SHOW_EXPLANATION,
    WAIT_FOR_SYSTEM_RESULT,
    REJECTED,
}

internal fun notificationPermissionDisposition(
    requestTaskId: String,
    granted: Boolean,
    shouldExplain: Boolean,
    inFlightTaskId: String?,
): NotificationPermissionDisposition = when {
    granted -> NotificationPermissionDisposition.GRANTED
    inFlightTaskId == requestTaskId -> NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT
    shouldExplain -> NotificationPermissionDisposition.SHOW_EXPLANATION
    else -> NotificationPermissionDisposition.REJECTED
}

internal fun notificationPermissionResultTaskId(
    inFlightTaskId: String?,
    explanationTaskId: String?,
): String? = inFlightTaskId ?: explanationTaskId

/** Chat 路由的显式依赖边界；测试可替换工厂和系统端口，无需全局可变注册表。 */
data class ChatRouteDependencies(
    val viewModelFactory: ViewModelProvider.Factory,
    val taskRepository: ITaskRepository,
    val copyToClipboard: (Context, String) -> Unit = ::copyTextToClipboard,
) {
    companion object {
        fun production(application: InfinityApplication): ChatRouteDependencies =
            ChatRouteDependencies(
                viewModelFactory = ChatViewModel.factory(application),
                taskRepository = application.taskRepository,
            )

        fun withGenerationCoordinator(
            application: InfinityApplication,
            generationCoordinator: com.promenar.infinity.domain.generation.GenerationCoordinator,
        ): ChatRouteDependencies = ChatRouteDependencies(
            viewModelFactory = ChatViewModel.factory(
                application = application,
                generationCoordinatorOverride = generationCoordinator,
            ),
            taskRepository = application.taskRepository,
        )
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Infinity Workbench", text))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatRoute(
    sessionId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (String) -> Unit = {},
    dependencies: ChatRouteDependencies? = null,
    onOpenDefaultModels: () -> Unit = {},
    /** 打开知识图谱页并搜索给定实体（“本轮记忆”面板的“在图谱中查看”）。 */
    onOpenKnowledgeGraph: ((String) -> Unit)? = null,
    /** 打开子代理只读查看页。 */
    onOpenSubagent: ((sessionId: String, stepId: String, index: Int) -> Unit)? = null,
    /** 打开 Tailscale 设置（模型选择器的 Tailscale 灯、tailnet 失败提示的“Tailscale 设置”与“去登录”）。 */
    onOpenTailscaleSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as InfinityApplication
    var gitSummary by remember(sessionId) { mutableStateOf<com.promenar.infinity.ui.git.GitEntrySummary.Repository?>(null) }
    var gitRefresh by remember(sessionId) { mutableStateOf(0) }
    val resolvedDependencies = remember(application, dependencies) {
        dependencies ?: ChatRouteDependencies.production(application)
    }
    val chatViewModel: ChatViewModel = viewModel(factory = resolvedDependencies.viewModelFactory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val startupIssues by application.startupIssues.collectAsStateWithLifecycle()
    val startupWritePaused = startupIssues.affectsSession(sessionId, uiState.session?.workspaceRootUuid)
    var showStartupIssues by remember(sessionId) { mutableStateOf(false) }
    // 运行模式：进入会话做一次检查（旧会话在 Linux 可用时首次固定为 Linux），不可用或未选择时弹出检查点；
    // 之后只观察，不在环境变化时自动弹出或改变模式。
    val linuxSupported = com.promenar.infinity.distribution.DistributionCapabilities.linuxRuntimeAvailable
    val runModeStatus by remember(sessionId) { application.sessionRunModes.observe(sessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var runModeCheckpoint by remember(sessionId) { mutableStateOf<com.promenar.infinity.ui.chat.runmode.RunModeCheckpointStage?>(null) }
    var showRunModePicker by remember(sessionId) { mutableStateOf(false) }
    val runModeScope = rememberCoroutineScope()
    LaunchedEffect(sessionId) {
        val check = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { application.sessionRunModes.check(sessionId) }
        }.getOrNull()
        runModeCheckpoint = check?.let { com.promenar.infinity.ui.chat.runmode.initialStage(it) }
    }
    val runModeBadge = runModeStatus?.takeIf { linuxSupported }?.let { status ->
        com.promenar.infinity.ui.chat.runmode.RunModeBadge(status.mode,
            unavailable = status.check is com.promenar.infinity.data.session.SessionRunModeCheck.EnvironmentUnavailable)
    }
    // 顶栏状态行的工作区：进入会话、切换工作区与每轮生成结束时解析一次；解析失败时不显示。
    var workspaceRefresh by remember(sessionId) { mutableStateOf(0) }
    val boundWorkspaceId = uiState.session?.workspaceId
    val workspaceStatus by produceState<com.promenar.infinity.ui.workspace.SessionWorkspaceUi?>(null, sessionId, boundWorkspaceId, workspaceRefresh, uiState.isGenerating) {
        if (uiState.isGenerating) return@produceState
        value = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { application.sessionWorkspaceResolver.resolve(sessionId) }.toUi()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    var showAgentReach by remember(sessionId) { mutableStateOf(false) }
    var pickingWorkspace by remember(sessionId) { mutableStateOf(false) }
    var workspaceSwitchFailed by remember(sessionId) { mutableStateOf(false) }
    // 与执行层一致：生成进行中或有待审批的操作时不允许切换工作区。
    val workspaceBusy = uiState.isGenerating || uiState.session?.approvalRequest != null
    val onWorkspaceSwitched: (com.promenar.infinity.data.workspace.WorkspaceSwitchResult) -> Unit = { result ->
        workspaceSwitchFailed = result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Failed ||
            result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.RejectedBusy
        if (result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched) workspaceRefresh++
    }
    val turnGraphStates by chatViewModel.turnGraphStates.collectAsStateWithLifecycle()
    val composerQueue by chatViewModel.composerQueue.collectAsStateWithLifecycle()
    val timedGrants by chatViewModel.activeTimedGrants.collectAsStateWithLifecycle()
    val interaction by application.generationInteractions.pending.collectAsStateWithLifecycle()
    val generationProgress by remember(sessionId) {
        application.generationCoordinator.observe(sessionId)
    }.collectAsStateWithLifecycle()
    interaction?.takeIf { it.sessionId == sessionId }?.let { prompt ->
        val unlimited = prompt.kind == com.promenar.infinity.data.generation.GenerationInteractionKind.UNLIMITED_AUTO
        InfinityConfirmDialog(
            onDismissRequest = { application.generationInteractions.respond(prompt.id, false) },
            title = stringResource(if (unlimited) R.string.p0_auto_confirm_title else R.string.p0_loop_title),
            message = stringResource(if (unlimited) R.string.p0_auto_confirm_body else R.string.p0_loop_body),
            confirmText = stringResource(R.string.p0_continue),
            cancelText = stringResource(R.string.generation_notification_stop),
            onConfirm = { application.generationInteractions.respond(prompt.id, true) },
            onCancel = { application.generationInteractions.respond(prompt.id, false) },
            confirmButtonModifier = Modifier.heightIn(min = 48.dp),
            cancelButtonModifier = Modifier.heightIn(min = 48.dp),
        )
    }
    val activeTaskTree by remember(sessionId, resolvedDependencies.taskRepository) {
        resolvedDependencies.taskRepository.observeActiveTree(sessionId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeTaskGoal by remember(sessionId, resolvedDependencies.taskRepository) {
        resolvedDependencies.taskRepository.observeGoal(sessionId)
    }.collectAsStateWithLifecycle(initialValue = null)
    val taskPanel = remember(activeTaskTree, activeTaskGoal, uiState.isGenerating) {
        taskPanelUiState(activeTaskTree, uiState.isGenerating, activeTaskGoal)
    }
    val stateBoard by chatViewModel.stateBoard.collectAsStateWithLifecycle()
    val subagentApprovals by application.subagentApprovalBroker.pending.collectAsStateWithLifecycle()
    val sessionSubagentApprovals = remember(subagentApprovals, sessionId) { subagentApprovals.filter { it.sessionId == sessionId } }
    val inputText by chatViewModel.inputText.collectAsStateWithLifecycle()
    val pendingQuestion by chatViewModel.pendingQuestion.collectAsStateWithLifecycle()
    val pendingSecretInput by chatViewModel.pendingSecretInput.collectAsStateWithLifecycle()
    val secretInputSubmission by chatViewModel.secretInputSubmission.collectAsStateWithLifecycle()
    val secretInputReceipts by chatViewModel.secretInputReceipts.collectAsStateWithLifecycle()
    val secretOutputCards by chatViewModel.secretOutputCards.collectAsStateWithLifecycle()
    var showSecretGitHubLogin by remember { mutableStateOf(false) }
    val tokenState by chatViewModel.tokenIndicatorState.collectAsStateWithLifecycle()
    val ragPhases by chatViewModel.ragPhases.collectAsStateWithLifecycle()
    val compressionState by chatViewModel.compressionState.collectAsStateWithLifecycle()
    val postProcessTasks by chatViewModel.postProcessTasks.collectAsStateWithLifecycle()
    val generationRetry by chatViewModel.generationRetry.collectAsStateWithLifecycle()
    val generationPhase by chatViewModel.generationPhase.collectAsStateWithLifecycle()
    val tailnetWait by chatViewModel.tailnetWait.collectAsStateWithLifecycle()
    val draftDocuments by chatViewModel.draftDocuments.collectAsStateWithLifecycle()
    val draftDocumentBudget by chatViewModel.draftDocumentBudget.collectAsStateWithLifecycle()
    val draftQuotes by chatViewModel.draftQuotes.collectAsStateWithLifecycle()
    val themePreferences by application.themePreferenceStore.state.collectAsStateWithLifecycle()
    val isImportingDocument by chatViewModel.isImportingDocument.collectAsStateWithLifecycle()
    val draftConsumptionEpoch by chatViewModel.draftConsumptionEpoch.collectAsStateWithLifecycle()
    val deletionUndo by chatViewModel.deletionUndo.collectAsStateWithLifecycle()
    val haptics by rememberUpdatedState(LocalInfinityHaptics.current)
    val statusHaptics = remember(chatViewModel) { ChatStatusHapticTracker() }
    val hapticSnapshot = ChatHapticSnapshot(uiState.session?.id, uiState.status, uiState.approvalRequest?.hapticKey())
    LaunchedEffect(statusHaptics, hapticSnapshot) {
        statusHaptics.next(hapticSnapshot, System.currentTimeMillis())?.let(haptics::perform)
    }
    LaunchedEffect(statusHaptics) {
        chatViewModel.generationOutcomes.collect { outcome ->
            statusHaptics.onOutcome(outcome, System.currentTimeMillis())?.let(haptics::perform)
        }
    }
    val taskCompletionUndo by chatViewModel.taskCompletionUndo.collectAsStateWithLifecycle()
    val providerModels by ProviderManager.getInstance().providerModels.collectAsStateWithLifecycle()
    val modelDisplayNames = remember(providerModels, uiState.session?.modelId, uiState.messages) {
        resolveModelDisplayNames(
            sessionModelId = uiState.session?.modelId,
            messageModelIds = uiState.messages.map { it.modelId },
            providerModels = providerModels,
        )
    }
    val providers by ProviderManager.getInstance().providers.collectAsStateWithLifecycle()
    val providerDisplayNames = remember(providers) {
        providers.filter { it.name.isNotBlank() }.associate { it.id to it.name }
    }
    val agentReasoningEffort by chatViewModel.agentReasoningEffort.collectAsStateWithLifecycle()
    val sessionReasoningEffort = uiState.session?.inferenceParams?.reasoningEffort
    val reasoningControl = remember(providers, providerModels, uiState.session?.modelId, sessionReasoningEffort, agentReasoningEffort) {
        chatReasoningControl(uiState.session?.modelId, providers, providerModels, sessionReasoningEffort, agentReasoningEffort)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarData by remember { mutableStateOf<InfinitySnackbarData?>(null) }
    var snackbarAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var selectedImageUriStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val selectedImageUris = selectedImageUriStrings.map(Uri::parse)
    // 画板：打开的对象与完成后替换的输入框图片下标（null 表示追加）。
    val imageAnnotationAvailable by chatViewModel.imageAnnotationAvailable.collectAsStateWithLifecycle()
    var annotationTarget by rememberSaveable(stateSaver = com.promenar.infinity.ui.annotation.AnnotationTargetSaver) {
        mutableStateOf<com.promenar.infinity.ui.annotation.AnnotationTarget?>(null)
    }
    var annotationReplaceIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedImageAnnotations by androidx.compose.runtime.produceState(
        initialValue = emptyMap<String, com.promenar.infinity.domain.media.ImageAnnotation?>(),
        selectedImageUriStrings,
    ) {
        value = selectedImageUriStrings.distinct().associateWith { chatViewModel.readImageAnnotation(Uri.parse(it)) }
    }
    val openComposerImageEditor: (Int) -> Unit = { index ->
        selectedImageUriStrings.getOrNull(index)?.let { uriString ->
            val annotation = selectedImageAnnotations[uriString]
            val source = annotation?.sourcePath
            annotationTarget = when {
                annotation?.kind == com.promenar.infinity.domain.media.ImageAnnotationKind.SKETCH ->
                    com.promenar.infinity.ui.annotation.AnnotationTarget.Sketch(annotation)
                annotation != null && source != null ->
                    com.promenar.infinity.ui.annotation.AnnotationTarget.WorkspaceImage(source, annotation)
                else -> com.promenar.infinity.ui.annotation.AnnotationTarget.ComposerImage(Uri.parse(uriString))
            }
            annotationReplaceIndex = index
        }
    }
    var activeOverlayToken by rememberSaveable { mutableStateOf<String?>(null) }
    val popoverAnchors = remember { ChatPopoverAnchors() }
    val wideWindow = com.promenar.infinity.ui.common.rememberInfinityWideWindow()
    // 媒体不可用占位请求定位的工作区目录；资源管理器打开后消费。
    var workspaceLocatePath by rememberSaveable { mutableStateOf<String?>(null) }
    var queuedOverlayToken by rememberSaveable { mutableStateOf<String?>(null) }
    var chatPagesToken by rememberSaveable { mutableStateOf("") }
    val chatPages = restoreChatPages(chatPagesToken)
    // 二级页面由检视器还是整页承载，以会话宿主自身的宽度为准：内屏三列的会话列虽在宽窗口里，自身仍是窄窗口。
    var paneInspector by remember { mutableStateOf(wideWindow) }
    // 远程面板请求打开后台任务列表的次数，交给会话页打开运行详情的“后台”页。
    var backgroundJobsRequest by remember(sessionId) { mutableIntStateOf(0) }
    // 窄窗口整页与检视器“文件”“Git”页签共用的层级；收起时仍按最后的层级渲染，退场动画期间内容不消失。
    val pageStack = chatPageStack(chatPages, paneInspector)
    var lastPageStack by remember { mutableStateOf(pageStack) }
    SideEffect { if (pageStack.isNotEmpty()) lastPageStack = pageStack }
    val shownChatPages = pageStack.ifEmpty { lastPageStack }
    val setChatPages: (List<ChatPage>) -> Unit = { next ->
        val pages = restoreChatPages(chatPagesToken)
        chatPagesToken = saveChatPages(next)
        // 离开 Git 后刷新顶栏与资源管理器入口的分支与改动；离开资源管理器后不再定位目录。
        if (ChatPage.Git in pages && ChatPage.Git !in next) {
            application.gitStatusCache.invalidate(sessionId)
            gitRefresh++
        }
        if (ChatPage.Explorer !in next) workspaceLocatePath = null
    }
    val openChatPage: (ChatPage) -> Unit = { page -> setChatPages(openChatPageRoot(page)) }
    val closeTopChatPage: () -> Unit = { setChatPages(popChatPage(restoreChatPages(chatPagesToken))) }
    val hasGenerationInteraction = interaction?.sessionId == sessionId
    val activeOverlay = if (hasGenerationInteraction) null else restoreChatOverlay(activeOverlayToken)
    var permissionExplanationTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionInFlightTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingNotificationPermission = permissionExplanationTaskId
        ?.let(::NotificationPermissionRequest)
    val scope = rememberCoroutineScope()
    val continueTaskPrompt = stringResource(R.string.chat_task_continue_prompt)
    val queueEditBlockedLabel = stringResource(R.string.chat_queue_edit_blocked)

    val hasBlockingOverlay = activeOverlay != null || hasGenerationInteraction
    val permissionDialogVisible = shouldShowNotificationPermissionDialog(
        NotificationPermissionOverlayState(
            hasPendingRequest = permissionExplanationTaskId != null,
            hasBlockingOverlay = hasBlockingOverlay,
        ),
    )
    val permissionPrefs = remember(context) {
        context.getSharedPreferences(GENERATION_NOTIFICATION_PERMISSION_PREFS, 0)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
        notificationPermissionResultTaskId(
            inFlightTaskId = permissionInFlightTaskId,
            explanationTaskId = permissionExplanationTaskId,
        )?.let { chatViewModel.onNotificationPermissionResult(it, granted) }
        permissionInFlightTaskId = null
        permissionExplanationTaskId = null
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> selectedImageUriStrings = uris.map(Uri::toString) }
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> chatViewModel.importFullContextDocuments(uris) }
    LaunchedEffect(draftConsumptionEpoch) {
        if (draftConsumptionEpoch > 0L) selectedImageUriStrings = emptyList()
    }
    // 已选图片只在界面持有；同步给 ViewModel，编辑排队消息前据此确认输入区为空。
    LaunchedEffect(selectedImageUriStrings) {
        chatViewModel.updateComposerImages(selectedImageUriStrings.map(Uri::parse))
    }
    val restoredDraftImages by chatViewModel.restoredDraftImages.collectAsStateWithLifecycle()
    LaunchedEffect(restoredDraftImages) {
        // 发送被回滚时把图片放回输入框；用户已重新选择图片则保留新的选择。
        restoredDraftImages?.let { uris ->
            if (selectedImageUriStrings.isEmpty()) selectedImageUriStrings = uris
            chatViewModel.consumeRestoredDraftImages()
        }
    }
    val requestOverlay: (ChatOverlay) -> Unit = { requested ->
        val next = enqueueChatOverlay(
            state = ChatOverlayQueueState(
                active = restoreChatOverlay(activeOverlayToken),
                queued = restoreChatOverlay(queuedOverlayToken),
            ),
            requested = requested,
            hasPendingNotificationPermission =
                permissionExplanationTaskId != null || permissionInFlightTaskId != null || hasGenerationInteraction,
        )
        activeOverlayToken = saveChatOverlay(next.active)
        queuedOverlayToken = saveChatOverlay(next.queued)
    }

    LaunchedEffect(
        activeOverlayToken,
        queuedOverlayToken,
        permissionExplanationTaskId,
        permissionInFlightTaskId,
        hasGenerationInteraction,
    ) {
        val next = advanceChatOverlay(
            state = ChatOverlayQueueState(
                active = restoreChatOverlay(activeOverlayToken),
                queued = restoreChatOverlay(queuedOverlayToken),
            ),
            hasPendingNotificationPermission =
                permissionExplanationTaskId != null || permissionInFlightTaskId != null || hasGenerationInteraction,
        )
        activeOverlayToken = saveChatOverlay(next.active)
        queuedOverlayToken = saveChatOverlay(next.queued)
    }

    LaunchedEffect(chatViewModel) {
        chatViewModel.notificationPermissionRequests.collect { request ->
            if (request == null) {
                permissionExplanationTaskId = null
                return@collect
            }
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            val shouldExplain = shouldExplainNotificationPermission(
                NotificationPermissionPromptState(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = granted,
                    alreadyAsked = permissionPrefs.getBoolean(
                        GENERATION_NOTIFICATION_PERMISSION_ASKED,
                        false,
                    ),
                ),
            )
            when (notificationPermissionDisposition(
                requestTaskId = request.taskId,
                granted = granted,
                shouldExplain = shouldExplain,
                inFlightTaskId = permissionInFlightTaskId,
            )) {
                NotificationPermissionDisposition.GRANTED ->
                    chatViewModel.onNotificationPermissionResult(request.taskId, true)
                NotificationPermissionDisposition.SHOW_EXPLANATION ->
                    permissionExplanationTaskId = request.taskId
                NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT -> Unit
                NotificationPermissionDisposition.REJECTED ->
                    chatViewModel.onNotificationPermissionResult(request.taskId, false)
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose(chatViewModel::saveCurrentDraft)
    }
    LaunchedEffect(sessionId) { chatViewModel.loadSession(sessionId) }
    val activity = context as? Activity
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // 回复生成期间始终保持亮屏；触摸锁定后同样只在生成期间常亮，结束后屏幕按系统设置熄灭。
    var touchLocked by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(uiState.isGenerating, activity) {
        if (uiState.isGenerating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val dismissLabel = stringResource(R.string.common_dismiss)
    val copiedLabel = stringResource(R.string.chat_copy_success)
    val branchFailedLabel = stringResource(R.string.chat_branch_failed)
    val branchUnstableLabel = stringResource(R.string.chat_branch_failed_unstable)
    val generationErrorMessage = uiState.generationNotice
        ?.let(GenerationFailureNotice::template)
        ?.let { resolved -> stringResource(resolved.resourceId, *resolved.args.toTypedArray()) }
        ?.let { message ->
            uiState.generationNotice?.detail
                ?.let { message + "\n" + stringResource(R.string.generation_failure_provider_detail, it) }
                ?: message
        }
    LaunchedEffect(
        uiState.generationNotice,
        generationErrorMessage,
        uiState.error,
        permissionDialogVisible,
    ) {
        if (permissionDialogVisible) {
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val message = when {
            uiState.error != null -> uiState.error
            shouldShowGenerationFailureSnackbar(uiState) -> generationErrorMessage
            else -> null
        } ?: return@LaunchedEffect
        snackbarAction = chatViewModel::clearError
        snackbarData = InfinitySnackbarData(message, SnackbarType.ERROR, dismissLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = message,
            actionLabel = dismissLabel,
            duration = SnackbarDuration.Long,
        )
        chatViewModel.clearError()
    }
    LaunchedEffect(
        uiState.backgroundWarning,
        uiState.generationNotice,
        uiState.error,
        permissionDialogVisible,
    ) {
        if (
            permissionDialogVisible ||
            uiState.generationNotice != null ||
            uiState.error != null
        ) return@LaunchedEffect
        val warning = uiState.backgroundWarning ?: return@LaunchedEffect
        snackbarAction = { chatViewModel.clearBackgroundWarning(warning.taskId) }
        snackbarData = InfinitySnackbarData(warning.message, SnackbarType.INFO, dismissLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = warning.message,
            actionLabel = dismissLabel,
            duration = SnackbarDuration.Long,
        )
    }

    // 未选择嵌入模型时检索静默跳过；首次遇到时给一次指向“默认模型”的引导，之后不再提醒。
    val embeddingSetupHintPending by application.embeddingSetupHint.pending.collectAsStateWithLifecycle()
    val embeddingSetupMessage = stringResource(R.string.chat_embedding_setup_hint)
    val embeddingSetupAction = stringResource(R.string.chat_embedding_setup_action)
    LaunchedEffect(
        embeddingSetupHintPending,
        uiState.generationNotice,
        uiState.error,
        permissionDialogVisible,
    ) {
        if (
            !embeddingSetupHintPending ||
            permissionDialogVisible ||
            uiState.generationNotice != null ||
            uiState.error != null
        ) return@LaunchedEffect
        application.embeddingSetupHint.markShown()
        snackbarAction = onOpenDefaultModels
        snackbarData = InfinitySnackbarData(embeddingSetupMessage, SnackbarType.INFO, embeddingSetupAction)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = embeddingSetupMessage,
            actionLabel = embeddingSetupAction,
            duration = SnackbarDuration.Long,
        )
    }

    val undoLabel = stringResource(R.string.chat_btn_undo)
    val deletedLabel = deletionUndo?.let { undo ->
        pluralStringResource(
            if (undo.withMemory) R.plurals.chat_undo_delete_with_memory else R.plurals.chat_undo_delete_count,
            undo.count,
            undo.count,
        )
    }.orEmpty()
    LaunchedEffect(deletionUndo) {
        val undo = deletionUndo ?: return@LaunchedEffect
        snackbarAction = chatViewModel::undoLastDeletion
        snackbarData = InfinitySnackbarData(deletedLabel, SnackbarType.INFO, undoLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.Dismissed) chatViewModel.dismissDeletionUndo(undo.token)
    }
    val taskCompletedLabel = stringResource(R.string.chat_task_marked_completed)
    LaunchedEffect(taskCompletionUndo) {
        val undo = taskCompletionUndo ?: return@LaunchedEffect
        snackbarAction = chatViewModel::undoTaskCompletion
        snackbarData = InfinitySnackbarData(taskCompletedLabel, SnackbarType.INFO, undoLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = taskCompletedLabel,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.Dismissed) chatViewModel.dismissTaskCompletionUndo(undo.token)
    }

    val showCommandFeedback: (String, SnackbarType) -> Unit = { message, type ->
        snackbarAction = null
        snackbarData = InfinitySnackbarData(message, type)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    val sessionExportViewModel = com.promenar.infinity.ui.chat.export.rememberSessionExportViewModel(sessionId)
    val slashCommands = rememberChatSlashCommands(
        sessionId = sessionId,
        chatViewModel = chatViewModel,
        uiState = uiState,
        inputText = inputText,
        isCompressing = compressionState.isCompressing,
        requestOverlay = requestOverlay,
        onNavigateToSession = onNavigateToSession,
        showFeedback = showCommandFeedback,
        sessionExportViewModel = sessionExportViewModel,
    )

    val latestProviderModels by rememberUpdatedState(providerModels)
    val subagentRuns = remember(application, sessionId, onOpenSubagent) {
        SubagentRunsAccess(
            observe = { stepId -> application.subagentRunStore.observe(sessionId, stepId) },
            snapshot = { stepId -> application.subagentRunStore.snapshot(sessionId, stepId) },
            onOpen = { stepId, index -> onOpenSubagent?.invoke(sessionId, stepId, index) },
            modelName = { modelId -> resolveModelDisplayName(modelId, latestProviderModels) },
        )
    }
    // 只在消息或其步骤数变化时重新解析工具结果，流式正文更新不触发。
    val stepsKey = uiState.messages.map { it.id to (it.executionSteps?.size ?: 0) }
    val workspaceChanges = remember(stepsKey) {
        uiState.messages.flatMap { message -> workspaceChangedFiles(message.id, message.executionSteps.orEmpty()) }
    }
    com.promenar.infinity.ui.canvas.CanvasSessionHost(
        sessionId = sessionId,
        store = application.canvasStore,
        artifactRepository = application.sessionArtifacts,
        workspaceChanges = workspaceChanges,
        onInsertText = { text -> chatViewModel.appendCanvasText(sessionId, text) },
        onOpenList = { openChatPage(ChatPage.Canvas) },
        onRevealInWorkspace = { path ->
            workspaceLocatePath = path
            openChatPage(ChatPage.Explorer)
        },
        primaryWorkspaceId = uiState.session?.workspaceId,
    ) {
    // 从系统授权页返回时重新读取精确闹钟权限，授权入口随之消失。
    var exactAlarmGranted by remember { mutableStateOf(com.promenar.infinity.background.schedule.canScheduleExact(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        exactAlarmGranted = com.promenar.infinity.background.schedule.canScheduleExact(context)
        onPauseOrDispose { }
    }
    var jobLogReference by remember(sessionId) { mutableStateOf<String?>(null) }
    jobLogReference?.let { reference ->
        com.promenar.infinity.ui.chat.components.BackgroundJobLogDialog(
            controller = application.backgroundJobController,
            sessionId = sessionId,
            reference = reference,
            onDismiss = { jobLogReference = null },
        )
    }
    val agentActivity = remember(application, sessionId, exactAlarmGranted) {
        com.promenar.infinity.ui.chat.components.AgentActivityAccess(
            sessionId = sessionId,
            jobs = application.backgroundJobController,
            schedules = application.scheduledTaskController,
            keepAliveAvailable = com.promenar.infinity.distribution.DistributionCapabilities.backgroundKeepAliveAvailable,
            exactAlarmAvailable = com.promenar.infinity.distribution.DistributionCapabilities.exactAlarmAvailable,
            onOpenLog = { reference -> jobLogReference = reference },
            onGrantExact = if (exactAlarmGranted) null else {
                com.promenar.infinity.background.schedule.exactAlarmPermissionIntent(context)?.let { intent ->
                    { runCatching { context.startActivity(intent) } }
                }
            },
            liveOutput = { job -> RunningToolLiveOutput(job.liveOutputKey, showStop = false) },
        )
    }
    // 会话顶栏在前台时观察缓存，生成结束后重新订阅；退到后台即停止轮询仓库元数据。
    // 顶栏只看分支与改动数，写入由工具结束与手动刷新失效，不靠 30 秒过期反复启动解释器。
    val gitLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sessionId, boundWorkspaceId, workspaceRefresh, uiState.isGenerating, gitRefresh) {
        if (uiState.isGenerating) return@LaunchedEffect
        gitLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            application.gitStatusCache.observe(sessionId, GIT_HEADER_MAX_AGE_MILLIS).collect { snapshot ->
                snapshot.result?.let { status ->
                    gitSummary = com.promenar.infinity.ui.git.GitEntrySummary.from(status) as? com.promenar.infinity.ui.git.GitEntrySummary.Repository
                }
            }
        }
    }
    // 本会话打开过 Agent 浏览器时才有状态；顶栏入口、检视器页签与浏览器步骤行据此显示。
    val browserIds by application.agentBrowser.sessionIds.collectAsStateWithLifecycle()
    val browserFlow = remember(browserIds, sessionId) {
        if (sessionId in browserIds) application.agentBrowser.state(sessionId) else null
    }
    val browserState by produceState<com.promenar.infinity.data.browser.BrowserUiState?>(null, browserFlow) {
        if (browserFlow == null) value = null else browserFlow.collect { value = it }
    }
    // 远程面板：智能体正在使用的 SSH（运行中的 ssh_exec、交互会话与 SSH 后台命令）与 Tailscale 状态决定页签圆点。
    val liveOutputKeys by application.toolLiveOutputStore.activeKeys.collectAsStateWithLifecycle()
    val recentMessages = uiState.messages.takeLast(REMOTE_USAGE_MESSAGE_WINDOW)
    val remoteExecs = remember(recentMessages, liveOutputKeys, uiState.isGenerating) {
        com.promenar.infinity.ui.remote.runningSshExecs(recentMessages.map { it.executionSteps.orEmpty() }, liveOutputKeys, uiState.isGenerating)
    }
    val sshActivity by application.sshRuntime.activity.collectAsStateWithLifecycle()
    val sessionJobs by remember(application, sessionId) { application.backgroundJobController.observe(sessionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val remoteUsage = com.promenar.infinity.ui.remote.RemoteAgentUsage(
        execs = remoteExecs,
        sessions = sshActivity.agentSessions.filter { it.sessionId == sessionId },
        jobs = com.promenar.infinity.ui.remote.runningSshJobs(sessionJobs, sessionId),
    )
    val tailnetState by application.tailnetManager.state.collectAsStateWithLifecycle()
    val remoteDotState = remoteDot(remoteUsage.active, com.promenar.infinity.ui.remote.tailscaleNeedsAttention(tailnetState.phase))
    val settingsNavigation = LocalSessionSettingsNavigation.current
    // 资源管理器、Git 变更、浏览器与远程的层级：窄窗口整页覆盖会话，宽窗口作为检视器页签的内容。
    val chatPageLevels: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        // 检视器根层由页签与收起按钮离开，不再显示返回。
        val inInspector = com.promenar.infinity.ui.common.LocalInfinityPanePresentation.current ==
            com.promenar.infinity.ui.common.InfinityPanePresentation.Inspector
        // 检视器换页签时按底栏顺序横向过渡；推入、弹出仍是层级过渡。方向在下次换页签前保持不变，退场动画中途不改向。
        var trackedPages by remember { mutableStateOf(shownChatPages) }
        var rootSwitch by remember { mutableStateOf<InspectorRootSwitch?>(null) }
        if (trackedPages != shownChatPages) {
            rootSwitch = inspectorRootSwitch(trackedPages, shownChatPages)
            trackedPages = shownChatPages
        }
        val tabSwitch = rootSwitch.takeIf { inInspector }
        // 按层级顺序叠放；刚弹出的页排在最上，退场动画盖在下层之上。
        chatPageStack(ChatPage.entries, paneInspector)
            .sortedBy { page -> shownChatPages.indexOf(page).let { if (it < 0) Int.MAX_VALUE else it } }
            .forEach { page ->
                androidx.compose.runtime.key(page) {
                    InfinityPaneLevel(
                        key = page.token,
                        visible = page in shownChatPages,
                        top = page == shownChatPages.lastOrNull(),
                        enter = if (tabSwitch != null && page == shownChatPages.firstOrNull()) {
                            com.promenar.infinity.ui.theme.InfinityMotion.sharedAxisXEnter(forward = tabSwitch.forward)
                        } else {
                            com.promenar.infinity.ui.theme.InfinityMotion.sharedAxisXEnter(forward = true)
                        },
                        exit = com.promenar.infinity.ui.theme.InfinityMotion.sharedAxisXExit(
                            forward = tabSwitch?.takeIf { page in it.leaving }?.forward ?: false,
                        ),
                    ) {
                        when (page) {
                            ChatPage.Explorer -> ResourceExplorerPage(
                                onBack = closeTopChatPage.takeUnless { inInspector },
                                // 检视器里直接切到“Git”页签；整页时叠一层带返回的 Git 页。
                                onOpenGit = {
                                    setChatPages(
                                        if (inInspector) openChatPageRoot(ChatPage.Git)
                                        else pushChatPage(restoreChatPages(chatPagesToken), ChatPage.Git),
                                    )
                                },
                                gitRefreshKey = gitRefresh,
                                sessionId = sessionId,
                                chatViewModel = chatViewModel,
                                initialFolderPath = workspaceLocatePath,
                            )
                            ChatPage.Git -> com.promenar.infinity.ui.git.GitChangesPage(
                                sessionId = sessionId,
                                onExit = closeTopChatPage.takeUnless { inInspector && shownChatPages.size == 1 },
                            )
                            ChatPage.Browser -> com.promenar.infinity.ui.browser.BrowserPage(
                                sessionId = sessionId,
                                onBack = closeTopChatPage.takeUnless { inInspector },
                            )
                            ChatPage.Remote -> com.promenar.infinity.ui.remote.RemotePage(
                                sessionId = sessionId,
                                usage = remoteUsage,
                                linuxMode = runModeStatus?.mode == com.promenar.infinity.domain.model.SessionRunMode.LINUX,
                                onBack = closeTopChatPage.takeUnless { inInspector },
                                onOpenSettings = settingsNavigation?.openRemoteSettings,
                                onOpenBackgroundJobs = {
                                    // 窄窗口先收起整页远程面板；宽窗口由会话页把检视器切到“运行”页签。
                                    if (!inInspector) closeTopChatPage()
                                    backgroundJobsRequest++
                                },
                            )
                            ChatPage.Canvas -> com.promenar.infinity.ui.canvas.CanvasListPage(
                                onBack = closeTopChatPage.takeUnless { inInspector },
                            )
                            ChatPage.Run -> Unit
                        }
                    }
                }
            }
    }
    // 窄窗口整页二级页面，生成中待用户处理时让出；宽窗口不覆盖，由会话页的检视器承载。
    com.promenar.infinity.ui.common.InfinityAdaptivePaneHost(
        paneVisible = pageStack.isNotEmpty(),
        onBack = closeTopChatPage,
        coverSuspended = hasGenerationInteraction,
        pane = chatPageLevels,
    ) {
    val hostInspector = com.promenar.infinity.ui.common.LocalInfinityInspectorLayout.current
    SideEffect { paneInspector = hostInspector }
    val branchParentId = uiState.session?.parentSessionId
    val branchParentTitle = remember(branchParentId) {
        branchParentId?.let { id -> application.database.sessionDao().observeById(id).map { it?.title } } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null).value
    val branchPlanJson = uiState.session?.branchPlanJson
    val branchAccess = remember(sessionId, branchParentId, branchParentTitle, branchPlanJson) {
        BranchAccess(
            sessionId = sessionId,
            controls = application.branchControls,
            onOpenSession = onNavigateToSession,
            parentSessionId = branchParentId,
            parentTitle = branchParentTitle,
            branchPlanTitle = branchPlanJson?.let(::branchPlanTitleOf),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatPopoverAnchors provides popoverAnchors,
        LocalToolLiveOutput provides application.toolLiveOutputStore,
        LocalMemoryChangeHost provides rememberMemoryChangeHost(application.longTermMemoryRepository),
        LocalSubagentRuns provides subagentRuns,
        LocalSubagentControls provides application.subagentControls,
        LocalBranchAccess provides branchAccess,
        com.promenar.infinity.ui.chat.components.LocalAgentActivity provides agentActivity,
        LocalChatBrowser provides ChatBrowserAccess(browserState) { openChatPage(ChatPage.Browser) },
        com.promenar.infinity.ui.annotation.LocalImageAnnotationLauncher provides if (imageAnnotationAvailable) {
            { path: String ->
                annotationTarget = com.promenar.infinity.ui.annotation.AnnotationTarget.WorkspaceImage(path)
                annotationReplaceIndex = null
            }
        } else {
            null
        },
    ) {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = uiState,
                inputText = inputText,
                pendingQuestion = pendingQuestion,
                pendingSecretInput = pendingSecretInput,
                secretInputSubmission = secretInputSubmission,
                secretInputReceipts = secretInputReceipts,
                secretOutputCards = secretOutputCards,
                tokenState = tokenState,
                ragPhases = ragPhases,
                compressionState = compressionState,
                postProcessTasks = postProcessTasks,
                generationRetry = generationRetry,
                generationPhase = generationPhase,
                tailnetWait = tailnetWait,
                selectedImageUris = selectedImageUris,
                selectedImageAnnotationKinds = selectedImageUriStrings.map { selectedImageAnnotations[it]?.kind },
                draftDocuments = draftDocuments,
                draftDocumentBudget = draftDocumentBudget,
                draftQuotes = draftQuotes,
                isImportingDocument = isImportingDocument,
                modelDisplayNames = modelDisplayNames,
                reasoningControl = reasoningControl,
                providerDisplayNames = providerDisplayNames,
                taskPanel = taskPanel,
                generationStartedAt = generationProgress?.startedAt,
                collapseFinishedTurns = themePreferences.collapseFinishedTurns,
                turnGraphStates = turnGraphStates,
                composerQueue = composerQueue,
                touchLocked = touchLocked,
                touchLockUnlock = themePreferences.touchLockUnlock,
                slashCommands = slashCommands.commands,
                stateBoard = stateBoard,
                subagentApprovals = sessionSubagentApprovals,
                gitSummary = gitSummary,
                startupWritePaused = startupWritePaused,
                runMode = runModeBadge,
                workspace = workspaceStatus,
                executionMode = uiState.session?.takeIf { it.options.toolsEnabled }?.let { session ->
                    com.promenar.infinity.ui.common.ExecutionMode.valueOf(
                        com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(session.executionMode).name,
                    )
                },
                timedGrants = timedGrants,
                inspectorPages = chatPages,
                browser = browserState,
                remote = remoteDotState,
                backgroundJobsRequest = backgroundJobsRequest,
            ),
            actions = ChatScreenActions(
                onOpenStartupIssues = { showStartupIssues = true },
                onOpenRunMode = {
                    val check = runModeStatus?.check
                    val pending = check?.let { com.promenar.infinity.ui.chat.runmode.initialStage(it) }
                    if (pending != null) runModeCheckpoint = pending else showRunModePicker = true
                },
                onNavigateBack = onNavigateBack,
                onResolveSubagentApproval = { id, approved -> application.subagentApprovalBroker.resolve(id, approved) },
                onOpenWorkspace = { openChatPage(ChatPage.Explorer) },
                onExecutionModeChange = { mode ->
                    chatViewModel.updateExecutionMode(
                        com.promenar.infinity.domain.model.ExecutionModeCodec.serialize(com.promenar.infinity.domain.model.ExecutionMode.valueOf(mode.name)),
                    )
                },
                onOpenWorkspaceStatus = {
                    workspaceSwitchFailed = false
                    pickingWorkspace = true
                },
                onOpenGit = { openChatPage(ChatPage.Git) },
                onOpenBrowser = { openChatPage(ChatPage.Browser) },
                onOpenRemote = { openChatPage(ChatPage.Remote) },
                onSetInspectorPages = setChatPages,
                onInspectorBack = closeTopChatPage,
                onLocateWorkspacePath = { path ->
                    workspaceLocatePath = path
                    openChatPage(ChatPage.Explorer)
                },
                onOpenSettings = { requestOverlay(ChatOverlay.ModelSettings) },
                onOpenModelPicker = {
                    val anchor = popoverAnchors.model
                    if (!anchor.consumeRecentDismiss()) {
                        requestOverlay(if (wideWindow && anchor.bounds != null) ChatOverlay.ModelQuickPick else ChatOverlay.ModelSettings)
                    }
                },
                onOpenPromptEditor = { requestOverlay(ChatOverlay.PromptEditor) },
                onOpenClearDialog = { requestOverlay(ChatOverlay.ClearHistory) },
                onOpenRenameDialog = { requestOverlay(ChatOverlay.RenameSession) },
                onOpenExport = { requestOverlay(ChatOverlay.ExportSession) },
                onOpenDeleteDialog = { requestOverlay(ChatOverlay.DeleteSession) },
                onContentChange = chatViewModel::updateMessageContentOnly,
                onCopy = { text ->
                    resolvedDependencies.copyToClipboard(context, text)
                    snackbarAction = null
                    snackbarData = InfinitySnackbarData(copiedLabel, SnackbarType.SUCCESS)
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(copiedLabel, duration = SnackbarDuration.Short)
                    }
                },
                onDeleteMessage = chatViewModel::deleteMessage,
                onRegenerateMessage = { requestOverlay(ChatOverlay.Truncate(it)) },
                onRetryLastMessage = chatViewModel::retryLastMessage,
                onOpenTailscaleSettings = onOpenTailscaleSettings,
                onOpenTurnMemory = { requestOverlay(ChatOverlay.TurnMemory(it)) },
                onBranchMessage = { messageId ->
                    scope.launch {
                        val result = try {
                            chatViewModel.branchFromMessage(messageId)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            snackbarData = InfinitySnackbarData(branchFailedLabel, SnackbarType.ERROR)
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(branchFailedLabel, duration = SnackbarDuration.Long)
                            return@launch
                        }
                        when (result) {
                            is com.promenar.infinity.data.session.BranchSessionResult.Success ->
                                onNavigateToSession(result.sessionId)
                            is com.promenar.infinity.data.session.BranchSessionResult.Rejected -> {
                                val message = if (
                                    result.reason == com.promenar.infinity.data.session.BranchSessionRejectReason.TargetNotStable
                                ) branchUnstableLabel else branchFailedLabel
                                snackbarData = InfinitySnackbarData(message, SnackbarType.ERROR)
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    message = message,
                                    duration = SnackbarDuration.Long,
                                )
                            }
                        }
                    }
                },
                onApprove = { request -> chatViewModel.approveRequest(request) },
                onApproveAll = chatViewModel::approveAllRequests,
                onApproveTimed = chatViewModel::approveWithTimedGrant,
                onRevokeTimedGrants = chatViewModel::revokeTimedGrants,
                onDecline = chatViewModel::rejectRequest,
                onRemovePostProcessTask = chatViewModel::removePostProcessTask,
                onManualSummary = chatViewModel::compactNow,
                onReasoningEffortChange = chatViewModel::updateReasoningEffort,
                onDismissCompressionResult = chatViewModel::dismissCompressionResult,
                onTextChange = chatViewModel::updateInputText,
                onSend = { text, images ->
                    slashCommands.submit(text) { chatViewModel.sendMessage(text, images) }
                },
                onStop = chatViewModel::stopGeneration,
                onDismissGenerationError = chatViewModel::acknowledgeGenerationFailure,
                onPickImages = { imagePickerLauncher.launch("image/*") },
                onPickDocuments = {
                    documentPickerLauncher.launch(
                        arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"),
                    )
                },
                onRemoveDocument = chatViewModel::removeDraftDocument,
                onSaveDraftDocumentToWorkspace = { id -> chatViewModel.saveDraftDocumentToWorkspace(id) },
                onQuote = chatViewModel::addQuote,
                onQuoteAnnotationChange = chatViewModel::updateQuoteAnnotation,
                onRemoveQuote = chatViewModel::removeQuote,
                onEditImage = openComposerImageEditor.takeIf { imageAnnotationAvailable },
                onSketch = if (imageAnnotationAvailable) {
                    {
                        annotationTarget = com.promenar.infinity.ui.annotation.AnnotationTarget.Sketch()
                        annotationReplaceIndex = null
                    }
                } else {
                    null
                },
                onRemoveImage = { index ->
                    selectedImageUriStrings = selectedImageUriStrings.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
                },
                onSnackbarAction = {
                    snackbarAction?.invoke()
                    snackbarHostState.currentSnackbarData?.dismiss()
                },
                onContinueTask = {
                    if (!uiState.isGenerating) {
                        chatViewModel.sendMessage(continueTaskPrompt)
                    }
                },
                onCompleteTask = chatViewModel::completeActivePlan,
                onClearCompletedTask = chatViewModel::clearCompletedPlan,
                onEditState = chatViewModel::editStateField,
                onRemoveState = chatViewModel::removeStateBoard,
                onSteerQueued = chatViewModel::steerQueuedMessage,
                onSendSteering = { text, images ->
                    slashCommands.submit(text) { chatViewModel.sendMessageSteering(text, images) }
                },
                onStopAndSend = { text, images ->
                    slashCommands.submit(text) { chatViewModel.stopAndSendNow(text, images) }
                },
                onAnswerQuestion = { question, answer -> chatViewModel.answerQuestion(question, answer) },
                onSubmitSecretInput = chatViewModel::submitSecretInput,
                onCancelSecretInput = chatViewModel::cancelSecretInput,
                onGitHubBrowserLogin = { showSecretGitHubLogin = true },
                secretOutputActions = { card ->
                    SecretOutputActions(
                        onReveal = { chatViewModel.revealSecret(card) },
                        onSaveAs = { destination, username, onResult -> chatViewModel.saveSecretAs(card, destination, username, onResult) },
                        onDestroy = { chatViewModel.destroySecret(card) },
                        loadSaveTargets = chatViewModel::secretSaveTargets,
                    )
                },
                onSaveComposerGitToken = chatViewModel::saveComposerGitToken,
                loadSecretSaveTargets = chatViewModel::secretSaveTargets,
                onEditQueued = { id ->
                    if (!chatViewModel.editQueuedMessage(id)) {
                        snackbarAction = null
                        snackbarData = InfinitySnackbarData(queueEditBlockedLabel, SnackbarType.INFO)
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(queueEditBlockedLabel, duration = SnackbarDuration.Short)
                        }
                    }
                },
                onMoveQueuedToFront = chatViewModel::moveQueuedMessageToFront,
                onRemoveQueued = chatViewModel::removeQueuedMessage,
                onResumeQueue = chatViewModel::resumeComposerQueue,
                onLockTouch = { touchLocked = true },
                onUnlockTouch = { touchLocked = false },
                onSlashCommandPicked = slashCommands.onPick,
                onSlashPaletteShown = slashCommands.onPaletteShown,
            ),
            snackbarHostState = snackbarHostState,
            snackbarData = snackbarData,
            inspectorPageContent = chatPageLevels,
        )
    }
    }
    }

    annotationTarget?.let { target ->
        com.promenar.infinity.ui.annotation.ImageAnnotationEditor(
            target = target,
            readWorkspaceImage = chatViewModel::readAnnotationSourceImage,
            onExport = { export ->
                val uri = chatViewModel.saveImageAnnotation(export).toString()
                val replace = annotationReplaceIndex
                selectedImageUriStrings = selectedImageUriStrings.toMutableList().apply {
                    if (replace != null && replace in indices) set(replace, uri) else add(uri)
                }
                annotationTarget = null
                annotationReplaceIndex = null
            },
            onDismiss = {
                annotationTarget = null
                annotationReplaceIndex = null
            },
        )
    }

    pendingNotificationPermission?.takeIf { permissionDialogVisible }?.let { request ->
        InfinityConfirmDialog(
            modifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_DIALOG),
            onDismissRequest = {
                permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                chatViewModel.onNotificationPermissionResult(request.taskId, false)
                permissionExplanationTaskId = null
            },
            title = stringResource(R.string.generation_notification_permission_title),
            message = stringResource(R.string.generation_notification_permission_explanation),
            confirmText = stringResource(R.string.generation_notification_permission_continue),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                permissionInFlightTaskId = request.taskId
                permissionExplanationTaskId = null
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onCancel = {
                permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                chatViewModel.onNotificationPermissionResult(request.taskId, false)
                permissionExplanationTaskId = null
            },
            confirmButtonModifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_CONTINUE),
            cancelButtonModifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_FOREGROUND_ONLY),
        )
    }
    if (activeOverlay == ChatOverlay.ClearHistory) {
        InfinityConfirmDialog(
            onDismissRequest = { activeOverlayToken = null },
            title = stringResource(R.string.chat_dialog_clear_history_title),
            message = stringResource(R.string.chat_dialog_clear_history_msg),
            confirmText = stringResource(R.string.common_btn_confirm),
            onConfirm = { chatViewModel.clearHistory(); activeOverlayToken = null },
            onCancel = { activeOverlayToken = null },
        )
    }
    if (activeOverlay == ChatOverlay.DeleteSession) {
        val deleteContext = LocalContext.current
        com.promenar.infinity.ui.workspace.DeleteSessionDialog(
            sessionId = sessionId,
            loadSummary = (LocalContext.current.applicationContext as? InfinityApplication)?.let { app -> app::privateWorkspaceSummary },
            onConfirm = { keep, removeWorktree ->
                activeOverlayToken = null
                if (removeWorktree) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            com.promenar.infinity.ui.workspace.removeBranchWorktree(deleteContext, application.branchControls, sessionId)
                        }
                        chatViewModel.deleteSession(onNavigateBack, keepFiles = keep)
                    }
                } else {
                    chatViewModel.deleteSession(onNavigateBack, keepFiles = keep)
                }
            },
            onCancel = { activeOverlayToken = null },
            loadWorktreeBranch = com.promenar.infinity.ui.workspace.worktreeBranchLoader(application),
        )
    }
    // 导出面板始终在场：面板关闭时也负责 /export <格式> 的保存与分享流程。
    com.promenar.infinity.ui.chat.export.SessionExportHost(
        viewModel = sessionExportViewModel,
        show = activeOverlay == ChatOverlay.ExportSession,
        sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new),
        messageCount = uiState.messages.count {
            it.role == com.promenar.infinity.data.model.MessageRole.USER || it.role == com.promenar.infinity.data.model.MessageRole.ASSISTANT
        },
        onDismiss = { if (activeOverlay == ChatOverlay.ExportSession) activeOverlayToken = null },
        showFeedback = showCommandFeedback,
    )
    if (activeOverlay == ChatOverlay.RenameSession) {
        RenameDialog(
            currentName = uiState.session?.title ?: stringResource(R.string.chat_title_new),
            onDismiss = { activeOverlayToken = null },
            onConfirm = { chatViewModel.renameSession(it); activeOverlayToken = null },
        )
    }
    (activeOverlay as? ChatOverlay.TurnMemory)?.let { overlay ->
        // 状态随消息实时更新；消息被删除或状态被清空时关闭面板。进程重建后会话尚未载入时保持打开。
        val session = uiState.session
        val message = session?.messages?.firstOrNull { it.id == overlay.messageId }
        val state = message?.turnMemory
        val graph = message?.let { turnGraphStates[it.id] }
        if (state == null && graph == null) {
            if (session != null) LaunchedEffect(overlay) { activeOverlayToken = null }
        } else {
            TurnMemorySheet(
                state = state,
                loadDetails = { chatViewModel.loadTurnMemory(overlay.messageId) },
                onRemove = { chatViewModel.removeTurnMemory(overlay.messageId) },
                onRewrite = { chatViewModel.rewriteTurnMemory(overlay.messageId) },
                onDismiss = { activeOverlayToken = null },
                graph = graph,
                loadGraph = { chatViewModel.loadTurnGraph(overlay.messageId) },
                onRetryGraph = { chatViewModel.retryTurnGraph(overlay.messageId) },
                onOpenGraph = onOpenKnowledgeGraph?.let { open ->
                    { query: String -> activeOverlayToken = null; open(query) }
                },
                popover = InfinityPopoverSpec(popoverAnchors.turnMemory, InfinityPopoverSide.Above, InfinityPopoverAlign.Auto, TurnMemoryPopoverWidth),
            )
        }
    }
    (activeOverlay as? ChatOverlay.Truncate)?.let { truncate ->
        InfinityConfirmDialog(
            onDismissRequest = { activeOverlayToken = null },
            title = stringResource(R.string.chat_confirm_truncate_title),
            message = stringResource(R.string.chat_confirm_truncate_message),
            confirmText = stringResource(R.string.common_btn_confirm),
            onConfirm = {
                chatViewModel.regenerateMessage(truncate.messageId)
                activeOverlayToken = null
            },
            onCancel = { activeOverlayToken = null },
            isDestructive = true,
        )
    }
    if (showStartupIssues) {
        com.promenar.infinity.ui.startup.StartupIssuesSheet(
            onDismiss = { showStartupIssues = false },
            diagnosisAgentId = { uiState.session?.agentId },
            onOpenSession = onNavigateToSession,
        )
    }
    val runModeSwitchFailed = stringResource(R.string.run_mode_switch_failed)
    com.promenar.infinity.ui.chat.runmode.RunModePickerSheet(
        show = showRunModePicker,
        selected = runModeStatus?.mode,
        linuxSupported = linuxSupported,
        onDismiss = { showRunModePicker = false },
        popover = InfinityPopoverSpec(popoverAnchors.runMode, InfinityPopoverSide.Below, InfinityPopoverAlign.Auto, RunModePopoverWidth),
        onSelect = { mode ->
            runModeScope.launch {
                try {
                    runModeCheckpoint = com.promenar.infinity.ui.chat.runmode.switchRunMode(application, sessionId, mode)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, runModeSwitchFailed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
    // 顶栏状态行的工作区打开选择器；选择器顶部链接打开可达范围面板，叠在选择器之上。
    // 生成进行中或有待审批时选择器照常打开，说明原因并只保留当前工作区可点。切换失败时选择器保持打开并提示。
    val closePickerAfter: (com.promenar.infinity.data.workspace.WorkspaceSwitchResult) -> Unit = { result ->
        onWorkspaceSwitched(result)
        if (result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched ||
            result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Unchanged
        ) pickingWorkspace = false
    }
    com.promenar.infinity.ui.workspace.WorkspacePickerSheet(
        show = pickingWorkspace,
        onDismiss = { pickingWorkspace = false },
        currentWorkspaceId = uiState.session?.workspaceId,
        runMode = runModeStatus?.mode,
        onPicked = { picked ->
            workspaceSwitchFailed = false
            if (picked.id == uiState.session?.workspaceId) pickingWorkspace = false
            else chatViewModel.switchSessionWorkspace(picked.id, closePickerAfter)
        },
        onPickPrivate = {
            workspaceSwitchFailed = false
            if (uiState.session?.workspaceId == null) pickingWorkspace = false
            else chatViewModel.switchSessionWorkspace(null, closePickerAfter)
        },
        switchStatus = com.promenar.infinity.ui.workspace.WorkspaceSwitchStatus(
            executionMode = com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(uiState.session?.executionMode),
            busy = workspaceBusy,
            failed = workspaceSwitchFailed,
            onOpenReach = { showAgentReach = true },
            // 与输入区停止按钮同一入口；只在本会话确有生成进行时提供，待审批造成的停用不显示。
            onStopGeneration = if (uiState.isGenerating) chatViewModel::stopGeneration else null,
        ),
    )
    if (showAgentReach) {
        com.promenar.infinity.ui.workspace.AgentReachSheet(
            sessionId = sessionId,
            executionMode = uiState.session?.executionMode,
            onDismiss = { showAgentReach = false },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
            workspaceKey = boundWorkspaceId to workspaceRefresh,
        )
    }
    runModeCheckpoint?.let { stage ->
        com.promenar.infinity.ui.chat.runmode.RunModeCheckpointSheet(
            application = application,
            sessionId = sessionId,
            initial = stage,
            workspaceId = uiState.session?.workspaceId,
            onDismiss = { runModeCheckpoint = null },
        )
    }
    // 模型速选只在宽窗口且量到模型按钮时贴着按钮弹出；否则（例如折叠后变窄）落到会话设置的“模型”页。
    val quickPickPopover = activeOverlay == ChatOverlay.ModelQuickPick && wideWindow && popoverAnchors.model.bounds != null
    if (quickPickPopover) {
        ModelQuickPickPopover(
            anchor = popoverAnchors.model,
            chatViewModel = chatViewModel,
            onDismiss = { activeOverlayToken = null },
            onMoreSettings = { activeOverlayToken = saveChatOverlay(ChatOverlay.SessionParams) },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
        )
    }
    SessionSettingsSheet(
        show = activeOverlay == ChatOverlay.ModelSettings || activeOverlay == ChatOverlay.SessionTools ||
            activeOverlay == ChatOverlay.SessionParams || (activeOverlay == ChatOverlay.ModelQuickPick && !quickPickPopover),
        onDismiss = { activeOverlayToken = null },
        chatViewModel = chatViewModel,
        onOpenTailscaleSettings = onOpenTailscaleSettings,
        initialPage = when (activeOverlay) {
            ChatOverlay.SessionTools -> 2
            ChatOverlay.SessionParams -> 1
            else -> 0
        },
    )
    if (showSecretGitHubLogin) {
        SecretGitHubLoginHost(
            onLoggedIn = chatViewModel::completeSecretInputWithGitHubLogin,
            onClose = { showSecretGitHubLogin = false },
        )
    }
    UnifiedPromptEditor(
        show = activeOverlay == ChatOverlay.PromptEditor,
        onDismiss = { activeOverlayToken = null },
        onSave = chatViewModel::saveCustomPrompt,
        initialText = uiState.session?.customPrompt.orEmpty(),
        title = stringResource(R.string.chat_session_prompt_title),
        placeholder = stringResource(R.string.chat_session_prompt_placeholder),
        mode = EditorMode.DIALOG,
    )
}

private val TurnMemoryPopoverWidth = 340.dp
private val RunModePopoverWidth = 300.dp

/** 兼容现有调用；新导航使用 [ChatRoute]。 */
@Composable
fun ChatScreen(sessionId: String, onNavigateBack: () -> Unit = {}) =
    ChatRoute(sessionId, onNavigateBack)

/** 会话顶栏的 Git 状态最长复用时间：仓库元数据变化与工具写入另行失效。 */
private const val GIT_HEADER_MAX_AGE_MILLIS = 5 * 60_000L
