package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 内置工具按类别分组，每组一张卡片；说明写在行内，不再逐个工具拆成卡片加脚注。
 * 知识检索没有全局开关，是否提供由智能体的检索方式决定，在文件分组末尾以说明行列出（T1）。
 */
@Composable
internal fun BuiltinToolsSections(
    tools: List<SkillInfo>,
    onToggle: (String) -> Unit,
    onConfigureSearch: () -> Unit,
) {
    val grouped = tools.groupedByToolCategory().toMap()
    val categories = BuiltinToolCategory.entries.filter { it in grouped || it == BuiltinToolCategory.FILES }
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
        categories.forEach { category ->
            InfinitySettingsSection(title = stringResource(category.titleRes)) {
                grouped[category].orEmpty().forEach { tool ->
                    BuiltinToolRow(
                        tool = tool,
                        onToggle = { onToggle(tool.id) },
                        onConfig = if (tool.id == "web_search") onConfigureSearch else null,
                    )
                }
                if (category == BuiltinToolCategory.FILES) KnowledgeSearchInfoRow()
            }
        }
    }
}

@Composable
private fun KnowledgeSearchInfoRow() {
    InfinitySettingsRowLayout(
        icon = Icons.AutoMirrored.Rounded.LibraryBooks,
        title = stringResource(R.string.settings_ia_knowledge_search_title),
        subtitle = stringResource(R.string.settings_ia_knowledge_search_desc),
        subtitleMaxLines = 2,
        modifier = Modifier.testTag("builtin_tool:knowledge_search"),
    )
}

@Composable
private fun BuiltinToolRow(
    tool: SkillInfo,
    onToggle: () -> Unit,
    onConfig: (() -> Unit)?,
) {
    // 开关先在本地翻转，避免偏好写回前的闪烁。
    var enabled by remember(tool.id, tool.enabled) { mutableStateOf(tool.enabled) }
    InfinitySettingsItem(
        icon = builtinToolIcon(tool.id),
        title = tool.name,
        subtitle = tool.description.ifBlank { null },
        subtitleMaxLines = 2,
        onClick = { enabled = !enabled; onToggle() },
        modifier = Modifier.testTag("builtin_tool:${tool.id}"),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onConfig != null) {
                    IconButton(onClick = onConfig) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.common_cd_config), modifier = Modifier.size(20.dp))
                    }
                }
                InfinitySwitch(checked = enabled, onCheckedChange = { enabled = it; onToggle() })
            }
        },
    )
}

/** MCP 服务器列表：地址与连接状态写在行内，添加入口是分组末行。 */
@Composable
internal fun McpServersSection(
    servers: List<McpServerUiModel>,
    expandedServerId: String?,
    onToggleExpand: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSync: (String) -> Unit,
    onAdd: () -> Unit,
) {
    InfinitySettingsSection(title = stringResource(R.string.tools_section_mcp_servers)) {
        if (servers.isEmpty()) {
            SkillsEmptyState(icon = Icons.Rounded.Hub, message = stringResource(R.string.skills_mcp_empty))
        }
        servers.forEach { server ->
            McpServerRow(
                server = server,
                isExpanded = expandedServerId == server.id,
                onToggleExpand = { onToggleExpand(server.id) },
                onToggleEnabled = { onToggleEnabled(server.id, it) },
                onDelete = { onDelete(server.id) },
                onSync = { onSync(server.id) },
            )
        }
        InfinitySettingsItem(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.skills_add_mcp),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = onAdd,
            modifier = Modifier.testTag("mcp_add_server"),
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerUiModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
) {
    val enabledLabel = stringResource(R.string.skills_mcp_enabled)
    val status = when {
        !server.isSupported -> stringResource(R.string.skills_mcp_legacy_unsupported)
        server.syncError != null -> stringResource(R.string.skills_mcp_sync_error, server.syncError)
        server.isConnected -> stringResource(R.string.settings_batch1_mcp_connected)
        else -> stringResource(R.string.settings_batch1_mcp_disconnected)
    }
    Column(Modifier.fillMaxWidth()) {
        InfinitySettingsItem(
            icon = Icons.Rounded.Hub,
            title = server.name,
            // 状态放在前面：行尾有三个控件，长地址换行后仍能看到连接状态。
            subtitleSegments = listOf(status, server.url),
            subtitleMaxLines = 2,
            onClick = onToggleExpand,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSync, enabled = server.isSupported && server.isEnabled) {
                        Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.common_cd_sync), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_cd_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                    InfinitySwitch(
                        checked = server.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics { contentDescription = "${server.name}: $enabledLabel" },
                    )
                }
            },
        )
        if (server.tools.isNotEmpty()) {
            AnimatedVisibility(visible = isExpanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column(
                    Modifier.fillMaxWidth().padding(start = SkillTextStart, end = InfinitySpacing.RowHorizontal, bottom = 12.dp),
                ) {
                    server.tools.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Text(tool, style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
