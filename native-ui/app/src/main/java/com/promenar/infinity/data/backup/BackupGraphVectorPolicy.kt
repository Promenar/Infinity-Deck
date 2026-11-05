package com.promenar.infinity.data.backup

import com.promenar.infinity.data.rag.documentContentHashFromVectorMetadata
import com.promenar.infinity.data.rag.vectorSpaceFromMetadata
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** 模型标识来自已备份偏好；历史向量按原有检索行为以维度判定兼容性。 */
internal fun embeddingModelId(preferences: BackupPreferenceSnapshot): String? {
    fun value(namespace: String, key: String) = preferences.entries.lastOrNull {
        BackupPreferencePolicy.normalizeNamespace(it.namespace) == namespace && it.key == key
    }?.value?.takeIf(String::isNotBlank)
    return value("settings", "preset_embedding_model") ?: value("provider", "embedding_model")
}

internal fun embeddingConfiguredDimension(preferences: BackupPreferenceSnapshot): Int? = preferences.entries.lastOrNull {
    BackupPreferencePolicy.normalizeNamespace(it.namespace) == "rag" && it.key == "embed_dimension"
}?.value?.toIntOrNull()?.takeIf { it > 0 }

/** 偏好提交可能异步刷新模型管理器；路由相关设置变化时先保守失效。 */
internal fun sameEmbeddingSettings(before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot): Boolean {
    fun relevant(snapshot: BackupPreferenceSnapshot) = snapshot.entries.filter { entry ->
        val namespace = BackupPreferencePolicy.normalizeNamespace(entry.namespace)
        when (namespace) {
            "provider" -> entry.key in setOf("protocol_id", "base_url", "model", "embedding_base_url", "embedding_model") || entry.key.startsWith("provider_")
            "settings" -> entry.key in setOf("preset_embedding_model", "all_models", "enabled_models", "extra_providers_count", "extra_providers_ids") ||
                entry.key.startsWith("extra_provider_") || entry.key.startsWith("model_info_")
            "rag" -> entry.key == "embed_dimension"
            else -> false
        } && BackupPreferencePolicy.isAllowed(entry.namespace, entry.key)
    }.associate { (BackupPreferencePolicy.normalizeNamespace(it.namespace) to it.key) to (it.type to it.value) }
    return relevant(before) == relevant(after)
}

internal fun embeddingMetadata(
    payload: DatabaseBackupPayload,
    preferences: BackupPreferenceSnapshot,
    currentSpace: String?,
): BackupEmbeddingMetadata? {
    val model = embeddingModelId(preferences) ?: return null
    val space = currentSpace ?: return null
    val dimensions = payload.tables["vectors"].orEmpty().asSequence().filter {
        (vectorSpaceFromMetadata(BackupLayerPolicy.string(it, "metadata"))?.let { key -> key == space } ?: true) &&
            (it["stale"] as? JsonPrimitive)?.longOrNull == 0L
    }.mapNotNull { row -> embeddingSize(row)?.takeIf { it > 0 && it % 4 == 0 }?.div(4) }.distinct().toList()
    val configured = embeddingConfiguredDimension(preferences)
    val dimension = configured?.takeIf { it in dimensions } ?: dimensions.singleOrNull()
    return dimension?.let { BackupEmbeddingMetadata(model, space, it) }
}

private fun embeddingSize(row: JsonObject): Int? = runCatching {
    Base64.getDecoder().decode(BackupLayerPolicy.string(row, "embedding") ?: return null).size
}.getOrNull()

