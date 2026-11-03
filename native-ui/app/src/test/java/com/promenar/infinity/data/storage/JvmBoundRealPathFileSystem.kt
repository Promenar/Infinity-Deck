package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * JVM 受控句柄替身：使用真实临时文件，并由测试显式记录被移动目录的绑定。
 * 此替身验证后端的句柄使用契约，不替代 Android 内核 fd 与 procfs 的设备验证。
 */
internal class JvmBoundRealPathFileSystem : RealPathFileSystem {
    val movedDirectories: MutableMap<Path, Path> = object : LinkedHashMap<Path, Path>() {
        override fun put(key: Path, value: Path): Path? = super.put(mappingPath(key), mappingPath(value))
        override fun putAll(from: Map<out Path, Path>) {
            from.forEach { (key, value) -> put(key, value) }
        }
    }
    val reads = mutableListOf<Path>()
    var beforeOperation: ((String, Path) -> Unit)? = null
    var afterOperation: ((String, Path) -> Unit)? = null
    var supportHardLinks = false
    var supportAtomicMoves = true
    var readStateAfterRead: ((Path, RealPathFileState) -> RealPathFileState)? = null
    var afterRead: ((Path) -> Unit)? = null
    var renameAcrossVolumes: ((Path, Path) -> Boolean)? = null
    var pathRenameAcrossVolumes = true
    var pathRenameCalls = 0
    var pathParentMismatch = false
    var availableSpace = Long.MAX_VALUE
    var emulateFuse = false
    var unlinkedStateErrno: Int? = null
    private val unlinkedObjects = mutableSetOf<String>()

    fun backend(root: File, forbiddenRoots: Set<Path> = emptySet(), permissionCheck: () -> Boolean = { true }): RealPathExternalFolderBackend =
        RealPathExternalFolderBackend(root, forbiddenRoots, permissionCheck, this)

    override val supportsPathRename: Boolean get() = true
    override fun statAbsolute(path: Path): RealPathFileState? {
        beforeOperation?.invoke("path-stat", path)
        return if (Files.exists(path, NOFOLLOW_LINKS)) attributes(path).let {
            if (pathParentMismatch && it.isDirectory) it.copy(objectId = "replaced-parent") else it
        } else null
    }
    override fun moveAbsolute(source: Path, target: Path) {
        pathRenameCalls++
        beforeOperation?.invoke("path-move", source)
        if (pathRenameAcrossVolumes) throw android.system.ErrnoException("rename", android.system.OsConstants.EXDEV)
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        afterOperation?.invoke("path-move", source)
    }
    override fun openFileSystemRoot(): BoundRealPathDirectory = Directory(Path.of("/"))

