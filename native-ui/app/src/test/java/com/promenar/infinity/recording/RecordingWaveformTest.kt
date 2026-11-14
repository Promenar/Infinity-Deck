package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWaveformTest {
    @Test fun samplesUseEveryPcmBlockAndKeepEnoughHistoryForTheInnerScreen() {
        val captured = mutableListOf<RecordingWaveformSamples>()
        val sampler = RecordingWaveformSampler(RecordingWaveformSamples(), captured::add)
        val steady = ShortArray(640) { 4_096 }
        sampler.accept(steady, 640)
        sampler.accept(steady, 640)
        sampler.accept(ShortArray(320), 320)
        assertEquals(1, captured.size)
        assertEquals(0.8944f, captured.single().levels.single(), 0.0001f)

        repeat(200) { sampler.accept(ShortArray(1_600), 1_600) }
        assertEquals(201L, captured.last().sequence)
        assertEquals(160, captured.last().levels.size)
        assertTrue(captured.last().levels.all { it == 0f })
    }

    @Test fun sampleAndOffsetChangeTogetherAndExistingBarsNeverRebound() {
        val motion = RecordingWaveformMotion()
        val two = RecordingWaveformSamples(listOf(.2f, .6f), 2)
        val three = RecordingWaveformSamples(listOf(.2f, .6f, .8f), 3)
        val initial = motion.frame(two, 100_000_000L)
        val next = motion.frame(three, 124_000_000L)
        assertEquals(.6f, initial.levels.last())
        assertEquals(.6f, next.levels[next.levels.lastIndex - 1])
        assertEquals(initial.offset, next.offset - 1, 0.001f)

        val moving = motion.frame(three, 144_000_000L)
        assertEquals(next.offset - .2f, moving.offset, 0.001f)
        assertEquals(three.levels, moving.levels)
    }

    @Test fun freezePreservesTheLastDrawAndDisabledMotionDrawsTheLatestSamples() {
        val motion = RecordingWaveformMotion()
        motion.frame(RecordingWaveformSamples(listOf(.2f), 1), 0)
        val current = RecordingWaveformSamples(listOf(.2f, .3f), 2)
        val beforePause = motion.frame(current, 100_000_000L)
        motion.pause()
        assertEquals(beforePause, motion.frame(current, 600_000_000L))
        assertEquals(0f, motion.frame(current, 900_000_000L).offset)
    }
}
