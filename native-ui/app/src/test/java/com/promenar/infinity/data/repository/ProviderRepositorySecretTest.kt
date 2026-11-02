package com.promenar.infinity.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderRepositorySecretTest {
    private lateinit var app: Application
    private lateinit var manager: ProviderManager
    private lateinit var secrets: MemorySecretStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("infinity_provider", "infinity_settings", "infinity_search", "infinity_backup_settings")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        secrets = MemorySecretStore()
        manager = ProviderManager.createForTest(app, secrets)
    }

    @Test
    fun `保存脱敏观察对象不会清除已有主 Provider Key`() = runTest {
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://before.invalid",
            CredentialUpdate.Replace("fake-existing-key"),
            "model-a",
            "Main",
        )
        val repository = ProviderRepository(manager)
        val redacted = repository.observeAll().first().single()

        repository.save(redacted.copy(baseUrl = "https://after.invalid"))

        assertThat(manager.getMainProviderConfig()!!.apiKey).isEqualTo("fake-existing-key")
        assertThat(manager.getMainProviderConfig()!!.baseUrl).isEqualTo("https://after.invalid")
    }

    @Test
    fun `已有额外 Provider 的脱敏对象跨凭证类别保存失败且所有状态不变`() = runTest {
        manager.addProvider(
            ProviderListItem(
                id = "existing-extra",
                name = "Existing",
                baseUrl = "https://openai.invalid",
                model = "model-a",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
            ),
            CredentialUpdate.Replace("fake-existing-key"),
        )
        val repository = ProviderRepository(manager)
        val redacted = repository.observeAll().first().single()
        val settingsPrefs = app.getSharedPreferences("infinity_settings", 0)
        val providersBefore = manager.providers.value.toList()
        val prefsBefore = settingsPrefs.all.toMap()
        val secretsBefore = secrets.snapshot()

        val result = runCatching {
            repository.save(
                redacted.copy(
                    protocolType = com.promenar.infinity.domain.model.ProtocolType.VERTEX_AI,
                    baseUrl = "https://vertex.invalid",
                    defaultModel = "vertex-model",
                )
            )
        }

        assertThat(result.isFailure).isTrue()
        assertThat(manager.providers.value).containsExactlyElementsIn(providersBefore).inOrder()
        assertThat(settingsPrefs.all).isEqualTo(prefsBefore)
        assertThat(secrets.snapshot()).isEqualTo(secretsBefore)
        assertThat(manager.getProviderConfig("existing-extra")!!.protocolType)
            .isEqualTo(ProtocolType.OpenAI_ChatCompletions)
    }

    @Test
    fun `Repository 连接测试复用唯一 probe 且 unsupported 不伪装连接失败`() = runTest {
        manager.addProvider(
            ProviderListItem(
                id = "qwen-provider",
                name = "Qwen",
                baseUrl = ProtocolType.Qwen_DashScope.defaultBaseUrl,
                model = "qwen-model",
                protocolType = ProtocolType.Qwen_DashScope,
            ),
            CredentialUpdate.Replace("fake-key"),
        )
        var requestCount = 0
        val repository = ProviderRepository(
            providerManager = manager,
            connectionProbe = ProviderConnectionProbe(
                HttpClient(MockEngine {
                    requestCount++
                    error("unsupported probe 不应发出请求")
                }),
            ),
        )

        val result = repository.testConnection("qwen-provider")

        assertThat(result.success).isFalse()
        assertThat(result.supported).isFalse()
        assertThat(result.error).isNull()
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `Repository connection probe cancellation is rethrown`() = runTest {
        manager.addProvider(
            ProviderListItem(
                id = "cancel-probe",
                name = "Cancel",
                baseUrl = "https://api.openai.com",
                model = "model",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
            ),
            CredentialUpdate.Replace("fake-key"),
        )
        val repository = ProviderRepository(
            providerManager = manager,
            connectionProbe = ProviderConnectionProbe(
                HttpClient(MockEngine { throw CancellationException("cancel probe") }),
            ),
        )

        val failure = try {
            repository.testConnection("cancel-probe")
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertThat(failure).isNotNull()
    }

    @Test
    fun `Repository model listing cancellation is rethrown`() = runTest {
        manager.addProvider(
            ProviderListItem(
                id = "cancel-models",
                name = "Cancel",
                baseUrl = "https://api.openai.com",
                model = "model",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
            ),
            CredentialUpdate.Replace("fake-key"),
        )
        val repository = ProviderRepository(
            providerManager = manager,
            modelListFetcher = { throw CancellationException("cancel models") },
        )

        val failure = try {
            repository.fetchModels("cancel-models")
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertThat(failure).isNotNull()
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
        fun snapshot(): Map<SecretId, String> = values.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
    }
}
