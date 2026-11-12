package com.promenar.infinity.domain.mediagen

import kotlinx.coroutines.flow.Flow

enum class MediaTaskState { AWAITING_APPROVAL, QUEUED, RUNNING, COMPLETED, FAILED }

data class MediaTask(
    val id: String,
    val request: MediaGenerationRequest,
    val estimate: Money,
    val state: MediaTaskState = MediaTaskState.AWAITING_APPROVAL,
    val remoteId: String? = null,
    val progress: Float? = null,
    val outputPaths: List<String> = emptyList(),
    val actualCost: Money? = null,
    val error: String? = null,
) {
    init { require(id.isNotBlank() && (progress == null || progress in 0f..1f)) }
    fun approve(): MediaTask { check(state == MediaTaskState.AWAITING_APPROVAL); return copy(state = MediaTaskState.QUEUED) }
    fun started(id: String): MediaTask {
        check(state == MediaTaskState.QUEUED && remoteId == null)
        require(id.isNotBlank())
        return copy(state = MediaTaskState.RUNNING, remoteId = id)
    }
    fun failed(message: String): MediaTask {
        check(state == MediaTaskState.QUEUED || state == MediaTaskState.RUNNING)
        require(message.isNotBlank())
        return copy(state = MediaTaskState.FAILED, error = message)
    }
    fun retry(): MediaTask {
        check(state == MediaTaskState.FAILED)
        return copy(state = if (remoteId == null) MediaTaskState.QUEUED else MediaTaskState.RUNNING, error = null)
    }
    fun completed(paths: List<String>, actual: Money?): MediaTask {
        check(state == MediaTaskState.RUNNING)
        require(paths.isNotEmpty() && paths.all { it.isNotBlank() })
        return copy(state = MediaTaskState.COMPLETED, outputPaths = paths.toList(), actualCost = actual, progress = 1f, error = null)
    }
}

/** 队列的实现须先持久化状态，再通知观察者并执行外部操作。 */
interface MediaGenerationQueue {
    suspend fun submit(request: MediaGenerationRequest): MediaTask
    fun observeTasks(): Flow<List<MediaTask>>
    fun observeTask(id: String): Flow<MediaTask?>
    suspend fun approve(id: String)
    suspend fun reject(id: String)
    suspend fun retry(id: String)
}
