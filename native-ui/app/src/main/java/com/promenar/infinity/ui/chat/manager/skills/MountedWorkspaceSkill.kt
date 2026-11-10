package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.model.withinHunkBudget
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths
import com.promenar.infinity.data.workspace.SessionPrivateWorkspaceRepository
import com.promenar.infinity.data.workspace.SessionPrivateWorkspaceBackend
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.data.storage.streamHash
import com.promenar.infinity.data.storage.produceExternalStream
import com.promenar.infinity.data.storage.ExternalTreeTransfer
import com.promenar.infinity.data.storage.externalSnapshotMime
import com.promenar.infinity.data.storage.requireWritable
import com.promenar.infinity.domain.repository.*
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.infra.util.MyersDiff
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.diff.FileChangeSummaries
import com.promenar.infinity.ui.chat.diff.FileSnapshot
import com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader
import com.promenar.infinity.ui.chat.diff.WorkspaceSnapshotReader
import com.promenar.infinity.ui.chat.manager.registry.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import com.promenar.infinity.data.storage.namespaceBindings
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 保留现有工具语义，只在仓库适配层增加虚拟挂载命名空间。 */
class MountedWorkspaceSkill(
    private val original: SkillDefinition,
    private val mounts: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
    /** 是否采集界面用的文件改动摘要；脚本网关只取结果文本，关闭以免每次写入多读一次文件。 */
    private val captureFileChanges: Boolean = true,
    private val privateReader: PrivateWorkspaceReader = PrivateWorkspaceReader.Default,
    private val linuxPaths: Boolean = false,
) : SkillDefinition by original {
    override val description = original.description + " 支持用户级技能 /skills/... 与 /mnt/<别名>/... 挂载路径。" + when (original.id) {
        "delete_file" -> " 删除文件时 expectedHash 或 expected_sha256 为可选版本校验，取自 read_file 的 Hash 或 sha256；目录不支持文件版本参数。"
        "move_file" -> " 涉及挂载路径的来源文件可提供 expectedHash 或 expected_sha256（取自 read_file 的 Hash 或 sha256）校验来源版本；私有工作区移动不校验内容版本。"
        else -> ""
    }
    override val parametersSchema: String = run {
        val schema = Json.parseToJsonElement(original.parametersSchema).jsonObject
        val properties = schema["properties"]?.jsonObject.orEmpty().toMutableMap()
        if (original.id in setOf("write_file", "patch_file", "delete_file", "move_file")) {
            val versionDescription = if (original.id in setOf("write_file", "patch_file")) WORKSPACE_VERSION_REQUIREMENT
                else if (original.id == "delete_file") "删除文件的可选版本校验，取自 read_file 的 Hash 或 sha256；目录不支持文件版本参数"
                else "涉及挂载路径的来源文件可选版本校验，取自 read_file 的 Hash 或 sha256；私有工作区移动不校验内容版本"
            for (parameter in listOf("expectedHash", "expected_sha256")) {
                properties[parameter] = buildJsonObject { put("type", "string"); put("description", versionDescription) }
            }
        }
        JsonObject(schema.toMutableMap().apply {
            put("properties", JsonObject(properties))
            (schema["required"] as? JsonArray)?.let { put("required", JsonArray(it.filterNot { item -> item.jsonPrimitive.content == "expectedHash" })) }
        }).toString()
    }

    override suspend fun execute(args: JsonObject, invocation: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            // 自选工作区以空别名挂载承载 `/`；私有树通过 `/session` 与专用 UUID 命名空间访问。
            val workspaceMount = mounts.workspaceMount(invocation.sessionId)
            val rooted = workspaceMount != null
            val privateInvocation = !linuxPaths && listOf("path", "parentPath", "newParentPath").any { key -> args.stringArgument(key)?.let(SessionPrivateWorkspacePaths::contains) == true } ||
                !linuxPaths && listOf("uuid", "parentUuid", "newParentUuid").any { key -> args.stringArgument(key)?.let(SessionPrivateWorkspacePaths::privateUuid) != null }
            val privateRoot = if (privateInvocation) workspace.ensureSessionRoot(invocation.sessionId) else null
            val linuxRootUuid = if (linuxPaths && mounts is LinuxSessionExternalWorkspaceAccess) mounts.toolRootUuid(invocation.sessionId) else null
            val context = if (workspaceMount == null && privateRoot == null) invocation else object : SkillExecutionContext by invocation {
                override val workspaceRootUuid = privateRoot?.uuid ?: linuxRootUuid ?: ExternalMountPaths.uuid(requireNotNull(workspaceMount), "/")
            }
            fun String.external() = (linuxPaths || !SessionPrivateWorkspacePaths.contains(this)) && (rooted || isMounted())
            fun aliasOf(path: String) = if (linuxPaths) com.promenar.infinity.data.workspace.LinuxSessionPaths.aliasOf(path) else ExternalMountPaths.aliasOf(path, rooted)
            val visibleMountIds = mounts.visible(context.sessionId).associate { it.alias to it.id } +
                listOfNotNull(workspaceMount).associate { it.alias to it.id }
            val approvedMountIds = context.approvedExecutionTarget?.let {
                (Json.parseToJsonElement(it) as? JsonArray)?.lastOrNull() as? JsonObject
            }?.get("mountIds")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            if (approvedMountIds.any { (alias, id) -> visibleMountIds[alias] != id }) {
                fail("CONFLICT", "批准的挂载身份已失效，请重新审批")
            }
            withContext(ExternalMountOperationBindings(visibleMountIds)) bound@ {
                val normalized = args.toMutableMap()
                args.stringArgument("expected_sha256")?.let { hash ->
                    if (args.stringArgument("expectedHash")?.let { it != hash } == true) fail("CONFLICT", "两个版本参数不一致")
                    normalized["expectedHash"] = JsonPrimitive(hash)
                }
                val routedArgs = JsonObject(normalized)
                val repository = MountedWorkspaceRepository(workspace, mounts, context.sessionId, rooted, linuxPaths)
                val operations = MountedFileOperations(files, mounts, context.sessionId, rooted, privateRoot?.uuid)
                val sourcePath = pathArgument(routedArgs, context, repository, "uuid", "path")
                // 根工作区的新建默认落在 `/`，与挂载目录同样走外部写入而不是私有工作区的 Room 事务。
                val parentPath = pathArgument(routedArgs, context, repository, "parentUuid", "parentPath")
                    ?: "/".takeIf { rooted && original.id in setOf("create_file", "create_directory") }
                val destinationParent = pathArgument(routedArgs, context, repository, "newParentUuid", "newParentPath")
                val sourceMounted = sourcePath?.let { (linuxPaths || !SessionPrivateWorkspacePaths.contains(it)) && rooted } == true || (args.stringArgument("uuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (sourcePath?.isMounted() == true))
                val parentMounted = parentPath?.let { (linuxPaths || !SessionPrivateWorkspacePaths.contains(it)) && rooted } == true || (args.stringArgument("parentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (parentPath?.isMounted() == true))
                val destinationMounted = destinationParent?.let { (linuxPaths || !SessionPrivateWorkspacePaths.contains(it)) && rooted } == true || (args.stringArgument("newParentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (destinationParent?.isMounted() == true || destinationParent == null && sourceMounted))
                val resolvedPaths = listOf("uuid" to sourcePath, "parentUuid" to parentPath, "newParentUuid" to destinationParent)
                    .mapNotNull { (key, path) -> routedArgs.stringArgument(key)?.trim()?.takeIf { it.isNotEmpty() && path != null }?.let { it to path!! } }.toMap()
                val currentTarget = if (linuxPaths && mounts is LinuxSessionExternalWorkspaceAccess)
                    mounts.executionTarget(context.sessionId, original.toProtocolTool(), routedArgs)
                else ToolExecutionTargets.extract(original.toProtocolTool(), routedArgs.toString(), resolvedPaths)
                // 审批侧无法给出目标的变更（如路径写法与审批规范化不一致）不执行，避免批准内容与实际写入位置不同。
                if (ToolExecutionTargets.isPathMutation(original.id) && currentTarget == null) {
                    fail("INVALID_PATH", "无法确定变更的目标路径，请使用以 / 开头的规范路径重新发起")
                }
                if (ToolExecutionTargets.requiresSkillsApproval(original.id, currentTarget)) {
                    fun tuple(target: String?) = target?.let { Json.parseToJsonElement(it).jsonArray }
                        ?.let { if (it.lastOrNull() is JsonObject) JsonArray(it.dropLast(1)) else it }
                    if (!context.isApprovalBoundExecution || tuple(context.approvedExecutionTarget) != tuple(currentTarget)) {
                        fail("APPROVAL_REQUIRED", "技能目录变更需要按当前路径逐次审批，请重新发起操作")
                    }
                }
                if (context.isApprovalBoundExecution || context.approvedExecutionTarget != null) {
                    val usedAliases = listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted })
                        .mapNotNull(::aliasOf)
                    if (usedAliases.any { it !in approvedMountIds }) fail("CONFLICT", "批准目标缺少挂载或工作区身份，请重新审批")
                }
                if (ToolExecutionTargets.isPathMutation(original.id)) {
                    val writeMounts = mounts.visible(context.sessionId) + listOfNotNull(mounts.workspaceMount(context.sessionId))
                    listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted })
                        .filter { it.external() }.mapNotNull(::aliasOf).distinct().forEach { alias ->
                            writeMounts.singleOrNull { it.alias == alias }?.requireWritable()
                        }
                }
                if (original.id in setOf("write_file", "patch_file")) {
                    workspaceVersionError(original.id, routedArgs)?.let { return@bound it }
                }
                // 改动摘要只用于界面展示：写入前快照在执行前读取，失败不影响工具本身。
                val capture = if (!captureFileChanges) null else FileChangeCapture.begin(
                    toolId = original.id,
                    args = routedArgs,
                    reader = WorkspaceSnapshotReader(repository, workspace, mounts, context.sessionId, context.workspaceRootUuid, rooted, privateReader = privateReader),
                    sourcePath = sourcePath,
                    parentPath = parentPath,
                    destinationParent = destinationParent,
                )
                suspend fun finish(result: ToolResult): ToolResult = capture?.finish(result) ?: result
                if (linuxPaths) {
                    if (!linuxPaths && original.id in setOf("create_file", "create_directory") && (parentPath ?: "/") == "/" && args.stringArgument("name")?.trim() in setOf("mnt", "skills")) {
                        fail("INVALID_PATH", "/mnt 与 /skills 为保留虚拟目录")
                    }
                    if (original.id in setOf("move_file", "delete_file") && sourceMounted && sourcePath in (if (linuxPaths) setOf("/mnt") else setOf("/mnt", "/skills"))) fail("INVALID_PATH", "不能变更保留虚拟根目录")
                } else {
                    if (original.id in setOf("create_file", "create_directory") && (parentPath ?: "/") == "/" && args.stringArgument("name")?.trim() in setOf("mnt", "skills", "session")) {
                        fail("INVALID_PATH", "/mnt、/skills 与 /session 为保留虚拟目录")
                    }
                    if (original.id in setOf("move_file", "delete_file") && sourcePath == "/session") fail("INVALID_PATH", "不能变更会话专用空间根目录")
                    if (original.id in setOf("move_file", "delete_file") && sourceMounted && sourcePath in setOf("/mnt", "/skills")) fail("INVALID_PATH", "不能变更保留虚拟根目录")
                }
                if (original.id in setOf("move_file", "delete_file") && rooted && sourcePath == "/") fail("INVALID_PATH", "不能移动或删除工作区根目录")
                if (original.id == "create_file" && parentMounted && parentPath != null) {
                    val name = args.stringArgument("name")?.trim() ?: fail("INVALID_PATH", "缺少文件名")
                    ExternalFolderPaths.validateName(name)
                    val path = parentPath.trimEnd('/') + "/" + name
                    val bytes = args.stringArgument("content").orEmpty().toByteArray(Charsets.UTF_8)
                    WorkspaceTextContentPolicy().validate(name, (mounts as? LinuxSessionExternalWorkspaceAccess)?.textMime(context.sessionId, path), bytes, WorkspaceTextOperation.WRITE)
                    val created = mounts.write(context.sessionId, path, bytes, null, true)
                    return@bound finish(ToolResult("mount_create", "已创建 $path [uuid=${created.uuid}] [sha256=${created.hash}]"))
                }
                if (original.id == "delete_file" && sourceMounted && sourcePath != null) {
                    mounts.delete(context.sessionId, sourcePath, routedArgs.stringArgument("expectedHash"))
                    return@bound finish(ToolResult("mount_delete", "已永久删除：$sourcePath"))
                }
                if (original.id == "move_file") {
                    val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
                    val source = sourcePath
                    if (source != null) {
                        val destination = (destinationParent ?: source.substringBeforeLast('/').ifEmpty { "/" }).trimEnd('/') + "/" +
                            (newName ?: source.substringAfterLast('/'))
                        if (destination in (if (linuxPaths) setOf("/mnt") else setOf("/mnt", "/skills", "/session"))) fail("INVALID_PATH", "不能覆盖保留虚拟根目录")
                        if (sourceMounted || destinationMounted) {
                            if (newName == null && destinationParent == null) fail("INVALID_PATH", "需要指定新名称或目标目录")
                            return@bound finish(move(context, source, destination, routedArgs.stringArgument("expectedHash"), sourceMounted, destinationMounted))
                        }
                    }
                }
                listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted }).filter { it.external() }.forEach {
                    if (it != "/mnt") mounts.entry(context.sessionId, it)
                }
                val delegate = when (original.id) {
                    "read_file" -> FileReadSkill(operations, repository)
                    "write_file" -> FileWriteSkill(operations, repository)
                    "patch_file" -> FilePatchSkill(operations, repository)
                    "diff_file" -> FileDiffSkill(operations, repository)
                    // Linux 目录列表已按可信根校验子路径，不再把登记根名称当作工具保留路径。
                    "list_files" -> if (linuxPaths) FileListSkill(repository, { false }) else FileListSkill(repository)
                    "search_files" -> if (linuxPaths) FileSearchSkill(repository, operations, { false }) else FileSearchSkill(repository, operations)
                    "create_file" -> CreateFileSkill(repository, operations)
                    "create_directory" -> CreateDirectorySkill(repository)
                    "move_file" -> MoveFileSkill(repository, indexEvents)
                    "delete_file" -> DeleteFileSkill(repository)
                    else -> original
                }
                val result = delegate.execute(routedArgs, context)
                finish(if (sourceMounted && sourcePath != null && result.status != "error" && original.id in setOf("read_file", "write_file", "patch_file", "diff_file")) {
                    val hash = operations.completedHash.orEmpty()
                    val workspaceFile = aliasOf(sourcePath) == ExternalMountPaths.WORKSPACE_ALIAS
                    if (original.id in setOf("write_file", "patch_file")) ToolResult(result.id,
                        if (workspaceFile) "已保存：$sourcePath\nsha256: $hash"
                        else "外部文件已保存：$sourcePath\nsha256: $hash\n资料快照可在文件面板重新导入。")
                    else result.copy(content = result.content + "\nsha256: $hash")
                } else result)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ExternalFolderException) {
            ToolResult("mount_error", "${failure.code}：${failure.message}", "error")
        } catch (failure: WorkspaceTextPolicyException) { workspaceTextFailureResult(original.id, failure) }
        catch (failure: SecurityException) { workspaceMutationFailureResult(original.id, failure) }
        catch (failure: Exception) { workspaceMutationFailureResult(original.id, failure) }
    }

    private suspend fun move(context: SkillExecutionContext, source: String, destination: String, expected: String?, sourceMounted: Boolean, destinationMounted: Boolean): ToolResult {
        val linux = (mounts as? LinuxSessionExternalWorkspaceAccess)?.takeIf { linuxPaths }
        if (linux != null) { linux.validatePath(context.sessionId, source); linux.validatePath(context.sessionId, destination) }
        else { ExternalFolderPaths.normalize(source); ExternalFolderPaths.normalize(destination) }
        (mounts as? LinuxSessionExternalWorkspaceAccess)?.movePrivate(context.sessionId, source, destination, expected)?.let { return it }
        if (destination != source && destination.startsWith(source.trimEnd('/') + "/")) fail("INVALID_PATH", "不能移动到子目录")
        val privateBackend = PrivateWorkspaceTransferBackend(workspace, context.workspaceRootUuid, indexEvents, privateReader)
        val bindings = kotlinx.coroutines.currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
            ?: mounts.namespaceBindings(context.sessionId)
        val rooted = ExternalMountPaths.WORKSPACE_ALIAS in bindings
        listOfNotNull(source.takeIf { sourceMounted }, destination.takeIf { destinationMounted }).forEach { path ->
            val alias = if (linuxPaths) com.promenar.infinity.data.workspace.LinuxSessionPaths.aliasOf(path) else ExternalMountPaths.aliasOf(path, rooted)
            if (alias !in bindings) fail("NOT_FOUND", "移动涉及的挂载不可见")
            (mounts.visible(context.sessionId) + listOfNotNull(mounts.workspaceMount(context.sessionId)))
                .singleOrNull { it.alias == alias }?.requireWritable()
        }
        val externalBackend = mounts.backend(context.sessionId, bindings)
        val privateNamespace = SessionPrivateWorkspaceBackend(privateBackend)
        val sourceBackend = if (sourceMounted) externalBackend else privateNamespace
        val targetBackend = if (destinationMounted) externalBackend else privateNamespace
        val sameMount = sourceMounted && destinationMounted && if (linux != null) {
            linux.sameMountedBackend(context.sessionId, source, destination)
        } else {
            val sourceAlias = ExternalMountPaths.aliasOf(source, rooted)
            val targetAlias = ExternalMountPaths.aliasOf(destination, rooted)
            sourceAlias != null && targetAlias != null && bindings[sourceAlias] != null && bindings[sourceAlias] == bindings[targetAlias]
        }
        if (expected != null) {
            val hash = sourceBackend.readStream(source, Long.MAX_VALUE) { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (hash != expected) fail("CONFLICT", "expected_sha256 与来源不一致")
        }
        if (destination == source) {
            sourceBackend.stat(source) ?: fail("NOT_FOUND", "源条目不存在")
            return ToolResult("mount_move", "目标路径未变化：$source")
        }
        // 摘要校验到移动之间仍有短暂竞态；后端在实际移动前会再次核对来源身份。
        val nativeMoved = try {
            if (!sameMount) false
            else if (linux != null) linux.moveMounted(context.sessionId, source, destination) != null
            else { externalBackend.move(source, destination); true }
        } catch (failure: ExternalFolderException) {
            if (failure.code != "UNSUPPORTED") throw failure
            false
        }
        if (nativeMoved) return ToolResult("mount_move", "已移动（原位重命名） / Moved (in-place rename)：$source → $destination")
        try {
            if (linux != null) {
                // 传输只校验根下名称；实际路径已由 Linux 命名空间核验，每次 IO 仍通过原安全后端。
                try {
                    ExternalTreeTransfer.move(LinuxToolTransferBackend(sourceBackend, source), "/item", LinuxToolTransferBackend(targetBackend, destination), "/item", expected)
                } catch (failure: ExternalFolderException) {
                    if (failure.code == "RECOVERY_REQUIRED") throw ExternalFolderException(failure.code,
                        "移动补偿无法安全完成，请核查来源 $source 与目标 $destination / Check source and destination").apply { initCause(failure) }
                    throw failure
                }
            } else ExternalTreeTransfer.move(sourceBackend, source, targetBackend, destination, expected)
        } catch (failure: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) {
                try { privateBackend.confirmCreated() } catch (confirmation: Exception) { failure.addSuppressed(confirmation) }
            }
            throw failure
        }
        val indexed = privateBackend.confirmCreated()
        return ToolResult("mount_move", "已复制、校验并移动 / Copied, verified and moved：$source → $destination" + if (indexed) "" else "；文件已保存，索引等待重试")
    }

    private suspend fun pathArgument(args: JsonObject, context: SkillExecutionContext, repository: IWorkspaceRepository, uuidKey: String, pathKey: String): String? {
        val uuid = args.stringArgument(uuidKey)?.trim()?.takeIf(String::isNotEmpty)
        return if (uuid != null) repository.getByUuid(context.workspaceRootUuid, uuid)?.materializedPath
        else args.stringArgument(pathKey)?.takeIf(String::isNotBlank)?.let {
            if (linuxPaths && mounts is LinuxSessionExternalWorkspaceAccess) mounts.validatePath(context.sessionId, it)
            else normalizeWorkspaceToolPath(it)
        }
    }
}

