package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.GenerationTiming
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 每轮请求开始、首个输出与结束时间点，以及缓存/推理用量随快照写入每条助手消息。 */
class ChatGenerationRunnerTimingTest {
    private var now = 1_000L

    @Test
    fun `单轮记录请求开始 首个非空输出与结束`() = runTest {
        val runtime = TimingRuntime(listOf(flow {
            now = 1_100
            emit(GenerationChunk.Text(""))
            now = 1_250
            emit(GenerationChunk.Text("答"))
            now = 1_900
            emit(GenerationChunk.Text("案"))
            now = 2_000
            emit(GenerationChunk.Completed(CompletionReason.END_TURN))
        }))

        ChatGenerationRunner(runtime) { now }.run(request()) {}

        assertThat(runtime.recorded.single().timing).isEqualTo(GenerationTiming(1_000, 1_250, 2_000))
        assertThat(runtime.persisted.first().timing).isEqualTo(GenerationTiming(1_000))
    }

    @Test
    fun `思考与工具调用增量也算首个输出且每轮独立计时`() = runTest {
        val runtime = TimingRuntime(listOf(
            flow {
                now = 1_300
                emit(GenerationChunk.Thinking("想"))
                now = 1_400
                emit(GenerationChunk.ToolCall("c1", "search", "{}"))
                now = 1_500
                emit(GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("c1")))
            },
            flow {
                now = 4_200
                emit(GenerationChunk.ToolCall("c2", "search", "{}"))
                now = 4_300
                emit(GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("c2")))
            },
            flow {
                now = 6_350
                emit(GenerationChunk.Text("完成"))
                now = 6_400
                emit(GenerationChunk.Completed(CompletionReason.END_TURN))
            },
        ))
        runtime.onHandleTools = { now += 2_000 }

        ChatGenerationRunner(runtime) { now }.run(request()) {}

        assertThat(runtime.recorded.map { it.timing }).containsExactly(
            GenerationTiming(1_000, 1_300, 1_500),
            GenerationTiming(3_500, 4_200, 4_300),
            GenerationTiming(6_300, 6_350, 6_400),
        ).inOrder()
    }

    @Test
    fun `失败轮次记录结束时间`() = runTest {
        val failure = GenerationFailure(GenerationFailureCode.AUTH, technical = "denied")
        val runtime = TimingRuntime(listOf(flow {
            now = 1_200
            emit(GenerationChunk.Text("半"))
            now = 1_700
            emit(GenerationChunk.Failure(failure))
        }))

        runCatching { ChatGenerationRunner(runtime) { now }.run(request()) {} }

        assertThat(runtime.persisted.last().timing).isEqualTo(GenerationTiming(1_000, 1_200, 1_700))
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    @Test
    fun `流抛出异常或被取消时补记结束时间并持久化`() = runTest {
        val cancelled = TimingRuntime(listOf(flow {
            now = 1_100
            emit(GenerationChunk.Text("半截"))
            now = 1_800
            throw CancellationException("用户停止")
        }))
        runCatching { ChatGenerationRunner(cancelled) { now }.run(request()) {} }
        assertThat(cancelled.persisted.last().timing).isEqualTo(GenerationTiming(1_000, 1_100, 1_800))
        assertThat(cancelled.persisted.last().content).isEqualTo("半截")

        now = 5_000
        val crashed = TimingRuntime(listOf(flow<GenerationChunk> {
            now = 5_300
            error("连接被重置")
        }))
        runCatching { ChatGenerationRunner(crashed) { now }.run(request()) {} }
        assertThat(crashed.persisted.last().timing).isEqualTo(GenerationTiming(5_000, null, 5_300))
    }

    @Test
    fun `自动重试沿用首次请求时间而首个输出取成功的那次`() = runTest {
        val runtime = TimingRuntime(listOf(
            flow {
                now = 1_100
                emit(GenerationChunk.Text("丢弃"))
                emit(GenerationChunk.Usage(100, 1, 101, cacheReadInput = 90, cacheWriteInput = 5, cacheWriteLongTtlInput = 3))
                emit(GenerationChunk.Failure(GenerationFailure(GenerationFailureCode.NETWORK, retryable = true)))
            },
            flow {
                now = 3_400
                emit(GenerationChunk.Text("重来"))
                emit(GenerationChunk.Usage(120, 1, 121, cacheReadInput = 0, cacheWriteInput = 20, cacheWriteLongTtlInput = 0))
                now = 3_600
                emit(GenerationChunk.Completed(CompletionReason.END_TURN))
            },
        ))
        runtime.retries = 1
        runtime.onRetry = { now += 2_000 }

        ChatGenerationRunner(runtime) { now }.run(request()) {}

        assertThat(runtime.recorded.single().timing).isEqualTo(GenerationTiming(1_000, 3_400, 3_600))
        assertThat(runtime.recorded.single().cacheReadInputTokens).isEqualTo(0)
        assertThat(runtime.recorded.single().cacheWriteInputTokens).isEqualTo(20)
        assertThat(runtime.recorded.single().cacheWriteLongTtlInputTokens).isEqualTo(0)
    }

    @Test
    fun `缓存与推理用量取本轮已报告的最大值且未报告保持 null`() = runTest {
        val runtime = TimingRuntime(listOf(flow {
            emit(GenerationChunk.Text("x"))
            emit(GenerationChunk.Usage(100, 1, 101, cacheReadInput = 80, cacheWriteInput = 20, cacheWriteLongTtlInput = 12, reasoningOutput = 0))
            emit(GenerationChunk.Usage(90, 1, 91, cacheReadInput = 60, cacheWriteInput = 10, cacheWriteLongTtlInput = 4))
            emit(GenerationChunk.Usage(100, 20, 120))
            emit(GenerationChunk.Completed(CompletionReason.END_TURN))
        }))

        ChatGenerationRunner(runtime) { now }.run(request()) {}

        val snapshot = runtime.recorded.single()
        assertThat(snapshot.cacheReadInputTokens).isEqualTo(80)
        assertThat(snapshot.reasoningOutputTokens).isEqualTo(0)
        assertThat(snapshot.cacheWriteInputTokens).isEqualTo(20)
        assertThat(snapshot.cacheWriteLongTtlInputTokens).isEqualTo(12)
        assertThat(snapshot.outputTokens).isEqualTo(20)
    }

    private fun request() = GenerationRequest(
        sessionId = "session",
        assistantMessageId = "assistant",
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private class TimingRuntime(private val streams: List<Flow<GenerationChunk>>) : ChatGenerationRuntime {
        val persisted = mutableListOf<GenerationSnapshot>()
        val recorded = mutableListOf<GenerationSnapshot>()
        val terminals = mutableListOf<GenerationTerminalStatus>()
        var onHandleTools: () -> Unit = {}
        var onRetry: () -> Unit = {}
        var retries = 0
        private var streamIndex = 0

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = GenerationPreparationOutcome.Ready
        override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> = streams[streamIndex++]
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
            persisted += snapshot
        }
        override suspend fun recordTaskUsage(request: GenerationRequest, snapshot: GenerationSnapshot) {
            recorded += snapshot
        }
        override fun maxStreamRetries(request: GenerationRequest): Int = retries
        override suspend fun awaitRetry(delayMs: Long) = onRetry()
        override suspend fun handleTools(request: GenerationRequest, toolCalls: List<GenerationToolCall>): GenerationToolDecision {
            onHandleTools()
            return GenerationToolDecision.CONTINUE
        }
        override suspend fun prepareContinuation(request: GenerationRequest): String = "assistant-$streamIndex"
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
        }
        override suspend fun flush(request: GenerationRequest) = Unit
        override fun cancelProvider() = Unit
    }
}
