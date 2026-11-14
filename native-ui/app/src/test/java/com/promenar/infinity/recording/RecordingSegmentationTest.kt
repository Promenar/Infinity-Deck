package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSegmentationTest {
    private val voice = ShortArray(640) { 4_000 }
    private val silence = ShortArray(640)

    @Test fun endpointUsesSpeechOnsetAndResetsSegmentStart() {
        val state = RecordingSegmentation(32_000)
        state.accept(silence, "", false)
        val first = state.accept(voice, " 首先， 测试 ", true)
        assertTrue(first.reset)
        assertEquals(RecordingSegment(1_040, 1_080, "首先，测试"), first.frame.segment)
        assertEquals("", first.frame.partial)
        assertTrue(first.frame.checkpoint)
        assertEquals(RecordingSegment(1_080, 1_120, "下一段"), state.accept(voice, "下一段", true).frame.segment)
    }

    @Test fun emptyEndpointResetsWithoutCreatingASegment() {
        val state = RecordingSegmentation(0)
        val empty = state.accept(voice, "  ", true)
        assertTrue(empty.reset)
        assertNull(empty.frame.segment)
        assertFalse(empty.frame.checkpoint)
        assertEquals(RecordingSegment(40, 80, "尾段"), state.accept(silence, "尾段", true).frame.segment)
    }

    @Test fun forcedLimitIsSixtySecondsAndWorksAfterResume() {
        val state = RecordingSegmentation(10_000 * 32L)
        repeat(1_499) {
            assertFalse(state.accept(voice, "连续说话", false).reset)
        }
        val end = state.accept(voice, "连续说话", false)
        assertTrue(end.reset)
        assertEquals(RecordingSegment(10_000, 70_000, "连续说话"), end.frame.segment)
        assertEquals(RecordingSegment(70_000, 70_040, "结束。"), state.accept(voice, "结束。", true).frame.segment)
    }

    @Test fun checkpointsCarryProvisionalTextEveryFiveSeconds() {
        val state = RecordingSegmentation(0)
        repeat(124) { assertFalse(state.accept(voice, " 实时， 文本 ", false).frame.checkpoint) }
        val checkpoint = state.accept(voice, " 实时， 文本 ", false).frame
        assertEquals(160_000L, checkpoint.bytes)
        assertEquals("实时，文本", checkpoint.partial)
        assertEquals(RecordingSegment(0, 5_000, "实时，文本"), checkpoint.provisional)
        assertNull(checkpoint.segment)
        assertTrue(checkpoint.checkpoint)
        repeat(124) { assertFalse(state.accept(voice, "实时，文本", false).frame.checkpoint) }
        assertTrue(state.accept(voice, "实时，文本", false).frame.checkpoint)
    }

    @Test fun endingExactlyAtASplitKeepsAnAudioRangeForBufferedWords() {
        for (endpoint in listOf(true, false)) {
            val state = RecordingSegmentation(32_000)
            repeat(1_499) { state.accept(voice, "已经解码", false) }
            val split = state.accept(voice, "已经解码", endpoint, decodedMs = 59_520)
            assertTrue(split.reset)
            assertEquals(RecordingSegment(1_000, 60_520, "已经解码"), split.frame.segment)
            assertEquals(61_000 * 32L, split.frame.bytes)
            assertEquals(RecordingSegment(60_520, 61_000, "缓冲区尾字"), state.finish("缓冲区尾字").segment)
        }
    }

    @Test fun louderBufferedAudioDoesNotHideSoftSpeechBeforeTheDecodedBoundary() {
        val state = RecordingSegmentation(0)
        state.accept(silence, "轻声", false, decodedMs = 0)
        val split = state.accept(voice, "轻声", true, decodedMs = 40)
        assertEquals(RecordingSegment(0, 40, "轻声"), split.frame.segment)
        assertEquals(RecordingSegment(40, 80, "后一字"), state.finish("后一字").segment)
    }

    @Test fun twoSecondsOfSilenceSetQuietAndVoiceClearsIt() {
        val state = RecordingSegmentation(0)
        repeat(49) { assertFalse(state.accept(silence, "", false).frame.quiet) }
        assertTrue(state.accept(silence, "", false).frame.quiet)
        val spoken = state.accept(voice, "测试", false).frame
        assertFalse(spoken.quiet)
        val tail = state.finish(" 测试， 完成。 ")
        assertEquals(RecordingSegment(2_000, 2_040, "测试，完成。"), tail.segment)
        assertTrue(tail.quiet)
    }
}
