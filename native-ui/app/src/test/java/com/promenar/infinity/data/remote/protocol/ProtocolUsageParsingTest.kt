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
    fun `输入报告状态区分缺失字段与显式零值`() {
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"completion_tokens":7}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":0}""")).inputReported).isTrue()
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":0}""")).inputReported).isTrue()
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_cache_hit_tokens":10}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.openAiResponses(obj("""{"output_tokens":7}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.openAiResponses(obj("""{"input_tokens":0}""")).inputReported).isTrue()
        assertThat(ProtocolUsageParsers.anthropic(null, obj("""{"output_tokens":7}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.anthropic(obj("""{"input_tokens":0,"cache_read_input_tokens":10}""")).inputReported).isTrue()
        assertThat(ProtocolUsageParsers.anthropic(obj("""{"cache_read_input_tokens":10}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.gemini(obj("""{"candidatesTokenCount":7}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.gemini(obj("""{"promptTokenCount":0}""")).inputReported).isTrue()
    }

    @Test
    fun `非法输入计数不能作为实际输入校准`() {
        assertThat(ProtocolUsageParsers.openAiChat(obj("""{"prompt_tokens":-1}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.openAiResponses(obj("""{"input_tokens":null}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.anthropic(obj("""{"input_tokens":"bad","cache_read_input_tokens":10}""")).inputReported).isFalse()
        assertThat(ProtocolUsageParsers.gemini(obj("""{"promptTokenCount":-1}""")).inputReported).isFalse()
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
    fun `Gemini 服务端工具输入并入总输入且缓存只计一次`() {
        val usage = ProtocolUsageParsers.gemini(obj(
            """{"promptTokenCount":800,"cachedContentTokenCount":512,"toolUsePromptTokenCount":25,
               "candidatesTokenCount":100,"thoughtsTokenCount":60,"totalTokenCount":985}""",
        ))
        assertThat(usage).isEqualTo(ProtocolUsage(825, 160, 985, cacheReadInput = 512, reasoningOutput = 60))
        assertThat(usage.input + usage.output).isEqualTo(usage.total)
        assertThat(ProtocolUsageParsers.gemini(obj("""{"toolUsePromptTokenCount":25}""")).inputReported).isFalse()
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
        val usages = protocol.sendPrompt(request()).toList().filterIsInstance<StreamChunk.Usage>().map { it.usage }
        assertThat(usages).containsExactly(
            ProtocolUsage(3520, 1, 3521, cacheReadInput = 3000, cacheWriteInput = 500),
            ProtocolUsage(3520, 240, 3760, cacheReadInput = 3000, cacheWriteInput = 500),
        ).inOrder()
    }

    @Test
    fun `Anthropic 中断前保留 message_start 的零输出用量`() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":0,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":0}}}

        """.trimIndent() + "\n"
        val chunks = anthropicProtocol(stream).sendPrompt(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Usage>().map { it.usage }).containsExactly(
            ProtocolUsage(3500, 0, 3500, cacheReadInput = 3000, cacheWriteInput = 500, inputReported = true),
        )
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
    }

    @Test
    fun `Anthropic 多个累计 delta 保留最新输入与缓存明细`() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"cache_creation":{"ephemeral_1h_input_tokens":200},"output_tokens":1}}}

            event: message_delta
            data: {"type":"message_delta","delta":{},"usage":{"input_tokens":30,"cache_read_input_tokens":3100,"cache_creation_input_tokens":600,"cache_creation":{"ephemeral_1h_input_tokens":300},"output_tokens":100}}

            event: message_delta
            data: {"type":"message_delta","delta":{},"usage":{"input_tokens":null,"cache_read_input_tokens":null,"cache_creation":{"ephemeral_5m_input_tokens":300},"output_tokens":200}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":240}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n"
        val usages = anthropicProtocol(stream).sendPrompt(request()).toList()
            .filterIsInstance<StreamChunk.Usage>().map { it.usage }
        assertThat(usages.map { it.input }).containsExactly(3520, 3730, 3730, 3730).inOrder()
        assertThat(usages.last()).isEqualTo(
            ProtocolUsage(3730, 240, 3970, cacheReadInput = 3100, cacheWriteInput = 600, cacheWriteLongTtlInput = 300),
        )
    }

    @Test
    fun `Anthropic 仅输出 usage 保留缺失输入标记`() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"output_tokens":0}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n"
        val usage = anthropicProtocol(stream).sendPrompt(request()).toList()
            .filterIsInstance<StreamChunk.Usage>().last().usage
        assertThat(usage.output).isEqualTo(7)
        assertThat(usage.inputReported).isFalse()
    }

    @Test
    fun `Anthropic 异常终止事件保留累计用量`() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":20,"output_tokens":0}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"input_tokens":30,"output_tokens":7}}

        """.trimIndent() + "\n"
        val chunks = anthropicProtocol(stream).sendPrompt(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Usage>().last().usage).isEqualTo(ProtocolUsage(30, 7, 37))
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
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

    private fun anthropicProtocol(stream: String) = AnthropicProtocol(
        baseUrl = "https://api.anthropic.com",
        apiKey = "test-key",
        model = "test-model",
        httpClient = HttpClient(MockEngine {
            respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }),
    )
}
