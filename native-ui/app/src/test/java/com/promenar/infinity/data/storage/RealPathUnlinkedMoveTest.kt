package com.promenar.infinity.data.storage

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RealPathUnlinkedMoveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fileSystem() = JvmBoundRealPathFileSystem().apply {
        emulateFuse = true
        supportAtomicMoves = false
        renameAcrossVolumes = { _, _ -> true }
        readStateAfterRead = { _, state -> state.copy(revision = "${state.revision}:ctime-drift") }
    }

    @Test fun `源删除报错且句柄ENOENT时完整目标可确认成功`() = runBlocking {
        for (directory in listOf(false, true)) {
            val root = temporary.newFolder().canonicalFile
            val source = File(root, "source")
            if (directory) source.mkdir()
            (if (directory) File(source, "child") else source).writeText("original")
            val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
            fs.afterOperation = { operation, path ->
                if (operation in listOf("delete", "delete-directory") && path == source.toPath()) throw java.io.IOException("源已解除链接后返回错误")
            }
            fs.backend(root).move("/source", "/target")
            assertFalse(source.exists())
            assertEquals("original", File(root, if (directory) "target/child" else "target").readText())
        }
    }

    @Test fun `源删除后句柄ENOENT不能放过目标改写或替换`() = runBlocking {
        for ((replace, content) in listOf(false to "external", true to "original")) {
            val root = temporary.newFolder().canonicalFile
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "target")
            val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
            fs.afterOperation = { operation, path ->
                if (operation == "delete" && path == source.toPath()) {
                    if (replace) Files.move(target.toPath(), File(root, "saved-target").toPath())
                    target.writeText(content)
                    throw java.io.IOException("源已解除链接后返回错误")
                }
            }
            val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
            assertEquals("RECOVERY_REQUIRED", (failure as? ExternalFolderException)?.code)
            assertFalse(source.exists())
            assertEquals(content, target.readText())
            if (replace) assertEquals("original", File(root, "saved-target").readText())
        }
    }

    @Test fun `源删除后被同名文件占用不能确认成功或清除原件副本`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) {
                source.writeText("external")
                throw java.io.IOException("源已解除链接后返回错误")
            }
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as? ExternalFolderException)?.code)
        assertEquals("external", source.readText())
        assertEquals("original", File(root, "target").readText())
    }

    @Test fun `源删除后其它句柄错误不得被当作ENOENT确认成功`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.EIO }
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) throw java.io.IOException("源删除返回错误")
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as? ExternalFolderException)?.code)
        assertFalse(source.exists())
        assertEquals("original", File(root, "target").readText())
    }
    @Test fun `目录部分删除后句柄ENOENT时保留完整目标供恢复`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { mkdir() }
        File(source, "a").writeText("first")
        File(source, "b").writeText("second")
        val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == File(source, "a").toPath()) throw java.io.IOException("第一项删除后报错")
        }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as? ExternalFolderException)?.code)
        assertFalse(File(source, "a").exists())
        assertEquals("second", File(source, "b").readText())
        assertEquals("first", File(root, "target/a").readText())
        assertEquals("second", File(root, "target/b").readText())
    }

    @Test fun `源删除后句柄ENOENT或同名替换遇取消时保留取消与目标`() = runBlocking {
        for (recreate in listOf(false, true)) {
            val root = temporary.newFolder().canonicalFile
            val source = File(root, "source").apply { writeText("original") }
            val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
            val cancelled = kotlinx.coroutines.CancellationException("移动已取消")
            fs.afterOperation = { operation, path ->
                if (operation == "delete" && path == source.toPath()) {
                    if (recreate) source.writeText("external")
                    throw cancelled
                }
            }
            val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
            assertTrue("取消异常必须保留，实际=$failure", failure is kotlinx.coroutines.CancellationException)
            // 协程跨调度器的堆栈恢复可包装异常，原始取消及其恢复信息必须留在异常链中。
            assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
            assertTrue(cancelled.suppressed.any { (it as? ExternalFolderException)?.code == "RECOVERY_REQUIRED" })
            if (recreate) assertEquals("external", source.readText()) else assertFalse(source.exists())
            assertEquals("original", File(root, "target").readText())
        }
    }

    @Test fun `确认目标期间来源重建时不能因先前缺失而报告成功`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val source = File(root, "source").apply { writeText("original") }
        val target = File(root, "target")
        val fs = fileSystem().apply { unlinkedStateErrno = android.system.OsConstants.ENOENT }
        var deleted = false
        fs.afterOperation = { operation, path ->
            if (operation == "delete" && path == source.toPath()) {
                deleted = true
                throw java.io.IOException("源已解除链接后返回错误")
            }
        }
        fs.afterRead = { path -> if (deleted && path == target.toPath()) source.writeText("external") }
        val failure = runCatching { fs.backend(root).move("/source", "/target") }.exceptionOrNull()
        assertEquals("RECOVERY_REQUIRED", (failure as? ExternalFolderException)?.code)
        assertEquals("external", source.readText())
        assertEquals("original", target.readText())
    }

}
