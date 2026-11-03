package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.domain.generation.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextLimitRecoveryTest {
    private val overflow = GenerationFailure(GenerationFailureCode.CONTEXT_LIMIT,
        mapOf(GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "UPSTREAM"))

    @Test fun `同次发送及工具多轮共用一次补救额度`() = runTest {
        val recovery = ContextLimitRecovery()
        var compressions = 0
        assertThat(recovery.attempt(overflow, false) { compressions++; true }).isTrue()
        assertThat(recovery.attempt(overflow, false) { compressions++; true }).isFalse()
        assertThat(compressions).isEqualTo(1)
    }

    @Test fun `已有摘要或本地拒发不消耗压缩调用且失败补救不重复`() = runTest {
        val recovery = ContextLimitRecovery()
        assertThat(recovery.attempt(overflow, true) { error("已有摘要不能再做") }).isFalse()
        val local = overflow.copy(formatArgs = mapOf(GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "CURRENT_TURN"))
        assertThat(recovery.attempt(local, false) { error("本地拒发不能补救") }).isFalse()
        assertThat(recovery.attempt(overflow, false) { false }).isFalse()
        assertThat(recovery.attempt(overflow, false) { error("失败补救也不能重复") }).isFalse()
    }

    @Test fun `唯一摘要仍未达到保守目标时不允许重发`() = runTest {
        val session = Session("s", "a", messages = listOf(Message("old", MessageRole.USER, "旧历史".repeat(1000)),
            Message("current", MessageRole.USER, "新问题")))
        val prompt = PromptRequest(model = "model", messages = listOf(ProtocolMessage("user", session.messages[0].content),
            ProtocolMessage("user", "新问题")))
        var summaries = 0
        val result = ContextCompactionPipeline().compact(ContextCompactionInput(session, "current", 1, 0.8,
            PromptWindow(8000, 1024), manual = true, inputBudgetOverride = 100),
            buildPrompt = { prompt }, summarizeHistory = { s, _ -> summaries++; s.copy(summary = "摘要") })
        assertThat(summaries).isEqualTo(1)
        assertThat(ContextLimitRecovery.meetsRecoveryBudget(result, 100)).isFalse()
    }
}
