package com.promenar.infinity.data.document.external

import com.promenar.infinity.data.document.DocumentTextDecoding
import com.promenar.infinity.data.document.DocumentTokenEstimator
import com.promenar.infinity.data.model.MessageDocumentAttachment
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 外部应用交来的一份文档：名称、大小与可读入口；读取在 IO 线程进行。 */
class ExternalDocumentInput(
    val displayName: String?,
    val declaredSizeBytes: Long?,
    val open: () -> InputStream,
)

/**
 * 暂存后的外部文档。之后的显示、另存为、存入存储空间与发送都只用 [file]，来源应用收回读取授权也不受影响。
 * [text] 为 null 表示超过 [ExternalDocumentStore.RENDER_LIMIT_BYTES]，只提供文件操作，不在应用内显示正文。
 */
data class ExternalDocument(
    val id: String,
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val text: String?,
)

enum class ExternalDocumentFailure { TooLarge, Empty, Unreadable, NotText }

sealed interface ExternalDocumentResult {
    data class Ready(val document: ExternalDocument) : ExternalDocumentResult
    data class Failed(val reason: ExternalDocumentFailure) : ExternalDocumentResult
}

/** 外部文档的私有暂存区：每份文档一个目录，目录名为随机 id，内有一个以原名命名的副本。 */
class ExternalDocumentStore(
    private val root: File,
    private val maxBytes: Long = MAX_BYTES,
    private val renderLimitBytes: Long = RENDER_LIMIT_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(renderLimitBytes in 1..maxBytes) { "渲染上限必须在 1 与暂存上限之间" }
    }

    suspend fun stage(input: () -> ExternalDocumentInput): ExternalDocumentResult = withContext(io) {
        pruneExpired()
        val source = try {
            input()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable)
        }
        val declared = source.declaredSizeBytes
        if (declared != null && declared > maxBytes) return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.TooLarge)
        if (declared == 0L) return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.Empty)
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        val target = File(directory, safeName(source.displayName))
        val copied = try {
            if (!directory.mkdirs()) throw IOException("无法创建暂存目录")
            source.open().use { input -> copyBounded(input, target) }
        } catch (cancelled: CancellationException) {
            directory.deleteRecursively()
            throw cancelled
        } catch (_: TooLargeException) {
            directory.deleteRecursively()
            return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.TooLarge)
        } catch (_: Exception) {
            directory.deleteRecursively()
            return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable)
        }
        if (copied == 0L) {
            directory.deleteRecursively()
            return@withContext ExternalDocumentResult.Failed(ExternalDocumentFailure.Empty)
        }
        read(id, target).also { if (it is ExternalDocumentResult.Failed) directory.deleteRecursively() }
    }

    /** 进程重建后按 id 取回暂存副本；id 不合法或副本已不在时返回 null。 */
    suspend fun restore(id: String): ExternalDocumentResult? = withContext(io) {
        val file = stagedFile(id) ?: return@withContext null
        read(id, file)
    }

    fun discard(id: String) {
        if (isValidId(id)) File(root, id).deleteRecursively()
    }

    /** 删除超过保留期的暂存目录。 */
    fun pruneExpired() {
        val expiry = now() - RETENTION_MS
        root.listFiles()?.forEach { directory ->
            if (directory.isDirectory && isValidId(directory.name) && directory.lastModified() < expiry) directory.deleteRecursively()
        }
    }

    private fun stagedFile(id: String): File? {
        if (!isValidId(id)) return null
        return File(root, id).listFiles()?.singleOrNull { it.isFile }
    }

    private fun read(id: String, file: File): ExternalDocumentResult {
        return try {
            val size = file.length()
            if (size == 0L) return ExternalDocumentResult.Failed(ExternalDocumentFailure.Empty)
            if (size > maxBytes) return ExternalDocumentResult.Failed(ExternalDocumentFailure.TooLarge)
            if (size <= renderLimitBytes) {
                val bytes = file.readBytes()
                val text = try {
                    DocumentTextDecoding.decode(bytes)
                } catch (_: CharacterCodingException) {
                    return ExternalDocumentResult.Failed(ExternalDocumentFailure.NotText)
                }
                if (DocumentTextDecoding.hasBinaryControlCharacters(text)) return ExternalDocumentResult.Failed(ExternalDocumentFailure.NotText)
                ExternalDocumentResult.Ready(ExternalDocument(id, file.name, file, size, DocumentTextDecoding.sha256(bytes), text))
            } else {
                // 不渲染的大文件只抽查开头，排除二进制内容；哈希按流计算。
                val sample = file.inputStream().use { input -> readSample(input) }
                if (DocumentTextDecoding.hasBinaryControlCharacters(DocumentTextDecoding.decodeLenient(sample))) {
                    return ExternalDocumentResult.Failed(ExternalDocumentFailure.NotText)
                }
                ExternalDocumentResult.Ready(ExternalDocument(id, file.name, file, size, streamSha256(file), null))
            }
        } catch (_: IOException) {
            ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable)
        } catch (_: SecurityException) {
            ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable)
        }
    }

    private fun copyBounded(input: InputStream, target: File): Long {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (total > maxBytes - count) throw TooLargeException()
                total += count
                output.write(buffer, 0, count)
            }
        }
        return total
    }

    private fun readSample(input: InputStream): ByteArray {
        val buffer = ByteArray(SAMPLE_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val count = input.read(buffer, filled, buffer.size - filled)
            if (count < 0) break
            filled += count
        }
        return buffer.copyOf(filled)
    }

    private fun streamSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) >= 0) Unit
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class TooLargeException : IOException()

    companion object {
        /** 暂存副本上限：超过时不打开。 */
        const val MAX_BYTES = 32L * 1024L * 1024L
        /** 应用内显示正文的上限；更大的文件只提供另存为、存入存储空间与发送（引用）。 */
        const val RENDER_LIMIT_BYTES = 16L * 1024L * 1024L
        private const val SAMPLE_BYTES = 64 * 1024
        private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** 去掉路径分隔与控制字符，最长 120 字符，并保证以 Markdown 扩展名结尾。 */
        fun safeName(displayName: String?): String {
            val base = displayName?.substringAfterLast('/')?.replace(UNSAFE_NAME_CHARS, "_")?.trim()
                ?.trimStart('.')?.take(120)?.takeIf(String::isNotBlank) ?: "document"
            return ExternalMarkdown.ensureMarkdownName(base)
        }
    }
}

/** 作为会话全文附件带入；只在正文已读入时可用。 */
fun ExternalDocument.toAttachment(id: String = UUID.randomUUID().toString()): MessageDocumentAttachment {
    val content = requireNotNull(text) { "正文未读入的文档只能以引用发送" }
    return MessageDocumentAttachment(
        id = id,
        name = displayName,
        mimeType = ExternalMarkdown.MARKDOWN_MIME,
        content = content,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        estimatedTokens = DocumentTokenEstimator.estimate(content),
    )
}
