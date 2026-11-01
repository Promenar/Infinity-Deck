package com.promenar.infinity.ui.lab

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.timeline.LabPlaybackState
import com.promenar.infinity.ui.lab.timeline.advance
import com.promenar.infinity.ui.lab.timeline.restart
import com.promenar.infinity.ui.lab.timeline.seek
import com.promenar.infinity.ui.lab.timeline.togglePlay
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LabPlaybackTest {
    @Test
    fun `五档速度按纳秒推进`() {
        for ((speed, expected) in listOf(0.25f to 250L, 0.5f to 500L, 1f to 1000L, 1.5f to 1500L, 2f to 2000L)) {
            assertThat(advance(LabPlaybackState(0, true, speed), 1_000_000_000, 5000).positionMs).isEqualTo(expected)
        }
    }

    @Test
    fun `亚毫秒累积避免低速播放漂移`() {
        var state = LabPlaybackState(0, true, 0.25f)
        repeat(4000) { state = advance(state, 1000, 5000) }
        assertThat(state.positionMs).isEqualTo(1)
        var frames = LabPlaybackState(0, true, 1.5f)
        repeat(60) { frames = advance(frames, 16_666_667, 5000) }
        assertThat(frames.positionMs).isEqualTo(1500)
    }

    @Test
    fun `末尾自动停止且暂停不推进`() {
        val end = advance(LabPlaybackState(900, true, 2f), 100_000_000, 1000)
        assertThat(end.positionMs).isEqualTo(1000)
        assertThat(end.playing).isFalse()
        val paused = LabPlaybackState(200, false, 1f)
        assertThat(advance(paused, Long.MAX_VALUE, 1000)).isEqualTo(paused)
        assertThat(advance(LabPlaybackState(0, true, 2f), Long.MAX_VALUE, 1000)).isEqualTo(end)
        assertThat(advance(LabPlaybackState(0, true, 1f), 0, 0).playing).isFalse()
    }

    @Test
    fun `seek夹取并清除零头而restart从头播放`() {
        val state = LabPlaybackState(500, true, 0.5f)
        assertThat(seek(state, -10, 1000).positionMs).isEqualTo(0)
        assertThat(seek(state, 2000, 1000).playing).isFalse()
        assertThat(seek(state, 2000, 1000).positionMs).isEqualTo(1000)
        val restarted = restart(seek(state, 2000, 1000), 1000)
        assertThat(restarted).isEqualTo(LabPlaybackState(0, true, 0.5f))
        assertThat(restart(state, 0).playing).isFalse()
        val fractional = advance(LabPlaybackState(0, true, 0.25f), 999_999, 1000)
        assertThat(advance(seek(fractional, 0, 1000), 3_000_000, 1000).positionMs).isEqualTo(0)
    }

    @Test
    fun `播放切换可暂停恢复且从末尾重播`() {
        val state = LabPlaybackState(100, true, 1f)
        assertThat(togglePlay(state, 1000)).isEqualTo(state.copy(playing = false))
        assertThat(togglePlay(state.copy(playing = false), 1000)).isEqualTo(state)
        assertThat(togglePlay(LabPlaybackState(1000, false, 2f), 1000)).isEqualTo(LabPlaybackState(0, true, 2f))
        assertThat(togglePlay(LabPlaybackState(), 0).playing).isFalse()
    }

    @Test
    fun `拒绝未支持的速度和负时间参数`() {
        assertThrows(IllegalArgumentException::class.java) { LabPlaybackState(speed = 3f) }
        assertThrows(IllegalArgumentException::class.java) { advance(LabPlaybackState(), -1, 1000) }
        assertThrows(IllegalArgumentException::class.java) { seek(LabPlaybackState(), 0, -1) }
    }
}
