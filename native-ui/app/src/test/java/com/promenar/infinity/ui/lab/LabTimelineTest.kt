package com.promenar.infinity.ui.lab

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LabTimelineTest {
    @Test
    fun `求值从初态重放且支持乱序跳转`() {
        val timeline = labTimeline(1) {
            at(100) { it + 2 }
            at(200) { it * 3 }
            hold(100)
        }
        listOf(200L, 0L, 300L, 100L, 200L, 50L, 200L).forEach { time ->
            assertThat(timeline.stateAt(time)).isEqualTo(when {
                time < 100 -> 1
                time < 200 -> 3
                else -> 9
            })
        }
    }

    @Test
    fun `时刻夹取且零时刻关键帧生效`() {
        val timeline = labTimeline(1) {
            at(0) { it + 1 }
            at(100) { it * 3 }
        }
        assertThat(timeline.stateAt(-5)).isEqualTo(2)
        assertThat(timeline.stateAt(0)).isEqualTo(2)
        assertThat(timeline.stateAt(99)).isEqualTo(2)
        assertThat(timeline.stateAt(100)).isEqualTo(6)
        assertThat(timeline.stateAt(Long.MAX_VALUE)).isEqualTo(6)
    }

    @Test
    fun `乱序加入会排序且同刻按加入顺序应用`() {
        val timeline = labTimeline("") {
            at(200) { it + "C" }
            at(100) { it + "A" }
            at(100) { it + "B" }
        }
        assertThat(timeline.keyframes.map { it.atMs }).containsExactly(100L, 100L, 200L).inOrder()
        assertThat(timeline.stateAt(200)).isEqualTo("ABC")
    }

    @Test
    fun `流式文本按完整字素揭示且结束向上取整`() {
        val clusters = listOf("A", "中", "😀", "e\u0301", "👩🏽‍💻", "🇨🇳", "1️⃣", "👨‍👩‍👧‍👦", "\r\n")
        val text = clusters.joinToString("")
        val timeline = labTimeline("") {
            stream(at = 100, charsPerSecond = 3f, text = text) { _, partial -> partial }
            hold(50)
        }
        assertThat(timeline.durationMs).isEqualTo(3150)
        assertThat(timeline.stateAt(100)).isEmpty()
        assertThat(timeline.stateAt(433)).isEmpty()
        assertThat(timeline.stateAt(434)).isEqualTo("A")
        var previousLength = 0
        val validPrefixes = (0..clusters.size).map { clusters.take(it).joinToString("") }
        for (time in 0L..3150L) {
            val partial = timeline.stateAt(time)
            assertThat(partial).isIn(validPrefixes)
            assertThat(partial.length).isAtLeast(previousLength)
            previousLength = partial.length
        }
        assertThat(timeline.stateAt(3100)).isEqualTo(text)
        assertThat(timeline.stateAt(Long.MAX_VALUE)).isEqualTo(text)
        assertThat(timeline.stateAt(1100)).isEqualTo("A中😀")
    }

    @Test
    fun `英文中文与空文本的边界`() {
        for (text in listOf("abc", "甲乙丙")) {
            val timeline = labTimeline("") { stream(0, 2f, text) { _, partial -> partial } }
            assertThat(timeline.durationMs).isEqualTo(1500)
            assertThat(timeline.stateAt(499)).isEmpty()
            assertThat(timeline.stateAt(500)).isEqualTo(text.take(1))
            assertThat(timeline.stateAt(1500)).isEqualTo(text)
        }
        val empty = labTimeline("初态") { stream(10, 40f, "") { _, partial -> partial } }
        assertThat(empty.durationMs).isEqualTo(10)
        assertThat(empty.stateAt(10)).isEmpty()
    }

    @Test
    fun `重叠流与瞬时帧按开始时刻及加入顺序折叠`() {
        val timeline = labTimeline("") {
            stream(0, 2f, "abcd") { _, partial -> partial }
            at(500) { "[$it]" }
            stream(500, 1f, "XY") { state, partial -> "$state:$partial" }
        }
        assertThat(timeline.stateAt(1500)).isEqualTo("[abc]:X")
        assertThat(timeline.stateAt(0)).isEmpty()
        assertThat(timeline.stateAt(2500)).isEqualTo("[abcd]:XY")
    }

    @Test
    fun `after以上一帧结束为基准且hold累计在最大结束之后`() {
        val timeline = labTimeline("") {
            at(100) { it + "A" }
            stream(200, 2f, "XY") { state, partial -> state + partial }
            after(300) { it + "B" }
            hold(200)
            at(50) { it + "Z" }
            after(25) { it + "Q" }
            hold(100)
        }
        assertThat(timeline.durationMs).isEqualTo(1800)
        assertThat(timeline.stateAt(1800)).isEqualTo("ZQAXYB")
        assertThat(labTimeline(0) { hold(23); hold(7) }.durationMs).isEqualTo(30)
        assertThat(labTimeline(0) {}.durationMs).isEqualTo(0)
    }

    @Test
    fun `拒绝无效时刻速度时长和溢出`() {
        assertThrows(IllegalArgumentException::class.java) { labTimeline(0) { at(-1) { it } } }
        assertThrows(IllegalArgumentException::class.java) { labTimeline(0) { after(-1) { it } } }
        assertThrows(IllegalArgumentException::class.java) { labTimeline(0) { hold(-1) } }
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                labTimeline("") { stream(0, speed, "a") { _, partial -> partial } }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { LabTimeline(0, emptyList(), -1) }
        assertThrows(ArithmeticException::class.java) { labTimeline(0) { at(Long.MAX_VALUE) { it }; hold(1) } }
    }
}
