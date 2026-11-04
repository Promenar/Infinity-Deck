package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PrivateWorkspaceStorageTest {
    @Test
    fun `身份属性能力降级只接受有效旧证明且不吞掉损坏错误`() {
        var recorded = false
        assertThat(readWorkspaceIdentityAttribute(true, { throw UnsupportedOperationException() }, { recorded = true })).isNull()
        assertThat(recorded).isTrue()
        assertThat(runCatching { readWorkspaceIdentityAttribute(false, { throw UnsupportedOperationException() }, {}) }.exceptionOrNull())
            .isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(runCatching { readWorkspaceIdentityAttribute(true, { throw java.io.IOException("属性内容损坏") }, {}) }.exceptionOrNull())
            .isInstanceOf(java.io.IOException::class.java)
        assertThat(readWorkspaceIdentityAttribute(true, { "不同的有效nonce" }, {})).isEqualTo("不同的有效nonce")
    }

    @Test
    fun `私有身份记录发布中断不留下半写最终文件且损坏记录隔离后重建`() {
        val base = Files.createTempDirectory("workspace-private-identity-test").toFile().canonicalFile.toPath()
        val fileKey = PersistentFileIdentity.encode("11111111-1111-4111-8111-111111111111", "ino=1")
        val nonce = "11111111-1111-4111-8111-111111111111"
        val value = PersistentFileIdentity.marker(nonce, "ino=1")
        var fail = true
        val interrupted = WorkspaceInternalStorage(base, directoryOpener = { PrivateStorageTestDirectory(it) },
            metadataPublisher = { directory, source, target, _ ->
                if (fail) { fail = false; throw java.io.IOException("模拟发布前中断") }
                Files.move(directory.resolve(source), directory.resolve(target)); Unit
            })
        try {
            assertThat(runCatching { interrupted.storeIdentity(fileKey, value) }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
            assertThat(interrupted.readIdentity(fileKey)).isNull()
            interrupted.storeIdentity(fileKey, value)
            assertThat(testStorage(base).readIdentity(fileKey)).isEqualTo(value)
            val digest = com.promenar.infinity.infra.util.Sha256Utils.hash(fileKey)
            base.resolve("identities/$digest").toFile().writeText("半写")
            val resumed = testStorage(base)
            assertThat(resumed.readIdentity(fileKey)).isNull()
            resumed.storeIdentity(fileKey, value)
            assertThat(resumed.readIdentity(fileKey)).isEqualTo(value)
            assertThat(Files.list(base.resolve("identities")).use { it.map { node -> node.fileName.toString() }.toList() }
                .any { it.startsWith("$digest.corrupt-") }).isTrue()
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `命令和回收组合不把内部文件暴露给Git`() = runBlocking {
        org.junit.Assume.assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        val base = Files.createTempDirectory("workspace-private-git-test")
        fun git(root: java.io.File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            return output
        }
        try {
            database.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", createdAt = 1, updatedAt = 1))
            val repository = WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao(),
                fileOps = privateOps(base.resolve("private")), mutationJournal = RoomWorkspaceFileMutationJournal(database),
                deleteCommitter = WorkspaceDeletionTransaction(database)::delete,
                treeScanner = PathWorkspaceTreeScanner)
            val root = repository.ensureSessionRoot("session", base.resolve("user").toString())
            val physicalRoot = java.io.File(root.physicalRootPath)
            val tracked = repository.createFileInWorkspace(root.uuid, "tracked", "tracked.txt", "原文", root.uuid, "/tracked.txt")
            git(physicalRoot, "init", "--quiet")
            git(physicalRoot, "add", "tracked.txt")
            git(physicalRoot, "-c", "user.name=InfinityTest", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "fixture")
            val outcome = repository.runCommandInWorkspace(root.uuid, "session") { directory ->
                assertThat(java.io.File(directory, COMMAND_PENDING_MARKER).exists()).isFalse()
                val command = ProcessBuilder("sh", "-c", "printf command > command.txt; printf keep > keep.txt")
                    .directory(directory).start()
                check(command.waitFor() == 0)
            }
            assertThat(outcome.reconcileError).isNull()
            repository.permanentDelete(root.uuid, tracked.uuid)
            val untracked = repository.getByMaterializedPath(root.uuid, "/command.txt")!!
            repository.permanentDelete(root.uuid, untracked.uuid)
            assertThat(physicalRoot.list()!!.toList()).containsExactly(".git", ".infinity", "keep.txt")
            val status = git(physicalRoot, "status", "--porcelain")
            assertThat(status).contains("tracked.txt")
            assertThat(status).contains("keep.txt")
            assertThat(status).doesNotContain(".infinity")
            assertThat(status).doesNotContain(".recycle_bin")
            assertThat(database.workspaceMutationDao().getUnfinished()).isEmpty()
        } finally { database.close(); base.toFile().deleteRecursively() }
    }

    @Test
    fun `内部目录忽略规则仅在目录首次创建时写入`() {
        val base = Files.createTempDirectory("workspace-private-ignore-test")
        try {
            val ops: WorkspaceFileOps = privateOps(base.resolve("private"))
            ops.ensureRoot(base)
            WorkspaceMutationCoordinator.bindIdentityForTesting(base, ops.ensureRoot(base))
            ensureWorkspaceInternalDirectory(ops, base)
            assertThat(Files.exists(base.resolve(".infinity/.gitignore"))).isTrue()
            base.resolve(".infinity/.gitignore").toFile().writeText("用户规则")
            ensureWorkspaceInternalDirectory(ops, base)
            assertThat(base.resolve(".infinity/.gitignore").toFile().readText()).isEqualTo("用户规则")
            Files.delete(base.resolve(".infinity/.gitignore"))
            ensureWorkspaceInternalDirectory(ops, base)
            assertThat(Files.exists(base.resolve(".infinity/.gitignore"))).isFalse()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `命令标记与版本内容不写入用户根`() {
        val base = Files.createTempDirectory("workspace-private-marker-test")
        try {
            val root = Files.createDirectory(base.resolve("user"))
            val ops: WorkspaceFileOps = privateOps(base.resolve("private"))
            WorkspaceMutationCoordinator.bindIdentityForTesting(root, ops.ensureRoot(root))
            ops.createFile(root, listOf(COMMAND_PENDING_MARKER), byteArrayOf())
            ops.ensureDirectory(root, listOf(".infinity_versions"))
            ops.createFile(root, listOf(".infinity_versions", "snapshot"), "原文".toByteArray())
            assertThat(Files.exists(root.resolve(COMMAND_PENDING_MARKER))).isFalse()
            assertThat(Files.exists(root.resolve(".infinity_versions"))).isFalse()
            assertThat(ops.read(root, listOf(".infinity_versions", "snapshot")).toString(Charsets.UTF_8)).isEqualTo("原文")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `删除文件清除数据库和物理文件`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        val base = Files.createTempDirectory("workspace-private-test")
        try {
            database.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", createdAt = 1, updatedAt = 1))
            val repository = WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao(),
                fileOps = privateOps(base.resolve("private")), deleteCommitter = WorkspaceDeletionTransaction(database)::delete)
            val root = repository.ensureSessionRoot("session", base.resolve("user").toString())
            val file = repository.createFileInWorkspace(root.uuid, "file", "note.txt", "内容", root.uuid, "/note.txt")
            repository.permanentDelete(root.uuid, file.uuid)

            assertThat(Files.exists(base.resolve("user/.recycle_bin"))).isFalse()
            assertThat(database.fileEntryDao().getAnyStateByUuidForLifecycle(root.uuid, file.uuid)).isNull()
            assertThat(Files.exists(base.resolve("user/note.txt"))).isFalse()
        } finally {
            database.close()
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `迁移在类别间中断后重跑不丢失已移动内容`() {
        val base = Files.createTempDirectory("workspace-private-migration-test")
        try {
            val root = Files.createDirectory(base.resolve("user"))
            Files.createDirectory(root.resolve(".recycle_bin"))
            root.resolve(".recycle_bin/a").toFile().writeText("A")
            Files.createDirectory(root.resolve(".infinity_versions"))
            root.resolve(".infinity_versions/b").toFile().writeText("B")
            val storage = testStorage(base.resolve("private"))
            val disk = TestWorkspaceFileOps()
            var remaining = 2
            val interrupted = object : WorkspaceFileOps by disk {
                override fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback {
                    if (--remaining == 0) throw java.io.IOException("模拟中断")
                    return disk.moveAcrossRoots(sourceRoot, source, targetRoot, target)
                }
            }
            val ops = PrivateWorkspaceFileOps(storage, interrupted)
            val rootIdentity = ops.ensureRoot(root)
            WorkspaceMutationCoordinator.bindIdentityForTesting(root, rootIdentity)
            assertThat(ops.read(root, listOf(".recycle_bin", "a")).toString(Charsets.UTF_8)).isEqualTo("A")
            assertThat(ops.read(root, listOf(".infinity_versions", "b")).toString(Charsets.UTF_8)).isEqualTo("B")
            assertThat(storage.migrationFailures()).isNotEmpty()
            assertThat(testStorage(base.resolve("private")).migrationFailures()).isNotEmpty()
            val resumed = PrivateWorkspaceFileOps(storage, disk)
            assertThat(resumed.read(root, listOf(".infinity_versions", "b")).toString(Charsets.UTF_8)).isEqualTo("B")
            assertThat(resumed.read(root, listOf(".recycle_bin", "a")).toString(Charsets.UTF_8)).isEqualTo("A")
            assertThat(Files.exists(root.resolve(".recycle_bin"))).isFalse()
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `旧回收journal经私有迁移后按数据库原路径恢复`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        val base = Files.createTempDirectory("workspace-private-journal-test")
        try {
            database.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", createdAt = 1, updatedAt = 1))
            val disk = TestWorkspaceFileOps()
            val repository = WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao(), fileOps = disk)
            val root = repository.ensureSessionRoot("session", base.resolve("user").toString())
            repository.createFileInWorkspace(root.uuid, "file", "note.txt", "内容", root.uuid, "/note.txt")
            val journal = RoomWorkspaceFileMutationJournal(database)
            journal.prepare(root.uuid, WorkspaceMutationType.RECYCLE, "note.txt", ".recycle_bin/file", "file", root.hash)
            Files.createDirectory(base.resolve("user/.recycle_bin"))
            Files.move(base.resolve("user/note.txt"), base.resolve("user/.recycle_bin/file"))
            val ops = PrivateWorkspaceFileOps(testStorage(base.resolve("private")), disk)
            WorkspaceFileMutationRecoveryCoordinator(database, ops).recoverOrThrow()
            assertThat(base.resolve("user/note.txt").toFile().readText()).isEqualTo("内容")
            assertThat(Files.exists(base.resolve("user/.recycle_bin"))).isFalse()
            assertThat(database.workspaceMutationDao().getUnfinished()).isEmpty()
        } finally { database.close(); base.toFile().deleteRecursively() }
    }

    private fun privateOps(base: Path): WorkspaceFileOps {
        val storage = testStorage(base)
        return PrivateWorkspaceFileOps(storage, TestWorkspaceFileOps(identityStorage = storage))
    }
    private fun testStorage(base: Path) = WorkspaceInternalStorage(base.toFile().canonicalFile.toPath(),
        directoryOpener = { PrivateStorageTestDirectory(it) },
        metadataPublisher = { directory, source, target, _ -> Files.move(directory.resolve(source), directory.resolve(target)); Unit })
}

/** 宿主夹具只提供真实磁盘IO；Android descriptor与xattr保证由仪器测试覆盖。 */
private class PrivateStorageTestDirectory(private val root: Path) : java.nio.file.SecureDirectoryStream<Path> {
    override fun close() = Unit
    override fun iterator(): MutableIterator<Path> = Files.list(root).use { it.toList().toMutableList().iterator() }
    override fun newDirectoryStream(path: Path, vararg options: java.nio.file.LinkOption) = PrivateStorageTestDirectory(resolve(path))
    override fun newByteChannel(path: Path, options: MutableSet<out java.nio.file.OpenOption>, vararg attrs: java.nio.file.attribute.FileAttribute<*>) =
        Files.newByteChannel(resolve(path), options, *attrs)
    override fun deleteFile(path: Path) { Files.delete(resolve(path)) }
    override fun deleteDirectory(path: Path) { Files.delete(resolve(path)) }
    override fun move(source: Path, targetDirectory: java.nio.file.SecureDirectoryStream<Path>, target: Path) {
        Files.move(resolve(source), (targetDirectory as PrivateStorageTestDirectory).resolve(target))
    }
    override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(type: Class<V>): V =
        Files.getFileAttributeView(root, type, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: java.nio.file.LinkOption): V =
        Files.getFileAttributeView(resolve(path), type, *options)
    private fun resolve(path: Path): Path {
        require(!path.isAbsolute && path.none { it.toString() == ".." })
        var current = root
        path.forEach { part -> current = current.resolve(part); if (Files.isSymbolicLink(current)) throw SecurityException("夹具拒绝符号链接") }
        return current
    }
}
