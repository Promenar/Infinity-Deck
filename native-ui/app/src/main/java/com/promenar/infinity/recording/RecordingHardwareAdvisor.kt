package com.promenar.infinity.recording

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

internal enum class RecordingRefineCapability {
    /** 旗舰 / 次旗舰设备：支持录音中逐句实时二遍精修，同时在暂停与结束时做兜底精修。 */
    REALTIME_TWO_PASS,
    /** 主流中端设备：录音中优先保障流式识别不卡顿，仅在暂停与结束时运行二遍精修。 */
    DEFERRED_TWO_PASS,
    /** 入门设备或可用内存偏紧：默认关闭二遍精修，仅保留低开销流式听写。 */
    STREAMING_ONLY,
}

internal enum class RecordingRefineMode {
    AUTO,
    ALWAYS_ON,
    OFF,
}

internal enum class RecordingRefineStrategy {
    NONE,
    DEFERRED_ONLY,
    REALTIME_AND_DEFERRED,
}

internal data class DeviceHardwareMetrics(
    val isArm64: Boolean,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val isLowMemory: Boolean,
    val cpuCoreCount: Int,
    val bigCoreCount: Int,
    val cpuMaxFreqKhz: Long,
    val socModel: String,
)

internal data class RecordingHardwareAssessment(
    val metrics: DeviceHardwareMetrics,
    val capability: RecordingRefineCapability,
    val defaultEnabled: Boolean,
)

internal object RecordingHardwareAdvisor {
    const val RAM_12G_THRESHOLD_BYTES = 10_500_000_000L
    const val RAM_8G_THRESHOLD_BYTES = 7_000_000_000L
    const val RAM_6G_THRESHOLD_BYTES = 5_200_000_000L

    const val AVAIL_RAM_REALTIME_BYTES = 1_200_000_000L
    const val AVAIL_RAM_DEFERRED_BYTES = 900_000_000L

    const val BIG_CORE_FREQ_KHZ = 2_350_000L
    const val FLAGSHIP_MAX_FREQ_KHZ = 2_550_000L
    const val MIN_FLAGSHIP_BIG_CORES = 3

    private val flagshipSocTokens = listOf(
        "SM8750", "SM8650", "SM8635", "SM8550", "SM8475", "SM8450",
        "SM7675", "SM7550",
        "MT6991", "MT6989", "MT6985", "MT6983", "MT6897", "MT6899",
        "TENSOR G3", "TENSOR G4", "TENSOR G5",
        "EXYNOS 2400", "EXYNOS 2500", "S5E9945", "S5E9955",
    )

