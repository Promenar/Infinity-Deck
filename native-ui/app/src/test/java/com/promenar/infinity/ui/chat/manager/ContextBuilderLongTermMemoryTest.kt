package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ContextBuilderLongTermMemoryTest {
    @Test fun `共享与隔离目录只注入当前归属的记录`() = runTest {
        val shared = memoryFixture(id = "shared", content = "共享正文")
        val isolated = memoryFixture(id = "isolated", content = "隔离正文").copy(ownerSessionId = "s")
        val other = memoryFixture(id = "other", content = "其它会话正文").copy(ownerSessionId = "other")
        val repository = RecordingMemoryRepository(listOf(shared, isolated, other))
        val access = RecordingMemoryAccess()
        val builder = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
        val sharedContext = builder.buildContext(params()).turnContext
        assertThat(sharedContext).contains("共享正文")
        assertThat(sharedContext).doesNotContain("隔离正文")
        access.ownerSessionId = "s"
        val isolatedContext = builder.buildContext(params()).turnContext
        assertThat(isolatedContext).contains("隔离正文")
        assertThat(isolatedContext).doesNotContain("共享正文")
        assertThat(isolatedContext).doesNotContain("其它会话正文")
    }
    @Test fun `长期记忆通过真实构建路径注入资料边界并按当前会话核验`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "稳定偏好")))
        val access = RecordingMemoryAccess()
        val params = params()
        val prompt = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
            .buildContext(params).turnContext
        assertThat(access.checkedSession).isEqualTo(params.session)
        assertThat(prompt).contains("- [memory-id] (user) 偏好: 稳定偏好")
        assertThat(prompt.indexOf(SystemPromptSections.DATA_BOUNDARY)).isLessThan(prompt.indexOf("### Active memory"))
        assertThat(prompt.indexOf("<reference_material>")).isLessThan(prompt.indexOf("### Active memory"))
        assertThat(prompt.indexOf("### Active memory")).isLessThan(prompt.indexOf("</reference_material>"))
    }

    @Test fun `访问策略关闭或会话禁用工具时完全不读取与注入记忆`() = runTest {
        for (toolsEnabled in listOf(true, false)) {
            val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "不得暴露")))
            val access = RecordingMemoryAccess(enabled = !toolsEnabled)
            val prompt = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = access)
                .buildContext(params(toolsEnabled = toolsEnabled)).turnContext
            assertThat(prompt).doesNotContain("不得暴露")
            assertThat(prompt).doesNotContain("### Active memory")
            assertThat(repository.reads).isEqualTo(0)
        }
    }

    @Test fun `缺失访问策略时不会读取仓库且仓库错误不进入提示词`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture())).apply { failure = IllegalStateException("private-error") }
        val withoutAccess = ContextBuilder(longTermMemoryRepository = repository).buildContext(params()).turnContext
        assertThat(repository.reads).isEqualTo(0)
        assertThat(withoutAccess).doesNotContain("### Active memory")
        val withAccess = ContextBuilder(longTermMemoryRepository = repository, longTermMemoryAccess = RecordingMemoryAccess())
            .buildContext(params()).turnContext
        assertThat(withAccess).doesNotContain("private-error")
        assertThat(withAccess).doesNotContain("### Active memory")
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
        assertThat(first.turnContext).contains("### Active memory")
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
        assertThat(disabled.turnContext).doesNotContain("### Active memory")
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
