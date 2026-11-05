package com.promenar.infinity.data.rag

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class EmbeddingCheckpointIdentity(
    val contentHash: String,
    val modelKey: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
)

data class EmbeddingBatchProgress(
    val processed: Int,
    val total: Int,
    val lastError: String? = null,
    val isRetrying: Boolean = false,
)

/** 中文按一个 UTF-16 单元估算一个 token，补充字符按两个单元估算。 */
internal fun embeddingBatchRanges(texts: List<String>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    var size = 0L
    for (index in texts.indices) {
        val cost = texts[index].length.toLong().coerceAtLeast(1)
        if (index > start && (index - start >= 16 || size + cost > 8_000)) {
            ranges += start until index
            start = index
            size = 0
        }
        size += cost
    }
    if (start < texts.size) ranges += start until texts.size
    return ranges
}

/** 私有目录中的每个文件只包含切块摘要和一批浮点向量。 */
internal class EmbeddingCheckpoints(private val root: File) {
    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hash(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun docDir(workspace: String, file: String) = File(root, hash("$workspace\u0000$file"))
    private fun identityKey(identity: EmbeddingCheckpointIdentity) = hash(
        listOf("v1", identity.contentHash, identity.modelKey, identity.chunkSize, identity.chunkOverlap).joinToString("\u0000"),
    )
    private fun dir(workspace: String, file: String, identity: EmbeddingCheckpointIdentity) =
        File(docDir(workspace, file), identityKey(identity))
    private fun chunkHash(chunks: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { chunk ->
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            md.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun prepare(workspace: String, file: String, identity: EmbeddingCheckpointIdentity) {
        val current = identityKey(identity)
        docDir(workspace, file).listFiles()?.filter { it.isDirectory && it.name != current }
            ?.forEach { check(it.deleteRecursively()) { "无法清理失效的向量检查点" } }
    }

    fun load(workspace: String, file: String, identity: EmbeddingCheckpointIdentity, start: Int, chunks: List<String>): List<FloatArray>? {
        val target = File(dir(workspace, file, identity), "$start.bin")
        if (!target.isFile) return null
        return try {
            check(target.length() in 33..MAX_CHECKPOINT_BYTES)
            val bytes = target.readBytes()
            val payload = bytes.copyOfRange(0, bytes.size - 32)
            check(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                check(input.readInt() == MAGIC && input.readInt() == start)
                check(input.readUTF() == chunkHash(chunks) && input.readInt() == chunks.size)
                val vectors = chunks.map {
                    val dimension = input.readInt()
                    check(dimension in 1..MAX_DIMENSION && dimension <= input.available() / Float.SIZE_BYTES)
                    FloatArray(dimension) { input.readFloat().also { value -> check(value.isFinite()) } }
                }
                check(input.available() == 0)
                vectors
            }
        } catch (_: Exception) {
            check(!target.exists() || target.delete()) { "无法清理损坏的向量检查点" }
            null
        }
    }

    fun save(workspace: String, file: String, identity: EmbeddingCheckpointIdentity, start: Int,
        chunks: List<String>, vectors: List<FloatArray>) {
        require(vectors.size == chunks.size && vectors.all { it.isNotEmpty() && it.size <= MAX_DIMENSION && it.all(Float::isFinite) }) {
            "向量结果数量或维度无效"
        }
        val directory = dir(workspace, file, identity)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建向量检查点目录" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(start)
            out.writeUTF(chunkHash(chunks))
            out.writeInt(vectors.size)
            vectors.forEach { vector -> out.writeInt(vector.size); vector.forEach(out::writeFloat) }
        }
        val payload = buffer.toByteArray()
        require(payload.size.toLong() + 32 <= MAX_CHECKPOINT_BYTES) { "向量检查点超过大小限制" }
        val target = File(directory, "$start.bin")
        val temporary = File(directory, ".$start.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.write(digest(payload))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "无法写入向量检查点" }
        } finally { temporary.delete() }
    }

    fun clear(workspace: String, file: String, identity: EmbeddingCheckpointIdentity? = null) {
        val target = if (identity == null) docDir(workspace, file) else dir(workspace, file, identity)
        check(!target.exists() || target.deleteRecursively()) { "无法清理向量检查点" }
        val parent = docDir(workspace, file)
        if (identity != null && parent.isDirectory && parent.listFiles()?.isEmpty() == true) parent.delete()
    }

    private companion object {
        const val MAGIC = 0x49455731
        const val MAX_DIMENSION = 65_536
        const val MAX_CHECKPOINT_BYTES = 32L * 1024 * 1024
    }
}

internal class CheckpointedEmbeddingRunner(
    private val checkpoints: EmbeddingCheckpoints,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val embedBatch: suspend (List<String>) -> List<FloatArray>,
) {
    suspend fun embed(workspace: String, file: String, identity: EmbeddingCheckpointIdentity, chunks: List<String>,
        onProgress: suspend (EmbeddingBatchProgress) -> Unit = {}): List<FloatArray> = withContext(Dispatchers.IO) {
        checkpoints.prepare(workspace, file, identity)
        val vectors = mutableListOf<FloatArray>()
        var lastError: String? = null
        onProgress(EmbeddingBatchProgress(0, chunks.size))
        for (range in embeddingBatchRanges(chunks)) {
            val batch = chunks.slice(range)
            val restored = checkpoints.load(workspace, file, identity, range.first, batch)
            val result = restored ?: run {
                var retry = 0
                var completed: List<FloatArray>? = null
                while (completed == null) {
                    try {
                        completed = embedBatch(batch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val error = when (failure) {
                            is EmbeddingBatchException -> failure
                            is IOException -> EmbeddingBatchException("向量请求网络错误，请检查连接", true)
                            else -> EmbeddingBatchException("向量请求失败，请检查模型配置", false)
                        }
                        lastError = error.message
                        val canRetry = error.retryable && retry < 3
                        onProgress(EmbeddingBatchProgress(vectors.size, chunks.size, lastError, isRetrying = canRetry))
                        if (!canRetry) throw error
                        val backoff = 3_000L * (1L shl retry++)
                        retryDelay(maxOf(backoff, error.retryAfterMillis ?: 0L))
                    }
                }
                check(completed.size == batch.size) { "Embedding 数量与切块不一致" }
                val expectedDimension = vectors.firstOrNull()?.size ?: completed.firstOrNull()?.size
                check(completed.all { it.isNotEmpty() && it.size == expectedDimension }) { "Embedding 向量维度不一致" }
                checkpoints.save(workspace, file, identity, range.first, batch, completed)
                completed
            }
            val expectedDimension = vectors.firstOrNull()?.size ?: result.firstOrNull()?.size
            check(result.all { it.size == expectedDimension }) { "Embedding 向量维度不一致" }
            vectors.addAll(result)
            // 本批已成功：清除重试期间的错误，避免界面在恢复后仍显示“重试中”。
            lastError = null
            onProgress(EmbeddingBatchProgress(vectors.size, chunks.size))
        }
        vectors
    }
}
