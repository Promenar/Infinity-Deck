package com.promenar.infinity.ui.welcome

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderSummary
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnboardingProviderConnectorTest {
    private class Fakes(var probeResult: ProviderConnectionProbeResult = ProviderConnectionProbeResult.Success) {
        val probed = mutableListOf<UnifiedProviderConfig>()
        val saved = mutableListOf<Triple<String?, ProtocolType, CredentialUpdate>>()
        val summaries = mutableMapOf<String, ProviderSummary>()

        suspend fun probe(config: UnifiedProviderConfig): ProviderConnectionProbeResult {
            probed += config
            return probeResult
        }

        suspend fun save(existing: String?, protocol: ProtocolType, baseUrl: String, credential: CredentialUpdate, model: String, name: String?): String {
            saved += Triple(existing, protocol, credential)
            val id = existing ?: "default"
            summaries[id] = ProviderSummary(id, name ?: protocol.legacyDisplayName, protocol, baseUrl, model,
                hasApiKey = credential is CredentialUpdate.Replace && protocol !is ProtocolType.Google_VertexAI,
                hasVertexCredentials = credential is CredentialUpdate.Replace && protocol is ProtocolType.Google_VertexAI)
            return id
        }
    }

    private suspend fun Fakes.connect(draft: OnboardingProviderDraft, existing: String? = null) =
        connectOnboardingProvider(
            draft = draft,
            existingProviderId = existing,
            probe = ::probe,
            save = ::save,
            readSummary = { summaries[it] },
        )

    private val openAi = OnboardingProviderDraft(ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", "sk-test", null)

    @Test fun `引导接受HTTP提供商地址并探测一次`() = runTest {
        val fakes = Fakes()
        assertThat(fakes.connect(openAi.copy(baseUrl = "http://192.168.1.2/v1")))
            .isInstanceOf(OnboardingConnectOutcome.Connected::class.java)
        assertThat(fakes.probed).hasSize(1)
    }

    @Test
    fun `一次连接只探测一次并在成功后保存`() = runTest {
        val fakes = Fakes()

        val outcome = fakes.connect(openAi)

        assertThat(outcome).isEqualTo(OnboardingConnectOutcome.Connected("default"))
        assertThat(fakes.probed).hasSize(1)
        assertThat(fakes.probed.single().apiKey).isEqualTo("sk-test")
        assertThat(fakes.saved).containsExactly(
            Triple(null, ProtocolType.OpenAI_ChatCompletions, CredentialUpdate.Replace("sk-test")),
        )
    }

    @Test
    fun `探测失败不写入任何提供商`() = runTest {
        val fakes = Fakes(ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED, 401))

        val outcome = fakes.connect(openAi)

        assertThat(outcome).isEqualTo(
            OnboardingConnectOutcome.Failed(ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED, 401),
        )
        assertThat(fakes.probed).hasSize(1)
        assertThat(fakes.saved).isEmpty()
    }

    @Test
    fun `协议没有可用探测时仍只保存一次并交给选模型验证`() = runTest {
        val fakes = Fakes(ProviderConnectionProbeResult.Unsupported)

        val outcome = fakes.connect(openAi)

        assertThat(outcome).isEqualTo(OnboardingConnectOutcome.Connected("default"))
        assertThat(fakes.probed).hasSize(1)
        assertThat(fakes.saved).hasSize(1)
    }

    @Test
    fun `返回后重新连接更新已保存的同一提供商`() = runTest {
        val fakes = Fakes()

        fakes.connect(openAi)
        val outcome = fakes.connect(openAi.copy(secret = "sk-new"), existing = "default")

        assertThat(outcome).isEqualTo(OnboardingConnectOutcome.Connected("default"))
        assertThat(fakes.saved.map { it.first }).containsExactly(null, "default").inOrder()
    }

    @Test
    fun `Vertex 的凭证作为服务账号发送给探测`() = runTest {
        val fakes = Fakes()

        fakes.connect(OnboardingProviderDraft(ProtocolType.Google_VertexAI, "https://us-central1-aiplatform.googleapis.com", "{\"type\":\"service_account\"}", null))

        assertThat(fakes.probed.single().apiKey).isEmpty()
        assertThat(fakes.probed.single().serviceAccountJson).isEqualTo("{\"type\":\"service_account\"}")
    }

    @Test
    fun `空密钥或无效地址不发起探测`() = runTest {
        val fakes = Fakes()

        assertThat(fakes.connect(openAi.copy(secret = "  "))).isEqualTo(OnboardingConnectOutcome.Invalid)
        assertThat(fakes.connect(openAi.copy(baseUrl = "ftp://api.openai.com"))).isEqualTo(OnboardingConnectOutcome.Invalid)
        assertThat(fakes.probed).isEmpty()
        assertThat(fakes.saved).isEmpty()
    }

    @Test
    fun `保存后读回的配置与输入不一致时视为失败`() = runTest {
        val fakes = Fakes()
        val outcome = connectOnboardingProvider(
            draft = openAi,
            existingProviderId = null,
            probe = fakes::probe,
            save = fakes::save,
            readSummary = { null },
        )

        assertThat(outcome).isEqualTo(OnboardingConnectOutcome.Failed(null, null))
    }

    @Test
    fun `本地推理没有已加载模型时不保存`() = runTest {
        val fakes = Fakes()

        assertThat(connectOnboardingLocalProvider(emptyList(), null, fakes::save))
            .isEqualTo(OnboardingConnectOutcome.LocalModelMissing)
        assertThat(connectOnboardingLocalProvider(listOf("qwen3-1.7b"), null, fakes::save))
            .isEqualTo(OnboardingConnectOutcome.Connected("default"))
        assertThat(fakes.saved.single().second).isEqualTo(ProtocolType.Local)
        assertThat(fakes.probed).isEmpty()
    }
}
