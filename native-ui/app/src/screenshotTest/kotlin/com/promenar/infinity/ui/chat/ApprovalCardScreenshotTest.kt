package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.subagent.SubagentApproval
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 统一审批卡：顶部类别条（类别、执行位置、风险标签），标题写“需要批准：动作”，
// 命令正文、类别说明与后台运行方式依次排列，拒绝与批准等宽并排。
// 下方是会话流内的子代理审批：分组卡标出子代理与“等你批准”，内层同款审批卡，按钮为“允许”。
@Composable
private fun ApprovalPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ApprovalCard(
                    toolName = "termux_exec",
                    description = "",
                    calls = listOf(
                        ApprovalDisplayCall(
                            name = "termux_exec",
                            argumentsSummary = "{}",
                            riskLabel = approvalRiskLabel("process"),
                            preview = ApprovalPreview.Preview(
                                action = ApprovalPreview.Action.SCRIPT,
                                target = "",
                                detail = "echo \"TERMUX-BG-START \$(date +%H:%M:%S)\"; sleep 6; ls ~/.infinity/jobs | head -5",
                            ),
                            executionTarget = "[\"process\",\"termux\",\"echo TERMUX-BG-START\"]",
                            highRisk = true,
                            background = BackgroundRunPreview(10, BackgroundJobExitAction.NOTIFY, keepAliveAvailable = true),
                        ),
                    ),
                )
                SubagentApprovalGroup(
                    requests = listOf(
                        SubagentApproval(
                            id = "a",
                            sessionId = "s",
                            subagentIndex = 0,
                            subagentPreset = null,
                            subagentDescription = "子代理能否调用 shell 与 python",
                            toolName = "python_exec",
                            risk = ToolRisk.PROCESS,
                            target = null,
                            preview = "import platform\nprint(platform.machine())",
                            requestedAt = 0L,
                        ),
                    ),
                    onResolve = { _, _ -> },
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "审批卡 浅中390", widthDp = 390, heightDp = 1100, locale = "zh-rCN")
@Composable
fun approvalCardLightChinesePreview() = ApprovalPage(InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "审批卡 深中390", widthDp = 390, heightDp = 1100, locale = "zh-rCN")
@Composable
fun approvalCardDarkChinesePreview() = ApprovalPage(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "审批卡 浅英360 大字", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun approvalCardLightEnglishLargePreview() = ApprovalPage(InfinityThemeMode.LIGHT)

// 知识库访问授权：类别条“内置工具 · 访问授权”，标题“需要批准：读取知识库”，
// 正文依次为申请原因（引用样式）、知识库与文档数、挂载路径、只读说明。
@Composable
private fun KnowledgeAccessApprovalPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val riskLabel = approvalRiskLabel("access_grant")
                ApprovalCard(
                    toolName = REQUEST_KNOWLEDGE_ACCESS_TOOL,
                    description = "",
                    calls = listOf(
                        ApprovalDisplayCall(
                            name = REQUEST_KNOWLEDGE_ACCESS_TOOL,
                            argumentsSummary = "{}",
                            riskLabel = riskLabel,
                            knowledgeAccess = KnowledgeAccessApproval(
                                reason = "片段 12 只写了签名配置的位置，需要读部署手册全文，确认 CI 里如何注入签名。",
                                targets = listOf(
                                    com.promenar.infinity.data.knowledge.KnowledgeAccessTarget(
                                        "root", "默认知识库", 128, "knowledge", "/mnt/knowledge",
                                    ),
                                ),
                                allowed = true,
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "审批卡 知识库访问 深中390", widthDp = 390, heightDp = 640, locale = "zh-rCN")
@Preview(name = "审批卡 知识库访问 浅英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun approvalCardKnowledgeAccessPreview() = KnowledgeAccessApprovalPage(InfinityThemeMode.DARK)

// 批量修改技能：批准为分体按钮，主体“批准当前”，右侧相连箭头段打开“全部批准（3 项）”与
// “批准，10 分钟内同类免审批”菜单（下方以静态浮层示意展开状态）；输入栏盾牌在授权期间带主色圆点。
@Composable
private fun TimedGrantApprovalPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val label = timedGrantLabel(com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
                val risk = approvalRiskLabel("file_write")
                fun call(name: String, action: ApprovalPreview.Action, path: String, detail: String) = ApprovalDisplayCall(
                    name = name,
                    argumentsSummary = "{}",
                    riskLabel = risk,
                    preview = ApprovalPreview.Preview(action = action, target = path, detail = detail),
                    executionTarget = "[\"path\",\"$path\"]",
                    timedGrantLabel = label,
                )
                ApprovalCard(
                    toolName = "create_file",
                    description = "",
                    calls = listOf(
                        call("create_file", ApprovalPreview.Action.CREATE_FILE, "/skills/hello-demo/SKILL.md", "---\nname: hello-demo\ndescription: 用户打招呼时用中文问候\n---"),
                        call("create_file", ApprovalPreview.Action.CREATE_FILE, "/skills/hello-demo/agents/openai.yaml", "policy:\n  allow_implicit_invocation: true"),
                    ),
                    onApproveAll = {},
                    onApproveTimed = {},
                )
                val menuColors = com.promenar.infinity.ui.common.infinityMenuColors()
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = menuColors.container,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, menuColors.edge),
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                ) {
                    Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 240.dp, max = 300.dp).padding(6.dp)) {
                        ApprovalMoreMenuItems(totalCount = 2, timedGrantLabel = label, onApproveAll = {}, onApproveTimed = {})
                    }
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExecutionModeButton(com.promenar.infinity.ui.common.ExecutionMode.WORKSPACE, onSelect = {})
                    ExecutionModeButton(
                        com.promenar.infinity.ui.common.ExecutionMode.WORKSPACE,
                        onSelect = {},
                        timedGrants = listOf(TimedGrantDisplay(label, Long.MAX_VALUE)),
                    )
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "审批卡 限时免审批 浅中390", widthDp = 390, heightDp = 1200, locale = "zh-rCN")
@Composable
fun approvalCardTimedGrantLightChinesePreview() = TimedGrantApprovalPage(InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "审批卡 限时免审批 深英360 大字", widthDp = 360, heightDp = 1500, locale = "en", fontScale = 1.3f)
@Composable
fun approvalCardTimedGrantDarkEnglishLargePreview() = TimedGrantApprovalPage(InfinityThemeMode.DARK)
