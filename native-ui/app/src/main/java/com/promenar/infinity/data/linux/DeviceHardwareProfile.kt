package com.promenar.infinity.data.linux

import java.io.File

internal const val MIB = 1024L * 1024

data class DeviceHardwareProfile(
    val appMemoryEnvelopeBytes: Long,
    val reservedUiCpuId: Int,
    val linuxCpuIds: List<Int>,
    val linuxParallelLimit: Int,
) {
    companion object {
        private const val CPU_DIRECTORY = "/sys/devices/system/cpu"
        private const val MAX_CPU_ID = 1023

        fun read(
            readText: (String) -> String? = ::readResourceFile,
            availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        ): DeviceHardwareProfile {
            val total = readText("/proc/meminfo")?.let { readKib(it, "MemTotal") } ?: 0
            val envelope = (total * 0.20).toLong().coerceIn(512 * MIB, 3072 * MIB)
            val onlineFromSysfs = cpuIds(readText("$CPU_DIRECTORY/online"))
            val online = onlineFromSysfs ?: cpuIds(readText("/proc/self/status")
                ?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':'))
                ?: (0 until availableProcessors.coerceIn(1, MAX_CPU_ID + 1)).toList()
            val scores = online.map { cpu ->
                val capacity = readText("$CPU_DIRECTORY/cpu$cpu/cpu_capacity")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                val frequency = readText("$CPU_DIRECTORY/cpu$cpu/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                if (capacity != null && frequency != null) CpuScore(cpu, capacity, frequency) else null
            }
            val reserved = if (onlineFromSysfs == null || scores.any { it == null }) online.last() else {
                scores.filterNotNull().maxWith(compareBy(CpuScore::capacity, CpuScore::maxFrequency, CpuScore::id)).id
            }
            val linux = if (online.size == 1) online else online.filterNot { it == reserved }
            return DeviceHardwareProfile(envelope, reserved, linux, minOf(linux.size, maxOf(2, online.size / 2)))
        }

        private fun cpuIds(text: String?): List<Int>? {
            val cpus = sortedSetOf<Int>()
            for (part in text?.trim()?.split(',') ?: return null) {
                val start = part.substringBefore('-').toIntOrNull() ?: return null
                val end = part.substringAfter('-', part).toIntOrNull() ?: return null
                if (start !in 0..MAX_CPU_ID || end !in start..MAX_CPU_ID) return null
                cpus.addAll(start..end)
            }
            return cpus.toList().takeIf { it.isNotEmpty() }
        }

        private data class CpuScore(val id: Int, val capacity: Long, val maxFrequency: Long)
    }
}

internal fun readResourceFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()

internal fun readKib(text: String, field: String): Long? =
    text.lineSequence().firstOrNull { it.startsWith("$field:") }
        ?.substringAfter(':')?.trimStart()?.takeWhile(Char::isDigit)?.toLongOrNull()
        ?.takeIf { it <= Long.MAX_VALUE / 1024 }?.times(1024)
