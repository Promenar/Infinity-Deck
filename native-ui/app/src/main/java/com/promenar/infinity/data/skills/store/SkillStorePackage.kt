package com.promenar.infinity.data.skills.store

import com.promenar.infinity.data.skills.AgentSkillStore
import com.promenar.infinity.data.skills.SkillMarkdownParser
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SkillStorePackage(private val client: HttpClient, private val cache: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Mutex()
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun download(entry: SkillStoreEntry, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(io) {
        lock.withLock {
            if (!cache.isDirectory && !cache.mkdirs()) throw StoreException(StoreFailure.STORAGE)
            val target = File(cache, "${entry.sha256}.zip")
            val part = File(cache, "${entry.sha256}.zip.part")
            if (target.isFile && target.length() == entry.size && try { digest(target.readBytes()) == entry.sha256 } catch (_: java.io.IOException) { throw StoreException(StoreFailure.STORAGE) }) return@withLock target
            try {
                client.prepareGet(entry.archiveUrl).execute { response ->
                    if (!response.call.request.url.protocol.name.equals("https", true)) throw StoreException(StoreFailure.INVALID_URL)
                    if (response.status.value !in 200..299) throw StoreException(StoreFailure.HTTP, response.status.value)
                    val channel = response.bodyAsChannel()
                    val hash = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val output = try { part.outputStream().buffered() } catch (_: java.io.IOException) { throw StoreException(StoreFailure.STORAGE) }
                    try {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count == -1) break
                            total += count
                            if (total > entry.size) throw StoreException(StoreFailure.TOO_LARGE)
                            try { output.write(buffer, 0, count) } catch (_: java.io.IOException) { throw StoreException(StoreFailure.STORAGE) }; hash.update(buffer, 0, count); progress(total, entry.size)
                        }
                    }
                    finally { try { output.close() } catch (_: java.io.IOException) { throw StoreException(StoreFailure.STORAGE) } }
                    val actual = hash.digest().joinToString("") { "%02x".format(it) }
                    if (total != entry.size || actual != entry.sha256) throw StoreException(StoreFailure.HASH_MISMATCH)
                }
                if (!part.renameTo(target)) throw StoreException(StoreFailure.STORAGE)
                target
            } catch (e: CancellationException) { throw e }
            catch (e: StoreException) { throw e }
            catch (_: Exception) { throw StoreException(StoreFailure.NETWORK) }
            finally { part.delete() }
        }
    }
    suspend fun extract(entry: SkillStoreEntry, file: File): Map<String, ByteArray> = withContext(io) {
        try {
            val result = linkedMapOf<String, ByteArray>()
            var total = 0L
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    val raw = item.name
                    if (raw.startsWith("__MACOSX/") || raw.substringAfterLast('/') == ".DS_Store") continue
                    val normalizedInput = if (item.isDirectory) raw.trimEnd('/') else raw
                    val path = AgentSkillStore.normalizeRelativePath(normalizedInput)
                    if (path != normalizedInput) throw StoreException(StoreFailure.INVALID_PACKAGE)
                    if (item.isDirectory) continue
                    if (result.containsKey(path) || result.size >= AgentSkillStore.MAX_FILES) throw StoreException(StoreFailure.INVALID_PACKAGE)
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n == -1) break
                        total += n
                        if (output.size() + n > AgentSkillStore.MAX_FILE_BYTES || total > AgentSkillStore.MAX_TOTAL_BYTES) throw StoreException(StoreFailure.TOO_LARGE)
                        output.write(buffer, 0, n)
                    }
                    result[path] = output.toByteArray()
                }
            }
            if (result.mapValues { it.value.size.toLong() } != entry.files.associate { it.path to it.size }) throw StoreException(StoreFailure.FILE_LIST_MISMATCH)
            val document = result["SKILL.md"]?.let(AgentSkillStore::decodeText) ?: throw StoreException(StoreFailure.INVALID_PACKAGE)
            if (SkillMarkdownParser.parse(document).metadata.name != entry.name) throw StoreException(StoreFailure.NAME_MISMATCH)
            result
        } catch (e: CancellationException) { throw e }
        catch (e: StoreException) { throw e }
        catch (_: Exception) { throw StoreException(StoreFailure.INVALID_PACKAGE) }
    }
}
