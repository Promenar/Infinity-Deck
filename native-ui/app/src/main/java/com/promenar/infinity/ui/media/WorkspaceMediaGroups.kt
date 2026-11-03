package com.promenar.infinity.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.ui.common.MarkdownMediaReference
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

/** 矩阵最多显示的格子数；更多的项以“+N”叠加在最后一格。 */
internal const val MEDIA_GALLERY_MAX_CELLS = 4

/** 矩阵最后一格叠加的剩余数量；不超过 [MEDIA_GALLERY_MAX_CELLS] 时为 0。 */
internal fun mediaGalleryOverflow(itemCount: Int): Int = (itemCount - MEDIA_GALLERY_MAX_CELLS).coerceAtLeast(0)

/** 组内按扩展名判为图片的路径，全屏查看器在这些图片之间切换。 */
internal fun mediaGroupImagePaths(items: List<MarkdownMediaReference>): List<String> =
    items.filter { mediaKindForFileName(mediaDisplayName(it.path)) == MediaKind.IMAGE }.map { it.path }

/** 第 [itemIndex] 项在 [mediaGroupImagePaths] 中的下标；该项不是图片时返回 -1。 */
internal fun mediaGroupImageViewerStart(items: List<MarkdownMediaReference>, itemIndex: Int): Int {
    if (itemIndex !in items.indices) return -1
    if (mediaKindForFileName(mediaDisplayName(items[itemIndex].path)) != MediaKind.IMAGE) return -1
    return items.take(itemIndex).count { mediaKindForFileName(mediaDisplayName(it.path)) == MediaKind.IMAGE }
}

private val GalleryCellShape = RoundedCornerShape(8.dp)
private val CarouselFrameShape = RoundedCornerShape(12.dp)
private val GallerySpacing = 4.dp
private val GroupMaxWidth = 560.dp

/**
 * ```gallery 矩阵：2 项并排等宽；3 项左侧大图跨两行、右侧上下两张；4 项 2×2；
 * 5 项及以上显示前 4 格，第 4 格叠加“+N”。点击图片格打开组内全屏查看器，视频与其它类别走各自的查看路径。
 */
