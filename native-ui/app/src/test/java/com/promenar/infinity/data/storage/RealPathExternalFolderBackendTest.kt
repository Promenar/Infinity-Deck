package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException

class RealPathExternalFolderBackendTest : ExternalFolderBackendContractTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    override fun createBackend(): ExternalFolderBackend = JvmBoundRealPathFileSystem().backend(temporaryFolder.newFolder().canonicalFile)

    @Test fun `FUSE大文件流写回执与无摘要stat使用同一对象身份`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().apply { emulateFuse = true }.backend(root)
        val receipt = backend.writeStream("/file", 2L * 1024 * 1024) { output ->
            val block = ByteArray(64 * 1024) { (it % 251).toByte() }
            repeat(32) { output.write(block) }
        }
        val current = backend.stat("/file")!!
        assertNull(current.sha256)
        assertEquals(receipt.identity, current.identity)
    }

    @Test fun `条件删除拒绝同内容新对象和非空目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().backend(root)
        val original = backend.write("/file", "same".toByteArray(), createOnly = true)
        Files.move(File(root, "file").toPath(), File(root, "saved").toPath())
        File(root, "file").writeText("same")
        expectCode("CONFLICT") { backend.deleteForTransfer("/file", original, original.sha256) }
        val directory = backend.createDirectory("/dir")
        File(root, "dir/new").writeText("外部新增")
        org.junit.Assert.assertNotNull(runCatching { backend.deleteForTransfer("/dir", directory, null) }.exceptionOrNull())
        assertEquals("外部新增", File(root, "dir/new").readText())
    }

    @Test fun `真实目录深度超限预检拒绝且未创建目标`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val source = File(root, "source").apply { mkdir() }
        var current = source
        repeat(33) { current = File(current, "child").apply { mkdir() } }
        val backend = JvmBoundRealPathFileSystem().backend(root)
        expectCode("TOO_LARGE") { ExternalTreeTransfer.move(backend, "/source", backend, "/target") }
        assertTrue(source.exists()); assertFalse(File(root, "target").exists())
    }

    @Test fun `不支持原子不覆盖移动时独占发布新文件`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = withoutAtomicMoves(root)
        backend.write("/file", "complete".toByteArray(), createOnly = true)
        assertEquals("complete", File(root, "file").readText())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `独占发布保留并发占用目标的内容`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "create" && path == File(root, "file").toPath()) File(root, "file").writeText("concurrent")
        }
        expectCode("CONFLICT") { withoutAtomicMoves(root, fs).write("/file", "ours".toByteArray(), createOnly = true) }
        assertEquals("concurrent", File(root, "file").readText())
    }

    @Test fun `独占复制期间目标被替换时保留替换者且返回冲突`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        var replaced = false
        fs.beforeOperation = { operation, path ->
            val target = File(root, "file")
            if (!replaced && operation == "read" && path.fileName.toString().startsWith(".infinity-write-") && target.exists()) {
                replaced = true
                Files.move(target.toPath(), File(root, "saved").toPath())
                target.writeText("concurrent")
            }
        }
        expectCode("RECOVERY_REQUIRED") { withoutAtomicMoves(root, fs).write("/file", "ours".toByteArray(), createOnly = true) }
        assertTrue(replaced)
        assertEquals("concurrent", File(root, "file").readText())
        assertEquals("ours", File(root, "saved").readText())
    }

    @Test fun `独占复制前发布源被修改时保留目标与暂存供恢复`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        var modified = false
        fs.beforeOperation = { operation, path ->
            if (!modified && operation == "read" && path.fileName.toString().startsWith(".infinity-write-") && File(root, "file").exists()) {
                modified = true
                path.toFile().writeText("changed-source")
            }
        }
        expectCode("RECOVERY_REQUIRED") { withoutAtomicMoves(root, fs).write("/file", "ours".toByteArray(), createOnly = true) }
        assertTrue(modified)
        assertTrue(File(root, "file").exists())
        assertTrue(File(root, ".infinity").list()!!.any { it.startsWith(".infinity-write-") })
    }

    @Test fun `独占发布失败不按路径清理用户目标`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        var targetCleanupAttempted = false
        fs.beforeOperation = { operation, path ->
            if (operation == "delete" && path == File(root, "file").toPath()) targetCleanupAttempted = true
            if (operation == "read" && path.fileName.toString().startsWith(".infinity-write-") && File(root, "file").exists()) {
                throw IOException("复制读取失败")
            }
        }
        expectCode("RECOVERY_REQUIRED") { withoutAtomicMoves(root, fs).write("/file", "ours".toByteArray(), createOnly = true) }
        assertTrue(!targetCleanupAttempted)
        assertTrue(File(root, "file").exists())
        val temporary = File(root, ".infinity").listFiles()!!.single { it.name.startsWith(".infinity-write-") }
        assertEquals("ours", temporary.readText())
    }

    @Test fun `独占发布被取消时仍抛取消并保留恢复副本`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "read" && path.fileName.toString().startsWith(".infinity-write-") && File(root, "file").exists()) {
                throw kotlinx.coroutines.CancellationException("取消复制")
            }
        }
        val failure = runCatching { withoutAtomicMoves(root, fs).write("/file", "ours".toByteArray(), createOnly = true) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { cause ->
            cause.suppressed.any { it is ExternalFolderException && it.code == "RECOVERY_REQUIRED" }
        })
        assertTrue(File(root, "file").exists())
        val temporary = File(root, ".infinity").listFiles()!!.single { it.name.startsWith(".infinity-write-") }
        assertEquals("ours", temporary.readText())
    }

    @Test fun `不支持原子不覆盖移动的卷可覆盖移动复制及递归删除`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = withoutAtomicMoves(root, allowCheckedMove = true)
        backend.createDirectory("/notes")
        val original = backend.write("/notes/file", "original".toByteArray(), createOnly = true)
        backend.write("/notes/file", "changed".toByteArray(), original.sha256)
        backend.move("/notes/file", "/notes/renamed")
        backend.move("/notes", "/moved")
        backend.copy("/moved", "/copied")
        assertEquals("changed", File(root, "moved/renamed").readText())
        assertEquals("changed", File(root, "copied/renamed").readText())
        backend.delete("/moved/renamed")
        backend.delete("/copied")
        backend.delete("/moved")
        assertTrue(backend.list("/").isEmpty())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `放宽覆盖在替换前保持原文且同目录暂存完整内容`() = runBlocking {
        for (failCommit in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            File(root, "notes").mkdir()
            val original = File(root, "notes/file").apply { writeText("original") }
            val fs = JvmBoundRealPathFileSystem()
            var checked = false
            fs.beforeOperation = { operation, path ->
                if (operation == "replace" && path == original.toPath()) {
                    checked = true
                    assertEquals("original", original.readText())
                    val staged = original.parentFile!!.listFiles()!!.single { it.name.startsWith(".infinity-write-") }
                    assertEquals("complete", staged.readText())
                    if (failCommit) throw IOException("替换尚未提交")
                }
            }
            val backend = withoutAtomicMoves(root, fs, allowCheckedMove = true)
            val hash = backend.stat("/notes/file")!!.sha256
            if (failCommit) expectCode("UNAVAILABLE") { backend.write("/notes/file", "complete".toByteArray(), hash) }
            else backend.write("/notes/file", "complete".toByteArray(), hash)
            assertTrue(checked)
            assertEquals(if (failCommit) "original" else "complete", original.readText())
            assertEquals(listOf("file"), original.parentFile!!.list()!!.toList())
        }
    }

    @Test fun `普通移动失败时保留源且不伪报成功`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source").writeText("original")
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, _ -> if (operation == "checked-move") throw IOException("移动未提交") }
        expectCode("IO") { withoutAtomicMoves(root, fs, allowCheckedMove = true).move("/source", "/target") }
        assertEquals("original", File(root, "source").readText())
        assertTrue(!File(root, "target").exists())
    }

    @Test fun `普通复制发布已提交但同步失败时清理本次副本`() = runBlocking {
        for (directory in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            val source = File(root, "source")
            if (directory) source.mkdir()
            val file = if (directory) File(source, "child") else source
            file.writeText("original")
            var failed = false
            val backend = withoutAtomicMoves(root, allowCheckedMove = true, afterCheckedMove = { name, _ ->
                if (!failed && name.startsWith(".infinity-copy-")) {
                    failed = true
                    throw IOException("普通移动已提交，目录同步失败")
                }
            })
            expectCode("IO") { backend.copy("/source", "/target") }
            assertTrue(failed)
            assertEquals("original", file.readText())
            assertTrue(!File(root, "target").exists())
            assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
        }
    }

    @Test fun `放宽覆盖在能力探测后拒绝被替换的原件`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val target = File(root, "file").apply { writeText("original") }
        var changed = false
        val backend = withoutAtomicMoves(root, allowCheckedMove = true, beforeUnsupportedMove = { source, _ ->
            if (source == "file" && !changed) {
                changed = true
                Files.move(target.toPath(), File(root, "saved").toPath())
                target.writeText("concurrent")
            }
        })
        val hash = backend.stat("/file")!!.sha256
        expectCode("CONFLICT") { backend.write("/file", "ours".toByteArray(), hash) }
        assertEquals("concurrent", target.readText())
        assertEquals("original", File(root, "saved").readText())
    }

    @Test fun `放宽移动删除在能力探测后拒绝身份变化`() = runBlocking {
        for (deleting in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            val target = File(root, "file").apply { writeText("original") }
            var changed = false
            val backend = withoutAtomicMoves(root, allowCheckedMove = true, beforeUnsupportedMove = { source, _ ->
                if (source == "file" && !changed) {
                    changed = true
                    Files.move(target.toPath(), File(root, "saved").toPath())
                    target.writeText("concurrent")
                }
            })
            expectCode("CONFLICT") { if (deleting) backend.delete("/file") else backend.move("/file", "/target") }
            assertEquals("concurrent", target.readText())
            assertEquals("original", File(root, "saved").readText())
            assertTrue(!File(root, "target").exists())
        }
    }

    @Test fun `放宽移动和新建在能力探测后拒绝被占用的目标名`() = runBlocking {
        for (creating in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            File(root, "source").writeText("original")
            val backend = withoutAtomicMoves(root, allowCheckedMove = true, beforeUnsupportedMove = { _, target ->
                if (target == "target") File(root, "target").writeText("concurrent")
            })
            expectCode(if (creating) "CONFLICT" else "NAME_EXISTS") {
                if (creating) backend.write("/target", "ours".toByteArray(), createOnly = true)
                else backend.move("/source", "/target")
            }
            assertEquals("concurrent", File(root, "target").readText())
            assertEquals("original", File(root, "source").readText())
        }
    }

    @Test fun `独占创建后返回句柄前失败时保留完整暂存`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        expectCode("RECOVERY_REQUIRED") { withoutAtomicMoves(root, failAfterCreate = true).write("/file", "ours".toByteArray(), createOnly = true) }
        assertTrue(File(root, "file").exists())
        val temporary = File(root, ".infinity").listFiles()!!.single { it.name.startsWith(".infinity-write-") }
        assertEquals("ours", temporary.readText())
    }

    @Test fun `跨父目录移动改变目录时间时递归删除成功`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "dir/a/b").mkdirs()
        File(root, "dir/a/b/file").writeText("内容")
        driftingDirectoryBackend(root).delete("/dir")
        assertFalse(File(root, "dir").exists())
        assertTrue(File(root, ".infinity").list()!!.none { it.startsWith(".infinity-delete-") })
    }

    @Test fun `隔离校验失败时完整目录恢复`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "dir/a").mkdirs()
        File(root, "dir/a/file").writeText("内容")
        expectCode("CONFLICT") { driftingDirectoryBackend(root, failVerification = true).delete("/dir") }
        assertEquals("内容", File(root, "dir/a/file").readText())
        assertTrue(File(root, ".infinity").list()!!.none { it.startsWith(".infinity-delete-") })
    }

    @Test fun `原名被占用时不覆盖并保留隔离目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "dir").mkdir()
        File(root, "dir/file").writeText("原内容")
        expectCode("RECOVERY_REQUIRED") { driftingDirectoryBackend(root, failVerification = true, occupyOriginal = true).delete("/dir") }
        assertTrue(File(root, "dir").isDirectory)
        val isolated = File(root, ".infinity").listFiles()!!.single { it.name.startsWith(".infinity-delete-") }
        assertEquals("原内容", File(isolated, "file").readText())
    }

    @Test fun `删除子项后失败时保留隔离目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "dir").mkdir()
        File(root, "dir/a").writeText("内容")
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "delete-directory" && path.fileName.toString().startsWith(".infinity-delete-")) throw IOException("删除目录失败")
        }
        expectCode("RECOVERY_REQUIRED") { driftingDirectoryBackend(root, fs).delete("/dir") }
        assertFalse(File(root, "dir").exists())
        assertTrue(File(root, ".infinity").listFiles()!!.any { it.name.startsWith(".infinity-delete-") && it.isDirectory })
    }

    @Test fun `删除子项后取消时提示恢复并保留隔离目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "dir").mkdir()
        File(root, "dir/a").writeText("内容")
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "delete-directory" && path.fileName.toString().startsWith(".infinity-delete-")) {
                throw kotlinx.coroutines.CancellationException("取消删除")
            }
        }
        expectCode("RECOVERY_REQUIRED") { driftingDirectoryBackend(root, fs).delete("/dir") }
        assertFalse(File(root, "dir").exists())
        assertTrue(File(root, ".infinity").listFiles()!!.any { it.name.startsWith(".infinity-delete-") && it.isDirectory })
    }

    private fun driftingDirectoryBackend(root: File, base: JvmBoundRealPathFileSystem = JvmBoundRealPathFileSystem(),
        failVerification: Boolean = false, occupyOriginal: Boolean = false): RealPathExternalFolderBackend {
        var failName: String? = null
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = object : BoundRealPathDirectory by directory {
            override fun openDirectory(name: String) = wrap(directory.openDirectory(name))
            override fun stat(name: String): RealPathFileState? {
                val state = directory.stat(name)
                if (name == failName && state != null) {
                    failName = null
                    if (occupyOriginal) check(File(root, "dir").mkdir())
                    return state.copy(objectId = "changed")
                }
                return state
            }
            override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) {
                val targetWrapped = targetDirectory as WrappedDirectory
                val isDirectory = directory.stat(source)?.isDirectory == true
                directory.moveNew(source, targetWrapped.delegate, target)
                if (isDirectory && target.startsWith(".infinity-delete-")) {
                    val moved = File(root, ".infinity/$target")
                    check(moved.setLastModified(moved.lastModified() - 5000))
                    if (failVerification && source == "dir") failName = target
                }
            }
        }.let { WrappedDirectory(it, directory) }
        return RealPathExternalFolderBackend(root, emptySet(), { true }, object : RealPathFileSystem {
            override fun openFileSystemRoot() = wrap(base.openFileSystemRoot())
        })
    }

    private class WrappedDirectory(private val wrapped: BoundRealPathDirectory, val delegate: BoundRealPathDirectory) :
        BoundRealPathDirectory by wrapped

    private fun withoutAtomicMoves(root: File, base: JvmBoundRealPathFileSystem = JvmBoundRealPathFileSystem(), failAfterCreate: Boolean = false, allowCheckedMove: Boolean = false,
        beforeUnsupportedMove: (String, String) -> Unit = { _, _ -> },
        afterCheckedMove: (String, String) -> Unit = { _, _ -> }): RealPathExternalFolderBackend {
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory {
            return PublishDirectory(directory, ::wrap, create = { name ->
                    val file = directory.createFile(name)
                    if (failAfterCreate && name == "file") { file.close(); throw IOException("独占创建后读取状态失败") }
                    file
                }, ordinaryMove = { source, targetDirectory, target ->
                    if (!allowCheckedMove) throw AtomicMoveNotSupportedException(null, null, "普通移动也不可用")
                    directory.moveReplacing(source, (targetDirectory as PublishDirectory).delegate, target)
                    afterCheckedMove(source, target)
                }) { source, _, target ->
                beforeUnsupportedMove(source, target)
                throw AtomicMoveNotSupportedException(null, null, "存储卷不支持原子不覆盖移动")
            }
        }
        return RealPathExternalFolderBackend(root, emptySet(), { true }, object : RealPathFileSystem {
            override fun openFileSystemRoot() = wrap(base.openFileSystemRoot())
        })
    }

    @Test fun `写入删除复制的暂存与隔离仅位于根隐藏目录`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "notes").mkdir()
        val fs = JvmBoundRealPathFileSystem()
        val internalOperations = mutableListOf<java.nio.file.Path>()
        fs.beforeOperation = { _, path ->
            if (path.fileName.toString().startsWith(".infinity-")) internalOperations.add(path)
        }
        val backend = fs.backend(root)
        val first = backend.write("/notes/file.txt", "original".toByteArray())
        backend.write("/notes/file.txt", "changed".toByteArray(), first.sha256)
        backend.copy("/notes/file.txt", "/notes/copied.txt")
        backend.delete("/notes/file.txt")
        assertTrue(internalOperations.isNotEmpty())
        assertTrue(internalOperations.all { it.startsWith(File(root, ".infinity").toPath()) })
        assertEquals("*\n", File(root, ".infinity/.gitignore").readText())
        assertEquals(listOf("copied.txt"), File(root, "notes").list()!!.toList())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    @Test fun `流式读取逐块交出内容并按上限拒绝`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        File(root, "big.bin").writeBytes(content)
        val backend = JvmBoundRealPathFileSystem().backend(root)
        assertArrayEquals(content, backend.readStream("/big.bin", content.size.toLong()) { it.readBytes() })
        val tooLarge = runCatching { backend.readStream("/big.bin", content.size - 1L) { it.readBytes() } }.exceptionOrNull()
        assertEquals("TOO_LARGE", (tooLarge as ExternalFolderException).code)
        val directory = runCatching { backend.readStream("/", 1L) { it.read() } }.exceptionOrNull()
        assertEquals("IS_DIRECTORY", (directory as ExternalFolderException).code)
    }

    @Test fun `根隐藏目录的忽略规则仅在创建时写入`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.write("/a", "a".toByteArray())
        val ignore = File(root, ".infinity/.gitignore")
        assertEquals("*\n", ignore.readText())
        ignore.writeText("*.tmp\n")
        backend.write("/b", "b".toByteArray())
        assertEquals("*.tmp\n", ignore.readText())
        ignore.delete()
        JvmBoundRealPathFileSystem().backend(root).write("/c", "c".toByteArray())
        assertTrue(!ignore.exists())
    }

    @Test fun `拒绝将根隐藏目录链接至挂载外部`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val outside = temporaryFolder.newFolder().canonicalFile
        Files.createSymbolicLink(File(root, ".infinity").toPath(), outside.toPath())
        val backend = JvmBoundRealPathFileSystem().backend(root)
        expectCode("INVALID_PATH") { backend.write("/new", "a".toByteArray()) }
        assertTrue(outside.list()!!.isEmpty())
        assertTrue(!File(root, "new").exists())
    }

    @Test fun `同卷发布先原子移动且不在用户目标创建半成品`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fs = JvmBoundRealPathFileSystem()
        fs.beforeOperation = { operation, path ->
            if (operation == "create" && path == File(root, "file").toPath()) throw IOException("用户目标不得先创建半成品")
        }
        fs.backend(root).write("/file", "complete".toByteArray())
        assertEquals("complete", File(root, "file").readText())
    }

    @Test fun `首次访问将已知旧暂存节点保留至根隐藏目录并保持原相对位置`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".infinity-write-11111111-2222-4333-8444-555555555555"
        val nested = ".infinity-copy-cleanup-66666666-7777-4888-8999-aaaaaaaaaaaa"
        File(root, name).writeText("root-recovery")
        File(root, "notes/$nested").mkdirs()
        File(root, "notes/$nested/data").writeText("nested-recovery")
        File(root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").writeText("user")
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.list("/")
        backend.list("/notes")
        assertEquals("root-recovery", File(root, ".infinity/legacy/$name").readText())
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
        assertTrue(!File(root, name).exists())
        assertTrue(!File(root, "notes/$nested").exists())
        assertEquals("user", File(root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").readText())
        JvmBoundRealPathFileSystem().backend(root).list("/notes")
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
    }

    @Test fun `更名前暂存节点迁入当前隐藏目录并保留未知文件`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".nexara-write-11111111-2222-4333-8444-555555555555"
        val nested = ".nexara-copy-cleanup-66666666-7777-4888-8999-aaaaaaaaaaaa"
        File(root, name).writeText("root-recovery")
        File(root, "notes/$nested").mkdirs()
        File(root, "notes/$nested/data").writeText("nested-recovery")
        File(root, ".nexara-unknown-11111111-2222-4333-8444-555555555555").writeText("user")
        val backend = JvmBoundRealPathFileSystem().backend(root)
        backend.list("/")
        backend.list("/notes")
        assertEquals("root-recovery", File(root, ".infinity/legacy/$name").readText())
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
        assertTrue(!File(root, name).exists())
        assertTrue(!File(root, "notes/$nested").exists())
        assertEquals("user", File(root, ".nexara-unknown-11111111-2222-4333-8444-555555555555").readText())
        JvmBoundRealPathFileSystem().backend(root).list("/notes")
        assertEquals("nested-recovery", File(root, ".infinity/legacy/notes/$nested/data").readText())
    }

    @Test fun `旧暂存迁移冲突或失败不覆盖不删除且可重试`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val name = ".infinity-replace-11111111-2222-4333-8444-555555555555"
        val source = File(root, "notes/$name").apply { parentFile!!.mkdirs(); writeText("source") }
        val target = File(root, ".infinity/legacy/notes/$name").apply { parentFile!!.mkdirs(); writeText("preserved") }
        val fs = JvmBoundRealPathFileSystem()
        fs.backend(root).list("/notes")
        assertEquals("source", source.readText())
        assertEquals("preserved", target.readText())
        target.delete()
        var migrationRejected = false
        fs.beforeOperation = { operation, path ->
            if (operation == "replace" && path == source.toPath()) {
                migrationRejected = true
                throw IOException("迁移暂不可用")
            }
        }
        fs.backend(root).list("/notes")
        assertTrue(migrationRejected)
        assertEquals("source", source.readText())
        fs.beforeOperation = null
        fs.backend(root).list("/notes")
        assertTrue(!source.exists())
        assertEquals("source", target.readText())
    }

    @Test fun `发布已移动后报错只清理未修改的本次目标且保留并发替换`() = runBlocking {
        for (replace in listOf(false, true)) {
            val root = temporaryFolder.newFolder().canonicalFile
            var fail = true
            val base = JvmBoundRealPathFileSystem()
            fun unwrap(directory: BoundRealPathDirectory) = (directory as? PublishDirectory)?.delegate ?: directory
            fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = PublishDirectory(directory, ::wrap) { source, target, name ->
                directory.moveNew(source, unwrap(target), name)
                if (source.startsWith(".infinity-write-") && fail) {
                    fail = false
                    if (replace) {
                        Files.move(File(root, name).toPath(), File(root, "saved").toPath())
                        File(root, name).writeText("concurrent")
                    }
                    throw IOException("发布后同步失败")
                }
            }
            val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
            expectCode("UNAVAILABLE") { RealPathExternalFolderBackend(root, emptySet(), { true }, fs).write("/file", "complete".toByteArray()) }
            if (replace) assertEquals("concurrent", File(root, "file").readText())
            else assertTrue(!File(root, "file").exists())
        }
    }

    @Test fun `发布失败后补偿移动已提交但同步报错仍保留唯一原文`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "file").writeText("original")
        var publishFailed = false
        var recoveryCommitted = false
        val base = JvmBoundRealPathFileSystem()
        fun unwrap(directory: BoundRealPathDirectory) = (directory as? PublishDirectory)?.delegate ?: directory
        fun wrap(directory: BoundRealPathDirectory): BoundRealPathDirectory = PublishDirectory(directory, ::wrap) { source, target, name ->
            if (source.startsWith(".infinity-write-") && !publishFailed) {
                publishFailed = true
                throw IOException("发布前失败")
            }
            directory.moveNew(source, unwrap(target), name)
            if (source.startsWith(".infinity-replace-")) {
                recoveryCommitted = true
                throw IOException("补偿移动已提交但同步失败")
            }
        }
        val fs = object : RealPathFileSystem { override fun openFileSystemRoot() = wrap(base.openFileSystemRoot()) }
        val backend = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)
        val original = backend.stat("/file")!!
        expectCode("UNAVAILABLE") { backend.write("/file", "changed".toByteArray(), original.sha256) }
        assertTrue(publishFailed)
        assertTrue(recoveryCommitted)
        assertEquals("original", File(root, "file").readText())
        assertEquals(listOf(".gitignore"), File(root, ".infinity").list()!!.toList())
    }

    private class PublishDirectory(
        val delegate: BoundRealPathDirectory,
        private val wrap: (BoundRealPathDirectory) -> BoundRealPathDirectory,
        private val create: ((String) -> BoundRealPathWrite)? = null,
        private val ordinaryMove: ((String, BoundRealPathDirectory, String) -> Unit)? = null,
        private val move: (String, BoundRealPathDirectory, String) -> Unit,
    ) : BoundRealPathDirectory by delegate {
        override fun openDirectory(name: String) = wrap(delegate.openDirectory(name))
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) = move(source, targetDirectory, target)
        override fun createFile(name: String) = create?.invoke(name) ?: delegate.createFile(name)
        override fun moveReplacing(source: String, targetDirectory: BoundRealPathDirectory, target: String) =
            ordinaryMove?.invoke(source, targetDirectory, target) ?:
                delegate.moveReplacing(source, (targetDirectory as? PublishDirectory)?.delegate ?: targetDirectory, target)
    }

    @Test fun `目标祖先目录实际绑定到来源子目录时拒绝复制与移动`() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "source/nested").mkdirs()
        File(root, "source/a").writeText("来源")
        File(root, "alias").mkdir()
        val fs = JvmBoundRealPathFileSystem()
        fs.movedDirectories[File(root, "alias").toPath()] = File(root, "source").toPath()
        val backend = fs.backend(root)
        expectCode("TARGET_INSIDE_SOURCE") { backend.copy("/source", "/alias/nested/copy") }
        expectCode("TARGET_INSIDE_SOURCE") { backend.move("/source", "/alias/nested/moved") }
        assertEquals("来源", File(root, "source/a").readText())
        assertEquals(emptyList<String>(), File(root, "source/nested").list()!!.toList())
    }

    @Test
    fun rejectsSymbolicLinksForReadWriteListDeleteAndRoot() = runBlocking {
        val root = temporaryFolder.newFolder("root").canonicalFile
        val outside = temporaryFolder.newFolder("outside").canonicalFile
        File(outside, "secret.txt").writeText("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val backend = JvmBoundRealPathFileSystem().backend(root)
        expectCode("INVALID_PATH") { backend.read("/link/secret.txt") }
        expectCode("INVALID_PATH") { backend.write("/link/new.txt", "wrong".toByteArray()) }
        expectCode("INVALID_PATH") { backend.list("/link") }
        expectCode("INVALID_PATH") { backend.delete("/link") }
        assertArrayEquals("outside".toByteArray(), File(outside, "secret.txt").readBytes())
        val rootLink = File(root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), outside.toPath())
        expectCode("INVALID_PATH") { JvmBoundRealPathFileSystem().backend(rootLink).stat("/") }
    }

    @Test
    fun directoryListingDoesNotExposeInternalTemporaryFiles() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, ".infinity-write-tmp").writeText("internal")
        File(root, ".recycle_bin").mkdir()
        File(root, "visible.txt").writeText("visible")
        assertEquals(listOf("visible.txt"), JvmBoundRealPathFileSystem().backend(root).list("/").map { it.name })
    }

    @Test
    fun volumeMountCannotEnterProtectedAndroidTreeThroughCaseVariants() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "aNdRoId/data/other").mkdirs()
        File(root, "notes").mkdirs()
        val backend = JvmBoundRealPathFileSystem().backend(root, setOf(File(root, "Android").toPath()))
        expectCode("INVALID_PATH") { backend.stat("/aNdRoId/data") }
        expectCode("INVALID_PATH") { backend.write("/aNdRoId/data/new.txt", byteArrayOf()) }
        expectCode("INVALID_PATH") { backend.delete("/aNdRoId") }
        assertEquals(listOf("notes"), backend.list("/").map { it.name })
    }

    @Test
    fun existingBackendRechecksPermissionForEveryOperation() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        var allowed = true
        val backend = JvmBoundRealPathFileSystem().backend(root, permissionCheck = { allowed })
        backend.write("/file.txt", "original".toByteArray())
        allowed = false
        expectCode("UNAVAILABLE") { backend.read("/file.txt") }
        expectCode("UNAVAILABLE") { backend.write("/file.txt", byteArrayOf()) }
        assertArrayEquals("original".toByteArray(), File(root, "file.txt").readBytes())
    }

    @Test
    fun largeFileStatDoesNotOpenContentAndDirectoryIdentitySurvivesChildMutations() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        File(root, "large.bin").writeBytes(ByteArray(1024 * 1024 + 1))
        val fileSystem = JvmBoundRealPathFileSystem()
        val backend = fileSystem.backend(root)
        assertNull(backend.stat("/large.bin")!!.sha256)
        assertEquals(emptyList<java.nio.file.Path>(), fileSystem.reads)
        val directory = backend.createDirectory("/notes")
        backend.write("/notes/a.txt", "a".toByteArray())
        assertEquals(directory.identity, backend.stat("/notes")!!.identity)
        backend.delete("/notes/a.txt")
        assertEquals(directory.identity, backend.stat("/notes")!!.identity)
    }

    @Test
    fun hardLinkPublicationReturnsIdentityAfterTemporaryLinkCleanup() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val fileSystem = JvmBoundRealPathFileSystem().apply { supportHardLinks = true }
        val backend = fileSystem.backend(root)
        val created = backend.write("/file.txt", "inside".toByteArray(), createOnly = true)
        assertEquals(created.identity, backend.stat("/file.txt")!!.identity)
    }

    @Test
    fun disappearedMountRootIsUnavailableInsteadOfAnAbsentUserFile() = runBlocking {
        val root = temporaryFolder.newFolder().canonicalFile
        val backend = JvmBoundRealPathFileSystem().backend(root)
        root.delete()
        expectCode("UNAVAILABLE") { backend.stat("/") }
    }
}
