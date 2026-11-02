package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.nio.channels.Channels

class SkillBackupSnapshotterTest {
    @Test
    fun `Git 元数据读取不会追随初始大小之后的追加内容`() {
        val source = Channels.newChannel(ByteArrayInputStream("old-appended".toByteArray()))
        Channels.newInputStream(boundedSkillMetadataChannel(source, 3)).use { input ->
            assertThat(input.readBytes()).isEqualTo("old".toByteArray())
        }
        assertThat(source.isOpen).isFalse()
    }

    @Test
    fun `Git 元数据在初始大小之前截断时失败`() {
        val source = Channels.newChannel(ByteArrayInputStream("ol".toByteArray()))
        Channels.newInputStream(boundedSkillMetadataChannel(source, 3)).use { input ->
            assertThrows<BackupValidationException> { input.readBytes() }
        }
    }

    @Test
    fun `大量 Git 短续行保持逻辑 URL 值且可脱敏`() {
        val config = "[remote \"origin\"]\nurl=https://" + "a\\\n".repeat(10000) + ":synthetic@example.invalid/repo.git\n"
        val safe = SkillGitMetadata.sanitizeConfig(config)
        assertThat(SkillGitMetadata.parse(".") { if (it == "config") safe else null }.remotes)
            .containsExactly(SkillGitRemote("origin", "https://example.invalid/repo.git"))
    }

