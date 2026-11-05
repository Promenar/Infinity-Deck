package com.promenar.infinity.data.rag

/** 任务仍由队列修改；对外发布的状态持有独立快照，使进度变化可被 StateFlow 比较。 */
internal fun snapshotVectorizationQueueState(
    tasks: List<VectorizationTask>,
    isProcessing: Boolean,
    restored: Boolean,
    activeTaskIds: Set<String>,
): VectorizationQueue.QueueState {
    val copies = tasks.map { it.copy() }
    return VectorizationQueue.QueueState(copies, copies.firstOrNull(), isProcessing, restored, activeTaskIds.toSet())
}
