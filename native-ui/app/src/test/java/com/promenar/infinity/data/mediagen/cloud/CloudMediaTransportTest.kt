package com.promenar.infinity.data.mediagen.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudMediaTransportTest {
    @Test fun reusesInjectedHttpAndPreservesAuthenticationAndPaths() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer fake-for-test", request.headers[HttpHeaders.Authorization])
            assertEquals("/v2/video_generation", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond("""{"task_id":"t"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        try {
            val result = KtorCloudMediaHttp({ "fake-for-test" }, client).request("p", "https://api.test/v2/video_generation", buildJsonObject { put("model", "user-model") })
            assertEquals("t", result.getValue("task_id").jsonPrimitive.content)
        } finally { client.close(); engine.close() }
        assertEquals("https://ark.test/api/v3/contents/generations/tasks", cloudApiUrl("https://ark.test/api/v3", "/api/v3/contents/generations/tasks"))
        assertEquals("https://api.test/v2/video_generation", cloudApiUrl("https://api.test/v1", "/v2/video_generation"))
        assertEquals("https://api.test/v2/video_generation", cloudApiUrl("https://api.test/v1/chat/completions", "/v2/video_generation"))
        assertEquals("https://api.test/v1/files/retrieve?file_id=1", cloudApiUrl("https://api.test/v1", "/v1/files/retrieve?file_id=1"))
    }
    @Test fun rejectsRedirectsAndDoesNotExposeErrorBody() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("sensitive-reflection", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test/"))
        }
        val client = HttpClient(engine)
        try {
            try { KtorCloudMediaHttp({ "fake" }, client).request("p", "https://api.test/v1", null); fail() }
            catch (e: CloudHttpException) { assertEquals(302, e.status); assertFalse(e.message!!.contains("sensitive")) }
            assertEquals(1, calls)
        } finally { client.close(); engine.close() }
    }
    @Test fun treatsBusinessFailureOnHttp200AsFailure() = runTest {
        val engine = MockEngine { respond("""{"base_resp":{"status_code":1002,"status_msg":"private"}}""") }
        val client = HttpClient(engine)
        try {
            try { KtorCloudMediaHttp({ "fake" }, client).request("p", "https://api.test/v1", null); fail() }
            catch (e: CloudApiException) { assertEquals(1002, e.code); assertFalse(e.message!!.contains("private")) }
        } finally { client.close(); engine.close() }
    }
}
