package com.promenar.infinity.data.mediagen.comfy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** 终端拦截器直接返回响应，全部测试不建立网络连接。 */
class ComfyHttpTest {
    @Test fun multipartUploadsImageAndConfiguredAudioAndUsesSecretAuthentication() = runTest {
        val fixture = ComfyFixture()
        val host = fixture.host.copy(authKind = ComfyAuthKind.BASIC, audioUploadPath = "/upload/audio", audioUploadField = "audio")
        val credentials = ComfyCredentials(fixture.secrets)
        credentials.put(host, ComfyCredential.Basic("user", "pass"))
        val requests = mutableListOf<Pair<Request, String>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer(); request.body!!.writeTo(body)
            requests += request to body.readUtf8()
            response(request, """{"name":"input.wav","subfolder":"infinity","type":"input"}""")
        }.build()
        val http = OkHttpComfyHttp(client, credentials)
        assertEquals("infinity/input.wav", http.upload(host, "ref.png", "image/png", ByteArrayInputStream("data".toByteArray())))
        http.upload(host, "ref.wav", "audio/wav", ByteArrayInputStream("audio-data".toByteArray()), audio = true)
        assertEquals("/upload/image", requests[0].first.url.encodedPath)
        assertEquals("/upload/audio", requests[1].first.url.encodedPath)
        assertTrue(requests[0].second.contains("name=\"image\"; filename=\"ref.png\""))
        assertTrue(requests[1].second.contains("name=\"audio\"; filename=\"ref.wav\""))
        assertTrue(requests[0].second.contains("name=\"overwrite\""))
        assertEquals(Credentials.basic("user", "pass", Charsets.UTF_8), requests[0].first.header("Authorization"))
        assertFalse(host.toString().contains("pass"))
    }
    @Test fun promptIsJsonAndBearerIsNotPlacedInPayload() = runTest {
        val f = ComfyFixture(); val host = f.host.copy(authKind = ComfyAuthKind.BEARER)
        val credentials = ComfyCredentials(f.secrets); credentials.put(host, ComfyCredential.Bearer("test-only"))
        var bodyText = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val buffer = Buffer(); request.body!!.writeTo(buffer); bodyText = buffer.readUtf8()
            assertEquals("Bearer test-only", request.header("Authorization"))
            response(request, """{"prompt_id":"x"}""")
        }.build()
        val payload = buildJsonObject { put("prompt", f.graph); put("client_id", "client") }
        assertEquals("x", OkHttpComfyHttp(client, credentials).post(host, "/prompt", payload)["prompt_id"]!!.jsonPrimitive.content)
        assertEquals(payload, Json.parseToJsonElement(bodyText)); assertFalse(bodyText.contains("test-only"))
    }
    @Test fun downloadStreamsAndRejectsUntrustedViewTargets() = runTest {
        val f = ComfyFixture()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls++; response(chain.request(), "bytes", mime = "image/png") }.build()
        val http = OkHttpComfyHttp(client, ComfyCredentials(f.secrets))
        val path = http.download(f.host, "/view?filename=x.png&type=output") { input, mime ->
            assertEquals("image/png", mime); assertEquals("bytes", input.readBytes().decodeToString()); "takes/x.png"
        }
        assertEquals("takes/x.png", path); assertEquals(1, calls)
        try { http.download(f.host, "http://other/x") { _, _ -> "" }; fail("应拒绝跨主机地址") } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }
    @Test fun httpErrorsAreReadableWithoutEchoingResponseSecrets() = runTest {
        val f = ComfyFixture()
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), "echo-secret", code = 401) }.build()
        try { OkHttpComfyHttp(client, ComfyCredentials(f.secrets)).get(f.host, "/object_info"); fail("应鉴权失败") }
        catch (e: ComfyFailure) { assertTrue(e.message!!.contains("鉴权失败")); assertFalse(e.message!!.contains("echo-secret")) }
    }
    @Test fun timeoutsBecomeReadableRetryableFailures() = runTest {
        val f = ComfyFixture()
        val client = OkHttpClient.Builder().addInterceptor { throw java.net.SocketTimeoutException("internal endpoint details") }.build()
        try { OkHttpComfyHttp(client, ComfyCredentials(f.secrets)).get(f.host, "/object_info"); fail("应超时") }
        catch (e: ComfyFailure) { assertTrue(e.retryable); assertTrue(e.message!!.contains("超时")); assertFalse(e.message!!.contains("internal")) }
    }
    @Test fun slowRelayedTransfersOnlyTimeOutWhenIdle() = runTest {
        // 经中转的大响应只是慢：不设整次调用总时限，只在连续无数据达到主机超时才失败。
        val f = ComfyFixture(); val host = f.host.copy(timeoutSeconds = 45)
        var callTimeout = -1L; var readTimeout = -1; var connectTimeout = -1
        val client = OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
            callTimeout = chain.call().timeout().timeoutNanos()
            readTimeout = chain.readTimeoutMillis(); connectTimeout = chain.connectTimeoutMillis()
            response(chain.request(), "{}")
        }.build()
        OkHttpComfyHttp(client, ComfyCredentials(f.secrets)).get(host, "/object_info")
        assertEquals(0L, callTimeout)
        assertEquals(45_000, readTimeout)
        assertEquals(30_000, connectTimeout)
    }
    @Test fun promptTransportTimeoutRequiresCheckingHostBeforeRetry() = runTest {
        val f = ComfyFixture()
        val client = OkHttpClient.Builder().addInterceptor { throw java.net.SocketTimeoutException("private details") }.build()
        try { OkHttpComfyHttp(client, ComfyCredentials(f.secrets)).post(f.host, "/prompt", JsonObject(emptyMap())); fail("应超时") }
        catch (e: ComfyFailure) { assertFalse(e.retryable); assertTrue(e.message!!.contains("提交结果未知")) }
    }
    private fun response(request: Request, text: String, code: Int = 200, mime: String = "application/json") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
        .header("Content-Type", mime).body(text.toResponseBody(mime.toMediaType())).build()
}
