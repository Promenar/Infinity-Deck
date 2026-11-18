package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ChatRouteContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val chatSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
    )
    private val routeSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/chat/ChatRoute.kt",
    )
    private val navSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/navigation/NavGraph.kt",
    )
    private val pipelineSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/chat/PipelineBubble.kt",
    )

    @Test
    fun `route owns injected dependencies and content does not resolve application or view model`() {
        val source = routeSource.readText()
        val content = chatSource.readText().substringAfter("fun ChatScreenContent(")

        assertThat(source).contains("data class ChatRouteDependencies")
        assertThat(source).contains("fun ChatRoute(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("InfinityApplication")
        assertThat(content).doesNotContain("rememberLauncherForActivityResult")
        assertThat(content).doesNotContain("copyToClipboard(")
    }

    @Test
    fun `session settings receives the route view model instance`() {
        val source = projectRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/SessionSettingsSheet.kt",
        ).readText()

        assertThat(source).contains("chatViewModel: ChatViewModel")
        assertThat(source.substringAfter("fun SessionSettingsSheet(")).doesNotContain(
            "ChatViewModel.factory",
        )
    }

    @Test
    fun `chat destination is injectable without mutable registry`() {
        val source = navSource.readText()

        assertThat(source).contains("chatDestination: ChatDestination")
        assertThat(source).contains("chatDestination(")
        assertThat(source).contains("onNavigateToSession")
        assertThat(source).doesNotContain("ChatDestinationRegistry")
    }

    @Test
    fun `文档选择与消息分支通过 route 显式接线`() {
        val route = routeSource.readText()
        val screen = chatSource.readText()
        val pipeline = pipelineSource.readText()

        assertThat(route).contains("ActivityResultContracts.OpenMultipleDocuments()")
        assertThat(route).contains("chatViewModel.importFullContextDocuments(uris)")
        assertThat(route).contains("onBranchMessage = { messageId ->")
        assertThat(screen).contains("ChatComposerAttachments(")
        assertThat(screen).contains("onRemoveDocument = actions.onRemoveDocument")
        assertThat(screen).contains("onPickDocument = actions.onPickDocuments")
        assertThat(pipeline).contains("MessageDocumentSummary(message.userDocuments.orEmpty())")
        assertThat(pipeline).contains("stringResource(R.string.chat_action_branch)")
    }

    @Test
    fun `route离开时释放常亮标志且权限流程可跨重建`() {
        val source = routeSource.readText()

        assertThat(source).contains("DisposableEffect(activity)")
        assertThat(source).contains("activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)")
        assertThat(source).contains("permissionInFlightTaskId by rememberSaveable")
        assertThat(source).contains("NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT")
        assertThat(source).contains("notificationPermissionResultTaskId(")
    }

    @Test
    fun `overlay和图片使用可保存表示且不保存Uri对象`() {
        val source = routeSource.readText()

        assertThat(source).contains("selectedImageUriStrings by rememberSaveable")
        assertThat(source).contains("activeOverlayToken by rememberSaveable")
        assertThat(source).contains("queuedOverlayToken by rememberSaveable")
        assertThat(source).doesNotContain("selectedImageUris by rememberSaveable")
        assertThat(source).contains("draftConsumptionEpoch by chatViewModel.draftConsumptionEpoch")
        val sendBlock = source.substringAfter("onSend = { text, images ->")
            .substringBefore("onStop =")
        assertThat(sendBlock).doesNotContain("selectedImageUriStrings = emptyList()")
    }

    @Test
    fun `route统一构造显示名 map 并注入 screen state`() {
        val route = routeSource.readText()
        val screen = chatSource.readText()
        val mapConstruction = route
            .substringAfter("val modelDisplayNames = remember(")
            .substringBefore("val snackbarHostState")

        assertThat(route).contains(
            "ProviderManager.getInstance().providerModels.collectAsStateWithLifecycle()",
        )
        assertThat(mapConstruction).contains(
            "providerModels, uiState.session?.modelId, uiState.messages",
        )
        assertThat(mapConstruction).contains("resolveModelDisplayNames(")
        assertThat(mapConstruction).contains("sessionModelId = uiState.session?.modelId")
        assertThat(mapConstruction).contains(
            "messageModelIds = uiState.messages.map { it.modelId }",
        )
        val screenState = route.substringAfter("state = ChatScreenState(")
            .substringBefore("actions = ChatScreenActions(")
        assertThat(screenState).contains("modelDisplayNames = modelDisplayNames")
        assertThat(screen).contains("val modelDisplayNames: Map<String, String> = emptyMap()")
    }

    @Test
    fun `输入区与历史尾注只消费 route 注入的同一显示名 map`() {
        val screen = chatSource.readText()
        val pipeline = pipelineSource.readText()
        val content = screen.substringAfter("fun ChatScreenContent(")
        val pipelineFunction = pipeline.substringAfter("fun PipelineBubble(")
        val pipelineCall = content.substringAfter("PipelineBubble(")
            .substringBefore("if (compressionState.isCompressing")
        val inputModel = content.substringAfter("val modelDisplayName =")
            .substringBefore("if (selectedImageUris.isNotEmpty())")
        val pipelineLookup = pipelineFunction
            .substringAfter("val modelDisplayName = if (lastMsg.modelId.isNullOrBlank())")
            .substringBefore("AssistantActionRow(")
        val compactInputModel = inputModel.replace(Regex("\\s+"), "")
        val compactPipelineLookup = pipelineLookup.replace(Regex("\\s+"), "")

        assertThat(pipelineCall).contains("modelDisplayNames = state.modelDisplayNames")
        assertThat(compactInputModel).contains(
            "uiState.session?.modelId?.let(state.modelDisplayNames::get).orEmpty()",
        )
        assertThat(inputModel).contains("ChatInputBar(")
        assertThat(inputModel).contains("modelName = modelDisplayName")
        assertThat(pipelineFunction).contains(
            "modelDisplayNames: Map<String, String> = emptyMap()",
        )
        assertThat(compactPipelineLookup).contains("modelDisplayNames[lastMsg.modelId]")
        assertThat(compactPipelineLookup).contains(
            "?:lastMsg.modelId.substringAfter(\"::\",lastMsg.modelId)",
        )
        assertThat(content).doesNotContain("findModelSpec(")
        assertThat(content).doesNotContain("resolveModelDisplayName(")
        assertThat(pipelineFunction).doesNotContain("findModelSpec(")
        assertThat(pipelineFunction).doesNotContain("resolveModelDisplayName(")
    }

    @Test
    fun `末轮重生成免二次确认且用户消息编辑与分支通过 route 完整接线`() {
        val route = routeSource.readText()
        val screen = chatSource.readText()
        val pipeline = pipelineSource.readText()

        val regenerateBlock = route
            .substringAfter("onRegenerateMessage = { messageId ->")
            .substringBefore("onRetryLastMessage =")
        assertThat(regenerateBlock).contains("chatViewModel.countSubsequentUserTurns(messageId) > 0")
        assertThat(regenerateBlock).contains("requestOverlay(ChatOverlay.Truncate(messageId))")
        assertThat(regenerateBlock).contains("chatViewModel.regenerateMessage(messageId)")

        assertThat(route).contains("onEditUserMessage = { messageId, payload ->")
        assertThat(route).contains("chatViewModel.editAndRegenerateMessage(")
        assertThat(route).contains("onBranchWithEditMessage = { messageId, payload -> launchBranch(messageId, payload) }")
        assertThat(screen).contains("onEditUserMessage = actions.onEditUserMessage")
        assertThat(screen).contains("onBranchWithEdit = actions.onBranchWithEditMessage")
        assertThat(screen).contains("subsequentUserTurns =")

        assertThat(pipeline).contains("UiTags.CHAT_MESSAGE_EDIT")
        assertThat(pipeline).contains("UiTags.CHAT_USER_EDIT_CARD")
        assertThat(pipeline).contains("UiTags.CHAT_USER_EDIT_INPUT")
        assertThat(pipeline).contains("UiTags.CHAT_USER_EDIT_CANCEL")
        assertThat(pipeline).contains("UiTags.CHAT_USER_EDIT_BRANCH")
        assertThat(pipeline).contains("UiTags.CHAT_USER_EDIT_SUBMIT")
    }
}
