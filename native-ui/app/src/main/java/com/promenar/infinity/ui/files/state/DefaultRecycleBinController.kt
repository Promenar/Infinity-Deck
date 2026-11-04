package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.RecycledItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultRecycleBinController(
    private val scope: CoroutineScope,
    private val services: FileManagerServices,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RecycleBinController {
    private val operationLock = Any()
    private val mutableState = MutableStateFlow(RecycleBinUiState())
    override val state: StateFlow<RecycleBinUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            services.operations.observeRecycleBin().flowOn(io).collect { items ->
                mutableState.update { it.copy(items = items.sortedByDescending(RecycledItem::deletedAt)) }
            }
        }
    }

    private fun runOperation(kind: FileManagerNotice.Kind, total: Int, action: suspend () -> FileManagerNotice) {
        synchronized(operationLock) {
            if (state.value.running) return
            mutableState.update { it.copy(running = true) }
        }
        scope.launch {
            try {
                val notice = withContext(io) { action() }
                mutableState.update { it.copy(notice = notice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(notice = FileManagerNotice.Failed(kind, error.fileCode(), total, total)) }
            } finally {
                synchronized(operationLock) { mutableState.update { it.copy(running = false) } }
            }
        }
    }

    override fun restore(items: List<RecycledItem>) {
        val ids = items.map { it.id }.distinct()
        if (ids.isEmpty()) return
        runOperation(FileManagerNotice.Kind.RESTORE, ids.size) {
            services.operations.restore(ids).toNotice(FileManagerNotice.Kind.RESTORE, ids.size)
        }
    }

    override fun purge(items: List<RecycledItem>) {
        val ids = items.map { it.id }.distinct()
        if (ids.isEmpty()) return
        runOperation(FileManagerNotice.Kind.PURGE, ids.size) {
            services.operations.purge(ids)
            FileManagerNotice.Done(FileManagerNotice.Kind.PURGE, ids.size)
        }
    }

    override fun emptyAll() {
        val count = state.value.items.size
        runOperation(FileManagerNotice.Kind.PURGE, count) {
            services.operations.emptyRecycleBin()
            FileManagerNotice.Done(FileManagerNotice.Kind.PURGE, count)
        }
    }

    override fun consumeNotice() { mutableState.update { it.copy(notice = null) } }
}
