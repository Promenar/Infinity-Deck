package com.promenar.infinity.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.repository.MessageRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class MessageQuoteMigrationTest {
    @Test
    fun `真实v30升级后旧消息引用为空且新引用经局部更新仍保留`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "quote-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/30.json").readText())
                .jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                schema.getValue("entities").jsonArray.forEach { element ->
                    val entity = element.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                seed(sqlite, "agents", mapOf("id" to "agent", "name" to "智能体"))
                seed(sqlite, "sessions", mapOf("id" to "s", "agent_id" to "agent"))
                seed(sqlite, "messages", mapOf("id" to "u", "session_id" to "s", "role" to "user", "content" to "旧正文"))
                sqlite.version = 30
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION, MIGRATION_38_39_MEDIA_GENERATION).allowMainThreadQueries().build()
            try {
                val sqlite = room.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(39)
                sqlite.query("PRAGMA table_info(messages)").use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    assertThat(columns).contains("quotes")
                }
                sqlite.query("SELECT quotes,content FROM messages WHERE id='u'").use {
                    check(it.moveToFirst())
                    assertThat(it.isNull(0)).isTrue()
                    assertThat(it.getString(1)).isEqualTo("旧正文")
                }
                val repository = MessageRepository(room.messageDao())
                val quotes = listOf(MessageQuote("source", "引文", "批注"))
                repository.updatePartial("u", mapOf("quotes" to quotes))
                repository.updatePartial("u", mapOf("content" to "新正文", "status" to "success"))
                assertThat(repository.getById("u")!!.quotes).isEqualTo(quotes)
                assertThat(repository.getById("u")!!.content).isEqualTo("新正文")
                repository.editContentInSession("s", "u", "编辑正文")
                assertThat(room.messageDao().getById("u")!!.toDomain().quotes).isEqualTo(quotes)
                repository.updatePartial("u", mapOf("quotes" to emptyList<MessageQuote>()))
                assertThat(room.messageDao().getById("u")!!.quotes).isNull()
                sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun seed(sqlite: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        sqlite.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                val value = fields[column]
                if (value != null) values.put(column, value)
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    if (cursor.getString(2).uppercase() in setOf("INTEGER", "REAL")) values.put(column, 0)
                    else values.put(column, "")
                }
            }
        }
        sqlite.insertOrThrow(table, null, values)
    }
}
