package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSpeakersTest {
    @Test fun `连续接话在同一条听写中保留原字并分开`() {
        val raw = "先确认 llama.cpp。Technical term 已验证，继续。"
        assertEquals(
            listOf("先确认 llama.cpp。", "Technical term 已验证，继续。"),
            splitRecordingSpeakerText(raw, listOf("先确认LAMA CPP", "technical term 已经验证继续")),
        )
    }

    @Test fun `第一位说话人的重跑字数漂移时采用双侧边界锚点`() {
        val first = "And those who parted with. Today we are testing the recording interface. The transcript will eat the title and the end of the paper. This is a second sentence with a different rhythm."
        val second = "The next person will test the speaker label. We can edit the name."
        val firstHint = "those who partner. Today we are testing the recording interface. The transcript will eventun and appear not document will separate. This is a second sentence with a different rhythm."
        assertEquals(listOf(first, second), splitRecordingSpeakerText("$first $second", listOf(firstHint, second)))
    }

    @Test fun `边界无法可靠定位时保留原段`() {
        assertNull(splitRecordingSpeakerText("这是完整的原始听写", listOf("完全无关", "另一句话")))
        assertNull(splitRecordingSpeakerText("technical terminology", listOf("tech", "nical terminology")))
    }

    @Test fun `同一说话人的停顿不产生额外边界`() {
        val segment = RecordingSegment(0, 9_000, "完整文本")
        val turns = listOf(
            RecordingSpeakerTurn(100, 2_000, 0), RecordingSpeakerTurn(2_300, 4_000, 0),
            RecordingSpeakerTurn(4_700, 7_800, 1),
        )
        assertEquals(
            listOf(RecordingSpeakerTurn(0, 4_350, 0), RecordingSpeakerTurn(4_350, 9_000, 1)),
            recordingSpeakerSpans(segment, turns),
        )
    }

    @Test fun `按说话人分批整理且保留原始选项`() {
        val segments = listOf(
            RecordingSegment(0, 2_000, "你好", 0), RecordingSegment(2_000, 4_000, "稍等", 0),
            RecordingSegment(4_000, 6_000, "好了", 1),
        )
        assertEquals(0..1, nextRecordingBatch(segments, 0, 30, separateSpeakers = true))
        assertEquals(0..2, nextRecordingBatch(segments, 0, 30))
    }
}
