package com.promenar.infinity.data.approval

import com.promenar.infinity.domain.tool.ApprovalGrantCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 仅在当前进程保存；定时移除到期项，查询时也清理已过期的授权。 */
class TimedApprovalGrantStore(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val sessions = mutableMapOf<String, MutableStateFlow<Map<ApprovalGrantCategory, Long>>>()
    private var expiryJob: Job? = null

    fun grant(sessionId: String, category: ApprovalGrantCategory): Long = synchronized(lock) {
        clearExpiredLocked()
        val expiresAt = clock() + GRANT_DURATION_MILLIS
        val state = stateLocked(sessionId)
        state.value = state.value + (category to expiresAt)
        scheduleExpiryLocked()
        expiresAt
    }

    fun isValid(sessionId: String, category: ApprovalGrantCategory): Boolean = synchronized(lock) {
        clearExpiredLocked()
        sessions[sessionId]?.value?.containsKey(category) == true
    }

    fun revokeAll(sessionId: String) = synchronized(lock) {
        sessions[sessionId]?.value = emptyMap()
        scheduleExpiryLocked()
    }

    fun observe(sessionId: String): StateFlow<Map<ApprovalGrantCategory, Long>> = synchronized(lock) {
        clearExpiredLocked()
        stateLocked(sessionId).asStateFlow()
    }

    private fun stateLocked(sessionId: String) = sessions.getOrPut(sessionId) { MutableStateFlow(emptyMap()) }

    private fun clearExpiredLocked() {
        val now = clock()
        sessions.values.forEach { state -> state.value = state.value.filterValues { it > now } }
    }

    private fun scheduleExpiryLocked() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (true) {
                val remaining = synchronized(lock) {
                    clearExpiredLocked()
                    sessions.values.flatMap { it.value.values }.minOrNull()?.let { it - clock() }
                } ?: return@launch
                delay(remaining.coerceAtLeast(1L))
            }
        }
    }

    companion object {
        const val GRANT_DURATION_MILLIS = 10 * 60 * 1_000L
    }
}
