package com.promenar.infinity.ui.rag

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.VectorStats
import com.promenar.infinity.data.rag.VectorStatsService
import com.promenar.infinity.data.rag.KeywordSearcher
import com.promenar.infinity.data.rag.KeywordSearchMode
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator
import com.promenar.infinity.data.rag.KgExtractionBudget
import com.promenar.infinity.data.rag.KgExtractionEstimate
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.RetrievalDepth
import com.promenar.infinity.data.rag.withRetrievalDepth
import com.promenar.infinity.domain.model.Document
import com.promenar.infinity.domain.model.Folder
import com.promenar.infinity.domain.repository.IKnowledgeGraphRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IVectorRepository
import com.promenar.infinity.domain.repository.MemoryVectorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.promenar.infinity.domain.usecase.RagConfigPersistence

import com.promenar.infinity.ui.common.KgStatus
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.share.core.AndroidSafContentSource
import com.promenar.infinity.share.core.AndroidSafImportRequestFactory
import com.promenar.infinity.share.core.AndroidShareIndexScheduler
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.SharedFileImporter
import kotlinx.coroutines.CancellationException

data class RagStats(
    val documentCount: Int = 0,
    val memoryCount: Int = 0,
    val graphEntityCount: Int = 0
)

data class QueueState(
    val queueLength: Int = 0,
    val isProcessing: Boolean = false,
    val progress: Float = 0f
)

data class DocumentKgConfirmation(
    val workspaceRootUuid: String,
    val fileUuid: String,
    val title: String,
    val contentHash: String,
    val targetEpoch: Long,
    val estimate: KgExtractionEstimate,
    val configuration: RagConfiguration,
) {
    val budget: KgExtractionBudget get() = KgExtractionBudget.from(configuration)
}

