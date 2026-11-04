package com.promenar.infinity.domain.tool

import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 完整授权目标使用 JSON 元组编码；显示格式不参与授权比较。 */
object ToolExecutionTargets {
    fun extract(
        tool: ProtocolTool,
        arguments: String,
        resolvedPaths: Map<String, String> = emptyMap(),
        sshIdentities: Map<String, SshExecutionIdentity> = emptyMap(),
        usesLinux: Boolean = false,
    ): String? {
        val args = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?: return null
        val serverId = tool.mcpServerId
        if (serverId != null || tool.sourceId == "mcp" || tool.runtimeToolId.startsWith("mcp:")) {
            if (serverId.isNullOrBlank()) return null
            val prefix = "mcp:$serverId:"
            if (!tool.runtimeToolId.startsWith(prefix)) return null
            val remoteName = tool.runtimeToolId.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
            return tuple("mcp", serverId, remoteName)
        }
        if (tool.function.name == "ssh_session_send") {
            val id = args.string("session_id") ?: return null
            val host = resolvedPaths["ssh_session:$id"]?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            val input = args.string("input") ?: return null
            return sshTarget("process", host, input, sshIdentities)
        }
        if (tool.function.name == "ssh_session_open") {
            val host = args.string("host")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            return sshTarget("process", host, "[PTY]", sshIdentities)
        }
        if (tool.function.name in setOf("sftp_upload", "sftp_download")) {
            val host = args.string("host")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            val remote = args.string("remote_path")?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Character::isISOControl) } ?: return null
            val scope = args.string("local_scope") ?: "workspace"
            val local = if (scope == "workspace") normalizePath(args.string("local_path") ?: return null) else
                args.string("local_path")?.takeIf { it.startsWith('/') && '\u0000' !in it && it.split('/').none { part -> part == "." || part == ".." } }
            if (local == null || scope !in setOf("workspace", "linux")) return null
            if (tool.function.name == "sftp_download" && com.promenar.infinity.data.ssh.isSftpProtectedLocalPath(local)) return null
            val overwrite = (args["overwrite"] as? JsonPrimitive)?.booleanOrNull ?: false
            val target = Json.parseToJsonElement(sshTarget("remote_path", host, remote, sshIdentities) ?: return null) as JsonArray
            val metadata = target.last() as JsonObject
            return JsonArray(target.dropLast(1) + JsonObject(metadata + mapOf(
                "oneShot" to JsonPrimitive(true),
                "direction" to JsonPrimitive(if (tool.function.name == "sftp_upload") "upload" else "download"),
                "local_scope" to JsonPrimitive(scope), "local_path" to JsonPrimitive(local),
                "remote_path" to JsonPrimitive(remote), "host" to JsonPrimitive(host),
                "overwrite" to JsonPrimitive(overwrite),
                "local_identity" to JsonPrimitive(resolvedPaths["sftp:local-identity"] ?: "unresolved"),
            ))).toString()
        }
        if (tool.function.name in setOf("sftp_list", "sftp_read", "sftp_write")) {
            val host = args.string("host")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            val path = args.string("path")?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Character::isISOControl) } ?: return null
            // 远端相对路径和符号链接的语义由服务器决定，授权键保留原始路径。
            return sshTarget("remote_path", host, path, sshIdentities)
        }
        if (tool.function.name in setOf("ssh_host_save", "ssh_host_delete", "ssh_host_install_key")) {
            // 主机管理按别名逐次授权（不可记住，避免同一别名被静默改指向）；安装公钥属于 PROCESS 风险，必须先于通用命令分支处理。
            val alias = args.string("alias")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            return JsonArray(listOf(JsonPrimitive("ssh_host"), JsonPrimitive(alias), JsonObject(mapOf("oneShot" to JsonPrimitive(true))))).toString()
        }
        if (tool.function.name == "job_input" && tool.sourceId == "builtin") {
            val job = args.string("job_id") ?: return null
            val text = args.string("text") ?: return null
            return JsonArray(listOf(JsonPrimitive("process"), JsonPrimitive("linux"),
                JsonPrimitive("$job: $text"), JsonObject(mapOf("oneShot" to JsonPrimitive(true))))).toString()
        }
        if (tool.risk == ToolRisk.PROCESS && tool.function.name == "python_exec" && tool.sourceId == "builtin") {
            val code = args.string("code")?.takeIf(String::isNotBlank) ?: return null
            return tuple("process", if (usesLinux) "linux" else "python", code)
        }
        if (tool.risk == ToolRisk.PROCESS) {
            val host = when (tool.function.name) {
                "termux_exec" -> "termux"
                "shell_exec" -> (if (usesLinux) "linux" else "builtin").takeIf { tool.sourceId == "builtin" }
                else -> null
            } ?: args.string("host")?.trim()?.takeIf(String::isNotBlank) ?: return null
            val command = args.string("command")?.takeIf(String::isNotBlank) ?: return null
            if (tool.function.name == "ssh_exec") {
                if (!SSH_ALIAS.matches(host)) return null
                return sshTarget("process", host, command, sshIdentities)
            }
            return tuple("process", host.lowercase(Locale.ROOT), command)
        }
        val target = when (tool.function.name) {
            "copy_knowledge_to_linux" -> runCatching {
                val source = args.string("source")?.takeIf { it.startsWith("/mnt/") } ?: return null
                val target = com.promenar.infinity.data.workspace.LinuxSessionPaths.normalize(args.string("target")
                    ?: com.promenar.infinity.ui.chat.manager.skills.CopyKnowledgeToLinuxSkill.DEFAULT_TARGET)
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive(target), JsonObject(mapOf("source" to JsonPrimitive(source), "linuxRootfs" to JsonPrimitive(
                    com.promenar.infinity.data.workspace.LinuxSessionPaths.route(target, false, null).kind == com.promenar.infinity.data.workspace.LinuxSessionPathKind.ROOTFS))))).toString()
            }.getOrNull()
            "media_fetch" -> runCatching {
                com.promenar.infinity.domain.media.MediaFetchPaths.targetPath(args.string("path")) ?: "/media"
            }.getOrNull()?.let { tuple("path", it) }
            "read_file", "write_file", "patch_file", "diff_file", "delete_file", "run_script" ->
                entryPath(args, resolvedPaths)?.let { tuple("path", it) }
            "create_file", "create_directory" -> {
                val name = validName(args.string("name")) ?: return null
                val parent = entryPath(args, resolvedPaths, "parentUuid", "parentPath", "/") ?: return null
                childPath(parent, name).takeUnless { it == "/mnt" }?.let { tuple("path", it) }
            }
            "move_file" -> moveTarget(args, resolvedPaths)
            "list_files" -> entryPath(args, resolvedPaths, "parentUuid", "path", "/")
                ?.let { tuple("path", it) }
            "search_files" -> args.string("query")?.takeIf(String::isNotBlank)?.let { tuple("path", "/") }
            "web_fetch" -> webHost(args)?.let { tuple("host", it) }
            "exec_js", "python_sandbox" -> args.string("code")?.let { tuple("tool", tool.function.name) }
            else -> null
        }
        return if (target != null && (requiresSkillsApproval(tool.function.name, target) || tool.function.name == "delete_file")) {
            JsonArray((Json.parseToJsonElement(target) as JsonArray) + JsonObject(mapOf("oneShot" to JsonPrimitive(true)))).toString()
        } else target
    }

    private val SFTP_PREVIEW_FIELDS = setOf("bytes_total", "target_exists", "will_overwrite", "source_revision", "target_revision")

    fun withSftpPreview(target: String, preview: JsonObject): String {
        val parts = Json.parseToJsonElement(target) as JsonArray
        val metadata = parts.last() as JsonObject
        return JsonArray(parts.dropLast(1) + JsonObject(metadata + preview.filterKeys { it in SFTP_PREVIEW_FIELDS })).toString()
    }

    fun withoutSftpPreview(target: String): String {
        val parts = Json.parseToJsonElement(target) as JsonArray
        val metadata = parts.last() as JsonObject
        return JsonArray(parts.dropLast(1) + JsonObject(metadata.filterKeys { it !in SFTP_PREVIEW_FIELDS })).toString()
    }

    fun canRemember(target: String): Boolean = runCatching {
        val metadata = (Json.parseToJsonElement(target) as? JsonArray)?.lastOrNull() as? JsonObject
        (metadata?.get("oneShot") as? JsonPrimitive)?.booleanOrNull != true
    }.getOrDefault(true)

    /** 技能目录及可连带变更项目技能的父目录不接受持久路径授权。 */
    fun isSkillsPathTarget(target: String): Boolean = runCatching {
        val array = Json.parseToJsonElement(target) as? JsonArray ?: return false
        val tuple = if (array.lastOrNull() is JsonObject) array.dropLast(1) else array.toList()
        val kind = (tuple.firstOrNull() as? JsonPrimitive)?.content
        if (kind !in setOf("path", "move") || tuple.size != if (kind == "path") 2 else 3) return false
        tuple.drop(1).any { item ->
            val path = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@any false
            // 共享存储工作区大小写不敏感，项目技能目录的两段按忽略大小写比较；/skills 是精确匹配的虚拟根。
            val scopedPath = com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths.relative(path) ?: path
            val folded = scopedPath.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
            path == "/skills" || path.startsWith("/skills/") ||
                folded == "/.agents" || folded == "/.agents/skills" || folded.startsWith("/.agents/skills/")
        }
    }.getOrDefault(false)

    /** 可能改变工作区或技能目录内容的文件工具。 */
    fun isPathMutation(toolName: String): Boolean = toolName in SKILL_MUTATIONS

    fun requiresSkillsApproval(toolName: String, target: String?): Boolean =
        toolName in SKILL_MUTATIONS && target != null && (isSkillsPathTarget(target) || runCatching {
            val parts = Json.parseToJsonElement(target) as? JsonArray
            val metadata = parts?.lastOrNull() as? JsonObject
            (metadata?.get("linuxSkills") as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false))

    /** 清除不可见控制字符与格式控制后按 UTF-16 长度限制显示，保留完整代理对。 */
    /** 进程目标的执行位置（如 builtin、python、termux）；命令正文另行完整展示时用它代替整条目标，避免重复。 */
    fun processScope(target: String): String? {
        val parts = runCatching { Json.parseToJsonElement(target) as? JsonArray }.getOrNull()
            ?.let { array -> if (array.lastOrNull() is JsonObject) JsonArray(array.dropLast(1)) else array }
            ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null }
            ?: return null
        return parts.takeIf { it.size == 3 && it[0] == "process" }?.get(1)?.let { display(it) }?.takeIf(String::isNotBlank)
    }

    /** 命令目标是否在 Linux 环境执行；审批卡据此区分 Linux 与内置命令的类别和访问范围说明。 */
    fun runsInLinux(target: String?): Boolean = target != null && runCatching {
        val parts = (Json.parseToJsonElement(target) as JsonArray).map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        parts.size == 3 && parts[0] == "process" && parts[1] == "linux"
    }.getOrDefault(false)

    fun display(target: String, maxChars: Int = 240): String {
        if (maxChars <= 0) return ""
        val parts = runCatching { Json.parseToJsonElement(target) as? JsonArray }.getOrNull()
            ?.let { array -> if (array.lastOrNull() is JsonObject) JsonArray(array.dropLast(1)) else array }
            ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@map null }
            ?.takeIf { it.none { part -> part == null } }
            ?.filterNotNull()
            .orEmpty()
        val readable = when (parts.firstOrNull()) {
            "path", "host", "tool", "ssh_host" -> parts.getOrNull(1)?.takeIf { parts.size == 2 }
            "move" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} → ${it[2]}" }
            "mcp" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} / ${it[2]}" }
            "process", "remote_path" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} · ${it[2]}" }
            else -> null
        } ?: target
        // 换行与制表符显示为可见符号，多行命令不会被拼成看似不同的单行；其它控制与双向格式字符剥离。
        val safe = buildString {
            var offset = 0
            while (offset < readable.length) {
                val point = readable.codePointAt(offset)
                when {
                    point == '\n'.code -> append('↵')
                    point == '\r'.code -> if (readable.getOrNull(offset + 1) != '\n') append('↵')
                    point == '\t'.code -> append('⇥')
                    !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt() -> appendCodePoint(point)
                }
                offset += Character.charCount(point)
            }
        }
        if (safe.length <= maxChars) return safe
        val end = (maxChars - 1).let { limit ->
            if (limit > 0 && Character.isHighSurrogate(safe[limit - 1])) limit - 1 else limit
        }
        return safe.substring(0, end) + "…"
    }

    private fun moveTarget(args: JsonObject, resolvedPaths: Map<String, String>): String? {
        val source = entryPath(args, resolvedPaths)?.takeIf { it != "/" } ?: return null
        val newName = args.string("newName")?.trim()?.takeIf(String::isNotEmpty)
        if (newName != null && validName(newName) == null) return null
        val hasParent = !args.string("newParentUuid").isNullOrBlank() || !args.string("newParentPath").isNullOrBlank()
        if (!hasParent && newName == null) return null
        val parent = if (hasParent) {
            entryPath(args, resolvedPaths, "newParentUuid", "newParentPath") ?: return null
        } else {
            source.substringBeforeLast('/').ifEmpty { "/" }
        }
        val destination = childPath(parent, newName ?: source.substringAfterLast('/'))
        if (source == "/mnt" || destination == "/mnt") return null
        return tuple("move", source, destination)
    }

    private fun entryPath(
        args: JsonObject,
        resolvedPaths: Map<String, String>,
        uuidKey: String = "uuid",
        pathKey: String = "path",
        defaultPath: String? = null,
    ): String? {
        // 非字符串参数按缺省处理，与执行层的 stringArgument 一致，避免审批与实际定位不同。
        val uuid = args.string(uuidKey)?.trim()?.takeIf(String::isNotEmpty)
        if (uuid != null) return resolvedPaths[uuid]?.let(::normalizePath)
        val path = args.string(pathKey)?.takeIf(String::isNotBlank) ?: defaultPath ?: return null
        return normalizePath(path)
    }

    private fun normalizePath(raw: String): String? {
        val path = raw.trim()
        if (path.isEmpty() || '\u0000' in path || path.startsWith("\\\\") ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(path)
        ) return null
        val segments = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
        if (segments.any { it == "." || it == ".." || WorkspaceInternalPaths.isReservedName(it) }) return null
        return "/" + segments.joinToString("/")
    }

    private fun validName(raw: String?): String? = raw?.trim()?.takeIf { name ->
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name &&
            !WorkspaceInternalPaths.isReservedName(name)
    }

    private fun webHost(args: JsonObject): String? {
        val raw = args.string("url")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if ("://" in raw && !raw.startsWith("http://") && !raw.startsWith("https://")) return null
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return uri.host?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
    }

    private fun childPath(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun tuple(vararg parts: String): String = JsonArray(parts.map(::JsonPrimitive)).toString()
    private fun sshTarget(kind: String, host: String, value: String, identities: Map<String, SshExecutionIdentity>): String? {
        val identity = identities[host] ?: return null
        if (identity.hostId.isBlank() || identity.digest.isBlank()) return null
        return JsonArray(listOf(JsonPrimitive(kind), JsonPrimitive(host), JsonPrimitive(value), JsonObject(mapOf(
            "sshHostId" to JsonPrimitive(identity.hostId),
            "sshIdentity" to JsonPrimitive(identity.digest),
        )))).toString()
    }
    private val SSH_ALIAS = Regex("[a-z][a-z0-9_-]{0,47}")
    private val SKILL_MUTATIONS = setOf("write_file", "patch_file", "create_file", "create_directory", "move_file", "delete_file", "media_fetch", "copy_knowledge_to_linux", "sftp_download")
}
