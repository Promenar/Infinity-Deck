package com.promenar.infinity.domain.generation

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * 生成失败的上层可见值。
 *
 * 本批次起，它只是 [GenerationFailure] 的薄壳：
 * - [failure] / [code] / [formatArgs] 是结构化失败契约，UI mapper 据此映射资源；
 * - [message] 与 [cause] 为**向后兼容访问器**，仅供尚未迁移到结构化契约的上层过渡使用。
 *
 * 兼容语义约束（必须保证）：
 * - [message] 永远只返回稳定、可回映射、**非自然语言**、**非异常原文**的信封串
 *   （由 [GenerationFailureCodec] 产出），不得返回 [Throwable.getMessage] 或协议原文；
 * - [cause] 仅诊断用途。
 *
 * 仍保留 `GenerationError(String, Throwable?)` 构造以兼容历史调用点
 * （例如 E2E fixture、androidTest），传入的任意字符串只会落入 [GenerationFailure.technical]，
 * 绝不会经 [message] 成为可展示文案。
 */
class GenerationError internal constructor(
    val failure: GenerationFailure,
) {
    constructor(message: String, cause: Throwable? = null) : this(
        GenerationFailure.unknown(technical = message.ifBlank { null }, cause = cause),
    )

    val code: GenerationFailureCode get() = failure.code
    val formatArgs: Map<String, String> get() = failure.formatArgs
    val technical: String? get() = failure.technical
    val cause: Throwable? get() = failure.cause

    /**
     * 向后兼容：稳定、可回映射、非自然语言的信封串。
     * 新代码请使用 [code] / [formatArgs] / [failure]。
     */
    val message: String
        get() = GenerationFailureCodec.encode(failure)

    override fun equals(other: Any?): Boolean = other is GenerationError && other.failure == failure

    override fun hashCode(): Int = failure.hashCode()

    override fun toString(): String = buildString {
        append("GenerationError(code=")
        append(code)
        failure.retryAfterSeconds?.takeIf { code == GenerationFailureCode.RATE_LIMIT && it > 0 }?.let {
            append(", retryAfterSeconds=")
            append(it)
        }
        append(')')
    }
}

data class GenerationTaskSnapshot(
    val taskId: String,
    val sessionId: String,
    val assistantMessageId: String,
    val phase: GenerationPhase,
    /** 本轮已到达的正文加思考内容的字数，会话列表据此估算出字速率。 */
    val generatedChars: Int,
    val startedAt: Long,
    val error: GenerationError? = null,
    val progress: GenerationProgress = GenerationProgress(),
    val queuePosition: Int? = null,
    val runtimePolicy: GenerationRuntimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    val startedOrder: Long = 0,
    /** 正在等待自动重发的请求；进入重发前阶段以外的阶段即清除。 */
    val retry: GenerationRetry? = null,
)

const val MAX_CONCURRENT_GENERATIONS = 16

sealed interface StartGenerationResult {
    data class Started(val taskId: String) : StartGenerationResult
    data class Queued(val taskId: String, val position: Int) : StartGenerationResult
    data class Existing(val snapshot: GenerationTaskSnapshot) : StartGenerationResult
    data class Busy(val activeSessionId: String) : StartGenerationResult
    data class Rejected(val error: GenerationError) : StartGenerationResult
}

enum class CancellationReason { USER, TIMEOUT, REPLACED, SHUTDOWN, BACKGROUND_UNAVAILABLE }

interface GenerationCoordinator {
    val sessions: StateFlow<Map<String, GenerationTaskSnapshot>>
    /** 最近发生状态变化的非终态任务，供旧调用方兼容。 */
    val active: StateFlow<GenerationTaskSnapshot?>
    fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?>
    /** 包含排队及终态发布后的资源收尾，供会话级互斥闸门使用。 */
    fun isSessionBusy(sessionId: String): Boolean = sessions.value[sessionId]?.phase?.let {
        it !in setOf(GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED,
            GenerationPhase.PERSISTENCE_FAILED, GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT)
    } == true
    /** 等待本会话运行任务连同资源收尾完全退出，避免确认终态后的短暂 Busy。 */
    suspend fun awaitSessionIdle(sessionId: String) {
        sessions.first { sessionId !in it || it[sessionId]?.phase in setOf(
            GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED,
            GenerationPhase.PERSISTENCE_FAILED, GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT,
        ) }
    }
    /** 资源释放后的结果回执独立于界面对展示终态的确认。 */
    suspend fun awaitTaskCompletion(sessionId: String, taskId: String): GenerationTaskSnapshot? {
        awaitSessionIdle(sessionId)
        return observe(sessionId).value?.takeIf { it.taskId == taskId }
    }
    suspend fun start(request: GenerationRequest): StartGenerationResult
    /** 登记任务前同步确认准入；回调只允许无阻塞原子操作，返回 false 时不登记新任务。 */
    suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult
    fun cancel(taskId: String, reason: CancellationReason = CancellationReason.USER): Boolean
    fun cancelAll(reason: CancellationReason = CancellationReason.USER): Int =
        sessions.value.values.count { cancel(it.taskId, reason) }
    fun cancelForPolicy(policy: GenerationRuntimePolicy, reason: CancellationReason): Int =
        sessions.value.values.count { it.runtimePolicy == policy && cancel(it.taskId, reason) }
    suspend fun cancelAndJoinSession(
        sessionId: String,
        reason: CancellationReason = CancellationReason.SHUTDOWN,
    ): Boolean = false
    fun acknowledgeTerminal(taskId: String): Boolean
    fun release(sessionId: String, discardTerminal: Boolean = false)
    /** 仅在当前快照仍属于预期任务时释放，避免旧任务清除新任务。 */
    fun release(sessionId: String, expectedTaskId: String, discardTerminal: Boolean = false)
}
