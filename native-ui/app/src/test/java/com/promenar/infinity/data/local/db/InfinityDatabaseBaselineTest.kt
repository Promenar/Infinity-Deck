package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class InfinityDatabaseBaselineTest {
    @Test
    fun backupSchemaGateMatchesExportedRoomIdentityHash() {
        assertThat(INFINITY_SCHEMA_V35_IDENTITY_HASH).isEqualTo(exportedSchema(35).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V35_IDENTITY).isEqualTo(exportedSchema(35).identityHash)
        assertThat(INFINITY_SCHEMA_V34_IDENTITY_HASH).isEqualTo(exportedSchema(34).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V34_IDENTITY_HASH).isEqualTo(exportedSchema(34).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V34_IDENTITY).isEqualTo(exportedSchema(34).identityHash)
        assertThat(INFINITY_SCHEMA_V33_IDENTITY_HASH).isEqualTo(exportedSchema(33).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V33_IDENTITY_HASH).isEqualTo(exportedSchema(33).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V33_IDENTITY).isEqualTo(exportedSchema(33).identityHash)
        assertThat(INFINITY_SCHEMA_V32_IDENTITY_HASH).isEqualTo(exportedSchema(32).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V32_IDENTITY_HASH).isEqualTo(exportedSchema(32).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V32_IDENTITY).isEqualTo(exportedSchema(32).identityHash)
        assertThat(INFINITY_SCHEMA_V31_IDENTITY_HASH).isEqualTo(exportedSchema(31).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V31_IDENTITY_HASH).isEqualTo(exportedSchema(31).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V31_IDENTITY).isEqualTo(exportedSchema(31).identityHash)
        assertThat(INFINITY_SCHEMA_V30_IDENTITY_HASH).isEqualTo(exportedSchema(30).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V30_IDENTITY_HASH).isEqualTo(exportedSchema(30).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V30_IDENTITY).isEqualTo(exportedSchema(30).identityHash)
        assertThat(INFINITY_SCHEMA_V29_IDENTITY_HASH).isEqualTo(exportedSchema(29).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V29_IDENTITY_HASH).isEqualTo(exportedSchema(29).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V29_IDENTITY).isEqualTo(exportedSchema(29).identityHash)
        assertThat(INFINITY_SCHEMA_V28_IDENTITY_HASH).isEqualTo(exportedSchema(28).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V28_IDENTITY_HASH).isEqualTo(exportedSchema(28).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V28_IDENTITY).isEqualTo(exportedSchema(28).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V27_IDENTITY_HASH).isEqualTo(exportedSchema(27).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V27_IDENTITY).isEqualTo(exportedSchema(27).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V26_IDENTITY_HASH).isEqualTo(exportedSchema(26).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V26_IDENTITY).isEqualTo(exportedSchema(26).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V25_IDENTITY_HASH).isEqualTo(exportedSchema(25).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V25_IDENTITY).isEqualTo(exportedSchema(25).identityHash)
        val schema = exportedSchema(24)

        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V24_IDENTITY_HASH)
            .isEqualTo(schema.identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V24_IDENTITY)
            .isEqualTo(schema.identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V23_IDENTITY)
            .isEqualTo(exportedSchema(23).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V22_IDENTITY)
            .isEqualTo(exportedSchema(22).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V21_IDENTITY_HASH)
            .isEqualTo(exportedSchema(21).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V21_IDENTITY)
            .isEqualTo(exportedSchema(21).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V20_IDENTITY_HASH)
            .isEqualTo(exportedSchema(20).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V20_IDENTITY)
            .isEqualTo(exportedSchema(20).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V19_IDENTITY_HASH)
            .isEqualTo(exportedSchema(19).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V19_IDENTITY)
            .isEqualTo(exportedSchema(19).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V18_IDENTITY_HASH)
            .isEqualTo(exportedSchema(18).identityHash)
        assertThat(com.promenar.infinity.data.local.db.recovery.CURRENT_V18_IDENTITY)
            .isEqualTo(exportedSchema(18).identityHash)
        assertThat(exportedSchema(3).identityHash)
            .isEqualTo("7a7a094ee1fd1b9b144a0a241812e53f")
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V2_IDENTITY_HASH)
            .isEqualTo(exportedSchema(2).identityHash)
        assertThat(com.promenar.infinity.data.backup.ROOM_SCHEMA_V1_IDENTITY_HASH)
            .isEqualTo(exportedSchema(1).identityHash)
    }

    @Test
    fun exportedSchemaV1SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/1.json")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        assertThat(sha256).isEqualTo("dbc19124b5704696e9d2a0c480f4b336a46e041a87796e2c9c35552ff7ce7a68")
    }

    @Test
    fun exportedPublishedSchemaV3SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/3.json")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        assertThat(sha256).isEqualTo("f41fd25e5d950275565e3e5cf9462a243c19c122dcb98873d5cd9d748f81d3a2")
    }

    @Test
    fun exportedPublishedSchemaV4SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/4.json")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        assertThat(sha256).isEqualTo("5898229c7b24673df3f1c6127cbd75ca89e5b7ea40f1eff6b3a2e4489b1e1fa8")
    }

    @Test
    fun exportedSchemaV18SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/18.json")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        assertThat(sha256).isEqualTo("ea5d3fbbef90f92ecabb8128e35c0fda747efe124fd41d000a5679e3f101c661")
    }
    @Test
    fun exportedSchemaV19SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/19.json")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        assertThat(sha256).isEqualTo("b6cde4fa63bc2f4fd31805f4675b5916a6a4841e7ef9f89c12b631cd86d8da0a")
    }

    @Test
    fun exportedSchemaV20SnapshotRemainsByteFrozen() {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/20.json")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        assertThat(sha256).isEqualTo("3f88a890f00e583cded76623b3d0c7fca7439942a6148e924732279383258d90")
    }

    private lateinit var context: Context
    private val databaseName = "infinity-v2-baseline-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun realDatabaseCanBeCreatedClosedAndReopenedWithCurrentSchema() {
        val schema = exportedSchema()
        val first = openDatabase()
        val databaseFile = context.getDatabasePath(databaseName)
        val firstConnection = first.openHelper.writableDatabase

        assertThat(databaseFile.isFile).isTrue()
        assertRuntimeSchemaMatchesExport(firstConnection, schema)
        first.close()

        val reopened = openDatabase()
        assertRuntimeSchemaMatchesExport(reopened.openHelper.writableDatabase, schema)
        reopened.close()
    }

    @Test
    fun applicationRegistersMigrationWithoutDestructiveFallback() {
        val source = File("app/src/main/java/com/promenar/infinity/InfinityApplication.kt").readText()
        val builder = "Room.databaseBuilder(this, InfinityDatabase::class.java, \"infinity_v2.db\")"

        assertThat(source).contains(builder)
        assertThat(source).contains("LegacyDatabasePromoter.promote(this)")
        assertThat(source.substringAfter(builder).substringBefore(".build()"))
            .contains(".addMigrations(*com.promenar.infinity.data.local.db.INFINITY_DATABASE_MIGRATIONS)")
        val edges = INFINITY_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertThat(edges).containsAtLeast(
            1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 18, 17 to 18, 18 to 19, 19 to 20, 20 to 21, 21 to 22,
            22 to 23, 23 to 24, 24 to 25, 25 to 26, 26 to 27, 27 to 28, 28 to 29, 29 to 30, 30 to 31, 31 to 32, 32 to 33, 33 to 34,
        )
        // 每个已发布过的起点版本都能沿注册的迁移前进到当前版本。
        val current = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase").listFiles()!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        for (start in listOf(1, 17) + (18 until current)) {
            var version = start
            while (version < current) {
                version = edges.filter { it.first == version }.maxOfOrNull { it.second }
                    ?: throw AssertionError("v$version 没有前进迁移")
            }
            assertThat(version).isEqualTo(current)
        }
        assertThat(source).doesNotContain(".fallbackToDestructiveMigration")
    }

    private fun openDatabase(): InfinityDatabase =
        Room.databaseBuilder(context, InfinityDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

    private fun exportedSchema(version: Int = 35): ExportedSchema {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/$version.json")
        assertThat(file.isFile).isTrue()
        val database = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray.map { entityElement ->
            val entity = entityElement.jsonObject
            ExportedEntity(
                tableName = entity.string("tableName"),
                indices = entity.array("indices").map { indexElement ->
                    val index = indexElement.jsonObject
                    ExportedIndex(
                        name = index.string("name"),
                        unique = index.getValue("unique").jsonPrimitive.boolean,
                        columns = index.strings("columnNames"),
                    )
                },
                foreignKeys = entity.array("foreignKeys").flatMap { foreignKeyElement ->
                    val foreignKey = foreignKeyElement.jsonObject
                    foreignKey.strings("columns").zip(foreignKey.strings("referencedColumns"))
                        .map { (column, referencedColumn) ->
                            ForeignKeyColumn(
                                table = foreignKey.string("table"),
                                column = column,
                                referencedColumn = referencedColumn,
                                onUpdate = foreignKey.string("onUpdate"),
                                onDelete = foreignKey.string("onDelete"),
                            )
                        }
                },
            )
        }
        return ExportedSchema(
            version = database.getValue("version").jsonPrimitive.int,
            identityHash = database.string("identityHash"),
            entities = entities,
        )
    }

    private fun assertRuntimeSchemaMatchesExport(
        database: SupportSQLiteDatabase,
        schema: ExportedSchema,
    ) {
        assertThat(schema.version).isEqualTo(35)
        assertThat(database.longQuery("PRAGMA user_version")).isEqualTo(35L)
        assertThat(database.longQuery("PRAGMA foreign_keys")).isEqualTo(1L)

        val runtimeTables = database.stringColumnQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).filterNot { table ->
            table == "android_metadata" || table.startsWith("sqlite_") ||
                table.startsWith("vectors_fts_")
        }.toSet()
        val expectedTables = schema.entities.mapTo(mutableSetOf()) { it.tableName }
            .apply { add("room_master_table") }

        assertThat(runtimeTables).containsExactlyElementsIn(expectedTables)
        assertThat(runtimeTables).containsAtLeast(
            "tool_execution_ledger",
            "long_term_memories",
            "file_versions",
            "room_master_table",
        )
        assertThat(database.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            .isEqualTo(schema.identityHash)

        schema.entities.forEach { entity ->
            assertIndices(database, entity)
            assertForeignKeys(database, entity)
        }
        assertToolExecutionLedger(database)
        assertFileVersions(database)
        assertWorkspaceRoot(database)
        assertVectorizationTaskFileReferenceContract(database)
        assertAgentCustomizationColumns(database)
        assertMessageKgPathsColumn(database)
    }

    private fun assertIndices(database: SupportSQLiteDatabase, entity: ExportedEntity) {
        val runtimeIndices = database.query("PRAGMA index_list(`${entity.tableName}`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.string("name")
                    put(
                        name,
                        ExportedIndex(
                            name = name,
                            unique = cursor.int("unique") == 1,
                            columns = database.stringColumnQuery("PRAGMA index_info(`$name`)", "name"),
                        ),
                    )
                }
            }
        }
        entity.indices.forEach { expected ->
            assertThat(runtimeIndices).containsKey(expected.name)
            assertThat(runtimeIndices.getValue(expected.name)).isEqualTo(expected)
        }
    }

    private fun assertForeignKeys(database: SupportSQLiteDatabase, entity: ExportedEntity) {
        val runtime = database.query("PRAGMA foreign_key_list(`${entity.tableName}`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyColumn(
                            table = cursor.string("table"),
                            column = cursor.string("from"),
                            referencedColumn = cursor.string("to"),
                            onUpdate = cursor.string("on_update"),
                            onDelete = cursor.string("on_delete"),
                        ),
                    )
                }
            }
        }
        assertThat(runtime).containsExactlyElementsIn(entity.foreignKeys.toSet())
    }

    private fun assertToolExecutionLedger(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("tool_execution_ledger")
        assertThat(columns.keys).containsExactly(
            "session_id",
            "assistant_message_id",
            "tool_call_id",
            "tool_name",
            "runtime_tool_id",
            "arguments_digest",
            "definition_digest",
            "requires_approval",
            "status",
            "result_message_id",
            "error",
            "created_at",
            "updated_at",
        )
        assertThat(columns.getValue("session_id").primaryKeyPosition).isEqualTo(1)
        assertThat(columns.getValue("assistant_message_id").primaryKeyPosition).isEqualTo(2)
        assertThat(columns.getValue("tool_call_id").primaryKeyPosition).isEqualTo(3)
    }

    private fun assertFileVersions(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("file_versions")
        assertThat(columns.keys).containsExactly(
            "id",
            "file_uuid",
            "workspace_root_uuid",
            "hash",
            "content_path",
            "created_by_session_id",
            "created_at",
        )
        assertThat(columns.getValue("id").primaryKeyPosition).isEqualTo(1)
        val indexNames = database.stringColumnQuery("PRAGMA index_list(`file_versions`)", "name")
        assertThat(indexNames).containsAtLeast(
            "index_file_versions_file_uuid",
            "index_file_versions_workspace_root_uuid",
        )
    }

    private fun assertWorkspaceRoot(database: SupportSQLiteDatabase) {
        val column = database.tableColumns("workspace_files").getValue("workspace_root_uuid")
        assertThat(column.notNull).isTrue()
    }

    private fun assertVectorizationTaskFileReferenceContract(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("vectorization_tasks")
        assertThat(columns.keys).containsAtLeast(
            "workspace_root_uuid",
            "kg_strategy",
            "skip_vectorization",
            "sub_status",
            "source_mime_type",
            "content_truncated",
            "target_content_hash",
            "target_epoch",
        )
        assertThat(columns.getValue("session_id").notNull).isFalse()
        assertThat(columns.getValue("target_content_hash").notNull).isFalse()
        assertThat(columns.getValue("target_epoch").notNull).isTrue()
        assertThat(columns.getValue("target_epoch").defaultValue).isEqualTo("0")
        val indexNames = database.stringColumnQuery("PRAGMA index_list(`vectorization_tasks`)", "name")
        assertThat(indexNames).containsAtLeast(
            "index_vectorization_tasks_workspace_root_uuid_doc_id",
            "index_vectorization_tasks_workspace_root_uuid_doc_id_type",
        )
        val foreignKeyPairs = database.query("PRAGMA foreign_key_list(`vectorization_tasks`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    if (cursor.string("table") == "workspace_files") {
                        add(cursor.string("from") to cursor.string("to"))
                    }
                }
            }
        }
        assertThat(foreignKeyPairs).containsExactly(
            "workspace_root_uuid" to "workspace_root_uuid",
            "doc_id" to "uuid",
        )
    }

    private fun assertAgentCustomizationColumns(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("agents")
        assertThat(columns).containsKey("name_customized")
        assertThat(columns).containsKey("description_customized")
        assertThat(columns.getValue("name_customized").notNull).isTrue()
        assertThat(columns.getValue("description_customized").notNull).isTrue()
    }

    private fun assertMessageKgPathsColumn(database: SupportSQLiteDatabase) {
        val column = database.tableColumns("messages").getValue("kg_paths")
        assertThat(column.notNull).isFalse()
    }

    private fun SupportSQLiteDatabase.tableColumns(table: String): Map<String, RuntimeColumn> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.string("name"),
                        RuntimeColumn(
                            notNull = cursor.int("notnull") == 1,
                            primaryKeyPosition = cursor.int("pk"),
                            defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.stringColumnQuery(
        sql: String,
        column: String = "name",
    ): List<String> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.string(column))
        }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.array(key: String): JsonArray = get(key)?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.strings(key: String): List<String> =
        array(key).map { it.jsonPrimitive.content }

    private data class ExportedSchema(
        val version: Int,
        val identityHash: String,
        val entities: List<ExportedEntity>,
    )

    private data class ExportedEntity(
        val tableName: String,
        val indices: List<ExportedIndex>,
        val foreignKeys: List<ForeignKeyColumn>,
    )

    private data class ExportedIndex(
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
    )

    private data class ForeignKeyColumn(
        val table: String,
        val column: String,
        val referencedColumn: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private data class RuntimeColumn(
        val notNull: Boolean,
        val primaryKeyPosition: Int,
        val defaultValue: String?,
    )
}
