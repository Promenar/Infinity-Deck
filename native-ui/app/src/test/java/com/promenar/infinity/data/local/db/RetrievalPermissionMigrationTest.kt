package com.promenar.infinity.data.local.db

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RetrievalPermissionMigrationTest {
    @Test fun `v34升级保留来源并把所有历史记录放入共享池`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retrieval-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = listOf(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/34.json"),
                    File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/34.json")).first(File::isFile)
                val db = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
                db.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                }
                db.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                fun row(table: String, supplied: Map<String, Any>): ContentValues {
                    val values = ContentValues()
                    sqlite.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val column = cursor.getString(1)
                            when (val value = supplied[column]) {
                                is String -> values.put(column, value)
                                is Long -> values.put(column, value)
                                null -> if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                                    if (cursor.getString(2) in setOf("INTEGER", "REAL")) values.put(column, 0) else values.put(column, "")
                                }
                            }
                        }
                    }
                    return values
                }
                sqlite.insertOrThrow("agents", null, row("agents", mapOf("id" to "private-a", "name" to "隔离", "retrieval_config" to
                    """{"memoryPolicy":{"shareWithOtherAgents":false,"memoryCap":"AGENT"}}""")))
                sqlite.insertOrThrow("agents", null, row("agents", mapOf("id" to "normal-a", "name" to "共享", "retrieval_config" to
                    """{"memoryPolicy":{"shareWithOtherAgents":true,"memoryCap":"ALL_AGENTS"}}""")))
                listOf("SESSION", "AGENT", "ALL_AGENTS").forEachIndexed { index, scope ->
                    sqlite.insertOrThrow("sessions", null, row("sessions", mapOf("id" to "s$index", "agent_id" to if (index == 0) "private-a" else "normal-a",
                        "rag_options" to """{"memoryScope":"$scope","isGlobal":true}""")))
                }
                sqlite.execSQL("INSERT INTO long_term_memories(id,kind,title,content,source_agent_id,source_session_id,source_message_id,created_at,updated_at) VALUES('m','user','偏好','测试','a','s',NULL,1,1)")
                sqlite.version = 34
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name).addMigrations(MIGRATION_34_35).allowMainThreadQueries().build()
            try {
                val sqlite = room.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(35)
                assertThat(room.agentDao().getById("private-a")!!.retrievalConfig!!.memoryPolicy.globalMemory.name).isEqualTo("FORBID")
                assertThat(room.agentDao().getById("private-a")!!.retrievalConfig!!.memoryPolicy.activeMemoryDefault.name).isEqualTo("ISOLATED")
                assertThat(room.agentDao().getById("normal-a")!!.retrievalConfig!!.memoryPolicy.memoryCap.name).isEqualTo("ALL_AGENTS")
                listOf("s0", "s1", "s2").forEach { id ->
                    val options = com.promenar.infinity.data.model.json.decodeFromString(com.promenar.infinity.data.model.RagOptions.serializer(), room.sessionDao().getById(id)!!.ragOptions!!)
                    assertThat(options.globalMemoryEnabled).isFalse()
                    assertThat(options.effectiveMemoryScope.name).isEqualTo("SESSION")
                }
                sqlite.query("SELECT source_session_id,owner_session_id FROM long_term_memories WHERE id='m'").use {
                    assertThat(it.moveToFirst()).isTrue()
                    assertThat(it.getString(0)).isEqualTo("s")
                    assertThat(it.isNull(1)).isTrue()
                }
                sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `历史策略按私密设置迁移且保留上限`() {
        val privateConfig = migrateLegacyRetrievalConfig("""{"memoryPolicy":{"shareWithOtherAgents":false,"memoryCap":"AGENT"}}""")
        val policy = Json.parseToJsonElement(privateConfig).jsonObject.getValue("memoryPolicy").jsonObject
        assertThat(policy.getValue("globalMemory").jsonPrimitive.content).isEqualTo("FORBID")
        assertThat(policy.getValue("activeMemoryDefault").jsonPrimitive.content).isEqualTo("ISOLATED")
        assertThat(policy.getValue("memoryCap").jsonPrimitive.content).isEqualTo("AGENT")
        val normal = Json.parseToJsonElement(migrateLegacyRetrievalConfig(null)).jsonObject.getValue("memoryPolicy").jsonObject
        assertThat(normal.getValue("globalMemory").jsonPrimitive.content).isEqualTo("ALLOW_DEFAULT_OFF")
        assertThat(normal.getValue("activeMemoryDefault").jsonPrimitive.content).isEqualTo("SHARED")
    }

    @Test fun `各旧会话范围都迁为本会话且保留图谱绑定`() {
        listOf("SESSION", "AGENT", "ALL_AGENTS").forEach { scope ->
            val raw = """{"memoryScope":"$scope","isGlobal":true,"graphBinding":{"collectionId":"work:a","mode":"READ_ONLY"}}"""
            val result = Json.parseToJsonElement(migrateLegacyRagOptions(raw, true)).jsonObject
            assertThat(result.getValue("memoryScope").jsonPrimitive.content).isEqualTo("SESSION")
            assertThat(result.getValue("globalMemoryEnabled").jsonPrimitive.content).isEqualTo("false")
            assertThat(result.getValue("activeMemoryMode").jsonPrimitive.content).isEqualTo("ISOLATED")
            assertThat(result.getValue("graphBinding").jsonObject.getValue("collectionId").jsonPrimitive.content).isEqualTo("work:a")
        }
    }
}
