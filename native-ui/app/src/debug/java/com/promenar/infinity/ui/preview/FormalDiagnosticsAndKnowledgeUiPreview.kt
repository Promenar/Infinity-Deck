package com.promenar.infinity.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.knowledge.KnowledgeAccessTarget
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.startup.diagnostics.TraceSessionSummary
import com.promenar.infinity.ui.chat.ApprovalCard
import com.promenar.infinity.ui.chat.ApprovalDisplayCall
import com.promenar.infinity.ui.chat.KnowledgeAccessApproval
import com.promenar.infinity.ui.chat.REQUEST_KNOWLEDGE_ACCESS_TOOL
import com.promenar.infinity.ui.chat.approvalRiskLabel
import com.promenar.infinity.ui.chat.components.ExternalMountManagerContent
import com.promenar.infinity.ui.chat.components.ExternalMountSource
import com.promenar.infinity.ui.chat.components.KnowledgeMountOption
import com.promenar.infinity.ui.developer.DeveloperScreen
import com.promenar.infinity.ui.developer.TraceSessionPickerContent
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@Composable
private fun FormalPreviewFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}

@Composable
private fun FormalKnowledgeAccessContent() {
    val riskLabel = approvalRiskLabel("access_grant")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ApprovalCard(
            toolName = REQUEST_KNOWLEDGE_ACCESS_TOOL,
            description = "",
            calls = listOf(
                ApprovalDisplayCall(
                    name = REQUEST_KNOWLEDGE_ACCESS_TOOL,
                    argumentsSummary = "{}",
                    riskLabel = riskLabel,
                    knowledgeAccess = KnowledgeAccessApproval(
                        reason = "需要查阅《帝国暗面》设定文档并核对 02-12 录音整理稿全文。",
                        targets = listOf(
                            KnowledgeAccessTarget("kb-1", "RAG Workspace", 11, "knowledge", "/mnt/knowledge", readOnly = true),
                        ),
                        allowed = true,
                        readWrite = false,
                    ),
                ),
            ),
        )
        ApprovalCard(
            toolName = REQUEST_KNOWLEDGE_ACCESS_TOOL,
            description = "",
            calls = listOf(
                ApprovalDisplayCall(
                    name = REQUEST_KNOWLEDGE_ACCESS_TOOL,
                    argumentsSummary = "{}",
                    riskLabel = riskLabel,
                    knowledgeAccess = KnowledgeAccessApproval(
                        reason = "需要在知识库中新建整理笔记、归类设定文档，并将过时草稿移至新目录。",
                        targets = listOf(
                            KnowledgeAccessTarget("kb-1", "RAG Workspace", 11, "knowledge", "/mnt/knowledge", readOnly = false),
                        ),
                        allowed = true,
                        readWrite = true,
                    ),
                ),
            ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().height(520.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            ExternalMountManagerContent(
                mounts = listOf(
                    ExternalMount("k-rw", "knowledge", "knowledge_base_rw", "root-1", "s1", createdAt = 1, readOnly = false),
                    ExternalMount("k-ro", "archive", "knowledge_base", "root-2", "s1", createdAt = 2, readOnly = true),
                ),
                sources = listOf(ExternalMountSource.Saf, ExternalMountSource.RealPath),
                loading = false,
                busy = false,
                notice = null,
                onRefresh = {},
                onAdd = {},
                onBrowse = {},
                onReauthorize = {},
                onRemove = {},
                onGrantRealPath = {},
                linuxSession = true,
                knowledgeMount = KnowledgeMountOption.Mounted,
            )
        }
    }
}

private val SampleTraceSessions = listOf(
    TraceSessionSummary("s1", "符号链接阻断文件工具", "LINUX", 1763476680000L, 8),
    TraceSessionSummary("s2", "Simple Addition Calculation", "BUILTIN", 1763455740000L, 2),
)

@Preview(name = "正式实现 知识库分级授权 深色 360dp", widthDp = 360, heightDp = 1580, locale = "zh-rCN", showBackground = true)
@Composable
internal fun FormalKnowledgeAccessDarkPreview() = FormalPreviewFrame(InfinityThemeMode.DARK) {
    FormalKnowledgeAccessContent()
}

@Preview(name = "正式实现 知识库分级授权 浅英 360dp 大字", widthDp = 360, heightDp = 1980, locale = "en", fontScale = 1.3f, showBackground = true)
@Composable
internal fun FormalKnowledgeAccessEnglishLargePreview() = FormalPreviewFrame(InfinityThemeMode.LIGHT) {
    FormalKnowledgeAccessContent()
}

@Preview(name = "正式实现 开发者诊断页 深色 360dp", widthDp = 360, heightDp = 960, locale = "zh-rCN", showBackground = true)
@Composable
internal fun FormalDeveloperDiagnosticsDarkPreview() = FormalPreviewFrame(InfinityThemeMode.DARK) {
    DeveloperScreen(
        onNavigateBack = {},
        onOpenVisualLab = {},
        onDisable = {},
        packageName = "com.promenar.infinity",
        adbBridgeEnabled = true,
        traceSessions = SampleTraceSessions,
    )
}

@Preview(name = "正式实现 开发者诊断页 浅色内屏 680dp", widthDp = 680, heightDp = 820, locale = "zh-rCN", showBackground = true)
@Composable
internal fun FormalDeveloperDiagnosticsWideLightPreview() = FormalPreviewFrame(InfinityThemeMode.LIGHT) {
    DeveloperScreen(
        onNavigateBack = {},
        onOpenVisualLab = {},
        onDisable = {},
        packageName = "com.promenar.infinity",
        adbBridgeEnabled = true,
        traceSessions = SampleTraceSessions,
    )
}

@Preview(name = "正式实现 会话轨迹选择弹层 深色 360dp", widthDp = 360, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
internal fun FormalTraceSessionPickerDarkPreview() = FormalPreviewFrame(InfinityThemeMode.DARK) {
    TraceSessionPickerContent(
        sessions = SampleTraceSessions,
        selectedSessionId = "s1",
        formatDate = { "11/18 22:38" },
        onSelect = {},
        onDismiss = {},
        onConfirm = {},
    )
}
