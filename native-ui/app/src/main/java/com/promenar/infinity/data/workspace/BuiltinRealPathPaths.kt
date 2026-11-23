package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.isKnowledgeBaseSource
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

internal enum class BuiltinRealPathKind {
    /** 当前会话私有目录（files/session_workspaces/<uuid>），继续由 Room 事务、版本与索引管理。 */
    PRIVATE,

    /** 当前会话绑定的自选外部目录主工作区。 */
    WORKSPACE,

    /** 用户技能目录（files/agent_skills），所有写删改操作两档均强制逐次审批（L2）。 */
    USER_SKILLS,

    /** 会话可见的 real_path 外部挂载（以真实路径直接寻址，不再经 /mnt/<alias> 伪装）。 */
    REAL_MOUNT,

    /** 无真实 POSIX 路径的逻辑挂载（/mnt/knowledge、/mnt/<saf-alias>）。 */
    VIRTUAL_MOUNT,

    /** /mnt 虚拟根目录，仅在会话存在资料库或 SAF 虚拟挂载时可见。 */
    VIRTUAL_MOUNTS_ROOT,

    /** 加密密钥存储文件（shared_prefs/infinity_encrypted_secrets.xml*），文件工具读写均硬拒。 */
    RUNTIME_DATA_SECRET,

    /** 应用关键运行时数据目录与诊断日志（databases/、非密钥 shared_prefs/、no_backup/、诊断日志）：开放只读诊断，禁止文件工具写删。 */
    RUNTIME_DATA_PROTECTED,

    /** 其它本机真实 Android 路径：按系统权限只读；写删操作在风险审批档需逐条审批（L1）。 */
    HOST_REAL_PATH,
}

internal data class BuiltinRealPathContext(
    val primaryRoot: File,
    val primaryIsDirectory: Boolean,
    val sessionPrivateRoot: File,
    val userSkillsRoot: File,
    val appDataDir: File,
    val realMounts: List<Pair<ExternalMount, File>>,
    val virtualMounts: List<ExternalMount>,
) {
    val primaryPath: String = canonicalOrAbs(primaryRoot)
    val privatePath: String = canonicalOrAbs(sessionPrivateRoot)
    val userSkillsPath: String = canonicalOrAbs(userSkillsRoot)
    val appDataPath: String = canonicalOrAbs(appDataDir)
    val appDataAliasPath: String? = if (appDataPath.startsWith("/data/user/0/")) {
        "/data/data/" + appDataPath.removePrefix("/data/user/0/")
    } else null
    val normalizedRealMounts: List<Pair<ExternalMount, String>> = realMounts
        .map { (mount, dir) -> mount to canonicalOrAbs(dir) }
        .sortedByDescending { it.second.length }

    companion object {
        internal fun canonicalOrAbs(file: File): String =
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).trimEnd('/').ifEmpty { "/" }
    }
}

internal data class BuiltinRealPathRoute(
    val kind: BuiltinRealPathKind,
    /** 唯一规范路径：本机真实 Android 路径，或逻辑虚拟源的 `/mnt/<alias>/...`。 */
    val canonicalPath: String,
    /** 对应后端内部的相对路径（以 `/` 开头）；HOST_REAL_PATH / RUNTIME_DATA_* 为绝对真实路径本身。 */
    val relativePath: String,
    val mount: ExternalMount? = null,
)

/**
 * 内置模式唯一路径解析与路由：
 * - 相对路径（不带前导 `/`）锚定当前主工作区真实根目录；
 * - 绝对路径（带前导 `/`）只接受真实 Android 路径或仅限无 POSIX 路径的逻辑虚拟源（`/mnt/knowledge`、`/mnt/<saf-alias>`）；
 * - 显式拒绝已废除的内置伪根（`/skills`、`/session`、`/mnt/<真实目录别名>` 以及不存在的伪 `/foo`）并给出唯一真实路径指引。
 */
internal object BuiltinRealPathPaths {
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
    private val SHELL_VAR_TOKENS = listOf(
        "\$INFINITY_WORKSPACE", "\${INFINITY_WORKSPACE}",
        "\$INFINITY_SKILLS", "\${INFINITY_SKILLS}",
        "\$NEXARA_WORKSPACE", "\${NEXARA_WORKSPACE}",
        "\$NEXARA_SKILLS", "\${NEXARA_SKILLS}",
    )
    private const val SECRET_PREF_PREFIX = "infinity_encrypted_secrets.xml"

