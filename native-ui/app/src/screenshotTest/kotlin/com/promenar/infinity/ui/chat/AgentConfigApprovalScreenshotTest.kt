package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.agentconfig.AgentConfigOp
import com.promenar.infinity.domain.agentconfig.AgentConfigTools
import com.promenar.infinity.ui.chat.diff.AgentConfigImpact
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FieldChange
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.infinityMenuColors
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private fun agentConfigCall(preview: ApprovalDiffPreview.AgentConfig, risk: String) = ApprovalDisplayCall(
    name = AgentConfigTools.WRITE,
    argumentsSummary = "{}",
    riskLabel = risk,
    diffPreview = preview,
    executionTarget = "${AgentConfigTools.TARGET_PREFIX}${preview.op.wireName}",
    oneShotOnly = true,
)

// 修改智能体与会话的审批卡：标题“需要批准：修改智能体提示词”等，正文依次为申请原因、
// 有变化的字段表（旧值删除线 → 新值）、提示词差异与影响说明；分体按钮菜单只有“仅批准这一次”与说明。
@Composable
private fun AgentConfigApprovalPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val risk = approvalRiskLabel("agent_config")
                ApprovalCard(
                    toolName = AgentConfigTools.WRITE,
                    description = "",
                    calls = listOf(
                        agentConfigCall(
                            ApprovalDiffPreview.AgentConfig(
                                op = AgentConfigOp.UPDATE_AGENT,
                                targetName = "写作助手",
                                isCurrentTarget = true,
                                promptDiff = FileDiffModel.fromTexts(
                                    "你是写作助手。\n- 回答尽量详细，列出所有可能。\n- 使用 Markdown。",
                                    "你是写作助手。\n- 先给结论，再补充必要细节。\n- 使用 Markdown。",
                                ),
                                impact = AgentConfigImpact.AgentPrompt("写作助手", 7),
                                reason = "用户说回答太啰嗦，希望以后先给结论。",
                            ),
                            risk,
                        ),
                    ),
                )
                ApprovalCard(
                    toolName = AgentConfigTools.WRITE,
                    description = "",
                    calls = listOf(
                        agentConfigCall(
                            ApprovalDiffPreview.AgentConfig(
                                op = AgentConfigOp.CREATE_AGENT,
                                targetName = "翻译助手",
                                fields = listOf(
                                    FieldChange("name", null, "翻译助手"),
                                    FieldChange("modelId", null, "DeepSeek · deepseek-v4"),
                                    FieldChange("temperature", null, "0.3"),
                                    FieldChange("reasoningEffort", null, "低"),
                                    FieldChange("executionMode", null, "命令需批准"),
                                ),
                                promptDiff = FileDiffModel.fromTexts("", "你是中英互译助手。\n- 保留原文格式。", changeKind = FileChangeKind.CREATED),
                                impact = AgentConfigImpact.NewAgent,
                                reason = "用户需要一个专门做中英互译的智能体。",
                            ),
                            risk,
                        ),
                    ),
                )
                ApprovalCard(
                    toolName = AgentConfigTools.WRITE,
                    description = "",
                    calls = listOf(
                        agentConfigCall(
                            ApprovalDiffPreview.AgentConfig(
                                op = AgentConfigOp.CREATE_SESSION,
                                targetName = "英文周报",
                                ownerAgentName = "写作助手",
                                fields = listOf(FieldChange("title", null, "英文周报")),
                                promptDiff = FileDiffModel.fromTexts("", "Always reply in English.", changeKind = FileChangeKind.CREATED),
                                sessionPrompt = true,
                                impact = AgentConfigImpact.NewSession,
                                reason = "用户要在单独的会话里写英文周报。",
                            ),
                            risk,
                        ),
                    ),
                )
                val menuColors = infinityMenuColors()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = menuColors.container,
                    border = BorderStroke(0.5.dp, menuColors.edge),
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp, max = 300.dp).padding(6.dp)) {
                        ApprovalMoreMenuItems(totalCount = 1, timedGrantLabel = null, onApproveAll = null, onApproveTimed = null, onApproveOnce = {})
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "审批卡 修改智能体 浅中390", widthDp = 390, heightDp = 2000, locale = "zh-rCN")
@Composable
fun agentConfigApprovalLightChinesePreview() = AgentConfigApprovalPage(InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "审批卡 修改智能体 深英360 大字", widthDp = 360, heightDp = 2600, locale = "en", fontScale = 1.3f)
@Composable
fun agentConfigApprovalDarkEnglishLargePreview() = AgentConfigApprovalPage(InfinityThemeMode.DARK)
