package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.proxy.BuiltInProxyController
import com.promenar.infinity.data.proxy.BuiltInProxySettings
import com.promenar.infinity.data.proxy.BuiltInProxyState
import com.promenar.infinity.data.proxy.ProxyLatencyResult
import com.promenar.infinity.data.proxy.ProxyMode
import com.promenar.infinity.data.proxy.ProxyYamlValidation
import com.promenar.infinity.ui.common.PromptSaveConflictException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface ProxyNotice {
    data class Imported(val count: Int) : ProxyNotice
    data class SubscriptionRefreshed(val count: Int) : ProxyNotice
    data object YamlSaved : ProxyNotice
    data object YamlSynced : ProxyNotice
    data object LatencyDone : ProxyNotice
    data class Failure(val message: String?) : ProxyNotice
}

internal data class ProxySettingsUiState(
    val available: Boolean = false,
    val settings: BuiltInProxySettings = BuiltInProxySettings(),
    val proxyState: BuiltInProxyState = BuiltInProxyState(),
    val latencies: Map<String, ProxyLatencyResult> = emptyMap(),
    val testingLatency: Boolean = false,
    val testingNodeIds: Set<String> = emptySet(),
    val refreshingSubscriptionIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val importError: String? = null,
    val yamlValidating: Boolean = false,
    val yamlValidation: ProxyYamlValidation? = null,
    val notice: ProxyNotice? = null,
)

