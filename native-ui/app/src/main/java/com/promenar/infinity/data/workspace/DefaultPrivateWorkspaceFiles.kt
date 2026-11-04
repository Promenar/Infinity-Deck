package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceLifecycleErrorCode
import com.promenar.infinity.data.repository.WorkspaceLifecycleException
import com.promenar.infinity.data.session.SessionDeletingException
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.domain.workspace.PrivateWorkspaceFiles
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import com.promenar.infinity.startup.StartupIsolationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultPrivateWorkspaceFiles(
    private val repository: IWorkspaceRepository,
    private val indexEvents: FileIndexEventSink,
    private val gate: SessionExecutionGate,
    private val isBusy: suspend (String) -> Boolean,
) : PrivateWorkspaceFiles {
    private data class RenameRequest(val sessionId: String, val uuid: String)
    private val renaming = ConcurrentHashMap.newKeySet<RenameRequest>()

    override suspend fun rename(sessionId: String, uuid: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val request = RenameRequest(sessionId, uuid)
        // 门锁内才读取名称会把重叠请求变成连续改名；只接纳同一条目的首个在途改名。
        val admitted = renaming.add(request)
        try {
            operation(sessionId) { root ->
                if (!admitted) throw DirectoryFileOpException(DirectoryFileOpCode.NOT_FOUND)
                validateName(newName)
                val entry = mutableEntry(root, uuid)
                if (entry.name == newName) return@operation
                when (val result = repository.rename(root, uuid, newName, expectedName = entry.name)) {
                    is RenameResult.Success -> result.affectedTargets.forEach { target ->
                        try {
                            indexEvents.publish(FileIndexEvent.Changed(root, target.fileUuid, target.targetHash, target.targetEpoch))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // 已提交的文件操作不因索引发布失败而回滚；协调器与启动扫描负责补偿。
                        }
                    }
                    is RenameResult.Conflict, RenameResult.NotFound -> throw DirectoryFileOpException(DirectoryFileOpCode.NOT_FOUND)
                }
            }
        } finally {
            if (admitted) renaming.remove(request)
        }
    }

    override suspend fun move(sessionId: String, uuids: List<String>, targetParentUuid: String): DirectoryBatchResult =
        operation(sessionId) { root ->
            batch(root, uuids) { entry ->
                val parent = activeEntry(root, targetParentUuid)
                if (!parent.isDirectory) throw DirectoryFileOpException(DirectoryFileOpCode.NOT_FOUND)
                if (entry.isDirectory && (entry.uuid == parent.uuid || isDescendant(parent, entry))) {
                    throw DirectoryFileOpException(DirectoryFileOpCode.TARGET_INSIDE_SOURCE)
                }
                if (entry.parentUuid != targetParentUuid) repository.updateParent(root, entry.uuid, targetParentUuid)
            }
        }

    override suspend fun delete(sessionId: String, uuids: List<String>): DirectoryBatchResult =
        operation(sessionId) { root ->
            batch(root, uuids) { entry -> repository.permanentDelete(root, entry.uuid) }
        }

    private suspend fun <T> operation(sessionId: String, block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        try {
            gate.withSessionMutation(sessionId) {
                if (isBusy(sessionId)) throw DirectoryFileOpException(DirectoryFileOpCode.SESSION_BUSY)
                block(repository.ensureSessionRoot(sessionId).uuid)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw mapped(failure)
        }
    }

    private suspend fun batch(
        root: String,
        uuids: List<String>,
        action: suspend (FileEntry) -> Unit,
    ): DirectoryBatchResult {
        val entries = linkedMapOf<String, FileEntry>()
        val failed = linkedMapOf<String, DirectoryFileOpCode>()
        val unique = uuids.distinct()
        // 先取得选择快照，确保子项排在父目录之前时也只操作最上层目录。
        for (uuid in unique) {
            try {
                entries[uuid] = mutableEntry(root, uuid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed[uuid] = mapped(failure).code
            }
        }
        val directories = entries.values.filter { it.isDirectory }
        val owners = entries.mapValues { (_, entry) ->
            directories.filter { it.uuid != entry.uuid && isDescendant(entry, it) }
                .minByOrNull { it.materializedPath.length }?.uuid ?: entry.uuid
        }
        val outcomes = mutableMapOf<String, DirectoryFileOpCode?>()
        for (owner in owners.values.distinct()) {
            try {
                action(entries.getValue(owner))
                outcomes[owner] = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                outcomes[owner] = mapped(failure).code
            }
        }
        val succeeded = mutableListOf<String>()
        for (uuid in unique) {
            val owner = owners[uuid] ?: continue
            // 后代随最上层选择一起落地；祖先失败时不能把未发生的操作报告为成功。
            val failure = outcomes.getValue(owner)
            if (failure == null) succeeded += uuid else failed[uuid] = failure
        }
        return DirectoryBatchResult(succeeded, unique.filter { it in failed }.associateWith { failed.getValue(it) })
    }

    private suspend fun activeEntry(root: String, uuid: String): FileEntry = repository.getByUuid(root, uuid)
        ?.takeIf { it.workspaceRootUuid == root && !it.inRecycleBin }
        ?: throw DirectoryFileOpException(DirectoryFileOpCode.NOT_FOUND)

    private suspend fun mutableEntry(root: String, uuid: String): FileEntry = activeEntry(root, uuid).also {
        if (it.uuid == root) throw DirectoryFileOpException(DirectoryFileOpCode.NAME_INVALID)
    }

    private fun isDescendant(entry: FileEntry, directory: FileEntry): Boolean =
        entry.materializedPath.startsWith(directory.materializedPath.trimEnd('/') + "/")

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            WorkspaceInternalPaths.isReservedName(name)) {
            throw DirectoryFileOpException(DirectoryFileOpCode.NAME_INVALID)
        }
    }

    private fun mapped(failure: Exception): DirectoryFileOpException {
        if (failure is DirectoryFileOpException) return failure
        val code = when (failure) {
            is WorkspaceLifecycleException -> if (failure.code == WorkspaceLifecycleErrorCode.TARGET_COLLISION) {
                DirectoryFileOpCode.NAME_EXISTS
            } else DirectoryFileOpCode.IO
            is SessionDeletingException -> DirectoryFileOpCode.SESSION_BUSY
            is StartupIsolationException -> DirectoryFileOpCode.WORKSPACE_UNAVAILABLE
            else -> DirectoryFileOpCode.IO
        }
        return DirectoryFileOpException(code, failure)
    }
}
