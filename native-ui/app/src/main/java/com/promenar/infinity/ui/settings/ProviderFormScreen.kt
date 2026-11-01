package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.ui.Alignment
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.testing.UiTags
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderSummary
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.data.remote.protocol.ProviderEndpointOperation
import com.promenar.infinity.data.remote.protocol.ProviderEndpointResolver
import com.promenar.infinity.data.remote.protocol.ProviderEndpointTarget
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor

data class ProviderPreset(
    val name: String,
    val protocolType: ProtocolType,
    val defaultBaseUrl: String,
    val iconRes: Int? = null,
)

internal enum class ProviderConnectionTestState { Idle, Testing, Success, Unavailable, Error }

internal data class ProviderFormUiState(
    val isEditing: Boolean = false,
    val isExistingProvider: Boolean = false,
    val name: String = "",
    val selectedPreset: ProviderPreset = PROVIDER_PRESETS.first(),
    val availablePresets: List<ProviderPreset> = PROVIDER_PRESETS,
    val presetMenuExpanded: Boolean = false,
    val baseUrl: String = PROVIDER_PRESETS.first().defaultBaseUrl,
    val secretInput: String = "",
    val hasStoredCredential: Boolean = false,
    val localProtocol: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val protocolMenuExpanded: Boolean = false,
    val usesVertexCredential: Boolean = false,
    val isLocal: Boolean = false,
    val endpointValid: Boolean = true,
    val protocolUnsupported: Boolean = false,
    val legacyMigrationBlocked: Boolean = false,
    val credentialKindMismatch: Boolean = false,
    val unavailableLocalConfiguration: Boolean = false,
    val connectionTestState: ProviderConnectionTestState = ProviderConnectionTestState.Idle,
    val connectionTestFailureMessage: String? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val sessionHeadersEnabled: Boolean = false,
    val sessionHeaderName: String = com.promenar.infinity.data.model.SessionHeaderPolicy.DEFAULT_NAME,
    val sessionHeaderNameValid: Boolean = true,
    /** 云端提供商的 API 源（公网或 Tailscale 内网）；本地推理为 null。 */
    val apiSource: ProviderApiSourceState? = null,
    val tailnetInfo: ProviderTailnetInfo = ProviderTailnetInfo(),
    /** “删除提供商”行：已保存的提供商可删，内置默认灰显并说明原因，新建时不出现。 */
    val deleteEntry: UnavailableReasons.ProviderDeleteEntry = UnavailableReasons.ProviderDeleteEntry.HIDDEN,
)

