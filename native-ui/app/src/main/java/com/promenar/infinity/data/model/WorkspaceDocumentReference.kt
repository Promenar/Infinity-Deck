package com.promenar.infinity.data.model

import com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths
import com.promenar.infinity.data.workspace.workspaceFileRelativePath
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import kotlinx.serialization.Serializable

/** 附件正文已存入工作区，路径使用对应会话运行模式的文件工具命名空间。 */
@Serializable
data class WorkspaceDocumentReference(
    val path: String,
    val lineCount: Int,
    val estimatedTokens: Int,
    val sourceSessionId: String? = null,
    val workspaceId: String? = null,
    val isPrivate: Boolean? = null,
    val linuxNamespace: Boolean? = null,
    val worktreeDirectory: String? = null,
    val sha256: String? = null,
)

/** 这里只核验逻辑来源；文件工具继续负责实际后端的目录与路径安全检查。 */
internal fun WorkspaceDocumentReference.isBoundTo(session: Session?): Boolean {
    if (session == null || sourceSessionId.isNullOrBlank() || sourceSessionId != session.id) return false
    val privateSource = isPrivate ?: return false
    val linuxSource = linuxNamespace ?: return false
    if (linuxSource != (session.runMode == SessionRunMode.LINUX)) return false
    val normalized = try {
        if (path.any(Char::isISOControl) || !path.startsWith('/')) return false
        ExternalFolderPaths.normalize(path)
    } catch (_: Exception) { return false }
    if (normalized != path || path == "/") return false
    if (privateSource) {
        val prefix = if (linuxSource) LinuxSessionPaths.PRIVATE_ROOT else SessionPrivateWorkspacePaths.ROOT
        return workspaceId == null && worktreeDirectory == null && path.startsWith("$prefix/")
    }
    val currentWorkspace = if (session.worktreeDirectory != null) {
        BranchWorktreeWorkspaceResolver.ID_PREFIX + session.id
    } else session.workspaceId
    if (workspaceId.isNullOrBlank() || workspaceId != currentWorkspace || worktreeDirectory != session.worktreeDirectory) return false
    if (workspaceId.startsWith(BranchWorktreeWorkspaceResolver.ID_PREFIX) && worktreeDirectory.isNullOrBlank()) return false
    return try {
        if (linuxSource) path.startsWith("${LinuxSessionPaths.WORKSPACE_ROOT}/")
        else workspaceFileRelativePath(path) == path && !ExternalMountPaths.isMountedPath(path)
    } catch (_: Exception) { false }
}
