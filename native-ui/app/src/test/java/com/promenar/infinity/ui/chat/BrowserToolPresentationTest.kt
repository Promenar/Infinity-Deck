package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.BrowserElement
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserSnapshot
import com.promenar.infinity.data.browser.BrowserSnapshotFormatter
import com.promenar.infinity.data.model.ExecutionStep
import org.junit.Test

class BrowserToolPresentationTest {
    private val snapshot = BrowserSnapshotFormatter.format(BrowserSnapshot("https://example.com/", "示例",
        listOf(BrowserElement("e1", "link", "首页"), BrowserElement("e2", "button", "搜索"))))

    private fun invocation(name: String, args: String, result: String? = null, type: String = "tool_result") =
        ToolInvocationPresentation(listOfNotNull(
            ExecutionStep("s", "tool_call", toolName = name, toolCallId = "c1", toolArgs = args),
            result?.let { ExecutionStep("r", type, toolCallId = "c1", content = it) },
        ))

    @Test fun `打开网页显示去掉www的主机名`() {
        val step = browserStep(invocation("browser_open", """{"url":"https://www.zhihu.com/question/1"}""", snapshot))!!
        assertThat(step.kind).isEqualTo(BrowserStepKind.OPEN)
        assertThat(step.detail).isEqualTo("zhihu.com")
        assertThat(step.running).isFalse()
        assertThat(browserHost("example.org/a")).isEqualTo("example.org")
    }

    @Test fun `读取页面从结果解析可操作元素数量`() {
        val step = browserStep(invocation("browser_snapshot", "{}", snapshot))!!
        assertThat(step.kind).isEqualTo(BrowserStepKind.SNAPSHOT)
        assertThat(step.count).isEqualTo(2)
    }

    @Test fun `点击显示结果中的元素名称，运行中退回ref`() {
        val done = browserStep(invocation("browser_click", """{"ref":"e2"}""", "Clicked e2 \"搜索\".\n\n$snapshot"))!!
        assertThat(done.detail).isEqualTo("搜索")
        val running = browserStep(invocation("browser_click", """{"ref":"e2"}"""))!!
        assertThat(running.running).isTrue()
        assertThat(running.detail).isEqualTo("e2")
    }

    @Test fun `输入显示文本，敏感字段交接显示为用户已处理`() {
        val typed = browserStep(invocation("browser_type", """{"ref":"e3","text":"kotlin"}""", "Typed into e3 \"搜索\".\n\n$snapshot"))!!
        assertThat(typed.kind).isEqualTo(BrowserStepKind.TYPE)
        assertThat(typed.detail).isEqualTo("kotlin")
        val handoff = browserStep(invocation("browser_type", """{"ref":"e4","text":""}""",
            "The password field e4 can only be filled by the user. The user handled the page and handed control back.\n\n$snapshot"))!!
        assertThat(handoff.kind).isEqualTo(BrowserStepKind.HANDOFF)
        assertThat(handoff.handoff).isEqualTo(BrowserHandoffReason.LOGIN)
        val payment = browserStep(invocation("browser_type", """{"ref":"e5","text":""}""", "The payment field e5 can only be filled by the user."))!!
        assertThat(payment.handoff).isEqualTo(BrowserHandoffReason.PAYMENT)
    }

    @Test fun `失败调用标记失败，非浏览器工具不处理`() {
        val failed = browserStep(invocation("browser_click", """{"ref":"e9"}""", "STALE_REF: No element e9.", type = "error"))!!
        assertThat(failed.failed).isTrue()
        assertThat(browserStep(invocation("web_fetch", """{"url":"https://a.test"}""", "x"))).isNull()
        assertThat(toolSummaryAction("browser_type")).isEqualTo(ToolSummaryAction.BROWSER)
    }

    @Test fun `浏览器条目在功能面板入口常驻，整页承载并可保存恢复`() {
        val entries = chatPanelHubEntries(ChatPanelHubSignals(), summary = { null }, onOpen = {})
        assertThat(entries.first { it.page == ChatPage.Browser }.available).isTrue()
        assertThat(chatPageStack(listOf(ChatPage.Browser), wideWindow = false)).containsExactly(ChatPage.Browser)
        assertThat(restoreChatPages("browser")).containsExactly(ChatPage.Browser)
    }

    @Test fun `Agent 操作浏览器时条目运行中，需要你接手时计入待处理`() {
        fun browser(mode: BrowserMode) =
            chatPanelHubEntries(ChatPanelHubSignals(browser = mode), summary = { null }, onOpen = {}).first { it.page == ChatPage.Browser }
        assertThat(browser(BrowserMode.AGENT).running).isTrue()
        assertThat(browser(BrowserMode.AGENT).pending).isEqualTo(0)
        assertThat(browser(BrowserMode.HANDOFF).pending).isEqualTo(1)
        assertThat(browser(BrowserMode.IDLE).running).isFalse()
    }
}
