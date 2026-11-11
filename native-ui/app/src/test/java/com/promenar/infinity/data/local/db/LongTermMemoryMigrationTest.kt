package com.promenar.infinity.data.local.db

import android.app.Application
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class LongTermMemoryMigrationTest {
    @Test
    fun `v27迁移保留会话并创建无来源外键的独立长期记忆表`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "long-term-memory-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = listOf(
                    File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/27.json"),
                    File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/27.json"),
                ).first(File::isFile)
                val definition = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
                definition.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach {
                        sqlite.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content))
                    }
                }
                definition.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                val session = ContentValues()
                sqlite.rawQuery("PRAGMA table_info(sessions)", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(1)
                        when {
                            column == "id" -> session.put(column, "session-before-v28")
                            column == "title" -> session.put(column, "保留旧会话")
                            cursor.getInt(3) != 0 && cursor.isNull(4) -> {
                                if (cursor.getString(2) in setOf("INTEGER", "REAL")) session.put(column, 0)
                                else session.put(column, "")
                            }
                        }
                    }
                }
                sqlite.insertOrThrow("sessions", null, session)
                sqlite.version = 27
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION).allowMainThreadQueries().build()
            try {
                val sqlite = room.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(38)
                assertThat(room.sessionDao().getById("session-before-v28")!!.title).isEqualTo("保留旧会话")
                sqlite.query("SELECT * FROM long_term_memories").use { assertThat(it.count).isEqualTo(0) }
                sqlite.query("PRAGMA foreign_key_list(long_term_memories)").use { assertThat(it.count).isEqualTo(0) }
                sqlite.query("PRAGMA index_info(index_long_term_memories_updated_at)").use {
                    assertThat(it.count).isEqualTo(1)
                    check(it.moveToFirst())
                    assertThat(it.getString(it.getColumnIndexOrThrow("name"))).isEqualTo("updated_at")
                }
                sqlite.execSQL(
                    "INSERT INTO long_term_memories " +
                        "(id,kind,title,content,source_agent_id,source_session_id,source_message_id,created_at,updated_at) " +
                        "VALUES ('memory','user','偏好','使用简体中文','deleted-agent','session-before-v28','deleted-message',1,2)",
                )
                room.sessionDao().deleteById("session-before-v28")
                sqlite.query("SELECT content,source_session_id FROM long_term_memories WHERE id='memory'").use {
                    check(it.moveToFirst())
                    assertThat(it.getString(0)).isEqualTo("使用简体中文")
                    assertThat(it.getString(1)).isEqualTo("session-before-v28")
                }
                sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(38, checkNotNull(GeneratedCurrentRoomSchemas.schemas[38]).identityHash)
                }).isTrue()
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