internal class LinuxToolTransferBackend(private val original: ExternalFolderBackend, private val root: String) : ExternalFolderBackend {
    private fun actual(path: String): String {
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized != "/item" && !normalized.startsWith("/item/")) fail("INVALID_PATH", "移动传输路径越界")
        return root + normalized.removePrefix("/item")
    }
    override suspend fun stat(path: String) = original.stat(actual(path))?.copy(path = path)
    override suspend fun list(path: String) = original.list(actual(path)).map { it.copy(path = path.trimEnd('/') + "/" + it.name) }
    override suspend fun read(path: String, maxBytes: Long) = original.read(actual(path), maxBytes)
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
        original.readStream(actual(path), maxBytes, consume)
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit) =
        original.writeStream(actual(path), expectedSize, createOnly, produce).copy(path = path)
    override suspend fun availableBytes(path: String) = original.availableBytes(if (path == "/") root.substringBeforeLast('/').ifEmpty { "/" } else actual(path))
    override suspend fun validateTree(path: String) = original.validateTree(actual(path))
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
        original.write(actual(path), content, expectedSha256, createOnly).copy(path = path)
    override suspend fun createDirectory(path: String) = original.createDirectory(actual(path)).copy(path = path)
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) =
        original.deleteForTransfer(actual(path), expected.copy(path = actual(path)), expectedSha256)
    override suspend fun delete(path: String, expectedSha256: String?) = original.delete(actual(path), expectedSha256)
}

