package com.promenar.infinity.data.exec

import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.linux.LinuxResourceBudget
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHostConfigTest {
    @get:Rule val temp = TemporaryFolder()

    @After fun restoreTemporaryPermissions() {
        temp.root.walkTopDown().filter { it.isDirectory && it.name.startsWith("git-") }.forEach { it.setWritable(true) }
    }

    @Test fun managedConfigurationUsesOnlyTaskInputs() {
        val cache = temp.newFolder("cache")
        val libs = temp.newFolder("libs")
        for (name in listOf("git", "git-remote-https", "gitssh")) libs.resolve("libnx$name.so").writeText("")
        val config = GitHostConfig(cache, PackagedHostTools(libs))
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

    @Test fun refusesExecutionOptionsInEverySpelling() {
        for (flag in listOf("--upload-p", "--receive-pa", "--exec-p", "--ext-d", "--textc", "--filt")) {
            assertThrows(HostToolException::class.java) { GitHostConfig.validateArguments(listOf("fetch", flag)) }
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
}
