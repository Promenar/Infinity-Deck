package com.promenar.infinity.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.domain.library.AskErrorCode
import com.promenar.infinity.domain.library.AskEvent
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.AskScope
import com.promenar.infinity.domain.library.LibraryAskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 问资料所处阶段：输入中只显示关键词结果；按搜索键后依次为检索、作答、完成。 */
internal enum class AskPhase { TYPING, RETRIEVING, ANSWERING, DONE, FAILED }

internal data class AskUiState(
    val query: String = "",
    val scope: AskScope = AskScope.AllIndexed,
    val phase: AskPhase = AskPhase.TYPING,
    /** 输入中的本机关键词结果。 */
    val keywordHits: List<AskHit> = emptyList(),
    /** 按搜索键时的问题；输入改动后回到输入中，但保留它以便“重新生成”。 */
    val askedQuery: String? = null,
    val hits: List<AskHit> = emptyList(),
    val mode: AskMode? = null,
    val answer: String = "",
    val citedHitIndexes: List<Int> = emptyList(),
    val modelLabel: String? = null,
    val noAnswer: Boolean = false,
    val error: AskErrorCode? = null,
    /** 结果筛选：null 为全部。 */
    val filter: AskHitKind? = null,
    /** 点答案里的编号后选中的结果下标。 */
    val selectedHit: Int? = null,
) {
    /** 当前列出的结果：输入中为关键词结果，其余为检索结果。 */
    val listedHits: List<AskHit> get() = if (phase == AskPhase.TYPING) keywordHits else hits

    /** 某篇文档在当前检索结果里的命中，用于文档页定位。 */
    fun hitsForDocument(documentId: String): List<AskHit> =
        hits.filter { it.kind == AskHitKind.DOCUMENT && it.documentId == documentId }
}

/** 把服务事件归并进界面状态；纯函数，便于单测。 */
internal fun AskUiState.reduce(event: AskEvent): AskUiState = when (event) {
    AskEvent.Retrieving -> copy(
        phase = AskPhase.RETRIEVING, hits = emptyList(), mode = null, answer = "", citedHitIndexes = emptyList(),
        modelLabel = null, noAnswer = false, error = null, filter = null, selectedHit = null,
    )
    is AskEvent.Hits -> copy(
        hits = event.hits,
        mode = event.mode,
        phase = if (event.mode == AskMode.FULL && event.hits.isNotEmpty()) AskPhase.ANSWERING else AskPhase.DONE,
    )
    is AskEvent.AnswerDelta -> copy(answer = answer + event.text)
    is AskEvent.AnswerDone -> copy(
        phase = AskPhase.DONE,
        answer = event.text,
        citedHitIndexes = event.citedHitIndexes.filter { it in hits.indices },
        modelLabel = event.modelLabel,
        noAnswer = event.noAnswer,
    )
    is AskEvent.Failed -> if (event.code == AskErrorCode.CANCELLED) this else copy(phase = AskPhase.FAILED, error = event.code)
}

/**
 * 问资料页的状态：输入时 250ms 防抖做本机关键词匹配；按搜索键调用 [LibraryAskService.ask]，
 * 同一时刻只保留最新一次提问，改动输入即取消正在进行的提问。
 */
class LibraryAskViewModel(private val service: LibraryAskService) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    internal val state: StateFlow<AskUiState> = _state.asStateFlow()

    /** 当前设置下按搜索键会走的模式；输入中提示与降级说明据此显示。 */
    val mode: StateFlow<AskMode?> = service.observeMode()
        .catch { emit(AskMode.KEYWORD_ONLY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private var keywordJob: Job? = null
    private var askJob: Job? = null

    fun onQueryChange(text: String) {
        askJob?.cancel()
        keywordJob?.cancel()
        _state.update { it.copy(query = text, phase = AskPhase.TYPING, selectedHit = null, keywordHits = if (text.isBlank()) emptyList() else it.keywordHits) }
        if (text.isBlank()) return
        val scope = _state.value.scope
        keywordJob = viewModelScope.launch {
            delay(KEYWORD_DEBOUNCE_MS)
            val hits = runCatchingCancellable { service.keywordSearch(text.trim(), scope) } ?: emptyList()
            _state.update { if (it.query == text) it.copy(keywordHits = hits) else it }
        }
    }

    /** 键盘搜索键：语义检索并作答。 */
    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        ask(query)
    }

    /** 答案卡“重新生成”：用上次的问题再问一次。 */
    fun regenerate() {
        val query = _state.value.askedQuery ?: return
        ask(query)
    }

    private fun ask(query: String) {
        keywordJob?.cancel()
        askJob?.cancel()
        val scope = _state.value.scope
        _state.update { it.copy(askedQuery = query).reduce(AskEvent.Retrieving) }
        askJob = viewModelScope.launch {
            try {
                service.ask(query, scope).collect { event -> _state.update { it.reduce(event) } }
                // 流结束仍停在检索或作答，视为没有答案。
                _state.update { if (it.phase == AskPhase.RETRIEVING || it.phase == AskPhase.ANSWERING) it.copy(phase = AskPhase.DONE) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.reduce(AskEvent.Failed(AskErrorCode.UNKNOWN, null)) }
            }
        }
    }

    fun setScope(scope: AskScope) {
        if (_state.value.scope == scope) return
        _state.update { it.copy(scope = scope) }
        val current = _state.value
        if (current.phase == AskPhase.TYPING) onQueryChange(current.query) else current.askedQuery?.let(::ask)
    }

    fun setFilter(kind: AskHitKind?) {
        _state.update { it.copy(filter = kind) }
    }

    /** 点答案编号：清除筛选并选中对应结果。 */
    fun selectHit(index: Int?) {
        _state.update { it.copy(selectedHit = index, filter = if (index != null) null else it.filter) }
    }

    fun clear() {
        keywordJob?.cancel()
        askJob?.cancel()
        _state.update { AskUiState(scope = it.scope) }
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
        internal const val KEYWORD_DEBOUNCE_MS = 250L

        fun factory(application: InfinityApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryAskViewModel(application.libraryAskService) as T
        }
    }
}
