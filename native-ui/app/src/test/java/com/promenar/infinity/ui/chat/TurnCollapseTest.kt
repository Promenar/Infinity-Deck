package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.data.model.GenerationTimingRecord
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import org.junit.jupiter.api.Test

class TurnCollapseTest {
    private fun toolCall(id: String, name: String, time: Long, vararg changedPaths: String) = listOf(
        ExecutionStep("call-$id", "tool_call", name, "{\"参数\":\"秘密参数\"}", id, timestamp = time),
        ExecutionStep(
            "result-$id", "tool_result", name, toolCallId = id, content = "秘密结果", timestamp = time + 400,
            fileChanges = changedPaths.map { FileChangeSummary(it, FileChangeType.MODIFIED, added = 1, removed = 0) }.ifEmpty { null },
        ),
    )

    /** 一轮完整 Agent 回复：思考 → 工具 → 中间正文 → 工具 → 最终正文。 */
    private fun agentTurn(): List<Message> = listOf(
        Message(
            "a1", MessageRole.ASSISTANT, "先看看文件。", reasoning = "需要读取配置",
            executionSteps = toolCall("t1", "read_file", 1_000) + toolCall("t2", "patch_file", 1_500, "app/a.kt"),
            toolCalls = listOf(ToolCall("t1", "read_file", "{}"), ToolCall("t2", "patch_file", "{}")),
            tokens = TokenUsage(timing = GenerationTimingRecord(requestStartedAt = 10_000, firstOutputAt = 10_500, endedAt = 20_000)),
        ),
        Message("tool-1", MessageRole.TOOL, "结果", toolCallId = "t1", name = "read_file"),
        Message(
            "a2", MessageRole.ASSISTANT, "",
            executionSteps = toolCall("t3", "write_file", 3_000, "app/a.kt", "app/b.kt"),
            toolCalls = listOf(ToolCall("t3", "write_file", "{}")),
        ),
        Message("tool-2", MessageRole.TOOL, "结果", toolCallId = "t3", name = "write_file"),
        Message(
            "final", MessageRole.ASSISTANT, "最终结论：已经修改完成。",
            tokens = TokenUsage(timing = GenerationTimingRecord(requestStartedAt = 60_000, firstOutputAt = 61_000, endedAt = 82_000)),
        ),
    )

    private fun plan(
        messages: List<Message>,
        isGenerating: Boolean = false,
        hasPendingApproval: Boolean = false,
        enabled: Boolean = true,
    ): CollapsedTurnPlan? = buildCollapsedTurnPlan(
        messages = messages,
        steps = buildPipelineRenderPlan(messages, isGenerating, false).steps,
        isGenerating = isGenerating,
        hasPendingApproval = hasPendingApproval,
        enabled = enabled,
    )

    @Test
    fun `有工具的完成态收起过程并单独保留最终正文`() {
        val messages = agentTurn()
        val allSteps = buildPipelineRenderPlan(messages, false, false).steps
        val plan = requireNotNull(plan(messages))

        assertThat(plan.finalContent.messageId).isEqualTo("final")
        assertThat(plan.finalContent.content).isEqualTo("最终结论：已经修改完成。")
        // 过程按原顺序保留思考、中间正文与工具（同一条消息先正文后工具，相邻工具合为一行），最终正文不在过程中重复。
        assertThat(plan.processSteps).isEqualTo(allSteps.dropLast(1))
        assertThat(plan.processSteps.map { it::class }).containsExactly(
            PipelineStep.Thinking::class, PipelineStep.Content::class, PipelineStep.ToolExec::class,
        ).inOrder()
        assertThat(plan.processSteps).doesNotContain(plan.finalContent)
        assertThat(plan.summary).isEqualTo(
            TurnProcessSummary(toolStepCount = 3, thinkingCount = 1, changedFileCount = 2, durationMs = 72_000),
        )
    }

