package com.promenar.infinity.data.model

import com.promenar.infinity.domain.repository.CacheUsageAggregate

/** 只传入主模型的一次请求；摘要及子代理并入消息的输入不进入分母。 */
fun TokenUsage.cacheUsageAggregate(): CacheUsageAggregate {
    val mainInput = input.toLong() - summaryInput
    val read = cacheReadInput?.toLong()
    val reported = read != null && read >= 0 && read <= mainInput
    return CacheUsageAggregate(
        requests = 1,
        reportedRequests = if (reported) 1 else 0,
        reportedInputTokens = if (reported) mainInput else 0,
        cacheReadTokens = if (reported) requireNotNull(read) else 0,
        cacheWriteTokens = cacheWriteInput?.toLong()?.coerceAtLeast(0) ?: 0,
        cacheWriteLongTtlTokens = cacheWriteLongTtlInput?.toLong()?.coerceAtLeast(0) ?: 0,
        anomalousRequests = if (read != null && read > mainInput) 1 else 0,
    )
}
