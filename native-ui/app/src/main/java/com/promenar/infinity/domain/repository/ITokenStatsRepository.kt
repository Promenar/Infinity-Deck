package com.promenar.infinity.domain.repository

import kotlinx.serialization.Serializable

/** 主模型缓存用量；只统计聊天消息，摘要、知识图谱等后台模型不计入。 */
@Serializable
data class CacheUsageAggregate(
    /** 有用量记录的主模型请求数。 */
    val requests: Long = 0,
    /** 报告了缓存读取字段且数值正常（0 ≤ R ≤ 主模型输入）的请求数。 */
    val reportedRequests: Long = 0,
    /** 上述已报告请求的主模型输入合计（命中率分母）。 */
    val reportedInputTokens: Long = 0,
    /** 上述已报告请求的缓存读取合计（命中率分子）。 */
    val cacheReadTokens: Long = 0,
    /** 报告了缓存写入的请求的写入合计。 */
    val cacheWriteTokens: Long = 0,
    /** 其中长 TTL 档写入合计。 */
    val cacheWriteLongTtlTokens: Long = 0,
    /** 缓存读取大于主模型输入的异常请求数，不计入已报告。 */
    val anomalousRequests: Long = 0,
) {
    /** 命中率 0..1；没有已报告请求或分母为 0 时为 null。 */
    val hitRate: Double? get() = if (reportedRequests > 0 && reportedInputTokens > 0) cacheReadTokens.toDouble() / reportedInputTokens else null
    /** 报告覆盖率 0..1；没有请求时为 null。 */
    val coverage: Double? get() = if (requests > 0) reportedRequests.toDouble() / requests else null
}


data class TokenUsageAggregate(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimated: Boolean = false,
    val cache: CacheUsageAggregate = CacheUsageAggregate(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class ModelTokenStats(
    val modelId: String,
    val usage: TokenUsageAggregate
)

data class CategoryTokenStats(
    val category: String,
    val usage: TokenUsageAggregate,
    val calls: Long = 0,
)

data class SessionTokenUsage(
    val sessionId: String,
    val title: String?,
    val inputTokens: Long,
    val outputTokens: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class DailyTokenStats(
    val day: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cache: CacheUsageAggregate = CacheUsageAggregate(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

interface ITokenStatsRepository {
    suspend fun getTotalUsage(): TokenUsageAggregate
    suspend fun getUsageByModel(): List<ModelTokenStats>
    suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate
    suspend fun getTopSessions(limit: Int): List<SessionTokenUsage>
    suspend fun getDailyTrend(days: Int): List<DailyTokenStats>
    suspend fun getUsageByCategory(): List<CategoryTokenStats> =
        listOf(CategoryTokenStats("chat", getTotalUsage()))
    suspend fun resetStats()
}
