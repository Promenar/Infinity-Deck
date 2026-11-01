package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.domain.repository.ModelTokenStats
import com.promenar.infinity.domain.repository.TokenUsageAggregate
import com.promenar.infinity.domain.repository.SessionTokenUsage
import com.promenar.infinity.domain.repository.DailyTokenStats
import com.promenar.infinity.domain.repository.CategoryTokenStats
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.domain.repository.sumCacheUsage
import com.promenar.infinity.data.model.cacheUsageAggregate
import com.promenar.infinity.data.rag.KgUsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TokenStatsRepository(
    private val messageDao: MessageDao,
    private val kgUsageStore: KgUsageStore = KgUsageStore.None,
    private val rolledBackSummaryUsageStore: RolledBackSummaryUsageStore? = null,
) : ITokenStatsRepository {

    override suspend fun getTotalUsage(): TokenUsageAggregate = withRollbackUsage { records ->
        val row = messageDao.getTotalTokenUsage()
        val kg = kgUsageStore.snapshot().usage
        val rolledBack = records.usage()
        TokenUsageAggregate(
            inputTokens = row.total_input + kg.inputTokens + rolledBack.inputTokens,
            outputTokens = row.total_output + kg.outputTokens + rolledBack.outputTokens,
            estimated = kg.estimated || rolledBack.estimated,
            cache = row.cache,
        )
    }

    override suspend fun getUsageByModel(): List<ModelTokenStats> = withRollbackUsage { records ->
        val chat = messageDao.getTokenUsageByModel().map { row ->
            ModelTokenStats(
                modelId = row.model_id,
                usage = TokenUsageAggregate(
                    inputTokens = row.total_input,
                    outputTokens = row.total_output,
                    cache = row.cache,
                )
            )
        }
        val kg = kgUsageStore.snapshot().records.map { record ->
            ModelTokenStats(record.modelId, TokenUsageAggregate(record.usage.inputTokens, record.usage.outputTokens, record.usage.estimated))
        }
        val rolledBack = records.map { record ->
            ModelTokenStats(record.modelId, listOf(record).usage())
        }
        (chat + kg + rolledBack).groupBy { it.modelId }.map { (modelId, entries) ->
            ModelTokenStats(modelId, TokenUsageAggregate(
                entries.sumOf { it.usage.inputTokens }, entries.sumOf { it.usage.outputTokens }, entries.any { it.usage.estimated },
                cache = entries.map { it.usage.cache }.sumCacheUsage(),
            ))
        }
    }

    override suspend fun getUsageBySession(sessionId: String): TokenUsageAggregate = withRollbackUsage { records ->
        val entities = messageDao.getMessagesWithTokensBySession(sessionId)
        var input = 0L
        var output = 0L
        var cache = CacheUsageAggregate()
        for (entity in entities) {
            val usage = parseTokens(entity.tokens) ?: continue
            input += usage.input
            output += usage.output
            if (entity.role == "assistant") cache += usage.cacheUsageAggregate()
        }
        val rolledBack = records.filter { it.sessionId == sessionId }.usage()
        TokenUsageAggregate(
            inputTokens = input + rolledBack.inputTokens,
            outputTokens = output + rolledBack.outputTokens,
            estimated = rolledBack.estimated,
            cache = cache,
        )
    }

    override suspend fun getTopSessions(limit: Int): List<SessionTokenUsage> = withRollbackUsage { records ->
        // 回滚费用可能使原先未入榜的会话进入前列，合并后再截断。
        val chat = messageDao.getSessionTokenRanking(if (records.isEmpty()) limit else Int.MAX_VALUE).map { row ->
            SessionTokenUsage(
                sessionId = row.session_id,
                title = row.title,
                inputTokens = row.total_input,
                outputTokens = row.total_output,
            )
        }
        val rolledBack = records.map { record ->
            SessionTokenUsage(record.sessionId, null, record.usage.input.toLong(), record.usage.output.toLong())
        }
        val ranked = (chat + rolledBack).groupBy { it.sessionId }.map { (sessionId, entries) ->
            SessionTokenUsage(
                sessionId, entries.firstNotNullOfOrNull { it.title },
                entries.sumOf { it.inputTokens }, entries.sumOf { it.outputTokens },
            )
        }.sortedByDescending { it.totalTokens }
        if (limit < 0) ranked else ranked.take(limit)
    }

    override suspend fun getDailyTrend(days: Int): List<DailyTokenStats> = withRollbackUsage { records ->
        val sinceTimestamp = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
        val chat = messageDao.getDailyTokenTrend(sinceTimestamp).mapNotNull { row ->
            val day = row.day ?: return@mapNotNull null
            DailyTokenStats(
                day = day,
                inputTokens = row.total_input,
                outputTokens = row.total_output,
                cache = row.cache,
            )
        }
        val firstDay = java.time.Instant.ofEpochMilli(sinceTimestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val kg = kgUsageStore.snapshot().dailyUsage.filter { it.day >= firstDay }.map {
            DailyTokenStats(it.day, it.usage.inputTokens, it.usage.outputTokens)
        }
        val rolledBack = records.filter { it.createdAt >= sinceTimestamp }.map { record ->
            val day = java.time.Instant.ofEpochMilli(record.createdAt).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            DailyTokenStats(day, record.usage.input.toLong(), record.usage.output.toLong())
        }
        (chat + kg + rolledBack).groupBy { it.day }.map { (day, records) ->
            DailyTokenStats(day, records.sumOf { it.inputTokens }, records.sumOf { it.outputTokens }, records.map { it.cache }.sumCacheUsage())
        }.sortedBy { it.day }
    }

    override suspend fun getUsageByCategory(): List<CategoryTokenStats> = withContext(Dispatchers.IO) {
        withRollbackUsage { records ->
            val chat = messageDao.getTotalTokenUsage()
            val kg = kgUsageStore.snapshot()
            val rolledBack = records.usage()
            listOf(
                CategoryTokenStats("chat", TokenUsageAggregate(
                    chat.total_input + rolledBack.inputTokens, chat.total_output + rolledBack.outputTokens, rolledBack.estimated, cache = chat.cache,
                )),
                CategoryTokenStats("kg", TokenUsageAggregate(kg.usage.inputTokens, kg.usage.outputTokens, kg.usage.estimated), kg.calls),
            )
        }
    }

    override suspend fun resetStats() {
        val clearMessageUsage: suspend () -> Unit = {
            messageDao.clearAllTokenData()
            kgUsageStore.clear()
        }
        if (rolledBackSummaryUsageStore == null) clearMessageUsage()
        else rolledBackSummaryUsageStore.reset(clearMessageUsage)
    }

    private suspend fun <T> withRollbackUsage(block: suspend (List<RolledBackSummaryUsageRecord>) -> T): T {
        val usageStore = rolledBackSummaryUsageStore ?: return block(emptyList())
        return usageStore.withRecords { records ->
            // 账本先于删除写盘；消息尚存时只由 Room 统计，删除失败也不会双计。
            block(records.filter { messageDao.getById(it.messageId) == null })
        }
    }

    // 仅 getUsageBySession 仍需解析 JSON（该路径无法用 SQL 替代，因为 session 级查询已由 DAO 直接处理）
    private fun parseTokens(jsonStr: String?): com.promenar.infinity.data.model.TokenUsage? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            com.promenar.infinity.data.model.json.decodeFromString(
                kotlinx.serialization.serializer(),
                jsonStr
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun List<RolledBackSummaryUsageRecord>.usage() = TokenUsageAggregate(
    inputTokens = sumOf { it.usage.input.toLong() },
    outputTokens = sumOf { it.usage.output.toLong() },
    estimated = any { it.usage.estimated },
)
