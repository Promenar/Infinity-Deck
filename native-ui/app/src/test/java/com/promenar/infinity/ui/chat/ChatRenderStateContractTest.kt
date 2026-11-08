package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.generation.GenerationFailureSurface
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.ui.testing.UiTags
import java.io.File
import org.junit.Test

class ChatRenderStateContractTest {
    @Test
    fun `输入栏必须消费统一活动状态而不是把终态当作生成中`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val inputBar = source.substringAfter("fun ChatInputBar(")
            .substringBefore("private fun GenerationStatusButton(")

        assertThat(source).contains("isGenerating = uiState.isGenerating")
        assertThat(inputBar).contains("isGenerating: Boolean")
        assertThat(inputBar).contains("effectiveStatus")
        assertThat(inputBar).doesNotContain("status != GenerationStatus.IDLE")
    }

    @Test
    fun `会话输入区使用语义化 M3 控件和实测高度`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val tags = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/testing/UiTags.kt",
        ).readText()
        assertThat(source).doesNotContain("CompactInputChip(")
        assertThat(source).doesNotContain("visualHeight = 34.dp")
        assertThat(source).doesNotContain("InfinityGlassCard(")
        assertThat(source).doesNotContain("InfinityColors.GlassSurface")
        assertThat(source).doesNotContain("InfinityColors.GlassBorder")
        assertThat(source).contains("onSizeChanged")
        assertThat(source).contains("chatComposerInsets(")
        assertThat(tags).contains("chat_composer")
        assertThat(tags).contains("chat_thinking_trace")
    }

    @Test
    fun `模型与上下文动作位于同一条四十 dp 工具行`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val composer = source.substringAfter("fun ChatInputBar(")
            .substringBefore("private fun GenerationStatusButton(")
        assertThat(source).doesNotContain("ChatInputTopBar(")
        assertThat(composer).contains(".height(40.dp)")
        assertThat(composer).contains("ChatModelSelector(")
        assertThat(composer).contains("TokenIndicator(")
        assertThat(composer).contains("maxLines = 6")
        assertThat(composer).contains("MaterialTheme.typography.bodyLarge")
    }

    @Test
    fun `上下文用量面板贴着圆环以单层弹出层展示`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val tokenIndicator = source
            .substringAfter("private fun TokenIndicator(")
            .substringBefore("internal fun ContextUsagePanel(")

        assertThat(tokenIndicator).contains("InfinityPopover(")
        assertThat(tokenIndicator).contains("infinityPopoverAnchor(anchor)")
        assertThat(tokenIndicator).contains("consumeRecentDismiss()")
        assertThat(tokenIndicator).doesNotContain("DropdownMenu(")
        assertThat(tokenIndicator).doesNotContain("Surface(")
        assertThat(tokenIndicator).contains("ContextUsagePanel(")
    }

    @Test
    fun `未选模型提示使用 M3 语义容器和标准圆角层级`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val modelHint = source
            .substringAfter("// ── 模型未选择提示气泡 ──")
            .substringBefore("visible = isUserScrolledAway")

        assertThat(modelHint).contains("shape = MaterialTheme.shapes.medium")
        assertThat(modelHint).contains(
            "color = MaterialTheme.colorScheme.primaryContainer",
        )
        assertThat(modelHint).contains("color = MaterialTheme.colorScheme.onPrimaryContainer")
        assertThat(modelHint).contains("shadowElevation = InfinityElevation.Level3")
        assertThat(modelHint).doesNotContain("RoundedCornerShape(")
        assertThat(modelHint).doesNotContain("shadowElevation = 8.dp")
    }

    @Test
    fun `生成中追尾应随输入区实测高度变化重新校正且尊重用户上滚`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val streamingFollowEffect = source
            .substringAfter("// 生成中跟随当前 AI item 的尾部")
            .substringBefore("// IME 键盘避让")

        assertThat(streamingFollowEffect).contains("composerHeightPx,")
        assertThat(streamingFollowEffect).contains(
            "if (uiState.isGenerating && autoFollowEnabled)",
        )
        assertThat(streamingFollowEffect).doesNotContain("delay(16)")
        assertThat(streamingFollowEffect).doesNotContain("withFrameNanos { }")
        // 几何跟随先让出调度再滚动：避免测量中途同步恢复收集方时滚动，同时不引入整帧延迟。
        assertThat(streamingFollowEffect).contains("kotlinx.coroutines.yield()\n            scrollItemAboveComposer(followTargetIndex)")
        assertThat(streamingFollowEffect).contains("scrollToStreamingTail()")
    }

    @Test
    fun `会话列表按消息角色安排间距并限制宽屏阅读宽度`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val conversationList = source.substringAfter("LazyColumn(")
            .substringBefore("// ── Skeleton")
        assertThat(conversationList).contains("InfinitySpacing.ChatMaxWidth")
        assertThat(conversationList).contains("InfinitySpacing.ChatHorizontal")
        assertThat(conversationList).contains("chatItemSpacing(")
        assertThat(conversationList.substringBefore(") {\n")).doesNotContain("spacedBy(")
        assertThat(source).doesNotContain("initialStreamingIndex")
    }

    @Test
    fun `five representative states have stable render anchors`() {
        assertThat(
            listOf(
                UiTags.CHAT_STATE_EMPTY,
                UiTags.CHAT_STATE_LOADING,
                UiTags.CHAT_STATE_GENERATING,
                UiTags.CHAT_STATE_APPROVAL,
                UiTags.CHAT_STATE_ERROR,
            ),
        ).containsExactly(
            "chat_state_empty",
            "chat_state_loading",
            "chat_state_generating",
            "chat_state_approval",
            "chat_state_error",
        ).inOrder()
    }

    @Test
    fun `状态锚点附着在真实会话表面且加载骨架可独立观测`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt",
        ).readText()
        val conversationSurface = source.substringAfter("LazyColumn(").substringBefore(") {")

        assertThat(conversationSurface).contains(".testTag(renderStateTag)")
        assertThat(source).doesNotContain("Spacer(Modifier.size(1.dp).testTag(renderStateTag))")
        assertThat(source).contains(".testTag(UiTags.CHAT_LOADING_SKELETON)")
    }

    @Test
    fun `已有消息的稳定态不会被误标为空态`() {
        val state = ChatUiState(
            messages = listOf(Message("m1", MessageRole.ASSISTANT, "ready")),
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_READY)
        assertThat(chatRenderStateTag(ChatUiState())).isEqualTo(UiTags.CHAT_STATE_EMPTY)
    }

    @Test
    fun `结构化服务错误映射应输出错误锚点`() {
        val state = ChatUiState(
            generationNotice = GenerationFailureNotice.from(
                GenerationFailure.of(GenerationFailureCode.SERVER),
            ),
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }

    @Test
    fun `会话内已持久化错误气泡不应重复触发顶部错误提示`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val routeSource = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatRoute.kt",
        ).readText()

        assertThat(routeSource).contains("shouldShowGenerationFailureSnackbar(uiState)")
        assertThat(routeSource).doesNotContain(
            "val message = generationErrorMessage ?: uiState.error ?: return@LaunchedEffect",
        )
        val viewModelSource = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatViewModel.kt",
        ).readText()
        assertThat(viewModelSource).doesNotContain("task.error?.let")

        assertThat(
            shouldShowGenerationFailureSnackbar(
                ChatUiState(
                    generationNotice = GenerationFailureNotice.from(GenerationFailure.unknown(null)),
                    generationFailureSurface = GenerationFailureSurface.INLINE_MESSAGE,
                ),
            ),
        ).isFalse()
        assertThat(
            shouldShowGenerationFailureSnackbar(
                ChatUiState(
                    generationNotice = GenerationFailureNotice.from(GenerationFailure.unknown(null)),
                    generationFailureSurface = GenerationFailureSurface.OVERLAY,
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `旧版错误字段应独立输出错误锚点`() {
        val state = ChatUiState(
            error = "legacy provider error",
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }

    @Test
    fun `状态错误不应被短暂无状态覆盖`() {
        val state = ChatUiState(
            status = GenerationStatus.ERROR,
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }

    @Test
    fun `PostProcessChip与TaskFloatingPanel不包含旧Glass组件和硬编码圆角`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val inlineComponentsSource = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatInlineComponents.kt",
        ).readText()
        val taskFloatingPanelSource = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/components/TaskFloatingPanel.kt",
        ).readText()
        val postProcessChipSource = inlineComponentsSource
            .substringAfter("fun PostProcessChip(")
            .substringBefore("\n@Composable\nfun SummaryCard(")

        assertThat(postProcessChipSource).doesNotContain("InfinityGlassCard")
        assertThat(postProcessChipSource).doesNotContain("GlassSurface")
        assertThat(postProcessChipSource).doesNotContain("GlassBorder")
        assertThat(postProcessChipSource).doesNotContain("RoundedCornerShape(50)")

        assertThat(taskFloatingPanelSource).doesNotContain("InfinityGlassCard")
        assertThat(taskFloatingPanelSource).doesNotContain("GlassSurface")
        assertThat(taskFloatingPanelSource).doesNotContain("GlassBorder")
        assertThat(taskFloatingPanelSource).doesNotContain("RoundedCornerShape(16.dp)")
    }

    @Test
    fun `主会话内联卡片使用 Material3 语义表面而非旧玻璃容器`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/ChatInlineComponents.kt",
        ).readText()
        val cardSources = listOf(
            source
                .substringAfter("fun SummaryCard(")
                .substringBefore("\n@Composable\nfun ApprovalCard("),
            source.substringAfter("fun ApprovalCard("),
        )

        cardSources.forEach { cardSource ->
            assertThat(cardSource).contains("Surface(")
            assertThat(cardSource).doesNotContain("InfinityGlassCard")
            assertThat(cardSource).doesNotContain("GlassSurface")
            assertThat(cardSource).doesNotContain("GlassBorder")
        }
        assertThat(cardSources[0]).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(cardSources[0]).contains("shape = MaterialTheme.shapes.large")
        assertThat(cardSources[0]).doesNotContain("shape = MaterialTheme.shapes.extraLarge")
        assertThat(cardSources[0]).contains(
            "heightIn(min = InfinitySpacing.MinimumTouchTarget)",
        )
        assertThat(cardSources[1]).contains("MaterialTheme.colorScheme.surfaceContainer")
        assertThat(cardSources[1]).contains("MaterialTheme.colorScheme.outlineVariant")
    }
}
