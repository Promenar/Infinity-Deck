package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class VertexStreamingLatencyTest {
    @Test fun `响应未结束时已收到首段正文`() = runBlocking {
        val bytes = ByteChannel(autoFlush = true)
        bytes.writeStringUtf8("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}\n\n")
        val seen = CompletableDeferred<String>()
        val client = HttpClient(MockEngine { call ->
            if (call.url.host == "oauth2.googleapis.com") respond("""{"access_token":"fake","expires_in":3600}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(key) + "\\n-----END PRIVATE KEY-----"
        key.fill(0)
        val credential = """{"project_id":"fixture","client_email":"fixture@example.invalid","private_key":"$pem"}"""
        val protocol = VertexAIProtocol(credential, "fixture", model = "gemini-3-flash", httpClient = client)
        val job = launch { protocol.sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "gemini-3-flash")).collect {
            if (it is StreamChunk.TextDelta && it.content.isNotEmpty()) seen.complete(it.content)
        } }
        val first = withTimeoutOrNull(2000) { seen.await() }
        job.cancelAndJoin()
        bytes.cancel(null)
        client.close()
        assertThat(first).isEqualTo("hello")
    }
}
