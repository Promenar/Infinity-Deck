package com.promenar.infinity.data.document.external

import com.promenar.infinity.data.document.ComposerDocumentInbox
import com.promenar.infinity.data.document.ConservativeContextTokenEstimator
import com.promenar.infinity.data.document.DocumentWorkspaceBytesSaveResult
import com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure
import com.promenar.infinity.data.document.DocumentWorkspaceSaver
import com.promenar.infinity.data.document.FullContextDocumentReader
import com.promenar.infinity.data.model.MessageDocumentAttachment
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 带入会话的方式：附件把全文放进下一条消息；引用把文件存进会话文件夹，消息只带路径。 */
enum class ExternalSendMode { Attachment, Reference }

object ExternalSendPolicy {
    /** 与会话全文附件的上限一致；超过时只能引用。 */
    const val ATTACHMENT_LIMIT_BYTES = FullContextDocumentReader.DEFAULT_MAX_DOCUMENT_BYTES

    fun attachmentAllowed(document: ExternalDocument): Boolean =
        document.text != null && document.sizeBytes <= ATTACHMENT_LIMIT_BYTES

    fun effectiveMode(requested: ExternalSendMode, document: ExternalDocument): ExternalSendMode =
        if (requested == ExternalSendMode.Attachment && !attachmentAllowed(document)) ExternalSendMode.Reference else requested
}

sealed interface ExternalSendResult {
    data class Sent(val sessionId: String, val mode: ExternalSendMode) : ExternalSendResult
    data class Failed(val reason: DocumentWorkspaceSaveFailure) : ExternalSendResult
}

/**
 * 把外部文档交给某个会话的输入框，由用户在会话里写完再发。
 * 附件与引用都成为草稿文档附件，发送时沿用会话附件的上下文预算与工作区规则。
 */
class ExternalDocumentSender(
    private val inbox: ComposerDocumentInbox,
    private val saver: DocumentWorkspaceSaver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun send(document: ExternalDocument, sessionId: String, requested: ExternalSendMode): ExternalSendResult {
        val mode = ExternalSendPolicy.effectiveMode(requested, document)
        val attachment = when (mode) {
            ExternalSendMode.Attachment -> document.toAttachment()
            ExternalSendMode.Reference -> when (val saved = saveReference(document, sessionId)) {
                is ReferenceOutcome.Saved -> saved.attachment
                is ReferenceOutcome.Failed -> return ExternalSendResult.Failed(saved.reason)
            }
        }
        inbox.offer(sessionId, attachment)
        return ExternalSendResult.Sent(sessionId, mode)
    }

    private sealed interface ReferenceOutcome {
        data class Saved(val attachment: MessageDocumentAttachment) : ReferenceOutcome
        data class Failed(val reason: DocumentWorkspaceSaveFailure) : ReferenceOutcome
    }

    private suspend fun saveReference(document: ExternalDocument, sessionId: String): ReferenceOutcome {
        val bytes = try {
            withContext(io) { document.file.readBytes() }
        } catch (_: IOException) {
            return ReferenceOutcome.Failed(DocumentWorkspaceSaveFailure.SaveFailed)
        }
        val lineCount = lineCount(bytes)
        val tokens = ConservativeContextTokenEstimator.estimateBytes(bytes.size.toLong())
        return when (val saved = saver.saveBytes(
            sessionId = sessionId,
            name = document.displayName,
            mimeType = ExternalMarkdown.MARKDOWN_MIME,
            bytes = bytes,
            lineCount = lineCount,
            estimatedTokens = tokens,
        )) {
            is DocumentWorkspaceBytesSaveResult.Success -> ReferenceOutcome.Saved(
                MessageDocumentAttachment(
                    id = UUID.randomUUID().toString(),
                    name = document.displayName,
                    mimeType = ExternalMarkdown.MARKDOWN_MIME,
                    content = "",
                    sizeBytes = document.sizeBytes,
                    sha256 = document.sha256,
                    estimatedTokens = tokens,
                    workspaceReference = saved.reference,
                    inlineInCurrentTurn = false,
                ),
            )
            is DocumentWorkspaceBytesSaveResult.Failure -> ReferenceOutcome.Failed(saved.reason)
        }
    }

    companion object {
        /** 与 `String.lineSequence().count()` 一致：非空内容的行数为换行符数加一。 */
        fun lineCount(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return 0
            return bytes.count { it == '\n'.code.toByte() } + 1
        }
    }
}