private fun String.isMounted() = ExternalMountPaths.isMountedPath(this)
private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)

/**
 * 私有工作区仓库加挂载命名空间。[rooted] 时 `/` 属于自选工作区，私有树需显式 `/session` 或专用 UUID。
 */
internal class MountedWorkspaceRepository(
    private val base: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
    private val rooted: Boolean = false,
    private val linuxPaths: Boolean = false,
) : IWorkspaceRepository by base {
    private val privateNamespace = SessionPrivateWorkspaceRepository(base, session)
    private fun String.external() = (linuxPaths || !SessionPrivateWorkspacePaths.contains(this)) && (rooted || isMounted())
    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.getByUuid(workspaceRootUuid, uuid)
        else if (uuid.startsWith("external:")) mounts.resolveUuid(session, uuid)?.let {
            mounts.entry(session, it)?.takeIf { entry -> entry.uuid == uuid }?.copy(workspaceRootUuid = workspaceRootUuid)
        }
        else if (rooted) null
        else base.getByUuid(workspaceRootUuid, uuid)
    override suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry? =
        if (!linuxPaths && SessionPrivateWorkspacePaths.contains(materializedPath)) privateNamespace.getByMaterializedPath(workspaceRootUuid, materializedPath)
        else if (materializedPath.external()) mounts.entry(session, materializedPath)?.copy(workspaceRootUuid = workspaceRootUuid)
        else base.getByMaterializedPath(workspaceRootUuid, materializedPath)
    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> {
        if (SessionPrivateWorkspacePaths.privateUuid(parentUuid) != null) return privateNamespace.observeChildren(workspaceRootUuid, parentUuid)
        if (parentUuid.startsWith("external:")) return flow {
            val path = mounts.resolveUuid(session, parentUuid) ?: fail("NOT_FOUND", "挂载条目不可见")
            val children = mounts.list(session, path).mapNotNull { mounts.entry(session, it.path)?.copy(workspaceRootUuid = workspaceRootUuid) }
            emit(children + if (rooted && path == "/") virtualRoots(workspaceRootUuid) else emptyList())
        }
        if (rooted) return flowOf(emptyList())
        val children = base.observeChildren(workspaceRootUuid, parentUuid)
        if (parentUuid != workspaceRootUuid) return children
        return combine(children, mounts.observeMounts(session)) { entries, _ ->
            entries.filterNot { it.name == "skills" } + virtualRoots(workspaceRootUuid)
        }
    }
    private suspend fun virtualRoots(workspaceRootUuid: String): List<FileEntry> {
        if (linuxPaths) return emptyList()
        val visible = mounts.visible(session)
        return listOfNotNull(
            "/mnt".takeIf { visible.any { !it.isUserSkills } },
            "/skills".takeIf { visible.any { it.isUserSkills } },
        ).mapNotNull { mounts.entry(session, it)?.copy(workspaceRootUuid = workspaceRootUuid) }
    }
    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> = flow {
        val matches = if (rooted) mutableListOf() else base.searchByName(workspaceRootUuid, query).first().toMutableList()
        // 自选目录不建索引，按目录逐层遍历；`.git` 内部对象不参与名称搜索。
        val limit = if (rooted) 5_000 else 500
        val linuxCwd = (mounts as? LinuxSessionExternalWorkspaceAccess)?.takeIf { linuxPaths }?.workingDirectory(session)
        val pending = ArrayDeque(if (linuxPaths) listOfNotNull(linuxCwd, "/workspace", "/home/session", "/mnt", "/") else if (rooted) listOf("/", "/mnt", "/skills") else listOf("/mnt", "/skills"))
        val visited = mutableSetOf<String>()
        var count = 0
        while (pending.isNotEmpty() && count < limit && matches.size < 100) {
            val directory = pending.removeFirst()
            if (!visited.add(directory)) continue
            mounts.list(session, directory).forEach { entry ->
                count++
                if (entry.name.contains(query, true)) mounts.entry(session, entry.path)?.let { matches.add(it.copy(workspaceRootUuid = workspaceRootUuid)) }
                if (entry.isDirectory && entry.name != ".git" && count < limit) pending.add(entry.path)
            }
        }
        emit(matches)
    }
    override suspend fun createFileInWorkspace(workspaceRootUuid: String, uuid: String, name: String, content: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (!linuxPaths && SessionPrivateWorkspacePaths.contains(materializedPath)) privateNamespace.createFileInWorkspace(workspaceRootUuid, uuid, name, content, parentUuid, materializedPath)
        else if (rooted || (parentUuid?.startsWith("external:") ?: materializedPath.isMounted())) mounts.write(session, materializedPath, content.toByteArray(Charsets.UTF_8), null, true)
        else base.createFileInWorkspace(workspaceRootUuid, uuid, name, content, parentUuid, materializedPath)
    override suspend fun createDirectoryInWorkspace(workspaceRootUuid: String, uuid: String, name: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (!linuxPaths && SessionPrivateWorkspacePaths.contains(materializedPath)) privateNamespace.createDirectoryInWorkspace(workspaceRootUuid, uuid, name, parentUuid, materializedPath)
        else if (rooted || (parentUuid?.startsWith("external:") ?: materializedPath.isMounted())) mounts.createDirectory(session, materializedPath)
        else base.createDirectoryInWorkspace(workspaceRootUuid, uuid, name, parentUuid, materializedPath)
    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.permanentDelete(workspaceRootUuid, uuid) else base.permanentDelete(workspaceRootUuid, uuid)
    override suspend fun permanentDeleteIfHash(workspaceRootUuid: String, uuid: String, expectedSha256: String) =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.permanentDeleteIfHash(workspaceRootUuid, uuid, expectedSha256) else base.permanentDeleteIfHash(workspaceRootUuid, uuid, expectedSha256)
    override suspend fun permanentDeleteEmptyDirectory(workspaceRootUuid: String, uuid: String) =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.permanentDeleteEmptyDirectory(workspaceRootUuid, uuid) else base.permanentDeleteEmptyDirectory(workspaceRootUuid, uuid)
    override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String, expectedName: String?) =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.rename(workspaceRootUuid, uuid, newName, expectedName) else base.rename(workspaceRootUuid, uuid, newName, expectedName)
    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        if (SessionPrivateWorkspacePaths.privateUuid(uuid) != null) privateNamespace.updateParent(workspaceRootUuid, uuid, newParentUuid) else base.updateParent(workspaceRootUuid, uuid, newParentUuid)
}

private class MountedFileOperations(
    private val base: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
    private val rooted: Boolean,
    private val privateRootUuid: String? = null,
) : IFileOperationRepository {
    /** 根工作区时私有工作区的 uuid 不可达。 */
    private fun isPrivate(uuid: String) = !uuid.startsWith("external:") && (!rooted || privateRootUuid != null || fail("NOT_FOUND", "条目不在当前工作区，请用 list_files 获取最新路径"))
    private fun privateUuid(uuid: String) = SessionPrivateWorkspacePaths.privateUuid(uuid) ?: uuid
    private fun privateRoot(root: String) = privateRootUuid ?: root
    var completedHash: String? = null
        private set
    private suspend fun path(uuid: String) = mounts.resolveUuid(session, uuid) ?: fail("NOT_FOUND", "挂载条目不可见")
    private suspend fun text(uuid: String, operation: WorkspaceTextOperation): Pair<String, ByteArray> {
        val path = path(uuid)
        val bytes = mounts.read(session, path, WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes)
        val text = WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), textMime(path), bytes, operation).text
        return text to bytes
    }
    override suspend fun readFileRange(workspaceRootUuid: String, uuid: String, startLine: Int?, endLine: Int?): ReadResult {
        if (isPrivate(uuid)) return base.readFileRange(privateRoot(workspaceRootUuid), privateUuid(uuid), startLine, endLine).copy(uuid = uuid)
        val (content, bytes) = text(uuid, WorkspaceTextOperation.READ)
        val lines = workspaceTextLines(content)
        val start = (startLine ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
        val end = (endLine ?: lines.size).coerceIn(1, lines.size.coerceAtLeast(1))
        val selected = if (lines.isEmpty()) "" else lines.subList(start - 1, end).joinToString("\n")
        completedHash = Sha256Utils.hash(bytes)
        return ReadResult(uuid, path(uuid).substringAfterLast('/'), lines.size, start, end, selected, completedHash!!, mounts.entry(session, path(uuid))!!.updatedAt)
    }
    override suspend fun readFileWindow(workspaceRootUuid: String, uuid: String, request: ReadWindowRequest): ReadWindowResult {
        if (isPrivate(uuid)) return base.readFileWindow(privateRoot(workspaceRootUuid), privateUuid(uuid), request).copy(uuid = uuid)
        val path = path(uuid)
        val name = path.substringAfterLast('/')
        WorkspaceTextContentPolicy().requireSupportedType(name, textMime(path))
        val entry = mounts.entry(session, path) ?: fail("NOT_FOUND", "挂载条目不可见")
        if (entry.sizeBytes > ReadWindowRequest.MAX_FILE_BYTES) throw workspaceTextWindowTooLarge()
        val smallLimit = WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes
        // 小文件整读会登记版本快照，diff_file 才能以这次读到的 sha256 作基准；大文件流式扫描，不登记快照。
        val small = if (entry.sizeBytes > smallLimit) null else try {
            mounts.read(session, path, smallLimit)
        } catch (failure: ExternalFolderException) {
            if (failure.code != "TOO_LARGE") throw failure
            null
        }
        val scan = if (small != null) {
            scanWorkspaceTextWindow(small.inputStream(), request, computeSha256 = true)
        } else try {
            mounts.readStream(session, path, ReadWindowRequest.MAX_FILE_BYTES) { input ->
                scanWorkspaceTextWindow(input, request, computeSha256 = true)
            }
        } catch (failure: ExternalFolderException) {
            if (failure.code == "TOO_LARGE") throw workspaceTextWindowTooLarge()
            throw failure
        }
        val hash = requireNotNull(scan.sha256)
        completedHash = hash
        return scan.toResult(uuid, name, hash, entry.updatedAt)
    }
    override suspend fun writeFileAtomic(workspaceRootUuid: String, uuid: String, newContent: String, sessionId: String, expectedHash: String): WriteResult {
        if (isPrivate(uuid)) return base.writeFileAtomic(privateRoot(workspaceRootUuid), privateUuid(uuid), newContent, sessionId, expectedHash)
        val path = path(uuid)
        val bytes = newContent.toByteArray(Charsets.UTF_8)
        WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), textMime(path), bytes, WorkspaceTextOperation.WRITE)
        completedHash = mounts.write(session, path, bytes, expectedHash, false).hash
        return WriteResult.Success(completedHash!!)
    }
    override suspend fun diffFile(workspaceRootUuid: String, uuid: String, basisHash: String?): DiffResult {
        if (isPrivate(uuid)) return base.diffFile(privateRoot(workspaceRootUuid), privateUuid(uuid), basisHash).copy(uuid = uuid)
        (mounts as? LinuxSessionExternalWorkspaceAccess)?.diffPrivate(session, uuid, basisHash)?.let {
            completedHash = it.currentHash
            return it
        }
        val (current, bytes) = text(uuid, WorkspaceTextOperation.DIFF)
        val hash = Sha256Utils.hash(bytes)
        completedHash = hash
        val basis = if (basisHash == null || basisHash == hash) current else {
            val original = mounts.version(session, path(uuid), basisHash) ?: fail("NOT_FOUND", "基准快照已过期，请重新读取当前文件")
            WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), textMime(path(uuid)), original, WorkspaceTextOperation.DIFF).text
        }
        return DiffResult(uuid, basisHash ?: hash, hash, MyersDiff.computeHunks(workspaceTextLines(basis), workspaceTextLines(current)).map {
            DiffHunk(it.oldStart, it.oldCount, it.newStart, it.newCount, it.lines.map { line -> DiffLine(line.type, line.content) })
        })
    }
    override suspend fun patchFile(workspaceRootUuid: String, uuid: String, operations: List<PatchOperation>, expectedHash: String): PatchResult {
        if (isPrivate(uuid)) return base.patchFile(privateRoot(workspaceRootUuid), privateUuid(uuid), operations, expectedHash)
        val (content, bytes) = text(uuid, WorkspaceTextOperation.PATCH)
        if (Sha256Utils.hash(bytes) != expectedHash) fail("CONFLICT", "expected_sha256 与当前文件不一致")
        val patched = when (val patch = applyWorkspaceLinePatch(content, operations, uuid)) {
            is WorkspaceLinePatchResult.Applied -> patch.content
            is WorkspaceLinePatchResult.Rejected -> {
                val code = when (patch.error.code) {
                    "LINE_OUT_OF_RANGE", "INVALID_ACTION" -> "INVALID_PATCH"
                    "CONTEXT_MISMATCH" -> "CONFLICT"
                    else -> patch.error.code
                }
                fail(code, patch.error.message)
            }
        }
        val newBytes = patched.toByteArray(Charsets.UTF_8)
        WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), textMime(path(uuid)), newBytes, WorkspaceTextOperation.PATCH)
        val updated = mounts.write(session, path(uuid), newBytes, expectedHash, false)
        completedHash = updated.hash
        return PatchResult.Success(updated.hash, operations.size)
    }
    private suspend fun textMime(path: String) = (mounts as? LinuxSessionExternalWorkspaceAccess)?.textMime(session, path)
}

