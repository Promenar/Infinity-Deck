package com.promenar.infinity.ui.pet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PetSensorsTest {
    /** 先喂一段静止读数，再以 50 毫秒间隔左右来回甩动 [swing] 米每二次方秒。 */
    private fun shakes(swing: Float, count: Int): List<PetShake> {
        val detector = PetMotionDetector()
        var time = 0L
        repeat(20) {
            detector.feed(0f, GRAVITY, 0f, time)
            time += 20
        }
        val results = mutableListOf<PetShake>()
        repeat(count) { index ->
            detector.feed(if (index % 2 == 0) swing else -swing, GRAVITY, 0f, time)?.let(results::add)
            time += 50
        }
        return results
    }

    @Test
    fun `来回轻摇三次识别为轻摇`() {
        assertThat(shakes(swing = 12f, count = 4)).containsExactly(PetShake.LIGHT)
    }

    @Test
    fun `用力来回摇晃五次识别为用力摇晃而不是轻摇`() {
        assertThat(shakes(swing = 25f, count = 6)).containsExactly(PetShake.STRONG)
    }

    @Test
    fun `走路幅度的晃动不触发`() {
        assertThat(shakes(swing = 3f, count = 40)).isEmpty()
    }

    @Test
    fun `同一方向的持续加速不算摇晃`() {
        val detector = PetMotionDetector()
        detector.feed(0f, GRAVITY, 0f, 0)
        val results = (1..10).mapNotNull { detector.feed(14f, GRAVITY, 0f, it * 50L) }

        assertThat(results).isEmpty()
    }

    @Test
    fun `右侧朝下时倾斜为正`() {
        val detector = PetMotionDetector()
        repeat(120) { detector.feed(-4.9f, 8.5f, 0f, it * 20L) }

        assertThat(detector.tilt).isWithin(0.02f).of(0.8f)
    }

    @Test
    fun `平放时没有倾斜`() {
        val detector = PetMotionDetector()
        repeat(60) { detector.feed(0f, 0f, GRAVITY, it * 20L) }

        assertThat(detector.tilt).isWithin(0.001f).of(0f)
    }

    @Test
    fun `持续的大音量识别为一次吹气并进入冷却`() {
        val logic = PetBlowLogic()

        val hits = (1..20).count { logic.feed(level = 0.5f, seconds = 0.05f) }

        assertThat(hits).isEqualTo(1)
    }

    @Test
    fun `短促的响声和普通说话音量不算吹气`() {
        val logic = PetBlowLogic()

        val spike = listOf(0.9f, 0.9f, 0.02f, 0.02f, 0.02f, 0.9f, 0.9f, 0.02f, 0.02f, 0.02f)
            .count { logic.feed(it, 0.05f) }
        val talking = (1..100).count { logic.feed(0.1f, 0.05f) }

        assertThat(spike).isEqualTo(0)
        assertThat(talking).isEqualTo(0)
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}
