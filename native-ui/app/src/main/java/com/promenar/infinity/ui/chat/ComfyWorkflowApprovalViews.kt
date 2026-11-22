package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/** ComfyUI 工作流/提交器脚本管理审批正文（必经一次性审批，严格匹配已确认 v3 原型）。 */
@Composable
internal fun ComfyManagementApprovalBody(
    preview: ApprovalDiffPreview.ComfyManagement,
    toolName: String,
    pending: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("CHAT_APPROVAL_COMFY_MANAGEMENT")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                Text(
                    text = if (pending) stringResource(R.string.chat_approval_title_required, preview.title) else preview.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (preview.isDeletion) scheme.error else scheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            SelectionContainer {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (preview.reason.isNotBlank()) AgentConfigReason(preview.reason)
        ComfyApprovalDetailTable(preview.rows.map { it.first to AnnotatedString(it.second) })
    }
}

/** ComfyUI 工作流固定字段（FIXED）单次实验覆写 Diff 审批正文（仅本次生效，不改动保存基线）。 */
@Composable
internal fun ComfyFixedOverrideApprovalBody(
    preview: ApprovalDiffPreview.ComfyFixedOverride,
    toolName: String,
    pending: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val preflight = preview.preflight
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("CHAT_APPROVAL_COMFY_FIXED_OVERRIDE")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = stringResource(R.string.comfy_override_approval_title)
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                Text(
                    text = if (pending) stringResource(R.string.chat_approval_title_required, title) else title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            SelectionContainer {
                Text(
                    text = toolName,
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
                    text = stringResource(R.string.comfy_override_approval_banner, preflight.diffs.size).keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall,
                    color = domain.onWarningContainer,
                )
            }
        }
        if (preflight.reason.isNotBlank()) AgentConfigReason(preflight.reason)
        val rows = buildList {
            add(stringResource(R.string.comfy_override_approval_target_workflow) to AnnotatedString(preflight.workflowName))
            preview.chainContextSummary?.let { chain ->
                add(stringResource(R.string.comfy_override_approval_chain_source) to AnnotatedString(chain))
            }
            preflight.diffs.forEach { diff ->
                add("${diff.label} (${diff.key})" to comfyDiffAnnotatedText(diff.oldValue, diff.newValue))
            }
        }
        ComfyApprovalDetailTable(rows)
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
                        text = preview.contentSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun comfyDiffAnnotatedText(oldVal: String, newVal: String): AnnotatedString {
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
private fun ComfyApprovalDetailTable(rows: List<Pair<String, AnnotatedString>>) {
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
