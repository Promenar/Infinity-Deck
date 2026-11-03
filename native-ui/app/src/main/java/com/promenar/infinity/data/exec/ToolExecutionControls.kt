package com.promenar.infinity.data.exec

import com.promenar.infinity.data.chat.queue.ComposerQueueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 队列的持久状态同时承担唤醒信号，登记等待前已经到达的插队也可见。 */
class SteeringContext(val queue: StateFlow<ComposerQueueState>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SteeringContext>
    fun pending(): Boolean = queue.value.let { it.pauseReason == null && it.items.any { item -> item.steering } }
    suspend fun await() { queue.first { it.pauseReason == null && it.items.any { item -> item.steering } } }
}

class ToolCallControlContext(val sessionId: String, val toolCallId: String, val stepId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolCallControlContext>
}

/** 只保留当前调用的控制租约；旧调用的退出不会移除同键的新租约。 */
object ToolExecutionControls {
    enum class Kind { WAITING, FOREGROUND }
    data class State(val sessionId: String, val toolCallId: String, val stepId: String, val kind: Kind,
        val canCheckNow: Boolean, val canMoveToBackground: Boolean)
    private data class Entry(val state: State, val release: CompletableDeferred<Unit>, val background: (() -> Unit)?)
    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, String>, Entry>()
    private val states = MutableStateFlow<Map<Pair<String, String>, State>>(emptyMap())
    fun observe(sessionId: String, toolCallId: String): Flow<State?> = states.map { it[sessionId to toolCallId] }.distinctUntilChanged()
    fun snapshot(sessionId: String, toolCallId: String): State? = states.value[sessionId to toolCallId]
    fun checkNow(sessionId: String, toolCallId: String): Boolean = synchronized(lock) {
        entries[sessionId to toolCallId]?.takeIf { it.state.canCheckNow }?.let { it.release.complete(Unit) } ?: false
    }
    fun moveToBackground(sessionId: String, toolCallId: String): Boolean = synchronized(lock) {
        entries[sessionId to toolCallId]?.background?.let { it(); true } ?: false
    }
    suspend fun <T> controlled(kind: Kind, background: (() -> Unit)? = null, block: suspend (Deferred<Unit>) -> T): T {
        val context = currentCoroutineContext()[ToolCallControlContext]
        val release = CompletableDeferred<Unit>()
        val key = context?.let { it.sessionId to it.toolCallId }
        val entry = context?.let { Entry(State(it.sessionId, it.toolCallId, it.stepId, kind, kind == Kind.WAITING, background != null), release, background) }
        if (key != null && entry != null) synchronized(lock) { entries[key] = entry; states.value = entries.mapValues { it.value.state } }
        try { return block(release) }
        finally { if (key != null && entry != null) synchronized(lock) {
            if (entries[key] === entry) { entries.remove(key); states.value = entries.mapValues { it.value.state } }
        } }
    }
}
