package com.promenar.infinity.domain.generation

enum class GenerationPhase {
    /** 已受理但同时生成的会话已达上限，排队等待空位；可取消。 */
    QUEUED,
    PREPARING,
    BUILDING_CONTEXT,
    CONNECTING,
    THINKING,
    STREAMING,
    /** 正在执行本轮已获准的工具调用；生成仍在进行，可停止。 */
    EXECUTING_TOOLS,
    WAITING_APPROVAL,
    /** 本轮因 `ask_user` 暂停，等待用户回答；与等待批准一样持久化，回答后续跑。 */
    WAITING_INPUT,
    POST_PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PERSISTENCE_FAILED,
}

enum class GenerationRuntimePolicy { BACKGROUND_ALLOWED, FOREGROUND_ONLY }

enum class GenerationInteractionMode { INTERACTIVE, HEADLESS }

/** Provider 明确声明的成功终止原因；不得由 EOF 或局部内容推断。 */
enum class CompletionReason { END_TURN, TOOL_CALLS }

/** 固定轮次模式的调用次数上限。 */
const val MAX_TOOL_CALLS_PER_GENERATION = 200

/** 未配置时单次生成允许的工具循环轮次。 */
const val DEFAULT_TOOL_ROUNDS_PER_GENERATION = 50

/** 用户可配置的工具循环轮次上限。 */
const val MAX_CONFIGURABLE_TOOL_ROUNDS = 100

/** 单次任务预算；0 表示该维度不限，耗尽后进入一次只收尾的请求。 */
data class GenerationToolBudget(
    val maxRounds: Int = DEFAULT_TOOL_ROUNDS_PER_GENERATION,
    val maxCalls: Int = MAX_TOOL_CALLS_PER_GENERATION,
    val maxTokens: Long = 0,
) {
    init {
        require(maxRounds >= 0) { "maxRounds 不得为负，0 表示不限" }
        require(maxCalls >= 0) { "maxCalls 不得为负，0 表示不限" }
        require(maxTokens >= 0) { "maxTokens 不得为负，0 表示关闭" }
    }
}

data class GenerationRequest(
    val sessionId: String,
    val assistantMessageId: String,
    val userMessageId: String?,
    val userContent: String,
    val imageDataUrls: List<String>,
    val runtimePolicy: GenerationRuntimePolicy,
    val rollbackUserOnPreparationFailure: Boolean = false,
    /** 本次生成成功（或进入等待审批）后才删除的旧回复消息；失败或取消时保留。 */
    val messageIdsToReplace: Set<String> = emptySet(),
    val requestId: String = assistantMessageId,
    val interactionMode: GenerationInteractionMode = GenerationInteractionMode.INTERACTIVE,
)

/** 已消耗的工具轮次与调用数。 */
data class GenerationToolUsage(
    val rounds: Int = 0,
    val calls: Int = 0,
    val tokens: Long = 0,
    val tokensEstimated: Boolean = false,
)

data class GenerationProgress(
    val rounds: Int = 0,
    val calls: Int = 0,
    val tokens: Long = 0,
    val estimated: Boolean = false,
)

data class GenerationToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class GenerationSnapshot(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<GenerationToolCall> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val citations: List<GenerationCitation> = emptyList(),
    val failure: GenerationFailure? = null,
    val tokensEstimated: Boolean = false,
    /** 协议原生思考状态的持久化信封（不透明字符串），用于向同一模型回传。 */
    val reasoningReplay: String? = null,
    /** [inputTokens] 中的缓存读取部分；null 表示服务商未报告。 */
    val cacheReadInputTokens: Int? = null,
    /** [inputTokens] 中的缓存写入部分；null 表示服务商未报告。 */
    val cacheWriteInputTokens: Int? = null,
    /** [outputTokens] 中的推理部分；null 表示服务商未报告。 */
    val reasoningOutputTokens: Int? = null,
    /** 本轮模型请求的时间点；尚未发出请求时为 null。 */
    val timing: GenerationTiming? = null,
    val cacheWriteLongTtlInputTokens: Int? = null,
)

