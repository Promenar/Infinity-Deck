package com.promenar.infinity.data.backup

import com.promenar.infinity.data.repository.PersistentFileIdentity
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption

/** Descriptor-relative operations for restore-owned trees. Unsupported providers fail closed. */
interface RestoreFileOperations {
    fun createTransactionRoot(parent: Path, name: String)
    fun createDirectory(root: Path, relative: List<String>)
    fun writeNew(root: Path, relative: List<String>, bytes: ByteArray)
    fun writeStream(root: Path, relative: List<String>, blob: BackupBlob) {
        // 旧测试适配器仅服务小样本；生产实现提供 descriptor-relative 流写入。
        if (blob.size > BackupPackageLimits.MAX_IN_MEMORY_BYTES) throw BackupValidationException("恢复文件操作未实现流写入")
        val bytes = blob.open().use { it.readBytes() }
        try { writeNew(root, relative, bytes) } finally { bytes.fill(0) }
    }
    fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String
    fun moveTree(parent: Path, sourceName: String, targetName: String)
    fun moveTreeBetween(sourceParent: Path, sourceName: String, targetParent: Path, targetName: String) {
        if (sourceParent != targetParent) throw BackupValidationException("恢复文件操作未实现跨目录移动")
        moveTree(sourceParent, sourceName, targetName)
    }
    fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry>
    fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>)
    /** expectedFileKey 是持久 nonce 与 inode 身份，完整 fileKey 只在本次删除期间比对。 */
    fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>? = null,
        expectedInventory: Set<RestoreTreeEntry>? = null,
    )
}

data class RestoreTreeEntry(val path: String, val directory: Boolean)

