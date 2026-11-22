package com.promenar.infinity.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.settings.PrototypeThemeSurface
import com.promenar.infinity.ui.theme.InfinityElevation
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/*
 * 高保真隔离原型 v1：ComfyUI 双级会话审批卡 —
 * 卡片 1：工作流/提交器管理审批（必经一次性审批）
 * 卡片 2：常规调用仅填内容字段不审批；当本次调用实验性改写「工作流固定字段」时触发一次性 diff 审批（仅本次生效）。
 * 仅位于 debug 源集供 Android Compose Preview 验收。
 */

@Preview(name = "ComfyUI会话双审批卡-浅色412", widthDp = 412, heightDp = 1380, locale = "zh-rCN")
@Preview(name = "ComfyUI会话双审批卡-深色412", widthDp = 412, heightDp = 1380, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun comfyManagementAndOverrideApprovalsPreview() {
    PrototypeThemeSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PrototypeApprovalSectionCaption("审批卡 1 · 新增 / 更新工作流与提交器脚本（管理动作必经审批）")
            WorkflowManagementApprovalCardPrototype()

            PrototypeApprovalSectionCaption("审批卡 2 · 固定字段单次实验覆写（常规填写内容字段不弹窗，改固定基线时触发）")
            FixedFieldExperimentalOverrideCardPrototype()
        }
    }
}

@Composable
private fun PrototypeApprovalSectionCaption(text: String) {
    InfinityScrollingLine(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun WorkflowManagementApprovalCardPrototype() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = InfinityElevation.Level0,
    ) {
        Column {
            ApprovalKindBand(
                toolName = "comfy_save_workflow",
                where = "工作站 4090 (MAIN)",
                riskLabel = "工作流管理",
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "需要批准：注册工作流与提交器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    SelectionContainer {
                        Text(
                            text = "comfy_save_workflow",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AgentConfigReason("从已验证的 MiniMax H3 Skill 导入 submit_h3_motion.py 并注册「MiniMax H3 Motion Context 接续」工作流，支持从任意中间段重跑。".keepCjkTailTogether())
                val rows = listOf(
                    "工作流名称" to AnnotatedString("MiniMax H3 Motion Context 接续"),
                    "所属主机" to AnnotatedString("工作站 4090 (MAIN)"),
                    "绑定提交器" to AnnotatedString("submit_h3_motion.py (SHA a91f3c02)"),
                    "执行模式" to AnnotatedString("有状态链式（first / next · 支持任意中段重试）"),
                    "干跑自检" to AnnotatedString("通过（0 个缺失节点，凭据由本机代理注入）".keepCjkTailTogether()),
                    "内容字段" to AnnotatedString("+ prompt, image_refs, guides, frames (4 项)"),
                    "固定字段" to AnnotatedString("+ family, speed, context_length, width, height, cfg, lora_strength (7 项)"),
                )
                ApprovalDetailTable(rows)
                ApprovalActionBar(
                    remainingCount = 0,
                    totalCount = 1,
                    enabled = true,
                    approveA11y = "批准注册工作流",
                    declineA11y = "拒绝注册工作流",
                    onApprove = {},
                    onDecline = {},
                    onApproveAll = null,
                    oneShotOnly = true,
                )
            }
        }
    }
}

@Composable
private fun FixedFieldExperimentalOverrideCardPrototype() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = InfinityElevation.Level0,
    ) {
        Column {
            ApprovalKindBand(
                toolName = "comfy_run_workflow",
                where = "工作站 4090 (MAIN)",
                riskLabel = "单次实验覆写",
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "需要批准：临时覆写工作流固定字段",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    SelectionContainer {
                        Text(
                            text = "comfy_run_workflow",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = domain.warningContainer,
                    border = BorderStroke(0.6.dp, domain.onWarningContainer.copy(alpha = 0.25f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Rounded.Science, null, tint = domain.onWarningContainer, modifier = Modifier.size(18.dp))
                        Text(
                            text = "本次调用改动了 3 项已锁定的固定基线参数；批准后仅作用于当前第 3 段新分支生成，不会修改工作流保存的基线。".keepCjkTailTogether(),
                            style = MaterialTheme.typography.bodySmall,
                            color = domain.onWarningContainer,
                        )
                    }
                }
                val diffRows = listOf(
                    "目标工作流" to AnnotatedString("MiniMax H3 Motion Context 接续"),
                    "接续来源" to AnnotatedString("S01-04 · 从第 2 段 v1 发起第 3 段 (v3)"),
                    "采样速度 (speed)" to buildDiffText("pika4", "native20"),
                    "引导强度 (cfg)" to buildDiffText("1.0", "3.5"),
                    "采样步数 (steps)" to buildDiffText("4", "20"),
                )
                ApprovalDetailTable(diffRows)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.surfaceContainer,
                    border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tune, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        InfinityScrollingLine(Modifier.weight(1f)) {
                            Text(
                                text = "内容字段已就绪：提示词 + 2 张参考图 + 121 帧",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                ApprovalActionBar(
                    remainingCount = 0,
                    totalCount = 1,
                    enabled = true,
                    approveA11y = "仅批准本次实验覆写",
                    declineA11y = "拒绝实验覆写",
                    onApprove = {},
                    onDecline = {},
                    onApproveAll = null,
                    approveLabel = "仅批准本次运行",
                    oneShotOnly = true,
                )
            }
        }
    }
}

@Composable
private fun buildDiffText(oldVal: String, newVal: String): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        withStyle(SpanStyle(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)) {
            append(oldVal)
        }
        append("  →  ")
        withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)) {
            append(newVal)
        }
    }
}

@Composable
private fun ApprovalDetailTable(rows: List<Pair<String, AnnotatedString>>) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHigh,
    ) {
        Column {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.38f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(0.62f),
                    )
                }
            }
        }
    }
}
