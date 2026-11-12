package com.promenar.infinity.data.backup

import com.promenar.infinity.data.mediagen.queue.MediaTaskCodec
import com.promenar.infinity.domain.mediagen.MediaTaskState
import kotlinx.serialization.json.*

/** 可移植备份只携带静态历史；进行中的任务保留在本机 Room 中恢复。 */
internal object MediaQueueBackupPolicy {
    val tables = setOf("media_generation_tasks", "media_generation_usage")
    private val historyStates = setOf(MediaTaskState.COMPLETED, MediaTaskState.FAILED, MediaTaskState.DOWNLOAD_FAILED, MediaTaskState.CANCELED)
    fun selection(table: String): String = if (table == "media_generation_tasks")
        " WHERE state IN ('COMPLETED','FAILED','DOWNLOAD_FAILED','CANCELED')" else ""

    fun snapshotRow(table: String, row: JsonObject): JsonObject {
        if (table != "media_generation_tasks") return row
        val record = MediaTaskCodec.decode(row.getValue("record_json").jsonPrimitive.content)
        require(record.task.state in historyStates)
        val error = record.task.error?.takeIf { record.task.state == MediaTaskState.CANCELED && it == "升级后取消" }
        val clean = record.copy(task = record.task.copy(remoteId = null, error = error), pollAttempt = 0,
            consecutivePollErrors = 0, nextPollAt = 0, remoteSucceeded = false, remoteOutputs = emptyList(), usage = emptyMap(), historyOnly = true)
        return JsonObject(row + ("record_json" to JsonPrimitive(MediaTaskCodec.encode(clean))))
    }

    fun mergeRows(table: String, local: List<JsonObject>, imported: List<JsonObject>): List<JsonObject> {
        require(table in tables)
        val key = if (table == "media_generation_tasks") "id" else "task_id"
        return (imported + local).associateBy { it.getValue(key).jsonPrimitive.content }.values.toList()
    }

    fun validateRows(tables: Map<String, List<JsonObject>>) {
        tables["media_generation_tasks"].orEmpty().forEach { row ->
            try {
                val record = MediaTaskCodec.decode(row.getValue("record_json").jsonPrimitive.content)
                require(record.historyOnly && record.task.state in historyStates && record.task.remoteId == null &&
                    record.remoteOutputs.isEmpty() && !record.remoteSucceeded && record.usage.isEmpty() && record.nextPollAt == 0L &&
                    record.pollAttempt == 0 && record.consecutivePollErrors == 0)
                require(row.getValue("id").jsonPrimitive.content == record.task.id && row.getValue("source_ref").jsonPrimitive.content == record.task.request.sourceRef &&
                    row.getValue("state").jsonPrimitive.content == record.task.state.name && row.getValue("updated_at").jsonPrimitive.long == record.updatedAt)
            } catch (_: Exception) { throw BackupValidationException("媒体任务备份包含无效历史或远端运行状态") }
        }
    }
}
