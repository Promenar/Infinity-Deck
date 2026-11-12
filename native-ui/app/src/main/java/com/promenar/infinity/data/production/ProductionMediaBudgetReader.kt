package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 项目预算以人民币分保存；读取发生在队列的串行入队事务区，前一任务已持久化。 */
class ProductionMediaBudgetReader(private val store: ShotGenerationStore, private val queue: MediaGenerationQueue,
    private val currency: String = "CNY") : MediaBudgetReader {
    override suspend fun readBudget(sourceRef: String): MediaBudget? = withContext(Dispatchers.IO) {
        val ref = ProductionSourceRef.parse(sourceRef) ?: return@withContext null
        val cents = store.production(ref.productionId)?.budgetCents ?: return@withContext null
        var spent = BigDecimal.ZERO
        for (task in queue.observeTasks().first()) {
            if (ProductionSourceRef.parse(task.request.sourceRef)?.productionId != ref.productionId) continue
            val cost = when {
                task.actualCost != null -> task.actualCost
                task.state == MediaTaskState.COMPLETED || task.state in setOf(MediaTaskState.DOWNLOADING, MediaTaskState.DOWNLOAD_FAILED) -> null
                task.state == MediaTaskState.FAILED && task.remoteId == null && task.failure == MediaTaskFailure.CHANNEL_UNAVAILABLE -> continue
                else -> task.estimate
            } ?: return@withContext null
            if (cost.currency != currency) return@withContext null
            spent = spent.add(cost.amount)
        }
        MediaBudget(Money(BigDecimal.valueOf(cents, 2), currency), Money(spent, currency))
    }
}
