package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMotion
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.infinityPointerHand
import com.promenar.infinity.ui.common.isDefaultProviderName
import com.promenar.infinity.ui.common.label
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityPillRow
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
import com.promenar.infinity.data.proxy.BuiltInProxyUnavailableException
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
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityOptionIconBadge
import com.promenar.infinity.ui.common.InfinityOptionItem
import com.promenar.infinity.ui.common.InfinityOptionSectionHeader
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.OpenRouterOAuthFailureKind
import com.promenar.infinity.ui.common.OpenRouterOAuthUiState
import com.promenar.infinity.ui.common.ProviderPresetBadgeChip
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.openExternalBrowserUrl
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberOpenRouterOAuthController
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 预设分组：主流官方云、订阅与聚合网关、本地与自定义。 */
enum class ProviderPresetCategory(@param:StringRes val titleRes: Int) {
    OFFICIAL_CLOUD(R.string.provider_preset_cat_official),
    SUBSCRIPTION_GATEWAY(R.string.provider_preset_cat_subscription),
    LOCAL_CUSTOM(R.string.provider_preset_cat_custom),
}

/** 明确中立的地域/授权能力分组（供设置与新手引导下拉预设弹层共用）。 */
enum class ProviderPresetRegion(@param:StringRes val titleRes: Int) {
    OAUTH_ONE_CLICK(R.string.provider_preset_region_oauth),
    CHINA(R.string.provider_preset_region_china),
    GLOBAL(R.string.provider_preset_region_global),
    CUSTOM_LOCAL(R.string.provider_preset_region_custom_local),
}

enum class ProviderBadgeTone { PRIMARY, SUCCESS, NEUTRAL }

/** 紧跟提供商名称的紧凑权益小徽章（一键登录 / 免费额度 / 含免费模型 / 每日免费 / 新客试用等）。 */
enum class ProviderPresetBadge(@param:StringRes val labelRes: Int, val tone: ProviderBadgeTone) {
    ONE_CLICK_OAUTH(R.string.provider_badge_one_click, ProviderBadgeTone.PRIMARY),
    FREE_MODELS(R.string.provider_badge_free_models, ProviderBadgeTone.SUCCESS),
    FREE_TIER(R.string.provider_badge_free_tier, ProviderBadgeTone.SUCCESS),
    FREE_DAILY(R.string.provider_badge_free_daily, ProviderBadgeTone.SUCCESS),
    NEW_USER_TRIAL(R.string.provider_badge_free_trial, ProviderBadgeTone.SUCCESS),
    STARTER_CREDIT(R.string.provider_badge_starter_credit, ProviderBadgeTone.SUCCESS),
    DIRECT_PAYG(R.string.provider_badge_payg, ProviderBadgeTone.NEUTRAL),
}

/** 同一平台下的端点地域或协议变体（如 OpenAI Compat vs Anthropic Messages）。 */
data class ProviderPresetVariant(
    val label: String,
    val protocolType: ProtocolType,
    val baseUrl: String,
    @param:StringRes val labelRes: Int? = null,
)

@Composable
fun ProviderPresetVariant.displayLabel(): String = labelRes?.let { stringResource(it) } ?: label

/** [name] 是稳定键（分支判断、testTag），界面名称取 [label]。 */
data class ProviderPreset(
    val name: String,
    val protocolType: ProtocolType,
    val defaultBaseUrl: String,
    val iconRes: Int? = null,
    /** 需要翻译的预设名；为空时品牌名 [name] 直接显示。 */
    @param:StringRes val labelRes: Int? = null,
    val category: ProviderPresetCategory = ProviderPresetCategory.OFFICIAL_CLOUD,
    val variants: List<ProviderPresetVariant> = emptyList(),
    val region: ProviderPresetRegion = ProviderPresetRegion.GLOBAL,
    val badges: List<ProviderPresetBadge> = emptyList(),
    val consoleKeyUrl: String? = null,
    val supportsOAuth: Boolean = false,
    val needsProxyInChinaMainland: Boolean = false,
)

fun ProviderPreset.label(resources: Resources): String = labelRes?.let(resources::getString) ?: name

@Composable
fun ProviderPreset.label(): String = labelRes?.let { stringResource(it) } ?: name

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
    val variantProtocol: ProtocolType? = null,
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
    val useBuiltInProxy: Boolean = false,
    val builtInProxyInfo: ProviderBuiltInProxyInfo = ProviderBuiltInProxyInfo(),
    val openRouterOAuthState: OpenRouterOAuthUiState = OpenRouterOAuthUiState.Idle,
    /** “删除提供商”行：已保存的提供商可删，内置默认灰显并说明原因，新建时不出现。 */
    val deleteEntry: UnavailableReasons.ProviderDeleteEntry = UnavailableReasons.ProviderDeleteEntry.HIDDEN,
)

