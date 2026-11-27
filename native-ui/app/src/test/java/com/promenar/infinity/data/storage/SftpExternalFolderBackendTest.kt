package com.promenar.infinity.data.storage

import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpExternalFolderBackendTest {
    @get:Rule val directory = TemporaryFolder()
    private val transport = MemoryMountTransport()
    private val mount = ExternalMount("test", "nas", "sftp_remote", "", "session", 0)
    private fun backend(readOnly: Boolean = false) =
        SftpExternalFolderBackend(mount.copy(readOnly = readOnly), SftpRemoteMountSpec("nas", "/mount"), directory.root) { transport }

    @Test fun 枚举隐藏链接与临时文件并传递预算() = runBlocking<Unit> {
        transport.put("/mount/a", byteArrayOf(4))
        transport.symlinks += "/mount/link"
        transport.put("/mount/.infinity-part-test.tmp", byteArrayOf())
        withContext(SftpDirectoryBudget(7)) { assertEquals(listOf("a"), backend().list("/").map { it.name }) }
        assertEquals(7, transport.lastListLimit)
        assertArrayEquals(byteArrayOf(4), backend().read("/a"))
    }

    @Test fun 拒绝链接越界只读与离线错误保持独立() = runBlocking<Unit> {
        transport.symlinks += "/mount/link"
        assertEquals("SYMLINK_UNSUPPORTED", fails { backend().stat("/link") })
        assertEquals("SYMLINK_UNSUPPORTED", fails { backend().stat("/link/a") })
        assertEquals("INVALID_PATH", fails { backend().stat("/../etc") })
        assertEquals("INVALID_PATH", fails { backend().stat("/a//b") })
        assertEquals("READ_ONLY", fails { backend(true).createDirectory("/new") })
        transport.directories.remove("/mount")
        transport.symlinks += "/mount"
        assertEquals("SYMLINK_UNSUPPORTED", fails { backend().list("/") })
        transport.statFailure = "TIMEOUT"
        assertEquals("TIMEOUT", fails { backend().list("/") })
    }

    @Test fun 新建重命名条件删除与流式写完整可用() = runBlocking<Unit> {
        val backend = backend()
        backend.createDirectory("/new")
        val data = byteArrayOf(11, 22, 33)
        backend.writeStream("/new/a", data.size.toLong()) { it.write(data) }
        assertEquals("CONFLICT", fails { backend.write("/new/a", data, createOnly = true) })
        backend.move("/new/a", "/new/b")
        val current = backend.stat("/new/b")!!
        backend.deleteForTransfer("/new/b", current, mountTestHash(data))
        backend.delete("/new")
        assertNull(backend.stat("/new"))
    }

    private suspend fun fails(action: suspend () -> Any?): String =
        (runCatching { action() }.exceptionOrNull() as ExternalFolderException).code
}

internal fun mountTestHash(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal class MemoryMountTransport : SftpMountTransport {
    private val data = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val directories = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("/", "/mount")) }
    val symlinks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val appendOffsets = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val readOffsets = java.util.concurrent.CopyOnWriteArrayList<Long>()
    var lastListLimit = 0
    var statFailure: String? = null
    var hardlink = true
    var onAppend: suspend (Long) -> Unit = {}
    var afterCreate: suspend () -> Unit = {}
    var afterPublish: suspend () -> Unit = {}
    var onRead: (Int) -> Unit = {}

    fun put(path: String, bytes: ByteArray) { data[path] = bytes; versions.merge(path, 1, Long::plus) }
    fun bytes(path: String): ByteArray? = data[path]

    override suspend fun stat(path: String): SftpDetailedEntry? {
        statFailure?.let { throw SshFailure(it) }
        val dir = path in directories
        val link = path in symlinks
        val file = data[path]
        if (!dir && !link && file == null) return null
        return SftpDetailedEntry(path.substringAfterLast('/'), path, dir, file != null, link,
            file?.size?.toLong() ?: 0, versions[path] ?: 0, 384)
    }

    override suspend fun list(path: String, limit: Int): List<SftpDetailedEntry> {
        lastListLimit = limit
        return (data.keys + directories + symlinks).filter { it != path && it.substringBeforeLast('/').ifEmpty { "/" } == path }
            .distinct().sorted().take(limit).mapNotNull { stat(it) }
    }

    override suspend fun mkdir(path: String) { directories += path }
    override suspend fun renameNoReplace(source: String, target: String) {
        if (stat(target) != null) throw SshFailure("TARGET_CONFLICT")
        put(target, data.remove(source) ?: error("源不存在"))
    }
    override suspend fun removeFile(path: String) { data.remove(path) }
    override suspend fun removeEmptyDir(path: String) {
        if (list(path).isNotEmpty()) throw SshFailure("NOT_EMPTY")
        directories.remove(path)
    }

    override suspend fun <T> read(path: String, maxBytes: Long, offset: Long, consume: suspend (InputStream) -> T): T {
        readOffsets += offset
        val bytes = data[path] ?: throw SshFailure("NOT_FOUND")
        if (bytes.size - offset > maxBytes) throw SshFailure("TOO_LARGE")
        return bytes.inputStream(offset.toInt(), bytes.size - offset.toInt()).use { source ->
            consume(object : java.io.FilterInputStream(source) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, length).also { if (it > 0) onRead(it) }
            })
        }
    }

    override suspend fun createTemporary(path: String) {
        if (data.putIfAbsent(path, byteArrayOf()) != null) throw SshFailure("TEMPORARY_CONFLICT")
        afterCreate()
    }
    override suspend fun append(path: String, offset: Long, content: ByteArray) {
        appendOffsets += offset
        onAppend(offset)
        val old = data[path] ?: throw SshFailure("OFFSET_MISMATCH")
        if (old.size.toLong() != offset) throw SshFailure("OFFSET_MISMATCH")
        data[path] = old + content
    }
    override suspend fun sha256(path: String) = RemoteSha256Receipt(
        mountTestHash(data[path] ?: throw SshFailure("NOT_FOUND")), "ssh_exec_sha256sum",
    )
    override suspend fun publish(temporary: String, target: String, expectedSha256: String): SftpMountPublishReceipt {
        val temp = data[temporary] ?: throw SshFailure("NOT_FOUND")
        val checksum = sha256(temporary)
        if (checksum.sha256 != expectedSha256) throw SshFailure("CHECKSUM_MISMATCH")
        val existing = data[target]
        if (existing != null) {
            if (mountTestHash(existing) != expectedSha256) throw SshFailure("TARGET_CONFLICT")
            return SftpMountPublishReceipt(checksum, true)
        }
        if (!hardlink) throw SshFailure("ATOMIC_PUBLISH_UNSUPPORTED")
        data[target] = temp
        afterPublish()
        return SftpMountPublishReceipt(checksum, false)
    }
}
