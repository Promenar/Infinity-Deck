package com.promenar.infinity.ui.welcome

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.R
import com.promenar.infinity.data.remote.oauth.ApiKeyClipboardDetector
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.CompactOutlinedTextField
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.OpenRouterOAuthFailureKind
import com.promenar.infinity.ui.common.OpenRouterOAuthUiState
import com.promenar.infinity.ui.common.ProviderPresetBadgeChip
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.common.label as protocolLabel
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.common.openExternalBrowserUrl
import com.promenar.infinity.ui.common.rememberOpenRouterOAuthController
import com.promenar.infinity.ui.settings.CUSTOM_PROTOCOL_OPTIONS
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.ProviderPreset
import com.promenar.infinity.ui.settings.ProviderPresetRegion
import com.promenar.infinity.ui.settings.ProviderPresetIcon
import com.promenar.infinity.ui.settings.displayLabel
import com.promenar.infinity.ui.settings.isProviderEndpointAllowed
import com.promenar.infinity.ui.settings.label
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

private const val PRESET_CUSTOM = "Custom"
private const val PRESET_LOCAL = "Local"
private const val PRESET_OPENROUTER = "OpenRouter"
private const val PRESET_SILICONFLOW = "SiliconFlow"

/** 新手引导预设分组（不含本地 GGUF，明确采用中立地域/能力分组，与常规设置保持一致）。 */
internal fun onboardingServiceGroups(): List<Pair<Int, List<ProviderPreset>>> {
    val onboardingEligible = PROVIDER_PRESETS.filterNot { it.name == PRESET_LOCAL }
    val orderedRegions = listOf(
        ProviderPresetRegion.OAUTH_ONE_CLICK to R.string.provider_preset_region_oauth,
        ProviderPresetRegion.CHINA to R.string.provider_preset_region_china,
        ProviderPresetRegion.GLOBAL to R.string.provider_preset_region_global,
        ProviderPresetRegion.CUSTOM_LOCAL to R.string.provider_preset_region_custom_only,
    )
    return orderedRegions.mapNotNull { (region, titleRes) ->
        val items = onboardingEligible.filter { it.region == region }
        if (items.isEmpty()) null else titleRes to items
    }
}

internal fun defaultOnboardingPresetName(languageTag: String): String =
    if (languageTag.startsWith("zh", ignoreCase = true)) PRESET_SILICONFLOW else PRESET_OPENROUTER

private fun presetByName(name: String): ProviderPreset =
    PROVIDER_PRESETS.firstOrNull { it.name == name } ?: PROVIDER_PRESETS.first()

@Composable
private fun ProviderPreset.displayName(): String = when (name) {
    PRESET_CUSTOM -> stringResource(R.string.onboarding_provider_custom)
    else -> label()
}

@Composable
private fun ProviderPreset.hostLabel(): String = when (name) {
    PRESET_CUSTOM -> stringResource(R.string.onboarding_provider_custom_host)
    else -> Uri.parse(defaultBaseUrl).host ?: defaultBaseUrl
}

@Composable
private fun ProviderPresetRegion.shortTagLabel(): String = when (this) {
    ProviderPresetRegion.OAUTH_ONE_CLICK -> stringResource(R.string.onboarding_v2_region_tag_oauth)
    ProviderPresetRegion.CHINA -> stringResource(R.string.onboarding_v2_region_tag_china)
    ProviderPresetRegion.GLOBAL -> stringResource(R.string.onboarding_v2_region_tag_global)
    ProviderPresetRegion.CUSTOM_LOCAL -> stringResource(R.string.onboarding_v2_region_tag_custom)
}

