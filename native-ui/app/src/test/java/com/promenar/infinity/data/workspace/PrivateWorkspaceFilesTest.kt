package com.promenar.infinity.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.RoomWorkspaceFileMutationJournal
import com.promenar.infinity.data.repository.StagedWorkspaceFileMutation
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceFileMutationJournal
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.session.SessionDeletingException
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.startup.StartupIssueManager
import com.promenar.infinity.startup.StartupIssueScope
import com.promenar.infinity.startup.StartupScopeKind
import com.promenar.infinity.startup.StartupStep
import com.promenar.infinity.startup.StartupWriteBarrier
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 使用真实 Room、磁盘和 journal，检查用户文件操作的身份与事务边界。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PrivateWorkspaceFilesTest {
    private lateinit var db: InfinityDatabase
    private lateinit var directory: File
    private lateinit var io: TestWorkspaceFileOps
    private lateinit var journal: RecordingJournal
    private lateinit var repo: WorkspaceRepository
    private lateinit var root: FileEntry
    private lateinit var gate: SessionExecutionGate
    private val events = mutableListOf<FileIndexEvent>()

    @Before fun setup() = runBlocking {
        StartupWriteBarrier.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        directory = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".infinity-workspace-private-").toFile()
        io = TestWorkspaceFileOps()
        gate = SessionExecutionGate()
        journal = RecordingJournal(RoomWorkspaceFileMutationJournal(db))
        repo = repository()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = directory.path, createdAt = 1, updatedAt = 1))
        root = repo.ensureSessionRoot("s")
    }

    @After fun cleanup() {
        StartupWriteBarrier.resetForTesting()
        db.close()
        directory.deleteRecursively()
    }

    @Test fun `改名保留UUID持久身份版本和物理内容`() = runBlocking<Unit> {
        val source = versionedFile()
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val history = versions.associate { it.contentPath to File(it.contentPath).readBytes().toList() }
        val identity = identity("a.txt")
        service().rename("s", source.uuid, "renamed.txt")
        val changed = current(source)
        assertThat(changed.uuid).isEqualTo(source.uuid)
        assertThat(changed.hash).isEqualTo(source.hash)
        assertThat(changed.name).isEqualTo("renamed.txt")
        assertThat(changed.materializedPath).isEqualTo("/renamed.txt")
        assertThat(changed.updatedAt).isGreaterThan(source.updatedAt)
        assertThat(changed.vectorizedAt).isEqualTo(source.vectorizedAt)
        assertThat(changed.kgExtractedAt).isEqualTo(source.kgExtractedAt)
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(File(directory, "renamed.txt").readText()).isEqualTo("current")
        assertThat(identity("renamed.txt")).isEqualTo(identity)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(versions)
        assertThat(history.mapValues { File(it.key).readBytes().toList() }).isEqualTo(history)
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.RENAME)
    }

    @Test fun `改名遇到在途索引任务时发布新目标`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        db.vectorizationTaskDao().insert(com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity(
            id = "pending", type = "document_reference", status = "pending", docId = source.uuid,
            workspaceRootUuid = root.uuid, createdAt = 1, updatedAt = 1,
        ))
        service().rename("s", source.uuid, "b.txt")
        val changed = current(source)
        val event = events.single() as FileIndexEvent.Changed
        assertThat(event.fileUuid).isEqualTo(source.uuid)
        assertThat(event.targetEpoch).isEqualTo(changed.updatedAt)
        assertThat(changed.vectorizedAt).isNull()
        assertThat(changed.kgExtractedAt).isNull()
    }

    @Test fun `目录改名保留后代索引状态并不发布事件`() = runBlocking<Unit> {
        val folder = folder("docs")
        val child = file("child", "a.txt", folder)
        val nested = folder("nested", folder)
        val leaf = file("leaf", "b.txt", nested)
        val files = service(sink = FileIndexEventSink { event ->
            events += event
            if (events.size == 1) error("注入索引发布失败")
        })
        files.rename("s", folder.uuid, "renamed")
        assertThat(current(child).materializedPath).isEqualTo("/renamed/a.txt")
        assertThat(current(leaf).materializedPath).isEqualTo("/renamed/nested/b.txt")
        assertThat(events).isEmpty()
        assertThat(current(child).vectorizedAt).isEqualTo(child.vectorizedAt)
        assertThat(current(leaf).vectorizedAt).isEqualTo(leaf.vectorizedAt)
        assertThat(File(directory, "renamed/nested/b.txt").readText()).isEqualTo("content")
        assertJournal(WorkspaceMutationType.RENAME)
    }

    @Test fun `数据库同名冲突不改变来源目标身份与版本`() = runBlocking<Unit> {
        val source = versionedFile()
        val occupied = file("occupied", "b.txt")
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val identity = identity("a.txt")
        assertCode(DirectoryFileOpCode.NAME_EXISTS) { service().rename("s", source.uuid, "b.txt") }
        assertThat(current(source)).isEqualTo(source)
        assertThat(current(occupied)).isEqualTo(occupied)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("current")
        assertThat(File(directory, "b.txt").readText()).isEqualTo("content")
        assertThat(identity("a.txt")).isEqualTo(identity)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(versions)
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `未纳管物理同名冲突不改变来源身份与版本`() = runBlocking<Unit> {
        val source = versionedFile()
        File(directory, "b.txt").writeText("untracked")
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val identity = identity("a.txt")
        assertCode(DirectoryFileOpCode.NAME_EXISTS) { service().rename("s", source.uuid, "b.txt") }
        assertThat(current(source)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("current")
        assertThat(File(directory, "b.txt").readText()).isEqualTo("untracked")
        assertThat(identity("a.txt")).isEqualTo(identity)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(versions)
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `非法名称与工作区根改名被拒绝`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        for (name in listOf("", "  \t", ".", "..", "a/b", "a\\b", "a\u0000b", ".infinity", ".INFINITY_versions", ".recycle_bin")) {
            assertCode(DirectoryFileOpCode.NAME_INVALID) { service().rename("s", source.uuid, name) }
        }
        assertCode(DirectoryFileOpCode.NAME_INVALID) { service().rename("s", root.uuid, "renamed") }
        assertCode(DirectoryFileOpCode.NOT_FOUND) { service().rename("s", "missing", "renamed") }
        assertThat(current(source)).isEqualTo(source)
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `改为当前名称不提交不推进epoch也不发布事件`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        service().rename("s", source.uuid, "a.txt")
        assertThat(current(source)).isEqualTo(source)
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `跨目录移动文件与目录保留身份索引标志并更新全部后代路径`() = runBlocking<Unit> {
        val source = versionedFile()
        val folder = folder("docs")
        val nested = folder("nested", folder)
        val child = file("child", "child.txt", nested)
        val target = folder("target")
        val identity = identity("a.txt")
        val childIdentity = identity("docs/nested/child.txt")
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val result = service().move("s", listOf(source.uuid, folder.uuid), target.uuid)
        assertThat(result.succeeded).containsExactly(source.uuid, folder.uuid).inOrder()
        assertThat(result.failed).isEmpty()
        for ((before, path) in listOf(source to "/target/a.txt", child to "/target/docs/nested/child.txt")) {
            val after = current(before)
            assertThat(after.uuid).isEqualTo(before.uuid)
            assertThat(after.hash).isEqualTo(before.hash)
            assertThat(after.vectorizedAt).isEqualTo(before.vectorizedAt)
            assertThat(after.kgExtractedAt).isEqualTo(before.kgExtractedAt)
            assertThat(after.materializedPath).isEqualTo(path)
        }
        assertThat(current(nested).materializedPath).isEqualTo("/target/docs/nested")
        assertThat(current(folder).parentUuid).isEqualTo(target.uuid)
        assertThat(identity("target/a.txt")).isEqualTo(identity)
        assertThat(identity("target/docs/nested/child.txt")).isEqualTo(childIdentity)
        assertThat(File(directory, "target/a.txt").readText()).isEqualTo("current")
        assertThat(File(directory, "target/docs/nested/child.txt").readText()).isEqualTo("content")
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(File(directory, "docs").exists()).isFalse()
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(versions)
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationType.MOVE)
    }

    @Test fun `同批移动冲突逐项失败其余项成功且结果按输入去重排序`() = runBlocking<Unit> {
        val target = folder("target")
        val source = file("source", "a.txt")
        val other = file("other", "b.txt")
        file("occupied", "a.txt", target)
        val result = service().move("s", listOf(source.uuid, "missing", other.uuid, source.uuid, "missing"), target.uuid)
        assertThat(result.succeeded).containsExactly(other.uuid)
        assertThat(result.failed.keys).containsExactly(source.uuid, "missing").inOrder()
        assertThat(result.failed[source.uuid]).isEqualTo(DirectoryFileOpCode.NAME_EXISTS)
        assertThat(result.failed["missing"]).isEqualTo(DirectoryFileOpCode.NOT_FOUND)
        assertThat(current(source)).isEqualTo(source)
        assertThat(current(other).materializedPath).isEqualTo("/target/b.txt")
        assertThat(File(directory, "a.txt").readText()).isEqualTo("content")
        assertThat(File(directory, "target/b.txt").readText()).isEqualTo("content")
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.MOVE)
    }

    @Test fun `移动目标自身或子孙目录被提前拒绝且相似前缀不被误判`() = runBlocking<Unit> {
        val source = folder("docs")
        val child = folder("nested", source)
        for (target in listOf(source, child)) {
            val result = service().move("s", listOf(source.uuid), target.uuid)
            assertThat(result.failed).containsExactly(source.uuid, DirectoryFileOpCode.TARGET_INSIDE_SOURCE)
        }
        assertJournal()
        val sibling = folder("docs-other")
        assertThat(service().move("s", listOf(source.uuid), sibling.uuid).succeeded).containsExactly(source.uuid)
        assertThat(current(child).materializedPath).isEqualTo("/docs-other/docs/nested")
        assertJournal(WorkspaceMutationType.MOVE)
    }

    @Test fun `目标目录必须是本工作区活动目录`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val recycled = folder("recycled")
        repo.permanentDelete(root.uuid, recycled.uuid)
        val foreignDirectory = File(directory, "foreign-root").apply { mkdir() }
        db.sessionDao().insert(SessionEntity(id = "other", agentId = "agent", workspacePath = foreignDirectory.path, createdAt = 1, updatedAt = 1))
        val foreign = repo.ensureSessionRoot("other")
        journal.types.clear()
        for (target in listOf("missing", source.uuid, recycled.uuid, foreign.uuid)) {
            val result = service().move("s", listOf(source.uuid), target)
            assertThat(result.failed).containsExactly(source.uuid, DirectoryFileOpCode.NOT_FOUND)
        }
        assertThat(current(source)).isEqualTo(source)
        assertJournal()
    }

    @Test fun `已在目标目录的条目不提交且工作区根不能移动`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val result = service().move("s", listOf(source.uuid, root.uuid), root.uuid)
        assertThat(result.succeeded).containsExactly(source.uuid)
        assertThat(result.failed).containsExactly(root.uuid, DirectoryFileOpCode.NAME_INVALID)
        assertThat(current(source)).isEqualTo(source)
        assertJournal()
    }

    @Test fun `目录与后代同时选择仅移动最上层目录且保留输入顺序`() = runBlocking<Unit> {
        val source = folder("docs")
        val nested = folder("nested", source)
        val child = file("child", "a.txt", nested)
        val target = folder("target")
        val result = service().move("s", listOf(child.uuid, source.uuid, nested.uuid, source.uuid), target.uuid)
        assertThat(result.succeeded).containsExactly(child.uuid, source.uuid, nested.uuid).inOrder()
        assertThat(result.failed).isEmpty()
        assertThat(current(child).parentUuid).isEqualTo(nested.uuid)
        assertThat(current(nested).parentUuid).isEqualTo(source.uuid)
        assertThat(current(child).materializedPath).isEqualTo("/target/docs/nested/a.txt")
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.MOVE)
    }

    @Test fun `所选祖先移动冲突时后代继承失败且均不移动`() = runBlocking<Unit> {
        val source = folder("docs")
        val nested = folder("nested", source)
        val child = file("child", "a.txt", nested)
        val target = folder("target")
        folder("docs", target)
        val result = service().move("s", listOf(child.uuid, source.uuid, nested.uuid, child.uuid), target.uuid)
        assertThat(result.succeeded).isEmpty()
        assertThat(result.failed.keys).containsExactly(child.uuid, source.uuid, nested.uuid).inOrder()
        assertThat(result.failed.values).containsExactly(DirectoryFileOpCode.NAME_EXISTS, DirectoryFileOpCode.NAME_EXISTS, DirectoryFileOpCode.NAME_EXISTS)
        assertThat(current(source)).isEqualTo(source)
        assertThat(current(nested)).isEqualTo(nested)
        assertThat(current(child)).isEqualTo(child)
        assertThat(File(directory, "docs/nested/a.txt").readText()).isEqualTo("content")
        assertThat(File(directory, "target/docs/nested/a.txt").exists()).isFalse()
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `所选祖先删除提交失败时后代继承失败且事务回滚`() = runBlocking<Unit> {
        val source = folder("docs")
        val child = file("child", "a.txt", source)
        val failing = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = io, mutationJournal = journal,
            executionGate = gate, deleteCommitter = { _, _ -> error("注入删除提交失败") },
        )
        val result = service(failing).delete("s", listOf(child.uuid, source.uuid))
        assertThat(result.succeeded).isEmpty()
        assertThat(result.failed.keys).containsExactly(child.uuid, source.uuid).inOrder()
        assertThat(result.failed.values).containsExactly(DirectoryFileOpCode.IO, DirectoryFileOpCode.IO)
        assertThat(current(source)).isEqualTo(source)
        assertThat(current(child)).isEqualTo(child)
        assertThat(File(directory, "docs/a.txt").readText()).isEqualTo("content")
        assertThat(File(directory, ".recycle_bin/${source.uuid}").exists()).isFalse()
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.DELETE)
    }

    @Test fun `删除文件清除内容和版本副本`() = runBlocking<Unit> {
        val source = versionedFile()
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val result = service().delete("s", listOf(source.uuid, source.uuid))
        assertThat(result.succeeded).containsExactly(source.uuid)
        assertThat(result.failed).isEmpty()
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isNull()
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEmpty()
        versions.forEach { assertThat(File(it.contentPath).exists()).isFalse() }
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
    }

    @Test fun `目录与子项同时选择仅删除目录且根和缺失项逐项失败`() = runBlocking<Unit> {
        val source = folder("docs")
        val child = file("child", "a.txt", source)
        val result = service().delete("s", listOf(child.uuid, root.uuid, source.uuid, "missing", child.uuid))
        assertThat(result.succeeded).containsExactly(child.uuid, source.uuid).inOrder()
        assertThat(result.failed.keys).containsExactly(root.uuid, "missing").inOrder()
        assertThat(result.failed[root.uuid]).isEqualTo(DirectoryFileOpCode.NAME_INVALID)
        assertThat(result.failed["missing"]).isEqualTo(DirectoryFileOpCode.NOT_FOUND)
        assertThat(repo.getByUuid(root.uuid, child.uuid)).isNull()
        assertJournal(WorkspaceMutationType.DELETE)
    }

    @Test fun `改名与移动提交失败回滚数据库磁盘身份且清除journal`() = runBlocking<Unit> {
        val source = versionedFile()
        val target = folder("target")
        val versions = db.fileVersionDao().getByFile(root.uuid, source.uuid)
        val identity = identity("a.txt")
        val failing = service(repository(update = { error("注入提交失败") }))
        assertCode(DirectoryFileOpCode.IO) { failing.rename("s", source.uuid, "renamed.txt") }
        val result = failing.move("s", listOf(source.uuid), target.uuid)
        assertThat(result.failed).containsExactly(source.uuid, DirectoryFileOpCode.IO)
        assertThat(current(source)).isEqualTo(source)
        assertThat(File(directory, "a.txt").readText()).isEqualTo("current")
        assertThat(File(directory, "renamed.txt").exists()).isFalse()
        assertThat(File(directory, "target/a.txt").exists()).isFalse()
        assertThat(identity("a.txt")).isEqualTo(identity)
        assertThat(db.fileVersionDao().getByFile(root.uuid, source.uuid)).isEqualTo(versions)
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.RENAME, WorkspaceMutationType.MOVE)
    }

    @Test fun `并发改名恰好一个生效且数据库与磁盘一致`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : IWorkspaceRepository by repo {
            override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?): RenameResult {
                entered.complete(Unit)
                release.await()
                return repo.rename(workspaceRootUuid, uuid, newName, expectedName)
            }
        }
        val files = service(repository)
        withTimeout(10_000) {
            val first = async(Dispatchers.IO) { runCatching { files.rename("s", source.uuid, "first.txt") } }
            entered.await()
            val second = withContext(Dispatchers.IO) {
                async(start = CoroutineStart.UNDISPATCHED) { runCatching { files.rename("s", source.uuid, "second.txt") } }
                    .also { release.complete(Unit) }
            }
            val results = listOf(first.await(), second.await())
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat((results.single { it.isFailure }.exceptionOrNull() as DirectoryFileOpException).code)
                .isAnyOf(DirectoryFileOpCode.NOT_FOUND, DirectoryFileOpCode.NAME_EXISTS)
        }
        val final = current(source)
        assertThat(final.name).isEqualTo("first.txt")
        assertThat(File(directory, final.materializedPath.removePrefix("/")).readText()).isEqualTo("content")
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(File(directory, "second.txt").exists()).isFalse()
        assertThat(events).isEmpty()
        assertJournal(WorkspaceMutationType.RENAME)
    }

    @Test fun `并发移动与删除同一条目最终数据库与物理状态一致`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val target = folder("target")
        val files = service()
        val start = CompletableDeferred<Unit>()
        withTimeout(10_000) {
            val tasks = listOf(
                async(Dispatchers.IO) { start.await(); files.move("s", listOf(source.uuid), target.uuid) },
                async(Dispatchers.IO) { start.await(); files.delete("s", listOf(source.uuid)) },
            )
            start.complete(Unit)
            val results = tasks.awaitAll()
            assertThat(results[1].succeeded).containsExactly(source.uuid)
            assertThat(results[0].failed[source.uuid]).isAnyOf(null, DirectoryFileOpCode.NOT_FOUND)
        }
        assertThat(repo.getByUuid(root.uuid, source.uuid)).isNull()
        assertThat(File(directory, "a.txt").exists()).isFalse()
        assertThat(File(directory, "target/a.txt").exists()).isFalse()
        assertThat(events).isEmpty()
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
    }

    @Test fun `忙碌时所有入口拒绝且不触碰仓库`() = runBlocking<Unit> {
        var touched = false
        val repository = object : IWorkspaceRepository by repo {
            override suspend fun ensureSessionRoot(sessionId: String): FileEntry {
                touched = true
                error("忙碌时不应解析根")
            }
        }
        val files = service(repository, busy = { true })
        assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.rename("s", "source", "b.txt") }
        assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.move("s", listOf("source"), root.uuid) }
        assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.delete("s", listOf("source")) }
        assertThat(touched).isFalse()
        assertJournal()
    }

    @Test fun `会话删除门禁拒绝所有入口`() = runBlocking<Unit> {
        gate.withDeletion("s", root.uuid) {
            val files = service()
            assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.rename("s", "source", "b.txt") }
            assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.move("s", listOf("source"), root.uuid) }
            assertCode(DirectoryFileOpCode.SESSION_BUSY) { files.delete("s", listOf("source")) }
        }
        assertJournal()
    }

    @Test fun `启动隔离映射为工作区不可用且不改文件`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val manager = StartupIssueManager()
        manager.bindRoot(root.uuid, directory.canonicalPath, "s")
        StartupWriteBarrier.install(manager)
        manager.recover(StartupStep.WORKSPACE_JOURNAL, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, root.uuid, "工作区")) {
            error("注入隔离")
        }
        assertCode(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE) { service().rename("s", source.uuid, "b.txt") }
        assertThat(File(directory, "a.txt").readText()).isEqualTo("content")
        assertThat(events).isEmpty()
    }

    @Test fun `取消从单项批量和索引发布向上传播`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val cancelling = object : IWorkspaceRepository by repo {
            override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?): RenameResult = throw CancellationException("取消改名")
            override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String): Unit = throw CancellationException("取消移动")
            override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String): Unit = throw CancellationException("取消删除")
        }
        val target = folder("target")
        val files = service(cancelling)
        assertCancelled { files.rename("s", source.uuid, "b.txt") }
        assertCancelled { files.move("s", listOf(source.uuid), target.uuid) }
        assertCancelled { files.delete("s", listOf(source.uuid)) }
        assertThat(current(source)).isEqualTo(source)
        service(sink = FileIndexEventSink { throw CancellationException("取消发布") }).rename("s", source.uuid, "b.txt")
        assertThat(current(source).name).isEqualTo("b.txt")
        assertJournal(WorkspaceMutationType.RENAME)
    }

    @Test fun `改名条件冲突或条目消失映射为未找到且传递当前名称`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        for (result in listOf(RenameResult.NotFound, RenameResult.Conflict("a.txt", "other.txt"))) {
            val repository = object : IWorkspaceRepository by repo {
                override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?): RenameResult {
                    assertThat(expectedName).isEqualTo("a.txt")
                    return result
                }
            }
            assertCode(DirectoryFileOpCode.NOT_FOUND) { service(repository).rename("s", source.uuid, "b.txt") }
        }
        assertThat(events).isEmpty()
        assertJournal()
    }

    @Test fun `已有操作异常原样传播且仓库删除异常映射为忙碌`() = runBlocking<Unit> {
        val source = file("source", "a.txt")
        val failure = DirectoryFileOpException(DirectoryFileOpCode.READ_ONLY)
        val repository = object : IWorkspaceRepository by repo {
            override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?): RenameResult = throw failure
            override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String): Unit = throw SessionDeletingException("s")
        }
        assertThat(runCatching { service(repository).rename("s", source.uuid, "b.txt") }.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(service(repository).delete("s", listOf(source.uuid)).failed).containsExactly(source.uuid, DirectoryFileOpCode.SESSION_BUSY)
    }

    private fun repository(update: (suspend (List<FileEntry>) -> Unit)? = null) = WorkspaceRepository(
        db.fileEntryDao(), db.workspaceSeqDao(), fileOps = io, updateCommitter = update, mutationJournal = journal, executionGate = gate,
        deleteCommitter = com.promenar.infinity.data.repository.WorkspaceDeletionTransaction(db)::delete,
    )

    private fun service(
        repository: IWorkspaceRepository = repo,
        sink: FileIndexEventSink = FileIndexEventSink { events += it },
        busy: suspend (String) -> Boolean = { false },
    ) = DefaultPrivateWorkspaceFiles(repository, sink, gate, busy)

    private suspend fun file(uuid: String, name: String, parent: FileEntry = root): FileEntry {
        val path = parent.materializedPath.trimEnd('/') + "/" + name
        val entry = repo.createFileInWorkspace(root.uuid, uuid, name, "content", parent.uuid, path)
        repo.confirmCreatedEntry(root.uuid, uuid)
        db.fileEntryDao().update(entry.copy(vectorizedAt = 10, kgExtractedAt = 20))
        journal.types.clear()
        return current(entry)
    }

    private suspend fun folder(name: String, parent: FileEntry = root): FileEntry =
        repo.createDirectoryInWorkspace(root.uuid, "dir-${parent.uuid}-$name", name, parent.uuid, parent.materializedPath.trimEnd('/') + "/" + name)
            .also { repo.confirmCreatedEntry(root.uuid, it.uuid); journal.types.clear() }

    private suspend fun versionedFile(): FileEntry {
        val entry = file("source", "a.txt")
        FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = io)
            .writeFileAtomic(root.uuid, entry.uuid, "current", "s", entry.hash)
        val current = current(entry)
        db.fileEntryDao().update(current.copy(vectorizedAt = 10, kgExtractedAt = 20))
        return current(entry)
    }

    private suspend fun current(entry: FileEntry) = repo.getByUuid(root.uuid, entry.uuid)!!
    private fun identity(path: String) = io.inspect(directory.toPath(), path.split('/')).fileKey

    private suspend fun assertJournal(vararg types: WorkspaceMutationType) {
        assertThat(journal.types).containsExactlyElementsIn(types.toList()).inOrder()
        assertThat(db.workspaceMutationDao().getUnfinished()).isEmpty()
    }

    private suspend fun assertCode(code: DirectoryFileOpCode, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(DirectoryFileOpException::class.java)
        assertThat((failure as DirectoryFileOpException).code).isEqualTo(code)
    }

    private suspend fun assertCancelled(action: suspend () -> Unit) {
        assertThat(runCatching { action() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    private class RecordingJournal(private val delegate: WorkspaceFileMutationJournal) : WorkspaceFileMutationJournal by delegate {
        val types = mutableListOf<WorkspaceMutationType>()
        override suspend fun prepare(
            workspaceRootUuid: String,
            operationType: WorkspaceMutationType,
            sourceRelativePath: String,
            targetRelativePath: String,
            databaseTargetUuid: String,
            rootIdentity: String,
        ): StagedWorkspaceFileMutation {
            types += operationType
            return delegate.prepare(workspaceRootUuid, operationType, sourceRelativePath, targetRelativePath, databaseTargetUuid, rootIdentity)
        }
    }
}
