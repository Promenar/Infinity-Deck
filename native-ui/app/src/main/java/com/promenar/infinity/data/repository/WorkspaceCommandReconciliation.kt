package com.promenar.infinity.data.repository

import java.io.File
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 命令直接改动工作区物理目录后的索引对账。
 *
 * 以前快照或上次成功对账的持久身份基线计算新增、修改、删除与移动。
 * 只对 size、mtime、inode 有变化或缺少基线的文件重算 SHA-256；唯一 fileKey 换路径时保留条目身份。
 * 保留路径（`.infinity*`、`.recycle_bin`、未入索引的顶层 `mnt`）、符号链接与特殊文件不写入索引，只作为警告返回。
 */
enum class WorkspaceScanKind { FILE, DIRECTORY, UNSUPPORTED }

data class WorkspaceScanNode(
    val kind: WorkspaceScanKind,
    val sizeBytes: Long,
    val modifiedNanos: Long,
    val fileKey: String?,
)

/** 键为以 `/` 开头的虚拟路径。 */
data class WorkspaceTreeSnapshot(
    val nodes: Map<String, WorkspaceScanNode>,
    val reserved: Map<String, WorkspaceScanNode>,
)

fun interface WorkspaceTreeScanner {
    fun scan(root: Path): WorkspaceTreeSnapshot

    /** 事务完成后仅刷新索引涉及的路径，不遍历整棵树。 */
    fun scanPaths(root: Path, paths: List<String>): Map<String, WorkspaceScanNode> =
        scan(root).nodes.filterKeys { it in paths }
}

internal fun isReservedWorkspaceName(name: String): Boolean =
    com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.isReservedName(name)

/**
 * `.git` 目录之下的路径（不含 `.git` 目录本身）：照常入索引以保持与磁盘一致，
 * 但不发布向量化事件（仓库元数据对检索无用，config 中的远端地址可能带令牌），命令结果也只给计数。
 */
fun isGitInternalWorkspacePath(path: String): Boolean = "/.git/" in path

/** 现有文件工具拒绝的名称：命令可以创建，但不进入索引。 */
internal fun isIndexableWorkspaceName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

/** 经 SecureDirectoryStream 逐级打开目录且不跟随符号链接，遍历期间目录被替换为链接也不会越出工作区。 */
object SecureWorkspaceTreeScanner : WorkspaceTreeScanner {
    override fun scan(root: Path): WorkspaceTreeSnapshot {
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink || !attributes.isDirectory) throw SecurityException("工作区根目录身份无效")
        val stream = Files.newDirectoryStream(root)
        val secure = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("当前文件系统不支持 SecureDirectoryStream，拒绝遍历工作区")
        }
        val nodes = LinkedHashMap<String, WorkspaceScanNode>()
        val reserved = LinkedHashMap<String, WorkspaceScanNode>()
        secure.use { walk(it, "", inReserved = false, nodes, reserved) }
        return WorkspaceTreeSnapshot(nodes, reserved)
    }

    override fun scanPaths(root: Path, paths: List<String>): Map<String, WorkspaceScanNode> {
        val stream = Files.newDirectoryStream(root)
        val secure = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("当前文件系统不支持安全路径属性读取")
        }
        return secure.use { directory ->
            paths.associateWith { path -> readPath(directory, root, path.trim('/').split('/'), 0) }
        }
    }

    private fun readPath(
        directory: SecureDirectoryStream<Path>, root: Path, parts: List<String>, offset: Int,
    ): WorkspaceScanNode {
        val text = parts[offset]
        require(isIndexableWorkspaceName(text) && !isReservedWorkspaceName(text))
        val name = root.fileSystem.getPath(text)
        if (offset < parts.lastIndex) {
            return directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                readPath(child, root, parts, offset + 1)
            }
        }
        return scanNode(directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes())
    }

    private fun scanNode(attributes: BasicFileAttributes): WorkspaceScanNode {
        val kind = when {
            attributes.isSymbolicLink || attributes.isOther -> WorkspaceScanKind.UNSUPPORTED
            attributes.isDirectory -> WorkspaceScanKind.DIRECTORY
            attributes.isRegularFile -> WorkspaceScanKind.FILE
            else -> WorkspaceScanKind.UNSUPPORTED
        }
        return WorkspaceScanNode(kind, if (kind == WorkspaceScanKind.FILE) attributes.size() else 0L,
            attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey()?.toString())
    }

    private fun walk(
        directory: SecureDirectoryStream<Path>,
        prefix: String,
        inReserved: Boolean,
        nodes: MutableMap<String, WorkspaceScanNode>,
        reserved: MutableMap<String, WorkspaceScanNode>,
    ) {
        val names = directory.map { it.fileName }.sortedBy { it.toString() }
        for (name in names) {
            val text = name.toString()
            val path = "$prefix/$text"
            val attributes = try {
                directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            } catch (_: NoSuchFileException) {
                continue
            }
            val isReserved = inReserved || isReservedWorkspaceName(text)
            val kind = when {
                !isIndexableWorkspaceName(text) -> WorkspaceScanKind.UNSUPPORTED
                attributes.isSymbolicLink || attributes.isOther -> WorkspaceScanKind.UNSUPPORTED
                attributes.isDirectory -> WorkspaceScanKind.DIRECTORY
                attributes.isRegularFile -> WorkspaceScanKind.FILE
                else -> WorkspaceScanKind.UNSUPPORTED
            }
            val node = WorkspaceScanNode(
                kind = kind,
                sizeBytes = if (kind == WorkspaceScanKind.FILE) attributes.size() else 0L,
                modifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                fileKey = attributes.fileKey()?.toString(),
            )
            (if (isReserved) reserved else nodes)[path] = node
            if (kind != WorkspaceScanKind.DIRECTORY) continue
            val child = try {
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                continue
            } catch (_: NotDirectoryException) {
                continue
            }
            child.use { walk(it, path, isReserved, nodes, reserved) }
        }
    }
}

