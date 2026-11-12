package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ProductionMigrationTest {
    @Test fun `版本37迁移创建制作项目表与级联外键`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "production-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/37.json").readText()).jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val name = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", name)
                    db.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                    entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.version = 37
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name).addMigrations(MIGRATION_37_38_PRODUCTION, MIGRATION_38_39_MEDIA_GENERATION).allowMainThreadQueries().build()
            try {
                val db = room.openHelper.writableDatabase
                assertThat(db.version).isEqualTo(39)
                assertThat(db.query("PRAGMA foreign_key_list(session_productions)").use { it.count }).isEqualTo(2)
                assertThat(db.query("SELECT count(*) FROM productions").use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
