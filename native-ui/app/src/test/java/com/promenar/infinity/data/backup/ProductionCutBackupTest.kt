package com.promenar.infinity.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.ProductionEntity
import com.promenar.infinity.data.production.CutExportRecord
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionCuts
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.security.SecretId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

/** 实际打包和恢复 productions.snapshot，覆盖新增默认字段与恢复期领域校验。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ProductionCutBackupTest {
    private lateinit var db: InfinityDatabase
    private lateinit var base: Path
    private lateinit var source: RoomBackupDataSource
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val codec = DefaultBackupPackageCodec()
    private val content = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES, BackupContent.SECRETS)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-cut-backup-test")
        val files = Files.createDirectory(base.resolve("source"))
        val restore = Files.createDirectory(base.resolve("restore"))
        source = RoomBackupDataSource(db, EmptyPreferences(), EmptySecrets(), setOf(files), restore, "test",
            journalAuthenticator = TestRestoreJournalAuthenticator, restoreFileOperations = TestRestoreFileOperations())
    }

    @After fun close() { db.close(); base.toFile().deleteRecursively() }

    private fun production() = Production("p", "剪辑项目", ProductionKind.FILM, "productions/p", createdAt = 1, updatedAt = 1,
        seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场",
            shots = listOf(Shot("SC01-01", "标题卡", durationSeconds = 2.0)))))))))

    private suspend fun store(p: Production, serialized: String = json.encodeToString(p)) {
        db.agentDao().insert(AgentEntity("agent", "助手", createdAt = 1))
        db.productionDao().insert(ProductionEntity(p.id, p.title, p.rootPath, 1, serialized, null, null, p.createdAt, p.updatedAt))
    }

    private suspend fun packageBytes(): ByteArray {
        val snapshot = source.snapshot(content)
        return try { codec.encode(snapshot, BackupOptions(content)) } finally { snapshot.wipe() }
    }

    @Test fun `完整备份恢复剪辑冻结字幕导出记录和删除编号`() = runBlocking {
        var p = ProductionCuts.create(production(), "S1E01", emptyMap(), 2)
        val first = p.cuts.single()
        p = ProductionCuts.delete(p, first.id)
        p = ProductionCuts.create(p, "S1E01", emptyMap(), 3)
        val cut = p.cuts.single()
        p = ProductionCuts.setSubtitles(p, cut.id, true)
        p = ProductionCuts.registerExport(p, cut.id, CutExportRecord(ProductionCuts.videoPath(p, cut), ProductionCuts.subtitlePath(p, cut), 2.0, 100, 4))
        store(p)
        val bytes = packageBytes()
        db.clearAllTables()
        codec.decode(bytes, null).use { source.restore(it) }
        val restored = json.decodeFromString<Production>(db.productionDao().get("p")!!.snapshot)
        assertThat(restored).isEqualTo(p)
        assertThat(restored.cuts.single().number).isEqualTo(2)
        val afterDelete = ProductionCuts.delete(restored, cut.id)
        assertThat(ProductionCuts.create(afterDelete, "S1E01", emptyMap(), 5).cuts.single().number).isEqualTo(3)
    }

    @Test fun `旧备份快照缺少剪辑字段时恢复为空列表与空编号记录`() = runBlocking {
        val p = production()
        val old = JsonObject(Json.parseToJsonElement(json.encodeToString(p)).jsonObject - "cuts" - "cutVersionHighWatermarks")
        store(p, old.toString())
        val bytes = packageBytes()
        db.clearAllTables()
        codec.decode(bytes, null).use { source.restore(it) }
        val restored = json.decodeFromString<Production>(db.productionDao().get("p")!!.snapshot)
        assertThat(restored.cuts).isEmpty()
        assertThat(restored.cutVersionHighWatermarks).isEmpty()
    }

    @Test fun `备份恢复拒绝剪辑无效镜头引用`() = runBlocking {
        val valid = ProductionCuts.create(production(), "S1E01", emptyMap(), 2)
        val cut = valid.cuts.single()
        val invalid = valid.copy(cuts = listOf(cut.copy(clips = listOf(cut.clips.single().copy(shotCode = "SC01-99")))))
        store(invalid)
        val bytes = packageBytes()
        val failure = codec.decode(bytes, null).use { runCatching { source.restore(it) }.exceptionOrNull() }
        assertThat(failure).isInstanceOf(BackupValidationException::class.java)
        assertThat(failure?.message).contains("制作项目快照无效")
    }

    private class EmptyPreferences : TransactionalBackupPreferenceStore {
        override suspend fun snapshot(maxTotalBytes: Long) = BackupPreferenceSnapshot(emptyList(), emptySet())
        override suspend fun preflightRestore(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }

    private class EmptySecrets : TransactionalBackupSecretStore {
        override suspend fun ids() = emptySet<SecretId>()
        override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long) = emptyMap<SecretId, ByteArray>()
        override suspend fun prepare(txId: String, before: Map<SecretId, ByteArray>, after: Map<SecretId, ByteArray>) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }
}
