package com.promenar.infinity

import com.promenar.infinity.ui.welcome.offersOnboardingLinux
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.InfinityNavGraph
import com.promenar.infinity.navigation.AppIntentRouter
import com.promenar.infinity.navigation.initialOnboardingDestination
import com.promenar.infinity.onboarding.OnboardingState
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.chat.ChatRoute
import com.promenar.infinity.ui.chat.ChatRouteDependencies
import com.promenar.infinity.share.core.AndroidShareIndexScheduler
import com.promenar.infinity.share.core.DurableShareInbox
import com.promenar.infinity.share.core.ShareImportTargetProvider
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.share.ui.ShareImportSheet
import com.promenar.infinity.share.ui.ShareImportViewModel
import com.promenar.infinity.share.ui.ShareImportPickerActions
import com.promenar.infinity.share.ui.ShareImportTargetSource
import com.promenar.infinity.share.core.ShareImportOptions
import com.promenar.infinity.share.ui.SharePendingBanner
import com.promenar.infinity.ui.startup.StartupGate
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.util.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.promenar.infinity.share.ui.ShareImportUiState

internal fun allowOnboardingEmptyModelsOverride(
    isDebugBuild: Boolean,
    requestedByIntent: Boolean,
): Boolean = isDebugBuild && requestedByIntent

internal fun debugChatSessionId(
    isDebugBuild: Boolean,
    requestedSessionId: String?,
): String? = requestedSessionId
    ?.trim()
    ?.takeIf { isDebugBuild && it.isNotEmpty() }

open class MainActivity : ComponentActivity() {
    /** 供真实 Activity E2E 以子类覆写依赖，生产默认不暴露全局可变测试注册表。 */
    protected open fun provideChatRouteDependencies(
        application: InfinityApplication,
    ): ChatRouteDependencies = application.createChatRouteDependencies()

