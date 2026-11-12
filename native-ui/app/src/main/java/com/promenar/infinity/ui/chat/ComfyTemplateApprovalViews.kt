package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.FieldChange
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.forReading

/**
 * `comfy_define_template` 审批卡正文：标题写明新建或修改，其下是智能体给出的理由，
 * 以及模板与主机、节点类型的增删、模型文件与能力声明的变化（旧值 → 新值）。
 */
@Composable
internal fun ComfyTemplateApprovalBody(preview: ApprovalDiffPreview.ComfyTemplate, toolName: String, pending: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("CHAT_APPROVAL_COMFY_TEMPLATE")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = stringResource(if (preview.isNew) R.string.comfy_template_approval_new else R.string.comfy_template_approval_edit)
            Text(
                text = if (pending) stringResource(R.string.chat_approval_title_required, title) else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectionContainer {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (preview.reason.isNotBlank()) AgentConfigReason(preview.reason)
        val rows = comfyTemplateRows(preview)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("CHAT_APPROVAL_COMFY_TEMPLATE_FIELDS"),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ComfyTemplateRow(label, value)
                }
            }
        }
    }
}

@Composable
private fun comfyTemplateRows(preview: ApprovalDiffPreview.ComfyTemplate): List<Pair<String, AnnotatedString>> = buildList {
    add(stringResource(R.string.comfy_template_approval_template) to AnnotatedString(preview.templateName))
    add(stringResource(R.string.comfy_template_approval_host) to AnnotatedString(preview.hostName))
    val nodes = listOfNotNull(
        preview.addedNodeTypes.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.comfy_template_approval_added, it.joinToString("、")) },
        preview.removedNodeTypes.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.comfy_template_approval_removed, it.joinToString("、")) },
    )
    if (nodes.isNotEmpty()) add(stringResource(R.string.comfy_template_approval_nodes) to AnnotatedString(nodes.joinToString("\n")))
    preview.modelChanges.forEach { add(stringResource(R.string.comfy_template_approval_model, it.key) to changeText(it)) }
    preview.capabilityChanges.forEach { add(it.key to changeText(it)) }
}

/** “旧值 → 新值”：旧值加删除线；新增只写新值，删除只写带删除线的旧值。 */
@Composable
private fun changeText(change: FieldChange): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        change.oldValue?.let { old ->
            withStyle(SpanStyle(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)) { append(old) }
            if (change.newValue != null) append("  →  ")
        }
        change.newValue?.let { new -> withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) { append(new) } }
    }
}

@Composable
private fun ComfyTemplateRow(label: String, value: AnnotatedString) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.36f))
        Text(value, style = MaterialTheme.typography.bodySmall.forReading(), modifier = Modifier.weight(0.64f))
    }
}
