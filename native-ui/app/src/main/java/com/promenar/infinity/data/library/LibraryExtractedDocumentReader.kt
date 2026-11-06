package com.promenar.infinity.data.library

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceMutationCoordinator
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import com.promenar.infinity.utils.InfinityLogger
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 将受控句柄中的原文复制到应用私有缓存后，复用现有 PDF/DOCX 提取器。 */
internal class LibraryExtractedDocumentReader(
    private val fileDao: FileEntryDao,
    private val ensureRoot: suspend () -> FileEntry,
    private val cacheDirectory: File,
    private val fileOps: SecureWorkspaceFileOps = SecureWorkspaceFileOps(),
    private val extract: suspend (FileEntry, Int) -> LibraryExtractedText = { entry, maxChars ->
        val budget = ((maxChars.coerceAtLeast(0).toLong() + 1L) * 4L)
            .coerceAtMost(DocumentReferenceExtractor.MAX_EXTRACTED_UTF8_BYTES)
        val result = DocumentReferenceExtractor(1024, 0, budget).extract(entry)
        LibraryExtractedText(result.graphText, result.truncated)
    },
) {
    suspend fun read(entry: FileEntry, maxChars: Int): LibraryExtractedText = withContext(Dispatchers.IO) {
        val root = ensureRoot()
        require(root.uuid == entry.workspaceRootUuid) { "存储空间根已变化" }
        val snapshot = File.createTempFile("library-preview-", ".tmp", cacheDirectory)
        try {
            val current = WorkspaceMutationCoordinator.withBoundRoot(Paths.get(root.physicalRootPath), root.hash) {
                val current = fileDao.getActiveByUuid(root.uuid, entry.uuid)?.takeUnless { it.isDirectory }
                    ?: throw NoSuchElementException("资料文件不存在")
                check(current.hash == entry.hash && current.updatedAt == entry.updatedAt) { "资料文件版本已变化" }
                val path = normalizedLibraryPath(current.materializedPath) ?: throw SecurityException("资料路径无效")
                require(path != "/" && !WorkspaceInternalPaths.containsReservedPath(path)) { "资料路径不可读取" }
                val mime = when (current.mimeType) {
                    "application/pdf", DocumentReferenceExtractor.DOCX_MIME, DocumentReferenceExtractor.DOC_MIME -> current.mimeType
                    else -> when (current.name.substringAfterLast('.', "").lowercase()) {
                        "pdf" -> "application/pdf"
                        "docx" -> DocumentReferenceExtractor.DOCX_MIME
                        "doc" -> DocumentReferenceExtractor.DOC_MIME
                        else -> throw IllegalArgumentException("不支持提取该文件类型")
                    }
                }
                val sourceLimit = if (mime in setOf(DocumentReferenceExtractor.DOCX_MIME, DocumentReferenceExtractor.DOC_MIME)) 50L * 1024 * 1024 else 100L * 1024 * 1024
                val digest = MessageDigest.getInstance("SHA-256")
                fileOps.readStream(Paths.get(root.physicalRootPath), path.trimStart('/').split('/'), sourceLimit) { input ->
                    snapshot.outputStream().buffered().use { output ->
                        copyLibrarySnapshot(input, output, sourceLimit, digest)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(actual == current.hash) { "资料文件内容与登记版本不一致" }
                current.copy(physicalRootPath = cacheDirectory.canonicalPath, materializedPath = "/${snapshot.name}",
                    mimeType = mime, sizeBytes = snapshot.length())
            }
            val result = extract(current, maxChars)
            WorkspaceMutationCoordinator.withBoundRoot(Paths.get(root.physicalRootPath), root.hash) {
                val latest = fileDao.getActiveByUuid(root.uuid, entry.uuid)
                check(latest != null && !latest.isDirectory && latest.hash == entry.hash &&
                    latest.updatedAt == entry.updatedAt && latest.materializedPath == entry.materializedPath) {
                    "资料文件在解析期间发生变化"
                }
                val sourceLimit = if (current.mimeType in setOf(DocumentReferenceExtractor.DOCX_MIME, DocumentReferenceExtractor.DOC_MIME)) 50L * 1024 * 1024 else 100L * 1024 * 1024
                val digest = MessageDigest.getInstance("SHA-256")
                fileOps.readStream(Paths.get(root.physicalRootPath), latest.materializedPath.trimStart('/').split('/'), sourceLimit) { input ->
                    copyLibrarySnapshot(input, DiscardOutput, sourceLimit, digest)
                }
                val currentHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(currentHash == entry.hash) { "资料文件在解析期间发生变化" }
            }
            result
        } finally {
            if (snapshot.exists() && !snapshot.delete()) InfinityLogger.log("[Library] 预览临时文件清理失败")
        }
    }
}

internal fun copyLibrarySnapshot(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, digest: MessageDigest) {
    val buffer = ByteArray(32 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(count.toLong() <= limit - copied) { "资料文件超过预览大小上限" }
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
        copied += count
    }
}

private object DiscardOutput : java.io.OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}
