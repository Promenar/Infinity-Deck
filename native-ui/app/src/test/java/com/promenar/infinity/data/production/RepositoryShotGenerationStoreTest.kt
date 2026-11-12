package com.promenar.infinity.data.production

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.repository.ProductionRepository
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class RepositoryShotGenerationStoreTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: ProductionRepository
    private lateinit var store: RepositoryShotGenerationStore
    private val files = PlanningFiles()
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        database.agentDao().insert(AgentEntity("a", "助手", createdAt = 1))
        database.sessionDao().insert(SessionEntity("s", "a", "会话", createdAt = 1, updatedAt = 1))
        repository = ProductionRepository(database, files, now = { 5 }, newId = { "p" })
        repository.define("s", "影片", ProductionKind.SERIES)
        repository.update("s", "m", Json.parseToJsonElement("""[
            {"op":"upsert_season","id":"S1","number":1},
            {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"第一集"},
            {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"场"},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"镜头","duration_seconds":5}
        ]""").jsonArray.map { it.jsonObject })
        val snapshot = repository.get("s")!!
        repository.apply("s", snapshot.revision, ProductionUserAction.Review(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.APPROVE, null))
        store = RepositoryShotGenerationStore(repository,
            { id -> database.productionDao().get(id)?.let { Json { ignoreUnknownKeys = true }.decodeFromString(Production.serializer(), it.snapshot) } },
            { id -> database.productionDao().activeSession(id) })
    }
    @After fun close() { repository.close(); database.close() }
    private fun task() = MediaTask("task", MediaGenerationRequest("video", GenerationMode.TEXT, "提交时文本", emptyList(), ProductionSourceRef("p", "S1E01", "SC01-01").encode(), sessionId = "s", outputDir = "productions/p/S1E01/takes"),
        null, MediaTaskState.COMPLETED, outputPaths = listOf("productions/p/S1E01/takes/output.mp4"))
    @Test fun `登记进入真实仓库事务且重复扫描不增加修订`() = runBlocking {
        Assert.assertTrue(store.register(task()))
        val first = repository.get("s")!!
        Assert.assertTrue(store.register(task()))
        val second = repository.get("s")!!
        Assert.assertEquals(first.revision, second.revision)
        val shot = second.production.seasons.single().episodes.single().scenes.single().shots.single()
        Assert.assertEquals(1, shot.takes.size)
        Assert.assertEquals("提交时文本", shot.takes.single().planSnapshot?.prompt)
    }
    @Test fun `解绑时保留任务且重新绑定后可登记`() = runBlocking {
        // 清单由后台同步写出；等写盘完成后再解绑。
        withTimeout(5000) { while (files.bytes["productions/p/production.json"] == null) delay(20) }
        repository.unbind("s")
        Assert.assertFalse(store.register(task()))
        repository.attach("s", "productions/p/production.json")
        Assert.assertTrue(store.register(task()))
    }
    @Test fun `重新编译原子清除用户改写且旧修订不覆盖`() = runBlocking {
        val current = repository.get("s")!!
        val edited = repository.apply("s", current.revision, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户文本"))
        try { store.recompile("s", current.revision, "S1E01", "SC01-01", "过期编译"); Assert.fail() } catch (_: IllegalArgumentException) { }
        store.recompile("s", edited.revision, "S1E01", "SC01-01", "新编译")
        val shot = repository.get("s")!!.production.seasons.single().episodes.single().scenes.single().shots.single()
        Assert.assertFalse(shot.promptUserEdited); Assert.assertEquals("新编译", shot.prompt)
    }
}
