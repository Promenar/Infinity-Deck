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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 从导出的 v21 真实结构经 Room 前进迁移到当前版本；Room 打开时按实体校验迁移结果。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceBindingMigrationTest {
    @Test
    fun `v21迁移保留业务数据并以空值接入工作区绑定与用户规则`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workspace-binding-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                createExported(database, 21)
                database.version = 21
                seed(database, "agents", mapOf("id" to "agent", "name" to "保留中文😀", "system_prompt" to "你是助手"))
                seed(database, "sessions", mapOf("id" to "session", "agent_id" to "agent", "title" to "旧会话",
                    "workspace_root_uuid" to "root"))
                seed(database, "ssh_hosts", mapOf("id" to "host", "alias" to "fnos", "hostname" to "fnos.local", "username" to "root"))
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = room.openHelper.writableDatabase
                sqlite.query("PRAGMA user_version").use { check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(34) }
                val agent = room.agentDao().getById("agent")!!
                assertThat(agent.name).isEqualTo("保留中文😀")
                assertThat(agent.systemPrompt).isEqualTo("你是助手")
                assertThat(agent.userRules).isEmpty()
                assertThat(agent.defaultWorkspaceId).isNull()
                val session = room.sessionDao().getById("session")!!
                assertThat(session.title).isEqualTo("旧会话")
                assertThat(session.workspaceRootUuid).isEqualTo("root")
                assertThat(session.workspaceId).isNull()
                assertThat(room.sshHostDao().find("fnos")!!.hostname).isEqualTo("fnos.local")
                assertThat(room.workspaceDao().getByLocation("shared", "/storage/emulated/0/x")).isNull()
                assertThat(room.agentRuleRevisionDao().listForAgent("agent")).isEmpty()
                sqlite.query("PRAGMA index_list(workspaces)").use { cursor ->
                    val unique = buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(2) == 1) }
                    assertThat(unique["index_workspaces_kind_location"]).isTrue()
                }
                sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(34, INFINITY_SCHEMA_V34_IDENTITY_HASH)
                }).isTrue()
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

    private fun seed(database: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                if (column in fields) values.put(column, fields.getValue(column))
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    when (cursor.getString(2).uppercase()) {
                        "INTEGER", "REAL" -> values.put(column, 0)
                        else -> values.put(column, "")
                    }
                }
            }
        }
        database.insertOrThrow(table, null, values)
    }

    private fun file(path: String): File = listOf(File("app/$path"), File(path)).first(File::isFile)
}
