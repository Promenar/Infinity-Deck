package com.promenar.infinity.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.KgScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test

/** 用真实 SQLite 检查旧证据的分区、端点映射与证据身份。 */
class KgCollectionsMigration34Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InfinityDatabase::class.java)

    @Test fun emptyDatabaseMigrationCreatesSharedCollection() {
        val name = "kg-collections-empty-33-34"
        helper.createDatabase(name, 33).close()
        helper.runMigrationsAndValidate(name, 34, true, MIGRATION_33_34).use { db ->
            db.query("SELECT id,kind FROM kg_collections").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("shared")
                assertThat(it.getString(1)).isEqualTo("shared")
                assertThat(it.moveToNext()).isFalse()
            }
        }
    }

    @Test fun migrationSplitsEvidenceWithoutLosingProvenance() {
        val name = "kg-collections-33-34"
        helper.createDatabase(name, 33).apply {
            execSQL("INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at) VALUES('s','a','会话',0,0,1,1)")
            execSQL("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('m','s','assistant','测试',1)")
            for ((id, parent, directory) in listOf(Triple("f1", null, 1), Triple("f2", null, 1), Triple("d1", "f1", 0), Triple("d2", "f2", 0))) {
                execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES(?,?,?,?,?,0,?,'/fixture','/fixture',1,1,0,1,1)", arrayOf(id, "root", parent, id, "h", directory))
            }
            execSQL("INSERT INTO kg_nodes(id,name,type,metadata,created_at,normalized_name) VALUES('a','同名','person','{}',1,'同名'),('b','目标','person','{}',1,'目标'),('orphan','无证据','concept',NULL,1,'无证据')")
            execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation) VALUES('e','a','b','认识',0.8,1,'认识')")
            val sources = listOf(
                listOf("document", "d1", null, null),
                listOf("document", "d2", null, null),
                listOf("session", null, "s", "m"),
                listOf("jit", "d1", "s", null),
                listOf("legacy", "historical", null, null),
                listOf("legacy", null, null, null),
            )
            sources.forEachIndexed { index, source ->
                execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document,message_id) VALUES(?,?,?,?,?,'h',?,1,0,?)", arrayOf("n$index", "a", source[0], source[1], source[2], "task-$index", source[3]))
                execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document,weight,message_id) VALUES(?,?,?,?,?,'h',?,1,0,0.8,?)", arrayOf("e$index", "e", source[0], source[1], source[2], "task-$index", source[3]))
            }
            close()
        }
        helper.runMigrationsAndValidate(name, 34, true, MIGRATION_33_34).use { db ->
            db.query("SELECT COUNT(*),COUNT(DISTINCT collection_id) FROM kg_nodes").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(10); assertThat(it.getInt(1)).isEqualTo(5)
            }
            db.query("SELECT COUNT(*) FROM kg_edges e JOIN kg_nodes s ON s.id=e.source_id JOIN kg_nodes t ON t.id=e.target_id WHERE e.collection_id!=s.collection_id OR e.collection_id!=t.collection_id").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(0)
            }
            val ids = db.query("SELECT id,collection_id FROM kg_nodes WHERE collection_id IN ('work:f1','work:f2') ORDER BY collection_id,id").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
            val first = ids.first { it.second == "work:f1" }.first
            val second = ids.first { it.second == "work:f2" }.first
            assertThat(runCatching {
                db.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation,collection_id) VALUES(?,?,?,'越界',1,1,'越界','work:f1')", arrayOf("invalid-edge", first, second))
            }.isFailure).isTrue()
            db.query("SELECT p.source_type,p.doc_id,p.session_id,n.collection_id FROM kg_evidence p JOIN kg_nodes n ON n.id=p.node_id").use {
                val seen = mutableSetOf<String>()
                while (it.moveToNext()) seen += "${it.getString(0)}:${it.getString(1)}:${it.getString(2)}:${it.getString(3)}"
                assertThat(seen).containsExactly(
                    "document:d1:null:work:f1", "document:d2:null:work:f2", "session:null:s:session:s",
                    "jit:d1:s:session:s", "legacy:historical:null:work:historical", "legacy:null:null:shared",
                )
            }
            db.query("SELECT id,node_id,edge_id,source_type,doc_id,session_id,content_hash,task_id,message_id FROM kg_evidence").use {
                var count = 0
                while (it.moveToNext()) {
                    val parts = (1..7).map { index -> if (it.isNull(index)) "" else it.getString(index) } +
                        if (it.isNull(8)) emptyList() else listOf(it.getString(8))
                    val key = Json.encodeToString(ListSerializer(String.serializer()), parts)
                    val expected = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
                    assertThat(it.getString(0)).isEqualTo(expected)
                    count++
                }
                assertThat(count).isEqualTo(12)
            }
            db.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        }
        val reopened = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, InfinityDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            runBlocking {
                GraphStore(reopened).upsertNode("同名", "person", scope = KgScope(docId = "d1", contentHash = "h", taskId = "task-0"), collectionId = "work:f1")
            }
            reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_evidence").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(12)
            }
        } finally { reopened.close() }
    }

    @Test fun migrationPreservesEveryEvidenceAcrossMultipleBatches() {
        val name = "kg-collections-33-34-batches"
        helper.createDatabase(name, 33).apply {
            beginTransaction()
            try {
                execSQL("INSERT INTO kg_nodes(id,name,type,created_at,normalized_name) VALUES('node','实体','concept',1,'实体')")
                repeat(1_501) { index ->
                    execSQL("INSERT INTO kg_evidence(id,node_id,source_type,content_hash,task_id,created_at,workspace_document) VALUES(?,'node','legacy','',?,1,0)",
                        arrayOf("e%04d".format(index), "task-$index"))
                }
                setTransactionSuccessful()
            } finally { endTransaction() }
            close()
        }
        helper.runMigrationsAndValidate(name, 34, true, MIGRATION_33_34).use { db ->
            db.query("SELECT COUNT(*),COUNT(DISTINCT task_id) FROM kg_evidence").use {
                check(it.moveToFirst())
                assertThat(it.getInt(0)).isEqualTo(1_501)
                assertThat(it.getInt(1)).isEqualTo(1_501)
            }
            db.query("SELECT COUNT(*) FROM kg_nodes WHERE collection_id='shared'").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(1)
            }
            db.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        }
    }
}
