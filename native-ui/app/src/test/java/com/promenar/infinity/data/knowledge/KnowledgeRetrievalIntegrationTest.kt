package com.promenar.infinity.data.knowledge

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.*
import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.rag.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.*
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.data.remote.protocol.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KnowledgeRetrievalIntegrationTest {
    @Test fun `工具与自动入口共用收紧后的范围和会话开关`() = runTest {
        val selected = RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.ALL_AGENTS,
            documentScope = DocumentScope.ALL_INDEXED, enableKnowledgeGraph = true)
        val policy = AgentMemoryPolicy(memoryCap = ConversationScope.SESSION, graphCap = ConversationScope.AGENT,
            documentCap = DocumentScope.KNOWLEDGE_BASE)
        val expected = resolveEffectiveRagOptions(selected, memoryPolicy = policy)
        var received: RagOptions? = null
        val rag = object : RagProvider {
            override suspend fun retrieveContext(query: String, sessionId: String, options: RagOptions,
                onProgress: ((String, Int, String?) -> Unit)?): RagRetrievalResult {
                received = options; return RagRetrievalResult("", emptyList(), null)
            }
        }
        val session = Session("s", "a", ragOptions = selected)
        ContextBuilder(ragProvider = rag).buildContext(ContextBuilderParams("s", "q", assistantMsgId = "m", session = session, agentMemoryPolicy = policy))
        assertThat(received).isEqualTo(expected)
        assertThat(expected.memoryScope).isEqualTo(ConversationScope.SESSION)
        assertThat(expected.graphScope).isEqualTo(ConversationScope.ALL_AGENTS)
        assertThat(expected.documentScope).isEqualTo(DocumentScope.KNOWLEDGE_BASE)
    }

    @Test fun `TOOL跳过全部前置检索且工具关闭回退AUTO和不注入目录`() = runTest {
        val rag = mockk<RagProvider> {
            coEvery { retrieveContext(any(), any(), any(), any()) } returns RagRetrievalResult("自动结果", emptyList(), null)
        }
        val kg = mockk<KgProvider> { coEvery { retrieveContext(any(), any(), any(), any()) } returns null }
        val base = ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a", ragOptions = RagOptions(enableKnowledgeGraph = true)),
            retrievalMode = AgentRetrievalMode.TOOL, knowledgeCatalog = "可检索资料目录")
        val builder = ContextBuilder(ragProvider = rag, kgProvider = kg)
        val tool = builder.buildContext(base)
        coVerify(exactly = 0) { rag.retrieveContext(any(), any(), any(), any()) }
        coVerify(exactly = 0) { kg.retrieveContext(any(), any(), any(), any()) }
        assertThat(tool.turnContext).contains("可检索资料目录")
        assertThat(tool.finalSystemPrompt).doesNotContain("可检索资料目录")
        val off = builder.buildContext(base.copy(session = base.session.copy(options = SessionOptions(toolsEnabled = false))))
        coVerify(exactly = 1) { rag.retrieveContext(any(), any(), any(), any()) }
        coVerify(exactly = 1) { kg.retrieveContext(any(), any(), any(), any()) }
        assertThat(off.finalSystemPrompt).doesNotContain("可检索资料目录")
        assertThat(off.ragContext).isEqualTo("自动结果")
        assertThat(effectiveRetrievalMode(null, true)).isEqualTo(AgentRetrievalMode.AUTO)
        assertThat(effectiveKnowledgeFileAccess(null, true)).isEqualTo(KnowledgeFileAccess.ON_REQUEST)
        assertThat(effectiveKnowledgeFileAccess(AgentRetrievalConfig(knowledgeFileAccess = KnowledgeFileAccess.ALWAYS), false)).isEqualTo(KnowledgeFileAccess.OFF)
    }

    @Test fun `本轮未广告knowledge_search时TOOL与BOTH回到自动检索且不注入目录`() = runTest {
        val rag = mockk<RagProvider> {
            coEvery { retrieveContext(any(), any(), any(), any()) } returns RagRetrievalResult("自动结果", emptyList(), null)
        }
        val builder = ContextBuilder(ragProvider = rag)
        for (mode in listOf(AgentRetrievalMode.TOOL, AgentRetrievalMode.BOTH)) {
            // 模型不支持工具调用（空集合）或 knowledge_search 被全局停用时，检索不得丢失。
            for (advertised in listOf(emptySet(), setOf("web_search"))) {
                val context = builder.buildContext(ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a"),
                    retrievalMode = mode, knowledgeCatalog = "可检索资料目录", availableToolNames = advertised))
                assertThat(context.ragContext).isEqualTo("自动结果")
                assertThat(context.finalSystemPrompt).doesNotContain("可检索资料目录")
            }
        }
        coVerify(exactly = 4) { rag.retrieveContext(any(), any(), any(), any()) }
        val advertised = builder.buildContext(ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a"),
            retrievalMode = AgentRetrievalMode.TOOL, knowledgeCatalog = "可检索资料目录", availableToolNames = setOf("knowledge_search")))
        coVerify(exactly = 4) { rag.retrieveContext(any(), any(), any(), any()) }
        assertThat(advertised.turnContext).contains("可检索资料目录")
        assertThat(advertised.finalSystemPrompt).doesNotContain("可检索资料目录")
    }

    @Test fun `工具注册与执行重验使用相同谓词且关闭工具只保留web_search`() = runTest {
        val names = listOf("knowledge_search", "request_knowledge_access", "web_search")
        val registry = mockk<SkillRegistry> { every { getAllTools(null) } returns names.map { name ->
            ProtocolTool(function = ProtocolToolFunction(name, "说明", "{}"), sourceId = "builtin", runtimeToolId = name, risk = ToolRisk.SAFE_READ)
        } }
        val settings = mockk<SharedPreferences>(relaxed = true) { every { getStringSet(any(), any()) } returns null }
        var config = AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.TOOL)
        val resolver = DefaultSessionToolResolver(settings, registry,
            knowledgeSearchAllowed = { com.promenar.infinity.data.generation.knowledgeSearchAvailableForPrompt(it, config) },
            knowledgeAccessAllowed = { effectiveKnowledgeFileAccess(config, it.options.toolsEnabled) == KnowledgeFileAccess.ON_REQUEST })
        val session = Session("s", "a")
        assertThat(resolver.resolve(session).map { it.function.name }).containsExactlyElementsIn(names)
        assertThat(resolver.resolveForExecution(session).map { it.function.name }).containsExactlyElementsIn(names)
        assertThat(resolver.resolve(session.copy(ragOptions = RagOptions(enableMemory = false, enableDocs = false))).map { it.function.name })
            .doesNotContain("knowledge_search")
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        assertThat(resolver.resolve(session).map { it.function.name }).doesNotContain("request_knowledge_access")
        assertThat(resolver.resolve(session.copy(options = SessionOptions(toolsEnabled = false))).map { it.function.name }).containsExactly("web_search")
        assertThat(renderKnowledgeCatalog(KnowledgeCatalogSnapshot())).isEmpty()
    }

    @Test fun `工具关闭提示明确禁止续调并要求直接作答`() {
        assertThat(disabledToolsMessage(false)).isEqualTo("""[SYSTEM WARNING]: Tool usage is currently DISABLED by the user configuration.
You CANNOT use tools in this turn.
Please STOP trying to use tools and answer the user's request directly using your internal knowledge.""")
        val withSearch = disabledToolsMessage(true)
        assertThat(withSearch).contains("except web_search")
        assertThat(withSearch).contains("STOP trying")
        assertThat(withSearch).contains("answer the user's request directly")
    }
}
