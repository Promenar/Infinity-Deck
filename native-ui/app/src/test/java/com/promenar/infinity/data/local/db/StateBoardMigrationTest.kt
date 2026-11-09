package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.repository.StateBoardRepository
import com.promenar.infinity.data.state.StateField
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StateBoardMigrationTest {
    @Test fun `v24升级v25保留原消息新增快照表索引与级联外键`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "state-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/24.json")
                val definition = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
                definition.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { index -> sqlite.execSQL(sql(index.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                }
                definition.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                sqlite.version = 24
                fun seed(table: String, fields: Map<String, String>) {
                    val values = android.content.ContentValues()
                    sqlite.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val column = cursor.getString(1)
                            if (column in fields) values.put(column, fields.getValue(column))
                            else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                                if (cursor.getString(2).uppercase() in setOf("INTEGER", "REAL")) values.put(column, 0) else values.put(column, "")
                            }
                        }
                    }
                    sqlite.insertOrThrow(table, null, values)
                }
                seed("agents", mapOf("id" to "a", "name" to "助手"))
                seed("sessions", mapOf("id" to "s", "agent_id" to "a", "title" to "旧会话"))
                seed("messages", mapOf("id" to "m", "session_id" to "s", "role" to "assistant", "content" to "保留正文"))
            }
            val migrated = Room.databaseBuilder(context, InfinityDatabase::class.java, name).addMigrations(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN).allowMainThreadQueries().build()
            try {
                val db = migrated
                val sqlite = db.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(37)
                assertThat(sqlite.query("PRAGMA foreign_key_list(state_board_versions)").use { it.count }).isEqualTo(3)
                assertThat(sqlite.query("PRAGMA index_list(state_board_versions)").use { it.count }).isEqualTo(2)
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { it.hasCompleteCurrentRoomSchema(37, INFINITY_SCHEMA_V37_IDENTITY_HASH) }).isTrue()
                assertThat(db.sessionDao().getById("s")!!.title).isEqualTo("旧会话")
                assertThat(db.messageDao().getById("m")!!.content).isEqualTo("保留正文")
                StateBoardRepository(db).define("s", "m", "面板", listOf(StateField("hp", "生命值", "number", JsonPrimitive(80))))
            } finally { migrated.close() }
            val reopened = Room.databaseBuilder(context, InfinityDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                val db = reopened
                assertThat(db.messageDao().getById("m")!!.content).isEqualTo("保留正文")
                assertThat(StateBoardRepository(db).get("s")!!.board!!.fields.single().value).isEqualTo(JsonPrimitive(80))
                db.messageDao().deleteByIdWithContextSummary("m")
                assertThat(StateBoardRepository(db).get("s")).isNull()
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
