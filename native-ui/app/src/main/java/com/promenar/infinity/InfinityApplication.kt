package com.promenar.infinity

import androidx.room.withTransaction
import com.promenar.infinity.startup.*
import kotlinx.serialization.encodeToString
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import java.io.File
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.data.local.inference.LocalInferenceRuntimeGate
import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.memory.isEnabledForToolAdvertisement
import com.promenar.infinity.data.local.db.SessionMountNormalizationCallback
import com.promenar.infinity.data.local.db.LegacyDatabasePromoter
import com.promenar.infinity.data.backup.BackupRuntime
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.backup.RestoreRelayActivity
import com.promenar.infinity.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseRecoveryExecutor
import com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseSourceProbe
import com.promenar.infinity.data.local.db.recovery.DualDatabaseBootstrapResult
import com.promenar.infinity.data.local.db.recovery.DualDatabaseRecoveryBootstrap
import com.promenar.infinity.data.local.db.recovery.FileDualDatabaseRecoveryJournal
import com.promenar.infinity.data.local.db.recovery.RecoverySnapshotStore
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.ImageService
import com.promenar.infinity.data.rag.KeywordSearcher
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.rag.MicroGraphExtractor
import com.promenar.infinity.data.rag.MicroGraphKgAdapter
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator
import com.promenar.infinity.data.rag.QueryRewriter
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.RerankClient
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import com.promenar.infinity.data.rag.RecursiveCharacterTextSplitter
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.toCredentialUpdate
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.DefaultProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.requireModelRoute
import com.promenar.infinity.data.remote.requireOpenAiModelRoute
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.remote.provider.VertexCredentialException
import com.promenar.infinity.data.remote.provider.VertexCredentialParser
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.repository.VectorRepository
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.ui.chat.manager.WebSearchContextProvider
import com.promenar.infinity.ui.chat.manager.WebSearchProvider
import com.promenar.infinity.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.ModularSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.UserSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.skills.CalculatorSkill
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.ChatRouteDependencies
import com.promenar.infinity.ui.chat.manager.KgProvider
import com.promenar.infinity.ui.chat.manager.skills.WebSearchSkill
import com.promenar.infinity.ui.chat.manager.skills.WebFetchSkill
import com.promenar.infinity.ui.chat.manager.skills.ImageGenerationSkill
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill
import com.promenar.infinity.ui.chat.manager.skills.FileDiffSkill
import com.promenar.infinity.ui.chat.manager.skills.FilePatchSkill
import com.promenar.infinity.ui.chat.manager.skills.FileListSkill
import com.promenar.infinity.ui.chat.manager.skills.FileSearchSkill
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.infinity.ui.chat.manager.skills.MoveFileSkill
import com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.infinity.ui.chat.manager.skills.ExecJsSkill
import com.promenar.infinity.ui.chat.manager.skills.InitializePlanSkill
import com.promenar.infinity.ui.chat.manager.skills.UpdatePlanSkill
import com.promenar.infinity.ui.chat.manager.skills.GetPlanSkill
import com.promenar.infinity.ui.chat.manager.skills.DropPlanSkill
import com.promenar.infinity.data.repository.TaskRepository
import com.promenar.infinity.data.repository.ToolExecutionLedgerRepository
import com.promenar.infinity.data.repository.SkillRepository
import com.promenar.infinity.data.repository.ISkillRepository
import com.promenar.infinity.data.repository.TokenStatsRepository
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.util.LocaleHelper
import com.promenar.infinity.ui.theme.ThemePreferenceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.promenar.infinity.startup.StartupBackgroundHealthMonitor
import com.promenar.infinity.startup.StartupBackgroundTask
import com.promenar.infinity.startup.StartupBackgroundTaskHealth
import com.promenar.infinity.startup.IdempotentStartupMigrationStage
import com.promenar.infinity.startup.StartupMigration
import com.promenar.infinity.startup.StartupWriterRegistration
import com.promenar.infinity.startup.StartupWriterSession
import com.promenar.infinity.startup.StartupWriterTransaction
import com.promenar.infinity.data.repository.LegacyWorkspaceRootAdoptionCoordinator
import com.promenar.infinity.ui.rag.RagWorkspaceProvisioner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

