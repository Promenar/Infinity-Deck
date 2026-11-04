package com.promenar.infinity.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.git.GitChangesService
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.domain.workspace.DirectoryWorkspaceFiles
import com.promenar.infinity.domain.workspace.PrivateWorkspaceFiles
import com.promenar.infinity.share.core.AndroidSafContentSource
import com.promenar.infinity.share.core.AndroidSafImportRequestFactory
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.git.GitEntrySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecycleOperation {
    Restore,
    PermanentDelete,
    Empty,
}

sealed interface RecycleOperationState {
    data object Idle : RecycleOperationState

    data class Running(
        val operation: RecycleOperation,
        val itemUuids: List<String>,
    ) : RecycleOperationState

    data class Success(
        val operation: RecycleOperation,
        val processedItemUuids: List<String>,
    ) : RecycleOperationState

    data class PartialFailure(
        val operation: RecycleOperation,
        val succeededItemUuids: List<String>,
        val failedItemUuids: List<String>,
    ) : RecycleOperationState

    data class Failure(
        val operation: RecycleOperation,
        val failedItemUuids: List<String>,
    ) : RecycleOperationState
}

class ResourceExplorerViewModel(
    application: Application,
    injectedWorkspaceRepository: IWorkspaceRepository? = null,
    injectedImporter: SharedFileImporter? = null,
    injectedGitChanges: GitChangesService? = null,
    injectedWorkspaceAccess: ResourceExplorerWorkspaceAccess? = null,
    injectedDirectoryFiles: DirectoryWorkspaceFiles? = null,
    injectedPrivateFiles: PrivateWorkspaceFiles? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val workspaceRepo: IWorkspaceRepository = injectedWorkspaceRepository
        ?: (application as InfinityApplication).workspaceRepository

    private val importer by lazy {
        injectedImporter ?: SharedFileImporter(
            source = AndroidSafContentSource(application.contentResolver),
            workspace = workspaceRepo,
        )
    }
    /** 注入了工作区仓库（测试）而未注入 Git 服务时不读取 Git 状态，入口保持隐藏。 */
    private val gitChanges: GitChangesService? by lazy {
        injectedGitChanges ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.gitChangesService
        } else {
            null
        }
    }
    /** 注入了工作区仓库（测试）而未注入解析能力时始终按会话私有工作区加载。 */
    private val workspaceAccess: ResourceExplorerWorkspaceAccess? by lazy {
        injectedWorkspaceAccess ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.sessionWorkspaceResolver?.let(::ResolverWorkspaceAccess)
        } else {
            null
        }
    }
    private val directoryFiles: DirectoryWorkspaceFiles? by lazy {
        injectedDirectoryFiles ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.directoryWorkspaceFiles
        } else {
            null
        }
    }
    private val privateFileService: PrivateWorkspaceFiles? by lazy {
        injectedPrivateFiles ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.privateWorkspaceFiles
        } else {
            null
        }
    }

    /** 私有工作区是否支持长按改名、移动与删除；不支持时文件页保持只读。 */
    val privateFileOpsAvailable: Boolean get() = privateFileService != null
    private val contentResolver = application.contentResolver
    private val requestFactory = AndroidSafImportRequestFactory(contentResolver)

    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()

    private val _rootFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val rootFiles: StateFlow<List<FileEntry>> = _rootFiles.asStateFlow()

    private val _recycledFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val recycledFiles: StateFlow<List<FileEntry>> = _recycledFiles.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recycleBinCount = MutableStateFlow(0)
    val recycleBinCount: StateFlow<Int> = _recycleBinCount.asStateFlow()

    private val _recycleOperationState = MutableStateFlow<RecycleOperationState>(RecycleOperationState.Idle)
    val recycleOperationState: StateFlow<RecycleOperationState> = _recycleOperationState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 结构化加载错误：稳定 code + severity，[UiStatusNotice.technical] 仅日志/诊断用。
     * UI 据 code 经 stringResource 显示双语安全文案，**不**直出底层 exception.message。
     */
    private val _loadError = MutableStateFlow<UiStatusNotice?>(null)
    val loadError: StateFlow<UiStatusNotice?> = _loadError.asStateFlow()

    private val _importItems = MutableStateFlow<List<ShareImportItem>>(emptyList())
    val importItems: StateFlow<List<ShareImportItem>> = _importItems.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** 会话工作区是 Git 仓库时的入口摘要（分支与改动数）；不是仓库或工作区不可用时为 [GitEntrySummary.Hidden]。 */
    private val _gitEntry = MutableStateFlow<GitEntrySummary>(GitEntrySummary.Hidden)
    val gitEntry: StateFlow<GitEntrySummary> = _gitEntry.asStateFlow()
    private var gitJob: Job? = null

    private val _workspaceMode = MutableStateFlow<ResourceExplorerWorkspaceMode>(ResourceExplorerWorkspaceMode.Resolving)
    val workspaceMode: StateFlow<ResourceExplorerWorkspaceMode> = _workspaceMode.asStateFlow()

    private val _directory = MutableStateFlow(DirectoryBrowserState())
    internal val directory: StateFlow<DirectoryBrowserState> = _directory.asStateFlow()

    private val _privateFiles = MutableStateFlow(PrivateFileOpsState())
    internal val privateFiles: StateFlow<PrivateFileOpsState> = _privateFiles.asStateFlow()

    private var workspaceEpoch = 0L
    private var resolveJob: Job? = null
    private var directoryJob: Job? = null
    private var previewJob: Job? = null

    private var currentSessionId: String? = null
    private var loadGeneration = 0L
    private var sessionObserverEpoch = 0L
    private var sessionJob: Job? = null
    private var importJob: Job? = null
    private var recycleOperationJob: Job? = null
    private var recycleRetryPlan: RecycleRetryPlan? = null
    private val importRequests = linkedMapOf<Uri, ShareRequest>()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 每次打开资源管理器都重新解析会话工作区：私有工作区沿用 Room 观察，目录工作区直接列目录，
     * 不可用时只展示原因，不回退到会话私有工作区。
     */
    fun loadSession(sessionId: String) {
        val access = workspaceAccess
        if (access == null) {
            _workspaceMode.value = ResourceExplorerWorkspaceMode.Private
            loadPrivateSession(sessionId)
            refreshGitEntry()
            return
        }
        if (sessionId != currentSessionId) {
            _gitEntry.value = GitEntrySummary.Hidden
            currentSessionId = sessionId
            leavePrivateWorkspace()
            resetDirectoryState()
            _workspaceMode.value = ResourceExplorerWorkspaceMode.Resolving
        }
        if (_workspaceMode.value == ResourceExplorerWorkspaceMode.Resolving) {
            _loadError.value = null
            _isLoading.value = true
        }
        val epoch = ++workspaceEpoch
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val resolved = try {
                access.resolve(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == workspaceEpoch) {
                    // 无法确认绑定关系时不展示任何一侧的旧内容。
                    leavePrivateWorkspace()
                    resetDirectoryState()
                    _workspaceMode.value = ResourceExplorerWorkspaceMode.Resolving
                    _loadError.value = ResourceExplorerNotice.workspaceLoadFailed(failure.message)
                    _isLoading.value = false
                }
                return@launch
            }
            if (epoch == workspaceEpoch && sessionId == currentSessionId) applyWorkspace(sessionId, resolved)
        }
        refreshGitEntry()
    }

    private fun applyWorkspace(sessionId: String, resolved: SessionWorkspace) {
        when (resolved) {
            SessionWorkspace.Private -> {
                if (_workspaceMode.value != ResourceExplorerWorkspaceMode.Private) resetDirectoryState()
                _workspaceMode.value = ResourceExplorerWorkspaceMode.Private
                loadPrivateSession(sessionId)
            }

            is SessionWorkspace.Directory -> {
                val mode = ResourceExplorerWorkspaceMode.Directory(
                    workspaceId = resolved.workspace.id,
                    displayName = resolved.workspace.displayName,
                    kind = resolved.kind,
                    location = resolved.workspace.location,
                )
                val previous = _workspaceMode.value
                leavePrivateWorkspace()
                _loadError.value = null
                _isLoading.value = false
                if (previous != mode) resetDirectoryState()
                _workspaceMode.value = mode
                loadDirectory(_directory.value.path)
                _directory.value.selectedFile?.let(::openDirectoryFile)
            }

            is SessionWorkspace.Unavailable -> {
                leavePrivateWorkspace()
                resetDirectoryState()
                _loadError.value = null
                _isLoading.value = false
                _workspaceMode.value = ResourceExplorerWorkspaceMode.Unavailable(
                    workspaceId = resolved.workspaceId,
                    displayName = resolved.workspace?.displayName,
                    reason = resolved.reason,
                    kind = resolved.workspace?.kind,
                    location = resolved.workspace?.location,
                )
            }
        }
    }

    private fun loadPrivateSession(sessionId: String) {
        val existingRootUuid = _workspaceRootUuid.value
        if (sessionId == currentSessionId && existingRootUuid != null) {
            val generation = loadGeneration
            _loadError.value = null
            _isLoading.value = false
            startSessionObservers(existingRootUuid, generation)
            return
        }

        if (sessionId != currentSessionId) _gitEntry.value = GitEntrySummary.Hidden
        currentSessionId = sessionId
        val generation = ++loadGeneration
        invalidateSessionObservers()
        importJob?.cancel()
        recycleOperationJob?.cancel()
        clearSessionState()
        _isLoading.value = true
        startSessionRootLoad(sessionId, generation)
    }

    /** 离开私有工作区：停止 Room 观察并作废进行中的导入与回收操作结果。 */
    private fun leavePrivateWorkspace() {
        ++loadGeneration
        invalidateSessionObservers()
        importJob?.cancel()
        recycleOperationJob?.cancel()
        clearSessionState()
    }

    private fun resetDirectoryState() {
        directoryJob?.cancel()
        previewJob?.cancel()
        _directory.value = DirectoryBrowserState()
    }

    /** 进入目录工作区内的 [path]；路径由目录后端规范化与校验。 */
    fun openDirectory(path: String) {
        if (_workspaceMode.value !is ResourceExplorerWorkspaceMode.Directory) return
        closeDirectoryFile()
        loadDirectory(path)
    }

    fun refreshDirectory() {
        if (_workspaceMode.value !is ResourceExplorerWorkspaceMode.Directory) return
        loadDirectory(_directory.value.path)
    }

    /** 选择文件后读取有界文本预览；目录项等同于进入该目录。 */
    fun openDirectoryFile(entry: ExternalFolderEntry) {
        if (entry.isDirectory) {
            openDirectory(entry.path)
            return
        }
        val mode = _workspaceMode.value as? ResourceExplorerWorkspaceMode.Directory ?: return
        previewJob?.cancel()
        if (entry.size > DIRECTORY_TEXT_PREVIEW_BYTES) {
            _directory.value = _directory.value.copy(
                selectedFile = entry,
                preview = ExternalTextPreview.TooLarge,
                previewError = null,
            )
            return
        }
        _directory.value = _directory.value.copy(
            selectedFile = entry,
            preview = ExternalTextPreview.Loading,
            previewError = null,
        )
        previewJob = viewModelScope.launch {
            val (preview, error) = try {
                val bytes = readDirectoryFile(entry.path, DIRECTORY_TEXT_PREVIEW_BYTES.toLong())
                withContext(io) { decodeExternalTextPreview(bytes, DIRECTORY_TEXT_PREVIEW_BYTES) } to null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ExternalTextPreview.Failed to failure.directoryReason()
            }
            val current = _directory.value
            if (_workspaceMode.value == mode && current.selectedFile?.path == entry.path) {
                _directory.value = current.copy(preview = preview, previewError = error)
            }
        }
    }

    fun closeDirectoryFile() {
        previewJob?.cancel()
        _directory.value = _directory.value.copy(selectedFile = null, preview = null, previewError = null)
    }

    fun toggleDirectorySelection(path: String) {
        val current = _directory.value
        _directory.value = current.copy(
            selection = if (path in current.selection) current.selection - path else current.selection + path,
            outcome = null,
        )
    }

    fun clearDirectorySelection() {
        _directory.value = _directory.value.copy(selection = emptySet())
    }

    /** 把选中条目放入剪贴板，[cut] 为 true 时粘贴即移动。 */
    fun copyDirectorySelection(cut: Boolean) {
        val current = _directory.value
        if (current.selection.isEmpty()) return
        _directory.value = current.copy(
            clipboard = DirectoryClipboard(current.entries.map { it.path }.filter(current.selection::contains), cut),
            selection = emptySet(),
            outcome = null,
        )
    }

    fun clearDirectoryClipboard() {
        _directory.value = _directory.value.copy(clipboard = null)
    }

    fun dismissDirectoryOutcome() {
        _directory.value = _directory.value.copy(outcome = null)
    }

    fun pasteDirectoryClipboard() {
        val clipboard = _directory.value.clipboard ?: return
        val target = _directory.value.path
        if (!clipboard.canPasteInto(target)) return
        val op = if (clipboard.cut) DirectoryOp.MOVE else DirectoryOp.COPY
        runDirectoryBatch(op, clearClipboard = true) { files, sessionId ->
            if (clipboard.cut) files.move(sessionId, clipboard.paths, target) else files.copy(sessionId, clipboard.paths, target)
        }
    }

    fun deleteDirectorySelection() {
        val paths = _directory.value.selection.toList()
        if (paths.isEmpty()) return
        runDirectoryBatch(DirectoryOp.DELETE, clearClipboard = false) { files, sessionId -> files.delete(sessionId, paths) }
    }

    /** 用户确认后永久删除先前因超出回收站容量而保留的条目。 */
    fun deleteTooLargePermanently() {
        val paths = _directory.value.permanentDeleteCandidates
        if (paths.isEmpty()) return
        _directory.value = _directory.value.copy(permanentDeleteCandidates = emptyList())
        runDirectoryBatch(DirectoryOp.DELETE, clearClipboard = false) { files, sessionId -> files.delete(sessionId, paths, permanent = true) }
    }

    fun keepTooLarge() {
        _directory.value = _directory.value.copy(permanentDeleteCandidates = emptyList())
    }

    /** 新建或改名；[onResult] 收到 null 表示成功，否则为失败原因，由对话框就地显示。 */
    fun createDirectoryFolder(name: String, onResult: (DirectoryFileOpCode?) -> Unit) {
        val parent = _directory.value.path
        runDirectorySingle(onResult) { files, sessionId -> files.createDirectory(sessionId, parent, name) }
    }

    fun renameDirectoryEntry(path: String, name: String, onResult: (DirectoryFileOpCode?) -> Unit) {
        runDirectorySingle(onResult) { files, sessionId -> files.rename(sessionId, path, name) }
    }

    private fun runDirectorySingle(
        onResult: (DirectoryFileOpCode?) -> Unit,
        action: suspend (DirectoryWorkspaceFiles, String) -> Unit,
    ) {
        val files = directoryFiles ?: return onResult(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
        val sessionId = currentSessionId ?: return onResult(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
        if (_directory.value.operating) return
        _directory.value = _directory.value.copy(operating = true, outcome = null)
        viewModelScope.launch {
            val code = try {
                withContext(io) { action(files, sessionId) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DirectoryFileOpException) {
                failure.code
            } catch (_: Exception) {
                DirectoryFileOpCode.IO
            }
            _directory.value = _directory.value.copy(operating = false, selection = if (code == null) emptySet() else _directory.value.selection)
            onResult(code)
            if (code == null) refreshDirectory()
        }
    }

    private fun runDirectoryBatch(
        op: DirectoryOp,
        clearClipboard: Boolean,
        action: suspend (DirectoryWorkspaceFiles, String) -> DirectoryBatchResult,
    ) {
        val files = directoryFiles ?: return
        val sessionId = currentSessionId ?: return
        if (_directory.value.operating) return
        _directory.value = _directory.value.copy(operating = true, outcome = null)
        viewModelScope.launch {
            var failure: DirectoryFileOpCode? = null
            val batch = try {
                withContext(io) { action(files, sessionId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DirectoryFileOpException) {
                failure = error.code
                null
            } catch (_: Exception) {
                failure = DirectoryFileOpCode.IO
                null
            }
            val outcome = batch?.toOutcome(op) ?: DirectoryOpOutcome(op, 0, 1, failure)
            val current = _directory.value
            // 粘贴部分失败时剪贴板只留下失败的条目，便于换个位置重试。
            val clipboard = if (!clearClipboard) current.clipboard else current.clipboard?.let { board ->
                val remaining = if (batch == null) board.paths else board.paths.filter(batch.failed::containsKey)
                remaining.takeIf { it.isNotEmpty() }?.let { board.copy(paths = it) }
            }
            _directory.value = current.copy(
                operating = false,
                selection = emptySet(),
                clipboard = clipboard,
                // 超出回收站容量的条目另行询问是否永久删除，不计入失败提示。
                outcome = if (op == DirectoryOp.DELETE && batch != null) {
                    batch.copy(failed = batch.failed.filterValues { it != DirectoryFileOpCode.TOO_LARGE })
                        .toOutcome(op).takeIf { it.succeeded + it.failed > 0 }
                } else {
                    outcome
                },
                permanentDeleteCandidates = if (op == DirectoryOp.DELETE) {
                    batch?.failed?.filterValues { it == DirectoryFileOpCode.TOO_LARGE }?.keys?.toList().orEmpty()
                } else {
                    current.permanentDeleteCandidates
                },
            )
            refreshDirectory()
        }
    }

    fun togglePrivateSelection(entry: FileEntry) {
        val current = _privateFiles.value
        _privateFiles.value = current.copy(
            selection = if (entry.uuid in current.selection) current.selection - entry.uuid else current.selection + (entry.uuid to entry),
            outcome = null,
        )
    }

    fun clearPrivateSelection() {
        if (_privateFiles.value.selection.isNotEmpty()) _privateFiles.value = _privateFiles.value.copy(selection = emptyMap())
    }

    /** 把选中条目放入剪贴板；进入目标目录后粘贴即移动。 */
    fun cutPrivateSelection() {
        val current = _privateFiles.value
        if (current.selection.isEmpty()) return
        val entries = current.selection.values.toList()
        _privateFiles.value = current.copy(
            clipboard = PrivateFileClipboard(entries.map(FileEntry::uuid), entries.map(FileEntry::materializedPath)),
            selection = emptyMap(),
            outcome = null,
        )
    }

    fun clearPrivateClipboard() {
        _privateFiles.value = _privateFiles.value.copy(clipboard = null)
    }

    fun dismissPrivateOutcome() {
        _privateFiles.value = _privateFiles.value.copy(outcome = null)
    }

    /** 把剪贴板中的条目移动到 [targetParentUuid]；部分失败时剪贴板只留下失败的条目。 */
    fun pastePrivateClipboard(targetParentUuid: String) {
        val clipboard = _privateFiles.value.clipboard ?: return
        runPrivateBatch(DirectoryOp.MOVE) { files, sessionId -> files.move(sessionId, clipboard.uuids, targetParentUuid) }
    }

    fun deletePrivateSelection() {
        val uuids = _privateFiles.value.selection.keys.toList()
        if (uuids.isEmpty()) return
        runPrivateBatch(DirectoryOp.DELETE) { files, sessionId -> files.delete(sessionId, uuids) }
    }

    /** 改名；[onResult] 收到 null 表示成功，否则为失败原因，由对话框就地显示。 */
    fun renamePrivateEntry(uuid: String, name: String, onResult: (DirectoryFileOpCode?) -> Unit) {
        val files = privateFileService ?: return onResult(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
        val sessionId = currentSessionId ?: return onResult(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
        if (_privateFiles.value.operating) return
        val generation = loadGeneration
        _privateFiles.value = _privateFiles.value.copy(operating = true, outcome = null)
        viewModelScope.launch {
            val code = try {
                withContext(io) { files.rename(sessionId, uuid, name) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DirectoryFileOpException) {
                failure.code
            } catch (_: Exception) {
                DirectoryFileOpCode.IO
            }
            // 会话已切换时旧结果不写入新会话的状态。
            if (generation != loadGeneration) return@launch
            val current = _privateFiles.value
            _privateFiles.value = current.copy(operating = false, selection = if (code == null) emptyMap() else current.selection)
            onResult(code)
        }
    }

    private fun runPrivateBatch(
        op: DirectoryOp,
        action: suspend (PrivateWorkspaceFiles, String) -> DirectoryBatchResult,
    ) {
        val files = privateFileService ?: return
        val sessionId = currentSessionId ?: return
        if (_privateFiles.value.operating) return
        val generation = loadGeneration
        _privateFiles.value = _privateFiles.value.copy(operating = true, outcome = null)
        viewModelScope.launch {
            var failure: DirectoryFileOpCode? = null
            val batch = try {
                withContext(io) { action(files, sessionId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DirectoryFileOpException) {
                failure = error.code
                null
            } catch (_: Exception) {
                failure = DirectoryFileOpCode.IO
                null
            }
            if (generation != loadGeneration) return@launch
            val current = _privateFiles.value
            val clipboard = if (op != DirectoryOp.MOVE) current.clipboard else current.clipboard?.let { board ->
                val keep = board.uuids.indices.filter { batch == null || board.uuids[it] in batch.failed }
                keep.takeIf { it.isNotEmpty() }?.let { PrivateFileClipboard(it.map(board.uuids::get), it.map(board.paths::get)) }
            }
            _privateFiles.value = current.copy(
                operating = false,
                selection = emptyMap(),
                clipboard = clipboard,
                outcome = batch?.toOutcome(op) ?: DirectoryOpOutcome(op, 0, 1, failure),
            )
        }
    }

    /**
     * 读取目录工作区内的文件，供预览与“使用其它应用打开”。每次读取前重新解析会话工作区，
     * 绑定已变化或目录不可用时抛出 [ExternalFolderException]。
     */
    suspend fun readDirectoryFile(path: String, maxBytes: Long): ByteArray {
        val backend = currentDirectoryBackend()
            ?: throw ExternalFolderException("UNAVAILABLE", "会话工作区已变化，请重新打开")
        return withContext(io) { backend.read(path, maxBytes) }
    }

    private fun loadDirectory(path: String) {
        val mode = _workspaceMode.value as? ResourceExplorerWorkspaceMode.Directory ?: return
        val epoch = workspaceEpoch
        directoryJob?.cancel()
        val current = _directory.value
        _directory.value = current.copy(
            path = path,
            entries = if (current.path == path) current.entries else emptyList(),
            selection = if (current.path == path) current.selection else emptySet(),
            isLoading = true,
            error = null,
        )
        directoryJob = viewModelScope.launch {
            val result = try {
                val backend = currentDirectoryBackend() ?: return@launch
                Result.success(withContext(io) { backend.list(path) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (epoch != workspaceEpoch || _workspaceMode.value != mode || _directory.value.path != path) return@launch
            _directory.value = _directory.value.copy(
                entries = result.getOrDefault(emptyList()),
                isLoading = false,
                error = result.exceptionOrNull()?.let { DirectoryLoadError((it as? Exception)?.directoryReason()) },
            )
        }
    }

    /** 重新解析会话工作区；仍是同一目录工作区时返回其目录后端，否则切换到新状态并返回 null。 */
    private suspend fun currentDirectoryBackend(): ExternalFolderBackend? {
        val sessionId = currentSessionId ?: return null
        val access = workspaceAccess ?: return null
        val mode = _workspaceMode.value as? ResourceExplorerWorkspaceMode.Directory ?: return null
        val epoch = workspaceEpoch
        val resolved = access.resolve(sessionId)
        if (epoch != workspaceEpoch || sessionId != currentSessionId) return null
        if (resolved is SessionWorkspace.Directory && resolved.workspace.id == mode.workspaceId) {
            return access.backend(resolved)
        }
        applyWorkspace(sessionId, resolved)
        return null
    }

    private fun Exception.directoryReason(): String? = when (this) {
        is ExternalFolderException -> message
        is SecurityException -> "目录访问被拒绝"
        else -> null
    }

    /** 重新读取 Git 入口摘要；打开资源管理器与关闭 Git 变更面板时调用。 */
    fun refreshGitEntry() {
        val sessionId = currentSessionId ?: return
        val service = gitChanges ?: return
        gitJob?.cancel()
        gitJob = viewModelScope.launch {
            // 首次读取先用 `.git` 判定占位；已有摘要时保留旧值直到新结果到达，避免刷新时闪烁。
            if (_gitEntry.value == GitEntrySummary.Hidden && service.isRepository(sessionId) && sessionId == currentSessionId) {
                _gitEntry.value = GitEntrySummary.Loading
            }
            val summary = GitEntrySummary.from(service.status(sessionId))
            if (sessionId == currentSessionId) _gitEntry.value = summary
        }
    }

    fun retryLoadSession() {
        currentSessionId?.let(::loadSession)
    }

    /** 资源管理器离开组合树时停止会话数据库观察，已发起的导入/回收操作不因此中断。 */
    fun deactivateSession() {
        invalidateSessionObservers()
        resolveJob?.cancel()
    }

    private fun startSessionRootLoad(sessionId: String, generation: Long) {
        val observerEpoch = beginSessionObserverEpoch()
        sessionJob = viewModelScope.launch {
            val rootUuid = try {
                workspaceRepo.ensureSessionRoot(sessionId).uuid
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleRootLoadFailure(generation, observerEpoch, failure)
                return@launch
            }
            if (!isCurrentSessionObserver(generation, observerEpoch)) return@launch
            _workspaceRootUuid.value = rootUuid
            _isLoading.value = false
            observeSessionWithFailureHandling(rootUuid, generation, observerEpoch)
        }
    }

    private fun startSessionObservers(rootUuid: String, generation: Long) {
        val observerEpoch = beginSessionObserverEpoch()
        sessionJob = viewModelScope.launch {
            observeSessionWithFailureHandling(rootUuid, generation, observerEpoch)
        }
    }

    private fun beginSessionObserverEpoch(): Long {
        val observerEpoch = ++sessionObserverEpoch
        sessionJob?.cancel()
        return observerEpoch
    }

    private fun invalidateSessionObservers() {
        ++sessionObserverEpoch
        sessionJob?.cancel()
        sessionJob = null
    }

    private suspend fun observeSessionWithFailureHandling(
        rootUuid: String,
        generation: Long,
        observerEpoch: Long,
    ) {
        try {
            observeSession(rootUuid, generation, observerEpoch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrentSessionObserver(generation, observerEpoch)) {
                _loadError.value = ResourceExplorerNotice.workspaceLoadFailed(failure.message)
                _isLoading.value = false
            }
        }
    }

    private fun handleRootLoadFailure(
        generation: Long,
        observerEpoch: Long,
        failure: Exception,
    ) {
        if (!isCurrentSessionObserver(generation, observerEpoch)) return
        clearSessionState()
        _loadError.value = ResourceExplorerNotice.workspaceLoadFailed(failure.message)
        _isLoading.value = false
    }

    private fun isCurrentSessionObserver(generation: Long, observerEpoch: Long): Boolean =
        generation == loadGeneration && observerEpoch == sessionObserverEpoch

    private suspend fun observeSession(
        rootUuid: String,
        generation: Long,
        observerEpoch: Long,
    ) {
        coroutineScope {
            launch {
                workspaceRepo.observeChildren(rootUuid, rootUuid).collect { files ->
                    if (isCurrentSessionObserver(generation, observerEpoch)) {
                        _rootFiles.value = files
                    }
                }
            }
            launch {
                workspaceRepo.observeRecycleBin(rootUuid).collect { files ->
                    if (isCurrentSessionObserver(generation, observerEpoch)) {
                        _recycledFiles.value = files
                        _recycleBinCount.value = files.size
                    }
                }
            }
        }
    }

    fun importDocuments(uris: List<Uri>) {
        val rootUuid = _workspaceRootUuid.value
        if (rootUuid == null) {
            _loadError.value = ResourceExplorerNotice.workspaceNotReady()
            return
        }
        importJob?.cancel()
        val generation = loadGeneration
        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                val requests = uris.distinct().map { uri -> uri to createRequest(uri, rootUuid) }
                importRequests.clear()
                importRequests.putAll(requests)
                val inspectedItems = requests.map { (_, request) ->
                    try {
                        importer.inspect(request).singleOrNull()
                            ?: ShareImportItem(
                                uri = request.uris.single(),
                                displayName = request.uris.single().lastPathSegment ?: "selected-file",
                                mimeType = request.mimeType,
                                sizeBytes = null,
                            )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ShareImportItem(
                            uri = request.uris.single(),
                            displayName = request.uris.single().lastPathSegment ?: "selected-file",
                            mimeType = request.mimeType,
                            sizeBytes = null,
                        ).unexpectedFailure(ShareRejectReason.ReadFailed)
                    }
                }
                ensureCurrentImport(generation, rootUuid)
                _importItems.value = inspectedItems
                requests.forEachIndexed { index, (_, request) ->
                    ensureCurrentImport(generation, rootUuid)
                    if (_importItems.value.getOrNull(index)?.status != ShareImportStatus.Pending) {
                        return@forEachIndexed
                    }
                    updateImportItem(index) { it.copy(status = ShareImportStatus.Importing, reason = null) }
                    try {
                        val result = importer.import(request, rootUuid)
                        updateImportItem(index) { result.items.singleOrNull() ?: it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ensureCurrentImport(generation, rootUuid)
                        updateImportItem(index) { it.unexpectedFailure(ShareRejectReason.WriteFailed) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == loadGeneration) _isImporting.value = false
            }
        }
    }

    fun retryImport(uri: Uri) {
        if (_isImporting.value) return
        val rootUuid = _workspaceRootUuid.value ?: return
        val request = importRequests[uri] ?: return
        val index = _importItems.value.indexOfFirst { it.uri == uri }
        if (index < 0) return
        importJob?.cancel()
        val generation = loadGeneration
        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                updateImportItem(index) { it.copy(status = ShareImportStatus.Importing, reason = null) }
                try {
                    val result = importer.import(request, rootUuid, setOf(uri))
                    ensureCurrentImport(generation, rootUuid)
                    updateImportItem(index) { result.items.singleOrNull() ?: it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureCurrentImport(generation, rootUuid)
                    updateImportItem(index) { it.unexpectedFailure(ShareRejectReason.WriteFailed) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == loadGeneration) _isImporting.value = false
            }
        }
    }

    fun clearImportResults() {
        if (!_isImporting.value) {
            importRequests.clear()
            _importItems.value = emptyList()
        }
    }

    fun restoreRecycledFiles(files: List<FileEntry>) {
        startRecycleOperation(RecycleOperation.Restore, files.map(FileEntry::uuid))
    }

    fun permanentlyDeleteRecycledFiles(files: List<FileEntry>) {
        startRecycleOperation(RecycleOperation.PermanentDelete, files.map(FileEntry::uuid))
    }

    fun emptyRecycleBin() {
        if (_recycledFiles.value.isEmpty()) return
        startRecycleOperation(RecycleOperation.Empty, emptyList())
    }

    fun retryFailedRecycleOperation() {
        val retryPlan = recycleRetryPlan ?: return
        startRecycleOperation(retryPlan.operation, retryPlan.itemUuids)
    }

    fun clearRecycleOperationState() {
        if (recycleOperationJob?.isActive == true) return
        recycleRetryPlan = null
        _recycleOperationState.value = RecycleOperationState.Idle
    }

    private fun startRecycleOperation(
        operation: RecycleOperation,
        requestedItemUuids: List<String>,
    ) {
        if (recycleOperationJob?.isActive == true) return
        val itemUuids = requestedItemUuids.distinct()
        if (operation != RecycleOperation.Empty && itemUuids.isEmpty()) return
        val rootUuid = _workspaceRootUuid.value
        if (rootUuid == null) {
            recycleRetryPlan = RecycleRetryPlan(operation, itemUuids)
            _recycleOperationState.value = RecycleOperationState.Failure(operation, itemUuids)
            return
        }
        val generation = loadGeneration
        _recycleOperationState.value = RecycleOperationState.Running(operation, itemUuids)
        recycleOperationJob = viewModelScope.launch {
            val succeededItemUuids = mutableListOf<String>()
            val failedItemUuids = mutableListOf<String>()
            var operationFailedWithoutItem = false
            when (operation) {
                RecycleOperation.Empty -> {
                    try {
                        workspaceRepo.emptyRecycleBin(rootUuid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        operationFailedWithoutItem = true
                    }
                }

                RecycleOperation.Restore,
                RecycleOperation.PermanentDelete,
                -> itemUuids.forEach { itemUuid ->
                    try {
                        when (operation) {
                            RecycleOperation.Restore -> workspaceRepo.restoreFromRecycleBin(rootUuid, itemUuid)
                            RecycleOperation.PermanentDelete -> workspaceRepo.permanentDelete(rootUuid, itemUuid)
                            RecycleOperation.Empty -> error("已由清空分支处理")
                        }
                        succeededItemUuids += itemUuid
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedItemUuids += itemUuid
                    }
                }
            }
            val hasFailures = operationFailedWithoutItem || failedItemUuids.isNotEmpty()
            if (!hasFailures || succeededItemUuids.isNotEmpty()) {
                refreshRecycleBin(rootUuid, generation)
            }
            if (generation != loadGeneration || _workspaceRootUuid.value != rootUuid) return@launch
            recycleRetryPlan = if (hasFailures) RecycleRetryPlan(operation, failedItemUuids) else null
            _recycleOperationState.value = when {
                !hasFailures -> RecycleOperationState.Success(operation, succeededItemUuids)
                succeededItemUuids.isEmpty() -> RecycleOperationState.Failure(operation, failedItemUuids)
                else -> RecycleOperationState.PartialFailure(
                    operation = operation,
                    succeededItemUuids = succeededItemUuids,
                    failedItemUuids = failedItemUuids,
                )
            }
        }
    }

    private suspend fun refreshRecycleBin(rootUuid: String, generation: Long) {
        try {
            val refreshedFiles = workspaceRepo.observeRecycleBin(rootUuid).first()
            if (generation == loadGeneration && _workspaceRootUuid.value == rootUuid) {
                _recycledFiles.value = refreshedFiles
                _recycleBinCount.value = refreshedFiles.size
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 长期观察者仍会继续接收数据库更新；刷新失败不应重放已成功的破坏性操作。
        }
    }

    private fun createRequest(uri: Uri, rootUuid: String): ShareRequest {
        return requestFactory.create(uri, rootUuid)
    }

    private fun updateImportItem(index: Int, transform: (ShareImportItem) -> ShareImportItem) {
        _importItems.value = _importItems.value.toMutableList().also { items ->
            if (index in items.indices) items[index] = transform(items[index])
        }
    }

    private fun ShareImportItem.unexpectedFailure(reason: ShareRejectReason): ShareImportItem = copy(
        status = ShareImportStatus.Rejected,
        reason = reason,
        created = null,
        indexStatus = null,
        fileUuid = null,
        indexTaskId = null,
    )

    private fun ensureCurrentImport(generation: Long, rootUuid: String) {
        if (generation != loadGeneration || _workspaceRootUuid.value != rootUuid) {
            throw CancellationException("会话工作区已切换")
        }
    }

    private fun clearSessionState() {
        _workspaceRootUuid.value = null
        _rootFiles.value = emptyList()
        _recycledFiles.value = emptyList()
        _recycleBinCount.value = 0
        _loadError.value = null
        _isImporting.value = false
        _importItems.value = emptyList()
        _recycleOperationState.value = RecycleOperationState.Idle
        recycleRetryPlan = null
        importRequests.clear()
        _privateFiles.value = PrivateFileOpsState()
    }

    private data class RecycleRetryPlan(
        val operation: RecycleOperation,
        val itemUuids: List<String>,
    )

    companion object {
        /** 目录工作区文本预览上限，与外部文件夹预览一致。 */
        internal const val DIRECTORY_TEXT_PREVIEW_BYTES = 256 * 1024

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ResourceExplorerViewModel(application) as T
                }
            }

    }
}
