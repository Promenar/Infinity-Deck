package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.ModelLatencyTrace
import com.promenar.infinity.data.remote.StreamConfig
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmDiagnosticIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `重试序号首Token耗时及会话头名称关联到同一次流请求`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        val session = "chat-llm"
        val headerValue = "0123456789abcdef0123456789abcdef"
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.OpenAI_ChatCompletions
            override suspend fun sendPrompt(request: PromptRequest) = flow {
                assertThat(request.sessionHeaders?.identifier).isEqualTo(headerValue)
                request.latencyTrace?.dispatched()
                emit(StreamChunk.TextDelta("synthetic-model-output"))
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val client = UnifiedLlmClient(
            providerConfigResolver = {
                UnifiedProviderConfig(
                    ProtocolType.OpenAI_ChatCompletions, "https://example.invalid/v1", "fixture-key", "model",
                    sessionHeaderName = "x-opencode-session",
                )
            },
            protocolFactory = { protocol },
            sessionIdentifier = { headerValue },
        ).withDiagnosticRoute("provider", "provider::model")
        val trace = ModelLatencyTrace(0, { 1 }) { stage, elapsed ->
            InfinityLogger.diagnostic("generation.latency", mapOf(
                "sessionId" to session, "stage" to stage, "elapsedMs" to elapsed, "attempt" to 2,
            ))
        }
        client.sendStream(
            StreamTextParams(listOf(ProtocolMessage("user", "synthetic-prompt")), model = "model"),
            StreamConfig(sessionId = session, latencyTrace = trace),
        ).toList()

        val events = AppDiagnostics.readEvents(sessionId = session)
        assertThat(events.map { it.event }).containsExactly("llm.route", "generation.latency", "llm.complete").inOrder()
        assertThat(events[0].fields["sessionHeaderName"]).isEqualTo(JsonPrimitive("x-opencode-session"))
        assertThat(events[0].fields["sessionHeaderInjected"]).isEqualTo(JsonPrimitive(true))
        assertThat(events[2].fields["attempt"]).isEqualTo(JsonPrimitive(2))
        assertThat(events[2].fields["modelId"]).isEqualTo(JsonPrimitive("provider::model"))
        assertThat((events[2].fields["firstTokenMs"] as JsonPrimitive).content.toLong()).isAtLeast(0)
        assertThat(events[2].fields["finishReason"]).isEqualTo(JsonPrimitive("END_TURN"))
        val output = events.joinToString("\n") { it.toJsonLine() }
        assertThat(listOf(headerValue, "fixture-key", "synthetic-prompt", "synthetic-model-output")
            .count { it in output }).isEqualTo(0)
    }
}
