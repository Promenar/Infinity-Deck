package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64

class SessionHeaderTransportTest {
    private val identifier = SessionHeaderPolicy.identifier("salt", "internal-session")
    private val request = PromptRequest(listOf(ProtocolMessage("user", "hello")), "model", stream = false)

    @Test
    fun `六种协议的同步与流式推理仅增加所选头且请求体不变`() = runTest {
        listOf("openai", "generic", "anthropic", "vertex", "gemini", "responses").forEach { kind ->
            listOf(false, true).forEach { streaming ->
                val captured = mutableListOf<HttpRequestData>()
                val http = HttpClient(MockEngine { data ->
                    if (data.url.encodedPath.endsWith("/token")) {
                        assertThat(data.headers["x-opencode-session"]).isNull()
                        assertThat(data.headers[HttpHeaders.UserAgent]).isNotEqualTo("Infinity-Deck/${BuildConfig.VERSION_NAME}")
                        respond("""{"access_token":"synthetic-token","expires_in":3600}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        captured += data
                        val body = if (streaming) streamBody(kind) else syncBody(kind)
                        respond(body, headers = headersOf(HttpHeaders.ContentType, if (streaming) "text/event-stream" else "application/json"))
                    }
                })
                try {
                    val protocol = protocol(kind, http)
                    suspend fun send(value: PromptRequest) {
                        if (streaming) protocol.sendPrompt(value).toList() else protocol.sendPromptSync(value)
                    }
                    val prompt = request.copy(stream = streaming)
                    send(prompt)
                    send(prompt.copy(sessionId = "internal-session"))
                    send(prompt.copy(sessionHeaders = SessionRequestHeaders("x-opencode-session", identifier, "Infinity-Deck/${BuildConfig.VERSION_NAME}")))
                    assertThat(captured).hasSize(3)
                    val baseline = captured[0]
                    assertThat(captured[1].headers).isEqualTo(baseline.headers)
                    assertThat(body(captured[1])).isEqualTo(body(baseline))
                    assertThat(body(captured[2])).isEqualTo(body(baseline))
                    assertThat(captured[2].headers["x-opencode-session"]).isEqualTo(identifier)
                    assertThat(captured[2].headers.getAll(HttpHeaders.UserAgent)).containsExactly("Infinity-Deck/${BuildConfig.VERSION_NAME}")
                    val unrelated = captured[2].headers.entries().filter { it.key.lowercase() !in setOf("x-opencode-session", "user-agent") }.associate { it.key.lowercase() to it.value }
                    assertThat(unrelated).isEqualTo(baseline.headers.entries().filter { it.key.lowercase() != "user-agent" }.associate { it.key.lowercase() to it.value })
                } finally { http.close() }
            }
        }
    }

    @Test
    fun `NEWAPI 模型覆盖与多轮工具续请求重试均显式复用会话`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { data ->
            captured += data
            respond(streamBody("generic"), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        try {
            fun router(mode: SessionHeaderMode, enabled: Boolean = false) = DefaultProviderRequestRouter(
                modelResolver = { ModelInfo("OpenCode Go", "gateway::opencode-go", "", true, providerId = "gateway", remoteModelId = "opencode-go", sessionHeaderMode = mode) },
                providerResolver = { ProviderListItem(id = "gateway", protocolType = ProtocolType.Generic_OpenAI_Compat) },
                configResolver = { ProviderConfig(baseUrl = "https://newapi.invalid/v1", apiKey = "synthetic-key", sessionHeadersEnabled = enabled) },
                sessionIdentifier = { SessionHeaderPolicy.identifier("salt", it) },
                clientFactory = { config -> UnifiedLlmClient({ config }, protocolFactory = { protocol("generic", http) }, sessionIdentifier = { SessionHeaderPolicy.identifier("salt", it) }) },
            )
            suspend fun send(mode: SessionHeaderMode, session: String?, enabled: Boolean = false) {
                val router = router(mode, enabled)
                val resolved = (router.resolve("gateway::opencode-go") as ProviderResolution.Success).value
                router.createClient(resolved).sendStream(
                    StreamTextParams(listOf(ProtocolMessage("user", "hello")), resolved.remoteModelId),
                    StreamConfig(sessionId = session),
                ).toList()
            }
            repeat(4) { send(SessionHeaderMode.ON, "internal-session") }
            assertThat(captured.map { it.headers["x-opencode-session"] }.toSet()).containsExactly(identifier)
            send(SessionHeaderMode.ON, "branch")
            assertThat(captured.last().headers["x-opencode-session"]).isNotEqualTo(identifier)
            send(SessionHeaderMode.OFF, "internal-session", true)
            send(SessionHeaderMode.INHERIT, "internal-session")
            send(SessionHeaderMode.ON, null)
            captured.takeLast(3).forEach {
                assertThat(it.headers["x-opencode-session"]).isNull()
                assertThat(it.headers[HttpHeaders.UserAgent]).isNotEqualTo("Infinity-Deck/${BuildConfig.VERSION_NAME}")
            }
            send(SessionHeaderMode.INHERIT, "internal-session", true)
            assertThat(captured.last().headers["x-opencode-session"]).isEqualTo(identifier)
        } finally { http.close() }
    }

    @Test
    fun `传输元数据永不进入序列化请求体`() {
        val baseline = Json.encodeToString(request)
        assertThat(Json.encodeToString(request.copy(sessionId = "secret-internal-id", sessionHeaders = SessionRequestHeaders("x-session", identifier, "Infinity-Deck/test")))).isEqualTo(baseline)
    }

    private fun body(data: HttpRequestData) = (data.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun protocol(kind: String, http: HttpClient): LlmProtocol = when (kind) {
        "openai" -> OpenAIProtocol("https://newapi.invalid", "synthetic-key", "model", httpClient = http)
        "generic" -> GenericOpenAICompatProtocol("https://newapi.invalid/v1", "synthetic-key", "model", httpClient = http)
        "anthropic" -> AnthropicProtocol("https://newapi.invalid", "synthetic-key", "model", httpClient = http)
        "responses" -> OpenAIResponsesProtocol("https://newapi.invalid", "synthetic-key", "model", httpClient = http)
        "gemini" -> GeminiProtocol("https://newapi.invalid", "synthetic-key", "model", httpClient = http)
        else -> VertexAIProtocol(credentials, "synthetic-project", model = "model", httpClient = http)
    }

    private fun syncBody(kind: String) = when (kind) {
        "anthropic" -> """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""
        "vertex", "gemini" -> """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        "responses" -> """{"status":"completed","output_text":"ok","output":[{"type":"message"}]}"""
        else -> """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
    }
    private fun streamBody(kind: String) = when (kind) {
        "anthropic" -> "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{}}}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        "vertex", "gemini" -> "data: ${syncBody(kind)}\n\n"
        "responses" -> "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":${syncBody(kind)}}\n\n"
        else -> "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    }
    private val credentials by lazy {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(key.encoded) + "\n-----END PRIVATE KEY-----\n"
        """{"project_id":"synthetic-project","client_email":"synthetic@example.invalid","private_key":${Json.encodeToString(pem)}}"""
    }
}
