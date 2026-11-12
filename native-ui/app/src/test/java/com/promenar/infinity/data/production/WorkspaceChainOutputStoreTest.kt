package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WorkspaceChainOutputStoreTest {
    private class Backend : ExternalFolderBackend {
        val contents = mutableMapOf<String, ByteArray>()
        private val ids = mutableMapOf<String, String>()
        private var serial = 0
        var failNextWrite = false
        override suspend fun stat(path: String) = contents[path]?.let { ExternalFolderEntry(path, path.substringAfterLast('/'), false, it.size.toLong(), 0, identity = ids[path]) }
        override suspend fun list(path: String) = emptyList<ExternalFolderEntry>()
        override suspend fun read(path: String, maxBytes: Long) = requireNotNull(contents[path])
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            if (failNextWrite) { failNextWrite = false; throw IllegalArgumentException("模拟写入失败") }
            require(!createOnly || path !in contents)
            contents[path] = content; ids[path] = "id${serial++}"
            return requireNotNull(stat(path))
        }
        override suspend fun createDirectory(path: String) = ExternalFolderEntry(path, path.substringAfterLast('/'), true, 0, 0)
        override suspend fun delete(path: String, expectedSha256: String?) { contents.remove(path); ids.remove(path) }
        override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
            require(stat(path)?.identity == expected.identity)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(requireNotNull(contents[path])).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedSha256 == hash)
            delete(path)
        }
    }
    @Test fun `固定链段可以安全替换并且写入失败恢复原文件`() = runTest {
        val dir = Files.createTempDirectory("chain-output-test-").toFile()
        try {
            val backend = Backend(); val publisher = WorkspaceChainOutputStore(dir)
            val path = "p/S1E01/takes/chain-test/seg-0.mp4"
            val source = java.io.File(dir, "source.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            publisher.publish(backend, path, source) {}
            assertThat(backend.contents["/$path"]).isEqualTo(byteArrayOf(1, 2, 3))
            source.writeBytes(byteArrayOf(4, 5, 6)); publisher.publish(backend, path, source) {}
            assertThat(backend.contents["/$path"]).isEqualTo(byteArrayOf(4, 5, 6))
            source.writeBytes(byteArrayOf(7, 8)); backend.failNextWrite = true
            val error = try { publisher.publish(backend, path, source) {}; null } catch (e: IllegalArgumentException) { e }
            assertThat(error?.message).isEqualTo("模拟写入失败")
            assertThat(backend.contents["/$path"]).isEqualTo(byteArrayOf(4, 5, 6))
            assertThat(dir.listFiles()?.filter { it.isDirectory }).isEmpty()
        } finally { dir.deleteRecursively() }
    }
}
