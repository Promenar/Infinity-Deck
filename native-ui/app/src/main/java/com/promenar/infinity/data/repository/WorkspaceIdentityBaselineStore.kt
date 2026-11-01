package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 应用私有目录中的身份缓存；调用方必须持有工作区根锁，保存时还须持有外部命令提交门。 */
class WorkspaceIdentityBaselineStore(
    private val directory: File,
    private val scanner: WorkspaceTreeScanner = SecureWorkspaceTreeScanner,
    private val diagnostic: (String) -> Unit = { reason ->
        InfinityLogger.diagnostic("workspace.identity_baseline_discarded", mapOf("reason" to reason))
    },
) {
    fun load(root: FileEntry): WorkspaceTreeSnapshot? {
        val file = location(root)
        if (!file.exists()) return null
        return try {
            require(file.length() <= MAX_BYTES) { "size" }
            DataInputStream(BoundedInput(GZIPInputStream(file.inputStream()))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw InvalidBaseline("format")
                if (input.readUTF() != root.uuid || input.readUTF() != root.hash ||
                    input.readUTF() != rootIdentity(root)) throw InvalidBaseline("root_identity")
                val snapshot = WorkspaceTreeSnapshot(readNodes(input), readNodes(input))
                require(input.read() == -1)
                snapshot
            }
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            diagnostic((failure as? InvalidBaseline)?.reason ?: "corrupted")
            null
        }
    }

    /** 先移除旧基线再改盘；失败时必须拒绝后续事务，不能留下可被重启误用的旧缓存。 */
    fun invalidate(root: FileEntry) {
        Files.deleteIfExists(location(root).toPath())
        // 失效必须先于文件/数据库事务持久化，避免系统崩溃后旧目录项重新出现。
        if (directory.isDirectory) syncDirectory()
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    fun save(root: FileEntry, snapshot: WorkspaceTreeSnapshot) {
        invalidate(root)
        stage(root, snapshot)?.use { it.publish() }
    }

    /** 全量编码、压缩和 fsync 在后台扫描阶段完成，不占用 Linux 命令开始/结束的提交门。 */
    internal fun stage(root: FileEntry, snapshot: WorkspaceTreeSnapshot): StagedBaseline? {
        val temporary = File(directory, location(root).name + ".tmp").toPath()
        return try {
            Files.createDirectories(directory.toPath())
            FileOutputStream(temporary.toFile()).use { file ->
                GZIPOutputStream(file).use { gzip ->
                    val output = DataOutputStream(gzip)
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(root.uuid)
                    output.writeUTF(root.hash)
                    output.writeUTF(rootIdentity(root))
                    writeNodes(output, snapshot.nodes)
                    writeNodes(output, snapshot.reserved)
                    output.flush()
                    gzip.finish()
                    file.fd.sync()
                }
            }
            StagedBaseline(temporary, location(root).toPath())
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            diagnostic("write_failed")
            null
        }
    }

    internal inner class StagedBaseline(
        private val temporary: java.nio.file.Path,
        private val target: java.nio.file.Path,
    ) : AutoCloseable {
        fun publish() {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory()
            } catch (_: Exception) {
                runCatching { Files.deleteIfExists(target); syncDirectory() }
                // 旧缓存已在索引事务之前失效；发布失败只退回无基线行为。
                diagnostic("write_failed")
            }
        }
        override fun close() { runCatching { Files.deleteIfExists(temporary) } }
    }

    /** 普通事务只刷新目标条目及其子树；异常或未知影响范围时保持失效状态。 */
    suspend fun <T> trackMutation(root: FileEntry, dao: FileEntryDao, uuid: String?, block: suspend () -> T): T {
        val baseline = load(root)
        val oldPath = uuid?.let { dao.getAnyStateByUuidForLifecycle(root.uuid, it)?.materializedPath }
        invalidate(root)
        val result = block()
        if (baseline != null && uuid != null) withContext(NonCancellable) {
            try {
                val entry = dao.getActiveByUuid(root.uuid, uuid)
                val newPath = entry?.materializedPath
                fun affected(path: String) = listOfNotNull(oldPath, newPath).any { prefix ->
                    path == prefix || path.startsWith(prefix.trimEnd('/') + "/")
                }
                val entries = when {
                    entry == null -> emptyList()
                    entry.isDirectory -> dao.getSubtree(root.uuid, entry.materializedPath, false)
                    else -> listOf(entry)
                }
                val paths = entries.map { it.materializedPath }.filter { path ->
                    path.trim('/').split('/').none(::isReservedWorkspaceName)
                }
                val nodes = baseline.nodes.filterKeys { !affected(it) } +
                    scanner.scanPaths(File(root.physicalRootPath).toPath(), paths)
                save(root, baseline.copy(nodes = nodes))
            } catch (_: Exception) {
                invalidate(root)
                diagnostic("mutation_refresh_failed")
            }
        }
        return result
    }

    private fun location(root: FileEntry) = File(directory, "${Sha256Utils.hash(root.uuid)}.bin.gz")

    private fun rootIdentity(root: FileEntry): String {
        val path = File(root.physicalRootPath).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink)
        return path.toFile().canonicalPath + "|" + checkNotNull(attributes.fileKey()).toString()
    }

    private fun writeNodes(output: DataOutputStream, nodes: Map<String, WorkspaceScanNode>) {
        require(nodes.size <= MAX_NODES)
        output.writeInt(nodes.size)
        nodes.forEach { (path, node) ->
            output.writeUTF(path)
            output.writeByte(node.kind.ordinal)
            output.writeLong(node.sizeBytes)
            output.writeLong(node.modifiedNanos)
            output.writeBoolean(node.fileKey != null)
            node.fileKey?.let(output::writeUTF)
        }
    }

    private fun readNodes(input: DataInputStream): Map<String, WorkspaceScanNode> {
        val count = input.readInt()
        require(count in 0..MAX_NODES)
        val nodes = LinkedHashMap<String, WorkspaceScanNode>()
        repeat(count) {
            val path = input.readUTF()
            require(path.startsWith('/') && path != "/" && path.split('/').drop(1).all {
                it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it
            })
            val kind = WorkspaceScanKind.entries[input.readUnsignedByte()]
            val size = input.readLong().also { require(it >= 0) }
            val modified = input.readLong()
            val key = if (input.readBoolean()) input.readUTF() else null
            require(nodes.put(path, WorkspaceScanNode(kind, size, modified, key)) == null)
        }
        return nodes
    }

    private class InvalidBaseline(val reason: String) : IllegalStateException()
    private class BoundedInput(input: InputStream) : FilterInputStream(input) {
        private var remaining = MAX_BYTES
        override fun read(): Int {
            check(remaining-- > 0)
            return super.read()
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(remaining > 0)
            return super.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }
    }

    private companion object {
        const val MAGIC = 0x49574249
        const val VERSION = 1
        const val MAX_NODES = 250_000
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
