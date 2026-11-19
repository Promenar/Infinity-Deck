package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxFileSystemTest {
    @Test fun `同步宿主路径查询可在同一单路IO调度器内完成`() {
        val io = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)
        val confined = LinuxFileSystemImpl(root, { ready }, TestLinuxFileMetadata, io)
        val result = java.util.concurrent.FutureTask {
            runBlocking { kotlinx.coroutines.withContext(io) { confined.hostFile("/") } }
        }
        Thread(result).apply { isDaemon = true }.start()
        assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(root)
    }

    private val base = Files.createTempDirectory("linux-files").toFile().canonicalFile
    private val root = File(base, "rootfs").apply { mkdir() }
    private val home = File(root, "root").apply { mkdir() }
    private var ready = true
    private val fs = LinuxFileSystemImpl(root, { ready }, TestLinuxFileMetadata)

    @After fun cleanup() { deleteLinuxFixture(base) }

    private fun file(path: String, text: String = "hello"): File = File(root, path.removePrefix("/")).apply {
        parentFile!!.mkdirs(); writeText(text)
    }
    private fun link(path: String, target: String) {
        val destination = File(root, path.removePrefix("/"))
        destination.parentFile!!.mkdirs()
        Files.createSymbolicLink(destination.toPath(), File(target).toPath())
    }
    private fun error(code: LinuxFileErrorCode, block: suspend () -> Unit) {
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking { block() } }
        assertThat(failure.code).isEqualTo(code)
    }

    @Test fun `绝对路径规范化且拒绝上溢与绑定路径`() = runBlocking<Unit> {
        file("/root/a")
        assertThat(fs.stat("/root/./x/../a")?.path).isEqualTo("/root/a")
        listOf("relative", "/../root", "/root/../../etc", "/workspace/a", "/mnt/a", "/root/\u0000").forEach {
            error(LinuxFileErrorCode.OUTSIDE_ROOTFS) { fs.stat(it) }
        }
        assertThat(fs.hostFile("/workspace/a")).isNull()
    }

    @Test fun `绝对相对链接与链接链按容器根解析`() = runBlocking<Unit> {
        file("/usr/share/a", "container")
        link("/root/absolute", "/usr/share/a")
        link("/root/relative", "../usr/share/a")
        link("/root/chain", "absolute")
        listOf("absolute", "relative", "chain").forEach {
            assertThat(fs.readText("/root/$it").bytes.toString(Charsets.UTF_8)).isEqualTo("container")
        }
        link("/root/dir", "/usr/share")
        assertThat(fs.stat("/root/dir")!!.targetIsDirectory).isTrue()
        assertThat(fs.list("/root/dir").single().path).isEqualTo("/root/dir/a")
    }

    @Test fun `越界链接环和绑定链接不会泄露宿主文件`() = runBlocking<Unit> {
        link("/root/out", "../../outside")
        link("/root/cycle", "cycle")
        link("/root/bind", "/workspace/file")
        listOf("out", "cycle", "bind").forEach {
            assertThat(fs.stat("/root/$it")!!.type).isEqualTo(LinuxFileType.SYMLINK)
            error(LinuxFileErrorCode.OUTSIDE_ROOTFS) { fs.readText("/root/$it") }
            assertThat(fs.hostFile("/root/$it")).isNull()
        }
        assertThat(fs.stat("/root/bind")!!.symlinkTarget).isEqualTo("/workspace/file")
    }

    @Test fun `真实proot双层宿主绝对链接还原且实体隐藏`() = runBlocking<Unit> {
        val final = file("/root/.l2s.data0001.0002", "hard data")
        link("/root/.l2s.data0001", final.path)
        link("/root/data", File(home, ".l2s.data0001").path)
        val entry = fs.list("/root").single()
        assertThat(entry.name).isEqualTo("data")
        assertThat(entry.type).isEqualTo(LinuxFileType.FILE)
        assertThat(entry.size).isEqualTo(9)
        assertThat(entry.symlinkTarget).isNull()
        assertThat(fs.readText("/root/data").bytes.toString(Charsets.UTF_8)).isEqualTo("hard data")
        assertThat(fs.copy(listOf("/root/data"), "/root")).isEmpty()
        assertThat(Files.isSymbolicLink(File(home, "data (2)").toPath())).isFalse()
        assertThat(File(home, "data (2)").readText()).isEqualTo("hard data")
    }

    @Test fun `系统祖先路径别名下的proot宿主链接也能解析和迁移`() = runBlocking<Unit> {
        val alias = File(base, "linked-files")
        Files.createSymbolicLink(alias.toPath(), base.toPath())
        try {
            val aliasedRoot = File(alias, "rootfs")
            file("/root/src/.l2s.data0001.0002", "aliased")
            link("/root/src/.l2s.data0001", File(aliasedRoot, "root/src/.l2s.data0001.0002").path)
            link("/root/src/data", File(aliasedRoot, "root/src/.l2s.data0001").path)
            val aliased = LinuxFileSystemImpl(aliasedRoot, { true }, TestLinuxFileMetadata)
            assertThat(aliased.readText("/root/src/data").bytes.toString(Charsets.UTF_8)).isEqualTo("aliased")
            aliased.rename("/root/src", "new")
            assertThat(aliased.readText("/root/new/data").bytes.toString(Charsets.UTF_8)).isEqualTo("aliased")
        } finally { Files.delete(alias.toPath()) }
    }

    @Test fun `属主权限取元数据且应用身份显示root`() = runBlocking<Unit> {
        file("/root/a")
        assertThat(fs.stat("/root/a")!!.owner).isEqualTo("root")
        assertThat(fs.stat("/root/a")!!.group).isEqualTo("root")
        val numeric = LinuxFileSystemImpl(root, { true }, object : LinuxFileMetadata by TestLinuxFileMetadata {
            override fun read(file: File) = LinuxFileIdentity(0x1ed, 42, 43)
        }).stat("/root/a")!!
        assertThat(numeric.owner).isEqualTo("42")
        assertThat(numeric.group).isEqualTo("43")
        assertThat(fs.chmod("/root/a", 0x1e8).mode).isEqualTo(0x1e8)
        // macOS 沙箱会剥除 setuid，低 12 位的投影通过元数据接口验证，真实 chmod 由设备夹具覆盖。
        var specialMode = 0x1e8
        val special = LinuxFileSystemImpl(root, { true }, object : LinuxFileMetadata by TestLinuxFileMetadata {
            override fun read(file: File) = TestLinuxFileMetadata.read(file).copy(mode = specialMode)
            override fun chmod(file: File, mode: Int) { specialMode = mode }
        })
        assertThat(special.chmod("/root/a", 0x9ed).mode).isEqualTo(0x9ed)
    }

    @Test fun `原子写保留权限冲突时原文保留且清理临时文件`() = runBlocking<Unit> {
        file("/root/a", "before")
        fs.chmod("/root/a", 0x1a0)
        val before = fs.stat("/root/a")!!
        error(LinuxFileErrorCode.CHANGED) { fs.writeText("/root/a", "bad", before.modifiedMs - 1) }
        assertThat(File(home, "a").readText()).isEqualTo("before")
        val after = fs.writeText("/root/a", "after", before.modifiedMs)
        assertThat(after.mode).isEqualTo(0x1a0)
        assertThat(home.list()!!.toList()).containsExactly("a")
        link("/root/link", "a")
        fs.writeText("/root/link", "linked")
        assertThat(Files.isSymbolicLink(File(home, "link").toPath())).isTrue()
        assertThat(File(home, "a").readText()).isEqualTo("linked")
    }

    @Test fun `重命名移动和删除作用于链接本身且复制保留链接与权限`() = runBlocking<Unit> {
        file("/root/a", "keep")
        fs.chmod("/root/a", 0x1e8)
        fs.createDirectory("/root", "dest")
        link("/root/link", "a")
        fs.rename("/root/link", "renamed")
        assertThat(fs.copy(listOf("/root/renamed", "/root/a"), "/root/dest")).isEmpty()
        assertThat(Files.readSymbolicLink(File(home, "dest/renamed").toPath()).toString()).isEqualTo("a")
        assertThat(fs.stat("/root/dest/a")!!.mode).isEqualTo(0x1e8)
        assertThat(fs.move(listOf("/root/renamed"), "/root/dest")["/root/renamed"]).isEqualTo(LinuxFileErrorCode.NAME_EXISTS)
        fs.createDirectory("/root", "other")
        assertThat(fs.move(listOf("/root/renamed"), "/root/other")).isEmpty()
        assertThat(fs.delete(listOf("/root/other"))).isEmpty()
        assertThat(File(home, "a").readText()).isEqualTo("keep")
    }

    @Test fun `目录不能复制移动到自身内部包括目录链接`() = runBlocking<Unit> {
        fs.createDirectory("/root", "src")
        fs.createDirectory("/root/src", "child")
        link("/root/alias", "/root/src/child")
        listOf("/root/src", "/root/src/child", "/root/alias").forEach {
            assertThat(fs.copy(listOf("/root/src"), it)["/root/src"]).isEqualTo(LinuxFileErrorCode.TARGET_INSIDE_SOURCE)
            assertThat(fs.move(listOf("/root/src"), it)["/root/src"]).isEqualTo(LinuxFileErrorCode.TARGET_INSIDE_SOURCE)
        }
    }

    @Test fun `重命名和批量操作的目标也不能进入运行时绑定路径`() = runBlocking<Unit> {
        fs.createDirectory("/root", "workspace")
        fs.createDirectory("/root", "mnt")
        error(LinuxFileErrorCode.OUTSIDE_ROOTFS) { fs.rename("/root", "workspace") }
        assertThat(fs.move(listOf("/root/workspace"), "/")["/root/workspace"]).isEqualTo(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        assertThat(fs.copy(listOf("/root/mnt"), "/")["/root/mnt"]).isEqualTo(LinuxFileErrorCode.OUTSIDE_ROOTFS)
    }

    @Test fun `移动重命名和删除目录不会破坏目录内外的假硬链接`() = runBlocking<Unit> {
        fs.createDirectory("/root", "src")
        fs.createDirectory("/root", "target")
        val final = file("/root/src/.l2s.data0001.0002", "shared")
        link("/root/src/.l2s.data0001", final.path)
        val middle = File(home, "src/.l2s.data0001").path
        link("/root/src/data", middle)
        link("/root/external", middle)
        fs.rename("/root/src", "renamed")
        assertThat(fs.readText("/root/renamed/data").bytes.toString(Charsets.UTF_8)).isEqualTo("shared")
        assertThat(fs.readText("/root/external").bytes.toString(Charsets.UTF_8)).isEqualTo("shared")
        assertThat(fs.move(listOf("/root/renamed"), "/root/target")).isEmpty()
        assertThat(fs.readText("/root/target/renamed/data").bytes.toString(Charsets.UTF_8)).isEqualTo("shared")
        assertThat(fs.delete(listOf("/root/target/renamed"))).isEmpty()
        assertThat(fs.readText("/root/external").bytes.toString(Charsets.UTF_8)).isEqualTo("shared")
    }

    @Test fun `未就绪失败与新建重名校验及分段读取`() = runBlocking<Unit> {
        fs.createFile("/root", "a")
        error(LinuxFileErrorCode.NAME_EXISTS) { fs.createFile("/root", "a") }
        error(LinuxFileErrorCode.NAME_INVALID) { fs.createDirectory("/root", "../bad") }
        fs.writeText("/root/a", "012345")
        assertThat(fs.readBytes("/root/a", 2, 3).toString(Charsets.UTF_8)).isEqualTo("234")
        ready = false
        error(LinuxFileErrorCode.NOT_READY) { fs.list("/root") }
        assertThat(fs.hostFile("/root/a")).isNull()
    }

    @Test fun `读取截断前缀保留实际大小并分析已读部分`() = runBlocking<Unit> {
        val source = file("/root/text", "a\n中文\nlast")
        val content = fs.readText("/root/text", 4)
        assertThat(content.truncated).isTrue()
        assertThat(content.bytes.size).isEqualTo(4)
        assertThat(content.totalSize).isEqualTo(source.length())
        assertThat(content.isBinary).isFalse()
        assertThat(content.lineCount).isEqualTo(2)
        assertThat(content.modifiedMs).isEqualTo(source.lastModified())
    }

    @Test fun `复制时来源变化返回CHANGED且清理只读子目录`() = runBlocking<Unit> {
        file("/root/src/first/a", "a")
        file("/root/src/last", "before")
        fs.chmod("/root/src/first", 0x16d)
        fs.createDirectory("/root", "dest")
        var changed = false
        val racing = LinuxFileSystemImpl(root, { true }, object : LinuxFileMetadata by TestLinuxFileMetadata {
            override fun read(file: File): LinuxFileIdentity {
                if (file.name == "last" && !changed) { changed = true; file.writeText("changed-long-content") }
                return TestLinuxFileMetadata.read(file)
            }
        })
        assertThat(racing.copy(listOf("/root/src"), "/root/dest")["/root/src"]).isEqualTo(LinuxFileErrorCode.CHANGED)
        assertThat(File(home, "dest").list()).isEmpty()
        File(home, "src/first").setWritable(true)
    }
}

internal object TestLinuxFileMetadata : LinuxFileMetadata {
    override val appUid = 10001L
    override val appGid = 10001L
    override fun read(file: File) = LinuxFileIdentity((Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Int) and 0xfff, appUid, appGid)
    override fun chmod(file: File, mode: Int) { Files.setAttribute(file.toPath(), "unix:mode", mode, NOFOLLOW_LINKS) }
}

internal fun deleteLinuxFixture(directory: File) {
    if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) Files.walk(directory.toPath()).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
