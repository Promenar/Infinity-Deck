package com.promenar.infinity.ui.chat.diff

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.agentconfig.AgentConfigOp
import com.promenar.infinity.domain.agentconfig.AgentsWriteRequest
import com.promenar.infinity.domain.model.Agent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class AgentConfigPreviewBuilderTest {
    private val writer = Agent(
        id = "a1", name = "写作助手", systemPrompt = "你是写作助手。\n- 回答尽量详细。", modelId = "p::m1",
        temperature = 0.7, mcpServerIds = listOf("mcp1"),
    )
    private val current = Session(id = "s1", agentId = "a1", title = "周报整理", customPrompt = null, modelId = "p::m1",
        inferenceParams = InferenceParams(temperature = 0.5))
    private val other = Session(id = "s2", agentId = "a1", title = "旧会话")

    private val sources = object : AgentConfigPreviewSources {
        override suspend fun agent(id: String) = writer.takeIf { it.id == id }
        override suspend fun session(id: String) = listOf(current, other).firstOrNull { it.id == id }
        override suspend fun sessionCount(agentId: String) = if (agentId == "a1") 7 else 0
        override suspend fun modelLabel(modelId: String) = when (modelId) {
            "p::m1" -> "Gemini · m1"
            "p::m2" -> "DeepSeek · m2"
            else -> modelId
        }
        override suspend fun mcpName(id: String) = "MCP-$id"
        override suspend fun workspaceName(id: String) = "WS-$id"
    }
    private val labels = AgentConfigValueLabels(on = "开启", off = "关闭", workspaceMode = "命令需批准", untitledSession = "新会话") { "档位-$it" }

    private fun request(json: String) = AgentsWriteRequest.parse(Json.parseToJsonElement(json).jsonObject)!!

    private suspend fun build(json: String) = AgentConfigPreviewBuilder.build(request(json), "s1", sources, labels)

    @Test
    fun `修改当前智能体提示词只给出差异与影响会话数，不进字段表`() = runTest {
        val preview = build("""{"op":"update_agent","fields":{"systemPrompt":"你是写作助手。\n- 先给结论。"},"reason":"用户要求"}""")!!
        assertThat(preview.op).isEqualTo(AgentConfigOp.UPDATE_AGENT)
        assertThat(preview.targetName).isEqualTo("写作助手")
        assertThat(preview.isCurrentTarget).isTrue()
        assertThat(preview.fields).isEmpty()
        assertThat(preview.promptDiff).isNotNull()
        assertThat(preview.impact).isEqualTo(AgentConfigImpact.AgentPrompt("写作助手", 7))
        assertThat(preview.reason).isEqualTo("用户要求")
    }

    @Test
    fun `字段表只列有变化的字段并显示模型与 MCP 名称`() = runTest {
        val preview = build(
            """{"op":"update_agent","id":"a1","fields":{"temperature":0.70,"modelId":"p::m2","mcpServerIds":["mcp1","mcp2"],"toolsEnabledByDefault":false},"reason":"r"}""",
        )!!
        assertThat(preview.fields).containsExactly(
            FieldChange("modelId", "Gemini · m1", "DeepSeek · m2"),
            FieldChange("mcpServerIds", "MCP-mcp1", "MCP-mcp1、MCP-mcp2"),
            FieldChange("toolsEnabledByDefault", "开启", "关闭"),
        ).inOrder()
        assertThat(preview.promptDiff).isNull()
        assertThat(preview.impact).isEqualTo(AgentConfigImpact.AgentFields("写作助手", 7))
    }

    @Test
    fun `清空可空字段显示为默认`() = runTest {
        val preview = build("""{"op":"update_agent","fields":{"temperature":null},"reason":"r"}""")!!
        assertThat(preview.fields).containsExactly(FieldChange("temperature", "0.7", null))
    }

    @Test
    fun `新建智能体全部为新增并附带固定审批方式`() = runTest {
        val preview = build(
            """{"op":"create_agent","fields":{"name":"翻译助手","modelId":"p::m2","reasoningEffort":"low","systemPrompt":"你是翻译助手。"},"reason":"r"}""",
        )!!
        assertThat(preview.targetName).isEqualTo("翻译助手")
        assertThat(preview.fields).containsExactly(
            FieldChange("name", null, "翻译助手"),
            FieldChange("modelId", null, "DeepSeek · m2"),
            FieldChange("reasoningEffort", null, "档位-low"),
            FieldChange(AgentConfigPreviewBuilder.EXECUTION_MODE_KEY, null, "命令需批准"),
        ).inOrder()
        assertThat(preview.promptDiff).isNotNull()
        assertThat(preview.impact).isEqualTo(AgentConfigImpact.NewAgent)
    }

    @Test
    fun `新建会话写明归属智能体，标题缺省时显示新会话`() = runTest {
        val preview = build("""{"op":"create_session","fields":{"customPrompt":"Reply in English."},"reason":"r"}""")!!
        assertThat(preview.ownerAgentName).isEqualTo("写作助手")
        assertThat(preview.targetName).isEqualTo("新会话")
        assertThat(preview.sessionPrompt).isTrue()
        assertThat(preview.promptDiff).isNotNull()
        assertThat(preview.impact).isEqualTo(AgentConfigImpact.NewSession)
    }

    @Test
    fun `修改本会话展开推理参数并区分本会话与其它会话`() = runTest {
        val mine = build(
            """{"op":"update_session","fields":{"title":"Weekly Report","customPrompt":"Always reply in English.","inferenceParams":{"temperature":0.5,"topP":0.9}},"reason":"r"}""",
        )!!
        assertThat(mine.isCurrentTarget).isTrue()
        assertThat(mine.fields).containsExactly(
            FieldChange("title", "周报整理", "Weekly Report"),
            FieldChange("inferenceParams.topP", null, "0.9"),
        ).inOrder()
        assertThat(mine.promptDiff).isNotNull()
        assertThat(mine.impact).isEqualTo(AgentConfigImpact.CurrentSession)

        val theirs = build("""{"op":"update_session","id":"s2","fields":{"title":"新标题"},"reason":"r"}""")!!
        assertThat(theirs.targetName).isEqualTo("旧会话")
        assertThat(theirs.impact).isEqualTo(AgentConfigImpact.OtherSession)
    }

    @Test
    fun `推理参数传 null 时逐项显示恢复默认`() = runTest {
        val preview = build("""{"op":"update_session","fields":{"inferenceParams":null},"reason":"r"}""")!!
        assertThat(preview.fields).containsExactly(FieldChange("inferenceParams.temperature", "0.5", null))
    }

    @Test
    fun `目标不存在或参数不成形时不给预览`() = runTest {
        assertThat(build("""{"op":"update_agent","id":"missing","fields":{"name":"x"},"reason":"r"}""")).isNull()
        assertThat(AgentsWriteRequest.parse(JsonObject(emptyMap()))).isNull()
    }
}