/** 数据库中的活动条目（不含根、回收区与系统条目）。 */
data class IndexedWorkspaceNode(
    val uuid: String,
    val parentUuid: String?,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val hash: String,
)

data class PlannedWorkspaceInsert(val path: String, val isDirectory: Boolean, val sizeBytes: Long, val hash: String)
data class PlannedWorkspaceUpdate(val uuid: String, val path: String, val sizeBytes: Long, val hash: String)
data class PlannedWorkspaceMove(val uuid: String, val from: String, val to: String)

data class WorkspaceReconcilePlan(
    val inserts: List<PlannedWorkspaceInsert>,
    val updates: List<PlannedWorkspaceUpdate>,
    val moves: List<PlannedWorkspaceMove>,
    val deletes: List<IndexedWorkspaceNode>,
    /** 命令新建或改动的保留路径。 */
    val reservedChanges: List<String>,
    /** 命令新建或改动的符号链接、特殊文件与无效名称。 */
    val unsupported: List<String>,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && moves.isEmpty() && deletes.isEmpty()
}

object WorkspaceReconcilePlanner {
    /**
     * @param before 命令前快照或有效持久基线；缺失时全部文件重算哈希且不识别移动。
     * @param hash 读取当前文件的 SHA-256；文件已消失时返回 null。
     */
    fun plan(
        indexed: List<IndexedWorkspaceNode>,
        before: WorkspaceTreeSnapshot?,
        after: WorkspaceTreeSnapshot,
        hash: (String) -> String?,
    ): WorkspaceReconcilePlan {
        val reserved = sortedSetOf<String>()
        val indexedPaths = indexed.mapTo(HashSet()) { it.path }
        fun allowed(path: String): Boolean = path in indexedPaths ||
            (path != "/mnt" && !path.startsWith("/mnt/") && path != "/skills" && !path.startsWith("/skills/"))
        val available = after.nodes.filter { (path, node) ->
            if (!allowed(path)) reserved += path
            node.kind != WorkspaceScanKind.UNSUPPORTED && allowed(path)
        }
        fun uniqueKeys(nodes: Map<String, WorkspaceScanNode>) = nodes.entries
            .filter { it.value.fileKey != null && it.value.kind != WorkspaceScanKind.UNSUPPORTED }
            .groupBy { it.value.fileKey!! }.filterValues { it.size == 1 }.mapValues { it.value.single().key }
        val priorByKey = uniqueKeys(before?.nodes.orEmpty())
        val currentByKey = uniqueKeys(after.nodes)
        val destinations = mutableMapOf<String, String>()
        val claimed = mutableSetOf<String>()
        // 先按身份分配全部目标，再按原路径兜底；原名重建和路径互换不会抢走移动条目的 UUID。
        for (entry in indexed) {
            val prior = before?.nodes?.get(entry.path) ?: continue
            val key = prior.fileKey ?: continue
            val target = currentByKey[key]?.takeIf { priorByKey[key] == entry.path } ?: continue
            val node = available[target] ?: continue
            if ((prior.kind == WorkspaceScanKind.DIRECTORY) != entry.isDirectory ||
                (node.kind == WorkspaceScanKind.DIRECTORY) != entry.isDirectory) continue
            if (claimed.add(target)) destinations[entry.uuid] = target
        }
        for (entry in indexed) {
            if (entry.uuid in destinations) continue
            val node = available[entry.path] ?: continue
            if ((node.kind == WorkspaceScanKind.DIRECTORY) == entry.isDirectory && claimed.add(entry.path)) {
                destinations[entry.uuid] = entry.path
            }
        }
        val kept = mutableSetOf<String>()
        val removed = mutableListOf<IndexedWorkspaceNode>()
        val moves = mutableListOf<PlannedWorkspaceMove>()
        val updates = mutableListOf<PlannedWorkspaceUpdate>()
        for (entry in indexed.sortedBy { it.path }) {
            val path = destinations[entry.uuid]
            val node = path?.let(available::get)
            if (node == null) { removed += entry; continue }
            if (!entry.isDirectory && (before?.nodes?.get(entry.path) != node || node.sizeBytes != entry.sizeBytes)) {
                val current = hash(path)
                if (current == null) { removed += entry; continue }
                if (current != entry.hash || node.sizeBytes != entry.sizeBytes) {
                    updates += PlannedWorkspaceUpdate(entry.uuid, path, node.sizeBytes, current)
                }
            }
            kept += path
            if (entry.path != path) moves += PlannedWorkspaceMove(entry.uuid, entry.path, path)
        }
        val inserts = mutableListOf<PlannedWorkspaceInsert>()
        for (path in available.keys.filter { it !in kept }.sortedWith(compareBy({ it.count { char -> char == '/' } }, { it }))) {
            val node = available.getValue(path)
            if (node.kind == WorkspaceScanKind.DIRECTORY) {
                inserts += PlannedWorkspaceInsert(path, isDirectory = true, sizeBytes = 0L, hash = "")
            } else {
                val current = hash(path) ?: continue
                inserts += PlannedWorkspaceInsert(path, isDirectory = false, sizeBytes = node.sizeBytes, hash = current)
            }
        }

        val unsupported = after.nodes.filter { (path, node) ->
            node.kind == WorkspaceScanKind.UNSUPPORTED && before?.nodes?.get(path) != node
        }.keys.sorted()
        if (before != null) {
            (before.reserved.keys + after.reserved.keys).filterTo(reserved) { path ->
                before.reserved[path] != after.reserved[path]
            }
        }
        return WorkspaceReconcilePlan(
            inserts = inserts,
            updates = updates,
            moves = moves,
            deletes = removed,
            reservedChanges = reserved.toList(),
            unsupported = unsupported,
        )
    }
}

