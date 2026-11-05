package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.data.local.inference.SlotState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddingClientTest {

    @Test
    fun `vector space tracks resolved remote model and endpoint while frozen route remains stable`() {
        fun route(model: String, endpoint: String) = com.promenar.infinity.data.remote.ResolvedProviderModel(
            "stable-id", model, "provider", "提供商",
            com.promenar.infinity.data.remote.UnifiedProviderConfig(
                com.promenar.infinity.data.remote.protocol.ProtocolType.OpenAI_ChatCompletions,
                endpoint, "fake-key", model,
            ),
        )
        var current = route("model-a", "https://example.invalid")
        val client = EmbeddingClient("", "", modelRouteResolver = { current }, allowLocalFallback = false)
        val frozen = client.frozen()
        val firstKey = frozen.vectorSpaceKey()
        current = route("model-b", "https://example.invalid")
        assertThat(client.vectorSpaceKey()).isNotEqualTo(firstKey)
        assertThat(frozen.vectorSpaceKey()).isEqualTo(firstKey)
        current = route("model-a", "https://other.invalid")
        assertThat(client.vectorSpaceKey()).isNotEqualTo(firstKey)
    }

    @Test
    fun `local vector space follows actual loaded slot`() {
        val local = mockk<LocalInferenceEngine>()
        val slot = MutableStateFlow(SlotState(modelPath = "/example/model-a", isLoaded = true))
        every { local.embeddingSlot } returns slot
        val route = com.promenar.infinity.data.remote.ResolvedProviderModel(
            "local-id", "local", "local", "本地",
            com.promenar.infinity.data.remote.UnifiedProviderConfig(
                com.promenar.infinity.data.remote.protocol.ProtocolType.Local, "", "", "local",
            ),
        )
        val client = EmbeddingClient("", "", localEngine = local, modelRouteResolver = { route })
        val first = client.vectorSpaceKey()
        slot.value = SlotState(modelPath = "/example/model-b", isLoaded = true)
        assertThat(client.vectorSpaceKey()).isNotEqualTo(first)
        slot.value = SlotState()
        assertThat(client.vectorSpaceKey()).isNull()
    }

    @Test
    fun strictEmbeddingDoesNotFallBackToAnotherVectorSpace() = runTest {
        val local = mockk<LocalInferenceEngine>()
        every { local.embeddingSlot } returns MutableStateFlow(SlotState(isLoaded = true))
        coEvery { local.embedBatch(any()) } returns Result.success(listOf(floatArrayOf(1f)))
        val transport = HttpClient(MockEngine { respond("{}", HttpStatusCode.InternalServerError) })
        val client = EmbeddingClient("https://embedding.example.invalid", "fake-key", "embedding-a",
            httpClient = transport, localEngine = local, allowLocalFallback = false)

        assertThrows<IllegalStateException> { client.embedQuery("问题") }
        coVerify(exactly = 0) { local.embedBatch(any()) }
        transport.close()
    }

    // ── 构造与配置 ────────────────────────────────────────────────

    @Test
    fun `constructor accepts all parameters`() {
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "text-embedding-3-small"
        )
        assertThat(client).isNotNull()
    }

    @Test
    fun `constructor uses default model when not specified`() {
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test"
        )
        assertThat(client).isNotNull()
    }

    @Test
    fun `embedBatch throws when baseUrl is blank`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""{"data":[],"usage":{"total_tokens":0}}""")
        }
        val client = EmbeddingClient(
            baseUrl = "",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val exception = assertThrows<IllegalStateException> {
            client.embedDocuments(listOf("hello"))
        }
        assertThat(exception.message).contains("未配置")
    }

    @Test
    fun `embedBatch throws when apiKey is blank`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""{"data":[],"usage":{"total_tokens":0}}""")
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val exception = assertThrows<IllegalStateException> {
            client.embedDocuments(listOf("hello"))
        }
        assertThat(exception.message).contains("API Key 未配置")
    }

    @Test
    fun `embedBatch throws when texts list is empty`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""{"data":[],"usage":{"total_tokens":0}}""")
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val exception = assertThrows<IllegalArgumentException> {
            client.embedDocuments(emptyList())
        }
        assertThat(exception.message).contains("No texts")
    }

    // ── URL 构建逻辑 ──────────────────────────────────────────────

    @Test
    fun `embedBatch appends v1-embeddings to non-v1 baseUrl`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respondOk(buildEmbeddingResponse(768, 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        client.embedDocuments(listOf("hello"))

        assertThat(capturedUrl).isEqualTo("https://api.example.com/v1/embeddings")
    }

    @Test
    fun `embedBatch uses v1-embeddings when baseUrl already ends with v1`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respondOk(buildEmbeddingResponse(768, 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        client.embedDocuments(listOf("hello"))

        assertThat(capturedUrl).isEqualTo("https://api.example.com/v1/embeddings")
    }

    @Test
    fun `embedBatch strips trailing slash from baseUrl`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respondOk(buildEmbeddingResponse(768, 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com/",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        client.embedDocuments(listOf("hello"))

        assertThat(capturedUrl).isEqualTo("https://api.example.com/v1/embeddings")
    }

    @Test
    fun `embedBatch includes Authorization header`() = runTest {
        var capturedHeaders: Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respondOk(buildEmbeddingResponse(768, 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-my-key-123",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        client.embedDocuments(listOf("hello"))

        val authHeader = capturedHeaders?.get("Authorization")
        assertThat(authHeader).isEqualTo("Bearer sk-my-key-123")
    }

    @Test
    fun `embedBatch sends correct content type`() = runTest {
        var capturedContentType: String? = null
        val mockEngine = MockEngine { request ->
            capturedContentType = request.body.contentType?.toString()
            respondOk(buildEmbeddingResponse(768, 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        client.embedDocuments(listOf("hello"))

        assertThat(capturedContentType).isNotNull()
        assertThat(capturedContentType).contains("application/json")
    }

    // ── 响应解析 ──────────────────────────────────────────────────

    @Test
    fun `embedDocuments parses single text response`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk(buildEmbeddingResponse(dimensions = 3, count = 1))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val result = client.embedDocuments(listOf("hello"))

        assertThat(result.embeddings).hasSize(1)
        assertThat(result.embeddings[0]).hasLength(3)
        assertThat(result.embeddings[0][0]).isEqualTo(0.1f)
        assertThat(result.embeddings[0][1]).isEqualTo(0.2f)
        assertThat(result.embeddings[0][2]).isEqualTo(0.3f)
    }

    @Test
    fun `embedDocuments parses multiple texts response`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk(buildEmbeddingResponse(dimensions = 2, count = 3))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val result = client.embedDocuments(listOf("a", "b", "c"))

        assertThat(result.embeddings).hasSize(3)
        result.embeddings.forEach { embedding ->
            assertThat(embedding).hasLength(2)
        }
    }

    @Test
    fun `embedDocuments returns usage info when present`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""
            {
                "object": "list",
                "data": [
                    {"object": "embedding", "index": 0, "embedding": [0.1, 0.2]},
                    {"object": "embedding", "index": 1, "embedding": [0.3, 0.4]}
                ],
                "model": "test-model",
                "usage": {"prompt_tokens": 5, "total_tokens": 5}
            }
            """.trimIndent())
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val result = client.embedDocuments(listOf("a", "b"))

        assertThat(result.usage).isNotNull()
        assertThat(result.usage?.totalTokens).isEqualTo(5)
    }

    @Test
    fun `embedDocuments returns null usage when absent`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk(buildEmbeddingResponse(dimensions = 2, count = 1))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val result = client.embedDocuments(listOf("a"))

        assertThat(result.usage).isNull()
    }

    @Test
    fun `embedDocuments throws on missing data array`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""{"object":"list","model":"test-model"}""")
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val exception = assertThrows<IllegalStateException> {
            client.embedDocuments(listOf("hello"))
        }
        assertThat(exception.message).contains("Missing data array")
    }

    @Test
    fun `embedDocuments throws on missing embedding field`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""{"data":[{"object":"embedding","index":0}]}""")
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val exception = assertThrows<IllegalStateException> {
            client.embedDocuments(listOf("hello"))
        }
        assertThat(exception.message).contains("Missing embedding")
    }

    // ── embedQuery ────────────────────────────────────────────────

    @Test
    fun `embedQuery returns single embedding and usage`() = runTest {
        val mockEngine = MockEngine { _ ->
            respondOk("""
            {
                "data": [{"embedding": [0.5, 0.6, 0.7]}],
                "usage": {"total_tokens": 2}
            }
            """.trimIndent())
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val (embedding, usage) = client.embedQuery("test query")

        assertThat(embedding).hasLength(3)
        assertThat(embedding[0]).isEqualTo(0.5f)
        assertThat(usage?.totalTokens).isEqualTo(2)
    }

    // ── 大型批量请求分片 ──────────────────────────────────────────

    @Test
    fun `embedDocuments splits large batch into chunks of 50`() = runTest {
        var requestCount = 0
        // 记录每个请求的实际批量大小
        val batchSizes = mutableListOf<Int>()
        val mockEngine = MockEngine { request ->
            requestCount++
            // 每个请求的 text 数量由 embedViaRemote 决定 (≤50)
            // 用递增计数器模拟：r1=50, r2=50, r3=20
            val countPerBatch = when (batchSizes.size) {
                0 -> 50
                1 -> 50
                else -> 20
            }
            batchSizes.add(countPerBatch)
            respondOk(buildEmbeddingResponse(dimensions = 2, count = countPerBatch))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        // 120 texts should result in 3 requests (50 + 50 + 20)
        val texts = (1..120).map { "text $it" }
        val result = client.embedDocuments(texts)

        assertThat(requestCount).isEqualTo(3)
        assertThat(result.embeddings).hasSize(120)
    }

    @Test
    fun `embedDocuments batch of exactly 50 makes one request`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respondOk(buildEmbeddingResponse(dimensions = 2, count = 50))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val texts = (1..50).map { "text $it" }
        val result = client.embedDocuments(texts)

        assertThat(requestCount).isEqualTo(1)
        assertThat(result.embeddings).hasSize(50)
    }

    // ── 本地引擎回退逻辑（通过包装测试） ──────────────────────────

    @Test
    fun `embedLocal throws when localEngine is null`() = runTest {
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            localEngine = null
        )

        val result = client.embedLocal("test")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("Local engine")
    }

    @Test
    fun `embedLocalBatch throws when localEngine is null`() = runTest {
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            localEngine = null
        )

        val result = client.embedLocalBatch(listOf("a", "b"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    private fun buildEmbeddingResponse(dimensions: Int, count: Int): String {
        val dataItems = (0 until count).joinToString(",") { i ->
            val values = (0 until dimensions).joinToString(",") { d ->
                "%.1f".format((i * dimensions + d + 1) * 0.1f)
            }
            """{"object":"embedding","index":$i,"embedding":[$values]}"""
        }
        return """{"object":"list","data":[$dataItems],"model":"test-model"}"""
    }

    private fun MockRequestHandleScope.respondOk(body: String) =
        respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )
}
