package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.data.storage.consumeUnlocked
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 所有文件内容操作都使用解析后的宿主路径，打开最终文件时禁止内核再跟随链接。 */
class LinuxFileSystemImpl internal constructor(
    private val rootfs: File,
    private val isReady: () -> Boolean,
    private val metadata: LinuxFileMetadata,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val directoryRoot: String? = null,
    private val rejectGuestLinks: Boolean = false,
    private val trustedMutationRoot: String? = directoryRoot,
    private val workspaceNames: Boolean = false,
) : LinuxFileSystem {
    constructor(runtime: LinuxRuntime) : this(runtime.paths.rootfs, { runtime.state.value is LinuxRuntimeState.Ready }, LinuxFileAndroidMetadata)

    private val access = metadata.access
    internal val paths by lazy { LinuxFilePaths(rootfs, access, rejectGuestLinks, workspaceNames) }
    private val mutex by lazy { locks.getOrPut(paths.root.toString()) { Mutex() } }

    internal fun asWorkspaceDirectory() = LinuxFileSystemImpl(rootfs, isReady, metadata, io,
        rejectGuestLinks = true, workspaceNames = true)

    internal fun forHome(available: () -> Boolean = { true }) = forDirectory("/root", available)

    internal fun forDirectory(directory: String, available: () -> Boolean = { true }): LinuxFileSystemImpl {
        com.promenar.infinity.data.workspace.LinuxWorkspaceLocation.requireDirectory(directory)
        return LinuxFileSystemImpl(rootfs, { isReady() && available() }, metadata, io, directoryRoot = directory)
    }

    /** Agent 文件路径在真正解析时拒绝物理链接，避免预检后被 guest 换成其它目标。 */
    internal fun forAgentTools() = forAgentTools(trustedMutationRoot)
    internal fun forAgentTools(trustedDirectory: String?): LinuxFileSystemImpl {
        trustedDirectory?.let(com.promenar.infinity.data.workspace.LinuxWorkspaceLocation::requireDirectory)
        return LinuxFileSystemImpl(rootfs, isReady, metadata, io, directoryRoot, rejectGuestLinks = true, trustedMutationRoot = trustedDirectory, workspaceNames = workspaceNames)
    }

    override suspend fun list(path: String): List<LinuxFileEntry> = operation {
        val normalized = normalize(path)
        val directory = resolvePath(normalized)
        requireDirectory(directory)
        run {
            access.children(directory).filterNot { LinuxFilePaths.isLink2SymlinkName(it.fileName.toString()) || rejectGuestLinks && access.isLink(it) }.mapNotNull {
                currentCoroutineContext().ensureActive()
                entry(join(normalized, it.fileName.toString()), it)
            }.sortedWith(compareByDescending<LinuxFileEntry> { it.type == LinuxFileType.DIRECTORY || it.targetIsDirectory }.thenBy { it.name })
        }
    }

    override suspend fun stat(path: String): LinuxFileEntry? = operation {
        val normalized = normalize(path)
        try { entry(normalized, resolvePath(normalized, followFinal = false)) }
        catch (_: NoSuchFileException) { null }
    }

    override suspend fun readText(path: String, maxBytes: Int): LinuxTextContent = operation {
        if (maxBytes < 0 || maxBytes > MAX_READ_BYTES) fail(LinuxFileErrorCode.IO)
        val source = resolvePath(path)
        val attributes = regularAttributes(source)
        val bytes = read(source, 0, maxBytes)
        val analysis = LinuxFileText.analyze(bytes, attributes.size() > bytes.size)
        LinuxTextContent(bytes, attributes.size(), analysis.isBinary, analysis.encoding, analysis.lineEnding, analysis.lineCount, attributes.lastModifiedTime().toMillis())
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray = operation {
        if (offset < 0 || length < 0 || length > MAX_READ_BYTES) fail(LinuxFileErrorCode.IO)
        val source = resolvePath(path)
        regularAttributes(source)
        read(source, offset, length)
    }

    /** 移动补偿使用进程内对象身份，不能以路径和时间戳代替。 */
    internal suspend fun fileIdentity(path: String): String = operation {
        access.attributes(resolvePath(path, followFinal = false)).fileKey()?.toString() ?: fail(LinuxFileErrorCode.IO)
    }

    override suspend fun availableBytes(parent: String): Long = operation {
        val directory = resolvePath(parent)
        requireDirectory(directory)
        access.directory(directory) { it.toFile().usableSpace }
    }

    override suspend fun <T> readStreaming(path: String, maxBytes: Long, reader: (java.io.InputStream) -> T): T =
        readStream(path, maxBytes) { reader(it) }

    /** 文件工具的挂起回调沿用同一个句柄和版本复核。 */
    internal suspend fun <T> readStream(path: String, maxBytes: Long, reader: suspend (java.io.InputStream) -> T): T = operation {
        val source = resolvePath(path)
        val original = regularAttributes(source)
        if (original.size() > maxBytes) fail(LinuxFileErrorCode.IO)
        access.openRead(source).use { opened ->
            val revision = opened.revision()
            val value = mutex.consumeUnlocked { reader(java.nio.channels.Channels.newInputStream(opened.channel)) }
            val after = regularAttributes(source)
            if (opened.revision() != revision || after.fileKey() != original.fileKey() || after.size() != original.size() ||
                after.lastModifiedTime() != original.lastModifiedTime()) fail(LinuxFileErrorCode.CHANGED)
            value
        }
    }

    override suspend fun writeStreaming(path: String, size: Long, expected: LinuxFileEntry?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: () -> Unit, writer: (java.io.OutputStream) -> String): LinuxFileEntry =
        writeStream(path, size, expected, check, beforeCommit, onCommitted) { writer(it) }

    /** 文件工具与同步传输共用大小、版本、空间和原子提交校验。 */
    internal suspend fun writeStream(path: String, size: Long, expected: LinuxFileEntry?, check: () -> Unit = {}, beforeCommit: () -> Unit = {}, onCommitted: () -> Unit = {}, writer: suspend (java.io.OutputStream) -> String): LinuxFileEntry = operation {
        require(size >= 0)
        val normalized = mutationPath(path)
        val destination = resolvePath(normalized, followFinal = false)
        requireDirectory(destination.parent)
        val before = entry(normalized, destination)
        if (before != expected) fail(LinuxFileErrorCode.CHANGED)
        val original = if (before != null) regularAttributes(destination) else null
        val originalHash = original?.let { hash(destination, check) }
        val space = access.directory(destination.parent) { it.toFile().usableSpace }
        if (space < size) throw LinuxStreamingException("NO_SPACE")
        val temporary = access.temporaryFile(destination.parent, ".infinity-sftp-", ".tmp")
        try {
            val checksum = access.file(temporary) { Files.newByteChannel(it, setOf(java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS)) }.use { channel ->
                val output = java.nio.channels.Channels.newOutputStream(channel)
                val hash = writer(output)
                output.flush()
                if (channel is java.nio.channels.FileChannel) channel.force(true)
                hash
            }
            if (access.attributes(temporary).size() != size || hash(temporary, check) != checksum) throw LinuxStreamingException("CHECKSUM_MISMATCH")
            val current = if (access.exists(destination)) regularAttributes(destination) else null
            if (current?.fileKey() != original?.fileKey() || current?.lastModifiedTime() != original?.lastModifiedTime() ||
                current?.size() != original?.size() || original != null && hash(destination, check) != originalHash) fail(LinuxFileErrorCode.CHANGED)
            if (resolvePath(normalized, followFinal = false) != destination) fail(LinuxFileErrorCode.CHANGED)
            currentCoroutineContext().ensureActive()
            before?.let { access.chmod(temporary, it.mode, metadata) }
            check()
            beforeCommit()
            check()
            currentCoroutineContext().ensureActive()
            if (original == null) access.move(temporary, destination)
            else access.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            onCommitted()
            entry(normalized, destination) ?: fail(LinuxFileErrorCode.IO)
        } finally { access.delete(temporary) }
    }

    override suspend fun writeText(path: String, text: String, expectedModifiedMs: Long?): LinuxFileEntry = writeContent(path, text.toByteArray(Charsets.UTF_8), expectedModifiedMs)

    internal suspend fun writeContent(path: String, bytes: ByteArray, expectedModifiedMs: Long? = null, expectedSha256: String? = null, createOnly: Boolean = false): LinuxFileEntry = operation {
        val normalized = mutationPath(path)
        if (createOnly && access.exists(resolvePath(normalized, followFinal = false))) fail(LinuxFileErrorCode.NAME_EXISTS)
        val destination = resolvePath(normalized)
        requireDirectory(destination.parent)
        val original = if (access.exists(destination)) regularAttributes(destination) else null
        if (expectedModifiedMs != null && original?.lastModifiedTime()?.toMillis() != expectedModifiedMs) fail(LinuxFileErrorCode.CHANGED)
        if (expectedSha256 != null && (original == null || hash(destination) != expectedSha256)) fail(LinuxFileErrorCode.CHANGED)
        val mode = original?.let { access.identity(destination, metadata).mode }
        val temporary = access.temporaryFile(destination.parent, ".infinity-linux-write-", ".tmp")
        var isolated: Path? = null
        try {
            access.file(temporary) { Files.newByteChannel(it, setOf(java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS)) }.use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { currentCoroutineContext().ensureActive(); channel.write(buffer) }
                if (channel is java.nio.channels.FileChannel) channel.force(true)
            }
            mode?.let { access.chmod(temporary, it, metadata) }
            val current = if (access.exists(destination)) regularAttributes(destination) else null
            if (current?.fileKey() != original?.fileKey() || current?.lastModifiedTime() != original?.lastModifiedTime() || current?.size() != original?.size()) fail(LinuxFileErrorCode.CHANGED)
            if (expectedSha256 != null && hash(destination) != expectedSha256) fail(LinuxFileErrorCode.CHANGED)
            if (resolvePath(normalized) != destination) fail(LinuxFileErrorCode.CHANGED)
            currentCoroutineContext().ensureActive()
            if (original != null) {
                val quarantine = destination.resolveSibling(".infinity-linux-old-${java.util.UUID.randomUUID()}")
                access.move(destination, quarantine)
                isolated = quarantine
                val captured = regularAttributes(quarantine)
                if (captured.fileKey() != original.fileKey() || captured.size() != original.size() || captured.lastModifiedTime() != original.lastModifiedTime() ||
                    (expectedSha256 != null && hash(quarantine) != expectedSha256)) fail(LinuxFileErrorCode.CHANGED)
            }
            access.move(temporary, destination)
            isolated?.let { runCatching { access.delete(it) }; isolated = null }
            entry(normalized, resolvePath(normalized, followFinal = false)) ?: fail(LinuxFileErrorCode.IO)
        } catch (failure: Throwable) {
            isolated?.let { quarantine ->
                // 目标出现新对象时恢复采用独占发布；恢复副本留在原目录，不覆盖并发写入。
                runCatching { access.move(quarantine, destination) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally { access.delete(temporary) }
    }

    override suspend fun createFile(parent: String, name: String): LinuxFileEntry = create(parent, name, directory = false)
    override suspend fun createDirectory(parent: String, name: String): LinuxFileEntry = create(parent, name, directory = true)

    private suspend fun create(parent: String, name: String, directory: Boolean): LinuxFileEntry = operation {
        val path = join(normalize(parent), LinuxFilePaths.validateName(name))
        val target = resolvePath(path, followFinal = false)
        requireDirectory(target.parent)
        if (directory) access.createDirectory(target) else access.createFile(target)
        entry(path, target) ?: fail(LinuxFileErrorCode.IO)
    }

    override suspend fun rename(path: String, newName: String): LinuxFileEntry = operation {
        val normalized = mutationPath(path)
        val source = resolvePath(normalized, followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        val targetPath = join(normalized.substringBeforeLast('/').ifEmpty { "/" }, LinuxFilePaths.validateName(newName))
        val destination = resolvePath(targetPath, followFinal = false)
        if (access.exists(destination)) fail(LinuxFileErrorCode.NAME_EXISTS)
        if (!rejectGuestLinks) LinuxFileLinks(paths).preserveBacking(source)
        access.move(source, destination)
        entry(targetPath, destination) ?: fail(LinuxFileErrorCode.IO)
    }

    override suspend fun copy(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode> = batch(paths) { raw ->
        val normalized = mutationPath(raw)
        val source = resolvePath(normalized, followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        val directory = resolvePath(targetDirectory)
        requireDirectory(directory)
        rejectInside(source, directory)
        val name = source.fileName.toString()
        val extension = if (!access.isDirectory(source) && name.lastIndexOf('.') > 0) ".${name.substringAfterLast('.')}" else ""
        val stem = name.removeSuffix(extension)
        var destination = directory.resolve(name)
        this.paths.guest(destination)
        var suffix = 2
        while (access.exists(destination)) destination = directory.resolve("$stem (${suffix++})$extension")
        // 在旁路临时目录中构建完整副本，取消或失败时不留半个目录。
        val staging = access.temporaryDirectory(directory, ".infinity-linux-copy-")
        try {
            copyNode(source, staging.resolve("item"))
            currentCoroutineContext().ensureActive()
            access.move(staging.resolve("item"), destination)
        } finally { deleteNode(staging, cleanup = true) }
    }

    override suspend fun move(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode> = batch(paths) { raw ->
        val source = resolvePath(mutationPath(raw), followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        val directory = resolvePath(targetDirectory)
        requireDirectory(directory)
        rejectInside(source, directory)
        val destination = directory.resolve(source.fileName)
        this.paths.guest(destination)
        if (access.exists(destination)) fail(LinuxFileErrorCode.NAME_EXISTS)
        if (!rejectGuestLinks) LinuxFileLinks(this.paths).preserveBacking(source)
        access.move(source, destination)
    }

    override suspend fun delete(paths: List<String>): Map<String, LinuxFileErrorCode> = batch(paths) {
        val source = resolvePath(mutationPath(it), followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        if (!rejectGuestLinks) LinuxFileLinks(this.paths).preserveBacking(source)
        deleteNode(source)
    }

    internal suspend fun moveExact(sourcePath: String, targetPath: String) = operation {
        val source = resolvePath(mutationPath(sourcePath), followFinal = false)
        val target = resolvePath(mutationPath(targetPath), followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        requireDirectory(target.parent)
        rejectInside(source, target)
        if (access.exists(target)) fail(LinuxFileErrorCode.NAME_EXISTS)
        if (!rejectGuestLinks) LinuxFileLinks(paths).preserveBacking(source)
        access.move(source, target)
    }

    internal suspend fun copyExact(sourcePath: String, targetPath: String) = operation {
        val source = resolvePath(mutationPath(sourcePath), followFinal = false)
        val target = resolvePath(mutationPath(targetPath), followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        requireDirectory(target.parent)
        rejectInside(source, target)
        if (access.exists(target)) fail(LinuxFileErrorCode.NAME_EXISTS)
        val staging = access.temporaryDirectory(target.parent, ".infinity-linux-copy-")
        try { copyNode(source, staging.resolve("item")); access.move(staging.resolve("item"), target) }
        finally { deleteNode(staging, cleanup = true) }
    }

    internal suspend fun deleteForTransfer(path: String, identity: String?, sha256: String?, directory: Boolean) = operation {
        val source = resolvePath(mutationPath(path), followFinal = false)
        val attributes = access.attributes(source)
        if (attributes.fileKey()?.toString() != identity || attributes.isDirectory != directory) fail(LinuxFileErrorCode.CHANGED)
        if (directory) {
            if (access.children(source).isNotEmpty()) fail(LinuxFileErrorCode.CHANGED)
        } else if (hash(source) != sha256) fail(LinuxFileErrorCode.CHANGED)
        // delete 只解除当前条目；非空目录由文件系统拒绝，绝不递归。
        access.delete(source)
    }

    internal suspend fun deleteChecked(path: String, expectedSha256: String?) = operation {
        val source = resolvePath(mutationPath(path), followFinal = false)
        requireExists(source)
        requireAgentTree(source)
        if (expectedSha256 != null && hash(resolvePath(path)) != expectedSha256) fail(LinuxFileErrorCode.CHANGED)
        if (!rejectGuestLinks) LinuxFileLinks(paths).preserveBacking(source)
        deleteNode(source)
    }

    override suspend fun compress(paths: List<String>, archiveName: String): LinuxFileEntry = operation {
        if (paths.isEmpty()) fail(LinuxFileErrorCode.NAME_INVALID)
        val sources = paths.map { resolvePath(mutationPath(it), followFinal = false).also(::requireExists) }
        val name = LinuxFilePaths.validateName(if (archiveName.endsWith(".tar.gz")) archiveName else "$archiveName.tar.gz")
        val destination = sources.first().parent.resolve(name)
        if (access.exists(destination)) fail(LinuxFileErrorCode.NAME_EXISTS)
        sources.forEach { rejectInside(it, destination) }
        val temporary = access.temporaryFile(destination.parent, ".infinity-linux-tar-", ".tmp")
        try {
            GZIPOutputStream(access.file(temporary) { Files.newOutputStream(it, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS) }).use { output ->
                val tar = LinuxFileTar(output, metadata, this.paths)
                val names = mutableSetOf<String>()
                sources.forEach { source ->
                    val entryName = source.fileName.toString()
                    if (!names.add(entryName)) fail(LinuxFileErrorCode.NAME_EXISTS)
                    tar.add(source, entryName)
                }
                tar.finish()
            }
            currentCoroutineContext().ensureActive()
            access.move(temporary, destination)
            val guest = this.paths.guest(destination)
            entry(guest, destination) ?: fail(LinuxFileErrorCode.IO)
        } finally { access.delete(temporary) }
    }

    override suspend fun chmod(path: String, mode: Int): LinuxFileEntry = operation {
        if (mode !in 0..0xfff) fail(LinuxFileErrorCode.IO)
        val target = resolvePath(path)
        requireExists(target)
        access.chmod(target, mode, metadata)
        entry(normalize(path), resolvePath(path, followFinal = false)) ?: fail(LinuxFileErrorCode.IO)
    }

    override fun hostFile(path: String): File? = try {
        if (!isReady()) null else resolvePath(path).takeIf { access.exists(it) }?.toFile()
    } catch (_: Exception) { null }

    private fun entry(guestPath: String, source: Path): LinuxFileEntry? {
        if (!access.exists(source)) return null
        if (rejectGuestLinks && access.isLink(source)) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val emulated = paths.isEmulatedHardLink(source)
        val actual = if (emulated) resolvePath(paths.guest(source)) else source
        val attrs = access.attributes(actual)
        val identity = access.identity(actual, metadata)
        val target = if (attrs.isSymbolicLink) access.readLink(source).toString() else null
        val directoryLink = target != null && try { access.isDirectory(resolvePath(paths.guest(source))) } catch (_: LinuxFileException) { false }
        val type = when {
            attrs.isSymbolicLink -> LinuxFileType.SYMLINK
            attrs.isDirectory -> LinuxFileType.DIRECTORY
            attrs.isRegularFile -> LinuxFileType.FILE
            else -> LinuxFileType.OTHER
        }
        return LinuxFileEntry(guestPath, guestPath.substringAfterLast('/').ifEmpty { "/" }, type, attrs.size(), attrs.lastModifiedTime().toMillis(),
            identity.mode, if (identity.uid == metadata.appUid) "root" else identity.uid.toString(),
            if (identity.gid == metadata.appGid) "root" else identity.gid.toString(), target, directoryLink)
    }

    private suspend fun copyNode(source: Path, destination: Path) {
        currentCoroutineContext().ensureActive()
        if (rejectGuestLinks && access.isLink(source)) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val actual = if (paths.isEmulatedHardLink(source)) resolvePath(paths.guest(source)) else source
        when {
            access.isLink(actual) -> access.createLink(destination, access.readLink(actual))
            access.isDirectory(actual) -> {
                access.createDirectory(destination)
                run {
                    access.children(actual).filterNot { LinuxFilePaths.isLink2SymlinkName(it.fileName.toString()) }.forEach { copyNode(it, destination.resolve(it.fileName)) }
                }
                access.chmod(destination, access.identity(actual, metadata).mode, metadata)
                access.modified(destination, access.attributes(actual).lastModifiedTime())
            }
            access.isFile(actual) -> {
                val before = access.attributes(actual)
                val mode = access.identity(actual, metadata).mode
                access.openRead(actual).use { opened ->
                    val input = opened.channel
                    val revision = opened.revision()
                    access.file(destination) { Files.newOutputStream(it, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS) }.use { output ->
                        val buffer = ByteBuffer.allocate(64 * 1024)
                        while (input.read(buffer) >= 0) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer.array(), 0, buffer.position())
                            buffer.clear()
                        }
                        if (opened.revision() != revision) fail(LinuxFileErrorCode.CHANGED)
                    }
                }
                val after = access.attributes(actual)
                if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) fail(LinuxFileErrorCode.CHANGED)
                access.chmod(destination, mode, metadata)
                access.modified(destination, before.lastModifiedTime())
            }
            else -> fail(LinuxFileErrorCode.IO)
        }
    }

    private fun deleteNode(source: Path, cleanup: Boolean = false) {
        if (rejectGuestLinks && access.isLink(source)) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        if (access.isDirectory(source)) {
            if (cleanup) access.chmod(source, access.identity(source, metadata).mode or 0x1c0, metadata)
            access.children(source).forEach { deleteNode(it, cleanup) }
        }
        access.delete(source)
    }

    /** 严格文件工具不接管 proot 隐藏实体，也不为批准路径以外的别名执行修复。 */
    private fun requireAgentTree(source: Path) {
        if (!rejectGuestLinks) return
        if (LinuxFilePaths.isLink2SymlinkName(source.fileName.toString())) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val attributes = access.attributes(source)
        when {
            attributes.isSymbolicLink -> fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
            attributes.isDirectory -> access.children(source).forEach(::requireAgentTree)
            !attributes.isRegularFile -> fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        }
    }

    private fun read(source: Path, offset: Long, length: Int): ByteArray = access.openRead(source).use { opened ->
        val channel = opened.channel
        val revision = opened.revision()
        channel.position(offset)
        val available = (channel.size() - offset).coerceAtLeast(0).coerceAtMost(length.toLong()).toInt()
        val buffer = ByteBuffer.allocate(available)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        if (opened.revision() != revision) fail(LinuxFileErrorCode.CHANGED)
        buffer.array().copyOf(buffer.position())
    }

    private fun rejectInside(source: Path, target: Path) {
        if (access.isDirectory(source) && target.startsWith(source)) fail(LinuxFileErrorCode.TARGET_INSIDE_SOURCE)
    }
    private fun requireExists(path: Path) { if (!access.exists(path)) fail(LinuxFileErrorCode.NOT_FOUND) }
    private fun requireDirectory(path: Path) { requireExists(path); if (!access.isDirectory(path)) fail(LinuxFileErrorCode.IO) }
    private fun regularAttributes(path: Path): BasicFileAttributes = access.attributes(path).also {
        if (!it.isRegularFile) fail(LinuxFileErrorCode.IO)
    }
    private fun normalize(raw: String): String = LinuxFilePaths.normalize(raw, workspaceNames)

    private fun mutationPath(raw: String): String = normalize(raw).also {
        if (it == "/") fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val root = trustedMutationRoot?.takeIf { root -> it == root || it.startsWith("$root/") }
        val relative = if (root == null) it else it.removePrefix(root)
        if (relative.split('/').any(LinuxFilePaths::isLink2SymlinkName)) fail(LinuxFileErrorCode.NAME_INVALID)
    }
    private fun join(parent: String, name: String) = parent.trimEnd('/') + "/" + name

    private fun hash(path: Path, check: () -> Unit = {}): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        access.openRead(path).use { opened ->
            val revision = opened.revision()
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (opened.channel.read(buffer) >= 0) { check(); digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            if (revision != opened.revision()) fail(LinuxFileErrorCode.CHANGED)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun resolvePath(raw: String, followFinal: Boolean = true): Path {
        val normalized = normalize(raw)
        if (directoryRoot != null && normalized != directoryRoot && !normalized.startsWith("$directoryRoot/")) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val resolved = paths.resolve(normalized, followFinal)
        if (directoryRoot != null && !resolved.startsWith(paths.root.resolve(directoryRoot.removePrefix("/")))) {
            val link = paths.resolve(normalized, followFinal = false)
            if (!followFinal || !link.startsWith(paths.root.resolve(directoryRoot.removePrefix("/"))) || !paths.isEmulatedHardLink(link)) fail(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        }
        return resolved
    }

    private suspend fun batch(paths: List<String>, action: suspend (String) -> Unit): Map<String, LinuxFileErrorCode> = operation {
        val failed = linkedMapOf<String, LinuxFileErrorCode>()
        paths.forEach {
            try { action(it) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed[it] = mapped(failure).code }
        }
        failed
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(io) {
        mutex.withLock {
            if (!isReady() || !access.isDirectory(paths.root)) fail(LinuxFileErrorCode.NOT_READY)
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw mapped(failure) }
        }
    }

    private fun mapped(failure: Exception): LinuxFileException = when (failure) {
        is LinuxFileException -> failure
        is com.promenar.infinity.domain.storage.ExternalFolderException -> LinuxStreamingException(failure.code).apply { initCause(failure) }
        is FileAlreadyExistsException -> LinuxFileException(LinuxFileErrorCode.NAME_EXISTS, failure)
        is NoSuchFileException -> LinuxFileException(LinuxFileErrorCode.NOT_FOUND, failure)
        is android.system.ErrnoException -> LinuxFileException(when (failure.errno) {
            android.system.OsConstants.ENOENT -> LinuxFileErrorCode.NOT_FOUND
            android.system.OsConstants.EEXIST -> LinuxFileErrorCode.NAME_EXISTS
            android.system.OsConstants.ELOOP -> LinuxFileErrorCode.OUTSIDE_ROOTFS
            else -> LinuxFileErrorCode.IO
        }, failure)
        else -> LinuxFileException(LinuxFileErrorCode.IO, failure)
    }

    companion object {
        private const val MAX_READ_BYTES = 64 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun fail(code: LinuxFileErrorCode): Nothing = throw LinuxFileException(code)
    }
}
