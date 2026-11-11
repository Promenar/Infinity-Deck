package com.promenar.infinity.data.production

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.namespaceBindings
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 复用现有工作区后端和带版本条件的文件写入设施。 */
class DefaultProductionWorkspaceFiles(
    private val workspace: IWorkspaceRepository,
    private val fileOperations: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val media: WorkspaceMediaResolver,
) : ProductionWorkspaceFiles {
    private data class Target(val backend: ExternalFolderBackend, val rootUuid: String?, val bindings: Map<String, String>)
    private suspend fun target(sessionId: String, path: String): Target {
        ProductionManifestCodec.validatePath(path)
        val virtualPath = "/$path"
        val bindings = mounts.namespaceBindings(sessionId)
        val mounted = ExternalMountPaths.aliasOf(virtualPath, ExternalMountPaths.WORKSPACE_ALIAS in bindings) != null
        if (mounted) return Target(mounts.backend(sessionId, bindings), null, bindings)
        val root = workspace.ensureSessionRoot(sessionId)
        return Target(PrivateWorkspaceTransferBackend(workspace, root.uuid), root.uuid, bindings)
    }
    override suspend fun read(sessionId: String, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val virtualPath = "/$path"
            if (target.backend.stat(virtualPath) == null) null else target.backend.read(virtualPath, 16L * 1024 * 1024)
        }
    }
    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?): Unit = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val virtualPath = "/$path"
            var parent = ""
            path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach { part ->
                parent += "/$part"
                val entry = target.backend.stat(parent)
                if (entry == null) target.backend.createDirectory(parent) else require(entry.isDirectory) { "$parent 不是目录。" }
            }
            if (target.rootUuid == null) {
                target.backend.write(virtualPath, bytes, expectedSha256, expectedSha256 == null)
            } else if (expectedSha256 == null) {
                target.backend.write(virtualPath, bytes, null, true)
                (target.backend as PrivateWorkspaceTransferBackend).confirmCreated()
            } else {
                val entry = workspace.getByMaterializedPath(target.rootUuid, virtualPath) ?: throw IllegalArgumentException("清单文件已不存在。")
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                when (val result = fileOperations.writeProductionManifestAtomic(target.rootUuid, entry.uuid, text, sessionId, expectedSha256)) {
                    is WriteResult.Success -> Unit
                    is WriteResult.Conflict -> throw IllegalArgumentException(result.message)
                    WriteResult.NotFound -> throw IllegalArgumentException("清单文件已不存在。")
                }
            }
            Unit
        }
    }
    override suspend fun isImageOrVideo(sessionId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        ProductionManifestCodec.validatePath(path)
        val result = media.resolve(sessionId, media.productionPath(sessionId, path))
        result is MediaResolution.Available && result.ref.kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO)
    }
}
