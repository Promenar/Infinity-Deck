package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private fun legacyObject(raw: String?): JsonObject =
    if (raw.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(raw).jsonObject

internal fun legacyPrivateAgent(raw: String?): Boolean {
    val policy = legacyObject(raw)["memoryPolicy"] as? JsonObject ?: return false
    return (policy["shareWithOtherAgents"] as? JsonPrimitive)?.booleanOrNull == false
}

internal fun migrateLegacyRetrievalConfig(raw: String?): String {
    val config = legacyObject(raw)
    val policy = config["memoryPolicy"] as? JsonObject ?: JsonObject(emptyMap())
    val isPrivate = legacyPrivateAgent(raw)
    val documentDefault = (policy["documentDefault"] as? JsonPrimitive)?.contentOrNull
    val migrated = JsonObject(policy + mapOf(
        "globalMemory" to JsonPrimitive(if (isPrivate) "FORBID" else "ALLOW_DEFAULT_OFF"),
        "activeMemoryDefault" to JsonPrimitive(if (isPrivate) "ISOLATED" else "SHARED"),
        "defaultCollectionIds" to if (documentDefault == "WORKSPACE") JsonArray(emptyList()) else kotlinx.serialization.json.JsonNull,
        "allowedCollectionIds" to kotlinx.serialization.json.JsonNull,
    ))
    return JsonObject(config + ("memoryPolicy" to migrated)).toString()
}

internal fun migrateLegacyRagOptions(raw: String?, isPrivate: Boolean): String {
    val options = legacyObject(raw)
    val workspaceOnly = (options["documentScope"] as? JsonPrimitive)?.contentOrNull == "WORKSPACE"
    return JsonObject(options + mapOf(
        "memoryScope" to JsonPrimitive("SESSION"),
        "isGlobal" to JsonPrimitive(false),
        "globalMemoryEnabled" to JsonPrimitive(false),
        "globalMemoryScope" to JsonPrimitive("AGENT"),
        "activeMemoryMode" to JsonPrimitive(if (isPrivate) "ISOLATED" else "SHARED"),
        "selectedCollectionIds" to if (workspaceOnly) JsonArray(emptyList()) else kotlinx.serialization.json.JsonNull,
    )).toString()
}

val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE long_term_memories ADD COLUMN owner_session_id TEXT")
        val privateAgents = mutableSetOf<String>()
        val agents = mutableListOf<Pair<String, String>>()
        db.query("SELECT id,retrieval_config FROM agents").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val raw = if (cursor.isNull(1)) null else cursor.getString(1)
                if (legacyPrivateAgent(raw)) privateAgents += id
                agents += id to migrateLegacyRetrievalConfig(raw)
            }
        }
        agents.forEach { (id, config) -> db.execSQL("UPDATE agents SET retrieval_config=? WHERE id=?", arrayOf(config, id)) }
        val sessions = mutableListOf<Pair<String, String>>()
        db.query("SELECT id,agent_id,rag_options FROM sessions").use { cursor ->
            while (cursor.moveToNext()) {
                val raw = if (cursor.isNull(2)) null else cursor.getString(2)
                sessions += cursor.getString(0) to migrateLegacyRagOptions(raw, cursor.getString(1) in privateAgents)
            }
        }
        sessions.forEach { (id, options) -> db.execSQL("UPDATE sessions SET rag_options=? WHERE id=?", arrayOf(options, id)) }
    }
}
