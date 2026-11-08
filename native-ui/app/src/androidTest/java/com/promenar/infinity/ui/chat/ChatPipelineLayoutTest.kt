package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class ChatPipelineLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun stepRowIs36DpWith48DpTouchAndArrowOnlyWhenExpanded() {
        var expanded by mutableStateOf<Boolean?>(false)
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(371.dp)) {
                    CompactStepRow(listOf("运行了 2 条命令"), Icons.Rounded.CheckCircle, {}, expanded = expanded, tag = "STEP")
                }
            }
        }
        rule.onNodeWithTag("STEP").assertHeightIsEqualTo(CompactStepRowHeight).assertHasClickAction()
        val node = rule.onNodeWithTag("STEP").fetchSemanticsNode()
        assertThat(node.touchBoundsInRoot.height / node.layoutInfo.density.density).isAtLeast(47.5f)
        // 收起与面板入口（expanded = null）都不显示箭头，展开后才出现。
        rule.onNode(hasTestTag("CHAT_STEP_ARROW"), useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { expanded = null }
        rule.onNode(hasTestTag("CHAT_STEP_ARROW"), useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { expanded = true }
        val text = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrow = rule.onNode(hasTestTag("CHAT_STEP_ARROW") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(abs(text.center.y - arrow.center.y)).isAtMost(with(rule.density) { 1.dp.toPx() })
    }

    @Test
    fun runningSummaryKeepsItsLeftEdgeAndThinkingPreviewFollowsAfterTwelveDp() {
        var active by mutableStateOf(false)
        rule.setContent {
            InfinityTheme {
                Column(Modifier.width(371.dp)) {
                    CompactStepRow(listOf("正在运行命令…"), Icons.Rounded.CheckCircle, {}, active = active, tag = "STEP")
                    ThinkingTrace("这是用于验证推理预览位置的较长文本。".repeat(8), true, 15, elapsedMs = 6_000)
                }
            }
        }
        fun summaryLeft() = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        val before = summaryLeft()
        rule.runOnIdle { active = true }
        rule.onNodeWithTag("STEP").assertHeightIsEqualTo(CompactStepRowHeight)
        assertThat(abs(summaryLeft() - before)).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        val label = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag(UiTags.CHAT_THINKING_TOGGLE)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag("CHAT_THINKING_PREVIEW", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val toggle = rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).fetchSemanticsNode().boundsInRoot
        rule.onNode(hasTestTag("CHAT_STEP_ARROW") and hasAnyAncestor(hasTestTag(UiTags.CHAT_THINKING_TOGGLE)), useUnmergedTree = true)
            .assertDoesNotExist()
        assertThat(abs(preview.left - label.right - with(rule.density) { 12.dp.toPx() })).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        assertThat(preview.right).isAtMost(toggle.right + 0.5f)
    }

    @Test
    fun collapsedToolsContainNaturalLanguageWithoutInternalIdsOrArguments() {
        rule.setContent {
            InfinityTheme { InlineToolRow(toolSteps(), isExecuting = false, fontSize = 15) }
        }
        listOf("ssh_exec", "read_file", "private-path", "token-marker").forEach { internalValue ->
            rule.onNodeWithText(internalValue, substring = true, useUnmergedTree = true).assertDoesNotExist()
            val description = rule.onNodeWithTag("CHAT_TOOL_TOGGLE").fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].joinToString()
            assertThat(description).doesNotContain(internalValue)
        }
        rule.onNodeWithTag("CHAT_TOOL_TOGGLE").performClick()
        rule.onNodeWithText("ssh_exec", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("CHAT_TOOL_DETAILS").assertIsDisplayed()
    }

    @Test
    fun expandedToolAndThinkingContentKeepEightDpBottomGap() {
        rule.setContent {
            InfinityTheme {
                Column(Modifier.width(371.dp)) {
                    Box(Modifier.testTag("TOOL_ROW")) { InlineToolRow(toolSteps(), isExecuting = false, fontSize = 15, initiallyExpanded = true) }
                    Box(Modifier.testTag("THINKING_ROW")) { ThinkingTrace("推理内容", false, 15, initiallyExpanded = true) }
                }
            }
        }
        val tolerance = with(rule.density) { 0.5.dp.toPx() }
        val eight = with(rule.density) { 8.dp.toPx() }
        val toolRow = rule.onNodeWithTag("TOOL_ROW").fetchSemanticsNode().boundsInRoot
        val details = rule.onNodeWithTag("CHAT_TOOL_DETAILS").fetchSemanticsNode().boundsInRoot
        // 展开内容与下一块之间：自身 8dp + 流水线块间 8dp = 16dp，与段落间距一致。
        assertThat(abs(toolRow.bottom - details.bottom - eight)).isAtMost(tolerance)
        val thinkingRow = rule.onNodeWithTag("THINKING_ROW").fetchSemanticsNode().boundsInRoot
        val thinking = rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(abs(thinkingRow.bottom - thinking.bottom - eight)).isAtMost(tolerance)
    }

    @Test
    fun userBubbleUsesAtMost82PercentAndAttachmentsAreAboveWithSixDpGap() {
        rule.setContent {
            InfinityTheme {
                UserMessageBubble(
                    message = Message("user", MessageRole.USER, "这是用来验证长消息宽度的内容。".repeat(8), userDocuments = listOf(document())),
                    fontSize = 15,
                    modifier = Modifier.width(360.dp).testTag("USER_AVAILABLE"),
                )
            }
        }
        val available = rule.onNodeWithTag("USER_AVAILABLE").fetchSemanticsNode().boundsInRoot
        val bubble = rule.onNodeWithTag("CHAT_USER_BUBBLE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val attachment = rule.onNodeWithTag(UiTags.CHAT_MESSAGE_DOCUMENTS, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 0.5.dp.toPx() }
        assertThat(bubble.width).isAtMost(available.width * 0.82f + tolerance)
        assertThat(abs(bubble.right - available.right)).isAtMost(tolerance)
        assertThat(abs(bubble.top - attachment.bottom - with(rule.density) { 6.dp.toPx() })).isAtMost(tolerance)
    }

    @Test
    fun userTimeIsOnlyInLongPressMenu() {
        val createdAt = 1_700_000_000_000L
        val timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt))
        rule.setContent {
            InfinityTheme { UserMessageBubble(Message("user", MessageRole.USER, "用户消息", createdAt = createdAt), 15) }
        }
        rule.onNodeWithText(timestamp).assertDoesNotExist()
        rule.onNodeWithText("用户消息").performTouchInput { longClick() }
        rule.onNodeWithText(timestamp).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun completedActionRowHas36DpVisualHeightAnd48DpTouchTargetsAndCallbacks() {
        var copied = 0
        var regenerated = 0
        var branched = 0
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(371.dp).padding(vertical = 8.dp)) {
                    AssistantActionRow("超长模型名称用于检查截断和时间可见性".repeat(4), "14:35", false,
                        onCopy = { copied++ }, onRegenerate = { regenerated++ }, onBranch = { branched++ }, onDelete = {})
                }
            }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_ACTIONS").assertHeightIsEqualTo(36.dp)
        listOf("CHAT_ASSISTANT_COPY", "CHAT_ASSISTANT_REGENERATE", "CHAT_ASSISTANT_MORE").forEach { tag ->
            val node = rule.onNodeWithTag(tag).assertHasClickAction().fetchSemanticsNode()
            val minimum = with(rule.density) { 48.dp.toPx() }
            assertThat(node.touchBoundsInRoot.width).isAtLeast(minimum)
            assertThat(node.touchBoundsInRoot.height).isAtLeast(minimum)
        }
        val model = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA).fetchSemanticsNode().boundsInRoot
        val time = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(model.right).isAtMost(time.left)
        assertThat(abs(model.center.y - time.center.y)).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        rule.onNodeWithTag("CHAT_ASSISTANT_COPY").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).performClick()
        rule.runOnIdle {
            assertThat(copied).isEqualTo(1)
            assertThat(regenerated).isEqualTo(1)
            assertThat(branched).isEqualTo(1)
        }
    }

    @Test
    fun generationHidesActionRowAndUnavailableRegenerationStaysHidden() {
        rule.setContent {
            InfinityTheme { AssistantActionRow("模型", "14:35", true, onCopy = {}) }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_ACTIONS").assertDoesNotExist()
    }

    @Test
    fun noRegenerationOrBranchCallbackDoesNotExposeThoseActions() {
        rule.setContent {
            InfinityTheme { AssistantActionRow("模型", "14:35", false, onCopy = {}) }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").assertDoesNotExist()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").assertIsNotEnabled()
    }

    @Test
    fun assistantBodyIsNotClickableSoLongPressGoesToTextSelection() {
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(371.dp).padding(vertical = 24.dp)) {
                    ContentSegment("结论", false, false, 15, onContentChange = null)
                }
            }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_BODY").assertHasNoClickAction()
    }

    @Test
    fun stepRowAt130PercentFontGrowsWithoutClipping() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                InfinityTheme {
                    Box(Modifier.width(371.dp)) {
                        CompactStepRow(listOf("查看了 2 个文件"), Icons.Rounded.CheckCircle, {}, tag = "LARGE_STEP")
                    }
                }
            }
        }
        rule.onNodeWithTag("LARGE_STEP").assertHeightIsAtLeast(CompactStepRowHeight)
        val row = rule.onNodeWithTag("LARGE_STEP").fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithTag("CHAT_STEP_SUMMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(text.top).isAtLeast(row.top)
        assertThat(text.bottom).isAtMost(row.bottom)
    }

    @Test
    fun toolResultTextStaysInFullResultSheet() {
        rule.setContent {
            InfinityTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InlineToolRow(listOf(
                        ExecutionStep("a", "tool_call", "read_file", "{\"path\":\"src/main.kt\"}", "call", timestamp = 1_000),
                        ExecutionStep("r", "tool_result", "read_file", toolCallId = "call", content = (1..15).joinToString("\n") { "result_$it" }, timestamp = 1_400),
                    ), false, 15, initiallyExpanded = true)
                }
            }
        }
        rule.onNodeWithText("result_1", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_TOOL_ARGUMENTS_PREVIEW, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_TOOL_FULL_OUTPUT).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.CHAT_TOOL_FULL_RESULT_TEXT).assertExists()
        rule.onNodeWithText("result_15", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun longToolErrorCollapsesMessageAndDetailsAndExpandsTogether() {
        val failure = "工具执行失败：{\"error_code\":\"COMMAND_FAILED\",\"exit_code\":2,\"guidance\":\"" +
            (1..8).joinToString("\\n") { "guidance_$it" } + "\",\"stderr\":\"" + (1..10).joinToString("\\n") { "stderr_$it" } + "\"}"
        rule.setContent {
            InfinityTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InlineToolRow(listOf(
                        ExecutionStep("a", "tool_call", "shell_exec", "{\"command\":\"ls\"}", "call", timestamp = 1_000),
                        ExecutionStep("e", "error", "shell_exec", toolCallId = "call", content = failure, timestamp = 1_400),
                    ), false, 15, initiallyExpanded = true)
                }
            }
        }
        rule.onNodeWithText("COMMAND_FAILED", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("工具执行失败", substring = true, useUnmergedTree = true).assertDoesNotExist()
        assertThat(textLayout("CHAT_TOOL_ERROR_MESSAGE").lineCount).isEqualTo(4)
        assertThat(textLayout("CHAT_TOOL_ERROR_DETAILS").lineCount).isEqualTo(6)
        rule.onNodeWithTag("CHAT_TOOL_EXPAND_ALL").performScrollTo().performClick()
        assertThat(textLayout("CHAT_TOOL_ERROR_MESSAGE").lineCount).isEqualTo(8)
        assertThat(textLayout("CHAT_TOOL_ERROR_DETAILS").lineCount).isEqualTo(10)
        assertThat(textLayout("CHAT_TOOL_ERROR_DETAILS").hasVisualOverflow).isFalse()
    }

    @Test
    fun historicalUnfinishedToolShowsPendingWithoutInternalIds() {
        val pending = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.chat_step_pending_count, 1, 1)
        rule.setContent {
            InfinityTheme {
                InlineToolRow(listOf(ExecutionStep("a", "tool_call", "ssh_exec", "{}", "a", timestamp = 1_000)), false, 15)
            }
        }
        rule.onNodeWithText(pending, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("ssh_exec", substring = true, useUnmergedTree = true).assertDoesNotExist()
        val description = rule.onNodeWithTag("CHAT_TOOL_TOGGLE").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertThat(description).containsExactly(pending)
    }

    @Test
    fun actionRowBindsCopyAndMessageCallbacksToVisibleBodyBeforeEmptyTail() {
        val copied = mutableListOf<String>()
        val regenerated = mutableListOf<String>()
        val branched = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val answer = Message("body-source", MessageRole.ASSISTANT, "实际可见的正文", modelId = "source-model")
        val emptyTail = Message("empty-tail", MessageRole.ASSISTANT, "", modelId = "tail-model", executionSteps = toolSteps())
        rule.setContent {
            InfinityTheme {
                PipelineBubble(PipelineGroup(listOf(answer, emptyTail), false), false,
                    streamingContent = "", fontSize = 15, onCopy = { copied += it },
                    onRegenerate = { regenerated += it }, onBranch = { branched += it }, onDelete = { deleted += it })
            }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_COPY").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        val deleteLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_action_delete)
        rule.onNodeWithText(deleteLabel).performClick()
        rule.runOnIdle {
            assertThat(copied).containsExactly("实际可见的正文")
            assertThat(regenerated).containsExactly("body-source")
            assertThat(branched).containsExactly("body-source")
            assertThat(deleted).containsExactly("body-source")
        }
        rule.onNodeWithText("source-model").assertIsDisplayed()
        rule.onNodeWithText("tail-model").assertDoesNotExist()
    }

    @Test
    fun longPressOnAssistantBodyNoLongerOpensMessageMenu() {
        rule.setContent {
            InfinityTheme {
                PipelineBubble(PipelineGroup(listOf(
                    Message("first", MessageRole.ASSISTANT, "前段正文"),
                    Message("second", MessageRole.ASSISTANT, "最终正文"),
                ), false), false, streamingContent = "", fontSize = 15,
                    onRegenerate = {}, onBranch = {})
            }
        }
        rule.onNodeWithText("前段正文").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_SELECT_TEXT).assertDoesNotExist()
    }

    @Test
    fun userBubbleMenuOpensTextSelectionSheet() {
        rule.setContent {
            InfinityTheme { UserMessageBubble(Message("user", MessageRole.USER, "可以选择的用户消息"), 15) }
        }
        rule.onNodeWithText("可以选择的用户消息").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_SELECT_TEXT).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_SELECTION_SHEET).assertIsDisplayed()
    }

    @Test
    fun userBubbleWithoutTextHasNoSelectTextEntry() {
        val document = MessageDocumentAttachment("doc", "a.pdf", "application/pdf", "正文", 10, "sha", 1)
        rule.setContent {
            InfinityTheme { UserMessageBubble(Message("user", MessageRole.USER, "", userDocuments = listOf(document)), 15) }
        }
        rule.onNodeWithText("a.pdf").performTouchInput { longClick() }
        val copyLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_action_copy)
        rule.onNodeWithText(copyLabel).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_SELECT_TEXT).assertDoesNotExist()
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun toolSteps() = listOf(
        ExecutionStep("a", "tool_call", "ssh_exec", "private-path", "a", timestamp = 1_000),
        ExecutionStep("ar", "tool_result", "ssh_exec", toolCallId = "a", content = "完成", timestamp = 1_400),
        ExecutionStep("b", "tool_call", "read_file", "token-marker", "b", timestamp = 1_500),
        ExecutionStep("br", "tool_result", "read_file", toolCallId = "b", content = "完成", timestamp = 1_800),
    )

    private fun document() = MessageDocumentAttachment("document", "需求说明.md", "text/markdown", "内容", 1_024, "fixture", 10)
}
