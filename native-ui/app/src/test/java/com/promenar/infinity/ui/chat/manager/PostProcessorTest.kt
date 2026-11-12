package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.promenar.infinity.data.remote.protocol.PromptRequest

@OptIn(ExperimentalCoroutinesApi::class)
class PostProcessorTest {
    @Test
    fun `标题快速模型与降级模型均传递会话身份`() = runTest {
        val requests = mutableListOf<PromptRequest>()
        PostProcessor.generateTitleWithFallback(
            PostProcessorParams("conversation", "assistant", "user", "问题", "回答", Agent("agent", "代理"), Session("conversation", "agent"), false, modelId = "provider::chat"),
            "provider::quick",
        ) { prompt -> requests += prompt; null }
        assertThat(requests.map { it.sessionId }).containsExactly("conversation", "conversation")
        assertThat(requests.map { it.model }).containsExactly("provider::quick", "provider::chat").inOrder()
    }

    private val testScope = TestScope()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {}
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    @Test
    fun estimateTokens() {
        assertThat(PostProcessor.estimateTokens("")).isEqualTo(0)
        assertThat(PostProcessor.estimateTokens("a")).isEqualTo(1)
        assertThat(PostProcessor.estimateTokens("hello world")).isEqualTo(2)
        assertThat(PostProcessor.estimateTokens("a very long string")).isEqualTo(4)
    }

    @Test
    fun updateStatsSetsBilling() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager)

        val agent = Agent(id = "a1", name = "Test Agent")
        val baseline = PromptUsageBaseline("s1", "model-key", "summary-hash", "tool-hash", 500, emptyList())
        val session = Session(id = "s1", agentId = "a1", title = "New Chat", stats = SessionStats(contextUsageBaseline = baseline))
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "Hello, how are you doing today?",
            assistantContent = "I'm doing great, thanks for asking!",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            accumulatedUsage = TokenUsage(input = 50, output = 30, total = 80),
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.stats).isNotNull()
        assertThat(updatedSession.stats!!.billing.chatInput.count).isEqualTo(50)
        assertThat(updatedSession.stats!!.billing.chatOutput.count).isEqualTo(30)
        assertThat(updatedSession.stats!!.billing.total).isEqualTo(80)
        assertThat(updatedSession.stats!!.contextUsageBaseline).isEqualTo(baseline)
    }

    @Test
    fun updateStatsAutoGeneratesTitle() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager)

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "s1", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "What is the weather?",
            assistantContent = "The weather is sunny.",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.title).isEqualTo("What is the weather")
    }

    @Test
    fun updateStatsWithTitleGeneratorSetsGeneratedTitle() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val postProcessor = PostProcessor(store, sessionManager, messageManager).apply {
            setTitleGenerator { "天气查询" }
        }

        val agent = Agent(id = "a1", name = "Test Agent")
        val session = Session(id = "s1", agentId = "a1", title = "New Chat")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val params = PostProcessorParams(
            sessionId = "s1",
            assistantMsgId = "m2",
            userMsgId = "m1",
            userContent = "今天北京天气怎么样？",
            assistantContent = "今天北京晴转多云。",
            agent = agent,
            session = store.getSession("s1")!!,
            ragEnabled = false,
            modelId = "gpt-4o"
        )

        postProcessor.updateStats(params)
        testScope.advanceUntilIdle()

        val updatedSession = store.getSession("s1")!!
        assertThat(updatedSession.title).isEqualTo("天气查询")
    }

    @Test
    fun cleanTitleSanitizesPunctuationAndLimitsLength() {
        assertThat(PostProcessor.cleanTitle("《超级人工智能大模型深度思考与解析指南》")).isEqualTo("超级人工智能大模型深")
        assertThat(PostProcessor.cleanTitle("“今日北京天气：多云”")).isEqualTo("今日北京天气多云")
        assertThat(PostProcessor.cleanTitle("  ### 标题：量子力学浅析！  ")).isEqualTo("量子力学浅析")
        assertThat(PostProcessor.cleanTitle("")).isNull()
    }

    @Test
    fun `标题按稳定模型 ID 经路由请求，优先快速模型`() = runTest {
        val calls = mutableListOf<String>()

        val title = PostProcessor.generateTitleWithFallback(titleParams(), "quick::model") { request ->
            calls += request.model
            "快速标题"
        }

        assertThat(calls).containsExactly("quick::model")
        assertThat(title).isEqualTo("快速标题")
    }

    @Test
    fun `快速模型无标题时沿用会话模型，都失败时返回空`() = runTest {
        val calls = mutableListOf<String>()

        val title = PostProcessor.generateTitleWithFallback(titleParams(), "quick::model") { request ->
            calls += request.model
            if (request.model == "quick::model") "" else "天气分析"
        }
        val none = PostProcessor.generateTitleWithFallback(titleParams(), "quick::model") { error("offline") }

        assertThat(calls).containsExactly("quick::model", "current::model").inOrder()
        assertThat(title).isEqualTo("天气分析")
        assertThat(none).isNull()
    }

    @Test
    fun `英文标题按单词截断且保留撇号`() {
        assertThat(PostProcessor.cleanTitle("Title: Switching Editors from Vim to VS Code."))
            .isEqualTo("Switching Editors from Vim to VS Code")
        assertThat(PostProcessor.cleanTitle("A Practical Guide to Configuring Neovim Plugins for Rust Development"))
            .isEqualTo("A Practical Guide to Configuring Neovim")
        assertThat(PostProcessor.cleanTitle("\"What's New in Kotlin 2.3\"")).isEqualTo("What's New in Kotlin 2.3")
        assertThat(PostProcessor.fallbackTitle("Remember this: I use Vim for coding.\nThanks"))
            .isEqualTo("Remember this I use Vim for coding")
        assertThat(PostProcessor.fallbackTitle("  ")).isEqualTo("会话")
    }

    @Test
    fun `标题先写回退值，模型标题异步生成后替换，期间用户改名则保留用户标题`() = testScope.runTest {
        val store = ChatStore()
        val sessionManager = SessionManager(store, stubSessionRepo)
        val messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val postProcessor = PostProcessor(store, sessionManager, messageManager, titleScope = this).apply {
            setTitleGenerator { release.await(); "Weather Check" }
        }
        sessionManager.addSession(Session(id = "s1", agentId = "a1", title = "New Chat"))
        sessionManager.addSession(Session(id = "s2", agentId = "a1", title = "New Chat"))
        advanceUntilIdle()
        fun params(id: String) = PostProcessorParams(
            sessionId = id, assistantMsgId = "m2", userMsgId = "m1",
            userContent = "What is the weather?", assistantContent = "Sunny.",
            agent = Agent(id = "a1", name = "Test Agent"), session = store.getSession(id)!!,
            ragEnabled = false, modelId = "p::m",
        )

        postProcessor.updateStats(params("s1"))
        postProcessor.updateStats(params("s2"))
        advanceUntilIdle()
        // 生成期间本轮已结束，不等待标题模型。
        assertThat(store.getSession("s1")!!.title).isEqualTo("What is the weather")
        sessionManager.updateSessionTitle("s2", "我的会话")

        release.complete(Unit)
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.title).isEqualTo("Weather Check")
        assertThat(store.getSession("s2")!!.title).isEqualTo("我的会话")
    }

    private fun titleParams() = PostProcessorParams(
        sessionId = "s1", assistantMsgId = "a1", userMsgId = "u1",
        userContent = "需要一个标题", assistantContent = "完整回答",
        agent = Agent("agent", "智能体"), session = Session("s1", "agent", modelId = "current::model"),
        ragEnabled = false, modelId = "current::model",
    )
}
