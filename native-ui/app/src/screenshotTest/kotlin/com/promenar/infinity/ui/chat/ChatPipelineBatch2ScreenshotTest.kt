@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ColorImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "用户消息 单行多行附件", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable
fun chatBatch2UserMessagesDarkPreview() = PipelinePreviewFrame {
    UserMessageBubble(Message("single", MessageRole.USER, "请帮我整理这份资料。"), 15)
    Spacer(Modifier.height(20.dp))
    UserMessageBubble(Message("multi", MessageRole.USER, "请比较两个方案的成本和风险，并列出执行步骤。\n我希望先看到结论，再查看各项依据和测算。"), 15)
    Spacer(Modifier.height(20.dp))
    UserMessageBubble(
        Message(
            "attachments", MessageRole.USER, "结合附件给出你的建议。",
            userImages = listOf("android.resource://android/${android.R.drawable.ic_menu_gallery}"),
            userDocuments = listOf(MessageDocumentAttachment("document", "项目说明.pdf", "application/pdf", "附件正文", 24_576, "fixture", 100)),
        ), 15,
    )
}

@PreviewTest
@Preview(name = "助手操作行 完成", widthDp = 411, heightDp = 260, locale = "zh-rCN")
@Composable
fun chatBatch2AssistantActionsDarkPreview() = PipelinePreviewFrame {
    AssistantActionRow("Claude Sonnet 4.5", "14:35", false, onCopy = {}, onRegenerate = {}, onBranch = {}, onDelete = {})
    Spacer(Modifier.height(20.dp))
    AssistantActionRow("超长模型展示名称 用于验证模型截断与时间保持可见", "14:36", false, onCopy = {}, onRegenerate = {}, onBranch = {}, onDelete = {})
}

@PreviewTest
@Preview(name = "步骤行 思考与工具各状态", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun chatBatch2StepStatesDarkPreview() = PipelinePreviewFrame {
    ThinkingTrace("先核对输入，再分析各个方案的边界和执行条件。", true, 15, elapsedMs = 6_000)
    ThinkingTrace("已经完成分析。", false, 15, elapsedMs = 6_000)
    InlineToolRow(listOf(ExecutionStep("running", "tool_call", "web_search", "{\"query\":\"资料\"}", "active", timestamp = 1_000)), true, 15)
    InlineToolRow(listOf(ExecutionStep("pending", "tool_call", "web_search", "{\"query\":\"资料\"}", "pending", timestamp = 1_000)), false, 15)
    InlineToolRow(listOf(ExecutionStep("stopped", "tool_call", "web_search", "{\"query\":\"资料\"}", "stopped", timestamp = 1_000)), false, 15, turnActive = false)
    InlineToolRow(previewToolSteps("ssh_exec", "exec_js"), false, 15)
    InlineToolRow(previewToolSteps("ssh_exec", "read_file", "list_files"), false, 15)
    InlineToolRow(previewToolSteps("ssh_exec", "read_file", "web_search"), false, 15)
    InlineToolRow(previewToolSteps("ssh_exec", "exec_js", failedIndex = 1), false, 15)
    InlineToolRow(previewToolSteps("knowledge_search", "memory_search"), false, 15)
    InlineToolRow(previewToolSteps("current_time", "calculator"), false, 15)
    InlineToolRow(previewToolSteps("mcp__custom__tool"), false, 15)
}

@PreviewTest
@Preview(name = "工具展开 长参数与结果", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Composable
fun chatBatch2ExpandedToolsDarkPreview() = PipelinePreviewFrame {
    InlineToolRow(
        steps = listOf(
            ExecutionStep("command", "tool_call", "exec_js",
                (1..10).joinToString("\n", prefix = "{\n", postfix = "\n}") { "  \"参数$it\": \"需要完整保留的执行内容\"," }, "call", timestamp = 1_000),
            ExecutionStep("result", "tool_result", "exec_js", toolCallId = "call",
                content = (1..18).joinToString("\n") { "第 $it 项：已校验执行结果与输入内容一致。" }, timestamp = 3_300),
            ExecutionStep("file", "tool_call", "read_file", "{\"path\":\"notes.md\"}", "file", timestamp = 3_400),
            ExecutionStep("error", "error", "read_file", toolCallId = "file", content = "未能读取文件：目标文件不存在。", timestamp = 3_500),
        ),
        isExecuting = false, fontSize = 15, initiallyExpanded = true,
    )
}

@PreviewTest
@Preview(name = "思考展开 阅读竖线", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable
fun chatBatch2ExpandedThinkingDarkPreview() = PipelinePreviewFrame {
    ThinkingTrace(
        reasoning = "### 核对方案\n\n先确认 **事实和限制**，再比较资源投入与实施成本。\n\n- 核对输入来源。\n- 保留风险边界。\n\n```kotlin\nval verified = true\n```\n\n核对结果满足约束，可以组织建议。",
        isGenerating = false, fontSize = 15, elapsedMs = 6_000, initiallyExpanded = true,
    )
}

@PreviewTest
@Preview(name = "步骤行 130%字号", widthDp = 411, heightDp = 320, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun chatBatch2StepsLargeFontDarkPreview() = PipelinePreviewFrame {
    ThinkingTrace("正在比较方案的执行条件与资源使用情况。", true, 15, elapsedMs = 6_000)
    InlineToolRow(previewToolSteps("ssh_exec", "read_file", "list_files"), false, 15)
    InlineToolRow(previewToolSteps("ssh_exec", "exec_js", failedIndex = 1), false, 15)
}

@PreviewTest
@Preview(name = "展开工具标识 浅色对比度", widthDp = 411, heightDp = 220, locale = "zh-rCN")
@Composable
fun chatBatch2ToolIdLightContrastPreview() = PipelinePreviewFrame(dark = false) {
    InlineToolRow(previewToolSteps("read_file"), false, 15, initiallyExpanded = true)
}

@Composable
private fun PipelinePreviewFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(0xFF365A69.toInt()) }) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                content()
            }
        }
    }
    }
}

