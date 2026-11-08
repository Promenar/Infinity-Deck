package com.promenar.infinity.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.params.ModelParameterProfile
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.model.params.ReasoningSupport
import com.promenar.infinity.data.model.params.resolveModelParameterProfile
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.common.InfinityMenuItem

/** 输入区推理强度控件的数据：模型支持的档位，以及会话（或助手）保存的档位。 */
@Immutable
data class ChatReasoningControl(
    val support: ReasoningSupport,
    /** null 为模型默认。 */
    val stored: ReasoningEffort?,
) {
    /** 实际发送的档位；null 表示按模型默认。 */
    val effective: ReasoningEffort? get() = support.effective(stored)

    /** 胶囊上显示的档位：已选择时为实际档位，模型默认时为文档写明的默认档位。 */
    val shown: ReasoningEffort? get() = effective ?: support.defaultEffort?.let(::displayedDefault)

    val isCustomized: Boolean get() = effective != null

    val isThinking: Boolean get() = support.thinks(effective)

    /** 只能开关的模型：点一下在模型默认与相反状态之间切换。 */
    fun toggled(): ReasoningEffort? = when {
        stored != null -> null
        support.thinks(null) -> ReasoningEffort.NONE
        else -> ReasoningEffort.HIGH
    }

    private fun displayedDefault(effort: ReasoningEffort): ReasoningEffort =
        if (support.isToggleOnly && effort != ReasoningEffort.NONE) ReasoningEffort.HIGH else effort
}

/** 会话未设置时沿用助手档位；会话的“模型默认”标记解析为 null。 */
internal fun storedReasoningEffort(sessionValue: String?, agentValue: String?): ReasoningEffort? =
    ReasoningEffort.fromWireName(sessionValue ?: agentValue)

/**
 * 输入区选择写入会话的取值。选“模型默认”时，助手没有档位就清空会话设置；
 * 助手有档位则写入显式标记，避免回退到助手档位。
 */
internal fun sessionReasoningEffortFor(selected: ReasoningEffort?, agentValue: String?): String? = when {
    selected != null -> selected.wireName
    ReasoningEffort.fromWireName(agentValue) == null -> null
    else -> ReasoningEffort.MODEL_DEFAULT_WIRE_NAME
}

/** 按会话模型所属服务商的协议解析档位；模型或服务商未知、模型不支持调节时返回 null，不显示胶囊。 */
internal fun chatReasoningControl(
    modelId: String?,
    providers: List<ProviderListItem>,
    providerModels: List<ModelInfo>,
    sessionValue: String?,
    agentValue: String?,
): ChatReasoningControl? {
    val support = sessionModelParameterProfile(modelId, providers, providerModels)?.reasoning ?: return null
    return ChatReasoningControl(support, storedReasoningEffort(sessionValue, agentValue))
}

/** 稳定模型 id（provider::remote）所在服务商协议上的参数能力；模型或服务商未知时返回 null。 */
internal fun sessionModelParameterProfile(
    modelId: String?,
    providers: List<ProviderListItem>,
    providerModels: List<ModelInfo>,
): ModelParameterProfile? {
    val stableId = modelId?.takeIf(String::isNotBlank) ?: return null
    val model = providerModels.firstOrNull { it.id == stableId }
    val providerId = stableId.substringBefore("::", "").ifEmpty { model?.providerId.orEmpty() }
    val provider = providers.firstOrNull { it.id == providerId } ?: return null
    val remoteId = model?.remoteModelId ?: stableId.substringAfter("::")
    return resolveModelParameterProfile(provider.protocolType, remoteId, provider.baseUrl)
}

@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.NONE -> R.string.reasoning_effort_none
        ReasoningEffort.MINIMAL -> R.string.reasoning_effort_minimal
        ReasoningEffort.LOW -> R.string.reasoning_effort_low
        ReasoningEffort.MEDIUM -> R.string.reasoning_effort_medium
        ReasoningEffort.HIGH -> R.string.reasoning_effort_high
        ReasoningEffort.XHIGH -> R.string.reasoning_effort_xhigh
        ReasoningEffort.MAX -> R.string.reasoning_effort_max
    },
)

/** 工具行胶囊（模型名、推理档位）的可见高度；点击区仍为 40dp 高。 */
internal val ComposerChipHeight = 32.dp

