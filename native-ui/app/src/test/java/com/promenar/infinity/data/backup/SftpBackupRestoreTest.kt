package com.promenar.infinity.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.storage.ExternalMountPaths.LIBRARY_SCOPE
import com.promenar.infinity.domain.storage.SFTP_REMOTE_SOURCE_TYPE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SftpBackupRestoreTest {
    private lateinit var db: InfinityDatabase
    private lateinit var base: Path
    private lateinit var cache: Path
    private lateinit var restore: Path
    private val dao get() = db.externalMountDao()
    private val codec = DefaultBackupPackageCodec()
    private val content = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
    private val core = setOf(BackupLayer.CORE)
    private val table = "sftp_remote_mounts"
    private val rowPrefix = "database/$table/"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-sftp-backup-test")
        cache = Files.createDirectory(base.resolve("cache"))
        restore = Files.createDirectory(base.resolve("restore"))
    }

    @After
    fun tearDown() {
        db.close()
        BackupStreams.deleteTree(base)
    }

    @Test
    fun `CORE 仅导出资料库和已随包导出的会话挂载`() = runBlocking {
        db.agentDao().insert(AgentEntity("agent", "助手", createdAt = 1))
        db.sessionDao().insert(SessionEntity("chat", "agent", createdAt = 1, updatedAt = 1))
        db.sessionDao().insert(SessionEntity("knowledge", BackupLayerPolicy.KNOWLEDGE_AGENT, createdAt = 1, updatedAt = 1))
        val mounts = listOf(
            sftp("library"),
            sftp("chat-mount", "chat", libraryId = "library"),
            sftp("knowledge-mount", "knowledge"),
            sftp("orphan", "missing"),
            sftp("global", ""),
            local("local"),
        )
        mounts.forEach { dao.insert(it) }
        dao.putSnapshot(snapshot("local"))
        val scenarios = listOf(
            core to setOf("library"),
            core + BackupLayer.SESSIONS to setOf("library", "chat-mount"),
            core + BackupLayer.KNOWLEDGE to setOf("library", "knowledge-mount"),
        )
        for ((layers, ids) in scenarios) {
            val captured = source().snapshotTo(layers, cache)
            try {
                val entry = captured.streamEntries.single { it.path.startsWith(rowPrefix) }
                assertThat(entry.layer).isEqualTo(BackupLayer.CORE)
                val rows = entry.blob.open().bufferedReader().useLines { lines ->
                    lines.map { Json.decodeFromString<JsonObject>(it) }.toList()
                }
                assertThat(rows.map { it.getValue("id") }).containsExactlyElementsIn(ids.map(::JsonPrimitive))
                rows.forEach { row ->
                    assertThat(row.keys).containsExactly("id", "alias", "source_type", "source_id", "scope_key", "read_only", "created_at")
                }
                assertThat(captured.streamEntries.map { it.path }.none {
                    it.startsWith("database/external_mounts/") || it.startsWith("database/external_snapshots/")
                }).isTrue()
                codec.decodeFrom(encodeStream(captured).inputStream(), null, cache).use { source().validateStaged(it) }
            } finally {
                captured.wipe()
            }
        }
    }

    @Test
    fun `恢复时挂载随已导出的会话保留资料库引用`() = runBlocking {
        val session = SessionEntity("chat", "agent", createdAt = 1, updatedAt = 1)
        db.agentDao().insert(AgentEntity("agent", "助手", createdAt = 1))
        db.sessionDao().insert(session)
        val library = sftp("library")
        val attached = sftp("attached", session.id, libraryId = library.id)
        dao.insert(library)
        dao.insert(attached)
        val captured = source().snapshotTo(core + BackupLayer.SESSIONS, cache)
        val bytes = try { encodeStream(captured) } finally { captured.wipe() }
        dao.delete(library.id)
        dao.delete(attached.id)
        db.sessionDao().delete(session)

        codec.decodeFrom(bytes.inputStream(), null, cache).use { source().restore(it, "session-mount") }

        assertThat(dao.all()).containsExactly(library.copy(unavailableReason = null), attached.copy(unavailableReason = null))
        assertThat(source().hasCompletedRestore("session-mount")).isTrue()
    }

    @Test
    fun `流式和完整恢复保留本机其他来源快照并使受影响的 SFTP 快照失效`() = runBlocking {
        val stable = sftp("stable").copy(alias = "old-name")
        val changed = sftp("changed")
        val renamed = sftp("renamed").copy(alias = "new-name")
        listOf(stable, changed, renamed).forEach { dao.insert(it) }
        val stream = captureStream()
        val legacy = captureLegacy()

        for ((index, data) in listOf(stream, legacy).withIndex()) {
            val localMounts = listOf(
                local("saf", "saf_tree"),
                local("disk", "real_path"),
                local("knowledge", "knowledge_base"),
            )
            val removed = sftp("removed")
            val before = localMounts + listOf(
                stable.copy(alias = renamed.alias),
                changed.copy(sourceId = sourceId("/before")),
                renamed.copy(alias = stable.alias),
                removed,
            )
            dao.all().forEach { dao.delete(it.id) }
            before.forEach { mount ->
                dao.insert(mount)
                dao.putSnapshot(snapshot(mount.id))
            }
            codec.decodeFrom(data.inputStream(), null, cache).use { source().restore(it, "restore-$index") }

            assertThat(dao.all()).containsExactlyElementsIn(localMounts + listOf(stable, changed, renamed).map { it.copy(unavailableReason = null) })
            (localMounts.map { it.id } + listOf("stable", "renamed")).forEach { id ->
                assertThat(dao.snapshot(id, "/file.txt", "root")).isEqualTo(snapshot(id))
            }
            assertThat(dao.snapshot("changed", "/file.txt", "root")).isNull()
            assertThat(dao.snapshot("removed", "/file.txt", "root")).isNull()
            assertThat(source().hasCompletedRestore("restore-$index")).isTrue()
        }
    }

    @Test
    fun `同版旧流式包和旧完整包缺段时清空 SFTP 配置和对应快照`() = runBlocking {
        val local = local("local")
        val mount = sftp("remote")
        dao.insert(local)
        dao.putSnapshot(snapshot(local.id))
        val stream = captureStream(includeMountTable = false)
        val legacy = captureLegacy { it.copy(tables = it.tables - table) }
        for ((index, data) in listOf(stream, legacy).withIndex()) {
            dao.insert(mount)
            dao.putSnapshot(snapshot(mount.id))
            codec.decodeFrom(data.inputStream(), null, cache).use {
                source().validateStaged(it)
                source().restore(it, "old-$index")
            }
            assertThat(dao.all()).containsExactly(local)
            assertThat(dao.snapshot(local.id, "/file.txt", "root")).isEqualTo(snapshot(local.id))
            assertThat(dao.snapshot(mount.id, "/file.txt", "root")).isNull()
        }
    }

    @Test
    fun `旧包空段沿用原恢复 marker 摘要`() = runBlocking {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = captureLegacy { payload ->
            val previousFormat = payload.copy(tables = payload.tables - table)
            previousFormat.tables.toSortedMap().forEach { (name, rows) ->
                digest.update(name.encodeToByteArray())
                rows.forEach { row ->
                    val canonical = row.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }
                    digest.update(canonical.encodeToByteArray())
                }
            }
            previousFormat
        }
        val operation = "legacy-fingerprint"
        codec.decode(bytes, null).use {
            val error = runCatching { source(RestoreCrashPoint.ROOM_COMMITTED).restore(it, operation) }.exceptionOrNull()
            assertThat(error).isInstanceOf(SimulatedRestoreProcessDeath::class.java)
        }
        val journal = FileRestoreJournal(restore, TestRestoreJournalAuthenticator).read()
        assertThat(journal?.expectedDatabaseFingerprint).isEqualTo(BackupStreams.hex(digest.digest()))
        source().recoverInterruptedRestore()
        assertThat(source().hasCompletedRestore(operation)).isTrue()
    }

    @Test
    fun `本机异源主键和作用域别名碰撞使恢复整体回滚`() = runBlocking {
        val imported = sftp("incoming")
        val agent = AgentEntity("agent", "包内助手", createdAt = 1)
        db.agentDao().insert(agent)
        dao.insert(imported)
        val stream = captureStream()
        val legacy = captureLegacy()
        dao.delete(imported.id)
        db.agentDao().update(agent.copy(name = "本机助手"))
        val remote = sftp("retained-on-failure")
        dao.insert(remote)
        dao.putSnapshot(snapshot(remote.id))
        val collisions = listOf(local(imported.id).copy(alias = "other"), local("other").copy(alias = imported.alias))
        for ((index, collision) in collisions.withIndex()) {
            dao.insert(collision)
            dao.putSnapshot(snapshot(collision.id))
            for ((format, bytes) in listOf(stream, legacy).withIndex()) {
                val operation = "collision-$index-$format"
                val error = codec.decodeFrom(bytes.inputStream(), null, cache).use {
                    runCatching { source().restore(it, operation) }.exceptionOrNull()
                }
                assertThat(error).isInstanceOf(BackupValidationException::class.java)
                assertThat(error?.message).contains("冲突")
                assertThat(source().isRestoreSettledWithoutApplying(operation)).isTrue()
                assertThat(db.agentDao().getAll().single().name).isEqualTo("本机助手")
                assertThat(dao.all()).containsExactly(remote, collision)
                assertThat(dao.snapshot(remote.id, "/file.txt", "root")).isEqualTo(snapshot(remote.id))
                assertThat(dao.snapshot(collision.id, "/file.txt", "root")).isEqualTo(snapshot(collision.id))
            }
            dao.delete(collision.id)
        }
    }

    @Test
    fun `拒绝非七列无效值重复键和未导出会话引用`() = runBlocking {
        dao.insert(sftp("remote"))
        val invalidRows = listOf<(JsonObject) -> List<JsonObject>>(
            { listOf(JsonObject(it + ("unavailable_reason" to JsonNull))) },
            { listOf(JsonObject(it - "created_at")) },
            { listOf(JsonObject(it + ("id" to JsonPrimitive("")))) },
            { listOf(JsonObject(it + ("alias" to JsonPrimitive("skills")))) },
            { listOf(JsonObject(it + ("source_type" to JsonPrimitive("real_path")))) },
            { listOf(JsonObject(it + ("source_id" to JsonPrimitive(sourceId("/../unsafe"))))) },
            { listOf(JsonObject(it + ("read_only" to JsonPrimitive(2)))) },
            { listOf(JsonObject(it + ("read_only" to JsonPrimitive("1")))) },
            { listOf(JsonObject(it + ("created_at" to JsonPrimitive(-1)))) },
            { listOf(it, JsonObject(it + ("alias" to JsonPrimitive("another")))) },
            { listOf(it, JsonObject(it + ("id" to JsonPrimitive("another")))) },
            { listOf(JsonObject(it + ("scope_key" to JsonPrimitive("missing-session")))) },
        )
        for ((index, transform) in invalidRows.withIndex()) {
            val bytes = captureLegacy { payload ->
                payload.copy(tables = payload.tables + (table to transform(payload.tables.getValue(table).single())))
            }
            val operation = "invalid-$index"
            val error = codec.decode(bytes, null).use { runCatching { source().restore(it, operation) }.exceptionOrNull() }
            assertThat(error).isInstanceOf(BackupValidationException::class.java)
            assertThat(error?.message).contains("SFTP")
            assertThat(source().isRestoreSettledWithoutApplying(operation)).isTrue()
            assertThat(dao.all()).containsExactly(sftp("remote"))
        }
    }

    @Test
    fun `流式包不能借本机未导出的会话通过作用域校验`() = runBlocking {
        db.sessionDao().insert(SessionEntity("local-session", "agent", createdAt = 1, updatedAt = 1))
        val captured = source().snapshotTo(core, cache)
        val path = Files.createTempFile(cache, "forged-sftp", ".jsonl")
        val mounted = sftp("injected", "local-session")
        val row = JsonObject(mapOf(
            "id" to JsonPrimitive(mounted.id), "alias" to JsonPrimitive(mounted.alias),
            "source_type" to JsonPrimitive(mounted.sourceType), "source_id" to JsonPrimitive(mounted.sourceId),
            "scope_key" to JsonPrimitive(mounted.scopeKey), "read_only" to JsonPrimitive(1), "created_at" to JsonPrimitive(7),
        ))
        val bytes = try {
            Files.writeString(path, "$row\n")
            val entries = captured.streamEntries.map { entry ->
                if (entry.path.startsWith(rowPrefix)) entry.copy(blob = BackupBlob.File(path)) else entry
            }
            encodeStream(captured.copy(streamEntries = entries))
        } finally {
            captured.wipe()
            Files.delete(path)
        }
        codec.decodeFrom(bytes.inputStream(), null, cache).use {
            val validation = runCatching { source().validateStaged(it) }.exceptionOrNull()
            assertThat(validation).isInstanceOf(BackupValidationException::class.java)
            val restoreError = runCatching { source().restore(it, "local-scope") }.exceptionOrNull()
            assertThat(restoreError).isInstanceOf(BackupValidationException::class.java)
            assertThat(restoreError?.message).contains("会话范围")
        }
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun `Room 提交前中断回滚 SFTP 挂载和快照且提交后可凭 marker 恢复`() = runBlocking {
        val incoming = sftp("incoming")
        dao.insert(incoming)
        val bytes = captureStream()
        dao.delete(incoming.id)
        val current = sftp("current")
        dao.insert(current)
        dao.putSnapshot(snapshot(current.id))

        val crashPoints = listOf(RestoreCrashPoint.EXTERNAL_COMMITTED, RestoreCrashPoint.ROOM_COMMITTED)
        for ((index, point) in crashPoints.withIndex()) {
            val operation = "crash-$index"
            val error = codec.decodeFrom(bytes.inputStream(), null, cache).use {
                runCatching { source(point).restore(it, operation) }.exceptionOrNull()
            }
            assertThat(error).isInstanceOf(SimulatedRestoreProcessDeath::class.java)
            source().recoverInterruptedRestore()
            val committed = point == RestoreCrashPoint.ROOM_COMMITTED
            assertThat(source().hasCompletedRestore(operation)).isEqualTo(committed)
            assertThat(dao.all()).containsExactly(if (committed) incoming.copy(unavailableReason = null) else current)
            assertThat(dao.snapshot(current.id, "/file.txt", "root")).isEqualTo(if (committed) null else snapshot(current.id))
        }
    }

    private fun source(crashPoint: RestoreCrashPoint? = null) = RoomBackupDataSource(
        db, EmptyPreferences, EmptySecrets, setOf(cache), restore, "test",
        crashHook = RestoreCrashHook { if (it == crashPoint) throw SimulatedRestoreProcessDeath(it) },
        journalAuthenticator = TestRestoreJournalAuthenticator,
        restoreFileOperations = TestRestoreFileOperations(),
    )

    private suspend fun captureStream(includeMountTable: Boolean = true): ByteArray {
        val captured = source().snapshotTo(core, cache)
        return try {
            encodeStream(if (includeMountTable) captured else captured.copy(streamEntries = captured.streamEntries.filterNot { it.path.startsWith(rowPrefix) }))
        } finally {
            captured.wipe()
        }
    }

    private fun encodeStream(captured: BackupSnapshot): ByteArray = ByteArrayOutputStream().use { output ->
        codec.encodeTo(captured, BackupOptions(content), output)
        output.toByteArray()
    }

    private suspend fun captureLegacy(transform: (DatabaseBackupPayload) -> DatabaseBackupPayload = { it }): ByteArray {
        val captured = source().snapshot(content)
        return try {
            val payload = Json.decodeFromString<DatabaseBackupPayload>(captured.database.decodeToString())
            codec.encode(captured.copy(database = Json.encodeToString(transform(payload)).encodeToByteArray()), BackupOptions(content))
        } finally {
            captured.wipe()
        }
    }

    private fun sftp(id: String, scope: String = LIBRARY_SCOPE, libraryId: String? = null) = ExternalMountEntity(
        id, id, SFTP_REMOTE_SOURCE_TYPE, sourceId("/remote", libraryId), scope, true, 7, "先前探测失败",
    )

    private fun sourceId(path: String, libraryId: String? = null): String {
        val columns = mapOf("v" to JsonPrimitive(1), "host_alias" to JsonPrimitive("missing-host"), "root_path" to JsonPrimitive(path))
        return JsonObject(if (libraryId == null) columns else columns + ("library_mount_id" to JsonPrimitive(libraryId))).toString()
    }

    private fun local(id: String, type: String = "real_path") =
        ExternalMountEntity(id, id, type, "device-source", LIBRARY_SCOPE, false, 3, "本机状态")

    private fun snapshot(id: String) = ExternalSnapshotEntity(id, "/file.txt", "root", "file-$id", 4, "hash-$id", 6)

    private object EmptyPreferences : TransactionalBackupPreferenceStore {
        override suspend fun snapshot(maxTotalBytes: Long) = BackupPreferenceSnapshot(emptyList(), emptySet())
        override suspend fun preflightRestore(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }

    private object EmptySecrets : TransactionalBackupSecretStore {
        override suspend fun ids() = emptySet<SecretId>()
        override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long) = emptyMap<SecretId, ByteArray>()
        override suspend fun prepare(txId: String, before: Map<SecretId, ByteArray>, after: Map<SecretId, ByteArray>) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }
}
