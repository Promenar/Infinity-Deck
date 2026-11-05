package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class BackupGraphVectorPolicyTest {
    private fun row(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `图谱按所有者分层证据按来源分层`() {
        val tables = mapOf(
            "sessions" to listOf(row("""{"id":"ks","agent_id":"__system__","workspace_root_uuid":"kr"}"""), row("""{"id":"s","agent_id":"agent"}""")),
            "workspace_files" to listOf(row("""{"uuid":"doc","workspace_root_uuid":"kr"}""")),
            "kg_collections" to listOf(row("""{"id":"work:doc","kind":"work","root_ref":"doc"}""")),
        )
        val index = BackupLayerPolicy.Index(tables)
        assertThat(index.layer("kg_nodes", row("""{"collection_id":"work:doc"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("kg_evidence", row("""{"source_type":"jit","doc_id":"doc","session_id":"s"}"""))).isEqualTo(BackupLayer.SESSIONS)
        assertThat(index.layer("kg_evidence", row("""{"source_type":"legacy"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("vectors", row("""{"doc_id":"doc","session_id":"s"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
    }

    @Test
    fun `混合模型或维度的文件不保留索引完成标记`() {
        val files = listOf(row("""{"uuid":"doc","hash":"h","vectorized_at":3,"kg_extracted_at":4}"""))
        val good = row("""{"id":"good","doc_id":"doc","embedding":"AAAAAQ==","metadata":"{\"type\":\"document\",\"embeddingSpaceKey\":\"space\"}","stale":0}""")
        val wrong = row("""{"id":"wrong","doc_id":"doc","embedding":"AAAAAQAAAAE=","metadata":"{\"type\":\"document\",\"embeddingSpaceKey\":\"other\"}","stale":0}""")
        val payload = DatabaseBackupPayload(16, mapOf("workspace_files" to files, "vectors" to listOf(good, wrong)))
        val prepared = prepareRestoredGraphAndVectors(payload, payload, null, 16, BackupEmbeddingMetadata("model", "space", 1), "model", "space")
        assertThat(prepared.tables.getValue("vectors").map { it.getValue("stale").jsonPrimitive.content }).containsExactly("0", "2").inOrder()
        assertThat(prepared.tables.getValue("workspace_files").single().getValue("vectorized_at")).isEqualTo(JsonNull)
        val dimensionsChanged = prepareRestoredGraphAndVectors(payload, payload, null, 16, BackupEmbeddingMetadata("model", "space", 1), "model", "space", 2)
        assertThat(dimensionsChanged.tables.getValue("vectors").map { it.getValue("stale").jsonPrimitive.content }).containsExactly("2", "2")
    }

    @Test
    fun `文件内容摘要变化使恢复向量失效`() {
        val file = row("""{"uuid":"doc","hash":"new-hash","vectorized_at":3,"kg_extracted_at":null}""")
        val vector = row("""{"id":"v","doc_id":"doc","embedding":"AAAAAQ==","metadata":"{\"type\":\"document\",\"contentHash\":\"old-hash\",\"embeddingSpaceKey\":\"space\"}","stale":0}""")
        val payload = DatabaseBackupPayload(16, mapOf("workspace_files" to listOf(file), "vectors" to listOf(vector)))
        val result = prepareRestoredGraphAndVectors(payload, payload, null, 16, BackupEmbeddingMetadata("model", "space", 1), "model", "space")
        assertThat(result.tables.getValue("vectors").single().getValue("stale").jsonPrimitive.content).isEqualTo("2")
        assertThat(result.tables.getValue("workspace_files").single().getValue("vectorized_at")).isEqualTo(JsonNull)
    }

    @Test
    fun `零散历史文档向量保留字节并解除未恢复会话引用`() {
        val vector = row("""{"id":"v","doc_id":"history","session_id":"missing","embedding":"AAAAAQ==","metadata":"{\"type\":\"document\",\"embeddingSpaceKey\":\"space\"}","stale":0}""")
        val payload = DatabaseBackupPayload(16, mapOf("vectors" to listOf(vector)))
        val result = prepareRestoredGraphAndVectors(payload, payload, null, 16, BackupEmbeddingMetadata("model", "space", 1), "model", "space")
        val restored = result.tables.getValue("vectors").single()
        assertThat(restored.getValue("embedding").jsonPrimitive.content).isEqualTo("AAAAAQ==")
        assertThat(restored.getValue("session_id")).isEqualTo(JsonNull)
        assertThat(restored.getValue("stale").jsonPrimitive.content).isEqualTo("0")
    }
}
