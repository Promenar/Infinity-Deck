package com.promenar.infinity.share.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.promenar.infinity.ShareRequest
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Android SAF 的单一内容源，供分享入口、资源管理器和知识库导入共同复用。 */
class AndroidSafContentSource(
    private val resolver: ContentResolver,
) : SharedContentSource {
    override fun metadata(uri: Uri): SharedContentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { displayName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it) }
            }
        }
        return SharedContentMetadata(
            displayName = displayName,
            mimeType = AndroidSafImportRequestFactory.resolveMime(
                resolver.getType(uri),
                displayName ?: uri.lastPathSegment,
            ),
            sizeBytes = sizeBytes,
        )
    }

    override fun open(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: throw IOException("无法打开所选文件")
}

/** 为单个 SAF URI 创建共享导入请求；未知二进制保持不支持 MIME，交由 importer 明确拒绝。 */
class AndroidSafImportRequestFactory(
    private val resolver: ContentResolver,
) {
    fun create(uri: Uri, targetWorkspaceRootUuid: String): ShareRequest = ShareRequest(
        uris = listOf(uri),
        mimeType = resolveMime(resolver.getType(uri), uri.lastPathSegment),
        fingerprint = uri.toString(),
        canonicalSizeBytes = uri.toString().toByteArray(Charsets.UTF_8).size,
        requestId = UUID.randomUUID().toString(),
        targetWorkspaceRootUuid = targetWorkspaceRootUuid,
    )

    companion object {
        private const val UNKNOWN_MIME = "application/octet-stream"

        internal fun resolveMime(declaredMime: String?, name: String?): String {
            val normalized = declaredMime
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() && it != "*/*" && it != UNKNOWN_MIME }
            if (normalized in SharedFileImporter.SUPPORTED_MIME_TYPES) return requireNotNull(normalized)
            return when {
                name?.endsWith(".txt", ignoreCase = true) == true -> "text/plain"
                name?.endsWith(".md", ignoreCase = true) == true -> "text/markdown"
                name?.endsWith(".csv", ignoreCase = true) == true -> "text/csv"
                name?.endsWith(".html", ignoreCase = true) == true ||
                    name?.endsWith(".htm", ignoreCase = true) == true -> "text/html"
                name?.endsWith(".json", ignoreCase = true) == true -> "application/json"
                name?.endsWith(".pdf", ignoreCase = true) == true -> "application/pdf"
                name?.endsWith(".doc", ignoreCase = true) == true -> "application/msword"
                name?.endsWith(".docx", ignoreCase = true) == true ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                else -> normalized ?: UNKNOWN_MIME
            }
        }
    }
}
