package com.promenar.infinity.ui.chat.components

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IWorkspaceRepository
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
    injectedWorkspaceAccess: ResourceExplorerWorkspaceAccess? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val workspaceRepo = injectedWorkspaceRepository
        ?: (application as InfinityApplication).workspaceRepository
    private val workspaceAccess: ResourceExplorerWorkspaceAccess? by lazy {
        injectedWorkspaceAccess ?: if (injectedWorkspaceRepository == null) {
            (application as? InfinityApplication)?.sessionWorkspaceResolver?.let(::ResolverWorkspaceAccess)
        } else null
    }
    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow<UiStatusNotice?>(null)
    val loadError: StateFlow<UiStatusNotice?> = _loadError.asStateFlow()
    private val _workspaceMode = MutableStateFlow<ResourceExplorerWorkspaceMode>(ResourceExplorerWorkspaceMode.Resolving)
    val workspaceMode: StateFlow<ResourceExplorerWorkspaceMode> = _workspaceMode.asStateFlow()

    private var currentSessionId: String? = null
    private var workspaceEpoch = 0L
    private var resolveJob: Job? = null

    /** 解析会话绑定，专用工作区时准备根目录（供外部文件夹面板使用）。 */
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
        _workspaceRootUuid.value = null
    }

    fun retryLoadSession() { currentSessionId?.let(::loadSession) }

    /** 绑定变化时清掉旧根目录，再解析新的工作区。 */
    fun onWorkspaceBindingChanged() {
        leavePrivateWorkspace()
        retryLoadSession()
    }

    /** 离开页面时停止解析。 */
    fun deactivateSession() {
        ++workspaceEpoch
        resolveJob?.cancel()
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
