package com.promenar.infinity.data.exec

import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.linux.LinuxResourceBudget
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GitHostConfigTest {
    @get:Rule val temp = TemporaryFolder()

    @After fun restoreTemporaryPermissions() {
        temp.root.walkTopDown().filter { it.isDirectory && it.name.startsWith("git-") }.forEach { it.setWritable(true) }
    }

    @Test fun managedConfigurationUsesOnlyTaskInputs() {
        val cache = temp.newFolder("cache")
        val libs = nativeLibraries("libs")
        val config = gitConfig(cache, libs)
        val env = config.environment(listOf(temp.root), mapOf(
            GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:8080",
            GitRemoteBroker.ENV_SSH_BRIDGE to "127.0.0.1:8081",
            GitRemoteBroker.ENV_NONCE to "test",
        ), LinuxResourceBudget(512L * 1024 * 1024, listOf(0, 1), 2), mapOf("LD_PRELOAD" to "bad", "GIT_EDITOR" to "bad"))
        val values = (0 until env.getValue("GIT_CONFIG_COUNT").toInt()).associate {
            env.getValue("GIT_CONFIG_KEY_$it") to env.getValue("GIT_CONFIG_VALUE_$it")
        }
        assertEquals(libs.resolve("libnxgitssh.so").path, env["GIT_SSH"])
        assertEquals("https://", values["url.http://127.0.0.1:8080/https/.insteadOf"])
        assertEquals("X-Infinity-Git-Nonce: test", values["http.http://127.0.0.1:8080/.extraHeader"])
        assertEquals("false", values["core.fsmonitor"])
        assertEquals("initial", values["http.followRedirects"])
        assertEquals("false", values["tag.forceSignAnnotated"])
        for (format in listOf("openpgp", "ssh", "x509")) {
            assertEquals("/system/bin/false", values["gpg.$format.program"])
        }
        assertEquals("never", values["protocol.ext.allow"])
        assertEquals("1", values["pack.threads"])
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        assertEquals("/dev/null", env["GIT_CONFIG_GLOBAL"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("ssh", env["GIT_SSH_VARIANT"])
        assertEquals("127.0.0.1:8081", env[GitRemoteBroker.ENV_SSH_BRIDGE])
        assertEquals("test", env[GitRemoteBroker.ENV_NONCE])
        assertEquals("cat", values["core.pager"])
        assertEquals("", values["core.sshCommand"])
        assertEquals("", values["credential.helper"])
        assertEquals("", values["http.http://127.0.0.1:8080/.proxy"])
        assertEquals("never", values["protocol.fd.allow"])
        assertEquals("user", values["protocol.file.allow"])
        for (scheme in listOf("http", "https", "ssh")) assertEquals("always", values["protocol.$scheme.allow"])
        assertTrue(values["pack.windowMemory"]!!.endsWith("m"))
        assertTrue(values["core.packedGitLimit"]!!.endsWith("m"))
        assertEquals("0", values["gc.auto"])
        assertEquals("false", values["fetch.writeCommitGraph"])
        assertEquals(cache.resolve("git-empty-hooks").path, values["core.hooksPath"])
        assertEquals(cache.resolve("git-empty-template").path, env["GIT_TEMPLATE_DIR"])
        assertEquals(temp.root.canonicalPath, values["safe.directory"])
        assertFalse("LD_PRELOAD" in env)
        assertFalse("GIT_EDITOR" in env)
    }

    @Test fun refreshesManagedLinksAfterAnApkUpgrade() {
        val cache = temp.newFolder("cache")
        val oldLibraries = nativeLibraries("old-apk")
        val newLibraries = nativeLibraries("new-apk")
        val original = gitConfig(cache, oldLibraries).environment(emptyList(), emptyMap())
        val exec = File(original.getValue("GIT_EXEC_PATH"))
        val entries = exec.listFiles()!!.associateWith { Files.readSymbolicLink(it.toPath()) }
        assertEquals(6, entries.size)
        assertTrue(oldLibraries.deleteRecursively())

        val workers = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val upgrades = List(2) {
                val config = gitConfig(cache, newLibraries)
                workers.submit<Map<String, String>> {
                    start.await()
                    config.environment(emptyList(), emptyMap())
                }
            }
            start.countDown()
            for (upgrade in upgrades) assertEquals(exec.path, upgrade.get(10, TimeUnit.SECONDS)["GIT_EXEC_PATH"])
        } finally {
            workers.shutdownNow()
        }
        for ((entry, previous) in entries) {
            assertEquals(newLibraries.toPath().resolve(previous.fileName), Files.readSymbolicLink(entry.toPath()))
            assertTrue(entry.isFile)
        }
        val managedDirectories = cache.listFiles()!!
        assertEquals(setOf("git-exec-path", "git-empty-template", "git-empty-hooks"), managedDirectories.map { it.name }.toSet())
        managedDirectories.forEach(::assertReadOnly)
        assertEquals(exec.path, gitConfig(cache, newLibraries).environment(emptyList(), emptyMap())["GIT_EXEC_PATH"])
    }

    @Test fun refusesUnexpectedManagedEntriesBeforeRefreshingAnyLinks() {
        val oldLibraries = nativeLibraries("old-apk")
        val newLibraries = nativeLibraries("new-apk")
        val invalidEntries: List<(File) -> Unit> = listOf(
            { directory -> directory.resolve("git").apply { Files.delete(toPath()); writeText("") } },
            { directory -> directory.resolve("git").apply { Files.delete(toPath()); mkdir() } },
            { directory -> Files.createSymbolicLink(directory.resolve("unexpected").toPath(), oldLibraries.resolve("libnxgit.so").toPath()) },
            { directory -> directory.resolve("git").apply {
                Files.delete(toPath())
                Files.createSymbolicLink(toPath(), oldLibraries.resolve("libnxgitssh.so").toPath())
            } },
        )
        for (invalidate in invalidEntries) {
            val cache = temp.newFolder()
            val env = gitConfig(cache, oldLibraries).environment(emptyList(), emptyMap())
            val exec = File(env.getValue("GIT_EXEC_PATH"))
            assertTrue(exec.setWritable(true, true))
            invalidate(exec)
            assertTrue(exec.setWritable(false, false))
            assertThrows(IllegalArgumentException::class.java) {
                gitConfig(cache, newLibraries).environment(emptyList(), emptyMap())
            }
            assertEquals(oldLibraries.resolve("libnxgit.so").toPath(),
                Files.readSymbolicLink(exec.resolve("git-upload-pack").toPath()))
            assertReadOnly(exec)
        }
    }

    @Test fun permitsCommonGitArgumentsThroughTheTranslator() {
        val work = temp.newFolder().canonicalFile
        val translator = HostToolPathTranslator(mapOf("/workspace" to work))
        val unchanged = listOf(
            listOf("push", "-u", "origin", "main"),
            listOf("log", "--follow", "README.md"),
            listOf("log", "-S", "needle"),
            listOf("log", "-S", "/needle"),
            listOf("log", "--remotes"),
            listOf("diff", "-S", "/needle"),
            listOf("show", "-S", "/needle"),
            listOf("grep", "-F", "needle"),
            listOf("pull", "--rebase"),
            listOf("pull", "--no-rebase"),
            listOf("pull", "-sort"),
            listOf("pull", "-Xours"),
            listOf("rebase", "--autostash", "origin/main"),
            listOf("rebase", "--onto", "origin/main", "HEAD~2"),
            listOf("rev-parse", "--abbrev-ref", "HEAD"),
            listOf("check-ignore", "a"),
            listOf("config", "--get", "remote.origin.url"),
            listOf("config", "--local", "--get-all", "remote.origin.pushurl"),
            listOf("config", "--get", "branch.main.merge"),
            listOf("config", "--get", "branch.main.remote"),
            listOf("config", "init.defaultbranch", "main"),
            listOf("switch", "-c", "feature/x"),
            listOf("switch", "-C", "feature/x"),
            listOf("rev-list", "--exclude", "refs/tags/*", "--all"),
        )
        for (args in unchanged) assertEquals(args, translator.translate("git", "/workspace", args, 2).arguments)
        val clone = listOf("clone", "--filter=blob:none", "https://example.com/a/b.git", "/workspace/b")
        assertEquals(clone.dropLast(1) + work.resolve("b").path, translator.translate("git", "/workspace", clone, 2).arguments)
        assertTrue(GitHostConfig.optionConsumesValue("rebase", "--onto"))
        assertTrue(GitHostConfig.optionConsumesValue("switch", "-c"))
        assertTrue(GitHostConfig.optionConsumesValue("switch", "-C"))
    }

    @Test fun refusesInteractiveRebaseAndUnsafeConfigWrites() {
        val blocked = listOf(
            listOf("rebase", "-i", "HEAD~2"),
            listOf("rebase", "-x", "sh", "origin/main"),
            listOf("rebase", "--interactive", "HEAD~2"),
            listOf("rebase", "--exec=sh", "origin/main"),
            listOf("rebase", "--edit-todo"),
            listOf("rebase", "-S", "origin/main"),
            listOf("rebase", "--gpg-sign", "origin/main"),
            listOf("rebase", "-s", "custom", "origin/main"),
            listOf("pull", "--rebase=i"),
            listOf("pull", "-rinteractive"),
            listOf("pull", "-qrinteractive"),
            listOf("clone", "-u", "x", "https://example.com/a/b.git"),
            listOf("ls-remote", "-u", "x", "https://example.com/a/b.git"),
            listOf("config", "remote.origin.url", "https://example.com/x"),
            listOf("config", "--get", "remote.origin.uploadpack"),
            listOf("config", "--get", "branch.main.description"),
        )
        for (args in blocked) assertThrows(args.toString(), HostToolException::class.java) {
            GitHostConfig.validateArguments(args)
        }
    }

    @Test fun translatesBlameAncestryFilesAndRejectsPathsOutsideTheMount() {
        val repository = temp.newFolder().canonicalFile
        val translator = HostToolPathTranslator(mapOf("/workspace" to repository))
        fun blame(vararg args: String) = translator.translate("git", "/workspace", listOf("blame") + args, 2).arguments
        val file = repository.resolve("ancestry").path
        assertEquals(listOf("blame", "-S", file, "README.md"), blame("-S", "ancestry", "README.md"))
        assertEquals(listOf("blame", "-S$file", "README.md"), blame("-Sancestry", "README.md"))
        assertThrows(HostToolException::class.java) { blame("-S", "/etc/outside", "README.md") }
        assertThrows(HostToolException::class.java) { blame("-S/etc/outside", "README.md") }
        assertThrows(HostToolException::class.java) { blame("--format", "-S", "ancestry", "README.md") }
    }

    @Test fun refusesExecutionOptionsInEverySpelling() {
        for (flag in listOf("--upload-p", "--receive-pa", "--exec-p", "--ext-d", "--textc", "--filt")) {
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(listOf("fetch", flag)) }
        }
        for (args in listOf(listOf("push", "--follow"), listOf("grep", "--exclude", "needle"))) {
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(args) }
        }
        for (key in listOf("core.sshCommand", "core.hooksPath", "core.fsmonitor", "core.pager", "core.editor",
            "credential.helper", "diff.external", "filter.x.process", "alias.x", "protocol.file.allow",
            "uploadpack.x", "receivepack.x", "remote.origin.uploadpack", "remote.origin.receivepack",
            "http.host.extraHeader", "url.host.insteadOf", "include.path", "includeIf.x.path")) {
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(listOf("-c$key=x", "status")) }
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(listOf("--config-env=$key=ENV", "status")) }
        }
    }
    @Test fun guardsConfigFileOptionsAndRepositoryMetadata() {
        for (args in listOf(listOf("blame", "--contents=local"), listOf("archive", "--add-file=local"),
            listOf("commit", "-m", "--", "--gpg-sign"), listOf("merge", "--strategy", "custom"),
            listOf("archive", "-olocal"), listOf("config", "user.name", "--file=local"))) {
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(args) }
        }
        val repository = temp.newFolder().canonicalFile
        val git = repository.resolve(".git").apply { mkdir() }
        git.resolve("HEAD").writeText("ref: refs/heads/main\n")
        git.resolve("objects").mkdir()
        git.resolve("refs").mkdir()
        val localConfig = git.resolve("config")
        localConfig.writeText("[core]\n repositoryformatversion = 0\n bare = false\n")
        val translator = HostToolPathTranslator.forHost(listOf(repository))
        val request = translator.translate("git", repository.path, listOf("status", "--short"), 2)
        val environment = GitRepositoryGuard(translator).environment(request)
        assertEquals(git.path, environment["GIT_DIR"])
        assertEquals(repository.path, environment["GIT_WORK_TREE"])
        assertTrue(environment.containsKey("GIT_CEILING_DIRECTORIES"))
        localConfig.appendText("[filter \"custom\"]\n process = unsupported\n")
        assertThrows(HostToolException::class.java) { GitRepositoryGuard(translator).environment(request) }
    }

    @Test fun acceptsCommonRepositorySettingsWhileRejectingExternalConfiguration() {
        val repository = temp.newFolder().canonicalFile
        val git = repository.resolve(".git").apply { mkdir() }
        git.resolve("HEAD").writeText("ref: refs/heads/main\n")
        git.resolve("objects").mkdir()
        git.resolve("refs").mkdir()
        val localConfig = git.resolve("config")
        val commonConfig = """
            [core]
                repositoryformatversion = 0
                sparseCheckout = true
            [branch "main"]
                vscode-merge-base = origin/main
            [user]
                signingkey = ssh-ed25519 public-key
            [gpg]
                format = ssh
            [remote "origin"]
                url = https://example.com/repo.git
                gh-resolved = base
                github-pr-owner = example
                promisor = true
                partialclonefilter = blob:none
            [rebase]
                autoStash = true
            [pack]
                threads = 2
            [advice]
                detachedHead = "FALSE" # 标准布尔值
                statusHints = Yes
            [submodule "sub"]
                url = https://example.com/sub.git
        """.trimIndent()
        val translator = HostToolPathTranslator.forHost(listOf(repository))
        val request = translator.translate("git", repository.path, listOf("status"), 2)
        val guard = GitRepositoryGuard(translator)
        localConfig.writeText(commonConfig)
        assertEquals(git.path, guard.environment(request)["GIT_DIR"])

        for (config in listOf(
            "[gpg \"ssh\"]\n program = /bin/sh", "[gpg \"ssh\"]\n format = ssh",
            "[gpg \"ssh\"]\n allowedSignersFile = /etc/passwd", "[gpg.ssh]\n program = /bin/sh",
            "[submodule \"sub\"]\n update = !sh", "[commit]\n template = /etc/passwd",
            "[include]\n path = x", "[filter \"x\"]\n process = x",
            "[remote \"origin\"]\n pushurl = /etc/outside", "[advice]\n detachedHead = maybe",
            "[advice]\n detachedHead # 原因 = true",
            "[lfs \"customtransfer.tool\"]\n path = /bin/sh",
            "[remote \"other\"]\n promisor = true",
            "[remote \"-unsafe\"]\n url = https://example.com/repo.git\n promisor = true",
        )) {
            localConfig.writeText("$commonConfig\n$config\n")
            assertThrows(config, HostToolException::class.java) { guard.environment(request) }
        }
        localConfig.writeText("$commonConfig\n[pull]\n rebase = interactive\n")
        assertEquals(git.path, guard.environment(request)["GIT_DIR"])
        val pull = translator.translate("git", repository.path, listOf("pull"), 2)
        assertThrows(HostToolException::class.java) { guard.environment(pull) }
    }

    @Test fun validatesOnDiskInstructionsBeforeResumingRebase() {
        val repository = temp.newFolder().canonicalFile
        val git = repository.resolve(".git").apply { mkdir() }
        git.resolve("HEAD").writeText("ref: refs/heads/main\n")
        git.resolve("objects").mkdir()
        git.resolve("refs").mkdir()
        val rebase = git.resolve("rebase-merge").apply { mkdir() }
        val todo = rebase.resolve("git-rebase-todo")
        val translator = HostToolPathTranslator.forHost(listOf(repository))
        val request = translator.translate("git", repository.path, listOf("rebase", "--continue"), 2)
        val guard = GitRepositoryGuard(translator)
        todo.writeText("# 剩余提交\npick 0123456 change\nfixup -C 0123457 other\nu refs/heads/topic\n")
        val environment = guard.environment(request)
        assertEquals(git.path, environment["GIT_DIR"])
        assertEquals(":", environment["GIT_EDITOR"])

        for (instruction in listOf("exec", "x", "edit", "reword")) {
            todo.appendText("$instruction unsupported\n")
            assertThrows(instruction, HostToolException::class.java) { guard.environment(request) }
            todo.writeText("pick 0123456 change\n")
        }
        for (instruction in listOf("m -c 0123457 topic", "fixup -c 0123457 other")) {
            todo.writeText("$instruction\n")
            assertThrows(instruction, HostToolException::class.java) { guard.environment(request) }
        }
        val abort = translator.translate("git", repository.path, listOf("rebase", "--abort"), 2)
        todo.writeText("exec unsupported\n")
        assertEquals(git.path, guard.environment(abort)["GIT_DIR"])

        todo.writeText("pick 0123456 change\n")
        val signing = rebase.resolve("gpg_sign_opt").apply { writeText("-S\n") }
        assertThrows(HostToolException::class.java) { guard.environment(request) }
        signing.writeText("")
        assertThrows(HostToolException::class.java) { guard.environment(request) }
        Files.delete(signing.toPath())
        val outside = temp.newFile().canonicalFile
        val message = rebase.resolve("message").toPath()
        Files.createSymbolicLink(message, outside.toPath())
        assertThrows(HostToolException::class.java) { guard.environment(request) }
        Files.delete(message)
        Files.delete(todo.toPath())
        Files.delete(rebase.toPath())
        Files.createSymbolicLink(rebase.toPath(), temp.newFolder().canonicalFile.toPath())
        assertThrows(HostToolException::class.java) { guard.environment(request) }
    }

    @Test fun linkedWorktreePointerUsesTheTranslatedGuestGitDirectory() {
        val repository = temp.newFolder().canonicalFile
        val git = repository.resolve(".git").apply { mkdir() }
        git.resolve("config").writeText("[core]\n repositoryformatversion = 0\n")
        git.resolve("objects").mkdir()
        git.resolve("refs").mkdir()
        val metadata = git.resolve("worktrees/branch").apply { mkdirs() }
        metadata.resolve("commondir").writeText("../..\n")
        metadata.resolve("HEAD").writeText("ref: refs/heads/main\n")
        val worktree = repository.resolve("branch").apply { mkdir() }
        worktree.resolve(".git").writeText("gitdir: /workspace/.git/worktrees/branch\n")
        val translator = HostToolPathTranslator(mapOf("/workspace" to repository))
        val command = translator.translate("git", "/workspace/branch", listOf("status"), 2)
        val environment = GitRepositoryGuard(translator).environment(command)
        assertEquals(metadata.path, environment["GIT_DIR"])
        assertEquals(git.path, environment["GIT_COMMON_DIR"])
        assertEquals(worktree.path, environment["GIT_WORK_TREE"])
    }

    private fun nativeLibraries(name: String): File = temp.newFolder(name).apply {
        for (tool in listOf("git", "git-remote-https", "gitssh")) resolve("libnx$tool.so").writeText("")
    }

    private fun gitConfig(cache: File, libs: File) = GitHostConfig(cache, PackagedHostTools(libs))

    private fun assertReadOnly(directory: File) {
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        for (permission in listOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)) {
            assertFalse("${directory.name}: $permission", permission in permissions)
        }
    }
}
