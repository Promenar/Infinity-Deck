package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.canonicalizedForRequest

import kotlinx.serialization.encodeToString

import com.promenar.infinity.data.model.TurnMemoryState
import android.content.SharedPreferences
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.document.ContextBudgetDecision
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.domain.usecase.resolvedKeepRecentTurns
import com.promenar.infinity.domain.usecase.inferenceDefaults
import com.promenar.infinity.domain.usecase.withDefaults
import com.promenar.infinity.domain.usecase.resolvedReasoningEffort
import com.promenar.infinity.data.model.contentForModel
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.PhaseStatus
import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.PostProcessType
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagPhase
import com.promenar.infinity.data.model.RagProgress
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.UpdateMessageOptions
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.StreamConfig
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ReasoningReplayCodec
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolToolChoice
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.ToolApprovalCreation
import com.promenar.infinity.data.repository.RolledBackSummaryUsageRecord
import com.promenar.infinity.data.repository.RolledBackSummaryUsageStore
import com.promenar.infinity.data.repository.ApprovalCallCandidate
import com.promenar.infinity.data.repository.ToolApprovalRequestFactory
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.DEFAULT_TOOL_ROUNDS_PER_GENERATION
import com.promenar.infinity.domain.generation.GenerationToolBudget
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolUsage
import com.promenar.infinity.domain.generation.MAX_CONFIGURABLE_TOOL_ROUNDS
import com.promenar.infinity.domain.generation.DEFAULT_STREAM_RETRY_LIMIT
import com.promenar.infinity.domain.generation.MAX_STREAM_RETRY_LIMIT
import com.promenar.infinity.domain.generation.STREAM_RETRY_LIMIT_KEY
import com.promenar.infinity.domain.generation.GenerationToolDecision
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.PostProcessor
import com.promenar.infinity.ui.chat.manager.PostProcessorParams
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.chat.manager.SummaryManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.knowledge.KnowledgeCitationScope
import com.promenar.infinity.data.knowledge.knowledgeTurnId
import com.promenar.infinity.data.trigger.isUnstartedCompletionPlaceholder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface GenerationUiPort {
    fun replaceRagPhases(phases: List<RagPhase>)
    fun updateRagPhases(transform: (List<RagPhase>) -> List<RagPhase>)
    fun setGenerating(value: Boolean)
    fun setStreamingContent(value: String)
    fun setError(failure: GenerationFailure?)
    fun setFailureSurface(surface: GenerationFailureSurface?)
    fun setProviderFailure(failure: ProviderResolution.Failure?)
    fun onHandledFailure()
    fun addPostProcessTask(type: PostProcessType, detail: String): String
    fun updatePostProcessTask(
        id: String,
        status: PostProcessStatus? = null,
        progress: Float? = null,
        detail: String? = null,
    )
    fun removePostProcessTask(id: String)
    /** 发送前等待内置 Tailscale 的状态；null 表示没有在等待。 */
    fun setTailnetWait(state: TailnetWaitState?) {}
}

/** 请求发往 tailnet 而内置 Tailscale 尚未接入时，界面展示的等待状态。 */
sealed interface TailnetWaitState {
    data class Waiting(val progress: com.promenar.infinity.data.tailnet.TailnetConnectProgress) : TailnetWaitState
    /** 等待后接入成功，请求随即发出。 */
    data object Connected : TailnetWaitState
}

// 检索轨迹本身也是需要持久化的结果：未命中、嵌入不可用等情形没有引用，但检索指示器仍要显示。
private fun ContextBuilderResult.hasPersistableRagContext(): Boolean =
    ragContext.isNotBlank() || ragReferences.isNotEmpty() || citations.isNotEmpty() ||
        kgPaths.isNotEmpty() || ragUsage != null || ragTrace != null

private fun ContextBuilderResult.toMessageRagUpdateOptions(): UpdateMessageOptions? {
    if (ragReferences.isEmpty() && citations.isEmpty() && kgPaths.isEmpty() && ragTrace == null) return null
    val hasReferenceMetadata = ragReferences.isNotEmpty() || citations.isNotEmpty() || ragTrace != null
    return UpdateMessageOptions(
        ragReferences = ragReferences.ifEmpty { null },
        citations = citations.ifEmpty { null },
        kgPaths = kgPaths.ifEmpty { null },
        ragMetadata = if (hasReferenceMetadata) {
            com.promenar.infinity.data.model.RagMetadata(
                chunkCount = ragReferences.size,
                totalTokens = ragUsage?.ragSystem ?: 0,
                retrievalTimeMs = ragTrace?.searchTimeMs ?: 0,
                trace = ragTrace,
            )
        } else null,
    )
}

/**
 * 生成业务的应用级具体实现。它不持有 Activity/ViewModel，只通过 [GenerationUiPort] 发布可选 UI effects。
 */
