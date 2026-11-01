package com.promenar.infinity.ui.lab

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.timeline.LabPlayer
import com.promenar.infinity.ui.lab.timeline.labTimeline
import com.promenar.infinity.ui.lab.timeline.rememberLabPlayer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LabPlayerCompositionTest {
    @BeforeEach
    fun `替换JVM未实现的平台诊断薄桥接`() {
        mockkStatic(Log::class, Trace::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
    }

    @AfterEach
    fun `恢复平台诊断桥接`() { unmockkStatic(Log::class, Trace::class) }

    @Test
    fun `帧时钟推进且暂停跳转更换脚本与离开合成均不泄漏时间`() = runTest {
        val clock = BroadcastFrameClock()
        val context = coroutineContext + clock
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val timeline = mutableStateOf(labTimeline("") { at(100) { "开始" }; at(1000) { "结束" } })
        lateinit var player: LabPlayer<String>
        suspend fun frame(ms: Long) {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(ms * 1_000_000)
                runCurrent()
            }
        }
        try {
            composition.setContent { player = rememberLabPlayer(timeline.value) }
            runCurrent()
            frame(0)
            frame(500)
            assertThat(player.playback.positionMs).isEqualTo(500)
            assertThat(player.state).isEqualTo("开始")
            player.togglePlay()
            frame(500)
            frame(9000)
            assertThat(player.playback.positionMs).isEqualTo(500)
            player.togglePlay()
            frame(9000)
            frame(9250)
            assertThat(player.playback.positionMs).isEqualTo(750)
            player.seek(0)
            frame(9250)
            frame(9750)
            assertThat(player.playback.positionMs).isEqualTo(500)
            player.restart()
            frame(9750)
            frame(11000)
            assertThat(player.state).isEqualTo("结束")
            assertThat(player.playback.playing).isFalse()
            val previous = player
            timeline.value = labTimeline("新脚本") { hold(2000) }
            frame(12000)
            assertThat(player).isNotSameInstanceAs(previous)
            assertThat(player.playback.positionMs).isEqualTo(0)
            frame(12250)
            assertThat(player.playback.positionMs).isEqualTo(250)
            assertThat(previous.playback.positionMs).isEqualTo(1000)
            composition.dispose()
            frame(15000)
            assertThat(player.playback.positionMs).isEqualTo(250)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
        }
    }

    @Test
    fun `自动播放参数只决定新时间轴的初态`() = runTest {
        val clock = BroadcastFrameClock()
        val context = coroutineContext + clock
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val timeline = labTimeline(0) { hold(1000) }
        val autoPlay = mutableStateOf(false)
        lateinit var player: LabPlayer<Int>
        try {
            composition.setContent { player = rememberLabPlayer(timeline, autoPlay.value) }
            runCurrent()
            val initial = player
            autoPlay.value = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(0)
            runCurrent()
            assertThat(player).isSameInstanceAs(initial)
            assertThat(player.playback.playing).isFalse()
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
