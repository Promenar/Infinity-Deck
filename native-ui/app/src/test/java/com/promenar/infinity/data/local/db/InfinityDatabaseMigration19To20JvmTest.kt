package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class InfinityDatabaseMigration19To20JvmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MIGRATED_DATABASE)
        context.deleteDatabase(FRESH_DATABASE)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MIGRATED_DATABASE)
        context.deleteDatabase(FRESH_DATABASE)
    }

    @Test
    fun migrationPreservesV18RowsThroughKgV19AndExternalMountV20() {
        createHistoricalDatabase(18)

        val room = openMigratedDatabase()
        try {
            val database = room.openHelper.writableDatabase
            assertThat(database.longQuery("PRAGMA user_version")).isEqualTo(39L)
            assertThat(database.stringQuery("SELECT name FROM agents WHERE id='existing-agent'"))
                .isEqualTo("已有助手")
            assertThat(database.stringQuery("SELECT title FROM sessions WHERE id='existing-session'"))
                .isEqualTo("已有会话")
            assertThat(database.stringQuery("SELECT content FROM messages WHERE id='existing-message'"))
                .isEqualTo("中文消息与😀")
            assertThat(database.stringQuery("SELECT legacy_attachments FROM messages WHERE id='existing-message'"))
                .isEqualTo("[]")
            assertThat(database.stringQuery("SELECT payload FROM workspace_mutations WHERE operation_id='existing-operation'"))
                .isEqualTo("已有恢复记录")
            assertThat(database.strings("SELECT name FROM sqlite_master WHERE type='table'"))
                .containsAtLeast("external_mounts", "external_snapshots")
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_mounts")).isEqualTo(0L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_snapshots")).isEqualTo(0L)
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertThat(cursor.count).isEqualTo(0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationFromV19PreservesKgEvidenceAndWorkspaceIntent() {
        createHistoricalDatabase()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(MIGRATED_DATABASE).path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL("INSERT INTO kg_nodes(id,name,normalized_name,type,created_at) VALUES('node','实体','实体','concept',1)")
            database.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,session_id,content_hash,task_id,created_at,workspace_document) VALUES('evidence','node','session','existing-session','hash','task',2,0)")
        }
        val room = openMigratedDatabase()
        try {
            val database = room.openHelper.writableDatabase
            assertThat(database.longQuery("PRAGMA user_version")).isEqualTo(39L)
            assertThat(database.stringQuery("SELECT normalized_name FROM kg_nodes WHERE id='node'"))
                .isEqualTo("实体")
            assertThat(database.stringQuery("SELECT content_hash FROM kg_evidence WHERE node_id='node'"))
                .isEqualTo("hash")
            assertThat(database.stringQuery("SELECT kg_strategy FROM workspace_files WHERE uuid='existing-file'"))
                .isEqualTo("full")
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_mounts")).isEqualTo(0L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_snapshots")).isEqualTo(0L)
        } finally {
            room.close()
        }
    }

    @Test
    fun emptyMigrationFailsRoomSchemaValidationAndRollsBackV19Rows() {
        createHistoricalDatabase()
        val emptyMigration = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, MIGRATED_DATABASE)
            .addMigrations(emptyMigration, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION, MIGRATION_38_39_MEDIA_GENERATION)
            .allowMainThreadQueries()
            .build()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                room.openHelper.writableDatabase
            }
            assertThat(failure).hasMessageThat().contains("external_mounts")
        } finally {
            room.close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(MIGRATED_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertThat(database.version).isEqualTo(19)
            database.rawQuery("SELECT content FROM messages WHERE id='existing-message'", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("中文消息与😀")
            }
            database.rawQuery("SELECT payload FROM workspace_mutations WHERE operation_id='existing-operation'", null)
                .use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("已有恢复记录")
                }
        }
    }

    @Test
    fun migrationEnforcesScopedAliasAndSnapshotForeignKeyConstraints() {
        createHistoricalDatabase()

        val room = openMigratedDatabase()
        try {
            val database = room.openHelper.writableDatabase
            database.insertMount("global", "资料", "")
            database.insertMount("session-a", "资料", "会话甲")
            database.insertMount("session-b", "资料", "会话乙")
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertMount("duplicate", "资料", "会话甲")
            }
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_mounts")).isEqualTo(3L)
            database.insertSnapshot("global", "root-a", "file-a")
            database.insertSnapshot("global", "root-b", "file-b")
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertSnapshot("global", "root-a", "other-file")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertSnapshot("missing-mount", "root-c", "file-c")
            }
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_snapshots")).isEqualTo(2L)
            database.execSQL("DELETE FROM external_mounts WHERE id='global'")
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_snapshots")).isEqualTo(0L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM external_mounts")).isEqualTo(2L)
        } finally {
            room.close()
        }
    }

    @Test
    fun migratedDatabaseSchemaMatchesFreshRoomDatabase() {
        createHistoricalDatabase()

        val migrated = openMigratedDatabase()
        val fresh = Room.databaseBuilder(context, InfinityDatabase::class.java, FRESH_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedDatabase = migrated.openHelper.writableDatabase
            val freshDatabase = fresh.openHelper.writableDatabase
            val tables = freshDatabase.strings("SELECT name FROM sqlite_master WHERE type='table'")
                .filterNot { it.startsWith("sqlite_") || it == "android_metadata" }
            assertThat(tables).containsAtLeast("external_mounts", "external_snapshots")
            tables.forEach { table ->
                assertThat(TableInfo.read(migratedDatabase, table))
                    .isEqualTo(TableInfo.read(freshDatabase, table))
            }
            assertThat(migratedDatabase.stringQuery("SELECT identity_hash FROM room_master_table WHERE id=42"))
                .isEqualTo(freshDatabase.stringQuery("SELECT identity_hash FROM room_master_table WHERE id=42"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test
    fun daoVisibilityIncludesOnlyOwnSessionWithoutLeakingLegacyGlobalRows() = runBlocking<Unit> {
        createHistoricalDatabase()
        val room = openMigratedDatabase()
        try {
            val dao = room.externalMountDao()
            val global = mount("global", "全局", "")
            val local = mount("local", "会话资料", "会话甲")
            val other = mount("other", "会话资料", "会话乙")
            listOf(global, local, other).forEach { dao.insert(it) }

            assertThat(dao.visible("会话甲")).containsExactly(local)
            assertThat(dao.observeVisible("会话乙").first()).containsExactly(other)
            assertThat(dao.visible("未知会话")).isEmpty()
            assertThat(dao.all()).containsExactly(global, local, other)
            assertThat(runCatching { dao.insert(local.copy(id = "duplicate")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            val updated = local.copy(alias = "更新别名", readOnly = true, unavailableReason = "授权已撤销")
            dao.update(updated)
            assertThat(dao.get("local")).isEqualTo(updated)
            assertThat(dao.visible("会话甲")).containsExactly(updated)
        } finally {
            room.close()
        }
    }

    @Test
    fun daoSnapshotsAreScopedToWorkspaceAndCascadeOnlyWhenMountIsDeleted() = runBlocking<Unit> {
        createHistoricalDatabase()
        val room = openMigratedDatabase()
        try {
            val dao = room.externalMountDao()
            val mount = mount("mount", "资料", "")
            dao.insert(mount)
            val first = ExternalSnapshotEntity("mount", "/文件.txt", "root-a", "file-a", 1, "hash-a", 2)
            val second = first.copy(workspaceRootUuid = "root-b", fileUuid = "file-b")
            val unrelated = first.copy(sourcePath = "/其它.txt")
            listOf(first, second, unrelated).forEach { dao.putSnapshot(it) }

            assertThat(dao.snapshot("mount", "/文件.txt", "root-a")).isEqualTo(first)
            assertThat(dao.snapshots("mount", "/文件.txt")).containsExactly(first, second)
            assertThat(dao.snapshot("mount", "/文件.txt", "missing-root")).isNull()
            val refreshed = first.copy(fileUuid = "new-file", sourceModified = 5, contentHash = "new-hash", importedAt = 6)
            dao.putSnapshot(refreshed)
            dao.update(mount.copy(alias = "重新命名"))
            assertThat(dao.snapshot("mount", "/文件.txt", "root-a")).isEqualTo(refreshed)
            assertThat(dao.snapshots("mount", "/文件.txt")).containsExactly(refreshed, second)

            dao.delete("mount")
            assertThat(dao.get("mount")).isNull()
            assertThat(dao.snapshots("mount", "/文件.txt")).isEmpty()
            assertThat(dao.snapshots("mount", "/其它.txt")).isEmpty()
        } finally {
            room.close()
        }
    }

    private fun mount(id: String, alias: String, scope: String) =
        ExternalMountEntity(id, alias, "saf_tree", "content://test/tree/$id", scope, false, 1)

    private fun openMigratedDatabase(): InfinityDatabase =
        Room.databaseBuilder(context, InfinityDatabase::class.java, MIGRATED_DATABASE)
            .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION, MIGRATION_38_39_MEDIA_GENERATION)
            .allowMainThreadQueries()
            .build()

    private fun createHistoricalDatabase(version: Int = 19) {
        val file = File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/$version.json")
        val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        val path = context.getDatabasePath(MIGRATED_DATABASE)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val tableName = entity.string("tableName")
                database.execSQL(entity.string("createSql").replace("\${TABLE_NAME}", tableName))
                entity["indices"]?.jsonArray?.forEach { index ->
                    database.execSQL(index.jsonObject.string("createSql").replace("\${TABLE_NAME}", tableName))
                }
                entity["contentSyncTriggers"]?.jsonArray?.forEach { trigger ->
                    database.execSQL(trigger.jsonPrimitive.content)
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
            database.version = version
            database.execSQL(
                """INSERT INTO agents(id,name,description,name_customized,description_customized,
                    system_prompt,model,icon,color,is_pinned,created_at,use_inherited_config)
                    VALUES('existing-agent','已有助手','',0,0,'','','','',0,1,1)""".trimIndent(),
            )
            database.execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES('existing-session','existing-agent','已有会话',0,0,1,2)""".trimIndent(),
            )
            database.execSQL(
                """INSERT INTO messages(id,session_id,role,content,rag_references_loading,
                    is_archived,is_error,legacy_attachments,created_at)
                    VALUES('existing-message','existing-session','user','中文消息与😀',0,0,0,'[]',3)""".trimIndent(),
            )
            if (version >= 19) {
                database.execSQL(
                    """INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,physical_root_path,
                        materialized_path,is_directory,name,hash,size_bytes,created_at,updated_at,
                        vector_version,kg_version,in_recycle_bin,kg_strategy)
                        VALUES('existing-file','existing-root',NULL,'/private/root','/文档.txt',0,'文档.txt',
                        'hash',1,1,1,1,1,0,'full')""".trimIndent(),
                )
            }
            database.execSQL(
                """INSERT INTO workspace_mutations(operation_id,workspace_root_uuid,operation_type,
                    payload_version,payload,payload_digest,state,created_at,updated_at)
                    VALUES('existing-operation','existing-root','write',1,'已有恢复记录','digest','pending',4,5)""".trimIndent(),
            )
        }
    }

    private fun SupportSQLiteDatabase.insertMount(id: String, alias: String, scope: String) {
        execSQL(
            """INSERT INTO external_mounts(id,alias,source_type,source_id,scope_key,read_only,created_at)
                VALUES(?,?,'saf_tree','content://test/tree/root',?,0,1)""".trimIndent(),
            arrayOf(id, alias, scope),
        )
    }

    private fun SupportSQLiteDatabase.insertSnapshot(mountId: String, rootId: String, fileId: String) {
        execSQL(
            """INSERT INTO external_snapshots(mount_id,source_path,workspace_root_uuid,file_uuid,
                source_modified,content_hash,imported_at) VALUES(?,'/文件.txt',?,?,3,'hash',4)""".trimIndent(),
            arrayOf(mountId, rootId, fileId),
        )
    }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> = query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val MIGRATED_DATABASE = "migration-19-20.db"
        const val FRESH_DATABASE = "migration-20-fresh.db"
    }
}