    @Test
    fun `命令报告的关键数据变化进入收起摘要`() {
        val steps = toolCall("s1", "shell_exec", 1_000).let { (call, result) ->
            listOf(call, result.copy(content = "{\"exit_code\":0,\"runtime_changes\":[\"files/agent_skills/p (added)\"]}"))
        }
        val messages = listOf(
            Message("a1", MessageRole.ASSISTANT, "", executionSteps = steps, toolCalls = listOf(ToolCall("s1", "shell_exec", "{}"))),
            Message("tool-1", MessageRole.TOOL, "结果", toolCallId = "s1", name = "shell_exec"),
            Message("final", MessageRole.ASSISTANT, "完成"),
        )
        assertThat(requireNotNull(plan(messages)).summary.runtimeChanges).containsExactly("files/agent_skills/p (added)")
        assertThat(requireNotNull(plan(agentTurn())).summary.runtimeChanges).isEmpty()
    }

    @Test
    fun `长期记忆改动进入收起摘要以便收起后仍能撤销`() {
        val steps = toolCall("m1", "memory_save", 1_000).let { (call, result) ->
            listOf(call, result.copy(content = "{\"id\":\"mem\",\"action\":\"created\",\"kind\":\"user\",\"title\":\"称呼\",\"content\":\"叫我小冷\"}"))
        }
        val messages = listOf(
            Message("a1", MessageRole.ASSISTANT, "", executionSteps = steps, toolCalls = listOf(ToolCall("m1", "memory_save", "{}"))),
            Message("tool-1", MessageRole.TOOL, "结果", toolCallId = "m1", name = "memory_save"),
            Message("final", MessageRole.ASSISTANT, "记住了"),
        )
        val changes = requireNotNull(plan(messages)).summary.memoryChanges
        assertThat(changes.map { it.id to it.written.title }).containsExactly("mem" to "称呼")
        assertThat(requireNotNull(plan(agentTurn())).summary.memoryChanges).isEmpty()
    }

    @Test
    fun `只有思考的回复也收起且不计工具步数`() {
        val messages = listOf(Message("only", MessageRole.ASSISTANT, "答案", reasoning = "推理过程"))
        val plan = requireNotNull(plan(messages))

        assertThat(plan.processSteps).containsExactly(PipelineStep.Thinking("推理过程", "only"))
        assertThat(plan.summary).isEqualTo(TurnProcessSummary(0, 1, 0, null))
    }

    @Test
    fun `无工具也无思考的普通回复不出现摘要`() {
        assertThat(plan(listOf(Message("plain", MessageRole.ASSISTANT, "普通回复")))).isNull()
        assertThat(plan(listOf(Message("a", MessageRole.ASSISTANT, "前段"), Message("b", MessageRole.ASSISTANT, "后段")))).isNull()
    }

    @Test
    fun `生成中保持实时平铺`() {
        assertThat(plan(agentTurn(), isGenerating = true)).isNull()
    }

    @Test
    fun `出错的轮不收起`() {
        // 错误消息会截断分组：前一组是半轮回复，错误自成一组，两组都不收起。
        val partial = Message("a1", MessageRole.ASSISTANT, "部分正文", reasoning = "思考", executionSteps = toolCall("t1", "read_file", 1))
        val failed = listOf(partial, Message("err", MessageRole.ASSISTANT, "", isError = true, errorMessage = "failed"))
        val groups = buildPipelineGroups(failed)
        assertThat(groups).hasSize(2)
        assertThat(groupInterruptedByError(groups, 0)).isTrue()
        assertThat(groupInterruptedByError(groups, 1)).isFalse()
        val steps = buildPipelineRenderPlan(groups[0].messages, false, false).steps
        assertThat(buildCollapsedTurnPlan(groups[0].messages, steps, false, false, true, interruptedByError = true)).isNull()
        assertThat(buildCollapsedTurnPlan(groups[0].messages, steps, false, false, true, interruptedByError = false)).isNotNull()
        assertThat(plan(groups[1].messages)).isNull()
        val withErrorText = agentTurn().dropLast(1) + Message("final", MessageRole.ASSISTANT, "部分正文", errorMessage = "timeout")
        assertThat(plan(withErrorText)).isNull()
    }

    @Test
    fun `待审批的轮不收起`() {
        assertThat(plan(agentTurn(), hasPendingApproval = true)).isNull()
        val pendingIds = agentTurn().map { if (it.id == "a2") it.copy(pendingApprovalToolIds = listOf("t3")) else it }
        assertThat(plan(pendingIds)).isNull()
        // 最后一条助手消息仍有工具调用：这一轮尚未结束。
        val awaitingTools = agentTurn().dropLast(1).map { if (it.id == "a2") it.copy(content = "我要写入文件") else it }
        assertThat(plan(awaitingTools)).isNull()
    }

