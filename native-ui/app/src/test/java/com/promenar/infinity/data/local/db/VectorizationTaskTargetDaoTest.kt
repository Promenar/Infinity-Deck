package com.promenar.infinity.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertOutcome
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VectorizationTaskTargetDaoTest {
    private lateinit var database: InfinityDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            InfinityDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `upsert target implements insert newer idempotent older and conflict contract`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        val epochOne = task("task-1", "hash-1", 10)

        val inserted = dao.upsertTarget(epochOne)
        assertThat(inserted.outcome).isEqualTo(VectorizationTaskTargetUpsertOutcome.INSERTED)
        assertThat(inserted.activeTaskId).isEqualTo("task-1")

        val idempotent = dao.upsertTarget(epochOne.copy(id = "same-target-new-id", status = "processing"))
        assertThat(idempotent.outcome).isEqualTo(VectorizationTaskTargetUpsertOutcome.IDEMPOTENT)
        assertThat(idempotent.activeTaskId).isEqualTo("task-1")
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)?.status).isEqualTo("pending")

        val older = task("older", "hash-0", 9)
        val ignored = dao.upsertTarget(older)
        assertThat(ignored.outcome).isEqualTo(VectorizationTaskTargetUpsertOutcome.IGNORED_OLDER)
        assertThat(ignored.activeTaskId).isEqualTo("task-1")
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)?.id).isEqualTo("task-1")

        val newer = task("task-2", "hash-2", 11)
        val replaced = dao.upsertTarget(newer)
        assertThat(replaced.outcome).isEqualTo(VectorizationTaskTargetUpsertOutcome.REPLACED_NEWER)
        assertThat(replaced.activeTaskId).isEqualTo("task-2")
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)).isEqualTo(newer)

        val conflict = runCatching { dao.upsertTarget(task("conflict", "other-hash", 11)) }
        assertThat(conflict.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)).isEqualTo(newer)
    }

    @Test
    fun `旧路径事件在文件移动事务后入队时采用当前版本`() = runTest {
        seedFile()
        val files = database.fileEntryDao()
        val before = requireNotNull(files.getByUuid(ROOT, DOC))
        files.update(before.copy(name = "renamed.txt", materializedPath = "/renamed.txt", updatedAt = 2))
        val dao = database.vectorizationTaskDao()
        dao.upsertTarget(task("late", before.hash, 1))
        val queued = requireNotNull(dao.getById("late"))
        assertThat(queued.targetEpoch).isEqualTo(2)
        assertThat(queued.targetContentHash).isEqualTo(before.hash)
        assertThat(queued.docTitle).isEqualTo("renamed.txt")
    }

    @Test
    fun `document reference target entry rejects missing hash or nonpositive epoch while legacy rows remain valid`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()

        assertThat(runCatching { dao.upsertTarget(task("missing-hash", null, 1)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { dao.upsertTarget(task("missing-epoch", "hash", 0)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)

        val legacyMemory = VectorizationTaskEntity(
            id = "legacy-memory",
            type = "memory",
            status = "pending",
            createdAt = 1,
            updatedAt = 1,
        )
        assertThat(dao.insertIgnore(legacyMemory)).isNotEqualTo(-1)
        assertThat(dao.getById(legacyMemory.id)?.targetContentHash).isNull()
        assertThat(dao.getById(legacyMemory.id)?.targetEpoch).isEqualTo(0)
        assertThat(
            runCatching {
                dao.upsertTarget(legacyMemory.copy(targetContentHash = "hash", targetEpoch = 1))
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `incoming主键已属于另一target时稳定失败且不覆盖任一行`() = runTest {
        seedFile()
        database.fileEntryDao().insert(file(ROOT, DOC_OTHER, isDirectory = false))
        val dao = database.vectorizationTaskDao()
        dao.upsertTarget(task("active", "hash-active", 10))
        dao.upsertTarget(task("colliding-id", "hash-other", 10, DOC_OTHER))

        val failure = runCatching {
            dao.upsertTarget(task("colliding-id", "hash-new", 11))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)?.id).isEqualTo("active")
        assertThat(dao.getByWorkspaceFile(ROOT, DOC_OTHER, TYPE)?.id).isEqualTo("colliding-id")
    }

    @Test
    fun `并发双writer最终仅最高epoch成为active target`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        dao.upsertTarget(task("base", "hash-1", 1))

        coroutineScope {
            listOf(
                async(Dispatchers.Default) { dao.upsertTarget(task("epoch-2", "hash-2", 2)) },
                async(Dispatchers.Default) { dao.upsertTarget(task("epoch-3", "hash-3", 3)) },
            ).awaitAll()
        }

        val active = dao.getByWorkspaceFile(ROOT, DOC, TYPE)
        assertThat(active?.id).isEqualTo("epoch-3")
        assertThat(active?.targetContentHash).isEqualTo("hash-3")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(dao.getByDocId(DOC)).hasSize(1)
    }

    @Test
    fun `target CAS status and delete reject stale workers`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        dao.upsertTarget(task("task", "hash-2", 2))

        assertThat(dao.updateStatusForTarget("task", "hash-1", 1, "completed", 20)).isEqualTo(0)
        assertThat(dao.deleteForTarget("task", "hash-1", 1)).isEqualTo(0)
        assertThat(dao.getById("task")?.status).isEqualTo("pending")

        assertThat(dao.updateStatusForTarget("task", "hash-2", 2, "completed", 21)).isEqualTo(1)
        assertThat(dao.deleteForTarget("task", "hash-2", 2)).isEqualTo(1)
        assertThat(dao.getById("task")).isNull()
    }

    @Test
    fun `legacy空target通过IS NULL CAS升级为合法新target`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        val legacy = task("legacy", null, 0)
        assertThat(dao.insertIgnore(legacy)).isNotEqualTo(-1)

        val replacement = task("active-v1", "hash-v1", 1)
        val result = dao.upsertTarget(replacement)

        assertThat(result.outcome).isEqualTo(VectorizationTaskTargetUpsertOutcome.REPLACED_NEWER)
        assertThat(result.activeTaskId).isEqualTo("active-v1")
        assertThat(dao.getById("legacy")).isNull()
        val active = dao.getByWorkspaceFile(ROOT, DOC, TYPE)
        assertThat(active?.id).isEqualTo("active-v1")
        assertThat(active?.targetContentHash).isEqualTo("hash-v1")
        assertThat(active?.targetEpoch).isEqualTo(1)
    }

    @Test
    fun `同target tuple的旧task id不能把active主键改回`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        val active = task("active-id", "hash-v1", 1)
        dao.upsertTarget(active)

        val changed = dao.updateForTarget(
            active.copy(id = "stale-worker-id", status = "failed", error = "stale"),
        )

        assertThat(changed).isEqualTo(0)
        assertThat(dao.getById("stale-worker-id")).isNull()
        assertThat(dao.getById("active-id")?.status).isEqualTo("pending")
    }

    @Test
    fun `legacy到reference迁移在Room事务内原子替换`() = runTest {
        seedFile()
        val dao = database.vectorizationTaskDao()
        val legacy = VectorizationTaskEntity(
            id = "legacy-document",
            type = "document",
            status = "saving",
            docId = DOC,
            workspaceRootUuid = ROOT,
            userContent = "legacy",
            createdAt = 1,
            updatedAt = 1,
        )
        dao.insert(legacy)
        val replacement = task("reference", "hash-current", 2)

        val migrated = dao.migrateLegacyToTarget(legacy.id, replacement)

        assertThat(migrated?.activeTaskId).isEqualTo("reference")
        assertThat(dao.getById(legacy.id)).isNull()
        assertThat(dao.getByWorkspaceFile(ROOT, DOC, TYPE)).isEqualTo(replacement)
        assertThat(dao.getByDocId(DOC)).containsExactly(replacement)
    }

    @Test
    fun `workspace多doc任务使用单批量SQL删除`() = runTest {
        seedFile()
        database.fileEntryDao().insert(file(ROOT, DOC_OTHER, isDirectory = false))
        val dao = database.vectorizationTaskDao()
        dao.upsertTarget(task("first", "hash-1", 1))
        dao.upsertTarget(task("second", "hash-2", 2, DOC_OTHER))

        val deleted = dao.deleteByWorkspaceFiles(ROOT, listOf(DOC, DOC_OTHER))

        assertThat(deleted).isEqualTo(2)
        assertThat(dao.getByDocId(DOC)).isEmpty()
        assertThat(dao.getByDocId(DOC_OTHER)).isEmpty()
    }

    private suspend fun seedFile() {
        database.fileEntryDao().insert(file(ROOT, ROOT, isDirectory = true))
        database.fileEntryDao().insert(file(ROOT, DOC, isDirectory = false))
    }

    private fun file(root: String, uuid: String, isDirectory: Boolean) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = root,
        parentUuid = if (isDirectory) null else root,
        name = uuid,
        hash = "hash-$uuid",
        isDirectory = isDirectory,
        physicalRootPath = "/tmp/$root",
        materializedPath = if (isDirectory) "/" else "/doc.txt",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun task(id: String, hash: String?, epoch: Long, docId: String = DOC) = VectorizationTaskEntity(
        id = id,
        type = TYPE,
        status = "pending",
        docId = docId,
        workspaceRootUuid = ROOT,
        targetContentHash = hash,
        targetEpoch = epoch,
        createdAt = epoch,
        updatedAt = epoch,
    )

    private companion object {
        const val ROOT = "root"
        const val DOC = "doc"
        const val DOC_OTHER = "doc-other"
        const val TYPE = "document_reference"
    }
}
