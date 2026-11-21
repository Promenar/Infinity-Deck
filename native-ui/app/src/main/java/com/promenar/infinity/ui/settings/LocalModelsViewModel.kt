package com.promenar.infinity.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.inference.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.promenar.infinity.R
import kotlinx.coroutines.launch
import java.io.File

class LocalModelsViewModel(application: Application) : ViewModel() {

    private val app = application as InfinityApplication
    private val appContext = app.applicationContext
    private val prefs = appContext.getSharedPreferences("infinity_settings", 0)

    private val engine: LocalInferenceEngine = app.localInferenceEngine
    private val storageManager: ModelStorageManager = ModelStorageManager(appContext)
    private val installer = app.recordings.recommendedModel
    private val revisions = LocalModelRevisionStore(appContext)
    private val slotStore = LocalModelSlotStore(appContext)
    private val edits = Mutex()
    private val _pinnedRevision = MutableStateFlow(revisions.read())
    internal val pinnedRevision = _pinnedRevision.asStateFlow()
    private val _savedSlotPaths = MutableStateFlow(slotStore.readAllSlotPaths())
    internal val savedSlotPaths = _savedSlotPaths.asStateFlow()
    private val _modelError = MutableStateFlow<Int?>(null)
    internal val modelError = _modelError.asStateFlow()


    val mainSlot: StateFlow<SlotState> = engine.mainSlot
    val embeddingSlot: StateFlow<SlotState> = engine.embeddingSlot
    val rerankSlot: StateFlow<SlotState> = engine.rerankSlot

    val gpuAvailable: Boolean = false

    private val _isEngineEnabled = MutableStateFlow(
        prefs.getBoolean("local_models_enabled", false)
    )
    val isEngineEnabled: StateFlow<Boolean> = _isEngineEnabled.asStateFlow()

    private val _availableModels = MutableStateFlow<List<StoredModel>>(emptyList())
    val availableModels: StateFlow<List<StoredModel>> = _availableModels.asStateFlow()

    private val _modelsLoaded = MutableStateFlow(false)
    val modelsLoaded: StateFlow<Boolean> = _modelsLoaded.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    val downloadState: StateFlow<ModelDownloader.DownloadState?> = installer.state

    init {
        refreshModelList()
        viewModelScope.launch {
            installer.state.collect { download ->
                if (download?.isComplete == true) refreshModelList()
                if (download?.error != null) _modelError.value = R.string.recording_model_download_failed
            }
        }
    }

    fun refreshModelList() {
        viewModelScope.launch {
            _availableModels.value = storageManager.listModels()
            _modelsLoaded.value = true
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            if (_isImporting.value) return@launch
            _isImporting.value = true
            _modelError.value = null
            try {
                storageManager.importModel(uri)
                    .onSuccess { refreshModelList() }
                    .onFailure { _modelError.value = R.string.recording_model_import_failed }
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun deleteModel(filePath: String) {
        viewModelScope.launch {
            edits.withLock {
                if (revisions.read()?.path == filePath) return@withLock
                listOf(SlotType.MAIN to mainSlot, SlotType.EMBEDDING to embeddingSlot, SlotType.RERANK to rerankSlot)
                    .filter { it.second.value.modelPath == filePath }
                    .forEach { engine.unloadModel(it.first) }
                storageManager.deleteModel(filePath).onSuccess {
                    slotStore.clearModelPath(filePath)
                    _savedSlotPaths.value = slotStore.readAllSlotPaths()
                    refreshModelList()
                }
            }
        }
    }

    fun loadModel(slot: SlotType, modelPath: String) {
        viewModelScope.launch {
            edits.withLock {
                if (!_isEngineEnabled.value) return@withLock
                val pin = revisions.read().takeIf { slot == SlotType.MAIN }
                if (pin != null && (pin.path != modelPath || !revisions.verify(pin))) {
                    _modelError.value = R.string.recording_model_locked
                    return@withLock
                }
                _modelError.value = null
                engine.loadModel(slot, modelPath)
                    .onSuccess {
                        slotStore.saveSlotPath(slot, modelPath)
                        _savedSlotPaths.value = slotStore.readAllSlotPaths()
                    }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _modelError.value = R.string.local_models_load_failed
                    }
            }
        }
    }

    fun unloadModel(slot: SlotType) {
        viewModelScope.launch {
            edits.withLock {
                engine.unloadModel(slot)
                slotStore.clearSlot(slot)
                _savedSlotPaths.value = slotStore.readAllSlotPaths()
            }
        }
    }

    internal fun installRecordingModel() {
        _modelError.value = null
        installer.start()
    }

    internal fun toggleRevision(path: String) {
        viewModelScope.launch {
            edits.withLock {
                _modelError.value = null
                try {
                    val current = revisions.read()
                    if (current?.path == path) {
                        revisions.unpin()
                        _pinnedRevision.value = null
                    } else if (current != null) {
                        _modelError.value = R.string.recording_model_locked
                    } else {
                        val pin = revisions.pin(path)
                        _pinnedRevision.value = pin
                        if (_isEngineEnabled.value && engine.mainSlot.value.modelPath != path) {
                            engine.loadModel(SlotType.MAIN, path).getOrThrow()
                        }
                        slotStore.saveSlotPath(SlotType.MAIN, path)
                        _savedSlotPaths.value = slotStore.readAllSlotPaths()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    _modelError.value = R.string.recording_model_pin_failed
                }
            }
        }
    }

    fun cancelDownload() {
        installer.pause()
    }

    fun setEngineEnabled(enabled: Boolean) {
        _isEngineEnabled.value = enabled
        prefs.edit().putBoolean("local_models_enabled", enabled).apply()
        if (enabled) {
            val toRestore = slotStore.readRestorableSlotPaths(
                localModelsEnabled = true,
                pinnedMainPath = revisions.read()?.path,
                fileExists = { File(it).isFile },
            )
            toRestore.forEach { (slot, path) ->
                val current = when (slot) {
                    SlotType.MAIN -> mainSlot.value
                    SlotType.EMBEDDING -> embeddingSlot.value
                    SlotType.RERANK -> rerankSlot.value
                }
                if (!current.isLoaded && !current.isLoading) {
                    loadModel(slot, path)
                }
            }
        } else {
            // 关闭总开关只卸载内存模型，保留已保存的槽位路径，方便再次开启或启动时自动恢复。
            viewModelScope.launch {
                edits.withLock {
                    if (_isEngineEnabled.value) return@withLock
                    engine.unloadModel(SlotType.MAIN)
                    engine.unloadModel(SlotType.EMBEDDING)
                    engine.unloadModel(SlotType.RERANK)
                }
            }
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LocalModelsViewModel(application) as T
                }
            }
    }
}
