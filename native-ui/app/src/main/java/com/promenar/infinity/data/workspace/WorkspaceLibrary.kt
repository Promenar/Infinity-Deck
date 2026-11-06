package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.storage.externalSha256
import com.promenar.infinity.data.storage.externalSnapshotMime
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.share.core.SharedFileImporter
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface WorkspaceDeleteResult {
    data object Deleted : WorkspaceDeleteResult
    /** 仍被会话或 Agent 引用，登记保留。 */
    data class Referenced(val sessions: Int, val agents: Int) : WorkspaceDeleteResult
    data object Missing : WorkspaceDeleteResult
}

/**
 * 知识库中的“工作区”分组：只读浏览已登记的自选目录，可把单个文件复制进知识库。
 * 重命名与删除只改登记记录，不触碰目录内容；不会自动索引整个目录。
 */
class WorkspaceLibrary(
    private val workspaceDao: WorkspaceDao,
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
    private val backendFor: (SessionWorkspace.Directory) -> ExternalFolderBackend,
    private val knowledge: IWorkspaceRepository,
    private val indexEvents: FileIndexEventSink,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observe(): Flow<List<WorkspaceEntity>> = workspaceDao.observeAll()

    suspend fun rename(id: String, rawName: String): Boolean = withContext(io) {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= MAX_DISPLAY_NAME) { "显示名不能为空且不超过 $MAX_DISPLAY_NAME 个字符" }
        workspaceDao.rename(id, name) == 1
    }

    suspend fun delete(id: String): WorkspaceDeleteResult = withContext(io) {
        if (workspaceDao.getById(id) == null) return@withContext WorkspaceDeleteResult.Missing
        if (workspaceDao.deleteIfUnreferenced(id)) return@withContext WorkspaceDeleteResult.Deleted
        if (workspaceDao.getById(id) == null) return@withContext WorkspaceDeleteResult.Missing
        WorkspaceDeleteResult.Referenced(workspaceDao.countSessionReferences(id), workspaceDao.countAgentReferences(id))
    }

    suspend fun list(workspaceId: String, path: String): List<ExternalFolderEntry> = withContext(io) {
        backend(workspaceId).list(path)
    }

    suspend fun read(workspaceId: String, path: String, maxBytes: Long): ByteArray = withContext(io) {
        backend(workspaceId).read(path, maxBytes)
    }

    /**
     * 把单个文件复制进知识库根目录并发布索引事件，返回新文件 uuid。
     * 与外部文件夹“加入知识库快照”同一规则：受支持的类型、8 MiB 上限、读取前后核对来源未变化。
     */
    suspend fun importToKnowledge(workspaceId: String, path: String, knowledgeRootUuid: String): String = withContext(io) {
        val backend = backend(workspaceId)
        val original = backend.stat(path) ?: throw ExternalFolderException("NOT_FOUND", "源文件不可用")
        if (original.isDirectory) throw ExternalFolderException("IS_DIRECTORY", "请选择单个文件加入资料")
        val mime = externalSnapshotMime(original.name)
        if (mime !in SharedFileImporter.SUPPORTED_MIME_TYPES) {
            throw ExternalFolderException("UNSUPPORTED", "资料支持 TXT、Markdown、CSV、JSON、HTML、PDF、DOC 和 DOCX")
        }
        val bytes = backend.read(path, MAX_IMPORT_BYTES)
        val after = backend.stat(path) ?: throw ExternalFolderException("UNAVAILABLE", "读取后来源不可用")
        val hash = externalSha256(bytes)
        if (after.lastModified != original.lastModified || after.size != original.size ||
            (after.identity != null && after.identity != original.identity) ||
            (!after.sha256.isNullOrEmpty() && after.sha256 != hash)
        ) throw ExternalFolderException("CONFLICT", "读取期间源文件发生变化，请重试")
        val uuid = UUID.randomUUID().toString()
        val safeName = "workspace-${uuid.take(8)}-${original.name}"
        val created = knowledge.createFileInWorkspaceStreaming(
            knowledgeRootUuid, uuid, safeName, mime,
            knowledgeRootUuid, "/$safeName", MAX_IMPORT_BYTES,
        ) { it.write(bytes) }
        try {
            indexEvents.publish(FileIndexEvent.Changed(knowledgeRootUuid, uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
            knowledge.confirmCreatedEntry(knowledgeRootUuid, uuid)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try { knowledge.rollbackCreatedEntry(knowledgeRootUuid, uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        uuid
    }

    private suspend fun backend(workspaceId: String): ExternalFolderBackend =
        when (val resolved = resolveWorkspace(workspaceId)) {
            is SessionWorkspace.Directory -> backendFor(resolved)
            is SessionWorkspace.Unavailable -> throw ExternalFolderException("UNAVAILABLE", resolved.reason)
            SessionWorkspace.Private -> throw ExternalFolderException("UNAVAILABLE", "工作区不可用")
        }

    companion object {
        const val MAX_DISPLAY_NAME = 80
        const val MAX_IMPORT_BYTES = 8L * 1024 * 1024
    }
}
