package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.LinuxBind
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimeZone

class LinuxLaunchTest {
    @Test fun `默认Linux工作区别名存在且无工作区时不虚构变量`() {
        val workspace = temporary.resolve("project").toFile().apply { mkdirs() }
        val environment = builder().build(LinuxLaunchSpec(emptyList(), workspace)).environment
        assertEquals("/workspace", environment["INFINITY_WORKSPACE"])
        assertEquals("/workspace", environment["NEXARA_WORKSPACE"])
        val bare = builder().build(LinuxLaunchSpec(emptyList())).environment
        assertFalse(bare.containsKey("INFINITY_WORKSPACE"))
        assertFalse(bare.containsKey("NEXARA_WORKSPACE"))
    }

    @Test fun `新旧环境变量在最终覆盖后保持一致`() {
        val workspace = temporary.resolve("project").toFile().apply { mkdirs() }
        val environment = builder().build(LinuxLaunchSpec(emptyList(), workspace, environment = mapOf(
            "INFINITY_WORKSPACE" to "/branches/feature", "INFINITY_WORKSPACE_KIND" to "project",
            "INFINITY_SKILLS" to "/mnt/skills", "NEXARA_WORKSPACE" to "/stale",
        ))).environment
        for (suffix in listOf("WORKSPACE", "WORKSPACE_KIND", "SKILLS")) {
            assertNotNull(environment["INFINITY_$suffix"])
            assertEquals(environment["INFINITY_$suffix"], environment["NEXARA_$suffix"])
        }
        assertEquals("/branches/feature", environment["NEXARA_WORKSPACE"])
    }

    @TempDir lateinit var temporary: Path
    private val root get() = temporary.resolve("alpine").toFile().apply { mkdirs() }
    private fun builder() = LinuxLaunchBuilder(PackagedProot(temporary.resolve("lib").toFile()), root,
        temporary.resolve("cache").toFile(), tailnetEnvironment = { mapOf("HTTPS_PROXY" to "http://127.0.0.1:8080") })

    @Test fun `构造独立参数工作区和额外挂载`() {
        val workspace = temporary.resolve("work space").toFile().apply { mkdirs() }
        val mount = temporary.resolve("data").toFile().apply { mkdirs() }
        val launch = builder().build(LinuxLaunchSpec(listOf("uname", "-a"), workspace, listOf(LinuxBind(mount, "/mnt/data"))))
        assertEquals(listOf("--kill-on-exit", "-0", "--link2symlink", "-L", "--sysvipc", "-r", root.path), launch.arguments.take(7))
        assertTrue(launch.arguments.contains("${workspace.path}:/workspace"))
        assertTrue(launch.arguments.contains("${mount.path}:/mnt/data"))
        assertEquals(listOf("-w", "/workspace", "uname", "-a"), launch.arguments.takeLast(4))
        assertEquals(workspace, launch.hostWorkingDirectory)
    }

    @Test fun `含冒号的Linux工作区通过内部别名绑定且cwd保持实际路径`() {
        val workspace = File(root, "opt/app:demo").apply { mkdirs() }
        val launch = builder().build(LinuxLaunchSpec(listOf("pwd"), workspace, workingDirectory = "/opt/app:demo"))
        val bind = launch.arguments.single { it.endsWith(":/workspace") }
        val source = java.nio.file.Paths.get(bind.removeSuffix(":/workspace"))
        assertFalse(':' in source.toString())
        assertTrue(Files.isSymbolicLink(source))
        assertEquals(workspace.toPath(), Files.readSymbolicLink(source))
        assertEquals(listOf("-w", "/opt/app:demo", "pwd"), launch.arguments.takeLast(3))
        assertEquals(bind, builder().build(LinuxLaunchSpec(listOf("pwd"), workspace)).arguments.single { it.endsWith(":/workspace") })
        Files.delete(source)
        Files.createSymbolicLink(source, temporary)
        assertThrows(IllegalArgumentException::class.java) { builder().build(LinuxLaunchSpec(listOf("pwd"), workspace)) }
    }

