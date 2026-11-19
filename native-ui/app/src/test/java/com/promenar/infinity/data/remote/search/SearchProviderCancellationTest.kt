package com.promenar.infinity.data.remote.search

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchProviderCancellationTest {
    @Test
    fun `DuckDuckGo request cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("duck-request-cancelled")
        val provider = DuckDuckGoProvider(cancellingRequestClient(cancellation))

        assertCancellationPreserved(cancellation) { provider.search("infinity") }
    }

    @Test
    fun `DuckDuckGo response body cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("duck-body-cancelled")
        val provider = DuckDuckGoProvider(cancellingBodyClient(cancellation, "text/html"))

        assertOriginalCancellation(cancellation) { provider.search("infinity") }
    }

    @Test
    fun `SearXNG request cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("searx-request-cancelled")
        val provider = SearXNGProvider(cancellingRequestClient(cancellation))

        assertCancellationPreserved(cancellation) { provider.search("infinity") }
    }

    @Test
    fun `SearXNG response body cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("searx-body-cancelled")
        val provider = SearXNGProvider(cancellingBodyClient(cancellation, "application/json"))

        assertOriginalCancellation(cancellation) { provider.search("infinity") }
    }

    @Test
    fun `Tavily request cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("tavily-request-cancelled")
        val provider = TavilyProvider(cancellingRequestClient(cancellation), apiKey = "test-key")

        assertCancellationPreserved(cancellation) { provider.search("infinity") }
    }

    @Test
    fun `Tavily response body cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("tavily-body-cancelled")
        val provider = TavilyProvider(
            cancellingBodyClient(cancellation, "application/json"),
            apiKey = "test-key",
        )

        assertOriginalCancellation(cancellation) { provider.search("infinity") }
    }

    private fun cancellingRequestClient(cancellation: CancellationException): HttpClient =
        HttpClient(MockEngine { throw cancellation })

    private fun cancellingBodyClient(
        cancellation: CancellationException,
        contentType: String,
    ): HttpClient {
        val body = object : ByteReadChannel by ByteReadChannel(ByteArray(0)) {
            override val isClosedForRead = false
            @OptIn(io.ktor.utils.io.InternalAPI::class)
            override val readBuffer: kotlinx.io.Source get() = throw cancellation
        }
        return HttpClient(MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        })
    }

    private suspend fun assertOriginalCancellation(
        expected: CancellationException,
        block: suspend () -> Unit,
    ) {
        val caught = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(caught).isSameInstanceAs(expected)
    }

    private suspend fun assertCancellationPreserved(
        expected: CancellationException,
        block: suspend () -> Unit,
    ) {
        val caught = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught?.message).isEqualTo(expected.message)
        assertThat(generateSequence(caught) { it.cause }.any { it === expected }).isTrue()
    }
}
