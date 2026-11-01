package com.promenar.infinity.ui.lab.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/** 只管理播放和数据求值，不创建可见界面；控制方法由宿主在主线程调用。 */
class LabPlayer<S> internal constructor(val timeline: LabTimeline<S>, autoPlay: Boolean) {
    var playback: LabPlaybackState by mutableStateOf(LabPlaybackState(playing = autoPlay && timeline.durationMs > 0))
        private set
    val state: S by derivedStateOf { timeline.stateAt(playback.positionMs) }
    internal var clockRevision: Long by mutableLongStateOf(0)
        private set

    fun seek(positionMs: Long) {
        playback = seek(playback, positionMs, timeline.durationMs)
        clockRevision++
    }

    fun restart() {
        playback = restart(playback, timeline.durationMs)
        clockRevision++
    }

    fun togglePlay() {
        playback = togglePlay(playback, timeline.durationMs)
        clockRevision++
    }

    fun setSpeed(speed: Float) { playback = playback.copy(speed = speed) }

    internal fun advanceFrame(deltaNanos: Long) {
        playback = advance(playback, deltaNanos, timeline.durationMs)
    }
}

/** autoPlay 只决定新时间轴的初态；更换时间轴会创建新播放器并取消旧帧循环。 */
@Composable
fun <S> rememberLabPlayer(timeline: LabTimeline<S>, autoPlay: Boolean = true): LabPlayer<S> {
    val player = remember(timeline) { LabPlayer(timeline, autoPlay) }
    LaunchedEffect(player, player.playback.playing, player.clockRevision) {
        if (!player.playback.playing) return@LaunchedEffect
        // 暂停恢复或跳转后重新取基准，避免把暂停期间的时长算进进度。
        var previous = withFrameNanos { it }
        while (player.playback.playing) {
            val now = withFrameNanos { it }
            player.advanceFrame((now - previous).coerceAtLeast(0))
            previous = now
        }
    }
    return player
}
