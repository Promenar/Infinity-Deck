package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class KeychainMigration36Test {
    @Test fun `v36 主机升级至 v37 并保留连接配置`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "keychain-migration-36.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/36.json").readText())
                .jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { element ->
                    val entity = element.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                    entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO ssh_hosts(id,alias,hostname,username) VALUES('host','server','example.test','alice')")
                db.version = 36
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION).allowMainThreadQueries().build()
            try {
                val db = room.openHelper.writableDatabase
                assertThat(db.version).isEqualTo(38)
                db.query("SELECT alias,hostname,key_entry_id FROM ssh_hosts WHERE id='host'").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("server")
                    assertThat(cursor.getString(1)).isEqualTo("example.test")
                    assertThat(cursor.isNull(2)).isTrue()
                }
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'keychain_%'").use { cursor ->
                    val names = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    assertThat(names).containsAtLeast("keychain_entries", "keychain_usage")
                }
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
