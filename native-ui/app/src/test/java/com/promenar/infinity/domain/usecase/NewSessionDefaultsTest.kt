package com.promenar.infinity.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class NewSessionDefaultsTest {
    @Test fun `默认智能体的新会话只检索本会话对话片段`() {
        val session = newSessionDefaults("session-a", "agent-a", Agent("agent-a", "测试智能体"), null, RagConfiguration(), 42)

        assertThat(session.ragOptions!!.effectiveMemoryScope).isEqualTo(ConversationScope.SESSION)
    }

    @Test fun `智能体默认关闭工具时新会话关闭工具且不复制技能名单`() {
        val agent = Agent("a", "智能体", skills = listOf("a", "b"), toolsEnabledByDefault = false)

        val session = newSessionDefaults("s", "a", agent, null, RagConfiguration(), 42)

        assertThat(session.options.toolsEnabled).isFalse()
        assertThat(session.toEntity().toDomain().options.toolsEnabled).isFalse()
        assertThat(session.activeSkillIds).isEmpty()
        assertThat(agent.skills).containsExactly("a", "b").inOrder()
    }

    @Test fun `智能体采用默认值或不存在时新会话开启工具`() {
        listOf(Agent("a", "智能体"), null).forEach { agent ->
            val session = newSessionDefaults("s", "a", agent, "fallback", RagConfiguration(), 42)
            assertThat(session.options.toolsEnabled).isTrue()
            assertThat(session.activeSkillIds).isEmpty()
        }
    }

    @Test fun `会话初值只采用全局开关及智能体记忆默认与工作区`() {
        val agent = Agent("a", "智能体", modelId = "agent-model", executionMode = ExecutionMode.FULL_ACCESS,
            skills = listOf("a", "b"), mcpServerIds = listOf("mcp"), defaultWorkspaceId = "workspace",
            useInheritedConfig = false, retrievalConfig = AgentRetrievalConfig(enableMemory = true,
                memoryPolicy = AgentMemoryPolicy(memoryDefault = ConversationScope.ALL_AGENTS, memoryCap = ConversationScope.SESSION)))
        val session = newSessionDefaults("s", "a", agent, "fallback", RagConfiguration(enableMemory = false, enableKnowledgeGraph = true), 42)
        assertThat(session.modelId).isEqualTo("agent-model")
        assertThat(session.executionMode).isEqualTo("full_access")
        assertThat(session.activeSkillIds).isEmpty()
        assertThat(session.activeMcpServerIds).containsExactly("mcp")
        assertThat(session.workspaceId).isEqualTo("workspace")
        assertThat(session.ragOptions!!.enableMemory).isFalse()
        assertThat(session.ragOptions!!.enableKnowledgeGraph).isTrue()
        assertThat(session.ragOptions!!.memoryScope).isEqualTo(ConversationScope.SESSION)
        assertThat(session.inferenceParams).isNull()
        assertThat(session.createdAt).isEqualTo(42)
        assertThat(session.updatedAt).isEqualTo(42)
        assertThat(newSessionDefaults("s", "missing", null, "fallback", RagConfiguration(), 42).modelId).isEqualTo("fallback")
        assertThat(newSessionDefaults("s", "a", agent.copy(modelId = " "), "fallback", RagConfiguration(), 42).modelId).isEqualTo("fallback")
    }
}
