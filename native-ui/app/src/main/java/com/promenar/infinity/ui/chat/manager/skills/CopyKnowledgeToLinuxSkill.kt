package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.linux.LinuxImportFiles
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.LinuxSessionPathKind
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.domain.linux.LinuxFileSystem
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** 广告与执行重验共用的最小权限策略。 */
object KnowledgeLinuxCopyPolicy {
    fun allows(session: Session, knowledgeAccessAllowed: Boolean): Boolean =
        session.runMode == SessionRunMode.LINUX && session.options.toolsEnabled && knowledgeAccessAllowed
}

class CopyKnowledgeToLinuxSkill(
    private val loadSession: suspend (String) -> Session?,
    private val knowledgeAccessAllowed: suspend (Session) -> Boolean,
    private val access: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: LinuxFileSystem,
    private val linuxReady: () -> Boolean,
    private val reconcile: suspend (String) -> Unit,
) : SkillDefinition {
    internal var rootfsFileSystem: com.promenar.infinity.data.storage.RealPathFileSystem = com.promenar.infinity.data.storage.AndroidRealPathFileSystem

    override val id = "copy_knowledge_to_linux"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val description = "把已获本会话只读挂载授权的资料文件或目录复制到 Linux。资料里应用不解析的格式（如 .sqlite、.epub），可先复制进 Linux 再用命令处理。source 使用 /mnt/<资料别名>/...，未授权时先调用 request_knowledge_access。target 为目标目录，默认 /home/session/inbox；单次总量不超过100 MiB，不覆盖同名文件，目录中的符号链接会跳过。"
    override val parametersSchema = """{"type":"object","properties":{"source":{"type":"string","description":"已获授权的资料文件或目录路径，例如 /mnt/knowledge/data.sqlite"},"target":{"type":"string","description":"Linux 目标目录，默认 /home/session/inbox"}},"required":["source"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        var touchedPrivate = false
        var reconcileFailure: Exception? = null
        val result = try {
            require(args.keys.all { it in setOf("source", "target") }) { "存在未支持的参数" }
            val session = loadSession(context.sessionId) ?: return@withContext error("会话不存在")
            if (!KnowledgeLinuxCopyPolicy.allows(session, knowledgeAccessAllowed(session))) return@withContext error("当前会话未开放 Linux 资料复制工具")
            if (context.runMode != null && context.runMode != session.runMode) return@withContext error("会话模式已变化，请重新调用")
            if (!linuxReady()) return@withContext error("Linux 环境尚未就绪")
            val source = string(args, "source")?.takeIf { it.isNotBlank() } ?: return@withContext error("source 不能为空")
            require(source.startsWith("/mnt/")) { "source 必须是已授权的资料挂载路径，请先调用 request_knowledge_access" }
            val normalizedSource = com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(source)
            val (alias, _) = ExternalMountPaths.parse(normalizedSource) ?: return@withContext error("请先调用 request_knowledge_access 授权资料")
            val mount = access.visible(session.id).singleOrNull {
                it.alias == alias && it.sessionId == session.id && it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE &&
                    it.readOnly && it.unavailableReason == null
            } ?: return@withContext error("source 未获本会话资料授权，请先调用 request_knowledge_access")
            val currentTarget = com.promenar.infinity.data.generation.knowledgeLinuxCopyTarget(session, args, workspace, access)
                ?: return@withContext error("无法确定复制目标或来源授权")
            if ((context.isApprovalBoundExecution || context.approvedExecutionTarget != null) && context.approvedExecutionTarget != currentTarget)
                return@withContext error("批准的来源挂载或目标已变化，请重新审批")
            if ((kotlinx.serialization.json.Json.parseToJsonElement(currentTarget) as kotlinx.serialization.json.JsonArray)
                    .last().let { (it as JsonObject)["linuxSkills"] }?.let { (it as JsonPrimitive).content == "true" } == true &&
                !context.isApprovalBoundExecution) return@withContext error("技能目录变更需要按当前 Linux 路径逐次审批")
            val rawTarget = if ("target" in args) string(args, "target") ?: return@withContext error("target 必须是目录路径") else DEFAULT_TARGET
            val target = LinuxSessionPaths.normalize(rawTarget)
            val route = LinuxSessionPaths.route(target, session.workspaceId == null, null)
            require(route.kind in setOf(LinuxSessionPathKind.PRIVATE, LinuxSessionPathKind.ROOTFS)) { "不能写入 /mnt 或非私有的 /workspace" }
            val rootfs = LinuxImportFiles(files, rootfsFileSystem)
            val root = if (route.kind == LinuxSessionPathKind.PRIVATE) workspace.ensureSessionRoot(session.id) else null
            if (root != null) validatePrivate(root.physicalRootPath, route.path)
            else rootfs.directory(target, create = true)
            val bindings = mapOf(alias to mount.id)
            val backend = access.backend(session.id, bindings)
            val plan = withContext(ExternalMountOperationBindings(bindings)) { plan(backend, normalizedSource) }
            var copied = 0
            var bytes = 0L
            withContext(ExternalMountOperationBindings(bindings)) {
                if (root != null) {
                    touchedPrivate = true
                    ensurePrivateDirectory(root.uuid, root.physicalRootPath, route.path)
                }
                for (entry in plan) {
                    currentCoroutineContext().ensureActive()
                    val fresh = loadSession(session.id) ?: throw IllegalArgumentException("会话不存在")
                    require(KnowledgeLinuxCopyPolicy.allows(fresh, knowledgeAccessAllowed(fresh)) && fresh.workspaceId == session.workspaceId && linuxReady()) { "会话权限或 Linux 状态已变化" }
                    val suffix = if (plan.first().isDirectory) entry.path.removePrefix(normalizedSource).let {
                        "/${plan.first().name}" + it
                    } else "/${entry.name}"
                    val destination = (if (root != null) route.path else target).trimEnd('/') + suffix
                    if (entry.isDirectory) {
                        if (root != null) ensurePrivateDirectory(root.uuid, root.physicalRootPath, destination)
                        else rootfs.directory(destination, create = true)
                    } else {
                        require(bytes <= MAX_BYTES - entry.size) { "复制总量超过100 MiB" }
                        val remaining = MAX_BYTES - bytes
                        val parent = destination.substringBeforeLast('/').ifEmpty { "/" }
                        val parentEntry = root?.let {
                            validatePrivate(it.physicalRootPath, destination)
                            val directory = ensurePrivateDirectory(it.uuid, it.physicalRootPath, parent)
                            require(workspace.getByMaterializedPath(it.uuid, destination) == null) { "目标已存在：$destination" }
                            directory
                        }
                        // 逐块转写，不把整个文件载入内存；读取端按剩余额度限长。
                        val written = backend.readStream(entry.path, remaining) { input ->
                            var count = 0L
                            if (root != null && parentEntry != null) {
                                val created = workspace.createFileInWorkspaceStreaming(root.uuid, UUID.randomUUID().toString(), entry.name,
                                    "application/octet-stream", parentEntry.uuid, destination, maxOf(1L, remaining)) { count = input.copyTo(it) }
                                try { workspace.confirmCreatedEntry(root.uuid, created.uuid) }
                                catch (failure: Throwable) {
                                    withContext(NonCancellable) { workspace.rollbackCreatedEntry(root.uuid, created.uuid) }
                                    throw failure
                                }
                            } else rootfs.create(parent, entry.name, remaining) { count = input.copyTo(it) }
                            count
                        }
                        bytes += written
                        copied++
                    }
                }
            }
            ToolResult(id, "已复制 $copied 个文件，共 $bytes 字节，目标目录：$target")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure.message ?: "资料文件复制失败") }
        finally {
            if (touchedPrivate) withContext(NonCancellable) {
                try { reconcile(context.sessionId) } catch (failure: Exception) { reconcileFailure = failure }
            }
        }
        if (reconcileFailure != null) error("文件已写入，但会话文件对账失败，请检查文件面板后重试") else result
    }

    private suspend fun plan(backend: ExternalFolderBackend, source: String): List<ExternalFolderEntry> {
        val result = mutableListOf<ExternalFolderEntry>()
        var total = 0L
        suspend fun visit(path: String, depth: Int) {
            require(depth <= 64 && result.size < 10_000) { "目录层级或条目数超过复制上限" }
            val entry = backend.stat(path) ?: throw IllegalArgumentException("资料源不存在")
            require(entry.path == path && (path == source || path.startsWith(source.trimEnd('/') + "/"))) { "资料返回了越界路径" }
            require(SharedFileImporter.normalizeName(entry.name) == entry.name) { "源文件名无效" }
            require(entry.size >= 0 && (entry.isDirectory || total <= MAX_BYTES - entry.size)) { "复制总量超过100 MiB" }
            if (!entry.isDirectory) total += entry.size
            result += if (path == source) entry.copy(name = source.substringAfterLast('/')) else entry
            if (entry.isDirectory) backend.list(path).forEach { child ->
                require(child.path == path.trimEnd('/') + "/" + child.name) { "资料子路径越界" }
                // 真实资料后端在列举时已过滤符号链接，读取时也使用不跟随链接的句柄。
                visit(child.path, depth + 1)
            }
        }
        visit(source, 0)
        return result
    }

    private suspend fun ensurePrivateDirectory(root: String, physicalRoot: String, path: String): com.promenar.infinity.data.local.db.entity.FileEntry {
        validatePrivate(physicalRoot, path)
        var current = workspace.getByUuid(root, root) ?: throw IllegalStateException("会话根不存在")
        var relative = ""
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            relative += "/$segment"
            val existing = workspace.getByMaterializedPath(root, relative)
            if (existing != null) {
                require(existing.isDirectory && !existing.inRecycleBin) { "目标不是可用目录" }
                current = existing
            } else {
                current = workspace.createDirectoryInWorkspace(root, UUID.randomUUID().toString(), segment, current.uuid, relative)
            }
        }
        return current
    }

    private fun validatePrivate(physicalRoot: String, path: String) {
        val base = java.io.File(physicalRoot).toPath().toRealPath()
        var current = base
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            require(segment !in setOf(".", "..")) { "目标路径越界" }
            current = current.resolve(segment)
            require(current.startsWith(base) && !Files.isSymbolicLink(current)) { "目标不能经符号链接逃逸" }
            if (Files.exists(current, NOFOLLOW_LINKS)) require(current.toRealPath().startsWith(base)) { "目标越界" }
        }
    }

    private fun string(args: JsonObject, name: String): String? = (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun error(message: String) = ToolResult(id, message, "error")
    companion object {
        const val DEFAULT_TARGET = "/home/session/inbox"
        const val MAX_BYTES = SharedFileImporter.MAX_BATCH_BYTES
    }
}
