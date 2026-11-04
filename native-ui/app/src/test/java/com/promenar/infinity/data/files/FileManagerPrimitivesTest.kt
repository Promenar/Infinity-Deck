package com.promenar.infinity.data.files

import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.files.FileTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileManagerPrimitivesTest {
    @Test fun `按扩展名和 MIME 分类`() {
        assertEquals(FileTypeFilter.CODE, classifyFile("Main.KT", null))
        assertEquals(FileTypeFilter.DOCUMENT, classifyFile("report.pdf", null))
        assertEquals(FileTypeFilter.IMAGE, classifyFile("unknown", "image/png"))
        assertEquals(FileTypeFilter.AUDIO, classifyFile("voice", "audio/ogg"))
        assertEquals(FileTypeFilter.VIDEO, classifyFile("clip.mp4", null))
        assertEquals(FileTypeFilter.ARCHIVE, classifyFile("archive.zip", null))
        assertEquals(FileTypeFilter.OTHER, classifyFile("blob", null))
    }

    @Test fun `位置标识和路径解析`() {
        assertEquals(LocationKey.Library, parseLocationId("kb"))
        assertEquals(LocationKey.Sessions, parseLocationId("sessions"))
        assertEquals(LocationKey.Session("a:b"), parseLocationId("session:a:b"))
        assertEquals(LocationKey.Workspace("w"), parseLocationId("workspace:w"))
        assertEquals(LocationKey.LinuxHome, parseLocationId("linux:home"))
        assertEquals(LocationKey.External("m"), parseLocationId("external:m"))
        assertNull(parseLocationId("session:"))
        assertNull(parseLocationId("unknown:x"))
        assertEquals("/one/two", normalizeFilePath("//one/two/"))
        assertCode(FileOpCode.INVALID_NAME) { normalizeFilePath("/one/../two") }
    }

    @Test fun `名称校验与扩展名避让`() {
        assertEquals("report (2).txt", collisionName("report.txt", false, 2))
        assertEquals("report (3).txt", collisionName("report.txt", false, 3))
        assertEquals(".hidden (2)", collisionName(".hidden", false, 2))
        assertEquals("folder.zip (2)", collisionName("folder.zip", true, 2))
        listOf("", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { name ->
            assertCode(FileOpCode.INVALID_NAME) { validateFileName(name) }
        }
    }

    @Test fun `压缩包条目拒绝越界路径`() {
        assertEquals(listOf("dir", "file.txt"), safeZipPath("dir/file.txt"))
        assertEquals(listOf("dir"), safeZipPath("dir/"))
        listOf("/etc/passwd", "../file", "a/../file", "a\\b", "C:/file", "./file", "").forEach { path ->
            assertCode(FileOpCode.INVALID_NAME) { safeZipPath(path) }
        }
    }

    @Test fun `回收站标识可反查并拒绝损坏标识`() {
        val original = RecycleKey("session:a:b", "u:123")
        assertEquals(original, decodeRecycleId(encodeRecycleId(original)))
        assertNull(decodeRecycleId("bad"))
        assertFalse(encodeRecycleId(original).contains('/'))
        assertTrue(encodeRecycleId(original).isNotEmpty())
    }

    private fun assertCode(code: FileOpCode, block: () -> Unit) {
        try { block(); fail("应拒绝无效输入") } catch (error: FileOpException) { assertEquals(code, error.code) }
    }
}
