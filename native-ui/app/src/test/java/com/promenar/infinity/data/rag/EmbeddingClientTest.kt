package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.remote.ModelRequestTimeouts
import com.promenar.infinity.data.remote.ModelTimeoutSettings
import io.ktor.client.plugins.HttpTimeout
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

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
            respondOk(buildEmbeddingResponse(768, 1))
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
            respondOk(buildEmbeddingResponse(768, 1))
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
            respondOk(buildEmbeddingResponse(768, 1))
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
            respondOk(buildEmbeddingResponse(768, 1))
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
            respondOk(buildEmbeddingResponse(768, 1))
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
    fun `乱序响应按 index 归位并拒绝重复或不完整索引`() = runTest {
        var duplicate = false
        val transport = HttpClient(MockEngine {
            val secondIndex = if (duplicate) 1 else 0
            respondOk("""{"data":[{"index":1,"embedding":[2.0]},{"index":$secondIndex,"embedding":[1.0]}]}""")
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            assertThat(client.embedDocuments(listOf("第一条", "第二条")).embeddings.map { it.single() })
                .containsExactly(1f, 2f).inOrder()
            duplicate = true
            assertThrows<IllegalStateException> { client.embedDocuments(listOf("第一条", "第二条")) }
        } finally { transport.close() }
    }

    @Test
    fun `错误回显短正文片段时只展示受控原因`() = runTest {
        val transport = HttpClient(MockEngine {
            respond("""{"error":{"message":"invalid text: 祖母"}}""", HttpStatusCode.BadRequest)
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            val error = assertThrows<EmbeddingBatchException> {
                client.embedDocuments(listOf("这是一段包含祖母信息的私密文档"))
            }
            assertThat(error.message).contains("HTTP 400")
            assertThat(error.message).doesNotContain("祖母")
        } finally { transport.close() }
    }

    @Test
    fun `默认请求 base64 并按小端 float32 解析`() = runTest {
        val formats = mutableListOf<String?>()
        val transport = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            formats += body["encoding_format"]?.jsonPrimitive?.content
            respondOk("""{"data":[{"embedding":"AACAPwAAIMAAAAA+"}]}""")
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            val vector = client.embedDocuments(listOf("正文")).embeddings.single()
            assertThat(formats).containsExactly("base64")
            assertThat(vector.toList()).containsExactly(1f, -2.5f, 0.125f).inOrder()
        } finally { transport.close() }
    }

    @Test
    fun `上游拒绝 encoding_format 后无字段重发且后续批次沿用回退`() = runTest {
        val formats = mutableListOf<String?>()
        val transport = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            formats += body["encoding_format"]?.jsonPrimitive?.content
            if (formats.size == 1) {
                respond("""{"error":{"message":"Unsupported parameter: encoding_format"}}""", HttpStatusCode.BadRequest)
            } else respondOk(buildEmbeddingResponse(2, body.getValue("input").jsonArray.size))
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            assertThat(client.embedDocuments(List(17) { "正文$it" }).embeddings).hasSize(17)
            assertThat(formats).containsExactly("base64", null, null).inOrder()
        } finally { transport.close() }
    }

    @Test
    fun `错误字段指向不支持的编码参数时回退`() = runTest {
        var calls = 0
        val transport = HttpClient(MockEngine { request ->
            calls++
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            if (calls == 1) {
                respond("""{"error":{"param":"encoding_format","message":"This parameter is not supported"}}""", HttpStatusCode.BadRequest)
            } else {
                assertThat(body.containsKey("encoding_format")).isFalse()
                respondOk(buildEmbeddingResponse(2, 1))
            }
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            assertThat(client.embedDocuments(listOf("正文")).embeddings).hasSize(1)
            assertThat(calls).isEqualTo(2)
        } finally { transport.close() }
    }

    @Test
    fun `上游忽略编码参数返回数字数组时直接解析并记住回退`() = runTest {
        val formats = mutableListOf<String?>()
        val transport = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            formats += body["encoding_format"]?.jsonPrimitive?.content
            respondOk(buildEmbeddingResponse(2, body.getValue("input").jsonArray.size))
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            val result = client.embedDocuments(List(17) { "正文$it" })
            assertThat(result.embeddings.first().toList()).containsExactly(0.1f, 0.2f).inOrder()
            assertThat(formats).containsExactly("base64", null).inOrder()
        } finally { transport.close() }
    }

    @Test
    fun `动态解析的同一客户端记住当前模型的编码回退`() = runTest {
        val formats = mutableListOf<String?>()
        val transport = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            formats += body["encoding_format"]?.jsonPrimitive?.content
            respondOk(buildEmbeddingResponse(2, 1))
        })
        try {
            val client = EmbeddingClient("", "", httpClient = transport, clientResolver = { http ->
                EmbeddingClient("https://api.example.com", "fake-key", "test-model", http,
                    allowLocalFallback = false)
            })
            client.frozenForIndex().embedDocuments(listOf("第一份"))
            client.frozenForIndex().embedDocuments(listOf("第二份"))
            assertThat(formats).containsExactly("base64", null).inOrder()
        } finally { transport.close() }
    }

    @Test
    fun `编码回退按模型隔离`() = runTest {
        val formats = mutableListOf<String?>()
        val transport = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            formats += body["encoding_format"]?.jsonPrimitive?.content
            respondOk(buildEmbeddingResponse(2, 1))
        })
        try {
            var model = "model-a"
            val client = EmbeddingClient("", "", httpClient = transport, clientResolver = { http ->
                EmbeddingClient("https://api.example.com", "fake-key", model, http, allowLocalFallback = false)
            })
            client.embedDocuments(listOf("第一份"))
            model = "model-b"
            client.embedDocuments(listOf("第二份"))
            model = "model-a"
            client.embedDocuments(listOf("第三份"))
            assertThat(formats).containsExactly("base64", "base64", null).inOrder()
        } finally { transport.close() }
    }

    @Test
    fun `响应体读取超时作为可重试批次错误且不泄露底层信息`() = runTest {
        val transport = HttpClient(MockEngine {
            val channel = ByteChannel(autoFlush = true)
            channel.close(java.net.SocketTimeoutException("fake-secret-transport-detail"))
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            val error = assertThrows<EmbeddingTimeoutException> { client.embedDocuments(listOf("正文")) }
            assertThat(error.retryable).isTrue()
            assertThat(error.message).doesNotContain("fake-secret-transport-detail")
        } finally { transport.close() }
    }

    @Test
    fun `上游回显超长密钥时摘要不暴露密钥片段`() = runTest {
        val secret = "s".repeat(700)
        val transport = HttpClient(MockEngine {
            respond("""{"error":{"message":"$secret"}}""", HttpStatusCode.BadRequest)
        })
        try {
            val client = EmbeddingClient("https://api.example.com", secret, "test-model", transport,
                allowLocalFallback = false)
            val error = assertThrows<EmbeddingBatchException> { client.embedDocuments(listOf("正文")) }
            assertThat(error.message).doesNotContain("s".repeat(16))
        } finally { transport.close() }
    }

    @Test
    fun `文档请求使用可配置空闲超时和放宽总时长查询保留原超时`() = runTest {
        val values = mutableListOf<Pair<Long?, Long?>>()
        val transport = HttpClient(MockEngine { request ->
            val timeout = checkNotNull(request.getCapabilityOrNull(HttpTimeout))
            values += timeout.socketTimeoutMillis to timeout.requestTimeoutMillis
            respondOk(buildEmbeddingResponse(2, 1))
        })
        try {
            var seconds = 30
            ModelRequestTimeouts.install { ModelTimeoutSettings(embeddingSeconds = seconds) }
            val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
                allowLocalFallback = false)
            client.embedDocuments(listOf("正文"))
            seconds = 60
            client.embedDocuments(listOf("正文"))
            client.embedQuery("问题")
            assertThat(values).containsExactly(
                30_000L to 120_000L,
                60_000L to 240_000L,
                60_000L to HttpTimeout.INFINITE_TIMEOUT_MS,
            ).inOrder()
        } finally {
            ModelRequestTimeouts.install { ModelTimeoutSettings() }
            transport.close()
        }
    }

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
    fun `embedDocuments 按请求实际内容分批且每批最多十六条`() = runTest {
        var requestCount = 0
        val batchSizes = mutableListOf<Int>()
        val mockEngine = MockEngine { request ->
            requestCount++
            val body = (request.body as TextContent).text
            val countPerBatch = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.size
            batchSizes.add(countPerBatch)
            respondOk(buildEmbeddingResponse(dimensions = 2, count = countPerBatch))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val texts = (1..120).map { "text $it" }
        val result = client.embedDocuments(texts)

        assertThat(requestCount).isEqualTo(8)
        assertThat(batchSizes).containsExactly(16, 16, 16, 16, 16, 16, 16, 8).inOrder()
        assertThat(result.embeddings).hasSize(120)
    }

    @Test
    fun `embedDocuments 十六条短文本只发送一个请求`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respondOk(buildEmbeddingResponse(dimensions = 2, count = 16))
        }
        val client = EmbeddingClient(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            model = "test-model",
            httpClient = HttpClient(mockEngine)
        )

        val texts = (1..16).map { "text $it" }
        val result = client.embedDocuments(texts)

        assertThat(requestCount).isEqualTo(1)
        assertThat(result.embeddings).hasSize(16)
    }

    @Test
    fun `不可重试 HTTP 错误显示状态和脱敏摘要`() = runTest {
        var calls = 0
        val secret = "fake-secret-key"
        val mockEngine = MockEngine {
            calls++
            respond("""{"error":{"message":"输入过长，凭据 $secret"}}""", HttpStatusCode.BadRequest)
        }
        val client = EmbeddingClient("https://api.example.com", secret, "test-model", HttpClient(mockEngine),
            allowLocalFallback = false)
        val error = assertThrows<EmbeddingBatchException> { client.embedDocuments(listOf("一份资料正文")) }
        assertThat(calls).isEqualTo(1)
        assertThat(error.message).contains("HTTP 400")
        assertThat(error.message).contains("输入过长")
        assertThat(error.message).doesNotContain(secret)
        assertThat(error.message).doesNotContain("一份资料正文")
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `HTTP 第二区段失败后恢复仅请求未完成区段`(@TempDir root: Path) = runTest {
        val texts = (0 until 35).map { "item-$it" }
        val requests = mutableListOf<String>()
        var fail = true
        val transport = HttpClient(MockEngine { request ->
            val body = (request.body as TextContent).text
            val input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            val first = input.first().jsonPrimitive.content
            requests += first
            if (first == texts[16] && fail) {
                respond("""{"error":{"message":"服务繁忙"}}""", HttpStatusCode.ServiceUnavailable)
            } else respondOk(buildEmbeddingResponse(dimensions = 2, count = input.size))
        })
        val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
            allowLocalFallback = false)
        fun runner() = CheckpointedEmbeddingRunner(EmbeddingCheckpoints(root.toFile()), retryDelay = {}) { batch ->
            client.embedDocuments(batch).embeddings
        }
        assertThrows<EmbeddingBatchException> {
            runner().embed("workspace", "file", EmbeddingCheckpointIdentity("hash", "model", 800, 100), texts)
        }
        fail = false
        val result = runner().embed("workspace", "file", EmbeddingCheckpointIdentity("hash", "model", 800, 100), texts)
        assertThat(result).hasSize(35)
        assertThat(requests.count { it == texts[0] }).isEqualTo(1)
        assertThat(requests.count { it == texts[16] }).isEqualTo(5)
        assertThat(requests.count { it == texts[32] }).isEqualTo(1)
        transport.close()
    }

    @Test
    fun `HTTP 限流返回的 Retry-After 进入批次重试策略`() = runTest {
        val transport = HttpClient(MockEngine {
            respond("""{"error":{"message":"服务限流"}}""", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "7"))
        })
        val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
            allowLocalFallback = false)
        val error = assertThrows<EmbeddingBatchException> { client.embedDocuments(listOf("正文")) }
        assertThat(error.httpStatus).isEqualTo(429)
        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterMillis).isEqualTo(7_000L)
        transport.close()
    }

    @Test
    fun `上游错误回显输入片段时隐藏正文`() = runTest {
        val transport = HttpClient(MockEngine {
            respond("""{"error":{"message":"输入有误：project-private"}}""", HttpStatusCode.BadRequest)
        })
        val client = EmbeddingClient("https://api.example.com", "fake-key", "test-model", transport,
            allowLocalFallback = false)
        val error = assertThrows<EmbeddingBatchException> {
            client.embedDocuments(listOf("project-private-document-body"))
        }
        assertThat(error.message).contains("HTTP 400")
        assertThat(error.message).doesNotContain("project-private")
        transport.close()
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
