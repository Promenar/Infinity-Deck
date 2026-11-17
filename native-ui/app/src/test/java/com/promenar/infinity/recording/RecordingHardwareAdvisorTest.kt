package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHardwareAdvisorTest {

    private fun metrics(
        isArm64: Boolean = true,
        totalRamBytes: Long = 12_000_000_000L,
        availRamBytes: Long = 3_000_000_000L,
        isLowMemory: Boolean = false,
        cpuCoreCount: Int = 8,
        bigCoreCount: Int = 4,
        cpuMaxFreqKhz: Long = 3_200_000L,
        socModel: String = "",
    ) = DeviceHardwareMetrics(
        isArm64 = isArm64,
        totalRamBytes = totalRamBytes,
        availRamBytes = availRamBytes,
        isLowMemory = isLowMemory,
        cpuCoreCount = cpuCoreCount,
        bigCoreCount = bigCoreCount,
        cpuMaxFreqKhz = cpuMaxFreqKhz,
        socModel = socModel,
    )

    @Test
    fun `flagship ram and cpu classify as realtime two pass`() {
        val capability = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 11_000_000_000L, availRamBytes = 2_000_000_000L, bigCoreCount = 2, cpuMaxFreqKhz = 2_200_000L),
        )
        assertEquals(RecordingRefineCapability.REALTIME_TWO_PASS, capability)

        val socFlagship = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 7_500_000_000L, availRamBytes = 1_500_000_000L, bigCoreCount = 1, cpuMaxFreqKhz = 2_000_000L, socModel = "Qualcomm SM8650"),
        )
        assertEquals(RecordingRefineCapability.REALTIME_TWO_PASS, socFlagship)

        val freqFlagship = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 7_500_000_000L, availRamBytes = 1_300_000_000L, bigCoreCount = 3, cpuMaxFreqKhz = 2_600_000L, socModel = "Custom"),
        )
        assertEquals(RecordingRefineCapability.REALTIME_TWO_PASS, freqFlagship)
    }

    @Test
    fun `mid range ram or constrained available ram classify as deferred two pass`() {
        val midRange8g = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 7_400_000_000L, availRamBytes = 1_500_000_000L, bigCoreCount = 2, cpuMaxFreqKhz = 2_200_000L),
        )
        assertEquals(RecordingRefineCapability.DEFERRED_TWO_PASS, midRange8g)

        val midRange6g = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 5_500_000_000L, availRamBytes = 1_000_000_000L, bigCoreCount = 2, cpuMaxFreqKhz = 2_100_000L),
        )
        assertEquals(RecordingRefineCapability.DEFERRED_TWO_PASS, midRange6g)

        val flagshipTightRam = RecordingHardwareAdvisor.classifyCapability(
            metrics(totalRamBytes = 11_500_000_000L, availRamBytes = 950_000_000L, socModel = "SM8750"),
        )
        assertEquals(RecordingRefineCapability.DEFERRED_TWO_PASS, flagshipTightRam)
    }

    @Test
    fun `non arm64 low memory or sub 6gb classify as streaming only`() {
        assertEquals(
            RecordingRefineCapability.STREAMING_ONLY,
            RecordingHardwareAdvisor.classifyCapability(metrics(isArm64 = false)),
        )
        assertEquals(
            RecordingRefineCapability.STREAMING_ONLY,
            RecordingHardwareAdvisor.classifyCapability(metrics(isLowMemory = true)),
        )
        assertEquals(
            RecordingRefineCapability.STREAMING_ONLY,
            RecordingHardwareAdvisor.classifyCapability(metrics(totalRamBytes = 3_800_000_000L, availRamBytes = 1_200_000_000L)),
        )
        assertEquals(
            RecordingRefineCapability.STREAMING_ONLY,
            RecordingHardwareAdvisor.classifyCapability(metrics(totalRamBytes = 5_600_000_000L, availRamBytes = 700_000_000L)),
        )
    }

    @Test
    fun `assess default enabled mirrors non streaming capability`() {
        assertTrue(RecordingHardwareAdvisor.assess(metrics(totalRamBytes = 12_000_000_000L)).defaultEnabled)
        assertTrue(RecordingHardwareAdvisor.assess(metrics(totalRamBytes = 5_600_000_000L, availRamBytes = 1_000_000_000L, bigCoreCount = 2, cpuMaxFreqKhz = 2_000_000L)).defaultEnabled)
        assertFalse(RecordingHardwareAdvisor.assess(metrics(isLowMemory = true)).defaultEnabled)
    }

    @Test
    fun `resolveStrategy handles install state user modes and runtime degradation`() {
        assertEquals(
            RecordingRefineStrategy.NONE,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.ALWAYS_ON, false, RecordingRefineCapability.REALTIME_TWO_PASS),
        )
        assertEquals(
            RecordingRefineStrategy.NONE,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.OFF, true, RecordingRefineCapability.REALTIME_TWO_PASS),
        )
        assertEquals(
            RecordingRefineStrategy.REALTIME_AND_DEFERRED,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.ALWAYS_ON, true, RecordingRefineCapability.STREAMING_ONLY, runtimeDegraded = true),
        )
        assertEquals(
            RecordingRefineStrategy.REALTIME_AND_DEFERRED,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.AUTO, true, RecordingRefineCapability.REALTIME_TWO_PASS, runtimeDegraded = false),
        )
        assertEquals(
            RecordingRefineStrategy.DEFERRED_ONLY,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.AUTO, true, RecordingRefineCapability.REALTIME_TWO_PASS, runtimeDegraded = true),
        )
        assertEquals(
            RecordingRefineStrategy.DEFERRED_ONLY,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.AUTO, true, RecordingRefineCapability.DEFERRED_TWO_PASS),
        )
        assertEquals(
            RecordingRefineStrategy.NONE,
            RecordingHardwareAdvisor.resolveStrategy(RecordingRefineMode.AUTO, true, RecordingRefineCapability.STREAMING_ONLY),
        )
    }

    @Test
    fun `recommended local context size scales across 16k 8k and 4k`() {
        assertEquals(16_384, RecordingHardwareAdvisor.recommendedLocalContextSize(metrics(totalRamBytes = 11_000_000_000L)))
        assertEquals(8_192, RecordingHardwareAdvisor.recommendedLocalContextSize(metrics(totalRamBytes = 7_500_000_000L)))
        assertEquals(4_096, RecordingHardwareAdvisor.recommendedLocalContextSize(metrics(totalRamBytes = 5_500_000_000L)))
        assertEquals(4_096, RecordingHardwareAdvisor.recommendedLocalContextSize(metrics(totalRamBytes = 12_000_000_000L, isLowMemory = true)))
    }

    @Test
    fun `recommended asr threads respects core count`() {
        assertEquals(3, RecordingHardwareAdvisor.recommendedAsrThreads(8))
        assertEquals(2, RecordingHardwareAdvisor.recommendedAsrThreads(6))
    }

    @Test
    fun `refine rtf tracker ignores short segments resets on fast segments and degrades after consecutive slow segments`() {
        val tracker = RefineRtfTracker(rtfThreshold = 0.65f, minSegmentAudioMs = 1_500L, consecutiveLimit = 2)

        assertFalse(tracker.record(elapsedMs = 2_000L, segmentAudioMs = 1_000L))
        assertFalse(tracker.record(elapsedMs = 1_600L, segmentAudioMs = 2_000L))
        assertFalse(tracker.record(elapsedMs = 400L, segmentAudioMs = 2_000L))
        assertFalse(tracker.record(elapsedMs = 1_500L, segmentAudioMs = 2_000L))
        assertTrue(tracker.record(elapsedMs = 1_500L, segmentAudioMs = 2_000L))
        assertTrue(tracker.isDegraded)

        tracker.reset()
        assertFalse(tracker.isDegraded)
    }
}
