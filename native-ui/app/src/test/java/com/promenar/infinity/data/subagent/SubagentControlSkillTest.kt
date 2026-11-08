package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
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
}
