package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.LoopbackUploadServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ImageGenClientOkHttpRegressionTest {
    @Test fun `真实 OkHttp 完整上传单图多图蒙版与标注`() = runBlocking {
        val response = """{"data":[{"url":"https://images.example.test/result.png"}]}"""
        val first = ImageGenInput(ByteArray(89_123) { it.toByte() }, "image/png", "first.png")
        val second = ImageGenInput(ByteArray(73_987) { (it * 3).toByte() }, "image/webp", "second.webp")
        val layer = ImageGenInput(byteArrayOf(0, 42, -1), "image/png", "layer.png")
        LoopbackUploadServer(200 to response, 200 to response, 200 to response).use { server ->
            HttpClient(OkHttp).use { http ->
                val client = ImageGenClient("${server.url}/v1", "test-key", "gpt-image-2", http)
                val results = listOf(
                    client.edit("单图", listOf(first)),
                    client.edit("多图与蒙版", listOf(first, second), mask = layer),
                    client.edit("多图与标注", listOf(first, second), markup = layer),
                )
                results.forEach { assertThat(it.images.single().url).isEqualTo("https://images.example.test/result.png") }
            }
            val requests = server.requests()
            requests.forEach { assertThat(it.line).isEqualTo("POST /v1/images/edits HTTP/1.1") }
            fun parts(request: LoopbackUploadServer.Request): List<Pair<String, String>> {
                val boundary = request.headers.getValue("content-type").substringAfter("boundary=").trim('"')
                val wire = request.body.toString(Charsets.ISO_8859_1)
                assertThat(wire).endsWith("--$boundary--\r\n")
                return wire.split("--$boundary").mapNotNull { part ->
                    val name = Regex("name=\"([^\"]+)\"").find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1)
                    name?.let { it to part.substringAfter("\r\n\r\n").removeSuffix("\r\n") }
                }
            }
            val image1 = "image[]" to first.bytes.toString(Charsets.ISO_8859_1)
            val image2 = "image[]" to second.bytes.toString(Charsets.ISO_8859_1)
            val layerBytes = layer.bytes.toString(Charsets.ISO_8859_1)
            assertThat(parts(requests[0]).filter { it.first in setOf("image[]", "mask", "markup") }).containsExactly(image1)
            assertThat(parts(requests[1]).filter { it.first in setOf("image[]", "mask", "markup") }).containsExactly(image1, image2, "mask" to layerBytes).inOrder()
            assertThat(parts(requests[2]).filter { it.first in setOf("image[]", "mask", "markup") }).containsExactly(image1, image2, "markup" to layerBytes).inOrder()
        }
    }
}
