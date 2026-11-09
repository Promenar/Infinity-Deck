package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.seedKgNode
import com.promenar.infinity.data.local.db.seedKgEdge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.infra.util.Sha256Utils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var rootA: File
    private lateinit var rootB: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )
        val projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"))
        rootA = Files.createTempDirectory(projectRoot, ".infinity-workspace-a").toFile()
        rootB = Files.createTempDirectory(projectRoot, ".infinity-workspace-b").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        rootA.deleteRecursively()
        rootB.deleteRecursively()
    }

    private suspend fun insertSession(id: String, path: String?) {
        val now = System.currentTimeMillis()
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                agentId = "agent",
                workspacePath = path,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `Android权限与原生移动errno返回具体原因且不回显路径`() {
        val errors = listOf(
            android.system.ErrnoException("/private/hidden/open", 13) to "权限不足",
            android.system.ErrnoException("/private/hidden/open", 2) to "条目不存在",
            java.io.IOException("不覆盖移动失败(errno=13)，未降低文件身份保证") to "权限不足",
            java.io.IOException("不覆盖移动失败(errno=18)，未降低文件身份保证") to "跨文件系统",
            java.io.IOException("同步移动源目录失败(errno=13)，未降低文件身份保证") to "IOException",
            java.io.IOException("不覆盖移动失败(errno=13)，未降低文件身份保证 /private/hidden") to "IOException",
        )
        for ((failure, reason) in errors) {
            val result = com.promenar.infinity.ui.chat.manager.skills.workspaceMutationFailureResult("move_file", failure)
            assertThat(result.status).isEqualTo("error")
            com.google.common.truth.Truth.assertWithMessage("异常类型=${failure.javaClass.simpleName}")
                .that(result.content).contains(reason)
            assertThat(result.content).doesNotContain("/private/hidden")
        }
    }

    @Test
    fun `同父目录移动不执行物理移动且保留条目时间戳`() = runBlocking<Unit> {
        insertSession("same-parent", rootA.absolutePath)
        val delegate = TestWorkspaceFileOps()
        var moves = 0
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = object : WorkspaceFileOps by delegate {
            override fun move(root: java.nio.file.Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
                moves++
                return delegate.move(root, source, target)
            }
        })
        val root = repo.ensureSessionRoot("same-parent")
        val file = repo.createFileInWorkspace(root.uuid, "same-file", "a.txt", "content", root.uuid, "/a.txt")
        repo.updateParent(root.uuid, file.uuid, root.uuid)
        assertThat(moves).isEqualTo(0)
        assertThat(repo.getByUuid(root.uuid, file.uuid)).isEqualTo(file)
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("content")
    }

    @Test
    fun `带哈希永久删除核验真实内容并在版本冲突时保留文件`() = runBlocking<Unit> {
        insertSession("hash-delete", rootA.absolutePath)
        val root = repo.ensureSessionRoot("hash-delete")
        val file = repo.createFileInWorkspace(root.uuid, "hash-file", "a.txt", "original", root.uuid, "/a.txt")
        repo.confirmCreatedEntry(root.uuid, file.uuid)
        val physical = File(root.physicalRootPath, "a.txt")
        physical.writeText("changed")
        try { repo.permanentDeleteIfHash(root.uuid, file.uuid, Sha256Utils.hash("original")); error("应拒绝版本冲突") }
        catch (failure: IllegalStateException) { assertThat(failure.message).contains("expected_sha256") }
        assertThat(physical.readText()).isEqualTo("changed")
        assertThat(repo.getByUuid(root.uuid, file.uuid)).isNotNull()
        repo.permanentDeleteIfHash(root.uuid, file.uuid, Sha256Utils.hash("changed"))
        assertThat(physical.exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNull()
    }

    @Test
    fun `永久删除提交后通知路径且事务失败时不通知`() = runBlocking<Unit> {
        insertSession("delete-notice", rootA.absolutePath)
        val root = repo.ensureSessionRoot("delete-notice")
        val done = repo.createFileInWorkspace(root.uuid, "done", "done.txt", "A", root.uuid, "/done.txt")
        val failed = repo.createFileInWorkspace(root.uuid, "failed", "failed.txt", "B", root.uuid, "/failed.txt")
        val notices = mutableListOf<Pair<String, String>>()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            afterDeletePathCommitted = { rootId, path -> notices += rootId to path })
        repo.permanentDelete(root.uuid, done.uuid)
        assertThat(notices).containsExactly(root.uuid to "/done.txt")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = { _, _ -> error("注入事务失败") },
            afterDeletePathCommitted = { rootId, path -> notices += rootId to path })
        assertThat(runCatching { repo.permanentDelete(root.uuid, failed.uuid) }.isFailure).isTrue()
        assertThat(notices).containsExactly(root.uuid to "/done.txt")
        assertThat(File(rootA, "failed.txt").readText()).isEqualTo("B")
    }

    @Test
    fun `所有文件生命周期操作接入统一journal并成功完成不留悬挂记录`() = runBlocking<Unit> {
        insertSession("journal-session", rootA.absolutePath)
        val journal = RecordingWorkspaceFileMutationJournal()
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            mutationJournal = journal,
        )
        val root = repo.ensureSessionRoot("journal-session")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "a", root.uuid, "/a.txt")
        repo.createFileInWorkspaceStreaming(
            root.uuid,
            "stream",
            "stream.bin",
            "application/octet-stream",
            root.uuid,
            "/stream.bin",
            64,
        ) { it.write(byteArrayOf(1, 2, 3)) }
        repo.rename(root.uuid, file.uuid, "b.txt", "a.txt")
        repo.updateParent(root.uuid, file.uuid, folder.uuid)
        repo.permanentDelete(root.uuid, file.uuid)

        assertThat(journal.preparedTypes).containsAtLeast(
            WorkspaceMutationType.MKDIR,
            WorkspaceMutationType.CREATE,
            WorkspaceMutationType.CREATE_STREAMING,
            WorkspaceMutationType.RENAME,
            WorkspaceMutationType.MOVE,
            WorkspaceMutationType.DELETE,
        )
        assertThat(journal.openOperations).isEmpty()
        assertThat(journal.markedCommitted).containsExactlyElementsIn(journal.completed)
    }

    @Test
    fun `move数据库提交失败且物理回滚失败时保留journal供启动恢复`() = runBlocking<Unit> {
        insertSession("journal-move-failure", rootA.absolutePath)
        val journal = RecordingWorkspaceFileMutationJournal()
        val delegate = TestWorkspaceFileOps()
        val rollbackFailingOps = object : WorkspaceFileOps by delegate {
            override fun move(
                root: java.nio.file.Path,
                source: List<String>,
                target: List<String>,
            ): WorkspaceFileRollback {
                val actual = delegate.move(root, source, target)
                return object : WorkspaceFileRollback {
                    override fun commit() = actual.commit()
                    override fun rollback() = throw IllegalStateException("injected move rollback failure")
                }
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = rollbackFailingOps,
            updateCommitter = { throw IllegalStateException("injected update failure") },
            mutationJournal = journal,
        )
        val root = repo.ensureSessionRoot("journal-move-failure")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")

        val failure = runCatching { repo.rename(root.uuid, file.uuid, "b.txt", "a.txt") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(journal.openOperations).hasSize(1)
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)!!.materializedPath).isEqualTo("/a.txt")
    }

    @Test
    fun `delete数据库提交失败且tombstone回滚失败时保留journal供启动恢复`() = runBlocking<Unit> {
        insertSession("journal-delete-failure", rootA.absolutePath)
        val root = repo.ensureSessionRoot("journal-delete-failure")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val journal = RecordingWorkspaceFileMutationJournal()
        val delegate = TestWorkspaceFileOps()
        val rollbackFailingOps = object : WorkspaceFileOps by delegate {
            override fun stageDelete(
                root: java.nio.file.Path,
                source: List<String>,
                deletionToken: String,
            ): WorkspaceFileRollback {
                val actual = delegate.stageDelete(root, source, deletionToken)
                return object : WorkspaceFileRollback {
                    override fun commit() = actual.commit()
                    override fun rollback() = throw IllegalStateException("injected delete rollback failure")
                }
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = rollbackFailingOps,
            deleteCommitter = { _, _ -> throw IllegalStateException("injected delete transaction failure") },
            mutationJournal = journal,
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, file.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(journal.openOperations).hasSize(1)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNotNull()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, ".infinity_tombstones").walkTopDown().any { it.isFile }).isTrue()
    }

    @Test
    fun `ensureSessionRoot creates safe default path when session path is absent`() = runBlocking<Unit> {
        insertSession("session-without-path", null)
        val parent = File(rootA, "sessions")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), parent, TestWorkspaceFileOps())

        val root = repo.ensureSessionRoot("session-without-path")

        assertThat(File(root.physicalRootPath).parentFile).isEqualTo(parent.canonicalFile)
        assertThat(File(root.physicalRootPath).name).isEqualTo(Sha256Utils.hash("session-without-path"))
        assertThat(db.sessionDao().getById("session-without-path")!!.workspacePath)
            .isEqualTo(root.physicalRootPath)
    }

    @Test
    fun `已进入的流式上传持有工作区租约直到物理写入与记录提交完成`() = runBlocking<Unit> {
        insertSession("stream-session", rootA.absolutePath)
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            executionGate = gate,
        )
        val root = repo.ensureSessionRoot("stream-session")
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val upload = async(Dispatchers.IO) {
            repo.createFileInWorkspaceStreaming(
                workspaceRootUuid = root.uuid,
                uuid = "stream-file",
                name = "stream.bin",
                mimeType = "application/octet-stream",
                parentUuid = root.uuid,
                materializedPath = "/stream.bin",
                maxBytes = 1024,
            ) { output ->
                writerEntered.countDown()
                check(releaseWriter.await(5, TimeUnit.SECONDS)) { "测试写入未获释放" }
                output.write("payload".toByteArray())
            }
        }
        assertThat(writerEntered.await(5, TimeUnit.SECONDS)).isTrue()
        var deletionEntered = false
        val deleting = async {
            gate.withDeletion("stream-session", root.uuid) { deletionEntered = true }
        }
        while (!gate.isDeleting("stream-session")) kotlinx.coroutines.yield()

        assertThat(deletionEntered).isFalse()
        releaseWriter.countDown()
        val created = upload.await()
        deleting.await()

        assertThat(deletionEntered).isTrue()
        assertThat(created.uuid).isEqualTo("stream-file")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, "stream-file")).isNotNull()
    }

    @Test
    fun `认领与复核会话根目录不推进会话的最近消息时间`() = runBlocking<Unit> {
        // 首页按 updatedAt 排序；打开文件面板会复核根目录，不能把只是被打开过的会话顶到最前。
        val activity = 1_000L
        db.sessionDao().insert(
            SessionEntity(id = "quiet", agentId = "agent", workspacePath = rootA.absolutePath, createdAt = activity, updatedAt = activity),
        )

        repo.ensureSessionRoot("quiet")
        assertThat(db.sessionDao().getById("quiet")!!.updatedAt).isEqualTo(activity)

        repo.ensureSessionRoot("quiet")
        assertThat(db.sessionDao().getById("quiet")!!.updatedAt).isEqualTo(activity)
    }

    @Test
    fun `ensureSessionRoot is concurrent idempotent and backfills session`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)

        val roots = List(16) { async(Dispatchers.Default) { repo.ensureSessionRoot("session-a") } }.awaitAll()

        assertThat(roots.map { it.uuid }.distinct()).hasSize(1)
        val root = roots.first()
        assertThat(root.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(root.isDirectory).isTrue()
        assertThat(root.parentUuid).isNull()
        assertThat(root.materializedPath).isEqualTo("/")
        assertThat(db.sessionDao().getById("session-a")!!.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(repo.observeRoots(root.uuid).first()).containsExactly(root)
    }

    @Test
    fun `覆盖安装会为应用私有目录中的旧版空 hash 根补建身份并保留认领`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val legacyPhysicalRoot = File(trustedParent, Sha256Utils.hash("legacy-session")).apply { mkdirs() }
        val existingUserFile = File(legacyPhysicalRoot, "legacy-note.txt").apply { writeText("keep me") }
        insertSession("legacy-session", legacyPhysicalRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "legacy-root",
            workspaceRootUuid = "legacy-root",
            parentUuid = null,
            name = legacyPhysicalRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = File(legacyPhysicalRoot, ".").path,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("legacy-session")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val migrated = repo.ensureSessionRoot("legacy-session")

        assertThat(migrated.uuid).isEqualTo(legacyRoot.uuid)
        assertThat(migrated.hash).isNotEmpty()
        assertThat(migrated.physicalRootPath).isEqualTo(legacyPhysicalRoot.canonicalPath)
        assertThat(File(legacyPhysicalRoot, ".infinity_root_identity").isFile).isTrue()
        assertThat(existingUserFile.readText()).isEqualTo("keep me")
        assertThat(db.fileEntryDao().getByUuid(legacyRoot.uuid, legacyRoot.uuid)!!.hash)
            .isEqualTo(migrated.hash)
    }

    @Test
    fun `公开v17会话工作区按旧目录和精确session叶名原位补建身份`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val publishedParent = File(rootA, "workspaces").apply { mkdirs() }
        val legacyPhysicalRoot = File(publishedParent, "published-session").apply { mkdirs() }
        val existingUserFile = File(legacyPhysicalRoot, "legacy-note.txt").apply { writeText("keep me") }
        insertSession("published-session", legacyPhysicalRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "published-root",
            workspaceRootUuid = "published-root",
            parentUuid = null,
            name = legacyPhysicalRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = legacyPhysicalRoot.canonicalPath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("published-session")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val migrated = repo.ensureSessionRoot("published-session")

        assertThat(migrated.uuid).isEqualTo(legacyRoot.uuid)
        assertThat(migrated.hash).isNotEmpty()
        assertThat(File(legacyPhysicalRoot, ".infinity_root_identity").isFile).isTrue()
        assertThat(existingUserFile.readText()).isEqualTo("keep me")
    }

    @Test
    fun `公开v17旧目录叶名与session不一致时拒绝补建身份`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val wrongRoot = File(File(rootA, "workspaces"), "another-session").apply { mkdirs() }
        insertSession("published-session", wrongRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "wrong-published-root",
            workspaceRootUuid = "wrong-published-root",
            parentUuid = null,
            name = wrongRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = wrongRoot.canonicalPath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("published-session")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), trustedParent, TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("published-session") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(wrongRoot, ".infinity_root_identity").exists()).isFalse()
    }

    @Test
    fun `覆盖安装不得沿旧根符号链接在应用私有目录外补建身份`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces").apply { mkdirs() }
        val externalRoot = File(rootB, "external-workspace").apply { mkdirs() }
        val linkedRoot = File(trustedParent, Sha256Utils.hash("linked-session"))
        Files.createSymbolicLink(linkedRoot.toPath(), externalRoot.toPath())
        insertSession("linked-session", linkedRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "linked-root",
            workspaceRootUuid = "linked-root",
            parentUuid = null,
            name = linkedRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = linkedRoot.absolutePath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("linked-session")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("linked-session") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(externalRoot, ".infinity_root_identity").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(legacyRoot.uuid, legacyRoot.uuid)!!.hash).isEmpty()
    }

    @Test
    fun `覆盖安装不得沿旧知识库根符号链接在应用私有目录外补建身份`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces").apply { mkdirs() }
        val externalRoot = File(rootB, "external-rag-workspace").apply { mkdirs() }
        val linkedRoot = File(rootA, "rag_workspace")
        Files.createSymbolicLink(linkedRoot.toPath(), externalRoot.toPath())
        insertSession("__infinity_rag_workspace__", linkedRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "linked-rag-root",
            workspaceRootUuid = "linked-rag-root",
            parentUuid = null,
            name = linkedRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = linkedRoot.absolutePath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("__infinity_rag_workspace__")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("__infinity_rag_workspace__") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(externalRoot, ".infinity_root_identity").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(legacyRoot.uuid, legacyRoot.uuid)!!.hash).isEmpty()
    }

    @Test
    fun `覆盖安装会原位迁移精确的旧知识库根`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val legacyPhysicalRoot = File(rootA, "rag_workspace").apply { mkdirs() }
        val existingUserFile = File(legacyPhysicalRoot, "knowledge.txt").apply { writeText("knowledge") }
        insertSession("__infinity_rag_workspace__", legacyPhysicalRoot.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "legacy-rag-root",
            workspaceRootUuid = "legacy-rag-root",
            parentUuid = null,
            name = legacyPhysicalRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = legacyPhysicalRoot.canonicalPath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("__infinity_rag_workspace__")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val migrated = repo.ensureSessionRoot("__infinity_rag_workspace__")

        assertThat(migrated.uuid).isEqualTo(legacyRoot.uuid)
        assertThat(migrated.hash).isNotEmpty()
        assertThat(File(legacyPhysicalRoot, ".infinity_root_identity").isFile).isTrue()
        assertThat(existingUserFile.readText()).isEqualTo("knowledge")
    }

    @Test
    fun `悬空旧根引用不得在受信目录留下身份标记`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val physicalRoot = File(trustedParent, Sha256Utils.hash("dangling-session")).apply { mkdirs() }
        insertSession("dangling-session", physicalRoot.absolutePath)
        db.sessionDao().update(
            db.sessionDao().getById("dangling-session")!!.copy(workspaceRootUuid = "missing-root"),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("dangling-session") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(physicalRoot, ".infinity_root_identity").exists()).isFalse()
    }

    @Test
    fun `非根旧记录不得在受信目录留下身份标记`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        val physicalRoot = File(trustedParent, Sha256Utils.hash("child-session")).apply { mkdirs() }
        insertSession("child-session", physicalRoot.absolutePath)
        val now = System.currentTimeMillis()
        val invalidRoot = FileEntry(
            uuid = "child-record",
            workspaceRootUuid = "child-record",
            parentUuid = "unexpected-parent",
            name = physicalRoot.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = physicalRoot.canonicalPath,
            materializedPath = "/nested",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(invalidRoot)
        db.sessionDao().update(
            db.sessionDao().getById("child-session")!!.copy(workspaceRootUuid = invalidRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("child-session") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(physicalRoot, ".infinity_root_identity").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(invalidRoot.uuid, invalidRoot.uuid)!!.hash).isEmpty()
    }

    @Test
    fun `覆盖安装不得认领应用私有目录之外的旧版空 hash 根`() = runBlocking<Unit> {
        val trustedParent = File(rootA, "session_workspaces")
        insertSession("untrusted-session", rootB.absolutePath)
        val now = System.currentTimeMillis()
        val legacyRoot = FileEntry(
            uuid = "untrusted-root",
            workspaceRootUuid = "untrusted-root",
            parentUuid = null,
            name = rootB.name,
            hash = "",
            isDirectory = true,
            physicalRootPath = rootB.canonicalPath,
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        db.fileEntryDao().insert(legacyRoot)
        db.sessionDao().update(
            db.sessionDao().getById("untrusted-session")!!.copy(workspaceRootUuid = legacyRoot.uuid),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            trustedParent,
            TestWorkspaceFileOps(),
        )

        val failure = runCatching { repo.ensureSessionRoot("untrusted-session") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(rootB, ".infinity_root_identity").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(legacyRoot.uuid, legacyRoot.uuid)!!.hash).isEmpty()
    }

    @Test
    fun `scoped reads and writes never cross workspace roots`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        insertSession("session-b", rootB.absolutePath)
        val a = repo.ensureSessionRoot("session-a")
        val b = repo.ensureSessionRoot("session-b")
        val childA = repo.createFileInWorkspace(a.uuid, "a-file", "a.txt", "A", a.uuid, "/a.txt")
        val childB = repo.createFileInWorkspace(b.uuid, "b-file", "b.txt", "B", b.uuid, "/b.txt")

        assertThat(repo.getByUuid(a.uuid, childA.uuid)).isEqualTo(childA)
        assertThat(repo.getByUuid(a.uuid, childB.uuid)).isNull()
        assertThat(repo.observeChildren(a.uuid, a.uuid).first()).containsExactly(childA)
        assertThat(repo.observeChildren(a.uuid, b.uuid).first()).isEmpty()
        assertThat(repo.searchByName(a.uuid, ".txt").first()).containsExactly(childA)
        assertThat(repo.searchByName(a.uuid, "b.txt").first()).isEmpty()
        assertThat(repo.getByMaterializedPath(a.uuid, "/b.txt")).isNull()
        assertThat(repo.getSubtree(a.uuid, "/")).containsExactly(a, childA)

        repo.rename(a.uuid, childA.uuid, "renamed.txt")
        assertThat(repo.getByMaterializedPath(a.uuid, "/renamed.txt")!!.name).isEqualTo("renamed.txt")
        assertThat(File(rootA, "renamed.txt").readText()).isEqualTo("A")

        var rejected = false
        try {
            repo.createFileInWorkspace(a.uuid, "cross", "x.txt", "x", b.uuid, "/x.txt")
        } catch (_: SecurityException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
    }

    @Test
    fun `rename CAS success returns committed name hash epoch and changed`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val result = repo.rename(root.uuid, entry.uuid, "b.txt", expectedName = "a.txt")

        assertThat(result).isInstanceOf(RenameResult.Success::class.java)
        result as RenameResult.Success
        assertThat(result.name).isEqualTo("b.txt")
        assertThat(result.targetHash).isEqualTo(entry.hash)
        assertThat(result.targetEpoch).isGreaterThan(entry.updatedAt)
        assertThat(result.changed).isTrue()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!.updatedAt)
            .isEqualTo(result.targetEpoch)
    }

    @Test
    fun `rename CAS conflict leaves physical file and database unchanged`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "actual.txt", "A", root.uuid, "/actual.txt")

        val result = repo.rename(root.uuid, entry.uuid, "local.txt", expectedName = "stale.txt")

        assertThat(result).isEqualTo(
            RenameResult.Conflict(expected = "stale.txt", current = "actual.txt"),
        )
        assertThat(File(rootA, "actual.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "local.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isEqualTo(entry)
    }

    @Test
    fun `rename missing target returns NotFound without physical mutation`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")

        val result = repo.rename(root.uuid, "missing", "new.txt", expectedName = "old.txt")

        assertThat(result).isEqualTo(RenameResult.NotFound)
        assertThat(rootA.listFiles().orEmpty().map { it.name })
            .containsNoneOf("old.txt", "new.txt")
    }

    @Test
    fun `rename default expectedName keeps explicit unconditional behavior`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        assertThat(entry.mimeType).isEqualTo("text/plain")

        val result = repo.rename(root.uuid, entry.uuid, "b.txt")

        assertThat(result).isInstanceOf(RenameResult.Success::class.java)
        assertThat((result as RenameResult.Success).changed).isTrue()
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("A")
    }

    @Test
    fun `rename to current name is idempotent no-op without epoch advance or move`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val delegate = TestWorkspaceFileOps()
        var moveCount = 0
        val countingOps = object : WorkspaceFileOps by delegate {
            override fun move(
                root: java.nio.file.Path,
                source: List<String>,
                target: List<String>,
            ): WorkspaceFileRollback {
                moveCount++
                return delegate.move(root, source, target)
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = countingOps,
        )

        val result = repo.rename(root.uuid, entry.uuid, "a.txt", expectedName = "stale-retry.txt")

        assertThat(result).isEqualTo(
            RenameResult.Success(
                name = "a.txt",
                targetHash = entry.hash,
                targetEpoch = entry.updatedAt,
                changed = false,
            ),
        )
        assertThat(moveCount).isEqualTo(0)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isEqualTo(entry)
    }

    @Test
    fun `rename occupied target fails without partial physical or database move`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val source = repo.createFileInWorkspace(root.uuid, "source", "a.txt", "A", root.uuid, "/a.txt")
        val occupied = repo.createFileInWorkspace(root.uuid, "occupied", "b.txt", "B", root.uuid, "/b.txt")

        val failure = runCatching {
            repo.rename(root.uuid, source.uuid, "b.txt", expectedName = "a.txt")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("B")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, source.uuid)).isEqualTo(source)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, occupied.uuid)).isEqualTo(occupied)
    }

    @Test
    fun `rename cancelled after validation but before commit leaves zero mutation`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val clockReached = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            clock = {
                clockReached.countDown()
                check(releaseClock.await(5, TimeUnit.SECONDS))
                entry.updatedAt
            },
        )

        val pending = async(Dispatchers.Default) {
            repo.rename(root.uuid, entry.uuid, "b.txt", expectedName = "a.txt")
        }
        assertThat(clockReached.await(5, TimeUnit.SECONDS)).isTrue()
        pending.cancel()
        releaseClock.countDown()
        runCatching { pending.await() }

        assertThat(pending.isCancelled).isTrue()
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "b.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isEqualTo(entry)
    }

    @Test
    fun `rename commit point completes atomically under cancellation and retry reports Success`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val dbCommitted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            updateCommitter = { updates ->
                db.fileEntryDao().updateAll(updates)
                dbCommitted.complete(Unit)
                releaseCommit.await()
            },
            clock = { entry.updatedAt },
        )

        val pending = async(Dispatchers.Default) {
            repo.rename(root.uuid, entry.uuid, "b.txt", expectedName = "a.txt")
        }
        dbCommitted.await()
        pending.cancel()
        releaseCommit.complete(Unit)
        val cancellation = runCatching { pending.await() }.exceptionOrNull()

        assertThat(cancellation).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        val committed = db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!
        assertThat(committed.name).isEqualTo("b.txt")
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("A")
        val retry = repo.rename(root.uuid, entry.uuid, "b.txt", expectedName = "a.txt")
        assertThat(retry).isEqualTo(
            RenameResult.Success("b.txt", entry.hash, committed.updatedAt, changed = false),
        )
    }

    @Test
    fun `rename epoch remains monotonic when clock returns same millisecond`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            clock = { entry.updatedAt },
        )

        val first = repo.rename(root.uuid, entry.uuid, "b.txt", expectedName = "a.txt") as RenameResult.Success
        val second = repo.rename(root.uuid, entry.uuid, "c.txt", expectedName = "b.txt") as RenameResult.Success

        assertThat(first.targetEpoch).isEqualTo(entry.updatedAt + 1L)
        assertThat(second.targetEpoch).isEqualTo(first.targetEpoch + 1L)
    }

    @Test
    fun `rename retains stale derived timestamps in the same commit`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val created = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val indexed = created.copy(vectorizedAt = 200L, kgExtractedAt = 300L)
        db.fileEntryDao().update(indexed)

        val result = repo.rename(root.uuid, created.uuid, "b.txt", expectedName = "a.txt")

        assertThat((result as RenameResult.Success).changed).isTrue()
        val renamed = db.fileEntryDao().getByUuid(root.uuid, created.uuid)!!
        assertThat(renamed.vectorizedAt).isEqualTo(200L)
        assertThat(renamed.kgExtractedAt).isEqualTo(300L)
    }

    @Test
    fun `directory rename keeps every descendant epoch monotonic and retains stale timestamps`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "docs", root.uuid, "/docs")
        val child = repo.createFileInWorkspace(
            root.uuid,
            "child",
            "a.txt",
            "A",
            folder.uuid,
            "/docs/a.txt",
        )
        val childEpoch = maxOf(folder.updatedAt, child.updatedAt) + 10_000L
        db.fileEntryDao().update(
            child.copy(
                updatedAt = childEpoch,
                vectorizedAt = 400L,
                kgExtractedAt = 500L,
            ),
        )
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            clock = { folder.updatedAt },
        )

        val result = repo.rename(root.uuid, folder.uuid, "renamed-docs", expectedName = "docs")

        val renamedChild = db.fileEntryDao().getByUuid(root.uuid, child.uuid)!!
        assertThat(renamedChild.materializedPath).isEqualTo("/renamed-docs/a.txt")
        assertThat(renamedChild.updatedAt).isEqualTo(childEpoch + 1L)
        assertThat(renamedChild.vectorizedAt).isEqualTo(400L)
        assertThat(renamedChild.kgExtractedAt).isEqualTo(500L)
        assertThat((result as RenameResult.Success).affectedTargets).isEmpty()
    }

    @Test
    fun `directory rename rejects descendant epoch overflow before physical commit`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "docs", root.uuid, "/docs")
        val child = repo.createFileInWorkspace(
            root.uuid,
            "child",
            "a.txt",
            "A",
            folder.uuid,
            "/docs/a.txt",
        )
        db.fileEntryDao().update(child.copy(updatedAt = Long.MAX_VALUE))

        val failure = runCatching {
            repo.rename(root.uuid, folder.uuid, "renamed-docs", expectedName = "docs")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(rootA, "docs/a.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "renamed-docs").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, folder.uuid)!!.materializedPath)
            .isEqualTo("/docs")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, child.uuid)!!.materializedPath)
            .isEqualTo("/docs/a.txt")
    }

    @Test
    fun `subtree uses strict directory boundary and treats wildcard characters literally`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val docs = repo.createDirectoryInWorkspace(root.uuid, "docs", "docs", root.uuid, "/docs")
        repo.createDirectoryInWorkspace(root.uuid, "docs2", "docs2", root.uuid, "/docs2")
        repo.createFileInWorkspace(root.uuid, "inside", "a.txt", "A", docs.uuid, "/docs/a.txt")
        val percent = repo.createDirectoryInWorkspace(root.uuid, "percent", "100%_notes", root.uuid, "/100%_notes")
        repo.createFileInWorkspace(root.uuid, "special", "b.txt", "B", percent.uuid, "/100%_notes/b.txt")

        assertThat(repo.getSubtree(root.uuid, "/docs").map { it.uuid })
            .containsExactly("docs", "inside")
        assertThat(repo.getSubtree(root.uuid, "/100%_notes").map { it.uuid })
            .containsExactly("percent", "special")

        repo.rename(root.uuid, docs.uuid, "renamed-docs")
        assertThat(repo.getByUuid(root.uuid, "inside")!!.materializedPath)
            .isEqualTo("/renamed-docs/a.txt")
        assertThat(repo.getByUuid(root.uuid, "docs2")!!.materializedPath).isEqualTo("/docs2")
        assertThat(File(rootA, "docs2").isDirectory).isTrue()
    }

    @Test
    fun `different sessions cannot claim same canonical physical root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        insertSession("session-b", rootA.resolve(".").path)
        val another = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps())
        val claims = listOf(
            async(Dispatchers.Default) { runCatching { repo.ensureSessionRoot("session-a") } },
            async(Dispatchers.Default) { runCatching { another.ensureSessionRoot("session-b") } },
        ).awaitAll()

        assertThat(claims.count { it.isSuccess }).isEqualTo(1)
        assertThat(claims.count { it.exceptionOrNull() is SecurityException }).isEqualTo(1)
        val assigned = listOf("session-a", "session-b")
            .count { db.sessionDao().getById(it)!!.workspaceRootUuid != null }
        assertThat(assigned).isEqualTo(1)
    }

    @Test
    fun `noncanonical first claim is backfilled and blocks later canonical claim`() = runBlocking<Unit> {
        insertSession("session-a", rootA.resolve(".").path)
        insertSession("session-b", rootA.canonicalPath)

        val claimed = repo.ensureSessionRoot("session-a")
        val rejected = runCatching { repo.ensureSessionRoot("session-b") }.exceptionOrNull()

        assertThat(claimed.physicalRootPath).isEqualTo(rootA.canonicalPath)
        assertThat(db.sessionDao().getById("session-a")!!.workspacePath).isEqualTo(rootA.canonicalPath)
        assertThat(rejected).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `existing root with blank session path reuses and backfills claimed root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val claimed = repo.ensureSessionRoot("session-a")
        val session = db.sessionDao().getById("session-a")!!
        db.sessionDao().update(session.copy(workspacePath = "  "))

        val reused = repo.ensureSessionRoot("session-a")

        assertThat(reused.uuid).isEqualTo(claimed.uuid)
        assertThat(db.sessionDao().getById("session-a")!!.workspacePath).isEqualTo(rootA.canonicalPath)
    }

    @Test
    fun `default parent replacement with forged self identity is rejected`() = runBlocking<Unit> {
        val parent = File(rootA, "sessions")
        insertSession("session-a", null)
        insertSession("session-b", null)
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), parent, TestWorkspaceFileOps())
        repo.ensureSessionRoot("session-a")
        val parked = File(rootA, "sessions-parked")
        assertThat(parent.renameTo(parked)).isTrue()
        val copiedNonce = File(parked, ".infinity_root_identity").readText().substringBefore('\n')
        parent.mkdirs()
        val key = Files.readAttributes(
            parent.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        File(parent, ".infinity_root_identity").writeText("$copiedNonce\n$key")

        val failure = runCatching { repo.ensureSessionRoot("session-b") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `path validation rejects traversal physical absolute and symlink escape`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")

        val invalidPaths = listOf(
            "/../evil.txt",
            "../evil.txt",
            rootA.absolutePath + "/absolute.txt",
            "C:\\outside\\evil.txt",
        )
        invalidPaths.forEachIndexed { index, path ->
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "bad-$index", "bad.txt", "nope", root.uuid, path)
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
        }

        val outside = File(rootA.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(rootA, "link").toPath(), outside.toPath())
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "symlink", "evil.txt", "nope", root.uuid, "/link/evil.txt")
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
            assertThat(File(outside, "evil.txt").exists()).isFalse()
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `reserved transaction sibling names cannot be created`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val names = listOf(".infinity-write-token", ".infinity-previous-token", ".infinity-rollback-token")

        names.forEachIndexed { index, name ->
            val failure = runCatching {
                repo.createFileInWorkspace(root.uuid, "reserved-$index", name, "x", root.uuid, "/$name")
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(File(rootA, name).exists()).isFalse()
        }
    }

    @Test
    fun `rename recycle and delete reconcile crash state before physical mutation`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val renameEntry = repo.createFileInWorkspace(root.uuid, "rename", "rename.txt", "old", root.uuid, "/rename.txt")
        val recycleEntry = repo.createFileInWorkspace(root.uuid, "recycle", "recycle.txt", "old", root.uuid, "/recycle.txt")
        val deleteEntry = repo.createFileInWorkspace(root.uuid, "delete", "delete.txt", "old", root.uuid, "/delete.txt")
        listOf("rename.txt", "recycle.txt", "delete.txt")
            .forEach { File(rootA, it).writeText("new-crash-state") }
        val delegate = TestWorkspaceFileOps()
        val reconciled = mutableListOf<String>()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(),
            fileOps = object : WorkspaceFileOps by delegate {
                override fun reconcileFile(
                    root: java.nio.file.Path,
                    relative: List<String>,
                    expectedHash: String,
                ) {
                    val target = relative.fold(root) { current, segment -> current.resolve(segment) }
                    Files.write(target, "old".toByteArray())
                    reconciled += relative.joinToString("/")
                    delegate.reconcileFile(root, relative, expectedHash)
                }
            },
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.rename(root.uuid, renameEntry.uuid, "renamed.txt")
        repo.permanentDelete(root.uuid, recycleEntry.uuid)
        repo.permanentDelete(root.uuid, deleteEntry.uuid)

        assertThat(File(rootA, "renamed.txt").readText()).isEqualTo("old")
        assertThat(File(rootA, "recycle.txt").exists()).isFalse()
        assertThat(File(rootA, "delete.txt").exists()).isFalse()
        assertThat(reconciled).containsExactly("rename.txt", "recycle.txt", "delete.txt")
    }

    @Test
    fun `create database failure removes descriptor-created physical file`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            insertCommitter = { throw IllegalStateException("injected insert failure") },
        )

        var failed = false
        try {
            repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, "file")).isNull()
    }

    @Test
    fun `move database failure rolls descriptor move and database paths back`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            updateCommitter = { throw IllegalStateException("injected update failure") },
        )

        var failed = false
        try {
            repo.rename(root.uuid, entry.uuid, "renamed.txt")
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "renamed.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!.materializedPath).isEqualTo("/a.txt")
    }

    @Test
    fun `delete database failure restores tombstoned file`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            deleteCommitter = { _, _ -> throw IllegalStateException("injected delete failure") },
        )

        var failed = false
        try {
            repo.permanentDelete(root.uuid, entry.uuid)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNotNull()
    }

    @Test
    fun `永久删除在barrier join完成前不得进入数据库提交`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val barrierEntered = CompletableDeferred<Unit>()
        val releaseBarrier = CompletableDeferred<Unit>()
        val committerCalled = CompletableDeferred<Unit>()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            beforeDeleteCommitted = { _, ids ->
                assertThat(ids).containsExactly(entry.uuid)
                object : WorkspaceDeleteBarrierLease {
                    override suspend fun awaitReady() {
                        barrierEntered.complete(Unit)
                        releaseBarrier.await()
                    }
                    override suspend fun commit() = Unit
                    override suspend fun abort() = Unit
                }
            },
            deleteCommitter = { workspace, ids ->
                committerCalled.complete(Unit)
                WorkspaceDeletionTransaction(db).delete(workspace, ids)
            },
        )

        val deletion = async(Dispatchers.Default) { repo.permanentDelete(root.uuid, entry.uuid) }
        barrierEntered.await()

        assertThat(committerCalled.isCompleted).isFalse()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        releaseBarrier.complete(Unit)
        deletion.await()

        assertThat(committerCalled.isCompleted).isTrue()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
    }

    @Test
    fun `永久删除必须等待legacy vector worker取消后再清理任务与物理文件`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val embeddingStarted = CompletableDeferred<Unit>()
        val embeddingCancelled = CompletableDeferred<Unit>()
        val embeddingClient = mockk<com.promenar.infinity.data.rag.EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            embeddingStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                embeddingCancelled.complete(Unit)
            }
        }
        val queue = com.promenar.infinity.data.rag.VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = db.vectorDao(),
            vectorizationTaskDao = db.vectorizationTaskDao(),
            dispatcher = Dispatchers.Default,
        )
        queue.enqueueDocument(root.uuid, entry.uuid, entry.name, "A")
        embeddingStarted.await()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            beforeDeleteCommitted = { workspace, ids -> queue.acquireDeleteBarrier(workspace, ids) },
            deleteCommitter = { workspace, ids ->
                check(embeddingCancelled.isCompleted) { "持久删除前必须等待 legacy worker 取消" }
                WorkspaceDeletionTransaction(db).delete(workspace, ids)
            },
        )

        repo.permanentDelete(root.uuid, entry.uuid)

        assertThat(embeddingCancelled.isCompleted).isTrue()
        assertThat(db.vectorizationTaskDao().getByDocId(entry.uuid)).isEmpty()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        queue.shutdown()
    }

    @Test
    fun `deleteCommitter失败先恢复物理文件再abort恢复legacy任务`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val calls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val embeddingClient = mockk<com.promenar.infinity.data.rag.EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            if (calls.incrementAndGet() == 1) firstStarted.complete(Unit) else secondStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        val queue = com.promenar.infinity.data.rag.VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = db.vectorDao(),
            vectorizationTaskDao = db.vectorizationTaskDao(),
            dispatcher = Dispatchers.Default,
        )
        queue.enqueueDocument(root.uuid, entry.uuid, entry.name, "A")
        firstStarted.await()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            beforeDeleteCommitted = { workspace, ids -> queue.acquireDeleteBarrier(workspace, ids) },
            deleteCommitter = { _, _ -> throw IllegalStateException("injected delete failure") },
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, entry.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        firstCancelled.await()
        kotlinx.coroutines.withTimeout(5_000) { secondStarted.await() }
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.vectorizationTaskDao().getByDocId(entry.uuid)).isNotEmpty()
        queue.shutdown()
    }

    @Test
    fun `legacyKG先迁移为reference且删除回滚后不复活派生数据`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        db.fileEntryDao().update(
            entry.copy(mimeType = "text/plain", hash = "hash-current", updatedAt = 2),
        )
        val graphStarted = CompletableDeferred<Unit>()
        val graphCancelled = CompletableDeferred<Unit>()
        val embeddingClient = mockk<com.promenar.infinity.data.rag.EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } answers {
            com.promenar.infinity.data.rag.EmbeddingResult(
                firstArg<List<String>>().map { floatArrayOf(1f, 0f) },
            )
        }
        val graphExtractor = mockk<com.promenar.infinity.data.rag.GraphExtractor>()
        val documentService = object : com.promenar.infinity.data.rag.DocumentIndexService {
            override suspend fun rebuild(event: com.promenar.infinity.data.rag.FileIndexEvent): com.promenar.infinity.data.rag.DocumentIndexResult {
                graphStarted.complete(Unit)
                try { awaitCancellation() } finally { graphCancelled.complete(Unit) }
            }

            override suspend fun delete(workspaceRootUuid: String, fileUuid: String) =
                com.promenar.infinity.data.rag.DocumentIndexResult.Deleted(fileUuid)
        }
        val queue = com.promenar.infinity.data.rag.VectorizationQueue(
            vectorStore = com.promenar.infinity.data.rag.VectorStore(
                db.vectorDao(), db.kgNodeDao(), db.kgEdgeDao(), db.kgEvidenceDao(),
            ),
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            vectorDao = db.vectorDao(),
            vectorizationTaskDao = db.vectorizationTaskDao(),
            dispatcher = Dispatchers.Default,
            fileEntryDao = db.fileEntryDao(),
            documentIndexService = documentService,
        )
        queue.enqueueDocument(root.uuid, entry.uuid, entry.name, "A", kgStrategy = "full")
        kotlinx.coroutines.withTimeout(5_000) { graphStarted.await() }
        assertThat(db.vectorDao().getByDocId(entry.uuid)).isEmpty()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            beforeDeleteCommitted = { workspace, ids -> queue.acquireDeleteBarrier(workspace, ids) },
            deleteCommitter = { _, _ -> throw IllegalStateException("injected delete failure") },
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, entry.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        kotlinx.coroutines.withTimeout(5_000) { graphCancelled.await() }
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.vectorDao().getByDocId(entry.uuid)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `仓储永久删除目录在同一数据库事务清理全部子文件派生数据`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        val file = repo.createFileInWorkspace(root.uuid, "child", "a.txt", "A", folder.uuid, "/folder/a.txt")
        seedDerivedArtifacts(root.uuid, file.uuid)
        com.promenar.infinity.data.rag.KgCollectionRepository(db).setFolderSetting(folder.uuid, com.promenar.infinity.data.rag.KgFolderSetting.SHARED)
        val deletion = WorkspaceDeletionTransaction(db)
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = deletion::delete,
        )

        repo.permanentDelete(root.uuid, folder.uuid)

        assertThat(com.promenar.infinity.data.rag.KgCollectionRepository(db).readFolderSetting(folder.uuid)).isEqualTo(com.promenar.infinity.data.rag.KgFolderSetting.AUTO)
        assertThat(db.vectorDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.kgEdgeDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.documentTagDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
    }

    @Test
    fun `永久删除成功commit后清理单文件与目录子树pending`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val single = repo.createFileInWorkspace(root.uuid, "single", "single.txt", "S", root.uuid, "/single.txt")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        val child = repo.createFileInWorkspace(root.uuid, "child", "child.txt", "C", folder.uuid, "/folder/child.txt")
        val coordinator = com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator(
            com.promenar.infinity.data.rag.FileIndexEventSink { throw IllegalStateException("hold pending") },
        )
        listOf(single, child).forEach { entry ->
            runCatching {
                coordinator.publish(
                    com.promenar.infinity.data.rag.FileIndexEvent.Changed(
                        root.uuid, entry.uuid, entry.hash, entry.updatedAt,
                    ),
                )
            }
        }
        var barrierCommitted = false
        val callbackIds = mutableListOf<List<String>>()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            beforeDeleteCommitted = { _, _ ->
                object : WorkspaceDeleteBarrierLease {
                    override suspend fun awaitReady() = Unit
                    override suspend fun commit() { barrierCommitted = true }
                    override suspend fun abort() = Unit
                }
            },
            afterDeleteCommitted = { workspace, ids ->
                check(barrierCommitted) { "callback must run after barrier commit" }
                callbackIds += ids
                coordinator.clearCommitted(workspace, ids)
            },
        )

        repo.permanentDelete(root.uuid, single.uuid)
        assertThat(coordinator.snapshot().map { it.fileUuid }).containsExactly(child.uuid)
        barrierCommitted = false
        repo.permanentDelete(root.uuid, folder.uuid)

        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(callbackIds[0]).containsExactly(single.uuid)
        assertThat(callbackIds[1]).containsAtLeast(folder.uuid, child.uuid)
    }

    @Test
    fun `删除失败abort不调用committed回调且pending保留`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val coordinator = com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator(
            com.promenar.infinity.data.rag.FileIndexEventSink { throw IllegalStateException("hold pending") },
        )
        val target = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            root.uuid, file.uuid, file.hash, file.updatedAt,
        )
        runCatching { coordinator.publish(target) }
        var aborted = false
        var callbackCalls = 0
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = { _, _ -> throw IllegalStateException("delete failed") },
            beforeDeleteCommitted = { _, _ ->
                object : WorkspaceDeleteBarrierLease {
                    override suspend fun awaitReady() = Unit
                    override suspend fun commit() = Unit
                    override suspend fun abort() { aborted = true }
                }
            },
            afterDeleteCommitted = { workspace, ids ->
                callbackCalls += 1
                coordinator.clearCommitted(workspace, ids)
            },
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, file.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(aborted).isTrue()
        assertThat(callbackCalls).isEqualTo(0)
        assertThat(coordinator.snapshot()).containsExactly(target)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNotNull()
    }

    @Test
    fun `committed回调异常不反转已完成删除`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            afterDeleteCommitted = { _, _ ->
                throw kotlinx.coroutines.CancellationException("callback cancelled")
            },
        )

        repo.permanentDelete(root.uuid, file.uuid)

        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNull()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
    }

    @Test
    fun `派生清理或文件记录删除失败会回滚数据库并恢复物理文件`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        seedDerivedArtifacts(root.uuid, file.uuid)
        val deletion = WorkspaceDeletionTransaction(db) { throw IllegalStateException("injected transaction failure") }
        var abortObservedPhysicalRollback = false
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = deletion::delete,
            beforeDeleteCommitted = { _, _ ->
                object : WorkspaceDeleteBarrierLease {
                    override suspend fun awaitReady() = Unit
                    override suspend fun commit() = Unit
                    override suspend fun abort() {
                        abortObservedPhysicalRollback = File(rootA, "a.txt").exists()
                    }
                }
            },
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, file.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNotNull()
        assertThat(db.vectorDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(db.documentTagDao().getByDocIdForLifecycleCleanup(file.uuid)).hasSize(1)
        assertThat(db.vectorizationTaskDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(abortObservedPhysicalRollback).isTrue()
    }

    private suspend fun seedDerivedArtifacts(workspaceRootUuid: String, fileUuid: String) {
        db.vectorDao().insert(com.promenar.infinity.data.local.db.entity.VectorEntity(
            id = "vector-$fileUuid", docId = fileUuid, content = "indexed",
            embedding = byteArrayOf(0, 0, 0, 0), createdAt = 1, fileUuid = fileUuid,
        ))
        db.seedKgNode(com.promenar.infinity.data.local.db.entity.KgNodeEntity(
            id = "source-$fileUuid", name = "source-$fileUuid", createdAt = 1,
        ) , docId = fileUuid, workspaceDocument = true)
        db.seedKgNode(com.promenar.infinity.data.local.db.entity.KgNodeEntity(
            id = "target-$fileUuid", name = "target-$fileUuid", createdAt = 1,
        ) , docId = fileUuid, workspaceDocument = true)
        db.seedKgEdge(com.promenar.infinity.data.local.db.entity.KgEdgeEntity(
            id = "edge-$fileUuid", sourceId = "source-$fileUuid", targetId = "target-$fileUuid",
            relation = "rel", createdAt = 1,
        ) , docId = fileUuid, workspaceDocument = true)
        db.tagDao().insert(com.promenar.infinity.data.local.db.entity.TagEntity("tag-$fileUuid", "tag", createdAt = 1))
        db.documentTagDao().insert(com.promenar.infinity.data.local.db.entity.DocumentTagEntity(
            fileUuid, "tag-$fileUuid", 1,
        ))
        db.vectorizationTaskDao().insert(com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity(
            id = "task-$fileUuid", type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            status = "failed", docId = fileUuid, workspaceRootUuid = workspaceRootUuid,
            sourceMimeType = "text/plain", createdAt = 1, updatedAt = 1,
        ))
    }

    @Test
    fun `failed tombstone cleanup is retryable by maintenance`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val secure = TestWorkspaceFileOps()
        val cleanupFailingOps = object : WorkspaceFileOps by secure {
            override fun stageDelete(
                root: java.nio.file.Path,
                source: List<String>,
                deletionToken: String,
            ): WorkspaceFileRollback {
                val actual = secure.stageDelete(root, source, deletionToken)
                return object : WorkspaceFileRollback {
                    override fun commit() = throw IllegalStateException("injected tombstone cleanup failure")
                    override fun rollback() = actual.rollback()
                }
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = cleanupFailingOps,
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, entry.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, ".infinity_tombstones").walkTopDown().any { it.isFile }).isTrue()

        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps())
        repo.cleanupPendingTombstones(root.uuid)
        assertThat(File(rootA, ".infinity_tombstones").walkTopDown().any { it.isFile }).isFalse()
    }

    @Test
    fun `stage后进程死亡时数据库仍有记录则维护流程恢复原路径`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("a.txt"), workspaceDeletionToken(entry.uuid))
        assertThat(File(rootA, "a.txt").exists()).isFalse()

        repo.cleanupPendingTombstones(root.uuid)

        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNotNull()
    }

    @Test
    fun `stage后进程死亡时数据库已提交删除则维护流程清理tombstone`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("a.txt"), workspaceDeletionToken(entry.uuid))
        WorkspaceDeletionTransaction(db).delete(root.uuid, listOf(entry.uuid))

        repo.cleanupPendingTombstones(root.uuid)

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, ".infinity_tombstones").walkTopDown().any { it.isFile }).isFalse()
    }

    @Test
    fun `损坏恢复路径拒绝越界且单token失败不阻断其他tombstone恢复`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val bad = repo.createFileInWorkspace(root.uuid, "bad", "bad.txt", "bad", root.uuid, "/bad.txt")
        val good = repo.createFileInWorkspace(root.uuid, "good", "good.txt", "good", root.uuid, "/good.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("bad.txt"), workspaceDeletionToken(bad.uuid))
        ops.stageDelete(rootA.toPath(), listOf("good.txt"), workspaceDeletionToken(good.uuid))
        db.fileEntryDao().update(bad.copy(materializedPath = "/../outside.txt"))
        val outside = rootA.parentFile!!.resolve("outside.txt")
        outside.delete()

        val report = repo.cleanupPendingTombstones(root.uuid)

        assertThat(report.attentionTokens).contains(workspaceDeletionToken(bad.uuid))
        assertThat(File(rootA, "good.txt").readText()).isEqualTo("good")
        assertThat(outside.exists()).isFalse()
    }

    @Test
    fun `自动恢复按root隔离并报告失败root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val healthy = repo.ensureSessionRoot("session-a")
        insertSession("session-b", rootB.absolutePath)
        val broken = repo.ensureSessionRoot("session-b")
        rootB.deleteRecursively()

        val results = listOf(healthy.uuid, broken.uuid).associateWith { id ->
            runCatching { repo.cleanupPendingTombstones(id) }
        }
        assertThat(results.getValue(healthy.uuid).isSuccess).isTrue()
        assertThat(results.getValue(broken.uuid).isFailure).isTrue()
    }

    @Test
    fun `删除目录一次处理整棵子树`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        repo.createFileInWorkspace(root.uuid, "child", "a.txt", "A", folder.uuid, "/folder/a.txt")
        val delegate = TestWorkspaceFileOps()
        var stagedDeletes = 0
        val coordinator = com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator(
            com.promenar.infinity.data.rag.FileIndexEventSink { throw IllegalStateException("hold pending") },
        )
        val child = db.fileEntryDao().getByUuid(root.uuid, "child")!!
        runCatching {
            coordinator.publish(
                com.promenar.infinity.data.rag.FileIndexEvent.Changed(
                    root.uuid, child.uuid, child.hash, child.updatedAt,
                ),
            )
        }
        var clearedIds: List<String> = emptyList()
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = object : WorkspaceFileOps by delegate {
                override fun stageDelete(
                    root: java.nio.file.Path,
                    source: List<String>,
                    deletionToken: String,
                ): WorkspaceFileRollback {
                    stagedDeletes += 1
                    return delegate.stageDelete(root, source, deletionToken)
                }
            },
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            afterDeleteCommitted = { workspace, ids ->
                clearedIds = ids
                coordinator.clearCommitted(workspace, ids)
            },
        )

        repo.permanentDelete(root.uuid, folder.uuid)

        assertThat(stagedDeletes).isEqualTo(1)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, folder.uuid)).isNull()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, "child")).isNull()
        assertThat(clearedIds).containsAtLeast(folder.uuid, "child")
        assertThat(coordinator.snapshot()).isEmpty()
    }

    @Test
    fun `concurrent creates for same path leave exactly one file and row`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val results = listOf("one", "two").map { id ->
            async(Dispatchers.Default) {
                runCatching {
                    repo.createFileInWorkspace(root.uuid, id, "same.txt", id, root.uuid, "/same.txt")
                }
            }
        }.awaitAll()

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(repo.getSubtree(root.uuid, "/").count { it.materializedPath == "/same.txt" }).isEqualTo(1)
        assertThat(File(rootA, "same.txt").isFile).isTrue()
    }

    @Test
    fun `三类create遇到未登记物理同名节点时fail fast且不删除原节点`() = runBlocking<Unit> {
        insertSession("create-collision", rootA.absolutePath)
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            mutationJournal = RoomWorkspaceFileMutationJournal(db),
        )
        val root = repo.ensureSessionRoot("create-collision")
        File(rootA, "plain.txt").writeText("keep-plain")
        File(rootA, "stream.bin").writeBytes(byteArrayOf(7, 8, 9))
        File(rootA, "folder").apply { mkdir(); resolve("child.txt").writeText("keep-child") }

        val failures = listOf(
            runCatching { repo.createFileInWorkspace(root.uuid, "plain", "plain.txt", "new", root.uuid, "/plain.txt") },
            runCatching {
                repo.createFileInWorkspaceStreaming(
                    root.uuid, "stream", "stream.bin", "application/octet-stream",
                    root.uuid, "/stream.bin", 100,
                ) { it.write(byteArrayOf(1, 2, 3)) }
            },
            runCatching { repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder") },
        )

        assertThat(failures.all { it.isFailure }).isTrue()
        assertThat(File(rootA, "plain.txt").readText()).isEqualTo("keep-plain")
        assertThat(File(rootA, "stream.bin").readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
        assertThat(File(rootA, "folder/child.txt").readText()).isEqualTo("keep-child")
        assertThat(db.fileEntryDao().getAnyStateByMaterializedPathForLifecycle(root.uuid, "/plain.txt")).isNull()
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
    }

    @Test
    fun `create先完成committed journal再清owner且清理失败不伪装业务失败`() = runBlocking<Unit> {
        insertSession("create-owner-cleanup", rootA.absolutePath)
        val delegate = TestWorkspaceFileOps()
        var failOwnerCleanup = true
        val cleanupFailingOps = object : WorkspaceFileOps by delegate {
            override fun deleteNonRecursive(root: java.nio.file.Path, source: List<String>) {
                if (failOwnerCleanup && source.size == 2 && source.first() == CREATE_OWNERSHIP_DIRECTORY) {
                    throw java.io.IOException("injected owner cleanup failure")
                }
                delegate.deleteNonRecursive(root, source)
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = cleanupFailingOps,
            mutationJournal = RoomWorkspaceFileMutationJournal(
                db,
                operationIdFactory = { "cleanup-op" },
            ),
        )
        val root = repo.ensureSessionRoot("create-owner-cleanup")

        val created = repo.createFileInWorkspace(
            root.uuid,
            "created",
            "created.txt",
            "payload",
            root.uuid,
            "/created.txt",
        )

        assertThat(created.uuid).isEqualTo("created")
        assertThat(File(rootA, "created.txt").readText()).isEqualTo("payload")
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
        assertThat(File(rootA, "$CREATE_OWNERSHIP_DIRECTORY/cleanup-op").isDirectory).isTrue()

        failOwnerCleanup = false
        WorkspaceFileMutationRecoveryCoordinator(db, delegate).recoverOrThrow()

        assertThat(File(rootA, CREATE_OWNERSHIP_DIRECTORY).exists()).isFalse()
        assertThat(File(rootA, "created.txt").readText()).isEqualTo("payload")
    }

    @Test
    fun `move目标同名冲突在prepare前拒绝且重启恢复无悬挂`() = runBlocking<Unit> {
        insertSession("move-collision", rootA.absolutePath)
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            mutationJournal = RoomWorkspaceFileMutationJournal(db),
        )
        val root = repo.ensureSessionRoot("move-collision")
        val left = repo.createDirectoryInWorkspace(root.uuid, "left", "left", root.uuid, "/left")
        val right = repo.createDirectoryInWorkspace(root.uuid, "right", "right", root.uuid, "/right")
        val moving = repo.createFileInWorkspace(root.uuid, "moving", "dup.txt", "left", left.uuid, "/left/dup.txt")
        repo.createFileInWorkspace(root.uuid, "occupied", "dup.txt", "right", right.uuid, "/right/dup.txt")

        assertThat(runCatching { repo.updateParent(root.uuid, moving.uuid, right.uuid) }.isFailure).isTrue()
        assertThat(File(rootA, "left/dup.txt").readText()).isEqualTo("left")
        assertThat(File(rootA, "right/dup.txt").readText()).isEqualTo("right")
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
        WorkspaceFileMutationRecoveryCoordinator(db, TestWorkspaceFileOps()).recoverOrThrow()
    }

    @Test
    fun `write and rename from different repositories serialize on canonical root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "old", root.uuid, "/a.txt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fileRepository = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val anotherWorkspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        val write = async(Dispatchers.Default) {
            fileRepository.writeFileAtomic(root.uuid, entry.uuid, "new", "session-a", entry.hash)
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val rename = async(Dispatchers.Default) { anotherWorkspace.rename(root.uuid, entry.uuid, "b.txt") }
        delay(100)
        assertThat(rename.isCompleted).isFalse()
        release.countDown()
        write.await()
        rename.await()

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("new")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!.materializedPath).isEqualTo("/b.txt")
    }

    @Test
    fun `写入与删除从不同仓库实例按根目录串行`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "old", root.uuid, "/a.txt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fileRepository = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val anotherWorkspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        val write = async(Dispatchers.Default) {
            fileRepository.writeFileAtomic(root.uuid, entry.uuid, "new", "session-a", entry.hash)
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val delete = async(Dispatchers.Default) { anotherWorkspace.permanentDelete(root.uuid, entry.uuid) }
        delay(100)
        assertThat(delete.isCompleted).isFalse()
        release.countDown()
        write.await()
        delete.await()

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
    }

    @Test
    fun `活动文件直接永久删除并保留工作区根`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "active", "active.txt", "keep", root.uuid, "/active.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.permanentDelete(root.uuid, entry.uuid)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
        assertThat(File(rootA, "active.txt").exists()).isFalse()
        assertThat(runCatching { repo.permanentDelete(root.uuid, root.uuid) }.exceptionOrNull())
            .isInstanceOf(SecurityException::class.java)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, root.uuid)).isNotNull()
    }

    @Test
    fun `creation rollback授权仅允许未确认的流式新建文件`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val rollback = repo.createFileInWorkspaceStreaming(
            root.uuid, "rollback", "rollback.txt", "text/plain", root.uuid, "/rollback.txt", 32,
        ) { it.write("temp".toByteArray()) }
        val confirmed = repo.createFileInWorkspaceStreaming(
            root.uuid, "confirmed", "confirmed.txt", "text/plain", root.uuid, "/confirmed.txt", 32,
        ) { it.write("keep".toByteArray()) }
        repo.confirmCreatedEntry(root.uuid, confirmed.uuid)

        repo.rollbackCreatedEntry(root.uuid, rollback.uuid)
        val rejected = runCatching {
            repo.rollbackCreatedEntry(root.uuid, confirmed.uuid)
        }.exceptionOrNull()

        assertThat(db.fileEntryDao().getByUuid(root.uuid, rollback.uuid)).isNull()
        assertThat(File(rootA, "rollback.txt").exists()).isFalse()
        assertThat(rejected).isInstanceOf(WorkspaceLifecycleException::class.java)
        assertThat((rejected as WorkspaceLifecycleException).code)
            .isEqualTo(WorkspaceLifecycleErrorCode.CREATION_ROLLBACK_NOT_AUTHORIZED)
        assertThat(File(rootA, "confirmed.txt").readText()).isEqualTo("keep")
    }

    @Test
    fun `删除文件不进入普通uuid路径子列表子树和搜索查询`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(
            root.uuid,
            "recycled-invisible",
            "hidden.txt",
            "secret",
            root.uuid,
            "/hidden.txt",
        )
        seedDerivedArtifacts(root.uuid, entry.uuid)
        repo.permanentDelete(root.uuid, entry.uuid)

        assertThat(repo.getByUuid(root.uuid, entry.uuid)).isNull()
        assertThat(repo.getByMaterializedPath(root.uuid, "/hidden.txt")).isNull()
        assertThat(repo.observeChildren(root.uuid, root.uuid).first()).isEmpty()
        assertThat(repo.getSubtree(root.uuid, "/")).containsExactly(root)
        assertThat(repo.searchByName(root.uuid, "hidden").first()).isEmpty()
        assertThat(db.vectorDao().getByDocId(entry.uuid)).isEmpty()
        assertThat(db.kgEdgeDao().getByDocId(entry.uuid)).isEmpty()
        assertThat(db.vectorizationTaskDao().getByDocId(entry.uuid)).isEmpty()
    }

    @Test
    fun `永久删除子树显式清理版本记录和物理快照`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        val child = repo.createFileInWorkspace(root.uuid, "child", "a.txt", "A", folder.uuid, "/folder/a.txt")
        val fileRepo = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps())
        fileRepo.writeFileAtomic(root.uuid, child.uuid, "B", "session-a", child.hash)
        val version = db.fileVersionDao().getByFile(root.uuid, child.uuid).single()
        assertThat(File(version.contentPath).isFile).isTrue()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.permanentDelete(root.uuid, folder.uuid)

        assertThat(db.fileVersionDao().getByFile(root.uuid, child.uuid)).isEmpty()
        assertThat(File(version.contentPath).exists()).isFalse()
    }
}