internal data class ProviderFormActions(
    val onBack: () -> Unit = {},
    val onPresetMenuExpandedChange: (Boolean) -> Unit = {},
    val onPresetSelected: (ProviderPreset) -> Unit = {},
    val onVariantSelected: (ProviderPresetVariant) -> Unit = {},
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
    val onUseBuiltInProxyChange: (Boolean) -> Unit = {},
    val onTailnetPeerSelected: (com.promenar.infinity.data.tailnet.TailnetPeer) -> Unit = {},
    val onOpenTailscaleSettings: () -> Unit = {},
    val onOpenBuiltInProxySettings: () -> Unit = {},
    val onStartOpenRouterOAuth: () -> Unit = {},
    val onReopenOpenRouterOAuth: () -> Unit = {},
    val onCancelOpenRouterOAuth: () -> Unit = {},
    val onOpenConsoleKeyUrl: (String) -> Unit = {},
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
    ProviderPreset(
        name = "OpenAI",
        protocolType = ProtocolType.OpenAI_ChatCompletions,
        defaultBaseUrl = "https://api.openai.com",
        iconRes = R.drawable.ic_provider_openai,
        region = ProviderPresetRegion.GLOBAL,
        consoleKeyUrl = "https://platform.openai.com/api-keys",
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "DeepSeek",
        protocolType = ProtocolType.DeepSeek,
        defaultBaseUrl = "https://api.deepseek.com",
        iconRes = R.drawable.ic_provider_deepseek,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.DIRECT_PAYG),
        consoleKeyUrl = "https://platform.deepseek.com/api_keys",
    ),
    ProviderPreset(
        name = "Anthropic",
        protocolType = ProtocolType.Anthropic_Messages,
        defaultBaseUrl = "https://api.anthropic.com",
        iconRes = R.drawable.ic_provider_anthropic,
        region = ProviderPresetRegion.GLOBAL,
        consoleKeyUrl = "https://console.anthropic.com/settings/keys",
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "Gemini",
        protocolType = ProtocolType.Google_Gemini,
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        iconRes = R.drawable.ic_provider_gemini,
        region = ProviderPresetRegion.GLOBAL,
        badges = listOf(ProviderPresetBadge.FREE_TIER),
        consoleKeyUrl = "https://aistudio.google.com/apikey",
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "Vertex AI",
        protocolType = ProtocolType.Google_VertexAI,
        defaultBaseUrl = "https://us-central1-aiplatform.googleapis.com",
        iconRes = R.drawable.ic_provider_gemini,
        region = ProviderPresetRegion.GLOBAL,
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "Kimi",
        protocolType = ProtocolType.Moonshot_Kimi,
        defaultBaseUrl = "https://api.moonshot.cn",
        iconRes = R.drawable.ic_provider_kimi,
        region = ProviderPresetRegion.CHINA,
        consoleKeyUrl = "https://platform.moonshot.cn/console/api-keys",
        variants = listOf(
            ProviderPresetVariant("CN (.cn)", ProtocolType.Moonshot_Kimi, "https://api.moonshot.cn", R.string.provider_variant_cn),
            ProviderPresetVariant("International (.ai)", ProtocolType.Generic_OpenAI_Compat, "https://api.moonshot.ai/v1", R.string.provider_variant_global),
        ),
    ),
    ProviderPreset(
        name = "通义千问",
        protocolType = ProtocolType.Qwen_DashScope,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
        iconRes = R.drawable.ic_provider_qwen,
        labelRes = R.string.provider_preset_qwen,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.NEW_USER_TRIAL),
        consoleKeyUrl = "https://bailian.console.aliyun.com/?apiKey=1",
        variants = listOf(
            ProviderPresetVariant("Beijing", ProtocolType.Qwen_DashScope, "https://dashscope.aliyuncs.com/compatible-mode", R.string.provider_variant_beijing),
            ProviderPresetVariant("Singapore", ProtocolType.Qwen_DashScope, "https://dashscope-intl.aliyuncs.com/compatible-mode", R.string.provider_variant_singapore),
        ),
    ),
    ProviderPreset(
        name = "智谱",
        protocolType = ProtocolType.Zhipu_GLM,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas",
        iconRes = R.drawable.ic_provider_zhipu,
        labelRes = R.string.provider_preset_zhipu,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.NEW_USER_TRIAL),
        consoleKeyUrl = "https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys",
        variants = listOf(
            ProviderPresetVariant("CN Standard", ProtocolType.Zhipu_GLM, "https://open.bigmodel.cn/api/paas", R.string.provider_variant_cn_standard),
            ProviderPresetVariant("CN Coding", ProtocolType.Generic_OpenAI_Compat, "https://open.bigmodel.cn/api/coding/paas/v4", R.string.provider_variant_cn_coding),
            ProviderPresetVariant("International (Z.AI)", ProtocolType.Generic_OpenAI_Compat, "https://api.z.ai/api/paas/v4", R.string.provider_variant_intl_standard),
            ProviderPresetVariant("International Coding (Z.AI)", ProtocolType.Generic_OpenAI_Compat, "https://api.z.ai/api/coding/paas/v4", R.string.provider_variant_intl_coding),
        ),
    ),
    ProviderPreset(
        name = "豆包",
        protocolType = ProtocolType.Doubao_ByteDance,
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api",
        iconRes = R.drawable.ic_provider_doubao,
        labelRes = R.string.provider_preset_ark_media,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.NEW_USER_TRIAL),
        consoleKeyUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
    ),
    ProviderPreset(
        name = "MiniMax",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://api.minimax.cn/v1",
        iconRes = R.drawable.ic_provider_minimax,
        region = ProviderPresetRegion.CHINA,
        consoleKeyUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        variants = listOf(
            ProviderPresetVariant("CN OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.cn/v1", R.string.provider_variant_cn_openai),
            ProviderPresetVariant("CN Anthropic", ProtocolType.Anthropic_Messages, "https://api.minimax.cn/anthropic", R.string.provider_variant_cn_anthropic),
            ProviderPresetVariant("International OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.io/v1", R.string.provider_variant_intl_openai),
            ProviderPresetVariant("International Anthropic", ProtocolType.Anthropic_Messages, "https://api.minimax.io/anthropic", R.string.provider_variant_intl_anthropic),
        ),
    ),
    ProviderPreset(
        name = "xAI",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://api.x.ai/v1",
        iconRes = R.drawable.ic_provider_xai,
        region = ProviderPresetRegion.GLOBAL,
        consoleKeyUrl = "https://console.x.ai/",
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "百川",
        protocolType = ProtocolType.Baichuan,
        defaultBaseUrl = "https://api.baichuan-ai.com",
        iconRes = R.drawable.ic_provider_baichuan,
        labelRes = R.string.provider_preset_baichuan,
        region = ProviderPresetRegion.CHINA,
    ),
    ProviderPreset(
        name = "Mistral",
        protocolType = ProtocolType.Mistral_Chat,
        defaultBaseUrl = "https://api.mistral.ai",
        iconRes = R.drawable.ic_provider_mistral,
        region = ProviderPresetRegion.GLOBAL,
        consoleKeyUrl = "https://console.mistral.ai/api-keys",
        needsProxyInChinaMainland = true,
    ),
    // 订阅计划与聚合网关
    ProviderPreset(
        name = "OpenCode Go",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://opencode.ai/zen/go/v1",
        iconRes = R.drawable.ic_provider_opencode,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.GLOBAL,
        needsProxyInChinaMainland = true,
        variants = listOf(
            ProviderPresetVariant("OpenAI Compat", ProtocolType.Generic_OpenAI_Compat, "https://opencode.ai/zen/go/v1", R.string.provider_variant_openai_compat),
            ProviderPresetVariant("Anthropic", ProtocolType.Anthropic_Messages, "https://opencode.ai/zen/go", R.string.provider_variant_anthropic),
        ),
    ),
    ProviderPreset(
        name = "OpenCode Zen",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://opencode.ai/zen/v1",
        iconRes = R.drawable.ic_provider_opencode,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.GLOBAL,
        needsProxyInChinaMainland = true,
        variants = listOf(
            ProviderPresetVariant("OpenAI Compat", ProtocolType.Generic_OpenAI_Compat, "https://opencode.ai/zen/v1", R.string.provider_variant_openai_compat),
            ProviderPresetVariant("Anthropic", ProtocolType.Anthropic_Messages, "https://opencode.ai/zen", R.string.provider_variant_anthropic),
        ),
    ),
    ProviderPreset(
        name = "百炼 Coding Plan",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://coding.dashscope.aliyuncs.com/v1",
        iconRes = R.drawable.ic_provider_qwen,
        labelRes = R.string.provider_preset_bailian_coding,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        variants = listOf(
            ProviderPresetVariant("OpenAI Compat", ProtocolType.Generic_OpenAI_Compat, "https://coding.dashscope.aliyuncs.com/v1", R.string.provider_variant_openai_compat),
            ProviderPresetVariant("Anthropic", ProtocolType.Anthropic_Messages, "https://coding.dashscope.aliyuncs.com/apps/anthropic", R.string.provider_variant_anthropic),
        ),
    ),
    ProviderPreset(
        name = "智谱 Coding Plan",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
        iconRes = R.drawable.ic_provider_zhipu,
        labelRes = R.string.provider_preset_zhipu_coding,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        variants = listOf(
            ProviderPresetVariant("CN OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://open.bigmodel.cn/api/coding/paas/v4", R.string.provider_variant_cn_openai),
            ProviderPresetVariant("CN Anthropic", ProtocolType.Anthropic_Messages, "https://open.bigmodel.cn/api/anthropic", R.string.provider_variant_cn_anthropic),
            ProviderPresetVariant("International OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://api.z.ai/api/coding/paas/v4", R.string.provider_variant_intl_openai),
            ProviderPresetVariant("International Anthropic", ProtocolType.Anthropic_Messages, "https://api.z.ai/api/anthropic", R.string.provider_variant_intl_anthropic),
        ),
    ),
    ProviderPreset(
        name = "MiniMax Token Plan",
        protocolType = ProtocolType.Anthropic_Messages,
        defaultBaseUrl = "https://api.minimax.cn/anthropic",
        iconRes = R.drawable.ic_provider_minimax,
        labelRes = R.string.provider_preset_minimax_token_plan,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        variants = listOf(
            ProviderPresetVariant("CN Anthropic", ProtocolType.Anthropic_Messages, "https://api.minimax.cn/anthropic", R.string.provider_variant_cn_anthropic),
            ProviderPresetVariant("CN OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.cn/v1", R.string.provider_variant_cn_openai),
            ProviderPresetVariant("International Anthropic", ProtocolType.Anthropic_Messages, "https://api.minimax.io/anthropic", R.string.provider_variant_intl_anthropic),
            ProviderPresetVariant("International OpenAI", ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.io/v1", R.string.provider_variant_intl_openai),
        ),
    ),
    ProviderPreset(
        name = "Kimi Coding Plan",
        protocolType = ProtocolType.Anthropic_Messages,
        defaultBaseUrl = "https://api.kimi.com/coding",
        iconRes = R.drawable.ic_provider_kimi,
        labelRes = R.string.provider_preset_kimi_coding,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        variants = listOf(
            ProviderPresetVariant("Anthropic", ProtocolType.Anthropic_Messages, "https://api.kimi.com/coding", R.string.provider_variant_anthropic),
            ProviderPresetVariant("OpenAI Compat", ProtocolType.Generic_OpenAI_Compat, "https://api.kimi.com/coding/v1", R.string.provider_variant_openai_compat),
        ),
    ),
    ProviderPreset(
        name = "方舟 Coding Plan",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3",
        iconRes = R.drawable.ic_provider_doubao,
        labelRes = R.string.provider_preset_ark_coding,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        variants = listOf(
            ProviderPresetVariant("Coding Plan", ProtocolType.Generic_OpenAI_Compat, "https://ark.cn-beijing.volces.com/api/coding/v3", R.string.provider_variant_coding),
            ProviderPresetVariant("Standard API", ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api", R.string.provider_variant_standard),
        ),
    ),
    ProviderPreset(
        name = "OpenRouter",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        iconRes = R.drawable.ic_provider_openrouter,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.OAUTH_ONE_CLICK,
        badges = listOf(ProviderPresetBadge.ONE_CLICK_OAUTH, ProviderPresetBadge.FREE_MODELS),
        consoleKeyUrl = "https://openrouter.ai/settings/keys",
        supportsOAuth = true,
        needsProxyInChinaMainland = true,
    ),
    ProviderPreset(
        name = "SiliconFlow",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        iconRes = R.drawable.ic_provider_siliconflow,
        labelRes = R.string.provider_preset_siliconflow,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.STARTER_CREDIT, ProviderPresetBadge.FREE_MODELS),
        consoleKeyUrl = "https://cloud.siliconflow.cn/account/ak",
        variants = listOf(
            ProviderPresetVariant("CN (.cn)", ProtocolType.Generic_OpenAI_Compat, "https://api.siliconflow.cn/v1", R.string.provider_variant_cn),
            ProviderPresetVariant("International", ProtocolType.Generic_OpenAI_Compat, "https://api.siliconflow.com/v1", R.string.provider_variant_global),
        ),
    ),
    ProviderPreset(
        name = "ModelScope",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        defaultBaseUrl = "https://api-inference.modelscope.cn/v1",
        iconRes = R.drawable.ic_provider_qwen,
        labelRes = R.string.provider_preset_modelscope,
        category = ProviderPresetCategory.SUBSCRIPTION_GATEWAY,
        region = ProviderPresetRegion.CHINA,
        badges = listOf(ProviderPresetBadge.FREE_DAILY),
        consoleKeyUrl = "https://modelscope.cn/my/myaccesstoken",
    ),
    // 本地与自定义
    ProviderPreset("Local", ProtocolType.Local, "", R.drawable.ic_provider_local, R.string.provider_preset_local, ProviderPresetCategory.LOCAL_CUSTOM, region = ProviderPresetRegion.CUSTOM_LOCAL),
    ProviderPreset("Custom", ProtocolType.Generic_OpenAI_Compat, "", R.drawable.ic_provider_custom, R.string.provider_preset_custom, ProviderPresetCategory.LOCAL_CUSTOM, region = ProviderPresetRegion.CUSTOM_LOCAL),
)

