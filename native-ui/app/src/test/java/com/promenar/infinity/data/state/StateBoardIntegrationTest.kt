package com.promenar.infinity.data.state

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.StateBoardRepository
import com.promenar.infinity.ui.chat.manager.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StateBoardIntegrationTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: StateBoardRepository
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "assistant"
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        repo = StateBoardRepository(db)
        db.agentDao().insert(AgentEntity("a", "助手", createdAt = 1))
        db.sessionDao().insert(SessionEntity("s", "a", "会话", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity("assistant", "s", "assistant", "回复", createdAt = 1))
    }
    @After fun teardown() = db.close()
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private suspend fun define() = StateDefineSkill(repo).execute(args("""{"title":"冒险","fields":[{"key":"hp","label":"生命值","type":"number","value":80},{"key":"bag","label":"背包","type":"list"}]}"""), context)
    private suspend fun prompt(excluded: Set<String> = emptySet(), enabled: Boolean = true) = ContextBuilder(stateBoardRepository = repo).buildContext(ContextBuilderParams(
        sessionId = "s", content = "继续", assistantMsgId = "assistant", session = Session("s", "a"),
        availableToolNames = if (enabled) setOf("state_define", "state_update", "state_get", "state_remove") else emptySet(),
        stateExcludedMessageIds = excluded,
    )).let { it.finalSystemPrompt + "\n" + it.turnContext }

    @Test fun `读取新状态后冻结块不会降低工具已读版本`() = runBlocking {
        define()
        val builder = ContextBuilder(stateBoardRepository = repo)
        val params = ContextBuilderParams("s", "继续", assistantMsgId = "assistant", session = Session("s", "a"))
        val initial = builder.buildContext(params)
        repo.edit("s", repo.get("s")!!.revision, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        val update = args("""{"operations":[{"key":"hp","op":"increment","value":-5}]}""")
        assertThat(StateUpdateSkill(repo).execute(update, context).status).isEqualTo("error")
        assertThat(StateGetSkill(repo).execute(args("{}"), context).status).isEqualTo("success")
        val frozen = builder.buildContext(params.copy(cachedTurnContext = initial.turnContext, cachedRetrieval = initial.retrieval))
        assertThat(frozen.turnContext).isEqualTo(initial.turnContext)
        assertThat(StateUpdateSkill(repo).execute(update, context).status).isEqualTo("success")
        assertThat(repo.get("s")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(85))
    }

    @Test fun `工具定义读取更新移除返回完整结构与变化`() = runBlocking {
        assertThat(define().status).isEqualTo("success")
        val result = StateUpdateSkill(repo).execute(args("""{"operations":[{"key":"hp","op":"increment","value":-15},{"key":"bag","op":"append","value":["火把"]}]}"""), context)
        assertThat(result.status).isEqualTo("success")
        val changes = args(result.content).getValue("changes").jsonArray
        assertThat(changes.first().jsonObject.getValue("before")).isEqualTo(JsonPrimitive(80))
        assertThat(changes.first().jsonObject.getValue("after")).isEqualTo(JsonPrimitive(65))
        val read = StateGetSkill(repo).execute(args("{}"), context)
        assertThat(args(read.content).getValue("board").jsonObject.getValue("fields").jsonArray).hasSize(2)
        assertThat(StateRemoveSkill(repo).execute(args("{}"), context).status).isEqualTo("success")
        assertThat(args(StateGetSkill(repo).execute(args("{}"), context).content)["board"]).isEqualTo(JsonNull)
    }
    @Test fun `列表工具接受单字符串并按数组持久化且空串不写入`() = runBlocking {
        val defined = StateDefineSkill(repo).execute(args("""{"title":"背包","fields":[{"key":"bag","label":"背包","type":"list","value":"绳子"}]}"""), context)
        assertThat(defined.status).isEqualTo("success")
        val updated = StateUpdateSkill(repo).execute(args("""{"operations":[{"key":"bag","op":"set","value":"火把"},{"key":"bag","op":"append","value":"绳子"},{"key":"bag","op":"remove","value":"火把"}]}"""), context)
        assertThat(updated.status).isEqualTo("success")
        assertThat(repo.get("s")!!.board!!.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
        val revision = repo.get("s")!!.revision
        val invalid = StateUpdateSkill(repo).execute(args("""{"operations":[{"key":"bag","op":"set","value":""}]}"""), context)
        assertThat(invalid.status).isEqualTo("error")
        assertThat(repo.get("s")!!.revision).isEqualTo(revision)
        assertThat(repo.get("s")!!.board!!.fields.single().value).isEqualTo(JsonArray(listOf(JsonPrimitive("绳子"))))
    }
    @Test fun `列表工具参数说明同时声明字符串数组和非空单字符串`() {
        val define = StateDefineSkill(repo)
        val update = StateUpdateSkill(repo)
        val defineValue = args(define.parametersSchema).getValue("properties").jsonObject
            .getValue("fields").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject.getValue("value").jsonObject
        val updateValue = args(update.parametersSchema).getValue("properties").jsonObject
            .getValue("operations").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject.getValue("value").jsonObject
        for (description in listOf(defineValue, updateValue).map { it.getValue("description").jsonPrimitive.content } + update.description) {
            assertThat(description).contains("字符串数组")
            assertThat(description).contains("非空字符串")
        }
    }
    @Test fun `工具错误参数上限与缺少助手身份均返回可操作错误且不写入`() = runBlocking {
        val tool = StateDefineSkill(repo)
        for (raw in listOf("{}", """{"title":1,"fields":[]}""", """{"title":"x","fields":[{"key":"hp","label":"hp","type":"unknown"}]}""", """{"title":"x","fields":[1]}""")) {
            val result = tool.execute(args(raw), context)
            assertThat(result.status).isEqualTo("error")
            assertThat(args(result.content).getValue("suggestion").jsonPrimitive.content).contains("state_get")
        }
        assertThat(StateUpdateSkill(repo).execute(args("""{"operations":[{"key":"hp","op":"clear"}]}"""), context).status).isEqualTo("error")
        assertThat(repo.get("s")).isNull()
        val noOwner = object : SkillExecutionContext by context { override val assistantMessageId: String? = null }
        assertThat(tool.execute(args("""{"title":"x","fields":[{"key":"x","label":"x","type":"text"}]}"""), noOwner).status).isEqualTo("error")
    }
    @Test fun `无面板仅提供简短工具用途有面板注入当前状态与手动修改提示`() = runBlocking {
        assertThat(prompt()).contains("state_define")
        assertThat(prompt()).doesNotContain("## 会话状态面板")
        define()
        assertThat(prompt()).contains("先调用 state_update 再给出正文")
        repo.edit("s", repo.get("s")!!.revision, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        val manual = prompt()
        assertThat(manual).contains("用户手动修改")
        assertThat(manual).contains("\"hp\"")
        assertThat(manual).contains("\"value\":90")
        assertThat(prompt(enabled = false)).doesNotContain("先调用 state_update")
        assertThat(prompt(setOf("assistant"))).doesNotContain("## 会话状态面板")
    }
    @Test fun `重新生成工具作用域排除旧版本并允许取消恢复`() = runBlocking {
        define()
        db.messageDao().insert(MessageEntity("old", "s", "assistant", "旧回复", createdAt = 2))
        repo.update("s", "old", listOf(StateOperation("hp", "set", JsonPrimitive(10))))
        repo.beginGeneration("s", "assistant", setOf("old"))
        try {
            val result = StateUpdateSkill(repo).execute(args("""{"operations":[{"key":"hp","op":"increment","value":-5}]}"""), context)
            assertThat(args(result.content).getValue("changes").jsonArray.first().jsonObject.getValue("after")).isEqualTo(JsonPrimitive(75))
        } finally { repo.endGeneration("s", "assistant") }
    }
    @Test fun `所有工具参数schema可解析且风险分类正确`() {
        listOf(StateDefineSkill(repo), StateUpdateSkill(repo), StateGetSkill(repo), StateRemoveSkill(repo)).forEach {
            assertThat(com.promenar.infinity.domain.tool.ToolSchemaValidator().validateDefinition(it.parametersSchema)).isInstanceOf(com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid::class.java)
            assertThat(args(it.parametersSchema)["type"]).isEqualTo(JsonPrimitive("object"))
            assertThat(it.risk).isEqualTo(if (it.name == "state_get") com.promenar.infinity.domain.tool.ToolRisk.SAFE_READ else com.promenar.infinity.domain.tool.ToolRisk.SESSION_STATE)
        }
    }

    @Test fun `状态读取后的续轮使用请求版本更新人工字段并拒绝请求后的编辑`() = runBlocking {
        define()
        repo.edit("s", repo.get("s")!!.revision, listOf(StateOperation("hp", "set", JsonPrimitive(90))))
        assertThat(StateGetSkill(repo).execute(args("{}"), context).status).isEqualTo("success")
        suspend fun build() = ContextBuilder(stateBoardRepository = repo).buildContext(ContextBuilderParams(
            sessionId = "s", content = "继续", assistantMsgId = "assistant", session = Session("s", "a"),
            availableToolNames = setOf("state_update", "state_get"),
        ))
        val requestContext = build()
        db.messageDao().insert(MessageEntity("next", "s", "assistant", "续轮", createdAt = 2))
        val nextContext = object : SkillExecutionContext by context { override val assistantMessageId = "next" }
        repo.recordContextRead("s", "next", requestContext.stateBoardRevision)
        val operations = args("""{"operations":[{"key":"hp","op":"increment","value":-5}]}""")
        assertThat(StateUpdateSkill(repo).execute(operations, nextContext).status).isEqualTo("success")
        assertThat(repo.get("s")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(85))

        val staleContext = build()
        repo.edit("s", repo.get("s")!!.revision, listOf(StateOperation("hp", "set", JsonPrimitive(100))))
        repo.recordContextRead("s", "next", staleContext.stateBoardRevision)
        assertThat(StateUpdateSkill(repo).execute(operations, nextContext).status).isEqualTo("error")
        assertThat(repo.get("s")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(100))
        repo.recordContextRead("s", "next", build().stateBoardRevision)
        assertThat(StateUpdateSkill(repo).execute(operations, nextContext).status).isEqualTo("success")
        assertThat(repo.get("s")!!.board!!.fields.first().value).isEqualTo(JsonPrimitive(95))
    }

    @Test fun `上下文对长字段和列表有界展示并标记截断`() = runBlocking {
        val fields = List(20) { index -> StateField("k$index", "字段$index", "list", JsonArray(List(50) { JsonPrimitive("x".repeat(200)) })) }
        repo.define("s", "assistant", "面板", fields)
        val context = stateBoardContext(repo.get("s")!!.board)
        assertThat(context.length).isLessThan(9000)
        assertThat(context).contains("\"truncated\":true")
        assertThat(context).contains("\"totalItems\":50")
        assertThat(context).contains("k19")
    }
}
