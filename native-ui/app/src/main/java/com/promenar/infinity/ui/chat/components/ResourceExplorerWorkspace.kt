package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry

/**
 * 资源管理器读取会话工作区所需的最小能力。目录后端由 [SessionWorkspaceResolver.backend] 提供，
 * 沿用其符号链接拒绝、越界路径与保留名校验；资源管理器不自行拼接真实路径。
 */
interface ResourceExplorerWorkspaceAccess {
    suspend fun resolve(sessionId: String): SessionWorkspace
    fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend
}

internal class ResolverWorkspaceAccess(
    private val resolver: SessionWorkspaceResolver,
) : ResourceExplorerWorkspaceAccess {
    override suspend fun resolve(sessionId: String): SessionWorkspace = resolver.resolve(sessionId)
    override fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend = resolver.backend(directory)
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

/** 目录读取失败；[reason] 只取目录后端的领域提示，其它异常为 null，由界面显示通用文案。 */
internal data class DirectoryLoadError(val reason: String?)

/** 目录工作区的浏览状态：[path] 为目录内的规范路径，`/` 即工作区根。 */
internal data class DirectoryBrowserState(
    val path: String = "/",
    val entries: List<ExternalFolderEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: DirectoryLoadError? = null,
    val selectedFile: ExternalFolderEntry? = null,
    val preview: ExternalTextPreview? = null,
    val previewError: String? = null,
    /** 多选中的条目路径；非空时点按切换选择，底部显示操作栏。 */
    val selection: Set<String> = emptySet(),
    val clipboard: DirectoryClipboard? = null,
    val operating: Boolean = false,
    val outcome: DirectoryOpOutcome? = null,
)

/** 复制或剪切后等待粘贴的条目；进入目标目录后“粘贴到此处”。 */
internal data class DirectoryClipboard(val paths: List<String>, val cut: Boolean)

enum class DirectoryOp { COPY, MOVE, DELETE }

/** 一次操作的结果提示：全部成功只报数量，部分失败报失败数与首个原因。 */
internal data class DirectoryOpOutcome(val op: DirectoryOp, val succeeded: Int, val failed: Int, val reason: DirectoryFileOpCode?)

internal fun DirectoryBatchResult.toOutcome(op: DirectoryOp) =
    DirectoryOpOutcome(op, succeeded.size, failed.size, failed.values.firstOrNull())

/** 粘贴目标不能是条目自身或其子目录；剪切到原目录没有意义，复制到原目录由后端自动改名。 */
internal fun DirectoryClipboard.canPasteInto(directory: String): Boolean =
    paths.none { directory == it || directory.startsWith(it.trimEnd('/') + "/") || (cut && parentDirectoryPath(it) == directory) }

/**
 * 私有工作区文件页的长按多选、剪切与操作结果。[selection] 以 UUID 为键并保留条目快照，
 * 供改名对话框取当前名称、剪切时记下物化路径。
 */
internal data class PrivateFileOpsState(
    val selection: Map<String, FileEntry> = emptyMap(),
    val clipboard: PrivateFileClipboard? = null,
    val operating: Boolean = false,
    val outcome: DirectoryOpOutcome? = null,
)

/** 剪切后等待粘贴的私有工作区条目：按 UUID 移动，按物化路径判定粘贴目标。 */
internal data class PrivateFileClipboard(val uuids: List<String>, val paths: List<String>) {
    /** 粘贴栏沿用目录工作区的剪切剪贴板载体。 */
    val board: DirectoryClipboard get() = DirectoryClipboard(paths, cut = true)

    fun canPasteInto(directoryPath: String): Boolean = board.canPasteInto(directoryPath)
}

/** 上一级目录；根目录返回 null。 */
internal fun parentDirectoryPath(path: String): String? =
    if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }
