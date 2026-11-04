package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.session.SessionDeletingException
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.domain.workspace.DirectoryWorkspaceFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultDirectoryWorkspaceFiles(
    private val mounts: ExternalWorkspaceAccess,
    private val gate: SessionExecutionGate,
    private val isBusy: suspend (String) -> Boolean,
) : DirectoryWorkspaceFiles {
    private val mutex = Mutex()

    override suspend fun createDirectory(sessionId: String, parent: String, name: String): ExternalFolderEntry =
        operation(sessionId) { backend ->
            val target = join(path(parent), ExternalFolderPaths.validateName(name))
            requireAbsent(backend, target)
            backend.createDirectory(target)
        }

    override suspend fun rename(sessionId: String, path: String, newName: String): ExternalFolderEntry =
        operation(sessionId) { backend ->
            val source = mutationPath(path)
            val target = join(source.substringBeforeLast('/').ifEmpty { "/" }, ExternalFolderPaths.validateName(newName))
            requireEntry(backend, source)
            if (source == target) throw DirectoryFileOpException(DirectoryFileOpCode.NAME_EXISTS)
            backend.move(source, target)
            requireEntry(backend, target)
        }

    override suspend fun move(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult =
        batch(sessionId, paths) { backend, raw ->
            val source = mutationPath(raw)
            val target = target(backend, source, targetDirectory)
            backend.move(source, target)
        }

    override suspend fun copy(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult =
        batch(sessionId, paths) { backend, raw ->
            val source = mutationPath(raw)
            var destination = target(backend, source, targetDirectory)
            val original = destination
            val name = source.substringAfterLast('/')
            val entry = requireEntry(backend, source)
            val extension = if (!entry.isDirectory && name.lastIndexOf('.') > 0) ".${name.substringAfterLast('.')}" else ""
            val stem = name.removeSuffix(extension)
            var suffix = 2
            while (backend.stat(destination) != null) {
                destination = join(original.substringBeforeLast('/').ifEmpty { "/" }, "$stem (${suffix++})$extension")
            }
            backend.copy(source, destination)
        }

    override suspend fun delete(sessionId: String, paths: List<String>): DirectoryBatchResult =
        batch(sessionId, paths) { backend, raw ->
            val source = mutationPath(raw)
            val entry = requireEntry(backend, source)
            backend.delete(source, entry.sha256)
        }

    private suspend fun target(backend: ExternalFolderBackend, source: String, rawTarget: String): String {
        val directory = path(rawTarget)
        val entry = requireEntry(backend, source)
        if (entry.isDirectory && (directory == source || directory.startsWith("$source/"))) {
            throw DirectoryFileOpException(DirectoryFileOpCode.TARGET_INSIDE_SOURCE)
        }
        if (!requireEntry(backend, directory).isDirectory) throw DirectoryFileOpException(DirectoryFileOpCode.IO)
        return join(directory, entry.name)
    }

    private suspend fun batch(
        sessionId: String,
        paths: List<String>,
        action: suspend (ExternalFolderBackend, String) -> Unit,
    ): DirectoryBatchResult = operation(sessionId) { backend ->
        val succeeded = mutableListOf<String>()
        val failed = linkedMapOf<String, DirectoryFileOpCode>()
        for (path in paths) {
            try {
                action(backend, path)
                succeeded += path
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed[path] = mapped(failure).code
            }
        }
        DirectoryBatchResult(succeeded, failed)
    }

    private suspend fun <T> operation(sessionId: String, block: suspend (ExternalFolderBackend) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                gate.withSessionMutation(sessionId) {
                    mutex.withLock {
                        val mount = mounts.workspaceMount(sessionId)
                            ?: throw DirectoryFileOpException(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
                        if (mount.unavailableReason != null) throw DirectoryFileOpException(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
                        if (isBusy(sessionId)) throw DirectoryFileOpException(DirectoryFileOpCode.SESSION_BUSY)
                        val bindings = mapOf(mount.alias to mount.id)
                        withContext(ExternalMountOperationBindings(bindings)) {
                            block(mounts.backend(sessionId, bindings))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw mapped(failure)
            }
        }

    private fun path(raw: String): String = ExternalFolderPaths.normalize(raw).also {
        // 独立挂载与技能根不是会话当前目录工作区的一部分。
        if (ExternalMountPaths.aliasOf(it, rooted = true) != ExternalMountPaths.WORKSPACE_ALIAS) {
            throw DirectoryFileOpException(DirectoryFileOpCode.NAME_INVALID)
        }
    }

    private fun mutationPath(raw: String): String = path(raw).also {
        if (it == "/") throw DirectoryFileOpException(DirectoryFileOpCode.NAME_INVALID)
    }

    private fun join(parent: String, name: String) = path(parent.trimEnd('/') + "/" + name)

    private suspend fun requireEntry(backend: ExternalFolderBackend, path: String) = backend.stat(path)
        ?: throw DirectoryFileOpException(DirectoryFileOpCode.NOT_FOUND)

    private suspend fun requireAbsent(backend: ExternalFolderBackend, path: String) {
        if (backend.stat(path) != null) throw DirectoryFileOpException(DirectoryFileOpCode.NAME_EXISTS)
    }

    private fun mapped(failure: Exception): DirectoryFileOpException {
        if (failure is DirectoryFileOpException) return failure
        val code = when (failure) {
            is SessionDeletingException -> DirectoryFileOpCode.SESSION_BUSY
            is ExternalFolderException -> when (failure.code) {
                "INVALID_PATH" -> DirectoryFileOpCode.NAME_INVALID
                "NOT_FOUND" -> DirectoryFileOpCode.NOT_FOUND
                "NAME_EXISTS" -> DirectoryFileOpCode.NAME_EXISTS
                "TARGET_INSIDE_SOURCE" -> DirectoryFileOpCode.TARGET_INSIDE_SOURCE
                "TOO_LARGE" -> DirectoryFileOpCode.TOO_LARGE
                "READ_ONLY" -> DirectoryFileOpCode.READ_ONLY
                "UNAVAILABLE", "MOUNT_CHANGED" -> DirectoryFileOpCode.WORKSPACE_UNAVAILABLE
                else -> DirectoryFileOpCode.IO
            }
            else -> DirectoryFileOpCode.IO
        }
        return DirectoryFileOpException(code, failure)
    }
}
