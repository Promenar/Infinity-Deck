package com.promenar.infinity.data.remote

import io.ktor.utils.io.ByteReadChannel

/** 每次请求使用独立的单调时钟；日志只含阶段与耗时。 */
class ModelLatencyTrace(
    private val preparationStart: Long,
    private val clock: () -> Long,
    private val record: (String, Long) -> Unit,
) {
    private var dispatchAt: Long? = null
    private var byteSeen = false
    private var visibleSeen = false

    @Synchronized fun dispatched() {
        if (dispatchAt != null) return
        val now = clock()
        dispatchAt = now
        record("prepare_ms", (now - preparationStart).coerceAtLeast(0))
    }

    @Synchronized fun firstByte() {
        val start = dispatchAt ?: return
        if (byteSeen) return
        byteSeen = true
        record("first_byte_ms", (clock() - start).coerceAtLeast(0))
    }

    @Synchronized fun firstVisible() {
        val start = dispatchAt ?: return
        if (visibleSeen) return
        visibleSeen = true
        record("first_text_ms", (clock() - start).coerceAtLeast(0))
    }
}

/** 等待响应体中首字节可读，不消费或改变流。 */
internal suspend fun ByteReadChannel.observeFirstByte(trace: ModelLatencyTrace?) {
    if (trace == null) return
    awaitContent()
    if (availableForRead > 0) trace.firstByte()
}
