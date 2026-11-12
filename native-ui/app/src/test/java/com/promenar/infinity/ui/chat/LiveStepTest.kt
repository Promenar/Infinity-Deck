package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRetry
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import org.junit.jupiter.api.Test

class LiveStepTest {
    private fun call(id: String, name: String, callId: String, at: Long) =
        ExecutionStep(id, "tool_call", name, "{}", callId, timestamp = at)

    private fun result(id: String, name: String, callId: String, at: Long) =
        ExecutionStep(id, "tool_result", name, toolCallId = callId, content = "完成", timestamp = at)

    private fun signals(
        phase: GenerationPhase?,
        isGenerating: Boolean = true,
        hasStreamingContent: Boolean = false,
        awaitingApproval: Boolean = false,
        approvalMessageId: String? = null,
        awaitingAnswer: Boolean = false,
        questionMessageId: String? = null,
        liveToolKeys: Set<String> = emptySet(),
        generationStartedAt: Long? = null,
        retry: LiveRetry? = null,
    ) = LiveTurnSignals(
        phase, isGenerating, hasStreamingContent, awaitingApproval, approvalMessageId,
        awaitingAnswer, questionMessageId, liveToolKeys, generationStartedAt, retry,
    )

    /** 第 3 次重试，界面在 50 秒收到，退避 4 秒。 */
    private val retry3 = LiveRetry(GenerationRetry(3, 10, GenerationFailureCode.NETWORK, 4_000), scheduledAt = 50_000)

    /** “现在删除源仓库”之后发起的删除命令，尚未返回结果。 */
    private val sayThenRun = Message(
        "a1", MessageRole.ASSISTANT, "现在删除源仓库", createdAt = 1_000,
        toolCalls = listOf(ToolCall("rm", "shell_exec", "{}")),
        executionSteps = listOf(call("c-rm", "shell_exec", "rm", 5_000)),
    )

    @Test
    fun `首字前只在流末尾显示等待模型响应，计时从请求发出起算`() {
        val placeholder = Message("a1", MessageRole.ASSISTANT, "", createdAt = 2_000)
        val live = resolveLiveStep(listOf(placeholder), signals(GenerationPhase.CONNECTING, generationStartedAt = 1_500))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.WAIT)
        assertThat(live.startedAt).isEqualTo(2_000)
        assertThat(live.labelRes).isEqualTo(R.string.live_step_waiting_model)
        assertThat(live.isTailRow).isTrue()
        // 思考阶段已开始但还没有任何推理或正文：仍是等待模型。
        assertThat(resolveLiveStep(listOf(placeholder), signals(GenerationPhase.THINKING))!!.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
    }

    @Test
    fun `工具结束后下一轮尚未输出时等待计时从最后一个工具结果起算`() {
        val done = sayThenRun.copy(executionSteps = sayThenRun.executionSteps!! + result("r-rm", "shell_exec", "rm", 9_000))
        val building = resolveLiveStep(listOf(done), signals(GenerationPhase.BUILDING_CONTEXT, generationStartedAt = 500))!!
        assertThat(building.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
        assertThat(building.startedAt).isEqualTo(9_000)
        // 工具阶段却已没有未完成的调用：同样是在等模型。
        assertThat(resolveLiveStep(listOf(done), signals(GenerationPhase.EXECUTING_TOOLS))!!.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
        // 续轮新建的占位消息比工具结果更晚创建时以它为准。
        val next = Message("a2", MessageRole.ASSISTANT, "", createdAt = 9_500)
        assertThat(resolveLiveStep(listOf(done, next), signals(GenerationPhase.CONNECTING))!!.startedAt).isEqualTo(9_500)
    }

    @Test
    fun `正在运行的命令持有进行中元素，目标与工具行内的调用步骤一致`() {
        val live = resolveLiveStep(listOf(sayThenRun), signals(GenerationPhase.EXECUTING_TOOLS))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.TOOL)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.TOOL)
        assertThat(live.startedAt).isEqualTo(5_000)
        assertThat(live.labelRes).isNull()
        assertThat(live.isTailRow).isFalse()
        val toolRow = buildPipelineRenderPlan(listOf(sayThenRun), isGenerating = true, hasStreamingContent = false)
            .steps.filterIsInstance<PipelineStep.ToolExec>().single()
        assertThat(toolRow.steps.map { it.id }).contains(live.targetId)
    }

