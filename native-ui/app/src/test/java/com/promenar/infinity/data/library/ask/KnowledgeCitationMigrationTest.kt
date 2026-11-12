package com.promenar.infinity.data.library.ask

import com.promenar.infinity.data.local.db.MIGRATION_36_37_KEYCHAIN
import com.promenar.infinity.data.local.db.MIGRATION_37_38_PRODUCTION
import com.promenar.infinity.data.local.db.MIGRATION_38_39_MEDIA_GENERATION
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.MIGRATION_32_33
import com.promenar.infinity.data.local.db.MIGRATION_33_34
import com.promenar.infinity.data.local.db.MIGRATION_34_35
import com.promenar.infinity.data.local.db.MIGRATION_35_36_TIMELINE
import com.promenar.infinity.data.model.RagReference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeCitationMigrationTest {
    @Test fun oldDatabaseMigratesAndCitationLifecycleIsPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "citation-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            val schema = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/32.json").readText())
                .jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                schema.getValue("entities").jsonArray.forEach { element ->
                    val entity = element.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    sqlite.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                    entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
                seed(sqlite, "agents", mapOf("id" to "agent", "name" to "虚构助手"))
                seed(sqlite, "sessions", mapOf("id" to "s", "agent_id" to "agent", "title" to "虚构会话"))
                seed(sqlite, "messages", mapOf("id" to "m", "session_id" to "s", "role" to "assistant", "content" to "原文"))
                seed(sqlite, "workspace_files", mapOf("uuid" to "d1", "name" to "文档一"))
                seed(sqlite, "workspace_files", mapOf("uuid" to "d2", "name" to "文档二"))
                sqlite.version = 32
            }
            val db = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE, MIGRATION_36_37_KEYCHAIN, MIGRATION_37_38_PRODUCTION, MIGRATION_38_39_MEDIA_GENERATION).allowMainThreadQueries().build()
            try {
                val sqlite = db.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(39)
                assertThat(db.messageDao().getById("m")!!.content).isEqualTo("原文")
                val repository = RoomKnowledgeCitationRepository(db)
                val first = KnowledgeCitationEntity("c1", "d1", 2, "v1", "CHAT", "s", "m", null, "K1", 10)
                val second = KnowledgeCitationEntity("c2", "d2", 3, "v2", "CHAT", "s", "m", null, "K2", 20)
                repository.replaceChat("m", listOf(first))
                repository.replaceChat("m", listOf(second))
                assertThat(repository.observeForDocument("d1").first()).isEmpty()
                val chat = repository.observeForDocument("d2").first().single()
                assertThat(chat.messageId).isEqualTo("m")
                assertThat(chat.sessionTitle).isEqualTo("虚构会话")
                assertThat(chat.agentName).isEqualTo("虚构助手")
                repository.recordAsk(listOf(
                    KnowledgeCitationEntity("c3", "d1", null, null, "ASK", null, null, "问题", "1", 30),
                    KnowledgeCitationEntity("c4", "d2", null, null, "ASK", null, null, "问题", "2", 40),
                ))
                assertThat(repository.observeRecentDocuments().first().map { it.documentId }).containsExactly("d2", "d1").inOrder()
                assertThat(repository.observeTopDocuments().first().map { it.documentId }).containsExactly("d2", "d1").inOrder()
                repository.recordAsk(listOf(KnowledgeCitationEntity("c5", "d1", null, null, "ASK", null, null, "问题", "1", 50)))
                assertThat(repository.observeTopDocuments().first().map { it.documentId }).containsExactly("d1", "d2").inOrder()
                sqlite.execSQL("DELETE FROM messages WHERE id = 'm'")
                assertThat(repository.observeForDocument("d2").first().map { it.label }).containsExactly("2")
                db.sessionDao().insert(com.promenar.infinity.data.local.db.entity.SessionEntity("s2", "agent", createdAt = 1, updatedAt = 1))
                db.messageDao().insert(com.promenar.infinity.data.local.db.entity.MessageEntity("m2", "s2", "assistant", "回答", createdAt = 1))
                repository.replaceChat("m2", listOf(KnowledgeCitationEntity("c6", "d1", null, null, "CHAT", "s2", "m2", null, "K1", 60)))
                db.sessionDao().deleteById("s2")
                assertThat(repository.observeForDocument("d1").first().map { it.sessionId }).doesNotContain("s2")
                sqlite.execSQL("DELETE FROM workspace_files WHERE uuid = 'd2'")
                assertThat(repository.observeForDocument("d2").first()).isEmpty()
                sqlite.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun onlyActuallyMentionedTurnLabelsAreSelected() {
        val references = listOf(
            RagReference(id = "v1", documentId = "d1", chunkIndex = 2),
            RagReference(id = "v2", documentId = "d2", chunkIndex = 3),
        )
        val selected = selectChatCitations("s", "m", "内容 [K 2] [K2] [K9]", mapOf("v1" to "K1", "v2" to "K2"), references, 10)
        assertThat(selected.map { it.documentId }).containsExactly("d2")
        assertThat(selected.single().label).isEqualTo("K2")
        assertThat(selected.single().chunkIndex).isEqualTo(3)
    }

    @Test fun successfulToolContinuationRetainsEachCompletedAssistantMessage() {
        val session = com.promenar.infinity.data.model.Session("s", "agent", messages = listOf(
            com.promenar.infinity.data.model.Message("old", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "旧版 [K1]"),
            com.promenar.infinity.data.model.Message("first", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "依据 [K1]"),
            com.promenar.infinity.data.model.Message("tool", com.promenar.infinity.data.model.MessageRole.TOOL, "结果"),
            com.promenar.infinity.data.model.Message("last", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "结论"),
        ))
        val messages = completedCitationMessages(session, setOf("old", "first", "tool", "last"),
            setOf("first", "tool", "last"), setOf("old"), "current-turn")
        assertThat(messages.map { it.id }).containsExactly("first", "last").inOrder()
        val references = listOf(RagReference(id = "v1", documentId = "d1", chunkIndex = 2))
        val rows = messages.flatMap { selectChatCitations("s", it.id, it.content, mapOf("v1" to "K1"), references, 1) }
        assertThat(rows.map { it.messageId }).containsExactly("first")
    }

    @Test fun approvalResumptionKeepsAssistantTextFromTheSameUserTurn() {
        val session = com.promenar.infinity.data.model.Session("s", "agent", messages = listOf(
            com.promenar.infinity.data.model.Message("user", com.promenar.infinity.data.model.MessageRole.USER, "问题"),
            com.promenar.infinity.data.model.Message("paused", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "依据 [K1]", status = "paused", parentMessageId = "user"),
            com.promenar.infinity.data.model.Message("resumed", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "结论", parentMessageId = "user"),
        ))
        val messages = completedCitationMessages(session, setOf("resumed"), setOf("user", "paused", "resumed"), emptySet(), "user")
        assertThat(messages.map { it.id }).containsExactly("paused", "resumed").inOrder()
    }

    private fun seed(sqlite: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        sqlite.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                val value = fields[column]
                if (value != null) values.put(column, value)
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    if (cursor.getString(2).uppercase() in setOf("INTEGER", "REAL")) values.put(column, 0)
                    else values.put(column, "")
                }
            }
        }
        sqlite.insertOrThrow(table, null, values)
    }
}
