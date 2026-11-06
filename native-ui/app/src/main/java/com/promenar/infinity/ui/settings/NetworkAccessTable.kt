package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.network.AgentNetworkSnapshot
import com.promenar.infinity.domain.network.AgentNetworkTool
import com.promenar.infinity.domain.network.NetworkAccessRow
import com.promenar.infinity.domain.network.NetworkAccessState
import com.promenar.infinity.domain.network.NetworkTargetClass
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 单元格的视觉档位：放行、受条件约束、当前不可达。 */
internal enum class NetworkCellTone { OPEN, LIMITED, CLOSED }

/** 单元格结合当前 Tailscale 状态后的路径含义。 */
internal enum class NetworkCellMeaning {
    ALLOWED, UNRESTRICTED, CONFIGURED,
    TAILNET_READY, TAILNET_OFFLINE, TAILNET_DISABLED, TAILNET_MISSING, DENIED,
}

internal data class NetworkCell(val target: NetworkTargetClass, val meaning: NetworkCellMeaning, val tone: NetworkCellTone)

/** 会话的运行模式，决定总表列出哪些行；null 表示不针对具体会话（全局设置页）。 */
internal enum class NetworkRunMode { BUILTIN, LINUX }

/** 工具行的适用范围标注：仅标注运行模式范围。 */
internal enum class NetworkRowScope { ALL_MODES, BUILTIN_ONLY }

internal fun networkRowScope(tool: AgentNetworkTool): NetworkRowScope = when (tool) {
    AgentNetworkTool.SCRIPT -> NetworkRowScope.BUILTIN_ONLY
    else -> NetworkRowScope.ALL_MODES
}

/**
 * 会话可达范围按运行模式取总表的行：Linux 会话没有隔离脚本与 Termux，去掉这两行；全局设置页（null）列出全部。
 * Linux 模式下“内置命令”一行显示为 Linux 命令：两者都由系统网络决定，内置 Tailscale 运行时
 * 都注入同一组代理变量（`LinuxLaunchBuilder` 与 `BuiltinExecEnvironment` 均取 `subprocessEnvironment()`），
 * proot 不隔离网络，guest 能连到本机代理，因此 tailnet 一格与内置命令相同。
 */
internal fun networkRowsFor(rows: List<NetworkAccessRow>, runMode: NetworkRunMode?): List<NetworkAccessRow> =
    if (runMode == NetworkRunMode.LINUX) rows.filterNot { it.tool in LINUX_ABSENT_TOOLS } else rows

/** Linux 会话工具集不含的联网工具，与 `SessionToolResolver.LINUX_HIDDEN_TOOLS` 对应。 */
private val LINUX_ABSENT_TOOLS = setOf(AgentNetworkTool.SCRIPT, AgentNetworkTool.TERMUX)

/** 把总表的静态规则与当前设置合成用户能直接读懂的状态，给出直连、配置端点和内置代理的路径。 */
internal fun networkCell(target: NetworkTargetClass, state: NetworkAccessState, snapshot: AgentNetworkSnapshot): NetworkCell = when (state) {
    NetworkAccessState.ALLOWED -> NetworkCell(target, NetworkCellMeaning.ALLOWED, NetworkCellTone.OPEN)
    NetworkAccessState.UNRESTRICTED -> NetworkCell(target, NetworkCellMeaning.UNRESTRICTED, NetworkCellTone.OPEN)
    NetworkAccessState.CONFIGURED_ONLY -> NetworkCell(target, NetworkCellMeaning.CONFIGURED, NetworkCellTone.LIMITED)
    NetworkAccessState.DENIED -> NetworkCell(target, NetworkCellMeaning.DENIED, NetworkCellTone.CLOSED)
    NetworkAccessState.TAILNET_REQUIRED -> when {
        !snapshot.tailnetAvailable -> NetworkCell(target, NetworkCellMeaning.TAILNET_MISSING, NetworkCellTone.CLOSED)
        !snapshot.tailnetEnabled -> NetworkCell(target, NetworkCellMeaning.TAILNET_DISABLED, NetworkCellTone.CLOSED)
        !snapshot.tailnetRunning -> NetworkCell(target, NetworkCellMeaning.TAILNET_OFFLINE, NetworkCellTone.LIMITED)
        else -> NetworkCell(target, NetworkCellMeaning.TAILNET_READY, NetworkCellTone.OPEN)
    }
}

@Composable
internal fun networkToolLabel(tool: AgentNetworkTool, runMode: NetworkRunMode? = null): String = stringResource(
    when (tool) {
        AgentNetworkTool.WEB_FETCH -> R.string.network_tool_web_fetch
        AgentNetworkTool.MEDIA_FETCH -> R.string.network_tool_media_fetch
        AgentNetworkTool.SCRIPT -> R.string.network_tool_script
        AgentNetworkTool.MCP -> R.string.network_tool_mcp
        AgentNetworkTool.BUILTIN_PROCESS ->
            if (runMode == NetworkRunMode.LINUX) R.string.network_tool_linux else R.string.network_tool_builtin
        AgentNetworkTool.TERMUX -> R.string.network_tool_termux
        AgentNetworkTool.SSH -> R.string.network_tool_ssh
    },
)