/** 通用协议按主机与路径精准匹配预设，保留自定义提供商的协议选择入口。 */
internal fun matchProviderPreset(presets: List<ProviderPreset>, protocol: ProtocolType, baseUrl: String): ProviderPreset? {
    fun parseUri(value: String): java.net.URI? = try {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) null else java.net.URI(trimmed)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: java.net.URISyntaxException) {
        null
    }

    val inputUri = parseUri(baseUrl)
    val inputHost = inputUri?.host?.lowercase()
    val inputPath = inputUri?.path?.trimEnd('/')?.lowercase().orEmpty()

    if (inputHost != null) {
        fun matchesEndpoint(url: String): Boolean {
            val uri = parseUri(url)
            val vHost = uri?.host?.lowercase()
            val vPath = uri?.path?.trimEnd('/')?.lowercase().orEmpty()
            return vHost == inputHost && vPath.isNotEmpty() && inputPath.startsWith(vPath)
        }
        // 第一优先级：主默认端点 host + 路径前缀精准命中（且协议一致或兼容），避免 Coding Plan 被同名标准预设截胡
        presets.firstOrNull { preset ->
            preset.name != "Custom" && preset.name != "Local" &&
                preset.protocolType == protocol && matchesEndpoint(preset.defaultBaseUrl)
        }?.let { return it }
        // 第二优先级：主默认端点 host + 路径前缀命中（允许在同预设内切换了协议变体）
        presets.firstOrNull { preset ->
            preset.name != "Custom" && preset.name != "Local" && matchesEndpoint(preset.defaultBaseUrl)
        }?.let { return it }
        // 第三优先级：优先匹配主协议一致的预设变体（避免国际 Coding / Token Plan 被同品牌标准预设抢先截胡）
        presets.firstOrNull { preset ->
            preset.name != "Custom" && preset.name != "Local" &&
                preset.protocolType == protocol &&
                preset.variants.any { it.protocolType == protocol && matchesEndpoint(it.baseUrl) }
        }?.let { return it }
        // 第四优先级：命中所属预设下的任意变体端点
        presets.firstOrNull { preset ->
            preset.name != "Custom" && preset.name != "Local" &&
                preset.variants.any { matchesEndpoint(it.baseUrl) }
        }?.let { return it }
        // 兼容历史域名（如 MiniMax 旧国内域名 api.minimaxi.com）
        if (inputHost == "api.minimaxi.com") {
            presets.firstOrNull { it.name == "MiniMax" }?.let { return it }
        }
    }

    if (protocol != ProtocolType.Generic_OpenAI_Compat) {
        return presets.firstOrNull { it.protocolType == protocol && it.category != ProviderPresetCategory.SUBSCRIPTION_GATEWAY }
            ?: presets.firstOrNull { it.protocolType == protocol }
    }

    if (inputHost != null) {
        val hostMatch = presets.firstOrNull { preset ->
            preset.name != "Custom" && preset.name != "Local" &&
                (listOf(ProviderPresetVariant(preset.name, preset.protocolType, preset.defaultBaseUrl)) + preset.variants).any { variant ->
                    variant.protocolType == protocol && parseUri(variant.baseUrl)?.host?.lowercase() == inputHost
                }
        }
        if (hostMatch != null) return hostMatch
    }
    return presets.firstOrNull { it.name == "Custom" }
}

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
    onOpenBuiltInProxySettings: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onSave: suspend (ProtocolType, String, CredentialUpdate, String, String?, Boolean) -> String = { _, _, _, _, _, _ -> "" },
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
    var variantProtocol by remember { mutableStateOf<ProtocolType?>(null) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var connectionTestState by remember { mutableStateOf(ProviderConnectionTestState.Idle) }
    var connectionTestFailureMessage by remember { mutableStateOf<String?>(null) }
    var legacyUnsupportedProtocol by remember { mutableStateOf<ProtocolType?>(null) }
    var recoveringUnknownProtocol by remember { mutableStateOf(false) }
    // API 源不保存：未手动切换时跟随地址自动识别。
    var manualApiSource by remember { mutableStateOf<ProviderApiSource?>(null) }
    var useBuiltInProxy by remember { mutableStateOf(false) }
    // 新建提供商时测试结果在保存拿到 ID 后再记录；编辑时记录载入时的连接参数，用于判断保存是否改了连接。
    var lastProbeResult by remember { mutableStateOf<ProviderConnectionProbeResult?>(null) }
    var loadedConnection by remember { mutableStateOf<Triple<ProtocolType, String, Boolean>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val tailnetState by app.tailnetManager.state.collectAsStateWithLifecycle()
    val proxyState by app.builtInProxyManager.state.collectAsStateWithLifecycle()
    val noticeController = LocalInfinityNoticeController.current
    val oauthFilledMsg = stringResource(R.string.oauth_openrouter_success_filled)
    val openRouterOAuth = rememberOpenRouterOAuthController { apiKey ->
        secretInput = apiKey
        credentialUpdate = CredentialUpdate.Replace(apiKey)
        connectionTestState = ProviderConnectionTestState.Idle
        connectionTestFailureMessage = null
        if (proxyState.running) useBuiltInProxy = true
        noticeController?.show(severity = InfinityNoticeSeverity.SUCCESS, message = oauthFilledMsg)
    }
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
                name = config.name.takeUnless(::isDefaultProviderName).orEmpty()
                baseUrl = config.baseUrl
                useBuiltInProxy = config.useBuiltInProxy
                hasCredential = config.hasApiKey || config.hasVertexCredentials
                originalUsesVertex = config.hasVertexCredentials
                legacyUnsupportedProtocol = config.protocolType.takeIf(::isRetiredProviderProtocol)
                val matched = matchProviderPreset(availableProviderPresets, config.protocolType, config.baseUrl)
                selectedPreset = when {
                    matched != null -> matched
                    legacyUnsupportedProtocol != null -> ProviderPreset(
                        name = config.protocolType.persistedId,
                        protocolType = config.protocolType,
                        defaultBaseUrl = config.baseUrl,
                        iconRes = config.protocolType.iconRes,
                        labelRes = config.protocolType.labelRes,
                    )
                    else -> availableProviderPresets.last()
                }
                if (selectedPreset.name == "Custom") {
                    localProtocol = config.protocolType.takeUnless {
                        it is ProtocolType.Local && !app.localInferenceRuntimeGate.isAvailable
                    } ?: ProtocolType.Generic_OpenAI_Compat
                    variantProtocol = null
                } else if (config.protocolType != selectedPreset.protocolType) {
                    variantProtocol = config.protocolType
                } else {
                    variantProtocol = null
                }
                loadedConnection = Triple(config.protocolType, config.baseUrl, config.useBuiltInProxy)
            } else {
                viewModel.getUnsupportedProviderSummary(providerId)?.let { unsupported ->
                    recoveringUnknownProtocol = true
                    name = unsupported.name.takeUnless(::isDefaultProviderName).orEmpty()
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

    val effectiveProtocol = when {
        selectedPreset.name == "Custom" -> localProtocol
        variantProtocol != null -> variantProtocol!!
        else -> selectedPreset.protocolType
    }
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
    val effectiveUseBuiltInProxy = useBuiltInProxy && apiSource?.selected == ProviderApiSource.PUBLIC
    val activeProxyNodeName = proxyState.activeNode?.let { id -> proxyState.nodes.firstOrNull { it.id == id }?.name }
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
        val savedProxy = effectiveUseBuiltInProxy
        val savedId = onSave(protocol, url, credential, model, providerName, savedProxy)
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
                    val connectionChanged = loadedConnection != Triple(effectiveProtocol, baseUrl, effectiveUseBuiltInProxy) ||
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
                                    useBuiltInProxy = effectiveUseBuiltInProxy,
                                    providerId = providerId,
                                ))
                            } finally {
                                transient.fill('\u0000')
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: BuiltInProxyUnavailableException) {
                        ProviderConnectionProbeResult.Failure(
                            com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE,
                            detail = resources.getString(R.string.provider_form_test_failed_proxy_unavailable),
                        )
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
                        val reasonText = probeResult.detail?.takeIf {
                            probeResult.reason == com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE
                        } ?: when (probeResult.reason) {
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
                        null,
                        false,
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

    val routeGlobalNotice = LocalInfinityNoticeController.current
    val currentNoticeKey = Triple(saveFailed, connectionTestState, connectionTestFailureMessage)
    var lastNoticeKey by remember { mutableStateOf<Triple<Boolean, ProviderConnectionTestState, String?>?>(null) }
    LaunchedEffect(currentNoticeKey, routeGlobalNotice) {
        val previous = lastNoticeKey
        lastNoticeKey = currentNoticeKey
        val host = routeGlobalNotice ?: return@LaunchedEffect
        if (previous == currentNoticeKey) return@LaunchedEffect
        val terminalNotice: Pair<InfinityStatusKind, String>? = when {
            saveFailed -> InfinityStatusKind.Error to resources.getString(R.string.provider_form_save_failed)
            else -> when (connectionTestState) {
                ProviderConnectionTestState.Idle, ProviderConnectionTestState.Testing -> null
                ProviderConnectionTestState.Success ->
                    InfinityStatusKind.Success to resources.getString(R.string.provider_form_connection_ok)
                ProviderConnectionTestState.Unavailable ->
                    InfinityStatusKind.Info to resources.getString(R.string.provider_form_connection_unavailable)
                ProviderConnectionTestState.Error -> InfinityStatusKind.Error to
                    (connectionTestFailureMessage ?: resources.getString(R.string.common_cd_failed))
            }
        }
        val (kind, message) = terminalNotice ?: return@LaunchedEffect
        host.showStatus(kind, message)
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
            variantProtocol = variantProtocol,
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
            useBuiltInProxy = useBuiltInProxy,
            builtInProxyInfo = ProviderBuiltInProxyInfo(
                available = app.builtInProxyManager.available,
                running = proxyState.running,
                activeNodeName = activeProxyNodeName,
            ),
            openRouterOAuthState = openRouterOAuth.uiState,
            deleteEntry = UnavailableReasons.providerDeleteEntry(providerId),
        ),
        actions = ProviderFormActions(
            onBack = onNavigateBack,
            onPresetMenuExpandedChange = { presetMenuExpanded = it },
            onPresetSelected = { preset ->
                selectedPreset = preset
                variantProtocol = null
                openRouterOAuth.cancel()
                if (preset.name != "Custom" && preset.name != "Local") {
                    name = preset.label(resources)
                    baseUrl = preset.defaultBaseUrl
                }
                manualApiSource = null
                presetMenuExpanded = false
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onVariantSelected = { variant ->
                variantProtocol = variant.protocolType
                baseUrl = variant.baseUrl
                manualApiSource = null
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
            onUseBuiltInProxyChange = {
                useBuiltInProxy = it
                connectionTestState = ProviderConnectionTestState.Idle
                connectionTestFailureMessage = null
            },
            onTailnetPeerSelected = { peer ->
                com.promenar.infinity.data.tailnet.TailnetEndpoints.addressForPeer(peer, baseUrl)?.let { address ->
                    baseUrl = address
                    connectionTestState = ProviderConnectionTestState.Idle
                    connectionTestFailureMessage = null
                }
            },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
            onOpenBuiltInProxySettings = onOpenBuiltInProxySettings,
            onStartOpenRouterOAuth = openRouterOAuth::start,
            onReopenOpenRouterOAuth = openRouterOAuth::reopenBrowser,
            onCancelOpenRouterOAuth = openRouterOAuth::cancel,
            onOpenConsoleKeyUrl = { url -> openExternalBrowserUrl(context, url) },
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProviderFormContent(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    initialPeerPickerOpen: Boolean = false,
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
        // 键盘弹出时只让出键盘高出导航栏的部分：底部按钮栏自带导航栏内边距，整段 imePadding 会把导航栏算两次。
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
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
                        ProviderInterfaceSection(state = state, actions = actions, initialPeerPickerOpen = initialPeerPickerOpen)
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
                        val scheme = MaterialTheme.colorScheme
                        val deleteLabel = stringResource(R.string.provider_form_delete)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = scheme.errorContainer.copy(alpha = 0.42f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = InfinitySpacing.Large)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button, onClickLabel = deleteLabel, onClick = actions.onDelete)
                                .infinityPointerHand()
                                .semantics {
                                    role = Role.Button
                                    contentDescription = deleteLabel
                                }
                                .testTag(UiTags.PROVIDER_FORM_DELETE),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = scheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = deleteLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                    color = scheme.error,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = !state.isLocal, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    ProviderFormActionsSection(
                        state = state,
                        actions = actions,
                        modifier = Modifier.padding(
                            start = InfinitySpacing.ScreenHorizontal,
                            top = 10.dp,
                            end = InfinitySpacing.ScreenHorizontal,
                            // 键盘弹出时按钮贴近键盘，只留小间距；收起后保持原有手势区留白。
                            bottom = contentPadding.calculateBottomPadding() -
                                if (WindowInsets.isImeVisible) ProviderFormActionsImeTrim else 0.dp,
                        ),
                    )
                }
            }
        }
    }
}

