package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSpeechBoundaryTest {
    @Test fun wordlessSpeechStillEndsAtAnAcousticPauseAndDoesNotRetriggerInSilence() {
        var probability = .95f
        val detector = RecordingSpeechBoundary { probability }
        repeat(3) { assertNull(detector.accept(ShortArray(640)).end) }

        // 前 21 块（13,440 + 384 = 13,824 样本 = 27 窗 = 864ms）静音不切段；第 22 块凑满第 28 窗（896ms）才切段。
        probability = .05f
        repeat(21) { assertNull(detector.accept(ShortArray(640)).end) }
        assertEquals(512, detector.accept(ShortArray(640)).end)
        repeat(100) { assertNull(detector.accept(ShortArray(640)).end) }
    }

    @Test fun shortGapAndSoftContinuationKeepTheSameUtterance() {
        var probability = .9f
        val detector = RecordingSpeechBoundary { probability }
        fun window() = detector.accept(ShortArray(RecordingSpeechBoundary.WINDOW_SAMPLES)).end
        repeat(3) { assertNull(window()) }
        // 气口停顿 8 窗（256ms）且低于 0.35 但高于 0.28 的轻声延续（0.32f）保持不断流。
        probability = .05f
        repeat(8) { assertNull(window()) }
        probability = .32f
        assertNull(window())
        probability = .05f
        repeat(27) { assertNull(window()) }
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
        repeat(21) { assertEquals(RecordingSpeechWindow(), detector.accept(block)) }
        assertEquals(RecordingSpeechWindow(end = 512), detector.accept(block))
    }
}
