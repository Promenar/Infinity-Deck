package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThinkingClockTest {
    @Test fun `离开再回来沿用第一次看到思考的起点`() {
        val clock = ThinkingClock()
        assertThat(clock.start("m1", now = 1_000)).isEqualTo(1_000)
        assertThat(clock.start("m1", now = 9_000)).isEqualTo(1_000)
    }

    @Test fun `结束后记下时长且只记一次`() {
        val clock = ThinkingClock()
        clock.start("m1", now = 1_000)
        clock.finish("m1", now = 4_500)
        clock.finish("m1", now = 60_000)
        assertThat(clock.duration("m1")).isEqualTo(3_500)
    }

    @Test fun `没开始过的消息不记时长`() {
        val clock = ThinkingClock()
        clock.finish("m2", now = 4_500)
        assertThat(clock.duration("m2")).isNull()
    }

    @Test fun `超过容量时淘汰最久未用的消息`() {
        val clock = ThinkingClock(capacity = 2)
        clock.start("a", now = 1)
        clock.start("b", now = 2)
        clock.start("a", now = 3)
        clock.start("c", now = 4)
        assertThat(clock.start("a", now = 5)).isEqualTo(1)
        assertThat(clock.start("b", now = 6)).isEqualTo(6)
    }

    @Test fun `写入的时长覆盖已有记录，之后的结束不再改写`() {
        val clock = ThinkingClock()
        clock.start("m1", now = 1_000)
        clock.finish("m1", now = 1_200)
        clock.record("m1", durationMs = 6_000)
        clock.finish("m1", now = 90_000)
        assertThat(clock.duration("m1")).isEqualTo(6_000)
    }

    @Test fun `重置后起点与时长都从下一次出现重新开始`() {
        val clock = ThinkingClock()
        clock.start("m1", now = 1_000)
        clock.record("m1", durationMs = 6_000)
        clock.reset("m1")
        assertThat(clock.duration("m1")).isNull()
        assertThat(clock.start("m1", now = 650_000)).isEqualTo(650_000)
    }
}
