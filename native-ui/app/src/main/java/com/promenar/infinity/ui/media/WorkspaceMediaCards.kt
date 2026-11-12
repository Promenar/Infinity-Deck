package com.promenar.infinity.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.delay
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton

/** 卡片所处位置：正文中的块级卡片，或工具步骤中沿用缩略图视觉的卡片。 */
enum class MediaCardStyle { MARKDOWN, TOOL }

private val CardShape = RoundedCornerShape(12.dp)
private val ToolThumbnailShape = RoundedCornerShape(8.dp)
private const val DEFAULT_IMAGE_RATIO = 4f / 3f
private const val DEFAULT_VIDEO_RATIO = 16f / 9f

/** 宽高比：已知时按实际比例（限制在 1:2 至 3:1），未知时用默认比例。 */
fun mediaAspectRatio(width: Int?, height: Int?, fallback: Float): Float {
    if (width == null || height == null || width <= 0 || height <= 0) return fallback
    return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 3f)
}

/** `mm:ss` 或 `h:mm:ss`；未知时返回 null。不足 1 秒的非零时长记为 1 秒。 */
fun formatMediaDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs < 0) return null
    val totalSeconds = if (durationMs in 1..999) 1 else durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) else String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
}

fun formatMediaSize(bytes: Long): String = when {
    bytes < 0 -> "--"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** 文件类型短标签：取扩展名大写（如 PDF），没有扩展名时退回 MIME。 */
fun mediaTypeLabel(ref: MediaRef): String? =
    mediaDisplayName(ref.path).substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() && it.length <= 8 }
        ?.uppercase(java.util.Locale.ROOT)
        ?: ref.mimeType.takeIf(String::isNotBlank)

internal fun mediaKindIcon(kind: MediaKind, mimeType: String? = null): ImageVector = when (kind) {
    MediaKind.IMAGE -> Icons.Rounded.Image
    MediaKind.VIDEO -> Icons.Rounded.Movie
    MediaKind.AUDIO -> Icons.Rounded.AudioFile
    MediaKind.FILE -> if (mimeType?.startsWith("text/") == true || mimeType == "application/pdf") {
        Icons.Rounded.Description
    } else {
        Icons.Rounded.InsertDriveFile
    }
}

/** 工作区内路径可定位到资源管理器；`/mnt` 挂载与 `/skills` 由其它面板管理，不提供定位。 */
fun canLocateInExplorer(path: String): Boolean {
    val absolute = if (path.startsWith('/')) path else "/$path"
    return absolute != "/mnt" && !absolute.startsWith("/mnt/") && absolute != "/skills" && !absolute.startsWith("/skills/")
}

@Composable
internal fun MediaCardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        content = content,
    )
}

/**
 * 工作区媒体引用：解析一次后按类别渲染卡片。[asLink] 为 Markdown 链接，总以文件卡片呈现，
 * 媒体类别可点开查看器。[hint] 为工具结果携带的元数据，解析完成前据此预留尺寸。
 */
@Composable
fun WorkspaceMediaReference(
    path: String,
    modifier: Modifier = Modifier,
    alt: String? = null,
    asLink: Boolean = false,
    hint: MediaRef? = null,
    style: MediaCardStyle = MediaCardStyle.MARKDOWN,
) {
    val environment = LocalWorkspaceMedia.current
    if (environment == null) {
        WorkspaceMediaUnavailable(path = path, reason = null, onLocate = null, modifier = modifier)
        return
    }
    val resolved = rememberWorkspaceMediaResolution(environment, path)
    when (val resolution = resolved?.resolution) {
        null -> WorkspaceMediaLoading(hint = hint, style = style, modifier = modifier)
        is MediaResolution.Unavailable -> WorkspaceMediaUnavailable(
            path = path,
            reason = resolution.reason,
            onLocate = environment.onLocate
                ?.takeIf { canLocateInExplorer(path) }
                ?.let { locate -> mediaParentPath(path)?.let { parent -> { locate(parent) } } },
            modifier = modifier,
        )
        is MediaResolution.Available -> {
            val model = WorkspaceMediaModel(environment.sessionId, path, resolved.identity)
            if (asLink) {
                WorkspaceFileCard(available = resolution, model = model, title = alt, modifier = modifier)
            } else {
                AvailableMediaCard(available = resolution, model = model, alt = alt, style = style, modifier = modifier)
            }
        }
    }
}

