package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.domain.tool.CommandToolClassification
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.RunDockScope
import com.promenar.infinity.ui.chat.components.RunDockScopeMode
import com.promenar.infinity.ui.chat.components.runDockElapsedText
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FieldChange
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityElevation
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.forReading

/*
 * BUG 3 隔离高保真原型（v1）：多卡审批队列聚焦错位 + 运行坞“等待审批 0/3”歧义修复。
 * 仅位于 debug 源集供 Android Compose Preview 验收，确认前不触及 src/main/ 正式代码。
 */

@Composable
private fun PrototypeApprovalQueueCard(
    toolName: String,
    description: String,
    calls: List<ApprovalDisplayCall>,
    modifier: Modifier = Modifier,
    initialQueueExpanded: Boolean = false,
) {
    val currentCall = calls.firstOrNull()
    val queuedCalls = calls.drop(1)
    val remainingCount = queuedCalls.size
    var queueExpanded by remember(calls.size, initialQueueExpanded) { mutableStateOf(initialQueueExpanded) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = InfinitySpacing.Small),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = InfinityElevation.Level0,
    ) {
        Column {
            ApprovalKindBand(
                toolName = currentCall?.name ?: toolName,
                where = currentCall?.let { prototypeCallWhere(it) },
                riskLabel = currentCall?.riskLabel.orEmpty(),
                executionTarget = currentCall?.executionTarget,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (currentCall != null) {
                    PrototypeApprovalCallBody(currentCall, description, pending = true)
                }

                // 关键修复 1：批准/拒绝操作栏紧贴当前待批项正下方，队列预览绝不再插在当前项与操作按钮之间。
                ApprovalActionBar(
                    remainingCount = remainingCount,
                    totalCount = calls.size.coerceAtLeast(1),
                    enabled = true,
                    approveA11y = "",
                    declineA11y = "",
                    onApprove = {},
                    onDecline = {},
                    onApproveAll = if (remainingCount > 0) ({}) else null,
                    timedGrantLabel = currentCall?.timedGrantLabel,
                    onApproveTimed = if (currentCall?.timedGrantLabel != null) ({}) else null,
                    oneShotOnly = currentCall?.oneShotOnly == true,
                )

                if (queuedCalls.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    PrototypeQueuedCallsSection(
                        queuedCalls = queuedCalls,
                        expanded = queueExpanded,
                        onToggle = { queueExpanded = !queueExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeQueuedCallsSection(
    queuedCalls: List<ApprovalDisplayCall>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chinese = LocalConfiguration.current.locales[0]?.language == "zh"
    val toggleLabel = if (chinese) {
        if (expanded) "收起详情" else "展开详情"
    } else {
        if (expanded) "Hide" else "Details"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_approval_remaining, queuedCalls.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = toggleLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            if (!expanded) {
                queuedCalls.forEachIndexed { index, call ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    PrototypeQueuedSummaryRow(
                        queuePosition = index + 2,
                        call = call,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    queuedCalls.forEach { call ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
                        ) {
                            ApprovalKindBand(
                                toolName = call.name,
                                where = prototypeCallWhere(call),
                                riskLabel = call.riskLabel,
                                executionTarget = call.executionTarget,
                            )
                            Box(Modifier.padding(12.dp)) {
                                PrototypeApprovalCallBody(call, "", pending = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrototypeQueuedSummaryRow(
    queuePosition: Int,
    call: ApprovalDisplayCall,
) {
    val where = prototypeCallWhere(call)
    val template = call.diffPreview as? ApprovalDiffPreview.ComfyTemplate
    val title = template?.templateName ?: approvalToolTitle(call.name)
    val subtitle = buildList {
        if (!where.isNullOrBlank() && where != title) add(where)
        add(call.name)
    }.distinct().joinToString(" $InfinityMetaSeparator ") // wrap-check: allow 队列紧凑单行省略摘要，不挤占右侧风险标签
    val showHighRiskIcon = call.highRisk ||
        call.preview?.action == ApprovalPreview.Action.DELETE ||
        call.preview?.scriptAllowsNetwork == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = "#$queuePosition",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showHighRiskIcon) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (call.riskLabel.isNotBlank()) {
                    ApprovalTag(call.riskLabel)
                }
            }
        }
    }
}

@Composable
private fun PrototypeApprovalCallBody(call: ApprovalDisplayCall, description: String, pending: Boolean) {
    (call.diffPreview as? ApprovalDiffPreview.ComfyTemplate)?.let { template ->
        ComfyTemplateApprovalBody(template, call.name, pending)
        return
    }
    val target = call.executionTarget?.let(ToolExecutionTargets::display)
        ?: call.preview?.target?.takeIf(String::isNotBlank)
    val preview = call.preview
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ApprovalCallTitle(call.name, pending)
        if (preview == null) {
            ApprovalTextBlock(text = call.argumentsSummary)
        } else {
            val detailText = preview.detail.orEmpty()
            val cleanBody = if (preview.action == ApprovalPreview.Action.DELETE) "" else detailText
            val heading = when (preview.action) {
                ApprovalPreview.Action.DELETE -> stringResource(R.string.permanent_delete_approval_preview)
                ApprovalPreview.Action.SCRIPT -> stringResource(R.string.chat_approval_preview_script)
                else -> call.name
            }
            if (preview.action != ApprovalPreview.Action.SCRIPT || cleanBody.isBlank()) {
                val targetLine = if (!target.isNullOrBlank()) "$heading  $target" else heading
                Text(
                    text = targetLine,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (cleanBody.isNotBlank()) ApprovalTextBlock(cleanBody)
        }
        ApprovalKindDetail(call.name, call.executionTarget)
        val command = CommandToolClassification.backendFor(call.name) != null
        val riskFacts = buildList {
            if (preview?.action == ApprovalPreview.Action.DELETE && !preview.detail.isNullOrBlank()) {
                add(preview.detail)
            } else if (call.highRisk && !target.isNullOrBlank() && !command) {
                add(stringResource(R.string.chat_approval_high_risk_target_warning))
            }
            if (preview?.scriptAllowsNetwork == true) add(stringResource(R.string.agent_panels_network_allowed))
        }
        approvalRiskLines(description, riskFacts).forEach { ApprovalRiskLine(it) }
    }
}

@Composable
private fun prototypeCallWhere(call: ApprovalDisplayCall): String? {
    val template = call.diffPreview as? ApprovalDiffPreview.ComfyTemplate
    if (template != null) return template.hostName
    val target = call.executionTarget?.let(ToolExecutionTargets::display)
        ?: call.preview?.target?.takeIf(String::isNotBlank)
    return approvalHeaderTarget(call.name, target)
}

/**
 * 关键修复 2：RunDock 在 WaitingApproval 状态下显示清晰的“%d 项待批准 / %d to approve”，
 * 并剥离裸计划进度（如 0/3），只保留本轮用时（0:42），消除“等待审批 0/3”的进度卡死错觉。
 */
@Composable
private fun PrototypeRunDockApprovalRow(
    pendingCount: Int,
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val chinese = LocalConfiguration.current.locales[0]?.language == "zh"
    val label = if (chinese) {
        pluralStringResource(R.plurals.run_dock_pending_approvals, pendingCount, pendingCount)
    } else {
        "$pendingCount to approve"
    }
    val meta = runDockElapsedText(elapsedSeconds)
    val fontScale = LocalDensity.current.fontScale
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelReserve = minOf(150.dp * fontScale, maxWidth * 0.42f) + ComposerTextInset
            val scopeWidth = (maxWidth - labelReserve - 10.dp - 6.dp)
                .coerceIn(ScopeScreenSpec.MinWidthDp.dp, ScopeScreenSpec.MaxWidthDp.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .padding(start = ComposerTextInset, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val metaStyle = MaterialTheme.typography.labelMedium
                val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = buildAnnotatedString {
                        append(label)
                        if (meta.isNotBlank()) {
                            append("\u2002")
                            withStyle(SpanStyle(fontSize = metaStyle.fontSize, color = metaColor, fontFeatureSettings = "tnum")) {
                                append(meta)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RunDockScope(RunDockScopeMode.LIVE, Modifier.width(scopeWidth))
            }
        }
    }
}

@Composable
private fun PrototypeSectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun comfyTemplateCall(
    templateName: String,
    reason: String,
    addedNodes: List<String>,
    modelKey: String,
    modelValue: String,
    modes: String,
    riskLabel: String,
) = ApprovalDisplayCall(
    name = "comfy_define_template",
    argumentsSummary = "{}",
    riskLabel = riskLabel,
    oneShotOnly = true,
    diffPreview = ApprovalDiffPreview.ComfyTemplate(
        templateName = templateName,
        hostName = "工作站 4090 (MAIN)",
        isNew = true,
        reason = reason,
        addedNodeTypes = addedNodes,
        removedNodeTypes = emptyList(),
        modelChanges = listOf(FieldChange(modelKey, null, modelValue)),
        capabilityChanges = listOf(FieldChange("modes", null, modes)),
    ),
)

@Composable
private fun sampleComfyBatchCalls(): List<ApprovalDisplayCall> {
    val risk = approvalRiskLabel("agent_config")
    return listOf(
        comfyTemplateCall(
            templateName = "Qwen2.1 文生图",
            reason = "录入 Skill 记录的 Qwen Image 2.1 标准文生图工作流。",
            addedNodes = listOf("UNETLoader", "CLIPTextEncode", "KSampler", "SaveImage"),
            modelKey = "unet",
            modelValue = "qwen_image_2.1_fp8.safetensors",
            modes = "TEXT_TO_IMAGE",
            riskLabel = risk,
        ),
        comfyTemplateCall(
            templateName = "Qwen2.1 编辑 3图参考",
            reason = "支持最多 3 张参考图做一致性角色编辑与局部改妆。",
            addedNodes = listOf("LoadImage", "TextEncodeQwenImageEditPlus", "KSampler"),
            modelKey = "unet",
            modelValue = "qwen_image_2.1_edit_fp8.safetensors",
            modes = "EDIT (max 3 refs)",
            riskLabel = risk,
        ),
        comfyTemplateCall(
            templateName = "Qwen Edit 2511 3图",
            reason = "保留 2511 编辑工作流作为对比版本以备分镜微调。",
            addedNodes = listOf("LoadImage", "QwenImageEdit2511", "SaveImage"),
            modelKey = "unet",
            modelValue = "qwen_image_edit_2511_fp8.safetensors",
            modes = "EDIT (max 3 refs)",
            riskLabel = risk,
        ),
    )
}

@Composable
private fun sampleChinesePlan(currentStepTitle: String) = remember(currentStepTitle) {
    taskPanelUiState(
        listOf(
            TaskStep(id = "s1", title = currentStepTitle, status = "doing", sortOrder = 0),
            TaskStep(id = "s2", title = "校验工作流节点签名", status = "todo", sortOrder = 1),
            TaskStep(id = "s3", title = "实测生成出图与编辑", status = "todo", sortOrder = 2),
        ),
        isGenerating = true,
    )
}

@Preview(name = "多卡审批 3项收起 深中390", widthDp = 390, heightDp = 780, locale = "zh-rCN")
@Composable
fun approvalQueueComfyBatchDarkChinesePreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val batch = sampleComfyBatchCalls()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrototypeSectionCaption("修复后（默认收起队列 · 3 项 ComfyUI 批审批一屏完整可见）")
                    PrototypeApprovalQueueCard(
                        toolName = "comfy_define_template",
                        description = "",
                        calls = batch,
                        initialQueueExpanded = false,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrototypeSectionCaption("底部 RunDock（显示待批准项数 + 用时，不误导拼接 0/3）")
                    PrototypeRunDockApprovalRow(pendingCount = 3, elapsedSeconds = 42L)
                }
            }
        }
    }
}

@Preview(name = "多卡审批 展开与推进 浅中390", widthDp = 390, heightDp = 2250, locale = "zh-rCN")
@Composable
fun approvalQueueExpandedAndAdvancedLightChinesePreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val batch = sampleComfyBatchCalls()
            val plan = sampleChinesePlan("生成模板配置")
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrototypeSectionCaption("A. 点击“展开详情”后：详情置于操作栏下方次级容器内，不遮挡顶部待批焦点")
                PrototypeApprovalQueueCard(
                    toolName = "comfy_define_template",
                    description = "",
                    calls = batch,
                    initialQueueExpanded = true,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                PrototypeSectionCaption("B. 批准第 1 项后推进：第 2 项升为顶部待批焦点 + RunDock 计数同步递减")
                PrototypeApprovalQueueCard(
                    toolName = "comfy_define_template",
                    description = "",
                    calls = batch.drop(1),
                    initialQueueExpanded = false,
                )
                PrototypeRunDockApprovalRow(pendingCount = 2, elapsedSeconds = 44L)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    RunDockRow(
                        state = requireNotNull(runDockState(plan, isGenerating = true, pendingApprovals = 0, runningJobs = 0, enabledSchedules = 0, boardTitle = null, elapsedMillis = null)),
                        onOpen = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "多卡审批 窄屏大字 浅英360", widthDp = 360, heightDp = 960, locale = "en", fontScale = 1.3f)
@Composable
fun approvalQueueNarrowLargeFontEnglishPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val deleteWarning = stringResource(R.string.agent_panels_irreversible_warning)
            val calls = listOf(
                ApprovalDisplayCall(
                    name = "delete_file",
                    argumentsSummary = "{\"path\":\"/scripts/old_submit.py\"}",
                    riskLabel = approvalRiskLabel("delete"),
                    executionTarget = "[\"path\",\"/scripts/old_submit.py\"]",
                    highRisk = true,
                    preview = ApprovalPreview.Preview(
                        action = ApprovalPreview.Action.DELETE,
                        target = "/scripts/old_submit.py",
                        detail = deleteWarning,
                    ),
                ),
                ApprovalDisplayCall(
                    name = "exec_js",
                    argumentsSummary = "{}",
                    riskLabel = approvalRiskLabel("process"),
                    highRisk = true,
                    preview = ApprovalPreview.Preview(
                        action = ApprovalPreview.Action.SCRIPT,
                        target = "",
                        detail = "fetch('http://100.106.180.58:8188/queue')",
                        scriptAllowsNetwork = true,
                    ),
                ),
                ApprovalDisplayCall(
                    name = "write_file",
                    argumentsSummary = "{\"path\":\"/notes/report.md\"}",
                    riskLabel = "File write",
                    executionTarget = "[\"path\",\"/notes/report.md\"]",
                ),
            )
            val enPlan = remember {
                taskPanelUiState(
                    listOf(
                        TaskStep(id = "a", title = "Inspect scripts", status = "done", sortOrder = 0),
                        TaskStep(id = "b", title = "Find Markdown files", status = "doing", sortOrder = 1),
                        TaskStep(id = "c", title = "Summarize findings", status = "todo", sortOrder = 2),
                    ),
                    isGenerating = true,
                )
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrototypeSectionCaption("Narrow 360dp / 1.3x Font Scale (Action Bar Above Queue)")
                PrototypeApprovalQueueCard(
                    toolName = "delete_file",
                    description = "",
                    calls = calls,
                    initialQueueExpanded = false,
                )
                PrototypeSectionCaption("RunDock Waiting Approval vs Active Step")
                PrototypeRunDockApprovalRow(pendingCount = 2, elapsedSeconds = 42L)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    RunDockRow(
                        state = requireNotNull(runDockState(enPlan, isGenerating = true, pendingApprovals = 0, runningJobs = 0, enabledSchedules = 0, boardTitle = null, elapsedMillis = null)),
                        onOpen = {},
                    )
                }
            }
        }
    }
}
