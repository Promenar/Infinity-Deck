package com.promenar.infinity.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.memory.LongTermMemoryEntity
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.data.repository.IdentityTestDirectory
import com.promenar.infinity.data.repository.PersistentFileIdentity
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.domain.library.GraphEstimate
import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.GraphStopReason
import com.promenar.infinity.domain.library.LibraryActionResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryKnowledgeSourceTest {
    private lateinit var db: InfinityDatabase
    private lateinit var source: DefaultLibraryKnowledgeSource
    private var now = 1_000_000_000L
    private var retryFailure: Exception? = null
    private var estimate: GraphEstimate? = GraphEstimate(3, 3, 12_288, false, "示例模型")
    private val graphRequests = mutableListOf<Boolean>()
    private val sessionGate = SessionExecutionGate()
    private val queue = MutableStateFlow(VectorizationQueue.QueueState(emptyList(), null, false))
    private val root = file("root", "/", directory = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        val preferences = context.getSharedPreferences("library-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        source = DefaultLibraryKnowledgeSource(
            database = db,
            ensureRoot = { root },
            queueState = queue,
            readText = { _, _, _ -> LibraryExtractedText("abcdef", false) },
            retryIndex = { _, _ -> retryFailure?.let { throw it }; true },
            rebuildIndex = { _, _ -> },
            preferences = preferences,
            libraryLabel = { "资料库" },
            failedReason = { "索引失败" },
            actionFailedReason = { "操作失败" },
            confirmationReason = { "需要确认用量" },
            memoryBusyReason = { "片段正在处理，请稍后重试" },
            sessionGate = sessionGate,
            now = { now },
            estimateGraph = { estimate },
            enqueueGraph = { _, _, confirmed -> graphRequests += confirmed; true },
        )
    }

    @After
    fun tearDown() {
        com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting()
        db.close()
    }

    @Test
    fun `概览持续观察已索引资料的变化`() = runBlocking {
        val values = Channel<Int>(Channel.UNLIMITED)
        val job = launch { source.observeOverview().collect { values.send(it.indexedDocuments) } }
        assertThat(withTimeout(5_000) { values.receive() }).isEqualTo(0)
        db.fileEntryDao().insert(file("indexed", "/indexed.txt").copy(vectorizedAt = 10))
        assertThat(withTimeout(5_000) { var n = values.receive(); while (n != 1) n = values.receive(); n }).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `待处理按失败运行排队过期排列并给出相对路径和排队位置`() = runBlocking {
        listOf("failed", "running", "queued", "stale").forEach {
            db.fileEntryDao().insert(file(it, "/项目/$it.txt").copy(vectorizedAt = if (it == "stale") 10 else null, updatedAt = 20))
        }
        val running = task("running", "vectorizing", 1)
        val waiting = task("queued", "pending", 2)
        val failed = task("failed", "failed", 3)
        queue.value = VectorizationQueue.QueueState(listOf(running, waiting, failed), running, true)
        val pending = source.observePending().first { it.size == 4 }
        assertThat(pending.map { it.kind }).containsExactly(PendingKind.FAILED, PendingKind.INDEXING, PendingKind.QUEUED, PendingKind.STALE).inOrder()
        assertThat(pending[0].reason).isEqualTo("索引失败")
        assertThat(pending[2].queuePosition).isEqualTo(1)
        assertThat(pending[0].folderPath).isEqualTo("资料库 › 项目")
        assertThat(pending[0].ref).isEqualTo(FileRef("kb", "/项目/failed.txt"))
        assertThat(source.documentIdFor(FileRef("kb", "/项目/failed.txt"))).isEqualTo("failed")
        assertThat(source.documentIdFor(FileRef("other", "/项目/failed.txt"))).isNull()
        assertThat(source.documentIdFor(FileRef("kb", "/项目/../failed.txt"))).isNull()
    }

    @Test
    fun `待处理映射当前阶段批次进度和最近错误`() {
        val entry = file("doc", "/doc.txt")
        val task = task("doc", "vectorizing", 1).copy(totalChunks = 35, lastChunkIndex = 16, error = "HTTP 429：限流")
        fun pending(current: VectorizationTask) = pendingDocuments("root", listOf(entry), emptyList(),
            VectorizationQueue.QueueState(listOf(current), current, true), "资料库", "索引失败", "需要确认").single()

        val vectorizing = pending(task)
        assertThat(vectorizing.stage).isEqualTo(PendingStage.VECTORIZING)
        assertThat(vectorizing.processed).isEqualTo(16)
        assertThat(vectorizing.total).isEqualTo(35)
        assertThat(vectorizing.lastError).isEqualTo("HTTP 429：限流")
        assertThat(pending(task.copy(status = "extracting_source")).stage).isEqualTo(PendingStage.PREPARING)
        assertThat(pending(task.copy(status = "extracting")).stage).isEqualTo(PendingStage.EXTRACTING_GRAPH)
        val saving = pending(task.copy(status = "saving"))
        assertThat(saving.stage).isEqualTo(PendingStage.SAVING)
        assertThat(saving.processed).isNull()
        assertThat(saving.total).isNull()
        assertThat(pending(task.copy(status = "extracting_source")).total).isNull()
        val failed = pending(task.copy(status = "failed"))
        assertThat(failed.stage).isNull()
        assertThat(failed.processed).isEqualTo(16)
        assertThat(failed.total).isEqualTo(35)
        assertThat(failed.lastError).isEqualTo("HTTP 429：限流")
    }

    @Test
    fun `需要图谱预算确认的任务给出明确原因`() = runBlocking {
        db.fileEntryDao().insert(file("doc", "/doc.txt").copy(vectorizedAt = 20))
        val task = task("doc", "partial", 1).copy(error = VectorizationQueue.KG_CONFIRMATION_REQUIRED)
        queue.value = VectorizationQueue.QueueState(listOf(task), task, false)
        assertThat(source.observePending().first().single().reason).isEqualTo("需要确认用量")
        assertThat(source.observePending().first().single().kind).isEqualTo(PendingKind.GRAPH_ATTENTION)
        assertThat(source.observePending().first().single().graphConfirmation).isTrue()
        assertThat(source.retry("doc")).isEqualTo(com.promenar.infinity.domain.library.LibraryActionResult.Failed("需要确认用量"))
    }

    @Test
    fun `估算和单独图谱抽取遵循确认结果`() = runBlocking {
        db.fileEntryDao().insert(file("doc", "/doc.txt").copy(vectorizedAt = 20))
        val expected = GraphEstimate(17, 17, 69_632, true, "示例模型")
        estimate = expected
        assertThat(source.graphEstimate("doc")).isEqualTo(expected)
        assertThat(source.extractGraph("doc", false)).isEqualTo(LibraryActionResult.Failed("需要确认用量"))
        assertThat(graphRequests).isEmpty()
        assertThat(source.extractGraph("doc", true)).isEqualTo(LibraryActionResult.Done)
        estimate = expected.copy(requiresConfirmation = false)
        assertThat(source.extractGraph("doc", false)).isEqualTo(LibraryActionResult.Done)
        assertThat(graphRequests).containsExactly(true, false).inOrder()
        estimate = null
        assertThat(source.graphEstimate("doc")).isNull()
        assertThat(source.graphEstimate("missing")).isNull()
    }

    @Test
    fun `已索引资料的图谱状态和待处理原因独立于向量索引`() = runBlocking {
        db.fileEntryDao().insert(file("doc", "/doc.txt").copy(vectorizedAt = 20))
        assertThat(source.observeOverview().first().indexedDocuments).isEqualTo(1)
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.NotExtracted)
        fun setTask(status: String, error: String?) {
            val item = task("doc", status, 1).copy(error = error, totalChunks = 4, lastChunkIndex = 2)
            queue.value = VectorizationQueue.QueueState(listOf(item), item, status == "extracting")
        }
        setTask("extracting", null)
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.Extracting(2, 4))
        setTask("partial", "kg.budget.calls")
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.Partial(2, 4, GraphStopReason.CALLS))
        assertThat(source.observePending().first().single().kind).isEqualTo(PendingKind.GRAPH_ATTENTION)
        assertThat(source.observePending().first().single().graphConfirmation).isFalse()
        setTask("partial", "kg.budget.output_tokens")
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.Partial(2, 4, GraphStopReason.OUTPUT_TOKENS))
        setTask("partial", "kg.budget.duration")
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.Partial(2, 4, GraphStopReason.DURATION))
        setTask("partial", VectorizationQueue.KG_FAILED + ".timeout")
        assertThat(source.insight("doc")!!.graph).isInstanceOf(DocumentGraphState.Failed::class.java)
        setTask("partial", VectorizationQueue.KG_CONFIRMATION_REQUIRED)
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.ConfirmationRequired(estimate!!))
        assertThat(source.observeOverview().first().indexedDocuments).isEqualTo(1)
        queue.value = VectorizationQueue.QueueState(emptyList(), null, false)
        db.fileEntryDao().update(db.fileEntryDao().getActiveByUuid("root", "doc")!!.copy(kgExtractedAt = 20))
        assertThat(source.insight("doc")!!.graph).isEqualTo(DocumentGraphState.Complete(0, 0))
    }

    @Test
    fun `已删除会话仍保留空标题的历史片段`() = runBlocking {
        // 历史遗留的孤儿片段：外键开关与插入必须在同一连接上执行，Room 的 DAO 可能走连接池里的另一条连接。
        val connection = db.openHelper.writableDatabase
        connection.execSQL("PRAGMA foreign_keys = OFF")
        try {
            connection.execSQL(
                "INSERT INTO vectors (id, session_id, content, embedding, metadata, created_at, stale, version) " +
                    "VALUES ('orphan', 'gone', 'orphan', x'', '{\"type\":\"memory\"}', 1, 0, 1)",
            )
        } finally {
            connection.execSQL("PRAGMA foreign_keys = ON")
        }
        val group = source.observeFragmentGroups().first().single()
        assertThat(group.sessionId).isEqualTo("gone")
        assertThat(group.sessionTitle).isNull()
        assertThat(group.agentName).isNull()
    }

    @Test
    fun `分组统计覆盖超过一百条的跨会话片段`() = runBlocking {
        listOf("s1", "s2").forEach { id ->
            db.sessionDao().insert(SessionEntity(id = id, agentId = "agent", title = id, createdAt = 1, updatedAt = 1))
        }
        repeat(101) { i -> db.vectorDao().insert(vector("m-$i", "s1", "memory", i.toLong() + 2)) }
        db.vectorDao().insert(vector("old", "s2", "memory", 1))
        val groups = source.observeFragmentGroups().first()
        assertThat(groups.map { it.sessionId }).containsExactly("s1", "s2").inOrder()
        assertThat(groups.map { it.fragmentCount }).containsExactly(101, 1).inOrder()
        val fragments = source.observeFragments("s1").first()
        assertThat(fragments).hasSize(100)
        assertThat(fragments.first().createdAt).isEqualTo(3)
        assertThat(source.observeFragments("s2").first().single().id).isEqualTo("old")
        source.clearFragments("s1")
        assertThat(source.observeFragments("s1").first()).isEmpty()
    }

    @Test
    fun `片段分组从会话和智能体读取名称`() = runBlocking {
        db.agentDao().insert(AgentEntity(id = "agent", name = "助手", createdAt = 1))
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        db.vectorDao().insert(vector("m2", "s1", "memory", 2))
        val group = source.observeFragmentGroups().first().single()
        assertThat(group.sessionTitle).isEqualTo("会话")
        assertThat(group.agentName).isEqualTo("助手")
        assertThat(group.fragmentCount).isEqualTo(2)
        assertThat(group.lastAt).isEqualTo(2)
    }

    @Test
    fun `重试取消向调用方传播而非返回失败`() = runBlocking {
        db.fileEntryDao().insert(file("doc", "/doc.txt"))
        retryFailure = CancellationException("fixture")
        var cancelled = false
        try {
            source.retry("doc")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertThat(cancelled).isTrue()
    }

    @Test
    fun `删除片段只影响记忆类型而不影响文档向量`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        db.vectorDao().insert(vector("m2", "s1", "memory", 2))
        db.vectorDao().insert(vector("d1", "s1", "document", 3))
        assertThat(source.observeFragments("s1").first().map { it.id }).containsExactly("m1", "m2").inOrder()
        source.deleteFragment("d1")
        source.deleteFragment("m1")
        source.clearFragments("s1")
        assertThat(db.vectorDao().getById("d1")).isNotNull()
        assertThat(db.vectorDao().getById("m1")).isNull()
        assertThat(db.vectorDao().getById("m2")).isNull()
    }

    @Test
    fun `记忆任务执行期间不允许删除片段且任务结束后可删除`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val task = VectorizationTaskEntity(id = "pending", type = "memory", status = "processing", sessionId = "s1", createdAt = 1, updatedAt = 1)
        db.vectorizationTaskDao().insert(task)
        var busy = false
        try { source.clearFragments("s1") } catch (_: LibraryFragmentsBusyException) { busy = true }
        assertThat(busy).isTrue()
        assertThat(db.vectorDao().getById("m1")).isNotNull()
        db.vectorizationTaskDao().update(task.copy(status = "completed"))
        source.clearFragments("s1")
        assertThat(db.vectorDao().getById("m1")).isNull()
    }

    @Test
    fun `记忆任务入队与清空交错时先完成登记再拒绝清空`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val enqueue = launch {
            com.promenar.infinity.data.rag.ConversationMemoryWriteCoordinator.serialized("s1") {
                started.complete(Unit)
                finish.await()
                db.vectorizationTaskDao().insert(VectorizationTaskEntity(id = "task", type = "memory", status = "pending", sessionId = "s1", createdAt = 1, updatedAt = 1))
            }
        }
        started.await()
        val clear = async { runCatching { source.clearFragments("s1") } }
        finish.complete(Unit)
        enqueue.join()
        assertThat(clear.await().exceptionOrNull()).isInstanceOf(LibraryFragmentsBusyException::class.java)
        assertThat(db.vectorDao().getById("m1")).isNotNull()
    }

    @Test
    fun `直接记忆写入未结束时拒绝清空，写入结束后可重试`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writer = launch {
            com.promenar.infinity.data.rag.ConversationMemoryWriteCoordinator.withDirectWriter("s1") {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        var busy = false
        try { source.clearFragments("s1") } catch (_: LibraryFragmentsBusyException) { busy = true }
        assertThat(busy).isTrue()
        assertThat(db.vectorDao().getById("m1")).isNotNull()
        finish.complete(Unit)
        writer.join()
        source.clearFragments("s1")
        assertThat(db.vectorDao().getById("m1")).isNull()
    }

    @Test
    fun `直接写入被取消后释放登记并允许清空`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val started = CompletableDeferred<Unit>()
        val writer = launch {
            com.promenar.infinity.data.rag.ConversationMemoryWriteCoordinator.withDirectWriter("s1") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        writer.cancelAndJoin()
        source.clearFragments("s1")
        assertThat(db.vectorDao().getById("m1")).isNull()
    }

    @Test
    fun `真实记忆入队执行期间不能清空片段`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val client = io.mockk.mockk<com.promenar.infinity.data.rag.EmbeddingClient>()
        io.mockk.coEvery { client.embedDocuments(any()) } coAnswers {
            started.complete(Unit)
            finish.await()
            com.promenar.infinity.data.rag.EmbeddingResult(listOf(floatArrayOf(1f)))
        }
        val actualQueue = VectorizationQueue(
            vectorStore = io.mockk.mockk(relaxed = true), embeddingClient = client, graphExtractor = null,
            vectorDao = db.vectorDao(), vectorizationTaskDao = db.vectorizationTaskDao(), executionGate = sessionGate,
        )
        try {
            actualQueue.enqueueMemory("s1", "提问", "回答", "u", "a")
            withTimeout(5_000) { started.await() }
            var busy = false
            try { source.clearFragments("s1") } catch (_: LibraryFragmentsBusyException) { busy = true }
            assertThat(busy).isTrue()
            assertThat(db.vectorDao().getById("m1")).isNotNull()
            finish.complete(Unit)
            withTimeout(5_000) {
                while (db.vectorizationTaskDao().getBySessionId("s1").any { it.type == "memory" && it.status != "completed" }) delay(10)
            }
            source.clearFragments("s1")
            assertThat(db.vectorDao().getById("m1")).isNull()
        } finally {
            finish.complete(Unit)
            actualQueue.shutdownForReplacement()
        }
    }

    @Test
    fun `归档向量生成未完成时不允许清空片段`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val client = io.mockk.mockk<com.promenar.infinity.data.rag.EmbeddingClient>()
        io.mockk.coEvery { client.embedDocuments(any()) } coAnswers {
            started.complete(Unit)
            finish.await()
            com.promenar.infinity.data.rag.EmbeddingResult(listOf(floatArrayOf(1f)))
        }
        val processor = com.promenar.infinity.ui.chat.manager.PostProcessor(
            store = io.mockk.mockk(relaxed = true), sessionManager = io.mockk.mockk(relaxed = true),
            messageManager = io.mockk.mockk(relaxed = true), embeddingClient = client,
            vectorStore = io.mockk.mockk(relaxed = true), textSplitter = com.promenar.infinity.data.rag.RecursiveCharacterTextSplitter(),
        )
        val writer = launch {
            processor.archiveMessagesToRag("s1", listOf(com.promenar.infinity.data.model.Message(
                id = "message", role = com.promenar.infinity.data.model.MessageRole.USER, content = "示例",
            )), "fixture-model")
        }
        started.await()
        var busy = false
        try { source.clearFragments("s1") } catch (_: LibraryFragmentsBusyException) { busy = true }
        assertThat(busy).isTrue()
        finish.complete(Unit)
        writer.join()
        source.clearFragments("s1")
        assertThat(db.vectorDao().getById("m1")).isNull()
    }

    @Test
    fun `启动隔离期间不能删除会话片段`() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", title = "会话", createdAt = 1, updatedAt = 1))
        db.vectorDao().insert(vector("m1", "s1", "memory", 1))
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.recover(com.promenar.infinity.startup.StartupStep.VECTOR_QUEUE,
            com.promenar.infinity.startup.StartupIssueScope.subsystem(com.promenar.infinity.startup.StartupSubsystem.VECTOR_QUEUE)) { error("fixture") }
        var rejected = false
        try { source.clearFragments("s1") } catch (_: com.promenar.infinity.startup.StartupIsolationException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(db.vectorDao().getById("m1")).isNotNull()
    }

    @Test
    fun `首次查看时间七天窗口与标记已读`() = runBlocking {
        db.longTermMemoryDao().upsert(memory("existing", now - 1))
        assertThat(source.observeNewMemoryIds().first()).isEmpty()
        now += 8L * DAY
        db.longTermMemoryDao().upsert(memory("old", now - 8L * DAY + 2))
        db.longTermMemoryDao().upsert(memory("new", now - 1))
        assertThat(source.observeNewMemoryIds().first()).containsExactly("new")
        val changes = Channel<Set<String>>(Channel.UNLIMITED)
        val job = launch { source.observeNewMemoryIds().collect { changes.send(it) } }
        assertThat(withTimeout(5_000) { changes.receive() }).containsExactly("new")
        source.markMemoriesSeen()
        assertThat(withTimeout(5_000) { changes.receive() }).isEmpty()
        job.cancel()
    }

    @Test
    fun `新记忆插入后无需等待定时刷新`() = runBlocking {
        val changes = Channel<Set<String>>(Channel.UNLIMITED)
        val job = launch { source.observeNewMemoryIds().collect { changes.send(it) } }
        assertThat(withTimeout(5_000) { changes.receive() }).isEmpty()
        now += 1
        db.longTermMemoryDao().upsert(memory("fresh", now))
        assertThat(withTimeout(5_000) { changes.receive() }).containsExactly("fresh")
        job.cancel()
    }

    @Test
    fun `文档实体按索引分块中的出现次数排序`() = runBlocking {
        db.fileEntryDao().insert(file("doc", "/doc.txt").copy(vectorizedAt = 2, kgExtractedAt = 2))
        db.vectorDao().insert(vector("chunk", null, "document", 1).copy(
            docId = "doc", fileUuid = "doc", content = "甲 乙 乙 乙",
        ))
        listOf("甲", "乙").forEach { name ->
            db.kgNodeDao().insert(KgNodeEntity(id = name, name = name, createdAt = 1))
            db.kgEvidenceDao().insert(KgEvidenceEntity(
                id = "e-$name", nodeId = name, sourceType = "document", docId = "doc",
                contentHash = "fixture-hash", taskId = "task", createdAt = 1, workspaceDocument = true,
            ))
        }
        com.promenar.infinity.data.rag.GraphStore(db).createEdge("甲", "乙", "认识", "doc")
        val insight = source.insight("doc")!!
        assertThat(insight.entities).containsExactly("乙", "甲").inOrder()
        assertThat(insight.chunkCount).isEqualTo(1)
        assertThat(insight.graph).isEqualTo(DocumentGraphState.Complete(2, 1))
    }

    @Test
    fun `受控文件句柄能够读取大文本前缀并拒绝符号链接`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("library-reader-fixture").toFile().canonicalFile.toPath()
        val outside = java.nio.file.Files.createTempFile("library-outside-fixture", ".txt")
        try {
            val nonce = java.util.UUID.randomUUID().toString()
            val key = java.nio.file.Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
            java.nio.file.Files.writeString(directory.resolve(".infinity_root_identity"), PersistentFileIdentity.marker(nonce, key))
            val ops = SecureWorkspaceFileOps(directoryOpener = { IdentityTestDirectory(it) }, rootNonceReader = { _, _ -> null })
            val identity = ops.ensureRoot(directory, allowUnboundParent = true)
            val boundRoot = root.copy(hash = identity, physicalRootPath = directory.toString())
            java.nio.file.Files.writeString(directory.resolve("large.txt"), "甲".repeat(400_000))
            db.fileEntryDao().insert(file("large", "/large.txt").copy(physicalRootPath = directory.toString()))
            val reader = LibraryTextReader(db.fileEntryDao(), { boundRoot }, ops)
            val preview = reader.read("root", "large", 5)
            assertThat(preview.text).isEqualTo("甲甲甲甲甲")
            assertThat(preview.truncated).isTrue()
            java.nio.file.Files.createSymbolicLink(directory.resolve("link.txt"), outside)
            db.fileEntryDao().insert(file("link", "/link.txt").copy(physicalRootPath = directory.toString()))
            var rejected = false
            try { reader.read("root", "link", 5) } catch (_: SecurityException) { rejected = true }
            assertThat(rejected).isTrue()
        } finally {
            directory.toFile().deleteRecursively()
            java.nio.file.Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `文本预览保留换行并安全截断代理项`() {
        val read = readLibraryTextPrefix(java.io.StringReader("\uFEFF甲😀乙\n末"), 2)
        assertThat(read.text).isEqualTo("甲")
        assertThat(read.truncated).isTrue()
        val whole = readLibraryTextPrefix(java.io.StringReader("甲\n乙"), 20)
        assertThat(whole.text).isEqualTo("甲\n乙")
        assertThat(whole.truncated).isFalse()
    }

    @Test
    fun `正文直接读取文本并按元数据顺序拼接提取分块`() = runBlocking {
        db.fileEntryDao().insert(file("text", "/文本.md"))
        db.fileEntryDao().insert(file("pdf", "/文档.pdf").copy(mimeType = "application/pdf"))
        db.vectorDao().insert(vector("p2", null, "document", 2).copy(docId = "pdf", fileUuid = "pdf", content = "第二段", metadata = "{\"type\":\"document\",\"chunkIndex\":1}"))
        db.vectorDao().insert(vector("p1", null, "document", 1).copy(docId = "pdf", fileUuid = "pdf", content = "第一段", metadata = "{\"type\":\"document\",\"chunkIndex\":0}"))
        val text = source.body("text", 3)!!
        assertThat(text.text).isEqualTo("abc")
        assertThat(text.markdown).isTrue()
        assertThat(text.extracted).isFalse()
        assertThat(text.truncated).isTrue()
        val pdf = source.body("pdf", 100)!!
        assertThat(pdf.text).isEqualTo("第一段\n\n第二段")
        assertThat(pdf.extracted).isTrue()
        assertThat(pdf.truncated).isFalse()
    }

    @Test
    fun `已索引文档的源内容截断也反映在正文状态`() = runBlocking {
        db.fileEntryDao().insert(file("pdf", "/doc.pdf").copy(mimeType = "application/pdf"))
        db.vectorDao().insert(vector("chunk", null, "document", 1).copy(docId = "pdf", fileUuid = "pdf", content = "前缀"))
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = "task", type = "document_reference", status = "completed", workspaceRootUuid = "root", docId = "pdf",
            targetContentHash = "fixture-hash", targetEpoch = 1, contentTruncated = true, createdAt = 1, updatedAt = 1,
        ))
        assertThat(source.body("pdf", 100)!!.truncated).isTrue()
    }

    private fun file(id: String, path: String, directory: Boolean = false) = FileEntry(
        uuid = id, workspaceRootUuid = "root", parentUuid = if (id == "root") null else "root",
        name = path.substringAfterLast('/'), hash = "fixture-hash", mimeType = "text/plain",
        isDirectory = directory, physicalRootPath = "/fixture", materializedPath = path,
        createdAt = 1, updatedAt = 1,
    )
    private fun task(id: String, status: String, time: Long) = VectorizationTask(
        id = "task-$id", type = "document_reference", docId = id, workspaceRootUuid = "root",
        status = status, createdAt = time, updatedAt = time,
    )
    private fun vector(id: String, session: String?, type: String, time: Long) = VectorEntity(
        id = id, sessionId = session, content = id, embedding = byteArrayOf(),
        metadata = "{\"type\":\"$type\"}", createdAt = time,
    )
    private fun memory(id: String, time: Long) = LongTermMemoryEntity(
        id, "fact", id, id, null, null, null, time, time,
    )
    companion object { private const val DAY = 86_400_000L }
}
