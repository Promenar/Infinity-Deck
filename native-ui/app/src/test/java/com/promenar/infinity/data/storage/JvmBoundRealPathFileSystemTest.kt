package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JvmBoundRealPathFileSystemTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `父目录别名登记的移动保持已打开目录身份并拒绝重新打开替换链接`() {
        verifyMovedDirectory(registerAfterReplacement = false)
    }

    @Test fun `替换链接后登记移动只解析父目录别名不跟随被替换节点`() {
        verifyMovedDirectory(registerAfterReplacement = true)
    }

    private fun verifyMovedDirectory(registerAfterReplacement: Boolean) {
        val realParent = temporary.newFolder("real-parent").canonicalFile
        val aliasParent = File(temporary.root, "parent-alias")
        Files.createSymbolicLink(aliasParent.toPath(), realParent.toPath())
        val users = File(aliasParent, "users").apply { mkdir() }
        val held = File(aliasParent, "held-users")
        val outside = temporary.newFolder("outside").canonicalFile
        val state = temporary.newFolder("state").canonicalFile
        File(users, "root-race/SKILL.md").apply { parentFile.mkdirs(); writeText("原始内容") }
        File(outside, "root-race/SKILL.md").apply { parentFile.mkdirs(); writeText("外部内容") }
        val fileSystem = JvmBoundRealPathFileSystem()

        openDirectory(fileSystem, users).use { directory ->
            val originalId = directory.state().objectId
            assertThat(users.renameTo(held)).isTrue()
            if (!registerAfterReplacement) fileSystem.movedDirectories[users.toPath()] = held.toPath()
            Files.createSymbolicLink(users.toPath(), outside.toPath())
            if (registerAfterReplacement) fileSystem.movedDirectories[users.toPath()] = held.toPath()

            assertThat(directory.state().objectId).isEqualTo(originalId)
            directory.openDirectory("root-race").use { skill ->
                skill.openRead("SKILL.md").use { assertThat(it.input.readBytes().toString(Charsets.UTF_8)).isEqualTo("原始内容") }
            }
            assertThat(runCatching { openDirectory(fileSystem, users).close() }.exceptionOrNull())
                .isInstanceOf(ExternalFolderException::class.java)
            openDirectory(fileSystem, state).use { destination -> directory.moveNew("root-race", destination, "backup") }
            assertThat(File(state, "backup/SKILL.md").readText()).isEqualTo("原始内容")
            assertThat(File(outside, "root-race/SKILL.md").readText()).isEqualTo("外部内容")
            assertThat(File(held, "root-race").exists()).isFalse()
            assertThat(Files.isSymbolicLink(users.toPath())).isTrue()
        }
    }

    private fun openDirectory(fileSystem: RealPathFileSystem, root: File): BoundRealPathDirectory {
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var directory = fileSystem.openFileSystemRoot().also(opened::add)
            File(root.parentFile!!.canonicalFile, root.name).toPath().forEach { segment ->
                directory = directory.openDirectory(segment.toString()).also(opened::add)
            }
            return opened.removeAt(opened.lastIndex)
        } finally {
            opened.asReversed().forEach { it.close() }
        }
    }
}
