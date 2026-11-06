package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.params.ParameterScheme
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.model.params.SamplingValues
import com.promenar.infinity.ui.chat.ChatReasoningControl
import com.promenar.infinity.ui.chat.ReasoningMenuContent
import com.promenar.infinity.ui.chat.reasoningEffortLabel
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.params.ParameterSchemeSection
import com.promenar.infinity.ui.common.params.SamplingEditorActions
import com.promenar.infinity.ui.common.params.SamplingEditorState
import com.promenar.infinity.ui.common.params.SamplingParamLayout
import com.promenar.infinity.ui.common.params.SamplingParamRows

/** 助手默认参数的编辑器数据：助手是最上一层，未设置的项按模型默认。 */
internal fun agentSamplingEditorState(
    sampling: SamplingValues,
    baseSchemeId: String?,
    layout: SamplingParamLayout,
    schemes: List<ParameterScheme>,
) = SamplingEditorState(
    own = sampling,
    baseSchemeId = baseSchemeId,
    layout = layout,
    schemes = schemes,
)

/** 助手编辑页“默认参数”行的摘要：全部未设置为“模型默认”，否则列出推理档位与已设置的参数数。 */
@Composable
internal fun agentDefaultParamsSummary(editor: SamplingEditorState, reasoning: ChatReasoningControl?): String {
    val effort = reasoning?.effective
    val count = editor.visible.count { editor.own.valueOf(it) != null }
    if (effort == null && count == 0) return stringResource(R.string.params_scheme_model_default)
    val separator = stringResource(R.string.params_list_separator)
    return listOfNotNull(
        effort?.let { "${stringResource(R.string.agent_edit_reasoning_effort)} ${reasoningEffortLabel(it)}" },
        count.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.agent_edit_params_count, it, it) },
    ).joinToString(separator)
}

/** 助手默认参数弹层：推理强度、本模型的参数方案与逐项采样参数。 */
@Composable
internal fun AgentDefaultParamsContent(
    reasoning: ChatReasoningControl?,
    onReasoningChange: (ReasoningEffort?) -> Unit,
    editor: SamplingEditorState,
    actions: SamplingEditorActions,
    modelName: String?,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(modifier.fillMaxWidth().verticalScroll(scrollState)) {
        Text(
            stringResource(R.string.agent_edit_default_params),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (reasoning != null) {
            var menuOpen by remember { mutableStateOf(false) }
            InfinitySettingsSection(title = stringResource(R.string.params_section_reasoning)) {
                Box {
                    InfinityValueRow(
                        title = stringResource(R.string.agent_edit_reasoning_effort),
                        value = reasoning.effective?.let { reasoningEffortLabel(it) }
                            ?: stringResource(R.string.params_scheme_model_default),
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("agent_params_reasoning"),
                    )
                    InfinityDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 208.dp) {
                        ReasoningMenuContent(reasoning) { effort ->
                            menuOpen = false
                            onReasoningChange(effort)
                        }
                    }
                }
            }
        } else {
            // 模型不提供推理强度调节时保留该分组，灰显并说明原因。
            InfinitySettingsSection(title = stringResource(R.string.params_section_reasoning)) {
                InfinityValueRow(
                    title = stringResource(R.string.agent_edit_reasoning_effort),
                    value = stringResource(R.string.params_scheme_model_default),
                    subtitle = UnavailableReasons.reasoningEffort(modelSelected = modelName != null, reasoningAdjustable = false)
                        ?.let { stringResource(it) },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("agent_params_reasoning"),
                )
            }
        }
        ParameterSchemeSection(
            editor, actions, modelName, scrollState = scrollState,
            footer = stringResource(R.string.agent_edit_scheme_shared_footer),
        )
        InfinitySettingsSection(title = stringResource(R.string.params_section_sampling)) {
            SamplingParamRows(editor, actions, ownLabel = null)
        }
    }
}
