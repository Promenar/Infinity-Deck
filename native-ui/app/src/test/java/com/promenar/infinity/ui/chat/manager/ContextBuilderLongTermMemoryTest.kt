package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ContextBuilderLongTermMemoryTest {
    @Test fun `长期记忆通过真实构建路径注入资料边界并按当前会话核验`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "稳定偏好")))
        val access = RecordingMemoryAccess()
        val params = params()
        val prompt = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
            .buildContext(params).turnContext
        assertThat(access.checkedSession).isEqualTo(params.session)
        assertThat(prompt).contains("- [memory-id] (user) 偏好: 稳定偏好")
        assertThat(prompt.indexOf(SystemPromptSections.DATA_BOUNDARY)).isLessThan(prompt.indexOf("### Long-term memory"))
        assertThat(prompt.indexOf("<reference_material>")).isLessThan(prompt.indexOf("### Long-term memory"))
        assertThat(prompt.indexOf("### Long-term memory")).isLessThan(prompt.indexOf("</reference_material>"))
    }

    @Test fun `访问策略关闭或会话禁用工具时完全不读取与注入记忆`() = runTest {
        for (toolsEnabled in listOf(true, false)) {
            val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "不得暴露")))
            val access = RecordingMemoryAccess(enabled = !toolsEnabled)
            val prompt = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
                .buildContext(params(toolsEnabled = toolsEnabled)).turnContext
            assertThat(prompt).doesNotContain("不得暴露")
            assertThat(prompt).doesNotContain("### Long-term memory")
            assertThat(repository.reads).isEqualTo(0)
        }
    }

    @Test fun `缺失访问策略时不会读取仓库且仓库错误不进入提示词`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture())).apply { failure = IllegalStateException("private-error") }
        val withoutAccess = ContextBuilder(longTermMemoryRepository = repository).buildContext(params()).turnContext
        assertThat(repository.reads).isEqualTo(0)
        assertThat(withoutAccess).doesNotContain("### Long-term memory")
        val withAccess = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = RecordingMemoryAccess())
            .buildContext(params()).turnContext
        assertThat(withAccess).doesNotContain("private-error")
        assertThat(withAccess).doesNotContain("### Long-term memory")
    }

    @Test fun `访问策略异常安全跳过但取消向上传播`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture()))
        val access = RecordingMemoryAccess().apply { failure = IllegalStateException("private-policy-error") }
        val builder = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
        val prompt = builder.buildContext(params()).turnContext
        assertThat(prompt).doesNotContain("private-policy-error")
        assertThat(repository.reads).isEqualTo(0)
        access.failure = CancellationException("cancel")
        assertCancellation { builder.buildContext(params()) }
        access.failure = null
        repository.failure = CancellationException("cancel")
        assertCancellation { builder.buildContext(params()) }
    }

    @Test fun `冻结上下文续轮不再读取长期记忆且新轮读取最新内容`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "长".repeat(6001))))
        val access = RecordingMemoryAccess()
        val builder = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
        val first = builder.buildContext(params(toolNames = setOf("memory_save")))
        assertThat(first.turnContext).contains("### Long-term memory")
        assertThat(first.turnContext).doesNotContain("memory_read")
        val second = builder.buildContext(params(toolNames = setOf("memory_read")).copy(
            cachedRetrieval = first.retrieval, cachedTurnContext = first.turnContext,
        ))
        assertThat(second.turnContext).isEqualTo(first.turnContext)
        assertThat(repository.reads).isEqualTo(1)
        val fresh = builder.buildContext(params(toolNames = setOf("memory_read")))
        assertThat(fresh.turnContext).contains("memory_read")
        assertThat(repository.reads).isEqualTo(2)
        access.enabled = false
        val disabled = builder.buildContext(params().copy(cachedRetrieval = first.retrieval))
        assertThat(disabled.turnContext).doesNotContain("### Long-term memory")
        assertThat(repository.reads).isEqualTo(2)
    }

    private fun params(toolsEnabled: Boolean = true, toolNames: Set<String> = setOf("memory_save", "memory_read", "memory_forget")) =
        ContextBuilderParams(
            sessionId = "session", content = "问题", assistantMsgId = "message",
            session = Session(id = "session", agentId = "agent", options = SessionOptions(toolsEnabled = toolsEnabled, enableTimeInjection = false)),
            availableToolNames = toolNames,
        )
    private suspend fun assertCancellation(block: suspend () -> Unit) {
        var caught: Throwable? = null
        try { block() } catch (failure: Throwable) { caught = failure }
        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }
}
