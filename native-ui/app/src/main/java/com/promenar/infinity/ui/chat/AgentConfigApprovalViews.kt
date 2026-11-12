package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.agentconfig.AgentConfigOp
import com.promenar.infinity.domain.agentconfig.SessionConfigKeys
import com.promenar.infinity.ui.chat.diff.AgentConfigImpact
import com.promenar.infinity.ui.chat.diff.AgentConfigPreviewBuilder
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FieldChange
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.forReading

/**
 * `agents_write` 审批卡正文：标题写明操作与对象，其下依次是智能体给出的理由、字段表（旧值 → 新值）、
 * 提示词差异（默认展开，可收起）与影响说明。
 */
@Composable
internal fun AgentConfigApprovalBody(preview: ApprovalDiffPreview.AgentConfig, toolName: String, pending: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("CHAT_APPROVAL_AGENT_CONFIG")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = agentConfigTitle(preview)
            Text(
                text = if (pending) stringResource(R.string.chat_approval_title_required, title) else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectionContainer {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (preview.reason.isNotBlank()) AgentConfigReason(preview.reason)
        if (preview.fields.isNotEmpty()) AgentConfigFieldTable(preview.fields, preview.op)
        preview.promptDiff?.let { diff ->
            var expanded by rememberSaveable(diff) { mutableStateOf(true) }
            ChatExpandLink(
                text = stringResource(
                    if (preview.sessionPrompt) R.string.agent_config_session_prompt_diff else R.string.agent_config_prompt_diff,
                ),
                expanded = expanded,
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("CHAT_APPROVAL_AGENT_PROMPT_TOGGLE"),
            )
            if (expanded) {
                FileDiffView(
                    model = diff,
                    maxViewportHeight = AgentConfigDiffViewportHeight,
                    modifier = Modifier.testTag("CHAT_APPROVAL_AGENT_PROMPT_DIFF"),
                )
            }
        }
        Text(
            text = agentConfigImpact(preview.impact),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("CHAT_APPROVAL_AGENT_IMPACT"),
        )
    }
}

/** 审批卡内提示词差异视口的最大高度，与文件差异一致。 */
private val AgentConfigDiffViewportHeight = 280.dp

@Composable
internal fun agentConfigTitle(preview: ApprovalDiffPreview.AgentConfig): String = when (preview.op) {
    AgentConfigOp.CREATE_AGENT -> stringResource(R.string.agent_config_title_create_agent, preview.targetName)
    AgentConfigOp.UPDATE_AGENT -> stringResource(R.string.agent_config_title_update_agent, preview.targetName)
    AgentConfigOp.CREATE_SESSION -> stringResource(R.string.agent_config_title_create_session, preview.ownerAgentName.orEmpty())
    AgentConfigOp.UPDATE_SESSION -> if (preview.isCurrentTarget) stringResource(R.string.agent_config_title_update_current_session)
        else stringResource(R.string.agent_config_title_update_session, preview.targetName)
}

@Composable
internal fun AgentConfigReason(reason: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag("CHAT_APPROVAL_AGENT_REASON"),
        )
    }
}

@Composable
private fun AgentConfigFieldTable(fields: List<FieldChange>, op: AgentConfigOp) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("CHAT_APPROVAL_AGENT_FIELDS"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            fields.forEachIndexed { index, field ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AgentConfigFieldRow(field, op)
            }
        }
    }
}

/** 一行字段：标题在左；值在右，“旧值 → 新值”，旧值加删除线。窄屏或大字号时值自动换行，不截断。 */
@Composable
private fun AgentConfigFieldRow(field: FieldChange, op: AgentConfigOp) {
    val unset = stringResource(R.string.agent_config_value_default)
    val scheme = MaterialTheme.colorScheme
    val value = buildAnnotatedString {
        if (op == AgentConfigOp.UPDATE_AGENT || op == AgentConfigOp.UPDATE_SESSION) {
            withStyle(SpanStyle(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)) {
                append(field.oldValue ?: unset)
            }
            append("  →  ")
        }
        withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) { append(field.newValue ?: unset) }
    }
    val label = agentConfigFieldLabel(field.key, op)
    val labelStyle = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        val gap = 10.dp
        val labelColumn = (maxWidth - gap) * 0.36f
        // 标题里最长的单词放不进标题列时改为上下排列，避免单词被拆到两行。
        val longestWord = with(LocalDensity.current) {
            label.split(' ').maxOf { word -> measurer.measure(word, labelStyle).size.width }.toDp()
        }
        if (longestWord <= labelColumn) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                Text(text = label, style = labelStyle, color = scheme.onSurfaceVariant, modifier = Modifier.weight(0.36f))
                Text(text = value, style = MaterialTheme.typography.bodySmall.forReading(), modifier = Modifier.weight(0.64f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = labelStyle, color = scheme.onSurfaceVariant)
                Text(text = value, style = MaterialTheme.typography.bodySmall.forReading())
            }
        }
    }
}

@Composable
internal fun agentConfigFieldLabel(key: String, op: AgentConfigOp): String {
    val sub = key.removePrefix("${SessionConfigKeys.INFERENCE_PARAMS}.")
    val res = when (sub) {
        "name" -> R.string.agent_config_field_name
        "description" -> R.string.agent_config_field_description
        "modelId" -> if (op.targetsAgent) R.string.agent_config_field_default_model else R.string.agent_config_field_model
        "temperature" -> R.string.agent_config_field_temperature
        "topP" -> R.string.agent_config_field_top_p
        "maxTokens" -> R.string.agent_config_field_max_tokens
        "topK" -> R.string.agent_config_field_top_k
        "frequencyPenalty" -> R.string.agent_config_field_frequency_penalty
        "presencePenalty" -> R.string.agent_config_field_presence_penalty
        "repetitionPenalty" -> R.string.agent_config_field_repetition_penalty
        "reasoningEffort" -> R.string.agent_config_field_reasoning_effort
        "mcpServerIds" -> R.string.agent_config_field_default_mcp
        "defaultWorkspaceId" -> R.string.agent_config_field_default_workspace
        "toolsEnabledByDefault" -> R.string.agent_config_field_tools_default
        AgentConfigPreviewBuilder.EXECUTION_MODE_KEY -> R.string.agent_config_field_execution_mode
        SessionConfigKeys.TITLE -> R.string.agent_config_field_title
        SessionConfigKeys.ACTIVE_SKILL_IDS -> R.string.agent_config_field_skills
        SessionConfigKeys.ACTIVE_MCP_SERVER_IDS -> R.string.agent_config_field_mcp
        else -> null
    }
    return res?.let { stringResource(it) } ?: key
}

@Composable
internal fun agentConfigImpact(impact: AgentConfigImpact): String = when (impact) {
    is AgentConfigImpact.AgentPrompt ->
        pluralStringResource(R.plurals.agent_config_impact_agent_prompt, impact.sessionCount, impact.agentName, impact.sessionCount)
    is AgentConfigImpact.AgentFields ->
        pluralStringResource(R.plurals.agent_config_impact_agent_fields, impact.sessionCount, impact.agentName, impact.sessionCount)
    AgentConfigImpact.NewAgent -> stringResource(R.string.agent_config_impact_new_agent)
    AgentConfigImpact.NewSession -> stringResource(R.string.agent_config_impact_new_session)
    AgentConfigImpact.CurrentSession -> stringResource(R.string.agent_config_impact_current_session)
    AgentConfigImpact.OtherSession -> stringResource(R.string.agent_config_impact_other_session)
}
