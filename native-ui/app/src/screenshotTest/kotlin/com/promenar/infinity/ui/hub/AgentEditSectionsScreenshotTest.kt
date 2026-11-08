package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val PreviewWorkspace = WorkspaceEntity(
    "ws-docs", WorkspaceEntity.KIND_SHARED, "Infinity Deck 文档", "/storage/emulated/0/Documents/Infinity Deck", 1,
)

@PreviewTest
@Preview(name = "智能体编辑 新会话默认与知识 深中411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Preview(name = "智能体编辑 新会话默认与知识 深中360 字号130", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "Agent editor defaults and knowledge EN 360", widthDp = 360, heightDp = 1300, locale = "en")
@Composable
fun agentEditSectionsPreview() = AgentEditPreviewSurface {
    AgentNewSessionDefaultsSection(
        executionMode = ExecutionMode.WORKSPACE,
        workspaceId = PreviewWorkspace.id,
        workspace = PreviewWorkspace,
        enabledUserSkills = 3,
        mcpRows = listOf(AgentMcpRow("fs", "filesystem", checked = true, available = true)),
        toolsEnabledByDefault = true,
        onToolsEnabledByDefaultChange = {},
        onExecutionModeChange = {},
        onChooseWorkspace = {},
        onOpenSkills = {},
        onMcpToggle = { _, _ -> },
    )
    AgentKnowledgeMemorySection(
        retrievalMode = AgentRetrievalMode.AUTO,
        fileAccess = KnowledgeFileAccess.ON_REQUEST,
        policy = AgentMemoryPolicy(),
        onRetrievalModeChange = {},
        onFileAccessChange = {},
        onPolicyChange = {},
    )
    AgentScopeSection(policy = AgentMemoryPolicy(), onPolicyChange = {}, onOpenKnowledgeSettings = {})
}

@PreviewTest
@Preview(name = "智能体编辑 私密 完全访问 无 MCP 深中360", widthDp = 360, heightDp = 1200, locale = "zh-rCN")
@Composable
fun agentEditSectionsPrivatePreview() = AgentEditPreviewSurface {
    AgentNewSessionDefaultsSection(
        executionMode = ExecutionMode.FULL_ACCESS,
        workspaceId = "gone",
        workspace = null,
        enabledUserSkills = null,
        mcpRows = emptyList(),
        toolsEnabledByDefault = false,
        onToolsEnabledByDefaultChange = {},
        onExecutionModeChange = {},
        onChooseWorkspace = {},
        onOpenSkills = {},
        onMcpToggle = { _, _ -> },
    )
    AgentKnowledgeMemorySection(
        retrievalMode = AgentRetrievalMode.TOOL,
        fileAccess = KnowledgeFileAccess.OFF,
        policy = AgentMemoryPolicy(shareWithOtherAgents = false),
        onRetrievalModeChange = {},
        onFileAccessChange = {},
        onPolicyChange = {},
    )
}

@Composable
private fun AgentEditPreviewSurface(content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
        }
    }
}
