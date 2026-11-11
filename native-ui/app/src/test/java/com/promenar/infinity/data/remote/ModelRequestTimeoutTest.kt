package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.data.rag.RerankClient
import com.promenar.infinity.data.rag.RerankMethod
import com.promenar.infinity.data.rag.SearchResult
import com.promenar.infinity.data.remote.protocol.OpenAIProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

abstract class ModelRequestTimeoutFixture {
    @After
    fun reset() { ModelRequestTimeouts.install { ModelTimeoutSettings() } }

    protected fun assertTimeout(failure: Throwable?) {
        assertThat(failure).isNotNull()
        val causes = generateSequence(failure) { it.cause }.map { it.javaClass.simpleName }.toList()
        assertThat(causes.any { it.contains("Timeout") }).isTrue()
    }

    protected val prompt = PromptRequest(messages = listOf(ProtocolMessage("user", "test")), model = "test")

    protected class LocalServer(private val respond: (Socket) -> Unit) : Closeable {
        protected val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/v1"
        protected val workers = mutableListOf<Thread>()
        protected val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                synchronized(workers) {
                    workers += thread(isDaemon = true) {
                        socket.use {
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: return@thread
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { input.read() }
                            // 超时测试的对端可能已关连接，写入失败属于预期。
                            try { respond(socket) } catch (_: java.io.IOException) { }
                        }
                    }
                }
            }
        }
        override fun close() {
            server.close()
            acceptor.join(1000)
            synchronized(workers) { workers.toList() }.forEach { it.join(2500) }
        }
    }

    protected fun Socket.write(text: String) { getOutputStream().apply { write(text.toByteArray()); flush() } }
    protected fun Socket.streamHeaders() = write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
    protected fun Socket.finishStream() = write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
    protected fun Socket.json(body: String) = write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
}

class ModelRequestTimeoutTest : ModelRequestTimeoutFixture() {
    @Test
    fun `流式总时长超过配置值仍完整收到全部内容`() = runBlocking {
        ModelRequestTimeouts.install { ModelTimeoutSettings(llmSeconds = 2) }
        // 客户端级短总超时代表旧默认值，按请求配置必须覆盖它。
        ModelHttp.client { install(HttpTimeout) { requestTimeoutMillis = 2000 } }.use { transport ->
            LocalServer { socket ->
                socket.streamHeaders()
                repeat(9) {
                    Thread.sleep(500)
                    socket.write("data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n")
                }
                socket.finishStream()
            }.use { server ->
                val client = OpenAIProtocol(server.url, "test-key", "test", transport)
                val chunks = withTimeout(10_000) { client.sendPrompt(prompt).toList() }
                assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("012345678")
                assertThat(chunks.filterIsInstance<StreamChunk.Error>()).isEmpty()
                assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).hasSize(1)
            }
        }
    }

    @Test
    fun `字节持续到达但尚未形成完整事件时不会触发行级超时`() = runBlocking {
        ModelRequestTimeouts.install { ModelTimeoutSettings(llmSeconds = 1) }
        LocalServer { socket ->
            socket.streamHeaders()
            socket.write("data: {\"choices\":[{\"delta\":{\"content\":\"")
            repeat(6) { Thread.sleep(300); socket.write("x") }
            socket.write("\"}}]}\n\n")
            socket.finishStream()
        }.use { server ->
            val chunks = withTimeout(6_000) {
                OpenAIProtocol(server.url, "test-key", "test").sendPrompt(prompt.copy(streamTimeout = 500)).toList()
            }
            assertThat(chunks.filterIsInstance<StreamChunk.Error>()).isEmpty()
            assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("xxxxxx")
        }
    }

}

class ModelRequestTimeoutStreamingTest : ModelRequestTimeoutFixture() {
    @Test
    fun `回包停顿超时且同一客户端下一请求即时采用新值`() = runBlocking {
        var current = ModelTimeoutSettings(llmSeconds = 1)
        ModelRequestTimeouts.install { current }
        LocalServer { socket ->
            socket.streamHeaders()
            socket.write("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")
            Thread.sleep(1600)
            socket.finishStream()
        }.use { server ->
            val client = OpenAIProtocol(server.url, "test-key", "test")
            val first = withTimeout(5_000) { client.sendPrompt(prompt).toList() }
            assertThat(first.filterIsInstance<StreamChunk.Error>().single().code).isEqualTo(GenerationFailureCode.TIMEOUT)
            current = current.copy(llmSeconds = 3)
            val second = withTimeout(5_000) { client.sendPrompt(prompt).toList() }
            assertThat(second.filterIsInstance<StreamChunk.Error>()).isEmpty()
            assertThat(second.filterIsInstance<StreamChunk.Completed>()).hasSize(1)
        }
    }

