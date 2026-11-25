package com.promenar.infinity.data.production

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.copyExternalStream
import com.promenar.infinity.data.storage.externalStreamHash
import com.promenar.infinity.data.storage.namespaceBindings
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.data.workspace.LinuxWorkspaceLocation
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 复用现有工作区后端和带版本条件的文件写入设施。 */
class DefaultProductionWorkspaceFiles(
    private val workspace: IWorkspaceRepository,
    private val fileOperations: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val media: WorkspaceMediaResolver,
    private val modeFor: suspend (String) -> SessionRunMode?,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
) : ProductionWorkspaceFiles, PrevisFileAccess {
    override suspend fun normalizeRelativePath(sessionId: String, rawPath: String, projectRoot: String?): String = withContext(Dispatchers.IO) {
        val linux = modeFor(sessionId) == SessionRunMode.LINUX
        val current = if (linux) sessionWorkspace(sessionId) else null
        val directory = when (current) {
            is SessionWorkspace.Directory -> LinuxWorkspaceLocation.workingDirectory(current.workspace)
            is SessionWorkspace.Unavailable -> {
                require(rawPath.startsWith("${LinuxSessionPaths.PRIVATE_ROOT}/") || rawPath.startsWith("session/") ||
                    privateProductionLocalPath(rawPath) != null || rawPath.removePrefix("/").startsWith("mnt/")) {
                    "当前会话工作区不可用，请检查目录和授权。"
                }
                LinuxSessionPaths.WORKSPACE_ROOT
            }
            else -> null
        }
        val mountsForPath = if (rawPath.removePrefix("/").startsWith("mnt/")) {
            mounts.visible(sessionId).filter { it.unavailableReason == null && (!linux || it.sourceType == "real_path") }.map { it.alias }.toSet()
        } else emptySet()
        normalizeProductionWorkspacePath(rawPath, linux, directory, projectRoot, mountsForPath)
    }

    private fun backendPath(path: String): String =
        privateProductionLocalPath(path) ?: "/$path"

    private data class Target(val backend: ExternalFolderBackend, val rootUuid: String?, val bindings: Map<String, String>)
    private suspend fun target(sessionId: String, path: String): Target {
        ProductionManifestCodec.validatePath(path)
        val virtualPath = "/$path"
        val bindings = mounts.namespaceBindings(sessionId)
        val mounted = privateProductionLocalPath(path) == null && ExternalMountPaths.aliasOf(virtualPath, ExternalMountPaths.WORKSPACE_ALIAS in bindings) != null
        if (mounted) return Target(mounts.backend(sessionId, bindings), null, bindings)
        val root = workspace.ensureSessionRoot(sessionId)
        return Target(PrivateWorkspaceTransferBackend(workspace, root.uuid), root.uuid, bindings)
    }
    override suspend fun read(sessionId: String, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val virtualPath = backendPath(path)
            if (target.backend.stat(virtualPath) == null) null else target.backend.read(virtualPath, 16L * 1024 * 1024)
        }
    }
    override suspend fun lastModified(sessionId: String, path: String): Long? = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            target.backend.stat(backendPath(path))?.lastModified?.takeIf { it > 0L }
        }
    }
    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?): Unit = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val virtualPath = backendPath(path)
            var parent = ""
            virtualPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach { part ->
                parent += "/$part"
                val entry = target.backend.stat(parent)
                if (entry == null) target.backend.createDirectory(parent) else require(entry.isDirectory) { "$parent 不是目录。" }
            }
            if (target.rootUuid == null) {
                target.backend.write(virtualPath, bytes, expectedSha256, expectedSha256 == null)
            } else if (expectedSha256 == null) {
                target.backend.write(virtualPath, bytes, null, true)
                (target.backend as PrivateWorkspaceTransferBackend).confirmCreated()
            } else {
                val entry = workspace.getByMaterializedPath(target.rootUuid, virtualPath) ?: throw IllegalArgumentException("清单文件已不存在。")
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                when (val result = fileOperations.writeProductionManifestAtomic(target.rootUuid, entry.uuid, text, sessionId, expectedSha256)) {
                    is WriteResult.Success -> Unit
                    is WriteResult.Conflict -> throw IllegalArgumentException(result.message)
                    WriteResult.NotFound -> throw IllegalArgumentException("清单文件已不存在。")
                }
            }
            Unit
        }
    }

    override suspend fun replaceBytes(sessionId: String, path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        replaceWith(sessionId, path) { backend, virtualPath, rootUuid ->
            backend.write(virtualPath, bytes, null, true)
            if (rootUuid != null) (backend as PrivateWorkspaceTransferBackend).confirmCreated()
        }
    }

    override suspend fun replaceFile(sessionId: String, path: String, file: java.io.File): Unit = withContext(Dispatchers.IO) {
        val length = file.length().coerceAtLeast(0L)
        replaceWith(sessionId, path) { backend, virtualPath, rootUuid ->
            backend.writeStream(virtualPath, length, createOnly = true) { output ->
                file.inputStream().use { input -> copyExternalStream(input, output) }
            }
            if (rootUuid != null) (backend as PrivateWorkspaceTransferBackend).confirmCreated()
        }
    }

    private suspend fun replaceWith(
        sessionId: String,
        path: String,
        writeNew: suspend (ExternalFolderBackend, String, String?) -> Unit,
    ) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val virtualPath = backendPath(path)
            var parent = ""
            virtualPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach { part ->
                parent += "/$part"
                val entry = target.backend.stat(parent)
                if (entry == null) target.backend.createDirectory(parent) else require(entry.isDirectory) { "$parent 不是目录。" }
            }
            val existing = target.backend.stat(virtualPath)
            if (existing != null) {
                require(!existing.isDirectory) { "$virtualPath 是目录，不能覆盖写入。" }
                val oldHash = target.backend.readStream(virtualPath, existing.size.coerceAtLeast(16L * 1024 * 1024)) {
                    externalStreamHash(it)
                }
                target.backend.deleteForTransfer(virtualPath, existing, oldHash)
            }
            writeNew(target.backend, virtualPath, target.rootUuid)
        }
    }
    suspend fun publishChainMedia(sessionId: String, path: String, file: java.io.File, cacheDir: java.io.File): Unit = withContext(Dispatchers.IO) {
        val target = target(sessionId, path)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            WorkspaceChainOutputStore(cacheDir).publish(target.backend, backendPath(path).removePrefix("/"), file) {
                (target.backend as? PrivateWorkspaceTransferBackend)?.confirmCreated()
            }
        }
    }
    suspend fun listProductionMedia(sessionId: String, production: Production): List<String> = withContext(Dispatchers.IO) {
        val target = target(sessionId, production.rootPath)
        withContext(ExternalMountOperationBindings(target.bindings)) {
            val found = mutableListOf<String>()
            suspend fun visit(path: String, depth: Int) {
                require(depth <= 32 && found.size <= 100_000) { "制作媒体目录超过扫描上限。" }
                val virtualPath = backendPath(path)
                val entry = target.backend.stat(virtualPath) ?: return
                if (!entry.isDirectory) { if (mediaKindFromPath(path) != null) found += path; return }
                target.backend.list(virtualPath).forEach { child ->
                    require(child.path.startsWith("$virtualPath/")) { "媒体目录返回了越界条目。" }
                    val name = child.path.removePrefix("$virtualPath/")
                    require('/' !in name) { "媒体目录返回了越界条目。" }
                    val next = "$path/$name"
                    ProductionManifestCodec.validatePath(next)
                    visit(next, depth + 1)
                }
            }
            val directories = listOf("${production.rootPath}/assets", "${production.rootPath}/takes") + production.seasons.flatMap { it.episodes }.map { "${production.rootPath}/${it.id}/takes" }
            directories.distinct().forEach { visit(it, 0) }
            found
        }
    }
    override suspend fun isImageOrVideo(sessionId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        ProductionManifestCodec.validatePath(path)
        val result = media.resolve(sessionId, media.productionPath(sessionId, path))
        result is MediaResolution.Available && isProductionMediaKind(result.ref.kind)
    }

    override suspend fun findManifestPaths(sessionId: String): List<String> = withContext(Dispatchers.IO) {
        val bindings = mounts.namespaceBindings(sessionId)
        val rooted = ExternalMountPaths.WORKSPACE_ALIAS in bindings
        val backend = if (rooted) {
            mounts.backend(sessionId, bindings)
        } else {
            val root = workspace.ensureSessionRoot(sessionId)
            PrivateWorkspaceTransferBackend(workspace, root.uuid)
        }
        val found = mutableListOf<String>()
        var visited = 0
        withContext(ExternalMountOperationBindings(bindings)) {
            suspend fun visit(virtualDir: String, depth: Int) {
                if (depth > MANIFEST_SCAN_MAX_DEPTH || visited >= MANIFEST_SCAN_MAX_ENTRIES || found.size >= MANIFEST_SCAN_MAX_RESULTS) return
                val children = runCatching { backend.list(virtualDir) }.getOrNull().orEmpty()
                for (child in children) {
                    if (visited >= MANIFEST_SCAN_MAX_ENTRIES || found.size >= MANIFEST_SCAN_MAX_RESULTS) return
                    visited++
                    val relative = child.path.trimStart('/')
                    if (relative.isBlank()) continue
                    val name = relative.substringAfterLast('/')
                    if (child.isDirectory) {
                        if (name.startsWith(".") || name in MANIFEST_SCAN_IGNORED_DIRS) continue
                        visit("/$relative", depth + 1)
                    } else if (name == "production.json" && '/' in relative) {
                        if (runCatching { ProductionManifestCodec.validatePath(relative) }.isSuccess) {
                            found += relative
                        }
                    }
                }
            }
            visit("/", 0)
        }
        found.distinct().sorted()
    }
}

private const val MANIFEST_SCAN_MAX_DEPTH = 6
private const val MANIFEST_SCAN_MAX_ENTRIES = 1_500
private const val MANIFEST_SCAN_MAX_RESULTS = 24
private val MANIFEST_SCAN_IGNORED_DIRS = setOf(
    "build", "node_modules", "__pycache__", "venv", "dist", "out", "target", "mnt", "skills",
)

internal fun isProductionMediaKind(kind: MediaKind): Boolean = kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)
