package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceCommandHostTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var workspace: File
    private val events = mutableListOf<FileIndexEvent>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            treeScanner = PathWorkspaceTreeScanner,
            indexEventSink = FileIndexEventSink { synchronized(events) { events += it } },
        )
        val projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"))
        workspace = Files.createTempDirectory(projectRoot, ".infinity-workspace-cmd").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        workspace.deleteRecursively()
    }

    private suspend fun root(): com.promenar.infinity.data.local.db.entity.FileEntry {
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = workspace.absolutePath, createdAt = now, updatedAt = now))
        return repo.ensureSessionRoot("s")
    }

    @Test
    fun `终端直接写入的已有文件能在完整对账时进入索引`() = runBlocking<Unit> {
        val root = root()
        val created = File(root.physicalRootPath, "terminal-output.txt").apply { writeText("from terminal") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/terminal-output.txt")).isNull()
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val indexed = checkNotNull(repo.getByMaterializedPath(root.uuid, "/terminal-output.txt"))
        assertThat(indexed.hash).isEqualTo(Sha256Utils.hash("from terminal"))
        created.writeText("changed by terminal")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        assertThat(repo.getByUuid(root.uuid, indexed.uuid)?.hash).isEqualTo(Sha256Utils.hash("changed by terminal"))
        created.delete()
        repo.reconcileSessionWorkspace(root.uuid, "s")
        assertThat(repo.getByUuid(root.uuid, indexed.uuid)).isNull()
    }

    @Test
    fun `后台扫描与文件创建修改移动删除及版本快照串行且索引不丢失`() = runBlocking<Unit> {
        val root = root()
        val original = repo.createFileInWorkspace(root.uuid, "edit", "edit.txt", "before", root.uuid, "/edit.txt")
        repo.confirmCreatedEntry(root.uuid, original.uuid)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val scans = java.util.concurrent.atomic.AtomicInteger()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            treeScanner = WorkspaceTreeScanner { path ->
                if (scans.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                PathWorkspaceTreeScanner.scan(path)
            })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val reconciler = SessionWorkspaceReconciler(scope) { repo.reconcileSessionWorkspace(root.uuid, "s") }
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = TestWorkspaceFileOps())
        try {
            reconciler.request("s")
            withContextIo { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            // 命令目录解析也不能因为另一个扫描持有根锁而排队。
            assertThat(withTimeout(1_000) { repo.sessionRootForCommand("s").uuid }).isEqualTo(root.uuid)
            val writer = async(Dispatchers.IO) {
                val created = repo.createFileInWorkspace(root.uuid, "new", "new.txt", "new", root.uuid, "/new.txt")
                repo.confirmCreatedEntry(root.uuid, created.uuid)
                assertThat(files.writeFileAtomic(root.uuid, original.uuid, "after", "s", original.hash))
                    .isInstanceOf(com.promenar.infinity.domain.repository.WriteResult.Success::class.java)
                repo.rename(root.uuid, created.uuid, "moved.txt", null)
                val removed = repo.createFileInWorkspace(root.uuid, "gone", "gone.txt", "gone", root.uuid, "/gone.txt")
                repo.confirmCreatedEntry(root.uuid, removed.uuid)
                repo.moveToRecycleBin(root.uuid, removed.uuid)
            }
            delay(50)
            assertThat(writer.isCompleted).isFalse()
            // 扫描进行期间的请求只能追加一个待跑标记。
            repeat(10) { reconciler.request("s") }
            release.countDown()
            withTimeout(5_000) { writer.await(); reconciler.awaitSettled("s") }
            assertThat(scans.get()).isEqualTo(2)
            assertThat(repo.getByMaterializedPath(root.uuid, "/edit.txt")?.hash).isEqualTo(Sha256Utils.hash("after"))
            assertThat(repo.getByMaterializedPath(root.uuid, "/moved.txt")?.hash).isEqualTo(Sha256Utils.hash("new"))
            assertThat(repo.getByMaterializedPath(root.uuid, "/new.txt")).isNull()
            assertThat(repo.getByMaterializedPath(root.uuid, "/gone.txt")).isNull()
            assertThat(File(workspace, "edit.txt").readText()).isEqualTo("after")
            assertThat(File(workspace, "moved.txt").readText()).isEqualTo("new")
            val version = checkNotNull(db.fileVersionDao().getByHash(root.uuid, original.uuid, original.hash))
            assertThat(File(version.contentPath).readText()).isEqualTo("before")
        } finally {
            release.countDown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    private suspend fun <T> withContextIo(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    @Test
    fun `扫描前置恢复与Linux命令启动串行`() = runBlocking<Unit> {
        val root = root()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val ops = object : WorkspaceFileOps by TestWorkspaceFileOps() {
            override fun migrateInternalStorage(path: java.nio.file.Path) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = ops,
            treeScanner = PathWorkspaceTreeScanner)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val reconciler = SessionWorkspaceReconciler(scope) { repo.reconcileSessionWorkspace(root.uuid, "s") }
        var finish: (suspend () -> Unit)? = null
        try {
            reconciler.request("s")
            withContextIo { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val command = async(Dispatchers.IO) { repo.beginSessionCommand("s") }
            delay(100)
            val startedDuringRecovery = command.isCompleted
            release.countDown()
            finish = withTimeout(5_000) { command.await() }
            finish.invoke()
            withTimeout(5_000) { reconciler.awaitSettled("s") }
            assertThat(startedDuringRecovery).isFalse()
        } finally {
            release.countDown()
            finish?.invoke()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `Linux命令在扫描期间临时移动目录不会误删条目和版本`() = runBlocking<Unit> {
        val root = root()
        val dir = repo.createDirectoryInWorkspace(root.uuid, "dir", "dir", root.uuid, "/dir")
        repo.confirmCreatedEntry(root.uuid, dir.uuid)
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "old", dir.uuid, "/dir/a.txt")
        repo.confirmCreatedEntry(root.uuid, entry.uuid)
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = TestWorkspaceFileOps())
        files.writeFileAtomic(root.uuid, entry.uuid, "current", "s", entry.hash)
        val entered = java.util.concurrent.CountDownLatch(1)
        val scan = java.util.concurrent.CountDownLatch(1)
        val captured = java.util.concurrent.CountDownLatch(1)
        val scans = java.util.concurrent.atomic.AtomicInteger()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            treeScanner = WorkspaceTreeScanner { path ->
                if (scans.incrementAndGet() == 1) { entered.countDown(); check(scan.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                PathWorkspaceTreeScanner.scan(path).also { captured.countDown() }
            })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val reconciler = SessionWorkspaceReconciler(scope) { repo.reconcileSessionWorkspace(root.uuid, "s") }
        var finish: (suspend () -> Unit)? = null
        try {
            reconciler.request("s")
            withContextIo { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            finish = withTimeout(1_000) { repo.beginSessionCommand("s") }
            check(File(workspace, "dir").renameTo(File(workspace, "temporary")))
            scan.countDown()
            withContextIo { check(captured.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            delay(50)
            assertThat(repo.getByUuid(root.uuid, entry.uuid)).isNotNull()
            assertThat(db.fileVersionDao().getByHash(root.uuid, entry.uuid, entry.hash)).isNotNull()
            check(File(workspace, "temporary").renameTo(File(workspace, "dir")))
            finish.invoke(); finish = null
            withTimeout(5_000) { reconciler.awaitSettled("s") }
            assertThat(repo.getByMaterializedPath(root.uuid, "/dir/a.txt")?.uuid).isEqualTo(entry.uuid)
            val version = checkNotNull(db.fileVersionDao().getByHash(root.uuid, entry.uuid, entry.hash))
            assertThat(File(version.contentPath).readText()).isEqualTo("old")
            assertThat(scans.get()).isAtLeast(2)
        } finally {
            scan.countDown()
            File(workspace, "temporary").takeIf { it.exists() }?.renameTo(File(workspace, "dir"))
            finish?.invoke()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `Linux命令结束以常数级身份保护恢复根标记`() = runBlocking<Unit> {
        val root = root()
        val marker = File(workspace, WORKSPACE_ROOT_IDENTITY_MARKER)
        val original = marker.readText()
        val finish = repo.beginSessionCommand("s")
        check(marker.delete())
        finish()
        finish()
        assertThat(marker.readText()).isEqualTo(original)
        assertThat(repo.sessionRootForCommand("s").uuid).isEqualTo(root.uuid)
    }

    @Test
    fun `文件写入等待活跃Linux命令且不会覆盖其新版本`() = runBlocking<Unit> {
        val root = root()
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "before", root.uuid, "/a.txt")
        repo.confirmCreatedEntry(root.uuid, entry.uuid)
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = TestWorkspaceFileOps())
        val finish = repo.beginSessionCommand("s")
        val writer = async(Dispatchers.IO) { runCatching { files.writeFileAtomic(root.uuid, entry.uuid, "tool", "s", entry.hash) } }
        try {
            delay(50)
            assertThat(writer.isCompleted).isFalse()
            File(workspace, "a.txt").writeText("linux")
            finish()
            assertThat(withTimeout(2_000) { writer.await() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(File(workspace, "a.txt").readText()).isEqualTo("linux")
        } finally { finish(); writer.cancelAndJoin() }
    }

    @Test
    fun `命令新增、修改、删除与重命名后索引与磁盘一致`() = runBlocking<Unit> {
        val root = root()
        val keep = repo.createFileInWorkspace(root.uuid, "keep", "keep.md", "old", root.uuid, "/keep.md").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val gone = repo.createFileInWorkspace(root.uuid, "gone", "gone.txt", "x", root.uuid, "/gone.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val renamed = repo.createFileInWorkspace(root.uuid, "ren", "a.txt", "same", root.uuid, "/a.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        events.clear()

        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, "keep.md").writeText("new content")
            File(physical, "gone.txt").delete()
            assertThat(File(physical, "a.txt").renameTo(File(physical, "b.txt"))).isTrue()
            File(physical, "out/deep").mkdirs()
            File(physical, "out/deep/r.csv").writeText("a,b\n1,2\n")
            File(physical, "out/blob").writeBytes(byteArrayOf(0, 1, 2, 3))
            "value"
        }

        assertThat(outcome.value).isEqualTo("value")
        assertThat(outcome.reconcileError).isNull()
        val changes = checkNotNull(outcome.changes)
        assertThat(changes.added).containsExactly("/out", "/out/blob", "/out/deep", "/out/deep/r.csv").inOrder()
        assertThat(changes.modified).containsExactly("/keep.md")
        assertThat(changes.removed).containsExactly("/gone.txt")
        assertThat(changes.moved).containsExactly("/a.txt" to "/b.txt")
        assertThat(changes.reservedChanges).isEmpty()

        val updated = checkNotNull(repo.getByUuid(root.uuid, keep.uuid))
        assertThat(updated.hash).isEqualTo(Sha256Utils.hash("new content"))
        assertThat(updated.sizeBytes).isEqualTo(11)
        assertThat(updated.updatedAt).isGreaterThan(keep.updatedAt)
        assertThat(updated.lastWriteSessionId).isEqualTo("s")
        assertThat(repo.getByUuid(root.uuid, gone.uuid)).isNull()
        val moved = checkNotNull(repo.getByUuid(root.uuid, renamed.uuid))
        assertThat(moved.materializedPath).isEqualTo("/b.txt")
        assertThat(moved.name).isEqualTo("b.txt")

        val out = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out"))
        val deep = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/deep"))
        val csv = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/deep/r.csv"))
        val blob = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/blob"))
        assertThat(out.isDirectory).isTrue()
        assertThat(out.parentUuid).isEqualTo(root.uuid)
        assertThat(deep.parentUuid).isEqualTo(out.uuid)
        assertThat(csv.parentUuid).isEqualTo(deep.uuid)
        assertThat(csv.mimeType).isEqualTo("text/csv")
        assertThat(csv.hash).isEqualTo(Sha256Utils.hash("a,b\n1,2\n"))
        assertThat(blob.mimeType).isEqualTo("application/octet-stream")

        val changed = events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }
        assertThat(changed).containsAtLeast(keep.uuid, csv.uuid, renamed.uuid)
        assertThat(changed).doesNotContain(blob.uuid)
        assertThat(File(workspace, COMMAND_PENDING_MARKER).exists()).isFalse()
    }

    @Test
    fun `git 目录内部文件入索引但不发布向量化事件`() = runBlocking<Unit> {
        val root = root()
        events.clear()
        repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, "repo/.git/refs/heads").mkdirs()
            File(physical, "repo/.git/config").writeText("[remote \"origin\"]\n\turl = https://user:token@example.com/r.git\n")
            File(physical, "repo/.git/HEAD").writeText("ref: refs/heads/master\n")
            File(physical, "repo/README.md").writeText("# r\n")
        }
        val config = checkNotNull(repo.getByMaterializedPath(root.uuid, "/repo/.git/config"))
        val readme = checkNotNull(repo.getByMaterializedPath(root.uuid, "/repo/README.md"))
        val changed = events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }
        assertThat(changed).contains(readme.uuid)
        assertThat(changed).doesNotContain(config.uuid)
        assertThat(changed).hasSize(1)
    }

    @Test
    fun `命令删除根身份标记时写回并给出保留路径警告`() = runBlocking<Unit> {
        val root = root()
        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, WORKSPACE_ROOT_IDENTITY_MARKER).delete()
            File(physical, ".infinity_planted").writeText("x")
        }
        assertThat(checkNotNull(outcome.changes).reservedChanges)
            .containsAtLeast("/$WORKSPACE_ROOT_IDENTITY_MARKER", "/.infinity_planted")
        // 根身份恢复后后续修改照常可用。
        repo.createDirectoryInWorkspace(root.uuid, "d", "d", root.uuid, "/d")
        assertThat(repo.getByMaterializedPath(root.uuid, "/d")).isNotNull()
        assertThat(db.fileEntryDao().getActiveByMaterializedPath(root.uuid, "/.infinity_planted")).isNull()
    }

    @Test
    fun `命令抛出异常时仍然对账并原样传播`() = runBlocking<Unit> {
        val root = root()
        val failure = runCatching {
            repo.runCommandInWorkspace(root.uuid, "s") { physical ->
                File(physical, "partial.txt").writeText("p")
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        assertThat(failure).hasMessageThat().isEqualTo("boom")
        assertThat(repo.getByMaterializedPath(root.uuid, "/partial.txt")).isNotNull()
    }

    @Test
    fun `命令运行期间其它写入排队等待`() = runBlocking<Unit> {
        val root = root()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            repo.runCommandInWorkspace(root.uuid, "s") { physical ->
                entered.complete(Unit)
                release.await()
                File(physical, "from-command.txt").writeText("c")
            }
        }
        entered.await()
        val write = async(Dispatchers.IO) { repo.createDirectoryInWorkspace(root.uuid, "w", "w", root.uuid, "/w") }
        delay(300)
        assertThat(write.isCompleted).isFalse()
        release.complete(Unit)
        withTimeout(10_000) { command.await(); write.await() }
        assertThat(repo.getByMaterializedPath(root.uuid, "/from-command.txt")).isNotNull()
        assertThat(repo.getByMaterializedPath(root.uuid, "/w")).isNotNull()
    }

    @Test
    fun `进程被杀留下标记时下一次修改前按磁盘收敛`() = runBlocking<Unit> {
        val root = root()
        val stale = repo.createFileInWorkspace(root.uuid, "stale", "stale.txt", "v1", root.uuid, "/stale.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        // 模拟命令中途进程被杀：标记残留，磁盘已改动但索引未更新。
        File(workspace, COMMAND_PENDING_MARKER).writeText("")
        File(workspace, "stale.txt").writeText("v2 longer")
        File(workspace, "orphan.txt").writeText("o")

        repo.createDirectoryInWorkspace(root.uuid, "d", "d", root.uuid, "/d")

        assertThat(checkNotNull(repo.getByUuid(root.uuid, stale.uuid)).hash).isEqualTo(Sha256Utils.hash("v2 longer"))
        assertThat(repo.getByMaterializedPath(root.uuid, "/orphan.txt")).isNotNull()
        assertThat(File(workspace, COMMAND_PENDING_MARKER).exists()).isFalse()
    }

    @Test
    fun `删除带版本历史的文件时清理版本快照`() = runBlocking<Unit> {
        val root = root()
        val file = repo.createFileInWorkspace(root.uuid, "v", "v.txt", "1", root.uuid, "/v.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val versions = File(workspace, workspaceVersionSnapshotDirectory(root.uuid, file.uuid).joinToString(File.separator))
        versions.mkdirs()
        File(versions, "old.snapshot").writeText("0")
        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical -> File(physical, "v.txt").delete() }
        assertThat(checkNotNull(outcome.changes).removed).containsExactly("/v.txt")
        assertThat(checkNotNull(outcome.changes).reservedChanges).isEmpty()
        assertThat(versions.exists()).isFalse()
        assertThat(repo.getByUuid(root.uuid, file.uuid)).isNull()
    }
}
