package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeHunkOmission
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FileChangeDiffSheetContent
import com.promenar.infinity.ui.chat.diff.FileChangeSummaries
import com.promenar.infinity.ui.chat.diff.FileSnapshot
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val ConfigOld = (1..40).joinToString("\n") { "val setting$it = load(\"key.$it\")" } + "\n"
private val ConfigNew = ConfigOld
    .replace("val setting6 = load(\"key.6\")", "val setting6 = load(\"key.6\", fallback = true)")
    .replace("val setting28 = load(\"key.28\")\n", "")
    .replace("val setting33 = load(\"key.33\")", "val setting33 = loadSecure(\"key.33\")")

private val ModifiedChange = FileChangeSummaries.modified(
    "/app/src/main/java/com/example/settings/SettingsRepository.kt",
    FileSnapshot.Text(ConfigOld),
    FileSnapshot.Text(ConfigNew),
)
private val CreatedChange = FileChangeSummaries.created("/docs/新建说明.md", FileSnapshot.Text("# 标题\n\n第一段内容\n第二段内容\n"))
private val DeletedChange = FileChangeSummaries.deleted("/legacy.properties", FileSnapshot.Text("obsolete = true\nlegacy = 1\n"))
private val MovedChange = FileChangeSummaries.moved("/notes/draft.md", "/archive/2026/notes/draft.md", directory = false)
private val BinaryChange = FileChangeSummaries.created("/assets/logo.png", FileSnapshot.Binary)
private val OversizedChange = FileChangeSummary(
    path = "/data/export.csv",
    change = FileChangeType.MODIFIED,
    added = 1_204,
    removed = 37,
    large = true,
    hunksOmitted = FileChangeHunkOmission.SIZE_LIMIT,
)

private fun fileStep(index: Int, tool: String, change: FileChangeSummary, args: String = "{\"path\":\"${change.path}\"}"): List<ExecutionStep> {
    val time = 1_000L + index * 900L
    return listOf(
        ExecutionStep("call-$index", "tool_call", tool, args, "call-$index", timestamp = time),
        ExecutionStep(
            "result-$index", "tool_result", tool, toolCallId = "call-$index",
            content = "已保存：${change.path}", timestamp = time + 600, fileChanges = listOf(change),
        ),
    )
}

private val MultiFileSteps =
    fileStep(0, "patch_file", ModifiedChange) +
        fileStep(1, "create_file", CreatedChange) +
        fileStep(2, "delete_file", DeletedChange) +
        fileStep(3, "move_file", MovedChange)

private val ShellSteps = listOf(
    ExecutionStep("sh", "tool_call", "shell_exec", "{\"command\":\"python3 scripts/gen.py\"}", "sh", timestamp = 1_000),
    ExecutionStep(
        "sh-result", "tool_result", "shell_exec", toolCallId = "sh", timestamp = 2_400,
        content = """{"exit_code":0,"stdout":"生成完成","workspace_changes":{"added":["/out/report.md"],"modified":["/scripts/gen.py"],""" +
            """"removed":["/tmp/cache.json"],"moved":[{"from":"/notes/a.md","to":"/archive/notes/a.md"}],""" +
            """"counts":{"added":1,"modified":1,"removed":1,"moved":1},"truncated":true}}""",
    ),
)

