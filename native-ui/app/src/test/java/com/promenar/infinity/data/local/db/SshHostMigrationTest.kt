package com.promenar.infinity.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 从已导出的真实旧结构打开 Room，验证前进迁移而非手工调用 SQL。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SshHostMigrationTest {
    @Test
    fun `v18迁移保留中文业务数据并创建不含凭据的SSH表`() = verifyMigration(18)

    @Test
    fun `v5经18前进到23并保留中文业务数据`() = verifyMigration(5)

    @Test
    fun `公开v17经18前进到23并保留中文业务数据`() = verifyMigration(17)

    @Test
    fun `v19前进到23保留知识图谱并创建SSH表`() = verifyMigration(19)

    @Test
    fun `v20前进到23保留知识图谱和外部挂载并创建SSH表`() = verifyMigration(20)

    private fun verifyMigration(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ssh-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                if (version == 17) createPublishedV17(database) else createExported(database, version)
                database.version = version
                seed(database, "agents", mapOf("id" to "ssh-migration-agent", "name" to "保留中文😀"))
                seed(database, "kg_nodes", mapOf("id" to "ssh-migration-node", "name" to "测试实体",
                    "normalized_name" to "测试实体", "type" to "concept"))
                // v19 起实体须有证据才可见；迁移按证据把实体分入图谱，无证据实体本就不可见。
                if (version >= 19) seed(database, "kg_evidence", mapOf("id" to "ssh-migration-evidence", "node_id" to "ssh-migration-node",
                    "source_type" to "legacy", "content_hash" to "", "task_id" to "ssh-migration", "created_at" to "0", "workspace_document" to "0"))
                if (version >= 20) seed(database, "external_mounts", mapOf("id" to "ssh-migration-mount",
                    "alias" to "docs", "source_type" to "saf_tree", "source_id" to "content://test/tree/docs", "scope_key" to "session"))
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_5_18, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE)
                .allowMainThreadQueries()
                .build()
            try {
                val database = room.openHelper.writableDatabase
                database.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getInt(0)).isEqualTo(36)
                }
                database.query("SELECT name FROM agents WHERE id='ssh-migration-agent'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo("保留中文😀")
                }
                database.query("SELECT name FROM kg_nodes WHERE id='ssh-migration-node'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo("测试实体")
                }
                if (version >= 20) database.query("SELECT alias FROM external_mounts WHERE id='ssh-migration-mount'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo("docs")
                }
                database.query("PRAGMA table_info(ssh_hosts)").use { cursor ->
                    val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    assertThat(columns).containsExactly(
                        "id", "alias", "hostname", "port", "username", "auth_type", "jump_alias",
                        "trusted_key_type", "trusted_fingerprint", "keep_terminal", "local_forward_port",
                        "forward_host", "forward_port",
                    )
                }
                database.query("PRAGMA index_list(ssh_hosts)").use { cursor ->
                    var uniqueAlias = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "index_ssh_hosts_alias") uniqueAlias = cursor.getInt(2) == 1
                    }
                    assertThat(uniqueAlias).isTrue()
                }
                database.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createExported(database: SQLiteDatabase, version: Int) {
        val schema = file("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/$version.json")
        val definition = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        definition.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            fun sql(value: String) = value.replace("\${TABLE_NAME}", tableName)
            database.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                database.execSQL(sql(index.jsonObject.getValue("createSql").jsonPrimitive.content))
            }
        }
        definition.getValue("setupQueries").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
    }

    private fun createPublishedV17(database: SQLiteDatabase) {
        val fixture = Json.parseToJsonElement(file("src/test/resources/legacy/public-v17.json").readText()).jsonObject
        fixture.getValue("statements").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
        database.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)",
            arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content))
    }

    private fun seed(database: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                if (column in fields) values.put(column, fields.getValue(column))
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    when (cursor.getString(2).uppercase()) {
                        "INTEGER", "REAL" -> values.put(column, 0)
                        "BLOB" -> values.put(column, byteArrayOf())
                        else -> values.put(column, "")
                    }
                }
            }
        }
        database.insertOrThrow(table, null, values)
    }

    private fun file(path: String): File = listOf(File("app/$path"), File(path)).first(File::isFile)
}
