package com.promenar.infinity.domain.storage

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class RemoteTransferDirection { UPLOAD, DOWNLOAD, PREVIEW_CACHE }

@Serializable
enum class RemoteTransferState {
    QUEUED, PREFLIGHT, TRANSFERRING, VERIFYING, COMMITTING, PAUSING, PAUSED, CANCELLING, CANCELLED, SUCCEEDED, FAILED, RECONCILING,
}

@Serializable
enum class RemoteOverwritePolicy { REFUSE_DIFFERING, ALLOW_OVERWRITE }

@Serializable
data class RemoteTransferReceipt(
    val mountPath: String,
    val remotePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val verificationMethod: String,
    val transferId: String,
    val deduplicated: Boolean = false,
)

@Serializable
data class RemoteTransferTask(
    val id: String,
    val direction: RemoteTransferDirection,
    val state: RemoteTransferState,
    val mountId: String,
    val hostAlias: String,
    val fileName: String,
    val sourceLabel: String,
    val targetLabel: String,
    val mountVirtualPath: String,
    val remoteFullPath: String,
    val localTargetPath: String?,
    val bytesTotal: Long,
    val bytesTransferred: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val overwritePolicy: RemoteOverwritePolicy,
    val canResume: Boolean,
    val receipt: RemoteTransferReceipt?,
    val errorCode: String?,
    val errorMessage: String?,
    val sessionId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class RemoteTransferSummary(
    val activeCount: Int,
    val pausedCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val aggregateBytesPerSecond: Long,
)

/** 调用方在入队前确认本地授权，管理器在每次启动时重新校验挂载身份与权限。 */
data class RemoteTransferRequest(
    val mountId: String,
    val hostAlias: String,
    val mountVirtualPath: String,
    val remoteFullPath: String,
    val localPath: String? = null,
    val sourceLabel: String = "",
    val targetLabel: String = "",
    val overwritePolicy: RemoteOverwritePolicy = RemoteOverwritePolicy.REFUSE_DIFFERING,
    val sessionId: String? = null,
)

interface RemoteTransferController {
    val tasks: StateFlow<List<RemoteTransferTask>>
    val summary: StateFlow<RemoteTransferSummary>
    suspend fun enqueueUpload(request: RemoteTransferRequest): RemoteTransferTask
    suspend fun enqueueDownload(request: RemoteTransferRequest): RemoteTransferTask
    suspend fun enqueuePreviewCache(request: RemoteTransferRequest): RemoteTransferTask
    suspend fun pause(taskId: String)
    suspend fun pauseAll()
    suspend fun resume(taskId: String)
    suspend fun resumeAll()
    suspend fun cancel(taskId: String)
    suspend fun retry(taskId: String)
    suspend fun remove(taskId: String)
    suspend fun clearFinished()
    suspend fun awaitTask(taskId: String, waitMs: Long): RemoteTransferTask?
}
