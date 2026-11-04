package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.providerDisplayName
import android.app.Application
import android.net.Uri
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationExport
import com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec
import com.promenar.infinity.data.media.annotation.ImageAnnotationStore
import com.promenar.infinity.data.media.annotation.ImageAnnotationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.chat.queue.ComposerQuoteDraft
import com.promenar.infinity.data.chat.queue.ComposerQuotes
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.PostProcessTask
import com.promenar.infinity.data.model.PostProcessType
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.params.sampling
import com.promenar.infinity.R
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.UpdateMessageOptions
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagPhase
import com.promenar.infinity.data.model.RagProgress
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.document.DocumentReadRejectReason
import com.promenar.infinity.data.document.DocumentReadResult
import com.promenar.infinity.data.document.FullContextDocumentReader
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.model.PhaseStatus
import com.promenar.infinity.data.model.findModelSpec
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.domain.usecase.ExportSessionUseCase
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.domain.usecase.resolvedKeepRecentTurns
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.data.model.turnMemory
import com.promenar.infinity.data.rag.ConversationReachStats
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.RetrievalScopeResolver
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.rag.MemoryManagerRagAdapter
import com.promenar.infinity.data.rag.RecursiveCharacterTextSplitter
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ImageInput
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.generation.ChatGenerationRunner
import com.promenar.infinity.data.generation.ChatGenerationRuntime
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.session.BranchSessionResult
import com.promenar.infinity.data.session.BranchSessionUseCase
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.ui.chat.manager.ApprovalManager
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import com.promenar.infinity.ui.chat.manager.KgProvider
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.messageRemovalIds
import com.promenar.infinity.ui.chat.manager.PostProcessor
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.data.chat.queue.ComposerQueueStore
import com.promenar.infinity.data.chat.queue.QueuePauseReason
import com.promenar.infinity.data.chat.queue.QueuedMessage
import com.promenar.infinity.domain.generation.GenerationPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class GenerationStatus {
    IDLE,
    UPLOADING,
    THINKING,
    RECEIVING,
    COMPLETED,
    ERROR
}

internal fun mergeToolCallsById(
    existing: List<ToolCall>,
    incoming: List<ToolCall>,
): List<ToolCall> {
    val merged = linkedMapOf<String, ToolCall>()
    existing.forEach { call -> merged.putIfAbsent(call.id, call) }
    incoming.forEach { call ->
        val previous = merged[call.id]
        merged[call.id] = if (previous == null) {
            call
        } else {
            previous.copy(
                name = call.name.ifBlank { previous.name },
                arguments = call.arguments.ifBlank { previous.arguments },
            )
        }
    }
    return merged.values.toList()
}

internal fun stableFallbackToolCallId(
    prefix: String,
    name: String,
    arguments: String,
    index: Int,
): String {
    val raw = "$prefix\u0000$name\u0000$arguments\u0000$index"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return "${prefix}_" + digest.take(16).joinToString("") { "%02x".format(it) }
}

internal suspend fun resolveChatApprovalExecutionTarget(
    session: Session,
    assistantMessageId: String,
    identity: com.promenar.infinity.data.model.ApprovalCallIdentity,
    toolLookup: (String) -> com.promenar.infinity.data.remote.protocol.ProtocolTool?,
    workspaceRepository: com.promenar.infinity.domain.repository.IWorkspaceRepository?,
    sshSessionHost: (String, String) -> String?,
    mounts: com.promenar.infinity.domain.storage.ExternalWorkspaceAccess? = null,
    sshHostStore: com.promenar.infinity.data.ssh.SshHostStore? = null,
    linuxFiles: com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess? = null,
    sessionCommunicationTarget: (suspend (Session, String, String) -> String?)? = null,
    knowledgeAccessTarget: (suspend (Session, String, String) -> String?)? = null,
): String? {
    val call = session.messages.firstOrNull { it.id == assistantMessageId }?.toolCalls.orEmpty()
        .firstOrNull { it.id == identity.toolCallId && it.name == identity.toolName } ?: return null
    val tool = toolLookup(identity.runtimeToolId) ?: return null
    return knowledgeAccessTarget?.invoke(session, call.name, call.arguments) ?: sessionCommunicationTarget?.invoke(session, call.name, call.arguments) ?: com.promenar.infinity.data.generation.resolveSessionToolTarget(
        session, tool, call.arguments, workspaceRepository, mounts, sshHostStore, sshSessionHost, linuxFiles,
    )
}

data class ChatUiState(
    val session: Session? = null,
    val agentName: String = "",
    val messages: List<Message> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val status: GenerationStatus = GenerationStatus.IDLE,
    val streamingContent: String = "",
    val error: String? = null,
    val generationNotice: UiStatusNotice? = null,
    val generationFailureSurface: com.promenar.infinity.data.generation.GenerationFailureSurface? = null,
    val backgroundWarning: BackgroundGenerationWarning? = null,
    val approvalRequest: ApprovalRequest? = null,
    val isApprovalSubmitting: Boolean = false,
)

enum class BackgroundWarningKind { FGS_UNAVAILABLE, NOTIFICATIONS_UNAVAILABLE }
/**
 * 删除后的撤销提示：[count] 为被删除的气泡数（用户消息与整轮回复各计一条），
 * [withMemory] 表示其中有已记入记忆的回复，提示需说明记忆一并删除。
 */
data class DeletionUndo(val token: String, val count: Int, val withMemory: Boolean = false)

/** “本轮记忆”面板的内容：本轮写入的片段，以及决定其他会话能否检索到它的设置。 */
data class TurnMemoryDetails(
    val chunks: List<String>,
    val shareWithOtherAgents: Boolean,
)

/** 用户在待办面板上“标记完成”后可撤销的记录；[previousStatuses] 为各步骤原状态。 */
data class TaskCompletionUndo(
    val token: String,
    val sessionId: String,
    val previousStatuses: Map<String, String>,
)

data class BackgroundGenerationWarning(
    val taskId: String,
    val kind: BackgroundWarningKind,
    val message: String,
)
data class BackgroundServiceStoppedError(val taskId: String, val message: String)
data class NotificationPermissionRequest(val taskId: String)

/**
 * 等待通知权限裁决的待发送轮次：仅当 [BackgroundGenerationPolicy.REQUIRES_PERMISSION] 时暂存，
 * 取得系统权限结果后再按授权/拒绝恢复为后台或仅前台生成。
 */
internal data class PendingPermissionTurn(
    val token: String,
    val sessionId: String,
    val text: String,
    val imageDataUrls: List<String>,
    val documents: List<MessageDocumentAttachment>,
    val assistantMessageIdsToReplace: Set<String> = emptySet(),
    val reuseUserMessageId: String? = null,
    val quotes: List<MessageQuote> = emptyList(),
    val imageAnnotations: List<ImageAnnotation?> = emptyList(),
    val imageAnnotationContext: ImageAnnotationContext? = null,
)

internal fun ContextBuilderResult.hasPersistableRagContext(): Boolean =
    ragContext.isNotBlank() || ragReferences.isNotEmpty() || citations.isNotEmpty() ||
        kgPaths.isNotEmpty() || ragUsage != null || ragTrace != null

private fun DocumentReadRejectReason.userMessage(resolve: (Int) -> String): String = when (this) {
    DocumentReadRejectReason.UnsupportedDocument -> resolve(R.string.chat_document_error_unsupported)
    DocumentReadRejectReason.EmptyDocument -> resolve(R.string.chat_document_error_empty)
    DocumentReadRejectReason.DocumentTooLarge -> resolve(R.string.chat_document_error_too_large)
    DocumentReadRejectReason.BinaryContent -> resolve(R.string.chat_document_error_binary)
    DocumentReadRejectReason.InvalidTextEncoding -> resolve(R.string.chat_document_error_encoding)
    DocumentReadRejectReason.ReadFailed -> resolve(R.string.chat_document_error_read)
}

internal fun ContextBuilderResult.toMessageRagUpdateOptions(): UpdateMessageOptions? {
    if (ragReferences.isEmpty() && citations.isEmpty() && kgPaths.isEmpty() && ragTrace == null) return null
    // 检索未命中也要留下记录，回复上才能显示“已检索但未命中”而不是什么都不显示。
    val hasReferenceMetadata = ragReferences.isNotEmpty() || citations.isNotEmpty() || ragTrace != null
    return UpdateMessageOptions(
        ragReferences = ragReferences.ifEmpty { null },
        citations = citations.ifEmpty { null },
        kgPaths = kgPaths.ifEmpty { null },
        ragMetadata = if (hasReferenceMetadata) {
            RagMetadata(
                chunkCount = ragReferences.size,
                totalTokens = ragUsage?.ragSystem ?: 0,
                retrievalTimeMs = ragTrace?.searchTimeMs ?: 0,
                trace = ragTrace,
            )
        } else null,
    )
}

