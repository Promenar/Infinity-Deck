package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.model.ModelInfo
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class ProviderRequestRouterTest {
    @Test
    fun `ProviderResolution失败字符串化不得泄漏模型与provider标识`() {
        val failure = ProviderResolution.Failure(
            ProviderResolutionError.MODEL_NOT_FOUND,
            "private-model-marker",
            "private-provider-marker",
        )

        assertThat(failure.toString()).doesNotContain("private-model-marker")
        assertThat(failure.toString()).doesNotContain("private-provider-marker")
        assertThat(failure.toString()).contains("MODEL_NOT_FOUND")
    }
    private val models = linkedMapOf<String, ModelInfo>()
    private val providers = linkedMapOf<String, ProviderListItem>()
    private val configs = linkedMapOf<String, ProviderConfig>()
    private val createdConfigs = mutableListOf<UnifiedProviderConfig>()

    private val router = DefaultProviderRequestRouter(
        modelResolver = models::get,
        providerResolver = providers::get,
        configResolver = configs::get,
        clientFactory = { config ->
            createdConfigs += config
            UnifiedLlmClient(providerConfigResolver = { config })
        },
    )

    @Test
    fun `本地推理不可用具有独立且可呈现的解析错误`() {
        assertThat(ProviderResolutionError.valueOf("LOCAL_INFERENCE_UNAVAILABLE").name)
            .isEqualTo("LOCAL_INFERENCE_UNAVAILABLE")
    }

    @Test
    fun `本地推理能力关闭时旧 Local 配置明确失败且不创建客户端`() {
        seedLocal()
        val unavailableRouter = DefaultProviderRequestRouter(
            modelResolver = models::get,
            providerResolver = providers::get,
            configResolver = configs::get,
            localInferenceAvailable = { false },
            clientFactory = { error("不可创建客户端") },
        )

        val failure = unavailableRouter.resolve("local::local-model") as ProviderResolution.Failure

        assertThat(failure.reason).isEqualTo(ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE)
        assertThat(failure.providerId).isEqualTo("local")
    }

    @Test
    fun `两个 Provider 的同名远端模型按复合标识独立解析`() {
        seed("provider-a", "same-model", "key-a")
        seed("provider-b", "same-model", "key-b")

        val a = router.resolve("provider-a::same-model") as ProviderResolution.Success
        val b = router.resolve("provider-b::same-model") as ProviderResolution.Success
        router.createClient(a.value)
        router.createClient(b.value)

        assertThat(a.value.remoteModelId).isEqualTo("same-model")
        assertThat(b.value.remoteModelId).isEqualTo("same-model")
        assertThat(createdConfigs.map { it.apiKey }).containsExactly("key-a", "key-b").inOrder()
    }

    @Test
    fun `Provider 删除后旧模型不会回退到其他 Provider`() {
        seed("deleted", "model", "key")
        providers.remove("deleted")
        configs.remove("deleted")

        assertFailure("deleted::model", ProviderResolutionError.PROVIDER_NOT_FOUND)
    }

    @Test
    fun `缺少 Key 在创建客户端之前失败`() {
        seed("p", "model", "")

        assertFailure("p::model", ProviderResolutionError.API_KEY_MISSING)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `Provider 列表与配置协议不一致时失败`() {
        seed("p", "model", "key")
        configs["p"] = configs.getValue("p").copy(protocolType = ProtocolType.Anthropic_Messages)

        assertFailure("p::model", ProviderResolutionError.PROTOCOL_MISMATCH)
    }

    @Test fun `公网局域网和本机HTTP端点均可解析`() {
        for ((index, host) in listOf("127.0.0.1", "192.168.1.10", "api.example.test", "[::1]").withIndex()) {
            val id = "http$index"
            seed(id, "model", "key", baseUrl = "http://$host:1337/v1")
            assertThat(router.resolve("$id::model")).isInstanceOf(ProviderResolution.Success::class.java)
        }
    }

    @Test fun `无效端点在网络前失败`() {
        seed("p", "model", "key", baseUrl = "ftp://invalid.test")
        assertFailure("p::model", ProviderResolutionError.BASE_URL_INVALID)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `模型复合 ID 与归属不一致时失败`() {
        seed("p", "model", "key")
        models["other::model"] = model("p", "model").copy(id = "other::model")

        assertFailure("other::model", ProviderResolutionError.MODEL_PROVIDER_MISMATCH)
    }

    @Test
    fun `已解析请求冻结当时配置而下一次解析读取新配置`() {
        seed("p", "model", "old-key")
        val first = (router.resolve("p::model") as ProviderResolution.Success).value
        configs["p"] = configs.getValue("p").copy(apiKey = "new-key")
        val second = (router.resolve("p::model") as ProviderResolution.Success).value

        router.createClient(first)
        router.createClient(second)

        assertThat(createdConfigs.map { it.apiKey }).containsExactly("old-key", "new-key").inOrder()
    }

    @Test
    fun `禁用 Provider 在网络前失败`() {
        seed("p", "model", "key")
        providers["p"] = providers.getValue("p").copy(enabled = false)

        assertFailure("p::model", ProviderResolutionError.PROVIDER_DISABLED)
    }

    @Test
    fun `禁用模型在网络前 typed fail`() {
        seed("p", "model", "key")
        models["p::model"] = models.getValue("p::model").copy(enabled = false)

        assertFailure("p::model", ProviderResolutionError.MODEL_DISABLED)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `Vertex 凭证缺字段或私钥不可解析时不创建客户端且不泄露原文`() {
        val secretMarker = "never-print-private-material"
        seedVertex("vertex", "gemini", """{"project_id":"project","client_email":"a@b","private_key":"$secretMarker"}""")

        val failure = router.resolve("vertex::gemini") as ProviderResolution.Failure

        assertThat(failure.reason).isEqualTo(ProviderResolutionError.VERTEX_CREDENTIAL_INVALID)
        assertThat(failure.toString()).doesNotContain(secretMarker)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `Vertex 非字符串凭证字段稳定映射为凭证错误而不异常逸出`() {
        seedVertex(
            "vertex-typed",
            "gemini",
            """{"project_id":"project","client_email":{},"private_key":"unused"}""",
        )

        val failure = router.resolve("vertex-typed::gemini") as ProviderResolution.Failure

        assertThat(failure.reason).isEqualTo(ProviderResolutionError.VERTEX_CREDENTIAL_INVALID)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `Vertex 完整有效凭证在创建客户端前解析出 projectId`() {
        val privateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(privateKey) +
            "\n-----END PRIVATE KEY-----"
        privateKey.fill(0)
        seedVertex(
            "vertex",
            "gemini",
            """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}""",
        )

        val success = router.resolve("vertex::gemini") as ProviderResolution.Success

        assertThat(success.value.config.projectId).isEqualTo("project-safe")
        assertThat(success.value.config.serviceAccountJson).contains("service@example.invalid")
        assertThat(success.value.config.baseUrl)
            .isEqualTo("https://us-central1-aiplatform.googleapis.com")
        assertThat(success.value.config.defaultModel).isEqualTo("gemini")
        assertThat(success.value.config.location).isEqualTo(VERTEX_DEFAULT_LOCATION)
    }

    @Test
    fun `Vertex attacker origin is rejected before client creation`() {
        val privateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(privateKey) +
            "\n-----END PRIVATE KEY-----"
        privateKey.fill(0)
        seedVertex(
            "vertex-attacker",
            "gemini",
            """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}""",
            baseUrl = "https://attacker.example.invalid",
        )

        assertFailure("vertex-attacker::gemini", ProviderResolutionError.BASE_URL_INVALID)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `历史 Cohere 与 Yi 配置在路由阶段 typed fail closed`() {
        listOf(ProtocolType.Cohere_Chat, ProtocolType.Yi_ZeroOne).forEachIndexed { index, protocol ->
            val providerId = "historical-$index"
            val modelId = stableModelId(providerId, "legacy-model")
            providers[providerId] = ProviderListItem(
                id = providerId,
                name = providerId,
                protocolType = protocol,
            )
            configs[providerId] = ProviderConfig(
                protocolType = protocol,
                baseUrl = protocol.defaultBaseUrl,
                apiKey = "fake-key",
                model = "legacy-model",
            )
            models[modelId] = model(providerId, "legacy-model")

            assertFailure(modelId, ProviderResolutionError.PROTOCOL_UNSUPPORTED)
        }
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `未知持久化协议在读取配置前映射为PROTOCOL_UNSUPPORTED`() {
        seed("unknown-provider", "legacy-model", "fake-key")
        val failClosedRouter = DefaultProviderRequestRouter(
            modelResolver = models::get,
            providerResolver = providers::get,
            configResolver = configs::get,
            unsupportedPersistedProtocolResolver = { it == "unknown-provider" },
            clientFactory = { error("未知协议不得创建客户端") },
        )

        val failure = failClosedRouter.resolve("unknown-provider::legacy-model") as ProviderResolution.Failure

        assertThat(failure.reason).isEqualTo(ProviderResolutionError.PROTOCOL_UNSUPPORTED)
    }

    @Test
    fun `本地协议允许空 endpoint 与空 Key`() {
        seedLocal()

        val result = router.resolve("local::local-model") as ProviderResolution.Success

        assertThat(result.value.config.protocolType).isEqualTo(ProtocolType.Local)
        assertThat(result.value.remoteModelId).isEqualTo("local-model")
    }

    private fun seedLocal() {
        val providerId = "local"
        providers[providerId] = ProviderListItem(
            id = providerId,
            name = "本地",
            protocolType = ProtocolType.Local,
        )
        configs[providerId] = ProviderConfig(
            protocolType = ProtocolType.Local,
            model = "local-model",
        )
        models["local::local-model"] = model(providerId, "local-model")
    }

    private fun seed(providerId: String, remoteModelId: String, key: String, baseUrl: String = "https://$providerId.invalid") {
        providers[providerId] = ProviderListItem(
            id = providerId,
            name = providerId,
            protocolType = ProtocolType.OpenAI_ChatCompletions,
        )
        configs[providerId] = ProviderConfig(
            protocolType = ProtocolType.OpenAI_ChatCompletions,
            baseUrl = baseUrl,
            apiKey = key,
            model = remoteModelId,
        )
        models[stableModelId(providerId, remoteModelId)] = model(providerId, remoteModelId)
    }

    private fun seedVertex(
        providerId: String,
        remoteModelId: String,
        credentials: String,
        baseUrl: String = "https://us-central1-aiplatform.googleapis.com",
    ) {
        providers[providerId] = ProviderListItem(
            id = providerId,
            name = providerId,
            protocolType = ProtocolType.Google_VertexAI,
        )
        configs[providerId] = ProviderConfig(
            protocolType = ProtocolType.Google_VertexAI,
            baseUrl = baseUrl,
            model = remoteModelId,
            vertexServiceAccountJson = credentials,
        )
        models[stableModelId(providerId, remoteModelId)] = model(providerId, remoteModelId)
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun model(providerId: String, remoteModelId: String) = ModelInfo(
        name = remoteModelId,
        id = stableModelId(providerId, remoteModelId),
        remoteModelId = remoteModelId,
        description = "",
        enabled = true,
        providerName = providerId,
        providerId = providerId,
    )

    private fun assertFailure(modelId: String, reason: ProviderResolutionError) {
        val failure = router.resolve(modelId) as ProviderResolution.Failure
        assertThat(failure.reason).isEqualTo(reason)
    }
}
