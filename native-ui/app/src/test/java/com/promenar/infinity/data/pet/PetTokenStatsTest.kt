package com.promenar.infinity.data.pet

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.ModelTokenRow
import com.promenar.infinity.data.local.db.dao.TokenTotalRow
import com.promenar.infinity.data.rag.KgDailyUsage
import com.promenar.infinity.data.rag.KgTokenUsage
import com.promenar.infinity.data.rag.KgUsageRecord
import com.promenar.infinity.data.rag.KgUsageSnapshot
import com.promenar.infinity.data.rag.KgUsageStore
import com.promenar.infinity.data.repository.TokenStatsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PetTokenStatsTest {
    @Test fun `宠物用量进入总计模型日期分类并可清零`() = runBlocking {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getTotalTokenUsage() } returns TokenTotalRow(10, 5)
        coEvery { dao.getTokenUsageByModel() } returns listOf(ModelTokenRow("model", 10, 5))
        coEvery { dao.getDailyTokenTrend(any()) } returns emptyList()
        val day = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val pet = object : KgUsageStore {
            var cleared = false
            override suspend fun record(record: KgUsageRecord) = Unit
            override suspend fun snapshot() = if (cleared) KgUsageSnapshot() else KgUsageSnapshot(
                listOf(KgUsageRecord("model", KgTokenUsage(3, 2))), listOf(KgDailyUsage(day, KgTokenUsage(3, 2))),
            )
            override suspend fun clear() { cleared = true }
        }
        val stats = TokenStatsRepository(dao, petUsageStore = pet)
        assertThat(stats.getTotalUsage().inputTokens).isEqualTo(13)
        assertThat(stats.getUsageByModel().single().usage.outputTokens).isEqualTo(7)
        assertThat(stats.getDailyTrend(1).single().inputTokens).isEqualTo(3)
        assertThat(stats.getUsageByCategory().single { it.category == "pet" }.usage.totalTokens).isEqualTo(5)
        stats.resetStats()
        assertThat(pet.cleared).isTrue()
    }
}
