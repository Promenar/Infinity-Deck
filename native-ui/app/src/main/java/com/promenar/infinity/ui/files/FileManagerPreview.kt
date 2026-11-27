package com.promenar.infinity.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.REMOTE_PREVIEW_CACHE_MAX_BYTES
import com.promenar.infinity.domain.files.FileGraphState
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.domain.storage.RemoteOverwritePolicy
import com.promenar.infinity.domain.storage.RemoteTransferRequest
import com.promenar.infinity.domain.storage.RemoteTransferState
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.library.isActiveTransfer
import com.promenar.infinity.ui.library.transferProgressFraction
import com.promenar.infinity.ui.media.formatMediaDuration
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/*
 * 文件预览：双栏布局的右栏与单列布局推入的预览页共用同一份内容。
 * 头部左侧为标题与元信息、右侧为宿主页头动作与更多菜单；面包屑或元信息下方按需呈现紧凑资料状态条，
 * 正文区直接阅读或针对不可内联文件在占位卡内置入“打开方式”，底部保留完整信息卡。
 */

private val CardShape = RoundedCornerShape(20.dp)
private val KnowledgeStripShape = RoundedCornerShape(14.dp)
private const val ArchiveEntryLimit = 100
private const val REMOTE_AUTO_PREVIEW_THRESHOLD_BYTES = 8L * 1024L * 1024L

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
                    InfinityHeaderTitle(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfinityMetaLine(
                        segments = fileMetaSegments(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewHeaderActions(item, state, actions)
            }
            FilePreviewContent(item, state, actions, showMeta = false, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 单列布局推入的预览页。
 * 内屏宿主（展开 / 还原）左侧仍是展开按钮，标题单行居中，与左列标题同高；页头下方是与列表相同位置的路径条，
 * 末级为本文件，点上级回到对应文件夹；大小与修改时间在“信息”卡里。
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
    val inInspector = com.promenar.infinity.ui.common.LocalInfinityPanePresentation.current ==
        com.promenar.infinity.ui.common.InfinityPanePresentation.Inspector
    Column(modifier.testTag(UiTags.FM_PREVIEW)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (inInspector) Modifier
                        .padding(top = com.promenar.infinity.ui.common.InspectorPanelTopGap)
                        .heightIn(min = com.promenar.infinity.ui.common.InspectorPanelHeaderHeight)
                        .padding(horizontal = InfinitySpacing.XSmall)
                    else Modifier
                        .heightIn(min = 64.dp)
                        .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inner) {
                FileManagerLeadingButton(actions.host.leading)
            } else {
                IconButton(onClick = controller::closePreview, modifier = Modifier.testTag(UiTags.FM_PREVIEW_BACK)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_preview_back))
                }
            }
            if (inner || inInspector) {
                // 内屏右列与会话检视器：统一 16sp 标题，下面是大小与修改时间或所在路径。
                InfinityPaneHeaderTitle(
                    title = item.name,
                    modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.XSmall),
                    meta = {
                        if (inner) InfinityMetaLine(fileMetaSegments(item), maxLines = 1)
                        else Text(locationPathText(state), maxLines = 1, softWrap = false)
                    },
                )
            } else Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
                InfinityHeaderTitle(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                run {
                    InfinityHeaderTitle(
                        text = locationPathText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        alignEnd = true,
                        heading = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            PreviewHeaderActions(item, state, actions)
        }
        if (inner) {
            FileBreadcrumbs(
                crumbs = state.fullCrumbs() + FileCrumb(item.name, item.ref.path),
                onGo = { path ->
                    controller.closePreview()
                    if (path != state.path) controller.goTo(path)
                },
                modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.Small, end = InfinitySpacing.XSmall),
                tagPrefix = UiTags.FM_PREVIEW_CRUMB,
            )
        }
        FilePreviewContent(item, state, actions, showMeta = !inner, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewHeaderActions(item: FileItem, state: FileManagerUiState, actions: FileManagerUiActions) {
    val headerActions = actions.host.actionsFor(listOf(item)).filter { it.headerAction }
    headerActions.forEach { action ->
        IconButton(
            onClick = { action.onInvoke(listOf(item)) },
            modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
        ) {
            Icon(action.icon, contentDescription = action.label)
        }
    }
    PreviewItemMenu(item, state, actions)
}

@Composable
private fun PreviewItemMenu(item: FileItem, state: FileManagerUiState, actions: FileManagerUiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_PREVIEW_MORE)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_item_actions, item.name))
        }
        InfinityDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            minWidth = FileItemMenuWidth,
            maxWidth = FileItemMenuWidth,
        ) {
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
            .padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        if (showMeta) {
            InfinityMetaLine(
                segments = fileMetaSegments(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.knowledge?.let { PreviewKnowledgeStatusStrip(it) }
        PreviewActions(item, actions)
        PreviewBody(item, state, state.preview, state.previewLoading, actions) { actions.openWith(item) }
        if (state.preview !is FilePreview.RemoteDetails) {
            PreviewInfoCard(item, state)
        }
    }
}

/** 紧凑资料状态条：仅呈现索引状态、分块数、图谱与失败原因，不重复路径/大小/修改时间。 */
@Composable
private fun PreviewKnowledgeStatusStrip(knowledge: FileKnowledge, modifier: Modifier = Modifier) {
    val stateColor = knowledgeStateColor(knowledge.state)
    val stateLabel = stringResource(knowledgeStateLabel(knowledge.state))
    val chunksLabel = knowledge.chunkCount?.let { pluralStringResource(R.plurals.fm_knowledge_chunks, it, it) }
    val graphLabel = knowledge.graph?.let { graphStatusLabel(it)?.let { res -> stringResource(res) } }
    val graphExtracted = knowledge.graph == FileGraphState.EXTRACTED
    val container = if (knowledge.state == KnowledgeIndexState.FAILED) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = if (knowledge.state == KnowledgeIndexState.FAILED) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    Surface(
        shape = KnowledgeStripShape,
        color = container,
        modifier = modifier.fillMaxWidth().border(0.5.dp, border, KnowledgeStripShape),
    ) {
        Column(
            Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Box(Modifier.size(8.dp).background(stateColor, CircleShape))
                InfinityMetaLine(
                    segments = listOfNotNull(stateLabel, chunksLabel, graphLabel.takeUnless { graphExtracted }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (graphExtracted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Hub,
                            contentDescription = graphLabel,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(15.dp),
                        )
                        if (graphLabel != null) {
                            Text(
                                text = graphLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                knowledge.collectionTag?.let { FileCollectionTagChip(it) }
            }
            knowledge.failureReason?.let { reason ->
                Text(
                    text = knowledgeFailureText(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 宿主主动作（第一个为主色）；“打开方式”由不可内联占位卡与更多菜单承载，不再占正文顶部空间。 */
@Composable
private fun PreviewActions(item: FileItem, actions: FileManagerUiActions) {
    val hostActions = actions.host.actionsFor(listOf(item)).filter { it.prominent && !it.headerAction }
    if (hostActions.isEmpty()) return
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
    }
}

@Composable
private fun PreviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), content = content)
    }
}

@Composable
private fun PreviewBody(
    item: FileItem,
    state: FileManagerUiState,
    preview: FilePreview?,
    loading: Boolean,
    actions: FileManagerUiActions,
    onOpenWith: () -> Unit,
) {
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
        is FilePreview.Audio -> {
            val available = remember(item.ref.path, preview.uri, preview.durationMs, item.sizeBytes) {
                com.promenar.infinity.domain.media.MediaResolution.Available(
                    ref = com.promenar.infinity.domain.media.MediaRef(
                        path = item.name,
                        mimeType = item.mimeType?.takeIf(String::isNotBlank) ?: "audio/*",
                        kind = com.promenar.infinity.domain.media.MediaKind.AUDIO,
                        sizeBytes = item.sizeBytes ?: 0L,
                        durationMs = preview.durationMs,
                    ),
                    source = com.promenar.infinity.domain.media.MediaSource.ContentUri(preview.uri),
                )
            }
            com.promenar.infinity.ui.media.InlineAudioPlayerCard(
                available = available,
                title = item.name,
                trailingActions = {
                    IconButton(onClick = onOpenWith, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.media_action_open_with),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )
        }
        is FilePreview.Video -> PreviewPlaceholder(
            icon = Icons.Rounded.Movie,
            title = formatMediaDuration(preview.durationMs) ?: stringResource(R.string.fm_preview_video),
            onOpenWith = onOpenWith,
        )
        is FilePreview.Pdf -> PreviewPlaceholder(
            icon = Icons.Rounded.PictureAsPdf,
            title = preview.pageCount?.let { pluralStringResource(R.plurals.fm_pdf_pages, it, it) } ?: stringResource(R.string.fm_preview_pdf),
            onOpenWith = onOpenWith,
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
        is FilePreview.RemoteDetails -> RemoteLargeFileDetailSection(
            item = item,
            details = preview,
            state = state,
            actions = actions,
        )
        FilePreview.Unsupported -> PreviewPlaceholder(
            icon = Icons.Rounded.VisibilityOff,
            title = stringResource(R.string.fm_preview_unsupported),
            onOpenWith = onOpenWith,
        )
    }
}

/** 超过 8 MB 阈值的远端 SFTP 文件轻量详情卡：零自动下载，支持按需缓存预览、下载到本机工作区、服务端 SHA-256 校验与 256 MB 缓存治理。 */
@Composable
private fun RemoteLargeFileDetailSection(
    item: FileItem,
    details: FilePreview.RemoteDetails,
    state: FileManagerUiState,
    actions: FileManagerUiActions,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? InfinityApplication
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val noticeController = LocalInfinityNoticeController.current

    val tasks by remember(app) {
        app?.remoteTransferManager?.tasks ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cacheStats by remember(app) {
        app?.remotePreviewCache?.stats ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)

    val matchingTask = remember(tasks, details.mountId, details.remotePath) {
        tasks.firstOrNull {
            it.mountId == details.mountId &&
                it.remoteFullPath == details.remotePath &&
                (it.state.isActiveTransfer() || it.state == RemoteTransferState.PAUSED)
        }
    }

    var shaState by remember(details.mountId, details.remotePath) { mutableStateOf<RemoteShaCheckState>(RemoteShaCheckState.Idle) }
    var downloadPickerOpen by remember(details.mountId, details.remotePath) { mutableStateOf(false) }

    val cacheQueuedText = stringResource(R.string.sftp_mount_notice_preview_cache_queued)
    val downloadQueuedText = stringResource(R.string.sftp_mount_notice_download_queued)
    val cacheClearedText = stringResource(R.string.sftp_mount_notice_cache_cleared)
    val shaCopiedText = stringResource(R.string.sftp_transfers_sha_copied)
    val cachePreviewLabel = stringResource(R.string.sftp_remote_action_cache_preview)
    val transferFailedLabel = stringResource(R.string.sftp_transfers_state_failed)

    val scopeKey = state.location?.sessionId ?: ExternalMountPaths.LIBRARY_SCOPE

    // 1. 主详情与按需动作卡
    Surface(
        shape = CardShape,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("remote_large_file_card"),
    ) {
        Column(
            modifier = Modifier.padding(InfinitySpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(domain.infoContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileKindIcon(fileKindOf(item)),
                    contentDescription = null,
                    tint = domain.onInfoContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val exceedsAutoPreview = details.sizeBytes > REMOTE_AUTO_PREVIEW_THRESHOLD_BYTES
            val isCachedLocally = details.cachedBytes != null && (cacheStats == null || cacheStats!!.usedBytes > 0L)
            com.promenar.infinity.ui.common.InfinityTagScrollRow(spacing = 8.dp) {
                ProductionStatusChip(formatFileSize(details.sizeBytes), ProductionTone.NEUTRAL)
                ProductionStatusChip(
                    text = stringResource(
                        when {
                            isCachedLocally -> R.string.sftp_remote_cached_badge
                            exceedsAutoPreview -> R.string.sftp_remote_large_badge
                            else -> R.string.sftp_remote_small_badge
                        },
                    ),
                    tone = if (isCachedLocally) ProductionTone.SUCCESS else ProductionTone.INFO,
                )
                ProductionStatusChip(stringResource(R.string.sftp_remote_resumable_badge), ProductionTone.SUCCESS)
            }
            Text(
                text = stringResource(
                    when {
                        isCachedLocally -> R.string.sftp_remote_cached_no_preview_hint
                        exceedsAutoPreview -> R.string.sftp_remote_large_hint
                        else -> R.string.sftp_remote_unsupported_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (matchingTask != null) {
                val progress = transferProgressFraction(matchingTask.bytesTransferred, matchingTask.bytesTotal)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            strokeCap = StrokeCap.Round,
                            drawStopIndicator = {},
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    actions.host.onOpenTransfers?.let { openTransfers ->
                        InfinityPillButton(
                            text = stringResource(R.string.sftp_remote_action_view_transfer),
                            icon = Icons.Rounded.SwapVert,
                            onClick = openTransfers,
                            tone = InfinityPillTone.NEUTRAL,
                            compact = true,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
            InfinityPillRow(reverseWhenStacked = true) {
                InfinityPillButton(
                    text = stringResource(R.string.sftp_remote_action_cache_preview),
                    icon = Icons.Rounded.CloudDownload,
                    enabled = app != null,
                    onClick = {
                        val targetApp = app ?: return@InfinityPillButton
                        scope.launch {
                            runCatching {
                                targetApp.remoteTransferManager.enqueuePreviewCache(
                                    RemoteTransferRequest(
                                        mountId = details.mountId,
                                        hostAlias = details.hostAlias,
                                        mountVirtualPath = details.virtualPath,
                                        remoteFullPath = details.remotePath,
                                        sourceLabel = "${details.hostAlias}:${details.remotePath}",
                                        targetLabel = cachePreviewLabel,
                                        sessionId = state.location?.sessionId,
                                    ),
                                )
                            }.onSuccess {
                                noticeController?.show(InfinityNoticeSeverity.INFO, cacheQueuedText)
                            }.onFailure { failure ->
                                noticeController?.show(
                                    InfinityNoticeSeverity.ERROR,
                                    failure.message ?: transferFailedLabel,
                                )
                            }
                        }
                    },
                    tone = InfinityPillTone.NEUTRAL,
                    modifier = Modifier.testTag("remote_detail_cache_preview"),
                )
                InfinityPillButton(
                    text = stringResource(R.string.sftp_remote_action_download_workspace),
                    icon = Icons.Rounded.Download,
                    enabled = app != null,
                    onClick = { downloadPickerOpen = true },
                    tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.testTag("remote_detail_download_workspace"),
                )
            }
        }
    }

    // 2. 远端属性卡（含按需服务端 SHA-256 校验，零回传流量）
    PreviewCard {
        Text(
            text = stringResource(R.string.sftp_remote_props_title),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        FileInfoDetailRows(
            listOf(
                FileInfoRow(
                    stringResource(R.string.sftp_remote_prop_host),
                    stringResource(R.string.sftp_remote_prop_host_value, details.hostAlias),
                ),
                FileInfoRow(
                    stringResource(R.string.sftp_remote_prop_remote_path),
                    details.remotePath,
                    monospace = true,
                ),
                FileInfoRow(
                    stringResource(R.string.sftp_remote_prop_virtual_path),
                    details.virtualPath,
                    monospace = true,
                ),
                FileInfoRow(
                    stringResource(R.string.fm_info_modified),
                    formatFileTime(details.lastModified),
                ),
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, enabled = app != null && shaState !is RemoteShaCheckState.Checking) {
                    val ready = shaState as? RemoteShaCheckState.Verified
                    if (ready != null) {
                        clipboard.setText(AnnotatedString(ready.sha256))
                        noticeController?.show(InfinityNoticeSeverity.SUCCESS, shaCopiedText)
                    } else {
                        val targetApp = app ?: return@clickable
                        shaState = RemoteShaCheckState.Checking
                        scope.launch {
                            shaState = try {
                                val receipt = targetApp.computeRemoteFileSha256(details.mountId, scopeKey, item.ref.path)
                                RemoteShaCheckState.Verified(receipt.sha256, receipt.verificationMethod)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                RemoteShaCheckState.Failed(error.message)
                            }
                        }
                    }
                }
                .padding(vertical = 4.dp)
                .testTag("remote_detail_sha256_row"),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = stringResource(R.string.sftp_remote_prop_sha256),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
            )
            when (val currentSha = shaState) {
                RemoteShaCheckState.Idle -> Text(
                    text = stringResource(R.string.sftp_remote_sha256_tap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                RemoteShaCheckState.Checking -> Text(
                    text = stringResource(R.string.sftp_remote_sha256_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                is RemoteShaCheckState.Verified -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = domain.success, modifier = Modifier.size(14.dp))
                        Text(
                            com.promenar.infinity.ui.library.formatRemoteChecksumMethod(currentSha.method),
                            style = MaterialTheme.typography.labelSmall,
                            color = domain.success,
                        )
                    }
                    Text(
                        text = currentSha.sha256.breakableIdentifier(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurface,
                    )
                }
                is RemoteShaCheckState.Failed -> Text(
                    text = currentSha.reason ?: stringResource(R.string.sftp_remote_sha256_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 3. 256 MB LRU 远程预览缓存状态条与一键清理
    val usedBytes = cacheStats?.usedBytes ?: 0L
    val maxBytes = (cacheStats?.maxBytes ?: REMOTE_PREVIEW_CACHE_MAX_BYTES).coerceAtLeast(1L)
    val fraction = (usedBytes.toDouble() / maxBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("remote_preview_cache_bar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.sftp_remote_cache_bar_title, formatFileSize(usedBytes), formatFileSize(maxBytes)),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.sftp_remote_cache_clear),
                enabled = app != null && usedBytes > 0L,
                onClick = {
                    val targetApp = app ?: return@InfinityPillButton
                    scope.launch {
                        targetApp.remotePreviewCache.clear()
                        noticeController?.show(InfinityNoticeSeverity.SUCCESS, cacheClearedText)
                    }
                },
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
            )
        }
    }

    if (downloadPickerOpen && app != null) {
        RemoteDownloadDestinationSheet(
            locations = state.locations,
            fileName = item.name,
            onDismiss = { downloadPickerOpen = false },
            onSelectDestination = { targetLocation, targetDir ->
                downloadPickerOpen = false
                val targetFile = File(targetDir, item.name)
                scope.launch {
                    runCatching {
                        app.remoteTransferManager.enqueueDownload(
                            RemoteTransferRequest(
                                mountId = details.mountId,
                                hostAlias = details.hostAlias,
                                mountVirtualPath = details.virtualPath,
                                remoteFullPath = details.remotePath,
                                localPath = targetFile.absolutePath,
                                sourceLabel = "${details.hostAlias}:${details.remotePath}",
                                targetLabel = "${targetLocation.title}/${item.name}",
                                overwritePolicy = RemoteOverwritePolicy.REFUSE_DIFFERING,
                                sessionId = state.location?.sessionId,
                            ),
                        )
                    }.onSuccess {
                        noticeController?.show(InfinityNoticeSeverity.SUCCESS, downloadQueuedText)
                    }.onFailure { failure ->
                        noticeController?.show(
                            InfinityNoticeSeverity.ERROR,
                            failure.message ?: transferFailedLabel,
                        )
                    }
                }
            },
        )
    }
}

private sealed interface RemoteShaCheckState {
    data object Idle : RemoteShaCheckState
    data object Checking : RemoteShaCheckState
    data class Verified(val sha256: String, val method: String) : RemoteShaCheckState
    data class Failed(val reason: String?) : RemoteShaCheckState
}

@Composable
private fun RemoteDownloadDestinationSheet(
    locations: List<FileLocation>,
    fileName: String,
    onDismiss: () -> Unit,
    onSelectDestination: (FileLocation, File) -> Unit,
) {
    val targets = remember(locations) {
        locations.mapNotNull { loc ->
            if (!loc.capabilities.writable) return@mapNotNull null
            if (loc.storageKind == FileLocationStorageKind.SFTP_REMOTE || loc.storageKind == FileLocationStorageKind.BUILTIN) {
                return@mapNotNull null
            }
            val raw = loc.displayPath?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val dir = File(raw)
            if (dir.isDirectory) loc to dir else null
        }
    }
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.sftp_remote_download_pick_title),
        scrollContent = true,
    ) {
        if (targets.isEmpty()) {
            Text(
                text = stringResource(R.string.sftp_remote_download_no_targets),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(InfinitySpacing.Large),
            )
        } else {
            InfinitySettingsGroup {
                targets.forEach { (loc, dir) ->
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Folder,
                        title = loc.title,
                        subtitle = "${loc.displayPath}/$fileName",
                        subtitleIsIdentifier = true,
                        subtitleMaxLines = 2,
                        onClick = { onSelectDestination(loc, dir) },
                        showChevron = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(icon: ImageVector, title: String, onOpenWith: () -> Unit) {
    PreviewCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            InfinityPillButton(
                text = stringResource(R.string.fm_open_with),
                onClick = onOpenWith,
                tone = InfinityPillTone.PRIMARY,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.testTag(UiTags.FM_PREVIEW_OPEN_WITH),
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
