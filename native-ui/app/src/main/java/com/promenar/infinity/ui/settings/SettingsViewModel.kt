package com.promenar.infinity.ui.settings

import android.app.Application
import com.promenar.infinity.data.generation.BuiltinToolPreferences
import android.content.Intent
import android.content.SharedPreferences
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.ModelTimeoutSettings
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.StreamConfig
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.local.db.entity.CustomSkillEntity
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.repository.ISkillRepository
import com.promenar.infinity.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.infinity.data.remote.mcp.McpEndpointPolicy
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.domain.repository.IVectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.avatar.AvatarCropFiles
import com.promenar.infinity.ui.welcome.runOnboardingEndpointProbe
import com.promenar.infinity.ui.welcome.verifyOnboardingModelCandidate
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/**
 * `listModels` 只有模型 ID，不能据此证明未知模型具备聊天能力。
 * 已知规格优先；明显的专用模型按名称保守分类；其余保持 unknown，
 * 交由首次引导对具体模型执行最小聊天探测，避免无证据误放行。
 */
internal fun classifyFetchedModelType(
    remoteModelId: String,
    knownType: String?,
): String {
    knownType?.lowercase()?.let { return it }
    val normalized = remoteModelId.lowercase()
    return when {
        Regex("(?:^|[-_/.])(embed(?:ding)?|bge-m3)(?:$|[-_/.])").containsMatchIn(normalized) -> "embedding"
        Regex("(?:^|[-_/.])(rerank(?:er)?|bge-reranker)(?:$|[-_/.])").containsMatchIn(normalized) -> "rerank"
        Regex("(?:^|[-_/.])(image|dall-e|flux|stable-diffusion)(?:$|[-_/.])").containsMatchIn(normalized) -> "image"
        Regex("(?:^|[-_/.])(audio|speech|tts|whisper)(?:$|[-_/.])").containsMatchIn(normalized) -> "audio"
        else -> "unknown"
    }
}

data class McpServerUiModel(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val isConnected: Boolean,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    val callIntervalMs: Long,
    val tools: List<String>,
    val isSupported: Boolean = true,
    val syncError: String? = null,
)

enum class McpConfigError { INVALID_ENDPOINT, BEARER_REQUIRED, SAVE_FAILED }

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

enum class SettingsAsyncErrorCode { AVATAR_IMPORT_FAILED }

internal fun UiStatusNotice.withFallbackWarning(usedFallback: Boolean): UiStatusNotice =
    if (usedFallback) {
        copy(
            severity = com.promenar.infinity.ui.common.status.NoticeSeverity.Warning,
            technical = "built_in_model_fallback_used",
        )
    } else {
        this
    }

sealed interface ModelTestState {
    data object Idle : ModelTestState
    data object Testing : ModelTestState
    data class Success(val latencyMs: Long) : ModelTestState {
        init {
            require(latencyMs >= 0)
        }
    }
    data class Error(
        val code: GenerationFailureCode,
        val retryAfterSeconds: Int? = null,
    ) : ModelTestState {
        override fun toString(): String = buildString {
            append("ModelTestState.Error(code=")
            append(code)
            retryAfterSeconds?.takeIf { it > 0 }?.let {
                append(", retryAfterSeconds=")
                append(it)
            }
            append(')')
        }
    }
}

/**
 * 模型连通性探测的进程内协调器。实际请求始终通过 [ProviderRequestRouter]
 * 与 [com.promenar.infinity.data.remote.UnifiedLlmClient]，不另建旁路协议客户端。
 */
