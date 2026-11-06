package com.promenar.infinity.ui.chat.export

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/** 导出面板正在执行的操作。 */
enum class SessionExportAction { SAVE, SHARE }

/** 预计输出的加载状态。 */
sealed interface SessionExportEstimateState {
    data object Loading : SessionExportEstimateState
    data class Ready(val estimate: SessionExportEstimate) : SessionExportEstimateState
    data object Unavailable : SessionExportEstimateState
}

@Immutable
data class SessionExportSheetState(
    val format: SessionExportFormat = SessionExportFormat.MARKDOWN,
    val options: SessionExportOptions = SessionExportOptions(),
    val estimate: SessionExportEstimateState = SessionExportEstimateState.Loading,
    val running: SessionExportAction? = null,
)

object SessionExportTags {
    const val MENU = "session_export_menu"
    const val SHEET = "session_export_sheet"
    const val THINKING = "session_export_thinking"
    const val TOOLS = "session_export_tools"
    const val ATTACHMENTS = "session_export_attachments"
    const val ESTIMATE = "session_export_estimate"
    const val SAVE = "session_export_save"
    const val SHARE = "session_export_share"
    const val CANCEL = "session_export_cancel"
    fun format(format: SessionExportFormat) = "session_export_format_${format.extension}"
}

@StringRes
internal fun SessionExportFormat.labelRes(): Int = when (this) {
    SessionExportFormat.MARKDOWN -> R.string.session_export_format_markdown
    SessionExportFormat.TXT -> R.string.session_export_format_txt
    SessionExportFormat.HTML -> R.string.session_export_format_html
    SessionExportFormat.PDF -> R.string.session_export_format_pdf
    SessionExportFormat.PNG -> R.string.session_export_format_png
}

@StringRes
private fun SessionExportFormat.footerRes(): Int? = when (this) {
    SessionExportFormat.MARKDOWN -> null
    SessionExportFormat.TXT -> R.string.session_export_footer_txt
    SessionExportFormat.HTML -> null
    SessionExportFormat.PDF -> R.string.session_export_footer_pdf
    SessionExportFormat.PNG -> R.string.session_export_footer_png
}

/** 导出面板操作回调。 */
class SessionExportSheetActions(
    val onFormat: (SessionExportFormat) -> Unit,
    val onOptions: (SessionExportOptions) -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onCancel: () -> Unit,
)

/** 会话菜单“导出会话”与不带参数的 `/export` 打开的面板；生成中关闭面板即取消。 */
@Composable
fun SessionExportSheet(
    show: Boolean,
    sessionTitle: String,
    messageCount: Int,
    state: SessionExportSheetState,
    actions: SessionExportSheetActions,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.session_export_title),
        height = InfinitySheetHeight.Content,
        scrollContent = true,
        modifier = Modifier.testTag(SessionExportTags.SHEET),
    ) {
        SessionExportSheetContent(sessionTitle, messageCount, state, actions)
    }
}

