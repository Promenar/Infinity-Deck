package com.promenar.infinity.data.local.db.recovery

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class DualDatabaseGraphMergerTest {
    @Test
    fun `新版图谱恢复时重映射作品会话绑定与证据身份`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val current = context.getDatabasePath("kg34-current-${System.nanoTime()}.db").toPath()
        val legacy = context.getDatabasePath("kg34-legacy-${System.nanoTime()}.db").toPath()
        try {
            for (path in listOf(current, legacy)) {
                createSchema(path)
                SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    listOf(
                        "CREATE TABLE kg_collections(id TEXT PRIMARY KEY,kind TEXT,title TEXT,root_ref TEXT,created_at INTEGER)",
                        "CREATE TABLE kg_folder_settings(folder_id TEXT PRIMARY KEY,mode TEXT)",
                        "CREATE TABLE kg_collection_members(doc_id TEXT PRIMARY KEY,collection_id TEXT,order_key TEXT,order_source TEXT)",
                        "CREATE TABLE kg_nodes(id TEXT PRIMARY KEY,name TEXT,type TEXT,metadata TEXT,created_at INTEGER,updated_at INTEGER,normalized_name TEXT,collection_id TEXT)",
                        "CREATE UNIQUE INDEX kg_node_identity ON kg_nodes(collection_id,normalized_name,type)",
                        "CREATE TABLE kg_edges(id TEXT PRIMARY KEY,source_id TEXT,target_id TEXT,relation TEXT,weight REAL,created_at INTEGER,normalized_relation TEXT,collection_id TEXT)",
                        "CREATE TABLE kg_evidence(id TEXT PRIMARY KEY,node_id TEXT,edge_id TEXT,source_type TEXT,doc_id TEXT,session_id TEXT,content_hash TEXT,task_id TEXT,created_at INTEGER,workspace_document INTEGER,weight REAL,message_id TEXT)",
                        "CREATE TABLE kg_jit_cache_sources(cache_key TEXT,doc_id TEXT,content_hash TEXT)",
                    ).forEach(db::execSQL)
                    seed(db, if (path == current) "current" else "legacy")
                    db.execSQL("INSERT INTO kg_collections VALUES('work:file','work','作品','file',1)")
                    db.execSQL("INSERT INTO kg_collection_members VALUES('file','work:file',NULL,NULL)")
                    db.execSQL("INSERT INTO kg_folder_settings VALUES('file','DOC_COLLECTION:work:file')")
                    db.execSQL("INSERT INTO kg_nodes VALUES('node','同名','person','{}',1,1,'同名','work:file')")
                    db.execSQL("INSERT INTO kg_evidence VALUES('evidence','node',NULL,'document','file',NULL,'h','task',1,1,NULL,NULL)")
                    db.execSQL("UPDATE sessions SET rag_options='{\"graphBinding\":{\"collectionId\":\"work:file\",\"mode\":\"READ_ONLY\"}}' WHERE id='same-session'")
                }
            }
            DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value }).merge(current, legacy, "kg34")
            SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val mappedFile = RecoveryStableId.map("workspace_files", "uuid", "file")
                val mappedSession = RecoveryStableId.map("sessions", "id", "same-session")
                assertThat(count(db, "kg_nodes")).isEqualTo(2)
                assertThat(count(db, "kg_collections")).isEqualTo(2)
                db.rawQuery("SELECT mode FROM kg_folder_settings WHERE folder_id=?", arrayOf(mappedFile)).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("DOC_COLLECTION:work:$mappedFile")
                }
                db.rawQuery("SELECT n.collection_id,p.id,p.node_id,p.edge_id,p.source_type,p.doc_id,p.session_id,p.content_hash,p.task_id,p.message_id FROM kg_evidence p JOIN kg_nodes n ON n.id=p.node_id WHERE p.doc_id=?", arrayOf(mappedFile)).use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo("work:$mappedFile")
                    val parts = (2..8).map { if (cursor.isNull(it)) "" else cursor.getString(it) } + if (cursor.isNull(9)) emptyList() else listOf(cursor.getString(9))
                    val key = Json.encodeToString(ListSerializer(String.serializer()), parts)
                    val expected = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertThat(cursor.getString(1)).isEqualTo(expected)
                }
                db.rawQuery("SELECT rag_options FROM sessions WHERE id=?", arrayOf(mappedSession)).use { cursor ->
                    check(cursor.moveToFirst()); assertThat(cursor.getString(0)).contains("work:$mappedFile")
                }
            }
        } finally { Files.deleteIfExists(current); Files.deleteIfExists(legacy) }
    }

    @Test
    fun `恢复工作区把持久nonce与inode回执绑定到唯一Room根`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val current = context.getDatabasePath("identity-current-${System.nanoTime()}.db").toPath()
        val legacy = context.getDatabasePath("identity-legacy-${System.nanoTime()}.db").toPath()
        try {
            createSchema(current)
            createSchema(legacy)
            SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { seed(it, "current") }
            SQLiteDatabase.openDatabase(legacy.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { seed(it, "legacy") }
            val identity = "nx1:11111111-2222-4333-8444-555555555555|" + "a".repeat(64)
            DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value }, mapOf("/legacy" to identity))
                .merge(current, legacy, "root-identity")
            SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT hash FROM workspace_files WHERE physical_root_path=? AND parent_uuid IS NULL", arrayOf("/legacy")).use {
                    assertThat(it.moveToFirst()).isTrue()
                    assertThat(it.getString(0)).isEqualTo(identity)
                    assertThat(it.moveToNext()).isFalse()
                }
            }
        } finally { Files.deleteIfExists(current); Files.deleteIfExists(legacy) }
    }

    @Test
    fun `历史非工作区KG文档及关系随恢复保留`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val current = context.getDatabasePath("kg-current-${System.nanoTime()}.db").toPath()
        val legacy = context.getDatabasePath("kg-legacy-${System.nanoTime()}.db").toPath()
        createSchema(current)
        createSchema(legacy)
        listOf(current, legacy).forEach { path ->
            SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE kg_nodes(id TEXT PRIMARY KEY, name TEXT)")
                db.execSQL("CREATE TABLE kg_edges(id TEXT PRIMARY KEY, source_id TEXT, target_id TEXT, doc_id TEXT)")
            }
        }
        SQLiteDatabase.openDatabase(legacy.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            seed(db, "legacy")
            db.execSQL("INSERT INTO kg_nodes VALUES('a','Alice'),('b','Bob')")
            db.execSQL("INSERT INTO kg_edges VALUES('e','a','b','historical-external-document')")
        }
        val result = DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value })
            .merge(current, legacy, "kg-external-document")
        SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertThat(count(db, "messages")).isEqualTo(2)
            assertThat(count(db, "kg_edges")).isEqualTo(1)
            db.rawQuery("SELECT doc_id FROM kg_edges", null).use {
                check(it.moveToFirst())
                assertThat(it.getString(0)).isEqualTo(RecoveryStableId.map("rag_documents", "id", "historical-external-document"))
            }
        }
        assertThat(result.unresolvedReferences.any { it.startsWith("discarded-derived:kg") }).isFalse()
    }

    @Test
    fun `same ids remain distinct and all known graph references follow legacy mapping`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val current = context.getDatabasePath("merge-current-${System.nanoTime()}.db").toPath()
        val legacy = context.getDatabasePath("merge-legacy-${System.nanoTime()}.db").toPath()
        createSchema(current)
        createSchema(legacy)
        SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            seed(db, "current")
        }
        SQLiteDatabase.openDatabase(legacy.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            seed(db, "legacy")
        }

        val result = DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value })
            .merge(current, legacy, "test-transaction")

        SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertThat(count(db, "sessions")).isEqualTo(2)
            assertThat(count(db, "messages")).isEqualTo(4)
            val legacySession = RecoveryStableId.map("sessions", "id", "same-session")
            val legacyParent = RecoveryStableId.map("messages", "id", "parent")
            db.rawQuery("SELECT parent_message_id FROM messages WHERE id=?", arrayOf(RecoveryStableId.map("messages", "id", "child"))).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(legacyParent)
            }
            db.rawQuery("SELECT session_id FROM messages WHERE id=?", arrayOf(legacyParent)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(legacySession)
            }
            db.rawQuery("SELECT file_uuid,workspace_root_uuid,created_by_session_id FROM file_versions WHERE id=?",
                arrayOf(RecoveryStableId.map("file_versions", "id", "version"))).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(RecoveryStableId.map("workspace_files", "uuid", "file"))
                assertThat(it.getString(1)).isEqualTo(RecoveryStableId.map("workspace_files", "uuid", "root"))
                assertThat(it.getString(2)).isEqualTo(legacySession)
            }
            db.rawQuery("SELECT status FROM vectorization_tasks ORDER BY id", null).use { cursor ->
                val statuses = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(statuses).hasSize(4)
                assertThat(statuses.toSet()).containsExactly("failed")
            }
            db.rawQuery("SELECT COUNT(*) FROM vectorization_tasks WHERE sub_status=?", arrayOf(DUAL_DATABASE_RECOVERY_INDEX_HOLD)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(2)
            }
            db.rawQuery("SELECT status FROM tool_execution_ledger ORDER BY session_id", null).use { cursor ->
                assertThat(buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }).containsExactly("FAILED", "FAILED")
            }
            db.rawQuery("SELECT images FROM messages WHERE id=?", arrayOf(legacyParent)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("data:image/png;base64,AA==")
            }
            assertThat(count(db, "kg_jit_cache")).isEqualTo(0)
        }
        assertThat(result.mappingCount).isGreaterThan(0)
        assertThat(result.unresolvedReferences.filter { it.startsWith("required:") }).isEmpty()
        Files.deleteIfExists(current); Files.deleteIfExists(legacy)
    }

    private fun createSchema(path: java.nio.file.Path) {
        Files.createDirectories(path.parent)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { db ->
            listOf(
                "CREATE TABLE agents(id TEXT PRIMARY KEY, skill_ids TEXT, mcp_server_ids TEXT)",
                "CREATE TABLE custom_skills(id TEXT PRIMARY KEY)",
                "CREATE TABLE mcp_servers(id TEXT PRIMARY KEY)",
                "CREATE TABLE sessions(id TEXT PRIMARY KEY, agent_id TEXT, active_skill_ids TEXT, active_mcp_server_ids TEXT, workspace_root_uuid TEXT, active_task_tree_id TEXT, workspace_path TEXT, approval_request TEXT, pending_intervention TEXT, loop_status TEXT NOT NULL, rag_options TEXT)",
                "CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, parent_message_id TEXT, rag_references TEXT, content TEXT, images TEXT, FOREIGN KEY(session_id) REFERENCES sessions(id))",
                "CREATE TABLE attachments(id TEXT PRIMARY KEY, message_id TEXT)",
                "CREATE TABLE vectors(id TEXT PRIMARY KEY, session_id TEXT, content TEXT, doc_id TEXT, file_uuid TEXT, start_message_id TEXT, end_message_id TEXT)",
                "CREATE VIRTUAL TABLE vectors_fts USING fts4(content)",
                "CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, workspace_root_uuid TEXT, parent_uuid TEXT, last_write_session_id TEXT, locked_by_session_id TEXT, original_parent_uuid TEXT, physical_root_path TEXT, name TEXT NOT NULL, mime_type TEXT, hash TEXT NOT NULL, is_directory INTEGER NOT NULL, in_recycle_bin INTEGER NOT NULL, vectorized_at INTEGER, updated_at INTEGER NOT NULL)",
                "CREATE TABLE task_nodes(id TEXT PRIMARY KEY, session_id TEXT, parent_id TEXT, artifact_file_uuids TEXT)",
                "CREATE TABLE file_versions(id TEXT PRIMARY KEY, file_uuid TEXT, workspace_root_uuid TEXT, created_by_session_id TEXT, content_path TEXT)",
                "CREATE TABLE workspace_seq(date_key TEXT PRIMARY KEY,last_seq INTEGER NOT NULL)",
                "CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY,type TEXT NOT NULL,status TEXT NOT NULL,doc_id TEXT,doc_title TEXT,workspace_root_uuid TEXT,last_chunk_index INTEGER NOT NULL,progress REAL NOT NULL,error TEXT,skip_vectorization INTEGER NOT NULL,sub_status TEXT,source_mime_type TEXT,content_truncated INTEGER NOT NULL,target_content_hash TEXT,target_epoch INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)",
                "CREATE TABLE tool_execution_ledger(session_id TEXT,assistant_message_id TEXT,tool_call_id TEXT,status TEXT,error TEXT,PRIMARY KEY(session_id,assistant_message_id,tool_call_id))",
                "CREATE TABLE kg_jit_cache(cache_key TEXT PRIMARY KEY,payload TEXT)",
                "CREATE TABLE workspace_mutations(operation_id TEXT PRIMARY KEY)",
            ).forEach(db::execSQL)
        }
    }

    private fun seed(db: SQLiteDatabase, label: String) {
        db.execSQL("INSERT INTO agents VALUES('same-agent','[]','[]')")
        db.execSQL("INSERT INTO sessions VALUES('same-session','same-agent','[]','[]','root','task',?,NULL,NULL,'running','{\"activeDocIds\":[\"file\"],\"activeFolderIds\":[\"root\"]}')", arrayOf("/$label"))
        db.execSQL("INSERT INTO messages VALUES('parent','same-session',NULL,'[]',?,'data:image/png;base64,AA==')", arrayOf("$label parent"))
        db.execSQL("INSERT INTO messages VALUES('child','same-session','parent','[]',?,NULL)", arrayOf("$label child"))
        db.execSQL("INSERT INTO workspace_files VALUES('root','root',NULL,'same-session',NULL,NULL,?,'root',NULL,'root-hash',1,0,NULL,1)", arrayOf("/$label"))
        db.execSQL("INSERT INTO workspace_files VALUES('file','root','root','same-session',NULL,NULL,?,'file.txt','text/plain','file-hash',0,0,NULL,2)", arrayOf("/$label"))
        db.execSQL("INSERT INTO task_nodes VALUES('task','same-session',NULL,'[\"file\"]')")
        db.execSQL("INSERT INTO file_versions VALUES('version','file','root','same-session',?)", arrayOf("/$label/version"))
        db.execSQL("INSERT INTO workspace_seq VALUES('2026-09-22',1)")
        db.execSQL("INSERT INTO vectorization_tasks VALUES('index','memory','pending',NULL,NULL,NULL,0,1,NULL,0,NULL,NULL,0,NULL,0,1,1)")
        db.execSQL("INSERT INTO tool_execution_ledger VALUES('same-session','parent','tool','RUNNING',NULL)")
        db.execSQL("INSERT INTO kg_jit_cache VALUES(?,?)", arrayOf("cache-$label", label))
    }

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst(); it.getInt(0)
    }
}
