package com.promenar.infinity.ui.hub

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.workspace.AgentDefaultWorkspaceRow

/** 单选面板的一项：标题、一句影响说明，默认项在标题后注明“（默认）”。 */
internal data class AgentChoice<T>(
    val value: T,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val isDefault: Boolean = false,
)

internal val executionModeChoices = listOf(
    AgentChoice(ExecutionMode.WORKSPACE, R.string.common_mode_workspace, R.string.common_mode_workspace_desc, isDefault = true),
    AgentChoice(ExecutionMode.FULL_ACCESS, R.string.common_mode_full_access, R.string.common_mode_full_access_desc),
)

internal val retrievalModeChoices = listOf(
    AgentChoice(AgentRetrievalMode.AUTO, R.string.agent_edit_retrieval_auto, R.string.agent_edit_retrieval_auto_desc, isDefault = true),
    AgentChoice(AgentRetrievalMode.TOOL, R.string.agent_edit_retrieval_tool, R.string.agent_edit_retrieval_tool_desc),
    AgentChoice(AgentRetrievalMode.BOTH, R.string.agent_edit_retrieval_both, R.string.agent_edit_retrieval_both_desc),
)

internal val fileAccessChoices = listOf(
    AgentChoice(KnowledgeFileAccess.OFF, R.string.agent_edit_file_access_off, R.string.agent_edit_file_access_off_desc),
    AgentChoice(KnowledgeFileAccess.ON_REQUEST, R.string.agent_edit_file_access_on_request, R.string.agent_edit_file_access_on_request_desc, isDefault = true),
    AgentChoice(KnowledgeFileAccess.ALWAYS, R.string.agent_edit_file_access_always, R.string.agent_edit_file_access_always_desc),
)

private fun <T> List<AgentChoice<T>>.labelOf(value: T): Int = (firstOrNull { it.value == value } ?: first()).labelRes

/** MCP 面板的一行；已选但现已停用或不允许连接的服务器仍列出，便于取消选择。 */
internal data class AgentMcpRow(val id: String, val name: String, val checked: Boolean, val available: Boolean)

/**
 * 可选服务器按原顺序列出；已选而不可选的排在后面并标为不可用，已删除的服务器以 id 显示。
 * 值行计数只算可用且已选的服务器，与新会话实际连接的一致。
 */
internal fun agentMcpRows(
    servers: List<McpServerEntity>,
    selectedIds: List<String>,
    isSelectable: (McpServerEntity) -> Boolean,
): List<AgentMcpRow> {
    val selectable = servers.filter(isSelectable)
    val selectableIds = selectable.mapTo(hashSetOf()) { it.id }
    val byId = servers.associateBy { it.id }
    return selectable.map { AgentMcpRow(it.id, it.name, it.id in selectedIds, available = true) } +
        selectedIds.filterNot { it in selectableIds }.distinct()
            .map { id -> AgentMcpRow(id, byId[id]?.name ?: id, checked = true, available = false) }
}

/** “新会话默认”：执行模式、默认工作区、技能摘要与 MCP。只影响之后新建的会话。 */
@Composable
internal fun AgentNewSessionDefaultsSection(
    executionMode: ExecutionMode,
    workspaceId: String?,
    workspace: WorkspaceEntity?,
    /** 已启用的用户技能数；首次扫描完成前为 null。 */
    enabledUserSkills: Int?,
    mcpRows: List<AgentMcpRow>,
    toolsEnabledByDefault: Boolean,
    onToolsEnabledByDefaultChange: (Boolean) -> Unit,
    onExecutionModeChange: (ExecutionMode) -> Unit,
    onChooseWorkspace: () -> Unit,
    onOpenSkills: () -> Unit,
    onMcpToggle: (String, Boolean) -> Unit,
) {
    var showModePicker by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    val selectedMcp = mcpRows.count { it.checked && it.available }
    InfinitySettingsSection(
        title = stringResource(R.string.agent_edit_section_new_session),
    ) {
        InfinityValueRow(
            icon = Icons.Rounded.Shield,
            title = stringResource(R.string.sheet_tool_execution_mode),
            value = stringResource(executionModeChoices.labelOf(executionMode)),
            onClick = { showModePicker = true },
            modifier = Modifier.testTag("agent_edit_execution_mode"),
        )
        AgentDefaultWorkspaceRow(workspaceId = workspaceId, workspace = workspace, onChoose = onChooseWorkspace)
        SettingsToggle(
            title = stringResource(R.string.ux2_agent_tools_default),
            icon = Icons.Rounded.Build,
            checked = toolsEnabledByDefault,
            onCheckedChange = onToolsEnabledByDefaultChange,
            modifier = Modifier.testTag(UiTags.AGENT_EDIT_TOOLS_DEFAULT),
        )
        // 会话的技能名单运行时不生效（用户技能按全局启用名单提供），这里只显示摘要并跳转全局技能包。
        InfinitySettingsItem(
            icon = Icons.Rounded.Extension,
            title = stringResource(R.string.agent_edit_skills),
            subtitle = enabledUserSkills?.let { pluralStringResource(R.plurals.agent_edit_skills_value, it, it) }
                ?: stringResource(R.string.agent_edit_skills_desc),
            onClick = onOpenSkills,
            showChevron = true,
            modifier = Modifier.testTag("agent_edit_skills"),
        )
        InfinityValueRow(
            icon = Icons.Rounded.Hub,
            title = stringResource(R.string.agent_edit_mcp),
            value = if (selectedMcp == 0) stringResource(R.string.agent_edit_mcp_none)
            else pluralStringResource(R.plurals.agent_edit_mcp_value, selectedMcp, selectedMcp),
            onClick = { showMcp = true },
            modifier = Modifier.testTag("agent_edit_mcp"),
        )
    }
    AgentChoiceSheet(
        show = showModePicker,
        title = stringResource(R.string.sheet_tool_execution_mode),
        choices = executionModeChoices,
        selected = executionMode,
        tagPrefix = "agent_edit_execution_mode",
        onSelect = { onExecutionModeChange(it); showModePicker = false },
        onDismiss = { showModePicker = false },
    )
    AgentMcpSheet(show = showMcp, rows = mcpRows, onToggle = onMcpToggle, onDismiss = { showMcp = false })
}

