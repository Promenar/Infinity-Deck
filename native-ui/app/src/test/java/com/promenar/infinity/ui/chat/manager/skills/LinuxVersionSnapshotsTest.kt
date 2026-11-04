package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxVersionSnapshotsTest {
    private val cache = LinuxVersionSnapshots(1024)

    @Test fun `私有目录删除只清理对应工作区及后代`() {
        val child = LinuxVersionSnapshots.Key("one", "/docs/a", "h", privateRoot = "root-one")
        val sibling = LinuxVersionSnapshots.Key("one", "/docs-other/a", "h", privateRoot = "root-one")
        val otherRoot = LinuxVersionSnapshots.Key("two", "/docs/a", "h", privateRoot = "root-two")
        val system = LinuxVersionSnapshots.Key("system", "/docs/a", "h", rootfs = true)
        listOf(child, sibling, otherRoot, system).forEach { cache.remember(it, byteArrayOf(1), cache.generation()) }
        cache.forgetPrivate("root-one", "/docs")
        assertThat(cache.get(child)).isNull()
        listOf(sibling, otherRoot, system).forEach { assertThat(cache.get(it)).isEqualTo(byteArrayOf(1)) }
    }

    @Test fun `Linux目录删除清理所有会话命名空间的同一路径`() {
        val first = LinuxVersionSnapshots.Key("one", "/root/docs/a", "h", rootfs = true)
        val second = LinuxVersionSnapshots.Key("two", "/root/docs/a", "h", rootfs = true)
        val sibling = LinuxVersionSnapshots.Key("two", "/root/docs2/a", "h", rootfs = true)
        listOf(first, second, sibling).forEach { cache.remember(it, byteArrayOf(2), cache.generation()) }
        cache.forgetRootfs("/root/docs")
        assertThat(cache.get(first)).isNull()
        assertThat(cache.get(second)).isNull()
        assertThat(cache.get(sibling)).isEqualTo(byteArrayOf(2))
    }

    @Test fun `删除前启动的读取不能在删除后重建快照`() {
        val oldGeneration = cache.generation()
        cache.forgetRootfs("/root/a")
        val key = LinuxVersionSnapshots.Key("one", "/root/a", "h", rootfs = true)
        cache.remember(key, byteArrayOf(1), oldGeneration)
        assertThat(cache.get(key)).isNull()
        cache.remember(key, byteArrayOf(2), cache.generation())
        assertThat(cache.get(key)).isEqualTo(byteArrayOf(2))
    }

    @Test fun `快照复制字节并限制数量与单文件大小`() {
        val key = LinuxVersionSnapshots.Key("one", "/root/a", "h", rootfs = true)
        val bytes = byteArrayOf(3)
        cache.remember(key, bytes, cache.generation())
        bytes[0] = 4
        cache.get(key)!![0] = 5
        assertThat(cache.get(key)).isEqualTo(byteArrayOf(3))
        val oversized = LinuxVersionSnapshots.Key("one", "/root/large", "h", rootfs = true)
        cache.remember(oversized, ByteArray(1025), cache.generation())
        assertThat(cache.get(oversized)).isNull()
        repeat(64) { cache.remember(LinuxVersionSnapshots.Key("one", "/root/$it", "h", rootfs = true), byteArrayOf(1), cache.generation()) }
        assertThat(cache.get(key)).isNull()
    }
}
