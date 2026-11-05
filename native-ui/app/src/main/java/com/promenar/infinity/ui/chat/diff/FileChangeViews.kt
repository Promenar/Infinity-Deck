package com.promenar.infinity.ui.chat.diff

import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.FileChangeHunkOmission
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.ProvideUniformRowHeight
import com.promenar.infinity.ui.common.uniformRowMinHeight
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 工具卡片标题：单个文件时为“文件名 +N −M”，多个文件时为“N 个文件 +X −Y”。
 * 同一文件被多次修改时按一个文件计数，行数逐次累加；没有任何计数时只显示文件部分。
 */
@Composable
internal fun fileChangesTitle(changes: List<FileChangeSummary>): String {
    val paths = changes.map { it.path }.distinct()
    val subject = if (paths.size == 1) {
        paths.single().trimEnd('/').substringAfterLast('/').ifEmpty { paths.single() }
    } else {
        pluralStringResource(R.plurals.file_change_file_count, paths.size, paths.size)
    }
    val (added, removed) = changes.totalCounts()
    val counts = countsLabel(added, removed) ?: return subject
    return stringResource(R.string.file_change_title_with_counts, subject, counts)
}

/** [fileChangesTitle] 的着色版：`+N` 用成功色、`−M` 用错误色，与文件行和差异头部一致。 */
@Composable
internal fun fileChangesStyledTitle(changes: List<FileChangeSummary>): AnnotatedString {
    val title = fileChangesTitle(changes)
    val (added, removed) = changes.totalCounts()
    val counts = countsLabel(added, removed)
    // 标题格式把计数放在末尾；格式不符时保持纯文本。
    if (counts == null || !title.endsWith(counts)) return AnnotatedString(title)
    val addedColor = MaterialTheme.infinityDomainColors.success
    val removedColor = MaterialTheme.colorScheme.error
    val start = title.length - counts.length
    return buildAnnotatedString {
        append(title)
        added?.let { addStyle(SpanStyle(color = addedColor), start, start + "+$it".length) }
        removed?.let { addStyle(SpanStyle(color = removedColor), title.length - "−$it".length, title.length) }
    }
}

/** 逐个文件列出路径与 `+N −M`；整行可点，打开该文件的差异面板。 */
@Composable
internal fun FileChangeList(
    changes: List<FileChangeSummary>,
    onOpen: (FileChangeSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("CHAT_FILE_CHANGES")) {
        // 列表各项等高：任一文件带“移自”第二行时，其余文件行也按双行高排版。
        ProvideUniformRowHeight(twoLine = changes.any { it.fromPath != null }) {
            changes.forEach { change -> FileChangeRow(change, onClick = { onOpen(change) }) }
        }
    }
}