/** 键盘弹出时从底部按钮栏留白中减去的高度：骨架给的 24dp 底部留白减到 12dp。 */
private val ProviderFormActionsImeTrim = 12.dp

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
        value = state.localProtocol.label(),
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
            val selected = protocol == state.localProtocol
            val (drawableRes, fallbackIcon, brandKey) = when (protocol) {
                ProtocolType.Generic_OpenAI_Compat -> Triple(R.drawable.ic_provider_openai, null, "openai")
                ProtocolType.OpenAI_Responses -> Triple(null, Icons.Rounded.Hub, "openai")
                ProtocolType.Anthropic_Messages -> Triple(R.drawable.ic_provider_anthropic, null, "anthropic")
                ProtocolType.Google_Gemini -> Triple(R.drawable.ic_provider_gemini, null, "google")
                else -> Triple(null, Icons.Rounded.Psychology, null)
            }
            InfinityOptionItem(
                title = protocol.label(),
                subtitle = protocol.defaultPath.takeIf(String::isNotEmpty),
                subtitleIsIdentifier = true,
                icon = fallbackIcon,
                drawableRes = drawableRes,
                accentColor = brandAccentColor(brandKey),
                selected = selected,
                onClick = {
                    actions.onProtocolSelected(protocol)
                    actions.onProtocolMenuExpandedChange(false)
                },
            )
        }
    }
}

