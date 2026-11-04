package com.promenar.infinity.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.share.core.AndroidSafContentSource
import com.promenar.infinity.share.core.AndroidSafImportRequestFactory
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.common.status.UiStatusNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResourceExplorerViewModel(
    application: Application,
    injectedWorkspaceRepository: IWorkspaceRepository? = null,
    injectedImporter: SharedFileImporter? = null,
    injectedWorkspaceAccess: ResourceExplorerWorkspaceAccess? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val workspaceRepo = injectedWorkspaceRepository
        ?: (application as InfinityApplication).workspaceRepository
    private val importer by lazy {
        injectedImporter ?: SharedFileImporter(
            source = AndroidSafContentSource(application.contentResolver),
            workspace = workspaceRepo,
        )
    }
    private val workspaceAccess: ResourceExplorerWorkspaceAccess? by lazy {
        injectedWorkspaceAccess ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.sessionWorkspaceResolver?.let(::ResolverWorkspaceAccess)
        } else null
    }
    private val requestFactory = AndroidSafImportRequestFactory(application.contentResolver)
    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow<UiStatusNotice?>(null)
    val loadError: StateFlow<UiStatusNotice?> = _loadError.asStateFlow()
    private val _importItems = MutableStateFlow<List<ShareImportItem>>(emptyList())
    val importItems: StateFlow<List<ShareImportItem>> = _importItems.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    private val _workspaceMode = MutableStateFlow<ResourceExplorerWorkspaceMode>(ResourceExplorerWorkspaceMode.Resolving)
    val workspaceMode: StateFlow<ResourceExplorerWorkspaceMode> = _workspaceMode.asStateFlow()

    private var currentSessionId: String? = null
    private var workspaceEpoch = 0L
    private var loadGeneration = 0L
    private var resolveJob: Job? = null
    private var importJob: Job? = null
    private val importRequests = linkedMapOf<Uri, ShareRequest>()

    /** 解析会话绑定并为专用工作区准备导入目标。 */
    fun loadSession(sessionId: String) {
        if (sessionId != currentSessionId) {
            currentSessionId = sessionId
            leavePrivateWorkspace()
        }
        val epoch = ++workspaceEpoch
        resolveJob?.cancel()
        _workspaceMode.value = ResourceExplorerWorkspaceMode.Resolving
        _loadError.value = null
        _isLoading.value = true
        resolveJob = viewModelScope.launch {
            try {
                val resolved = workspaceAccess?.resolve(sessionId) ?: SessionWorkspace.Private
                if (epoch != workspaceEpoch || sessionId != currentSessionId) return@launch
                when (resolved) {
                    SessionWorkspace.Private -> {
                        val root = _workspaceRootUuid.value ?: withContext(io) {
                            workspaceRepo.ensureSessionRoot(sessionId).uuid
                        }
                        if (epoch != workspaceEpoch || sessionId != currentSessionId) return@launch
                        _workspaceRootUuid.value = root
                        _workspaceMode.value = ResourceExplorerWorkspaceMode.Private
                    }
                    is SessionWorkspace.Directory -> {
                        leavePrivateWorkspace()
                        _workspaceMode.value = ResourceExplorerWorkspaceMode.Directory(
                            workspaceId = resolved.workspace.id,
                            displayName = resolved.workspace.displayName,
                            kind = resolved.kind,
                            location = resolved.workspace.location,
                        )
                    }
                    is SessionWorkspace.Unavailable -> {
                        leavePrivateWorkspace()
                        _workspaceMode.value = ResourceExplorerWorkspaceMode.Unavailable(
                            workspaceId = resolved.workspaceId,
                            displayName = resolved.workspace?.displayName,
                            reason = resolved.reason,
                            kind = resolved.workspace?.kind,
                            location = resolved.workspace?.location,
                        )
                    }
                }
                _isLoading.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == workspaceEpoch && sessionId == currentSessionId) {
                    _loadError.value = ResourceExplorerNotice.workspaceLoadFailed(failure.message)
                    _isLoading.value = false
                }
            }
        }
    }

    private fun leavePrivateWorkspace() {
        ++loadGeneration
        importJob?.cancel()
        _workspaceRootUuid.value = null
        _importItems.value = emptyList()
        _isImporting.value = false
        importRequests.clear()
    }

    fun retryLoadSession() { currentSessionId?.let(::loadSession) }

    /** 绑定变化时立即取消旧目标的导入，再解析新的工作区。 */
    fun onWorkspaceBindingChanged() {
        leavePrivateWorkspace()
        retryLoadSession()
    }

    /** 离开页面时停止解析；已开始的导入继续运行。 */
    fun deactivateSession() {
        ++workspaceEpoch
        resolveJob?.cancel()
    }

    fun importDocuments(uris: List<Uri>) {
        if (_workspaceMode.value != ResourceExplorerWorkspaceMode.Private || _isImporting.value) return
        val rootUuid = _workspaceRootUuid.value
        if (rootUuid == null) {
            _loadError.value = ResourceExplorerNotice.workspaceNotReady()
            return
        }
        importJob?.cancel()
        val generation = loadGeneration
        _isImporting.value = true
        importJob = viewModelScope.launch {
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
                        ensureCurrentImport(generation, rootUuid)
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
        if (_workspaceMode.value != ResourceExplorerWorkspaceMode.Private || _isImporting.value) return
        val rootUuid = _workspaceRootUuid.value ?: return
        val request = importRequests[uri] ?: return
        val index = _importItems.value.indexOfFirst { it.uri == uri }
        if (index < 0) return
        importJob?.cancel()
        val generation = loadGeneration
        _isImporting.value = true
        importJob = viewModelScope.launch {
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

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ResourceExplorerViewModel(application) as T
            }
    }
}
