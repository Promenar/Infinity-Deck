package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.CompletionReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Gemini API 与 Vertex 共用的 generateContent 协议：地址、认证头、函数调用与 thought signature 回传。 */
class GeminiProtocolTest {
    private val tool = ProtocolTool(function = ProtocolToolFunction("weather", "查天气", WEATHER_SCHEMA))

    private class Recorder {
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
    }

    private fun gemini(
        recorder: Recorder = Recorder(),
        baseUrl: String = "https://generativelanguage.googleapis.com",
        respond: MockRequestHandleScope.() -> HttpResponseData = { sse(TEXT_STREAM) },
    ) = GeminiProtocol(
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "gemini-3-flash-preview",
        httpClient = HttpClient(MockEngine { request ->
            recorder.urls += request.url.toString()
            recorder.keys += request.headers[GeminiProtocol.API_KEY_HEADER]
            recorder.bodies += request.body.toByteArray().decodeToString()
            respond()
        }),
    )

    private fun MockRequestHandleScope.sse(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private fun MockRequestHandleScope.jsonBody(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun body(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun prompt(vararg messages: ProtocolMessage, tools: List<ProtocolTool>? = listOf(tool)) =
        PromptRequest(messages.toList(), "gemini-3-flash-preview", tools = tools)

    @Test
    fun `流式与同步请求走官方 v1beta 路径且 API Key 只在请求头`() = runBlocking {
        val recorder = Recorder()
        gemini(recorder).sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)).toList()
        gemini(recorder) { jsonBody(SYNC_RESPONSE) }
            .sendPromptSync(prompt(ProtocolMessage("user", "hi"), tools = null))

        assertThat(recorder.urls).containsExactly(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:streamGenerateContent?alt=sse",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent",
        ).inOrder()
        assertThat(recorder.keys).containsExactly("test-key", "test-key")
        recorder.urls.forEach { assertThat(it).doesNotContain("key=") }
    }

    @Test
    fun `地址保留配置的版本段并还原粘贴的完整路径`() {
        fun resolve(base: String) = ProviderEndpointResolver.resolve(
            ProtocolType.Google_Gemini,
            base,
            ProviderEndpointTarget.GeminiInference("models/gemini-2.5-pro", streaming = false),
        )
        assertThat(resolve("")).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
        )
        assertThat(resolve("https://generativelanguage.googleapis.com/v1")).isEqualTo(
            "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-pro:generateContent",
        )
        assertThat(resolve("https://generativelanguage.googleapis.com/v1beta/models/x:generateContent")).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
        )
        assertThat(resolve("https://proxy.example/gemini")).isEqualTo(
            "https://proxy.example/gemini/v1beta/models/gemini-2.5-pro:generateContent",
        )
        assertThat(
            ProviderEndpointResolver.resolve(ProtocolType.Google_Gemini, "", ProviderEndpointOperation.MODELS),
        ).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
        assertThat(runCatching { resolve("https://proxy.example/v1beta?key=leak") }.isFailure).isTrue()
        assertThat(runCatching {
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_Gemini, "", ProviderEndpointTarget.GeminiInference("..", streaming = true),
            )
        }.isFailure).isTrue()
    }

    @Test
    fun `模型列表去掉 models 前缀`() = runBlocking {
        val ids = gemini(respond = { jsonBody(MODELS_RESPONSE) }).listModels()
        assertThat(ids).containsExactly("gemini-3-flash-preview", "gemini-2.5-pro").inOrder()
    }

    @Test
    fun `流中函数调用与签名在完成前以回传状态发出`() = runBlocking {
        val chunks = gemini(respond = { sse(TOOL_STREAM) })
            .sendPrompt(prompt(ProtocolMessage("user", "杭州和上海天气"))).toList()

        val calls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertThat(calls.map { it.id }).containsExactly("call-1", "call-2").inOrder()
        assertThat(calls.map { it.index }).containsExactly(0, 1).inOrder()
        assertThat(body(calls.first().arguments)["city"]!!.jsonPrimitive.content).isEqualTo("杭州")
        assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().mapNotNull { it.reasoning }.joinToString(""))
            .isEqualTo("先查两个城市")

        val state = chunks.filterIsInstance<StreamChunk.ReasoningState>().single()
        assertThat(state.replay).isEqualTo(ReasoningReplay.GeminiSignatures(calls = mapOf("call-1" to "sig-call")))
        val completed = chunks.last() as StreamChunk.Completed
        assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
        assertThat(completed.completedToolCallIds).containsExactly("call-1", "call-2").inOrder()
        assertThat(chunks.indexOf(state)).isLessThan(chunks.indexOf(completed))
    }

    @Test
    fun `空文本 part 携带的正文签名同样被保存`() = runBlocking {
        val chunks = gemini(respond = { sse(TEXT_SIGNATURE_STREAM) })
            .sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)).toList()
        val state = chunks.filterIsInstance<StreamChunk.ReasoningState>().single()
        assertThat(state.replay).isEqualTo(ReasoningReplay.GeminiSignatures(text = "sig-text"))
        assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("你好")
    }

    @Test
    fun `回传时函数调用签名原样附在对应 part 且服务端 ID 保留`() = runBlocking {
        val recorder = Recorder()
        val signatures = ReasoningReplay.GeminiSignatures(text = "sig-text", calls = mapOf("call-1" to "sig-call"))
        gemini(recorder).sendPrompt(prompt(
            ProtocolMessage("user", "杭州天气"),
            ProtocolMessage(
                role = "assistant",
                content = "我查一下",
                reasoning = "需要调用工具",
                toolCalls = listOf(
                    ProtocolToolCall("call-1", "weather", "{\"city\":\"杭州\"}"),
                    ProtocolToolCall("call-2", "weather", "{\"city\":\"上海\"}"),
                ),
                reasoningReplay = signatures,
            ),
            ProtocolMessage("tool", "晴", toolCallId = "call-1"),
            ProtocolMessage("tool", "雨", toolCallId = "call-2"),
        )).toList()

        val contents = body(recorder.bodies.single())["contents"]!!.jsonArray.map { it.jsonObject }
        assertThat(contents.map { it["role"]!!.jsonPrimitive.content }).containsExactly("user", "model", "user").inOrder()
        val modelParts = contents[1]["parts"]!!.jsonArray.map { it.jsonObject }
        assertThat(modelParts[0]["thought"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(modelParts[1]["text"]!!.jsonPrimitive.content).isEqualTo("我查一下")
        assertThat(modelParts[1]["thoughtSignature"]!!.jsonPrimitive.content).isEqualTo("sig-text")
        assertThat(modelParts[2]["thoughtSignature"]!!.jsonPrimitive.content).isEqualTo("sig-call")
        assertThat(modelParts[2]["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(modelParts[3].containsKey("thoughtSignature")).isFalse()

        val responses = contents[2]["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
        assertThat(responses.map { it["id"]!!.jsonPrimitive.content }).containsExactly("call-1", "call-2").inOrder()
        assertThat(responses.map { it["name"]!!.jsonPrimitive.content }).containsExactly("weather", "weather")
        assertThat(responses.first()["response"]!!.jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("晴")
    }

    @Test
    fun `当前回合缺签名的函数调用只在首个调用补占位签名且历史回合不补`() = runBlocking {
        val recorder = Recorder()
        val localId = GeminiContentCodec.LOCAL_CALL_ID_PREFIX + "x"
        gemini(recorder).sendPrompt(prompt(
            ProtocolMessage("user", "上一回合"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("old", "weather", "{}"))),
            ProtocolMessage("tool", "旧结果", toolCallId = "old"),
            ProtocolMessage("assistant", "旧回答"),
            ProtocolMessage("user", "当前回合"),
            ProtocolMessage(
                role = "assistant",
                content = "",
                reasoning = "其它模型的思考",
                toolCalls = listOf(ProtocolToolCall(localId, "weather", ""), ProtocolToolCall("b", "weather", "{}")),
            ),
            ProtocolMessage("tool", "结果", toolCallId = localId),
            ProtocolMessage("tool", "结果", toolCallId = "b"),
        )).toList()

        val contents = body(recorder.bodies.single())["contents"]!!.jsonArray.map { it.jsonObject }
        val models = contents.filter { it["role"]!!.jsonPrimitive.content == "model" }
            .map { turn -> turn["parts"]!!.jsonArray.map { it.jsonObject } }
        assertThat(models.first().none { it.containsKey("thoughtSignature") }).isTrue()
        val current = models.last()
        assertThat(current.none { it.containsKey("thought") }).isTrue()
        assertThat(current[0]["thoughtSignature"]!!.jsonPrimitive.content)
            .isEqualTo(GeminiContentCodec.SKIP_SIGNATURE_VALIDATOR)
        assertThat(current[0]["functionCall"]!!.jsonObject.containsKey("id")).isFalse()
        assertThat(current[0]["functionCall"]!!.jsonObject["args"]).isEqualTo(JsonObject(emptyMap()))
        assertThat(current[1].containsKey("thoughtSignature")).isFalse()
        val localResponse = contents.last()["parts"]!!.jsonArray.first().jsonObject["functionResponse"]!!.jsonObject
        assertThat(localResponse.containsKey("id")).isFalse()
        assertThat(localResponse["name"]!!.jsonPrimitive.content).isEqualTo("weather")
    }

    @Test
    fun `工具以 JSON Schema 声明且所有请求均不叠加内置搜索`() = runBlocking {
        val recorder = Recorder()
        val protocol = gemini(recorder)
        protocol.sendPrompt(prompt(ProtocolMessage("user", "hi"))).toList()
        protocol.sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)).toList()

        val withTools = body(recorder.bodies[0])["tools"]!!.jsonArray
        assertThat(withTools).hasSize(1)
        val declaration = withTools.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
        assertThat(declaration["parametersJsonSchema"]).isEqualTo(Json.parseToJsonElement(WEATHER_SCHEMA))
        assertThat(declaration.containsKey("parameters")).isFalse()
        assertThat(body(recorder.bodies[1]).containsKey("tools")).isFalse()
    }

    @Test
    fun `旧原生搜索字段任意取值都不影响请求体`() = runBlocking {
        val recorder = Recorder()
        for (enabled in listOf(true, false, null)) {
            gemini(recorder).sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)
                .copy(enableGeminiSearch = enabled)).toList()
        }
        assertThat(recorder.bodies.distinct()).hasSize(1)
        recorder.bodies.forEach { assertThat(body(it).containsKey("tools")).isFalse() }
    }

    @Test
    fun `只发送显式设置的采样参数，思考模型默认请求思考摘要`() = runBlocking {
        val recorder = Recorder()
        val protocol = gemini(recorder)
        protocol.sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)).toList()
        protocol.sendPrompt(
            prompt(ProtocolMessage("user", "hi"), tools = null)
                .copy(temperature = 0.3, maxTokens = 2048, reasoning = true, repetitionPenalty = 1.2),
        ).toList()

        val plain = body(recorder.bodies[0])["generationConfig"]!!.jsonObject
        assertThat(plain.keys).containsExactly("thinkingConfig")
        assertThat(plain["thinkingConfig"]!!.jsonObject.keys).containsExactly("includeThoughts")
        val tuned = body(recorder.bodies[1])["generationConfig"]!!.jsonObject
        assertThat(tuned["temperature"]!!.jsonPrimitive.content).isEqualTo("0.3")
        assertThat(tuned["maxOutputTokens"]!!.jsonPrimitive.content).isEqualTo("2048")
        assertThat(tuned["thinkingConfig"]!!.jsonObject["includeThoughts"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(tuned.keys).containsNoneOf("repetition_penalty", "repetitionPenalty", "max_output_tokens")
    }

    @Test
    fun `非 STOP 终止与截断流均为错误且不发出回传状态`() = runBlocking {
        listOf(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\",\"thoughtSignature\":\"s\"}]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\",\"thoughtSignature\":\"s\"}]}}]}\n\n",
        ).forEach { stream ->
            val chunks = gemini(respond = { sse(stream) })
                .sendPrompt(prompt(ProtocolMessage("user", "hi"), tools = null)).toList()
            assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
            assertThat(chunks.filterIsInstance<StreamChunk.ReasoningState>()).isEmpty()
            assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        }
    }

    @Test
    fun `服务端未返回调用 ID 时生成本地 ID`() = runBlocking {
        val stream = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"weather\",\"args\":{}}," +
            "\"thoughtSignature\":\"sig\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        val chunks = gemini(respond = { sse(stream) }).sendPrompt(prompt(ProtocolMessage("user", "hi"))).toList()
        val id = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().id
        assertThat(id).startsWith(GeminiContentCodec.LOCAL_CALL_ID_PREFIX)
        val state = chunks.filterIsInstance<StreamChunk.ReasoningState>().single().replay
        assertThat((state as ReasoningReplay.GeminiSignatures).calls).containsExactly(id, "sig")
        Unit
    }

    @Test
    fun `同步响应返回函数调用`() = runBlocking {
        val response = gemini(respond = { jsonBody(SYNC_TOOL_RESPONSE) }).sendPromptSync(prompt(ProtocolMessage("user", "hi")))
        assertThat(response.toolCalls!!.single().name).isEqualTo("weather")
        assertThat(response.content).isEqualTo("查一下")
    }

    @Test
    fun `Vertex 共用同一套函数调用与签名处理`() = runBlocking {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "oauth2.googleapis.com") {
                jsonBody("""{"access_token":"fake-token","expires_in":3600}""")
            } else {
                bodies += request.body.toByteArray().decodeToString()
                sse(TOOL_STREAM)
            }
        })
        val protocol = VertexAIProtocol(
            serviceAccountJson = validVertexCredentialJson(),
            projectId = "",
            model = "gemini-3-flash-preview",
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            httpClient = client,
        )
        val chunks = protocol.sendPrompt(prompt(
            ProtocolMessage("user", "天气"),
            ProtocolMessage(
                "assistant", "",
                toolCalls = listOf(ProtocolToolCall("call-0", "weather", "{}")),
                reasoningReplay = ReasoningReplay.GeminiSignatures(calls = mapOf("call-0" to "sig-0")),
            ),
            ProtocolMessage("tool", "晴", toolCallId = "call-0"),
        )).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.ToolCallDelta>()).hasSize(2)
        assertThat((chunks.last() as StreamChunk.Completed).reason).isEqualTo(CompletionReason.TOOL_CALLS)
        val modelPart = body(bodies.single())["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertThat(modelPart["thoughtSignature"]!!.jsonPrimitive.content).isEqualTo("sig-0")
    }

    private fun validVertexCredentialJson(): String {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(encoded) + "\\n-----END PRIVATE KEY-----"
        return """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":"$pem"}"""
    }

    private companion object {
        const val WEATHER_SCHEMA =
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}"""

        const val TEXT_STREAM =
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}]}\r\n\r\n"

        const val TEXT_SIGNATURE_STREAM =
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"好\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\",\"thoughtSignature\":\"sig-text\"}]}," +
                "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2," +
                "\"thoughtsTokenCount\":5,\"totalTokenCount\":10}}\n\n"

        const val TOOL_STREAM =
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"先查两个城市\",\"thought\":true}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"id\":\"call-1\",\"name\":\"weather\",\"args\":{\"city\":\"杭州\"}},\"thoughtSignature\":\"sig-call\"}," +
                "{\"functionCall\":{\"id\":\"call-2\",\"name\":\"weather\",\"args\":{\"city\":\"上海\"}}}" +
                "]},\"finishReason\":\"STOP\"}]}\n\n"

        const val SYNC_RESPONSE =
            """{"candidates":[{"content":{"parts":[{"text":"pong"}]},"finishReason":"STOP"}]}"""

        const val SYNC_TOOL_RESPONSE =
            """{"candidates":[{"content":{"parts":[{"text":"查一下"},{"functionCall":{"id":"c","name":"weather","args":{"city":"杭州"}},"thoughtSignature":"s"}]},"finishReason":"STOP"}]}"""

        const val MODELS_RESPONSE =
            """{"models":[{"name":"models/gemini-3-flash-preview","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}]}"""
    }
}
