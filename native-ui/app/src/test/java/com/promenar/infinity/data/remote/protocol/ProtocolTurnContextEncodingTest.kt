package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.KeyPairGenerator
import java.util.Base64

class ProtocolTurnContextEncodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    enum class WireProtocol { OPENAI, ANTHROPIC, VERTEX, GENERIC, RESPONSES, GEMINI }
    enum class LocalFormat { CHAT_ML, LLAMA_3, MISTRAL, GEMMA }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `纯文本上下文位于逐字保留的用户原文之前`(wire: WireProtocol) = runTest {
        val message = messageWithContext()
        val body = capture(wire, listOf(message))

        assertThat(message.content).isEqualTo(RAW_CONTENT)
        assertThat(encodedContent(wire, body)).isEqualTo(expectedContent(wire, withContext = true))
        assertThat(body.toString()).doesNotContain("cache_control")
        assertThat(body.toString()).doesNotContain("prompt_cache_key")
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `上下文独立文本块先于原文且附件顺序不变`(wire: WireProtocol) = runTest {
        val message = messageWithContext().copy(
            imageUrls = listOf(ImageInput(base64 = "image-one"), ImageInput(base64 = "image-two")),
            documentData = if (wire in DOCUMENT_PROTOCOLS) listOf(
                DocumentInput(base64 = "document-one"), DocumentInput(base64 = "document-two"),
            ) else null,
        )

        val body = capture(wire, listOf(message))

        assertThat(message.content).isEqualTo(RAW_CONTENT)
        assertThat(encodedContent(wire, body)).isEqualTo(expectedContent(wire, withContext = true, withMedia = true))
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `旧消息没有上下文时纯文本出站结构保持不变`(wire: WireProtocol) = runTest {
        val body = capture(wire, listOf(ProtocolMessage("user", RAW_CONTENT)))

        assertThat(encodedContent(wire, body)).isEqualTo(expectedContent(wire, withContext = false))
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `旧消息没有上下文时附件出站结构保持不变`(wire: WireProtocol) = runTest {
        val message = ProtocolMessage("user", RAW_CONTENT).copy(
            imageUrls = listOf(ImageInput(base64 = "image-one"), ImageInput(base64 = "image-two")),
            documentData = if (wire in DOCUMENT_PROTOCOLS) listOf(
                DocumentInput(base64 = "document-one"), DocumentInput(base64 = "document-two"),
            ) else null,
        )
        val body = capture(wire, listOf(message))

        assertThat(encodedContent(wire, body)).isEqualTo(expectedContent(wire, withContext = false, withMedia = true))
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `空上下文与未提供上下文的消息编码相同`(wire: WireProtocol) = runTest {
        val legacy = capture(wire, listOf(ProtocolMessage("user", RAW_CONTENT)))
        val emptyContext = capture(wire, listOf(messageWithContext(context = "")))

        assertThat(emptyContext).isEqualTo(legacy)
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `空正文仍可携带上下文`(wire: WireProtocol) = runTest {
        val body = capture(wire, listOf(messageWithContext(content = "")))
        val expected = when (wire) {
            WireProtocol.OPENAI, WireProtocol.GENERIC, WireProtocol.RESPONSES -> JsonPrimitive("$TURN_CONTEXT\n\n")
            WireProtocol.ANTHROPIC -> buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", TURN_CONTEXT) })
            }
            WireProtocol.VERTEX, WireProtocol.GEMINI -> buildJsonArray {
                add(buildJsonObject { put("text", TURN_CONTEXT) })
            }
        }

        assertThat(encodedContent(wire, body)).isEqualTo(expected)
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `非用户角色的上下文不进入请求体`(wire: WireProtocol) = runTest {
        val messages = listOf(
            ProtocolMessage("system", "固定系统说明"),
            ProtocolMessage("user", "问题"),
            ProtocolMessage("assistant", "回答"),
        )
        val legacy = capture(wire, messages)
        val withIgnoredContext = capture(wire, messages.map { message ->
            if (message.role == "user") message else messageWithContext(message.role, message.content)
        })

        assertThat(withIgnoredContext).isEqualTo(legacy)
    }

    @ParameterizedTest
    @EnumSource(WireProtocol::class)
    fun `工具名称排序且 MCP 参数对象递归规范键顺序`(wire: WireProtocol) = runTest {
        val schema = """{"type":"object","required":["z","a"],"properties":{"z":{"type":"array","items":{"type":"object","properties":{"z":{"type":"number"},"a":{"type":"string"}}}},"a":{"type":"string"}},"allOf":[{"required":["z","a"],"description":"限定"}]}"""
        val reordered = """{"allOf":[{"description":"限定","required":["z","a"]}],"properties":{"a":{"type":"string"},"z":{"items":{"properties":{"a":{"type":"string"},"z":{"type":"number"}},"type":"object"},"type":"array"}},"required":["z","a"],"type":"object"}"""
        fun tool(name: String, parameters: String) = ProtocolTool(
            function = ProtocolToolFunction(name, "工具说明", parameters),
            sourceId = "mcp",
            mcpServerId = "fixture-server",
        )

        val first = capture(wire, listOf(ProtocolMessage("user", RAW_CONTENT)), listOf(tool("z_tool", schema), tool("a_tool", schema)))
        val second = capture(wire, listOf(ProtocolMessage("user", RAW_CONTENT)), listOf(tool("a_tool", reordered), tool("z_tool", reordered)))
        val tools = encodedTools(wire, first)

        assertThat(first.toString()).isEqualTo(second.toString())
        assertThat(tools.map { tool ->
            if (wire in CHAT_PROTOCOLS) tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            else tool.jsonObject["name"]!!.jsonPrimitive.content
        }).containsExactly("a_tool", "z_tool").inOrder()
        val parameters = when (wire) {
            WireProtocol.OPENAI, WireProtocol.GENERIC -> tools.first().jsonObject["function"]!!.jsonObject["parameters"]!!
            WireProtocol.ANTHROPIC -> tools.first().jsonObject["input_schema"]!!
            WireProtocol.VERTEX, WireProtocol.GEMINI -> tools.first().jsonObject["parametersJsonSchema"]!!
            WireProtocol.RESPONSES -> tools.first().jsonObject["parameters"]!!
        }
        assertThat(parameters.toString()).isEqualTo(reordered)
    }

    @ParameterizedTest
    @EnumSource(LocalFormat::class)
    fun `本地模板仅在用户正文前编码上下文`(format: LocalFormat) {
        val template: PromptTemplate = when (format) {
            LocalFormat.CHAT_ML -> ChatMLTemplate()
            LocalFormat.LLAMA_3 -> Llama3Template()
            LocalFormat.MISTRAL -> MistralTemplate()
            LocalFormat.GEMMA -> GemmaTemplate()
        }
        val message = messageWithContext()
        val body = template.format(listOf(message), null)
        val encodedText = "$TURN_CONTEXT\n\n$RAW_CONTENT"

        val expected = when (format) {
            LocalFormat.CHAT_ML -> "<|im_start|>user\n$encodedText<|im_end|>\n<|im_start|>assistant\n"
            LocalFormat.LLAMA_3 -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$encodedText<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            LocalFormat.MISTRAL -> "<s>[INST] $encodedText [/INST]"
            LocalFormat.GEMMA -> "<bos><start_of_turn>user\n$encodedText<end_of_turn>\n<start_of_turn>model\n"
        }
        assertThat(body).isEqualTo(expected)
        assertThat(message.content).isEqualTo(RAW_CONTENT)
    }

    private fun messageWithContext(
        role: String = "user",
        content: String = RAW_CONTENT,
        context: String = TURN_CONTEXT,
    ): ProtocolMessage = json.decodeFromString(ProtocolMessage.serializer(), buildJsonObject {
        put("role", role)
        put("content", content)
        put("turnContext", context)
    }.toString())

    private suspend fun capture(
        wire: WireProtocol,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>? = null,
    ): JsonObject {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val response = if (request.url.host == "oauth2.googleapis.com") {
                """{"access_token":"fixture-token","expires_in":3600}"""
            } else {
                bodies += request.body.toByteReadPacket().readText()
                when (wire) {
                    WireProtocol.OPENAI, WireProtocol.GENERIC ->
                        """{"choices":[{"message":{"content":"完成"},"finish_reason":"stop"}]}"""
                    WireProtocol.ANTHROPIC ->
                        """{"content":[{"type":"text","text":"完成"}],"stop_reason":"end_turn"}"""
                    WireProtocol.VERTEX, WireProtocol.GEMINI ->
                        """{"candidates":[{"content":{"parts":[{"text":"完成"}]},"finishReason":"STOP"}]}"""
                    WireProtocol.RESPONSES -> """{"status":"completed","output_text":"完成"}"""
                }
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        try {
            val protocol: LlmProtocol = when (wire) {
                WireProtocol.OPENAI -> OpenAIProtocol("https://api.openai.com/v1", "fixture-key", MODEL, client)
                WireProtocol.GENERIC -> GenericOpenAICompatProtocol("https://compat.example/v1", "fixture-key", MODEL, httpClient = client)
                WireProtocol.ANTHROPIC -> AnthropicProtocol("https://api.anthropic.com", "fixture-key", MODEL, httpClient = client)
                WireProtocol.RESPONSES -> OpenAIResponsesProtocol("https://api.openai.com/v1", "fixture-key", MODEL, client)
                WireProtocol.GEMINI -> GeminiProtocol("https://generativelanguage.googleapis.com", "fixture-key", MODEL, client)
                WireProtocol.VERTEX -> VertexAIProtocol(
                    serviceAccountJson = validVertexCredentialJson(), projectId = "fixture-project", model = MODEL,
                    httpClient = client,
                )
            }
            protocol.sendPromptSync(PromptRequest(messages, MODEL, tools = tools, stream = false))
            return json.parseToJsonElement(bodies.single()).jsonObject
        } finally {
            client.close()
        }
    }

    private fun encodedContent(wire: WireProtocol, body: JsonObject) = when (wire) {
        WireProtocol.OPENAI, WireProtocol.GENERIC, WireProtocol.ANTHROPIC -> body["messages"]!!.jsonArray.first().jsonObject["content"]!!
        WireProtocol.RESPONSES -> body["input"]!!.jsonArray.first().jsonObject["content"]!!
        WireProtocol.VERTEX, WireProtocol.GEMINI -> body["contents"]!!.jsonArray.first().jsonObject["parts"]!!
    }

    private fun encodedTools(wire: WireProtocol, body: JsonObject): JsonArray = when (wire) {
        WireProtocol.VERTEX, WireProtocol.GEMINI -> body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
        else -> body["tools"]!!.jsonArray
    }

    private fun expectedContent(wire: WireProtocol, withContext: Boolean, withMedia: Boolean = false): kotlinx.serialization.json.JsonElement {
        if (!withMedia && wire in CHAT_PROTOCOLS + WireProtocol.RESPONSES) {
            return JsonPrimitive(if (withContext) "$TURN_CONTEXT\n\n$RAW_CONTENT" else RAW_CONTENT)
        }
        if (!withMedia && !withContext && wire == WireProtocol.ANTHROPIC) return JsonPrimitive(RAW_CONTENT)
        return buildJsonArray {
            fun text(value: String) = buildJsonObject {
                when (wire) {
                    WireProtocol.OPENAI, WireProtocol.GENERIC, WireProtocol.ANTHROPIC -> put("type", "text")
                    WireProtocol.RESPONSES -> put("type", "input_text")
                    else -> Unit
                }
                put("text", value)
            }
            if (withContext) add(text(TURN_CONTEXT))
            add(text(RAW_CONTENT))
            if (withMedia) {
                listOf("image-one", "image-two").forEach { data ->
                    add(when (wire) {
                        WireProtocol.OPENAI, WireProtocol.GENERIC -> buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/png;base64,$data") })
                        }
                        WireProtocol.ANTHROPIC -> buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64"); put("media_type", "image/png"); put("data", data)
                            })
                        }
                        WireProtocol.RESPONSES -> buildJsonObject {
                            put("type", "input_image"); put("image_url", "data:image/png;base64,$data")
                        }
                        WireProtocol.VERTEX, WireProtocol.GEMINI -> inlineData("image/png", data)
                    })
                }
                if (wire in DOCUMENT_PROTOCOLS) listOf("document-one", "document-two").forEach { data ->
                    add(if (wire == WireProtocol.ANTHROPIC) buildJsonObject {
                        put("type", "document")
                        put("source", buildJsonObject {
                            put("type", "base64"); put("media_type", "application/pdf"); put("data", data)
                        })
                    } else inlineData("application/pdf", data))
                }
            }
        }
    }

    private fun inlineData(mimeType: String, data: String) = buildJsonObject {
        put("inlineData", buildJsonObject { put("mimeType", mimeType); put("data", data) })
    }

    private fun validVertexCredentialJson(): String {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(encoded) + "\n-----END PRIVATE KEY-----"
        encoded.fill(0)
        return buildJsonObject {
            put("project_id", "fixture-project")
            put("client_email", "service@example.invalid")
            put("private_key", pem)
        }.toString()
    }

    private companion object {
        const val MODEL = "gemini-3-flash-preview"
        const val RAW_CONTENT = " \t用户原文\r\n\n  末行  \n"
        const val TURN_CONTEXT = "<turn_context>\n当前上下文\n</turn_context>"
        val CHAT_PROTOCOLS = setOf(WireProtocol.OPENAI, WireProtocol.GENERIC)
        val DOCUMENT_PROTOCOLS = setOf(WireProtocol.ANTHROPIC, WireProtocol.VERTEX, WireProtocol.GEMINI)
    }
}