internal object SecureBackupFileOps : RestoreFileOperations {
    override fun createTransactionRoot(parent: Path, name: String) {
        createDirectoryByDescriptorMove(parent, emptyList(), name)
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        require(relative.isNotEmpty())
        createDirectoryByDescriptorMove(root, relative.dropLast(1), relative.last())
    }

    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) = writeStream(root, relative, BackupBlob.Bytes(bytes))

    override fun writeStream(root: Path, relative: List<String>, blob: BackupBlob) {
        require(relative.isNotEmpty()) { "恢复文件相对路径不能为空" }
        openSecure(root).use { rootStream ->
            var current: SecureDirectoryStream<Path> = rootStream
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                relative.dropLast(1).forEach { segment ->
                    val next = current.newDirectoryStream(Paths.get(segment), LinkOption.NOFOLLOW_LINKS)
                    opened += next
                    current = next
                }
                val options = setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                current.newByteChannel(Paths.get(relative.last()), options).use { channel ->
                    blob.open().use { input ->
                        BackupStreams.copy(input, java.nio.channels.Channels.newOutputStream(channel), blob.size)
                    }
                    (channel as? FileChannel)?.force(true)
                        ?: throw BackupValidationException("恢复文件系统不支持文件 fsync")
                }
            } finally {
                opened.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }

    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String {
        require(relative.isNotEmpty() && nonce.isNotBlank())
        return openSecure(root).use { rootStream ->
            var current: SecureDirectoryStream<Path> = rootStream
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                relative.forEach { segment ->
                    val next = current.newDirectoryStream(Paths.get(segment), LinkOption.NOFOLLOW_LINKS)
                    opened += next
                    current = next
                }
                val key = current.getFileAttributeView(
                    Paths.get("."),
                    java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes().fileKey()
                    ?: throw BackupValidationException("恢复 workspace root 缺少 fileKey")
                val marker = PersistentFileIdentity.marker(nonce, key).toByteArray(Charsets.UTF_8)
                current.newByteChannel(
                    Paths.get(".infinity_root_identity"),
                    setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    var offset = 0
                    while (offset < marker.size) {
                        offset += channel.write(ByteBuffer.wrap(marker, offset, marker.size - offset))
                    }
                    (channel as? FileChannel)?.force(true)
                        ?: throw BackupValidationException("恢复 workspace identity 不支持 fsync")
                }
                PersistentFileIdentity.encode(nonce, key)
            } finally {
                opened.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }

    override fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) {
        val childRoot = parent.resolve(childName)
        val expectedOperationKey = Files.readAttributes(
            childRoot,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey() ?: throw BackupValidationException("待删除恢复目录缺少 fileKey")
        if (PersistentFileIdentity.read(childRoot) != expectedFileKey) {
            throw BackupValidationException("待删除恢复目录持久身份已变化")
        }
        openSecure(parent).use { parentStream ->
            val childPath = Paths.get(childName)
            val child = try {
                parentStream.newDirectoryStream(childPath, LinkOption.NOFOLLOW_LINKS)
            } catch (error: Exception) {
                throw BackupValidationException("待删除恢复目录不是受信普通目录", error)
            }
            child.use {
                val attributes = it.getFileAttributeView(
                    Paths.get("."),
                    java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                if (attributes.fileKey() != expectedOperationKey ||
                    PersistentFileIdentity.encode(PersistentFileIdentity.nonce(expectedFileKey), attributes.fileKey()) != expectedFileKey
                ) {
                    throw BackupValidationException("待删除恢复目录 fileKey 已变化")
                }
                expectedInventory?.let { expected ->
                    // UnixSecureDirectoryStream 只允许从同一 handle 获取一次 iterator。
                    // 使用同一 root descriptor 派生独立 view 做 inventory，保留原 handle 给后续删除遍历。
                    val actual = it.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS).use { inventoryView ->
                        buildSet { collectInventory(inventoryView, "", this) }
                    }
                    if (actual != expected) throw BackupValidationException("待删除目录 descriptor inventory 已变化")
                }
                marker?.let { (name, expected) ->
                    val bytes = readBounded(it, Paths.get(name), 128)
                    try {
                        if (bytes.toString(Charsets.UTF_8) != expected) {
                            throw BackupValidationException("待删除恢复目录 owner marker 无效")
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
                deleteChildren(it, marker?.first)
                marker?.let { itMarker -> it.deleteFile(Paths.get(itMarker.first)) }
            }
            parentStream.deleteDirectory(childPath)
        }
    }

    override fun moveTree(parent: Path, sourceName: String, targetName: String) {
        openSecure(parent).use { secure ->
            secure.move(Paths.get(sourceName), secure, Paths.get(targetName))
        }
    }

    override fun moveTreeBetween(sourceParent: Path, sourceName: String, targetParent: Path, targetName: String) {
        openSecure(sourceParent).use { source ->
            openSecure(targetParent).use { target ->
                source.move(Paths.get(sourceName), target, Paths.get(targetName))
            }
        }
    }

    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> =
        openSecure(parent).use { secure ->
            secure.newDirectoryStream(Paths.get(childName), LinkOption.NOFOLLOW_LINKS).use { child ->
                buildSet { collectInventory(child, "", this) }
            }
        }

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        val parent = root.parent ?: throw BackupValidationException("恢复 root 缺少父目录")
        if (inventory(parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("恢复 staging inventory 与预期不一致")
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(FileRestoreJournal::syncDirectory)
        }
        if (inventory(parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("恢复 staging 在 fsync 期间发生变化")
        }
    }

    private fun createDirectoryByDescriptorMove(root: Path, parents: List<String>, name: String) {
        val temporaryName = ".mkdir-${java.util.UUID.randomUUID()}"
        val temporary = root.resolve(temporaryName)
        Files.createDirectory(temporary)
        try {
            openSecure(root).use { rootStream ->
                var current = rootStream
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                try {
                    parents.forEach { segment ->
                        current = current.newDirectoryStream(Paths.get(segment), LinkOption.NOFOLLOW_LINKS)
                            .also(opened::add)
                    }
                    rootStream.newDirectoryStream(Paths.get(temporaryName), LinkOption.NOFOLLOW_LINKS).use { }
                    rootStream.move(Paths.get(temporaryName), current, Paths.get(name))
                } finally {
                    opened.asReversed().forEach { runCatching { it.close() } }
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteChildren(directory: SecureDirectoryStream<Path>, markerName: String? = null) {
        directory.toList().forEach { entry ->
            val name = entry.fileName
            if (markerName != null && name.toString() == markerName) return@forEach
            val child = runCatching { directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (child == null) {
                directory.deleteFile(name)
            } else {
                child.use { deleteChildren(it) }
                directory.deleteDirectory(name)
            }
        }
    }

    private fun collectInventory(
        directory: SecureDirectoryStream<Path>,
        prefix: String,
        result: MutableSet<RestoreTreeEntry>,
    ) {
        directory.toList().forEach { entry ->
            val name = entry.fileName
            val relative = if (prefix.isEmpty()) name.toString() else "$prefix/${name}"
            val attributes = directory.getFileAttributeView(
                name,
                java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (attributes.isSymbolicLink) throw BackupValidationException("恢复树 inventory 包含符号链接")
            if (attributes.isDirectory) {
                result += RestoreTreeEntry(relative, true)
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use {
                    collectInventory(it, relative, result)
                }
            } else if (attributes.isRegularFile) {
                result += RestoreTreeEntry(relative, false)
            } else {
                throw BackupValidationException("恢复树 inventory 包含非普通节点")
            }
        }
    }

    private fun readBounded(directory: SecureDirectoryStream<Path>, name: Path, limit: Int): ByteArray {
        val channel = directory.newByteChannel(
            name,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
        channel.use {
            val buffer = ByteBuffer.allocate(limit + 1)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw BackupValidationException("恢复 owner marker 超过限制")
            }
            return buffer.array().copyOf(total)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openSecure(path: Path): SecureDirectoryStream<Path> {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("受信目录身份无效")
        }
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(path)
        return stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw BackupValidationException("当前文件系统不支持 SecureDirectoryStream，拒绝降低路径安全保证")
        }
    }
}
