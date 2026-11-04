package com.promenar.infinity.data.repository

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.io.OutputStream

/** Robolectric 会隔离 JDK 接口类加载器，因此仓储测试使用可回滚的真实磁盘夹具。 */
class TestWorkspaceFileOps(
    private val identityStorage: WorkspaceInternalStorage? = null,
    private val hook: ((WorkspaceFilePhase) -> Unit)? = null,
) : WorkspaceFileOps {
    var identityFileKeyOverride: ((String) -> String)? = null
    var inspectedFileKeyOverride: ((Path, String) -> String)? = null

    /** 业务夹具的属性端口；真实FD/xattr持久性由Android仪器测试证明。 */
    fun removeFileIdentityForTesting(root: Path, node: List<String>) {
        fileIdentityTokens.remove(attributeKey(root, resolve(root, node, true)))
    }
    override fun ensureRoot(
        root: Path,
        initializeIdentity: Boolean,
        expectedIdentity: String?,
        allowUnboundParent: Boolean,
    ): String {
        Files.createDirectories(root)
        requireSafeRoot(root)
        val actualKey = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        val key = identityFileKeyOverride?.invoke(actualKey.toString()) ?: actualKey.toString()
        val marker = root.resolve(".infinity_root_identity")
        val existing = if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(marker).toString(Charsets.UTF_8) else null
        val nonce = if (existing != null) {
            val proof = PersistentFileIdentity.parseMarker(existing)
            if (proof.second != PersistentFileIdentity.inode(key)) throw SecurityException("测试工作区根 inode 不匹配")
            proof.first
        } else if (identityStorage != null) PersistentFileIdentity.nonce(PersistentFileIdentity.read(root))
        else {
            if (!initializeIdentity) throw SecurityException("测试工作区根缺少持久身份标记")
            UUID.randomUUID().toString()
        }
        val identity = PersistentFileIdentity.encode(nonce, key)
        if (expectedIdentity != null && identity != expectedIdentity) throw SecurityException("测试工作区根认领不匹配")
        val value = PersistentFileIdentity.marker(nonce, key)
        if (identityStorage != null) {
            identityStorage.storeIdentity(identity, value)
            Files.deleteIfExists(marker)
        } else if (existing == null) Files.write(marker, value.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW)
        return identity
    }

    override fun read(root: Path, relative: List<String>): ByteArray =
        Files.readAllBytes(resolve(root, relative, requireTarget = true))

    override fun readLimited(root: Path, relative: List<String>, maxBytes: Long): ByteArray {
        val target = resolve(root, relative, requireTarget = true)
        if (Files.size(target) > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
        return Files.readAllBytes(target).also { bytes ->
            if (bytes.size.toLong() > maxBytes) throw WorkspaceFileTooLargeException(maxBytes)
        }
    }

    override fun exists(root: Path, relative: List<String>): Boolean =
        Files.exists(resolve(root, relative, requireTarget = true), LinkOption.NOFOLLOW_LINKS)

    override fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity {
        val target = resolve(root, relative, requireTarget = true)
        val actualKey = Files.readAttributes(
            target,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()?.toString() ?: throw SecurityException("测试节点缺少 fileKey")
        val stable = PersistentFileIdentity.read(target)
        val fileKey = inspectedFileKeyOverride?.invoke(target, stable) ?: stable
        return if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            WorkspaceNodeIdentity("directory", 0, null, fileKey)
        } else {
            WorkspaceNodeIdentity(
                "file",
                Files.size(target),
                com.promenar.infinity.infra.util.Sha256Utils.hashFile(target.toFile()),
                fileKey,
            )
        }
    }

    override fun listChildren(root: Path, relative: List<String>): List<String> =
        Files.newDirectoryStream(if (relative.isEmpty()) root else resolve(root, relative, true)).use { directory ->
            directory.map { it.fileName.toString() }
        }

    override fun deleteNonRecursive(root: Path, relative: List<String>) {
        Files.delete(resolve(root, relative, true))
    }

    override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) {
        val target = resolve(root, relative)
        if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.NoSuchFileException(target.parent.toString())
        }
        hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        fileIdentityTokens.remove(attributeKey(root, target))
    }

    override fun retainCreationProof(root: Path, node: List<String>, owner: List<String>, token: String) {
        requireCreationToken(token)
        if (inspect(root, node).kind == "directory") {
            createFile(root, node + CREATE_DIRECTORY_MARKER, token.toByteArray())
        } else {
            check(fileIdentityTokens.putIfAbsent(attributeKey(root, resolve(root, node, true)), token) == null) {
                "测试文件已有持久身份属性"
            }
        }
    }

    override fun verifyCreationProof(
        root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity,
    ) {
        requireCreationToken(token)
        if (inspect(root, node) != expected) throw SecurityException("测试创建节点身份改变")
        if (expected.kind == "directory") {
            if (readLimited(root, node + CREATE_DIRECTORY_MARKER, 128).toString(Charsets.UTF_8) != token) {
                throw SecurityException("测试创建目录标记不匹配")
            }
        } else if (fileIdentityTokens[attributeKey(root, resolve(root, node, true))] != token) {
            throw SecurityException("测试创建文件持久身份不匹配")
        }
    }

    override fun deleteCreatedNode(
        root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity,
    ) {
        val quarantine = owner + CREATE_ROLLBACK_NODE
        val deletionPhase = owner + CREATE_DIRECTORY_DELETE_READY
        if (node == quarantine && expected.kind == "directory" && exists(root, deletionPhase) &&
            !exists(root, node + CREATE_DIRECTORY_MARKER)) {
            if (readLimited(root, deletionPhase, 128).toString(Charsets.UTF_8) != token || inspect(root, node) != expected) {
                throw SecurityException("测试目录删除阶段证明不匹配")
            }
            Files.delete(resolve(root, quarantine, true))
            return
        }
        verifyCreationProof(root, node, owner, token, expected)
        fun requireOnlyMarker(relative: List<String>) {
            Files.newDirectoryStream(resolve(root, relative, true)).use { directory ->
                if (directory.map { it.fileName.toString() } != listOf(CREATE_DIRECTORY_MARKER)) {
                    throw SecurityException("测试创建目录包含额外内容")
                }
            }
        }
        if (expected.kind == "directory") requireOnlyMarker(node)
        val moved = node != quarantine
        if (moved) move(root, node, quarantine).commit()
        var markerRemoved = false
        try {
            verifyCreationProof(root, quarantine, owner, token, expected)
            if (expected.kind == "directory") {
                requireOnlyMarker(quarantine)
                if (!exists(root, deletionPhase)) createFile(root, deletionPhase, token.toByteArray())
                if (readLimited(root, deletionPhase, 128).toString(Charsets.UTF_8) != token) {
                    throw SecurityException("测试目录删除阶段令牌不匹配")
                }
                Files.delete(resolve(root, quarantine + CREATE_DIRECTORY_MARKER, true))
                markerRemoved = true
                hook?.invoke(WorkspaceFilePhase.CREATION_MARKER_REMOVED)
            } else {
                val phase = owner + CREATE_FILE_DELETE_READY
                if (!exists(root, phase)) createFile(root, phase, token.toByteArray())
                if (readLimited(root, phase, 128).toString(Charsets.UTF_8) != token) {
                    throw SecurityException("测试文件删除phase不匹配")
                }
                removeFileIdentityForTesting(root, quarantine)
            }
            Files.delete(resolve(root, quarantine, true))
            if (expected.kind == "file") hook?.invoke(WorkspaceFilePhase.CREATION_FILE_DELETED)
        } catch (failure: Throwable) {
            if (!markerRemoved && moved && exists(root, quarantine) && !exists(root, node)) move(root, quarantine, node).commit()
            throw failure
        }
    }

    override fun releaseCreationProof(root: Path, node: List<String>, token: String) {
        val marker = node + CREATE_DIRECTORY_MARKER
        if (!exists(root, marker)) return
        if (readLimited(root, marker, 128).toString(Charsets.UTF_8) != token) {
            throw SecurityException("测试目录归属标记改变")
        }
        Files.delete(resolve(root, marker, true))
    }

    override fun createFileStreaming(
        root: Path,
        relative: List<String>,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): WorkspaceStreamWriteResult {
        val target = resolve(root, relative)
        val temporary = target.resolveSibling(".create-${UUID.randomUUID()}")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                writer(object : OutputStream() {
                    override fun write(value: Int) {
                        ensureCapacity(1)
                        output.write(value)
                        digest.update(value.toByte())
                        size++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        ensureCapacity(length)
                        output.write(bytes, offset, length)
                        digest.update(bytes, offset, length)
                        size += length
                    }
                    private fun ensureCapacity(incoming: Int) {
                        if (size > maxBytes - incoming) throw WorkspaceFileTooLargeException(maxBytes)
                    }
                })
            }
            hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
            Files.move(temporary, target)
            fileIdentityTokens.remove(attributeKey(root, target))
            return WorkspaceStreamWriteResult(
                size,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        val target = resolve(root, relative)
        Files.createDirectory(target)
    }

    override fun ensureDirectory(root: Path, relative: List<String>) {
        val target = resolve(root, relative)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) {
                throw java.nio.file.NoSuchFileException(target.parent.toString())
            }
            Files.createDirectory(target)
        }
        requireSafeRoot(root)
        rejectSymlinkPath(root, relative)
    }

    override fun replaceFile(root: Path, relative: List<String>, bytes: ByteArray): WorkspaceFileRollback {
        val target = resolve(root, relative, requireTarget = true)
        val token = com.promenar.infinity.infra.util.Sha256Utils.hash(target.fileName.toString()).take(24)
        val backup = target.resolveSibling(".infinity-previous-$token")
        val replacement = target.resolveSibling(".infinity-write-$token")
        Files.write(replacement, bytes)
        fileIdentityTokens.remove(attributeKey(root, replacement))
        hook?.invoke(WorkspaceFilePhase.BEFORE_MUTATION)
        Files.move(target, backup)
        Files.move(replacement, target)
        return rollback(
            commit = { Files.deleteIfExists(backup) },
            rollback = {
                val displaced = target.resolveSibling(".infinity-rollback-$token")
                Files.move(target, displaced)
                try {
                    hook?.invoke(WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE)
                    Files.move(backup, target)
                } catch (failure: Throwable) {
                    runCatching { Files.move(displaced, target) }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
                Files.deleteIfExists(displaced)
            },
        )
    }

    override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) {
        val target = resolve(root, relative)
        val token = com.promenar.infinity.infra.util.Sha256Utils.hash(target.fileName.toString()).take(24)
        val temporary = target.resolveSibling(".infinity-write-$token")
        val backup = target.resolveSibling(".infinity-previous-$token")
        val displaced = target.resolveSibling(".infinity-rollback-$token")
        if (listOf(temporary, backup, displaced).none { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) return
        fun hash(path: Path): String? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            hook?.invoke(WorkspaceFilePhase.RECONCILE_CONTENT_READ)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, java.nio.file.StandardOpenOption.READ).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        when {
            hash(target) == expectedHash -> {
                Files.deleteIfExists(backup)
                Files.deleteIfExists(temporary)
                Files.deleteIfExists(displaced)
            }
            hash(backup) == expectedHash -> {
                Files.deleteIfExists(displaced)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(target, displaced)
                Files.move(backup, target)
                Files.deleteIfExists(displaced)
                Files.deleteIfExists(temporary)
            }
            else -> throw IllegalStateException("测试文件哈希与数据库不一致")
        }
    }

    override fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
        val from = resolve(root, source, requireTarget = true)
        val to = resolve(root, target)
        if (!Files.isDirectory(to.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw java.nio.file.NoSuchFileException(to.parent.toString())
        }
        Files.move(from, to)
        return rollback(rollback = {
            Files.move(to, from)
        })
    }

    override fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback {
        if (sourceRoot == targetRoot) return move(sourceRoot, source, target)
        fun transfer(fromRoot: Path, fromPath: List<String>, toRoot: Path, toPath: List<String>) {
            val from = resolve(fromRoot, fromPath, true)
            val to = resolve(toRoot, toPath)
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(to.toString())
            val tokens = Files.walk(from).use { nodes -> nodes.map { node ->
                val key = Files.readAttributes(node, java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS).fileKey().toString()
                key to fileIdentityTokens["${fromRoot.toAbsolutePath().normalize()}|$key"]
            }.toList() }
            Files.move(from, to)
            tokens.forEach { (key, token) -> if (token != null) {
                fileIdentityTokens.remove("${fromRoot.toAbsolutePath().normalize()}|$key")
                fileIdentityTokens["${toRoot.toAbsolutePath().normalize()}|$key"] = token
            } }
        }
        transfer(sourceRoot, source, targetRoot, target)
        return rollback(rollback = { transfer(targetRoot, target, sourceRoot, source) })
    }

    override fun stageDelete(root: Path, source: List<String>, deletionToken: String): WorkspaceFileRollback {
        val from = resolve(root, source, requireTarget = true)
        val tombstones = resolve(root, listOf(".infinity_tombstones"))
        Files.createDirectories(tombstones)
        val staged = tombstones.resolve(deletionToken)
        Files.move(from, staged)
        return rollback(
            commit = {
                deleteTree(staged)
                // 与生产实现一致：暂存目录不留在用户可见的根目录下。
                Files.list(tombstones).use { if (!it.findAny().isPresent) Files.delete(tombstones) }
            },
            rollback = {
                Files.move(staged, from)
            },
        )
    }

    override fun recoverTombstones(
        root: Path,
        restorePath: (String) -> List<String>?,
    ): TombstoneRecoveryReport {
        val tombstones = resolve(root, listOf(".infinity_tombstones"))
        if (!Files.exists(tombstones, LinkOption.NOFOLLOW_LINKS)) return TombstoneRecoveryReport()
        val attention = mutableListOf<String>()
        Files.list(tombstones).use { entries ->
            entries.toList().forEach { staged ->
                try {
                    val source = restorePath(staged.fileName.toString())
                    if (source == null) deleteTree(staged)
                    else Files.move(staged, resolve(root, source))
                } catch (_: Exception) {
                    attention += staged.fileName.toString()
                }
            }
        }
        return TombstoneRecoveryReport(attention)
    }

    override fun delete(root: Path, source: List<String>) {
        deleteTree(resolve(root, source, requireTarget = true))
    }

    override fun cleanupTombstones(root: Path) {
        val tombstones = resolve(root, listOf(".infinity_tombstones"))
        if (Files.exists(tombstones, LinkOption.NOFOLLOW_LINKS)) deleteTree(tombstones)
    }

    private fun resolve(root: Path, relative: List<String>, requireTarget: Boolean = false): Path {
        requireSafeRoot(root)
        require(relative.isNotEmpty() && relative.none { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it })
        rejectSymlinkPath(root, if (requireTarget) relative else relative.dropLast(1))
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }.normalize()
        if (!target.startsWith(root.normalize())) throw SecurityException("测试夹具路径越界")
        if (requireTarget && Files.isSymbolicLink(target)) throw SecurityException("测试夹具拒绝符号链接")
        return target
    }

    private fun requireSafeRoot(root: Path) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw SecurityException("测试夹具工作区根无效")
        }
    }

    private fun rejectSymlinkPath(root: Path, relative: List<String>) {
        var current = root
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw SecurityException("测试夹具拒绝符号链接路径")
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.newDirectoryStream(path).use { entries -> entries.forEach(::deleteTree) }
        }
        Files.deleteIfExists(path)
    }

    private fun rollback(
        commit: () -> Unit = {},
        rollback: () -> Unit,
    ) = object : WorkspaceFileRollback {
        override fun commit() = commit.invoke()
        override fun rollback() = rollback.invoke()
    }

    private fun attributeKey(root: Path, target: Path): String = root.toAbsolutePath().normalize().toString() + "|" +
        Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey().toString()

    private companion object {
        val fileIdentityTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