class ChatViewModel(
    private val application: Application,
    private val sessionRepository: ISessionRepository,
    private val messageRepository: IMessageRepository,
    private val agentRepository: IAgentRepository,
    private val llmProvider: LlmProvider,
    providerRequestRouter: ProviderRequestRouter,
    private val configResolver: AgentConfigResolver,
    providerResolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localLlmProviderFactory: (String) -> LlmProvider = { modelId ->
        (application as InfinityApplication).generationLocalProviders.create(modelId)
    },
    private val embeddingClient: EmbeddingClient? = null,
    private val vectorStore: VectorStore? = null,
    private val textSplitter: RecursiveCharacterTextSplitter? = null,
    private val memoryManager: MemoryManager? = null,
    private val kgProvider: KgProvider? = null,
    private val skillRegistry: com.promenar.infinity.ui.chat.manager.registry.SkillRegistry? = null,
    private val sessionToolResolver: com.promenar.infinity.data.generation.SessionToolResolver =
        (application as InfinityApplication).sessionToolResolver,
    private val exportSessionUseCase: ExportSessionUseCase? = null,
    private val fullContextDocumentReader: FullContextDocumentReader =
        FullContextDocumentReader(application.contentResolver),
    private val branchSessionUseCase: BranchSessionUseCase? = null,
    generationCoordinatorOverride: com.promenar.infinity.domain.generation.GenerationCoordinator? = null,
    generationPresentationStoreOverride: com.promenar.infinity.data.generation.GenerationPresentationStore? = null,
    generationForegroundControllerOverride:
        com.promenar.infinity.background.generation.GenerationForegroundController? = null,
    notificationPermissionGateway:
        com.promenar.infinity.background.generation.NotificationPermissionGateway? = null,
    stringResourceResolverOverride: ((Int) -> String)? = null,
    sessionWorkspaceRootOverride: (suspend (String) -> Pair<String, String>)? = null,
    private val sshOwnerCloser: suspend (String) -> Unit = { owner ->
        (application as InfinityApplication).sshRuntime.closeOwner(owner)
    },
    private val sshOwnerPauser: suspend (String) -> Unit = { owner ->
        (application as InfinityApplication).sshRuntime.pauseOwner(owner)
    },
    private val sshOwnerResumer: suspend (String) -> Unit = { owner ->
        (application as InfinityApplication).sshRuntime.resumeOwner(owner)
    },
    private val sshCleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val contextCompactionService: com.promenar.infinity.data.generation.ContextCompactionService =
        (application as InfinityApplication).contextCompactionService,
    private val contextUsageEstimator: suspend (String) -> com.promenar.infinity.data.generation.ContextUsageEstimate =
        { sessionId -> (application as InfinityApplication).estimateContextUsage(sessionId) },
    /** 工作区登记查询；为 null 时读取应用数据库。 */
    private val workspaceLookupOverride:
        (suspend (String) -> com.promenar.infinity.data.local.db.entity.WorkspaceEntity?)? = null,
    /** 切换提示文案；为 null 时按应用资源本地化。 */
    private val workspaceSwitchNoticeTextOverride: ((
        com.promenar.infinity.data.workspace.WorkspaceLabel,
        com.promenar.infinity.data.workspace.WorkspaceLabel,
    ) -> String)? = null,
    /** 会话图谱抽取状态；为 null 时读取应用实例。 */
    private val sessionGraphPortOverride: com.promenar.infinity.data.rag.SessionGraphPort? = null,
    /** 参数方案；为 null 时读取应用实例。 */
    private val parameterSchemeRepositoryOverride: com.promenar.infinity.data.repository.ParameterSchemeRepository? = null,
    composerQueueStoreOverride: ComposerQueueStore? = null,
    composerDraftRecoveryOverride: com.promenar.infinity.data.chat.queue.ComposerDraftRecoveryStore? = null,
    /** 保密输入与输出的界面入口；默认取应用实例上的实现。 */
    secretInputCoordinator: com.promenar.infinity.domain.secret.SecretInputCoordinator =
        (application as InfinityApplication).secretInputCoordinator,
    secretOutputController: com.promenar.infinity.domain.secret.SecretOutputController =
        (application as InfinityApplication).secretOutputController,
    private val draftDocumentBudgetEstimator: suspend (String, String, List<MessageDocumentAttachment>) -> com.promenar.infinity.data.generation.DraftDocumentBudget =
        { id, text, documents -> (application as InfinityApplication).estimateDraftDocumentBudget(id, text, documents) },
    private val documentWorkspaceSaver: com.promenar.infinity.data.document.DocumentWorkspaceSaver =
        (application as InfinityApplication).documentWorkspaceSaver,
) : ViewModel() {

    private val store = (application as InfinityApplication).chatStore
    private val composerQueueStore = composerQueueStoreOverride ?: (application as InfinityApplication).composerQueueStore
    private val composerDraftRecovery = composerDraftRecoveryOverride ?: (application as InfinityApplication).composerDraftRecovery
    private val _composerQueue = MutableStateFlow(ComposerQueueUiState())
    val composerQueue: StateFlow<ComposerQueueUiState> = _composerQueue
    private val queueMutexes = ConcurrentHashMap<String, Mutex>()
    private fun queueMutex(sessionId: String) = queueMutexes.getOrPut(sessionId) { Mutex() }
    private val queueSubmissions = mutableMapOf<String, QueuedMessage>()
    private val queueUserMessageIds = mutableMapOf<String, String>()
    private val pendingQueueWrites = mutableMapOf<String, Int>()
    private val queueTerminalPhases = mutableMapOf<String, GenerationPhase>()
    private val queuePauseOverrides = mutableMapOf<String, QueuePauseReason>()
    /** “停止并立即发送”的会话：本轮以取消收尾时不暂停队列，直接发出排在队首的草稿。 */
    private val stopAndSendSessions = ConcurrentHashMap.newKeySet<String>()
    private val preparingSessions = ConcurrentHashMap.newKeySet<String>()
    private val annotationSavingSessions = ConcurrentHashMap.newKeySet<String>()
    private val queueDispatchJobs = ConcurrentHashMap<String, Job>()
    private val queueDispatchRequested = ConcurrentHashMap.newKeySet<String>()
    private val draftRecoveryRetries = ConcurrentHashMap.newKeySet<String>()
    private var editingQueueItem = false
    private var composerImages: List<Uri> = emptyList()
    private val generationCoordinator = generationCoordinatorOverride
        ?: (application as InfinityApplication).generationCoordinator
    private val generationPresentationStore = generationPresentationStoreOverride
        ?: (application as InfinityApplication).generationPresentationStore
    private val generationForegroundController = generationForegroundControllerOverride
        ?: (application as InfinityApplication).generationForegroundController
    private val notificationPermissionGateway = notificationPermissionGateway
    private val stringResourceResolver = stringResourceResolverOverride ?: application::getString
    private val sessionWorkspaceRoot: suspend (String) -> Pair<String, String> =
        sessionWorkspaceRootOverride ?: { sessionId: String ->
            val root = (application as InfinityApplication).workspaceRepository.ensureSessionRoot(sessionId)
            root.uuid to root.physicalRootPath
        }

    private val sessionManager = SessionManager(
        store,
        sessionRepository,
        { id: String, keepFiles: Boolean -> (application as InfinityApplication).deleteSessionRecoverably(id, keepFiles) },
    )
    private val appContext = application
    private val directoryBusyRegistration = (application as? InfinityApplication)
        ?.registerDirectoryWorkspaceBusyCheck { id -> id == _currentSessionId.value && isWorkspaceSwitchBlocked() }
    val availableSessionMcpServers = (application as InfinityApplication).skillRepository
        .getAllMcpServers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val messageManager = MessageManager(
        store,
        messageRepository,
        sessionRepository,
        memoryLifecycle = (application as InfinityApplication).conversationMemoryLifecycle,
        stateBoardRepository = (application as InfinityApplication).stateBoardRepository,
        isSessionGenerating = generationCoordinator::isSessionBusy,
    )
    private val toolLedger = (application as InfinityApplication).toolExecutionLedger
    private val toolExecutor = ToolExecutor(
        store,
        messageManager,
        skillRegistry,
        sessionToolResolver,
        (application as InfinityApplication).taskRepository,
        toolLedger,
        (application as InfinityApplication).sessionExecutionGate,
        grantVerifier = (application as InfinityApplication)::verifyTargetGrant,
        liveOutput = (application as InfinityApplication).toolLiveOutputStore,
        afterTools = (application as InfinityApplication)::reconcileBranchWorkspace,
    )
    private val taskPlanActions = TaskPlanActions((application as InfinityApplication).taskRepository)
    private val approvalManager = ApprovalManager(
        store,
        toolLedger,
        messageManager,
        sessionRepository,
        (application as InfinityApplication).sessionExecutionGate,
        (application as InfinityApplication).toolAllowlistStore,
        resolveExecutionTarget = { session, assistantMessageId, identity ->
            resolveChatApprovalExecutionTarget(
                session, assistantMessageId, identity,
                toolLookup = { runtimeId -> skillRegistry?.getSkillByRuntimeToolId(runtimeId)?.toProtocolTool() },
                workspaceRepository = (application as InfinityApplication).workspaceRepository,
                sshSessionHost = (application as InfinityApplication).sshRuntime::sessionHost,
                mounts = (application as InfinityApplication).externalFolderManager,
                sshHostStore = (application as InfinityApplication).sshHostStore,
                linuxFiles = (application as InfinityApplication).linuxSessionFiles,
                sessionCommunicationTarget = (application as InfinityApplication)::sessionCommunicationApprovalTarget,
                knowledgeAccessTarget = (application as InfinityApplication)::knowledgeAccessApprovalTarget,
            )
        },
        resolveAllowlistScope = (application as InfinityApplication)::resolveToolAllowlistScope,
        timedGrants = (application as InfinityApplication).timedApprovalGrants,
    )

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText
    private val _draftDocuments = MutableStateFlow<List<MessageDocumentAttachment>>(emptyList())
    val draftDocuments: StateFlow<List<MessageDocumentAttachment>> = _draftDocuments
    private val _draftDocumentBudget = MutableStateFlow<com.promenar.infinity.data.generation.DraftDocumentBudget?>(null)
    /** null 表示没有会话或预算暂不可用；三种有效状态由 DraftBudgetStatus 表达。 */
    val draftDocumentBudget: StateFlow<com.promenar.infinity.data.generation.DraftDocumentBudget?> = _draftDocumentBudget
    /** 输入区上方的引用卡；发送时只有写了批注的进入消息。 */
    private val _draftQuotes = MutableStateFlow<List<ComposerQuoteDraft>>(emptyList())
    val draftQuotes: StateFlow<List<ComposerQuoteDraft>> = _draftQuotes
    private val _draftConsumptionEpoch = MutableStateFlow(0L)
    val draftConsumptionEpoch: StateFlow<Long> = _draftConsumptionEpoch
    /** 发送时已从输入框移走、尚未被生成接受的草稿；用户消息被回滚时还原。 */
    private var submittedDraft: com.promenar.infinity.data.generation.SubmittedGenerationDraft? = null
    private val _restoredDraftImages = MutableStateFlow<List<String>?>(null)
    /** 待还原到输入框的图片 URI；界面取用后调用 [consumeRestoredDraftImages]。 */
    val restoredDraftImages: StateFlow<List<String>?> = _restoredDraftImages
    private val _isImportingDocument = MutableStateFlow(false)
    val isImportingDocument: StateFlow<Boolean> = _isImportingDocument

    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val timedApprovalGrants = (application as InfinityApplication).timedApprovalGrants
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeTimedGrants: StateFlow<Map<com.promenar.infinity.domain.tool.ApprovalGrantCategory, Long>> = _currentSessionId
        .flatMapLatest { sessionId -> if (sessionId == null) flowOf(emptyMap()) else timedApprovalGrants.observe(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun revokeTimedGrants() {
        _currentSessionId.value?.let(timedApprovalGrants::revokeAll)
    }

    private val askUserLedger = (application as InfinityApplication).askUserLedger
    private val questionSubmissionLock = Any()
    private val questionSubmissions = mutableSetOf<com.promenar.infinity.domain.ask.PendingQuestion>()
    private var lastAnsweredQuestion: com.promenar.infinity.domain.ask.PendingQuestion? = null
    private val questionResumptionAttempts = mutableMapOf<String, String>()
    private val questionResumeMutexes = ConcurrentHashMap<String, Mutex>()
    private fun questionResumeMutex(sessionId: String) = questionResumeMutexes.getOrPut(sessionId) { Mutex() }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingQuestion: StateFlow<com.promenar.infinity.domain.ask.PendingQuestion?> = _currentSessionId
        .flatMapLatest { sessionId -> if (sessionId == null) flowOf(null) else askUserLedger.observePending(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val secretIo = ChatSecretIo(
        scope = viewModelScope,
        sessionId = _currentSessionId,
        coordinator = secretInputCoordinator,
        controller = secretOutputController,
        gitCredentials = { (application as InfinityApplication).gitCredentialStore },
        saveTargetsLoader = {
            val app = application as InfinityApplication
            SecretSaveTargets(
                sshHosts = app.sshHostStore.observeHosts().first().map { SecretSaveTargets.Target(it.id, it.alias) },
                providers = ProviderManager.getInstance().providers.value.map {
                    SecretSaveTargets.Target(it.id, providerDisplayName(it.name, it.protocolType, app.resources))
                },
                gitHosts = app.gitCredentialStore.credentials.value.map { it.host to it.username },
            )
        },
        ioDispatcher = Dispatchers.IO,
        failureText = { stringResourceResolver(R.string.secret_io_failed) },
    )
    /** 本会话等待用户输入的保密请求；卡片与 `ask_user` 提问卡在同一位置。 */
    internal val pendingSecretInput: StateFlow<com.promenar.infinity.domain.secret.PendingSecretInput?> = secretIo.pending
    /** 本会话 `show_secret` 交给用户的保密输出卡。 */
    internal val secretOutputCards: StateFlow<List<com.promenar.infinity.domain.secret.SecretOutputCard>> = secretIo.outputCards
    internal val secretInputReceipts: StateFlow<List<SecretInputReceipt>> = secretIo.receipts
    internal val secretInputSubmission: StateFlow<SecretInputSubmission?> = secretIo.submission

    /** 提交保密输入；[value] 由此处起负责清零，调用方不再使用。返回 false 表示请求已不在等待。 */
    internal fun submitSecretInput(
        expected: com.promenar.infinity.domain.secret.PendingSecretInput,
        value: CharArray,
        username: String?,
    ): Boolean = secretIo.submit(expected, value, username)

    internal fun cancelSecretInput(expected: com.promenar.infinity.domain.secret.PendingSecretInput) = secretIo.cancel(expected)

    internal fun completeSecretInputWithGitHubLogin() = secretIo.completeWithGitHubLogin()

    /** 设备验证通过后取值；调用方用完立即清零。 */
    internal fun revealSecret(card: com.promenar.infinity.domain.secret.SecretOutputCard): CharArray? = secretIo.reveal(card)

    internal fun saveSecretAs(
        card: com.promenar.infinity.domain.secret.SecretOutputCard,
        destination: com.promenar.infinity.domain.secret.SecretDestination,
        username: String?,
        onResult: (com.promenar.infinity.domain.secret.SecretInputOutcome) -> Unit,
    ) = secretIo.saveAs(card, destination, username, onResult)

    internal fun destroySecret(card: com.promenar.infinity.domain.secret.SecretOutputCard) = secretIo.destroy(card)

    internal suspend fun secretSaveTargets(): SecretSaveTargets = secretIo.saveTargets()

    /** 输入框提醒里把 GitHub 令牌存进 Git 凭据；[token] 由此处起负责清零。 */
    internal fun saveComposerGitToken(host: String, username: String, token: CharArray, onResult: (Boolean) -> Unit) =
        secretIo.saveComposerGitToken(host, username, token, onResult)

    private val _agentName = MutableStateFlow("")

    private val _streamingContent = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val workspaceErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val annotationStore by lazy {
        val app = application as InfinityApplication
        ImageAnnotationStore(app.workspaceMediaStore, app.workspaceMediaResolver,
            java.io.File(application.filesDir, "image_annotation_previews"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val imageAnnotationAvailable: StateFlow<Boolean> = combine(_currentSessionId, store.state, workspaceErrors) { id, state, errors ->
        Triple(id, state.sessions.firstOrNull { it.id == id }?.let {
            listOf(it.workspaceId, it.executionMode, it.runMode, it.worktreeDirectory, it.workspaceRootUuid)
        }, id != null && errors.containsKey(id))
    }.distinctUntilChanged().mapLatest { (id, binding, unavailable) ->
        id != null && binding != null && !unavailable && withContext(Dispatchers.IO) {
            (application as InfinityApplication).workspaceMediaStore.canSave(id)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    suspend fun saveImageAnnotation(export: ImageAnnotationExport): Uri {
        val sessionId = _currentSessionId.value ?: error("请先打开会话，再保存画板。")
        val session = store.getSession(sessionId) ?: error("当前会话已不存在，无法保存画板。")
        val protectedUris = composerImages.map(Uri::toString) + _restoredDraftImages.value.orEmpty() +
            generationPresentationStore.composerAttachments(sessionId)?.imageUris.orEmpty() +
            generationPresentationStore.submittedDraft(sessionId)?.imageUris.orEmpty() + composerDraftRecovery.imageUris(sessionId)
        val cleanupAllowed = pendingQueueWrites[sessionId] == null && sessionId !in preparingSessions
        check(annotationSavingSessions.add(sessionId)) { "画板正在保存，请稍后再试。" }
        return try {
            val file = annotationStore.save(sessionId, export, ImageAnnotationContext.from(session))
            val current = store.getSession(sessionId)
            check(_currentSessionId.value == sessionId && current?.workspaceId == session.workspaceId &&
                current?.executionMode == session.executionMode && current?.runMode == session.runMode && current?.worktreeDirectory == session.worktreeDirectory) {
                "保存期间会话或工作区已切换，请在当前会话重新打开画板。"
            }
            if (cleanupAllowed) {
                try {
                    // 队列副本由队列管理；原预览还可能由提交草稿或失败恢复持有。
                    val queued = composerQueueStore.snapshot(sessionId)
                    val protectedPaths = (protectedUris.mapNotNull { Uri.parse(it).path } +
                        queued.items.flatMap { it.imagePaths } + queued.sending?.imagePaths.orEmpty() + file.absolutePath).toSet()
                    annotationStore.cleanup(sessionId, protectedPaths)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 清理失败保留预览，留待下次保存时处理。 */ }
            }
            Uri.fromFile(file)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw IllegalStateException("画板保存失败：${failure.message ?: "请检查工作区目录与授权。"}", failure)
        } finally { annotationSavingSessions.remove(sessionId) }
    }

    suspend fun readImageAnnotation(uri: Uri): ImageAnnotation? = withContext(Dispatchers.IO) {
        try {
            val preview = application.contentResolver.openInputStream(uri)?.use { ImageAnnotationPreviewCodec.readPreview(it) }
            preview?.takeIf { it.context?.matches(store.getSession(_currentSessionId.value ?: "")) == true }?.annotation
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun readAnnotationForSend(uri: Uri, session: Session): ImageAnnotation? = withContext(Dispatchers.IO) {
        val preview = application.contentResolver.openInputStream(uri)?.use { ImageAnnotationPreviewCodec.readPreview(it) }
        require(preview == null || preview.context?.matches(session) == true) { "画板所属的会话或工作区已变化，请重新打开画板确认原图。" }
        preview?.annotation
    }

    suspend fun readAnnotationSourceImage(path: String): ByteArray {
        val sessionId = _currentSessionId.value ?: error("请先打开会话，再读取画板原图。")
        return annotationStore.readSource(sessionId, path)
    }

    private val _generationNotice = MutableStateFlow<UiStatusNotice?>(null)
    private val _generationFailureSurface = MutableStateFlow<
        com.promenar.infinity.data.generation.GenerationFailureSurface?
    >(null)
    private val _backgroundServiceStoppedError =
        MutableStateFlow<BackgroundServiceStoppedError?>(null)
    private val _backgroundWarning = MutableStateFlow<BackgroundGenerationWarning?>(null)
    private var foregroundTrackedTaskId: String? = null
    private val mutableNotificationPermissionRequest =
        MutableStateFlow<NotificationPermissionRequest?>(null)
    val notificationPermissionRequests: StateFlow<NotificationPermissionRequest?> =
        mutableNotificationPermissionRequest
    private var pendingPermissionTurn: PendingPermissionTurn? = null
    private var pendingDraftClearTaskId: String? = null
    private var lastFailedTaskId: String? = null
    private var acknowledgedFailureTaskId: String? = null
    private val _providerResolutionFailure = MutableStateFlow<ProviderResolution.Failure?>(null)
    val providerResolutionFailure: StateFlow<ProviderResolution.Failure?> = _providerResolutionFailure
    private val _isGenerating = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _generationStatus = MutableStateFlow(GenerationStatus.IDLE)
    private val _generationOutcomes = MutableSharedFlow<ChatGenerationOutcome>(extraBufferCapacity = 8)
    /** 每个生成任务进入终态时发出一次；终态在 [GenerationStatus] 上可能停留不足一帧，状态振动以此为准。 */
    internal val generationOutcomes: SharedFlow<ChatGenerationOutcome> = _generationOutcomes.asSharedFlow()
    private var lastOutcomeTaskId: String? = null
    private val _isApprovalSubmitting = MutableStateFlow(false)

    data class TokenIndicatorState(
        val used: Int = 0,
        val max: Int = 128000,
        val systemTokens: Int = 0,
        val summaryTokens: Int = 0,
        val activeTokens: Int = 0,
        val ragTokens: Int = 0,
        val foldedToolResults: Int = 0,
        val autoCompactionThreshold: Double = 0.8,
        val isGenerating: Boolean = false,
        val keepRecentTurns: Int = 5,
        /** 系统提示、工具定义、对话三个分区及其叶子类别，供上下文面板平铺显示。 */
        val categories: List<com.promenar.infinity.data.generation.ContextUsageCategory> = emptyList(),
        /** MCP 服务器 id 到名称，面板据此给工具定义的各服务器行命名。 */
        val mcpServerNames: Map<String, String> = emptyMap(),
    )
    private val _tokenIndicatorState = MutableStateFlow(TokenIndicatorState())
    val tokenIndicatorState: StateFlow<TokenIndicatorState> = _tokenIndicatorState

    private val _ragPhases = MutableStateFlow<List<RagPhase>>(emptyList())
    val ragPhases: StateFlow<List<RagPhase>> = _ragPhases

    data class CompressionState(
        val isCompressing: Boolean = false,
        val progress: Float = 0f,
        val detail: String = "",
        val result: String? = null
    )

    private val _compressionState = MutableStateFlow(CompressionState())
    val compressionState: StateFlow<CompressionState> = _compressionState

    private val _postProcessTasks = MutableStateFlow<List<PostProcessTask>>(emptyList())
    val postProcessTasks: StateFlow<List<PostProcessTask>> = _postProcessTasks
    private val _generationRetry = MutableStateFlow<com.promenar.infinity.domain.generation.GenerationRetry?>(null)
    /** 当前正在等待的自动重试；为 null 时不显示重试提示。 */
    val generationRetry: StateFlow<com.promenar.infinity.domain.generation.GenerationRetry?> = _generationRetry
    private val _generationPhase = MutableStateFlow<com.promenar.infinity.domain.generation.GenerationPhase?>(null)
    /** 当前会话进行中生成任务的阶段，驱动生成动效；没有任务时为 null。 */
    val generationPhase: StateFlow<com.promenar.infinity.domain.generation.GenerationPhase?> = _generationPhase
    private val _tailnetWait = MutableStateFlow<com.promenar.infinity.data.generation.TailnetWaitState?>(null)
    /** 发送到 tailnet 内的模型时，等待内置 Tailscale 接入的进度；为 null 时不显示连接浮窗。 */
    val tailnetWait: StateFlow<com.promenar.infinity.data.generation.TailnetWaitState?> = _tailnetWait

    fun addPostProcessTask(type: PostProcessType, status: PostProcessStatus = PostProcessStatus.RUNNING, progress: Float = 0f, detail: String = ""): String {
        val sessionId = _currentSessionId.value ?: return ""
        val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return ""
        val port = generationPresentationStore.port(sessionId, taskId)
        val id = port.addPostProcessTask(type, detail)
        port.updatePostProcessTask(id, status, progress, detail)
        return id
    }

    fun updatePostProcessTask(id: String, status: PostProcessStatus? = null, progress: Float? = null, detail: String? = null) {
        _currentSessionId.value?.let { sessionId ->
            val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return@let
            generationPresentationStore.port(sessionId, taskId)
                .updatePostProcessTask(id, status, progress, detail)
        }
    }

    fun removePostProcessTask(id: String) {
        _currentSessionId.value?.let { sessionId ->
            val taskId = generationCoordinator.observe(sessionId).value?.taskId ?: return@let
            generationPresentationStore.port(sessionId, taskId).removePostProcessTask(id)
        }
    }

    private val generationJobs = ConcurrentHashMap<String, Job>()
    private val approvalJobs = ConcurrentHashMap<String, Job>()
    private var fontSizePersistenceJob: Job? = null
    private val sendPreparationMutexes = ConcurrentHashMap<String, Mutex>()
    private fun sendPreparationMutex(sessionId: String) = sendPreparationMutexes.getOrPut(sessionId) { Mutex() }
    private val approvalSubmissionMutexes = ConcurrentHashMap<String, Mutex>()
    private fun approvalSubmissionMutex(sessionId: String) = approvalSubmissionMutexes.getOrPut(sessionId) { Mutex() }
    /** 重新生成期间被替换、暂不展示的旧回复；成功后由运行时删除，失败或取消后恢复展示。 */
    private val _replacingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val stateBoardRepository = (application as InfinityApplication).stateBoardRepository
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val stateBoard = combine(_currentSessionId, _replacingMessageIds) { session, excluded -> session to excluded }
        .flatMapLatest { (session, excluded) ->
            if (session == null) kotlinx.coroutines.flow.flowOf(null) else stateBoardRepository.observe(session, excluded)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun editStateField(revision: Long, operation: com.promenar.infinity.data.state.StateOperation): String? {
        val session = _currentSessionId.value ?: return stringResourceResolver(R.string.state_save_failed)
        return try {
            stateBoardRepository.edit(session, revision, listOf(operation), _replacingMessageIds.value)
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { stringResourceResolver(R.string.state_save_failed) }
    }

    suspend fun removeStateBoard(revision: Long): String? {
        val session = _currentSessionId.value ?: return stringResourceResolver(R.string.state_save_failed)
        return try {
            stateBoardRepository.removeByUser(session, revision, _replacingMessageIds.value)
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { stringResourceResolver(R.string.state_save_failed) }
    }

    /** 隐藏旧回复所对应的生成任务；该任务结束或进入等待审批后恢复展示（成功时旧回复已被删除）。 */
    private var replacingTaskId: String? = null
    private val _deletionUndo = MutableStateFlow<DeletionUndo?>(null)
    /** 最近一次删除的可撤销提示；为空表示没有可撤销的删除。 */
    val deletionUndo: StateFlow<DeletionUndo?> = _deletionUndo

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ChatUiState> = combine(
        store.state,
        _currentSessionId,
        _agentName,
        _isGenerating,
        _isLoading,
        _generationStatus,
        _streamingContent,
        _error,
        _generationNotice,
        _generationFailureSurface,
        _backgroundServiceStoppedError,
        _backgroundWarning,
        _isApprovalSubmitting,
        _replacingMessageIds,
        workspaceErrors,
    ) { args: Array<Any?> ->
        val state = args[0] as com.promenar.infinity.ui.chat.ChatState
        val sessionId = args[1] as String?
        val agentName = args[2] as String
        val isGenerating = args[3] as Boolean
        val isLoading = args[4] as Boolean
        val status = args[5] as GenerationStatus
        val streamingContent = args[6] as String
        val error = (args[7] as String?) ?: (args[14] as Map<String, String>)[sessionId]
        val generationNotice = args[8] as UiStatusNotice?
        val generationFailureSurface = args[9] as com.promenar.infinity.data.generation.GenerationFailureSurface?
        val backgroundServiceStoppedError = args[10] as BackgroundServiceStoppedError?
        val backgroundWarning = args[11] as BackgroundGenerationWarning?
        val isApprovalSubmitting = args[12] as Boolean
        val replacingMessageIds = args[13] as Set<String>

        val session = state.sessions.find { it.id == sessionId }
        if (session != null) {
            updateTokenIndicator(session, isGenerating)
        } else {
            tokenEstimateJob?.cancel()
        }
        ChatUiState(
            session = session,
            agentName = agentName,
            messages = session?.messages.orEmpty().let { messages ->
                if (replacingMessageIds.isEmpty()) messages else messages.filterNot { it.id in replacingMessageIds }
            },
            isGenerating = isGenerating,
            isLoading = isLoading,
            status = status,
            streamingContent = streamingContent,
            error = backgroundServiceStoppedError?.message ?: error,
            generationNotice = generationNotice,
            generationFailureSurface = generationFailureSurface,
            backgroundWarning = backgroundWarning,
            approvalRequest = session?.approvalRequest,
            isApprovalSubmitting = isApprovalSubmitting,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        viewModelScope.launch {
            combine(store.state, _currentSessionId, _inputText, _draftDocuments, _draftQuotes) { state, id, text, documents, quotes ->
                state.sessions.firstOrNull { it.id == id }?.let { session -> Triple(session,
                    com.promenar.infinity.data.model.MessageQuoteFormatter.formatUserContent(text, ComposerQuotes.toMessageQuotes(quotes)), documents) }
            }.collectLatest { draft ->
                _draftDocumentBudget.value = null
                if (draft != null) {
                    try {
                        val budget = withContext(Dispatchers.Default) { draftDocumentBudgetEstimator(draft.first.id, draft.second, draft.third) }
                        _draftDocumentBudget.value = budget
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _draftDocumentBudget.value = null }
                }
            }
        }
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val resumptions = _currentSessionId.flatMapLatest { sessionId ->
                if (sessionId != null) questionResumptionAttempts.remove(sessionId)
                if (sessionId == null) flowOf(null)
                // 并发上限由协调器排队处理，这里只等本会话的提问任务退出。
                else combine(askUserLedger.observeResumption(sessionId), generationCoordinator.observe(sessionId)) { resumption, task ->
                    sessionId.takeIf {
                        resumption != null && (task == null || task.assistantMessageId != resumption.assistant.id ||
                            task.phase in TERMINAL_GENERATION_PHASES)
                    }
                }
            }
            resumptions.collect { sessionId ->
                // 页面观察器只派发恢复；同会话由恢复锁去重，其它会话不等待旧任务收尾。
                if (sessionId != null) viewModelScope.launch {
                    try {
                        resumeAfterQuestion(sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _error.value = stringResourceResolver(R.string.ask_user_resume_failed)
                    }
                }
            }
        }
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                _composerQueue.value = ComposerQueueUiState()
                if (sessionId == null) return@collectLatest
                try {
                    val queue = composerQueueStore.observe(sessionId)
                    if (!isSessionGenerating(sessionId) && queue.value.pauseReason == null) {
                        queue.value.sending?.let {
                            composerQueueStore.rollback(sessionId, it.id, QueuePauseReason.INTERRUPTED)
                            queueSubmissions.remove(sessionId)
                            queueUserMessageIds.remove(sessionId)
                        }
                        composerQueueStore.pause(sessionId, QueuePauseReason.INTERRUPTED)
                    }
                    var settledTask: String? = null
                    combine(queue, generationPresentationStore.observe(sessionId), generationCoordinator.observe(sessionId)) { state, presentation, active ->
                        Triple(state, presentation, active)
                    }.collect { (state, presentation, active) ->
                        _composerQueue.value = ComposerQueueUiState(
                            state.items.map { QueuedMessageUi(it.id, it.text, it.imagePaths.size, it.documents.map { doc -> doc.name }, it.steering) },
                            state.pauseReason?.let { ComposerQueuePauseReason.valueOf(it.name) },
                        )
                        if (presentation?.phase in TERMINAL_GENERATION_PHASES) queueTerminalPhases[sessionId] = requireNotNull(presentation?.phase)
                        if (presentation?.phase in DRAFT_SETTLING_PHASES && settledTask != presentation?.taskId) {
                            settledTask = presentation?.taskId
                            val reason = terminalQueuePauseReason(sessionId, presentation?.phase)
                            if (state.pauseReason == null && !dispatchesAfterTerminal(sessionId, presentation?.phase)) composerQueueStore.pause(sessionId, reason)
                        }
                        if (dispatchesAfterTerminal(sessionId, queueTerminalPhases[sessionId]) && (active == null || active.phase in QUEUE_INACTIVE_PHASES) &&
                            state.pauseReason == null && state.items.isNotEmpty() && pendingQueueWrites[sessionId] == null
                        ) dispatchComposerQueue(sessionId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _error.value = stringResourceResolver(R.string.chat_queue_error_load)
                    _composerQueue.value = _composerQueue.value.copy(pauseReason = ComposerQueuePauseReason.FAILED)
                } finally {
                    queueTerminalPhases.remove(sessionId)
                    withContext(NonCancellable) {
                        runCatching { composerQueueStore.pauseIfRunningAway(sessionId) }.onFailure {
                            InfinityLogger.diagnostic("composer_queue.pause_failed", mapOf("reason" to "storage"))
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                _compressionState.value = CompressionState()
                if (sessionId != null) contextCompactionService.observe(sessionId).collect { progress ->
                    _compressionState.value = projectCompressionProgress(sessionId, progress)
                    if (!progress.isRunning && progress.result != null) {
                        store.getSession(sessionId)?.let { updateTokenIndicator(it) }
                    }
                }
            }
        }

        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                if (sessionId == null) return@collectLatest
                resetSessionTransientState()
                _isApprovalSubmitting.value = approvalJobs[sessionId]?.isActive == true
                submittedDraft = generationPresentationStore.submittedDraft(sessionId)
                pendingDraftClearTaskId = submittedDraft?.taskId
                restoreFailedComposerDraft(sessionId)
                restoreHistoricalRagProjection(sessionId)
                try {
                    combine(
                        generationPresentationStore.observe(sessionId),
                        generationPresentationStore.observeSubmittedDraft(sessionId),
                    ) { presentation, draft -> presentation to draft }.collect { (presentation, draft) ->
                        if (draft != null) {
                            submittedDraft = draft
                            if (draft.recoveryReady) {
                                restoreSubmittedDraftFromPresentation(sessionId)
                                return@collect
                            }
                            if (draft.taskId != null && pendingDraftClearTaskId == null) pendingDraftClearTaskId = draft.taskId
                        }
                        if (presentation == null) {
                            if (draft?.taskId != null && pendingDraftClearTaskId == draft.taskId &&
                                generationCoordinator.observe(sessionId).value == null
                            ) {
                                pendingDraftClearTaskId = null
                                if (submittedUserMessageRolledBack(sessionId)) restoreSubmittedDraftFromPresentation(sessionId)
                                else consumeAcceptedDraft(sessionId)
                            }
                            if (pendingDraftClearTaskId != DRAFT_PENDING_START) pendingDraftClearTaskId = null
                            _streamingContent.value = ""
                            _postProcessTasks.value = emptyList()
                            _generationRetry.value = null
                            _tailnetWait.value = null
                            _ragPhases.value = emptyList()
                            if (_generationStatus.value != GenerationStatus.ERROR) {
                                _error.value = null
                                _generationNotice.value = null
                                _providerResolutionFailure.value = null
                                _generationStatus.value = GenerationStatus.IDLE
                            }
                            return@collect
                        }
                        _ragPhases.value = presentation.ragPhases
                        _streamingContent.value = presentation.streamingContent
                        if (
                            pendingDraftClearTaskId != null &&
                            (
                                pendingDraftClearTaskId == DRAFT_PENDING_START ||
                                    presentation.taskId == pendingDraftClearTaskId
                                ) &&
                            presentation.promptAccepted
                        ) {
                            pendingDraftClearTaskId = null
                            consumeAcceptedDraft(sessionId)
                        } else if (
                            pendingDraftClearTaskId != null &&
                            presentation.taskId == pendingDraftClearTaskId &&
                            !presentation.promptAccepted &&
                            presentation.phase in DRAFT_SETTLING_PHASES
                        ) {
                            // 准备阶段就结束：用户消息被回滚（如全文文档超出预算）才还原草稿，否则消息已在历史中可重试。
                            pendingDraftClearTaskId = null
                            if (submittedUserMessageRolledBack(sessionId)) {
                                restoreSubmittedDraftFromPresentation(sessionId)
                            } else {
                                consumeAcceptedDraft(sessionId)
                            }
                        }
                        val failureAcknowledged = presentation.taskId == acknowledgedFailureTaskId
                        if (
                            presentation.handledFailure ||
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.FAILED ||
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED
                        ) {
                            lastFailedTaskId = presentation.taskId
                        }
                        _generationFailureSurface.value =
                            if (failureAcknowledged) null else presentation.failureSurface
                        _generationNotice.value =
                            if (failureAcknowledged) null else presentation.error?.let(GenerationFailureNotice::from)
                        _providerResolutionFailure.value = presentation.providerFailure
                        _isGenerating.value = presentation.generating
                        _postProcessTasks.value = presentation.postProcessTasks
                        _generationRetry.value = presentation.retry
                        _tailnetWait.value = presentation.tailnetWait
                        _generationStatus.value = when {
                            presentation.handledFailure ->
                                if (failureAcknowledged) GenerationStatus.IDLE else GenerationStatus.ERROR
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.THINKING ||
                                presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.EXECUTING_TOOLS ->
                                GenerationStatus.THINKING
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.STREAMING ->
                                GenerationStatus.RECEIVING
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED ->
                                GenerationStatus.COMPLETED
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.FAILED ->
                                if (failureAcknowledged) GenerationStatus.IDLE else GenerationStatus.ERROR
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED ->
                                GenerationStatus.IDLE
                            presentation.phase == com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED ->
                                if (failureAcknowledged) GenerationStatus.IDLE else GenerationStatus.ERROR
                            else -> _generationStatus.value
                        }
                    }
                } finally {
                    generationPresentationStore.release(sessionId)
                }
            }
        }
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                _generationPhase.value = null
                if (sessionId == null) return@collectLatest
                var observedTaskId: String? = null
                run {
                    combine(
                        generationCoordinator.observe(sessionId),
                        generationPresentationStore.observe(sessionId),
                    ) { task, presentation -> task to presentation }
                        .collect { (task, presentation) ->
                        replacingTaskId?.let { replacing ->
                            if (task == null || task.taskId != replacing || task.phase in FOREGROUND_STOPPING_PHASES) {
                                replacingTaskId = null
                                _replacingMessageIds.value = emptySet()
                            }
                        }
                        _generationPhase.value = task?.phase
                        if (task == null) {
                            _isGenerating.value = false
                            observedTaskId?.let {
                                clearBackgroundWarningForTask(it)
                                clearNotificationPermissionRequestForTask(it)
                            }
                            observedTaskId = null
                            if (
                                _generationStatus.value == GenerationStatus.COMPLETED ||
                                (
                                    _generationStatus.value == GenerationStatus.ERROR &&
                                        _generationNotice.value == null &&
                                        _error.value == null
                                    )
                            ) {
                                _generationStatus.value = GenerationStatus.IDLE
                            }
                            return@collect
                        }
                        observedTaskId = task.taskId
                        _isGenerating.value = task.phase !in setOf(
                            com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED,
                            com.promenar.infinity.domain.generation.GenerationPhase.FAILED,
                            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED,
                            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
                            com.promenar.infinity.domain.generation.GenerationPhase.WAITING_APPROVAL,
                            com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT,
                        )
                        _generationStatus.value = when (task.phase) {
                            com.promenar.infinity.domain.generation.GenerationPhase.QUEUED,
                            com.promenar.infinity.domain.generation.GenerationPhase.PREPARING,
                            com.promenar.infinity.domain.generation.GenerationPhase.BUILDING_CONTEXT,
                            com.promenar.infinity.domain.generation.GenerationPhase.CONNECTING -> GenerationStatus.UPLOADING
                            com.promenar.infinity.domain.generation.GenerationPhase.THINKING -> GenerationStatus.THINKING
                            com.promenar.infinity.domain.generation.GenerationPhase.STREAMING -> GenerationStatus.RECEIVING
                            com.promenar.infinity.domain.generation.GenerationPhase.EXECUTING_TOOLS -> GenerationStatus.THINKING
                            com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED -> GenerationStatus.COMPLETED
                            com.promenar.infinity.domain.generation.GenerationPhase.FAILED -> GenerationStatus.ERROR
                            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED -> GenerationStatus.IDLE
                            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED -> GenerationStatus.ERROR
                            com.promenar.infinity.domain.generation.GenerationPhase.WAITING_APPROVAL,
                            com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT -> _generationStatus.value
                            com.promenar.infinity.domain.generation.GenerationPhase.POST_PROCESSING -> GenerationStatus.RECEIVING
                        }
                        if (task.phase in TERMINAL_GENERATION_PHASES) {
                            clearBackgroundWarningForTask(task.taskId)
                            clearNotificationPermissionRequestForTask(task.taskId)
                            val terminalPresentation = presentation
                                ?.takeIf { it.taskId == task.taskId }
                                ?.takeIf {
                                    it.handledFailure ||
                                        it.phase?.let { phase -> phase in TERMINAL_GENERATION_PHASES } == true
                                }
                                ?: return@collect
                            if (lastOutcomeTaskId != task.taskId) {
                                lastOutcomeTaskId = task.taskId
                                queueTerminalPhases[sessionId] = task.phase
                                if (
                                    (task.phase == GenerationPhase.COMPLETED && !terminalPresentation.handledFailure) ||
                                    (task.phase == GenerationPhase.CANCELLED && sessionId in stopAndSendSessions)
                                ) {
                                    dispatchComposerQueue(sessionId)
                                } else {
                                    queueAction {
                                        val queue = composerQueueStore.snapshot(sessionId)
                                        if (queue.pauseReason == null) composerQueueStore.pause(sessionId,
                                            terminalQueuePauseReason(sessionId, task.phase))
                                    }
                                }
                                chatGenerationOutcomeKind(task.phase, terminalPresentation.handledFailure)?.let { kind ->
                                    _generationOutcomes.tryEmit(ChatGenerationOutcome(sessionId, kind))
                                }
                            }
                            terminalPresentation.error?.let {
                                _generationNotice.value = GenerationFailureNotice.from(it)
                            }
                            _providerResolutionFailure.value =
                                terminalPresentation.providerFailure ?: _providerResolutionFailure.value
                            _ragPhases.value = terminalPresentation.ragPhases
                            _postProcessTasks.value = terminalPresentation.postProcessTasks
                            if (task.phase != com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED) {
                                generationCoordinator.acknowledgeTerminal(task.taskId)
                            }
                        }
                        }
                }
            }
        }
        viewModelScope.launch {
            generationForegroundController.failures.collect { failure ->
                if (
                    failure.outcome ==
                    com.promenar.infinity.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED &&
                    foregroundTrackedTaskId == failure.taskId
                ) {
                    _backgroundServiceStoppedError.value = BackgroundServiceStoppedError(
                        taskId = failure.taskId,
                        message = stringResourceResolver(
                            R.string.generation_background_service_stopped,
                        ),
                    )
                }
            }
        }
        approvalManager.setCallbacks(
            onGenerateMessage = { sessionId, _, _ -> resumeAfterApproval(sessionId) },
            onExecuteTools = { sessionId, targetMessageId, allowedToolCallIds ->
                val session = store.getSession(sessionId) ?: return@setCallbacks
                val msg = session.messages.find { it.id == targetMessageId } ?: return@setCallbacks
                if (msg.toolCalls != null && msg.toolCalls.any { it.id in allowedToolCallIds }) {
                    executeWithApprovedSshOwner(sessionId) {
                        toolExecutor.executeTools(
                            sessionId,
                            targetMessageId,
                            msg.toolCalls,
                            allowedToolCallIds,
                        )
                    }
                }
            }
        )
    }

    fun updateInputText(text: String) {
        if (_generationStatus.value == GenerationStatus.ERROR) {
            acknowledgeGenerationFailure()
        }
        _inputText.update { text }
    }

    /** 画布只能追加到它所在会话的草稿，发送仍由用户触发。 */
    fun appendCanvasText(sessionId: String, text: String) {
        com.promenar.infinity.ui.canvas.appendCanvasDraft(sessionId, _currentSessionId.value, _inputText.value, text)
            ?.let(::updateInputText)
    }

    /**
     * 用户确认生成失败：解除失败态，恢复发送按钮。
     * 同一 taskId 的失败 presentation 之后不再把状态拉回 ERROR。
     */
    fun acknowledgeGenerationFailure() {
        if (_generationStatus.value != GenerationStatus.ERROR) return
        acknowledgedFailureTaskId = lastFailedTaskId
        _generationStatus.value = GenerationStatus.IDLE
        _generationFailureSurface.value = null
        _generationNotice.value = null
    }

    fun importFullContextDocument(uri: Uri) = importFullContextDocuments(listOf(uri))

    fun importFullContextDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _isImportingDocument.value = true
        viewModelScope.launch {
            try {
                val imported = buildList {
                    uris.forEach { uri ->
                        when (val result = fullContextDocumentReader.read(uri)) {
                            is DocumentReadResult.Success -> add(result.attachment)
                            is DocumentReadResult.Rejected ->
                                _error.value = result.reason.userMessage(stringResourceResolver)
                        }
                    }
                }
                if (imported.isNotEmpty()) {
                    _draftDocuments.update { current ->
                        val importedHashes = imported.mapTo(mutableSetOf()) { it.sha256 }
                        current.filterNot { it.sha256 in importedHashes } + imported.distinctBy { it.sha256 }
                    }
                    if (imported.size == uris.size) _error.value = null
                }
            } finally {
                _isImportingDocument.value = false
            }
        }
    }

    fun removeDraftDocument(documentId: String) {
        _draftDocuments.update { documents -> documents.filterNot { it.id == documentId } }
    }

    /** 项目规则指定的落点由 relativeDirectory 传入；未指定时使用附件日期目录。 */
    suspend fun saveDraftDocumentToWorkspace(documentId: String, relativeDirectory: String? = null): com.promenar.infinity.data.document.DocumentWorkspaceSaveResult {
        val sessionId = _currentSessionId.value ?: return com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Failure(
            com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure.WorkspaceUnavailable)
        val document = _draftDocuments.value.firstOrNull { it.id == documentId }
            ?: return com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Failure(com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure.DocumentNotFound)
        val result = documentWorkspaceSaver.save(sessionId, document, relativeDirectory)
        if (result is com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Success) {
            var replaced = false
            if (_currentSessionId.value == sessionId) _draftDocuments.update { current -> current.map {
                if (it == document) { replaced = true; result.attachment } else it
            } }
            if (!replaced) return com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Failure(
                com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure.DraftChanged)
        }
        return result
    }

    /** 选字工具条的“引用”：同一来源的同一段引文只加一张卡。 */
    fun addQuote(sourceMessageId: String, selectedText: String) {
        val draft = ComposerQuotes.draft(sourceMessageId, selectedText) ?: return
        _draftQuotes.update { current ->
            if (current.any { it.sourceMessageId == draft.sourceMessageId && it.text == draft.text }) current else current + draft
        }
    }

    fun updateQuoteAnnotation(id: String, annotation: String) {
        _draftQuotes.update { quotes -> quotes.map { if (it.id == id) it.copy(annotation = annotation) else it } }
    }

    fun removeQuote(id: String) {
        _draftQuotes.update { quotes -> quotes.filterNot { it.id == id } }
    }

    fun sendMessage(text: String, imageUris: List<Uri> = emptyList()) {
        val sessionId = _currentSessionId.value
        if (sessionId == null) return
        if (_isImportingDocument.value) {
            _error.value = stringResourceResolver(R.string.chat_document_import_in_progress)
            return
        }
        val documents = _draftDocuments.value
        val quotes = ComposerQuotes.toMessageQuotes(_draftQuotes.value)
        if (text.isBlank() && imageUris.isEmpty() && documents.isEmpty() && quotes.isEmpty()) return

        if (store.getSession(sessionId) == null) return

        if (generationPresentationStore.submittedDraft(sessionId)?.recoveryReady == true) {
            if (!draftRecoveryRetries.add(sessionId)) return
            val inputAtClick = _inputText.value
            val textAtClick = inputAtClick.ifBlank { text }
            val documentsAtClick = documents.toList()
            val quotesAtClick = _draftQuotes.value.toList()
            val composerImagesAtClick = composerImages.toList()
            val imagesAtClick = (imageUris + composerImagesAtClick).distinct()
            viewModelScope.launch {
                try {
                    restoreSubmittedDraftFromPresentation(sessionId)
                    if (_currentSessionId.value == sessionId && generationPresentationStore.submittedDraft(sessionId) == null) {
                        if (_inputText.value == inputAtClick && _draftDocuments.value == documentsAtClick && _draftQuotes.value == quotesAtClick && composerImages == composerImagesAtClick) {
                            sendMessage(text, imageUris)
                        } else {
                            if (textAtClick.isNotBlank() && !_inputText.value.contains(textAtClick)) {
                                _inputText.value = listOf(textAtClick, _inputText.value).filter { it.isNotBlank() }.joinToString("\n\n")
                            }
                            _draftDocuments.value = (documentsAtClick + _draftDocuments.value).distinctBy { it.id }
                            _draftQuotes.value = ComposerQuotes.merge(quotesAtClick, _draftQuotes.value)
                            composerImages = (imagesAtClick + composerImages).distinct()
                            _restoredDraftImages.value = composerImages.map(Uri::toString).takeIf { it.isNotEmpty() }
                        }
                    }
                } finally {
                    draftRecoveryRetries.remove(sessionId)
                }
            }
            return
        }
        if (isSessionGenerating(sessionId)) {
            enqueueComposerDraft(sessionId, text, imageUris, documents, quotes)
            return
        }
        cancelPendingPreparation(sessionId)
        preparingSessions.add(sessionId)
        val submissionId = takeDraftForSending(sessionId, text, imageUris)
        generationJobs[sessionId] = viewModelScope.launch {
            try {
                sendPreparationMutex(sessionId).withLock {
                    closePendingApprovalBeforeNewTurn(sessionId)
                    val session = store.getSession(sessionId) ?: return@withLock
                    if (_currentSessionId.value == sessionId) {
                        _generationStatus.update { GenerationStatus.UPLOADING }
                        _isGenerating.update { true }
                        _error.update { null }
                        _generationNotice.value = null
                        _backgroundServiceStoppedError.value = null
                        _backgroundWarning.value = null
                        _providerResolutionFailure.value = null
                    }

                    val imageAnnotations = try {
                        imageUris.map { readAnnotationForSend(it, session) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        restoreSubmittedDraft(sessionId)
                        if (_currentSessionId.value == sessionId) {
                            _error.value = failure.message ?: stringResourceResolver(R.string.chat_image_read_failed)
                            _generationStatus.value = GenerationStatus.ERROR
                            _isGenerating.value = false
                        }
                        return@withLock
                    }
                    val imageDataUrls = if (imageUris.isNotEmpty()) {
                        val converted = withContext(Dispatchers.IO) {
                            imageUris.map { uri -> uriToDataUrl(uri) }
                        }
                        if (converted.any { it == null }) {
                            restoreSubmittedDraft(sessionId)
                            if (_currentSessionId.value == sessionId) {
                                _error.update { stringResourceResolver(R.string.chat_image_read_failed) }
                                _generationStatus.update { GenerationStatus.ERROR }
                                _isGenerating.update { false }
                            }
                            return@withLock
                        }
                        converted.filterNotNull()
                    } else {
                        emptyList()
                    }

                    applyBackgroundPolicyGate(sessionId, session, text, imageDataUrls, documents, quotes, imageAnnotations = imageAnnotations, imageAnnotationContext = ImageAnnotationContext.from(session))
                }
            } finally {
                try {
                    val draft = generationPresentationStore.submittedDraft(sessionId)
                    if (draft?.submissionId == submissionId && draft.taskId == null && pendingPermissionTurn?.sessionId != sessionId) {
                        withContext(NonCancellable) { restoreSubmittedDraft(sessionId, expectedSubmissionId = submissionId) }
                    }
                } finally {
                    preparingSessions.remove(sessionId)
                    if (queueTerminalPhases[sessionId] == GenerationPhase.COMPLETED) dispatchComposerQueue(sessionId)
                }
            }
        }
    }


    private fun isSessionGenerating(sessionId: String): Boolean {
        if (sessionId in preparingSessions || pendingPermissionTurn?.sessionId == sessionId ||
            generationPresentationStore.submittedDraft(sessionId) != null
        ) return true
        if (store.getSession(sessionId)?.approvalRequest != null) return false
        val active = generationCoordinator.observe(sessionId).value
        return (active?.sessionId == sessionId && active.phase !in QUEUE_INACTIVE_PHASES) ||
            generationPresentationStore.observe(sessionId).value?.generating == true ||
            (_currentSessionId.value == sessionId && generationJobs[sessionId]?.isActive == true && _generationStatus.value == GenerationStatus.UPLOADING)
    }

    private suspend fun ComposerQueueStore.pauseIfRunningAway(sessionId: String) {
        var state = snapshot(sessionId)
        if (state.sending != null && generationCoordinator.observe(sessionId).value?.phase.let { it == null || it in QUEUE_INACTIVE_PHASES } && sessionId !in preparingSessions) {
            generationCoordinator.awaitSessionIdle(sessionId)
            state = snapshot(sessionId)
            state.sending?.let { sending ->
                rollback(sessionId, sending.id, state.pauseReason ?: queuePauseOverrides[sessionId] ?: QueuePauseReason.INTERRUPTED)
                queueSubmissions.remove(sessionId)
                queueUserMessageIds.remove(sessionId)
                return
            }
        }
        if (state.pauseReason == null && (state.items.isNotEmpty() || state.sending != null)) {
            pause(sessionId, queuePauseOverrides[sessionId] ?: QueuePauseReason.INTERRUPTED)
        }
    }

    private fun enqueueComposerDraft(sessionId: String, text: String, imageUris: List<Uri>, documents: List<MessageDocumentAttachment>, quotes: List<MessageQuote>, steer: Boolean = false, moveToFront: Boolean = false, beforeEnqueue: () -> Unit = {}) {
        generationPresentationStore.discardComposerAttachments(sessionId)
        _inputText.value = ""
        _draftDocuments.value = emptyList()
        _draftQuotes.value = emptyList()
        _restoredDraftImages.value = null
        composerImages = emptyList()
        _draftConsumptionEpoch.value += 1
        pendingQueueWrites[sessionId] = (pendingQueueWrites[sessionId] ?: 0) + 1
        beforeEnqueue()
        viewModelScope.launch {
            var stored = false
            try {
                val item = composerQueueStore.enqueue(sessionId, text, imageUris.map(Uri::toString), documents, quotes)
                stored = true
                if (steer && composerQueueStore.steer(sessionId, item.id)) queuePauseOverrides.remove(sessionId)
                if (moveToFront) composerQueueStore.moveToFront(sessionId, item.id)
                composerQueueStore.releaseDraftImages(sessionId, imageUris.mapNotNull { it.path })
                if (_currentSessionId.value != sessionId || !viewModelScope.isActive) composerQueueStore.pause(sessionId, queuePauseOverrides[sessionId] ?: QueuePauseReason.INTERRUPTED)
                else if (queueTerminalPhases[sessionId] in DRAFT_SETTLING_PHASES && sessionId !in stopAndSendSessions) {
                    val state = composerQueueStore.snapshot(sessionId)
                    if (state.pauseReason == null) composerQueueStore.pause(sessionId, queuePauseOverrides[sessionId] ?: QueuePauseReason.FAILED)
                }
                sessionManager.updateSessionDraft(sessionId, if (_currentSessionId.value == sessionId) _inputText.value.ifBlank { null } else store.getSession(sessionId)?.draft)
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    if (stored) {
                        val state = composerQueueStore.snapshot(sessionId)
                        composerQueueStore.pause(sessionId, state.pauseReason ?: if (failure is CancellationException) QueuePauseReason.INTERRUPTED else QueuePauseReason.FAILED)
                        _error.value = stringResourceResolver(R.string.chat_queue_error_draft_after_enqueue)
                    } else {
                        composerDraftRecovery.retain(sessionId, com.promenar.infinity.data.chat.queue.RecoverableComposerDraft(text, imageUris.map(Uri::toString), documents, quotes))
                        if (_currentSessionId.value == sessionId && viewModelScope.isActive) restoreFailedComposerDraft(sessionId)
                        _error.value = stringResourceResolver(R.string.chat_queue_error_enqueue)
                    }
                }
                if (failure is kotlinx.coroutines.CancellationException) throw failure
            } finally {
                val remaining = (pendingQueueWrites[sessionId] ?: 1) - 1
                if (remaining == 0) pendingQueueWrites.remove(sessionId) else pendingQueueWrites[sessionId] = remaining
                if (viewModelScope.isActive && dispatchesAfterTerminal(sessionId, queueTerminalPhases[sessionId])) dispatchComposerQueue(sessionId)
            }
        }
    }

    /** 本轮终态后是否继续发出队列：正常完成，或“停止并立即发送”触发的取消。 */
    private fun dispatchesAfterTerminal(sessionId: String, phase: GenerationPhase?): Boolean =
        phase == GenerationPhase.COMPLETED || (phase == GenerationPhase.CANCELLED && sessionId in stopAndSendSessions)

    private suspend fun restoreFailedComposerDraft(sessionId: String) {
        if (_currentSessionId.value != sessionId || !viewModelScope.isActive) return
        val recovered = composerDraftRecovery.take(sessionId)
        if (recovered.isEmpty()) return
        _inputText.value = (recovered.map { it.text } + _inputText.value).filter { it.isNotBlank() }.joinToString("\n\n")
        _draftDocuments.value = (recovered.flatMap { it.documents } + _draftDocuments.value).distinctBy { it.id }
        _draftQuotes.value = ComposerQuotes.merge(ComposerQuotes.fromMessageQuotes(recovered.flatMap { it.quotes }), _draftQuotes.value)
        composerImages = (recovered.flatMap { it.imageUris }.map(Uri::parse) + composerImages).distinct()
        _restoredDraftImages.value = composerImages.map(Uri::toString).takeIf { it.isNotEmpty() }
        runCatching { sessionManager.updateSessionDraft(sessionId, _inputText.value.ifBlank { null }) }
            .onFailure { _error.value = stringResourceResolver(R.string.chat_queue_error_draft_after_restore) }
    }

    /**
     * 本轮未成功结束时队列的暂停原因。用户停止会先同步记下 STOPPED，再异步写盘；
     * 终态处理可能读到写盘前的快照，取消时必须优先采用它，不能误报成“离开会话”。
     */
    private fun terminalQueuePauseReason(sessionId: String, phase: GenerationPhase?): QueuePauseReason =
        if (phase == GenerationPhase.CANCELLED) queuePauseOverrides[sessionId] ?: QueuePauseReason.INTERRUPTED
        else QueuePauseReason.FAILED

    /** 界面选图或移除图片时同步，用于编辑队列项前检查输入区是否为空。 */
    fun updateComposerImages(imageUris: List<Uri>) {
        composerImages = imageUris.toList()
    }

    fun steerQueuedMessage(id: String) {
        val sessionId = _currentSessionId.value ?: return
        queueAction {
            if (composerQueueStore.steer(sessionId, id)) {
                queuePauseOverrides.remove(sessionId)
                dispatchComposerQueue(sessionId)
            }
        }
    }

    /** 生成中立即打断当前步骤并注入当前循环；不在生成时等同普通发送。 */
    fun sendMessageSteering(text: String, imageUris: List<Uri> = emptyList()) {
        val sessionId = _currentSessionId.value ?: return
        if (!queuesDraft(sessionId, text, imageUris)) return sendMessage(text, imageUris)
        enqueueComposerDraft(sessionId, text, imageUris, _draftDocuments.value, ComposerQuotes.toMessageQuotes(_draftQuotes.value), steer = true)
    }

    /** 停止本轮，草稿排到队首并在停止落定后立即发出；不在生成时等同普通发送。 */
    fun stopAndSendNow(text: String, imageUris: List<Uri> = emptyList()) {
        val sessionId = _currentSessionId.value ?: return
        if (!queuesDraft(sessionId, text, imageUris)) return sendMessage(text, imageUris)
        stopAndSendSessions += sessionId
        // 先登记待写入草稿再取消旧循环，新条目入队时旧循环已撤销，终态派发也会等待写盘。
        enqueueComposerDraft(sessionId, text, imageUris, _draftDocuments.value, ComposerQuotes.toMessageQuotes(_draftQuotes.value),
            moveToFront = true, beforeEnqueue = ::cancelCurrentSessionGeneration)
        viewModelScope.launch {
            sendPreparationMutex(sessionId).withLock { terminateSessionWork(sessionId) }
        }
    }

    /** 与 [sendMessage] 的排队判定一致：只有生成中且没有待审批时，草稿才进入队列。 */
    private fun queuesDraft(sessionId: String, text: String, imageUris: List<Uri>): Boolean =
        !_isImportingDocument.value &&
            (text.isNotBlank() || imageUris.isNotEmpty() || _draftDocuments.value.isNotEmpty() || ComposerQuotes.annotatedCount(_draftQuotes.value) > 0) &&
            store.getSession(sessionId)?.approvalRequest == null && isSessionGenerating(sessionId)

    fun removeQueuedMessage(id: String) {
        val sessionId = _currentSessionId.value ?: return
        queueAction { composerQueueStore.remove(sessionId, id) }
    }

    fun moveQueuedMessageToFront(id: String) {
        val sessionId = _currentSessionId.value ?: return
        queueAction { composerQueueStore.moveToFront(sessionId, id) }
    }

    fun editQueuedMessage(id: String): Boolean {
        val sessionId = _currentSessionId.value ?: return false
        if (editingQueueItem || _inputText.value.isNotBlank() || _draftDocuments.value.isNotEmpty() || _draftQuotes.value.isNotEmpty() ||
            composerImages.isNotEmpty() || !_restoredDraftImages.value.isNullOrEmpty() || _isImportingDocument.value
        ) return false
        if (composerQueue.value.items.none { it.id == id }) return false
        editingQueueItem = true
        queueAction {
            withContext(NonCancellable) {
                queueMutex(sessionId).withLock {
                    var prepared: QueuedMessage? = null
                    var committed = false
                    try {
                        prepared = composerQueueStore.prepareEdit(sessionId, id) ?: return@withLock
                        // 复制之后重验输入区，确认接受时先同步交接草稿，再提交队列移除。
                        if (!viewModelScope.isActive || _currentSessionId.value != sessionId || _inputText.value.isNotBlank() ||
                            _draftDocuments.value.isNotEmpty() || _draftQuotes.value.isNotEmpty() || composerImages.isNotEmpty() || !_restoredDraftImages.value.isNullOrEmpty()
                        ) return@withLock
                        val item = prepared
                        _inputText.value = item.text
                        _draftDocuments.value = item.documents
                        _draftQuotes.value = ComposerQuotes.fromMessageQuotes(item.quotes)
                        composerImages = item.imagePaths.map { Uri.fromFile(java.io.File(it)) }
                        _restoredDraftImages.value = composerImages.map(Uri::toString).takeIf { it.isNotEmpty() }
                        try {
                            committed = composerQueueStore.completeEdit(sessionId, item)
                        } catch (failure: Exception) {
                            releaseUncommittedEditFromComposer(sessionId, item)
                            throw failure
                        }
                        if (!committed) {
                            releaseUncommittedEditFromComposer(sessionId, item)
                        } else {
                            sessionManager.updateSessionDraft(sessionId, if (_currentSessionId.value == sessionId) _inputText.value.ifBlank { null } else item.text.ifBlank { null })
                            if (_currentSessionId.value != sessionId || !viewModelScope.isActive) {
                                composerDraftRecovery.retain(sessionId, com.promenar.infinity.data.chat.queue.RecoverableComposerDraft(item.text, item.imagePaths.map { Uri.fromFile(java.io.File(it)).toString() }, item.documents, item.quotes))
                            }
                        }
                    } finally {
                        if (!committed) prepared?.let { composerQueueStore.cancelEdit(sessionId, it) }
                        editingQueueItem = false
                    }
                }
            }
        }
        return true
    }

    private fun releaseUncommittedEditFromComposer(sessionId: String, item: QueuedMessage) {
        if (_currentSessionId.value != sessionId) return
        if (_inputText.value == item.text) _inputText.value = ""
        _draftDocuments.value = _draftDocuments.value.filterNot { doc -> item.documents.any { it.id == doc.id } }
        _draftQuotes.value = _draftQuotes.value.filterNot { draft -> item.quotes.any { it.sourceMessageId == draft.sourceMessageId && it.text == draft.text } }
        composerImages = composerImages.filterNot { it.path in item.imagePaths }
        _restoredDraftImages.value = composerImages.map(Uri::toString).takeIf { it.isNotEmpty() }
    }

    fun resumeComposerQueue() {
        val sessionId = _currentSessionId.value ?: return
        queuePauseOverrides.remove(sessionId)
        queueAction {
            composerQueueStore.pause(sessionId, null)
            dispatchComposerQueue(sessionId)
        }
    }

    private fun queueAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { _error.value = stringResourceResolver(R.string.chat_queue_error_update) }
        }
    }

    private fun dispatchComposerQueue(sessionId: String) {
        if (!viewModelScope.isActive) return
        if (queueDispatchJobs[sessionId]?.isActive == true) {
            queueDispatchRequested.add(sessionId)
            return
        }
        lateinit var dispatch: Job
        dispatch = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                queueMutex(sessionId).withLock queue@ {
                    if (_currentSessionId.value != sessionId || isSessionGenerating(sessionId) ||
                        pendingQueueWrites[sessionId] != null || queueSubmissions.containsKey(sessionId) || pendingDraftClearTaskId != null || submittedDraft?.sessionId == sessionId
                    ) return@queue
                    generationCoordinator.awaitSessionIdle(sessionId)
                    if (_currentSessionId.value != sessionId || isSessionGenerating(sessionId) || pendingQueueWrites[sessionId] != null) return@queue
                    val state = composerQueueStore.snapshot(sessionId)
                    if (state.pauseReason != null || state.items.isEmpty()) return@queue
                    val item = composerQueueStore.claimNext(sessionId) ?: return@queue
                    queueTerminalPhases.remove(sessionId)
                    stopAndSendSessions.remove(sessionId)
                    queuePauseOverrides.remove(sessionId)
                    queueSubmissions[sessionId] = item
                    preparingSessions.add(sessionId)
                    try {
                        sendPreparationMutex(sessionId).withLock preparation@ {
                            if (_currentSessionId.value != sessionId) {
                                restoreSubmittedDraft(sessionId, queueReason = QueuePauseReason.INTERRUPTED)
                                return@preparation
                            }
                            closePendingApprovalBeforeNewTurn(sessionId)
                            val session = store.getSession(sessionId) ?: error("会话已删除")
                            _isGenerating.value = true
                            _generationStatus.value = GenerationStatus.UPLOADING
                            val imageAnnotations = item.imagePaths.map { readAnnotationForSend(Uri.fromFile(java.io.File(it)), session) }
                            val images = if (item.imagePaths.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                                item.imagePaths.map { uriToDataUrl(Uri.fromFile(java.io.File(it))) ?: error("无法读取排队图片") }
                            }
                            if (_currentSessionId.value != sessionId) {
                                restoreSubmittedDraft(sessionId, queueReason = QueuePauseReason.INTERRUPTED)
                            } else {
                                applyBackgroundPolicyGate(sessionId, session, item.text, images, item.documents, item.quotes, imageAnnotations = imageAnnotations, imageAnnotationContext = ImageAnnotationContext.from(session))
                            }
                        }
                    } catch (failure: Exception) {
                        withContext(NonCancellable) {
                            restoreSubmittedDraft(sessionId, queueReason = if (failure is CancellationException) QueuePauseReason.INTERRUPTED else QueuePauseReason.FAILED)
                        }
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        if (_currentSessionId.value == sessionId) {
                            _error.value = stringResourceResolver(R.string.chat_queue_error_send)
                            _isGenerating.value = false
                        }
                    } finally {
                        preparingSessions.remove(sessionId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                queueTerminalPhases[sessionId] = GenerationPhase.FAILED
                if (_currentSessionId.value == sessionId) _error.value = stringResourceResolver(R.string.chat_queue_error_send)
                runCatching { composerQueueStore.pause(sessionId, QueuePauseReason.FAILED) }
                if (_currentSessionId.value == sessionId) _composerQueue.value = _composerQueue.value.copy(pauseReason = ComposerQueuePauseReason.FAILED)
            } finally {
                if (queueDispatchJobs.remove(sessionId, dispatch)) {
                    val requested = queueDispatchRequested.remove(sessionId)
                    if (requested) dispatchComposerQueue(sessionId)
                    else if (viewModelScope.isActive && _currentSessionId.value == sessionId &&
                        queueTerminalPhases[sessionId] == GenerationPhase.COMPLETED &&
                        !isSessionGenerating(sessionId) && !queueSubmissions.containsKey(sessionId) && pendingQueueWrites[sessionId] == null
                    ) {
                        val remaining = composerQueueStore.snapshot(sessionId)
                        if (remaining.items.isNotEmpty() && remaining.pauseReason == null) dispatchComposerQueue(sessionId)
                    }
                }
            }
        }
        queueDispatchJobs[sessionId] = dispatch
        dispatch.start()
    }


    private fun currentBackgroundAllowed(): Boolean {
        val snapshot = notificationPermissionGateway?.current() ?: return true
        return snapshot.sdkInt < 33 || snapshot.granted
    }

    /**
     * 发送后台可持续生成前的权限时序门：在添加消息/启动生成之前决定运行策略。
     * 未授权且从未问过时必须先解释并取得系统结果，因此暂存该轮次并发出权限请求，
     * 绝不在此状态下以 BACKGROUND_ALLOWED 启动前台服务。
     */
    private suspend fun applyBackgroundPolicyGate(
        sessionId: String,
        session: Session,
        text: String,
        imageDataUrls: List<String>,
        documents: List<MessageDocumentAttachment>,
        quotes: List<MessageQuote> = emptyList(),
        assistantMessageIdsToReplace: Set<String> = emptySet(),
        reuseUserMessageId: String? = null,
        imageAnnotations: List<ImageAnnotation?> = emptyList(),
        imageAnnotationContext: ImageAnnotationContext? = null,
    ) {
        when (
            com.promenar.infinity.background.generation.resolveBackgroundGenerationPolicy(
                notificationPermissionGateway?.current(),
            )
        ) {
            com.promenar.infinity.background.generation.BackgroundGenerationPolicy.BACKGROUND_ALLOWED ->
                enqueuePreparedUserTurnAfterReplacement(
                    sessionId,
                    session,
                    text,
                    imageDataUrls,
                    documents,
                    backgroundAllowed = true,
                    assistantMessageIdsToReplace = assistantMessageIdsToReplace,
                    reuseUserMessageId = reuseUserMessageId,
                    quotes = quotes,
                    imageAnnotations = imageAnnotations,
                    imageAnnotationContext = imageAnnotationContext,
                )
            com.promenar.infinity.background.generation.BackgroundGenerationPolicy.FOREGROUND_ONLY ->
                enqueuePreparedUserTurnAfterReplacement(
                    sessionId,
                    session,
                    text,
                    imageDataUrls,
                    documents,
                    backgroundAllowed = false,
                    assistantMessageIdsToReplace = assistantMessageIdsToReplace,
                    reuseUserMessageId = reuseUserMessageId,
                    quotes = quotes,
                    imageAnnotations = imageAnnotations,
                    imageAnnotationContext = imageAnnotationContext,
                )
            com.promenar.infinity.background.generation.BackgroundGenerationPolicy.REQUIRES_PERMISSION -> {
                if (_currentSessionId.value != sessionId) {
                    restoreSubmittedDraft(sessionId)
                    return
                }
                val token = IdGenerator.message("perm")
                pendingPermissionTurn = PendingPermissionTurn(
                    token,
                    sessionId,
                    text,
                    imageDataUrls,
                    documents,
                    assistantMessageIdsToReplace,
                    reuseUserMessageId,
                    quotes,
                    imageAnnotations,
                    imageAnnotationContext,
                )
                mutableNotificationPermissionRequest.value = NotificationPermissionRequest(token)
                _isGenerating.value = false
                _generationStatus.value = GenerationStatus.IDLE
            }
        }
    }

    private fun resumePendingPermissionTurn(token: String, granted: Boolean) {
        val pending = pendingPermissionTurn ?: return
        if (pending.token != token) return
        pendingPermissionTurn = null
        clearNotificationPermissionRequestForTask(token)
        generationJobs[pending.sessionId] = viewModelScope.launch {
            try {
                sendPreparationMutex(pending.sessionId).withLock {
                    if (_currentSessionId.value != pending.sessionId) {
                        restoreSubmittedDraft(pending.sessionId, queueReason = QueuePauseReason.INTERRUPTED)
                        return@withLock
                    }
                    _generationStatus.update { GenerationStatus.UPLOADING }
                    _isGenerating.update { true }
                    _error.update { null }
                    _generationNotice.value = null
                    _backgroundServiceStoppedError.value = null
                    _backgroundWarning.value = null
                    _providerResolutionFailure.value = null
                    val session = store.getSession(pending.sessionId)
                    if (session == null) {
                        _isGenerating.update { false }
                        return@withLock
                    }
                    try {
                        enqueuePreparedUserTurnAfterReplacement(
                            pending.sessionId,
                            session,
                            pending.text,
                            pending.imageDataUrls,
                            pending.documents,
                            backgroundAllowed = granted,
                            assistantMessageIdsToReplace = pending.assistantMessageIdsToReplace,
                            reuseUserMessageId = pending.reuseUserMessageId,
                            quotes = pending.quotes,
                            imageAnnotations = pending.imageAnnotations,
                            imageAnnotationContext = pending.imageAnnotationContext,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (_currentSessionId.value == pending.sessionId) {
                            _error.update { "无法安全删除旧回复，已取消重试。" }
                            _generationStatus.update { GenerationStatus.ERROR }
                            _isGenerating.update { false }
                        }
                    }
                }
            } finally {
                if (generationPresentationStore.submittedDraft(pending.sessionId)?.taskId == null) {
                    withContext(NonCancellable) {
                        restoreSubmittedDraftFromPresentation(pending.sessionId)
                    }
                }
            }
        }
    }

    private suspend fun enqueuePreparedUserTurnAfterReplacement(
        sessionId: String,
        session: Session,
        text: String,
        imageDataUrls: List<String>,
        documents: List<MessageDocumentAttachment>,
        backgroundAllowed: Boolean,
        assistantMessageIdsToReplace: Set<String>,
        reuseUserMessageId: String?,
        quotes: List<MessageQuote> = emptyList(),
        imageAnnotations: List<ImageAnnotation?> = emptyList(),
        imageAnnotationContext: ImageAnnotationContext? = null,
    ) {
        if (reuseUserMessageId != null) {
            enqueuePreparedAssistantRetry(
                sessionId,
                session,
                reuseUserMessageId,
                text,
                backgroundAllowed,
                assistantMessageIdsToReplace,
            )
        } else {
            enqueuePreparedUserTurn(sessionId, session, text, imageDataUrls, documents, backgroundAllowed, quotes, imageAnnotations, imageAnnotationContext)
        }
    }

    private suspend fun enqueuePreparedAssistantRetry(
        sessionId: String,
        session: Session,
        userMessageId: String,
        text: String,
        backgroundAllowed: Boolean,
        assistantMessageIdsToReplace: Set<String>,
    ): Boolean = store.turnAdmission.withLock(sessionId) {
        val assistantMessageId = IdGenerator.message("ai")
        messageManager.addMessage(
            sessionId,
            Message(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                parentMessageId = userMessageId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        _replacingMessageIds.value = assistantMessageIdsToReplace
        val start = try {
            generateMessage(
                sessionId = sessionId,
                interventionContent = "",
                isResumption = false,
                existingAssistantMsgId = assistantMessageId,
                userMsgId = userMessageId,
                userContent = text,
                backgroundAllowed = backgroundAllowed,
                rollbackUserOnPreparationFailure = false,
                assistantMessageIdsToReplace = assistantMessageIdsToReplace,
            )
        } catch (failure: Throwable) {
            _replacingMessageIds.value = emptySet()
            withContext(NonCancellable) {
                runCatching {
                    messageManager.discardPreparedMessages(sessionId, listOf(assistantMessageId))
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        replacingTaskId = when (start) {
            is com.promenar.infinity.domain.generation.StartGenerationResult.Started -> start.taskId
            is com.promenar.infinity.domain.generation.StartGenerationResult.Queued -> start.taskId
            else -> null
        }
        if (replacingTaskId == null) _replacingMessageIds.value = emptySet()
        if (
            start is com.promenar.infinity.domain.generation.StartGenerationResult.Busy ||
            start is com.promenar.infinity.domain.generation.StartGenerationResult.Rejected ||
            start == null
        ) {
            messageManager.discardPreparedMessages(sessionId, listOf(assistantMessageId))
            _isGenerating.value = false
            _generationStatus.value = GenerationStatus.ERROR
            return@withLock false
        }
        return@withLock true
    }

    private fun uriToDataUrl(uri: Uri): String? =
        ChatImageEncoder.encodeToDataUrl { application.contentResolver.openInputStream(uri) }

    private suspend fun enqueuePreparedUserTurn(
        sessionId: String,
        session: Session,
        text: String,
        imageDataUrls: List<String>,
        documents: List<MessageDocumentAttachment>,
        backgroundAllowed: Boolean,
        quotes: List<MessageQuote> = emptyList(),
        imageAnnotations: List<ImageAnnotation?> = emptyList(),
        imageAnnotationContext: ImageAnnotationContext? = null,
    ) = store.turnAdmission.withLock(sessionId) {
        val userMsgId = IdGenerator.message("user")
        val assistantMsgId = IdGenerator.message("ai")
        val queuedSubmission = queueSubmissions[sessionId]
        if (queuedSubmission == null) {
            generationPresentationStore.submittedDraft(sessionId)?.copy(userMessageId = userMsgId)?.let { draft ->
                if (_currentSessionId.value == sessionId) submittedDraft = draft
                generationPresentationStore.retainSubmittedDraft(draft)
            }
        }
        queuedSubmission?.let { item ->
            queueUserMessageIds[sessionId] = userMsgId
            composerQueueStore.bindSending(sessionId, item.id, userMsgId)
        }

        try {
            val userMessage = Message(
                id = userMsgId,
                role = MessageRole.USER,
                content = text,
                userImages = imageDataUrls.ifEmpty { null },
                userImageAnnotations = imageAnnotations.takeIf { annotations -> annotations.any { it != null } },
                userImageAnnotationContext = imageAnnotationContext?.takeIf { imageAnnotations.any { it != null } },
                userDocuments = documents.ifEmpty { null },
                quotes = quotes.ifEmpty { null },
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, userMessage)

            val assistantMessage = Message(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                parentMessageId = userMsgId,
                createdAt = System.currentTimeMillis()
            )
            messageManager.addMessage(sessionId, assistantMessage)

            when (
                val start = generateMessage(
                    sessionId,
                    "",
                    false,
                    assistantMsgId,
                    userMsgId,
                    text,
                    backgroundAllowed,
                    rollbackUserOnPreparationFailure = true,
                    clearDraftOnConnecting = queuedSubmission == null,
                )
            ) {
                is com.promenar.infinity.domain.generation.StartGenerationResult.Started,
                is com.promenar.infinity.domain.generation.StartGenerationResult.Queued,
                is com.promenar.infinity.domain.generation.StartGenerationResult.Existing -> {
                    if (queuedSubmission != null) {
                        viewModelScope.launch {
                            composerQueueStore.observe(sessionId).first { it.sending?.id != queuedSubmission.id }
                            if (queueSubmissions[sessionId]?.id == queuedSubmission.id) {
                                queueSubmissions.remove(sessionId)
                                queueUserMessageIds.remove(sessionId)
                            }
                            if (queueTerminalPhases[sessionId] == GenerationPhase.COMPLETED) dispatchComposerQueue(sessionId)
                        }
                    } else if (
                        _currentSessionId.value == sessionId &&
                        pendingDraftClearTaskId != null &&
                        generationPresentationStore.observe(sessionId).value == null &&
                        generationCoordinator.observe(sessionId).value == null &&
                        messageRepository.getById(userMsgId) != null
                    ) {
                        pendingDraftClearTaskId = null
                        consumeAcceptedDraft(sessionId)
                    }
                }
                is com.promenar.infinity.domain.generation.StartGenerationResult.Busy,
                is com.promenar.infinity.domain.generation.StartGenerationResult.Rejected,
                null -> {
                    messageManager.discardPreparedMessages(
                        sessionId,
                        listOf(assistantMsgId, userMsgId),
                    )
                    if (_currentSessionId.value == sessionId) pendingDraftClearTaskId = null
                    restoreSubmittedDraft(sessionId, fallbackText = text,
                        queueReason = if (start is com.promenar.infinity.domain.generation.StartGenerationResult.Busy) QueuePauseReason.BUSY else QueuePauseReason.FAILED)
                    if (_currentSessionId.value == sessionId) {
                        _isGenerating.value = false
                        _generationStatus.value = GenerationStatus.ERROR
                        _generationNotice.value = when (start) {
                        is com.promenar.infinity.domain.generation.StartGenerationResult.Busy ->
                            GenerationFailureNotice.from(
                                com.promenar.infinity.domain.generation.GenerationFailure.busy(),
                            )
                        is com.promenar.infinity.domain.generation.StartGenerationResult.Rejected ->
                            GenerationFailureNotice.from(start.error.failure)
                        else -> GenerationFailureNotice.from(
                            com.promenar.infinity.domain.generation.GenerationFailure.unknown(
                                technical = "generation request did not start",
                            ),
                        )
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    messageManager.discardPreparedMessages(
                        sessionId,
                        listOf(assistantMsgId, userMsgId),
                    )
                }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { restoreSubmittedDraft(sessionId, fallbackText = text) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                if (_currentSessionId.value == sessionId) _isGenerating.value = false
            }
            throw failure
        }
    }

    /**
     * 新回合（发送、重新生成、删除待审批回复）开始前关闭仍在等待的审批：
     * 未决调用写入“已取消”结果，协议历史中每个工具调用都有对应结果。
     */
    private suspend fun closePendingApprovalBeforeNewTurn(sessionId: String) {
        val session = store.getSession(sessionId) ?: return
        if (session.approvalRequest == null && session.loopStatus !in setOf(
                com.promenar.infinity.data.model.LoopStatus.PAUSED,
                com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT,
                com.promenar.infinity.data.model.LoopStatus.INPUT_ANSWERED,
            )) return
        terminateSessionWork(sessionId)
    }

    /** 终止当前任务时同步审批终态，并在不可取消的 IO 清理中关闭该会话的 SSH owner。 */
    private suspend fun terminateSessionWork(sessionId: String) {
        // 准备锁等待期间可能登记了续跑任务，获锁后必须再次同步取消。
        generationCoordinator.observe(sessionId).value?.let {
            generationCoordinator.cancel(it.taskId, com.promenar.infinity.domain.generation.CancellationReason.USER)
        }
        try {
            if (store.getSession(sessionId)?.loopStatus in setOf(
                    com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT,
                    com.promenar.infinity.data.model.LoopStatus.INPUT_ANSWERED,
                ) || pendingQuestion.value?.sessionId == sessionId) {
                generationCoordinator.cancelAndJoinSession(sessionId, com.promenar.infinity.domain.generation.CancellationReason.USER)
                askUserLedger.cancel(sessionId).forEach { messageManager.mirrorPersistedMessage(sessionId, it) }
            }
            // 停止本轮时一并关闭仍在等待的保密输入卡，协调器把“已取消”交回 Agent。
            secretIo.cancelForSession(sessionId)
            if (store.getSession(sessionId)?.approvalRequest != null) {
                try {
                    approvalManager.cancelPendingApproval(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 台账无法写入取消结果时仍关闭请求，缺失结果由协议历史修复补齐。
                    InfinityLogger.logError("[ChatViewModel] close pending approval failed", error)
                }
            }
            approvalManager.setApprovalRequest(sessionId, null)
            approvalManager.setLoopStatus(sessionId, com.promenar.infinity.data.model.LoopStatus.PAUSED)
        } finally {
            if (!closeSessionSshOwner(sessionId)) throw CancellationException("SSH_CLEANUP_FAILED")
        }
    }

    private suspend fun closeSessionSshOwner(sessionId: String): Boolean =
        withContext(NonCancellable + sshCleanupDispatcher) {
            try {
                sshOwnerCloser("agent:$sessionId")
                true
            } catch (_: Exception) {
                _error.value = "SSH_CLEANUP_FAILED"
                false
            }
        }

    /** 审批返回后为待决定的资源登记租约；已终止的审批释放资源。 */
    internal suspend fun settleApprovalSshOwner(sessionId: String, expectedTaskId: String?) {
        val currentTaskId = generationCoordinator.observe(sessionId).value?.taskId
        if (currentTaskId != null && currentTaskId != expectedTaskId) return
        val session = store.getSession(sessionId) ?: return
        if (session.approvalRequest != null && session.loopStatus == com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_APPROVAL) {
            withContext(NonCancellable + sshCleanupDispatcher) {
                try {
                    sshOwnerPauser("agent:$sessionId")
                } catch (_: Exception) {
                    _error.value = "SSH_CLEANUP_FAILED"
                    closeSessionSshOwner(sessionId)
                }
            }
        } else if (session.approvalRequest == null && session.loopStatus == com.promenar.infinity.data.model.LoopStatus.PAUSED) {
            closeSessionSshOwner(sessionId)
        }
    }

    /** 仅供精确审批通过后的工具执行回调调用，执行前解除该 owner 的等待租约。 */
    internal suspend fun executeWithApprovedSshOwner(sessionId: String, execute: suspend () -> Unit) {
        withContext(sshCleanupDispatcher) {
            try {
                sshOwnerResumer("agent:$sessionId")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw com.promenar.infinity.data.ssh.SshFailure("SSH_RESUME_FAILED")
            }
        }
        execute()
    }

    /**
     * 审批决定完成后继续本轮：新建助手消息承接后续回答并携带本轮用户消息，
     * 发起工具调用的原消息与其工具结果保持不变，协议历史才能保持调用与结果成对。
     */
    internal suspend fun resumeAfterApproval(sessionId: String) {
        val session = store.getSession(sessionId) ?: return
        val userMessage = com.promenar.infinity.data.generation.generationResumption(session)?.user ?: return
        enqueuePreparedAssistantRetry(
            sessionId = sessionId,
            session = session,
            userMessageId = userMessage.id,
            text = userMessage.content,
            backgroundAllowed = currentBackgroundAllowed(),
            assistantMessageIdsToReplace = emptySet(),
        )
    }

    /** 返回值仅表示本地受理；数据库失败保留原提问并通过错误通道提示。 */
    fun answerQuestion(
        expected: com.promenar.infinity.domain.ask.PendingQuestion,
        answer: com.promenar.infinity.domain.ask.AskUserAnswer,
    ): Boolean {
        val frozenAnswer = answer.copy(answers = answer.answers.mapValues { it.value.toList() }.toMap())
        synchronized(questionSubmissionLock) {
            if (_currentSessionId.value != expected.sessionId || pendingQuestion.value != expected ||
                lastAnsweredQuestion == expected ||
                com.promenar.infinity.ui.chat.manager.AskUserValidation.answer(expected.request, frozenAnswer) != null ||
                !questionSubmissions.add(expected)) return false
        }
        viewModelScope.launch {
            try {
                sendPreparationMutex(expected.sessionId).withLock {
                    val result = askUserLedger.answer(expected, frozenAnswer) ?: return@launch
                    synchronized(questionSubmissionLock) { lastAnsweredQuestion = expected }
                    messageManager.mirrorPersistedMessage(expected.sessionId, result.result)
                    messageManager.mirrorPersistedMessage(expected.sessionId, result.assistant)
                    store.updateSession(expected.sessionId) { it.copy(loopStatus = com.promenar.infinity.data.model.LoopStatus.INPUT_ANSWERED) }
                }
                resumeAfterQuestion(expected.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = stringResourceResolver(R.string.ask_user_answer_failed)
            } finally {
                synchronized(questionSubmissionLock) { questionSubmissions.remove(expected) }
            }
        }
        return true
    }

    private suspend fun resumeAfterQuestion(sessionId: String) = questionResumeMutex(sessionId).withLock {
        val continuation = askUserLedger.resumption(sessionId) ?: return@withLock
        val task = generationCoordinator.observe(sessionId).value
        if (task?.assistantMessageId == continuation.assistant.id && task.phase !in TERMINAL_GENERATION_PHASES) return@withLock
        if (task?.phase == GenerationPhase.WAITING_INPUT) generationCoordinator.awaitSessionIdle(sessionId)
        else if (task != null && task.phase !in TERMINAL_GENERATION_PHASES) return@withLock
        sendPreparationMutex(sessionId).withLock questionStart@ {
            // 等待旧任务释放期间可能已经停止、删除或被其它操作处理，不能使用此前读出的对象启动。
            val current = askUserLedger.resumption(sessionId) ?: return@questionStart
            if (current.assistant.id != continuation.assistant.id || questionResumptionAttempts[sessionId] == current.assistant.id) return@questionStart
            questionResumptionAttempts[sessionId] = current.assistant.id
            messageManager.mirrorPersistedMessage(sessionId, current.result)
            messageManager.mirrorPersistedMessage(sessionId, current.assistant)
            try {
                val result = generateMessage(sessionId, current.user.content, isResumption = true,
                    existingAssistantMsgId = current.assistant.id, userMsgId = current.user.id,
                    userContent = current.user.content, generationRequestId = current.assistant.id)
                if (result is com.promenar.infinity.domain.generation.StartGenerationResult.Rejected) {
                    _error.value = stringResourceResolver(R.string.ask_user_resume_failed)
                } else if (result is com.promenar.infinity.domain.generation.StartGenerationResult.Busy) {
                    questionResumptionAttempts.remove(sessionId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = stringResourceResolver(R.string.ask_user_resume_failed)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun generateMessage(
        sessionId: String,
        interventionContent: String,
        isResumption: Boolean,
        existingAssistantMsgId: String? = null,
        userMsgId: String? = null,
        userContent: String? = null,
        backgroundAllowed: Boolean = currentBackgroundAllowed(),
        rollbackUserOnPreparationFailure: Boolean = false,
        clearDraftOnConnecting: Boolean = false,
        assistantMessageIdsToReplace: Set<String> = emptySet(),
        generationRequestId: String? = null,
    ): com.promenar.infinity.domain.generation.StartGenerationResult? {
        val session = store.getSession(sessionId) ?: return null
        val assistantMsgId = existingAssistantMsgId
            ?: session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
            ?: return null
        val effectiveUserContent = userContent
            ?: interventionContent.ifBlank { session.pendingIntervention }
            ?: session.messages.lastOrNull { it.role == MessageRole.USER }?.content
            ?: ""
        val request = com.promenar.infinity.domain.generation.GenerationRequest(
            sessionId = sessionId,
            assistantMessageId = assistantMsgId,
            userMessageId = userMsgId,
            userContent = effectiveUserContent,
            imageDataUrls = session.messages.lastOrNull { it.id == userMsgId }?.userImages.orEmpty(),
            runtimePolicy = if (backgroundAllowed) {
                com.promenar.infinity.domain.generation.GenerationRuntimePolicy.BACKGROUND_ALLOWED
            } else {
                com.promenar.infinity.domain.generation.GenerationRuntimePolicy.FOREGROUND_ONLY
            },
            rollbackUserOnPreparationFailure = rollbackUserOnPreparationFailure,
            messageIdsToReplace = assistantMessageIdsToReplace,
            requestId = generationRequestId ?: IdGenerator.message("generation"),
        )
        if (clearDraftOnConnecting && _currentSessionId.value == sessionId) {
            pendingDraftClearTaskId = DRAFT_PENDING_START
        }
        return generationCoordinator.start(request).also { result ->
            if (clearDraftOnConnecting) {
                val taskId = when (result) {
                    is com.promenar.infinity.domain.generation.StartGenerationResult.Started -> result.taskId
                    is com.promenar.infinity.domain.generation.StartGenerationResult.Queued -> result.taskId
                    is com.promenar.infinity.domain.generation.StartGenerationResult.Existing -> result.snapshot.taskId
                    else -> null
                }
                if (taskId != null) generationPresentationStore.submittedDraft(sessionId)?.copy(taskId = taskId)?.let { draft ->
                    if (_currentSessionId.value == sessionId) submittedDraft = draft
                    generationPresentationStore.retainSubmittedDraft(draft)
                }
                if (_currentSessionId.value == sessionId) {
                    if (pendingDraftClearTaskId == DRAFT_PENDING_START) pendingDraftClearTaskId = taskId
                    val presentation = generationPresentationStore.observe(sessionId).value
                    if (presentation?.taskId == pendingDraftClearTaskId && presentation?.promptAccepted == true) {
                        pendingDraftClearTaskId = null
                        consumeAcceptedDraft(sessionId)
                    } else if (presentation != null && presentation.taskId == pendingDraftClearTaskId &&
                        presentation.phase in DRAFT_SETTLING_PHASES
                    ) {
                        // 任务在 start 返回前就已在准备阶段结束，订阅方当时还无法匹配任务。
                        pendingDraftClearTaskId = null
                        if (submittedUserMessageRolledBack(sessionId)) restoreSubmittedDraft(sessionId)
                        else consumeAcceptedDraft(sessionId)
                    }
                }
            }
            when (result) {
            is com.promenar.infinity.domain.generation.StartGenerationResult.Started -> {
                if (backgroundAllowed) {
                    generationCoordinator.observe(sessionId).value
                        ?.takeIf { it.taskId == result.taskId }
                        ?.let(::trackForegroundGeneration)
                }
            }
            is com.promenar.infinity.domain.generation.StartGenerationResult.Queued -> {
                if (backgroundAllowed) {
                    generationCoordinator.observe(sessionId).value
                        ?.takeIf { it.taskId == result.taskId }
                        ?.let(::trackForegroundGeneration)
                }
            }
            is com.promenar.infinity.domain.generation.StartGenerationResult.Existing -> {
                if (backgroundAllowed) {
                    trackForegroundGeneration(result.snapshot)
                }
            }
            is com.promenar.infinity.domain.generation.StartGenerationResult.Busy -> {
                if (_currentSessionId.value == sessionId) _generationNotice.value = GenerationFailureNotice.from(
                    com.promenar.infinity.domain.generation.GenerationFailure.busy(),
                )
            }
            is com.promenar.infinity.domain.generation.StartGenerationResult.Rejected -> {
                if (_currentSessionId.value == sessionId) {
                    _generationNotice.value = GenerationFailureNotice.from(result.error.failure)
                    _generationStatus.value = GenerationStatus.ERROR
                }
            }
        }
        }
    }

    private fun trackForegroundGeneration(
        snapshot: com.promenar.infinity.domain.generation.GenerationTaskSnapshot,
    ) {
        val current = generationCoordinator.observe(snapshot.sessionId).value
        if (current?.taskId != snapshot.taskId || current.phase in FOREGROUND_STOPPING_PHASES) return
        val result = generationForegroundController.track(current)
        if (_currentSessionId.value != current.sessionId) return
        foregroundTrackedTaskId = current.taskId
        when (result) {
            com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithNotifications -> {
                clearBackgroundWarningForTask(current.taskId)
            }
            com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithoutNotifications -> {
                publishBackgroundWarning(current.taskId, BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE)
                mutableNotificationPermissionRequest.value =
                    NotificationPermissionRequest(current.taskId)
            }
            is com.promenar.infinity.background.generation.ForegroundStartResult.NotAllowed,
            is com.promenar.infinity.background.generation.ForegroundStartResult.PermissionDenied -> {
                publishBackgroundWarning(current.taskId, BackgroundWarningKind.FGS_UNAVAILABLE)
            }
        }
    }

    fun onNotificationPermissionResult(taskId: String, granted: Boolean) {
        val pending = pendingPermissionTurn
        if (pending != null && pending.token == taskId) {
            resumePendingPermissionTurn(taskId, granted)
            return
        }
        clearNotificationPermissionRequestForTask(taskId)
        if (granted) clearBackgroundWarningForTask(taskId)
    }

    fun clearBackgroundWarning(taskId: String) {
        clearBackgroundWarningForTask(taskId)
    }

    private fun publishBackgroundWarning(taskId: String, kind: BackgroundWarningKind) {
        _backgroundWarning.value = BackgroundGenerationWarning(
            taskId = taskId,
            kind = kind,
            message = stringResourceResolver(
                when (kind) {
                    BackgroundWarningKind.FGS_UNAVAILABLE -> R.string.generation_background_unavailable
                    BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE ->
                        R.string.generation_notifications_unavailable
                },
            ),
        )
    }

    private fun clearBackgroundWarningForTask(taskId: String) {
        if (_backgroundWarning.value?.taskId == taskId) _backgroundWarning.value = null
    }

    private fun clearNotificationPermissionRequestForTask(taskId: String) {
        if (mutableNotificationPermissionRequest.value?.taskId == taskId) {
            mutableNotificationPermissionRequest.value = null
        }
    }

    fun loadSession(sessionId: String) {
        (application as InfinityApplication).generationInteractions.selectSession(sessionId)
        viewModelScope.launch(Dispatchers.IO) { (application as InfinityApplication).checkSessionRunMode(sessionId) }
        // 折叠屏展开或旋转会让界面重建并重新载入同一会话：会话 id 不变时各收集器不会重启，
        // 输入、生成状态、提示与撤销都仍是最新的，不能清空后等待下一次事件再补回。
        val reattaching = _currentSessionId.value == sessionId
        val abandonedPermissionSession = pendingPermissionTurn?.sessionId?.takeIf { it != sessionId }
        val liveInput = _inputText.value.takeIf { reattaching }
        if (!reattaching) {
            saveCurrentDraft()
            _inputText.value = ""
            _draftDocuments.value = emptyList()
            _draftQuotes.value = emptyList()
            _restoredDraftImages.value = null
            composerImages = emptyList()
            resetSessionTransientState()
        }
        val existing = store.getSession(sessionId)
        if (existing != null) {
            if (existing.workspaceRootUuid.isNullOrBlank()) {
                viewModelScope.launch { ensureSessionWorkspace(sessionId) }
            }
            _inputText.value = liveInput ?: generationPresentationStore.composerText(sessionId) ?: existing.draft.orEmpty()
            if (!reattaching) restoreComposerAttachments(sessionId)
            _currentSessionId.update { sessionId }
            abandonedPermissionSession?.let(generationPresentationStore::markSubmittedDraftRecoverable)
            updateAgentName(existing.agentId)
            viewModelScope.launch { messageManager.reconcileTurnMemory(sessionId) }
            // 同步当前会话的 ragOptions 到缓存
            existing.ragOptions?.let { _currentRagOptions.value = it }
            // P0 持久化修复: 恢复历史检索指示器
            val lastMsg = existing.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
            if (!reattaching && lastMsg?.ragReferences.isNullOrEmpty() == false) {
                _ragPhases.update {
                    listOf(
                        RagPhase("retrieved", "已检索", PhaseStatus.DONE, 100,
                            "${lastMsg!!.ragReferences!!.size} 个来源")
                    )
                }
            }
            viewModelScope.launch { restoreFailedComposerDraft(sessionId) }
            return
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                val session = store.getOrLoadSession(sessionId) { sessionRepository.getById(sessionId) }
                if (session != null) {
                    var hydrated = session
                    if (hydrated.ragOptions == null) {
                        val defaultOptions = getDefaultRagOptions(session.agentId)
                        hydrated = hydrated.copy(ragOptions = defaultOptions)
                        sessionManager.updateSession(sessionId, mapOf("ragOptions" to defaultOptions))
                    }
                    if (hydrated.workspaceRootUuid.isNullOrBlank()) {
                        val root = resolveSessionWorkspace(sessionId)
                        if (root != null) {
                            hydrated = hydrated.copy(workspaceRootUuid = root.first, workspacePath = root.second)
                            sessionManager.updateSession(
                                sessionId,
                                mapOf("workspaceRootUuid" to root.first, "workspacePath" to root.second),
                            )
                        }
                    }
                    store.update { state ->
                        if (state.sessions.any { it.id == sessionId }) state
                        else state.copy(sessions = state.sessions + hydrated)
                    }
                    _inputText.value = liveInput ?: generationPresentationStore.composerText(sessionId) ?: hydrated.draft.orEmpty()
                    if (!reattaching) restoreComposerAttachments(sessionId)
                    _currentSessionId.update { sessionId }
                    updateAgentName(hydrated.agentId)
                    messageManager.reconcileTurnMemory(sessionId)
                    // 同步当前会话的 ragOptions 到缓存
                    hydrated.ragOptions?.let { _currentRagOptions.value = it }
                // P0 持久化修复: 若会话中已有检索结果，恢复指示器为历史完成态
                val lastAssistantMsg = hydrated.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
                if (lastAssistantMsg?.ragReferences.isNullOrEmpty() == false) {
                    _ragPhases.update {
                        listOf(
                            RagPhase("retrieved", "已检索", PhaseStatus.DONE, 100,
                                "${lastAssistantMsg!!.ragReferences!!.size} 个来源")
                        )
                    }
                }
                // 从 DB 恢复未发送的草稿
                    restoreFailedComposerDraft(sessionId)
                } else {
                    _error.update { "Session not found: $sessionId" }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _error.update { "Failed to load session: ${e.message}" }
            } finally {
                abandonedPermissionSession?.let(generationPresentationStore::markSubmittedDraftRecoverable)
                _isLoading.update { false }
            }
        }
    }

    private fun resetSessionTransientState() {
        submittedDraft = null
        replacingTaskId = null
        _replacingMessageIds.value = emptySet()
        _deletionUndo.value = null
        _streamingContent.value = ""
        _postProcessTasks.value = emptyList()
        _generationRetry.value = null
        _ragPhases.value = emptyList()
        _error.value = null
        _generationNotice.value = null
        _generationFailureSurface.value = null
        _backgroundServiceStoppedError.value = null
        _backgroundWarning.value = null
        foregroundTrackedTaskId = null
        pendingPermissionTurn = null
        pendingDraftClearTaskId = null
        mutableNotificationPermissionRequest.value = null
        _providerResolutionFailure.value = null
        _generationStatus.value = GenerationStatus.IDLE
        _isGenerating.value = false
    }

    private fun restoreHistoricalRagProjection(sessionId: String) {
        val lastMessage = store.getSession(sessionId)?.messages
            ?.lastOrNull { it.role == MessageRole.ASSISTANT && !it.ragReferences.isNullOrEmpty() }
            ?: return
        _ragPhases.value = listOf(
            RagPhase(
                "retrieved",
                "已检索",
                PhaseStatus.DONE,
                100,
                "${lastMessage.ragReferences!!.size} 个来源",
            ),
        )
    }

    fun createNewSession(agentId: String) {
        viewModelScope.launch {
            val sessionId = insertNewSession(agentId)
            _currentSessionId.update { sessionId }
            (application as InfinityApplication).generationInteractions.selectSession(sessionId)
            updateAgentName(agentId)
        }
    }

    /** 斜杠命令 /new：以当前会话的助手新建会话并返回其 ID；当前没有会话或写入失败时返回 null。 */
    suspend fun createSessionForCurrentAgent(): String? {
        val agentId = _currentSessionId.value?.let(store::getSession)?.agentId ?: return null
        return try {
            insertNewSession(agentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("ChatViewModel.createSessionForCurrentAgent", failure)
            null
        }
    }

    private suspend fun insertNewSession(agentId: String): String {
        val sessionId = IdGenerator.session()
        val agent = agentRepository.getById(agentId)
        val globalRagConfig = com.promenar.infinity.domain.usecase.RagConfigPersistence(
            application.getSharedPreferences("rag_settings", android.content.Context.MODE_PRIVATE),
        ).loadFullConfig()
        val session = com.promenar.infinity.domain.usecase.newSessionDefaults(
            sessionId, agentId, agent, resolveDefaultModelId(), globalRagConfig, System.currentTimeMillis(),
        )
        sessionManager.addSession(session)
        ensureSessionWorkspace(sessionId)
        return sessionId
    }

    private suspend fun ensureSessionWorkspace(sessionId: String) {
        val (rootUuid, rootPath) = resolveSessionWorkspace(sessionId) ?: return
        sessionManager.updateSession(
            sessionId,
            mapOf("workspaceRootUuid" to rootUuid, "workspacePath" to rootPath),
        )
    }

    /** 身份失败只阻断文件访问；已持久化的会话仍可打开并对话，取消继续向上传播。 */
    private suspend fun resolveSessionWorkspace(sessionId: String): Pair<String, String>? = try {
        sessionWorkspaceRoot(sessionId).also { workspaceErrors.update { errors -> errors - sessionId } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        InfinityLogger.logError("ChatViewModel.resolveSessionWorkspace", failure)
        val message = stringResourceResolver(R.string.legacy_root_marker_workspace_unavailable)
        workspaceErrors.update { it + (sessionId to message) }
        null
    }

    private fun resolveDefaultModelId(): String? {
        return runCatching {
            val providerManager = ProviderManager.getInstance()
            providerManager.summaryModelId.value.takeIf { it.isNotBlank() }
                ?: providerManager.getMainConfiguredModelId()
        }.getOrNull()
    }

    private fun updateAgentName(agentId: String?) {
        if (agentId == null) {
            _agentName.value = ""
            return
        }
        viewModelScope.launch {
            val agent = agentRepository.getById(agentId)
            _agentName.value = configResolver.resolveName(agent)
        }
    }

    fun stopGeneration() {
        _currentSessionId.value?.let { id ->
            stopAndSendSessions.remove(id)
            queuePauseOverrides[id] = QueuePauseReason.STOPPED
            viewModelScope.launch { composerQueueStore.pause(id, QueuePauseReason.STOPPED) }
        }
        cancelCurrentSessionGeneration()
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                sendPreparationMutex(sessionId).withLock {
                    terminateSessionWork(sessionId)
                }
            }
        }
    }

    private fun cancelPendingPreparation(sessionId: String) {
        queueDispatchJobs[sessionId]?.cancel()
        generationJobs.remove(sessionId)?.cancel()
        approvalJobs.remove(sessionId)?.cancel()
        if (pendingPermissionTurn?.sessionId == sessionId) {
            val pendingSession = pendingPermissionTurn?.sessionId
            pendingPermissionTurn = null
            if (pendingSession != null) {
                queueAction { restoreSubmittedDraft(pendingSession, queueReason = QueuePauseReason.INTERRUPTED) }
            }
            mutableNotificationPermissionRequest.value = null
            _isGenerating.value = false
            _generationStatus.value = GenerationStatus.IDLE
        }
    }

    private fun cancelCurrentSessionGeneration() {
        val sessionId = _currentSessionId.value ?: return
        cancelPendingPreparation(sessionId)
        generationCoordinator.observe(sessionId).value?.taskId?.let { taskId ->
            generationCoordinator.cancel(
                taskId,
                com.promenar.infinity.domain.generation.CancellationReason.USER,
            )
        }
    }

    fun retryLastMessage() {
        val sessionId = _currentSessionId.value ?: return
        val lastUserMsg = store.getSession(sessionId)?.messages?.lastOrNull { it.role == MessageRole.USER } ?: return
        regenerateMessage(lastUserMsg.id)
    }

    fun approveRequest(expectedRequest: ApprovalRequest, intervention: String? = null) {
        val sessionId = _currentSessionId.value ?: return
        submitApproval(sessionId, expectedRequest, approved = true, intervention = intervention)
    }

    /**
     * 批准当前批次中剩余的全部调用：按队列逐个精确批准，最后一个决定后由审批管理器统一继续生成。
     * 新一轮生成产生的审批（属于其它助手消息）不在本次批准范围内。
     */
    fun approveAllRequests(expectedRequest: ApprovalRequest) {
        submitBatchApproval(expectedRequest, timed = false)
    }

    fun approveWithTimedGrant(request: ApprovalRequest) {
        if (request.calls.firstOrNull()?.grantCategory == null) return
        submitBatchApproval(request, timed = true)
    }

    private fun submitBatchApproval(expectedRequest: ApprovalRequest, timed: Boolean) {
        val sessionId = _currentSessionId.value ?: return
        val approvalLock = approvalSubmissionMutex(sessionId)
        if (!approvalLock.tryLock()) return
        _isApprovalSubmitting.value = true
        val expectedTaskId = generationCoordinator.observe(sessionId).value?.taskId
        approvalJobs[sessionId] = viewModelScope.launch {
            try {
                if (timed) approvalManager.approveWithTimedGrant(sessionId, expectedRequest)
                else approvalManager.approveAll(sessionId, expectedRequest)
            } finally {
                try {
                    sendPreparationMutex(sessionId).withLock {
                        settleApprovalSshOwner(sessionId, expectedTaskId)
                    }
                } finally {
                    if (_currentSessionId.value == sessionId) _isApprovalSubmitting.value = false
                    approvalLock.unlock()
                }
            }
        }
    }

    fun rejectRequest(expectedRequest: ApprovalRequest) {
        val sessionId = _currentSessionId.value ?: return
        submitApproval(sessionId, expectedRequest, approved = false, intervention = null)
    }

    private fun submitApproval(
        sessionId: String,
        expectedRequest: ApprovalRequest,
        approved: Boolean,
        intervention: String?,
    ) {
        val approvalLock = approvalSubmissionMutex(sessionId)
        if (!approvalLock.tryLock()) return
        _isApprovalSubmitting.value = true
        val expectedTaskId = generationCoordinator.observe(sessionId).value?.taskId
        approvalJobs[sessionId] = viewModelScope.launch {
            try {
                approvalManager.resumeGeneration(
                    sessionId,
                    expectedRequest = expectedRequest,
                    approved = approved,
                    intervention = intervention,
                )
            } finally {
                try {
                    sendPreparationMutex(sessionId).withLock {
                        settleApprovalSshOwner(sessionId, expectedTaskId)
                    }
                } finally {
                    if (_currentSessionId.value == sessionId) _isApprovalSubmitting.value = false
                    approvalLock.unlock()
                }
            }
        }
    }

    fun updateExecutionMode(mode: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionExecutionMode(sessionId, mode)
        }
    }

    fun clearError() {
        _generationNotice.value = null
        _generationFailureSurface.value = null
        if (_backgroundServiceStoppedError.value != null) {
            _backgroundServiceStoppedError.value = null
            return
        }
        _currentSessionId.value?.let { sessionId ->
            workspaceErrors.update { it - sessionId }
            generationCoordinator.observe(sessionId).value
                ?.takeIf {
                    it.phase == com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED
                }
                ?.let { generationCoordinator.acknowledgeTerminal(it.taskId) }
        }
        _error.update { null }
        _providerResolutionFailure.value = null
        if (
            _generationStatus.value == GenerationStatus.ERROR &&
            _currentSessionId.value?.let { generationCoordinator.observe(it).value } == null
        ) {
            _generationStatus.value = GenerationStatus.IDLE
        }
    }

    fun regenerateLastMessage() {
        retryLastMessage()
    }

    private val _currentRagOptions = MutableStateFlow(com.promenar.infinity.data.model.RagOptions())
    val currentRagOptions: StateFlow<com.promenar.infinity.data.model.RagOptions> = _currentRagOptions

    fun updateRagOptions(options: com.promenar.infinity.data.model.RagOptions) {
        val sessionId = _currentSessionId.value ?: return
        _currentRagOptions.value = options  // 立即缓存，绕过 store 异步延迟
        InfinityLogger.log("[ChatViewModel] updateRagOptions: enableMemory=${options.enableMemory}, enableDocs=${options.enableDocs}, scope=${options.memoryScope}/${options.graphScope}/${options.documentScope}, enableRerank=${options.enableRerank}, enableKG=${options.enableKnowledgeGraph}")
        viewModelScope.launch {
            sessionManager.updateSession(sessionId, mapOf("ragOptions" to options))
        }
    }

    fun compactNow() {
        val sessionId = _currentSessionId.value ?: return
        if (_compressionState.value.isCompressing) return
        viewModelScope.launch {
            try {
                contextCompactionService.compactNow(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: com.promenar.infinity.data.generation.ContextCompactionBusyException) {
                if (_currentSessionId.value == sessionId) _compressionState.value = CompressionState(
                    detail = stringResourceResolver(R.string.chat_context_compress_unavailable_generating),
                )
            } catch (_: Exception) {
                if (_currentSessionId.value == sessionId) _compressionState.value = CompressionState(
                    detail = stringResourceResolver(R.string.chat_context_compaction_failed),
                )
            }
        }
    }

    private fun projectCompressionProgress(
        sessionId: String,
        progress: com.promenar.infinity.data.generation.ContextCompactionProgress,
    ): CompressionState {
        if (progress.isRunning) {
            val (fraction, resource) = when (progress.stage) {
                com.promenar.infinity.data.generation.ContextCompactionStage.FOLDING -> 0.3f to R.string.chat_context_folding
                com.promenar.infinity.data.generation.ContextCompactionStage.SUMMARIZING -> 0.7f to R.string.chat_context_summarizing
                else -> 0.1f to R.string.chat_summary_card_progress_preparing
            }
            return CompressionState(true, fraction, stringResourceResolver(resource))
        }
        if (progress.failed) return CompressionState(detail = stringResourceResolver(R.string.chat_context_compaction_failed))
        val result = progress.result ?: return CompressionState()
        val session = store.getSession(sessionId)
        val keep = com.promenar.infinity.domain.usecase.resolveKeepRecentTurns(
            session?.inferenceParams?.keepRecentTurns, session?.inferenceParams?.activeContextWindow,
        )
        return if (result.newlyFoldedToolResults == 0 && result.summarizedTurns == 0) {
            CompressionState(progress = 1f, detail = String.format(
                java.util.Locale.getDefault(), stringResourceResolver(R.string.chat_context_nothing_to_compress), keep,
            ))
        } else CompressionState(
            progress = 1f,
            result = String.format(
                java.util.Locale.getDefault(), stringResourceResolver(R.string.chat_context_compaction_result),
                result.newlyFoldedToolResults, result.summarizedTurns, result.beforeTokens, result.afterTokens,
            ) + session?.summary?.takeIf { result.summarizedTurns > 0 }?.let { "\n\n$it" }.orEmpty(),
        )
    }

    fun dismissCompressionResult() {
        _currentSessionId.value?.let(contextCompactionService::dismiss)
        _compressionState.value = CompressionState()
    }

    fun toggleTool(toolName: String, enabled: Boolean) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val options = session.options ?: SessionOptions()
        val nextOptions = when (toolName) {
            "timeInjection" -> options.copy(enableTimeInjection = enabled)
            "toolsEnabled" -> options.copy(toolsEnabled = enabled)
            "keepWebSearchWhenToolsOff" -> options.copy(keepWebSearchWhenToolsOff = enabled)
            "enableGeminiSearch" -> options.copy(enableGeminiSearch = enabled)
            "webSearch" -> options.copy(webSearch = enabled)
            else -> options
        }
        viewModelScope.launch {
            sessionManager.updateSessionOptions(sessionId, nextOptions)
        }
    }

    fun toggleSessionMcpServer(serverId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch { sessionManager.toggleMcpServer(sessionId, serverId) }
    }

    fun updateFontSize(size: Int) {
        fontSizePersistenceJob?.cancel()
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val options = session.options ?: SessionOptions()
        viewModelScope.launch {
            sessionManager.updateSessionOptions(sessionId, options.copy(fontSize = size))
        }
    }

    fun updateFontSizeLocally(size: Int) {
        val sessionId = _currentSessionId.value ?: return
        store.updateSession(sessionId) { s ->
            val options = s.options ?: SessionOptions()
            s.copy(options = options.copy(fontSize = size))
        }
        fontSizePersistenceJob?.cancel()
        fontSizePersistenceJob = viewModelScope.launch {
            delay(FONT_SIZE_PERSISTENCE_DEBOUNCE_MS)
            val latest = store.getSession(sessionId)?.options ?: SessionOptions()
            sessionManager.updateSessionOptions(sessionId, latest.copy(fontSize = size))
        }
    }

    fun updateSessionTitle(title: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionTitle(sessionId, title)
        }
    }

    /** 当前会话智能体预设的推理参数，供设置面板显示会话未覆盖项的实际取值。 */
    /** 当前会话所属助手的记忆权限，用于会话设置显示可选档位与上限。 */
    suspend fun agentMemoryPolicy(): com.promenar.infinity.data.agent.AgentMemoryPolicy {
        val agentId = _currentSessionId.value?.let(store::getSession)?.agentId
        return configResolver.resolve(agentId?.let { agentRepository.getById(it) }).memoryPolicy
    }

    /** 当前会话在三档对话范围下各可检索多少会话与助手，供会话设置说明范围。 */
    suspend fun conversationReachStats(): Map<ConversationScope, ConversationReachStats> {
        val sessionId = _currentSessionId.value ?: return emptyMap()
        val resolver = RetrievalScopeResolver((application as InfinityApplication).database)
        return ConversationScope.entries.associateWith { scope ->
            resolver.stats(resolver.resolve(sessionId, RagOptions(memoryScope = scope)).memory)
        }
    }

    private val sessionGraphPort by lazy { sessionGraphPortOverride ?: (application as InfinityApplication).sessionGraphPort }

    /** 当前会话每轮的图谱抽取状态，键为该轮最后一条助手消息。只在界面订阅时读取数据库。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val turnGraphStates: StateFlow<Map<String, com.promenar.infinity.data.rag.TurnGraphState>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyMap()) else sessionGraphPort.observeTurns(sessionId)
        }
        .catch { error ->
            InfinityLogger.logError("[ChatViewModel] 读取会话图谱状态失败", error)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 会话设置中的后台抽取队列；开关关闭时为 null。 */
    val sessionGraphQueue: StateFlow<com.promenar.infinity.data.rag.SessionGraphQueueStatus?> =
        flow { emitAll(sessionGraphPort.observeQueue()) }
            .catch { error ->
                InfinityLogger.logError("[ChatViewModel] 读取会话图谱队列失败", error)
                emit(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun loadTurnGraph(assistantMessageId: String): com.promenar.infinity.data.rag.TurnGraphDetails =
        sessionGraphPort.turnDetails(assistantMessageId)

    /** 按当前正文重新排入这一轮的图谱抽取。 */
    fun retryTurnGraph(assistantMessageId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                sessionGraphPort.retry(sessionId, assistantMessageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[ChatViewModel] 重新排入会话图谱抽取失败", error)
            }
        }
    }

    suspend fun loadTurnMemory(assistantMessageId: String): TurnMemoryDetails {
        val sessionId = _currentSessionId.value ?: return TurnMemoryDetails(emptyList(), true)
        val chunks = withContext(Dispatchers.IO) {
            (application as InfinityApplication).database.vectorDao()
                .getMemoryByAssistantMessage(sessionId, assistantMessageId)
                .sortedWith(compareBy({ it.createdAt }, { memoryChunkIndex(it.metadata) }))
                .map { it.content }
        }
        return TurnMemoryDetails(chunks, agentMemoryPolicy().shareWithOtherAgents)
    }

    /** 只从记忆中移除这一轮，消息保留；之后可在面板中重新记入。 */
    fun removeTurnMemory(assistantMessageId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                (application as InfinityApplication).conversationMemoryLifecycle.removeTurn(sessionId, assistantMessageId)
                messageManager.setTurnMemoryState(sessionId, assistantMessageId,
                    TurnMemoryState(TurnMemoryState.Status.REMOVED, updatedAt = System.currentTimeMillis()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[ChatViewModel] 移除本轮记忆失败", error)
                _error.value = application.getString(R.string.turn_memory_remove_failed)
            }
        }
    }

    /** 按当前正文重新写入这一轮的记忆，用于写入失败后重试或移除后重新记入。 */
    fun rewriteTurnMemory(assistantMessageId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val app = application as InfinityApplication
            val previous = store.getSession(sessionId)?.messages?.firstOrNull { it.id == assistantMessageId }?.turnMemory
            messageManager.writeTurnMemory(sessionId, assistantMessageId, previous) {
                check(app.memoryManager.canEmbed) { "未配置嵌入模型" }
                val written = app.conversationMemoryLifecycle.rewriteTurn(sessionId, assistantMessageId)
                TurnMemoryState(
                    status = if (written > 0) TurnMemoryState.Status.STORED else TurnMemoryState.Status.FAILED,
                    chunkCount = written,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun agentInferenceDefaults(): InferenceParams {
        val agentId = _currentSessionId.value?.let(store::getSession)?.agentId
        val agent = agentId?.let { agentRepository.getById(it) }
        return configResolver.resolve(agent).inferenceDefaults
    }

    fun updateInferenceParams(params: InferenceParams) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionInferenceParams(sessionId, params)
        }
    }

    /** 当前会话所属助手保存的推理强度（原始取值），输入区胶囊在会话未设置时沿用。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val agentReasoningEffort: StateFlow<String?> = _currentSessionId
        .flatMapLatest { sessionId ->
            val agentId = sessionId?.let(store::getSession)?.agentId
            if (agentId == null) flowOf(null) else agentRepository.observeById(agentId).map { it?.reasoningEffort }
        }
        .catch { error ->
            InfinityLogger.logError("[ChatViewModel] 读取助手推理强度失败", error)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val parameterSchemeRepository by lazy {
        parameterSchemeRepositoryOverride ?: (application as InfinityApplication).parameterSchemeRepository
    }

    /** 当前模型的已存方案；读取失败时视为没有方案。 */
    fun parameterSchemes(modelKey: String): kotlinx.coroutines.flow.Flow<List<com.promenar.infinity.data.model.params.ParameterScheme>> =
        flow { emitAll(parameterSchemeRepository.observeForModel(modelKey)) }
            .catch { error ->
                InfinityLogger.logError("[ChatViewModel] 读取参数方案失败", error)
                emit(emptyList())
            }

    private fun editSessionParams(transform: suspend (InferenceParams, InferenceParams) -> InferenceParams?) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = store.getSession(sessionId) ?: return@launch
            val next = transform(session.inferenceParams ?: InferenceParams(), agentInferenceDefaults()) ?: return@launch
            sessionManager.updateSessionInferenceParams(sessionId, next)
        }
    }

    fun applyModelDefaultSampling() = editSessionParams { params, _ -> params.withModelDefaultSampling() }

    fun applyParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme) =
        editSessionParams { params, _ -> params.withScheme(scheme) }

    fun updateSamplingParam(param: com.promenar.infinity.data.model.params.SamplingParam, value: Double?) =
        editSessionParams { params, agent ->
            params.withSamplingParam(param, value, agent.sampling)
        }

    /** 把当前取值存为本模型的新方案，会话随即选用它。 */
    fun saveParameterScheme(modelKey: String, name: String, values: com.promenar.infinity.data.model.params.SamplingValues) =
        editSessionParams { params, _ ->
            params.withScheme(parameterSchemeRepository.create(modelKey, name, values))
        }

    fun overwriteParameterScheme(
        scheme: com.promenar.infinity.data.model.params.ParameterScheme,
        values: com.promenar.infinity.data.model.params.SamplingValues,
    ) = editSessionParams { params, _ ->
        if (parameterSchemeRepository.overwrite(scheme.id, values)) params.withScheme(scheme.copy(values = values)) else null
    }

    fun renameParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme, name: String) {
        viewModelScope.launch { parameterSchemeRepository.rename(scheme.id, name) }
    }

    /** 删除方案不改动正在使用它的会话取值。 */
    fun deleteParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme) {
        viewModelScope.launch { parameterSchemeRepository.delete(scheme.id) }
    }

    /** 输入区选择推理强度，只写入当前会话；null 为模型默认。 */
    fun updateReasoningEffort(effort: com.promenar.infinity.data.model.params.ReasoningEffort?) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = store.getSession(sessionId) ?: return@launch
            val agentValue = session.agentId?.let { agentRepository.getById(it) }?.reasoningEffort
            val params = (session.inferenceParams ?: InferenceParams())
                .copy(reasoningEffort = sessionReasoningEffortFor(effort, agentValue))
            sessionManager.updateSessionInferenceParams(sessionId, params)
        }
    }


    fun updateModelId(modelId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionModel(sessionId, modelId)
        }
    }

    private val _workspaceSwitchNotice = MutableStateFlow<com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched?>(null)
    /** 最近一次成功切换的前后工作区；只属于当前会话，切换会话时界面按 sessionId 过滤。 */
    val workspaceSwitchNotice: StateFlow<com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched?> =
        _workspaceSwitchNotice

    /** 生成进行中或有待审批的工具调用时禁止切换工作区：本轮执行目标已按原工作区解析。 */
    fun isWorkspaceSwitchBlocked(): Boolean {
        val sessionId = _currentSessionId.value ?: return true
        val phase = generationCoordinator.observe(sessionId).value?.phase
        val running = phase != null && phase !in setOf(
            com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED,
            com.promenar.infinity.domain.generation.GenerationPhase.FAILED,
            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED,
            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
        )
        return sessionId in annotationSavingSessions || _isGenerating.value || running || store.getSession(sessionId)?.approvalRequest != null
    }

    /** 切换当前会话的工作区；[workspaceId] 为 null 时改回会话私有工作区。不改动任何目录内容。 */
    fun switchSessionWorkspace(
        workspaceId: String?,
        onResult: (com.promenar.infinity.data.workspace.WorkspaceSwitchResult) -> Unit = {},
    ) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val lookup = workspaceLookupOverride
                ?: { id: String -> (application as InfinityApplication).database.workspaceDao().getById(id) }
            val result = com.promenar.infinity.data.workspace.switchSessionWorkspace(
                sessionId = sessionId,
                currentWorkspaceId = store.getSession(sessionId)?.workspaceId,
                targetWorkspaceId = workspaceId,
                busy = isWorkspaceSwitchBlocked(),
                persist = { target -> sessionManager.updateSession(sessionId, mapOf("workspaceId" to target)) },
                lookup = lookup,
            )
            if (result is com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched) {
                _workspaceSwitchNotice.value = result
                // 聊天流里留下可见记录；该消息不进入模型上下文。
                runCatching {
                    messageManager.addMessage(
                        sessionId,
                        com.promenar.infinity.data.workspace.WorkspaceSwitchNotice.create(
                            (workspaceSwitchNoticeTextOverride ?: ::workspaceSwitchNoticeText)(result.from, result.to),
                        ),
                    )
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
            onResult(result)
        }
    }

    private fun workspaceSwitchNoticeText(
        from: com.promenar.infinity.data.workspace.WorkspaceLabel,
        to: com.promenar.infinity.data.workspace.WorkspaceLabel,
    ): String = application.getString(R.string.workspace_switch_notice, workspaceLabelText(from), workspaceLabelText(to))

    private fun workspaceLabelText(label: com.promenar.infinity.data.workspace.WorkspaceLabel): String = when (label) {
        com.promenar.infinity.data.workspace.WorkspaceLabel.Private -> application.getString(R.string.workspace_kind_private)
        is com.promenar.infinity.data.workspace.WorkspaceLabel.Registered -> label.name
        is com.promenar.infinity.data.workspace.WorkspaceLabel.Missing -> application.getString(R.string.workspace_missing_name)
    }

    fun updateCustomPrompt(prompt: String) {
        viewModelScope.launch {
            saveCustomPrompt(prompt)
        }
    }

    /** 仅在仓库确认成功后更新内存 Store，供 Prompt 编辑器决定是否关闭。 */
    suspend fun saveCustomPrompt(prompt: String): Result<Unit> {
        val sessionId = _currentSessionId.value
            ?: return Result.failure(IllegalStateException("会话尚未加载"))
        return try {
            sessionRepository.updatePartial(sessionId, mapOf("customPrompt" to prompt))
            store.updateSession(sessionId) { session -> session.copy(customPrompt = prompt) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }




    private val lastDeletedMessages = mutableListOf<Pair<String, Message>>()
    private var lastDeletedStateVersions = emptyList<com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity>()

    fun undoLastDeletion() {
        _deletionUndo.value = null
        if (lastDeletedMessages.isEmpty()) return
        val stateVersions = lastDeletedStateVersions
        lastDeletedStateVersions = emptyList()
        val restored = lastDeletedMessages.toList()
        lastDeletedMessages.clear()
        viewModelScope.launch {
            restored.groupBy({ it.first }, { it.second }).forEach { (sid, messages) ->
                messageManager.restoreMessages(sid, messages.sortedBy { it.createdAt })
                stateBoardRepository.restoreVersions(stateVersions.filter { it.sessionId == sid })
            }
        }
    }

    private val _taskCompletionUndo = MutableStateFlow<TaskCompletionUndo?>(null)
    val taskCompletionUndo: StateFlow<TaskCompletionUndo?> = _taskCompletionUndo

    /** 把当前计划未完成的步骤全部标记为完成，并提供撤销；生成中不可用。 */
    fun completeActivePlan() {
        val sessionId = _currentSessionId.value ?: return
        if (_isGenerating.value) return
        viewModelScope.launch {
            val previous = try {
                taskPlanActions.completeRemaining(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("ChatViewModel.completeActivePlan", error)
                null
            } ?: return@launch
            _taskCompletionUndo.value = TaskCompletionUndo(IdGenerator.message("undo"), sessionId, previous)
        }
    }

    fun undoTaskCompletion() {
        val undo = _taskCompletionUndo.value ?: return
        _taskCompletionUndo.value = null
        viewModelScope.launch {
            try {
                taskPlanActions.restore(undo.sessionId, undo.previousStatuses)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("ChatViewModel.undoTaskCompletion", error)
            }
        }
    }

    fun dismissTaskCompletionUndo(token: String) {
        if (_taskCompletionUndo.value?.token == token) _taskCompletionUndo.value = null
    }

    /** 清除已全部完成的计划，面板随之收起。 */
    fun clearCompletedPlan() {
        val sessionId = _currentSessionId.value ?: return
        _taskCompletionUndo.value = null
        viewModelScope.launch {
            try {
                taskPlanActions.clearCompleted(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("ChatViewModel.clearCompletedPlan", error)
            }
        }
    }

    private fun retainComposerAttachments(sessionId: String) {
        generationPresentationStore.retainComposerAttachments(
            sessionId,
            com.promenar.infinity.data.generation.SessionComposerAttachments(
                documents = _draftDocuments.value,
                imageUris = (composerImages.map(Uri::toString) + _restoredDraftImages.value.orEmpty()).distinct(),
                quotes = _draftQuotes.value,
            ),
        )
    }

    private fun restoreComposerAttachments(sessionId: String) {
        val attachments = generationPresentationStore.composerAttachments(sessionId)
        _draftDocuments.value = attachments?.documents.orEmpty()
        _draftQuotes.value = attachments?.quotes.orEmpty()
        composerImages = attachments?.imageUris.orEmpty().map(Uri::parse)
        _restoredDraftImages.value = attachments?.imageUris?.takeIf { it.isNotEmpty() }
    }

    /**
     * 离开会话时调用，ViewModel 随后就会被清理：先同步改内存会话（重新进入读的是它），
     * 再不可取消地落库；否则写库途中作用域被取消，手动删掉的草稿会在重新进入时复活。
     */
    fun saveCurrentDraft() {
        val sessionId = _currentSessionId.value ?: return
        retainComposerAttachments(sessionId)
        val draft = _inputText.value.ifBlank { null }
        generationPresentationStore.retainComposerText(sessionId, draft.orEmpty())
        store.updateSession(sessionId) { it.copy(draft = draft) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                generationPresentationStore.withDraftRecovery(sessionId) {
                    sessionManager.updateSessionDraft(sessionId, draft)
                }
            }
        }
    }

    override fun onCleared() {
        val abandonedPermissionSession = pendingPermissionTurn?.sessionId
        _currentSessionId.value?.let(::retainComposerAttachments)
        directoryBusyRegistration?.close()
        super.onCleared()
        abandonedPermissionSession?.let(generationPresentationStore::markSubmittedDraftRecoverable)
        // 生成由应用级 owner 持有；ViewModel 销毁只移除观察者，不终止请求。
    }

    /**
     * 删除消息。助手回复按整轮删除（同一气泡中的全部助手与工具消息），用户消息连同其回复一起删除；
     * 删除后发布可撤销提示。
     */
    fun deleteMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val session = store.getSession(sessionId) ?: return
        val target = session.messages.find { it.id == messageId } ?: return
        val targetIds = if (target.role == MessageRole.USER) {
            listOf(messageId)
        } else {
            buildPipelineGroups(session.messages)
                .firstOrNull { group -> !group.isUser && group.messages.any { it.id == messageId } }
                ?.messages?.map { it.id }
                ?: listOf(messageId)
        }

        viewModelScope.launch {
            val approvalTarget = store.getSession(sessionId)?.approvalRequest?.assistantMessageId
            val questionTarget = askUserLedger.pending(sessionId)?.assistantMessageId
            val expanded = targetIds.flatMapTo(linkedSetOf()) { messageRemovalIds(session.messages, it) }
            if ((approvalTarget != null && approvalTarget in expanded) || (questionTarget != null && questionTarget in expanded)) {
                sendPreparationMutex(sessionId).withLock {
                    closePendingApprovalBeforeNewTurn(sessionId)
                }
            }
            val current = store.getSession(sessionId)?.messages.orEmpty()
            val removalIds = targetIds.flatMapTo(linkedSetOf()) { messageRemovalIds(current, it) }
            val messagesToRestore = current.filter { it.id in removalIds }
            val stateVersions = messageManager.deleteMessagesWithStateUndo(sessionId, targetIds) { targetSession ->
                if (_currentSessionId.value == targetSession) stopGeneration()
                else {
                    queuePauseOverrides[targetSession] = QueuePauseReason.STOPPED
                    generationCoordinator.observe(targetSession).value?.taskId?.let { generationCoordinator.cancel(it) }
                    viewModelScope.launch {
                        composerQueueStore.pause(targetSession, QueuePauseReason.STOPPED)
                        sendPreparationMutex(targetSession).withLock { terminateSessionWork(targetSession) }
                    }
                }
            }
            lastDeletedStateVersions = stateVersions
            lastDeletedMessages.clear()
            messagesToRestore.forEach { lastDeletedMessages.add(sessionId to it) }
            _deletionUndo.value = DeletionUndo(
                token = IdGenerator.message("undo"),
                count = buildPipelineGroups(messagesToRestore).size,
                withMemory = messagesToRestore.any { it.turnMemory?.status == TurnMemoryState.Status.STORED },
            )
        }
    }

    fun dismissDeletionUndo(token: String) {
        if (_deletionUndo.value?.token == token) _deletionUndo.value = null
    }

    fun updateMessageContentOnly(messageId: String, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                messageManager.editMessageContent(sessionId, messageId, newContent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_currentSessionId.value == sessionId) {
                    _error.value = stringResourceResolver(R.string.chat_message_edit_failed)
                }
            }
        }
    }

    /**
     * 从 [messageId] 所在轮次的用户消息重新生成。该用户消息之后的全部消息先隐藏，
     * 新回复成功（或进入等待审批）后才删除；启动失败、准备失败或取消时旧消息原样恢复。
     */
    fun regenerateMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        val initial = store.getSession(sessionId) ?: return
        val index = initial.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val userIndex = if (initial.messages[index].role == MessageRole.USER) {
            index
        } else {
            initial.messages.subList(0, index).indexOfLast { it.role == MessageRole.USER }
        }
        if (userIndex < 0) return
        val userMessage = initial.messages[userIndex]

        cancelCurrentSessionGeneration()
        generationJobs[sessionId] = viewModelScope.launch {
            sendPreparationMutex(sessionId).withLock {
                try {
                    closePendingApprovalBeforeNewTurn(sessionId)
                    val session = store.getSession(sessionId) ?: return@withLock
                    val currentIndex = session.messages.indexOfFirst { it.id == userMessage.id }
                    if (currentIndex < 0) return@withLock
                    val replacedIds = session.messages.drop(currentIndex + 1).mapTo(linkedSetOf()) { it.id }
                    applyBackgroundPolicyGate(
                        sessionId,
                        session,
                        userMessage.content,
                        userMessage.userImages.orEmpty(),
                        userMessage.userDocuments.orEmpty(),
                        quotes = userMessage.quotes.orEmpty(),
                        assistantMessageIdsToReplace = replacedIds,
                        reuseUserMessageId = userMessage.id,
                        imageAnnotations = userMessage.userImageAnnotations.orEmpty(),
                        imageAnnotationContext = userMessage.userImageAnnotationContext,
                    )
                } catch (cancelled: CancellationException) {
                    _replacingMessageIds.value = emptySet()
                    throw cancelled
                } catch (_: Exception) {
                    _replacingMessageIds.value = emptySet()
                    _error.update { "无法安全删除旧回复，已取消重试。" }
                    _generationStatus.update { GenerationStatus.ERROR }
                    _isGenerating.update { false }
                }
            }
        }
    }

    fun clearHistory() {
        val sessionId = _currentSessionId.value ?: return
        cancelCurrentSessionGeneration()
        viewModelScope.launch {
            sendPreparationMutex(sessionId).withLock {
                terminateSessionWork(sessionId)
                messageManager.clearMessages(sessionId)
            }
        }
    }

    fun renameSession(newName: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionManager.updateSessionTitle(sessionId, newName)
        }
    }

    fun deleteSession(onDeleted: () -> Unit = {}, keepFiles: Boolean = false) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val failure = SessionDeletionUiAction {
                val result = sessionManager.deleteSession(sessionId, keepFiles)
                com.promenar.infinity.ui.workspace.announceKeptFiles(appContext, result)
                generationCoordinator.release(sessionId, discardTerminal = true)
                generationPresentationStore.release(sessionId)
            }.run(onDeleted)
            if (failure != null) {
                // 审计缺陷 P1-10：失败码与根因原先被吞掉，仅剩通用文案，无法定位删除链断点
                InfinityLogger.logError("ChatViewModel", failure)
                _error.value = stringResourceResolver(R.string.chat_delete_session_failed)
            }
        }
    }

    suspend fun exportSession(
        sessionId: String,
        format: ExportSessionUseCase.Format
    ): ExportSessionUseCase.ExportResult {
        val useCase = exportSessionUseCase ?: throw IllegalStateException("ExportSessionUseCase not available")
        return useCase.export(sessionId, format)
    }

    suspend fun branchFromMessage(messageId: String): BranchSessionResult {
        val sessionId = _currentSessionId.value
            ?: return BranchSessionResult.Rejected(
                com.promenar.infinity.data.session.BranchSessionRejectReason.SourceNotFound,
            )
        val useCase = branchSessionUseCase
            ?: return BranchSessionResult.Rejected(
                com.promenar.infinity.data.session.BranchSessionRejectReason.SourceNotFound,
            )
        return useCase.branch(sessionId, messageId)
    }

    /** 点击发送即清空输入框与附件，保留副本直到生成接受或回滚；持久化草稿在接受后才清除。 */
    private fun takeDraftForSending(sessionId: String, text: String, imageUris: List<Uri>): String {
        queueTerminalPhases.remove(sessionId)
        stopAndSendSessions.remove(sessionId)
        queuePauseOverrides.remove(sessionId)
        val draft = com.promenar.infinity.data.generation.SubmittedGenerationDraft(
            sessionId = sessionId,
            text = _inputText.value.ifBlank { text },
            documents = _draftDocuments.value,
            imageUris = imageUris.map(Uri::toString),
            quotes = ComposerQuotes.toMessageQuotes(_draftQuotes.value),
        )
        submittedDraft = draft
        generationPresentationStore.retainSubmittedDraft(draft)
        generationPresentationStore.discardComposerAttachments(sessionId)
        _inputText.value = ""
        _draftDocuments.value = emptyList()
        _draftQuotes.value = emptyList()
        _restoredDraftImages.value = null
        composerImages = emptyList()
        _draftConsumptionEpoch.value += 1
        return draft.submissionId
    }

    private fun submittedUserMessageRolledBack(sessionId: String): Boolean {
        val messageId = queueUserMessageIds[sessionId] ?: generationPresentationStore.submittedDraft(sessionId)?.userMessageId ?: return true
        return store.getSession(sessionId)?.messages?.none { it.id == messageId } ?: true
    }

    private suspend fun restoreSubmittedDraftFromPresentation(sessionId: String) {
        try {
            restoreSubmittedDraft(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (_currentSessionId.value == sessionId) _error.value = stringResourceResolver(R.string.chat_queue_error_draft_after_restore)
        }
    }

    /** 与用户随后输入的内容合并，成功保存后才移除待恢复副本。 */
    private suspend fun restoreSubmittedDraft(
        sessionId: String,
        fallbackText: String = "",
        queueReason: QueuePauseReason = QueuePauseReason.FAILED,
        expectedSubmissionId: String? = null,
    ) = generationPresentationStore.withDraftRecovery(sessionId) {
        queueSubmissions[sessionId]?.let { item ->
            val reason = composerQueueStore.snapshot(sessionId).pauseReason ?: queueReason
            composerQueueStore.rollback(sessionId, item.id, reason)
            queueSubmissions.remove(sessionId)
            queueUserMessageIds.remove(sessionId)
            return@withDraftRecovery
        }
        val draft = generationPresentationStore.submittedDraft(sessionId)
            ?: return@withDraftRecovery
        if (expectedSubmissionId != null && draft.submissionId != expectedSubmissionId) return@withDraftRecovery
        if (_currentSessionId.value != sessionId || !viewModelScope.isActive) {
            generationPresentationStore.markSubmittedDraftRecoverable(sessionId)
            return@withDraftRecovery
        }
        if (!draft.recoveryApplied) {
            val text = draft.text.ifBlank { fallbackText }
            if (text.isNotBlank()) _inputText.value = listOf(text, _inputText.value).filter { it.isNotBlank() }.joinToString("\n\n")
            _draftDocuments.value = (draft.documents + _draftDocuments.value).distinctBy { it.id }
            _draftQuotes.value = ComposerQuotes.merge(ComposerQuotes.fromMessageQuotes(draft.quotes), _draftQuotes.value)
            val images = (draft.imageUris + composerImages.map(Uri::toString) + _restoredDraftImages.value.orEmpty()).distinct()
            if (images.isNotEmpty()) {
                composerImages = images.map(Uri::parse)
                _restoredDraftImages.value = images
            }
            generationPresentationStore.retainSubmittedDraft(draft.copy(recoveryApplied = true))
        }
        try {
            sessionManager.updateSessionDraft(sessionId, _inputText.value.ifBlank { null })
        } catch (failure: Throwable) {
            generationPresentationStore.markSubmittedDraftRecoverable(sessionId)
            throw failure
        }
        if (_currentSessionId.value != sessionId || !viewModelScope.isActive) {
            generationPresentationStore.markSubmittedDraftRecoverable(sessionId)
            return@withDraftRecovery
        }
        retainComposerAttachments(sessionId)
        generationPresentationStore.submittedDraft(sessionId)?.takeIf { it.submissionId == draft.submissionId }
            ?.let(generationPresentationStore::discardSubmittedDraft)
        if (submittedDraft?.submissionId == draft.submissionId) submittedDraft = null
    }

    fun consumeRestoredDraftImages() {
        _restoredDraftImages.value = null
    }

    /**
     * 生成已接受：发送时已清空过输入框的，只把此后新输入的内容落为会话草稿，不再清空，
     * 以免吞掉生成期间新输入的文字与附件；没有发送副本的路径仍按原样清空。
     */
    private suspend fun consumeAcceptedDraft(sessionId: String) {
        queueSubmissions[sessionId]?.let { item ->
            composerQueueStore.accept(sessionId, item.id)
            queueSubmissions.remove(sessionId)
            queueUserMessageIds.remove(sessionId)
            if (queueTerminalPhases[sessionId] == GenerationPhase.COMPLETED) dispatchComposerQueue(sessionId)
            return
        }
        val draftImages = submittedDraft?.takeIf { it.sessionId == sessionId }?.imageUris.orEmpty()
        composerQueueStore.releaseDraftImages(sessionId, draftImages.mapNotNull { Uri.parse(it).path })
        val clearedOnSend = submittedDraft?.sessionId == sessionId
        submittedDraft?.takeIf { it.sessionId == sessionId }?.let(generationPresentationStore::discardSubmittedDraft)
        submittedDraft = null
        if (clearedOnSend) {
            sessionManager.updateSessionDraft(sessionId, _inputText.value.ifBlank { null })
            if (queueTerminalPhases[sessionId] == GenerationPhase.COMPLETED) dispatchComposerQueue(sessionId)
            return
        }
        _inputText.value = ""
        _draftDocuments.value = emptyList()
        _draftQuotes.value = emptyList()
        _draftConsumptionEpoch.value += 1
        sessionManager.updateSessionDraft(sessionId, null)
    }

    companion object {
        private val QUEUE_INACTIVE_PHASES = setOf(
            GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.PERSISTENCE_FAILED,
            GenerationPhase.CANCELLED, GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT,
        )
        private val DRAFT_SETTLING_PHASES = setOf(
            com.promenar.infinity.domain.generation.GenerationPhase.FAILED,
            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED,
            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
        )

        /** 审计缺陷 A：<1K 显示原始 token 数，避免整数截断把数百 token 吞成 0K。 */
        fun formatTokenCount(tokens: Int): String {
            if (tokens < 0) return "0"
            if (tokens < 1000) return tokens.toString()
            if (tokens >= 999_500) {
                val m = tokens / 1_000_000.0
                val text = if (m < 100) String.format(java.util.Locale.US, "%.1f", m) else String.format(java.util.Locale.US, "%.0f", m)
                return text.removeSuffix(".0") + "M"
            }
            val k = tokens / 1000.0
            val text = if (k < 100) String.format(java.util.Locale.US, "%.1f", k) else String.format(java.util.Locale.US, "%.0f", k)
            return text.removeSuffix(".0") + "K"
        }
        private val TERMINAL_GENERATION_PHASES = setOf(
            com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED,
            com.promenar.infinity.domain.generation.GenerationPhase.FAILED,
            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED,
            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
        )
        private val FOREGROUND_STOPPING_PHASES = TERMINAL_GENERATION_PHASES +
            setOf(GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT)
        private const val FONT_SIZE_PERSISTENCE_DEBOUNCE_MS = 180L
        private const val DRAFT_PENDING_START = "__pending_generation_start__"

        fun factory(
            application: Application,
            generationCoordinatorOverride:
                com.promenar.infinity.domain.generation.GenerationCoordinator? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return ChatViewModel(
                        application = application,
                        sessionRepository = app.sessionRepository,
                        messageRepository = app.messageRepository,
                        agentRepository = app.agentRepository,
                        llmProvider = app.llmProvider,
                        providerRequestRouter = app.providerRequestRouter,
                        configResolver = app.configResolver,
                        providerResolutionDispatcher = Dispatchers.IO,
                        localLlmProviderFactory = { modelId ->
                            app.generationLocalProviders.create(modelId)
                        },
                        embeddingClient = app.embeddingClient,
                        vectorStore = app.vectorStore,
                        textSplitter = app.textSplitter,
                        memoryManager = app.memoryManager,
                        kgProvider = app.kgProvider,
                        skillRegistry = app.skillRegistry,
                        exportSessionUseCase = ExportSessionUseCase(
                            app.messageRepository as com.promenar.infinity.domain.repository.IMessageRepository,
                            app.sessionRepository as com.promenar.infinity.domain.repository.ISessionRepository
                        ),
                        branchSessionUseCase = BranchSessionUseCase(
                            app.database,
                            canvasStore = app.canvasStore,
                            branchTitle = { title ->
                                application.getString(R.string.chat_branch_title, title)
                            },
                        ),
                        generationCoordinatorOverride = generationCoordinatorOverride,
                        notificationPermissionGateway = {
                            val prefs = application.getSharedPreferences(
                                com.promenar.infinity.background.generation
                                    .GENERATION_NOTIFICATION_PERMISSION_PREFS,
                                0,
                            )
                            com.promenar.infinity.background.generation
                                .NotificationPermissionPromptState(
                                    sdkInt = android.os.Build.VERSION.SDK_INT,
                                    granted = android.os.Build.VERSION.SDK_INT < 33 ||
                                        androidx.core.content.ContextCompat.checkSelfPermission(
                                            application,
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                                    alreadyAsked = prefs.getBoolean(
                                        com.promenar.infinity.background.generation
                                            .GENERATION_NOTIFICATION_PERMISSION_ASKED,
                                        false,
                                    ),
                                )
                        },
                    ) as T
                }
            }
    }

    private fun providerRoutingErrorMessage(failure: ProviderResolution.Failure): String =
        if (failure.reason == ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE) {
            application.getString(R.string.local_inference_release_unavailable)
        } else {
            "Provider 路由失败：${failure.reason.name}；请打开对应 Provider 设置" +
                (failure.providerId?.let { "（$it）" } ?: "")
        }

    private var tokenEstimateJob: Job? = null

    private fun updateTokenIndicator(session: Session, isGenerating: Boolean = _isGenerating.value) {
        _tokenIndicatorState.update { previous ->
            previous.copy(
                isGenerating = isGenerating,
                autoCompactionThreshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8,
                keepRecentTurns = session.inferenceParams.resolvedKeepRecentTurns,
            )
        }
        tokenEstimateJob?.cancel()
        tokenEstimateJob = viewModelScope.launch {
            try {
                val estimate = contextUsageEstimator(session.id)
                if (_currentSessionId.value != session.id) return@launch
                _tokenIndicatorState.value = TokenIndicatorState(
                    used = estimate.totalTokens, max = estimate.modelContextTokens,
                    systemTokens = estimate.systemTokens, summaryTokens = estimate.summaryTokens,
                    activeTokens = estimate.conversationTokens, ragTokens = estimate.ragTokens,
                    foldedToolResults = estimate.foldedToolResults,
                    categories = estimate.categories,
                    mcpServerNames = availableSessionMcpServers.value.associate { it.id to it.name },
                    autoCompactionThreshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8,
                    isGenerating = isGenerating,
                    keepRecentTurns = com.promenar.infinity.domain.usecase.resolveKeepRecentTurns(
                        session.inferenceParams?.keepRecentTurns, session.inferenceParams?.activeContextWindow,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: com.promenar.infinity.data.session.SessionDeletingException) {
                // 删除屏障关闭后不再为旧会话发布估算。
            } catch (_: Exception) {
                // 无法重建上下文时保留最近一次完整估算。
            }
        }
    }


    /** 新会话的检索选项：开关取全局 RAG 设置，范围档位取助手默认值。 */
    private suspend fun getDefaultRagOptions(agentId: String): com.promenar.infinity.data.model.RagOptions {
        val global = com.promenar.infinity.domain.usecase.RagConfigPersistence(
            application.getSharedPreferences("rag_settings", android.content.Context.MODE_PRIVATE),
        ).loadFullConfig()
        return com.promenar.infinity.domain.usecase.defaultSessionRagOptions(agentRepository.getById(agentId), global)
    }

}

/** 记忆片段在一轮内的顺序，取自向量元数据的 chunkIndex。 */
private fun memoryChunkIndex(metadata: String?): Int =
    metadata?.let { Regex("\"chunkIndex\":(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
