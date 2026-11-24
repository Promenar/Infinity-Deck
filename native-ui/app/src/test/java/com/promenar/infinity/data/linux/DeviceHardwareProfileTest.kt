package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceHardwareProfileTest {
    @Test fun `按容量 频率和编号保留单个性能核`() {
        val profile = read("0-3,5-7") { cpu, field ->
            when (field) {
                "cpu_capacity" -> if (cpu >= 5) "1024" else "512"
                else -> if (cpu == 6) "3200000" else "2000000"
            }
        }
        assertThat(profile.reservedUiCpuId).isEqualTo(6)
        assertThat(profile.linuxCpuIds).containsExactly(0, 1, 2, 3, 5, 7).inOrder()
        assertThat(profile.linuxParallelLimit).isEqualTo(3)
        assertThat(profile.appMemoryEnvelopeBytes).isEqualTo(3072 * MIB)
    }

    @Test fun `部分拓扑不可读时等权并覆盖单核和最小包络`() {
        val profile = read("0-3") { cpu, _ -> if (cpu == 3) null else (1000 - cpu).toString() }
        assertThat(profile.reservedUiCpuId).isEqualTo(3)
        assertThat(profile.linuxParallelLimit).isEqualTo(2)
        val single = DeviceHardwareProfile.read({ path ->
            when (path) {
                "/proc/meminfo" -> "MemTotal: 1048576 kB\nMemAvailable: 0 kB"
                "/sys/devices/system/cpu/online" -> "5"
                else -> null
            }
        }, availableProcessors = 8)
        assertThat(single.linuxCpuIds).containsExactly(5)
        assertThat(single.reservedUiCpuId).isEqualTo(5)
        assertThat(single.linuxParallelLimit).isEqualTo(1)
        assertThat(single.appMemoryEnvelopeBytes).isEqualTo(512 * MIB)
    }

    private fun read(online: String, sysfs: (Int, String) -> String?) =
        DeviceHardwareProfile.read({ path ->
            when (path) {
                "/proc/meminfo" -> "MemTotal: 16777216 kB\nMemAvailable: 1 kB"
                "/sys/devices/system/cpu/online" -> online
                else -> {
                    val cpu = path.substringAfter("/cpu/cpu").substringBefore('/').toInt()
                    sysfs(cpu, path.substringAfterLast('/'))
                }
            }
        }, availableProcessors = 1)
}
