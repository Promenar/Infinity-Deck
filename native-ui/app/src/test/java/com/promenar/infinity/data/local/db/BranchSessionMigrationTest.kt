package com.promenar.infinity.data.local.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class BranchSessionMigrationTest {
    @Test
    fun `v28迁移保留会话与消息并补齐分支默认值`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "branch-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val schema = listOf(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/28.json"),
                    File("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/28.json")).first(File::isFile)
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
                sqlite.execSQL("INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at) VALUES('old','agent','保留会话',0,0,1,2)")
                sqlite.execSQL("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('message','old','user','保留消息',3)")
                sqlite.version = 28
            }
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, com.promenar.infinity.data.local.db.MIGRATION_36_37_DOCUMENT_SETTINGS).allowMainThreadQueries().build()
            try {
                assertThat(room.openHelper.writableDatabase.version).isEqualTo(36)
                val session = room.sessionDao().getById("old")!!
                assertThat(session.title).isEqualTo("保留会话")
                assertThat(session.parentSessionId).isNull()
                assertThat(session.branchPlanJson).isNull()
                assertThat(session.worktreeDirectory).isNull()
                assertThat(session.worktreeBranch).isNull()
                assertThat(session.worktreeBase).isNull()
                assertThat(session.parentSessionDeleted).isFalse()
                assertThat(session.sessionAutoWakeCount).isEqualTo(0)
                assertThat(session.sessionAutoProcessingPaused).isFalse()
                assertThat(room.messageDao().getById("message")!!.content).isEqualTo("保留消息")
                room.sessionDao().updateRow(session.copy(parentSessionId = "parent", branchPlanJson = "{}",
                    worktreeDirectory = "/project/feature", worktreeBranch = "feature", worktreeBase = "abc",
                    parentSessionDeleted = true, sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))
                assertThat(room.sessionDao().getById("old")!!.sessionAutoWakeCount).isEqualTo(8)
                assertThat(SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.hasCompleteCurrentRoomSchema(35, INFINITY_SCHEMA_V35_IDENTITY_HASH)
                }).isTrue()
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
