package com.promenar.infinity.data.local.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgProvenanceMigrationTest {
    @Test fun `v18重复实体合并关系重指向且保留legacy与各来源证据`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "kg-migration-${System.nanoTime()}.db"
        createV18(context, name).use { old ->
            old.execSQL("INSERT INTO kg_nodes(id,name,type,session_id,source_type,created_at,stale) VALUES('a1',' Ａlice ','PERSON','s1','full',1,0),('a2','alice','person','s2','full',2,0),('b','Bob','person',NULL,'full',1,0),('unknown','Unknown','concept',NULL,'full',1,0)")
            old.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,doc_id,session_id,source_type,created_at,stale) VALUES('e1','a1','b','knows',0.5,'external-doc',NULL,'full',1,0),('e2','a2','b',' KNOWS ',0.9,NULL,'s2','jit',2,0)")
        }
        val db = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
            .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33).allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertThat(count(db, "kg_nodes")).isEqualTo(3)
            assertThat(count(db, "kg_edges")).isEqualTo(1)
            sqlite.query("SELECT source_id,target_id,weight FROM kg_edges").use {
                check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("a1")
                assertThat(it.getString(1)).isEqualTo("b"); assertThat(it.getDouble(2)).isEqualTo(0.9)
            }
            sqlite.query("SELECT source_type,doc_id FROM kg_evidence WHERE edge_id='e1'").use {
                val sources = mutableSetOf<String>(); while (it.moveToNext()) sources += it.getString(0)
                assertThat(sources).containsExactly("legacy", "jit")
            }
            sqlite.query("SELECT source_type FROM kg_evidence WHERE node_id='unknown'").use {
                check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("legacy")
            }
            assertThat(db.kgEdgeDao().getByDocId("external-doc").single().weight).isEqualTo(0.5)
            db.kgEvidenceDao().clearSession("s1")
            assertThat(count(db, "kg_edges")).isEqualTo(1)
            db.kgEvidenceDao().clearDocument("external-doc")
            assertThat(count(db, "kg_edges")).isEqualTo(1)
            db.kgEvidenceDao().clearSession("s2")
            assertThat(count(db, "kg_edges")).isEqualTo(0)
            assertThat(db.kgNodeDao().getById("unknown")).isNotNull()
            sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.moveToFirst()).isFalse() }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `多批迁移不遗漏边界行且导出schema与真实Room一致`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "kg-migration-batches-${System.nanoTime()}.db"
        createV18(context, name).use { old ->
            old.beginTransaction()
            try {
                repeat(2501) { index ->
                    old.execSQL("INSERT INTO kg_nodes(id,name,type,source_type,created_at,stale) VALUES(?,?,'concept','full',1,0)", arrayOf("n%05d".format(index), "node-$index"))
                    if (index > 0) old.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,source_type,created_at,stale) VALUES(?,?,?,'next',1,'full',1,0)", arrayOf("e%05d".format(index), "n%05d".format(index - 1), "n%05d".format(index)))
                }
                old.setTransactionSuccessful()
            } finally { old.endTransaction() }
        }
        val started = System.nanoTime()
        val db = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
            .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33).allowMainThreadQueries().build()
        try {
            assertThat(count(db, "kg_nodes")).isEqualTo(2501)
            assertThat(count(db, "kg_edges")).isEqualTo(2500)
            println("KG-B 迁移：2501 节点 / 2500 边，耗时 ${(System.nanoTime() - started) / 1_000_000} ms（本机 Robolectric）")
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(33) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun count(db: InfinityDatabase, table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getInt(0) }

    private fun createV18(context: Context, name: String): SQLiteDatabase {
        val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/18.json").readText()).jsonObject.getValue("database").jsonObject
        context.getDatabasePath(name).parentFile!!.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).apply {
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { execSQL(it.jsonPrimitive.content) }
            version = 18
        }
    }
}