internal data class ProviderFormActions(
    val onBack: () -> Unit = {},
    val onPresetMenuExpandedChange: (Boolean) -> Unit = {},
    val onPresetSelected: (ProviderPreset) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onBaseUrlChange: (String) -> Unit = {},
    val onSecretChange: (String) -> Unit = {},
    val onRevealSecret: suspend () -> CharArray? = { null },
    val onClearSecret: () -> Unit = {},
    val onProtocolSelected: (ProtocolType) -> Unit = {},
    val onProtocolMenuExpandedChange: (Boolean) -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onSessionHeadersEnabled: (Boolean) -> Unit = {},
    val onSessionHeaderName: (String) -> Unit = {},
    val onLocalPrimaryAction: () -> Unit = {},
    val onApiSourceSelected: (ProviderApiSource) -> Unit = {},
    val onTailnetPeerSelected: (com.promenar.infinity.data.tailnet.TailnetPeer) -> Unit = {},
    val onOpenTailscaleSettings: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

internal suspend fun persistVerifiedProviderConnection(
    protocolType: ProtocolType,
    baseUrl: String,
    credential: CredentialUpdate,
    model: String,
    name: String?,
    onSave: suspend (ProtocolType, String, CredentialUpdate, String, String?) -> String,
    readSummary: suspend (String) -> ProviderSummary?,
): String {
    val savedProviderId = onSave(protocolType, baseUrl, credential, model, name)
    val summary = checkNotNull(readSummary(savedProviderId)) { "保存后的提供商不可读" }
    check(summary.protocolType == protocolType && summary.baseUrl == baseUrl) {
        "保存后的提供商配置与已验证输入不一致"
    }
    when (credential) {
        is CredentialUpdate.Replace -> check(
            if (protocolType is ProtocolType.Google_VertexAI) summary.hasVertexCredentials else summary.hasApiKey,
        ) { "保存后的提供商凭证不可用" }
        CredentialUpdate.Clear -> check(!summary.hasApiKey && !summary.hasVertexCredentials) {
            "提供商凭证清除未生效"
        }
        CredentialUpdate.Preserve -> Unit
    }
    return savedProviderId
}

internal fun requiresLegacyCredentialMigration(
    legacyProtocol: Boolean,
    hasStoredCredential: Boolean,
    credentialUpdate: CredentialUpdate,
): Boolean = legacyProtocol && hasStoredCredential && credentialUpdate is CredentialUpdate.Preserve

val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", R.drawable.ic_provider_openai),
    ProviderPreset("DeepSeek", ProtocolType.DeepSeek, "https://api.deepseek.com", R.drawable.ic_provider_deepseek),
    ProviderPreset("Anthropic", ProtocolType.Anthropic_Messages, "https://api.anthropic.com", R.drawable.ic_provider_anthropic),
    ProviderPreset("Gemini", ProtocolType.Google_Gemini, "https://generativelanguage.googleapis.com", R.drawable.ic_provider_gemini),
    ProviderPreset("Vertex AI", ProtocolType.Google_VertexAI, "https://us-central1-aiplatform.googleapis.com", R.drawable.ic_provider_gemini),
    ProviderPreset("Kimi", ProtocolType.Moonshot_Kimi, "https://api.moonshot.cn", R.drawable.ic_provider_kimi),
    ProviderPreset("通义千问", ProtocolType.Qwen_DashScope, "https://dashscope.aliyuncs.com/compatible-mode", R.drawable.ic_provider_qwen),
    ProviderPreset("智谱", ProtocolType.Zhipu_GLM, "https://open.bigmodel.cn/api/paas", R.drawable.ic_provider_zhipu),
    ProviderPreset("豆包", ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api", R.drawable.ic_provider_doubao),
    ProviderPreset("百川", ProtocolType.Baichuan, "https://api.baichuan-ai.com", R.drawable.ic_provider_baichuan),
    ProviderPreset("Mistral", ProtocolType.Mistral_Chat, "https://api.mistral.ai", R.drawable.ic_provider_mistral),
    ProviderPreset("Local", ProtocolType.Local, "", R.drawable.ic_provider_local),
    ProviderPreset("Custom", ProtocolType.Generic_OpenAI_Compat, "", R.drawable.ic_provider_custom),
)

internal val CUSTOM_PROTOCOL_OPTIONS = listOf(
    ProtocolType.Generic_OpenAI_Compat,
    ProtocolType.Anthropic_Messages,
    ProtocolType.OpenAI_Responses,
    ProtocolType.Google_Gemini,
)

internal const val PROVIDER_FORM_LIST_TAG = "provider_form_list"

@Composable
fun ProviderFormScreen(
    providerId: String? = null,
    onNavigateBack: () -> Unit,
    onOpenTailscaleSettings: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onSave: suspend (ProtocolType, String, CredentialUpdate, String, String?) -> String = { _, _, _, _, _ -> "" },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val app = context.applicationContext as InfinityApplication
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val availableProviderPresets = remember(app.localInferenceRuntimeGate.isAvailable) {
        if (app.localInferenceRuntimeGate.isAvailable) {
            PROVIDER_PRESETS
        } else {
            PROVIDER_PRESETS.filterNot { it.protocolType is ProtocolType.Local }
        }
    }
    var sessionHeadersEnabled by remember { mutableStateOf(false) }
    var sessionHeaderName by remember { mutableStateOf(com.promenar.infinity.data.model.SessionHeaderPolicy.DEFAULT_NAME) }
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(availableProviderPresets.first()) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(PROVIDER_PRESETS.first().defaultBaseUrl) }
    var secretInput by remember { mutableStateOf("") }
    var credentialUpdate by remember { mutableStateOf<CredentialUpdate>(CredentialUpdate.Preserve) }
    var hasCredential by remember { mutableStateOf(false) }
    var originalUsesVertex by remember { mutableStateOf<Boolean?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var unavailableLocalConfiguration by remember { mutableStateOf(false) }
    var localProtocol by remember { mutableStateOf<ProtocolType>(ProtocolType.Generic_OpenAI_Compat) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var connectionTestState by remember { mutableStateOf(ProviderConnectionTestState.Idle) }
    var connectionTestFailureMessage by remember { mutableStateOf<String?>(null) }
    var legacyUnsupportedProtocol by remember { mutableStateOf<ProtocolType?>(null) }
    var recoveringUnknownProtocol by remember { mutableStateOf(false) }
    // API 源不保存：未手动切换时跟随地址自动识别。
    var manualApiSource by remember { mutableStateOf<ProviderApiSource?>(null) }
    // 新建提供商时测试结果在保存拿到 ID 后再记录；编辑时记录载入时的连接参数，用于判断保存是否改了连接。
    var lastProbeResult by remember { mutableStateOf<ProviderConnectionProbeResult?>(null) }
    var loadedConnection by remember { mutableStateOf<Pair<ProtocolType, String>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val tailnetState by app.tailnetManager.state.collectAsStateWithLifecycle()
    val connectionProbe = ProviderConnectionProbe.processScoped
    val scope = rememberCoroutineScope()

    LaunchedEffect(providerId) {
        if (providerId != null) {
            val headerSettings = viewModel.getProviderSessionHeaders(providerId)
            sessionHeadersEnabled = headerSettings.enabled
            sessionHeaderName = headerSettings.name
            val config = viewModel.getProviderSummary(providerId)
            if (config != null) {
                unavailableLocalConfiguration =
                    config.protocolType is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                name = config.name
                baseUrl = config.baseUrl
                hasCredential = config.hasApiKey || config.hasVertexCredentials
                originalUsesVertex = config.hasVertexCredentials
                legacyUnsupportedProtocol = config.protocolType.takeIf(::isRetiredProviderProtocol)
                val matched = availableProviderPresets.find {
                    it.protocolType == config.protocolType &&
                        (it.name != "Custom" || config.protocolType == ProtocolType.Generic_OpenAI_Compat)
                }
                selectedPreset = when {
                    matched != null -> matched
                    legacyUnsupportedProtocol != null -> ProviderPreset(
                        name = config.protocolType.displayName,
                        protocolType = config.protocolType,
                        defaultBaseUrl = config.baseUrl,
                        iconRes = config.protocolType.iconRes,
                    )
                    else -> availableProviderPresets.last()
                }
                if (selectedPreset.name == "Custom") {
                    localProtocol = config.protocolType.takeUnless {
                        it is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                    } ?: ProtocolType.Generic_OpenAI_Compat
                }
                loadedConnection = config.protocolType to config.baseUrl
            } else {
                viewModel.getUnsupportedProviderSummary(providerId)?.let { unsupported ->
                    recoveringUnknownProtocol = true
                    name = unsupported.name
                    baseUrl = unsupported.baseUrl
                    hasCredential = unsupported.hasApiKey || unsupported.hasVertexCredentials
                    originalUsesVertex = when {
                        unsupported.hasVertexCredentials && !unsupported.hasApiKey -> true
                        unsupported.hasApiKey && !unsupported.hasVertexCredentials -> false
                        else -> null
                    }
                    selectedPreset = availableProviderPresets.last { it.name == "Custom" }
                    localProtocol = ProtocolType.Generic_OpenAI_Compat
                }
            }
        }
    }

    val effectiveProtocol = if (selectedPreset.name == "Custom") localProtocol else selectedPreset.protocolType
    val endpointValid = isProviderEndpointAllowed(effectiveProtocol, baseUrl)
    val protocolUnsupported = isRetiredProviderProtocol(effectiveProtocol)
    val legacyMigrationBlocked = requiresLegacyCredentialMigration(
        legacyProtocol = legacyUnsupportedProtocol != null || recoveringUnknownProtocol,
        hasStoredCredential = hasCredential,
        credentialUpdate = credentialUpdate,
    )
    val selectedUsesVertex = effectiveProtocol is ProtocolType.Google_VertexAI
    val apiSource = if (effectiveProtocol is ProtocolType.Local) null
        else providerApiSourceState(baseUrl, manualApiSource, tailnetState)
    val showsTailnet = apiSource?.selected == ProviderApiSource.TAILNET
    LaunchedEffect(showsTailnet, tailnetState.phase) {
        // 设备列表在设置页之外不会主动刷新；切到 Tailscale 内网时取一次最新节点。
        if (showsTailnet && tailnetState.phase == com.promenar.infinity.data.tailnet.TailnetPhase.RUNNING) {
            // 刷新失败只是沿用旧列表；离开页面时的取消必须继续向上传递。
            try {
                app.tailnetManager.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
    val credentialKindMismatch = hasCredential && credentialUpdate is CredentialUpdate.Preserve &&
        originalUsesVertex != null && originalUsesVertex != selectedUsesVertex

    val sessionHeaderNameValid = (!sessionHeadersEnabled && sessionHeaderName.isEmpty()) ||
        com.promenar.infinity.data.model.SessionHeaderPolicy.isValidName(sessionHeaderName)
    val saveWithSessionHeaders: suspend (ProtocolType, String, CredentialUpdate, String, String?) -> String = { protocol, url, credential, model, providerName ->
        check(sessionHeaderNameValid) { "会话请求头名称无效" }
        val savedEnabled = sessionHeadersEnabled
        val savedName = sessionHeaderName
        val savedId = onSave(protocol, url, credential, model, providerName)
        viewModel.saveProviderSessionHeaders(savedId, savedEnabled, savedName)
        savedId
    }
    val launchSave: () -> Unit = {
        if (!isSaving && connectionTestState != ProviderConnectionTestState.Testing &&
            endpointValid && !protocolUnsupported && !legacyMigrationBlocked && !credentialKindMismatch && sessionHeaderNameValid
        ) {
            isSaving = true
            saveFailed = false
            scope.launch {
                try {
                    val testedCurrentInput = connectionTestState != ProviderConnectionTestState.Idle
                    val connectionChanged = loadedConnection != (effectiveProtocol to baseUrl) ||
                        credentialUpdate !is CredentialUpdate.Preserve
                    val savedId = saveWithSessionHeaders(
                        effectiveProtocol,
                        baseUrl,
                        credentialUpdate,
                        "",
                        name.ifBlank { null },
                    )
                    if (providerId == null && testedCurrentInput) {
                        lastProbeResult?.let { viewModel.recordProviderConnectionTest(savedId, it) }
                    } else if (providerId != null && !testedCurrentInput && connectionChanged) {
                        // 连接参数已变而没有重新测试：旧的测试结果不再代表当前配置。
                        viewModel.clearProviderConnectionTest(savedId)
                    }
                    if (providerId != null) {
                        onNavigateToModels()
                    } else {
                        onNavigateBack()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveFailed = true
                } finally {
                    isSaving = false
                }
            }
        }
    }

    val launchConnectionTest: () -> Unit = {
        if (connectionTestState != ProviderConnectionTestState.Testing && !isSaving &&
            endpointValid && !protocolUnsupported && !legacyMigrationBlocked && !credentialKindMismatch && sessionHeaderNameValid
        ) {
            connectionTestState = ProviderConnectionTestState.Testing
            connectionTestFailureMessage = null
            scope.launch {
                val probeResult = withContext(Dispatchers.IO) {
                    try {
                        if (effectiveProtocol is ProtocolType.Local) {
                            ProviderConnectionProbeResult.Success
                        } else {
                            val transient = when (val update = credentialUpdate) {
                                is CredentialUpdate.Replace -> update.value.toCharArray()
                                CredentialUpdate.Clear -> CharArray(0)
                                CredentialUpdate.Preserve -> providerId?.let {
                                    viewModel.revealProviderCredential(
                                        it,
                                        effectiveProtocol is ProtocolType.Google_VertexAI,
                                    )
                                } ?: CharArray(0)
                            }
                            try {
                                val credential = transient.concatToString()
                                connectionProbe.probe(UnifiedProviderConfig(
                                    protocolType = effectiveProtocol,
                                    baseUrl = baseUrl,
                                    apiKey = if (selectedUsesVertex) "" else credential,
                                    defaultModel = "",
                                    serviceAccountJson = if (selectedUsesVertex) credential else "",
                                    projectId = "",
                                    location = VERTEX_DEFAULT_LOCATION,
                                ))
                            } finally {
                                transient.fill('\u0000')
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ProviderConnectionProbeResult.Failure(
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE,
                        )
                    }
                }
                lastProbeResult = probeResult
                providerId?.let { viewModel.recordProviderConnectionTest(it, probeResult) }
                connectionTestState = when (probeResult) {
                    ProviderConnectionProbeResult.Success -> {
                        connectionTestFailureMessage = null
                        ProviderConnectionTestState.Success
                    }
                    ProviderConnectionProbeResult.Unsupported -> {
                        connectionTestFailureMessage = null
                        ProviderConnectionTestState.Unavailable
                    }
                    is ProviderConnectionProbeResult.Failure -> {
                        val reasonText = when (probeResult.reason) {
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED ->
                                resources.getString(R.string.provider_form_test_failed_auth)
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE ->
                                resources.getString(R.string.provider_form_test_failed_network)
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.ENDPOINT_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_endpoint)
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.CREDENTIALS_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_credentials)
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.CREDENTIALS_MISSING ->
                                resources.getString(R.string.provider_form_test_failed_missing)
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.RESPONSE_INVALID ->
                                resources.getString(R.string.provider_form_test_failed_response)
                        }
                        val prefix = resources.getString(R.string.common_cd_failed)
                        connectionTestFailureMessage = if (probeResult.statusCode != null) {
                            "$prefix (HTTP ${probeResult.statusCode})：$reasonText"
                        } else {
                            "$prefix：$reasonText"
                        }
                        ProviderConnectionTestState.Error
                    }
                }
                if (probeResult == ProviderConnectionProbeResult.Success && providerId != null) {
                    isSaving = true
                    saveFailed = false
                    try {
                        persistVerifiedProviderConnection(
                            protocolType = effectiveProtocol,
                            baseUrl = baseUrl,
                            credential = credentialUpdate,
                            model = "",
                            name = name.ifBlank { null },
                            onSave = saveWithSessionHeaders,
                            readSummary = viewModel::getProviderSummary,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        connectionTestState = ProviderConnectionTestState.Error
                        saveFailed = true
                    } finally {
                        isSaving = false
                    }
                }
            }
        }
    }

    val launchLocalPrimary: () -> Unit = {
        if (!isSaving) {
            scope.launch {
                isSaving = true
                saveFailed = false
                try {
                    onSave(
                        ProtocolType.Local,
                        "",
                        CredentialUpdate.Preserve,
                        "",
                        "本地模型",
                    )
                    onNavigateToLocalModels()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveFailed = true
                } finally {
                    isSaving = false
                }
            }
        }
    }

    ProviderFormContent(
        state = ProviderFormUiState(
            isEditing = providerId != null,
            isExistingProvider = providerId != null,
            name = name,
            selectedPreset = selectedPreset,
            availablePresets = availableProviderPresets,
            presetMenuExpanded = presetMenuExpanded,
            baseUrl = baseUrl,
            secretInput = secretInput,
            hasStoredCredential = hasCredential,
            localProtocol = localProtocol,
            protocolMenuExpanded = protocolMenuExpanded,
            usesVertexCredential = effectiveProtocol is ProtocolType.Google_VertexAI,
            isLocal = effectiveProtocol is ProtocolType.Local,
            endpointValid = endpointValid,
            protocolUnsupported = protocolUnsupported,
            legacyMigrationBlocked = legacyMigrationBlocked,
            credentialKindMismatch = credentialKindMismatch,
            unavailableLocalConfiguration = unavailableLocalConfiguration,
            connectionTestState = connectionTestState,
            connectionTestFailureMessage = connectionTestFailureMessage,
            isSaving = isSaving,
            saveFailed = saveFailed,
            sessionHeadersEnabled = sessionHeadersEnabled,
            sessionHeaderName = sessionHeaderName,
            sessionHeaderNameValid = sessionHeaderNameValid,
            apiSource = apiSource,
            tailnetInfo = ProviderTailnetInfo(
                selfName = tailnetState.summary?.selfName,
                tailnetName = tailnetState.summary?.tailnetName,
                peers = tailnetState.peers,
            ),
            deleteEntry = UnavailableReasons.providerDeleteEntry(providerId),
        ),
        actions = ProviderFormActions(
            onBack = onNavigateBack,
            onPresetMenuExpandedChange = { presetMenuExpanded = it },
            onPresetSelected = { preset ->
                selectedPreset = preset
                if (preset.name != "Custom" && preset.name != "Local") {
                    name = preset.name
                    baseUrl = preset.defaultBaseUrl
                }
                manualApiSource = null
                presetMenuExpanded = false
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onNameChange = { name = it },
            onBaseUrlChange = {
                baseUrl = it
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onSecretChange = {
                secretInput = it
                credentialUpdate = credentialUpdateForSecretInput(it, hasCredential)
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onRevealSecret = {
                providerId?.let {
                    viewModel.revealProviderCredential(
                        it,
                        effectiveProtocol is ProtocolType.Google_VertexAI,
                    )
                }
            },
            onClearSecret = {
                secretInput = ""
                hasCredential = false
                credentialUpdate = CredentialUpdate.Clear
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onProtocolSelected = {
                localProtocol = it
                protocolMenuExpanded = false
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onProtocolMenuExpandedChange = { protocolMenuExpanded = it },
            onTestConnection = launchConnectionTest,
            onSave = launchSave,
            onSessionHeadersEnabled = { sessionHeadersEnabled = it },
            onSessionHeaderName = { sessionHeaderName = it },
            onLocalPrimaryAction = launchLocalPrimary,
            onApiSourceSelected = { manualApiSource = it },
            onTailnetPeerSelected = { peer ->
                com.promenar.infinity.data.tailnet.TailnetEndpoints.addressForPeer(peer, baseUrl)?.let { address ->
                    baseUrl = address
                    connectionTestState = ProviderConnectionTestState.Idle
                    connectionTestFailureMessage = null
                }
            },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
            onDelete = { showDeleteConfirm = true },
        ),
    )
    if (showDeleteConfirm && providerId != null) {
        InfinityConfirmDialog(
            title = stringResource(R.string.settings_delete_provider_title),
            message = stringResource(R.string.settings_delete_provider_message),
            confirmText = stringResource(R.string.settings_btn_delete),
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteProvider(providerId)
                onNavigateBack()
            },
            onCancel = { showDeleteConfirm = false },
            isDestructive = true,
            modifier = Modifier.testTag(UiTags.PROVIDER_FORM_DELETE_CONFIRM),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderFormContent(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = when {
        state.isEditing && state.name.isNotBlank() -> state.name
        state.isEditing -> stringResource(R.string.provider_form_title_edit)
        else -> stringResource(R.string.provider_form_title_add)
    }
    InfinitySettingsPageLayout(
        title = title,
        onBack = actions.onBack,
        modifier = modifier,
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(PROVIDER_FORM_LIST_TAG),
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (state.unavailableLocalConfiguration) {
                    animatedFormItem("local-unavailable") {
                        ProviderFormNotice(stringResource(R.string.local_inference_release_unavailable))
                    }
                }
                if (state.legacyMigrationBlocked) {
                    animatedFormItem("legacy-migration") {
                        ProviderFormNotice(stringResource(R.string.provider_form_legacy_migration_required))
                    }
                }
                // 提供商：预设、自定义协议与显示名称。
                animatedFormItem("preset") {
                    InfinitySettingsSection(title = stringResource(R.string.provider_form_group_provider)) {
                        ProviderPresetSelector(state = state, actions = actions)
                        if (state.selectedPreset.name == "Custom") {
                            InfinityGroupDivider()
                            ProviderProtocolSelector(state = state, actions = actions)
                        }
                        if (!state.isLocal) {
                            InfinityGroupDivider()
                            InfinityFieldRow(
                                label = stringResource(R.string.provider_form_label_name),
                                value = state.name,
                                onValueChange = actions.onNameChange,
                                placeholder = stringResource(R.string.provider_form_placeholder_name),
                            )
                        }
                    }
                }
                if (state.isLocal) {
                    animatedFormItem("local-section") {
                        LocalProviderSection(state = state, actions = actions)
                    }
                } else {
                    animatedFormItem("interface-section") {
                        ProviderInterfaceSection(state = state, actions = actions)
                    }
                    animatedFormItem("cloud-section") {
                        CloudProviderSection(state = state, actions = actions)
                    }
                    animatedFormItem("advanced-section") {
                        ProviderSessionHeaderFields(
                            enabled = state.sessionHeadersEnabled,
                            name = state.sessionHeaderName,
                            onEnabled = actions.onSessionHeadersEnabled,
                            onName = actions.onSessionHeaderName,
                        )
                    }
                }
                if (state.deleteEntry != UnavailableReasons.ProviderDeleteEntry.HIDDEN) {
                    animatedFormItem("delete") {
                        InfinitySettingsGroup {
                            InfinitySettingsItem(
                                title = stringResource(R.string.provider_form_delete),
                                icon = Icons.Rounded.Delete,
                                titleColor = MaterialTheme.colorScheme.error,
                                onClick = actions.onDelete,
                                modifier = Modifier.testTag(UiTags.PROVIDER_FORM_DELETE),
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(visible = !state.isLocal, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                ProviderFormActionsSection(
                    state = state,
                    actions = actions,
                    modifier = Modifier.padding(
                        start = InfinitySpacing.ScreenHorizontal,
                        top = InfinitySpacing.Small,
                        end = InfinitySpacing.ScreenHorizontal,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

/** 表单中按条件出现的分组与提示：出现时淡入、其余分组平滑让位。 */
private fun LazyListScope.animatedFormItem(key: String, content: @Composable () -> Unit) =
    item(key) { Box(Modifier.animateItem()) { content() } }

/** 协议选项带接口路径副标题，弹出层比默认宽一些。 */
private val ProviderProtocolPopoverWidth = 360.dp

@Composable
private fun ProviderProtocolSelector(state: ProviderFormUiState, actions: ProviderFormActions) {
    val anchor = rememberInfinityPopoverAnchor()
    InfinityValueRow(
        title = stringResource(R.string.provider_form_protocol_type),
        value = state.localProtocol.displayName,
        onClick = { if (!anchor.consumeRecentDismiss()) actions.onProtocolMenuExpandedChange(true) },
        modifier = Modifier.infinityPopoverAnchor(anchor).testTag("provider_protocol_picker"),
    )
    InfinityBottomSheet(
        show = state.protocolMenuExpanded,
        scrollContent = true,
        title = stringResource(R.string.provider_form_protocol_type),
        onDismiss = { actions.onProtocolMenuExpandedChange(false) },
        popover = InfinityPopoverSpec(anchor, width = ProviderProtocolPopoverWidth),
    ) {
        CUSTOM_PROTOCOL_OPTIONS.forEach { protocol ->
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = protocol.displayName,
                subtitle = protocol.defaultPath.takeIf(String::isNotEmpty),
                subtitleIsIdentifier = true,
                onClick = {
                    actions.onProtocolSelected(protocol)
                    actions.onProtocolMenuExpandedChange(false)
                },
                role = Role.RadioButton,
                selected = protocol == state.localProtocol,
                trailingContent = {
                    androidx.compose.material3.RadioButton(selected = protocol == state.localProtocol, onClick = null)
                },
            )
        }
    }
}

/** 预设提供商：与协议类型一致，点按打开底部面板（宽窗口下贴着本行弹出）选择。 */
@Composable
private fun ProviderPresetSelector(state: ProviderFormUiState, actions: ProviderFormActions) {
    val anchor = rememberInfinityPopoverAnchor()
    val title = stringResource(R.string.provider_form_preset_title)
    InfinitySettingsItem(
        title = title,
        leadingContent = { ProviderPresetIcon(state.selectedPreset, selected = false) },
        onClick = { if (!anchor.consumeRecentDismiss()) actions.onPresetMenuExpandedChange(true) },
        modifier = Modifier
            .infinityPopoverAnchor(anchor)
            .testTag(UiTags.PROVIDER_FORM_PRESET_PICKER)
            .semantics { stateDescription = state.selectedPreset.name },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selectedPreset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    InfinityBottomSheet(
        show = state.presetMenuExpanded,
        scrollContent = true,
        title = title,
        onDismiss = { actions.onPresetMenuExpandedChange(false) },
        popover = InfinityPopoverSpec(anchor),
    ) {
        state.availablePresets.forEach { preset ->
            val selected = preset == state.selectedPreset
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = preset.name,
                leadingContent = { ProviderPresetIcon(preset, selected) },
                onClick = { actions.onPresetSelected(preset) },
                role = Role.RadioButton,
                selected = selected,
                modifier = Modifier.testTag("provider_preset:${preset.name}"),
                trailingContent = {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun ProviderPresetIcon(preset: ProviderPreset, selected: Boolean) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    preset.iconRes?.let { iconId ->
        Icon(
            painter = painterResource(iconId),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    } ?: Icon(
        imageVector = Icons.Rounded.Psychology,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

/** 接口：公网 / Tailscale 内网分段与状态行、tailnet 设备、接口地址与地址提示；仅内网时显示脚注。 */
@Composable
private fun ProviderInterfaceSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    val tailnet = state.apiSource?.selected == ProviderApiSource.TAILNET
    InfinitySettingsSection(
        title = stringResource(R.string.provider_form_group_interface),
        footer = if (tailnet) stringResource(R.string.provider_api_source_footer) else null,
    ) {
        state.apiSource?.let { source ->
            ProviderApiSourceRows(
                source = source,
                tailnet = state.tailnetInfo,
                onSourceSelected = actions.onApiSourceSelected,
                onOpenTailscaleSettings = actions.onOpenTailscaleSettings,
            )
            InfinityGroupDivider()
        }
        // 选 Tailscale 内网时先选设备，接口地址随之填好。
        if (tailnet && selectableTailnetPeers(state.tailnetInfo.peers).isNotEmpty()) {
            TailnetPeerPicker(
                peers = state.tailnetInfo.peers,
                baseUrl = state.baseUrl,
                onPeerSelected = actions.onTailnetPeerSelected,
            )
            InfinityGroupDivider()
        }
        InfinityFieldRow(
            label = stringResource(R.string.provider_form_label_url),
            value = state.baseUrl,
            onValueChange = actions.onBaseUrlChange,
            placeholder = stringResource(R.string.provider_form_placeholder_url),
            monospace = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            error = if (!state.endpointValid) {
                stringResource(
                    when {
                        state.protocolUnsupported -> R.string.provider_form_protocol_unsupported
                        state.apiSource?.hint == ProviderApiSourceHint.TAILNET_NEEDS_HTTPS ->
                            R.string.provider_form_tailnet_https_required
                        else -> R.string.provider_form_https_required
                    },
                )
            } else {
                null
            },
        )
        ProviderAddressHint(hint = state.apiSource?.hint, onSourceSelected = actions.onApiSourceSelected)
    }
}

/** 凭证：无框密钥行；“密钥保存在本机安全存储中”作为脚注。 */
@Composable
private fun CloudProviderSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    InfinitySettingsSection(
        title = stringResource(R.string.provider_form_group_credentials),
        footer = stringResource(R.string.provider_form_credentials_footer),
    ) {
        SecretField(
            value = state.secretInput,
            onValueChange = actions.onSecretChange,
            hasStoredSecret = state.hasStoredCredential,
            onRevealRequest = actions.onRevealSecret,
            onClear = actions.onClearSecret,
            modifier = if (state.credentialKindMismatch) {
                Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
            } else {
                Modifier
            },
            placeholder = stringResource(
                if (state.usesVertexCredential) {
                    R.string.provider_form_paste_json
                } else {
                    R.string.provider_form_placeholder_api_key
                },
            ),
            label = stringResource(
                if (state.usesVertexCredential) {
                    R.string.provider_form_label_sa
                } else {
                    R.string.provider_form_label_api_key
                },
            ),
            supportingText = if (state.credentialKindMismatch) stringResource(R.string.provider_form_credential_kind_changed) else null,
            isError = state.credentialKindMismatch,
            frameless = true,
        )
    }
}

/** 底部操作栏：测试结果以状态条贴在“测试连接 / 保存”上方。 */
@Composable
private fun ProviderFormActionsSection(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    modifier: Modifier = Modifier,
) {
    val testing = state.connectionTestState == ProviderConnectionTestState.Testing
    val connectionStatusDescription = when (state.connectionTestState) {
        ProviderConnectionTestState.Idle -> null
        ProviderConnectionTestState.Testing -> stringResource(R.string.provider_form_testing)
        ProviderConnectionTestState.Success -> stringResource(R.string.common_cd_success)
        ProviderConnectionTestState.Unavailable -> stringResource(R.string.provider_form_connection_unavailable)
        ProviderConnectionTestState.Error -> stringResource(R.string.common_cd_failed)
    }
    val strip: Pair<InfinityStatusKind, String>? = when {
        state.saveFailed -> InfinityStatusKind.Error to stringResource(R.string.provider_form_save_failed)
        else -> when (state.connectionTestState) {
            ProviderConnectionTestState.Idle -> null
            ProviderConnectionTestState.Testing -> InfinityStatusKind.Info to stringResource(R.string.provider_form_testing)
            ProviderConnectionTestState.Success -> InfinityStatusKind.Success to stringResource(R.string.provider_form_connection_ok)
            ProviderConnectionTestState.Unavailable ->
                InfinityStatusKind.Info to stringResource(R.string.provider_form_connection_unavailable)
            ProviderConnectionTestState.Error -> InfinityStatusKind.Error to
                (state.connectionTestFailureMessage ?: stringResource(R.string.common_cd_failed))
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        AnimatedVisibility(visible = strip != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            strip?.let { (kind, message) ->
                InfinityStatusStrip(
                    kind = kind,
                    message = message,
                    busy = testing,
                    modifier = Modifier.testTag(UiTags.PROVIDER_FORM_STATUS_STRIP),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = actions.onTestConnection,
                enabled = !testing && !state.isSaving && state.endpointValid &&
                    !state.protocolUnsupported && !state.legacyMigrationBlocked &&
                    !state.credentialKindMismatch && state.sessionHeaderNameValid,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp)
                    .semantics {
                        connectionStatusDescription?.let { stateDescription = it }
                    },
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = stringResource(
                        if (testing) R.string.provider_form_testing else R.string.provider_form_btn_test,
                    ),
                    modifier = if (testing) Modifier.padding(start = 8.dp) else Modifier,
                )
            }
            Button(
                onClick = actions.onSave,
                enabled = state.endpointValid && !state.protocolUnsupported &&
                    !state.legacyMigrationBlocked && !state.credentialKindMismatch && state.sessionHeaderNameValid && !state.isSaving &&
                    state.connectionTestState != ProviderConnectionTestState.Testing,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = stringResource(
                        if (state.isSaving) R.string.provider_form_saving else R.string.provider_form_btn_save,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** 表单顶部的阻断提示（本地推理不可用、旧协议需迁移凭证）。 */
@Composable
private fun ProviderFormNotice(message: String) {
    InfinityStatusStrip(
        kind = InfinityStatusKind.Error,
        message = message,
        modifier = Modifier.padding(bottom = InfinitySpacing.Large),
    )
}

@Composable
private fun LocalProviderSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    InfinitySettingsSection(
        title = stringResource(R.string.settings_batch1_local_inference),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = actions.onLocalPrimaryAction,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.local_models_title))
        }
    }
}
}

internal fun isSecureProviderEndpoint(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun isProviderEndpointAllowed(protocol: ProtocolType, value: String): Boolean =
    when {
        protocol is ProtocolType.Local -> true
        isRetiredProviderProtocol(protocol) -> false
        !isSecureProviderEndpoint(value) -> false
        else -> runCatching {
            when (protocol) {
                ProtocolType.Google_VertexAI -> ProviderEndpointResolver.resolve(
                    protocol = protocol,
                    configuredBaseUrl = value,
                    target = ProviderEndpointTarget.VertexInference(
                        projectId = "endpoint-check",
                        location = VERTEX_DEFAULT_LOCATION,
                        model = "endpoint-check",
                        streaming = false,
                    ),
                )
                else -> ProviderEndpointResolver.resolve(
                    protocol = protocol,
                    configuredBaseUrl = value,
                    operation = ProviderEndpointOperation.INFERENCE,
                )
            }
        }.isSuccess
    }

internal fun isRetiredProviderProtocol(protocol: ProtocolType): Boolean =
    protocol == ProtocolType.Cohere_Chat || protocol == ProtocolType.Yi_ZeroOne

internal fun localProviderModelsForConnection(state: SlotState): List<String> =
    state.modelName.trim().takeIf { state.isLoaded && it.isNotEmpty() }?.let(::listOf).orEmpty()

internal fun credentialUpdateForSecretInput(
    input: String,
    hasStoredCredential: Boolean,
): CredentialUpdate = when {
    input.isNotBlank() -> CredentialUpdate.Replace(input)
    hasStoredCredential -> CredentialUpdate.Preserve
    else -> CredentialUpdate.Clear
}
