package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.BrowserActionException
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserController
import com.promenar.infinity.data.browser.BrowserElement
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserSnapshot
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.net.InetAddress

class BrowserSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "chat-1"
        override val agentId = "agent"
        override val workspacePath = null
        override val workspaceRootUuid = "workspace"
    }
    private val publicPolicy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun tool(name: String, controller: FakeBrowserController, policy: AgentNetworkPolicy = publicPolicy) =
        browserTools(controller, policy).single { it.name == name }

    private val search = BrowserElement("e2", "searchbox", "搜索", tag = "input", inputType = "search", editable = true)
    private val password = BrowserElement("e3", "textbox", "密码", tag = "input", inputType = "password", editable = true)
    private val button = BrowserElement("e4", "button", "登录", tag = "button")

    @Test fun `四个工具schema合法、绑定会话状态且免审批`() {
        val tools = browserTools(FakeBrowserController(), publicPolicy)
        val validator = ToolSchemaValidator()
        assertThat(tools.map { it.name }).containsExactly(BROWSER_OPEN, BROWSER_SNAPSHOT, BROWSER_CLICK, BROWSER_TYPE).inOrder()
        val samples = mapOf(
            BROWSER_OPEN to """{"url":"https://example.com"}""",
            BROWSER_SNAPSHOT to """{"include_text":true,"text_offset":0}""",
            BROWSER_CLICK to """{"ref":"e4"}""",
            BROWSER_TYPE to """{"ref":"e2","text":"kotlin","submit":true}""",
        )
        for (skill in tools) {
            assertThat(skill.risk).isEqualTo(ToolRisk.SESSION_STATE)
            assertThat(validator.validateDefinition(skill.parametersSchema)).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            assertThat(validator.validate(skill.parametersSchema, args(samples.getValue(skill.name)))).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            for (mode in ExecutionMode.entries) {
                assertThat(ToolExecutionPolicy.requiresApproval(mode, skill.name, skill.risk, samples.getValue(skill.name))).isFalse()
            }
        }
    }

    @Test fun `打开网页经网络策略校验并返回快照`() = runTest {
        val controller = FakeBrowserController()
        val result = tool(BROWSER_OPEN, controller).execute(args("""{"url":"example.com"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(controller.calls).containsExactly("open chat-1 https://example.com")
        assertThat(result.content).contains("Interactive elements (3)")
    }

    @Test fun `局域网与本机地址允许打开且无效协议仍拒绝`() = runTest {
        val controller = FakeBrowserController()
        val lan = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("192.168.1.8")) })
        val result = tool(BROWSER_OPEN, controller, lan).execute(args("""{"url":"https://router.test"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(controller.calls).containsExactly("open chat-1 https://router.test")
        val jsController = FakeBrowserController()
        val js = tool(BROWSER_OPEN, jsController).execute(args("""{"url":"javascript:alert(1)"}"""), context)
        assertThat(js.content).startsWith("INVALID_ARGUMENTS")
        assertThat(jsController.calls).isEmpty()
    }

    @Test fun `点击结果首行带元素名称供步骤行显示`() = runTest {
        val controller = FakeBrowserController()
        val result = tool(BROWSER_CLICK, controller).execute(args("""{"ref":"e4"}"""), context)
        assertThat(result.content).startsWith("Clicked e4 \"登录\".")
        assertThat(controller.calls).containsExactly("describe e4", "click e4")
    }

    @Test fun `普通输入框直接输入并可回车提交`() = runTest {
        val controller = FakeBrowserController()
        val result = tool(BROWSER_TYPE, controller).execute(args("""{"ref":"e2","text":"kotlin 协程","submit":true}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(controller.calls).containsExactly("describe e2", "type e2 kotlin 协程 true")
    }

    @Test fun `密码框不代填文本而是交给用户，交回后返回新快照`() = runTest {
        val controller = FakeBrowserController()
        val result = tool(BROWSER_TYPE, controller).execute(args("""{"ref":"e3","text":"hunter2"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(controller.calls).containsExactly("describe e3", "handoff e3 LOGIN")
        assertThat(controller.calls.joinToString()).doesNotContain("hunter2")
        assertThat(result.content).doesNotContain("hunter2")
        assertThat(result.content).contains("can only be filled by the user")
    }

    @Test fun `不可编辑元素与失效ref返回可纠正的错误`() = runTest {
        val controller = FakeBrowserController()
        val notEditable = tool(BROWSER_TYPE, controller).execute(args("""{"ref":"e4","text":"x"}"""), context)
        assertThat(notEditable.content).startsWith("NOT_EDITABLE")
        val stale = tool(BROWSER_CLICK, controller).execute(args("""{"ref":"e99"}"""), context)
        assertThat(stale.status).isEqualTo("error")
        assertThat(stale.content).startsWith("STALE_REF")
        assertThat(stale.content).contains("browser_snapshot")
    }

    @Test fun `用户超时等页面错误透传错误码`() = runTest {
        val controller = FakeBrowserController().apply { failure = BrowserActionException("USER_TIMEOUT", "The user did not hand control back.") }
        val result = tool(BROWSER_CLICK, controller).execute(args("""{"ref":"e4"}"""), context)
        assertThat(result.content).startsWith("USER_TIMEOUT")
    }

    @Test fun `WebView不支持独立配置文件时拒绝并给出当前版本`() = runTest {
        val controller = FakeBrowserController().apply { availability = BrowserAvailability.Unsupported("99.0.1") }
        val result = tool(BROWSER_OPEN, controller).execute(args("""{"url":"https://example.com"}"""), context)
        assertThat(result.content).startsWith("BROWSER_UNSUPPORTED")
        assertThat(result.content).contains("99.0.1")
        assertThat(controller.calls).isEmpty()
    }

    private inner class FakeBrowserController : BrowserController {
        val calls = mutableListOf<String>()
        var availability: BrowserAvailability = BrowserAvailability.Supported
        var failure: BrowserActionException? = null
        private val snapshot = BrowserSnapshot("https://example.com/", "示例", listOf(search, password, button))
        private fun result(): BrowserSnapshot { failure?.let { throw it }; return snapshot }

        override fun availability() = availability
        override suspend fun open(sessionId: String, url: String) = result().also { calls += "open $sessionId $url" }
        override suspend fun snapshot(sessionId: String, includeText: Boolean, textOffset: Int) = result().also { calls += "snapshot $includeText $textOffset" }
        override suspend fun describe(sessionId: String, ref: String): BrowserElement? {
            calls += "describe $ref"
            return snapshot.elements.firstOrNull { it.ref == ref }
        }
        override suspend fun click(sessionId: String, element: BrowserElement) = result().also { calls += "click ${element.ref}" }
        override suspend fun type(sessionId: String, element: BrowserElement, text: String, submit: Boolean) =
            result().also { calls += "type ${element.ref} $text $submit" }
        override suspend fun handOff(sessionId: String, element: BrowserElement, reason: BrowserHandoffReason) =
            result().also { calls += "handoff ${element.ref} $reason" }
    }
}
