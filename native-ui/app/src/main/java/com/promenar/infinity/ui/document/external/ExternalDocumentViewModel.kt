package com.promenar.infinity.ui.document.external

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.data.document.MarkdownChunks
import com.promenar.infinity.data.document.external.ExternalDocument
import com.promenar.infinity.data.document.external.ExternalDocumentFailure
import com.promenar.infinity.data.document.external.ExternalDocumentInput
import com.promenar.infinity.data.document.external.ExternalDocumentResult
import com.promenar.infinity.data.document.external.ExternalDocumentSender
import com.promenar.infinity.data.document.external.ExternalDocumentStore
import com.promenar.infinity.data.document.external.ExternalLibrarySaveResult
import com.promenar.infinity.data.document.external.ExternalSendMode
import com.promenar.infinity.data.document.external.ExternalSendPolicy
import com.promenar.infinity.data.document.external.ExternalSendResult
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareSessionChoice
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExternalAgentChoice(val id: String, val name: String)

/** 查看器需要的存储空间、会话与助手数据。 */
interface ExternalDocumentTargets {
    suspend fun knowledgeRootUuid(): String
    suspend fun folders(parentUuid: String): List<ShareKnowledgeFolder>
    suspend fun folderExists(uuid: String): Boolean
    suspend fun sessions(): List<ShareSessionChoice>
    suspend fun agents(): List<ExternalAgentChoice>
    /** 以该助手新建会话并返回其 id；失败时返回 null。 */
    suspend fun createSession(agentId: String): String?
}

/** 记住上次存入的文件夹层级（自根向下）。 */
interface ExternalFolderMemory {
    fun load(): List<ShareKnowledgeFolder>
    fun save(trail: List<ShareKnowledgeFolder>)
}

data class ExternalDocumentView(
    val name: String,
    val sizeBytes: Long,
    /** 正文分块；为 null 表示超过显示上限，不在应用内显示正文。 */
    val chunks: List<String>?,
    val attachmentAllowed: Boolean,
)

/** 已存入存储空间：[folderPath] 为存储空间内的文件夹路径，根目录为 "/"。 */
data class ExternalSavedLocation(val documentId: String, val folderPath: String)

sealed interface ExternalDocumentPanel {
    data class Folder(
        val trail: List<ShareKnowledgeFolder>,
        val folders: List<ShareKnowledgeFolder> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false,
    ) : ExternalDocumentPanel

    data class Send(
        val mode: ExternalSendMode,
        val attachmentAllowed: Boolean,
        val sessions: List<ShareSessionChoice> = emptyList(),
        val agents: List<ExternalAgentChoice> = emptyList(),
        /** 正在为“新建会话”选择助手。 */
        val pickingAgent: Boolean = false,
        val loading: Boolean = true,
        val failed: Boolean = false,
    ) : ExternalDocumentPanel
}

data class ExternalDocumentUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val document: ExternalDocumentView? = null,
    val failure: ExternalDocumentFailure? = null,
    val saved: ExternalSavedLocation? = null,
    val panel: ExternalDocumentPanel? = null,
    val busy: Boolean = false,
)

sealed interface ExternalDocumentNotice {
    data class Saved(val folderPath: String) : ExternalDocumentNotice
    data object SaveFailed : ExternalDocumentNotice
    data object SavedAs : ExternalDocumentNotice
    data object SaveAsFailed : ExternalDocumentNotice
    data object SendFailed : ExternalDocumentNotice
    data object CreateSessionFailed : ExternalDocumentNotice
}

sealed interface ExternalDocumentEvent {
    data class OpenSession(val sessionId: String) : ExternalDocumentEvent
    data class OpenLibraryDocument(val documentId: String) : ExternalDocumentEvent
    data class Notice(val notice: ExternalDocumentNotice) : ExternalDocumentEvent
}

/**
 * 外部 Markdown 查看器：暂存外部文件，显示正文，并提供另存为、存入存储空间与发送到会话。
 * 当前文档的暂存 id 记在 [savedState] 里，进程重建后从暂存区恢复。
 */
