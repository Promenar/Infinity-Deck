package com.promenar.infinity.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v23 → v24：旧采样参数恢复为模型默认，非采样会话设置保留，新增参数方案表可读写。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ParameterSchemeMigrationTest {
    @Test
    fun `v23迁移清空助手与会话的旧采样参数并保留其它会话设置`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "parameter-schemes-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                createExported(database, 23)
                database.version = 23
                seed(database, "agents", mapOf("id" to "agent", "name" to "助手", "temperature" to "0.7", "top_p" to "0.9", "max_tokens" to "4096"))
                seed(database, "sessions", mapOf(
                    "id" to "preset", "agent_id" to "agent", "title" to "档位写入",
                    "inference_params" to """{"temperature":1.0,"topP":1.0,"topK":100,"maxTokens":2048,"thinkingLevel":"high","streamTimeout":300,"autoSummaryThreshold":0.6,"keepRecentTurns":8}""",
                ))
                seed(database, "sessions", mapOf("id" to "plain", "agent_id" to "agent", "title" to "仅超时", "inference_params" to """{"streamTimeout":60}"""))
                seed(database, "sessions", mapOf("id" to "broken", "agent_id" to "agent", "title" to "损坏", "inference_params" to "not json"))
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE)
                .allowMainThreadQueries()
                .build()
            try {
                val agent = room.agentDao().getById("agent")!!
                assertThat(listOf(agent.temperature, agent.top_p, agent.max_tokens?.toDouble())).containsExactly(null, null, null)
                assertThat(agent.reasoningEffort).isNull()

                val preset = Json.parseToJsonElement(room.sessionDao().getById("preset")!!.inferenceParams!!).jsonObject
                assertThat(preset.keys).containsExactly("streamTimeout", "autoSummaryThreshold", "keepRecentTurns")
                assertThat(preset.getValue("streamTimeout").jsonPrimitive.content).isEqualTo("300")
                assertThat(room.sessionDao().getById("plain")!!.inferenceParams).isEqualTo("""{"streamTimeout":60}""")
                assertThat(room.sessionDao().getById("broken")!!.inferenceParams).isEqualTo("not json")

                room.parameterSchemeDao().upsert(ParameterSchemeEntity("s1", "p::m", "写代码", """{"temperature":0.2}""", 1, 1))
                room.parameterSchemeDao().upsert(ParameterSchemeEntity("s2", "p::other", "别的模型", "{}", 2, 2))
                assertThat(room.parameterSchemeDao().observeForModel("p::m").first().map { it.name }).containsExactly("写代码")
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(35, INFINITY_SCHEMA_V35_IDENTITY_HASH)
                }).isTrue()
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `会话参数改写只移除采样键`() {
        assertThat(resetSessionSamplingJson("""{"streamTimeout":120}""")).isNull()
        assertThat(resetSessionSamplingJson("""{"topK":50,"keepRecentTurns":3}""")).isEqualTo("""{"keepRecentTurns":3}""")
        assertThat(resetSessionSamplingJson("[1]")).isNull()
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