internal class ProxyViewModel(
    private val controller: BuiltInProxyController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ProxySettingsUiState(
            available = controller.available,
            settings = controller.settings.value,
            proxyState = controller.state.value,
        ),
    )
    val uiState: StateFlow<ProxySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(controller.settings, controller.state) { settings, proxyState ->
                settings to proxyState
            }.collect { (settings, proxyState) ->
                val validIds = settings.nodes.mapTo(hashSetOf()) { it.id }
                _uiState.update { current ->
                    current.copy(
                        available = controller.available,
                        settings = settings,
                        proxyState = proxyState,
                        latencies = current.latencies.filterKeys(validIds::contains),
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = runAction {
        controller.setEnabled(enabled)
    }

    fun setMode(mode: ProxyMode) = runAction {
        controller.setMode(mode)
    }

    fun selectNode(id: String) = runAction {
        controller.selectNode(id)
    }

    fun setNodeEnabled(id: String, enabled: Boolean) = runAction {
        controller.setNodeEnabled(id, enabled)
    }

    fun moveNode(id: String, offset: Int) = runAction {
        controller.moveNode(id, offset)
    }

    fun removeNode(id: String) = runAction {
        controller.removeNode(id)
    }

    fun removeSubscription(id: String) = runAction {
        controller.removeSubscription(id)
    }

    fun syncAdvancedYaml() = runAction {
        controller.syncAdvancedYaml()
        _uiState.update { it.copy(yamlValidation = null, notice = ProxyNotice.YamlSynced) }
    }

    fun importNodes(content: String, onSuccess: () -> Unit) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || _uiState.value.importing) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true, importError = null) }
            try {
                val imported = controller.importNodes(trimmed)
                _uiState.update {
                    it.copy(
                        importing = false,
                        importError = null,
                        notice = ProxyNotice.Imported(imported.size),
                    )
                }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        importing = false,
                        importError = error.message?.takeIf(String::isNotBlank) ?: "",
                    )
                }
            }
        }
    }

    fun addAndRefreshSubscription(name: String, url: String, onSuccess: () -> Unit) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isEmpty() || trimmedUrl.isEmpty() || _uiState.value.importing) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true, importError = null) }
            var createdId: String? = null
            try {
                val created = controller.addSubscription(trimmedName, trimmedUrl)
                createdId = created.id
                val refreshed = controller.refreshSubscription(created.id)
                _uiState.update {
                    it.copy(
                        importing = false,
                        importError = null,
                        notice = ProxyNotice.SubscriptionRefreshed(refreshed.nodeCount),
                    )
                }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 首次拉取失败时回滚空订阅，避免用户修正地址重试时触发“订阅已存在”。
                createdId?.let { id -> runCatching { controller.removeSubscription(id) } }
                _uiState.update {
                    it.copy(
                        importing = false,
                        importError = error.message?.takeIf(String::isNotBlank) ?: "",
                    )
                }
            }
        }
    }

    fun refreshSubscription(id: String) {
        if (id in _uiState.value.refreshingSubscriptionIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshingSubscriptionIds = it.refreshingSubscriptionIds + id) }
            try {
                val refreshed = controller.refreshSubscription(id)
                _uiState.update {
                    it.copy(
                        refreshingSubscriptionIds = it.refreshingSubscriptionIds - id,
                        notice = ProxyNotice.SubscriptionRefreshed(refreshed.nodeCount),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        refreshingSubscriptionIds = it.refreshingSubscriptionIds - id,
                        notice = ProxyNotice.Failure(error.message),
                    )
                }
            }
        }
    }

    fun testAllLatency() {
        val ids = _uiState.value.settings.nodes.filter { it.enabled }.map { it.id }
        if (ids.isEmpty() || _uiState.value.testingLatency) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingLatency = true, testingNodeIds = it.testingNodeIds + ids) }
            try {
                val updated = _uiState.value.latencies.toMutableMap()
                ids.chunked(LATENCY_BATCH_SIZE).forEach { batch ->
                    val results = controller.testLatency(batch)
                    results.forEach { result -> updated[result.name] = result }
                    _uiState.update { it.copy(latencies = updated.toMap()) }
                }
                _uiState.update {
                    it.copy(
                        testingLatency = false,
                        testingNodeIds = it.testingNodeIds - ids.toSet(),
                        notice = ProxyNotice.LatencyDone,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        testingLatency = false,
                        testingNodeIds = it.testingNodeIds - ids.toSet(),
                        notice = ProxyNotice.Failure(error.message),
                    )
                }
            }
        }
    }

    fun testSingleLatency(id: String) {
        if (id in _uiState.value.testingNodeIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingNodeIds = it.testingNodeIds + id) }
            try {
                val results = controller.testLatency(listOf(id))
                _uiState.update { current ->
                    val nextLatencies = current.latencies + results.associateBy { it.name }
                    current.copy(
                        latencies = nextLatencies,
                        testingNodeIds = current.testingNodeIds - id,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        testingNodeIds = it.testingNodeIds - id,
                        notice = ProxyNotice.Failure(error.message),
                    )
                }
            }
        }
    }

    suspend fun loadEditableYaml(): String = controller.readEditableYaml()

    fun validateYaml(yaml: String) {
        if (_uiState.value.yamlValidating) return
        viewModelScope.launch {
            _uiState.update { it.copy(yamlValidating = true) }
            try {
                val result = controller.validateYaml(yaml)
                _uiState.update { it.copy(yamlValidating = false, yamlValidation = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        yamlValidating = false,
                        yamlValidation = ProxyYamlValidation(valid = false, error = error.message),
                    )
                }
            }
        }
    }

    suspend fun saveAdvancedYaml(yaml: String): Result<Unit> {
        _uiState.update { it.copy(yamlValidating = true) }
        return try {
            val validation = controller.setAdvancedYaml(yaml)
            _uiState.update {
                it.copy(
                    yamlValidating = false,
                    yamlValidation = validation,
                    notice = if (validation.valid) ProxyNotice.YamlSaved else it.notice,
                )
            }
            if (validation.valid) {
                Result.success(Unit)
            } else {
                Result.failure(PromptSaveConflictException(validation.error.orEmpty()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    yamlValidating = false,
                    yamlValidation = ProxyYamlValidation(valid = false, error = error.message),
                )
            }
            Result.failure(PromptSaveConflictException(error.message.orEmpty()))
        }
    }

    fun clearImportError() {
        _uiState.update { it.copy(importError = null) }
    }

    fun clearYamlValidation() {
        _uiState.update { it.copy(yamlValidation = null) }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = ProxyNotice.Failure(error.message)) }
            }
        }
    }

    companion object {
        private const val LATENCY_BATCH_SIZE = 32

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return ProxyViewModel(app.builtInProxyManager) as T
                }
            }
    }
}
