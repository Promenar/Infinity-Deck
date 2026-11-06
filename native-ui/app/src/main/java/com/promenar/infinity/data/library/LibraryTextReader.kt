package com.promenar.infinity.data.library

import com.promenar.infinity.data.rag.DocumentTextEncoding
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceMutationCoordinator
import com.promenar.infinity.data.repository.WorkspaceTextContentPolicy
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.io.Reader
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 在受控根锁与安全文件句柄中读取有界文本预览。 */
internal class LibraryTextReader(
    private val fileEntryDao: FileEntryDao,
    private val ensureRoot: suspend () -> FileEntry,
    private val fileOps: SecureWorkspaceFileOps = SecureWorkspaceFileOps(),
) {
    suspend fun read(rootId: String, documentId: String, maxChars: Int): LibraryExtractedText = withContext(Dispatchers.IO) {
        val root = ensureRoot()
        require(root.uuid == rootId) { "资料库根已变化" }
        val rootPath = Paths.get(root.physicalRootPath)
        WorkspaceMutationCoordinator.withBoundRoot(rootPath, root.hash) {
            val entry = fileEntryDao.getActiveByUuid(rootId, documentId)?.takeUnless { it.isDirectory }
                ?: throw NoSuchElementException("资料文件不存在")
            val path = normalizedLibraryPath(entry.materializedPath)
                ?: throw SecurityException("资料路径无效")
            require(path != "/" && !WorkspaceInternalPaths.containsReservedPath(path)) { "资料路径不可读取" }
            WorkspaceTextContentPolicy().requireSupportedType(entry.name, entry.mimeType)
            val parts = path.trimStart('/').split('/')
            val encoding = DocumentTextEncoding.detect { check -> fileOps.readStream(rootPath, parts, Long.MAX_VALUE, check) }
            fileOps.readStream(rootPath, parts, Long.MAX_VALUE) { input ->
                readLibraryTextPrefix(DocumentTextEncoding.reader(input, encoding), maxChars)
            }
        }
    }
}

internal fun readLibraryTextPrefix(reader: Reader, maxChars: Int): LibraryExtractedText {
    val limit = maxChars.coerceIn(0, MAX_PREVIEW_CHARS)
    val text = StringBuilder()
    val buffer = CharArray(8_192)
    val target = limit + 2 // 额外容纳 BOM 与截断判定字符。
    while (text.length < target) {
        val count = reader.read(buffer, 0, minOf(buffer.size, target - text.length))
        if (count < 0) break
        if (count == 0) continue
        text.append(buffer, 0, count)
    }
    val content = text.toString().removePrefix("\uFEFF")
    require('\u0000' !in content) { "资料文件不是文本" }
    val body = toDocumentBody(content, limit, markdown = false, extracted = false)
    return LibraryExtractedText(body.text, body.truncated)
}

private const val MAX_PREVIEW_CHARS = 16 * 1024 * 1024