private fun previewToolSteps(vararg names: String, failedIndex: Int = -1): List<ExecutionStep> = names.flatMapIndexed { index, name ->
    val time = 1_000L + index * 1_200L
    listOf(
        ExecutionStep("call-$index", "tool_call", name, "{}", "call-$index", timestamp = time),
        ExecutionStep("result-$index", if (index == failedIndex) "error" else "tool_result", name, toolCallId = "call-$index",
            content = if (index == failedIndex) "执行失败" else "执行完成", timestamp = time + 1_100),
    )
}

@PreviewTest
@Preview(name = "Step failure English 360dp font130", widthDp = 360, heightDp = 120, locale = "en", fontScale = 1.3f)
@Composable
fun chatBatch2FailureNarrowEnglishPreview() = PipelinePreviewFrame {
    InlineToolRow(previewToolSteps("ssh_exec", "read_file", failedIndex = 1), false, 15)
}

private val CommandResultSteps = listOf(
    ExecutionStep("call-cmd", "tool_call", "shell_exec", """{"command":"which node npm; apk info nodejs | head -3","description":"检查 Node.js 是否已安装"}""", "call-cmd", timestamp = 1_000),
    ExecutionStep(
        "result-cmd", "tool_result", "shell_exec", toolCallId = "call-cmd", timestamp = 1_739,
        content = """{"error_code":null,"guidance":null,"exit_code":1,"timed_out":false,"stderr":"","stdout":"--- apk installed check ---\nnodejs-24.22.1-r1 description:\nJavaScript runtime built on V8 engine - LTS version\n","stdout_original_length":96,"stderr_original_length":0,"length_unit":"UTF-16","stdout_truncated":false,"stderr_truncated":false,"truncated":false,"truncation":"none","runtime":"linux"}""",
    ),
)

@PreviewTest
@Preview(name = "Command result expanded 中文 411dp", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "Command result expanded English 360dp font130", widthDp = 360, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun chatBatch2CommandResultExpandedPreview() = PipelinePreviewFrame {
    InlineToolRow(CommandResultSteps, false, 15, initiallyExpanded = true)
}
