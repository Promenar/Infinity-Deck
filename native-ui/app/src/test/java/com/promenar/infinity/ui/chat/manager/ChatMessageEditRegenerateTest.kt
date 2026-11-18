package com.promenar.infinity.ui.chat.manager

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ContextSummaryEntity
import com.promenar.infinity.data.media.annotation.ImageAnnotationContext
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.session.EditedUserMessagePayload
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationKind
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class ChatMessageEditRegenerateTest {
    @Test
    fun `暂存与连续编辑保留旧摘要且回滚恢复原正文附件引文和上下文`() = runBlocking {
        withSession { manager, messages, store, original, _ ->
            val payload = EditedUserMessagePayload("新问题", userImages = emptyList(), userDocuments = emptyList(),
                userImageReferences = emptyList(), userImageAnnotations = emptyList(),
                quotes = listOf(MessageQuote("reply", "新引用", "")))
            manager.stageUserMessageEditForRegeneration("session", original.id, payload)
            val staged = requireNotNull(messages.getById(original.id))
            assertThat(staged.content).isEqualTo("新问题")
            assertThat(staged.userImages.orEmpty()).isEmpty()
            assertThat(staged.userImageReferences.orEmpty()).isEmpty()
            assertThat(staged.userDocuments.orEmpty()).isEmpty()
            assertThat(staged.userImageAnnotations).isNull()
            assertThat(staged.userImageAnnotationContext).isNull()
            assertThat(staged.quotes).containsExactly(MessageQuote("reply", "新引用", ""))
            assertThat(staged.turnContext).isNull()
            assertThat(staged.isArchived).isTrue()
            assertThat(store.getSession("session")?.summary).isEqualTo("旧摘要")

            manager.stageUserMessageEditForRegeneration("session", original.id, EditedUserMessagePayload("再编辑"))
            manager.rollbackStagedUserMessageEditIfPresent("session", original.id)
            manager.rollbackStagedUserMessageEditIfPresent("session", original.id)
            assertThat(messages.getById(original.id)).isEqualTo(original)
            assertThat(store.getSession("session")?.messages?.first()).isEqualTo(original)
            assertThat(store.getSession("session")?.summary).isEqualTo("旧摘要")
        }
    }

    @Test
    fun `提交归档用户编辑会清除摘要恢复归档历史并保留新轮次上下文`() = runBlocking {
        withSession { manager, messages, store, original, runtimeManager ->
            manager.stageUserMessageEditForRegeneration("session", original.id, EditedUserMessagePayload("已提交"))
            runtimeManager.persistModelInputs("session", original.id, mapOf("turnContext" to "新上下文"))
            runtimeManager.commitStagedUserMessageEditIfPresent("session", original.id)
            manager.rollbackStagedUserMessageEditIfPresent("session", original.id)

            val committed = requireNotNull(messages.getById(original.id))
            assertThat(committed.content).isEqualTo("已提交")
            assertThat(committed.turnContext).isEqualTo("新上下文")
            assertThat(committed.userImageReferences).isEqualTo(original.userImageReferences)
            assertThat(messages.getBySession("session").any { it.isArchived }).isFalse()
            assertThat(store.getSession("session")?.summary).isNull()
            assertThat(store.getSession("session")?.messages?.first()).isEqualTo(committed)
        }
    }

    @Test
    fun `批量替换后续用户轮次时保留新回复并在同一删除事务中清除摘要`() = runBlocking {
        withSession { manager, messages, store, original, _ ->
            val laterUser = Message("later", MessageRole.USER, "后续问题", createdAt = 3)
            val freshReply = Message("fresh", MessageRole.ASSISTANT, "新回复", parentMessageId = original.id, createdAt = 4)
            manager.addMessage("session", laterUser)
            manager.addMessage("session", freshReply)
            manager.stageUserMessageEditForRegeneration("session", original.id, EditedUserMessagePayload("已编辑"))
            manager.deleteMessages("session", listOf("reply", "later"), replacementUserMessageId = original.id)

            assertThat(messages.getBySession("session").map { it.id }).containsExactly(original.id, "fresh").inOrder()
            assertThat(store.getSession("session")?.summary).isNull()
            assertThat(messages.getById(original.id)?.isArchived).isFalse()
            manager.commitStagedUserMessageEditIfPresent("session", original.id)
            assertThat(messages.getById(original.id)?.content).isEqualTo("已编辑")
        }
    }

    private suspend fun withSession(
        block: suspend (MessageManager, MessageRepository, ChatStore, Message, MessageManager) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val messages = MessageRepository(database.messageDao())
            val sessions = SessionRepository(database.sessionDao(), database.messageDao(), database = database)
            val store = ChatStore()
            val original = Message("user", MessageRole.USER, "旧问题", createdAt = 1, isArchived = true,
                userImages = listOf("旧图"), userImageReferences = listOf(WorkspaceDocumentReference("/旧图.png", 0, 1)),
                userDocuments = listOf(MessageDocumentAttachment("doc", "旧文档", "text/plain", "原文", 6, "hash", 1)),
                userImageAnnotations = listOf(ImageAnnotation(ImageAnnotationKind.MARKUP, 4, 4, "/标注.png", "/旧图.png")),
                userImageAnnotationContext = ImageAnnotationContext("session", linuxNamespace = false),
                quotes = listOf(MessageQuote("old", "原引文", "批注")), turnContext = "旧上下文")
            sessions.create(Session("session", "agent"))
            messages.insert(original, "session")
            messages.insert(Message("reply", MessageRole.ASSISTANT, "旧回复", parentMessageId = original.id, createdAt = 2), "session")
            database.contextSummaryDao().insert(ContextSummaryEntity("summary", "session", original.id, original.id, "旧摘要", 1))
            val persisted = requireNotNull(sessions.getById("session"))
            store.update { it.copy(sessions = listOf(persisted)) }
            block(MessageManager(store, messages, sessions), messages, store, original, MessageManager(store, messages, sessions))
            if (store.getSession("session")?.summary == null) {
                assertThat(database.contextSummaryDao().getBySessionId("session")).isEmpty()
            } else {
                assertThat(database.contextSummaryDao().getBySessionId("session").single().summaryContent).isEqualTo("旧摘要")
            }
        } finally {
            database.close()
        }
    }
}
