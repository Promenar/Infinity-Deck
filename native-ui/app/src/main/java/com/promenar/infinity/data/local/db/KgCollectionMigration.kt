package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 将 v33 的来源拆入独立图谱；临时映射留在 SQLite，不将整库载入内存。 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS kg_collections (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, root_ref TEXT, created_at INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('shared','shared','通用知识',NULL,0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS kg_folder_settings (folder_id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS kg_collection_members (doc_id TEXT NOT NULL PRIMARY KEY, collection_id TEXT NOT NULL, order_key TEXT, order_source TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_collection_members_collection_id ON kg_collection_members(collection_id)")

        // 工作区根本身不是作品文件夹；不在工作区内的历史文档按文档 ID 建图。
        db.execSQL("""CREATE TEMP TABLE kg_migration_documents AS
            SELECT DISTINCT p.doc_id AS doc_id,
                CASE WHEN parent.uuid IS NOT NULL AND parent.is_directory=1 AND parent.uuid!=w.workspace_root_uuid
                    THEN parent.uuid ELSE p.doc_id END AS root_ref,
                CASE WHEN parent.uuid IS NOT NULL AND parent.is_directory=1 AND parent.uuid!=w.workspace_root_uuid
                    THEN parent.name ELSE COALESCE(w.name,p.doc_id) END AS title
            FROM kg_evidence p LEFT JOIN workspace_files w ON w.uuid=p.doc_id AND w.is_directory=0
            LEFT JOIN workspace_files parent ON parent.uuid=w.parent_uuid
            WHERE p.doc_id IS NOT NULL AND (p.source_type IN ('document','legacy') OR (p.source_type='jit' AND p.session_id IS NULL))""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX kg_migration_documents_id ON kg_migration_documents(doc_id)")
        db.execSQL("""CREATE TEMP TABLE kg_migration_targets AS
            SELECT p.id AS evidence_id,
                CASE WHEN p.source_type IN ('session','jit') AND p.session_id IS NOT NULL THEN 'session:' || p.session_id
                    WHEN p.doc_id IS NOT NULL THEN 'work:' || COALESCE(d.root_ref,p.doc_id)
                    WHEN p.session_id IS NOT NULL THEN 'session:' || p.session_id
                    ELSE 'shared' END AS collection_id
            FROM kg_evidence p LEFT JOIN kg_migration_documents d ON d.doc_id=p.doc_id""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX kg_migration_targets_id ON kg_migration_targets(evidence_id)")
        db.execSQL("""INSERT OR IGNORE INTO kg_collections(id,kind,title,root_ref,created_at)
            SELECT 'work:' || root_ref,'work',MIN(title),root_ref,0 FROM kg_migration_documents GROUP BY root_ref""".trimIndent())
        db.execSQL("""INSERT OR IGNORE INTO kg_collections(id,kind,title,root_ref,created_at)
            SELECT DISTINCT t.collection_id,CASE WHEN t.collection_id='shared' THEN 'shared' WHEN t.collection_id LIKE 'session:%' THEN 'session' ELSE 'work' END,
                CASE WHEN t.collection_id='shared' THEN '通用知识' WHEN p.session_id IS NOT NULL THEN COALESCE(s.title,p.session_id) ELSE COALESCE(p.doc_id,t.collection_id) END,
                CASE WHEN t.collection_id LIKE 'work:%' THEN p.doc_id ELSE NULL END,0
            FROM kg_migration_targets t JOIN kg_evidence p ON p.id=t.evidence_id LEFT JOIN sessions s ON s.id=p.session_id""".trimIndent())
        db.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) SELECT doc_id,'work:' || root_ref FROM kg_migration_documents")

        db.execSQL("CREATE TEMP TABLE kg_migration_node_groups (old_id TEXT NOT NULL, collection_id TEXT NOT NULL, PRIMARY KEY(old_id,collection_id))")
        db.execSQL("INSERT OR IGNORE INTO kg_migration_node_groups SELECT p.node_id,t.collection_id FROM kg_evidence p JOIN kg_migration_targets t ON t.evidence_id=p.id WHERE p.node_id IS NOT NULL")
        db.execSQL("INSERT OR IGNORE INTO kg_migration_node_groups SELECT e.source_id,t.collection_id FROM kg_evidence p JOIN kg_migration_targets t ON t.evidence_id=p.id JOIN kg_edges e ON e.id=p.edge_id")
        db.execSQL("INSERT OR IGNORE INTO kg_migration_node_groups SELECT e.target_id,t.collection_id FROM kg_evidence p JOIN kg_migration_targets t ON t.evidence_id=p.id JOIN kg_edges e ON e.id=p.edge_id")
        db.execSQL("""CREATE TEMP TABLE kg_migration_node_map AS SELECT g.old_id,g.collection_id,
            CASE WHEN g.collection_id=(SELECT MIN(x.collection_id) FROM kg_migration_node_groups x WHERE x.old_id=g.old_id)
                THEN g.old_id ELSE 'copy:' || lower(hex(randomblob(16))) END AS new_id FROM kg_migration_node_groups g""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX kg_migration_node_map_key ON kg_migration_node_map(old_id,collection_id)")
        db.execSQL("CREATE TABLE kg_nodes_new (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, metadata TEXT, created_at INTEGER NOT NULL, updated_at INTEGER, normalized_name TEXT NOT NULL, collection_id TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_kg_nodes_collection_id_id ON kg_nodes_new(collection_id,id)")
        db.execSQL("""INSERT INTO kg_nodes_new SELECT m.new_id,n.name,n.type,n.metadata,n.created_at,n.updated_at,n.normalized_name,m.collection_id
            FROM kg_migration_node_map m JOIN kg_nodes n ON n.id=m.old_id""".trimIndent())
        restoreV18SourceMetadata(db)

        db.execSQL("CREATE TEMP TABLE kg_migration_edge_groups (old_id TEXT NOT NULL, collection_id TEXT NOT NULL, PRIMARY KEY(old_id,collection_id))")
        db.execSQL("INSERT OR IGNORE INTO kg_migration_edge_groups SELECT p.edge_id,t.collection_id FROM kg_evidence p JOIN kg_migration_targets t ON t.evidence_id=p.id WHERE p.edge_id IS NOT NULL")
        db.execSQL("""CREATE TEMP TABLE kg_migration_edge_map AS SELECT g.old_id,g.collection_id,
            CASE WHEN g.collection_id=(SELECT MIN(x.collection_id) FROM kg_migration_edge_groups x WHERE x.old_id=g.old_id)
                THEN g.old_id ELSE 'copy:' || lower(hex(randomblob(16))) END AS new_id FROM kg_migration_edge_groups g""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX kg_migration_edge_map_key ON kg_migration_edge_map(old_id,collection_id)")
        db.execSQL("CREATE TABLE kg_edges_new (id TEXT NOT NULL PRIMARY KEY, source_id TEXT NOT NULL, target_id TEXT NOT NULL, relation TEXT NOT NULL, weight REAL NOT NULL, created_at INTEGER NOT NULL, normalized_relation TEXT NOT NULL, collection_id TEXT NOT NULL, FOREIGN KEY(collection_id,source_id) REFERENCES kg_nodes_new(collection_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(collection_id,target_id) REFERENCES kg_nodes_new(collection_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("""INSERT INTO kg_edges_new SELECT m.new_id,s.new_id,t.new_id,e.relation,e.weight,e.created_at,e.normalized_relation,m.collection_id
            FROM kg_migration_edge_map m JOIN kg_edges e ON e.id=m.old_id
            JOIN kg_migration_node_map s ON s.old_id=e.source_id AND s.collection_id=m.collection_id
            JOIN kg_migration_node_map t ON t.old_id=e.target_id AND t.collection_id=m.collection_id""".trimIndent())
        db.execSQL("CREATE TABLE kg_evidence_new (id TEXT NOT NULL PRIMARY KEY, node_id TEXT, edge_id TEXT, source_type TEXT NOT NULL, doc_id TEXT, session_id TEXT, content_hash TEXT NOT NULL, task_id TEXT NOT NULL, created_at INTEGER NOT NULL, workspace_document INTEGER NOT NULL, weight REAL, message_id TEXT, FOREIGN KEY(node_id) REFERENCES kg_nodes_new(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(edge_id) REFERENCES kg_edges_new(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        copyEvidence(db)

        db.execSQL("DROP TABLE kg_evidence")
        db.execSQL("DROP TABLE kg_edges")
        db.execSQL("DROP TABLE kg_nodes")
        db.execSQL("ALTER TABLE kg_nodes_new RENAME TO kg_nodes")
        db.execSQL("ALTER TABLE kg_edges_new RENAME TO kg_edges")
        db.execSQL("ALTER TABLE kg_evidence_new RENAME TO kg_evidence")
        db.execSQL("CREATE UNIQUE INDEX index_kg_nodes_collection_id_normalized_name_type ON kg_nodes(collection_id,normalized_name,type)")
        db.execSQL("CREATE INDEX index_kg_edges_source_id ON kg_edges(source_id)")
        db.execSQL("CREATE INDEX index_kg_edges_target_id ON kg_edges(target_id)")
        db.execSQL("CREATE INDEX index_kg_edges_collection_id_target_id ON kg_edges(collection_id,target_id)")
        db.execSQL("CREATE UNIQUE INDEX index_kg_edges_collection_id_source_id_target_id_normalized_relation ON kg_edges(collection_id,source_id,target_id,normalized_relation)")
        listOf("node_id", "edge_id", "doc_id", "session_id", "task_id", "message_id").forEach { column ->
            db.execSQL("CREATE INDEX index_kg_evidence_$column ON kg_evidence($column)")
        }
        // 旧缓存键不含图谱，无法安全复用；证据本身保留。
        db.execSQL("DELETE FROM kg_jit_cache")
        listOf("kg_migration_documents", "kg_migration_targets", "kg_migration_node_groups", "kg_migration_node_map", "kg_migration_edge_groups", "kg_migration_edge_map").forEach { db.execSQL("DROP TABLE $it") }
        listOf("kg_v18_node_metadata", "kg_v18_edge_nodes", "kg_v18_attached_nodes").forEach { db.execSQL("DROP TABLE IF EXISTS temp.$it") }
    }

    private fun restoreV18SourceMetadata(db: SupportSQLiteDatabase) {
        val hasV18Mapping = db.query("SELECT COUNT(*) FROM sqlite_temp_master WHERE type='table' AND name IN ('kg_v18_node_metadata','kg_v18_edge_nodes')").use {
            it.moveToFirst() && it.getInt(0) == 2
        }
        if (!hasV18Mapping) return
        // v18 的实体及关系证据使用原 ID 标记任务；只合并目标图谱对应的来源。
        val sql = """SELECT DISTINCT m.new_id,v.old_id,v.metadata
            FROM kg_migration_node_map m
            JOIN kg_v18_node_metadata v ON v.mapped_id=m.old_id
            JOIN kg_evidence p ON p.node_id=m.old_id
            JOIN kg_migration_targets t ON t.evidence_id=p.id AND t.collection_id=m.collection_id
            WHERE p.task_id='migration-v18:node:' || v.old_id
                OR (p.task_id LIKE 'migration-v18:edge:%' AND EXISTS (
                    SELECT 1 FROM kg_v18_edge_nodes x
                    WHERE x.old_id=substr(p.task_id,length('migration-v18:edge:')+1)
                        AND (v.old_id=x.source_old_id OR v.old_id=x.target_old_id)))
            ORDER BY m.new_id,v.old_id""".trimIndent()
        var currentId: String? = null
        var metadata: String? = null
        fun flush() {
            currentId?.let { db.execSQL("UPDATE kg_nodes_new SET metadata=? WHERE id=?", arrayOf<Any?>(metadata, it)) }
        }
        fun metadataObject(value: String?) =
            runCatching { Json.parseToJsonElement(value.orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val value = if (cursor.isNull(2)) null else cursor.getString(2)
                if (id != currentId) {
                    flush()
                    currentId = id
                    metadata = value
                } else {
                    metadata = JsonObject(metadataObject(metadata) + metadataObject(value)).toString()
                }
            }
        }
        flush()
    }

    private fun copyEvidence(db: SupportSQLiteDatabase) {
        var after: String? = null
        var count: Int
        do {
            count = 0
            val predicate = if (after == null) "" else "WHERE p.id>?"
            val sql = """SELECT p.id,n.new_id,e.new_id,p.source_type,p.doc_id,p.session_id,p.content_hash,p.task_id,p.created_at,p.workspace_document,p.weight,p.message_id
                FROM kg_evidence p JOIN kg_migration_targets t ON t.evidence_id=p.id
                LEFT JOIN kg_migration_node_map n ON n.old_id=p.node_id AND n.collection_id=t.collection_id
                LEFT JOIN kg_migration_edge_map e ON e.old_id=p.edge_id AND e.collection_id=t.collection_id
                $predicate ORDER BY p.id LIMIT 500""".trimIndent()
            val args: Array<out Any?> = after?.let { arrayOf(it) } ?: emptyArray()
            db.query(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    after = cursor.getString(0)
                    fun value(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
                    val node = value(1)
                    val edge = value(2)
                    check((node == null) != (edge == null)) { "图谱证据缺少可映射的实体或关系" }
                    val parts = listOf(node.orEmpty(), edge.orEmpty(), value(3).orEmpty(), value(4).orEmpty(), value(5).orEmpty(), value(6).orEmpty(), value(7).orEmpty()) + listOfNotNull(value(11))
                    val id = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(ListSerializer(String.serializer()), parts).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val weight = if (cursor.isNull(10)) null else cursor.getDouble(10)
                    db.execSQL("INSERT OR IGNORE INTO kg_evidence_new(id,node_id,edge_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document,weight,message_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(id, node, edge, value(3), value(4), value(5), value(6), value(7), cursor.getLong(8), cursor.getInt(9), weight, value(11)))
                    if (weight != null) db.execSQL("UPDATE kg_evidence_new SET weight=MAX(COALESCE(weight,0),?) WHERE id=?", arrayOf<Any?>(weight, id))
                    count++
                }
            }
        } while (count == 500)
    }
}
