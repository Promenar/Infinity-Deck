package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver

/**
 * 资源管理器读取会话绑定工作区所需的最小能力。
 */
interface ResourceExplorerWorkspaceAccess {
    suspend fun resolve(sessionId: String): SessionWorkspace
}

internal class ResolverWorkspaceAccess(
    private val resolver: SessionWorkspaceResolver,
) : ResourceExplorerWorkspaceAccess {
    override suspend fun resolve(sessionId: String): SessionWorkspace = resolver.resolve(sessionId)
}

/** 资源管理器文件区当前展示的工作区。 */
sealed interface ResourceExplorerWorkspaceMode {
    /** 首次打开或切换会话时正在解析绑定的工作区。 */
    data object Resolving : ResourceExplorerWorkspaceMode

    /** 会话私有工作区：由 Room 树跟踪，支持搜索、导入与永久删除。 */
    data object Private : ResourceExplorerWorkspaceMode

    /** 自选目录工作区：直接浏览目录内容，与 Agent 文件工具的 `/` 指向同一目录。 */
    data class Directory(
        val workspaceId: String,
        val displayName: String,
        val kind: String,
        val location: String = "",
    ) : ResourceExplorerWorkspaceMode

    /** 绑定的工作区当前不可用；不回退到会话私有工作区。 */
    data class Unavailable(
        val workspaceId: String,
        val displayName: String?,
        val reason: String,
        val kind: String? = null,
        val location: String? = null,
    ) : ResourceExplorerWorkspaceMode
}

/** 等待粘贴的目录条目。 */
internal data class DirectoryClipboard(val paths: List<String>, val cut: Boolean)

internal fun DirectoryClipboard.canPasteInto(directory: String): Boolean =
    paths.none { directory == it || directory.startsWith(it.trimEnd('/') + "/") || (cut && parentDirectoryPath(it) == directory) }

internal fun parentDirectoryPath(path: String): String? =
    if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }
