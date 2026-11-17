package com.promenar.infinity.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator
import com.promenar.infinity.data.repository.WorkspaceTextPolicyException
import com.promenar.infinity.domain.model.Document
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.recording.LOCAL_RECORDING_MIME
import com.promenar.infinity.recording.hasRecordingSessions
import com.promenar.infinity.recording.recordingLibraryDocumentLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

enum class DocEditorPhase {
    Loading,
    Ready,
    LoadError,
    Saving,
    SaveConflict,
    SaveError,
    NotFound,
}

enum class DocEditorFailureCode {
    LoadFailed,
    LoadNotFound,
    TitleRenameFailed,
    ContentSaveFailed,
    ContentConflict,
    TitleConflict,
    SaveNotFound,
    SaveCancelled,
}

enum class DocEditorReadonlyReason {
    Oversize,
    UnsupportedContent,
}

data class DocEditorUiState(
    val phase: DocEditorPhase = DocEditorPhase.Loading,
    val workspaceRootUuid: String = "",
    val documentId: String = "",
    val documentEpoch: Long = 0L,
    val title: String = "",
    val content: String = "",
    val persistedTitle: String = "",
    val persistedContent: String = "",
    val currentHash: String = "",
    val totalLines: Int = 0,
    val wordCount: Int = 0,
    val lastModified: Long = 0L,
    val sizeBytes: Long = 0L,
    val titleDirty: Boolean = false,
    val contentDirty: Boolean = false,
    val contentAccess: DocEditorContentAccess = DocEditorContentAccess.Editable,
    val readonlyReason: DocEditorReadonlyReason? = null,
    val warningDismissed: Boolean = false,
    val hasLoadedDocument: Boolean = false,
    val failureCode: DocEditorFailureCode? = null,
    /** 仅保留给未来经脱敏的技术诊断；绝不直接写入异常原文。 */
    val failureDetail: String? = null,
    val conflictCurrentHash: String? = null,
    val titleConflictCurrentName: String? = null,
    val indexPendingTargets: List<RenameIndexTarget> = emptyList(),
    val indexQueueFailed: Boolean = false,
) {
    val isDirty: Boolean
        get() = titleDirty || contentDirty

    val document: Document?
        get() = if (hasLoadedDocument) {
            Document(
                id = documentId,
                folderId = "",
                title = title,
                content = content,
                hash = currentHash,
                createdAt = lastModified,
                updatedAt = lastModified,
            )
        } else {
            null
        }
}

