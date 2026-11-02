package com.promenar.infinity.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderProtocolMigrationTest {
    private lateinit var app: Application
    private lateinit var secrets: ProtocolMigrationSecretStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("infinity_provider", "infinity_settings").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        secrets = ProtocolMigrationSecretStore()
    }

    @Test
    fun `显示名恢复覆盖升级前的通用协议并写回稳定标识`() {
        seedMain("is4", "https://gateway.invalid", ProtocolType.Generic_OpenAI_Compat.legacyDisplayName)
        secrets.put(SecretCatalog.providerApiKey("default"), byteArrayOf(1, 2, 3))

        val manager = ProviderManager.createForTest(app, secrets)

        assertThat(manager.getMainProviderConfig()?.protocolType).isEqualTo(ProtocolType.Generic_OpenAI_Compat)
        assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo("Generic_OpenAI_Compat")
        assertThat(manager.getProviderSummary("default")?.protocolType).isEqualTo(ProtocolType.Generic_OpenAI_Compat)
        assertThat(manager.isPersistedProtocolUnsupported("default")).isFalse()
        assertThat(manager.getUnsupportedProviderSummary("default")).isNull()
        assertThat(manager.getMainProviderConfig()?.baseUrl).isEqualTo("https://gateway.invalid")
        assertThat(manager.getMainProviderConfig()?.model).isEqualTo("synthetic-model")
        assertThat(secrets.get(SecretCatalog.providerApiKey("default"))?.toList())
            .containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        mainPrefs().edit().putString("protocol_id_name", "DeepSeek").commit()
        assertThat(ProviderManager.createForTest(app, secrets).getMainProviderConfig()?.protocolType)
            .isEqualTo(ProtocolType.Generic_OpenAI_Compat)
    }

    @Test
    fun `稳定标识和旧别名优先于显示名且统一写回规范大小写`() {
        listOf(
            "generic_openai_compat" to "Generic_OpenAI_Compat",
            "openai" to "OpenAI_ChatCompletions",
            "ANTHROPIC" to "Anthropic_Messages",
            "VERTEX_AI" to "Google_VertexAI",
            "LOCAL" to "Local",
        ).forEach { (raw, expected) ->
            seedMain(raw, "https://gateway.invalid", "DeepSeek")
            val manager = ProviderManager.createForTest(app, secrets)
            assertThat(manager.getMainProviderConfig()?.protocolType).isEqualTo(ProtocolType.fromLegacyName(expected))
            assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo(expected)
        }
    }

    @Test
    fun `唯一混淆候选不依赖地址即可迁移`() {
        seedMain("yr4", "")
        val manager = ProviderManager.createForTest(app, secrets)

        assertThat(manager.getMainProviderConfig()?.protocolType).isEqualTo(ProtocolType.Generic_OpenAI_Compat)
        assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo("Generic_OpenAI_Compat")
    }

    @Test
    fun `is4使用主机消歧或最新发布表`() {
        listOf(
            "https://API.DEEPSEEK.COM:443/proxy" to "DeepSeek",
            // 非预设主机：自建或第三方网关，按通用兼容协议恢复。
            "https://gateway.invalid" to "Generic_OpenAI_Compat",
            // 无法解析主机：没有消歧依据，采用最新发布表。
            "://invalid" to "DeepSeek",
        ).forEach { (url, expected) ->
            seedMain("is4", url)
            val manager = ProviderManager.createForTest(app, secrets)
            assertThat(manager.getMainProviderConfig()?.protocolType).isEqualTo(ProtocolType.fromLegacyName(expected))
            assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo(expected)
        }
    }

    @Test
    fun `ks4使用空地址区分本地与通用协议`() {
        listOf("" to "Local", "https://gateway.invalid" to "Generic_OpenAI_Compat").forEach { (url, expected) ->
            seedExtra("ks4", url)
            val manager = ProviderManager.createForTest(app, secrets)
            assertThat(manager.getProviderConfig("extra")?.protocolType).isEqualTo(ProtocolType.fromLegacyName(expected))
            assertThat(extraPrefs().getString("extra_provider_0_protocol", null)).isEqualTo(expected)
            assertThat(manager.getProviderSummary("extra")?.protocolType).isEqualTo(ProtocolType.fromLegacyName(expected))
            assertThat(manager.isPersistedProtocolUnsupported("extra")).isFalse()
        }
    }

    @Test
    fun `旧版预设主机优先于最新发布表且不会仅按主机伪造候选`() {
        listOf(
            "https://dashscope.aliyuncs.com/compatible-mode/v1" to "Qwen_DashScope",
            "https://api.cohere.ai/v2" to "Cohere_Chat",
            "https://api.anthropic.com/v1" to "Anthropic_Messages",
            "https://api.openai.com/v1" to "Anthropic_Messages",
        ).forEach { (url, expected) ->
            seedMain("fs4", url)
            val manager = ProviderManager.createForTest(app, secrets)
            assertThat(manager.getMainProviderConfig()?.protocolType).isEqualTo(ProtocolType.fromLegacyName(expected))
            assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo(expected)
        }
    }

    @Test
    fun `旧type字段迁移到protocol字段并保留其它配置`() {
        seedExtra("ks4", "https://gateway.invalid", legacyType = true)
        val manager = ProviderManager.createForTest(app, secrets)

        assertThat(manager.getProviderConfig("extra")?.protocolType).isEqualTo(ProtocolType.Generic_OpenAI_Compat)
        assertThat(extraPrefs().getString("extra_provider_0_protocol", null)).isEqualTo("Generic_OpenAI_Compat")
        assertThat(extraPrefs().getString("extra_provider_0_name", null)).isEqualTo("测试提供商")
        assertThat(extraPrefs().getString("extra_provider_0_model", null)).isEqualTo("synthetic-model")
        assertThat(extraPrefs().getBoolean("extra_provider_0_enabled", true)).isFalse()
    }

    @Test
    fun `运行时直接读取和凭证保留更新均使用带上下文的解析`() {
        val manager = ProviderManager.createForTest(app, secrets)
        seedMain("is4", "https://gateway.invalid", ProtocolType.Generic_OpenAI_Compat.legacyDisplayName)
        manager.updateMainProvider(
            ProtocolType.Generic_OpenAI_Compat, "https://gateway.invalid", CredentialUpdate.Preserve, "synthetic-model",
        )
        assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo("Generic_OpenAI_Compat")

        seedExtra("ks4", "")
        assertThat(manager.getUnsupportedProviderSummary("extra")).isNull()
        assertThat(manager.getProviderConfig("extra")?.protocolType).isEqualTo(ProtocolType.Local)
    }

    @Test
    fun `不受支持的协议不改原值且保留主和额外提供商的凭据及字段`() {
        listOf("Future_Unknown", "is4", "ds4").forEach { raw ->
            val url = if (raw == "ds4") "https://gateway.invalid" else ""
            seedMain(raw, url)
            seedExtra(raw, url)
            listOf("default", "extra").forEach { id ->
                secrets.put(SecretCatalog.providerApiKey(id), byteArrayOf(1, 2, 3))
                secrets.put(SecretCatalog.vertexServiceAccount(id), byteArrayOf(4, 5, 6))
            }
            val manager = ProviderManager.createForTest(app, secrets)

            listOf("default", "extra").forEach { id ->
                assertThat(manager.getProviderConfig(id)).isNull()
                assertThat(manager.isPersistedProtocolUnsupported(id)).isTrue()
                val summary = manager.getUnsupportedProviderSummary(id)!!
                assertThat(summary.rawProtocolId).isEqualTo(raw)
                assertThat(summary.baseUrl).isEqualTo(url)
                assertThat(summary.model).isEqualTo("synthetic-model")
                assertThat(summary.hasApiKey).isTrue()
                assertThat(summary.hasVertexCredentials).isTrue()
                assertThat(secrets.get(SecretCatalog.providerApiKey(id))?.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
                assertThat(secrets.get(SecretCatalog.vertexServiceAccount(id))?.toList()).containsExactly(4.toByte(), 5.toByte(), 6.toByte()).inOrder()
            }
            assertThat(manager.unsupportedProviders.value.map { it.id }).containsExactly("default", "extra")
            assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo(raw)
            assertThat(extraPrefs().getString("extra_provider_0_protocol", null)).isEqualTo(raw)
        }
    }

    @Test
    fun `主和额外提供商写入使用稳定标识`() {
        val manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(ProtocolType.Google_VertexAI, "https://vertex.invalid", CredentialUpdate.Clear, "")
        manager.addProvider(
            ProviderListItem(id = "extra", name = "测试", baseUrl = "https://gateway.invalid", model = "", protocolType = ProtocolType.Generic_OpenAI_Compat),
            CredentialUpdate.Clear,
        )
        assertThat(mainPrefs().getString("protocol_id", null)).isEqualTo("Google_VertexAI")
        assertThat(extraPrefs().getString("extra_provider_0_protocol", null)).isEqualTo("Generic_OpenAI_Compat")
    }

    private fun mainPrefs() = app.getSharedPreferences("infinity_provider", 0)
    private fun extraPrefs() = app.getSharedPreferences("infinity_settings", 0)

    private fun seedMain(raw: String, baseUrl: String, displayName: String? = null) {
        mainPrefs().edit().clear()
            .putString("protocol_id", raw)
            .putString("protocol_id_name", displayName)
            .putString("base_url", baseUrl)
            .putString("model", "synthetic-model")
            .putString("provider_name", "测试主提供商")
            .commit()
    }

    private fun seedExtra(raw: String, baseUrl: String, legacyType: Boolean = false) {
        extraPrefs().edit().clear()
            .putInt("extra_providers_count", 1)
            .putString("extra_provider_0_id", "extra")
            .putString("extra_provider_0_name", "测试提供商")
            .putString(if (legacyType) "extra_provider_0_type" else "extra_provider_0_protocol", raw)
            .putString("extra_provider_0_base_url", baseUrl)
            .putString("extra_provider_0_model", "synthetic-model")
            .putBoolean("extra_provider_0_enabled", false)
            .commit()
    }
}

private class ProtocolMigrationSecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    override fun contains(id: SecretId): Boolean = values.containsKey(id)
}
