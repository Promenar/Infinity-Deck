package com.promenar.infinity.recording

import org.junit.Assert.assertArrayEquals
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
        assertArrayEquals(pcm.takeLast(7_168).toShortArray(), lead.replayFrom(-1_024)!!.pcm)
    }

    @Test fun ordinaryShortPauseKeepsExistingAudioContext() {
        val lead = RecordingUtteranceLead()
        lead.append(ShortArray(5_000))
        assertNull(lead.replayFrom(-1_024))
    }
}
