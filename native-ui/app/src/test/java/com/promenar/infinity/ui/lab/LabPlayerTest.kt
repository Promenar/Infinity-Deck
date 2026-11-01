package com.promenar.infinity.ui.lab

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.timeline.LabPlayer
import com.promenar.infinity.ui.lab.timeline.labTimeline
import org.junit.jupiter.api.Test

class LabPlayerTest {
    @Test
    fun `播放器控制与当前脚本状态保持一致`() {
        val timeline = labTimeline("") { at(100) { "已开始" }; at(1000) { "已结束" } }
        val player = LabPlayer(timeline, autoPlay = false)
        assertThat(player.playback.playing).isFalse()
        player.togglePlay()
        player.advanceFrame(100_000_000)
        assertThat(player.state).isEqualTo("已开始")
        player.setSpeed(2f)
        player.advanceFrame(450_000_000)
        assertThat(player.state).isEqualTo("已结束")
        assertThat(player.playback.playing).isFalse()
        player.seek(-1)
        assertThat(player.state).isEmpty()
        assertThat(player.playback.playing).isFalse()
        player.restart()
        assertThat(player.playback.playing).isTrue()
        assertThat(player.playback.positionMs).isEqualTo(0)
        assertThat(player.playback.speed).isEqualTo(2f)
    }

    @Test
    fun `空时间轴不能启动播放`() {
        val player = LabPlayer(labTimeline(1) {}, autoPlay = true)
        assertThat(player.playback.playing).isFalse()
        player.togglePlay()
        player.restart()
        assertThat(player.playback.playing).isFalse()
        assertThat(player.state).isEqualTo(1)
    }
}
