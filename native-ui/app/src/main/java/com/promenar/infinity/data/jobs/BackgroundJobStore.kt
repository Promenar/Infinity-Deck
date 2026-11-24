package com.promenar.infinity.data.jobs

import com.promenar.infinity.domain.jobs.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackgroundJobRecord(
    val id: String,
    val sessionId: String,
    val workspaceRootUuid: String,
    val runner: String,
    val summary: String,
    val state: String = "RUNNING",
    val exitCode: Int? = null,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val maxRuntimeMinutes: Int,
    val onExit: String,
    val logWorkspacePath: String?,
    val runDirectory: String,
    val workspaceDirectory: String? = null,
    val termuxDirectory: String? = null,
    val termuxPid: Int? = null,
    val guidance: String? = null,
    val isolatedWorkspace: Boolean = false,
    /** 无法确认旧writer已停止时保留原日志目录项，避免unlink后丢失追加输出。 */
    val logMigrationDeferred: Boolean = false,
    /** 让出任务在原工作区直接运行，结束后异步对账，不执行快照合入。 */
    val directWorkspace: Boolean = false,
    val commandResult: kotlinx.serialization.json.JsonObject? = null,
    /** SSH 任务的主机 ID；本地任务与旧记录为 null。 */
    val remoteHost: String? = null,
    val transfer: SftpTransferRecord? = null,
    /** 仅记录执行时引用的槽位名，供进程恢复时明确报告缺失。 */
    val secretNames: List<String> = emptyList(),
    /** 智能体为命令写的短标题；旧记录与未填写时为 null。 */
    val title: String? = null,
    /** 最近一次安全输出事件的时间；旧记录从日志时间恢复。 */
    val lastOutputAtMillis: Long? = null,
    val toolResult: com.promenar.infinity.data.model.ToolResult? = null,
    val logReclaimed: Boolean = false,
) {
    /** 未知执行器按 SHELL 展示，持久化原值保留，不据此启动新命令。 */
    val runnerKind: BackgroundJobRunner get() = BackgroundJobRunner.entries.firstOrNull { it.name == runner } ?: BackgroundJobRunner.SHELL
    fun job() = BackgroundJob(id,sessionId,runnerKind,summary,BackgroundJobState.valueOf(state),exitCode,startedAtMillis,endedAtMillis,maxRuntimeMinutes,BackgroundJobExitAction.fromWire(onExit) ?: BackgroundJobExitAction.NONE,BackgroundJobLogs.reference(id),"job:$id",remoteHost,transfer?.value(),title)
    val running: Boolean get() = state == "RUNNING"
}

/** 文件提交完成才更新内存；损坏文件保留并拒绝启动，避免遗失进程归属。 */
class BackgroundJobStore(private val root: File) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var records: List<BackgroundJobRecord>? = null
    private var pendingCleanup: Set<String>? = null

    private fun load(): List<BackgroundJobRecord> = records ?: File(root,"records.json").let { file ->
        (if(file.exists()) json.decodeFromString<List<BackgroundJobRecord>>(file.readText()) else emptyList()).also { records=it }
    }
    private fun save(next: List<BackgroundJobRecord>) {
        writeAtomically("records.json", json.encodeToString(next))
        records = next
    }

    private fun loadPendingCleanup(): Set<String> = pendingCleanup ?: File(root, "pending-cleanup.json").let { file ->
        (if (file.exists()) json.decodeFromString<Set<String>>(file.readText()) else emptySet()).also { pendingCleanup = it }
    }

    private fun savePendingCleanup(ids: Set<String>) {
        writeAtomically("pending-cleanup.json", json.encodeToString(ids))
        pendingCleanup = ids
    }

    private fun writeAtomically(name: String, content: String) {
        check(root.isDirectory || root.mkdirs()) { "无法创建后台任务记录目录" }
        val temp = File(root, "$name.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), File(root, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
    private suspend fun <T> locked(block: ()->T):T=withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { block() } }
    suspend fun updateIfPresent(id:String, transform:(BackgroundJobRecord)->BackgroundJobRecord):BackgroundJobRecord? = locked {
        val values=load()
        val old=values.find{it.id==id} ?: return@locked null
        val updated=transform(old)
        require(updated.id==old.id && updated.sessionId==old.sessionId)
        if (updated != old) save(values.map { if (it.id == id) updated else it })
        updated
    }

    suspend fun clearFinished(sessionId: String, excludedIds: Set<String> = emptySet()): List<BackgroundJobRecord> =
        clearFinishedWithSkipped(sessionId, excludedIds).first

    internal suspend fun clearFinishedWithSkipped(
        sessionId: String,
        excludedIds: Set<String>,
    ): Pair<List<BackgroundJobRecord>, Int> = locked {
        val (removed, retained) = load().partition { it.sessionId == sessionId && !it.running && it.id !in excludedIds }
        val skipped = retained.count { it.sessionId == sessionId && !it.running && it.id in excludedIds }
        if (removed.isNotEmpty()) {
            // 先记录清理意图。恢复时只执行在 records.json 中已经提交移除的任务。
            savePendingCleanup(loadPendingCleanup() + removed.map { it.id })
            save(retained)
        }
        removed to skipped
    }

    internal suspend fun restorePendingCleanup(): Set<String> = locked {
        val pending = loadPendingCleanup()
        if (pending.isEmpty()) return@locked pending
        val committed = pending - load().map { it.id }.toSet()
        if (committed != pending) savePendingCleanup(committed)
        committed
    }

    internal suspend fun acknowledgeCleanup(ids: Set<String>) = locked {
        val pending = loadPendingCleanup()
        val remaining = pending - ids
        if (remaining != pending) savePendingCleanup(remaining)
    }

    suspend fun markLogsReclaimed(ids: Set<String>) = locked {
        val values = load()
        val next = values.map { record ->
            if (!record.running && !record.logReclaimed && record.id in ids) record.copy(logReclaimed = true) else record
        }
        if (next != values) save(next)
    }

    suspend fun all():List<BackgroundJobRecord> = locked { load().toList() }
    suspend fun put(record:BackgroundJobRecord) = locked { save(load().filterNot { it.id==record.id }+record) }
    suspend fun deleteSession(sessionId:String) = locked { save(load().filterNot{it.sessionId==sessionId}) }
}

/** 独立持久化 DTO，领域模型保持既定形状；旧 JSON 缺少 transfer 时为 null。 */
@Serializable
data class SftpTransferRecord(
    val direction: String,
    val localScope: String,
    val localPath: String,
    val remotePath: String,
    val totalBytes: Long? = null,
    val transferredBytes: Long = 0,
    val bytesPerSecond: Long? = null,
    val sha256: String? = null,
    val failure: String? = null,
    val remoteTemporaryPath: String? = null,
    val remoteTemporaryIdentity: String? = null,
) {
    fun value() = SftpTransfer(SftpTransferDirection.valueOf(direction), localScope, localPath, remotePath,
        totalBytes, transferredBytes, bytesPerSecond, sha256, failure?.let(SftpTransferFailure::valueOf))
    companion object {
        fun from(value: SftpTransfer) = SftpTransferRecord(value.direction.name, value.localScope, value.localPath,
            value.remotePath, value.totalBytes, value.transferredBytes, value.bytesPerSecond, value.sha256, value.failure?.name)
    }
}
