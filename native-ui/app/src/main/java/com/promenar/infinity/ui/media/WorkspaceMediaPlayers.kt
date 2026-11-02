package com.promenar.infinity.ui.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import java.util.UUID

/** 播放源：真实文件用文件 URI，SAF 来源的 content URI 直接交给播放器。 */
fun playbackUri(source: MediaSource): Uri = when (source) {
    is MediaSource.LocalFile -> Uri.fromFile(source.file)
    is MediaSource.ContentUri -> Uri.parse(source.uri)
}

/**
 * 一张卡片的播放会话：首次播放时才创建 ExoPlayer，经 [MediaPlaybackCoordinator] 保证同一时刻只播一个；
 * 离开组合时释放。
 */
@Stable
class MediaPlayerSession internal constructor(
    private val context: Context,
    private val available: MediaResolution.Available,
    private val coordinator: MediaPlaybackCoordinator,
) {
    val id: String = UUID.randomUUID().toString()
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    var error: PlaybackException? by mutableStateOf(null)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) coordinator.onStarted(id) else coordinator.onStopped(id)
        }

        override fun onPlayerError(playbackError: PlaybackException) {
            error = playbackError
            coordinator.onStopped(id)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) error = null
        }
    }

    private fun ensurePlayer(startPositionMs: Long): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .build()
        .also { created ->
            created.setAudioAttributes(AudioAttributes.DEFAULT, true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(listener)
            created.setMediaItem(MediaItem.fromUri(playbackUri(available.source)), startPositionMs.coerceAtLeast(0))
            created.prepare()
            coordinator.register(id) { created.pause() }
            player = created
        }

    fun play(startPositionMs: Long = 0) {
        val current = ensurePlayer(startPositionMs)
        if (error != null) retry() else current.play()
    }

    fun pause() {
        player?.pause()
    }

    fun retry() {
        val current = player ?: return play()
        error = null
        current.prepare()
        current.play()
    }

    fun release() {
        coordinator.unregister(id)
        player?.let { current ->
            current.removeListener(listener)
            current.release()
        }
        player = null
    }
}

/** 记住播放会话；离开组合时释放播放器，应用进入后台时暂停。 */
@Composable
fun rememberMediaPlayerSession(
    available: MediaResolution.Available,
    coordinator: MediaPlaybackCoordinator = MediaPlaybackCoordinator.Shared,
): MediaPlayerSession {
    val context = LocalContext.current.applicationContext
    // 按路径与来源记住：复核解析得到的新对象不应中断正在进行的播放。
    val session = remember(available.ref.path, available.source, coordinator) {
        MediaPlayerSession(context, available, coordinator)
    }
    DisposableEffect(session) {
        onDispose { session.release() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return session
}

/** 视频画面：Media3 ContentFrame 负责 Surface 绑定与宽高比。 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    ContentFrame(player = player, modifier = modifier, contentScale = ContentScale.Fit)
}

/** 进度条与控制行：播放/暂停、时间、静音与全屏切换均使用 Media3 Material3 组件。 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
internal fun MediaPlaybackControls(
    player: Player,
    modifier: Modifier = Modifier,
    onFullscreen: (() -> Unit)? = null,
    fullscreen: Boolean = false,
    showMute: Boolean = true,
) {
    val progressLabel = stringResource(R.string.media_playback_progress)
    Column(modifier = modifier.fillMaxWidth()) {
        ProgressSlider(
            player = player,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = progressLabel },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayPauseButton(player = player)
            PositionAndDurationText(
                player = player,
                modifier = Modifier.weight(1f),
            )
            if (showMute) MuteButton(player = player)
            if (onFullscreen != null) {
                IconButton(onClick = onFullscreen) {
                    Icon(
                        imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(
                            if (fullscreen) R.string.media_action_exit_fullscreen else R.string.media_action_fullscreen,
                        ),
                    )
                }
            }
        }
    }
}

/** 播放出错：错误说明与重试。 */
@Composable
internal fun MediaPlaybackError(onRetry: () -> Unit, modifier: Modifier = Modifier, contentColor: Color = LocalContentColor.current) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.media_playback_error),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
        InfinityRetryButton(onClick = onRetry, text = stringResource(R.string.media_action_retry))
    }
}

/**
 * 独立全屏层：沿用传入的播放器，退出时保持进度；横竖屏均按画面宽高比居中显示，点按画面切换控件显隐。
 */
@Composable
fun FullscreenVideoDialog(
    session: MediaPlayerSession,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        com.promenar.infinity.ui.common.ImmersiveSystemBars()
        BackHandler(onBack = onDismiss)
        var controlsVisible by rememberSaveable { mutableStateOf(true) }
        val overlay = MaterialTheme.infinityDomainColors.overlayContent
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = null,
                ) { controlsVisible = !controlsVisible },
            contentAlignment = Alignment.Center,
        ) {
            val player = session.player
            // 已播完时换到新画面层不会再渲染帧；回到开头以显示首帧，点播放即重播。
            LaunchedEffect(player) { if (player?.playbackState == Player.STATE_ENDED) player.seekTo(0) }
            if (player != null) VideoSurface(player, Modifier.fillMaxSize())
            val error = session.error
            CompositionLocalProvider(LocalContentColor provides overlay) {
                if (error != null) {
                    MediaPlaybackError(onRetry = session::retry, contentColor = overlay)
                }
                if (controlsVisible || error != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f), contentColor = overlay),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.media_action_close))
                    }
                    if (player != null) {
                        MediaPlaybackControls(
                            player = player,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                                .navigationBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            onFullscreen = onDismiss,
                            fullscreen = true,
                        )
                    }
                }
            }
        }
    }
}
