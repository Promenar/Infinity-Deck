package com.promenar.infinity.data.exec

import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.git.remote.LinuxGitEnvironment
import com.promenar.infinity.data.linux.LinuxGitSafeDirectories
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class LinuxCommandEnvironmentTest {
    @TempDir lateinit var temporary: Path

    private val lease = mapOf(
        GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:43123",
        GitRemoteBroker.ENV_NONCE to "synthetic-command-environment",
        GitRemoteBroker.ENV_CREDENTIAL_HOSTS to "git.test,git.example.test",
    )

    @Test fun `凭据租约和保密槽位不能覆盖启动器的git配置或插入游离编号`() {
        val launch = LinuxGitEnvironment.from(lease).toMutableMap().also {
            LinuxGitSafeDirectories.appendTo(it, listOf("/workspace", "/mnt/shared"))
        }
        val conflictingLease = lease + mapOf(
            "GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "safe.directory", "GIT_CONFIG_VALUE_0" to "*",
            "GIT_CONFIG_KEY_99" to "safe.directory", "GIT_CONFIG_VALUE_99" to "*",
        )
        val secrets = mapOf("GIT_CONFIG_COUNT" to "0", "GIT_CONFIG_KEY_1" to "safe.directory", "GIT_CONFIG_VALUE_1" to "*",
            "GIT_CONFIG_GLOBAL" to "/untrusted/config", "GIT_CONFIG_NOSYSTEM" to "0", "APP_TOKEN" to "synthetic-app-value")
        val merged = linuxCommandEnvironment(launch, conflictingLease, secrets)
        assertEquals(launch.filterKeys { it.startsWith("GIT_CONFIG_") }, merged.filterKeys { it.startsWith("GIT_CONFIG_") })
        assertEquals(lease[GitRemoteBroker.ENV_NONCE], merged[GitRemoteBroker.ENV_NONCE])
        assertEquals("synthetic-app-value", merged["APP_TOKEN"])
    }

    @Test fun `非git环境沿用租约与保密槽位覆盖顺序且不修改输入`() {
        val launch = mapOf("GIT_CONFIG_COUNT" to "0", "PATH" to "/usr/bin", "SHARED" to "launch")
        val merged = linuxCommandEnvironment(launch, mapOf("SHARED" to "lease", "LEASE_ONLY" to "value"), mapOf("SHARED" to "secret"))
        assertEquals("secret", merged["SHARED"])
        assertEquals("value", merged["LEASE_ONLY"])
        assertEquals("/usr/bin", merged["PATH"])
        assertEquals(mapOf("GIT_CONFIG_COUNT" to "0", "PATH" to "/usr/bin", "SHARED" to "launch"), launch)
    }

    @Test fun `本机git用合并后的凭据和挂载配置放行根及子仓库并拒绝根外仓库`() {
        val git = listOf("/usr/bin/git", "git").firstOrNull {
            runCatching { run(it, temporary.toFile(), isolated, "--version").first == 0 }.getOrDefault(false)
        }
        assumeTrue(git != null, "需要本机 git")
        val base = temporary.toFile().canonicalFile
        val root = File(base, "共享 仓库 \"q\" #1").apply { mkdirs() }
        val nested = File(root, "子 仓库").apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }
        listOf(root, nested, outside).forEach { assertEquals(0, run(git!!, it, isolated, "init", "-q").first) }
        val rejected = run(git!!, root, isolated, "status")
        assertNotEquals(0, rejected.first)
        assertTrue("dubious ownership" in rejected.second, rejected.second)

        val credentials = LinuxGitEnvironment.from(lease)
        val launch = credentials.toMutableMap().also { LinuxGitSafeDirectories.appendTo(it, listOf(root.path)) }
        val environment = isolated + linuxCommandEnvironment(launch, lease, emptyMap())
        listOf(root, nested).forEach {
            val status = run(git, it, environment, "status")
            assertEquals(0, status.first, status.second)
        }
        val outsideStatus = run(git, outside, environment, "status")
        assertNotEquals(0, outsideStatus.first)
        assertTrue("dubious ownership" in outsideStatus.second, outsideStatus.second)
        // 从 git 自身读取代理条目，证明它与挂载信任同时被解析；不发起网络请求。
        (0 until credentials.getValue("GIT_CONFIG_COUNT").toInt()).forEach { index ->
            val config = run(git, root, environment, "config", "--get", credentials.getValue("GIT_CONFIG_KEY_$index"))
            assertEquals(0, config.first, config.second)
            assertEquals(credentials.getValue("GIT_CONFIG_VALUE_$index"), config.second.trimEnd('\n', '\r'))
        }
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
}
