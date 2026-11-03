package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.*
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.browser.BrowserScreenshotPublisher
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import java.net.InetAddress

class BrowserDeveloperSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "chat-test"
        override val agentId = "agent"
        override val workspacePath = null
        override val workspaceRootUuid = "workspace"
    }
    private val controller = FakeBrowser()
    private val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("203.0.113.10")) })
    private val tools = browserTools(controller, policy, BrowserScreenshotPublisher { session, bytes ->
        assertThat(session).isEqualTo(context.sessionId)
        assertThat(bytes.toList()).containsExactly(1.toByte(), 2.toByte())
        ToolResult("browser_screenshot", "PNG: /media/example.png")
    })
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private suspend fun call(name: String, raw: String = "{}") = tools.single { it.name == name }.execute(args(raw), context)

    @Test fun `全部工具schema有效且只有脚本在命令审批档逐次批准`() {
        val samples = mapOf(
            "browser_eval" to """{"code":"document.title"}""",
            "browser_console" to """{"levels":["error"],"since_last_read":true}""",
            "browser_dom" to """{"selector":"main","limit":10,"max_chars":1000,"text_only":true}""",
            "browser_network" to "{}", "browser_screenshot" to "{}", "browser_tabs" to "{}",
            "browser_set_mode" to """{"mode":"desktop"}""", "browser_new_tab" to """{"url":"https://example.com"}""",
            "browser_switch_tab" to """{"tab_id":"tab-1"}""", "browser_close_tab" to """{"tab_id":"tab-1"}""",
        )
        assertThat(tools.map { it.name }).containsExactlyElementsIn(BROWSER_TOOL_NAMES)
        val validator = ToolSchemaValidator()
        tools.filter { it.name in BROWSER_DEVELOPER_TOOL_NAMES }.forEach {
            assertThat(it.risk).isEqualTo(ToolRisk.SESSION_STATE)
            assertThat(validator.validateDefinition(it.parametersSchema)).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            assertThat(validator.validate(it.parametersSchema, args(samples.getValue(it.name)))).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, it.name, it.risk)).isEqualTo(it.name == "browser_eval")
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, it.name, it.risk)).isFalse()
        }
    }

    @Test fun `开发工具绑定当前会话并标注不可信页面数据`() = runTest {
        assertThat(call("browser_eval", """{"code":"document.title"}""").content).contains(BROWSER_UNTRUSTED_NOTICE)
        assertThat(call("browser_console").content).contains("records")
        assertThat(call("browser_dom", """{"selector":"main"}""").content).contains("DOM")
        assertThat(call("browser_network").content).contains(BROWSER_NETWORK_LIMITATIONS)
        assertThat(controller.sessions.toSet()).containsExactly(context.sessionId)
        assertThat(controller.calls).containsExactly("eval document.title", "console true", "dom main 20 12000 false", "network").inOrder()
    }

    @Test fun `非法参数在调用宿主之前拒绝`() = runTest {
        listOf(
            "browser_eval" to """{"code":false}""", "browser_dom" to """{"selector":"p","limit":51}""",
            "browser_console" to """{"since_last_read":"true"}""", "browser_set_mode" to """{"mode":"tablet"}""",
            "browser_switch_tab" to """{"tab_id":""}""", "browser_new_tab" to """{"url":"file:///tmp/example"}""",
        ).forEach { (name, args) -> assertThat(call(name, args).status).isEqualTo("error") }
        assertThat(controller.calls).isEmpty()
    }

    @Test fun `标签和模式控制返回可供界面使用的完整状态`() = runTest {
        val mode = call("browser_set_mode", """{"mode":"desktop"}""")
        assertThat(mode.content).contains("\"display_mode\":\"desktop\"")
        call("browser_new_tab", """{"url":"https://example.com"}""")
        call("browser_switch_tab", """{"tab_id":"tab-1"}""")
        call("browser_close_tab", """{"tab_id":"tab-1"}""")
        assertThat(call("browser_tabs").content).contains("released")
        assertThat(controller.calls).containsExactly("mode DESKTOP", "new https://example.com", "switch tab-1", "close tab-1", "tabs").inOrder()
    }

    @Test fun `截图字节交给媒体发布器返回工作区路径`() = runTest {
        assertThat(call("browser_screenshot").content).contains("/media/example.png")
        assertThat(controller.calls).containsExactly("screenshot")
    }

    private class FakeBrowser : BrowserController {
        val calls = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        private var state = BrowserUiState(currentTabId = "tab-1", tabs = listOf(BrowserTabState("tab-1")))
        private fun record(session: String, call: String) { sessions += session; calls += call }
        override fun availability() = BrowserAvailability.Supported
        override suspend fun open(sessionId: String, url: String) = BrowserSnapshot(url)
        override suspend fun snapshot(sessionId: String, includeText: Boolean, textOffset: Int) = BrowserSnapshot("https://example.com")
        override suspend fun describe(sessionId: String, ref: String): BrowserElement? = null
        override suspend fun click(sessionId: String, element: BrowserElement) = BrowserSnapshot("https://example.com")
        override suspend fun type(sessionId: String, element: BrowserElement, text: String, submit: Boolean) = BrowserSnapshot("https://example.com")
        override suspend fun handOff(sessionId: String, element: BrowserElement, reason: BrowserHandoffReason) = BrowserSnapshot("https://example.com")
        override suspend fun evaluate(sessionId: String, code: String) = "{}".also { record(sessionId, "eval $code") }
        override suspend fun console(sessionId: String, levels: Set<String>, sinceLastRead: Boolean) = emptyList<BrowserConsoleEntry>().also { record(sessionId, "console $sinceLastRead") }
        override suspend fun dom(sessionId: String, selector: String, limit: Int, maxChars: Int, textOnly: Boolean) = "DOM".also { record(sessionId, "dom $selector $limit $maxChars $textOnly") }
        override suspend fun network(sessionId: String) = emptyList<BrowserNetworkEntry>().also { record(sessionId, "network") }
        override suspend fun screenshot(sessionId: String) = byteArrayOf(1, 2).also { record(sessionId, "screenshot") }
        override suspend fun setDisplayMode(sessionId: String, mode: BrowserDisplayMode): BrowserUiState { record(sessionId, "mode $mode"); state = state.copy(displayMode = mode); return state }
        override suspend fun listTabs(sessionId: String) = state.also { record(sessionId, "tabs") }
        override suspend fun newTab(sessionId: String, url: String) = state.also { record(sessionId, "new $url") }
        override suspend fun switchTab(sessionId: String, tabId: String) = state.also { record(sessionId, "switch $tabId") }
        override suspend fun closeTab(sessionId: String, tabId: String) = state.also { record(sessionId, "close $tabId") }
    }
}
