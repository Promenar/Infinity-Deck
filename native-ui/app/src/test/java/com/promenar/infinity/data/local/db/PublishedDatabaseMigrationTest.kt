package com.promenar.infinity.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 依据官方 APK 在空白模拟器生成的 schema 验证，不从当前 schema 反向构造旧库。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PublishedDatabaseMigrationTest {
    @Test
    fun `公开v01的真实v17结构迁移保留中文消息及旧附件字段`() = verifyPublishedMigration(17)

    @Test
    fun `公开v02的真实v2结构沿全部前进迁移保留中文消息`() = verifyPublishedMigration(2)

    private fun verifyPublishedMigration(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "published-migration-${UUID.randomUUID()}.db"
        val fixtureFile = listOf(
            File("app/src/test/resources/legacy/public-v$version.json"),
            File("src/test/resources/legacy/public-v$version.json"),
        ).first(File::isFile)
        val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
        assertThat(fixture.getValue("userVersion").jsonPrimitive.int).isEqualTo(version)
        assertThat(fixture.getValue("containsUserData").jsonPrimitive.content).isEqualTo("false")
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                fixture.getValue("statements").jsonArray.forEach {
                    database.execSQL(it.jsonPrimitive.content)
                }
                database.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)",
                    arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content))
                database.version = version
                seed(database, "agents", mapOf("id" to "published-agent", "name" to "恢复测试"))
                seed(database, "sessions", mapOf("id" to "published-session", "agent_id" to "published-agent", "title" to "公开版本迁移"))
                seed(database, "messages", buildMap {
                    put("id", "published-message")
                    put("session_id", "published-session")
                    put("role", "user")
                    put("content", "中文与😀必须保留")
                    if (version == 17) put("attachments", "[]")
                })
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_18, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
                .allowMainThreadQueries()
                .build()
            try {
                val database = room.openHelper.writableDatabase
                database.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getInt(0)).isEqualTo(34)
                }
                database.query("SELECT content,session_id,legacy_attachments FROM messages WHERE id='published-message'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo("中文与😀必须保留")
                    assertThat(cursor.getString(1)).isEqualTo("published-session")
                    if (version == 17) assertThat(cursor.getString(2)).isEqualTo("[]")
                }
                database.query("PRAGMA foreign_key_check").use { cursor ->
                    assertThat(cursor.count).isEqualTo(0)
                }
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun seed(database: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                val supplied = fields[column]
                if (supplied != null) values.put(column, supplied)
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    when (cursor.getString(2).uppercase()) {
                        "INTEGER", "REAL" -> values.put(column, 0)
                        "BLOB" -> values.put(column, byteArrayOf())
                        else -> values.put(column, "")
                    }
                }
            }
        }
        database.insertOrThrow(table, null, values)
    }
}
