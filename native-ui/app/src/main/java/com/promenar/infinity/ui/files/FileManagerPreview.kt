package com.promenar.infinity.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.media.formatMediaDuration
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing

/*
 * 文件预览：双栏布局的右栏与单列布局推入的预览页共用同一份内容。
 * 上方是名称、大小 · 时间与动作按钮（宿主主动作在前，“打开方式”在后），中间按类型预览，下方是信息卡。
 */

private val CardShape = RoundedCornerShape(20.dp)
private const val ArchiveEntryLimit = 100

/** 双栏右栏：有预览条目时显示预览，否则显示当前文件夹概要。 */
@Composable
internal fun FilePreviewPane(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier = Modifier) {
    val item = state.previewItem
    Column(modifier.testTag(UiTags.FM_PREVIEW)) {
        if (item == null || item.isDirectory) {
            FolderSummary(state, actions)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = InfinitySpacing.Small)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfinityMetaLine(
                        segments = fileMetaSegments(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewItemMenu(item, state, actions)
            }
            FilePreviewContent(item, state, actions, showMeta = false, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 单列布局推入的预览页。内屏宿主（展开 / 还原）左侧仍是展开按钮，标题下方“‹ 文件夹名”返回列表；
 * 其它宿主左侧是返回键，标题下方显示所在路径。
 */
@Composable
internal fun FileCompactPreviewPage(
    item: FileItem,
    state: FileManagerUiState,
    actions: FileManagerUiActions,
    modifier: Modifier = Modifier,
) {
    val controller = actions.controller
    val inner = actions.host.leading.isInnerScreen
    Column(modifier.testTag(UiTags.FM_PREVIEW)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inner) {
                FileManagerLeadingButton(actions.host.leading)
            } else {
                IconButton(onClick = controller::closePreview, modifier = Modifier.testTag(UiTags.FM_PREVIEW_BACK)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_preview_back))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (inner) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = controller::closePreview)
                            .testTag(UiTags.FM_PREVIEW_BACK_LINK),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = stringResource(R.string.fm_back_to_folder, state.currentFolderTitle()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = locationPathText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PreviewItemMenu(item, state, actions)
        }
        FilePreviewContent(item, state, actions, showMeta = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewItemMenu(item: FileItem, state: FileManagerUiState, actions: FileManagerUiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_PREVIEW_MORE)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_item_actions, item.name))
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileItemMenu(item, state, actions) { open = false }
        }
    }
}

@Composable
private fun FilePreviewContent(
    item: FileItem,
    state: FileManagerUiState,
    actions: FileManagerUiActions,
    showMeta: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.Large)
            .padding(bottom = InfinitySpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        if (showMeta) {
            InfinityMetaLine(
                segments = fileMetaSegments(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PreviewActions(item, actions)
        PreviewBody(item, state.preview, state.previewLoading)
        PreviewInfoCard(item, state)
    }
}

/** 宿主主动作在前（第一个为主色），“打开方式”在后；放不下时整组竖排，按钮文字不折行。 */
@Composable
private fun PreviewActions(item: FileItem, actions: FileManagerUiActions) {
    val hostActions = actions.host.actionsFor(listOf(item)).filter { it.prominent }
    InfinityPillRow(reverseWhenStacked = false) {
        hostActions.forEachIndexed { index, action ->
            InfinityPillButton(
                text = action.label,
                onClick = { action.onInvoke(listOf(item)) },
                tone = if (index == 0) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                icon = action.icon,
                modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
            )
        }
        InfinityPillButton(
            text = stringResource(R.string.fm_open_with),
            onClick = { actions.openWith(item) },
            tone = if (hostActions.isEmpty()) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            modifier = Modifier.testTag(UiTags.FM_PREVIEW_OPEN_WITH),
        )
    }
}

@Composable
private fun PreviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), content = content)
    }
}

