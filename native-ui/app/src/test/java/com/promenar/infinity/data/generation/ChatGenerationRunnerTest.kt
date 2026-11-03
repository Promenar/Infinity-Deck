package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationToolDecision
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolBudget
import com.promenar.infinity.domain.generation.GenerationToolUsage
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.CompletionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Test

class ChatGenerationRunnerTest {
    @Test
    fun `工具结果提交后插队注入同一循环并继续生成`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.ToolCall("call", "search", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ), flowOf(GenerationChunk.Text("不应续写"), GenerationChunk.Completed(CompletionReason.END_TURN))))
        runtime.toolDecisions += GenerationToolDecision.CONTINUE
        runtime.yieldAtBoundary = true
        val events = mutableListOf<GenerationEvent>()
        ChatGenerationRunner(runtime).run(request(), events::add)
        assertThat(runtime.streamAttempts).isEqualTo(2)
        assertThat(runtime.handledToolRounds).isEqualTo(1)
        assertThat(runtime.nextAssistantId).isEqualTo("assistant-next")
        assertThat(runtime.persisted.last().content).isEqualTo("不应续写")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(runtime.yieldChecks).isEqualTo(2)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `纯文字结束边界同样注入插队`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(GenerationChunk.Text("完成"), GenerationChunk.Completed(CompletionReason.END_TURN)),
            flowOf(GenerationChunk.Text("新指令回复"), GenerationChunk.Completed(CompletionReason.END_TURN))))
        runtime.yieldAtBoundary = true
        ChatGenerationRunner(runtime).run(request()) {}
        assertThat(runtime.yieldChecks).isEqualTo(2)
        assertThat(runtime.streamAttempts).isEqualTo(2)
        assertThat(runtime.persisted.last().content).isEqualTo("新指令回复")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `显式完成后上游迟到的失败分片不得污染成功终态`() = runTest {
        val lateFailure = GenerationFailure(
            code = GenerationFailureCode.NETWORK,
            technical = "late provider failure",
        )
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
            GenerationChunk.Failure(lateFailure),
        )))
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.persisted.last().content).isEqualTo("done")
        assertThat(runtime.persisted.last().failure).isNull()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `固定阶段顺序且每个流分片持久化并在完成时flush`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("A"),
            GenerationChunk.Text("B"),
            GenerationChunk.Thinking("R"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING,
            GenerationPhase.THINKING,
            GenerationPhase.STREAMING,
            GenerationPhase.POST_PROCESSING,
            GenerationPhase.COMPLETED,
        ).inOrder()
        assertThat(runtime.persisted.map { it.content to it.reasoning })
            .containsExactly("A" to "", "AB" to "", "AB" to "R").inOrder()
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `工具等待完成后继续下一轮流`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("tool-1", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")),
            ),
            flowOf(GenerationChunk.Text("continued"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply { toolDecisions += GenerationToolDecision.CONTINUE }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING, GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING, GenerationPhase.THINKING,
            GenerationPhase.EXECUTING_TOOLS,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING, GenerationPhase.THINKING, GenerationPhase.STREAMING,
            GenerationPhase.POST_PROCESSING, GenerationPhase.COMPLETED,
        ).inOrder()
        assertThat(runtime.streamAttempts).isEqualTo(2)
        assertThat(runtime.persisted.last().toolCalls).isEmpty()
        assertThat(runtime.persisted.last().failure).isNull()
        assertThat(runtime.flushCount).isEqualTo(2)
        assertThat(events.filterIsInstance<GenerationEvent.TargetChanged>().map { it.assistantMessageId })
            .containsExactly("assistant-next")
    }

    @Test
    fun `等待人工工具审批时flush并停在等待阶段`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("tool-1", "write", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")),
            ),
        )).apply { toolDecisions += GenerationToolDecision.WAIT_FOR_APPROVAL }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases().takeLast(2))
            .containsExactly(GenerationPhase.EXECUTING_TOOLS, GenerationPhase.WAITING_APPROVAL)
            .inOrder()
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
        assertThat(runtime.resourceReleases).containsExactly(true)
        assertThat(runtime.flushCountsAtRelease).containsExactly(1)
    }

    @Test
    fun `等待用户回答时flush并停在等待阶段`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("tool-1", "ask_user", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")),
            ),
        )).apply { toolDecisions += GenerationToolDecision.WAIT_FOR_INPUT }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases().takeLast(2))
            .containsExactly(GenerationPhase.EXECUTING_TOOLS, GenerationPhase.WAITING_INPUT).inOrder()
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
        assertThat(runtime.resourceReleases).containsExactly(true)
        assertThat(runtime.flushCountsAtRelease).containsExactly(1)
    }

    @Test
    fun `已有部分文本后收到Failure仍落ERROR且不进入完成态`() = runTest {
        val streamFailure = GenerationFailure(
            code = GenerationFailureCode.TIMEOUT,
            formatArgs = emptyMap(),
            technical = "stream broken",
        )
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("partial"),
            GenerationChunk.Failure(streamFailure),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))
        val events = mutableListOf<GenerationEvent>()

        val executionFailure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(executionFailure).isInstanceOf(GenerationFailedException::class.java)
        assertThat((executionFailure as GenerationFailedException).failure).isSameInstanceAs(streamFailure)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
        assertThat(runtime.persisted.last().content).isEqualTo("partial")
        assertThat(runtime.persisted.last().failure).isEqualTo(streamFailure)
        assertThat(runtime.persisted.last().failure?.code).isEqualTo(GenerationFailureCode.TIMEOUT)
        assertThat(runtime.persisted.last().failure?.technical).isEqualTo("stream broken")
    }

    @Test
    fun `失败时标记FAILED并flush`() = runTest {
        val runtime = FakeRuntime(listOf(flow { throw IllegalStateException("network") }))
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.FAILED)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `业务异常后终态持久化失败发布PERSISTENCE_FAILED且保留原异常`() = runTest {
        val original = IllegalStateException("network")
        val runtime = FakeRuntime(listOf(flow { throw original })).apply {
            terminalFailure = IllegalStateException("terminal db")
        }
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        val persistence = events.filterIsInstance<GenerationEvent.PersistenceFailed>().single()
        assertThat(persistence.failure.code).isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(persistence.failure.technical).isEqualTo("terminal db")
        assertThat(persistence.originalCause).isSameInstanceAs(original)
        assertThat(persistence.persistenceCause.message).isEqualTo("terminal db")
        assertThat(persistence.persistenceCause.suppressed.asList()).contains(original)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(GenerationFailureCodec.encode(persistence.failure))
            .doesNotContain("terminal db")
        assertThat(events.phases()).doesNotContain(GenerationPhase.FAILED)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `成功结果终态持久化失败不得发布COMPLETED`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        ))).apply {
            terminalFailure = IllegalStateException("missing row")
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>().single().failure.code)
            .isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>().single().failure.technical)
            .isEqualTo("missing row")
    }

    @Test
    fun `CONTINUE轮次强刷重试仍失败时停止下一轮并发布PERSISTENCE_FAILED`() = runTest {
        val runtime = FakeRuntime(
            listOf(flowOf(
                GenerationChunk.ToolCall("call", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
            )),
        ).apply {
            toolDecisions += GenerationToolDecision.CONTINUE
            flushFailuresRemaining = 2
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.streamAttempts).isEqualTo(1)
        assertThat(runtime.flushCount).isEqualTo(2)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `续轮上下文已处理失败时释放工具资源且不留下审批租约`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.ToolCall("call", "search", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))).apply {
            toolDecisions += GenerationToolDecision.CONTINUE
            preparationOutcomes += GenerationPreparationOutcome.Ready
            preparationOutcomes += GenerationPreparationOutcome.Handled(GenerationFailure.unknown("continuation"))
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.streamAttempts).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.FAILED)
        assertThat(runtime.resourceReleases).containsExactly(false)
        assertThat(runtime.flushCount).isEqualTo(2)
    }

    @Test
    fun `审批等待持久化失败时释放工具资源而不暂停保留`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.ToolCall("call", "write", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))).apply {
            toolDecisions += GenerationToolDecision.WAIT_FOR_APPROVAL
            flushFailuresRemaining = 1
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).contains(GenerationPhase.WAITING_APPROVAL)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(runtime.resourceReleases).containsExactly(false)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `审批持久化失败后释放错误附加到原持久化失败`() = runTest {
        val releaseFailure = IllegalStateException("resource-release")
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.ToolCall("call", "write", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))).apply {
            toolDecisions += GenerationToolDecision.WAIT_FOR_APPROVAL
            flushFailuresRemaining = 1
            resourceReleaseFailure = releaseFailure
        }
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isNull()
        val persistence = events.filterIsInstance<GenerationEvent.PersistenceFailed>().single()
        assertThat(persistence.persistenceCause.suppressed.toList()).contains(releaseFailure)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `审批强刷期间用户取消后释放资源而不留下等待租约`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.ToolCall("call", "write", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))).apply {
            toolDecisions += GenerationToolDecision.WAIT_FOR_APPROVAL
            suspendFlush = true
        }
        val events = mutableListOf<GenerationEvent>()
        val job = launch { ChatGenerationRunner(runtime).run(request(), events::add) }
        yield()
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.flushCompleted).isFalse()

        job.cancel()
        job.join()

        assertThat(runtime.flushCompleted).isTrue()
        assertThat(runtime.resourceReleases).containsExactly(false)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.CANCELLED)
    }

    @Test
    fun `释放失败不得跳过最终flush或覆盖用户取消`() = runTest {
        val original = CancellationException("user-cancelled")
        val releaseFailure = IllegalStateException("resource-release")
        val runtime = FakeRuntime(listOf(flow { throw original })).apply {
            terminalFailure = CancellationException("terminal-cleanup")
            resourceReleaseFailure = releaseFailure
        }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        assertThat(original.suppressed.toList()).contains(releaseFailure)
        assertThat(runtime.resourceReleases).containsExactly(false)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.flushCompleted).isTrue()
    }

    @Test
    fun `释放失败只作为原生成失败的suppressed`() = runTest {
        val original = IllegalStateException("provider-failure")
        val releaseFailure = IllegalStateException("resource-release")
        val runtime = FakeRuntime(listOf(flow { throw original })).apply {
            resourceReleaseFailure = releaseFailure
        }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        assertThat(original.suppressed.toList()).contains(releaseFailure)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `成功生成后的释放错误在完成flush后向上传播`() = runTest {
        val releaseFailure = IllegalStateException("resource-release")
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("answer"), GenerationChunk.Completed(CompletionReason.END_TURN),
        ))).apply { resourceReleaseFailure = releaseFailure }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(generateSequence(failure) { it.cause }.last()).isSameInstanceAs(releaseFailure)
        assertThat(runtime.flushCompleted).isTrue()
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `失败与取消时observer异常只作为suppressed且不阻断flush`() = runTest {
        val originalFailure = IllegalStateException("network")
        val failedRuntime = FakeRuntime(listOf(flow { throw originalFailure }))
        val failed = runCatching {
            ChatGenerationRunner(failedRuntime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.FAILED) {
                    throw IllegalStateException("failed observer")
                }
            }
        }.exceptionOrNull()
        assertThat(failed).isSameInstanceAs(originalFailure)
        assertThat(failed!!.suppressed.map { it.message }).contains("failed observer")
        assertThat(failedRuntime.flushCount).isEqualTo(1)

        val cancelledRuntime = FakeRuntime(listOf(flow { throw CancellationException("cancel") }))
        val cancelled = runCatching {
            ChatGenerationRunner(cancelledRuntime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.CANCELLED) {
                    throw IllegalStateException("cancel observer")
                }
            }
        }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(cancelled!!.suppressed.map { it.message }).contains("cancel observer")
        assertThat(cancelledRuntime.flushCount).isEqualTo(1)
    }

    @Test
    fun `observer 自身抛出 CancellationException 时必须原样传播`() = runTest {
        val observerCancellation = CancellationException("observer cancelled")
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))

        val failure = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event == GenerationEvent.PhaseChanged(GenerationPhase.PREPARING)) {
                    throw observerCancellation
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(observerCancellation)
        assertThat(runtime.providerCancelCount).isEqualTo(1)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `SUCCESS后COMPLETED observer异常不得重标ERROR`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))

        val failure = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.COMPLETED) {
                    throw IllegalStateException("observer gone")
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isNull()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `成功终态严格按mark再flush再COMPLETED发布`() = runTest {
        val trace = mutableListOf<String>()
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )), trace)

        ChatGenerationRunner(runtime).run(request()) { event ->
            if (event is GenerationEvent.PhaseChanged) trace += "phase:${event.phase}"
        }

        assertThat(trace.takeLast(3)).containsExactly(
            "terminal:SUCCESS",
            "flush",
            "phase:COMPLETED",
        ).inOrder()
    }

    @Test
    fun `上下文或路由错误已由adapter处理时停止连接并flush`() = runTest {
        val handledFailure = GenerationFailure.unknown(technical = "handled failure")
        val runtime = FakeRuntime(listOf(flowOf(GenerationChunk.Completed(CompletionReason.END_TURN)))).apply {
            preparationOutcome = GenerationPreparationOutcome.Handled(handledFailure)
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING, GenerationPhase.BUILDING_CONTEXT, GenerationPhase.FAILED,
        ).inOrder()
        val rejected = events.filterIsInstance<GenerationEvent.Rejected>().single()
        assertThat(rejected.failure.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(rejected.failure.technical).isEqualTo("handled failure")
        assertThat(GenerationFailureCodec.encode(rejected.failure))
            .doesNotContain("handled failure")
        assertThat(runtime.streamAttempts).isEqualTo(0)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `取消传递到provider Flow并标记CANCELLED且flush`() = runTest {
        var providerCancelled = false
        val cancellingFlow = flow<GenerationChunk> {
            try {
                emit(GenerationChunk.Text("partial"))
                throw CancellationException("stop")
            } finally {
                providerCancelled = true
            }
        }
        val runtime = FakeRuntime(listOf(cancellingFlow))
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(providerCancelled).isTrue()
        assertThat(runtime.providerCancelCount).isEqualTo(1)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.persisted.last().content).isEqualTo("partial")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.CANCELLED)
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `外部取消时挂起中的终态flush在NonCancellable内完成`() = runTest {
        val runtime = FakeRuntime(listOf(flow { awaitCancellation() })).apply {
            suspendFlush = true
            suspendResourceRelease = true
        }
        val events = mutableListOf<GenerationEvent>()
        val job = launch { ChatGenerationRunner(runtime).run(request(), events::add) }
        yield()

        job.cancel()
        job.join()

        assertThat(runtime.flushCompleted).isTrue()
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(runtime.resourceReleaseCompleted).isTrue()
        assertThat(runtime.resourceReleases).containsExactly(false)
    }

    @Test
    fun `审批前已消耗的工具轮次计入预算，恢复后首个超额轮即转入收尾`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("call-1", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-1")),
            ),
            flowOf(
                GenerationChunk.Text("summary"),
                GenerationChunk.Completed(CompletionReason.END_TURN),
            ),
        )).apply {
            budget = GenerationToolBudget(maxRounds = 2)
            consumed = GenerationToolUsage(rounds = 2, calls = 2)
        }

        ChatGenerationRunner(runtime).run(request()) {}

        assertThat(runtime.rejectedCalls).containsExactly("call-1")
        assertThat(runtime.handledToolRounds).isEqualTo(0)
        assertThat(runtime.finalAnswerModeEntered).isTrue()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `工具轮次后空正文结束时提醒一次并在同一回复上重新请求`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(GenerationChunk.ToolCall("tool-1", "search", "{}"), GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1"))),
            flowOf(GenerationChunk.Completed(CompletionReason.END_TURN)),
            flowOf(GenerationChunk.Text("结果如下"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply {
            toolDecisions += GenerationToolDecision.CONTINUE
            replyReminderSupported = true
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.streamAttempts).isEqualTo(3)
        assertThat(runtime.replyReminders).isEqualTo(1)
        assertThat(runtime.persisted.last().content).isEqualTo("结果如下")
        // 提醒重发沿用续轮创建的回复，不再新建占位。
        assertThat(events.filterIsInstance<GenerationEvent.TargetChanged>().map { it.assistantMessageId })
            .containsExactly("assistant-next")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `空正文提醒每次生成只发一次，无工具轮次或不支持时不提醒`() = runTest {
        val toolRound = flowOf(GenerationChunk.ToolCall("tool-1", "search", "{}"), GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")))
        val empty = { flowOf<GenerationChunk>(GenerationChunk.Completed(CompletionReason.END_TURN)) }
        val twice = FakeRuntime(listOf(toolRound, empty(), empty())).apply {
            toolDecisions += GenerationToolDecision.CONTINUE
            replyReminderSupported = true
        }
        val failed = runCatching { ChatGenerationRunner(twice).run(request()) {} }.exceptionOrNull()
        assertThat((failed as GenerationFailedException).failure.code).isEqualTo(GenerationFailureCode.EMPTY_REPLY)
        assertThat(twice.streamAttempts).isEqualTo(3)
        assertThat(twice.replyReminders).isEqualTo(1)
        assertThat(twice.terminals).containsExactly(GenerationTerminalStatus.ERROR)

        val unsupported = FakeRuntime(listOf(toolRound, empty())).apply { toolDecisions += GenerationToolDecision.CONTINUE }
        ChatGenerationRunner(unsupported).run(request()) {}
        assertThat(unsupported.streamAttempts).isEqualTo(2)

        val textOnly = FakeRuntime(listOf(flowOf(GenerationChunk.Text("完成"), GenerationChunk.Completed(CompletionReason.END_TURN))))
            .apply { replyReminderSupported = true }
        ChatGenerationRunner(textOnly).run(request()) {}
        assertThat(textOnly.replyReminders).isEqualTo(0)
    }

    @Test
    fun `收尾轮仍只返回工具调用时以工具预算失败结束而非空回答成功`() = runTest {
        val toolRound = { id: String ->
            flowOf(
                GenerationChunk.ToolCall(id, "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf(id)),
            )
        }
        val runtime = FakeRuntime(listOf(toolRound("call-1"), toolRound("call-2"))).apply {
            budget = GenerationToolBudget(maxRounds = 1)
            consumed = GenerationToolUsage(rounds = 1, calls = 1)
        }
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
        assertThat((failure as GenerationFailedException).failure.code)
            .isEqualTo(GenerationFailureCode.TOOL_BUDGET_EXHAUSTED)
        assertThat(runtime.rejectedCalls).containsExactly("call-1", "call-2").inOrder()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    @Test
    fun `不限模式允许超过固定轮次与调用上限后执行工具`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("call-unlimited", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-unlimited")),
            ),
            flowOf(GenerationChunk.Text("done"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply {
            budget = GenerationToolBudget(maxRounds = 0, maxCalls = 0)
            consumed = GenerationToolUsage(rounds = 1_000, calls = 10_000)
            toolDecisions += GenerationToolDecision.CONTINUE
        }
        ChatGenerationRunner(runtime).run(request()) {}
        assertThat(runtime.handledToolRounds).isEqualTo(1)
        assertThat(runtime.rejectedCalls).isEmpty()
        assertThat(runtime.finalAnswerModeEntered).isFalse()
    }

    @Test
    fun `流式用量按轮累计并在token预算达到时拒绝工具进入收尾`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.Usage(10, 10, 20),
                GenerationChunk.Usage(50, 50, 100),
                GenerationChunk.ToolCall("budget-call", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("budget-call")),
            ),
            flowOf(GenerationChunk.Text("summary"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply { budget = GenerationToolBudget(maxTokens = 100) }
        val events = mutableListOf<GenerationEvent>()
        ChatGenerationRunner(runtime).run(request(), events::add)
        assertThat(runtime.rejectedCalls).containsExactly("budget-call")
        assertThat(runtime.handledToolRounds).isEqualTo(0)
        assertThat(runtime.finalAnswerModeEntered).isTrue()
        val progress = events.filterIsInstance<GenerationEvent.ProgressChanged>()
        assertThat(progress.any { it.progress.tokens == 100L && !it.progress.estimated }).isTrue()
    }

    @Test
    fun `无Usage时估算预算仍进入收尾且显示估算`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.Text("long".repeat(100)),
                GenerationChunk.ToolCall("estimated-call", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("estimated-call")),
            ),
            flowOf(GenerationChunk.Text("summary"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply { budget = GenerationToolBudget(maxTokens = 50) }
        val events = mutableListOf<GenerationEvent>()
        ChatGenerationRunner(runtime).run(request(), events::add)
        assertThat(runtime.finalAnswerModeEntered).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.ProgressChanged>().last().progress.estimated).isTrue()
    }

    @Test
    fun `重复调用停止时不执行触发调用并保持结果配对的拒绝路径`() = runTest {
        val streams = (1..3).map { n -> flowOf(
            GenerationChunk.ToolCall("repeat-$n", "search", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("repeat-$n")),
        ) }
        val runtime = FakeRuntime(streams).apply {
            repeat(3) { toolDecisions += GenerationToolDecision.CONTINUE }
        }
        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(runtime.handledToolRounds).isEqualTo(2)
        assertThat(runtime.rejectedCalls).containsExactly("repeat-3")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.CANCELLED)
    }

    @Test
    fun `继续重复调用后重新计数且累计用量不重复累加同轮Usage`() = runTest {
        val streams = (1..6).map { n -> flowOf(
            GenerationChunk.Usage(1, 1, 2), GenerationChunk.Usage(5, 5, 10),
            GenerationChunk.ToolCall("repeat-$n", "search", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("repeat-$n")),
        ) } + flowOf(GenerationChunk.Text("done"), GenerationChunk.Completed(CompletionReason.END_TURN))
        val runtime = FakeRuntime(streams).apply {
            loopContinue = true
            repeat(6) { toolDecisions += GenerationToolDecision.CONTINUE }
        }
        val events = mutableListOf<GenerationEvent>()
        ChatGenerationRunner(runtime).run(request(), events::add)
        assertThat(runtime.loopPrompts).isEqualTo(2)
        assertThat(runtime.handledToolRounds).isEqualTo(6)
        assertThat(events.filterIsInstance<GenerationEvent.ProgressChanged>().any {
            it.progress.tokens == 60L && !it.progress.estimated
        }).isTrue()
    }

    @Test
    fun `后续Usage只报告输出时保留已报告输入并按合计触发预算`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.Usage(100, 1, 101),
                GenerationChunk.Usage(0, 5, 0),
                GenerationChunk.ToolCall("usage-partial", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("usage-partial")),
            ),
            flowOf(GenerationChunk.Text("summary"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply { budget = GenerationToolBudget(maxTokens = 105) }
        ChatGenerationRunner(runtime).run(request()) {}
        assertThat(runtime.rejectedCalls).containsExactly("usage-partial")
        assertThat(runtime.handledToolRounds).isEqualTo(0)
        assertThat(runtime.finalAnswerModeEntered).isTrue()
    }

    private fun request() = GenerationRequest(
        sessionId = "session",
        assistantMessageId = "assistant",
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private fun List<GenerationEvent>.phases() = filterIsInstance<GenerationEvent.PhaseChanged>().map { it.phase }

    private class FakeRuntime(
        private val streams: List<Flow<GenerationChunk>>,
        private val trace: MutableList<String> = mutableListOf(),
    ) : ChatGenerationRuntime {
        val persisted = mutableListOf<GenerationSnapshot>()
        val toolDecisions = ArrayDeque<GenerationToolDecision>()
        var yieldAtBoundary = false
        var yieldChecks = 0
        override suspend fun injectSteering(request: GenerationRequest): GenerationRequest? {
            yieldChecks++
            if (!yieldAtBoundary) return null
            yieldAtBoundary = false
            return request.copy(userMessageId = "steered-user", userContent = "新指令")
        }
        var streamAttempts = 0
        var flushCount = 0
        var providerCancelCount = 0
        var suspendFlush = false
        var flushCompleted = false
        var preparationOutcome: GenerationPreparationOutcome = GenerationPreparationOutcome.Ready
        val preparationOutcomes = ArrayDeque<GenerationPreparationOutcome>()
        val resourceReleases = mutableListOf<Boolean>()
        val flushCountsAtRelease = mutableListOf<Int>()
        var resourceReleaseFailure: Throwable? = null
        var suspendResourceRelease = false
        var resourceReleaseCompleted = false
        val terminals = mutableListOf<GenerationTerminalStatus>()
        var terminalFailure: Throwable? = null
        var flushFailuresRemaining = 0
        var nextAssistantId: String? = "assistant-next"
        var budget = GenerationToolBudget()
        var consumed = GenerationToolUsage()
        val rejectedCalls = mutableListOf<String>()
        var handledToolRounds = 0
        var finalAnswerModeEntered = false
        var replyReminderSupported = false
        var replyReminders = 0
        override fun requestReplyAfterEmptyTurn(request: GenerationRequest): Boolean {
            if (replyReminderSupported) replyReminders++
            return replyReminderSupported
        }
        var loopContinue = false
        var loopPrompts = 0
        override suspend fun confirmLoopContinuation(request: GenerationRequest): Boolean {
            loopPrompts++
            return loopContinue
        }

        override fun toolBudget(request: GenerationRequest): GenerationToolBudget = budget
        override suspend fun consumedToolUsage(request: GenerationRequest): GenerationToolUsage = consumed
        override suspend fun rejectToolCalls(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
            reason: String,
        ) {
            rejectedCalls += toolCalls.map { it.id }
        }
        override fun enterFinalAnswerMode(request: GenerationRequest) {
            finalAnswerModeEntered = true
        }

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) =
            preparationOutcomes.removeFirstOrNull() ?: preparationOutcome
        override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> {
            streamAttempts++
            return streams[attempt]
        }
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
            persisted += snapshot
        }
        override fun knownToolNames(request: GenerationRequest): Set<String> =
            setOf("search", "write", "read_file")
        override suspend fun handleTools(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
        ): GenerationToolDecision {
            handledToolRounds++
            return toolDecisions.removeFirstOrNull() ?: GenerationToolDecision.COMPLETE
        }
        override suspend fun prepareContinuation(request: GenerationRequest): String? =
            nextAssistantId.also { nextAssistantId = null }
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
            trace += "terminal:$status"
            terminalFailure?.let { throw it }
        }
        override suspend fun flush(request: GenerationRequest) {
            flushCount++
            trace += "flush"
            if (flushFailuresRemaining > 0) {
                flushFailuresRemaining--
                throw IllegalStateException("flush db")
            }
            if (suspendFlush) yield()
            flushCompleted = true
        }
        override suspend fun releaseToolResources(request: GenerationRequest, awaitingApproval: Boolean) {
            resourceReleases += awaitingApproval
            flushCountsAtRelease += flushCount
            if (suspendResourceRelease) {
                yield()
                currentCoroutineContext().ensureActive()
            }
            resourceReleaseFailure?.let { throw it }
            resourceReleaseCompleted = true
        }
        override fun cancelProvider() { providerCancelCount++ }
    }
}
