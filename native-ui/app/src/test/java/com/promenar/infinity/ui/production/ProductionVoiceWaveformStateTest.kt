package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProductionVoiceWaveformStateTest {
    @Test fun `PCM 最小值转振幅不溢出`() {
        assertThat(pcm16Amplitude(Short.MIN_VALUE)).isEqualTo(1f)
        assertThat(pcm16Amplitude(0)).isEqualTo(0f)
        assertThat(pcm16Amplitude(Short.MAX_VALUE)).isLessThan(1f)
    }
    @Test fun `包络按时间取峰值并拒绝无效样本`() {
        val accumulator = VoiceWaveformAccumulator(1000, 2)
        accumulator.record(0.25f, 0)
        accumulator.record(0.5f, 499)
        accumulator.record(2f, 1000)
        accumulator.record(Float.NaN, 0)
        accumulator.record(1f, -1)
        accumulator.record(1f, Long.MAX_VALUE)
        assertThat(accumulator.snapshot()).containsExactly(0.5f, 1f).inOrder()
    }
}
