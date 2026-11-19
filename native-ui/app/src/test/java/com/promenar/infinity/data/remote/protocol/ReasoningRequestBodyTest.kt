package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.params.ReasoningEffort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** 推理强度与采样参数在各协议请求体中的实际写法。 */
class ReasoningRequestBodyTest {

    private suspend fun body(build: (HttpClient) -> LlmProtocol, request: PromptRequest): JsonObject {
        var captured: String? = null
        val engine = MockEngine { call ->
            captured = call.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        runCatching { build(HttpClient(engine)).sendPromptSync(request.copy(stream = false)) }
        return Json.parseToJsonElement(requireNotNull(captured)).jsonObject
    }

    private fun request(model: String, effort: ReasoningEffort?, temperature: Double? = 0.7) = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "hi")),
        model = model,
        temperature = temperature,
        topP = 0.9,
        reasoningEffort = effort,
    )

    /** 默认用协议的官方地址；自定义兼容协议没有官方地址，用测试域名。 */
    private fun compat(type: ProtocolType, model: String, baseUrl: String = type.defaultBaseUrl.ifEmpty { "https://example.test/v1" }) =
        { client: HttpClient -> GenericOpenAICompatProtocol(baseUrl, "k", model, type, client) }

    private operator fun JsonObject.get(path: String, key: String) = this[path]!!.jsonObject[key]

    @Test
    fun `模型默认时不发送任何推理字段`() = runTest {
        val sent = body(compat(ProtocolType.DeepSeek, "deepseek-v4-pro"), request("deepseek-v4-pro", null))
        assertThat(sent.keys).containsNoneOf("reasoning_effort", "thinking", "enable_thinking")
    }

    @Test
    fun `OpenAI Chat 写 reasoning_effort 并剔除推理中的温度与 Top P`() = runTest {
        val sent = body({ OpenAIProtocol("https://api.openai.com", "k", "gpt-5.5", it) }, request("gpt-5.5", ReasoningEffort.HIGH))
        assertThat(sent["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("high")
        assertThat(sent.keys).containsNoneOf("temperature", "top_p")

        val off = body({ OpenAIProtocol("https://api.openai.com", "k", "gpt-5.5", it) }, request("gpt-5.5", ReasoningEffort.NONE))
        assertThat(off["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("none")
        assertThat(off["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
    }

    @Test
    fun `OpenAI 官方非推理模型不发送官方接口不接受的 top_k 与 repetition_penalty`() = runTest {
        val sent = body(
            { OpenAIProtocol("https://api.openai.com", "k", "gpt-4o", it) },
            request("gpt-4o", ReasoningEffort.HIGH).copy(topK = 40, repetitionPenalty = 1.1),
        )
        assertThat(sent.keys).containsNoneOf("top_k", "repetition_penalty", "reasoning_effort")
        assertThat(sent["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
    }

    @Test
    fun `Responses 写 reasoning effort 嵌套字段`() = runTest {
        val sent = body({ OpenAIResponsesProtocol("https://api.openai.com", "k", "gpt-6-astra", it) }, request("gpt-6-astra", ReasoningEffort.XHIGH))
        assertThat(sent["reasoning", "effort"]!!.jsonPrimitive.content).isEqualTo("xhigh")
        assertThat(sent.keys).doesNotContain("temperature")
    }

    @Test
    fun `DeepSeek 用 thinking 开关加强度，档位就近映射`() = runTest {
        val on = body(compat(ProtocolType.DeepSeek, "deepseek-v4-pro"), request("deepseek-v4-pro", ReasoningEffort.MEDIUM))
        assertThat(on["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("enabled")
        assertThat(on["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("high")
        assertThat(on.keys).doesNotContain("temperature")

        val off = body(compat(ProtocolType.DeepSeek, "deepseek-v4-pro"), request("deepseek-v4-pro", ReasoningEffort.NONE))
        assertThat(off["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("disabled")
        assertThat(off.keys).doesNotContain("reasoning_effort")
        assertThat(off["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
    }

    @Test
    fun `不可关闭的模型不发 thinking 开关，固定参数的模型不发温度`() = runTest {
        val glm = body(compat(ProtocolType.Zhipu_GLM, "glm-5.3"), request("glm-5.3", ReasoningEffort.LOW))
        assertThat(glm.keys).doesNotContain("thinking")
        assertThat(glm["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("low")

        val kimi = body(compat(ProtocolType.Moonshot_Kimi, "kimi-k3"), request("kimi-k3", null))
        assertThat(kimi.keys).containsNoneOf("temperature", "top_p", "reasoning_effort")
    }

    @Test
    fun `第三方或自建接口上的同名模型照常发送资料里不支持的参数`() = runTest {
        val relay = body(compat(ProtocolType.Generic_OpenAI_Compat, "deepseek/deepseek-v4-pro", "https://openrouter.ai/api/v1"),
            request("deepseek/deepseek-v4-pro", ReasoningEffort.HIGH))
        assertThat(relay["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
        assertThat(relay["top_p"]!!.jsonPrimitive.content).isEqualTo("0.9")

        val local = body(compat(ProtocolType.Moonshot_Kimi, "kimi-k3", "http://192.168.1.20:8000/v1"), request("kimi-k3", null))
        assertThat(local["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")

        // 官方接口的子域名仍按文档剔除；仿冒官方域名的主机不算官方。
        val official = body(compat(ProtocolType.Moonshot_Kimi, "kimi-k3", "https://api.moonshot.ai/v1"), request("kimi-k3", null))
        assertThat(official.keys).containsNoneOf("temperature", "top_p")
        val lookalike = body(compat(ProtocolType.Moonshot_Kimi, "kimi-k3", "https://api.moonshot.cn.proxy.example/v1"), request("kimi-k3", null))
        assertThat(lookalike["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
    }

    @Test
    fun `通义千问混合模型用 enable_thinking`() = runTest {
        val sent = body(compat(ProtocolType.Qwen_DashScope, "qwen3.6-plus"), request("qwen3.6-plus", ReasoningEffort.NONE))
        assertThat(sent["enable_thinking"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(sent.keys).doesNotContain("reasoning_effort")
    }

    @Test
    fun `无法识别的模型保持原样发送采样参数`() = runTest {
        val sent = body(compat(ProtocolType.Generic_OpenAI_Compat, "my-local-llm"), request("my-local-llm", ReasoningEffort.HIGH))
        assertThat(sent["temperature"]!!.jsonPrimitive.content).isEqualTo("0.7")
        assertThat(sent.keys).containsNoneOf("reasoning_effort", "thinking")
    }

    private fun anthropic(model: String) = { client: HttpClient -> AnthropicProtocol("https://api.anthropic.com", "k", model, httpClient = client) }

    @Test
    fun `Claude 选档位时自适应思考并写 output_config effort`() = runTest {
        val sent = body(anthropic("claude-opus-5-5"), request("claude-opus-5-5", ReasoningEffort.MAX))
        assertThat(sent["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        assertThat(sent["output_config", "effort"]!!.jsonPrimitive.content).isEqualTo("max")
        assertThat(sent.keys).containsNoneOf("temperature", "top_p")
    }

    @Test
    fun `Claude 关闭思考：可关闭的模型发 disabled，默认不思考的模型省略 thinking`() = runTest {
        val haiku = body(anthropic("claude-haiku-5-5"), request("claude-haiku-5-5", ReasoningEffort.NONE))
        assertThat(haiku["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("disabled")
        assertThat(haiku.keys).doesNotContain("output_config")

        val opus48 = body(anthropic("claude-opus-4-8"), request("claude-opus-4-8", ReasoningEffort.NONE))
        assertThat(opus48.keys).containsNoneOf("thinking", "output_config")

        val opus48High = body(anthropic("claude-opus-4-8"), request("claude-opus-4-8", ReasoningEffort.HIGH))
        assertThat(opus48High["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        assertThat(opus48High["output_config", "effort"]!!.jsonPrimitive.content).isEqualTo("high")
    }

    @Test
    fun `Claude 模型默认沿用原有思考配置，且未设最大输出时不再固定 4096`() = runTest {
        val sent = body(anthropic("claude-sonnet-5"), request("claude-sonnet-5", null, temperature = null))
        assertThat(sent["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        assertThat(sent.keys).doesNotContain("output_config")
        assertThat(sent["max_tokens"]!!.jsonPrimitive.int).isGreaterThan(4096)
    }

    @Test
    fun `Anthropic 兼容端点上的 DeepSeek 写 thinking type 与 output_config effort`() = runTest {
        val sent = body(anthropic("deepseek-v4-pro"), request("deepseek-v4-pro", ReasoningEffort.MAX))
        assertThat(sent["thinking", "type"]!!.jsonPrimitive.content).isEqualTo("enabled")
        assertThat(sent["output_config", "effort"]!!.jsonPrimitive.content).isEqualTo("max")
    }

    private fun gemini(model: String) = { client: HttpClient -> GeminiProtocol("https://generativelanguage.googleapis.com", "k", model, client) }
    private fun JsonObject.thinkingConfig() = this["generationConfig"]!!.jsonObject["thinkingConfig"]?.jsonObject

    @Test
    fun `Gemini 3 起写 thinkingLevel 并请求思考摘要，忽略的采样参数不发送`() = runTest {
        val sent = body(gemini("gemini-4-argon"), request("gemini-4-argon", ReasoningEffort.MINIMAL))
        val config = sent.thinkingConfig()!!
        assertThat(config["thinkingLevel"]!!.jsonPrimitive.content).isEqualTo("low")
        assertThat(config["includeThoughts"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(sent["generationConfig"]!!.jsonObject.keys).containsNoneOf("temperature", "topP")

        val byDefault = body(gemini("gemini-4-argon"), request("gemini-4-argon", null)).thinkingConfig()!!
        assertThat(byDefault.keys).containsExactly("includeThoughts")
    }

    @Test
    fun `Gemini 2_5 用 thinkingBudget，关闭时预算为 0 且不请求思考摘要`() = runTest {
        val high = body(gemini("gemini-2.5-flash"), request("gemini-2.5-flash", ReasoningEffort.HIGH)).thinkingConfig()!!
        assertThat(high["thinkingBudget"]!!.jsonPrimitive.int).isEqualTo(24_576)

        val off = body(gemini("gemini-2.5-flash"), request("gemini-2.5-flash", ReasoningEffort.NONE)).thinkingConfig()!!
        assertThat(off["thinkingBudget"]!!.jsonPrimitive.int).isEqualTo(0)
        assertThat(off.keys).doesNotContain("includeThoughts")
    }
}
