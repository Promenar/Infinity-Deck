package com.promenar.infinity.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.net.InetAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ImageGenerationWorkspaceTest {
    private val store = RecordingMediaStore()
    private val providers = mockk<ProviderManager> {
        every { imageModelId } returns MutableStateFlow("default::image-test")
        every { providerModels } returns MutableStateFlow(listOf(ModelInfo(id = "default::image-test", remoteModelId = "image-test", providerId = "default", name = "Image", description = "", enabled = true)))
        every { this@mockk.providers } returns MutableStateFlow(listOf(ProviderListItem(id = "default", name = "Image")))
        every { getProviderConfig("default") } returns ProviderConfig(baseUrl = "https://images.test/v1", apiKey = "test-key")
        every { isPersistedProtocolUnsupported("default") } returns false
    }
    private val downloader = WorkspaceMediaDownloader(OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .header("Content-Type", "image/png").body(mediaTestPng.toResponseBody()).build()
    }.build(), MediaFetchUrlPolicy { false }, { false },
        AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) }))

    private suspend fun generate(vararg images: JsonObject, args: JsonObject = buildJsonObject { put("prompt", "一棵树") },
        fields: JsonObject = buildJsonObject {}): com.promenar.infinity.data.model.ToolResult {
        val client = HttpClient(MockEngine { respond(buildJsonObject { fields.forEach { (key, value) -> put(key, value) }; put("data", JsonArray(images.toList())) }.toString()) })
        return try {
            val skill = ImageGenerationSkill(mockk<Context>(), providers, store, downloader) { url, key, model -> ImageGenClient(url, key, model, client) }
            skill.execute(args, mediaTestContext)
        } finally { client.close() }
    }
    private fun base64Image(raw: String = mediaBase64()) = buildJsonObject { put("b64_json", raw); put("revised_prompt", "模型修订的提示词") }

    @Test fun `URL和base64结果落工作区并提供独立卡片数据`() = runTest {
        val result = generate(buildJsonObject { put("url", "https://images.test/a.png") }, base64Image())
        assertThat(result.media).hasSize(2)
        val data = ImageGenerationResultData.decode(result.data)
        assertThat(data?.model).isEqualTo("image-test")
        assertThat(data?.mode).isEqualTo("generate")
        assertThat(data?.failedCount).isEqualTo(0)
        listOf("localPath", "url", "b64Json").forEach { assertThat(result.data).doesNotContain("\"$it\"") }
        assertThat(result.content).contains("/media/2026-10-20/gen-1.png")
        assertThat(result.content).contains("/media/2026-10-20/gen-2.png")
        assertThat(result.content).contains("3×2")
        assertThat(result.content).contains("![说明]")
        assertThat(result.content).contains("模型修订的提示词")
        assertThat(result.content).doesNotContain(mediaBase64())
        assertThat(result.content).doesNotContain("原始提示词")
        assertThat(Regex(Regex.escape("![说明]")).findAll(result.content).count()).isEqualTo(1)
        assertThat(store.saved.map { it.origin }).containsExactly(MediaOrigin.GENERATED, MediaOrigin.GENERATED)
    }
    @Test fun `部分失败保留成功引用并说明数量`() = runTest {
        val result = generate(base64Image(), base64Image("%invalid%"), base64Image())
        assertThat(result.status).isEqualTo("success")
        assertThat(result.media).hasSize(2)
        assertThat(result.content).contains("第 2 张")
        assertThat(ImageGenerationResultData.decode(result.data)?.failedCount).isEqualTo(1)
    }
    @Test fun `服务端错误返回状态码错误码与完整说明且不重复前缀`() = runTest {
        val message = "No available channel for model default::sensenova-u1.5-lite under group default (distributor) (request id: 20261020145110140000000000000000)"
        val body = buildJsonObject { put("error", buildJsonObject { put("code", "model_not_found"); put("message", message); put("type", "new_api_error") }) }.toString()
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.ServiceUnavailable) })
        val result = try {
            ImageGenerationSkill(mockk<Context>(), providers, store, downloader) { url, key, model -> ImageGenClient(url, key, model, client) }
                .execute(buildJsonObject { put("prompt", "一棵树") }, mediaTestContext)
        } finally { client.close() }
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).isEqualTo("Image generation failed (HTTP 503 · model_not_found): $message")
    }
    @Test fun `工作区不可用返回明确错误不写旧目录`() = runTest {
        store.failure = WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用")
        val result = generate(base64Image())
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("工作区不可用")
        assertThat(result.media).isNull()
        assertThat(ImageGenerationResultData.decode(result.data)?.failedCount).isEqualTo(1)
    }

    @Test fun `保存MIME优先响应格式其次请求格式最后识别字节`() = runTest {
        val jpeg = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 0)
        generate(base64Image(mediaBase64(jpeg)), args = buildJsonObject { put("prompt", "照片"); put("output_format", "png") },
            fields = buildJsonObject { put("output_format", "jpeg") })
        generate(base64Image(), args = buildJsonObject { put("prompt", "一棵树"); put("output_format", "webp") })
        generate(base64Image(mediaBase64(jpeg)))
        assertThat(store.saved.map { it.mime }).containsExactly("image/jpeg", "image/webp", "image/jpeg").inOrder()
    }

    @Test fun `回显与用量写入卡片且未指定参数保持为空`() = runTest {
        val result = generate(base64Image(), fields = buildJsonObject {
            put("size", "1536x1024"); put("quality", "high"); put("background", "opaque"); put("output_format", "png")
            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 20); put("total_tokens", 30) })
        })
        val data = ImageGenerationResultData.decode(result.data)!!
        assertThat(data.requested.size).isNull()
        assertThat(data.requested.n).isNull()
        assertThat(data.actual.size).isEqualTo("1536x1024")
        assertThat(data.actual.quality).isEqualTo("high")
        assertThat(data.usage?.totalTokens).isEqualTo(30L)
        assertThat(data.revisedPrompts).containsExactly("模型修订的提示词")
    }

    @Test fun `参数无效返回可纠正的错误且不发HTTP请求`() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; respond("""{"data":[]}""") })
        try {
            val skill = ImageGenerationSkill(mockk<Context>(), providers, store, downloader) { url, key, model -> ImageGenClient(url, key, model, http) }
            val result = skill.execute(buildJsonObject { put("prompt", "图片"); put("quality", "hd") }, mediaTestContext)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("low")
            assertThat(requests).isEqualTo(0)
            val schema = Json.parseToJsonElement(skill.parametersSchema).jsonObject["properties"]!!.jsonObject
            assertThat(schema.keys).containsExactly("prompt", "size", "quality", "background", "output_format", "output_compression", "n", "images", "mask", "input_fidelity")
        } finally { http.close() }
    }

    @Test fun `编辑读取工作区并在data保留规范输入路径`() = runTest {
        val resolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
                MediaRef("/media/$path", "image/png", MediaKind.IMAGE, mediaTestPng.size.toLong(), width = 3, height = 2),
                MediaSource.LocalFile(java.io.File("unused")))
            override suspend fun open(resolution: MediaResolution.Available) = mediaTestPng.inputStream()
        }
        val http = HttpClient(MockEngine { request ->
            assertThat(request.url.encodedPath).isEqualTo("/v1/images/edits")
            respond("""{"data":[{"b64_json":"${mediaBase64()}"}]}""")
        })
        try {
            val skill = ImageGenerationSkill(mockk<Context>(), providers, store, downloader, mediaResolver = resolver,
                imageGenClientFactory = { url, key, model -> ImageGenClient(url, key, model, http) })
            val result = skill.execute(buildJsonObject { put("prompt", "调整光照"); put("images", buildJsonArray { add("a.png") }); put("mask", "mask.png") }, mediaTestContext)
            assertThat(result.status).isEqualTo("success")
            val data = ImageGenerationResultData.decode(result.data)!!
            assertThat(data.mode).isEqualTo("edit")
            assertThat(data.inputImages).containsExactly("/media/a.png")
            assertThat(data.mask).isEqualTo("/media/mask.png")
        } finally { http.close() }
    }

    @Test fun `越界图片返回error并在网络前拒绝`() = runTest {
        val resolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Unavailable(path, MediaUnavailableReason.INVALID_PATH)
            override suspend fun open(resolution: MediaResolution.Available): java.io.InputStream = error("不能打开越界来源")
        }
        val skill = ImageGenerationSkill(mockk<Context>(), providers, store, downloader, mediaResolver = resolver,
            imageGenClientFactory = { _, _, _ -> error("越界图片不能创建客户端") })
        val result = skill.execute(buildJsonObject { put("prompt", "调整光照"); put("images", buildJsonArray { add("../../outside.png") }) }, mediaTestContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("工作区")
    }

    @Test fun `空图片响应保留卡片元数据并返回error`() = runTest {
        val result = generate(fields = buildJsonObject { put("quality", "high") })
        assertThat(result.status).isEqualTo("error")
        assertThat(result.media).isNull()
        assertThat(result.content).contains("未返回图片")
        assertThat(ImageGenerationResultData.decode(result.data)?.actual?.quality).isEqualTo("high")
        assertThat(ImageGenerationResultData.decode(result.data)?.failedCount).isEqualTo(1)
    }
}
