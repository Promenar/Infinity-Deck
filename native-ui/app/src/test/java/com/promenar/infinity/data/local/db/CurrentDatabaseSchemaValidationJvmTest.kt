package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.recovery.AndroidDualDatabaseSourceProbe
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CurrentDatabaseSchemaValidationJvmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatabases()
    }

    @After
    fun tearDown() = deleteDatabases()

    @Test
    fun `版本38的Room身份必须不同于版本37`() {
        assertThat(GeneratedCurrentRoomSchemas.schemas.getValue(38).identityHash).isNotEqualTo(GeneratedCurrentRoomSchemas.schemas.getValue(37).identityHash)
    }

    @Test
    fun `版本39的Room身份必须不同于版本38`() {
        assertThat(GeneratedCurrentRoomSchemas.schemas.getValue(39).identityHash)
            .isNotEqualTo(GeneratedCurrentRoomSchemas.schemas.getValue(38).identityHash)
    }

    @Test
    fun completeExportedV18ThroughV39DatabasesPassBothProductionGates() = verify(Mutation.NONE)

    @Test
    fun productionMigrationChainFromV18PassesBothProductionGatesAtEveryVersion() {
        createLegacyDatabase()
        createCurrentDatabase(18, Mutation.NONE)
        for (version in 18..39) {
            for (gate in Gate.entries) {
                val source = context.getDatabasePath("infinity_v2.db")
                val originalBytes = source.readBytes()
                validate(gate, version)
                assertThat(source.readBytes()).isEqualTo(originalBytes)
            }
            if (version < 39) migrateCurrentDatabase(version + 1)
        }
    }

    @Test
    fun `版本39缺少媒体任务表时两个生产门禁均拒绝`() = verify(Mutation.MISSING_MEDIA_TASK_TABLE, 39..39)

    @Test
    fun `版本39缺少费用表时两个生产门禁均拒绝`() = verify(Mutation.MISSING_MEDIA_USAGE_TABLE, 39..39)

    @Test
    fun `版本39费用字段类型错误时两个生产门禁均拒绝`() = verify(Mutation.MEDIA_USAGE_COLUMN_TYPE, 39..39)

    @Test
    fun `版本39缺少媒体索引时两个生产门禁均拒绝`() = verify(Mutation.MISSING_MEDIA_USAGE_INDEX, 39..39)

    @Test
    fun matchingIdentityWithMissingTaskNodesTableFailsBothProductionGates() = verify(Mutation.MISSING_TABLE)

    @Test
    fun matchingV28IdentityWithMissingLongTermMemoryTableFailsBothProductionGates() =
        verify(Mutation.MISSING_LONG_TERM_MEMORY_TABLE, 28..28)

    @Test
    fun matchingV28IdentityWithMissingLongTermMemoryIndexFailsBothProductionGates() =
        verify(Mutation.MISSING_LONG_TERM_MEMORY_INDEX, 28..28)

    @Test
    fun matchingIdentityWithMissingTaskNodesColumnFailsBothProductionGates() = verify(Mutation.MISSING_COLUMN)

    @Test
    fun matchingIdentityWithRenamedTaskNodesColumnFailsBothProductionGates() = verify(Mutation.RENAMED_COLUMN)

    @Test
    fun matchingIdentityWithWrongColumnTypeFailsBothProductionGates() = verify(Mutation.COLUMN_TYPE)

    @Test
    fun matchingIdentityWithNullableRequiredColumnFailsBothProductionGates() = verify(Mutation.NULLABILITY)

    @Test
    fun matchingIdentityWithWrongPrimaryKeyPositionFailsBothProductionGates() = verify(Mutation.PRIMARY_KEY)

    @Test
    fun matchingIdentityWithWrongColumnDefaultFailsBothProductionGates() = verify(Mutation.DEFAULT_VALUE)

    @Test
    fun matchingIdentityWithMissingIndexFailsBothProductionGates() = verify(Mutation.MISSING_INDEX)

    @Test
    fun matchingIdentityWithRenamedIndexFailsBothProductionGates() = verify(Mutation.RENAMED_INDEX)

    @Test
    fun matchingIdentityWithWrongIndexUniquenessFailsBothProductionGates() = verify(Mutation.INDEX_UNIQUENESS)

    @Test
    fun matchingIdentityWithWrongIndexColumnOrderFailsBothProductionGates() = verify(Mutation.INDEX_COLUMN_ORDER)

    @Test
    fun matchingIdentityWithWrongIndexSortOrderFailsBothProductionGates() = verify(Mutation.INDEX_SORT_ORDER)

    @Test
    fun matchingIdentityWithPartialIndexFailsBothProductionGates() = verify(Mutation.PARTIAL_INDEX)

    @Test
    fun matchingIdentityWithWrongForeignKeyFailsBothProductionGates() = verify(Mutation.FOREIGN_KEY)

    @Test
    fun matchingIdentityWithWrongFtsDeclarationFailsBothProductionGates() = verify(Mutation.FTS_DECLARATION)

    private fun verify(mutation: Mutation, versions: IntRange = 18..39) {
        val failures = mutableListOf<String>()
        for (version in versions) {
            for (gate in Gate.entries) {
                deleteDatabases()
                createLegacyDatabase()
                createCurrentDatabase(version, mutation)
                val source = context.getDatabasePath("infinity_v2.db")
                val originalBytes = source.readBytes()
                val failure = runCatching { validate(gate, version) }.exceptionOrNull()
                val accepted = failure == null
                if (mutation == Mutation.NONE && !accepted) {
                    failures += "v$version/$gate 拒绝完整合法库：$failure"
                } else if (mutation != Mutation.NONE && failure !is IOException) {
                    failures += "v$version/$gate 应以结构错误拒绝 $mutation，实际：${failure ?: "接受"}"
                }
                assertThat(source.readBytes()).isEqualTo(originalBytes)
            }
        }
        assertThat(failures).isEmpty()
    }

    private fun validate(gate: Gate, version: Int) {
        when (gate) {
            Gate.PROMOTER -> assertThat(LegacyDatabasePromoter.promote(context))
                .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
            Gate.SOURCE_PROBE -> {
                // 常规 v18+ 主库由公开入口交给 Promoter；这里覆盖 WAL 恢复后调用的现有结构门禁。
                val method = AndroidDualDatabaseSourceProbe::class.java.getDeclaredMethod(
                    "validatePublishedShape", SQLiteDatabase::class.java, String::class.java, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath("infinity_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    try {
                        method.invoke(AndroidDualDatabaseSourceProbe(context), sqlite, "current", version)
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                }
            }
        }
    }

    private fun createCurrentDatabase(version: Int, mutation: Mutation) {
        val schema = exportedSchema(version)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("infinity_v2.db"), null).use { sqlite ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                if (table == "task_nodes" && mutation == Mutation.MISSING_TABLE) return@forEach
                if (table == "long_term_memories" && mutation == Mutation.MISSING_LONG_TERM_MEMORY_TABLE) return@forEach
                if (table == "media_generation_tasks" && mutation == Mutation.MISSING_MEDIA_TASK_TABLE) return@forEach
                if (table == "media_generation_usage" && mutation == Mutation.MISSING_MEDIA_USAGE_TABLE) return@forEach
                var sql = entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                if (table == "task_nodes") {
                    sql = when (mutation) {
                        Mutation.MISSING_COLUMN -> replaceChecked(sql, "`note` TEXT, ", "")
                        Mutation.RENAMED_COLUMN -> replaceChecked(sql, "`note` TEXT", "`renamed_note` TEXT")
                        Mutation.COLUMN_TYPE -> replaceChecked(sql, "`title` TEXT NOT NULL", "`title` INTEGER NOT NULL")
                        Mutation.NULLABILITY -> replaceChecked(sql, "`title` TEXT NOT NULL", "`title` TEXT")
                        Mutation.PRIMARY_KEY -> replaceChecked(sql, "PRIMARY KEY(`id`)", "PRIMARY KEY(`session_id`, `id`)")
                        Mutation.DEFAULT_VALUE -> replaceChecked(sql, "`sort_order` INTEGER NOT NULL DEFAULT 0", "`sort_order` INTEGER NOT NULL DEFAULT 1")
                        Mutation.FOREIGN_KEY -> replaceChecked(sql, "ON DELETE CASCADE", "ON DELETE RESTRICT")
                        else -> sql
                    }
                }
                if (table == "media_generation_usage" && mutation == Mutation.MEDIA_USAGE_COLUMN_TYPE) {
                    sql = replaceChecked(sql, "`amount` TEXT", "`amount` INTEGER")
                }
                if (table == "vectors_fts" && mutation == Mutation.FTS_DECLARATION) {
                    sql = replaceChecked(sql, "`content` TEXT NOT NULL", "`content` INTEGER")
                }
                sqlite.execSQL(sql)
                createIndices(sqlite, entity, table, mutation)
                entity["contentSyncTriggers"]?.jsonArray?.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.version = version
        }
    }

    private fun migrateCurrentDatabase(targetVersion: Int) {
        val migration = when (targetVersion) {
            19 -> MIGRATION_18_19
            20 -> MIGRATION_19_20
            21 -> MIGRATION_20_21
            22 -> MIGRATION_21_22
            23 -> MIGRATION_22_23
            24 -> MIGRATION_23_24
            25 -> MIGRATION_24_25
            26 -> MIGRATION_25_26
            27 -> MIGRATION_26_27
            28 -> MIGRATION_27_28
            29 -> MIGRATION_28_29
            30 -> MIGRATION_29_30
            31 -> MIGRATION_30_31
            32 -> MIGRATION_31_32
            33 -> MIGRATION_32_33
            34 -> MIGRATION_33_34
            35 -> MIGRATION_34_35
            36 -> MIGRATION_35_36_TIMELINE
            37 -> MIGRATION_36_37_KEYCHAIN
            38 -> MIGRATION_37_38_PRODUCTION
            39 -> MIGRATION_38_39_MEDIA_GENERATION
            else -> error("数据库测试没有对应的生产迁移")
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("infinity_v2.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(targetVersion) {
                // 与 Room 一致：迁移期间不启用外键，打开后再启用并检查迁移结果的外键完整性。
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                    db.query("PRAGMA foreign_key_check").use { check(it.count == 0) { "v$targetVersion 迁移后存在外键违例" } }
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    error("迁移测试必须使用已存在的 v18 数据库")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion == targetVersion - 1 && newVersion == targetVersion)
                    migration.migrate(db)
                    // 生产迁移负责业务结构，Room 在迁移后更新该版本的身份记录。
                    exportedSchema(targetVersion).getValue("setupQueries").jsonArray.forEach {
                        db.execSQL(it.jsonPrimitive.content)
                    }
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }
    }

    private fun exportedSchema(version: Int): JsonObject = Json.parseToJsonElement(
        File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/$version.json").readText(),
    ).jsonObject.getValue("database").jsonObject

    private fun createIndices(sqlite: SQLiteDatabase, entity: JsonObject, table: String, mutation: Mutation) {
        entity["indices"]?.jsonArray?.forEach { element ->
            val index = element.jsonObject
            val name = index.getValue("name").jsonPrimitive.content
            if (name == "index_long_term_memories_updated_at" && mutation == Mutation.MISSING_LONG_TERM_MEMORY_INDEX) return@forEach
            if (name == "index_media_generation_usage_provider_id_model_id" && mutation == Mutation.MISSING_MEDIA_USAGE_INDEX) return@forEach
            var sql = index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            if (name == "index_task_nodes_status") {
                sql = when (mutation) {
                    Mutation.MISSING_INDEX -> return@forEach
                    Mutation.RENAMED_INDEX -> replaceChecked(sql, name, "renamed_task_nodes_status")
                    Mutation.INDEX_UNIQUENESS -> replaceChecked(sql, "CREATE INDEX", "CREATE UNIQUE INDEX")
                    Mutation.INDEX_SORT_ORDER -> replaceChecked(sql, "(`status`)", "(`status` DESC)")
                    Mutation.PARTIAL_INDEX -> "$sql WHERE status='pending'"
                    else -> sql
                }
            }
            if (name == "index_messages_session_id_created_at" && mutation == Mutation.INDEX_COLUMN_ORDER) {
                sql = replaceChecked(sql, "(`session_id`, `created_at`)", "(`created_at`, `session_id`)")
            }
            sqlite.execSQL(sql)
        }
    }

    private fun replaceChecked(source: String, before: String, after: String): String {
        check(before in source) { "数据库测试夹具缺少变异目标：$before" }
        return source.replace(before, after)
    }

    private fun createLegacyDatabase() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("infinity.db"), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')")
            sqlite.version = 17
        }
    }

    private fun deleteDatabases() {
        context.deleteDatabase("infinity.db")
        context.deleteDatabase("infinity_v2.db")
    }

    private enum class Gate { PROMOTER, SOURCE_PROBE }

    private enum class Mutation {
        NONE, MISSING_TABLE, MISSING_LONG_TERM_MEMORY_TABLE, MISSING_LONG_TERM_MEMORY_INDEX,
        MISSING_MEDIA_TASK_TABLE, MISSING_MEDIA_USAGE_TABLE, MEDIA_USAGE_COLUMN_TYPE, MISSING_MEDIA_USAGE_INDEX,
        MISSING_COLUMN, RENAMED_COLUMN, COLUMN_TYPE, NULLABILITY, PRIMARY_KEY,
        DEFAULT_VALUE, MISSING_INDEX, RENAMED_INDEX, INDEX_UNIQUENESS, INDEX_COLUMN_ORDER, INDEX_SORT_ORDER,
        PARTIAL_INDEX, FOREIGN_KEY, FTS_DECLARATION,
    }
}