@Composable
internal fun WorkspaceMediaGallery(items: List<MarkdownMediaReference>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    val overflow = mediaGalleryOverflow(items.size)
    val openImage: (Int) -> Unit = { index -> mediaGroupImageViewerStart(items, index).takeIf { it >= 0 }?.let { viewerStart = it } }
    val base = modifier.widthIn(max = GroupMaxWidth).fillMaxWidth()
    val spacing = Arrangement.spacedBy(GallerySpacing)
    when (minOf(items.size, MEDIA_GALLERY_MAX_CELLS)) {
        1 -> GalleryCell(items, 0, 0, openImage, base.aspectRatio(4f / 3f))
        2 -> Row(base.aspectRatio(2f), horizontalArrangement = spacing) {
            GalleryCell(items, 0, 0, openImage, Modifier.weight(1f).fillMaxHeight())
            GalleryCell(items, 1, 0, openImage, Modifier.weight(1f).fillMaxHeight())
        }
        3 -> Row(base.aspectRatio(3f / 2f), horizontalArrangement = spacing) {
            GalleryCell(items, 0, 0, openImage, Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = spacing) {
                GalleryCell(items, 1, 0, openImage, Modifier.weight(1f).fillMaxWidth())
                GalleryCell(items, 2, 0, openImage, Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(base.aspectRatio(1f), verticalArrangement = spacing) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = spacing) {
                GalleryCell(items, 0, 0, openImage, Modifier.weight(1f).fillMaxHeight())
                GalleryCell(items, 1, 0, openImage, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = spacing) {
                GalleryCell(items, 2, 0, openImage, Modifier.weight(1f).fillMaxHeight())
                GalleryCell(items, 3, overflow, openImage, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    viewerStart?.let { start ->
        WorkspaceImageGroupViewer(paths = mediaGroupImagePaths(items), startIndex = start, onDismiss = { viewerStart = null })
    }
}

@Composable
private fun GalleryCell(
    items: List<MarkdownMediaReference>,
    index: Int,
    overflow: Int,
    onOpenImage: (Int) -> Unit,
    modifier: Modifier,
) {
    MediaGroupCell(
        reference = items[index],
        contentScale = ContentScale.Crop,
        onOpenImage = { onOpenImage(index) },
        modifier = modifier,
        shape = GalleryCellShape,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        showPlayBadge = overflow <= 0,
    ) {
        if (overflow > 0) {
            Box(
                modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.media_gallery_more, overflow),
                    style = InfinityTypography.titleLarge,
                    color = MaterialTheme.infinityDomainColors.overlayContent,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * ```carousel 内嵌查看器：固定 4:3 画框，左右滑动或点两侧按钮切换，右下角页码，画框下方单行说明。
 * 点击图片打开组内全屏查看器并定位到当前项。
 */
@Composable
internal fun WorkspaceMediaCarousel(items: List<MarkdownMediaReference>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    // 流式生成中项数增长：页数读取最新列表，已翻到的页不回跳。
    val currentItems by rememberUpdatedState(items)
    val pagerState = rememberPagerState(pageCount = { currentItems.size })
    val scope = rememberCoroutineScope()
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    val page = pagerState.currentPage.coerceIn(0, items.lastIndex)
    Column(
        modifier = modifier.widthIn(max = GroupMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(CarouselFrameShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.matchParentSize()) { index ->
                val reference = currentItems.getOrNull(index) ?: return@HorizontalPager
                MediaGroupCell(
                    reference = reference,
                    contentScale = ContentScale.Fit,
                    onOpenImage = {
                        mediaGroupImageViewerStart(currentItems, index).takeIf { it >= 0 }?.let { viewerStart = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    background = Color.Transparent,
                )
            }
            MediaPagerControls(
                index = page,
                count = items.size,
                onPrevious = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                counterAlignment = Alignment.BottomEnd,
            )
        }
        Text(
            text = items[page].label ?: mediaDisplayName(items[page].path),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    viewerStart?.let { start ->
        WorkspaceImageGroupViewer(paths = mediaGroupImagePaths(items), startIndex = start, onDismiss = { viewerStart = null })
    }
}

/**
 * 画框两侧的上一项/下一项圆形半透明按钮（首末项隐藏对应按钮）与页码角标；
 * 内嵌查看器与全屏查看器共用。
 */
@Composable
internal fun BoxScope.MediaPagerControls(
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    counterAlignment: Alignment,
    modifier: Modifier = Modifier,
) {
    if (count < 2) return
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
        contentColor = MaterialTheme.infinityDomainColors.overlayContent,
    )
    if (index > 0) {
        FilledTonalIconButton(
            onClick = onPrevious,
            modifier = modifier.align(Alignment.CenterStart).padding(8.dp).size(36.dp),
            shape = CircleShape,
            colors = colors,
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.media_action_previous))
        }
    }
    if (index < count - 1) {
        FilledTonalIconButton(
            onClick = onNext,
            modifier = modifier.align(Alignment.CenterEnd).padding(8.dp).size(36.dp),
            shape = CircleShape,
            colors = colors,
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.media_action_next))
        }
    }
    Text(
        text = stringResource(R.string.media_carousel_page, index + 1, count),
        style = InfinityTypography.labelSmall,
        color = MaterialTheme.infinityDomainColors.overlayContent,
        maxLines = 1,
        modifier = modifier
            .align(counterAlignment)
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 矩阵格或查看器页：解析一次后按类别绘制。图片按 [contentScale] 显示，视频显示首帧并叠加播放图标，
 * 音频与其它文件显示类别图标；点击图片交给 [onOpenImage]，其它类别打开各自的查看器。
 */
@Composable
private fun MediaGroupCell(
    reference: MarkdownMediaReference,
    contentScale: ContentScale,
    onOpenImage: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    background: Color,
    /** 被“+N”覆盖的格子不显示视频播放图标，避免两者叠在一起。 */
    showPlayBadge: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val environment = LocalWorkspaceMedia.current
    val resolved = environment?.let { rememberWorkspaceMediaResolution(it, reference.path) }
    val loading = environment != null && resolved == null
    val available = resolved?.available
    val model = if (environment != null && available != null) {
        WorkspaceMediaModel(environment.sessionId, reference.path, resolved?.identity)
    } else {
        null
    }
    var showViewer by remember(reference.path) { mutableStateOf(false) }
    val name = reference.label ?: mediaDisplayName(reference.path)
    val description = when (available?.ref?.kind) {
        MediaKind.IMAGE -> stringResource(R.string.media_cd_image, name)
        MediaKind.VIDEO -> stringResource(R.string.media_cd_video, name)
        MediaKind.AUDIO -> stringResource(R.string.media_cd_audio, name)
        MediaKind.FILE -> stringResource(R.string.media_cd_file, name)
        null -> stringResource(if (loading) R.string.media_loading else R.string.media_unavailable_title)
    }
    val viewLabel = stringResource(R.string.media_action_view)
    val clickModifier = if (available != null && model != null) {
        Modifier.clickable(role = Role.Image, onClickLabel = viewLabel) {
            if (available.ref.kind == MediaKind.IMAGE) onOpenImage() else showViewer = true
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(clickModifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (available != null && model != null) {
            AvailableCellContent(ref = available.ref, model = model, contentScale = contentScale, showPlayBadge = showPlayBadge)
        } else {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        overlay()
    }
    if (showViewer && available != null && model != null) {
        WorkspaceMediaViewerFor(available = available, model = model, onDismiss = { showViewer = false })
    }
}

@Composable
private fun BoxScope.AvailableCellContent(ref: MediaRef, model: WorkspaceMediaModel, contentScale: ContentScale, showPlayBadge: Boolean) {
    if (ref.kind != MediaKind.IMAGE && ref.kind != MediaKind.VIDEO) {
        Icon(
            imageVector = mediaKindIcon(ref.kind, ref.mimeType),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        return
    }
    val video = ref.kind == MediaKind.VIDEO
    var failed by remember(model) { mutableStateOf(false) }
    if (video) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim))
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = Modifier.matchParentSize(),
        contentScale = contentScale,
        onState = { state -> failed = state is AsyncImagePainter.State.Error },
    )
    if (failed) {
        Icon(
            imageVector = if (video) mediaKindIcon(MediaKind.VIDEO) else Icons.Rounded.BrokenImage,
            contentDescription = null,
            tint = if (video) MaterialTheme.infinityDomainColors.overlayContent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
    }
    if (video && showPlayBadge) {
        // 视频格只显示首帧与播放图标，点击走现有的全屏播放。
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.infinityDomainColors.overlayContent,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
