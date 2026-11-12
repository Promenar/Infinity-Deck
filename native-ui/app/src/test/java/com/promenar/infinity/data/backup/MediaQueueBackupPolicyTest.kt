package com.promenar.infinity.data.backup

import com.promenar.infinity.data.mediagen.queue.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaQueueBackupPolicyTest {
    private fun record(state: MediaTaskState) = PersistentMediaTask(
        MediaTask("task", MediaGenerationRequest("channel", GenerationMode.TEXT, "树林", emptyList(), "project/shot"), null,
            state = state, remoteId = "remote-secret-link"),
        MediaChannel("channel", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT))),
        "binding", "provider", "model", 1, pollAttempt = 4, nextPollAt = 500, remoteSucceeded = true,
        remoteOutputs = listOf(RemoteOutput("https://host/output?token=signed-url", InputKind.VIDEO)), usage = mapOf("tokens" to 100),
    )
    private fun row(record: PersistentMediaTask) = JsonObject(mapOf("id" to JsonPrimitive(record.task.id), "source_ref" to JsonPrimitive(record.task.request.sourceRef),
        "state" to JsonPrimitive(record.task.state.name), "updated_at" to JsonPrimitive(record.updatedAt), "record_json" to JsonPrimitive(MediaTaskCodec.encode(record))))

    @Test fun `历史备份清除远端ID地址与轮询状态并禁用调度`() {
        val original = row(record(MediaTaskState.DOWNLOAD_FAILED))
        val clean = MediaQueueBackupPolicy.snapshotRow("media_generation_tasks", original)
        val decoded = MediaTaskCodec.decode(clean.getValue("record_json").jsonPrimitive.content)
        assertNull(decoded.task.remoteId)
        assertTrue(decoded.remoteOutputs.isEmpty())
        assertFalse(decoded.remoteSucceeded)
        assertTrue(decoded.historyOnly)
        assertEquals(0L, decoded.nextPollAt)
        assertFalse(clean.toString().contains("signed-url"))
        MediaQueueBackupPolicy.validateRows(mapOf("media_generation_tasks" to listOf(clean)))
    }
    @Test fun `恢复拒绝可调度任务与远端状态`() {
        assertThrows(BackupValidationException::class.java) {
            MediaQueueBackupPolicy.validateRows(mapOf("media_generation_tasks" to listOf(row(record(MediaTaskState.RUNNING)))))
        }
        assertThrows(BackupValidationException::class.java) {
            MediaQueueBackupPolicy.validateRows(mapOf("media_generation_tasks" to listOf(row(record(MediaTaskState.COMPLETED)))))
        }
    }
    @Test fun `恢复同ID历史时保留本机远端状态与未知提交记录`() {
        val active = row(record(MediaTaskState.RUNNING))
        val historical = MediaQueueBackupPolicy.snapshotRow("media_generation_tasks", row(record(MediaTaskState.COMPLETED)))
        assertEquals(listOf(active), MediaQueueBackupPolicy.mergeRows("media_generation_tasks", listOf(active), listOf(historical)))
        val unknown = row(record(MediaTaskState.RESULT_UNKNOWN))
        assertEquals(listOf(unknown), MediaQueueBackupPolicy.mergeRows("media_generation_tasks", listOf(unknown), emptyList()))
    }
    @Test fun `费用原值包含未知并归入核心层`() {
        val usage = JsonObject(mapOf("amount" to JsonNull, "currency" to JsonNull))
        assertEquals(usage, MediaQueueBackupPolicy.snapshotRow("media_generation_usage", usage))
        assertEquals(BackupLayer.CORE, BackupLayerPolicy.tableLayer("media_generation_usage"))
        assertEquals(BackupLayer.CORE, BackupLayerPolicy.tableLayer("media_generation_tasks"))
    }
}
