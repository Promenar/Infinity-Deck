package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KgChatRoutingRegressionTest {
    @Test fun `旧图谱范围保持原始值且会话开关正常传递`() = runTest {
        var captured: RagOptions? = null
        val provider = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>) = KgContextResult("")
            override suspend fun retrieveContext(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions): KgContextResult {
                captured = options
                return KgContextResult("")
            }
        }
        ContextBuilder(kgProvider = provider).buildContext(ContextBuilderParams(
            sessionId = "s", content = "问题", assistantMsgId = "m", session = Session(id = "s", agentId = "a",
                ragOptions = RagOptions(enableDocs = false, enableMemory = false, globalMemoryEnabled = true, activeDocIds = listOf("d"), enableKnowledgeGraph = true)),

        ))
        assertThat(captured?.graphScope).isNull()
        assertThat(captured?.memoryScope).isEqualTo(com.promenar.infinity.data.model.ConversationScope.AGENT)
        assertThat(captured?.activeDocIds).containsExactly("d")
    }

    @Test fun `预算后路径快照与发给模型的本轮上下文使用同一份关系`() = runTest {
        val path = com.promenar.infinity.data.model.KgPath(
            nodes = listOf(com.promenar.infinity.data.model.KgNode("a", "甲", "concept"), com.promenar.infinity.data.model.KgNode("b", "乙", "concept")),
            edges = listOf(com.promenar.infinity.data.model.KgEdge("a", "b", "关系")), origins = listOf("persistent"), score = 1.0)
        val provider = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>) =
                KgContextResult("不应注入的独立文字", listOf(path), canonicalPaths = true, maxContextTokens = 512)
        }
        val result = ContextBuilder(kgProvider = provider).buildContext(ContextBuilderParams("s", "甲", assistantMsgId = "m",
            session = Session("s", "a", ragOptions = RagOptions(enableDocs = false, enableMemory = false, enableKnowledgeGraph = true))))
        val rendered = com.promenar.infinity.data.rag.KgContextBudget.render(result.kgPaths)
        assertThat(rendered).contains("甲（concept） —关系→ 乙（concept）")
        assertThat(result.retrieval.kgContext).isEqualTo(rendered)
        val user = Message("u", MessageRole.USER, "甲", turnContext = result.turnContext)
        val protocol = DefaultChatGenerationContentStrategy(mockk(relaxed = true), null)
            .buildProtocolMessages(Session("s", "a", messages = listOf(user)), result.finalSystemPrompt, user.id)
        assertThat(protocol.first { it.role == "system" }.content).doesNotContain(rendered)
        val sentUser = protocol.single { it.role == "user" }
        assertThat(sentUser.content).isEqualTo("甲")
        assertThat(sentUser.turnContext).isEqualTo(turnContextForModel(result.turnContext))
        assertThat(sentUser.turnContext).contains(rendered)
        assertThat(protocol.joinToString { it.content + it.turnContext.orEmpty() }).doesNotContain("不应注入的独立文字")
    }

    @Test fun `只开启知识图谱也执行图检索`() = runTest {
        var calls = 0
        val provider = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                calls++
                return KgContextResult("图谱资料")
            }
        }
        ContextBuilder(kgProvider = provider).buildContext(ContextBuilderParams(
            sessionId = "s", content = "北京", assistantMsgId = "m",
            session = Session(id = "s", agentId = "a", ragOptions = RagOptions(
                enableDocs = false, enableMemory = false, enableKnowledgeGraph = true,
            )),
        ))
        assertThat(calls).isEqualTo(1)
    }
}
