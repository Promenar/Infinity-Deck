package com.promenar.infinity.data.repository

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
import java.io.OutputStream
import java.nio.channels.Channels
import java.util.UUID

enum class WorkspaceFilePhase {
    DESCRIPTOR_OPENED,
    DIRECTORY_TEMP_CREATED,
    BEFORE_MUTATION,
    ROLLBACK_BEFORE_RESTORE,
    RECONCILE_CONTENT_READ,
    CREATION_MARKER_REMOVED,
    CREATION_FILE_DELETED,
}

interface WorkspaceFileOps {
    fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean = true,
        expectedIdentity: String? = null,
        allowUnboundParent: Boolean = false,
    ): String
    fun read(root: Path, relative: List<String>): ByteArray
    fun readLimited(root: Path, relative: List<String>, maxBytes: Long): ByteArray
    /** 在文件句柄有效期内把内容交给 [consume] 流式处理；默认实现退回 [readLimited] 整读，供测试替身使用。 */
    fun <T> readStreamLimited(root: Path, relative: List<String>, maxBytes: Long, consume: (java.io.InputStream) -> T): T =
        consume(readLimited(root, relative, maxBytes).inputStream())
    fun exists(root: Path, relative: List<String>): Boolean
    fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity
    fun listChildren(root: Path, relative: List<String>): List<String>
    fun deleteNonRecursive(root: Path, relative: List<String>)
    fun retainCreationProof(root: Path, node: List<String>, owner: List<String>, token: String)
    fun verifyCreationProof(root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity)
    fun deleteCreatedNode(root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity)
    fun releaseCreationProof(root: Path, node: List<String>, token: String)
    fun createFile(root: Path, relative: List<String>, bytes: ByteArray)
    fun createFileStreaming(
        root: Path,
        relative: List<String>,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): WorkspaceStreamWriteResult
    fun createDirectory(root: Path, relative: List<String>)
    fun ensureDirectory(root: Path, relative: List<String>)
    fun replaceFile(root: Path, relative: List<String>, bytes: ByteArray): WorkspaceFileRollback
    fun reconcileFile(root: Path, relative: List<String>, expectedHash: String)
    fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback
    fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback {
        if (sourceRoot != targetRoot) throw SecurityException("文件端口不支持安全跨根移动")
        return move(sourceRoot, source, target)
    }
    fun stageDelete(root: Path, source: List<String>, deletionToken: String): WorkspaceFileRollback
    fun recoverTombstones(root: Path, restorePath: (deletionToken: String) -> List<String>?): TombstoneRecoveryReport
    fun delete(root: Path, source: List<String>)
    fun cleanupTombstones(root: Path)
    fun captureRootIdentityRestorer(root: Path): () -> Boolean = { false }
    fun migrateInternalStorage(root: Path) = Unit
    fun hasExternalRootIdentity(root: Path): Boolean = false
    /** 按 nx1 之前的算法逐字核验旧根认领；吻合时返回同一 nonce 与 inode 的现行身份，否则返回 null。 */
    fun adoptLegacyRootIdentity(root: Path, legacyIdentity: String): String? = null
    /** 根认领改写为等价现行身份前，把按旧身份寻址的私有元数据移到新身份下；可重入。 */
    fun adoptLegacyRootPrivateData(legacyIdentity: String, identity: String) = Unit
}

@kotlinx.serialization.Serializable
data class WorkspaceNodeIdentity(
    val kind: String,
    val sizeBytes: Long,
    val sha256: String?,
    val fileKey: String,
)

data class WorkspaceStreamWriteResult(val sizeBytes: Long, val sha256: String)
data class TombstoneRecoveryReport(val attentionTokens: List<String> = emptyList())

class WorkspaceFileTooLargeException(val limitBytes: Long) : java.io.IOException(
    "工作区文件超过上限: $limitBytes bytes"
)

interface WorkspaceFileRollback {
    fun commit()
    fun rollback()
}

