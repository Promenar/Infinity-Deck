package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.cacheUsageAggregate
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.minus
import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.domain.repository.sumCacheUsage
import kotlinx.serialization.Serializable

/** 一个实际请求的最新用量快照；同一请求的流内快照替换，恢复后的新请求使用新 id。 */
@Serializable
data class SubagentCacheRequestUsage(val id: String, val day: String, val usage: TokenUsage? = null) {
    /** null 表示请求已登记但尚无用量，不增加请求计数。 */
    val cache: CacheUsageAggregate get() = usage?.cacheUsageAggregate() ?: CacheUsageAggregate()
}

internal fun SubagentRun.legacyCache(day: String? = null): CacheUsageAggregate =
    (if (day == null) cache else dailyCacheUsage[day] ?: CacheUsageAggregate()) -
        cacheRequests.filter { day == null || it.day == day }.map { it.cache }.sumCacheUsage()

/** 重置时已开始的请求整体排除；该请求后续输入输出增量保留原水位口径，缓存按未知处理。 */
internal fun SubagentRun.cacheSince(watermark: SubagentUsageWatermark, day: String? = null): CacheUsageAggregate =
    (legacyCache(day) - watermark.cache) + cacheRequests
        .filter { (day == null || it.day == day) && it.id !in watermark.cacheRequestIds }
        .map { it.cache }.sumCacheUsage()

internal fun Iterable<Int?>.sumReportedCache(): Int? {
    val reported = filterNotNull()
    return if (reported.isEmpty()) null else reported.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
