package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.tool.ToolArgumentsValidation
import com.promenar.infinity.domain.tool.ToolArgumentsValidator
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess

/** UUID 必须在当前会话工作区内解析；解析失败不产生可记住的授权目标。 */
suspend fun resolveSessionToolTarget(
    session: Session,
    tool: ProtocolTool,
    arguments: String,
    repository: IWorkspaceRepository?,
    mounts: com.promenar.infinity.domain.storage.ExternalWorkspaceAccess? = null,
    sshHostStore: com.promenar.infinity.data.ssh.SshHostStore? = null,
    sshSessionHost: ((String, String) -> String?)? = null,
    linuxFiles: LinuxSessionExternalWorkspaceAccess? = null,
    sftpWorkspaceFiles: com.promenar.infinity.data.workspace.SftpWorkspaceFiles? = null,
    sftpRuntime: com.promenar.infinity.data.ssh.SshRuntime? = null,
    sftpLinuxFiles: com.promenar.infinity.domain.linux.LinuxFileSystem? = null,
    builtinFiles: com.promenar.infinity.ui.chat.manager.skills.BuiltinRealPathWorkspaceAccess? = null,
): String? {
    val args = (ToolArgumentsValidator().validate(arguments) as? ToolArgumentsValidation.Valid)?.arguments ?: return null
    val installedSftp = com.promenar.infinity.ui.chat.manager.skills.InstalledSftpTransferAccess.get(sshHostStore)
    val transferWorkspace = sftpWorkspaceFiles ?: installedSftp?.access?.workspaceFiles
    val transferRuntime = sftpRuntime ?: installedSftp?.runtime
    val transferLinux = sftpLinuxFiles ?: installedSftp?.access?.linux
    val paths = mutableMapOf<String, String>()
    var sftpLocal: com.promenar.infinity.data.ssh.SftpLocalFile? = null
    try {
        val usesLinux = session.runMode == SessionRunMode.LINUX
        val remoteMountOperation = com.promenar.infinity.domain.tool.RemoteMountOperation.fromTool(tool.function.name)
        if (remoteMountOperation != null && tool.sourceId == "builtin" && tool.mcpServerId == null) {
            if (remoteMountOperation == com.promenar.infinity.domain.tool.RemoteMountOperation.STATUS) return null
            val resolver = remoteMountTargetResolver(mounts ?: return null, sshHostStore ?: return null, builtinFiles, linuxFiles)
            val parsed = com.promenar.infinity.domain.tool.RemoteMountArguments.parse(remoteMountOperation, args)
            val binding = resolver.binding(session.id, requireNotNull(parsed.path), remoteMountOperation.writesRemote)
            return resolver.resolve(session.id, session.runMode, tool, remoteMountOperation, args, parsed, binding).target
        }
        if (tool.function.name in setOf("sftp_upload", "sftp_download") && tool.mcpServerId == null && tool.sourceId == "builtin") {
            val scope = (args["local_scope"] as? JsonPrimitive)?.content ?: "workspace"
            if (scope == "linux") {
                if (!usesLinux) return null
                paths["sftp:local-identity"] = "linux:${session.workspaceRootUuid}"
                val path = (args["local_path"] as? JsonPrimitive)?.content ?: return null
                val files = transferLinux ?: return null
                sftpLocal = com.promenar.infinity.data.workspace.SftpLinuxFile(
                    (files as? com.promenar.infinity.data.linux.LinuxFileSystemImpl)?.forAgentTools() ?: files, path)
            } else {
                val path = (args["local_path"] as? JsonPrimitive)?.content ?: return null
                val root = session.workspaceRootUuid ?: return null
                val bound = (transferWorkspace ?: return null).resolve(session.id, root, path, tool.function.name == "sftp_download")
                paths["sftp:local-identity"] = bound.identity
                sftpLocal = bound.file
            }
        }
        if (tool.sourceId == "builtin" && tool.mcpServerId == null && tool.function.name == "copy_knowledge_to_linux")
            return knowledgeLinuxCopyTarget(session, args, repository, mounts)
        if (usesLinux && tool.mcpServerId == null && tool.sourceId == "builtin" &&
            tool.function.name in setOf("read_file", "write_file", "patch_file", "diff_file", "delete_file", "create_file", "create_directory", "move_file", "list_files", "search_files")
        ) return try {
            linuxFiles?.executionTarget(session.id, tool, args)
        } catch (denied: com.promenar.infinity.domain.storage.ExternalFolderException) {
            if (denied.code == com.promenar.infinity.ui.chat.manager.skills.SFTP_USE_REMOTE_MOUNT_TOOL)
                com.promenar.infinity.domain.tool.ToolExecutionPolicy.hardBlockedTarget(denied.code)
            else null
        }
        if (!usesLinux && builtinFiles != null && tool.mcpServerId == null && tool.sourceId == "builtin" &&
            tool.function.name in setOf("read_file", "write_file", "patch_file", "diff_file", "delete_file", "create_file", "create_directory", "move_file", "list_files", "search_files")
        ) return try {
            builtinFiles.executionTarget(session.id, tool, args)
        } catch (denied: com.promenar.infinity.domain.storage.ExternalFolderException) {
            if (denied.code in setOf("ACCESS_DENIED", "PROTECTED_RUNTIME_DATA", com.promenar.infinity.ui.chat.manager.skills.SFTP_USE_REMOTE_MOUNT_TOOL)) {
                com.promenar.infinity.domain.tool.ToolExecutionPolicy.hardBlockedTarget(denied.code)
            } else {
                null
            }
        }
        val visibleMounts = mounts?.visible(session.id).orEmpty()
        // 自选目录工作区以空别名挂载承载 `/`：路径授权绑定工作区身份，切换工作区后旧授权不再适用。
        val workspaceMount = mounts?.workspaceMount(session.id)
        val namespaceMounts = visibleMounts + listOfNotNull(workspaceMount)
        if (tool.function.name == "ssh_session_send") {
            val id = (args["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            sshSessionHost?.invoke("agent:${session.id}", id)?.let { paths["ssh_session:$id"] = it }
        }
        val rootUuid = session.workspaceRootUuid
        listOf("uuid", "parentUuid", "newParentUuid").forEach { key ->
            val uuid = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (!uuid.isNullOrBlank()) {
                if (uuid.startsWith("external:")) com.promenar.infinity.data.storage.ExternalMountPaths.uuidPath(uuid, namespaceMounts)?.let { paths[uuid] = it }
                else if (rootUuid != null && com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths.privateUuid(uuid) != null) {
                    repository?.getByUuid(rootUuid, requireNotNull(com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths.privateUuid(uuid)))
                        ?.materializedPath?.let { paths[uuid] = com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths.virtual(it) }
                }
                else if (workspaceMount == null && rootUuid != null) repository?.getByUuid(rootUuid, uuid)?.materializedPath?.let { paths[uuid] = it }
            }
        }
        val sshIdentities = if (tool.mcpServerId == null && tool.sourceId != "mcp" && !tool.runtimeToolId.startsWith("mcp:") &&
            tool.function.name in setOf("ssh_exec", "ssh_session_open", "ssh_session_send", "sftp_list", "sftp_read", "sftp_write", "sftp_upload", "sftp_download")
        ) {
            val alias = if (tool.function.name == "ssh_session_send") {
                val id = (args["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                paths["ssh_session:$id"]
            } else (args["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (alias == null) return null
            val host = sshHostStore?.find(alias) ?: return null
            mapOf(alias to host.executionIdentity())
        } else emptyMap()
        val baseTarget = ToolExecutionTargets.extract(tool, arguments, paths, sshIdentities, usesLinux = usesLinux) ?: return null
        val target = if (sftpLocal != null) {
            val runtime = transferRuntime ?: return null
            val host = (args["host"] as? JsonPrimitive)?.content ?: return null
            val snapshot = runtime.hostSnapshot(host)
            if (snapshot.executionIdentity() != sshIdentities[host]) return null
            val remote = (args["remote_path"] as? JsonPrimitive)?.content ?: return null
            val overwrite = (args["overwrite"] as? JsonPrimitive)?.content == "true"
            val preview = runtime.withHostSnapshot(snapshot) {
                com.promenar.infinity.ui.chat.manager.skills.sftpApprovalSnapshot(runtime, session.id, host, remote, requireNotNull(sftpLocal),
                    tool.function.name == "sftp_upload", overwrite)
            }
            ToolExecutionTargets.withSftpPreview(baseTarget, preview.fields)
        } else baseTarget
        val defaultMediaPath = tool.function.name == "media_fetch" &&
            com.promenar.infinity.domain.media.MediaFetchPaths.targetPath((args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content) == null
        if (mounts == null && !defaultMediaPath) return target
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(target) as? kotlinx.serialization.json.JsonArray ?: return null
        val metadata = parts.lastOrNull() as? kotlinx.serialization.json.JsonObject
        val tuple = if (metadata != null) parts.dropLast(1) else parts.toList()
        val strings = tuple.map { (it as? JsonPrimitive)?.content ?: return null }
        if (strings.firstOrNull() !in setOf("path", "move")) return target
        val defaultMedia = defaultMediaPath
        val defaultDestination = if (defaultMedia) com.promenar.infinity.data.workspace.defaultWorkspaceFileApprovalTarget(
            workspaceMount?.sourceId ?: session.workspaceId, visibleMounts, namespaceMounts.associate { it.alias to it.id }) else null
        val legacyPaths = paths.filter { (uuid, path) -> !uuid.startsWith("external:") && com.promenar.infinity.data.storage.ExternalMountPaths.isMountedPath(path) }
        val mountIds = strings.drop(1).mapNotNull { path ->
            val parsed = com.promenar.infinity.data.storage.ExternalMountPaths.parse(path)
            if (parsed == null) {
                val defaultPrivateMedia = defaultMedia &&
                    com.promenar.infinity.data.workspace.defaultWorkspaceFileDestination(workspaceMount != null, visibleMounts) ==
                    com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination.SESSION_PRIVATE
                workspaceMount?.takeIf { !defaultPrivateMedia && com.promenar.infinity.data.storage.ExternalMountPaths.aliasOf(path, rooted = true) == com.promenar.infinity.data.storage.ExternalMountPaths.WORKSPACE_ALIAS }
                    ?.let { it.alias to it.id }
            } else parsed.let { (alias, _) ->
                val mountId = visibleMounts.singleOrNull { it.alias == alias }?.id
                if (mountId == null && path !in legacyPaths.values) return null
                mountId?.let { alias to it }
            }
        }.toMap().toSortedMap()
        if (mountIds.isEmpty() && legacyPaths.isEmpty() && defaultDestination == null) return target
        // 授权绑定挂载身份；移除后复用同名别名也不会继承旧授权。
        val frozenDefault = defaultDestination?.let { mapOf("defaultFileDestination" to it) }.orEmpty()
        return kotlinx.serialization.json.JsonArray(tuple + kotlinx.serialization.json.JsonObject(metadata.orEmpty() + frozenDefault + mapOf(
            "mountIds" to kotlinx.serialization.json.JsonObject(mountIds.mapValues { JsonPrimitive(it.value) }),
            "legacyWorkspaceUuids" to kotlinx.serialization.json.JsonArray(legacyPaths.keys.sorted().map(::JsonPrimitive)),
        ))).toString()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { return null }
}
