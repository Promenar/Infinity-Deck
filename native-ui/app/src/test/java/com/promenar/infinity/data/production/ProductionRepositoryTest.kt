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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ProductionRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: ProductionRepository
    private val files = FakeFiles()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        db.agentDao().insert(AgentEntity("a", "助手", createdAt = 1))
        listOf("s", "s2").forEach { db.sessionDao().insert(SessionEntity(it, "a", "会话", createdAt = 1, updatedAt = 1)) }
        repo = ProductionRepository(db, files, now = { 10 }, newId = { "project" })
    }
    @After fun close() { repo.close(); db.close() }
    @Test fun `定义解绑重新绑定与曾经绑定`() = runBlocking {
        val created = repo.define("s", "影片", ProductionKind.FILM)
        assertThat(created.manifest).isEqualTo(ManifestSync.Pending)
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.InSync)
        assertThat(files.bytes).isNotEmpty()
        repo.unbind("s")
        assertThat(repo.observeEverBound("s").first()).isTrue()
        assertThat(repo.get("s")).isNull()
        val attached = repo.attach("s", "productions/project/production.json")
        assertThat(attached.production.id).isEqualTo("project")
    }
    @Test fun `外部变化采纳与覆盖`() = runBlocking {
        val initial = repo.define("s", "影片", ProductionKind.FILM)
        val path = "productions/project/production.json"
        repo.get("s")
        files.bytes["s" to path] = ProductionManifestCodec.encode(initial.production.copy(title = "外部"), 5)
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.ExternalChange(5))
        val adopted = repo.resolveManifest("s", ManifestResolution.ADOPT_FILE)
        assertThat(adopted.production.title).isEqualTo("外部")
        assertThat(adopted.revision).isEqualTo(6)
        files.bytes["s" to path] = "invalid".toByteArray()
        assertThat(repo.get("s")!!.manifest).isInstanceOf(ManifestSync.Invalid::class.java)
        assertThat(repo.resolveManifest("s", ManifestResolution.OVERWRITE_FILE).manifest).isEqualTo(ManifestSync.InSync)
    }
    @Test fun `写入失败记录错误并能重试`() = runBlocking {
        files.failWrite = true
        val result = repo.define("s", "影片", ProductionKind.FILM)
        assertThat(result.manifest).isEqualTo(ManifestSync.Pending)
        assertThat(repo.get("s")!!.manifest).isInstanceOf(ManifestSync.WriteFailed::class.java)
        assertThat(db.productionDao().get("project")!!.manifestError).isNotNull()
        files.failWrite = false
        assertThat(repo.resolveManifest("s", ManifestResolution.OVERWRITE_FILE).manifest).isEqualTo(ManifestSync.InSync)
    }
    @Test fun `删除会话只级联解绑`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        db.sessionDao().deleteById("s")
        assertThat(db.productionDao().get("project")).isNotNull()
        assertThat(repo.observeEverBound("s").first()).isFalse()
    }
    @Test fun `附加同一项目时拒绝其他活动会话`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        repo.get("s")
        val path = "productions/project/production.json"
        files.bytes["s2" to path] = files.bytes.getValue("s" to path).copyOf()
        try { repo.attach("s2", path); org.junit.Assert.fail("应拒绝绑定") } catch (e: IllegalArgumentException) {
            assertThat(e.message).isEqualTo("该项目已绑定到另一个会话，请先在原会话解除绑定。")
        }
        repo.unbind("s")
        assertThat(repo.attach("s2", path).production.id).isEqualTo("project")
    }
    @Test fun `缺失清单时重写且过期用户操作被拒绝`() = runBlocking {
        val created = repo.define("s", "影片", ProductionKind.FILM)
        files.bytes.clear()
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.InSync)
        assertThat(files.bytes).isNotEmpty()
        val action = ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.LOCKED)
        repo.apply("s", created.revision, action)
        try { repo.apply("s", created.revision, action); org.junit.Assert.fail("应拒绝过期修订") } catch (e: IllegalArgumentException) { assertThat(e.message).isEqualTo("面板已更新，请重试。") }
    }
    @Test fun `锁定内容编辑事务只保存待审请求并拒绝过期审批`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        val operations = Json.parseToJsonElement("""[
            {"op":"upsert_season","id":"S1","number":1},
            {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"集"},
            {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"场"},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"原镜头","duration_seconds":2}
        ]""").jsonArray.map { it.jsonObject }
        val created = repo.update("s", "m", operations)
        val locked = repo.apply("s", created.revision, ProductionUserAction.LockScene("S1E01", "SC01", true))
        fun shot(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().scenes.single().shots.single()
        val action = ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(locked).copy(title = "已审批镜头"))
        val pending = repo.apply("s", locked.revision, action)
        val request = pending.production.changeRequests.single()
        assertThat(shot(repo.get("s")!!)).isEqualTo(shot(locked))
        assertThat(request.approved).isFalse()
        try { repo.apply("s", locked.revision, action); org.junit.Assert.fail("应拒绝过期编辑") } catch (_: ProductionRevisionConflictException) { }
        try { repo.apply("s", locked.revision, ProductionUserAction.ResolveChange(request.id, true)); org.junit.Assert.fail("应拒绝过期审批") } catch (_: ProductionRevisionConflictException) { }
        val persisted = repo.get("s")!!
        assertThat(persisted.revision).isEqualTo(pending.revision)
        assertThat(persisted.production.changeRequests).containsExactly(request)
        val approved = repo.apply("s", pending.revision, ProductionUserAction.ResolveChange(request.id, true))
        assertThat(shot(approved).title).isEqualTo("已审批镜头")
        assertThat(approved.production.changeRequests.single().approved).isTrue()
    }
    @Test fun `连续提交合并写出最新修订`() = runBlocking {
        val initial = repo.define("s", "影片", ProductionKind.FILM)
        val next = repo.apply("s", initial.revision, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.IN_PROGRESS))
        repo.apply("s", next.revision, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.LOCKED))
        delay(800)
        val file = ProductionManifestCodec.decode(files.bytes.getValue("s" to "productions/project/production.json"))
        assertThat(files.writes.get()).isEqualTo(1)
        assertThat(file.revision).isEqualTo(3)
        assertThat(file.production.stages.first().status).isEqualTo(StageStatus.LOCKED)
    }
    @Test fun `登记候选必须由工作区识别为媒体`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        fun operations(raw: String) = Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }
        repo.update("s", "m", operations("""[{"op":"upsert_season","id":"S1","number":1},{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"集"},{"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"场"},{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"镜头","duration_seconds":2}]"""))
        val beforeLock = repo.get("s")!!
        repo.apply("s", beforeLock.revision, ProductionUserAction.LockScene("S1E01", "SC01", true))
        repo.recordContextRead("s", "m", beforeLock.revision + 1, beforeLock.production.id)
        val take = operations("""[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"media/a.mp4","service":"服务"}]""")
        try { repo.update("s", "m", take); org.junit.Assert.fail("缺失媒体应被拒绝") } catch (_: IllegalArgumentException) { }
        files.bytes["s" to "media/a.mp4"] = byteArrayOf(1)
        try { repo.update("s", "m", take); org.junit.Assert.fail("非媒体文件应被拒绝") } catch (_: IllegalArgumentException) { }
        files.mediaPaths.add("s" to "media/a.mp4")
        val result = repo.update("s", "m", take)
        assertThat(result.production.seasons.single().episodes.single().scenes.single().shots.single().takes).hasSize(1)
    }
    @Test fun `清单变化后拒绝采纳旧判定`() = runBlocking {
        val created = repo.define("s", "影片", ProductionKind.FILM)
        repo.get("s")
        val path = "productions/project/production.json"
        val first = ProductionManifestCodec.encode(created.production.copy(title = "外部一"), 5)
        files.bytes["s" to path] = first
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.ExternalChange(5))
        files.bytes["s" to path] = ProductionManifestCodec.encode(created.production.copy(title = "外部二"), 6)
        try { repo.resolveManifest("s", ManifestResolution.ADOPT_FILE); org.junit.Assert.fail("应拒绝采纳") } catch (e: IllegalArgumentException) { assertThat(e.message).isEqualTo("清单已变化，请重新查看。") }
        files.bytes["s" to path] = first
        try { repo.resolveManifest("s", ManifestResolution.ADOPT_FILE); org.junit.Assert.fail("必须重新查看") } catch (e: IllegalArgumentException) { assertThat(e.message).isEqualTo("清单已变化，请重新查看。") }
    }
    @Test fun `新项目拒绝覆盖其他项目的清单并提示换路径`() = runBlocking {
        val other = Production("other", "其他", ProductionKind.FILM, "productions/project", createdAt = 1, updatedAt = 1)
        files.bytes["s" to "productions/project/production.json"] = ProductionManifestCodec.encode(other, 1)
        try { repo.define("s", "影片", ProductionKind.FILM); org.junit.Assert.fail("应拒绝覆盖") } catch (e: IllegalArgumentException) { assertThat(e.message).contains("root_path") }
        assertThat(db.productionDao().get("project")).isNull()
    }
    @Test fun `修改根目录清除旧哈希`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        repo.get("s")
        assertThat(db.productionDao().get("project")!!.manifestSha256).isNotNull()
        files.failWrite = true
        repo.define("s", "影片", ProductionKind.FILM, rootPath = "productions/new")
        assertThat(db.productionDao().get("project")!!.manifestSha256).isNull()
    }
    @Test fun `后台失败后再次提交可写出`() = runBlocking {
        files.failWrite = true
        val initial = repo.define("s", "影片", ProductionKind.FILM)
        withTimeout(5000) { while (db.productionDao().get("project")?.manifestError == null) delay(20) }
        assertThat(db.productionDao().get("project")!!.manifestError).isEqualTo("写入失败")
        assertThat(repo.observe("s").first()!!.manifest).isInstanceOf(ManifestSync.WriteFailed::class.java)
        files.failWrite = false
        repo.apply("s", initial.revision, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.IN_PROGRESS))
        withTimeout(5000) { while (files.bytes.isEmpty()) delay(20) }
        assertThat(files.bytes).isNotEmpty()
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.InSync)
    }
    @Test fun `采纳文件清除已读取修订`() = runBlocking {
        val initial = repo.define("s", "影片", ProductionKind.FILM)
        repo.get("s")
        repo.recordContextRead("s", "m", 5, "project")
        val external = initial.production.copy(stages = ProductionStage.entries.map { StageState(it, changedBy = ChangeSource.USER, changedAt = 5) })
        files.bytes["s" to "productions/project/production.json"] = ProductionManifestCodec.encode(external, 5)
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.ExternalChange(5))
        repo.resolveManifest("s", ManifestResolution.ADOPT_FILE)
        val ops = Json.parseToJsonElement("""[{"op":"set_stage","stage":"script","status":"in_progress"}]""").jsonArray.map { it.jsonObject }
        try { repo.update("s", "m", ops); org.junit.Assert.fail("采纳后必须重新读取") } catch (e: IllegalArgumentException) { assertThat(e.message).contains("production_get") }
    }
    @Test fun `数据库快照包含默认值并兼容未知字段`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        val row = db.productionDao().get("project")!!
        assertThat(row.snapshot).contains("\"previewEnabled\":true")
        val extra = row.snapshot.dropLast(1) + ",\"futureField\":true}"
        db.productionDao().update(row.copy(snapshot = extra))
        assertThat(repo.get("s")!!.production.previewEnabled).isTrue()
    }
    @Test fun `未判定为外部变化时不能采纳`() = runBlocking {
        repo.define("s", "影片", ProductionKind.FILM)
        assertThat(repo.get("s")!!.manifest).isEqualTo(ManifestSync.InSync)
        try { repo.resolveManifest("s", ManifestResolution.ADOPT_FILE); org.junit.Assert.fail("应拒绝采纳") } catch (e: IllegalArgumentException) { assertThat(e.message).isEqualTo("清单已变化，请重新查看。") }
    }
    private class FakeFiles : ProductionWorkspaceFiles {
        val bytes = ConcurrentHashMap<Pair<String, String>, ByteArray>()
        val writes = AtomicInteger()
        val mediaPaths = ConcurrentHashMap.newKeySet<Pair<String, String>>()
        @Volatile var failWrite = false
        override suspend fun read(sessionId: String, path: String) = bytes[sessionId to path]
        override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?) {
            check(!failWrite) { "写入失败" }
            check(this.bytes[sessionId to path]?.let(ProductionManifestCodec::sha256) == expectedSha256) { "文件已改变" }
            this.bytes[sessionId to path] = bytes
            writes.incrementAndGet()
        }
        override suspend fun isImageOrVideo(sessionId: String, path: String) = bytes.containsKey(sessionId to path) && (sessionId to path) in mediaPaths
    }
}
