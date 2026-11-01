package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.LinuxBind
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class LinuxGitSafeDirectoriesTest {
    @TempDir lateinit var temporary: Path

    @Test fun `工作区与挂载根各生成自身和子目录前缀两项并去重`() {
        assertEquals(listOf("/workspace", "/workspace/*", "/mnt/docs", "/mnt/docs/*", "/mnt/a/b", "/mnt/a/b/*"),
            LinuxGitSafeDirectories.values(listOf("/workspace", "/mnt/docs/", "/mnt/a/b", "/workspace")))
    }

    @Test fun `拒绝相对路径通配末段越界和控制字符`() {
        assertEquals(emptyList<String>(), LinuxGitSafeDirectories.values(listOf(
            "", "/", "mnt/a", "~/repo", "%(prefix)/x", "/mnt/*", "/mnt/*/", "/mnt/../etc", "/mnt/a\nb", "/mnt/a\u0000b",
        )))
    }

    @Test fun `特殊字符路径按原文传给git不做转义`() {
        val root = "/mnt/我的 仓库 \"q\" #1;x=y'z"
        assertEquals(listOf(root, "$root/*"), LinuxGitSafeDirectories.values(listOf(root)))
        val environment = mutableMapOf<String, String>()
        LinuxGitSafeDirectories.appendTo(environment, listOf(root))
        assertEquals(root, environment["GIT_CONFIG_VALUE_0"])
        assertEquals("$root/*", environment["GIT_CONFIG_VALUE_1"])
    }

    @Test fun `追加在已有配置项之后且不改动原条目`() {
        val environment = mutableMapOf("GIT_CONFIG_COUNT" to "2",
            "GIT_CONFIG_KEY_0" to "url.x.insteadOf", "GIT_CONFIG_VALUE_0" to "https://x/",
            "GIT_CONFIG_KEY_1" to "http.x.proxy", "GIT_CONFIG_VALUE_1" to "")
        LinuxGitSafeDirectories.appendTo(environment, listOf("/workspace"))
        assertEquals("4", environment["GIT_CONFIG_COUNT"])
        assertEquals("url.x.insteadOf", environment["GIT_CONFIG_KEY_0"])
        assertEquals("", environment["GIT_CONFIG_VALUE_1"])
        assertEquals("safe.directory", environment["GIT_CONFIG_KEY_2"])
        assertEquals("/workspace", environment["GIT_CONFIG_VALUE_2"])
        assertEquals("/workspace/*", environment["GIT_CONFIG_VALUE_3"])
    }

    @Test fun `无可信根或计数无效时保持环境不变`() {
        val empty = mutableMapOf<String, String>()
        LinuxGitSafeDirectories.appendTo(empty, emptyList())
        assertTrue(empty.isEmpty())
        val invalid = mutableMapOf("GIT_CONFIG_COUNT" to "x")
        LinuxGitSafeDirectories.appendTo(invalid, listOf("/workspace"))
        assertEquals(mapOf("GIT_CONFIG_COUNT" to "x"), invalid)
    }

    @Test fun `启动环境只信任本次绑定的工作区与挂载并接在凭据配置之后`() {
        val workspace = temporary.resolve("project").toFile().apply { mkdirs() }
        val mount = temporary.resolve("shared").toFile().apply { mkdirs() }
        val environment = builder().build(LinuxLaunchSpec(emptyList(), workspace, listOf(LinuxBind(mount, "/mnt/shared")),
            environment = mapOf("GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "http.x.proxy", "GIT_CONFIG_VALUE_0" to ""))).environment
        assertEquals("5", environment["GIT_CONFIG_COUNT"])
        assertEquals("http.x.proxy", environment["GIT_CONFIG_KEY_0"])
        assertEquals(listOf("/workspace", "/workspace/*", "/mnt/shared", "/mnt/shared/*"), (1..4).map {
            assertEquals("safe.directory", environment["GIT_CONFIG_KEY_$it"])
            environment.getValue("GIT_CONFIG_VALUE_$it")
        })
        assertFalse(builder().build(LinuxLaunchSpec(emptyList())).environment.containsKey("GIT_CONFIG_COUNT"))
    }

    /** 用本机 git 模拟属主不一致：配置只来自注入的环境，根下仓库可用，根外仓库仍被拒绝。 */
    @Test fun `本机git按注入配置放行根内仓库并拒绝根外仓库`() {
        val git = listOf("/usr/bin/git", "git").firstOrNull { runCatching { run(it, temporary.toFile(), emptyMap(), "--version").first == 0 }.getOrDefault(false) }
        assumeTrue(git != null, "需要本机 git")
        val base = temporary.toFile().canonicalFile
        val root = File(base, "共享 存储 \"q\" #1").apply { mkdirs() }
        val nested = File(root, "子 仓库").apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }
        listOf(root, nested, outside).forEach { assertEquals(0, run(git!!, it, isolated, "init", "-q").first) }

        val dubious = run(git!!, root, isolated, "status")
        assertNotEquals(0, dubious.first)
        assertTrue("dubious ownership" in dubious.second, dubious.second)

        val trusted = isolated.toMutableMap().also { LinuxGitSafeDirectories.appendTo(it, listOf(root.path)) }
        assertEquals(0, run(git, root, trusted, "status").first)
        assertEquals(0, run(git, nested, trusted, "status").first)
        assertTrue("dubious ownership" in run(git, outside, trusted, "status").second)
    }

    private val isolated = mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_TEST_ASSUME_DIFFERENT_OWNER" to "1", "LC_ALL" to "C")

    private fun run(git: String, directory: File, environment: Map<String, String>, vararg arguments: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(git, *arguments)).directory(directory).redirectErrorStream(true).apply {
            environment().keys.removeAll { it.startsWith("GIT_") }
            environment().putAll(environment)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private fun builder() = LinuxLaunchBuilder(PackagedProot(temporary.resolve("lib").toFile()),
        temporary.resolve("alpine").toFile().apply { mkdirs() }, temporary.resolve("cache").toFile(), tailnetEnvironment = { emptyMap() })
}
