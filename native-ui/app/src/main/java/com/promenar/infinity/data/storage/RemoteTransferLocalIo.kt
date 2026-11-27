package com.promenar.infinity.data.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.promenar.infinity.data.linux.LinuxFileIo
import com.promenar.infinity.data.repository.AndroidWorkspaceFileMoves
import com.promenar.infinity.data.ssh.SshFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

/** Android 固定父目录与文件句柄，传输流打开后不会被同名符号链接重新定向。 */
internal class RemoteTransferLocalIo(private val secure: Boolean) {
    private val io = LinuxFileIo(secure)

    fun exists(file: File): Boolean = io.exists(file.toPath())
    fun attributes(file: File): BasicFileAttributes = io.attributes(file.toPath()).also {
        if (!it.isRegularFile) throw SshFailure("UNSUPPORTED_FILE_TYPE")
    }
    suspend fun sha256(file: File): String {
        val input = if (secure) ParcelFileDescriptor.AutoCloseInputStream(open(file, writing = false))
            else Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)
        return input.use { externalStreamHash(it) }
    }
    fun create(file: File) = io.createFile(file.toPath())
    fun delete(file: File) = io.delete(file.toPath())

    suspend fun <T> withFile(
        file: File,
        writing: Boolean,
        expected: BasicFileAttributes,
        action: suspend (File) -> T,
    ): T {
        if (!secure) return action(file)
        return open(file, writing).use {
            val pinned = File("/proc/self/fd/${it.fd}")
            val actual = Files.readAttributes(pinned.toPath(), BasicFileAttributes::class.java)
            if (!sameRevision(expected, actual)) throw SshFailure(if (writing) "CHECKPOINT_INVALID" else "SOURCE_CHANGED")
            action(pinned)
        }
    }

    private fun open(file: File, writing: Boolean): ParcelFileDescriptor = io.file(file.toPath()) { path ->
            val mode = if (writing) OsConstants.O_RDWR else OsConstants.O_RDONLY
            val fd = Os.open(path.toString(), mode or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw SshFailure("UNSUPPORTED_FILE_TYPE")
                ParcelFileDescriptor.dup(fd)
            } finally {
                Os.close(fd)
            }
        }

    fun publishNew(part: File, target: File, verified: BasicFileAttributes) = io.directory(target.toPath().parent) { dir ->
        val source = dir.resolve(part.name)
        val state = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!sameRevision(verified, state)) throw SshFailure("CHECKPOINT_INVALID")
        if (secure) {
            // Android SELinux 或共享卷可拒绝硬链接，使用项目已有的原子不覆盖移动。
            val fd = Os.open(dir.toString(), OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                ParcelFileDescriptor.dup(fd).use { AndroidWorkspaceFileMoves.moveNoReplace(it.fd, part.name, it.fd, target.name) }
            } finally {
                Os.close(fd)
            }
        } else {
            Files.createLink(dir.resolve(target.name), source)
        }
        Unit
    }

    fun publishReplacing(part: File, target: File, verified: BasicFileAttributes, expected: BasicFileAttributes) =
        io.directory(target.toPath().parent) { path ->
            val boundPart = path.resolve(part.name)
            val boundTarget = path.resolve(target.name)
            val current = Files.readAttributes(boundTarget, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!sameRevision(expected, current)) throw SshFailure("TARGET_CONFLICT")
            val partState = Files.readAttributes(boundPart, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!sameRevision(verified, partState)) throw SshFailure("CHECKPOINT_INVALID")
            Files.move(boundPart, boundTarget, ATOMIC_MOVE, REPLACE_EXISTING)
            Unit
        }

    fun sameRevision(expected: BasicFileAttributes, actual: BasicFileAttributes): Boolean =
        actual.isRegularFile && expected.fileKey() == actual.fileKey() && expected.size() == actual.size() &&
            expected.lastModifiedTime() == actual.lastModifiedTime()
}
