package com.promenar.infinity.data.ssh

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class AuthorizedKeyInstallCommandTest {
    @Test fun `接受各类公钥并以固定方式安装`() {
        listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa").forEach { type ->
            val key = "$type AAAAB3NzaC1yc2E= safe comment"
            val command = authorizedKeyInstallCommand(key)
            assertTrue(command.contains("grep -qxF '$key' ~/.ssh/authorized_keys"))
            assertTrue(command.contains("chmod 700 ~/.ssh"))
            assertTrue(command.contains("chmod 600 ~/.ssh/authorized_keys"))
        }
    }

    @Test fun `拒绝 shell 特殊字符和换行`() {
        listOf("'", ";", "\$", "\n", "`").forEach { character ->
            assertTrue(runCatching { authorizedKeyInstallCommand("ssh-ed25519 AAAA x${character}y") }.isFailure)
        }
    }

    @Test fun `重复执行只追加一次并收紧权限`() {
        val home = Files.createTempDirectory("ssh-install-test").toFile()
        try {
            val key = "ssh-ed25519 AAAAB3NzaC1lZDI1NTE5 test"
            repeat(2) {
                val process = ProcessBuilder("/bin/sh", "-c", authorizedKeyInstallCommand(key))
                    .also { it.environment()["HOME"] = home.absolutePath }
                    .start()
                assertEquals(0, process.waitFor())
            }
            val ssh = home.resolve(".ssh")
            assertEquals(listOf(key), ssh.resolve("authorized_keys").readLines())
            assertEquals("rwx------", Files.getPosixFilePermissions(ssh.toPath()).let {
                java.nio.file.attribute.PosixFilePermissions.toString(it)
            })
            assertEquals("rw-------", Files.getPosixFilePermissions(ssh.resolve("authorized_keys").toPath()).let {
                java.nio.file.attribute.PosixFilePermissions.toString(it)
            })
        } finally { home.deleteRecursively() }
    }
}
