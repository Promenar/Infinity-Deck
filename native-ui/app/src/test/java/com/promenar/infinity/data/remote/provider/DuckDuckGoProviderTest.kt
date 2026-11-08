package com.promenar.infinity.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.search.DuckDuckGoProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DuckDuckGoProviderTest {

    @Test
    fun `搜索使用固定HTML夹具解析且不访问真实网络`() = runBlocking {
        val fixtureStream = javaClass.getResourceAsStream("/search/duckduckgo-success.html")
        assertThat(fixtureStream).isNotNull()
        val fixture = requireNotNull(fixtureStream).bufferedReader().use { it.readText() }
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount += 1
            assertThat(request.url.host).isEqualTo("html.duckduckgo.com")
            assertThat(request.url.encodedPath).isEqualTo("/html/")
            assertThat(request.url.parameters["q"]).isEqualTo("Infinity Deck release")
            respond(
                content = fixture,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        }
        val client = HttpClient(engine)

        try {
            val (context, citations) = DuckDuckGoProvider(
                httpClient = client,
                maxResults = 2,
            ).search("Infinity Deck release")

            assertThat(requestCount).isEqualTo(1)
            assertThat(citations).hasSize(2)
            assertThat(citations.map { it.title }).containsExactly(
                "Infinity Deck release notes",
                "Infinity Deck documentation",
            ).inOrder()
            assertThat(citations.map { it.url }).containsExactly(
                "https://example.test/infinity/releases",
                "https://docs.example.test/infinity",
            ).inOrder()
            assertThat(citations.all { it.source == "DuckDuckGo" }).isTrue()
            assertThat(context).contains("[1] Infinity Deck release notes")
            assertThat(context).contains("[2] Infinity Deck documentation")
        } finally {
            client.close()
        }
    }
}