    @Test
    fun `非流式响应持续回包时总时长也可超过配置超时`() = runBlocking {
        ModelRequestTimeouts.install { ModelTimeoutSettings(llmSeconds = 1) }
        LocalServer { socket ->
            val body = """{"choices":[{"message":{"content":"hello"},"finish_reason":"stop"}]}"""
            socket.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n")
            body.chunked(8).forEach { Thread.sleep(300); socket.write(it) }
        }.use { server ->
            ModelHttp.client { install(HttpTimeout) { requestTimeoutMillis = 1000 } }.use { transport ->
                val result = withTimeout(6000) {
                    OpenAIProtocol(server.url, "test-key", "test", transport).sendPromptSync(prompt.copy(stream = false))
                }
                assertThat(result.content).isEqualTo("hello")
            }
        }
    }

}

class ModelRequestTimeoutClientsTest : ModelRequestTimeoutFixture() {
    @Test
    fun `生图读取IMAGE且下一请求即时更新`() = runBlocking {
        var current = ModelTimeoutSettings(llmSeconds = 9, imageSeconds = 1, embeddingSeconds = 9, rerankSeconds = 9)
        ModelRequestTimeouts.install { current }
        LocalServer { socket ->
            Thread.sleep(1600)
            socket.json("""{"data":[{"url":"https://images.invalid/result.png"}]}""")
        }.use { server ->
            val client = ImageGenClient(server.url, "test-key", "image")
            val failure = withTimeout(5000) { runCatching { client.generate("test") }.exceptionOrNull() }
            assertTimeout(failure)
            current = current.copy(imageSeconds = 3)
            assertThat(withTimeout(5000) { client.generate("test") }.images.single().url).endsWith("result.png")
        }
    }

    @Test
    fun `向量读取EMBEDDING且下一请求即时更新`() = runBlocking {
        var current = ModelTimeoutSettings(llmSeconds = 9, imageSeconds = 9, embeddingSeconds = 1, rerankSeconds = 9)
        ModelRequestTimeouts.install { current }
        LocalServer { socket ->
            Thread.sleep(1600)
            socket.json("""{"data":[{"embedding":[0.25,0.75]}]}""")
        }.use { server ->
            val client = EmbeddingClient(server.url, "test-key")
            val failure = withTimeout(5000) { runCatching { client.embedQuery("test") }.exceptionOrNull() }
            assertTimeout(failure)
            current = current.copy(embeddingSeconds = 3)
            assertThat(withTimeout(5000) { client.embedQuery("test") }.first.toList()).containsExactly(0.25f, 0.75f).inOrder()
        }
    }

    @Test
    fun `重排读取RERANK并在超时后沿用现有失败回退`() = runBlocking {
        var current = ModelTimeoutSettings(llmSeconds = 9, imageSeconds = 9, embeddingSeconds = 9, rerankSeconds = 1)
        ModelRequestTimeouts.install { current }
        LocalServer { socket ->
            Thread.sleep(1600)
            socket.json("""{"results":[{"index":1,"relevance_score":0.9}]}""")
        }.use { server ->
            val client = RerankClient(server.url, "test-key", "rerank")
            val docs = listOf("a", "b").map { SearchResult(id = it, content = it, similarity = 0.1f, createdAt = 0) }
            assertThat(withTimeout(5000) { client.rerankWithOutcome("test", docs, 1) }.method).isEqualTo(RerankMethod.FAILED)
            current = current.copy(rerankSeconds = 3)
            val result = withTimeout(5000) { client.rerankWithOutcome("test", docs, 1) }
            assertThat(result.method).isEqualTo(RerankMethod.API)
            assertThat(result.results.single().id).isEqualTo("b")
        }
    }

}
