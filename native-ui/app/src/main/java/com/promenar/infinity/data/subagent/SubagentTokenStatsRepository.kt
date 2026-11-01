package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.domain.repository.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 子代理独立累积用量不经助手消息 drain，后台完成、恢复及通知重试均只计算一次。 */
class SubagentTokenStatsRepository(
    private val base: ITokenStatsRepository,
    private val store: SubagentRunStore,
    private val title: suspend (String) -> String? = { null },
) : ITokenStatsRepository {
    private val lock = Mutex()
    private data class Record(val sessionId: String, val run: SubagentRun, val usage: TokenUsageAggregate)
    private suspend fun records(): List<Record> {
        val baseline = store.usageWatermarks()
        return store.all().flatMap { batch -> batch.runs.filter { it.id.isNotEmpty() }.map { run ->
            val reset = baseline[run.id] ?: SubagentUsageWatermark()
            Record(batch.sessionId, run, TokenUsageAggregate((run.inputTokens - reset.input).coerceAtLeast(0).toLong(),
                (run.outputTokens - reset.output).coerceAtLeast(0).toLong(), run.tokensEstimated, cache = run.cacheSince(reset)))
        } }
    }
    override suspend fun getTotalUsage() = lock.withLock { base.getTotalUsage() + records().map { it.usage }.sumUsage() }
    override suspend fun getUsageBySession(sessionId: String) = lock.withLock {
        base.getUsageBySession(sessionId) + records().filter { it.sessionId == sessionId }.map { it.usage }.sumUsage()
    }
    override suspend fun getUsageByModel() = lock.withLock {
        (base.getUsageByModel() + records().map { ModelTokenStats(it.run.modelId, it.usage) }).groupBy { it.modelId }
            .map { (id, values) -> ModelTokenStats(id, values.map { it.usage }.sumUsage()) }
    }
    override suspend fun getTopSessions(limit: Int) = lock.withLock {
        val all = base.getTopSessions(-1) + records().map { SessionTokenUsage(it.sessionId, title(it.sessionId), it.usage.inputTokens, it.usage.outputTokens) }
        val ranked = all.groupBy { it.sessionId }.map { (id, values) -> SessionTokenUsage(id, values.firstNotNullOfOrNull { it.title }, values.sumOf { it.inputTokens }, values.sumOf { it.outputTokens }) }
            .sortedByDescending { it.totalTokens }
        if (limit < 0) ranked else ranked.take(limit)
    }
    override suspend fun getDailyTrend(days: Int) = lock.withLock {
        val since = System.currentTimeMillis() - days.toLong() * 86_400_000
        val baseline = store.usageWatermarks()
        val firstDay = java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val extra = records().flatMap { record -> (record.run.dailyUsage.keys + record.run.dailyCacheUsage.keys).filter { it >= firstDay }.map { day ->
            val usage = record.run.dailyUsage[day] ?: TokenUsage()
            val reset = baseline["${record.run.id}/$day"] ?: SubagentUsageWatermark()
            DailyTokenStats(day, (usage.input - reset.input).coerceAtLeast(0).toLong(), (usage.output - reset.output).coerceAtLeast(0).toLong(), cache = record.run.cacheSince(reset, day))
        } }
        (base.getDailyTrend(days) + extra).groupBy { it.day }.map { (day, values) -> DailyTokenStats(day, values.sumOf { it.inputTokens }, values.sumOf { it.outputTokens }, values.map { it.cache }.sumCacheUsage()) }.sortedBy { it.day }
    }
    override suspend fun getUsageByCategory() = lock.withLock {
        val records = records()
        base.getUsageByCategory() + CategoryTokenStats("subagent", records.map { it.usage }.sumUsage(), records.count { it.usage.totalTokens > 0 }.toLong())
    }
    override suspend fun resetStats() = lock.withLock {
        val old = store.usageWatermarks()
        withContext(NonCancellable) {
            store.resetUsageWatermarks()
            try { base.resetStats() }
            catch (failure: Throwable) {
                try { store.restoreUsageWatermarks(old) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            }
        }
    }
}
private operator fun TokenUsageAggregate.plus(other: TokenUsageAggregate) = TokenUsageAggregate(inputTokens + other.inputTokens, outputTokens + other.outputTokens, estimated || other.estimated, cache + other.cache)
private fun List<TokenUsageAggregate>.sumUsage() = fold(TokenUsageAggregate()) { sum, value -> sum + value }
