package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiRuntimeInstructionScopeTest {
    @Test fun `尾部提醒不截断当前真实用户轮的函数签名范围且标记不出站`() {
        val reminder = ProtocolMessage("user", "尾部提醒", isRuntimeInstruction = true)
        val request = PromptRequest(model = "gemini-model", messages = listOf(
            ProtocolMessage("user", "读取资料"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("call", "read_file", "{}"))),
            ProtocolMessage("tool", "资料", name = "read_file", toolCallId = "call"),
            reminder,
        ))
        val body = Json.parseToJsonElement(GeminiContentCodec.buildRequestBody(request, json)).jsonObject
        val parts = body.getValue("contents").jsonArray.flatMap { it.jsonObject.getValue("parts").jsonArray }
        val call = parts.single { "functionCall" in it.jsonObject }.jsonObject
        assertThat(call["thoughtSignature"]?.jsonPrimitive?.content).isEqualTo("skip_thought_signature_validator")
        assertThat(json.encodeToString(reminder)).doesNotContain("isRuntimeInstruction")
        assertThat(body.toString()).doesNotContain("isRuntimeInstruction")
    }
}