open class InfinityApplication : Application(), SingletonImageLoader.Factory,
    com.promenar.infinity.background.exec.ExecRunRegistryOwner {
    companion object {
        lateinit var instance: InfinityApplication
            private set
    }

    open val secretStore: SecretStore by lazy { AndroidKeystoreSecretStore(this) }
    val termuxRuntime: com.promenar.infinity.data.termux.TermuxRuntime by lazy {
        com.promenar.infinity.data.termux.TermuxRuntime(this)
    }
    open val mcpConnectionSettings: com.promenar.infinity.data.remote.mcp.McpConnectionSettings by lazy {
        com.promenar.infinity.data.remote.mcp.McpConnectionSettings(getSharedPreferences("infinity_settings", MODE_PRIVATE), secretStore)
    }

    val sshHostStore: com.promenar.infinity.data.ssh.SshHostStore by lazy {
        com.promenar.infinity.data.ssh.RoomSshHostStore(database.sshHostDao(), secretStore)
    }
    /** 工具执行中需要用户亲自输入密码或核对主机指纹时的应用内弹窗队列。 */
    val sshUserPrompts = com.promenar.infinity.data.ssh.SshUserPromptBroker()
    val sshRuntime: com.promenar.infinity.data.ssh.SshRuntime by lazy {
        com.promenar.infinity.data.ssh.SshRuntime(sshHostStore, onPersistentActivity = { active ->
            com.promenar.infinity.background.ssh.SshConnectionService.update(this, active)
        }, uiIdleTimeoutMs = {
            com.promenar.infinity.data.ssh.SshTerminalIdlePolicy.read(
                getSharedPreferences(com.promenar.infinity.data.ssh.SshTerminalIdlePolicy.PREFERENCES_NAME, MODE_PRIVATE),
            ).timeoutMillis
        }, liveOutput = toolLiveOutputStore, vault = sessionSecretVault, redactor = secretOutputRedactor)
    }
    /** 内置 Tailscale（tsnet 子进程，不占用系统 VPN）；创建时安装为全局 tailnet 路由。未打包可执行文件时报告不可用。 */
    val tailnetManager: com.promenar.infinity.data.tailnet.TailnetManager by lazy {
        val executable = File(applicationInfo.nativeLibraryDir, com.promenar.infinity.data.tailnet.TailnetManager.EXECUTABLE_NAME)
        com.promenar.infinity.data.tailnet.TailnetManager(
            settingsStore = com.promenar.infinity.data.tailnet.TailnetSettingsStore(
                getSharedPreferences(com.promenar.infinity.data.tailnet.TailnetSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
                secretStore,
            ),
            launcher = if (executable.isFile) {
                com.promenar.infinity.data.tailnet.AndroidTailnetProcessLauncher(
                    executable = executable,
                    homeDirectory = File(noBackupFilesDir, "tailnet-home"),
                    tempDirectory = File(cacheDir, "tailnet-tmp"),
                )
            } else null,
            stateDirectory = File(noBackupFilesDir, "tailnet"),
            hostname = com.promenar.infinity.data.tailnet.TailnetManager.hostnameFor(android.os.Build.MODEL),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { com.promenar.infinity.data.tailnet.InstalledTailnetRouting.routing = it }
    }
    /** 模型请求发往 tailnet 时等待内置 Tailscale 接入的关口。 */
    val tailnetConnectGate: com.promenar.infinity.data.tailnet.TailnetConnectGate by lazy {
        com.promenar.infinity.data.tailnet.TailnetConnectGate(tailnetManager)
    }
    /** 手机当前网络能否访问互联网，供模型选择器的“公网”指示灯使用；首次读取时注册系统网络回调。 */
    val publicNetworkMonitor: com.promenar.infinity.data.network.PublicNetworkMonitor by lazy {
        com.promenar.infinity.data.network.PublicNetworkMonitor(this)
    }
    val themePreferenceStore: ThemePreferenceStore by lazy { ThemePreferenceStore(this) }

    /** 生产默认依赖保持不变；instrumentation 可通过 Application 子类注入可验证端口。 */
    open fun createChatRouteDependencies(): ChatRouteDependencies =
        ChatRouteDependencies.production(this)

    private lateinit var backupRuntime: BackupRuntime
    private val dualDatabaseRecoveryBootstrap: DualDatabaseRecoveryBootstrap by lazy {
        createDualDatabaseRecoveryBootstrap()
    }
    private var acceptedDualDatabaseOfferToken: String? = null
    var restoreRelayEarlyExit: Boolean = false
        private set
    val startupIssueManager = StartupIssueManager()
    val startupIssues: StateFlow<List<StartupIssue>> = startupIssueManager.issues
    private val _startupBlockedSummary = MutableStateFlow<String?>(null)
    val startupBlockedSummary: StateFlow<String?> = _startupBlockedSummary.asStateFlow()

    suspend fun resolveStartupIssue(issueId: String, action: StartupIssueAction, confirmed: Boolean = false): StartupIssueResolution =
        startupIssueManager.resolve(issueId, action, confirmed)

    suspend fun exportStartupDiagnostics(output: java.io.OutputStream) =
        com.promenar.infinity.startup.diagnostics.AppDiagnostics.export(output, startupIssueManager.formatIssues())

    suspend fun createDiagnosticSession(issueIds: List<String>, agentId: String, modelId: String? = null): com.promenar.infinity.data.model.Session =
        withContext(Dispatchers.IO) {
            StartupDiagnosticSessionFactory(
                startupIssueManager, sessionRepository, workspaceRepository,
                StartupDiagnosticSessionStrings(
                    title = { getString(com.promenar.infinity.R.string.startup_diagnostic_session_title, it) },
                    draft = { getString(com.promenar.infinity.R.string.startup_diagnostic_session_draft, it) },
                    noIssues = getString(com.promenar.infinity.R.string.startup_diagnostic_no_issues),
                ),
            ).createDiagnosticSession(issueIds, agentId, modelId).also(chatStore::upsertSession)
        }

    private val _startupState = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    val startupState: StateFlow<BackupStartupState> = _startupState.asStateFlow()

    /** 启动期已提交的备份恢复摘要，供设置页展示各层结果。 */
    private val _backupRestoreResult = MutableStateFlow<com.promenar.infinity.data.backup.BackupRestoreResult?>(null)
    val backupRestoreResult: StateFlow<com.promenar.infinity.data.backup.BackupRestoreResult?> = _backupRestoreResult.asStateFlow()

    /** 启动时完成的备份恢复结果，界面弹出说明替换范围与待处理事项后清除。 */
    private val _backupRestoreNotice = MutableStateFlow<com.promenar.infinity.data.backup.BackupRestoreResult?>(null)
    val backupRestoreNotice: StateFlow<com.promenar.infinity.data.backup.BackupRestoreResult?> = _backupRestoreNotice.asStateFlow()

    fun consumeBackupRestoreNotice() {
        _backupRestoreNotice.value = null
    }

    /** 启动时待恢复事务确定性失败且已完整回滚；界面告知数据保持原状后清除。 */
    private val _restoreNotAppliedNotice = MutableStateFlow(false)
    val restoreNotAppliedNotice: StateFlow<Boolean> = _restoreNotAppliedNotice.asStateFlow()

    fun consumeRestoreNotAppliedNotice() {
        _restoreNotAppliedNotice.value = false
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val modelCatalogUpdater by lazy {
        com.promenar.infinity.data.model.catalog.ModelCatalogUpdater.create(this) { catalog ->
            ModelCatalogRuntime.installPublished(catalog)
            ProviderManager.getInstance().refreshCatalogMetadata()
        }
    }
    private var catalogObserverRegistered = false

    private fun startModelCatalogUpdates() {
        if (!catalogObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    if (_startupState.value == BackupStartupState.Ready) {
                        startupScope.launch { modelCatalogUpdater.refresh() }
                    }
                }
            })
            catalogObserverRegistered = true
        }
        startupScope.launch { modelCatalogUpdater.refresh() }
    }
    private var startupRecoveryJob: Job? = null
    private var writersInitialized = false
    private var startupWriterSession: StartupWriterSession<PreparedStartupWriters>? = null
    private val startupMigrationStage = IdempotentStartupMigrationStage()
    private val startupBackgroundHealthMonitor = StartupBackgroundHealthMonitor { task, failure ->
        InfinityLogger.logError("StartupBackground.${task.name}", failure)
    }
    val startupBackgroundHealth: StateFlow<Map<StartupBackgroundTask, StartupBackgroundTaskHealth>> =
        startupBackgroundHealthMonitor.state

    val database: InfinityDatabase by lazy {
        LegacyDatabasePromoter.promote(this)
        Room.databaseBuilder(this, InfinityDatabase::class.java, "infinity_v2.db")
            .addMigrations(*com.promenar.infinity.data.local.db.INFINITY_DATABASE_MIGRATIONS)
            .addCallback(SessionMountNormalizationCallback { InfinityLogger.logError("SessionMountNormalization", it) })
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sqlQuery, bindArgs ->
                    if (com.promenar.infinity.BuildConfig.DEBUG) {
                        try {
                            if (sqlQuery.contains("Message", ignoreCase = true) || 
                                sqlQuery.contains("TaskNodeEntity", ignoreCase = true) ||
                                sqlQuery.contains("Session", ignoreCase = true)
                            ) {
                                val json = org.json.JSONObject().apply {
                                    put("operation", sqlQuery.trimStart().substringBefore(' ').uppercase())
                                    put("argumentCount", bindArgs.size)
                                }
                                InfinityLogger.metro("DB_QUERY", json.toString())
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                },
                java.util.concurrent.Executors.newSingleThreadExecutor()
            )
            .build()
    }

    val toolExecutionLedger by lazy {
        StartupGuardedToolLedger(ToolExecutionLedgerRepository(database))
    }

    val askUserLedger by lazy {
        com.promenar.infinity.data.repository.AskUserLedgerRepository(database, sessionExecutionGate)
    }

    val sessionSecretVault = com.promenar.infinity.data.secret.SessionSecretVault()
    val secretOutputRedactor = com.promenar.infinity.data.secret.SecretOutputRedactor(sessionSecretVault)
    private val secretDestinations by lazy {
        com.promenar.infinity.data.secret.DefaultSecretDestinationWriter(
            git = { gitCredentialStore }, hosts = sshHostStore,
            provider = { ProviderManager.getInstance() }, jobs = { backgroundJobs }, vault = sessionSecretVault,
        )
    }
    val secretInputLedger by lazy {
        com.promenar.infinity.data.secret.SecretInputLedgerRepository(database, secretDestinations, sessionExecutionGate).also { ledger ->
            generationScope.launch(Dispatchers.IO) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    try { ledger.expirePending() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* 启动写屏障关闭时等待下一次检查。 */ }
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }
    val secretInputCoordinator: com.promenar.infinity.domain.secret.SecretInputCoordinator get() = secretInputLedger
    val secretOutputController: com.promenar.infinity.domain.secret.SecretOutputController by lazy {
        com.promenar.infinity.data.secret.DefaultSecretOutputController(sessionSecretVault, secretDestinations)
    }

    val localInferenceRuntimeGate: LocalInferenceRuntimeGate by lazy {
        LocalInferenceRuntimeGate.fromBuildConfig()
    }

    val localInferenceEngine: LocalInferenceEngine by lazy {
        localInferenceRuntimeGate.requireAvailable()
        LocalInferenceEngine(this)
    }

    val agentRepository: com.promenar.infinity.data.repository.AgentRepository by lazy {
        com.promenar.infinity.data.repository.AgentRepository(database.agentDao(), database.agentRuleRevisionDao(), database)
    }

    val parameterSchemeRepository: com.promenar.infinity.data.repository.ParameterSchemeRepository by lazy {
        com.promenar.infinity.data.repository.ParameterSchemeRepository(database.parameterSchemeDao())
    }

    val configResolver: com.promenar.infinity.domain.usecase.AgentConfigResolver by lazy {
        com.promenar.infinity.domain.usecase.AgentConfigResolver(
            getSharedPreferences("infinity_settings", MODE_PRIVATE)
        )
    }

    val createAgentUseCase: com.promenar.infinity.domain.usecase.CreateAgentUseCase by lazy {
        com.promenar.infinity.domain.usecase.CreateAgentUseCase(agentRepository)
    }

    val deleteAgentUseCase by lazy {
        com.promenar.infinity.domain.usecase.DeleteAgentUseCase(agentRepository, sessionRepository)
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.messageDao(), newSessionRunMode = sessionRunModes::newSessionMode,
            onSessionCreated = { session -> knowledgeAccessPlanner.mountIfAlways(session) }, database = database)
    }

    val sessionRunModes by lazy {
        com.promenar.infinity.data.session.SessionRunModeService(database.sessionDao(), database.workspaceDao(), linux.runtime,
            onModeChanged = { sessionId, mode -> chatStore.updateSession(sessionId) { it.copy(runMode = mode) } })
    }

    /** 进入会话和发出模型请求前使用同一检查，失败仅阻断该会话请求。 */
    suspend fun checkSessionRunMode(sessionId: String): com.promenar.infinity.domain.generation.GenerationFailure? {
        val code = when (val check = sessionRunModes.check(sessionId)) {
            is com.promenar.infinity.data.session.SessionRunModeCheck.Available -> {
                chatStore.updateSession(sessionId) { it.copy(runMode = check.mode) }
                return null
            }
            is com.promenar.infinity.data.session.SessionRunModeCheck.EnvironmentUnavailable -> com.promenar.infinity.domain.generation.GenerationFailureCode.LINUX_UNAVAILABLE
            else -> com.promenar.infinity.domain.generation.GenerationFailureCode.RUN_MODE_REQUIRED
        }
        return com.promenar.infinity.domain.generation.GenerationFailure(code)
    }

    val workspaceIndexReconciliation: com.promenar.infinity.data.repository.SessionWorkspaceReconciler by lazy {
        com.promenar.infinity.data.repository.SessionWorkspaceReconciler(
            generationScope, File(noBackupFilesDir, "workspace-reconcile-pending"),
            reconcileSharedRoot = { rootUuid ->
                if (workspaceRepository.getByUuid(rootUuid, rootUuid) != null) {
                    (workspaceRepository as WorkspaceRepository).reconcileSessionWorkspace(rootUuid, null)
                }
            },
        ) { sessionId ->
            // 已删除会话没有待维护的索引，不能为恢复请求重建会话根。
            val row = database.sessionDao().getById(sessionId)
            if (row != null) {
                val sharedSource = row.workspaceId?.let { database.workspaceDao().getById(it) }
                    ?.takeIf { it.kind == "branch_shared" }
                sharedSource?.let { workspaceIndexReconciliation.requestSharedRoot(it.location) }
                val root = row.workspaceRootUuid?.let { workspaceRepository.getByUuid(it, it) }
                    ?: workspaceRepository.ensureSessionRoot(sessionId)
                if (database.workspaceDao().getByLocation("branch_shared", root.uuid) != null) {
                    workspaceIndexReconciliation.requestSharedRoot(root.uuid)
                    workspaceIndexReconciliation.awaitSharedRootSettled(root.uuid)
                } else (workspaceRepository as WorkspaceRepository).reconcileSessionWorkspace(root.uuid, sessionId)
                sharedSource?.let { workspaceIndexReconciliation.awaitSharedRootSettled(it.location) }
            }
        }
    }

    private fun requestSessionHomeReconcile(sessionId: String) { workspaceIndexReconciliation.request(sessionId) }

    private suspend fun reconcileSessionHome(sessionId: String) {
        requestSessionHomeReconcile(sessionId)
        workspaceIndexReconciliation.awaitSettled(sessionId)
    }

    private suspend fun sessionCommandHome(sessionId: String): File {
        val root = (workspaceRepository as WorkspaceRepository).sessionRootForCommand(sessionId)
        return File(root.physicalRootPath)
    }

    private suspend fun beginSessionMutation(sessionId: String): suspend () -> Unit {
        val row = database.sessionDao().getById(sessionId)
        val sharedRootUuid = row?.workspaceId?.let { database.workspaceDao().getById(it) }
            ?.takeIf { it.kind == "branch_shared" }?.location
        val finish = (workspaceRepository as WorkspaceRepository).beginSessionCommand(sessionId)
        var homeMarked = false
        try {
            workspaceIndexReconciliation.markDirty(sessionId)
            homeMarked = true
            sharedRootUuid?.let(workspaceIndexReconciliation::markSharedRootDirty)
        } catch (failure: Throwable) {
            if (homeMarked) workspaceIndexReconciliation.mutationFinished(sessionId)
            finish()
            throw failure
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return {
            if (closed.compareAndSet(false, true)) {
                try { finish() } finally {
                    workspaceIndexReconciliation.mutationFinished(sessionId)
                    sharedRootUuid?.let(workspaceIndexReconciliation::sharedRootMutationFinished)
                }
            }
        }
    }

    val timedApprovalGrants by lazy {
        com.promenar.infinity.data.approval.TimedApprovalGrantStore(generationScope)
    }
    val toolAllowlistStore by lazy {
        com.promenar.infinity.data.repository.ToolAllowlistStore(java.io.File(noBackupFilesDir, "tool-allowlist"))
    }
    val toolOutputStore by lazy {
        com.promenar.infinity.data.repository.ToolOutputStore(java.io.File(noBackupFilesDir, "tool-outputs"))
    }
    val contextFoldStore by lazy {
        com.promenar.infinity.data.repository.ContextFoldStore(java.io.File(noBackupFilesDir, "context-folds"))
    }
    private val contextCompactionPipeline by lazy {
        com.promenar.infinity.data.generation.ContextCompactionPipeline(contextFoldStore)
    }
    val contextCompactionService: com.promenar.infinity.data.generation.ContextCompactionService by lazy {
        com.promenar.infinity.data.generation.ContextCompactionService(
            isGenerating = { sessionId ->
                generationCoordinator.isSessionBusy(sessionId)
            },
            manualCompaction = { sessionId, progress ->
                val taskId = "compaction-${java.util.UUID.randomUUID()}"
                generationPresentationStore.begin(sessionId, taskId)
                try {
                    generationRunnerFactory.createRuntime(sessionId, taskId).compactNow(sessionId, progress)
                } finally {
                    generationScope.launch {
                        kotlinx.coroutines.delay(3000)
                        generationPresentationStore.finish(sessionId, taskId)
                    }
                }
            },
            admission = { sessionId, block -> sessionExecutionGate.withNewSessionAdmission(sessionId, block) },
            applicationScope = generationScope,
        )
    }
    suspend fun estimateContextUsage(sessionId: String): com.promenar.infinity.data.generation.ContextUsageEstimate =
        sessionExecutionGate.withNewSessionAdmission(sessionId) {
            generationRunnerFactory.createRuntime(sessionId, "context-estimate").estimateContext(sessionId)
        }
    suspend fun estimateDraftDocumentBudget(sessionId: String, text: String,
        documents: List<com.promenar.infinity.data.model.MessageDocumentAttachment>): com.promenar.infinity.data.generation.DraftDocumentBudget =
        sessionExecutionGate.withNewSessionAdmission(sessionId) {
            generationRunnerFactory.createRuntime(sessionId, "draft-budget").estimateDraftDocumentBudget(sessionId, text, documents)
        }

    val documentWorkspaceSaver: com.promenar.infinity.data.document.DocumentWorkspaceSaver by lazy {
        com.promenar.infinity.data.document.WorkspaceDocumentSaver(
            com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver(
                sessionRepository, workspaceRepository, externalFolderManager, sessionWorkspaceResolver::resolve, skillCatalog.userRoot,
                sessionWorkspaceResolver::resolveWorkspace, sessionWorkspaceResolver::backend, modeFor = sessionRunModes::modeFor,
            ), workspaceRepository, externalFolderManager, pendingDocumentIndexCoordinator, sessionFor = sessionRepository::getById,
        )
    }
    private val userAttachmentWorkspaceStore by lazy {
        com.promenar.infinity.data.document.DefaultUserAttachmentWorkspaceStore(documentWorkspaceSaver)
    }
    private val agentNetworkSettings by lazy {
        com.promenar.infinity.data.network.AgentNetworkSettings(getSharedPreferences(
            com.promenar.infinity.data.network.AgentNetworkSettings.PREFERENCES_NAME, MODE_PRIVATE))
    }
    private val agentNetworkPolicy by lazy {
        com.promenar.infinity.domain.network.AgentNetworkPolicy(
            isTailnetHost = tailnetManager::isTailnetHost,
            tailnetRunning = { tailnetManager.state.value.phase == com.promenar.infinity.data.tailnet.TailnetPhase.RUNNING },
        )
    }
    val agentNetworkAccess: com.promenar.infinity.domain.network.AgentNetworkAccess by lazy {
        com.promenar.infinity.data.network.DefaultAgentNetworkAccess(tailnetManager, startupScope)
    }

    val scriptFileApprovals by lazy { com.promenar.infinity.data.script.ScriptFileApprovalBroker() }
    private val scriptRuntime by lazy {
        com.promenar.infinity.domain.script.SupervisedScriptRuntime { session ->
            com.promenar.infinity.data.script.AndroidScriptProcess.bind(this, session)
        }
    }
    private val scriptToolRunner by lazy {
        com.promenar.infinity.data.script.AndroidScriptToolRunner(scriptRuntime, workspaceRepository, externalFolderManager,
            fileOperationRepository, pendingDocumentIndexCoordinator, scriptFileApprovals,
            sessionWorkspaceResolver::resolve, networkPolicy = agentNetworkPolicy, tailnetRouting = tailnetManager)
    }
    val generationInteractions by lazy {
        com.promenar.infinity.data.generation.GenerationInteractionStore(
            com.promenar.infinity.data.generation.FileHeadlessInteractionPersistence(
                java.io.File(noBackupFilesDir, "system-trigger-interaction.json"),
            ),
        ).also { interactions ->
            interactions.onHeadlessDecision = { prompt, allowed ->
                generationScope.launch {
                    try { resumeHeadlessInteraction(prompt, allowed) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        interactions.retryHeadlessResponse(prompt.id)
                        InfinityLogger.logError("SystemTriggerResume", failure)
                        if (interactions.pendingBySession.value[prompt.sessionId]?.id == prompt.id) {
                            systemTriggerNotifications.failed(prompt.sessionId, "", getString(R.string.schedule_notification_failed_reason))
                        }
                    }
                }
            }
        }
    }

    suspend fun resolveToolAllowlistScope(sessionId: String, target: String) =
        com.promenar.infinity.data.repository.ToolAllowlistScopes.resolveForSession(
            sessionId, target, sessionWorkspaceResolver::resolve, externalFolderManager,
        )

    suspend fun verifyTargetGrant(
        session: com.promenar.infinity.data.model.Session,
        skill: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition,
        args: kotlinx.serialization.json.JsonObject,
        target: String,
    ): Boolean {
        return false
    }

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val sessionExporter: com.promenar.infinity.domain.export.SessionExporter by lazy {
        com.promenar.infinity.data.export.DefaultSessionExporter(
            this, messageRepository, sessionRepository, workspaceMediaResolver,
            agentName = { agentId -> agentRepository.getById(agentId)?.name },
            modelName = { modelId -> ProviderManager.getInstance().providerModels.value.firstOrNull { it.id == modelId }?.name },
        )
    }

    val chatStore: ChatStore by lazy { ChatStore() }
    val sessionExecutionGate by lazy { com.promenar.infinity.data.session.SessionExecutionGate() }
    private val sessionWorkspaceParent: java.nio.file.Path
        get() = File(filesDir, "session_workspaces").toPath()
    private val workspaceInternalStorage by lazy {
        com.promenar.infinity.data.repository.WorkspaceInternalStorage(File(noBackupFilesDir, "workspace-internal").canonicalFile.toPath())
    }
    private val privateWorkspaceFileOps by lazy {
        com.promenar.infinity.data.repository.PrivateWorkspaceFileOps(workspaceInternalStorage)
    }
    private val workspaceMutationRecoveryCoordinator by lazy {
        com.promenar.infinity.data.repository.WorkspaceMutationRecoveryCoordinator(
            workspaceParent = sessionWorkspaceParent,
            loadUnfinished = { database.workspaceMutationDao().getUnfinished() },
            sessionExists = { sessionId -> database.sessionDao().getById(sessionId) != null },
            deletePrepared = { operationId ->
                check(database.workspaceMutationDao().deletePrepared(operationId) == 1) {
                    "PREPARED journal 恢复清理失败"
                }
            },
            deleteCommitted = { operationId ->
                check(database.workspaceMutationDao().deleteCommitted(operationId) == 1) {
                    "DB_COMMITTED journal 恢复清理失败"
                }
            },
            abandonUnchanged = { snapshot, expectedSessionExists, verifyDisk ->
                database.withTransaction {
                    val current = database.workspaceMutationDao().get(snapshot.operationId)
                    val payload = (com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadCodec.decode(snapshot.payloadVersion, snapshot.payload)
                        as? com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadResult.Valid)?.payload
                    val sessionId = payload?.databaseTargetUuid
                    val session = sessionId?.let { database.sessionDao().getById(it) }
                    if (current != snapshot || (session != null) != expectedSessionExists) false
                    else {
                        if (session != null) {
                            check(session.workspaceRootUuid == snapshot.workspaceRootUuid) { "会话工作区绑定已变化" }
                            val root = checkNotNull(database.fileEntryDao().getAnyStateByUuidForLifecycle(snapshot.workspaceRootUuid, snapshot.workspaceRootUuid))
                            check(root.hash == payload?.expectedSha256 &&
                                File(root.physicalRootPath).canonicalFile.toPath() == sessionWorkspaceParent.toFile().canonicalFile.toPath().resolve(payload!!.sourceRelativePath)) {
                                "会话根身份或路径不一致"
                            }
                            check(session.workspacePath == null || File(session.workspacePath).canonicalFile == File(root.physicalRootPath).canonicalFile) { "会话根路径不一致" }
                            com.promenar.infinity.data.repository.StartupJournalConsistency.rootSnapshot(
                                root, database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(root.uuid),
                                com.promenar.infinity.data.repository.SecureWorkspaceFileOps(),
                            )
                        }
                        verifyDisk()
                        when (snapshot.state) {
                            com.promenar.infinity.data.local.db.entity.WorkspaceMutationStage.PREPARED -> database.workspaceMutationDao().deletePrepared(snapshot.operationId) == 1
                            com.promenar.infinity.data.local.db.entity.WorkspaceMutationStage.DB_COMMITTED -> database.workspaceMutationDao().deleteCommitted(snapshot.operationId) == 1
                        }
                    }
                }
            },
            verifyRootIdentity = { path, identity ->
                privateWorkspaceFileOps.ensureRoot(
                    path,
                    initializeIdentity = false,
                    expectedIdentity = identity,
                )
                Unit
            },
            identityIsExternal = true,
            afterRootDeleted = privateWorkspaceFileOps::removePrivateData,
            hasExternalRootIdentity = privateWorkspaceFileOps::hasExternalRootIdentity,
        )
    }
    private val workspaceFileMutationJournal by lazy {
        com.promenar.infinity.data.repository.RoomWorkspaceFileMutationJournal(database)
    }
    private val workspaceFileMutationRecoveryCoordinator by lazy {
        com.promenar.infinity.data.repository.WorkspaceFileMutationRecoveryCoordinator(database, privateWorkspaceFileOps)
    }
    private val sessionDeletionTargetResolver by lazy {
        com.promenar.infinity.data.session.RoomSessionDeletionTargetResolver(
            database,
            sessionWorkspaceParent,
            verifyIdentity = { path, identity ->
                privateWorkspaceFileOps.ensureRoot(path, initializeIdentity = false, expectedIdentity = identity)
                Unit
            },
        )
    }
    private val privateWorkspaceFileRetention by lazy {
        com.promenar.infinity.data.workspace.PrivateWorkspaceFileRetention(
            sessionWorkspaceResolver.projectsRoot, database.workspaceDao(),
        )
    }

    suspend fun privateWorkspaceSummary(sessionId: String): com.promenar.infinity.domain.workspace.PrivateWorkspaceSummary? =
        withContext(Dispatchers.IO) {
            val session = database.sessionDao().getById(sessionId) ?: return@withContext null
            if (session.workspaceId != null || session.workspaceRootUuid.isNullOrBlank() || session.workspacePath.isNullOrBlank()) {
                return@withContext null
            }
            if (!java.nio.file.Files.exists(File(session.workspacePath).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return@withContext null
            }
            val target = sessionDeletionTargetResolver.resolve(sessionId) ?: return@withContext null
            privateWorkspaceFileRetention.summary(target.physicalRoot)
        }

    private val sessionDeletionCoordinator by lazy {
        val journal = com.promenar.infinity.data.session.RoomSessionWorkspaceMutationJournal(
            database,
            sessionWorkspaceParent,
            verifyIdentity = { path, identity ->
                privateWorkspaceFileOps.ensureRoot(path, initializeIdentity = false, expectedIdentity = identity)
                Unit
            },
            afterRootDeleted = privateWorkspaceFileOps::removePrivateData,
            identityIsExternal = true,
        )
        val transaction = com.promenar.infinity.data.session.RoomSessionDeletionTransaction(database)
        com.promenar.infinity.data.session.SessionDeletionCoordinator(
            gate = sessionExecutionGate,
            recoverSessionDeletion = workspaceMutationRecoveryCoordinator::recoverSessionOrThrow,
            resolveTarget = sessionDeletionTargetResolver::resolve,
            isDatabaseOnlySession = { sessionId ->
                val session = database.sessionDao().getById(sessionId)
                if (session == null) {
                    true
                } else {
                    val rootUuid = session.workspaceRootUuid
                    val pathStr = session.workspacePath
                    val sharedWorkspace = rootUuid?.let { database.workspaceDao().getByLocation("branch_shared", it) }
                    val sharedByBranches = sharedWorkspace != null && database.workspaceDao().countSessionReferences(sharedWorkspace.id) > 0
                    if (sharedByBranches || rootUuid.isNullOrBlank() || pathStr.isNullOrBlank()) {
                        true
                    } else {
                        val path = java.io.File(pathStr)
                        val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(rootUuid, rootUuid)
                        !path.exists() || !path.isDirectory || root == null
                    }
                }
            },
            deleteDatabaseOnly = { sessionId ->
                val rootUuid = database.sessionDao().getById(sessionId)?.workspaceRootUuid
                val shared = rootUuid?.let { database.workspaceDao().getByLocation("branch_shared", it) }
                if (shared != null && database.workspaceDao().countSessionReferences(shared.id) > 0) {
                    // 先持久交接保留根；删除会话不能丢弃尚未执行的索引请求。
                    workspaceIndexReconciliation.requestSharedRoot(checkNotNull(rootUuid))
                }
                transaction.deleteWithoutWorkspace(sessionId)
            },
            cancelAndJoinGeneration = { sessionId ->
                generationCoordinator.cancelAndJoinSession(sessionId)
                generationCoordinator.release(sessionId, discardTerminal = true)
                scheduledTaskController.cancelForSession(sessionId)
                sshRuntime.closeOwner("agent:$sessionId")
                backgroundJobs.stopSession(sessionId)
            },
            recoverFileMutations = { target ->
                workspaceFileMutationRecoveryCoordinator.recoverRootOrThrow(target.workspaceRootUuid)
            },
            closePendingExecution = { sessionId ->
                askUserLedger.cancelForDeletion(sessionId)
                database.toolExecutionLedgerDao().cancelOpenForSession(
                    sessionId,
                    "会话已进入删除事务",
                    System.currentTimeMillis(),
                )
            },
            acquireVectorBarrier = { target ->
                val lease = awaitVectorizationQueue().acquireSessionDeleteBarrier(
                    target.sessionId,
                    target.workspaceRootUuid,
                    target.fileUuids,
                )
                object : com.promenar.infinity.data.session.SessionDeletionBarrier {
                    override suspend fun awaitReady() = lease.awaitReady()
                    override suspend fun commit() = lease.commit()
                    override suspend fun abort() = lease.abort()
                }
            },
            journal = journal,
            deleteDatabase = transaction::delete,
            deleteDatabaseKeepingFiles = { target, operationId, retained ->
                transaction.deleteKeepingFiles(target, operationId, retained.register)
            },
            afterDatabaseCommit = { target ->
                pendingDocumentIndexCoordinator.clearCommitted(
                    target.workspaceRootUuid,
                    target.fileUuids,
                )
            },
            cleanupSessionData = { sessionId ->
                timedApprovalGrants.revokeAll(sessionId)
                sessionSecretVault.clearSession(sessionId)
                secretOutputRedactor.clearSession(sessionId)
                com.promenar.infinity.data.session.cleanupSessionRuntimeData(
                    // 会话私有工作区只属于该会话，删除会话时一并撤销绑定到它的白名单条目。
                    { toolAllowlistStore.removeScope("session:$sessionId") },
                    { toolOutputStore.deleteSession(sessionId) },
                    deleteMounts = { externalFolderManager.deleteSessionMounts(sessionId); knowledgeTurns.forget(sessionId) },
                    deleteFolds = { contextFoldStore.deleteSession(sessionId) },
                    deleteSubagents = { subagentLauncher.deleteSession(sessionId) },
                    deleteComposerQueue = {
                        composerDraftRecovery.deleteSession(sessionId)
                        generationPresentationStore.discardSubmittedDraft(sessionId)
                        generationPresentationStore.discardComposerAttachments(sessionId)
                        generationPresentationStore.discardComposerText(sessionId)
                        composerQueueStore.deleteSession(sessionId)
                    },
                    deleteCanvases = { canvasStore.deleteSession(sessionId) },
                    deleteJobs = { backgroundJobs.deleteSession(sessionId); linuxWakeInbox.deleteSession(sessionId) },
                    deleteSchedules = { scheduledTaskController.deleteForSession(sessionId) },
                    deleteTriggerInteraction = { generationInteractions.clearHeadless(sessionId) },
                )
            },
            retainWorkspace = { target ->
                withContext(Dispatchers.IO) {
                    val session = checkNotNull(database.sessionDao().getById(target.sessionId))
                    if (session.workspaceId == null) privateWorkspaceFileRetention.prepare(target.physicalRoot, session.title)
                    else null
                }
            },
        )
    }

    suspend fun deleteSessionRecoverably(sessionId: String, keepFiles: Boolean = false):
        com.promenar.infinity.data.session.SessionDeletionResult = try {
        withContext(Dispatchers.IO) {
            val result = sessionDeletionCoordinator.delete(sessionId, keepFiles)
            if (result !is com.promenar.infinity.data.session.SessionDeletionResult.Failed) timedApprovalGrants.revokeAll(sessionId)
            database.sessionDao().getAll().filter { it.parentSessionDeleted }.forEach { row ->
                chatStore.updateSession(row.id) { it.copy(parentSessionId = row.parentSessionId, parentSessionDeleted = true) }
            }
            result
        }
    } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            scheduledTaskController.resumeSchedulingForSession(sessionId)
        }
    }

    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val composerDraftRecovery = com.promenar.infinity.data.chat.queue.ComposerDraftRecoveryStore()
    /** 各聊天会话的 Agent 浏览器页面，WebView 只在主线程创建。 */
    val agentBrowser: com.promenar.infinity.ui.browser.AgentBrowserHost by lazy { com.promenar.infinity.ui.browser.AgentBrowserHost(this) }
    val canvasStore: com.promenar.infinity.data.canvas.CanvasStore by lazy {
        com.promenar.infinity.data.artifact.ArtifactLifecycleCanvasStore(com.promenar.infinity.data.canvas.FileCanvasStore(
            java.io.File(filesDir, "session_canvases"),
            workspaceFiles = com.promenar.infinity.data.canvas.WorkspaceCanvasFiles(
                com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver(
                    sessionRepository, workspaceRepository, externalFolderManager,
                    sessionWorkspaceResolver::resolve, skillCatalog.userRoot,
                    sessionWorkspaceResolver::resolveWorkspace, sessionWorkspaceResolver::backend,
                    modeFor = sessionRunModes::modeFor,
                ),
                workspaceRepository, fileOperationRepository, externalFolderManager, pendingDocumentIndexCoordinator,
            ),
        )) { fileSessionArtifacts }
    }
    private val fileSessionArtifacts by lazy {
        com.promenar.infinity.data.artifact.FileSessionArtifacts(java.io.File(filesDir, "session_artifacts"), canvasStore, workspaceMediaResolver)
    }
    /** 会话产物：画布与文件快照合并后的列表，产物页与本轮产物卡读取。 */
    val sessionArtifacts: com.promenar.infinity.data.artifact.SessionArtifactRepository by lazy {
        fileSessionArtifacts
    }
    val composerQueueStore: com.promenar.infinity.data.chat.queue.ComposerQueueStore by lazy {
        com.promenar.infinity.data.chat.queue.FileComposerQueueStore(
            java.io.File(filesDir, "composer_queue"),
            { uri -> contentResolver.openInputStream(android.net.Uri.parse(uri)) },
            isUserPersisted = { _, userId -> messageRepository.getById(userId) != null },
        )
    }
    val generationPresentationStore by lazy {
        com.promenar.infinity.data.generation.GenerationPresentationStore()
    }
    /** 运行中命令的实时输出，只存内存。 */
    val toolLiveOutputStore by lazy { com.promenar.infinity.data.exec.ToolLiveOutputStore() }
    val subagentRunStore by lazy {
        com.promenar.infinity.data.subagent.SubagentRunStore(java.io.File(noBackupFilesDir, "subagents"))
    }
    val subagentApprovalBroker by lazy {
        com.promenar.infinity.data.subagent.SubagentApprovalBroker().also { broker ->
            broker.notifier = com.promenar.infinity.background.subagent.SubagentApprovalNotifications(this) { sessionId ->
                generationCoordinator.observe(sessionId).value
            }
        }
    }
    private val subagentToolHost = object : com.promenar.infinity.data.subagent.SubagentToolHost {
        override suspend fun tools(session: com.promenar.infinity.data.model.Session) = sessionToolResolver.resolveForExecution(session)

        override suspend fun target(
            session: com.promenar.infinity.data.model.Session,
            tool: com.promenar.infinity.data.remote.protocol.ProtocolTool,
            arguments: String,
        ) = com.promenar.infinity.data.generation.resolveSessionToolTarget(
            session, tool, arguments, workspaceRepository, externalFolderManager, sshHostStore, sshRuntime::sessionHost, linuxSessionFiles,
        )

        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = false

        override suspend fun remember(sessionId: String, toolName: String, target: String) = Unit

        override fun preview(toolName: String, arguments: kotlinx.serialization.json.JsonObject): String =
            com.promenar.infinity.ui.chat.ApprovalPreview.format(toolName, arguments)?.let { preview ->
                listOfNotNull(preview.target.takeIf(String::isNotBlank), preview.detail?.takeIf(String::isNotBlank)).joinToString("\n")
            }.orEmpty()

        override suspend fun execute(
            tool: com.promenar.infinity.data.remote.protocol.ProtocolTool,
            arguments: kotlinx.serialization.json.JsonObject,
            context: com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext,
        ): com.promenar.infinity.data.subagent.SubagentToolOutcome {
            val skill = skillRegistry.getSkillByRuntimeToolId(tool.runtimeToolId)?.takeIf { it.name == tool.function.name }
                ?: return com.promenar.infinity.data.subagent.SubagentToolOutcome("工具当前不可用", failed = true)
            val result = try { skill.execute(arguments, context) }
                finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { reconcileBranchWorkspace(context.sessionId) } }
            val failed = result.status != "success"
            return com.promenar.infinity.data.subagent.SubagentToolOutcome(
                if (failed) com.promenar.infinity.ui.chat.manager.ToolFailureSummary.format(result.content) else result.content,
                failed,
            )
        }
    }
    internal val generationLocalProviders by lazy {
        com.promenar.infinity.data.generation.LocalGenerationProviderFactory { modelId ->
            LlmProvider.local(localInferenceEngine, modelId)
        }
    }
    val subagentControls: com.promenar.infinity.data.subagent.SubagentControls get() = subagentLauncher
    val branchPlans by lazy { com.promenar.infinity.data.branch.BranchPlanLedger(database, sessionExecutionGate) }
    private val sessionMailbox by lazy {
        com.promenar.infinity.data.branch.SessionMessageStore(database, generationScope, { systemTurnDispatcher }, chatStore,
            generationMessageManager, { getString(R.string.branch_auto_processing_paused) })
    }
    private val branchWorktrees by lazy {
        com.promenar.infinity.data.branch.BranchWorktreeService(
            resolveDirectory = { session ->
                session.worktreeDirectory ?: session.workspaceId?.let { database.workspaceDao().getById(it) }
                    ?.let(com.promenar.infinity.data.workspace.LinuxWorkspaceLocation::workingDirectory) ?: "/workspace"
            },
            execute = { session, cwd, command ->
                withContext(Dispatchers.IO) {
                    val base = branchUnderlyingWorkspace(session)
                    val launch = linux.runtime.prepareLaunch(com.promenar.infinity.domain.linux.LinuxLaunchSpec.shell(command, base)
                        .copy(workingDirectory = cwd, wakeInbox = linuxWakeInbox.prepare(session.id), mounts = externalFolderManager.visible(session.id).filter { it.commandAccessible }
                            .map { com.promenar.infinity.domain.linux.LinuxBind(File(it.sourceId), "/mnt/${it.alias}") }))
                    val output = backgroundCommandRunner.run(com.promenar.infinity.data.exec.BuiltinCommandRequest(
                        launch.command, launch.hostWorkingDirectory, launch.environment, timeoutSeconds = 120))
                    com.promenar.infinity.data.branch.BranchWorktreeCommandOutput(output.exitCode, output.stdout, output.stderr,
                        output.timedOut, output.stdoutTruncated || output.stderrTruncated)
                }
            },
        )
    }
    private suspend fun branchUnderlyingWorkspace(session: com.promenar.infinity.data.local.db.entity.SessionEntity): File {
        val workspace = if (session.workspaceId != null)
            sessionWorkspaceResolver.resolveWorkspace(session.workspaceId) else sessionWorkspaceResolver.resolve(session.id)
        return when (workspace) {
            is com.promenar.infinity.data.workspace.SessionWorkspace.Directory -> workspace.root
            is com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable -> error(workspace.reason)
            com.promenar.infinity.data.workspace.SessionWorkspace.Private -> File(workspaceRepository.ensureSessionRoot(session.id).physicalRootPath)
        }
    }
    private suspend fun branchMountedDirectory(session: com.promenar.infinity.data.local.db.entity.SessionEntity, path: String): File? {
        if (path == "/workspace" || path.startsWith("/workspace/")) return File(branchUnderlyingWorkspace(session), path.removePrefix("/workspace").removePrefix("/"))
        if (path.startsWith("/mnt/")) {
            val alias = path.removePrefix("/mnt/").substringBefore('/')
            val mount = externalFolderManager.visible(session.id).singleOrNull { it.alias == alias && it.commandAccessible } ?: return null
            return File(mount.sourceId, path.removePrefix("/mnt/$alias").removePrefix("/"))
        }
        return null
    }
    private val branchService: com.promenar.infinity.data.branch.BranchSessionService by lazy {
        com.promenar.infinity.data.branch.BranchSessionService(database, branchPlans, askUserLedger, generationScope,
            generationCoordinator, chatStore, generationMessageManager, ::loadGenerationSession,
            { workspaceRepository.ensureSessionRoot(it); Unit }, branchWorktrees, sessionMailbox, sessionExecutionGate,
            { generationForegroundController.track(it) }, { getString(R.string.branch_worktree_failed, it) },
            subagentTitle = { parent, id -> subagentLauncher.read(parent, id).description },
            reconcileSharedSource = { rootUuid, _ ->
                workspaceIndexReconciliation.requestSharedRoot(rootUuid)
            })
    }
    private val agentConfigService: com.promenar.infinity.data.agentconfig.AgentConfigService by lazy {
        com.promenar.infinity.data.agentconfig.AgentConfigService(
            agentRepository, sessionRepository,
            catalog = { session ->
                val providers = ProviderManager.getInstance()
                val configured = providers.providers.value.mapTo(hashSetOf()) { it.id }
                val directory = sessionWorkspaceResolver.resolve(session.id) as? com.promenar.infinity.data.workspace.SessionWorkspace.Directory
                val fileSkills = skillCatalog.scan(directory?.let { com.promenar.infinity.data.skills.SkillWorkspace(it.workspace.id, it.root) }).skills
                com.promenar.infinity.data.agentconfig.AgentConfigCatalog(
                    models = providers.providerModels.value.filter { it.providerId in configured }.associate { it.id to it.name },
                    skills = skillRepository.getAllCustomSkills().first().associate { it.id to it.name } + fileSkills.associate { it.id to (it.displayName ?: it.name) },
                    mcpServers = skillRepository.getAllMcpServers().first().associate { it.id to it.name },
                )
            },
            workspaceName = { id -> database.workspaceDao().getById(id)?.takeUnless { it.kind == "branch_shared" }?.displayName },
            ragConfig = { ragConfigPersistence.loadFullConfig() },
            fallbackModelId = { ProviderManager.getInstance().getMainConfiguredModelId() },
            database = database,
            onSessionSaved = { saved, created, fields ->
                if (created) chatStore.upsertSession(saved) else chatStore.updateSession(saved.id) { current ->
                    current.copy(
                        title = if ("title" in fields) saved.title else current.title,
                        customPrompt = if ("customPrompt" in fields) saved.customPrompt else current.customPrompt,
                        modelId = if ("modelId" in fields) saved.modelId else current.modelId,
                        inferenceParams = if ("inferenceParams" in fields) saved.inferenceParams else current.inferenceParams,
                        activeSkillIds = if ("activeSkillIds" in fields) saved.activeSkillIds else current.activeSkillIds,
                        activeMcpServerIds = if ("activeMcpServerIds" in fields) saved.activeMcpServerIds else current.activeMcpServerIds,
                    )
                }
            },
        )
    }
    val branchControls: com.promenar.infinity.domain.branch.BranchControls get() = branchService
    suspend fun reconcileBranchWorkspace(sessionId: String): Unit = branchService.afterTools(sessionId)
    suspend fun sessionCommunicationApprovalTarget(session: com.promenar.infinity.data.model.Session, tool: String, arguments: String): String? =
        if (tool == com.promenar.infinity.domain.agentconfig.AgentConfigTools.WRITE)
            agentConfigService.preflight(session, arguments).target
        else branchService.approvalTarget(session, tool, arguments)
    private val subagentCompletions by lazy { com.promenar.infinity.data.trigger.SubagentCompletionSteering(generationScope) }
    private val subagentResultFiles by lazy {
        com.promenar.infinity.data.subagent.SubagentResultFiles(
            sessionRepository, workspaceRepository, externalFolderManager, sessionWorkspaceResolver::resolve,
            skillCatalog.userRoot, pendingDocumentIndexCoordinator, modeFor = sessionRunModes::modeFor,
            linuxFiles = linux.files, directoryBackend = sessionWorkspaceResolver::backend,
        )
    }
    private val subagentRuntimeTexts get() = com.promenar.infinity.data.subagent.SubagentRuntimeTexts(
        interrupted = getString(R.string.bg_subagent_interrupted),
        resultWriteFailed = getString(R.string.bg_subagent_result_write_failed),
        executionFailed = getString(R.string.bg_subagent_execution_failed),
        persistenceFailed = getString(R.string.bg_subagent_persistence_failed),
        stopPersistenceFailed = getString(R.string.bg_subagent_stop_persistence_failed),
        contextLimit = getString(R.string.bg_subagent_context_limit),
        modelUnavailable = { getString(R.string.subagent_model_unavailable, it) },
        modelToolCallingUnsupported = getString(R.string.ux2_unavailable_tool_calling),
    )
    val subagentLauncher by lazy {
        com.promenar.infinity.data.subagent.SubagentLauncher(
            runner = com.promenar.infinity.data.subagent.SubagentRunner(
                models = com.promenar.infinity.data.subagent.RoutedSubagentModelClient(
                    com.promenar.infinity.data.generation.ChatProviderRouteGate(providerRequestRouter),
                ) { modelId -> generationLocalProviders.create(modelId) },
                host = subagentToolHost,
                approvals = subagentApprovalBroker,
                liveOutput = toolLiveOutputStore,
                contextLimitText = subagentRuntimeTexts.contextLimit,
                modelSupportsToolCalling = { modelId -> com.promenar.infinity.data.subagent.SubagentModelSelection.supportsToolCalling(
                    modelId, ProviderManager.getInstance().providerModels.value,
                ) },
                modelUnavailableText = subagentRuntimeTexts.modelUnavailable,
                modelToolCallingUnsupportedText = subagentRuntimeTexts.modelToolCallingUnsupported,
            ),
            store = subagentRunStore,
            session = ::loadGenerationSession,
            modelFor = { session ->
                // 与父会话生成相同的模型选择顺序。
                session.modelId?.takeIf(String::isNotBlank)
                    ?: configResolver.resolve(agentRepository.getById(session.agentId)).modelId.takeIf(String::isNotBlank)
                    ?: ProviderManager.getInstance().getMainConfiguredModelId()
            },
            taskTokenLimit = {
                getSharedPreferences("infinity_settings", MODE_PRIVATE).getInt("task_token_limit", 0).coerceAtLeast(0).toLong()
            },
            applicationScope = CoroutineScope(generationScope.coroutineContext + Dispatchers.IO),
            dispatcher = { systemTurnDispatcher },
            contextWindow = { modelId -> ProviderManager.getInstance().providerModels.value.find { it.id == modelId }?.contextLength },
            writeResult = subagentResultFiles::write,
            texts = subagentRuntimeTexts,
            modelPolicyFor = { agentRepository.getById(it.agentId)?.subagentModelPolicy ?: com.promenar.infinity.data.agent.AgentSubagentModelPolicy() },
        ).also { launcher ->
            generationScope.launch {
                launcher.activeRuns.map { it.isNotEmpty() }.distinctUntilChanged().collect { active ->
                    if (active) generationForegroundController.trackSubagents()
                }
            }
        }
    }
    val generationForegroundController:
        com.promenar.infinity.background.generation.GenerationForegroundController by lazy {
        com.promenar.infinity.background.generation.AndroidGenerationForegroundController(this)
    }
    val appIntentRouter by lazy {
        com.promenar.infinity.navigation.AppIntentRouter(
            sessionOpenTokens = com.promenar.infinity.navigation.AndroidSessionOpenTokenStore(this),
        ) { taskId, sessionId ->
            generationCoordinator.observe(sessionId).value?.taskId == taskId
        }
    }
    @Volatile var visibleSessionId: String? = null
    val sessionOpenIntentFactory: com.promenar.infinity.navigation.SessionOpenIntentFactory by lazy {
        com.promenar.infinity.navigation.AndroidSessionOpenIntentFactory(this, appIntentRouter)
    }
    val systemTriggerNotifications by lazy {
        com.promenar.infinity.background.trigger.SystemTriggerNotifications(this, sessionOpenIntentFactory) { visibleSessionId }
    }
    private val systemTurnRuntime by lazy {
        com.promenar.infinity.data.trigger.ApplicationSystemTurnRuntime(
                startup = startupState, store = chatStore,
                subagentCompletions = subagentCompletions,
                admitMessageWake = sessionMailbox::admitWake,
                findSession = ::loadGenerationSession,
                exists = { id -> database.sessionDao().getById(id) != null },
                hasApproval = { id -> database.sessionDao().hasPendingUserInteraction(id) },
                messages = generationMessageManager, coordinator = generationCoordinator,
                foreground = generationForegroundController, interactions = generationInteractions,
                executionGate = sessionExecutionGate,
                notify = { request, outcome, reply ->
                    try { systemTriggerNotifications.result(request, outcome, reply) }
                    catch (failure: Exception) { InfinityLogger.logError("SystemTriggerNotification", failure) }
                },
                failureText = { error -> error?.let {
                    com.promenar.infinity.ui.chat.GenerationFailureNotice.template(
                        com.promenar.infinity.ui.chat.GenerationFailureNotice.from(it.failure),
                    ).let { text -> getString(text.resourceId, *text.args.toTypedArray()) }
                } ?: getString(R.string.schedule_notification_failed_reason) },
        )
    }
    val systemTurnDispatcher: com.promenar.infinity.domain.trigger.SystemTurnDispatcher by lazy {
        com.promenar.infinity.data.trigger.DefaultSystemTurnDispatcher(
            generationScope, systemTurnRuntime,
            failureMessage = getString(R.string.schedule_notification_failed_reason),
            interruptedMessage = getString(R.string.schedule_interrupted),
        )
    }
    val scheduledTaskController by lazy {
        com.promenar.infinity.data.schedule.DefaultScheduledTaskController(this, database,
            object : com.promenar.infinity.domain.trigger.SystemTurnDispatcher {
                override suspend fun request(request: com.promenar.infinity.domain.trigger.SystemTurnRequest) =
                    systemTurnDispatcher.request(request)
            },
            withdrawPending = { (systemTurnDispatcher as com.promenar.infinity.data.trigger.DefaultSystemTurnDispatcher).withdrawPendingSource(it) },
        )
    }

    /** 前台可见时由进程内计时器准点触发定时任务；系统闹钟与 JobScheduler 只在后台兜底。 */
    private fun startForegroundScheduleTicker() {
        val ticker = com.promenar.infinity.data.schedule.ForegroundScheduleTicker(
            scope = generationScope,
            tasks = scheduledTaskController.observe(null),
            foreground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                .map { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
            clock = System::currentTimeMillis,
            fire = { id -> scheduledTaskController.fire(id) },
        )
        generationScope.launch { ticker.run() }
    }

    suspend fun stopSystemTurnSource(sourceId: String) {
        (systemTurnDispatcher as com.promenar.infinity.data.trigger.DefaultSystemTurnDispatcher).stopSource(sourceId)
    }

    private suspend fun loadGenerationSession(id: String): com.promenar.infinity.data.model.Session? = withContext(Dispatchers.IO) {
        val session = sessionRepository.getById(id) ?: return@withContext null
        if (session.ragOptions != null) return@withContext session
        val config = com.promenar.infinity.domain.usecase.RagConfigPersistence(getSharedPreferences("rag_settings", MODE_PRIVATE)).loadFullConfig()
        val policy = agentRepository.getById(session.agentId)?.memoryPolicy ?: com.promenar.infinity.data.agent.AgentMemoryPolicy()
        val options = policy.defaultsFor(com.promenar.infinity.data.model.RagOptions(
            enableMemory = config.enableMemory, enableDocs = config.enableDocs,
            enableRerank = config.enableRerank, enableKnowledgeGraph = config.enableKnowledgeGraph,
        ))
        sessionRepository.updatePartial(id, mapOf("ragOptions" to options))
        session.copy(ragOptions = options)
    }

    private val headlessGenerationResumer by lazy {
        com.promenar.infinity.data.generation.HeadlessGenerationResumer(
            store = chatStore, interactions = generationInteractions,
            coordinator = generationCoordinator, messages = generationMessageManager,
            awaitReady = { startupState.first { it == BackupStartupState.Ready } },
            loadSession = ::loadGenerationSession,
            updateSession = { id, updates -> generationSessionManager.updateSession(id, updates) },
            confirmUnlimited = {
                com.promenar.infinity.data.generation.UnlimitedAutomationConsent(getSharedPreferences("infinity_settings", MODE_PRIVATE)).confirm()
            },
            track = { generationForegroundController.track(it) },
            executionGate = sessionExecutionGate,
        )
    }

    /** 无界面确认恢复通过应用级端口接续，不要求先创建 ChatViewModel。 */
    private suspend fun resumeHeadlessInteraction(
        prompt: com.promenar.infinity.data.generation.GenerationInteraction,
        allowed: Boolean,
    ) {
        headlessGenerationResumer.resume(prompt, allowed)
    }

    private val generationSessionManager by lazy {
        com.promenar.infinity.ui.chat.manager.SessionManager(chatStore, sessionRepository)
    }
    private val generationMessageManager by lazy {
        com.promenar.infinity.ui.chat.manager.MessageManager(
            chatStore,
            messageRepository,
            sessionRepository,
            generationScope,
            memoryLifecycle = conversationMemoryLifecycle,
            isSessionGenerating = { sessionId -> generationCoordinator.isSessionBusy(sessionId) },
        )
    }
    val conversationMemoryLifecycle: com.promenar.infinity.data.rag.ConversationMemoryLifecycle by lazy {
        com.promenar.infinity.data.rag.RoomConversationMemoryLifecycle(
            databaseProvider = { database },
            turnWriter = { sessionId, userContent, assistantContent, userMessageId, assistantMessageId ->
                memoryManager.addTurnToMemory(sessionId, userContent, assistantContent, userMessageId, assistantMessageId)
            },
            sessionGraph = sessionGraphScheduler,
            sessionState = sessionStateScheduler,
        )
    }
    private val generationContextBuilder by lazy {
        com.promenar.infinity.ui.chat.manager.ContextBuilder(
            ragProvider = com.promenar.infinity.data.rag.MemoryManagerRagAdapter { memoryManager },
            kgProvider = kgProvider,
            taskRepository = taskRepository,
            stateBoardRepository = stateBoardRepository,
            longTermMemoryRepository = longTermMemoryRepository,
            longTermMemoryAccess = longTermMemoryAccess,
            onEmbeddingNotConfigured = embeddingSetupHint::onEmbeddingNotConfigured,
        )
    }
    val embeddingSetupHint by lazy {
        com.promenar.infinity.data.rag.EmbeddingSetupHint(getSharedPreferences("infinity_settings", MODE_PRIVATE))
    }
    private val generationToolExecutor by lazy {
        com.promenar.infinity.ui.chat.manager.ToolExecutor(
            chatStore,
            generationMessageManager,
            skillRegistry,
            mainChatToolResolver,
            taskRepository,
            toolExecutionLedger,
            sessionExecutionGate,
            grantVerifier = this::verifyTargetGrant,
            liveOutput = toolLiveOutputStore,
            unavailableLedgerMessage = { getString(com.promenar.infinity.R.string.startup_isolation_tool_ledger_diagnostics_only) },
            afterTools = ::reconcileBranchWorkspace,
        )
    }
    private val generationPostProcessor by lazy {
        val settings = getSharedPreferences("infinity_settings", MODE_PRIVATE)
        // 快速模型与会话模型都是稳定模型 ID，必须按其所属提供商精确路由，不能发给默认提供商。
        val titleRouter = com.promenar.infinity.data.generation.RoutedToolResultSummarizer(
            com.promenar.infinity.data.generation.ChatProviderRouteGate(providerRequestRouter),
        ) { modelId -> generationLocalProviders.create(modelId) }
        com.promenar.infinity.ui.chat.manager.PostProcessor(
            chatStore,
            generationSessionManager,
            generationMessageManager,
            embeddingClient,
            vectorStore,
            textSplitter,
            titleScope = generationScope,
        ).apply {
            setTitleGenerator { params ->
                val quickModelId = settings.getString("preset_summary_model", "")
                com.promenar.infinity.ui.chat.manager.PostProcessor.generateTitleWithFallback(
                    params = params,
                    quickModelId = quickModelId,
                ) { request -> titleRouter.summarizePrompt(request)?.content }
            }
        }
    }
    private val generationRunnerFactory: com.promenar.infinity.data.generation.DefaultChatGenerationRunnerFactory by lazy {
        val settings = getSharedPreferences("infinity_settings", MODE_PRIVATE)
        val provider = llmProvider
        com.promenar.infinity.data.generation.DefaultChatGenerationRunnerFactory(
            settings = settings,
            applicationScope = generationScope,
            store = chatStore,
            agentRepository = agentRepository,
            configResolver = configResolver,
            routeGate = com.promenar.infinity.data.generation.ChatProviderRouteGate(providerRequestRouter),
            contextBuilder = generationContextBuilder,
            messageManager = generationMessageManager,
            localProviderFactory = { modelId -> generationLocalProviders.create(modelId) },
            provider = provider,
            toolLedger = toolExecutionLedger,
            toolExecutor = generationToolExecutor,
            postProcessor = generationPostProcessor,
            memoryManager = memoryManager,
            summaryManager = com.promenar.infinity.data.generation.RoutedToolResultSummarizer(
                com.promenar.infinity.data.generation.ChatProviderRouteGate(providerRequestRouter),
            ) { modelId -> generationLocalProviders.create(modelId) }.historySummaryManager(provider),
            sessionManager = generationSessionManager,
            skillRegistry = skillRegistry,
            toolResolver = mainChatToolResolver,
            presentationStore = generationPresentationStore,
            skillCatalog = skillCatalog,
            toolAllowlistStore = toolAllowlistStore,
            timedGrants = timedApprovalGrants,
            workspaceRepository = workspaceRepository,
            outputStore = toolOutputStore,
            externalFolders = externalFolderManager,
            interactionStore = generationInteractions,
            sshRuntime = sshRuntime,
            compactionPipeline = contextCompactionPipeline,
            compactionService = contextCompactionService,
            sessionWorkspace = sessionWorkspaceResolver::resolve,
            mediaContextLoader = com.promenar.infinity.data.generation.ViewedMediaContextLoader(workspaceMediaViewer, ::resolveMediaViewTarget),
            sessionGraph = sessionGraphScheduler,
            sessionState = sessionStateScheduler,
            subagentUsage = subagentRunStore,
            subagentCompletions = subagentCompletions,
            tailnetGate = tailnetConnectGate,
            composerQueueStore = composerQueueStore,
            stateBoardRepository = stateBoardRepository,
            runModeCheck = ::checkSessionRunMode,
            linuxFiles = linuxSessionFiles,
            askUserLedger = askUserLedger,
            secretInputLedger = secretInputLedger,
            agentConfigService = { agentConfigService },
            attachmentWorkspaceStore = userAttachmentWorkspaceStore,
            branchService = { branchService }, branchPlans = branchPlans, sessionMailbox = sessionMailbox,
            knowledgeCatalog = knowledgeCatalog, knowledgeAccessPlanner = knowledgeAccessPlanner, knowledgeTurns = knowledgeTurns,
            chatCitationRecorder = com.promenar.infinity.data.library.ask.ChatCitationRecorder(roomKnowledgeCitationRepository),
        )
    }
    val generationCoordinator: com.promenar.infinity.domain.generation.GenerationCoordinator by lazy {
        com.promenar.infinity.data.generation.DefaultGenerationCoordinator(
            applicationScope = generationScope,
            runnerFactory = generationRunnerFactory,
            presentationStore = generationPresentationStore,
            executionGate = sessionExecutionGate,
            compactionService = contextCompactionService,
        ).also { coordinator ->
            com.promenar.infinity.data.generation.observeForegroundOnlyPolicy(
                generationScope,
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map {
                    it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                },
                coordinator,
            )
        }
    }

    private var _vectorRepository: com.promenar.infinity.domain.repository.IVectorRepository? = null
    val vectorRepository: com.promenar.infinity.domain.repository.IVectorRepository
        get() = _vectorRepository ?: VectorRepository(database.vectorDao(), embeddingClient).also { _vectorRepository = it }

    val skillRepository: ISkillRepository by lazy {
        com.promenar.infinity.data.repository.SkillRepository(database.skillDao())
    }

    val kgUsageStore: com.promenar.infinity.data.rag.KgUsageStore by lazy {
        com.promenar.infinity.data.rag.PreferencesKgUsageStore(getSharedPreferences("kg_usage", MODE_PRIVATE))
    }

    val tokenStatsRepository: ITokenStatsRepository by lazy {
        com.promenar.infinity.data.subagent.SubagentTokenStatsRepository(TokenStatsRepository(
            database.messageDao(), kgUsageStore,
            com.promenar.infinity.data.repository.RolledBackSummaryUsageStore(getSharedPreferences("infinity_settings", MODE_PRIVATE)),
        ), subagentRunStore, title = { sessionId -> sessionRepository.getById(sessionId)?.title })
    }

    private val workspaceIdentityBaselines by lazy {
        com.promenar.infinity.data.repository.WorkspaceIdentityBaselineStore(
            File(noBackupFilesDir, "workspace-identity-baselines"),
        )
    }

    /** 统一资源管理器的位置、文件操作与固定文件夹。 */
    val fileManagerServices: com.promenar.infinity.domain.files.FileManagerServices by lazy {
        com.promenar.infinity.data.files.DefaultFileManagerServices.create(this, ::isSessionBusyForUserFileOps)
    }

    val workspaceRepository: com.promenar.infinity.domain.repository.IWorkspaceRepository by lazy {
        WorkspaceRepository(
            database.fileEntryDao(),
            database.workspaceSeqDao(),
            File(filesDir, "session_workspaces"),
            fileOps = privateWorkspaceFileOps,
            identityBaselines = workspaceIdentityBaselines,
            deleteCommitter = com.promenar.infinity.data.repository.WorkspaceDeletionTransaction(database)::delete,
            beforeDeleteCommitted = { workspaceRootUuid, ids ->
                awaitVectorizationQueue().acquireDeleteBarrier(workspaceRootUuid, ids)
            },
            afterDeleteCommitted = { workspaceRootUuid, ids ->
                pendingDocumentIndexCoordinator.clearCommitted(workspaceRootUuid, ids)
            },
            afterDeletePathCommitted = { workspaceRootUuid, path ->
                if (linuxSessionFilesDelegate.isInitialized()) linuxSessionFiles.forgetPrivateVersions(workspaceRootUuid, path)
            },
            executionGate = sessionExecutionGate,
            mutationJournal = workspaceFileMutationJournal,
            // 延迟到发布时解析，避免与索引协调器的初始化互相依赖。
            indexEventSink = com.promenar.infinity.data.rag.FileIndexEventSink { event ->
                pendingDocumentIndexCoordinator.publish(event)
            },
        )
    }

    /** 会话脚本唤醒在应用作用域监听、补扫和限流派发。 */
    private val linuxWakeInbox by lazy {
        com.promenar.infinity.data.linux.LinuxWakeInbox(
            filesDir, generationScope,
            com.promenar.infinity.data.linux.LinuxWakeDispatch(generationScope, { systemTurnDispatcher }),
            com.promenar.infinity.data.linux.LinuxWakeFileObserver(),
            sessionExists = { database.sessionDao().getById(it) != null },
        )
    }
    private val linuxWakeLifecycle by lazy {
        com.promenar.infinity.data.linux.LinuxWakeLifecycle(linux.runtime, backgroundJobs, linuxWakeInbox, generationScope)
    }

    /** 内置 Linux 环境（proot + Alpine）；界面与执行层通过它取得 domain/linux 接口的实现。 */
    val linux by lazy {
        com.promenar.infinity.data.linux.LinuxModule(
            this,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            com.promenar.infinity.data.linux.LinuxHostServices(
                keepAlive = commandKeepAlive,
                sessionHome = ::sessionCommandHome,
                reconcileSession = ::requestSessionHomeReconcile,
                beginSessionMutation = ::beginSessionMutation,
                wakeInbox = { linuxWakeInbox.prepare(it) },
                sessionWorkspaceRoot = { sessionId ->
                    when (val workspace = sessionWorkspaceResolver.resolve(sessionId)) {
                        is com.promenar.infinity.data.workspace.SessionWorkspace.Directory -> workspace.root
                        is com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable -> null
                        com.promenar.infinity.data.workspace.SessionWorkspace.Private -> sessionCommandHome(sessionId)
                    }
                },
            ),
        )
    }

    private val runtimeDataMonitor by lazy {
        com.promenar.infinity.data.exec.RuntimeDataMonitor(File(applicationInfo.dataDir))
    }
    private val backgroundCommandRunner by lazy {
        com.promenar.infinity.data.exec.BuiltinCommandRunner(
            groupLauncher = listOf("/system/bin/setsid"), shell = "/system/bin/sh",
            signaller = com.promenar.infinity.data.exec.AndroidProcessGroupSignaller,
            scratchDirectory = { File(cacheDir, "exec-run") },
            clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
            // 系统开机计数不依赖受设备策略限制的 /proc/sys 文件。
            bootIdentity = { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT).toString() },
            requireBackgroundIdentity = true,
            runtimeDataMonitor = runtimeDataMonitor,
        )
    }
    private val commandKeepAlive by lazy {
        com.promenar.infinity.background.exec.AndroidExecKeepAlive(this, execRunRegistry,
            com.promenar.infinity.distribution.DistributionCapabilities.backgroundKeepAliveAvailable)
    }
    private val runModeMessages by lazy { com.promenar.infinity.data.exec.RunModeMessages { key -> getString(when (key) {
                com.promenar.infinity.data.exec.RunModeMessage.LINUX_UNAVAILABLE -> R.string.run_mode_linux_unavailable
                com.promenar.infinity.data.exec.RunModeMessage.MODE_REQUIRED -> R.string.run_mode_required
                com.promenar.infinity.data.exec.RunModeMessage.MODE_CHANGED -> R.string.run_mode_changed
                com.promenar.infinity.data.exec.RunModeMessage.BUILTIN_TOOL_BLOCKED -> R.string.run_mode_builtin_tool_blocked
                com.promenar.infinity.data.exec.RunModeMessage.INSTALL_PACKAGE -> R.string.run_mode_install_package
                com.promenar.infinity.data.exec.RunModeMessage.SEARCH_PACKAGE -> R.string.run_mode_search_package
            }) } }

    val backgroundJobController: com.promenar.infinity.domain.jobs.BackgroundJobController get() = backgroundJobs
    val backgroundJobs: com.promenar.infinity.data.jobs.BackgroundJobManager by lazy {
        val notifications = com.promenar.infinity.background.jobs.BackgroundJobNotifications(this) { sessionOpenIntentFactory }
        com.promenar.infinity.data.jobs.BackgroundJobManager(
            scope = generationScope,
            store = com.promenar.infinity.data.jobs.BackgroundJobStore(File(noBackupFilesDir, "background-jobs")),
            runRoot = File(noBackupFilesDir, "background-jobs/runs"),
            runner = backgroundCommandRunner,
            prepareWorkspace = { record ->
                when (val target = sessionWorkspaceResolver.resolve(record.sessionId)) {
                    is com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable -> throw IllegalStateException("会话工作区不可用")
                    is com.promenar.infinity.data.workspace.SessionWorkspace.Directory -> com.promenar.infinity.data.jobs.BackgroundJobLocation(target.root, target.root, false,
                        target.kind == com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_SHARED)
                    com.promenar.infinity.data.workspace.SessionWorkspace.Private -> {
                        if (record.runner == "LINUX") {
                            val root = sessionCommandHome(record.sessionId)
                            com.promenar.infinity.data.jobs.BackgroundJobLocation(root, root, false, false, workspaceKindOverride = "private")
                        } else if (record.runner == "TERMUX") {
                            // Termux 不能访问应用私有根，不复制整个工作区。
                            com.promenar.infinity.data.jobs.BackgroundJobLocation(File(record.runDirectory), File(record.runDirectory), false, false)
                        } else {
                            val staging = File(record.runDirectory, "workspace")
                            val root = (workspaceRepository as WorkspaceRepository).prepareBackgroundWorkspace(record.workspaceRootUuid, record.sessionId, record.id, staging)
                            com.promenar.infinity.data.jobs.BackgroundJobLocation(staging, root, true, false)
                        }
                    }
                }
            },
            prepareCommand = { kind, source, stdin, location, minutes -> builtinExecService.prepareBackgroundCommand(kind, source, stdin, location, minutes, location.sessionId) },
            reconcile = { record, finished, interrupted ->
                if (record.isolatedWorkspace || File(record.runDirectory, "baseline.json").isFile) {
                    workspaceIndexReconciliation.markDirty(record.sessionId)
                    try {
                        (workspaceRepository as WorkspaceRepository).reconcileBackgroundWorkspace(
                            record.workspaceRootUuid, record.sessionId, record.id, File(record.runDirectory, "workspace"), finished, interrupted,
                            reconcileIndex = false)
                    } finally { workspaceIndexReconciliation.mutationFinished(record.sessionId) }
                } else null
            },
            requestReconcile = { record ->
                if (record.runner == "LINUX" || record.isolatedWorkspace || File(record.runDirectory, "baseline.json").isFile) {
                    requestSessionHomeReconcile(record.sessionId)
                }
            },
            reconcileWarning = { record -> workspaceIndexReconciliation.failures.value[record.sessionId] },
            termux = com.promenar.infinity.data.termux.TermuxBackgroundJobs(termuxRuntime::executeRequest),
            keepAlive = commandKeepAlive, liveOutput = toolLiveOutputStore,
            completion = com.promenar.infinity.data.jobs.BackgroundJobCompletion({ systemTurnDispatcher }, notifications::notify),
            keepAliveMode = if (com.promenar.infinity.distribution.DistributionCapabilities.backgroundKeepAliveAvailable) "service" else "foreground_only",
            executionGate = sessionExecutionGate,
            sessionExists = { database.sessionDao().getById(it) != null },
            linuxRuntime = linux.runtime,
            sessionMode = sessionRunModes::modeFor,
            messages = runModeMessages,
            vault = sessionSecretVault, redactor = secretOutputRedactor,
        )
    }

    val builtinExecService: com.promenar.infinity.data.exec.BuiltinExecService by lazy {
        com.promenar.infinity.data.exec.BuiltinExecService(
            workspace = workspaceRepository as com.promenar.infinity.data.repository.WorkspaceCommandHost,
            runner = backgroundCommandRunner,
            environment = com.promenar.infinity.data.exec.BuiltinExecEnvironment.create(this, pipEnabled = BuildConfig.DISTRIBUTION == "sideload", skillsDirectory = skillCatalog.userRoot),
            unavailablePythonAbi = { android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" },
            keepAlive = commandKeepAlive,
            sessionWorkspace = sessionWorkspaceResolver::resolve,
            gitRemote = gitRemoteBroker,
            runtimeDataMonitor = runtimeDataMonitor,
            linuxRuntime = linux.runtime,
            linuxProvenance = linux.provenance,
            visibleMounts = externalFolderManager::visible,
            sessionTitle = { database.sessionDao().getById(it)?.title },
            sessionMode = { id -> id?.let { sessionRunModes.modeFor(it) } },
            sessionHome = ::sessionCommandHome,
            requestSessionHomeReconcile = ::requestSessionHomeReconcile,
            beginSessionMutation = ::beginSessionMutation,
            wakeInbox = { linuxWakeInbox.prepare(it) },
            messages = runModeMessages,
            vault = sessionSecretVault, redactor = secretOutputRedactor,
            linuxLaunchBinding = { sessionId ->
                val row = database.sessionDao().getById(sessionId)
                row?.worktreeDirectory?.let { com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace(branchUnderlyingWorkspace(row), it) }
            },
        )
    }

    /** 会话工作区解析：自选目录（项目文件夹位于 `filesDir/projects`，共享存储仅侧载渠道）优先，否则为私有工作区。 */
    val sessionWorkspaceResolver: com.promenar.infinity.data.workspace.SessionWorkspaceResolver by lazy {
        com.promenar.infinity.data.workspace.SessionWorkspaceResolver(
            sessionDao = database.sessionDao(),
            workspaceDao = database.workspaceDao(),
            projectsRoot = File(filesDir, "projects"),
            sharedAccess = com.promenar.infinity.data.workspace.AndroidSharedWorkspaceAccess(this),
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeWorkspaceSource(linux.runtime),
            branchWorktreeResolver = com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver(linux.runtime, ::branchMountedDirectory),
            resolveSharedPrivateRoot = { uuid -> database.fileEntryDao().getAnyStateByUuidForLifecycle(uuid, uuid)?.physicalRootPath?.let(::File) },
        )
    }

    /** 自选工作区的目录浏览与登记：项目文件夹与 [sessionWorkspaceResolver] 共用同一项目根。 */
    val workspaceDirectoryBrowser: com.promenar.infinity.data.workspace.WorkspaceDirectoryBrowser by lazy {
        com.promenar.infinity.data.workspace.WorkspaceDirectoryBrowser(
            projectsRoot = sessionWorkspaceResolver.projectsRoot,
            shared = com.promenar.infinity.data.workspace.AndroidSharedStorageVolumes(this),
            workspaceDao = database.workspaceDao(),
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeWorkspaceSource(linux.runtime),
        )
    }

    /** 知识库“工作区”分组：只读浏览已登记目录，单个文件复制进知识库后经索引协调器入队。 */
    val workspaceLibrary: com.promenar.infinity.data.workspace.WorkspaceLibrary by lazy {
        com.promenar.infinity.data.workspace.WorkspaceLibrary(
            workspaceDao = database.workspaceDao(),
            resolveWorkspace = sessionWorkspaceResolver::resolveWorkspace,
            backendFor = sessionWorkspaceResolver::backend,
            knowledge = workspaceRepository,
            indexEvents = pendingDocumentIndexCoordinator,
        )
    }

    /** Git 变更面板的只读数据：打包 Python 直接运行 `infinity_git --infinity-inspect`，不经过 shell_exec 与执行审批。 */
    val gitChangesService: com.promenar.infinity.data.git.GitChangesService by lazy {
        val locator = com.promenar.infinity.data.git.SessionGitWorkspaceLocator(
            resolveWorkspace = sessionWorkspaceResolver::resolve,
            privateRoot = { sessionId -> workspaceRepository.ensureSessionRoot(sessionId).physicalRootPath },
            linuxPathMappings = { sessionId ->
                if (sessionRunModes.modeFor(sessionId) != com.promenar.infinity.domain.model.SessionRunMode.LINUX) null else {
                    val session = checkNotNull(database.sessionDao().getById(sessionId))
                    mapOf("/" to linux.runtime.paths.rootfs.canonicalPath, "/workspace" to branchUnderlyingWorkspace(session).canonicalPath) +
                        externalFolderManager.visible(sessionId).filter { it.commandAccessible }.associate { "/mnt/${it.alias}" to File(it.sourceId).canonicalPath }
                }
            },
        )
        com.promenar.infinity.data.git.GitChangesService(
            locate = locator::locate,
            inspector = com.promenar.infinity.data.git.GitInspector(
                // 只读检查不需要 pip 与用户站点目录，保持 PYTHONNOUSERSITE，确保加载打包的 dulwich。
                runtime = com.promenar.infinity.data.git.BuiltinGitPythonRuntime(
                    com.promenar.infinity.data.exec.BuiltinExecEnvironment.create(this, pipEnabled = false, skillsDirectory = skillCatalog.userRoot),
                ),
                runner = com.promenar.infinity.data.git.BuiltinGitInspectCommandRunner(
                    com.promenar.infinity.data.exec.BuiltinCommandRunner(
                        groupLauncher = listOf("/system/bin/setsid"),
                        shell = "/system/bin/sh",
                        signaller = com.promenar.infinity.data.exec.AndroidProcessGroupSignaller,
                        scratchDirectory = { File(cacheDir, "git-inspect-run") },
                        clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
                    ),
                ),
                outputDirectory = { File(cacheDir, "git-inspect") },
            ),
        )
    }

    override val execRunRegistry: com.promenar.infinity.data.exec.ExecRunRegistry by lazy {
        com.promenar.infinity.data.exec.ExecRunRegistry()
    }

    internal val globalKnowledgeWorkspaceProvisioner: RagWorkspaceProvisioner by lazy {
        RagWorkspaceProvisioner(
            filesDir = filesDir,
            database = database,
            workspaceRepository = workspaceRepository,
        )
    }

    val externalFolderManager: com.promenar.infinity.domain.storage.ExternalWorkspaceAccess by lazy {
        com.promenar.infinity.data.storage.ExternalFolderManager(
            this, database.externalMountDao(), workspaceRepository, pendingDocumentIndexCoordinator,
            workspaces = sessionWorkspaceResolver,
            userSkillsDirectory = skillCatalog.userRoot,
            // 每次知识库挂载访问都会核验根，只查根列，不加载会话实体。
            knowledgeBaseRoots = { withContext(Dispatchers.IO) { database.query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT DISTINCT workspace_root_uuid FROM sessions WHERE agent_id = ? AND workspace_root_uuid IS NOT NULL AND workspace_root_uuid != ''",
                arrayOf(com.promenar.infinity.data.rag.RetrievalScopeResolver.KNOWLEDGE_BASE_AGENT_ID),
            )).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } } } },
            knowledgeBaseOwnerRoot = { sessionId -> database.sessionDao().getById(sessionId)
                ?.takeIf { it.agentId == "__system__" }?.workspaceRootUuid },
        )
    }

    private val directoryWorkspaceBusyChecks = com.promenar.infinity.data.workspace.DirectoryWorkspaceBusyChecks()

    fun registerDirectoryWorkspaceBusyCheck(check: (String) -> Boolean): AutoCloseable =
        directoryWorkspaceBusyChecks.register(check)

    /** 会话正在生成、等待审批或有其它占用时，资源管理器的用户文件操作一律拒绝。 */
    private suspend fun isSessionBusyForUserFileOps(sessionId: String): Boolean {
        val phase = generationCoordinator.observe(sessionId).value?.phase
        val running = phase != null && phase !in setOf(
            com.promenar.infinity.domain.generation.GenerationPhase.COMPLETED,
            com.promenar.infinity.domain.generation.GenerationPhase.FAILED,
            com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED,
            com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED,
        )
        return running || directoryWorkspaceBusyChecks.isBusy(sessionId) ||
            chatStore.getSession(sessionId)?.approvalRequest != null ||
            database.sessionDao().getById(sessionId)?.approvalRequest != null
    }

    val directoryWorkspaceFiles: com.promenar.infinity.domain.workspace.DirectoryWorkspaceFiles by lazy {
        com.promenar.infinity.data.workspace.DefaultDirectoryWorkspaceFiles(externalFolderManager, sessionExecutionGate, ::isSessionBusyForUserFileOps)
    }

    val privateWorkspaceFiles: com.promenar.infinity.domain.workspace.PrivateWorkspaceFiles by lazy {
        com.promenar.infinity.data.workspace.DefaultPrivateWorkspaceFiles(
            workspaceRepository,
            pendingDocumentIndexCoordinator,
            sessionExecutionGate,
            ::isSessionBusyForUserFileOps,
        )
    }

    val pendingDocumentIndexCoordinator: PendingDocumentIndexCoordinator by lazy {
        PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { event -> awaitVectorizationQueue().publish(event) },
            resolveCurrentTarget = { target ->
                val entry = database.fileEntryDao().getActiveByUuid(
                    target.workspaceRootUuid,
                    target.fileUuid,
                )
                entry?.let {
                    target.copy(
                        contentHash = entry.hash,
                        targetEpoch = entry.updatedAt,
                    )
                }
            },
        )
    }

    val fileOperationRepository: com.promenar.infinity.domain.repository.IFileOperationRepository by lazy {
        FileOperationRepository(
            database.fileEntryDao(),
            database.fileVersionDao(),
            fileOps = privateWorkspaceFileOps,
            identityBaselines = workspaceIdentityBaselines,
            indexEventSink = pendingDocumentIndexCoordinator,
            executionGate = sessionExecutionGate,
        )
    }

    val longTermMemoryRepository: com.promenar.infinity.data.memory.LongTermMemoryRepository by lazy {
        com.promenar.infinity.data.memory.RoomLongTermMemoryRepository(database)
    }
    private val roomKnowledgeCitationRepository by lazy {
        com.promenar.infinity.data.library.ask.RoomKnowledgeCitationRepository(database)
    }
    val knowledgeCitationRepository: com.promenar.infinity.domain.library.KnowledgeCitationRepository by lazy {
        roomKnowledgeCitationRepository
    }
    val libraryAskService: com.promenar.infinity.domain.library.LibraryAskService by lazy {
        val backend = com.promenar.infinity.data.library.ask.RoomLibraryAskBackend(
            database = database,
            manager = ProviderManager.getInstance(),
            preferences = getSharedPreferences("infinity_settings", MODE_PRIVATE),
            embedding = { libraryAskEmbeddingClient },
            embeddingSlotChanges = if (localInferenceRuntimeGate.isAvailable) {
                localInferenceEngine.embeddingSlot.map { Unit }
            } else kotlinx.coroutines.flow.flowOf(Unit),
            memoryManager = { memoryManager },
            memories = longTermMemoryRepository,
            citations = roomKnowledgeCitationRepository,
            libraryLocation = { getString(R.string.library_ask_location_library) },
            workspaceLocation = { getString(R.string.library_ask_location_workspace, it) },
            memoryLocation = { getString(R.string.library_ask_location_memory) },
            entityLocation = { getString(R.string.library_ask_location_entity, it) },
            answerStream = { modelId, prompt ->
                kotlinx.coroutines.flow.flow {
                    val route = providerRequestRouter.requireModelRoute(modelId, ::modelRouteError)
                    val protocol = createRoutedModelProtocol(route)
                    var completed = false
                    protocol.sendPrompt(com.promenar.infinity.data.remote.protocol.PromptRequest(
                        model = route.remoteModelId,
                        messages = listOf(
                            com.promenar.infinity.data.remote.protocol.ProtocolMessage("system", prompt.system),
                            com.promenar.infinity.data.remote.protocol.ProtocolMessage("user", prompt.user),
                        ),
                        maxTokens = 1024,
                        stream = true,
                    )).collect { chunk ->
                        when (chunk) {
                            is com.promenar.infinity.data.remote.protocol.StreamChunk.TextDelta -> emit(chunk.content)
                            is com.promenar.infinity.data.remote.protocol.StreamChunk.Error -> error("问资料模型请求失败")
                            is com.promenar.infinity.data.remote.protocol.StreamChunk.Completed -> {
                                check(chunk.reason == com.promenar.infinity.domain.generation.CompletionReason.END_TURN)
                                completed = true
                            }
                            else -> Unit
                        }
                    }
                    check(completed) { "问资料模型未返回完成信号" }
                }
            },
        )
        com.promenar.infinity.data.library.ask.DefaultLibraryAskService(backend)
    }
    val longTermMemoryAccess: com.promenar.infinity.data.memory.LongTermMemoryAccess by lazy {
        com.promenar.infinity.data.memory.RoomLongTermMemoryAccess(
            database, getSharedPreferences("infinity_settings", MODE_PRIVATE), chatStore::getSession,
        )
    }
    val stateBoardRepository by lazy { com.promenar.infinity.data.repository.StateBoardRepository(database) }

    val taskRepository: com.promenar.infinity.domain.repository.ITaskRepository by lazy {
        TaskRepository(database.taskNodeDao(), database)
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addInterceptor(com.promenar.infinity.utils.MetroLogInterceptor())
                // tailnet 目标经内置 Tailscale 本机代理；其它目标仍按系统默认选择器（无系统代理即直连）。
                config {
                    proxySelector(tailnetManager.proxySelector)
                    proxyAuthenticator(tailnetManager.proxyAuthenticator)
                }
            }
        }
    }

    private val linuxSessionFilesDelegate = lazy {
        com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess(
            externalFolderManager, workspaceRepository, fileOperationRepository, sessionWorkspaceResolver::resolve,
            com.promenar.infinity.data.workspace.LinuxSessionRootfsBackend(linux.files), pendingDocumentIndexCoordinator, sessions = sessionRepository,
            branchNamespace = { sessionId ->
                val row = database.sessionDao().getById(sessionId)
                row?.worktreeDirectory?.let { cwd ->
                    val baseRoot = branchUnderlyingWorkspace(row)
                    com.promenar.infinity.data.branch.BranchLinuxFileNamespace(
                        com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace(baseRoot, cwd),
                        com.promenar.infinity.data.storage.RealPathExternalFolderBackend(baseRoot))
                }
            },
        )
    }
    val linuxSessionFiles by linuxSessionFilesDelegate

    internal fun forgetLinuxRootfsVersions(path: String) {
        if (linuxSessionFilesDelegate.isInitialized()) linuxSessionFiles.forgetRootfsVersions(path)
    }

    val linuxRunModeRecovery by lazy {
        com.promenar.infinity.data.linux.LinuxRunModeRecovery(linux.runtime, linuxHomeLocation = { sessionId ->
            database.sessionDao().getById(sessionId)?.workspaceId?.let { id -> database.workspaceDao().getById(id) }
                ?.takeIf { it.kind == com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_LINUX_HOME }?.location
        })
    }

    fun exportLinuxSessionHome(sessionId: String, treeUri: android.net.Uri? = null): kotlinx.coroutines.flow.Flow<com.promenar.infinity.data.linux.LinuxRecoveryEvent> =
        kotlinx.coroutines.flow.flow {
            val target = withContext(Dispatchers.IO) {
                com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
                if (treeUri != null) com.promenar.infinity.data.linux.LinuxRunModeSafExportTarget(contentResolver, treeUri)
                else {
                    val root = workspaceRepository.ensureSessionRoot(sessionId)
                    com.promenar.infinity.data.linux.LinuxRunModeDirectoryExportTarget(File(root.physicalRootPath),
                        reconcile = { reconcileSessionHome(sessionId) }, sessionId = sessionId, rootId = root.uuid)
                }
            }
            linuxRunModeRecovery.export(sessionId, target).collect { emit(it) }
        }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        com.promenar.infinity.domain.tool.LinuxExecRouting.runtime = linux.runtime
        DefaultSkillRegistry().apply {
            fun mounted(skill: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition) =
                com.promenar.infinity.ui.chat.manager.skills.LinuxSessionFileSkill(
                    com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill(skill, externalFolderManager, workspaceRepository, fileOperationRepository, pendingDocumentIndexCoordinator),
                    sessionRunModes::modeFor, linuxSessionFiles, workspaceRepository, fileOperationRepository, pendingDocumentIndexCoordinator,
                )
            startupDiagnosticTools(startupIssueManager).forEach(::register)
            com.promenar.infinity.ui.chat.manager.skills.longTermMemoryTools(longTermMemoryRepository, longTermMemoryAccess).forEach(::register)
            register(com.promenar.infinity.ui.chat.manager.skills.KnowledgeSearchSkill(
                sessionRepository::getById, { agentRepository.getById(it)?.retrievalConfig }, knowledgeCatalog,
                com.promenar.infinity.data.rag.MemoryManagerRagAdapter { memoryManager }, kgProvider, knowledgeTurns))
            register(com.promenar.infinity.ui.chat.manager.skills.RequestKnowledgeAccessSkill(
                knowledgeAccessPlanner, sessionRepository::getById, { agentRepository.getById(it)?.retrievalConfig }, knowledgeTurns))
            register(com.promenar.infinity.ui.chat.manager.skills.CopyKnowledgeToLinuxSkill(
                sessionRepository::getById, { current -> knowledgeAccessAllowedForTools(current) }, externalFolderManager,
                workspaceRepository, linux.files, { linux.runtime.state.value is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready }, ::reconcileSessionHome))
            register(CalculatorSkill())
            register(com.promenar.infinity.data.skills.InstallApprovedSkillTool(skillCatalog))
            register(com.promenar.infinity.ui.chat.manager.AskUserTool())
            register(com.promenar.infinity.ui.chat.manager.skills.RequestSecretSkill())
            register(com.promenar.infinity.ui.chat.manager.skills.ShowSecretSkill(sessionSecretVault))
            register(com.promenar.infinity.data.branch.SessionBranchTool())
            register(com.promenar.infinity.data.agentconfig.AgentsReadTool { agentConfigService })
            register(com.promenar.infinity.data.agentconfig.AgentsWriteTool { agentConfigService })
            register(com.promenar.infinity.data.branch.SessionListTool { branchService })
            register(com.promenar.infinity.ui.chat.manager.skills.TermuxExecSkill(termuxRuntime::executeRequest, backgroundJobs) {
                com.promenar.infinity.data.termux.TermuxPreferences.isEnabled(getSharedPreferences("infinity_settings", MODE_PRIVATE))
            })
            com.promenar.infinity.ui.chat.manager.skills.sshTools(sshRuntime, backgroundJobs,
                com.promenar.infinity.ui.chat.manager.skills.SftpTransferAccess(
                    workspaceFiles = com.promenar.infinity.data.workspace.SftpWorkspaceFiles(
                        sessionRepository, workspaceRepository, externalFolderManager,
                        sessionWorkspaceResolver::resolve, skillCatalog.userRoot,
                        sessionWorkspaceResolver::resolveWorkspace, sessionWorkspaceResolver::backend,
                        indexEvents = pendingDocumentIndexCoordinator,
                    ),
                    linux = linux.files,
                    linuxAvailable = { BuildConfig.DISTRIBUTION == "sideload" && com.promenar.infinity.domain.tool.LinuxExecRouting.available },
                ),
            ).forEach(::register)
            com.promenar.infinity.ui.chat.manager.skills.sshHostManagementTools(
                store = sshHostStore,
                runtime = object : com.promenar.infinity.ui.chat.manager.skills.SshHostManagementRuntime {
                    override suspend fun probe(alias: String) = sshRuntime.probe(alias)
                    override suspend fun run(owner: String, alias: String, command: String): Int? =
                        sshRuntime.exec(owner, alias, command, timeoutSeconds = 30, maxOutputChars = 4_000).let { if (it.timedOut) null else it.exitCode }
                    override suspend fun stopForward(alias: String) = sshRuntime.stopForward(alias)
                    override fun generateKey(): Pair<ByteArray, String> =
                        com.promenar.infinity.data.ssh.SshKeyGenerator.generate().use { it.privateKey.copyOf() to it.publicKey }
                },
                prompts = sshUserPrompts,
            ) {
                com.promenar.infinity.data.ssh.SshAgentPreferences.isHostManagementEnabled(getSharedPreferences("infinity_settings", MODE_PRIVATE))
            }.forEach(::register)
            register(WebSearchSkill(this@InfinityApplication, httpClient, secretStore))
            register(WebFetchSkill(gitRemoteHttpClient, networkPolicy = agentNetworkPolicy, tailnetRouting = tailnetManager))
            com.promenar.infinity.ui.chat.manager.skills.browserTools(agentBrowser, agentNetworkPolicy,
                com.promenar.infinity.ui.browser.WorkspaceBrowserScreenshotPublisher(workspaceMediaStore, workspaceMediaViewer) { sessionId ->
                    chatStore.getSession(sessionId)?.let { resolveMediaViewTarget(it) }
                        ?: com.promenar.infinity.data.generation.MediaViewTarget()
                }).forEach(::register)
            register(com.promenar.infinity.ui.chat.manager.skills.ViewMediaSkill(workspaceMediaViewer) { sessionId ->
                chatStore.getSession(sessionId)?.let { resolveMediaViewTarget(it) }
                    ?: com.promenar.infinity.data.generation.MediaViewTarget()
            })
            register(ImageGenerationSkill(this@InfinityApplication, ProviderManager.getInstance(), workspaceMediaStore, workspaceMediaDownloader,
                providerRequestRouter = providerRequestRouter, mediaResolver = workspaceMediaResolver))
            register(com.promenar.infinity.ui.chat.manager.skills.MediaFetchSkill(workspaceMediaStore, workspaceMediaDownloader,
                mounts = externalFolderManager, sessionWorkspace = sessionWorkspaceResolver::resolve))
            register(mounted(FileReadSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileWriteSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileDiffSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FilePatchSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileListSkill(workspaceRepository)))
            register(mounted(FileSearchSkill(workspaceRepository, fileOperationRepository)))
            register(mounted(CreateFileSkill(workspaceRepository, fileOperationRepository)))
            register(mounted(CreateDirectorySkill(workspaceRepository)))
            register(mounted(MoveFileSkill(workspaceRepository, pendingDocumentIndexCoordinator)))
            register(mounted(DeleteFileSkill(workspaceRepository)))
            register(ExecJsSkill(scriptToolRunner))
            register(com.promenar.infinity.ui.chat.manager.skills.RunScriptSkill(scriptToolRunner))
            register(com.promenar.infinity.ui.chat.manager.skills.PythonSandboxSkill(scriptToolRunner))
            register(com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill(builtinExecService, backgroundJobs))
            register(com.promenar.infinity.ui.chat.manager.skills.PythonExecSkill(builtinExecService, backgroundJobs))
            com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools(backgroundJobs).forEach(::register)
            register(com.promenar.infinity.ui.chat.manager.skills.StateDefineSkill(stateBoardRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.StateUpdateSkill(stateBoardRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.StateGetSkill(stateBoardRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.StateRemoveSkill(stateBoardRepository))
            register(InitializePlanSkill(taskRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.CanvasCreateSkill(canvasStore))
            register(com.promenar.infinity.ui.chat.manager.skills.CanvasUpdateSkill(canvasStore))
            register(com.promenar.infinity.ui.chat.manager.skills.CanvasReadSkill(canvasStore))
            register(com.promenar.infinity.ui.chat.manager.skills.CanvasListSkill(canvasStore))
            register(com.promenar.infinity.ui.chat.manager.skills.ArtifactPublishSkill(sessionArtifacts))
            register(com.promenar.infinity.ui.chat.manager.skills.ArtifactListSkill(sessionArtifacts))
            register(UpdatePlanSkill(taskRepository))
            register(GetPlanSkill(taskRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.ReadToolOutputSkill(toolOutputStore))
            register(DropPlanSkill(taskRepository))
            register(com.promenar.infinity.ui.chat.manager.skills.TailnetStatusSkill(tailnetManager))
            register(com.promenar.infinity.ui.chat.manager.skills.AgentTaskSkill(
                policyFor = { agentRepository.getById(it.agentId)?.subagentModelPolicy ?: com.promenar.infinity.data.agent.AgentSubagentModelPolicy() },
                modelSupportsToolCalling = { modelId -> com.promenar.infinity.data.subagent.SubagentModelSelection.supportsToolCalling(
                    modelId, ProviderManager.getInstance().providerModels.value,
                ) },
            ) { subagentLauncher })
            register(com.promenar.infinity.ui.chat.manager.skills.AgentWaitSkill { subagentLauncher })
            val subagentAddress = com.promenar.infinity.ui.chat.manager.skills.SubagentAddressHandler({ subagentLauncher }) { context, run, text ->
                sessionMailbox.recordSubagentSend(context.sessionId, run.id, run.description, text)
            }
            listOf("session_read", "session_send", "session_stop").forEach { operation ->
                register(com.promenar.infinity.ui.chat.manager.skills.SessionSubagentSkill(operation, listOf(subagentAddress, com.promenar.infinity.data.branch.BranchSessionAddressHandler { branchService })))
            }
        }
    }

    val userSkillRegistry: UserSkillRegistry by lazy {
        UserSkillRegistry(skillRepository as SkillRepository)
    }

    val mcpSkillRegistry: McpSkillRegistry by lazy {
        McpSkillRegistry(skillRepository as SkillRepository, httpClient, generationScope, connectionSettings = mcpConnectionSettings, mediaStore = workspaceMediaStore)
    }

    /** Agent Skills 指令包存储（ADR-022）。 */
    val agentSkillStore: com.promenar.infinity.data.skills.AgentSkillStore by lazy {
        com.promenar.infinity.data.skills.AgentSkillStore(
            File(filesDir, "agent_skills"),
            getSharedPreferences("infinity_settings", MODE_PRIVATE),
        )
    }

    /** 内置指令与用户级、工作区项目级技能的统一目录入口。 */
    val skillCatalog: com.promenar.infinity.data.skills.SkillCatalog by lazy {
        com.promenar.infinity.data.skills.BuiltinSkillCatalog(com.promenar.infinity.data.skills.FileSkillCatalog(
            File(filesDir, "agent_skills"),
            getSharedPreferences("infinity_settings", MODE_PRIVATE),
            File(noBackupFilesDir, "skill_state"),
        )) { path -> assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    }

    val skillRegistry: SkillRegistry by lazy { createSkillRegistry() }

    /** 会话工具表绑定自己的工作区，后台生成不依赖界面当前选中的会话。 */
    private fun createSkillRegistry(sessionId: String? = null): SkillRegistry =
        StartupGuardedSkillRegistry(ModularSkillRegistry(
            listOf(
                presetSkillRegistry,
                com.promenar.infinity.ui.chat.manager.skills.scheduleSkillRegistry(scheduledTaskController),
                com.promenar.infinity.ui.chat.manager.skills.AgentSkillToolRegistry(skillCatalog, sessionWorkspaceResolver::resolve, sessionId),
                userSkillRegistry,
                mcpSkillRegistry,
            )
        ))

    private fun knowledgeConfigForTools(agentId: String): com.promenar.infinity.data.agent.AgentRetrievalConfig? =
        database.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT retrieval_config FROM agents WHERE id = ?", arrayOf(agentId))).use {
            if (it.moveToFirst()) com.promenar.infinity.data.local.db.Converters.toRetrievalConfig(it.getString(0)) else null
        }

    val knowledgeTurns = com.promenar.infinity.data.knowledge.KnowledgeTurnState()
    val knowledgeCatalog by lazy { com.promenar.infinity.data.knowledge.RoomKnowledgeCatalog(database, externalFolderManager) }
    val knowledgeAccessPlanner by lazy { com.promenar.infinity.data.knowledge.KnowledgeAccessPlanner(
        sessionRepository::getById, { agentRepository.getById(it)?.retrievalConfig }, externalFolderManager, knowledgeCatalog,
    ) }

    suspend fun knowledgeAccessApprovalTarget(session: com.promenar.infinity.data.model.Session, name: String, arguments: String): String? {
        if (name != "request_knowledge_access") return null
        val args = (com.promenar.infinity.domain.tool.ToolArgumentsValidator().validate(arguments) as? com.promenar.infinity.domain.tool.ToolArgumentsValidation.Valid)?.arguments ?: return null
        val base = (args["knowledge_base"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return com.promenar.infinity.data.model.json.encodeToString(knowledgeAccessPlanner.preview(session.id, base))
    }

    private fun knowledgeAccessAllowedForTools(current: com.promenar.infinity.data.model.Session): Boolean =
        com.promenar.infinity.data.rag.effectiveKnowledgeFileAccess(knowledgeConfigForTools(current.agentId), current.options.toolsEnabled) ==
            com.promenar.infinity.data.agent.KnowledgeFileAccess.ON_REQUEST

    val sessionToolResolver: com.promenar.infinity.data.generation.SessionToolResolver by lazy { createSessionToolResolver(false) }
    private val mainChatToolResolver: com.promenar.infinity.data.generation.SessionToolResolver by lazy { createSessionToolResolver(true) }

    private fun createSessionToolResolver(stableKnowledgeAvailability: Boolean): com.promenar.infinity.data.generation.SessionToolResolver =
        object : com.promenar.infinity.data.generation.SessionToolResolver {
            override fun resolve(session: com.promenar.infinity.data.model.Session): List<com.promenar.infinity.data.remote.protocol.ProtocolTool> =
                com.promenar.infinity.data.generation.DefaultSessionToolResolver(
                    getSharedPreferences("infinity_settings", MODE_PRIVATE),
                    createSkillRegistry(session.id),
                    longTermMemoryAllowed = longTermMemoryAccess::isEnabledForToolAdvertisement,
                    knowledgeSearchAllowed = { current ->
                        val config = knowledgeConfigForTools(current.agentId)
                        if (stableKnowledgeAvailability) com.promenar.infinity.data.generation.knowledgeSearchAvailableForPrompt(current, config) else
                            com.promenar.infinity.data.rag.effectiveRetrievalMode(config, current.options.toolsEnabled) != com.promenar.infinity.data.agent.AgentRetrievalMode.AUTO &&
                                knowledgeCatalog.hasIndexedMaterial(current, com.promenar.infinity.data.rag.resolveEffectiveRagOptions(current.ragOptions,
                                    memoryPolicy = config?.memoryPolicy ?: com.promenar.infinity.data.agent.AgentMemoryPolicy()))
                    },
                    knowledgeAccessAllowed = ::knowledgeAccessAllowedForTools,
                    subagentModelPolicy = { current -> knowledgeConfigForTools(current.agentId)?.subagentModels ?: com.promenar.infinity.data.agent.AgentSubagentModelPolicy() },
                    modelName = { modelId -> ProviderManager.getInstance().providerModels.value.firstOrNull { it.id == modelId }?.name },
                ) { current ->
                    com.promenar.infinity.data.generation.MediaViewTarget.capabilities(
                        ProviderManager.getInstance().providerModels.value.firstOrNull { it.id == current.modelId },
                    )
                }.resolve(session)

            // 审批后的执行路径从主线程调用；技能注册表会同步扫描磁盘，必须切到 IO。
            override suspend fun resolveForExecution(session: com.promenar.infinity.data.model.Session): List<com.promenar.infinity.data.remote.protocol.ProtocolTool> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val current = session.copy(runMode = sessionRunModes.modeFor(session.id))
                    val target = resolveMediaViewTarget(current)
                    if (target.modelId.isBlank()) resolve(current).filterNot { it.runtimeToolId == "view_media" }
                    else resolve(current.copy(modelId = target.modelId))
                }
        }

    private val workspaceMediaViewer by lazy {
        com.promenar.infinity.data.generation.WorkspaceMediaViewer(
            workspaceMediaResolver,
            com.promenar.infinity.data.generation.AndroidMediaViewPreprocessor(this, workspaceMediaResolver),
        )
    }

    private suspend fun resolveMediaViewTarget(
        session: com.promenar.infinity.data.model.Session,
        routedModelId: String? = null,
    ): com.promenar.infinity.data.generation.MediaViewTarget = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val manager = ProviderManager.getInstance()
        val modelId = routedModelId ?: session.modelId?.takeIf(String::isNotBlank)
            ?: configResolver.resolve(agentRepository.getById(session.agentId)).modelId.takeIf(String::isNotBlank)
            ?: manager.getMainConfiguredModelId().orEmpty()
        when (val route = providerRequestRouter.resolve(modelId)) {
            is com.promenar.infinity.data.remote.ProviderResolution.Failure -> com.promenar.infinity.data.generation.MediaViewTarget()
            is com.promenar.infinity.data.remote.ProviderResolution.Success -> com.promenar.infinity.data.generation.MediaViewTarget(
                com.promenar.infinity.data.generation.MediaViewTarget.capabilities(manager.providerModels.value.firstOrNull { it.id == route.value.modelId }),
                route.value.config.protocolType,
                route.value.modelId,
            )
        }
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("infinity_provider", MODE_PRIVATE)
    }

    private val _providerConfigurationVersion = MutableStateFlow(0L)
    val providerConfigurationVersion: StateFlow<Long> = _providerConfigurationVersion
    private var localProviderOverride: String? = null
    val llmProvider: LlmProvider get() = buildCredentialResolvingProvider()

    private var _unifiedLlmClient: com.promenar.infinity.data.remote.UnifiedLlmClient? = null
    val unifiedLlmClient: com.promenar.infinity.data.remote.UnifiedLlmClient?
        get() = _unifiedLlmClient ?: buildUnifiedLlmClient().also { _unifiedLlmClient = it }

    private val sessionHeaderIdentity by lazy {
        com.promenar.infinity.data.local.SessionHeaderIdentityStore(java.io.File(noBackupFilesDir, "session_headers"))
    }

    /** 聊天主路径按每次请求的稳定模型归属解析，不复用 default Provider 客户端。 */
    val providerRequestRouter: ProviderRequestRouter by lazy {
        val middlewares = if (com.promenar.infinity.BuildConfig.DEBUG) {
            listOf(com.promenar.infinity.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        DefaultProviderRequestRouter(
            ProviderManager.getInstance(),
            middlewares,
            sessionIdentifier = sessionHeaderIdentity::identifier,
            sessionHeaderPreferences = com.promenar.infinity.data.local.SessionHeaderPreferences(getSharedPreferences("infinity_settings", MODE_PRIVATE)),
        )
    }

    var hapticEnabled: Boolean = true
        internal set

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // 工作区媒体：字符串地址映射、带内容身份的缓存键与经解析器读取的取数器。
                add(com.promenar.infinity.ui.media.WorkspaceMediaUriMapper())
                add(com.promenar.infinity.ui.media.WorkspaceMediaKeyer())
                add(com.promenar.infinity.ui.media.WorkspaceMediaFetcher.Factory(context) { workspaceMediaResolver })
                add(VideoFrameDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(base: Context) {
        if (android.os.Process.isIsolated()) {
            super.attachBaseContext(base)
            return
        }
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        agentNetworkSettings
        getSharedPreferences("infinity_settings", MODE_PRIVATE).edit().remove("mcp_allow_loopback_http").apply()
        getSharedPreferences(com.promenar.infinity.data.tailnet.TailnetSettingsStore.PREFERENCES_NAME, MODE_PRIVATE)
            .edit().remove("tailnet_allowed_peers").apply()
        instance = this
        com.promenar.infinity.startup.diagnostics.AppDiagnostics.initialize(filesDir)
        com.promenar.infinity.utils.InfinityLogger.init(this)
        StartupWriteBarrier.install(startupIssueManager)
        startupIssueManager.scopeDisplayName = { scope ->
            when (scope.kind) {
                StartupScopeKind.GLOBAL -> getString(R.string.startup_isolation_scope_global)
                StartupScopeKind.WORKSPACE_ROOT -> scope.displayName.takeUnless { it == "工作区" } ?: getString(R.string.startup_isolation_scope_workspace)
                StartupScopeKind.SESSION -> scope.displayName.takeUnless { it == "会话" } ?: getString(R.string.startup_isolation_scope_session)
                StartupScopeKind.SUBSYSTEM -> getString(when (scope.id) {
                    StartupSubsystem.WORKSPACE_RECOVERY.name -> R.string.startup_subsystem_workspaces
                    StartupSubsystem.TOOL_LEDGER.name -> R.string.startup_subsystem_tools
                    StartupSubsystem.VECTOR_QUEUE.name -> R.string.startup_subsystem_vectors
                    StartupSubsystem.BACKGROUND_JOBS.name -> R.string.startup_subsystem_background
                    StartupSubsystem.HEADLESS_INTERACTIONS.name -> R.string.startup_subsystem_confirmations
                    StartupSubsystem.SCHEDULED_TASKS.name -> R.string.startup_subsystem_schedules
                    StartupSubsystem.MODEL_CATALOG.name -> R.string.startup_subsystem_catalog
                    else -> R.string.startup_subsystem_memory
                })
            }
        }
        startupIssueManager.writeBlockedMessage = { issue ->
            getString(when (issue.scope.kind) {
                StartupScopeKind.WORKSPACE_ROOT -> com.promenar.infinity.R.string.startup_isolation_workspace_write_blocked
                StartupScopeKind.SESSION -> com.promenar.infinity.R.string.startup_isolation_session_write_blocked
                else -> com.promenar.infinity.R.string.startup_isolation_subsystem_disabled
            })
        }
        startupIssueManager.onResolved = { issue ->
            if (issue.severity == StartupSeverity.BLOCKING) {
                startBackupRecovery()
                startupRecoveryJob?.join()
            } else if (_startupState.value == BackupStartupState.Ready && (issue.scope.kind in setOf(StartupScopeKind.SESSION, StartupScopeKind.WORKSPACE_ROOT) ||
                    issue.scope.kind == StartupScopeKind.SUBSYSTEM && issue.scope.id == StartupSubsystem.WORKSPACE_RECOVERY.name)) {
                // 范围恢复成功后补回该范围被跳过的索引和排期，其它子系统失败单独记录。
                if (!startupIssueManager.isSubsystemIsolated(StartupSubsystem.VECTOR_QUEUE)) {
                    startupIssueManager.recover(StartupStep.VECTOR_QUEUE, StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE)) {
                        withContext(Dispatchers.IO) { vectorizationQueue.resumeInterruptedTasks().getOrThrow() }
                    }
                }
                if (!startupIssueManager.isSubsystemIsolated(StartupSubsystem.SCHEDULED_TASKS)) {
                    startupIssueManager.recover(StartupStep.SCHEDULED_TASKS, StartupIssueScope.subsystem(StartupSubsystem.SCHEDULED_TASKS)) { scheduledTaskController.reconcile() }
                }
            }
        }

        // 独立 relay 进程不得打开 Room、pending payload 或注册任何业务 writer。
        if (currentProcessName().endsWith(RestoreRelayActivity.PROCESS_SUFFIX)) {
            restoreRelayEarlyExit = true
            InfinityLogger.log("[InfinityRestoreRelay] application_early_exit_before_runtime_and_writers")
            return
        }

        ModelCatalogRuntime.initialize(this)

        PDFBoxResourceLoader.init(applicationContext)
        startupScope.launch(Dispatchers.IO) {
            runCatching {
                toolAllowlistStore.clearLegacyOnce()
                val legacy = File(noBackupFilesDir, "tool-grants")
                check(!legacy.exists() || legacy.deleteRecursively()) { "旧会话授权目录清理失败" }
            }.onFailure { InfinityLogger.log("[ToolAllowlist] 旧会话授权清理失败：${it::class.java.simpleName}") }
        }
        startBackupRecovery()
        // 模型调用在请求线程上首次读取路由，内置 Tailscale 无需在主线程创建。
        com.promenar.infinity.data.remote.ModelHttp.install(routing = { tailnetManager }, gate = { tailnetConnectGate })
        // 内置 Tailscale 启用时随 App 启动拉起子进程；App 进程结束时子进程随 stdin 关闭退出。
        startupScope.launch(Dispatchers.IO) { tailnetManager.start() }
    }

    private fun currentProcessName(): String = try {
        Application.getProcessName()
    } catch (_: NoSuchMethodError) {
        // 旧 Robolectric shadow 不提供该静态方法；真实 minSdk 31 设备始终走上支。
        packageName
    }

    fun retryStartupRecovery() {
        startBackupRecovery()
    }

    fun startDualDatabaseRecovery() {
        val required = _startupState.value as? BackupStartupState.DualDatabaseRecoveryRequired ?: return
        acceptedDualDatabaseOfferToken = required.offerToken
        startBackupRecovery()
    }

    private fun startBackupRecovery() {
        if (startupRecoveryJob?.isActive == true || _startupState.value == BackupStartupState.Ready) return
        _startupState.value = BackupStartupState.Recovering
        _startupBlockedSummary.value = null
        startupRecoveryJob = startupScope.launch {
            try {
                var dualResult: DualDatabaseBootstrapResult? = null
                startupIssueManager.recover(StartupStep.DUAL_DATABASE, StartupIssueScope.Global) {
                    dualResult = withContext(Dispatchers.IO) {
                        acceptedDualDatabaseOfferToken?.let { token ->
                            acceptedDualDatabaseOfferToken = null
                            dualDatabaseRecoveryBootstrap.recover(token)
                        } ?: dualDatabaseRecoveryBootstrap.inspectOrResume()
                    }
                    (dualResult as? DualDatabaseBootstrapResult.Blocked)?.let { throw IllegalStateException(it.detail) }
                }
                when (val result = checkNotNull(dualResult)) {
                    DualDatabaseBootstrapResult.Normal, is DualDatabaseBootstrapResult.Completed -> Unit
                    is DualDatabaseBootstrapResult.RecoveryRequired -> {
                        _startupState.value = BackupStartupState.DualDatabaseRecoveryRequired(
                            result.offer.summary, result.offer.offerToken,
                        )
                        return@launch
                    }
                    is DualDatabaseBootstrapResult.Blocked -> error("双数据库恢复失败")
                }
                startupIssueManager.recover(StartupStep.BACKUP_RESTORE, StartupIssueScope.Global) {
                    withContext(Dispatchers.IO) {
                        if (!::backupRuntime.isInitialized) backupRuntime = createBackupRuntime()
                        check(backupRuntime.recoverBeforeWriters() == BackupStartupState.Ready) {
                            backupRuntime.failureSummary.value ?: "备份恢复失败"
                        }
                    }
                }
                // 备份恢复已收敛，业务 writer 尚未开放；重复启动只升级未自定义的出厂头像。
                // 头像升级只影响外观，失败时跳过，不能挡住后续 writer 开放。
                try {
                    com.promenar.infinity.data.agent.upgradePresetAgentAvatars(database.agentDao())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    InfinityLogger.logError("PresetAgentAvatarUpgrade", failure)
                }
                val runtime = backupRuntime
                // 向导发起的恢复在界面出现前结算：提交成功则引导记为完成，启动后直接进入主界面。
                withContext(Dispatchers.IO) { settleOnboardingRestoreAtStartup(runtime) }
                PostBackupStartupRecoverySequence(
                    recoverWorkspaceJournal = { recoverStartupWorkspaces() },
                    recoverToolLedger = {
                        startupIssueManager.recover(StartupStep.TOOL_LEDGER, StartupIssueScope.subsystem(StartupSubsystem.TOOL_LEDGER)) {
                            withContext(Dispatchers.IO) {
                                // 未知外部副作用不能重放，原有台账恢复语义保留。
                                toolExecutionLedger.recoverInterruptedRunning("进程在工具执行期间中断，请确认结果后重新发起")
                                toolExecutionLedger.recoverApprovalState()
                                askUserLedger.recoverAnsweredState()
                                secretInputLedger.expirePending()
                                com.promenar.infinity.data.secret.SessionSecretRecovery(database, sessionSecretVault).restoreUnavailableSlots()
                            }
                        }
                    },
                    // 每个根的 tombstone 与该根 journal 已在同一可重试范围内完成。
                    recoverWriterTombstones = {},
                    initializeWriters = {
                        startupIssueManager.recover(StartupStep.WRITER_INITIALIZATION, StartupIssueScope.Global) { initializeAfterRecoveryOnce() }
                    },
                    resumeVectorQueue = {
                        startupIssueManager.recover(StartupStep.VECTOR_QUEUE, StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE)) {
                            withContext(Dispatchers.IO) { vectorizationQueue.resumeInterruptedTasks().getOrThrow() }
                        }
                    },
                    onIsolatedFailure = { step, failure ->
                        val subsystem = when (step) {
                            StartupStep.TOOL_LEDGER -> StartupSubsystem.TOOL_LEDGER
                            StartupStep.VECTOR_QUEUE -> StartupSubsystem.VECTOR_QUEUE
                            else -> StartupSubsystem.WORKSPACE_RECOVERY
                        }
                        startupIssueManager.recover(step, StartupIssueScope.subsystem(subsystem)) {
                            when (subsystem) {
                                StartupSubsystem.WORKSPACE_RECOVERY -> recoverStartupWorkspaces()
                                StartupSubsystem.TOOL_LEDGER -> withContext(Dispatchers.IO) {
                                    toolExecutionLedger.recoverInterruptedRunning("进程在工具执行期间中断，请确认结果后重新发起")
                                    toolExecutionLedger.recoverApprovalState()
                                    askUserLedger.recoverAnsweredState()
                                secretInputLedger.expirePending()
                                com.promenar.infinity.data.secret.SessionSecretRecovery(database, sessionSecretVault).restoreUnavailableSlots()
                                }
                                StartupSubsystem.VECTOR_QUEUE -> withContext(Dispatchers.IO) { vectorizationQueue.resumeInterruptedTasks().getOrThrow() }
                                else -> throw failure
                            }
                        }
                    },
                ).runOrThrow()
                withContext(Dispatchers.IO) { cleanupLegacyRecycleOnce() }
                _backupRestoreResult.value = runtime.restoreResult.value
                // 恢复结果对话框已包含技能事项，不再单独弹技能提示。
                runtime.restoreResult.value?.takeIf { it.needsNotice }
                    ?.let { _backupRestoreNotice.value = it }
                if (runtime.restoreNotApplied.value) _restoreNotAppliedNotice.value = true
                startupIssueManager.recover(StartupStep.BACKGROUND_JOBS, StartupIssueScope.subsystem(StartupSubsystem.BACKGROUND_JOBS)) {
                    backgroundJobs.initialize()
                    workspaceIndexReconciliation.resumePending()
                }
                startupIssueManager.recover(StartupStep.HEADLESS_INTERACTIONS, StartupIssueScope.subsystem(StartupSubsystem.HEADLESS_INTERACTIONS)) {
                    generationInteractions.restoreHeadless { sessionId ->
                        withContext(Dispatchers.IO) { database.sessionDao().getById(sessionId) != null }
                    }
                }
                startupIssueManager.recover(StartupStep.SCHEDULED_TASKS, StartupIssueScope.subsystem(StartupSubsystem.SCHEDULED_TASKS)) {
                    scheduledTaskController.initialize()
                    startForegroundScheduleTicker()
                }
                startupIssueManager.recover(StartupStep.MODEL_CATALOG, StartupIssueScope.subsystem(StartupSubsystem.MODEL_CATALOG)) { startModelCatalogUpdates() }
                _startupState.value = BackupStartupState.Ready
                linuxWakeLifecycle.start()
                generationScope.launch {
                    try {
                        val sessionIds = withContext(Dispatchers.IO) { database.sessionDao().getAll().map { it.id } }
                        systemTurnRuntime.recoverSubagentCompletions(sessionIds)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        InfinityLogger.logError("SubagentCompletionRecovery", failure)
                    }
                    subagentLauncher.recover()
                    branchService.recover()
                }
                generationScope.launch(Dispatchers.IO) {
                    startupIssueManager.recover(StartupStep.MEMORY_CLEANUP, StartupIssueScope.subsystem(StartupSubsystem.MEMORY_CLEANUP)) {
                        conversationMemoryLifecycle.purgeExpired()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("BackupStartupRecovery", error)
                _startupBlockedSummary.value = startupIssues.value.firstOrNull { it.severity == StartupSeverity.BLOCKING }?.summary
                    ?: com.promenar.infinity.startup.diagnostics.DiagnosticRedactor.summary(error)
                _startupState.value = BackupStartupState.Blocked
            } catch (error: Error) {
                _startupBlockedSummary.value = com.promenar.infinity.startup.diagnostics.DiagnosticRedactor.summary(error)
                _startupState.value = BackupStartupState.Blocked
                throw error
            }
        }
    }

    private suspend fun recoverStartupWorkspaces() = withContext(Dispatchers.IO) {
        java.nio.file.Files.createDirectories(sessionWorkspaceParent)
        StartupWorkspaceRecovery(
            database, startupIssueManager, workspaceFileMutationRecoveryCoordinator, workspaceMutationRecoveryCoordinator,
            LegacyWorkspaceRootAdoptionCoordinator(filesDir, File(filesDir, "session_workspaces"),
                database.sessionDao(), database.fileEntryDao(), workspaceRepository),
            WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao(),
                identityBaselines = workspaceIdentityBaselines,
                deleteCommitter = com.promenar.infinity.data.repository.WorkspaceDeletionTransaction(database)::delete),
        ).recover()
    }

    private suspend fun cleanupLegacyRecycleOnce() {
        val recycleCleanupPrefs = getSharedPreferences("infinity_settings", MODE_PRIVATE)
        val dao = database.fileEntryDao()
        val cleanup = com.promenar.infinity.data.repository.LegacyRecycleCleanup(
            isComplete = { recycleCleanupPrefs.getBoolean("legacy_recycle_cleanup_complete", false) },
            markComplete = { recycleCleanupPrefs.edit().putBoolean("legacy_recycle_cleanup_complete", true).commit() },
            loadEntries = {
                dao.getAllWorkspaceRootsForMaintenance().flatMap { root ->
                    dao.getAllStatesByWorkspaceRootForCleanup(root.uuid).map { entry ->
                        com.promenar.infinity.data.repository.LegacyRecycleEntry(
                            entry.workspaceRootUuid, entry.uuid, entry.materializedPath, entry.inRecycleBin, entry.isDirectory,
                        )
                    }
                }
            },
            deleteEntry = { root, uuid -> workspaceRepository.permanentDelete(root, uuid) },
            deleteDirectory = { name ->
                com.promenar.infinity.data.repository.deleteLegacyRecycleDirectory(noBackupFilesDir.toPath(), name)
            },
        )
        val result = cleanup.run()
        if (!result.alreadyComplete) {
            InfinityLogger.log("[LegacyRecycleCleanup] deleted=${result.deletedRoots} failed=${result.failed}")
        }
    }

    private fun initializeAfterRecoveryOnce() {
        if (writersInitialized) return
        startupWriterSession = createStartupWriterTransaction().commit()
        writersInitialized = true
    }

    private fun createStartupWriterTransaction() = StartupWriterTransaction(
        preflight = ::prepareAndMigrateStartupWriters,
        registrations = ::startupWriterRegistrations,
    )

    private fun prepareAndMigrateStartupWriters(): PreparedStartupWriters {
        backupRuntime.requireWriterGate()
        // 第一阶段仅执行读取和不注册外部资源的对象构造。
        val settingsPrefs = getSharedPreferences("infinity_settings", MODE_PRIVATE)
        val nextHapticEnabled = settingsPrefs.getBoolean("haptic_enabled", true)
        val lastModelToLoad = localInferenceRuntimeGate.startupModelPath(
            localModelsEnabled = settingsPrefs.getBoolean("local_models_enabled", false),
            autoLoadEnabled = settingsPrefs.getBoolean("local_auto_load", false),
            persistedModelPath = prefs.getString("last_local_model", null),
        )
        val inferenceEngine = if (localInferenceRuntimeGate.isAvailable) localInferenceEngine else null
        val processObserver = inferenceEngine?.let { engine ->
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    engine.mainSlot.value.modelPath?.let { mainPath ->
                        prefs.edit().putString("last_local_model", mainPath).apply()
                    }
                }
            }
        }

        // 第二阶段是进程内幂等 migration；后续注册失败重试时不会重复执行。
        startupMigrationStage.run(StartupMigration.WORKSPACE_DIRECTORY) {
            val workSpaceDir = java.io.File(filesDir, "WorkSpace")
            check((workSpaceDir.isDirectory || workSpaceDir.mkdirs()) && workSpaceDir.isDirectory) {
                "workspace_directory_unavailable"
            }
            Unit
        }
        val providerManager = startupMigrationStage.run(StartupMigration.PROVIDER_MIGRATION_AND_INIT) {
            ProviderManager.init(this, secretStore)
        }

        // 依赖 ProviderManager 的对象只能在 migration 完成后构造。
        return PreparedStartupWriters(
            providerManager = providerManager,
            settingsPrefs = settingsPrefs,
            hapticEnabled = nextHapticEnabled,
            inferenceEngine = inferenceEngine,
            lastModelToLoad = lastModelToLoad,
            processObserver = processObserver,
        )
    }

    private fun startupWriterRegistrations(
        prepared: PreparedStartupWriters,
    ): List<StartupWriterRegistration> {
        return listOfNotNull(
            jobRegistration(prepared) {
                generationScope.launch(start = CoroutineStart.LAZY) {
                    com.promenar.infinity.background.generation.AskUserNotifications(
                        this@InfinityApplication, askUserLedger, sessionOpenIntentFactory,
                    ).observe()
                }
            },
            prepared.processObserver?.let { observer ->
                reversibleRegistration(
                    register = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) },
                    rollback = { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) },
                )
            },
            reversibleRegistration(
                register = { prefs.registerOnSharedPreferenceChangeListener(providerListener) },
                rollback = { prefs.unregisterOnSharedPreferenceChangeListener(providerListener) },
            ),
            reversibleRegistration(
                register = {
                    prepared.settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
                },
                rollback = {
                    prepared.settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
                },
            ),
            jobRegistration(prepared) {
                startupBackgroundHealthMonitor.launch(
                    scope = startupScope,
                    task = StartupBackgroundTask.PROVIDER_CONFIGURATION,
                    start = CoroutineStart.LAZY,
                ) {
                    startupBackgroundHealthMonitor.collectResilient(
                        task = StartupBackgroundTask.PROVIDER_CONFIGURATION,
                        events = prepared.providerManager.configurationChanges,
                    ) {
                        _providerConfigurationVersion.value += 1
                        rebuildEmbeddingClient()
                        rebuildRerankClient()
                        resetVectorizationQueue()
                        _unifiedLlmClient = null
                    }
                }
            },
            prepared.lastModelToLoad?.let { modelPath ->
                jobRegistration(prepared) {
                    startupBackgroundHealthMonitor.launch(
                        scope = startupScope,
                        task = StartupBackgroundTask.LOCAL_MODEL_AUTO_LOAD,
                        start = CoroutineStart.LAZY,
                    ) {
                        checkNotNull(prepared.inferenceEngine) { "本地推理启动计划与运行时能力不一致" }
                            .loadModel(SlotType.MAIN, modelPath).getOrThrow()
                    }
                }
            },
            reversibleRegistration(
                register = { hapticEnabled = prepared.hapticEnabled },
                rollback = { hapticEnabled = true },
            ),
        )
    }

    private fun reversibleRegistration(
        register: () -> Unit,
        rollback: () -> Unit,
    ): StartupWriterRegistration {
        var attempted = false
        return StartupWriterRegistration(
            register = {
                attempted = true
                register()
            },
            rollback = {
                if (attempted) {
                    attempted = false
                    rollback()
                }
            },
        )
    }

    private fun jobRegistration(
        prepared: PreparedStartupWriters,
        create: () -> Job,
    ): StartupWriterRegistration {
        var job: Job? = null
        return reversibleRegistration(
            register = {
                create().also { created ->
                    job = created
                    prepared.jobs += created
                    check(created.start()) { "startup_job_not_started" }
                }
            },
            rollback = {
                job?.cancel()
                prepared.jobs.remove(job)
                job = null
            },
        )
    }

    private data class PreparedStartupWriters(
        val providerManager: ProviderManager,
        val settingsPrefs: SharedPreferences,
        val hapticEnabled: Boolean,
        val inferenceEngine: LocalInferenceEngine?,
        val lastModelToLoad: String?,
        val processObserver: DefaultLifecycleObserver?,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    /** 结算失败只记录日志、不阻断启动，引导停在原步骤。 */
    private suspend fun settleOnboardingRestoreAtStartup(runtime: BackupRuntime) {
        try {
            val store = com.promenar.infinity.onboarding.OnboardingStateStore(this)
            val txId = store.stagedRestoreTxId ?: return
            // 以数据库里的恢复完成回执判定；提交后、结算前进程中断时，下次启动仍能认出这次恢复。
            com.promenar.infinity.onboarding.settleOnboardingRestore(
                store = store,
                committed = runtime.hasCompletedRestore(txId),
                persistLanguage = { com.promenar.infinity.util.LocaleController.persistLanguage(this, it) },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("OnboardingRestoreSettlement", failure)
        }
    }

    /** 仅供测试应用替换 AndroidKeyStore/真实文件系统依赖；生产始终使用安全 runtime。 */
    protected open fun createBackupRuntime(): BackupRuntime =
        BackupRuntime.createAndroid(this, database, secretStore)

    internal open fun createDualDatabaseRecoveryBootstrap(): DualDatabaseRecoveryBootstrap {
        val databaseDirectory = requireNotNull(getDatabasePath("infinity_v2.db").parentFile).toPath()
        val noBackupDirectory = requireNotNull(androidx.core.content.ContextCompat.getNoBackupFilesDir(this)).toPath()
        val journal = FileDualDatabaseRecoveryJournal(
            noBackupDirectory,
            AndroidRestoreJournalAuthenticator("infinity.dual.database.recovery.hmac.v1"),
        )
        return DualDatabaseRecoveryBootstrap(
            lockDirectory = noBackupDirectory,
            sourceProbe = AndroidDualDatabaseSourceProbe(this),
            journal = journal,
            executorProvider = {
                val snapshotStore = RecoverySnapshotStore(
                    allowedSourceRoots = setOf(databaseDirectory, filesDir.toPath()),
                    maxFiles = 200_000,
                    maxTotalBytes = 16L * 1024L * 1024L * 1024L,
                    trustedAppDataRoot = dataDir.toPath(),
                )
                AndroidDualDatabaseRecoveryExecutor(this, journal, snapshotStore)
            },
        )
    }

    private var _embeddingClient: EmbeddingClient? = null
    val embeddingClient: EmbeddingClient
        get() = _embeddingClient ?: buildEmbeddingClient().also { _embeddingClient = it }

    private fun modelRouteError(reason: com.promenar.infinity.data.remote.ProviderResolutionError): String =
        getString(R.string.model_request_route_failed, reason.name)

    private fun resolveAuxiliaryModel(modelId: String, allowLocal: Boolean = false): com.promenar.infinity.data.remote.ResolvedProviderModel {
        val route = providerRequestRouter.requireModelRoute(modelId, ::modelRouteError)
        return if (allowLocal && route.config.protocolType == ProtocolType.Local) route
        else route.requireOpenAiModelRoute(::modelRouteError)
    }

    private fun buildEmbeddingClient(): EmbeddingClient = EmbeddingClient(
        baseUrl = "",
        apiKey = "",
        clientResolver = { http -> buildCurrentEmbeddingClient(http) },
    )

    private val libraryAskEmbeddingClient by lazy {
        EmbeddingClient(baseUrl = "", apiKey = "",
            clientResolver = { http -> buildCurrentEmbeddingClient(http, allowLocalFallback = false) })
    }

    private fun buildCurrentEmbeddingClient(httpClient: io.ktor.client.HttpClient, allowLocalFallback: Boolean = true): EmbeddingClient {
        val manager = ProviderManager.getInstance()
        val presetModel = manager.embeddingModelId.value
        val manualBase = prefs.getString("embedding_base_url", "").orEmpty()
        val manualModel = prefs.getString("embedding_model", "").orEmpty()
        // 预设选择拥有完整路由；历史手动配置只在未选预设时作为独立配置使用。
        val routeModel = presetModel.ifBlank { manualModel.takeIf { id -> manager.providerModels.value.any { it.id == id } }.orEmpty() }
        return EmbeddingClient(
            baseUrl = if (routeModel.isBlank()) manualBase else "",
            apiKey = if (routeModel.isBlank()) readSecret(SecretCatalog.embeddingApiKey) else "",
            model = if (routeModel.isBlank()) manualModel else "",
            httpClient = httpClient,
            localEngine = localInferenceRuntimeGate.createIfAvailable { localInferenceEngine },
            allowLocalFallback = allowLocalFallback,
            modelRouteResolver = if (routeModel.isNotBlank()) {
                { resolveAuxiliaryModel(routeModel, allowLocal = true) }
            } else null,
        )
    }

    fun rebuildEmbeddingClient() {
        _embeddingClient = buildEmbeddingClient()
        rebuildMemoryManager()
        rebuildGraphExtractor()
        _vectorRepository = null
        _imageService = null
        _microGraphExtractor = null
        _kgProvider = null
    }

    private var _rerankClient: RerankClient? = null
    val rerankClient: RerankClient
        get() = _rerankClient ?: buildRerankClient().also { _rerankClient = it }

    private fun buildRerankClient(): RerankClient = RerankClient(
        baseUrl = "",
        apiKey = "",
        modelId = "",
        maxPerCall = ragConfigPersistence.loadFullConfig().rerankMaxPerCall,
        modelRouteResolver = { resolveAuxiliaryModel(ProviderManager.getInstance().rerankModelId.value) },
    )

    fun rebuildRerankClient() {
        _rerankClient = buildRerankClient()
        rebuildMemoryManager()
    }

    // 写入方（ProviderManager）可能持锁 apply()，回调推迟到下一条主线程消息，避免重建组件时与后台反向加锁死锁。
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val providerListener = com.promenar.infinity.utils.DeferredPreferenceChangeListener({ mainHandler.post(it) }) { key ->
        if (key == "base_url" || key == "embedding_base_url") {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            resetVectorizationQueue()
        }
        if (key == "model") {
            rebuildEmbeddingClient()
            resetVectorizationQueue()
        }
    }

    private val settingsListener = com.promenar.infinity.utils.DeferredPreferenceChangeListener({ mainHandler.post(it) }) { key ->
        if (key == "preset_embedding_model" || key == "all_models" || key == "enabled_models") {
            rebuildEmbeddingClient()
            resetVectorizationQueue()
        }
        if (key == "preset_rerank_model" || key == "all_models") {
            rebuildRerankClient()
        }
        // 非敏感额外提供商地址变化仍由 prefs 监听；凭证变化走 ProviderManager.configurationChanges。
        if (key?.startsWith("extra_provider_") == true && key.endsWith("_base_url")) {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            resetVectorizationQueue()
        }
    }

    private var _imageService: ImageService? = null
    val imageService: ImageService
        get() = _imageService ?: ImageService(
            context = this,
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            textSplitter = textSplitter
        ).also { _imageService = it }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao(),
            kgEvidenceDao = database.kgEvidenceDao(),
        )
    }

    val graphStore: GraphStore by lazy {
        GraphStore(database)
    }

    val keywordSearcher: KeywordSearcher by lazy {
        KeywordSearcher(vectorDao = database.vectorDao())
    }

    /** RAG 配置持久化读取器，与 RagViewModel 共用同一个 SharedPreferences */
    private val ragConfigPersistence: RagConfigPersistence by lazy {
        RagConfigPersistence(getSharedPreferences("rag_settings", MODE_PRIVATE))
    }

    private var _memoryManager: MemoryManager? = null
    val memoryManager: MemoryManager
        get() = _memoryManager ?: MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = keywordSearcher,
            graphStore = graphStore,
            embeddingClient = embeddingClient,
            rerankClient = rerankClient,
            queryRewriter = QueryRewriter(
                llmProvider.protocol,
                modelRouteResolver = { selected ->
                    providerRequestRouter.requireModelRoute(
                        com.promenar.infinity.data.rag.resolveQueryRewriteModel(selected,
                            getSharedPreferences("infinity_settings", MODE_PRIVATE).getString("preset_summary_model", null),
                            ProviderManager.getInstance().getMainConfiguredModelId()).orEmpty(),
                        ::modelRouteError,
                    )
                },
                protocolFactory = ::createRoutedModelProtocol,
            ),
            ragConfig = ragConfigPersistence.loadFullConfig()  // P0: 从用户保存的配置读取，不再硬编码默认值
        ).also { _memoryManager = it }

    fun rebuildMemoryManager() {
        _memoryManager = null
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    private val kgExtractorLock = Any()

    private fun kgExtractionSettings(
        config: com.promenar.infinity.data.rag.RagConfiguration = ragConfigPersistence.loadFullConfig(),
    ) = com.promenar.infinity.data.rag.KgExtractionSettings.resolve(
        config = config,
        summaryModel = getSharedPreferences("infinity_settings", MODE_PRIVATE).getString("preset_summary_model", null),
        mainModel = getSavedProviderConfig()?.model?.takeIf { it.isNotBlank() }
            ?.let { com.promenar.infinity.data.remote.stableModelId("default", it) },
    )

    private fun createKgRequestClient() = com.promenar.infinity.data.rag.KgRequestClient(
        router = providerRequestRouter,
        usageStore = kgUsageStore,
        defaultMaxOutputTokens = ragConfigPersistence.loadFullConfig().kgRequestMaxOutputTokens.coerceIn(128, 16384),
        protocolFactory = ::createRoutedModelProtocol,
    )

    private fun createRoutedModelProtocol(resolved: com.promenar.infinity.data.remote.ResolvedProviderModel): com.promenar.infinity.data.remote.protocol.LlmProtocol {
        val config = resolved.config
        if (config.protocolType == ProtocolType.Local) {
            return generationLocalProviders.create(resolved.remoteModelId).protocol
        }
        return com.promenar.infinity.data.remote.protocol.ProtocolFactory.create(
            type = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = resolved.remoteModelId,
            serviceAccountJson = config.serviceAccountJson,
            projectId = config.projectId,
            location = config.location,
            localEngine = if (config.protocolType == ProtocolType.Local) localInferenceEngine else null,
        )
    }

    private var _microGraphExtractor: MicroGraphExtractor? = null
    val microGraphExtractor: MicroGraphExtractor
        get() = synchronized(kgExtractorLock) {
            _microGraphExtractor ?: run {
                val settings = kgExtractionSettings()
                MicroGraphExtractor(
                    requestClient = createKgRequestClient(),
                    graphStore = graphStore,
                    jitCacheDao = database.kgJitCacheDao(),
                    modelId = settings.modelId,
                    systemPrompt = settings.systemPrompt,
                    executionGate = sessionExecutionGate,
                ).also { _microGraphExtractor = it }
            }
        }

    private var _kgProvider: KgProvider? = null
    val kgProvider: KgProvider
        get() = synchronized(kgExtractorLock) {
            _kgProvider ?: com.promenar.infinity.data.rag.PersistentGraphKgProvider(
                graphStore, MicroGraphKgAdapter { microGraphExtractor },
            ) { ragConfigPersistence.loadFullConfig() }.also { _kgProvider = it }
        }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient, secretStore)
    }

    private var _graphExtractor: com.promenar.infinity.data.rag.GraphExtractor? = null
    val graphExtractor: com.promenar.infinity.data.rag.GraphExtractor
        get() = synchronized(kgExtractorLock) {
            _graphExtractor ?: run {
                val config = ragConfigPersistence.loadFullConfig()
                val settings = kgExtractionSettings(config)
                com.promenar.infinity.data.rag.GraphExtractor(
                    requestClient = createKgRequestClient(),
                    graphStore = graphStore,
                    modelId = settings.modelId,
                    systemPrompt = settings.systemPrompt,
                    chunkSize = config.docChunkSize.coerceAtLeast(400),
                    chunkOverlap = config.chunkOverlap.coerceAtMost(200),
                    timeoutMs = config.kgExtractionTimeoutSeconds.coerceIn(5, 300) * 1000L,
                    budget = com.promenar.infinity.data.rag.KgExtractionBudget.from(config),
                ).also { _graphExtractor = it }
            }
        }

    fun rebuildGraphExtractor() {
        synchronized(kgExtractorLock) {
            _graphExtractor = null
            _microGraphExtractor?.close()
            _microGraphExtractor = null
            _kgProvider = null
        }
        resetVectorizationQueue()
    }

    private val vectorizationQueueResource = com.promenar.infinity.utils.SynchronizedResettableResource<com.promenar.infinity.data.rag.VectorizationQueue>(
        dispose = com.promenar.infinity.data.rag.VectorizationQueue::shutdown,
    )

    private fun resetVectorizationQueue() {
        val handle = vectorizationQueueResource.beginReset() ?: return
        startupScope.launch(Dispatchers.IO) {
            try {
                handle.value.shutdownForReplacement()
            } catch (failure: Exception) {
                InfinityLogger.logError("VectorizationQueue.handoff", failure)
            } finally {
                vectorizationQueueResource.completeReset(handle)
            }
            vectorizationQueue.resumeInterruptedTasks().exceptionOrNull()?.let { failure ->
                InfinityLogger.logError("VectorizationQueue.resumeAfterHandoff", failure)
            }
        }
    }
    val documentIndexService: com.promenar.infinity.data.rag.DocumentIndexService
        get() = createDocumentIndexService(ragConfigPersistence.loadFullConfig())

    private fun createDocumentIndexService(
        config: com.promenar.infinity.data.rag.RagConfiguration,
    ): com.promenar.infinity.data.rag.DocumentIndexService =
        com.promenar.infinity.data.rag.RoomDocumentIndexService(
            database = database,
            executionGate = sessionExecutionGate,
            onDeleted = graphExtractor::clearCheckpoints,
            candidateBuilder = com.promenar.infinity.data.rag.WorkspaceDocumentIndexCandidateBuilder(
                fileEntryDao = database.fileEntryDao(),
                embeddingClient = embeddingClient,
                ragConfig = config,
                graphCandidateBuilder = com.promenar.infinity.data.rag.GraphExtractorKnowledgeGraphCandidateBuilder(
                    graphExtractor,
                ),
            ),
        )

    /**
     * 当前向量化队列。替换进行中时会阻塞调用线程直到旧队列停止，只供后台线程使用；
     * 主线程请用 [awaitVectorizationQueue] 取队列、用 [vectorizationQueueState] 读状态。
     */
    val vectorizationQueue: com.promenar.infinity.data.rag.VectorizationQueue
        get() = vectorizationQueueResource.getOrCreate(::createVectorizationQueue)

    /** 在 IO 线程取得当前向量化队列，替换进行中时挂起等待而不阻塞调用线程。 */
    suspend fun awaitVectorizationQueue(): com.promenar.infinity.data.rag.VectorizationQueue =
        vectorizationQueueResource.awaitOrCreate(Dispatchers.IO, ::createVectorizationQueue)

    private val _vectorizationQueueState = kotlinx.coroutines.flow.MutableStateFlow(
        com.promenar.infinity.data.rag.VectorizationQueue.QueueState(emptyList(), null, false),
    )
    private val vectorizationQueueStateLock = Any()
    private var vectorizationQueueStateForwarding: kotlinx.coroutines.Job? = null

    /** 当前向量化队列的状态，队列被替换后自动跟随新实例；读取不会阻塞，也不会触发队列创建。 */
    val vectorizationQueueState: kotlinx.coroutines.flow.StateFlow<com.promenar.infinity.data.rag.VectorizationQueue.QueueState> =
        _vectorizationQueueState.asStateFlow()

    @Suppress("unused")
    private val vectorizationQueueStateSubscription = vectorizationQueueResource.observeInstances { queue ->
        synchronized(vectorizationQueueStateLock) {
            vectorizationQueueStateForwarding?.cancel()
            vectorizationQueueStateForwarding = startupScope.launch {
                queue.state.collect { _vectorizationQueueState.value = it }
            }
        }
    }

    private fun createVectorizationQueue(): com.promenar.infinity.data.rag.VectorizationQueue {
        val config = ragConfigPersistence.loadFullConfig()
        return com.promenar.infinity.data.rag.VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao(),
            ragConfig = config,
            fileEntryDao = database.fileEntryDao(),
            documentIndexService = createDocumentIndexService(config),
            executionGate = sessionExecutionGate,
            sessionGraph = com.promenar.infinity.data.rag.RoomSessionGraphProcessor({ database }, { graphExtractor }, sessionGraphStats),
            sessionState = com.promenar.infinity.data.rag.RoomSessionStateProcessor(
                databaseProvider = { database },
                requestClient = ::createKgRequestClient,
                embeddingClient = { embeddingClient },
                settings = { kgExtractionSettings(ragConfigPersistence.loadFullConfig().copy(kgExtractionModel = null)) },
                timeoutMs = { ragConfigPersistence.loadFullConfig().kgExtractionTimeoutSeconds.toLong() * 1000 },
                executionGate = sessionExecutionGate,
                enabled = { ragConfigPersistence.loadFullConfig().memoryStateExtractionEnabled },
            ),
        )
    }

    val sessionGraphStats: com.promenar.infinity.data.rag.SessionGraphStats by lazy {
        com.promenar.infinity.data.rag.SessionGraphStats(getSharedPreferences("session_graph_stats", MODE_PRIVATE))
    }

    private fun sessionGraphEnabled(): Boolean = ragConfigPersistence.loadFullConfig().kgSessionExtractionEnabled

    val sessionGraphScheduler: com.promenar.infinity.data.rag.SessionGraphScheduler by lazy {
        com.promenar.infinity.data.rag.RoomSessionGraphScheduler({ database }, ::sessionGraphEnabled) { awaitVectorizationQueue() }
    }

    val sessionGraphPort: com.promenar.infinity.data.rag.SessionGraphPort by lazy {
        com.promenar.infinity.data.rag.RoomSessionGraphPort({ database }, sessionGraphScheduler, sessionGraphStats, ::sessionGraphEnabled)
    }

    val sessionStateScheduler: com.promenar.infinity.data.rag.SessionStateScheduler by lazy {
        com.promenar.infinity.data.rag.RoomSessionStateScheduler(
            { database }, { ragConfigPersistence.loadFullConfig().memoryStateExtractionEnabled }, { awaitVectorizationQueue() },
        )
    }

    fun observeVectorizationQueueInstances(
        observer: (com.promenar.infinity.data.rag.VectorizationQueue) -> Unit,
    ): AutoCloseable = vectorizationQueueResource.observeInstances(observer)

    val defaultAgents: List<com.promenar.infinity.domain.model.Agent> by lazy {
        // DB 只保存与 Locale 无关的稳定 fallback；显示与编辑按 Activity 当前 Locale 叠加。
        val preset = com.promenar.infinity.data.agent.PresetAgents
        listOf(
            com.promenar.infinity.domain.model.Agent(
                id = "default",
                name = preset.fallbackName("default"),
                description = preset.fallbackDescription("default"),
                icon = "sym:all_inclusive", color = "#6366F1", isPinned = true
            ),
            com.promenar.infinity.domain.model.Agent(
                id = "coder",
                name = preset.fallbackName("coder"),
                description = preset.fallbackDescription("coder"),
                systemPrompt = com.promenar.infinity.data.agent.PresetAgentPrompts.forAgentId("coder"),
                icon = "sym:terminal", color = "#3B82F6"
            ),
            com.promenar.infinity.domain.model.Agent(
                id = "writer",
                name = preset.fallbackName("writer"),
                description = preset.fallbackDescription("writer"),
                systemPrompt = com.promenar.infinity.data.agent.PresetAgentPrompts.forAgentId("writer"),
                icon = "sym:ink_pen", color = "#10B981"
            )
        )
    }

    fun updateProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        apiKey: String,
        model: String,
        name: String? = null
    ) {
        // 委托 ProviderManager 持久化
        ProviderManager.getInstance().updateMainProvider(
            protocolType,
            baseUrl,
            apiKey.toCredentialUpdate(),
            model,
            name,
        )
        localProviderOverride = null
        _providerConfigurationVersion.value += 1
        // 同步重建 Embedding / Rerank 客户端（使用最新的 baseUrl/apiKey）
        rebuildEmbeddingClient()
        rebuildRerankClient()
        resetVectorizationQueue() // 让 VectorizationQueue 下次访问时重新捕获新的 embeddingClient
        _unifiedLlmClient = null
    }

    fun updateProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        credentialUpdate: com.promenar.infinity.data.model.CredentialUpdate,
        model: String,
        name: String? = null,
    ) {
        ProviderManager.getInstance().updateMainProvider(
            protocolType, baseUrl, credentialUpdate, model, name,
        )
        localProviderOverride = null
        _providerConfigurationVersion.value += 1
        rebuildEmbeddingClient()
        rebuildRerankClient()
        resetVectorizationQueue()
        _unifiedLlmClient = null
    }

    fun switchToLocalProvider(modelName: String = "") {
        localInferenceRuntimeGate.requireAvailable()
        localProviderOverride = modelName
        _providerConfigurationVersion.value += 1
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        return ProviderManager.getInstance().getMainProviderConfig()
    }

    private fun buildUnifiedLlmClient(): com.promenar.infinity.data.remote.UnifiedLlmClient? {
        val summary = ProviderManager.getInstance().getProviderSummary("default") ?: return null
        if (!summary.hasApiKey && !summary.hasVertexCredentials && summary.protocolType !is ProtocolType.Local) return null
        val middlewares = if (com.promenar.infinity.BuildConfig.DEBUG) {
            listOf(com.promenar.infinity.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        return com.promenar.infinity.data.remote.UnifiedLlmClient(
            providerConfigResolver = { buildUnifiedProviderConfig() },
            middlewares = middlewares,
        )
    }

    private fun buildUnifiedProviderConfig(): com.promenar.infinity.data.remote.UnifiedProviderConfig? {
        val config = getSavedProviderConfig() ?: return null
        if (config.protocolType is ProtocolType.Local && !localInferenceRuntimeGate.isAvailable) return null
        if (config.apiKey.isBlank() && config.vertexServiceAccountJson.isBlank() && config.protocolType !is ProtocolType.Local) return null
        val vertexProjectId = if (config.protocolType is ProtocolType.Google_VertexAI) {
            try {
                VertexCredentialParser.parse(config.vertexServiceAccountJson).projectId
            } catch (_: VertexCredentialException) {
                return null
            }
        } else {
            ""
        }
        return com.promenar.infinity.data.remote.UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model,
            serviceAccountJson = config.vertexServiceAccountJson,
            projectId = vertexProjectId,
            location = VERTEX_DEFAULT_LOCATION,
        )
    }

    private fun buildCredentialResolvingProvider(): LlmProvider {
        localProviderOverride?.let {
            return if (localInferenceRuntimeGate.isAvailable) {
                generationLocalProviders.create(it)
            } else {
                buildUnavailableLocalPlaceholderProvider()
            }
        }
        val summary = ProviderManager.getInstance().getProviderSummary("default")
        if (summary?.protocolType is ProtocolType.Local) {
            return if (localInferenceRuntimeGate.isAvailable) {
                generationLocalProviders.create(summary.model)
            } else {
                buildUnavailableLocalPlaceholderProvider()
            }
        }
        val protocolType = summary?.protocolType ?: ProtocolType.OpenAI_ChatCompletions
        return LlmProvider.resolving(protocolType) { buildProviderFromPrefs().protocol }
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null && (
                config.apiKey.isNotBlank() ||
                    config.vertexServiceAccountJson.isNotBlank() ||
                    config.protocolType is ProtocolType.Local
                )) {
            if (config.protocolType is ProtocolType.Local) {
                if (localInferenceRuntimeGate.isAvailable) {
                    generationLocalProviders.create(config.model)
                } else {
                    buildUnavailableLocalPlaceholderProvider()
                }
            } else if (config.protocolType is ProtocolType.Google_VertexAI) {
                val credential = VertexCredentialParser.parse(config.vertexServiceAccountJson)
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .baseUrl(config.baseUrl)
                    .serviceAccountJson(config.vertexServiceAccountJson)
                    .projectId(credential.projectId)
                    .location(VERTEX_DEFAULT_LOCATION)
                    .model(config.model)
                    .build()
            } else {
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .baseUrl(config.baseUrl)
                    .apiKey(config.apiKey)
                    .model(config.model)
                    .build()
            }
        } else {
            buildUnavailableLocalPlaceholderProvider()
        }
    }

    /** 仅供旧版依赖持有可取消的 Provider；实际生成会先由 ProviderRequestRouter 返回明确错误。 */
    private fun buildUnavailableLocalPlaceholderProvider(): LlmProvider =
        LlmProvider.builder()
            .protocolType(ProtocolType.OpenAI_ChatCompletions)
            .baseUrl("")
            .apiKey("")
            .model("")
            .build()

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8)?.trim().orEmpty()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 能力不可用的发行变体不能在此触发引擎工厂：lazy requireAvailable 会抛出导致进程崩溃。
                localInferenceRuntimeGate.createIfAvailable { localInferenceEngine }?.let { engine ->
                    CoroutineScope(Dispatchers.IO).launch {
                        engine.unloadModel(SlotType.RERANK)
                        engine.unloadModel(SlotType.EMBEDDING)
                    }
                }
            }
        }
    }

    // ProviderConfig 已迁移至 data/model/ProviderModels.kt

    /** 内置 git 的 HTTPS 凭据：主机与用户名、令牌都在 SecretStore，不进应用备份。 */
    val gitCredentialStore: com.promenar.infinity.data.git.remote.GitCredentialStore by lazy {
        com.promenar.infinity.data.git.remote.SecretGitCredentialStore(secretStore)
    }

    /** 按会话把工作区虚拟路径解析为图片、视频、音频等媒体，供会话渲染、播放与模型查看。 */
    val workspaceMediaResolver: com.promenar.infinity.domain.media.WorkspaceMediaResolver by lazy {
        com.promenar.infinity.data.media.DefaultWorkspaceMedia(
            this, sessionRepository, workspaceRepository, externalFolderManager,
            sessionWorkspaceResolver::resolve, pendingDocumentIndexCoordinator,
            userSkillsDirectory = skillCatalog.userRoot,
            modeFor = sessionRunModes::modeFor,
            linuxFiles = linux.files,
            directoryBackend = sessionWorkspaceResolver::backend,
        )
    }

    /** 把生图、MCP、下载等来源的媒体写入会话工作区。 */
    val workspaceMediaStore: com.promenar.infinity.domain.media.WorkspaceMediaStore by lazy {
        workspaceMediaResolver as com.promenar.infinity.domain.media.WorkspaceMediaStore
    }

    /** 媒体下载与内置 git 一样使用 tailnet 分流；逐跳策略和流量上限由下载器控制。 */
    val workspaceMediaDownloader: com.promenar.infinity.data.media.download.WorkspaceMediaDownloader by lazy {
        com.promenar.infinity.data.media.download.WorkspaceMediaDownloader(
            client = gitRemoteHttpClient,
            policy = com.promenar.infinity.domain.media.MediaFetchUrlPolicy(tailnetManager::isTailnetHost),
            tailnetConnected = { tailnetManager.state.value.phase == com.promenar.infinity.data.tailnet.TailnetPhase.RUNNING },
            networkPolicy = agentNetworkPolicy,
            tailnetRouting = tailnetManager,
        )
    }

    /** 凭据注入代理向远端转发所用的 OkHttp 客户端：tailnet 上的 git 主机经内置 Tailscale 本机代理，其它主机按系统默认选择器。 */
    val gitRemoteHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .proxySelector(tailnetManager.proxySelector)
            .proxyAuthenticator(tailnetManager.proxyAuthenticator)
            .build()
    }

    val gitHubDeviceLogin: com.promenar.infinity.data.git.remote.GitHubDeviceLogin by lazy {
        com.promenar.infinity.data.git.remote.OAuthDeviceLogin(
            BuildConfig.GITHUB_OAUTH_CLIENT_ID,
            gitRemoteHttpClient,
            com.promenar.infinity.data.git.remote.LazyGitCredentialStore { gitCredentialStore },
        )
    }

    /**
     * 内置 git 的远端认证代理（HTTPS 凭据注入 + SSH 桥），由内置执行后端按命令签发 nonce。
     * 装配可能发生在主线程：凭据存储与 SshRuntime 都延迟到首次使用（在 IO 线程）再创建。
     */
    val gitRemoteBroker: com.promenar.infinity.data.git.remote.GitRemoteBroker by lazy {
        val nonces = com.promenar.infinity.data.git.remote.GitNonceRegistry()
        val credentials = com.promenar.infinity.data.git.remote.LazyGitCredentialStore { gitCredentialStore }
        com.promenar.infinity.data.git.remote.GitRemoteBroker(
            credentials = credentials,
            nonces = nonces,
            proxy = com.promenar.infinity.data.git.remote.GitCredentialProxy(credentials, nonces, gitRemoteHttpClient),
            githubApi = com.promenar.infinity.data.git.remote.GitHubApiProxy(
                com.promenar.infinity.data.linux.LinuxGhConfig.directory(cacheDir),
                com.promenar.infinity.data.git.remote.GitHubApiForwarder(credentials, nonces, gitRemoteHttpClient),
            ),
            sshBridge = com.promenar.infinity.data.git.remote.GitSshBridge(
                nonces = nonces,
                hosts = { com.promenar.infinity.data.git.remote.currentSshHosts(sshHostStore) },
                executor = { host, command, environment, stdin, stdout, stderr, started ->
                    com.promenar.infinity.data.git.remote.sshRuntimeGitExecutor(sshRuntime)
                        .execute(host, command, environment, stdin, stdout, stderr, started)
                },
            ),
        )
    }
}
