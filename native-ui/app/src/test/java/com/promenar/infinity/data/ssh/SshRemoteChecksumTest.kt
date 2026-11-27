package com.promenar.infinity.data.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SshRemoteChecksumTest {
    private val hash = "Ab".repeat(32)

    @Test fun 固定命令安全引用路径并严格解析三种回执() = runBlocking {
        assertTrue(remoteChecksumCommand("/目录/a'b\$x*").contains("p='/目录/a'\"'\"'b\$x*'"))
        assertTrue(remoteChecksumCommand("/a").contains("< \"\$p\""))
        listOf("sha256sum", "python3_hashlib", "shasum_a_256").forEach { tool ->
            val result = remoteChecksumReceipt(SshExecOutput(0, "$tool\n$hash\n", "", false, false))
            assertEquals(hash.lowercase(), result.sha256)
            assertEquals("ssh_exec_$tool", result.verificationMethod)
        }
    }

    @Test fun 受控PATH中按工具顺序选用且通过标准输入读取路径() {
        val root = java.nio.file.Files.createTempDirectory("checksum-tools").toFile()
        try {
            val file = java.io.File(root, "a'b \$x?.txt").apply { writeText("回环数据\n") }
            val tools = java.io.File(root, "bin").apply { mkdir() }
            for ((program, method) in listOf("sha256sum" to "sha256sum", "python3" to "python3_hashlib", "shasum" to "shasum_a_256")) {
                val code = buildString {
                    appendLine("#!/bin/sh")
                    if (program == "python3") appendLine("""[ "${'$'}2" = 'import hashlib' ] && exit 0""")
                    if (program == "shasum") appendLine("""[ "${'$'}1" = '-a' ] && [ "${'$'}2" = '256' ] || exit 1""")
                    appendLine("IFS= read -r input")
                    appendLine("""[ "${'$'}input" = '回环数据' ] || exit 1""")
                    appendLine("printf '%s\\n' '$hash -'")
                }
                val executable = java.io.File(tools, program).apply { writeText(code); check(setExecutable(true)) }
                val process = ProcessBuilder("/bin/sh", "-c", remoteChecksumCommand(file.path))
                    .apply { environment()["PATH"] = tools.path }.start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val errors = process.errorStream.bufferedReader().use { it.readText() }
                val checked = remoteChecksumReceipt(SshExecOutput(process.waitFor(), output, errors, false, false))
                assertEquals("ssh_exec_$method", checked.verificationMethod)
                assertEquals(hash.lowercase(), checked.sha256)
                check(executable.delete())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun OpenSSH仅SFTP会话返回明确的执行不可用() {
        val failure = assertThrows(SshFailure::class.java) {
            remoteChecksumReceipt(SshExecOutput(1, "This service allows sftp connections only.\n", "", false, false))
        }
        assertEquals("EXEC_UNAVAILABLE", failure.code)
    }

    @Test fun 额外行错误摘要和非零退出一律失败() {
        for ((status, stdout) in listOf(0 to "sha256sum\n$hash\n\n", 0 to "sha256sum\nwrong\n",
            1 to "sha256sum\n$hash\n", 127 to "", -1 to "")
        ) {
            val failure = assertThrows(SshFailure::class.java) {
                remoteChecksumReceipt(SshExecOutput(status, stdout, "", false, false))
            }
            assertEquals(when (status) { 127 -> "CHECKSUM_TOOL_UNAVAILABLE"; -1 -> "EXEC_UNAVAILABLE"; else -> "CHECKSUM_FAILED" }, failure.code)
        }
    }
}