@Composable
private fun AvailableMediaCard(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    alt: String?,
    style: MediaCardStyle,
    modifier: Modifier = Modifier,
) {
    when (available.ref.kind) {
        MediaKind.IMAGE -> WorkspaceImageCard(available = available, model = model, alt = alt, style = style, modifier = modifier)
        MediaKind.VIDEO -> WorkspaceVideoCard(available = available, model = model, alt = alt, modifier = modifier)
        MediaKind.AUDIO -> WorkspaceAudioCard(available = available, modifier = modifier)
        MediaKind.FILE -> WorkspaceFileCard(available = available, model = model, title = alt, modifier = modifier)
    }
}

@Composable
fun WorkspaceImageCard(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    alt: String?,
    modifier: Modifier = Modifier,
    style: MediaCardStyle = MediaCardStyle.MARKDOWN,
    cropToFill: Boolean = false,
) {
    val ref = available.ref
    val name = mediaDisplayName(ref.path)
    val description = stringResource(R.string.media_cd_image, alt?.takeIf(String::isNotBlank) ?: name)
    var showViewer by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    val ratio = mediaAspectRatio(ref.width, ref.height, DEFAULT_IMAGE_RATIO)
    val sizing = when {
        cropToFill -> Modifier.fillMaxSize()
        style == MediaCardStyle.TOOL -> toolMediaSizing(ratio)
        else -> Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 480.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true)
    }
    Box(
        modifier = modifier
            .then(sizing)
            .clip(if (style == MediaCardStyle.TOOL) ToolThumbnailShape else CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.media_action_view)) { showViewer = true }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = if (cropToFill) ContentScale.Crop else ContentScale.Fit,
            onState = { state -> failed = state is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
    if (showViewer) {
        WorkspaceImageViewer(available = available, model = model, onDismiss = { showViewer = false })
    }
}

@Composable
fun WorkspaceVideoCard(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    alt: String?,
    modifier: Modifier = Modifier,
) {
    val ref = available.ref
    val name = alt?.takeIf(String::isNotBlank) ?: mediaDisplayName(ref.path)
    val session = rememberMediaPlayerSession(available)
    var fullscreen by rememberSaveable(model.path) { mutableStateOf(false) }
    var resumePositionMs by rememberSaveable(model.path) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(model.path) { mutableStateOf(false) }
    val player = session.player
    // 定期记下进度：滚出屏幕后回来、全屏中旋转导致界面重建时都能从原位置继续。
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        while (true) {
            resumePositionMs = current.currentPosition
            resumePlaying = current.playWhenReady
            delay(1_000)
        }
    }
    LaunchedEffect(session) {
        if (fullscreen && session.player == null) {
            session.play(resumePositionMs)
            if (!resumePlaying) session.pause()
        }
    }
    val ratio = mediaAspectRatio(ref.width, ref.height, DEFAULT_VIDEO_RATIO)
    val durationText = formatMediaDuration(ref.durationMs)
    MediaCardSurface(modifier = modifier.widthIn(max = 560.dp)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .clip(CardShape)
                    .background(MaterialTheme.colorScheme.scrim),
                contentAlignment = Alignment.Center,
            ) {
                if (player != null && !fullscreen) {
                    VideoSurface(player, Modifier.fillMaxSize())
                } else {
                    VideoCover(model = model, description = stringResource(R.string.media_cd_video, name))
                }
                if (player == null) {
                    FilledTonalIconButton(
                        onClick = { session.play(resumePositionMs) },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.infinityDomainColors.overlayContent,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.media_action_play),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    MediaBadge(
                        text = listOfNotNull(stringResource(R.string.media_kind_video), durationText).joinToString(" · "),  // wrap-check: allow 单行省略角标
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
                session.error?.let {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        MediaPlaybackError(onRetry = session::retry, contentColor = MaterialTheme.infinityDomainColors.overlayContent)
                    }
                }
            }
            if (player != null) {
                MediaPlaybackControls(
                    player = player,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    onFullscreen = { fullscreen = true },
                )
            }
        }
    }
    if (fullscreen && player != null) {
        FullscreenVideoDialog(session = session, onDismiss = { fullscreen = false })
    }
}

