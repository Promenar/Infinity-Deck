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
        in CORE_TABLES -> BackupLayer.CORE
        "tags", "document_tags" -> BackupLayer.KNOWLEDGE
        "workspace_files" -> declared?.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.WORKSPACE_FILES }
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
        fun layer(table: String, row: JsonObject): BackupLayer {
            if (table == "workspace_files") return if (string(row, "workspace_root_uuid") in roots) BackupLayer.KNOWLEDGE else BackupLayer.WORKSPACE_FILES
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
