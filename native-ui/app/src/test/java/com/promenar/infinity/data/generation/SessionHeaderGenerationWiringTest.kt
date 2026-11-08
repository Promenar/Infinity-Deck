package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.Companion.MODEL
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.generation.*
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatState
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.*
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionHeaderGenerationWiringTest {
    @Test fun `真实编排两轮工具续请求与自动重试复用会话而独立会话隔离`() = ioTest {
        SessionHeaderWiringEndpoint().use { endpoint ->
            var calls = 0
            endpoint.reply = {
                calls++
                when (calls) {
                    1 -> HttpStatusCode.ServiceUnavailable to "{}"
                    2 -> HttpStatusCode.OK to SessionHeaderWiringEndpoint.toolStream()
                    else -> HttpStatusCode.OK to SessionHeaderWiringEndpoint.textStream("final")
                }
            }
            val fixture = fixture(endpoint, "parent")
            ChatGenerationRunner(fixture.runtime).run(fixture.request) {}
            assertThat(endpoint.requests).hasSize(3)
            assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests[2])).contains("tool-result")
            fixture.store.updateSession("parent") { it.copy(messages = it.messages + listOf(
                Message("next-u", MessageRole.USER, "followup"), Message("next-a", MessageRole.ASSISTANT, ""))) }
            val next = fixture.request.copy(assistantMessageId = "next-a", userMessageId = "next-u", userContent = "followup")
            fixture.runtime.prepare(next)
            fixture.runtime.buildContext(next)
            fixture.runtime.stream(next, 0).toList()
            assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests.last())).contains("followup")
            assertThat(endpoint.requests.take(4).map { it.headers["x-opencode-session"] })
                .containsExactlyElementsIn(List(4) { endpoint.identifier("parent") })
            val branch = com.promenar.infinity.data.session.BranchSessionPlanner.plan(
                com.promenar.infinity.data.local.db.entity.SessionEntity("parent", "agent", modelId = MODEL, createdAt = 1, updatedAt = 1),
                listOf(com.promenar.infinity.data.local.db.entity.MessageEntity("source-user", "parent", "user", "question", createdAt = 1)),
                "source-user", "branch", 2, newMessageId = { "copy-$it" },
            ) as com.promenar.infinity.data.session.BranchSessionPlanResult.Ready
            assertThat(branch.plan.messages.single().sessionId).isEqualTo(branch.plan.session.id)
            listOf("other", branch.plan.session.id).forEach { id ->
                val independent = fixture(endpoint, id)
                independent.runtime.buildContext(independent.request)
                independent.runtime.stream(independent.request, 0).toList()
                assertThat(endpoint.requests.last().headers["x-opencode-session"]).isEqualTo(endpoint.identifier(id))
            }
            assertThat(endpoint.requests.mapNotNull { it.headers["x-opencode-session"] }.toSet()).hasSize(3)
            endpoint.requests.forEach { assertThat(it.headers["User-Agent"]).isEqualTo("Infinity-Deck/${BuildConfig.VERSION_NAME}") }
        }
    }

    @Test fun `默认关闭的真实会话请求与无身份请求头和请求体相同`() = ioTest {
        SessionHeaderWiringEndpoint(false).use { endpoint ->
            val f = fixture(endpoint, "parent")
            f.runtime.buildContext(f.request)
            f.runtime.stream(f.request, 0).toList()
            val first = endpoint.requests.single()
            endpoint.enabled = true
            val baseline = fixture(endpoint, " ")
            baseline.runtime.buildContext(baseline.request)
            baseline.runtime.stream(baseline.request, 0).toList()
            assertThat(first.headers).isEqualTo(endpoint.requests.last().headers)
            assertThat(SessionHeaderWiringEndpoint.body(first)).isEqualTo(SessionHeaderWiringEndpoint.body(endpoint.requests.last()))
            assertThat(first.headers["x-opencode-session"]).isNull()
            assertThat(first.headers["User-Agent"]).isNotEqualTo("Infinity-Deck/${BuildConfig.VERSION_NAME}")
        }
    }

    @Test fun `App标题装配使用真实会话并经摘要路由保留身份`() = ioTest {
        SessionHeaderWiringEndpoint().use { endpoint ->
            val f = fixture(endpoint, "title-session")
            ChatGenerationRunner(f.runtime).run(f.request) {}
            assertThat(endpoint.requests).hasSize(2)
            assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests.last())).contains("简短标题")
            assertThat(endpoint.requests.last().headers["x-opencode-session"]).isEqualTo(endpoint.identifier("title-session"))
            val enabledBodies = endpoint.requests.map { SessionHeaderWiringEndpoint.body(it) }
            endpoint.enabled = false
            endpoint.requests.clear()
            val disabled = fixture(endpoint, "title-session")
            ChatGenerationRunner(disabled.runtime).run(disabled.request) {}
            assertThat(endpoint.requests.map { SessionHeaderWiringEndpoint.body(it) }).isEqualTo(enabledBodies)
            endpoint.requests.forEach {
                assertThat(it.headers["x-opencode-session"]).isNull()
                assertThat(it.headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
            }
        }
    }

    @Test fun `历史摘要经生产摘要路由保留会话身份`() = ioTest {
        SessionHeaderWiringEndpoint().use { endpoint ->
            val routed = RoutedToolResultSummarizer(ChatProviderRouteGate(endpoint.router)) { error("cloud") }
            val manager = routed.historySummaryManager(LlmProvider(endpoint.protocol))
            assertThat(manager.summarizeOrNull(null, listOf(Message("u", MessageRole.USER, "old")), MODEL, sessionId = "summary-session")).isNotNull()
            assertThat(endpoint.requests.single().headers["x-opencode-session"]).isEqualTo(endpoint.identifier("summary-session"))
            endpoint.enabled = false
            manager.summarizeOrNull(null, listOf(Message("u", MessageRole.USER, "old")), MODEL, sessionId = "summary-session")
            assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests.last())).isEqualTo(SessionHeaderWiringEndpoint.body(endpoint.requests.first()))
            assertThat(endpoint.requests.last().headers["x-opencode-session"]).isNull()
            assertThat(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
        }
    }

    @Test fun `自动摘要和手动压缩中的历史与工具折叠摘要都带当前会话`() = ioTest {
        listOf(false, true).forEach { manual ->
            var enabledBodies: List<String>? = null
            listOf(true, false).forEach { enabled ->
                SessionHeaderWiringEndpoint(enabled).use { endpoint ->
                    val history = listOf(
                        Message("old-u", MessageRole.USER, "旧问题"),
                        Message("old-a", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("old-call", "read_file", "{}"))),
                        Message("old-t", MessageRole.TOOL, "工具结果内容".repeat(1800), toolCallId = "old-call", name = "read_file"),
                        Message("old-final", MessageRole.ASSISTANT, "旧结论"),
                    ).let { initial -> initial.dropLast(1) + (1..3).flatMap { i -> listOf(
                        Message("recent-a-$i", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("recent-$i", "read_file", "{}"))),
                        Message("recent-t-$i", MessageRole.TOOL, "短结果", toolCallId = "recent-$i", name = "read_file"),
                    ) } + initial.last() }
                    val f = fixture(endpoint, "compaction-session", history, threshold = 0.0)
                    if (manual) f.runtime.compactNow(f.request.sessionId) {} else f.runtime.buildContext(f.request)
                    assertThat(endpoint.requests).hasSize(2)
                    assertThat(endpoint.requests.map { SessionHeaderWiringEndpoint.body(it) }.any { it.contains("压缩工具") }).isTrue()
                    assertThat(endpoint.requests.map { SessionHeaderWiringEndpoint.body(it) }.any { it.contains("对话记录压缩") }).isTrue()
                    endpoint.requests.forEach {
                        assertThat(it.headers["x-opencode-session"]).isEqualTo(if (enabled) endpoint.identifier("compaction-session") else null)
                        if (!enabled) assertThat(it.headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
                    }
                    val bodies = endpoint.requests.map { SessionHeaderWiringEndpoint.body(it) }
                    if (enabled) enabledBodies = bodies else assertThat(bodies).isEqualTo(enabledBodies)
                }
            }
        }
    }

    private data class Fixture(val runtime: DefaultChatGenerationRuntime, val request: GenerationRequest, val store: ChatStore)

    // Ktor 的内存传输含真实调度器，不能让虚拟时间在其完成前跳到请求超时。
    private fun ioTest(block: suspend TestScope.() -> Unit) = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
    }

    private fun TestScope.fixture(endpoint: SessionHeaderWiringEndpoint, id: String,
        history: List<Message> = emptyList(), threshold: Double = 0.8): Fixture {
        val settings = mockk<SharedPreferences> {
            every { getStringSet(any(), any()) } returns setOf("read_file")
            every { getString(any(), any()) } answers { secondArg() }
            every { getFloat(any(), any()) } answers { secondArg() }
            every { getInt(any(), any()) } answers { secondArg() }
            every { getBoolean(any(), any()) } answers { secondArg() }
        }
        val store = ChatStore().apply { update { ChatState(sessions = listOf(Session(id, "agent", modelId = MODEL,
            title = if (id == "title-session") "" else "existing", executionMode = "auto",
            inferenceParams = InferenceParams(maxTokens = 128, keepRecentTurns = 1, autoSummaryThreshold = threshold),
            options = SessionOptions(toolsEnabled = true, enableTimeInjection = false),
            messages = history + listOf(Message("u", MessageRole.USER, "question"), Message("a", MessageRole.ASSISTANT, ""))))) } }
        val sessions = mockk<ISessionRepository>(relaxed = true)
        val messages = MessageManager(store, mockk<IMessageRepository>(relaxed = true), sessions, this)
        val sessionManager = SessionManager(store, sessions)
        val post = PostProcessor(store, sessionManager, messages).apply {
            setTitleGenerator { params -> PostProcessor.generateTitleWithFallback(params, null) { prompt ->
                RoutedToolResultSummarizer(ChatProviderRouteGate(endpoint.router)) { error("cloud") }.summarizePrompt(prompt)?.content
            } }
        }
        val tool = ProtocolTool(function = ProtocolToolFunction("read_file", "read", "{\"type\":\"object\"}"),
            risk = ToolRisk.SAFE_READ, runtimeToolId = "read_file", sourceId = "builtin")
        val registry = mockk<SkillRegistry> { every { getAllTools(any()) } returns listOf(tool) }
        val executor = mockk<ToolExecutor>(relaxed = true)
        coEvery { executor.executeTools(any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<List<ToolCall>>().forEach { call -> messages.addMessage(firstArg(), Message("tool-${call.id}", MessageRole.TOOL,
                "tool-result", toolCallId = call.id, name = call.name)) }
        }
        val runtime = DefaultChatGenerationRuntime(settings, this, store,
            mockk<IAgentRepository> { coEvery { getById(any()) } returns null }, AgentConfigResolver(settings),
            ChatProviderRouteGate(endpoint.router, UnconfinedTestDispatcher(testScheduler)), ContextBuilder(), messages,
            { error("cloud") }, LlmProvider(endpoint.protocol), mockk(relaxed = true), executor, post, null,
            RoutedToolResultSummarizer(ChatProviderRouteGate(endpoint.router)) { error("cloud") }.historySummaryManager(LlmProvider(endpoint.protocol)),
            sessionManager, DefaultChatGenerationContentStrategy(settings, registry), mockk(relaxed = true))
        return Fixture(runtime, GenerationRequest(id, "a", "u", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED), store)
    }
}