    private val appIntentRouter: AppIntentRouter
        get() = (application as InfinityApplication).appIntentRouter
    private val onboardingStore by lazy {
        OnboardingStateStore(applicationContext, linuxOffered = (application as InfinityApplication).offersOnboardingLinux())
    }
    private val durableShareInbox by lazy { DurableShareInbox.get(noBackupFilesDir) }
    private val shareIntentViewModel by viewModels<ShareIntentViewModel> {
        ShareIntentViewModel.factory(durableShareInbox, applicationContext.contentResolver)
    }
    private val shareIntentQueue: ShareIntentQueue get() = shareIntentViewModel.queue
    @Volatile private var currentSessionId: String? = null
    /** 当前路由上的聊天会话；它的子代理审批在会话流内显示，全局弹窗跳过。 */
    private var visibleChatSessionId by androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile private var currentShareSubmissionId: Long? = null
    private val shareTargetProvider by lazy {
        val app = application as InfinityApplication
        ShareImportTargetProvider(
            app.database.sessionDao(),
            app.workspaceRepository,
            ensureKnowledgeRoot = app.globalKnowledgeWorkspaceProvisioner::ensureRoot,
            linuxRuntime = app.linux.runtime,
        )
    }
    private val sharedFileImporter by lazy {
        val app = application as InfinityApplication
        SharedFileImporter(
            source = durableShareInbox.contentSource(),
            workspace = app.workspaceRepository,
            indexScheduler = AndroidShareIndexScheduler(app),
            linuxFiles = app.linux.files,
            linuxReady = { app.linux.runtime.state.value is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready },
        )
    }
    private val shareImportViewModel by viewModels<ShareImportViewModel> {
        ShareImportViewModel.factory(
            queue = shareIntentQueue,
            importer = sharedFileImporter,
            targets = object : ShareImportTargetSource {
                override suspend fun options(): ShareImportOptions {
                    val session = currentSessionId?.let { appSessionId ->
                        (application as InfinityApplication).chatStore.current.sessions
                            .firstOrNull { it.id == appSessionId }
                    }
                    return shareTargetProvider.options(currentSessionId, session?.title)
                }
                override suspend fun sessions(excludeSessionId: String?) = shareTargetProvider.sessions(excludeSessionId)
                override suspend fun knowledgeFolders(parentUuid: String) = shareTargetProvider.knowledgeFolders(parentUuid)
                override suspend fun sessionDestination(sessionId: String, current: Boolean) =
                    shareTargetProvider.sessionDestination(sessionId, current)
            },
            indexQueueState = (application as InfinityApplication).vectorizationQueueState,
            retryIndex = { root, fileUuid ->
                (application as InfinityApplication).awaitVectorizationQueue().retryDocumentReferenceTask(root, fileUuid)
            },
        )
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shareIntentViewModel.stagingCoordinator.outcomes.collect { outcome ->
                    handleShareStageOutcome(outcome)
                }
            }
        }
        stageShareIntent(intent, ShareDelivery.of(savedInstanceState != null, intent?.flags ?: 0))
        routeOpenGenerationIntent(intent)
        routeOpenSshRemoteIntent(intent)
        routeOpenLabIntent(intent)
        val app = application as InfinityApplication
        setContent {
            val themePreferences by app.themePreferenceStore.state.collectAsStateWithLifecycle()
            InfinityTheme(preferences = themePreferences) {
                com.promenar.infinity.ui.chat.ScriptFileApprovalDialog(app.scriptFileApprovals) { app.chatStore.getSession(it)?.title }
                com.promenar.infinity.ui.chat.SubagentApprovalDialog(
                    broker = app.subagentApprovalBroker,
                    sessionTitle = { app.chatStore.getSession(it)?.title },
                    inlineSessionId = visibleChatSessionId,
                )
                val startupState by app.startupState.collectAsStateWithLifecycle()
                val startupBlockedSummary by app.startupBlockedSummary.collectAsStateWithLifecycle()
                val startupScope = androidx.compose.runtime.rememberCoroutineScope()
                val exportDiagnostics = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri ->
                    if (uri != null) startupScope.launch {
                        // 阻断页没有可写的业务界面，导出失败只能留给用户重试。
                        runCatching { contentResolver.openOutputStream(uri)?.use { app.exportStartupDiagnostics(it) } }
                    }
                }
                StartupGate(
                    state = startupState,
                    onRetry = app::retryStartupRecovery,
                    onRecoverBothDatabases = app::startDualDatabaseRecovery,
                    blockedDetail = startupBlockedSummary,
                    onExportDiagnostics = { exportDiagnostics.launch("infinity-diagnostics.txt") },
                ) {
                    val chatRouteDependencies = remember(app) {
                        provideChatRouteDependencies(app)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true }
                    ) {
                        val navController = rememberNavController()
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        val importState by shareImportViewModel.state.collectAsStateWithLifecycle()
                        val openGenerationRequest by appIntentRouter.pending.collectAsStateWithLifecycle()
                        val debugChatSessionId = debugChatSessionId(
                            isDebugBuild = BuildConfig.DEBUG,
                            requestedSessionId = intent.getStringExtra(
                                EXTRA_CHAT_SESSION_ID_FOR_TESTING,
                            ),
                        )
                        val startDestination = remember(onboardingStore) {
                            initialOnboardingDestination(onboardingStore.state.value.step)
                        }

                        InfinityNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            onboardingStateStore = onboardingStore,
                            onboardingModelsOverride = if (allowOnboardingEmptyModelsOverride(
                                isDebugBuild = BuildConfig.DEBUG,
                                requestedByIntent = intent.getBooleanExtra(
                                    EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING,
                                    false,
                                ),
                            )) {
                                emptyList<ModelInfo>()
                            } else {
                                null
                            },
                            forceLocalProbeFailureForTesting = allowOnboardingEmptyModelsOverride(
                                isDebugBuild = BuildConfig.DEBUG,
                                requestedByIntent = intent.getBooleanExtra(
                                    EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING,
                                    false,
                                ),
                            ),
                            chatDestination = { sessionId, onNavigateBack, onNavigateToSession, onOpenDefaultModels, onOpenKnowledgeGraph, onOpenSubagent, onOpenTailscaleSettings ->
                                ChatRoute(
                                    sessionId = sessionId,
                                    onNavigateBack = onNavigateBack,
                                    onNavigateToSession = onNavigateToSession,
                                    dependencies = chatRouteDependencies,
                                    onOpenDefaultModels = onOpenDefaultModels,
                                    onOpenKnowledgeGraph = onOpenKnowledgeGraph,
                                    onOpenSubagent = onOpenSubagent,
                                    onOpenTailscaleSettings = onOpenTailscaleSettings,
                                )
                            },
                            openGenerationRequest = openGenerationRequest,
                            onOpenGenerationConsumed = appIntentRouter::consume,
                        )
                        LaunchedEffect(debugChatSessionId) {
                            debugChatSessionId?.let { sessionId ->
                                navController.navigate(NavDestinations.chatHero(sessionId)) {
                                    popUpTo(startDestination) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                        // 视觉实验室深链：启动就绪后直接打开版本页。
                        val labRequested by openLabRequest.collectAsStateWithLifecycle()
                        LaunchedEffect(labRequested, startupState) {
                            val request = labRequested ?: return@LaunchedEffect
                            if (startupState != BackupStartupState.Ready) return@LaunchedEffect
                            openLabRequest.value = null
                            navController.navigate(NavDestinations.labVersion(request.versionId, request.record)) { launchSingleTop = true }
                        }
                        // SSH 连接通知的“打开远程”：启动就绪后打开 SSH 设置页（活动连接与终端入口）。
                        val sshRemoteRequested by openSshRemoteRequest.collectAsStateWithLifecycle()
                        LaunchedEffect(sshRemoteRequested, startupState) {
                            if (!sshRemoteRequested || startupState != BackupStartupState.Ready) return@LaunchedEffect
                            openSshRemoteRequest.value = false
                            navController.navigate(NavDestinations.SSH_CONFIG) { launchSingleTop = true }
                        }
                        LaunchedEffect(startupState, backStackEntry) {
                            currentSessionId = backStackEntry?.arguments?.getString("sessionId")
                            app.visibleSessionId = currentSessionId
                            // 只有聊天页把审批放进会话流；子代理查看页、命令输出页仍需弹窗。
                            visibleChatSessionId = currentSessionId?.takeIf {
                                backStackEntry?.destination?.route == com.promenar.infinity.navigation.NavDestinations.CHAT_HERO
                            }
                            if (startupState == BackupStartupState.Ready) shareImportViewModel.presentNext()
                        }
                        val skillRestoreNotice by app.skillRestoreNotice.collectAsStateWithLifecycle()
                        skillRestoreNotice?.let { summary ->
                            com.promenar.infinity.ui.skills.SkillRestoreNoticeDialog(summary, app::consumeSkillRestoreNotice)
                        }
                        val restoreNotAppliedNotice by app.restoreNotAppliedNotice.collectAsStateWithLifecycle()
                        if (restoreNotAppliedNotice) {
                            com.promenar.infinity.ui.startup.RestoreNotAppliedDialog(app::consumeRestoreNotAppliedNotice)
                        }
                        ShareImportSheet(
                            state = importState,
                            onSelectTarget = shareImportViewModel::selectTarget,
                            onImport = shareImportViewModel::importAll,
                            onRetry = shareImportViewModel::retryLastFailure,
                            onClose = { shareImportViewModel.postpone() },
                            onCancel = shareImportViewModel::cancelConfirmed,
                            pickers = ShareImportPickerActions(
                                onOpenSessionPicker = shareImportViewModel::openSessionPicker,
                                onPickSession = shareImportViewModel::pickSession,
                                onDismissSessionPicker = shareImportViewModel::dismissSessionPicker,
                                onOpenFolderPicker = shareImportViewModel::openFolderPicker,
                                onEnterFolder = shareImportViewModel::enterFolder,
                                onFolderUp = shareImportViewModel::folderUp,
                                onConfirmFolder = shareImportViewModel::confirmFolder,
                                onDismissFolderPicker = shareImportViewModel::dismissFolderPicker,
                                onPickLinuxDirectory = shareImportViewModel::pickLinuxDirectory,
                                // 先把这次分享留到稍后处理，再去安装 Linux；装好后从待处理横幅回来。
                                onInstallLinux = {
                                    shareImportViewModel.postpone()
                                    navController.navigate(NavDestinations.LINUX_ENVIRONMENT) { launchSingleTop = true }
                                },
                            ),
                        )
                        com.promenar.infinity.ui.ssh.SshUserPromptHost(app.sshUserPrompts)
                        SharePendingBanner(
                            pendingCount = importState.pendingCount,
                            visible = !importState.visible && importState.pendingCount > 0,
                            onOpen = shareImportViewModel::presentNext,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ShareIntentQueue.isShareIntent(intent)) {
            setIntent(intent)
            stageShareIntent(intent, ShareDelivery.of(restoredInstanceState = false, intentFlags = intent.flags))
        } else if (!routeOpenSshRemoteIntent(intent) && !routeOpenLabIntent(intent) && !routeOpenGenerationIntent(intent) && currentShareSubmissionId == null) {
            setIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            shareIntentQueue.refreshDurableCount()
            consumeShareIntentsIfReady()
        }
    }

    @VisibleForTesting
    internal fun onboardingStateForTesting(): OnboardingState = onboardingStore.state.value

    @VisibleForTesting
    internal fun onboardingStateStoreForTesting(): OnboardingStateStore = onboardingStore

    private fun stageShareIntent(candidate: Intent?, delivery: ShareDelivery) {
        if (!ShareIntentQueue.isShareIntent(candidate)) return
        currentShareSubmissionId = shareIntentViewModel.stagingCoordinator.submit(candidate!!, delivery)
    }

    private fun handleShareStageOutcome(outcome: ShareStageOutcome) {
        val isCurrent = currentShareSubmissionId == outcome.submissionId
        when (outcome.result) {
            ShareEnqueueResult.Accepted, ShareEnqueueResult.Duplicate -> {
                try {
                    if (isCurrent) {
                        setIntent(cleanMainIntent())
                        currentShareSubmissionId = null
                    }
                    // 系统重放保持静默；用户新的分享与待处理请求重复时必须告知，避免看似被丢弃。
                    if (outcome.result == ShareEnqueueResult.Duplicate && outcome.delivery == ShareDelivery.Fresh) {
                        showShareFeedback(R.string.share_intent_already_pending)
                    }
                    consumeShareIntentsIfReady()
                } finally {
                    shareIntentViewModel.stagingCoordinator.acknowledge(outcome.submissionId)
                }
            }
            ShareEnqueueResult.RejectedInvalid -> {
                try {
                    revokeShareReadGrants(outcome.candidate)
                    if (isCurrent) {
                        setIntent(cleanMainIntent())
                        currentShareSubmissionId = null
                    }
                    showShareFeedback(R.string.share_intent_invalid)
                } finally {
                    shareIntentViewModel.stagingCoordinator.acknowledge(outcome.submissionId)
                }
            }
            ShareEnqueueResult.RejectedCapacity -> showShareFeedback(R.string.share_intent_queue_full)
        }
    }

    private fun cleanMainIntent() = Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
    }

    /** SSH 连接通知点按与“打开远程”按钮；消费后把 Activity intent 换成普通启动。 */
    private val openSshRemoteRequest = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun routeOpenSshRemoteIntent(candidate: Intent?): Boolean {
        if (candidate?.action != com.promenar.infinity.background.ssh.SshConnectionService.ACTION_OPEN_REMOTE) return false
        setIntent(cleanMainIntent())
        openSshRemoteRequest.value = true
        return true
    }

    /** 视觉实验室深链 [EXTRA_LAB_VERSION]：只在开发者模式开启且版本存在时生效，否则当作普通启动。 */
    private val openLabRequest = kotlinx.coroutines.flow.MutableStateFlow<com.promenar.infinity.ui.lab.LabDeepLink?>(null)

    private fun routeOpenLabIntent(candidate: Intent?): Boolean {
        val versionId = candidate?.getStringExtra(EXTRA_LAB_VERSION) ?: return false
        val enabled = com.promenar.infinity.ui.developer.DeveloperModeStore.get(this).enabled.value
        val request = com.promenar.infinity.ui.lab.LabDeepLink.resolve(
            versionId = versionId,
            record = candidate.getBooleanExtra(EXTRA_LAB_RECORD, false),
            developerModeEnabled = enabled,
        )
        setIntent(cleanMainIntent())
        if (request != null) openLabRequest.value = request
        return true
    }

    private fun routeOpenGenerationIntent(candidate: Intent?): Boolean {
        if (candidate?.action == AppIntentRouter.ACTION_OPEN_SESSION) {
            val sessionIntent = Intent(candidate)
            setIntent(cleanMainIntent())
            lifecycleScope.launch {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { appIntentRouter.offer(sessionIntent) }
            }
            return true
        }
        if (!appIntentRouter.offer(candidate)) return false
        setIntent(cleanMainIntent())
        return true
    }

    private fun revokeShareReadGrants(candidate: Intent) {
        val uris = linkedSetOf<android.net.Uri>()
        candidate.data?.let(uris::add)
        try {
            @Suppress("DEPRECATION")
            if (candidate.action == Intent.ACTION_SEND) {
                candidate.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            } else {
                uris += candidate.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
        } catch (_: IllegalArgumentException) {
            // 无法解析 EXTRA_STREAM 时仍继续回收 data/ClipData 授权并清空 Activity intent。
        } catch (_: ClassCastException) {
            // 同上。
        }
        candidate.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(uris::add) }
        }
        uris.forEach { uri ->
            try {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // grant 可能已由发送方或系统回收。
            }
        }
    }

    private fun showShareFeedback(message: Int) {
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
    }

    private fun consumeShareIntentsIfReady() {
        val app = application as InfinityApplication
        if (app.startupState.value != BackupStartupState.Ready) return
        shareImportViewModel.presentNext()
    }

    @VisibleForTesting
    internal fun shareImportStateForTesting(): ShareImportUiState = shareImportViewModel.state.value

    companion object {
        /** 视觉实验室深链：值为版本 id，例如 `chat.workflow.ade-json`。 */
        const val EXTRA_LAB_VERSION = "com.promenar.infinity.extra.LAB_VERSION"
        /** 与 [EXTRA_LAB_VERSION] 一起传 true 时以录制模式打开。 */
        const val EXTRA_LAB_RECORD = "com.promenar.infinity.extra.LAB_RECORD"
        @VisibleForTesting
        internal const val EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING =
            "com.promenar.infinity.extra.ONBOARDING_EMPTY_MODELS_FOR_TESTING"
        @VisibleForTesting
        internal const val EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING =
            "com.promenar.infinity.extra.ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING"
        @VisibleForTesting
        internal const val EXTRA_CHAT_SESSION_ID_FOR_TESTING =
            "com.promenar.infinity.extra.CHAT_SESSION_ID_FOR_TESTING"
    }
}
