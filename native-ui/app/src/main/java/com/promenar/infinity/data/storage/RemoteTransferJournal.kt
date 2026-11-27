package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.RemoteTransferState
import com.promenar.infinity.domain.storage.RemoteTransferTask
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RemoteTransferJournal(noBackupFilesDir: File) {
    internal val directory = File(noBackupFilesDir, "remote-transfers")
    private val journal = File(directory, "journal.json")

    @Serializable
    internal data class Source(val size: Long, val modifiedAt: Long, val sha256: String? = null)

    @Serializable
    internal data class Record(
        val task: RemoteTransferTask,
        val localSourcePath: String? = null,
        val source: Source? = null,
        val hostIdentity: String? = null,
        val mountRoot: String? = null,
        val ownsTemporary: Boolean = false,
        val creatingTemporary: Boolean = false,
        val cleanupPending: Boolean = false,
        val verifiedSha256: String? = null,
        val verificationMethod: String? = null,
        val hidden: Boolean = false,
    )

    @Serializable
    private data class Snapshot(val version: Int = 1, val records: List<Record>)

    internal fun load(): List<Record> {
        if (!journal.exists()) return emptyList()
        val saved = Json.decodeFromString<Snapshot>(journal.readText())
        require(saved.version == 1)
        require(saved.records.map { it.task.id }.distinct().size == saved.records.size)
        val recovered = saved.records.map { record ->
            val task = record.task
            when (task.state) {
                RemoteTransferState.COMMITTING -> record.copy(task = task.copy(
                    state = RemoteTransferState.RECONCILING, bytesPerSecond = 0, etaSeconds = null,
                ))
                RemoteTransferState.CANCELLING -> record.copy(task = task.copy(
                    state = RemoteTransferState.CANCELLED, canResume = false, bytesPerSecond = 0, etaSeconds = null,
                ), cleanupPending = record.ownsTemporary || record.creatingTemporary)
                RemoteTransferState.QUEUED, RemoteTransferState.PREFLIGHT, RemoteTransferState.TRANSFERRING,
                RemoteTransferState.VERIFYING, RemoteTransferState.PAUSING -> record.copy(task = task.copy(
                    state = RemoteTransferState.PAUSED, canResume = true, bytesPerSecond = 0, etaSeconds = null,
                    errorCode = "PROCESS_INTERRUPTED", errorMessage = "进程中断，续传时将重新校验检查点",
                ))
                else -> record
            }
        }
        if (recovered != saved.records) save(recovered)
        return recovered
    }

    internal fun save(records: Collection<Record>) {
        Files.createDirectories(directory.toPath())
        val temporary = File(directory, "journal.tmp")
        FileOutputStream(temporary).use {
            it.write(Json.encodeToString(Snapshot(records = records.toList())).toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