/** 单轮模型请求的时间点（epoch 毫秒）；未知时间点为 null。 */
data class GenerationTiming(
    /** 发出本轮请求（含其后的自动重试）的时间。 */
    val requestStartedAt: Long,
    /** 首个正文、思考或工具调用增量到达的时间。 */
    val firstOutputAt: Long? = null,
    /** 本轮流结束、失败或被取消的时间。 */
    val endedAt: Long? = null,
)

data class GenerationCitation(val title: String, val url: String, val source: String? = null)

sealed interface GenerationChunk {
    data class Text(val content: String, val reasoning: String? = null) : GenerationChunk
    data class Thinking(val content: String) : GenerationChunk
    /** [encoded] 为已带来源模型的持久化信封。 */
    data class ReasoningState(val encoded: String) : GenerationChunk
    data class ToolCall(val id: String, val name: String, val arguments: String) : GenerationChunk
    data class Usage(
        val input: Int,
        val output: Int,
        val total: Int,
        val cacheReadInput: Int? = null,
        val cacheWriteInput: Int? = null,
        val reasoningOutput: Int? = null,
        val cacheWriteLongTtlInput: Int? = null,
    ) : GenerationChunk
    data class Citations(val citations: List<GenerationCitation>) : GenerationChunk
    data class Failure(val failure: GenerationFailure) : GenerationChunk
    data class Completed(
        val reason: CompletionReason,
        val completedToolCallIds: List<String> = emptyList(),
    ) : GenerationChunk
}

enum class GenerationToolDecision { CONTINUE, WAIT_FOR_APPROVAL, WAIT_FOR_INPUT, COMPLETE }
sealed interface GenerationPreparationOutcome {
    data object Ready : GenerationPreparationOutcome
    data class Handled(val failure: GenerationFailure) : GenerationPreparationOutcome
}
enum class GenerationTerminalStatus { SUCCESS, ERROR, CANCELLED }

/** 流式请求失败后的一次自动重试；[attempt] 从 1 开始，[delayMs] 为本次重发前的等待。 */
data class GenerationRetry(
    val attempt: Int,
    val maxAttempts: Int,
    val code: GenerationFailureCode,
    val delayMs: Long,
)

/** 未配置时网络波动等可重试失败的自动重试次数。 */
const val DEFAULT_STREAM_RETRY_LIMIT = 10

/** 用户可配置的自动重试次数上限。 */
const val MAX_STREAM_RETRY_LIMIT = 20

/** 自动重试次数的偏好键（settings 命名空间）。 */
const val STREAM_RETRY_LIMIT_KEY = "stream_retry_limit"

sealed interface GenerationEvent {
    data class ProgressChanged(val progress: GenerationProgress) : GenerationEvent
    /** 本轮请求即将自动重发；新一轮开始输出后由展示层清除。 */
    data class RetryScheduled(val retry: GenerationRetry) : GenerationEvent
    data class PhaseChanged(val phase: GenerationPhase) : GenerationEvent
    data class SnapshotChanged(val snapshot: GenerationSnapshot) : GenerationEvent
    data class TargetChanged(val assistantMessageId: String) : GenerationEvent
    data class Rejected(val failure: GenerationFailure) : GenerationEvent
    data class PersistenceFailed(
        val persistenceCause: Throwable,
        val originalCause: Throwable? = null,
        val failure: GenerationFailure,
    ) : GenerationEvent {
        override fun toString(): String =
            "PersistenceFailed(code=${failure.code}, " +
                "hasPersistenceCause=true, hasOriginalCause=${originalCause != null})"
    }
    data class Failed(val failure: GenerationFailure) : GenerationEvent
}

fun interface GenerationRunner {
    suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit)
}
