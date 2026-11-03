package com.promenar.infinity.ui.welcome

import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.onboarding.OnboardingStep

/** 引导页的 Linux 状态；渠道或 ABI 不支持时整体为 null，Linux 步骤与胶囊都不出现。 */
internal data class OnboardingLinuxStatus(
    val runtime: LinuxRuntimeState,
    val packages: Set<LinuxOptionalPackage> = LinuxOptionalPackage.entries.toSet(),
    /** 用户在面板里选择跳过后，胶囊收起，完成页按未安装显示。 */
    val hidden: Boolean = false,
)

internal sealed interface OnboardingConnectStatus {
    data object Idle : OnboardingConnectStatus
    data object Connecting : OnboardingConnectStatus
    /** 探测与保存成功，正在拉取模型列表。 */
    data object Connected : OnboardingConnectStatus
    data class Problem(val outcome: OnboardingConnectOutcome) : OnboardingConnectStatus
}

internal data class OnboardingUiState(
    val step: OnboardingStep,
    val linux: OnboardingLinuxStatus? = null,
    val localInferenceAvailable: Boolean = false,
    val connect: OnboardingConnectStatus = OnboardingConnectStatus.Idle,
    val models: List<ModelInfo> = emptyList(),
    /** 当前提供商下被过滤掉的非对话模型数量。 */
    val hiddenModelCount: Int = 0,
    val modelsLoading: Boolean = false,
    val checkingModelId: String? = null,
    val failedModelIds: Set<String> = emptySet(),
    val providerName: String? = null,
    val modelName: String? = null,
    val agentName: String? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    /** 从备份恢复的面板；null 表示面板关闭。 */
    val restore: OnboardingRestoreSheetState? = null,
)

internal class OnboardingActions(
    val onLanguageSelected: (String) -> Unit = {},
    val onSetLinuxPackage: (LinuxOptionalPackage, Boolean) -> Unit = { _, _ -> },
    val onInstallLinux: () -> Unit = {},
    val onSkipLinux: () -> Unit = {},
    val onCancelLinux: () -> Unit = {},
    val onRetryLinux: () -> Unit = {},
    val onHideLinux: () -> Unit = {},
    val onConnect: (OnboardingProviderDraft) -> Unit = {},
    val onConnectLocal: () -> Unit = {},
    val onOpenLocalModels: () -> Unit = {},
    val onBackToProvider: () -> Unit = {},
    val onUseModel: (String) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onBackToModel: () -> Unit = {},
    val onCreateAgent: (String) -> Unit = {},
    val onStartChat: () -> Unit = {},
    val onOpenRestore: () -> Unit = {},
    val restore: OnboardingRestoreSheetActions = OnboardingRestoreSheetActions(),
)

/** 引导页的测试标签；设备测试与截图按步骤定位。 */
internal object OnboardingTags {
    fun step(step: OnboardingStep) = "onboarding_step_${step.name.lowercase()}"
    const val LANGUAGE_PREFIX = "onboarding_language_"
    const val BACK = "onboarding_back"
    const val LINUX_CAPSULE = "onboarding_linux_capsule"
    const val LINUX_INSTALL = "onboarding_linux_install"
    const val LINUX_SKIP = "onboarding_linux_skip"
    const val SERVICE_PREFIX = "onboarding_service_"
    const val SERVICE_MORE = "onboarding_service_more"
    const val SERVICE_SEARCH = "onboarding_service_search"
    const val SERVICE_LIST = "onboarding_service_list"
    const val PROVIDER_URL = "onboarding_provider_url"
    const val PROVIDER_SECRET = "onboarding_provider_secret"
    const val PROVIDER_CONNECT = "onboarding_provider_connect"
    const val PROVIDER_STATUS = "onboarding_provider_status"
    const val MODEL_LIST = "onboarding_model_list"
    const val MODEL_OPTION = "onboarding_model_option"
    const val MODEL_USE = "onboarding_model_use"
    const val MODEL_EMPTY = "onboarding_model_empty"
    const val MODEL_REFRESH = "onboarding_model_refresh"
    const val MODEL_BACK_PROVIDER = "onboarding_model_back_provider"
    const val AGENT_NAME = "onboarding_agent_name"
    const val AGENT_CREATE = "onboarding_agent_create"
    const val START_CHAT = "onboarding_start_chat"
}

/** 进度轨道上的步骤：语言与完成页不占位。 */
internal fun onboardingRailSteps(linuxOffered: Boolean): List<OnboardingStep> = buildList {
    if (linuxOffered) add(OnboardingStep.LINUX)
    add(OnboardingStep.PROVIDER)
    add(OnboardingStep.MODEL)
    add(OnboardingStep.AGENT)
}
