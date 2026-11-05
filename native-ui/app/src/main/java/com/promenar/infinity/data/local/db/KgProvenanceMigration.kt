package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Room 在一个事务内升级；以 500 行主键游标分批读取，映射驻留 SQLite，内存不随图大小增长。
 * 每条旧边在重指向后为关系和端点补齐同源证据，避免首个节点归属吞掉其它来源。
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE kg_edges RENAME TO kg_edges_v18")
        db.execSQL("ALTER TABLE kg_nodes RENAME TO kg_nodes_v18")
        db.execSQL("DROP INDEX IF EXISTS index_kg_edges_source_id")
        db.execSQL("DROP INDEX IF EXISTS index_kg_edges_target_id")
        db.execSQL("DROP INDEX IF EXISTS index_kg_edges_doc_id")
        db.execSQL("CREATE TABLE kg_nodes (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, metadata TEXT, created_at INTEGER NOT NULL, updated_at INTEGER, normalized_name TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_kg_nodes_normalized_name_type ON kg_nodes(normalized_name,type)")
        db.execSQL("CREATE TABLE kg_edges (id TEXT NOT NULL PRIMARY KEY, source_id TEXT NOT NULL, target_id TEXT NOT NULL, relation TEXT NOT NULL, weight REAL NOT NULL, created_at INTEGER NOT NULL, normalized_relation TEXT NOT NULL, FOREIGN KEY(source_id) REFERENCES kg_nodes(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(target_id) REFERENCES kg_nodes(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX index_kg_edges_source_id ON kg_edges(source_id)")
        db.execSQL("CREATE INDEX index_kg_edges_target_id ON kg_edges(target_id)")
        db.execSQL("CREATE UNIQUE INDEX index_kg_edges_source_id_target_id_normalized_relation ON kg_edges(source_id,target_id,normalized_relation)")
        db.execSQL("CREATE TABLE kg_evidence (id TEXT NOT NULL PRIMARY KEY, node_id TEXT, edge_id TEXT, source_type TEXT NOT NULL, doc_id TEXT, session_id TEXT, content_hash TEXT NOT NULL, task_id TEXT NOT NULL, created_at INTEGER NOT NULL, workspace_document INTEGER NOT NULL, weight REAL, CHECK ((node_id IS NULL) != (edge_id IS NULL)), FOREIGN KEY(node_id) REFERENCES kg_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(edge_id) REFERENCES kg_edges(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        listOf("node_id", "edge_id", "doc_id", "session_id", "task_id").forEach {
            db.execSQL("CREATE INDEX index_kg_evidence_$it ON kg_evidence($it)")
        }
        db.execSQL("CREATE TABLE kg_jit_cache_sources (cache_key TEXT NOT NULL, doc_id TEXT NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY(cache_key,doc_id), FOREIGN KEY(cache_key) REFERENCES kg_jit_cache(cache_key) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_kg_jit_cache_sources_doc_id ON kg_jit_cache_sources(doc_id)")
        // 历史缓存没有可核验的配置与文档依赖，作为派生缓存失效，不影响旧图证据。
        db.execSQL("DELETE FROM kg_jit_cache")
        db.execSQL("CREATE TABLE kg_migration_node_map (old_id TEXT NOT NULL PRIMARY KEY, new_id TEXT NOT NULL)")
        migrateNodes(db)
        migrateEdges(db)
        // 既无归属又无任何边可推断来源的历史节点，保留到用户手动清理。
        db.execSQL("INSERT INTO kg_evidence(id,node_id,edge_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document) SELECT 'legacy-node:' || n.id,n.id,NULL,'legacy',NULL,NULL,'','migration-v18',n.created_at,0 FROM kg_nodes n WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id=n.id)")
        db.execSQL("DROP TABLE kg_migration_node_map")
        db.execSQL("DROP TABLE kg_edges_v18")
        db.execSQL("DROP TABLE kg_nodes_v18")
        db.execSQL("ALTER TABLE workspace_files ADD COLUMN kg_strategy TEXT")
        db.execSQL("UPDATE workspace_files SET kg_strategy = COALESCE((SELECT kg_strategy FROM vectorization_tasks t WHERE t.doc_id=workspace_files.uuid AND t.kg_strategy IS NOT NULL ORDER BY t.updated_at DESC LIMIT 1),'full') WHERE kg_extracted_at IS NOT NULL")
    }

    private fun migrateNodes(db: SupportSQLiteDatabase) {
        batches(db, "kg_nodes_v18") { cursor ->
            val id = cursor.text("id")!!
            val name = cursor.text("name").orEmpty()
            val type = KgIdentity.normalize(cursor.text("type").orEmpty()).ifBlank { "concept" }
            val normalized = KgIdentity.normalize(name)
            val created = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            db.execSQL("INSERT OR IGNORE INTO kg_nodes(id,name,type,metadata,created_at,updated_at,normalized_name) VALUES(?,?,?,?,?,?,?)",
                arrayOf(id, KgIdentity.display(name), type, cursor.text("metadata"), created, cursor.text("updated_at")?.toLongOrNull(), normalized))
            val mapped = db.string("SELECT id FROM kg_nodes WHERE normalized_name=? AND type=?", normalized, type)!!
            if (mapped != id) {
                val previous = db.string("SELECT metadata FROM kg_nodes WHERE id=?", mapped)
                val incoming = cursor.text("metadata")
                fun metadataObject(value: String?) =
                    runCatching { Json.parseToJsonElement(value.orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                val merged = JsonObject(metadataObject(previous) + metadataObject(incoming)).toString()
                db.execSQL("UPDATE kg_nodes SET metadata=? WHERE id=?", arrayOf(merged, mapped))
            }
            db.execSQL("INSERT INTO kg_migration_node_map(old_id,new_id) VALUES(?,?)", arrayOf(id, mapped))
            val doc = cursor.text("file_uuid")
            val session = cursor.text("session_id")
            if (doc != null || session != null) addEvidence(db, mapped, null, doc, session, cursor.text("source_type"), "node:$id", created)
        }
    }

    private fun migrateEdges(db: SupportSQLiteDatabase) {
        batches(db, "kg_edges_v18") { cursor ->
            val id = cursor.text("id")!!
            val source = db.string("SELECT new_id FROM kg_migration_node_map WHERE old_id=?", cursor.text("source_id").orEmpty())
            val target = db.string("SELECT new_id FROM kg_migration_node_map WHERE old_id=?", cursor.text("target_id").orEmpty())
            // 旧库外键一致性由启动入口校验；异常悬挂边不能编造端点。
            if (source != null && target != null) {
                val relation = cursor.text("relation").orEmpty()
                val normalized = KgIdentity.normalize(relation)
                val weight = cursor.getDouble(cursor.getColumnIndexOrThrow("weight")).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
                val created = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                db.execSQL("INSERT OR IGNORE INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(id, source, target, KgIdentity.display(relation), weight, created, normalized))
                val mapped = db.string("SELECT id FROM kg_edges WHERE source_id=? AND target_id=? AND normalized_relation=?", source, target, normalized)!!
                db.execSQL("UPDATE kg_edges SET weight=MAX(weight,?),created_at=MIN(created_at,?) WHERE id=?", arrayOf(weight, created, mapped))
                val doc = cursor.text("file_uuid") ?: cursor.text("doc_id")
                val session = cursor.text("session_id")
                listOf(source, target).distinct().forEach { addEvidence(db, it, null, doc, session, cursor.text("source_type"), "edge:$id", created) }
                addEvidence(db, null, mapped, doc, session, cursor.text("source_type"), "edge:$id", created, weight)
            }
        }
    }

    private fun addEvidence(db: SupportSQLiteDatabase, node: String?, edge: String?, doc: String?, session: String?, oldType: String?, task: String, time: Long, weight: Double? = null) {
        val hash = doc?.let { db.string("SELECT hash FROM workspace_files WHERE uuid=?", it) }
        val type = when { oldType == "jit" && (doc != null || session != null) -> "jit"; hash != null -> "document"; doc != null -> "legacy"; session != null -> "session"; else -> "legacy" }
        val identity = listOf(node, edge, doc, session, task).joinToString("\u0000") { it.orEmpty() }
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        db.execSQL("INSERT OR IGNORE INTO kg_evidence(id,node_id,edge_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document,weight) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, node, edge, type, doc, session, hash.orEmpty(), "migration-v18:$task", time, if (hash != null) 1 else 0, weight))
    }

    private fun batches(db: SupportSQLiteDatabase, table: String, consume: (android.database.Cursor) -> Unit) {
        var last: String? = null
        while (true) {
            var count = 0
            val query = if (last == null) "SELECT * FROM $table ORDER BY id LIMIT 500" else "SELECT * FROM $table WHERE id > ? ORDER BY id LIMIT 500"
            val args: Array<out Any?> = if (last == null) emptyArray() else arrayOf(last)
            db.query(query, args).use { cursor ->
                while (cursor.moveToNext()) { last = cursor.text("id"); consume(cursor); count++ }
            }
            if (count < 500) return
        }
    }

    private fun android.database.Cursor.text(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun SupportSQLiteDatabase.string(sql: String, vararg args: String): String? = query(sql, args).use { if (it.moveToFirst()) it.getString(0) else null }
}
