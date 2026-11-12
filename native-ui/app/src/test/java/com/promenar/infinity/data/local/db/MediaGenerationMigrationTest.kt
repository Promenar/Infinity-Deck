package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.data.mediagen.queue.*
import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import java.io.File
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MediaGenerationMigrationTest {
    @Test fun `38到39只新增两表保留制作表并允许未知费用`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "media-38-39.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(38) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE production_sentinel (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO production_sentinel VALUES ('project', 'unchanged')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            val before = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            MIGRATION_38_39_MEDIA_GENERATION.migrate(db)
            val after = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(setOf("media_generation_tasks", "media_generation_usage"), after - before)
            assertEquals("unchanged", db.query("SELECT value FROM production_sentinel").use { it.moveToFirst(); it.getString(0) })
            db.execSQL("INSERT INTO media_generation_usage VALUES ('task', 'provider', 'model', 'source', NULL, NULL, 100)")
            assertTrue(db.query("SELECT amount, currency FROM media_generation_usage").use { it.moveToFirst(); it.isNull(0) && it.isNull(1) })
            assertEquals(2, db.query("PRAGMA index_list(media_generation_tasks)").use { c -> var count = 0; while (c.moveToNext()) if (c.getString(1).startsWith("index_")) count++; count })
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun `冻结38导出结构升级39通过Room完整校验`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "media-frozen-38.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schemaFile = listOf(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/38.json"),
                File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/38.json")).first { it.isFile }
            val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                    entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.version = 38
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_38_39_MEDIA_GENERATION).allowMainThreadQueries().build()
            try { assertEquals(39, room.openHelper.writableDatabase.version) } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `迁移新增表与Room实体一致且任务和费用事务去重`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sqlite = room.openHelper.writableDatabase
            // 用迁移语句创建表，验证 DAO 真实读写；完整结构由冻结 v38 的测试校验。
            sqlite.execSQL("DROP TABLE media_generation_tasks")
            sqlite.execSQL("DROP TABLE media_generation_usage")
            MIGRATION_38_39_MEDIA_GENERATION.migrate(sqlite)
            val channel = MediaChannel("c", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT)))
            val task = MediaTask("task", MediaGenerationRequest("c", GenerationMode.TEXT, "树林", emptyList(), "source"), null)
            val record = PersistentMediaTask(task, channel, "binding", "provider", "model", 1)
            val store = RoomMediaTaskStore(room)
            val entry = MediaUsageEntry("task", "provider", "model", "source", Money(BigDecimal("0.000001"), "USD"), 2)
            store.save(record, entry)
            store.save(record, entry)
            assertEquals(record, store.get("task"))
            assertEquals(1, room.mediaGenerationDao().usage().size)
            assertEquals(entry, room.mediaGenerationDao().usage().single().toEntry())
            store.delete("task")
            assertEquals(1, room.mediaGenerationDao().usage().size)
        } finally { room.close() }
    }
}
