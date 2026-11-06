package com.promenar.infinity.ui.startup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.startup.StartupIssue
import com.promenar.infinity.startup.StartupIssueAction
import com.promenar.infinity.startup.StartupIssueScope
import com.promenar.infinity.startup.StartupScopeKind
import com.promenar.infinity.startup.StartupSeverity
import com.promenar.infinity.startup.StartupStep
import com.promenar.infinity.startup.StartupSubsystem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val SampleRows = listOf(
    StartupIssueRow(
        StartupIssue(
            id = "issue-journal", step = StartupStep.WORKSPACE_JOURNAL, severity = StartupSeverity.ISOLATED,
            scope = StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root-1", "工作环境能力探测"),
            summary = "WorkspaceMutationRecoveryException: 移动类 journal 文件布局冲突",
            actions = setOf(StartupIssueAction.RETRY, StartupIssueAction.ABANDON), occurredAt = 0,
        ),
        scopeName = "工作环境能力探测",
    ),
    StartupIssueRow(
        StartupIssue(
            id = "issue-vectors", step = StartupStep.VECTOR_QUEUE, severity = StartupSeverity.ISOLATED,
            scope = StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE),
            summary = "向量队列恢复失败",
            actions = setOf(StartupIssueAction.RETRY), occurredAt = 0,
        ),
        scopeName = "记忆与索引",
    ),
)

@Composable
private fun StartupPreview(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) { Column { content() } }
    }
}

// 会话列表与会话顶栏下方的提示条，以及问题面板：一条可重试和放弃，一条只能重试。
@PreviewTest
@Preview(name = "启动问题 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun startupIssuesDarkPreview() = StartupPreview(InfinityThemeMode.DARK) {
    StartupIssuesBanner(count = 2, onOpen = {})
    Spacer(Modifier.height(8.dp))
    StartupWritePausedBanner(onOpen = {})
    Spacer(Modifier.height(16.dp))
    // 面板外壳给出 24dp 水平留白。
    Column(Modifier.padding(horizontal = 24.dp)) {
        StartupIssuesSheetContent(
            SampleRows, busyIssueId = null, message = "仍未解决", messageIssueId = "issue-journal",
            canDiagnose = true, onAction = { _, _ -> }, onDiagnose = {}, onViewRecords = {}, onExport = {},
        )
    }
}

@PreviewTest
@Preview(name = "启动问题 浅英 大字体", widthDp = 411, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun startupIssuesLightLargeFontPreview() = StartupPreview(InfinityThemeMode.LIGHT) {
    StartupIssuesBanner(count = 1, onOpen = {})
    Spacer(Modifier.height(16.dp))
    Column(Modifier.padding(horizontal = 24.dp)) {
        StartupIssuesSheetContent(SampleRows, busyIssueId = "issue-vectors", message = null, canDiagnose = false, onAction = { _, _ -> }, onDiagnose = {}, onViewRecords = {}, onExport = {})
    }
}
