package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.WorkspaceTextContentPolicy
import com.promenar.infinity.data.repository.WorkspaceTextOperation
import com.promenar.infinity.data.repository.WorkspaceTextPolicyException
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.externalSnapshotMime
import com.promenar.infinity.data.storage.isKnowledgeBaseSource
import com.promenar.infinity.data.storage.isSftpRemoteSource
import com.promenar.infinity.data.storage.resolveRemoteMountLocalFile
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.data.storage.requireWritable
import com.promenar.infinity.data.workspace.BuiltinHostPathBackend
import com.promenar.infinity.data.workspace.BuiltinRealPathContext
import com.promenar.infinity.data.workspace.BuiltinRealPathKind
import com.promenar.infinity.data.workspace.BuiltinRealPathPaths
import com.promenar.infinity.data.workspace.BuiltinRealPathRoute
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.DiffResult
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/**
 * 内置模式真实路径命名空间：
 * - 文件工具与 shell/python 共享同一套真实 Android 规范路径；
 * - 私有会话目录继续走 Room 事务、版本历史与向量索引；
 * - 仅物理上不存在 POSIX 路径的逻辑源（资料库、SAF、远程 SFTP）保留虚拟前缀；
 * - 关键运行时数据目录开放只读自诊断、硬拒文件工具写删，加密密钥存储读写全拒；
 * - 用户技能目录（`files/agent_skills`）与项目 `.agents/skills` 的写删改始终强制 L2 审批；
 * - 工作区外的其它合法真实路径写入归入 L1 `OUTSIDE_WORKSPACE` 审批。
 */
