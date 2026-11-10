package com.promenar.infinity.data.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.promenar.infinity.data.model.MessageDocumentAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.UUID

class FullContextDocumentReader(
    private val contentResolver: ContentResolver,
    private val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
) {
    init {
        require(maxDocumentBytes > 0) { "文档大小上限必须为正数" }
    }

    suspend fun read(uri: Uri): DocumentReadResult = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(uri) ?: return@withContext DocumentReadResult.Rejected(
            DocumentReadRejectReason.ReadFailed,
        )
        val declaredMimeType = try {
            contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IllegalArgumentException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        }
        val mimeType = resolveMimeType(declaredMimeType, metadata.displayName)
            ?: return@withContext DocumentReadResult.Rejected(
                DocumentReadRejectReason.UnsupportedDocument,
            )
        if (metadata.sizeBytes != null && metadata.sizeBytes <= 0L) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)
        }
        if (metadata.sizeBytes != null && metadata.sizeBytes > maxDocumentBytes) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.DocumentTooLarge)
        }

        val bytes = try {
            readBoundedBytes(uri)
        } catch (_: DocumentTooLargeException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.DocumentTooLarge)
        } catch (_: SecurityException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IOException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IllegalArgumentException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        }
        if (bytes.isEmpty()) return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)

        val content = try {
            DocumentTextDecoding.decode(bytes)
        } catch (_: CharacterCodingException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.InvalidTextEncoding)
        }
        if (content.isEmpty()) return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)
        if (DocumentTextDecoding.hasBinaryControlCharacters(content)) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.BinaryContent)
        }

        DocumentReadResult.Success(
            MessageDocumentAttachment(
                id = UUID.randomUUID().toString(),
                name = metadata.displayName ?: uri.lastPathSegment ?: "document.txt",
                mimeType = mimeType,
                content = content,
                sizeBytes = bytes.size.toLong(),
                sha256 = DocumentTextDecoding.sha256(bytes),
                estimatedTokens = DocumentTokenEstimator.estimate(content),
            ),
        )
    }

    private fun queryMetadata(uri: Uri): DocumentMetadata? = try {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it)?.trim()?.takeIf(String::isNotEmpty) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it) }
            }
        }
        DocumentMetadata(displayName, sizeBytes)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readBoundedBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选文档")
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size().toLong() > maxDocumentBytes - read) {
                    throw DocumentTooLargeException()
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun resolveMimeType(declaredMimeType: String?, displayName: String?): String? {
        val extensionMimeType = when {
            displayName?.endsWith(".txt", ignoreCase = true) == true -> "text/plain"
            displayName?.endsWith(".md", ignoreCase = true) == true -> "text/markdown"
            displayName?.endsWith(".markdown", ignoreCase = true) == true -> "text/markdown"
            else -> null
        }
        if (displayName != null) return extensionMimeType
        val declared = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (declared in SUPPORTED_MIME_TYPES) return declared
        return null
    }

    private data class DocumentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private class DocumentTooLargeException : IOException()

    companion object {
        const val DEFAULT_MAX_DOCUMENT_BYTES = 1024L * 1024L
        private val SUPPORTED_MIME_TYPES = setOf("text/plain", "text/markdown")
    }
}

sealed interface DocumentReadResult {
    data class Success(val attachment: MessageDocumentAttachment) : DocumentReadResult
    data class Rejected(val reason: DocumentReadRejectReason) : DocumentReadResult
}

enum class DocumentReadRejectReason {
    UnsupportedDocument,
    EmptyDocument,
    DocumentTooLarge,
    BinaryContent,
    InvalidTextEncoding,
    ReadFailed,
}

object DocumentTokenEstimator {
    fun estimate(content: String): Int =
        ConservativeContextTokenEstimator.estimate(content)
}

/** 一字节按一个 token 计入，作为未知 tokenizer 下不会低估文本的保守门禁上界。 */
object ConservativeContextTokenEstimator {
    fun estimate(content: String): Int = content.toByteArray(Charsets.UTF_8).size

    /** 不读入正文时按 UTF-8 字节数估算，结果不超过 Int 范围。 */
    fun estimateBytes(sizeBytes: Long): Int = sizeBytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
