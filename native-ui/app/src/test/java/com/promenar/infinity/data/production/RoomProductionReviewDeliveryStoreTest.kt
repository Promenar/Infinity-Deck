package com.promenar.infinity.data.production

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.repository.ProductionRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomProductionReviewDeliveryStoreTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repository: ProductionRepository
    private lateinit var store: RoomProductionReviewDeliveryStore
    private val bytes = ConcurrentHashMap<String, ByteArray>()
    private var holdQueuedWrite = false
    private val writing = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val files = object : ProductionWorkspaceFiles {
        override suspend fun read(sessionId: String, path: String) = bytes[path]
        override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?) {
            if (holdQueuedWrite && ProductionManifestCodec.decode(bytes).production.queuedReviewRound != null) {
                writing.complete(Unit)
                release.await()
            }
            this@RoomProductionReviewDeliveryStoreTest.bytes[path] = bytes
        }
        override suspend fun isImageOrVideo(sessionId: String, path: String) = false
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        db.agentDao().insert(AgentEntity("a", "助手", createdAt = 1))
        db.sessionDao().insert(SessionEntity("s", "a", createdAt = 1, updatedAt = 1))
        repository = ProductionRepository(db, files, now = { 10 }, newId = { "p" })
        store = RoomProductionReviewDeliveryStore(db, repository)
    }
    @After fun close() { release.complete(Unit); repository.close(); db.close() }
    private suspend fun queue(): ProductionSnapshot {
        val created = repository.define("s", "项目", ProductionKind.FILM)
        val reviewed = repository.apply("s", created.revision, ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "审核意见"))
        return repository.apply("s", reviewed.revision, ProductionUserAction.MarkReviewQueued(1, 10))
    }

    @Test fun `会话删除后保留项目仍可按身份回退且旧回执不影响其它项目`() = runBlocking {
        val queued = queue()
        db.sessionDao().deleteById("s")
        store.apply("s", "p", queued.revision, ProductionUserAction.RevertReviewQueued(1))
        val restored = requireNotNull(store.getById("p"))
        assertThat(restored.production.unsentReviews().single().note).isEqualTo("审核意见")
        assertThat(restored.production.nextReviewRound).isEqualTo(1)
        assertThat(store.queuedProjects()).isEmpty()
        assertThat(store.getById("missing")).isNull()
    }

    @Test fun `清单写入持有旧快照时删除会话回执等待写入结束再提交`() = runBlocking {
        holdQueuedWrite = true
        val queued = queue()
        withTimeout(5000) { writing.await() }
        db.sessionDao().deleteById("s")
        val receipt = async { store.apply("s", "p", queued.revision, ProductionUserAction.MarkReviewSent(1, 20)) }
        kotlinx.coroutines.yield()
        assertThat(receipt.isCompleted).isFalse()
        release.complete(Unit)
        withTimeout(5000) { receipt.await() }
        val sent = requireNotNull(store.getById("p"))
        assertThat(sent.production.nextReviewRound).isEqualTo(2)
        assertThat(sent.production.reviewRounds.single().entries.single().note).isEqualTo("审核意见")
        assertThat(sent.revision).isGreaterThan(queued.revision)
    }
}
