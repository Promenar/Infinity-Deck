package com.promenar.infinity.data.generation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.ProviderSummary
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.KeywordSearcher
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.rag.MemoryManagerRagAdapter
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.rag.documentVectorMetadata
import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.STREAM_RETRY_LIMIT_KEY
import com.promenar.infinity.domain.generation.StartGenerationResult
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatState
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 保留应用的 Provider 装配、实际 HTTP 协议、检索和生成工厂，只替换传输与无关的后处理。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GenerationApplicationRemoteTest {
    private lateinit var app: InfinityApplication
    private lateinit var database: InfinityDatabase
    private lateinit var applicationScope: CoroutineScope
    private lateinit var engine: MockEngine
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val requests = CopyOnWriteArrayList<CapturedRequest>()
    private val responseGates = ConcurrentHashMap<String, ResponseGate>()

    private data class CapturedRequest(val path: String, val body: String)
    private class ResponseGate {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("infinity_settings", 0).edit().clear()
            .putInt(STREAM_RETRY_LIMIT_KEY, 0).commit()
        context.getSharedPreferences("infinity_provider", 0).edit().clear().commit()
        val real = InfinityApplication()
        ContextWrapper::class.java.getDeclaredField("mBase").apply {
            isAccessible = true
            set(real, context)
        }
        app = spyk(real)
        every { app.localInferenceEngine } returns mockk(relaxed = true)

        val config = ProviderConfig(
            protocolType = ProtocolType.DeepSeek,
            baseUrl = "https://remote.test/v1",
            apiKey = "fake-test-key",
            model = "deepseek-chat",
        )
        val manager = mockk<ProviderManager>()
        every { manager.providers } returns MutableStateFlow(listOf(ProviderListItem(
            id = "default", name = "测试远程", protocolType = ProtocolType.DeepSeek, hasApiKey = true,
        )))
        every { manager.providerModels } returns MutableStateFlow(listOf(
            model("deepseek-chat"), model("embed"),
        ))
        every { manager.embeddingModelId } returns MutableStateFlow("default::embed")
        every { manager.getProviderConfig("default") } returns config
        every { manager.getMainProviderConfig() } returns config
        every { manager.getMainConfiguredModelId() } returns "default::deepseek-chat"
        every { manager.isPersistedProtocolUnsupported(any()) } returns false
        every { manager.getProviderSummary("default") } returns ProviderSummary(
            "default", "测试远程", ProtocolType.DeepSeek, config.baseUrl, config.model, true, false,
        )
        mockkObject(ProviderManager.Companion)
        every { ProviderManager.getInstance() } returns manager

        engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            requests += CapturedRequest(request.url.encodedPath, body)
            if (request.url.encodedPath.endsWith("/embeddings")) {
                respond("""{"data":[{"embedding":[1.0,0.0]}],"usage":{"total_tokens":2}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val json = Json.parseToJsonElement(body).jsonObject
                val user = json.getValue("messages").jsonArray.last {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.jsonObject.getValue("content").jsonPrimitive.content
                // 出站纯文本可能带冻结上下文；以完整问题及协议分隔符定位各会话的闸门。
                responseGates.entries.singleOrNull { (question, _) ->
                    user == question || user.endsWith("\n\n$question")
                }?.value?.let { gate ->
                    gate.arrived.complete(Unit)
                    try { gate.release.await() }
                    finally { if (!currentCoroutineContext().isActive) gate.cancelled.complete(Unit) }
                }
                if (json["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    respond(SSE_RESPONSE, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                } else {
                    respond("""{"choices":[{"message":{"role":"assistant","content":"默认提供商成功"},"finish_reason":"stop"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        mockkObject(ModelHttp)
        every { ModelHttp.client(any()) } answers {
            // 与应用保持多个 HttpClient 共用引擎的结构，并实际应用调用方的超时及插件配置。
            HttpClient(engine, firstArg<HttpClientConfig<*>.() -> Unit>()).also(clients::add)
        }
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        clients.forEach(HttpClient::close)
        engine.close()
        database.close()
        unmockkObject(ModelHttp, ProviderManager.Companion)
        Dispatchers.resetMain()
    }

    @Test
    fun `真实远程工厂经执行闸门完成资料检索和流式回复`() = runBlocking {
        val fixture = fixture("single")
        val started = fixture.start("single")
        val terminal = withTimeout(10_000) {
            fixture.coordinator.awaitTaskCompletion("single", started.taskId)
        }
        assertThat(terminal?.phase).isEqualTo(GenerationPhase.COMPLETED)
        val answer = database.messageDao().getById("assistant-single")!!.let {
            MessageRepository(database.messageDao()).getById(it.id)!!
        }
        assertThat(answer.content).isEqualTo("生成成功")
        assertThat(answer.ragMetadata?.trace?.outcome).isEqualTo(RagRetrievalTrace.OUTCOME_HIT)
        assertThat(answer.ragReferences?.single()?.content).isEqualTo("固定检索正文")
        val embedding = requests.filter { it.path.endsWith("/embeddings") }
        val generation = requests.filter { it.path.endsWith("/chat/completions") }
        assertThat(embedding).hasSize(1)
        assertThat(generation).hasSize(1)
        assertThat(Json.parseToJsonElement(embedding.single().body).jsonObject["model"]?.jsonPrimitive?.content)
            .isEqualTo("embed")
        assertThat(Json.parseToJsonElement(generation.single().body).jsonObject["model"]?.jsonPrimitive?.content)
            .isEqualTo("deepseek-chat")
        assertThat(generation.single().body).contains("固定检索正文")
    }

    @Test
    fun `默认Provider保留应用实际装配并能发送远程同步请求`() = runBlocking {
        val response = app.llmProvider.sendPromptSync(PromptRequest(
            model = "deepseek-chat", messages = listOf(ProtocolMessage("user", "默认装配请求")), stream = false,
        ))
        assertThat(response.content).isEqualTo("默认提供商成功")
        assertThat(requests.single().path).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun `请求发出前取消不会误取消其它会话或占用共享Provider`() = runBlocking {
        val preparing = CompletableDeferred<Unit>()
        val fixture = fixture("before-http", "unrelated") { id ->
            if (id == "before-http") {
                preparing.complete(Unit)
                awaitCancellation()
            }
        }
        val cancelled = fixture.start("before-http")
        withTimeout(10_000) { preparing.await() }
        assertThat(requests).isEmpty()
        assertThat(fixture.coordinator.cancel(cancelled.taskId)).isTrue()
        val terminal = withTimeout(10_000) {
            fixture.coordinator.awaitTaskCompletion("before-http", cancelled.taskId)
        }
        val other = fixture.start("unrelated")
        val completed = withTimeout(10_000) {
            fixture.coordinator.awaitTaskCompletion("unrelated", other.taskId)
        }
        assertThat(terminal?.phase).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(completed?.phase).isEqualTo(GenerationPhase.COMPLETED)
        assertThat(requests.filter { it.path.endsWith("/chat/completions") }).hasSize(1)
        assertThat(requests.any { it.body.contains("问题-before-http") }).isFalse()
    }

    @Test
    fun `取消一个远程请求只终止自己的HTTP且另一会话成功`() = runBlocking {
        val blocked = ResponseGate().also { responseGates["问题-cancel"] = it }
        val otherBlocked = ResponseGate().also { responseGates["问题-other"] = it }
        val fixture = fixture("cancel", "other")
        val cancelled = fixture.start("cancel")
        withTimeout(10_000) { blocked.arrived.await() }
        val other = fixture.start("other")
        withTimeout(10_000) { otherBlocked.arrived.await() }
        assertThat(fixture.coordinator.cancel(cancelled.taskId)).isTrue()
        val cancelledTerminal = withTimeout(10_000) {
            fixture.coordinator.awaitTaskCompletion("cancel", cancelled.taskId)
        }
        withTimeout(10_000) { blocked.cancelled.await() }
        assertThat(cancelledTerminal?.phase).isEqualTo(GenerationPhase.CANCELLED)
        try {
            assertThat(otherBlocked.cancelled.isCompleted).isFalse()
            assertThat(fixture.coordinator.isSessionBusy("other")).isTrue()
        } finally { otherBlocked.release.complete(Unit) }
        val otherTerminal = withTimeout(10_000) {
            fixture.coordinator.awaitTaskCompletion("other", other.taskId)
        }
        assertThat(otherTerminal?.phase).isEqualTo(GenerationPhase.COMPLETED)
        assertThat(MessageRepository(database.messageDao()).getById("assistant-other")?.content)
            .isEqualTo("生成成功")
        assertThat(requests.count { it.path.endsWith("/chat/completions") }).isEqualTo(2)
    }

    private class Fixture(val store: ChatStore, val coordinator: DefaultGenerationCoordinator) {
        suspend fun start(sessionId: String): StartGenerationResult.Started = store.turnAdmission.withLock(sessionId) {
            coordinator.start(GenerationRequest(
                sessionId, "assistant-$sessionId", "user-$sessionId", "问题-$sessionId", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            )) as StartGenerationResult.Started
        }
    }

    private suspend fun fixture(vararg sessionIds: String, beforeContext: suspend (String) -> Unit = {}): Fixture {
        val sessions = sessionIds.map { id -> Session(
            id = id, agentId = "agent", modelId = "default::deepseek-chat", executionMode = "workspace",
            options = SessionOptions(toolsEnabled = false, enableTimeInjection = false),
            ragOptions = RagOptions(
                enableMemory = false, enableDocs = true, documentScope = DocumentScope.ALL_INDEXED,
                enableRerank = false, enableKnowledgeGraph = false, enableQueryRewrite = false,
                enableHybridSearch = false,
            ),
            messages = listOf(Message("user-$id", MessageRole.USER, "问题-$id"),
                Message("assistant-$id", MessageRole.ASSISTANT, "", parentMessageId = "user-$id")),
        ) }
        sessions.forEach { session ->
            database.sessionDao().insert(session.toEntity())
            session.messages.forEach { database.messageDao().insert(it.toEntity(session.id)) }
        }
        val store = ChatStore().apply { update { ChatState(sessions = sessions) } }
        val sessionRepository = SessionRepository(database.sessionDao(), database.messageDao())
        val messages = MessageManager(store, MessageRepository(database.messageDao()), sessionRepository, applicationScope)
        val vectors = VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao())
        vectors.addVectorRecords(listOf(VectorStore.NewVectorRecord(
            docId = "document", content = "固定检索正文", embedding = floatArrayOf(1f, 0f),
            metadata = documentVectorMetadata("document", 0, "测试资料"),
        )))
        val memory = MemoryManager(
            vectors, KeywordSearcher(database.vectorDao()), GraphStore(database), app.embeddingClient,
            ragConfig = RagConfiguration(enableQueryRewrite = false, enableHybridSearch = false, enableRerank = false),
        )
        val settings = app.getSharedPreferences("infinity_settings", 0)
        val presentation = GenerationPresentationStore()
        val routeGate = ChatProviderRouteGate(app.providerRequestRouter)
        val provider = app.llmProvider
        val compaction = ContextCompactionService(
            isGenerating = { false },
            manualCompaction = { _, _ -> error("测试不发起手动压缩") },
        )
        val factory = DefaultChatGenerationRunnerFactory(
            settings = settings, applicationScope = applicationScope, store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings), routeGate = routeGate,
            contextBuilder = ContextBuilder(ragProvider = MemoryManagerRagAdapter(memory)),
            messageManager = messages, localProviderFactory = { error("远程路由不能调用本地 Provider") },
            provider = provider, toolLedger = mockk(relaxed = true), toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true), memoryManager = memory,
            summaryManager = RoutedToolResultSummarizer(routeGate) { error("远程摘要不能调用本地 Provider") }
                .historySummaryManager(provider),
            sessionManager = SessionManager(store, sessionRepository), skillRegistry = null,
            toolResolver = SessionToolResolver { emptyList() }, presentationStore = presentation,
            compactionService = compaction,
            runModeCheck = { id -> beforeContext(id); null },
        )
        return Fixture(store, DefaultGenerationCoordinator(
            applicationScope, factory, presentation, executionGate = SessionExecutionGate(), compactionService = compaction,
        ))
    }

    private fun model(remoteId: String) = ModelInfo(
        name = remoteId, id = "default::$remoteId", description = "", enabled = true,
        providerId = "default", remoteModelId = remoteId,
    )

    private companion object {
        val SSE_RESPONSE = """
            data: {"choices":[{"index":0,"delta":{"content":"生成成功"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
    }
}