    @Test
    fun `工具调用缺少结果时不当成已完成的过程`() {
        val interrupted = listOf(
            Message("a1", MessageRole.ASSISTANT, "", executionSteps = listOf(ExecutionStep("call", "tool_call", "read_file", "{}", "c", timestamp = 1))),
            Message("final", MessageRole.ASSISTANT, "正文"),
        )
        assertThat(plan(interrupted)).isNull()
    }

    @Test
    fun `开关关闭时不收起`() {
        assertThat(plan(agentTurn(), enabled = false)).isNull()
    }

    @Test
    fun `最终正文为空或尾部是空工具消息时不收起`() {
        val emptyTail = listOf(
            Message("answer", MessageRole.ASSISTANT, "正文", reasoning = "思考"),
            Message("tail", MessageRole.ASSISTANT, "", executionSteps = toolCall("t", "read_file", 1)),
        )
        assertThat(plan(emptyTail)).isNull()
    }

    @Test
    fun `页脚复制只取最终正文而复制全部过程沿用整轮正文拼接`() {
        val messages = agentTurn()
        val allSteps = buildPipelineRenderPlan(messages, false, false).steps
        val plan = requireNotNull(plan(messages))

        assertThat(plan.finalCopyText).isEqualTo("最终结论：已经修改完成。")
        assertThat(plan.allProcessCopyText()).isEqualTo(pipelineReplyCopyText(allSteps))
        assertThat(plan.allProcessCopyText()).isEqualTo("先看看文件。\n\n最终结论：已经修改完成。")
        assertThat(plan.allProcessCopyText()).doesNotContain("秘密")
    }

    @Test
    fun `审批请求按助手消息归属到组且旧请求只作用于最后一组`() {
        val group = buildPipelineGroups(agentTurn()).single()
        val other = PipelineGroup(listOf(Message("x", MessageRole.ASSISTANT, "其它")), isUser = false)
        val request = ApprovalRequest(assistantMessageId = "a2")

        assertThat(groupHasPendingApproval(group, request, isLastGroup = false)).isTrue()
        assertThat(groupHasPendingApproval(other, request, isLastGroup = true)).isFalse()
        assertThat(groupHasPendingApproval(group, null, isLastGroup = true)).isFalse()
        assertThat(groupHasPendingApproval(other, ApprovalRequest(), isLastGroup = true)).isTrue()
        assertThat(groupHasPendingApproval(other, ApprovalRequest(), isLastGroup = false)).isFalse()
    }

    @Test
    fun `用时按四舍五入拆成小时分秒且至少一秒`() {
        assertThat(turnDurationParts(72_000)).isEqualTo(TurnDurationParts(0, 1, 12))
        assertThat(turnDurationParts(300)).isEqualTo(TurnDurationParts(0, 0, 1))
        assertThat(turnDurationParts(59_600)).isEqualTo(TurnDurationParts(0, 1, 0))
        assertThat(turnDurationParts(3_725_000)).isEqualTo(TurnDurationParts(1, 2, 5))
    }

    @Test
    fun `最后一条消息正文之后的已完成工具并入过程，正文仍留在最下方`() {
        val messages = listOf(
            Message("only", MessageRole.ASSISTANT, "已清理完成", reasoning = "检查目录", executionSteps = toolCall("t1", "shell_exec", 1_000)),
        )
        val allSteps = buildPipelineRenderPlan(messages, false, false).steps
        assertThat(allSteps.map { it::class }).containsExactly(
            PipelineStep.Thinking::class, PipelineStep.Content::class, PipelineStep.ToolExec::class,
        ).inOrder()
        val plan = requireNotNull(plan(messages))
        assertThat(plan.finalContent.content).isEqualTo("已清理完成")
        assertThat(plan.processSteps.map { it::class }).containsExactly(PipelineStep.Thinking::class, PipelineStep.ToolExec::class).inOrder()
        assertThat(plan.summary.toolStepCount).isEqualTo(1)
    }
}
