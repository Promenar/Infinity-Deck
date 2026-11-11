package com.promenar.infinity.ui.skills.store

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.store.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class SkillStoreUiState(
    val sources: List<SkillStoreSource> = emptyList(), val categories: List<SkillStoreCategory> = emptyList(),
    val items: List<StoreSkillItem> = emptyList(), val query: String = "", val filter: StoreFilter = StoreFilter.ALL,
    val categoryId: String? = null, val categoryCounts: Map<String?, Int> = emptyMap(),
    val installedCount: Int = 0, val updateCount: Int = 0, val refreshing: Boolean = false,
    val autoCheck: Boolean = true, val lastCheckedAt: Long? = null, val operations: Map<String, StoreOperation> = emptyMap(),
)
sealed interface SkillStoreEvent {
    data class Installed(val name: String, val enabled: Boolean) : SkillStoreEvent
    data class Updated(val name: String) : SkillStoreEvent
    data class Uninstalled(val name: String) : SkillStoreEvent
    data class Failed(val failure: StoreFailure, val httpStatus: Int? = null) : SkillStoreEvent
    data class NeedsConfirm(val key: String, val reason: ConfirmReason) : SkillStoreEvent
    data class SourceAdded(val url: String) : SkillStoreEvent
}

class SkillStoreViewModel internal constructor(private val repository: SkillStoreRepository, private val catalog: SkillCatalog) : ViewModel() {
    private val _state = MutableStateFlow(SkillStoreUiState())
    val state: StateFlow<SkillStoreUiState> = _state.asStateFlow()
    private val _event = MutableStateFlow<SkillStoreEvent?>(null)
    val event: StateFlow<SkillStoreEvent?> = _event.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    init {
        viewModelScope.launch { repository.snapshot.collect { update() } }
        viewModelScope.launch { catalog.revision.collect { runOperation { repository.rescan() } } }
        viewModelScope.launch { runOperation {
            repository.initialize()
            if (repository.snapshot.value.sources.all { it.status == SourceStatus.Idle }) repository.refresh()
        } }
    }
    private fun update() {
        val data = repository.snapshot.value; val previous = _state.value
        val base = data.items.filter { item ->
            (previous.query.isBlank() || listOf(item.entry.name, item.entry.title, item.entry.summary).any { it.contains(previous.query, ignoreCase = true) }) &&
                when (previous.filter) {
                    StoreFilter.ALL -> true
                    StoreFilter.INSTALLED -> item.installState is StoreInstallState.Installed || item.installState is StoreInstallState.UpdateAvailable
                    StoreFilter.UPDATES -> item.installState is StoreInstallState.UpdateAvailable
                }
        }
        val counts = mutableMapOf<String?, Int>(null to base.size)
        data.categories.forEach { category -> counts[category.id] = base.count { it.entry.categoryId == category.id } }
        val visible = base.filter { previous.categoryId == null || it.entry.categoryId == previous.categoryId }.sortedWith(
            compareBy<StoreSkillItem> { it.installState !is StoreInstallState.UpdateAvailable }.thenBy { !it.source.official }.thenBy { it.entry.title.lowercase() })
        _state.value = previous.copy(sources = data.sources, categories = data.categories, items = visible, categoryCounts = counts,
            installedCount = data.items.count { it.installState is StoreInstallState.Installed || it.installState is StoreInstallState.UpdateAvailable },
            updateCount = data.items.count { it.installState is StoreInstallState.UpdateAvailable }, refreshing = data.refreshing,
            autoCheck = data.autoCheck, lastCheckedAt = data.lastCheckedAt)
    }
    fun consumeEvent() { _event.value = null }
    fun setQuery(query: String) { _state.value = _state.value.copy(query = query); update() }
    fun setFilter(filter: StoreFilter) { _state.value = _state.value.copy(filter = filter); update() }
    fun setCategory(id: String?) { _state.value = _state.value.copy(categoryId = id); update() }
    fun refresh() { viewModelScope.launch { runOperation { repository.refresh() } } }
    fun install(key: String, confirmed: Boolean = false) {
        if (jobs[key]?.isActive == true) return
        val job = viewModelScope.launch {
            try { runOperation {
                when (val result = repository.install(key, confirmed) { operation -> _state.value = _state.value.copy(operations = _state.value.operations + (key to operation)) }) {
                    is InstallResult.Confirm -> _event.value = SkillStoreEvent.NeedsConfirm(key, result.reason)
                    is InstallResult.Done -> _event.value = if (result.updated) SkillStoreEvent.Updated(result.name) else SkillStoreEvent.Installed(result.name, result.enabled)
                }
            } } finally { _state.value = _state.value.copy(operations = _state.value.operations - key); jobs.remove(key) }
        }
        jobs[key] = job
    }
    fun cancel(key: String) { jobs[key]?.cancel() }
    fun uninstall(key: String) { viewModelScope.launch { runOperation { _event.value = SkillStoreEvent.Uninstalled(repository.uninstall(key)) } } }
    fun item(key: String): StateFlow<StoreSkillItem?> = repository.snapshot.map { it.items.find { item -> item.key == key } }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.snapshot.value.items.find { it.key == key })
    suspend fun loadSkillMarkdown(key: String): Result<String> = try { Result.success(repository.markdown(key)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    fun addSource(url: String) { viewModelScope.launch { runOperation { _event.value = SkillStoreEvent.SourceAdded(repository.addSource(url)) } } }
    fun removeSource(url: String) { viewModelScope.launch { runOperation { repository.removeSource(url) } } }
    fun setAutoCheck(enabled: Boolean) { viewModelScope.launch { runOperation { repository.setAutoCheck(enabled) } } }
    private suspend fun runOperation(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: StoreException) { _event.value = SkillStoreEvent.Failed(e.failure, e.httpStatus) } catch (_: Exception) { _event.value = SkillStoreEvent.Failed(StoreFailure.STORAGE) }
    }
    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as InfinityApplication
                return SkillStoreViewModel(app.skillStore, app.skillCatalog) as T
            }
        }
    }
}
