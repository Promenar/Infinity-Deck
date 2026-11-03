package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.CancellationReason
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRetry
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.StartGenerationResult
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.session.SessionExecutionGate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test

class DefaultGenerationCoordinatorTest {
    @Test fun `审批创建窗口插队续跑保持并发槽且可取消`() = runTest {
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val resumed = CompletableDeferred<Unit>()
        val runtime = object : ChatGenerationRuntime {
            override suspend fun prepare(request: GenerationRequest) = Unit
            override suspend fun buildContext(request: GenerationRequest) = com.promenar.infinity.domain.generation.GenerationPreparationOutcome.Ready
            override suspend fun stream(request: GenerationRequest, attempt: Int): kotlinx.coroutines.flow.Flow<com.promenar.infinity.domain.generation.GenerationChunk> =
                if (attempt == 0) kotlinx.coroutines.flow.flowOf(
                    com.promenar.infinity.domain.generation.GenerationChunk.ToolCall("call", "write", "{}"),
                    com.promenar.infinity.domain.generation.GenerationChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.TOOL_CALLS, listOf("call")))
                else kotlinx.coroutines.flow.flow { resumed.complete(Unit); awaitCancellation() }
            override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
            override suspend fun handleTools(request: GenerationRequest, toolCalls: List<com.promenar.infinity.domain.generation.GenerationToolCall>): com.promenar.infinity.domain.generation.GenerationToolDecision {
                queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                    com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
                return com.promenar.infinity.domain.generation.GenerationToolDecision.WAIT_FOR_APPROVAL
            }
            override suspend fun steeringContext(request: GenerationRequest) = com.promenar.infinity.data.exec.SteeringContext(queue)
            override suspend fun interruptPendingTools(request: GenerationRequest, toolCalls: List<com.promenar.infinity.domain.generation.GenerationToolCall>) = true
            override suspend fun injectSteering(request: GenerationRequest): GenerationRequest? {
                queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState()
                return request.copy(userMessageId = "queued-user", userContent = "插队")
            }
            override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
            override suspend fun flush(request: GenerationRequest) = Unit
            override fun cancelProvider() = Unit
        }
        val coordinator = DefaultGenerationCoordinator(applicationScope = backgroundScope,
            taskIdFactory = { "slot-${nextTaskId.incrementAndGet()}" },
            runnerFactory = GenerationRunnerFactory { request, _ ->
                if (request.sessionId == "steering") ChatGenerationRunner(runtime) else GenerationRunner { _, _ -> awaitCancellation() }
            })
        val first = coordinator.start(request("steering", "a")) as StartGenerationResult.Started
        resumed.await()
        repeat(com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS - 1) { coordinator.start(request("other-$it", "a-$it")) }
        coordinator.start(request("overflow", "a-overflow"))
        runCurrent()
        assertThat(coordinator.observe("overflow").value!!.phase).isEqualTo(GenerationPhase.QUEUED)
        assertThat(coordinator.cancel(first.taskId, CancellationReason.USER)).isTrue()
        runCurrent()
        assertThat(coordinator.observe("overflow").value!!.phase).isNotEqualTo(GenerationPhase.QUEUED)
    }

    @Test
    fun `确认成功终态后awaitSessionIdle仍等待资源收尾`() = runTest {
        val terminal = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            terminal.complete(Unit)
            release.await()
        }
        val first = coordinator.start(request("s1", "a1")) as StartGenerationResult.Started
        terminal.await()
        coordinator.acknowledgeTerminal(first.taskId)
        assertThat(coordinator.active.value).isNull()
        val idle = async { coordinator.awaitSessionIdle("s1") }
        val completion = async { coordinator.awaitTaskCompletion("s1", first.taskId) }
        runCurrent()
        assertThat(idle.isCompleted).isFalse()
        assertThat(completion.isCompleted).isFalse()
        release.complete(Unit)
        idle.await()
        assertThat(completion.await()!!.phase).isEqualTo(GenerationPhase.COMPLETED)
        assertThat(coordinator.start(request("s1", "a2"))).isInstanceOf(StartGenerationResult.Started::class.java)
    }

    @Test
    fun `deleting gate在runner创建前拒绝新生成`() = runTest {
        val gate = SessionExecutionGate()
        val created = AtomicInteger()
        val coordinator = DefaultGenerationCoordinator(
            applicationScope = backgroundScope,
            executionGate = gate,
            runnerFactory = GenerationRunnerFactory { _, _ ->
                created.incrementAndGet()
                GenerationRunner { _, _ -> Unit }
            },
        )

        val result = gate.withDeletion("s1", "root-1") {
            coordinator.start(request("s1", "a1"))
        }

        assertThat(result).isInstanceOf(StartGenerationResult.Rejected::class.java)
        assertThat(created.get()).isEqualTo(0)
    }

    @Test
    fun `cancelAndJoinSession等待runner finally结束`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val coordinator = coordinator { _, _ ->
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        coordinator.start(request("s1", "a1"))
        entered.await()

        assertThat(coordinator.cancelAndJoinSession("s1")).isTrue()
        assertThat(finished.isCompleted).isTrue()
        assertThat(coordinator.active.value).isNull()
    }

    @Test
    fun `同请求Existing不同会话独立启动`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.CONNECTING))
            entered.complete(Unit)
            awaitCancellation()
        }

        val first = coordinator.start(request("s1", "a1"))
        entered.await()
        val same = coordinator.start(request("s1", "a1"))
        val other = coordinator.start(request("s2", "a2"))

        assertThat(first).isInstanceOf(StartGenerationResult.Started::class.java)
        assertThat(same).isInstanceOf(StartGenerationResult.Existing::class.java)
        assertThat(other).isInstanceOf(StartGenerationResult.Started::class.java)
        assertThat(coordinator.sessions.value.keys).containsExactly("s1", "s2")
        assertThat(coordinator.observe("s2").value?.sessionId).isEqualTo("s2")
    }

    @Test
    fun `相同assistant但不同requestId必须Busy不能误判Existing`() = runTest {
        val coordinator = coordinator { _, _ -> awaitCancellation() }
        coordinator.start(request("s1", "same-assistant").copy(requestId = "request-1"))
        runCurrent()

        val second = coordinator.start(
            request("s1", "same-assistant").copy(requestId = "request-2"),
        )

        assertThat(second).isEqualTo(StartGenerationResult.Busy("s1"))
    }

    @Test
    fun `并发start通过Mutex只创建一次任务`() = runTest {
        val starts = AtomicInteger()
        val coordinator = coordinator { _, _ ->
            starts.incrementAndGet()
            awaitCancellation()
        }

        val results = List(8) { async { coordinator.start(request("s1", "a1")) } }.map { it.await() }
        runCurrent()

        assertThat(results.count { it is StartGenerationResult.Started }).isEqualTo(1)
        assertThat(results.count { it is StartGenerationResult.Existing }).isEqualTo(7)
        assertThat(starts.get()).isEqualTo(1)
    }

    @Test
    fun `旧页面release不得在新任务启动窗口移除既有订阅flow`() = runTest {
        val factoryEntered = CountDownLatch(1)
        val allowFactory = CountDownLatch(1)
        val coordinator = DefaultGenerationCoordinator(
            applicationScope = backgroundScope,
            runnerFactory = GenerationRunnerFactory { _, _ ->
                factoryEntered.countDown()
                check(allowFactory.await(5, TimeUnit.SECONDS))
                GenerationRunner { _, _ -> awaitCancellation() }
            },
            taskIdFactory = { "new-task" },
        )
        val observed = coordinator.observe("same-session")

        val starting = async(Dispatchers.Default) {
            coordinator.start(request("same-session", "assistant"))
        }
        assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue()
        val releaseAttempted = CountDownLatch(1)
        val releasing = async(Dispatchers.Default) {
            releaseAttempted.countDown()
            coordinator.release("same-session", discardTerminal = true)
        }
        assertThat(releaseAttempted.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(releasing.isCompleted).isFalse()
        allowFactory.countDown()
        val started = starting.await() as StartGenerationResult.Started
        releasing.await()

        assertThat(coordinator.observe("same-session")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo(started.taskId)
        assertThat(coordinator.cancel(started.taskId, CancellationReason.USER)).isTrue()
    }

    @Test
    fun `新观察者先注册后旧页面release仍复用同一flow接收后续任务`() = runTest {
        val coordinator = coordinator { _, _ -> awaitCancellation() }
        val observed = coordinator.observe("reopened-session")

        coordinator.release("reopened-session", discardTerminal = true)
        val started = coordinator.start(request("reopened-session", "assistant")) as StartGenerationResult.Started
        runCurrent()

        assertThat(coordinator.observe("reopened-session")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo(started.taskId)
        assertThat(coordinator.cancel(started.taskId, CancellationReason.USER)).isTrue()
    }

    @Test
    fun `陈旧taskId不能取消新任务且当前taskId可取消同一job`() = runTest {
        val coordinator = coordinator { _, _ -> awaitCancellation() }
        val started = coordinator.start(request("s1", "a1")) as StartGenerationResult.Started
        runCurrent()

        assertThat(coordinator.cancel("stale", CancellationReason.USER)).isFalse()
        assertThat(coordinator.cancel(started.taskId, CancellationReason.USER)).isTrue()
        runCurrent()
        assertThat(coordinator.active.value).isNull()
    }

    @Test
    fun `任务快照带出重试倒计时与正文加思考字数供会话列表驱动指示动效`() = runTest {
        val step = Channel<Unit>()
        val retry = GenerationRetry(attempt = 1, maxAttempts = 3, code = GenerationFailureCode.NETWORK, delayMs = 2_000)
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.CONNECTING))
            emit(GenerationEvent.RetryScheduled(retry))
            step.receive()
            emit(GenerationEvent.PhaseChanged(GenerationPhase.CONNECTING))
            step.receive()
            emit(GenerationEvent.PhaseChanged(GenerationPhase.THINKING))
            emit(GenerationEvent.SnapshotChanged(GenerationSnapshot(reasoning = "先想一想")))
            step.receive()
            emit(GenerationEvent.PhaseChanged(GenerationPhase.STREAMING))
            emit(GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "答复", reasoning = "先想一想")))
            awaitCancellation()
        }
        coordinator.start(request("s1", "a1"))
        runCurrent()
        assertThat(coordinator.observe("s1").value?.retry).isEqualTo(retry)

        step.send(Unit)
        runCurrent()
        // 重发后仍在连接，倒计时保留到首个输出阶段之外的阶段才清除。
        assertThat(coordinator.observe("s1").value?.retry).isEqualTo(retry)

        step.send(Unit)
        runCurrent()
        val thinking = coordinator.observe("s1").value!!
        assertThat(thinking.retry).isEqualTo(retry)
        assertThat(thinking.generatedChars).isEqualTo(4)

        step.send(Unit)
        runCurrent()
        val streaming = coordinator.observe("s1").value!!
        assertThat(streaming.retry).isNull()
        assertThat(streaming.generatedChars).isEqualTo(6)
    }

    @Test
    fun `VM观察者消失不取消应用级任务且新观察者恢复同一task`() = runTest {
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.STREAMING))
            awaitCancellation()
        }
        val started = coordinator.start(request("s1", "a1")) as StartGenerationResult.Started
        runCurrent()
        val observer = launch { coordinator.observe("s1").first { it?.phase == GenerationPhase.STREAMING } }
        observer.join()

        val restored = coordinator.observe("s1").value

        assertThat(restored?.taskId).isEqualTo(started.taskId)
        assertThat(restored?.phase).isEqualTo(GenerationPhase.STREAMING)
        assertThat(coordinator.active.value?.taskId).isEqualTo(started.taskId)
    }

    @Test
    fun `当前观察者收到完成终态并确认后新观察者只看到空闲`() = runTest {
        val completed = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        }
        val terminal = async {
            completed.observe("ok").first { it?.phase == GenerationPhase.COMPLETED }!!
        }
        completed.start(request("ok", "a1"))
        runCurrent()

        val snapshot = terminal.await()
        assertThat(completed.active.value).isNull()
        assertThat(snapshot.phase).isEqualTo(GenerationPhase.COMPLETED)
        assertThat(completed.acknowledgeTerminal(snapshot.taskId)).isTrue()
        assertThat(completed.observe("ok").value).isNull()
        completed.release("ok")
        assertThat(completed.retainedSessionStateCount()).isEqualTo(0)
    }

    @Test
    fun `异常终态在确认前可恢复且确认后不污染新VM`() = runTest {
        val failed = coordinator { _, _ -> throw IllegalStateException("boom") }
        failed.start(request("bad", "a2"))
        runCurrent()

        assertThat(failed.active.value).isNull()
        val terminal = failed.observe("bad").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(terminal.error?.technical).isEqualTo("boom")
        assertThat(terminal.error?.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failed.acknowledgeTerminal(terminal.taskId)).isTrue()
        assertThat(failed.observe("bad").value).isNull()
    }

    @Test
    fun `runner异常终态同步结束展示层的生成状态`() = runTest {
        val presentationStore = GenerationPresentationStore()
        val coordinator = DefaultGenerationCoordinator(
            applicationScope = backgroundScope,
            presentationStore = presentationStore,
            runnerFactory = GenerationRunnerFactory { _, _ ->
                GenerationRunner { _, emit ->
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.THINKING))
                    throw IllegalStateException("boom")
                }
            },
            taskIdFactory = { "fallback-terminal" },
        )

        coordinator.start(request("stale-ui", "assistant"))
        runCurrent()

        val presentation = presentationStore.observe("stale-ui").value
        assertThat(presentation?.phase).isEqualTo(GenerationPhase.FAILED)
        assertThat(presentation?.generating).isFalse()
        assertThat(presentation?.error?.technical).isEqualTo("boom")
    }

    @Test
    fun `同会话确认首轮终态后原订阅必须收到第二轮状态`() = runTest {
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.THINKING))
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        }
        val observed = coordinator.observe("repeat")

        coordinator.start(request("repeat", "assistant-1"))
        runCurrent()
        val first = observed.value!!
        assertThat(coordinator.acknowledgeTerminal(first.taskId)).isTrue()
        assertThat(observed.value).isNull()

        coordinator.start(request("repeat", "assistant-2"))
        runCurrent()

        assertThat(coordinator.observe("repeat")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isNotEqualTo(first.taskId)
        assertThat(observed.value?.phase).isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `未确认终态有界保留且释放会话后map不增长`() = runTest {
        val coordinator = coordinator(terminalRetentionLimit = 3) { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        }

        repeat(20) { index ->
            coordinator.start(request("session-$index", "assistant-$index"))
            runCurrent()
        }

        assertThat(coordinator.retainedSessionStateCount()).isAtMost(3)
        repeat(20) { index -> coordinator.release("session-$index", discardTerminal = true) }
        assertThat(coordinator.retainedSessionStateCount()).isEqualTo(0)
    }

    @Test
    fun `路由handled失败携带设置入口状态直至显式确认且不重复终态写入`() = runTest {
        val presentationStore = GenerationPresentationStore()
        val handledFailure = GenerationFailure.unknown(technical = "请配置 API Key")
        val failure = ProviderResolution.Failure(
            reason = ProviderResolutionError.API_KEY_MISSING,
            modelId = "provider::model",
            providerId = "provider",
        )
        val coordinator = DefaultGenerationCoordinator(
            applicationScope = backgroundScope,
            presentationStore = presentationStore,
            runnerFactory = GenerationRunnerFactory { request, taskId ->
                GenerationRunner { _, emit ->
                    presentationStore.port(request.sessionId, taskId).setProviderFailure(failure)
                    presentationStore.port(request.sessionId, taskId).setError(handledFailure)
                    emit(GenerationEvent.Rejected(handledFailure))
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.FAILED))
                }
            },
            taskIdFactory = { "route-task" },
        )

        coordinator.start(request("route-session", "assistant"))
        runCurrent()

        val terminal = coordinator.observe("route-session").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(terminal.error?.technical).isEqualTo("请配置 API Key")
        assertThat(presentationStore.observe("route-session").value?.providerFailure).isEqualTo(failure)
        assertThat(coordinator.acknowledgeTerminal(terminal.taskId)).isTrue()
        assertThat(coordinator.observe("route-session").value).isNull()
        assertThat(presentationStore.observe("route-session").value).isNull()
    }

    @Test
    fun `持久化失败不得被runner后续原业务异常覆盖成普通FAILED`() = runTest {
        val original = IllegalStateException("network")
        val persistence = IllegalStateException("missing row")
        val persistenceFailure = GenerationFailure.persistence(
            technical = "missing row",
            cause = persistence,
        )
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PersistenceFailed(persistence, original, persistenceFailure))
            throw original
        }

        coordinator.start(request("durability", "assistant"))
        runCurrent()

        val terminal = coordinator.observe("durability").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(terminal.error?.cause).isSameInstanceAs(persistence)
    }

    private fun TestScope.coordinator(
        terminalRetentionLimit: Int = 32,
        run: suspend (GenerationRequest, suspend (GenerationEvent) -> Unit) -> Unit,
    ) = DefaultGenerationCoordinator(
        applicationScope = backgroundScope,
        runnerFactory = GenerationRunnerFactory { _, _ -> GenerationRunner { request, emit -> run(request, emit) } },
        taskIdFactory = { "task-${nextTaskId.incrementAndGet()}" },
        terminalRetentionLimit = terminalRetentionLimit,
    )

    private fun request(sessionId: String, assistantId: String) = GenerationRequest(
        sessionId = sessionId,
        assistantMessageId = assistantId,
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private companion object {
        val nextTaskId = AtomicInteger()
    }
}
