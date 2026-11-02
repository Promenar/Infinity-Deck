package com.promenar.infinity.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.usecase.AgentCreationType
import com.promenar.infinity.domain.usecase.CreateAgentUseCase
import com.promenar.infinity.domain.usecase.IdGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentHubViewModel(
    private val agentRepository: AgentRepository,
    private val createAgentUseCase: CreateAgentUseCase,
    private val defaultAgents: List<Agent>,
    private val deleteAgentUseCase: com.promenar.infinity.domain.usecase.DeleteAgentUseCase? = null,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _dbAgents = MutableStateFlow<List<Agent>>(emptyList())

    val agents: StateFlow<List<Agent>> = combine(_dbAgents, _searchQuery) { list, _ ->
        list.sortedWith(compareByDescending<Agent> { it.isPinned }.thenByDescending { it.createdAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    init {
        viewModelScope.launch {
            var seeded = false
            agentRepository.observeAll().collect { saved ->
                if (saved.isEmpty() && !seeded && defaultAgents.isNotEmpty()) {
                    seeded = true
                    defaultAgents.forEach { agentRepository.create(it) }
                    _dbAgents.value = defaultAgents
                } else {
                    _dbAgents.value = saved
                }
            }
        }
    }

    fun createAgent(name: String, type: AgentCreationType, model: String, systemPrompt: String) {
        viewModelScope.launch {
            val agent = createAgentUseCase(name, "", model, systemPrompt, type = type)
            _dbAgents.update { agents ->
                if (agents.any { it.id == agent.id }) agents else agents + agent
            }
        }
    }

    private var releaseSessionCloser: (() -> Unit)? = null

    fun bindSessionCloser(close: (Set<String>) -> Unit) {
        releaseSessionCloser?.invoke()
        releaseSessionCloser = deleteAgentUseCase?.registerSessionCloser(close)
    }

    override fun onCleared() {
        releaseSessionCloser?.invoke()
        super.onCleared()
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            if (deleteAgentUseCase != null) deleteAgentUseCase.invoke(agentId) else agentRepository.delete(agentId)
            _dbAgents.update { it.filter { a -> a.id != agentId } }
        }
    }

    fun togglePin(agentId: String) {
        viewModelScope.launch {
            val current = _dbAgents.value.find { it.id == agentId } ?: return@launch
            val updated = current.copy(isPinned = !current.isPinned)
            // 只写置顶一列，不用列表里的旧副本覆盖智能体或编辑页刚写入的字段。
            agentRepository.updateFields(agentId, setOf(com.promenar.infinity.domain.model.AgentField.IS_PINNED), updated)
            _dbAgents.update { list ->
                list.map { a -> if (a.id == agentId) updated else a }
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return AgentHubViewModel(
                        deleteAgentUseCase = app.deleteAgentUseCase,
                        agentRepository = app.agentRepository,
                        createAgentUseCase = app.createAgentUseCase,
                        defaultAgents = app.defaultAgents,
                    ) as T
                }
            }
    }
}
