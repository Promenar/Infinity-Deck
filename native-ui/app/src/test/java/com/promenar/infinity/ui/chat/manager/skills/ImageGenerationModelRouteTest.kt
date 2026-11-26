package com.promenar.infinity.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.GeminiImageClient
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.stableModelId
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class ImageGenerationModelRouteTest {
    private val jobFixture = MediaTestJobFixture()
    @org.junit.After fun closeJobs() = jobFixture.close()
    private val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    private val providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    private val selected = MutableStateFlow("")
    private val configs = mutableMapOf<String, ProviderConfig>()
    private val requests = mutableListOf<HttpRequestData>()
    private val manager = mockk<ProviderManager> {
        every { imageModelId } returns selected
        every { providerModels } returns models
        every { this@mockk.providers } returns this@ImageGenerationModelRouteTest.providers
        every { getProviderConfig(any()) } answers { configs[firstArg()] }
        every { isPersistedProtocolUnsupported(any()) } returns false
        every { getMainProviderConfig() } answers { configs["default"] }
    }

    private fun seed(provider: String, remote: String, protocol: ProtocolType = ProtocolType.OpenAI_ChatCompletions): String {
        val id = stableModelId(provider, remote)
        configs[provider] = ProviderConfig(protocolType = protocol, baseUrl = "https://$provider.test/v1", apiKey = "fake-$provider-key", model = remote)
        providers.value += ProviderListItem(id = provider, name = provider, protocolType = protocol)
        models.value += ModelInfo(id = id, remoteModelId = remote, providerId = provider, providerName = provider, name = remote, description = "", enabled = true)
        return id
    }

    private suspend fun generate(id: String): com.promenar.infinity.data.model.ToolResult {
        selected.value = id
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(buildJsonObject { put("data", buildJsonArray { add(buildJsonObject { put("b64_json", mediaBase64()) }) }) }.toString())
        })
        return try {
            ImageGenerationSkill(mockk<Context>(), manager, RecordingMediaStore(), jobs = jobFixture.jobs, imageGenClientFactory = { url, key, model ->
                ImageGenClient(url, key, model, client)
            }).execute(buildJsonObject { put("prompt", "一棵树") }, mediaTestContext)
        } finally { client.close() }
    }

    private fun assertRequest(index: Int, provider: String, remote: String) {
        val request = requests[index]
        assertThat(request.url.toString()).isEqualTo("https://$provider.test/v1/images/generations")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer fake-$provider-key")
        assertThat(Json.parseToJsonElement((request.body as TextContent).text).jsonObject["model"]?.jsonPrimitive?.content).isEqualTo(remote)
    }

    @Test fun `默认提供商稳定ID只发送远端模型ID`() = runTest {
        assertThat(generate(seed("default", "sensenova-u1.5-lite")).status).isEqualTo("success")
        assertRequest(0, "default", "sensenova-u1.5-lite")
    }

    @Test fun `额外提供商使用自己的地址和凭据`() = runTest {
        seed("default", "main-image")
        assertThat(generate(seed("extra", "sensenova-u1.5-lite")).status).isEqualTo("success")
        assertRequest(0, "extra", "sensenova-u1.5-lite")
    }

    @Test fun `同名模型不会串用提供商且特殊字符原样发送`() = runTest {
        val remote = "org/image::version:1/preview"
        val first = seed("default", remote)
        val second = seed("extra", remote)
        generate(second)
        generate(first)
        assertRequest(0, "extra", remote)
        assertRequest(1, "default", remote)
    }

    @Test fun `模型不存在时不发请求并指向设置`() = runTest {
        seed("default", "main-image")
        val result = generate("deleted::image")
        assertThat(requests).isEmpty()
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("MODEL_NOT_FOUND")
        assertThat(result.content).contains("Settings")
    }

    @Test fun `禁用模型时不发请求`() = runTest {
        val id = seed("default", "image")
        models.value = models.value.map { it.copy(enabled = false) }
        val result = generate(id)
        assertThat(requests).isEmpty()
        assertThat(result.content).contains("MODEL_DISABLED")
    }

    @Test fun `删除或禁用提供商时不回退主提供商`() = runTest {
        seed("default", "main-image")
        val id = seed("extra", "image")
        providers.value = providers.value.map { if (it.id == "extra") it.copy(enabled = false) else it }
        assertThat(generate(id).content).contains("PROVIDER_DISABLED")
        providers.value = providers.value.filterNot { it.id == "extra" }
        assertThat(generate(id).content).contains("PROVIDER_NOT_FOUND")
        assertThat(requests).isEmpty()
    }

    @Test fun `不支持的图像协议在请求前明确失败`() = runTest {
        val id = seed("default", "image", ProtocolType.Anthropic_Messages)
        val result = generate(id)
        assertThat(requests).isEmpty()
        assertThat(result.content).contains("PROTOCOL_UNSUPPORTED")
        assertThat(result.content).contains("Settings")
    }

    @Test fun `凭据地址与历史协议失效均在网络前报告原因`() = runTest {
        val id = seed("default", "image")
        val original = configs.getValue("default")
        configs["default"] = original.copy(apiKey = "")
        assertThat(generate(id).content).contains("API_KEY_MISSING")
        configs["default"] = original.copy(baseUrl = "ftp://unsafe.test")
        assertThat(generate(id).content).contains("BASE_URL_INVALID")
        configs["default"] = original.copy(protocolType = ProtocolType.Anthropic_Messages)
        assertThat(generate(id).content).contains("PROTOCOL_MISMATCH")
        configs["default"] = original
        every { manager.isPersistedProtocolUnsupported("default") } returns true
        assertThat(generate(id).content).contains("PROTOCOL_UNSUPPORTED")
        assertThat(requests).isEmpty()
    }

    @Test fun `未选择图像模型不发请求并说明设置入口`() = runTest {
        val result = generate("")
        assertThat(requests).isEmpty()
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("Default Image Model")
    }

    private fun seedGemini(
        provider: String = "google",
        remote: String = "gemini-2.5-flash-image",
        baseUrl: String = "https://generativelanguage.googleapis.com",
    ): String {
        val id = stableModelId(provider, remote)
        configs[provider] = ProviderConfig(
            protocolType = ProtocolType.Google_Gemini,
            baseUrl = baseUrl,
            apiKey = "gemini-test-key",
            model = remote,
        )
        providers.value += ProviderListItem(id = provider, name = provider, protocolType = ProtocolType.Google_Gemini)
        models.value += ModelInfo(id = id, remoteModelId = remote, providerId = provider, providerName = provider, name = remote, description = "", enabled = true)
        return id
    }

    private fun geminiImageJsonResponse(
        imageB64: String = mediaBase64(),
        mimeType: String = "image/png",
        text: String? = "柔和日光下的湖畔木屋",
        totalTokens: Long = 1310L,
    ): String = buildJsonObject {
        put("candidates", buildJsonArray {
            add(buildJsonObject {
                put("finishReason", "STOP")
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        text?.let { add(buildJsonObject { put("text", it) }) }
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mimeType)
                                put("data", imageB64)
                            })
                        })
                    })
                })
            })
        })
        put("usageMetadata", buildJsonObject {
            put("promptTokenCount", 20)
            put("candidatesTokenCount", totalTokens - 20)
            put("totalTokenCount", totalTokens)
        })
    }.toString()

    @Test fun `Google Gemini 原生生图走 generateContent 与 x-goog-api-key 并写入工作区与用量`() = runTest {
        val id = seedGemini()
        selected.value = id
        val store = RecordingMediaStore()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(geminiImageJsonResponse())
        })
        val result = try {
            ImageGenerationSkill(
                mockk<Context>(),
                manager,
                store,
                jobs = jobFixture.jobs,
                geminiImageClientFactory = { route -> GeminiImageClient(route, http) },
                imageGenClientFactory = { _, _, _ -> error("Gemini 协议不应走 OpenAI ImageGenClient") },
            ).execute(buildJsonObject { put("prompt", "湖畔木屋"); put("size", "1536x1024") }, mediaTestContext)
        } finally { http.close() }

        assertThat(result.status).isEqualTo("success")
        assertThat(result.media).hasSize(1)
        assertThat(requests).hasSize(1)
        val req = requests.single()
        assertThat(req.url.toString()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent")
        assertThat(req.headers["x-goog-api-key"]).isEqualTo("gemini-test-key")
        assertThat(req.headers["Authorization"]).isNull()
        val reqBody = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        val genCfg = reqBody["generationConfig"]!!.jsonObject
        assertThat(genCfg["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("IMAGE", "TEXT").inOrder()
        assertThat(genCfg["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content).isEqualTo("3:2")

        val data = ImageGenerationResultData.decode(result.data)!!
        assertThat(data.mode).isEqualTo("generate")
        assertThat(data.model).isEqualTo("gemini-2.5-flash-image")
        assertThat(data.actual.outputFormat).isEqualTo("png")
        assertThat(data.usage?.inputTokens).isEqualTo(20L)
        assertThat(data.usage?.totalTokens).isEqualTo(1310L)
        assertThat(data.revisedPrompts).containsExactly("柔和日光下的湖畔木屋")
    }

    @Test fun `Google Gemini 多图编辑携带 inlineData 与 mask 并保留规范路径`() = runTest {
        val id = seedGemini(baseUrl = "https://proxy.example.test/gemini/v1beta")
        selected.value = id
        val store = RecordingMediaStore()
        val resolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
                MediaRef("/media/$path", "image/png", MediaKind.IMAGE, mediaTestPng.size.toLong(), width = 3, height = 2),
                MediaSource.LocalFile(java.io.File("unused")),
            )
            override suspend fun open(resolution: MediaResolution.Available) = mediaTestPng.inputStream()
        }
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(geminiImageJsonResponse(text = "已按参考图合成"))
        })
        val result = try {
            ImageGenerationSkill(
                mockk<Context>(),
                manager,
                store,
                mediaResolver = resolver,
                jobs = jobFixture.jobs,
                geminiImageClientFactory = { route -> GeminiImageClient(route, http) },
                imageGenClientFactory = { _, _, _ -> error("不应走 OpenAI 路径") },
            ).execute(buildJsonObject {
                put("prompt", "合并两张参考图的风格")
                put("images", buildJsonArray { add("a.png"); add("b.png") })
                put("mask", "mask.png")
            }, mediaTestContext)
        } finally { http.close() }

        assertThat(result.status).isEqualTo("success")
        assertThat(requests).hasSize(1)
        assertThat(requests.single().url.toString())
            .isEqualTo("https://proxy.example.test/gemini/v1beta/models/gemini-2.5-flash-image:generateContent")
        val parts = Json.parseToJsonElement((requests.single().body as TextContent).text)
            .jsonObject["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertThat(parts).hasSize(4)
        assertThat(parts[0].jsonObject["text"]!!.jsonPrimitive.content).contains("合并两张参考图的风格")
        assertThat(parts[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(parts[2].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(parts[3].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")

        val data = ImageGenerationResultData.decode(result.data)!!
        assertThat(data.mode).isEqualTo("edit")
        assertThat(data.inputImages).containsExactly("/media/a.png", "/media/b.png").inOrder()
        assertThat(data.mask).isEqualTo("/media/mask.png")
    }

    @Test fun `Google Gemini 指定多张数量 n 时循环补足并累加用量`() = runTest {
        val id = seedGemini()
        selected.value = id
        val store = RecordingMediaStore()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(geminiImageJsonResponse(totalTokens = 100L))
        })
        val result = try {
            ImageGenerationSkill(
                mockk<Context>(),
                manager,
                store,
                jobs = jobFixture.jobs,
                geminiImageClientFactory = { route -> GeminiImageClient(route, http) },
            ).execute(buildJsonObject { put("prompt", "星空"); put("n", 2) }, mediaTestContext)
        } finally { http.close() }

        assertThat(result.status).isEqualTo("success")
        assertThat(requests).hasSize(2)
        assertThat(result.media).hasSize(2)
        assertThat(ImageGenerationResultData.decode(result.data)?.usage?.totalTokens).isEqualTo(200L)
    }

    @Test fun `Google Gemini 服务端 HTTP 错误透出状态码与详情且不重复前缀`() = runTest {
        val id = seedGemini()
        selected.value = id
        val errorBody = buildJsonObject {
            put("error", buildJsonObject {
                put("code", 400)
                put("message", "API key not valid. Please pass a valid API key.")
                put("status", "INVALID_ARGUMENT")
            })
        }.toString()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(errorBody, HttpStatusCode.BadRequest)
        })
        val result = try {
            ImageGenerationSkill(
                mockk<Context>(),
                manager,
                RecordingMediaStore(),
                jobs = jobFixture.jobs,
                geminiImageClientFactory = { route -> GeminiImageClient(route, http) },
            ).execute(buildJsonObject { put("prompt", "一只猫") }, mediaTestContext)
        } finally { http.close() }

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).startsWith("Image generation failed (HTTP 400")
        assertThat(result.content).contains("API key not valid.")
        assertThat(Regex("Image generation failed").findAll(result.content).count()).isEqualTo(1)
    }

    @Test fun `Google Vertex AI 原生生图交换 OAuth 令牌并走 Vertex generateContent`() = runTest {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8B64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$pkcs8B64\n-----END PRIVATE KEY-----"
        val saJson = buildJsonObject {
            put("type", "service_account")
            put("project_id", "demo-proj")
            put("client_email", "img-bot@demo-proj.iam.gserviceaccount.com")
            put("private_key", pem)
        }.toString()
        val provider = "vertex"
        val remote = "gemini-2.5-flash-image"
        val id = stableModelId(provider, remote)
        configs[provider] = ProviderConfig(
            protocolType = ProtocolType.Google_VertexAI,
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            apiKey = "",
            model = remote,
            vertexServiceAccountJson = saJson,
        )
        providers.value += ProviderListItem(id = provider, name = provider, protocolType = ProtocolType.Google_VertexAI)
        models.value += ModelInfo(id = id, remoteModelId = remote, providerId = provider, providerName = provider, name = remote, description = "", enabled = true)
        selected.value = id

        val store = RecordingMediaStore()
        val http = HttpClient(MockEngine { request ->
            requests += request
            if (request.url.toString() == "https://oauth2.googleapis.com/token") {
                respond("""{"access_token":"vertex-test-token","expires_in":3600}""")
            } else {
                respond(geminiImageJsonResponse())
            }
        })
        val result = try {
            ImageGenerationSkill(
                mockk<Context>(),
                manager,
                store,
                jobs = jobFixture.jobs,
                geminiImageClientFactory = { route -> GeminiImageClient(route, http) },
                imageGenClientFactory = { _, _, _ -> error("Vertex 协议不应走 OpenAI 客户端") },
            ).execute(buildJsonObject { put("prompt", "雪山日出") }, mediaTestContext)
        } finally { http.close() }

        assertThat(result.status).isEqualTo("success")
        assertThat(result.media).hasSize(1)
        assertThat(requests).hasSize(2)
        assertThat(requests[0].url.toString()).isEqualTo("https://oauth2.googleapis.com/token")
        assertThat(requests[1].url.toString())
            .isEqualTo("https://us-central1-aiplatform.googleapis.com/v1/projects/demo-proj/locations/us-central1/publishers/google/models/gemini-2.5-flash-image:generateContent")
        assertThat(requests[1].headers["Authorization"]).isEqualTo("Bearer vertex-test-token")
    }
}
