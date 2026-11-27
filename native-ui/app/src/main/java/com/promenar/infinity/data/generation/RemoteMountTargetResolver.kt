package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.RemoteMountLocalFile
import com.promenar.infinity.data.storage.isSftpRemoteSource
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.skills.BuiltinRealPathWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import kotlinx.serialization.json.*

internal data class ResolvedRemoteMount(
    val mount: ExternalMount,
    val spec: SftpRemoteMountSpec,
    val identity: SshExecutionIdentity,
) {
    val sourceIdentity: String get() = Sha256Utils.hash(SftpRemoteMountSpec.encode(spec))
}

internal data class ResolvedRemoteMountTarget(
    val binding: ResolvedRemoteMount,
    val local: RemoteMountLocalFile?,
    val target: String,
)

/** 审批预览和执行共享取址，任何失败都不能产生可复用的批准目标。 */
internal class RemoteMountTargetResolver(
    private val mounts: ExternalWorkspaceAccess,
    private val hostIdentity: suspend (String) -> SshExecutionIdentity?,
    private val localFile: suspend (String, SessionRunMode?, String, Boolean) -> RemoteMountLocalFile,
) {
    suspend fun binding(sessionId: String, path: RemoteMountPath, writing: Boolean): ResolvedRemoteMount {
        val visible = mounts.visible(sessionId).singleOrNull { it.alias == path.alias && it.isSftpRemoteSource }
            ?: throw ExternalFolderException("NOT_FOUND", "远程挂载不存在或不属于当前会话")
        val (mount, spec) = if (mounts is ExternalFolderManager) {
            mounts.requireSftpTransferMount(visible.id, sessionId)
        } else visible to (SftpRemoteMountSpec.decode(visible.sourceId)
            ?: throw ExternalFolderException("INVALID_MOUNT_SPEC", "远程挂载配置无效"))
        if (mount.alias != path.alias) throw ExternalFolderException("CONFLICT", "远程挂载别名已变更")
        if (writing && mount.readOnly) throw ExternalFolderException("READ_ONLY", "远程挂载为只读")
        val identity = hostIdentity(spec.hostAlias)
            ?: throw ExternalFolderException("HOST_AUTHORIZATION_CHANGED", "SSH 主机配置已移除或不可用")
        return ResolvedRemoteMount(mount, spec, identity)
    }

    suspend fun resolve(
        sessionId: String,
        mode: SessionRunMode?,
        tool: ProtocolTool,
        operation: RemoteMountOperation,
        args: JsonObject,
        parsed: RemoteMountArguments,
        bound: ResolvedRemoteMount,
    ): ResolvedRemoteMountTarget {
        val path = requireNotNull(parsed.path)
        val local = parsed.workspacePath?.let { localFile(sessionId, mode, it, operation == RemoteMountOperation.DOWNLOAD) }
        val paths = buildMap {
            put("mountId", bound.mount.id)
            put("host", bound.spec.hostAlias)
            put("mountRoot", bound.spec.rootPath)
            put("mountSource", bound.sourceIdentity)
            local?.let {
                put("localPath", it.workspacePath)
                put("localIdentity", it.identity)
                put("localRevision", it.revision)
            }
        }
        val target = ToolExecutionTargets.extract(tool, args.toString(), paths, mapOf(bound.spec.hostAlias to bound.identity))
            ?: throw ExternalFolderException("INVALID_ARGUMENTS", "无法确定远程挂载执行目标")
        val backend = mounts.backend(sessionId, mapOf(path.alias to bound.mount.id))
        val source = backend.stat(path.virtual)
        val destination = when (operation) {
            RemoteMountOperation.RENAME -> backend.stat(requireNotNull(parsed.targetPath).virtual)
            RemoteMountOperation.ARCHIVE, RemoteMountOperation.MKDIR -> source
            else -> null
        }
        if (operation in setOf(RemoteMountOperation.DOWNLOAD, RemoteMountOperation.RENAME, RemoteMountOperation.DELETE) &&
            (source == null || source.isDirectory)
        ) {
            throw ExternalFolderException(if (source == null) "NOT_FOUND" else "UNSUPPORTED_FILE_TYPE", "远程源须为普通文件")
        }
        val targetExists = when (operation) {
            RemoteMountOperation.DOWNLOAD -> local?.size != null
            RemoteMountOperation.DELETE -> source != null
            else -> destination != null
        }
        val preview = buildJsonObject {
            val bytes = if (operation == RemoteMountOperation.ARCHIVE) local?.size
                else source?.takeUnless { it.isDirectory }?.size
            bytes?.let { put("bytes_total", it) }
            put("target_exists", targetExists)
            put("will_overwrite", operation == RemoteMountOperation.DOWNLOAD && targetExists && parsed.overwrite)
            source?.identity?.let { put("source_revision", it) }
            destination?.identity?.let { put("target_revision", it) }
        }
        val current = binding(sessionId, path, operation.writesRemote)
        if (bound.identity != current.identity) {
            throw ExternalFolderException("HOST_AUTHORIZATION_CHANGED", "SSH 主机身份已变更，请重新审批")
        }
        if (bound.mount.id != current.mount.id || bound.spec != current.spec) {
            throw ExternalFolderException("CONFLICT", "远程挂载来源已变更，请重新审批")
        }
        return ResolvedRemoteMountTarget(current, local, ToolExecutionTargets.withSftpPreview(target, preview))
    }
}

internal fun remoteMountTargetResolver(
    mounts: ExternalWorkspaceAccess,
    hosts: SshHostStore,
    builtinFiles: BuiltinRealPathWorkspaceAccess?,
    linuxFiles: LinuxSessionExternalWorkspaceAccess?,
) = RemoteMountTargetResolver(mounts, { hosts.find(it)?.executionIdentity() }) { sessionId, mode, path, writing ->
    val local = if (mode == SessionRunMode.LINUX) linuxFiles?.remoteMountLocalFile(sessionId, path, writing)
        else builtinFiles?.remoteMountLocalFile(sessionId, path, writing)
    local ?: throw ExternalFolderException("WORKSPACE_UNAVAILABLE", "当前会话的工作区文件解析器不可用")
}
