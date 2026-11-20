package com.promenar.infinity.domain.trigger

/** 系统触发轮次的来源。 */
enum class SystemTurnKind(val wireName: String) {
    /** 定时任务到点。 */
    SCHEDULE("schedule"),
    /** 后台任务结束且要求唤醒 Agent。 */
    JOB_EXIT("job_exit"),
    /** 后台子代理完成、失败或交回阶段结论；[SystemTurnRequest.sourceId] 为子代理 id，正文为结论或摘要与全文路径。 */
    SUBAGENT_DONE("subagent_done"),
    /** 会话间持久消息，复用步骤边界送达。 */
    SESSION_MESSAGE("session_message"),
    /** Linux 模式会话内脚本经 `infinity-wake` 投递的唤醒；[SystemTurnRequest.sourceId] 为 `wake:<key>`。 */
    SCRIPT_WAKE("script_wake"),
    ;

    companion object {
        fun fromWire(value: String): SystemTurnKind? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 请求在 [sessionId] 中由应用自行发起一轮生成。
 *
 * 同一 [sourceId] 最多排队一个待发请求；新的请求到达时替换尚未发出的旧请求，旧请求以
 * [SystemTurnSkipReason.SUPERSEDED] 结束。
 * 子代理完成通知按来源与 [sequence] 区分，恢复运行后的结论不会覆盖前一次尚未送达的结论。
 */
data class SystemTurnRequest(
    val sessionId: String,
    val kind: SystemTurnKind,
    /** 定时任务 id 或后台任务 id。 */
    val sourceId: String,
    /** 展示用短标题：定时任务名称或命令摘要。 */
    val title: String,
    /** 发给模型的正文，不含触发标记行。 */
    val body: String,
    /** 第几次触发；子代理通知重试时保持不变，恢复运行后的新通知递增；后台任务为 null。 */
    val sequence: Int? = null,
    /** 错过触发后的补发。 */
    val catchUp: Boolean = false,
    /** 完成通知的持久送达标识；合并通知时保留全部来源与通知序号。 */
    val deliveryIds: List<String> = emptyList(),
    /** 运行内送达租约；消息持久化前与工具等待互斥，失败后释放以便重试。 */
    val delivery: SystemTurnDelivery? = null,
    /** 轮内完成通知关联当前助手；生成首步关联空占位，续步关联刚结束的助手步骤。 */
    val consumerAssistantId: String? = null,
)

enum class SystemTurnSkipReason {
    /** 会话已删除。 */
    SESSION_MISSING,
    /** 30 分钟内一直有其它生成在运行。 */
    BUSY_TIMEOUT,
    /** 同一来源的较新请求替换了它。 */
    SUPERSEDED,
    /** 应用数据尚未就绪（例如备份恢复中）就被系统结束。 */
    NOT_READY,
}

sealed interface SystemTurnOutcome {
    /** 本轮正常结束。 */
    data object Completed : SystemTurnOutcome

    /** 本轮因审批或确认暂停，已发通知等待用户。 */
    data object NeedsUser : SystemTurnOutcome

    data class Skipped(val reason: SystemTurnSkipReason) : SystemTurnOutcome

    /** 生成失败或被系统中断；[message] 为面向用户的简短说明。 */
    data class Failed(val message: String) : SystemTurnOutcome
}

/** 应用级系统触发调度器：等待数据就绪、载入会话、插入触发消息并发起生成。 */
interface SystemTurnDispatcher {
    /** 挂起到该轮结束、暂停或被跳过。调用方取消协程只撤回尚未发出的请求，不停止已开始的生成。 */
    suspend fun request(request: SystemTurnRequest): SystemTurnOutcome
}

/** 完成与通信通知共享可靠送达桥，普通定时来源仍使用原有过期规则。 */
val SystemTurnKind.usesSteeringDelivery: Boolean
    get() = this == SystemTurnKind.SUBAGENT_DONE || this == SystemTurnKind.SESSION_MESSAGE || this == SystemTurnKind.JOB_EXIT
