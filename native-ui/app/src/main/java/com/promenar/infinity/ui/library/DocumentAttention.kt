package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.PendingKind

/** 文档页需要用户处理的状态：窄屏详情按钮显示红点，正文上方显示提示条。 */
internal enum class DocumentAttention {
    /** 索引失败，动作为重试。 */
    INDEX_FAILED,

    /** 文件改动后索引过期，动作为重建。 */
    STALE,

    /** 图谱抽取等待确认用量，动作为确认抽取。 */
    GRAPH_CONFIRM,
}

/**
 * 按索引状态与图谱状态判定需要处理的事项；索引问题优先于图谱。
 * 文档还在排队或索引时不提示图谱确认，与详情里图谱动作的禁用条件一致。
 */
internal fun documentAttention(pending: PendingKind?, graph: DocumentGraphState?): DocumentAttention? = when (pending) {
    PendingKind.FAILED -> DocumentAttention.INDEX_FAILED
    PendingKind.STALE -> DocumentAttention.STALE
    PendingKind.QUEUED, PendingKind.INDEXING -> null
    PendingKind.GRAPH_ATTENTION, null ->
        if (graph is DocumentGraphState.ConfirmationRequired) DocumentAttention.GRAPH_CONFIRM else null
}
