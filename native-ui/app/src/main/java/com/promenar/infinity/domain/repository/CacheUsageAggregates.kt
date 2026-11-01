package com.promenar.infinity.domain.repository

import kotlinx.serialization.Serializable


operator fun CacheUsageAggregate.plus(other: CacheUsageAggregate) = CacheUsageAggregate(
    requests + other.requests, reportedRequests + other.reportedRequests,
    reportedInputTokens + other.reportedInputTokens, cacheReadTokens + other.cacheReadTokens,
    cacheWriteTokens + other.cacheWriteTokens, cacheWriteLongTtlTokens + other.cacheWriteLongTtlTokens,
    anomalousRequests + other.anomalousRequests,
)

/** 重置后的消费增量；旧水位没有缓存字段时按零处理。 */
operator fun CacheUsageAggregate.minus(other: CacheUsageAggregate) = CacheUsageAggregate(
    (requests - other.requests).coerceAtLeast(0), (reportedRequests - other.reportedRequests).coerceAtLeast(0),
    (reportedInputTokens - other.reportedInputTokens).coerceAtLeast(0), (cacheReadTokens - other.cacheReadTokens).coerceAtLeast(0),
    (cacheWriteTokens - other.cacheWriteTokens).coerceAtLeast(0), (cacheWriteLongTtlTokens - other.cacheWriteLongTtlTokens).coerceAtLeast(0),
    (anomalousRequests - other.anomalousRequests).coerceAtLeast(0),
)

fun Iterable<CacheUsageAggregate>.sumCacheUsage(): CacheUsageAggregate = fold(CacheUsageAggregate()) { sum, usage -> sum + usage }
