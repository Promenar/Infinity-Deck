package com.promenar.infinity.data.workspace

import android.content.Context
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.storage.BoundRealPathDirectory
import com.promenar.infinity.data.storage.BoundRealPathRead
import com.promenar.infinity.data.storage.RealPathFileSystem
import com.promenar.infinity.data.storage.RealPathExternalFolderBackend
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import io.mockk.*
import java.io.File
import java.io.RandomAccessFile
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileSystemException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryWorkspaceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(
        fileSystem: RealPathFileSystem = JvmBoundRealPathFileSystem(),
        kind: String = WorkspaceEntity.KIND_PROJECT,
    ): Pair<File, ExternalWorkspaceAccess> {
        val root = temporary.newFolder().canonicalFile
        val row = WorkspaceEntity("project", kind, "项目", if (kind == WorkspaceEntity.KIND_PROJECT) root.name else root.path, 1)
        val sessions = mockk<SessionDao>()
        val workspaces = mockk<WorkspaceDao>()
        coEvery { sessions.getById("s") } returns SessionEntity(
            id = "s", agentId = "a", workspaceId = "project", branchPlanJson = null,
            worktreeDirectory = null, createdAt = 1, updatedAt = 1,
        )
        coEvery { workspaces.getById("project") } returns row
        val shared = mockk<SharedWorkspaceAccess> { every { root(row.location) } returns root }
        val resolver = SessionWorkspaceResolver(sessions, workspaces, checkNotNull(root.parentFile), shared)
        val dao = mockk<ExternalMountDao>()
        coEvery { dao.visible(any()) } returns emptyList()
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.newFolder()
        val backend = RealPathExternalFolderBackend(root, emptySet(), { true }, fileSystem)
        val manager = ExternalFolderManager(context, dao, mockk<IWorkspaceRepository>(), FileIndexEventSink.None, resolver) { backend }
        return root to manager
    }

    @Test fun `设备文件系统与内置文件系统使用相同的大目录操作路径`() = runBlocking {
        val (root, mounts) = fixture(kind = WorkspaceEntity.KIND_SHARED)
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "source").mkdir()
        RandomAccessFile(File(root, "source/large"), "rw").use { it.setLength(33L * 1024 * 1024) }
        files.rename("s", "/source", "renamed")
        File(root, "target").mkdir()
        assertEquals(listOf("/renamed"), files.move("s", listOf("/renamed"), "/target").succeeded)
        assertEquals(listOf("/target/renamed"), files.copy("s", listOf("/target/renamed"), "/").succeeded)
        assertEquals(33L * 1024 * 1024, File(root, "renamed/large").length())
        assertEquals(listOf("/renamed"), files.delete("s", listOf("/renamed")).succeeded)
        assertFalse(File(root, "renamed").exists())
    }

    @Test fun `超过快照字节和条目上限的目录改名移动保持 inode 与内容且不读文件`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "repo").apply { mkdir() }
        val large = File(source, "large.bin")
        RandomAccessFile(large, "rw").use { it.setLength(33L * 1024 * 1024); it.writeUTF("内容标记") }
        repeat(1001) { File(source, "item-$it").createNewFile() }
        val identities = source.walkTopDown().associate { source.toPath().relativize(it.toPath()).toString() to inode(it) }
        files.rename("s", "/repo", "renamed")
        File(root, "target").mkdir()
        assertEquals(listOf("/renamed"), files.move("s", listOf("/renamed"), "/target").succeeded)
        val moved = File(root, "target/renamed")
        assertEquals(identities, moved.walkTopDown().associate { moved.toPath().relativize(it.toPath()).toString() to inode(it) })
        RandomAccessFile(File(moved, "large.bin"), "r").use { assertEquals("内容标记", it.readUTF()) }
        assertEquals(33L * 1024 * 1024, File(moved, "large.bin").length())
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `超过快照上限的目录可以复制且源和目标内容一致`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "large").apply { mkdir() }
        val large = File(source, "data.bin")
        RandomAccessFile(large, "rw").use { it.setLength(33L * 1024 * 1024); it.writeUTF("流式复制") }
        val result = files.copy("s", listOf("/large"), "/")
        assertEquals(listOf("/large"), result.succeeded)
        val target = File(root, "large (2)/data.bin")
        assertEquals(large.length(), target.length())
        assertEquals(digest(large), digest(target))
        assertNotEquals(inode(large), inode(target))
    }

    @Test fun `超过原快照上限的文件直接永久删除`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(33L * 1024 * 1024) }
        val result = files.delete("s", listOf("/large.bin"))
        assertEquals(listOf("/large.bin"), result.succeeded)
        assertFalse(source.exists())
    }

    @Test fun `大量条目目录和小文件均可永久删除`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "many").apply { mkdir() }
        repeat(1001) { File(source, "item-$it").createNewFile() }
        File(root, "small").writeText("小文件")
        val result = files.delete("s", listOf("/many", "/small"))
        assertTrue(result.failed.isEmpty())
        assertEquals(listOf("/many", "/small"), result.succeeded)
        assertFalse(source.exists())
        assertFalse(File(root, "small").exists())
    }

    @Test fun `永久删除大目录与小文件均不创建回收副本`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "large").apply { mkdir() }
        RandomAccessFile(File(source, "data.bin"), "rw").use { it.setLength(33L * 1024 * 1024) }
        File(root, "small").writeText("小文件")
        assertEquals(listOf("/large", "/small"), files.delete("s", listOf("/large", "/small")).succeeded)
        assertEquals(listOf(".infinity"), root.list()!!.toList())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `来源或祖先为链接时所有文件操作均拒绝`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "target").mkdir()
        val outside = temporary.newFolder().canonicalFile
        File(outside, "a").writeText("界外文件")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        for (path in listOf("/link", "/link/a")) {
            expect(DirectoryFileOpCode.NAME_INVALID) { files.rename("s", path, "renamed") }
            assertEquals(DirectoryFileOpCode.NAME_INVALID, files.move("s", listOf(path), "/target").failed[path])
            assertEquals(DirectoryFileOpCode.NAME_INVALID, files.copy("s", listOf(path), "/target").failed[path])
            assertEquals(DirectoryFileOpCode.NAME_INVALID, files.delete("s", listOf(path)).failed[path])
        }
        assertEquals("界外文件", File(outside, "a").readText())
        assertTrue(File(root, "target").list()!!.isEmpty())
    }

    @Test fun `永久删除仍拒绝目录内的链接与保留条目`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "linked").mkdir()
        File(root, "reserved/.infinity-internal").mkdirs()
        Files.createSymbolicLink(File(root, "linked/link").toPath(), temporary.newFolder().toPath())
        val result = files.delete("s", listOf("/linked", "/reserved"))
        assertEquals(mapOf("/linked" to DirectoryFileOpCode.NAME_INVALID, "/reserved" to DirectoryFileOpCode.NAME_INVALID), result.failed)
        assertTrue(File(root, "linked").exists())
        assertTrue(File(root, "reserved/.infinity-internal").exists())
    }

    @Test fun `原子移动提交时出现同名目标也不覆盖`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "a").writeText("源")
        fs.beforeOperation = { operation, _ -> if (operation == "move") File(root, "b").writeText("并发新增") }
        expect(DirectoryFileOpCode.NAME_EXISTS) { files.rename("s", "/a", "b") }
        assertEquals("源", File(root, "a").readText())
        assertEquals("并发新增", File(root, "b").readText())
    }

    @Test fun `改名与移动的同名目标即使是链接也返回 NAME_EXISTS`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "a").writeText("来源")
        File(root, "target").mkdir()
        val outside = temporary.newFolder().canonicalFile
        Files.createSymbolicLink(File(root, "b").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "target/a").toPath(), File(outside, "missing").toPath())
        expect(DirectoryFileOpCode.NAME_EXISTS) { files.rename("s", "/a", "b") }
        assertEquals(DirectoryFileOpCode.NAME_EXISTS, files.move("s", listOf("/a"), "/target").failed["/a"])
        assertEquals("来源", File(root, "a").readText())
        assertTrue(Files.isSymbolicLink(File(root, "b").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "target/a").toPath()))
    }

    @Test fun `原子移动提交时来源被换成链接会拒绝并恢复链接位置`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "source").mkdir()
        File(root, "source/a").writeText("来源")
        val outside = temporary.newFolder().canonicalFile
        var replaced = false
        fs.beforeOperation = { operation, path ->
            if (operation == "move" && path == File(root, "source").toPath() && !replaced) {
                replaced = true
                Files.move(path, File(root, "saved").toPath())
                Files.createSymbolicLink(path, outside.toPath())
            }
        }
        expect(DirectoryFileOpCode.NAME_INVALID) { files.rename("s", "/source", "renamed") }
        assertFalse(File(root, "renamed").exists())
        assertTrue(Files.isSymbolicLink(File(root, "source").toPath()))
        assertEquals("来源", File(root, "saved/a").readText())
    }

    @Test fun `移动已提交但同步失败时恢复来源并返回 IO`() = runBlocking {
        val base = JvmBoundRealPathFileSystem()
        var failed = false
        fun unwrap(directory: BoundRealPathDirectory) = (directory as? MoveDirectory)?.delegate ?: directory
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = MoveDirectory(directory, ::wrap) { source, target, name ->
            directory.moveNew(source, unwrap(target), name)
            if (!failed) { failed = true; throw IOException("移动后同步失败") }
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val (root, mounts) = fixture(fs)
        File(root, "a").writeText("来源")
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        expect(DirectoryFileOpCode.IO) { files.rename("s", "/a", "b") }
        assertEquals("来源", File(root, "a").readText())
        assertFalse(File(root, "b").exists())
    }

    @Test fun `并发方已完成移动时本次未提交失败不得撤回并发结果`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        File(root, "a").writeText("来源")
        var moved = false
        fs.beforeOperation = { operation, path ->
            if (operation == "move" && !moved) {
                moved = true
                Files.move(path, File(root, "b").toPath())
            }
        }
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        expect(DirectoryFileOpCode.NOT_FOUND) { files.rename("s", "/a", "b") }
        assertFalse(File(root, "a").exists())
        assertEquals("来源", File(root, "b").readText())
    }

    @Test fun `移动补偿提交时目标被替换不把并发文件留在来源位置`() = runBlocking {
        val base = JvmBoundRealPathFileSystem()
        var moves = 0
        lateinit var root: File
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = MoveDirectory(directory, ::wrap) { source, target, name ->
            moves++
            if (moves == 2) {
                Files.move(File(root, "b").toPath(), File(root, "saved-b").toPath())
                File(root, "b").writeText("并发内容")
            }
            directory.moveNew(source, (target as? MoveDirectory)?.delegate ?: target, name)
            if (moves == 1) throw IOException("移动后同步失败")
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val fixture = fixture(fs)
        root = fixture.first
        File(root, "a").writeText("来源")
        val files = DefaultDirectoryWorkspaceFiles(fixture.second, SessionExecutionGate()) { false }
        expect(DirectoryFileOpCode.IO) { files.rename("s", "/a", "b") }
        assertFalse(File(root, "a").exists())
        assertEquals("并发内容", File(root, "b").readText())
        assertEquals("来源", File(root, "saved-b").readText())
    }

    @Test fun `复制发布已提交但同步失败时清理已发布副本`() = runBlocking {
        val base = JvmBoundRealPathFileSystem()
        var failed = false
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = MoveDirectory(directory, ::wrap) { source, target, name ->
            directory.moveNew(source, (target as? MoveDirectory)?.delegate ?: target, name)
            if (name == "source (2)" && !failed) { failed = true; throw IOException("发布后同步失败") }
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val (root, mounts) = fixture(fs)
        File(root, "source").mkdir()
        File(root, "source/a").writeText("来源")
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        assertEquals(DirectoryFileOpCode.IO, files.copy("s", listOf("/source"), "/").failed["/source"])
        assertTrue(failed)
        assertEquals(setOf("source", ".infinity"), root.list()!!.toSet())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
        assertEquals("来源", File(root, "source/a").readText())
    }

    private open class MoveDirectory(
        val delegate: BoundRealPathDirectory,
        private val wrap: (BoundRealPathDirectory) -> BoundRealPathDirectory,
        private val move: (String, BoundRealPathDirectory, String) -> Unit,
    ) : BoundRealPathDirectory by delegate {
        override fun openDirectory(name: String) = wrap(delegate.openDirectory(name))
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) = move(source, targetDirectory, target)
    }

    @Test fun `复制回滚前目标被并发替换时保留并发文件`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        val source = File(root, "source").apply { mkdir() }
        File(source, "a").writeText("来源")
        RandomAccessFile(File(source, "b"), "rw").use { it.setLength(2L * 1024 * 1024) }
        var replacement: File? = null
        fs.beforeOperation = { operation, path ->
            if (operation == "read" && path == File(source, "b").toPath()) throw IOException("读取失败")
            if (operation in setOf("move", "delete") && path.fileName.toString() == "a" && !path.startsWith(source.toPath()) && replacement == null) {
                Files.move(path, path.resolveSibling("saved"))
                path.toFile().writeText("并发文件")
                replacement = path.toFile()
            }
        }
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        assertEquals(setOf("/source"), files.copy("s", listOf("/source"), "/").failed.keys)
        assertNotNull(replacement)
        assertEquals(listOf("并发文件"), root.walkTopDown().filter { it.isFile && it.name == "a" && !it.toPath().startsWith(source.toPath()) }.map { it.readText() }.toList())
        assertEquals("来源", File(source, "a").readText())
    }

    @Test fun `跨卷移动失败返回 IO 且不复制不删除来源`() = runBlocking {
        val fs = JvmBoundRealPathFileSystem()
        val (root, mounts) = fixture(fs)
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "source").mkdir()
        File(root, "source/a").writeText("来源")
        File(root, "target").mkdir()
        fs.beforeOperation = { operation, _ ->
            if (operation == "move") throw FileSystemException("source", "target", "EXDEV")
        }
        assertEquals(DirectoryFileOpCode.IO, files.move("s", listOf("/source"), "/target").failed["/source"])
        expect(DirectoryFileOpCode.IO) { files.rename("s", "/source", "renamed") }
        assertEquals("来源", File(root, "source/a").readText())
        assertTrue(File(root, "target").list()!!.isEmpty())
        assertFalse(File(root, "renamed").exists())
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `复制超过条目和深度快照上限的目录成功`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        val source = File(root, "many").apply { mkdir() }
        repeat(1001) { File(source, "item-$it").createNewFile() }
        val deepPath = (1..34).joinToString("/") { "d" }
        File(source, deepPath).mkdirs()
        File(source, "$deepPath/a").writeText("深层内容")
        assertEquals(listOf("/many"), files.copy("s", listOf("/many"), "/").succeeded)
        assertEquals(1002, File(root, "many (2)").list()!!.size)
        assertEquals("深层内容", File(root, "many (2)/$deepPath/a").readText())
    }

    @Test fun `流式复制读到部分文件后失败会清理副本且不改来源和已有目标`() = copyInterrupted(cancel = false)

    @Test fun `流式复制取消后清理副本并传播取消`() = copyInterrupted(cancel = true)

    private fun copyInterrupted(cancel: Boolean) = runBlocking {
        val base = JvmBoundRealPathFileSystem()
        var interrupted = false
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = object : MoveDirectory(directory, ::wrap, { source, target, name ->
            directory.moveNew(source, (target as? MoveDirectory)?.delegate ?: target, name)
        }) {
            override fun openRead(name: String): BoundRealPathRead {
                val read = directory.openRead(name)
                if (name != "b") return read
                return object : BoundRealPathRead by read {
                    override val input = object : FilterInputStream(read.input) {
                        var copied = 0
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (copied >= 8192) {
                                interrupted = true
                                if (cancel) throw CancellationException("取消复制") else throw IOException("流式读取失败")
                            }
                            return super.read(buffer, offset, length).also { if (it > 0) copied += it }
                        }
                    }
                }
            }
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val (root, mounts) = fixture(fs)
        val source = File(root, "source").apply { mkdir() }
        File(source, "a").writeText("第一份内容")
        RandomAccessFile(File(source, "b"), "rw").use { it.setLength(2L * 1024 * 1024) }
        File(root, "source (2)").mkdir()
        File(root, "source (2)/existing").writeText("已有内容")
        val before = digest(File(source, "b"))
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        if (cancel) assertTrue(runCatching { files.copy("s", listOf("/source"), "/") }.exceptionOrNull() is CancellationException)
        else assertEquals(setOf("/source"), files.copy("s", listOf("/source"), "/").failed.keys)
        assertTrue(interrupted)
        assertFalse(File(root, "source (3)").exists())
        assertEquals(setOf("source", "source (2)", ".infinity"), root.list()!!.toSet())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
        assertEquals("已有内容", File(root, "source (2)/existing").readText())
        assertEquals("第一份内容", File(source, "a").readText())
        assertEquals(before, digest(File(source, "b")))
    }

    private fun inode(file: File) = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()

    private fun digest(file: File): List<Byte> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().toList()
    }

    @Test fun `改名移动复制和删除使用同一挂载且目录永久删除`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        files.createDirectory("s", "/", "source")
        File(root, "source/a.md").writeText("内容")
        assertEquals("/source/b.md", files.rename("s", "//source/a.md", "b.md").path)
        files.createDirectory("s", "/", "target")
        assertEquals(listOf("/source/b.md"), files.move("s", listOf("/source/b.md"), "/target").succeeded)
        assertFalse(File(root, "source/b.md").exists())
        assertEquals(listOf("/target/b.md"), files.copy("s", listOf("/target/b.md"), "/target").succeeded)
        files.copy("s", listOf("/target/b.md"), "/target")
        assertEquals("内容", File(root, "target/b (2).md").readText())
        assertEquals("内容", File(root, "target/b (3).md").readText())
        assertEquals(listOf("/target"), files.delete("s", listOf("/target")).succeeded)
        assertFalse(File(root, "target").exists())
        assertFalse(File(root, "target/b (3).md").exists())
    }

    @Test fun `单项失败继续执行且不覆盖同名文件`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "a").writeText("源")
        File(root, "b").writeText("目标")
        expect(DirectoryFileOpCode.NAME_EXISTS) { files.rename("s", "/a", "b") }
        expect(DirectoryFileOpCode.NAME_INVALID) { files.rename("s", "/a", "../b") }
        expect(DirectoryFileOpCode.NOT_FOUND) { files.rename("s", "/missing", "c") }
        val result = files.delete("s", listOf("/missing", "/../outside", "/a"))
        assertEquals(listOf("/a"), result.succeeded)
        assertEquals(mapOf("/missing" to DirectoryFileOpCode.NOT_FOUND, "/../outside" to DirectoryFileOpCode.NAME_INVALID), result.failed)
        assertEquals("目标", File(root, "b").readText())
        files.createDirectory("s", "/", "target")
        File(root, "target/b").writeText("已有")
        assertEquals(DirectoryFileOpCode.NAME_EXISTS, files.move("s", listOf("/b"), "/target").failed["/b"])
    }

    @Test fun `目录递归复制且目录点文件和无扩展名按完整名称避让`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "notes.md/nested").mkdirs()
        File(root, "notes.md/nested/a").writeText("递归内容")
        File(root, ".env").writeText("点文件")
        File(root, "README").writeText("无扩展名")
        val result = files.copy("s", listOf("/notes.md", "/.env", "/README"), "/")
        assertTrue(result.failed.isEmpty())
        assertEquals("递归内容", File(root, "notes.md (2)/nested/a").readText())
        assertEquals("点文件", File(root, ".env (2)").readText())
        assertEquals("无扩展名", File(root, "README (2)").readText())
    }

    @Test fun `拒绝目录自身子目录符号链接及独立挂载命名空间`() = runBlocking {
        val (root, mounts) = fixture()
        val files = DefaultDirectoryWorkspaceFiles(mounts, SessionExecutionGate()) { false }
        File(root, "dir/child").mkdirs()
        for (target in listOf("/dir", "/dir/child")) {
            assertEquals(DirectoryFileOpCode.TARGET_INSIDE_SOURCE, files.move("s", listOf("/dir"), target).failed["/dir"])
            assertEquals(DirectoryFileOpCode.TARGET_INSIDE_SOURCE, files.copy("s", listOf("/dir"), target).failed["/dir"])
        }
        Files.createSymbolicLink(File(root, "dir/link").toPath(), temporary.newFolder().toPath())
        assertEquals(DirectoryFileOpCode.NAME_INVALID, files.copy("s", listOf("/dir"), "/").failed["/dir"])
        assertEquals(DirectoryFileOpCode.NAME_INVALID, files.delete("s", listOf("/dir/link")).failed["/dir/link"])
        expect(DirectoryFileOpCode.NAME_INVALID) { files.createDirectory("s", "/mnt/other", "x") }
        expect(DirectoryFileOpCode.NAME_INVALID) { files.createDirectory("s", "/skills", "x") }
        assertTrue(File(root, "dir/child").exists())
    }

    @Test fun `不可用忙碌和意外异常转换为契约错误`() = runBlocking {
        val (_, mounts) = fixture()
        val mount = mounts.workspaceMount("s")!!
        val override = mockk<ExternalWorkspaceAccess>()
        coEvery { override.workspaceMount("s") } returns null
        val files = DefaultDirectoryWorkspaceFiles(override, SessionExecutionGate()) { false }
        expect(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE) { files.createDirectory("s", "/", "x") }
        coEvery { override.workspaceMount("s") } returns mount.copy(unavailableReason = "离线")
        expect(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE) { files.createDirectory("s", "/", "x") }
        coEvery { override.workspaceMount("s") } returns mount
        expect(DirectoryFileOpCode.SESSION_BUSY) {
            DefaultDirectoryWorkspaceFiles(override, SessionExecutionGate()) { true }.rename("s", "/a", "b")
        }
        every { override.backend(any(), any()) } throws java.io.IOException("读写失败")
        expect(DirectoryFileOpCode.IO) { files.createDirectory("s", "/", "x") }
    }

    private suspend fun expect(code: DirectoryFileOpCode, block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error.toString(), error is DirectoryFileOpException)
        assertEquals(code, (error as DirectoryFileOpException).code)
    }
}
