package com.promenar.infinity.data.repository

import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Linux 命令不占扫描锁；只在索引提交阶段与命令开始/结束互斥。 */
internal object WorkspaceExternalMutationCoordinator {
    private class RootState {
        val commit = Mutex()
        val active = MutableStateFlow(0)
        var epoch = 0L
    }
    private val roots = java.util.concurrent.ConcurrentHashMap<String, RootState>()
    private fun state(root: Path) = roots.computeIfAbsent(root.toFile().canonicalPath) { RootState() }

    suspend fun begin(root: Path): suspend () -> Unit {
        val state = state(root)
        state.commit.withLock { state.active.value++; state.epoch++ }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return {
            withContext(NonCancellable) {
                if (closed.compareAndSet(false, true)) state.commit.withLock { state.active.value--; state.epoch++ }
            }
        }
    }

    suspend fun awaitIdle(root: Path) { state(root).active.first { it == 0 } }

    suspend fun scanEpoch(root: Path): Long = state(root).let { state ->
        state.commit.withLock {
            if (state.active.value != 0) throw WorkspaceScanInvalidatedException()
            state.epoch
        }
    }

    suspend fun <T> indexedMutation(root: Path, block: suspend () -> T): T = state(root).let { state ->
        state.commit.withLock {
            if (state.active.value != 0) throw WorkspaceScanInvalidatedException()
            block()
        }
    }

    suspend fun <T> commit(root: Path, epoch: Long, block: suspend () -> T): T = state(root).let { state ->
        state.commit.withLock {
            if (state.active.value != 0 || state.epoch != epoch) throw WorkspaceScanInvalidatedException()
            block()
        }
    }

}

internal class WorkspaceScanInvalidatedException : IllegalStateException("扫描期间发生 Linux 写入，需要重新扫描")
