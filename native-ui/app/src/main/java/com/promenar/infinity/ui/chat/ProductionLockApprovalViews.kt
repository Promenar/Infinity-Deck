package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.ProductionLockFieldDiff
import com.promenar.infinity.data.production.ProductionLockOutcome
import com.promenar.infinity.data.production.ProductionLockedItemChange
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors

/** `production_update` 涉及已锁定制作项时的结构化审批主体：修改理由、逐项字段差异、未锁定附属改动摘要与退回待审提示。 */
@Composable
internal fun ProductionLockApprovalBody(
    preview: ApprovalDiffPreview.ProductionLock,
    toolName: String,
    pending: Boolean,
) {
    val preflight = preview.preflight
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Column(
        modifier = Modifier.testTag("CHAT_APPROVAL_PRODUCTION_LOCK"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ApprovalCallTitle(
            name = toolName,
            pending = pending,
            titleOverride = stringResource(R.string.production_lock_approval_title),
        )
        if (preflight.reason.isNotBlank()) {
            AgentConfigReason(preflight.reason)
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                preflight.lockedItems.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    ProductionLockedItemDiffBlock(item)
                }
            }
        }
        if (preflight.unlockedSummaries.isNotEmpty()) {
            val previewSummaries = preflight.unlockedSummaries.take(3).joinToString("、")
            val summaryText = if (preflight.unlockedSummaries.size == 1) {
                stringResource(R.string.production_lock_unlocked_summary_one, preflight.unlockedSummaries.size, previewSummaries)
            } else {
                stringResource(R.string.production_lock_unlocked_summary_more, preflight.unlockedSummaries.size, previewSummaries)
            }
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = scheme.onSurfaceVariant,
            )
        }
        if (preflight.showsVisualOrScriptRevertWarning) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = domain.warningContainer.copy(alpha = 0.35f),
                border = BorderStroke(0.5.dp, domain.warning.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = domain.warning,
                        modifier = Modifier.padding(top = 1.dp).size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.production_lock_visual_revert_warning),
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductionLockedItemDiffBlock(item: ProductionLockedItemChange) {
    val scheme = MaterialTheme.colorScheme
    val isDelete = item.outcome == ProductionLockOutcome.PERMANENT_REMOVE
    val outcomeLabel = stringResource(
        when (item.outcome) {
            ProductionLockOutcome.KEEP_LOCKED -> R.string.production_lock_outcome_keep_locked
            ProductionLockOutcome.RETURN_TO_REVIEW -> R.string.production_lock_outcome_return_review
            ProductionLockOutcome.PERMANENT_REMOVE -> R.string.production_lock_outcome_permanent_remove
        },
    )
    val outcomeTone = when (item.outcome) {
        ProductionLockOutcome.KEEP_LOCKED -> ProductionTone.SUCCESS
        ProductionLockOutcome.RETURN_TO_REVIEW -> ProductionTone.WARNING
        ProductionLockOutcome.PERMANENT_REMOVE -> ProductionTone.ERROR
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (isDelete) Icons.Rounded.DeleteOutline else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (isDelete) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = item.kindAndId,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
            }
            ProductionStatusChip(outcomeLabel, outcomeTone)
        }
        if (item.title.isNotBlank()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        item.fields.forEach { field ->
            ProductionLockFieldRow(field)
        }
        item.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = if (isDelete) scheme.error else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProductionLockFieldRow(field: ProductionLockFieldDiff) {
    val scheme = MaterialTheme.colorScheme
    val emptyOld = stringResource(R.string.production_lock_diff_old_empty)
    val clearedNew = stringResource(R.string.production_lock_diff_cleared)
    val annotated = buildProductionFieldDiffText(
        oldValue = field.oldValue,
        newValue = field.newValue,
        emptyOldLabel = emptyOld,
        clearedNewLabel = clearedNew,
        oldColor = scheme.onSurfaceVariant,
        newColor = scheme.onSurface,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(0.28f),
        )
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.forReading(),
            modifier = Modifier.weight(0.72f),
        )
    }
}

private fun buildProductionFieldDiffText(
    oldValue: String?,
    newValue: String?,
    emptyOldLabel: String,
    clearedNewLabel: String,
    oldColor: androidx.compose.ui.graphics.Color,
    newColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    val oldText = oldValue?.takeIf(String::isNotBlank) ?: emptyOldLabel
    val newText = newValue?.takeIf(String::isNotBlank) ?: clearedNewLabel
    withStyle(
        SpanStyle(
            color = oldColor,
            textDecoration = if (oldValue != null) TextDecoration.LineThrough else TextDecoration.None,
        ),
    ) {
        append(oldText)
    }
    append("  →  ")
    withStyle(SpanStyle(color = newColor, fontWeight = FontWeight.Medium)) {
        append(newText)
    }
}
