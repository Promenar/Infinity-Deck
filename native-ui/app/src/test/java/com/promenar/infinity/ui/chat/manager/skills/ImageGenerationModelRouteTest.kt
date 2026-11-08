package com.promenar.infinity.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.stableModelId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
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
}