/**
 * 模型选择器右侧的推理强度胶囊，与模型胶囊同为无描边的填充胶囊。多档模型点开下拉选择；只能开关的模型点一下切换。
 * 模型默认时为灰字，选择后换成主色淡底与主色文字。
 */
@Composable
internal fun ChatReasoningChip(
    control: ChatReasoningControl,
    onChange: (ReasoningEffort?) -> Unit,
    /** 模型默认时的胶囊填充色，与模型胶囊相同。 */
    containerColor: Color,
    modifier: Modifier = Modifier,
    /** 窄屏只显示灯泡，档位由无障碍状态与菜单给出，把宽度留给模型名。 */
    compact: Boolean = false,
) {
    val haptics = LocalInfinityHaptics.current
    var expanded by remember { mutableStateOf(false) }
    val support = control.support
    val label = when {
        support.isToggleOnly -> stringResource(if (control.isThinking) R.string.reasoning_effort_on else R.string.reasoning_effort_none)
        else -> control.shown?.let { reasoningEffortLabel(it) } ?: stringResource(R.string.reasoning_effort_default_short)
    }
    val accent = control.isCustomized
    val contentColor = when {
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fill = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f).compositeOver(containerColor) else containerColor
    val chipDescription = stringResource(R.string.reasoning_effort_title)
    val chipState = if (accent) label else stringResource(R.string.reasoning_effort_model_default_state, label)
    // 40dp 外层只撑触控区，按压反馈画在 32dp 胶囊里。
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = chipDescription) {
                    if (support.isToggleOnly) {
                        haptics.select(alreadySelected = false)
                        onChange(control.toggled())
                    } else {
                        expanded = true
                    }
                }
                .semantics {
                    contentDescription = chipDescription
                    stateDescription = chipState
                }
                .testTag(UiTags.CHAT_REASONING_CHIP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .height(ComposerChipHeight)
                    .composerChip(interaction, fill, outlined = false)
                    // 紧凑时左右各 8dp，与 16dp 灯泡正好拼成 32dp 圆。
                    .padding(horizontal = if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (control.isThinking) Icons.Rounded.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
                if (!compact) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = 208.dp) {
            ReasoningMenuContent(control) { effort ->
                expanded = false
                haptics.select(alreadySelected = effort == control.stored)
                onChange(effort)
            }
        }
    }
}

@Composable
internal fun ReasoningMenuContent(control: ChatReasoningControl, onSelect: (ReasoningEffort?) -> Unit) {
    val support = control.support
    val selected = control.effective
    Text(
        text = stringResource(R.string.reasoning_effort_title),
        style = InfinityTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    val defaultLabel = support.defaultEffort?.let { reasoningEffortLabel(it) }
    ReasoningMenuItem(
        title = stringResource(R.string.reasoning_effort_model_default),
        detail = defaultLabel,
        checked = selected == null,
        tag = "reasoning_option_default",
        onClick = { onSelect(null) },
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    support.levels.forEachIndexed { index, effort ->
        ReasoningMenuItem(
            title = reasoningEffortLabel(effort),
            detail = when (index) {
                0 -> stringResource(R.string.reasoning_effort_fastest)
                support.levels.lastIndex -> stringResource(R.string.reasoning_effort_deepest)
                else -> null
            },
            checked = selected == effort,
            tag = "reasoning_option_${effort.wireName}",
            onClick = { onSelect(effort) },
        )
    }
    if (support.canDisable) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ReasoningMenuItem(
            title = reasoningEffortLabel(ReasoningEffort.NONE),
            detail = null,
            checked = selected == ReasoningEffort.NONE,
            tag = "reasoning_option_none",
            onClick = { onSelect(ReasoningEffort.NONE) },
        )
    }
}

@Composable
private fun ReasoningMenuItem(title: String, detail: String?, checked: Boolean, tag: String, onClick: () -> Unit) {
    val selectedState = stringResource(R.string.common_state_selected)
    InfinityMenuItem(
        text = {
            Column {
                Text(
                    text = title,
                    style = InfinityTypography.labelLarge,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingIcon = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick,
        modifier = Modifier
            .semantics { if (checked) stateDescription = selectedState }
            .testTag(tag),
    )
}
