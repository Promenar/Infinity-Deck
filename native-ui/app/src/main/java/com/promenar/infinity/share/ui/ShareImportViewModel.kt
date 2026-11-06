package com.promenar.infinity.share.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.ShareIntentQueue
import com.promenar.infinity.ShareLease
import com.promenar.infinity.share.core.ShareImportBatchResult
import com.promenar.infinity.share.core.ShareImportDestination
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportOptions
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareIndexStatus
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareLinuxAvailability
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.share.core.ShareTargetKind
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.data.rag.VectorizationQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

enum class SharePresentationState { Idle, Presenting, Visible }

enum class ShareImportErrorCode {
    PRESENT_FAILED,
    IMPORT_FAILED,
    INDEX_RETRY_FAILED,
    POSTPONE_FAILED,
    CANCEL_FAILED,
}

/** 导入面板的落点数据源；MainActivity 绑定当前会话后适配 ShareImportTargetProvider。 */
interface ShareImportTargetSource {
    suspend fun options(): ShareImportOptions
    suspend fun sessions(excludeSessionId: String?): List<ShareSessionChoice>
    suspend fun knowledgeFolders(parentUuid: String): List<ShareKnowledgeFolder>
    suspend fun sessionDestination(sessionId: String, current: Boolean): ShareImportDestination.SessionFolder?
}

