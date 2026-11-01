package com.promenar.infinity.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.onboarding.OnboardingLanguageSelectionCoordinator
import com.promenar.infinity.onboarding.isSuccessfulOnboardingAssistant
import com.promenar.infinity.ui.chat.ChatRoute
import com.promenar.infinity.ui.hub.AgentEditScreen
import com.promenar.infinity.ui.rag.DocEditorScreen
import com.promenar.infinity.ui.rag.KnowledgeBaseSettingsScreen
import com.promenar.infinity.ui.rag.KnowledgeDebugSettingsScreen
import com.promenar.infinity.ui.rag.KnowledgeGraphSettingsScreen
import com.promenar.infinity.ui.rag.KnowledgeGraphScreen
import com.promenar.infinity.ui.rag.RagDebugScreen
import com.promenar.infinity.ui.rag.RagFolderScreen
import com.promenar.infinity.ui.settings.BackupSettingsScreen
import com.promenar.infinity.ui.settings.LocalModelsScreen
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.settings.ProviderFormScreen
import com.promenar.infinity.ui.settings.ProviderModelsScreen
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.settings.ProviderListScreen
import com.promenar.infinity.ui.settings.DefaultModelsScreen
import com.promenar.infinity.ui.settings.AgentRuntimeSection
import com.promenar.infinity.ui.settings.SkillsScreen
import com.promenar.infinity.ui.settings.ThemeScreen
import com.promenar.infinity.ui.settings.TokenUsageScreen
import com.promenar.infinity.ui.welcome.OnboardingRoute
import com.promenar.infinity.ui.welcome.offersOnboardingLinux
import com.promenar.infinity.util.LocaleController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object NavDestinations {
    const val WELCOME = "welcome"
    const val MAIN_TAB_SCAFFOLD = "main_tab_scaffold"
    const val CHAT_HERO = "chat_hero/{sessionId}"
    const val PROVIDER_FORM = "provider_form?providerId={providerId}"
    const val PROVIDER_MODELS = "provider_models/{providerId}"
    const val AGENT_EDIT = "agent_edit/{agentId}"
    const val DOC_EDITOR = "doc_editor/{workspaceRootUuid}/{docId}"
    const val KNOWLEDGE_GRAPH = "knowledge_graph"
    const val KNOWLEDGE_GRAPH_ROUTE = "knowledge_graph?query={query}"
    const val SUBAGENT_VIEWER_ROUTE = "subagent/{sessionId}/{stepId}/{index}"
    const val RAG_ADVANCED_KG = "rag_advanced_kg"
    const val RAG_GLOBAL_CONFIG = "rag_global_config"
    const val LONG_TERM_MEMORY = "long_term_memory"
    const val RAG_FOLDER = "rag_folder/{folderId}"
    const val RAG_DEBUG = "rag_debug"
    const val RAG_VECTOR_STATS = "rag_vector_stats"
    const val TOKEN_USAGE = "token_usage"
    const val SKILLS_CONFIG = "skills_config"
    const val AGENT_BUDGET = "agent_budget"
    const val BUILTIN_TOOLS = "builtin_tools"
    const val MCP_SERVERS = "mcp_servers"
    const val SSH_CONFIG = "ssh_config"
    const val TERMUX_CONFIG = "termux_config"
    const val LINUX_ENVIRONMENT = "linux_environment"
    const val LINUX_PACKAGES = "linux_packages"
    const val LINUX_MIRRORS = "linux_mirrors"
    const val LINUX_TERMINAL = "linux_terminal?session={session}&dir={dir}&cmd={cmd}"
    const val LINUX_FILES = "linux_files?path={path}"
    const val LINUX_FILE = "linux_file?path={path}"
    const val COMMAND_OUTPUT = "command_output/{sessionId}/{messageId}/{toolCallId}"
    const val NETWORK_ACCESS = "network_access"
    const val TAILSCALE_CONFIG = "tailscale_config"
    const val SSH_TERMINAL = "ssh_terminal/{alias}"
    const val GIT_CREDENTIALS = "git_credentials"
    const val BACKGROUND_ACTIVITY = "background_activity"
    const val THEME_CONFIG = "theme_config"
    const val BACKUP_SETTINGS = "backup_settings"
    const val LOCAL_MODELS = "local_models"
    const val PROVIDER_LIST = "provider_list"
    const val DEFAULT_MODELS = "default_models"
    /** 开发者页：版本号连点解锁后才在设置首页出现。 */
    const val DEVELOPER = "developer"
    /** 视觉实验室目录，path 为目录节点路径（如 chat/conversation），空为根目录。 */
    const val VISUAL_LAB = "visual_lab?path={path}"
    /** 视觉实验室的一个版本：整页渲染，不进设置右列。 */
    const val LAB_VERSION = "lab_version/{versionId}"

    fun chatHero(sessionId: String) = "chat_hero/$sessionId"
    fun agentEdit(agentId: String) = "agent_edit/$agentId"
    fun providerForm(providerId: String? = null) =
        if (providerId != null) "provider_form?providerId=$providerId" else "provider_form"
    fun providerModels(providerId: String) = "provider_models/$providerId"
    fun docEditor(workspaceRootUuid: String, docId: String) = "doc_editor/$workspaceRootUuid/$docId"
    @Suppress("UNUSED_PARAMETER")
    fun ragFolder(folderId: String) = "rag_folder/$folderId"
    fun knowledgeGraph(query: String) = "knowledge_graph?query=${android.net.Uri.encode(query)}"
    fun linuxTerminal(sessionId: String? = null, directory: String? = null, command: String? = null) =
        "linux_terminal" + query("session" to sessionId, "dir" to directory, "cmd" to command)
    fun linuxFiles(path: String? = null) = "linux_files" + query("path" to path)
    fun linuxFile(path: String) = "linux_file" + query("path" to path)
    fun commandOutput(sessionId: String, messageId: String, toolCallId: String) =
        "command_output/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(messageId)}/${android.net.Uri.encode(toolCallId)}"
    private fun query(vararg params: Pair<String, String?>) = params.filter { it.second != null }
        .joinToString("&", prefix = "?") { (key, value) -> "$key=${android.net.Uri.encode(value)}" }
        .takeIf { it != "?" }.orEmpty()
    fun visualLab(path: String = "") = if (path.isEmpty()) "visual_lab" else "visual_lab?path=${android.net.Uri.encode(path)}"
    fun labVersion(versionId: String) = "lab_version/${android.net.Uri.encode(versionId)}"
    fun subagentViewer(sessionId: String, stepId: String, index: Int) =
        "subagent/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(stepId)}/$index"
}