class RagViewModel(
    application: Application,
    private val workspaceRepository: IWorkspaceRepository,
    private val vectorRepository: IVectorRepository,
    private val kgRepository: IKnowledgeGraphRepository,
    private val fileOperationRepository: IFileOperationRepository,
    private val ragConfigPersistence: RagConfigPersistence,
    private val keywordSearcher: KeywordSearcher,
    injectedImporter: SharedFileImporter? = null,
    injectedRequestFactory: ((Uri, String) -> ShareRequest)? = null,
    injectedPendingIndexCoordinator: PendingDocumentIndexCoordinator? = null,
    injectedConfigSaver: ((RagConfiguration) -> Unit)? = null,
    injectedEnsureRagWorkspaceRoot: (suspend () -> FileEntry)? = null,
    injectedListRagWorkspaceRoots: (suspend () -> List<RagWorkspaceSource>)? = null,
    injectedEnsureRagWorkspaceRootForSession: (suspend (String) -> FileEntry)? = null,
    injectedCompositeWorkspaceRepository: IWorkspaceRepository? = null,
    private val injectedKgEstimator: (suspend (FileEntry) -> KgExtractionEstimate)? = null,
    injectedIndexBaseline: IndexedChunkBaseline? = null,
) : ViewModel() {

    private val app = application as InfinityApplication

    private val compositeWorkspaceRepository: IWorkspaceRepository = injectedCompositeWorkspaceRepository
        ?: run {
            val sessionDao = try {
                app.database.sessionDao()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("sessionDao", failure)
                null
            }
            val fileEntryDao = try {
                app.database.fileEntryDao()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("fileEntryDao", failure)
                null
            }
            if (sessionDao != null && fileEntryDao != null) {
                com.promenar.infinity.data.repository.CompositeGlobalWorkspaceRepository(
                    baseRepo = workspaceRepository,
                    sessionDao = sessionDao,
                    fileEntryDao = fileEntryDao,
                    sessionWorkspacesRootName = app.getString(R.string.files_session_workspaces_title),
                    untitledSessionName = app.getString(R.string.hub_session_untitled),
                    onTransferToKnowledgeBaseCommitted = { transferredEntry ->
                        try {
                            val rootUuid = _workspaceRootUuid.value
                            if (rootUuid != null) {
                                enqueueCurrentDocumentReference(rootUuid, transferredEntry)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            reportOperationFailure(failure)
                        }
                    }
                )
            } else {
                workspaceRepository
            }
        }

    private val sharedFileImporter = injectedImporter ?: SharedFileImporter(
        source = AndroidSafContentSource(app.contentResolver),
        workspace = workspaceRepository,
        indexScheduler = AndroidShareIndexScheduler(app),
    )
    private val importRequestFactory = injectedRequestFactory
        ?: AndroidSafImportRequestFactory(app.contentResolver)::create
    private val pendingIndexCoordinator = injectedPendingIndexCoordinator
    private val ensureGlobalKnowledgeRoot: suspend () -> FileEntry = injectedEnsureRagWorkspaceRoot
        ?: { app.globalKnowledgeWorkspaceProvisioner.ensureRoot() }
    private val listAvailableRagWorkspaceRoots: suspend () -> List<RagWorkspaceSource> = injectedListRagWorkspaceRoots
        ?: { app.globalKnowledgeWorkspaceProvisioner.listAvailableRoots() }
    private val ensureRagWorkspaceRootForSession: suspend (String) -> FileEntry = injectedEnsureRagWorkspaceRootForSession
        ?: { sessionId: String -> app.globalKnowledgeWorkspaceProvisioner.ensureRoot(sessionId) }

    private val vectorStatsService = VectorStatsService(vectorRepository)

    /** 当前工作区根目录的 FileEntry UUID（首个根目录，用于 FilesPanel） */
    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()

    private val _availableWorkspaceRoots = MutableStateFlow<List<RagWorkspaceSource>>(emptyList())
    val availableWorkspaceRoots: StateFlow<List<RagWorkspaceSource>> =
        _availableWorkspaceRoots.asStateFlow()

    private val _selectedWorkspaceSessionId = MutableStateFlow<String?>(null)
    val selectedWorkspaceSessionId: StateFlow<String?> =
        _selectedWorkspaceSessionId.asStateFlow()
    private var workspaceSwitchJob: Job? = null
    private var workspaceSwitchGeneration = 0L

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _folderStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderStats: StateFlow<Map<String, Int>> = _folderStats.asStateFlow()

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _searchResults = MutableStateFlow<List<RagSearchResult>>(emptyList())
    val searchResults: StateFlow<List<RagSearchResult>> = _searchResults.asStateFlow()

    private val _searchState = MutableStateFlow<RagSearchUiState>(RagSearchUiState.Idle)
    val searchState: StateFlow<RagSearchUiState> = _searchState.asStateFlow()
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var folderDocumentsObservationJob: Job? = null
    private var folderStatsJob: Job? = null

    private val _memoryVectors = MutableStateFlow<List<MemoryVectorRecord>>(emptyList())
    val memoryVectors: StateFlow<List<MemoryVectorRecord>> = _memoryVectors.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexingProgress = MutableStateFlow(0f)
    val indexingProgress: StateFlow<Float> = _indexingProgress.asStateFlow()

    private val _indexingNotice = MutableStateFlow<UiStatusNotice?>(null)
    private val _operationNotice = MutableStateFlow<UiStatusNotice?>(null)
    val indexingNotice: StateFlow<UiStatusNotice?> = combine(_operationNotice, _indexingNotice) { operation, indexing ->
        operation ?: indexing
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _canRetryLastFailedIndex = MutableStateFlow(false)
    val canRetryLastFailedIndex: StateFlow<Boolean> = _canRetryLastFailedIndex.asStateFlow()

    private val _isRetryingLastFailedIndex = MutableStateFlow(false)
    val isRetryingLastFailedIndex: StateFlow<Boolean> = _isRetryingLastFailedIndex.asStateFlow()

    private val _pendingRenameIndexTargets = MutableStateFlow<List<RenameIndexTarget>>(emptyList())
    val pendingRenameIndexTargets: StateFlow<List<RenameIndexTarget>> =
        _pendingRenameIndexTargets.asStateFlow()

    private val _isRetryingPendingRenameIndex = MutableStateFlow(false)
    val isRetryingPendingRenameIndex: StateFlow<Boolean> =
        _isRetryingPendingRenameIndex.asStateFlow()

    private val _isMovingDocuments = MutableStateFlow(false)
    val isMovingDocuments: StateFlow<Boolean> = _isMovingDocuments.asStateFlow()

    private val _isDeletingDocuments = MutableStateFlow(false)
    val isDeletingDocuments: StateFlow<Boolean> = _isDeletingDocuments.asStateFlow()

    /** 当前正在索引中的文件 UUID 集合 */
    private val _indexingDocIds = MutableStateFlow<Set<String>>(emptySet())
    val indexingDocIds: StateFlow<Set<String>> = _indexingDocIds.asStateFlow()

    private val _kgExtractionStates = MutableStateFlow<Map<String, KgStatus>>(emptyMap())
    val kgExtractionStates: StateFlow<Map<String, KgStatus>> = _kgExtractionStates.asStateFlow()
    private val _kgExtractionConfirmation = MutableStateFlow<DocumentKgConfirmation?>(null)
    val kgExtractionConfirmation: StateFlow<DocumentKgConfirmation?> = _kgExtractionConfirmation.asStateFlow()
    private var kgPreparationJob: Job? = null

    private var queueResourceObservation: AutoCloseable? = null
    private var queueStateObservation: AutoCloseable? = null
    @Volatile private var observedQueue: com.promenar.infinity.data.rag.VectorizationQueue? = null

    private val _config = MutableStateFlow(RagConfiguration())
    private val libraryIndexer by lazy {
        com.promenar.infinity.data.library.LibraryDocumentIndexer(
            queue = app::awaitVectorizationQueue,
            taskDao = app.database.vectorizationTaskDao(),
            fileDao = app.database.fileEntryDao(),
            sessionGate = app.sessionExecutionGate,
            graphEnabled = { _config.value.enableKnowledgeGraph },
        )
    }
    val config: StateFlow<RagConfiguration> = _config.asStateFlow()

    private val prefs = app.getSharedPreferences("rag_settings", 0)
    private val configUpdateLock = Any()
    private val configSaver = injectedConfigSaver ?: ::saveConfig

    private val _stats = MutableStateFlow(RagStats())
    val stats: StateFlow<RagStats> = _stats.asStateFlow()

    private val _vectorStats = MutableStateFlow<VectorStats?>(null)
    val vectorStats: StateFlow<VectorStats?> = _vectorStats.asStateFlow()

    private val indexBaseline: IndexedChunkBaseline = injectedIndexBaseline
        ?: SharedPreferencesIndexedChunkBaseline(app.getSharedPreferences(SharedPreferencesIndexedChunkBaseline.PREFS_NAME, 0))

    /** 现有索引建立时的分块参数；与当前配置不同时，调试页提示全部重新向量化。 */
    private val _indexedChunkSettings = MutableStateFlow<ChunkSettings?>(null)
    val indexedChunkSettings: StateFlow<ChunkSettings?> = _indexedChunkSettings.asStateFlow()

    init {
        loadConfig()
        loadStats()
        startDataObservation()
        observeQueue()
        ensureRagWorkspaceRoot()
        pendingIndexCoordinator?.let { coordinator ->
            viewModelScope.launch {
                coordinator.pendingTargets.collect {
                    synchronizeSharedPendingTargets()
                }
            }
        }
    }

    /** 为全局知识库建立不可见的系统 Session owner，再通过统一 repository 认领真实 root。 */
    private fun ensureRagWorkspaceRoot() {
        viewModelScope.launch {
            try {
                val root = ensureGlobalKnowledgeRoot()
                _selectedWorkspaceSessionId.value = RagWorkspaceProvisioner.SESSION_ID
                _workspaceRootUuid.value = root.uuid
                synchronizeSharedPendingTargets()
                _availableWorkspaceRoots.value = listAvailableRagWorkspaceRoots()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                InfinityLogger.logError("[RagViewModel] ensureRagWorkspaceRoot failed", failure)
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure::class.simpleName,
                )
            }
        }
    }

    /**
     * 切换知识库浏览来源。切换期间先撤销旧根的观察与搜索，只有目标 Session 重新通过
     * 根身份校验后才发布新的 UUID，避免旧来源的晚到结果落入当前面板。
     */
    fun selectWorkspaceSource(sessionId: String) {
        val source = _availableWorkspaceRoots.value.firstOrNull { it.sessionId == sessionId }
            ?: return
        if (_selectedWorkspaceSessionId.value == sessionId &&
            _workspaceRootUuid.value == source.workspaceRootUuid
        ) {
            return
        }

        val previousSessionId = _selectedWorkspaceSessionId.value
        val previousRootUuid = _workspaceRootUuid.value
        workspaceSwitchGeneration += 1
        val generation = workspaceSwitchGeneration
        workspaceSwitchJob?.cancel()
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
        folderDocumentsObservationJob?.cancel()
        folderDocumentsObservationJob = null
        _searchResults.value = emptyList()
        _searchState.value = RagSearchUiState.Idle
        _workspaceRootUuid.value = null
        _documents.value = emptyList()
        _folders.value = emptyList()
        _folderStats.value = emptyMap()
        _pendingRenameIndexTargets.value = emptyList()

        workspaceSwitchJob = viewModelScope.launch {
            try {
                val root = ensureRagWorkspaceRootForSession(sessionId)
                check(root.uuid == source.workspaceRootUuid) {
                    "知识库来源根身份在切换期间发生变化"
                }
                if (generation != workspaceSwitchGeneration) return@launch
                _selectedWorkspaceSessionId.value = sessionId
                _workspaceRootUuid.value = root.uuid
                synchronizeSharedPendingTargets()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != workspaceSwitchGeneration) return@launch
                InfinityLogger.logError("[RagViewModel] selectWorkspaceSource failed", failure)
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure::class.simpleName,
                )
                // 目标未通过校验时恢复此前已经验证的来源，不能把 UI 绑定到未验证根。
                _selectedWorkspaceSessionId.value = previousSessionId
                _workspaceRootUuid.value = previousRootUuid
            }
        }
    }

    private fun observeQueue() {
        queueResourceObservation = app.observeVectorizationQueueInstances { observedQueue ->
            queueStateObservation?.close()
            queueStateObservation = null
            this.observedQueue = observedQueue
            queueStateObservation = observedQueue.setOnStateChange { allTasks, runningTask ->
                if (this.observedQueue !== observedQueue) return@setOnStateChange
                // 会话记忆与会话图谱抽取在聊天界面展示状态，不进入资料库的索引卡片与重试。
                val conversationTypes = com.promenar.infinity.data.rag.VectorizationQueue.CONVERSATION_TASK_TYPES
                val queue = allTasks.filter { it.type !in conversationTypes }
                val currentTask = runningTask?.takeIf { it.type !in conversationTypes }
                val isProcessing = observedQueue.state.value.isDocumentIndexing
                _isIndexing.value = isProcessing
                // 使用 currentTask 的进度；若队列空但有残留错误，保持上次进度
                _indexingProgress.value = (currentTask?.progress ?: 0.0).toFloat() / 100f

                // 更新正在索引中的文件 UUID 集合（排除已失败、中断或完成的非活跃任务）
                _indexingDocIds.value = queue.filter {
                    it.type in setOf("document", com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE) &&
                        it.docId != null &&
                        it.status !in setOf("failed", "partial", "completed", "interrupted")
                }
                    .mapNotNull { it.docId }.toSet()

                val queueNotice = currentTask?.let(IndexingNotice::fromTask)
                if (queueNotice != null) {
                    _indexingNotice.value = queueNotice
                } else if (!shouldKeepNotice(_indexingNotice.value)) {
                    _indexingNotice.value = null
                }

                val task = currentTask
                if (task != null && task.status in setOf("failed", "partial")) {
                    lastFailedIndexTarget = task.toRetryTarget()
                    _canRetryLastFailedIndex.value = lastFailedIndexTarget != null
                    _isIndexing.value = true  // 保持错误卡片可见
                    _indexingProgress.value = (task.progress / 100.0).toFloat()
                } else if (task != null) {
                    clearRetryTarget()
                }

                // 队列空 & 无当前任务 & 无错误留存 → 停止展示
                if (queue.isEmpty() && currentTask == null) {
                    refreshStats()
                    if (!shouldKeepNotice(_indexingNotice.value)) {
                        _isIndexing.value = false
                        _indexingNotice.value = null
                    }
                    if (shouldKeepNotice(_indexingNotice.value)) {
                        _isIndexing.value = true
                    }
                }

                val currentRoot = _workspaceRootUuid.value
                val terminalStatuses = setOf("completed", "partial", "failed", "interrupted")
                val targetOrder = compareBy<com.promenar.infinity.data.rag.VectorizationTask> { it.targetEpoch }
                    .thenBy { it.status !in terminalStatuses }.thenBy { it.updatedAt }.thenBy { it.createdAt }
                // 同一文档只采用最新任务；无 KG 的新版本必须清除上一版本的状态。
                val kgStates = queue.filter { currentRoot != null && it.workspaceRootUuid == currentRoot && it.docId != null }
                    .groupBy { it.docId!! }.mapNotNull { (id, tasks) ->
                        val kgTask = tasks.maxWithOrNull(targetOrder) ?: return@mapNotNull null
                        if (kgTask.kgStrategy == null) return@mapNotNull null
                        id to when (kgTask.status) {
                            "completed" -> KgStatus.COMPLETED
                            "partial" -> KgStatus.PARTIAL
                            "failed", "interrupted" -> KgStatus.FAILED
                            else -> KgStatus.IN_PROGRESS
                        }
                    }.toMap()
                _kgExtractionStates.value = kgStates
                recomputeIndexingVisibility()
            }
        }
    }

    override fun onCleared() {
        workspaceSwitchJob?.cancel()
        folderDocumentsObservationJob?.cancel()
        folderStatsJob?.cancel()
        observedQueue = null
        queueResourceObservation?.close()
        queueResourceObservation = null
        queueStateObservation?.close()
        queueStateObservation = null
        super.onCleared()
    }

    private sealed interface FailedIndexTarget {
        val workspaceRootUuid: String
        val docId: String

        data class Document(
            override val workspaceRootUuid: String,
            override val docId: String,
        ) : FailedIndexTarget

        data class DocumentReference(
            override val workspaceRootUuid: String,
            override val docId: String,
        ) : FailedIndexTarget
    }

    private var lastFailedIndexTarget: FailedIndexTarget? = null

    private fun com.promenar.infinity.data.rag.VectorizationTask.toRetryTarget(): FailedIndexTarget? {
        val root = workspaceRootUuid ?: return null
        val documentId = docId ?: return null
        return when (type) {
            "document" -> FailedIndexTarget.Document(root, documentId)
            com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE ->
                FailedIndexTarget.DocumentReference(root, documentId)
            else -> null
        }
    }

    fun retryLastFailedIndex() {
        if (_isRetryingLastFailedIndex.value) return
        val target = lastFailedIndexTarget ?: return
        if (target is FailedIndexTarget.DocumentReference &&
            app.vectorizationQueueState.value.queue.any {
                it.workspaceRootUuid == target.workspaceRootUuid && it.docId == target.docId &&
                    it.error == com.promenar.infinity.data.rag.VectorizationQueue.KG_CONFIRMATION_REQUIRED
            }
        ) {
            extractKG(target.docId)
            return
        }
        _isRetryingLastFailedIndex.value = true
        viewModelScope.launch {
            try {
                val submitted = when (target) {
                    is FailedIndexTarget.Document -> retryDocument(target)
                    is FailedIndexTarget.DocumentReference -> libraryIndexer.retryReference(
                        target.workspaceRootUuid,
                        target.docId,
                    )
                }
                if (submitted) {
                    clearRetryTarget()
                    _indexingNotice.value = null
                    _isIndexing.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure.message?.take(160),
                )
            } finally {
                _isRetryingLastFailedIndex.value = false
            }
        }
    }

    private suspend fun retryDocument(target: FailedIndexTarget.Document): Boolean {
        val entry = workspaceRepository.getByUuid(target.workspaceRootUuid, target.docId) ?: return false
        return libraryIndexer.retryLegacy(target.workspaceRootUuid, entry)
    }

    private suspend fun enqueueCurrentDocumentReference(
        workspaceRootUuid: String,
        entry: FileEntry,
    ) {
        libraryIndexer.enqueue(workspaceRootUuid, entry)
    }

    private fun clearRetryTarget() {
        lastFailedIndexTarget = null
        _canRetryLastFailedIndex.value = false
    }

    private fun discardFailedIndexTarget() {
        val discardedTarget = lastFailedIndexTarget
        clearRetryTarget()
        discardedTarget?.let { target ->
            viewModelScope.launch {
                app.awaitVectorizationQueue().cancelAndJoin(target.workspaceRootUuid, listOf(target.docId))
            }
        }
    }

    /** 手动关闭错误提示（用户点击关闭后清除） */
    fun dismissQueueError() {
        if (_isRetryingLastFailedIndex.value || _isRetryingPendingRenameIndex.value) return
        if (_operationNotice.value != null) {
            _operationNotice.value = null
            recomputeIndexingVisibility()
            return
        }
        discardFailedIndexTarget()
        _indexingNotice.value = null
        observedQueue?.clearAttention()
        recomputeIndexingVisibility()
    }

    /** 手动取消正在进行中的向量化/索引任务 */
    fun cancelIndexing() {
        viewModelScope.launch {
            discardFailedIndexTarget()
            observedQueue?.clear()
            _indexingDocIds.value = emptySet()
            _isIndexing.value = false
            _indexingNotice.value = null
            clearRetryTarget()
            recomputeIndexingVisibility()
        }
    }

    private fun reportOperationFailure(failure: Exception) {
        _operationNotice.value = UiStatusNotice(
            severity = NoticeSeverity.Error,
            code = IndexingNotice.CODE_OPERATION_FAILED,
            technical = failure::class.java.simpleName,
        )
    }

    private fun logRefreshFailure(operation: String, failure: Exception) {
        InfinityLogger.log("[RagViewModel] $operation failed: ${failure::class.java.simpleName}")
    }

    private fun shouldKeepNotice(notice: UiStatusNotice?): Boolean = when (notice?.code) {
        IndexingNotice.CODE_FAILED,
        IndexingNotice.CODE_OPERATION_FAILED,
        IndexingNotice.CODE_PARTIAL,
        IndexingNotice.CODE_KG_PARTIAL,
        IndexingNotice.CODE_KG_CONFIRMATION,
        IndexingNotice.CODE_WARNING,
        IndexingNotice.CODE_IMPORT_FAILED,
        IndexingNotice.CODE_MOVE_FAILED,
        IndexingNotice.CODE_DELETE_FAILED -> true
        else -> false
    }

    private fun startDataObservation() {
        viewModelScope.launch {
            workspaceRootUuid.flatMapLatest { root ->
                if (root == null) {
                    flowOf<Pair<String?, List<FileEntry>>>(null to emptyList<FileEntry>())
                } else {
                    workspaceRepository.observeChildren(root, root).map { entries -> root to entries }
                }
            }.collect { (root, roots) ->
                val allFiles = roots.filter { !it.isDirectory }
                _documents.value = allFiles.map { it.toDocument() }
                if (root == null) _folderStats.value = emptyMap()
            }
        }

        viewModelScope.launch {
            workspaceRootUuid.flatMapLatest { root ->
                if (root == null) {
                    flowOf<Pair<String?, List<FileEntry>>>(null to emptyList<FileEntry>())
                } else {
                    workspaceRepository.observeChildren(root, root).map { entries -> root to entries }
                }
            }.collect { (root, entries) ->
                val dirs = entries.filter { it.isDirectory }
                _folders.value = dirs.map { it.toFolder() }
                updateFolderStats(root, _folders.value)
            }
        }
    }

    private fun refreshStats() {
        viewModelScope.launch {
            loadStats()
            updateFolderStats(_workspaceRootUuid.value, _folders.value)
        }
    }

    private fun updateFolderStats(rootUuid: String?, folderList: List<Folder>) {
        folderStatsJob?.cancel()
        if (rootUuid == null) {
            _folderStats.value = emptyMap()
            return
        }
        folderStatsJob = viewModelScope.launch {
            val stats = mutableMapOf<String, Int>()
            for (folder in folderList) {
                val children = workspaceRepository.observeChildren(rootUuid, folder.id).first()
                stats[folder.id] = children.size
            }
            if (_workspaceRootUuid.value == rootUuid) {
                _folderStats.value = stats
            }
        }
    }

    private fun loadConfig() {
        val rag = ragConfigPersistence.loadRagConfig()
        val retrieval = ragConfigPersistence.loadRetrievalConfig()
        _config.value = com.promenar.infinity.data.rag.KgAdvancedSettings.load(RagConfiguration(
            enableMemory = retrieval.enableMemory,
            enableDocs = retrieval.enableDocs,
            enableKnowledgeGraph = retrieval.enableKnowledgeGraph,
            enableQueryRewrite = retrieval.enableQueryRewrite,
            enableHybridSearch = retrieval.enableHybridSearch,
            enableRerank = retrieval.enableRerank,
            enableIncrementalHash = prefs.getBoolean(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, true),
            enableLocalPreprocess = prefs.getBoolean(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS, false),
            queryRewriteStrategy = retrieval.queryRewriteStrategy,
            queryRewriteModel = retrieval.queryRewriteModel,
            queryRewriteCount = retrieval.queryRewriteCount,
            memoryLimit = retrieval.memoryLimit,
            memoryThreshold = retrieval.memoryThreshold,
            docLimit = retrieval.docLimit,
            docThreshold = retrieval.docThreshold,
            docChunkSize = rag.docChunkSize,
            chunkOverlap = rag.chunkOverlap,
            memoryChunkSize = rag.memoryChunkSize,
            rerankTopK = retrieval.rerankTopK,
            rerankFinalK = retrieval.rerankFinalK,
            hybridAlpha = retrieval.hybridAlpha,
            hybridBM25Boost = retrieval.hybridBM25Boost,
            kgExtractionModel = retrieval.kgExtractionModel,
            kgExtractionPrompt = retrieval.kgExtractionPrompt,
            kgFreeMode = retrieval.kgFreeMode,
            kgDomainAuto = retrieval.kgDomainAuto,
            kgExtractionTimeoutSeconds = retrieval.kgExtractionTimeoutSeconds,
            costStrategy = prefs.getString(RagConfigPersistence.KEY_COST_STRATEGY, "on-demand") ?: "on-demand",
            jitMaxChunks = retrieval.jitMaxChunks,
            showRetrievalProgress = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS, true),
            showRetrievalDetails = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, true),
            trackRetrievalMetrics = prefs.getBoolean(RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS, false),
            summaryTemplate = rag.summaryTemplate.ifEmpty {
                RagConfiguration().summaryTemplate
            },
            currentPreset = rag.currentPreset,
            rerankMaxPerCall = prefs.getInt("rerank_max_per_call", 100)
        ), prefs)
        _indexedChunkSettings.value = indexBaseline.load()
            ?: _config.value.chunkSettings().also(indexBaseline::save)
    }

    /** 把当前分块参数记为索引基线：全部重新向量化已提交或向量已清空时调用。 */
    private fun recordIndexBaseline() {
        val current = _config.value.chunkSettings()
        indexBaseline.save(current)
        _indexedChunkSettings.value = current
    }

    private fun saveConfig(config: RagConfiguration) {
        com.promenar.infinity.data.rag.KgAdvancedSettings.save(config, prefs)
        ragConfigPersistence.saveRagConfig(
            com.promenar.infinity.data.agent.AgentRagConfig(
                docChunkSize = config.docChunkSize,
                chunkOverlap = config.chunkOverlap,
                memoryChunkSize = config.memoryChunkSize,
                summaryTemplate = config.summaryTemplate,
                currentPreset = config.currentPreset
            )
        )
        ragConfigPersistence.saveRetrievalConfig(
            com.promenar.infinity.data.agent.AgentRetrievalConfig(
                memoryLimit = config.memoryLimit,
                memoryThreshold = config.memoryThreshold,
                docLimit = config.docLimit,
                docThreshold = config.docThreshold,
                enableRerank = config.enableRerank,
                rerankTopK = config.rerankTopK,
                rerankFinalK = config.rerankFinalK,
                enableQueryRewrite = config.enableQueryRewrite,
                queryRewriteStrategy = config.queryRewriteStrategy,
                queryRewriteCount = config.queryRewriteCount,
                enableHybridSearch = config.enableHybridSearch,
                hybridAlpha = config.hybridAlpha,
                hybridBM25Boost = config.hybridBM25Boost,
                enableMemory = config.enableMemory,
                enableDocs = config.enableDocs,
                enableKnowledgeGraph = config.enableKnowledgeGraph,
                queryRewriteModel = config.queryRewriteModel,
                kgExtractionModel = config.kgExtractionModel,
                kgExtractionPrompt = config.kgExtractionPrompt,
                kgFreeMode = config.kgFreeMode,
                kgExtractionTimeoutSeconds = config.kgExtractionTimeoutSeconds,
            )
        )
        prefs.edit()
            .remove(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH)
            .remove(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS)
            .remove(RagConfigPersistence.KEY_KG_DOMAIN_AUTO)
            .remove(RagConfigPersistence.KEY_JIT_MAX_CHUNKS)
            .remove(RagConfigPersistence.KEY_EMBED_DIMENSION)
            .remove(RagConfigPersistence.KEY_MAX_EMBED_TOKENS_PER_CALL)
            .putString(RagConfigPersistence.KEY_COST_STRATEGY, config.costStrategy)
            .putBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS, config.showRetrievalProgress)
            .putBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, config.showRetrievalDetails)
            .putBoolean(RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS, config.trackRetrievalMetrics)
            .putInt("rerank_max_per_call", config.rerankMaxPerCall)
            .apply()

        // P0: 用户修改配置后立即重建 MemoryManager，使新参数即时生效
        app.rebuildMemoryManager()
        app.rebuildGraphExtractor()
    }
    /** 暴露工作区仓库供 UI 层 FilesPanel 使用（支持挂载会话工作区的全局资源聚合仓库） */
    fun getWorkspaceRepo(): IWorkspaceRepository = compositeWorkspaceRepository

    /**
     * 将会话工作区内的文件转存沉淀至全局知识库中，并自动触发入队切块与向量化。
     */
    fun transferFileToKnowledgeBase(fileUuid: String, targetFolderUuid: String? = null) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val compositeRepo = compositeWorkspaceRepository as? com.promenar.infinity.data.repository.CompositeGlobalWorkspaceRepository
                if (compositeRepo != null) {
                    val transferred = compositeRepo.transferFileToKnowledgeBase(
                        sourceFileUuid = fileUuid,
                        targetKnowledgeBaseRootUuid = rootUuid,
                        targetFolderUuid = targetFolderUuid,
                    )
                    enqueueCurrentDocumentReference(rootUuid, transferred)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    fun loadCollections() {
        viewModelScope.launch {
            loadStats()
        }
    }

    private fun loadVectorStats() {
        viewModelScope.launch {
            try {
                _vectorStats.value = vectorStatsService.getStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("loadVectorStats", failure)
            }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val docCount = _documents.value.size
                val nodeCount = kgRepository.getNodeCount()
                val vStats = vectorStatsService.getStats()
                _vectorStats.value = vStats
                _stats.value = RagStats(
                    documentCount = docCount,
                    memoryCount = vStats.byType.memory,
                    graphEntityCount = nodeCount
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("loadStats", failure)
            }
        }
    }

    fun createFolder(name: String, parentFolderId: String? = null) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val targetParent = parentFolderId?.takeIf { it.isNotBlank() } ?: rootUuid
                val uuid = java.util.UUID.randomUUID().toString()
                val parentEntry = if (targetParent != rootUuid) {
                    workspaceRepository.getByUuid(rootUuid, targetParent)
                } else null
                val parentPath = parentEntry?.materializedPath?.trimEnd('/') ?: ""
                val matPath = "$parentPath/$name"
                workspaceRepository.createDirectoryInWorkspace(
                    workspaceRootUuid = rootUuid,
                    uuid = uuid,
                    name = name,
                    parentUuid = targetParent,
                    materializedPath = matPath
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    fun loadDocumentsForFolder(folderId: String) {
        folderDocumentsObservationJob?.cancel()
        folderDocumentsObservationJob = viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                workspaceRepository.observeChildren(rootUuid, folderId).collect { entries ->
                    if (_workspaceRootUuid.value == rootUuid) {
                        _documents.value = entries.filter { !it.isDirectory }.map { it.toDocument() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("loadDocumentsForFolder", failure)
            }
        }
    }

    fun deleteCollection(id: String, onResult: ((Boolean) -> Unit)? = null) =
        deleteDocuments(listOf(id)) { succeeded, _ -> onResult?.invoke(succeeded) }

    fun renameFolder(id: String, newName: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                when (val result = workspaceRepository.rename(rootUuid, id, newName)) {
                    is RenameResult.Success -> {
                        if (pendingIndexCoordinator == null) {
                            mergePendingRenameTargets(result.affectedTargets)
                        }
                        result.affectedTargets.forEach { target ->
                            try {
                                val event = target.toChangedEvent(rootUuid)
                                if (pendingIndexCoordinator != null) {
                                    pendingIndexCoordinator.publish(event)
                                } else {
                                    app.awaitVectorizationQueue().publish(event)
                                    removePendingRenameTarget(target)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                logRefreshFailure("renameIndex", failure)
                                // 已提交的重命名不能回滚；target 留在 VM 生命周期补偿队列中。
                            }
                        }
                        synchronizeSharedPendingTargets()
                        if (pendingIndexCoordinator == null) {
                            if (_pendingRenameIndexTargets.value.isNotEmpty()) {
                                _indexingNotice.value = UiStatusNotice(
                                    NoticeSeverity.Warning,
                                    IndexingNotice.CODE_WARNING,
                                )
                            } else if (_indexingNotice.value?.code == IndexingNotice.CODE_WARNING) {
                                _indexingNotice.value = null
                            }
                        }
                        loadStats()
                    }
                    is RenameResult.Conflict,
                    RenameResult.NotFound,
                    -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    private fun mergePendingRenameTargets(targets: List<RenameIndexTarget>) {
        if (targets.isEmpty()) return
        val merged = LinkedHashMap<String, RenameIndexTarget>()
        _pendingRenameIndexTargets.value.forEach { merged[it.fileUuid] = it }
        targets.forEach { merged[it.fileUuid] = it }
        _pendingRenameIndexTargets.value = merged.values.toList()
    }

    private fun removePendingRenameTarget(target: RenameIndexTarget) {
        _pendingRenameIndexTargets.value = _pendingRenameIndexTargets.value.filterNot { pending ->
            pending == target
        }
    }

    private suspend fun publishRenameIndexTarget(
        workspaceRootUuid: String,
        target: RenameIndexTarget,
    ) {
        app.awaitVectorizationQueue().publish(
            com.promenar.infinity.data.rag.FileIndexEvent.Changed(
                workspaceRootUuid = workspaceRootUuid,
                fileUuid = target.fileUuid,
                contentHash = target.targetHash,
                targetEpoch = target.targetEpoch,
            ),
        )
    }

    private fun RenameIndexTarget.toChangedEvent(workspaceRootUuid: String) =
        FileIndexEvent.Changed(
            workspaceRootUuid = workspaceRootUuid,
            fileUuid = fileUuid,
            contentHash = targetHash,
            targetEpoch = targetEpoch,
        )

    internal suspend fun retryPendingRenameIndexNow() {
        val rootUuid = _workspaceRootUuid.value ?: return
        if (pendingIndexCoordinator != null) {
            pendingIndexCoordinator.snapshot()
                .filter { it.workspaceRootUuid == rootUuid }
                .forEach { pendingIndexCoordinator.retry(it) }
            synchronizeSharedPendingTargets()
            return
        }
        val snapshot = _pendingRenameIndexTargets.value
        snapshot.forEach { target ->
            try {
                publishRenameIndexTarget(rootUuid, target)
                removePendingRenameTarget(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("retryRenameIndex", failure)
                // 保留失败 target，继续补偿其它互不依赖的文件。
            }
        }
        if (_pendingRenameIndexTargets.value.isEmpty()) {
            if (_indexingNotice.value?.code == IndexingNotice.CODE_WARNING) {
                _indexingNotice.value = null
            }
        } else {
            _indexingNotice.value = UiStatusNotice(
                NoticeSeverity.Warning,
                IndexingNotice.CODE_WARNING,
            )
        }
    }

    fun retryPendingRenameIndex() {
        if (_isRetryingPendingRenameIndex.value || _pendingRenameIndexTargets.value.isEmpty()) return
        _isRetryingPendingRenameIndex.value = true
        viewModelScope.launch {
            try {
                retryPendingRenameIndexNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isRetryingPendingRenameIndex.value = false
            }
        }
    }

    private fun synchronizeSharedPendingTargets() {
        val coordinator = pendingIndexCoordinator ?: return
        val rootUuid = _workspaceRootUuid.value
        _pendingRenameIndexTargets.value = coordinator.snapshot()
            .asSequence()
            .filter { it.workspaceRootUuid == rootUuid }
            .map { event ->
                RenameIndexTarget(
                    fileUuid = event.fileUuid,
                    targetHash = event.contentHash,
                    targetEpoch = event.targetEpoch,
                )
            }
            .toList()
        recomputeIndexingVisibility()
    }

    private fun recomputeIndexingVisibility() {
        _isIndexing.value = _pendingRenameIndexTargets.value.isNotEmpty() ||
            observedQueue?.state?.value?.isDocumentIndexing == true ||
            shouldKeepNotice(_indexingNotice.value) || _operationNotice.value != null ||
            _canRetryLastFailedIndex.value
    }

    fun deleteFolder(id: String) = deleteCollection(id)

    fun deleteDocuments(
        ids: Collection<String>,
        onComplete: ((Boolean, List<String>) -> Unit)? = null,
    ) {
        val attemptedIds = ids.distinct()
        if (attemptedIds.isEmpty()) {
            onComplete?.invoke(false, emptyList())
            return
        }
        if (_isDeletingDocuments.value) {
            onComplete?.invoke(false, attemptedIds)
            return
        }
        _isDeletingDocuments.value = true
        discardFailedIndexTarget()
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            val technicalFailures = mutableListOf<String>()
            try {
                val rootUuid = _workspaceRootUuid.value
                if (rootUuid == null) {
                    failed += attemptedIds
                } else {
                    attemptedIds.forEach { id ->
                        try {
                            val entry = workspaceRepository.getByUuid(rootUuid, id)
                            val entriesToClean = if (entry?.isDirectory == true) {
                                workspaceRepository.getSubtree(rootUuid, entry.materializedPath)
                            } else {
                                listOfNotNull(entry)
                            }
                            workspaceRepository.moveToRecycleBin(rootUuid, id)
                            val runtimeIds = entriesToClean.filterNot { it.isDirectory }.map { it.uuid }
                                .ifEmpty { if (entry?.isDirectory == true) emptyList() else listOf(id) }
                            runtimeIds.forEach(::clearDocumentRuntimeState)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            failed += id
                            technicalFailures += "$id:${failure::class.simpleName}"
                        }
                    }
                }

                _indexingNotice.value = when {
                    failed.isEmpty() -> null
                    else -> UiStatusNotice(
                        severity = if (failed.size == attemptedIds.size) NoticeSeverity.Error else NoticeSeverity.Warning,
                        code = IndexingNotice.CODE_DELETE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160).takeIf { it.isNotBlank() },
                    )
                }
                if (failed.size < attemptedIds.size) loadStats()
                onComplete?.invoke(failed.isEmpty(), failed.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isDeletingDocuments.value = false
            }
        }
    }

    fun importDocuments(uris: List<Uri>, folderId: String? = null) {
        if (uris.isEmpty()) return
        discardFailedIndexTarget()
        viewModelScope.launch {
            val rootUuid = _workspaceRootUuid.value ?: return@launch
            val parentUuid = folderId ?: rootUuid
            val imported = mutableListOf<ShareImportItem>()
            val failures = mutableListOf<String>()
            _indexingNotice.value = null
            _isIndexing.value = app.vectorizationQueueState.value.isDocumentIndexing

            for (uri in uris.distinct()) {
                try {
                    val request = importRequestFactory(uri, rootUuid)
                    val result = sharedFileImporter.import(
                        request = request,
                        workspaceRootUuid = rootUuid,
                        parentUuid = parentUuid,
                    )
                    imported += result.created
                    failures += result.rejected.map { item ->
                        "${item.displayName} (${item.reason?.name ?: "Rejected"})"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += "${uri.lastPathSegment ?: app.getString(R.string.rag_import_unknown_file)} - ${failure.message?.take(80) ?: app.getString(R.string.rag_import_unknown_error)}"
                }
            }

            if (failures.isNotEmpty()) {
                _indexingNotice.value = IndexingNotice.importFailed(
                    technical = failures.joinToString("；").take(240)
                )
                _isIndexing.value = true
            } else {
                _isIndexing.value = app.vectorizationQueueState.value.isDocumentIndexing
            }
            if (imported.isNotEmpty()) {
                loadStats()
            }
        }
    }

    /** 手动触发重新索引 */
    fun reindexFile(uuid: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@launch
                enqueueCurrentDocumentReference(rootUuid, entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    /** 批量重新索引 */
    fun reindexDocuments(uuids: Collection<String>) {
        if (uuids.isEmpty()) {
            _indexingNotice.value = UiStatusNotice(
                severity = NoticeSeverity.Warning,
                code = IndexingNotice.CODE_WARNING,
            )
            return
        }
        discardFailedIndexTarget()
        viewModelScope.launch {
            val rootUuid = _workspaceRootUuid.value ?: return@launch
            var submitted = 0
            var failed = 0
            uuids.forEach { uuid ->
                try {
                    val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@forEach
                    enqueueCurrentDocumentReference(rootUuid, entry)
                    submitted += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    InfinityLogger.logError("[RagViewModel] reindexDocuments failed: uuid=$uuid", e)
                    failed += 1
                }
            }
            _indexingNotice.value = when {
                failed == 0 -> null
                submitted == 0 -> UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_FAILED)
                else -> UiStatusNotice(NoticeSeverity.Warning, IndexingNotice.CODE_WARNING)
            }
        }
    }

    /** 移动文件到指定目录 */
    fun moveFile(uuid: String, targetParentUuid: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                moveFileInternal(rootUuid, uuid, targetParentUuid)
                onResult?.invoke(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
                onResult?.invoke(false)
            }
        }
    }

    fun moveDocuments(
        uuids: Collection<String>,
        targetParentUuid: String,
        onComplete: ((Boolean, List<String>) -> Unit)? = null,
    ) {
        if (uuids.isEmpty()) {
            onComplete?.invoke(false, emptyList())
            _indexingNotice.value = UiStatusNotice(
                severity = NoticeSeverity.Warning,
                code = IndexingNotice.CODE_WARNING,
            )
            return
        }
        if (_isMovingDocuments.value) {
            onComplete?.invoke(false, uuids.distinct())
            return
        }
        _isMovingDocuments.value = true
        discardFailedIndexTarget()
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value
                if (rootUuid == null) {
                    _indexingNotice.value = UiStatusNotice(
                        severity = NoticeSeverity.Error,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                    )
                    onComplete?.invoke(false, uuids.toList())
                    return@launch
                }
                val failed = mutableListOf<String>()
                val technicalFailures = mutableListOf<String>()
                var moved = 0
                uuids.distinct().forEach { uuid ->
                    try {
                        moveFileInternal(rootUuid, uuid, targetParentUuid)
                        moved += 1
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed += uuid
                        technicalFailures += "$uuid:${failure::class.simpleName}"
                    }
                }

                val success = moved > 0 && failed.isEmpty()
                _indexingNotice.value = when {
                    failed.isEmpty() -> null
                    moved == 0 -> UiStatusNotice(
                        severity = NoticeSeverity.Error,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160),
                    )
                    else -> UiStatusNotice(
                        severity = NoticeSeverity.Warning,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160),
                    )
                }
                if (failed.isEmpty()) {
                    _isIndexing.value = app.vectorizationQueueState.value.isDocumentIndexing
                }
                if (moved > 0) {
                    loadStats()
                }
                onComplete?.invoke(success, failed.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isMovingDocuments.value = false
            }
        }
    }

    private suspend fun moveFileInternal(rootUuid: String, uuid: String, targetParentUuid: String) {
        val newParent = targetParentUuid.ifEmpty { rootUuid }
        workspaceRepository.updateParent(rootUuid, uuid, newParent)
    }

    /** 手动抽取持久入队，由索引服务解析文件并原子切换图谱与完成标记。 */
    fun extractKG(uuid: String) {
        prepareKnowledgeGraphExtraction(uuid, null)
    }

    fun dismissKnowledgeGraphConfirmation() {
        _kgExtractionConfirmation.value = null
    }

    fun confirmKnowledgeGraphExtraction() {
        val confirmation = _kgExtractionConfirmation.value ?: return
        _kgExtractionConfirmation.value = null
        if (_workspaceRootUuid.value == confirmation.workspaceRootUuid) {
            prepareKnowledgeGraphExtraction(confirmation.fileUuid, confirmation)
        }
    }

    private fun prepareKnowledgeGraphExtraction(uuid: String, confirmation: DocumentKgConfirmation?) {
        kgPreparationJob?.cancel()
        _kgExtractionConfirmation.value = null
        kgPreparationJob = viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val entry = workspaceRepository.getByUuid(rootUuid, uuid)
                    ?: error("知识图谱抽取文件不存在")
                require(com.promenar.infinity.data.rag.DocumentReferenceExtractor.supports(entry)) { "文件类型或大小不支持知识图谱抽取" }
                require(!entry.inRecycleBin && !entry.isDirectory) { "知识图谱来源文件已失效" }
                val configuration = _config.value
                val confirmed = confirmation != null && confirmation.workspaceRootUuid == rootUuid &&
                    confirmation.contentHash == entry.hash && confirmation.targetEpoch == entry.updatedAt &&
                    confirmation.configuration == configuration
                val estimate = if (confirmed) confirmation!!.estimate else injectedKgEstimator?.invoke(entry)
                    ?: withContext(Dispatchers.IO) {
                        val text = com.promenar.infinity.data.rag.DocumentReferenceExtractor(
                            configuration.docChunkSize.coerceAtLeast(400), configuration.chunkOverlap.coerceIn(0, 200),
                        ).extract(entry).graphText
                        app.graphExtractor.estimate(text)
                    }
                if (_workspaceRootUuid.value != rootUuid) return@launch
                if (estimate.requiresConfirmation && !confirmed) {
                    _kgExtractionConfirmation.value = DocumentKgConfirmation(
                        rootUuid, uuid, entry.name, entry.hash, entry.updatedAt, estimate, configuration,
                    )
                    return@launch
                }
                _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.IN_PROGRESS)
                app.awaitVectorizationQueue().enqueueDocumentReference(
                    workspaceRootUuid = rootUuid,
                    docId = uuid,
                    docTitle = entry.name,
                    sourceMimeType = requireNotNull(entry.mimeType),
                    targetContentHash = entry.hash,
                    targetEpoch = entry.updatedAt,
                    kgStrategy = if (confirmed) KgExtractionBudget.CONFIRMED_STRATEGY else "full",
                    skipVectorization = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                InfinityLogger.logError("[RagViewModel] KG 入队失败", failure)
                _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.FAILED)
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure.message?.take(80),
                )
            }
        }
    }

    /** 复制文件（若来源为会话工作区，则转存至全局知识库） */
    fun copyFile(uuid: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val repo = getWorkspaceRepo()
                val entry = repo.getByUuid(rootUuid, uuid) ?: return@launch
                if (entry.workspaceRootUuid != rootUuid) {
                    transferFileToKnowledgeBase(uuid)
                    return@launch
                }
                val content = fileOperationRepository.readFileRange(rootUuid, uuid).content
                val newUuid = java.util.UUID.randomUUID().toString()
                val newName = duplicateFileName(entry.name) { base -> app.getString(R.string.rag_duplicate_file_name, base) }
                val parentUuid = entry.parentUuid ?: rootUuid
                val parentPath = repo.getByUuid(rootUuid, parentUuid)?.materializedPath ?: return@launch
                repo.createFileInWorkspace(
                    workspaceRootUuid = rootUuid,
                    uuid = newUuid,
                    name = newName,
                    content = content,
                    parentUuid = parentUuid,
                    materializedPath = parentPath.trimEnd('/') + "/$newName"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    fun extractKnowledgeGraph(docId: String, @Suppress("UNUSED_PARAMETER") kgStrategy: String) {
        extractKG(docId)
    }

    fun search(query: String) {
        val normalizedQuery = query.trim()
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (normalizedQuery.isBlank()) {
            _searchResults.value = emptyList()
            _searchState.value = RagSearchUiState.Idle
            return
        }
        _searchResults.value = emptyList()
        _searchState.value = RagSearchUiState.Loading(normalizedQuery)
        searchJob = viewModelScope.launch {
            delay(RagSearchContract.DEBOUNCE_MILLIS)
            if (generation != searchGeneration) return@launch
            val rootUuid = _workspaceRootUuid.value
            var titleFallback = emptyList<RagSearchResult>()
            try {
                val titleMatches = if (rootUuid == null) {
                    emptyList()
                } else {
                    workspaceRepository.searchByName(rootUuid, normalizedQuery).first()
                        .filterNot { it.isDirectory }
                }
                titleFallback = titleMatches.map {
                    RagSearchResult(
                        document = it.toDocument(),
                        matchKind = RagSearchMatchKind.TitleFallback,
                    )
                }
                val searchOutcome = if (rootUuid == null) {
                    null
                } else {
                    keywordSearcher.searchWithOutcome(
                        normalizedQuery,
                        limit = 20,
                        options = KeywordSearcher.SearchOptions(workspaceRootUuid = rootUuid),
                    )
                }
                if (searchOutcome?.mode == KeywordSearchMode.LikeFallback) {
                    if (generation != searchGeneration) return@launch
                    _searchResults.value = titleFallback
                    _searchState.value = RagSearchUiState.Warning(
                        query = normalizedQuery,
                        code = RagSearchErrorCode.FtsUnavailable,
                        fallbackItems = titleFallback,
                        technical = searchOutcome.technical?.take(80),
                    )
                    return@launch
                }
                val ftsItems = if (searchOutcome == null) {
                    emptyList()
                } else {
                    val seenDocIds = mutableSetOf<String>()
                    buildList {
                        searchOutcome.results.forEach { hit ->
                            val docId = hit.docId ?: return@forEach
                            if (!seenDocIds.add(docId)) return@forEach
                            val activeRootUuid = rootUuid ?: return@forEach
                            val entry = workspaceRepository.getByUuid(activeRootUuid, docId)
                                ?.takeUnless { it.isDirectory }
                                ?: return@forEach
                            add(
                                RagSearchResult(
                                    document = entry.toDocument(),
                                    snippet = buildRagSearchSnippet(hit.content, normalizedQuery),
                                    matchKind = RagSearchMatchKind.FtsBody,
                                ),
                            )
                        }
                    }
                }
                if (generation != searchGeneration) return@launch
                val ftsIds = ftsItems.mapTo(mutableSetOf()) { it.document.id }
                val merged = ftsItems + titleFallback.filterNot { it.document.id in ftsIds }
                _searchResults.value = merged
                _searchState.value = RagSearchUiState.Results(normalizedQuery, merged)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != searchGeneration) return@launch
                _searchResults.value = titleFallback
                _searchState.value = RagSearchUiState.Warning(
                    query = normalizedQuery,
                    code = RagSearchErrorCode.FtsUnavailable,
                    fallbackItems = titleFallback,
                    technical = failure::class.simpleName?.take(80),
                )
            }
        }
    }

    fun loadMemoryVectors() {
        viewModelScope.launch {
            try {
                _memoryVectors.value = vectorRepository.getMemoryVectors()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logRefreshFailure("loadMemoryVectors", failure)
            }
        }
    }

    fun deleteMemoryVector(id: String) {
        viewModelScope.launch {
            try {
                vectorRepository.deleteVector(id)
                _memoryVectors.value = _memoryVectors.value.filter { it.id != id }
                loadStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    private fun clearDocumentRuntimeState(docId: String) {
        _indexingDocIds.update { ids -> ids - docId }
        _kgExtractionStates.update { states -> states - docId }
    }

    fun clearAllVectors(withGraph: Boolean = false) {
        viewModelScope.launch {
            try {
                vectorRepository.deleteAll()
                if (withGraph) {
                    kgRepository.clear()
                }
                recordIndexBaseline()
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                workspaceRepository.resetAllRAGStatus(rootUuid)
                loadStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportOperationFailure(failure)
            }
        }
    }

    /**
     * 按当前分块设置重新向量化全局知识库中的全部文档，提交后把当前分块参数记为索引基线。
     * 对话记忆的向量来自已结束的对话，不在重建范围内。[onSubmitted] 收到提交的文档数。
     */
    fun reindexAllDocuments(onSubmitted: (Int) -> Unit = {}) {
        discardFailedIndexTarget()
        viewModelScope.launch {
            val rootUuid = try {
                ensureGlobalKnowledgeRoot().uuid
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                InfinityLogger.logError("[RagViewModel] reindexAllDocuments root failed", e)
                _indexingNotice.value = UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_FAILED)
                return@launch
            }
            val documents = try {
                knowledgeBaseDocuments(rootUuid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                InfinityLogger.logError("[RagViewModel] reindexAllDocuments list failed", e)
                _indexingNotice.value = UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_FAILED)
                return@launch
            }
            var submitted = 0
            var failed = 0
            documents.forEach { entry ->
                try {
                    enqueueCurrentDocumentReference(rootUuid, entry)
                    submitted += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    InfinityLogger.logError("[RagViewModel] reindexAllDocuments failed: uuid=${entry.uuid}", e)
                    failed += 1
                }
            }
            if (failed == 0) recordIndexBaseline()
            _indexingNotice.value = when {
                failed == 0 -> null
                submitted == 0 -> UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_FAILED)
                else -> UiStatusNotice(NoticeSeverity.Warning, IndexingNotice.CODE_WARNING)
            }
            onSubmitted(submitted)
        }
    }

    /** 知识库根下所有可索引的文档（含子文件夹），不含回收站。 */
    private suspend fun knowledgeBaseDocuments(rootUuid: String): List<FileEntry> =
        workspaceRepository.observeRoots(rootUuid).first()
            .flatMap { entry ->
                if (entry.isDirectory) workspaceRepository.getSubtree(rootUuid, entry.materializedPath) else listOf(entry)
            }
            .filter { entry ->
                DocumentReferenceExtractor.supports(entry) && entry.hash.isNotBlank() && entry.updatedAt > 0
            }
            .distinctBy { it.uuid }

    fun updateConfig(config: RagConfiguration) {
        updateConfig { config }
    }

    fun updateConfig(transform: (RagConfiguration) -> RagConfiguration) {
        synchronized(configUpdateLock) {
            val newConfig = transform(_config.value)
            _config.value = newConfig
            configSaver(newConfig)
        }
    }

    /** 套用检索深度档位：一次写入四个原始值，保存后随配置重建记忆管理器。 */
    fun applyRetrievalDepth(depth: RetrievalDepth) {
        updateConfig { current -> current.withRetrievalDepth(depth) }
    }

    fun applyPreset(preset: String) {
        updateConfig { current ->
            when (preset.lowercase()) {
                "balanced" -> current.copy(
                    currentPreset = "balanced",
                    docChunkSize = 800,
                    chunkOverlap = 100
                )
                "writing" -> current.copy(
                    currentPreset = "writing",
                    docChunkSize = 1200,
                    chunkOverlap = 200
                )
                "coding" -> current.copy(
                    currentPreset = "coding",
                    docChunkSize = 500,
                    chunkOverlap = 50
                )
                else -> current
            }
        }
    }

    private fun FileEntry.toFolder() = Folder(
        id = uuid,
        name = name,
        parentId = parentUuid,
        createdAt = createdAt
    )

    private fun FileEntry.toDocument() = Document(
        id = uuid,
        folderId = parentUuid ?: "",
        title = name,
        content = "",
        hash = hash,
        fileSize = sizeBytes,
        vectorized = if (vectorizedAt != null) 2 else 0,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    val vectorRepo = com.promenar.infinity.data.repository.VectorRepository(
                        vectorDao = app.database.vectorDao(),
                        embeddingClient = app.embeddingClient
                    )
                    val ragPrefs = app.getSharedPreferences("rag_settings", 0)
                    return RagViewModel(
                        application = application,
                        workspaceRepository = app.workspaceRepository,
                        vectorRepository = vectorRepo,
                        kgRepository = com.promenar.infinity.data.repository.KnowledgeGraphRepository(
                            kgNodeDao = app.database.kgNodeDao(),
                            kgEdgeDao = app.database.kgEdgeDao()
                        ),
                        fileOperationRepository = app.fileOperationRepository,
                        ragConfigPersistence = RagConfigPersistence(ragPrefs),
                        keywordSearcher = app.keywordSearcher,
                        injectedPendingIndexCoordinator = app.pendingDocumentIndexCoordinator,
                    ) as T
                }
            }
    }
}

/** 副本名：在扩展名前插入当前语言的副本后缀；没有扩展名时不追加多余的点。 */
internal fun duplicateFileName(name: String, copyOf: (String) -> String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) copyOf(name.substring(0, dot)) + name.substring(dot) else copyOf(name)
}
