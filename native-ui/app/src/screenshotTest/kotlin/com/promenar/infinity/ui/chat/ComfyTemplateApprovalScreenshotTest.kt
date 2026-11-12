package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FieldChange
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val TOOL = "comfy_define_template"

private fun templateCall(preview: ApprovalDiffPreview.ComfyTemplate, risk: String) = ApprovalDisplayCall(
    name = TOOL,
    argumentsSummary = "{}",
    riskLabel = risk,
    diffPreview = preview,
    oneShotOnly = true,
)

// 工作流模板审批卡：标题“需要批准：修改工作流模板 / 新建工作流模板”，正文为申请原因、
// 模板与主机、新增与删除的节点类型、模型与能力字段（旧值删除线 → 新值）。
@Composable
private fun ComfyTemplateApprovalPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val risk = approvalRiskLabel("agent_config")
                ApprovalCard(
                    toolName = TOOL,
                    description = "",
                    calls = listOf(templateCall(ApprovalDiffPreview.ComfyTemplate(
                        templateName = "H3 图生视频",
                        hostName = "工作站 4090",
                        isNew = false,
                        reason = "参考位从 3 个增加到 4 个，加入怀表链特写。",
                        addedNodeTypes = listOf("LoadImage", "ImageBatch"),
                        removedNodeTypes = listOf("PreviewImage"),
                        modelChanges = listOf(FieldChange("unet", "h3_i2v_fp16.safetensors", "h3_i2v_fp8.safetensors")),
                        capabilityChanges = listOf(
                            FieldChange("maxImages", "3", "4"),
                            FieldChange("maxDurationSeconds", "10", "15"),
                        ),
                    ), risk)),
                )
                ApprovalCard(
                    toolName = TOOL,
                    description = "",
                    calls = listOf(templateCall(ApprovalDiffPreview.ComfyTemplate(
                        templateName = "Wan 首尾帧",
                        hostName = "工作站 4090",
                        isNew = true,
                        reason = "SC01-03 需要首尾帧衔接。",
                        addedNodeTypes = listOf("WanFirstLastFrameToVideo", "SaveVideo"),
                        removedNodeTypes = emptyList(),
                        modelChanges = listOf(FieldChange("unet", null, "wan2.2_flf2v_14B_fp8.safetensors")),
                        capabilityChanges = listOf(FieldChange("modes", null, "FIRST_LAST")),
                    ), risk)),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "模板审批卡 浅中390", widthDp = 390, heightDp = 1300, locale = "zh-rCN")
@Composable
fun comfyTemplateApprovalLightChinesePreview() = ComfyTemplateApprovalPage(InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "模板审批卡 深英360 大字", widthDp = 360, heightDp = 1700, locale = "en", fontScale = 1.3f)
@Composable
fun comfyTemplateApprovalDarkEnglishLargePreview() = ComfyTemplateApprovalPage(InfinityThemeMode.DARK)
