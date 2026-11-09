package com.promenar.infinity.data.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserControlTest {
    private val click = BrowserAgentAction(BrowserActionKind.CLICK, "e12", "搜索")

    @Test fun `Agent操作时触碰页面即接手，空闲时触碰不改变状态`() {
        val control = BrowserControl()
        control.userTakeOver(explicit = false)
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.IDLE)
        control.agentStarted(click)
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.AGENT)
        assertThat(control.state.value.action).isEqualTo(click)
        control.userTakeOver(explicit = false)
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.USER)
        // 用户接手期间 Agent 的新动作不能抢回操作权。
        control.agentStarted(click)
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.USER)
        control.handBack()
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.AGENT)
        control.agentIdle()
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.IDLE)
        assertThat(control.state.value.action).isNull()
    }

    @Test fun `用户接手时Agent等待交回，超时返回false`() = runTest {
        val control = BrowserControl()
        control.userTakeOver(explicit = true)
        val waiting = async { control.awaitAgentTurn(60_000) }
        runCurrent()
        assertThat(waiting.isCompleted).isFalse()
        control.handBack()
        runCurrent()
        assertThat(waiting.await()).isTrue()

        control.userTakeOver(explicit = true)
        val timedOut = async { control.awaitAgentTurn(1_000) }
        advanceTimeBy(1_001)
        assertThat(timedOut.await()).isFalse()
    }

    @Test fun `交接态等待用户完成，取消后退出交接但不影响接手`() = runTest {
        val control = BrowserControl()
        control.requestHandoff(BrowserHandoffReason.LOGIN, click)
        assertThat(control.state.value.handoff).isEqualTo(BrowserHandoffReason.LOGIN)
        val waiting = async { control.awaitHandBack(60_000) }
        runCurrent()
        assertThat(waiting.isCompleted).isFalse()
        control.handBack()
        assertThat(waiting.await()).isTrue()
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.AGENT)
        assertThat(control.state.value.handoff).isNull()

        control.requestHandoff(BrowserHandoffReason.PAYMENT, click)
        control.endHandoff()
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.IDLE)
        control.userTakeOver(explicit = true)
        control.endHandoff()
        assertThat(control.state.value.mode).isEqualTo(BrowserMode.USER)
    }
}

class BrowserArgumentsTest {
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun invalid(block: () -> Unit) = assertThrows(BrowserArgumentException::class.java, block)

    @Test fun `网址按主机补全协议并拒绝非网页协议与内嵌凭据`() {
        assertThat(BrowserArguments.url(args("""{"url":" example.com/a?b=1 "}"""))).isEqualTo("https://example.com/a?b=1")
        assertThat(BrowserArguments.url(args("""{"url":"localhost:8080/x"}"""))).isEqualTo("http://localhost:8080/x")
        assertThat(BrowserArguments.url(args("""{"url":"http://example.com"}"""))).isEqualTo("http://example.com")
        for ((host, expected) in listOf(
            "nas" to "http://nas", "foo.localhost:80" to "http://foo.localhost:80",
            "100.64.0.2" to "http://100.64.0.2", "[::1]:8080" to "http://[::1]:8080",
            "box.tail123.ts.net" to "http://box.tail123.ts.net", "example.com" to "https://example.com",
            "fd7a:115c:a1e0::1" to "http://[fd7a:115c:a1e0::1]",
        )) {
            assertThat(BrowserArguments.url(args("""{"url":${kotlinx.serialization.json.JsonPrimitive(host)}}"""))).isEqualTo(expected)
        }
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/a.html", "intent://x#Intent;end", "content://a/b",
            "data:text/html,hi", "https://user:pass@example.com/", "https:///nohost", "")) {
            invalid { BrowserArguments.url(args("""{"url":${kotlinx.serialization.json.JsonPrimitive(bad)}}""")) }
        }
        invalid { BrowserArguments.url(args("""{"url":1}""")) }
    }

    @Test fun `ref只接受快照编号`() {
        assertThat(BrowserArguments.ref(args("""{"ref":" e12 "}"""))).isEqualTo("e12")
        for (bad in listOf("12", "e0", "e012", "E1", "e1;alert(1)", "#login")) {
            invalid { BrowserArguments.ref(args("""{"ref":"$bad"}""")) }
        }
    }

    @Test fun `文本长度、布尔与偏移参数按类型校验`() {
        assertThat(BrowserArguments.text(args("""{"text":""}"""))).isEmpty()
        invalid { BrowserArguments.text(args("""{"text":"${"x".repeat(BrowserArguments.MAX_TEXT + 1)}"}""")) }
        invalid { BrowserArguments.text(args("{}")) }
        assertThat(BrowserArguments.submit(args("{}"))).isFalse()
        assertThat(BrowserArguments.submit(args("""{"submit":true}"""))).isTrue()
        invalid { BrowserArguments.submit(args("""{"submit":"yes"}""")) }
        assertThat(BrowserArguments.includeText(args("""{"include_text":true}"""))).isTrue()
        assertThat(BrowserArguments.textOffset(args("""{"text_offset":6000}"""))).isEqualTo(6000)
        invalid { BrowserArguments.textOffset(args("""{"text_offset":-1}""")) }
        invalid { BrowserArguments.textOffset(args("""{"text_offset":"5"}""")) }
    }
}

