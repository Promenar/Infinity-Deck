package com.promenar.infinity.data.files

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException

internal class MemoryFileBackend : ExternalFolderBackend {
    private data class Node(val directory: Boolean, val bytes: ByteArray, val modified: Long)
    private val nodes = linkedMapOf("/" to Node(true, byteArrayOf(), 1))

    fun addDirectory(path: String, modified: Long = 1) { nodes[path] = Node(true, byteArrayOf(), modified) }
    fun addFile(path: String, bytes: ByteArray, modified: Long = 1) { nodes[path] = Node(false, bytes, modified) }
    fun bytes(path: String) = nodes.getValue(path).bytes
    override suspend fun stat(path: String): ExternalFolderEntry? = nodes[path]?.let { node ->
        ExternalFolderEntry(path, if (path == "/") "" else path.substringAfterLast('/'), node.directory,
            node.bytes.size.toLong(), node.modified, identity = path)
    }
    override suspend fun list(path: String): List<ExternalFolderEntry> {
        if (nodes[path]?.directory != true) throw ExternalFolderException("NOT_FOUND", "目录不存在")
        val prefix = path.trimEnd('/') + "/"
        return nodes.keys.filter { it != path && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.mapNotNull { stat(it) }
    }
    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val value = nodes[path] ?: throw ExternalFolderException("NOT_FOUND", "文件不存在")
        if (value.bytes.size > maxBytes) throw ExternalFolderException("TOO_LARGE", "文件过大")
        return value.bytes.copyOf()
    }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
        if (createOnly && path in nodes) throw ExternalFolderException("CONFLICT", "目标已存在")
        if (nodes[parentFilePath(path)]?.directory != true) throw ExternalFolderException("NOT_FOUND", "父目录不存在")
        addFile(path, content.copyOf())
        return requireNotNull(stat(path))
    }
    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        if (path in nodes) throw ExternalFolderException("CONFLICT", "目标已存在")
        if (nodes[parentFilePath(path)]?.directory != true) throw ExternalFolderException("NOT_FOUND", "父目录不存在")
        addDirectory(path)
        return requireNotNull(stat(path))
    }
    override suspend fun delete(path: String, expectedSha256: String?) {
        nodes.keys.filter { it == path || it.startsWith("$path/") }.forEach(nodes::remove)
    }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        if (nodes[path]?.directory == true && list(path).isNotEmpty()) throw ExternalFolderException("CONFLICT", "目录非空")
        nodes.remove(path)
    }
    override suspend fun move(sourcePath: String, targetPath: String) {
        if (targetPath in nodes) throw ExternalFolderException("CONFLICT", "目标已存在")
        val node = nodes.remove(sourcePath) ?: throw ExternalFolderException("NOT_FOUND", "文件不存在")
        nodes[targetPath] = node
    }
}
