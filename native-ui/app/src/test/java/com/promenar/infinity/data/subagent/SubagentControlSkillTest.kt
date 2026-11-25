package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.branch.SessionMessageStore
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class SubagentControlSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "parent"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    @Test fun `控制工具只把合法参数交给对应前缀处理器且免审批`() = runTest {
        val calls = mutableListOf<String>()
        val handler = object : SessionAddressHandler {
            override fun accepts(id: String) = id.startsWith("sa_")
            override suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext): String {
                calls += "$operation:$id:${context.sessionId}"; return "ok"
            }
        }
        for (name in listOf("session_read", "session_send", "session_stop")) {
            val skill = SessionSubagentSkill(name, listOf(handler))
            val args = buildJsonObject { put("id", "sa_123abc"); if (name == "session_send") { put("message", "说明"); put("wrapUp", true) } }
            assertThat(skill.execute(args, context).status).isEqualTo("success")
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, name, skill.risk, args.toString())).isFalse()
            assertThat(skill.execute(buildJsonObject { put("id", "branch_test"); if (name == "session_send") put("message", "说明") }, context).content).contains("未找到")
            assertThat(skill.execute(buildJsonObject { put("id", true) }, context).status).isEqualTo("error")
        }
        assertThat(calls).hasSize(3)
    }
    @Test fun `会话读取和检索默认使用当前会话并校验参数`() = runTest {
        val seen = mutableListOf<String>()
        val handler = object : SessionAddressHandler {
            override fun accepts(id: String) = true
            override suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext): String {
                seen += id
                return "ok"
            }
        }
        val read = SessionSubagentSkill("session_read", listOf(handler))
        val search = SessionSubagentSkill("session_search", listOf(handler))
        assertThat(read.execute(buildJsonObject {}, context).status).isEqualTo("success")
        assertThat(search.execute(buildJsonObject { put("query", "关键词") }, context).status).isEqualTo("success")
        assertThat(seen).containsExactly("parent", "parent")
        assertThat(read.execute(buildJsonObject { put("before", "a"); put("after", "b") }, context).status).isEqualTo("error")
        assertThat(read.execute(buildJsonObject { put("limit", 101) }, context).status).isEqualTo("error")
        assertThat(search.execute(buildJsonObject { put("query", "") }, context).status).isEqualTo("error")
        assertThat(search.execute(buildJsonObject { put("query", "x"); put("role", "system") }, context).status).isEqualTo("error")
        assertThat(search.execute(buildJsonObject { put("id", "sa_123abc"); put("query", "x") }, context).status).isEqualTo("error")
    }
    @Test fun `派生参数校验正轮数有限超时和布尔后台选项`() {
        val skill = AgentTaskSkill { error("parse不得启动运行器") }
        fun parse(extra: String) = skill.parse(Json.parseToJsonElement("""{"tasks":[{"description":"任务","prompt":"任务"}],$extra}""").jsonObject)
        assertThat(parse("\"background\":true,\"max_turns\":15,\"timeout_seconds\":0.5")).isNotNull()
        assertThat(parse("\"background\":\"true\"")).isNull()
        assertThat(parse("\"max_turns\":0")).isNull()
        assertThat(parse("\"max_turns\":1.5")).isNull()
        assertThat(parse("\"timeout_seconds\":0")).isNull()
    }

    @Test fun `会话分段参数先校验再寻址`() = runTest {
        val handler = object : SessionAddressHandler {
            override fun accepts(id: String) = true
            override suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext) = "ok"
        }
        val skill = SessionSubagentSkill("session_read", listOf(handler))
        val invalid = listOf(
            """{"message_id":"0","before":"1"}""",
            """{"message_id":"0","after":"1"}""",
            """{"message_id":"0","limit":1}""",
            """{"offset":0}""",
            """{"max_chars":8000}""",
            """{"message_id":"  "}""",
            """{"message_id":"0","offset":-1}""",
            """{"message_id":"0","max_chars":0}""",
            """{"message_id":"0","max_chars":16001}""",
            """{"message_id":"0","max_chars":"1"}""",
        )
        for (args in invalid) {
            val result = skill.execute(Json.parseToJsonElement(args).jsonObject, context)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).isEqualTo("Invalid session_read arguments.")
        }
        for (maxChars in listOf(1, 16_000)) {
            val args = buildJsonObject { put("message_id", "0"); put("offset", Long.MAX_VALUE); put("max_chars", maxChars) }
            assertThat(skill.execute(args, context).status).isEqualTo("success")
        }
    }

    @Test fun `子代理检查点历史支持按索引续读长输出`() = runTest {
        val prefix = "a".repeat(7_999)
        val original = prefix + "😀" + "b".repeat(500)
        val launcher = mockk<SubagentLauncher>()
        coEvery { launcher.readHistory("parent", "sa_123abc") } returns listOf(
            ProtocolMessage("user", "短消息"),
            ProtocolMessage("assistant", original, toolCalls = listOf(ProtocolToolCall("call", "test", "x".repeat(501)))),
        )
        val skill = SessionSubagentSkill("session_read", listOf(SubagentAddressHandler { launcher }))
        suspend fun execute(extra: JsonObjectBuilder.() -> Unit) =
            skill.execute(buildJsonObject { put("id", "sa_123abc"); extra() }, context)
        suspend fun read(extra: JsonObjectBuilder.() -> Unit): JsonObject {
            val result = execute(extra)
            assertThat(result.status).isEqualTo("success")
            return Json.parseToJsonElement(result.content).jsonObject
        }
        val page = read { put("limit", 2) }["messages"]!!.jsonArray
        assertThat(page.first().jsonObject.keys).containsNoneOf("truncated", "total_chars")
        val preview = page.last().jsonObject
        assertThat(preview["index"]).isEqualTo(JsonPrimitive("1"))
        assertThat(preview["content"]).isEqualTo(JsonPrimitive(prefix))
        assertThat(preview["truncated"]).isEqualTo(JsonPrimitive(true))
        assertThat(preview["total_chars"]).isEqualTo(JsonPrimitive(original.length))
        val first = read { put("message_id", "1") }
        assertThat(first["message_id"]).isEqualTo(JsonPrimitive("1"))
        assertThat(first["has_more"]).isEqualTo(JsonPrimitive(true))
        assertThat(first["next_offset"]).isEqualTo(JsonPrimitive(prefix.length))
        assertThat(first["tool_calls"]).isEqualTo(preview["tool_calls"])
        val last = read {
            put("message_id", "1")
            put("offset", first["next_offset"]!!.jsonPrimitive.int)
            put("max_chars", 1_000)
        }
        assertThat(first["content"]!!.jsonPrimitive.content + last["content"]!!.jsonPrimitive.content).isEqualTo(original)
        assertThat(last["has_more"]).isEqualTo(JsonPrimitive(false))
        for (index in listOf("-1", "01", "+1", "2", "2147483648")) {
            assertThat(execute { put("message_id", index) }.status).isEqualTo("error")
        }
        coEvery { launcher.readHistory("parent", "sa_123abc") } returns listOf(
            ProtocolMessage("system", "内部正文", name = SessionMessageStore.WAKE_MESSAGE_NAME),
            ProtocolMessage("user", "可见正文"),
            ProtocolMessage("system", "内部正文", name = SessionMessageStore.DELIVERY_MESSAGE_NAME),
        )
        assertThat(execute { put("message_id", "0") }.content).isEqualTo("消息不存在")
        for (args in listOf<JsonObjectBuilder.() -> Unit>(
            { put("limit", 1) },
            { put("after", "0"); put("limit", 1) },
            { put("before", "2"); put("limit", 1) },
        )) {
            val visiblePage = read(args)
            assertThat(visiblePage["messages"]!!.jsonArray.single().jsonObject["index"]).isEqualTo(JsonPrimitive("1"))
            assertThat(visiblePage["has_more_before"]).isEqualTo(JsonPrimitive(false))
            assertThat(visiblePage["has_more_after"]).isEqualTo(JsonPrimitive(false))
            assertThat(visiblePage.toString()).doesNotContain("内部正文")
        }
    }
}
