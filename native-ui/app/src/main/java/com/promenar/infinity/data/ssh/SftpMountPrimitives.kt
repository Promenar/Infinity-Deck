package com.promenar.infinity.data.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class SftpDetailedEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val isRegular: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifiedAtMillis: Long,
    val permissions: Int,
)

/** 绑定到单一主机配置的传输接口，供挂载与传输调度器共用。 */
interface SftpMountTransport {
    suspend fun stat(path: String): SftpDetailedEntry?
    suspend fun list(path: String, limit: Int = 1024): List<SftpDetailedEntry>
    suspend fun mkdir(path: String)
    suspend fun renameNoReplace(source: String, target: String)
    suspend fun removeFile(path: String)
    suspend fun removeEmptyDir(path: String)
    suspend fun <T> read(path: String, maxBytes: Long, offset: Long = 0, consume: suspend (InputStream) -> T): T
    suspend fun createTemporary(path: String)
    suspend fun append(path: String, offset: Long, content: ByteArray)
    suspend fun sha256(path: String): RemoteSha256Receipt
    suspend fun publish(temporary: String, target: String, expectedSha256: String): SftpMountPublishReceipt
}

data class SftpMountPublishReceipt(val checksum: RemoteSha256Receipt, val alreadyExistedSameHash: Boolean)

internal fun sftpChild(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

internal fun canonicalSftpPath(path: String): String = try {
    SftpRemoteMountSpec.canonicalPath(path)
} catch (_: com.promenar.infinity.domain.storage.ExternalFolderException) {
    throw SshFailure("INVALID_REMOTE_PATH")
}

// JSch 的路径 API 会展开 * 和 ?，挂载操作只将文件名解释为字面值。
internal fun sftpLiteral(path: String): String = path.replace("*", "\\*").replace("?", "\\?")

internal fun ChannelSftp.mountStat(path: String): SftpDetailedEntry? = try {
    lstat(sftpLiteral(path)).mountEntry(path)
} catch (failure: SftpException) {
    if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw mountSftpError(failure)
}

internal fun SftpATTRS.mountEntry(path: String) = SftpDetailedEntry(
    path.substringAfterLast('/').ifEmpty { "/" }, path, isDir, isReg, isLink, size,
    mTime.toLong().and(0xffffffffL) * 1000, permissions,
)

internal fun ChannelSftp.checkedMountEntry(path: String): SftpDetailedEntry? {
    var prefix = "/"
    for (segment in path.removePrefix("/").split('/').filter(String::isNotEmpty)) {
        val parent = mountStat(prefix) ?: throw SshFailure("NOT_FOUND")
        if (parent.isSymlink) throw SshFailure("SYMLINK_UNSUPPORTED")
        if (!parent.isDirectory) throw SshFailure("NOT_DIRECTORY")
        prefix = sftpChild(prefix, segment)
    }
    return mountStat(path)?.also {
        if (it.isSymlink) throw SshFailure("SYMLINK_UNSUPPORTED")
        if (!it.isRegular && !it.isDirectory) throw SshFailure("UNSUPPORTED_FILE_TYPE")
    }
}

internal fun mountSftpError(failure: SftpException): SshFailure = when (failure.id) {
    ChannelSftp.SSH_FX_NO_SUCH_FILE -> SshFailure("NOT_FOUND")
    ChannelSftp.SSH_FX_PERMISSION_DENIED -> SshFailure("ACCESS_DENIED")
    ChannelSftp.SSH_FX_OP_UNSUPPORTED -> SshFailure("UNSUPPORTED")
    ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST -> SshFailure("CONNECTION_FAILED")
    else -> classifySshFailure(failure, fallbackCode = "SFTP_IO_FAILED")
}

internal fun requireSftpPart(path: String) {
    if (!Regex("""\.infinity-part-[A-Za-z0-9-]{1,64}\.tmp""").matches(path.substringAfterLast('/'))) {
        throw SshFailure("INVALID_TEMPORARY_PATH")
    }
}

/** JSch 的高级接口没有排他 OPEN，并会自动协商覆盖式 rename；这两个请求使用 SFTP v3。 */
internal class SftpV3MountPacket(input: InputStream, output: OutputStream) {
    private val source = DataInputStream(input)
    private val target = DataOutputStream(output)
    private var requestId = 0

    init {
        send(1) { writeInt(3) }
        val version = receive()
        if (version.readUnsignedByte() != 2 || version.readInt() != 3) throw SshFailure("UNSUPPORTED")
    }

    fun createPrivate(path: String) {
        val handle = request(3, 102) {
            string(path.toByteArray(Charsets.UTF_8))
            writeInt(0x02 or 0x08 or 0x20) // WRITE | CREAT | EXCL
            writeInt(0x04) // SSH_FILEXFER_ATTR_PERMISSIONS
            writeInt(0x180) // 0600
        }
        val length = handle.readInt()
        if (length !in 1..256) throw SshFailure("SFTP_PROTOCOL_ERROR")
        val value = ByteArray(length).also(handle::readFully)
        request(4, 101) { string(value) }
    }

    fun renameNoReplace(source: String, target: String) {
        request(18, 101) {
            string(source.toByteArray(Charsets.UTF_8))
            string(target.toByteArray(Charsets.UTF_8))
        }
    }

    private fun request(type: Int, expected: Int, body: DataOutputStream.() -> Unit): DataInputStream {
        val id = ++requestId
        send(type) { writeInt(id); body() }
        val response = receive()
        val responseType = response.readUnsignedByte()
        if (response.readInt() != id) throw SshFailure("SFTP_PROTOCOL_ERROR")
        if (responseType == 101) {
            val status = response.readInt()
            if (status != 0) throw mountSftpError(SftpException(status, ""))
        }
        if (responseType != expected) throw SshFailure("SFTP_PROTOCOL_ERROR")
        return response
    }

    private fun send(type: Int, body: DataOutputStream.() -> Unit) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.writeByte(type); it.body() }
        target.writeInt(payload.size())
        payload.writeTo(target)
        target.flush()
    }

    private fun receive(): DataInputStream {
        val length = source.readInt()
        if (length !in 5..262_144) throw SshFailure("SFTP_PROTOCOL_ERROR")
        return DataInputStream(ByteArray(length).also(source::readFully).inputStream())
    }

    private fun DataOutputStream.string(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }
}