/** 工作区 descriptor-relative 文件操作；文件系统不支持 SecureDirectoryStream 时失败关闭。 */
class SecureWorkspaceFileOps(
    private val identityStorage: WorkspaceInternalStorage? = null,
    private val directoryOpener: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
    private val identityFileKey: (Any?) -> Any? = { it },
    private val rootNonceReader: (Path, String) -> String? = { path, key ->
        AndroidWorkspaceCreationProof.withRootDirectory(path, key) { AndroidWorkspaceFileIdentity.readRoot(it.fd) }
    },
    private val hook: ((WorkspaceFilePhase) -> Unit)? = null,
) : WorkspaceFileOps {
    override fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean,
        expectedIdentity: String?,
        allowUnboundParent: Boolean,
    ): String {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return openSecure(root, requireIdentity = false).use { secure ->
                ensureRootIdentity(root, secure, initializeIdentity, expectedIdentity)
            }
        }
        if (!initializeIdentity) throw SecurityException("已认领工作区根目录不存在")
        val parent = root.parent ?: throw SecurityException("工作区根缺少受信父目录")
        try {
            createDirectoryInternal(parent, listOf(root.fileName.toString()), requireIdentity = !allowUnboundParent)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // 并发创建者获胜后仍必须按 descriptor 身份重新验证。
        }
        return openSecure(root, requireIdentity = false).use { secure ->
            ensureRootIdentity(root, secure, initialize = true, expectedIdentity = expectedIdentity)
        }
    }

    override fun read(root: Path, relative: List<String>): ByteArray =
        readLimited(root, relative, Long.MAX_VALUE)

    override fun readLimited(root: Path, relative: List<String>, maxBytes: Long): ByteArray =
        withParent(root, relative) { parent, name ->
            parent.newByteChannel(
                name,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                if (channel.size() > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (total > maxBytes - count) throw WorkspaceFileTooLargeException(maxBytes)
                    output.write(buffer.array(), 0, count)
                    total += count
                }
                output.toByteArray()
            }
        }

    override fun <T> readStreamLimited(root: Path, relative: List<String>, maxBytes: Long, consume: (java.io.InputStream) -> T): T =
        readStream(root, relative, maxBytes, consume)

    /** 流式读取持有安全父目录和文件句柄，结束前复核路径与句柄版本。 */
    fun <T> readStream(root: Path, relative: List<String>, maxBytes: Long, consume: (java.io.InputStream) -> T): T =
        withParent(root, relative) { parent, name ->
            val view = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            val before = view.readAttributes()
            if (!before.isRegularFile) throw SecurityException("只能读取普通工作区文件")
            parent.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (maxBytes < 0 || channel.size() > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
                val result = consume(java.nio.channels.Channels.newInputStream(channel))
                val after = view.readAttributes()
                if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() || channel.size() != before.size()) {
                    throw SecurityException("工作区文件在读取期间发生变化")
                }
                result
            }
        }

    override fun exists(root: Path, relative: List<String>): Boolean =
        try {
            withParent(root, relative) { _, _ ->
                val target = relative.fold(root) { current, part -> current.resolve(part) }.normalize()
                if (Files.isSymbolicLink(target)) throw SecurityException("工作区目标不可为符号链接")
                Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }

    override fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity =
        try {
            withParent(root, relative) { parent, name ->
                parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { }
                WorkspaceNodeIdentity("directory", 0, null, nodeIdentity(root, relative, parent, name))
            }
        } catch (_: java.nio.file.NotDirectoryException) {
            withParent(root, relative) { parent, name ->
                parent.newByteChannel(
                    name,
                    setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        buffer.clear()
                        val count = channel.read(buffer)
                        if (count < 0) break
                        digest.update(buffer.array(), 0, count)
                        size += count
                    }
                    WorkspaceNodeIdentity(
                        "file",
                        size,
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                        nodeIdentity(root, relative, parent, name),
                    )
                }
            }
        }

    private fun nodeFileKey(parent: SecureDirectoryStream<Path>, name: Path): String {
        return parent.getFileAttributeView(
            name,
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes().fileKey()?.toString() ?: throw SecurityException("工作区节点缺少 fileKey")
    }

    private fun nodeIdentity(root: Path, relative: List<String>, parent: SecureDirectoryStream<Path>, name: Path): String {
        val before = nodeFileKey(parent, name)
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val parentKey = directoryKey(parent)
            return openSecure(root).use { anchor ->
                val rootKey = directoryKey(anchor)
                val validate: (SecureDirectoryStream<Path>, SecureDirectoryStream<Path>, String) -> Unit = { openedRoot, openedParent, openedKey ->
                    if (directoryKey(openedRoot) != rootKey || directoryKey(openedParent) != parentKey ||
                        openedKey != before || nodeFileKey(parent, name) != before) {
                        throw SecurityException("工作区节点在身份读取期间被替换")
                    }
                }
                val action: (android.os.ParcelFileDescriptor) -> String = { fd -> PersistentFileIdentity.readFd(fd.fd, before) }
                val attributes = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS).readAttributes()
                if (attributes.isDirectory) AndroidWorkspaceCreationProof.withDirectory(root, relative, validate, action)
                else AndroidWorkspaceCreationProof.withFile(root, relative, validate, action)
            }
        }
        val path = relative.fold(root) { directory, segment -> directory.resolve(segment) }
        val identity = PersistentFileIdentity.read(path)
        val pathKey = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()?.toString()
        if (pathKey != before || nodeFileKey(parent, name) != before) throw SecurityException("工作区节点在身份读取期间被替换")
        return identity
    }

    override fun listChildren(root: Path, relative: List<String>): List<String> {
        if (relative.isNotEmpty()) requireSafeRelative(relative)
        return openSecure(root).use { secure ->
            openDirectory(secure, relative).use { directory -> directory.map { it.fileName.toString() } }
        }
    }

    override fun deleteNonRecursive(root: Path, relative: List<String>) {
        withParent(root, relative) { parent, name ->
            val attributes = parent.getFileAttributeView(name,
                java.nio.file.attribute.BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
            when {
                attributes.isSymbolicLink -> throw SecurityException("清理目标不能是符号链接")
                attributes.isDirectory -> parent.deleteDirectory(name)
                attributes.isRegularFile -> parent.deleteFile(name)
                else -> throw SecurityException("清理目标不是普通节点")
            }
        }
    }

    override fun retainCreationProof(root: Path, node: List<String>, owner: List<String>, token: String) {
        requireCreationToken(token)
        val identity = inspect(root, node)
        if (identity.kind == "directory") {
            withCreationDirectory(root, node, identity) { AndroidWorkspaceFileIdentity.create(it.fd, token) }
        } else {
            withCreationFile(root, node, identity) { file ->
                AndroidWorkspaceFileIdentity.create(file.fd, token)
            }
        }
        verifyCreationProof(root, node, owner, token, identity)
    }

    override fun verifyCreationProof(
        root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity,
    ) {
        requireCreationToken(token)
        if (inspect(root, node) != expected) throw SecurityException("创建节点内容或身份发生变化")
        if (expected.kind == "directory") {
            val legacyValues = CREATE_DIRECTORY_MARKERS.mapNotNull { marker ->
                if (exists(root, node + marker)) readLimited(root, node + marker, 128).toString(Charsets.UTF_8) else null
            }
            if (legacyValues.distinct().size > 1) throw SecurityException("创建目录新旧标记冲突")
            val legacy = legacyValues.firstOrNull()
            val persistent = readWorkspaceIdentityAttribute(legacy == token,
                { withCreationDirectory(root, node, expected) { AndroidWorkspaceFileIdentity.read(it.fd) } },
                { identityStorage?.recordFailure("legacy-directory", "directory-proof-xattr-unsupported", it) })
            if ((persistent != null && persistent != token) || (persistent == null && legacy != token)) {
                throw SecurityException("创建目录持久标记不匹配")
            }
        } else {
            withCreationFile(root, node, expected) { file ->
                if (AndroidWorkspaceFileIdentity.read(file.fd) != token) {
                    throw SecurityException("创建文件持久身份属性不匹配")
                }
            }
        }
    }

    private fun <T> withCreationDirectory(root: Path, node: List<String>, expected: WorkspaceNodeIdentity,
        action: (android.os.ParcelFileDescriptor) -> T): T = withParent(root, node) { parent, name ->
        val parentKey = directoryKey(parent)
        openSecure(root).use { secure ->
            val rootKey = directoryKey(secure)
            AndroidWorkspaceCreationProof.withDirectory(root, node, { anchored, source, openedKey ->
                if (directoryKey(anchored) != rootKey || directoryKey(source) != parentKey ||
                    PersistentFileIdentity.encode(PersistentFileIdentity.nonce(expected.fileKey), openedKey) != expected.fileKey || nodeFileKey(parent, name) != openedKey) {
                    throw SecurityException("创建目录FD与目录绑定不匹配")
                }
            }) { file ->
                if (PersistentFileIdentity.readFd(file.fd, "ino=${android.system.Os.fstat(file.fileDescriptor).st_ino}") != expected.fileKey) {
                    throw SecurityException("创建目录持久 nonce 不匹配")
                }
                action(file)
            }
        }
    }

    private fun <T> withCreationFile(
        root: Path, node: List<String>, expected: WorkspaceNodeIdentity,
        action: (android.os.ParcelFileDescriptor) -> T,
    ): T = withParent(root, node) { parent, name ->
        val parentKey = directoryKey(parent)
        openSecure(root).use { secure ->
            val rootKey = directoryKey(secure)
            AndroidWorkspaceCreationProof.withFile(root, node, { anchored, source, openedKey ->
                if (directoryKey(anchored) != rootKey || directoryKey(source) != parentKey ||
                    PersistentFileIdentity.encode(PersistentFileIdentity.nonce(expected.fileKey), openedKey) != expected.fileKey || nodeFileKey(parent, name) != openedKey) {
                    throw SecurityException("创建文件FD与目录绑定不匹配")
                }
            }) { file ->
                if (PersistentFileIdentity.readFd(file.fd, "ino=${android.system.Os.fstat(file.fileDescriptor).st_ino}") != expected.fileKey) {
                    throw SecurityException("创建文件持久 nonce 不匹配")
                }
                check(android.system.Os.fstat(file.fileDescriptor).st_size == expected.sizeBytes) { "创建文件FD大小不匹配" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(file.fileDescriptor)).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0L
                    while (read < expected.sizeBytes) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expected.sizeBytes - read).toInt())
                        check(count > 0) { "创建文件FD读取提前结束" }
                        digest.update(buffer, 0, count)
                        read += count
                    }
                }
                check(android.system.Os.fstat(file.fileDescriptor).st_size == expected.sizeBytes &&
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expected.sha256) {
                    "创建文件FD内容摘要不匹配"
                }
                action(file)
            }
        }
    }

    override fun deleteCreatedNode(
        root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity,
    ) {
        requireCreationToken(token)
        val quarantine = owner + CREATE_ROLLBACK_NODE
        val deletionPhase = owner + CREATE_DIRECTORY_DELETE_READY
        if (node == quarantine && expected.kind == "directory" && exists(root, deletionPhase) &&
            CREATE_DIRECTORY_MARKERS.none { exists(root, node + it) }) {
            if (readLimited(root, deletionPhase, 128).toString(Charsets.UTF_8) != token ||
                inspect(root, node) != expected) {
                throw SecurityException("创建目录删除阶段证明不匹配")
            }
            val manifestPath = owner + CREATE_MANIFEST
            if (exists(root, manifestPath) && decodeCreateOwnershipManifest(readLimited(root, manifestPath, 8192))?.proofVersion == 4) {
                verifyCreationProof(root, node, owner, token, expected)
            }
            // 此阶段只授权回收 operation 私有隔离槽内的空目录，绝不删除公开路径或目录内容。
            withParent(root, quarantine) { parent, name -> parent.deleteDirectory(name) }
            return
        }
        verifyCreationProof(root, node, owner, token, expected)
        if (expected.kind == "directory") requireOnlyCreationMarker(root, node)
        val moved = node != quarantine
        if (moved) {
            if (exists(root, quarantine)) throw SecurityException("创建回滚隔离节点已存在")
            move(root, node, quarantine).commit()
        }
        var markerRemoved = false
        try {
            // 移入私有 operation 目录后重新核验，检查与移动之间被替换的目标绝不删除。
            verifyCreationProof(root, quarantine, owner, token, expected)
            withParent(root, quarantine) { parent, name ->
                if (expected.kind == "directory") {
                    parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                        val children = directory.map { it.fileName.toString() }
                        if (children.any { it !in CREATE_DIRECTORY_MARKERS }) {
                            throw SecurityException("创建目录包含额外内容，拒绝回滚删除")
                        }
                        if (exists(root, deletionPhase)) {
                            if (readLimited(root, deletionPhase, 128).toString(Charsets.UTF_8) != token) {
                                throw SecurityException("创建目录删除阶段令牌不匹配")
                            }
                        } else writeNew(parent, Paths.get(CREATE_DIRECTORY_DELETE_READY), token.toByteArray(Charsets.UTF_8))
                        children.forEach { directory.deleteFile(Paths.get(it)) }
                        markerRemoved = true
                    }
                    hook?.invoke(WorkspaceFilePhase.CREATION_MARKER_REMOVED)
                    // 仅 rmdir：即使新增内容出现在最后一个检查之后，也不会递归删除。
                    parent.deleteDirectory(name)
                } else {
                    val phase = owner + CREATE_FILE_DELETE_READY
                    if (exists(root, phase)) {
                        if (readLimited(root, phase, 128).toString(Charsets.UTF_8) != token) {
                            throw SecurityException("创建文件删除阶段令牌不匹配")
                        }
                    } else writeNew(parent, Paths.get(CREATE_FILE_DELETE_READY), token.toByteArray(Charsets.UTF_8))
                    parent.deleteFile(name)
                    hook?.invoke(WorkspaceFilePhase.CREATION_FILE_DELETED)
                }
            }
        } catch (failure: Throwable) {
            // 留下冲突节点和 journal；仅当公开路径空缺时恢复原位置，绝不覆盖新节点。
            if (!markerRemoved && moved && exists(root, quarantine) && !exists(root, node)) {
                runCatching { move(root, quarantine, node).commit() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    override fun releaseCreationProof(root: Path, node: List<String>, token: String) {
        requireCreationToken(token)
        withParent(root, node) { parent, name ->
            parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                val markers = CREATE_DIRECTORY_MARKERS.filter { exists(root, node + it) }
                // 所有证明先核验，再删除；冲突时保留两份证据。
                markers.forEach { marker ->
                    if (readLimited(root, node + marker, 128).toString(Charsets.UTF_8) != token) {
                        throw SecurityException("创建目录标记已变化")
                    }
                }
                markers.forEach { directory.deleteFile(Paths.get(it)) }
            }
        }
    }

    private fun requireOnlyCreationMarker(root: Path, node: List<String>) {
        withParent(root, node) { parent, name ->
            parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                val names = directory.map { it.fileName.toString() }
                if (names.any { it !in CREATE_DIRECTORY_MARKERS }) {
                    throw SecurityException("创建目录包含额外内容，拒绝回滚删除")
                }
            }
        }
    }

    override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) {
        val staging = ensureWorkspaceInternalDirectory(this, root)
        withParent(root, relative) { parent, name ->
            val temporary = Paths.get(".create-${UUID.randomUUID()}")
            openSecure(root).use { secure -> openDirectory(secure, staging).use { staged -> try {
                writeNew(staged, temporary, bytes)
                hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent))
                verifyDirectoryBinding(root, staging, directoryKey(staged))
                AndroidWorkspaceCreationProof.moveNoReplace(root, staging + temporary.toString(), relative,
                    directoryKey(secure).toString(), directoryKey(staged).toString(), directoryKey(parent).toString())
            } finally {
                runCatching { staged.deleteFile(temporary) }
            } } }
        }
    }

    override fun createFileStreaming(
        root: Path,
        relative: List<String>,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): WorkspaceStreamWriteResult {
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        val staging = ensureWorkspaceInternalDirectory(this, root)
        return withParent(root, relative) { parent, name ->
            val temporary = Paths.get(".create-${UUID.randomUUID()}")
            openSecure(root).use { secure -> openDirectory(secure, staging).use { staged -> try {
                val result = staged.newByteChannel(
                    temporary,
                    setOf<OpenOption>(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                ).use { channel ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val target = Channels.newOutputStream(channel)
                    val limited = object : OutputStream() {
                        override fun write(value: Int) {
                            ensureCapacity(1)
                            target.write(value)
                            digest.update(value.toByte())
                            size += 1
                        }

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (length == 0) return
                            ensureCapacity(length)
                            target.write(bytes, offset, length)
                            digest.update(bytes, offset, length)
                            size += length
                        }

                        override fun flush() = target.flush()

                        private fun ensureCapacity(incoming: Int) {
                            if (incoming < 0 || size > maxBytes - incoming) {
                                throw WorkspaceFileTooLargeException(maxBytes)
                            }
                        }
                    }
                    writer(limited)
                    limited.flush()
                    (channel as? FileChannel)?.force(true)
                        ?: throw IllegalStateException("当前文件系统不支持工作区文件 fsync")
                    WorkspaceStreamWriteResult(
                        sizeBytes = size,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                    )
                }
                hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent))
                verifyDirectoryBinding(root, staging, directoryKey(staged))
                AndroidWorkspaceCreationProof.moveNoReplace(root, staging + temporary.toString(), relative,
                    directoryKey(secure).toString(), directoryKey(staged).toString(), directoryKey(parent).toString())
                result
            } finally {
                runCatching { staged.deleteFile(temporary) }
            } } }
        }
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        createDirectoryInternal(root, relative, requireIdentity = true)
    }

    private fun createDirectoryInternal(root: Path, relative: List<String>, requireIdentity: Boolean) {
        requireSafeRelative(relative)
        if (!requireIdentity || relative == listOf(".infinity")) {
            openSecure(root, requireIdentity).use { rootStream ->
                val rootKey = directoryKey(rootStream)
                openDirectory(rootStream, relative.dropLast(1)).use { parent ->
                    hook?.invoke(WorkspaceFilePhase.DESCRIPTOR_OPENED)
                    verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent), requireIdentity)
                    try {
                        com.promenar.infinity.data.local.db.recovery.AndroidSnapshotDirectoryCreator.create(
                            root, rootKey.toString(), relative.dropLast(1).fold(root) { path, part -> path.resolve(part) },
                            directoryKey(parent).toString(), relative.last(),
                        )
                    } catch (failure: android.system.ErrnoException) {
                        if (failure.errno == android.system.OsConstants.EEXIST) throw java.nio.file.FileAlreadyExistsException("工作区目录已存在")
                        throw failure
                    }
                }
            }
            return
        }
        val staging = ensureWorkspaceInternalDirectory(this, root)
        val temporaryName = ".mkdir-${UUID.randomUUID()}"
        openSecure(root, requireIdentity).use { rootStream ->
            val rootKey = directoryKey(rootStream)
            hook?.invoke(WorkspaceFilePhase.DESCRIPTOR_OPENED)
            openDirectory(rootStream, staging).use { stagingStream -> try {
                com.promenar.infinity.data.local.db.recovery.AndroidSnapshotDirectoryCreator.create(
                    root, rootKey.toString(), root.resolve(".infinity"), directoryKey(stagingStream).toString(), temporaryName,
                )
                hook?.invoke(WorkspaceFilePhase.DIRECTORY_TEMP_CREATED)
                verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                stagingStream.newDirectoryStream(Paths.get(temporaryName), LinkOption.NOFOLLOW_LINKS).use { }
                openDirectory(rootStream, relative.dropLast(1)).use { parent ->
                    val parentKey = directoryKey(parent)
                    hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                    verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                    verifyDirectoryBinding(root, relative.dropLast(1), parentKey, requireIdentity)
                    AndroidWorkspaceCreationProof.moveNoReplace(root, staging + temporaryName, relative,
                        rootKey.toString(), directoryKey(stagingStream).toString(), parentKey.toString())
                    // 发布后根绑定变化时保留现场，不能再按公开目标名字回收未知节点。
                    verifyDirectoryBinding(root, emptyList(), rootKey, requireIdentity)
                }
            } finally {
                // 只尝试回收已打开根能力内的空临时目录，不触碰重新解析后的绝对路径。
                runCatching { stagingStream.deleteDirectory(Paths.get(temporaryName)) }
            } }
        }
    }

    override fun ensureDirectory(root: Path, relative: List<String>) {
        requireSafeRelative(relative)
        val present = try {
            openSecure(root).use { rootStream -> openDirectory(rootStream, relative).use { } }
            true
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }
        if (!present) {
            try {
                createDirectory(root, relative)
            } catch (race: java.nio.file.FileAlreadyExistsException) {
                openSecure(root).use { rootStream -> openDirectory(rootStream, relative).use { } }
            }
        }
    }

    override fun replaceFile(
        root: Path,
        relative: List<String>,
        bytes: ByteArray,
    ): WorkspaceFileRollback {
        val staging = ensureWorkspaceInternalDirectory(this, root)
        return withParent(root, relative) { parent, name ->
        val token = stableNameToken(relative.joinToString("/"))
        val temporary = Paths.get(".infinity-write-$token")
        val backup = Paths.get(".infinity-previous-$token")
        openSecure(root).use { secure -> openDirectory(secure, staging).use { staged ->
        writeNew(staged, temporary, bytes)
        try {
            hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
            verifyDirectoryBinding(root, relative.dropLast(1), directoryKey(parent))
            verifyDirectoryBinding(root, staging, directoryKey(staged))
            parent.move(name, staged, backup)
            try {
                staged.move(temporary, parent, name)
            } catch (failure: Throwable) {
                runCatching { staged.move(backup, parent, name) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            DescriptorRollback(
                commitAction = { withParent(root, staging + backup.toString()) { current, file -> current.deleteFile(file) } },
                rollbackAction = {
                    withParent(root, relative) { current, currentName ->
                        openSecure(root).use { currentRoot -> openDirectory(currentRoot, staging).use { currentStaging ->
                        val displaced = Paths.get(".infinity-rollback-$token")
                        current.move(currentName, currentStaging, displaced)
                        try {
                            hook?.invoke(WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE)
                            currentStaging.move(backup, current, currentName)
                        } catch (failure: Throwable) {
                            // 恢复旧版失败时先恢复新版，保证规范路径始终存在；backup 保留供同一 rollback 重试。
                            runCatching { currentStaging.move(displaced, current, currentName) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                            throw failure
                        }
                        runCatching { currentStaging.deleteFile(displaced) }
                        } }
                    }
                },
            )
        } finally {
            runCatching { staged.deleteFile(temporary) }
        }
        } }
    }
    }

    override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) {
        for (directory in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.directories) {
            if (exists(root, listOf(directory))) {
                for (prefix in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.directories) {
                    reconcileFileAt(root, relative, expectedHash, listOf(directory), stableNameToken(relative.joinToString("/")), prefix)
                }
            }
        }
        for (prefix in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.directories) {
            reconcileFileAt(root, relative, expectedHash, relative.dropLast(1), stableNameToken(relative.last()), prefix)
        }
    }

    private fun reconcileFileAt(root: Path, relative: List<String>, expectedHash: String, staging: List<String>, token: String, prefix: String = ".infinity") {
        withParent(root, relative) { parent, name ->
            openSecure(root).use { secure -> openDirectory(secure, staging).use { staged ->
            val temporary = Paths.get("$prefix-write-$token")
            val backup = Paths.get("$prefix-previous-$token")
            val displaced = Paths.get("$prefix-rollback-$token")
            if (!nodeExists(staged, temporary) && !nodeExists(staged, backup) && !nodeExists(staged, displaced)) {
                return@withParent
            }
            val targetHash = hashIfPresent(parent, name)
            val backupHash = hashIfPresent(staged, backup)
            when {
                targetHash == expectedHash -> {
                    runCatching { staged.deleteFile(backup) }
                    runCatching { staged.deleteFile(temporary) }
                    runCatching { staged.deleteFile(displaced) }
                }
                backupHash == expectedHash -> {
                    runCatching { staged.deleteFile(displaced) }
                    if (targetHash != null) parent.move(name, staged, displaced)
                    staged.move(backup, parent, name)
                    runCatching { staged.deleteFile(displaced) }
                    runCatching { staged.deleteFile(temporary) }
                }
                else -> throw IllegalStateException("工作区文件无法按数据库哈希恢复")
            }
            } }
        }
    }

    override fun move(
        root: Path,
        source: List<String>,
        target: List<String>,
    ): WorkspaceFileRollback {
        requireSafeRelative(source)
        requireSafeRelative(target)
        descriptorMove(root, source, target)
        return DescriptorRollback(
            commitAction = {},
            rollbackAction = { descriptorMove(root, target, source) },
        )
    }

    override fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback {
        requireSafeRelative(source)
        requireSafeRelative(target)
        if (sourceRoot == targetRoot) return move(sourceRoot, source, target)
        fun transfer(fromRoot: Path, from: List<String>, toRoot: Path, to: List<String>) {
            openSecure(fromRoot).use { fromStream ->
                openSecure(toRoot).use { toStream ->
                    openDirectory(fromStream, from.dropLast(1)).use { fromParent ->
                        openDirectory(toStream, to.dropLast(1)).use { toParent ->
                            hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                            verifyDirectoryBinding(fromRoot, from.dropLast(1), directoryKey(fromParent))
                            verifyDirectoryBinding(toRoot, to.dropLast(1), directoryKey(toParent))
                            AndroidWorkspaceCreationProof.moveAcrossRootsNoReplace(fromRoot, from, toRoot, to,
                                directoryKey(fromStream).toString(), directoryKey(toStream).toString(),
                                directoryKey(fromParent).toString(), directoryKey(toParent).toString())
                        }
                    }
                }
            }
        }
        transfer(sourceRoot, source, targetRoot, target)
        return DescriptorRollback({}, { transfer(targetRoot, target, sourceRoot, source) })
    }

    override fun stageDelete(root: Path, source: List<String>, deletionToken: String): WorkspaceFileRollback {
        requireSafeRelative(source)
        require(deletionToken.matches(Regex("[0-9a-f]{24}"))) { "删除令牌无效" }
        val internal = ensureWorkspaceInternalDirectory(this, root)
        val tombstone = internal + listOf("tombstones", deletionToken)
        ensureDirectory(root, internal + "tombstones")
        descriptorMove(root, source, tombstone)
        return DescriptorRollback(
            commitAction = { deleteNode(root, tombstone) },
            rollbackAction = { descriptorMove(root, tombstone, source) },
        )
    }

    override fun recoverTombstones(
        root: Path,
        restorePath: (String) -> List<String>?,
    ): TombstoneRecoveryReport {
        val attention = mutableListOf<String>()
        for (directory in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.tombstoneDirectories) {
        if (!exists(root, directory)) continue
        listChildren(root, directory).forEach { token ->
            try {
                require(token.matches(Regex("[0-9a-f]{24}"))) { "删除令牌无效" }
                val tombstone = directory + token
                val source = restorePath(token)
                if (source == null) deleteNode(root, tombstone)
                else {
                    requireSafeRelative(source)
                    descriptorMove(root, tombstone, source)
                }
            } catch (_: Exception) {
                attention += token
            }
        }
        if (directory in listOf(listOf(".infinity_tombstones"), listOf(".nexara_tombstones")) && listChildren(root, directory).isEmpty()) deleteNonRecursive(root, directory)
        }
        return TombstoneRecoveryReport(attention)
    }

    override fun delete(root: Path, source: List<String>) {
        deleteNode(root, source)
    }

    override fun cleanupTombstones(root: Path) {
        for (directory in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.tombstoneDirectories) {
            if (!exists(root, directory)) continue
            openSecure(root).use { secure -> openDirectory(secure, directory).use(::deleteChildren) }
        }
    }

    private fun descriptorMove(root: Path, source: List<String>, target: List<String>) {
        openSecure(root).use { rootStream ->
            openDirectory(rootStream, source.dropLast(1)).use { sourceParent ->
                openDirectory(rootStream, target.dropLast(1)).use { targetParent ->
                    hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
                    verifyDirectoryBinding(root, source.dropLast(1), directoryKey(sourceParent))
                    verifyDirectoryBinding(root, target.dropLast(1), directoryKey(targetParent))
                    AndroidWorkspaceCreationProof.moveNoReplace(root, source, target,
                        directoryKey(rootStream).toString(), directoryKey(sourceParent).toString(),
                        directoryKey(targetParent).toString())
                }
            }
        }
    }

    private fun ensureSystemDirectory(root: Path, name: String) {
        openSecure(root).use { secure ->
            val exists = try {
                secure.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS).use { }
                true
            } catch (_: java.nio.file.NoSuchFileException) {
                false
            }
            if (!exists) createDirectory(root, listOf(name))
        }
    }

    private fun deleteNode(root: Path, relative: List<String>) {
        withParent(root, relative) { parent, name ->
            val directory = runCatching {
                parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (directory == null) {
                parent.deleteFile(name)
            } else {
                directory.use(::deleteChildren)
                parent.deleteDirectory(name)
            }
        }
    }

    private fun deleteChildren(directory: SecureDirectoryStream<Path>) {
        directory.toList().forEach { childPath ->
            val name = childPath.fileName
            val child = runCatching {
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (child == null) {
                directory.deleteFile(name)
            } else {
                child.use(::deleteChildren)
                directory.deleteDirectory(name)
            }
        }
    }

    private inline fun <T> withParent(
        root: Path,
        relative: List<String>,
        block: (SecureDirectoryStream<Path>, Path) -> T,
    ): T {
        requireSafeRelative(relative)
        openSecure(root).use { rootStream ->
            openDirectory(rootStream, relative.dropLast(1)).use { parent ->
                hook?.invoke(WorkspaceFilePhase.DESCRIPTOR_OPENED)
                return block(parent, Paths.get(relative.last()))
            }
        }
    }

    private fun openDirectory(
        root: SecureDirectoryStream<Path>,
        relative: List<String>,
    ): SecureDirectoryStream<Path> {
        var current = root.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS)
        try {
            relative.forEach { segment ->
                val next = current.newDirectoryStream(Paths.get(segment), LinkOption.NOFOLLOW_LINKS)
                current.close()
                current = next
            }
            return current
        } catch (failure: Throwable) {
            runCatching { current.close() }
            throw failure
        }
    }

    private fun writeNew(directory: SecureDirectoryStream<Path>, name: Path, bytes: ByteArray) {
        directory.newByteChannel(
            name,
            setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ).use { channel ->
            var offset = 0
            while (offset < bytes.size) {
                offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            }
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("当前文件系统不支持工作区文件 fsync")
        }
    }

    private fun nodeExists(directory: SecureDirectoryStream<Path>, name: Path): Boolean = try {
        directory.getFileAttributeView(
            name,
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        true
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }

    private fun hashIfPresent(directory: SecureDirectoryStream<Path>, name: Path): String? = try {
        directory.newByteChannel(
            name,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            hook?.invoke(WorkspaceFilePhase.RECONCILE_CONTENT_READ)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                digest.update(buffer.array(), 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun stableNameToken(value: String): String = sha256(value.toByteArray()).take(24)

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun directoryKey(directory: SecureDirectoryStream<Path>): Any =
        directory.getFileAttributeView(
            Paths.get("."),
            java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes().fileKey()
            ?: throw SecurityException("工作区目录缺少稳定 fileKey")

    private fun verifyDirectoryBinding(
        root: Path,
        relative: List<String>,
        expectedKey: Any,
        requireIdentity: Boolean = true,
    ) {
        try {
            openSecure(root, requireIdentity).use { rootStream ->
                openDirectory(rootStream, relative).use { current ->
                    if (directoryKey(current) != expectedKey) {
                        throw SecurityException("工作区目录路径绑定发生变化")
                    }
                }
            }
        } catch (security: SecurityException) {
            throw security
        } catch (failure: Throwable) {
            throw SecurityException("工作区目录路径绑定无法复核", failure)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openSecure(root: Path, requireIdentity: Boolean = true): SecureDirectoryStream<Path> {
        val expected = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (expected.isSymbolicLink || !expected.isDirectory || expected.fileKey() == null) {
            throw SecurityException("工作区根目录身份无效")
        }
        val stream: DirectoryStream<Path> = directoryOpener(root)
        val secure = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("当前文件系统不支持 SecureDirectoryStream，拒绝降低路径安全保证")
        }
        val actual = try {
            secure.getFileAttributeView(
                Paths.get("."),
                java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
        } catch (failure: Throwable) {
            secure.close()
            throw failure
        }
        if (!actual.isDirectory || actual.fileKey() != expected.fileKey()) {
            secure.close()
            throw SecurityException("工作区根目录在打开期间发生替换")
        }
        if (requireIdentity) verifyRootIdentity(root, secure, actual.fileKey())
        return secure
    }

    private fun ensureRootIdentity(
        path: Path,
        root: SecureDirectoryStream<Path>,
        initialize: Boolean,
        expectedIdentity: String?,
    ): String {
        val operationKey = directoryKey(root).toString()
        val key = PersistentFileIdentity.inode(identityFileKey(operationKey))
        val marker = readLegacyIdentity(root)
        val proof = marker?.let(PersistentFileIdentity::parseMarker)
        if (proof != null && proof.second != key) throw SecurityException("工作区根身份标记与目录 fileKey 不一致")
        val persistentNonce = if (identityStorage != null || marker == null) readRootNonce(path, operationKey, marker) else null
        if (persistentNonce != null && proof != null && persistentNonce != proof.first) {
            throw SecurityException("工作区根目录FD身份与标记冲突")
        }
        val nonce = persistentNonce ?: proof?.first ?: if (initialize) UUID.randomUUID().toString()
            else throw SecurityException("工作区根缺少持久身份标记")
        val identity = PersistentFileIdentity.encode(nonce, key)
        if (expectedIdentity != null && identity != expectedIdentity) {
            throw SecurityException("工作区根身份与数据库认领不一致")
        }
        val record = PersistentFileIdentity.marker(nonce, key)
        val stored = try { identityStorage?.readIdentity(identity) } catch (failure: Exception) {
            identityStorage?.recordFailure(identity, "root-identity-read", failure)
            null
        }
        if (stored != null && stored != record) throw SecurityException("工作区根目录FD身份与私有索引冲突")
        if (identityStorage == null) {
            if (marker == null && persistentNonce == null) writeNew(root, Paths.get(ROOT_IDENTITY_FILE), record.toByteArray(Charsets.UTF_8))
            else if (marker != null && PersistentFileIdentity.parseLegacyMarker(marker) != null) {
                val temporary = Paths.get(".root-identity-${UUID.randomUUID()}")
                try {
                    writeNew(root, temporary, record.toByteArray(Charsets.UTF_8))
                    verifyDirectoryBinding(path, emptyList(), directoryKey(root), requireIdentity = false)
                    if (readLegacyIdentity(root) != marker) throw SecurityException("工作区根身份标记在迁移期间发生变化")
                    root.move(temporary, root, Paths.get(ROOT_IDENTITY_FILE))
                } finally { runCatching { root.deleteFile(temporary) } }
            }
        } else {
            var durableNonce = persistentNonce != null
            try {
                if (persistentNonce == null) AndroidWorkspaceCreationProof.withRootDirectory(path, operationKey) {
                    AndroidWorkspaceFileIdentity.createRoot(it.fd, nonce)
                }
                durableNonce = true
                identityStorage.storeIdentity(identity, record)
                if (marker != null) com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.rootIdentityNames.forEach { name ->
                    if (readIdentityMarker(root, name) != null) root.deleteFile(Paths.get(name))
                }
            } catch (failure: Exception) {
                identityStorage.recordFailure(identity, "root-identity", failure)
                if (marker == null && !durableNonce) throw failure
            }
        }
        return identity
    }

    private fun verifyRootIdentity(path: Path, root: SecureDirectoryStream<Path>, fileKey: Any?) {
        val operationKey = fileKey?.toString() ?: throw SecurityException("工作区根缺少稳定fileKey")
        val key = PersistentFileIdentity.inode(identityFileKey(fileKey))
        val marker = readLegacyIdentity(root)
        val proof = marker?.let(PersistentFileIdentity::parseMarker)
        val nonce = if (identityStorage != null || proof == null) readRootNonce(path, operationKey, marker) else null
        if (nonce != null && proof != null && nonce != proof.first) throw SecurityException("工作区根目录FD身份与标记冲突")
        val actualNonce = nonce ?: proof?.first ?: throw SecurityException("工作区根缺少持久身份标记")
        val trusted = identityStorage?.privateIdentity(path) ?: WorkspaceMutationCoordinator.expectedIdentity(path)
            ?: throw SecurityException("工作区根缺少进程内数据库身份绑定")
        if ((proof != null && proof.second != key) || PersistentFileIdentity.encode(actualNonce, key) != trusted) {
            throw SecurityException("工作区根身份标记与目录 fileKey 不一致")
        }
    }

    private fun readRootNonce(path: Path, key: String, marker: String?): String? = readWorkspaceIdentityAttribute(marker != null,
        { rootNonceReader(path, key) },
        { identityStorage?.recordFailure("root-xattr", "root-xattr-unsupported", it) })

    override fun captureRootIdentityRestorer(root: Path): () -> Boolean {
        if (identityStorage == null) return { false }
        val (key, token) = openSecure(root).use { secure ->
            val key = directoryKey(secure).toString()
            key to readRootNonce(root, key, readLegacyIdentity(secure))
        }
        if (token == null) return { false }
        return {
            AndroidWorkspaceCreationProof.withRootDirectory(root, key) { file ->
                val current = runCatching { AndroidWorkspaceFileIdentity.readRoot(file.fd) }.getOrNull()
                if (current == token) false else {
                    AndroidWorkspaceFileIdentity.restoreRoot(file.fd, token)
                    true
                }
            }
        }
    }

    /**
     * 旧认领为 `nonce|sha256(完整fileKey字符串)`。旧标记提供签发摘要的原始 fileKey，需与当前 inode 相同；
     * 没有标记时仍要求目录属性 nonce 与当前完整 fileKey 按旧算法逐字吻合。此处只核验，不改写证据。
     */
    override fun adoptLegacyRootIdentity(root: Path, legacyIdentity: String): String? {
        if (!LEGACY_ROOT_IDENTITY.matches(legacyIdentity)) return null
        return openSecure(root, requireIdentity = false).use { secure ->
            val operationKey = directoryKey(secure).toString()
            val marker = readLegacyIdentity(secure)
            if (marker != null) {
                val proof = PersistentFileIdentity.parseLegacyMarker(marker) ?: return@use null
                val reportedKey = identityFileKey(operationKey)
                if (PersistentFileIdentity.inode(proof.second) != PersistentFileIdentity.inode(reportedKey) ||
                    "${proof.first}|${sha256(proof.second.toByteArray(Charsets.UTF_8))}" != legacyIdentity) return@use null
                val attributeNonce = try { rootNonceReader(root, operationKey) } catch (_: UnsupportedOperationException) { null }
                if (attributeNonce != null && attributeNonce != proof.first) return@use null
                return@use PersistentFileIdentity.encode(proof.first, reportedKey)
            }
            val nonce = try { rootNonceReader(root, operationKey) } catch (_: UnsupportedOperationException) { null }
                ?: return@use null
            val reportedKey = identityFileKey(operationKey).toString()
            if ("$nonce|${sha256(reportedKey.toByteArray(Charsets.UTF_8))}" != legacyIdentity) return@use null
            PersistentFileIdentity.encode(nonce, reportedKey)
        }
    }

    override fun hasExternalRootIdentity(root: Path): Boolean = openSecure(root, requireIdentity = false).use { secure ->
        val key = directoryKey(secure).toString()
        val nonce = try { AndroidWorkspaceCreationProof.withRootDirectory(root, key) { AndroidWorkspaceFileIdentity.readRoot(it.fd) } }
            catch (_: UnsupportedOperationException) { null }
        nonce != null || readLegacyIdentity(secure) != null
    }

    private fun readLegacyIdentity(root: SecureDirectoryStream<Path>): String? {
        val records = com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.rootIdentityNames.mapNotNull { readIdentityMarker(root, it) }
        if (records.size > 1 && records.map(PersistentFileIdentity::parseMarker).distinct().size > 1) {
            throw SecurityException("工作区新旧根身份标记冲突")
        }
        return records.firstOrNull()
    }

    private fun readIdentityMarker(root: SecureDirectoryStream<Path>, name: String): String? = try {
        root.newByteChannel(
            Paths.get(name),
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(256)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024) throw SecurityException("工作区根身份标记过大")
                output.write(buffer.array(), 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun requireSafeRelative(relative: List<String>) {
        require(relative.isNotEmpty()) { "工作区相对路径不能为空" }
        if (relative.any { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) {
            throw SecurityException("工作区相对路径无效")
        }
    }

    private class DescriptorRollback(
        private val commitAction: () -> Unit,
        private val rollbackAction: () -> Unit,
    ) : WorkspaceFileRollback {
        private var completed = false

        @Synchronized
        override fun commit() {
            if (completed) return
            commitAction()
            completed = true
        }

        @Synchronized
        override fun rollback() {
            if (completed) return
            rollbackAction()
            completed = true
        }
    }

    private companion object {
        const val ROOT_IDENTITY_FILE = ".infinity_root_identity"
        val LEGACY_ROOT_IDENTITY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[|][0-9a-f]{64}")
    }
}
