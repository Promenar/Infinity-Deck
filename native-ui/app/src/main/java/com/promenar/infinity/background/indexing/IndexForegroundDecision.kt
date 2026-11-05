package com.promenar.infinity.background.indexing

import com.promenar.infinity.data.rag.VectorizationQueue

internal data class IndexBatch(val taskIds: List<String> = emptyList())

internal data class IndexNotificationState(
    val docTitle: String?,
    val position: Int,
    val total: Int,
    val progressPercent: Int?,
)

internal sealed interface IndexForegroundDecision {
    data object Stop : IndexForegroundDecision
    data class Active(val notification: IndexNotificationState, val batch: IndexBatch) : IndexForegroundDecision
}

/** 活动批次保留已离队的任务序号，直到最后一份文档结束。 */
internal fun reduceIndexForeground(
    state: VectorizationQueue.QueueState,
    batch: IndexBatch = IndexBatch(),
): IndexForegroundDecision {
    val documents = state.queue.filter {
        it.type in DOCUMENT_TYPES &&
            (it.status !in STOPPED_STATUSES || it.status == "interrupted" && it.id in state.activeTaskIds)
    }
        .distinctBy { it.id }
    if (documents.isEmpty()) return IndexForegroundDecision.Stop

    val taskIds = (batch.taskIds + documents.map { it.id }).distinct()
    val current = documents.first()
    val progress = current.totalChunks?.takeIf { it > 0 }?.let { total ->
        (current.lastChunkIndex.toLong().coerceIn(0L, total.toLong()) * 100L / total).toInt()
    }
    return IndexForegroundDecision.Active(
        notification = IndexNotificationState(
            docTitle = current.docTitle?.takeIf { it.isNotBlank() },
            position = taskIds.indexOf(current.id) + 1,
            total = taskIds.size,
            progressPercent = progress,
        ),
        batch = IndexBatch(taskIds),
    )
}

private val DOCUMENT_TYPES = setOf("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
private val STOPPED_STATUSES = setOf("completed", "failed", "partial", "interrupted", "cancelled", "canceled")
