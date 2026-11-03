package com.promenar.infinity.data.generation

import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationTiming
import com.promenar.infinity.domain.generation.GenerationToolBudget
import com.promenar.infinity.domain.generation.GenerationToolUsage
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolDecision
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.data.remote.protocol.normalizeToolArguments
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface ChatGenerationRuntime {
    suspend fun cancelBeforeStart(request: GenerationRequest) = Unit
    suspend fun prepare(request: GenerationRequest)
    suspend fun buildContext(request: GenerationRequest): GenerationPreparationOutcome
    suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk>
    suspend fun normalize(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot = snapshot
    suspend fun finalizeStream(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot = snapshot
    suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot)
    suspend fun recordTaskUsage(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
    /** 本轮 Prompt 中实际发送给 Provider 的不可变工具名快照。 */
    fun knownToolNames(request: GenerationRequest): Set<String> = emptySet()
    suspend fun handleTools(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision
    suspend fun prepareContinuation(request: GenerationRequest): String? = null
    /** 认领并持久注入插队消息，返回后续请求使用的用户输入。 */
    suspend fun injectSteering(request: GenerationRequest): GenerationRequest? = null
    /** 完整步骤结束后认领队首一条用户消息。 */
    suspend fun injectQueuedAtBoundary(request: GenerationRequest): GenerationRequest? = injectSteering(request)
    suspend fun steeringContext(request: GenerationRequest): com.promenar.infinity.data.exec.SteeringContext? = null
    suspend fun markStepInterrupted(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
    /** 插队到达审批或提问创建窗口时，先关闭等待并补齐本批结果。 */
    suspend fun interruptPendingTools(request: GenerationRequest, toolCalls: List<GenerationToolCall>): Boolean = false
    /** 本次生成的工具轮次与调用预算。 */
    fun toolBudget(request: GenerationRequest): GenerationToolBudget = GenerationToolBudget()
    fun estimatedInputTokens(request: GenerationRequest): Int = 0
    fun additionalTokenUsage(request: GenerationRequest): GenerationToolUsage = GenerationToolUsage()
    fun loopDetectionThreshold(request: GenerationRequest): Int = 3
    suspend fun confirmLoopContinuation(request: GenerationRequest): Boolean = false
    /** 单轮流式请求遇到可重试失败时的自动重试次数；0 表示不重试。 */
    fun maxStreamRetries(request: GenerationRequest): Int = 0
    /** 重试前的退避等待；可被取消，停止生成时立即结束。 */
    suspend fun awaitRetry(delayMs: Long) = delay(delayMs)

    /** 本轮对话在此次生成之前已消耗的工具轮次与调用数（例如审批前的轮次），用于跨审批续算预算。 */
    suspend fun consumedToolUsage(request: GenerationRequest): GenerationToolUsage = GenerationToolUsage()
    /** 预算耗尽时不执行本轮调用，为每个调用写入可回传给模型的失败结果。 */
    suspend fun rejectToolCalls(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
        reason: String,
    ) = Unit
    /** 后续轮次只允许模型基于已有结果收尾；下一次 buildContext 起生效。 */
    fun enterFinalAnswerMode(request: GenerationRequest) = Unit
    /**
     * 工具轮次之后模型以空正文结束：给当前回复的下一次请求附加一次提醒，要求继续或用文字答复。
     * 返回 false 表示不支持，生成按原样结束。
     */
    fun requestReplyAfterEmptyTurn(request: GenerationRequest): Boolean = false
    suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot)
    suspend fun markTerminal(
        request: GenerationRequest,
        status: GenerationTerminalStatus,
        snapshot: GenerationSnapshot,
        cause: Throwable? = null,
    ) = Unit
    suspend fun flush(request: GenerationRequest)
    suspend fun pauseForUser(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
    /** 审批等待只保留有界租约，其余退出路径释放本轮工具资源。 */
    suspend fun releaseToolResources(request: GenerationRequest, awaitingApproval: Boolean) = Unit
    fun cancelProvider()
}

class ChatGenerationRunner(
    private val runtime: ChatGenerationRuntime,
    /** 记录每轮请求时间点所用的时钟（epoch 毫秒），测试可注入。 */
    private val clock: () -> Long = System::currentTimeMillis,
) : GenerationRunner, QueuedGenerationCancellation {
    override suspend fun cancelBeforeStart(request: GenerationRequest) = runtime.cancelBeforeStart(request)

    override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
        var request = request
        var snapshot = GenerationSnapshot()
        var flushed = false
        var primaryFailure: Throwable? = null
        var resultsCommitted = false
        var awaitingApproval = false
        suspend fun safeEmit(event: GenerationEvent, primary: Throwable? = null) {
            try {
                emit(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (observerFailure: Throwable) {
                primary?.addSuppressed(observerFailure)
            }
        }
        suspend fun phase(value: GenerationPhase, primary: Throwable? = null) =
            safeEmit(GenerationEvent.PhaseChanged(value), primary)
        fun Throwable.isRecoveryOf(original: CancellationException): Boolean {
            var current: Throwable? = this
            while (current != null) {
                if (current === original) return true
                current = current.cause
            }
            return false
        }
        suspend fun flushOnce(primary: Throwable? = null) {
            if (!flushed) {
                try {
                    runtime.flush(request)
                    flushed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (flushFailure: Throwable) {
                    flushed = true
                    if (primary != null) primary.addSuppressed(flushFailure) else throw flushFailure
                }
            }
        }
        fun markFirstOutput() {
            val timing = snapshot.timing ?: return
            if (timing.firstOutputAt == null && timing.endedAt == null) {
                snapshot = snapshot.copy(timing = timing.copy(firstOutputAt = clock()))
            }
        }
        fun markRoundEnded() {
            val timing = snapshot.timing ?: return
            if (timing.endedAt == null) snapshot = snapshot.copy(timing = timing.copy(endedAt = clock()))
        }
        /** 失败或取消时补记本轮结束时间；未确认的工具调用由 [clearUnconfirmedToolCalls] 一并持久化。 */
        suspend fun closeOpenRoundTiming(primary: Throwable) {
            val timing = snapshot.timing ?: return
            if (timing.endedAt != null) return
            markRoundEnded()
            if (snapshot.toolCalls.isNotEmpty() && !resultsCommitted) return
            try {
                withContext(NonCancellable) {
                    snapshot = persist(request, snapshot, emit)
                }
            } catch (timingFailure: Throwable) {
                if (timingFailure !== primary) primary.addSuppressed(timingFailure)
            }
        }
        suspend fun clearUnconfirmedToolCalls(primary: Throwable) {
            if (snapshot.toolCalls.isEmpty() || resultsCommitted) return
            val cleanSnapshot = snapshot.copy(toolCalls = emptyList())
            snapshot = cleanSnapshot
            try {
                withContext(NonCancellable) {
                    snapshot = persist(request, cleanSnapshot, emit)
                }
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
            }
        }
        suspend fun publishPersistenceFailure(
            persistenceFailure: Throwable,
            originalFailure: Throwable? = null,
        ) {
            if (persistenceFailure is CancellationException) throw persistenceFailure
            if (primaryFailure == null) primaryFailure = persistenceFailure
            if (originalFailure != null && originalFailure !== persistenceFailure) {
                persistenceFailure.addSuppressed(originalFailure)
            }
            flushOnce(persistenceFailure)
            val failure = com.promenar.infinity.domain.generation.GenerationFailure.persistence(
                technical = persistenceFailure.message,
                cause = persistenceFailure,
            )
            safeEmit(
                GenerationEvent.PersistenceFailed(persistenceFailure, originalFailure, failure),
                persistenceFailure,
            )
            phase(GenerationPhase.PERSISTENCE_FAILED, persistenceFailure)
        }
        suspend fun persistTerminal(
            status: GenerationTerminalStatus,
            originalFailure: Throwable? = null,
        ): Boolean = try {
            runtime.markTerminal(request, status, snapshot, originalFailure)
            flushOnce()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (persistenceFailure: Throwable) {
            publishPersistenceFailure(persistenceFailure, originalFailure)
            false
        }
        suspend fun flushRoundBoundaryWithRetry(): Boolean = try {
            runtime.flush(request)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Throwable) {
            try {
                runtime.flush(request)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Throwable) {
                if (primaryFailure == null) primaryFailure = retryFailure
                retryFailure.addSuppressed(firstFailure)
                flushed = true
                val persistenceFailure = com.promenar.infinity.domain.generation.GenerationFailure.persistence(
                    technical = retryFailure.message,
                    cause = retryFailure,
                )
                safeEmit(
                    GenerationEvent.PersistenceFailed(retryFailure, null, persistenceFailure),
                    retryFailure,
                )
                phase(GenerationPhase.PERSISTENCE_FAILED, retryFailure)
                false
            }
        }

        try {
            phase(GenerationPhase.PREPARING)
            runtime.prepare(request)
            phase(GenerationPhase.BUILDING_CONTEXT)
            val initialPreparation = runtime.buildContext(request)
            if (initialPreparation is GenerationPreparationOutcome.Handled) {
                withContext(NonCancellable) {
                    safeEmit(GenerationEvent.Rejected(initialPreparation.failure))
                    try {
                        flushOnce()
                        phase(GenerationPhase.FAILED)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (persistenceFailure: Throwable) {
                        publishPersistenceFailure(persistenceFailure, initialPreparation.failure.cause)
                    }
                }
                return
            }
            val steering = runtime.steeringContext(request)
            var attempt = 0
            val budget = runtime.toolBudget(request)
            val consumed = runtime.consumedToolUsage(request)
            var confirmedToolCallCount = consumed.calls
            var toolRoundCount = consumed.rounds
            var cumulativeTokens = consumed.tokens
            var tokensEstimated = consumed.tokensEstimated
            val loopDetector = ConsecutiveToolLoopDetector(runtime.loopDetectionThreshold(request))
            suspend fun progress(tokens: Long = cumulativeTokens, estimated: Boolean = tokensEstimated) = safeEmit(
                GenerationEvent.ProgressChanged(com.promenar.infinity.domain.generation.GenerationProgress(
                    toolRoundCount, confirmedToolCallCount, tokens, estimated,
                )),
            )
            progress()
            var finalAnswerMode = false
            var finalAnswerIgnoredBudget = false
            var emptyReplyRetried = false
            var accountedAdditionalTokens = 0L
            while (true) {
                val additional = runtime.additionalTokenUsage(request)
                cumulativeTokens += (additional.tokens - accountedAdditionalTokens).coerceAtLeast(0)
                accountedAdditionalTokens = additional.tokens
                tokensEstimated = tokensEstimated || additional.tokensEstimated
                progress()
                if (!finalAnswerMode && budget.maxTokens > 0 && cumulativeTokens >= budget.maxTokens) {
                    finalAnswerMode = true
                    runtime.enterFinalAnswerMode(request)
                    val preparation = runtime.buildContext(request)
                    if (preparation is GenerationPreparationOutcome.Handled) {
                        safeEmit(GenerationEvent.Rejected(preparation.failure))
                        flushOnce()
                        phase(GenerationPhase.FAILED)
                        return
                    }
                }
                // 本轮尚未执行任何工具；可重试失败时丢弃本轮半截输出，退避后原样重发。
                val roundStart = snapshot
                val maxRetries = runtime.maxStreamRetries(request).coerceAtLeast(0)
                var retries = 0
                val roundTools = linkedMapOf<String, GenerationToolCall>()
                var reportedUsage: Long? = null
                // 自动重试沿用本轮首次请求时间，首个输出与结束时间以最终成功的那次请求为准。
                var roundRequestStartedAt: Long? = null
                var streamInterrupted = false
                val terminal: GenerationChunk.Completed
                while (true) {
                    val outcome: GenerationChunk.Completed? = try {
                        phase(GenerationPhase.CONNECTING)
                        val requestStartedAt = roundRequestStartedAt ?: clock().also { roundRequestStartedAt = it }
                        snapshot = snapshot.copy(timing = GenerationTiming(requestStartedAt))
                        val streamOutcome = com.promenar.infinity.data.exec.interruptOnSteering(steering) {
                        val stream = runtime.stream(request, attempt)
                        phase(GenerationPhase.THINKING)
                        var streamingPhaseEmitted = false
                        roundTools.clear()
                        var completed: GenerationChunk.Completed? = null
                        reportedUsage = null
                        stream.transformWhile { chunk ->
                            emit(chunk)
                            chunk !is GenerationChunk.Completed
                        }.collect { chunk ->
                            when (chunk) {
                                is GenerationChunk.Text -> {
                                    if (chunk.content.isNotEmpty() || !chunk.reasoning.isNullOrEmpty()) markFirstOutput()
                                    if (!streamingPhaseEmitted) {
                                        streamingPhaseEmitted = true
                                        phase(GenerationPhase.STREAMING)
                                    }
                                    snapshot = snapshot.copy(
                                        content = snapshot.content + chunk.content,
                                        reasoning = snapshot.reasoning + chunk.reasoning.orEmpty(),
                                    )
                                    snapshot = persist(request, snapshot, emit)
                                }
                                is GenerationChunk.Thinking -> {
                                    if (chunk.content.isNotEmpty()) markFirstOutput()
                                    snapshot = snapshot.copy(reasoning = snapshot.reasoning + chunk.content)
                                    snapshot = persist(request, snapshot, emit)
                                }
                                is GenerationChunk.ReasoningState -> {
                                    snapshot = snapshot.copy(reasoningReplay = chunk.encoded)
                                    snapshot = persist(request, snapshot, emit)
                                }
                                is GenerationChunk.ToolCall -> {
                                    markFirstOutput()
                                    val old = roundTools[chunk.id]
                                    roundTools[chunk.id] = GenerationToolCall(
                                        id = chunk.id,
                                        name = chunk.name.ifBlank { old?.name.orEmpty() },
                                        arguments = old?.arguments.orEmpty() + chunk.arguments,
                                    )
                                    snapshot = snapshot.copy(toolCalls = roundTools.values.toList())
                                    snapshot = persist(request, snapshot, emit)
                                }
                                is GenerationChunk.Usage -> {
                                    val inputTokens = maxOf(snapshot.inputTokens, chunk.input.coerceAtLeast(0))
                                    val outputTokens = maxOf(snapshot.outputTokens, chunk.output.coerceAtLeast(0))
                                    val observedUsage = maxOf(
                                        reportedUsage ?: 0,
                                        chunk.total.toLong().coerceAtLeast(0),
                                        inputTokens.toLong() + outputTokens.toLong(),
                                    )
                                    if (observedUsage > 0) reportedUsage = observedUsage
                                    snapshot = snapshot.copy(
                                        inputTokens = inputTokens,
                                        outputTokens = outputTokens,
                                        totalTokens = (reportedUsage ?: 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        cacheReadInputTokens = maxReported(snapshot.cacheReadInputTokens, chunk.cacheReadInput),
                                        cacheWriteInputTokens = maxReported(snapshot.cacheWriteInputTokens, chunk.cacheWriteInput),
                                        cacheWriteLongTtlInputTokens = maxReported(snapshot.cacheWriteLongTtlInputTokens, chunk.cacheWriteLongTtlInput),
                                        reasoningOutputTokens = maxReported(snapshot.reasoningOutputTokens, chunk.reasoningOutput),
                                    )
                                    snapshot = persist(request, snapshot, emit)
                                    if (reportedUsage != null) progress(cumulativeTokens + requireNotNull(reportedUsage))
                                }
                                is GenerationChunk.Citations -> {
                                    snapshot = snapshot.copy(citations = chunk.citations)
                                    snapshot = persist(request, snapshot, emit)
                                }
                                is GenerationChunk.Failure -> {
                                    snapshot = snapshot.copy(failure = chunk.failure)
                                    markRoundEnded()
                                    snapshot = persist(request, snapshot, emit)
                                    throw GenerationFailedException(chunk.failure)
                                }
                                is GenerationChunk.Completed -> completed = chunk
                            }
                        }
                        (completed
                            ?: throw GenerationFailedException(GenerationFailure(
                                code = GenerationFailureCode.NETWORK,
                                technical = "Provider stream ended without explicit Completed",
                                retryable = true,
                            )))
                        }
                        markRoundEnded()
                        when (streamOutcome) {
                            is com.promenar.infinity.data.exec.SteeringStep.Completed -> streamOutcome.value
                            com.promenar.infinity.data.exec.SteeringStep.Interrupted -> {
                                streamInterrupted = true
                                GenerationChunk.Completed(CompletionReason.END_TURN)
                            }
                        }
                    } catch (failed: GenerationFailedException) {
                        if (retries >= maxRetries || !isRetryableStreamFailure(failed.failure)) throw failed
                        retries++
                        val delayMs = streamRetryDelayMs(retries, failed.failure)
                        snapshot = persist(
                            request,
                            roundStart.copy(timing = roundRequestStartedAt?.let { GenerationTiming(it) }),
                            emit,
                        )
                        safeEmit(GenerationEvent.RetryScheduled(
                            com.promenar.infinity.domain.generation.GenerationRetry(retries, maxRetries, failed.failure.code, delayMs),
                        ))
                        InfinityLogger.diagnostic(
                            "generation.stream_retry",
                            mapOf(
                                "sessionId" to request.sessionId,
                                "attempt" to retries.toString(),
                                "code" to failed.failure.code.name,
                            ),
                        )
                        runtime.awaitRetry(delayMs)
                        null
                    }
                    if (outcome != null) {
                        terminal = outcome
                        break
                    }
                }
                if (streamInterrupted) {
                    // 未完成参数只保留可序列化占位，全部调用写中断结果，不能进入执行器。
                    val pairedCalls = snapshot.toolCalls.distinctBy { it.id }.map { call ->
                        val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
                        call.copy(name = call.name.ifBlank { "interrupted_tool_call" }, arguments = args?.toString() ?: "{}")
                    }
                    snapshot = persist(request, snapshot.copy(toolCalls = pairedCalls), emit)
                    runtime.rejectToolCalls(request, pairedCalls, "用户插队打断了本步，调用未执行。")
                    resultsCommitted = true
                } else {
                    val finalized = runtime.finalizeStream(request, snapshot)
                    if (finalized != snapshot) snapshot = persist(request, finalized, emit)
                    validateCompletedRound(terminal, snapshot.toolCalls)
                }
                if (reportedUsage == null) {
                    val input = runtime.estimatedInputTokens(request).coerceAtLeast(0)
                    val output = ApproximateTokenEstimator.estimate(snapshot.content + snapshot.reasoning) +
                        snapshot.toolCalls.sumOf { ApproximateTokenEstimator.estimate(it.name + it.arguments) }
                    snapshot = snapshot.copy(inputTokens = input, outputTokens = output,
                        totalTokens = (input.toLong() + output).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        tokensEstimated = true)
                }
                runtime.recordTaskUsage(request, snapshot)
                cumulativeTokens += reportedUsage ?: snapshot.totalTokens.toLong()
                if (reportedUsage == null) tokensEstimated = true
                progress()
                if (streamInterrupted) runtime.markStepInterrupted(request, snapshot)
                if (terminal.reason == CompletionReason.END_TURN) {
                    val delivered = if (streamInterrupted) runtime.injectSteering(request) else runtime.injectQueuedAtBoundary(request)
                    if (delivered != null || streamInterrupted) {
                        if (delivered != null) request = delivered
                        emptyReplyRetried = false
                        attempt++
                        runtime.prepareContinuation(request)?.let { safeEmit(GenerationEvent.TargetChanged(it)) }
                        snapshot = GenerationSnapshot()
                        resultsCommitted = false
                        phase(GenerationPhase.BUILDING_CONTEXT)
                        val preparation = runtime.buildContext(request)
                        if (preparation is GenerationPreparationOutcome.Handled) {
                            safeEmit(GenerationEvent.Rejected(preparation.failure))
                            flushOnce()
                            phase(GenerationPhase.FAILED)
                            return
                        }
                        continue
                    }
                    // 工具轮次之后既无正文也无调用：同一条回复上提醒一次并重新请求，避免对话无声结束。
                    if (!emptyReplyRetried && !finalAnswerMode && toolRoundCount > 0 && snapshot.content.isBlank() &&
                        snapshot.toolCalls.isEmpty() && snapshot.failure == null && runtime.requestReplyAfterEmptyTurn(request)
                    ) {
                        emptyReplyRetried = true
                        attempt++
                        snapshot = persist(request, roundStart, emit)
                        InfinityLogger.diagnostic("generation.empty_reply_retry", mapOf("sessionId" to request.sessionId))
                        continue
                    }
                    if (emptyReplyRetried && snapshot.content.isBlank() && snapshot.toolCalls.isEmpty()) {
                        throw GenerationFailedException(GenerationFailure(GenerationFailureCode.EMPTY_REPLY))
                    }
                    break
                }
                val normalizedTools = snapshot.toolCalls.map { it.copy(arguments = normalizeToolArguments(it.arguments)) }
                if (normalizedTools != snapshot.toolCalls) {
                    snapshot = persist(request, snapshot.copy(toolCalls = normalizedTools), emit)
                }
                val effectiveTools = snapshot.toolCalls
                toolRoundCount++
                confirmedToolCallCount += effectiveTools.size
                progress()
                val exhausted = (budget.maxRounds > 0 && toolRoundCount > budget.maxRounds) ||
                    (budget.maxCalls > 0 && confirmedToolCallCount > budget.maxCalls) ||
                    (budget.maxTokens > 0 && cumulativeTokens >= budget.maxTokens)
                if (finalAnswerMode || exhausted) {
                    // 未知工具名由执行器回写可纠错结果；预算耗尽则拒绝本轮调用并只允许模型收尾。
                    runtime.rejectToolCalls(request, effectiveTools, budgetExhaustedFeedback(budget))
                    resultsCommitted = true
                    InfinityLogger.diagnostic(
                        "generation.tool_budget_exhausted",
                        mapOf(
                            "sessionId" to request.sessionId,
                            "rounds" to toolRoundCount.toString(),
                            "calls" to confirmedToolCallCount.toString(),
                        ),
                    )
                    if (finalAnswerMode) {
                        finalAnswerIgnoredBudget = true
                        break
                    }
                    finalAnswerMode = true
                    runtime.enterFinalAnswerMode(request)
                }
                var decision = if (finalAnswerMode) {
                    GenerationToolDecision.CONTINUE
                } else {
                    val repeated = effectiveTools.fold(false) { found, call -> loopDetector.record(call) || found }
                    if (repeated) {
                        if (!runtime.confirmLoopContinuation(request)) {
                            runtime.rejectToolCalls(request, effectiveTools, "用户已停止重复工具调用。")
                            resultsCommitted = true
                            throw CancellationException("重复工具调用已停止")
                        }
                        loopDetector.reset()
                    }
                    // 自动执行的工具仍属于生成过程；只有真正挂起等待用户决定时才进入 WAITING_APPROVAL。
                    phase(GenerationPhase.EXECUTING_TOOLS)
                    // 批次可能在后续调用被取消前已有结果提交；终态按实际结果保留配对。
                    resultsCommitted = true
                    runtime.handleTools(request, effectiveTools).also { resultsCommitted = true }
                }
                if (decision in setOf(GenerationToolDecision.WAIT_FOR_APPROVAL, GenerationToolDecision.WAIT_FOR_INPUT) &&
                    steering?.pending() == true && runtime.interruptPendingTools(request, effectiveTools)) {
                    decision = GenerationToolDecision.CONTINUE
                }
                when (decision) {
                    GenerationToolDecision.CONTINUE -> {
                        attempt++
                        if (!flushRoundBoundaryWithRetry()) return
                        if (steering?.pending() == true) runtime.markStepInterrupted(request, snapshot)
                        runtime.injectQueuedAtBoundary(request)?.let {
                            request = it
                            emptyReplyRetried = false
                        }
                        runtime.prepareContinuation(request)?.let { assistantMessageId ->
                            safeEmit(GenerationEvent.TargetChanged(assistantMessageId))
                        }
                        snapshot = GenerationSnapshot()
                        resultsCommitted = false
                        phase(GenerationPhase.BUILDING_CONTEXT)
                        val continuationPreparation = runtime.buildContext(request)
                        if (continuationPreparation is GenerationPreparationOutcome.Handled) {
                            withContext(NonCancellable) {
                                safeEmit(
                                    GenerationEvent.Rejected(continuationPreparation.failure),
                                )
                                try {
                                    flushOnce()
                                    phase(GenerationPhase.FAILED)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (persistenceFailure: Throwable) {
                                    publishPersistenceFailure(
                                        persistenceFailure,
                                        continuationPreparation.failure.cause,
                                    )
                                }
                            }
                            return
                        }
                        snapshot = persist(request, snapshot, emit)
                    }
                    GenerationToolDecision.WAIT_FOR_APPROVAL, GenerationToolDecision.WAIT_FOR_INPUT -> {
                        phase(if (decision == GenerationToolDecision.WAIT_FOR_APPROVAL) GenerationPhase.WAITING_APPROVAL else GenerationPhase.WAITING_INPUT)
                        withContext(NonCancellable) {
                            try {
                                flushOnce()
                                awaitingApproval = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (persistenceFailure: Throwable) {
                                publishPersistenceFailure(persistenceFailure)
                            }
                        }
                        if (awaitingApproval) currentCoroutineContext().ensureActive()
                        return
                    }
                    GenerationToolDecision.COMPLETE -> break
                }
            }
            snapshot.failure?.let { throw GenerationFailedException(it) }
            if (finalAnswerIgnoredBudget && snapshot.content.isBlank()) {
                // 收尾轮已禁止调用工具，模型仍只返回工具调用：不能以空回答记为成功。
                throw GenerationFailedException(
                    GenerationFailure(
                        code = GenerationFailureCode.TOOL_BUDGET_EXHAUSTED,
                        technical = "final answer round returned tool calls without content",
                    ),
                )
            }
            // 经过工具轮次后模型可能以空正文结束，过程与结果已保存在此前消息与工具记录中。
            check(snapshot.content.isNotBlank() || toolRoundCount > 0) { "生成完成但未产生有效内容" }
            phase(GenerationPhase.POST_PROCESSING)
            runtime.postProcess(request, snapshot)
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.SUCCESS)) {
                    InfinityLogger.diagnostic("generation.completed", mapOf("sessionId" to request.sessionId))
                    phase(GenerationPhase.COMPLETED)
                }
            }
        } catch (paused: HeadlessInteractionRequired) {
            withContext(NonCancellable) {
                closeOpenRoundTiming(paused)
                clearUnconfirmedToolCalls(paused)
                try {
                    runtime.pauseForUser(request, snapshot)
                    flushOnce()
                    awaitingApproval = true
                    phase(GenerationPhase.WAITING_APPROVAL)
                } catch (failure: Throwable) {
                    publishPersistenceFailure(failure, paused)
                }
            }
        } catch (cancelled: CancellationException) {
            awaitingApproval = false
            primaryFailure = cancelled
            try {
                runtime.cancelProvider()
            } catch (cleanupFailure: Throwable) {
                if (!cleanupFailure.isRecoveryOf(cancelled)) {
                    cancelled.addSuppressed(cleanupFailure)
                }
            }
            closeOpenRoundTiming(cancelled)
            clearUnconfirmedToolCalls(cancelled)
            try {
                withContext(NonCancellable) {
                    if (persistTerminal(GenerationTerminalStatus.CANCELLED, cancelled)) {
                        phase(GenerationPhase.CANCELLED, cancelled)
                    }
                }
            } catch (cleanupFailure: Throwable) {
                if (!cleanupFailure.isRecoveryOf(cancelled)) {
                    cancelled.addSuppressed(cleanupFailure)
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            awaitingApproval = false
            primaryFailure = failure
            val generationFailure = failure.failureOrUnknown()
            InfinityLogger.diagnostic(
                "generation.failed",
                mapOf("sessionId" to request.sessionId, "code" to generationFailure.code.name),
            )
            withContext(NonCancellable) {
                closeOpenRoundTiming(failure)
                clearUnconfirmedToolCalls(failure)
                if (persistTerminal(GenerationTerminalStatus.ERROR, failure)) {
                    safeEmit(GenerationEvent.Failed(generationFailure), failure)
                    phase(GenerationPhase.FAILED, failure)
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                val releaseFailure = try {
                    runtime.releaseToolResources(request, awaitingApproval)
                    null
                } catch (failure: Throwable) {
                    failure
                }
                try {
                    flushOnce(primaryFailure)
                } catch (flushFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary == null) {
                        releaseFailure?.takeUnless { it === flushFailure }?.let(flushFailure::addSuppressed)
                        throw flushFailure
                    }
                    if (flushFailure !== primary) primary.addSuppressed(flushFailure)
                }
                if (releaseFailure != null) {
                    val primary = primaryFailure
                    if (primary == null) throw releaseFailure
                    if (releaseFailure !== primary) primary.addSuppressed(releaseFailure)
                }
            }
        }
    }

    private fun maxReported(current: Int?, reported: Int?): Int? {
        val value = reported?.coerceAtLeast(0) ?: return current
        return if (current == null) value else maxOf(current, value)
    }

    private suspend fun persist(
        request: GenerationRequest,
        snapshot: GenerationSnapshot,
        emit: suspend (GenerationEvent) -> Unit,
    ): GenerationSnapshot {
        val normalized = runtime.normalize(request, snapshot)
        runtime.persist(request, normalized)
        try {
            emit(GenerationEvent.SnapshotChanged(normalized))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 展示观察者失败不影响已完成的持久化；取消仍必须向上传播。
        }
        return normalized
    }

    private fun budgetExhaustedFeedback(budget: GenerationToolBudget): String =
        "工具调用未执行：本次任务已达到工具或 token 预算上限（轮次=${budget.maxRounds}，调用=${budget.maxCalls}，token=${budget.maxTokens}；0 表示不限）。" +
            "不要再调用工具，请基于已获得的结果总结已完成内容、当前状态与剩余待办，并告知用户可继续发送消息接续。"

    private fun validateCompletedRound(
        terminal: GenerationChunk.Completed,
        toolCalls: List<GenerationToolCall>,
    ) {
        val terminalIds = terminal.completedToolCallIds
        if (terminalIds.any(String::isBlank) || terminalIds.distinct().size != terminalIds.size) {
            throw contractFailure("Completed contains blank or duplicate tool IDs")
        }
        when (terminal.reason) {
            CompletionReason.END_TURN -> {
                if (toolCalls.isNotEmpty() || terminalIds.isNotEmpty()) {
                    throw contractFailure("END_TURN contained executable tool calls")
                }
            }
            CompletionReason.TOOL_CALLS -> {
                if (toolCalls.isEmpty() || terminalIds != toolCalls.map { it.id }) {
                    throw contractFailure("Completed tool IDs do not exactly match streamed calls")
                }
                // 未知工具名属于模型可纠错错误，交由执行器回写结果；结构不完整仍失败关闭。
                if (toolCalls.any { call ->
                        call.id.isBlank() ||
                            call.name.isBlank() ||
                            !(call.arguments.isBlank() ||
                                runCatching { Json.parseToJsonElement(call.arguments) is JsonObject }
                                    .getOrDefault(false))
                    }
                ) {
                    throw contractFailure("Tool call is incomplete or has non-object arguments")
                }
            }
        }
    }

    private fun contractFailure(technical: String): GenerationFailedException =
        GenerationFailedException(
            GenerationFailure(
                code = GenerationFailureCode.SERVER,
                technical = technical,
            ),
        )
}

/** 网络、超时与限流总是重试；服务端与未知错误只在协议层明确可重试（如 5xx、流截断）时重试。 */
internal fun isRetryableStreamFailure(failure: GenerationFailure): Boolean = when (failure.code) {
    GenerationFailureCode.NETWORK,
    GenerationFailureCode.TIMEOUT,
    GenerationFailureCode.RATE_LIMIT,
    -> true
    GenerationFailureCode.SERVER,
    GenerationFailureCode.UNKNOWN,
    -> failure.retryable
    else -> false
}

/** 指数退避 1s、2s、4s… 封顶 30s；限流给出 Retry-After 时按其等待，最长 60s。 */
internal fun streamRetryDelayMs(attempt: Int, failure: GenerationFailure): Long {
    failure.retryAfterSeconds?.takeIf { failure.code == GenerationFailureCode.RATE_LIMIT && it > 0 }?.let {
        return (it * 1_000L).coerceAtMost(60_000L)
    }
    val exponent = (attempt - 1).coerceIn(0, 5)
    return (1_000L shl exponent).coerceAtMost(30_000L)
}
