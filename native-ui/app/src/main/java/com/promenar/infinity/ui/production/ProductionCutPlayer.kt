package com.promenar.infinity.ui.production

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.media.*
import com.promenar.infinity.ui.theme.InfinityMonospace
import kotlinx.coroutines.delay

/** 沿用会话媒体播放会话与后台暂停规则，播放完成后进入下一个镜头。 */
@Composable
internal fun ProductionCutPlayer(
    clip: ProductionCutClip?,
    playing: Boolean,
    onComplete: () -> Unit,
    onStop: () -> Unit,
    clipIndex: Int = 0,
    totalClips: Int = 0,
    isPreviewMode: Boolean = true,
) {
    val environment = LocalWorkspaceMedia.current
    val complete by rememberUpdatedState(onComplete)
    val stop by rememberUpdatedState(onStop)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val path = clip?.path
    if (path == null) {
        CutPlaceholderMonitor(
            clip = clip,
            playing = playing,
            onComplete = complete,
            clipIndex = clipIndex,
            totalClips = totalClips,
            isPreviewMode = isPreviewMode,
        )
        return
    }
    if (environment == null) {
        ProductionMediaReference(path, Modifier.fillMaxWidth())
        return
    }
    val mapped = rememberProductionPath(environment, path) as? ProductionPathState.Ready ?: return
    val resolution = rememberWorkspaceMediaResolution(environment, mapped.path)?.resolution
    if (resolution !is MediaResolution.Available) {
        ProductionMediaReference(path, Modifier.fillMaxWidth())
        LaunchedEffect(resolution) { if (resolution is MediaResolution.Unavailable) stop() }
        return
    }
    if (resolution.ref.kind == MediaKind.IMAGE || resolution.ref.kind == MediaKind.FILE) {
        ProductionMediaReference(path, Modifier.fillMaxWidth())
        LaunchedEffect(path, playing) { if (playing) { delay(((clip?.seconds ?: 1.0) * 1000).toLong().coerceAtLeast(1)); complete() } }
    } else {
        val session = rememberMediaPlayerSession(resolution)
        LaunchedEffect(session, playing) { if (playing) session.play() else session.pause() }
        val player = session.player
        val active by rememberUpdatedState(playing)
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED && active) complete() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { stop() }
            }
            player?.addListener(listener)
            onDispose { player?.removeListener(listener) }
        }
        if (player != null) {
            Column {
                if (resolution.ref.kind == MediaKind.VIDEO) VideoSurface(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                MediaPlaybackControls(player)
            }
        } else ProductionThumbnail(path, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        session.error?.let { MediaPlaybackError(onRetry = session::retry) }
    }
}

/** 无可用成片/预演文件时的 16:9 监视器占位卡：顶部镜序与模式胶囊 + 镜号标题与计划时长 + 连播进度推进。 */
@Composable
private fun CutPlaceholderMonitor(
    clip: ProductionCutClip?,
    playing: Boolean,
    onComplete: () -> Unit,
    clipIndex: Int,
    totalClips: Int,
    isPreviewMode: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val progress = remember(clip) { Animatable(0f) }
    LaunchedEffect(clip, playing, animate) {
        progress.snapTo(0f)
        if (!playing || clip == null) return@LaunchedEffect
        val durationMs = (clip.seconds * 1000).toLong().coerceAtLeast(1L)
        if (animate) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    easing = LinearEasing,
                ),
            )
        } else {
            progress.snapTo(0.42f)
            delay(durationMs)
        }
        onComplete()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    ) {
        if (clip == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideocamOff,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.p2_no_shots),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.production_cut_placeholder_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Surface
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 195.dp
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (totalClips > 0) {
                    Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHighest.copy(alpha = 0.88f)) {
                        Text(
                            text = stringResource(R.string.production_cut_shot_position, clipIndex + 1, totalClips),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (playing) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.88f),
                ) {
                    val statusTint = if (playing) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                playing -> Icons.Rounded.PlayArrow
                                isPreviewMode -> Icons.Rounded.Slideshow
                                else -> Icons.Rounded.Movie
                            },
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(
                                when {
                                    playing -> R.string.production_cut_placeholder_playing
                                    isPreviewMode -> R.string.production_cut_placeholder_preview_badge
                                    else -> R.string.production_cut_placeholder_no_frame_badge
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            val primaryTitle = listOfNotNull(
                clip.shotCode.takeIf(String::isNotBlank),
                clip.title.takeIf(String::isNotBlank),
            ).joinToString(" $InfinityMetaSeparator ") // wrap-check: allow 监视器占位卡镜号与标题固定单行省略
                .ifBlank { stringResource(R.string.p2_no_shots) }
            val statusPhrase = stringResource(
                if (isPreviewMode) R.string.production_cut_placeholder_no_preview else R.string.production_cut_placeholder_no_take,
            )
            val durationPhrase = stringResource(R.string.production_cut_planned_duration, secondsLabel(clip.seconds))
            val subtitleText = listOf(statusPhrase, durationPhrase).joinToString(" $InfinityMetaSeparator ") // wrap-check: allow 监视器占位副标题居中显示完整状态与计划时长

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = if (playing) 14.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (compactHeight) 30.dp else 34.dp)
                            .clip(CircleShape)
                            .background(if (playing) scheme.primary.copy(alpha = 0.14f) else scheme.onSurface.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.VideocamOff,
                            contentDescription = null,
                            tint = if (playing) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(if (compactHeight) 16.dp else 18.dp),
                        )
                    }
                    Text(
                        text = primaryTitle,
                        style = if (compactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (playing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.28f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                            .background(scheme.primary),
                    )
                }
            }
        }
    }
}
