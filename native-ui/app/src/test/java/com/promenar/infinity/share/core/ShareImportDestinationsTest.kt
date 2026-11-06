package com.promenar.infinity.share.core

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ShareRequest
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class ShareImportDestinationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = Uri.parse("content://fixture/data.sqlite")
    private val bytes = byteArrayOf(0, 1, 0xff.toByte(), 3)
    private val request = ShareRequest(listOf(uri), "*/*", "0".repeat(64), 1, "request")
    private fun source() = object : SharedContentSource {
        override fun metadata(uri: Uri) = SharedContentMetadata("data.sqlite", "application/octet-stream", bytes.size.toLong())
        override fun open(uri: Uri) = bytes.inputStream()
        override fun contentSha256(uri: Uri) = sha(bytes)
    }

    @Test
    fun `两类会话落在各自根且存储空间可选根或子文件夹`() = runTest {
        val fixture = ShareWorkspaceFixture(temporary.newFolder())
        val scheduler = mockk<ShareIndexScheduler>()
        val importer = SharedFileImporter(source(), fixture.repo, scheduler)
        for ((id, current) in listOf("current" to true, "other" to false)) {
            val root = fixture.repo.ensureSessionRoot(id)
            val result = importer.import(request, ShareImportDestination.SessionFolder(id, root.uuid, current))
            assertThat(result.created.single().created?.materializedPath).isEqualTo("/data.sqlite")
            assertThat(File(root.physicalRootPath, "data.sqlite").readBytes()).isEqualTo(bytes)
        }
        val root = fixture.root("knowledge")
        val folder = fixture.folder(root.uuid, "/books")
        for ((uuid, path) in listOf(root.uuid to "/", folder.uuid to "/books")) {
            val result = importer.import(request, ShareImportDestination.KnowledgeFolder(root.uuid, uuid, path))
            assertThat(result.created.single().created?.parentUuid).isEqualTo(uuid)
        }
        assertThat(importer.import(request, ShareImportDestination.KnowledgeFolder(root.uuid, "missing", "/bad")).rejected.single().reason)
            .isEqualTo(ShareRejectReason.TargetRequired)
        coVerify(exactly = 0) { scheduler.schedule(any(), any()) }
    }

    @Test
    fun `Linux流式导入原样保存碰撞改名且哈希重试不重复写`() = runTest {
        val root = temporary.newFolder()
        val folder = File(root, "root").apply { mkdirs() }
        File(folder, "data.sqlite").writeText("different")
        val fixture = ShareWorkspaceFixture(temporary.newFolder())
        val scheduler = mockk<ShareIndexScheduler>()
        val importer = SharedFileImporter(source(), fixture.repo, scheduler, shareLinuxFiles(root)) { true }
        importer.linuxFileSystem = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        val destination = ShareImportDestination.LinuxDirectory("/root")
        repeat(2) {
            val item = importer.import(request, destination).created.single()
            assertThat(item.linuxPath).isEqualTo("/root/data (1).sqlite")
            assertThat(item.fileUuid).isNull()
            assertThat(item.created).isNull()
            assertThat(item.indexStatus).isNull()
        }
        assertThat(folder.list()).asList().containsExactly("data.sqlite", "data (1).sqlite")
        assertThat(File(folder, "data (1).sqlite").readBytes()).isEqualTo(bytes)
        coVerify(exactly = 0) { scheduler.schedule(any(), any()) }
    }

    @Test
    fun `Linux未就绪全部拒绝且无效路径不可重试`() = runTest {
        val root = temporary.newFolder()
        File(root, "root").mkdirs()
        File(root, "file").writeText("x")
        Files.createSymbolicLink(File(root, "escape").toPath(), temporary.newFolder().toPath())
        val repo = ShareWorkspaceFixture(temporary.newFolder()).repo
        val files = shareLinuxFiles(root)
        val unavailable = SharedFileImporter(source(), repo, linuxFiles = files)
        assertThat(unavailable.import(request, ShareImportDestination.LinuxDirectory("/root")).rejected.single().reason)
            .isEqualTo(ShareRejectReason.LinuxUnavailable)
        val ready = SharedFileImporter(source(), repo, linuxFiles = files, linuxReady = { true }).apply {
            linuxFileSystem = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        }
        for (path in listOf("/../outside", "/workspace", "/home/session", "/mnt/knowledge", "/proc", "/dev/shm", "/sys", "/file", "/missing", "/escape")) {
            val item = ready.import(request, ShareImportDestination.LinuxDirectory(path)).rejected.single()
            assertThat(item.reason).isEqualTo(ShareRejectReason.LinuxPathInvalid)
            assertThat(item.reason?.retryable).isFalse()
        }
    }

    @Test
    fun `DOCX损坏的UTF8条目名逐项拒绝并保留两种落点的部分成功`() = runTest {
        val malformed = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("a")); zip.write(1); zip.closeEntry()
            }
        }.toByteArray().also { it[30] = 0xff.toByte() }
        val bad = Uri.parse("content://fixture/bad.docx")
        val source = object : SharedContentSource {
            override fun metadata(uri: Uri) = SharedContentMetadata(uri.lastPathSegment,
                if (uri == bad) SharedFileImporter.DOCX_MIME else "application/octet-stream",
                (if (uri == bad) malformed else bytes).size.toLong())
            override fun open(uri: Uri) = (if (uri == bad) malformed else bytes).inputStream()
        }
        val batch = ShareRequest(listOf(uri, bad), "*/*", "0".repeat(64), 1, "request")
        val rootfs = temporary.newFolder()
        File(rootfs, "root").mkdirs()
        val fixture = ShareWorkspaceFixture(temporary.newFolder())
        val root = fixture.root("knowledge")
        val importer = SharedFileImporter(source, fixture.repo, linuxFiles = shareLinuxFiles(rootfs), linuxReady = { true }).apply {
            linuxFileSystem = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        }
        for (target in listOf(ShareImportDestination.LinuxDirectory("/root"), ShareImportDestination.KnowledgeFolder(root.uuid, root.uuid, "/"))) {
            val result = importer.import(batch, target)
            assertThat(result.created).hasSize(1)
            assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
        }
    }

    @Test
    fun `Linux父目录被替换为逃逸链接时固定句柄且不写出rootfs`() = runTest {
        val root = temporary.newFolder().canonicalFile
        val original = File(root, "root").apply { mkdirs() }
        val moved = File(root, "moved")
        val outside = temporary.newFolder().canonicalFile
        val handles = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        handles.beforeOperation = { operation, path ->
            if (operation == "create" && path.fileName.toString() == "data.sqlite") {
                handles.beforeOperation = null
                Files.move(original.toPath(), moved.toPath())
                handles.movedDirectories[original.toPath()] = moved.toPath()
                Files.createSymbolicLink(original.toPath(), outside.toPath())
            }
        }
        val importer = SharedFileImporter(source(), ShareWorkspaceFixture(temporary.newFolder()).repo,
            linuxFiles = shareLinuxFiles(root), linuxReady = { true }).apply { linuxFileSystem = handles }
        val item = importer.import(request, ShareImportDestination.LinuxDirectory("/root")).rejected.single()
        assertThat(item.reason).isEqualTo(ShareRejectReason.LinuxPathInvalid)
        assertThat(outside.list()).isEmpty()
        assertThat(moved.list()).isEmpty()
    }

    @Test
    fun `Linux磁盘写入错误保留已完成项并清理当前半文件`() = runTest {
        val root = temporary.newFolder()
        File(root, "root").mkdirs()
        val base = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        fun wrap(directory: com.promenar.infinity.data.storage.BoundRealPathDirectory): com.promenar.infinity.data.storage.BoundRealPathDirectory =
            object : com.promenar.infinity.data.storage.BoundRealPathDirectory by directory {
                override fun openDirectory(name: String) = wrap(directory.openDirectory(name))
                override fun createFile(name: String): com.promenar.infinity.data.storage.BoundRealPathWrite {
                    val file = directory.createFile(name)
                    return object : com.promenar.infinity.data.storage.BoundRealPathWrite by file {
                        // Robolectric 下 OsConstants 的 errno 常量未初始化（均为 0），ErrnoException 会被当成 EACCES；这里直接抛 IO 失败。
                        override fun sync() { if (name == "bad.sqlite") throw java.io.IOException("fsync: ENOSPC") else file.sync() }
                    }
                }
            }
        val failing = object : com.promenar.infinity.data.storage.RealPathFileSystem {
            override fun openFileSystemRoot() = wrap(base.openFileSystemRoot())
        }
        val bad = Uri.parse("content://fixture/bad.sqlite")
        val source = object : SharedContentSource {
            override fun metadata(uri: Uri) = SharedContentMetadata(uri.lastPathSegment, "application/octet-stream", bytes.size.toLong())
            override fun open(uri: Uri) = bytes.inputStream()
        }
        val importer = SharedFileImporter(source, ShareWorkspaceFixture(temporary.newFolder()).repo,
            linuxFiles = shareLinuxFiles(root), linuxReady = { true }).apply { linuxFileSystem = failing }
        val result = importer.import(ShareRequest(listOf(uri, bad), "*/*", "0".repeat(64), 1, "request"), ShareImportDestination.LinuxDirectory("/root"))
        assertThat(result.created).hasSize(1)
        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.WriteFailed)
        assertThat(File(root, "root").list()).asList().containsExactly("data.sqlite")
    }

    @Test
    fun `Linux写入中途源读取失败删除半文件`() = runTest {
        val root = temporary.newFolder()
        File(root, "root").mkdirs()
        val source = object : SharedContentSource {
            override fun metadata(uri: Uri) = SharedContentMetadata("data.sqlite", "application/octet-stream", null)
            override fun open(uri: Uri) = object : java.io.InputStream() {
                var read = false
                override fun read(): Int = if (!read) { read = true; 1 } else throw java.io.IOException("fixture")
            }
        }
        val importer = SharedFileImporter(source, ShareWorkspaceFixture(temporary.newFolder()).repo,
            linuxFiles = shareLinuxFiles(root), linuxReady = { true }).apply {
            linuxFileSystem = com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem()
        }
        assertThat(importer.import(request, ShareImportDestination.LinuxDirectory("/root")).rejected.single().reason)
            .isEqualTo(ShareRejectReason.ReadFailed)
        assertThat(File(root, "root").list()).isEmpty()
    }
}
