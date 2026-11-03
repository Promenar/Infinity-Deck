package com.promenar.infinity.ui.welcome

import com.promenar.infinity.ui.settings.label
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.CompactOutlinedTextField
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.common.label as protocolLabel
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.settings.CUSTOM_PROTOCOL_OPTIONS
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.ProviderPreset
import com.promenar.infinity.ui.settings.isProviderEndpointAllowed
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

private const val PRESET_CUSTOM = "Custom"
private const val PRESET_LOCAL = "Local"
/** 网格直接列出的常用服务；其余在“更多服务”里搜索。 */
private val GridPresetNames = listOf("OpenAI", "Anthropic", "Gemini", "DeepSeek", "通义千问")
private val GlobalPresetNames = listOf("Vertex AI", "Mistral")
private val ChinaPresetNames = listOf("Kimi", "智谱", "豆包", "百川")

private fun preset(name: String): ProviderPreset = PROVIDER_PRESETS.first { it.name == name }

/** “更多服务”面板的分组；本地推理只在当前版本可用时出现。 */
internal fun onboardingServiceGroups(localAvailable: Boolean): List<Pair<Int, List<ProviderPreset>>> = listOf(
    R.string.onboarding_provider_group_popular to GridPresetNames.map(::preset),
    R.string.onboarding_provider_group_global to GlobalPresetNames.map(::preset),
    R.string.onboarding_provider_group_china to ChinaPresetNames.map(::preset),
    R.string.onboarding_provider_group_custom to listOfNotNull(
        preset(PRESET_CUSTOM),
        preset(PRESET_LOCAL).takeIf { localAvailable },
    ),
)

@Composable
private fun ProviderPreset.displayName(): String = when (name) {
    PRESET_CUSTOM -> stringResource(R.string.onboarding_provider_custom)
    PRESET_LOCAL -> stringResource(R.string.onboarding_provider_local)
    else -> label()
}

@Composable
private fun ProviderPreset.hostLabel(): String = when (name) {
    PRESET_CUSTOM -> stringResource(R.string.onboarding_provider_custom_host)
    PRESET_LOCAL -> stringResource(R.string.onboarding_provider_local_host)
    else -> Uri.parse(defaultBaseUrl).host ?: defaultBaseUrl
}

