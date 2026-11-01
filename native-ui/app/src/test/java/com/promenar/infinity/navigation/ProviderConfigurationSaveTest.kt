package com.promenar.infinity.navigation

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProviderConfigurationSaveTest {
    private lateinit var app: InfinityApplication
    private lateinit var manager: ProviderManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf("infinity_provider", "infinity_settings", "infinity_provider_status").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        val secrets = object : SecretStore {
            private val values = mutableMapOf<SecretId, ByteArray>()
            override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
            override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
            override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
            override fun contains(id: SecretId): Boolean = id in values
        }
        manager = ProviderManager.createForTest(context, secrets)
        mockkObject(ProviderManager.Companion)
        every { ProviderManager.getInstance() } returns manager
        val real = InfinityApplication()
        ContextWrapper::class.java.getDeclaredField("mBase").apply {
            isAccessible = true
            set(real, context)
        }
        app = spyk(real)
        // 保存测试保留真实持久化与缓存失效，仅替换需要完整启动环境的客户端重建。
        every { app.rebuildEmbeddingClient() } returns Unit
        every { app.rebuildRerankClient() } returns Unit
    }

    @After
    fun tearDown() { unmockkObject(ProviderManager.Companion) }

    @Test
    fun `首个用户提供商删除后新增提供商重新占用主槽位`() = runTest {
        assertThat(manager.providers.value).isEmpty()
        assertThat(save("first")).isEqualTo("default")
        val extraId = save("extra")
        assertThat(extraId).startsWith("extra_")
        val extraConfig = manager.getProviderConfig(extraId)

        manager.deleteProvider("default")

        assertThat(manager.getMainProviderConfig()).isNull()
        assertThat(save("replacement")).isEqualTo("default")
        assertThat(manager.getMainConfiguredModelId()).isEqualTo("default::replacement")
        assertThat(manager.getProviderConfig(extraId)).isEqualTo(extraConfig)
        assertThat(manager.providers.value.map { it.id }).containsExactly("default", extraId)
    }

    private suspend fun save(model: String): String = saveProviderConfiguration(
        app = app,
        providerId = null,
        protocolType = ProtocolType.OpenAI_ChatCompletions,
        baseUrl = "https://$model.invalid/v1",
        credential = CredentialUpdate.Replace("fake-$model"),
        model = model,
        name = model,
    )
}
