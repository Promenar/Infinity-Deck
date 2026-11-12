package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.media.*
import kotlinx.coroutines.delay

/** 沿用会话媒体播放会话与后台暂停规则，播放完成后进入下一个镜头。 */
@Composable
internal fun ProductionCutPlayer(clip: ProductionCutClip?, playing: Boolean, onComplete: () -> Unit, onStop: () -> Unit) {
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
        ProductionBlock {
            Text(clip?.title ?: stringResource(R.string.p2_no_shots), style = MaterialTheme.typography.titleMedium)
            clip?.let { Text(secondsLabel(it.seconds)) }
        }
        LaunchedEffect(clip, playing) { if (playing && clip != null) { delay((clip.seconds * 1000).toLong().coerceAtLeast(1)); complete() } }
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
