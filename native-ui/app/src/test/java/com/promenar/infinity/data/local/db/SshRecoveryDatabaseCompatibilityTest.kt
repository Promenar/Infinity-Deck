package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SshRecoveryDatabaseCompatibilityTest {
    @Test
    fun `独立恢复数据库保持真实v18身份且不创建SSH表`() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), InfinityV18RecoveryDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(18)
            }
            sqlite.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("c32f59706ea482fd5697681569c6fdb5")
            }
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ssh_hosts'").use {
                assertThat(it.moveToFirst()).isFalse()
            }
        } finally { database.close() }
    }

    @Test
    fun `保留历史库时Promoter精确验证当前正式版本且不覆盖旧数据`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("infinity.db")
        context.deleteDatabase("infinity_v2.db")
        try {
            context.getDatabasePath("infinity.db").parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("infinity.db"), null).use {
                it.execSQL("CREATE TABLE sentinel(value TEXT)")
                it.execSQL("INSERT INTO sentinel VALUES('historical')")
                it.version = 17
            }
            Room.databaseBuilder(context, InfinityDatabase::class.java, "infinity_v2.db")
                .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()

            assertThat(LegacyDatabasePromoter.promote(context)).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
            SQLiteDatabase.openDatabase(context.getDatabasePath("infinity.db").path, null, SQLiteDatabase.OPEN_READONLY)
                .use { sqlite ->
                    sqlite.rawQuery("SELECT value FROM sentinel", null).use {
                        check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("historical")
                    }
                }
        } finally {
            context.deleteDatabase("infinity.db")
            context.deleteDatabase("infinity_v2.db")
        }
    }

    @Test
    fun `旧主库头与当前版本WAL共存时Promoter接受完整新库且源库零写入`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("infinity.db")
        context.deleteDatabase("infinity_v2.db")
        try {
            context.getDatabasePath("infinity.db").parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("infinity.db"), null).use {
                it.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, attachments TEXT)")
                it.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
                it.execSQL("INSERT INTO room_master_table VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')")
                it.version = 17
            }
            Room.databaseBuilder(context, InfinityDatabase::class.java, "infinity_v2.db")
                .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()
            val main = context.getDatabasePath("infinity_v2.db")
            SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.enableWriteAheadLogging()
                sqlite.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { check(it.moveToFirst()) }
                sqlite.version = 5
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0)
                }
                sqlite.version = 32
                RandomAccessFile(main, "r").use { file -> file.seek(60); assertThat(file.readInt()).isEqualTo(5) }
                val before = sourceHashes(context)
                assertThat(before.keys).contains("infinity_v2.db-wal")

                assertThat(LegacyDatabasePromoter.promote(context)).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
                RandomAccessFile(main, "r").use { file -> file.seek(60); assertThat(file.readInt()).isEqualTo(5) }
                assertThat(sourceHashes(context)).isEqualTo(before)
            }
        } finally {
            context.deleteDatabase("infinity.db")
            context.deleteDatabase("infinity_v2.db")
        }
    }

    @Test
    fun `WAL隔离副本的当前版本白名单包含完整v25至v32身份`() {
        val accepted = com.promenar.infinity.data.local.db.recovery.CURRENT_RECOVERY_SOURCE_IDENTITIES
        assertThat(accepted).contains(32 to INFINITY_SCHEMA_V32_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(32 to INFINITY_SCHEMA_V31_IDENTITY_HASH)
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(32, INFINITY_SCHEMA_V32_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(31 to INFINITY_SCHEMA_V31_IDENTITY_HASH)
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(31, INFINITY_SCHEMA_V31_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(30 to INFINITY_SCHEMA_V30_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(30 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(30, INFINITY_SCHEMA_V30_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(29 to INFINITY_SCHEMA_V29_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(29 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(29, INFINITY_SCHEMA_V29_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(28 to INFINITY_SCHEMA_V28_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(28 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(28, INFINITY_SCHEMA_V28_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(27 to INFINITY_SCHEMA_V27_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(27 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(27, INFINITY_SCHEMA_V27_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(26 to INFINITY_SCHEMA_V26_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(26 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(26, INFINITY_SCHEMA_V26_IDENTITY_HASH)).isTrue()
        assertThat(accepted).contains(25 to INFINITY_SCHEMA_V25_IDENTITY_HASH)
        assertThat(accepted).doesNotContain(25 to "wrong-identity")
        assertThat(com.promenar.infinity.data.local.db.recovery.isTrustedCurrentRoomSchema(25, INFINITY_SCHEMA_V25_IDENTITY_HASH)).isTrue()
    }

    private fun sourceHashes(context: Context): Map<String, String> =
        listOf("infinity.db", "infinity_v2.db").flatMap { name ->
            listOf(name, "$name-wal", "$name-journal")
        }.map(context::getDatabasePath).filter { it.isFile }.associate { file ->
            file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
}
