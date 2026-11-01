package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobLog
import com.promenar.infinity.domain.jobs.BackgroundJobState

/** 后端只返回日志末尾的这么多字符（与 BackgroundJobManager 的 takeLast 一致）。 */
internal const val JOB_LOG_TAIL_CHARS = 8_000

/** 运行中的任务每隔这么久刷新一次日志。 */
internal const val JOB_LOG_REFRESH_INTERVAL_MS = 2_000L

/** 单次读取超过这么久仍无结果时提示“仍在读取”并允许取消。 */
internal const val JOB_LOG_SLOW_READ_MS = 5_000L

/**
 * 日志对话框的读取状态。纯数据，由对话框在读取开始、成功、失败与取消时推进；
 * 已读到的日志在后续刷新失败时保留，只额外标记 [failed]。
 */
internal data class JobLogReadState(
    val log: BackgroundJobLog? = null,
    val updatedAtMillis: Long? = null,
    /** 当前读取开始的时间；没有进行中的读取时为 null。 */
    val inFlightSinceMillis: Long? = null,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
) {
    fun started(nowMillis: Long): JobLogReadState =
        copy(inFlightSinceMillis = nowMillis, failed = false, cancelled = false)

    fun succeeded(result: BackgroundJobLog, nowMillis: Long): JobLogReadState =
        copy(log = result, updatedAtMillis = nowMillis, inFlightSinceMillis = null, failed = false, cancelled = false)

    fun failedRead(): JobLogReadState = copy(inFlightSinceMillis = null, failed = true, cancelled = false)

    fun cancelledRead(): JobLogReadState = copy(inFlightSinceMillis = null, failed = false, cancelled = true)

    /** 用户点“重试”：清掉失败与取消标记，让读取循环重新开始。 */
    fun retried(): JobLogReadState = copy(failed = false, cancelled = false)
}

internal enum class JobLogPhase {
    /** 首次读取进行中，未超过 [JOB_LOG_SLOW_READ_MS]。 */
    Loading,

    /** 首次读取超过 [JOB_LOG_SLOW_READ_MS] 仍无结果，显示“仍在读取…”与取消。 */
    SlowLoading,

    /** 首次读取失败，显示错误与重试。 */
    Failed,

    /** 用户取消了首次读取，显示重试。 */
    Cancelled,

    /** 已有日志内容；后续刷新失败另由 [JobLogReadState.failed] 提示。 */
    Content,
}

internal fun resolveJobLogPhase(state: JobLogReadState, nowMillis: Long): JobLogPhase = when {
    state.log != null -> JobLogPhase.Content
    state.failed -> JobLogPhase.Failed
    state.cancelled -> JobLogPhase.Cancelled
    state.inFlightSinceMillis != null && nowMillis - state.inFlightSinceMillis >= JOB_LOG_SLOW_READ_MS -> JobLogPhase.SlowLoading
    else -> JobLogPhase.Loading
}

/**
 * 一次读取结束后到下一次读取的间隔；null 表示停止刷新。
 * 只有任务仍在运行且本次读取成功时才继续，失败或取消后等待用户重试，不反复打后端。
 */
internal fun jobLogRefreshDelayMillis(jobRunning: Boolean, state: JobLogReadState): Long? =
    JOB_LOG_REFRESH_INTERVAL_MS.takeIf { jobRunning && !state.failed && !state.cancelled }

/** 距上次成功读取的整秒数；从未读到时为 null。 */
internal fun jobLogSecondsSinceUpdate(state: JobLogReadState, nowMillis: Long): Long? =
    state.updatedAtMillis?.let { ((nowMillis - it).coerceAtLeast(0L)) / 1_000L }

/** 日志标题后的状态胶囊。 */
internal sealed interface JobLogStatus {
    data object Live : JobLogStatus
    data class Exited(val exitCode: Int?) : JobLogStatus {
        val isFailure: Boolean get() = exitCode != null && exitCode != 0
    }
    data object Stopped : JobLogStatus
    data object TimedOut : JobLogStatus
    data object Interrupted : JobLogStatus
    data object Failed : JobLogStatus
}

/** 任务记录尚未取到或已不在列表中时返回 null，不显示胶囊。 */
internal fun resolveJobLogStatus(job: BackgroundJob?): JobLogStatus? = when (job?.state) {
    null -> null
    BackgroundJobState.RUNNING -> JobLogStatus.Live
    BackgroundJobState.EXITED -> JobLogStatus.Exited(job.exitCode)
    BackgroundJobState.STOPPED -> JobLogStatus.Stopped
    BackgroundJobState.TIMED_OUT -> JobLogStatus.TimedOut
    BackgroundJobState.INTERRUPTED -> JobLogStatus.Interrupted
    BackgroundJobState.FAILED -> JobLogStatus.Failed
}

/** 标题里的任务名：优先智能体写的短标题，没有时用命令摘要。 */
internal fun jobLogDisplayName(job: BackgroundJob?): String? =
    job?.title?.takeIf(String::isNotBlank) ?: job?.summary?.takeIf(String::isNotBlank)

/** 用户停在日志末尾附近时，新内容到达后继续贴底；向上翻阅时不打断。 */
internal fun shouldFollowLogTail(scrollValue: Int, maxScroll: Int, slackPx: Int): Boolean =
    scrollValue >= maxScroll - slackPx
