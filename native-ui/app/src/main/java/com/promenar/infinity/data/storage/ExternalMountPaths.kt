package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalMountCommandAccess
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.util.Base64
import java.io.File

object ExternalMountPaths {
    const val LIBRARY_SCOPE = "__library__"
    fun isLibraryScope(scope: String): Boolean = scope == LIBRARY_SCOPE
    const val ROOT_UUID = "external:root"
    const val SKILLS_ALIAS = "skills"
    const val SKILLS_MOUNT_ID = "builtin:skills"
    const val SKILLS_SOURCE = "builtin_skills"
    fun validateAlias(alias: String): String = ExternalFolderPaths.validateName(alias.trim()).also {
        if (it in setOf("mnt", SKILLS_ALIAS)) throw ExternalFolderException("INVALID_PATH", "$it 是保留名称")
    }
    fun parse(path: String): Pair<String, String>? {
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized == "/skills" || normalized.startsWith("/skills/")) {
            return SKILLS_ALIAS to normalized.removePrefix("/skills").ifEmpty { "/" }
        }
        if (!normalized.startsWith("/mnt/")) return null
        val parts = normalized.removePrefix("/mnt/").split('/', limit = 2)
        if (parts[0] == SKILLS_ALIAS) throw ExternalFolderException("INVALID_PATH", "skills 是保留名称；请在挂载管理中以其它别名重新添加历史同名挂载")
        validateAlias(parts[0])
        return parts[0] to if (parts.size == 1) "/" else "/${parts[1]}"
    }
    fun uuid(mount: ExternalMount, path: String): String = "external:${mount.id}:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ExternalFolderPaths.normalize(path).toByteArray(Charsets.UTF_8))
    fun uuidPath(uuid: String, mounts: List<ExternalMount>): String? {
        if (uuid == ROOT_UUID) return "/mnt"
        if (!uuid.startsWith("external:")) return null
        val separator = uuid.lastIndexOf(':')
        if (separator <= "external:".length) return null
        val mount = mounts.singleOrNull { it.id == uuid.substring("external:".length, separator) } ?: return null
        return try {
            virtualPath(mount, ExternalFolderPaths.normalize(String(Base64.getUrlDecoder().decode(uuid.substring(separator + 1)), Charsets.UTF_8)))
        } catch (_: IllegalArgumentException) { null }
    }

    /** 工作区挂载的别名；其虚拟路径以 `/` 为根，`/mnt` 和 `/skills` 为独立保留根。 */
    const val WORKSPACE_ALIAS = ""
    const val WORKSPACE_SOURCE = "workspace"
    const val KNOWLEDGE_BASE_SOURCE = "knowledge_base"
    fun workspaceMountId(workspaceId: String) = "ws_$workspaceId"

    fun virtualPath(mount: ExternalMount, relative: String): String = when {
        mount.isWorkspaceRoot -> relative
        mount.isUserSkills -> "/skills" + relative.takeIf { it != "/" }.orEmpty()
        else -> "/mnt/${mount.alias}" + relative.takeIf { it != "/" }.orEmpty()
    }

    fun isMountedPath(path: String): Boolean {
        val normalized = ExternalFolderPaths.normalize(path)
        return normalized == "/mnt" || parse(normalized) != null
    }

    /** `/skills` 与 `/mnt/<别名>` 优先归属挂载；[rooted] 时其余路径（`/mnt` 除外）属于工作区。 */
    fun aliasOf(path: String, rooted: Boolean): String? {
        parse(path)?.let { return it.first }
        if (com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths.contains(path)) return null
        return if (rooted && ExternalFolderPaths.normalize(path) != "/mnt") WORKSPACE_ALIAS else null
    }
    fun conflicts(first: ExternalMount, second: ExternalMount): Boolean =
        first.id != second.id && first.alias == second.alias &&
            first.sessionId == second.sessionId

    /** 仅映射本机命令路径，不代表远端 SSH 或其它执行后端拥有该目录。 */
    fun commandAccess(mount: ExternalMount, relativePath: String = "/"): ExternalMountCommandAccess {
        val relative = ExternalFolderPaths.normalize(relativePath)
        if (!mount.commandAccessible) return ExternalMountCommandAccess(null, "该来源没有命令可用的真实路径，请使用文件工具访问虚拟路径")
        mount.unavailableReason?.let { return ExternalMountCommandAccess(null, it) }
        val root = File(mount.sourceId)
        if (!root.isAbsolute) throw ExternalFolderException("INVALID_PATH", "命令路径必须为绝对路径")
        return ExternalMountCommandAccess(File(root, relative.removePrefix("/")).path, null)
    }
}

val ExternalMount.isWorkspaceRoot: Boolean get() = alias == ExternalMountPaths.WORKSPACE_ALIAS
val ExternalMount.isUserSkills: Boolean get() = id == ExternalMountPaths.SKILLS_MOUNT_ID && sourceType == ExternalMountPaths.SKILLS_SOURCE

/** 知识库来源始终只读，即使历史或受损记录的标志没有置位。 */
internal fun ExternalMount.requireWritable() {
    if (readOnly || sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE) {
        throw ExternalFolderException("READ_ONLY", "知识库挂载为只读")
    }
}

/** 一次工具调用要固定的全部挂载身份：可见挂载加当前工作区挂载。 */
suspend fun com.promenar.infinity.domain.storage.ExternalWorkspaceAccess.namespaceBindings(sessionId: String): Map<String, String> =
    visible(sessionId).associate { it.alias to it.id } + listOfNotNull(workspaceMount(sessionId)).associate { it.alias to it.id }
