package com.promenar.infinity.data.cut

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.CutVideoSegment
import org.junit.Test

class Media3CutExporterPolicyTest {

    @Test
    fun `静态标题卡固定 24fps 而视频片段沿用自身帧率避免产生 VFR`() {
        assertThat(CUT_EXPORT_FRAME_RATE).isEqualTo(24)

        val titleCard = CutVideoSegment(
            episodeId = "S1E01",
            shotCode = "SC01-01",
            path = null,
            title = "缺口标题卡",
            startSeconds = 0.0,
            durationSeconds = 2.0,
        )
        val videoTake = CutVideoSegment(
            episodeId = "S1E01",
            shotCode = "SC01-02",
            path = "productions/p/takes/s1.mp4",
            title = "镜头二",
            startSeconds = 2.0,
            durationSeconds = 5.0,
        )

        assertThat(cutSegmentFrameRate(titleCard)).isEqualTo(24)
        assertThat(cutSegmentFrameRate(videoTake)).isNull()
    }
}