@Composable
internal fun OnboardingProviderStep(state: OnboardingUiState, actions: OnboardingActions, wide: Boolean) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localeLanguage = remember(configuration) {
        configuration.locales[0]?.language.orEmpty()
    }
    val defaultPreset = remember(localeLanguage) { defaultOnboardingPresetName(localeLanguage) }

    var presetName by rememberSaveable { mutableStateOf(defaultPreset) }
    var baseUrl by rememberSaveable { mutableStateOf(presetByName(presetName).defaultBaseUrl) }
    var variantProtocolIndex by rememberSaveable { mutableIntStateOf(-1) }
    // 密钥只留在内存，不写入可被持久化的界面保存状态。
    var secret by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var customProtocolIndex by rememberSaveable { mutableIntStateOf(0) }
    var presetSheetOpen by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var forceManualKeyForOAuth by rememberSaveable { mutableStateOf(false) }
    var clipboardCandidate by remember { mutableStateOf<String?>(null) }

    val selected = presetByName(presetName)
    val isCustom = selected.name == PRESET_CUSTOM
    val activeVariant = selected.variants.getOrNull(variantProtocolIndex)
    val protocol = when {
        isCustom -> CUSTOM_PROTOCOL_OPTIONS[customProtocolIndex]
        activeVariant != null -> activeVariant.protocolType
        else -> selected.protocolType
    }
    val useBuiltInProxy = state.builtInProxyRunning && selected.needsProxyInChinaMainland
    val showOAuthMode = selected.supportsOAuth && !forceManualKeyForOAuth
    val connecting = state.connect == OnboardingConnectStatus.Connecting || state.connect == OnboardingConnectStatus.Connected
    val endpointValid = isProviderEndpointAllowed(protocol, baseUrl.trim())
    val canConnect = !connecting && !state.busy && endpointValid && secret.isNotBlank()

    val openRouterOAuth = rememberOpenRouterOAuthController { mintedKey ->
        secret = mintedKey
        actions.onConnect(
            OnboardingProviderDraft(
                protocol = selected.protocolType,
                baseUrl = selected.defaultBaseUrl,
                secret = mintedKey,
                name = selected.name,
                useBuiltInProxy = state.builtInProxyRunning,
            ),
        )
    }

    // 仅在应用回到前台且用户尚未填写密钥时温和检查剪贴板 API 密钥形态（不自动提交、不记录明文）。
    val lifecycleOwner = LocalLifecycleOwner.current
    val inspection = LocalInspectionMode.current
    DisposableEffect(lifecycleOwner, selected.name, showOAuthMode) {
        fun inspectClipboard() {
            if (inspection || showOAuthMode || secret.isNotBlank()) {
                clipboardCandidate = null
                return
            }
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = runCatching {
                manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
            clipboardCandidate = ApiKeyClipboardDetector.detectCandidate(clipText)
        }
        inspectClipboard()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) inspectClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selectPreset: (ProviderPreset) -> Unit = { picked ->
        if (picked.name != presetName) {
            openRouterOAuth.cancel()
            presetName = picked.name
            baseUrl = picked.defaultBaseUrl
            variantProtocolIndex = -1
            forceManualKeyForOAuth = false
            advancedExpanded = picked.name == PRESET_CUSTOM
            clipboardCandidate = null
        }
    }

    val isCnLocale = localeLanguage.startsWith("zh", ignoreCase = true)

    OnboardingStepPage(
        footer = {
            ConnectStatusText(state.connect)
            if (!showOAuthMode) {
                RiseIn(8) {
                    OnboardingPrimaryButton(
                        onClick = {
                            actions.onConnect(
                                OnboardingProviderDraft(
                                    protocol = protocol,
                                    baseUrl = baseUrl,
                                    secret = secret,
                                    name = name.ifBlank { selected.name.takeUnless { isCustom } },
                                    useBuiltInProxy = useBuiltInProxy,
                                ),
                            )
                        },
                        enabled = canConnect,
                        modifier = Modifier.testTag(OnboardingTags.PROVIDER_CONNECT),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(InfinitySpacing.Small))
                        } else {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            stringResource(
                                when {
                                    connecting -> R.string.onboarding_provider_connecting
                                    state.connect is OnboardingConnectStatus.Problem -> R.string.onboarding_provider_reconnect
                                    else -> R.string.onboarding_provider_connect
                                },
                            ),
                            style = OnboardingType.button(),
                        )
                    }
                }
            }
        },
    ) {
        RiseIn(0) { ConnectionWire(state.connect) }
        OnboardingHeading(
            stringResource(R.string.onboarding_v2_title),
            stringResource(R.string.onboarding_v2_desc),
            index = 1,
        )
        if (onboardingRestoreStep(linuxOffered = state.linux != null) == OnboardingStep.PROVIDER) {
            RiseIn(3) { OnboardingRestoreEntry(onClick = actions.onOpenRestore, enabled = !connecting && !state.busy) }
        }

        // 统一下拉预设选择行（与「设置 → 添加提供商」保持一致，紧跟免费/试用/一键登录小徽章）
        RiseIn(3) {
            OnboardingPresetDropdownTrigger(
                selected = selected,
                enabled = !connecting,
                onClick = { presetSheetOpen = true },
            )
        }

        // 下方凭据区根据所选预设动态切换：OpenRouter OAuth 主卡片 vs 新手 2 步领 Key 卡片
        RiseIn(4) {
            if (showOAuthMode) {
                OnboardingOpenRouterOAuthCard(
                    oauthState = openRouterOAuth.uiState,
                    showChinaProxyHint = isCnLocale || !state.builtInProxyRunning,
                    builtInProxyRunning = state.builtInProxyRunning,
                    connecting = connecting,
                    onStartOAuth = openRouterOAuth::start,
                    onReopenOAuth = openRouterOAuth::reopenBrowser,
                    onCancelOAuth = openRouterOAuth::cancel,
                    onOpenBuiltInProxy = actions.onOpenBuiltInProxy,
                    onSwitchToManual = {
                        openRouterOAuth.cancel()
                        forceManualKeyForOAuth = true
                    },
                    onSwitchPreset = { presetSheetOpen = true },
                )
            } else {
                OnboardingApiKeyBeginnerCard(
                    selected = selected,
                    protocol = protocol,
                    secret = secret,
                    onSecretChange = {
                        secret = it
                        if (it.isNotBlank()) clipboardCandidate = null
                    },
                    clipboardCandidate = clipboardCandidate,
                    onFillClipboard = { candidate ->
                        secret = candidate
                        clipboardCandidate = null
                    },
                    onOpenConsole = { url -> openExternalBrowserUrl(context, url) },
                    onSwitchBackToOAuth = if (selected.supportsOAuth) {
                        { forceManualKeyForOAuth = false }
                    } else {
                        null
                    },
                    connecting = connecting,
                )
            }
        }

        // 高级配置折叠项：新手默认隐藏接口地址与自定义名称，自定义接口自动展开
        if (!showOAuthMode) {
            RiseIn(5) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isCustom) {
                        OutlinedButton(
                            onClick = { advancedExpanded = !advancedExpanded },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp).testTag("onboarding_advanced_toggle"),
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.onboarding_v2_advanced_toggle),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Icon(
                                imageVector = if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    AnimatedVisibility(visible = isCustom || advancedExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (isCustom) {
                                CustomProtocolPicker(customProtocolIndex, enabled = !connecting) { customProtocolIndex = it }
                            }
                            if (selected.variants.size > 1) {
                                InfinitySegmentedControl(
                                    segments = selected.variants.mapIndexed { idx, variant ->
                                        InfinitySegment(idx, variant.displayLabel())
                                    },
                                    selected = if (variantProtocolIndex in selected.variants.indices) variantProtocolIndex else 0,
                                    onSelect = { idx ->
                                        variantProtocolIndex = idx
                                        baseUrl = selected.variants[idx].baseUrl
                                    },
                                    enabled = !connecting,
                                    fillWidth = selected.variants.size <= 2,
                                    compact = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            CompactOutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = stringResource(R.string.provider_form_label_url),
                                placeholder = stringResource(R.string.provider_form_placeholder_url),
                                enabled = !connecting,
                                isError = baseUrl.isNotBlank() && !endpointValid,
                                supportingText = if (baseUrl.isNotBlank() && !endpointValid) {
                                    stringResource(R.string.provider_form_http_url_required)
                                } else {
                                    com.promenar.infinity.ui.common.cleartextUrlHelp(baseUrl)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.PROVIDER_URL),
                            )
                            CompactOutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = stringResource(R.string.provider_form_label_name),
                                placeholder = selected.displayName(),
                                enabled = !connecting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (presetSheetOpen) {
        ServiceSheet(
            groups = onboardingServiceGroups(),
            selected = selected,
            onSelect = selectPreset,
            onDismiss = { presetSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingPresetDropdownTrigger(
    selected: ProviderPreset,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val host = selected.hostLabel()
    val regionTag = selected.region.shortTagLabel()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.provider_form_preset_title),
            style = OnboardingType.eyebrow(),
            color = onboardingFaintText,
        )
        OnboardingCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag(OnboardingTags.SERVICE_MORE),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProviderPresetIcon(selected, selected = true, tileSize = 38.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selected.displayName(),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                            maxLines = 1,
                        )
                        selected.badges.forEach { badge ->
                            ProviderPresetBadgeChip(badge)
                        }
                    }
                    Text(
                        text = "$host · $regionTag", // wrap-check: allow 预设主机与地域标签
                        style = OnboardingType.caption(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OnboardingOpenRouterOAuthCard(
    oauthState: OpenRouterOAuthUiState,
    showChinaProxyHint: Boolean,
    builtInProxyRunning: Boolean,
    connecting: Boolean,
    onStartOAuth: () -> Unit,
    onReopenOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onOpenBuiltInProxy: () -> Unit,
    onSwitchToManual: () -> Unit,
    onSwitchPreset: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    OnboardingCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (oauthState) {
                OpenRouterOAuthUiState.Idle -> {
                    Button(
                        onClick = onStartOAuth,
                        enabled = !connecting,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .testTag("onboarding_openrouter_oauth_btn"),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(
                                if (connecting) R.string.onboarding_provider_connecting
                                else R.string.oauth_openrouter_btn_primary,
                            ),
                            style = OnboardingType.button(),
                        )
                    }

                    OutlinedButton(
                        onClick = onSwitchToManual,
                        enabled = !connecting,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.oauth_openrouter_btn_manual),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                is OpenRouterOAuthUiState.WaitingBrowser, OpenRouterOAuthUiState.Exchanging -> {
                    val exchanging = oauthState is OpenRouterOAuthUiState.Exchanging
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            WireEnd { Icon(Icons.Rounded.PhoneAndroid, null, tint = scheme.onSurface, modifier = Modifier.size(18.dp)) }
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.2.dp)
                            WireEnd { Icon(Icons.Rounded.Cloud, null, tint = scheme.primary, modifier = Modifier.size(18.dp)) }
                        }
                        Text(
                            text = stringResource(
                                if (exchanging) R.string.oauth_openrouter_exchanging_title
                                else R.string.oauth_openrouter_waiting_title,
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.oauth_openrouter_waiting_desc),
                            style = OnboardingType.caption(),
                            color = scheme.onSurfaceVariant,
                        )
                        if (!exchanging) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onCancelOAuth,
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                ) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                FilledTonalButton(
                                    onClick = onReopenOAuth,
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                ) {
                                    Text(stringResource(R.string.oauth_openrouter_btn_reopen))
                                }
                            }
                        }
                    }
                }

                is OpenRouterOAuthUiState.Failed -> {
                    val isNetwork = oauthState.kind == OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = (if (isNetwork) domain.warning else scheme.error).copy(alpha = 0.16f),
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiOff,
                                        contentDescription = null,
                                        tint = if (isNetwork) domain.warning else scheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(
                                        when (oauthState.kind) {
                                            OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED -> R.string.oauth_openrouter_err_network_title
                                            OpenRouterOAuthFailureKind.REJECTED -> R.string.oauth_openrouter_err_rejected
                                            OpenRouterOAuthFailureKind.STATE_MISMATCH -> R.string.oauth_openrouter_err_state
                                            OpenRouterOAuthFailureKind.BROWSER_UNAVAILABLE -> R.string.oauth_openrouter_err_browser
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                )
                                if (isNetwork) {
                                    Text(
                                        text = stringResource(R.string.oauth_openrouter_err_network_desc),
                                        style = OnboardingType.caption(),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onSwitchPreset,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                            ) {
                                Text(stringResource(R.string.onboarding_v2_switch_preset), maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = if (isNetwork) onOpenBuiltInProxy else onStartOAuth,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (isNetwork) R.string.oauth_openrouter_proxy_action
                                        else R.string.oauth_openrouter_btn_signin,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onSwitchToManual,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                        ) {
                            Text(stringResource(R.string.oauth_openrouter_btn_manual), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (showChinaProxyHint) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (builtInProxyRunning) domain.success else domain.warning).copy(alpha = 0.10f),
                    border = BorderStroke(
                        0.8.dp,
                        (if (builtInProxyRunning) domain.success else domain.warning).copy(alpha = 0.30f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = if (builtInProxyRunning) domain.success else domain.warning,
                                modifier = Modifier.padding(top = 2.dp).size(18.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(
                                        if (builtInProxyRunning) R.string.oauth_openrouter_proxy_running
                                        else R.string.oauth_openrouter_proxy_title,
                                    ),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.oauth_openrouter_proxy_desc),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onOpenBuiltInProxy,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.align(Alignment.End).heightIn(min = 32.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.oauth_openrouter_proxy_action),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = onboardingFaintText, modifier = Modifier.size(14.dp))
                Text(
                    text = stringResource(R.string.oauth_openrouter_privacy_note),
                    style = OnboardingType.caption().copy(fontSize = 11.sp),
                    color = onboardingFaintText,
                )
            }
        }
    }
}

@Composable
private fun OnboardingApiKeyBeginnerCard(
    selected: ProviderPreset,
    protocol: ProtocolType,
    secret: String,
    onSecretChange: (String) -> Unit,
    clipboardCandidate: String?,
    onFillClipboard: (String) -> Unit,
    onOpenConsole: (String) -> Unit,
    onSwitchBackToOAuth: (() -> Unit)?,
    connecting: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val vertex = protocol is ProtocolType.Google_VertexAI
    val consoleUrl = selected.consoleKeyUrl?.takeUnless { vertex }

    OnboardingCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onSwitchBackToOAuth != null) {
                OutlinedButton(
                    onClick = onSwitchBackToOAuth,
                    enabled = !connecting,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.oauth_openrouter_btn_back_oauth), style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider(color = onboardingLineColor)
            }

            if (consoleUrl != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepNumberBadge("1")
                    Text(
                        text = stringResource(R.string.onboarding_v2_step1_console, selected.displayName()),
                        style = OnboardingType.body().copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                    )
                }
                FilledTonalButton(
                    onClick = { onOpenConsole(consoleUrl) },
                    enabled = !connecting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp).testTag("onboarding_open_console"),
                ) {
                    Text(stringResource(R.string.onboarding_v2_open_console), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = stringResource(R.string.onboarding_v2_step1_hint),
                    style = OnboardingType.caption(),
                    color = scheme.onSurfaceVariant,
                )

                HorizontalDivider(color = onboardingLineColor)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepNumberBadge("2")
                    Text(
                        text = stringResource(R.string.onboarding_v2_step2_paste),
                        style = OnboardingType.body().copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                    )
                }
            }

            // 前台剪贴板 API 密钥智能识别条（脱敏展示，一键填入）
            if (clipboardCandidate != null && secret.isBlank() && !vertex) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, scheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_clipboard_banner"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.onboarding_v2_clipboard_title),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.primary,
                            )
                            Text(
                                text = ApiKeyClipboardDetector.maskSecret(clipboardCandidate),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onFillClipboard(clipboardCandidate) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(stringResource(R.string.onboarding_v2_clipboard_fill), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            SecretField(
                value = secret,
                onValueChange = onSecretChange,
                hasStoredSecret = false,
                onRevealRequest = { null },
                onClear = { onSecretChange("") },
                modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.PROVIDER_SECRET),
                placeholder = stringResource(if (vertex) R.string.provider_form_paste_json else R.string.provider_form_placeholder_api_key),
                label = stringResource(if (vertex) R.string.provider_form_label_sa else R.string.provider_form_label_api_key),
                compact = true,
            )
        }
    }
}

