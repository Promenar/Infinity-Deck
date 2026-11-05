package com.promenar.infinity.data.local.db.recovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.INFINITY_DATABASE_MIGRATIONS
import com.promenar.infinity.data.local.db.InfinityDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DualDatabaseV18GraphUpgradeTest {
    @Test fun `v18双库合并后升级按映射文档分割图谱`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val currentName = "recovery-current-${System.nanoTime()}.db"
        val legacyName = "recovery-legacy-${System.nanoTime()}.db"
        val current = context.getDatabasePath(currentName)
        val legacy = context.getDatabasePath(legacyName)
        try {
            createV18(current).use { seed(it, "current") }
            createV18(legacy).use { seed(it, "legacy") }
            val result = DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value })
                .merge(current.toPath(), legacy.toPath(), "v18-graph-upgrade")
            assertThat(result.unresolvedReferences.filter { it.startsWith("required:") }).isEmpty()

            val upgraded = Room.databaseBuilder(context, InfinityDatabase::class.java, currentName)
                .addMigrations(*INFINITY_DATABASE_MIGRATIONS).allowMainThreadQueries().build()
            try {
                val mappedDoc = RecoveryStableId.map("workspace_files", "uuid", "doc")
                upgraded.openHelper.readableDatabase.query(
                    "SELECT DISTINCT n.collection_id FROM kg_evidence p JOIN kg_nodes n ON n.id=p.node_id " +
                        "WHERE p.doc_id IN (?,?) ORDER BY n.collection_id", arrayOf("doc", mappedDoc),
                ).use { cursor ->
                    val collections = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    assertThat(collections).containsExactly("work:doc", "work:$mappedDoc")
                }
                upgraded.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_edges").use {
                    check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(2)
                }
                upgraded.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                    assertThat(it.count).isEqualTo(0)
                }
            } finally { upgraded.close() }
        } finally {
            context.deleteDatabase(currentName)
            context.deleteDatabase(legacyName)
        }
    }

    private fun createV18(file: File): SQLiteDatabase {
        val schema = Json.parseToJsonElement(
            File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/18.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        file.parentFile!!.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach {
                    execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { execSQL(it.jsonPrimitive.content) }
            version = 18
        }
    }

    private fun seed(db: SQLiteDatabase, label: String) {
        db.execSQL("INSERT INTO sessions(id,agent_id,title,unread,is_pinned,workspace_root_uuid,created_at,updated_at) VALUES('session','__system__',?,0,0,'root',1,1)", arrayOf(label))
        db.execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES('root','root',NULL,'root','root-hash',0,1,?,'/',1,1,0,1,1)", arrayOf("/fixture/$label"))
        db.execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES('doc','root','root','doc','h',1,0,?,'/doc',1,1,0,1,1)", arrayOf("/fixture/$label"))
        db.execSQL("INSERT INTO kg_nodes(id,name,type,metadata,source_type,created_at,file_uuid) VALUES('a','共同人物','person','{}','full',1,'doc'),('b','目标','person','{}','full',1,'doc')")
        db.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,doc_id,source_type,created_at,file_uuid) VALUES('e','a','b','认识',0.8,'doc','full',1,'doc')")
    }
}