typealias ChatDestination = @Composable (
    sessionId: String,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit,
    onOpenDefaultModels: () -> Unit,
    onOpenKnowledgeGraph: (String) -> Unit,
    onOpenSubagent: (sessionId: String, stepId: String, index: Int) -> Unit,
    onOpenTailscaleSettings: () -> Unit,
) -> Unit

@Composable
fun InfinityNavGraph(
    navController: NavHostController,
    startDestination: String = NavDestinations.WELCOME,
    onboardingStateStore: OnboardingStateStore? = null,
    onboardingModelsOverride: List<ModelInfo>? = null,
    forceLocalProbeFailureForTesting: Boolean = false,
    openGenerationRequest: OpenGenerationRequest? = null,
    onOpenGenerationConsumed: (String) -> Boolean = { false },
    chatDestination: ChatDestination = { sessionId, onNavigateBack, onNavigateToSession, onOpenDefaultModels, onOpenKnowledgeGraph, onOpenSubagent, onOpenTailscaleSettings ->
        ChatRoute(
            sessionId = sessionId,
            onNavigateBack = onNavigateBack,
            onNavigateToSession = onNavigateToSession,
            onOpenDefaultModels = onOpenDefaultModels,
            onOpenKnowledgeGraph = onOpenKnowledgeGraph,
            onOpenSubagent = onOpenSubagent,
            onOpenTailscaleSettings = onOpenTailscaleSettings,
        )
    },
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val onboardingStore = remember(app, onboardingStateStore) {
        onboardingStateStore ?: OnboardingStateStore(app, linuxOffered = app.offersOnboardingLinux())
    }
    val onboardingState by onboardingStore.state.collectAsState()
    val languageSelection = remember(context, onboardingStore) {
        OnboardingLanguageSelectionCoordinator(
            stateStore = onboardingStore,
            currentLanguage = { LocaleController.getSavedLanguage(context) },
            persistLanguage = { LocaleController.persistLanguage(context, it) },
            applyLanguage = { LocaleController.applyLanguage(context, it) },
        )
    }

    LaunchedEffect(onboardingState.step, onboardingState.sessionId) {
        val sessionId = onboardingState.sessionId
        if (onboardingState.step == OnboardingStep.FIRST_CHAT && sessionId != null) {
            app.chatStore.state.collect { chatState ->
                val assistantSucceeded = chatState.sessions
                    .firstOrNull { it.id == sessionId }
                    ?.messages
                    .orEmpty()
                    .any(::isSuccessfulOnboardingAssistant)
                if (assistantSucceeded) {
                    onboardingStore.recordFirstChatResult(sessionId, assistantSucceeded = true)
                }
            }
        }
    }

    LaunchedEffect(openGenerationRequest, onboardingState.step) {
        val request = openGenerationRequest ?: return@LaunchedEffect
        if (onboardingState.step != OnboardingStep.COMPLETED) return@LaunchedEffect
        navController.navigate(NavDestinations.chatHero(request.sessionId)) {
            launchSingleTop = true
        }
        onOpenGenerationConsumed(request.requestId)
    }

    val mainTabs = com.promenar.infinity.ui.rememberMainTabsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val settingsTwoPane = com.promenar.infinity.ui.settings.settingsTwoPaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口变宽：设置标签打开的整页设置子页收回到设置右列，主导航回到主标签页。
    LaunchedEffect(settingsTwoPane) {
        if (!settingsTwoPane || mainTabs.selectedTab != com.promenar.infinity.ui.AppTab.SETTINGS) return@LaunchedEffect
        val pages = NavControllerSettingsBackStack(navController).takeSettingsPagesAboveMainTabs() ?: return@LaunchedEffect
        mainTabs.settingsStack = pages
        mainTabs.settingsLastRoot = pages.first()
    }
    val hubThreePane = com.promenar.infinity.ui.hub.hubThreePaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口变宽（展开折叠屏）：对话页打开的整页会话收进首页会话列，主导航回到主标签页。
    LaunchedEffect(hubThreePane) {
        if (!hubThreePane || mainTabs.selectedTab != com.promenar.infinity.ui.AppTab.CHAT) return@LaunchedEffect
        val pages = NavControllerSettingsBackStack(navController).takeHubPagesAboveMainTabs() ?: return@LaunchedEffect
        mainTabs.restoreHubStack(pages)
    }
    // 首页会话列：会话与子代理查看页在列内切换，其余页面（默认模型、知识图谱、Linux 等）仍由主导航整页打开。
    val hubPaneGraph: NavGraphBuilder.(HubPaneNavigator) -> Unit = remember(navController, chatDestination, app) {
        { navigator -> hubPaneDestinations(navigator, navController, chatDestination, app) }
    }

    // 页面层级导航统一用视差推入：新页整页从右缘推入，旧页左移并压暗；返回反向。内屏同样按全宽推入。
    InfinityPageNavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composablePage(NavDestinations.WELCOME) {
            OnboardingRoute(
                store = onboardingStore,
                languageSelection = languageSelection,
                navController = navController,
                modelsOverride = onboardingModelsOverride,
                forceLocalProbeFailureForTesting = forceLocalProbeFailureForTesting,
            )
        }

        composablePage(NavDestinations.MAIN_TAB_SCAFFOLD) {
            com.promenar.infinity.ui.MainTabScaffold(
                onNavigateToSecondary = { route ->
                    navController.navigate(route)
                },
                onNavigateToAgentEdit = { agentId ->
                    navController.navigate(NavDestinations.agentEdit(agentId))
                },
                onNavigateToChat = { sessionId ->
                    navController.navigate(NavDestinations.chatHero(sessionId))
                },
                state = mainTabs,
                settingsPaneGraph = { navigator -> settingsDestinations(navigator) },
                hubPaneGraph = hubPaneGraph,
            )
        }

        composablePage(
            route = NavDestinations.CHAT_HERO,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatHeroDestination(
                sessionId = sessionId,
                navController = navController,
                chatDestination = chatDestination,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { branchedSessionId ->
                    navController.navigate(NavDestinations.chatHero(branchedSessionId)) {
                        launchSingleTop = true
                    }
                },
                onOpenSubagent = { owner, stepId, index -> navController.navigate(NavDestinations.subagentViewer(owner, stepId, index)) },
            )
        }

        composablePage(
            route = NavDestinations.SUBAGENT_VIEWER_ROUTE,
            arguments = SubagentViewerArguments,
        ) { backStackEntry ->
            SubagentViewerDestination(app, backStackEntry, onBack = { navController.popBackStack() })
        }

        composablePage(
            route = NavDestinations.AGENT_EDIT,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentEditScreen(
                agentId = agentId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSkills = { navController.navigate(NavDestinations.SKILLS_CONFIG) { launchSingleTop = true } },
                onNavigateToKnowledgeSettings = {
                    navController.navigate(NavDestinations.RAG_GLOBAL_CONFIG) { launchSingleTop = true }
                },
            )
        }

        composablePage(
            route = NavDestinations.DOC_EDITOR,
            arguments = listOf(
                navArgument("workspaceRootUuid") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val workspaceRootUuid = backStackEntry.arguments?.getString("workspaceRootUuid") ?: ""
            val docId = backStackEntry.arguments?.getString("docId") ?: ""
            DocEditorScreen(
                workspaceRootUuid = workspaceRootUuid,
                docId = docId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        settingsDestinations(SettingsNavigator(navController))

        composablePage(
            route = NavDestinations.LAB_VERSION,
            arguments = listOf(navArgument("versionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            com.promenar.infinity.ui.lab.LabVersionScreen(
                versionId = backStackEntry.arguments?.getString("versionId").orEmpty(),
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composablePage(
            route = NavDestinations.KNOWLEDGE_GRAPH_ROUTE,
            arguments = listOf(navArgument("query") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            KnowledgeGraphScreen(
                initialQuery = backStackEntry.arguments?.getString("query"),
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { sessionId -> navController.navigate(NavDestinations.chatHero(sessionId)) },
            )
        }

        composablePage(
            route = NavDestinations.RAG_FOLDER,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: ""
            RagFolderScreen(
                folderId = folderId,
                onNavigateToDocEditor = { rootUuid, docId ->
                    navController.navigate(NavDestinations.docEditor(rootUuid, docId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composablePage(
            NavDestinations.LINUX_TERMINAL,
            arguments = listOf("session", "dir", "cmd").map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val command = args?.getString("cmd")
            val request = args?.getString("session")?.let {
                com.promenar.infinity.ui.linux.terminal.LinuxTerminalRequest(com.promenar.infinity.domain.linux.LinuxTerminalLocation.SessionWorkspace(it), command)
            } ?: args?.getString("dir")?.let {
                com.promenar.infinity.ui.linux.terminal.LinuxTerminalRequest(com.promenar.infinity.domain.linux.LinuxTerminalLocation.Directory(it), command)
            }
            com.promenar.infinity.ui.linux.terminal.LinuxTerminalScreen(
                request = request,
                onBack = { navController.popBackStack() },
                onOpenEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) },
            )
        }
        composablePage(
            NavDestinations.LINUX_FILES,
            arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            com.promenar.infinity.ui.linux.files.LinuxFilesScreen(
                onBack = { navController.popBackStack() },
                onOpenTerminal = { directory, command -> navController.navigate(NavDestinations.linuxTerminal(directory = directory, command = command)) },
                onOpenEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) },
                initialPath = backStackEntry.arguments?.getString("path") ?: com.promenar.infinity.ui.linux.files.LINUX_HOME_PATH,
                onOpenFile = { navController.navigate(NavDestinations.linuxFile(it)) },
            )
        }
        composablePage(
            NavDestinations.LINUX_FILE,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            com.promenar.infinity.ui.linux.files.LinuxFileScreen(
                path = backStackEntry.arguments?.getString("path") ?: com.promenar.infinity.ui.linux.files.LINUX_HOME_PATH,
                onBack = { navController.popBackStack() },
                onOpenTerminal = { directory, command -> navController.navigate(NavDestinations.linuxTerminal(directory = directory, command = command)) },
            )
        }
        composablePage(
            NavDestinations.COMMAND_OUTPUT,
            arguments = listOf("sessionId", "messageId", "toolCallId").map { navArgument(it) { type = NavType.StringType } },
        ) { backStackEntry ->
            val args = requireNotNull(backStackEntry.arguments)
            com.promenar.infinity.ui.linux.output.CommandOutputScreen(
                source = com.promenar.infinity.ui.linux.output.CommandOutputSource(
                    sessionId = requireNotNull(args.getString("sessionId")),
                    messageId = requireNotNull(args.getString("messageId")),
                    toolCallId = requireNotNull(args.getString("toolCallId")),
                ),
                onBack = { navController.popBackStack() },
                onRerunInTerminal = { command, sessionId -> navController.navigate(NavDestinations.linuxTerminal(sessionId = sessionId, command = command)) },
            )
        }

        composablePage(NavDestinations.SSH_TERMINAL) { entry ->
            val alias = entry.arguments?.getString("alias").orEmpty()
            com.promenar.infinity.ui.ssh.SshTerminalScreen(alias, onBack = { navController.popBackStack() })
        }
    }
}


/** 会话页：整页与首页会话列共用，差别只在返回、打开别的会话与子代理查看页的去向。 */
@Composable
private fun ChatHeroDestination(
    sessionId: String,
    navController: NavHostController,
    chatDestination: ChatDestination,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit,
    onOpenSubagent: (sessionId: String, stepId: String, index: Int) -> Unit,
) {
    val linuxNavigation = remember(navController, sessionId) {
        com.promenar.infinity.ui.linux.LinuxNavigation(
            openEnvironment = { navController.navigate(NavDestinations.LINUX_ENVIRONMENT) { launchSingleTop = true } },
            openCommandOutput = { messageId, toolCallId ->
                navController.navigate(NavDestinations.commandOutput(sessionId, messageId, toolCallId))
            },
        )
    }
    val sessionSettingsNavigation = remember(navController) {
        com.promenar.infinity.ui.chat.SessionSettingsNavigation(
            openSkills = { navController.navigate(NavDestinations.SKILLS_CONFIG) { launchSingleTop = true } },
            openNetworkAccess = { navController.navigate(NavDestinations.NETWORK_ACCESS) { launchSingleTop = true } },
            openAgentEdit = { agentId -> navController.navigate(NavDestinations.agentEdit(agentId)) { launchSingleTop = true } },
            openRemoteSettings = { link ->
                val route = when (link) {
                    com.promenar.infinity.ui.remote.RemoteSettingsLink.LINUX -> NavDestinations.LINUX_ENVIRONMENT
                    com.promenar.infinity.ui.remote.RemoteSettingsLink.TERMUX -> NavDestinations.TERMUX_CONFIG
                    com.promenar.infinity.ui.remote.RemoteSettingsLink.BACKGROUND -> NavDestinations.BACKGROUND_ACTIVITY
                }
                navController.navigate(route) { launchSingleTop = true }
            },
        )
    }
    CompositionLocalProvider(
        com.promenar.infinity.ui.linux.LocalLinuxNavigation provides linuxNavigation,
        com.promenar.infinity.ui.chat.LocalSessionSettingsNavigation provides sessionSettingsNavigation,
    ) {
        chatDestination(
            sessionId,
            onNavigateBack,
            { branchedSessionId -> if (branchedSessionId != sessionId) onNavigateToSession(branchedSessionId) },
            {
                navController.navigate(NavDestinations.DEFAULT_MODELS) {
                    launchSingleTop = true
                }
            },
            { query -> navController.navigate(NavDestinations.knowledgeGraph(query)) },
            onOpenSubagent,
            { navController.navigate(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
        )
    }
}

private val SubagentViewerArguments = listOf(
    navArgument("sessionId") { type = NavType.StringType },
    navArgument("stepId") { type = NavType.StringType },
    navArgument("index") { type = NavType.IntType },
)

@Composable
private fun SubagentViewerDestination(app: InfinityApplication, backStackEntry: androidx.navigation.NavBackStackEntry, onBack: () -> Unit) {
    val owner = backStackEntry.arguments?.getString("sessionId").orEmpty()
    val stepId = backStackEntry.arguments?.getString("stepId").orEmpty()
    val index = backStackEntry.arguments?.getInt("index") ?: 0
    val initial = remember(owner, stepId) { app.subagentRunStore.snapshot(owner, stepId) }
    // 已结束的记录要从磁盘读取，读完之前不显示“记录不存在”。
    var loaded by remember(owner, stepId) { mutableStateOf(initial != null) }
    val batch by remember(owner, stepId) { app.subagentRunStore.observe(owner, stepId).onEach { loaded = true } }
        .collectAsState(initial = initial)
    val providerModels by com.promenar.infinity.data.manager.ProviderManager.getInstance().providerModels.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    CompositionLocalProvider(com.promenar.infinity.ui.chat.LocalToolLiveOutput provides app.toolLiveOutputStore) {
        com.promenar.infinity.ui.chat.SubagentViewerScreen(
            batch = batch,
            loading = !loaded,
            index = index,
            modelName = { modelId ->
                com.promenar.infinity.ui.chat.resolveModelDisplayNames(modelId, emptyList(), providerModels)[modelId] ?: modelId
            },
            onBack = onBack,
            onStop = { run -> scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { app.subagentControls.stop(owner, run.id) } } },
        )
    }
}

/** 首页会话列（内屏三列右列）的页面：会话打开别的会话换掉整列，子代理查看页压在会话之上。 */
private fun NavGraphBuilder.hubPaneDestinations(
    navigator: HubPaneNavigator,
    navController: NavHostController,
    chatDestination: ChatDestination,
    app: InfinityApplication,
) {
    composablePage(
        route = NavDestinations.CHAT_HERO,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) { backStackEntry ->
        ChatHeroDestination(
            sessionId = backStackEntry.arguments?.getString("sessionId") ?: "",
            navController = navController,
            chatDestination = chatDestination,
            // 会话列的会话没有返回键；删除会话等收尾回到新建入口。
            onNavigateBack = navigator::clear,
            onNavigateToSession = navigator::openSession,
            onOpenSubagent = { owner, stepId, index -> navigator.open(NavDestinations.subagentViewer(owner, stepId, index)) },
        )
    }
    composablePage(route = NavDestinations.SUBAGENT_VIEWER_ROUTE, arguments = SubagentViewerArguments) { backStackEntry ->
        SubagentViewerDestination(app, backStackEntry, onBack = navigator::back)
    }
}

/**
 * 设置子页的注册：主导航（整页）与宽屏设置右列（[SettingsNavigator] 带 outside）共用。
 * 页面间跳转经 [navigator]，右列中打开终端、文件、知识图谱与会话等整页内容时交给主导航。
 */
/** “智能体运行”的四个页面：执行轮次与预算、内置工具、技能包、MCP 服务器。 */
private fun NavGraphBuilder.agentRuntimeDestination(navigator: SettingsNavigator, route: String, section: AgentRuntimeSection) {
    composablePage(route) { entry ->
        navigator.Page(entry) {
            SkillsScreen(onNavigateBack = { navigator.back() }, section = section)
        }
    }
}

internal fun NavGraphBuilder.settingsDestinations(navigator: SettingsNavigator) {
    composablePage(NavDestinations.RAG_ADVANCED_KG) { entry ->
        navigator.Page(entry) {
            KnowledgeGraphSettingsScreen(
                onNavigateBack = { navigator.back() },
                onNavigateToGraph = { navigator.open(NavDestinations.KNOWLEDGE_GRAPH) },
                onNavigateToDebug = { navigator.open(NavDestinations.RAG_DEBUG) },
            )
        }
    }

    composablePage(NavDestinations.RAG_GLOBAL_CONFIG) { entry ->
        navigator.Page(entry) {
            KnowledgeBaseSettingsScreen(
                onNavigateBack = { navigator.back() },
                onOpenDefaultModels = { navigator.open(NavDestinations.DEFAULT_MODELS) },
            )
        }
    }

    composablePage(NavDestinations.LONG_TERM_MEMORY) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.memory.LongTermMemoryScreen(onNavigateBack = { navigator.back() })
        }
    }

    composablePage(NavDestinations.RAG_DEBUG) { entry ->
        navigator.Page(entry) {
            KnowledgeDebugSettingsScreen(
                onNavigateBack = { navigator.back() },
                onOpenVectorStats = { navigator.open(NavDestinations.RAG_VECTOR_STATS) },
            )
        }
    }

    composablePage(NavDestinations.RAG_VECTOR_STATS) { entry ->
        navigator.Page(entry) {
            RagDebugScreen(onNavigateBack = { navigator.back() })
        }
    }

    composablePage(
        route = NavDestinations.PROVIDER_FORM,
        arguments = listOf(
            navArgument("providerId") {
                type = NavType.StringType
                nullable = true
            }
        )
    ) { backStackEntry ->
        navigator.Page(backStackEntry) {
            val app = LocalContext.current.applicationContext as InfinityApplication
            val providerId = backStackEntry.arguments?.getString("providerId")
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            ProviderFormScreen(
                providerId = providerId,
                onNavigateBack = { navigator.back() },
                onOpenTailscaleSettings = { navigator.open(NavDestinations.TAILSCALE_CONFIG) { launchSingleTop = true } },
                onNavigateToModels = {
                    val pid = providerId ?: ""
                    navigator.open(NavDestinations.providerModels(pid)) {
                        popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                        // 从模型页“编辑提供商”进入时，保存后回到原模型页而不是再叠一层。
                        launchSingleTop = true
                    }
                },
                onNavigateToLocalModels = {
                    if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
                        navigator.open(NavDestinations.LOCAL_MODELS) {
                            popUpTo(NavDestinations.PROVIDER_FORM) { inclusive = true }
                        }
                    }
                },
                onSave = { protocolType, baseUrl, credential, model, name ->
                    saveProviderConfiguration(app, providerId, protocolType, baseUrl, credential, model, name)
                },
            )
        }
    }

    composablePage(
        route = NavDestinations.PROVIDER_MODELS,
        arguments = listOf(navArgument("providerId") { type = NavType.StringType })
    ) { backStackEntry ->
        navigator.Page(backStackEntry) {
            val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
            val context = LocalContext.current
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

            ProviderModelsScreen(
                providerId = providerId,
                onNavigateBack = { navigator.back() },
                onNavigateToSecondary = { route -> navigator.open(route) },
            )
        }
    }

    composablePage(NavDestinations.TOKEN_USAGE) { entry ->
        navigator.Page(entry) {
            TokenUsageScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    agentRuntimeDestination(navigator, NavDestinations.AGENT_BUDGET, AgentRuntimeSection.Budget)
    agentRuntimeDestination(navigator, NavDestinations.BUILTIN_TOOLS, AgentRuntimeSection.BuiltinTools)
    agentRuntimeDestination(navigator, NavDestinations.SKILLS_CONFIG, AgentRuntimeSection.SkillPacks)
    agentRuntimeDestination(navigator, NavDestinations.MCP_SERVERS, AgentRuntimeSection.Mcp)

    composablePage(NavDestinations.LINUX_ENVIRONMENT) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.linux.settings.LinuxEnvironmentScreen(
                onBack = { navigator.back() },
                onOpenTerminal = { navigator.open(NavDestinations.linuxTerminal()) },
                onOpenFiles = { navigator.open(NavDestinations.linuxFiles()) },
                onOpenPackages = { navigator.open(NavDestinations.LINUX_PACKAGES) },
                onOpenMirrors = { navigator.open(NavDestinations.LINUX_MIRRORS) },
                onOpenTermux = { navigator.open(NavDestinations.TERMUX_CONFIG) },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composablePage(NavDestinations.LINUX_PACKAGES) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.linux.settings.LinuxPackagesScreen(onBack = { navigator.back() })
        }
    }

    composablePage(NavDestinations.LINUX_MIRRORS) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.linux.settings.LinuxMirrorsScreen(onBack = { navigator.back() })
        }
    }

    composablePage(NavDestinations.NETWORK_ACCESS) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.NetworkAccessSettingsScreen(
                onBack = { navigator.back() },
                onOpenTailscale = { navigator.open(NavDestinations.TAILSCALE_CONFIG) },
            )
        }
    }

    composablePage(NavDestinations.DEVELOPER) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.developer.DeveloperRoute(
                onNavigateBack = { navigator.back() },
                onOpenVisualLab = { navigator.open(NavDestinations.visualLab()) },
            )
        }
    }

    composablePage(
        route = NavDestinations.VISUAL_LAB,
        arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.lab.VisualLabScreen(
                path = entry.arguments?.getString("path").orEmpty(),
                onNavigateBack = { navigator.back() },
                onOpenNode = { path -> navigator.open(NavDestinations.visualLab(path)) },
                onOpenVersion = { version -> navigator.open(NavDestinations.labVersion(version.id)) },
            )
        }
    }

    composablePage(NavDestinations.BACKGROUND_ACTIVITY) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.BackgroundActivitySettingsScreen(
                onBack = { navigator.back() },
                onOpenSession = { sessionId -> navigator.open(NavDestinations.chatHero(sessionId)) },
            )
        }
    }

    composablePage(NavDestinations.TAILSCALE_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.TailscaleSettingsScreen(onBack = { navigator.back() })
        }
    }

    composablePage(NavDestinations.TERMUX_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.TermuxSettingsScreen(
                onBack = { navigator.back() },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composablePage(NavDestinations.SSH_CONFIG) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.SshSettingsScreen(
                onBack = { navigator.back() },
                onOpenTerminal = { alias -> navigator.open("ssh_terminal/${android.net.Uri.encode(alias)}") },
                onOpenTailscale = { navigator.open(NavDestinations.TAILSCALE_CONFIG) },
            )
        }
    }

    composablePage(NavDestinations.GIT_CREDENTIALS) { entry ->
        navigator.Page(entry) {
            com.promenar.infinity.ui.settings.GitCredentialSettingsScreen(
                onBack = { navigator.back() },
                onOpenSsh = { navigator.open(NavDestinations.SSH_CONFIG) },
            )
        }
    }

    composablePage(NavDestinations.THEME_CONFIG) { entry ->
        navigator.Page(entry) {
            ThemeScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composablePage(NavDestinations.PROVIDER_LIST) { entry ->
        navigator.Page(entry) {
            ProviderListScreen(
                onNavigateToSecondary = { route -> navigator.open(route) },
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composablePage(NavDestinations.DEFAULT_MODELS) { entry ->
        navigator.Page(entry) {
            DefaultModelsScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    composablePage(NavDestinations.BACKUP_SETTINGS) { entry ->
        navigator.Page(entry) {
            BackupSettingsScreen(
                onNavigateBack = { navigator.back() }
            )
        }
    }

    if (BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
        composablePage(NavDestinations.LOCAL_MODELS) { entry ->
            navigator.Page(entry) {
                LocalModelsScreen(
                    onNavigateBack = { navigator.back() }
                )
            }
        }
    }
}
