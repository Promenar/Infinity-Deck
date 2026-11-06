package com.promenar.infinity.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.TurnContextPersistenceTest.Companion.RAW_CONTEXT
import com.promenar.infinity.data.model.TurnContextPersistenceTest.Companion.RAW_INSTRUCTION
import com.promenar.infinity.data.repository.MessageRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class MessageTurnContextMigrationTest {
    @Test
    fun `真实v31升级后旧消息上下文为空且原文经关闭重开与局部更新保留`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "turn-context-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/31.json").readText())
                .jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                schema.getValue("entities").jsonArray.forEach { element ->
                    val entity = element.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                    entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                seed(sqlite, "agents", mapOf("id" to "agent", "name" to "智能体"))
                seed(sqlite, "sessions", mapOf("id" to "s", "agent_id" to "agent"))
                seed(sqlite, "messages", mapOf("id" to "legacy", "session_id" to "s", "role" to "user", "content" to "旧正文"))
                sqlite.version = 31
            }
            fun open() = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, com.promenar.infinity.data.local.db.MIGRATION_36_37_DOCUMENT_SETTINGS).allowMainThreadQueries().build()
            suspend fun withDatabase(block: suspend (InfinityDatabase) -> Unit) {
                val room = open()
                try { block(room) } finally { room.close() }
            }
            withDatabase { room ->
                val sqlite = room.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(36)
                assertThat(room.messageDao().getById("legacy")!!.turnContext).isNull()
                assertThat(room.messageDao().getById("legacy")!!.requestInstruction).isNull()
                sqlite.query("PRAGMA table_info(messages)").use { cursor ->
                    val found = mutableSetOf<String>()
                    while (cursor.moveToNext()) if (cursor.getString(1) in setOf("turn_context", "request_instruction")) {
                        found += cursor.getString(1)
                        assertThat(cursor.getString(2)).isEqualTo("TEXT")
                        assertThat(cursor.getInt(3)).isEqualTo(0)
                    }
                    assertThat(found).containsExactly("turn_context", "request_instruction")
                }
                val repository = MessageRepository(room.messageDao())
                repository.insert(Message("u", MessageRole.USER, "用户正文", turnContext = RAW_CONTEXT), "s")
                repository.updatePartial("u", mapOf("status" to "success"))
                repository.insert(Message("a", MessageRole.ASSISTANT, "助手正文", requestInstruction = RAW_INSTRUCTION), "s")
                repository.updatePartial("a", mapOf("status" to "success"))
            }
            withDatabase { room ->
                val repository = MessageRepository(room.messageDao())
                val restored = repository.getById("u")!!
                assertThat(restored.turnContext!!.toByteArray(Charsets.UTF_8)).isEqualTo(RAW_CONTEXT.toByteArray(Charsets.UTF_8))
                assertThat(restored.content).isEqualTo("用户正文")
                assertThat(repository.getById("a")!!.requestInstruction!!.toByteArray(Charsets.UTF_8)).isEqualTo(RAW_INSTRUCTION.toByteArray(Charsets.UTF_8))
                val observed = repository.observeBySession("s").first()
                assertThat(observed.first { it.id == "u" }.turnContext).isEqualTo(RAW_CONTEXT)
                assertThat(observed.first { it.id == "a" }.requestInstruction).isEqualTo(RAW_INSTRUCTION)
                repository.editContentInSession("s", "u", "编辑正文")
                assertThat(repository.getById("u")!!.turnContext).isNull()
                repository.updatePartial("u", mapOf("turnContext" to "  更新原文\r\n"))
                assertThat(repository.getById("u")!!.turnContext).isEqualTo("  更新原文\r\n")
                repository.updatePartial("a", mapOf("requestInstruction" to ""))
                assertThat(repository.getById("a")!!.requestInstruction).isEmpty()
                repository.updatePartial("a", mapOf("requestInstruction" to null))
                assertThat(repository.getById("a")!!.requestInstruction).isNull()
                repository.updatePartial("u", mapOf("turnContext" to null))
                assertThat(repository.getById("u")!!.turnContext).isNull()
                room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            }
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
