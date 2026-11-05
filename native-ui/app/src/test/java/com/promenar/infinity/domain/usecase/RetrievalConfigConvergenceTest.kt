package com.promenar.infinity.domain.usecase

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRagConfig
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.rag.*
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.chat.manager.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetrievalConfigConvergenceTest {
    @Test fun `全局与智能体查询改写默认关闭`() {
        assertThat(RagConfiguration().enableQueryRewrite).isFalse()
        assertThat(AgentRetrievalConfig().enableQueryRewrite).isFalse()
    }

    @Test fun `智能体旧自定义和会话调优值不影响全局参数且记忆上限不改保存值`() = runTest {
        val policy = AgentMemoryPolicy(memoryCap = ConversationScope.SESSION, graphCap = ConversationScope.AGENT,
            documentCap = DocumentScope.WORKSPACE)
        val agent = Agent("a", "a", useInheritedConfig = false, ragConfig = AgentRagConfig(docChunkSize = 11),
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 99, docLimit = 99, enableMemory = false,
                enableDocs = false, enableKnowledgeGraph = false, enableRerank = false, memoryPolicy = policy))
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val resolved = AgentConfigResolver(prefs).resolve(agent)
        assertThat(resolved.ragConfig).isNull()
        assertThat(resolved.retrievalConfig).isNull()
        val global = RagConfiguration(memoryLimit = 8, docLimit = 15, rerankTopK = 60, rerankFinalK = 10,
            enableKnowledgeGraph = true, docChunkSize = 1234)
        val manager = mockk<MemoryManager>()
        val captured = slot<MemoryManager.RetrieveOptions>()
        every { manager.ragConfig } returns global
        coEvery { manager.retrieveContext(any(), any(), capture(captured), any()) } returns
            MemoryManager.RetrieveResult("context", emptyList(), MemoryManager.RetrieveMetadata())
        val saved = RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.ALL_AGENTS,
            documentScope = DocumentScope.ALL_INDEXED, enableKnowledgeGraph = true,
            memoryLimit = 1, memoryThreshold = 0.1f, docLimit = 1, docThreshold = 0.1f,
            rerankTopK = 1, rerankFinalK = 1, enableQueryRewrite = false, queryRewriteStrategy = "hyde",
            queryRewriteCount = 1, enableHybridSearch = false, hybridAlpha = 0.1f, hybridBM25Boost = 9f)
        val result = ContextBuilder(ragProvider = MemoryManagerRagAdapter(manager))
            .buildContext(ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a", ragOptions = saved),
                 agentMemoryPolicy = resolved.memoryPolicy))
        // 除会话开关外，检索参数与全局完全一致。
        val override = captured.captured.configOverride!!
        assertThat(override.copy(enableMemory = global.enableMemory, enableDocs = global.enableDocs,
            enableRerank = global.enableRerank, enableKnowledgeGraph = global.enableKnowledgeGraph)).isEqualTo(global)
        assertThat(captured.captured.enableMemory).isTrue()
        assertThat(captured.captured.enableDocs).isTrue()
        assertThat(captured.captured.enableRerank).isTrue()
        assertThat(result.retrieval.effectiveRagOptions!!.memoryScope).isEqualTo(ConversationScope.SESSION)
        assertThat(result.retrieval.effectiveRagOptions!!.graphScope).isEqualTo(ConversationScope.ALL_AGENTS)
        assertThat(result.retrieval.effectiveRagOptions!!.documentScope).isEqualTo(DocumentScope.ALL_INDEXED)
        assertThat(saved.memoryScope).isEqualTo(ConversationScope.ALL_AGENTS)
        assertThat(saved.graphScope).isEqualTo(ConversationScope.ALL_AGENTS)
        assertThat(saved.documentScope).isEqualTo(DocumentScope.ALL_INDEXED)
    }

    @Test fun `智能体检索开关不再关闭已开启的会话检索`() = runTest {
        val rag = mockk<RagProvider>()
        val kg = mockk<KgProvider>()
        coEvery { rag.retrieveContext(any(), any(), any(), any()) } returns
            RagRetrievalResult("", emptyList(), null)
        coEvery { kg.retrieveContext(any(), any(), any(), any()) } returns null
        val result = ContextBuilder(ragProvider = rag, kgProvider = kg)
            .buildContext(ContextBuilderParams("s", "q", assistantMsgId = "m", session = Session("s", "a",
                ragOptions = RagOptions(enableKnowledgeGraph = true))))
        val effective = result.retrieval.effectiveRagOptions!!
        assertThat(effective.enableMemory).isTrue()
        assertThat(effective.enableDocs).isTrue()
        assertThat(effective.enableRerank).isTrue()
        assertThat(effective.enableKnowledgeGraph).isTrue()
        coVerify(exactly = 1) { rag.retrieveContext(any(), any(), any(), any()) }
        coVerify(exactly = 1) { kg.retrieveContext(any(), any(), any(), any()) }
    }
}