@Composable
private fun StepNumberBadge(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.primary.copy(alpha = 0.18f),
        modifier = Modifier.size(22.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.primary,
            )
        }
    }
}

/** 四段等分放不下完整协议名，分段控件用短名称保持单行。 */
@Composable
private fun ProtocolType.shortLabel(): String = when (this) {
    ProtocolType.Generic_OpenAI_Compat -> stringResource(R.string.provider_protocol_short_generic_openai)
    ProtocolType.Anthropic_Messages -> stringResource(R.string.provider_protocol_short_anthropic)
    ProtocolType.OpenAI_Responses -> stringResource(R.string.provider_protocol_short_openai_responses)
    ProtocolType.Google_Gemini -> stringResource(R.string.provider_protocol_short_gemini)
    else -> protocolLabel()
}

@Composable
internal fun CustomProtocolPicker(selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        Text(
            stringResource(R.string.provider_form_protocol_type),
            style = OnboardingType.eyebrow(),
            color = onboardingFaintText,
        )
        InfinitySegmentedControl(
            segments = CUSTOM_PROTOCOL_OPTIONS.mapIndexed { index, protocol -> InfinitySegment(index, protocol.shortLabel()) },
            selected = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
        )
    }
}

/** 手机与云之间的连线：连接中数据点沿线流动，成功或失败时右端出现徽标；关闭动画时数据点静止。 */
@Composable
private fun ConnectionWire(status: OnboardingConnectStatus) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val flowing = status == OnboardingConnectStatus.Connecting
    val ok = status == OnboardingConnectStatus.Connected
    val failed = status is OnboardingConnectStatus.Problem
    val primary = MaterialTheme.colorScheme.primary
    val lineColor by animateColorAsState(
        when {
            ok -> MaterialTheme.infinityDomainColors.success
            failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        tween(InfinityMotion.DurationMedium),
        label = "wire_color",
    )
    val phase = if (flowing && animate) {
        rememberInfiniteTransition(label = "wire_flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
            label = "wire_phase",
        ).value
    } else {
        0.5f
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WireEnd { Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        Canvas(Modifier.weight(1f).height(8.dp).padding(horizontal = InfinitySpacing.Small)) {
            val y = size.height / 2f
            drawLine(
                lineColor,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (ok) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
            if (flowing) {
                for (i in 0 until 3) {
                    val t = (phase + i / 3f) % 1f
                    drawCircle(primary.copy(alpha = 0.35f + 0.65f * (1f - kotlin.math.abs(t - 0.5f) * 2f)), 3.dp.toPx(), Offset(size.width * t, y))
                }
            }
        }
        Box {
            WireEnd { Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            androidx.compose.animation.AnimatedVisibility(
                visible = ok || failed,
                enter = scaleIn(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)),
                exit = scaleOut(tween(InfinityMotion.DurationShort)),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.infinityDomainColors.success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (failed) Icons.Rounded.Close else Icons.Rounded.Check,
                        null,
                        tint = if (failed) MaterialTheme.colorScheme.onError else MaterialTheme.infinityDomainColors.onSuccess,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WireEnd(content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(onboardingCardColor, CircleShape)
            .border(1.dp, onboardingLineColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ConnectStatusText(status: OnboardingConnectStatus) {
    val text = connectStatusText(status)
    val error = status is OnboardingConnectStatus.Problem
    AnimatedContent(
        targetState = text to error,
        transitionSpec = { InfinityMotion.fadeThrough() },
        label = "connect_status",
    ) { (shown, isError) ->
        if (shown != null) {
            Text(
                shown,
                style = OnboardingType.caption(),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTags.PROVIDER_STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun connectStatusText(status: OnboardingConnectStatus): String? = when (status) {
    OnboardingConnectStatus.Idle -> null
    OnboardingConnectStatus.Connecting -> stringResource(R.string.onboarding_provider_connecting)
    OnboardingConnectStatus.Connected -> stringResource(R.string.onboarding_provider_connected)
    is OnboardingConnectStatus.Problem -> when (val outcome = status.outcome) {
        is OnboardingConnectOutcome.Failed -> {
            val reason = outcome.reason
            if (reason == null) {
                stringResource(R.string.onboarding_provider_save_failed)
            } else {
                val prefix = stringResource(R.string.common_cd_failed)
                val detail = stringResource(probeFailureText(reason))
                outcome.statusCode?.let { stringResource(R.string.onboarding_provider_failed_http, prefix, it, detail) }
                    ?: stringResource(R.string.onboarding_provider_failed, prefix, detail)
            }
        }
        OnboardingConnectOutcome.Invalid -> stringResource(R.string.onboarding_provider_secret_missing)
        OnboardingConnectOutcome.LocalModelMissing -> stringResource(R.string.onboarding_local_model_unavailable)
        is OnboardingConnectOutcome.Connected -> null
    }
}

private fun probeFailureText(reason: ProviderConnectionProbeFailure): Int = when (reason) {
    ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED -> R.string.provider_form_test_failed_auth
    ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE -> R.string.provider_form_test_failed_network
    ProviderConnectionProbeFailure.ENDPOINT_INVALID -> R.string.provider_form_test_failed_endpoint
    ProviderConnectionProbeFailure.CREDENTIALS_INVALID -> R.string.provider_form_test_failed_credentials
    ProviderConnectionProbeFailure.CREDENTIALS_MISSING -> R.string.provider_form_test_failed_missing
    ProviderConnectionProbeFailure.RESPONSE_INVALID -> R.string.provider_form_test_failed_response
}

/** 可搜索的提供商预设底部面板：按一键授权 / 中国提供商 / 海外提供商 / 自定义接口分组，紧跟免费/试用小徽章。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ServiceSheet(
    groups: List<Pair<Int, List<ProviderPreset>>>,
    selected: ProviderPreset,
    onSelect: (ProviderPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val labels = groups.flatMap { it.second }.associateWith { it.displayName() to it.hostLabel() }
    val filtered = groups.map { (title, items) ->
        title to items.filter { item ->
            val (label, host) = labels.getValue(item)
            val q = query.trim()
            q.isBlank() || label.contains(q, ignoreCase = true) ||
                host.contains(q, ignoreCase = true) || item.name.contains(q, ignoreCase = true)
        }
    }.filter { it.second.isNotEmpty() }

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Content, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_provider_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            InfinitySearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.provider_preset_search_placeholder),
                borderless = true,
                modifier = Modifier.testTag(OnboardingTags.SERVICE_SEARCH),
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .padding(horizontal = InfinitySpacing.XLarge)
                .testTag(OnboardingTags.SERVICE_LIST),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.onboarding_provider_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = InfinitySpacing.Large),
                    )
                }
            }
            filtered.forEach { (title, items) ->
                item(key = "group_$title") {
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                item(key = "card_$title") {
                    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
                        items.forEachIndexed { idx, item ->
                            if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                            val (label, host) = labels.getValue(item)
                            val isSelected = item == selected
                            val subtitle = if (item.variants.size > 1) {
                                val count = stringResource(R.string.provider_preset_variants_count, item.variants.size)
                                "$host · $count" // wrap-check: allow 预设主机与变体数
                            } else {
                                host
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = isSelected, role = Role.RadioButton) {
                                        onSelect(item)
                                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                                    }
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag(OnboardingTags.SERVICE_PREFIX + "sheet_" + item.name),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ProviderPresetIcon(item, selected = isSelected, tileSize = 36.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        itemVerticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = scheme.onSurface,
                                            maxLines = 1,
                                        )
                                        item.badges.forEach { badge ->
                                            ProviderPresetBadgeChip(badge)
                                        }
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(InfinitySpacing.XLarge)) }
        }
    }
}