internal class ProviderModelTestCoordinator(
    private val router: ProviderRequestRouter,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 15_000,
    private val nowNanos: () -> Long = System::nanoTime,
    private val beforeJobStartForTest: (String) -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<String, ModelTestState>>(emptyMap())
    val states: StateFlow<Map<String, ModelTestState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val jobsLock = Any()

    fun test(modelId: String) {
        val job: Job
        val generation: Long
        synchronized(jobsLock) {
            if (jobs[modelId] != null) return
            generation = (generations[modelId] ?: 0L) + 1
            generations[modelId] = generation
            _states.value = _states.value + (modelId to ModelTestState.Testing)
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val startedAt = nowNanos()
                    val outcome = withTimeout(timeoutMillis) { probe(modelId) }
                    val terminal = when (outcome) {
                        is ProbeOutcome.Success -> ModelTestState.Success(
                            latencyMs = ((outcome.receivedAtNanos - startedAt) / 1_000_000L).coerceAtLeast(0),
                        )
                        is ProbeOutcome.Failed -> outcome.error
                    }
                    setState(modelId, terminal, generation)
                } catch (_: TimeoutCancellationException) {
                    setState(modelId, ModelTestState.Error(GenerationFailureCode.TIMEOUT), generation)
                } catch (cancelled: CancellationException) {
                    setState(modelId, ModelTestState.Idle, generation)
                    throw cancelled
                } catch (error: Exception) {
                    val normalized = ErrorNormalizer.normalize(error)
                    setState(modelId, ModelTestState.Error(normalized.toFailure().code), generation)
                }
            }.also { jobs[modelId] = it }
        }
        job.invokeOnCompletion {
            synchronized(jobsLock) {
                if (jobs[modelId] === job) jobs.remove(modelId)
            }
        }
        beforeJobStartForTest(modelId)
        job.start()
    }

    fun cancel(modelId: String) {
        val job: Job?
        synchronized(jobsLock) {
            job = jobs.remove(modelId)
            generations[modelId] = (generations[modelId] ?: 0L) + 1
            _states.value = _states.value + (modelId to ModelTestState.Idle)
        }
        job?.cancel()
    }

    private fun setState(modelId: String, state: ModelTestState, generation: Long) {
        synchronized(jobsLock) {
            if (generations[modelId] != generation) return
            _states.value = _states.value + (modelId to state)
        }
    }

    private suspend fun probe(modelId: String): ProbeOutcome {
        // 模型级生成能力测试：与 ProviderConnectionProbe 的零推理成本连接探测严格分离。
        val resolved = when (val resolution = router.resolve(modelId)) {
            is ProviderResolution.Success -> resolution.value
            is ProviderResolution.Failure -> return ProbeOutcome.Failed(
                ModelTestState.Error(
                    code = when (resolution.reason) {
                        com.promenar.infinity.data.remote.ProviderResolutionError.API_KEY_MISSING,
                        com.promenar.infinity.data.remote.ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
                        -> GenerationFailureCode.AUTH
                        else -> GenerationFailureCode.INVALID_REQUEST
                    },
                ),
            )
        }
        // 不传输出上限：首包即取消，上限反而会让隐藏推理的模型在可见输出前被截断，或被只认 max_completion_tokens 的接口拒绝。
        return router.createClient(resolved).sendStream(
            params = StreamTextParams(
                messages = listOf(ProtocolMessage(role = "user", content = "Reply OK.")),
                model = resolved.remoteModelId,
                streamTimeout = timeoutMillis,
            ),
            config = StreamConfig(),
        ).mapNotNull { chunk ->
            when (chunk) {
                is StreamChunk.Error -> ProbeOutcome.Failed(ModelTestState.Error(chunk.code, chunk.retryAfterSeconds))
                is StreamChunk.TextDelta -> if (chunk.content.isNotEmpty()) ProbeOutcome.Success(nowNanos()) else null
                is StreamChunk.Thinking -> if (chunk.content.isNotEmpty()) ProbeOutcome.Success(nowNanos()) else null
                is StreamChunk.Completed -> ProbeOutcome.Failed(ModelTestState.Error(GenerationFailureCode.UNKNOWN))
                else -> null
            }
        }.firstOrNull() ?: ProbeOutcome.Failed(ModelTestState.Error(GenerationFailureCode.UNKNOWN))
    }

    private sealed interface ProbeOutcome {
        data class Success(val receivedAtNanos: Long) : ProbeOutcome
        data class Failed(val error: ModelTestState.Error) : ProbeOutcome
    }
}

// ProviderListItem 已迁移至 data/model/ProviderModels.kt，此处通过 import 引入。
// 保留此注释以防止 git diff 混乱。