@Composable
fun SessionExportSheetContent(
    sessionTitle: String,
    messageCount: Int,
    state: SessionExportSheetState,
    actions: SessionExportSheetActions,
) {
    val busy = state.running != null
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.session_export_subtitle,
                sessionTitle,
                pluralStringResource(R.plurals.session_export_message_count, messageCount, messageCount),
            ),
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = InfinitySpacing.Small),
        )
        SettingsSectionHeader(title = stringResource(R.string.session_export_section_format))
        Spacer(Modifier.height(InfinitySpacing.XSmall))
        NaturalWidthFlowRow(
            spacing = InfinitySpacing.Small,
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            SessionExportFormat.entries.forEach { format ->
                FormatButton(
                    format = format,
                    selected = format == state.format,
                    enabled = !busy,
                    onClick = { actions.onFormat(format) },
                )
            }
        }
        Spacer(Modifier.height(InfinitySpacing.Large))
        SettingsSectionHeader(title = stringResource(R.string.session_export_section_include))
        IncludeToggle(
            title = stringResource(R.string.session_export_thinking),
            description = stringResource(R.string.session_export_thinking_desc),
            icon = Icons.Rounded.Psychology,
            checked = state.options.includeThinking,
            enabled = !busy,
            tag = SessionExportTags.THINKING,
            onChange = { actions.onOptions(state.options.copy(includeThinking = it)) },
        )
        IncludeToggle(
            title = stringResource(R.string.session_export_tools),
            description = stringResource(R.string.session_export_tools_desc),
            icon = Icons.Rounded.Build,
            checked = state.options.includeToolCalls,
            enabled = !busy,
            tag = SessionExportTags.TOOLS,
            onChange = { actions.onOptions(state.options.copy(includeToolCalls = it)) },
        )
        // PDF 与图片固定把图片画进页面，开关只读但保留这一行，切换格式时面板高度不跳。
        val attachmentsConfigurable = state.format.supportsAttachments
        IncludeToggle(
            title = stringResource(R.string.session_export_attachments),
            description = stringResource(
                when (state.format) {
                    SessionExportFormat.HTML -> R.string.session_export_attachments_desc_html
                    SessionExportFormat.PDF, SessionExportFormat.PNG -> R.string.session_export_attachments_desc_snapshot
                    else -> R.string.session_export_attachments_desc_text
                },
            ),
            icon = Icons.Rounded.AttachFile,
            checked = if (attachmentsConfigurable) state.options.includeAttachments else true,
            enabled = !busy && attachmentsConfigurable,
            tag = SessionExportTags.ATTACHMENTS,
            onChange = { actions.onOptions(state.options.copy(includeAttachments = it)) },
        )
        Spacer(Modifier.height(InfinitySpacing.Small))
        EstimateBox(state, onCancel = actions.onCancel)
        Spacer(Modifier.height(InfinitySpacing.Large))
        Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            OutlinedButton(
                onClick = actions.onSave,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SessionExportTags.SAVE),
            ) { Text(stringResource(R.string.session_export_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = actions.onShare,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SessionExportTags.SHARE),
            ) { Text(stringResource(R.string.session_export_share), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        state.format.footerRes()?.let { footer ->
            Text(
                text = stringResource(footer),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = InfinitySpacing.Small),
            )
        }
    }
}

/**
 * 按子项自然宽度排行，放不下时换行；每行剩余宽度平均加到本行各项上，同一行等高。
 * 与 FlowRow 加 weight 不同，子项不会被压到自然宽度以下（等宽分配会让“Markdown”溢出）。
 */
@Composable
private fun NaturalWidthFlowRow(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxWidth) }
        val lines = packNaturalWidthLines(natural, gap, maxWidth)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val positions = IntArray(measurables.size * 2)
        var y = 0
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) y += gap
            val widths = stretchLine(line.map { natural[it] }, gap, maxWidth)
            val height = line.indices.maxOf { measurables[line[it]].maxIntrinsicHeight(widths[it]) }
            var x = 0
            line.forEachIndexed { i, child ->
                placeables[child] = measurables[child].measure(Constraints.fixed(widths[i], height))
                positions[child * 2] = x
                positions[child * 2 + 1] = y
                x += widths[i] + gap
            }
            y += height
        }
        layout(maxWidth, y) {
            placeables.forEachIndexed { i, placeable -> placeable?.place(positions[i * 2], positions[i * 2 + 1]) }
        }
    }
}

/** 按自然宽度贪心分行，返回每行的子项下标；单项超宽时独占一行。 */
internal fun packNaturalWidthLines(widths: List<Int>, gap: Int, maxWidth: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var lineWidth = 0
    widths.forEachIndexed { index, width ->
        val current = lines.lastOrNull()
        if (current == null || lineWidth + gap + width > maxWidth) {
            lines += mutableListOf(index)
            lineWidth = width
        } else {
            current += index
            lineWidth += gap + width
        }
    }
    return lines
}