    @Test fun `拒绝mnt越界路径冒号和无法保证的只读挂载`() {
        val mount = temporary.toFile()
        listOf("/mnt", "/mnt/../etc", "/mnt/a/../../root", "/mnt2/a", "mnt/a", "/mnt/a:b", "/mnt/a\\b").forEach {
            assertThrows(IllegalArgumentException::class.java) { builder().build(LinuxLaunchSpec(emptyList(), mounts = listOf(LinuxBind(mount, it)))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder().build(LinuxLaunchSpec(emptyList(), mounts = listOf(LinuxBind(mount, "/mnt/data", true))))
        }
    }

    @Test fun `mnt下名为mnt的目录也是合法挂载点`() {
        val launch = builder().build(LinuxLaunchSpec(emptyList(), mounts = listOf(LinuxBind(temporary.toFile(), "/mnt/mnt"))))
        assertTrue(launch.arguments.contains("${temporary}:/mnt/mnt"))
    }

    @Test fun `会话私有目录每次独立绑定且可以不同于主工作区`() {
        val workspace = temporary.resolve("project").toFile().apply { mkdirs() }
        val homes = listOf("chat-a", "chat-b").map { temporary.resolve(it).toFile().apply { mkdirs() } }
        val launches = homes.map { home -> builder().build(LinuxLaunchSpec(listOf("pwd"), workspace, sessionHome = home)) }
        launches.forEachIndexed { index, launch ->
            assertTrue(launch.arguments.contains("${workspace.path}:/workspace"))
            assertTrue(launch.arguments.contains("${homes[index].path}:/home/session"))
            assertFalse(launch.arguments.contains("${homes[1 - index].path}:/home/session"))
        }
        for (guest in listOf("/home/session", "/home/session/other", "/home/../root")) {
            assertThrows(IllegalArgumentException::class.java) {
                builder().build(LinuxLaunchSpec(emptyList(), mounts = listOf(LinuxBind(homes[0], guest))))
            }
        }
    }

    @Test fun `白名单环境不继承宿主且用户覆盖最后生效`() {
        val launch = builder().build(LinuxLaunchSpec(emptyList(), environment = mapOf("HOME" to "/root/custom", "HTTPS_PROXY" to "custom")))
        assertEquals("/root/custom", launch.environment["HOME"])
        assertEquals("custom", launch.environment["HTTPS_PROXY"])
        assertEquals("1", launch.environment["PIP_BREAK_SYSTEM_PACKAGES"])
        assertEquals("C.UTF-8", launch.environment["LANG"])
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "HOME", "PATH", "LANG", "TERM", "TZ", "PIP_BREAK_SYSTEM_PACKAGES", "SHELL", "HTTPS_PROXY"), launch.environment.keys)
        assertFalse(launch.environment.containsKey("INFINITY_WORKSPACE"))
        assertEquals(listOf("-w", "/root", "/bin/sh", "-l"), launch.arguments.takeLast(4))
        assertEquals("/bin/sh", launch.environment["SHELL"])
    }

    @Test fun `有bash时选择登录bash且支持容器内工作目录`() {
        File(root, "bin").mkdirs()
        File(root, "bin/bash").writeText("shell")
        val launch = builder().build(LinuxLaunchSpec(emptyList(), workingDirectory = "/tmp"))
        assertEquals(listOf("-w", "/tmp", "/bin/bash", "-l"), launch.arguments.takeLast(4))
        assertEquals("/bin/bash", launch.environment["SHELL"])
        File(root, "bin/bash").delete()
        Files.createSymbolicLink(File(root, "bin/bash").toPath(), temporary.resolve("outside"))
        assertEquals(listOf("/bin/sh", "-l"), builder().build(LinuxLaunchSpec(emptyList())).arguments.takeLast(2))
    }

