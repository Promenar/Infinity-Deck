package com.promenar.infinity.data.library.ask

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 删除消息会级联删掉引用；撤销删除时由快照放回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class ChatCitationUndoTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: RoomKnowledgeCitationRepository
    private val answer = MessageEntity(id = "answer", sessionId = "s", role = "assistant", content = "回答", status = "success", createdAt = 2)
    private val other = MessageEntity(id = "other", sessionId = "s", role = "assistant", content = "另一条", status = "success", createdAt = 3)

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomKnowledgeCitationRepository(database)
        database.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insertAll(listOf(answer, other))
        listOf("d1", "d2").forEach { id ->
            database.fileEntryDao().insert(FileEntry(uuid = id, parentUuid = null, name = "虚构文档$id", hash = "", physicalRootPath = "",
                materializedPath = "$id.txt", createdAt = 1, updatedAt = 1))
        }
        database.knowledgeCitationDao().insertAll(listOf(
            KnowledgeCitationEntity("c1", "d1", 0, "v1", "CHAT", "s", "answer", null, "K1", 10),
            KnowledgeCitationEntity("c2", "d2", 1, "v2", "CHAT", "s", "answer", null, "K2", 10),
            KnowledgeCitationEntity("c3", "d1", 2, "v3", "CHAT", "s", "other", null, "K1", 11),
            KnowledgeCitationEntity("c4", "d1", null, null, "ASK", null, null, "问题", "1", 12),
        ))
    }

    @After fun tearDown() { database.close() }

    @Test fun `快照只取被删消息的对话引用，撤销后原样放回`() = runBlocking<Unit> {
        val snapshot = repository.snapshotChat(listOf("answer"))
        assertThat(snapshot.map { it.id }).containsExactly("c1", "c2")
        database.messageDao().deleteByIdsInSession("s", listOf("answer"))
        assertThat(database.knowledgeCitationDao().forMessages(listOf("answer"))).isEmpty()
        database.messageDao().insertAll(listOf(answer))
        repository.restoreChat(snapshot)
        assertThat(database.knowledgeCitationDao().forMessages(listOf("answer"))).containsExactlyElementsIn(snapshot)
        // 重复撤销不报错也不重复写入。
        repository.restoreChat(snapshot)
        assertThat(database.knowledgeCitationDao().forSession("s")).hasSize(3)
    }

    @Test fun `文档已删除或消息未恢复的引用跳过`() = runBlocking<Unit> {
        val snapshot = repository.snapshotChat(listOf("answer", "other"))
        database.messageDao().deleteByIdsInSession("s", listOf("answer", "other"))
        database.openHelper.writableDatabase.execSQL("DELETE FROM workspace_files WHERE uuid = 'd2'")
        database.messageDao().insertAll(listOf(answer))
        repository.restoreChat(snapshot)
        assertThat(database.knowledgeCitationDao().forSession("s").map { it.id }).containsExactly("c1")
    }
}