    private fun mappingPath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        // 登记时统一父目录别名（如 macOS /var）；末段可能已换成链接，必须保留其原名称。
        // 已打开句柄的寻址不能在换链后重新 realpath，否则会跟随替换节点。
        return absolute.parent?.toFile()?.canonicalFile?.toPath()?.resolve(absolute.fileName) ?: absolute
    }

    private fun bound(path: Path): Path {
        val mapping = movedDirectories.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.nameCount }
        return if (mapping == null) path else mapping.value.resolve(mapping.key.relativize(path))
    }

    private inner class Directory(private val openedPath: Path) : BoundRealPathDirectory {
        private val openedState = attributes(bound(openedPath))
        override val readTimestampsMayDrift: Boolean get() = emulateFuse
        private fun child(name: String): Path = bound(openedPath).resolve(name)
        override fun state(): RealPathFileState = heldState(bound(openedPath), openedState)
        override fun availableBytes(): Long = minOf(availableSpace, Files.getFileStore(bound(openedPath)).usableSpace)
        override fun stat(name: String): RealPathFileState? {
            beforeOperation?.invoke("stat", openedPath.resolve(name))
            return child(name).let { if (Files.exists(it, NOFOLLOW_LINKS)) attributes(it) else null }
        }
        override fun names(): List<String> {
            beforeOperation?.invoke("list", openedPath)
            return Files.newDirectoryStream(bound(openedPath)).use { it.map { path -> path.fileName.toString() } }
        }
        override fun openDirectory(name: String): BoundRealPathDirectory {
            beforeOperation?.invoke("directory", openedPath.resolve(name))
            val state = stat(name) ?: throw ExternalFolderException("NOT_FOUND", "测试目录不存在")
            if (state.isSymbolicLink) throw ExternalFolderException("INVALID_PATH", "测试替身拒绝符号链接")
            if (!state.isDirectory) throw ExternalFolderException("NOT_DIRECTORY", "测试目标不是目录")
            return Directory(child(name))
        }
        override fun openRead(name: String): BoundRealPathRead {
            beforeOperation?.invoke("read", openedPath.resolve(name))
            reads.add(openedPath.resolve(name))
            val channel = FileChannel.open(child(name), READ, NOFOLLOW_LINKS)
            val openedState = attributes(child(name))
            var completed = false
            val stream = object : FilterInputStream(Channels.newInputStream(channel)) {
                private fun finish(count: Int): Int {
                    if (count < 0 && !completed) {
                        completed = true
                        afterRead?.invoke(child(name))
                    }
                    return count
                }
                override fun read(): Int = finish(super.read())
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = finish(`in`.read(bytes, offset, length))
            }
            return object : BoundRealPathRead {
                override val input: InputStream = stream
                override fun state(): RealPathFileState = heldState(child(name), openedState).let {
                    if (completed) readStateAfterRead?.invoke(child(name), it) ?: it else it
                }
                override fun close() = stream.close()
            }
        }
        override fun createFile(name: String): BoundRealPathWrite {
            beforeOperation?.invoke("create", openedPath.resolve(name))
            val channel = try { FileChannel.open(child(name), WRITE, CREATE_NEW, NOFOLLOW_LINKS) } catch (_: java.nio.file.FileAlreadyExistsException) {
                throw ExternalFolderException("CONFLICT", "测试目标已存在")
            }
            val stream = Channels.newOutputStream(channel)
            return object : BoundRealPathWrite {
                override val output: OutputStream = stream
                override fun state(): RealPathFileState = attributes(child(name))
                override fun sync() = channel.force(true)
                override fun close() = stream.close()
            }
        }
        override fun createDirectory(name: String) { Files.createDirectory(child(name)) }
        override fun atomicReplace(source: String, target: String) {
            val operation = if (target.startsWith(".infinity-delete-")) "delete" else "replace"
            beforeOperation?.invoke(operation, openedPath.resolve(if (source.startsWith(".infinity")) target else source))
            Files.move(child(source), child(target), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) {
            if (!supportAtomicMoves) throw java.nio.file.AtomicMoveNotSupportedException(source, target, "测试卷不支持原子不覆盖移动")
            val operation = when {
                target.startsWith(".infinity-delete-") -> "delete"
                target.startsWith(".infinity-replace-") -> "replace"
                else -> "move"
            }
            beforeOperation?.invoke(operation, openedPath.resolve(source))
            val destination = targetDirectory as Directory
            Files.move(child(source), destination.child(target))
        }
        override fun linkNew(source: String, target: String): Boolean {
            if (!supportHardLinks) return false
            Files.createLink(child(target), child(source))
            return true
        }
        override fun moveReplacing(source: String, targetDirectory: BoundRealPathDirectory, target: String) {
            beforeOperation?.invoke("checked-move", openedPath.resolve(source))
            val destination = targetDirectory as Directory
            if (renameAcrossVolumes?.invoke(child(source), destination.child(target)) == true) {
                throw android.system.ErrnoException("renameat", android.system.OsConstants.EXDEV)
            }
            Files.move(child(source), destination.child(target), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        override fun deleteFile(name: String) {
            beforeOperation?.invoke("delete", openedPath.resolve(name))
            val state = attributes(child(name))
            Files.delete(child(name))
            unlinkedObjects += state.objectId
            afterOperation?.invoke("delete", openedPath.resolve(name))
        }
        override fun deleteDirectory(name: String) {
            beforeOperation?.invoke("delete-directory", openedPath.resolve(name))
            val state = attributes(child(name))
            Files.delete(child(name))
            unlinkedObjects += state.objectId
            afterOperation?.invoke("delete-directory", openedPath.resolve(name))
        }
        override fun close() = Unit
    }

    private fun heldState(path: Path, opened: RealPathFileState): RealPathFileState {
        if (opened.objectId in unlinkedObjects) {
            unlinkedStateErrno?.let { throw android.system.ErrnoException("fstat", it) }
            return opened.copy(linkCount = 0)
        }
        return if (Files.exists(path, NOFOLLOW_LINKS)) attributes(path) else opened
    }

    private fun attributes(path: Path): RealPathFileState {
        val value = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val objectId = value.fileKey()?.toString() ?: throw ExternalFolderException("UNSUPPORTED", "测试文件系统缺少对象标识")
        val changed = (Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS) as FileTime).to(TimeUnit.NANOSECONDS)
        return RealPathFileState(value.isDirectory, value.isRegularFile, value.isSymbolicLink, value.size(), value.lastModifiedTime().toMillis(),
            objectId, "$objectId:${value.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:$changed:${value.size()}",
            modifiedTime = value.lastModifiedTime().to(TimeUnit.NANOSECONDS).toString(), changedTime = changed.toString(),
            linkCount = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong())
    }
}