    @Test fun `每次启动读取设备时区且显式TZ优先`() {
        val original = TimeZone.getDefault()
        try {
            val launcher = builder()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("<+08>-8", launcher.build(LinuxLaunchSpec(emptyList())).environment["TZ"])
            TimeZone.setDefault(TimeZone.getTimeZone("America/Phoenix"))
            assertEquals("<-07>7", launcher.build(LinuxLaunchSpec(emptyList())).environment["TZ"])
            assertEquals("UTC0", launcher.build(LinuxLaunchSpec(emptyList(), environment = mapOf("TZ" to "UTC0"))).environment["TZ"])
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun `启动前仅清理一次旧全局忽略块并保留用户后续修改`() {
        val ignore = File(root, "root/.config/git/ignore")
        ignore.parentFile!!.mkdirs()
        val managed = "# >>> Infinity Deck workspace internal files >>>\n/.infinity*\n/.recycle_bin/\n# <<< Infinity Deck workspace internal files <<<\n"
        ignore.writeText("*.local\n" + managed + "# 用户规则")
        builder().build(LinuxLaunchSpec(emptyList()))
        assertEquals("*.local\n# 用户规则", ignore.readText())
        val expected = "*.user\n" + managed
        ignore.writeText(expected)
        val modified = ignore.lastModified()
        builder().build(LinuxLaunchSpec(emptyList()))
        assertEquals(expected, ignore.readText())
        assertEquals(modified, ignore.lastModified())
    }

    @Test fun `修复期启动显式禁止维护HOME配置`() {
        val ignore = File(root, "root/.config/git/ignore").apply { parentFile!!.mkdirs(); writeText("user-only\n") }
        builder().build(LinuxLaunchSpec(emptyList()), maintainHomeConfiguration = false)
        assertEquals("user-only\n", ignore.readText())
        ignore.delete()
        builder().build(LinuxLaunchSpec(emptyList()), maintainHomeConfiguration = false)
        assertFalse(ignore.exists())
    }

    @Test fun `打包proot必须两个文件均可执行`() {
        val lib = temporary.resolve("lib").toFile().apply { mkdirs() }
        val proot = PackagedProot(lib)
        assertFalse(proot.isAvailable)
        listOf(proot.executable, proot.loader).forEach { it.writeText("elf"); it.setExecutable(true) }
        assertTrue(proot.isAvailable)
        proot.loader.delete()
        assertFalse(proot.isAvailable)
    }

    @Test fun `guest绝对链接按容器根解析且链接循环被拒绝`() {
        File(root, "bin").mkdirs()
        File(root, "bin/busybox").writeText("shell")
        Files.createSymbolicLink(File(root, "bin/sh").toPath(), Path.of("/bin/busybox"))
        assertTrue(LinuxRootfsFiles.isGuestFile(root, File(root, "bin/sh")))
        Files.createSymbolicLink(File(root, "bin/bash").toPath(), Path.of("/bin/bash"))
        assertFalse(LinuxRootfsFiles.isGuestFile(root, File(root, "bin/bash")))
    }

    @Test fun `链接中的上级目录不能跳过缺失目录或普通文件`() {
        File(root, "bin").mkdirs()
        File(root, "bin/busybox").writeText("shell")
        val link = File(root, "bin/sh").toPath()
        Files.createSymbolicLink(link, Path.of("/missing/../bin/busybox"))
        assertFalse(LinuxRootfsFiles.isGuestFile(root, link.toFile()))
        Files.delete(link)
        Files.createSymbolicLink(link, Path.of("/bin/busybox/../busybox"))
        assertFalse(LinuxRootfsFiles.isGuestFile(root, link.toFile()))
    }

    @Test fun `占用统计跳过不可读目录与链接而不整体失败`() {
        File(root, "usr").mkdirs()
        File(root, "usr/data").writeBytes(ByteArray(100))
        Files.createSymbolicLink(File(root, "usr/link").toPath(), Path.of("/usr/data"))
        val locked = File(root, "tmp/locked").apply { mkdirs() }
        File(locked, "hidden").writeBytes(ByteArray(7))
        locked.setReadable(false, false)
        try {
            assertEquals(100, LinuxRootfsFiles.size(root))
        } finally { locked.setReadable(true, false) }
    }
}
