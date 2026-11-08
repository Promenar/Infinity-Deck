package com.promenar.infinity.data.rag

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.toEntity

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.session.SessionExecutionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** 状态抽取通过真实请求解析与 Room，验证轮次、助手范围及删除边界。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionStateMemoryIntegrationTest {
    @Test
    fun `只有引用的用户消息排入状态抽取后按相同文本校验并发送模型`() = runTest {
        seedTurn()
        database.messageDao().update(Message("u1", MessageRole.USER, "", quotes = listOf(MessageQuote("source", "引文", "批注")), createdAt = 100).toEntity("s1"))
        val queue = idleQueue()
        assertThat(RoomSessionStateScheduler({ database }, { true }, { queue }).schedule("s1", "a1", "u1")).isTrue()
        val row = database.vectorizationTaskDao().getById("session-state-a1")!!
        assertThat(row.userContent).isEqualTo("> 引文\n\n批注")
        val requests = mutableListOf<PromptRequest>()
        val protocol = mockk<LlmProtocol> { coEvery { sendPromptSync(any()) } answers {
            requests += firstArg<PromptRequest>()
            PromptResponse(content = """{"event":null,"states":[]}""")
        } }
        processor(protocol).processPersisted(task().copy(userContent = "> 引文\n\n批注", createdAt = row.createdAt))
        assertThat(requests.single().messages.last().content).contains("> 引文\n\n批注")
    }

    @Test
    fun `生产状态记忆抽取带当前会话标识且关闭设置时保留原请求体`() = runTest {
        seedTurn()
        com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint().use { endpoint ->
            endpoint.reply = { io.ktor.http.HttpStatusCode.OK to com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.sync("{\"event\":null,\"states\":[]}") }
            val client = KgRequestClient(endpoint.router, { endpoint.protocol })
            val processor = RoomSessionStateProcessor({ database }, { client }, { error("没有记忆条目不应嵌入") },
                { KgExtractionSettings(com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.MODEL, "") })
            processor.processPersisted(task())
            assertThat(endpoint.requests.single().headers["x-opencode-session"]).isEqualTo(endpoint.identifier("s1"))
            endpoint.enabled = false
            processor.processPersisted(task())
            assertThat(endpoint.requests.last().headers["x-opencode-session"]).isNull()
            assertThat(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
            assertThat(com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.body(endpoint.requests.last()))
                .isEqualTo(com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.body(endpoint.requests.first()))
        }
    }

    private lateinit var database: InfinityDatabase
    private val queues = mutableListOf<VectorizationQueue>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        queues.forEach { it.shutdown() }
        database.close()
    }

    @Test
    fun `处理器批量嵌入事件与状态并按用户消息时间写入精确契约`() = runTest {
        seedTurn()
        val protocol = protocol("""{"event":"确定了界面主题","states":[{"subject":"Infinity Deck","attribute":"theme","value":"dark"},{"subject":"Infinity Deck","attribute":"plan","value":null}]}""")
        val embedded = mutableListOf<List<String>>()
        val embedding = embeddingClient(embedded)

        processor(protocol, embedding).processPersisted(task())

        val rows = database.vectorDao().getBySessionId("s1")
        assertThat(rows).hasSize(3)
        val event = rows.single { it.metadata == """{"type":"summary","kind":"event"}""" }
        assertThat(event.content).isEqualTo("确定了界面主题")
        val states = rows.filter { it.metadata.orEmpty().startsWith("{\"type\":\"state\",\"key\":") }
        assertThat(states.map { it.metadata }).containsExactly(
            """{"type":"state","key":"infinity deck\u001ftheme","subject":"Infinity Deck","attribute":"theme","value":"dark"}""",
            """{"type":"state","key":"infinity deck\u001fplan","subject":"Infinity Deck","attribute":"plan","value":null}""",
        )
        assertThat(states.map { it.content }).containsExactly("Infinity Deck · theme: dark", "Infinity Deck · plan: (no longer applies)")
        rows.forEach { row ->
            assertThat(row.sessionId).isEqualTo("s1")
            assertThat(row.docId).isNull()
            assertThat(row.startMessageId).isEqualTo("u1")
            assertThat(row.endMessageId).isEqualTo("a1")
            assertThat(row.createdAt).isEqualTo(100L)
            assertThat(ByteBuffer.wrap(row.embedding).asFloatBuffer().let { listOf(it.get(), it.get()) })
                .containsExactly(1f, 0f).inOrder()
        }
        assertThat(embedded).hasSize(1)
        assertThat(embedded.single()).containsExactly("确定了界面主题", "Infinity Deck · theme: dark", "Infinity Deck · plan: (no longer applies)").inOrder()
        coVerify(exactly = 1) { protocol.sendPromptSync(match { it.maxTokens == 1024 && !it.stream }) }
    }

    @Test
    fun `同轮抽取替换事件与状态且保留原始记忆及其它轮次`() = runTest {
        seedTurn()
        seedTurn("s2", "u2", "a2")
        database.vectorDao().insert(memory())
        database.vectorDao().insert(stateRow("other", sessionId = "s2", userId = "u2", assistantId = "a2"))
        var output = """{"event":"选择了主题","states":[{"subject":"Infinity Deck","attribute":"theme","value":"light"}]}"""
        val protocol = mockk<LlmProtocol> { coEvery { sendPromptSync(any()) } answers { PromptResponse(content = output) } }
        val processor = processor(protocol)
        processor.processPersisted(task())
        output = """{"event":null,"states":[{"subject":"Infinity Deck","attribute":"theme","value":"dark"}]}"""

        processor.processPersisted(task())

        val rows = database.vectorDao().getBySessionId("s1")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.content }).containsExactly("needle memory", "Infinity Deck · theme: dark")
        assertThat(database.vectorDao().getById("other")).isNotNull()
    }

    @Test
    fun `全空抽取清除旧事件与状态而不调用嵌入`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(memory(), eventRow(), stateRow("state")))
        val embedding = mockk<EmbeddingClient>()

        processor(protocol("""{"event":" ","states":[]}"""), embedding).processPersisted(task())

        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("memory")
        coVerify(exactly = 0) { embedding.embedDocuments(any()) }
    }

    @Test
    fun `请求只携带同助手的最新有效状态且限制已知状态数量`() = runTest {
        seedTurn()
        seedTurn("s2", "u2", "a2")
        seedTurn("s3", "u3", "a3", agentId = "other-agent")
        val dao = database.vectorDao()
        dao.insert(stateRow("old", value = "old-value", sessionId = "s2", userId = "u2", assistantId = "a2", occurredAt = 1))
        dao.insert(stateRow("latest", value = "current-value", sessionId = "s2", userId = "u2", assistantId = "a2", occurredAt = 2))
        dao.insert(stateRow("private", value = "other-agent-value", sessionId = "s3", userId = "u3", assistantId = "a3", occurredAt = 3))
        dao.insert(stateRow("revoked-old", attribute = "revoked", value = "revoked-value", occurredAt = 3))
        dao.insert(stateRow("revoked", attribute = "revoked", value = null, occurredAt = 4))
        val requests = mutableListOf<PromptRequest>()
        val protocol = mockk<LlmProtocol> {
            coEvery { sendPromptSync(any()) } answers {
                requests += firstArg<PromptRequest>()
                PromptResponse(content = """{"event":null,"states":[]}""")
            }
        }

        processor(protocol).processPersisted(task())

        val input = requests.single().messages.last().content.orEmpty()
        assertThat(input).contains("Infinity Deck · theme: current-value")
        assertThat(input).doesNotContain("old-value")
        assertThat(input).doesNotContain("other-agent-value")
        assertThat(input).doesNotContain("revoked-value")

        for (index in 1..41) {
            dao.insert(stateRow("limit-$index", attribute = "item-$index", value = "known-item-$index", occurredAt = 100L + index))
        }
        requests.clear()
        processor(protocol).processPersisted(task())
        val cappedInput = requests.single().messages.last().content.orEmpty()
        assertThat(Regex("known-item-1(?!\\d)").containsMatchIn(cappedInput)).isFalse()
        assertThat(cappedInput).contains("known-item-41")
        assertThat(Regex("known-item-\\d+").findAll(cappedInput).count()).isEqualTo(40)
    }

    @Test
    fun `较旧任务晚完成时最新状态仍由消息发生时间决定`() = runTest {
        seedTurn()
        seedTurn("s2", "u2", "a2", occurredAt = 300)
        val newer = processor(protocol("""{"event":null,"states":[{"subject":"Infinity Deck","attribute":"theme","value":"new"}]}"""))
        val older = processor(protocol("""{"event":null,"states":[{"subject":"Infinity Deck","attribute":"theme","value":"old"}]}"""))

        newer.processPersisted(task("s2", "u2", "a2"))
        older.processPersisted(task())

        val latest = latestStates(database.vectorDao().getByType("state").mapNotNull(::parseStateRow))
        assertThat(latest.map { it.value }).containsExactly("new")
        assertThat(latest.single().occurredAt).isEqualTo(300L)
    }

    @Test
    fun `模型错误坏JSON及嵌入错误都不覆盖已有轮次状态`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(eventRow(), stateRow("state")))
        val unavailable = mockk<LlmProtocol> { coEvery { sendPromptSync(any()) } throws IllegalStateException("模型不可用") }
        val failedEmbedding = mockk<EmbeddingClient> { coEvery { embedDocuments(any()) } throws IllegalStateException("嵌入不可用") }
        val processors = listOf(
            processor(unavailable),
            processor(protocol("这不是 JSON")),
            processor(protocol("""{"event":"替换事件","states":[]}"""), failedEmbedding),
        )

        processors.forEach { processor ->
            assertThat(runCatching { processor.processPersisted(task()) }.exceptionOrNull()).isNotNull()
            assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("event", "state")
        }
    }

    @Test
    fun `抽取期间用户编辑或消息删除阻止旧正文回写`() = runTest {
        seedTurn()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val protocol = mockk<LlmProtocol> {
            coEvery { sendPromptSync(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                PromptResponse(content = """{"event":"过期事件","states":[]}""")
            }
        }
        val runningProcessor = processor(protocol)
        val processing = async { runningProcessor.processPersisted(task()) }
        entered.await()
        database.openHelper.writableDatabase.execSQL("UPDATE messages SET content = '用户已编辑正文' WHERE id = 'u1'")
        release.complete(Unit)
        processing.await()
        assertThat(database.vectorDao().getBySessionId("s1")).isEmpty()

        database.messageDao().deleteById("a1")
        processor(protocol("""{"event":"孤儿事件","states":[]}""")).processPersisted(task())
        assertThat(rawVectorIds()).isEmpty()
    }

    @Test
    fun `手动移除正在抽取的持久任务后旧结果不得重建记忆`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(eventRow(), stateRow("state")))
        database.vectorizationTaskDao().insert(taskEntity("a1", "extracting"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val protocol = mockk<LlmProtocol> {
            coEvery { sendPromptSync(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                PromptResponse(content = """{"event":"被移除的旧结果","states":[]}""")
            }
        }
        val runningProcessor = processor(protocol)
        val processing = async { runningProcessor.processPersisted(task()) }
        entered.await()

        lifecycle().removeTurn("s1", "a1")
        release.complete(Unit)
        processing.await()

        assertThat(rawVectorIds()).isEmpty()
        assertThat(database.vectorizationTaskDao().getById("session-state-a1")).isNull()
        assertThat(database.messageDao().getById("a1")).isNotNull()
    }

    @Test
    fun `执行前已删除或替换的任务快照不得调用模型或重建记忆`() = runTest {
        seedTurn()
        val oldTask = task().copy(createdAt = 10)
        database.vectorizationTaskDao().insert(taskEntity("a1", "pending").copy(createdAt = 10))
        lifecycle().removeTurn("s1", "a1")
        val protocol = protocol("""{"event":"已失效的结果","states":[]}""")
        val processor = processor(protocol)

        processor.process(oldTask)
        database.vectorizationTaskDao().insert(taskEntity("a1", "pending").copy(createdAt = 20))
        processor.process(oldTask)

        assertThat(rawVectorIds()).isEmpty()
        coVerify(exactly = 0) { protocol.sendPromptSync(any()) }
        assertThat(database.vectorizationTaskDao().getById("session-state-a1")?.createdAt).isEqualTo(20L)
    }

    @Test
    fun `抽取期间仅消息向量化状态更新不应丢弃未变更正文的结果`() = runTest {
        seedTurn()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val protocol = mockk<LlmProtocol> {
            coEvery { sendPromptSync(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                PromptResponse(content = """{"event":"有效事件","states":[]}""")
            }
        }
        val runningProcessor = processor(protocol)
        val processing = async { runningProcessor.processPersisted(task()) }
        entered.await()
        database.openHelper.writableDatabase.execSQL("UPDATE messages SET vectorization_status = 'completed' WHERE id = 'a1'")

        release.complete(Unit)
        processing.await()

        assertThat(database.vectorDao().getBySessionId("s1").map { it.content }).containsExactly("有效事件")
        assertThat(database.messageDao().getById("a1")?.vectorizationStatus).isEqualTo("completed")
    }

    @Test
    fun `事件状态跟随轮次删除撤销恢复且片段计数仅包含memory`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(memory(), eventRow(), stateRow("state")))
        val lifecycle = lifecycle()
        val dao = database.vectorDao()
        assertThat(dao.getMemoryByAssistantMessage("s1", "a1").map { it.id }).containsExactly("memory")
        assertThat(dao.getMemoryByUserMessage("s1", "u1").map { it.id }).containsExactly("memory")
        assertThat(lifecycle.turnChunkCount("s1", "a1")).isEqualTo(1)

        database.messageDao().deleteById("a1")
        lifecycle.onMessagesRemoved("s1")
        assertThat(dao.getBySessionId("s1")).isEmpty()
        assertThat(dao.getByType("summary")).isEmpty()
        assertThat(dao.getByType("state")).isEmpty()
        assertThat(dao.searchFtsExcludeDocs("needle")).isEmpty()

        database.messageDao().insert(assistant())
        lifecycle.onMessagesRestored("s1")
        assertThat(dao.getBySessionId("s1").map { it.id }).containsExactly("memory", "event", "state")
        assertThat(dao.getById("state")?.stale).isFalse()
    }

    @Test
    fun `事件与状态的孤儿行过撤销窗口后物理删除`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(eventRow(), stateRow("state")))
        var now = 1_000L
        val lifecycle = lifecycle(clock = { now })
        database.messageDao().deleteById("u1")
        lifecycle.onMessagesRemoved("s1")
        assertThat(rawVectorIds()).containsExactly("event", "state")

        now = 11_001
        lifecycle.purgeExpired()

        assertThat(rawVectorIds()).isEmpty()
    }

    @Test
    fun `编辑用户消息删除该轮事件状态且不重新调度`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(eventRow(), stateRow("state")))
        val scheduled = mutableListOf<String>()

        lifecycle(stateScheduler = { _, assistant, _ -> scheduled += assistant; true }).onMessageEdited("s1", "u1")

        assertThat(database.vectorDao().getBySessionId("s1")).isEmpty()
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `编辑只有状态的助手消息也清除旧行并按新正文重新调度`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(eventRow(), stateRow("state")))
        database.openHelper.writableDatabase.execSQL("UPDATE messages SET content = '助手已经提供了新的方案内容' WHERE id = 'a1'")
        val scheduled = mutableListOf<Triple<String, String, String?>>()

        lifecycle(stateScheduler = { session, assistant, user -> scheduled += Triple(session, assistant, user); true })
            .onMessageEdited("s1", "a1")

        assertThat(database.vectorDao().getBySessionId("s1")).isEmpty()
        assertThat(scheduled).hasSize(1)
        assertThat(scheduled.single().first).isEqualTo("s1")
        assertThat(scheduled.single().second).isEqualTo("a1")
    }

    @Test
    fun `手动移除删除三类行且重写重新排入状态抽取`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(memory(), eventRow(), stateRow("state")))
        val scheduled = mutableListOf<String>()
        val lifecycle = lifecycle(stateScheduler = { _, assistant, _ -> scheduled += assistant; true })

        assertThat(lifecycle.removeTurn("s1", "a1")).isEqualTo(1)
        assertThat(database.vectorDao().getBySessionId("s1")).isEmpty()
        assertThat(database.messageDao().getById("a1")).isNotNull()

        assertThat(lifecycle.rewriteTurn("s1", "a1")).isEqualTo(1)
        assertThat(scheduled).containsExactly("a1")
        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("rewritten")
    }

    @Test
    fun `状态调度异常不影响编辑记忆的重写结果`() = runTest {
        seedTurn()
        database.vectorDao().insertAll(listOf(memory(), stateRow("state")))

        val result = lifecycle(stateScheduler = { _, _, _ -> error("队列不可用") }).onMessageEdited("s1", "a1")

        assertThat(result["a1"]?.chunkCount).isEqualTo(1)
        assertThat(database.vectorDao().getBySessionId("s1").map { it.id }).containsExactly("rewritten")
    }

    @Test
    fun `关闭过短及不存在的消息不排队而有效轮次保留会话身份`() = runTest {
        seedTurn()
        val queue = idleQueue()
        assertThat(RoomSessionStateScheduler({ database }, { false }, { error("不得访问队列") }).schedule("s1", "a1", "u1")).isFalse()
        database.messageDao().insert(MessageEntity("short-user", "s1", "user", "嗯", createdAt = 300))
        database.messageDao().insert(MessageEntity("short-assistant", "s1", "assistant", "好", createdAt = 301, parentMessageId = "short-user"))
        val scheduler = RoomSessionStateScheduler({ database }, { true }, { queue })
        assertThat(scheduler.schedule("s1", "short-assistant", null)).isFalse()
        assertThat(scheduler.schedule("s1", "missing", null)).isFalse()
        assertThat(scheduler.schedule("wrong-session", "a1", "u1")).isFalse()

        assertThat(scheduler.schedule("s1", "a1", null)).isTrue()

        val row = database.vectorizationTaskDao().getById("session-state-a1")!!
        assertThat(row.type).isEqualTo("session_state")
        assertThat(row.sessionId).isEqualTo("s1")
        assertThat(row.userMessageId).isEqualTo("u1")
        assertThat(row.assistantMessageId).isEqualTo("a1")
        assertThat(row.userContent).isEqualTo(USER)
        assertThat(row.aiContent).isEqualTo(ASSISTANT)
        assertThat(queue.state.value.isDocumentIndexing).isFalse()
    }

    @Test
    fun `旧正文排队请求不得替换已经排入的新正文任务`() = runTest {
        seedTurn()
        val updatedAnswer = "助手已经确认改用浅色主题，并保留当前计划。"
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET content = ? WHERE id = 'a1'", arrayOf(updatedAnswer),
        )
        val queue = idleQueue()
        assertThat(queue.enqueueSessionState("s1", "u1", "a1", USER, updatedAnswer)).isTrue()
        val current = database.vectorizationTaskDao().getById("session-state-a1")!!

        assertThat(queue.enqueueSessionState("s1", "u1", "a1", USER, ASSISTANT)).isFalse()

        val retained = database.vectorizationTaskDao().getById("session-state-a1")!!
        assertThat(retained.aiContent).isEqualTo(updatedAnswer)
        assertThat(retained.createdAt).isEqualTo(current.createdAt)
        assertThat(queue.snapshotState().queue.map { it.aiContent }).containsExactly(updatedAnswer)
    }

    @Test
    fun `会话删除准入与队列屏障都拒绝状态任务持久化`() = runTest {
        seedTurn()
        val gate = SessionExecutionGate()
        val queue = idleQueue(gate = gate)
        val scheduler = RoomSessionStateScheduler({ database }, { true }, { queue })
        gate.withDeletion("s1", null) {
            assertThat(runCatching { scheduler.schedule("s1", "a1", "u1") }.exceptionOrNull()).isNotNull()
            assertThat(database.vectorizationTaskDao().getById("session-state-a1")).isNull()
        }
        val barrier = queue.acquireSessionDeleteBarrier("s1", "workspace", emptyList())
        try {
            barrier.awaitReady()
            assertThat(runCatching { scheduler.schedule("s1", "a1", "u1") }.exceptionOrNull()).isNotNull()
            assertThat(database.vectorizationTaskDao().getById("session-state-a1")).isNull()
        } finally {
            barrier.abort()
        }
        assertThat(scheduler.schedule("s1", "a1", "u1")).isTrue()
    }

    @Test
    fun `重启恢复状态任务并清理失败与孤儿任务`() = runTest {
        seedTurn()
        val dao = database.vectorizationTaskDao()
        dao.insert(taskEntity("a1", "interrupted"))
        dao.insert(taskEntity("missing", "interrupted"))
        dao.insert(taskEntity("failed", "failed"))
        val queue = idleQueue()

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(queue.snapshotState().queue.map { it.id }).containsExactly("session-state-a1")
        assertThat(dao.getById("session-state-a1")?.sessionId).isEqualTo("s1")
        assertThat(dao.getById("session-state-missing")).isNull()
        assertThat(dao.getById("session-state-failed")).isNull()
    }

    @Test
    fun `重启立即恢复三十秒内仍标记抽取中的状态任务`() = runTest {
        seedTurn()
        database.vectorizationTaskDao().insert(
            taskEntity("a1", "extracting").copy(updatedAt = System.currentTimeMillis()),
        )
        val queue = idleQueue()

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        val restored = queue.snapshotState().queue.single()
        assertThat(restored.id).isEqualTo("session-state-a1")
        assertThat(restored.sessionId).isEqualTo("s1")
        assertThat(restored.aiContent).isEqualTo(ASSISTANT)
    }

    @Test
    fun `后台执行成功及失败的状态任务均被清理而不占文档队列`() = runBlocking {
        seedTurn()
        database.messageDao().insert(assistant().copy(id = "a2"))
        val processed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true), embeddingClient = mockk(relaxed = true), graphExtractor = null,
            vectorDao = database.vectorDao(), vectorizationTaskDao = database.vectorizationTaskDao(),
            dispatcher = Dispatchers.Default,
            sessionState = { task ->
                processed += task.assistantMessageId!!
                if (task.assistantMessageId == "a2") error("状态抽取失败")
            },
        ).also { queues += it }.apply { beforeFailureDisplayDelayForTest = {} }
        queue.enqueueSessionState("s1", "u1", "a1", USER, ASSISTANT)
        queue.enqueueSessionState("s1", "u1", "a2", USER, ASSISTANT)

        withTimeout(10_000) {
            while (processed.size < 2 || database.vectorizationTaskDao().getById("session-state-a1") != null ||
                database.vectorizationTaskDao().getById("session-state-a2") != null ||
                queue.snapshotState().queue.isNotEmpty() || queue.snapshotState().isProcessing
            ) delay(20)
        }

        assertThat(processed.toList()).containsExactly("a1", "a2").inOrder()
        assertThat(queue.snapshotState().queue).isEmpty()
        assertThat(queue.state.value.isDocumentIndexing).isFalse()
        // 本用例的处理协程跑在 Dispatchers.Default 上：关库前等它收尾，避免完成后的清理查询撞上已关闭的库并泄漏到下一个用例。
        queue.shutdownForReplacement()
    }

    private fun TestScope.processor(
        protocol: LlmProtocol,
        embedding: EmbeddingClient = embeddingClient(),
    ) = RoomSessionStateProcessor(
        databaseProvider = { database },
        requestClient = { KgRequestClient(testKgRouter(), { protocol }, Dispatchers.IO) },
        embeddingClient = { embedding },
        settings = { KgExtractionSettings("owner::summary", "") },
    )

    private suspend fun RoomSessionStateProcessor.processPersisted(task: VectorizationTask) {
        database.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = task.id, type = task.type, status = task.status, sessionId = task.sessionId,
            userContent = task.userContent, aiContent = task.aiContent, userMessageId = task.userMessageId,
            assistantMessageId = task.assistantMessageId, createdAt = task.createdAt, updatedAt = task.updatedAt,
        ))
        process(task)
    }

    private fun protocol(output: String) = mockk<LlmProtocol> {
        coEvery { sendPromptSync(any()) } returns PromptResponse(content = output)
    }

    private fun embeddingClient(batches: MutableList<List<String>> = mutableListOf()) = mockk<EmbeddingClient> {
        coEvery { embedDocuments(any()) } answers {
            val texts = firstArg<List<String>>()
            batches += texts
            EmbeddingResult(texts.map { floatArrayOf(1f, 0f) })
        }
    }

    private fun lifecycle(
        stateScheduler: SessionStateScheduler? = null,
        clock: () -> Long = { 1_000L },
    ) = RoomConversationMemoryLifecycle(
        databaseProvider = { database },
        turnWriter = { _, _, _, _, _ -> database.vectorDao().insert(memory("rewritten")); 1 },
        clock = clock, undoWindowMillis = 10_000, sessionState = stateScheduler,
    )

    private fun idleQueue(gate: SessionExecutionGate? = null) = VectorizationQueue(
        vectorStore = mockk(relaxed = true), embeddingClient = mockk(relaxed = true), graphExtractor = null,
        vectorDao = database.vectorDao(), vectorizationTaskDao = database.vectorizationTaskDao(),
        dispatcher = StandardTestDispatcher(TestCoroutineScheduler()), executionGate = gate, sessionState = {},
    ).also { queues += it }

    private suspend fun seedTurn(
        sessionId: String = "s1", userId: String = "u1", assistantId: String = "a1",
        agentId: String = "agent", occurredAt: Long = 100,
    ) {
        database.sessionDao().insert(SessionEntity(sessionId, agentId, createdAt = 1, updatedAt = 1))
        database.messageDao().insert(MessageEntity(userId, sessionId, "user", USER, createdAt = occurredAt))
        database.messageDao().insert(assistant().copy(id = assistantId, sessionId = sessionId, parentMessageId = userId, createdAt = occurredAt + 100))
    }

    private fun assistant() = MessageEntity("a1", "s1", "assistant", ASSISTANT, createdAt = 200, parentMessageId = "u1")

    private fun task(sessionId: String = "s1", userId: String = "u1", assistantId: String = "a1") = VectorizationTask(
        id = "session-state-$assistantId", type = "session_state", sessionId = sessionId,
        userContent = USER, aiContent = ASSISTANT, userMessageId = userId, assistantMessageId = assistantId, status = "pending",
    )

    private fun taskEntity(assistantId: String, status: String) = VectorizationTaskEntity(
        id = "session-state-$assistantId", type = "session_state", status = status, sessionId = "s1",
        userMessageId = "u1", assistantMessageId = assistantId, userContent = USER, aiContent = ASSISTANT,
        createdAt = 1, updatedAt = 1,
    )

    private fun memory(id: String = "memory") = row(id, """{"type":"memory","chunkIndex":0}""", "needle memory")
    private fun eventRow() = row("event", """{"type":"summary","kind":"event"}""", "needle event")
    private fun stateRow(
        id: String, attribute: String = "theme", value: String? = "needle state", sessionId: String = "s1",
        userId: String = "u1", assistantId: String = "a1", occurredAt: Long = 100,
    ) = row(id, buildJsonObject {
        put("type", "state"); put("key", "infinity deck\u001f$attribute"); put("subject", "Infinity Deck"); put("attribute", attribute)
        if (value == null) put("value", JsonNull) else put("value", value)
    }.toString(), "Infinity Deck · $attribute: ${value ?: "(no longer applies)"}")
        .copy(sessionId = sessionId, startMessageId = userId, endMessageId = assistantId, createdAt = occurredAt)

    private fun row(id: String, metadata: String, content: String) = VectorEntity(
        id = id, sessionId = "s1", content = content, metadata = metadata,
        embedding = ByteBuffer.allocate(8).also { it.asFloatBuffer().put(floatArrayOf(1f, 0f)) }.array(),
        startMessageId = "u1", endMessageId = "a1", createdAt = 100,
    )

    private fun rawVectorIds(): List<String> = database.query("SELECT id FROM vectors", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private companion object {
        const val USER = "请把 Infinity Deck 的界面主题设置为深色，并撤销之前的计划。"
        const val ASSISTANT = "好的，界面使用深色主题；之前的计划已经撤销。"
    }
}
