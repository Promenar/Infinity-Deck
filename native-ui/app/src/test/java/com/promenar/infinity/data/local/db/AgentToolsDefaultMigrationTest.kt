package com.promenar.infinity.data.local.db

import android.app.Application
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
class AgentToolsDefaultMigrationTest {
    @Test
    fun `v29迁移保留智能体且工具默认开启并可写入关闭`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "agent-tools-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = listOf(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/29.json"),
                    File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/29.json")).first(File::isFile)
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
                sqlite.execSQL("""INSERT INTO agents(id,name,description,name_customized,description_customized,
                    system_prompt,model,icon,color,is_pinned,created_at,use_inherited_config)
                    VALUES('old','保留智能体','描述',0,0,'提示词','model','✨','#C0C1FF',0,1,1)""".trimIndent())
                sqlite.version = 29
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35).allowMainThreadQueries().build()
            try {
                assertThat(room.openHelper.writableDatabase.version).isEqualTo(35)
                val agent = room.agentDao().getById("old")!!
                assertThat(agent.name).isEqualTo("保留智能体")
                assertThat(agent.systemPrompt).isEqualTo("提示词")
                assertThat(agent.toolsEnabledByDefault).isTrue()
                room.openHelper.writableDatabase.query("SELECT tools_enabled_default FROM agents WHERE id='old'").use {
                    check(it.moveToFirst())
                    assertThat(it.getInt(0)).isEqualTo(1)
                }
                room.agentDao().update(agent.copy(toolsEnabledByDefault = false))
                assertThat(room.agentDao().getById("old")!!.toolsEnabledByDefault).isFalse()
                room.openHelper.writableDatabase.query("SELECT tools_enabled_default FROM agents WHERE id='old'").use {
                    check(it.moveToFirst())
                    assertThat(it.getInt(0)).isEqualTo(0)
                }
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(35, INFINITY_SCHEMA_V35_IDENTITY_HASH)
                }).isTrue()
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
