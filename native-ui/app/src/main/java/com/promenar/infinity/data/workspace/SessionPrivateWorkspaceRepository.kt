package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map

/** 私有树继续由原仓库管理，虚拟路径和 UUID 只在命名空间边界转换。 */
internal class SessionPrivateWorkspaceRepository(
    private val base: IWorkspaceRepository,
    private val sessionId: String,
) : IWorkspaceRepository by base {
    private suspend fun root() = base.ensureSessionRoot(sessionId)
    private fun id(uuid: String) = SessionPrivateWorkspacePaths.privateUuid(uuid) ?: uuid
    private fun path(path: String) = requireNotNull(SessionPrivateWorkspacePaths.relative(path))
    private fun virtual(entry: FileEntry) = entry.copy(
        uuid = SessionPrivateWorkspacePaths.uuid(entry.uuid),
        parentUuid = entry.parentUuid?.let(SessionPrivateWorkspacePaths::uuid),
        materializedPath = SessionPrivateWorkspacePaths.virtual(entry.materializedPath),
    )
    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String) = base.getByUuid(root().uuid, id(uuid))?.let(::virtual)
    override suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String) =
        base.getByMaterializedPath(root().uuid, path(materializedPath))?.let(::virtual)
    override fun observeChildren(workspaceRootUuid: String, parentUuid: String) = flow {
        emitAll(base.observeChildren(root().uuid, id(parentUuid)).map { entries -> entries.map(::virtual) })
    }
    override suspend fun createFileInWorkspace(workspaceRootUuid: String, uuid: String, name: String, content: String, parentUuid: String?, materializedPath: String) =
        virtual(base.createFileInWorkspace(root().uuid, id(uuid), name, content, parentUuid?.let(::id), path(materializedPath)))
    override suspend fun createDirectoryInWorkspace(workspaceRootUuid: String, uuid: String, name: String, parentUuid: String?, materializedPath: String) =
        virtual(base.createDirectoryInWorkspace(root().uuid, id(uuid), name, parentUuid?.let(::id), path(materializedPath)))
    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) = base.permanentDelete(root().uuid, id(uuid))
    override suspend fun permanentDeleteIfHash(workspaceRootUuid: String, uuid: String, expectedSha256: String) =
        base.permanentDeleteIfHash(root().uuid, id(uuid), expectedSha256)
    override suspend fun permanentDeleteEmptyDirectory(workspaceRootUuid: String, uuid: String) =
        base.permanentDeleteEmptyDirectory(root().uuid, id(uuid))
    override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?) =
        base.rename(root().uuid, id(uuid), newName, expectedName)
    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        base.updateParent(root().uuid, id(uuid), id(newParentUuid))
}
