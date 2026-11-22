package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

class ComfyLocalAuthProxyTest {
    private class MapSecrets : SecretStore {
        private val map = linkedMapOf<String, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { map[id.value] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = map[id.value]?.copyOf()
        override fun remove(id: SecretId) { map.remove(id.value) }
        override fun contains(id: SecretId): Boolean = id.value in map
    }

    private data class CapturedRequest(
        val pathWithQuery: String,
        val authorization: String?,
        val bodyUtf8: String,
    )

    @Test fun injectsBearerCredentialStripsSpoofedAuthAndRejectsWrongNonce() = runBlocking {
        val captured = ConcurrentLinkedQueue<CapturedRequest>()
        val responses = ConcurrentLinkedQueue<String>().apply {
            add("""{"system":{"comfyui_version":"0.41.3"}}""")
            add("""{"name":"ref.png","subfolder":"sub","type":"input"}""")
            add("PNG_BYTES_OK")
        }
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val rawQuery = exchange.requestURI.rawQuery
                val pathWithQuery = if (rawQuery.isNullOrEmpty()) {
                    exchange.requestURI.rawPath
                } else {
                    "${exchange.requestURI.rawPath}?$rawQuery"
                }
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val auth = exchange.requestHeaders.getFirst("Authorization")
                captured.add(CapturedRequest(pathWithQuery, auth, body))
                val respBytes = (responses.poll() ?: "OK").toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, respBytes.size.toLong())
                exchange.responseBody.use { it.write(respBytes) }
            }
            start()
        }
        val tempDir = Files.createTempDirectory("proxy-test").toFile()
        try {
            val host = ComfyHost(
                id = "h1",
                name = "Test Comfy",
                baseUrl = "http://127.0.0.1:${upstream.address.port}",
                authKind = ComfyAuthKind.BEARER,
            )
            val credentials = ComfyCredentials(MapSecrets()).apply {
                put(host, ComfyCredential.Bearer("secret-bearer-token-999"))
            }
            val client = OkHttpClient()

            ComfyLocalAuthProxy.open(host, credentials, client, tempDir).use { proxy ->
                assertTrue(proxy.baseUrl.startsWith("http://127.0.0.1:${proxy.port}/p/"))
                assertFalse(proxy.baseUrl.contains("secret-bearer-token-999"))

                // 1. 缺少或错误 nonce 必须返回 403，且绝不转发给上游。
                val wrongNonceResp = client.newCall(
                    Request.Builder().url("http://127.0.0.1:${proxy.port}/system_stats").get().build(),
                ).execute()
                wrongNonceResp.use {
                    assertEquals(403, it.code)
                }
                assertEquals(0, proxy.forwardedCount)

                // 2. 携带合法 nonce 且客户端恶意附带伪造 Authorization，代理必须剥离后注入真实凭据。
                val statsResp = client.newCall(
                    Request.Builder()
                        .url("${proxy.baseUrl}/system_stats")
                        .header("Authorization", "Bearer spoofed-token")
                        .get()
                        .build(),
                ).execute()
                statsResp.use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().contains("0.41.3"))
                }
                val rec1 = captured.poll()!!
                assertEquals("/system_stats", rec1.pathWithQuery)
                assertEquals("Bearer secret-bearer-token-999", rec1.authorization)

                // 3. Multipart 文件上传转发验证。
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("type", "input")
                    .addFormDataPart("image", "sample.png", byteArrayOf(1, 2, 3, 4).toRequestBody())
                    .build()
                val uploadResp = client.newCall(
                    Request.Builder().url("${proxy.baseUrl}/upload/image").post(multipart).build(),
                ).execute()
                uploadResp.use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().contains("ref.png"))
                }
                val rec2 = captured.poll()!!
                assertEquals("/upload/image", rec2.pathWithQuery)
                assertEquals("Bearer secret-bearer-token-999", rec2.authorization)
                assertTrue(rec2.bodyUtf8.contains("sample.png"))

                // 4. 带查询参数的 /view 下载验证。
                val viewResp = client.newCall(
                    Request.Builder().url("${proxy.baseUrl}/view?filename=out.mp4&type=output").get().build(),
                ).execute()
                viewResp.use {
                    assertEquals(200, it.code)
                    assertEquals("PNG_BYTES_OK", it.body!!.string())
                }
                val rec3 = captured.poll()!!
                assertEquals("/view?filename=out.mp4&type=output", rec3.pathWithQuery)
            }
        } finally {
            tempDir.deleteRecursively()
            upstream.stop(0)
        }
    }
}
