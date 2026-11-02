package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.FileAlreadyExistsException
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 仅在 rename 明确未提交时使用；创建回执与补偿均固定在原有目录句柄内。 */
internal object RealPathCopyMove {
    private enum class Deletion { NONE, CONFIRMED, FD_UNAVAILABLE, SOURCE_REPLACED }
    private class Node(val name: String, var source: RealPathFileState) {
        var sha256: String? = null
        var writtenSize = 0L
        var writtenSha256 = externalSha256(byteArrayOf())
        val children = mutableListOf<Node>()
        var target: RealPathFileState? = null
        var creationAttempted = false
        var deletion = Deletion.NONE
        val deleted get() = deletion == Deletion.CONFIRMED
    }
    private class Size { var bytes = 0L }

    suspend fun move(source: BoundRealPathDirectory, sourceName: String, sourcePath: String, expected: RealPathFileState,
        target: BoundRealPathDirectory, targetName: String, targetPath: String) {
        checkRealPathReadState(expected, source.stat(sourceName), timestamps = !source.readTimestampsMayDrift)
        val size = Size()
        val node = capture(source, sourceName, size, 0)
        checkRealPathReadState(expected, node.source, timestamps = !source.readTimestampsMayDrift)
        if (target.availableBytes() < size.bytes) {
            throw ExternalFolderException("NO_SPACE", "目标存储卷可用空间不足 / Insufficient free space on destination volume")
        }
        var deletingSource = false
        try {
            create(source, sourceName, target, targetName, node)
            verify(source, sourceName, node, target = false)
            verify(target, targetName, node, target = true)
            currentCoroutineContext().ensureActive()
            deletingSource = true
            removeSource(source, sourceName, node, target, targetName)
            verify(target, targetName, node, target = true)
            if (source.stat(sourceName) != null) realPathReadConflict("identity=recreated-after-delete")
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    if ((node.deleted || node.deletion == Deletion.FD_UNAVAILABLE) &&
                        source.stat(sourceName) == null && failure !is CancellationException) {
                        try {
                            verify(target, targetName, node, target = true)
                            if (source.stat(sourceName) != null) realPathReadConflict("identity=recreated-after-delete")
                            return@withContext
                        } catch (changed: Throwable) { failure.addSuppressed(changed) }
                    }
                    if (deletingSource && (hasDeleted(node) || source.stat(sourceName) == null)) {
                        verify(target, targetName, node, target = true)
                        restoreMissing(source, sourceName, node, target, targetName)
                    }
                    rollback(target, targetName, node)
                } catch (cleanup: Throwable) {
                    val recovery = ExternalFolderException("RECOVERY_REQUIRED",
                        "移动补偿未完成，请核查来源 $sourcePath 与目标 $targetPath / Move recovery required: source=$sourcePath, target=$targetPath")
                    recovery.addSuppressed(failure)
                    recovery.addSuppressed(cleanup)
                    if (failure is CancellationException) { failure.addSuppressed(recovery); throw failure }
                    throw recovery
                }
                throw failure
            }
        }
    }

    /** 只记录身份、大小和树结构，文件内容始终通过固定大小缓冲区处理。 */
    private suspend fun capture(parent: BoundRealPathDirectory, name: String, size: Size, depth: Int): Node {
        currentCoroutineContext().ensureActive()
        if (depth > 32) throw ExternalFolderException("TOO_LARGE", "目录超过深度上限 / Tree depth limit exceeded")
        val state = parent.stat(name) ?: realPathReadConflict("identity=missing")
        requireSupported(state)
        val node = Node(name, state)
        if (state.isDirectory) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(state, directory.state())
                for (child in directory.names().sorted()) {
                    com.promenar.infinity.domain.storage.ExternalFolderPaths.validateName(child)
                    node.children += capture(directory, child, size, depth + 1)
                }
                checkIdentity(state, parent.stat(name))
            }
        } else {
            if (state.size < 0 || state.size > Long.MAX_VALUE - size.bytes) {
                throw ExternalFolderException("NO_SPACE", "移动所需空间超出存储容量 / Move exceeds storage capacity")
            }
            size.bytes += state.size
        }
        return node
    }

    private suspend fun create(source: BoundRealPathDirectory, sourceName: String,
        parent: BoundRealPathDirectory, name: String, node: Node) {
        currentCoroutineContext().ensureActive()
        checkRealPathReadState(node.source, source.stat(sourceName), timestamps = !source.readTimestampsMayDrift)
        node.creationAttempted = true
        try {
            if (node.source.isDirectory) {
                parent.createDirectory(name)
                node.target = parent.stat(name) ?: realPathReadConflict("identity=missing")
                parent.openDirectory(name).use { directory ->
                    checkIdentity(node.target!!, directory.state())
                    source.openDirectory(sourceName).use { from ->
                        checkIdentity(node.source, from.state())
                        if (from.names().sorted() != node.children.map { it.name }.sorted()) realPathReadConflict("identity(children)")
                        for (child in node.children) create(from, child.name, directory, child.name, child)
                    }
                }
            } else {
                parent.createFile(name).use { file ->
                    node.target = file.state()
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        val read = readVerifiedRealPath(source, sourceName, node.source.size) { input ->
                            copy(input) { bytes, count ->
                                file.output.write(bytes, 0, count)
                                digest.update(bytes, 0, count)
                                node.writtenSize += count
                            }
                        }
                        checkRealPathReadState(node.source, read.state, timestamps = !source.readTimestampsMayDrift)
                        node.source = read.state
                        node.sha256 = read.sha256
                        file.sync()
                    } finally {
                        node.writtenSha256 = digest.digest().hex()
                        node.target = file.state()
                    }
                }
            }
        } catch (failure: Throwable) {
            // 明确的独占创建重名错误证明没有创建目标，不能接管并发占用者。
            if (node.target == null && (failure is FileAlreadyExistsException ||
                failure is ErrnoException && failure.errno == OsConstants.EEXIST)) node.creationAttempted = false
            throw failure
        }
        verify(parent, name, node, target = true)
    }

    private suspend fun copy(input: InputStream, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            write(buffer, count)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private suspend fun verify(parent: BoundRealPathDirectory, name: String, node: Node, target: Boolean) {
        val expected = if (target) node.target ?: realPathReadConflict("identity=missing") else node.source
        val current = parent.stat(name)
        checkIdentity(expected, current)
        if (node.source.isDirectory) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(expected, directory.state())
                if (directory.names().sorted() != node.children.map { it.name }.sorted()) realPathReadConflict("identity(children)")
                for (child in node.children) verify(directory, child.name, child, target)
            }
        } else {
            val read = readVerifiedRealPath(parent, name, node.source.size) { }
            checkIdentity(expected, read.state)
            val hash = node.sha256 ?: realPathReadConflict("sha256=missing")
            if (hash != read.sha256) realPathReadConflict("sha256=$hash -> ${read.sha256}")
            if (!target) node.source = read.state else node.target = read.state
        }
    }

    private suspend fun removeSource(parent: BoundRealPathDirectory, name: String, node: Node,
        target: BoundRealPathDirectory, targetName: String) {
        currentCoroutineContext().ensureActive()
        checkRealPathReadState(node.source, parent.stat(name), timestamps = !parent.readTimestampsMayDrift && !node.source.isDirectory)
        if (node.source.isDirectory) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(node.source, directory.state())
                target.openDirectory(targetName).use { targetDirectory ->
                    checkIdentity(node.target!!, targetDirectory.state())
                    for (child in node.children) removeSource(directory, child.name, child, targetDirectory, child.name)
                }
                if (directory.names().isNotEmpty()) realPathReadConflict("identity(children)")
                verify(target, targetName, node, target = true)
                checkIdentity(node.source, parent.stat(name))
                val links = directory.state().linkCount
                try { parent.deleteDirectory(name); node.deletion = Deletion.CONFIRMED }
                catch (failure: Throwable) {
                    try { recordFailedDelete(parent, name, node, links, directory::state) }
                    catch (observation: Throwable) { failure.addSuppressed(observation) }
                    throw failure
                }
            }
        } else parent.openRead(name).use { held ->
            val before = held.state()
            checkIdentity(node.source, before)
            verifyPair(parent, name, target, targetName, node)
            checkRealPathReadState(node.source, parent.stat(name))
            checkRealPathReadState(node.target!!, target.stat(targetName))
            // unlink 前固定目标对象并再次核对路径；补偿期间原源 FD 仍保持打开。
            target.openRead(targetName).use { recoveryCopy ->
                checkRealPathReadState(node.target!!, recoveryCopy.state(), timestamps = !target.readTimestampsMayDrift)
                checkRealPathReadState(node.source, parent.stat(name))
                checkRealPathReadState(node.target!!, target.stat(targetName))
                val links = held.state().linkCount
                try {
                    try { parent.deleteFile(name); node.deletion = Deletion.CONFIRMED }
                    catch (failure: Throwable) {
                        try { recordFailedDelete(parent, name, node, links, held::state) }
                        catch (observation: Throwable) { failure.addSuppressed(observation) }
                        throw failure
                    }
                    verify(target, targetName, node, target = true)
                } catch (failure: Throwable) {
                    if (node.deleted && parent.stat(name) == null) {
                        try {
                            withContext(NonCancellable) {
                                if (runCatching { verify(target, targetName, node, target = true) }.isFailure) {
                                    // 路径无法证明目标可信时，先用原源 FD 保全原件，避免目标先改写再删除污染恢复内容。
                                    checkRealPathReadState(node.source, held.state(), timestamps = false)
                                    restoreFile(parent, name, node, held.input)
                                }
                            }
                        } catch (recovery: Throwable) {
                            recovery.addSuppressed(failure)
                            throw recovery
                        }
                    }
                    throw failure
                }
            }
        }
    }

    private suspend fun verifyPair(source: BoundRealPathDirectory, sourceName: String,
        target: BoundRealPathDirectory, targetName: String, node: Node) {
        repeat(3) {
            verify(source, sourceName, node, target = false)
            verify(target, targetName, node, target = true)
            val sourceNow = source.stat(sourceName)
            val targetNow = target.stat(targetName)
            checkRealPathReadState(node.source, sourceNow, timestamps = false)
            checkRealPathReadState(node.target!!, targetNow, timestamps = false)
            if (sourceNow!!.revision == node.source.revision && targetNow!!.revision == node.target!!.revision) return
        }
        realPathReadConflict("mtime/ctime=unstable")
    }

    private fun recordFailedDelete(parent: BoundRealPathDirectory, name: String, node: Node,
        links: Long, heldState: () -> RealPathFileState) {
        val current = parent.stat(name)
        if (current != null) {
            // 删除后出现不同身份时必须保留目标副本，不能按“删除未发生”回滚它。
            node.deletion = Deletion.SOURCE_REPLACED
            checkIdentity(node.source, current)
            node.deletion = Deletion.NONE
            return
        }
        try {
            if (heldState().linkCount < links) node.deletion = Deletion.CONFIRMED
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) throw failure
            // 共享卷可能拒绝对已解除链接的 FD 做 fstat；仅记录不可读，不能据此恢复或删除数据。
            node.deletion = Deletion.FD_UNAVAILABLE
        }
    }

    private fun hasDeleted(node: Node): Boolean = node.deletion != Deletion.NONE || node.children.any(::hasDeleted)

    private suspend fun restoreMissing(parent: BoundRealPathDirectory, name: String, node: Node,
        target: BoundRealPathDirectory, targetName: String) {
        if (parent.stat(name) == null) {
            if (!node.deleted) realPathReadConflict("identity=missing-before-delete")
            if (node.source.isDirectory) {
                parent.createDirectory(name)
                node.source = parent.stat(name) ?: realPathReadConflict("identity=missing")
            } else {
                val read = readVerifiedRealPath(target, targetName, node.source.size) { input ->
                    restoreFile(parent, name, node, input)
                }
                if (read.sha256 != node.sha256) realPathReadConflict("sha256(restore)")
            }
        } else if (node.deletion != Deletion.NONE) {
            realPathReadConflict("identity=recreated-after-delete")
        }
        checkIdentity(node.source, parent.stat(name))
        if (node.source.isDirectory) parent.openDirectory(name).use { directory ->
            checkIdentity(node.source, directory.state())
            target.openDirectory(targetName).use { copy ->
                checkIdentity(node.target!!, copy.state())
                for (child in node.children) restoreMissing(directory, child.name, child, copy, child.name)
            }
        }
        verify(parent, name, node, target = false)
        node.deletion = Deletion.NONE
    }

    private suspend fun restoreFile(parent: BoundRealPathDirectory, name: String, node: Node, input: InputStream) {
        val expectedHash = node.sha256 ?: realPathReadConflict("sha256=missing")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        parent.createFile(name).use { file ->
            node.source = file.state()
            try {
                copy(input) { bytes, length ->
                    file.output.write(bytes, 0, length)
                    digest.update(bytes, 0, length)
                    count += length
                }
                file.sync()
            } finally { node.source = file.state() }
        }
        if (count != node.writtenSize || digest.digest().hex() != expectedHash) realPathReadConflict("sha256(restore)")
        verify(parent, name, node, target = false)
        node.deletion = Deletion.NONE
    }

    private suspend fun rollback(parent: BoundRealPathDirectory, name: String, node: Node) {
        if (!node.creationAttempted) return
        val current = parent.stat(name) ?: return
        val expected = node.target ?: realPathReadConflict("identity=unknown")
        checkIdentity(expected, current)
        if (node.source.isDirectory) {
            parent.openDirectory(name).use { directory ->
                checkIdentity(expected, directory.state())
                for (child in node.children.asReversed()) rollback(directory, child.name, child)
                if (directory.names().isNotEmpty()) realPathReadConflict("identity(children)")
            }
            checkIdentity(expected, parent.stat(name))
            parent.deleteDirectory(name)
        } else {
            if (current.size != expected.size || current.size != node.writtenSize) {
                realPathReadConflict("size=${node.writtenSize} -> ${current.size}")
            }
            val read = readVerifiedRealPath(parent, name, node.writtenSize) { }
            checkIdentity(expected, read.state)
            if (read.state.size != expected.size) realPathReadConflict("size=${expected.size} -> ${read.state.size}")
            // 仅清理与已记录写入长度及摘要一致的自有副本，未知的部分写入交给人工恢复。
            if (read.sha256 != node.writtenSha256) realPathReadConflict("sha256=${node.writtenSha256} -> ${read.sha256}")
            checkRealPathReadState(read.state, parent.stat(name))
            parent.deleteFile(name)
        }
    }

    private fun checkIdentity(expected: RealPathFileState, actual: RealPathFileState?) {
        if (actual == null || actual.objectId != expected.objectId || actual.isDirectory != expected.isDirectory ||
            actual.isSymbolicLink || actual.isRegularFile != expected.isRegularFile) realPathReadConflict("identity=${expected.objectId} -> ${actual?.objectId ?: "missing"}")
    }
    private fun requireSupported(state: RealPathFileState) {
        if (state.isSymbolicLink || !state.isDirectory && !state.isRegularFile) {
            throw ExternalFolderException("INVALID_PATH", "不支持链接或特殊文件 / Links and special files are not supported")
        }
    }
}
