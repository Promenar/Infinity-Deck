package com.promenar.infinity.data.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.*
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuxiliaryApplicationRoutingTest {
    private lateinit var app: InfinityApplication
    private lateinit var http: HttpClient
    private val selected = MutableStateFlow("extra::remote")
    private val requests = mutableListOf<HttpRequestData>()
    private val manager = mockk<ProviderManager>()
    private val route = ResolvedProviderModel("extra::remote", "remote", "extra", "Extra",
        UnifiedProviderConfig(ProtocolType.OpenAI_ChatCompletions, "https://extra.test/v1", "fake-extra-key", "remote"))
    private val router = mockk<ProviderRequestRouter>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("infinity_provider", 0).edit().clear()
            .putString("embedding_base_url", "https://manual.test/v1").putString("embedding_model", "manual").commit()
        context.getSharedPreferences("rag_settings", 0).edit().clear().commit()
        val real = InfinityApplication()
        android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true; set(real, context) }
        app = spyk(real)
        every { app.getString(R.string.model_request_route_failed, any<String>()) } returns "模型不可用。请在设置中检查所选模型及其提供商。"
        every { app.providerRequestRouter } returns router
        every { app.localInferenceEngine } returns mockk(relaxed = true)
        // Robolectric 4.11 的 android.os.Process 没有 is64Bit()，门禁在此直接给定。
        every { app.localInferenceRuntimeGate } returns com.promenar.infinity.data.local.inference.LocalInferenceRuntimeGate(com.promenar.infinity.BuildConfig.LOCAL_INFERENCE_AVAILABLE)
        val secrets = mockk<SecretStore>()
        every { secrets.get(SecretCatalog.embeddingApiKey) } returns "fake-manual-key".toByteArray()
        every { app.secretStore } returns secrets
        mockkObject(ProviderManager.Companion)
        every { ProviderManager.getInstance() } returns manager
        every { manager.embeddingModelId } returns selected
        every { manager.rerankModelId } returns selected
        every { manager.providerModels } returns MutableStateFlow(emptyList())
        every { router.resolve("extra::remote") } returns ProviderResolution.Success(route)
        http = HttpClient(MockEngine { request ->
            requests += request
            respond("""{"data":[{"embedding":[1.0]}],"results":[{"index":0,"relevance_score":0.9}]}""")
        })
        mockkObject(ModelHttp)
        every { ModelHttp.client(any()) } returns http
    }

    @After fun cleanup() {
        http.close()
        unmockkObject(ModelHttp, ProviderManager.Companion)
    }

    private fun assertRequest(index: Int, owner: String, model: String, path: String = "embeddings") {
        val request = requests[index]
        assertThat(request.url.toString()).isEqualTo("https://$owner.test/v1/$path")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer fake-$owner-key")
        assertThat(Json.parseToJsonElement((request.body as TextContent).text).jsonObject["model"]?.jsonPrimitive?.content).isEqualTo(model)
    }

    @Test fun `保留的Embedding引用清空预设后使用完整手动配置`() = runTest {
        val retained = app.embeddingClient
        retained.embedQuery("a")
        selected.value = ""
        retained.embedQuery("b")
        assertRequest(0, "extra", "remote")
        assertRequest(1, "manual", "manual")
    }

    @Test fun `保留的手动Embedding引用切换预设后使用模型所属提供商`() = runTest {
        selected.value = ""
        val retained = app.embeddingClient
        retained.embedQuery("a")
        selected.value = "extra::remote"
        retained.embedQuery("b")
        assertRequest(0, "manual", "manual")
        assertRequest(1, "extra", "remote")
    }

    @Test fun `Rerank装配不借用手动Embedding地址凭据`() = runTest {
        val documents = listOf(SearchResult(id = "a", content = "甲", createdAt = 0L, similarity = 0.5f), SearchResult(id = "b", content = "乙", createdAt = 0L, similarity = 0.4f))
        app.rerankClient.rerankWithOutcome("q", documents, 1)
        assertRequest(0, "extra", "remote", "rerank")
    }

    @Test fun `无预设无手动地址时保留已加载本地向量通道`() = runTest {
        selected.value = ""
        app.getSharedPreferences("infinity_provider", 0).edit().remove("embedding_base_url").remove("embedding_model").commit()
        coEvery { app.localInferenceEngine.embedBatch(any()) } returns Result.success(listOf(floatArrayOf(0.25f)))
        assertThat(app.embeddingClient.embedDocuments(listOf("local")).embeddings.single().single()).isEqualTo(0.25f)
        assertThat(requests).isEmpty()
        coVerify(exactly = 1) { app.localInferenceEngine.embedBatch(listOf("local")) }
    }

    @Test fun `长期Embedding入口保留显式本地方法契约`() = runTest {
        coEvery { app.localInferenceEngine.embed(any()) } returns Result.success(floatArrayOf(0.5f))
        coEvery { app.localInferenceEngine.embedBatch(any()) } returns Result.success(listOf(floatArrayOf(0.25f)))
        assertThat(app.embeddingClient.embedLocal("local").getOrThrow().single()).isEqualTo(0.5f)
        assertThat(app.embeddingClient.embedLocalBatch(listOf("local")).getOrThrow().single().single()).isEqualTo(0.25f)
        assertThat(requests).isEmpty()
    }

    @Test fun `保留的Embedding引用遇到失效预设不回退手动配置`() = runTest {
        val retained = app.embeddingClient
        every { router.resolve("extra::remote") } returns ProviderResolution.Failure(ProviderResolutionError.MODEL_DISABLED, "extra::remote", "extra")
        try {
            retained.embedDocuments(listOf("private"))
            throw AssertionError("失效预设必须失败")
        } catch (failure: ModelRequestRouteException) {
            assertThat(failure.reason).isEqualTo(ProviderResolutionError.MODEL_DISABLED)
        }
        assertThat(requests).isEmpty()
    }
}
