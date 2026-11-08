package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.StartGenerationResult
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.remote.protocol.PromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenerationConcurrencyTest {
    @Test fun `等待提问释放名额且回答续跑服从FIFO队列`() = runTest {
        val pause = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                entered += request.assistantMessageId
                if (request.sessionId == "question" && request.requestId != "answer") {
                    pause.await()
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_INPUT))
                } else awaitCancellation()
            }
        }, maxConcurrent = 4)
        coordinator.start(request("question"))
        (1..4).forEach { coordinator.start(request("running-$it")) }
        runCurrent()
        assertThat(entered).hasSize(4)
        pause.complete(Unit)
        runCurrent()
        assertThat(entered).hasSize(5)
        assertThat(coordinator.isSessionBusy("question")).isFalse()
        val continuation = request("question").copy(assistantMessageId = "answer-assistant", requestId = "answer")
        val queued = coordinator.start(continuation) as StartGenerationResult.Queued
        assertThat(coordinator.start(continuation)).isInstanceOf(StartGenerationResult.Existing::class.java)
        assertThat(entered).doesNotContain("answer-assistant")
        coordinator.cancel(coordinator.observe("running-1").value!!.taskId)
        runCurrent()
        assertThat(entered.last()).isEqualTo("answer-assistant")
        assertThat(coordinator.observe("question").value?.taskId).isEqualTo(queued.taskId)
    }

    @Test
    fun `旧任务收尾不得释放同会话的新审批任务`() = runTest {
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                if (request.requestId == "old") emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
                else {
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_APPROVAL))
                    awaitCancellation()
                }
            }
        }, maxConcurrent = 4)
        val old = coordinator.start(request("same").copy(requestId = "old")) as StartGenerationResult.Started
        runCurrent()
        coordinator.awaitTaskCompletion("same", old.taskId)
        val current = coordinator.start(request("same").copy(requestId = "new")) as StartGenerationResult.Started
        runCurrent()
        assertThat(coordinator.observe("same").value?.taskId).isEqualTo(current.taskId)
        coordinator.release("same", old.taskId)
        assertThat(coordinator.observe("same").value?.taskId).isEqualTo(current.taskId)
    }

    @Test
    fun `准入撤回后不登记任务且已有任务只允许相同请求复用`() = runTest {
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, _ -> entered += request.sessionId; awaitCancellation() }
        }, maxConcurrent = 4)
        val denied = coordinator.start(request("denied")) { false }
        assertThat(denied).isInstanceOf(StartGenerationResult.Busy::class.java)
        assertThat(coordinator.sessions.value).doesNotContainKey("denied")
        val original = coordinator.start(request("existing")) as StartGenerationResult.Started
        val acceptedIds = mutableListOf<String>()
        val same = coordinator.start(request("existing")) { id -> acceptedIds += id; true }
        val deniedExisting = coordinator.start(request("existing")) { false }
        val competing = coordinator.start(request("existing").copy(requestId = "other")) { error("不可调用准入") }
        runCurrent()
        assertThat(same).isInstanceOf(StartGenerationResult.Existing::class.java)
        assertThat(acceptedIds).containsExactly(original.taskId)
        assertThat(deniedExisting).isInstanceOf(StartGenerationResult.Busy::class.java)
        assertThat(coordinator.isSessionBusy("existing")).isTrue()
        assertThat(competing).isInstanceOf(StartGenerationResult.Busy::class.java)
        assertThat(entered).containsExactly("existing")
    }

    @Test
    fun `运行器准备失败不会提交准入票据`() = runTest {
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ -> error("准备失败") }, maxConcurrent = 4)
        var accepted = false
        val result = coordinator.start(request("broken")) { accepted = true; true }
        assertThat(result).isInstanceOf(StartGenerationResult.Rejected::class.java)
        assertThat(accepted).isFalse()
        assertThat(coordinator.sessions.value).doesNotContainKey("broken")
    }

    @Test
    fun `补位后进入运行器前取消应完成一次预启动清理`() = runTest {
        val emitters = mutableMapOf<String, suspend (GenerationEvent) -> Unit>()
        val entered = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            object : GenerationRunner, QueuedGenerationCancellation {
                override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
                    entered += request.sessionId
                    emitters[request.sessionId] = emit
                    awaitCancellation()
                }
                override suspend fun cancelBeforeStart(request: GenerationRequest) { cleaned += request.sessionId }
            }
        }, maxConcurrent = 4)
        (1..4).forEach { coordinator.start(request("s$it")) }
        val queued = coordinator.start(request("s5")) as StartGenerationResult.Queued
        runCurrent()
        emitters.getValue("s1")(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        assertThat(coordinator.observe("s5").value?.phase).isEqualTo(GenerationPhase.PREPARING)
        assertThat(coordinator.cancel(queued.taskId)).isTrue()
        runCurrent()
        assertThat(entered).doesNotContain("s5")
        assertThat(cleaned).containsExactly("s5")
        assertThat(coordinator.awaitTaskCompletion("s5", queued.taskId)?.phase).isEqualTo(GenerationPhase.CANCELLED)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `全部停止时即时收尾不能让排队任务执行`() = runTest {
        val entered = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val coordinator = DefaultGenerationCoordinator(scope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                entered += request.sessionId
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { emit(GenerationEvent.PhaseChanged(GenerationPhase.CANCELLED)) }
                }
            }
        }, maxConcurrent = 4)
        (1..5).forEach { coordinator.start(request("s$it")) }
        assertThat(coordinator.cancelAll()).isEqualTo(5)
        assertThat(entered).containsExactly("s1", "s2", "s3", "s4")
        assertThat(coordinator.observe("s5").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
    }

    @Test
    fun `不同会话同时运行`() = runTest {
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(
            backgroundScope,
            GenerationRunnerFactory { _, _ -> GenerationRunner { request, _ ->
                entered += request.sessionId
                awaitCancellation()
            } },
         maxConcurrent = 4)
        val first = coordinator.start(request("first"))
        val second = coordinator.start(request("second"))
        runCurrent()

        assertThat(first).isInstanceOf(StartGenerationResult.Started::class.java)
        assertThat(second).isInstanceOf(StartGenerationResult.Started::class.java)
        assertThat(entered).containsExactly("first", "second")
    }

    @Test
    fun `上限后的任务按顺序排队取消后位置变化并自动补位`() = runTest {
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, _ -> entered += request.sessionId; awaitCancellation() }
        }, maxConcurrent = 4)
        val first = (1..4).map { coordinator.start(request("s$it")) as StartGenerationResult.Started }
        val fifth = coordinator.start(request("s5")) as StartGenerationResult.Queued
        val sixth = coordinator.start(request("s6")) as StartGenerationResult.Queued
        runCurrent()

        assertThat(entered).containsExactly("s1", "s2", "s3", "s4").inOrder()
        assertThat(fifth.position).isEqualTo(1)
        assertThat(sixth.position).isEqualTo(2)
        assertThat(coordinator.sessions.value.getValue("s5").phase).isEqualTo(GenerationPhase.QUEUED)
        assertThat(coordinator.sessions.value.getValue("s6").queuePosition).isEqualTo(2)
        assertThat(coordinator.start(request("s5"))).isInstanceOf(StartGenerationResult.Existing::class.java)
        assertThat(coordinator.cancel(fifth.taskId)).isTrue()
        runCurrent()
        assertThat(coordinator.observe("s5").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.sessions.value.getValue("s6").queuePosition).isEqualTo(1)
        assertThat(coordinator.cancel(first[0].taskId)).isTrue()
        runCurrent()
        assertThat(entered).containsExactly("s1", "s2", "s3", "s4", "s6").inOrder()
        assertThat(coordinator.observe("s6").value?.phase).isEqualTo(GenerationPhase.PREPARING)
        assertThat(coordinator.observe("s2").value?.phase).isEqualTo(GenerationPhase.PREPARING)
        assertThat(coordinator.observe("s1").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
    }

    @Test
    fun `等待审批释放名额但同会话资源收尾仍受保护`() = runTest {
        val pause = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                entered += request.sessionId
                if (request.sessionId == "s1") {
                    pause.await()
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_APPROVAL))
                    cleanup.await()
                } else awaitCancellation()
            }
        }, maxConcurrent = 4)
        (1..5).forEach { coordinator.start(request("s$it")) }
        runCurrent()
        val idle = async { coordinator.awaitSessionIdle("s1") }
        val unrelatedIdle = async { coordinator.awaitSessionIdle("missing") }
        pause.complete(Unit)
        runCurrent()
        assertThat(entered).contains("s5")
        assertThat(idle.isCompleted).isFalse()
        assertThat(unrelatedIdle.isCompleted).isTrue()
        assertThat(coordinator.isSessionBusy("s1")).isTrue()
        assertThat(coordinator.active.value?.sessionId).isEqualTo("s1")
        assertThat(coordinator.active.value?.phase).isEqualTo(GenerationPhase.WAITING_APPROVAL)
        val compaction = ContextCompactionService(
            isGenerating = coordinator::isSessionBusy,
            manualCompaction = { _, _ -> ContextCompactionResult(PromptRequest(model = "m", messages = emptyList()), 0, 0, 0, 0) },
        )
        assertThat(runCatching { compaction.compactNow("s1") }.exceptionOrNull())
            .isInstanceOf(ContextCompactionBusyException::class.java)
        assertThat(coordinator.start(request("s1").copy(requestId = "different")))
            .isInstanceOf(StartGenerationResult.Busy::class.java)
        cleanup.complete(Unit)
        idle.await()
        assertThat(coordinator.isSessionBusy("s1")).isFalse()
        assertThat(coordinator.observe("s1").value).isNull()
    }

    @Test
    fun `删除闸门和压缩互斥只约束目标会话`() = runTest {
        val gate = SessionExecutionGate()
        lateinit var coordinator: DefaultGenerationCoordinator
        val compaction = ContextCompactionService(
            isGenerating = { coordinator.sessions.value[it]?.phase in setOf(GenerationPhase.QUEUED, GenerationPhase.PREPARING) },
            manualCompaction = { _, _ -> ContextCompactionResult(PromptRequest(model = "m", messages = emptyList()), 0, 0, 0, 0) },
        )
        coordinator = DefaultGenerationCoordinator(backgroundScope,
            GenerationRunnerFactory { _, _ -> GenerationRunner { _, _ -> awaitCancellation() } },
            executionGate = gate, compactionService = compaction, maxConcurrent = 4)
        (1..5).forEach { coordinator.start(request("s$it")) }
        assertThat(runCatching { compaction.compactNow("s5") }.exceptionOrNull())
            .isInstanceOf(ContextCompactionBusyException::class.java)
        assertThat(compaction.compactNow("other").summaryFailed).isFalse()
        gate.withDeletion("deleted", "root") {
            assertThat(coordinator.start(request("deleted"))).isInstanceOf(StartGenerationResult.Rejected::class.java)
            assertThat(coordinator.sessions.value).containsKey("s1")
        }
    }

    @Test
    fun `离开前台只停止仅前台任务包括排队中的任务`() = runTest {
        val foreground = MutableStateFlow(true)
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        }, maxConcurrent = 4)
        observeForegroundOnlyPolicy(backgroundScope, foreground, coordinator)
        val foregroundRunning = request("visible").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY)
        val foregroundQueued = request("queued").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY)
        coordinator.start(foregroundRunning)
        (1..3).forEach { coordinator.start(request("background-$it")) }
        coordinator.start(foregroundQueued)
        runCurrent()
        foreground.value = false
        runCurrent()
        assertThat(coordinator.observe("visible").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.observe("queued").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.observe("background-1").value?.phase).isEqualTo(GenerationPhase.PREPARING)
    }

    @Test
    fun `已在后台时新登记的仅前台任务也会被停止`() = runTest {
        val foreground = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val coordinator = DefaultGenerationCoordinator(scope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        }, maxConcurrent = 4)
        observeForegroundOnlyPolicy(scope, foreground, coordinator)
        runCurrent()
        coordinator.start(request("visible").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY))
        (1..4).forEach { coordinator.start(request("background-$it")) }
        coordinator.start(request("queued").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY))
        runCurrent()
        assertThat(coordinator.observe("visible").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.observe("queued").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.observe("background-1").value?.phase).isEqualTo(GenerationPhase.PREPARING)
    }

    @Test
    fun `按策略批量停止时同步收尾不能先启动同策略排队任务`() = runTest {
        val entered = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val coordinator = DefaultGenerationCoordinator(scope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                entered += request.sessionId
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { emit(GenerationEvent.PhaseChanged(GenerationPhase.CANCELLED)) }
                }
            }
        }, maxConcurrent = 4)
        coordinator.start(request("foreground").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY))
        (1..3).forEach { coordinator.start(request("background-$it")) }
        coordinator.start(request("queued").copy(runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY))
        assertThat(coordinator.cancelForPolicy(GenerationRuntimePolicy.FOREGROUND_ONLY,
            com.promenar.infinity.domain.generation.CancellationReason.BACKGROUND_UNAVAILABLE)).isEqualTo(2)
        assertThat(entered).doesNotContain("queued")
        assertThat(coordinator.observe("queued").value?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(coordinator.observe("background-1").value?.phase).isEqualTo(GenerationPhase.PREPARING)
    }

    @Test
    fun `排队任务在删除闸门关闭后不得启动runner`() = runTest {
        val gate = SessionExecutionGate()
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, _ -> entered += request.sessionId; awaitCancellation() }
        }, executionGate = gate, maxConcurrent = 4)
        val first = (1..4).map { coordinator.start(request("running-$it")) as StartGenerationResult.Started }
        coordinator.start(request("deleting"))
        runCurrent()
        gate.withDeletion("deleting", null, beforeAdmissionsDrained = {
            coordinator.cancel(first.first().taskId)
            runCurrent()
            coordinator.cancelAndJoinSession("deleting")
        }) { Unit }
        assertThat(entered).doesNotContain("deleting")
    }

    @Test
    fun `删除关闭准入后补位取消不得执行预启动写入`() = runTest {
        val gate = SessionExecutionGate()
        val emitters = mutableMapOf<String, suspend (GenerationEvent) -> Unit>()
        val cleaned = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            object : GenerationRunner, QueuedGenerationCancellation {
                override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
                    emitters[request.sessionId] = emit
                    awaitCancellation()
                }
                override suspend fun cancelBeforeStart(request: GenerationRequest) { cleaned += request.sessionId }
            }
        }, executionGate = gate, maxConcurrent = 4)
        (1..4).forEach { coordinator.start(request("gate-$it")) }
        val queued = coordinator.start(request("deleting")) as StartGenerationResult.Queued
        runCurrent()
        gate.withDeletion("deleting", null, beforeAdmissionsDrained = {
            emitters.getValue("gate-1")(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            coordinator.cancel(queued.taskId)
            runCurrent()
            coordinator.cancelAndJoinSession("deleting")
        }) { Unit }
        assertThat(cleaned).doesNotContain("deleting")
    }

    private fun request(sessionId: String) = GenerationRequest(
        sessionId = sessionId,
        assistantMessageId = "assistant-$sessionId",
        userMessageId = "user-$sessionId",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )
}
