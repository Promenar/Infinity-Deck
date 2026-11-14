package com.promenar.infinity.recording

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.domain.repository.WriteResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.promenar.infinity.infra.util.Sha256Utils

internal data class RecordingDocument(val path: String, val hash: String)

internal class RecordingLibrary(private val app: InfinityApplication) {
    suspend fun publish(session: RecordingSession, prepare: suspend (base: String?, target: String) -> Unit): RecordingDocument {
        check(withTimeoutOrNull(15_000) { app.startupState.first { it == BackupStartupState.Ready } } != null)
        val root = app.globalKnowledgeWorkspaceProvisioner.ensureRoot()
        val repository = app.workspaceRepository
        val content = recordingMarkdown(session)
        val bytes = content.toByteArray()
        val hash = Sha256Utils.hash(bytes)
        val existing = repository.getByUuid(root.uuid, session.id)
        if (existing != null) {
            if (existing.hash == hash) {
                repository.confirmCreatedEntry(root.uuid, existing.uuid)
                return RecordingDocument(existing.materializedPath, existing.hash)
            }
            check(existing.hash == session.pendingDocumentHash || existing.hash == session.documentHash) { "资料文档版本冲突" }
            prepare(existing.hash, hash)
            val result = app.fileOperationRepository.writeFileAtomicExact(
                root.uuid, existing.uuid, content, "recording", existing.hash,
            )
            check(result is WriteResult.Success) { "资料文档版本冲突" }
            return RecordingDocument(existing.materializedPath, result.newHash)
        }

        val title = session.title.map { if (it == '/' || it.code == 92 || it.isISOControl()) ' ' else it }
            .joinToString("").trim().take(70).ifBlank { "Recording" }
        val filename = "$title-${session.id.take(8)}.md"
        prepare(null, hash)
        val entry = repository.createFileInWorkspaceStreaming(
            workspaceRootUuid = root.uuid,
            uuid = session.id,
            name = filename,
            mimeType = LOCAL_RECORDING_MIME,
            parentUuid = root.uuid,
            materializedPath = "/$filename",
            maxBytes = bytes.size.toLong(),
            writer = { it.write(bytes) },
        )
        repository.confirmCreatedEntry(root.uuid, entry.uuid)
        return RecordingDocument(entry.materializedPath, entry.hash)
    }
}