/** 预设提供商：点按打开带搜索、中立地域分组与权益小徽章的底部面板（宽窗口下贴着本行弹出）选择。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderPresetSelector(state: ProviderFormUiState, actions: ProviderFormActions) {
    val anchor = rememberInfinityPopoverAnchor()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val title = stringResource(R.string.provider_form_preset_title)
    val selectedLabel = state.selectedPreset.label()
    val hostOnly = providerHostLabel(state.selectedPreset.defaultBaseUrl).takeIf { it.isNotEmpty() }
    var presetQuery by remember(state.presetMenuExpanded) { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(role = Role.Button) {
                if (!anchor.consumeRecentDismiss()) actions.onPresetMenuExpandedChange(true)
            }
            .infinityPointerHand()
            .infinityPopoverAnchor(anchor)
            .testTag(UiTags.PROVIDER_FORM_PRESET_PICKER)
            .semantics { stateDescription = selectedLabel }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderPresetIcon(state.selectedPreset, selected = false, tileSize = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                state.selectedPreset.badges.forEach { badge ->
                    ProviderPresetBadgeChip(badge)
                }
            }
            if (hostOnly != null) {
                Text(
                    text = hostOnly,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = scheme.onSurfaceVariant,
        )
    }
    InfinityBottomSheet(
        show = state.presetMenuExpanded,
        scrollContent = true,
        title = title,
        onDismiss = { actions.onPresetMenuExpandedChange(false) },
        popover = InfinityPopoverSpec(anchor, width = ProviderProtocolPopoverWidth),
    ) {
        InfinitySearchBar(
            value = presetQuery,
            onValueChange = { presetQuery = it },
            placeholder = stringResource(R.string.provider_preset_search_placeholder),
            borderless = true,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .testTag("provider_preset_search"),
        )
        val trimmedQuery = presetQuery.trim()
        val regions = listOf(
            ProviderPresetRegion.OAUTH_ONE_CLICK,
            ProviderPresetRegion.CHINA,
            ProviderPresetRegion.GLOBAL,
            ProviderPresetRegion.CUSTOM_LOCAL,
        )
        regions.forEach { region ->
            val items = state.availablePresets.filter { preset ->
                preset.region == region && (
                    trimmedQuery.isEmpty() ||
                        preset.name.contains(trimmedQuery, ignoreCase = true) ||
                        preset.label(resources).contains(trimmedQuery, ignoreCase = true) ||
                        preset.defaultBaseUrl.contains(trimmedQuery, ignoreCase = true)
                    )
            }
            if (items.isNotEmpty()) {
                InfinityOptionSectionHeader(title = stringResource(region.titleRes))
                BettboxListGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    items.forEachIndexed { idx, preset ->
                        if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                        val selected = preset == state.selectedPreset
                        val itemHost = providerHostLabel(preset.defaultBaseUrl).takeIf { it.isNotEmpty() }
                        val subtitle = when {
                            itemHost != null && preset.variants.size > 1 -> {
                                val countLabel = stringResource(R.string.provider_preset_variants_count, preset.variants.size)
                                "$itemHost · $countLabel" // wrap-check: allow 预设主机与变体数摘要
                            }
                            itemHost != null -> itemHost
                            else -> null
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.RadioButton) { actions.onPresetSelected(preset) }
                                .infinityPointerHand()
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = selected
                                }
                                .testTag("provider_preset:${preset.name}")
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProviderPresetIcon(preset, selected, tileSize = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = preset.label(),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                    )
                                    preset.badges.forEach { badge ->
                                        ProviderPresetBadgeChip(badge)
                                    }
                                }
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProviderPresetIcon(preset: ProviderPreset, selected: Boolean, tileSize: Dp = 34.dp) {
    val scheme = MaterialTheme.colorScheme
    val brand = remember(preset.name, preset.defaultBaseUrl) {
        ModelBrandResolver.brandKey(preset.name, preset.defaultBaseUrl)
    }
    val accent = brandAccentColor(brand)
    val bg = when {
        accent != null -> accent.copy(alpha = if (selected) 0.20f else 0.12f)
        selected -> scheme.primaryContainer.copy(alpha = 0.72f)
        else -> scheme.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val rim = if (accent != null) accent.copy(alpha = 0.20f) else scheme.outlineVariant.copy(alpha = 0.35f)
    val tint = accent ?: if (selected) scheme.primary else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(0.5.dp, rim, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val iconRes = preset.iconRes
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(tileSize * 0.54f),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(tileSize * 0.54f),
            )
        }
    }
}

/** 接口：公网 / Tailscale 内网分段与状态行、预设端点变体快切、tailnet 设备、接口地址与地址提示；仅内网时显示脚注。 */
@Composable
private fun ProviderInterfaceSection(
    state: ProviderFormUiState,
    actions: ProviderFormActions,
    initialPeerPickerOpen: Boolean,
) {
    val tailnet = state.apiSource?.selected == ProviderApiSource.TAILNET
    val variants = state.selectedPreset.variants
    val effectiveProtocol = when {
        state.selectedPreset.name == "Custom" -> state.localProtocol
        state.variantProtocol != null -> state.variantProtocol
        else -> state.selectedPreset.protocolType
    }
    InfinitySettingsSection(
        title = stringResource(R.string.provider_form_group_interface),
        footer = if (tailnet) stringResource(R.string.provider_api_source_footer) else null,
        typographicFooter = true,
    ) {
        state.apiSource?.let { source ->
            ProviderApiSourceRows(
                source = source,
                tailnet = state.tailnetInfo,
                onSourceSelected = actions.onApiSourceSelected,
                onOpenTailscaleSettings = actions.onOpenTailscaleSettings,
                useBuiltInProxy = state.useBuiltInProxy,
                builtInProxy = state.builtInProxyInfo,
                onUseBuiltInProxyChange = actions.onUseBuiltInProxyChange,
                onOpenBuiltInProxySettings = actions.onOpenBuiltInProxySettings,
            )
            InfinityGroupDivider()
        }
        if (variants.size > 1) {
            val activeVariant = variants.firstOrNull { variant ->
                variant.protocolType == effectiveProtocol &&
                    state.baseUrl.trim().trimEnd('/').equals(variant.baseUrl.trimEnd('/'), ignoreCase = true)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.provider_preset_variant_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfinitySegmentedControl(
                    segments = variants.mapIndexed { index, variant ->
                        val isDuplicateProtocol = variants.count { it.protocolType == variant.protocolType } > 1
                        InfinitySegment(
                            value = variant,
                            label = variant.displayLabel(),
                            tag = if (isDuplicateProtocol) {
                                "provider_variant:${variant.protocolType.persistedId}:$index"
                            } else {
                                "provider_variant:${variant.protocolType.persistedId}"
                            },
                        )
                    },
                    selected = activeVariant,
                    onSelect = actions.onVariantSelected,
                    fillWidth = variants.size <= 2,
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth().testTag("provider_preset_variants"),
                )
            }
            InfinityGroupDivider()
        }
        // 选 Tailscale 内网时先选设备，接口地址随之填好。
        if (tailnet && selectableTailnetPeers(state.tailnetInfo.peers).isNotEmpty()) {
            TailnetPeerPicker(
                peers = state.tailnetInfo.peers,
                baseUrl = state.baseUrl,
                onPeerSelected = actions.onTailnetPeerSelected,
                initialOpen = initialPeerPickerOpen,
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
            help = com.promenar.infinity.ui.common.cleartextUrlHelp(state.baseUrl),
            error = if (!state.endpointValid) {
                stringResource(
                    when {
                        state.protocolUnsupported -> R.string.provider_form_protocol_unsupported
                        else -> R.string.provider_form_http_url_required
                    },
                )
            } else {
                null
            },
        )
        ProviderAddressHint(hint = state.apiSource?.hint, onSourceSelected = actions.onApiSourceSelected)
    }
}

/** 凭证与授权：支持 OpenRouter 一键 OAuth 复用、控制台快捷领 Key 与无框密钥行。 */
@Composable
private fun CloudProviderSection(state: ProviderFormUiState, actions: ProviderFormActions) {
    val scheme = MaterialTheme.colorScheme
    val supportsOAuth = state.selectedPreset.supportsOAuth
    val consoleUrl = state.selectedPreset.consoleKeyUrl?.takeUnless { state.usesVertexCredential }
    InfinitySettingsSection(
        title = stringResource(R.string.provider_form_group_credentials),
        footer = stringResource(R.string.provider_form_credentials_footer),
        typographicFooter = true,
    ) {
        if (supportsOAuth) {
            ProviderFormOpenRouterOAuthBanner(
                oauthState = state.openRouterOAuthState,
                onStart = actions.onStartOpenRouterOAuth,
                onReopen = actions.onReopenOpenRouterOAuth,
                onCancel = actions.onCancelOpenRouterOAuth,
                onOpenBuiltInProxy = actions.onOpenBuiltInProxySettings,
            )
            InfinityGroupDivider()
        }
        if (consoleUrl != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.RowHorizontal, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        if (supportsOAuth) R.string.provider_form_manual_key_hint
                        else R.string.provider_form_console_helper_label,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { actions.onOpenConsoleKeyUrl(consoleUrl) },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 26.dp).testTag("provider_form_open_console"),
                ) {
                    Text(
                        text = stringResource(R.string.provider_form_get_key_console),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp))
                }
            }
        }
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

