package com.promenar.infinity.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.ProviderConnectionOutcome
import com.promenar.infinity.data.local.ProviderStatusStore
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.model.catalog.BundledModelCatalog
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.DefaultProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.settings.persistVerifiedProviderConnection
import com.promenar.infinity.ui.settings.requiresLegacyCredentialMigration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderManagerTest {

    @Test
    fun `未知历史协议仅在确有旧凭证时要求替换或清除`() {
        assertThat(requiresLegacyCredentialMigration(true, true, CredentialUpdate.Preserve)).isTrue()
        assertThat(requiresLegacyCredentialMigration(true, false, CredentialUpdate.Preserve)).isFalse()
        assertThat(requiresLegacyCredentialMigration(true, true, CredentialUpdate.Clear)).isFalse()
        assertThat(
            requiresLegacyCredentialMigration(
                true,
                true,
                CredentialUpdate.Replace("replacement"),
            ),
        ).isFalse()
    }
    private lateinit var app: Application
    private lateinit var manager: ProviderManager
    private lateinit var secrets: TestSecretStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("infinity_provider", "infinity_settings", "infinity_provider_status").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        secrets = TestSecretStore()
        manager = ProviderManager.createForTest(app, secrets)
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://provider.invalid",
            CredentialUpdate.Replace("test-key"),
            "remote-model",
            "同名提供商",
        )
    }

    @Test
    fun `删除提供商会清除连接和同步状态并保留主提供商状态`() {
        addExtraProviderWithModel()
        manager.recordProviderConnectionTest("extra-a", ProviderConnectionProbeResult.Success)
        manager.recordProviderModelSync("extra-a")
        manager.recordProviderConnectionTest("default", ProviderConnectionProbeResult.Unsupported)
        manager.recordProviderModelSync("default")
        assertThat(manager.providerStatuses.value["extra-a"]?.lastConnection?.outcome)
            .isEqualTo(ProviderConnectionOutcome.SUCCESS)
        assertThat(manager.providerStatuses.value["extra-a"]?.lastModelSyncAtMillis).isNotNull()

        manager.deleteProvider("extra-a")

        assertThat(manager.providerStatuses.value.keys).containsExactly("default")
        val restored = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(restored.providerStatuses.value.keys).containsExactly("default")
        assertThat(restored.providerStatuses.value["default"]?.lastConnection?.outcome)
            .isEqualTo(ProviderConnectionOutcome.UNSUPPORTED)
        assertThat(restored.providerStatuses.value["default"]?.lastModelSyncAtMillis).isNotNull()
    }

    @Test
    fun `重载提供商只保留已保存提供商的状态`() {
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .putInt("extra_providers_count", 1)
            .putString("extra_provider_0_id", "unsupported")
            .putString("extra_provider_0_protocol", "Future_Protocol")
            .commit()
        val statusStore = ProviderStatusStore(app.getSharedPreferences("injected_provider_status", 0)) { 42L }
        listOf("default", "unsupported", "orphan").forEach { statusStore.recordModelSync(it) }
        val isolated = ProviderManager.createForTest(app, TestSecretStore(), providerStatusStore = statusStore)
        assertThat(isolated.providerStatuses.value.keys).containsExactly("default", "unsupported")
        assertThat(isolated.providerStatuses.value["unsupported"]?.lastModelSyncAtMillis).isEqualTo(42L)

        prefs.edit().putInt("extra_providers_count", 0).commit()
        app.getSharedPreferences("infinity_provider", 0).edit().clear().commit()
        isolated.loadProviders()

        assertThat(isolated.providerStatuses.value).isEmpty()
        assertThat(statusStore.statuses.value).isEmpty()
    }

    @Test
    fun `模型超时与预设模型共用偏好且可持久化往返`() {
        manager.setModelTimeout(com.promenar.infinity.data.remote.ModelTimeoutKind.LLM, 137)
        manager.setModelTimeout(com.promenar.infinity.data.remote.ModelTimeoutKind.EMBEDDING, 89)
        val restored = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(restored.modelTimeouts.value)
            .isEqualTo(com.promenar.infinity.data.remote.ModelTimeoutSettings(llmSeconds = 137, embeddingSeconds = 89))
        assertThat(app.getSharedPreferences("infinity_settings", 0).getInt("model_timeout_llm_seconds", -1)).isEqualTo(137)
    }

    @Test
    fun `新增模型使用稳定复合标识并保留远端模型 ID`() {
        val stored = manager.providerModels.value.single()
        assertThat(stored.id).isEqualTo("default::remote-model")
        assertThat(stored.remoteModelId).isEqualTo("remote-model")
        assertThat(manager.getMainConfiguredModelId()).isEqualTo("default::remote-model")
        assertThat(manager.getProviderConfigByModelId(stored.id)?.apiKey).isEqualTo("test-key")

        manager.setPresetModel("summary", stored.id)
        assertThat(manager.summaryModelId.value).isEqualTo("default::remote-model")
        assertThat(runCatching { manager.setPresetModel("summary", "remote-model") }.isFailure).isTrue()
    }

    @Test
    fun `连接探测后修改地址和 Key 必须保存且可由 ProviderManager 反读`() = runTest {
        val updatedUrl = "https://updated-provider.invalid/v1"
        val updatedKey = "updated-key"

        val savedId = persistVerifiedProviderConnection(
            protocolType = ProtocolType.OpenAI_ChatCompletions,
            baseUrl = updatedUrl,
            credential = CredentialUpdate.Replace(updatedKey),
            model = "",
            name = "更新后的提供商",
            onSave = { protocol, url, credential, model, name ->
                manager.updateMainProvider(protocol, url, credential, model, name)
                "default"
            },
            readSummary = manager::getProviderSummary,
        )

        assertThat(savedId).isEqualTo("default")
        assertThat(manager.getProviderSummary("default")?.baseUrl).isEqualTo(updatedUrl)
        assertThat(manager.getMainProviderConfig()?.apiKey).isEqualTo(updatedKey)
    }

    @Test
    fun `跨偏好与SecretStore写入期间发布奇数revision完成后恢复偶数`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isolated = ProviderManager.createForTest(
            app = app,
            secretStore = TestSecretStore(),
            afterProviderConfigurationWriteStarted = {
                assertThat(ProviderConfigurationRevision.current()).isNotEqualTo(0L)
                assertThat(ProviderConfigurationRevision.current() % 2L).isEqualTo(1L)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            isolated.updateMainProvider(
                ProtocolType.OpenAI_ChatCompletions,
                "https://revision.invalid/v1/chat/completions",
                CredentialUpdate.Replace("revision-key"),
                "revision-model",
            )
        }

        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(ProviderConfigurationRevision.current() % 2L).isEqualTo(1L)
            assertThat(app.getSharedPreferences("infinity_provider", 0).all.keys)
                .doesNotContain("provider_configuration_revision")
            release.countDown()
            future.get(5, TimeUnit.SECONDS)
            assertThat(ProviderConfigurationRevision.current() % 2L).isEqualTo(0L)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `主与额外Provider增改删均发布完整稳定revision`() {
        fun assertStableMutation(block: () -> Unit) {
            val before = ProviderConfigurationRevision.current()
            assertThat(before % 2L).isEqualTo(0L)
            block()
            val after = ProviderConfigurationRevision.current()
            assertThat(after).isGreaterThan(before)
            assertThat(after % 2L).isEqualTo(0L)
        }

        assertStableMutation {
            manager.updateMainProvider(
                ProtocolType.OpenAI_ChatCompletions,
                "https://main-revision.invalid/v1/chat/completions",
                CredentialUpdate.Replace("main-revision-key"),
                "main-revision-model",
            )
        }
        val extra = ProviderListItem(
            id = "revision-extra",
            name = "Revision Extra",
            baseUrl = "https://extra-revision.invalid/v1/chat/completions",
            model = "extra-model",
            protocolType = ProtocolType.OpenAI_ChatCompletions,
        )
        assertStableMutation { manager.addProvider(extra, CredentialUpdate.Replace("extra-key")) }
        assertStableMutation {
            manager.updateExtraProvider(
                extra.id,
                extra.copy(name = "Revision Extra Updated"),
                CredentialUpdate.Preserve,
            )
        }
        assertStableMutation { manager.deleteProvider(extra.id) }
    }

    @Test
    fun `缺失 providerId 不按 providerName 模糊回退`() {
        manager.addModel(model(providerId = null, id = "unowned"))

        assertThat(manager.getProviderConfigByModelId("unowned")).isNull()
    }

    @Test
    fun `v0_2 不对旧裸模型 ID 做半迁移且 Router typed fail`() {
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .clear()
            .putStringSet("all_models", setOf("legacy-model"))
            .putStringSet("enabled_models", setOf("legacy-model"))
            .putString("all_models_order", "legacy-model")
            .putString("model_info_legacy-model_name", "Legacy")
            .putString("model_info_legacy-model_provider_id", "default")
            .putString("model_info_legacy-model_provider", "同名提供商")
            .commit()
        val isolated = ProviderManager.createForTest(app, TestSecretStore()).also {
            it.updateMainProvider(
                ProtocolType.OpenAI_ChatCompletions,
                "https://provider.invalid",
                CredentialUpdate.Replace("test-key"),
                "remote-model",
                "同名提供商",
            )
        }

        assertThat(isolated.providerModels.value.map { it.id })
            .containsAtLeast("legacy-model", "default::remote-model")
        val failure = DefaultProviderRequestRouter(isolated).resolve("legacy-model") as ProviderResolution.Failure
        assertThat(failure.reason).isEqualTo(ProviderResolutionError.MODEL_PROVIDER_MISMATCH)
    }

    @Test
    fun `未知主Provider保持可见可恢复且Router在网络前typed fail closed`() {
        app.getSharedPreferences("infinity_provider", 0).edit()
            .putString("protocol_id", "Future_Unknown_Protocol")
            .remove("protocol_id_name")
            .putString("provider_name", "历史主提供商")
            .putString("base_url", "https://legacy.invalid/v1")
            .commit()

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())

        assertThat(reloaded.getMainProviderConfig()).isNull()
        assertThat(reloaded.providers.value.map { it.id }).doesNotContain("default")
        assertThat(reloaded.unsupportedProviders.value).containsExactly(
            UnsupportedProviderListItem(
                id = "default",
                name = "历史主提供商",
                rawProtocolId = "Future_Unknown_Protocol",
                enabled = true,
                hasApiKey = false,
                hasVertexCredentials = false,
            ),
        )
        assertThat(reloaded.getUnsupportedProviderSummary("default")?.baseUrl)
            .isEqualTo("https://legacy.invalid/v1")
        val failure = DefaultProviderRequestRouter(reloaded)
            .resolve("default::remote-model") as ProviderResolution.Failure
        assertThat(failure.reason).isEqualTo(ProviderResolutionError.PROTOCOL_UNSUPPORTED)
    }

    @Test
    fun `更新已知额外Provider不会静默改写或删除未知历史Provider`() {
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .putInt("extra_providers_count", 1)
            .putString("extra_provider_0_id", "unknown-extra")
            .putString("extra_provider_0_name", "Unknown Extra")
            .putString("extra_provider_0_protocol", "Future_Unknown_Protocol")
            .putString("extra_provider_0_base_url", "https://unknown.invalid")
            .putString("extra_provider_0_model", "legacy-model")
            .commit()
        val isolated = ProviderManager.createForTest(app, TestSecretStore())

        isolated.addProvider(
            ProviderListItem(
                id = "known-extra",
                name = "Known Extra",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
                baseUrl = "https://known.invalid/v1/chat/completions",
                model = "known-model",
            ),
            CredentialUpdate.Replace("known-key"),
        )

        assertThat(prefs.getInt("extra_providers_count", 0)).isEqualTo(2)
        val unknownIndex = (0 until 2).single {
            prefs.getString("extra_provider_${it}_id", null) == "unknown-extra"
        }
        assertThat(prefs.getString("extra_provider_${unknownIndex}_protocol", null))
            .isEqualTo("Future_Unknown_Protocol")
        assertThat(isolated.isPersistedProtocolUnsupported("unknown-extra")).isTrue()
    }

    @Test
    fun `未知额外Provider以typed只读行可见且删除后完整清理并允许复用ID`() {
        val providerId = "unknown-extra"
        val modelId = "$providerId::legacy-model"
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .clear()
            .putInt("extra_providers_count", 1)
            .putString("extra_provider_0_id", providerId)
            .putString("extra_provider_0_name", "Unknown Extra")
            .putString("extra_provider_0_protocol", "Future_Unknown_Protocol")
            .putString("extra_provider_0_base_url", "https://must-not-be-exposed.invalid")
            .putString("extra_provider_0_model", "legacy-model")
            .putBoolean("extra_provider_0_enabled", true)
            .putStringSet("all_models", setOf(modelId))
            .putStringSet("enabled_models", setOf(modelId))
            .putString("all_models_order", modelId)
            .putString("model_info_${modelId}_name", "Legacy Model")
            .putString("model_info_${modelId}_provider", "Unknown Extra")
            .putString("model_info_${modelId}_remote_model_id", "legacy-model")
            .putString("preset_summary_model", modelId)
            .putStringSet("suppressed_provider_models", setOf(providerId))
            .commit()
        val secrets = TestSecretStore().apply {
            put(SecretCatalog.providerApiKey(providerId), "hidden-key".toByteArray())
            put(SecretCatalog.vertexServiceAccount(providerId), "hidden-json".toByteArray())
        }

        val isolated = ProviderManager.createForTest(app, secrets)

        assertThat(isolated.providers.value.map { it.id }).doesNotContain(providerId)
        assertThat(isolated.unsupportedProviders.value).containsExactly(
            UnsupportedProviderListItem(
                id = providerId,
                name = "Unknown Extra",
                rawProtocolId = "Future_Unknown_Protocol",
                enabled = true,
                hasApiKey = true,
                hasVertexCredentials = true,
            ),
        )
        assertThat(runCatching {
            isolated.addProvider(knownProvider(providerId), CredentialUpdate.Clear)
        }.isFailure).isTrue()

        isolated.deleteProvider(providerId)

        assertThat(isolated.unsupportedProviders.value).isEmpty()
        assertThat(isolated.providerModels.value.map { it.id }).doesNotContain(modelId)
        assertThat(isolated.summaryModelId.value).isEmpty()
        assertThat(prefs.getStringSet("all_models", emptySet())).doesNotContain(modelId)
        assertThat(prefs.getStringSet("enabled_models", emptySet())).doesNotContain(modelId)
        assertThat(prefs.getString("all_models_order", "").orEmpty()).doesNotContain(modelId)
        assertThat(prefs.getStringSet("suppressed_provider_models", emptySet()))
            .doesNotContain(providerId)
        assertThat(prefs.all.keys.any { it.startsWith("extra_provider_0_") }).isFalse()
        assertThat(prefs.all.keys.any { it.startsWith("model_info_${modelId}_") }).isFalse()
        assertThat(secrets.contains(SecretCatalog.providerApiKey(providerId))).isFalse()
        assertThat(secrets.contains(SecretCatalog.vertexServiceAccount(providerId))).isFalse()
        assertThat(isolated.isPersistedProtocolUnsupported(providerId)).isFalse()

        isolated.addProvider(knownProvider(providerId), CredentialUpdate.Clear)
        assertThat(isolated.providers.value.map { it.id }).contains(providerId)
    }

    @Test
    fun `删除未知Provider会压缩保留项并清理旧尾槽位`() {
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .clear()
            .putInt("extra_providers_count", 2)
            .putString("extra_provider_0_id", "unknown-a")
            .putString("extra_provider_0_name", "Unknown A")
            .putString("extra_provider_0_protocol", "Future_A")
            .putString("extra_provider_0_base_url", "https://a.invalid")
            .putString("extra_provider_0_model", "model-a")
            .putBoolean("extra_provider_0_enabled", false)
            .putString("extra_provider_1_id", "unknown-b")
            .putString("extra_provider_1_name", "Unknown B")
            .putString("extra_provider_1_protocol", "Future_B")
            .putString("extra_provider_1_base_url", "https://b.invalid")
            .putString("extra_provider_1_model", "model-b")
            .putBoolean("extra_provider_1_enabled", true)
            .commit()
        val isolated = ProviderManager.createForTest(app, TestSecretStore())

        isolated.deleteProvider("unknown-a")

        assertThat(prefs.getInt("extra_providers_count", -1)).isEqualTo(1)
        assertThat(prefs.getString("extra_provider_0_id", null)).isEqualTo("unknown-b")
        assertThat(prefs.getString("extra_provider_0_name", null)).isEqualTo("Unknown B")
        assertThat(prefs.getString("extra_provider_0_protocol", null)).isEqualTo("Future_B")
        assertThat(prefs.getString("extra_provider_0_base_url", null)).isEqualTo("https://b.invalid")
        assertThat(prefs.getString("extra_provider_0_model", null)).isEqualTo("model-b")
        assertThat(prefs.getBoolean("extra_provider_0_enabled", false)).isTrue()
        assertThat(prefs.all.keys.any { it.startsWith("extra_provider_1_") }).isFalse()
    }

    @Test
    fun `provider scoped disable only changes the requested provider`() {
        addExtraProviderWithModel()
        val defaultId = "default::remote-model"
        val extraId = "extra-a::extra-model"

        manager.disableAllModels("default")

        assertThat(manager.providerModels.value.single { it.id == defaultId }.enabled).isFalse()
        assertThat(manager.providerModels.value.single { it.id == extraId }.enabled).isTrue()
    }

    @Test
    fun `provider scoped delete preserves hidden provider models and persistence`() {
        addExtraProviderWithModel()

        manager.deleteAllModels("default")

        assertThat(manager.providerModels.value.map { it.id }).containsExactly("extra-a::extra-model")
        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(reloaded.providerModels.value.map { it.id }).containsExactly("extra-a::extra-model")
    }

    @Test
    fun `恢复的provider模型抑制集合在重建与refresh后都不会复活configured model`() {
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        prefs.edit()
            .remove("all_models")
            .remove("enabled_models")
            .remove("all_models_order")
            .putStringSet("suppressed_provider_models", setOf("default"))
            .commit()

        val restored = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(restored.providerModels.value.map { it.id })
            .doesNotContain("default::remote-model")

        restored.refreshAll()
        assertThat(restored.providerModels.value.map { it.id })
            .doesNotContain("default::remote-model")
        assertThat(prefs.getStringSet("suppressed_provider_models", emptySet()))
            .contains("default")
    }

    @Test
    fun `删除主槽位清理凭证模型元数据预设状态并允许重新写入`() {
        addExtraProviderWithModel()
        val mainModelId = "default::remote-model"
        val settings = app.getSharedPreferences("infinity_settings", 0)
        val providerPrefs = app.getSharedPreferences("infinity_provider", 0)
        secrets.put(SecretCatalog.vertexServiceAccount("default"), "fake-vertex".toByteArray())
        settings.edit()
            .putString("model_info_${mainModelId}_display_name", "主模型")
            .putString("default_model", mainModelId)
            .commit()
        providerPrefs.edit().putString("embedding_base_url", "https://independent.invalid").commit()
        listOf("summary", "image", "embedding", "rerank").forEach { manager.setPresetModel(it, mainModelId) }
        manager.recordProviderConnectionTest("default", ProviderConnectionProbeResult.Success)
        manager.recordProviderModelSync("extra-a")
        val extraConfig = manager.getProviderConfig("extra-a")
        val extraModels = manager.providerModels.value.filter { it.providerId == "extra-a" }
        val extraStatus = manager.providerStatuses.value["extra-a"]

        manager.deleteProvider("default")

        assertThat(manager.getMainProviderConfig()).isNull()
        assertThat(manager.getProviderSummary("default")).isNull()
        assertThat(manager.getMainConfiguredModelId()).isNull()
        assertThat(manager.currentModelSummary.value).isEmpty()
        assertThat(settings.contains("default_model")).isFalse()
        assertThat(providerPrefs.getString("embedding_base_url", null)).isEqualTo("https://independent.invalid")
        assertThat(manager.providers.value.map { it.id }).containsExactly("extra-a")
        assertThat(secrets.contains(SecretCatalog.providerApiKey("default"))).isFalse()
        assertThat(secrets.contains(SecretCatalog.vertexServiceAccount("default"))).isFalse()
        assertThat(providerPrefs.all.keys).containsNoneOf("protocol_id", "protocol_id_name", "base_url", "model", "provider_name")
        assertThat(manager.providerModels.value).containsExactlyElementsIn(extraModels)
        assertThat(settings.all.keys.any { it.startsWith("model_info_${mainModelId}_") }).isFalse()
        assertThat(manager.summaryModelId.value).isEmpty()
        assertThat(manager.imageModelId.value).isEmpty()
        assertThat(manager.embeddingModelId.value).isEmpty()
        assertThat(manager.rerankModelId.value).isEmpty()
        assertThat(manager.providerStatuses.value.keys).containsExactly("extra-a")
        assertThat(manager.providerStatuses.value["extra-a"]).isEqualTo(extraStatus)
        assertThat(manager.getProviderConfig("extra-a")).isEqualTo(extraConfig)
        val restored = ProviderManager.createForTest(app, secrets)
        restored.refreshAll()
        assertThat(restored.getMainProviderConfig()).isNull()
        assertThat(restored.providerModels.value.map { it.id })
            .containsExactlyElementsIn(extraModels.map { it.id })
        assertThat(restored.getProviderConfig("extra-a")).isEqualTo(extraConfig)
        assertThat(restored.summaryModelId.value).isEmpty()
        assertThat(restored.imageModelId.value).isEmpty()
        assertThat(restored.embeddingModelId.value).isEmpty()
        assertThat(restored.rerankModelId.value).isEmpty()
        assertThat(restored.providerStatuses.value.keys).containsExactly("extra-a")

        restored.updateMainProvider(ProtocolType.OpenAI_ChatCompletions, "https://new.invalid", CredentialUpdate.Replace("fake-new"), "new-model")

        assertThat(restored.providers.value.map { it.id }).containsExactly("default", "extra-a")
        assertThat(restored.getMainConfiguredModelId()).isEqualTo("default::new-model")
        assertThat(restored.getMainProviderConfig()?.apiKey).isEqualTo("fake-new")
        assertThat(restored.getProviderConfig("extra-a")).isEqualTo(extraConfig)
    }

    @Test
    fun `删除主槽位发布刷新通知且旧模型路由失效`() = runTest {
        val router = DefaultProviderRequestRouter(manager)
        val modelId = "default::remote-model"
        assertThat(router.resolve(modelId)).isInstanceOf(ProviderResolution.Success::class.java)
        val beforeRevision = ProviderConfigurationRevision.current()
        val notification = async(start = CoroutineStart.UNDISPATCHED) { manager.configurationChanges.first() }

        manager.deleteProvider("default")

        notification.await()
        assertThat(ProviderConfigurationRevision.current()).isGreaterThan(beforeRevision)
        assertThat(ProviderConfigurationRevision.current() % 2L).isEqualTo(0L)
        assertThat((router.resolve(modelId) as ProviderResolution.Failure).reason)
            .isEqualTo(ProviderResolutionError.MODEL_NOT_FOUND)
    }

    @Test
    fun `删除主槽位保留额外提供商的预设与当前选择`() {
        addExtraProviderWithModel()
        val extraModelId = "extra-a::extra-model"
        listOf("summary", "image", "embedding", "rerank").forEach { manager.setPresetModel(it, extraModelId) }
        val settings = app.getSharedPreferences("infinity_settings", 0)
        settings.edit().putString("default_model", extraModelId).commit()

        manager.deleteProvider("default")

        assertThat(manager.currentModelSummary.value).isEqualTo(extraModelId)
        assertThat(manager.summaryModelId.value).isEqualTo(extraModelId)
        assertThat(manager.imageModelId.value).isEqualTo(extraModelId)
        assertThat(manager.embeddingModelId.value).isEqualTo(extraModelId)
        assertThat(manager.rerankModelId.value).isEqualTo(extraModelId)
        assertThat(settings.getString("default_model", null)).isEqualTo(extraModelId)
        val restored = ProviderManager.createForTest(app, secrets)
        assertThat(restored.currentModelSummary.value).isEqualTo(extraModelId)
        restored.refreshAll()
        assertThat(restored.currentModelSummary.value).isEqualTo(extraModelId)
    }

    @Test
    fun `未选预设时删除主槽位清空当前模型摘要`() {
        manager.deleteProvider("default")
        assertThat(manager.currentModelSummary.value).isEmpty()
    }

    @Test
    fun `deleting extra provider cascades models and preset references`() {
        addExtraProviderWithModel()
        val extraModelId = "extra-a::extra-model"
        listOf("summary", "image", "embedding", "rerank").forEach { type ->
            manager.setPresetModel(type, extraModelId)
        }

        manager.deleteProvider("extra-a")

        assertThat(manager.providers.value.map { it.id }).doesNotContain("extra-a")
        assertThat(manager.providerModels.value.map { it.id }).doesNotContain(extraModelId)
        assertThat(manager.summaryModelId.value).isEmpty()
        assertThat(manager.imageModelId.value).isEmpty()
        assertThat(manager.embeddingModelId.value).isEmpty()
        assertThat(manager.rerankModelId.value).isEmpty()

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(reloaded.providers.value.map { it.id }).doesNotContain("extra-a")
        assertThat(reloaded.providerModels.value.map { it.id }).doesNotContain(extraModelId)
        assertThat(reloaded.summaryModelId.value).isEmpty()
        assertThat(reloaded.imageModelId.value).isEmpty()
        assertThat(reloaded.embeddingModelId.value).isEmpty()
        assertThat(reloaded.rerankModelId.value).isEmpty()
    }

    @Test
    fun `deleting model removes all persisted metadata keys`() {
        addExtraProviderWithModel()
        val modelId = "extra-a::extra-model"
        val prefs = app.getSharedPreferences("infinity_settings", 0)
        assertThat(prefs.all.keys.any { it.startsWith("model_info_${modelId}_") }).isTrue()

        manager.deleteModel(modelId)

        assertThat(prefs.all.keys.any { it.startsWith("model_info_${modelId}_") }).isFalse()
        assertThat(prefs.getStringSet("all_models", emptySet())).doesNotContain(modelId)
    }

    @Test
    fun `custom model uses requested provider stable id display name and persists`() {
        addExtraProviderWithModel()

        assertThat(manager.addCustomModel("extra-a", "  custom-model  ", "  Custom Name  ")).isTrue()
        assertThat(manager.addCustomModel("extra-a", "custom-model", "duplicate")).isFalse()

        val stored = manager.providerModels.value.single { it.id == "extra-a::custom-model" }
        assertThat(stored.remoteModelId).isEqualTo("custom-model")
        assertThat(stored.providerId).isEqualTo("extra-a")
        assertThat(stored.providerName).isEqualTo("隐藏提供商")
        assertThat(stored.name).isEqualTo("Custom Name")
        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(reloaded.providerModels.value.single { it.id == "extra-a::custom-model" }.name)
            .isEqualTo("Custom Name")
    }

    @Test
    fun `旧版自动生成的 DeepSeek V4 Flash 指纹整体迁移到新规格`() {
        persistLegacyDeepSeekV4FlashFingerprint()

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val migrated = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(migrated.id).isEqualTo("default::deepseek-v4-flash")
            assertThat(migrated.remoteModelId).isEqualTo("deepseek-v4-flash")
            assertThat(migrated.providerId).isEqualTo("default")
            assertThat(migrated.enabled).isTrue()
            assertThat(migrated.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(migrated.type).isEqualTo("reasoning")
            assertThat(migrated.contextLength).isEqualTo(1000000)
            assertThat(migrated.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput", "toolcalling")
            assertThat(migrated.maxOutputTokens).isEqualTo(384000)
            assertThat(migrated.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧指纹名称被用户修改后只保护名称`() {
        persistLegacyDeepSeekV4FlashFingerprint(name = "我的 DeepSeek")

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val preserved = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(preserved.name).isEqualTo("我的 DeepSeek")
            assertThat(preserved.type).isEqualTo("reasoning")
            assertThat(preserved.contextLength).isEqualTo(1000000)
            assertThat(preserved.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput", "toolcalling")
            assertThat(preserved.maxOutputTokens).isEqualTo(384000)
            assertThat(preserved.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧指纹上下文被用户修改后只保护上下文`() {
        persistLegacyDeepSeekV4FlashFingerprint(contextLength = 131072)

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val preserved = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(preserved.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(preserved.type).isEqualTo("reasoning")
            assertThat(preserved.contextLength).isEqualTo(131072)
            assertThat(preserved.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput", "toolcalling")
            assertThat(preserved.maxOutputTokens).isEqualTo(384000)
            assertThat(preserved.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧 fetched unknown empty 8192 由 SharedPreferences 加载后全部保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "future-model",
            name = "future-model",
            type = "unknown",
            contextLength = 8192,
            capabilities = emptySet(),
            maxOutputTokens = 0,
        )

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        val migrated = reloaded.providerModels.value.single { it.remoteModelId == "future-model" }

        assertThat(migrated.userEditedFields).isEmpty()
        assertThat(migrated.type).isEqualTo("unknown")
        assertThat(migrated.contextLength).isEqualTo(0)
        assertThat(migrated.capabilities).isEmpty()
    }

    @Test
    fun `旧 configured chat chat 8192 由 SharedPreferences 加载后全部保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "future-model",
            name = "future-model",
            type = "chat",
            contextLength = 8192,
            capabilities = setOf("chat"),
            maxOutputTokens = 0,
        )

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        val migrated = reloaded.providerModels.value.single { it.remoteModelId == "future-model" }

        assertThat(migrated.userEditedFields).isEmpty()
        assertThat(migrated.type).isEqualTo("unknown")
        assertThat(migrated.contextLength).isEqualTo(0)
        assertThat(migrated.capabilities).isEmpty()
    }

    @Test
    fun `Task4 精确 findModelSpec 形状由 SharedPreferences 加载后保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "deepseek-v4-flash",
            name = "DeepSeek V4 Flash",
            type = "reasoning",
            contextLength = 1_000_000,
            capabilities = setOf("chat", "reasoning", "structuredoutput"),
            maxOutputTokens = 384_000,
        )

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val migrated = reloaded.providerModels.value.single {
                it.remoteModelId == "deepseek-v4-flash"
            }

            assertThat(migrated.userEditedFields).isEmpty()
            assertThat(migrated.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(migrated.contextLength).isEqualTo(1_000_000)
            assertThat(migrated.maxOutputTokens).isEqualTo(384_000)
        }
    }

    @Test
    fun `deleting and readding provider clears prior model suppression`() {
        addExtraProviderWithModel()
        manager.deleteAllModels("extra-a")
        manager.deleteProvider("extra-a")

        addExtraProviderWithModel()

        assertThat(manager.providerModels.value.map { it.id }).contains("extra-a::extra-model")
    }

    private fun addExtraProviderWithModel() {
        manager.addProvider(
            ProviderListItem(
                id = "extra-a",
                name = "隐藏提供商",
                baseUrl = "https://extra.invalid",
                model = "extra-model",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
                enabled = true,
            ),
            CredentialUpdate.Replace("extra-test-key"),
        )
    }

    private fun knownProvider(id: String) = ProviderListItem(
        id = id,
        name = "Known Replacement",
        baseUrl = "https://known.invalid/v1/chat/completions",
        model = "known-model",
        protocolType = ProtocolType.OpenAI_ChatCompletions,
    )

    private fun persistLegacyDeepSeekV4FlashFingerprint(
        name: String = "DeepSeek",
        contextLength: Int = 64000,
    ) {
        val id = "default::deepseek-v4-flash"
        val prefix = "model_info_$id"
        app.getSharedPreferences("infinity_settings", 0).edit()
            .clear()
            .putStringSet("all_models", setOf(id))
            .putStringSet("enabled_models", setOf(id))
            .putString("all_models_order", id)
            .putString("${prefix}_name", name)
            .putString("${prefix}_type", "chat")
            .putInt("${prefix}_context", contextLength)
            .putStringSet("${prefix}_caps", setOf("chat"))
            .putString("${prefix}_provider", "同名提供商")
            .putString("${prefix}_provider_id", "default")
            .putString("${prefix}_remote_model_id", "deepseek-v4-flash")
            .putInt("${prefix}_maxoutput", 0)
            .commit()
    }

    private fun persistLegacyModel(
        remoteModelId: String,
        name: String,
        type: String,
        contextLength: Int,
        capabilities: Set<String>,
        maxOutputTokens: Int,
    ) {
        val id = "default::$remoteModelId"
        val prefix = "model_info_$id"
        app.getSharedPreferences("infinity_settings", 0).edit()
            .clear()
            .putStringSet("all_models", setOf(id))
            .putStringSet("enabled_models", setOf(id))
            .putString("all_models_order", id)
            .putString("${prefix}_name", name)
            .putString("${prefix}_type", type)
            .putInt("${prefix}_context", contextLength)
            .putStringSet("${prefix}_caps", capabilities)
            .putString("${prefix}_provider", "同名提供商")
            .putString("${prefix}_provider_id", "default")
            .putString("${prefix}_remote_model_id", remoteModelId)
            .putInt("${prefix}_maxoutput", maxOutputTokens)
            .commit()
    }

    private fun <T> withBundledCatalog(block: () -> T): T {
        val snapshot = listOf(
            File("app/src/main/assets/model-catalog/models-dev.normalized.json"),
            File("src/main/assets/model-catalog/models-dev.normalized.json"),
            File("native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json"),
        ).firstOrNull(File::isFile)
            ?: error("models-dev.normalized.json fixture is unavailable")
        val catalog = BundledModelCatalog.fromJson(snapshot.readText())
        return ModelCatalogRuntime.withTestResolver(
            replacement = ModelCatalogRuntime.resolverFor(catalog),
            block = block,
        )
    }

    private fun model(providerId: String?, id: String) = ModelInfo(
        name = id,
        id = id,
        description = "",
        enabled = true,
        providerName = "同名提供商",
        providerId = providerId,
        remoteModelId = id,
    )
}

private class TestSecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    override fun contains(id: SecretId): Boolean = values.containsKey(id)
}
