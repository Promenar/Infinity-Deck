package com.promenar.infinity.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.library.LibraryKnowledgeModule
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.ConversationFragment
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.domain.library.KnowledgeCitationRepository
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.domain.library.DocumentInsight
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.domain.library.GraphEstimate
import com.promenar.infinity.domain.library.LibraryActionResult
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryKnowledgeSource
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 资料页给用户的一次性提示。 */
sealed interface LibraryNotice {
    data class ActionDone(val kind: Kind) : LibraryNotice
    data class ActionFailed(val kind: Kind, val reason: String?) : LibraryNotice

    enum class Kind { RETRY, REBUILD, GRAPH, IMPORT, NOTE, CLEAR_FRAGMENTS, DELETE_FRAGMENT }
}

/** 抽取知识图谱前展示给用户确认的用量估算。 */
data class GraphExtractionRequest(val documentId: String, val name: String, val estimate: GraphEstimate)

/** 左列与外屏“文件”页的位置分组：位置、固定的文件夹。 */
data class LibraryLocations(
    val locations: List<FileLocation> = emptyList(),
    val pins: List<FileRef> = emptyList(),
    /** 固定的会话文件夹所属会话名，键为 `session:<id>`；会话不在位置列表里，侧栏靠它显示名称。 */
    val sessionNames: Map<String, String> = emptyMap(),
)

/**
 * 资料页的数据入口：概览、待处理、最近、对话片段与“新记住的”来自知识层；位置与固定文件夹来自资源管理器数据层。
 * 资源管理器有独立控制器，这里不重复。
 */