/** 把一行的剩余宽度平均加到各项上（余数从左起逐项补 1px），各项不小于自然宽度。 */
internal fun stretchLine(widths: List<Int>, gap: Int, maxWidth: Int): List<Int> {
    val extra = (maxWidth - widths.sum() - gap * (widths.size - 1)).coerceAtLeast(0)
    val share = extra / widths.size
    val remainder = extra % widths.size
    return widths.mapIndexed { i, width -> width + share + if (i < remainder) 1 else 0 }
}

@Composable
private fun FormatButton(
    format: SessionExportFormat,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalInfinityHaptics.current
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                haptics.select(selected)
                if (!selected) onClick()
            }
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)
            .testTag(SessionExportTags.format(format)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(format.labelRes()),
            style = InfinityTypography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = ".${format.extension}",
            style = InfinityTypography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun IncludeToggle(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    val haptics = LocalInfinityHaptics.current
    InfinitySettingsRowLayout(
        title = title,
        subtitle = description,
        icon = icon,
        enabled = enabled,
        horizontalPadding = 0.dp,
        subtitleMaxLines = 5,
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) {
                haptics.toggle(it)
                onChange(it)
            }
            .testTag(tag),
    ) {
        // 格式锁定为“始终包含”时，默认禁用色在纯黑底上只剩一个黑点；改用淡化的主色轨道保持“已开启”可辨。
        val colors = MaterialTheme.colorScheme
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                disabledCheckedTrackColor = colors.primary.copy(alpha = 0.32f),
                disabledCheckedThumbColor = colors.onSurface.copy(alpha = 0.6f),
                disabledCheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun EstimateBox(state: SessionExportSheetState, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)
            .testTag(SessionExportTags.ESTIMATE),
    ) {
        if (state.running != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(InfinitySpacing.Small))
                Text(
                    text = stringResource(R.string.session_export_running, stringResource(state.format.labelRes())),
                    style = InfinityTypography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel, modifier = Modifier.testTag(SessionExportTags.CANCEL)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.session_export_cancel), tint = colors.onSurfaceVariant)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.session_export_estimate_title),
                style = InfinityTypography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                modifier = Modifier.padding(top = InfinitySpacing.XSmall),
            )
            Text(
                text = estimateText(state.format, state.estimate),
                style = InfinityTypography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
            )
        }
    }
}

/** “1 个 .md 文件，约 38 KB”“3 张长图，约 4.2 MB”“1 个 .pdf 文件，A4 竖版，约 14 页，约 1.1 MB”。 */
@Composable
private fun estimateText(format: SessionExportFormat, state: SessionExportEstimateState): String {
    val estimate = when (state) {
        SessionExportEstimateState.Loading -> return stringResource(R.string.session_export_estimate_loading)
        SessionExportEstimateState.Unavailable -> return stringResource(R.string.session_export_estimate_unavailable)
        is SessionExportEstimateState.Ready -> state.estimate
    }
    var text = if (format == SessionExportFormat.PNG) {
        pluralStringResource(R.plurals.session_export_estimate_images, estimate.primaryFileCount, estimate.primaryFileCount)
    } else {
        pluralStringResource(R.plurals.session_export_estimate_files, estimate.primaryFileCount, estimate.primaryFileCount, format.extension)
    }
    if (estimate.attachmentArchive) text = stringResource(R.string.session_export_estimate_with_zip, text)
    estimate.approxPages?.takeIf { format == SessionExportFormat.PDF }?.let { pages ->
        text = stringResource(R.string.session_export_estimate_join, text, pluralStringResource(R.plurals.session_export_estimate_pages, pages, pages))
    }
    val size = stringResource(R.string.session_export_estimate_size, Formatter.formatShortFileSize(LocalContext.current, estimate.approxBytes))
    return stringResource(R.string.session_export_estimate_join, text, size)
}
