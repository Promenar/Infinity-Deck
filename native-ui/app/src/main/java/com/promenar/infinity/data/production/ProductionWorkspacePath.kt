package com.promenar.infinity.data.production

import com.promenar.infinity.data.workspace.LinuxSessionPathKind
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths

private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

// 物理工作区已有内部名称门禁，使用保留名称可区分旧清单中私有根下的 session/ 子目录。
private const val PRIVATE_PRODUCTION_PREFIX = ".infinity-production-private/"

internal fun privateProductionLocalPath(path: String): String? =
    path.takeIf { it.startsWith(PRIVATE_PRODUCTION_PREFIX) }
        ?.removePrefix(PRIVATE_PRODUCTION_PREFIX)
        ?.let {
            ProductionManifestCodec.validatePath(it)
            "/${validateProductionLocalPath(it)}"
        }

internal fun productionRelativePathSyntax(path: String): String =
    if (privateProductionLocalPath(path) != null) path else validateProductionLocalPath(path)

private fun validateProductionLocalPath(path: String): String {
    require(!uriScheme.containsMatchIn(path.trimStart()) && "://" !in path) { "制作路径须为工作区文件路径，不接受 URI。" }
    val relative = path.removePrefix("/")
    ProductionManifestCodec.validatePath(relative)
    try {
        relative.split('/').forEach(ExternalFolderPaths::validateName)
    } catch (failure: ExternalFolderException) {
        throw IllegalArgumentException("制作路径包含无效或保留名称，请使用工作区内的文件路径。", failure)
    }
    return relative
}

/** 将已知命名空间转为清单路径，保留独立私有目录与挂载的归属。 */
internal fun normalizeProductionWorkspacePath(
    raw: String,
    linux: Boolean,
    workspaceDirectory: String?,
    projectRoot: String?,
    visibleMounts: Set<String>,
): String {
    val relative = productionRelativePathSyntax(raw)
    if (privateProductionLocalPath(relative) != null) return relative
    fun privatePath(path: String) =
        if (path.startsWith("session/")) path else PRIVATE_PRODUCTION_PREFIX + path
    fun projectMount(path: String): String {
        val alias = path.removePrefix("mnt/").substringBefore('/')
        require(alias in visibleMounts && alias != "skills" &&
            (projectRoot == null || path == projectRoot || path.startsWith("$projectRoot/"))) {
            "制作路径 $raw 不属于当前项目的挂载，请先存入当前工作区目录。"
        }
        return path
    }
    if (relative == "mnt" || relative == "skills" || relative.startsWith("skills/") || relative == "session") {
        throw IllegalArgumentException("制作路径 $raw 指向保留目录，请使用工作区内的文件或项目目录。")
    }
    if (relative.startsWith("mnt/")) return projectMount(relative)
    if (!raw.startsWith('/')) return relative
    if (!linux) {
        val inside = SessionPrivateWorkspacePaths.relative(raw)?.removePrefix("/")
        return if (inside == null) relative else privatePath(inside)
    }

    val routed = try {
        LinuxSessionPaths.route(raw, privateWorkspace = workspaceDirectory == null, workspaceDirectory = workspaceDirectory)
    } catch (failure: ExternalFolderException) {
        throw IllegalArgumentException("Linux 制作路径 $raw 无效，请核对工作区下的路径与保留名称。", failure)
    }
    val inside = routed.path.removePrefix("/")
    require(inside.isNotEmpty()) { "制作路径 $raw 需要工作区根目录下的文件或项目目录。" }
    val first = inside.substringBefore('/')
    val reservedNamespace = first in setOf("session", "mnt", "skills")
    return when (routed.kind) {
        LinuxSessionPathKind.PRIVATE -> if (workspaceDirectory != null || reservedNamespace) {
            privatePath(inside)
        } else inside
        LinuxSessionPathKind.WORKSPACE -> {
            require(!reservedNamespace) { "制作路径 $raw 的首级名称与保留目录冲突，请改用其它工作区目录。" }
            inside
        }
        else -> throw IllegalArgumentException("Linux 制作路径 $raw 不在当前工作区，请先存入 /workspace 或 /home/session 下。")
    }
}
