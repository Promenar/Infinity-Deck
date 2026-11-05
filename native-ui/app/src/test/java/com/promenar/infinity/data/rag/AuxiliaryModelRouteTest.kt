package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.*
import com.promenar.infinity.data.remote.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class AuxiliaryModelRouteTest {
    private val remote = "org/model::revision:1/preview"
    private val models = mutableMapOf<String, ModelInfo>()
    private val providers = mutableMapOf<String, ProviderListItem>()
    private val configs = mutableMapOf<String, ProviderConfig>()
    private val requests = mutableListOf<HttpRequestData>()
    private val router = DefaultProviderRequestRouter(models::get, providers::get, configs::get)
    private var selected = seed("extra")

    private fun seed(provider: String): String {
        val id = stableModelId(provider, remote)
        models[id] = ModelInfo(id = id, remoteModelId = remote, providerId = provider, name = remote, description = "", enabled = true)
        providers[provider] = ProviderListItem(id = provider, name = provider, protocolType = ProtocolType.OpenAI_ChatCompletions)
        configs[provider] = ProviderConfig(protocolType = ProtocolType.OpenAI_ChatCompletions, baseUrl = "https://$provider.test/v1", apiKey = "fake-$provider-key")
        return id
    }

    private fun http(body: String) = HttpClient(MockEngine { request ->
        requests += request
        respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    })

    private fun route() = router.requireModelRoute(selected)
    private fun assertRequest(index: Int, provider: String, path: String) {
        val request = requests[index]
        assertThat(request.url.toString()).isEqualTo("https://$provider.test/v1/$path")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer fake-$provider-key")
        assertThat(Json.parseToJsonElement((request.body as TextContent).text).jsonObject["model"]?.jsonPrimitive?.content).isEqualTo(remote)
    }

    private suspend fun assertRouteFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("失效模型必须明确失败")
        } catch (failure: ModelRequestRouteException) {
            assertThat(failure.reason).isEqualTo(ProviderResolutionError.PROVIDER_DISABLED)
        }
        assertThat(requests).isEmpty()
    }

    @Test fun `Embedding每次解析预设且不混用历史手动配置`() = runTest {
        val http = http("""{"data":[{"embedding":[1.0,2.0]}]}""")
        try {
            val client = EmbeddingClient("https://manual.test", "fake-manual-key", "manual-model", http, modelRouteResolver = ::route)
            client.embedDocuments(listOf("a"))
            selected = seed("default")
            client.embedQuery("b")
            assertRequest(0, "extra", "embeddings")
            assertRequest(1, "default", "embeddings")
        } finally { http.close() }
    }

    @Test fun `Embedding路由失效不发请求也不触发本地降级`() = runTest {
        providers["extra"] = providers.getValue("extra").copy(enabled = false)
        val http = http("{}")
        try {
            val client = EmbeddingClient("https://manual.test", "fake-manual-key", "manual", http, localEngine = mockk(), modelRouteResolver = ::route)
            assertThat(client.isConfigured).isFalse()
            assertThat(client.hasLocalFallback).isFalse()
            assertThat(client.diagnosticMessage()).contains("PROVIDER_DISABLED")
            assertRouteFailure { client.embedQuery("a") }
            assertRouteFailure { client.embedDocuments(listOf("a")) }
        } finally { http.close() }
    }

    @Test fun `Embedding批次共享一次解析快照`() = runTest {
        var resolutions = 0
        // 每请求至多 16 个切块：17 条拆成 16 + 1 两批，响应条数与请求一致。
        val http = HttpClient(MockEngine { request ->
            requests += request
            val count = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["input"]!!.jsonArray.size
            respond("""{"data":[${List(count) { """{"embedding":[1.0]}""" }.joinToString(",")}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        try {
            val client = EmbeddingClient("", "", httpClient = http, modelRouteResolver = { resolutions++; route() })
            client.embedDocuments(List(17) { "text-$it" })
            assertThat(resolutions).isEqualTo(1)
            assertThat(requests).hasSize(2)
            assertRequest(0, "extra", "embeddings")
            assertRequest(1, "extra", "embeddings")
        } finally { http.close() }
    }

    private val docs = listOf(SearchResult(id = "a", content = "甲", createdAt = 0L, similarity = 0.5f), SearchResult(id = "b", content = "乙", createdAt = 0L, similarity = 0.4f))

    @Test fun `Rerank预设使用所属提供商且保留远端特殊字符`() = runTest {
        val http = http("""{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.1}]}""")
        try {
            val client = RerankClient("https://manual.test", "fake-manual-key", "manual", httpClient = http, modelRouteResolver = ::route)
            assertThat(client.rerankWithOutcome("query", docs, 2).method).isEqualTo(RerankMethod.API)
            assertRequest(0, "extra", "rerank")
        } finally { http.close() }
    }

    @Test fun `Rerank预设失效不回退主提供商LLM`() = runTest {
        providers["extra"] = providers.getValue("extra").copy(enabled = false)
        val http = http("{}")
        try {
            val client = RerankClient("https://manual.test", "fake-manual-key", "manual", llmProtocol = mockk(), llmModelId = "main", httpClient = http, modelRouteResolver = ::route)
            assertRouteFailure { client.rerankWithOutcome("query", docs, 2) }
        } finally { http.close() }
    }

    @Test fun `Rerank预设上游失败也不会调用主提供商LLM`() = runTest {
        val http = HttpClient(MockEngine { request -> requests += request; respond("{}", HttpStatusCode.ServiceUnavailable) })
        val mainProtocol = mockk<LlmProtocol>()
        try {
            val client = RerankClient("", "", "", llmProtocol = mainProtocol, llmModelId = "main", httpClient = http, modelRouteResolver = ::route)
            assertThat(client.rerankWithOutcome("query", docs, 2).method).isEqualTo(RerankMethod.FAILED)
            assertThat(requests).hasSize(1)
            assertRequest(0, "extra", "rerank")
            coVerify(exactly = 0) { mainProtocol.sendPromptSync(any()) }
        } finally { http.close() }
    }

    @Test fun `查询改写按覆盖模型解析且同名模型不串提供商`() = runTest {
        val http = http("""{"choices":[{"message":{"content":"另一种问法"}}]}""")
        val main = seed("default")
        try {
            val rewriter = QueryRewriter(mockk(), modelId = main, modelRouteResolver = { router.requireModelRoute(it.orEmpty()) }, protocolFactory = { route ->
                OpenAIProtocol(route.config.baseUrl, route.config.apiKey, route.remoteModelId, httpClient = http)
            })
            rewriter.rewrite("query", modelIdOverride = selected)
            rewriter.rewrite("query")
            assertRequest(0, "extra", "chat/completions")
            assertRequest(1, "default", "chat/completions")
        } finally { http.close() }
    }

    @Test fun `查询改写路由失败向调用方报告且不请求固定主协议`() = runTest {
        providers["extra"] = providers.getValue("extra").copy(enabled = false)
        val rewriter = QueryRewriter(mockk(), modelId = selected, modelRouteResolver = { router.requireModelRoute(it.orEmpty()) })
        assertRouteFailure { rewriter.rewrite("query") }
    }
}
