package com.promenar.infinity.data.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.repository.IdentityTestDirectory
import com.promenar.infinity.data.repository.PersistentFileIdentity
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

class LibraryExtractedDocumentReaderTest {
    @Test
    fun `源流增长超过预算时立即终止快照写入`() {
        val input = java.io.ByteArrayInputStream(ByteArray(20) { 65 })
        val output = ByteArrayOutputStream()
        var rejected = false
        try { copyLibrarySnapshot(input, output, 10, MessageDigest.getInstance("SHA-256")) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(output.size()).isAtMost(10)
    }

    @Test
    fun `解析期间文档被更新时拒绝旧正文`() = runBlocking {
        val rootPath = Files.createTempDirectory("library-version-root").toFile().canonicalFile.toPath()
        val cachePath = Files.createTempDirectory("library-version-cache").toFile().canonicalFile.toPath()
        try {
            val nonce = UUID.randomUUID().toString()
            val key = Files.readAttributes(rootPath, BasicFileAttributes::class.java).fileKey().toString()
            Files.writeString(rootPath.resolve(".infinity_root_identity"), PersistentFileIdentity.marker(nonce, key))
            val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })
            val identity = ops.ensureRoot(rootPath, allowUnboundParent = true)
            val bytes = "fixture".toByteArray()
            Files.write(rootPath.resolve("doc.pdf"), bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val root = FileEntry("root", "root", null, "root", identity, isDirectory = true,
                physicalRootPath = rootPath.toString(), materializedPath = "/", createdAt = 1, updatedAt = 1)
            val entry = FileEntry("doc", "root", "root", "doc.pdf", hash, "application/pdf", bytes.size.toLong(),
                false, rootPath.toString(), "/doc.pdf", createdAt = 1, updatedAt = 1)
            var active = entry
            val dao = mockk<FileEntryDao>()
            coEvery { dao.getActiveByUuid("root", "doc") } coAnswers { active }
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val reader = LibraryExtractedDocumentReader(dao, { root }, cachePath.toFile(), ops, extract = { _, _ ->
                started.complete(Unit)
                finish.await()
                LibraryExtractedText("旧正文", false)
            })
            val result = async { runCatching { reader.read(entry, 100) } }
            started.await()
            active = entry.copy(hash = "changed", updatedAt = 2)
            finish.complete(Unit)
            assertThat(result.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(Files.list(cachePath).use { it.count() }).isEqualTo(0)
            active = entry
            val startedAgain = CompletableDeferred<Unit>()
            val finishAgain = CompletableDeferred<Unit>()
            val second = LibraryExtractedDocumentReader(dao, { root }, cachePath.toFile(), ops, extract = { _, _ ->
                startedAgain.complete(Unit)
                finishAgain.await()
                LibraryExtractedText("旧正文", false)
            })
            val replaced = async { runCatching { second.read(entry, 100) } }
            startedAgain.await()
            Files.writeString(rootPath.resolve("doc.pdf"), "changed")
            finishAgain.complete(Unit)
            assertThat(replaced.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(Files.list(cachePath).use { it.count() }).isEqualTo(0)
        } finally {
            rootPath.toFile().deleteRecursively()
            cachePath.toFile().deleteRecursively()
        }
    }

    @Test
    fun `从受控快照解析文档并清理临时文件，同时拒绝符号链接`() = runBlocking {
        val rootPath = Files.createTempDirectory("library-doc-root").toFile().canonicalFile.toPath()
        val cachePath = Files.createTempDirectory("library-doc-cache").toFile().canonicalFile.toPath()
        val outside = Files.createTempFile("library-doc-outside", ".docx")
        try {
            val nonce = UUID.randomUUID().toString()
            val key = Files.readAttributes(rootPath, BasicFileAttributes::class.java).fileKey().toString()
            Files.writeString(rootPath.resolve(".infinity_root_identity"), PersistentFileIdentity.marker(nonce, key))
            val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })
            val identity = ops.ensureRoot(rootPath, allowUnboundParent = true)
            val bytes = ByteArrayOutputStream().use { output ->
                XWPFDocument().use { document ->
                    document.createParagraph().createRun().setText("示例正文")
                    document.write(output)
                }
                output.toByteArray()
            }
            Files.write(rootPath.resolve("doc.docx"), bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val root = FileEntry("root", "root", null, "root", identity, isDirectory = true,
                physicalRootPath = rootPath.toString(), materializedPath = "/", createdAt = 1, updatedAt = 1)
            val entry = FileEntry("doc", "root", "root", "doc.docx", hash, DocumentReferenceExtractor.DOCX_MIME,
                bytes.size.toLong(), false, rootPath.toString(), "/doc.docx", createdAt = 1, updatedAt = 1)
            val dao = mockk<FileEntryDao>()
            coEvery { dao.getActiveByUuid("root", "doc") } returns entry
            val reader = LibraryExtractedDocumentReader(dao, { root }, cachePath.toFile(), ops)
            assertThat(reader.read(entry, 100).text).contains("示例正文")
            assertThat(Files.list(cachePath).use { it.count() }).isEqualTo(0)
            Files.createSymbolicLink(rootPath.resolve("link.docx"), outside)
            val link = entry.copy(materializedPath = "/link.docx")
            coEvery { dao.getActiveByUuid("root", "doc") } returns link
            var rejected = false
            try { reader.read(link, 100) } catch (_: SecurityException) { rejected = true }
            assertThat(rejected).isTrue()
            assertThat(Files.list(cachePath).use { it.count() }).isEqualTo(0)
        } finally {
            rootPath.toFile().deleteRecursively()
            cachePath.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }
}
