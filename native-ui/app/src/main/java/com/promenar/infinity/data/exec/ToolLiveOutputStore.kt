package com.promenar.infinity.data.exec

import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行中工具的实时输出，只存内存、不写数据库。按工具步骤 id 登记，命令结束即移除；
 * 每个命令保留尾部 [limit] 个字符，界面按 [throttleMillis] 节流读取，快照在 [dispatcher] 上生成。
 * 每次工具调用都会登记通道，但只有执行方实际使用过（输出、登记停止或声明不支持）的通道才对界面可见。
 */
class ToolLiveOutputStore(
    private val limit: Int = LIMIT_CHARS,
    private val throttleMillis: Long = THROTTLE_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Snapshot(
        val text: String,
        /** 开头已有输出因超出保留上限被丢弃。 */
        val truncated: Boolean,
        val stoppable: Boolean,
        val stopRequested: Boolean,
        val unsupported: Boolean,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val outputListeners = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>>()
    fun listenOutput(key: String, listener: (String) -> Unit): () -> Unit {
        outputListeners.compute(key) { _, existing ->
            (existing ?: java.util.concurrent.CopyOnWriteArrayList()).also { it.add(listener) }
        }
        return { outputListeners.computeIfPresent(key) { _, existing -> existing.apply { remove(listener) }.takeIf { it.isNotEmpty() } }; Unit }
    }
    internal fun publishOutput(key: String, text: String) = output(key, text)
    private fun output(key: String, text: String) {
        if (text.isNotEmpty()) outputListeners[key]?.forEach { it(text) }
    }
    private val version = MutableStateFlow(0L)
    private val _activeKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 执行方已使用的通道 key。审批通过后的工具由审批流程直接执行、不在生成任务内，
     * 界面据此仍把这些调用显示为运行中。
     */
    val activeKeys: StateFlow<Set<String>> = _activeKeys.asStateFlow()

    /** 为一次工具调用打开通道；同一 key 再次打开时替换旧通道。 */
    fun open(key: String, inferOutputEvents: Boolean = true): ToolLiveOutputSink = Entry(key, inferOutputEvents).also {
        entries[key] = it
        _activeKeys.update { keys -> keys - key }
        bump()
    }

    /** 只移除 [sink] 本身登记的通道，避免旧调用的收尾移除新调用。 */
    fun close(key: String, sink: ToolLiveOutputSink) {
        if (sink is ToolLiveOutputStore.Entry && sink.deferClose()) return
        if (entries.remove(key, sink)) {
            _activeKeys.update { keys -> keys - key }
            bump()
        }
    }

    fun snapshot(key: String): Snapshot? = entries[key]?.takeIf { it.active }?.snapshot()

    /** 当前登记的 key，供测试核对登记位置。 */
    internal fun keys(): Set<String> = entries.keys.toSet()

    fun requestStop(key: String) {
        entries[key]?.requestStop()
    }

    // StateFlow 本身合并更新：节流等待期间的多次变化只取最新一次。
    fun observe(key: String): Flow<Snapshot?> = version
        .transform { emit(it); delay(throttleMillis) }
        .map { snapshot(key) }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    private fun bump() = version.update { it + 1 }

    private inner class Entry(private val key: String, private val inferOutputEvents: Boolean) : ToolLiveOutputSink {
        private val buffer = StringBuilder()
        private var dropped = false
        // 增量游标独立于界面的 16 KiB 尾部；终端快照可保留更多文字。
        private var sourceSnapshot = ""
        private var retainCount = 0
        private var closePending = false
        @Synchronized fun deferClose(): Boolean {
            if (retainCount == 0) return false
            closePending = true
            return true
        }
        override fun retain(): () -> Unit {
            synchronized(this) { retainCount++ }
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            return {
                if (released.compareAndSet(false, true)) {
                    val closeNow = synchronized(this) { retainCount--; retainCount == 0 && closePending }
                    if (closeNow) close(key, this)
                }
            }
        }
        override fun replace(stream: ToolOutputStream, text: String) {
            synchronized(this) {
                markActive()
                if (inferOutputEvents) {
                    val delta = newOutputSuffix(sourceSnapshot, text)
                    sourceSnapshot = text
                    output(key, delta)
                }
                buffer.clear()
                val start = (text.length - limit).coerceAtLeast(0).let { if (it < text.length && text[it].isLowSurrogate()) it + 1 else it }
                buffer.append(text.substring(start))
                dropped = text.length > limit
            }
            bump()
        }
        @Volatile private var stop: (() -> Unit)? = null
        @Volatile private var unsupportedBackend = false
        @Volatile var active = false
            private set
        @Volatile override var stopRequested: Boolean = false
            private set

        override fun emit(stream: ToolOutputStream, text: String) {
            if (text.isEmpty()) return
            synchronized(this) {
                markActive()
                if (inferOutputEvents) output(key, text)
                if (inferOutputEvents) sourceSnapshot = (sourceSnapshot + text).takeLast(32_000)
                buffer.append(text)
                if (buffer.length > limit) {
                    var start = buffer.length - limit
                    // 不从代理对中间切开。
                    if (Character.isLowSurrogate(buffer[start])) start++
                    buffer.delete(0, start)
                    dropped = true
                }
            }
            bump()
        }

        override fun onStop(stop: () -> Unit) {
            this.stop = stop
            markActive()
            bump()
            if (stopRequested) stop()
        }

        override fun unsupported() {
            unsupportedBackend = true
            markActive()
            bump()
        }

        private fun markActive() {
            if (active) return
            active = true
            // 已被关闭或替换的通道不再登记。
            if (entries[key] === this) _activeKeys.update { keys -> keys + key }
        }

        fun requestStop() {
            if (stopRequested) return
            stopRequested = true
            bump()
            stop?.invoke()
        }

        @Synchronized
        fun snapshot() = Snapshot(buffer.toString(), dropped, stop != null, stopRequested, unsupportedBackend)
    }

    companion object {
        const val LIMIT_CHARS = 16 * 1024
        const val THROTTLE_MILLIS = 100L
    }
}

/** 快照裁尾后寻找旧尾与新首的最长重叠，已展示的文本不重新作为新输出。 */
internal fun newOutputSuffix(previous: String, current: String): String {
    if (current.startsWith(previous)) return current.drop(previous.length)
    if (previous.isEmpty() || current.isEmpty()) return current
    val prefix = IntArray(current.length)
    for (index in 1 until current.length) {
        var count = prefix[index - 1]
        while (count > 0 && current[index] != current[count]) count = prefix[count - 1]
        if (current[index] == current[count]) count++
        prefix[index] = count
    }
    var overlap = 0
    for (char in previous) {
        while (overlap > 0 && (overlap == current.length || current[overlap] != char)) overlap = prefix[overlap - 1]
        if (overlap < current.length && current[overlap] == char) overlap++
    }
    return current.drop(overlap)
}
