package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import com.promenar.infinity.data.remote.protocol.GenericOpenAICompatProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.StreamChunk

class ModelLatencyTraceTest {
    @Test fun `各阶段只记录一次并以单调时钟计算耗时`() {
        var now = 100L
        val events = mutableListOf<Pair<String, Long>>()
        val trace = ModelLatencyTrace(50L, { now }, { name, ms -> events += name to ms })
        trace.dispatched()
        now = 130
        trace.firstByte()
        now = 140
        trace.firstByte()
        now = 175
        trace.firstVisible()
        trace.firstVisible()
        assertThat(events).containsExactly("prepare_ms" to 50L, "first_byte_ms" to 30L, "first_text_ms" to 75L).inOrder()
    }

    @Test fun `未发送时不会报告网络和可见耗时`() {
        val events = mutableListOf<Pair<String, Long>>()
        val trace = ModelLatencyTrace(0L, { 5L }, { name, ms -> events += name to ms })
        trace.firstByte()
        trace.firstVisible()
        assertThat(events).isEmpty()
    }
    @Test fun `首字节观测不消费流数据`() = runBlocking {
        val events = mutableListOf<Pair<String, Long>>()
        val trace = ModelLatencyTrace(0L, { 10L }, { name, ms -> events += name to ms })
        trace.dispatched()
        val channel = ByteReadChannel("data: hello\n".toByteArray())
        channel.observeFirstByte(trace)
        assertThat(channel.readUTF8Line()).isEqualTo("data: hello")
        assertThat(events.map { it.first }).containsExactly("prepare_ms", "first_byte_ms").inOrder()
    }
    @Test fun `兼容协议观测首字节且正文保持完整`() = runBlocking {
        val events = mutableListOf<Pair<String, Long>>()
        val trace = ModelLatencyTrace(0L, { 10L }, { name, ms -> events += name to ms })
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) })
        val protocol = GenericOpenAICompatProtocol("https://example.invalid/v1", "", "fixture", httpClient = client)
        val chunks = protocol.sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "fixture")), "fixture", latencyTrace = trace)).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("hello")
        assertThat(events.map { it.first }).containsExactly("prepare_ms", "first_byte_ms").inOrder()
        client.close()
    }
}
