package com.promenar.infinity.data.storage

import com.promenar.infinity.data.ssh.SftpChunkDirective
import com.promenar.infinity.data.ssh.SftpDetailedEntry
import com.promenar.infinity.data.ssh.SftpMountTransport
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.sftpChild
import com.promenar.infinity.data.ssh.sftpPartPath
import com.promenar.infinity.data.ssh.sftpResumableUploadChunked
import com.promenar.infinity.data.ssh.writeMountBytes
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

class SftpExternalFolderBackend(
    private val mount: ExternalMount,
    spec: SftpRemoteMountSpec,
    private val stagingDirectory: File,
    private val connect: suspend () -> SftpMountTransport,
) : ExternalFolderBackend {
    private val root = spec.normalized().rootPath

    override suspend fun stat(path: String): ExternalFolderEntry? = call {
        val target = checked(path)
        target.entry?.toEntry(target.relative)
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = call {
        val target = checked(path).requireDirectory()
        val budget = currentCoroutineContext()[SftpDirectoryBudget]?.limit ?: 1024
        list(target.fullPath, budget).mapNotNull { entry ->
            val name = entry.name
            if (entry.isSymlink || !(entry.isRegular || entry.isDirectory) || name.startsWith(".infinity-part-") ||
                !runCatching { ExternalFolderPaths.validateName(name); SftpRemoteMountSpec.canonicalPath("/$name") }.isSuccess ||
                entry.fullPath != sftpChild(target.fullPath, name)
            ) return@mapNotNull null
            entry.toEntry(sftpChild(target.relative, name))
        }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray =
        readStream(path, maxBytes) { it.readBytes() }

    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T = call {
        val target = checked(path).requireRegular()
        read(target.fullPath, maxBytes, consume = consume)
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = writeCall {
        val target = checked(path, modifying = true)
        val receipt = writeMountBytes(target.fullPath, content, expectedSha256, createOnly)
        checked(path).requireRegular().toEntry().copy(sha256 = receipt.checksum.sha256)
    }

    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit): ExternalFolderEntry = writeCall {
        if (!createOnly) throw ExternalFolderException("UNSUPPORTED", "远程流式写入仅支持新建文件")
        val target = checked(path, modifying = true)
        if (target.entry != null) throw SshFailure("TARGET_CONFLICT")
        Files.createDirectories(stagingDirectory.toPath())
        val local = Files.createTempFile(stagingDirectory.toPath(), "remote-write-", ".tmp").toFile()
        val remote = sftpPartPath(target.fullPath, UUID.randomUUID().toString())
        try {
            local.outputStream().use { produceExternalStream(it, expectedSize, produce) }
            val digest = sha256File(local)
            createTemporary(remote)
            try {
                sftpResumableUploadChunked(remote, local, expectedSize, 0, { SftpChunkDirective.CONTINUE }) {}
                val published = publish(remote, target.fullPath, digest)
                if (published.alreadyExistedSameHash) throw SshFailure("TARGET_CONFLICT")
            } finally {
                withContext(NonCancellable) { runCatching { removeFile(remote) } }
            }
            checked(path).requireRegular().toEntry().copy(sha256 = digest)
        } finally {
            local.delete()
        }
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = writeCall {
        val target = checked(path, modifying = true)
        if (target.entry != null) throw SshFailure("TARGET_CONFLICT")
        mkdir(target.fullPath)
        checked(path).requireDirectory().toEntry()
    }

    override suspend fun delete(path: String, expectedSha256: String?) = writeCall {
        val target = checked(path, modifying = true)
        val entry = target.entry ?: throw SshFailure("NOT_FOUND")
        if (expectedSha256 != null && (!entry.isRegular || sha256(target.fullPath).sha256 != expectedSha256)) {
            throw SshFailure("TARGET_CONFLICT")
        }
        if (entry.isDirectory) removeEmptyDir(target.fullPath) else removeFile(target.fullPath)
    }

    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) = writeCall {
        val target = checked(path, modifying = true)
        val entry = target.toEntry()
        if (entry.identity != expected.identity || entry.isDirectory != expected.isDirectory) throw SshFailure("TARGET_CONFLICT")
        if (entry.isDirectory) {
            removeEmptyDir(target.fullPath)
        } else {
            if (expectedSha256 == null || sha256(target.fullPath).sha256 != expectedSha256) throw SshFailure("TARGET_CONFLICT")
            removeFile(target.fullPath)
        }
    }

    override suspend fun move(sourcePath: String, targetPath: String) = writeCall {
        val source = checked(sourcePath, modifying = true)
        val target = checked(targetPath, modifying = true)
        if (source.entry == null) throw SshFailure("NOT_FOUND")
        if (target.entry != null) throw SshFailure("TARGET_CONFLICT")
        renameNoReplace(source.fullPath, target.fullPath)
    }

    override suspend fun validateTree(path: String) = call {
        if (checked(path).entry == null) throw SshFailure("NOT_FOUND")
    }

    private suspend fun SftpMountTransport.checked(path: String, modifying: Boolean = false): Target {
        val relative = SftpRemoteMountSpec.canonicalPath(path)
        val segments = relative.removePrefix("/").split('/').filter(String::isNotEmpty)
        segments.forEach {
            ExternalFolderPaths.validateName(it)
            if (it.startsWith(".infinity-part-")) throw ExternalFolderException("INVALID_PATH", "传输临时文件为保留路径")
        }
        if (modifying && relative == "/") throw ExternalFolderException("INVALID_PATH", "不能修改远程挂载根目录")
        var current = "/"
        var entry = safeStat(current)
        val rootSegments = root.removePrefix("/").split('/').filter(String::isNotEmpty)
        for (segment in rootSegments) {
            if (entry?.isDirectory != true) throw SshFailure("NOT_DIRECTORY")
            current = sftpChild(current, segment)
            entry = safeStat(current)
        }
        if (entry?.isDirectory != true) throw SshFailure("NOT_DIRECTORY")
        for ((index, segment) in segments.withIndex()) {
            if (entry == null) throw SshFailure("NOT_FOUND")
            if (!entry.isDirectory) throw SshFailure("NOT_DIRECTORY")
            current = sftpChild(current, segment)
            entry = safeStat(current)
            if (entry == null && index < segments.lastIndex) throw SshFailure("NOT_FOUND")
        }
        return Target(relative, current, entry)
    }

    private suspend fun SftpMountTransport.safeStat(path: String): SftpDetailedEntry? = stat(path)?.also {
        if (it.fullPath != path) throw SshFailure("INVALID_REMOTE_PATH")
        if (it.isSymlink) throw SshFailure("SYMLINK_UNSUPPORTED")
        if (!it.isDirectory && !it.isRegular) throw SshFailure("UNSUPPORTED_FILE_TYPE")
    }

    private data class Target(val relative: String, val fullPath: String, val entry: SftpDetailedEntry?) {
        fun requireDirectory(): Target {
            if (entry?.isDirectory != true) throw SshFailure("NOT_DIRECTORY")
            return this
        }

        fun requireRegular(): Target {
            if (entry == null) throw SshFailure("NOT_FOUND")
            if (!entry.isRegular) throw SshFailure("UNSUPPORTED_FILE_TYPE")
            return this
        }

        fun toEntry() = (entry ?: throw SshFailure("NOT_FOUND")).toEntry(relative)
    }

    private suspend fun <T> writeCall(action: suspend SftpMountTransport.() -> T): T {
        mount.requireWritable()
        return call(action)
    }

    private suspend fun <T> call(action: suspend SftpMountTransport.() -> T): T = withContext(Dispatchers.IO) {
        try { connect().action() } catch (failure: SshFailure) { throw sftpFolderFailure(failure) }
    }
}

private fun SftpDetailedEntry.toEntry(relative: String) =
    ExternalFolderEntry(relative, name, isDirectory, size, modifiedAtMillis, identity = "$fullPath:$size:$modifiedAtMillis")

internal fun sftpFolderFailure(failure: SshFailure): ExternalFolderException {
    val code = when (failure.code) {
        "HOST_NOT_FOUND" -> "SSH_HOST_MISSING"
        "TARGET_CONFLICT" -> "CONFLICT"
        else -> failure.code
    }
    val message = when (code) {
        "SSH_HOST_MISSING" -> "SSH 主机配置缺失"
        "NETWORK_OFFLINE", "TIMEOUT", "CONNECTION_FAILED", "CONNECTION_REFUSED", "HOST_UNREACHABLE", "DNS_FAILED" -> "远程目录当前不可用，请刷新重试"
        "NOT_FOUND", "NOT_DIRECTORY" -> "远程文件或目录不存在"
        "SYMLINK_UNSUPPORTED" -> "远程挂载拒绝访问符号链接"
        "ACCESS_DENIED" -> "远程服务器拒绝文件访问"
        "CONFLICT" -> "远程目标已存在或文件已变更"
        "ATOMIC_PUBLISH_UNSUPPORTED" -> "远程服务器不支持原子发布"
        else -> "远程文件操作失败（$code）"
    }
    return ExternalFolderException(code, message)
}

internal fun sha256File(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
        val bytes = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(bytes)
            if (read < 0) break
            digest.update(bytes, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** 远程搜索把剩余条目预算带到 SFTP 目录枚举层。 */
internal class SftpDirectoryBudget(val limit: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SftpDirectoryBudget>
}
