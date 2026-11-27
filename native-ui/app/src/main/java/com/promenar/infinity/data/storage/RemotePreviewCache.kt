package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val REMOTE_AUTO_PREVIEW_MAX_BYTES = 8L * 1024 * 1024
const val REMOTE_PREVIEW_CACHE_MAX_BYTES = 256L * 1024 * 1024

data class RemotePreviewCacheStats(val usedBytes: Long, val maxBytes: Long, val entryCount: Int)

data class RemotePreviewKey(
    val hostIdentityDigest: String,
    val canonicalRemotePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
) {
    internal val digest: String
        get() {
            require(sizeBytes >= 0 && hostIdentityDigest.isNotBlank() && '\u0000' !in hostIdentityDigest)
            val path = SftpRemoteMountSpec.canonicalPath(canonicalRemotePath)
            return MessageDigest.getInstance("SHA-256")
                .digest("$hostIdentityDigest\u0000$path\u0000$sizeBytes\u0000$modifiedAtMillis".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
}

internal fun remotePreviewCanRead(size: Long, cached: Boolean): Boolean = size <= REMOTE_AUTO_PREVIEW_MAX_BYTES || cached

class RemotePreviewCache(
    cacheDir: File,
    private val maxBytes: Long = REMOTE_PREVIEW_CACHE_MAX_BYTES,
) : AutoCloseable {
    private val root = File(cacheDir, "remote-preview-cache")
    private val index = File(root, "index.json")
    private val shares = File(cacheDir, "workspace-media-share/remote-preview-cache")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Flight>()
    private val entries = linkedMapOf<String, Record>()
    private var generation = 0L
    private var accessClock = 0L
    private val mutableStats = MutableStateFlow(RemotePreviewCacheStats(0, maxBytes, 0))
    val stats = mutableStats.asStateFlow()

    @Serializable
    private data class Record(val key: String, val size: Long, val access: Long)
    @Serializable
    private data class Index(val version: Int = 1, val rows: List<Record>)

    private data class Flight(val token: Any, val job: Deferred<File?>)

    init {
        require(maxBytes > 0)
        Files.createDirectories(root.toPath())
        val persisted = runCatching { if (index.isFile) Json.decodeFromString<Index>(index.readText()) else null }.getOrNull()
        if (persisted?.version == 1) {
            persisted.rows.sortedBy { it.access }.forEach { row ->
                if (row.key.matches(KEY) && row.size in 0..maxBytes && validFile(data(row.key), row.size)) {
                    entries[row.key] = row
                    accessClock = maxOf(accessClock, row.access)
                }
            }
        }
        root.listFiles().orEmpty().filter { it != index && it.name.removeSuffix(".bin") !in entries }.forEach(File::delete)
        shares.listFiles().orEmpty().filter { share ->
            val key = share.name.substringBefore('-')
            key !in entries || !runCatching { Files.isSameFile(share.toPath(), data(key).toPath()) }.getOrDefault(false)
        }.forEach(File::delete)
        evict()
        persist()
    }

    suspend fun lookup(key: RemotePreviewKey): File? = withContext(Dispatchers.IO) {
        lock.withLock { touch(key.digest) }
    }

    suspend fun getOrFetch(key: RemotePreviewKey, fetch: suspend (OutputStream) -> Unit): File? {
        val digest = key.digest
        val flight = withContext(Dispatchers.IO) { lock.withLock {
            touch(digest)?.let { return@withLock Flight(Any(), kotlinx.coroutines.CompletableDeferred(it)) }
            if (key.sizeBytes > maxBytes) return@withLock null
            pending[digest]?.let { return@withLock it }
            val epoch = generation
            val token = Any()
            val deferred = scope.async(start = CoroutineStart.LAZY) { fill(digest, key.sizeBytes, epoch, token, fetch) }
            Flight(token, deferred).also { pending[digest] = it; deferred.start() }
        } }
        return flight?.job?.await()
    }

    internal suspend fun generationToken(): Long = lock.withLock { generation }

    /** 显式传输已校验完整内容，允许替换同尺寸同时间戳但摘要不匹配的旧条目。 */
    internal suspend fun publishVerified(
        key: RemotePreviewKey, source: File, expectedSha256: String, epoch: Long,
    ): Boolean? = withContext(Dispatchers.IO) { lock.withLock {
        if (epoch != generation || key.sizeBytes > maxBytes) return@withLock null
        val digest = key.digest
        val existing = touch(digest)
        if (existing != null && sha256File(existing) == expectedSha256) return@withLock true
        if (!validFile(source, key.sizeBytes)) return@withLock null
        val temporary = File(root, "${UUID.randomUUID()}.part")
        try {
            // 保留传输检查点直到缓存提交；Android 存储可能禁止硬链接。
            try {
                Files.createLink(temporary.toPath(), source.toPath())
            } catch (_: java.io.IOException) {
                Files.copy(source.toPath(), temporary.toPath(), NOFOLLOW_LINKS)
            }
            if (!validFile(temporary, key.sizeBytes) || sha256File(temporary) != expectedSha256) return@withLock null
            pending.remove(digest)
            deleteShares(digest)
            install(digest, key.sizeBytes, temporary)
            false
        } finally {
            temporary.delete()
        }
    } }

    /** 在现有 FileProvider 已授权的目录创建同 inode 的入口，随缓存条目一起淘汰。 */
    suspend fun shareFile(key: RemotePreviewKey): File? = withContext(Dispatchers.IO) { lock.withLock {
        val digest = key.digest
        val file = touch(digest) ?: return@withLock null
        Files.createDirectories(shares.toPath())
        val extension = key.canonicalRemotePath.substringAfterLast('/').substringAfterLast('.', "")
        val suffix = if (Regex("[A-Za-z0-9]{1,16}").matches(extension)) ".$extension" else ""
        val share = File(shares, "$digest-file$suffix")
        if (!Files.exists(share.toPath(), NOFOLLOW_LINKS)) Files.createLink(share.toPath(), file.toPath())
        if (!Files.isSameFile(share.toPath(), file.toPath())) {
            Files.delete(share.toPath())
            Files.createLink(share.toPath(), file.toPath())
        }
        share
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        generation++
        pending.clear()
        entries.keys.toList().forEach(::delete)
        persist()
    } }

    override fun close() { scope.cancel() }

    private suspend fun fill(
        key: String, size: Long, epoch: Long, token: Any, fetch: suspend (OutputStream) -> Unit,
    ): File? {
        val temporary = File(root, "${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { stream ->
                produceExternalStream(stream, size, fetch)
                stream.fd.sync()
            }
            return lock.withLock {
                if (generation != epoch) return@withLock null
                if (pending[key]?.token !== token) return@withLock touch(key)
                install(key, size, temporary)
            }
        } finally {
            temporary.delete()
            withContext(NonCancellable) { lock.withLock {
                if (pending[key]?.token === token) pending.remove(key)
            } }
        }
    }

    private fun install(key: String, size: Long, temporary: File): File {
        val file = data(key)
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        entries.remove(key)
        entries[key] = Record(key, size, ++accessClock)
        evict()
        persist()
        return file
    }

    private fun validFile(file: File, size: Long): Boolean =
        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == size

    private fun touch(key: String): File? {
        val row = entries[key] ?: return null
        val file = data(key)
        if (!validFile(file, row.size)) {
            delete(key)
            persist()
            return null
        }
        entries.remove(key)
        entries[key] = row.copy(access = ++accessClock)
        persist()
        return file
    }

    private fun evict() {
        var used = entries.values.sumOf { it.size }
        while (used > maxBytes) {
            val row = entries.values.first()
            delete(row.key)
            used -= row.size
        }
    }

    private fun delete(key: String) {
        deleteShares(key)
        Files.deleteIfExists(data(key).toPath())
        entries.remove(key)
    }

    private fun deleteShares(key: String) {
        shares.listFiles { _, name -> name.startsWith("$key-") }.orEmpty()
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun persist() {
        val temporary = File(root, "index.tmp")
        FileOutputStream(temporary).use {
            it.write(Json.encodeToString(Index(rows = entries.values.toList())).toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), index.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        mutableStats.value = RemotePreviewCacheStats(entries.values.sumOf { it.size }, maxBytes, entries.size)
    }

    private fun data(key: String) = File(root, "$key.bin")

    private companion object { val KEY = Regex("[0-9a-f]{64}") }
}