/** 在插入前收集引用、失效旧来源并回收孤立图元素，避免插入时违反外键。 */
internal fun prepareRestoredGraphAndVectors(
    payload: DatabaseBackupPayload,
    imported: DatabaseBackupPayload,
    layers: Set<BackupLayer>?,
    sourceVersion: Int,
    embedding: BackupEmbeddingMetadata?,
    targetModel: String?,
    currentSpace: String?,
    targetDimension: Int? = null,
    preserveUnselectedVectors: Boolean = false,
): DatabaseBackupPayload {
    val tables = payload.tables.toMutableMap()
    fun rows(table: String) = tables[table].orEmpty()
    fun string(row: JsonObject, key: String) = BackupLayerPolicy.string(row, key)
    val files = rows("workspace_files").associateBy { string(it, "uuid") }
    val sessions = rows("sessions").mapNotNull { string(it, "id") }.toSet()
    val messages = rows("messages").associate { string(it, "id") to string(it, "session_id") }
    val collections = rows("kg_collections").mapNotNull { string(it, "id") }.toSet()
    val nodes = rows("kg_nodes").asSequence().filter { string(it, "collection_id") in collections }
        .associate { string(it, "id") to string(it, "collection_id") }
    val edges = rows("kg_edges").asSequence().filter { row ->
        val collection = string(row, "collection_id")
        nodes[string(row, "source_id")] == collection && nodes[string(row, "target_id")] == collection && collection != null
    }.associate { string(it, "id") to (string(it, "source_id") to string(it, "target_id")) }

    val selectedFiles: Set<String?>
    val selectedSessions: Set<String?>
    if (sourceVersion < 16 && layers != null) {
        val index = BackupLayerPolicy.Index(payload.tables)
        selectedFiles = rows("workspace_files").filter { index.layer("workspace_files", it) in layers }.mapTo(hashSetOf()) { string(it, "uuid") } +
            imported.tables["workspace_files"].orEmpty().map { string(it, "uuid") }
        selectedSessions = rows("sessions").filter { index.layer("sessions", it) in layers }.mapTo(hashSetOf()) { string(it, "id") } +
            imported.tables["sessions"].orEmpty().map { string(it, "id") }
    } else {
        selectedFiles = emptySet()
        selectedSessions = emptySet()
    }
    val invalidJitTasks = rows("kg_evidence").filter {
        string(it, "source_type") == "jit" && (string(it, "doc_id") in selectedFiles || string(it, "session_id") in selectedSessions)
    }.mapNotNull { string(it, "task_id") }.toSet()
    val evidence = BackupDatabaseRows.select(rows("kg_evidence")) { row ->
        val doc = string(row, "doc_id")
        val session = string(row, "session_id")
        val message = string(row, "message_id")
        val node = string(row, "node_id")
        val edge = string(row, "edge_id")
        (node != null && edge == null && node in nodes || edge != null && node == null && edge in edges) &&
            (doc == null || files[doc]?.let { string(it, "is_directory") != "1" } == true) && (session == null || session in sessions) &&
            (message == null || message in messages && (session == null || messages[message] == session)) &&
            doc !in selectedFiles && session !in selectedSessions && string(row, "task_id") !in invalidJitTasks
    }
    val usedEdges = evidence.mapNotNull { string(it, "edge_id") }.toSet()
    val usedNodes = evidence.mapNotNull { string(it, "node_id") }.toMutableSet()
    usedEdges.mapNotNull(edges::get).forEach { edge ->
        edge.first?.let(usedNodes::add)
        edge.second?.let(usedNodes::add)
    }
    tables["kg_evidence"] = evidence
    tables["kg_edges"] = BackupDatabaseRows.select(rows("kg_edges")) { string(it, "id") in usedEdges && string(it, "id") in edges }
    tables["kg_nodes"] = BackupDatabaseRows.select(rows("kg_nodes")) { string(it, "id") in usedNodes && string(it, "id") in nodes }
    val usedCollections = usedNodes.mapNotNull { nodes[it] }.toSet()
    tables["kg_collections"] = BackupDatabaseRows.select(rows("kg_collections")) { string(it, "id") == "shared" || string(it, "id") in usedCollections }
    tables["kg_collection_members"] = BackupDatabaseRows.select(rows("kg_collection_members")) { string(it, "doc_id") in files && string(it, "collection_id") in usedCollections }
    val memberCollections = tables.getValue("kg_collection_members").associate { string(it, "doc_id") to string(it, "collection_id") }
    val edgeCollections = tables.getValue("kg_edges").associate { string(it, "id") to string(it, "collection_id") }
    val evidenceById = evidence.associateBy { string(it, "id") }
    tables["kg_edge_mentions"] = BackupDatabaseRows.select(rows("kg_edge_mentions")) { row ->
        val edge = string(row, "edge_id")
        val source = evidenceById[string(row, "evidence_id")]
        edge != null && edge in usedEdges && source != null && string(source, "edge_id") == edge &&
            listOf("doc_id", "task_id", "content_hash").all { field -> string(row, field) != null && string(row, field) == string(source, field) } &&
            memberCollections[string(row, "doc_id")] == edgeCollections[edge]
    }
    tables["kg_edge_validity"] = BackupDatabaseRows.select(rows("kg_edge_validity")) { row ->
        val edge = string(row, "edge_id")
        val collection = string(row, "collection_id")
        val toDoc = string(row, "to_doc_id")
        edge != null && edge in usedEdges && collection == edgeCollections[edge] &&
            memberCollections[string(row, "from_doc_id")] == collection && (toDoc == null || memberCollections[toDoc] == collection)
    }
    tables["kg_entity_links"] = BackupDatabaseRows.select(rows("kg_entity_links")) { row ->
        val collection = string(row, "collection_id")
        val from = string(row, "from_node")
        val to = string(row, "to_node")
        collection != null && from != null && to != null && from != to && from in usedNodes && to in usedNodes &&
            nodes[from] == collection && nodes[to] == collection && string(row, "verdict") in setOf("same", "different")
    }

    val compatible = embedding != null && embedding.dimension in 1..65_536 && embedding.modelId == targetModel && embedding.spaceKey == currentSpace &&
        (targetDimension == null || embedding.dimension == targetDimension)
    val importedVectorIds = imported.tables["vectors"].orEmpty().mapNotNull { string(it, "id") }.toSet()
    val selectedVectors = BackupDatabaseRows.select(rows("vectors")) { row ->
        val doc = string(row, "file_uuid") ?: string(row, "doc_id")
        val session = string(row, "session_id")
        doc != null || session == null || session in sessions
    }
    tables["vectors"] = BackupDatabaseRows.map(selectedVectors) { row ->
        val doc = string(row, "file_uuid") ?: string(row, "doc_id")
        val session = string(row, "session_id")
        val normalized = if (doc != null && session != null && session !in sessions) JsonObject(row + ("session_id" to JsonNull)) else row
        val contentHash = documentContentHashFromVectorMetadata(string(row, "metadata"))
        val untouchedLocal = layers != null && preserveUnselectedVectors && string(row, "id") !in importedVectorIds
        val rowSpace = vectorSpaceFromMetadata(string(row, "metadata"))
        val sourceMatches = untouchedLocal || compatible && (rowSpace == null || rowSpace == embedding?.spaceKey) &&
            embeddingSize(row) == embedding?.dimension?.times(4) &&
            (string(row, "file_uuid") == null || files[doc]?.let { string(it, "is_directory") != "1" } == true) &&
            (contentHash == null || doc != null && contentHash == files[doc]?.let { string(it, "hash") })
        // 2 与普通孤儿标记 1 区分，避免消息撤销恢复时误激活未知模型的向量。
        if (sourceMatches) normalized else JsonObject(normalized + ("stale" to JsonPrimitive(2)))
    }
    val activeVectors = tables.getValue("vectors").asSequence().filter { (it["stale"] as? JsonPrimitive)?.longOrNull == 0L }
    val invalidVectors = tables.getValue("vectors").asSequence().filter { (it["stale"] as? JsonPrimitive)?.longOrNull == 2L }
    val invalidFiles = invalidVectors.mapNotNull { string(it, "file_uuid") ?: string(it, "doc_id") }.toSet()
    val invalidMessages = tables.getValue("vectors").asSequence().filter { (it["stale"] as? JsonPrimitive)?.longOrNull == 2L }
        .flatMap { sequenceOf(string(it, "start_message_id"), string(it, "end_message_id")) }.filterNotNull().toSet()
    val vectorFiles = activeVectors.mapNotNull { string(it, "file_uuid") ?: string(it, "doc_id") }.toSet() - invalidFiles
    val vectorMessages = tables.getValue("vectors").asSequence().filter { (it["stale"] as? JsonPrimitive)?.longOrNull == 0L }
        .flatMap { sequenceOf(string(it, "start_message_id"), string(it, "end_message_id")) }.filterNotNull().toSet()
        .minus(invalidMessages)
    val graphFiles = evidence.asSequence().filter { row ->
        val doc = string(row, "doc_id")
        val file = files[doc]
        val hash = string(row, "content_hash").orEmpty()
        doc != null && file != null && string(row, "source_type") in setOf("document", "legacy") &&
            (hash.isEmpty() || hash == string(file, "hash"))
    }.mapNotNull { string(it, "doc_id") }.toSet()
    tables["workspace_files"] = BackupDatabaseRows.map(rows("workspace_files")) { row ->
        val id = string(row, "uuid")
        JsonObject(row + mapOf(
            "vectorized_at" to if (id in vectorFiles) row.getValue("vectorized_at") else JsonNull,
            "kg_extracted_at" to if (id in graphFiles) row.getValue("kg_extracted_at") else JsonNull,
        ))
    }
    tables["messages"] = BackupDatabaseRows.map(rows("messages")) { row ->
        if (string(row, "id") in vectorMessages) row else JsonObject(row + ("vectorization_status" to JsonNull))
    }
    return payload.copy(tables = tables)
}
