package com.promenar.infinity.data.knowledge

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.RequestKnowledgeAccessSkill
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class RequestKnowledgeAccessSkillTest {
    private var session = Session("s", "a", messages = listOf(
        Message("user", MessageRole.USER, "读取资料全文"),
        Message("assistant", MessageRole.ASSISTANT, ""),
    ))
    private var config = AgentRetrievalConfig()
    private val mounts = mutableListOf<ExternalMount>()
    private var writes = 0
    private var previewReads = 0
    private val access = mockk<ExternalWorkspaceAccess>().also { access ->
        coEvery { access.visible("s") } answers { mounts.toList() }
        coEvery { access.addKnowledgeBase(any(), "s", any()) } answers {
            writes++
            ExternalMount("m-$writes", firstArg(), ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, thirdArg(), "s", 1, readOnly = true)
                .also(mounts::add)
        }
    }
    private val source = object : KnowledgeCatalogSource {
        override suspend fun knowledgeBases(session: Session, options: RagOptions): List<KnowledgeBaseSummary> {
            previewReads++
            return listOf(KnowledgeBaseSummary("kb", "资料", 2, true))
        }
        override suspend fun snapshot(session: Session, options: RagOptions) = KnowledgeCatalogSnapshot()
        override fun hasIndexedMaterial(session: Session, options: RagOptions) = false
        override suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation? = null
    }
    private val planner = KnowledgeAccessPlanner({ session }, { config }, access, source)
    private val turns = KnowledgeTurnState()
    private val skill = RequestKnowledgeAccessSkill(planner, { session }, { config }, turns)
    private val args = buildJsonObject { put("reason", "需要读取完整部署步骤") }

    @Test
    fun `缺少审批绑定标记或目标时不能挂载`() = runTest {
        val preview = encode(planner.preview("s"))
        for (context in listOf(context(false, null), context(false, preview), context(true, null))) {
            val result = skill.execute(args, context)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("需要用户批准")
        }
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `真实规划器预览绑定后批准返回只读路径且返回原预览数据`() = runTest {
        val preview = planner.preview("s")
        val result = skill.execute(args, context(true, encode(preview)))
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("/mnt/knowledge")
        assertThat(result.content).contains("只读")
        assertThat(Json.decodeFromString<KnowledgeAccessPreview>(requireNotNull(result.data))).isEqualTo(preview)
        assertThat(mounts.single().readOnly).isTrue()
        assertThat(mounts.single().sourceId).isEqualTo("kb")
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `知识库名称前后空白与预览规范化一致`() = runTest {
        val request = buildJsonObject { put("reason", "读取全文"); put("knowledge_base", " 资料 ") }
        val approved = planner.preview("s", " 资料 ")
        assertThat(skill.execute(request, context(true, encode(approved))).status).isEqualTo("success")
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `批准后智能体范围收紧则拒绝且不写入挂载`() = runTest {
        val approved = encode(planner.preview("s"))
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        val result = skill.execute(args, context(true, approved))
        assertThat(result.status).isEqualTo("error")
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `同轮拒绝后的再次申请直接返回用户未授权并跳过规划器`() = runTest {
        val approved = encode(planner.preview("s"))
        val reads = previewReads
        turns.deny("s", knowledgeTurnId(session, "assistant"))
        val result = skill.execute(args, context(true, approved))
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).isEqualTo("用户未授权")
        assertThat(previewReads).isEqualTo(reads)
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `已保存拒绝消息恢复后同轮不再授权下一轮可重新申请`() = runTest {
        val approved = encode(planner.preview("s"))
        session = session.copy(messages = session.messages + Message("denied", MessageRole.TOOL, "用户未授权", name = "request_knowledge_access"))
        assertThat(skill.execute(args, context(true, approved)).content).isEqualTo("用户未授权")
        assertThat(writes).isEqualTo(0)
        session = session.copy(messages = session.messages + listOf(
            Message("user-2", MessageRole.USER, "现授权读取资料"),
            Message("assistant-2", MessageRole.ASSISTANT, ""),
        ))
        val result = skill.execute(args, context(true, approved, "assistant-2"))
        assertThat(result.status).isEqualTo("success")
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `工具关闭和OFF时不能执行已批准的申请`() = runTest {
        val approved = encode(planner.preview("s"))
        session = session.copy(options = SessionOptions(toolsEnabled = false))
        assertThat(skill.execute(args, context(true, approved)).status).isEqualTo("error")
        session = session.copy(options = SessionOptions())
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        assertThat(skill.execute(args, context(true, approved)).status).isEqualTo("error")
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `审批目标会话或指定知识库与参数不一致时不能执行`() = runTest {
        val preview = planner.preview("s")
        val otherSession = preview.copy(sessionId = "other")
        assertThat(skill.execute(args, context(true, encode(otherSession))).status).isEqualTo("error")
        val otherSelection = preview.copy(knowledgeBase = "资料")
        assertThat(skill.execute(args, context(true, encode(otherSelection))).status).isEqualTo("error")
        assertThat(writes).isEqualTo(0)
    }

    private fun context(bound: Boolean, target: String?, assistantId: String = "assistant") = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val assistantMessageId = assistantId
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val isApprovalBoundExecution = bound
        override val approvedExecutionTarget = target
    }

    private fun encode(preview: KnowledgeAccessPreview) = Json.encodeToString(KnowledgeAccessPreview.serializer(), preview)
}