internal class DefaultChatGenerationRuntime(
    private val settings: SharedPreferences,
    private val applicationScope: CoroutineScope,
    private val store: ChatStore,
    private val agentRepository: IAgentRepository,
    private val configResolver: AgentConfigResolver,
    private val routeGate: ChatProviderRouteGate,
    private val contextBuilder: ContextBuilder,
    private val messageManager: MessageManager,
    private val localProviderFactory: (String) -> LlmProvider,
    private val provider: LlmProvider,
    private val toolLedger: ToolExecutionLedger,
    private val toolExecutor: ToolExecutor,
    private val postProcessor: PostProcessor,
    private val memoryManager: MemoryManager?,
    private val summaryManager: SummaryManager,
    private val sessionManager: SessionManager,
    private val contentStrategy: ChatGenerationContentStrategy,
    private val ui: GenerationUiPort,
    private val skillCatalog: com.promenar.infinity.data.skills.SkillCatalog? = null,
    private val toolAllowlistStore: com.promenar.infinity.data.repository.ToolAllowlistStore? = null,
    private val workspaceRepository: com.promenar.infinity.domain.repository.IWorkspaceRepository? = null,
    private val outputStore: com.promenar.infinity.data.repository.ToolOutputStore? = null,
    private val interactionStore: GenerationInteractionStore? = null,
    private val externalFolders: com.promenar.infinity.domain.storage.ExternalWorkspaceAccess? = null,
    private val sshRuntime: com.promenar.infinity.data.ssh.SshRuntime? = null,
    private val compactionPipeline: ContextCompactionPipeline = ContextCompactionPipeline(),
    private val compactionService: ContextCompactionService? = null,
    /** 会话当前工作区，用于系统提示词的工作区说明；null 时按私有工作区说明。 */
    private val sessionWorkspace: (suspend (String) -> com.promenar.infinity.data.workspace.SessionWorkspace)? = null,
    /** 构建工具表的调度器；工具注册表会同步扫描技能目录，不能落在主线程。 */
    private val mediaContextLoader: ViewedMediaContextLoader? = null,
    /** 会话图谱抽取排队；为空或全局开关关闭时不抽取。 */
    private val sessionGraph: com.promenar.infinity.data.rag.SessionGraphScheduler? = null,
    private val subagentUsage: com.promenar.infinity.data.subagent.SubagentUsageLedger? = null,
    private val toolBuildDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val sessionState: com.promenar.infinity.data.rag.SessionStateScheduler? = null,
    /** 接口在 tailnet 内时，请求前等待内置 Tailscale 接入；为空时不等待。 */
    private val tailnetGate: com.promenar.infinity.data.tailnet.TailnetConnectGate? = null,
    private val composerQueueStore: com.promenar.infinity.data.chat.queue.ComposerQueueStore? = null,
    private val stateBoardRepository: com.promenar.infinity.data.repository.StateBoardRepository? = null,
    private val runModeCheck: (suspend (String) -> GenerationFailure?)? = null,
    private val linuxFiles: com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess? = null,
    private val askUserLedger: com.promenar.infinity.data.repository.AskUserLedgerRepository? = null,
    private val secretInputLedger: com.promenar.infinity.data.secret.SecretInputLedgerRepository? = null,
    private val subagentCompletions: com.promenar.infinity.data.trigger.SubagentCompletionSteering? = null,
    private val branchService: (() -> com.promenar.infinity.data.branch.BranchSessionService)? = null,
    private val branchPlans: com.promenar.infinity.data.branch.BranchPlanLedger? = null,
    private val sessionMailbox: com.promenar.infinity.data.branch.SessionMessageStore? = null,
    private val knowledgeCatalog: com.promenar.infinity.data.knowledge.KnowledgeCatalogSource? = null,
    private val knowledgeAccessPlanner: com.promenar.infinity.data.knowledge.KnowledgeAccessPlanner? = null,
    private val knowledgeTurns: com.promenar.infinity.data.knowledge.KnowledgeTurnState? = null,
    private val chatCitationRecorder: com.promenar.infinity.data.library.ask.ChatCitationRecorder? = null,
    private val toolProgressReminder: ToolProgressReminder = ToolProgressReminder(),
    private val timedGrants: com.promenar.infinity.data.approval.TimedApprovalGrantStore? = null,
    private val agentConfigService: (() -> com.promenar.infinity.data.agentconfig.AgentConfigService)? = null,
    private val attachmentWorkspaceStore: com.promenar.infinity.data.document.UserAttachmentWorkspaceStore? = null,
) : ChatGenerationRuntime {
    private var interactionMode = com.promenar.infinity.domain.generation.GenerationInteractionMode.INTERACTIVE
    private val compactionTurnState = ContextCompactionTurnState()
    private val attachmentPersistenceAttempts = mutableSetOf<String>()
    private val contextLimitRecovery = ContextLimitRecovery()
    private var activeToolBudget: GenerationToolBudget? = null
    private var baselineTokens = 0L
    private var mainSpentTokens = 0L
    private var summarySpentTokens = 0L
    private var summaryUsageEstimated = false
    private var preparedRoute: ChatProviderRoute? = null
    private var preparedContext: ContextBuilderResult? = null
    private var preparedContextSummary: String? = null
    private var preparedPrompt: PromptRequest? = null
    private var preparedTools: List<ProtocolTool> = emptyList()
    private var toolRounds = 0
    private var finalAnswerMode = false
    /** 下一次发送附加空回复提醒；发送后即清除，只作用于那一次请求。 */
    private var emptyReplyReminder = false
    private var currentAssistantMessageId: String? = null
    private var rollbackBaselineMessageIds: Set<String> = emptySet()
    private var replacementCommitted = false
    private var manualCompaction = false
    private var frozenTurnContext: String? = null
    private var replacementTurnContext: Pair<String, String?>? = null
    private var refreshingForSend = false
    private val unsentInstructionIds = mutableSetOf<String>()
    private var observedStateRevision = 0L
    private var cachedRetrieval: com.promenar.infinity.ui.chat.manager.ContextRetrieval? = null

    /** 生成运行在主线程作用域；工具表要按会话扫描技能目录，统一放到 IO 上构建。 */
    private suspend fun buildToolsOffMain(session: com.promenar.infinity.data.model.Session, modelId: String? = preparedRoute?.modelId): List<ProtocolTool> =
        kotlinx.coroutines.withContext(toolBuildDispatcher) {
            contentStrategy.buildTools(if (modelId == null) session else session.copy(modelId = modelId)).filterNot {
                interactionMode == com.promenar.infinity.domain.generation.GenerationInteractionMode.HEADLESS &&
                    it.function.name in setOf(com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME, com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME, "session_branch")
            }.canonicalizedForRequest()
        }

    private suspend fun buildMessagesWithMedia(
        session: com.promenar.infinity.data.model.Session,
        systemPrompt: String,
        pinnedUserMessageId: String? = null,
        excludedMessageIds: Set<String> = emptySet(),
        modelId: String? = null,
    ): List<ProtocolMessage> {
        val messages = contentStrategy.buildProtocolMessages(session, systemPrompt, pinnedUserMessageId, excludedMessageIds, modelId)
        return mediaContextLoader?.attach(session, messages, modelId) ?: messages
    }

    private suspend fun scanSkills(workspace: com.promenar.infinity.data.workspace.SessionWorkspace?): List<com.promenar.infinity.data.skills.DiscoveredSkill> {
        val directory = workspace as? com.promenar.infinity.data.workspace.SessionWorkspace.Directory
        return try {
            skillCatalog?.scan(directory?.let { com.promenar.infinity.data.skills.SkillWorkspace(it.workspace.id, it.root) })?.skills.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.log("[AgentSkills] 上下文目录扫描失败：${failure.javaClass.simpleName}")
            emptyList()
        }
    }

    /** 读取失败时提示中不列挂载，工具调用仍按实时挂载判定。 */
    private suspend fun visibleMounts(sessionId: String): List<com.promenar.infinity.domain.storage.ExternalMount> = try {
        externalFolders?.visible(sessionId).orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        InfinityLogger.log("[Mounts] 上下文挂载读取失败：${failure.javaClass.simpleName}")
        emptyList()
    }

    /**
     * 本轮用户消息以 `/技能名` 开头时预先加载该技能（与 `activate_skill` 等价）。
     * 按落库的用户消息原文判断，排队发送、重新生成与审批后续写都能还原同一技能。
     */
    private suspend fun requestedSkill(
        session: com.promenar.infinity.data.model.Session,
        userMessageId: String?,
        fallbackText: String,
        skills: List<com.promenar.infinity.data.skills.DiscoveredSkill>,
    ): com.promenar.infinity.ui.chat.manager.skills.RequestedSkill? {
        val text = session.messages.firstOrNull { it.id == userMessageId && it.role == MessageRole.USER }?.content
            ?: fallbackText
        val catalog = skillCatalog ?: return null
        return com.promenar.infinity.ui.chat.manager.skills.SlashSkillActivation.resolve(text, skills) { catalog.readDocument(it) }
    }

    private suspend fun requestedSkills(
        session: com.promenar.infinity.data.model.Session,
        userMessageId: String?,
        fallbackText: String,
        skills: List<com.promenar.infinity.data.skills.DiscoveredSkill>,
    ): List<com.promenar.infinity.ui.chat.manager.skills.RequestedSkill> {
        val text = session.messages.firstOrNull { it.id == userMessageId && it.role == MessageRole.USER }?.content ?: fallbackText
        val catalog = skillCatalog ?: return emptyList()
        return com.promenar.infinity.ui.chat.manager.skills.SlashSkillActivation.resolveAll(text, skills) { catalog.readDocument(it) }
    }

    private fun userContent(request: GenerationRequest, session: com.promenar.infinity.data.model.Session): String =
        session.messages.firstOrNull { it.id == request.userMessageId && it.role == MessageRole.USER }
            ?.takeIf { !it.quotes.isNullOrEmpty() }?.contentForModel() ?: request.userContent

    private suspend fun compactContext(
        request: GenerationRequest,
        session: com.promenar.infinity.data.model.Session,
        prompt: PromptRequest,
        systemPrompt: String,
        manual: Boolean = false,
        currentTurnId: String? = request.userMessageId,
        onProgress: (ContextCompactionStage) -> Unit = {},
        recoveryBudget: Int? = null,
    ): ContextCompactionResult {
        val operation: suspend () -> ContextCompactionResult = {
            val eligible = session.copy(messages = session.messages.filterNot { it.id in request.messageIdsToReplace })
            compactionPipeline.compact(
                ContextCompactionInput(
                    session = eligible,
                    currentTurnId = currentTurnId,
                    keepRecentTurns = if (recoveryBudget != null) 1 else session.inferenceParams.resolvedKeepRecentTurns,
                    threshold = session.inferenceParams?.autoSummaryThreshold ?: 0.8,
                    window = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, preparedRoute?.modelId),
                    manual = manual,
                    turnState = compactionTurnState,
                    stableModelId = preparedRoute?.modelId,
                    inputBudgetOverride = recoveryBudget,
                ),
                buildPrompt = { updated ->
                    val updatedSystem = replaceHistorySummary(systemPrompt, updated.summary, preparedContextSummary)
                    prompt.copy(messages = offloadToolResults(
                        request.sessionId,
                        buildMessagesWithMedia(
                            updated, updatedSystem, currentTurnId, request.messageIdsToReplace, preparedRoute?.modelId,
                        ),
                    ))
                },
                summarizeTool = { text -> summarizeToolResult(request, prompt, text) },
                summarizeHistory = { previous, messages ->
                    val estimatedInput = 128L + ApproximateTokenEstimator.estimate(previous.summary.orEmpty()) +
                        messages.sumOf { ApproximateTokenEstimator.estimate(it.contentForModel().take(2000)).toLong() + 8 }
                    if (!canSpendOnSummary(request, estimatedInput + 1024)) null else {
                        if (memoryEnabled(session)) {
                            archiveMessages(request, preparedRoute?.modelId ?: prompt.model, messages)
                        }
                        if (!summarize(request, previous, messages)) null else {
                            store.getSession(request.sessionId)?.let { updated ->
                                updated.copy(messages = updated.messages.filterNot { it.id in request.messageIdsToReplace })
                            }
                        }
                    }
                },
                onProgress = onProgress,
            )
        }
        val result = if (manual || compactionService == null) operation()
            else compactionService.compactAutomatically(request.sessionId, operation)
        if (result.newlyFoldedToolResults > 0 || result.summarizedTurns > 0)
            sessionManager.updateContextUsageBaseline(request.sessionId, null)
        return result
    }

    private fun canSpendOnSummary(request: GenerationRequest, estimatedTokens: Long): Boolean {
        val limit = toolBudget(request).maxTokens
        return !finalAnswerMode && (limit <= 0 ||
            baselineTokens + mainSpentTokens + summarySpentTokens + estimatedTokens <= limit)
    }

    private suspend fun summarizeToolResult(request: GenerationRequest, prompt: PromptRequest, text: String): String? {
        if (!canSpendOnSummary(request, ApproximateTokenEstimator.estimate(text).toLong() + 384)) return null
        var reported: TokenUsage? = null
        var result: RoutedToolSummary? = null
        try {
            result = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                RoutedToolResultSummarizer(routeGate, localProviderFactory).summarizeWithUsage(
                    text, preparedRoute?.modelId ?: prompt.model, sessionId = request.sessionId, onUsage = { reported = it },
                )
            }
            return result?.content
        } finally {
            val usage = result?.usage ?: reported ?: TokenUsage(
                input = ApproximateTokenEstimator.estimate(text) + 128,
                output = 256,
                total = ApproximateTokenEstimator.estimate(text) + 384,
                estimated = true,
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, usage) }
        }
    }

    private suspend fun offloadToolResults(
        sessionId: String,
        messages: List<com.promenar.infinity.data.remote.protocol.ProtocolMessage>,
    ) = messages.map { message ->
        if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else {
            val content = try {
                outputStore.offload(sessionId, message.content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                ContextCompactionPipeline.headTail(message.content, 2000)
            }
            message.copy(content = content)
        }
    }

    /** 手动压缩只重建系统与现有历史，不触发新的联网或知识库检索。 */
    private suspend fun prepareStoredContext(sessionId: String): StoredContext {
        val session = requireNotNull(store.getSession(sessionId)) { "压缩会话不存在" }
        val agent = agentRepository.getById(session.agentId)
        val config = configResolver.resolve(agent)
        val model = session.modelId?.takeIf(String::isNotBlank)
            ?: config.modelId.takeIf(String::isNotBlank)
            ?: ProviderManager.getInstance().getMainConfiguredModelId().orEmpty()
        val lastUser = session.messages.lastOrNull { it.role == MessageRole.USER }
        val request = GenerationRequest(
            sessionId = sessionId,
            assistantMessageId = "manual-compaction-${java.util.UUID.randomUUID()}",
            userMessageId = lastUser?.id,
            userContent = lastUser?.contentForModel().orEmpty(),
            imageDataUrls = emptyList(),
            runtimePolicy = com.promenar.infinity.domain.generation.GenerationRuntimePolicy.FOREGROUND_ONLY,
        )
        currentAssistantMessageId = request.assistantMessageId
        activeToolBudget = readToolBudget()
        preparedRoute = when (val result = routeGate.prepare(model) { Unit }) {
            is ChatRoutePreparation.Success -> result.route
            is ChatRoutePreparation.Failure -> ChatProviderRoute(
                null, model, result.failure, modelId = model, supportsToolCalls = false,
            )
        }
        val tools = if (preparedRoute?.supportsToolCalls == true) buildToolsOffMain(session) else emptyList()
        val workspace = sessionWorkspace?.invoke(sessionId)
        val storedSkills = scanSkills(workspace)
        val prompt = PromptRequest(
            model = preparedRoute?.remoteModelId ?: model,
            messages = emptyList(),
            tools = tools.ifEmpty { null },
            maxTokens = session.inferenceParams.withDefaults(config.inferenceDefaults()).maxTokens,
        )
        val context = contextBuilder.buildContext(ContextBuilderParams(
            sessionId = sessionId,
            content = lastUser?.contentForModel().orEmpty(),
            assistantMsgId = request.assistantMessageId,
            session = session,
            ragOptions = session.ragOptions,
            agentSystemPrompt = config.systemPrompt.takeIf(String::isNotBlank),
            sessionCustomPrompt = session.customPrompt,
            retrievalMode = com.promenar.infinity.data.rag.effectiveRetrievalMode(agent?.retrievalConfig, session.options.toolsEnabled),
            agentMemoryPolicy = config.memoryPolicy,
            availableToolNames = tools.mapTo(linkedSetOf()) { it.function.name },
            availableSkills = storedSkills,
            requestedSkill = requestedSkill(session, lastUser?.id, lastUser?.contentForModel().orEmpty(), storedSkills),
            cachedRetrieval = com.promenar.infinity.ui.chat.manager.ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList()),
            workspace = workspace,
            mounts = visibleMounts(sessionId),
            modelContextTokens = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, preparedRoute?.modelId).modelContextTokens,
            runtimeModelId = prompt.model,
        ))
        val systemPrompt = replaceHistorySummary(context.finalSystemPrompt, session.summary, session.summary)
        preparedContext = context.copy(
            finalSystemPrompt = systemPrompt,
            systemPromptSections = context.systemPromptSections.map { section ->
                if (section.id == "history_summary") section.copy(content = replaceHistorySummary(section.content, session.summary, session.summary)) else section
            },
        )
        preparedContextSummary = session.summary
        preparedPrompt = prompt
        return StoredContext(session, request, prompt, systemPrompt)
    }

    private data class StoredContext(
        val session: com.promenar.infinity.data.model.Session,
        val request: GenerationRequest,
        val prompt: PromptRequest,
        val systemPrompt: String,
    )

    internal suspend fun compactNow(sessionId: String, onProgress: (ContextCompactionStage) -> Unit): ContextCompactionResult {
        manualCompaction = true
        val context = prepareStoredContext(sessionId)
        return compactContext(context.request, context.session, context.prompt, context.systemPrompt, manual = true, onProgress = onProgress)
    }

    internal suspend fun estimateContext(sessionId: String): ContextUsageEstimate {
        val context = prepareStoredContext(sessionId)
        val messages = buildMessagesWithMedia(context.session, context.systemPrompt, modelId = preparedRoute?.modelId).map { message ->
            if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else message.copy(content = try {
                outputStore.previewForContext(sessionId, message.content)
            } catch (_: java.io.IOException) { ContextCompactionPipeline.headTail(message.content, 2000) })
        }
        val preview = compactionPipeline.preview(context.session, context.prompt.copy(messages = messages), preparedRoute?.modelId)
        val window = PreparedPromptBudgetGate.resolveWindow(context.session, context.prompt, settings, preparedRoute?.modelId)
        val local = ContextUsageBreakdown.estimate(
            preview.prompt, preparedContext?.systemPromptSections.orEmpty(),
            preview.foldedToolResults, window.modelContextTokens ?: 128000,
        )
        return ContextUsageCalibration.scale(local, preview.afterTokens)
    }

    internal suspend fun estimateDraftDocumentBudget(sessionId: String, text: String,
        documents: List<com.promenar.infinity.data.model.MessageDocumentAttachment>): DraftDocumentBudget {
        val context = prepareStoredContext(sessionId)
        val messages = buildMessagesWithMedia(context.session, context.systemPrompt, modelId = preparedRoute?.modelId).map { message ->
            if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else message.copy(content = try {
                outputStore.previewForContext(sessionId, message.content)
            } catch (_: java.io.IOException) { ContextCompactionPipeline.headTail(message.content, 2000) })
        }
        val preview = compactionPipeline.preview(context.session, context.prompt.copy(messages = messages), preparedRoute?.modelId)
        // 合成用户轮只用于只读预估，使原当前轮附件和工具媒体采用历史引用。
        val nextTurn = context.session.copy(messages = context.session.messages + com.promenar.infinity.data.model.Message(
            "draft-budget-preview", MessageRole.USER, "",
        ))
        val nextMessages = buildMessagesWithMedia(nextTurn, context.systemPrompt, modelId = preparedRoute?.modelId).dropLast(1).map { message ->
            if (message.role != "tool" || message.name == "view_media" || outputStore == null) message else message.copy(content = try {
                outputStore.previewForContext(sessionId, message.content)
            } catch (_: java.io.IOException) { ContextCompactionPipeline.headTail(message.content, 2000) })
        }
        val nextPreview = compactionPipeline.preview(nextTurn, context.prompt.copy(messages = nextMessages), preparedRoute?.modelId)
        return DraftDocumentBudgetEvaluator.evaluate(context.session, DraftBudgetContext(
            preparedRoute?.modelId ?: context.prompt.model, preview.prompt,
            PreparedPromptBudgetGate.resolveWindow(context.session, context.prompt, settings, preparedRoute?.modelId),
            historyForNextTurn = nextPreview.prompt), text, documents)
    }

    override suspend fun prepare(request: GenerationRequest) {
        toolProgressReminder.start()
        interactionMode = request.interactionMode
        val input = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == request.userMessageId }
        if (input?.role == MessageRole.USER && input.name == null && !request.assistantMessageId.startsWith("ask_resume_"))
            sessionMailbox?.resetForUserInput(request.sessionId)
        branchService?.invoke()?.prepareWorkspace(request.sessionId)
        stateBoardRepository?.beginGeneration(request.sessionId, request.assistantMessageId, request.messageIdsToReplace)
        sshRuntime?.resumeOwner("agent:${request.sessionId}")
        val session = requireNotNull(store.getSession(request.sessionId)) { "生成会话不存在" }
        currentAssistantMessageId = request.assistantMessageId
        rollbackBaselineMessageIds = session.messages.mapTo(mutableSetOf()) { it.id }.apply {
            remove(request.assistantMessageId)
        }
        require(session.messages.any {
            it.id == assistantMessageId(request) && it.role == MessageRole.ASSISTANT
        }) { "生成目标消息不存在" }
        ui.setGenerating(true)
        activeToolBudget = readToolBudget()
        baselineTokens = consumedToolUsageInTurn(session.messages, request).tokens
        ensureAutomaticConsent(request, session.executionMode)
    }

    private suspend fun ensureAutomaticConsent(request: GenerationRequest, mode: String) {
        val consent = UnlimitedAutomationConsent(settings)
        if (consent.requiresConfirmation(toolBudget(request).maxRounds == 0, mode)) {
            val confirmed = interactionStore?.awaitDecision(request.sessionId, GenerationInteractionKind.UNLIMITED_AUTO, request.interactionMode) == true
            if (!confirmed) throw CancellationException("用户未确认不限轮次全自动执行")
            consent.confirm()
        }
    }

    private suspend fun persistCurrentUserAttachments(request: GenerationRequest) {
        val saver = attachmentWorkspaceStore ?: return
        val session = store.getSession(request.sessionId) ?: return
        val user = session.messages.firstOrNull { it.id == request.userMessageId && it.role == MessageRole.USER } ?: return
        if (user.userImages.isNullOrEmpty() && user.userDocuments.isNullOrEmpty()) return
        if (!attachmentPersistenceAttempts.add(user.id)) return
        try {
            val saved = saver.save(session, user.userImages.orEmpty(), user.userDocuments.orEmpty())
            if (saved.imageReferences != user.userImageReferences || saved.documents != user.userDocuments.orEmpty())
                messageManager.persistUserAttachments(session.id, user, saved.imageReferences, saved.documents)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            InfinityLogger.diagnostic("generation.user_attachment_save_failed", mapOf("sessionId" to session.id, "messageId" to user.id))
        }
    }

    override suspend fun buildContext(request: GenerationRequest): GenerationPreparationOutcome {
        interactionMode = request.interactionMode
        subagentCompletions?.let { bridge ->
            val target = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
            bridge.deliver(request.sessionId, store, messageManager,
                consumerAssistantId = target?.takeIf { it.isUnstartedCompletionPlaceholder() }?.id)
        }
        runModeCheck?.invoke(request.sessionId)?.let { failure ->
            handlePreparationFailure(request, failure, null)
            return GenerationPreparationOutcome.Handled(failure)
        }
        persistCurrentUserAttachments(request)
        val storedUser = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == request.userMessageId && it.role == MessageRole.USER }
        val turnMessages = store.getSession(request.sessionId)?.messages.orEmpty()
            .dropWhile { it.id != request.userMessageId }.drop(1).takeWhile { it.role != MessageRole.USER }
        val hasPreviousAssistant = turnMessages.any { it.role == MessageRole.ASSISTANT && it.id != assistantMessageId(request) && it.id !in request.messageIdsToReplace }
        val originalOwner = com.promenar.infinity.ui.chat.manager.turnContextMetadata(storedUser?.turnContext)?.assistantMessageId
        val regenerate = request.messageIdsToReplace.isNotEmpty() ||
            (originalOwner != null && originalOwner != request.assistantMessageId && !hasPreviousAssistant)
        val legacyResumption = storedUser?.turnContext == null && hasPreviousAssistant && !regenerate
        if (frozenTurnContext == null && replacementTurnContext == null && regenerate && storedUser != null)
            replacementTurnContext = storedUser.id to storedUser.turnContext
        val savedTurnContext = frozenTurnContext ?: if (regenerate) null else
            storedUser?.turnContext ?: if (legacyResumption) "" else null
        val reusedRetrieval = cachedRetrieval ?: savedTurnContext?.let {
            com.promenar.infinity.ui.chat.manager.ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList())
        }
        if (reusedRetrieval == null) ui.replaceRagPhases(defaultRagPhases())
        var session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val savedConfiguration = com.promenar.infinity.ui.chat.manager.turnContextMetadata(savedTurnContext)?.configuration
        val agent = agentRepository.getById(session.agentId)
        val agentConfig = configResolver.resolve(agent)
        val inference = savedConfiguration?.inferenceParams ?: session.inferenceParams.withDefaults(agentConfig.inferenceDefaults())
        val toolSession = if (savedConfiguration == null) session else session.copy(
            activeSkillIds = savedConfiguration.activeSkillIds ?: session.activeSkillIds,
            activeMcpServerIds = savedConfiguration.activeMcpServerIds ?: session.activeMcpServerIds,
        )
        val model = savedConfiguration?.modelId ?: session.modelId?.takeIf(String::isNotBlank)
            ?: agentConfig.modelId.takeIf(String::isNotBlank)
            ?: ProviderManager.getInstance().getMainConfiguredModelId().orEmpty()
        val effectiveRagOptions = session.ragOptions ?: RagOptions()
        val params = ContextBuilderParams(
            sessionId = request.sessionId,
            stateExcludedMessageIds = request.messageIdsToReplace,
            content = userContent(request, session),
            assistantMsgId = assistantMessageId(request),
            session = session,
            ragOptions = effectiveRagOptions,
            onRagProgress = { stage, percentage, detail ->
                messageManager.updateMessageProgress(
                    request.sessionId,
                    assistantMessageId(request),
                    RagProgress(stage, percentage, detail),
                )
                ui.updateRagPhases { updateRagProgress(it, stage, percentage, detail) }
            },
            agentSystemPrompt = agentConfig.systemPrompt.takeIf(String::isNotBlank),
            sessionCustomPrompt = session.customPrompt,
            turnConfiguration = savedConfiguration ?: com.promenar.infinity.ui.chat.manager.TurnConfigurationSnapshot(
                agentConfig.systemPrompt.takeIf(String::isNotBlank), session.customPrompt, model, inference, session.activeSkillIds, session.activeMcpServerIds,
            ),
            retrievalMode = com.promenar.infinity.data.rag.effectiveRetrievalMode(agent?.retrievalConfig, session.options.toolsEnabled),
            knowledgeCatalog = if (savedTurnContext == null && com.promenar.infinity.data.rag.effectiveRetrievalMode(agent?.retrievalConfig, session.options.toolsEnabled) != com.promenar.infinity.data.agent.AgentRetrievalMode.AUTO) {
                knowledgeCatalog?.snapshot(session, com.promenar.infinity.data.rag.resolveEffectiveRagOptions(session.ragOptions, memoryPolicy = agentConfig.memoryPolicy))
                    ?.let { com.promenar.infinity.data.knowledge.renderKnowledgeCatalog(it) }.orEmpty()
            } else "",
            agentMemoryPolicy = agentConfig.memoryPolicy,
            workspace = sessionWorkspace?.invoke(request.sessionId),
            mounts = visibleMounts(request.sessionId),
        )
        val turnId = request.userMessageId ?: knowledgeTurnId(session, assistantMessageId(request))
        knowledgeTurns?.seed(session, turnId)
        val citationScope = knowledgeTurns?.let { turns ->
            KnowledgeCitationScope { id -> turns.citation(session.id, turnId, id) }
        }
        var routeTools: List<ProtocolTool> = emptyList()
        val preparation = try {
            routeGate.prepareWithRoute(model) { route ->
                // Vertex 的多轮函数调用要求 thought signature 原样回传；夹具闭环前保持能力关闭。
                routeTools = if (route.supportsToolCalls) buildToolsOffMain(toolSession, route.modelId) else emptyList()
                val toolNames = routeTools.mapTo(linkedSetOf()) { it.function.name }
                val skills = scanSkills(params.workspace)
                val contextParams = params.copy(
                    runtimeModelId = requireNotNull(route.remoteModelId),
                    availableToolNames = toolNames,
                    availableSkills = skills,
                    requestedSkills = if (savedTurnContext == null) requestedSkills(session, request.userMessageId, request.userContent, skills) else emptyList(),
                    cachedTurnContext = savedTurnContext,
                    modelContextTokens = PreparedPromptBudgetGate.resolveWindow(
                        session, PromptRequest(model = route.remoteModelId ?: model, messages = emptyList()), settings, route.modelId,
                    ).modelContextTokens,
                    cachedRetrieval = reusedRetrieval,
                )
                if (citationScope == null) contextBuilder.buildContext(contextParams) else withContext(citationScope) {
                    contextBuilder.buildContext(contextParams)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = error.failureOrUnknown()
            handlePreparationFailure(request, failure, null)
            return GenerationPreparationOutcome.Handled(failure)
        }
        val (route, context) = when (preparation) {
            is ChatRoutePreparation.Failure -> {
                val providerFailure = preparation.failure
                val failure = providerFailure.toGenerationFailure()
                handlePreparationFailure(request, failure, providerFailure)
                return GenerationPreparationOutcome.Handled(failure)
            }
            is ChatRoutePreparation.Success -> preparation.route to preparation.context
        }
        ui.setProviderFailure(null)
        // 检索只依赖用户问题：首轮检索并把引用挂到首条回复上，后续工具轮次复用结果，不重复联网与检索。
        if (reusedRetrieval == null) {
            if (!context.hasPersistableRagContext()) {
                ui.replaceRagPhases(emptyList())
                messageManager.clearMessageRagState(request.sessionId, assistantMessageId(request))
            } else {
                ui.updateRagPhases { phases ->
                    val executed = phases.filter { it.status != PhaseStatus.PENDING }.map { it.id }.toSet()
                    phases.map { if (it.id in executed) it.copy(status = PhaseStatus.DONE) else it }
                }
            }
            context.toMessageRagUpdateOptions()?.let {
                messageManager.updateMessageContent(request.sessionId, assistantMessageId(request), "", it)
                messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
            }
            cachedRetrieval = context.retrieval
            knowledgeTurns?.seed(session, turnId, context.ragReferences)
        }
        val tools = routeTools
        preparedRoute = route
        preparedContext = context
        observedStateRevision = maxOf(observedStateRevision, context.stateBoardRevision, observedStateRevision(session, request))
        stateBoardRepository?.recordContextRead(request.sessionId, assistantMessageId(request), observedStateRevision)
        preparedContextSummary = session.summary
        preparedTools = tools
        if (frozenTurnContext == null) {
            if (storedUser != null && !legacyResumption && storedUser.turnContext != context.turnContext) {
                messageManager.persistModelInputs(request.sessionId, storedUser.id, mapOf("turnContext" to context.turnContext))
            }
            frozenTurnContext = context.turnContext
        }
        val target = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
        if (target != null && (target.requestInstruction == null || target.id in unsentInstructionIds)) {
            val instruction = when {
                finalAnswerMode -> FINAL_ANSWER_INSTRUCTION.trimStart()
                emptyReplyReminder -> EMPTY_REPLY_INSTRUCTION
                toolProgressReminder.shouldRemind() -> PROGRESS_INSTRUCTION
                else -> ""
            }
            if (target.requestInstruction != instruction)
                messageManager.persistModelInputs(request.sessionId, target.id, mapOf("requestInstruction" to instruction))
            unsentInstructionIds += target.id
        }
        if (target != null && target.modelId != route.modelId)
            messageManager.persistModelInputs(request.sessionId, target.id, mapOf("modelId" to route.modelId))
        session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val systemPrompt = replaceHistorySummary(context.finalSystemPrompt, session.summary, session.summary)
        val prompt = PromptRequest(
            messages = buildMessagesWithMedia(
                session,
                systemPrompt,
                request.userMessageId,
                request.messageIdsToReplace,
                route.modelId,
            ),
            model = requireNotNull(route.remoteModelId),
            temperature = inference.temperature,
            topP = inference.topP,
            maxTokens = inference.maxTokens,
            frequencyPenalty = inference.frequencyPenalty,
            presencePenalty = inference.presencePenalty,
            topK = inference.topK,
            repetitionPenalty = inference.repetitionPenalty,
            reasoningEffort = inference.resolvedReasoningEffort,
            tools = tools.ifEmpty { null },
            // 收尾轮仍声明工具（历史中的调用需要它们才能被部分协议解析），但禁止再发起调用。
            toolChoice = if (finalAnswerMode) ProtocolToolChoice.NONE else ProtocolToolChoice.AUTO,
            stream = true,
        )
        val hasFullContextDocuments = session.messages.firstOrNull { it.id == request.userMessageId }
            ?.userDocuments.orEmpty().any { it.workspaceReference == null || it.inlineInCurrentTurn } || prompt.messages.any { !it.documentData.isNullOrEmpty() }
        val window = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, route.modelId)
        val floor = compactionPipeline.inputFloor(session, prompt, window, route.modelId)
        if (floor.exceeded || (hasFullContextDocuments && window.inputBudgetTokens == null)) {
            val failure = if (floor.exceeded) floor.failure(hasFullContextDocuments) else GenerationFailure(
                GenerationFailureCode.CONTEXT_LIMIT, mapOf(
                    GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "UNKNOWN_WINDOW",
                    GenerationFailure.KEY_REQUIRED_TOKENS to floor.tokens.toString(),
                    GenerationFailure.KEY_AVAILABLE_TOKENS to "0",
                    GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to hasFullContextDocuments.toString(),
                ))
            handleDocumentBudgetFailure(request, failure)
            return GenerationPreparationOutcome.Handled(failure)
        }
        val compacted = if (refreshingForSend) {
            compactionPipeline.preview(session, prompt.copy(messages = offloadToolResults(request.sessionId, prompt.messages)), route.modelId)
        } else compactContext(request, session, prompt, systemPrompt)
        compacted.blockedFloor?.let { blocked ->
            val failure = blocked.failure(hasFullContextDocuments)
            handleDocumentBudgetFailure(request, failure)
            return GenerationPreparationOutcome.Handled(failure)
        }
        val budgetedPrompt = compacted.prompt
        val budgetDecision = PreparedPromptBudgetGate.evaluate(
            store.getSession(request.sessionId) ?: session,
            budgetedPrompt,
            settings,
            hasFullContextDocuments,
            route.modelId,
        )
        if (budgetDecision is ContextBudgetDecision.Blocked) {
            val failure = GenerationFailure(
                code = GenerationFailureCode.CONTEXT_LIMIT,
                formatArgs = mapOf(
                    GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "TOTAL_INPUT",
                    GenerationFailure.KEY_REQUIRED_TOKENS to budgetDecision.requiredTokens.toString(),
                    GenerationFailure.KEY_AVAILABLE_TOKENS to budgetDecision.availableTokens.toString(),
                    GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to hasFullContextDocuments.toString(),
                ),
                technical = "full-context budget blocked: reason=${budgetDecision.reason}, " +
                    "required=${budgetDecision.requiredTokens}, available=${budgetDecision.availableTokens}",
            )
            handleDocumentBudgetFailure(request, failure)
            return GenerationPreparationOutcome.Handled(failure)
        }
        preparedPrompt = budgetedPrompt
        return GenerationPreparationOutcome.Ready
    }

    override suspend fun recoverContextLimit(request: GenerationRequest, failure: GenerationFailure,
        beforeRetry: suspend () -> Unit): Boolean = contextLimitRecovery.attempt(failure, compactionTurnState.historySummaryAttempted) {
        recoverContextLimit(request, beforeRetry)
    }

    private suspend fun recoverContextLimit(request: GenerationRequest, beforeRetry: suspend () -> Unit): Boolean {
        val session = store.getSession(request.sessionId) ?: return false
        val prompt = preparedPrompt ?: return false
        val window = PreparedPromptBudgetGate.resolveWindow(session, prompt, settings, preparedRoute?.modelId)
        // 服务端已拒绝本地口径，补救前清除基线，避免沿用失配的实际输入。
        sessionManager.updateContextUsageBaseline(request.sessionId, null)
        val uncalibrated = store.getSession(request.sessionId) ?: return false
        val floor = compactionPipeline.inputFloor(uncalibrated, prompt, window, preparedRoute?.modelId)
        val available = window.inputBudgetTokens ?: return false
        if (floor.exceeded || historyCompactionCandidates(uncalibrated.messages, request.userMessageId, 1).isEmpty()) return false
        val failedTokens = PromptUsageCalibration.estimate(uncalibrated, prompt, preparedRoute?.modelId)
        val budget = maxOf(floor.tokens.toLong(), minOf(available * 4 / 5, failedTokens.toLong() * 3 / 4)).toInt()
        beforeRetry()
        val reset = store.getSession(request.sessionId) ?: return false
        val result = compactContext(request, reset, prompt, preparedContext?.finalSystemPrompt.orEmpty(),
            manual = true, recoveryBudget = budget)
        return ContextLimitRecovery.meetsRecoveryBudget(result, budget)
    }

    override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> {
        // 页面已切走或销毁时，仍由应用级运行时结清已接受的队列发送租约。
        composerQueueStore?.acceptForUser(request.sessionId, request.userMessageId)
        // 每次实际发送前刷新配置；冻结块与已发送的尾部指令仍原文复用。
        refreshingForSend = true
        val refreshed = try { buildContext(request) } finally { refreshingForSend = false }
        if (refreshed is GenerationPreparationOutcome.Handled)
            return kotlinx.coroutines.flow.flowOf(GenerationChunk.Failure(refreshed.failure))
        val route = requireNotNull(preparedRoute) { "生成路由尚未准备完成" }
        val prepared = requireNotNull(preparedPrompt) { "生成请求尚未准备完成" }
        // 网络重试不会重建上下文；每次真正发起请求前都要重新校验工作区媒体。
        val mediaPrompt = if (mediaContextLoader == null) prepared else {
            val currentSession = store.getSession(request.sessionId)
                ?: com.promenar.infinity.data.model.Session(request.sessionId, "")
            prepared.copy(messages = mediaContextLoader.attach(currentSession, prepared.messages, route.modelId))
        }
        val prompt = mediaPrompt
        val currentSession = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val finalHasDocuments = prompt.messages.any { !it.documentData.isNullOrEmpty() } ||
            currentSession.messages.firstOrNull { it.id == request.userMessageId }?.userDocuments.orEmpty().any { it.workspaceReference == null || it.inlineInCurrentTurn }
        val finalBudget = PreparedPromptBudgetGate.evaluate(currentSession, prompt, settings,
            finalHasDocuments, route.modelId)
        if (finalBudget is ContextBudgetDecision.Blocked) {
            val failure = GenerationFailure(code = GenerationFailureCode.CONTEXT_LIMIT, formatArgs = mapOf(
                GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "TOTAL_INPUT",
                GenerationFailure.KEY_REQUIRED_TOKENS to finalBudget.requiredTokens.toString(),
                GenerationFailure.KEY_AVAILABLE_TOKENS to finalBudget.availableTokens.toString(),
                GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to finalHasDocuments.toString(),
            ), technical = "media budget blocked before request: ${finalBudget.reason}")
            handleDocumentBudgetFailure(request, failure)
            return kotlinx.coroutines.flow.flowOf(GenerationChunk.Failure(failure))
        }
        preparedPrompt = prompt
        if (!route.useLocalProvider) {
            awaitTailnet(route)?.let { failure -> return kotlinx.coroutines.flow.flowOf(GenerationChunk.Failure(failure)) }
        }
        unsentInstructionIds.remove(assistantMessageId(request))
        emptyReplyReminder = false
        InfinityLogger.log(mainPromptPrefixTracker.observe(request.sessionId, prompt))
        val source = if (route.useLocalProvider) {
            localProviderFactory(prompt.model).sendPrompt(prompt)
        } else {
            val client = requireNotNull(route.client) { "Router 成功但未创建云端请求客户端" }
            client.sendStream(
                StreamTextParams(
                    messages = prompt.messages,
                    model = prompt.model,
                    temperature = prompt.temperature,
                    topP = prompt.topP,
                    maxOutputTokens = prompt.maxTokens,
                    frequencyPenalty = prompt.frequencyPenalty,
                    presencePenalty = prompt.presencePenalty,
                    topK = prompt.topK,
                    repetitionPenalty = prompt.repetitionPenalty,
                    reasoningEffort = prompt.reasoningEffort,
                    streamTimeout = prompt.streamTimeout,
                    tools = preparedTools.associateBy { it.function.name },
                    toolChoice = prompt.toolChoice,
                ),
                StreamConfig(sessionId = request.sessionId),
            )
        }
        val recorder = RequestInputUsageRecorder(currentSession, prompt, route.modelId) { baseline ->
            sessionManager.updateContextUsageBaseline(request.sessionId, baseline)
        }
        return kotlinx.coroutines.flow.flow {
            recorder.start()
            emitAll(source.mapNotNull { chunk ->
                if (!route.useLocalProvider && chunk is StreamChunk.Usage) recorder.observe(chunk.usage)
                val mapped = toGenerationChunk(chunk)?.let { mapped ->
                    if (mapped !is GenerationChunk.Failure || mapped.failure.code != GenerationFailureCode.CONTEXT_LIMIT) mapped else
                        mapped.copy(failure = mapped.failure.copy(formatArgs = mapped.failure.formatArgs + mapOf(
                            GenerationFailure.KEY_REQUIRED_TOKENS to PromptUsageCalibration.estimate(
                                store.getSession(request.sessionId) ?: currentSession, prompt, route.modelId).toString(),
                            GenerationFailure.KEY_AVAILABLE_TOKENS to (PreparedPromptBudgetGate.resolveWindow(currentSession, prompt, settings, route.modelId).inputBudgetTokens ?: 0).toString(),
                            GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to finalHasDocuments.toString(),
                        )))
                }
                mapped?.also {
                    if (it is GenerationChunk.Text) toolProgressReminder.onUserFacingText(it.content)
                }
            })
        }
    }

    /**
     * 接口在 tailnet 内且内置 Tailscale 未接入时等待接入（用户发送视为主动重试，忽略失败退避）；
     * 等待期间通过 [GenerationUiPort.setTailnetWait] 展示进度。接入失败返回失败，请求不发出。
     */
    private suspend fun awaitTailnet(route: ChatProviderRoute): GenerationFailure? {
        val gate = tailnetGate ?: return null
        var waited = false
        val result = try {
            gate.await(route.endpointHost, force = true) { progress ->
                waited = true
                ui.setTailnetWait(TailnetWaitState.Waiting(progress))
            }
        } catch (error: Throwable) {
            if (waited) ui.setTailnetWait(null)
            throw error
        }
        when (result) {
            is com.promenar.infinity.data.tailnet.TailnetConnectResult.Ready -> {
                if (waited) ui.setTailnetWait(if (result.waited) TailnetWaitState.Connected else null)
                return null
            }
            com.promenar.infinity.data.tailnet.TailnetConnectResult.NotRequired -> {
                if (waited) ui.setTailnetWait(null)
                return null
            }
            is com.promenar.infinity.data.tailnet.TailnetConnectResult.Unavailable -> {
                if (waited) ui.setTailnetWait(null)
                return GenerationFailure(
                    code = GenerationFailureCode.TAILNET_UNAVAILABLE,
                    formatArgs = mapOf(GenerationFailure.KEY_TAILNET_REASON to result.reason.name),
                    technical = "tailnet unavailable before request: reason=${result.reason}",
                )
            }
        }
    }

    override fun knownToolNames(request: GenerationRequest): Set<String> =
        preparedTools.mapTo(linkedSetOf()) { it.function.name }

    override suspend fun normalize(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot {
        val calls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val normalized = contentStrategy.normalize(snapshot.content, calls)
        if (normalized.syntheticSteps.isNotEmpty()) {
            // 基于最新视图追加，避免覆盖上一轮工具调用尚未刷入 store 的执行步骤。
            messageManager.updateFromLatest(request.sessionId, assistantMessageId(request)) { current ->
                UpdateMessageOptions(executionSteps = current.executionSteps.orEmpty() + normalized.syntheticSteps)
            }
        }
        return snapshot.copy(content = normalized.content)
    }

    override suspend fun finalizeStream(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot {
        val current = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        if (current.isNotEmpty()) return snapshot
        if (snapshot.content.isBlank()) return snapshot
        val fallback = contentStrategy.extractFallbackToolCalls(snapshot.content)
        if (fallback.isEmpty()) return snapshot
        // 文本 fallback 仅用于兼容展示清理；没有 Provider Completed IDs，绝不能转为可执行调用。
        return snapshot.copy(
            content = contentStrategy.stripToolCallMarkup(snapshot.content),
        )
    }

    override suspend fun restoreContextSnapshot(request: GenerationRequest, snapshot: GenerationSnapshot) {
        val current = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
            ?: error("重试回复不存在")
        messageManager.restoreModelReply(request.sessionId, current.id, current.copy(
            content = snapshot.content, reasoning = snapshot.reasoning.takeIf(String::isNotBlank),
            thoughtSignature = snapshot.reasoningReplay, toolCalls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
            citations = snapshot.citations.map { com.promenar.infinity.data.model.Citation(it.title, it.url, it.source) },
            tokens = mainAndSummaryUsage(request, snapshot), isError = snapshot.failure != null,
            errorMessage = snapshot.failure?.let(GenerationFailureCodec::encode),
        ))
        ui.setFailureSurface(snapshot.failure?.let { GenerationFailureSurface.INLINE_MESSAGE })
        ui.setError(snapshot.failure)
        ui.setStreamingContent(snapshot.content)
    }

    override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
        val calls = snapshot.toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        val failureEnvelope = snapshot.failure?.let(GenerationFailureCodec::encode)
        ui.setFailureSurface(
            snapshot.failure?.let { GenerationFailureSurface.INLINE_MESSAGE },
        )
        ui.setError(snapshot.failure)
        ui.setStreamingContent(snapshot.content)
        messageManager.updateMessageContent(
            request.sessionId,
            assistantMessageId(request),
            snapshot.content,
            UpdateMessageOptions(
                reasoning = snapshot.reasoning.ifBlank { null },
                thoughtSignature = snapshot.reasoningReplay,
                tokens = mainAndSummaryUsage(request, snapshot),
                toolCalls = calls.takeIf { it.isNotEmpty() },
                clearToolCalls = calls.isEmpty(),
                citations = snapshot.citations.map {
                    com.promenar.infinity.data.model.Citation(it.title, it.url, it.source)
                }.takeIf { it.isNotEmpty() },
                isError = snapshot.failure?.let { true },
                errorMessage = failureEnvelope,
                clearError = snapshot.failure == null,
            ),
        )
    }

    override suspend fun recordTaskUsage(request: GenerationRequest, snapshot: GenerationSnapshot) {
        mainSpentTokens += snapshot.totalTokens
        val usage = mainAndSummaryUsage(request, snapshot)
        messageManager.updateFromLatest(request.sessionId, assistantMessageId(request)) {
            UpdateMessageOptions(tokens = usage)
        }
    }

    private fun mainAndSummaryUsage(request: GenerationRequest, snapshot: GenerationSnapshot): TokenUsage {
        val existing = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }?.tokens
            ?: TokenUsage()
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return existing.copy(
            input = add(snapshot.inputTokens, existing.summaryInput),
            output = add(snapshot.outputTokens, existing.summaryOutput),
            total = add(snapshot.totalTokens, existing.summaryTotal),
            estimated = snapshot.tokensEstimated || existing.summaryEstimated,
            cacheReadInput = snapshot.cacheReadInputTokens,
            cacheWriteInput = snapshot.cacheWriteInputTokens,
            cacheWriteLongTtlInput = snapshot.cacheWriteLongTtlInputTokens,
            reasoningOutput = snapshot.reasoningOutputTokens,
            timing = snapshot.timing?.let {
                com.promenar.infinity.data.model.GenerationTimingRecord(it.requestStartedAt, it.firstOutputAt, it.endedAt)
            } ?: existing.timing,
        )
    }

    private suspend fun recordSummaryUsage(request: GenerationRequest, usage: TokenUsage) {
        summarySpentTokens += usage.total
        summaryUsageEstimated = summaryUsageEstimated || usage.estimated
        val message = if (manualCompaction) null else store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
        if (message == null) {
            // 没有助手消息的手动任务将费用写入既有摘要账本，不创建会话占位消息。
            val record = RolledBackSummaryUsageRecord(
                messageId = "manual-summary-${java.util.UUID.randomUUID()}",
                sessionId = request.sessionId,
                modelId = preparedRoute?.modelId ?: preparedPrompt?.model.orEmpty(),
                usage = usage,
                createdAt = System.currentTimeMillis(),
            )
            RolledBackSummaryUsageStore(settings).preserveBeforeDiscard(listOf(record)) {}
            return
        }
        val existing = message.tokens ?: TokenUsage()
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val combined = existing.copy(
            input = add(existing.input, usage.input), output = add(existing.output, usage.output), total = add(existing.total, usage.total),
            estimated = existing.estimated || usage.estimated,
            summaryInput = add(existing.summaryInput, usage.input), summaryOutput = add(existing.summaryOutput, usage.output),
            summaryTotal = add(existing.summaryTotal, usage.total), summaryEstimated = existing.summaryEstimated || usage.estimated,
        )
        messageManager.updateMessageContent(request.sessionId, assistantMessageId(request), message.content, UpdateMessageOptions(tokens = combined))
        messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
    }

    /** 子代理用量计入父会话本轮：并入助手消息的附加用量，不计入主模型的速度统计。 */
    private suspend fun recordSubagentUsage(request: GenerationRequest) {
        val usage = subagentUsage?.drain(request.sessionId) ?: return
        // 取出即结算：写入在不可取消区内完成，取消不会让同一笔用量被重复计入。
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, usage) }
        } catch (error: Exception) {
            InfinityLogger.logError("[GenerationRuntime] 子代理用量写入失败", error)
        }
    }

    override fun additionalTokenUsage(request: GenerationRequest): GenerationToolUsage =
        GenerationToolUsage(tokens = summarySpentTokens, tokensEstimated = summaryUsageEstimated)

    override suspend fun handleTools(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision {
        val queue = composerQueueStore?.observe(request.sessionId)
        val decision = if (queue == null) handleToolRound(request, toolCalls) else
            withContext(com.promenar.infinity.data.exec.SteeringContext(queue)) { handleToolRound(request, toolCalls) }
        if (toolCalls.isNotEmpty() && decision == GenerationToolDecision.CONTINUE) toolProgressReminder.onToolsCompleted()
        return decision
    }

    private suspend fun handleToolRound(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision {
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        settleQuestionResumption(request)
        val calls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
        if (kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.exec.SteeringContext]?.pending() == true) {
            toolExecutor.rejectTools(request.sessionId, assistantMessageId(request), calls, "用户插入了新消息，此调用未执行。")
            return GenerationToolDecision.CONTINUE
        }
        if (calls.any { it.name == "session_branch" }) {
            toolRounds++
            val repository = branchPlans
            val advertised = preparedTools.singleOrNull { it.function.name == "session_branch" }
            val current = if (request.interactionMode == com.promenar.infinity.domain.generation.GenerationInteractionMode.INTERACTIVE)
                buildToolsOffMain(session).singleOrNull { it.function.name == "session_branch" } else null
            if (repository == null || com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
                toolExecutor.rejectTools(request.sessionId, assistantMessageId(request), calls, "当前运行无法创建分支方案。")
                return GenerationToolDecision.CONTINUE
            }
            val result = repository.createBatch(request.sessionId, assistantMessageId(request), calls, current?.takeIf { it == advertised })
            result.messages.forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
            if (result.pending != null) {
                store.updateSession(request.sessionId) { it.copy(approvalRequest = null, loopStatus = com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT) }
                commitReplacement(request)
                ui.setGenerating(false)
                return GenerationToolDecision.WAIT_FOR_APPROVAL
            }
            return GenerationToolDecision.CONTINUE
        }
        if (calls.any { it.name == com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME }) {
            toolRounds++
            val repository = askUserLedger
            if (repository == null || com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
                for (call in calls) toolExecutor.rejectTools(request.sessionId, assistantMessageId(request), listOf(call),
                    com.promenar.infinity.ui.chat.manager.AskUserProtocol.unavailable())
                return GenerationToolDecision.CONTINUE
            }
            val advertised = preparedTools.singleOrNull { it.function.name == com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME }
            val current = if (request.interactionMode == com.promenar.infinity.domain.generation.GenerationInteractionMode.INTERACTIVE)
                buildToolsOffMain(session).singleOrNull { it.function.name == com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME }
                else null
            try {
                val result = repository.createBatch(request.sessionId, assistantMessageId(request), calls, current?.takeIf { it == advertised })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                result.messages.forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
                if (result.pending != null) {
                    store.updateSession(request.sessionId) { it.copy(approvalRequest = null, loopStatus = com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT) }
                    commitReplacement(request)
                    ui.setGenerating(false)
                    return GenerationToolDecision.WAIT_FOR_INPUT
                }
            } catch (cancelled: CancellationException) {
                // 创建已提交但尚未镜像至界面时停止，也必须关闭账本中的提问。
                withContext(kotlinx.coroutines.NonCancellable) {
                    repository.cancel(request.sessionId).forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
                }
                throw cancelled
            }
            return GenerationToolDecision.CONTINUE
        }
        if (calls.any { it.name == com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME }) {
            toolRounds++
            val repository = secretInputLedger
            if (repository == null || com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
                for (call in calls) toolExecutor.rejectTools(request.sessionId, assistantMessageId(request), listOf(call),
                    com.promenar.infinity.ui.chat.manager.skills.SecretRequestProtocol.failure("SECRET_INPUT_UNAVAILABLE"))
                return GenerationToolDecision.CONTINUE
            }
            val advertised = preparedTools.singleOrNull { it.function.name == com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME }
            val current = if (request.interactionMode == com.promenar.infinity.domain.generation.GenerationInteractionMode.INTERACTIVE)
                buildToolsOffMain(session).singleOrNull { it.function.name == com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME } else null
            try {
                val result = repository.createBatch(request.sessionId, assistantMessageId(request), calls, current?.takeIf { it == advertised })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                result.messages.forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
                if (result.pending != null) {
                    store.updateSession(request.sessionId) { it.copy(approvalRequest = null, loopStatus = com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT) }
                    commitReplacement(request)
                    ui.setGenerating(false)
                    return GenerationToolDecision.WAIT_FOR_INPUT
                }
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    askUserLedger?.cancel(request.sessionId)?.forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
                }
                throw cancelled
            }
            return GenerationToolDecision.CONTINUE
        }
        ensureAutomaticConsent(request, session.executionMode.ifEmpty { "workspace" })
        if (com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
            toolRounds++
            try {
                toolExecutor.executeTools(request.sessionId, assistantMessageId(request), calls, preparedTools = preparedTools)
            } finally { recordSubagentUsage(request) }
            return GenerationToolDecision.CONTINUE
        }
        val preparedByName = preparedTools.groupBy { it.function.name }
        val configPreflights = calls.filter { it.name == com.promenar.infinity.domain.agentconfig.AgentConfigTools.WRITE }
            .associate { call -> call.id to (agentConfigService?.invoke()?.preflight(session, call.arguments)
                ?: com.promenar.infinity.data.agentconfig.AgentConfigPreflight(error = "配置工具不可用")) }
        val invalidConfigCalls = configPreflights.filterValues { it.error != null }.keys
        val targets = calls.associate { call ->
            val tool = preparedByName[call.name]?.singleOrNull()
            call.id to tool?.let { if (call.name == com.promenar.infinity.domain.agentconfig.AgentConfigTools.WRITE) {
                configPreflights[call.id]?.target
            } else if (call.name == "request_knowledge_access") {
                val args = (com.promenar.infinity.domain.tool.ToolArgumentsValidator().validate(call.arguments) as? com.promenar.infinity.domain.tool.ToolArgumentsValidation.Valid)?.arguments
                val base = (args?.get("knowledge_base") as? kotlinx.serialization.json.JsonPrimitive)?.content
                knowledgeAccessPlanner?.preview(session.id, base)?.let { com.promenar.infinity.data.model.json.encodeToString(it) }
            } else branchService?.invoke()?.approvalTarget(session, call.name, call.arguments) ?: resolveSessionToolTarget(session, it, call.arguments, workspaceRepository, externalFolders, sshRuntime?.hostStore, sshRuntime?.let { runtime -> runtime::sessionHost }, linuxFiles) }
        }
        val mode = com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(session.executionMode)
        val turnId = request.userMessageId ?: com.promenar.infinity.data.knowledge.knowledgeTurnId(session, assistantMessageId(request))
        knowledgeTurns?.seed(session, turnId)
        val grantCategories = calls.associate { call ->
            call.id to com.promenar.infinity.domain.tool.ToolExecutionPolicy.grantCategory(
                mode, call.name, preparedByName[call.name]?.singleOrNull()?.risk ?: contentStrategy.riskForTool(call.name),
                call.arguments, targets[call.id], usesLinux = session.runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX,
            )
        }
        // 授权在本轮判定时取快照，执行中到期不改变已经准入的调用。
        val timedGrantTargets = calls.filter { call ->
            grantCategories[call.id]?.let { timedGrants?.isValid(session.id, it) } == true
        }.associate { it.id to targets[it.id] }
        val requestedPendingIds = calls.filter { call ->
            call.id !in invalidConfigCalls && call.id !in timedGrantTargets &&
            !(call.name == "request_knowledge_access" && knowledgeTurns?.isDenied(session.id, turnId) == true) &&
            com.promenar.infinity.domain.tool.ToolExecutionPolicy.requiresApproval(
                mode, call.name, preparedByName[call.name]?.singleOrNull()?.risk ?: contentStrategy.riskForTool(call.name),
                call.arguments, targets[call.id], usesLinux = session.runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX,
            )
        }.mapTo(mutableSetOf()) { it.id }
        val grantTargets = emptyMap<String, String>()
        val validInvocations = calls.mapNotNull { call ->
            val tool = preparedByName[call.name]?.singleOrNull() ?: return@mapNotNull null
            val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(
                call = call,
                tool = tool,
                requiresApproval = call.id in requestedPendingIds,
            ) as? ToolInvocationIdentityResolution.Valid ?: return@mapNotNull null
            Triple(call, tool, resolved.identity)
        }
        val pendingIds = validInvocations
            .filter { (call, _, identity) -> call.id in requestedPendingIds && identity.requiresApproval }
            .map { it.first.id }
        val allowedIds = validInvocations
            .filterNot { it.first.id in pendingIds }
            .mapTo(linkedSetOf()) { it.first.id }
        if (pendingIds.isEmpty()) toolRounds++
        try {
            toolExecutor.executeTools(
                request.sessionId,
                assistantMessageId(request),
                calls,
                allowedIds,
                preparedTools,
                expectedGrantTargets = grantTargets,
                timedGrantTargets = timedGrantTargets,
            )
        } finally {
            // 停止本轮时子代理已消耗的用量同样计入本轮，不留给下一轮。
            recordSubagentUsage(request)
        }
        if (kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.exec.SteeringContext]?.pending() == true) {
            // 待审批集合在执行前计算；插队后的跳过结果已经配对，不能再用旧集合创建审批。
            toolExecutor.rejectTools(request.sessionId, assistantMessageId(request), calls.filter { it.id in pendingIds }, "用户插入了新消息，此调用未执行。")
            store.updateSession(request.sessionId) { it.copy(approvalRequest = null, loopStatus = com.promenar.infinity.data.model.LoopStatus.RUNNING) }
            return GenerationToolDecision.CONTINUE
        }
        if (pendingIds.isNotEmpty()) {
            val approval = ToolApprovalRequestFactory.create(
                assistantMessageId = assistantMessageId(request),
                candidates = validInvocations.filter { it.first.id in pendingIds }.map { (call, tool, identity) ->
                    ApprovalCallCandidate(call, tool.risk, identity, executionTarget = targets[call.id], grantCategory = grantCategories[call.id])
                },
                reason = "Execution mode: ${session.executionMode.ifEmpty { "workspace" }}",
            )
            messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
            val creation = toolLedger.createToolApproval(
                request.sessionId,
                assistantMessageId(request),
                calls,
                pendingIds.toSet(),
                approval,
            )
            if (creation == ToolApprovalCreation.CONFLICT) {
                ui.setFailureSurface(GenerationFailureSurface.OVERLAY)
                ui.setError(
                    GenerationFailure.busy(
                        technical = "tool approval conflict: sessionId=${request.sessionId}",
                    ),
                )
                ui.setGenerating(false)
                return GenerationToolDecision.WAIT_FOR_APPROVAL
            }
            messageManager.mirrorPersistedPendingApprovalState(
                request.sessionId,
                assistantMessageId(request),
                pendingIds,
            )
            store.updateSession(request.sessionId) {
                it.copy(
                    approvalRequest = approval,
                    loopStatus = com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_APPROVAL,
                )
            }
            // 新回复已持久化待审批调用，本次运行在此结束且不会再到达终态，旧回复必须在此完成替换。
            commitReplacement(request)
            ui.setGenerating(false)
            return GenerationToolDecision.WAIT_FOR_APPROVAL
        }
        return GenerationToolDecision.CONTINUE
    }

    override suspend fun interruptPendingTools(request: GenerationRequest, toolCalls: List<GenerationToolCall>): Boolean {
        val queue = composerQueueStore?.observe(request.sessionId) ?: return false
        if (!com.promenar.infinity.data.exec.SteeringContext(queue).pending()) return false
        withContext(kotlinx.coroutines.NonCancellable) {
            val reason = "用户插队打断了本步，调用未执行。"
            if (toolCalls.any { it.name in setOf("session_branch", com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME, com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME) }) {
                requireNotNull(askUserLedger) { "等待账本不可用，无法安全中断提问" }
                    .cancel(request.sessionId, interruptionReason = reason)
                    .forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
            }
            toolExecutor.rejectTools(request.sessionId, assistantMessageId(request),
                toolCalls.map { ToolCall(it.id, it.name, it.arguments) }, reason)
            val transition = toolLedger.completeToolApproval(request.sessionId, assistantMessageId(request))
            transition.terminalMessages.forEach { messageManager.mirrorPersistedMessage(request.sessionId, it) }
            messageManager.mirrorPersistedPendingApprovalState(request.sessionId, assistantMessageId(request), null)
            store.updateSession(request.sessionId) { it.copy(approvalRequest = null, loopStatus = com.promenar.infinity.data.model.LoopStatus.RUNNING) }
            ui.setGenerating(true)
        }
        return true
    }

    override fun toolBudget(request: GenerationRequest): GenerationToolBudget = activeToolBudget ?: readToolBudget()

    private fun readToolBudget(): GenerationToolBudget {
        val saved = settings.getInt(LOOP_LIMIT_KEY, DEFAULT_TOOL_ROUNDS_PER_GENERATION)
        return GenerationToolBudget(
            maxRounds = if (saved == 0) 0 else saved.coerceIn(1, MAX_CONFIGURABLE_TOOL_ROUNDS),
            maxCalls = if (saved == 0) 0 else com.promenar.infinity.domain.generation.MAX_TOOL_CALLS_PER_GENERATION,
            maxTokens = settings.getInt("task_token_limit", 0).coerceAtLeast(0).toLong(),
        )
    }

    override fun estimatedInputTokens(request: GenerationRequest): Int = preparedPrompt?.let { prompt ->
        store.getSession(request.sessionId)?.let { PromptUsageCalibration.estimate(it, prompt, preparedRoute?.modelId) }
            ?: ApproximateTokenEstimator.estimate(prompt)
    } ?: 0

    override fun maxStreamRetries(request: GenerationRequest): Int =
        settings.getInt(STREAM_RETRY_LIMIT_KEY, DEFAULT_STREAM_RETRY_LIMIT).coerceIn(0, MAX_STREAM_RETRY_LIMIT)

    override fun loopDetectionThreshold(request: GenerationRequest): Int =
        settings.getInt("tool_loop_repeat_limit", 3).coerceIn(2, 20)

    override suspend fun confirmLoopContinuation(request: GenerationRequest): Boolean =
        interactionStore?.awaitDecision(request.sessionId, GenerationInteractionKind.REPEATED_TOOL, request.interactionMode) == true

    override suspend fun consumedToolUsage(request: GenerationRequest): GenerationToolUsage {
        val messages = store.getSession(request.sessionId)?.messages ?: return GenerationToolUsage()
        return consumedToolUsageInTurn(messages, request)
    }

    override suspend fun rejectToolCalls(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
        reason: String,
    ) {
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        settleQuestionResumption(request)
        toolExecutor.rejectTools(
            request.sessionId,
            assistantMessageId(request),
            toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
            reason,
        )
    }

    override fun enterFinalAnswerMode(request: GenerationRequest) {
        finalAnswerMode = true
    }

    override fun requestReplyAfterEmptyTurn(request: GenerationRequest): Boolean {
        emptyReplyReminder = true
        // 当前回复的尾部指令已随上一次请求发出；重新标为未发送，下一次发送时改写为提醒。
        unsentInstructionIds += assistantMessageId(request)
        return true
    }

    override suspend fun steeringContext(request: GenerationRequest): com.promenar.infinity.data.exec.SteeringContext? =
        composerQueueStore?.observe(request.sessionId)?.let { com.promenar.infinity.data.exec.SteeringContext(it) }

    override suspend fun markStepInterrupted(request: GenerationRequest, snapshot: GenerationSnapshot) {
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
        messageManager.markGenerationTerminal(request.sessionId, assistantMessageId(request), snapshot.content, "interrupted")
    }

    override suspend fun injectSteering(request: GenerationRequest): GenerationRequest? = injectQueuedMessage(request, true)
    override suspend fun injectQueuedAtBoundary(request: GenerationRequest): GenerationRequest? = injectQueuedMessage(request, false)
    private suspend fun injectQueuedMessage(request: GenerationRequest, steeringOnly: Boolean): GenerationRequest? {
        val queue = composerQueueStore ?: return null
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val item = (if (steeringOnly) queue.claimSteering(request.sessionId) else queue.claimBoundary(request.sessionId)) ?: return null
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                queue.rollback(request.sessionId, item.id, com.promenar.infinity.data.chat.queue.QueuePauseReason.INTERRUPTED)
            }
            throw cancelled
        }
        // 消息插入和认领回执形成不可取消区间，避免停止时消息已落盘却又回到托盘。
        return withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            val userId = "steer_${item.id}"
            var inserted = false
            try {
                val annotationSession = store.getSession(request.sessionId) ?: error("会话已不存在，无法读取排队画板。")
                val loadedImages = item.imagePaths.map { QueuedUserImage.read(it, annotationSession) }
                val images = loadedImages.map { it.dataUrl }
                val annotations = loadedImages.map { it.annotation }.takeIf { values -> values.any { it != null } }
                queue.bindSending(request.sessionId, item.id, userId)
                messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))
                val target = store.getSession(request.sessionId)?.messages?.firstOrNull { it.id == assistantMessageId(request) }
                    ?: error("插队目标消息不存在")
                messageManager.markGenerationTerminal(request.sessionId, target.id, target.content,
                    if (target.status == "interrupted") "interrupted" else "success")
                if (request.messageIdsToReplace.isNotEmpty()) commitReplacement(request)
                messageManager.addMessage(request.sessionId, com.promenar.infinity.data.model.Message(
                    id = userId, role = MessageRole.USER, content = item.text,
                    userImages = images.takeIf { it.isNotEmpty() }, userDocuments = item.documents.takeIf { it.isNotEmpty() },
                    userImageAnnotations = annotations,
                    userImageAnnotationContext = annotations?.let { com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(annotationSession) },
                    quotes = item.quotes.takeIf { it.isNotEmpty() }, createdAt = System.currentTimeMillis(),
                ))
                inserted = true
                queue.accept(request.sessionId, item.id)
                frozenTurnContext = null
                replacementTurnContext = null
                cachedRetrieval = null
                preparedContext = null
                emptyReplyReminder = false
                compactionTurnState.historySummaryAttempted = false
                request.copy(userMessageId = userId, userContent = item.text, imageDataUrls = images,
                    rollbackUserOnPreparationFailure = false, messageIdsToReplace = emptySet())
            } catch (failure: Throwable) {
                // 已插入的消息按绑定 ID 恢复回执；尚未插入的消息保留待用户重试。
                if (!inserted) queue.rollback(request.sessionId, item.id, com.promenar.infinity.data.chat.queue.QueuePauseReason.FAILED)
                throw failure
            }
        }
    }

    /** 只继承本轮成功的内置状态工具读数，恢复生成时也不必改写初始快照。 */
    private fun observedStateRevision(session: com.promenar.infinity.data.model.Session, request: GenerationRequest): Long {
        val start = session.messages.indexOfFirst { it.id == request.userMessageId && it.role == MessageRole.USER }
        if (start < 0) return 0
        val stateTools = setOf("state_define", "state_update", "state_get", "state_remove")
        val history = sanitizeProtocolHistory(session.messages.drop(start + 1).takeWhile { it.role != MessageRole.USER }
            .filterNot { it.id in request.messageIdsToReplace || it.parentMessageId in request.messageIdsToReplace })
        val calls = history.filter { it.role == MessageRole.ASSISTANT }.flatMap { it.toolCalls.orEmpty() }
            .filter { it.name in stateTools }.associate { it.id to it.name }
        return history.asSequence()
            .filter { it.role == MessageRole.TOOL && !it.isError && it.name in stateTools && calls[it.toolCallId] == it.name }
            .mapNotNull { message -> runCatching {
                val result = com.promenar.infinity.data.model.json.parseToJsonElement(message.content) as? kotlinx.serialization.json.JsonObject
                val action = (result?.get("action") as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (action != message.name || result?.containsKey("error") == true) null else
                    (result?.get("revision") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 }
            }.getOrNull() }.maxOrNull() ?: 0
    }

    override suspend fun prepareContinuation(request: GenerationRequest): String {
        val session = store.getSession(request.sessionId) ?: error("生成会话不存在")
        val nextAssistantMessageId = com.promenar.infinity.domain.usecase.IdGenerator.message("ai")
        messageManager.addMessage(
            request.sessionId,
            com.promenar.infinity.data.model.Message(
                id = nextAssistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelId = session.modelId,
                parentMessageId = request.userMessageId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        currentAssistantMessageId = nextAssistantMessageId
        stateBoardRepository?.recordContextRead(request.sessionId, nextAssistantMessageId, observedStateRevision)
        return nextAssistantMessageId
    }

    override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) {
        ui.setStreamingContent("")
        // 审批通过后由审批流程执行的子代理，用量在本轮结束时汇入。
        recordSubagentUsage(request)
        val userMessageId = request.userMessageId ?: return
        val context = requireNotNull(preparedContext)
        val session = store.getSession(request.sessionId) ?: return
        val userContent = userContent(request, session)
        try {
            val rag = session.ragOptions
            postProcessor.updateStats(
                PostProcessorParams(
                    sessionId = request.sessionId,
                    assistantMsgId = assistantMessageId(request),
                    userMsgId = userMessageId,
                    userContent = userContent,
                    assistantContent = snapshot.content,
                    agent = com.promenar.infinity.data.model.Agent(session.agentId, session.agentId),
                    session = session,
                    ragEnabled = rag?.enableMemory == true || rag?.enableDocs == true,
                    ragUsage = com.promenar.infinity.data.knowledge.mergeKnowledgeUsage(context.ragUsage, knowledgeTurns?.usage(session.id, userMessageId)),
                    accumulatedUsage = TokenUsage(snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens),
                    totalContextTokens = context.ragUsage?.ragSystem ?: 0,
                    modelId = session.modelId ?: "",
                ),
            )
            val memory = memoryManager
            // 未配置嵌入模型时不写入也不显示写入状态，由嵌入模型设置提示引导用户。
            if (memoryEnabled(session) && userContent.isNotBlank() &&
                snapshot.content.isNotBlank() && memory != null && memory.canEmbed
            ) {
                val turnMessageId = assistantMessageId(request)
                messageManager.writeTurnMemory(request.sessionId, turnMessageId, previous = null) {
                    val written = memory.addTurnToMemory(
                        request.sessionId,
                        userContent,
                        snapshot.content,
                        userMessageId,
                        turnMessageId,
                    )
                    if (written > 0) TurnMemoryState(TurnMemoryState.Status.STORED, written, System.currentTimeMillis()) else null
                }
            }
            // 后台抽取从数据库读取本轮正文，并在写回前核对正文未变；流式正文按防抖落库，
            // 此时可能还缺最后一段，必须先落库，否则抽取读到残缺正文、结果又在终态写入后被判为过期。
            val extractable = snapshot.failure == null && memoryEnabled(session) &&
                userContent.isNotBlank() && snapshot.content.isNotBlank() &&
                (sessionGraph != null || sessionState != null) && try {
                    messageManager.flushNonApprovalUpdatesNow(request.sessionId, assistantMessageId(request))
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    InfinityLogger.logError("[GenerationRuntime] 本轮正文落库失败，跳过后台抽取", error)
                    false
                }
            // 图谱抽取只需要抽取模型，不依赖嵌入模型；会话关闭记忆时同样不抽取对话内容。
            val graph = sessionGraph
            if (graph != null && extractable) {
                try {
                    graph.schedule(request.sessionId, assistantMessageId(request), userMessageId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    InfinityLogger.logError("[GenerationRuntime] 会话图谱抽取排队失败", error)
                }
            }
            if (sessionState != null && extractable && memory?.canEmbed == true) {
                try {
                    sessionState.schedule(request.sessionId, assistantMessageId(request), userMessageId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    InfinityLogger.logError("[GenerationRuntime] 状态记忆抽取排队失败", error)
                }
            }
            // 轮后按下一次请求实际发送的未摘要历史估算，不使用累计账单 token 触发摘要。
            val nextPrompt = (preparedPrompt ?: PromptRequest(model = session.modelId.orEmpty(), messages = emptyList()))
            compactContext(request, store.getSession(request.sessionId) ?: session, nextPrompt, context.finalSystemPrompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[GenerationRuntime] postProcess failed", error)
        }
    }

    override suspend fun markTerminal(
        request: GenerationRequest,
        status: GenerationTerminalStatus,
        snapshot: GenerationSnapshot,
        cause: Throwable?,
    ) {
        messageManager.markGenerationTerminal(
            request.sessionId,
            assistantMessageId(request),
            snapshot.content,
            when (status) {
                GenerationTerminalStatus.SUCCESS -> "success"
                GenerationTerminalStatus.ERROR -> "error"
                GenerationTerminalStatus.CANCELLED -> "cancelled"
            },
            if (status == GenerationTerminalStatus.ERROR) {
                GenerationFailureCodec.encode(
                    snapshot.failure
                        ?: cause?.failureOrUnknown()
                        ?: GenerationFailure.unknown("terminal error without failure"),
                )
            } else null,
        )
        settleQuestionResumption(request)
        val citationSession = if (status == GenerationTerminalStatus.SUCCESS && chatCitationRecorder != null) {
            store.getSession(request.sessionId)
        } else null
        val createdCitationIds = if (citationSession != null) messagesCreatedSincePrepare(request).toSet() else emptySet()
        if (request.messageIdsToReplace.isNotEmpty()) {
            if (status == GenerationTerminalStatus.SUCCESS) {
                commitReplacement(request)
            } else if (!replacementCommitted) {
                discardPreparedMessagesPreservingSummaryUsage(
                    request,
                    messagesCreatedSincePrepare(request),
                )
            }
        }
        if (citationSession != null && chatCitationRecorder != null) {
            val retained = store.getSession(request.sessionId)?.messages.orEmpty().mapTo(hashSetOf()) { it.id }
            com.promenar.infinity.data.library.ask.completedCitationMessages(
                citationSession, createdCitationIds, retained, request.messageIdsToReplace,
                request.userMessageId ?: knowledgeTurnId(citationSession, assistantMessageId(request)),
            ).forEach { message ->
                val turnId = knowledgeTurnId(citationSession, message.id)
                knowledgeTurns?.seed(citationSession, turnId)
                val content = if (message.id == assistantMessageId(request)) snapshot.content else message.content
                chatCitationRecorder.record(citationSession, message.id, content,
                    knowledgeTurns?.citationMapping(citationSession.id, turnId).orEmpty(),
                    preparedContext?.ragReferences.orEmpty())
            }
        }
    }

    override suspend fun flush(request: GenerationRequest) =
        messageManager.flushGenerationTerminal(request.sessionId, assistantMessageId(request))

    override suspend fun pauseForUser(request: GenerationRequest, snapshot: GenerationSnapshot) {
        messageManager.markGenerationTerminal(request.sessionId, assistantMessageId(request), snapshot.content, "paused")
        sessionManager.updateSession(request.sessionId, mapOf("loopStatus" to com.promenar.infinity.data.model.LoopStatus.PAUSED))
    }

    override suspend fun releaseToolResources(request: GenerationRequest, awaitingApproval: Boolean) {
        try {
            val userId = request.userMessageId
            if (store.getSession(request.sessionId)?.messages.orEmpty().any { it.id == userId }) {
                composerQueueStore?.acceptForUser(request.sessionId, userId)
            } else {
                composerQueueStore?.rollbackForUser(request.sessionId, userId)
            }
        } finally {
            val owner = "agent:${request.sessionId}"
            try {
                if (awaitingApproval) sshRuntime?.pauseOwner(owner) else sshRuntime?.closeOwner(owner)
            } finally { stateBoardRepository?.endGeneration(request.sessionId, request.assistantMessageId) }
        }
    }

    override suspend fun cancelBeforeStart(request: GenerationRequest) {
        if (request.messageIdsToReplace.isNotEmpty()) {
            messageManager.discardPreparedMessages(request.sessionId, listOf(request.assistantMessageId))
        } else {
            messageManager.markGenerationTerminal(request.sessionId, request.assistantMessageId, "", "cancelled")
            messageManager.flushGenerationTerminal(request.sessionId, request.assistantMessageId)
        }
        composerQueueStore?.acceptForUser(request.sessionId, request.userMessageId)
    }

    // 网络流与本地推理由任务自身的协程取消；共享 Provider 的取消入口会影响其它会话。
    override fun cancelProvider() = Unit

    /** 删除被本次生成替换的旧回复；只执行一次，删除失败向上抛出以保留旧回复并走持久化失败路径。 */
    private suspend fun commitReplacement(request: GenerationRequest) {
        if (replacementCommitted || request.messageIdsToReplace.isEmpty()) return
        val present = store.getSession(request.sessionId)?.messages.orEmpty().mapTo(hashSetOf()) { it.id }
        request.messageIdsToReplace.filter { it in present }.forEach { replacedId ->
            if (store.getSession(request.sessionId)?.messages?.any { it.id == replacedId } == true) {
                messageManager.deleteMessage(request.sessionId, replacedId)
            }
        }
        replacementCommitted = true
    }

    /**
     * 本轮是否使用对话记忆：按检索时实际生效的选项判断；会话未保存检索选项（null）时即为默认选项，记忆开启。
     */
    private fun memoryEnabled(session: com.promenar.infinity.data.model.Session): Boolean =
        preparedContext?.retrieval?.effectiveRagOptions?.enableMemory
            ?: (session.ragOptions ?: RagOptions()).enableMemory

    private fun assistantMessageId(request: GenerationRequest): String =
        currentAssistantMessageId ?: request.assistantMessageId

    private fun messagesCreatedSincePrepare(request: GenerationRequest): List<String> {
        val currentIds = store.getSession(request.sessionId)?.messages.orEmpty()
            .map { it.id }
            .filterNot { it in rollbackBaselineMessageIds }
        return (listOf(request.assistantMessageId) + currentIds + assistantMessageId(request)).distinct()
    }

    private suspend fun handlePreparationFailure(
        request: GenerationRequest,
        generationFailure: GenerationFailure,
        failure: ProviderResolution.Failure?,
    ) {
        if (refreshingForSend) return
        ui.setFailureSurface(
            if (request.messageIdsToReplace.isEmpty()) {
                GenerationFailureSurface.INLINE_MESSAGE
            } else {
                GenerationFailureSurface.OVERLAY
            },
        )
        ui.setError(generationFailure)
        ui.setProviderFailure(failure)
        ui.setGenerating(false)
        ui.onHandledFailure()
        if (request.messageIdsToReplace.isNotEmpty()) {
            discardPreparedMessagesPreservingSummaryUsage(
                request,
                messagesCreatedSincePrepare(request),
            )
        } else {
            messageManager.markGenerationTerminal(
                request.sessionId,
                assistantMessageId(request),
                "",
                "error",
                GenerationFailureCodec.encode(generationFailure),
            )
        }
        settleQuestionResumption(request)
    }

    private suspend fun handleDocumentBudgetFailure(
        request: GenerationRequest,
        generationFailure: GenerationFailure,
    ) {
        if (refreshingForSend) return
        ui.setFailureSurface(GenerationFailureSurface.OVERLAY)
        ui.setError(generationFailure)
        ui.setProviderFailure(null)
        ui.setGenerating(false)
        ui.onHandledFailure()
        if (askUserLedger?.resumptionForAssistant(request.sessionId, request.assistantMessageId) != null) {
            messageManager.markGenerationTerminal(request.sessionId, assistantMessageId(request), "", "error", GenerationFailureCodec.encode(generationFailure))
            settleQuestionResumption(request)
            return
        }
        if (request.messageIdsToReplace.isNotEmpty()) {
            discardPreparedMessagesPreservingSummaryUsage(
                request,
                messagesCreatedSincePrepare(request),
            )
        } else if (toolRounds == 0) {
            val preparedIds = listOfNotNull(
                assistantMessageId(request),
                request.userMessageId.takeIf { request.rollbackUserOnPreparationFailure },
            )
            discardPreparedMessagesPreservingSummaryUsage(request, preparedIds)
        }
    }

    /** 首个模型回合或终态已落盘后才关闭恢复入口；工具副作用发生前必须完成。 */
    private suspend fun settleQuestionResumption(request: GenerationRequest) {
        if (askUserLedger?.consumeResumption(request.sessionId, request.assistantMessageId) == true) {
            store.updateSession(request.sessionId) { it.copy(loopStatus = com.promenar.infinity.data.model.LoopStatus.RUNNING) }
        }
    }

    private suspend fun discardPreparedMessagesPreservingSummaryUsage(request: GenerationRequest, messageIds: List<String>) {
        if (request.messageIdsToReplace.isNotEmpty() && !replacementCommitted) {
            replacementTurnContext?.let { (userId, original) ->
                messageManager.persistModelInputs(request.sessionId, userId, mapOf("turnContext" to original))
            }
        }
        val session = store.getSession(request.sessionId)
        val ids = messageIds.toSet()
        val usageRecords = session?.messages.orEmpty().filter { it.id in ids }.mapNotNull { message ->
            val tokens = message.tokens ?: return@mapNotNull null
            if (tokens.summaryInput <= 0 && tokens.summaryOutput <= 0 && tokens.summaryTotal <= 0) return@mapNotNull null
            RolledBackSummaryUsageRecord(
                messageId = message.id,
                sessionId = request.sessionId,
                modelId = message.modelId ?: session?.modelId ?: "unknown",
                usage = TokenUsage(tokens.summaryInput, tokens.summaryOutput, tokens.summaryTotal, tokens.summaryEstimated),
                createdAt = message.createdAt,
            )
        }
        // 无摘要费用时保留原调度；已有费用的补偿重试仍须与统计读取串行。
        if (usageRecords.isEmpty() && summarySpentTokens == 0L) {
            messageManager.discardPreparedMessages(request.sessionId, messageIds)
            return
        }
        RolledBackSummaryUsageStore(settings).preserveBeforeDiscard(usageRecords) {
            messageManager.discardPreparedMessages(request.sessionId, messageIds)
        }
    }

    private suspend fun archiveMessages(
        request: GenerationRequest,
        modelId: String,
        messages: List<com.promenar.infinity.data.model.Message>,
    ) {
        val taskId = ui.addPostProcessTask(PostProcessType.ARCHIVE_TO_RAG, "")
        try {
            postProcessor.archiveMessagesToRag(request.sessionId, messages.map { it.copy(content = it.contentForModel(), quotes = null) }, modelId) { progress, detail ->
                ui.updatePostProcessTask(taskId, progress = progress)
            }
            ui.updatePostProcessTask(taskId, PostProcessStatus.DONE, 1f)
            applicationScope.launch {
                kotlinx.coroutines.delay(3000)
                ui.removePostProcessTask(taskId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
        }
    }

    private suspend fun summarize(
        request: GenerationRequest,
        session: com.promenar.infinity.data.model.Session,
        messages: List<com.promenar.infinity.data.model.Message>,
    ): Boolean {
        val taskId = ui.addPostProcessTask(PostProcessType.AUTO_SUMMARY, "")
        return try {
            var usage: TokenUsage? = null
            val summary = try {
                summaryManager.summarizeOrNull(
                    session.summary,
                    messages.map { it.copy(content = it.contentForModel(), quotes = null) },
                    preparedRoute?.modelId ?: preparedPrompt?.model ?: session.modelId.orEmpty(),
                    onProgress = { ui.updatePostProcessTask(taskId, progress = 0.5f) },
                    onUsage = { usage = it },
                    sessionId = request.sessionId,
                    sourceSession = session,
                )
            } finally {
                usage?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recordSummaryUsage(request, it) } }
            }
            if (summary.isNullOrBlank()) {
                ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
                return false
            }
            sessionManager.commitContextCompaction(
                request.sessionId, summary, messages.map { it.id },
                com.promenar.infinity.data.model.ContextCompactionSource(session.summary, messages.toList()),
            )
            ui.updatePostProcessTask(taskId, PostProcessStatus.DONE, 1f)
            applicationScope.launch {
                kotlinx.coroutines.delay(3000)
                ui.removePostProcessTask(taskId)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ui.updatePostProcessTask(taskId, PostProcessStatus.ERROR, detail = null)
            false
        }
    }

    private fun toGenerationChunk(chunk: StreamChunk): GenerationChunk? = when (chunk) {
        is StreamChunk.TextDelta -> GenerationChunk.Text(chunk.content, chunk.reasoning)
        is StreamChunk.Thinking -> GenerationChunk.Thinking(chunk.content)
        is StreamChunk.ToolCallDelta -> GenerationChunk.ToolCall(chunk.id, chunk.name, chunk.arguments)
        is StreamChunk.Usage -> GenerationChunk.Usage(
            input = chunk.usage.input,
            output = chunk.usage.output,
            total = chunk.usage.total,
            cacheReadInput = chunk.usage.cacheReadInput,
            cacheWriteInput = chunk.usage.cacheWriteInput,
            cacheWriteLongTtlInput = chunk.usage.cacheWriteLongTtlInput,
            reasoningOutput = chunk.usage.reasoningOutput,
        )
        is StreamChunk.Citations -> GenerationChunk.Citations(
            chunk.citations.map { com.promenar.infinity.domain.generation.GenerationCitation(it.title, it.url, it.source) },
        )
        is StreamChunk.Error -> GenerationChunk.Failure(
            GenerationFailure(
                code = chunk.code,
                formatArgs = chunk.retryAfterSeconds
                    ?.takeIf { it > 0 }
                    ?.let { mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to it.toString()) }
                    .orEmpty() +
                    chunk.providerMessage?.let { mapOf(GenerationFailure.KEY_PROVIDER_MESSAGE to it) }.orEmpty() +
                    (if (chunk.code == GenerationFailureCode.CONTEXT_LIMIT)
                        mapOf(GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "UPSTREAM") else emptyMap()),
                technical = chunk.technical,
                cause = chunk.cause,
                retryable = chunk.retryable,
            ),
        )
        is StreamChunk.Completed -> GenerationChunk.Completed(chunk.reason, chunk.completedToolCallIds)
        is StreamChunk.ToolCallLifecycle -> null
        is StreamChunk.ReasoningState -> preparedRoute?.modelId?.takeIf(String::isNotBlank)?.let { source ->
            GenerationChunk.ReasoningState(ReasoningReplayCodec.encode(source, chunk.replay))
        }
    }

    private fun defaultRagPhases() = listOf(
        RagPhase("embed", "Embedding query", PhaseStatus.PENDING),
        RagPhase("memory", "Searching memory", PhaseStatus.PENDING),
        RagPhase("docs", "Searching documents", PhaseStatus.PENDING),
        RagPhase("hybrid", "Hybrid fusion", PhaseStatus.PENDING),
        RagPhase("rank", "Ranking results", PhaseStatus.PENDING),
        RagPhase("rerank", "Reranking", PhaseStatus.PENDING),
        RagPhase("kg", "KG retrieval", PhaseStatus.PENDING),
        RagPhase("ready", "Context ready", PhaseStatus.PENDING),
    )

    private fun updateRagProgress(
        phases: List<RagPhase>,
        stage: String,
        percentage: Int,
        detail: String?,
    ): List<RagPhase> {
        val phaseId = when {
            stage.contains("Embedding", true) -> "embed"
            stage.contains("memory", true) -> "memory"
            stage.contains("document", true) -> "docs"
            stage.contains("Hybrid", true) || stage.contains("fusion", true) -> "hybrid"
            stage.contains("Rerank", true) -> "rerank"
            stage.contains("Ranking", true) || stage.contains("Rank", true) -> "rank"
            stage.contains("KG", true) -> "kg"
            stage.contains("Context ready", true) -> "ready"
            else -> return phases
        }
        val target = phases.indexOfFirst { it.id == phaseId }
        if (target < 0) return phases
        return phases.mapIndexed { index, phase ->
            when {
                index == target -> phase.copy(
                    status = if (phaseId == "ready") PhaseStatus.DONE else PhaseStatus.ACTIVE,
                    progress = if (phaseId == "ready") 100 else percentage,
                    detail = detail,
                )
                index < target && phase.status == PhaseStatus.ACTIVE -> phase.copy(status = PhaseStatus.DONE)
                else -> phase
            }
        }
    }

    private fun ProviderResolution.Failure.toGenerationFailure(): GenerationFailure {
        val code = when (reason) {
            ProviderResolutionError.API_KEY_MISSING,
            ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
            -> GenerationFailureCode.AUTH

            ProviderResolutionError.MODEL_NOT_FOUND,
            ProviderResolutionError.MODEL_DISABLED,
            ProviderResolutionError.PROVIDER_ID_MISSING,
            ProviderResolutionError.PROVIDER_NOT_FOUND,
            ProviderResolutionError.PROVIDER_DISABLED,
            ProviderResolutionError.BASE_URL_INVALID,
            ProviderResolutionError.MODEL_PROVIDER_MISMATCH,
            ProviderResolutionError.PROTOCOL_MISMATCH,
            ProviderResolutionError.PROTOCOL_UNSUPPORTED,
            ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE,
            -> GenerationFailureCode.INVALID_REQUEST
        }
        return GenerationFailure(
            code = code,
            technical = buildString {
                append("provider route failure: reason=")
                append(reason.name)
                append(", modelId=")
                append(modelId)
                providerId?.let {
                    append(", providerId=")
                    append(it)
                }
            },
        )
    }

    private companion object {
        const val LOOP_LIMIT_KEY = "loop_limit"
        private const val EMPTY_REPLY_INSTRUCTION = "[System reminder]\nYour previous response ended without any text for the user. " +
            "If the task is not finished, continue working; otherwise reply to the user now with the results and any next steps."
        private const val PROGRESS_INSTRUCTION = "[System reminder]\nBefore continuing, give the user one concise sentence about your current progress or next action. Avoid filler and repeating tool results."
        const val FINAL_ANSWER_INSTRUCTION = "\n\n## Tool Budget Exhausted\n" +
            "The tool budget for this request is used up. Do NOT call any tool. " +
            "Using only the results already obtained, summarize what was completed, the current state, " +
            "and the remaining steps, and tell the user they can send a message to continue.\n"
    }

}

/**
 * 统计 [request] 所属对话轮在本次生成之前已发起的工具轮次与调用数。
 * 只计本轮用户消息之后、下一条用户消息之前的助手消息；本次生成的目标消息与待替换的旧回复不计入。
 */
internal fun consumedToolUsageInTurn(
    messages: List<com.promenar.infinity.data.model.Message>,
    request: GenerationRequest,
): GenerationToolUsage {
    val userMessageId = request.userMessageId ?: return GenerationToolUsage()
    val start = messages.indexOfFirst { it.id == userMessageId && it.role == MessageRole.USER }
    if (start < 0) return GenerationToolUsage()
    val excluded = request.messageIdsToReplace + request.assistantMessageId
    var rounds = 0
    var calls = 0
    var tokens = 0L
    var estimated = false
    for (message in messages.drop(start + 1)) {
        if (message.role == MessageRole.USER) break
        if (message.role != MessageRole.ASSISTANT || message.id in excluded) continue
        val usage = message.tokens
        val reported = usage?.let { maxOf(it.total.toLong(), it.input.toLong() + it.output) } ?: 0
        if (reported > 0) {
            tokens += reported
            estimated = estimated || usage?.estimated == true
        } else {
            tokens += ApproximateTokenEstimator.estimate(message.content + message.reasoning.orEmpty()) +
                message.toolCalls.orEmpty().sumOf { ApproximateTokenEstimator.estimate(it.name + it.arguments) }
            if (message.content.isNotBlank() || !message.reasoning.isNullOrBlank() || !message.toolCalls.isNullOrEmpty()) estimated = true
        }
        val count = message.toolCalls?.size ?: 0
        if (count > 0) {
            rounds++
            calls += count
        }
    }
    return GenerationToolUsage(rounds, calls, tokens, estimated)
}
