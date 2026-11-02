package com.promenar.infinity.ui.welcome

import com.promenar.infinity.ui.common.providerDisplayName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.openOnboardingFirstChat
import com.promenar.infinity.navigation.saveProviderConfiguration
import com.promenar.infinity.onboarding.OnboardingLanguageSelectionCoordinator
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.onboarding.createOnboardingAgentSession
import com.promenar.infinity.onboarding.onboardingOffersLinux
import com.promenar.infinity.ui.linux.settings.LinuxEnvironmentViewModel
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.settings.localProviderModelsForConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前安装是否在引导中提供 Linux 步骤：侧载渠道的运行时可用且主 ABI 为 arm64。 */
internal fun InfinityApplication.offersOnboardingLinux(): Boolean = onboardingOffersLinux(
    linuxRuntimeAvailable = linux.runtime.state.value !is LinuxRuntimeState.Unavailable,
    primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull(),
)

/** 首次引导的状态装配：步骤检查点来自 [store]，提供商、模型与 Linux 状态来自各自的视图模型。 */
@Composable
internal fun OnboardingRoute(
    store: OnboardingStateStore,
    languageSelection: OnboardingLanguageSelectionCoordinator,
    navController: NavHostController,
    modelsOverride: List<ModelInfo>?,
    forceLocalProbeFailureForTesting: Boolean,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val onboarding by store.state.collectAsState()
    val settings: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val linuxViewModel: LinuxEnvironmentViewModel? =
        if (store.linuxOffered) viewModel(factory = LinuxEnvironmentViewModel.factory(app)) else null
    val linuxState = linuxViewModel?.uiState?.collectAsState()?.value
    val providerModels by settings.providerModels.collectAsState()
    val fetchingModels by settings.isFetchingModels.collectAsState()
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf<OnboardingConnectStatus>(OnboardingConnectStatus.Idle) }
    var checkingModelId by remember { mutableStateOf<String?>(null) }
    var failedModelIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var linuxHidden by rememberSaveable { mutableStateOf(false) }
    val actionFailed = stringResource(R.string.onboarding_action_failed)
    val modelFailed = stringResource(R.string.onboarding_model_probe_failed)

    val allModels = modelsOverride ?: providerModels
    val candidates = onboardingModelCandidates(allModels, onboarding.providerId)
    val hiddenCount = allModels.count { it.providerId == onboarding.providerId } - candidates.size

    LaunchedEffect(onboarding.step) { errorMessage = null }
    LaunchedEffect(onboarding.step, onboarding.providerId) {
        val providerId = onboarding.providerId
        // 恢复到选模型步骤而本地还没有模型时补一次同步；连接成功时已经同步过。
        if (onboarding.step == OnboardingStep.MODEL && modelsOverride == null && providerId != null && candidates.isEmpty()) {
            settings.refreshProviderModels(providerId)
        }
    }

    val providerName by produceState<String?>(null, onboarding.providerId, onboarding.step) {
        value = onboarding.providerId?.let { id ->
            settings.getProviderSummary(id)?.let { providerDisplayName(it.name, it.protocolType, app.resources) }
        }
    }
    val agentName by produceState<String?>(null, onboarding.agentId) {
        value = onboarding.agentId?.let { app.agentRepository.getById(it)?.name }
    }
    val modelName = onboarding.modelId?.let { id -> allModels.firstOrNull { it.id == id }?.name ?: id }

    val finishConnect: suspend (OnboardingConnectOutcome) -> Unit = { outcome ->
        if (outcome is OnboardingConnectOutcome.Connected) {
            connect = OnboardingConnectStatus.Connected
            if (modelsOverride == null) settings.refreshProviderModels(outcome.providerId)?.join()
            store.recordProviderConnected(outcome.providerId)
            connect = OnboardingConnectStatus.Idle
            failedModelIds = emptySet()
        } else {
            connect = OnboardingConnectStatus.Problem(outcome)
        }
    }
    val save: OnboardingProviderSave = { existing, protocol, baseUrl, credential, model, name ->
        saveProviderConfiguration(app, existing, protocol, baseUrl, credential, model, name)
    }

    val actions = OnboardingActions(
        onLanguageSelected = { code ->
            if (!busy) {
                busy = true
                languageSelection.select(code)
                busy = false
            }
        },
        onSetLinuxPackage = { item, selected -> linuxViewModel?.setInstallPackage(item, selected) },
        onInstallLinux = {
            when (linuxState?.runtime) {
                is LinuxRuntimeState.NotInstalled, is LinuxRuntimeState.InstallInterrupted -> linuxViewModel?.install()
                else -> Unit
            }
            linuxHidden = false
            store.completeLinuxStep()
        },
        onSkipLinux = {
            linuxHidden = true
            store.completeLinuxStep()
        },
        onCancelLinux = { linuxViewModel?.cancelInstall() },
        onRetryLinux = {
            linuxHidden = false
            linuxViewModel?.install()
        },
        onHideLinux = { linuxHidden = true },
        onConnect = { draft ->
            if (connect != OnboardingConnectStatus.Connecting) {
                connect = OnboardingConnectStatus.Connecting
                scope.launch {
                    finishConnect(
                        connectOnboardingProvider(
                            draft = draft,
                            existingProviderId = onboarding.providerId,
                            probe = { config ->
                                withContext(Dispatchers.IO) {
                                    try {
                                        ProviderConnectionProbe.processScoped.probe(config)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE)
                                    }
                                }
                            },
                            save = save,
                            readSummary = settings::getProviderSummary,
                        ),
                    )
                }
            }
        },
        onConnectLocal = {
            if (connect != OnboardingConnectStatus.Connecting) {
                connect = OnboardingConnectStatus.Connecting
                scope.launch {
                    val slot = if (forceLocalProbeFailureForTesting) SlotState() else app.localInferenceEngine.mainSlot.value
                    finishConnect(connectOnboardingLocalProvider(localProviderModelsForConnection(slot), onboarding.providerId, save))
                }
            }
        },
        onOpenLocalModels = { navController.navigate(NavDestinations.LOCAL_MODELS) },
        onBackToProvider = {
            if (!busy) {
                connect = OnboardingConnectStatus.Idle
                store.returnToProvider()
            }
        },
        onUseModel = { modelId ->
            val model = candidates.firstOrNull { it.id == modelId }
            if (model != null && checkingModelId == null && !busy) {
                checkingModelId = modelId
                errorMessage = null
                scope.launch {
                    val verified = settings.verifyOnboardingModel(onboarding.providerId.orEmpty(), model)
                    if (verified == null) {
                        failedModelIds = failedModelIds + modelId
                        errorMessage = modelFailed
                    } else {
                        if (!verified.enabled) settings.updateModel(verified.copy(enabled = true))
                        store.selectModel(verified.id)
                    }
                    checkingModelId = null
                }
            }
        },
        onRefreshModels = {
            failedModelIds = emptySet()
            errorMessage = null
            onboarding.providerId?.let(settings::refreshProviderModels)
        },
        onBackToModel = { if (!busy) store.returnToModel() },
        onCreateAgent = { name ->
            if (!busy) {
                busy = true
                errorMessage = null
                scope.launch {
                    runCatching {
                        val modelId = requireNotNull(onboarding.modelId?.takeIf { it.isNotBlank() }) { "首次引导尚未选择模型" }
                        createOnboardingAgentSession(
                            modelId = modelId,
                            createAgent = {
                                app.createAgentUseCase(name = name, description = "", modelId = modelId, systemPrompt = "").id
                            },
                            newSessionId = IdGenerator::session,
                            createSession = app.sessionRepository::create,
                            loadSession = app.sessionRepository::getById,
                            deleteSession = app.sessionRepository::delete,
                            deleteAgent = app.agentRepository::delete,
                            chatStore = app.chatStore,
                            recordCheckpoint = store::recordAgentCreated,
                        )
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        errorMessage = actionFailed
                    }
                    busy = false
                }
            }
        },
        onStartChat = {
            onboarding.sessionId?.let { openOnboardingFirstChat(navController, it) }
        },
    )

    OnboardingScreen(
        state = OnboardingUiState(
            step = onboarding.step,
            linux = linuxState?.let { OnboardingLinuxStatus(it.runtime, it.installPackages, linuxHidden) },
            localInferenceAvailable = app.localInferenceRuntimeGate.isAvailable,
            connect = connect,
            models = candidates,
            hiddenModelCount = hiddenCount.coerceAtLeast(0),
            modelsLoading = fetchingModels,
            checkingModelId = checkingModelId,
            failedModelIds = failedModelIds,
            providerName = providerName,
            modelName = modelName,
            agentName = agentName,
            busy = busy,
            errorMessage = errorMessage,
        ),
        actions = actions,
    )
}
