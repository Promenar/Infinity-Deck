package com.promenar.infinity.data.local.db.recovery

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.promenar.infinity.data.repository.PersistentFileIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString


internal data class DualDatabaseMergeResult(
    val idMappingSha256: String,
    val mappingCount: Int,
    val unresolvedReferences: List<String>,
)

internal fun interface RecoveryPathRewriter {
    fun rewrite(table: String, column: String, value: String): String
}

/**
 * 两侧都已在私有 working 副本迁移到相同的受信任 schema 后执行。当前侧为基底，旧侧全部标识符固定映射，
 * 不修改聊天正文；只处理已知结构化ID字段。
 */
internal class DualDatabaseGraphMerger(
    private val pathRewriter: RecoveryPathRewriter,
    private val recoveredRootIdentities: Map<String, String> = emptyMap(),
) {
    fun merge(currentV18: Path, legacyV18: Path, transactionMarker: String): DualDatabaseMergeResult {
        require(transactionMarker.matches(Regex("[A-Za-z0-9:-]{1,128}")))
        val target = SQLiteDatabase.openDatabase(currentV18.toString(), null, SQLiteDatabase.OPEN_READWRITE)
        try {
            target.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "当前working数据库WAL无法checkpoint" }
            }
            target.execSQL("ATTACH DATABASE ? AS legacy", arrayOf(legacyV18.toString()))
            try {
                target.execSQL("PRAGMA foreign_keys=OFF")
                target.beginTransaction()
                val mappings = buildMappings(target)
                val unresolved = mutableListOf<String>()
                if (rowCount(target, "legacy", "kg_jit_cache") > 0) {
                    unresolved += "preserved-derived-cache:kg_jit_cache"
                }
                COPY_ORDER.forEach { table -> copyTable(target, table, mappings, unresolved) }
                if (columns(target, "main", "kg_evidence").isNotEmpty()) {
                    target.execSQL("DELETE FROM kg_edges WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id=kg_edges.id)")
                    target.execSQL("DELETE FROM kg_nodes WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id=kg_nodes.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id=kg_nodes.id OR e.target_id=kg_nodes.id)")
                    target.execSQL("DELETE FROM kg_jit_cache_sources")
                }
                mergeWorkspaceSequence(target)
                rebuildVectorFts(target)
                target.execSQL("DELETE FROM kg_jit_cache")
                markRecoveredRuntimeState(target)
                ensureRecoveryIndexHolds(target)
                bindRecoveredWorkspaceRoots(target)
                target.execSQL(
                    "CREATE TABLE IF NOT EXISTS dual_database_recovery_receipts " +
                        "(transaction_marker TEXT NOT NULL PRIMARY KEY, mapping_sha256 TEXT NOT NULL, created_at INTEGER NOT NULL)",
                )
                val digest = mappingDigest(mappings)
                target.execSQL(
                    "INSERT INTO dual_database_recovery_receipts(transaction_marker,mapping_sha256,created_at) VALUES(?,?,?)",
                    arrayOf<Any>(transactionMarker, digest, System.currentTimeMillis()),
                )
                if (unresolved.any { it.startsWith("required:") }) {
                    throw IllegalStateException("旧数据库包含不可达的唯一引用，拒绝发布: ${unresolved.first()}")
                }
                target.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    if (cursor.moveToFirst()) throw IllegalStateException("双库候选存在外键不一致")
                }
                target.setTransactionSuccessful()
                return DualDatabaseMergeResult(digest, mappings.values.sumOf(Map<String, String>::size), unresolved.sorted())
            } finally {
                if (target.inTransaction()) target.endTransaction()
                target.execSQL("DETACH DATABASE legacy")
            }
        } finally { target.close() }
    }

    private fun buildMappings(db: SQLiteDatabase): Map<ColumnKey, Map<String, String>> {
        val result = linkedMapOf<ColumnKey, Map<String, String>>()
        val currentHasFixedRagSession = rowExists(
            db,
            "SELECT 1 FROM sessions WHERE id=? LIMIT 1",
            arrayOf(RAG_WORKSPACE_SESSION_ID),
        )
        COPY_ORDER.forEach { table ->
            primaryKeyColumns(db, "legacy", table).filterNot { ColumnKey(table, it) in NATURAL_PRIMARY_KEYS }.forEach { column ->
                val values = linkedMapOf<String, String>()
                db.rawQuery("SELECT `${escape(column)}` FROM legacy.`${escape(table)}`", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(0)) cursor.getString(0)?.let { old ->
                            values[old] = when {
                                table == "kg_collections" && column == "id" -> mappedGraphId(old, result)
                                table == "kg_folder_settings" && column == "folder_id" -> mappedDocumentId(old, result)
                                table == "kg_collection_members" && column == "doc_id" -> mappedDocumentId(old, result)
                                else -> existingKgIdentity(db, table, old, result) ?: if (
                                table == "sessions" && column == "id" &&
                                old == RAG_WORKSPACE_SESSION_ID && !currentHasFixedRagSession
                                ) old else RecoveryStableId.map(table, column, old)
                            }
                        }
                    }
                }
                result[ColumnKey(table, column)] = values
            }
        }
        val ragDocuments = linkedSetOf<String>()
        listOf(
            ColumnKey("vectors", "doc_id"),
            ColumnKey("document_tags", "doc_id"),
            ColumnKey("vectorization_tasks", "doc_id"),
            ColumnKey("kg_nodes", "file_uuid"),
            ColumnKey("kg_edges", "file_uuid"),
            ColumnKey("kg_edges", "doc_id"),
            ColumnKey("kg_evidence", "doc_id"),
        ).forEach { key ->
            if (columns(db, "legacy", key.table).contains(key.column)) {
                db.rawQuery("SELECT `${escape(key.column)}` FROM legacy.`${escape(key.table)}`", null).use { cursor ->
                    while (cursor.moveToNext()) if (!cursor.isNull(0)) cursor.getString(0)
                        ?.takeIf(String::isNotBlank)?.let(ragDocuments::add)
                }
            }
        }
        result[RAG_DOCUMENT_KEY] = ragDocuments.associateWith { RecoveryStableId.map("rag_documents", "id", it) }
        val toolCallIds = linkedSetOf<String>()
        result[TOOL_CALL_KEY]?.keys?.let(toolCallIds::addAll)
        if (columns(db, "legacy", "messages").contains("tool_call_id")) {
            db.rawQuery("SELECT tool_call_id,tool_calls,pending_approval_tool_ids FROM legacy.messages", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) cursor.getString(0)?.takeIf(String::isNotBlank)?.let(toolCallIds::add)
                    listOf(1, 2).forEach { index ->
                        if (!cursor.isNull(index)) collectJsonStringValues(cursor.getString(index), setOf("id"), toolCallIds)
                    }
                }
            }
        }
        if (columns(db, "legacy", "sessions").contains("approval_request")) {
            db.rawQuery("SELECT approval_request FROM legacy.sessions WHERE approval_request IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) collectJsonStringValues(cursor.getString(0), setOf("toolCallId", "tool_call_id"), toolCallIds)
            }
        }
        result[TOOL_CALL_KEY] = toolCallIds.associateWith {
            RecoveryStableId.map("tool_execution_ledger", "tool_call_id", it)
        }
        return result
    }

    private fun mappedDocumentId(old: String, mappings: Map<ColumnKey, Map<String, String>>): String =
        mappings[ColumnKey("workspace_files", "uuid")]?.get(old)
            ?: RecoveryStableId.map("rag_documents", "id", old)

    private fun mappedGraphId(old: String, mappings: Map<ColumnKey, Map<String, String>>): String = when {
        old == "shared" -> old
        old.startsWith("work:") -> "work:" + mappedDocumentId(old.removePrefix("work:"), mappings)
        old.startsWith("session:") -> {
            val id = old.removePrefix("session:")
            "session:" + (mappings[ColumnKey("sessions", "id")]?.get(id) ?: RecoveryStableId.map("sessions", "id", id))
        }
        else -> RecoveryStableId.map("kg_collections", "id", old)
    }

    private fun bindRecoveredWorkspaceRoots(db: SQLiteDatabase) {
        recoveredRootIdentities.forEach { (physicalPath, identity) ->
            require(physicalPath.startsWith('/') && PersistentFileIdentity.isValid(identity)) {
                "恢复工作区身份合同无效"
            }
            db.execSQL(
                "UPDATE workspace_files SET hash=? WHERE physical_root_path=? " +
                    "AND uuid=workspace_root_uuid AND parent_uuid IS NULL AND is_directory=1",
                arrayOf(identity, physicalPath),
            )
            db.rawQuery("SELECT changes()", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "恢复工作区根身份未唯一绑定" }
            }
        }
        db.rawQuery(
            "SELECT physical_root_path FROM workspace_files WHERE uuid=workspace_root_uuid " +
                "AND parent_uuid IS NULL AND is_directory=1 AND (hash IS NULL OR hash='')",
            null,
        ).use { cursor ->
            check(!cursor.moveToFirst()) { "恢复候选仍有未绑定工作区根" }
        }
    }

    private fun collectJsonStringValues(raw: String, keys: Set<String>, output: MutableSet<String>) {
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach { child ->
                    if (child is JsonPrimitive && child.isString) output += child.content else visit(child)
                }
                is JsonObject -> element.forEach { (key, child) ->
                    if (key in keys && child is JsonPrimitive && child.isString) output += child.content
                    else visit(child)
                }
                else -> Unit
            }
        }
        runCatching { visit(Json.parseToJsonElement(raw)) }
    }

    private fun copyTable(
        db: SQLiteDatabase,
        table: String,
        mappings: Map<ColumnKey, Map<String, String>>,
        unresolved: MutableList<String>,
    ) {
        val targetColumns = columns(db, "main", table)
        val sourceColumns = columns(db, "legacy", table).filter(targetColumns::contains)
        if (sourceColumns.isEmpty()) return
        val foreign = foreignColumns(db, table)
        db.rawQuery(
            "SELECT ${sourceColumns.joinToString { "`${escape(it)}`" }} FROM legacy.`${escape(table)}`",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(sourceColumns.size)
                val stringRow = sourceColumns.mapIndexedNotNull { index, column ->
                    if (!cursor.isNull(index) && cursor.getType(index) == Cursor.FIELD_TYPE_STRING) column to cursor.getString(index)
                    else null
                }.toMap()
                sourceColumns.forEachIndexed { index, column ->
                    val raw = cursorValue(cursor, index)
                    val transformed = if (raw is String) {
                        transformString(table, column, raw, stringRow, foreign[column], mappings, unresolved)
                    } else raw
                    put(values, column, transformed)
                }
                if (table == "kg_evidence" && values.containsKey("content_hash") && values.containsKey("task_id")) {
                    val parts = listOf("node_id", "edge_id", "source_type", "doc_id", "session_id", "content_hash", "task_id")
                        .map { values.getAsString(it).orEmpty() } + listOfNotNull(values.getAsString("message_id"))
                    val input = Json.encodeToString(ListSerializer(String.serializer()), parts)
                    val id = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    values.put("id", id)
                }
                if (table in setOf("kg_nodes", "kg_edges")) {
                    val inserted = db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (table == "kg_nodes" && inserted == -1L && values.containsKey("metadata")) {
                        val oldMetadata = db.rawQuery("SELECT metadata FROM kg_nodes WHERE id=?", arrayOf(values.getAsString("id"))).use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
                        }
                        fun parse(raw: String?): JsonObject = runCatching { Json.parseToJsonElement(raw.orEmpty()) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                        db.execSQL("UPDATE kg_nodes SET metadata=? WHERE id=?", arrayOf(JsonObject(parse(oldMetadata) + parse(values.getAsString("metadata"))).toString(), values.getAsString("id")))
                    }
                    if (table == "kg_edges" && values.containsKey("weight")) {
                        db.execSQL("UPDATE kg_edges SET weight=MAX(weight,?) WHERE id=?", arrayOf(values.getAsDouble("weight"), values.getAsString("id")))
                    }
                } else if (table in setOf("kg_collections", "kg_evidence")) {
                    db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (table == "kg_evidence" && values.getAsDouble("weight") != null) {
                        db.execSQL("UPDATE kg_evidence SET weight=MAX(COALESCE(weight,0),?) WHERE id=?", arrayOf(values.getAsDouble("weight"), values.getAsString("id")))
                    }
                } else if (db.insertOrThrow(table, null, values) < 0) throw IllegalStateException("合并${table}失败")
            }
        }
    }

    private fun existingKgIdentity(db: SQLiteDatabase, table: String, old: String,
        mappings: Map<ColumnKey, Map<String, String>>): String? {
        val identityColumn = when (table) { "kg_nodes" -> "normalized_name"; "kg_edges" -> "normalized_relation"; else -> return null }
        if (identityColumn !in columns(db, "legacy", table)) return null
        val graphAware = "collection_id" in columns(db, "legacy", table) && "collection_id" in columns(db, "main", table)
        val graphId = if (graphAware) db.rawQuery("SELECT collection_id FROM legacy.`${escape(table)}` WHERE id=?", arrayOf(old)).use {
            if (!it.moveToFirst()) return null
            mappings[ColumnKey("kg_collections", "id")]?.get(it.getString(0)) ?: return null
        } else null
        val sql = if (table == "kg_nodes") {
            "SELECT n.id FROM kg_nodes n JOIN legacy.kg_nodes l ON n.normalized_name=l.normalized_name AND n.type=l.type WHERE l.id=?" +
                if (graphAware) " AND n.collection_id=?" else ""
        } else {
            val source = db.rawQuery("SELECT source_id,target_id,normalized_relation FROM legacy.kg_edges WHERE id=?", arrayOf(old)).use {
                if (!it.moveToFirst()) return null
                Triple(it.getString(0), it.getString(1), it.getString(2))
            }
            val args = listOf(mappings[ColumnKey("kg_nodes", "id")]?.get(source.first), mappings[ColumnKey("kg_nodes", "id")]?.get(source.second), source.third) + listOfNotNull(graphId)
            return db.rawQuery("SELECT id FROM kg_edges WHERE source_id=? AND target_id=? AND normalized_relation=?" + if (graphAware) " AND collection_id=?" else "", args.toTypedArray()).use { if (it.moveToFirst()) it.getString(0) else null }
        }
        return db.rawQuery(sql, (listOf(old) + listOfNotNull(graphId)).toTypedArray()).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun transformString(
        table: String,
        column: String,
        value: String,
        row: Map<String, String>,
        foreignTarget: ColumnKey?,
        mappings: Map<ColumnKey, Map<String, String>>,
        unresolved: MutableList<String>,
    ): String {
        if (value.isBlank()) return value
        if (table == "kg_evidence" && column == "task_id") {
            return mappings[ColumnKey("vectorization_tasks", "id")]?.get(value)
                ?: RecoveryStableId.map("kg_extraction_tasks", "id", value)
        }
        if (table == "kg_collections" && column == "root_ref") return mappedDocumentId(value, mappings)
        if (table in setOf("kg_nodes", "kg_edges", "kg_collection_members") && column == "collection_id") {
            return mappings[ColumnKey("kg_collections", "id")]?.get(value) ?: mappedGraphId(value, mappings)
        }
        if (table == "sessions" && column == "agent_id" && value == RAG_SYSTEM_AGENT_ID) return value
        if (table == "attachments" && column == "uri" && value.startsWith("content://") &&
            row["local_uri"].isNullOrBlank()) {
            unresolved += "required:attachments.uri=$value"
            return value
        }
        if (table == "messages" && column == "images") {
            return if (value.startsWith("data:image/")) value else pathRewriter.rewrite(table, column, value)
        }
        if (column == "doc_id" && table in setOf("vectors", "document_tags", "vectorization_tasks")) {
            val workspaceIds = mappings[ColumnKey("workspace_files", "uuid")].orEmpty()
            val workspaceReference = value in workspaceIds ||
                table == "vectorization_tasks" && !row["workspace_root_uuid"].isNullOrBlank() ||
                table == "vectors" && !row["file_uuid"].isNullOrBlank()
            val key = if (workspaceReference) ColumnKey("workspace_files", "uuid") else RAG_DOCUMENT_KEY
            return mappings[key]?.get(value) ?: value.also { unresolved += "required:$table.$column=$value" }
        }
        if ((column == "doc_id" && table in setOf("kg_edges", "kg_evidence")) ||
            (column == "file_uuid" && table in setOf("kg_nodes", "kg_edges"))) {
            return mappings[ColumnKey("workspace_files", "uuid")]?.get(value)
                ?: mappings[RAG_DOCUMENT_KEY]?.get(value)
                ?: value.also { unresolved += "required:$table.$column=$value" }
        }
        val target = EXPLICIT_REFERENCES[ColumnKey(table, column)] ?: foreignTarget
        if (target != null) {
            return mappings[target]?.get(value) ?: value.also {
                unresolved += "required:$table.$column=$value"
            }
        }
        mappings[ColumnKey(table, column)]?.get(value)?.let { return it }
        JSON_ARRAY_REFERENCES[ColumnKey(table, column)]?.let { jsonTarget ->
            return rewriteJsonArray(value, mappings[jsonTarget].orEmpty(), "$table.$column", unresolved)
        }
        if (ColumnKey(table, column) in TOOL_CALL_JSON) return rewriteKnownReferenceJson(
            value, mappings, "$table.$column", unresolved, mapBareIdToToolCall = true,
        )
        if (ColumnKey(table, column) in STRUCTURED_REFERENCE_JSON) {
            return rewriteKnownReferenceJson(value, mappings, "$table.$column", unresolved)
        }
        if (ColumnKey(table, column) in PATH_COLUMNS) return pathRewriter.rewrite(table, column, value)
        if (column.endsWith("_json") || column in POSSIBLE_UNKNOWN_JSON) {
            unresolved += "preserved-json:$table.$column"
        }
        return value
    }

    private fun rewriteJsonArray(
        raw: String,
        mapping: Map<String, String>,
        location: String,
        unresolved: MutableList<String>,
    ): String = runCatching {
        val array = Json.parseToJsonElement(raw) as JsonArray
        JsonArray(array.map { element ->
            val old = (element as? JsonPrimitive)?.contentOrNull ?: return@map element
            JsonPrimitive(mapping[old] ?: old.also { unresolved += "required:$location=$old" })
        }).toString()
    }.getOrElse {
        unresolved += "required:$location:invalid-structured-json"
        raw
    }

    private fun rewriteKnownReferenceJson(
        raw: String,
        mappings: Map<ColumnKey, Map<String, String>>,
        location: String,
        unresolved: MutableList<String>,
        mapBareIdToToolCall: Boolean = false,
    ): String = runCatching {
        fun visit(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::visit))
            is JsonObject -> JsonObject(element.mapValues { (key, child) ->
                val target = if (key == "id" && mapBareIdToToolCall) TOOL_CALL_KEY else JSON_REFERENCE_KEYS[key]
                val arrayTarget = JSON_ARRAY_REFERENCE_KEYS[key]
                if (key in JSON_PATH_KEYS && child is JsonPrimitive && child.isString) {
                    JsonPrimitive(pathRewriter.rewrite(location.substringBefore('.'), key, child.content))
                } else if (location == "sessions.rag_options" && key == "collectionId" && child is JsonPrimitive && child.isString) {
                    JsonPrimitive(mappings[ColumnKey("kg_collections", "id")]?.get(child.content) ?: mappedGraphId(child.content, mappings))
                } else if (arrayTarget != null && child is JsonArray) {
                    JsonArray(child.map { item ->
                        val old = (item as? JsonPrimitive)?.contentOrNull ?: return@map item
                        val mapped = mappings[arrayTarget]?.get(old)
                            ?: if (key == "activeDocIds") mappings[RAG_DOCUMENT_KEY]?.get(old) else null
                        JsonPrimitive(mapped ?: old.also {
                            unresolved += "required:$location.$key=$old"
                        })
                    })
                } else if (target != null && child is JsonPrimitive && child.isString) {
                    val old = child.content
                    val mapped = mappings[target]?.get(old)
                        ?: if (key == "documentId" || key == "document_id") mappings[RAG_DOCUMENT_KEY]?.get(old) else null
                    JsonPrimitive(mapped ?: old.also {
                        unresolved += "required:$location.$key=$old"
                    })
                } else visit(child)
            })
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(pathRewriter.rewrite(location.substringBefore('.'), "value", element.content))
            } else element
        }
        visit(Json.parseToJsonElement(raw)).toString()
    }.getOrElse {
        unresolved += "required:$location:invalid-structured-json"
        raw
    }

    private fun mergeWorkspaceSequence(db: SQLiteDatabase) {
        db.execSQL(
            """INSERT INTO workspace_seq(date_key,last_seq)
                SELECT date_key,last_seq FROM legacy.workspace_seq
                WHERE 1
                ON CONFLICT(date_key) DO UPDATE SET last_seq=MAX(last_seq,excluded.last_seq)""".trimIndent(),
        )
    }

    private fun rebuildVectorFts(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO vectors_fts(vectors_fts) VALUES('rebuild')")
    }

    private fun markRecoveredRuntimeState(db: SQLiteDatabase) {
        db.execSQL(
            "UPDATE vectorization_tasks SET status='failed', progress=0, " +
                "error='双库恢复保留了任务，但禁止自动调用外部索引服务' " +
                "WHERE status NOT IN ('completed','failed')",
        )
        db.execSQL(
            "UPDATE tool_execution_ledger SET status='FAILED', error='双库恢复后需人工确认，未自动重放' " +
                "WHERE status IN ('RUNNING','APPROVED','PENDING_APPROVAL')",
        )
        db.execSQL(
            "UPDATE sessions SET approval_request=NULL, pending_intervention=NULL, loop_status='idle' " +
                "WHERE approval_request IS NOT NULL OR pending_intervention IS NOT NULL OR loop_status<>'idle'",
        )
        db.execSQL("DELETE FROM workspace_mutations")
    }

    private fun ensureRecoveryIndexHolds(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT uuid,workspace_root_uuid,name,mime_type,hash,updated_at FROM workspace_files " +
                "WHERE is_directory=0 AND in_recycle_bin=0 AND (vectorized_at IS NULL OR updated_at>vectorized_at)",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fileId = cursor.getString(0)
                val rootId = cursor.getString(1)
                val now = maxOf(1L, cursor.getLong(5))
                val updated = db.update(
                    "vectorization_tasks",
                    ContentValues().apply {
                        put("status", "failed")
                        put("progress", 0.0)
                        put("error", "双库恢复保留了未索引文件；请由用户显式重试")
                        put("sub_status", DUAL_DATABASE_RECOVERY_INDEX_HOLD)
                        put("updated_at", now)
                    },
                    "workspace_root_uuid=? AND doc_id=? AND type='document_reference'",
                    arrayOf(rootId, fileId),
                )
                if (updated == 0) {
                    db.insertOrThrow("vectorization_tasks", null, ContentValues().apply {
                        put("id", RecoveryStableId.map("recovery_index_holds", "id", "$rootId\u0000$fileId"))
                        put("type", "document_reference")
                        put("status", "failed")
                        put("doc_id", fileId)
                        put("doc_title", cursor.getString(2))
                        put("workspace_root_uuid", rootId)
                        put("last_chunk_index", 0)
                        put("progress", 0.0)
                        put("error", "双库恢复保留了未索引文件；请由用户显式重试")
                        put("skip_vectorization", 0)
                        put("sub_status", DUAL_DATABASE_RECOVERY_INDEX_HOLD)
                        if (!cursor.isNull(3)) put("source_mime_type", cursor.getString(3))
                        put("content_truncated", 0)
                        put("target_content_hash", cursor.getString(4))
                        put("target_epoch", now)
                        put("created_at", now)
                        put("updated_at", now)
                    })
                }
            }
        }
    }

    private fun mappingDigest(mappings: Map<ColumnKey, Map<String, String>>): String = recoverySha256(
        mappings.entries.sortedBy { "${it.key.table}.${it.key.column}" }.flatMap { (key, values) ->
            values.entries.sortedBy(Map.Entry<String, String>::key).map { "${key.table}.${key.column}\t${it.key}\t${it.value}" }
        }.joinToString("\n").toByteArray(Charsets.UTF_8),
    )

    private fun columns(db: SQLiteDatabase, schema: String, table: String): List<String> =
        db.rawQuery("PRAGMA $schema.table_info(`${escape(table)}`)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun rowCount(db: SQLiteDatabase, schema: String, table: String): Int =
        if (columns(db, schema, table).isEmpty()) 0 else
            db.rawQuery("SELECT COUNT(*) FROM $schema.`${escape(table)}`", null).use { cursor ->
                check(cursor.moveToFirst()); cursor.getInt(0)
            }

    private fun rowExists(db: SQLiteDatabase, sql: String, args: Array<String>): Boolean =
        db.rawQuery(sql, args).use { it.moveToFirst() }

    private fun primaryKeyColumns(db: SQLiteDatabase, schema: String, table: String): List<String> =
        db.rawQuery("PRAGMA $schema.table_info(`${escape(table)}`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) if (cursor.getInt(cursor.getColumnIndexOrThrow("pk")) > 0) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }

    private fun foreignColumns(db: SQLiteDatabase, table: String): Map<String, ColumnKey> =
        db.rawQuery("PRAGMA main.foreign_key_list(`${escape(table)}`)", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(
                    cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    ColumnKey(
                        cursor.getString(cursor.getColumnIndexOrThrow("table")),
                        cursor.getString(cursor.getColumnIndexOrThrow("to")),
                    ),
                )
            }
        }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    private fun put(values: ContentValues, column: String, value: Any?) = when (value) {
        null -> values.putNull(column)
        is String -> values.put(column, value)
        is Long -> values.put(column, value)
        is Double -> values.put(column, value)
        is ByteArray -> values.put(column, value)
        else -> error("不支持的SQLite值类型")
    }

    private fun escape(identifier: String): String {
        require(identifier.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
            "SQLite标识符不属于受控ASCII集合: $identifier"
        }
        return identifier
    }

    private data class ColumnKey(val table: String, val column: String)

    private companion object {
        val COPY_ORDER = listOf(
            "agents", "custom_skills", "mcp_servers", "mcp_tool_snapshots", "sessions", "messages",
            "attachments", "vectors", "context_summaries", "tags", "document_tags", "workspace_files",
            "kg_collections", "kg_folder_settings", "kg_collection_members", "kg_nodes", "kg_edges", "kg_evidence",
            "audit_logs", "artifacts", "task_nodes", "tool_execution_ledger",
            "file_versions", "vectorization_tasks",
        )
        val EXPLICIT_REFERENCES = mapOf(
            ColumnKey("sessions", "agent_id") to ColumnKey("agents", "id"),
            ColumnKey("mcp_tool_snapshots", "server_id") to ColumnKey("mcp_servers", "id"),
            ColumnKey("sessions", "workspace_root_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("sessions", "active_task_tree_id") to ColumnKey("task_nodes", "id"),
            ColumnKey("messages", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("messages", "parent_message_id") to ColumnKey("messages", "id"),
            ColumnKey("attachments", "message_id") to ColumnKey("messages", "id"),
            ColumnKey("vectors", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("vectors", "file_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("vectors", "start_message_id") to ColumnKey("messages", "id"),
            ColumnKey("vectors", "end_message_id") to ColumnKey("messages", "id"),
            ColumnKey("context_summaries", "start_message_id") to ColumnKey("messages", "id"),
            ColumnKey("context_summaries", "end_message_id") to ColumnKey("messages", "id"),
            ColumnKey("context_summaries", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("kg_nodes", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("kg_nodes", "agent_id") to ColumnKey("agents", "id"),
            ColumnKey("kg_nodes", "file_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("kg_edges", "doc_id") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("kg_edges", "source_id") to ColumnKey("kg_nodes", "id"),
            ColumnKey("kg_edges", "target_id") to ColumnKey("kg_nodes", "id"),
            ColumnKey("kg_edges", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("kg_edges", "agent_id") to ColumnKey("agents", "id"),
            ColumnKey("kg_edges", "file_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("kg_evidence", "node_id") to ColumnKey("kg_nodes", "id"),
            ColumnKey("kg_evidence", "edge_id") to ColumnKey("kg_edges", "id"),
            ColumnKey("kg_evidence", "doc_id") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("kg_evidence", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("kg_evidence", "message_id") to ColumnKey("messages", "id"),
            ColumnKey("vectorization_tasks", "user_message_id") to ColumnKey("messages", "id"),
            ColumnKey("vectorization_tasks", "assistant_message_id") to ColumnKey("messages", "id"),
            ColumnKey("vectorization_tasks", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("vectorization_tasks", "workspace_root_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("audit_logs", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("audit_logs", "agent_id") to ColumnKey("agents", "id"),
            ColumnKey("audit_logs", "skill_id") to ColumnKey("custom_skills", "id"),
            ColumnKey("artifacts", "message_id") to ColumnKey("messages", "id"),
            ColumnKey("artifacts", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("workspace_files", "workspace_root_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("workspace_files", "parent_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("workspace_files", "original_parent_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("workspace_files", "last_write_session_id") to ColumnKey("sessions", "id"),
            ColumnKey("workspace_files", "locked_by_session_id") to ColumnKey("sessions", "id"),
            ColumnKey("task_nodes", "parent_id") to ColumnKey("task_nodes", "id"),
            ColumnKey("task_nodes", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("tool_execution_ledger", "session_id") to ColumnKey("sessions", "id"),
            ColumnKey("tool_execution_ledger", "assistant_message_id") to ColumnKey("messages", "id"),
            ColumnKey("tool_execution_ledger", "result_message_id") to ColumnKey("messages", "id"),
            ColumnKey("messages", "tool_call_id") to ColumnKey("tool_execution_ledger", "tool_call_id"),
            ColumnKey("file_versions", "file_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("file_versions", "workspace_root_uuid") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("file_versions", "created_by_session_id") to ColumnKey("sessions", "id"),
        )
        val RAG_DOCUMENT_KEY = ColumnKey("rag_documents", "id")
        val JSON_ARRAY_REFERENCES = mapOf(
            ColumnKey("agents", "skill_ids") to ColumnKey("custom_skills", "id"),
            ColumnKey("agents", "mcp_server_ids") to ColumnKey("mcp_servers", "id"),
            ColumnKey("sessions", "active_mcp_server_ids") to ColumnKey("mcp_servers", "id"),
            ColumnKey("sessions", "active_skill_ids") to ColumnKey("custom_skills", "id"),
            ColumnKey("task_nodes", "artifact_file_uuids") to ColumnKey("workspace_files", "uuid"),
            ColumnKey("messages", "pending_approval_tool_ids") to ColumnKey("tool_execution_ledger", "tool_call_id"),
        )
        val STRUCTURED_REFERENCE_JSON = setOf(
            ColumnKey("messages", "rag_references"), ColumnKey("messages", "files"),
            ColumnKey("messages", "user_images"),
            ColumnKey("messages", "legacy_attachments"),
            ColumnKey("sessions", "rag_options"), ColumnKey("sessions", "approval_request"),
        )
        val TOOL_CALL_JSON = setOf(ColumnKey("messages", "tool_calls"), ColumnKey("messages", "tool_results"))
        val JSON_REFERENCE_KEYS = mapOf(
            "documentId" to ColumnKey("workspace_files", "uuid"),
            "document_id" to ColumnKey("workspace_files", "uuid"),
            "fileUuid" to ColumnKey("workspace_files", "uuid"),
            "file_uuid" to ColumnKey("workspace_files", "uuid"),
            "messageId" to ColumnKey("messages", "id"),
            "message_id" to ColumnKey("messages", "id"),
            "sessionId" to ColumnKey("sessions", "id"),
            "session_id" to ColumnKey("sessions", "id"),
            "assistantMessageId" to ColumnKey("messages", "id"),
            "assistant_message_id" to ColumnKey("messages", "id"),
            "resultMessageId" to ColumnKey("messages", "id"),
            "result_message_id" to ColumnKey("messages", "id"),
            "toolCallId" to ColumnKey("tool_execution_ledger", "tool_call_id"),
            "tool_call_id" to ColumnKey("tool_execution_ledger", "tool_call_id"),
        )
        val JSON_ARRAY_REFERENCE_KEYS = mapOf(
            "activeDocIds" to ColumnKey("workspace_files", "uuid"),
            "activeFolderIds" to ColumnKey("workspace_files", "uuid"),
        )
        val JSON_PATH_KEYS = setOf("uri", "localUri", "local_uri", "path", "workspacePath", "workspace_path")
        val NATURAL_PRIMARY_KEYS = setOf(ColumnKey("mcp_tool_snapshots", "remote_tool_name"))
        val TOOL_CALL_KEY = ColumnKey("tool_execution_ledger", "tool_call_id")
        val PATH_COLUMNS = setOf(
            ColumnKey("agents", "avatar_path"), ColumnKey("sessions", "workspace_path"),
            ColumnKey("attachments", "uri"), ColumnKey("attachments", "local_uri"),
            ColumnKey("artifacts", "workspace_path"),
            ColumnKey("workspace_files", "physical_root_path"), ColumnKey("file_versions", "content_path"),
        )
        val POSSIBLE_UNKNOWN_JSON = setOf("metadata", "content", "citations", "tool_calls", "tool_results")
    }
}
