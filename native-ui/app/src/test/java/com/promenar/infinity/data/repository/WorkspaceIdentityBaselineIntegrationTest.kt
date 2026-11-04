package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceIdentityBaselineIntegrationTest {
    private lateinit var db: InfinityDatabase
    private lateinit var sandbox: File
    private lateinit var workspace: File
    private lateinit var store: WorkspaceIdentityBaselineStore
    private lateinit var repo: WorkspaceRepository
    private lateinit var files: FileOperationRepository
    private lateinit var root: FileEntry
    private val deleted = mutableListOf<String>()
    private val diagnostics = mutableListOf<String>()
    private val scannedPaths = mutableListOf<List<String>>()
    private var fullScans = 0
    private val scanner = object : WorkspaceTreeScanner {
        override fun scan(root: java.nio.file.Path): WorkspaceTreeSnapshot {
            fullScans++
            return PathWorkspaceTreeScanner.scan(root)
        }
        override fun scanPaths(root: java.nio.file.Path, paths: List<String>): Map<String, WorkspaceScanNode> {
            scannedPaths += paths
            return PathWorkspaceTreeScanner.scanPaths(root, paths)
        }
    }

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        sandbox = Files.createTempDirectory("identity-baseline").toFile()
        workspace = File(sandbox, "workspace").apply { mkdir() }
        reopenRepositories()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "a", workspacePath = workspace.path, createdAt = 1, updatedAt = 1))
        root = repo.ensureSessionRoot("s")
    }

    @After fun tearDown() {
        db.close()
        sandbox.deleteRecursively()
    }

    private fun reopenRepositories() {
        store = WorkspaceIdentityBaselineStore(File(sandbox, "baselines"), scanner) { diagnostics += it }
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            treeScanner = scanner, identityBaselines = store,
            deleteCommitter = { rootId, ids -> deleted += ids; WorkspaceDeletionTransaction(db).delete(rootId, ids) })
        files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = TestWorkspaceFileOps(), identityBaselines = store)
    }

    private suspend fun create(uuid: String, path: String, content: String = uuid, parent: String = root.uuid): FileEntry =
        repo.createFileInWorkspace(root.uuid, uuid, path.substringAfterLast('/'), content, parent, path).also {
            repo.confirmCreatedEntry(root.uuid, uuid)
        }

    private fun mv(source: String, target: String) {
        val process = ProcessBuilder("mv", File(workspace, source).path, File(workspace, target).path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "mv 失败：$output" }
    }

    private suspend fun command(block: () -> Unit) {
        val count = fullScans
        val finish = repo.beginSessionCommand("s")
        assertThat(fullScans).isEqualTo(count)
        try { block() } finally { finish() }
        assertThat(fullScans).isEqualTo(count)
        repo.reconcileSessionWorkspace(root.uuid, "s")
    }

    @Test fun `重启仓库后读取持久基线且mv保留UUID与版本不触发清理`() = runBlocking<Unit> {
        val entry = create("file", "/a.txt", "old")
        files.writeFileAtomic(root.uuid, entry.uuid, "current", "s", entry.hash)
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val version = checkNotNull(db.fileVersionDao().getByHash(root.uuid, entry.uuid, entry.hash))
        reopenRepositories()
        // 应用启动的无待办 tombstone 检查不能清空持久基线。
        repo.cleanupPendingTombstones(root.uuid)
        command { mv("a.txt", "b.txt") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")?.uuid).isEqualTo("file")
        assertThat(deleted).isEmpty()
        assertThat(File(version.contentPath).readText()).isEqualTo("old")
        assertThat(db.fileVersionDao().getByHash(root.uuid, entry.uuid, entry.hash)).isEqualTo(version)
    }

    @Test fun `两条命令间文件工具写入创建改名删除后mv保留身份`() = runBlocking<Unit> {
        val edited = create("edited", "/edit.txt", "old")
        val recycled = create("recycled", "/recycle.txt")
        val dir = repo.createDirectoryInWorkspace(root.uuid, "dir", "dir", root.uuid, "/dir")
        create("child", "/dir/a.txt", "child", dir.uuid)
        repo.reconcileSessionWorkspace(root.uuid, "s")
        command { mv("edit.txt", "first.txt") }
        val scansBeforeTools = fullScans
        files.writeFileAtomic(root.uuid, edited.uuid, "written by tool", "s", edited.hash)
        repo.rename(root.uuid, edited.uuid, "tool.txt", null)
        repo.rename(root.uuid, dir.uuid, "tool-dir", null)
        repo.permanentDelete(root.uuid, recycled.uuid)
        val created = create("created", "/created.txt")
        assertThat(fullScans).isEqualTo(scansBeforeTools)
        assertThat(scannedPaths.flatten()).containsAtLeast("/first.txt", "/tool.txt", "/tool-dir/a.txt", "/created.txt")
        command {
            mv("tool.txt", "last.txt")
            mv("tool-dir", "last-dir")
            mv("created.txt", "new.txt")
        }
        assertThat(repo.getByMaterializedPath(root.uuid, "/last.txt")?.uuid).isEqualTo(edited.uuid)
        assertThat(repo.getByMaterializedPath(root.uuid, "/last.txt")?.hash).isEqualTo(Sha256Utils.hash("written by tool"))
        assertThat(repo.getByMaterializedPath(root.uuid, "/last-dir")?.uuid).isEqualTo("dir")
        assertThat(repo.getByMaterializedPath(root.uuid, "/last-dir/a.txt")?.uuid).isEqualTo("child")
        assertThat(repo.getByMaterializedPath(root.uuid, "/new.txt")?.uuid).isEqualTo(created.uuid)
        assertThat(db.fileEntryDao().getAnyStateByUuidForLifecycle(root.uuid, recycled.uuid)).isNull()
        assertThat(deleted).containsExactly(recycled.uuid)
        val version = checkNotNull(db.fileVersionDao().getByHash(root.uuid, edited.uuid, edited.hash))
        assertThat(File(version.contentPath).readText()).isEqualTo("old")
    }

    @Test fun `改名修改内容并在原路径新建文件时旧UUID跟随移动`() = runBlocking<Unit> {
        val original = create("original", "/a.txt", "old")
        files.writeFileAtomic(root.uuid, original.uuid, "current", "s", original.hash)
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val version = checkNotNull(db.fileVersionDao().getByHash(root.uuid, original.uuid, original.hash))
        command {
            mv("a.txt", "b.txt")
            File(workspace, "b.txt").appendText(" changed")
            File(workspace, "a.txt").writeText("new file")
        }
        assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")?.uuid).isEqualTo("original")
        assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")?.hash).isEqualTo(Sha256Utils.hash("current changed"))
        assertThat(repo.getByMaterializedPath(root.uuid, "/a.txt")?.uuid).isNotEqualTo("original")
        assertThat(deleted).isEmpty()
        assertThat(File(version.contentPath).readText()).isEqualTo("old")
    }

    @Test fun `基线损坏时无异常退回无基线对账并记录诊断`() = runBlocking<Unit> {
        create("old", "/a.txt")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        File(sandbox, "baselines").listFiles()!!.single().writeText("damaged")
        command { mv("a.txt", "b.txt") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")).isNotNull()
        assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")?.uuid).isNotEqualTo("old")
        assertThat(diagnostics).contains("corrupted")
        assertThat(store.load(root)).isNotNull()
    }

    @Test fun `持久基线暂存不阻塞Linux命令且旧纪元不会发布缓存`() = runBlocking<Unit> {
        create("file", "/a.txt")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockingDirectory = object : File(sandbox, "baselines") {
            override fun toPath(): java.nio.file.Path {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return super.toPath()
            }
        }
        val slowStore = WorkspaceIdentityBaselineStore(blockingDirectory, scanner)
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            treeScanner = scanner, identityBaselines = slowStore)
        val scan = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val deferred = scan.async { runCatching { repo.reconcileSessionWorkspace(root.uuid, "s") } }
        var finish: (suspend () -> Unit)? = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            finish = kotlinx.coroutines.withTimeout(1_000) { repo.beginSessionCommand("s") }
            mv("a.txt", "b.txt")
            kotlinx.coroutines.withTimeout(1_000) { finish.invoke() }
            finish = null
            release.countDown()
            val failure = kotlinx.coroutines.withTimeout(5_000) { deferred.await() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(WorkspaceScanInvalidatedException::class.java)
            assertThat(store.load(root)?.nodes?.containsKey("/a.txt")).isTrue()
            reopenRepositories()
            repo.reconcileSessionWorkspace(root.uuid, "s")
            assertThat(repo.getByMaterializedPath(root.uuid, "/b.txt")?.uuid).isEqualTo("file")
            assertThat(File(sandbox, "baselines").listFiles()!!.none { it.extension == "tmp" }).isTrue()
        } finally {
            release.countDown()
            finish?.invoke()
            scan.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun `索引应用失败后旧基线不可被新仓库实例读取`() = runBlocking<Unit> {
        create("old", "/a.txt")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            treeScanner = scanner, identityBaselines = store, deleteCommitter = { _, _ -> error("模拟事务失败") })
        val finish = repo.beginSessionCommand("s")
        try { check(File(workspace, "a.txt").delete()) } finally { finish() }
        assertThat(runCatching { repo.reconcileSessionWorkspace(root.uuid, "s") }.isFailure).isTrue()
        assertThat(WorkspaceIdentityBaselineStore(File(sandbox, "baselines")).load(root)).isNull()
        assertThat(repo.getByUuid(root.uuid, "old")).isNotNull()
        reopenRepositories()
        repo.reconcileSessionWorkspace(root.uuid, "s")
        assertThat(repo.getByUuid(root.uuid, "old")).isNull()
        assertThat(store.load(root)).isNotNull()
    }

    @Test fun `基线失效不能持久化时拒绝文件工具事务`() = runBlocking<Unit> {
        create("old", "/a.txt")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val failingDirectory = object : File(sandbox, "baselines") {
            override fun toPath(): java.nio.file.Path = throw java.io.IOException("模拟目录同步失败")
        }
        val failingStore = WorkspaceIdentityBaselineStore(failingDirectory, scanner)
        files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = TestWorkspaceFileOps(), identityBaselines = failingStore)
        assertThat(runCatching { files.writeFileAtomic(root.uuid, "old", "new", "s", Sha256Utils.hash("old")) }.isFailure).isTrue()
        assertThat(File(workspace, "a.txt").readText()).isEqualTo("old")
        assertThat(repo.getByUuid(root.uuid, "old")?.hash).isEqualTo(Sha256Utils.hash("old"))
    }

    @Test fun `工具事务失败后不能复用旧基线`() = runBlocking<Unit> {
        create("old", "/a.txt")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            treeScanner = scanner, identityBaselines = store, insertCommitter = { error("模拟创建提交失败") })
        assertThat(runCatching { create("failed", "/failed.txt") }.isFailure).isTrue()
        assertThat(WorkspaceIdentityBaselineStore(File(sandbox, "baselines")).load(root)).isNull()
        assertThat(repo.getByMaterializedPath(root.uuid, "/a.txt")?.uuid).isEqualTo("old")
    }

    @Test fun `首次没有基线时命令不扫描且成功对账建立下一轮基线`() = runBlocking<Unit> {
        create("old", "/a.txt")
        command { mv("a.txt", "b.txt") }
        val first = checkNotNull(repo.getByMaterializedPath(root.uuid, "/b.txt"))
        assertThat(first.uuid).isNotEqualTo("old")
        command { mv("b.txt", "c.txt") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/c.txt")?.uuid).isEqualTo(first.uuid)
    }
}
