package com.promenar.infinity.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingUtteranceLeadTest {
    @Test fun extendedPauseCanRestartWithTheExactAudioBeforeSpeech() {
        val lead = RecordingUtteranceLead()
        val pcm = ShortArray(13_440) { it.toShort() }
        for (offset in pcm.indices step 640) {
            lead.append(pcm.copyOfRange(offset, minOf(pcm.size, offset + 640)))
        }
        // 起声点在当前音频块之前 1,024 个样本。
        assertArrayEquals(pcm.takeLast(6_656).toShortArray(), lead.replayFrom(-1_024, null)!!.pcm)
    }

    @Test fun ordinaryShortPauseKeepsExistingAudioContext() {
        val lead = RecordingUtteranceLead()
        lead.append(ShortArray(5_000))
        assertNull(lead.replayFrom(-1_024, null))
    }

    @Test fun tokenInQuietLeadDoesNotBlockReplayButProtectsTheOnset() {
        val lead = RecordingUtteranceLead()
        lead.append(ShortArray(23_040))
        val onsetFromEnd = -1_024
        assertEquals(16_384L, lead.replayFrom(onsetFromEnd, recordingLastTokenSample(floatArrayOf(1f)))!!.start)
        // 声前保护窗保留音频，只有已到达起声窗的词元能阻止重试。
        assertEquals(16_384L, lead.replayFrom(onsetFromEnd, lastToken = 16_384)!!.start)
        assertNull(lead.replayFrom(onsetFromEnd, lastToken = 22_016))
        assertNull(lead.replayFrom(onsetFromEnd, recordingLastTokenSample(floatArrayOf(1f, 1.38f))))
        for (invalid in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(-1f))) {
            assertNull(lead.replayFrom(onsetFromEnd, recordingLastTokenSample(invalid)))
        }
        assertEquals(12_800L, recordingLastTokenSample(floatArrayOf(0.79999995f)))
    }
}
