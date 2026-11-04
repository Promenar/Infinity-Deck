package com.promenar.infinity.data.files

import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.files.FileRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileManagerArchivesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `压缩解压往返`() = runBlocking {
        val source = MemoryFileBackend().apply {
            addDirectory("/folder")
            addFile("/folder/file.txt", "内容".toByteArray())
        }
        val target = MemoryFileBackend()
        val handle = FileBackendHandle("external:source", source, emptyList(), true)
        FileManagerArchives.compress(temporary.root, listOf(FileRef("external:source", "/folder") to handle), target, "/files.zip")
        FileManagerArchives.extract(temporary.root, target, "/files.zip", target, "/extracted")
        assertArrayEquals("内容".toByteArray(), target.bytes("/extracted/folder/file.txt"))
        assertEquals(2, FileManagerArchives.preview(temporary.root, target, "/files.zip").totalEntries)
    }

    @Test fun `路径穿越在写入目标前拒绝`() = runBlocking {
        val source = MemoryFileBackend().apply { addFile("/bad.zip", zip("../escape.txt", byteArrayOf(1))) }
        val target = MemoryFileBackend()
        assertCode(FileOpCode.INVALID_NAME) { FileManagerArchives.extract(temporary.root, source, "/bad.zip", target, "/out") }
        assertNull(target.stat("/out"))
    }

    @Test fun `解压超过单文件与总大小时拒绝`() = runBlocking {
        val source = MemoryFileBackend().apply { addFile("/large.zip", zip("big.txt", ByteArray(12))) }
        val target = MemoryFileBackend()
        assertCode(FileOpCode.TOO_LARGE) {
            FileManagerArchives.extract(temporary.root, source, "/large.zip", target, "/out", ArchiveLimits(singleBytes = 8, totalBytes = 10))
        }
        assertNull(target.stat("/out"))
    }


    @Test fun `解压总量超限时目标保持空`() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("first.txt", "second.txt").forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(6)); zip.closeEntry()
            }
        }
        val source = MemoryFileBackend().apply { addFile("/large.zip", output.toByteArray()) }
        val target = MemoryFileBackend()
        assertCode(FileOpCode.TOO_LARGE) {
            FileManagerArchives.extract(temporary.root, source, "/large.zip", target, "/out", ArchiveLimits(singleBytes = 10, totalBytes = 10))
        }
        assertNull(target.stat("/out"))
    }

    private fun zip(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        return output.toByteArray()
    }

    private suspend fun assertCode(code: FileOpCode, action: suspend () -> Unit) {
        try { action(); fail("应拒绝压缩包") } catch (error: FileOpException) { assertEquals(code, error.code) }
    }
}
