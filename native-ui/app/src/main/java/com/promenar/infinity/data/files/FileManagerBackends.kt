package com.promenar.infinity.data.files

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.LinuxHomeExternalFolderBackend
import com.promenar.infinity.data.workspace.LinuxHomeAvailability
import com.promenar.infinity.data.workspace.LinuxHomeWorkspaceSource
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import com.promenar.infinity.startup.StartupWriteBarrier

internal data class FileBackendHandle(
    val locationId: String,
    val backend: ExternalFolderBackend,
    val sessionIds: List<String>,
    val writable: Boolean,
    val roomRoot: String? = null,
    val roomSession: String? = null,
    val roomBackend: PrivateWorkspaceTransferBackend? = null,
    val mount: ExternalMount? = null,
) {
    suspend fun confirmCreated() { roomBackend?.confirmCreated() }
}

internal class FileManagerBackends(private val app: InfinityApplication) {
    private val home by lazy { LinuxHomeWorkspaceSource(app.linux.runtime) }

    suspend fun resolve(locationId: String): FileBackendHandle = when (val key = parseLocationId(locationId)) {
        LocationKey.Library -> room(locationId, "__infinity_rag_workspace__", app.globalKnowledgeWorkspaceProvisioner.ensureRoot().uuid)
        is LocationKey.Session -> {
            val session = app.database.sessionDao().getById(key.id)?.takeUnless(::isSystemSession)
                ?: throw FileOpException(FileOpCode.NOT_FOUND)
            room(locationId, session.id, app.workspaceRepository.ensureSessionRoot(session.id).uuid)
        }
        is LocationKey.Workspace -> {
            val sessions = app.database.sessionDao().getAll().filterNot(::isSystemSession)
            val branch = key.id.removePrefix(BranchWorktreeWorkspaceResolver.ID_PREFIX)
                .takeIf { key.id.startsWith(BranchWorktreeWorkspaceResolver.ID_PREFIX) }
            val resolved = if (branch != null) app.sessionWorkspaceResolver.resolve(branch)
                else app.sessionWorkspaceResolver.resolveWorkspace(key.id)
            val directory = resolved as? SessionWorkspace.Directory ?: throw FileOpException(FileOpCode.PERMISSION_LOST)
            if (directory.workspace.id != key.id) throw FileOpException(FileOpCode.NOT_FOUND)
            val owners = sessions.filter { session ->
                session.id == branch || (branch == null && session.workspaceId == key.id && session.worktreeDirectory == null)
            }.map { it.id }.sorted()
            val backend = (app.externalFolderManager as com.promenar.infinity.data.storage.ExternalFolderManager).withVersionCleanup(
                ExternalMountPaths.workspaceMountId(directory.workspace.id),
                StartupFileBackend(app.sessionWorkspaceResolver.backend(directory), owners) { directory.root },
            )
            FileBackendHandle(locationId, backend, owners, true)
        }
        is LocationKey.External -> {
            val record = app.database.externalMountDao().get(key.id) ?: throw FileOpException(FileOpCode.NOT_FOUND)
            val mount = app.externalFolderManager.visible(record.scopeKey).singleOrNull { it.id == key.id }
                ?: throw FileOpException(FileOpCode.NOT_FOUND)
            if (mount.unavailableReason != null) throw FileOpException(FileOpCode.PERMISSION_LOST)
            val backend = app.externalFolderManager.backend(mount.sessionId, mapOf(mount.alias to mount.id))
            FileBackendHandle(locationId, MountedBackend(backend, mount), if (ExternalMountPaths.isLibraryScope(mount.sessionId)) emptyList() else listOf(mount.sessionId),
                !mount.readOnly && mount.sourceType != "knowledge_base", mount = mount)
        }
        LocationKey.LinuxHome -> {
            if (home.availability() != LinuxHomeAvailability.READY) throw FileOpException(FileOpCode.NOT_FOUND)
            val backend = LinuxHomeExternalFolderBackend(home.files) { home.availability() == LinuxHomeAvailability.READY }
            val owners = app.database.sessionDao().getAll().filter { session ->
                session.workspaceId?.let { app.database.workspaceDao().getById(it)?.kind == "linux_home" } == true
            }.map { it.id }.sorted()
            val guarded = StartupFileBackend(backend, owners) { home.root() }
            val cacheAware = LinuxHomeVersionCleanupBackend(guarded, app::forgetLinuxRootfsVersions)
            FileBackendHandle(locationId, cacheAware, owners, true)
        }
        LocationKey.Sessions -> throw FileOpException(FileOpCode.READ_ONLY)
        null -> throw FileOpException(FileOpCode.NOT_FOUND)
    }