class SettingsViewModel(
    application: Application,
    private val vectorRepository: IVectorRepository,
    private val tokenStatsRepository: ITokenStatsRepository,
    private val mcpSkillRegistry: McpSkillRegistry? = null
) : ViewModel() {

    private val app = application as InfinityApplication
    private val prefs: SharedPreferences =
        application.getSharedPreferences("infinity_settings", 0)
    private val avatarStore by lazy { AvatarStore(
        directory = File(application.filesDir, "avatars"),
        mimeTypeOf = { uri: Uri ->
            application.contentResolver.getType(uri)
                ?: java.net.URLConnection.guessContentTypeFromName(uri.path)
        },
        openInput = { uri: Uri -> application.contentResolver.openInputStream(uri) },
    ) }

    /** 统一单例数据源 — 所有提供商/模型操作均通过 ProviderManager */
    private val pm: ProviderManager = ProviderManager.getInstance()
    val modelCatalogStatus get() = app.modelCatalogUpdater.status

    fun refreshModelCatalog() {
        viewModelScope.launch { app.modelCatalogUpdater.refresh(force = true) }
    }

    private val sessionHeaderPreferences = com.promenar.infinity.data.local.SessionHeaderPreferences(prefs)
    private val sessionHeaderRevision = MutableStateFlow(0L)
    private val sessionHeaderEdits = com.promenar.infinity.data.local.SessionHeaderModelEdits(sessionHeaderPreferences) { id ->
        pm.providerModels.value.any { it.id == id }
    }
    val providerModels: StateFlow<List<ModelInfo>> = kotlinx.coroutines.flow.combine(pm.providerModels, sessionHeaderRevision) { models, _ ->
        models.map { it.copy(sessionHeaderMode = sessionHeaderPreferences.model(it.id)) }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
        pm.providerModels.value.map { it.copy(sessionHeaderMode = sessionHeaderPreferences.model(it.id)) })

    fun getProviderSessionHeaders(providerId: String) = sessionHeaderPreferences.provider(providerId)

    suspend fun saveProviderSessionHeaders(providerId: String, enabled: Boolean, name: String) = withContext(Dispatchers.IO) {
        com.promenar.infinity.data.manager.ProviderConfigurationRevision.publishMutation {
            sessionHeaderPreferences.saveProvider(providerId, enabled, name)
        }
    }

    private val _settingsError = MutableStateFlow<SettingsAsyncErrorCode?>(null)
    val settingsError: StateFlow<SettingsAsyncErrorCode?> = _settingsError.asStateFlow()
    private var avatarRetryUri: Uri? = null
    private var avatarImportJob: Job? = null

    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    private val _userSkills = MutableStateFlow<List<CustomSkillEntity>>(emptyList())
    val userSkills: StateFlow<List<CustomSkillEntity>> = _userSkills.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerUiModel>>(emptyList())
    val mcpServers: StateFlow<List<McpServerUiModel>> = _mcpServers.asStateFlow()
    private val mcpSyncErrors = mutableMapOf<String, String>()
    val allowLoopbackMcp get() = app.mcpConnectionSettings.allowLoopbackHttp
    private val _mcpConfigError = MutableStateFlow<McpConfigError?>(null)
    val mcpConfigError: StateFlow<McpConfigError?> = _mcpConfigError.asStateFlow()

    private val _userName = MutableStateFlow(application.getString(R.string.settings_default_user_name))
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _language = MutableStateFlow("zh")
    val language: StateFlow<String> = _language.asStateFlow()

    val themePreferences: StateFlow<InfinityThemePreferences> = app.themePreferenceStore.state

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    val providers: StateFlow<List<ProviderListItem>> = pm.providers
    val unsupportedProviders: StateFlow<List<UnsupportedProviderListItem>> = pm.unsupportedProviders
    /** 各提供商最近一次连接测试与模型同步记录（本机状态，不进备份）。 */
    val providerStatuses: StateFlow<Map<String, com.promenar.infinity.data.local.ProviderStatus>> = pm.providerStatuses

    fun recordProviderConnectionTest(
        providerId: String,
        result: com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult,
    ) = pm.recordProviderConnectionTest(providerId, result)

    /** 连接参数改动后未重新测试即保存时，清掉已过期的连接记录。 */
    fun clearProviderConnectionTest(providerId: String) = pm.clearProviderConnectionTest(providerId)

    val currentModelSummary: StateFlow<String> = pm.currentModelSummary

    private val _activeSourcesCount = MutableStateFlow(0)
    val activeSourcesCount: StateFlow<Int> = _activeSourcesCount.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    /**
     * 同步模型后的结构化反馈：稳定 code + severity + 类型化 args。
     * UI 据 severity 着色、据 code+args 经 stringResource 双语格式化，**禁止**用展示串 startsWith 判定颜色。
     * [UiStatusNotice.technical] 仅用于日志/诊断，不直接展示给用户。
     */
    private val _modelSyncNotice = MutableStateFlow<UiStatusNotice?>(null)
    val modelSyncNotice: StateFlow<UiStatusNotice?> = _modelSyncNotice.asStateFlow()
    private val modelSyncGate = ModelSyncGate()

    private val modelTestCoordinator by lazy {
        ProviderModelTestCoordinator(app.providerRequestRouter, viewModelScope)
    }
    val modelTestStates: StateFlow<Map<String, ModelTestState>>
        get() = modelTestCoordinator.states

    fun clearSyncNotice() { _modelSyncNotice.value = null }

    fun testModel(modelId: String) = modelTestCoordinator.test(modelId)

    fun cancelModelTest(modelId: String) = modelTestCoordinator.cancel(modelId)

    val summaryModelId: StateFlow<String> = pm.summaryModelId
    val modelTimeouts: StateFlow<ModelTimeoutSettings> = pm.modelTimeouts
    fun setModelTimeout(kind: ModelTimeoutKind, seconds: Int) = pm.setModelTimeout(kind, seconds)
    val imageModelId: StateFlow<String> = pm.imageModelId
    val embeddingModelId: StateFlow<String> = pm.embeddingModelId
    val rerankModelId: StateFlow<String> = pm.rerankModelId

    private val _loopLimit = MutableStateFlow(prefs.getInt("loop_limit", 50))
    val loopLimit: StateFlow<Int> = _loopLimit.asStateFlow()

    fun updateLoopLimit(limit: Int) {
        _loopLimit.value = limit.coerceIn(0, 100)
        prefs.edit().putInt("loop_limit", _loopLimit.value).apply()
    }

    val taskTokenLimit = MutableStateFlow(prefs.getInt("task_token_limit", 0))
    val toolLoopRepeatLimit = MutableStateFlow(prefs.getInt("tool_loop_repeat_limit", 3))
    val streamRetryLimit = MutableStateFlow(readStreamRetryLimit())
    fun updateTaskTokenLimit(value: Int) = updateRuntimeInt("task_token_limit", value.coerceIn(0, 10_000_000), taskTokenLimit)
    fun updateToolLoopRepeatLimit(value: Int) = updateRuntimeInt("tool_loop_repeat_limit", value.coerceIn(2, 20), toolLoopRepeatLimit)
    fun updateStreamRetryLimit(value: Int) = updateRuntimeInt(
        com.promenar.infinity.domain.generation.STREAM_RETRY_LIMIT_KEY,
        value.coerceIn(0, com.promenar.infinity.domain.generation.MAX_STREAM_RETRY_LIMIT),
        streamRetryLimit,
    )

    private fun readStreamRetryLimit(): Int = prefs.getInt(
        com.promenar.infinity.domain.generation.STREAM_RETRY_LIMIT_KEY,
        com.promenar.infinity.domain.generation.DEFAULT_STREAM_RETRY_LIMIT,
    ).coerceIn(0, com.promenar.infinity.domain.generation.MAX_STREAM_RETRY_LIMIT)

    private fun updateRuntimeInt(key: String, value: Int, state: MutableStateFlow<Int>) {
        prefs.edit().putInt(key, value).apply()
        state.value = value
    }

    fun resetUnlimitedAutoConsent() {
        viewModelScope.launch { com.promenar.infinity.data.generation.UnlimitedAutomationConsent(prefs).reset() }
    }


    init {
        loadAll()
    }

    private fun loadAll() {
        loadUserProfile()
        loadPreferences()
        loadKnowledgeStats()
        loadSkills()
        observeSkills()
        // 提供商和模型已由 ProviderManager 在 init 时加载
    }

    private fun observeSkills() {
        viewModelScope.launch {
            app.skillRepository.getAllCustomSkills().collectLatest { all ->
                // Filter out any skills that might have IDs matching preset skills to avoid duplication in UI
                val presetIds = setOf("web_search", "calculator", "image_generation")
                _userSkills.value = all.filter { it.id !in presetIds }
            }
        }
        viewModelScope.launch {
            app.skillRepository.getAllMcpServers().collectLatest { entities ->
                _mcpServers.value = entities.map { entity ->
                    val snapshots = app.skillRepository.getMcpToolSnapshots(entity.id)
                    val supported = isModernMcpServer(entity.type, entity.url, entity.id)
                    McpServerUiModel(
                        id = entity.id,
                        name = entity.name,
                        url = entity.url,
                        type = entity.type,
                        isConnected = supported && snapshots.isNotEmpty(),
                        isEnabled = entity.enabled,
                        isDefault = entity.isDefault,
                        callIntervalMs = entity.callIntervalMs,
                        tools = snapshots.map { it.remoteToolName },
                        isSupported = supported,
                        syncError = mcpSyncErrors[entity.id],
                    )
                }
            }
        }
    }

    private fun loadUserProfile() {
        val defaultName = app.getString(R.string.settings_default_user_name)
        _userName.value = prefs.getString("user_name", defaultName) ?: defaultName
        _userAvatar.value = prefs.getString("user_avatar", null)
    }

    private fun loadPreferences() {
        _language.value = prefs.getString("language", "zh") ?: "zh"
        _hapticEnabled.value = prefs.getBoolean("haptic_enabled", true)
        _loopLimit.value = prefs.getInt("loop_limit", 50)
        taskTokenLimit.value = prefs.getInt("task_token_limit", 0)
        toolLoopRepeatLimit.value = prefs.getInt("tool_loop_repeat_limit", 3)
        streamRetryLimit.value = readStreamRetryLimit()
    }

    /** 刷新模型列表（远程获取 + ModelSpecs 数据库回退 + 元数据更新）；已有同步在进行时返回 null。 */
    fun refreshProviderModels(providerId: String): Job? {
        if (!modelSyncGate.tryAcquire()) return null
        _isFetchingModels.value = true
        _modelSyncNotice.value = ModelSyncNotice.loading()
        return viewModelScope.launch {
            try {
                val config = pm.getProviderConfig(providerId)
                if (config == null) {
                    _modelSyncNotice.value = ModelSyncNotice.providerNotFound()
                    return@launch
                }
                val providerName = pm.providers.value.find { it.id == providerId }?.name.orEmpty()

                // 步骤 1: 尝试远程拉取模型列表
                var fetchedModels: List<com.promenar.infinity.data.remote.protocol.RemoteModelDescriptor> = emptyList()
                var usedBuiltInFallback = false
                try {
                    val tmpProvider = if (config.protocolType is com.promenar.infinity.data.remote.protocol.ProtocolType.Local) {
                        com.promenar.infinity.data.remote.provider.LlmProvider.local(app.localInferenceEngine, config.model)
                    } else {
                        com.promenar.infinity.data.remote.provider.LlmProvider.builder()
                            .protocolType(config.protocolType)
                            .baseUrl(config.baseUrl)
                            .apiKey(config.apiKey)
                            .model(config.model)
                            .serviceAccountJson(config.vertexServiceAccountJson)
                            .projectId("")
                            .location(VERTEX_DEFAULT_LOCATION)
                            .build()
                    }
                    fetchedModels = tmpProvider.listModelDescriptors()
                    currentCoroutineContext().ensureActive()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) { /* 远程拉取失败，回退到数据库 */ }

                // 步骤 2: 回退 — 从 ModelSpecs 数据库匹配该协议类型的已知模型
                if (fetchedModels.isEmpty()) {
                    usedBuiltInFallback = true
                    fetchedModels = getFallbackModelIds(config.protocolType).map {
                        com.promenar.infinity.data.remote.protocol.RemoteModelDescriptor(it)
                    }
                }

                // 步骤 3: 合并模型（新增 + 更新元数据）
                if (fetchedModels.isNotEmpty()) {
                    var newCount = 0
                    var updatedCount = 0

                    for (descriptor in fetchedModels) {
                        when (pm.syncModelMetadata(providerId, providerName, descriptor)) {
                            com.promenar.infinity.data.manager.ModelSyncResult.ADDED -> newCount++
                            com.promenar.infinity.data.manager.ModelSyncResult.UPDATED -> updatedCount++
                            com.promenar.infinity.data.manager.ModelSyncResult.UNCHANGED -> Unit
                        }
                    }
                    // 只有真正从提供商拉到列表并合并完成才算一次同步；内置回退不更新“上次同步”时间。
                    if (!usedBuiltInFallback) pm.recordProviderModelSync(providerId)

                    // 构造结构化反馈：新增/更新计数进入 typed args，展示层负责本地化
                    if (newCount > 0 || updatedCount > 0) {
                        val synced = ModelSyncNotice.synced(newCount, updatedCount)
                        _modelSyncNotice.value = synced.withFallbackWarning(usedBuiltInFallback)
                    } else {
                        _modelSyncNotice.value = ModelSyncNotice.upToDate()
                            .withFallbackWarning(usedBuiltInFallback)
                    }
                } else {
                    _modelSyncNotice.value = ModelSyncNotice.noModelsFound()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _modelSyncNotice.value = ModelSyncNotice.syncFailed(e.message)
            } finally {
                _isFetchingModels.value = false
                modelSyncGate.release()
            }
        }
    }

    suspend fun verifyOnboardingModel(providerId: String, model: ModelInfo): ModelInfo? {
        // 这是用户明确选择具体模型后的最小生成能力验证，不是 Provider 连接探测。
        val verified = verifyOnboardingModelCandidate(model) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runOnboardingEndpointProbe {
                    val resolved = when (val result = com.promenar.infinity.ui.welcome.resolveOnboardingModelRoute(pm, providerId, model.id)) {
                        is ProviderResolution.Success -> result.value
                        is ProviderResolution.Failure -> throw IllegalStateException(result.reason.name)
                    }
                    val config = resolved.config
                    val remoteModelId = resolved.remoteModelId
                    val provider = if (config.protocolType is ProtocolType.Local) {
                        com.promenar.infinity.data.remote.provider.LlmProvider.local(
                            app.localInferenceEngine,
                            remoteModelId,
                        )
                    } else {
                        com.promenar.infinity.data.remote.provider.LlmProvider.builder()
                            .protocolType(config.protocolType)
                            .baseUrl(config.baseUrl)
                            .apiKey(config.apiKey)
                            .serviceAccountJson(config.serviceAccountJson)
                            .projectId(config.projectId)
                            .location(config.location)
                            .model(remoteModelId)
                            .build()
                    }
                    provider.sendPromptSync(
                        com.promenar.infinity.data.remote.protocol.PromptRequest(
                            messages = listOf(
                                com.promenar.infinity.data.remote.protocol.ProtocolMessage(
                                    role = "user",
                                    content = "Reply OK.",
                                )
                            ),
                            model = remoteModelId,
                            maxTokens = 1,
                            tools = null,
                            stream = false,
                        )
                    )
                }
            }
        }
        if (verified != null && verified != model) pm.replaceModelFromInternalFlow(verified)
        return verified
    }

    /**
     * 当远程 API 不可用时，从 ModelSpecs 数据库匹配已知模型 ID。
     * 按协议类型的关键词匹配 ModelSpec 条目。
     */
    private fun getFallbackModelIds(protocolType: com.promenar.infinity.data.remote.protocol.ProtocolType): List<String> {
        val keywords = when (protocolType) {
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Anthropic_Messages -> listOf("claude")
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Google_VertexAI,
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Google_Gemini,
            -> listOf("gemini")
            is com.promenar.infinity.data.remote.protocol.ProtocolType.DeepSeek -> listOf("deepseek")
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Mistral_Chat -> listOf("mistral")
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Cohere_Chat,
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Yi_ZeroOne,
            -> return emptyList()
            is com.promenar.infinity.data.remote.protocol.ProtocolType.OpenAI_ChatCompletions -> listOf("gpt", "o1", "o3", "o4")
            is com.promenar.infinity.data.remote.protocol.ProtocolType.Local -> return emptyList()
            else -> return emptyList()
        }
        // 扫描 MODEL_SPECS，匹配 StringPattern 包含任一关键词的条目
        return com.promenar.infinity.data.model.MODEL_SPECS
            .filter { spec ->
                keywords.any { kw -> spec.pattern.matches(kw) }
            }
            .mapNotNull { spec ->
                when (val p = spec.pattern) {
                    is com.promenar.infinity.data.model.ModelPattern.StringPattern -> p.value
                    else -> null
                }
            }
            .distinct()
    }

    /**
     * 根据 providerId 获取完整提供商配置（用于编辑回填）。
     * - "default" → 主提供商
     * - "extra_N" → 额外提供商
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        return pm.getProviderConfig(providerId)
    }

    suspend fun getProviderSummary(providerId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pm.getProviderSummary(providerId)
        }

    suspend fun getUnsupportedProviderSummary(providerId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pm.getUnsupportedProviderSummary(providerId)
        }

    suspend fun revealProviderCredential(providerId: String, vertex: Boolean): CharArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pm.revealCredential(providerId, vertex)
        }

    private fun loadSkills() {
        val allPresetSkills = setOf(
            "web_search", "web_fetch",
            "calculator", "image_generation",
            "file_read", "file_write", "file_list", "file_search", "file_diff", "file_patch",
            "exec_js", "run_script", "initialize_plan", "update_plan", "get_plan", "drop_plan"
        )
        if (!prefs.getBoolean("preset_skills_migrated_v3", false)) {
            prefs.edit()
                .putStringSet("enabled_skills", allPresetSkills)
                .putBoolean("preset_skills_migrated_v3", true)
                .apply()
        }

        val enabledSet = prefs.getStringSet("enabled_skills", allPresetSkills)
        _skills.value = listOf(
            SkillInfo("web_search", app.getString(R.string.skill_web_search), app.getString(R.string.skill_web_search_desc), enabledSet?.contains("web_search") ?: true),
            SkillInfo("view_media", app.getString(R.string.skill_view_media), app.getString(R.string.skill_view_media_desc), BuiltinToolPreferences.isEnabled(prefs, "view_media")),
            SkillInfo("ask_user", app.getString(R.string.ask_user_tool_name), app.getString(R.string.ask_user_tool_description), BuiltinToolPreferences.isEnabled(prefs, "ask_user")),
            SkillInfo("web_fetch", app.getString(R.string.skill_web_fetch), app.getString(R.string.skill_web_fetch_desc), enabledSet?.contains("web_fetch") ?: true),
            SkillInfo("calculator", app.getString(R.string.skill_calculator), app.getString(R.string.skill_calculator_desc), enabledSet?.contains("calculator") ?: true),
            SkillInfo("image_generation", app.getString(R.string.skill_image_generation), app.getString(R.string.skill_image_generation_desc), enabledSet?.contains("image_generation") ?: true),
            SkillInfo("copy_knowledge_to_linux", "复制资料库到 Linux", "将已授权的资料库文件或目录复制到 Linux，默认 /home/session/inbox", BuiltinToolPreferences.isEnabled(prefs, "copy_knowledge_to_linux")),
            SkillInfo("file_read", app.getString(R.string.skill_file_read), app.getString(R.string.skill_file_read_desc), enabledSet?.contains("file_read") ?: true),
            SkillInfo("file_write", app.getString(R.string.skill_file_write), app.getString(R.string.skill_file_write_desc), enabledSet?.contains("file_write") ?: true),
            SkillInfo("file_list", app.getString(R.string.skill_file_list), app.getString(R.string.skill_file_list_desc), enabledSet?.contains("file_list") ?: true),
            SkillInfo("file_search", app.getString(R.string.skill_file_search), app.getString(R.string.skill_file_search_desc), enabledSet?.contains("file_search") ?: true),
            SkillInfo("file_diff", app.getString(R.string.skill_file_diff), app.getString(R.string.skill_file_diff_desc), enabledSet?.contains("file_diff") ?: true),
            SkillInfo("file_patch", app.getString(R.string.skill_file_patch), app.getString(R.string.skill_file_patch_desc), enabledSet?.contains("file_patch") ?: true),
            SkillInfo("exec_js", app.getString(R.string.skill_exec_js), app.getString(R.string.skill_exec_js_desc), enabledSet?.contains("exec_js") ?: true),
            SkillInfo("run_script", app.getString(R.string.skill_run_script), app.getString(R.string.skill_run_script_desc), BuiltinToolPreferences.isEnabled(prefs, "run_script")),
            SkillInfo("state_define", app.getString(R.string.state_tool_define), app.getString(R.string.state_tool_define_desc), BuiltinToolPreferences.isEnabled(prefs, "state_define")),
            SkillInfo("state_update", app.getString(R.string.state_tool_update), app.getString(R.string.state_tool_update_desc), BuiltinToolPreferences.isEnabled(prefs, "state_update")),
            SkillInfo("state_get", app.getString(R.string.state_tool_get), app.getString(R.string.state_tool_get_desc), BuiltinToolPreferences.isEnabled(prefs, "state_get")),
            SkillInfo("state_remove", app.getString(R.string.state_tool_remove), app.getString(R.string.state_tool_remove_desc), BuiltinToolPreferences.isEnabled(prefs, "state_remove")),
            SkillInfo("initialize_plan", app.getString(R.string.skill_initialize_plan), app.getString(R.string.skill_initialize_plan_desc), enabledSet?.contains("initialize_plan") ?: true),
            SkillInfo("update_plan", app.getString(R.string.skill_update_plan), app.getString(R.string.skill_update_plan_desc), enabledSet?.contains("update_plan") ?: true),
            SkillInfo("get_plan", app.getString(R.string.skill_get_plan), app.getString(R.string.skill_get_plan_desc), enabledSet?.contains("get_plan") ?: true),
            SkillInfo("drop_plan", app.getString(R.string.skill_drop_plan), app.getString(R.string.skill_drop_plan_desc), enabledSet?.contains("drop_plan") ?: true),
        ) + listOf(
            Triple("canvas_create", R.string.canvas_tool_create, R.string.canvas_tool_create_description),
            Triple("canvas_update", R.string.canvas_tool_update, R.string.canvas_tool_update_description),
            Triple("canvas_read", R.string.canvas_tool_read, R.string.canvas_tool_read_description),
            Triple("canvas_list", R.string.canvas_tool_list, R.string.canvas_tool_list_description),
            Triple("artifact_publish", R.string.artifact_tool_publish, R.string.artifact_tool_publish_description),
            Triple("artifact_list", R.string.artifact_tool_list, R.string.artifact_tool_list_description),
            Triple("media_fetch", R.string.skill_media_fetch, R.string.skill_media_fetch_desc),
            Triple("browser_open", R.string.skill_browser_open, R.string.skill_browser_open_desc),
            Triple("browser_snapshot", R.string.skill_browser_snapshot, R.string.skill_browser_snapshot_desc),
            Triple("browser_click", R.string.skill_browser_click, R.string.skill_browser_click_desc),
            Triple("browser_type", R.string.skill_browser_type, R.string.skill_browser_type_desc),
            Triple("create_file", R.string.skill_create_file, R.string.skill_create_file_desc),
            Triple("create_directory", R.string.skill_create_directory, R.string.skill_create_directory_desc),
            Triple("move_file", R.string.skill_move_file, R.string.skill_move_file_desc),
            Triple("delete_file", R.string.skill_delete_file, R.string.skill_delete_file_desc),
            Triple("shell_exec", R.string.skill_shell_exec, R.string.skill_shell_exec_desc),
            Triple("python_exec", R.string.skill_python_exec, R.string.skill_python_exec_desc),
            Triple("python_sandbox", R.string.skill_python_sandbox, R.string.skill_python_sandbox_desc),
            Triple("agent_task", R.string.skill_agent_task, R.string.skill_agent_task_desc),
        ).map { (id, nameRes, descRes) ->
            SkillInfo(id, app.getString(nameRes), app.getString(descRes), BuiltinToolPreferences.isEnabled(prefs, id))
        }
    }

    private fun loadKnowledgeStats() {
        _activeSourcesCount.value = 0
    }

    fun updateUserName(name: String) {
        _userName.value = name
        prefs.edit().putString("user_name", name).apply()
    }

    fun updateUserAvatar(uriStr: String?) {
        if (uriStr == null) {
            avatarImportJob?.cancel()
            _userAvatar.value = null
            prefs.edit().remove("user_avatar").apply()
            return
        }

        avatarImportJob?.cancel()
        avatarImportJob = viewModelScope.launch {
            val source = Uri.parse(uriStr)
            val localPath = try {
                withContext(Dispatchers.IO) {
                    avatarStore.save(source, "user-avatar")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (localPath != null) {
                _userAvatar.value = localPath
                prefs.edit().putString("user_avatar", localPath).apply()
                AvatarCropFiles.deleteIfOwned(app.cacheDir, source)
                avatarRetryUri = null
                if (_settingsError.value == SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED) {
                    _settingsError.value = null
                }
            } else {
                avatarRetryUri = source
                _settingsError.value = SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED
            }
        }
    }

    fun reportAvatarImportFailure() {
        avatarRetryUri = null
        _settingsError.value = SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED
    }

    fun setLanguage(lang: String) {
        if (com.promenar.infinity.util.LocaleController.setApplicationLanguage(app, lang)) {
            _language.value = lang
        }
    }

    fun setHaptic(enabled: Boolean) {
        _hapticEnabled.value = enabled
        prefs.edit().putBoolean("haptic_enabled", enabled).apply()
        app.hapticEnabled = enabled
    }

    fun deleteProvider(providerId: String) {
        val ids = pm.providerModels.value.filter { it.providerId == providerId }.map { it.id }
        sessionHeaderEdits.remove(ids) { pm.deleteProvider(providerId) }
        sessionHeaderPreferences.removeProvider(providerId)
        sessionHeaderRevision.update { it + 1 }
    }

    fun updateExtraProvider(id: String, item: ProviderListItem) {
        pm.updateExtraProvider(id, item)
    }

    fun addProvider(item: ProviderListItem) {
        pm.addProvider(item)
    }

    fun updateModel(updatedModel: ModelInfo) {
        pm.replaceModelFromInternalFlow(updatedModel)
    }

    fun updateUserModel(updatedModel: ModelInfo) {
        sessionHeaderEdits.update(updatedModel.id, updatedModel.sessionHeaderMode) {
            pm.applyUserModelUpdate(updatedModel)
        }
        sessionHeaderRevision.update { it + 1 }
    }

    fun toggleModel(id: String) = pm.toggleModel(id)



    fun disableAllModels(providerId: String) = pm.disableAllModels(providerId)

    fun deleteAllModels(providerId: String) {
        val ids = pm.providerModels.value.filter { it.providerId == providerId }.map { it.id }
        sessionHeaderEdits.remove(ids) { pm.deleteAllModels(providerId) }
        sessionHeaderRevision.update { it + 1 }
    }

    fun addCustomModel(providerId: String, id: String, name: String): Boolean =
        pm.addCustomModel(providerId, id, name)

    fun toggleSkill(id: String) {
        if (id.startsWith("user_")) {
            viewModelScope.launch {
                val skill = _userSkills.value.find { it.id == id }
                if (skill != null) {
                    app.skillRepository.updateCustomSkillEnabled(id, !skill.enabled)
                }
            }
        } else {
            _skills.update { skills ->
                val updated = skills.map {
                    if (it.id == id) it.copy(enabled = !it.enabled) else it
                }
                saveEnabledSkills(updated)
                updated
            }
        }
    }

    private fun saveEnabledSkills(skills: List<SkillInfo>) {
        val enabled = skills.filter { it.enabled }.map { it.id }.toSet()
        prefs.edit()
            .putStringSet(BuiltinToolPreferences.ENABLED_KEY, enabled)
            .putStringSet(BuiltinToolPreferences.KNOWN_KEY, skills.map { it.id }.toSet())
            .apply()
    }

    // MCP 配置与同步。
    fun setAllowLoopbackMcp(enabled: Boolean) {
        app.mcpConnectionSettings.setAllowLoopbackHttp(enabled)
        _mcpConfigError.value = null
        _mcpServers.update { servers ->
            servers.map { server ->
                val supported = isModernMcpServer(server.type, server.url, server.id)
                server.copy(isSupported = supported, isConnected = supported && server.tools.isNotEmpty())
            }
        }
    }

    fun clearMcpConfigError() { _mcpConfigError.value = null }

    fun addMcpServer(
        name: String,
        url: String,
        type: String,
        bearerToken: String = "",
        onComplete: (Boolean) -> Unit = {},
    ) {
        val normalizedUrl = url.trim()
        val isLoopback = McpEndpointPolicy.kind(normalizedUrl) == McpEndpointPolicy.Kind.LOOPBACK_HTTP
        if (isLoopback && !McpEndpointPolicy.isValidBearerToken(bearerToken)) {
            _mcpConfigError.value = McpConfigError.BEARER_REQUIRED
            onComplete(false)
            return
        }
        if (name.isBlank() || !McpEndpointPolicy.isAllowed(
                type = type,
                url = normalizedUrl,
                allowLoopbackHttp = isLoopback && app.mcpConnectionSettings.allowLoopbackHttp.value,
                hasBearerToken = isLoopback && McpEndpointPolicy.isValidBearerToken(bearerToken),
            )
        ) {
            _mcpConfigError.value = McpConfigError.INVALID_ENDPOINT
            onComplete(false)
            return
        }
        _mcpConfigError.value = null
        viewModelScope.launch {
            val server = McpServerEntity(
                id = "mcp_${IdGenerator.uuid()}",
                name = name.trim(),
                url = normalizedUrl,
                type = type
            )
            var bearerSaved = false
            var serverSaved = false
            val success = try {
                if (isLoopback) {
                    app.mcpConnectionSettings.saveBearerToken(server.id, bearerToken)
                    bearerSaved = true
                    check(app.mcpConnectionSettings.isAllowed(server.id, type, normalizedUrl))
                }
                app.skillRepository.insertMcpServer(server)
                serverSaved = true
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _mcpConfigError.value = McpConfigError.SAVE_FAILED
                false
            } finally {
                if (bearerSaved && !serverSaved) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { app.mcpConnectionSettings.removeBearerToken(server.id) }
                    }
                }
            }
            onComplete(success)
        }
    }

    fun deleteMcpServer(id: String) {
        viewModelScope.launch {
            val server = _mcpServers.value.find { it.id == id }
            if (server != null) {
                app.skillRepository.deleteMcpServer(com.promenar.infinity.data.local.db.entity.McpServerEntity(
                    id = server.id,
                    name = server.name,
                    url = server.url,
                    type = server.type,
                    enabled = server.isEnabled,
                    callIntervalMs = server.callIntervalMs,
                    isDefault = server.isDefault
                ))
                if (McpEndpointPolicy.kind(server.url) == McpEndpointPolicy.Kind.LOOPBACK_HTTP) {
                    try {
                        app.mcpConnectionSettings.removeBearerToken(id)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _mcpConfigError.value = McpConfigError.SAVE_FAILED
                    }
                }
            }
        }
    }

    fun toggleMcpServer(id: String, enabled: Boolean) {
        viewModelScope.launch {
            app.skillRepository.updateMcpServerEnabled(id, enabled)
        }
    }

    fun updateMcpServerDefault(id: String, isDefault: Boolean) {
        viewModelScope.launch {
            app.skillRepository.updateMcpServerDefault(id, isDefault)
        }
    }

    // User Skill Methods
    fun addCustomSkill(name: String, description: String, schema: String, code: String, id: String? = null) {
        viewModelScope.launch {
            val skill = CustomSkillEntity(
                id = id ?: IdGenerator.uuid(),
                name = name,
                description = description,
                parametersSchema = schema,
                code = code,
                enabled = true
            )
            app.skillRepository.insertCustomSkill(skill)
        }
    }

    fun deleteCustomSkill(id: String) {
        viewModelScope.launch {
            val skill = _userSkills.value.find { it.id == id }
            if (skill != null) {
                app.skillRepository.deleteCustomSkill(skill)
            }
        }
    }

    fun syncMcpServer(id: String) {
        viewModelScope.launch {
            val server = _mcpServers.value.find { it.id == id } ?: return@launch
            val result = mcpSkillRegistry?.syncServer(id)
                ?: com.promenar.infinity.ui.chat.manager.registry.McpSyncResult.Failure("MCP_REGISTRY_UNAVAILABLE")
            val snapshots = app.skillRepository.getMcpToolSnapshots(id)
            val error = (result as? com.promenar.infinity.ui.chat.manager.registry.McpSyncResult.Failure)?.code
            if (error == null) mcpSyncErrors.remove(id) else mcpSyncErrors[id] = error
            _mcpServers.update { list ->
                list.map {
                    if (it.id == id) it.copy(
                        isConnected = result is com.promenar.infinity.ui.chat.manager.registry.McpSyncResult.Success,
                        tools = snapshots.map { snapshot -> snapshot.remoteToolName },
                        syncError = error,
                    ) else it
                }
            }
        }
    }

    private fun isModernMcpServer(type: String, url: String, serverId: String): Boolean =
        if (McpEndpointPolicy.kind(url) == McpEndpointPolicy.Kind.LOOPBACK_HTTP) {
            app.mcpConnectionSettings.isAllowed(serverId, type, url)
        } else {
            McpEndpointPolicy.isAllowed(type, url)
        }

    fun retryLastError() {
        when (_settingsError.value) {
            SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED -> avatarRetryUri?.let { updateUserAvatar(it.toString()) }
            null -> Unit
        }
    }


    fun refreshAll() {
        loadAll()
    }

    fun deleteModel(id: String) {
        sessionHeaderEdits.remove(listOf(id)) { pm.deleteModel(id) }
        sessionHeaderRevision.update { it + 1 }
    }

    fun setPresetModel(type: String, modelId: String) = pm.setPresetModel(type, modelId)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return SettingsViewModel(application, app.vectorRepository, app.tokenStatsRepository, app.mcpSkillRegistry) as T
                }
            }
    }
}
