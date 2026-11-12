package com.promenar.infinity.data.production

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.data.storage.copyExternalStream
import com.promenar.infinity.data.storage.externalStreamHash
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 链自身的固定文件名可替换；替换前备份、条件删除，失败时恢复旧文件。 */
internal class WorkspaceChainOutputStore(private val cacheDir: File) {
    companion object { private val mutex = Mutex() }
    suspend fun publish(backend: ExternalFolderBackend, path: String, file: File, afterWrite: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ProductionManifestCodec.validatePath(path)
            require(Regex(".*/takes/chain-[A-Za-z0-9_-]+/(?:seg-[0-7](?:-last)?\\.(?:mp4|png)|joined(?:-[0-9]+)?\\.mp4)").matches(path)) { "链产物只能写入本链目录。" }
            val virtual = "/$path"
            val nextHash = file.inputStream().use { externalStreamHash(it) }
            val existing = backend.stat(virtual)
            val recovery = java.nio.file.Files.createTempDirectory(cacheDir.toPath(), "chain-output-").toFile()
            val backup = File(recovery, "original.bin")
            var oldHash: String? = null
            var original: ExternalFolderEntry? = null
            var deleted = false
            var written: ExternalFolderEntry? = null
            var keepRecovery = false
            try {
                if (existing != null) {
                    require(!existing.isDirectory && existing.identity != null) { "链产物缺少安全替换所需的文件身份。" }
                    oldHash = backend.readStream(virtual, existing.size) { input -> backup.outputStream().use { output -> copyExternalStream(input, output).also { output.fd.sync() } } }
                    val checked = requireNotNull(backend.stat(virtual)) { "链产物在备份期间消失。" }
                    require(checked.identity == existing.identity && checked.size == existing.size && backup.length() == existing.size && (checked.sha256 == null || checked.sha256 == oldHash)) { "链产物在备份期间发生变化。" }
                    original = checked
                    if (oldHash == nextHash) return@withLock
                    File(recovery, "target.txt").writeText(path, Charsets.UTF_8)
                }
                var parent = ""
                path.substringBeforeLast('/').split('/').forEach { name ->
                    parent += "/$name"
                    val entry = backend.stat(parent)
                    if (entry == null) backend.createDirectory(parent) else require(entry.isDirectory) { "链产物父级不是目录。" }
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    original?.let { deleted = true; backend.deleteForTransfer(virtual, it, oldHash) }
                    written = backend.writeStream(virtual, file.length()) { output -> file.inputStream().use { copyExternalStream(it, output) } }
                    val actual = backend.readStream(virtual, file.length()) { externalStreamHash(it) }
                    require(actual == nextHash) { "链产物写入校验失败。" }
                    afterWrite()
                }
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try {
                        written?.let { backend.deleteForTransfer(virtual, it, nextHash) }
                        if (deleted) {
                            val current = backend.stat(virtual)
                            if (current == null) {
                                backend.writeStream(virtual, backup.length()) { output -> backup.inputStream().use { copyExternalStream(it, output) } }
                                afterWrite()
                            } else {
                                require(current.identity == original?.identity && backend.readStream(virtual, current.size) { externalStreamHash(it) } == oldHash) { "链产物目标被其它内容占用，无法自动恢复。" }
                            }
                        }
                    } catch (rollback: Exception) {
                        keepRecovery = true
                        throw IllegalStateException("链产物恢复失败，备份位置：${recovery.path}", rollback).apply { addSuppressed(failure) }
                    }
                }
                throw failure
            } finally { if (!keepRecovery) withContext(NonCancellable + Dispatchers.IO) { recovery.deleteRecursively() } }
        }
    }
}
