package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxResourceGovernorTest {
    @Test fun `预算仅扣本进程和活跃子树并按阈值收敛线程`() {
        var self = 512 * MIB
        val governor = LinuxResourceGovernor(
            { DeviceHardwareProfile(2048 * MIB, 7, (0..6).toList(), 4) },
            { self }, { 256 * MIB },
        )
        assertThat(governor.budget().jobMemoryBudgetBytes).isEqualTo(1280 * MIB)
        assertThat(governor.budget().effectiveThreads).isEqualTo(4)
        self = 1024 * MIB
        assertThat(governor.budget().effectiveThreads).isEqualTo(2)
        self = 2048 * MIB
        assertThat(governor.budget().jobMemoryBudgetBytes).isEqualTo(256 * MIB)
        assertThat(governor.budget().effectiveThreads).isEqualTo(1)
    }

    @Test fun `紧急信号或连续越限才熔断且保留峰值`() {
        val samples = listOf(null, 101L, null, 85L, 102L, 103L).iterator()
        var critical = false
        val watch = LinuxMemoryWatch(100, { samples.next() }, { critical })
        assertThat(watch.check(123, 0)).isFalse()
        assertThat(watch.check(123, 1000)).isFalse()
        assertThat(watch.check(123, 1500)).isFalse()
        assertThat(watch.check(123, 2500)).isFalse()
        assertThat(watch.check(123, 2999)).isFalse()
        assertThat(watch.check(123, 3000)).isFalse()
        assertThat(watch.check(123, 3500)).isTrue()
        assertThat(watch.peakMemoryBytes).isEqualTo(103)
        val idle = LinuxMemoryWatch(100, { null }, { critical })
        critical = true
        assertThat(idle.check(null, 0)).isTrue()
        assertThat(idle.peakMemoryBytes).isNull()
        val osLowMemory = LinuxMemoryWatch(100, { 20 }, { false }, lowMemory = { true })
        assertThat(osLowMemory.check(123, 0)).isTrue()
        assertThat(osLowMemory.peakMemoryBytes).isEqualTo(20)
    }

    @Test fun `进程采样只读取所属子树或进程组并合计RSS和交换`() {
        val reads = mutableListOf<Int>()
        val sampler = LinuxProcessMemory(
            processes = { listOf(
                LinuxProcessIdentity(10, 2, 10), LinuxProcessIdentity(20, 10, 20),
                LinuxProcessIdentity(21, 20, 21), LinuxProcessIdentity(22, 1, 20),
                LinuxProcessIdentity(30, 2, 30),
            ) },
            status = { reads += it; "VmRSS: 1024 kB\nVmSwap: 128 kB" },
        )
        assertThat(sampler.forGroup(20)).isEqualTo(3 * 1152 * 1024L)
        assertThat(reads).containsExactly(20, 21, 22)
        reads.clear()
        assertThat(sampler.forChildren(10)).isEqualTo(3 * 1152 * 1024L)
        assertThat(reads).containsExactly(20, 21, 22)
        assertThat(LinuxProcessMemory.residentBytes("RssAnon: 24 kB\nVmSwap: 8 kB")).isEqualTo(32 * 1024L)
        assertThat(LinuxProcessMemory.residentBytes("Name: gone")).isNull()
    }
}
