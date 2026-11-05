package com.promenar.infinity.data.local.db

import kotlinx.serialization.json.*
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity

/** 夹具显式登记来源，避免绕过生产的实体与证据原子写入约束。 */
suspend fun InfinityDatabase.seedKgNode(node: KgNodeEntity, sessionId: String? = null, docId: String? = null, workspaceDocument: Boolean = false) {
    kgNodeDao().insert(node)
    kgEvidenceDao().insert(KgEvidenceEntity("fixture-node:${node.id}:$sessionId:$docId", nodeId = node.id,
        sourceType = if (docId != null) "document" else if (sessionId != null) "session" else "legacy",
        docId = docId, sessionId = sessionId, contentHash = "", taskId = "fixture", createdAt = node.createdAt, workspaceDocument = workspaceDocument))
}

suspend fun InfinityDatabase.seedKgEdge(edge: KgEdgeEntity, sessionId: String? = null, docId: String? = null, workspaceDocument: Boolean = false) {
    kgEdgeDao().insert(edge)
    val storedId = requireNotNull(kgEdgeDao().findIdentity(edge.collectionId, edge.sourceId, edge.targetId, edge.normalizedRelation)).id
    listOf(edge.sourceId, edge.targetId).distinct().forEach { id ->
        kgEvidenceDao().insert(KgEvidenceEntity("fixture-endpoint:${edge.id}:$id:$sessionId:$docId", nodeId = id,
            sourceType = if (docId != null) "document" else if (sessionId != null) "session" else "legacy", docId = docId, sessionId = sessionId,
            contentHash = "", taskId = "fixture", createdAt = edge.createdAt, workspaceDocument = workspaceDocument, weight = edge.weight))
    }
    kgEvidenceDao().insert(KgEvidenceEntity("fixture-edge:${edge.id}:$sessionId:$docId", edgeId = storedId,
        sourceType = if (docId != null) "document" else if (sessionId != null) "session" else "legacy", docId = docId, sessionId = sessionId,
        contentHash = "", taskId = "fixture", createdAt = edge.createdAt, workspaceDocument = workspaceDocument, weight = edge.weight))
}

fun replaceKgTablesWithV18(database: android.database.sqlite.SQLiteDatabase) {
    listOf("kg_jit_cache_sources", "kg_evidence", "kg_edges", "kg_nodes", "kg_jit_cache", "kg_collection_members", "kg_folder_settings", "kg_collections").forEach {
        database.execSQL("DROP TABLE IF EXISTS $it")
    }
    val schema = kotlinx.serialization.json.Json.parseToJsonElement(
        java.io.File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/18.json").readText(),
    ).jsonObject.getValue("database").jsonObject
    schema.getValue("entities").jsonArray.filter { it.jsonObject.getValue("tableName").jsonPrimitive.content.startsWith("kg_") }.forEach { item ->
        val entity = item.jsonObject
        val table = entity.getValue("tableName").jsonPrimitive.content
        database.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        entity["indices"]?.jsonArray.orEmpty().forEach { database.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
    }
}
