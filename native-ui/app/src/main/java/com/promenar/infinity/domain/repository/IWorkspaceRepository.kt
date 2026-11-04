package com.promenar.infinity.domain.repository

import com.promenar.infinity.data.local.db.entity.FileEntry
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

interface IWorkspaceRepository {
    suspend fun ensureSessionRoot(sessionId: String): FileEntry
    suspend fun ensureSessionRoot(sessionId: String, physicalRootPath: String): FileEntry
    fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>>
    fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>>
    suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry?
    suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry?
    suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean = false,
    ): List<FileEntry>
    fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>>

    suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String
    ): FileEntry

    suspend fun createFileInWorkspaceStreaming(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        mimeType: String,
        parentUuid: String?,
        materializedPath: String,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): FileEntry

    suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String
    ): FileEntry

    suspend fun permanentDelete(workspaceRootUuid: String, uuid: String)
    /** 在同一工作区写锁中核验文件摘要并永久删除。 */
    suspend fun permanentDeleteIfHash(workspaceRootUuid: String, uuid: String, expectedSha256: String) {
        throw UnsupportedOperationException("仓库不支持带版本条件的永久删除")
    }
    /** 仅在目录仍为空时永久删除。 */
    suspend fun permanentDeleteEmptyDirectory(workspaceRootUuid: String, uuid: String) {
        throw UnsupportedOperationException("仓库不支持空目录条件删除")
    }
    suspend fun rollbackCreatedEntry(workspaceRootUuid: String, uuid: String)
    suspend fun confirmCreatedEntry(workspaceRootUuid: String, uuid: String)
    suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String)
    suspend fun rename(
        workspaceRootUuid: String,
        uuid: String,
        newName: String,
        expectedName: String? = null,
    ): RenameResult
    /** 在同一根锁与 journal 提交中移动并重命名，保留 UUID 与文件版本链。 */
    suspend fun relocate(
        workspaceRootUuid: String,
        uuid: String,
        newParentUuid: String,
        newName: String,
        expectedName: String? = null,
        expectedHash: String? = null,
    ): RenameResult = throw UnsupportedOperationException("仓库不支持原子移动并重命名")
    suspend fun getNextSeqForDate(dateKey: String): Int
    suspend fun resetAllRAGStatus(workspaceRootUuid: String)
}

sealed interface RenameResult {
    data class Success(
        val name: String,
        val targetHash: String,
        val targetEpoch: Long,
        val changed: Boolean,
        val affectedTargets: List<RenameIndexTarget> = emptyList(),
    ) : RenameResult

    data class Conflict(
        val expected: String,
        val current: String,
    ) : RenameResult

    data object NotFound : RenameResult
}

data class RenameIndexTarget(
    val fileUuid: String,
    val targetHash: String,
    val targetEpoch: Long,
)
