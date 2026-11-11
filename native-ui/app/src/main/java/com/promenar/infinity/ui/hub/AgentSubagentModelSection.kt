package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.ModelPicker
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 子代理要调用工具；只有模型目录明确标为不支持工具调用的模型被排除，未知按支持处理。 */
internal fun List<ModelSelectionUiModel>.subagentCapable(): List<ModelSelectionUiModel> =
    filter { it.capabilityStates[ModelCapability.TOOL_CALLING] != SupportState.UNSUPPORTED }

private enum class SubagentModelPick { Default, Selectable }

/**
 * 智能体设置“子代理”一节：默认模型（跟随会话或指定一个）与“允许智能体按任务换模型”。
 * [available] 是已启用且可调用工具的模型，选择器只列这些；[displayName] 给已保存 ID 取显示名。
 * 已保存但不在 [available] 中的模型（被停用或不支持工具调用）标为不可用，运行时也不会悄悄换成别的模型。
 */
@Composable
internal fun AgentSubagentModelSection(
    policy: AgentSubagentModelPolicy,
    available: List<ModelSelectionUiModel>,
    displayName: (String) -> String,
    onPolicyChange: ((AgentSubagentModelPolicy) -> AgentSubagentModelPolicy) -> Unit,
) {
    var picking by remember { mutableStateOf<SubagentModelPick?>(null) }
    val availableIds = remember(available) { available.mapTo(mutableSetOf()) { it.selectionId } }
    InfinitySettingsSection(
        title = stringResource(R.string.subagent_model_section),
        footer = stringResource(R.string.subagent_model_section_footer),
    ) {
        val fixed = policy.defaultModelId
        InfinitySettingsItem(
            title = stringResource(R.string.subagent_model_follow),
            subtitle = stringResource(R.string.subagent_model_follow_desc),
            role = Role.RadioButton,
            selected = fixed == null,
            leadingContent = { RadioButton(selected = fixed == null, onClick = null) },
            onClick = { onPolicyChange { it.copy(defaultModelId = null) } },
            modifier = Modifier.testTag("agent_subagent_model_follow"),
        )
        // 选“指定模型”先打开选择器，选定后才切换；取消选择保持原状。
        InfinitySettingsItem(
            title = stringResource(R.string.subagent_model_fixed),
            subtitle = fixed?.let { modelLabel(it, it in availableIds, displayName) } ?: stringResource(R.string.subagent_model_fixed_desc),
            subtitleMaxLines = 2,
            role = Role.RadioButton,
            selected = fixed != null,
            leadingContent = { RadioButton(selected = fixed != null, onClick = null) },
            showChevron = fixed != null,
            onClick = { picking = SubagentModelPick.Default },
            modifier = Modifier.testTag("agent_subagent_model_fixed"),
        )
        SettingsToggle(
            title = stringResource(R.string.subagent_model_allow_per_task),
            description = stringResource(R.string.subagent_model_allow_per_task_desc),
            descriptionMaxLines = 3,
            icon = Icons.Rounded.SwapHoriz,
            checked = policy.allowPerTaskModel,
            onCheckedChange = { allow -> onPolicyChange { it.copy(allowPerTaskModel = allow) } },
            modifier = Modifier.testTag("agent_subagent_model_allow"),
        )
        AnimatedVisibility(visible = policy.allowPerTaskModel) {
            SelectableModels(
                ids = policy.selectableModelIds,
                availableIds = availableIds,
                displayName = displayName,
                onRemove = { id -> onPolicyChange { it.copy(selectableModelIds = it.selectableModelIds - id) } },
                onAdd = { picking = SubagentModelPick.Selectable },
            )
        }
    }
    picking?.let { target ->
        ModelPicker(
            show = true,
            title = stringResource(if (target == SubagentModelPick.Default) R.string.subagent_model_picker_title else R.string.subagent_model_add_title),
            filterTag = "chat",
            currentModelId = if (target == SubagentModelPick.Default) policy.defaultModelId.orEmpty() else "",
            models = if (target == SubagentModelPick.Default) available
            else available.filter { it.selectionId !in policy.selectableModelIds },
            onDismiss = { picking = null },
            onSelect = { id, _ ->
                picking = null
                onPolicyChange {
                    if (target == SubagentModelPick.Default) it.copy(defaultModelId = id)
                    else it.copy(selectableModelIds = (it.selectableModelIds + id).distinct())
                }
            },
        )
    }
}

@Composable
private fun modelLabel(id: String, available: Boolean, displayName: (String) -> String): String =
    if (available) displayName(id) else stringResource(R.string.subagent_model_missing, displayName(id))

/** 已勾选的可选模型：每个一行，行尾移除；末尾“添加”打开只含可调用工具模型的选择器。 */
@Composable
private fun SelectableModels(
    ids: List<String>,
    availableIds: Set<String>,
    displayName: (String) -> String,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    // 与开关标题左缘对齐：行内边距 + 图标位宽度。
    Column(
        Modifier.fillMaxWidth()
            .padding(start = InfinitySpacing.RowHorizontal + InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap, end = InfinitySpacing.RowHorizontal, bottom = 8.dp)
            .testTag("agent_subagent_model_selectable"),
    ) {
        if (ids.isEmpty()) {
            Text(
                stringResource(R.string.subagent_model_selectable_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 可选模型数量不限：每个模型占一行，行尾移除；末尾一行添加。
        ids.forEach { id ->
            val available = id in availableIds
            val removeLabel = stringResource(R.string.subagent_model_remove_cd, displayName(id))
            Row(
                Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("agent_subagent_model_chip_$id"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelLabel(id, available, displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(id) }, modifier = Modifier.testTag("agent_subagent_model_remove_$id")) {
                    Icon(Icons.Rounded.Close, contentDescription = removeLabel, modifier = Modifier.size(18.dp))
                }
            }
        }
        val addLabel = stringResource(R.string.subagent_model_add_cd)
        TextButton(
            onClick = onAdd,
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.semantics { contentDescription = addLabel }.testTag("agent_subagent_model_add"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.subagent_model_add))
        }
    }
}
