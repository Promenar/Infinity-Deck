package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 锁定目录浏览页头、外部文件夹面板与全站层级切换动效的共享约定。 */
class NavigationMotionContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `动效基准提供层级、同级与局部显隐三类过渡`() {
        val motion = source("theme/Motion.kt")

        listOf("fun sharedAxisX(", "fun sharedAxisY(", "fun fadeThrough(", "val expandIn", "val shrinkOut",
            "fun <S> AnimatedContentTransitionScope<S>.hierarchicalTransition(").forEach { api ->
            assertThat(motion).contains(api)
        }
        // 层级切换深度相同时退化为交叉淡入，不做方向性滑动。
        assertThat(motion).contains("if (from == to) InfinityMotion.fadeThrough()")
    }

    @Test
    fun `目录页头只保留一个返回按钮并由根目录回到外层`() {
        val header = source("common/FolderBreadcrumbs.kt")

        assertThat(header.split("Icons.AutoMirrored.Rounded.ArrowBack").size - 1).isEqualTo(1)
        assertThat(header).contains("onBack = if (atRoot) onExitRoot else onNavigateBack")
        assertThat(header).contains("AnimatedContent(")
        assertThat(header).doesNotContain("\"›\"")

        val knowledge = source("workspace/KnowledgeWorkspaces.kt")
        val directory = source("chat/components/DirectoryWorkspaceBrowser.kt")
        assertThat(knowledge).contains("onExitRoot = onBackToList")
        for ((name, text) in listOf("KnowledgeWorkspaces" to knowledge, "DirectoryWorkspaceBrowser" to directory)) {
            // 文件页与目录页都用统一页头，不再叠加单独的“返回”文字按钮。
            assertWithMessage(name).that(text).doesNotContain("workspace_kb_back")
            assertWithMessage(name).that(text).doesNotContain("resource_explorer_directory_back")
            assertWithMessage(name).that(text).contains("InfinityNavigationHeader(")
        }
    }

    @Test
    fun `所有目录浏览都按层级方向切换内容`() {
        listOf(
            "common/FolderEntriesList.kt",
            "rag/RagHomeScreen.kt",
            "chat/components/WorkspaceFolderBrowser.kt",
            "chat/components/DirectoryWorkspaceBrowser.kt",
            "chat/components/ExternalFoldersPanel.kt",
            "workspace/KnowledgeWorkspaces.kt",
            "workspace/WorkspacePicker.kt",
        ).forEach { path ->
            val text = source(path)
            assertWithMessage(path).that(text).contains("AnimatedContent(")
            assertWithMessage(path).that(text).contains("hierarchicalTransition {")
        }
    }

    @Test
    fun `外部文件夹在同一个全高面板内逐页切换而不是叠放对话框`() {
        val panel = source("chat/components/ExternalFoldersPanel.kt")

        assertThat(panel).doesNotContain("AlertDialog")
        assertThat(panel).contains("height = InfinitySheetHeight.Full")
        assertThat(panel).contains("ExternalMountPage.Manager -> ExternalMountManagerContent(")
        assertThat(panel).contains("is ExternalMountPage.Form -> ExternalMountFormContent(")
        assertThat(panel).contains("ExternalMountPage.Recycle -> ExternalMountRecyclePage(")
        // 管理页按已挂载、添加方式、回收区分组，刷新在页头。
        val manager = panel.substringAfter("internal fun ExternalMountManagerContent(").substringBefore("private fun MountRefreshAction(")
        assertThat(manager).contains("R.string.mount_section_mounted")
        assertThat(manager).contains("R.string.agent_panels_mount_add")
        assertThat(manager).contains("R.string.mount_recycle_title")
        assertThat(manager).contains("MountRefreshAction(")
        assertThat(panel).contains("BackHandler(")
    }

    @Test
    fun `高频路径的状态切换带过渡`() {
        val tabs = source("MainTabScaffold.kt")
        // 标签按顺序整页滑动：底栏横滑、导航轨纵滑，内容区裁剪。
        assertThat(tabs).contains("InfinityMotion.tabSlide(tabSlideDirection(initialState, targetState), vertical = tabSlideVertical(navigationRail))")
        assertThat(tabs).contains("modifier = Modifier.fillMaxSize().clipToBounds(),")
        assertThat(tabs).contains("animateColorAsState(")
        assertThat(tabs).contains("expandHorizontally(")

        val rag = source("rag/RagHomeScreen.kt")
        assertThat(rag).contains("label = \"rag_portal_tab\"")
        assertThat(rag).contains("label = \"rag_documents_search\"")

        val pipeline = source("chat/PipelineBubble.kt")
        assertThat(pipeline).contains("visible = internalExpanded && reasoning.isNotBlank()")
        assertThat(pipeline).contains("AnimatedVisibility(visible = isExpanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut)")
        assertThat(pipeline).contains("visible = !isGenerating")
        assertThat(pipeline).doesNotContain("if (isGenerating) return")

        val chat = source("chat/ChatScreen.kt")
        assertThat(chat).contains("label = \"chat_send_icon\"")
        assertThat(chat).contains("visible = runDockVisible,")

        val files = source("chat/components/FilesPanel.kt")
        assertThat(files).contains("label = \"files_panel_state\"")
        assertThat(files).contains("Modifier.animateItem()")
    }

    @Test
    fun `调用方关闭面板时先播放下滑退场`() {
        val sheet = source("common/InfinityBottomSheet.kt")

        assertThat(sheet).contains("sheetState.hide()")
        assertThat(sheet).contains("if (!show && !composed) return")
    }

    @Test
    fun `页面路由统一使用视差推入`() {
        fun read(path: String) = Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)
        val nav = read("app/src/main/java/com/promenar/infinity/navigation/NavGraph.kt")
        val settingsPane = source("settings/SettingsTwoPane.kt")
        val hubPane = source("hub/HubThreePane.kt")

        // 主导航、设置右列与首页会话列都经同一个页面级 NavHost，目的地都有不透明外框。
        for ((name, text) in listOf("NavGraph" to nav, "SettingsTwoPane" to settingsPane, "HubThreePane" to hubPane)) {
            assertWithMessage(name).that(text).contains("InfinityPageNavHost(")
            assertWithMessage(name).that(Regex("(?<![A-Za-z])NavHost\\(").containsMatchIn(text)).isFalse()
            assertWithMessage(name).that(text).doesNotContain("import androidx.navigation.compose.composable")
        }
        assertThat(nav).doesNotContain("tween(300)")
        // 栏内换根页面叠化；会话列迁移进来的初始层级直接出现。
        assertThat(settingsPane).contains("isSameLevel = isPaneRoot,")
        assertThat(hubPane).contains("isSameLevel = isPaneRoot,")
        assertThat(hubPane).contains("isInstant = { entry -> entry.id in initialEntryIds },")

        val pages = read("app/src/main/java/com/promenar/infinity/navigation/PageTransitions.kt")
        assertThat(pages).contains("modifier = modifier.clipToBounds(),")
        assertThat(pages).contains(".background(background),")
        assertThat(pages).contains("propagateMinConstraints = true,")

        val motion = source("theme/Motion.kt")
        assertThat(motion).contains("const val DurationPagePush = 360")
        assertThat(motion).contains("val PageSlideEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)")
    }

    @Test
    fun `系统返回手势跟手进行视差返回`() {
        fun read(path: String) = Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)
        // 不传跟手返回的转场时，导航库在手势中改用淡入与缩小，视差与压暗都会丢失。
        val pages = read("app/src/main/java/com/promenar/infinity/navigation/PageTransitions.kt")
        assertThat(pages).contains("predictivePopEnterTransition = { pageEnterFor(predictiveBackward()) },")
        assertThat(pages).contains("predictivePopExitTransition = { pageExitFor(predictiveBackward()) },")
        // 手势进度直接定位转场，位移曲线须为线性，页面才与手指同步。
        assertThat(source("theme/Motion.kt")).contains("tween(DurationMedium, easing = LinearEasing)")
        // Android 13–15 也走 OnBackInvokedCallback，才有跟手返回；Android 16 起 targetSdk 36 默认如此。
        assertThat(read("app/src/main/AndroidManifest.xml")).contains("android:enableOnBackInvokedCallback=\"true\"")

        // BackHandler 优先取 NavigationEvent 调度器：被遮住的层必须连同它一起换成不接收返回的调度器。
        val pane = source("common/InfinityAdaptivePane.kt")
        assertThat(pane).contains("LocalNavigationEventDispatcherOwner provides if (active) it else inert")
        assertThat(pane).contains("LocalOnBackPressedDispatcherOwner provides if (active) it else inert")
        // 不走 NavHost 的整页二级页面同样跟手：返回处理须能拿到手势进度。
        assertThat(pane).contains("events.collect { event -> gesture.drag(event.progress) }")
        assertThat(pane).doesNotContain("BackHandler(onBack = onBack)")
    }

    @Test
    fun `聊天消息只淡入不做位移以免干扰跟随滚动`() {
        val chat = source("chat/ChatScreen.kt")

        assertThat(chat).contains(".animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = null)")
        // 压缩卡与审批卡同样不做位移。
        assertThat(chat.split("placementSpec = null").size - 1).isAtLeast(3)
        assertThat(chat).doesNotContain("if (panel == null && postProcessTasks.isEmpty() && retry == null) return")
    }

    @Test
    fun `运行信息收为浮岛顶部一行运行坞，回复下方不再挂运行统计`() {
        val dock = source("chat/components/RunDock.kt")
        // 运行坞读屏读完整一句；轮次与用时已在回合顶部，详细统计在统计浮卡，回复下方不再重复。
        assertThat(dock).contains("semantics(mergeDescendants = true)")
        assertThat(source("chat/components/RunDetailSheet.kt")).doesNotContain("GenerationProgress")
        assertThat(source("chat/PipelineBubble.kt")).doesNotContain("replyRunStats")
        val chat = source("chat/ChatScreen.kt")
        assertThat(chat).doesNotContain("R.string.p0_progress")
        assertThat(chat).doesNotContain("ChatGenerationProgressBar")
        assertThat(chat).doesNotContain("GenerationActivityRow(")
        assertThat(chat).doesNotContain("TaskFloatingPanel(")
        assertThat(chat).doesNotContain("StateBoardPanel(")
        assertThat(chat).contains("RunDockRow(")
        assertThat(chat).contains("RunDetailSheet(")
        // 用时取生成任务自身的开始时间；用户消息时间可能早于本次运行（定时触发、继续处理、旧会话）。
        assertThat(chat).contains("state.generationStartedAt?.takeIf { uiState.isGenerating }")
        assertThat(source("chat/ChatRoute.kt")).contains("generationStartedAt = generationProgress?.startedAt")
        // 生成中输入框仍可编辑。
        assertThat(chat).contains("enabled = true")
        // 宽窗口运行详情在浮动检视器的“运行”页签，浮岛照样显示运行坞，点按打开检视器运行页；检视器与运行详情面板共用同一份运行详情正文。
        assertThat(chat).contains("com.promenar.infinity.ui.common.LocalInfinityInspectorLayout.current")
        assertThat(chat).contains("runDockVisible = dock != null,")
        assertThat(chat).contains("actions.onSetInspectorPages(openChatPageRoot(ChatPage.Run))")
        assertThat(chat).contains("ChatInspectorCard(")
        assertThat(chat).contains("RunDetailContent(")
        assertThat(chat.split("RunDetailSheet(").size - 1).isEqualTo(1)
    }

    @Test
    fun `低频页面的标签、状态与列表增删带过渡`() {
        mapOf(
            "git/GitChangesPanel.kt" to listOf("label = \"git_panel_page\"", "label = \"git_panel_tab\"", "label = \"git_panel_title\"", "GitLoadContent("),
            "chat/components/RagDetailsSheet.kt" to listOf("label = \"rag_details_tab\""),
            // 智能体运行各节已拆为独立页面，没有标签切换；改为检查高级参数的展开过渡。
            "common/InfinitySettingsExpandableRow.kt" to listOf("AnimatedVisibility(", "label = \"settings_expandable_chevron\""),
            "welcome/OnboardingScreen.kt" to listOf("label = \"onboarding_intro\"", "label = \"onboarding_step\""),
            "rag/DocEditorScreen.kt" to listOf("label = \"doc_editor_state\"", "exit = ExitTransition.None"),
            "rag/KnowledgeGraphScreen.kt" to listOf("label = \"kg_canvas_phase\""),
            "rag/KnowledgeGraphPageParts.kt" to listOf("label = \"kg_search_phase\""),
            "chat/components/RecycleBinPanel.kt" to listOf("Modifier.animateItem()", "label = \"recycle_operation_icon\""),
            "chat/ResourceExplorerSheet.kt" to listOf("label = \"resource_explorer_recycle_phase\""),
            "common/UnifiedPromptEditor.kt" to listOf("label = \"prompt_editor_view\""),
            "settings/ProviderModelsScreen.kt" to listOf("Box(Modifier.animateItem())", "label = \"provider_models_sync_icon\""),
            "settings/ProviderListScreen.kt" to listOf(".animateItem()", ".animateContentSize("),
            "settings/ProviderFormScreen.kt" to listOf("animatedFormItem(\"cloud-section\")"),
            "rag/RagFolderScreen.kt" to listOf("Box(Modifier.animateItem())"),
        ).forEach { (path, markers) ->
            val text = source(path)
            markers.forEach { marker -> assertWithMessage(path).that(text).contains(marker) }
        }
        // 文档编辑器切换窗格时不得重建编辑面板：两个窗格仍各自只出现一次。
        val editor = source("rag/DocEditorScreen.kt")
        assertThat(editor.split("EditorPane(\n").size - editor.split("fun EditorPane(\n").size).isEqualTo(1)
    }
}
