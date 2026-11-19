package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.isValidKnowledgeMount
import com.promenar.infinity.data.workspace.LinuxSessionPathKind
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.CopyKnowledgeToLinuxSkill
import kotlinx.serialization.json.*

/** 将来源挂载和会话私有根一并绑定到审批目标，别名复用不能继承旧审批。 */
internal suspend fun knowledgeLinuxCopyTarget(
    session: Session,
    args: JsonObject,
    repository: IWorkspaceRepository?,
    mounts: ExternalWorkspaceAccess?,
): String? {
    if (session.runMode != SessionRunMode.LINUX || repository == null || mounts == null) return null
    fun string(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val rawSource = string("source")?.takeIf { it.startsWith("/mnt/") } ?: return null
    val source = com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(rawSource)
    val (alias, _) = ExternalMountPaths.parse(source) ?: return null
    val mount = mounts.visible(session.id).singleOrNull { it.alias == alias && it.sessionId == session.id &&
        it.isValidKnowledgeMount && it.unavailableReason == null } ?: return null
    val target = LinuxSessionPaths.normalize(if ("target" in args) string("target") ?: return null else CopyKnowledgeToLinuxSkill.DEFAULT_TARGET)
    val route = LinuxSessionPaths.route(target, session.workspaceId == null, null)
    if (route.kind !in setOf(LinuxSessionPathKind.PRIVATE, LinuxSessionPathKind.ROOTFS)) return null
    val root = if (route.kind == LinuxSessionPathKind.PRIVATE) repository.ensureSessionRoot(session.id) else null
    val relative = route.path.lowercase(java.util.Locale.ROOT)
    val skills = route.kind == LinuxSessionPathKind.PRIVATE && (relative == "/.agents" || relative == "/.agents/skills" ||
        relative.startsWith("/.agents/skills/") || relative == "/" && source.substringAfterLast('/').equals(".agents", true))
    return JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive(target), buildJsonObject {
        put("source", source)
        put("mountIds", buildJsonObject { put(alias, mount.id) })
        put("linuxRootfs", route.kind == LinuxSessionPathKind.ROOTFS)
        if (skills) { put("linuxSkills", true); put("oneShot", true) }
        root?.let { put("privateRootUuid", it.uuid); put("privateRootPath", java.io.File(it.physicalRootPath).canonicalPath) }
    })).toString()
}
