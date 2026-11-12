package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*

/** 同一 ComfyUI 主机共用一个 binding，providerId 是并发资源组；缺省串行。 */
data class MediaBackendBinding(
    val id: String,
    val providerId: String,
    val modelId: (MediaChannel) -> String,
    val backend: MediaGenerationBackend,
    /** 动态来源在入队时解析资源组，值随任务持久化。 */
    val providerFor: suspend (MediaChannel) -> String = { providerId },
    val configurationFor: suspend (MediaChannel) -> String? = { null },
    val expectedOutputsFor: suspend (MediaChannel) -> Int = { 1 },
) {
    init { require(id.isNotBlank() && providerId.isNotBlank()) }
}

data class MediaQueueConcurrency(
    val maxTotal: Int = 3,
    val defaultProvider: Int = 1,
    val defaultChannel: Int = 1,
    val providerLimits: Map<String, Int> = emptyMap(),
    val channelLimits: Map<String, Int> = emptyMap(),
) {
    init { require((listOf(maxTotal, defaultProvider, defaultChannel) + providerLimits.values + channelLimits.values).all { it in 1..8 }) }
}

data class MediaPollingPolicy(
    val initialMillis: Long = 2_000,
    val maximumMillis: Long = 30_000,
    val maxConsecutiveErrors: Int = 5,
) {
    init { require(initialMillis > 0 && maximumMillis >= initialMillis && maxConsecutiveErrors > 0) }
    fun delayAfter(attempt: Int): Long {
        require(attempt >= 0)
        var value = initialMillis
        repeat(attempt.coerceAtMost(63)) { value = if (value >= maximumMillis - value) maximumMillis else value * 2 }
        return value.coerceAtMost(maximumMillis)
    }
}

class InvalidMediaRequestException(val validation: RequestValidation) : IllegalArgumentException("INVALID_MEDIA_REQUEST")

/** 文案由 Android 装配点从资源提供；异常原文不进入任务表，避免携带请求头或带鉴权的 URL。 */
fun interface MediaQueueMessages {
    fun message(failure: MediaTaskFailure): String
}

/** 制作规划读取随入队冻结的候选容量占用。 */
interface MediaOutputReservationReader {
    suspend fun outputReservations(taskIds: Set<String>): Map<String, Int>
}