@Composable
private fun PreviewBody(item: FileItem, preview: FilePreview?, loading: Boolean) {
    if (loading) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            InfinityLoadingIndicator()
        }
        return
    }
    when (preview) {
        null -> Unit
        is FilePreview.Text -> PreviewCard {
            if (preview.markdown) {
                MarkdownText(markdown = preview.content)
            } else {
                Text(
                    text = preview.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (preview.truncated) {
                Text(
                    text = stringResource(R.string.fm_preview_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is FilePreview.Image -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 420.dp)
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = preview.uri,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is FilePreview.Audio -> PreviewPlaceholder(
            icon = Icons.Rounded.AudioFile,
            title = formatMediaDuration(preview.durationMs) ?: stringResource(R.string.fm_preview_audio),
        )
        is FilePreview.Video -> PreviewPlaceholder(
            icon = Icons.Rounded.Movie,
            title = formatMediaDuration(preview.durationMs) ?: stringResource(R.string.fm_preview_video),
        )
        is FilePreview.Pdf -> PreviewPlaceholder(
            icon = Icons.Rounded.PictureAsPdf,
            title = preview.pageCount?.let { pluralStringResource(R.plurals.fm_pdf_pages, it, it) } ?: stringResource(R.string.fm_preview_pdf),
        )
        is FilePreview.Archive -> PreviewCard {
            Text(
                text = pluralStringResource(R.plurals.fm_archive_entries, preview.totalEntries, preview.totalEntries),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.entries.take(ArchiveEntryLimit).forEach { entry ->
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val hidden = preview.totalEntries - preview.entries.take(ArchiveEntryLimit).size
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.fm_archive_more, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilePreview.Unsupported -> PreviewPlaceholder(
            icon = Icons.Rounded.VisibilityOff,
            title = stringResource(R.string.fm_preview_unsupported),
        )
    }
}

@Composable
private fun PreviewPlaceholder(icon: ImageVector, title: String) {
    PreviewCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 信息卡：位置、大小、修改时间与资料状态。 */
@Composable
private fun PreviewInfoCard(item: FileItem, state: FileManagerUiState) {
    val rows = mutableListOf(
        stringResource(R.string.fm_info_location) to locationPathText(state),
        stringResource(R.string.fm_info_size) to formatFileSize(item.sizeBytes),
        stringResource(R.string.fm_info_modified) to formatFileTime(item.lastModified),
    )
    val knowledge = item.knowledge
    if (knowledge != null) {
        val status = stringResource(knowledgeStateLabel(knowledge.state))
        val chunks = knowledge.chunkCount?.let { pluralStringResource(R.plurals.fm_knowledge_chunks, it, it) }
        rows += stringResource(R.string.fm_info_knowledge) to listOfNotNull(status, chunks, knowledge.failureReason?.let { knowledgeFailureText(it) }).joinToString("\n")
    }
    PreviewCard {
        Text(
            text = stringResource(R.string.fm_info_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FileInfoRows(rows)
    }
}

/** 双栏右栏的文件夹概要：文件夹与文件数、位置、可用空间、最近改动的文件。 */
@Composable
private fun FolderSummary(state: FileManagerUiState, actions: FileManagerUiActions) {
    val folders = state.items.count { it.isDirectory }
    val files = state.items.size - folders
    val recent = remember(state.items) { state.items.filterNot { it.isDirectory }.sortedByDescending { it.lastModified }.take(4) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.Large)
            .padding(bottom = InfinitySpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Column(Modifier.heightIn(min = 56.dp).padding(top = InfinitySpacing.Small), verticalArrangement = Arrangement.Center) {
            Text(
                text = state.currentFolderTitle(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            InfinityMetaLine(
                segments = listOf(
                    pluralStringResource(R.plurals.fm_folder_count, folders, folders),
                    pluralStringResource(R.plurals.fm_file_count, files, files),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rows = mutableListOf(stringResource(R.string.fm_info_location) to locationPathText(state))
        state.location?.freeBytes?.let { rows += stringResource(R.string.fm_info_free) to formatFileSize(it) }
        PreviewCard { FileInfoRows(rows) }
        if (recent.isNotEmpty()) {
            PreviewCard {
                Text(
                    text = stringResource(R.string.fm_recent_changes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recent.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { actions.controller.showPreview(file) }
                            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                            .padding(vertical = InfinitySpacing.XSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileTypeBadge(file, size = 32.dp)
                        Spacer(Modifier.width(InfinitySpacing.Medium))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatFileTime(file.lastModified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
