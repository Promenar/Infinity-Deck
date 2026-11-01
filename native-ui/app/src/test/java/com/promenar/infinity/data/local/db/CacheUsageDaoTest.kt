package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.repository.TokenStatsRepository
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CacheUsageDaoTest {
    @Test fun `SQL 总量模型与每日聚合保持未报告零值异常及摘要分母`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().openHelperFactory(::jsonSqliteTestHelper).build()
        try {
            db.sessionDao().insert(SessionEntity("s", "a", createdAt = 0, updatedAt = 0))
            val tokens = listOf(
                """{"input":100,"output":1}""",
                """{"input":200,"cacheReadInput":0}""",
                """{"input":130,"summaryInput":30,"cacheReadInput":60,"cacheWriteInput":20,"cacheWriteLongTtlInput":5}""",
                """{"input":100,"summaryInput":50,"cacheReadInput":70,"cacheWriteInput":10}""",
                """{"input":80,"cacheWriteInput":40,"cacheWriteLongTtlInput":10}""",
                """{"input":0,"cacheReadInput":0}""",
                """{"input":100,"cacheReadInput":-1}""",
            )
            tokens.forEachIndexed { i, value -> db.messageDao().insert(MessageEntity(
                "m$i", "s", "assistant", content = "", modelId = "model", tokens = value, createdAt = 1_000,
            )) }
            db.messageDao().insert(MessageEntity("user", "s", "user", content = "", tokens = """{"input":20,"cacheReadInput":20}""", createdAt = 1_000))
            val repository = TokenStatsRepository(db.messageDao())
            val expected = CacheUsageAggregate(7, 3, 300, 60, 70, 15, 1)
            assertThat(repository.getTotalUsage().cache).isEqualTo(expected)
            assertThat(repository.getUsageByModel().single { it.modelId == "model" }.usage.cache).isEqualTo(expected)
            assertThat(db.messageDao().getDailyTokenTrend(0).single().cache).isEqualTo(expected)
            assertThat(repository.getUsageBySession("s").cache).isEqualTo(expected)
            assertThat(repository.getUsageByCategory().single { it.category == "chat" }.usage.cache).isEqualTo(expected)
            assertThat(expected.hitRate).isEqualTo(0.2)
            assertThat(expected.coverage).isWithin(1e-9).of(3.0 / 7)
        } finally { db.close() }
    }

    @Test fun `无记录与零分母不伪造命中率`() = runBlocking {
        assertThat(CacheUsageAggregate().hitRate).isNull()
        assertThat(CacheUsageAggregate().coverage).isNull()
        assertThat(CacheUsageAggregate(requests = 1, reportedRequests = 1).hitRate).isNull()
    }
}