    @Test
    fun `多个工具并行时只取调用最早的一个，时间相同取先出现的`() {
        val parallel = Message(
            "a1", MessageRole.ASSISTANT, "", createdAt = 1_000,
            executionSteps = listOf(
                call("c1", "web_search", "s1", 3_000), result("r1", "web_search", "s1", 3_500),
                call("c2", "read_file", "s2", 4_000),
                call("c3", "shell_exec", "s3", 2_000),
                call("c4", "shell_exec", "s4", 2_000),
            ),
        )
        val live = resolveLiveStep(listOf(parallel), signals(GenerationPhase.EXECUTING_TOOLS))!!
        assertThat(live.targetId).isEqualTo("a1::c3")
        assertThat(live.startedAt).isEqualTo(2_000)
        // 只有一个进行中元素，不会同时出现等待行。
        assertThat(live.isTailRow).isFalse()
    }

    @Test
    fun `早先消息里未完成的调用不算正在运行，只认最后一条助手消息`() {
        val stale = Message("old", MessageRole.ASSISTANT, "", executionSteps = listOf(call("c0", "read_file", "x", 100)))
        val placeholder = Message("a2", MessageRole.ASSISTANT, "", createdAt = 2_000)
        assertThat(resolveLiveStep(listOf(stale, placeholder), signals(GenerationPhase.EXECUTING_TOOLS))!!.kind)
            .isEqualTo(LiveStepKind.WAITING_MODEL)
    }

    @Test
    fun `审批通过后在生成任务外执行的命令按实时输出判定为运行中且优先于审批`() {
        val live = resolveLiveStep(
            listOf(sayThenRun),
            signals(GenerationPhase.WAITING_APPROVAL, isGenerating = false, awaitingApproval = true, liveToolKeys = setOf("c-rm")),
        )!!
        assertThat(live.kind).isEqualTo(LiveStepKind.TOOL)
        assertThat(live.targetId).isEqualTo("a1::c-rm")
    }

