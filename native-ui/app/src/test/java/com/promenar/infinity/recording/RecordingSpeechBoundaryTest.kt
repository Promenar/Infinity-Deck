package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSpeechBoundaryTest {
    @Test fun wordlessSpeechStillEndsAtAnAcousticPauseAndDoesNotRetriggerInSilence() {
        var probability = .95f
        val detector = RecordingSpeechBoundary { probability }
        repeat(3) { assertNull(detector.accept(ShortArray(640)).end) }

        probability = .05f
        repeat(10) { assertNull(detector.accept(ShortArray(640)).end) }
        assertEquals(384, detector.accept(ShortArray(640)).end)
        repeat(100) { assertNull(detector.accept(ShortArray(640)).end) }
    }

    @Test fun shortGapAndSoftContinuationKeepTheSameUtterance() {
        var probability = .9f
        val detector = RecordingSpeechBoundary { probability }
        fun window() = detector.accept(ShortArray(RecordingSpeechBoundary.WINDOW_SAMPLES)).end
        repeat(3) { assertNull(window()) }
        probability = .05f
        repeat(8) { assertNull(window()) }
        probability = .4f
        assertNull(window())
        probability = .05f
        repeat(13) { assertNull(window()) }
        assertEquals(512, window())
    }

    @Test fun classifierSeesEverySampleInOrderAcrossUnevenReads() {
        val seen = mutableListOf<Float>()
        val detector = RecordingSpeechBoundary { samples -> seen.addAll(samples.toList()); .1f }
        val expected = List(2_048) { it.toShort() }
        var start = 0
        for (size in listOf(1, 640, 8, 640, 640, 119)) {
            assertNull(detector.accept(expected.subList(start, start + size).toShortArray()).end)
            start += size
        }
        assertEquals(expected.map { it / 32768f }, seen)
    }

    @Test fun activityCanReportAnOnsetBeforeThisReadAndAnExactCut() {
        var probability = .9f
        val detector = RecordingSpeechBoundary { probability }
        val block = ShortArray(640)
        assertEquals(RecordingSpeechWindow(), detector.accept(block))
        assertEquals(RecordingSpeechWindow(), detector.accept(block))
        assertEquals(RecordingSpeechWindow(onset = -1_280), detector.accept(block))
        probability = .05f
        repeat(10) { assertEquals(RecordingSpeechWindow(), detector.accept(block)) }
        assertEquals(RecordingSpeechWindow(end = 384), detector.accept(block))
    }
}
