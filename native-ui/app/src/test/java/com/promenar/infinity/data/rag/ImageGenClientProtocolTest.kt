package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class ImageGenClientProtocolTest {
    private fun multipartFields(text: String): List<Pair<String, String>> = text.split("\r\n--").mapNotNull { part ->
        val headerEnd = part.indexOf("\r\n\r\n")
        if (headerEnd < 0) return@mapNotNull null
        val name = Regex("(?:^|; )name=(?:\"([^\"]+)\"|([^;\r\n]+))").find(part.substring(0, headerEnd))
            ?: return@mapNotNull null
        name.groupValues[1].ifEmpty { name.groupValues[2] } to part.substring(headerEnd + 4).removeSuffix("\r\n")
    }

    @Test fun `生成请求仅包含显式参数与最低审核档位`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("https://images.test/v1/images/generations")
            bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"data":[]}""")
        })
        try {
            val client = ImageGenClient("https://images.test/v1", "test-key", "gpt-image-2", http)
            client.generate("一棵树")
            client.generate("海报", size = "1536x1024", quality = "high", background = "opaque",
                outputFormat = "webp", outputCompression = 80, n = 4)
            assertThat(bodies[0].keys).containsExactly("model", "prompt", "moderation")
            assertThat(bodies[0]["moderation"]?.jsonPrimitive?.content).isEqualTo("low")
            assertThat(bodies[1]).isEqualTo(Json.parseToJsonElement("""{"model":"gpt-image-2","prompt":"海报","size":"1536x1024","quality":"high","background":"opaque","output_format":"webp","output_compression":80,"n":4,"moderation":"low"}""").jsonObject)
        } finally { http.close() }
    }

    @Test fun `编辑使用重复image数组字段并传递蒙版及保真度`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("https://images.test/v1/images/edits")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-key")
            val body = request.body as OutgoingContent.WriteChannelContent
            assertThat(body.contentType.toString()).startsWith("multipart/form-data; boundary=")
            val text = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val reader = async { channel.readRemaining().readText() }
                body.writeTo(channel)
                channel.close(null)
                reader.await()
            }
            val fields = multipartFields(text)
            assertThat(fields.filter { it.first == "image[]" }.map { it.second }).containsExactly("JPEG-BYTES", "WEBP-BYTES").inOrder()
            mapOf("mask" to "MASK-BYTES", "input_fidelity" to "high", "size" to "auto", "quality" to "high",
                "background" to "transparent", "output_format" to "webp", "output_compression" to "90", "n" to "2", "moderation" to "low",
                "model" to "gpt-image-1.5", "prompt" to "合成海报").forEach { (key, value) ->
                assertThat(fields.filter { it.first == key }.map { it.second }).containsExactly(value)
            }
            assertThat(text).contains("Content-Type: image/jpeg")
            assertThat(text).contains("Content-Type: image/webp")
            assertThat(text).contains("Content-Type: image/png")
            assertThat(text).contains("filename=\"mask.png\"")
            assertThat(text).contains("\r\n\r\nhigh\r\n")
            assertThat(text).contains("JPEG-BYTES")
            assertThat(text).contains("WEBP-BYTES")
            assertThat(text).contains("MASK-BYTES")
            respond("""{"data":[]}""")
        })
        try {
            ImageGenClient("https://images.test", "test-key", "gpt-image-1.5", http).edit(
                "合成海报", listOf(ImageGenInput("JPEG-BYTES".toByteArray(), "image/jpeg", "first.jpg"),
                    ImageGenInput("WEBP-BYTES".toByteArray(), "image/webp", "second.webp")),
                mask = ImageGenInput("MASK-BYTES".toByteArray(), "image/png", "mask.png"), inputFidelity = "high",
                size = "auto", quality = "high", background = "transparent", outputFormat = "webp", outputCompression = 90, n = 2,
            )
        } finally { http.close() }
    }

    @Test fun `编辑省略所有未提供的可选参数`() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = request.body as OutgoingContent.WriteChannelContent
            val text = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val reader = async { channel.readRemaining().readText() }
                body.writeTo(channel); channel.close(null); reader.await()
            }
            assertThat(multipartFields(text).map { it.first })
                .containsExactly("model", "prompt", "moderation", "image[]")
            respond("""{"data":[]}""")
        })
        try {
            ImageGenClient("https://images.test", "test-key", "gpt-image-2", http)
                .edit("调整照明", listOf(ImageGenInput(byteArrayOf(1), "image/png", "image.png")))
        } finally { http.close() }
    }

    @Test fun `响应解析回显字段与完整用量详情`() = runTest {
        val http = HttpClient(MockEngine { respond("""{"created":123,"size":"1536x1024","quality":"high","background":"opaque","output_format":"jpeg","data":[{"b64_json":"aGVsbG8=","revised_prompt":"修订"}],"usage":{"input_tokens":12,"output_tokens":34,"total_tokens":46,"input_tokens_details":{"text_tokens":2,"image_tokens":10},"output_tokens_details":{"image_tokens":34}}}""") })
        try {
            val result = ImageGenClient("https://images.test", "test-key", "gpt-image-2", http).generate("照片")
            assertThat(result.created).isEqualTo(123L)
            assertThat(result.size).isEqualTo("1536x1024")
            assertThat(result.quality).isEqualTo("high")
            assertThat(result.background).isEqualTo("opaque")
            assertThat(result.outputFormat).isEqualTo("jpeg")
            assertThat(result.images.single().revisedPrompt).isEqualTo("修订")
            assertThat(result.usage?.inputTokens).isEqualTo(12L)
            assertThat(result.usage?.outputTokens).isEqualTo(34L)
            assertThat(result.usage?.totalTokens).isEqualTo(46L)
            assertThat(result.usage?.inputTokensDetails?.get("image_tokens")?.jsonPrimitive?.int).isEqualTo(10)
            assertThat(result.usage?.outputTokensDetails?.get("image_tokens")?.jsonPrimitive?.int).isEqualTo(34)
        } finally { http.close() }
    }

    @Test fun `编辑响应同样返回回显与用量且空字段保持空值`() = runTest {
        val http = HttpClient(MockEngine { respond("""{"created":456,"quality":"medium","output_format":"webp","data":[{"b64_json":"aGVsbG8=","url":null,"revised_prompt":null}],"usage":{"input_tokens":2,"total_tokens":5}}""") })
        try {
            val result = ImageGenClient("https://images.test/v1/", "test-key", "gpt-image-2", http)
                .edit("调整光照", listOf(ImageGenInput(byteArrayOf(1), "image/png", "a.png")))
            assertThat(result.created).isEqualTo(456L)
            assertThat(result.quality).isEqualTo("medium")
            assertThat(result.outputFormat).isEqualTo("webp")
            assertThat(result.size).isNull()
            assertThat(result.images.single().url).isNull()
            assertThat(result.images.single().revisedPrompt).isNull()
            assertThat(result.usage?.inputTokens).isEqualTo(2L)
            assertThat(result.usage?.outputTokens).isNull()
        } finally { http.close() }
    }

    @Test fun `编辑失败沿用HTTP错误摘要`() = runTest {
        val http = HttpClient(MockEngine { respond("""{"error":{"code":"invalid_image","message":"输入图片无效"}}""", HttpStatusCode.BadRequest) })
        try {
            val error = runCatching { ImageGenClient("https://images.test", "test-key", "gpt-image-2", http)
                .edit("调整光照", listOf(ImageGenInput(byteArrayOf(1), "image/png", "a.png"))) }.exceptionOrNull()
            assertThat(error?.message).isEqualTo("Image generation failed (HTTP 400 · invalid_image): 输入图片无效")
        } finally { http.close() }
    }

    @Test fun `DALL·E 模型不发送审核档位`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"data":[]}""")
        })
        try {
            ImageGenClient("https://images.test", "test-key", "dall-e-3", http).generate("一棵树")
            assertThat(bodies.single().keys).containsExactly("model", "prompt")
        } finally { http.close() }
    }

    @Test fun `网关不认识审核参数时去掉后重发一次`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            bodies += body
            if ("moderation" in body) respond(
                """{"error":{"message":"Unknown parameter: 'moderation'.","type":"invalid_request_error","param":"moderation","code":"unknown_parameter"}}""",
                HttpStatusCode.BadRequest,
            ) else respond("""{"data":[{"b64_json":"aGVsbG8="}]}""")
        })
        try {
            val result = ImageGenClient("https://images.test", "test-key", "gpt-image-2", http).generate("一棵树", quality = "high")
            assertThat(result.images).hasSize(1)
            assertThat(bodies).hasSize(2)
            assertThat(bodies[1].keys).containsExactly("model", "prompt", "quality")
        } finally { http.close() }
    }

    @Test fun `内容被安全系统拦截时不重发并照常报错`() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond(
                """{"error":{"message":"Your request was rejected by the safety system.","type":"image_generation_user_error","code":"moderation_blocked"}}""",
                HttpStatusCode.BadRequest,
            )
        })
        try {
            val error = runCatching { ImageGenClient("https://images.test", "test-key", "gpt-image-2", http).generate("一棵树") }.exceptionOrNull()
            assertThat(calls).isEqualTo(1)
            assertThat(error?.message).startsWith("Image generation failed (HTTP 400 · moderation_blocked)")
        } finally { http.close() }
    }

    @Test fun `编辑同样在网关拒绝审核参数时重发`() = runTest {
        val sent = mutableListOf<List<String>>()
        val http = HttpClient(MockEngine { request ->
            val body = request.body as OutgoingContent.WriteChannelContent
            val text = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val reader = async { channel.readRemaining().readText() }
                body.writeTo(channel); channel.close(null); reader.await()
            }
            val names = multipartFields(text).map { it.first }
            sent += names
            if ("moderation" in names) respond("""{"error":{"message":"moderation: unsupported field"}}""", HttpStatusCode.UnprocessableEntity)
            else respond("""{"data":[]}""")
        })
        try {
            ImageGenClient("https://images.test", "test-key", "gpt-image-2", http)
                .edit("调整照明", listOf(ImageGenInput(byteArrayOf(1), "image/png", "image.png")))
            assertThat(sent).hasSize(2)
            assertThat(sent[1]).containsExactly("model", "prompt", "image[]")
        } finally { http.close() }
    }
}