@Composable
private fun ProviderFormOpenRouterOAuthBanner(
    oauthState: OpenRouterOAuthUiState,
    onStart: () -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
    onOpenBuiltInProxy: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (oauthState) {
            OpenRouterOAuthUiState.Idle -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.primary.copy(alpha = 0.11f))
                        .border(0.8.dp, scheme.primary.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                        .testTag("provider_form_openrouter_oauth_card"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Login,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.oauth_openrouter_card_title),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.oauth_openrouter_card_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = onStart,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp).testTag("provider_form_openrouter_oauth_btn"),
                    ) {
                        Text(stringResource(R.string.oauth_openrouter_btn_signin), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            is OpenRouterOAuthUiState.WaitingBrowser, OpenRouterOAuthUiState.Exchanging -> {
                val exchanging = oauthState is OpenRouterOAuthUiState.Exchanging
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(0.8.dp, scheme.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(
                                    if (exchanging) R.string.oauth_openrouter_exchanging_title
                                    else R.string.oauth_openrouter_waiting_title,
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.oauth_openrouter_waiting_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!exchanging) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onCancel,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f).height(34.dp),
                            ) {
                                Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = onReopen,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f).height(34.dp),
                            ) {
                                Text(stringResource(R.string.oauth_openrouter_btn_reopen), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            is OpenRouterOAuthUiState.Failed -> {
                val isNetwork = oauthState.kind == OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED
                val tint = if (isNetwork) domain.warning else scheme.error
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tint.copy(alpha = 0.10f))
                        .border(0.8.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                when (oauthState.kind) {
                                    OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED -> R.string.oauth_openrouter_err_network_title
                                    OpenRouterOAuthFailureKind.REJECTED -> R.string.oauth_openrouter_err_rejected
                                    OpenRouterOAuthFailureKind.STATE_MISMATCH -> R.string.oauth_openrouter_err_state
                                    OpenRouterOAuthFailureKind.BROWSER_UNAVAILABLE -> R.string.oauth_openrouter_err_browser
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                    }
                    if (isNetwork) {
                        Text(
                            text = stringResource(R.string.oauth_openrouter_err_network_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isNetwork) {
                            OutlinedButton(
                                onClick = onOpenBuiltInProxy,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f).height(34.dp),
                            ) {
                                Text(stringResource(R.string.oauth_openrouter_proxy_action), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalButton(
                            onClick = onStart,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Text(stringResource(R.string.oauth_openrouter_btn_signin), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
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
    val globalNotice = LocalInfinityNoticeController.current
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
    // 进行中状态由“测试”按钮自带加载圈承担；终态结果与保存失败在路由层发往全局悬浮胶囊，纯内容区仅在无全局胶囊时回退显示状态条。
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        if (globalNotice == null) {
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
        }
        // 文字单行；窄屏大字号并排放不下时整组竖排，保存在上；去除旧软盘图标，改用⚡测试 + ✓保存。
        InfinityPillRow(modifier = Modifier.fillMaxWidth(), spacing = 10.dp) {
            FilledTonalButton(
                onClick = actions.onTestConnection,
                enabled = !testing && !state.isSaving && state.endpointValid &&
                    !state.protocolUnsupported && !state.legacyMigrationBlocked &&
                    !state.credentialKindMismatch && state.sessionHeaderNameValid,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        connectionStatusDescription?.let { stateDescription = it }
                    },
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = stringResource(
                        if (testing) R.string.provider_form_testing else R.string.provider_form_btn_test,
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Button(
                onClick = actions.onSave,
                enabled = state.endpointValid && !state.protocolUnsupported &&
                    !state.legacyMigrationBlocked && !state.credentialKindMismatch && state.sessionHeaderNameValid && !state.isSaving &&
                    state.connectionTestState != ProviderConnectionTestState.Testing,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                Text(
                    text = stringResource(
                        if (state.isSaving) R.string.provider_form_saving else R.string.provider_form_btn_save,
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                    softWrap = false,
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

internal fun isHttpProviderEndpoint(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun isProviderEndpointAllowed(protocol: ProtocolType, value: String): Boolean =
    when {
        protocol is ProtocolType.Local -> true
        isRetiredProviderProtocol(protocol) -> false
        !isHttpProviderEndpoint(value) -> false
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
