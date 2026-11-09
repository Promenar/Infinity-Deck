package com.promenar.infinity.data.local.db

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.ScheduledTaskEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ScheduledTaskMigrationTest {
    @Test fun `v25迁移保留会话并新增定时占位表索引和级联删除`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "scheduled-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                val definition = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/25.json").readText()).jsonObject.getValue("database").jsonObject
                definition.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                }
                definition.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                val values = ContentValues()
                db.rawQuery("PRAGMA table_info(sessions)", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(1)
                        if (column == "id") values.put(column, "s")
                        else if (column == "title") values.put(column, "原会话")
                        else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                            if (cursor.getString(2) in setOf("INTEGER", "REAL")) values.put(column, 0) else values.put(column, "")
                        }
                    }
                }
                db.insertOrThrow("sessions", null, values)
                db.version = 25
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name).addMigrations(MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN).allowMainThreadQueries().build()
            try {
                assertThat(room.openHelper.writableDatabase.version).isEqualTo(37)
                assertThat(room.sessionDao().getById("s")!!.title).isEqualTo("原会话")
                room.scheduledTaskDao().insert(ScheduledTaskEntity("sch", "s", "检查", "检查状态", "interval", "15", "UTC", false, false, true, 1000, 0, null, null, null, null, 1, 47))
                assertThat(room.scheduledTaskDao().claim("sch", "claim", 1000)).isEqualTo(1)
                assertThat(room.scheduledTaskDao().claim("sch", "duplicate", 1000)).isEqualTo(0)
                assertThat(room.scheduledTaskDao().get("sch")!!.claimToken).isEqualTo("claim")
                assertThat(room.openHelper.writableDatabase.query("PRAGMA foreign_key_list(scheduled_tasks)").use { it.count }).isEqualTo(1)
                room.sessionDao().deleteById("s")
                assertThat(room.scheduledTaskDao().get("sch")).isNull()
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