@Composable
private fun VideoCover(model: WorkspaceMediaModel, description: String) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
            onState = { state -> failed = state is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.infinityDomainColors.overlayContent.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = InfinityTypography.labelSmall,
        color = MaterialTheme.infinityDomainColors.overlayContent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun WorkspaceAudioCard(
    available: MediaResolution.Available,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
) {
    val ref = available.ref
    val name = mediaDisplayName(ref.path)
    val session = rememberMediaPlayerSession(available)
    var resumePositionMs by rememberSaveable(ref.path) { mutableLongStateOf(0L) }
    val player = session.player
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        while (true) {
            resumePositionMs = current.currentPosition
            delay(1_000)
        }
    }
    LaunchedEffect(session, autoPlay) { if (autoPlay && session.player == null) session.play(resumePositionMs) }
    val audioDescription = stringResource(R.string.media_cd_audio, name)
    MediaCardSurface(modifier = modifier.widthIn(max = 560.dp)) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (player == null) {
                    IconButton(onClick = { session.play(resumePositionMs) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.media_action_play))
                    }
                } else {
                    Box(Modifier.size(48.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = audioDescription },
                ) {
                    Text(
                        text = name,
                        style = InfinityTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            stringResource(R.string.media_kind_audio),
                            formatMediaDuration(ref.durationMs) ?: stringResource(R.string.media_duration_unknown),
                        ).joinToString(" · "),  // wrap-check: allow 单行显示
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (player != null) {
                MediaPlaybackControls(player = player, showMute = false)
            } else {
                val duration = ref.durationMs?.takeIf { it > 0 }
                val progressLabel = stringResource(R.string.media_playback_progress)
                Slider(
                    value = duration?.let { (resumePositionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
                    onValueChange = { fraction -> duration?.let { resumePositionMs = (fraction * it).toLong() } },
                    enabled = duration != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .semantics { contentDescription = progressLabel },
                )
            }
            session.error?.let {
                MediaPlaybackError(onRetry = session::retry, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun WorkspaceFileCard(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val environment = LocalWorkspaceMedia.current ?: return
    val ref = available.ref
    val runner = rememberMediaActionRunner(environment)
    val name = title?.takeIf(String::isNotBlank) ?: mediaDisplayName(ref.path)
    var showViewer by remember(model) { mutableStateOf(false) }
    val viewable = ref.kind != MediaKind.FILE
    val description = stringResource(R.string.media_cd_file, name)
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    MediaCardSurface(modifier = modifier.widthIn(max = 560.dp)) {
        BoxWithConstraints {
            // 窄屏或大字号时操作按钮换到第二行，保证文件名与大小不被截断。
            val compact = maxWidth < 320.dp * fontScale
            Column(
                modifier = Modifier
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(if (viewable) R.string.media_action_view else R.string.media_action_open_with),
                    ) { if (viewable) showViewer = true else runner.openWith(available) }
                    .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.XSmall, top = 4.dp, bottom = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = mediaKindIcon(ref.kind, ref.mimeType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = InfinitySpacing.Medium, vertical = 6.dp)
                            .semantics { contentDescription = description },
                    ) {
                        Text(
                            text = name,
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        InfinityMetaLine(
                            segments = listOfNotNull(mediaTypeLabel(ref), formatMediaSize(ref.sizeBytes)),
                            style = InfinityTypography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (!compact) MediaActionButtons(available = available, runner = runner)
                }
                if (compact) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MediaActionButtons(available = available, runner = runner)
                    }
                }
            }
        }
    }
    if (showViewer) {
        WorkspaceMediaViewerFor(available = available, model = model, onDismiss = { showViewer = false })
    }
}

@Composable
internal fun MediaActionButtons(
    available: MediaResolution.Available,
    runner: MediaActionRunner,
    includeOpen: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (includeOpen) {
        IconButton(onClick = { runner.openWith(available) }) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = stringResource(R.string.media_action_open_with), tint = tint)
        }
    }
    IconButton(onClick = { runner.save(available) }) {
        Icon(Icons.Rounded.SaveAlt, contentDescription = stringResource(R.string.media_action_save), tint = tint)
    }
    IconButton(onClick = { runner.share(available) }) {
        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.media_action_share), tint = tint)
    }
}

