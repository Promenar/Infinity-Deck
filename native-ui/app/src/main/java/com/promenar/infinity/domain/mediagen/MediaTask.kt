package com.promenar.infinity.domain.mediagen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
enum class MediaTaskState {
    /** 仅用于读取升级前的待批准记录。 */
    AWAITING_APPROVAL,
    QUEUED, SUBMITTING, RUNNING, DOWNLOADING, COMPLETED, FAILED, DOWNLOAD_FAILED, RESULT_UNKNOWN, CANCELED,
}

@Serializable
enum class MediaTaskFailure { REMOTE, POLL, DOWNLOAD, SUBMISSION_UNKNOWN, CHANNEL_UNAVAILABLE, INVALID_OUTPUT }

@Serializable
data class MediaTask(
    val id: String,
    val request: MediaGenerationRequest,
    /** null 表示未知，不使用零费用代替。 */
    val estimate: Money? = null,
    val state: MediaTaskState = MediaTaskState.QUEUED,
    val remoteId: String? = null,
    val progress: Float? = null,
    val outputPaths: List<String> = emptyList(),
    val actualCost: Money? = null,
    val error: String? = null,
    val failure: MediaTaskFailure? = null,
    /** 旧记录缺少创建时间时为 0。 */
    val createdAt: Long = 0,
) {
    init { require(id.isNotBlank() && (progress == null || progress in 0f..1f)) }
    fun submitting(): MediaTask {
        check(state == MediaTaskState.QUEUED && remoteId == null)
        return copy(state = MediaTaskState.SUBMITTING, failure = null, error = null)
    }
    fun started(id: String): MediaTask {
        check(state in setOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING) && remoteId == null)
        require(id.isNotBlank())
        return copy(state = MediaTaskState.RUNNING, remoteId = id, error = null, failure = null)
    }
    fun failed(message: String): MediaTask {
        check(state in setOf(MediaTaskState.QUEUED, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING))
        require(message.isNotBlank())
        return copy(state = MediaTaskState.FAILED, error = message)
    }
    fun canceled(): MediaTask {
        check(state in setOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING))
        return copy(state = MediaTaskState.CANCELED, error = null, failure = null)
    }
    fun completed(paths: List<String>, actual: Money?): MediaTask {
        check(state in setOf(MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING))
        require(paths.isNotEmpty() && paths.all { it.isNotBlank() })
        return copy(state = MediaTaskState.COMPLETED, outputPaths = paths.toList(), actualCost = actual, progress = 1f, error = null, failure = null)
    }
}

/** 队列的实现须先持久化状态，再通知观察者并执行外部操作。 */
interface MediaGenerationQueue {
    suspend fun submit(request: MediaGenerationRequest): MediaTask
    fun observeTasks(): Flow<List<MediaTask>>
    fun observeTask(id: String): Flow<MediaTask?>
    suspend fun cancel(id: String)
}

/** sourceRef 保持不透明，前缀约定由制作项目调用方维护。 */
fun MediaGenerationQueue.observeTasks(sourceRefPrefix: String): Flow<List<MediaTask>> =
    observeTasks().map { tasks -> tasks.filter { it.request.sourceRef.startsWith(sourceRefPrefix) } }
