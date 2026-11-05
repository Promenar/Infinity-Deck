package com.promenar.infinity.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 知识库使用 __system__ 所属会话；workspaces.kind 描述用户选择目录的后端。 */
internal object BackupLayerPolicy {
    const val KNOWLEDGE_AGENT = "__system__"
    val CORE_TABLES = setOf("workspaces", "agents", "agent_rule_revisions", "long_term_memories", "parameter_schemes", "mcp_servers", "custom_skills", "workspace_seq")
    val SESSION_TABLES = setOf("sessions", "messages", "attachments", "artifacts", "context_summaries", "task_nodes", "state_board_versions")
    fun tableLayer(table: String, declared: BackupLayer? = null): BackupLayer? = when (table) {
        "long_term_memories" -> declared?.takeIf { it in setOf(BackupLayer.CORE, BackupLayer.KNOWLEDGE, BackupLayer.SESSIONS) }
        in CORE_TABLES -> BackupLayer.CORE
        "tags", "document_tags" -> BackupLayer.KNOWLEDGE
        "workspace_files", "kg_folder_settings" -> declared?.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.WORKSPACE_FILES }
        "kg_collections", "kg_collection_members", "kg_nodes", "kg_edges", "kg_evidence", "kg_edge_mentions", "kg_edge_validity", "kg_entity_links", "vectors" ->
            declared?.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.WORKSPACE_FILES || it == BackupLayer.SESSIONS }
        "knowledge_citations" -> declared?.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.WORKSPACE_FILES || it == BackupLayer.SESSIONS }
        in SESSION_TABLES -> declared?.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.SESSIONS }
        else -> null
    }
    fun string(row: JsonObject, column: String): String? = (row[column] as? JsonPrimitive)?.contentOrNull
    fun knowledgeSessions(tables: Map<String, List<JsonObject>>): Set<String> = tables["sessions"].orEmpty()
        .filter { string(it, "agent_id") == KNOWLEDGE_AGENT }.mapNotNull { string(it, "id") }.toSet()
    fun knowledgeRoots(tables: Map<String, List<JsonObject>>): Set<String> = tables["sessions"].orEmpty()
        .filter { string(it, "agent_id") == KNOWLEDGE_AGENT }.mapNotNull { string(it, "workspace_root_uuid") }.toSet()
    class Index(tables: Map<String, List<JsonObject>>) {
        private val owners = knowledgeSessions(tables)
        private val roots = knowledgeRoots(tables)
        private val messages = tables["messages"].orEmpty().asSequence().filter { string(it, "session_id") in owners }.mapNotNull { string(it, "id") }.toSet()
        private val fileRoots = tables["workspace_files"].orEmpty().associate { string(it, "uuid") to string(it, "workspace_root_uuid") }
        private fun fileLayer(id: String?): BackupLayer = if (fileRoots[id] in roots) BackupLayer.KNOWLEDGE else BackupLayer.WORKSPACE_FILES
        private fun sessionLayer(id: String?): BackupLayer = if (id in owners) BackupLayer.KNOWLEDGE else BackupLayer.SESSIONS
        private val collectionLayers = tables["kg_collections"].orEmpty().associate { row ->
            val id = string(row, "id")
            id to when (string(row, "kind")) {
                "shared" -> BackupLayer.KNOWLEDGE
                "session" -> sessionLayer(id?.removePrefix("session:"))
                else -> fileLayer(string(row, "root_ref"))
            }
        }
        private val edgeCollections = tables["kg_edges"].orEmpty().associate { string(it, "id") to string(it, "collection_id") }
        fun layer(table: String, row: JsonObject): BackupLayer {
            if (table == "long_term_memories") return string(row, "owner_session_id")?.let(::sessionLayer) ?: BackupLayer.CORE
            if (table == "kg_folder_settings") return if (fileRoots[string(row, "folder_id")] in roots) BackupLayer.KNOWLEDGE else BackupLayer.WORKSPACE_FILES
            if (table == "workspace_files") return if (string(row, "workspace_root_uuid") in roots) BackupLayer.KNOWLEDGE else BackupLayer.WORKSPACE_FILES
            if (table == "kg_collections") return collectionLayers[string(row, "id")] ?: BackupLayer.WORKSPACE_FILES
            if (table in setOf("kg_collection_members", "kg_nodes", "kg_edges", "kg_edge_validity", "kg_entity_links")) return collectionLayers[string(row, "collection_id")] ?: BackupLayer.WORKSPACE_FILES
            if (table == "kg_edge_mentions") return collectionLayers[edgeCollections[string(row, "edge_id")]] ?: BackupLayer.WORKSPACE_FILES
            if (table == "kg_evidence") {
                val session = string(row, "session_id")
                val doc = string(row, "doc_id")
                return when {
                    session != null && string(row, "source_type") in setOf("session", "jit") -> sessionLayer(session)
                    doc != null -> fileLayer(doc)
                    session != null -> sessionLayer(session)
                    else -> BackupLayer.KNOWLEDGE
                }
            }
            if (table == "vectors") {
                val doc = string(row, "file_uuid") ?: string(row, "doc_id")
                return if (doc != null) fileLayer(doc) else sessionLayer(string(row, "session_id"))
            }
            if (table == "knowledge_citations") {
                if (string(row, "source_kind") == "CHAT") {
                    return if (string(row, "session_id") in owners) BackupLayer.KNOWLEDGE else BackupLayer.SESSIONS
                }
                return if (fileRoots[string(row, "document_id")] in roots) BackupLayer.KNOWLEDGE else BackupLayer.WORKSPACE_FILES
            }
            if (table in CORE_TABLES) return BackupLayer.CORE
            if (table == "tags" || table == "document_tags") return BackupLayer.KNOWLEDGE
            val knowledge = when (table) {
                "sessions" -> string(row, "id") in owners
                "attachments" -> string(row, "message_id") in messages
                else -> string(row, "session_id") in owners
            }
            return if (knowledge) BackupLayer.KNOWLEDGE else BackupLayer.SESSIONS
        }
    }
    fun clear(row: JsonObject, vararg columns: String): JsonObject = JsonObject(row + columns.associateWith { JsonNull })
}