internal suspend fun SftpMountTransport.writeMountBytes(
    target: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean,
): SftpMountPublishReceipt {
    val temp = sftpChild(target.substringBeforeLast('/').ifEmpty { "/" }, ".infinity-part-${java.util.UUID.randomUUID()}.tmp")
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
    val previous = stat(target)
    if (previous != null) {
        if (createOnly || !previous.isRegular || previous.isSymlink || !hash.equals(expectedSha256, ignoreCase = true)) {
            throw SshFailure("TARGET_CONFLICT")
        }
        val receipt = sha256(target)
        if (receipt.sha256 != hash) throw SshFailure("TARGET_CONFLICT")
        return SftpMountPublishReceipt(receipt, true)
    }
    if (expectedSha256 != null) throw SshFailure("TARGET_CONFLICT")
    createTemporary(temp)
    try {
        var offset = 0
        while (offset < content.size) {
            val end = minOf(content.size, offset + SFTP_MOUNT_CHUNK_BYTES)
            append(temp, offset.toLong(), content.copyOfRange(offset, end))
            offset = end
        }
        val result = publish(temp, target, hash)
        if (createOnly && result.alreadyExistedSameHash) throw SshFailure("TARGET_CONFLICT")
        return result
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { removeFile(temp) } }
    }
}

internal const val SFTP_MOUNT_CHUNK_BYTES = 256 * 1024

internal class SftpMountBoundedInput(private val source: InputStream, private val limit: Long) : InputStream() {
    private var readBytes = 0L

    override fun read(): Int {
        val byte = source.read()
        if (byte >= 0 && readBytes++ == limit) throw SshFailure("TOO_LARGE")
        return byte
    }

    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        if (readBytes == limit) return read()
        val count = source.read(bytes, start, minOf(length.toLong(), limit - readBytes).toInt())
        if (count > 0) readBytes += count
        return count
    }
}

enum class SftpChunkDirective { CONTINUE, PAUSE, CANCEL }
data class SftpChunkResult(val confirmedBytes: Long, val paused: Boolean)

suspend fun SftpMountTransport.sftpResumableUploadChunked(
    temporary: String,
    source: java.io.File,
    size: Long,
    offset: Long,
    directive: () -> SftpChunkDirective,
    checkpoint: suspend (Long) -> Unit,
): SftpChunkResult = java.io.RandomAccessFile(source, "r").use { input ->
    require(offset in 0..size)
    input.seek(offset)
    var confirmed = offset
    while (confirmed < size) {
        if (directive().stop()) return@use SftpChunkResult(confirmed, true)
        val chunk = ByteArray(minOf(SFTP_MOUNT_CHUNK_BYTES.toLong(), size - confirmed).toInt())
        input.readFully(chunk)
        append(temporary, confirmed, chunk)
        confirmed += chunk.size
        checkpoint(confirmed)
    }
    SftpChunkResult(confirmed, directive().stop())
}

suspend fun SftpMountTransport.sftpResumableDownloadChunked(
    source: String,
    temporary: java.io.File,
    size: Long,
    offset: Long,
    directive: () -> SftpChunkDirective,
    checkpoint: suspend (Long) -> Unit,
): SftpChunkResult = java.io.RandomAccessFile(temporary, "rw").use { output ->
    require(offset in 0..size)
    if (output.length() != offset) throw SshFailure("OFFSET_MISMATCH")
    output.seek(offset)
    val chunk = ByteArray(SFTP_MOUNT_CHUNK_BYTES)
    var confirmed = offset
    while (confirmed < size) {
        if (directive().stop()) return@use SftpChunkResult(confirmed, true)
        // 每个通道只承担一组有界块，单次网络超时不限制整份文件的传输时长。
        val windowEnd = confirmed + minOf(SFTP_MOUNT_CHUNK_BYTES * 16L, size - confirmed)
        val paused = read(source, size - confirmed, confirmed) { input ->
            while (confirmed < windowEnd) {
                if (directive().stop()) return@read true
                val expected = minOf(chunk.size.toLong(), windowEnd - confirmed).toInt()
                var filled = 0
                while (filled < expected) {
                    val count = input.read(chunk, filled, expected - filled)
                    if (count <= 0) throw SshFailure("SOURCE_CHANGED")
                    filled += count
                }
                // 每个已报告检查点都已落盘，可供进程中断后重新打开。
                output.write(chunk, 0, filled)
                output.fd.sync()
                confirmed += filled
                checkpoint(confirmed)
            }
            false
        }
        if (paused) return@use SftpChunkResult(confirmed, true)
    }
    SftpChunkResult(confirmed, directive().stop())
}

private fun SftpChunkDirective.stop(): Boolean = when (this) {
    SftpChunkDirective.CONTINUE -> false
    SftpChunkDirective.PAUSE -> true
    SftpChunkDirective.CANCEL -> throw kotlinx.coroutines.CancellationException("远程传输已取消")
}

internal fun sftpPartPath(target: String, taskId: String): String =
    sftpChild(target.substringBeforeLast('/').ifEmpty { "/" }, ".infinity-part-$taskId.tmp").also(::requireSftpPart)
