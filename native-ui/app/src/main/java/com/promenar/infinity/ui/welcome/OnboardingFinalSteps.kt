package com.promenar.infinity.ui.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.ui.common.CompactOutlinedTextField
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors

private const val AgentNameMaxLength = 40

@Composable
internal fun OnboardingModelStep(state: OnboardingUiState, actions: OnboardingActions) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.models.firstOrNull { it.id == selectedId }
    val checking = state.checkingModelId != null
    OnboardingLazyStepPage(
        listTag = OnboardingTags.MODEL_LIST,
        footer = {
            state.errorMessage?.let { OnboardingError(it) }
            if (state.models.isNotEmpty()) {
                OnboardingPrimaryButton(
                    onClick = { selected?.let { actions.onUseModel(it.id) } },
                    enabled = selected != null && !checking && !state.busy,
                    modifier = Modifier.testTag(OnboardingTags.MODEL_USE),
                ) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Box(Modifier.size(InfinitySpacing.Small))
                    }
                    Text(stringResource(R.string.onboarding_model_use), style = OnboardingType.button())
                }
            }
            val refreshButton: @Composable () -> Unit = {
                InfinityPillButton(
                    text = stringResource(R.string.onboarding_model_refresh),
                    onClick = actions.onRefreshModels,
                    enabled = !state.modelsLoading && !checking,
                    icon = Icons.Rounded.Refresh,
                    modifier = Modifier.testTag(OnboardingTags.MODEL_REFRESH),
                )
            }
            if (state.models.isEmpty() && !state.modelsLoading) {
                // 没有可用模型时“返回连接提供商 / 刷新列表”是两条同级出路，并排放置。
                InfinityPillRow(modifier = Modifier.fillMaxWidth(), reverseWhenStacked = false) {
                    InfinityPillButton(
                        text = stringResource(R.string.onboarding_model_back_provider),
                        onClick = actions.onBackToProvider,
                        modifier = Modifier.testTag(OnboardingTags.MODEL_BACK_PROVIDER),
                    )
                    refreshButton()
                }
            } else {
                Box(Modifier.align(Alignment.CenterHorizontally)) { refreshButton() }
            }
        },
    ) {
        item(key = "heading") {
            Column(Modifier.padding(bottom = 16.dp)) {
                OnboardingHeading(stringResource(R.string.onboarding_model_title), stringResource(R.string.onboarding_model_desc))
            }
        }
        when {
            state.models.isEmpty() && state.modelsLoading -> item(key = "loading") {
                RiseIn(2) {
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        Text(
                            stringResource(R.string.onboarding_model_loading),
                            style = OnboardingType.body(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            state.models.isEmpty() -> item(key = "empty") {
                RiseIn(2) {
                    Text(
                        stringResource(R.string.onboarding_model_empty),
                        style = OnboardingType.body(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(OnboardingTags.MODEL_EMPTY),
                    )
                }
            }
            else -> {
                itemsIndexed(state.models, key = { _, model -> model.id }) { index, model ->
                    RiseIn(2 + index.coerceAtMost(8)) {
                        ModelCard(
                            model = model,
                            selected = model.id == selectedId,
                            tag = modelTag(model, state),
                            enabled = !checking && !state.busy,
                            onClick = { selectedId = model.id },
                        )
                    }
                }
                if (state.hiddenModelCount > 0) {
                    item(key = "hidden") {
                        Text(
                            stringResource(R.string.onboarding_model_hidden, state.hiddenModelCount),
                            style = OnboardingType.caption(),
                            color = onboardingFaintText,
                        )
                    }
                }
            }
        }
    }
}

private enum class ModelTag { CHAT, REASONING, UNKNOWN, CHECKING, FAILED }

private fun modelTag(model: ModelInfo, state: OnboardingUiState): ModelTag = when {
    model.id == state.checkingModelId -> ModelTag.CHECKING
    model.id in state.failedModelIds -> ModelTag.FAILED
    model.type.equals("reasoning", ignoreCase = true) -> ModelTag.REASONING
    model.type.equals("unknown", ignoreCase = true) -> ModelTag.UNKNOWN
    else -> ModelTag.CHAT
}

@Composable
private fun ModelCard(model: ModelInfo, selected: Boolean, tag: ModelTag, enabled: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else onboardingLineColor,
        tween(InfinityMotion.DurationShort),
        label = "model_border",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else onboardingCardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(OnboardingTags.MODEL_OPTION),
    ) {
        Row(
            Modifier.heightIn(min = OnboardingDimens.RowMinHeight).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(model.name, style = OnboardingType.rowTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (model.remoteModelId != model.name) {
                    Text(
                        model.remoteModelId,
                        style = OnboardingType.rowSubtitle().copy(fontFamily = InfinityMonospace, fontSize = 11.sp),
                        color = onboardingFaintText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ModelTagChip(tag)
        }
    }
}

@Composable
private fun ModelTagChip(tag: ModelTag) {
    val (text, container, content) = when (tag) {
        ModelTag.CHAT -> Triple(R.string.onboarding_model_tag_chat, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModelTag.REASONING -> Triple(R.string.onboarding_model_tag_reasoning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ModelTag.UNKNOWN -> Triple(R.string.onboarding_model_tag_unknown, MaterialTheme.infinityDomainColors.warningContainer, MaterialTheme.infinityDomainColors.onWarningContainer)
        ModelTag.CHECKING -> Triple(R.string.onboarding_model_tag_checking, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ModelTag.FAILED -> Triple(R.string.onboarding_model_tag_failed, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            stringResource(text),
            style = OnboardingType.caption().copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnboardingAgentStep(state: OnboardingUiState, actions: OnboardingActions) {
    var name by rememberSaveable { mutableStateOf("") }
    val fallback = stringResource(R.string.onboarding_agent_default_name)
    val shown = name.trim().ifEmpty { fallback }
    val suggestions = listOf(
        stringResource(R.string.onboarding_agent_suggestion_research),
        stringResource(R.string.onboarding_agent_suggestion_code),
        stringResource(R.string.onboarding_agent_suggestion_writing),
    )
    OnboardingStepPage(
        footer = {
            state.errorMessage?.let { OnboardingError(it) }
            OnboardingPrimaryButton(
                onClick = { actions.onCreateAgent(shown) },
                enabled = !state.busy,
                modifier = Modifier.testTag(OnboardingTags.AGENT_CREATE),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Box(Modifier.size(InfinitySpacing.Small))
                }
                Text(
                    stringResource(if (state.busy) R.string.onboarding_agent_creating else R.string.onboarding_agent_action),
                    style = OnboardingType.button(),
                )
            }
        },
    ) {
        RiseIn(0, Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AgentOrb(shown) }
        }
        OnboardingHeading(stringResource(R.string.onboarding_agent_title), stringResource(R.string.onboarding_agent_desc), centered = true, index = 1)
        RiseIn(3) {
            CompactOutlinedTextField(
                value = name,
                onValueChange = { name = it.take(AgentNameMaxLength) },
                label = stringResource(R.string.onboarding_agent_name),
                placeholder = fallback,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.AGENT_NAME),
            )
        }
        RiseIn(4) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                suggestions.forEach { suggestion ->
                    InfinityChip(label = suggestion, onClick = { name = suggestion }, selected = name.trim() == suggestion, enabled = !state.busy)
                }
            }
        }
        RiseIn(5) {
            OnboardingCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow(stringResource(R.string.onboarding_agent_summary_model), state.modelName.orEmpty())
                    SummaryRow(stringResource(R.string.onboarding_agent_summary_env), agentEnvironmentText(state.linux))
                }
            }
        }
    }
}

@Composable
private fun agentEnvironmentText(linux: OnboardingLinuxStatus?): String = when (linux?.runtime) {
    is LinuxRuntimeState.Ready -> stringResource(R.string.onboarding_agent_env_linux)
    is LinuxRuntimeState.Installing -> stringResource(R.string.onboarding_agent_env_installing)
    is LinuxRuntimeState.InstallInterrupted ->
        if (linux.hidden) stringResource(R.string.onboarding_agent_env_builtin) else stringResource(R.string.onboarding_agent_env_stopped)
    else -> stringResource(R.string.onboarding_agent_env_builtin)
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        Text(label, style = OnboardingType.body(), color = onboardingFaintText)
        Text(
            value,
            style = OnboardingType.body(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 呼吸的头像球：显示名称首字；关闭动画时静止。 */
@Composable
private fun AgentOrb(name: String) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val breath = if (animate) {
        rememberInfiniteTransition(label = "agent_orb").animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = InfinityMotion.Standard), RepeatMode.Reverse),
            label = "agent_orb_scale",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(64.dp)
            .scale(breath)
            .background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
                CircleShape,
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
internal fun OnboardingReadyStep(state: OnboardingUiState, actions: OnboardingActions) {
    OnboardingStepPage(
        footer = {
            state.errorMessage?.let { OnboardingError(it) }
            RiseIn(7) {
                OnboardingPrimaryButton(
                    onClick = actions.onStartChat,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(OnboardingTags.START_CHAT),
                ) { Text(stringResource(R.string.onboarding_first_chat_action), style = OnboardingType.button()) }
            }
        },
    ) {
        OnboardingWordmark(Modifier.fillMaxWidth().height(170.dp))
        OnboardingHeading(stringResource(R.string.onboarding_ready_title), stringResource(R.string.onboarding_ready_desc), centered = true)
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RiseIn(2) { CheckRow(stringResource(R.string.onboarding_ready_provider, state.providerName.orEmpty()), CheckState.DONE) }
            RiseIn(3) { CheckRow(stringResource(R.string.onboarding_ready_model, state.modelName.orEmpty()), CheckState.DONE) }
            RiseIn(4) { CheckRow(stringResource(R.string.onboarding_ready_agent, state.agentName.orEmpty()), CheckState.DONE) }
            state.linux?.let { linux -> RiseIn(5) { LinuxReadyRow(linux, actions.onRetryLinux) } }
        }
    }
}

private enum class CheckState { DONE, RUNNING, WARNING, OFF }

@Composable
private fun LinuxReadyRow(linux: OnboardingLinuxStatus, onRetry: () -> Unit) {
    when (val runtime = linux.runtime) {
        is LinuxRuntimeState.Ready -> CheckRow(stringResource(R.string.onboarding_ready_linux_ok), CheckState.DONE)
        is LinuxRuntimeState.Installing -> {
            val fraction = runtime.progress.fraction
            CheckRow(
                fraction?.let { stringResource(R.string.onboarding_ready_linux_installing, (it * 100).toInt()) }
                    ?: stringResource(R.string.onboarding_ready_linux_preparing),
                CheckState.RUNNING,
                fraction = fraction,
            )
        }
        is LinuxRuntimeState.InstallInterrupted -> if (linux.hidden) {
            CheckRow(stringResource(R.string.onboarding_ready_linux_skipped), CheckState.OFF)
        } else {
            CheckRow(stringResource(R.string.onboarding_ready_linux_stopped), CheckState.WARNING) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.onboarding_ready_linux_retry),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> CheckRow(stringResource(R.string.onboarding_ready_linux_skipped), CheckState.OFF)
    }
}

@Composable
private fun CheckRow(
    text: String,
    state: CheckState,
    fraction: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                CheckState.DONE -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.infinityDomainColors.success, modifier = Modifier.size(18.dp))
                CheckState.RUNNING -> OnboardingProgressRing(fraction, Modifier.size(16.dp))
                CheckState.WARNING -> Icon(Icons.Rounded.PriorityHigh, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                CheckState.OFF -> Icon(Icons.Rounded.Remove, null, tint = onboardingFaintText, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, style = OnboardingType.rowTitle().copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun OnboardingError(message: String) {
    AnimatedVisibility(visible = true, enter = InfinityMotion.expandIn) {
        Text(
            message,
            style = OnboardingType.caption(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}