class LibraryViewModel(
    private val knowledge: LibraryKnowledgeSource,
    private val files: FileManagerServices,
    private val citations: KnowledgeCitationRepository,
) : ViewModel() {

    val overview: StateFlow<LibraryOverview?> = knowledge.observeOverview()
        .catch { emit(EMPTY_OVERVIEW) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val pending: StateFlow<List<PendingDocument>> = knowledge.observePending()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val recent: StateFlow<List<LibraryDocumentSummary>> = knowledge.observeRecent(RECENT_LIMIT)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val fragmentGroups: StateFlow<List<FragmentGroup>> = knowledge.observeFragmentGroups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val newMemoryIds: StateFlow<Set<String>> = knowledge.observeNewMemoryIds()
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /** 概览“智能体最近用到”。 */
    val recentCited: StateFlow<List<CitedDocument>> = citations.observeRecentDocuments(OVERVIEW_CITED_LIMIT)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** 视图“智能体常用”。 */
    val topCited: StateFlow<List<CitedDocument>> = citations.observeTopDocuments()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** 文档页“智能体怎么用它”。 */
    fun citationsFor(documentId: String): Flow<List<DocumentCitation>> = citations.observeForDocument(documentId).catch { emit(emptyList()) }

    val locations: StateFlow<LibraryLocations> = combine(
        files.locations.observeLibraryLocations(),
        files.pins.observePins(),
    ) { locations, pins ->
        val names = if (pins.any { it.locationId.startsWith("session:") }) sessionNames() else emptyMap()
        LibraryLocations(locations, pins, names)
    }
        .catch { error ->
            com.promenar.infinity.utils.InfinityLogger.logError("LibraryViewModel.locations", error)
            emit(LibraryLocations())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryLocations())

    private suspend fun sessionNames(): Map<String, String> = try {
        withContext(Dispatchers.IO) { files.operations.list("sessions", "/") }.associate { it.ref.locationId to it.name }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }

    private val _notice = MutableStateFlow<LibraryNotice?>(null)
    val notice: StateFlow<LibraryNotice?> = _notice.asStateFlow()

    /** 正在重试或重建的文档，避免重复点击。 */
    private val _busyDocuments = MutableStateFlow<Set<String>>(emptySet())
    val busyDocuments: StateFlow<Set<String>> = _busyDocuments.asStateFlow()

    fun fragments(sessionId: String): Flow<List<ConversationFragment>> = knowledge.observeFragments(sessionId)

    suspend fun insight(documentId: String): DocumentInsight? = runCatchingCancellable { knowledge.insight(documentId) }
    suspend fun body(documentId: String): DocumentBody? = runCatchingCancellable { knowledge.body(documentId) }

    /** 待处理文档的主动作：失败重试，过旧重建；排队与索引中不提供动作。 */
    fun resolve(document: PendingDocument) {
        when (document.kind) {
            PendingKind.GRAPH_ATTENTION ->
                if (document.graphConfirmation) requestGraphExtraction(document.documentId, document.name) else retry(document.documentId)
            PendingKind.FAILED -> retry(document.documentId)
            PendingKind.STALE -> rebuild(document.documentId)
            PendingKind.QUEUED, PendingKind.INDEXING -> Unit
        }
    }

    fun retry(documentId: String) = runDocumentAction(documentId, LibraryNotice.Kind.RETRY) { knowledge.retry(documentId) }
    fun rebuild(documentId: String) = runDocumentAction(documentId, LibraryNotice.Kind.REBUILD) { knowledge.rebuild(documentId) }

    private val _graphRequest = MutableStateFlow<GraphExtractionRequest?>(null)

    /** 等待用户确认的图谱抽取；为 null 时不显示确认框。 */
    val graphRequest: StateFlow<GraphExtractionRequest?> = _graphRequest.asStateFlow()

    /**
     * 抽取知识图谱的入口：先按当前图谱设置估算用量，交给确认框展示，用户确认后才发起请求。
     * 无论文档是否超过大文档门槛都先确认，抽取总会产生模型费用。
     */
    fun requestGraphExtraction(documentId: String, name: String) {
        if (documentId in _busyDocuments.value) return
        _busyDocuments.value = _busyDocuments.value + documentId
        viewModelScope.launch {
            try {
                val estimate = runCatchingCancellable { knowledge.graphEstimate(documentId) }
                if (estimate == null) _notice.value = LibraryNotice.ActionFailed(LibraryNotice.Kind.GRAPH, null)
                else _graphRequest.value = GraphExtractionRequest(documentId, name, estimate)
            } finally {
                _busyDocuments.value = _busyDocuments.value - documentId
            }
        }
    }

    fun confirmGraphExtraction() {
        val request = _graphRequest.value ?: return
        _graphRequest.value = null
        runDocumentAction(request.documentId, LibraryNotice.Kind.GRAPH) { knowledge.extractGraph(request.documentId, confirmed = true) }
    }

    fun dismissGraphExtraction() {
        _graphRequest.value = null
    }

    private fun runDocumentAction(documentId: String, kind: LibraryNotice.Kind, action: suspend () -> LibraryActionResult) {
        if (documentId in _busyDocuments.value) return
        _busyDocuments.value = _busyDocuments.value + documentId
        viewModelScope.launch {
            try {
                _notice.value = when (val result = action()) {
                    LibraryActionResult.Done -> LibraryNotice.ActionDone(kind)
                    is LibraryActionResult.Failed -> LibraryNotice.ActionFailed(kind, result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = LibraryNotice.ActionFailed(kind, e.message)
            } finally {
                _busyDocuments.value = _busyDocuments.value - documentId
            }
        }
    }

    /** 概览“收集”卡：从设备导入到资料库根目录。 */
    fun importToLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        launchNotice(LibraryNotice.Kind.IMPORT) {
            val outcome = files.operations.importUris(uris, LIBRARY_ROOT)
            if (outcome.failed.isEmpty()) null else outcome.failed.first().code.name
        }
    }

    /** 概览“收集”卡：把一段文字存成资料库根目录下的笔记。 */
    fun saveNote(name: String, text: String) {
        launchNotice(LibraryNotice.Kind.NOTE) {
            files.operations.importText(text, LIBRARY_ROOT, name)
            null
        }
    }

    fun deleteFragment(id: String) {
        launchNotice(LibraryNotice.Kind.DELETE_FRAGMENT, silentSuccess = true) {
            knowledge.deleteFragment(id)
            null
        }
    }

    fun clearFragments(sessionId: String) {
        launchNotice(LibraryNotice.Kind.CLEAR_FRAGMENTS) {
            knowledge.clearFragments(sessionId)
            null
        }
    }

    fun markMemoriesSeen() {
        viewModelScope.launch { runCatchingCancellable { knowledge.markMemoriesSeen() } }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** [block] 返回 null 表示成功，否则为失败原因。 */
    private fun launchNotice(kind: LibraryNotice.Kind, silentSuccess: Boolean = false, block: suspend () -> String?) {
        viewModelScope.launch {
            val failure = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileOpException) {
                e.code.name
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            when {
                failure != null -> _notice.value = LibraryNotice.ActionFailed(kind, failure)
                !silentSuccess -> _notice.value = LibraryNotice.ActionDone(kind)
            }
        }
    }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val RECENT_LIMIT = 50
        private const val OVERVIEW_CITED_LIMIT = 5
        val LIBRARY_ROOT = FileRef(LIBRARY_LOCATION_ID, "/")
        private val EMPTY_OVERVIEW = LibraryOverview(0, 0, 0, 0, 0, 0)

        fun factory(application: InfinityApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
                knowledge = LibraryKnowledgeModule.create(application),
                files = application.fileManagerServices,
                citations = application.knowledgeCitationRepository,
            ) as T
        }
    }
}

/** 资料库位置的固定 id，见 files-core 的位置 id 规则。 */
const val LIBRARY_LOCATION_ID = "kb"
