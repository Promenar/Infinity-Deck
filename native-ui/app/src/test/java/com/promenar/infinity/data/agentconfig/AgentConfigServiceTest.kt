package com.promenar.infinity.data.agentconfig

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.domain.model.*
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AgentConfigServiceTest {
    private lateinit var db: InfinityDatabase
    private lateinit var agents: AgentRepository
    private lateinit var sessions: SessionRepository
    private lateinit var service: AgentConfigService
    private val saved = mutableListOf<Pair<Session, Boolean>>()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        agents = AgentRepository(db.agentDao(), db.agentRuleRevisionDao(), db)
        sessions = SessionRepository(db.sessionDao(), db.messageDao(), database = db)
        agents.create(Agent("a", "助手", description = "描述", modelId = "m", systemPrompt = "Agent 提示", defaultWorkspaceId = "w", mcpServerIds = listOf("mcp")))
        sessions.create(Session("s", "a", title = "会话", modelId = "m", customPrompt = "旧提示", draft = "不可输出草稿",
            summary = "不可输出摘要", lastMessage = "不可输出末条", activeSkillIds = listOf("skill"), activeMcpServerIds = listOf("mcp"),
            inferenceParams = InferenceParams(temperature = 0.5, keepRecentTurns = 7), workspaceId = "w"))
        db.messageDao().insert(MessageEntity("message", "s", "user", "不可输出消息正文", createdAt = 1L))
        db.workspaceDao().insert(WorkspaceEntity("w", "project", "项目", "project", 1L))
        service = AgentConfigService(agents, sessions, { AgentConfigCatalog(mapOf("m" to "模型"), mapOf("skill" to "技能"), mapOf("mcp" to "服务")) },
            { db.workspaceDao().getById(it)?.displayName }, database = db, onSessionSaved = { s, created, _ -> saved += s to created })
    }
    @After fun tearDown() = db.close()

    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun context(target: String? = null, bound: Boolean = target != null, subagent: String? = null, session: String = "s") = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "a"
        override val sourceSubagentId = subagent
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val approvedExecutionTarget = target
        override val isApprovalBoundExecution = bound
    }
    private suspend fun preview(raw: String) = service.preflight(sessions.getConfigurationById("s")!!, raw)
    private suspend fun write(raw: String) = AgentsWriteTool { service }.execute(args(raw), context(preview(raw).target))

    @Test fun `四种读取目标使用当前默认并且不输出任何消息草稿摘要`(): Unit = runBlocking {
        val tool = AgentsReadTool { service }
        val results = listOf("agents", "agent", "sessions", "session").associateWith { target ->
            tool.execute(args("""{"target":"$target"}"""), context()).also { assertThat(it.status).isEqualTo("success") }.content
        }
        val agent = Json.parseToJsonElement(results.getValue("agent")).jsonObject
        assertThat(agent["id"]!!.jsonPrimitive.content).isEqualTo("a")
        assertThat(agent["systemPrompt"]!!.jsonPrimitive.content).isEqualTo("Agent 提示")
        assertThat(agent["defaultWorkspaceName"]!!.jsonPrimitive.content).isEqualTo("项目")
        assertThat(agent["mcpServers"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("服务")
        assertThat(Json.parseToJsonElement(results.getValue("agents")).jsonArray.single().jsonObject["sessionCount"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(Json.parseToJsonElement(results.getValue("sessions")).jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("s")
        val session = Json.parseToJsonElement(results.getValue("session")).jsonObject
        assertThat(session["id"]!!.jsonPrimitive.content).isEqualTo("s")
        assertThat(session["customPrompt"]!!.jsonPrimitive.content).isEqualTo("旧提示")
        assertThat(session["activeSkillIds"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("skill")
        for (result in results.values) for (privateData in listOf("不可输出", "messages", "draft", "summary", "lastMessage")) assertThat(result).doesNotContain(privateData)
    }

    @Test fun `读取单个智能体时agentId与id等价且不回落到当前智能体`(): Unit = runBlocking {
        agents.create(Agent("b", "翻译", systemPrompt = "翻译提示"))
        val tool = AgentsReadTool { service }
        for (key in listOf("id", "agentId")) {
            val result = tool.execute(args("""{"target":"agent","$key":"b"}"""), context())
            assertThat(result.status).isEqualTo("success")
            assertThat(Json.parseToJsonElement(result.content).jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("b")
        }
    }

    @Test fun `用户专属字段和删除在审批前拒绝并且不写入`(): Unit = runBlocking {
        for ((op, field, value) in listOf(
            Triple("update_agent", "executionMode", "\"full_access\""), Triple("update_session", "runMode", "\"LINUX\""),
            Triple("update_session", "workspaceId", "\"w\""), Triple("update_agent", "skills", "[]"),
            Triple("update_agent", "icon", "\"x\""), Triple("update_agent", "delete", "true"),
        )) {
            val raw = """{"op":"$op","fields":{"$field":$value},"reason":"配置"}"""
            val result = preview(raw)
            assertThat(result.target).isNull()
            assertThat(result.error).contains("只能由用户在界面修改")
            assertThat(result.error).contains("入口")
            assertThat(write(raw).status).isEqualTo("error")
        }
        assertThat(preview("""{"op":"delete_agent","fields":{"name":"a"},"reason":"删除"}""").target).isNull()
        assertThat(agents.getById("a")!!.executionMode).isEqualTo(ExecutionMode.WORKSPACE)
        assertThat(sessions.getConfigurationById("s")!!.workspaceId).isEqualTo("w")
    }

    @Test fun `未知字段说明可写字段而不指向界面入口`(): Unit = runBlocking {
        val nested = preview("""{"op":"create_agent","fields":{"name":"T","reason":"建"},"reason":"建"}""")
        assertThat(nested.target).isNull()
        assertThat(nested.error).doesNotContain("只能由用户在界面修改")
        assertThat(nested.error).contains("reason 应放在顶层参数")
        assertThat(nested.error).contains("systemPrompt")
        val unknown = preview("""{"op":"update_agent","fields":{"nickname":"x"},"reason":"改"}""")
        assertThat(unknown.error).contains("nickname 不是可写字段")
        assertThat(unknown.error).doesNotContain("reason 应放在顶层参数")
    }

    @Test fun `引用和类型错误均在审批前失败`(): Unit = runBlocking {
        val invalid = listOf(
            """{"op":"update_agent","fields":{"modelId":"missing"},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"mcpServerIds":["missing"]},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"defaultWorkspaceId":"missing"},"reason":"配置"}""",
            """{"op":"update_session","fields":{"activeSkillIds":["missing"]},"reason":"配置"}""",
            """{"op":"create_session","fields":{"agentId":"missing"},"reason":"配置"}""",
            """{"op":"update_agent","id":"missing","fields":{"name":"a"},"reason":"配置"}""",
            """{"op":"update_session","id":"missing","fields":{"title":"a"},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"topK":1.5},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"temperature":"0.5"},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"toolsEnabledByDefault":null},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"mcpServerIds":[1]},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"reasoningEffort":"unsupported"},"reason":"配置"}""",
            """{"op":"update_session","fields":{"inferenceParams":{"keepRecentTurns":3}},"reason":"配置"}""",
            """{"op":"update_session","fields":{},"reason":"配置"}""",
            """{"op":"update_session","fields":{"title":"新"},"reason":" "}""",
            """{"op":"create_agent","fields":{"description":"描述"},"reason":"配置"}""",
            """{"op":"update_agent","fields":{"name":"a"},"reason":"配置","extra":1}""",
        )
        for (raw in invalid) {
            assertThat(preview(raw).target).isNull()
            assertThat(preview(raw).error).isNotEmpty()
            assertThat(write(raw).status).isEqualTo("error")
        }
        assertThat(agents.getById("a")!!.modelId).isEqualTo("m")
        assertThat(sessions.getConfigurationById("s")!!.title).isEqualTo("会话")
    }

    @Test fun `审批后涉及字段变化即失败而未涉及字段可并存`(): Unit = runBlocking {
        val raw = """{"op":"update_agent","fields":{"systemPrompt":"新提示"},"reason":"规则"}"""
        val target = preview(raw).target!!
        agents.updateFields("a", setOf(AgentField.SYSTEM_PROMPT), agents.getById("a")!!.copy(systemPrompt = "他人提示"))
        val result = AgentsWriteTool { service }.execute(args(raw), context(target))
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains(AgentConfigService.CHANGED)
        assertThat(agents.getById("a")!!.systemPrompt).isEqualTo("他人提示")
        val fresh = preview(raw).target!!
        agents.updateFields("a", setOf(AgentField.IS_PINNED), agents.getById("a")!!.copy(isPinned = true))
        assertThat(AgentsWriteTool { service }.execute(args(raw), context(fresh)).status).isEqualTo("success")
        assertThat(agents.getById("a")!!.isPinned).isTrue()
        assertThat(agents.observePromptRevisions("a").first().first().source).isEqualTo(AgentPromptRevision.Source.AGENT)
    }

    @Test fun `会话审批快照变化或缺少审批绑定不写入`(): Unit = runBlocking {
        val raw = """{"op":"update_session","fields":{"customPrompt":"新提示"},"reason":"规则"}"""
        val target = preview(raw).target!!
        assertThat(AgentsWriteTool { service }.execute(args(raw), context(target, bound = false)).status).isEqualTo("error")
        sessions.updatePartial("s", mapOf("customPrompt" to "他人提示"))
        val result = AgentsWriteTool { service }.execute(args(raw), context(target))
        assertThat(result.status).isEqualTo("error")
        assertThat(sessions.getConfigurationById("s")!!.customPrompt).isEqualTo("他人提示")
        assertThat(saved).isEmpty()
    }

    @Test fun `创建会话继承Agent工作区且不创建消息或切换界面`(): Unit = runBlocking {
        val result = write("""{"op":"create_session","fields":{"title":"新会话"},"reason":"创建"}""")
        assertThat(result.status).isEqualTo("success")
        val id = Json.parseToJsonElement(result.content).jsonObject["id"]!!.jsonPrimitive.content
        val created = sessions.getById(id)!!
        assertThat(created.workspaceId).isEqualTo("w")
        assertThat(created.modelId).isEqualTo("m")
        assertThat(created.messages).isEmpty()
        assertThat(created.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(saved.single().second).isTrue()
        assertThat(sessions.getConfigurationById("s")!!.title).isEqualTo("会话")
    }

    @Test fun `创建Agent固定工作区权限并记录提示词AGENT来源`(): Unit = runBlocking {
        val result = write("""{"op":"create_agent","fields":{"name":"新助手","systemPrompt":"新规则","modelId":"m"},"reason":"创建"}""")
        assertThat(result.status).isEqualTo("success")
        val id = Json.parseToJsonElement(result.content).jsonObject["id"]!!.jsonPrimitive.content
        assertThat(agents.getById(id)!!.executionMode).isEqualTo(ExecutionMode.WORKSPACE)
        assertThat(agents.getById(id)!!.nameCustomized).isTrue()
        assertThat(agents.observePromptRevisions(id).first().single().source).isEqualTo(AgentPromptRevision.Source.AGENT)
    }

    @Test fun `局部推理参数合并保留其它参数且null恢复默认`(): Unit = runBlocking {
        assertThat(write("""{"op":"update_session","fields":{"inferenceParams":{"topK":8},"activeSkillIds":[],"activeMcpServerIds":["mcp"]},"reason":"参数"}""").status).isEqualTo("success")
        val params = sessions.getConfigurationById("s")!!.inferenceParams!!
        assertThat(params.temperature).isEqualTo(0.5)
        assertThat(params.topK).isEqualTo(8)
        assertThat(params.keepRecentTurns).isEqualTo(7)
        assertThat(write("""{"op":"update_session","fields":{"inferenceParams":null,"customPrompt":null,"modelId":null},"reason":"默认"}""").status).isEqualTo("success")
        val cleared = sessions.getConfigurationById("s")!!
        assertThat(cleared.inferenceParams!!.temperature).isNull()
        assertThat(cleared.inferenceParams!!.topK).isNull()
        assertThat(cleared.inferenceParams!!.keepRecentTurns).isEqualTo(7)
        assertThat(cleared.customPrompt).isNull()
        assertThat(cleared.modelId).isNull()
    }

    @Test fun `并发局部保存保留工具提示词与界面标题`(): Unit = runBlocking {
        val realDao = db.sessionDao()
        val readDone = CompletableDeferred<Unit>()
        val writerDone = CompletableDeferred<Unit>()
        val guardedDao = object : com.promenar.infinity.data.local.db.dao.SessionDao by realDao {
            override suspend fun getById(sessionId: String): com.promenar.infinity.data.local.db.entity.SessionEntity? {
                val row = realDao.getById(sessionId)
                if (sessionId == "s") {
                    readDone.complete(Unit)
                    // 事务外读取会允许另一写入先提交，再被旧行覆盖；事务内由 Room 串行提交。
                    if (!db.inTransaction()) writerDone.await()
                }
                return row
            }
        }
        val guarded = SessionRepository(guardedDao, db.messageDao(), database = db)
        val writer = async(Dispatchers.IO) {
            readDone.await()
            sessions.updatePartial("s", mapOf("customPrompt" to "工具刚保存"))
            writerDone.complete(Unit)
        }
        kotlinx.coroutines.withTimeout(5_000) {
            guarded.updatePartial("s", mapOf("title" to "界面改名"))
            writer.await()
        }
        assertThat(sessions.getConfigurationById("s")!!.customPrompt).isEqualTo("工具刚保存")
        assertThat(sessions.getConfigurationById("s")!!.title).isEqualTo("界面改名")
    }

    @Test fun `新建会话附加回调在数据库事务外运行`(): Unit = runBlocking {
        var transactionAtCallback: Boolean? = null
        val callbackRepository = SessionRepository(db.sessionDao(), db.messageDao(), onSessionCreated = {
            transactionAtCallback = db.inTransaction()
        }, database = db)
        val local = AgentConfigService(agents, callbackRepository, { AgentConfigCatalog(mapOf("m" to "模型")) },
            { db.workspaceDao().getById(it)?.displayName }, database = db)
        val raw = """{"op":"create_session","fields":{"title":"新"},"reason":"创建"}"""
        val target = local.preflight(sessions.getConfigurationById("s")!!, raw).target
        assertThat(AgentsWriteTool { local }.execute(args(raw), context(target)).status).isEqualTo("success")
        assertThat(transactionAtCallback).isFalse()
    }

    @Test fun `子代理和分支的来源与目标均被拒绝并从列表排除`(): Unit = runBlocking {
        sessions.create(Session("branch", "a", parentSessionId = "s"))
        sessions.create(Session("detached", "a", branchPlanJson = "{}"))
        sessions.create(Session("sa_hidden", "a"))
        val read = AgentsReadTool { service }
        val write = AgentsWriteTool { service }
        val raw = """{"op":"update_session","fields":{"title":"新"},"reason":"配置"}"""
        assertThat(read.execute(args("""{"target":"agent"}"""), context(subagent = "sa_child")).status).isEqualTo("error")
        assertThat(write.execute(args(raw), context(subagent = "sa_child")).status).isEqualTo("error")
        for (id in listOf("branch", "detached", "sa_hidden")) {
            assertThat(read.execute(args("""{"target":"session","id":"$id"}"""), context()).status).isEqualTo("error")
            assertThat(read.execute(args("""{"target":"agent"}"""), context(session = id)).status).isEqualTo("error")
            assertThat(preview("""{"op":"update_session","id":"$id","fields":{"title":"新"},"reason":"配置"}""").target).isNull()
        }
        val list = Json.parseToJsonElement(read.execute(args("""{"target":"sessions"}"""), context()).content).jsonArray
        assertThat(list.map { it.jsonObject["id"]!!.jsonPrimitive.content }).containsExactly("s")
    }
}
