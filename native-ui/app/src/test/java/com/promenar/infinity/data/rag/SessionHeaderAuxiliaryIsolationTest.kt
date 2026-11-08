package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.Companion.BASE
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.Companion.MODEL
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SessionHeaderAuxiliaryIsolationTest {
    @Test fun `启用设置后文档图谱嵌入重排改写连接和目录仍无会话头与客户端标识`() = runBlocking {
        SessionHeaderWiringEndpoint().use { endpoint ->
            val resolved = (endpoint.router.resolve(MODEL) as ProviderResolution.Success).value
            endpoint.reply = { request -> HttpStatusCode.OK to when {
                request.url.encodedPath.endsWith("/embeddings") -> """{"data":[{"index":0,"embedding":[1.0,0.0]}]}"""
                request.url.encodedPath.endsWith("/rerank") -> """{"results":[{"index":1,"relevance_score":0.9}]}"""
                request.url.encodedPath.endsWith("/models") -> """{"data":[{"id":"model"}]}"""
                else -> SessionHeaderWiringEndpoint.sync("{\"nodes\":[],\"edges\":[]}")
            } }
            val kg = KgRequestClient(endpoint.router, { endpoint.protocol })
            assertThat(kg.extract(MODEL, "system", "document", 10_000).isSuccess).isTrue()
            val embedding = EmbeddingClient(BASE, "synthetic-key", "model", httpClient = endpoint.http,
                modelRouteResolver = { resolved })
            assertThat(embedding.embedQuery("query").first.toList()).containsExactly(1f, 0f).inOrder()
            val sources = listOf("first", "second").map { SearchResult(it, content = it, createdAt = 0, similarity = 1f) }
            val rerank = RerankClient(BASE, "synthetic-key", "model", modelRouteResolver = { resolved }, httpClient = endpoint.http)
            assertThat(rerank.rerankWithOutcome("query", sources, 1).method).isEqualTo(RerankMethod.API)
            QueryRewriter(endpoint.protocol, modelId = MODEL, modelRouteResolver = { resolved }, protocolFactory = { endpoint.protocol }).rewrite("query")
            assertThat(ProviderConnectionProbe(endpoint.http).probe(resolved.config)).isInstanceOf(ProviderConnectionProbeResult.Success::class.java)
            assertThat(endpoint.protocol.listModels()).contains("model")
            endpoint.reply = { HttpStatusCode.OK to SessionHeaderWiringEndpoint.sync("[{\"index\":0,\"score\":9}]") }
            val fallback = RerankClient("", "", "", llmProtocol = endpoint.protocol, llmModelId = "model", httpClient = endpoint.http)
            assertThat(fallback.rerankWithOutcome("query", sources, 1).method).isEqualTo(RerankMethod.LLM)
            assertThat(endpoint.requests).hasSize(7)
            endpoint.requests.forEach { request ->
                assertThat(request.headers["x-opencode-session"]).isNull()
                assertThat(request.headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
            }
        }
    }
}
