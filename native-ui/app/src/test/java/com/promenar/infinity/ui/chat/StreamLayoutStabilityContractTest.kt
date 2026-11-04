package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/**
 * 生成中会话流不随阶段跳变：非正文卡片默认收起，实时输出只在用户展开后出现，
 * 流末尾状态位常驻固定高度，整轮结束时过程以动画收起。
 */
class StreamLayoutStabilityContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    private fun source(relativePath: String): String =
        Files.readAllBytes(sourceRoot.resolve(relativePath)).toString(Charsets.UTF_8)

    private val pipeline by lazy { source("chat/PipelineBubble.kt") }

    @Test
    fun `工具行的实时输出只在展开区内显示`() {
        val toolRow = pipeline.substringAfter("internal fun InlineToolRow(").substringBefore("private fun ToolInvocationCard(")
        val beforeDetails = toolRow.substringBefore("AnimatedVisibility(visible = isExpanded")
        val details = toolRow.substringAfter("AnimatedVisibility(visible = isExpanded")
        assertThat(beforeDetails).doesNotContain("RunningToolLiveOutput(")
        assertThat(details).contains("RunningToolLiveOutput(")
        assertThat(toolRow).contains("var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }")
        assertThat(toolRow).contains("initiallyExpanded: Boolean = false")
    }

    @Test
    fun `会话流中的子代理分组卡不弹出实时输出`() {
        val subagent = source("chat/SubagentViews.kt")
        val runRow = subagent.substringAfter("private fun SubagentRunRow(").substringBefore("\n}\n")
        assertThat(runRow).doesNotContain("RunningToolLiveOutput(")
    }

    @Test
    fun `流末尾状态位常驻且整轮结束时过程以动画收起`() {
        val bubble = pipeline.substringAfter("fun PipelineBubble(").substringBefore("private class LiveTurnFlag")
        assertThat(bubble).contains("LiveTailSlot(")
        assertThat(bubble).doesNotContain("else if (live.isTailRow) LiveWaitingRow(")
        assertThat(bubble).contains("MutableTransitionState(sawLiveTurn.value || processExpanded)")
        assertThat(bubble).contains("AnimatedVisibility(visibleState = processVisibility")
    }

    @Test
    fun `思考块与工具详情默认收起`() {
        val thinking = pipeline.substringAfter("internal fun ThinkingTrace(").substringBefore("internal fun AssistantActionRow(")
        assertThat(thinking).contains("initiallyExpanded: Boolean = false")
        assertThat(pipeline).doesNotContain("initiallyExpanded = isGenerating")
        assertThat(pipeline).doesNotContain("initiallyExpanded = true")
    }
}
