package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.secret.SecretCapture
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.tool.ToolOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SecretExecutionIoTest {
    @Test fun `PTY规范化生成的秘密在日志和实时快照之前遮蔽`() = runBlocking {
        val root = Files.createTempDirectory("secret-pty-normalized").toFile()
        try {
            val vault = SessionSecretVault()
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "synthetic-secret".toByteArray())
            val log = File(root, "output.log")
            val diskSnapshots = mutableListOf<String>()
            val live = mutableListOf<String>()
            val request = BuiltinCommandRequest(listOf("/bin/sh", "-c", "printf 'synthetic-\\033[31msecret\\n'; printf '%0800d' 0; sleep 0.1; printf end"), root,
                mapOf("PATH" to "/usr/bin:/bin"), timeoutSeconds = 10, terminal = true,
                outputTransform = secretCommandOutput("s", SecretOutputRedactor(vault)))
            val output = runner(root).runBackground(request, BuiltinCommandControl(), log, File(root, "pgid"),
                onTerminalOutput = { snapshot -> live += snapshot; diskSnapshots += log.takeIf(File::isFile)?.readText().orEmpty() })
            assertThat(output.stdout).contains("‹保密:TOKEN›")
            assertThat(log.readText()).contains("‹保密:TOKEN›")
            assertThat(diskSnapshots.joinToString("\n")).doesNotContain("synthetic-secret")
            assertThat(live.joinToString("\n")).doesNotContain("synthetic-secret")
        } finally { root.deleteRecursively() }
    }

    @Test fun `后台日志在写盘前遮蔽跨块值且保留尾部`() = runBlocking {
        val root = Files.createTempDirectory("secret-exec-log").toFile()
        try {
            val vault = SessionSecretVault()
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "fixture-token-value".toByteArray())
            val request = BuiltinCommandRequest(listOf("/bin/sh", "-c", "printf fixture-token; sleep 0.1; printf -- '-value tail'"), root,
                mapOf("PATH" to "/usr/bin:/bin"), timeoutSeconds = 10,
                outputTransform = secretCommandOutput("s", SecretOutputRedactor(vault)))
            val log = File(root, "output.log")
            runner(root).runBackground(request, BuiltinCommandControl(), log, File(root, "pgid"))
            assertThat(com.promenar.infinity.data.jobs.readJobLogTail(log).text).isEqualTo("‹保密:TOKEN› tail")
        } finally { root.deleteRecursively() }
    }

    @Test fun `规范化日志的秘密可以跨两次提交分块`() {
        val vault = SessionSecretVault()
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "synthetic-\nsecret".toByteArray())
        val normalized = checkNotNull(secretCommandOutput("s", SecretOutputRedactor(vault)).normalized).invoke()
        val output = normalized.accept(ToolOutputStream.STDOUT, "synthetic-\n") +
            normalized.accept(ToolOutputStream.STDOUT, "secret\ntrailing") + normalized.finish(ToolOutputStream.STDOUT)
        assertThat(output).isEqualTo("‹保密:TOKEN›\ntrailing")
    }

    @Test fun `ANSI拼接的长秘密在终端裁尾前遮蔽`() = runBlocking {
        val root = Files.createTempDirectory("secret-pty-long").toFile()
        try {
            val vault = SessionSecretVault()
            val value = "X".repeat(40_000)
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, value.toByteArray())
            val command = "printf '%020000d' 0 | tr 0 X; printf '\\033[31m'; printf '%020000d' 0 | tr 0 X"
            val request = BuiltinCommandRequest(listOf("/bin/sh", "-c", command), root, mapOf("PATH" to "/usr/bin:/bin"),
                timeoutSeconds = 10, terminal = true, outputTransform = secretCommandOutput("s", SecretOutputRedactor(vault)))
            val output = runner(root).runTerminal(request, BuiltinCommandControl(), {})
            assertThat(output.stdout).isEqualTo("‹保密:TOKEN›")
        } finally { root.deleteRecursively() }
    }

    @Test fun `字节终端输入不经过字符串接口且调用后清零`() {
        val control = BuiltinCommandControl()
        val captured = mutableListOf<Byte>()
        control.inputBytes = { value, eof -> captured.addAll(value.toList()); assertThat(eof).isFalse() }
        val value = "fixture-input".toByteArray()
        control.writeInput(value, false)
        assertThat(captured.toByteArray()).isEqualTo(value)
    }

    @Test fun `引用注入不影响原始命令`() {
        val vault = SessionSecretVault()
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "fixture-token".toByteArray())
        vault.put("s", "OTHER", "其它", SecretSource.UserInput, "fixture-other".toByteArray())
        val source = "printf '%s' \"\$TOKEN\""
        assertThat(vault.environment("s", source).keys).containsExactly("TOKEN")
        assertThat(source).isEqualTo("printf '%s' \"\$TOKEN\"")
    }

    private fun runner(root: File) = BuiltinCommandRunner(
        listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"), "/bin/sh",
        ProcessGroupSignaller { pid, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$pid").start().waitFor() },
        { File(root, "scratch") }, killGraceMillis = 100,
        terminalFactory = { argv, directory, environment ->
            ProcessBuilder(listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127") + argv)
                .directory(directory).redirectErrorStream(true).apply { environment().clear(); environment().putAll(environment) }.start()
        },
    )
}