@Composable
internal fun OnboardingProviderStep(state: OnboardingUiState, actions: OnboardingActions, wide: Boolean) {
    var presetName by rememberSaveable { mutableStateOf(GridPresetNames.first()) }
    var baseUrl by rememberSaveable { mutableStateOf(preset(presetName).defaultBaseUrl) }
    // 密钥只留在内存，不写入可被持久化的界面保存状态。
    var secret by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var customProtocolIndex by rememberSaveable { mutableIntStateOf(0) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }

    val selected = preset(presetName)
    val local = selected.name == PRESET_LOCAL
    val protocol = if (selected.name == PRESET_CUSTOM) CUSTOM_PROTOCOL_OPTIONS[customProtocolIndex] else selected.protocolType
    val connecting = state.connect == OnboardingConnectStatus.Connecting || state.connect == OnboardingConnectStatus.Connected
    val endpointValid = local || isProviderEndpointAllowed(protocol, baseUrl.trim())
    val canConnect = !connecting && !state.busy && (local || (endpointValid && secret.isNotBlank()))
    val select: (ProviderPreset) -> Unit = { picked ->
        if (picked.name != presetName) {
            presetName = picked.name
            baseUrl = picked.defaultBaseUrl
        }
    }

    OnboardingStepPage(
        footer = {
            ConnectStatusText(state.connect)
            RiseIn(8) {
                OnboardingPrimaryButton(
                    onClick = {
                        if (local) actions.onConnectLocal()
                        else actions.onConnect(OnboardingProviderDraft(protocol, baseUrl, secret, name.ifBlank { null }))
                    },
                    enabled = canConnect,
                    modifier = Modifier.testTag(OnboardingTags.PROVIDER_CONNECT),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Box(Modifier.size(InfinitySpacing.Small))
                    }
                    Text(
                        stringResource(
                            when {
                                connecting -> R.string.onboarding_provider_connecting
                                local -> R.string.onboarding_provider_local_action
                                state.connect is OnboardingConnectStatus.Problem -> R.string.onboarding_provider_reconnect
                                else -> R.string.onboarding_provider_connect
                            },
                        ),
                        style = OnboardingType.button(),
                    )
                }
            }
            val problem = (state.connect as? OnboardingConnectStatus.Problem)?.outcome
            if (problem == OnboardingConnectOutcome.LocalModelMissing) {
                OutlinedButton(
                    onClick = actions.onOpenLocalModels,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(OnboardingDimens.ButtonHeight),
                ) {
                    Text(stringResource(R.string.local_models_title), style = OnboardingType.button())
                }
            }
        },
    ) {
        RiseIn(0) { ConnectionWire(state.connect) }
        OnboardingHeading(
            stringResource(R.string.onboarding_provider_title),
            stringResource(R.string.onboarding_provider_desc),
            index = 1,
        )
        RiseIn(3) {
            Text(
                stringResource(R.string.onboarding_provider_service),
                style = OnboardingType.eyebrow(),
                color = onboardingFaintText,
            )
        }
        RiseIn(4) {
            ServiceGrid(
                selected = selected,
                columns = if (wide) 3 else 2,
                moreCount = onboardingServiceGroups(state.localInferenceAvailable).drop(1).sumOf { it.second.size },
                enabled = !connecting,
                onSelect = select,
                onMore = { moreOpen = true },
            )
        }
        if (local) {
            RiseIn(5) {
                Text(
                    stringResource(R.string.onboarding_provider_local_desc),
                    style = OnboardingType.body(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (selected.name == PRESET_CUSTOM) {
                RiseIn(5) { CustomProtocolPicker(customProtocolIndex, enabled = !connecting) { customProtocolIndex = it } }
            }
            RiseIn(5) {
                CompactOutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = stringResource(R.string.provider_form_label_url),
                    placeholder = stringResource(R.string.provider_form_placeholder_url),
                    enabled = !connecting,
                    isError = baseUrl.isNotBlank() && !endpointValid,
                    supportingText = if (baseUrl.isNotBlank() && !endpointValid) stringResource(R.string.provider_form_http_url_required)
                    else com.promenar.infinity.ui.common.cleartextUrlHelp(baseUrl),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.PROVIDER_URL),
                )
            }
            RiseIn(6) {
                val vertex = protocol is ProtocolType.Google_VertexAI
                // 与提供商表单共用密钥输入：普通文本键盘、关闭候选与个性化学习，尚未保存过所以没有可回显的存量密钥。
                SecretField(
                    value = secret,
                    onValueChange = { secret = it },
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = { secret = "" },
                    modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.PROVIDER_SECRET),
                    placeholder = stringResource(if (vertex) R.string.provider_form_paste_json else R.string.provider_form_placeholder_api_key),
                    label = stringResource(if (vertex) R.string.provider_form_label_sa else R.string.provider_form_label_api_key),
                    compact = true,
                )
            }
            RiseIn(7) {
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
    if (moreOpen) {
        ServiceSheet(
            groups = onboardingServiceGroups(state.localInferenceAvailable),
            selected = selected,
            onSelect = select,
            onDismiss = { moreOpen = false },
        )
    }
}

@Composable
private fun ServiceGrid(
    selected: ProviderPreset,
    columns: Int,
    moreCount: Int,
    enabled: Boolean,
    onSelect: (ProviderPreset) -> Unit,
    onMore: () -> Unit,
) {
    val inGrid = selected.name in GridPresetNames
    // 前几格是常用服务，最后一格是“更多服务”或从中选中的服务。
    val cellCount = GridPresetNames.size + 1
    // 固定列数的网格：每行等宽，字号放大时也不会让某一格独占一行。
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (0 until cellCount).chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { index ->
                    val cell = Modifier.weight(1f).fillMaxHeight()
                    val item = GridPresetNames.getOrNull(index)?.let(::preset)
                    when {
                        item != null -> ServiceTile(
                            icon = { PresetIcon(item, item == selected) },
                            title = item.displayName(),
                            subtitle = item.hostLabel(),
                            selected = item == selected,
                            enabled = enabled,
                            onClick = { onSelect(item) },
                            modifier = cell.testTag(OnboardingTags.SERVICE_PREFIX + item.name),
                        )
                        inGrid -> ServiceTile(
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            title = stringResource(R.string.onboarding_provider_more),
                            subtitle = stringResource(R.string.onboarding_provider_more_count, moreCount),
                            selected = false,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onMore,
                            modifier = cell.testTag(OnboardingTags.SERVICE_MORE),
                        )
                        // 从“更多服务”选中的服务替换到最后一格，再点按可更换。
                        else -> ServiceTile(
                            icon = { PresetIcon(selected, true) },
                            title = selected.displayName(),
                            subtitle = stringResource(R.string.onboarding_provider_change),
                            selected = true,
                            enabled = enabled,
                            onClick = onMore,
                            modifier = cell.testTag(OnboardingTags.SERVICE_MORE),
                        )
                    }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PresetIcon(preset: ProviderPreset, selected: Boolean) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val iconModifier = Modifier.size(16.dp)
    preset.iconRes?.let { Icon(painterResource(it), contentDescription = null, tint = tint, modifier = iconModifier) }
        ?: Icon(Icons.Rounded.Psychology, contentDescription = null, tint = tint, modifier = iconModifier)
}

@Composable
private fun ServiceTile(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else onboardingLineColor,
        tween(InfinityMotion.DurationShort),
        label = "service_border",
    )
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else onboardingCardColor,
        tween(InfinityMotion.DurationShort),
        label = "service_container",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .heightIn(min = OnboardingDimens.TileMinHeight)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OnboardingIconTile { icon() }
            Column(Modifier.weight(1f)) {
                Text(title, style = OnboardingType.body().copy(lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = OnboardingType.caption().copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = onboardingFaintText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/** “更多服务”：搜索名称或地址，分组列出全部服务。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceSheet(
    groups: List<Pair<Int, List<ProviderPreset>>>,
    selected: ProviderPreset,
    onSelect: (ProviderPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val labels = groups.flatMap { it.second }.associateWith { it.displayName() to it.hostLabel() }
    val filtered = groups.map { (title, items) ->
        title to items.filter { item ->
            val (label, host) = labels.getValue(item)
            query.isBlank() || label.contains(query.trim(), ignoreCase = true) ||
                host.contains(query.trim(), ignoreCase = true) || item.name.contains(query.trim(), ignoreCase = true)
        }
    }.filter { it.second.isNotEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XLarge)) {
            Text(stringResource(R.string.onboarding_provider_sheet_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.onboarding_provider_search)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = InfinitySpacing.Medium)
                    .testTag(OnboardingTags.SERVICE_SEARCH),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag(OnboardingTags.SERVICE_LIST)) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.onboarding_provider_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
                    )
                }
            }
            filtered.forEach { (title, items) ->
                item(key = "group_$title") {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = InfinitySpacing.XLarge, top = InfinitySpacing.Medium, bottom = InfinitySpacing.XSmall),
                    )
                }
                items(items, key = { "${title}_${it.name}" }) { item ->
                    val (label, host) = labels.getValue(item)
                    val isSelected = item == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                onSelect(item)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Small)
                            .testTag(OnboardingTags.SERVICE_PREFIX + "sheet_" + item.name),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        Box(
                            Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) { PresetIcon(item, isSelected) }
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Box(Modifier.height(InfinitySpacing.XLarge)) }
        }
    }
}