    @Test
    fun `临时目录凭据与符号链接被排除且正常隐藏文件和空目录被保留`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, "beta/SKILL.md", "乙")
        write(root, "alpha/scripts/run.py", "print(1)")
        write(root, "alpha/.hidden", "hidden")
        write(root, ".staging-one/alpha/SKILL.md", "临时")
        write(root, ".trash-one/SKILL.md", "回收")
        write(root, "alpha/credentials", "synthetic")
        write(root, ".git-credentials", "synthetic")
        Files.createDirectories(root.resolve("beta/empty"))
        Files.createSymbolicLink(root.resolve("linked"), root.resolve("alpha"))
        Files.createSymbolicLink(root.resolve("alpha/broken"), root.resolve("missing"))
        val partition = snapshot(root)
        try {
            assertThat(partition.files.keys).containsExactly(
                "alpha/SKILL.md", "beta/SKILL.md", "alpha/scripts/run.py", "alpha/.hidden",
            )
            assertThat(partition.metadata.directories).containsExactly("alpha", "alpha/scripts", "beta", "beta/empty")
            assertThat(partition.metadata.summary.skillCount).isEqualTo(2)
            assertThat(partition.metadata.summary.skippedSymbolicLinks).isEqualTo(2)
        } finally { partition.wipe() }
    }

    @Test
    fun `含 Git 超出剩余内存预算时跳过 Git 而不是取消备份`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, "alpha/.git/HEAD", "ref: refs/heads/main\n")
        write(root, "alpha/.git/config", "[remote \"origin\"]\n url = https://example.invalid/alpha.git\n")
        write(root, "alpha/.git/objects/pack", "x".repeat(4096))
        val budget = 1024L
        val partition = SkillBackupSnapshotter(root).snapshot(budget, BackupPackageLimits.MAX_ENTRIES - 2)
        try {
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md")
            assertThat(partition.metadata.summary.skippedGitRepositories.map { it.relativePath }).containsExactly("alpha")
        } finally { partition.wipe() }
    }

    @Test
    fun `未超限的根仓库和技能仓库均打包且配置副本脱敏`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        val config = "[remote \"origin\"]\n url = https://synthetic-user:synthetic-password@example.invalid/root.git\n"
        write(root, ".git/config", config)
        write(root, ".git/HEAD", "ref: refs/heads/main\n")
        write(root, "alpha/.git/config", config.replace("root.git", "alpha.git"))
        write(root, ".git/modules/sub/config", config)
        write(root, ".git/config.worktree", config)
        write(root, "alpha/.git/objects/object", "object")
        write(root, "alpha/.git/credentials", "synthetic")
        val partition = snapshot(root)
        try {
            assertThat(partition.files.getValue(".git/config").toString(Charsets.UTF_8))
                .contains("https://example.invalid/root.git")
            assertThat(partition.files.getValue("alpha/.git/config").toString(Charsets.UTF_8))
                .doesNotContain("synthetic-password")
            assertThat(partition.files).containsKey("alpha/.git/objects/object")
            assertThat(partition.files).doesNotContainKey("alpha/.git/credentials")
            assertThat(partition.files.getValue(".git/modules/sub/config").toString(Charsets.UTF_8))
                .doesNotContain("synthetic-password")
            assertThat(partition.files.getValue(".git/config.worktree").toString(Charsets.UTF_8))
                .doesNotContain("synthetic-password")
            assertThat(readUtf8(root.resolve(".git/config"))).isEqualTo(config)
            assertThat(partition.metadata.summary.skippedGitRepositories).isEmpty()
        } finally { partition.wipe() }
    }

    @Test
    fun `Git 超量时所有仓库被跳过并解析松散和 packed refs`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        val sha = "a".repeat(40)
        val packedSha = "b".repeat(40)
        write(root, ".git/config", "[remote \"origin\"]\n url=https://synthetic:synthetic@example.invalid/root.git\n")
        write(root, ".git/HEAD", "ref: refs/heads/main\n")
        write(root, ".git/refs/heads/main", "$sha\n")
        write(root, "alpha/.git/config", "[remote \"upstream\"]\n url=ssh://synthetic@example.invalid/alpha.git\n pushurl=https://synthetic:synthetic@example.invalid/push.git\n")
        write(root, "alpha/.git/HEAD", "ref: refs/heads/feature/branch\n")
        write(root, "alpha/.git/packed-refs", "# pack-refs with: peeled\n$packedSha refs/heads/feature/branch\n^${"c".repeat(40)}\n")
        val large = root.resolve(".git/objects/large")
        Files.createDirectories(large.parent)
        RandomAccessFile(large.toFile(), "rw").use { it.setLength(64L * 1024 * 1024) }

        val partition = snapshot(root)
        try {
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md")
            val repos = partition.metadata.summary.skippedGitRepositories.associateBy { it.relativePath }
            assertThat(repos.keys).containsExactly(".", "alpha")
            assertThat(repos.getValue(".").branch).isEqualTo("main")
            assertThat(repos.getValue(".").commit).isEqualTo(sha)
            assertThat(repos.getValue(".").remotes.single().url).isEqualTo("https://example.invalid/root.git")
            assertThat(repos.getValue("alpha").branch).isEqualTo("feature/branch")
            assertThat(repos.getValue("alpha").commit).isEqualTo(packedSha)
            assertThat(repos.getValue("alpha").remotes.map { it.url })
                .containsExactly("ssh://example.invalid/alpha.git", "https://example.invalid/push.git")
        } finally { partition.wipe() }
    }

    @Test
    fun `超限 Git 的大配置与 packed refs 流式解析而不受清单文件大小限制`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, ".git/config", "# comment\n".repeat(450000) + "[remote \"origin\"]\nurl=https://synthetic:synthetic@example.invalid/repo.git\n")
        write(root, ".git/HEAD", "ref: refs/heads/main\n")
        write(root, ".git/packed-refs", "# refs\n".repeat(650000) + "${"b".repeat(40)} refs/heads/main\n")
        RandomAccessFile(root.resolve(".git/large").toFile(), "rw").use { it.setLength(64L * 1024 * 1024) }

        val partition = snapshot(root)
        try {
            val repository = partition.metadata.summary.skippedGitRepositories.single()
            assertThat(repository.commit).isEqualTo("b".repeat(40))
            assertThat(repository.remotes).containsExactly(SkillGitRemote("origin", "https://example.invalid/repo.git"))
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md")
        } finally { partition.wipe() }
    }

    @Test
    fun `包含 Git 的条目数超过四千时跳过 Git`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, ".git/HEAD", "d".repeat(40))
        repeat(4000) { write(root, ".git/objects/$it", "") }
        val partition = snapshot(root)
        try {
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md")
            assertThat(partition.metadata.summary.skippedGitRepositories.single().commit).isEqualTo("d".repeat(40))
        } finally { partition.wipe() }
    }

    @Test
    fun `无 Git 文件超过六十四 MiB 时在创建备份包前失败`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        RandomAccessFile(root.resolve("large").toFile(), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        val failure = assertThrows<BackupValidationException> { snapshot(root) }
        assertThat(failure).hasMessageThat().contains("64 MiB")
    }

    @Test
    fun `技能分区遵守整包内存预算与条目预算`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "1234")
        assertThrows<BackupValidationException> { SkillBackupSnapshotter(root).snapshot(3, 100) }
        assertThrows<BackupValidationException> { SkillBackupSnapshotter(root).snapshot(100, 1) }
    }

    @Test
    fun `Git 配置续行和引号地址脱敏并保留 SCP 地址`() {
        val config = "[remote \"origin\"]\nurl=\"https://synthetic:\\\nsynthetic@example.invalid/a.git\"\npushurl=git@example.invalid:a.git\n"
        val safe = SkillGitMetadata.sanitizeConfig(config)
        assertThat(safe).doesNotContain("synthetic")
        assertThat(safe).contains("git@example.invalid:a.git")
        assertThat(SkillGitMetadata.parse(".") { if (it == "config") config else null }.remotes.map { it.url })
            .containsExactly("https://example.invalid/a.git", "git@example.invalid:a.git")
    }

    @Test
    fun `Git 分段引号中的用户信息不会进入打包配置`() = withRoot { root ->
        val config = "[remote \"origin\"]\nurl=\"https://\"synthetic-user:synthetic-password@example.invalid/repo.git\n" +
            "pushurl=https://\"synthetic-user:synthetic-password\"@example.invalid/push.git\n" +
            "[core]\n editor = \"tool --arg=\\\"value\\\"\"\n"
        write(root, ".git/config", config)
        val partition = snapshot(root)
        try {
            val safe = partition.files.getValue(".git/config").toString(Charsets.UTF_8)
            assertThat(safe).doesNotContain("synthetic-password")
            assertThat(safe).contains("https://example.invalid/repo.git")
            assertThat(safe).contains("https://example.invalid/push.git")
            assertThat(safe).contains("tool --arg=\\\"value\\\"")
            assertThat(readUtf8(root.resolve(".git/config"))).isEqualTo(config)
        } finally { partition.wipe() }
    }

    @Test
    fun `Git 注释中的反斜杠不会吞掉下一节远端配置`() {
        listOf("editor=tool # comment \\", "# comment \\", "; comment \\").forEach { comment ->
            val config = "[core]\n$comment\n[remote \"origin\"]\nurl=https://synthetic:synthetic@example.invalid/repo.git\n"
            val safe = SkillGitMetadata.sanitizeConfig(config)
            val metadata = SkillGitMetadata.parse(".") { if (it == "config") safe else null }
            assertThat(metadata.remotes).containsExactly(SkillGitRemote("origin", "https://example.invalid/repo.git"))
            assertThat(safe).doesNotContain("synthetic:synthetic")
        }
    }

    @Test
    fun `名为 credentials 的普通目录与其非凭据文件可备份`() = withRoot { root ->
        write(root, "alpha/credentials/readme.md", "说明")
        val partition = snapshot(root)
        try { assertThat(partition.files.getValue("alpha/credentials/readme.md").toString(Charsets.UTF_8)).isEqualTo("说明") }
        finally { partition.wipe() }
    }

    @Test
    fun `经技能挂载写入后留下的内部目录不进入备份且快照成功`() = withRoot { root ->
        Files.createDirectories(root.resolve("alpha"))
        val backend = JvmBoundRealPathFileSystem().backend(root.toFile().canonicalFile)
        runBlocking { backend.write("/alpha/SKILL.md", "甲".toByteArray(), null, true) }
        assertThat(Files.isDirectory(root.resolve(".infinity"))).isTrue()

        val partition = snapshot(root)
        try {
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md")
            assertThat(partition.metadata.directories).containsExactly("alpha")
            assertThat(partition.metadata.summary.skillCount).isEqualTo(1)
        } finally { partition.wipe() }
    }

    @Test
    fun `任意深度的工作区内部名称被排除而普通隐藏文件保留`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, "alpha/.hidden", "hidden")
        write(root, ".infinity/.gitignore", "*\n")
        write(root, ".infinity/.infinity-write-0", "暂存")
        write(root, ".nexara/tombstones/old", "旧")
        write(root, ".INFINITY_versions/v1", "版本")
        write(root, "alpha/.infinity_root_identity", "标记")
        write(root, "alpha/nested/.nexara/x", "旧")
        val partition = snapshot(root)
        try {
            assertThat(partition.files.keys).containsExactly("alpha/SKILL.md", "alpha/.hidden")
            assertThat(partition.metadata.directories).containsExactly("alpha", "alpha/nested")
        } finally { partition.wipe() }
    }

    @Test
    fun `技能根含内部目录时整包编码与解码往返成功`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "甲")
        write(root, ".infinity/.gitignore", "*\n")
        val partition = snapshot(root)
        val codec = DefaultBackupPackageCodec()
        val bytes = codec.encode(
            BackupSnapshot(database = "{}".toByteArray(), preferences = "{}".toByteArray(), databaseSchemaVersion = 1, appVersion = "test", skills = partition),
            BackupOptions(content = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)),
        )
        try {
            codec.decode(bytes, null).use { decoded ->
                assertThat(decoded.skills!!.files.keys).containsExactly("alpha/SKILL.md")
            }
        } finally {
            bytes.fill(0)
            partition.wipe()
        }
    }

    @Test
    fun `技能失败按可处理原因分类`() = withRoot { root ->
        write(root, "alpha/SKILL.md", "1234")
        assertThat(assertThrows<BackupValidationException> { SkillBackupSnapshotter(root).snapshot(3, 100) }.reason)
            .isEqualTo(BackupFailureReason.CAPACITY_EXCEEDED)
        assertThat(assertThrows<BackupValidationException> { SkillBackupSnapshotter(root).snapshot(100, 1) }.reason)
            .isEqualTo(BackupFailureReason.CAPACITY_EXCEEDED)
        RandomAccessFile(root.resolve("large").toFile(), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        assertThat(assertThrows<BackupValidationException> { snapshot(root) }.reason)
            .isEqualTo(BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)
        Files.delete(root.resolve("large"))
        write(root, "alpha/a:b.txt", "冒号")
        assertThat(assertThrows<BackupValidationException> { snapshot(root) }.reason)
            .isEqualTo(BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)
    }

    private fun writeUtf8(path: Path, value: String) = Files.write(path, value.toByteArray(Charsets.UTF_8))
    private fun readUtf8(path: Path): String = Files.readAllBytes(path).toString(Charsets.UTF_8)

    private fun snapshot(root: Path) = SkillBackupSnapshotter(root)
        .snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES, BackupPackageLimits.MAX_ENTRIES - 2)

    private fun write(root: Path, path: String, value: String) {
        Files.createDirectories(root.resolve(path).parent)
        writeUtf8(root.resolve(path), value)
    }

    private fun withRoot(block: (Path) -> Unit) {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".skill-backup-test")
        try { block(Files.createDirectory(base.resolve("agent_skills"))) }
        finally { base.toFile().deleteRecursively() }
    }
}
