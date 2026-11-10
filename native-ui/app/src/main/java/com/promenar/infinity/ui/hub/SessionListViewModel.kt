package com.promenar.infinity.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.domain.usecase.newSessionDefaults
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 新建会话的默认模型：优先摘要模型，其次主模型；都未配置时为 null。 */
internal fun defaultNewSessionModelId(): String? = runCatching {
    val providerManager = ProviderManager.getInstance()
    providerManager.summaryModelId.value.takeIf { it.isNotBlank() }
        ?: providerManager.getMainConfiguredModelId()
}.getOrNull()

class SessionListViewModel(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository,
    private val agentRepository: AgentRepository,
    coordinatedDelete: (suspend (String, Boolean) -> com.promenar.infinity.data.session.SessionDeletionResult)? = null,
    private val onDeleted: (com.promenar.infinity.data.session.SessionDeletionResult) -> Unit = {},
    private val globalRagConfig: () -> RagConfiguration = { RagConfiguration() },
) : ViewModel() {

    private val sessionManager = SessionManager(store, sessionRepository, coordinatedDelete)
    private val sessionMutationMutex = Mutex()

    private val _operationFailed = MutableStateFlow(false)
    val operationFailed: StateFlow<Boolean> = _operationFailed.asStateFlow()

    /** 全部助手的会话目录，置顶在前，其余按更新时间倒序；对话页据此分组。 */
    val sessions: StateFlow<List<Session>> = store.state.map { state ->
        state.sessions.sortedWith(
            compareByDescending<Session> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadSessions() {
        viewModelScope.launch {
            try {
                sessionMutationMutex.withLock {
                    refreshSessionsFromRepository()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                InfinityLogger.logError("SessionListViewModel.loadSessions", failure)
            }
        }
    }

    fun createSession(agentId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _operationFailed.value = false
            val agent = try { agentRepository.observeById(agentId).first() } catch (_: Exception) { null }
            val sessionId = IdGenerator.session()
            val session = newSessionDefaults(
                sessionId, agentId, agent, resolveDefaultModelId(), globalRagConfig(), System.currentTimeMillis(),
            )
            try {
                sessionMutationMutex.withLock {
                    sessionManager.addSession(session)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _operationFailed.value = true
                return@launch
            }
            onCreated(sessionId)
        }
    }

    fun deleteSession(id: String, keepFiles: Boolean = false) {
        viewModelScope.launch {
            runSessionOperation {
                sessionManager.deleteSession(id, keepFiles).also { deleted ->
                    onDeleted(deleted)
                    com.promenar.infinity.utils.InfinityLogger.diagnostic(
                        "session.delete",
                        mapOf("sessionId" to id, "deleted" to deleted::class.simpleName),
                    )
                }
            }
        }
    }

    fun pinSession(id: String) {
        viewModelScope.launch {
            runSessionOperation { sessionManager.toggleSessionPin(id) }
        }
    }

    fun dismissOperationFailure() {
        _operationFailed.value = false
    }

    private fun resolveDefaultModelId(): String? = defaultNewSessionModelId()

    private suspend fun runSessionOperation(operation: suspend () -> Unit) {
        _operationFailed.value = false
        try {
            sessionMutationMutex.withLock { operation() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _operationFailed.value = true
        }
    }

    private suspend fun refreshSessionsFromRepository() {
        repeat(2) {
            val expectedVersion = store.get().sessionCatalogVersion
            val all = sessionRepository.getAll()
            if (store.replaceSessionsIfCatalogUnchanged(expectedVersion, all)) return
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return SessionListViewModel(
                        globalRagConfig = { RagConfigPersistence(app.getSharedPreferences("rag_settings", 0)).loadFullConfig() },
                        store = app.chatStore,
                        sessionRepository = app.sessionRepository,
                        agentRepository = app.agentRepository,
                        coordinatedDelete = { id, keepFiles -> app.deleteSessionRecoverably(id, keepFiles) },
                        onDeleted = { com.promenar.infinity.ui.workspace.announceKeptFiles(app, it) },
                    ) as T
                }
            }
    }
}
