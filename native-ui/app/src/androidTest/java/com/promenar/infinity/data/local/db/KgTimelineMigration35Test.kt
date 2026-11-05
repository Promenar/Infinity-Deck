package com.promenar.infinity.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.GraphRetrievalStore
import com.promenar.infinity.data.rag.KgRetrievalScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class KgTimelineMigration35Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InfinityDatabase::class.java)

    @Test fun migrationPreservesEdgesAndAssignsNaturalArticleOrder() {
        val name = "kg-timeline-34-35"
        helper.createDatabase(name, 34).apply {
            execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('work:p','work','作品',1)")
            listOf("第2章", "第10章", "第1章").forEach { id ->
                execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES(?,'root','p',?,'h',0,0,'/fixture',?,1,1,0,1,1)", arrayOf(id, id, "/$id"))
                execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES(?,'work:p')", arrayOf(id))
            }
            execSQL("INSERT INTO kg_nodes(id,name,type,created_at,normalized_name,collection_id) VALUES('a','甲','person',1,'甲','work:p'),('b','乙','person',1,'乙','work:p')")
            execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,created_at,normalized_relation,collection_id) VALUES('e','a','b','同盟',1,1,'同盟','work:p')")
            execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document,weight) VALUES('p','e','document','第1章','h','task',1,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 35, true, MIGRATION_34_35_TIMELINE).use { db ->
            db.query("SELECT doc_id,order_source FROM kg_collection_members ORDER BY order_key").use { c ->
                val ordered = buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                assertThat(ordered).containsExactly("第1章" to "natural", "第2章" to "natural", "第10章" to "natural").inOrder()
            }
            db.query("SELECT COUNT(*) FROM kg_edges").use { c -> check(c.moveToFirst()); assertThat(c.getInt(0)).isEqualTo(1) }
            db.query("SELECT COUNT(*) FROM kg_evidence").use { c -> check(c.moveToFirst()); assertThat(c.getInt(0)).isEqualTo(1) }
            db.query("SELECT COUNT(*) FROM kg_edge_mentions").use { c -> check(c.moveToFirst()); assertThat(c.getInt(0)).isEqualTo(0) }
            db.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        }
        val reopened = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, InfinityDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            runBlocking<Unit> {
                val paths = GraphRetrievalStore(reopened).retrieve("甲", KgRetrievalScope("s", listOf("第1章", "第2章", "第10章"), collectionId = "work:p")).paths
                assertThat(paths).hasSize(1)
                assertThat(paths.single().edges.single().relation).isEqualTo("同盟")
                assertThat(paths.single().reasoning).contains("位置未知")
            }
        } finally { reopened.close() }
    }
}