/** 私有工作区通过现有仓库创建、删除和确认，读取继续使用安全文件操作与根身份。 */
internal class PrivateWorkspaceTransferBackend(
    private val workspace: IWorkspaceRepository,
    private val rootUuid: String,
    private val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
    private val privateReader: PrivateWorkspaceReader? = null,
) : ExternalFolderBackend {
    private val created = mutableListOf<String>()
    private suspend fun find(path: String) = workspace.getByMaterializedPath(rootUuid, ExternalFolderPaths.normalize(path))
    override suspend fun stat(path: String) = find(path)?.let { ExternalFolderEntry(path, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash, identity = it.uuid) }
    override suspend fun list(path: String) = workspace.observeChildren(rootUuid, find(path)?.uuid ?: fail("NOT_FOUND", "目录不存在")).first()
        .filterNot(FileEntry::isSystemEntry).map { ExternalFolderEntry(it.materializedPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash) }
    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val entry = find(path) ?: fail("NOT_FOUND", "文件不存在")
        if (entry.isDirectory) fail("IS_DIRECTORY", "目标为目录")
        privateReader?.let { return it.read(workspace, rootUuid, path, maxBytes) }
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("NOT_FOUND", "工作区不可用")
        return WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            SecureWorkspaceFileOps().readLimited(File(root.physicalRootPath).toPath(), ExternalFolderPaths.normalize(path).trim('/').split('/'), maxBytes)
        }
    }
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T {
        privateReader?.let { return it.readStream(workspace, rootUuid, path, maxBytes, consume) }
        val entry = find(path) ?: fail("NOT_FOUND", "文件不存在")
        if (entry.isDirectory) fail("IS_DIRECTORY", "目标为目录")
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("NOT_FOUND", "工作区不可用")
        val context = kotlinx.coroutines.currentCoroutineContext()
        return WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            SecureWorkspaceFileOps().readStream(File(root.physicalRootPath).toPath(), ExternalFolderPaths.normalize(path).trim('/').split('/'), maxBytes) { input ->
                // 仓库的文件句柄回调是同步 API；保留取消与挂载绑定，移除调度器以免单线程调用死锁。
                kotlinx.coroutines.runBlocking(context.minusKey(kotlin.coroutines.ContinuationInterceptor)) {
                    WorkspaceMutationCoordinator.consumeUnlocked(File(root.physicalRootPath).toPath()) { consume(input) }
                }
            }
        }
    }
    override suspend fun availableBytes(path: String): Long? {
        find(path)?.takeIf { it.isDirectory } ?: fail("NOT_FOUND", "目标目录不存在")
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("NOT_FOUND", "工作区不可用")
        return WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            File(root.physicalRootPath).usableSpace
        }
    }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry =
        writeStream(path, content.size.toLong(), createOnly) { it.write(content) }

    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit): ExternalFolderEntry {
        if (!createOnly) fail("UNSUPPORTED", "跨工作区复制只允许新建")
        val normalized = ExternalFolderPaths.normalize(path)
        val parentPath = normalized.substringBeforeLast('/').ifEmpty { "/" }
        val parent = find(parentPath) ?: fail("NOT_FOUND", "目标目录不存在")
        if (!parent.isDirectory) fail("NOT_DIRECTORY", "目标父项不是目录")
        if (find(normalized) != null) fail("CONFLICT", "目标已存在")
        if (expectedSize < 0) fail("INVALID_ARGUMENT", "文件大小无效")
        if (availableBytes(parentPath)!! < expectedSize) fail("NO_SPACE", "目标可用空间不足 / Insufficient destination space")
        val context = kotlinx.coroutines.currentCoroutineContext()
        val entry = workspace.createFileInWorkspaceStreaming(rootUuid, UUID.randomUUID().toString(), normalized.substringAfterLast('/'), externalSnapshotMime(normalized.substringAfterLast('/')), parent.uuid, normalized, expectedSize.coerceAtLeast(1)) { output ->
            kotlinx.coroutines.runBlocking(context.minusKey(kotlin.coroutines.ContinuationInterceptor)) { produceExternalStream(output, expectedSize, produce) }
        }
        created.add(entry.uuid)
        return ExternalFolderEntry(path, entry.name, false, entry.sizeBytes, entry.updatedAt, entry.hash, entry.uuid)
    }
    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        val parent = find(path.substringBeforeLast('/').ifEmpty { "/" }) ?: fail("NOT_FOUND", "目标目录不存在")
        val entry = workspace.createDirectoryInWorkspace(rootUuid, UUID.randomUUID().toString(), path.substringAfterLast('/'), parent.uuid, path)
        created.add(entry.uuid)
        return stat(path)!!
    }
    override suspend fun delete(path: String, expectedSha256: String?) {
        val entry = find(path) ?: return
        if (expectedSha256 != null) {
            try { workspace.permanentDeleteIfHash(rootUuid, entry.uuid, expectedSha256) }
            catch (failure: IllegalStateException) { fail("CONFLICT", "删除前来源已改变") }
            created.remove(entry.uuid)
            return
        }
        if (entry.uuid in created) {
            if (entry.isDirectory) workspace.permanentDelete(rootUuid, entry.uuid) else workspace.rollbackCreatedEntry(rootUuid, entry.uuid)
            created.remove(entry.uuid)
        }
        else workspace.permanentDelete(rootUuid, entry.uuid)
    }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        val entry = find(path) ?: fail("CONFLICT", "来源已不存在")
        if (entry.uuid != expected.identity || entry.isDirectory != expected.isDirectory) fail("CONFLICT", "来源身份已变化")
        if (entry.isDirectory) workspace.permanentDeleteEmptyDirectory(rootUuid, entry.uuid)
        else try { workspace.permanentDeleteIfHash(rootUuid, entry.uuid, requireNotNull(expectedSha256)) }
        catch (failure: IllegalStateException) { fail("CONFLICT", "删除前来源已改变") }
        created.remove(entry.uuid)
    }
    suspend fun confirmCreated(): Boolean {
        var indexed = true
        created.forEach { uuid ->
            workspace.confirmCreatedEntry(rootUuid, uuid)
            val entry = workspace.getByUuid(rootUuid, uuid)
            if (entry != null && !entry.isDirectory && externalSnapshotMime(entry.name) in com.promenar.infinity.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) {
                try { indexEvents.publish(com.promenar.infinity.data.rag.FileIndexEvent.Changed(rootUuid, uuid, entry.hash, entry.updatedAt.coerceAtLeast(1))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { indexed = false }
            }
        }
        created.clear()
        return indexed
    }
}

/**
 * 文件工具改动摘要的采集：执行前读取写入前快照，成功后读取写入后快照并计算 [ToolResult.fileChanges]。
 * 只追加字段，不改动工具返回给模型的 `content`；读取或计算失败时原样返回结果。
 */
private class FileChangeCapture private constructor(
    private val toolId: String,
    private val reader: WorkspaceSnapshotReader,
    private val path: String,
    private val fromPath: String?,
    private val before: FileSnapshot?,
) {
    suspend fun finish(result: ToolResult): ToolResult {
        if (result.status != "success") return result
        return try {
            val summary = when (toolId) {
                "write_file", "patch_file" -> {
                    val after = reader.read(path).snapshot
                    withContext(Dispatchers.Default) { FileChangeSummaries.modified(path, before ?: FileSnapshot.Unavailable, after) }
                }
                "create_file" -> {
                    val after = reader.read(path).snapshot
                    withContext(Dispatchers.Default) { FileChangeSummaries.created(path, after) }
                }
                "delete_file" -> withContext(Dispatchers.Default) {
                    FileChangeSummaries.deleted(path, before ?: FileSnapshot.Unavailable)
                }
                "move_file" -> FileChangeSummaries.moved(fromPath ?: return result, path, reader.isDirectory(path) == true)
                else -> return result
            }
            result.copy(fileChanges = listOf(summary).withinHunkBudget())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result
        }
    }

    companion object {
        private val CAPTURED_TOOLS = setOf("write_file", "create_file", "patch_file", "delete_file", "move_file")

        suspend fun begin(
            toolId: String,
            args: JsonObject,
            reader: WorkspaceSnapshotReader,
            sourcePath: String?,
            parentPath: String?,
            destinationParent: String?,
        ): FileChangeCapture? {
            if (toolId !in CAPTURED_TOOLS) return null
            return try {
                when (toolId) {
                    "create_file" -> {
                        val name = args.stringArgument("name")?.trim()?.takeIf(String::isNotEmpty) ?: return null
                        FileChangeCapture(toolId, reader, (parentPath ?: "/").trimEnd('/') + "/" + name, null, null)
                    }
                    "move_file" -> {
                        val source = sourcePath ?: return null
                        val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
                        val destination = (destinationParent ?: source.substringBeforeLast('/').ifEmpty { "/" }).trimEnd('/') +
                            "/" + (newName ?: source.substringAfterLast('/'))
                        if (destination == source) return null
                        FileChangeCapture(toolId, reader, destination, source, null)
                    }
                    else -> {
                        val source = sourcePath ?: return null
                        val captured = reader.read(source)
                        // 写入以 expectedHash 做乐观锁：快照哈希不一致说明读到的不是被覆盖的版本，不用于计数。
                        val expected = args.stringArgument("expectedHash")
                        val before = if (toolId != "delete_file" && expected != null && captured.sha256 != null && captured.sha256 != expected) {
                            FileSnapshot.Unavailable
                        } else captured.snapshot
                        FileChangeCapture(toolId, reader, source, null, before)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}
