package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

data class ChatState(
    val sessions: List<Session> = emptyList(),
    val currentGeneratingSessionId: String? = null,
    val activeKGExtractions: Set<String> = emptySet(),
    internal val sessionCatalogVersion: Long = 0,
)

class ChatStore {
    val turnAdmission = com.promenar.infinity.data.generation.GenerationTurnAdmission()
    // 界面与应用级运行器按会话共享插入锁，确保同会话同毫秒消息按 Room 写入顺序同步且不阻塞其它会话。
    private val messageInsertions = ConcurrentHashMap<String, Mutex>()
    internal fun messageInsertionLock(sessionId: String): Mutex =
        messageInsertions.computeIfAbsent(sessionId) { Mutex() }
    // 界面与应用级运行器各自持有 MessageManager，共享同一会话的编辑快照。
    internal val pendingUserEditSnapshots = ConcurrentHashMap<String, Message>()
    private val loading = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    val current: ChatState get() = _state.value

    fun get(): ChatState = _state.value

    fun update(transform: (ChatState) -> ChatState) {
        while (true) {
            val current = _state.value
            val transformed = transform(current)
            val nextVersion = if (sameSessionMembership(current.sessions, transformed.sessions)) {
                current.sessionCatalogVersion
            } else {
                current.sessionCatalogVersion + 1
            }
            val next = transformed.copy(sessionCatalogVersion = nextVersion)
            if (_state.compareAndSet(current, next)) return
        }
    }

    fun replaceSessionsIfCatalogUnchanged(expectedVersion: Long, replacement: List<Session>): Boolean {
        val current = _state.value
        if (current.sessionCatalogVersion != expectedVersion) return false
        val currentById = current.sessions.associateBy { it.id }
        val merged = replacement.map { persisted -> currentById[persisted.id] ?: persisted }
        val nextVersion = if (sameSessionMembership(current.sessions, merged)) {
            current.sessionCatalogVersion
        } else {
            current.sessionCatalogVersion + 1
        }
        return _state.compareAndSet(
            current,
            current.copy(sessions = merged, sessionCatalogVersion = nextVersion),
        )
    }

    fun upsertSession(session: Session, prepend: Boolean = true) {
        update { state ->
            val remaining = state.sessions.filterNot { it.id == session.id }
            state.copy(sessions = if (prepend) listOf(session) + remaining else remaining + session)
        }
    }

    fun removeSession(sessionId: String) {
        update { state -> state.copy(sessions = state.sessions.filterNot { it.id == sessionId }) }
    }

    private fun sameSessionMembership(left: List<Session>, right: List<Session>): Boolean =
        left.size == right.size && left.mapTo(mutableSetOf()) { it.id } == right.mapTo(mutableSetOf()) { it.id }

    fun getSession(id: String): Session? {
        return _state.value.sessions.find { it.id == id }
    }

    /** 同会话载入去重，不同会话独立；已有内存消息优先于较早的数据库快照。 */
    suspend fun getOrLoadSession(id: String, load: suspend () -> Session?): Session? {
        getSession(id)?.let { return it }
        val lock = loading.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            getSession(id)?.let { return it }
            val loaded = load() ?: return null
            update { state ->
                if (state.sessions.any { it.id == id }) state else state.copy(sessions = state.sessions + loaded)
            }
            return getSession(id)
        } finally { lock.unlock() }
    }

    fun updateSession(id: String, transform: (Session) -> Session) {
        update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == id) transform(s) else s
                }
            )
        }
    }

    fun updateMessageInSession(
        sessionId: String,
        messageId: String,
        transform: (com.promenar.infinity.data.model.Message) -> com.promenar.infinity.data.model.Message
    ) {
        update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == messageId) transform(m) else m
                            }
                        )
                    } else s
                }
            )
        }
    }

    fun clear() {
        pendingUserEditSnapshots.clear()
        update { ChatState() }
    }
}