class ExternalDocumentViewModel(
    private val store: ExternalDocumentStore,
    private val librarySave: suspend (ExternalDocument, String, String) -> ExternalLibrarySaveResult,
    private val sender: ExternalDocumentSender,
    private val targets: ExternalDocumentTargets,
    private val folderMemory: ExternalFolderMemory,
    private val savedState: SavedStateHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ExternalDocumentUiState())
    val state: StateFlow<ExternalDocumentUiState> = _state.asStateFlow()

    private val eventChannel = Channel<ExternalDocumentEvent>(Channel.BUFFERED)
    val events: Flow<ExternalDocumentEvent> = eventChannel.receiveAsFlow()

    private var current: ExternalDocument? = null
    private var openJob: Job? = null
    private var actionJob: Job? = null

    init {
        savedState.get<String>(KEY_DOCUMENT_ID)?.let { id ->
            _state.value = ExternalDocumentUiState(visible = true, loading = true)
            openJob = viewModelScope.launch {
                val restored = store.restore(id)
                if (restored == null) {
                    savedState.remove<String>(KEY_DOCUMENT_ID)
                    _state.value = ExternalDocumentUiState()
                } else {
                    apply(restored)
                }
            }
        }
    }

    fun open(input: () -> ExternalDocumentInput) {
        openJob?.cancel()
        actionJob?.cancel()
        discardCurrent()
        _state.value = ExternalDocumentUiState(visible = true, loading = true)
        openJob = viewModelScope.launch { apply(store.stage(input)) }
    }

    fun close() {
        openJob?.cancel()
        actionJob?.cancel()
        discardCurrent()
        _state.value = ExternalDocumentUiState()
    }

    // ---- 存入存储空间 ----

    fun openFolderPanel() {
        if (current == null) return
        launchAction {
            val remembered = folderMemory.load()
            val trail = remembered.takeIf { it.isNotEmpty() && targets.folderExists(it.last().uuid) }.orEmpty()
            loadFolder(trail)
        }
    }

    fun enterFolder(folder: ShareKnowledgeFolder) {
        val panel = _state.value.panel as? ExternalDocumentPanel.Folder ?: return
        launchAction { loadFolder(panel.trail + folder) }
    }

    fun folderUp() {
        val panel = _state.value.panel as? ExternalDocumentPanel.Folder ?: return
        if (panel.trail.isEmpty()) return
        launchAction { loadFolder(panel.trail.dropLast(1)) }
    }

    fun confirmFolder() {
        val document = current ?: return
        val panel = _state.value.panel as? ExternalDocumentPanel.Folder ?: return
        if (panel.loading || panel.failed) return
        launchAction {
            _state.update { it.copy(busy = true) }
            val result = try {
                val root = targets.knowledgeRootUuid()
                librarySave(document, root, panel.trail.lastOrNull()?.uuid ?: root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExternalLibrarySaveResult.Failed(null)
            }
            when (result) {
                is ExternalLibrarySaveResult.Saved -> {
                    folderMemory.save(panel.trail)
                    val folderPath = panel.trail.lastOrNull()?.path ?: "/"
                    _state.update { it.copy(busy = false, panel = null, saved = ExternalSavedLocation(result.documentId, folderPath)) }
                    eventChannel.trySend(ExternalDocumentEvent.Notice(ExternalDocumentNotice.Saved(folderPath)))
                }
                is ExternalLibrarySaveResult.Failed -> {
                    _state.update { it.copy(busy = false) }
                    eventChannel.trySend(ExternalDocumentEvent.Notice(ExternalDocumentNotice.SaveFailed))
                }
            }
        }
    }

    fun openInLibrary() {
        val saved = _state.value.saved ?: return
        eventChannel.trySend(ExternalDocumentEvent.OpenLibraryDocument(saved.documentId))
        close()
    }

    // ---- 发送到会话 ----

    fun openSendPanel() {
        val document = current ?: return
        val allowed = ExternalSendPolicy.attachmentAllowed(document)
        val initial = ExternalDocumentPanel.Send(
            mode = if (allowed) ExternalSendMode.Attachment else ExternalSendMode.Reference,
            attachmentAllowed = allowed,
        )
        _state.update { it.copy(panel = initial) }
        launchAction {
            val loaded = try {
                initial.copy(sessions = targets.sessions(), agents = targets.agents(), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                initial.copy(loading = false, failed = true)
            }
            updateSendPanel { loaded.copy(mode = it.mode) }
        }
    }

    fun setSendMode(mode: ExternalSendMode) {
        updateSendPanel { panel ->
            if (mode == ExternalSendMode.Attachment && !panel.attachmentAllowed) panel else panel.copy(mode = mode)
        }
    }

    fun sendTo(sessionId: String) {
        val panel = _state.value.panel as? ExternalDocumentPanel.Send ?: return
        launchAction { send(sessionId, panel.mode) }
    }

    /** “新建会话”：只有一个助手时直接新建，多个时先选助手。 */
    fun startNewSession() {
        val panel = _state.value.panel as? ExternalDocumentPanel.Send ?: return
        when (panel.agents.size) {
            0 -> Unit
            1 -> createSessionAndSend(panel.agents.single().id)
            else -> updateSendPanel { it.copy(pickingAgent = true) }
        }
    }

    fun createSessionAndSend(agentId: String) {
        val panel = _state.value.panel as? ExternalDocumentPanel.Send ?: return
        launchAction {
            _state.update { it.copy(busy = true) }
            val sessionId = try {
                targets.createSession(agentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (sessionId == null) {
                _state.update { it.copy(busy = false) }
                eventChannel.trySend(ExternalDocumentEvent.Notice(ExternalDocumentNotice.CreateSessionFailed))
                return@launchAction
            }
            send(sessionId, panel.mode)
        }
    }

    // ---- 面板与另存为 ----

    /** 面板内的返回：先退出助手选择或回到上一级文件夹，已在顶层时关闭面板。 */
    fun panelBack() {
        when (val panel = _state.value.panel) {
            is ExternalDocumentPanel.Folder -> if (panel.trail.isNotEmpty()) folderUp() else dismissPanel()
            is ExternalDocumentPanel.Send -> if (panel.pickingAgent) updateSendPanel { it.copy(pickingAgent = false) } else dismissPanel()
            null -> Unit
        }
    }

    fun dismissPanel() {
        actionJob?.cancel()
        _state.update { it.copy(panel = null, busy = false) }
    }

    fun saveAs(openOutput: () -> OutputStream?) {
        val document = current ?: return
        viewModelScope.launch {
            val ok = try {
                withContext(io) {
                    val output = openOutput() ?: return@withContext false
                    output.use { sink -> document.file.inputStream().use { it.copyTo(sink) } }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            eventChannel.trySend(ExternalDocumentEvent.Notice(if (ok) ExternalDocumentNotice.SavedAs else ExternalDocumentNotice.SaveAsFailed))
        }
    }

    /** 另存为的建议文件名。 */
    fun suggestedName(): String? = current?.displayName

    private suspend fun apply(result: ExternalDocumentResult) {
        when (result) {
            is ExternalDocumentResult.Ready -> {
                val document = result.document
                current = document
                savedState[KEY_DOCUMENT_ID] = document.id
                val chunks = document.text?.let { text -> withContext(compute) { MarkdownChunks.split(text) } }
                _state.value = ExternalDocumentUiState(
                    visible = true,
                    document = ExternalDocumentView(
                        name = document.displayName,
                        sizeBytes = document.sizeBytes,
                        chunks = chunks,
                        attachmentAllowed = ExternalSendPolicy.attachmentAllowed(document),
                    ),
                )
            }
            is ExternalDocumentResult.Failed -> {
                current = null
                savedState.remove<String>(KEY_DOCUMENT_ID)
                _state.value = ExternalDocumentUiState(visible = true, failure = result.reason)
            }
        }
    }

    private suspend fun loadFolder(trail: List<ShareKnowledgeFolder>) {
        _state.update { it.copy(panel = ExternalDocumentPanel.Folder(trail)) }
        val loaded = try {
            val parent = trail.lastOrNull()?.uuid ?: targets.knowledgeRootUuid()
            ExternalDocumentPanel.Folder(trail, targets.folders(parent), loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExternalDocumentPanel.Folder(trail, loading = false, failed = true)
        }
        _state.update { if (it.panel is ExternalDocumentPanel.Folder) it.copy(panel = loaded) else it }
    }

    private suspend fun send(sessionId: String, mode: ExternalSendMode) {
        val document = current ?: return
        _state.update { it.copy(busy = true) }
        val result = try {
            sender.send(document, sessionId, mode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (result is ExternalSendResult.Sent) {
            eventChannel.trySend(ExternalDocumentEvent.OpenSession(result.sessionId))
            close()
        } else {
            _state.update { it.copy(busy = false) }
            eventChannel.trySend(ExternalDocumentEvent.Notice(ExternalDocumentNotice.SendFailed))
        }
    }

    private fun updateSendPanel(transform: (ExternalDocumentPanel.Send) -> ExternalDocumentPanel.Send) {
        _state.update { state ->
            val panel = state.panel as? ExternalDocumentPanel.Send ?: return@update state
            state.copy(panel = transform(panel))
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        actionJob?.cancel()
        actionJob = viewModelScope.launch { block() }
    }

    private fun discardCurrent() {
        // 未主动关闭的暂存副本（例如进程被回收）由保留期清理。
        current?.let { document -> viewModelScope.launch(io) { store.discard(document.id) } }
        current = null
        savedState.remove<String>(KEY_DOCUMENT_ID)
    }

    companion object {
        const val KEY_DOCUMENT_ID = "external_document_id"
    }
}