@Composable
internal fun FileChangeRow(change: FileChangeSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val typeLabel = fileChangeTypeLabel(change.change)
    val description = listOfNotNull(
        typeLabel,
        change.fromPath?.let { stringResource(R.string.file_change_moved_path, it, change.path) } ?: change.path,
        countsDescription(change.shownAdded, change.shownRemoved),
        stringResource(R.string.file_change_badge_binary).takeIf { change.binary },
        stringResource(R.string.file_change_badge_large).takeIf { change.large },
    ).joinToString(stringResource(R.string.chat_step_summary_separator))
    val pathStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace)
    val fromStyle = MaterialTheme.typography.labelSmall
    // 双行高 = 上下内边距 6dp×2 + 路径一行 + “移自”一行，默认字号下 12 + 18 + 16 = 46dp；行高随字号实算。
    val twoLineHeight = with(LocalDensity.current) { 12.dp + pathStyle.lineHeight.toDp() + fromStyle.lineHeight.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(uniformRowMinHeight(hasSubtitle = change.fromPath != null, singleLine = 40.dp, twoLine = twoLineHeight))
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.file_change_open_diff_action),
                onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = InfinitySpacing.XSmall, vertical = 6.dp)
            .testTag("CHAT_FILE_CHANGE_ROW"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Icon(
            imageVector = fileChangeIcon(change.change),
            contentDescription = null,
            tint = fileChangeTint(change.change),
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            InfinityIdentifierText(
                text = change.path,
                style = pathStyle,
                color = MaterialTheme.colorScheme.onSurface,
                // 列表各项等高：路径限一行；中间省略，深层路径在大字号下也保留文件名，完整路径见无障碍描述。
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            change.fromPath?.let { from ->
                InfinityIdentifierText(
                    text = stringResource(R.string.file_change_moved_from, from),
                    style = fromStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FileChangeBadges(change)
        FileChangeCounts(change.shownAdded, change.shownRemoved)
    }
}

@Composable
private fun FileChangeBadges(change: FileChangeSummary) {
    val badge = when {
        change.binary -> stringResource(R.string.file_change_badge_binary)
        change.large -> stringResource(R.string.file_change_badge_large)
        else -> return
    }
    Text(
        text = badge,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** 新增用成功色、删除用错误色；颜色之外以 `+` / `−` 符号区分。 */
@Composable
internal fun FileChangeCounts(added: Int?, removed: Int?, modifier: Modifier = Modifier) {
    if (added == null && removed == null) return
    val addedColor = MaterialTheme.infinityDomainColors.success
    val removedColor = MaterialTheme.colorScheme.error
    Text(
        text = buildAnnotatedString {
            if (added != null) withStyle(SpanStyle(color = addedColor)) { append("+$added") }
            if (added != null && removed != null) append(" ")
            if (removed != null) withStyle(SpanStyle(color = removedColor)) { append("−$removed") }
        },
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
private fun countsDescription(added: Int?, removed: Int?): String? {
    val parts = listOfNotNull(
        added?.let { pluralStringResource(R.plurals.file_diff_added_lines_a11y, it, it) },
        removed?.let { pluralStringResource(R.plurals.file_diff_removed_lines_a11y, it, it) },
    )
    return when (parts.size) {
        0 -> null
        1 -> parts.single()
        else -> stringResource(R.string.file_diff_counts_a11y, parts[0], parts[1])
    }
}

@Composable
internal fun fileChangeTypeLabel(type: FileChangeType): String = stringResource(
    when (type) {
        FileChangeType.CREATED -> R.string.file_change_type_created
        FileChangeType.MODIFIED -> R.string.file_change_type_modified
        FileChangeType.DELETED -> R.string.file_change_type_deleted
        FileChangeType.MOVED -> R.string.file_change_type_moved
    },
)

private fun fileChangeIcon(type: FileChangeType): ImageVector = when (type) {
    FileChangeType.CREATED -> Icons.Rounded.Add
    FileChangeType.MODIFIED -> Icons.Rounded.Edit
    FileChangeType.DELETED -> Icons.Rounded.DeleteOutline
    FileChangeType.MOVED -> Icons.AutoMirrored.Rounded.DriveFileMove
}

@Composable
private fun fileChangeTint(type: FileChangeType): Color = when (type) {
    FileChangeType.CREATED -> MaterialTheme.infinityDomainColors.success
    FileChangeType.DELETED -> MaterialTheme.colorScheme.error
    FileChangeType.MODIFIED, FileChangeType.MOVED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 底部面板中的单文件差异。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileChangeDiffSheet(change: FileChangeSummary, onDismissRequest: () -> Unit) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // 面板占满屏幕，差异视口扣除拖动条、标题与路径后的高度；窗口尺寸尚未就绪（为 0）时仍保留可用的最小视口。
    val viewportHeight = (windowHeight * 0.7f).coerceAtLeast(240.dp)
    InfinityModalSheet(onDismissRequest = onDismissRequest, height = InfinitySheetHeight.Full) {
        FileChangeDiffSheetContent(change, maxViewportHeight = viewportHeight, modifier = Modifier.navigationBarsPadding())
    }
}

/**
 * 面板内容：类型与路径，随后是已存 hunk 渲染的差异视图；没有 hunk 时只显示计数与原因。
 * 差异视图自带限高视口，[maxViewportHeight] 必须是有限值。
 */
@Composable
internal fun FileChangeDiffSheetContent(
    change: FileChangeSummary,
    maxViewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val model = remember(change) { change.toDiffModel() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, bottom = InfinitySpacing.Large)
            .testTag("CHAT_FILE_CHANGE_SHEET"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Icon(
                    imageVector = fileChangeIcon(change.change),
                    contentDescription = null,
                    tint = fileChangeTint(change.change),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = fileChangeTypeLabel(change.change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model == null) FileChangeCounts(change.shownAdded, change.shownRemoved)
                FileChangeBadges(change)
            }
            InfinityIdentifierText(
                text = change.path,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            change.fromPath?.let { from ->
                InfinityIdentifierText(
                    text = stringResource(R.string.file_change_moved_from, from),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (model != null) {
            FileDiffView(model = model, maxViewportHeight = maxViewportHeight)
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    text = stringResource(omissionReason(change)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = maxViewportHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(InfinitySpacing.Large),
                )
            }
        }
    }
}

private fun omissionReason(change: FileChangeSummary): Int = when {
    change.binary -> R.string.file_change_reason_binary
    change.directory -> R.string.file_change_reason_directory
    else -> when (change.hunksOmitted) {
        FileChangeHunkOmission.PERMANENT_DELETE -> R.string.permanent_delete_diff_omitted
        FileChangeHunkOmission.BINARY -> R.string.file_change_reason_binary
        FileChangeHunkOmission.LARGE -> R.string.file_change_reason_large
        FileChangeHunkOmission.TOO_COMPLEX -> R.string.file_change_reason_too_complex
        FileChangeHunkOmission.SIZE_LIMIT -> R.string.file_change_reason_size_limit
        FileChangeHunkOmission.UNAVAILABLE -> R.string.file_change_reason_unavailable
        FileChangeHunkOmission.DIRECTORY -> R.string.file_change_reason_directory
        FileChangeHunkOmission.UNCHANGED -> R.string.file_change_reason_unchanged
        null -> if (change.large) R.string.file_change_reason_large else R.string.file_change_reason_missing
    }
}

/** 内置命令在会话私有工作区的改动清单：只列路径与类型，不可点开差异。 */
@Composable
internal fun WorkspaceChangesList(changes: WorkspaceChangeList, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("CHAT_WORKSPACE_CHANGES"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.file_change_workspace_changes_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        changes.entries.forEach { entry ->
            val path = entry.fromPath?.let { stringResource(R.string.file_change_moved_path, it, entry.path) } ?: entry.path
            val typeLabel = fileChangeTypeLabel(entry.change)
            val description = stringResource(R.string.file_change_row_a11y, typeLabel, path)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(horizontal = InfinitySpacing.XSmall, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Icon(
                    imageVector = fileChangeIcon(entry.change),
                    contentDescription = null,
                    tint = fileChangeTint(entry.change),
                    modifier = Modifier.size(16.dp),
                )
                InfinityIdentifierText(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (changes.truncated) {
            Text(
                text = stringResource(R.string.file_change_workspace_changes_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.XSmall),
            )
        }
    }
}