class BuiltinRealPathWorkspaceAccess(
    private val original: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val userSkillsDir: File,
    private val appDataDir: File,
    private val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
    private val sessions: ISessionRepository? = null,
    private val privateReader: com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader? = null,
) : SessionNamespaceWorkspaceAccess, ExternalWorkspaceAccess by original {
    private val hostBackend: BuiltinHostPathBackend = BuiltinHostPathBackend(appDataDir)

    private data class Namespace(
        val root: FileEntry,
        val workspace: SessionWorkspace,
        val ctx: BuiltinRealPathContext,
        val mount: ExternalMount,
    )

    private val versions = LinuxVersionSnapshots(VERSION_BYTES)
    override val knowledgeManager: ExternalFolderManager? get() = original as? ExternalFolderManager

    override suspend fun workspaceMount(sessionId: String): ExternalMount = namespace(sessionId).mount

    override suspend fun toolRootUuid(sessionId: String): String = operation {
        val current = namespace(sessionId)
        uuid(current, current.ctx.primaryPath)
    }

    override suspend fun workingDirectory(sessionId: String): String = namespace(sessionId).ctx.primaryPath

    suspend fun privateWorkspacePath(sessionId: String): String = namespace(sessionId).ctx.privatePath

    val userSkillsRealPath: String get() = BuiltinRealPathContext.canonicalOrAbs(userSkillsDir)

    internal suspend fun remoteMountLocalFile(sessionId: String, rawPath: String, writing: Boolean) = operation {
        if (com.promenar.infinity.data.ssh.isSftpProtectedLocalPath(rawPath)) {
            fail("ACCESS_DENIED", "技能与受保护路径不可用于远程挂载传输")
        }
        val current = namespace(sessionId)
        if (current.workspace is SessionWorkspace.Unavailable && !rawPath.trim().startsWith('/')) {
            fail("WORKSPACE_UNAVAILABLE", "当前工作区不可用，请检查目录与授权")
        }
        val routed = route(current, rawPath)
        val selected = current.workspace as? SessionWorkspace.Directory
        val private = routed.kind == BuiltinRealPathKind.PRIVATE
        val root = when {
            private -> File(current.root.physicalRootPath)
            routed.kind == BuiltinRealPathKind.WORKSPACE && selected != null -> selected.root
            else -> fail("ACCESS_DENIED", "本地传输路径须位于当前工作区")
        }
        if (writing) requireMutableTarget(sessionId, current, rawPath)
        resolveRemoteMountLocalFile(sessionId, current.mount.id, root, routed.relativePath, routed.canonicalPath,
            if (private) current.root.uuid else selected?.workspace?.takeIf { it.kind == "branch_shared" }?.location, writing)
    }

    override suspend fun searchRoots(sessionId: String): List<String> = operation {
        val ctx = namespace(sessionId).ctx
        buildList {
            add(ctx.primaryPath)
            if (ctx.privatePath != ctx.primaryPath) add(ctx.privatePath)
            ctx.normalizedRealMounts.forEach { add(it.second) }
            if (ctx.virtualMounts.isNotEmpty()) add("/mnt")
        }.distinct()
    }

    override suspend fun aliasOf(sessionId: String, path: String): String = operation {
        val ctx = namespace(sessionId).ctx
        BuiltinRealPathPaths.aliasOf(path, ctx)
    }

    override fun protectedVirtualRoots(): Set<String> = setOf("/mnt")

    override suspend fun resolveUuid(sessionId: String, uuid: String): String? = operation {
        val current = namespace(sessionId)
        val prefix = "external:${current.mount.id}:"
        if (uuid.startsWith(prefix)) {
            val decoded = try {
                String(Base64.getUrlDecoder().decode(uuid.removePrefix(prefix)), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return@operation null
            }
            return@operation runCatching { BuiltinRealPathPaths.route(decoded, current.ctx).canonicalPath }.getOrNull()
        }
        if (uuid.startsWith("external:")) {
            val mounts = withOriginalBindings(current) { original.visible(sessionId) + listOfNotNull(original.workspaceMount(sessionId)) }
            val legacyVirtual = ExternalMountPaths.uuidPath(uuid, mounts) ?: return@operation null
            return@operation fromOriginalVirtual(current, legacyVirtual)
        }
        workspace.getByUuid(current.root.uuid, uuid)?.materializedPath?.let { rel ->
            BuiltinRealPathPaths.joinPath(current.ctx.privatePath, rel)
        }
    }

    override suspend fun entry(sessionId: String, path: String): FileEntry? = entry(sessionId, path, fillHash = true)

    override suspend fun statEntry(sessionId: String, path: String): FileEntry? = entry(sessionId, path, fillHash = false)

    private suspend fun entry(sessionId: String, path: String, fillHash: Boolean): FileEntry? = operation {
        val current = namespace(sessionId)
        val routed = route(current, path)
        if (routed.kind == BuiltinRealPathKind.RUNTIME_DATA_SECRET) {
            fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
        }
        val stat = stat(sessionId, current, routed, fillHash) ?: return@operation null
        val hash = stat.sha256?.takeIf(String::isNotBlank) ?: if (fillHash && !stat.isDirectory && stat.size <= VERSION_BYTES) {
            runCatching { Sha256Utils.hash(readRouted(sessionId, current, routed, VERSION_BYTES)) }.getOrDefault("")
        } else ""
        val canonical = routed.canonicalPath
        FileEntry(
            uuid = uuid(current, canonical),
            parentUuid = canonical.takeUnless { it == "/" || it == current.ctx.primaryPath }?.let { uuid(current, parent(it)) },
            name = if (canonical == "/") "" else canonical.substringAfterLast('/'),
            hash = hash,
            sizeBytes = stat.size,
            isDirectory = stat.isDirectory,
            physicalRootPath = "",
            materializedPath = canonical,
            createdAt = 0,
            updatedAt = stat.lastModified,
        )
    }

    override suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry> = operation {
        val current = namespace(sessionId)
        val routed = route(current, virtualPath)
        when (routed.kind) {
            BuiltinRealPathKind.RUNTIME_DATA_SECRET ->
                fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT ->
                current.ctx.virtualMounts.map { ExternalFolderEntry("/mnt/${it.alias}", it.alias, true, 0, it.createdAt) }
            BuiltinRealPathKind.PRIVATE ->
                privateBackend(current).list(routed.relativePath).map { it.copy(path = child(routed.canonicalPath, it.name)) }
            BuiltinRealPathKind.WORKSPACE ->
                withOriginalBindings(current) { original.list(sessionId, routed.relativePath) }
                    .map { it.copy(path = child(routed.canonicalPath, it.name)) }
            BuiltinRealPathKind.USER_SKILLS ->
                withOriginalBindings(current) { original.list(sessionId, skillsOriginalPath(routed.relativePath)) }
                    .map { it.copy(path = child(routed.canonicalPath, it.name)) }
            BuiltinRealPathKind.REAL_MOUNT ->
                withOriginalBindings(current) { original.list(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath)) }
                    .map { it.copy(path = child(routed.canonicalPath, it.name)) }
            BuiltinRealPathKind.VIRTUAL_MOUNT ->
                withOriginalBindings(current) { original.list(sessionId, routed.canonicalPath) }
                    .map { it.copy(path = child(routed.canonicalPath, it.name)) }
            BuiltinRealPathKind.RUNTIME_DATA_PROTECTED, BuiltinRealPathKind.HOST_REAL_PATH ->
                hostBackend.list(routed.canonicalPath)
        }
    }

    override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = operation {
        val generation = versions.generation()
        val current = namespace(sessionId)
        val routed = route(current, virtualPath)
        val bytes = readRouted(sessionId, current, routed, maxBytes)
        remember(current, routed, bytes, generation)
        bytes
    }

    private suspend fun readRouted(sessionId: String, current: Namespace, routed: BuiltinRealPathRoute, maxBytes: Long): ByteArray = when (routed.kind) {
        BuiltinRealPathKind.RUNTIME_DATA_SECRET ->
            fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
        BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT -> fail("IS_DIRECTORY", "目标是目录")
        BuiltinRealPathKind.PRIVATE -> privateBackend(current).read(routed.relativePath, maxBytes)
        BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.read(sessionId, routed.relativePath, maxBytes) }
        BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.read(sessionId, skillsOriginalPath(routed.relativePath), maxBytes) }
        BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.read(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath), maxBytes) }
        BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.read(sessionId, routed.canonicalPath, maxBytes) }
        BuiltinRealPathKind.RUNTIME_DATA_PROTECTED, BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.read(routed.canonicalPath, maxBytes)
    }

    override suspend fun <T> readStream(sessionId: String, virtualPath: String, maxBytes: Long, consume: suspend (InputStream) -> T): T = operation {
        val current = namespace(sessionId)
        val routed = route(current, virtualPath)
        when (routed.kind) {
            BuiltinRealPathKind.RUNTIME_DATA_SECRET ->
                fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT -> fail("IS_DIRECTORY", "目标是目录")
            BuiltinRealPathKind.PRIVATE -> privateBackend(current).readStream(routed.relativePath, maxBytes, consume)
            BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.readStream(sessionId, routed.relativePath, maxBytes, consume) }
            BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.readStream(sessionId, skillsOriginalPath(routed.relativePath), maxBytes, consume) }
            BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.readStream(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath), maxBytes, consume) }
            BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.readStream(sessionId, routed.canonicalPath, maxBytes, consume) }
            BuiltinRealPathKind.RUNTIME_DATA_PROTECTED, BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.readStream(routed.canonicalPath, maxBytes, consume)
        }
    }

    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry = operation {
        val generation = versions.generation()
        val current = namespace(sessionId)
        val routed = requireMutableTarget(sessionId, current, path)
        when (routed.kind) {
            BuiltinRealPathKind.PRIVATE -> writePrivate(sessionId, current, routed.relativePath, bytes, expectedSha256, createOnly)
            BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.write(sessionId, routed.relativePath, bytes, expectedSha256, createOnly) }
            BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.write(sessionId, skillsOriginalPath(routed.relativePath), bytes, expectedSha256, createOnly) }
            BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.write(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath), bytes, expectedSha256, createOnly) }
            BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.write(sessionId, routed.canonicalPath, bytes, expectedSha256, createOnly) }
            BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.write(routed.canonicalPath, bytes, expectedSha256, createOnly)
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT, BuiltinRealPathKind.RUNTIME_DATA_SECRET, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED ->
                fail("PROTECTED_RUNTIME_DATA", "目标位置不可写入：${routed.canonicalPath}")
        }
        remember(current, routed, bytes, generation)
        entry(sessionId, routed.canonicalPath) ?: fail("NOT_FOUND", "写入后文件不可用")
    }

    override suspend fun createDirectory(sessionId: String, path: String): FileEntry = operation {
        val current = namespace(sessionId)
        val routed = requireMutableTarget(sessionId, current, path)
        when (routed.kind) {
            BuiltinRealPathKind.PRIVATE -> privateBackend(current).let { backend -> backend.createDirectory(routed.relativePath).also { backend.confirmCreated() } }
            BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.createDirectory(sessionId, routed.relativePath) }
            BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.createDirectory(sessionId, skillsOriginalPath(routed.relativePath)) }
            BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.createDirectory(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath)) }
            BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.createDirectory(sessionId, routed.canonicalPath) }
            BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.createDirectory(routed.canonicalPath)
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT, BuiltinRealPathKind.RUNTIME_DATA_SECRET, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED ->
                fail("PROTECTED_RUNTIME_DATA", "目标位置不可创建目录：${routed.canonicalPath}")
        }
        entry(sessionId, routed.canonicalPath) ?: fail("NOT_FOUND", "创建后目录不可用")
    }

    override suspend fun delete(sessionId: String, path: String, expectedSha256: String?) = operation {
        val current = namespace(sessionId)
        val routed = requireMutableTarget(sessionId, current, path)
        try {
            when (routed.kind) {
                BuiltinRealPathKind.PRIVATE -> {
                    val entry = workspace.getByMaterializedPath(current.root.uuid, routed.relativePath) ?: fail("NOT_FOUND", "文件不存在")
                    if (expectedSha256 != null) workspace.permanentDeleteIfHash(current.root.uuid, entry.uuid, expectedSha256)
                    else workspace.permanentDelete(current.root.uuid, entry.uuid)
                }
                BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.delete(sessionId, routed.relativePath, expectedSha256) }
                BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.delete(sessionId, skillsOriginalPath(routed.relativePath), expectedSha256) }
                BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.delete(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath), expectedSha256) }
                BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.delete(sessionId, routed.canonicalPath, expectedSha256) }
                BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.delete(routed.canonicalPath, expectedSha256)
                BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT, BuiltinRealPathKind.RUNTIME_DATA_SECRET, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED ->
                    fail("PROTECTED_RUNTIME_DATA", "目标位置不可删除：${routed.canonicalPath}")
            }
        } finally {
            forgetVersions(current, routed)
        }
    }

    override suspend fun version(sessionId: String, path: String, hash: String): ByteArray? = operation {
        val current = namespace(sessionId)
        val routed = route(current, path)
        when (routed.kind) {
            BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { original.version(sessionId, routed.relativePath, hash) }
            BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { original.version(sessionId, skillsOriginalPath(routed.relativePath), hash) }
            BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { original.version(sessionId, mountOriginalPath(requireNotNull(routed.mount), routed.relativePath), hash) }
            BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { original.version(sessionId, routed.canonicalPath, hash) }
            else -> versions.get(versionKey(current, routed, hash))
        }
    }

    override fun backend(sessionId: String, mountIds: Map<String, String>): ExternalFolderBackend = object : ExternalFolderBackend {
        private suspend fun <T> bound(block: suspend () -> T): T =
            if (mountIds.isEmpty()) block() else withContext(ExternalMountOperationBindings(mountIds)) { block() }

        private suspend fun <T> transferBackend(path: String, writing: Boolean = false, action: suspend (ExternalFolderBackend, String) -> T): T = bound {
            val current = namespace(sessionId)
            val routed = if (writing) requireMutableTarget(sessionId, current, path) else route(current, path)
            when (routed.kind) {
                BuiltinRealPathKind.PRIVATE -> action(privateBackend(current), routed.relativePath)
                BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) {
                    action(original.backend(sessionId, currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty()), routed.relativePath)
                }
                BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) {
                    action(original.backend(sessionId, currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty()), skillsOriginalPath(routed.relativePath))
                }
                BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) {
                    action(original.backend(sessionId, currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty()), mountOriginalPath(requireNotNull(routed.mount), routed.relativePath))
                }
                BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) {
                    action(original.backend(sessionId, currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty()), routed.canonicalPath)
                }
                BuiltinRealPathKind.HOST_REAL_PATH, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED -> action(hostBackend, routed.canonicalPath)
                BuiltinRealPathKind.RUNTIME_DATA_SECRET -> fail("ACCESS_DENIED", "加密密钥存储文件不可访问")
                BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT -> fail("INVALID_PATH", "不能操作挂载根目录")
            }
        }

        override suspend fun stat(path: String) = transferBackend(path) { backend, actual -> backend.stat(actual)?.copy(path = path) }
        override suspend fun list(path: String) = bound { this@BuiltinRealPathWorkspaceAccess.list(sessionId, path) }
        override suspend fun read(path: String, maxBytes: Long) = bound { this@BuiltinRealPathWorkspaceAccess.read(sessionId, path, maxBytes) }
        override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T =
            bound { this@BuiltinRealPathWorkspaceAccess.readStream(sessionId, path, maxBytes, consume) }
        override suspend fun availableBytes(path: String) = transferBackend(path) { backend, actual -> backend.availableBytes(actual) }
        override suspend fun validateTree(path: String) = transferBackend(path) { backend, actual -> backend.validateTree(actual) }
        override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit) =
            transferBackend(path, writing = true) { backend, actual ->
                val receipt = backend.writeStream(actual, expectedSize, createOnly, produce)
                if (backend is PrivateWorkspaceTransferBackend) backend.confirmCreated()
                receipt.copy(path = path)
            }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) = bound {
            this@BuiltinRealPathWorkspaceAccess.write(sessionId, path, content, expectedSha256, createOnly)
                .let { ExternalFolderEntry(it.materializedPath, it.name, false, it.sizeBytes, it.updatedAt, it.hash, it.uuid) }
        }
        override suspend fun createDirectory(path: String) = transferBackend(path, writing = true) { backend, actual ->
            val receipt = backend.createDirectory(actual)
            if (backend is PrivateWorkspaceTransferBackend) backend.confirmCreated()
            receipt.copy(path = path)
        }
        override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) = bound {
            val current = namespace(sessionId)
            val routed = requireMutableTarget(sessionId, current, path)
            try {
                transferBackend(path, writing = true) { backend, actual ->
                    backend.deleteForTransfer(actual, expected.copy(path = actual), expectedSha256)
                }
            } finally {
                forgetVersions(current, routed)
            }
        }
        override suspend fun delete(path: String, expectedSha256: String?) = bound {
            this@BuiltinRealPathWorkspaceAccess.delete(sessionId, path, expectedSha256)
        }
        override suspend fun move(sourcePath: String, targetPath: String) = fail("UNSUPPORTED", "内置真实路径跨区传输交由安全复制移动完成")
        override suspend fun copy(sourcePath: String, targetPath: String) = fail("UNSUPPORTED", "不支持直接复制")
    }

    override suspend fun validatePath(sessionId: String, path: String): String = operation {
        val current = namespace(sessionId)
        val routed = route(current, path)
        if (routed.kind == BuiltinRealPathKind.RUNTIME_DATA_SECRET) {
            fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
        }
        routed.canonicalPath
    }

    override suspend fun executionTarget(sessionId: String, tool: ProtocolTool, args: JsonObject): String? = operation {
        val current = namespace(sessionId)
        val normalized = BuiltinRealPathPaths.normalizeArguments(tool.function.name, args, current.ctx)
        val resolved = mutableMapOf<String, String>()
        for (key in listOf("uuid", "parentUuid", "newParentUuid")) {
            val uuid = (normalized[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (uuid.isNotEmpty()) resolved[uuid] = resolveUuid(sessionId, uuid) ?: return@operation null
        }
        val target = ToolExecutionTargets.extract(tool, normalized.toString(), resolved, usesLinux = false) ?: return@operation null
        val parts = Json.parseToJsonElement(target) as? JsonArray ?: return@operation null
        val metadata = parts.lastOrNull() as? JsonObject
        val tuple = if (metadata == null) parts.toList() else parts.dropLast(1)
        if ((tuple.firstOrNull() as? JsonPrimitive)?.content !in setOf("path", "move")) return@operation target
        val paths = tuple.drop(1).map { (it as? JsonPrimitive)?.content ?: return@operation null }
        val routes = paths.map { route(current, it) }
        val isMutation = ToolExecutionTargets.isPathMutation(tool.function.name)
        if (isMutation && routes.any { it.mount?.isSftpRemoteSource == true }) throw sftpGenericMutationDenied()
        if (routes.any { it.kind == BuiltinRealPathKind.RUNTIME_DATA_SECRET }) {
            fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问")
        }
        if (isMutation && routes.any { it.kind == BuiltinRealPathKind.RUNTIME_DATA_PROTECTED }) {
            fail("PROTECTED_RUNTIME_DATA", "应用关键运行时数据目录仅开放只读自诊断，禁止通过文件工具修改或删除")
        }
        val ids = visible(sessionId).associate { it.alias to it.id } + ("" to current.mount.id)
        val aliases = paths.map { BuiltinRealPathPaths.aliasOf(it, current.ctx) }.toSet() + ""
        val skillMutation = isMutation && paths.any { BuiltinRealPathPaths.isSkillsMutationPath(it, current.ctx) }
        val outsideWorkspace = isMutation && !skillMutation && routes.any { it.kind == BuiltinRealPathKind.HOST_REAL_PATH }
        JsonArray(tuple + JsonObject(metadata.orEmpty() + buildMap {
            put("builtinRealPath", JsonPrimitive(true))
            put("mountIds", JsonObject(aliases.sorted().associateWith { JsonPrimitive(ids[it] ?: return@operation null) }))
            if (skillMutation) put("builtinSkills", JsonPrimitive(true))
            if (outsideWorkspace) put("outsideWorkspace", JsonPrimitive(true))
            if (skillMutation || outsideWorkspace) put("oneShot", JsonPrimitive(true))
        })).toString()
    }

    override suspend fun invocationArguments(sessionId: String, tool: String, args: JsonObject): JsonObject = operation {
        val current = namespace(sessionId)
        val values = BuiltinRealPathPaths.normalizeArguments(tool, args, current.ctx).toMutableMap()
        for (key in listOf("uuid", "parentUuid", "newParentUuid")) {
            val uuid = (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (uuid.isNotEmpty()) {
                val path = resolveUuid(sessionId, uuid) ?: fail("NOT_FOUND", "条目不属于当前会话")
                val entry = if (tool == "read_file" && (args["mode"] as? JsonPrimitive)?.content == "stat") {
                    statEntry(sessionId, path)
                } else entry(sessionId, path)
                values[key] = JsonPrimitive(entry?.uuid ?: fail("NOT_FOUND", "条目不存在"))
            }
        }
        JsonObject(values)
    }

    override suspend fun diffPrivate(sessionId: String, uuid: String, basisHash: String?): DiffResult? = operation {
        val current = namespace(sessionId)
        val path = resolveUuid(sessionId, uuid) ?: fail("NOT_FOUND", "条目不属于当前会话")
        val routed = route(current, path)
        if (routed.kind != BuiltinRealPathKind.PRIVATE) return@operation null
        val entry = workspace.getByMaterializedPath(current.root.uuid, routed.relativePath) ?: fail("NOT_FOUND", "私有文件不存在")
        files.diffFile(current.root.uuid, entry.uuid, basisHash).copy(uuid = uuid)
    }

    override suspend fun movePrivate(sessionId: String, source: String, destination: String, expectedHash: String?): ToolResult? = operation {
        val current = namespace(sessionId)
        val from = requireMutableTarget(sessionId, current, source)
        val to = requireMutableTarget(sessionId, current, destination)
        if (from.kind != BuiltinRealPathKind.PRIVATE || to.kind != BuiltinRealPathKind.PRIVATE) return@operation null
        val target = workspace.getByMaterializedPath(current.root.uuid, from.relativePath) ?: fail("NOT_FOUND", "源条目不存在")
        if (expectedHash != null && (target.isDirectory || target.hash != expectedHash)) fail("CONFLICT", "expected_sha256 与来源不一致")
        if (from.relativePath == to.relativePath) return@operation ToolResult("builtin_private_move", "目标路径未变化：${to.canonicalPath}")
        if (workspace.getByMaterializedPath(current.root.uuid, to.relativePath) != null) fail("NAME_EXISTS", "目标路径已存在，未执行移动")
        if (target.isDirectory && to.relativePath.startsWith(from.relativePath.trimEnd('/') + "/")) fail("INVALID_PATH", "不能移动到自身子目录")
        val parent = workspace.getByMaterializedPath(current.root.uuid, parent(to.relativePath)) ?: fail("NOT_FOUND", "目标目录不存在")
        if (!parent.isDirectory) fail("INVALID_PATH", "目标父条目不是目录")
        val name = to.relativePath.substringAfterLast('/')
        when (val renamed = workspace.relocate(current.root.uuid, target.uuid, parent.uuid, name, expectedName = target.name, expectedHash = expectedHash)) {
            is RenameResult.Success -> renamed.affectedTargets.forEach { affected ->
                try { indexEvents.publish(FileIndexEvent.Changed(current.root.uuid, affected.fileUuid, affected.targetHash, affected.targetEpoch)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* 待处理索引协调器负责补偿已提交的重命名。 */ }
            }
            is RenameResult.Conflict -> fail("CONFLICT", "来源名称已改变，请重新定位")
            RenameResult.NotFound -> fail("NOT_FOUND", "来源已不存在")
        }
        val moved = entry(sessionId, to.canonicalPath) ?: fail("NOT_FOUND", "移动后条目不可用")
        ToolResult("builtin_private_move", "已完成：${to.canonicalPath} [uuid=${moved.uuid}] [hash=${moved.hash}]")
    }

    override suspend fun sameMountedBackend(sessionId: String, source: String, destination: String): Boolean = operation {
        val current = namespace(sessionId)
        val from = route(current, source)
        val to = route(current, destination)
        when {
            from.kind == BuiltinRealPathKind.WORKSPACE && to.kind == BuiltinRealPathKind.WORKSPACE -> true
            from.kind == BuiltinRealPathKind.USER_SKILLS && to.kind == BuiltinRealPathKind.USER_SKILLS -> true
            from.kind == BuiltinRealPathKind.REAL_MOUNT && to.kind == BuiltinRealPathKind.REAL_MOUNT -> from.mount?.id == to.mount?.id
            from.kind == BuiltinRealPathKind.VIRTUAL_MOUNT && to.kind == BuiltinRealPathKind.VIRTUAL_MOUNT -> from.mount?.id == to.mount?.id
            from.kind == BuiltinRealPathKind.HOST_REAL_PATH && to.kind == BuiltinRealPathKind.HOST_REAL_PATH -> true
            else -> false
        }
    }

    override suspend fun moveMounted(sessionId: String, source: String, destination: String): Unit? = operation {
        val current = namespace(sessionId)
        val from = requireMutableTarget(sessionId, current, source)
        val to = requireMutableTarget(sessionId, current, destination)
        if (!sameMountedBackend(sessionId, from.canonicalPath, to.canonicalPath)) return@operation null
        if (from.kind == BuiltinRealPathKind.HOST_REAL_PATH) {
            hostBackend.move(from.canonicalPath, to.canonicalPath)
            return@operation Unit
        }
        val (srcActual, dstActual) = when (from.kind) {
            BuiltinRealPathKind.WORKSPACE -> from.relativePath to to.relativePath
            BuiltinRealPathKind.USER_SKILLS -> skillsOriginalPath(from.relativePath) to skillsOriginalPath(to.relativePath)
            BuiltinRealPathKind.REAL_MOUNT -> mountOriginalPath(requireNotNull(from.mount), from.relativePath) to mountOriginalPath(requireNotNull(to.mount), to.relativePath)
            BuiltinRealPathKind.VIRTUAL_MOUNT -> from.canonicalPath to to.canonicalPath
            else -> return@operation null
        }
        withOriginalBindings(current) {
            val bindings = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty()
            original.backend(sessionId, bindings).move(srcActual, dstActual)
        }
    }

    override suspend fun textMime(sessionId: String, path: String): String? = operation {
        val current = namespace(sessionId)
        val routed = route(current, path)
        when (routed.kind) {
            BuiltinRealPathKind.PRIVATE, BuiltinRealPathKind.HOST_REAL_PATH, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED -> "text/plain"
            BuiltinRealPathKind.VIRTUAL_MOUNT -> if (routed.mount?.isKnowledgeBaseSource == true) {
                withOriginalBindings(current) { original.entry(sessionId, routed.canonicalPath)?.mimeType }
            } else null
            else -> null
        }
    }

    override suspend fun isPathWritable(sessionId: String, path: String): Boolean = operation {
        val current = namespace(sessionId)
        val routed = route(current, path)
        when (routed.kind) {
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT, BuiltinRealPathKind.RUNTIME_DATA_SECRET, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED -> false
            BuiltinRealPathKind.VIRTUAL_MOUNT -> if (routed.mount?.isKnowledgeBaseSource == true) {
                withOriginalBindings(current) { knowledgeManager?.isKnowledgePathWritable(sessionId, routed.canonicalPath) ?: false }
            } else routed.mount?.let { !it.isSftpRemoteSource && !it.readOnly } == true
            BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.isWritable(routed.canonicalPath)
            else -> try {
                requireMutableTarget(sessionId, current, routed.canonicalPath)
                true
            } catch (failure: ExternalFolderException) {
                if (failure.code !in setOf("READ_ONLY", "ACCESS_DENIED", "PROTECTED_RUNTIME_DATA", "INVALID_PATH")) throw failure
                false
            } catch (_: com.promenar.infinity.startup.StartupIsolationException) {
                false
            }
        }
    }

    private suspend fun namespace(sessionId: String): Namespace {
        if (sessionId.isBlank()) fail("NOT_FOUND", "会话不存在")
        val root = sessions?.getById(sessionId)?.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
            ?: workspace.ensureSessionRoot(sessionId)
        val current = sessionWorkspace(sessionId)
        val privateDir = File(root.physicalRootPath)
        val primaryDir = (current as? SessionWorkspace.Directory)?.root ?: privateDir
        val visibleMounts = original.visible(sessionId)
        val realMounts = visibleMounts.filter { it.sourceType == "real_path" && !it.isUserSkills }
            .map { it to File(it.sourceId) }
        val virtualMounts = visibleMounts.filter { it.isKnowledgeBaseSource || it.sourceType == "saf_tree" || it.isSftpRemoteSource }
        val ctx = BuiltinRealPathContext(
            primaryRoot = primaryDir,
            primaryIsDirectory = current is SessionWorkspace.Directory,
            sessionPrivateRoot = privateDir,
            userSkillsRoot = userSkillsDir,
            appDataDir = appDataDir,
            realMounts = realMounts,
            virtualMounts = virtualMounts,
        )
        val mainIdentity = when (current) {
            SessionWorkspace.Private -> "private:${ctx.privatePath}"
            is SessionWorkspace.Directory -> "${current.workspace.id}:${current.workspace.kind}:${ctx.primaryPath}"
            is SessionWorkspace.Unavailable -> "unavailable:${current.workspaceId}"
        }
        val identity = Sha256Utils.hash("builtin-real|$sessionId|${root.uuid}|${root.hash}|$mainIdentity")
        val mount = ExternalMount("builtin-real:$identity", "", "builtin_real_session", sessionId, sessionId, 0)
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound != null && bound[""] != mount.id) fail("CONFLICT", "当前会话工作区身份已改变，请重新审批")
        return Namespace(root, current, ctx, mount)
    }

    private fun route(current: Namespace, path: String): BuiltinRealPathRoute =
        BuiltinRealPathPaths.route(path, current.ctx).also {
            if (it.kind == BuiltinRealPathKind.WORKSPACE && current.workspace is SessionWorkspace.Unavailable) {
                fail("UNAVAILABLE", "会话主工作区不可用，请检查目录与授权")
            }
        }

    private suspend fun requireMutableTarget(sessionId: String, current: Namespace, path: String): BuiltinRealPathRoute {
        val routed = route(current, path)
        if (routed.mount?.isSftpRemoteSource == true) throw sftpGenericMutationDenied()
        when (routed.kind) {
            BuiltinRealPathKind.RUNTIME_DATA_SECRET ->
                fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问：${routed.canonicalPath}")
            BuiltinRealPathKind.RUNTIME_DATA_PROTECTED ->
                fail("PROTECTED_RUNTIME_DATA", "应用关键运行时数据目录仅开放只读自诊断，禁止通过文件工具修改或删除：${routed.canonicalPath}")
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT ->
                fail("INVALID_PATH", "不能修改挂载根目录 /mnt")
            else -> Unit
        }
        val canonical = routed.canonicalPath
        val protectedRoots = setOf(
            "/", current.ctx.primaryPath, current.ctx.privatePath,
            current.ctx.userSkillsPath, current.ctx.appDataPath,
        ) + current.ctx.normalizedRealMounts.map { it.second } + current.ctx.virtualMounts.map { "/mnt/${it.alias}" }
        if (canonical in protectedRoots) {
            fail("INVALID_PATH", "不能直接删除或覆盖工作区、挂载或应用根目录：$canonical")
        }
        if (routed.kind in setOf(BuiltinRealPathKind.REAL_MOUNT, BuiltinRealPathKind.VIRTUAL_MOUNT)) {
            routed.mount?.requireWritable()
        }
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(
            sessionId = sessionId,
            rootId = current.root.uuid,
            path = if (routed.kind == BuiltinRealPathKind.VIRTUAL_MOUNT) null else canonical,
        )
        return routed
    }

    private suspend fun stat(sessionId: String, current: Namespace, routed: BuiltinRealPathRoute, fillHash: Boolean): ExternalFolderEntry? {
        suspend fun originalEntry(actual: String) =
            if (!fillHash && original is ExternalFolderManager) original.statEntry(sessionId, actual)
            else original.entry(sessionId, actual)
        return when (routed.kind) {
            BuiltinRealPathKind.RUNTIME_DATA_SECRET -> fail("ACCESS_DENIED", "加密密钥存储文件不可通过文件工具访问")
            BuiltinRealPathKind.VIRTUAL_MOUNTS_ROOT -> ExternalFolderEntry("/mnt", "mnt", true, 0, 0)
            BuiltinRealPathKind.PRIVATE -> privateBackend(current).stat(routed.relativePath)?.copy(path = routed.canonicalPath)
            BuiltinRealPathKind.WORKSPACE -> withOriginalBindings(current) { originalEntry(routed.relativePath) }?.let {
                ExternalFolderEntry(routed.canonicalPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            BuiltinRealPathKind.USER_SKILLS -> withOriginalBindings(current) { originalEntry(skillsOriginalPath(routed.relativePath)) }?.let {
                ExternalFolderEntry(routed.canonicalPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            BuiltinRealPathKind.REAL_MOUNT -> withOriginalBindings(current) { originalEntry(mountOriginalPath(requireNotNull(routed.mount), routed.relativePath)) }?.let {
                ExternalFolderEntry(routed.canonicalPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            BuiltinRealPathKind.VIRTUAL_MOUNT -> withOriginalBindings(current) { originalEntry(routed.canonicalPath) }?.let {
                ExternalFolderEntry(routed.canonicalPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            BuiltinRealPathKind.RUNTIME_DATA_PROTECTED, BuiltinRealPathKind.HOST_REAL_PATH -> hostBackend.stat(routed.canonicalPath)
        }
    }

    private suspend fun writePrivate(sessionId: String, current: Namespace, relativePath: String, bytes: ByteArray, expected: String?, createOnly: Boolean) {
        val existing = workspace.getByMaterializedPath(current.root.uuid, relativePath)
        if (createOnly) {
            if (existing != null) fail("NAME_EXISTS", "同名文件已存在")
            val parent = workspace.getByMaterializedPath(current.root.uuid, parent(relativePath)) ?: fail("NOT_FOUND", "父目录不存在")
            val mime = try {
                WorkspaceTextContentPolicy().validate(relativePath.substringAfterLast('/'), "text/plain", bytes, WorkspaceTextOperation.WRITE)
                "text/plain"
            } catch (_: WorkspaceTextPolicyException) { externalSnapshotMime(relativePath.substringAfterLast('/')) }
            val entry = workspace.createFileInWorkspaceStreaming(
                current.root.uuid, UUID.randomUUID().toString(), relativePath.substringAfterLast('/'), mime,
                parent.uuid, relativePath, bytes.size.toLong().coerceAtLeast(1),
            ) { it.write(bytes) }
            workspace.confirmCreatedEntry(current.root.uuid, entry.uuid)
            if (mime in com.promenar.infinity.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) {
                indexEvents.publish(FileIndexEvent.Changed(current.root.uuid, entry.uuid, entry.hash, entry.updatedAt.coerceAtLeast(1)))
            }
            return
        }
        if (existing == null) fail("NOT_FOUND", "文件不存在，请先创建")
        if (existing.isDirectory) fail("IS_DIRECTORY", "目标是目录")
        if (expected == null) fail("CONFLICT", "写入私有文件必须提供当前 sha256")
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) fail("INVALID_ENCODING", "私有文件覆盖写入要求 UTF-8 文本")
        when (files.writeFileAtomicExact(current.root.uuid, existing.uuid, text, sessionId, expected)) {
            is WriteResult.Success -> Unit
            is WriteResult.Conflict -> fail("CONFLICT", "文件版本已经改变")
            WriteResult.NotFound -> fail("NOT_FOUND", "文件不存在")
        }
    }

    private suspend fun <T> withOriginalBindings(current: Namespace, action: suspend () -> T): T {
        val ids = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty().toMutableMap()
        when (val selected = current.workspace) {
            is SessionWorkspace.Directory -> ids[""] = ExternalMountPaths.workspaceMountId(selected.workspace.id)
            is SessionWorkspace.Unavailable -> ids[""] = ExternalMountPaths.workspaceMountId(selected.workspaceId)
            SessionWorkspace.Private -> ids.remove("")
        }
        original.visible(current.mount.sessionId).forEach { ids[it.alias] = it.id }
        return withContext(ExternalMountOperationBindings(ids)) { action() }
    }

    private fun fromOriginalVirtual(current: Namespace, virtualPath: String): String = when {
        virtualPath == "/skills" || virtualPath.startsWith("/skills/") ->
            BuiltinRealPathPaths.joinPath(current.ctx.userSkillsPath, virtualPath.removePrefix("/skills").ifEmpty { "/" })
        virtualPath.startsWith("/mnt/") -> {
            val rest = virtualPath.removePrefix("/mnt/")
            val alias = rest.substringBefore('/')
            val rel = if ('/' in rest) "/" + rest.substringAfter('/') else "/"
            current.ctx.normalizedRealMounts.singleOrNull { it.first.alias == alias }?.let { (_, root) ->
                BuiltinRealPathPaths.joinPath(root, rel)
            } ?: virtualPath
        }
        else -> BuiltinRealPathPaths.joinPath(current.ctx.primaryPath, virtualPath)
    }

    private fun skillsOriginalPath(relative: String): String =
        "/skills" + relative.takeUnless { it == "/" }.orEmpty()

    private fun mountOriginalPath(mount: ExternalMount, relative: String): String =
        "/mnt/${mount.alias}" + relative.takeUnless { it == "/" }.orEmpty()

    private fun privateBackend(current: Namespace) = PrivateWorkspaceTransferBackend(workspace, current.root.uuid, indexEvents, privateReader)
    private fun uuid(current: Namespace, canonicalPath: String): String =
        "external:${current.mount.id}:" + Base64.getUrlEncoder().withoutPadding().encodeToString(canonicalPath.toByteArray(Charsets.UTF_8))

    private fun remember(current: Namespace, routed: BuiltinRealPathRoute, bytes: ByteArray, generation: Long) {
        if (routed.kind !in setOf(BuiltinRealPathKind.PRIVATE, BuiltinRealPathKind.HOST_REAL_PATH, BuiltinRealPathKind.RUNTIME_DATA_PROTECTED)) return
        versions.remember(versionKey(current, routed, Sha256Utils.hash(bytes)), bytes, generation)
    }

    private fun versionKey(current: Namespace, routed: BuiltinRealPathRoute, hash: String): LinuxVersionSnapshots.Key =
        if (routed.kind == BuiltinRealPathKind.PRIVATE) {
            LinuxVersionSnapshots.Key("${current.root.uuid}|${current.root.hash}", routed.relativePath, hash, privateRoot = current.root.uuid)
        } else {
            LinuxVersionSnapshots.Key(current.mount.id, routed.canonicalPath, hash, rootfs = true)
        }

    private fun forgetVersions(current: Namespace, routed: BuiltinRealPathRoute) {
        when (routed.kind) {
            BuiltinRealPathKind.PRIVATE -> versions.forgetPrivate(current.root.uuid, routed.relativePath)
            BuiltinRealPathKind.HOST_REAL_PATH -> versions.forgetRootfs(routed.canonicalPath)
            else -> Unit
        }
    }

    private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
    private fun child(base: String, name: String) = BuiltinRealPathPaths.joinPath(base, "/$name")
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)

    companion object {
        private const val VERSION_BYTES = 1024L * 1024L
    }
}
