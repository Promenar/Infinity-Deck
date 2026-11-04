package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ExternalTreeNode(val relativePath: String, val isDirectory: Boolean, val bytes: ByteArray)
data class ExternalCreatedNode(val node: ExternalTreeNode, val receipt: ExternalFolderEntry)
data class ExternalTree(val nodes: List<ExternalTreeNode>) {
    val size: Long get() = nodes.sumOf { it.bytes.size.toLong() }
    fun digest(): String = externalSha256(nodes.joinToString("\n") {
        "${it.relativePath}:${it.isDirectory}:${externalSha256(it.bytes)}"
    }.toByteArray(Charsets.UTF_8))
}

internal fun externalSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

object ExternalTreeTransfer {
    const val MAX_BYTES = 32L * 1024 * 1024
    suspend fun capture(backend: ExternalFolderBackend, path: String, maxBytes: Long = MAX_BYTES): ExternalTree {
        val root = ExternalFolderPaths.normalize(path)
        val nodes = mutableListOf<ExternalTreeNode>()
        var remaining = maxBytes
        suspend fun visit(current: String, relative: String, depth: Int) {
            if (nodes.size >= 1000 || depth > 32) throw ExternalFolderException("TOO_LARGE", "目录超过快照条目或深度上限")
            val entry = backend.stat(current) ?: throw ExternalFolderException("NOT_FOUND", "源条目不存在")
            if (entry.isDirectory) {
                nodes.add(ExternalTreeNode(relative, true, byteArrayOf()))
                backend.list(current).sortedBy { it.name }.forEach { child ->
                    ExternalFolderPaths.validateName(child.name)
                    visit(current.trimEnd('/') + "/" + child.name, relative.trimEnd('/') + "/" + child.name, depth + 1)
                }
            } else {
                if (entry.size > remaining) throw ExternalFolderException("RECYCLE_LIMIT", "文件超过剩余快照空间")
                val bytes = backend.read(current, remaining.coerceAtLeast(1))
                if (bytes.size > remaining) throw ExternalFolderException("RECYCLE_LIMIT", "文件超过剩余快照空间")
                remaining -= bytes.size
                nodes.add(ExternalTreeNode(relative, false, bytes))
            }
        }
        visit(root, "", 0)
        return ExternalTree(nodes)
    }

    suspend fun copy(tree: ExternalTree, backend: ExternalFolderBackend, path: String): List<ExternalCreatedNode> {
        val created = mutableListOf<ExternalCreatedNode>()
        try {
            for (node in tree.nodes) {
                val destination = path.trimEnd('/') + node.relativePath
                val receipt = if (node.isDirectory) backend.createDirectory(destination)
                else backend.write(destination, node.bytes, createOnly = true)
                created.add(ExternalCreatedNode(node, receipt))
                if (!node.isDirectory && !backend.read(destination, MAX_BYTES).contentEquals(node.bytes)) {
                    throw ExternalFolderException("VERIFY_FAILED", "目标副本校验失败")
                }
            }
        } catch (failure: Exception) {
            compensate("目标 $path", failure) { rollback(backend, path, created) }
            throw failure
        }
        return created
    }
    suspend fun move(source: ExternalFolderBackend, sourcePath: String, target: ExternalFolderBackend, targetPath: String, expectedSha256: String? = null) {
        ExternalStreamingMove.move(source, sourcePath, target, targetPath, expectedSha256)
    }

    private suspend fun rollback(backend: ExternalFolderBackend, path: String, nodes: List<ExternalCreatedNode>) {
        for ((node, receipt) in nodes.asReversed()) {
            val destination = path.trimEnd('/') + node.relativePath
            val entry = backend.stat(destination) ?: continue
            if (entry.isDirectory != node.isDirectory || receipt.identity != null && entry.identity != receipt.identity) {
                throw ExternalFolderException("RECOVERY_REQUIRED", "目标身份发生变化，已保留 $path 供人工核查")
            }
            if (entry.isDirectory) {
                if (backend.list(destination).isNotEmpty()) throw ExternalFolderException("RECOVERY_REQUIRED", "目标目录发生变化，请检查 $path")
                backend.delete(destination)
            } else {
                backend.delete(destination, externalSha256(node.bytes))
            }
        }
    }

    private suspend fun compensate(label: String, original: Exception, block: suspend () -> Unit) = withContext(NonCancellable) {
        try { block() } catch (failure: Exception) {
            val recovery = ExternalFolderException("RECOVERY_REQUIRED", "$label 补偿未完成；来源或目标已保留，请在文件面板核查")
            recovery.addSuppressed(original); recovery.addSuppressed(failure)
            throw recovery
        }
    }
}
