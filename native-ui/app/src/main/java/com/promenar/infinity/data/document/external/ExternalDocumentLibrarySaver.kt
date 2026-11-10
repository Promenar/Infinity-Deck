package com.promenar.infinity.data.document.external

import android.net.Uri
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.share.core.ShareIndexReceipt
import com.promenar.infinity.share.core.ShareIndexScheduler
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedContentMetadata
import com.promenar.infinity.share.core.SharedContentSource
import com.promenar.infinity.share.core.SharedFileImporter
import java.io.InputStream

sealed interface ExternalLibrarySaveResult {
    /** [path] 为存储空间内路径，例如 /工作/周报.md。 */
    data class Saved(val documentId: String, val name: String, val path: String) : ExternalLibrarySaveResult
    data class Failed(val reason: ShareRejectReason?) : ExternalLibrarySaveResult
}

/**
 * 把暂存的外部文档存入存储空间的某个文件夹：沿用分享导入的写入路径，
 * 包括同名编号、同名同内容复用已有文件、可解析格式排入索引。
 */
class ExternalDocumentLibrarySaver(
    private val workspace: IWorkspaceRepository,
    private val indexScheduler: ShareIndexScheduler = ShareIndexScheduler { _, entry ->
        ShareIndexReceipt(taskId = "no-op-${entry.uuid}", fileUuid = entry.uuid)
    },
) {
    suspend fun save(document: ExternalDocument, workspaceRootUuid: String, folderUuid: String): ExternalLibrarySaveResult {
        val uri = Uri.fromFile(document.file)
        val source = object : SharedContentSource {
            override fun metadata(uri: Uri) = SharedContentMetadata(document.displayName, ExternalMarkdown.MARKDOWN_MIME, document.sizeBytes)
            override fun open(uri: Uri): InputStream = document.file.inputStream()
            override fun contentSha256(uri: Uri): String = document.sha256
        }
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = ExternalMarkdown.MARKDOWN_MIME,
            fingerprint = document.sha256,
            canonicalSizeBytes = 0,
            requestId = document.id,
        )
        val result = SharedFileImporter(source, workspace, indexScheduler).import(request, workspaceRootUuid, parentUuid = folderUuid)
        val created = result.created.singleOrNull()?.created
            ?: return ExternalLibrarySaveResult.Failed(result.rejected.firstOrNull()?.reason)
        return ExternalLibrarySaveResult.Saved(created.uuid, created.name, created.materializedPath)
    }
}
