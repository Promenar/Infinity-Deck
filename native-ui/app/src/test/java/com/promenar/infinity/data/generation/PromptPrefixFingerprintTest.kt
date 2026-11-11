package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PromptPrefixFingerprintTest {
    private val tool = ProtocolTool(function = ProtocolToolFunction("read", "读取", "{}"))
    private fun prompt(vararg messages: ProtocolMessage) = PromptRequest(messages.toList(), "model", tools = listOf(tool))

    @Test fun `仅追加消息时全部旧历史构成前缀`() {
        val first = prompt(ProtocolMessage("system", "系统"), ProtocolMessage("user", "正文"))
        val next = first.copy(messages = first.messages + ProtocolMessage("assistant", "回答"))
        val comparison = comparePromptPrefixes(fingerprintPrompt(first), fingerprintPrompt(next))
        assertThat(comparison.toolsChanged).isFalse()
        assertThat(comparison.systemChanged).isFalse()
        assertThat(comparison.matchingHistoryMessages).isEqualTo(1)
        assertThat(comparison.currentHistoryMessages).isEqualTo(2)
    }

    @Test fun `顺序角色工具参数媒体与工具调用变化均打断对应指纹`() {
        val first = prompt(ProtocolMessage("system", "系统"), ProtocolMessage("user", "a"), ProtocolMessage("assistant", "b"))
        val changed = first.copy(messages = first.messages.dropLast(1) + ProtocolMessage("assistant", "b", toolCalls = listOf(ProtocolToolCall("id", "read", "{}"))))
        assertThat(comparePromptPrefixes(fingerprintPrompt(first), fingerprintPrompt(changed)).matchingHistoryMessages).isEqualTo(1)
        assertThat(fingerprintPrompt(first).toolsHash).isNotEqualTo(fingerprintPrompt(first.copy(tools = listOf(tool.copy(function = tool.function.copy(parameters = "{ }"))))).toolsHash)
        assertThat(fingerprintPrompt(first).historyHashes).isNotEqualTo(fingerprintPrompt(first.copy(messages = first.messages.reversed())).historyHashes)
        val media = first.copy(messages = first.messages.dropLast(1) + first.messages.last().copy(imageUrls = listOf(ImageInput(url = "fixture"))))
        assertThat(comparePromptPrefixes(fingerprintPrompt(first), fingerprintPrompt(media)).matchingHistoryMessages).isEqualTo(1)
    }

    @Test fun `首次未知与日志脱敏且会话相互隔离`() {
        val tracker = PromptPrefixTracker(maxSessions = 1)
        val request = prompt(ProtocolMessage("system", "秘密系统"), ProtocolMessage("user", "秘密用户"))
        val initial = tracker.observe("秘密会话", request)
        assertThat(initial).doesNotContain("秘密")
        assertThat(initial).contains("toolsChanged=null")
        assertThat(tracker.observe("秘密会话", request)).contains("historyPrefix=1")
        tracker.observe("另一个", request)
        assertThat(tracker.observe("秘密会话", request)).contains("toolsChanged=null")
    }
    @Test fun `发行模式跳过指纹计算和日志`() = runBlocking {
        var calculations = 0
        val logs = mutableListOf<String>()
        logPrefixDiagnostic(false, { calculations++; "fingerprint" }, { logs.add(it) })
        assertThat(calculations).isEqualTo(0)
        assertThat(logs).isEmpty()
        logPrefixDiagnostic(true, { calculations++; "fingerprint" }, { logs.add(it) })
        assertThat(calculations).isEqualTo(1)
        assertThat(logs).containsExactly("fingerprint")
        Unit
    }
}
