package com.promenar.infinity.data.rag

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 对会话记忆写入登记、入队及片段删除建立同一短期互斥边界。 */
internal object ConversationMemoryWriteCoordinator {
    private class State(val mutex: Mutex = Mutex(), var activeWriters: Int = 0, var references: Int = 0)
    private val monitor = Any()
    private val states = mutableMapOf<String, State>()

    private fun acquire(sessionId: String): State = synchronized(monitor) {
        states.getOrPut(sessionId) { State() }.also { it.references++ }
    }

    private fun release(sessionId: String, state: State) = synchronized(monitor) {
        state.references--
        if (state.references == 0 && states[sessionId] === state) states.remove(sessionId)
        Unit
    }

    suspend fun <T> withDirectWriter(sessionId: String, block: suspend () -> T): T {
        val state = acquire(sessionId)
        try {
            state.mutex.withLock { state.activeWriters++ }
            try {
                return block()
            } finally {
                withContext(NonCancellable) { state.mutex.withLock { state.activeWriters-- } }
            }
        } finally {
            release(sessionId, state)
        }
    }

    suspend fun <T> serialized(sessionId: String, block: suspend (activeWriters: Int) -> T): T {
        val state = acquire(sessionId)
        try {
            return state.mutex.withLock { block(state.activeWriters) }
        } finally {
            release(sessionId, state)
        }
    }
}
