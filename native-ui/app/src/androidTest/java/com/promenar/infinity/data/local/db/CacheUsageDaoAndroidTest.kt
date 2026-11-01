package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android SQLite 原生路径；工作包只编译，设备执行由集成验收完成。 */
@RunWith(AndroidJUnit4::class)
class CacheUsageDaoAndroidTest {
    @Test fun aggregateCacheUsageInRoomMemoryDatabase() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.sessionDao().insert(SessionEntity("s", "a", createdAt = 0, updatedAt = 0))
            listOf(
                """{"input":100}""",
                """{"input":200,"cacheReadInput":0}""",
                """{"input":130,"summaryInput":30,"cacheReadInput":60,"cacheWriteInput":20,"cacheWriteLongTtlInput":5}""",
                """{"input":100,"summaryInput":50,"cacheReadInput":70}""",
            ).forEachIndexed { index, tokens -> database.messageDao().insert(MessageEntity(
                "m$index", "s", "assistant", "", modelId = "model", tokens = tokens, createdAt = 1_000,
            )) }
            val expected = CacheUsageAggregate(4, 2, 300, 60, 20, 5, 1)
            assertEquals(expected, database.messageDao().getTotalTokenUsage().cache)
            assertEquals(expected, database.messageDao().getTokenUsageByModel().single().cache)
            assertEquals(expected, database.messageDao().getDailyTokenTrend(0).single().cache)
        } finally { database.close() }
    }
}