class DocEditorViewModel(
    private val fileOperationRepository: IFileOperationRepository,
    private val workspaceRepository: IWorkspaceRepository,
    private val indexEventSink: com.promenar.infinity.data.rag.FileIndexEventSink? = null,
    private val pendingIndexCoordinator: PendingDocumentIndexCoordinator? = null,
    private val onRecordingDocumentSaved: (suspend (documentId: String, title: String, content: String, newHash: String) -> Unit)? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DocEditorUiState())
    val uiState: StateFlow<DocEditorUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private val saveMutex = Mutex()
    private val activeSaveIdentity = AtomicReference<SaveIdentity?>(null)

    init {
        pendingIndexCoordinator?.let { coordinator ->
            viewModelScope.launch {
                coordinator.pendingTargets.collect {
                    synchronizeSharedPendingTargets()
                }
            }
        }
    }

    fun loadFile(workspaceRootUuid: String, uuid: String) {
        val activeSave = activeSaveIdentity.get()
        val current = _uiState.value
        if (activeSave != null &&
            activeSave.workspaceRootUuid == workspaceRootUuid &&
            activeSave.documentId == uuid &&
            current.workspaceRootUuid == activeSave.workspaceRootUuid &&
            current.documentId == activeSave.documentId &&
            current.documentEpoch == activeSave.documentEpoch
        ) {
            return
        }

        loadJob?.cancel()
        val generation = ++loadGeneration
        val loadingPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
        _uiState.value = DocEditorUiState(
            phase = DocEditorPhase.Loading,
            workspaceRootUuid = workspaceRootUuid,
            documentId = uuid,
            documentEpoch = generation,
            indexPendingTargets = loadingPendingTargets,
            indexQueueFailed = loadingPendingTargets.isNotEmpty(),
        )
        loadJob = viewModelScope.launch {
            try {
                val fileEntry = workspaceRepository.getByUuid(workspaceRootUuid, uuid)
                    ?: throw NoSuchElementException(uuid)
                if (fileEntry.sizeBytes > LARGE_FILE_SIZE_BYTES) {
                    val readyPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
                    updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                        DocEditorUiState(
                            phase = DocEditorPhase.Ready,
                            workspaceRootUuid = workspaceRootUuid,
                            documentId = uuid,
                            documentEpoch = generation,
                            title = fileEntry.name,
                            persistedTitle = fileEntry.name,
                            currentHash = fileEntry.hash,
                            lastModified = fileEntry.updatedAt,
                            sizeBytes = fileEntry.sizeBytes,
                            contentAccess = DocEditorContentAccess.MetadataOnly,
                            readonlyReason = DocEditorReadonlyReason.Oversize,
                            hasLoadedDocument = true,
                            indexPendingTargets = readyPendingTargets,
                            indexQueueFailed = readyPendingTargets.isNotEmpty(),
                        )
                    }
                    return@launch
                }
                val result = try {
                    fileOperationRepository.readFileRange(workspaceRootUuid, uuid)
                } catch (_: WorkspaceTextPolicyException) {
                    val readyPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
                    updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                        DocEditorUiState(
                            phase = DocEditorPhase.Ready,
                            workspaceRootUuid = workspaceRootUuid,
                            documentId = uuid,
                            documentEpoch = generation,
                            title = fileEntry.name,
                            persistedTitle = fileEntry.name,
                            currentHash = fileEntry.hash,
                            lastModified = fileEntry.updatedAt,
                            sizeBytes = fileEntry.sizeBytes,
                            contentAccess = DocEditorContentAccess.MetadataOnly,
                            readonlyReason = DocEditorReadonlyReason.UnsupportedContent,
                            hasLoadedDocument = true,
                            indexPendingTargets = readyPendingTargets,
                            indexQueueFailed = readyPendingTargets.isNotEmpty(),
                        )
                    }
                    return@launch
                }
                val statistics = analyzeDocEditorText(result.content)
                val readyPendingTargets = sharedPendingTargets(workspaceRootUuid, uuid)
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    DocEditorUiState(
                        phase = DocEditorPhase.Ready,
                        workspaceRootUuid = workspaceRootUuid,
                        documentId = uuid,
                        documentEpoch = generation,
                        title = result.name,
                        content = result.content,
                        persistedTitle = result.name,
                        persistedContent = result.content,
                        currentHash = result.hash,
                        totalLines = statistics.lineCount,
                        wordCount = statistics.wordCount,
                        contentAccess = contentAccessFor(result.content.length, statistics),
                        lastModified = result.lastModified,
                        sizeBytes = fileEntry.sizeBytes,
                        hasLoadedDocument = true,
                        indexPendingTargets = readyPendingTargets,
                        indexQueueFailed = readyPendingTargets.isNotEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchElementException) {
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    it.copy(
                        phase = DocEditorPhase.NotFound,
                        failureCode = DocEditorFailureCode.LoadNotFound,
                        failureDetail = null,
                    )
                }
            } catch (_: Exception) {
                updateCurrentLoad(generation, workspaceRootUuid, uuid) {
                    it.copy(
                        phase = DocEditorPhase.LoadError,
                        failureCode = DocEditorFailureCode.LoadFailed,
                        failureDetail = null,
                    )
                }
            }
        }
    }

    fun loadDocument(workspaceRootUuid: String, docId: String) = loadFile(workspaceRootUuid, docId)

    fun reload() {
        val current = _uiState.value
        if (current.workspaceRootUuid.isBlank() || current.documentId.isBlank()) return
        loadFile(current.workspaceRootUuid, current.documentId)
    }

    fun saveDocument() {
        val snapshot = _uiState.value
        if (!snapshot.hasLoadedDocument ||
            snapshot.workspaceRootUuid.isBlank() ||
            snapshot.documentId.isBlank() ||
            snapshot.phase == DocEditorPhase.SaveConflict ||
            snapshot.contentAccess == DocEditorContentAccess.MetadataOnly ||
            !snapshot.isDirty ||
            !saveMutex.tryLock()
        ) {
            return
        }
        val saveIdentity = SaveIdentity(
            workspaceRootUuid = snapshot.workspaceRootUuid,
            documentId = snapshot.documentId,
            documentEpoch = snapshot.documentEpoch,
        )
        activeSaveIdentity.set(saveIdentity)

        _uiState.update {
            it.copy(
                phase = DocEditorPhase.Saving,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
        viewModelScope.launch {
            try {
                val recording = onRecordingDocumentSaved != null &&
                    workspaceRepository.getByUuid(snapshot.workspaceRootUuid, snapshot.documentId)?.mimeType == LOCAL_RECORDING_MIME
                if (recording) {
                    recordingLibraryDocumentLock(snapshot.documentId).withLock {
                        withContext(NonCancellable) { saveDocument(snapshot, syncRecording = true) }
                    }
                } else {
                    saveDocument(snapshot, syncRecording = false)
                }
            } catch (cancelled: CancellationException) {
                recoverCancelledSave(snapshot)
                throw cancelled
            } catch (_: Exception) {
                updateSameDocument(snapshot) { it.copy(phase = DocEditorPhase.SaveError, failureCode = DocEditorFailureCode.ContentSaveFailed) }
            } finally {
                activeSaveIdentity.compareAndSet(saveIdentity, null)
                saveMutex.unlock()
            }
        }
    }

    private suspend fun saveDocument(snapshot: DocEditorUiState, syncRecording: Boolean) {
        val renamed = if (snapshot.titleDirty) renameTitle(snapshot) ?: return else null
        val title = renamed?.name ?: snapshot.persistedTitle
        if (snapshot.contentDirty) {
            saveContent(snapshot, title, syncRecording)
        } else {
            if (syncRecording && renamed != null) {
                val content = if (renamed.targetHash == snapshot.currentHash && snapshot.contentAccess == DocEditorContentAccess.Editable) {
                    snapshot.persistedContent
                } else {
                    fileOperationRepository.readFileRange(snapshot.workspaceRootUuid, snapshot.documentId, null, null).content
                }
                onRecordingDocumentSaved?.invoke(snapshot.documentId, title, content, renamed.targetHash)
            }
            markReadyFor(snapshot)
        }
    }

    private suspend fun renameTitle(snapshot: DocEditorUiState): RenameResult.Success? = try {
        when (val result = workspaceRepository.rename(
            workspaceRootUuid = snapshot.workspaceRootUuid,
            uuid = snapshot.documentId,
            newName = snapshot.title,
            expectedName = snapshot.persistedTitle,
        )) {
            is RenameResult.Success -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        persistedTitle = result.name,
                        titleDirty = current.title != result.name,
                        lastModified = result.targetEpoch,
                        titleConflictCurrentName = null,
                    )
                }
                if (pendingIndexCoordinator == null) {
                    mergePendingIndexTargets(snapshot, result.affectedTargets)
                }
                result.affectedTargets.forEach { target ->
                    try {
                        val event = FileIndexEvent.Changed(
                            workspaceRootUuid = snapshot.workspaceRootUuid,
                            fileUuid = target.fileUuid,
                            contentHash = target.targetHash,
                            targetEpoch = target.targetEpoch,
                        )
                        if (pendingIndexCoordinator != null) {
                            pendingIndexCoordinator.publish(event)
                        } else {
                            indexEventSink?.publish(event)
                            removePendingIndexTarget(snapshot, target)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 重命名已经提交；精确 target 留在补偿队列中。
                    }
                }
                result
            }

            is RenameResult.Conflict -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        phase = DocEditorPhase.SaveConflict,
                        failureCode = DocEditorFailureCode.TitleConflict,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = result.current,
                    )
                }
                null
            }

            RenameResult.NotFound -> {
                updateSameDocument(snapshot) { current ->
                    current.copy(
                        phase = DocEditorPhase.NotFound,
                        failureCode = DocEditorFailureCode.SaveNotFound,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = null,
                    )
                }
                null
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        updateSameDocument(snapshot) { current ->
            current.copy(
                phase = DocEditorPhase.SaveError,
                failureCode = DocEditorFailureCode.TitleRenameFailed,
                failureDetail = null,
            )
        }
        null
    }

    private suspend fun saveContent(snapshot: DocEditorUiState, title: String, syncRecording: Boolean) {
        val result = try {
            if (syncRecording) fileOperationRepository.writeFileAtomicExact(
                snapshot.workspaceRootUuid, snapshot.documentId, snapshot.content, EDITOR_SESSION_ID, snapshot.currentHash,
            ) else fileOperationRepository.writeFileAtomic(
                snapshot.workspaceRootUuid, snapshot.documentId, snapshot.content, EDITOR_SESSION_ID, snapshot.currentHash,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.SaveError,
                    failureCode = DocEditorFailureCode.ContentSaveFailed,
                    failureDetail = null,
                )
            }
            return
        }

        when (result) {
            is WriteResult.Success -> {
                updateSameDocument(snapshot) { current ->
                    val pending = if (pendingIndexCoordinator != null) {
                        sharedPendingTargets(snapshot.workspaceRootUuid, snapshot.documentId)
                    } else {
                        current.indexPendingTargets
                            .filterNot { it.fileUuid == snapshot.documentId }
                            .toMutableList()
                            .apply {
                                if (!result.indexQueued && result.targetEpoch != null) {
                                    add(
                                        RenameIndexTarget(
                                            fileUuid = snapshot.documentId,
                                            targetHash = result.newHash,
                                            targetEpoch = result.targetEpoch,
                                        ),
                                    )
                                }
                            }
                    }
                    current.copy(
                        phase = DocEditorPhase.Ready,
                        persistedContent = snapshot.content,
                        currentHash = result.newHash,
                        lastModified = result.targetEpoch ?: current.lastModified,
                        contentDirty = current.content != snapshot.content,
                        failureCode = null,
                        failureDetail = null,
                        conflictCurrentHash = null,
                        titleConflictCurrentName = null,
                        indexPendingTargets = pending,
                        indexQueueFailed = pending.isNotEmpty(),
                    )
                }
                if (syncRecording) onRecordingDocumentSaved?.invoke(snapshot.documentId, title, snapshot.content, result.newHash)
            }

            is WriteResult.Conflict -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.SaveConflict,
                    failureCode = DocEditorFailureCode.ContentConflict,
                    failureDetail = null,
                    conflictCurrentHash = result.currentHash,
                    titleConflictCurrentName = null,
                )
            }

            WriteResult.NotFound -> updateSameDocument(snapshot) { current ->
                current.copy(
                    phase = DocEditorPhase.NotFound,
                    failureCode = DocEditorFailureCode.SaveNotFound,
                    failureDetail = null,
                    titleConflictCurrentName = null,
                )
            }
        }
    }

    private fun markReadyFor(snapshot: DocEditorUiState) {
        updateSameDocument(snapshot) { current ->
            current.copy(
                phase = DocEditorPhase.Ready,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    private fun recoverCancelledSave(snapshot: DocEditorUiState) {
        if (viewModelScope.coroutineContext[Job]?.isActive != true) return
        updateSameDocument(snapshot) { current ->
            if (current.phase != DocEditorPhase.Saving) {
                current
            } else {
                current.copy(
                    phase = DocEditorPhase.SaveError,
                    failureCode = DocEditorFailureCode.SaveCancelled,
                    failureDetail = null,
                    conflictCurrentHash = null,
                    titleConflictCurrentName = null,
                )
            }
        }
    }

    private inline fun updateSameDocument(
        snapshot: DocEditorUiState,
        transform: (DocEditorUiState) -> DocEditorUiState,
    ) {
        _uiState.update { current ->
            if (current.workspaceRootUuid == snapshot.workspaceRootUuid &&
                current.documentId == snapshot.documentId &&
                current.documentEpoch == snapshot.documentEpoch
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private inline fun updateCurrentLoad(
        generation: Long,
        workspaceRootUuid: String,
        documentId: String,
        transform: (DocEditorUiState) -> DocEditorUiState,
    ) {
        _uiState.update { current ->
            if (generation == loadGeneration &&
                current.workspaceRootUuid == workspaceRootUuid &&
                current.documentId == documentId &&
                current.documentEpoch == generation
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    fun onContentChanged(newContent: String) {
        val snapshot = _uiState.value
        if (!snapshot.hasLoadedDocument ||
            snapshot.contentAccess != DocEditorContentAccess.Editable
        ) return
        val statistics = analyzeDocEditorText(newContent)
        _uiState.update { current ->
            if (!current.hasLoadedDocument ||
                current.contentAccess != DocEditorContentAccess.Editable ||
                current.workspaceRootUuid != snapshot.workspaceRootUuid ||
                current.documentId != snapshot.documentId ||
                current.documentEpoch != snapshot.documentEpoch
            ) current else current.copy(
                content = newContent,
                contentDirty = newContent != current.persistedContent,
                totalLines = statistics.lineCount,
                wordCount = statistics.wordCount,
                contentAccess = contentAccessFor(newContent.length, statistics),
                failureCode = current.failureCode.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                failureDetail = null,
                conflictCurrentHash = current.conflictCurrentHash.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                titleConflictCurrentName = current.titleConflictCurrentName.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
            )
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { current ->
            if (!current.hasLoadedDocument ||
                current.contentAccess == DocEditorContentAccess.MetadataOnly
            ) current else current.copy(
                title = newTitle,
                titleDirty = newTitle != current.persistedTitle,
                failureCode = current.failureCode.takeIf {
                    current.phase == DocEditorPhase.SaveConflict
                },
                failureDetail = null,
            )
        }
    }

    fun useWorkspaceTitle() {
        _uiState.update { current ->
            val workspaceTitle = current.titleConflictCurrentName
            if (current.phase != DocEditorPhase.SaveConflict ||
                current.failureCode != DocEditorFailureCode.TitleConflict ||
                workspaceTitle == null
            ) current else current.copy(
                phase = DocEditorPhase.Ready,
                title = workspaceTitle,
                persistedTitle = workspaceTitle,
                titleDirty = false,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    fun retryMyTitle() {
        _uiState.update { current ->
            val workspaceTitle = current.titleConflictCurrentName
            if (current.phase != DocEditorPhase.SaveConflict ||
                current.failureCode != DocEditorFailureCode.TitleConflict ||
                workspaceTitle == null
            ) current else current.copy(
                phase = DocEditorPhase.Ready,
                persistedTitle = workspaceTitle,
                titleDirty = current.title != workspaceTitle,
                failureCode = null,
                failureDetail = null,
                conflictCurrentHash = null,
                titleConflictCurrentName = null,
            )
        }
    }

    fun dismissWarning() {
        _uiState.update { it.copy(warningDismissed = true) }
    }

    fun retryPendingIndex() {
        if (_uiState.value.indexPendingTargets.isEmpty()) return
        viewModelScope.launch {
            retryPendingIndexNow()
        }
    }

    internal suspend fun retryPendingIndexNow() {
        val snapshot = _uiState.value
        if (pendingIndexCoordinator != null) {
            pendingIndexCoordinator.snapshot()
                .filter {
                    it.workspaceRootUuid == snapshot.workspaceRootUuid &&
                        it.fileUuid == snapshot.documentId
                }
                .forEach { pendingIndexCoordinator.retry(it) }
            synchronizeSharedPendingTargets()
            return
        }
        snapshot.indexPendingTargets.forEach { target ->
            try {
                indexEventSink?.publish(
                    com.promenar.infinity.data.rag.FileIndexEvent.Changed(
                        workspaceRootUuid = snapshot.workspaceRootUuid,
                        fileUuid = target.fileUuid,
                        contentHash = target.targetHash,
                        targetEpoch = target.targetEpoch,
                    ),
                )
                removePendingIndexTarget(snapshot, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单个 target 失败不阻止其它独立 target；失败项保持原样。
            }
        }
    }

    private fun mergePendingIndexTargets(
        snapshot: DocEditorUiState,
        targets: List<RenameIndexTarget>,
    ) {
        if (targets.isEmpty()) return
        updateSameDocument(snapshot) { current ->
            val merged = LinkedHashMap<String, RenameIndexTarget>()
            current.indexPendingTargets.forEach { merged[it.fileUuid] = it }
            targets.forEach { incoming ->
                val existing = merged[incoming.fileUuid]
                if (existing == null || incoming.targetEpoch >= existing.targetEpoch) {
                    merged[incoming.fileUuid] = incoming
                }
            }
            current.copy(
                indexPendingTargets = merged.values.toList(),
                indexQueueFailed = merged.isNotEmpty(),
            )
        }
    }

    private fun removePendingIndexTarget(
        snapshot: DocEditorUiState,
        target: RenameIndexTarget,
    ) {
        updateSameDocument(snapshot) { current ->
            val pending = current.indexPendingTargets.filterNot { it == target }
            current.copy(
                indexPendingTargets = pending,
                indexQueueFailed = pending.isNotEmpty(),
            )
        }
    }

    private fun sharedPendingTargets(
        workspaceRootUuid: String,
        documentId: String,
    ): List<RenameIndexTarget> = pendingIndexCoordinator?.snapshot().orEmpty()
        .asSequence()
        .filter { it.workspaceRootUuid == workspaceRootUuid && it.fileUuid == documentId }
        .map { event ->
            RenameIndexTarget(
                fileUuid = event.fileUuid,
                targetHash = event.contentHash,
                targetEpoch = event.targetEpoch,
            )
        }
        .toList()

    private fun synchronizeSharedPendingTargets() {
        if (pendingIndexCoordinator == null) return
        _uiState.update { current ->
            val pending = sharedPendingTargets(current.workspaceRootUuid, current.documentId)
            current.copy(
                indexPendingTargets = pending,
                indexQueueFailed = pending.isNotEmpty(),
            )
        }
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as InfinityApplication
            return DocEditorViewModel(
                fileOperationRepository = app.fileOperationRepository,
                workspaceRepository = app.workspaceRepository,
                pendingIndexCoordinator = app.pendingDocumentIndexCoordinator,
                onRecordingDocumentSaved = { id, title, content, hash ->
                    withContext(Dispatchers.IO) {
                        if (hasRecordingSessions(app, listOf(id))) {
                            app.recordings.syncFromLibraryDocument(id, content, hash, overrideTitle = title)
                        }
                    }
                },
            ) as T
        }
    }

    private companion object {
        const val EDITOR_SESSION_ID = "editor"
        const val LARGE_FILE_SIZE_BYTES = 1_048_576L
    }

    private data class SaveIdentity(
        val workspaceRootUuid: String,
        val documentId: String,
        val documentEpoch: Long,
    )
}
