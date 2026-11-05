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
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class SessionRunModeMigrationTest {
    @Test fun `v26迁移保留旧会话及空运行模式并接受固定模式写入`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "run-mode-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = listOf(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/26.json"),
                    File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/26.json")).first(File::isFile)
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
                val values = ContentValues()
                sqlite.rawQuery("PRAGMA table_info(sessions)", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(1)
                        when {
                            column == "id" -> values.put(column, "s")
                            column == "title" -> values.put(column, "保留旧会话😀")
                            cursor.getInt(3) != 0 && cursor.isNull(4) -> {
                                if (cursor.getString(2) in setOf("INTEGER", "REAL")) values.put(column, 0)
                                else values.put(column, "")
                            }
                        }
                    }
                }
                sqlite.insertOrThrow("sessions", null, values)
                sqlite.version = 26
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35_TIMELINE).allowMainThreadQueries().build()
            try {
                assertThat(room.openHelper.writableDatabase.version).isEqualTo(35)
                assertThat(room.sessionDao().getById("s")!!.title).isEqualTo("保留旧会话😀")
                assertThat(room.sessionDao().getById("s")!!.runMode).isNull()
                val activity = room.sessionDao().getById("s")!!.updatedAt
                assertThat(room.sessionDao().initializeRunMode("s", "LINUX")).isEqualTo(1)
                assertThat(room.sessionDao().initializeRunMode("s", "BUILTIN")).isEqualTo(0)
                assertThat(room.sessionDao().getById("s")!!.runMode).isEqualTo("LINUX")
                // 进入旧会话时补写运行模式属于元数据，不推进首页排序用的最近消息时间。
                assertThat(room.sessionDao().getById("s")!!.updatedAt).isEqualTo(activity)
                assertThat(room.sessionDao().countLinuxSessions()).isEqualTo(1)
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(35, INFINITY_SCHEMA_V35_IDENTITY_HASH)
                }).isTrue()
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
