package com.promenar.infinity.data.document

import com.promenar.infinity.data.media.MediaTypeSniffer
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.model.isBoundTo
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.utils.InfinityLogger
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface UserAttachmentWorkspaceStore {
    suspend fun save(
        session: Session,
        images: List<String>,
        documents: List<MessageDocumentAttachment>,
        relativeDirectory: String? = null,
    ): StoredUserAttachments
}

data class StoredUserAttachments(
    val imageReferences: List<WorkspaceDocumentReference?>?,
    val documents: List<MessageDocumentAttachment>,
)

enum class UserAttachmentWorkspaceKind { Image, Document }
enum class UserAttachmentWorkspaceFailure { DecodeFailed, UnsupportedImage, StorageFailed }

data class UserAttachmentWorkspaceDiagnostic(
    val kind: UserAttachmentWorkspaceKind,
    val index: Int,
    val reason: UserAttachmentWorkspaceFailure,
    val storageReason: DocumentWorkspaceSaveFailure? = null,
)

fun interface UserAttachmentWorkspaceDiagnosticSink {
    fun record(diagnostic: UserAttachmentWorkspaceDiagnostic)
}

/** 自动附件沿用工作区新建与来源校验；失败按项回退到发送时的原始输入。 */
class DefaultUserAttachmentWorkspaceStore(
    private val workspace: DocumentWorkspaceSaver,
    private val diagnostics: UserAttachmentWorkspaceDiagnosticSink = UserAttachmentWorkspaceDiagnosticSink {
        InfinityLogger.diagnostic("user_attachment.workspace_store_failed", mapOf(
            "kind" to it.kind.name,
            "index" to it.index,
            "reason" to it.reason.name,
            "storage_reason" to it.storageReason?.name,
        ))
    },
) : UserAttachmentWorkspaceStore {
    private class SessionState {
        val mutex = Mutex()
        val references = linkedMapOf<String, MutableList<WorkspaceDocumentReference>>()

        fun remember(hash: String, reference: WorkspaceDocumentReference) {
            val entries = references.getOrPut(hash) { mutableListOf() }
            entries.removeAll { it.path == reference.path && it.workspaceId == reference.workspaceId && it.isPrivate == reference.isPrivate }
            entries.add(0, reference)
            if (entries.size > 8) entries.removeAt(entries.lastIndex)
            if (references.size > 256) references.remove(references.keys.first())
        }
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()

    override suspend fun save(
        session: Session,
        images: List<String>,
        documents: List<MessageDocumentAttachment>,
        relativeDirectory: String?,
    ): StoredUserAttachments = withContext(Dispatchers.IO) {
        val state = sessions.computeIfAbsent(session.id) { SessionState() }
        state.mutex.withLock {
            // 归档消息中的引用仍是会话已有附件，不随提示词裁剪而丢失去重来源。
            val existing = session.messages.asSequence().flatMap { message ->
                message.userImageReferences.orEmpty().filterNotNull().asSequence() +
                    message.userDocuments.orEmpty().mapNotNull { it.workspaceReference }.asSequence()
            }.distinct().toList()
            val known = existing.filter { it.sha256 != null }.groupBy { requireNotNull(it.sha256) }.toMutableMap()
            val unhashed = existing.filter { it.sha256 == null }
            val imageReferences = images.mapIndexed { index, dataUrl ->
                try {
                    val bytes = decodeDataUrl(dataUrl)
                    val type = MediaTypeSniffer.sniff(bytes.copyOf(minOf(bytes.size, 4096)), null)
                    if (type.kind != MediaKind.IMAGE) {
                        record(UserAttachmentWorkspaceDiagnostic(UserAttachmentWorkspaceKind.Image, index, UserAttachmentWorkspaceFailure.UnsupportedImage))
                        null
                    } else {
                        val hash = workspaceAttachmentSha256(bytes)
                        savePayload(
                            session, state, known[hash].orEmpty() + unhashed, "image-${hash.take(20)}.${MediaTypeSniffer.extension(type.mimeType, null)}",
                            type.mimeType, bytes, 0, 0, relativeDirectory,
                            UserAttachmentWorkspaceKind.Image, index,
                        ).also { reference ->
                            if (reference != null) known[hash] = (known[hash].orEmpty() + reference).distinct()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ImageDecodeException) {
                    record(UserAttachmentWorkspaceDiagnostic(UserAttachmentWorkspaceKind.Image, index, UserAttachmentWorkspaceFailure.DecodeFailed))
                    null
                } catch (failure: Exception) {
                    recordStorageFailure(UserAttachmentWorkspaceKind.Image, index, failure)
                    null
                }
            }.takeIf { images.isNotEmpty() }
            val storedDocuments = documents.mapIndexed { index, document ->
                // 手动转存已明确只保留引用；自动发送不会恢复其正文或内联标记。
                if (document.workspaceReference != null && !document.inlineInCurrentTurn) return@mapIndexed document
                try {
                    val bytes = document.content.toByteArray(Charsets.UTF_8)
                    val hash = workspaceAttachmentSha256(bytes)
                    val reference = savePayload(
                        session, state, listOfNotNull(document.workspaceReference) + known[hash].orEmpty() + unhashed,
                        document.name, document.mimeType, bytes,
                        if (document.content.isEmpty()) 0 else document.content.lineSequence().count(), document.estimatedTokens,
                        relativeDirectory, UserAttachmentWorkspaceKind.Document, index,
                    )
                    if (reference != null) known[hash] = (known[hash].orEmpty() + reference).distinct()
                    document.copy(workspaceReference = reference, inlineInCurrentTurn = reference != null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordStorageFailure(UserAttachmentWorkspaceKind.Document, index, failure)
                    document.copy(workspaceReference = null, inlineInCurrentTurn = false)
                }
            }
            StoredUserAttachments(imageReferences, storedDocuments)
        }
    }

    private suspend fun savePayload(
        session: Session,
        state: SessionState,
        known: List<WorkspaceDocumentReference>,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        lineCount: Int,
        estimatedTokens: Int,
        relativeDirectory: String?,
        kind: UserAttachmentWorkspaceKind,
        index: Int,
    ): WorkspaceDocumentReference? {
        AttachmentWorkspacePaths.name(name)
        relativeDirectory?.let {
            val directory = AttachmentWorkspacePaths.directory(it)
            AttachmentWorkspacePaths.namespace("$directory/$name")
        }
        val hash = workspaceAttachmentSha256(bytes)
        val candidates = (state.references[hash].orEmpty() + known).distinct()
        for (reference in candidates) {
            if ((reference.sha256 != null && reference.sha256 != hash) || !reference.isBoundTo(session)) continue
            val reusable = try {
                workspace.isReusable(session, reference, hash, bytes.size.toLong())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (reusable) {
                return reference.copy(lineCount = lineCount, estimatedTokens = estimatedTokens, sha256 = hash).also { state.remember(hash, it) }
            }
            state.references[hash]?.remove(reference)
        }
        return when (val saved = workspace.saveBytes(
            session.id, name, mimeType, bytes, lineCount, estimatedTokens, relativeDirectory,
        )) {
            is DocumentWorkspaceBytesSaveResult.Success -> {
                if (saved.reference.sha256 == hash && saved.reference.isBoundTo(session)) {
                    saved.reference.also { state.remember(hash, it) }
                } else {
                    record(UserAttachmentWorkspaceDiagnostic(kind, index, UserAttachmentWorkspaceFailure.StorageFailed, DocumentWorkspaceSaveFailure.WorkspaceUnavailable))
                    null
                }
            }
            is DocumentWorkspaceBytesSaveResult.Failure -> {
                record(UserAttachmentWorkspaceDiagnostic(kind, index, UserAttachmentWorkspaceFailure.StorageFailed, saved.reason))
                null
            }
        }
    }

    private fun recordStorageFailure(kind: UserAttachmentWorkspaceKind, index: Int, failure: Exception) {
        val reason = if (failure is ExternalFolderException && failure.code == "INVALID_PATH") {
            DocumentWorkspaceSaveFailure.InvalidPath
        } else DocumentWorkspaceSaveFailure.SaveFailed
        record(UserAttachmentWorkspaceDiagnostic(kind, index, UserAttachmentWorkspaceFailure.StorageFailed, reason))
    }

    private fun record(diagnostic: UserAttachmentWorkspaceDiagnostic) {
        try { diagnostics.record(diagnostic) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private class ImageDecodeException : Exception()

    private fun decodeDataUrl(value: String): ByteArray {
        if (!value.startsWith("data:", ignoreCase = true)) throw ImageDecodeException()
        val separator = value.indexOf(',')
        if (separator < 5) throw ImageDecodeException()
        val header = value.substring(5, separator)
        val payload = value.substring(separator + 1)
        val bytes = try {
            if (header.split(';').drop(1).any { it.equals("base64", ignoreCase = true) }) {
                Base64.getDecoder().decode(payload.filterNot { it in " \t\r\n" })
            } else {
                val output = ByteArrayOutputStream()
                var cursor = 0
                while (cursor < payload.length) {
                    if (payload[cursor] == '%') {
                        if (cursor + 2 >= payload.length) throw ImageDecodeException()
                        val high = payload[cursor + 1].digitToIntOrNull(16) ?: throw ImageDecodeException()
                        val low = payload[cursor + 2].digitToIntOrNull(16) ?: throw ImageDecodeException()
                        output.write((high shl 4) or low)
                        cursor += 3
                    } else {
                        val end = payload.indexOf('%', cursor).takeIf { it >= 0 } ?: payload.length
                        output.write(payload.substring(cursor, end).toByteArray(Charsets.UTF_8))
                        cursor = end
                    }
                }
                output.toByteArray()
            }
        } catch (_: IllegalArgumentException) { throw ImageDecodeException() }
        if (bytes.isEmpty()) throw ImageDecodeException()
        return bytes
    }
}
