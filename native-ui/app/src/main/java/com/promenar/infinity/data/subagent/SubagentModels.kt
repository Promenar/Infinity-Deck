package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.Serializable

/**
 * 子代理状态。
 * - [QUEUED]：后台子代理并发已满（[SubagentLimits.MAX_BACKGROUND_CONCURRENT]），等待空位；
 * - [WRAPPING_UP]：收到主会话的软停止，正在交回阶段结论；
 * - [STOPPED]：被硬停止（用户在卡片上停止或主 Agent 调用 `session_stop`），不可恢复；
 * - [CANCELLED]：父会话删除或旧版本中随父轮取消；
 * - [INTERRUPTED]：进程重启后失去执行者。
 * 已完成（[COMPLETED]）或软停止后交回的子代理可以经 `session_send` 恢复，恢复后回到 [RUNNING]。
 */
@Serializable
enum class SubagentStatus { QUEUED, RUNNING, WAITING_APPROVAL, WRAPPING_UP, COMPLETED, FAILED, STOPPED, CANCELLED, INTERRUPTED;
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING_APPROVAL || this == WRAPPING_UP
}

/** 子代理可用工具的范围；两档都不含 `agent_task` 与修改父会话状态的工具。 */
@Serializable
enum class SubagentToolProfile(val wireName: String) {
    READ_ONLY("read_only"),
    INHERIT("inherit");

    companion object {
        fun parse(raw: String?): SubagentToolProfile? = entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }
    }
}

/** 预置子代理：固定名称、工具档位与额外说明。 */
enum class SubagentPreset(val wireName: String, val profile: SubagentToolProfile, val instruction: String) {
    EXPLORER(
        "explorer",
        SubagentToolProfile.READ_ONLY,
        "你是只读的探索者：查找、阅读并归纳信息，不修改任何文件或外部系统。结论中注明依据的文件路径或来源。",
    );

    companion object {
        fun parse(raw: String?): SubagentPreset? = entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }
    }
}

/**
 * 子代理时间线上的一条事件。主会话的干预与等待的变化也记在这里，界面以“来自主会话”等事件行展示：
 * - [Kind.PARENT_MESSAGE]：主 Agent 经 `session_send` 追加的指令，[text] 为指令正文；
 * - [Kind.WRAP_UP]：软停止，[text] 为附带说明（可为空）；
 * - [Kind.STOP]：硬停止，[by] 为 [Actor.USER] 或 [Actor.PARENT]；
 * - [Kind.RESUME]：已结束的子代理收到新指令后恢复，[text] 为该指令正文；
 * - [Kind.DETACH]：主会话结束等待、子代理转入后台，[by] 为 [Actor.USER]（点“转入后台”）、[Actor.TIMEOUT] 或 [Actor.PARENT]（主会话本轮结束）。
 */
@Serializable
data class SubagentEvent(
    val kind: Kind,
    val text: String = "",
    val toolName: String? = null,
    val failed: Boolean = false,
    val at: Long,
    val by: Actor? = null,
) {
    @Serializable
    enum class Kind { TEXT, TOOL_CALL, TOOL_RESULT, APPROVAL, ERROR, PARENT_MESSAGE, WRAP_UP, STOP, RESUME, DETACH }

    @Serializable
    enum class Actor { USER, PARENT, TIMEOUT }
}

/** 一个子代理的任务、过程与交回结果；只保存展示所需内容，正文按上限截断。 */
@Serializable
data class SubagentRun(
    val index: Int,
    /** 全应用唯一的子代理地址（如 `sa_3f9k2m`），供 `agent_wait`、`session_*` 工具寻址；旧记录为空。 */
    val id: String = "",
    val preset: String? = null,
    val description: String,
    val prompt: String,
    val toolProfile: SubagentToolProfile,
    val modelId: String,
    /** 继承父会话的推理强度（取值同 [com.promenar.infinity.data.model.InferenceParams.reasoningEffort]）。 */
    val reasoningEffort: String? = null,
    /** 旧版本的固定步数上限；新记录取 [maxTurns] 或 0（不限）。 */
    val maxSteps: Int,
    /** 主 Agent 可选设定的轮数上限，null 表示不限；到达时交回阶段结论并标明未完成。 */
    val maxTurns: Int? = null,
    /** 本子代理的合计 token 上限；父会话设置了任务 token 上限时按子代理数分摊。 */
    val maxTokens: Int = SubagentLimits.MAX_TOTAL_TOKENS,
    val status: SubagentStatus = SubagentStatus.RUNNING,
    val step: Int = 0,
    val currentTool: String? = null,
    /** 正在执行的子代理工具在实时输出存储中的键。 */
    val liveOutputKey: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val events: List<SubagentEvent> = emptyList(),
    val result: String? = null,
    /** 结论超过主会话上下文安全阈值时，全文写入的会话工作区路径；[result] 此时为摘要。 */
    val resultPath: String? = null,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheReadInput: Int? = null,
    val cacheWriteInput: Int? = null,
    val cacheWriteLongTtlInput: Int? = null,
    /** 按请求结算，混合已报告和未报告请求时保留准确分母。 */
    val cache: com.promenar.infinity.domain.repository.CacheUsageAggregate = com.promenar.infinity.domain.repository.CacheUsageAggregate(),
    val dailyCacheUsage: Map<String, com.promenar.infinity.domain.repository.CacheUsageAggregate> = emptyMap(),
    /** 逐请求快照用于检查点恢复、每日报告语义和重置水位。 */
    val cacheRequests: List<SubagentCacheRequestUsage> = emptyList(),
    val tokensEstimated: Boolean = false,
    /** 每次交回结果或重启中断的稳定通知序号。 */
    val completionSequence: Int = 0,
    /** 已由等待回执或持久消息消费的通知序号。 */
    val deliveredSequence: Int = 0,
    /** 未确认送达的每次结论，按完成顺序保留。 */
    val pendingNotices: List<SubagentCompletionNotice> = emptyList(),
    /** 每日实际消费增量；恢复不会把既有消费移动到新的日期。 */
    val dailyUsage: Map<String, com.promenar.infinity.data.model.TokenUsage> = emptyMap(),
    /** 启动时接受的工具名白名单；null 沿用档位工具，空列表禁用全部工具。 */
    val allowedTools: List<String>? = null,
    /** 启动时不存在或超出档位的请求工具名，供工具回执说明。 */
    val ignoredTools: List<String> = emptyList(),
    /** 模型由智能体在 task.model 中指定；false 表示默认子代理模型或会话模型，旧记录为 false。 */
    val modelChosenByAgent: Boolean = false,
)

