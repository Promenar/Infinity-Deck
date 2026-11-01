package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 各协议 usage 归一：input 含缓存读写、output 含推理，缺失字段为 null，报告为 0 时保持 0。 */
class ProtocolUsageParsingTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `OpenAI 读取缓存与推理明细且缺失时为 null`() {
        val full = ProtocolUsageParsers.openAiChat(obj(
            """{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,
               "prompt_tokens_details":{"cached_tokens":1024},
               "completion_tokens_details":{"reasoning_tokens":128}}""",
        ))
        assertThat(full).isEqualTo(ProtocolUsage(1200, 300, 1500, cacheReadInput = 1024, reasoningOutput = 128))

        val plain = ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}"""))
        assertThat(plain).isEqualTo(ProtocolUsage(3, 1, 4))
        assertThat(plain.cacheReadInput).isNull()
        assertThat(plain.reasoningOutput).isNull()

        val zero = ProtocolUsageParsers.openAiChat(obj(
            """{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":null}""",
        ))
        assertThat(zero.cacheReadInput).isEqualTo(0)
        assertThat(zero.reasoningOutput).isNull()
    }

    @Test
    fun `DeepSeek 命中映射为缓存读取且 miss 不另存`() {
        val usage = ProtocolUsageParsers.openAiChat(obj(
            """{"prompt_tokens":900,"completion_tokens":50,"total_tokens":950,
               "prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260,
               "completion_tokens_details":{"reasoning_tokens":20}}""",
        ))
        assertThat(usage).isEqualTo(ProtocolUsage(900, 50, 950, cacheReadInput = 640, reasoningOutput = 20))
        // 只有 hit/miss 时由二者之和得到全部输入。
        val withoutPrompt = ProtocolUsageParsers.openAiChat(obj("""{"prompt_cache_hit_tokens":10,"prompt_cache_miss_tokens":5}"""))
        assertThat(withoutPrompt.input).isEqualTo(15)
        assertThat(withoutPrompt.cacheReadInput).isEqualTo(10)
    }

    @Test
    fun `Anthropic 输入归一为未缓存加缓存读写`() {
        val start = obj("""{"input_tokens":20,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":1}""")
        val delta = obj("""{"output_tokens":240}""")
        assertThat(ProtocolUsageParsers.anthropic(start, delta)).isEqualTo(
            ProtocolUsage(3520, 240, 3760, cacheReadInput = 3000, cacheWriteInput = 500),
        )
        // 未报告缓存字段时保持 null，input 与原口径一致。
        assertThat(ProtocolUsageParsers.anthropic(obj("""{"input_tokens":10}"""), obj("""{"output_tokens":4}""")))
            .isEqualTo(ProtocolUsage(10, 4, 14))
        // delta 若带累计字段则以 delta 为准。
        assertThat(ProtocolUsageParsers.anthropic(start, obj("""{"output_tokens":240,"cache_read_input_tokens":3100}""")).input)
            .isEqualTo(3620)
    }

    @Test
    fun `Gemini 缓存读取与推理均为子集且思考并入输出`() {
        val usage = GeminiContentCodec.usage(obj(
            """{"promptTokenCount":800,"cachedContentTokenCount":512,"candidatesTokenCount":100,"thoughtsTokenCount":60,"totalTokenCount":960}""",
        ))
        assertThat(usage).isEqualTo(ProtocolUsage(800, 160, 960, cacheReadInput = 512, reasoningOutput = 60))
        val plain = GeminiContentCodec.usage(obj("""{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}"""))
        assertThat(plain).isEqualTo(ProtocolUsage(3, 2, 5))
    }

    @Test
    fun `OpenAI Responses 读取缓存与推理明细`() {
        val usage = ProtocolUsageParsers.openAiResponses(obj(
            """{"input_tokens":50,"output_tokens":30,"input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":12}}""",
        ))
        assertThat(usage).isEqualTo(ProtocolUsage(50, 30, 80, cacheReadInput = 40, reasoningOutput = 12))
    }

    @Test
    fun `负值与非数字字段不产生负数或异常`() {
        val usage = ProtocolUsageParsers.openAiChat(obj(
            """{"prompt_tokens":-5,"completion_tokens":"x","total_tokens":2,"prompt_tokens_details":{"cached_tokens":-1}}""",
        ))
        assertThat(usage.input).isEqualTo(0)
        assertThat(usage.output).isEqualTo(0)
        assertThat(usage.cacheReadInput).isEqualTo(0)
    }

    @Test
    fun `OpenAI 与兼容协议流式 usage 携带缓存与推理字段`() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"42\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":50,\"total_tokens\":950," +
            "\"prompt_cache_hit_tokens\":640,\"prompt_cache_miss_tokens\":260," +
            "\"completion_tokens_details\":{\"reasoning_tokens\":20}}}\n\n" +
            "data: [DONE]\n\n"
        fun client() = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) })
        val protocols = listOf(
            OpenAIProtocol("http://localhost/v1/chat/completions", "", "fixture", client()),
            GenericOpenAICompatProtocol("http://localhost/v1/chat/completions", "", "fixture", httpClient = client()),
        )
        for (protocol in protocols) {
            val usage = protocol.sendPrompt(request()).toList().filterIsInstance<StreamChunk.Usage>().single().usage
            assertThat(usage).isEqualTo(ProtocolUsage(900, 50, 950, cacheReadInput = 640, reasoningOutput = 20))
        }
    }

    @Test
    fun `Anthropic 流式 usage 合并 message_start 的缓存字段`() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":240}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n"
        val protocol = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }),
        )
        val usage = protocol.sendPrompt(request()).toList().filterIsInstance<StreamChunk.Usage>().single().usage
        assertThat(usage).isEqualTo(ProtocolUsage(3520, 240, 3760, cacheReadInput = 3000, cacheWriteInput = 500))
    }

    @Test
    fun `Responses 写入与百炼创建字段保留显式零和缺失语义`() {
        assertThat(ProtocolUsageParsers.openAiResponses(obj("""{"input_tokens":100,"input_tokens_details":{"cache_write_tokens":60}}""")).cacheWriteInput).isEqualTo(60)
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":100,"prompt_tokens_details":{"cache_creation_input_tokens":0}}""")).cacheWriteInput).isEqualTo(0)
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":100,"prompt_tokens_details":{"cache_creation_input_tokens":60}}""")).cacheWriteInput).isEqualTo(60)
    }

    @Test
    fun `长 TTL 明细为总写入子集且缺失零值与仅写入均可辨识`() {
        val responses = ProtocolUsageParsers.openAiResponses(obj("""{"input_tokens":100,"input_tokens_details":{"cache_write_tokens":60,"cache_write_tokens_details":{"ttl_30m_tokens":20,"ttl_12h_tokens":40}}}"""))
        assertThat(responses.cacheWriteInput).isEqualTo(60)
        assertThat(responses.cacheWriteLongTtlInput).isEqualTo(40)
        assertThat(responses.cacheReadInput).isNull()
        val anthropic = ProtocolUsageParsers.anthropic(obj("""{"input_tokens":10,"cache_creation_input_tokens":60,"cache_creation":{"ephemeral_1h_input_tokens":40}}"""))
        assertThat(anthropic.input).isEqualTo(70)
        assertThat(anthropic.cacheWriteLongTtlInput).isEqualTo(40)
        assertThat(ProtocolUsageParsers.anthropic(null, obj("""{"cache_creation":{"ephemeral_1h_input_tokens":0}}""")).cacheWriteLongTtlInput).isEqualTo(0)
        assertThat(ProtocolUsageParsers.openAiResponses(obj("{}")).cacheWriteLongTtlInput).isNull()
        assertThat(ProtocolUsageParsers.openAiResponses(obj("""{"input_tokens_details":{"cache_write_tokens":0,"cache_write_tokens_details":{"ttl_12h_tokens":0}}}""")).cacheWriteLongTtlInput).isEqualTo(0)
        val kimi = ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":12,"cache_write_tokens":0}}"""))
        assertThat(kimi.cacheReadInput).isEqualTo(12)
        assertThat(kimi.cacheWriteInput).isEqualTo(0)
    }

    private fun request() = PromptRequest(listOf(ProtocolMessage("user", "synthetic")), "fixture")
}
