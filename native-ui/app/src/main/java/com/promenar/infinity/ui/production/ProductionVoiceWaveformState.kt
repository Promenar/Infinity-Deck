package com.promenar.infinity.ui.production

import kotlin.math.abs

internal fun pcm16Amplitude(sample: Short): Float = abs(sample.toInt()) / 32768f

/** 固定桶数的峰值包络，内存用量不随音频时长增长。 */
internal class VoiceWaveformAccumulator(private val durationUs: Long, bucketCount: Int = 64) {
    private val peaks = FloatArray(bucketCount)
    init { require(durationUs in 1..120_000_000L && bucketCount in 1..256) }
    fun record(amplitude: Float, timeUs: Long) {
        if (!amplitude.isFinite() || timeUs < 0 || timeUs > durationUs) return
        val index = (timeUs * peaks.size / durationUs).toInt().coerceIn(0, peaks.lastIndex)
        peaks[index] = maxOf(peaks[index], amplitude.coerceIn(0f, 1f))
    }
    fun snapshot(): List<Float> = peaks.toList()
}