@Composable
private fun FileChangeFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "执行卡 文件改动 折叠与展开 深中411", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Composable
fun fileChangeExecutionCardDarkPreview() = FileChangeFrame {
    InlineToolRow(fileStep(0, "write_file", ModifiedChange), isExecuting = false, fontSize = 15)
    InlineToolRow(MultiFileSteps, isExecuting = false, fontSize = 15)
    InlineToolRow(MultiFileSteps, isExecuting = false, fontSize = 15, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "执行卡 命令工作区改动 浅中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Composable
fun fileChangeShellWorkspaceLightPreview() = FileChangeFrame(dark = false) {
    InlineToolRow(fileStep(0, "create_file", BinaryChange) + fileStep(1, "write_file", OversizedChange), isExecuting = false, fontSize = 15, initiallyExpanded = true)
    InlineToolRow(ShellSteps, isExecuting = false, fontSize = 15, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "Execution card files English 360dp font130", widthDp = 360, heightDp = 880, locale = "en", fontScale = 1.3f)
@Composable
fun fileChangeExecutionCardEnglishLargeFontPreview() = FileChangeFrame {
    InlineToolRow(fileStep(0, "write_file", ModifiedChange), isExecuting = false, fontSize = 15)
    InlineToolRow(MultiFileSteps, isExecuting = false, fontSize = 15, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "改动差异面板 已存分段 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun fileChangeSheetHunksDarkPreview() = FileChangeFrame {
    FileChangeDiffSheetContent(ModifiedChange, maxViewportHeight = 520.dp)
}

@PreviewTest
@Preview(name = "改动差异面板 移动与降级 浅中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun fileChangeSheetFallbacksLightPreview() = FileChangeFrame(dark = false) {
    FileChangeDiffSheetContent(MovedChange, maxViewportHeight = 160.dp)
    FileChangeDiffSheetContent(OversizedChange, maxViewportHeight = 160.dp)
    FileChangeDiffSheetContent(BinaryChange, maxViewportHeight = 160.dp)
}

private val ApprovalOld = "# 项目周会\n日期：2026-10-18\n参与人：产品与研发\n\n## 待办事项\n1. 核对本周交付内容。\n2. 补充验收记录。\n"
private val ApprovalNew = "# 项目周会\n日期：2026-10-20\n参与人：产品、研发与测试\n\n## 待办事项\n1. 核对本周交付内容。\n2. 补充验收记录。\n3. 安排回归测试。\n"

private fun approvalWriteCall(diff: ApprovalDiffPreview?) = ApprovalDisplayCall(
    name = "write_file",
    argumentsSummary = "{\"path\":\"/notes/meeting.md\"}",
    riskLabel = "文件写入",
    preview = ApprovalPreview.Preview(ApprovalPreview.Action.OVERWRITE, "/notes/meeting.md", ApprovalNew),
    executionTarget = "[\"path\",\"/notes/meeting.md\"]",
    diffPreview = diff,
)

@PreviewTest
@Preview(name = "审批差异预览 覆盖与删除 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Composable
fun approvalDiffPreviewDarkPreview() = FileChangeFrame {
    ApprovalCard(
        toolName = "write_file",
        description = "逐项核对目标和内容，也可批准列表中的全部调用。",
        calls = listOf(
            approvalWriteCall(ApprovalDiffPreview.Diff(FileDiffModel.fromTexts(ApprovalOld, ApprovalNew, changeKind = FileChangeKind.MODIFIED))),
            ApprovalDisplayCall(
                name = "delete_file",
                argumentsSummary = "{\"path\":\"/notes/archive.md\"}",
                riskLabel = "删除文件",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.DELETE, "/notes/archive.md", null),
                executionTarget = "[\"path\",\"/notes/archive.md\"]",
                highRisk = true,
                diffPreview = ApprovalDiffPreview.Deletion(128),
            ),
        ),
        onApproveAll = {},
    )
}

@PreviewTest
@Preview(name = "审批差异预览 新建 浅中360", widthDp = 360, heightDp = 600, locale = "zh-rCN")
@Composable
fun approvalDiffPreviewCreateLightPreview() = FileChangeFrame(dark = false) {
    ApprovalCard(
        toolName = "create_file",
        description = "此操作会新建文件，请核对内容后批准。",
        calls = listOf(
            ApprovalDisplayCall(
                name = "create_file",
                argumentsSummary = "{\"name\":\"plan.md\"}",
                riskLabel = "新建文件",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.CREATE_FILE, "/docs/plan.md", ApprovalNew),
                diffPreview = ApprovalDiffPreview.Diff(FileDiffModel.fromTexts("", ApprovalNew, changeKind = FileChangeKind.CREATED)),
            ),
        ),
    )
}