    fun readMetrics(context: Context): DeviceHardwareMetrics {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager?.getMemoryInfo(memoryInfo)
        val isArm64 = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
        val socModel = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL.orEmpty().ifBlank { Build.HARDWARE.orEmpty() }
        } else {
            Build.HARDWARE.orEmpty()
        }
        val (cpuMaxFreq, bigCores) = readCpuFrequencyStats()
        return DeviceHardwareMetrics(
            isArm64 = isArm64,
            totalRamBytes = memoryInfo.totalMem,
            availRamBytes = memoryInfo.availMem,
            isLowMemory = memoryInfo.lowMemory,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            bigCoreCount = bigCores,
            cpuMaxFreqKhz = cpuMaxFreq,
            socModel = socModel,
        )
    }

    fun assess(metrics: DeviceHardwareMetrics): RecordingHardwareAssessment {
        val capability = classifyCapability(metrics)
        return RecordingHardwareAssessment(
            metrics = metrics,
            capability = capability,
            defaultEnabled = capability != RecordingRefineCapability.STREAMING_ONLY,
        )
    }

    fun assess(context: Context): RecordingHardwareAssessment = assess(readMetrics(context))

    fun classifyCapability(metrics: DeviceHardwareMetrics): RecordingRefineCapability {
        if (!metrics.isArm64 || metrics.isLowMemory) {
            return RecordingRefineCapability.STREAMING_ONLY
        }
        val flagshipCpu = (metrics.cpuMaxFreqKhz >= FLAGSHIP_MAX_FREQ_KHZ && metrics.bigCoreCount >= MIN_FLAGSHIP_BIG_CORES) ||
            isKnownFlagshipSoc(metrics.socModel) ||
            metrics.totalRamBytes >= RAM_12G_THRESHOLD_BYTES
        if (metrics.totalRamBytes >= RAM_8G_THRESHOLD_BYTES &&
            metrics.availRamBytes >= AVAIL_RAM_REALTIME_BYTES &&
            flagshipCpu
        ) {
            return RecordingRefineCapability.REALTIME_TWO_PASS
        }
        if (metrics.totalRamBytes >= RAM_6G_THRESHOLD_BYTES &&
            metrics.availRamBytes >= AVAIL_RAM_DEFERRED_BYTES
        ) {
            return RecordingRefineCapability.DEFERRED_TWO_PASS
        }
        return RecordingRefineCapability.STREAMING_ONLY
    }

    fun resolveStrategy(
        mode: RecordingRefineMode,
        refineModelInstalled: Boolean,
        capability: RecordingRefineCapability,
        runtimeDegraded: Boolean = false,
    ): RecordingRefineStrategy {
        if (!refineModelInstalled || mode == RecordingRefineMode.OFF) {
            return RecordingRefineStrategy.NONE
        }
        if (mode == RecordingRefineMode.ALWAYS_ON) {
            return RecordingRefineStrategy.REALTIME_AND_DEFERRED
        }
        return when (capability) {
            RecordingRefineCapability.REALTIME_TWO_PASS ->
                if (runtimeDegraded) RecordingRefineStrategy.DEFERRED_ONLY
                else RecordingRefineStrategy.REALTIME_AND_DEFERRED
            RecordingRefineCapability.DEFERRED_TWO_PASS -> RecordingRefineStrategy.DEFERRED_ONLY
            RecordingRefineCapability.STREAMING_ONLY -> RecordingRefineStrategy.NONE
        }
    }

    fun recommendedLocalContextSize(metrics: DeviceHardwareMetrics): Int = when {
        metrics.isLowMemory -> 4096
        metrics.totalRamBytes >= RAM_12G_THRESHOLD_BYTES -> 16_384
        metrics.totalRamBytes >= RAM_8G_THRESHOLD_BYTES -> 8_192
        else -> 4096
    }

    fun recommendedLocalContextSize(context: Context): Int =
        recommendedLocalContextSize(readMetrics(context))

    fun recommendedAsrThreads(coreCount: Int = Runtime.getRuntime().availableProcessors()): Int =
        if (coreCount >= 8) 3 else 2

    fun isKnownFlagshipSoc(socModel: String): Boolean {
        val upper = socModel.trim().uppercase()
        if (upper.isBlank()) return false
        return flagshipSocTokens.any { token -> upper.contains(token) }
    }

    private fun readCpuFrequencyStats(): Pair<Long, Int> {
        val cpuDir = File("/sys/devices/system/cpu")
        val cores = cpuDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("cpu") && file.name.drop(3).all(Char::isDigit)
        } ?: return 0L to 0
        var maxKhz = 0L
        var bigCount = 0
        for (core in cores) {
            val freqFile = File(core, "cpufreq/cpuinfo_max_freq")
            val khz = runCatching {
                if (freqFile.isFile) freqFile.readText().trim().toLongOrNull() ?: 0L else 0L
            }.getOrDefault(0L)
            if (khz > maxKhz) maxKhz = khz
            if (khz >= BIG_CORE_FREQ_KHZ) bigCount++
        }
        return maxKhz to bigCount
    }
}

/**
 * 实时二遍精修 RTF 熔断器：连续多段精修耗时过长（RTF > 0.65）时，
 * 表明设备正处于发热降频或后台高负载状态，自动退避到暂停/结束再统一精修。
 */
internal class RefineRtfTracker(
    private val rtfThreshold: Float = 0.65f,
    private val minSegmentAudioMs: Long = 1_500L,
    private val consecutiveLimit: Int = 2,
) {
    private var consecutiveSlow = 0
    @Volatile
    var isDegraded: Boolean = false
        private set

    fun record(elapsedMs: Long, segmentAudioMs: Long): Boolean {
        if (segmentAudioMs < minSegmentAudioMs) return isDegraded
        val rtf = elapsedMs.toFloat() / segmentAudioMs.toFloat()
        if (rtf > rtfThreshold) {
            consecutiveSlow++
            if (consecutiveSlow >= consecutiveLimit) {
                isDegraded = true
            }
        } else {
            consecutiveSlow = 0
        }
        return isDegraded
    }

    fun reset() {
        consecutiveSlow = 0
        isDegraded = false
    }
}