    private fun room(locationId: String, sessionId: String, root: String): FileBackendHandle {
        val backend = PrivateWorkspaceTransferBackend(app.workspaceRepository, root, app.pendingDocumentIndexCoordinator)
        return FileBackendHandle(locationId, backend, listOf(sessionId), true, root, sessionId, backend)
    }
}

/** Linux 主目录与文件工具共享 rootfs；删除尝试后使相关进程内基准失效。 */
internal class LinuxHomeVersionCleanupBackend(
    private val delegate: ExternalFolderBackend,
    private val forget: (String) -> Unit,
) : ExternalFolderBackend by delegate {
    override suspend fun delete(path: String, expectedSha256: String?) {
        val guestPath = "/root" + normalizeFilePath(path)
        try { delegate.delete(path, expectedSha256) } finally { forget(guestPath) }
    }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        val guestPath = "/root" + normalizeFilePath(path)
        try { delegate.deleteForTransfer(path, expected, expectedSha256) } finally { forget(guestPath) }
    }
}

/** 把会话挂载的虚拟前缀收敛为位置内路径，同时固定挂载身份。 */
private class MountedBackend(private val base: ExternalFolderBackend, private val mount: ExternalMount) : ExternalFolderBackend {
    private fun path(path: String) = ExternalMountPaths.virtualPath(mount, normalizeFilePath(path))
    private fun item(entry: ExternalFolderEntry, path: String) = entry.copy(path = normalizeFilePath(path))
    override suspend fun stat(path: String) = base.stat(path(path))?.let { item(it, path) }
    override suspend fun list(path: String) = base.list(path(path)).map { item(it, joinFilePath(path, it.name)) }
    override suspend fun read(path: String, maxBytes: Long) = base.read(path(path), maxBytes)
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T =
        base.readStream(path(path), maxBytes, consume)
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
        item(base.write(path(path), content, expectedSha256, createOnly), path)
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit) =
        item(base.writeStream(path(path), expectedSize, createOnly, produce), path)
    override suspend fun availableBytes(path: String) = base.availableBytes(path(path))
    override suspend fun createDirectory(path: String) = item(base.createDirectory(path(path)), path)
    override suspend fun delete(path: String, expectedSha256: String?) = base.delete(path(path), expectedSha256)
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) =
        base.deleteForTransfer(path(path), expected.copy(path = path(path)), expectedSha256)
    override suspend fun copy(sourcePath: String, targetPath: String) = base.copy(path(sourcePath), path(targetPath))
    override suspend fun move(sourcePath: String, targetPath: String) = base.move(path(sourcePath), path(targetPath))
    override suspend fun validateTree(path: String) = base.validateTree(path(path))
}

/** 未绑定会话的目录也按物理范围检查启动隔离；每个写原语再次核验。 */
private class StartupFileBackend(
    private val delegate: ExternalFolderBackend,
    private val sessions: List<String>,
    private val physicalRoot: suspend () -> File,
) : ExternalFolderBackend by delegate {
    private suspend fun <T> writing(paths: List<String>, action: suspend () -> T): T {
        val root = physicalRoot().canonicalFile
        val owners: List<String?> = if (sessions.isEmpty()) listOf(null) else sessions
        for (owner in owners) {
            StartupWriteBarrier.requireWritable(sessionId = owner, path = root.path)
            for (path in paths) {
                val relative = normalizeFilePath(path).removePrefix("/")
                StartupWriteBarrier.requireWritable(sessionId = owner, path = File(root, relative).canonicalPath)
            }
        }
        return action()
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
        writing(listOf(path)) { delegate.write(path, content, expectedSha256, createOnly) }
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit) =
        writing(listOf(path)) { delegate.writeStream(path, expectedSize, createOnly, produce) }
    override suspend fun createDirectory(path: String) = writing(listOf(path)) { delegate.createDirectory(path) }
    override suspend fun delete(path: String, expectedSha256: String?) = writing(listOf(path)) { delegate.delete(path, expectedSha256) }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) =
        writing(listOf(path)) { delegate.deleteForTransfer(path, expected, expectedSha256) }
    override suspend fun move(sourcePath: String, targetPath: String) =
        writing(listOf(sourcePath, targetPath)) { delegate.move(sourcePath, targetPath) }
    override suspend fun copy(sourcePath: String, targetPath: String) =
        writing(listOf(targetPath)) { delegate.copy(sourcePath, targetPath) }
}
