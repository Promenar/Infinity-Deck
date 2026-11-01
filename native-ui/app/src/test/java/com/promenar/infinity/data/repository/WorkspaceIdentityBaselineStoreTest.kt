package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import org.junit.After
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class WorkspaceIdentityBaselineStoreTest {
    private val sandbox = Files.createTempDirectory("identity-store").toFile()
    private val physical = File(sandbox, "workspace").apply { mkdir() }
    private val directory = File(sandbox, "private-baselines")
    private val root = FileEntry("root", "root", null, "root", "identity", isDirectory = true,
        physicalRootPath = physical.path, materializedPath = "/", createdAt = 1, updatedAt = 1)
    private val snapshot = WorkspaceTreeSnapshot(mapOf(
        "/a.txt" to WorkspaceScanNode(WorkspaceScanKind.FILE, 3, 7, "(dev=1,ino=2)"),
        "/dir" to WorkspaceScanNode(WorkspaceScanKind.DIRECTORY, 0, 8, "(dev=1,ino=3)"),
    ), emptyMap())
    private val events = mutableListOf<String>()
    private val store = WorkspaceIdentityBaselineStore(directory, diagnostic = events::add)

    @After fun tearDown() { sandbox.deleteRecursively() }

    @Test fun `新实例完整读取持久身份且替换不留下临时文件`() {
        store.save(root, snapshot)
        val changed = snapshot.copy(reserved = mapOf("/.infinity_versions" to WorkspaceScanNode(WorkspaceScanKind.DIRECTORY, 0, 9, "versions")))
        store.save(root, changed)
        assertThat(WorkspaceIdentityBaselineStore(directory).load(root)).isEqualTo(changed)
        assertThat(directory.listFiles()!!.map { it.extension }).containsExactly("gz")
        assertThat(events).isEmpty()
    }

    @Test fun `不支持的文件名也能保存在快照中而不使整份基线失效`() {
        val tree = snapshot.copy(nodes = snapshot.nodes + ("/bad\\name" to WorkspaceScanNode(WorkspaceScanKind.UNSUPPORTED, 0, 1, "bad")))
        store.save(root, tree)
        assertThat(store.load(root)).isEqualTo(tree)
        assertThat(events).isEmpty()
    }

    @Test fun `根UUID相同但认领身份变化时丢弃基线`() {
        store.save(root, snapshot)
        assertThat(store.load(root.copy(hash = "different-identity"))).isNull()
        assertThat(events).containsExactly("root_identity")
        assertThat(directory.listFiles()).isEmpty()
    }

    @Test fun `根物理目录被替换但认领字符串不变时丢弃基线`() {
        store.save(root, snapshot)
        check(physical.renameTo(File(sandbox, "old-root")))
        check(physical.mkdir())
        assertThat(store.load(root)).isNull()
        assertThat(events).containsExactly("root_identity")
    }

    @Test fun `格式版本不匹配时无异常退回空基线`() {
        store.save(root, snapshot)
        DataOutputStream(GZIPOutputStream(directory.listFiles()!!.single().outputStream())).use {
            it.writeInt(0x49574249)
            it.writeInt(999)
        }
        assertThat(store.load(root)).isNull()
        assertThat(events).containsExactly("format")
    }

    @Test fun `文件截断或压缩校验损坏时丢弃基线`() {
        store.save(root, snapshot)
        val file = directory.listFiles()!!.single()
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertThat(store.load(root)).isNull()
        store.save(root, snapshot)
        val corrupted = file.readBytes().apply { this[lastIndex - 7] = (this[lastIndex - 7].toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        assertThat(store.load(root)).isNull()
        assertThat(events).containsExactly("corrupted", "corrupted")
    }

    @Test fun `一万六千节点压缩基线小于一MiB并完整回读`() {
        val nodes = (1..16_000).associate { index ->
            "/src/package-$index/file-$index.kt" to WorkspaceScanNode(WorkspaceScanKind.FILE, index.toLong(),
                1_700_000_000_000L + index, "(dev=1,ino=$index)")
        }
        val large = WorkspaceTreeSnapshot(nodes, emptyMap())
        store.save(root, large)
        assertThat(directory.listFiles()!!.single().length()).isLessThan(1024L * 1024)
        assertThat(WorkspaceIdentityBaselineStore(directory).load(root)).isEqualTo(large)
    }
}
