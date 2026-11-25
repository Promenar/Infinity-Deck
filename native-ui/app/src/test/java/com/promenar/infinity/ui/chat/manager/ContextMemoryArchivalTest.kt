package com.promenar.infinity.ui.chat.manager

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.ContextCompactionInput
import com.promenar.infinity.data.generation.ContextCompactionFailureReason
import com.promenar.infinity.data.generation.ContextCompactionOutcome
import com.promenar.infinity.data.generation.ContextCompactionPipeline
import com.promenar.infinity.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.infinity.data.generation.PromptWindow
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.ContextCompactionSource
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.EmbeddingResult
import com.promenar.infinity.data.rag.RecursiveCharacterTextSplitter
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ContextMemoryArchivalTest {
    private val databases = mutableListOf<InfinityDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(InfinityDatabase::close)
    }

    @Test
    fun `真实记忆向量归档成功只更新向量状态而不提前归档摘要候选`() = runTest {
        val fixture = fixture()

        fixture.archiveMemory(fixture.history())

        assertThat(fixture.database.vectorDao().getBySessionId(SESSION)).isNotEmpty()
        assertHistoryUnarchived(fixture)
        assertThat(fixture.repository.getById(SESSION)?.summary).isNull()
        assertThat(fixture.database.sessionDao().getCurrentContextSummary(SESSION)).isNull()
        assertOriginalHistorySent(fixture, fixture.current())
    }

    @Test
    fun `记忆归档成功但历史摘要失败后下一请求仍从Store和Room发送原文`() = runTest {
        val fixture = fixture()

        val result = ContextCompactionPipeline().compact(
            input(fixture), fixture::prompt,
            summarizeHistory = { _, messages ->
                fixture.archiveMemory(messages)
                ContextCompactionOutcome.Failure(ContextCompactionFailureReason.EMPTY_OUTPUT)
            },
        )

        assertThat(result.summaryFailed).isTrue()
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertHistoryUnarchived(fixture)
        assertOriginalHistorySent(fixture, fixture.current())
        assertOriginalHistorySent(fixture, checkNotNull(fixture.repository.getById(SESSION)))
        assertThat(fixture.repository.getById(SESSION)?.summary).isNull()
    }

    @Test
    fun `记忆归档成功但摘要取消后下一请求仍保留原文且取消向外传播`() = runTest {
        val fixture = fixture()
        val cancellation = CancellationException("摘要已取消")

        val failure = runCatching {
            ContextCompactionPipeline().compact(
                input(fixture), fixture::prompt,
                summarizeHistory = { _, messages ->
                    fixture.archiveMemory(messages)
                    throw cancellation
                },
            )
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertHistoryUnarchived(fixture)
        assertOriginalHistorySent(fixture, fixture.current())
        assertOriginalHistorySent(fixture, checkNotNull(fixture.repository.getById(SESSION)))
        assertThat(fixture.database.sessionDao().getCurrentContextSummary(SESSION)).isNull()
    }

    @Test
    fun `只有成功提交摘要才归档历史且后续向量状态不改变归档边界`() = runTest {
        val fixture = fixture()
        fixture.archiveMemory(fixture.history())
        assertHistoryUnarchived(fixture)

        fixture.sessions.commitContextCompaction(
            SESSION, "历史已总结", HISTORY_IDS,
            ContextCompactionSource(fixture.current().summary, fixture.history()),
        )

        assertThat(fixture.repository.getById(SESSION)?.summary).isEqualTo("历史已总结")
        val allIds = HISTORY_IDS + CURRENT
        for (status in listOf("processing", "success", "error", "skipped")) {
            fixture.messages.setVectorizationStatus(SESSION, allIds, status)
            val persisted = checkNotNull(fixture.repository.getById(SESSION))
            val visible = fixture.current()
            allIds.forEach { id ->
                val expectedArchived = id in HISTORY_IDS
                val row = checkNotNull(fixture.database.messageDao().getById(id))
                assertThat(row.vectorizationStatus).isEqualTo(status)
                assertThat(row.isArchived).isEqualTo(if (expectedArchived) 1 else 0)
                assertThat(persisted.messages.single { it.id == id }.isArchived).isEqualTo(expectedArchived)
                assertThat(visible.messages.single { it.id == id }.isArchived).isEqualTo(expectedArchived)
            }
        }
        assertThat(fixture.prompt(fixture.current()).messages.filter { it.role != "system" }.map { it.content })
            .containsExactly("当前问题")
    }

    @Test
    fun `向量状态持久化与摘要提交使用同一会话锁以保护摘要归档状态`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val commitReachedRepository = CompletableDeferred<Unit>()
        var pauseWrites = false
        val fixture = fixture(
            beforeStatusWrite = {
                if (pauseWrites) {
                    entered.complete(Unit)
                    release.await()
                }
            },
            beforeSummaryCommit = { commitReachedRepository.complete(Unit); Unit },
        )
        fixture.messages.setVectorizationStatus(SESSION, listOf(HISTORY_IDS.first()), "success")
        pauseWrites = true
        val vectorization = async {
            fixture.messages.setVectorizationStatus(SESSION, listOf(HISTORY_IDS.first()), "success")
        }
        entered.await()
        val source = ContextCompactionSource(fixture.current().summary, fixture.history())
        val commit = async { fixture.sessions.commitContextCompaction(SESSION, "并发摘要", HISTORY_IDS, source) }

        runCurrent()
        val commitWaited = !commitReachedRepository.isCompleted
        release.complete(Unit)
        vectorization.await()
        commit.await()

        assertThat(commitWaited).isTrue()
        assertThat(commitReachedRepository.isCompleted).isTrue()
        val persisted = checkNotNull(fixture.repository.getById(SESSION))
        assertThat(persisted.summary).isEqualTo("并发摘要")
        assertThat(persisted.messages.filter { it.id in HISTORY_IDS }.all { it.isArchived }).isTrue()
        assertThat(fixture.current().messages.filter { it.id in HISTORY_IDS }.all { it.isArchived }).isTrue()
        assertThat(fixture.database.messageDao().getById(HISTORY_IDS.first())?.vectorizationStatus).isEqualTo("success")
    }

    private fun input(fixture: Fixture) = ContextCompactionInput(
        session = fixture.current(), currentTurnId = CURRENT, keepRecentTurns = 1,
        threshold = 0.8, window = PromptWindow(128_000, 4_096), manual = true,
    )

    private suspend fun assertHistoryUnarchived(fixture: Fixture) {
        val visible = fixture.current()
        val persisted = checkNotNull(fixture.repository.getById(SESSION))
        HISTORY_IDS.forEach { id ->
            val row = checkNotNull(fixture.database.messageDao().getById(id))
            assertThat(row.vectorizationStatus).isEqualTo("success")
            assertThat(row.isArchived).isEqualTo(0)
            assertThat(visible.messages.single { it.id == id }.vectorizationStatus).isEqualTo("success")
            assertThat(visible.messages.single { it.id == id }.isArchived).isFalse()
            assertThat(persisted.messages.single { it.id == id }.isArchived).isFalse()
        }
    }

    private fun assertOriginalHistorySent(fixture: Fixture, session: Session) {
        assertThat(fixture.prompt(session).messages.filter { it.role != "system" }.map { it.content })
            .containsExactly("历史用户原文", "历史助手原文", "当前问题").inOrder()
    }

    private suspend fun TestScope.fixture(
        beforeStatusWrite: suspend () -> Unit = {},
        beforeSummaryCommit: suspend () -> Unit = {},
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        databases += database
        return Fixture(database, context, this, beforeStatusWrite, beforeSummaryCommit).also { fixture ->
            fixture.sessions.addSession(Session(SESSION, "agent", modelId = "active-model"))
            fixture.messages.addMessage(SESSION, Message(HISTORY_IDS[0], MessageRole.USER, "历史用户原文", createdAt = 1))
            fixture.messages.addMessage(SESSION, Message(HISTORY_IDS[1], MessageRole.ASSISTANT, "历史助手原文", createdAt = 2))
            fixture.messages.addMessage(SESSION, Message(CURRENT, MessageRole.USER, "当前问题", createdAt = 3))
        }
    }

    private class Fixture(
        val database: InfinityDatabase,
        context: Context,
        scope: CoroutineScope,
        beforeStatusWrite: suspend () -> Unit,
        beforeSummaryCommit: suspend () -> Unit,
    ) {
        val store = ChatStore()
        val repository = object : SessionRepository(database.sessionDao(), database.messageDao()) {
            override suspend fun commitContextCompaction(
                sessionId: String,
                summary: String,
                messageIds: List<String>,
                source: ContextCompactionSource,
            ) {
                beforeSummaryCommit()
                super.commitContextCompaction(sessionId, summary, messageIds, source)
            }
        }
        private val messageRepository = object : MessageRepository(database.messageDao()) {
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                if ("vectorizationStatus" in updates) beforeStatusWrite()
                super.updatePartial(messageId, updates)
            }

            override suspend fun updateArchiveVectorizationStatus(messageId: String, status: String) {
                beforeStatusWrite()
                super.updateArchiveVectorizationStatus(messageId, status)
            }

            override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {
                beforeStatusWrite()
                super.updateVectorizationStatus(messageId, status, isArchived)
            }
        }
        val messages = MessageManager(store, messageRepository, repository, scope)
        val sessions = SessionManager(store, repository)
        private val embeddings = mockk<EmbeddingClient>().apply {
            coEvery { embedDocuments(any()) } answers {
                EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f, 0f) })
            }
        }
        private val postProcessor = PostProcessor(
            store, sessions, messages, embeddings,
            VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao()),
            RecursiveCharacterTextSplitter(),
        )
        private val contentStrategy = DefaultChatGenerationContentStrategy(
            context.getSharedPreferences("context-memory-${UUID.randomUUID()}", Context.MODE_PRIVATE), null,
        )

        fun current(): Session = checkNotNull(store.getSession(SESSION))
        fun history(): List<Message> = current().messages.filter { it.id in HISTORY_IDS }
        suspend fun archiveMemory(messages: List<Message>) =
            postProcessor.archiveMessagesToRag(SESSION, messages, "active-model")

        fun prompt(session: Session) = PromptRequest(
            messages = contentStrategy.buildProtocolMessages(session, "系统指令 ${session.summary.orEmpty()}", CURRENT),
            model = "active-model",
        )
    }

    private companion object {
        const val SESSION = "context-memory-session"
        const val CURRENT = "current-user"
        val HISTORY_IDS = listOf("old-user", "old-assistant")
    }
}
