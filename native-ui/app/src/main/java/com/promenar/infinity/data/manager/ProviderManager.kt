package com.promenar.infinity.data.manager

import android.app.Application
import android.content.SharedPreferences
import com.promenar.infinity.data.local.ProviderStatus
import com.promenar.infinity.data.local.ProviderStatusStore
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.ProviderSummary
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderSummary
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.USER_EDITABLE_MODEL_FIELDS
import com.promenar.infinity.data.model.mergeResolvedMetadata
import com.promenar.infinity.data.model.migrateLegacyMetadata
import com.promenar.infinity.data.model.toModelInfo
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelMetadataOverride
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.remote.protocol.RemoteModelDescriptor
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.stableModelId
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.ModelTimeoutPreferenceStore
import com.promenar.infinity.data.remote.ModelRequestTimeouts
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

enum class ModelSyncResult {
    ADDED,
    UPDATED,
    UNCHANGED,
}

/**
 * 统一提供商与模型管理单例 — 全站唯一数据源。
 *
 * 替代原先散落在 SettingsViewModel 和 InfinityApplication 中的提供商
 * CRUD、模型持久化、预设模型管理逻辑。
 */
class ProviderManager private constructor(
    private val app: Application,
    private val secretStore: SecretStore,
    private val beforeModelMetadataTransform: () -> Unit = {},
    private val beforeModelPersistenceLock: () -> Unit = {},
    private val beforeModelPersistenceApply: (List<ModelInfo>) -> Unit = {},
    private val afterProviderConfigurationWriteStarted: () -> Unit = {},
    private val providerStatusStore: ProviderStatusStore = ProviderStatusStore(
        app.getSharedPreferences("infinity_provider_status", 0),
    ),
) {
    private val suppressedProviderModelsKey = "suppressed_provider_models"
    private val modelPersistenceLock = Any()

    // ── SharedPreferences ────────────────────────────────────────────
    private val providerPrefs: SharedPreferences =
        app.getSharedPreferences("infinity_provider", 0)
    private val settingsPrefs: SharedPreferences =
        app.getSharedPreferences("infinity_settings", 0)

    private val modelTimeoutStore = ModelTimeoutPreferenceStore(settingsPrefs)
    val modelTimeouts = modelTimeoutStore.settings

    fun setModelTimeout(kind: ModelTimeoutKind, seconds: Int) = modelTimeoutStore.set(kind, seconds)

    val providerStatuses: StateFlow<Map<String, ProviderStatus>> = providerStatusStore.statuses

    fun recordProviderConnectionTest(providerId: String, result: ProviderConnectionProbeResult) =
        providerStatusStore.recordConnectionTest(providerId, result)

    fun recordProviderModelSync(providerId: String) = providerStatusStore.recordModelSync(providerId)

    fun clearProviderConnectionTest(providerId: String) = providerStatusStore.clearConnectionTest(providerId)

    // ── StateFlow: 提供商列表 ────────────────────────────────────────
    private val _providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val providers: StateFlow<List<ProviderListItem>> = _providers.asStateFlow()

    private val _unsupportedProviders = MutableStateFlow<List<UnsupportedProviderListItem>>(emptyList())
    val unsupportedProviders: StateFlow<List<UnsupportedProviderListItem>> =
        _unsupportedProviders.asStateFlow()

    private val _configurationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configurationChanges: SharedFlow<Unit> = _configurationChanges

    // ── StateFlow: 模型列表 ──────────────────────────────────────────
    private val _providerModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val providerModels: StateFlow<List<ModelInfo>> = _providerModels.asStateFlow()

    // ── StateFlow: 4 个预设模型 ──────────────────────────────────────
    private val _summaryModelId = MutableStateFlow("")
    val summaryModelId: StateFlow<String> = _summaryModelId.asStateFlow()

    private val _imageModelId = MutableStateFlow("")
    val imageModelId: StateFlow<String> = _imageModelId.asStateFlow()

    private val _embeddingModelId = MutableStateFlow("")
    val embeddingModelId: StateFlow<String> = _embeddingModelId.asStateFlow()

    private val _rerankModelId = MutableStateFlow("")
    val rerankModelId: StateFlow<String> = _rerankModelId.asStateFlow()

    // ── StateFlow: 当前模型摘要 ──────────────────────────────────────
    private val _currentModelSummary = MutableStateFlow("")
    val currentModelSummary: StateFlow<String> = _currentModelSummary.asStateFlow()

    // ── 初始化 ───────────────────────────────────────────────────────
    init {
        migrateLegacySecrets()
        loadProviders()
        loadModels()
        _providers.value.forEach { provider ->
            ensureConfiguredModel(provider.id, provider.name, provider.model)
        }
        loadPresetModels()
    }

    // ── 主提供商配置读写 ─────────────────────────────────────────────

    /**
     * 写入主提供商配置（SharedPreferences "infinity_provider"）。
     */
    @Synchronized
    fun updateMainProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        credentialUpdate: CredentialUpdate,
        model: String,
        name: String? = null
    ) {
        ProviderConfigurationRevision.publishMutation(afterProviderConfigurationWriteStarted) {
            val existingName = providerPrefs.getString("protocol_id", null)
            val existingType = readMainProtocol()
            require(existingName == null || existingType != null || credentialUpdate !is CredentialUpdate.Preserve) {
                "未知历史协议必须明确替换或清除凭证"
            }
            validateCredentialTransition(existingType, protocolType, credentialUpdate)
            providerPrefs.edit()
                .putString("protocol_id", protocolType.persistedId)
                .putString("protocol_id_name", protocolType.legacyDisplayName)
                .putString("base_url", baseUrl)
                .putString("model", model)
                .apply()
            applyCredentialUpdate("default", protocolType, credentialUpdate)
            if (name != null) {
                providerPrefs.edit().putString("provider_name", name).apply()
            }
            // 重建提供商列表
            loadProviders()
            clearProviderModelSuppression("default")
            ensureConfiguredModel("default", name ?: protocolType.legacyDisplayName, model)
        }
    }

    /**
     * 读取主提供商完整配置。兼容旧 ProtocolId 枚举名。
     */
    @Synchronized
    fun getMainProviderConfig(): ProviderConfig? {
        val protocolType = readMainProtocol() ?: return null
        return ProviderConfig(
            protocolType = protocolType,
            baseUrl = providerPrefs.getString("base_url", "") ?: "",
            apiKey = readSecret(SecretCatalog.providerApiKey("default")),
            model = providerPrefs.getString("model", "") ?: "",
            name = providerPrefs.getString("provider_name", null),
            vertexServiceAccountJson = readSecret(SecretCatalog.vertexServiceAccount("default")),
        )
    }

    fun getMainConfiguredModelId(): String? = getMainProviderConfig()
        ?.model
        ?.takeIf { it.isNotBlank() }
        ?.let { stableModelId("default", it) }

    fun getRemoteModelId(modelId: String): String? = _providerModels.value
        .firstOrNull { it.id == modelId }
        ?.remoteModelId

    // ── 提供商查询 ──────────────────────────────────────────────────

    /**
     * 根据模型 ID 反向查找其所属提供商的完整配置。
     *
     * 查找策略：
     * 1. 在模型列表中按 ID 匹配
     * 2. 使用模型的 providerId 精确查找
     * 3. providerId 缺失时失败，不按显示名称猜测归属
     */
    fun getProviderConfigByModelId(modelId: String): ProviderConfig? {
        val model = _providerModels.value.find { it.id == modelId }
        if (model == null) {
            InfinityLogger.log("[ProviderManager] 模型未找到: modelId=$modelId, 已加载模型IDs=${_providerModels.value.map { it.id }}")
            return null
        }
        val pid = model.providerId
        if (pid == null) {
            InfinityLogger.log("[ProviderManager] providerId 无法解析: modelId=$modelId providerName=${model.providerName}, 已加载提供商=${_providers.value.map { "${it.id}->${it.name}" }}")
            return null
        }
        val config = getProviderConfig(pid)
        if (config == null) {
            InfinityLogger.log("[ProviderManager] 提供商配置未找到: modelId=$modelId pid=$pid")
        } else if (config.baseUrl.isBlank()) {
            InfinityLogger.log("[ProviderManager] baseUrl 为空: modelId=$modelId pid=$pid protocolType=${config.protocolType.persistedId}")
        }
        return config
    }

    /**
     * 根据 providerId 获取完整配置。
     * - "default" → 主提供商配置
     * - "extra_N" → 额外提供商配置（新格式按索引，也支持按真实 ID 查找）
     */
    @Synchronized
    fun getProviderConfig(providerId: String): ProviderConfig? {
        if (providerId == "default") return getMainProviderConfig()
        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (i in 0 until count) {
            val prefix = "extra_provider_$i"
            val resolvedId = resolveExtraProviderId(i)
            if (resolvedId == providerId) {
                return ProviderConfig(
                    protocolType = readExtraProtocol(prefix) ?: return null,
                    baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                    apiKey = readSecret(SecretCatalog.providerApiKey(resolvedId)),
                    model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                    name = settingsPrefs.getString("${prefix}_name", null),
                    vertexServiceAccountJson = readSecret(
                        SecretCatalog.vertexServiceAccount(resolvedId)
                    ),
                )
            }
        }
        return null
    }

    /** 保密输入直接写入加密存储，调用方负责清零字节数组。 */
    @Synchronized
    fun replaceApiKey(providerId: String, value: ByteArray) {
        val summary = getProviderSummary(providerId) ?: throw IllegalArgumentException("服务商不存在")
        require(summary.protocolType !is ProtocolType.Google_VertexAI) { "该服务商不使用 API Key" }
        require(value.isNotEmpty()) { "API Key 不能为空" }
        secretStore.put(SecretCatalog.providerApiKey(providerId), value)
        _configurationChanges.tryEmit(Unit)
        loadProviders()
    }

    /** 仅供当前可见配置页临时显示；调用方取得数组所有权并负责及时清零。 */
    fun revealCredential(providerId: String, vertex: Boolean): CharArray? {
        val id = if (vertex) {
            SecretCatalog.vertexServiceAccount(providerId)
        } else {
            SecretCatalog.providerApiKey(providerId)
        }
        val bytes = secretStore.get(id) ?: return null
        return try {
            bytes.toString(Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    // ── 提供商列表 ───────────────────────────────────────────────────

    fun loadProviders() {
        val items = mutableListOf<ProviderListItem>()
        val unsupportedItems = mutableListOf<UnsupportedProviderListItem>()
        val config = getMainProviderConfig()
        _currentModelSummary.value = _summaryModelId.value.ifBlank { config?.model.orEmpty() }
        if (config != null) {
            items.add(
                ProviderListItem(
                    id = "default",
                    name = config.name ?: config.protocolType.legacyDisplayName,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    protocolType = config.protocolType,
                    hasApiKey = config.apiKey.isNotBlank(),
                    hasVertexCredentials = config.vertexServiceAccountJson.isNotBlank(),
                )
            )
        } else {
            getUnsupportedProviderSummary("default")?.let { unsupported ->
                unsupportedItems += UnsupportedProviderListItem(
                    id = unsupported.id,
                    name = unsupported.name,
                    rawProtocolId = unsupported.rawProtocolId,
                    enabled = true,
                    hasApiKey = unsupported.hasApiKey,
                    hasVertexCredentials = unsupported.hasVertexCredentials,
                )
            }
        }
        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (i in 0 until count) {
            val prefix = "extra_provider_$i"
            val realId = resolveExtraProviderId(i)
            val name = settingsPrefs.getString("${prefix}_name", null) ?: realId
            val protoName = settingsPrefs.getString("${prefix}_protocol", null)
                ?: settingsPrefs.getString("${prefix}_type", null) ?: ""
            val protocolType = readExtraProtocol(prefix)
            if (protocolType == null) {
                unsupportedItems += UnsupportedProviderListItem(
                    id = realId,
                    name = name,
                    rawProtocolId = protoName,
                    enabled = settingsPrefs.getBoolean("${prefix}_enabled", true),
                    hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(realId)),
                    hasVertexCredentials = secretStore.contains(
                        SecretCatalog.vertexServiceAccount(realId),
                    ),
                )
                continue
            }
            items.add(
                ProviderListItem(
                    id = realId,
                    name = name,
                    baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                    model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                    protocolType = protocolType,
                    hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(realId)),
                    hasVertexCredentials = secretStore.contains(SecretCatalog.vertexServiceAccount(realId)),
                    enabled = settingsPrefs.getBoolean("${prefix}_enabled", true),
                )
            )
        }
        _providers.value = items
        _unsupportedProviders.value = unsupportedItems
        providerStatusStore.retainOnly(
            items.mapTo(mutableSetOf()) { it.id } + unsupportedItems.map { it.id },
        )
    }

    @Synchronized
    fun addProvider(
        item: ProviderListItem,
        credentialUpdate: CredentialUpdate = CredentialUpdate.Preserve,
    ) {
        ProviderConfigurationRevision.publishMutation(afterProviderConfigurationWriteStarted) {
            require(
                item.id != "default" &&
                    getProviderConfig(item.id) == null &&
                    !isPersistedProtocolUnsupported(item.id) &&
                    _providers.value.none { it.id == item.id }
            ) {
                "Provider ID 已存在: ${item.id}"
            }
            applyCredentialUpdate(item.id, item.protocolType, credentialUpdate)
            _providers.update { it + item }
            persistExtraProviders()
            loadProviders()
            clearProviderModelSuppression(item.id)
            ensureConfiguredModel(item.id, item.name, item.model)
            _configurationChanges.tryEmit(Unit)
        }
    }

    @Synchronized
    fun updateExtraProvider(
        id: String,
        item: ProviderListItem,
        credentialUpdate: CredentialUpdate = CredentialUpdate.Preserve,
    ) {
        ProviderConfigurationRevision.publishMutation(afterProviderConfigurationWriteStarted) {
            val existingType = getProviderConfig(id)?.protocolType
            require(existingType != null || !isPersistedProtocolUnsupported(id) || credentialUpdate !is CredentialUpdate.Preserve) {
                "未知历史协议必须明确替换或清除凭证"
            }
            validateCredentialTransition(existingType, item.protocolType, credentialUpdate)
            if (id != item.id) {
                moveSecret(SecretCatalog.providerApiKey(id), SecretCatalog.providerApiKey(item.id))
                moveSecret(SecretCatalog.vertexServiceAccount(id), SecretCatalog.vertexServiceAccount(item.id))
            }
            applyCredentialUpdate(item.id, item.protocolType, credentialUpdate)
            _providers.update { list ->
                val updated = list.map { if (it.id == id) item else it }
                if (updated.any { it.id == item.id }) updated else updated + item
            }
            persistExtraProviders(replacedUnsupportedProviderIds = setOf(id, item.id))
            loadProviders()
            clearProviderModelSuppression(item.id)
            ensureConfiguredModel(item.id, item.name, item.model)
            _configurationChanges.tryEmit(Unit)
        }
    }

    @Synchronized
    fun deleteProvider(providerId: String) {
        ProviderConfigurationRevision.publishMutation(afterProviderConfigurationWriteStarted) {
            if (providerId == "default") {
                // 主槽位与独立的 Embedding 配置共用偏好文件，只清理该提供商的键。
                providerPrefs.edit()
                    .remove("protocol_id")
                    .remove("protocol_id_name")
                    .remove("base_url")
                    .remove("model")
                    .remove("provider_name")
                    .apply()
            }
            val removedModelIds = _providerModels.value
                .filter { model ->
                    model.providerId == providerId ||
                        (model.providerId == null && model.id.startsWith("$providerId::"))
                }
                .mapTo(mutableSetOf()) { model -> model.id }
            secretStore.remove(SecretCatalog.providerApiKey(providerId))
            secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
            providerStatusStore.remove(providerId)
            _providers.update { it.filter { p -> p.id != providerId } }
            _unsupportedProviders.update { it.filter { p -> p.id != providerId } }
            if (removedModelIds.isNotEmpty()) {
                _providerModels.update { models -> models.filterNot { it.id in removedModelIds } }
                removePersistedModelMetadata(removedModelIds)
                persistModels()
                clearPresetReferences(removedModelIds)
            }
            persistExtraProviders(replacedUnsupportedProviderIds = setOf(providerId))
            loadProviders()
            clearProviderModelSuppression(providerId)
            _configurationChanges.tryEmit(Unit)
        }
    }

    private fun clearPresetReferences(removedModelIds: Set<String>) {
        if (_summaryModelId.value in removedModelIds) setPresetModel("summary", "")
        if (_imageModelId.value in removedModelIds) setPresetModel("image", "")
        if (_embeddingModelId.value in removedModelIds) setPresetModel("embedding", "")
        if (_rerankModelId.value in removedModelIds) setPresetModel("rerank", "")
        if (settingsPrefs.getString("default_model", null) in removedModelIds) {
            settingsPrefs.edit().remove("default_model").apply()
        }
    }

    private fun clearProviderModelSuppression(providerId: String) {
        val suppressed = settingsPrefs.getStringSet(suppressedProviderModelsKey, emptySet()).orEmpty()
        if (providerId in suppressed) {
            settingsPrefs.edit()
                .putStringSet(suppressedProviderModelsKey, suppressed - providerId)
                .apply()
        }
    }

    private fun persistExtraProviders(
        replacedUnsupportedProviderIds: Set<String> = emptySet(),
    ) {
        val oldCount = settingsPrefs.getInt("extra_providers_count", 0)
        val known = _providers.value.filter { it.id != "default" }.map { item ->
            PersistedExtraProvider(
                id = item.id,
                name = item.name,
                protocolName = item.protocolType.persistedId,
                baseUrl = item.baseUrl,
                model = item.model,
                enabled = item.enabled,
            )
        }
        val knownIds = known.mapTo(mutableSetOf()) { it.id }
        val unsupported = readUnsupportedExtraProviders().filter {
            it.id !in knownIds && it.id !in replacedUnsupportedProviderIds
        }
        val extras = known + unsupported
        val editor = settingsPrefs.edit()
        for (index in 0 until oldCount) {
            val prefix = "extra_provider_$index"
            editor
                .remove("${prefix}_id")
                .remove("${prefix}_name")
                .remove("${prefix}_protocol")
                .remove("${prefix}_type")
                .remove("${prefix}_base_url")
                .remove("${prefix}_model")
                .remove("${prefix}_enabled")
        }
        editor
            .putInt("extra_providers_count", extras.size)
            .putString("extra_providers_ids", extras.joinToString(",") { it.id })
        extras.forEachIndexed { index, item ->
            val prefix = "extra_provider_$index"
            editor
                .putString("${prefix}_name", item.name)
                .putString("${prefix}_protocol", item.protocolName)
                .putString("${prefix}_base_url", item.baseUrl)
                .putString("${prefix}_model", item.model)
                .putString("${prefix}_id", item.id)
                .putBoolean("${prefix}_enabled", item.enabled)
        }
        editor.apply()
    }

    private fun readUnsupportedExtraProviders(): List<PersistedExtraProvider> {
        val count = settingsPrefs.getInt("extra_providers_count", 0)
        return (0 until count).mapNotNull { index ->
            val prefix = "extra_provider_$index"
            val protocolName = settingsPrefs.getString("${prefix}_protocol", null)
                ?: settingsPrefs.getString("${prefix}_type", null)
                ?: return@mapNotNull null
            if (readExtraProtocol(prefix) != null) return@mapNotNull null
            PersistedExtraProvider(
                id = resolveExtraProviderId(index),
                name = settingsPrefs.getString("${prefix}_name", null) ?: return@mapNotNull null,
                protocolName = protocolName,
                baseUrl = settingsPrefs.getString("${prefix}_base_url", "").orEmpty(),
                model = settingsPrefs.getString("${prefix}_model", "").orEmpty(),
                enabled = settingsPrefs.getBoolean("${prefix}_enabled", true),
            )
        }
    }

    private data class PersistedExtraProvider(
        val id: String,
        val name: String,
        val protocolName: String,
        val baseUrl: String,
        val model: String,
        val enabled: Boolean,
    )

    fun getProviderSummary(providerId: String): ProviderSummary? {
        val protocolType: ProtocolType
        val baseUrl: String
        val model: String
        val name: String?
        if (providerId == "default") {
            protocolType = readMainProtocol() ?: return null
            baseUrl = providerPrefs.getString("base_url", "").orEmpty()
            model = providerPrefs.getString("model", "").orEmpty()
            name = providerPrefs.getString("provider_name", null)
        } else {
            val count = settingsPrefs.getInt("extra_providers_count", 0)
            val index = (0 until count).firstOrNull { resolveExtraProviderId(it) == providerId } ?: return null
            val prefix = "extra_provider_$index"
            protocolType = readExtraProtocol(prefix) ?: return null
            baseUrl = settingsPrefs.getString("${prefix}_base_url", "").orEmpty()
            model = settingsPrefs.getString("${prefix}_model", "").orEmpty()
            name = settingsPrefs.getString("${prefix}_name", null)
        }
        return ProviderSummary(
            id = providerId,
            name = name ?: protocolType.legacyDisplayName,
            protocolType = protocolType,
            baseUrl = baseUrl,
            model = model,
            hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(providerId)),
            hasVertexCredentials = secretStore.contains(SecretCatalog.vertexServiceAccount(providerId)),
        )
    }

    fun getUnsupportedProviderSummary(providerId: String): UnsupportedProviderSummary? {
        val rawProtocolId: String
        val baseUrl: String
        val model: String
        val name: String
        if (providerId == "default") {
            rawProtocolId = providerPrefs.getString("protocol_id", null) ?: return null
            if (readMainProtocol() != null) return null
            baseUrl = providerPrefs.getString("base_url", "").orEmpty()
            model = providerPrefs.getString("model", "").orEmpty()
            name = providerPrefs.getString("provider_name", null) ?: rawProtocolId
        } else {
            val count = settingsPrefs.getInt("extra_providers_count", 0)
            val index = (0 until count).firstOrNull { resolveExtraProviderId(it) == providerId }
                ?: return null
            val prefix = "extra_provider_$index"
            rawProtocolId = settingsPrefs.getString("${prefix}_protocol", null)
                ?: settingsPrefs.getString("${prefix}_type", null)
                ?: return null
            if (readExtraProtocol(prefix) != null) return null
            baseUrl = settingsPrefs.getString("${prefix}_base_url", "").orEmpty()
            model = settingsPrefs.getString("${prefix}_model", "").orEmpty()
            name = settingsPrefs.getString("${prefix}_name", null) ?: providerId
        }
        return UnsupportedProviderSummary(
            id = providerId,
            name = name,
            rawProtocolId = rawProtocolId,
            baseUrl = baseUrl,
            model = model,
            hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(providerId)),
            hasVertexCredentials = secretStore.contains(SecretCatalog.vertexServiceAccount(providerId)),
        )
    }

    fun isPersistedProtocolUnsupported(providerId: String): Boolean {
        return if (providerId == "default") {
            if (!providerPrefs.contains("protocol_id")) return false
            readMainProtocol() == null
        } else {
            val count = settingsPrefs.getInt("extra_providers_count", 0)
            val index = (0 until count).firstOrNull { resolveExtraProviderId(it) == providerId }
                ?: return false
            val prefix = "extra_provider_$index"
            if (!settingsPrefs.contains("${prefix}_protocol") && !settingsPrefs.contains("${prefix}_type")) return false
            readExtraProtocol(prefix) == null
        }
    }

    @Synchronized
    private fun readMainProtocol(): ProtocolType? {
        val name = providerPrefs.getString("protocol_id", null) ?: return null
        val protocol = decodePersistedProtocol(
            name = name,
            displayName = providerPrefs.getString("protocol_id_name", null),
            baseUrl = providerPrefs.getString("base_url", "").orEmpty(),
        ) ?: return null
        if (name != protocol.persistedId) {
            providerPrefs.edit().putString("protocol_id", protocol.persistedId).apply()
        }
        return protocol
    }

    @Synchronized
    private fun readExtraProtocol(prefix: String): ProtocolType? {
        val stored = settingsPrefs.getString("${prefix}_protocol", null)
        val name = stored ?: settingsPrefs.getString("${prefix}_type", null) ?: return null
        val protocol = decodePersistedProtocol(
            name = name,
            baseUrl = settingsPrefs.getString("${prefix}_base_url", "").orEmpty(),
        ) ?: return null
        if (stored != protocol.persistedId) {
            settingsPrefs.edit().putString("${prefix}_protocol", protocol.persistedId).apply()
        }
        return protocol
    }

    /** 成功解析后仅规范化协议字段，无法识别时保留全部原值与凭据。 */
    private fun decodePersistedProtocol(
        name: String,
        baseUrl: String,
        displayName: String? = null,
    ): ProtocolType? = try {
        ProtocolType.fromPersistedName(name = name, baseUrl = baseUrl, displayName = displayName)
    } catch (_: com.promenar.infinity.data.remote.protocol.UnsupportedPersistedProtocolException) {
        null
    }

    private fun migrateLegacySecrets() {
        migratePreference(providerPrefs, "api_key", SecretCatalog.providerApiKey("default"))
        migratePreference(providerPrefs, "embedding_api_key", SecretCatalog.embeddingApiKey)
        migratePreference(providerPrefs, "vertex_service_account_json", SecretCatalog.vertexServiceAccount("default"))

        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (index in 0 until count) {
            val prefix = "extra_provider_$index"
            val providerId = resolveExtraProviderId(index)
            migratePreference(settingsPrefs, "${prefix}_api_key", SecretCatalog.providerApiKey(providerId))
            migratePreference(
                settingsPrefs,
                "${prefix}_vertex_service_account_json",
                SecretCatalog.vertexServiceAccount(providerId),
            )
        }

        val searchPrefs = app.getSharedPreferences("infinity_search", 0)
        migratePreference(searchPrefs, "tavily_api_key", SecretCatalog.tavilyApiKey)
        val backupPrefs = app.getSharedPreferences("infinity_backup_settings", 0)
        migratePreference(backupPrefs, "webdav_pass", SecretCatalog.webDavPassword)
        migratePreference(backupPrefs, "automatic_backup_password", SecretCatalog.automaticBackupPassword)
    }

    private fun migratePreference(prefs: SharedPreferences, key: String, secretId: SecretId) {
        if (!prefs.contains(key)) return
        val plaintext = prefs.getString(key, null) ?: return
        if (plaintext.isNotEmpty() && !secretStore.contains(secretId)) {
            val bytes = plaintext.toByteArray(Charsets.UTF_8)
            try {
                secretStore.put(secretId, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        check(prefs.edit().remove(key).commit()) { "旧明文凭证删除失败" }
    }

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8)?.trim().orEmpty()

    private fun writeSecret(id: SecretId, value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) secretStore.remove(id)
        else secretStore.put(id, trimmed.toByteArray(Charsets.UTF_8))
        _configurationChanges.tryEmit(Unit)
    }

    private fun writeProviderCredential(providerId: String, protocolType: ProtocolType, credential: String) {
        if (protocolType is ProtocolType.Google_VertexAI) {
            writeSecret(SecretCatalog.vertexServiceAccount(providerId), credential)
            secretStore.remove(SecretCatalog.providerApiKey(providerId))
        } else {
            writeSecret(SecretCatalog.providerApiKey(providerId), credential)
            secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
        }
    }

    private fun applyCredentialUpdate(
        providerId: String,
        protocolType: ProtocolType,
        update: CredentialUpdate,
    ) {
        when (update) {
            CredentialUpdate.Preserve -> Unit
            CredentialUpdate.Clear -> {
                secretStore.remove(SecretCatalog.providerApiKey(providerId))
                secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
                _configurationChanges.tryEmit(Unit)
            }
            is CredentialUpdate.Replace -> writeProviderCredential(providerId, protocolType, update.value)
        }
    }

    private fun resolveExtraProviderId(index: Int): String {
        val prefix = "extra_provider_$index"
        settingsPrefs.getString("${prefix}_id", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return settingsPrefs.getString("extra_providers_ids", null)
            ?.split(',')
            ?.getOrNull(index)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "extra_$index"
    }

    private fun validateCredentialTransition(
        existingType: ProtocolType?,
        newType: ProtocolType,
        update: CredentialUpdate,
    ) {
        if (update !is CredentialUpdate.Preserve || existingType == null) return
        val existingUsesVertexJson = existingType is ProtocolType.Google_VertexAI
        val newUsesVertexJson = newType is ProtocolType.Google_VertexAI
        require(existingUsesVertexJson == newUsesVertexJson) {
            "跨凭证类别切换必须 Replace 或 Clear"
        }
    }

    private fun moveSecret(from: SecretId, to: SecretId) {
        secretStore.get(from)?.let { value ->
            secretStore.put(to, value)
            secretStore.remove(from)
        }
    }

    // ── 模型管理 ────────────────────────────────────────────────────

    fun loadModels() {
        val allIds = settingsPrefs.getStringSet("all_models", null)
        if (allIds == null) {
            _providerModels.value = emptyList()
            return
        }
        // 优先从有序 ID 列表恢复顺序（跨 session 稳定排序）
        val orderedIds = settingsPrefs.getString("all_models_order", null)
            ?.split(",")?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        val idOrder = if (orderedIds != null && orderedIds.size == allIds.size) {
            // 以持久化顺序为主，同时兼容新增/缺失的 ID
            val idIndex = orderedIds.withIndex().associate { (i, id) -> id to i }
            allIds.sortedBy { idIndex[it] ?: Int.MAX_VALUE }
        } else {
            allIds.toList()
        }
        val enabledSet = settingsPrefs.getStringSet("enabled_models", emptySet()) ?: emptySet()
        var migrated = false
        // 稳定排序：enabled 降序 → 持久化顺序 → name 字母序
        val models = idOrder.map { id ->
            val prefix = "model_info_$id"
            val storedName = settingsPrefs.getString("${prefix}_name", id) ?: id
            val storedCaps = settingsPrefs.getStringSet("${prefix}_caps", emptySet())?.toSet() ?: emptySet()
            val type = settingsPrefs.getString("${prefix}_type", "unknown") ?: "unknown"
            val contextLength = settingsPrefs.getInt("${prefix}_context", 0)
            val providerName = settingsPrefs.getString("${prefix}_provider", "Cloud") ?: "Cloud"
            val storedProviderId = settingsPrefs.getString("${prefix}_provider_id", null)
            val remoteModelId = settingsPrefs.getString("${prefix}_remote_model_id", null)
                ?: storedProviderId?.let { providerId ->
                    id.removePrefix("$providerId::").takeIf { it != id }
                }
                ?: id
            val enabled = enabledSet.contains(id)
            val maxOutput = settingsPrefs.getInt("${prefix}_maxoutput", 0)
            val cutoff = settingsPrefs.getString("${prefix}_cutoff", null)
            val familyName = settingsPrefs.getString("${prefix}_family", null)
            val canonicalModelId = settingsPrefs.getString("${prefix}_canonical_id", null)
            val chatEndpoint = settingsPrefs.getString("${prefix}_chat_endpoint", null)
                ?.let { value -> runCatching { SupportState.valueOf(value) }.getOrNull() }
                ?: SupportState.UNKNOWN
            val autoFingerprint = settingsPrefs.getString("${prefix}_auto_fingerprint", null)
            val inputTokens = settingsPrefs.getInt("${prefix}_input", 0)
            val metadataSourceByField = settingsPrefs.getStringSet("${prefix}_metadata_sources", emptySet())
                .orEmpty().mapNotNull { entry ->
                    val separator = entry.lastIndexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val source = runCatching { MetadataSource.valueOf(entry.substring(separator + 1)) }.getOrNull()
                        ?: return@mapNotNull null
                    entry.substring(0, separator) to source
                }.toMap()
            val metadataDiagnostics = settingsPrefs.getStringSet("${prefix}_metadata_diagnostics", emptySet())
                .orEmpty().toSet()
            val providerOwnedBy = settingsPrefs.getString("${prefix}_provider_owned_by", null)
            val sourceProviderId = settingsPrefs.getString("${prefix}_source_provider_id", null)
            val providerMetadata = loadProviderMetadata(prefix)
            val userEditedFields = settingsPrefs
                .getStringSet("${prefix}_user_edited_fields", emptySet())
                ?.intersect(USER_EDITABLE_MODEL_FIELDS)
                ?: emptySet()

            var model = ModelInfo(
                name = storedName,
                id = id,
                description = "",
                enabled = enabled,
                type = type,
                contextLength = contextLength,
                capabilities = storedCaps.toList(),
                providerName = providerName,
                providerId = storedProviderId,
                remoteModelId = remoteModelId,
                maxOutputTokens = maxOutput,
                inputTokens = inputTokens,
                knowledgeCutoff = cutoff,
                familyName = familyName,
                canonicalModelId = canonicalModelId,
                chatEndpointCompatible = chatEndpoint,
                autoMetadataFingerprint = autoFingerprint,
                userEditedFields = userEditedFields,
                metadataSourceByField = metadataSourceByField,
                metadataDiagnostics = metadataDiagnostics,
                providerOwnedBy = providerOwnedBy,
                sourceProviderId = sourceProviderId,
                providerMetadata = providerMetadata,
            )

            val resolved = ModelCatalogRuntime.resolver.resolve(
                remoteModelId = remoteModelId,
                providerId = storedProviderId,
                sourceProviderId = sourceProviderId,
                ownedBy = providerOwnedBy,
                providerMetadata = providerMetadata,
            )
            val storedFieldPresence = buildSet {
                if (settingsPrefs.contains("${prefix}_name")) add("name")
                if (settingsPrefs.contains("${prefix}_type")) add("type")
                if (settingsPrefs.contains("${prefix}_caps")) add("capabilities")
                if (settingsPrefs.contains("${prefix}_context")) add("contextLength")
                if (settingsPrefs.contains("${prefix}_maxoutput")) add("maxOutputTokens")
            }
            val migratedModel = if (autoFingerprint != null || userEditedFields.isNotEmpty()) {
                model.mergeResolvedMetadata(resolved)
            } else {
                model.migrateLegacyMetadata(resolved, storedFieldPresence)
            }
            if (migratedModel != model) {
                migrated = true
            }
            // 历史裸 ID 无法可靠确定 Provider 归属，残留裸 ID 由 Router typed fail-close，
            // 禁止只迁 ModelInfo 却遗漏 Agent/Session/预设引用的半迁移。
            migratedModel
         }.sortedWith(compareByDescending<ModelInfo> { it.enabled }.thenBy { it.name.lowercase() })

        _providerModels.value = models

        // 如果发生了迁移，自动保存以持久化修正后的数据
        if (migrated) {
            persistModels()
        }
    }

    fun toggleModel(id: String) {
        _providerModels.update { models ->
            models.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        }
        persistModels()
    }

    fun addModel(model: ModelInfo) {
        val sanitized = model.copy(
            userEditedFields = model.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS),
        )
        val normalized = sanitized.providerId?.let { providerId ->
            sanitized.copy(id = stableModelId(providerId, sanitized.remoteModelId))
        } ?: sanitized
        _providerModels.update { models ->
            require(models.none { it.id == normalized.id }) { "模型 ID 已存在: ${normalized.id}" }
            models + normalized
        }
        normalized.providerId?.let(::clearProviderModelSuppression)
        persistModels()
    }

    /** 向指定提供商添加自定义远端模型；失败时不改变现有列表。 */
    fun addCustomModel(providerId: String, remoteModelId: String, displayName: String): Boolean {
        val provider = _providers.value.firstOrNull { it.id == providerId } ?: return false
        val remoteId = remoteModelId.trim()
        if (remoteId.isEmpty()) return false
        val stableId = stableModelId(providerId, remoteId)
        if (_providerModels.value.any { it.id == stableId }) return false
        val resolved = ModelCatalogRuntime.resolver.resolve(remoteId, providerId)
        return runCatching {
            val customName = displayName.trim()
            addModel(resolved.toModelInfo(
                providerId = providerId,
                providerName = provider.name,
                enabled = true,
                description = "Custom model",
            ).copy(
                name = customName.ifEmpty { resolved.displayName },
                userEditedFields = if (customName.isEmpty()) emptySet() else setOf("name"),
            ))
        }.isSuccess
    }

    private fun ensureConfiguredModel(providerId: String, providerName: String, remoteModelId: String) {
        if (remoteModelId.isBlank()) return
        if (providerId in settingsPrefs.getStringSet(suppressedProviderModelsKey, emptySet()).orEmpty()) return
        val id = stableModelId(providerId, remoteModelId)
        if (_providerModels.value.any { it.id == id }) return
        val resolved = ModelCatalogRuntime.resolver.resolve(remoteModelId, providerId)
        addModel(resolved.toModelInfo(
            providerId = providerId,
            providerName = providerName,
            enabled = true,
            description = "Provider configured model",
        ))
    }

    fun syncModelMetadata(
        providerId: String,
        providerName: String,
        remoteModelId: String,
    ): ModelSyncResult {
        return syncModelMetadata(providerId, providerName, RemoteModelDescriptor(id = remoteModelId))
    }

    fun syncModelMetadata(
        providerId: String,
        providerName: String,
        descriptor: RemoteModelDescriptor,
    ): ModelSyncResult {
        val remoteModelId = descriptor.id
        val incomingMetadata = descriptor.metadata.copy(
            capabilities = Collections.unmodifiableMap(LinkedHashMap(descriptor.metadata.capabilities)),
        )
        val id = stableModelId(providerId, remoteModelId)
        var result = ModelSyncResult.UNCHANGED
        _providerModels.update { models ->
            val existing = models.firstOrNull { it.id == id }
            val effective = mergeProviderDescriptor(existing, descriptor, incomingMetadata)
            val resolved = ModelCatalogRuntime.resolver.resolve(
                remoteModelId = remoteModelId,
                providerId = providerId,
                sourceProviderId = effective.sourceProviderId,
                ownedBy = effective.ownedBy,
                providerMetadata = effective.metadata,
            )
            if (existing == null) {
                result = ModelSyncResult.ADDED
                models + resolved.toModelInfo(
                    providerId = providerId,
                    providerName = providerName,
                    enabled = false,
                    description = resolved.familyName ?: "Fetched model",
                    providerOwnedBy = effective.ownedBy,
                    sourceProviderId = effective.sourceProviderId,
                    providerMetadata = effective.metadata,
                )
            } else {
                beforeModelMetadataTransform()
                val merged = existing.mergeResolvedMetadata(resolved).copy(
                    providerOwnedBy = effective.ownedBy,
                    sourceProviderId = effective.sourceProviderId,
                    providerMetadata = effective.metadata,
                )
                result = if (merged == existing) ModelSyncResult.UNCHANGED else ModelSyncResult.UPDATED
                if (result == ModelSyncResult.UNCHANGED) models
                else models.map { if (it.id == id) merged else it }
            }
        }
        if (result != ModelSyncResult.UNCHANGED) {
            clearProviderModelSuppression(providerId)
            persistModels()
        }
        return result
    }

    private fun mergeProviderDescriptor(
        existing: ModelInfo?,
        incoming: RemoteModelDescriptor,
        incomingMetadata: ModelMetadataOverride,
    ): EffectiveProviderDescriptor {
        if (existing == null) {
            return EffectiveProviderDescriptor(incoming.ownedBy, incoming.sourceProviderId, incomingMetadata)
        }
        val ownerChanged = incoming.ownedBy != null && incoming.ownedBy != existing.providerOwnedBy
        val scopeChanged = incoming.sourceProviderId != null && incoming.sourceProviderId != existing.sourceProviderId
        val identityChanged = ownerChanged || scopeChanged
        val previous = if (identityChanged) null else existing.providerMetadata
        val capabilities = LinkedHashMap(previous?.capabilities.orEmpty()).apply {
            putAll(incomingMetadata.capabilities)
        }
        return EffectiveProviderDescriptor(
            ownedBy = if (identityChanged) incoming.ownedBy else incoming.ownedBy ?: existing.providerOwnedBy,
            sourceProviderId = if (identityChanged) {
                incoming.sourceProviderId
            } else {
                incoming.sourceProviderId ?: existing.sourceProviderId
            },
            metadata = ModelMetadataOverride(
                displayName = incomingMetadata.displayName ?: previous?.displayName,
                workload = incomingMetadata.workload ?: previous?.workload,
                capabilities = Collections.unmodifiableMap(capabilities),
                contextTokens = incomingMetadata.contextTokens ?: previous?.contextTokens,
                inputTokens = incomingMetadata.inputTokens ?: previous?.inputTokens,
                outputTokens = incomingMetadata.outputTokens ?: previous?.outputTokens,
            ),
        )
    }

    private data class EffectiveProviderDescriptor(
        val ownedBy: String?,
        val sourceProviderId: String?,
        val metadata: ModelMetadataOverride,
    )

    fun refreshCatalogMetadata(): Int {
        var changed = 0
        _providerModels.update { models ->
            val refreshed = models.map { existing ->
                val resolved = ModelCatalogRuntime.resolver.resolve(
                    remoteModelId = existing.remoteModelId,
                    providerId = existing.providerId,
                    sourceProviderId = existing.sourceProviderId,
                    ownedBy = existing.providerOwnedBy,
                    providerMetadata = existing.providerMetadata,
                )
                existing.mergeResolvedMetadata(resolved)
            }
            changed = refreshed.zip(models).count { (after, before) -> after != before }
            if (changed == 0) models else refreshed
        }
        if (changed > 0) persistModels()
        return changed
    }

    fun applyUserModelUpdate(submitted: ModelInfo) {
        val submittedFields = submitted.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS)
        var changed = false
        _providerModels.update { models ->
            models.map { current ->
                if (current.id != submitted.id) return@map current
                val updated = current.copy(
                    remoteModelId = if ("remoteModelId" in submittedFields) {
                        submitted.remoteModelId.trim().ifBlank { current.remoteModelId }
                    } else {
                        current.remoteModelId
                    },
                    name = if ("name" in submittedFields) submitted.name else current.name,
                    type = if ("type" in submittedFields) submitted.type else current.type,
                    capabilities = if ("capabilities" in submittedFields) {
                        submitted.capabilities.toList()
                    } else {
                        current.capabilities
                    },
                    contextLength = if ("contextLength" in submittedFields) {
                        submitted.contextLength
                    } else {
                        current.contextLength
                    },
                    maxOutputTokens = if ("maxOutputTokens" in submittedFields) {
                        submitted.maxOutputTokens
                    } else {
                        current.maxOutputTokens
                    },
                    userEditedFields = current.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS) +
                        submittedFields,
                )
                if (updated != current) changed = true
                updated
            }
        }
        if (changed) persistModels()
    }

    internal fun replaceModelFromInternalFlow(updated: ModelInfo) {
        val sanitized = updated.copy(
            userEditedFields = updated.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS),
        )
        _providerModels.update { models ->
            models.map { if (it.id == sanitized.id) sanitized else it }
        }
        persistModels()
    }

    fun deleteModel(id: String) {
        _providerModels.update { it.filter { m -> m.id != id } }
        removePersistedModelMetadata(setOf(id))
        persistModels()
        clearPresetReferences(setOf(id))
    }

    fun disableAllModels(providerId: String) {
        _providerModels.update { models ->
            models.map { model ->
                if (model.providerId == providerId) model.copy(enabled = false) else model
            }
        }
        persistModels()
    }

    fun deleteAllModels(providerId: String) {
        val removedModelIds = _providerModels.value
            .filter { model -> model.providerId == providerId }
            .mapTo(mutableSetOf()) { model -> model.id }
        _providerModels.update { models -> models.filterNot { it.id in removedModelIds } }
        removePersistedModelMetadata(removedModelIds)
        persistModels()
        clearPresetReferences(removedModelIds)
        val suppressed = settingsPrefs.getStringSet(suppressedProviderModelsKey, emptySet()).orEmpty()
        settingsPrefs.edit()
            .putStringSet(suppressedProviderModelsKey, suppressed + providerId)
            .apply()
    }

    private fun removePersistedModelMetadata(modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        val prefixes = modelIds.map { id -> "model_info_${id}_" }
        val staleKeys = settingsPrefs.all.keys.filter { key ->
            prefixes.any(key::startsWith)
        }
        if (staleKeys.isEmpty()) return
        settingsPrefs.edit().apply {
            staleKeys.forEach(::remove)
        }.apply()
    }

    private fun persistModels() {
        beforeModelPersistenceLock()
        synchronized(modelPersistenceLock) {
            val models = _providerModels.value
            val editor = settingsPrefs.edit()
                .putStringSet("all_models", models.map { it.id }.toSet())
                .putStringSet("enabled_models", models.filter { it.enabled }.map { it.id }.toSet())
                .putString("all_models_order", models.joinToString(",") { it.id })
            models.forEach { model ->
                val prefix = "model_info_${model.id}"
                editor.apply {
                    putString("${prefix}_name", model.name)
                    putString("${prefix}_type", model.type)
                    putInt("${prefix}_context", model.contextLength)
                    putStringSet("${prefix}_caps", LinkedHashSet(model.capabilities))
                    putString("${prefix}_provider", model.providerName)
                    putString("${prefix}_provider_id", model.providerId)
                    putString("${prefix}_remote_model_id", model.remoteModelId)
                    putInt("${prefix}_maxoutput", model.maxOutputTokens)
                    putInt("${prefix}_input", model.inputTokens)
                    putString("${prefix}_chat_endpoint", model.chatEndpointCompatible.name)
                    putStringSet(
                        "${prefix}_user_edited_fields",
                        LinkedHashSet(model.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS)),
                    )
                    putStringSet(
                        "${prefix}_metadata_sources",
                        model.metadataSourceByField.mapTo(LinkedHashSet()) { (field, source) -> "$field=${source.name}" },
                    )
                    putStringSet("${prefix}_metadata_diagnostics", LinkedHashSet(model.metadataDiagnostics))
                    putNullableString("${prefix}_provider_owned_by", model.providerOwnedBy)
                    putNullableString("${prefix}_source_provider_id", model.sourceProviderId)
                    persistProviderMetadata(prefix, model.providerMetadata)
                    if (model.knowledgeCutoff == null) remove("${prefix}_cutoff")
                    else putString("${prefix}_cutoff", model.knowledgeCutoff)
                    if (model.familyName == null) remove("${prefix}_family")
                    else putString("${prefix}_family", model.familyName)
                    if (model.canonicalModelId == null) remove("${prefix}_canonical_id")
                    else putString("${prefix}_canonical_id", model.canonicalModelId)
                    if (model.autoMetadataFingerprint == null) remove("${prefix}_auto_fingerprint")
                    else putString("${prefix}_auto_fingerprint", model.autoMetadataFingerprint)
                }
            }
            beforeModelPersistenceApply(models)
            editor.apply()
        }
    }

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.persistProviderMetadata(prefix: String, metadata: ModelMetadataOverride?) {
        putNullableString("${prefix}_provider_display_name", metadata?.displayName)
        putNullableString("${prefix}_provider_workload", metadata?.workload?.name)
        putNullableString("${prefix}_provider_context", metadata?.contextTokens?.toString())
        putNullableString("${prefix}_provider_input", metadata?.inputTokens?.toString())
        putNullableString("${prefix}_provider_output", metadata?.outputTokens?.toString())
        putStringSet(
            "${prefix}_provider_capabilities",
            metadata?.capabilities?.mapTo(LinkedHashSet()) { (capability, state) ->
                "${capability.name}=${state.name}"
            } ?: emptySet(),
        )
    }

    private fun loadProviderMetadata(prefix: String): ModelMetadataOverride? {
        val displayName = settingsPrefs.getString("${prefix}_provider_display_name", null)
        val workload = settingsPrefs.getString("${prefix}_provider_workload", null)
            ?.let { runCatching { ModelWorkload.valueOf(it) }.getOrNull() }
        val context = settingsPrefs.getString("${prefix}_provider_context", null)?.toIntOrNull()
        val input = settingsPrefs.getString("${prefix}_provider_input", null)?.toIntOrNull()
        val output = settingsPrefs.getString("${prefix}_provider_output", null)?.toIntOrNull()
        val capabilities = settingsPrefs.getStringSet("${prefix}_provider_capabilities", emptySet())
            .orEmpty().mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val capability = runCatching { ModelCapability.valueOf(parts[0]) }.getOrNull()
                    ?: return@mapNotNull null
                val state = runCatching { SupportState.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                capability to state
            }.toMap()
        return ModelMetadataOverride(displayName, workload, capabilities, context, input, output)
            .takeUnless { it == ModelMetadataOverride() }
    }

    // ── 预设模型管理 ────────────────────────────────────────────────

    fun loadPresetModels() {
        _summaryModelId.value = settingsPrefs.getString("preset_summary_model", "") ?: ""
        _imageModelId.value = settingsPrefs.getString("preset_image_model", "") ?: ""
        _embeddingModelId.value = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        _rerankModelId.value = settingsPrefs.getString("preset_rerank_model", "") ?: ""
        _currentModelSummary.value = _summaryModelId.value.ifBlank {
            getProviderSummary("default")?.model.orEmpty()
        }
    }

    fun setPresetModel(type: String, modelId: String) {
        require(modelId.isBlank() || _providerModels.value.any { it.id == modelId }) {
            "预设模型必须使用已注册的稳定复合 ID"
        }
        val key = when (type) {
            "summary" -> {
                _summaryModelId.value = modelId
                _currentModelSummary.value = modelId
                "preset_summary_model"
            }
            "image" -> {
                _imageModelId.value = modelId
                "preset_image_model"
            }
            "embedding" -> {
                _embeddingModelId.value = modelId
                "preset_embedding_model"
            }
            "rerank" -> {
                _rerankModelId.value = modelId
                "preset_rerank_model"
            }
            else -> return
        }
        settingsPrefs.edit().putString(key, modelId).apply()
    }

    // ── 全局刷新 ────────────────────────────────────────────────────

    fun refreshAll() {
        loadProviders()
        loadModels()
        loadPresetModels()
    }

    // ── 单例 ────────────────────────────────────────────────────────

    companion object {
        @Volatile
        private var INSTANCE: ProviderManager? = null

        fun init(app: Application): ProviderManager =
            INSTANCE ?: init(app, AndroidKeystoreSecretStore(app))

        fun init(app: Application, secretStore: SecretStore): ProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderManager(app.applicationContext as Application, secretStore).also {
                    ModelRequestTimeouts.install { it.modelTimeouts.value }
                    INSTANCE = it
                }
            }
        }

        internal fun createForTest(
            app: Application,
            secretStore: SecretStore,
            beforeModelMetadataTransform: () -> Unit = {},
            beforeModelPersistenceLock: () -> Unit = {},
            beforeModelPersistenceApply: (List<ModelInfo>) -> Unit = {},
            afterProviderConfigurationWriteStarted: () -> Unit = {},
            providerStatusStore: ProviderStatusStore = ProviderStatusStore(
                app.getSharedPreferences("infinity_provider_status", 0),
            ),
        ): ProviderManager = ProviderManager(
            app,
            secretStore,
            beforeModelMetadataTransform,
            beforeModelPersistenceLock,
            beforeModelPersistenceApply,
            afterProviderConfigurationWriteStarted,
            providerStatusStore,
        )

        fun getInstance(): ProviderManager {
            return INSTANCE ?: throw IllegalStateException(
                "ProviderManager not initialized. Call ProviderManager.init(app) first."
            )
        }
    }
}