@Composable
private fun networkToolHint(tool: AgentNetworkTool, runMode: NetworkRunMode?): String = stringResource(
    when (tool) {
        AgentNetworkTool.WEB_FETCH -> R.string.network_tool_web_fetch_hint
        AgentNetworkTool.MEDIA_FETCH -> R.string.network_tool_media_fetch_hint
        AgentNetworkTool.SCRIPT -> R.string.network_tool_script_hint
        AgentNetworkTool.MCP -> R.string.network_tool_mcp_hint
        AgentNetworkTool.BUILTIN_PROCESS ->
            if (runMode == NetworkRunMode.LINUX) R.string.network_tool_linux_hint else R.string.network_tool_builtin_hint
        AgentNetworkTool.TERMUX -> R.string.network_tool_termux_hint
        AgentNetworkTool.SSH -> R.string.network_tool_ssh_hint
    },
)

@Composable
private fun networkScopeLabel(scope: NetworkRowScope): String? = when (scope) {
    NetworkRowScope.ALL_MODES -> null
    NetworkRowScope.BUILTIN_ONLY -> stringResource(R.string.ux2_network_scope_builtin_only)
}

@Composable
private fun targetLabel(target: NetworkTargetClass): String = stringResource(
    when (target) {
        NetworkTargetClass.PUBLIC -> R.string.network_target_public
        NetworkTargetClass.LAN -> R.string.network_target_lan
        NetworkTargetClass.LOOPBACK -> R.string.network_target_loopback
        NetworkTargetClass.TAILNET -> R.string.network_target_tailnet
    },
)

@Composable
private fun meaningLabel(cell: NetworkCell): String = when (cell.meaning) {
    NetworkCellMeaning.ALLOWED -> stringResource(R.string.network_cell_allowed)
    NetworkCellMeaning.UNRESTRICTED -> stringResource(R.string.network_cell_unrestricted)
    NetworkCellMeaning.CONFIGURED -> stringResource(R.string.network_cell_configured)
    NetworkCellMeaning.TAILNET_READY -> stringResource(R.string.network_cell_tailnet_ready)
    NetworkCellMeaning.TAILNET_OFFLINE -> stringResource(R.string.network_cell_tailnet_offline)
    NetworkCellMeaning.TAILNET_DISABLED -> stringResource(R.string.network_cell_tailnet_disabled)
    NetworkCellMeaning.TAILNET_MISSING -> stringResource(R.string.network_cell_tailnet_missing)
    NetworkCellMeaning.DENIED -> stringResource(R.string.network_cell_denied)
}

/**
 * 网络访问总表：每个工具一行，名称与一句范围说明在上，四类目标的状态标签在下（窄屏自动换行）。
 * 只做展示；规则来自 [AgentNetworkSnapshot.rows]，与工具执行时的判定同源。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkAccessRows(
    snapshot: AgentNetworkSnapshot,
    modifier: Modifier = Modifier,
    tools: Set<AgentNetworkTool>? = null,
    runMode: NetworkRunMode? = null,
) {
    Column(modifier.fillMaxWidth()) {
        networkRowsFor(snapshot.rows, runMode).filter { tools == null || it.tool in tools }.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Medium)
                    .testTag("network_row_${row.tool.name.lowercase()}"),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Text(
                    text = networkToolLabel(row.tool, runMode),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = networkToolHint(row.tool, runMode),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                networkScopeLabel(networkRowScope(row.tool))?.let { scope ->
                    Text(
                        text = stringResource(R.string.ux2_network_scope_prefix, scope),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("network_scope_${row.tool.name.lowercase()}"),
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(top = InfinitySpacing.XSmall),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.cells.forEach { cell -> NetworkCellChip(networkCell(cell.target, cell.state, snapshot)) }
                }
            }
        }
    }
}

@Composable
private fun NetworkCellChip(cell: NetworkCell) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (cell.tone) {
        NetworkCellTone.OPEN -> scheme.primaryContainer to scheme.onPrimaryContainer
        NetworkCellTone.LIMITED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        NetworkCellTone.CLOSED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val target = targetLabel(cell.target)
    val meaning = meaningLabel(cell)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$target：$meaning" },
    ) {
        InfinityMetaLine(
            segments = listOf(target, meaning),
            style = MaterialTheme.typography.labelMedium,
            color = if (cell.tone == NetworkCellTone.CLOSED) content.copy(alpha = 0.85f) else Color.Unspecified,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.XSmall),
        )
    }
}
