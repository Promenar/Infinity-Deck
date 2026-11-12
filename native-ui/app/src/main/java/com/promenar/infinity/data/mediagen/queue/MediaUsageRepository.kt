package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.Money
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 币种独立汇总；未知费用独立成组，不能转换成零或混入美元合计。 */
data class MediaUsageSummary(
    val providerId: String,
    val modelId: String,
    val currency: String?,
    val knownCost: Money?,
    val tasks: Int,
    val unknownCostTasks: Int,
)

fun summarizeMediaUsage(entries: List<MediaUsageEntry>): List<MediaUsageSummary> = entries
    .groupBy { Triple(it.providerId, it.modelId, it.cost?.currency) }
    .map { (key, rows) ->
        MediaUsageSummary(key.first, key.second, key.third,
            key.third?.let { currency -> Money(rows.fold(BigDecimal.ZERO) { sum, row -> sum.add(requireNotNull(row.cost).amount) }, currency) },
            rows.size, rows.count { it.cost == null })
    }.sortedWith(compareBy({ it.providerId }, { it.modelId }, { it.currency ?: "" }))

class MediaUsageRepository(private val dao: MediaGenerationDao) {
    fun observeSummary(): Flow<List<MediaUsageSummary>> = dao.observeUsage().map { rows -> summarizeMediaUsage(rows.map { it.toEntry() }) }
    suspend fun summary(): List<MediaUsageSummary> = summarizeMediaUsage(dao.usage().map { it.toEntry() })
}