    fun route(raw: String, ctx: BuiltinRealPathContext): BuiltinRealPathRoute {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any(Character::isISOControl) || '\\' in trimmed || WINDOWS_DRIVE.containsMatchIn(trimmed)) {
            fail("INVALID_PATH", "路径包含非法字符、反斜杠或盘符写法")
        }
        if (SHELL_VAR_TOKENS.any { trimmed.contains(it) }) {
            fail(
                "INVALID_PATH",
                "文件工具不解析 Shell 环境变量。请改用工作区相对路径（不带前导 /）或完整真实路径（当前工作区：${ctx.primaryPath}；用户技能：${ctx.userSkillsPath}）。",
            )
        }
        if (!trimmed.startsWith('/')) {
            val rel = normalizeRelative(trimmed)
            val full = joinPath(ctx.primaryPath, rel)
            return routeAbsolute(full, ctx)
        }
        val normalized = normalizeAbsolute(trimmed, ctx)
        return routeAbsolute(normalized, ctx)
    }

    fun normalizeArguments(tool: String, args: JsonObject, ctx: BuiltinRealPathContext): JsonObject =
        JsonObject(args.toMutableMap().apply {
            listOf("path", "parentPath", "newParentPath").forEach { key ->
                (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let {
                    put(key, JsonPrimitive(route(it.content, ctx).canonicalPath))
                }
            }
            val defaultKey = when (tool) {
                "create_file", "create_directory" -> "parentPath".takeIf { !hasString(args, "parentUuid") }
                "list_files" -> "path".takeIf { !hasString(args, "parentUuid") }
                else -> null
            }
            if (defaultKey != null && !hasString(args, defaultKey)) {
                put(defaultKey, JsonPrimitive(ctx.primaryPath))
            }
        })

    fun isSecretStorePath(path: String, appDataPath: String): Boolean {
        val prefsRoot = "$appDataPath/shared_prefs"
        if (!isSameOrBelow(path, prefsRoot)) return false
        val name = path.removePrefix("$prefsRoot/").substringBefore('/')
        return name == SECRET_PREF_PREFIX || name == "$SECRET_PREF_PREFIX.bak"
    }

    fun isProtectedRuntimeDataPath(path: String, appDataPath: String): Boolean {
        val protectedDirs = listOf(
            "$appDataPath/databases",
            "$appDataPath/shared_prefs",
            "$appDataPath/no_backup",
            "$appDataPath/files/diag-keep",
        )
        val protectedFiles = setOf(
            "$appDataPath/files/infinity_diagnostics.jsonl",
            "$appDataPath/files/infinity_logs.txt",
        )
        return path in protectedFiles || protectedDirs.any { isSameOrBelow(path, it) }
    }

    fun isSkillsMutationPath(path: String, ctx: BuiltinRealPathContext): Boolean {
        if (isSameOrBelow(path, ctx.userSkillsPath)) return true
        val rel = when {
            isSameOrBelow(path, ctx.primaryPath) -> relativeTo(path, ctx.primaryPath)
            isSameOrBelow(path, ctx.privatePath) -> relativeTo(path, ctx.privatePath)
            else -> return false
        }.lowercase(Locale.ROOT)
        return rel == "/.agents" || rel == "/.agents/skills" || rel.startsWith("/.agents/skills/")
    }

    fun aliasOf(path: String, ctx: BuiltinRealPathContext): String {
        if (isSameOrBelow(path, ctx.userSkillsPath)) return ExternalMountPaths.SKILLS_ALIAS
        if (path.startsWith("/mnt/")) {
            val alias = path.removePrefix("/mnt/").substringBefore('/')
            if (ctx.virtualMounts.any { it.alias == alias }) return alias
        }
        ctx.normalizedRealMounts.firstOrNull { isSameOrBelow(path, it.second) }?.let { return it.first.alias }
        return ExternalMountPaths.WORKSPACE_ALIAS
    }

    private fun routeAbsolute(path: String, ctx: BuiltinRealPathContext): BuiltinRealPathRoute {
        // 1. 逻辑虚拟挂载（仅无 POSIX 路径的资料库与 SAF）
        if (path == "/mnt") {
            if (ctx.virtualMounts.isNotEmpty()) {
                return BuiltinRealPathRoute(BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT, "/mnt", "/")
            }
            if (File("/mnt").exists()) {
                return BuiltinRealPathRoute(BuiltinRealPathKind.HOST_REAL_PATH, "/mnt", "/mnt")
            }
            fail("NOT_FOUND", "当前会话没有虚拟挂载（资料库或 SAF）；真实目录挂载请直接使用真实路径。")
        }
        if (path.startsWith("/mnt/")) {
            val rest = path.removePrefix("/mnt/")
            val alias = rest.substringBefore('/')
            val rel = if ('/' in rest) "/" + rest.substringAfter('/') else "/"
            ctx.virtualMounts.singleOrNull { it.alias == alias }?.let { mount ->
                return BuiltinRealPathRoute(BuiltinRealPathKind.VIRTUAL_MOUNT, path, rel, mount)
            }
            if (alias == ExternalMountPaths.SKILLS_ALIAS) {
                val suggested = joinPath(ctx.userSkillsPath, rel)
                fail("INVALID_PATH", "内置模式已统一使用真实路径，不再使用 /mnt/skills；请改用 $suggested 或 read_skill_file。")
            }
            ctx.normalizedRealMounts.singleOrNull { it.first.alias == alias }?.let { (_, root) ->
                val suggested = joinPath(root, rel)
                fail("INVALID_PATH", "内置模式已统一使用真实路径，不再使用 /mnt/$alias；请直接使用真实路径 $suggested。")
            }
            if (!File("/mnt/$alias").exists()) {
                fail("NOT_FOUND", "挂载或路径不存在：$path")
            }
        }

        // 2. 显式拒绝已废除的内置伪虚拟前缀 /skills、/session
        if (path == "/skills" || path.startsWith("/skills/")) {
            val rel = path.removePrefix("/skills").ifEmpty { "/" }
            val suggested = joinPath(ctx.userSkillsPath, rel)
            fail("INVALID_PATH", "内置模式已统一使用真实路径，不再使用虚拟路径 /skills；用户技能真实路径为 $suggested（读取技能也可使用 read_skill_file）。")
        }
        if (path == "/session" || path.startsWith("/session/")) {
            val rel = path.removePrefix("/session").ifEmpty { "/" }
            val suggested = joinPath(ctx.privatePath, rel)
            fail("INVALID_PATH", "内置模式已统一使用真实路径，不再使用虚拟路径 /session；会话专用空间真实路径为 $suggested。")
        }

        // 3. 真实区域归属（严格优先顺序）
        if (isSameOrBelow(path, ctx.userSkillsPath)) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.USER_SKILLS, path, relativeTo(path, ctx.userSkillsPath))
        }
        if (isSameOrBelow(path, ctx.primaryPath)) {
            val kind = if (ctx.primaryIsDirectory) BuiltinRealPathKind.WORKSPACE else BuiltinRealPathKind.PRIVATE
            return BuiltinRealPathRoute(kind, path, relativeTo(path, ctx.primaryPath))
        }
        if (isSameOrBelow(path, ctx.privatePath)) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.PRIVATE, path, relativeTo(path, ctx.privatePath))
        }
        ctx.normalizedRealMounts.firstOrNull { isSameOrBelow(path, it.second) }?.let { (mount, root) ->
            return BuiltinRealPathRoute(BuiltinRealPathKind.REAL_MOUNT, path, relativeTo(path, root), mount)
        }

        // 4. 关键运行时数据（密钥文件读写全拒；其余开放只读自诊断、写删硬拒）
        if (isSecretStorePath(path, ctx.appDataPath)) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.RUNTIME_DATA_SECRET, path, path)
        }
        if (isProtectedRuntimeDataPath(path, ctx.appDataPath)) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.RUNTIME_DATA_PROTECTED, path, path)
        }

        // 5. 其它本机真实路径（并拒绝明显属于已废除伪工作区根的前导 / 路径）
        if (isSameOrBelow(path, ctx.appDataPath)) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.HOST_REAL_PATH, path, path)
        }
        if (path == "/") {
            return BuiltinRealPathRoute(BuiltinRealPathKind.HOST_REAL_PATH, "/", "/")
        }
        val topDir = "/" + path.removePrefix("/").substringBefore('/')
        if (File(topDir).exists()) {
            return BuiltinRealPathRoute(BuiltinRealPathKind.HOST_REAL_PATH, path, path)
        }

        val relativeHint = path.removePrefix("/")
        val realHint = joinPath(ctx.primaryPath, "/$relativeHint")
        fail(
            "INVALID_PATH",
            "路径 $path 不是有效的本机真实路径。内置模式下工作区相对路径不带前导 /（例如 $relativeHint），绝对路径请使用真实路径（例如 $realHint）。",
        )
    }

    private fun normalizeRelative(raw: String): String {
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            fail("INVALID_PATH", "相对路径不允许包含 ..；跨目录请直接传入完整真实路径。")
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    private fun normalizeAbsolute(raw: String, ctx: BuiltinRealPathContext): String {
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) {
            fail("INVALID_PATH", "绝对路径不允许包含 . 或 .. 片段，请传入规范真实路径。")
        }
        val collapsed = if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
        val alias = ctx.appDataAliasPath
        return if (alias != null && isSameOrBelow(collapsed, alias)) {
            ctx.appDataPath + collapsed.removePrefix(alias)
        } else {
            collapsed
        }
    }

    internal fun isSameOrBelow(path: String, root: String): Boolean {
        val cleanRoot = root.trimEnd('/').ifEmpty { "/" }
        return if (cleanRoot == "/") path.startsWith('/') else path == cleanRoot || path.startsWith("$cleanRoot/")
    }

    internal fun relativeTo(path: String, root: String): String {
        val cleanRoot = root.trimEnd('/').ifEmpty { "/" }
        if (cleanRoot == "/") return path
        val suffix = path.removePrefix(cleanRoot)
        return suffix.ifEmpty { "/" }
    }

    internal fun joinPath(root: String, relative: String): String {
        val cleanRoot = root.trimEnd('/')
        val cleanRel = relative.trimStart('/')
        return when {
            cleanRel.isEmpty() -> cleanRoot.ifEmpty { "/" }
            cleanRoot.isEmpty() -> "/$cleanRel"
            else -> "$cleanRoot/$cleanRel"
        }
    }

    private fun hasString(args: JsonObject, key: String) =
        (args[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true

    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
}
