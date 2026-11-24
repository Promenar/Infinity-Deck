package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.proxy.BuiltInProxyUnavailableException
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.data.rag.RerankClient
import com.promenar.infinity.data.rag.SearchResult
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetConnectGate
import com.promenar.infinity.data.tailnet.TailnetConnector
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.tailnet.TailnetUnavailableException
import com.promenar.infinity.data.tailnet.TailnetUnavailableReason
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ModelHttpTest {
    private class FakeConnector(phase: TailnetPhase, private val starts: Boolean) : TailnetConnector {
        override val state: StateFlow<TailnetState> = MutableStateFlow(TailnetState(phase))
        private val disabled = phase == TailnetPhase.DISABLED
        override fun isTailnetHost(host: String): Boolean = !disabled && host.endsWith(".ts.net")
        override fun isUnreachableWhileDisabled(host: String): Boolean = disabled && host.endsWith(".ts.net")
        override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean = starts
    }

    private val tailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
    }

    /** 守卫之后接一个记录到达次数的终端拦截器，代替真实网络。 */
    private fun client(gate: TailnetConnectGate, reached: AtomicInteger) = OkHttpClient.Builder()
        .addInterceptor(TailnetRouteGuard({ tailnetRouting }, { gate }))
        .addInterceptor(Interceptor { chain ->
            reached.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        })
        .build()

    @Test
    fun `内置 Tailscale 未接入时 tailnet 请求直接失败，不发出也不回退直连`() {
        val reached = AtomicInteger()
        val gate = TailnetConnectGate(FakeConnector(TailnetPhase.FAILED, starts = false))
        val call = client(gate, reached).newCall(
            Request.Builder().url("https://gpu.tail1.ts.net/v1/chat/completions").header("Authorization", "Bearer k").build(),
        )
        val error = assertThrows(TailnetUnavailableException::class.java) { call.execute() }
        assertThat(error.reason).isEqualTo(TailnetUnavailableReason.FAILED)
        assertThat(error.message).doesNotContain("Bearer")
        assertThat(reached.get()).isEqualTo(0)
    }

    @Test
    fun `内置 Tailscale 关闭且系统无 tailnet 路由时请求立即失败`() {
        val reached = AtomicInteger()
        val gate = TailnetConnectGate(FakeConnector(TailnetPhase.DISABLED, starts = false))
        val call = client(gate, reached).newCall(Request.Builder().url("https://gpu.tail1.ts.net/v1/embeddings").build())
        val error = assertThrows(TailnetUnavailableException::class.java) { call.execute() }
        assertThat(error.reason).isEqualTo(TailnetUnavailableReason.DISABLED)
        assertThat(reached.get()).isEqualTo(0)
    }

    @Test
    fun `已接入或非 tailnet 目标照常发出`() {
        val reached = AtomicInteger()
        val offline = client(TailnetConnectGate(FakeConnector(TailnetPhase.FAILED, starts = false)), reached)
        offline.newCall(Request.Builder().url("https://api.deepseek.com/v1/models").build()).execute().close()
        val online = client(TailnetConnectGate(FakeConnector(TailnetPhase.RUNNING, starts = true)), reached)
        online.newCall(Request.Builder().url("https://gpu.tail1.ts.net/v1/models").build()).execute().close()
        assertThat(reached.get()).isEqualTo(2)
    }

    private fun Any.declaredHttpClient(): HttpClient {
        val field = generateSequence<Class<*>>(javaClass) { it.superclass }
            .firstNotNullOf { type -> type.declaredFields.firstOrNull { it.name == "httpClient" } }
        field.isAccessible = true
        return field.get(this) as HttpClient
    }

    @Test
    fun `所有云端协议、连接测试、向量化、重排与生图默认共用模型引擎`() {
        val protocols = listOf(
            ProtocolType.OpenAI_ChatCompletions,
            ProtocolType.OpenAI_Responses,
            ProtocolType.Anthropic_Messages,
            ProtocolType.Google_Gemini,
            ProtocolType.Google_VertexAI,
            ProtocolType.Generic_OpenAI_Compat,
            ProtocolType.DeepSeek,
        ).map { type -> ProtocolFactory.create(type, baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", model = "m") }
        val clients = protocols + listOf(
            ProviderConnectionProbe(),
            EmbeddingClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k"),
            RerankClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", modelId = "r"),
            ImageGenClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", model = "i"),
        )
        clients.forEach { owner ->
            assertThat(owner.declaredHttpClient().engine).isSameInstanceAs(ModelHttp.engine)
        }
    }

    @Test
    fun `各远程模型列表保留内置代理不可用错误且流式调用保留类型化终态`() = runBlocking {
        val types = listOf(
            ProtocolType.OpenAI_ChatCompletions,
            ProtocolType.OpenAI_Responses,
            ProtocolType.Anthropic_Messages,
            ProtocolType.Google_Gemini,
            ProtocolType.Generic_OpenAI_Compat,
        )
        types.forEach { type ->
            val protocol = ProtocolFactory.create(
                type, "https://provider.invalid/v1", "test-key", "model",
                useBuiltInProxy = true, providerId = "provider-test",
            )
            assertThrows(BuiltInProxyUnavailableException::class.java) {
                runBlocking { protocol.listModelDescriptors() }
            }
            val request = PromptRequest(listOf(ProtocolMessage(role = "user", content = "测试")), "model")
            assertThrows(BuiltInProxyUnavailableException::class.java) {
                runBlocking { protocol.sendPromptSync(request) }
            }
            val terminal = protocol.sendPrompt(request).toList().single() as StreamChunk.Error
            assertThat(terminal.cause).isInstanceOf(BuiltInProxyUnavailableException::class.java)
            assertThat(terminal.retryable).isFalse()
        }
    }

    @Test
    fun `连接探测向量化重排和生图从当前提供商读取代理策略`() {
        val baseUrl = "https://provider.invalid/v1"
        val config = UnifiedProviderConfig(
            ProtocolType.OpenAI_ChatCompletions, baseUrl, "test-key", "model",
            useBuiltInProxy = true, providerId = "provider-test",
        )
        val route = ResolvedProviderModel("model-id", "model", "provider-test", "测试提供商", config)
        val embedding = EmbeddingClient("", "", modelRouteResolver = { route })
        val reranker = RerankClient("", "", "", modelRouteResolver = { route })
        val image = ImageGenClient(baseUrl, "test-key", "model").withProviderRoute(route)
        val documents = listOf("a", "b").map { SearchResult(it, content = it, createdAt = 0, similarity = 1f) }
        val operations = listOf<suspend () -> Unit>(
            { ProviderConnectionProbe.processScoped.probe(config) },
            { embedding.embedDocuments(listOf("测试")) },
            { reranker.rerank("测试", documents, topK = 1) },
            { image.generate("测试") },
        )
        operations.forEach { call ->
            assertThrows(BuiltInProxyUnavailableException::class.java) { runBlocking { call() } }
        }
    }
}
