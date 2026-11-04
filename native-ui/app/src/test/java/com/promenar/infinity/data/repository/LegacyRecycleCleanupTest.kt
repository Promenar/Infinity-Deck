package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class LegacyRecycleCleanupTest {
    @Test fun `活动子项保护历史目录并允许清理其他条目`() = runBlocking<Unit> {
        var complete = false
        val deleted = mutableListOf<String>()
        var entries = listOf(
            LegacyRecycleEntry("root", "bin", "/.recycle_bin", true, true),
            LegacyRecycleEntry("root", "keep", "/.recycle_bin/keep", false, false),
            LegacyRecycleEntry("root", "remove", "/.recycle_bin/remove", true, false),
        )
        val cleanup = LegacyRecycleCleanup(
            isComplete = { complete }, markComplete = { complete = true; true },
            loadEntries = { entries },
            deleteEntry = { _, id -> deleted += id; entries = entries.filterNot { it.uuid == id } },
            deleteDirectory = { true },
        )
        assertThat(cleanup.run().completed).isFalse()
        assertThat(complete).isFalse()
        assertThat(deleted).containsExactly("remove")
        assertThat(entries.map { it.uuid }).containsExactly("bin", "keep")
    }

    @Test fun `目录清理拒绝根链接且不跟随内部链接`() {
        val base = Files.createTempDirectory("legacy-cleanup-test")
        val outside = Files.createTempDirectory("legacy-outside-test")
        Files.writeString(outside.resolve("keep"), "safe")
        val recycle = Files.createDirectory(base.resolve("external-recycle"))
        Files.createSymbolicLink(recycle.resolve("link"), outside)
        assertThat(deleteLegacyRecycleDirectory(base, "external-recycle")).isTrue()
        assertThat(Files.exists(recycle, NOFOLLOW_LINKS)).isFalse()
        assertThat(Files.readString(outside.resolve("keep"))).isEqualTo("safe")
        Files.createSymbolicLink(base.resolve("file-manager-recycle"), outside)
        assertThat(deleteLegacyRecycleDirectory(base, "file-manager-recycle")).isFalse()
        assertThat(Files.readString(outside.resolve("keep"))).isEqualTo("safe")
    }

    @Test fun `不清除未纳入回收清理范围的技能暂存`() {
        val base = Files.createTempDirectory("legacy-skill-cleanup-test")
        val trash = Files.createDirectories(base.resolve("skill_state/trash"))
        val owned = Files.createDirectory(trash.resolve("123-user-fixture"))
        Files.writeString(owned.resolve("SKILL.md"), "old")
        assertThat(runCatching { deleteLegacyRecycleDirectory(base, "skill_state/trash") }.isFailure).isTrue()
        assertThat(Files.readString(owned.resolve("SKILL.md"))).isEqualTo("old")
    }
    @Test fun `只清理回收子树且成功后重复执行不再删除`() = runBlocking<Unit> {
        var complete = false
        val deleted = mutableListOf<String>()
        val directories = mutableListOf<String>()
        var entries = listOf(
            LegacyRecycleEntry("root", "normal", "/keep.txt", false, false),
            LegacyRecycleEntry("root", "bin", "/.recycle_bin", true, true),
            LegacyRecycleEntry("root", "child", "/.recycle_bin/child", true, false),
        )
        val cleanup = LegacyRecycleCleanup(
            isComplete = { complete },
            markComplete = { complete = true; true },
            loadEntries = { entries },
            deleteEntry = { _, id -> deleted += id; entries = entries.filterNot { it.recycled } },
            deleteDirectory = { name -> directories += name; true },
        )
        assertThat(cleanup.run().completed).isTrue()
        assertThat(cleanup.run().alreadyComplete).isTrue()
        assertThat(deleted).containsExactly("bin")
        assertThat(directories).containsExactly("external-recycle", "file-manager-recycle")
    }

    @Test fun `部分失败或异常路径不写完成标记并可在下次重试`() = runBlocking<Unit> {
        var complete = false
        var fail = true
        val deleted = mutableListOf<String>()
        var entries = listOf(
            LegacyRecycleEntry("root", "item", "/.recycle_bin/item", true, false),
            LegacyRecycleEntry("root", "bad", "/normal.txt", true, false),
        )
        val cleanup = LegacyRecycleCleanup(
            isComplete = { complete },
            markComplete = { complete = true; true },
            loadEntries = { entries },
            deleteEntry = { _, id -> if (fail) error("模拟失败") else { deleted += id; entries = entries.filterNot { it.uuid == id } } },
            deleteDirectory = { true },
        )
        assertThat(cleanup.run().completed).isFalse()
        assertThat(complete).isFalse()
        entries = entries.take(1)
        fail = false
        assertThat(cleanup.run().completed).isTrue()
        assertThat(deleted).containsExactly("item")
    }

    @Test fun `目录清理失败或标记写入失败均不报告完成`() = runBlocking<Unit> {
        for (directorySuccess in listOf(false, true)) {
            var marked = false
            val cleanup = LegacyRecycleCleanup(
                isComplete = { false },
                markComplete = { marked = true; false },
                loadEntries = { emptyList() },
                deleteEntry = { _, _ -> error("不应删除") },
                deleteDirectory = { directorySuccess },
            )
            assertThat(cleanup.run().completed).isFalse()
            assertThat(marked).isEqualTo(directorySuccess)
        }
    }
}