/** 父会话一次 `agent_task` 调用派生的全部子代理，按该调用的执行步骤 id 归档。 */
@Serializable
data class SubagentBatch(
    val sessionId: String,
    val stepId: String,
    val runs: List<SubagentRun>,
    /** 主会话正在等待这批子代理（`agent_task` 前台等待或 `agent_wait`）；为 null 时没有等待，界面不显示“转入后台”。 */
    val wait: SubagentWait? = null,
)

/** 主会话一侧的等待：[deadline] 到达后工具先返回当前进度，子代理继续运行。 */
@Serializable
data class SubagentWait(
    val startedAt: Long,
    val deadline: Long,
)

/**
 * 界面对子代理的操作，由应用级子代理运行器实现。
 * 两个操作都只作用于指定会话的指定对象，不影响父会话本轮的生成。
 */
interface SubagentControls {
    /** 用户点“转入后台”：立即结束主会话对 [stepId] 这批子代理的等待，工具返回当前进度，子代理照常运行。没有等待时无操作。 */
    fun releaseWait(sessionId: String, stepId: String)

    /** 用户在卡片或查看页点“停止”：硬停止该子代理，等同主 Agent 调用 `session_stop`。已结束时无操作。 */
    suspend fun stop(sessionId: String, runId: String)
}

data class SubagentSpec(
    val description: String,
    val prompt: String,
    val preset: SubagentPreset?,
    val toolProfile: SubagentToolProfile,
    val allowedTools: List<String>? = null,
    /** task.model 原值经 trim 后保存；空串视为未传。 */
    val model: String? = null,
)

object SubagentLimits {
    const val MAX_PARALLEL = 3
    /** 全应用同时运行的后台子代理上限，与会话生成的并发名额分开计数；超出时排队（[SubagentStatus.QUEUED]）。 */
    const val MAX_BACKGROUND_CONCURRENT = 6
    /** 主会话等待子代理的默认超时；主 Agent 可经 `timeout_seconds` 设定。 */
    const val DEFAULT_WAIT_TIMEOUT_MS = 10 * 60_000L
    /** 软停止后最多再给的收尾步数，之后只允许输出结论。 */
    const val WRAP_UP_GRACE_STEPS = 2
    const val WAIT_TOOL_NAME = "agent_wait"
    const val DEFAULT_MAX_STEPS = 12
    const val MAX_TOTAL_TOKENS = 400_000
    const val MAX_OUTPUT_TOKENS = 8_192
    const val MAX_RESULT_CHARS = 12_000
    const val MAX_EVENT_CHARS = 2_000
    const val MAX_EVENTS = 160
    const val MAX_PROMPT_CHARS = 16_000
    const val APPROVAL_TIMEOUT_MS = 10 * 60_000L
    const val TOOL_NAME = "agent_task"
    /** 子代理只交回文本，看不到媒体，排除查看媒体的工具以免据此编造内容。 */
    private val EXCLUDED_TOOLS = setOf(TOOL_NAME, "view_media", "agents_read", "agents_write", com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME)

    /** 子代理不能再派生子代理，也不能改动父会话的计划等内部状态。 */
    fun toolset(parentTools: List<ProtocolTool>, profile: SubagentToolProfile): List<ProtocolTool> = parentTools.filter { tool ->
        tool.function.name !in EXCLUDED_TOOLS && tool.runtimeToolId !in EXCLUDED_TOOLS && tool.risk != ToolRisk.SESSION_STATE && tool.risk != ToolRisk.ACCESS_GRANT && tool.risk != ToolRisk.AGENT_CONFIG &&
            (profile == SubagentToolProfile.INHERIT || tool.risk == ToolRisk.SAFE_READ)
    }

    /** 父会话的任务 token 上限（0 表示不限）按子代理数分摊，并不超过单个子代理的固定上限。 */
    fun tokenBudget(taskTokenLimit: Long, count: Int): Int =
        if (taskTokenLimit <= 0) MAX_TOTAL_TOKENS
        else (taskTokenLimit / count.coerceAtLeast(1)).coerceIn(1, MAX_TOTAL_TOKENS.toLong()).toInt()
}

/** 与旧输入输出水位使用相同 JSON 键，缺少缓存水位的旧文件可直接解码。 */
@Serializable
data class SubagentUsageWatermark(
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0,
    val cache: com.promenar.infinity.domain.repository.CacheUsageAggregate = com.promenar.infinity.domain.repository.CacheUsageAggregate(),
    val cacheRequestIds: Set<String> = emptySet(),
)
