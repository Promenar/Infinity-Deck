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
    fun `历史向量按维度恢复且不补写空间标记`() {
        val preferences = BackupPreferenceSnapshot(listOf(BackupPreferenceEntry("settings", "preset_embedding_model", "model")), emptySet())
        val file = row("""{"uuid":"doc","hash":"h","vectorized_at":3,"kg_extracted_at":null}""")
        val historical = row("""{"id":"old","doc_id":"doc","file_uuid":"doc","embedding":"AAAAAQ==","metadata":"{\"type\":\"document\"}","stale":0}""")
        val wrongDimension = row("""{"id":"wrong","doc_id":"other","embedding":"AAAAAQAAAAE=","metadata":"{}","stale":0}""")
        val source = DatabaseBackupPayload(16, mapOf("workspace_files" to listOf(file), "vectors" to listOf(historical)))
        val manifest = embeddingMetadata(source, preferences, "space")
        assertThat(manifest?.dimension).isEqualTo(1)
        val payload = source.copy(tables = source.tables + ("vectors" to listOf(historical, wrongDimension)))
        val result = prepareRestoredGraphAndVectors(payload, payload, null, 16, manifest, "model", "space")
        assertThat(result.tables.getValue("vectors").map { it.getValue("stale").jsonPrimitive.content }).containsExactly("0", "2").inOrder()
        assertThat(result.tables.getValue("vectors").first().getValue("metadata")).isEqualTo(historical.getValue("metadata"))
        assertThat(result.tables.getValue("workspace_files").single().getValue("vectorized_at").jsonPrimitive.content).isEqualTo("3")
    }

    @Test
    fun `混合维度依配置选择清单维度否则失效`() {
        val old = row("""{"id":"old","embedding":"AAAAAQ==","metadata":"{}","stale":0}""")
        val marked = row("""{"id":"marked","embedding":"AAAAAQAAAAE=","metadata":"{\"embeddingSpaceKey\":\"space\"}","stale":0}""")
        val payload = DatabaseBackupPayload(16, mapOf("vectors" to listOf(old, marked)))
        val entries = listOf(BackupPreferenceEntry("settings", "preset_embedding_model", "model"))
        val unset = BackupPreferenceSnapshot(entries, emptySet())
        assertThat(embeddingMetadata(payload, unset, "space")).isNull()
        val configured = unset.copy(entries = entries + BackupPreferenceEntry("rag", "embed_dimension", "2"))
        assertThat(embeddingMetadata(payload, configured, "space")?.dimension).isEqualTo(2)
        val noManifest = prepareRestoredGraphAndVectors(payload, payload, null, 16, null, "model", "space")
        assertThat(noManifest.tables.getValue("vectors").map { it.getValue("stale").jsonPrimitive.content }).containsExactly("2", "2")
        val mismatch = prepareRestoredGraphAndVectors(payload, payload, null, 16, BackupEmbeddingMetadata("model", "space", 2), "other", "space")
        assertThat(mismatch.tables.getValue("vectors").map { it.getValue("stale").jsonPrimitive.content }).containsExactly("2", "2")
    }

    @Test
    fun `图谱按所有者分层证据按来源分层`() {
        val tables = mapOf(
            "sessions" to listOf(row("""{"id":"ks","agent_id":"__system__","workspace_root_uuid":"kr"}"""), row("""{"id":"s","agent_id":"agent"}""")),
            "workspace_files" to listOf(row("""{"uuid":"doc","workspace_root_uuid":"kr"}""")),
            "kg_collections" to listOf(row("""{"id":"work:doc","kind":"work","root_ref":"doc"}""")),
            "kg_edges" to listOf(row("""{"id":"edge","collection_id":"work:doc"}""")),
        )
        val index = BackupLayerPolicy.Index(tables)
        assertThat(index.layer("kg_nodes", row("""{"collection_id":"work:doc"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("kg_edge_mentions", row("""{"edge_id":"edge"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("kg_edge_validity", row("""{"collection_id":"work:doc"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("kg_entity_links", row("""{"collection_id":"work:doc"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("kg_evidence", row("""{"source_type":"jit","doc_id":"doc","session_id":"s"}"""))).isEqualTo(BackupLayer.SESSIONS)
        assertThat(index.layer("kg_evidence", row("""{"source_type":"legacy"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
        assertThat(index.layer("vectors", row("""{"doc_id":"doc","session_id":"s"}"""))).isEqualTo(BackupLayer.KNOWLEDGE)
    }

    @Test
    fun `恢复时丢弃与证据来源不一致的出现记录`() {
        val tables = mapOf(
            "workspace_files" to listOf(row("""{"uuid":"d1"}"""), row("""{"uuid":"d2"}""")),
            "kg_collections" to listOf(row("""{"id":"work:p"}""")),
            "kg_collection_members" to listOf(row("""{"doc_id":"d1","collection_id":"work:p"}"""), row("""{"doc_id":"d2","collection_id":"work:p"}""")),
            "kg_nodes" to listOf(row("""{"id":"a","collection_id":"work:p"}"""), row("""{"id":"b","collection_id":"work:p"}""")),
            "kg_edges" to listOf(row("""{"id":"e","source_id":"a","target_id":"b","collection_id":"work:p"}""")),
            "kg_evidence" to listOf(row("""{"id":"p","edge_id":"e","doc_id":"d1","task_id":"t","content_hash":"h"}""")),
            "kg_edge_mentions" to listOf(row("""{"id":"good","edge_id":"e","evidence_id":"p","doc_id":"d1","task_id":"t","content_hash":"h"}"""),
                row("""{"id":"bad","edge_id":"e","evidence_id":"p","doc_id":"d2","task_id":"t","content_hash":"h"}""")),
        )
        val payload = DatabaseBackupPayload(17, tables)
        val restored = prepareRestoredGraphAndVectors(payload, payload, null, 17, null, null, null)
        assertThat(restored.tables.getValue("kg_edge_mentions").map { it.getValue("id").jsonPrimitive.content }).containsExactly("good")
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