private class RecordingWorkspaceFileMutationJournal : WorkspaceFileMutationJournal {
    val preparedTypes = mutableListOf<WorkspaceMutationType>()
    val openOperations = linkedSetOf<String>()
    val markedCommitted = mutableListOf<String>()
    val completed = mutableListOf<String>()
    private var nextId = 0

    override suspend fun prepare(
        workspaceRootUuid: String,
        operationType: WorkspaceMutationType,
        sourceRelativePath: String,
        targetRelativePath: String,
        databaseTargetUuid: String,
        rootIdentity: String,
    ): StagedWorkspaceFileMutation {
        preparedTypes += operationType
        val id = "record-${++nextId}"
        openOperations += id
        return StagedWorkspaceFileMutation(id, workspaceRootUuid, operationType)
    }

    override suspend fun commitDatabase(
        staged: StagedWorkspaceFileMutation,
        mutation: suspend () -> Unit,
    ) {
        check(staged.operationId in openOperations)
        mutation()
        markedCommitted += staged.operationId
    }

    override suspend fun complete(staged: StagedWorkspaceFileMutation) {
        check(staged.operationId in openOperations)
        completed += staged.operationId
        openOperations -= staged.operationId
    }

    override suspend fun abort(staged: StagedWorkspaceFileMutation) {
        openOperations -= staged.operationId
    }
}
