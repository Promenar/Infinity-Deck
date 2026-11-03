package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextInputFloorTest {
    @Test fun `当前轮底数超过输入预算时不折叠不摘要并保留失败参数`() = runTest {
        val session = Session("session", "agent", messages = listOf(
            Message("old", MessageRole.USER, "旧历史".repeat(1000)),
            Message("new", MessageRole.USER, "文档".repeat(12000))))
        val prompt = PromptRequest(model = "model", messages = listOf(
            ProtocolMessage("system", "系统"), ProtocolMessage("user", session.messages[0].content),
            ProtocolMessage("user", session.messages[1].content)))
        val result = ContextCompactionPipeline().compact(
            ContextCompactionInput(session, "new", 1, 0.8, PromptWindow(8000, 1024)),
            buildPrompt = { prompt }, summarizeTool = { error("底数超限不能折叠") },
            summarizeHistory = { _, _ -> error("底数超限不能摘要") })
        val floor = result.blockedFloor!!
        assertThat(floor.tokens.toLong()).isGreaterThan(floor.availableTokens!!)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.prompt).isEqualTo(prompt)
        val failure = floor.failure(hasFullContextDocuments = true)
        assertThat(failure.formatArgs[GenerationFailure.KEY_CONTEXT_LIMIT_REASON]).isEqualTo("CURRENT_TURN")
        assertThat(failure.formatArgs[GenerationFailure.KEY_REQUIRED_TOKENS]).isEqualTo(floor.tokens.toString())
        assertThat(failure.formatArgs[GenerationFailure.KEY_AVAILABLE_TOKENS]).isEqualTo(floor.availableTokens.toString())
        assertThat(GenerationFailureCodec.decode(GenerationFailureCodec.encode(failure))?.formatArgs).isEqualTo(failure.formatArgs)
    }

    @Test fun `底数放得下时旧历史仍会摘要且当前轮不进入候选`() = runTest {
        val session = Session("session", "agent", messages = listOf(
            Message("old", MessageRole.USER, "旧历史".repeat(6000)), Message("new", MessageRole.USER, "当前问题")))
        val prompt = PromptRequest(model = "model", messages = listOf(ProtocolMessage("system", "规则"),
            ProtocolMessage("user", session.messages[0].content), ProtocolMessage("user", "当前问题")))
        var candidates: List<Message> = emptyList()
        val result = ContextCompactionPipeline().compact(
            ContextCompactionInput(session, "new", 1, 0.5, PromptWindow(8000, 1024)),
            buildPrompt = { if (it.summary == null) prompt else prompt.copy(messages = prompt.messages.filterIndexed { i, _ -> i != 1 }) },
            summarizeHistory = { s, messages -> candidates = messages; s.copy(summary = "摘要") })
        assertThat(result.blockedFloor).isNull()
        assertThat(result.summarizedTurns).isEqualTo(1)
        assertThat(candidates.map { it.id }).containsExactly("old")
    }

    @Test fun `底数按完整请求的校准占比分配且运行指令不另开用户轮次`() {
        val prompt = PromptRequest(model = "model", messages = listOf(ProtocolMessage("system", "规则"),
            ProtocolMessage("user", "旧问题".repeat(100)), ProtocolMessage("assistant", "旧回复"),
            ProtocolMessage("user", "当前问题"), ProtocolMessage("user", "本轮指令", isRuntimeInstruction = true)))
        val session = Session("s", "a").let { it.copy(stats = SessionStats(contextUsageBaseline =
            PromptUsageCalibration.capture(it, prompt, ApproximateTokenEstimator.estimate(prompt) * 4))) }
        val floor = ContextInputFloor.evaluate(session, prompt, PromptWindow(128000, 1024))
        val local = ApproximateTokenEstimator.estimate(prompt.copy(messages = listOf(prompt.messages[0]) + prompt.messages.takeLast(2)))
        assertThat(floor.tokens).isEqualTo(local * 4)
    }
}
