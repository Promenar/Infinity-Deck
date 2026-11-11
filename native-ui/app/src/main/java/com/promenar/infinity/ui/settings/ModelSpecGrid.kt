package com.promenar.infinity.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 规格格子的一格。[onClick] 为空时只读。 */
@Immutable
internal data class ModelSpecCell(
    val field: ModelSpecField,
    val label: String,
    val shortValue: String,
    val exactValue: String?,
    val source: ModelSpecSource?,
    val onClick: (() -> Unit)? = null,
    val tag: String? = null,
)

/** 2×2 规格区：每格显示简写值、精确值与数据来源；可编辑格带编辑图标。 */
@Composable
internal fun ModelSpecGrid(cells: List<ModelSpecCell>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        cells.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) InfinityGroupDivider(startInset = 0.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { index, cell ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        )
                    }
                    ModelSpecGridCell(cell, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModelSpecGridCell(cell: ModelSpecCell, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val sourceLabel = cell.source?.let { stringResource(modelSpecSourceLabel(it)) }
    val description = listOfNotNull(cell.label, cell.exactValue ?: cell.shortValue, sourceLabel).joinToString("，")
    val editLabel = stringResource(R.string.model_spec_edit_action, cell.label)
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .then(cell.tag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(
                if (cell.onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = editLabel, onClick = cell.onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cell.label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sourceLabel != null) {
                Spacer(Modifier.width(6.dp))
                ModelSpecSourceChip(sourceLabel, cell.source == ModelSpecSource.Manual)
            }
            Spacer(Modifier.weight(1f))
            if (cell.onClick != null) {
                Icon(Icons.Rounded.Edit, contentDescription = null, Modifier.size(14.dp), scheme.onSurfaceVariant)
            }
        }
        Text(
            cell.shortValue,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            maxLines = 1,
        )
        cell.exactValue?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModelSpecSourceChip(label: String, manual: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
        color = if (manual) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(
                if (manual) scheme.tertiaryContainer else scheme.surfaceContainerHighest,
                RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

internal fun modelSpecSourceLabel(source: ModelSpecSource): Int = when (source) {
    ModelSpecSource.Catalog -> R.string.model_spec_source_catalog
    ModelSpecSource.Provider -> R.string.model_spec_source_provider
    ModelSpecSource.Manual -> R.string.model_spec_source_manual
    ModelSpecSource.Inferred -> R.string.model_spec_source_inferred
}

/**
 * token 上限编辑面板：一个数字输入行加一条快捷值分段；输入即生效（编辑页即改即存），“完成”只关闭面板。
 */
@Composable
internal fun ModelTokenLimitSheet(
    show: Boolean,
    title: String,
    value: Int,
    quickValues: List<Int>,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    fieldTag: String,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss, title = title) {
        var text by remember(show) { mutableStateOf(if (value > 0) value.toString() else "") }
        Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large)) {
            InfinityFieldRow(
                label = title,
                value = text,
                onValueChange = { next ->
                    if (next.length <= 9 && next.all(Char::isDigit)) {
                        text = next
                        next.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
                    }
                },
                monospace = true,
                help = text.toIntOrNull()?.takeIf { it > 0 }?.let { formatTokenShort(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                fieldModifier = Modifier.testTag(fieldTag),
            )
            InfinitySegmentedControl(
                segments = quickValues.map { InfinitySegment(it, formatTokenShort(it)) },
                selected = text.toIntOrNull(),
                onSelect = {
                    text = it.toString()
                    onValueChange(it)
                },
                fillWidth = true,
                contentPadding = PaddingValues(horizontal = InfinitySpacing.RowHorizontal),
                modifier = Modifier.padding(top = InfinitySpacing.Small),
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.RowHorizontal)
                    .padding(top = InfinitySpacing.Large)
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget),
            ) {
                Text(stringResource(R.string.model_spec_done))
            }
        }
    }
}

/** 上下文与输出上限的快捷值。 */
internal val ContextQuickValues = listOf(32_768, 131_072, 200_000, 1_048_576)
internal val OutputQuickValues = listOf(4_096, 8_192, 32_768, 65_536)
