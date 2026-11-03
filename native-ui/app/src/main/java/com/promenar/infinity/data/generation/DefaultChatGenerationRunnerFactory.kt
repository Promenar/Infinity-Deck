package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.PostProcessor
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.chat.manager.SummaryManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import kotlinx.coroutines.CoroutineScope

internal class DefaultChatGenerationRunnerFactory(
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
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver,
    private val presentationStore: GenerationPresentationStore,
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
    private val mediaContextLoader: ViewedMediaContextLoader? = null,
    private val sessionGraph: com.promenar.infinity.data.rag.SessionGraphScheduler? = null,
    private val subagentUsage: com.promenar.infinity.data.subagent.SubagentUsageLedger? = null,
    private val toolBuildDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val sessionState: com.promenar.infinity.data.rag.SessionStateScheduler? = null,
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
    private val timedGrants: com.promenar.infinity.data.approval.TimedApprovalGrantStore? = null,
    private val agentConfigService: (() -> com.promenar.infinity.data.agentconfig.AgentConfigService)? = null,
    private val attachmentWorkspaceStore: com.promenar.infinity.data.document.UserAttachmentWorkspaceStore? = null,
) : GenerationRunnerFactory {
    override fun create(request: GenerationRequest, taskId: String): GenerationRunner =
        ChatGenerationRunner(createRuntime(request.sessionId, taskId))

    fun createRuntime(sessionId: String, taskId: String): DefaultChatGenerationRuntime {
        val contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry, toolResolver)
        return DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = applicationScope,
            store = store,
            agentRepository = agentRepository,
            configResolver = configResolver,
            routeGate = routeGate,
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = localProviderFactory,
            provider = provider,
            toolLedger = toolLedger,
            toolExecutor = toolExecutor,
            postProcessor = postProcessor,
            memoryManager = memoryManager,
            summaryManager = summaryManager,
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = presentationStore.port(sessionId, taskId),
            skillCatalog = skillCatalog,
            toolAllowlistStore = toolAllowlistStore,
            timedGrants = timedGrants,
            agentConfigService = agentConfigService,
            attachmentWorkspaceStore = attachmentWorkspaceStore,
            workspaceRepository = workspaceRepository,
            outputStore = outputStore,
            interactionStore = interactionStore,
            externalFolders = externalFolders,
            sshRuntime = sshRuntime,
            compactionPipeline = compactionPipeline,
            compactionService = compactionService,
            sessionWorkspace = sessionWorkspace,
            toolBuildDispatcher = toolBuildDispatcher,
            mediaContextLoader = mediaContextLoader,
            sessionGraph = sessionGraph,
            sessionState = sessionState,
            subagentUsage = subagentUsage,
            tailnetGate = tailnetGate,
            composerQueueStore = composerQueueStore,
            stateBoardRepository = stateBoardRepository,
            runModeCheck = runModeCheck,
            linuxFiles = linuxFiles,
            askUserLedger = askUserLedger,
            secretInputLedger = secretInputLedger,
            subagentCompletions = subagentCompletions,
            branchService = branchService, branchPlans = branchPlans, sessionMailbox = sessionMailbox,
            knowledgeCatalog = knowledgeCatalog, knowledgeAccessPlanner = knowledgeAccessPlanner, knowledgeTurns = knowledgeTurns,
        )
    }
}
