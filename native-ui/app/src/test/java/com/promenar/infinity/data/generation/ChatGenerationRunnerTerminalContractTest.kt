package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.GenerationToolBudget
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatGenerationRunnerTerminalContractTest {
    @Test fun `短时并发清理等待空位后可以正常完成`() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val completed = mutableListOf<Int>()
        repeat(12) { index ->
            launch {
                awaitGenerationCleanup("flushOnce", "s", 100, dispatcher) { delay(10) }
                completed += index
            }
        }
        advanceUntilIdle()
        assertThat(completed).containsExactlyElementsIn(0..11)
    }

    @Test fun `释放和刷盘不响应取消时仍发布取消终态并归还协调器会话`() = runTest {
        val blocked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cleanupCalls = mutableListOf<String>()
        val runtime = object : ChatGenerationRuntime by RuntimeFixture(listOf(flow { kotlinx.coroutines.awaitCancellation() }), knownTools = emptySet()) {
            override suspend fun flush(request: GenerationRequest) {
                cleanupCalls += "flush"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { blocked.await() }
            }
            override suspend fun releaseToolResources(request: GenerationRequest, awaitingApproval: Boolean) {
                cleanupCalls += "release"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { blocked.await() }
            }
        }
        val runner = ChatGenerationRunner(runtime, cleanupTimeoutMs = 20,
            cleanupDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val coordinator = DefaultGenerationCoordinator(backgroundScope,
            runnerFactory = GenerationRunnerFactory { _, _ -> runner })
        val request = request()
        try {
            val task = coordinator.start(request) as com.promenar.infinity.domain.generation.StartGenerationResult.Started
            kotlinx.coroutines.yield()
            assertThat(coordinator.cancel(task.taskId)).isTrue()
            val completed = coordinator.awaitTaskCompletion(request.sessionId, task.taskId)
            assertThat(completed?.phase).isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED)
            coordinator.awaitSessionIdle(request.sessionId)
            assertThat(coordinator.isSessionBusy(request.sessionId)).isFalse()
            assertThat(cleanupCalls).containsExactly("flush", "release")
        } finally {
            blocked.complete(Unit)
        }
    }

    @Test
    fun `Error EOF 与取消都会持久化清除未确认工具后再标终态`() = runTest {
        val providerFailure = com.promenar.infinity.domain.generation.GenerationFailure(
            com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
        )
        val cases = listOf(
            RuntimeFixture(
                listOf(flowOf(
                    GenerationChunk.ToolCall("error", "search", "{}"),
                    GenerationChunk.Failure(providerFailure),
                )),
                knownTools = setOf("search"),
            ) to GenerationTerminalStatus.ERROR,
            RuntimeFixture(
                listOf(flowOf(GenerationChunk.ToolCall("eof", "search", "{}"))),
                knownTools = setOf("search"),
            ) to GenerationTerminalStatus.ERROR,
            RuntimeFixture(
                listOf(flow {
                    emit(GenerationChunk.ToolCall("cancel", "search", "{}"))
                    throw CancellationException("cancel")
                }),
                knownTools = setOf("search"),
            ) to GenerationTerminalStatus.CANCELLED,
        )

        cases.forEach { (runtime, expectedTerminal) ->
            val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(runtime.handled).isEmpty()
            assertThat(runtime.persisted.last().toolCalls).isEmpty()
            assertThat(runtime.terminals).containsExactly(expectedTerminal)
            assertThat(runtime.terminalSnapshots.single().toolCalls).isEmpty()
        }
    }

    @Test
    fun `取消后的工具清理在NonCancellable完成且传播原取消异常`() = runTest {
        val original = CancellationException("original")
        val runtime = RuntimeFixture(
            listOf(flow {
                emit(GenerationChunk.ToolCall("cancel", "search", "{}"))
                throw original
            }),
            knownTools = setOf("search"),
        ).apply { suspendCleanPersist = true }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        assertThat(runtime.cleanPersistCompleted).isTrue()
        assertThat(runtime.persisted.last().toolCalls).isEmpty()
        assertThat(runtime.terminalSnapshots.single().toolCalls).isEmpty()
    }

    @Test
    fun `截断工具流没有 Completed 时不得进入审批或执行`() = runTest {
        val runtime = RuntimeFixture(
            listOf(flowOf(GenerationChunk.ToolCall("call", "search", "{}"))),
            knownTools = setOf("search"),
        )

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
        assertThat(runtime.handled).isEmpty()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    @Test
    fun `TOOL_CALLS completed IDs 不匹配与数组参数都失败关闭`() = runTest {
        val mismatch = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "{}"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("other")),
                ),
            ),
            knownTools = setOf("search"),
        )
        val malformed = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "[]"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
                ),
            ),
            knownTools = setOf("search"),
        )

        assertThat(runCatching { ChatGenerationRunner(mismatch).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(runCatching { ChatGenerationRunner(malformed).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(mismatch.handled).isEmpty()
        assertThat(malformed.handled).isEmpty()
    }

    @Test
    fun `END_TURN 不能携带工具`() = runTest {
        val endTurnWithTool = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "{}"),
                    GenerationChunk.Completed(CompletionReason.END_TURN),
                ),
            ),
            knownTools = setOf("search"),
        )

        assertThat(runCatching { ChatGenerationRunner(endTurnWithTool).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(endTurnWithTool.handled).isEmpty()
    }

    @Test
    fun `未知工具名交给运行时回写可纠错结果而不是终止生成`() = runTest {
        val unknown = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "unknown", "{}"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
                ),
                flowOf(
                    GenerationChunk.Text("已改用直接回答"),
                    GenerationChunk.Completed(CompletionReason.END_TURN),
                ),
            ),
            knownTools = setOf("search"),
        ).apply { decisions += GenerationToolDecision.CONTINUE }

        ChatGenerationRunner(unknown).run(request()) {}

        assertThat(unknown.handled.single().single().name).isEqualTo("unknown")
        assertThat(unknown.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `空参数串规范化为空对象后再交给运行时`() = runTest {
        val runtime = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", ""),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
                ),
            ),
            knownTools = setOf("search"),
        )

        ChatGenerationRunner(runtime).run(request()) {}

        assertThat(runtime.handled.single().single().arguments).isEqualTo("{}")
    }

    @Test
    fun `工具调用预算耗尽时拒绝本轮调用并进入总结轮`() = runTest {
        fun round(prefix: String, count: Int): Flow<GenerationChunk> {
            val calls = (1..count).map { GenerationChunk.ToolCall("$prefix-$it", "search", "{\"query\":\"$it\"}") }
            return flowOf(
                *(calls + GenerationChunk.Completed(
                    CompletionReason.TOOL_CALLS,
                    calls.map { it.id },
                )).toTypedArray(),
            )
        }
        val runtime = RuntimeFixture(
            listOf(
                round("a", 6),
                round("b", 5),
                flowOf(GenerationChunk.Text("总结"), GenerationChunk.Completed(CompletionReason.END_TURN)),
            ),
            knownTools = setOf("search"),
        ).apply {
            budget = GenerationToolBudget(maxRounds = 50, maxCalls = 10)
            decisions += GenerationToolDecision.CONTINUE
        }

        ChatGenerationRunner(runtime).run(request()) {}

        assertThat(runtime.handled).hasSize(1)
        assertThat(runtime.handled.single()).hasSize(6)
        assertThat(runtime.rejected.single().map { it.id }).containsExactly("b-1", "b-2", "b-3", "b-4", "b-5")
        assertThat(runtime.finalAnswerEntered).isTrue()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `轮次预算耗尽后总结轮仍请求工具则拒绝并以预算失败结束`() = runTest {
        fun round(id: String): Flow<GenerationChunk> = flowOf(
            GenerationChunk.ToolCall(id, "search", "{}"),
            GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf(id)),
        )
        val runtime = RuntimeFixture(
            listOf(round("a"), round("b"), round("c")),
            knownTools = setOf("search"),
        ).apply {
            budget = GenerationToolBudget(maxRounds = 1, maxCalls = 10)
            decisions += GenerationToolDecision.CONTINUE
        }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(runtime.handled.map { calls -> calls.map { it.id } }).containsExactly(listOf("a"))
        assertThat(runtime.rejected.map { calls -> calls.map { it.id } })
            .containsExactly(listOf("b"), listOf("c")).inOrder()
        // 收尾轮仍只返回工具调用、没有正文：不能记为成功，以明确的预算失败呈现给用户。
        assertThat((failure as com.promenar.infinity.domain.generation.GenerationFailedException).failure.code)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationFailureCode.TOOL_BUDGET_EXHAUSTED)
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    private fun request() = GenerationRequest(
        "session", "assistant", "user", "hello", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private class RuntimeFixture(
        private val streams: List<Flow<GenerationChunk>>,
        private val knownTools: Set<String>,
    ) : ChatGenerationRuntime {
        val handled = mutableListOf<List<GenerationToolCall>>()
        val persisted = mutableListOf<GenerationSnapshot>()
        val terminals = mutableListOf<GenerationTerminalStatus>()
        val terminalSnapshots = mutableListOf<GenerationSnapshot>()
        val decisions = ArrayDeque<GenerationToolDecision>()
        val rejected = mutableListOf<List<GenerationToolCall>>()
        var budget = GenerationToolBudget()
        var finalAnswerEntered = false
        var suspendCleanPersist = false
        var cleanPersistCompleted = false

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = GenerationPreparationOutcome.Ready
        override suspend fun stream(request: GenerationRequest, attempt: Int) = streams[attempt]
        override fun knownToolNames(request: GenerationRequest): Set<String> = knownTools
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
            if (suspendCleanPersist && snapshot.toolCalls.isEmpty()) {
                kotlinx.coroutines.yield()
                cleanPersistCompleted = true
            }
            persisted += snapshot
        }
        override suspend fun handleTools(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
        ): GenerationToolDecision {
            handled += toolCalls
            return decisions.removeFirstOrNull() ?: GenerationToolDecision.COMPLETE
        }
        override fun toolBudget(request: GenerationRequest) = budget
        override suspend fun rejectToolCalls(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
            reason: String,
        ) {
            rejected += toolCalls
        }
        override fun enterFinalAnswerMode(request: GenerationRequest) {
            finalAnswerEntered = true
        }
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
            terminalSnapshots += snapshot
        }
        override suspend fun flush(request: GenerationRequest) = Unit
        override fun cancelProvider() = Unit
    }
}
