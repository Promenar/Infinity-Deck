package com.promenar.infinity.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingUtteranceLeadTest {
    @Test fun extendedPauseCanRestartWithTheExactAudioBeforeSpeech() {
        val lead = RecordingUtteranceLead()
        // 长停顿（> 24*512 = 12,288）且起音减去 640ms pre-roll（20*512 = 10,240）仍大于 0 时，精准从 start 开始回放。
        val pcm = ShortArray(16_000) { it.toShort() }
        for (offset in pcm.indices step 640) {
            lead.append(pcm.copyOfRange(offset, minOf(pcm.size, offset + 640)))
        }
        // written = 16,000, onsetFromEnd = -1,024 => onset = 14,976, start = 14,976 - 10,240 = 4,736, length = 11,264
        assertArrayEquals(pcm.takeLast(11_264).toShortArray(), lead.replayFrom(-1_024, null)!!.pcm)
    }

    @Test fun ordinaryShortPauseOrNearStreamStartKeepsExistingAudioContext() {
        val lead = RecordingUtteranceLead()
        lead.append(ShortArray(5_000))
        assertNull(lead.replayFrom(-1_024, null))

        // onset 虽超过长停顿，但回放起点 <= 0（说明句首全部已留在当前流中），绝不毁流重灌。
        val shallow = RecordingUtteranceLead()
        shallow.append(ShortArray(11_264)) // written=11,264; onset=10,240? No wait: 13_440-1024=12_416 >= 12_288, start=2_176 > 0; 11_264+0
        val nearStart = RecordingUtteranceLead()
        nearStart.append(ShortArray(10_880))
        assertNull(nearStart.replayFrom(-640, null))
    }

    @Test fun tokenInQuietLeadDoesNotBlockReplayButProtectsTheOnset() {
        val lead = RecordingUtteranceLead()
        lead.append(ShortArray(23_040))
        val onsetFromEnd = -1_024
        // onset = 22,016, start = 22,016 - 10,240 = 11,776
        assertEquals(11_776L, lead.replayFrom(onsetFromEnd, recordingLastTokenSample(floatArrayOf(1f)))!!.start)
        // 声前保护窗保留音频，只有已到达起声窗的词元能阻止重试。
        assertEquals(11_776L, lead.replayFrom(onsetFromEnd, lastToken = 16_384)!!.start)
        assertNull(lead.replayFrom(onsetFromEnd, lastToken = 22_016))
        assertNull(lead.replayFrom(onsetFromEnd, recordingLastTokenSample(floatArrayOf(1f, 1.38f))))
        for (invalid in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(-1f))) {
            assertNull(lead.replayFrom(onsetFromEnd, recordingLastTokenSample(invalid)))
        }
        assertEquals(12_800L, recordingLastTokenSample(floatArrayOf(0.79999995f)))
    }
}