@Composable
fun WorkspaceMediaUnavailable(
    path: String,
    reason: MediaUnavailableReason?,
    onLocate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reasonText = stringResource(
        when (reason) {
            MediaUnavailableReason.NOT_FOUND -> R.string.media_unavailable_not_found
            MediaUnavailableReason.INVALID_PATH -> R.string.media_unavailable_invalid_path
            MediaUnavailableReason.IS_DIRECTORY -> R.string.media_unavailable_directory
            MediaUnavailableReason.UNREADABLE -> R.string.media_unavailable_unreadable
            null -> R.string.media_unavailable_no_session
        },
    )
    MediaCardSurface(modifier = modifier.widthIn(max = 560.dp)) {
        Column(modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.media_unavailable_title),
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.media_unavailable_detail, reasonText, path),
                style = InfinityTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (onLocate != null) {
                InfinityPillButton(
                    text = stringResource(R.string.media_action_locate),
                    onClick = onLocate,
                    icon = Icons.Rounded.TravelExplore,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun WorkspaceMediaLoading(hint: MediaRef?, style: MediaCardStyle, modifier: Modifier = Modifier, fill: Boolean = false) {
    val loadingText = stringResource(R.string.media_loading)
    val visual = hint?.kind == MediaKind.IMAGE || hint?.kind == MediaKind.VIDEO
    val sizing = when {
        fill -> Modifier.fillMaxSize()
        !visual -> Modifier.fillMaxWidth().height(56.dp)
        style == MediaCardStyle.TOOL -> toolMediaSizing(
            mediaAspectRatio(hint?.width, hint?.height, if (hint?.kind == MediaKind.VIDEO) DEFAULT_VIDEO_RATIO else DEFAULT_IMAGE_RATIO),
        )
        else -> Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .aspectRatio(
                mediaAspectRatio(hint?.width, hint?.height, if (hint?.kind == MediaKind.VIDEO) DEFAULT_VIDEO_RATIO else DEFAULT_IMAGE_RATIO),
                matchHeightConstraintsFirst = true,
            )
    }
    Box(
        modifier = modifier
            .then(sizing)
            .clip(if (style == MediaCardStyle.TOOL) ToolThumbnailShape else CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = loadingText },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** 工具卡里的单张图片按原始比例显示：横图占满可用宽度，竖图限高居中，不再压进固定高度的横条。 */
private fun toolMediaSizing(ratio: Float): Modifier =
    Modifier.widthIn(max = 560.dp).heightIn(max = 320.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true)

/**
 * 工具步骤中的媒体：两张及以上图片按两列网格排列，其余类别纵向排列；单张图片按原始比例居中显示。
 */
@Composable
fun ToolMediaList(media: List<MediaRef>, modifier: Modifier = Modifier) {
    val images = media.filter { it.kind == MediaKind.IMAGE }
    val others = media.filter { it.kind != MediaKind.IMAGE }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        if (images.size == 1) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WorkspaceMediaReference(path = images.single().path, hint = images.single(), style = MediaCardStyle.TOOL)
            }
        } else if (images.size > 1) {
            images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    row.forEach { ref ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) { ToolGridImage(ref) }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        others.forEach { ref ->
            WorkspaceMediaReference(path = ref.path, hint = ref, style = MediaCardStyle.TOOL)
        }
    }
}

@Composable
private fun ToolGridImage(ref: MediaRef) {
    val environment = LocalWorkspaceMedia.current
    if (environment == null) {
        WorkspaceMediaUnavailable(path = ref.path, reason = null, onLocate = null, modifier = Modifier.fillMaxSize())
        return
    }
    val resolved = rememberWorkspaceMediaResolution(environment, ref.path)
    when (val resolution = resolved?.resolution) {
        is MediaResolution.Available -> WorkspaceImageCard(
            available = resolution,
            model = WorkspaceMediaModel(environment.sessionId, ref.path, resolved.identity),
            alt = null,
            style = MediaCardStyle.TOOL,
            cropToFill = true,
        )
        is MediaResolution.Unavailable -> Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(ToolThumbnailShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val unavailable = stringResource(R.string.media_unavailable_title)
            Icon(
                Icons.Rounded.BrokenImage,
                contentDescription = unavailable,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        null -> WorkspaceMediaLoading(hint = ref, style = MediaCardStyle.TOOL, fill = true)
    }
}

/** 生图编辑模式的参考图：标签加一排小方图，点开看原图。 */
@Composable
fun ToolReferenceThumbnails(paths: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.tool_step_reference_label),
            style = InfinityTypography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        paths.take(MAX_REFERENCE_THUMBNAILS).forEach { path ->
            Box(Modifier.size(40.dp)) {
                ToolGridImage(MediaRef(path, mediaMimeFromPath(path), MediaKind.IMAGE, 0))
            }
        }
        if (paths.size > MAX_REFERENCE_THUMBNAILS) {
            Text(
                "+${paths.size - MAX_REFERENCE_THUMBNAILS}",
                style = InfinityTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private const val MAX_REFERENCE_THUMBNAILS = 5

private fun mediaMimeFromPath(path: String): String = when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}