/** 对账结果中返回给模型的路径变化；目录整体删除或移动时只列最上层。 */
data class WorkspaceChangeReport(
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val moved: List<Pair<String, String>> = emptyList(),
    val reservedChanges: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty() && moved.isEmpty()

    companion object {
        fun from(plan: WorkspaceReconcilePlan): WorkspaceChangeReport {
            val removedPaths = plan.deletes.map { it.path }.toSet()
            val movedPairs = plan.moves.associate { it.from to it.to }
            return WorkspaceChangeReport(
                added = plan.inserts.map { it.path },
                modified = plan.updates.map { it.path },
                removed = removedPaths.filter { path -> parentPath(path) !in removedPaths }.sorted(),
                moved = plan.moves.filter { move ->
                    val parent = parentPath(move.from)
                    movedPairs[parent] != parentPath(move.to)
                }.map { it.from to it.to },
                reservedChanges = plan.reservedChanges,
                unsupported = plan.unsupported,
            )
        }
    }
}

internal fun parentPath(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

/** 命令直接在工作区物理根内运行的宿主：运行期间独占工作区写锁，结束后（含失败与取消）对账索引。 */
interface WorkspaceCommandHost {
    suspend fun <T> runCommandInWorkspace(
        workspaceRootUuid: String,
        sessionId: String?,
        block: suspend (physicalRoot: File) -> T,
    ): WorkspaceCommandOutcome<T>
}

/** 对账失败时 [changes] 为 null，[reconcileError] 给出不含物理路径的原因。 */
data class WorkspaceCommandOutcome<T>(
    val value: T,
    val changes: WorkspaceChangeReport?,
    val reconcileError: String? = null,
)

/**
 * 命令前保留根身份快照；结束后在同一目录身份上恢复FD属性或兼容旧身份标记。
 * 根目录被替换时拒绝恢复，旧标记写回不跟随符号链接。
 */
internal class WorkspaceRootIdentityGuard private constructor(
    private val root: Path,
    private val rootKey: Any?,
    private val originals: Map<String, ByteArray?>,
    private val restorePrivateIdentity: () -> Boolean,
) {
    /** @return 是否写回了标记。 */
    fun restoreIfChanged(): Boolean {
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink || !attributes.isDirectory || attributes.fileKey() != rootKey) {
            throw SecurityException("工作区根目录在命令运行期间被替换")
        }
        val privateRestored = restorePrivateIdentity()
        var restored = privateRestored
        for ((name, bytes) in originals) {
            val marker = root.resolve(name)
            val current = runCatching {
                Files.readAttributes(marker, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (bytes == null && current == null || bytes != null && current != null && current.isRegularFile &&
                runCatching { Files.readAllBytes(marker) }.getOrNull()?.contentEquals(bytes) == true) continue
            if (current != null) {
                if (current.isDirectory) throw SecurityException("工作区根身份标记被替换为目录，无法自动恢复")
                Files.delete(marker)
            }
            if (bytes != null) Files.write(marker, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            restored = true
        }
        return restored
    }

    companion object {
        fun capture(root: Path, restorePrivateIdentity: () -> Boolean = { false }): WorkspaceRootIdentityGuard {
            val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val originals = com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.rootIdentityNames.associateWith { name ->
                val marker = root.resolve(name)
                runCatching {
                    val markerAttributes = Files.readAttributes(marker, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (markerAttributes.isRegularFile) Files.readAllBytes(marker) else null
                }.getOrNull()
            }
            return WorkspaceRootIdentityGuard(root, attributes.fileKey(), originals, restorePrivateIdentity)
        }
    }
}

/** 命令新增文件的 MIME：与文件工具和索引管线认可的类型对齐，未知扩展名的小文件按 UTF-8 文本嗅探。 */
internal object WorkspaceCommandMimeTypes {
    const val SNIFF_LIMIT_BYTES = 256L * 1024L

    fun guess(name: String, sizeBytes: Long, read: () -> ByteArray?): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        when (extension) {
            "md", "markdown" -> return "text/markdown"
            "csv" -> return "text/csv"
            "json" -> return "application/json"
            "html", "htm" -> return "text/html"
            "pdf" -> return "application/pdf"
            "doc" -> return "application/msword"
            "docx" -> return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
        if (WorkspaceTextContentPolicy.isTextExtension(extension)) return "text/plain"
        runCatching { URLConnection.guessContentTypeFromName(name) }.getOrNull()?.let { return it }
        if (extension.isEmpty() || sizeBytes <= SNIFF_LIMIT_BYTES) {
            val bytes = if (sizeBytes <= SNIFF_LIMIT_BYTES) read() else null
            if (bytes != null && looksLikeUtf8Text(bytes)) return "text/plain"
        }
        return "application/octet-stream"
    }

    private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        }.isSuccess
    }
}

/** 与 SecureWorkspaceFileOps 写入的根身份标记同名。 */
internal const val WORKSPACE_ROOT_IDENTITY_MARKER = ".infinity_root_identity"
