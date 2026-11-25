package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class VertexStreamingLatencyTest {
    @Test fun `响应未结束时已收到首段正文`() = runBlocking {
        val bytes = ByteChannel(autoFlush = true)
        bytes.writeStringUtf8("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}\n\n")
        val seen = CompletableDeferred<String>()
        val client = client { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
        val protocol = VertexAIProtocol(credential(), "fixture", model = "gemini-3-flash", httpClient = client)
        val job = launch { protocol.sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "gemini-3-flash")).collect {
            if (it is StreamChunk.TextDelta && it.content.isNotEmpty()) seen.complete(it.content)
        } }
        val first = withTimeoutOrNull(2000) { seen.await() }
        job.cancelAndJoin()
        bytes.cancel(null)
        client.close()
        assertThat(first).isEqualTo("hello")
    }

    @Test fun `Vertex HTTP 402 返回脱敏的额度诊断`() = runBlocking {
        val payload = """{"error":{"code":"insufficient_balance","message":"Balance exhausted","api_key":"mock-value"}}"""
        client { respond(payload, HttpStatusCode.PaymentRequired, headersOf(HttpHeaders.ContentType, "application/json")) }.use {
            val protocol = VertexAIProtocol(credential(), "fixture", model = "gemini-3-flash", httpClient = it)
            val request = PromptRequest(listOf(ProtocolMessage("user", "hi")), "gemini-3-flash")
            val error = protocol.sendPrompt(request).filterIsInstance<StreamChunk.Error>().first()
            assertThat(error.code).isEqualTo(GenerationFailureCode.QUOTA)
            assertThat(error.retryable).isFalse()
            assertThat(error.httpStatus).isEqualTo(402)
            assertThat(error.errorCode).isEqualTo("insufficient_balance")
            assertThat(error.providerMessage).isEqualTo("Balance exhausted")
            assertThat(error.rawDiagnostic).contains("Balance exhausted")
            assertThat(error.rawDiagnostic).doesNotContain("mock-value")
        }
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { call ->
            if (call.url.host == "oauth2.googleapis.com") {
                respond("""{"access_token":"fake","expires_in":3600}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                handler(call)
            }
        })

    private fun credential(): String {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(key) + "\\n-----END PRIVATE KEY-----"
        key.fill(0)
        return """{"project_id":"fixture","client_email":"fixture@example.invalid","private_key":"$pem"}"""
    }
}
