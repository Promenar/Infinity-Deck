package com.promenar.infinity.data.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackupLayerPolicyTest {
    private fun row(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
    @Test fun `系统知识库所有者区分资料与会话私有文件且目录登记归核心`() {
        val tables = mapOf("sessions" to listOf(
            row("id" to "knowledge", "agent_id" to "__system__", "workspace_root_uuid" to "kb-root"),
            row("id" to "chat", "agent_id" to "agent", "workspace_root_uuid" to "chat-root")),
            "messages" to listOf(row("id" to "km", "session_id" to "knowledge")))
        val policy = BackupLayerPolicy.Index(tables)
        assertEquals(BackupLayer.KNOWLEDGE, policy.layer("workspace_files", row("workspace_root_uuid" to "kb-root")))
        assertEquals(BackupLayer.WORKSPACE_FILES, policy.layer("workspace_files", row("workspace_root_uuid" to "chat-root")))
        assertEquals(BackupLayer.KNOWLEDGE, policy.layer("attachments", row("message_id" to "km")))
        assertEquals(BackupLayer.CORE, policy.layer("workspaces", row("kind" to "shared")))
        assertEquals(BackupLayer.CORE, policy.layer("agent_rule_revisions", row("id" to "revision")))
    }
}
