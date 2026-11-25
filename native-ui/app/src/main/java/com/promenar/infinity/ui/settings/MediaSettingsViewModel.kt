package com.promenar.infinity.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class ComfyConnection { UNKNOWN, CHECKING, ONLINE, OFFLINE }

internal data class MediaSettingsUiState(
    val snapshot: MediaSettingsSnapshot = MediaSettingsSnapshot(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val errorDetail: String? = null,
    val connections: Map<String, ComfyConnection> = emptyMap(),
) {
    val groups: Map<MediaGroup, List<MediaChannelRow>>
        get() = with(snapshot) { mediaChannelRows(channels, models, templates, hosts, workflows, defaultImageModelId) }
}

internal class MediaSettingsViewModel(
    createStore: (CoroutineScope) -> MediaSettingsStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(MediaSettingsUiState())
    val state: StateFlow<MediaSettingsUiState> = mutable.asStateFlow()
    private val store = createStore(
        CoroutineScope(
            viewModelScope.coroutineContext + CoroutineExceptionHandler { _, e ->
                mutable.update { it.copy(failed = true, errorDetail = e.message) }
            },
        ),
    )
    private var observer: Job? = null
    private val probes = Semaphore(3)

    init { reload() }

    fun reload() {
        observer?.cancel()
        mutable.update { it.copy(failed = false, errorDetail = null) }
        observer = viewModelScope.launch {
            try {
                store.observe().collect { snapshot ->
                    mutable.update { previous ->
                        val unchangedHosts = snapshot.hosts.filter { host -> previous.snapshot.hosts.any { it == host } }.map { it.id }.toSet()
                        previous.copy(
                            snapshot = snapshot,
                            loaded = true,
                            connections = previous.connections.filterKeys { it in unchangedHosts },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(failed = true, errorDetail = e.message) }
            }
        }
    }

    private fun perform(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, failed = false, errorDetail = null) }
        viewModelScope.launch {
            try {
                withContext(dispatcher) { action() }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(failed = true, errorDetail = e.message) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun moveChannelUp(group: MediaGroup, id: String) = perform {
        val ids = state.value.groups[group].orEmpty().map { it.channel.id }
        store.reorder(group, moveMediaItemUp(ids, id))
    }

    fun setReferenceMode(id: String, mode: CharacterReferenceMode) = perform {
        val current = requireNotNull(state.value.snapshot.models.first { it.id == id }.effectiveMediaSettings())
        val updated = changeMediaReferences(current, mode)
        store.changeReferences(id, updated.capabilities.characterReferenceMode, updated.capabilities.referenceOrder)
    }

    fun moveReferenceUp(id: String, name: String) = perform {
        val settings = requireNotNull(state.value.snapshot.models.first { it.id == id }.effectiveMediaSettings())
        store.changeReferences(id, settings.capabilities.characterReferenceMode, moveMediaItemUp(settings.capabilities.referenceOrder, name))
    }

    fun saveTariff(id: String, draft: MediaTariffDraft, onSaved: () -> Unit) {
        if (draft.validationError() != null) return
        perform(onSaved) { store.saveTariff(id, draft) }
    }

    fun saveHost(host: ComfyHost, credential: ComfyCredentialInput, onSaved: () -> Unit) =
        perform(onSaved) { store.saveHost(host, credential) }

    fun removeHost(id: String, onRemoved: () -> Unit) = perform(onRemoved) { store.removeHost(id) }
    fun preflight(id: String) = perform { store.preflight(id) }

    fun preflightHost(id: String) = perform {
        state.value.snapshot.templates.filter { it.template.hostId == id }.forEach { store.preflight(it.template.id) }
        state.value.snapshot.workflows.filter { it.workflow.hostId == id }.forEach { store.preflightWorkflow(it.workflow.workflowId) }
    }

    fun lockTemplate(id: String, order: List<String>) = perform { store.lock(id, order) }

    fun saveSubmitter(
        hostId: String,
        scriptId: String,
        fileName: String,
        displayName: String,
        description: String,
        sourceKind: SubmitterSourceKind,
        protocol: SubmitterInvocationProtocol,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
        runDryRun: Boolean = true,
        onSaved: () -> Unit = {},
    ) = perform(onSaved) {
        store.saveSubmitter(
            hostId = hostId,
            scriptId = scriptId,
            fileName = fileName,
            displayName = displayName,
            description = description,
            sourceKind = sourceKind,
            protocol = protocol,
            sourceText = sourceText,
            supportFiles = supportFiles,
            runDryRun = runDryRun,
        )
    }

    fun dryRunSubmitter(hostId: String, scriptId: String) = perform {
        store.dryRunSubmitter(hostId, scriptId)
    }

    fun dryRunAllSubmitters(hostId: String) = perform {
        state.value.snapshot.submitters.filter { it.hostId == hostId }.forEach { sub ->
            runCatching { store.dryRunSubmitter(hostId, sub.scriptId) }
        }
    }

    fun deleteSubmitter(hostId: String, scriptId: String, onDeleted: () -> Unit = {}) = perform(onDeleted) {
        store.deleteSubmitter(hostId, scriptId)
    }

    suspend fun readSubmitterSource(hostId: String, scriptId: String): String? =
        withContext(dispatcher) { store.readSubmitterSource(hostId, scriptId) }

    fun saveWorkflow(workflow: ComfyWorkflow, onSaved: () -> Unit = {}) = perform(onSaved) {
        store.saveWorkflow(workflow)
    }

    fun preflightWorkflow(workflowId: String) = perform {
        store.preflightWorkflow(workflowId)
    }

    fun deleteWorkflow(workflowId: String, onDeleted: () -> Unit = {}) = perform(onDeleted) {
        store.deleteWorkflow(workflowId)
    }

    fun checkConnections() {
        val hosts = state.value.snapshot.hosts
        hosts.forEach { host ->
            if (state.value.connections[host.id] == ComfyConnection.CHECKING) return@forEach
            mutable.update { it.copy(connections = it.connections + (host.id to ComfyConnection.CHECKING)) }
            viewModelScope.launch {
                val result = try {
                    probes.withPermit { withContext(dispatcher) { store.probe(host.id) } }
                    ComfyConnection.ONLINE
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ComfyConnection.OFFLINE
                }
                if (state.value.snapshot.hosts.any { it == host }) {
                    mutable.update { it.copy(connections = it.connections + (host.id to result)) }
                }
            }
        }
    }

    companion object {
        fun factory(app: InfinityApplication, registry: MediaChannelRegistry? = null) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MediaSettingsViewModel::class.java))
                return MediaSettingsViewModel({ scope -> ApplicationMediaSettingsStore.create(app, scope, registry) }) as T
            }
        }
    }
}
