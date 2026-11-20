package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationPresentationStoreTest {
    @Test fun `开始生成时绑定初始助手消息且诊断输出隐藏正文`() {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A", "a1")
        store.port("A", "task-A").setStreamingContent("仅供展示的正文")

        val state = requireNotNull(store.observe("A").value)
        assertThat(state.streamingMessageId).isEqualTo("a1")
        assertThat(state.toString()).contains("streamingMessageId=a1")
        assertThat(state.toString()).doesNotContain("仅供展示的正文")
    }

    @Test fun `目标切换事件清空上一轮正文并绑定新助手消息`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A", "a1")
        store.port("A", "task-A").setStreamingContent("上一轮")

        store.accept("A", "task-A", GenerationEvent.TargetChanged("a2"))

        val state = requireNotNull(store.observe("A").value)
        assertThat(state.streamingContent).isEmpty()
        assertThat(state.streamingMessageId).isEqualTo("a2")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `展示端口在一次状态更新中发布正文和助手消息目标`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A", "a1")
        val port = store.port("A", "task-A")
        port.setStreamingContent("上一轮")
        val observed = mutableListOf<Pair<String?, String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe("A").collect { state ->
                if (state != null) observed += state.streamingMessageId to state.streamingContent
            }
        }

        port.setStreamingTarget("a2")
        port.setStreamingContent("续答正文", "a3")

        assertThat(observed).containsExactly(
            "a1" to "上一轮",
            "a2" to "",
            "a3" to "续答正文",
        ).inOrder()
    }

    @Test fun `交错的会话回写只改变各自展示状态`() {
        val store = GenerationPresentationStore()
        store.begin("first", "task-first")
        store.begin("second", "task-second")
        val first = store.port("first", "task-first")
        val second = store.port("second", "task-second")
        first.setGenerating(true)
        second.setError(com.promenar.infinity.domain.generation.GenerationFailure.unknown(technical = "second"))
        second.setFailureSurface(GenerationFailureSurface.OVERLAY)
        first.setStreamingContent("first output")
        assertThat(store.observe("first").value?.streamingContent).isEqualTo("first output")
        assertThat(store.observe("first").value?.error).isNull()
        assertThat(store.observe("first").value?.generating).isTrue()
        assertThat(store.observe("second").value?.error?.technical).isEqualTo("second")
        assertThat(store.observe("second").value?.failureSurface).isEqualTo(GenerationFailureSurface.OVERLAY)
    }

    @Test
    fun `待回答保留流式展示但释放生成状态`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        store.port("A", "task-A").setStreamingContent("请选择方案")

        store.accept("A", "task-A", GenerationEvent.PhaseChanged(com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT))

        assertThat(store.observe("A").value?.generating).isFalse()
        assertThat(store.observe("A").value?.streamingContent).isEqualTo("请选择方案")
        assertThat(store.observe("A").value?.phase).isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT)
    }

    @Test
    fun `失败事件只保存结构化failure且保留诊断引用`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        val cause = IllegalStateException("provider raw")
        val failSnapshot = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "37"),
            technical = "HTTP 429",
            cause = cause,
        )

        store.accept("A", "task-A", GenerationEvent.Failed(failSnapshot))

        assertThat(store.observe("A").value?.error).isSameInstanceAs(failSnapshot)
        assertThat(store.observe("A").value?.phase)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.FAILED)
        assertThat(store.observe("A").value?.generating).isFalse()
        assertThat(store.observe("A").value?.error?.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(store.observe("A").value?.error?.retryAfterSeconds).isEqualTo(37)
        assertThat(store.observe("A").value?.error?.cause).isSameInstanceAs(cause)
        assertThat(store.observe("A").value?.error?.technical).isEqualTo("HTTP 429")
    }

    @Test
    fun `结构化失败事件逐类落地并按任务隔离`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        val snapshotFailure = GenerationFailure.unknown(technical = "snapshot")
        val rejectedFailure = GenerationFailure(GenerationFailureCode.AUTH, technical = "rejected")
        val persistenceFailure = GenerationFailure.persistence(technical = "persistence")
        val failedFailure = GenerationFailure(GenerationFailureCode.NETWORK, technical = "failed")

        store.accept(
            "A",
            "task-A",
            GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "content", failure = snapshotFailure)),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(snapshotFailure)
        assertThat(store.observe("A").value?.failureSurface)
            .isEqualTo(GenerationFailureSurface.INLINE_MESSAGE)
        store.accept(
            "A",
            "task-A",
            GenerationEvent.Rejected(rejectedFailure),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(rejectedFailure)
        assertThat(store.observe("A").value?.phase).isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.FAILED)
        store.accept(
            "A",
            "task-A",
            GenerationEvent.PersistenceFailed(
                persistenceCause = RuntimeException("io"),
                originalCause = null,
                failure = persistenceFailure,
            ),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(persistenceFailure)
        assertThat(store.observe("A").value?.phase)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED)
        assertThat(store.observe("A").value?.failureSurface)
            .isEqualTo(GenerationFailureSurface.OVERLAY)
        store.accept("A", "task-A", GenerationEvent.Failed(failedFailure))
        assertThat(store.observe("A").value?.error).isSameInstanceAs(failedFailure)

        store.begin("B", "task-B")
        val uiFailure = GenerationFailure(
            code = GenerationFailureCode.TIMEOUT,
            technical = "ui",
        )
        store.port("B", "task-B").setError(uiFailure)
        assertThat(store.observe("B").value?.error?.technical).isEqualTo("ui")
        assertThat(store.observe("B").value?.error?.code).isEqualTo(GenerationFailureCode.TIMEOUT)
        assertThat(store.observe("A").value?.error?.code).isEqualTo(GenerationFailureCode.NETWORK)
    }

    @Test
    fun `A生成时导航B后A更新不污染B且返回A可恢复`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        store.begin("B", "task-B")
        val aPort = store.port("A", "task-A")

        aPort.setStreamingContent("partial-A")
        store.accept("A", "task-A", GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "final-A")))

        assertThat(store.observe("B").value?.streamingContent).isEmpty()
        assertThat(store.observe("B").value?.error).isNull()
        assertThat(store.observe("A").value?.streamingContent).isEqualTo("final-A")
    }

    @Test
    fun `旧task事件被隔离且finish清理entry`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "old")
        val oldPort = store.port("A", "old")
        oldPort.setStreamingContent("old")
        store.finish("A", "old")

        assertThat(store.observe("A").value).isNull()

        store.begin("A", "new")
        val newPort = store.port("A", "new")
        oldPort.setStreamingContent("stale")
        store.accept("A", "old", GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "stale-event")))
        newPort.setStreamingContent("fresh")

        assertThat(store.observe("A").value?.taskId).isEqualTo("new")
        assertThat(store.observe("A").value?.streamingContent).isEqualTo("fresh")
    }

    @Test
    fun `同会话终态后再次生成必须复用原订阅flow`() = runTest {
        val store = GenerationPresentationStore()
        val observed = store.observe("A")

        store.begin("A", "first")
        store.finish("A", "first")
        store.begin("A", "second")
        store.port("A", "second").setStreamingContent("second turn")

        assertThat(store.observe("A")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo("second")
        assertThat(observed.value?.streamingContent).isEqualTo("second turn")
    }

    @Test
    fun `旧页面基于空快照释放时不得移除并发开始的新任务`() = runTest {
        val store = GenerationPresentationStore()
        val observed = store.observe("A")
        assertThat(observed.value).isNull()

        store.release("A")
        store.begin("A", "new-task")
        store.port("A", "new-task").setStreamingContent("visible")

        assertThat(store.observe("A")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo("new-task")
        assertThat(observed.value?.streamingContent).isEqualTo("visible")
    }
}
