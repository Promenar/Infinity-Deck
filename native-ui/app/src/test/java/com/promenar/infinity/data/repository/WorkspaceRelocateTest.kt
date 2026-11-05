package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.DocumentIndexCandidate
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgSource
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.infra.util.Sha256Utils
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 复用仓储的 Room 与真实磁盘测试端口，验证单次移动而非两步补偿。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceRelocateTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var directory: File
    private lateinit var io: TestWorkspaceFileOps

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        io = TestWorkspaceFileOps()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = io,
            afterMoveCommitted = { KgCollectionRepository(db).reconcileDocuments() })
        directory = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".infinity-workspace-relocate").toFile()
    }

    @After fun cleanup() { com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting(); db.close(); directory.deleteRecursively() }


    @Test fun `relocate在隔离根下拒绝且文件仍可读取`() = runBlocking {
        val root = root()
        val source = file(root, "source", "a.txt", "source")
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.bindRoot(root.uuid, directory.canonicalPath, "s")
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, root.uuid, "工作区")) { error("冲突") }
        assertThat(runCatching { repo.relocate(root.uuid, source.uuid, root.uuid, "b.txt", source.name) }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("source")
        assertThat(File(directory, "b.txt").exists()).isFalse()
    }

    @Test fun `最终数据库目标已存在时源UUID路径与持久历史均未改变`() = runBlocking<Unit> {
        val root = root()
        val source = file(root, "source", "a.txt", "old")
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = io)
        files.writeFileAtomic(root.uuid, source.uuid, "current", "s", source.hash)
        val before = repo.getByUuid(root.uuid, source.uuid)!!
        val history = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val parent = folder(root, "docs")
        repo.createFileInWorkspace(root.uuid, "occupied", "b.txt", "destination", parent.uuid, "/docs/b.txt")
        val failure = runCatching { repo.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", source.name, before.hash) }.exceptionOrNull()
        assertThat((failure as WorkspaceLifecycleException).code).isEqualTo(WorkspaceLifecycleErrorCode.TARGET_COLLISION)
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isEqualTo(before)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(history)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("current")
        assertThat(File(directory, "docs/a.txt").exists()).isFalse()
        assertThat(File(directory, "docs/b.txt").readText()).isEqualTo("destination")
        assertThat(files.diffFile(root.uuid, source.uuid, source.hash).hunks).isNotEmpty()
    }

    @Test fun `最终物理目标未纳管但已存在时也不覆盖或半移动`() = runBlocking<Unit> {
        val root = root()
        val source = file(root, "source", "a.txt", "source")
        val parent = folder(root, "docs")
        File(directory, "docs/b.txt").writeText("untracked")
        val failure = runCatching { repo.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", source.name) }.exceptionOrNull()
        assertThat((failure as WorkspaceLifecycleException).code).isEqualTo(WorkspaceLifecycleErrorCode.TARGET_COLLISION)
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("source")
        assertThat(File(directory, "docs/a.txt").exists()).isFalse()
        assertThat(File(directory, "docs/b.txt").readText()).isEqualTo("untracked")
    }

    @Test fun `同时改名换目录直接到最终目标并保留UUID与可读取的旧版本`() = runBlocking<Unit> {
        val root = root()
        val source = file(root, "source", "a.txt", "old")
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = io)
        val written = files.writeFileAtomic(root.uuid, source.uuid, "current", "s", source.hash) as WriteResult.Success
        val history = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val parent = folder(root, "docs")
        repo.createFileInWorkspace(root.uuid, "intermediate", "a.txt", "unrelated", parent.uuid, "/docs/a.txt")
        val moved = repo.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", source.name, written.newHash) as RenameResult.Success
        val current = repo.getByUuid(root.uuid, source.uuid)!!
        assertThat(current.uuid).isEqualTo(source.uuid)
        assertThat(current.parentUuid).isEqualTo(parent.uuid)
        assertThat(current.materializedPath).isEqualTo("/docs/b.txt")
        assertThat(current.hash).isEqualTo(written.newHash)
        assertThat(moved.affectedTargets.single().fileUuid).isEqualTo(source.uuid)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(history)
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(File(directory, "docs/a.txt").readText()).isEqualTo("unrelated")
        assertThat(File(directory, "docs/b.txt").readText()).isEqualTo("current")
        assertThat(files.diffFile(root.uuid, source.uuid, source.hash).hunks).isNotEmpty()
    }

    @Test fun `数据库提交失败由现有journal移动原语回滚源而不留下中间路径`() = runBlocking<Unit> {
        val root = root()
        val source = file(root, "source", "a.txt", "source")
        val parent = folder(root, "docs")
        val failing = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = io, updateCommitter = { error("注入提交失败") })
        assertThat(runCatching { failing.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", source.name) }.isFailure).isTrue()
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("source")
        assertThat(File(directory, "docs/b.txt").exists()).isFalse()
        assertThat(File(directory, "docs/a.txt").exists()).isFalse()
    }

    @Test fun `目录移动一次更新后代路径且保留UUID并推进索引epoch`() = runBlocking<Unit> {
        val root = root()
        val source = folder(root, "source")
        val parent = folder(root, "destination")
        val child = repo.createFileInWorkspace(root.uuid, "child", "a.txt", "content", source.uuid, "/source/a.txt")
        db.fileEntryDao().update(child.copy(vectorizedAt = 10, kgExtractedAt = 10))
        val result = repo.relocate(root.uuid, source.uuid, parent.uuid, "renamed", "source") as RenameResult.Success
        val current = repo.getByUuid(root.uuid, child.uuid)!!
        assertThat(current.materializedPath).isEqualTo("/destination/renamed/a.txt")
        assertThat(current.parentUuid).isEqualTo(source.uuid)
        assertThat(current.hash).isEqualTo(child.hash)
        assertThat(current.updatedAt).isGreaterThan(child.updatedAt)
        assertThat(current.vectorizedAt).isNull()
        assertThat(current.kgExtractedAt).isNull()
        assertThat(result.affectedTargets.single().fileUuid).isEqualTo(child.uuid)
        assertThat(File(directory, "destination/renamed/a.txt").readText()).isEqualTo("content")
    }

    @Test fun `真实文档移动后成员校正将既有证据迁到目标作品`() = runBlocking<Unit> {
        val root = root()
        val from = folder(root, "source")
        val target = folder(root, "destination")
        val file = repo.createFileInWorkspace(root.uuid, "document", "a.txt", "content", from.uuid, "/source/a.txt")
        repo.confirmCreatedEntry(root.uuid, file.uuid)
        val source = KgNodeEntity(id = "source-node", name = "甲", type = "person", createdAt = 1)
        val destination = KgNodeEntity(id = "target-node", name = "乙", type = "person", createdAt = 1)
        val relation = KgEdgeEntity(id = "relation", sourceId = source.id, targetId = destination.id, relation = "认识", createdAt = 1)
        GraphStore(db).commitCandidate(DocumentIndexCandidate(emptyList(), listOf(source, destination), listOf(relation)),
            KgSource("document", docId = file.uuid, contentHash = file.hash, workspaceDocument = true))
        val previous = db.kgEdgeDao().getByDocId(file.uuid).single()
        val oldEvidence = db.kgEvidenceDao().getForEdges(listOf(previous.id)).single()
        val indexed = requireNotNull(db.fileEntryDao().findActiveByUuid(file.uuid))
        db.fileEntryDao().update(indexed.copy(vectorizedAt = 1, kgExtractedAt = 1))

        repo.relocate(root.uuid, file.uuid, target.uuid, file.name, file.name, file.hash)
        val movedFile = requireNotNull(db.fileEntryDao().findActiveByUuid(file.uuid))
        assertThat(movedFile.vectorizedAt).isNull()
        assertThat(movedFile.kgExtractedAt).isNull()
        val moved = db.kgEdgeDao().getByDocId(file.uuid).single()
        assertThat(moved.collectionId).isEqualTo("work:${target.uuid}")
        assertThat(db.kgNodeDao().getRawById(moved.sourceId)?.collectionId).isEqualTo(moved.collectionId)
        assertThat(db.kgNodeDao().getRawById(moved.targetId)?.collectionId).isEqualTo(moved.collectionId)
        val movedEvidence = db.kgEvidenceDao().getForEdges(listOf(moved.id)).single()
        assertThat(movedEvidence.docId).isEqualTo(oldEvidence.docId)
        assertThat(movedEvidence.contentHash).isEqualTo(oldEvidence.contentHash)
        assertThat(movedEvidence.sourceType).isEqualTo(oldEvidence.sourceType)
        assertThat(db.kgNodeDao().getRawById(previous.sourceId)).isNull()
        assertThat(db.kgEdgeDao().getRawById(previous.id)).isNull()
    }

    @Test fun `名称或真实内容的版本条件不匹配时不移动`() = runBlocking<Unit> {
        val root = root()
        val source = file(root, "source", "a.txt", "source")
        val parent = folder(root, "docs")
        assertThat(repo.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", "stale")).isInstanceOf(RenameResult.Conflict::class.java)
        File(directory, "a.txt").writeText("external")
        assertThat(runCatching { repo.relocate(root.uuid, source.uuid, parent.uuid, "b.txt", "a.txt", source.hash) }.isFailure).isTrue()
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("external")
        assertThat(File(directory, "docs/b.txt").exists()).isFalse()
    }

    private suspend fun root(): FileEntry {
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = directory.absolutePath, createdAt = 1, updatedAt = 1))
        return repo.ensureSessionRoot("s")
    }
    private suspend fun folder(root: FileEntry, name: String) = repo.createDirectoryInWorkspace(root.uuid, name, name, root.uuid, "/$name")
    private suspend fun file(root: FileEntry, uuid: String, name: String, content: String) =
        repo.createFileInWorkspace(root.uuid, uuid, name, content, root.uuid, "/$name").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
}
