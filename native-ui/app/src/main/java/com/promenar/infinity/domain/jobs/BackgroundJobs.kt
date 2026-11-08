package com.promenar.infinity.domain.jobs

import kotlinx.coroutines.flow.Flow

enum class BackgroundJobRunner { SHELL, PYTHON, LINUX, TERMUX, SSH, IN_APP }

enum class BackgroundJobState {
    RUNNING,
    /** 进程自行退出，退出码见 [BackgroundJob.exitCode]。 */
    EXITED,
    /** 被用户或 `job_stop` 停止。 */
    STOPPED,
    /** 超过最长运行时间被终止。 */
    TIMED_OUT,
    /** 应用进程重启时任务仍在运行，已清理残留进程。 */
    INTERRUPTED,
    /** 启动失败或状态无法确认。 */
    FAILED,
    ;

    val isTerminal: Boolean get() = this != RUNNING
}

/** 任务结束时的处理方式。 */
enum class BackgroundJobExitAction(val wireName: String) {
    NONE("none"),
    NOTIFY("notify"),
    WAKE("wake"),
    ;

    companion object {
        fun fromWire(value: String?): BackgroundJobExitAction? = entries.firstOrNull { it.wireName == value }
    }
}

data class BackgroundJob(
    val id: String,
    val sessionId: String,
    val runner: BackgroundJobRunner,
    /** 单行命令摘要。 */
    val summary: String,
    val state: BackgroundJobState,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val maxRuntimeMinutes: Int,
    val onExit: BackgroundJobExitAction,
    /** 任务日志引用，由 BackgroundJobController.readLog 读取，不是工作区路径。 */
    val logWorkspacePath: String?,
    /** 实时输出在 `ToolLiveOutputStore` 中的键，供界面复用实时输出面板。 */
    val liveOutputKey: String,
    /** [BackgroundJobRunner.SSH] 任务所在的主机 ID，与运行环境标签一起显示；本地任务为 null。 */
    val remoteHost: String? = null,
    /** SFTP 传输任务的方向、路径与进度；命令任务为 null。 */
    val transfer: SftpTransfer? = null,
    /** 智能体为命令写的短标题；界面优先显示它，没有时显示命令原文。 */
    val title: String? = null,
)

enum class SftpTransferDirection { UPLOAD, DOWNLOAD }

/** 传输中断原因；正常完成或仍在进行时为 null。 */
enum class SftpTransferFailure {
    /** 连接断开或网络切换。 */
    NETWORK,
    /** 系统前台服务时限到期被断开（Play 版 dataSync）。 */
    TIME_LIMIT,
    /** 用户或 `job_stop` 停止。 */
    STOPPED,
    /** 传输完成但 SHA-256 与源文件不一致，目标未替换。 */
    CHECKSUM_MISMATCH,
    /** 目标已存在且未允许覆盖，或目标在传输期间被改动。 */
    TARGET_CONFLICT,
    /** 本地或远端空间不足。 */
    NO_SPACE,
    /** 其它错误，详情见任务日志。 */
    OTHER,
}

/**
 * 传输任务的界面描述。路径为模型可见路径：工作区以 `/` 为根，Linux 范围为 Linux 内的绝对路径。
 * [transferredBytes] 由运行时节流更新（约每 500 毫秒或每 1 MiB 一次）。
 */
data class SftpTransfer(
    val direction: SftpTransferDirection,
    /** `workspace` 或 `linux`。 */
    val localScope: String,
    val localPath: String,
    val remotePath: String,
    /** 未知时为 null（远端 stat 尚未完成）。 */
    val totalBytes: Long?,
    val transferredBytes: Long,
    /** 最近窗口的平均速度；刚开始或已结束时为 null。 */
    val bytesPerSecond: Long?,
    /** 完成且校验通过后的 SHA-256。 */
    val sha256: String? = null,
    val failure: SftpTransferFailure? = null,
)

data class BackgroundJobLog(val text: String, val truncated: Boolean)

/** 界面使用的后台任务入口。 */
interface BackgroundJobController {
    /** [sessionId] 为 null 时返回全部会话的任务，按启动时间倒序。 */
    fun observe(sessionId: String?): Flow<List<BackgroundJob>>

    /** 会话归属校验后读取应用私有日志，兼容实时任务的日志末尾。 */
    suspend fun readLog(sessionId: String, reference: String): BackgroundJobLog =
        throw UnsupportedOperationException("任务日志读取不可用")

    /** 返回 false 表示任务不存在或已经结束。 */
    suspend fun stop(jobId: String): Boolean
}