    @Test
    fun `等待批准时卡片上方显示等待审批，计时从这条消息最后的动静起算`() {
        val live = resolveLiveStep(
            listOf(sayThenRun),
            signals(GenerationPhase.WAITING_APPROVAL, isGenerating = false, awaitingApproval = true, approvalMessageId = "a1"),
        )!!
        assertThat(live.kind).isEqualTo(LiveStepKind.APPROVAL)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.APPROVAL)
        assertThat(live.startedAt).isEqualTo(5_000)
        assertThat(live.targetId).isEqualTo("a1")
        assertThat(live.labelRes).isEqualTo(R.string.live_step_waiting_approval)
        assertThat(live.isStalled(live.startedAt!! + 120_000)).isFalse()
        // 状态行与流末尾同格式：名称加计时，不带标签。
        val line = live.statusLine(live.startedAt!! + 23_000)
        assertThat(line.labelRes).isEqualTo(R.string.live_step_waiting_approval)
        assertThat(line.clockSeconds).isEqualTo(23)
        assertThat(line.badge).isNull()
    }

    @Test
    fun `等待回答提问时卡片上方显示等待回答，未完成的提问不当成运行中的工具`() {
        val ask = Message(
            "a1", MessageRole.ASSISTANT, "", createdAt = 1_000,
            executionSteps = listOf(call("c-ask", "ask_user", "q1", 3_000)),
        )
        val live = resolveLiveStep(
            listOf(ask),
            signals(GenerationPhase.WAITING_INPUT, isGenerating = false, awaitingAnswer = true, questionMessageId = "a1"),
        )!!
        assertThat(live.kind).isEqualTo(LiveStepKind.QUESTION)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.APPROVAL)
        assertThat(live.startedAt).isEqualTo(3_000)
        assertThat(live.labelRes).isEqualTo(R.string.live_step_waiting_answer)
        // 即使阶段仍是工具执行，未回答的提问也不会被挂上工具动画。
        assertThat(resolveLiveStep(listOf(ask), signals(GenerationPhase.EXECUTING_TOOLS))!!.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
    }

    @Test
    fun `推理流式到达时思考行本身是进行中的一步`() {
        val thinking = Message("a1", MessageRole.ASSISTANT, "", reasoning = "先确认路径", createdAt = 1_000)
        val live = resolveLiveStep(listOf(thinking), signals(GenerationPhase.THINKING))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.THINKING)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.STREAM)
        assertThat(live.targetId).isEqualTo("a1")
        assertThat(live.labelRes).isEqualTo(R.string.live_step_thinking_elapsed)
        // 思考行沿用自身的思考计时。
        assertThat(live.startedAt).isNull()
        assertThat(live.isTailRow).isFalse()
    }

    @Test
    fun `正文流式输出时动画挂在正文末尾`() {
        val streaming = Message("a1", MessageRole.ASSISTANT, "", reasoning = "已想好")
        val live = resolveLiveStep(listOf(streaming), signals(GenerationPhase.STREAMING, hasStreamingContent = true))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.CONTENT)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.STREAM)
        assertThat(live.targetId).isEqualTo("a1")
        assertThat(live.labelRes).isNull()
        // 没有任务阶段（预览或任务尚未建立）时按消息内容推断。
        assertThat(resolveLiveStep(listOf(Message("a1", MessageRole.ASSISTANT, "春风")), signals(null))!!.kind)
            .isEqualTo(LiveStepKind.CONTENT)
    }

    @Test
    fun `模型正在输出工具调用参数时流末尾显示正在准备工具调用`() {
        val preparing = Message(
            "a1", MessageRole.ASSISTANT, "现在删除源仓库", createdAt = 1_000,
            toolCalls = listOf(ToolCall("rm", "shell_exec", "{\"comm")),
        )
        val live = resolveLiveStep(listOf(preparing), signals(GenerationPhase.STREAMING, hasStreamingContent = true))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.PREPARING_TOOL)
        assertThat(live.labelRes).isEqualTo(R.string.live_step_preparing_tool)
        assertThat(live.isTailRow).isTrue()
        assertThat(live.startedAt).isEqualTo(1_000)
        // 调用一旦登记为执行步骤，就不再是“准备中”。
        assertThat(resolveLiveStep(listOf(sayThenRun), signals(GenerationPhase.STREAMING))!!.kind).isNotEqualTo(LiveStepKind.PREPARING_TOOL)
    }

    @Test
    fun `等待模型超过30秒才进入警示，其它种类不警示`() {
        val waiting = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, startedAt = 10_000)
        assertThat(waiting.isStalled(39_999)).isFalse()
        assertThat(waiting.isStalled(40_000)).isTrue()
        assertThat(waiting.elapsedSeconds(40_000)).isEqualTo(30)
        // 时钟回拨不出现负数。
        assertThat(waiting.elapsedSeconds(5_000)).isEqualTo(0)
        assertThat(waiting.copy(startedAt = null).isStalled(1_000_000)).isFalse()
        listOf(LiveStepKind.TOOL, LiveStepKind.PREPARING_TOOL, LiveStepKind.APPROVAL, LiveStepKind.QUESTION).forEach { kind ->
            assertThat(waiting.copy(kind = kind).isStalled(1_000_000)).isFalse()
        }
    }

    @Test
    fun `未重试时满30秒行尾换成无响应标签，之前不带标签`() {
        val waiting = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, startedAt = 10_000)
        val before = waiting.statusLine(39_999)
        assertThat(before.labelRes).isEqualTo(R.string.live_step_waiting_model)
        assertThat(before.clockSeconds).isEqualTo(29)
        assertThat(before.badge).isNull()
        assertThat(before.stalled).isFalse()
        val after = waiting.statusLine(40_000)
        assertThat(after.badge).isEqualTo(LiveStatusBadge.Stalled)
        assertThat(after.stalled).isTrue()
        // 升级不改名称、不改计时口径，也不改动画阶段。
        assertThat(after.labelRes).isEqualTo(R.string.live_step_waiting_model)
        assertThat(after.clockSeconds).isEqualTo(30)
        assertThat(after.motionPhase).isEqualTo(GenerationMotionPhase.WAIT)
        // 正在准备工具调用不会升级。
        assertThat(waiting.copy(kind = LiveStepKind.PREPARING_TOOL).statusLine(1_000_000).badge).isNull()
    }

    @Test
    fun `重试并入等待行，准备工具调用时不带重试`() {
        val placeholder = Message("a1", MessageRole.ASSISTANT, "", createdAt = 2_000)
        val waiting = resolveLiveStep(listOf(placeholder), signals(GenerationPhase.CONNECTING, retry = retry3))!!
        assertThat(waiting.kind).isEqualTo(LiveStepKind.WAITING_MODEL)
        assertThat(waiting.retry).isEqualTo(retry3)
        val preparing = Message("a1", MessageRole.ASSISTANT, "", createdAt = 2_000, toolCalls = listOf(ToolCall("rm", "shell_exec", "{")))
        val live = resolveLiveStep(listOf(preparing), signals(GenerationPhase.STREAMING, retry = retry3))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.PREPARING_TOOL)
        assertThat(live.retry).isNull()
    }

    @Test
    fun `退避期间显示原因与倒计时，不计时也不算无响应`() {
        // 首字等待早已超过 30 秒，但重试退避不算无响应。
        val step = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, startedAt = 1_000, retry = retry3)
        val line = step.statusLine(50_000)
        assertThat(line.countdown).isEqualTo(LiveRetryCountdown(R.string.chat_retry_reason_network, 4))
        assertThat(line.labelRes).isNull()
        assertThat(line.clockSeconds).isNull()
        assertThat(line.badge).isEqualTo(LiveStatusBadge.Retry(3, 10))
        assertThat(line.motionPhase).isEqualTo(GenerationMotionPhase.RETRY)
        // 倒计时向上取整，最后一秒仍显示 1。
        assertThat(step.statusLine(50_001).countdown!!.seconds).isEqualTo(4)
        assertThat(step.statusLine(53_999).countdown!!.seconds).isEqualTo(1)
        assertThat(step.isStalled(53_999)).isFalse()
    }

    @Test
    fun `退避结束后回到等待模型响应，计时从重新连接起算，同一次连接满30秒才升级`() {
        val step = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, startedAt = 1_000, retry = retry3)
        val reconnected = step.statusLine(54_000)
        assertThat(reconnected.countdown).isNull()
        assertThat(reconnected.labelRes).isEqualTo(R.string.live_step_waiting_model)
        assertThat(reconnected.clockSeconds).isEqualTo(0)
        assertThat(reconnected.badge).isEqualTo(LiveStatusBadge.Retry(3, 10))
        assertThat(reconnected.motionPhase).isEqualTo(GenerationMotionPhase.WAIT)
        assertThat(step.statusLine(83_999).badge).isEqualTo(LiveStatusBadge.Retry(3, 10))
        val stalled = step.statusLine(84_000)
        assertThat(stalled.badge).isEqualTo(LiveStatusBadge.Stalled)
        assertThat(stalled.clockSeconds).isEqualTo(30)
    }

    @Test
    fun `重试原因按失败类别归为限流、服务异常与网络波动`() {
        assertThat(retryReasonRes(GenerationFailureCode.RATE_LIMIT)).isEqualTo(R.string.chat_retry_reason_rate_limit)
        assertThat(retryReasonRes(GenerationFailureCode.SERVER)).isEqualTo(R.string.chat_retry_reason_server)
        assertThat(retryReasonRes(GenerationFailureCode.UNKNOWN)).isEqualTo(R.string.chat_retry_reason_server)
        assertThat(retryReasonRes(GenerationFailureCode.NETWORK)).isEqualTo(R.string.chat_retry_reason_network)
        assertThat(retryReasonRes(GenerationFailureCode.TIMEOUT)).isEqualTo(R.string.chat_retry_reason_network)
    }

    @Test
    fun `生成结束或失败后不显示任何进行中元素`() {
        val done = Message("a1", MessageRole.ASSISTANT, "完成")
        assertThat(resolveLiveStep(listOf(done), signals(GenerationPhase.COMPLETED, isGenerating = false))).isNull()
        assertThat(resolveLiveStep(listOf(done), signals(null, isGenerating = false))).isNull()
        // 结束的瞬间阶段已进入终态而生成标记还没撤下：同样不显示。
        listOf(GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED).forEach { phase ->
            assertThat(resolveLiveStep(listOf(sayThenRun), signals(phase, isGenerating = true)) ).isNull()
        }
        // 已中断的调用（生成已停止）不再计时。
        assertThat(resolveLiveStep(listOf(sayThenRun), signals(GenerationPhase.CANCELLED, isGenerating = false))).isNull()
    }

    @Test
    fun `收尾处理阶段动画留在正文末尾且不计时`() {
        val live = resolveLiveStep(listOf(Message("a1", MessageRole.ASSISTANT, "结论")), signals(GenerationPhase.POST_PROCESSING))!!
        assertThat(live.kind).isEqualTo(LiveStepKind.CONTENT)
        assertThat(live.motionPhase).isEqualTo(GenerationMotionPhase.WAIT)
        assertThat(live.startedAt).isNull()
    }

    @Test
    fun `计时文字为分加两位秒`() {
        assertThat(liveClockText(0)).isEqualTo("0:00")
        assertThat(liveClockText(9)).isEqualTo("0:09")
        assertThat(liveClockText(75)).isEqualTo("1:15")
        assertThat(liveClockText(3_725)).isEqualTo("62:05")
    }
}