/** “其他会话”的会话列表；只列出，不预选。 */
data class ShareSessionPickerState(
    val sessions: List<ShareSessionChoice> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** 存储空间文件夹选择器：[trail] 是根目录以下已进入的各级文件夹，空表示根目录。 */
data class ShareFolderPickerState(
    val trail: List<ShareKnowledgeFolder> = emptyList(),
    val folders: List<ShareKnowledgeFolder> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

data class ShareImportUiState(
    val visible: Boolean = false,
    val items: List<ShareImportItem> = emptyList(),
    val options: ShareImportOptions? = null,
    val selectedKind: ShareTargetKind? = null,
    /** 用户在会话列表里亲手选的会话；未选时“其他会话”不能导入。 */
    val otherSession: ShareSessionChoice? = null,
    val linuxDirectory: String? = null,
    /** null 表示存储空间根目录。 */
    val knowledgeFolder: ShareKnowledgeFolder? = null,
    val sessionPicker: ShareSessionPickerState? = null,
    val folderPicker: ShareFolderPickerState? = null,
    val importing: Boolean = false,
    val presentation: SharePresentationState = SharePresentationState.Idle,
    val pendingCount: Int = 0,
    val error: ShareImportErrorCode? = null,
) {
    val linuxReady: Boolean get() = options?.linux == ShareLinuxAvailability.Ready

    /** 选中的落点是否完整可用。 */
    val canImportTo: Boolean get() = when (selectedKind) {
        ShareTargetKind.CurrentSession -> options?.currentSession != null
        ShareTargetKind.OtherSession -> otherSession != null
        ShareTargetKind.Linux -> linuxReady && linuxDirectory != null
        ShareTargetKind.KnowledgeBase -> options != null
        null -> false
    }
}

class ShareImportViewModel(
    private val queue: ShareIntentQueue,
    private val importer: SharedFileImporter,
    private val targets: ShareImportTargetSource,
    private val indexQueueState: StateFlow<VectorizationQueue.QueueState>? = null,
    private val retryIndex: suspend (String, String) -> String? = { _, _ -> null },
) : ViewModel() {
    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()
    private var lease: ShareLease? = null
    private var completedRequest: com.promenar.infinity.ShareRequest? = null
    /** 最近一次导入实际使用的落点；索引重试按它的工作区根执行。 */
    private var lastDestination: ShareImportDestination? = null
    private val presentMutex = Mutex()
    private var pickerJob: Job? = null

    init {
        viewModelScope.launch {
            queue.refreshDurableCount()
            queue.durablePendingCount.collectLatest { count ->
                _state.value = _state.value.copy(pendingCount = count)
            }
        }
        indexQueueState?.let { states ->
            viewModelScope.launch {
                states.collectLatest { queueState -> reconcileIndexState(queueState) }
            }
        }
    }

    fun presentNext() {
        viewModelScope.launch {
            presentMutex.withLock {
                if (_state.value.presentation != SharePresentationState.Idle || _state.value.importing) return@withLock
                _state.value = _state.value.copy(presentation = SharePresentationState.Presenting)
                // 呈现阶段若 durable nack 失败，保留原 lease 并重放同一次呈现，
                // 不得再 claim 另一条请求导致顺序错乱。
                val claimed = lease ?: queue.claimNextDurably()
                if (claimed == null) {
                    _state.value = _state.value.copy(presentation = SharePresentationState.Idle)
                    return@withLock
                }
                lease = claimed
                try {
                    val options = targets.options()
                    val items = importer.inspect(claimed.request)
                    _state.value = restoreSelection(
                        ShareImportUiState(
                            visible = true,
                            items = items,
                            options = options,
                            presentation = SharePresentationState.Visible,
                            pendingCount = _state.value.pendingCount,
                        ),
                        claimed.request.targetDestination,
                    )
                    indexQueueState?.value?.let(::reconcileIndexState)
                } catch (cancelled: CancellationException) {
                    nackAfterCancellation(claimed)
                    throw cancelled
                } catch (failure: Exception) {
                    val released = tryNack(claimed)
                    if (released) lease = null
                    _state.value = _state.value.copy(
                        // PRESENT_FAILED 必须留在可见恢复面，不能只剩一个无法解释的 pending banner。
                        visible = true,
                        presentation = SharePresentationState.Idle,
                        error = ShareImportErrorCode.PRESENT_FAILED,
                    )
                }
            }
        }
    }

    /**
     * 切换导入位置。“其他会话”还没选会话时打开会话列表；Linux 不可用时忽略；
     * 选中 Linux 时目录默认为 Linux 主目录。
     */
    fun selectTarget(kind: ShareTargetKind) {
        val state = _state.value
        val options = state.options ?: return
        if (state.importing) return
        when (kind) {
            ShareTargetKind.CurrentSession -> if (options.currentSession == null) return
            ShareTargetKind.Linux -> if (!state.linuxReady) return
            ShareTargetKind.OtherSession, ShareTargetKind.KnowledgeBase -> Unit
        }
        _state.value = state.copy(
            selectedKind = kind,
            linuxDirectory = if (kind == ShareTargetKind.Linux) state.linuxDirectory ?: options.linuxDefaultDirectory else state.linuxDirectory,
        )
        if (kind == ShareTargetKind.OtherSession && state.otherSession == null) openSessionPicker()
    }

    fun openSessionPicker() {
        val options = _state.value.options ?: return
        _state.value = _state.value.copy(sessionPicker = ShareSessionPickerState())
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            val picker = try {
                ShareSessionPickerState(targets.sessions(options.currentSession?.sessionId), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ShareSessionPickerState(loading = false, failed = true)
            }
            if (_state.value.sessionPicker != null) _state.value = _state.value.copy(sessionPicker = picker)
        }
    }

    fun pickSession(choice: ShareSessionChoice) {
        val picker = _state.value.sessionPicker ?: return
        if (picker.sessions.none { it.sessionId == choice.sessionId }) return
        _state.value = _state.value.copy(
            selectedKind = ShareTargetKind.OtherSession,
            otherSession = choice,
            sessionPicker = null,
        )
    }

    fun dismissSessionPicker() {
        pickerJob?.cancel()
        _state.value = _state.value.copy(sessionPicker = null)
    }

    /** 文件夹选择器总是从存储空间根目录打开。 */
    fun openFolderPicker() = loadFolders(emptyList())

    fun enterFolder(folder: ShareKnowledgeFolder) {
        val picker = _state.value.folderPicker ?: return
        if (picker.loading || picker.folders.none { it.uuid == folder.uuid }) return
        loadFolders(picker.trail + folder)
    }

    fun folderUp() {
        val picker = _state.value.folderPicker ?: return
        if (picker.trail.isEmpty()) return
        loadFolders(picker.trail.dropLast(1))
    }

    fun confirmFolder() {
        val picker = _state.value.folderPicker ?: return
        if (picker.loading || picker.failed) return
        _state.value = _state.value.copy(
            selectedKind = ShareTargetKind.KnowledgeBase,
            knowledgeFolder = picker.trail.lastOrNull(),
            folderPicker = null,
        )
    }

    fun dismissFolderPicker() {
        pickerJob?.cancel()
        _state.value = _state.value.copy(folderPicker = null)
    }

    /** Linux 目录选择器选定的容器内目录；Linux 不可用时忽略。 */
    fun pickLinuxDirectory(path: String) {
        if (!_state.value.linuxReady || !path.startsWith('/') || _state.value.importing) return
        _state.value = _state.value.copy(selectedKind = ShareTargetKind.Linux, linuxDirectory = path)
    }

    private fun loadFolders(trail: List<ShareKnowledgeFolder>) {
        val root = _state.value.options?.knowledgeRootUuid ?: return
        _state.value = _state.value.copy(folderPicker = ShareFolderPickerState(trail = trail))
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            val picker = try {
                ShareFolderPickerState(trail, targets.knowledgeFolders(trail.lastOrNull()?.uuid ?: root), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ShareFolderPickerState(trail, loading = false, failed = true)
            }
            if (_state.value.folderPicker != null) _state.value = _state.value.copy(folderPicker = picker)
        }
    }

    /** 把界面选择解析为导入落点；会话已不存在时返回 null。 */
    private suspend fun resolveDestination(state: ShareImportUiState): ShareImportDestination? {
        val options = state.options ?: return null
        return when (state.selectedKind) {
            ShareTargetKind.CurrentSession -> options.currentSession?.let { targets.sessionDestination(it.sessionId, current = true) }
            ShareTargetKind.OtherSession -> state.otherSession?.let { targets.sessionDestination(it.sessionId, current = false) }
            ShareTargetKind.Linux -> state.linuxDirectory?.takeIf { state.linuxReady }?.let(ShareImportDestination::LinuxDirectory)
            ShareTargetKind.KnowledgeBase -> ShareImportDestination.KnowledgeFolder(
                workspaceRootUuid = options.knowledgeRootUuid,
                folderUuid = state.knowledgeFolder?.uuid ?: options.knowledgeRootUuid,
                displayPath = state.knowledgeFolder?.path ?: "/",
            )
            null -> null
        }
    }

    /**
     * 恢复收件箱里记录的落点（用户上次亲手选过的）；没有记录或记录已失效时，
     * 有当前会话就选当前会话，否则选存储空间根目录。“其他会话”从不在这里替用户挑选。
     */
    private suspend fun restoreSelection(base: ShareImportUiState, saved: ShareImportDestination?): ShareImportUiState {
        val options = base.options ?: return base
        val fallback = base.copy(
            selectedKind = if (options.currentSession != null) ShareTargetKind.CurrentSession else ShareTargetKind.KnowledgeBase,
        )
        return when (saved) {
            null -> fallback
            is ShareImportDestination.SessionFolder -> when {
                saved.sessionId == options.currentSession?.sessionId -> base.copy(selectedKind = ShareTargetKind.CurrentSession)
                else -> targets.sessions(options.currentSession?.sessionId)
                    .firstOrNull { it.sessionId == saved.sessionId }
                    ?.let { base.copy(selectedKind = ShareTargetKind.OtherSession, otherSession = it) }
                    ?: fallback
            }
            is ShareImportDestination.LinuxDirectory ->
                if (options.linux == ShareLinuxAvailability.Ready) base.copy(selectedKind = ShareTargetKind.Linux, linuxDirectory = saved.path)
                else fallback
            is ShareImportDestination.KnowledgeFolder ->
                if (saved.workspaceRootUuid != options.knowledgeRootUuid) fallback
                else base.copy(
                    selectedKind = ShareTargetKind.KnowledgeBase,
                    knowledgeFolder = saved.folderUuid.takeIf { it != saved.workspaceRootUuid }?.let { uuid ->
                        ShareKnowledgeFolder(uuid, saved.displayPath.trimEnd('/').substringAfterLast('/'), saved.displayPath)
                    },
                )
        }
    }

    fun importAll() {
        val active = lease ?: return
        val selection = _state.value
        if (!selection.canImportTo || selection.importing) return
        _state.value = _state.value.copy(importing = true, error = null)
        viewModelScope.launch {
            try {
                val destination = resolveDestination(selection)
                    ?: throw ShareImportPersistenceException("导入位置已失效")
                queue.recordTargetDurably(active.request.requestId, destination)
                lastDestination = destination
                val result = importer.import(active.request, destination)
                completedRequest = active.request
                // 先把实际导入结果呈现出来；即使后续 manifest/ack 失败，也不会伪装成未创建。
                _state.value = _state.value.copy(items = result.items)
                indexQueueState?.value?.let(::reconcileIndexState)
                queue.recordCreatedDurably(active.request.requestId, result.created)
                if (result.rejected.isEmpty()) {
                    if (!queue.ackDurably(active.token)) throw ShareImportPersistenceException("确认导入结果失败")
                    lease = null
                }
            } catch (cancelled: CancellationException) {
                nackAfterCancellation(active)
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = ShareImportErrorCode.IMPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(importing = false)
            }
        }
    }

    fun retryRejected() {
        val request = completedRequest ?: lease?.request ?: return
        val selection = _state.value
        if (!selection.canImportTo) return
        val failed = _state.value.items.filter {
            it.status == ShareImportStatus.Rejected && it.reason?.retryable == true
        }.mapTo(linkedSetOf()) { it.uri }
        val failedIndexes = _state.value.items.filter {
            it.status == ShareImportStatus.Created &&
                it.indexStatus in setOf(ShareIndexStatus.Failed, ShareIndexStatus.Partial) &&
                it.fileUuid != null
        }
        if (_state.value.importing) return
        if (failed.isEmpty() && failedIndexes.isNotEmpty()) {
            _state.value = _state.value.copy(importing = true, error = null)
            viewModelScope.launch {
                try {
                    val root = when (val destination = lastDestination ?: resolveDestination(selection)) {
                        is ShareImportDestination.SessionFolder -> destination.workspaceRootUuid
                        is ShareImportDestination.KnowledgeFolder -> destination.workspaceRootUuid
                        is ShareImportDestination.LinuxDirectory, null -> throw ShareImportPersistenceException("落点没有索引")
                    }
                    // 队列为重试换用新的任务标识，旧标识随即从快照中消失；重试挂起期间先解除对旧标识的跟踪，
                    // 否则界面线程处理到这份快照时会把它误判为已完成。
                    val retrying = failedIndexes.mapTo(mutableSetOf()) { it.fileUuid!! }
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { item ->
                            if (item.fileUuid in retrying) item.copy(indexStatus = ShareIndexStatus.Pending, indexTaskId = null) else item
                        },
                    )
                    val retriedTasks = retrying.associateWith { fileUuid -> retryIndex(root, fileUuid) }
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { item ->
                            val fileUuid = item.fileUuid?.takeIf { it in retrying } ?: return@map item
                            val taskId = retriedTasks[fileUuid]
                            if (taskId != null) item.copy(indexTaskId = taskId)
                            else failedIndexes.first { it.fileUuid == fileUuid }
                        },
                    )
                    if (retriedTasks.values.any { it == null }) throw ShareImportPersistenceException("索引任务暂时无法重试")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(error = ShareImportErrorCode.INDEX_RETRY_FAILED)
                } finally {
                    _state.value = _state.value.copy(importing = false)
                }
            }
            return
        }
        if (failed.isEmpty()) {
            // recordTarget/recordCreated/ack 任一步失败都可安全重放；importer 以 staged SHA 幂等复用已创建文件。
            importAll()
            return
        }
        _state.value = _state.value.copy(importing = true, error = null)
        viewModelScope.launch {
            try {
                val destination = resolveDestination(selection)
                    ?: throw ShareImportPersistenceException("导入位置已失效")
                lastDestination = destination
                val retried = importer.import(request, destination, failed)
                val merged = mergeRetry(_state.value.items, retried)
                _state.value = _state.value.copy(items = merged)
                queue.recordCreatedDurably(request.requestId, retried.created)
                if (merged.none { it.status == ShareImportStatus.Rejected }) {
                    lease?.let { active ->
                        if (!queue.ackDurably(active.token)) throw ShareImportPersistenceException("确认重试结果失败")
                        lease = null
                    }
                }
            } catch (cancelled: CancellationException) {
                lease?.let { nackAfterCancellation(it) }
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = ShareImportErrorCode.IMPORT_FAILED)
            } finally {
                _state.value = _state.value.copy(importing = false)
            }
        }
    }

    /**
     * 重试按钮只重放产生当前错误的操作；无显式错误时保持逐项导入/索引重试语义。
     */
    fun retryLastFailure() {
        when (_state.value.error) {
            ShareImportErrorCode.PRESENT_FAILED -> presentNext()
            ShareImportErrorCode.IMPORT_FAILED,
            ShareImportErrorCode.INDEX_RETRY_FAILED,
            null,
            -> retryRejected()
            ShareImportErrorCode.POSTPONE_FAILED -> postpone()
            ShareImportErrorCode.CANCEL_FAILED -> cancelConfirmed()
        }
    }

    fun postpone(): Boolean {
        if (_state.value.importing) return false
        val shouldAdvance = lease == null
        val active = lease
        viewModelScope.launch {
            try {
                if (active == null || queue.nackDurably(active.token)) {
                    if (lease === active) lease = null
                    completedRequest = null
                    lastDestination = null
                    _state.value = ShareImportUiState(pendingCount = queue.durablePendingCount.value)
                } else {
                    _state.value = _state.value.copy(error = ShareImportErrorCode.POSTPONE_FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = ShareImportErrorCode.POSTPONE_FAILED)
            }
        }
        return shouldAdvance
    }

    fun cancelConfirmed() {
        if (_state.value.importing) return
        val active = lease ?: return
        viewModelScope.launch {
            try {
                if (queue.dropDurably(active.token)) {
                    lease = null
                    completedRequest = null
                    lastDestination = null
                    _state.value = ShareImportUiState(pendingCount = queue.durablePendingCount.value)
                } else {
                    _state.value = _state.value.copy(error = ShareImportErrorCode.CANCEL_FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = ShareImportErrorCode.CANCEL_FAILED)
            }
        }
    }

    @Deprecated("使用 postpone 明确表达稍后处理")
    fun close(): Boolean = postpone()

    private fun mergeRetry(
        previous: List<ShareImportItem>,
        retry: ShareImportBatchResult,
    ): List<ShareImportItem> {
        val replacements = retry.items.associateBy { it.uri }
        return previous.map { replacements[it.uri] ?: it }
    }

    private suspend fun nackAfterCancellation(active: ShareLease) {
        try {
            if (queue.nackDurably(active.token) && lease === active) lease = null
        } catch (_: IOException) {
            // 取消路径不得覆盖原始 CancellationException；durable lease 可由新进程按 token/超时恢复。
        } catch (_: IllegalArgumentException) {
            // 同上。
        } catch (_: IllegalStateException) {
            // 取消路径不得覆盖原始 CancellationException；durable lease 可由新进程按 token/超时恢复。
        }
    }

    private suspend fun tryNack(active: ShareLease): Boolean = try {
        queue.nackDurably(active.token)
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun reconcileIndexState(queueState: VectorizationQueue.QueueState) {
        if (!queueState.restored && queueState.queue.isEmpty()) return
        val byId = queueState.queue.associateBy { it.id }
        _state.value = _state.value.copy(
            items = _state.value.items.map { item ->
                val taskId = item.indexTaskId ?: return@map item
                val task = byId[taskId]
                val status = when (task?.status) {
                    "failed" -> ShareIndexStatus.Failed
                    "partial" -> ShareIndexStatus.Partial
                    "completed" -> ShareIndexStatus.Completed
                    null -> if (queueState.restored) ShareIndexStatus.Completed else item.indexStatus
                    else -> ShareIndexStatus.Pending
                }
                item.copy(indexStatus = status)
            },
        )
    }

    companion object {
        fun factory(
            queue: ShareIntentQueue,
            importer: SharedFileImporter,
            targets: ShareImportTargetSource,
            indexQueueState: StateFlow<VectorizationQueue.QueueState>? = null,
            retryIndex: suspend (String, String) -> String? = { _, _ -> null },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShareImportViewModel(queue, importer, targets, indexQueueState, retryIndex) as T
        }
    }
}

private class ShareImportPersistenceException(message: String) : Exception(message)