/** “知识与记忆”：检索方式与知识库文件访问（两项互不联动）。 */
@Composable
internal fun AgentKnowledgeMemorySection(
    retrievalMode: AgentRetrievalMode,
    fileAccess: KnowledgeFileAccess,
    policy: AgentMemoryPolicy,
    onRetrievalModeChange: (AgentRetrievalMode) -> Unit,
    onFileAccessChange: (KnowledgeFileAccess) -> Unit,
    onPolicyChange: ((AgentMemoryPolicy) -> AgentMemoryPolicy) -> Unit,
) {
    var showMode by remember { mutableStateOf(false) }
    var showAccess by remember { mutableStateOf(false) }
    InfinitySettingsSection(
        title = stringResource(R.string.agent_edit_section_knowledge_memory),
        footer = stringResource(R.string.agent_edit_knowledge_footer),
    ) {
        InfinityValueRow(
            icon = Icons.Rounded.TravelExplore,
            title = stringResource(R.string.agent_edit_retrieval_mode),
            value = stringResource(retrievalModeChoices.labelOf(retrievalMode)),
            onClick = { showMode = true },
            modifier = Modifier.testTag("agent_edit_retrieval_mode"),
        )
        InfinityValueRow(
            icon = Icons.Rounded.FolderSpecial,
            title = stringResource(R.string.agent_edit_file_access),
            value = stringResource(fileAccessChoices.labelOf(fileAccess)),
            onClick = { showAccess = true },
            modifier = Modifier.testTag("agent_edit_file_access"),
        )
    }
    AgentChoiceSheet(
        show = showMode,
        title = stringResource(R.string.agent_edit_retrieval_mode),
        choices = retrievalModeChoices,
        selected = retrievalMode,
        tagPrefix = "agent_edit_retrieval_mode",
        onSelect = { onRetrievalModeChange(it); showMode = false },
        onDismiss = { showMode = false },
    )
    AgentChoiceSheet(
        show = showAccess,
        title = stringResource(R.string.agent_edit_file_access),
        choices = fileAccessChoices,
        selected = fileAccess,
        tagPrefix = "agent_edit_file_access",
        onSelect = { onFileAccessChange(it); showAccess = false },
        onDismiss = { showAccess = false },
    )
}

@Composable
private fun <T> AgentChoiceSheet(
    show: Boolean,
    title: String,
    choices: List<AgentChoice<T>>,
    selected: T,
    tagPrefix: String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss, title = title, scrollContent = true) {
        choices.forEach { choice ->
            val label = stringResource(choice.labelRes)
            val isSelected = choice.value == selected
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = if (choice.isDefault) stringResource(R.string.numeric_default_value, label) else label,
                subtitle = stringResource(choice.descriptionRes),
                subtitleMaxLines = 3,
                role = Role.RadioButton,
                selected = isSelected,
                modifier = Modifier.testTag("${tagPrefix}_${choice.value.toString().lowercase()}"),
                onClick = { onSelect(choice.value) },
                trailingContent = {
                    if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun AgentMcpSheet(
    show: Boolean,
    rows: List<AgentMcpRow>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss, title = stringResource(R.string.agent_edit_mcp), scrollContent = true) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.agent_edit_mcp_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp).testTag("agent_edit_mcp_empty"),
            )
            return@InfinityBottomSheet
        }
        InfinitySettingsGroup(footer = stringResource(R.string.agent_edit_mcp_sheet_footer)) {
            rows.forEach { row ->
                SettingsToggle(
                    title = row.name,
                    description = if (row.available) null else stringResource(R.string.agent_edit_mcp_unavailable),
                    icon = Icons.Rounded.Sync,
                    checked = row.checked,
                    onCheckedChange = { onToggle(row.id, it) },
                    modifier = Modifier.testTag("agent_edit_mcp_${row.id}"),
                )
            }
        }
    }
}
