package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.agentconfig.AgentConfigOp
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.ui.chat.agentConfigFieldLabel
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.PromptEditorHandle
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 冲突提示条：琥珀色底，说明在上，操作按钮在下；按钮文字单行，放不下时整组竖排。 */
@Composable
internal fun AgentConflictBanner(
    message: String,
    modifier: Modifier = Modifier,
    details: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.infinityDomainColors.warningContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.infinityDomainColors.onWarningContainer,
            )
            details()
            // 三个操作是同级的替代方案，竖排时保持原顺序。
            InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp, reverseWhenStacked = false) { actions() }
        }
    }
}

/** 编辑页非提示词字段的冲突：列出两边的值，选择采用新内容或保留自己的。 */
@Composable
internal fun AgentFieldConflictBanner(
    conflict: AgentEditConflict,
    local: (AgentField) -> String,
    onAdopt: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = conflict.fields.map { agentEditFieldName(it) }.distinct().joinToString("、")
    AgentConflictBanner(
        message = stringResource(R.string.agent_edit_conflict_field, names),
        modifier = modifier.testTag("agent_edit_conflict_banner"),
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                conflict.fields.filter { it.toolKey != null }.forEach { field ->
                    Text(
                        text = stringResource(R.string.agent_edit_conflict_mine, local(field)),
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = MaterialTheme.infinityDomainColors.onWarningContainer,
                    )
                    Text(
                        text = stringResource(R.string.agent_edit_conflict_theirs, agentFieldDisplay(field, conflict.remote)),
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.infinityDomainColors.onWarningContainer,
                    )
                }
            }
        },
    ) {
        InfinityPillButton(stringResource(R.string.agent_edit_conflict_adopt), onAdopt, Modifier.testTag("agent_edit_conflict_adopt"))
        InfinityPillButton(stringResource(R.string.agent_edit_conflict_keep), onKeep, Modifier.testTag("agent_edit_conflict_keep"), tone = InfinityPillTone.PRIMARY)
    }
}

/**
 * 提示词编辑器内的冲突提示条：查看差异（你的草稿 → 智能体的版本）、采用新内容（草稿存为历史版本）、保留我的（保存草稿）。
 * 没有冲突但刚采用了新内容时，显示一行说明。
 */
@Composable
internal fun AgentPromptConflictBanner(
    conflict: AgentPromptConflict?,
    notice: AgentEditNotice?,
    handle: PromptEditorHandle,
    onAdopt: suspend (String) -> String?,
    onKeep: suspend (String) -> Result<Unit>,
    onNoticeShown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showDiff by remember(conflict) { mutableStateOf(false) }
    var busy by remember(conflict) { mutableStateOf(false) }
    if (conflict == null) {
        if (notice == AgentEditNotice.ADOPTED_REMOTE_PROMPT) {
            AgentEditNoticeLine(notice, onNoticeShown, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        return
    }
    AgentConflictBanner(
        message = stringResource(R.string.agent_edit_conflict_prompt),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("agent_prompt_conflict_banner"),
        details = {
            if (showDiff) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.agent_edit_conflict_diff_caption),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.infinityDomainColors.onWarningContainer,
                    )
                    FileDiffView(
                        oldText = handle.text,
                        newText = conflict.remotePrompt,
                        maxViewportHeight = 220.dp,
                        modifier = Modifier.testTag("agent_prompt_conflict_diff"),
                    )
                }
            }
        },
    ) {
        InfinityPillButton(
            stringResource(if (showDiff) R.string.agent_edit_conflict_hide_diff else R.string.agent_edit_conflict_view_diff),
            { showDiff = !showDiff },
            Modifier.testTag("agent_prompt_conflict_diff_toggle"),
        )
        InfinityPillButton(
            stringResource(R.string.agent_edit_conflict_adopt),
            {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            onAdopt(handle.text)?.let(handle::replace)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            Modifier.testTag("agent_prompt_conflict_adopt"),
            enabled = !busy,
        )
        InfinityPillButton(
            stringResource(R.string.agent_edit_conflict_keep),
            {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            if (onKeep(handle.text).isSuccess) handle.close()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            Modifier.testTag("agent_prompt_conflict_keep"),
            tone = InfinityPillTone.PRIMARY,
            enabled = !busy,
        )
    }
}

/** 冲突处理与恢复后的一次性说明，约 2.6 秒后消失。 */
@Composable
internal fun AgentEditNoticeLine(notice: AgentEditNotice, onShown: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(notice) {
        delay(2_600)
        onShown()
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("agent_edit_notice"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = stringResource(
                when (notice) {
                    AgentEditNotice.ADOPTED_REMOTE_PROMPT -> R.string.agent_edit_notice_adopted_prompt
                    AgentEditNotice.KEPT_MY_PROMPT -> R.string.agent_edit_notice_kept_prompt
                    AgentEditNotice.ADOPTED_REMOTE -> R.string.agent_edit_notice_adopted
                    AgentEditNotice.KEPT_MINE -> R.string.agent_edit_notice_kept
                    AgentEditNotice.RESTORED -> R.string.agent_edit_notice_restored
                },
            ),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 冲突提示里的字段名；工具可写字段沿用审批卡的字段名，其余为界面设置。 */
@Composable
internal fun agentEditFieldName(field: AgentField): String = when (field) {
    AgentField.ICON, AgentField.COLOR, AgentField.AVATAR_PATH -> stringResource(R.string.agent_avatar_row_title)
    AgentField.SYSTEM_PROMPT -> stringResource(R.string.agent_edit_prompt_label)
    else -> field.toolKey?.let { agentConfigFieldLabel(it, AgentConfigOp.UPDATE_AGENT) }
        ?: stringResource(R.string.agent_edit_conflict_other_setting)
}

/** 冲突提示里的字段值；名称与描述等文本原样显示，其余只给出简短文本。 */
@Composable
internal fun agentFieldDisplay(field: AgentField, agent: Agent): String {
    val empty = stringResource(R.string.agent_config_value_default)
    return when (field) {
        AgentField.NAME -> agent.name
        AgentField.DESCRIPTION -> agent.description
        AgentField.SYSTEM_PROMPT -> agent.systemPrompt
        AgentField.TOOLS_ENABLED_BY_DEFAULT -> stringResource(if (agent.toolsEnabledByDefault) R.string.agent_config_value_on else R.string.agent_config_value_off)
        AgentField.MCP_SERVER_IDS -> agent.mcpServerIds.joinToString("、")
        else -> field.read(agent)?.toString()
    }?.takeIf(String::isNotBlank) ?: empty
}
