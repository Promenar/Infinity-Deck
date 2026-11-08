package com.promenar.infinity.data.linux

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LinuxLaunchGitIgnoreTest {
    @TempDir lateinit var temporary: Path
    private val root get() = temporary.resolve("rootfs").toFile().apply { mkdirs() }
    private val managed = "# >>> Infinity Deck workspace internal files >>>\n/.recycle_bin/\n/.infinity*\n# <<< Infinity Deck workspace internal files <<<\n"
    private fun cleanup(environment: Map<String, String> = emptyMap()) = LinuxLaunchGitIgnore.cleanupOnce(root, environment)
    private fun ignore(relative: String = "root/.config/git/ignore", content: String = managed): File =
        File(root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    @Test fun `清理仅移除完整受管块并逐字保留用户前后文本`() {
        val user = "*.local\r\n# 用户规则\r\n"
        assertEquals(user + "*.user\n", LinuxLaunchGitIgnore.merge(user + managed + "*.user\n"))
        assertEquals(user + "*.user\n", LinuxLaunchGitIgnore.merge(user + managed.replace("\n", "\r\n") + "*.user\n"))
        assertEquals(user, LinuxLaunchGitIgnore.merge(user))
        assertEquals("", LinuxLaunchGitIgnore.merge(managed))
    }

    @Test fun `不完整重复或倒置的标记保持原文件且允许修正后重试`() {
        assertNull(LinuxLaunchGitIgnore.merge(managed.substringBefore("# <<<")))
        assertNull(LinuxLaunchGitIgnore.merge(managed + managed))
        assertNull(LinuxLaunchGitIgnore.merge(managed.lines().reversed().joinToString("\n")))
        val file = ignore(content = managed.substringBefore("# <<<"))
        assertFalse(cleanup())
        assertEquals(managed.substringBefore("# <<<"), file.readText())
        file.writeText(managed)
        assertTrue(cleanup())
        assertEquals("", file.readText())
    }

    @Test fun `保留CRLF用户内容非UTF8字节和现有权限`() {
        val file = ignore()
        val prefix = byteArrayOf(0xff.toByte(), 0x0d, 0x0a)
        val suffix = byteArrayOf(0x80.toByte(), 0x0d, 0x0a)
        file.writeBytes(prefix + managed.replace("\n", "\r\n").toByteArray() + suffix)
        val permissions = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        assertTrue(cleanup())
        assertArrayEquals(prefix + suffix, file.readBytes())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        assertFalse(cleanup())
    }

    @Test fun `清理只执行一次且不创建缺失的ignore文件`() {
        assertFalse(cleanup())
        assertFalse(File(root, "root/.config/git/ignore").exists())
        val file = ignore()
        assertFalse(cleanup())
        assertEquals(managed, file.readText())
    }

    @Test fun `成功清理后用户重新添加同名区块不再改写`() {
        val file = ignore(content = "*.local\n" + managed)
        assertTrue(cleanup())
        assertEquals("*.local\n", file.readText())
        file.writeText(managed + "*.user\n")
        assertFalse(cleanup())
        assertEquals(managed + "*.user\n", file.readText())
    }

    @Test fun `HOME与XDG覆盖按各目标独立清理且非法路径不写入`() {
        val home = ignore("home/user/.config/git/ignore")
        val xdg = ignore("custom/config/git/ignore")
        assertTrue(cleanup(mapOf("HOME" to "/home/user")))
        assertEquals("", home.readText())
        assertTrue(cleanup(mapOf("XDG_CONFIG_HOME" to "/custom/config")))
        assertEquals("", xdg.readText())
        assertFalse(cleanup(mapOf("XDG_CONFIG_HOME" to "relative")))
        assertFalse(cleanup(mapOf("XDG_CONFIG_HOME" to "/../../outside")))
    }

    @Test fun `不跟随目录或文件链接也不覆盖用户Git配置`() {
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        File(root, "root").mkdirs()
        Files.createSymbolicLink(File(root, "root/.config").toPath(), outside.toPath())
        assertFalse(cleanup())
        assertFalse(File(outside, "git/ignore").exists())
        Files.delete(File(root, "root/.config").toPath())
        val config = File(root, "root/.gitconfig").apply { writeText("[core]\n\texcludesFile = /custom-ignore\n") }
        val file = File(root, "root/.config/git/ignore")
        file.parentFile!!.mkdirs()
        val destination = File(outside, "ignore").apply { writeText(managed) }
        Files.createSymbolicLink(file.toPath(), destination.toPath())
        assertFalse(cleanup())
        assertEquals(managed, destination.readText())
        Files.delete(file.toPath())
        file.writeText(managed)
        assertTrue(cleanup())
        assertEquals("", file.readText())
        assertEquals("[core]\n\texcludesFile = /custom-ignore\n", config.readText())
    }

    @Test fun `实际git add采用用户忽略规则且允许强制跟踪内部目录`() {
        val file = ignore(content = "*.local\n" + managed)
        assertTrue(cleanup())
        assertEquals("*.local\n", file.readText())
        val repo = temporary.resolve("repo").toFile().apply { mkdirs() }
        fun git(vararg arguments: String): String {
            val builder = ProcessBuilder(listOf("git", "-C", repo.path) + arguments)
            val path = System.getenv("PATH") ?: "/usr/bin:/bin"
            builder.environment().clear()
            builder.environment().putAll(mapOf("PATH" to path, "HOME" to File(root, "root").path,
                "XDG_CONFIG_HOME" to File(root, "root/.config").path, "GIT_CONFIG_NOSYSTEM" to "1"))
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            return output
        }
        git("init")
        val exclude = File(repo, ".git/info/exclude").readBytes()
        listOf(".infinity_command_pending", ".recycle_bin/deleted", ".infinity/internal", "user.local", "visible.txt").forEach { name ->
            File(repo, name).apply { parentFile!!.mkdirs(); writeText("data\n") }
        }
        File(repo, ".infinity/.gitignore").writeText("*\n")
        git("add", "-A")
        assertEquals(setOf(".infinity_command_pending", ".recycle_bin/deleted", "visible.txt"), git("ls-files").lineSequence().filter { it.isNotEmpty() }.toSet())
        git("add", "-f", ".infinity/internal")
        assertTrue(".infinity/internal" in git("ls-files").lineSequence().toSet())
        assertFalse(File(repo, ".gitignore").exists())
        assertArrayEquals(exclude, File(repo, ".git/info/exclude").readBytes())
    }
}
