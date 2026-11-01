package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspaceReconcilePlannerTest {
    private fun file(size: Long, mtime: Long = 1, key: String? = null) =
        WorkspaceScanNode(WorkspaceScanKind.FILE, size, mtime, key)
    private fun dir(key: String? = null, mtime: Long = 1) = WorkspaceScanNode(WorkspaceScanKind.DIRECTORY, 0, mtime, key)
    private fun other(key: String? = null) = WorkspaceScanNode(WorkspaceScanKind.UNSUPPORTED, 0, 1, key)
    private fun snapshot(vararg nodes: Pair<String, WorkspaceScanNode>, reserved: Map<String, WorkspaceScanNode> = emptyMap()) =
        WorkspaceTreeSnapshot(mapOf(*nodes), reserved)
    private fun indexed(uuid: String, path: String, size: Long = 0, hash: String = "", directory: Boolean = false, parent: String = "root") =
        IndexedWorkspaceNode(uuid, parent, path, directory, size, hash)

    private class Hasher(private val values: Map<String, String>) : (String) -> String? {
        val requested = mutableListOf<String>()
        override fun invoke(path: String): String? = values[path].also { requested += path }
    }

    @Test fun `命令创建的skills同名物理目录不进入虚拟工作区索引`() {
        val after = snapshot("/skills" to dir(), "/skills/a.md" to file(1))
        val plan = WorkspaceReconcilePlanner.plan(emptyList(), snapshot(), after, Hasher(mapOf("/skills/a.md" to "h")))
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.reservedChanges).containsExactly("/skills", "/skills/a.md")
    }

    @Test
    fun `未变化的文件不重算哈希`() {
        val tree = snapshot("/a.txt" to file(3, key = "1"), "/d" to dir("2"))
        val hasher = Hasher(emptyMap())
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a.txt", 3, "h"), indexed("d", "/d", directory = true)), tree, tree, hasher,
        )
        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.reservedChanges).isEmpty()
        assertThat(hasher.requested).isEmpty()
    }

    @Test
    fun `mtime 变化时重算哈希并只在内容不同时更新`() {
        val before = snapshot("/a.txt" to file(3, 1, "1"), "/same.txt" to file(3, 1, "2"))
        val after = snapshot("/a.txt" to file(3, 2, "1"), "/same.txt" to file(3, 2, "2"))
        val hasher = Hasher(mapOf("/a.txt" to "new", "/same.txt" to "h2"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a.txt", 3, "h1"), indexed("s", "/same.txt", 3, "h2")), before, after, hasher,
        )
        assertThat(plan.updates).containsExactly(PlannedWorkspaceUpdate("a", "/a.txt", 3, "new"))
        assertThat(hasher.requested).containsExactly("/a.txt", "/same.txt")
    }

    @Test
    fun `新增条目按深度排序且目录先于其中文件`() {
        val before = snapshot()
        val after = snapshot("/z.txt" to file(1), "/d/e/f.txt" to file(2), "/d" to dir(), "/d/e" to dir())
        val plan = WorkspaceReconcilePlanner.plan(
            emptyList(), before, after, Hasher(mapOf("/z.txt" to "z", "/d/e/f.txt" to "f")),
        )
        assertThat(plan.inserts.map { it.path }).containsExactly("/d", "/z.txt", "/d/e", "/d/e/f.txt").inOrder()
        assertThat(plan.inserts.first { it.path == "/d/e/f.txt" }).isEqualTo(PlannedWorkspaceInsert("/d/e/f.txt", false, 2, "f"))
    }

    @Test
    fun `删除目录时报告只列最上层`() {
        val before = snapshot("/d" to dir("1"), "/d/a.txt" to file(1, key = "2"), "/b.txt" to file(1, key = "3"))
        val after = snapshot()
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("d", "/d", directory = true), indexed("a", "/d/a.txt", 1, "h", parent = "d"), indexed("b", "/b.txt", 1, "h")),
            before, after, Hasher(emptyMap()),
        )
        assertThat(plan.deletes.map { it.uuid }).containsExactly("b", "d", "a")
        assertThat(WorkspaceChangeReport.from(plan).removed).containsExactly("/b.txt", "/d").inOrder()
    }

    @Test
    fun `同一 inode 换路径且内容未变视为移动并保留身份`() {
        val before = snapshot("/d" to dir("10"), "/d/a.txt" to file(5, 7, "11"))
        val after = snapshot("/e" to dir("10", mtime = 9), "/e/a.txt" to file(5, 7, "11"))
        val hasher = Hasher(emptyMap())
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("d", "/d", directory = true), indexed("a", "/d/a.txt", 5, "h", parent = "d")),
            before, after, hasher,
        )
        assertThat(plan.moves).containsExactly(
            PlannedWorkspaceMove("d", "/d", "/e"),
            PlannedWorkspaceMove("a", "/d/a.txt", "/e/a.txt"),
        ).inOrder()
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.deletes).isEmpty()
        assertThat(hasher.requested).isEmpty()
        assertThat(WorkspaceChangeReport.from(plan).moved).containsExactly("/d" to "/e")
    }

    @Test
    fun `inode 相同且改名后内容变化时保留身份并更新哈希`() {
        val before = snapshot("/a.txt" to file(5, 7, "11"))
        val after = snapshot("/b.txt" to file(6, 8, "11"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a.txt", 5, "h")), before, after, Hasher(mapOf("/b.txt" to "nb")),
        )
        assertThat(plan.moves).containsExactly(PlannedWorkspaceMove("a", "/a.txt", "/b.txt"))
        assertThat(plan.updates).containsExactly(PlannedWorkspaceUpdate("a", "/b.txt", 6, "nb"))
        assertThat(plan.deletes).isEmpty()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `改名后原路径新建同名文件时旧身份跟随inode`() {
        val before = snapshot("/a.txt" to file(3, 1, "old"))
        val after = snapshot("/a.txt" to file(3, 2, "new"), "/b.txt" to file(3, 1, "old"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a.txt", 3, "old-hash")), before, after,
            Hasher(mapOf("/a.txt" to "new-hash")),
        )
        assertThat(plan.moves).containsExactly(PlannedWorkspaceMove("a", "/a.txt", "/b.txt"))
        assertThat(plan.inserts).containsExactly(PlannedWorkspaceInsert("/a.txt", false, 3, "new-hash"))
        assertThat(plan.updates).isEmpty()
        assertThat(plan.deletes).isEmpty()
    }

    @Test
    fun `跨目录移动后修改内容仍保留身份`() {
        val before = snapshot("/a" to dir("d1"), "/b" to dir("d2"), "/a/f" to file(1, 1, "f"))
        val after = snapshot("/a" to dir("d1"), "/b" to dir("d2"), "/b/f" to file(2, 2, "f"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("d1", "/a", directory = true), indexed("d2", "/b", directory = true),
                indexed("f", "/a/f", 1, "old", parent = "d1")), before, after, Hasher(mapOf("/b/f" to "new")),
        )
        assertThat(plan.moves).containsExactly(PlannedWorkspaceMove("f", "/a/f", "/b/f"))
        assertThat(plan.updates).containsExactly(PlannedWorkspaceUpdate("f", "/b/f", 2, "new"))
        assertThat(plan.deletes).isEmpty()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `已索引的历史mnt目录和子项保持身份`() {
        val tree = snapshot("/mnt" to dir("d"), "/mnt/a.txt" to file(1, 1, "f"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("d", "/mnt", directory = true), indexed("f", "/mnt/a.txt", 1, "h", parent = "d")),
            tree, tree, Hasher(emptyMap()),
        )
        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `路径交换时两个UUID跟随各自inode`() {
        val before = snapshot("/a" to file(1, 1, "a"), "/b" to file(1, 1, "b"))
        val after = snapshot("/a" to file(1, 1, "b"), "/b" to file(1, 1, "a"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a", 1, "ha"), indexed("b", "/b", 1, "hb")), before, after, Hasher(emptyMap()),
        )
        assertThat(plan.moves).containsExactly(PlannedWorkspaceMove("a", "/a", "/b"), PlannedWorkspaceMove("b", "/b", "/a"))
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.deletes).isEmpty()
    }

    @Test
    fun `硬链接身份有歧义时不猜测移动来源`() {
        val before = snapshot("/a" to file(1, 1, "shared"), "/b" to file(1, 1, "shared"))
        val after = snapshot("/c" to file(1, 1, "shared"), "/d" to file(1, 1, "shared"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a", 1, "h"), indexed("b", "/b", 1, "h")), before, after,
            Hasher(mapOf("/c" to "h", "/d" to "h")),
        )
        assertThat(plan.moves).isEmpty()
        assertThat(plan.deletes.map { it.uuid }).containsExactly("a", "b")
        assertThat(plan.inserts.map { it.path }).containsExactly("/c", "/d")
    }

    @Test
    fun `文件替换为同名目录时删除旧条目并新增目录`() {
        val before = snapshot("/x" to file(1, key = "1"))
        val after = snapshot("/x" to dir("2"), "/x/y.txt" to file(1, key = "3"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("x", "/x", 1, "h")), before, after, Hasher(mapOf("/x/y.txt" to "y")),
        )
        assertThat(plan.deletes.map { it.uuid }).containsExactly("x")
        assertThat(plan.inserts.map { it.path to it.isDirectory }).containsExactly("/x" to true, "/x/y.txt" to false).inOrder()
    }

    @Test
    fun `保留路径挂载命名空间与新建的符号链接只作为警告`() {
        val before = snapshot(
            "/old-link" to other("1"),
            reserved = mapOf("/.infinity_versions" to dir("5"), "/.recycle_bin" to dir("6")),
        )
        val after = snapshot(
            "/old-link" to other("1"),
            "/new-link" to other("2"),
            "/mnt" to dir("3"),
            "/mnt/a.txt" to file(1, key = "4"),
            reserved = mapOf("/.infinity_versions" to dir("5"), "/.infinity_evil" to file(1, key = "7")),
        )
        val plan = WorkspaceReconcilePlanner.plan(emptyList(), before, after, Hasher(emptyMap()))
        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.unsupported).containsExactly("/new-link")
        assertThat(plan.reservedChanges).containsExactly("/.infinity_evil", "/.recycle_bin", "/mnt", "/mnt/a.txt")
    }

    @Test
    fun `已入索引的符号链接目标按删除处理`() {
        val before = snapshot("/a.txt" to file(1, key = "1"))
        val after = snapshot("/a.txt" to other("2"))
        val plan = WorkspaceReconcilePlanner.plan(listOf(indexed("a", "/a.txt", 1, "h")), before, after, Hasher(emptyMap()))
        assertThat(plan.deletes.map { it.uuid }).containsExactly("a")
        assertThat(plan.unsupported).containsExactly("/a.txt")
    }

    @Test
    fun `崩溃恢复时没有前快照，全部文件重算哈希且不识别移动`() {
        val after = snapshot("/a.txt" to file(1, key = "1"), "/b.txt" to file(1, key = "2"))
        val hasher = Hasher(mapOf("/a.txt" to "h", "/b.txt" to "hb"))
        val plan = WorkspaceReconcilePlanner.plan(
            listOf(indexed("a", "/a.txt", 1, "h"), indexed("c", "/c.txt", 1, "hb")), null, after, hasher,
        )
        assertThat(hasher.requested).containsExactly("/a.txt", "/b.txt")
        assertThat(plan.updates).isEmpty()
        assertThat(plan.moves).isEmpty()
        assertThat(plan.deletes.map { it.uuid }).containsExactly("c")
        assertThat(plan.inserts.map { it.path }).containsExactly("/b.txt")
        assertThat(plan.reservedChanges).isEmpty()
    }

    @Test
    fun `哈希时文件已消失按删除处理`() {
        val before = snapshot("/a.txt" to file(1, 1, "1"))
        val after = snapshot("/a.txt" to file(2, 2, "1"))
        val plan = WorkspaceReconcilePlanner.plan(listOf(indexed("a", "/a.txt", 1, "h")), before, after, Hasher(emptyMap()))
        assertThat(plan.deletes.map { it.uuid }).containsExactly("a")
        assertThat(plan.inserts).isEmpty()
    }
}
