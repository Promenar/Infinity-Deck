package com.promenar.infinity.data.exec

import com.promenar.infinity.data.git.remote.GitCredentialProxy
import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.git.remote.LinuxGitEnvironment
import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.LinuxResourceGovernor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

internal class GitHostConfig(private val cache: File, private val tools: PackagedHostTools) {
    fun environment(
        roots: Collection<File>,
        lease: Map<String, String>,
        budget: LinuxResourceBudget = LinuxResourceGovernor.system.budget(),
        input: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val git = tools.availableTools()["git"] ?: throw HostToolException("TOOL_UNAVAILABLE", "宿主 Git 未打包。")
        val (exec, templates, hooks) = prepare(git)
        val environment = hostToolEnvironment(input).toMutableMap()
        environment += mapOf(
            "GIT_EXEC_PATH" to exec.path,
            "GIT_TEMPLATE_DIR" to templates.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_SSH" to tools.gitSsh.absolutePath,
            "GIT_SSH_VARIANT" to "ssh",
            // Git 优先采用 core.sshCommand，空值也会遮盖 GIT_SSH。由受管路径提供更高优先级的命令。
            "GIT_SSH_COMMAND" to "'${tools.gitSsh.absolutePath.replace("'", "'\\''")}'",
            "GIT_PAGER" to "cat",
            "GIT_NO_REPLACE_OBJECTS" to "1",
            "GIT_OPTIONAL_LOCKS" to "0",
        )
        val configuration = mutableListOf(
            "core.hooksPath" to hooks.path,
            "core.fsmonitor" to "false",
            "core.pager" to "cat",
            "core.editor" to "/system/bin/false",
            "sequence.editor" to "/system/bin/false",
            "core.sshCommand" to "",
            "credential.helper" to "",
            "protocol.ext.allow" to "never",
            "protocol.fd.allow" to "never",
            "protocol.file.allow" to "user",
            "protocol.http.allow" to "always",
            "protocol.https.allow" to "always",
            "protocol.ssh.allow" to "always",
            "protocol.allow" to "never",
            "maintenance.auto" to "false",
            "commit.gpgSign" to "false",
            "tag.gpgSign" to "false",
            "merge.verifySignatures" to "false",
            "submodule.recurse" to "false",
            "diff.ignoreSubmodules" to "all",
            "http.followRedirects" to "false",
            "safe.directory" to "",
        )
        configuration += roots.map { it.canonicalPath }.distinct().map { "safe.directory" to it }
        val nonce = lease[GitRemoteBroker.ENV_NONCE]?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        if (nonce != null) {
            lease[GitRemoteBroker.ENV_HTTP_PROXY]?.takeIf(::isLoopbackEndpoint)?.let { proxy ->
                val prefix = "http://$proxy/"
                configuration += listOf(
                    "url.${prefix}https/.insteadOf" to "https://",
                    "http.$prefix.extraHeader" to "${GitCredentialProxy.NONCE_HEADER}: $nonce",
                    "http.$prefix.proxy" to "",
                )
            }
            lease[GitRemoteBroker.ENV_SSH_BRIDGE]?.takeIf(::isLoopbackEndpoint)?.let { bridge ->
                environment[GitRemoteBroker.ENV_SSH_BRIDGE] = bridge
                environment[GitRemoteBroker.ENV_NONCE] = nonce
            }
        }
        LinuxGitEnvironment.appendConfiguration(environment, configuration)
        LinuxGitEnvironment.appendResourceConfiguration(environment, budget)
        return environment
    }

    @Synchronized
    private fun prepare(git: File): Triple<File, File, File> {
        require(Files.isDirectory(cache.toPath(), NOFOLLOW_LINKS)) { "Git 缓存目录无效。" }
        val exec = managedDirectory("git-exec-path", mapOf(
            "git" to git, "git-remote-http" to tools.gitRemoteHttps, "git-remote-https" to tools.gitRemoteHttps,
            "git-upload-pack" to git, "git-receive-pack" to git, "git-upload-archive" to git,
        ))
        return Triple(exec, managedDirectory("git-empty-template", emptyMap()), managedDirectory("git-empty-hooks", emptyMap()))
    }

    private fun managedDirectory(name: String, links: Map<String, File>): File {
        val directory = File(cache, name).absoluteFile
        val path = directory.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path)
            links.forEach { (entry, target) -> Files.createSymbolicLink(path.resolve(entry), target.absoluteFile.toPath()) }
        }
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Git 受管目录不能是符号链接。" }
        Files.newDirectoryStream(path).use { entries ->
            val actual = entries.associateBy { it.fileName.toString() }
            require(actual.keys == links.keys && actual.all { (entry, link) ->
                Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == links.getValue(entry).absoluteFile.toPath()
            }) { "Git 受管目录内容不匹配。" }
        }
        require(directory.setWritable(false, false)) { "无法设定 Git 受管目录为只读。" }
        return directory
    }

    companion object {
        private val blocked = setOf(
            "upload-pack", "receive-pack", "exec-path", "ext-diff", "textconv", "filters",
            "template", "separate-git-dir", "recurse-submodules", "recursive", "config-env",
            "output", "open-files-in-pager", "helper", "difftool", "no-index", "gpg-sign",
            "sign", "verify-signatures", "edit", "exec", "pathspec-from-file", "output-directory",
            "object-format", "reference", "reference-if-able", "shared", "server-option", "unsafe-paths",
            "directory", "remote", "super-prefix", "prefix", "template-dir", "no-verify", "file", "exclude-from", "rebase",
            "signed", "show-signature", "verify", "edit-description", "no-recurse-submodules", "push-option", "negotiation-tip",
            "contents", "add-file", "add-virtual-file", "exclude-standard", "exclude-per-directory", "follow-tags",
        )
        private val safeCommandConfig = setOf(
            "user.name", "user.email", "author.name", "author.email", "committer.name", "committer.email",
            "color.ui", "core.quotepath", "init.defaultbranch", "commit.gpgsign", "tag.gpgsign",
        )
        private val allowedCommands = setOf(
            "add", "apply", "archive", "blame", "branch", "cat-file", "checkout", "cherry-pick",
            "clean", "clone", "commit", "config", "describe", "diff", "diff-files", "diff-index", "diff-tree",
            "fetch", "for-each-ref", "grep", "init", "log", "ls-files", "ls-remote", "ls-tree",
            "merge", "merge-base", "mv", "pull", "push", "reflog", "remote", "reset", "restore",
            "rev-list", "rev-parse", "revert", "rm", "shortlog", "show", "show-ref", "stash",
            "status", "switch", "tag", "worktree",
        )

        private val optionsWithValues = setOf(
            "--origin", "--message", "--depth", "--shallow-since", "--shallow-exclude", "--filter",
            "--max-count", "--format", "--grep", "--author", "--since", "--until", "--strategy", "--strategy-option",
            "--color-moved-ws", "--diff-filter", "--exclude", "--set-upstream-to", "--sort",
            "--target", "--initial-branch", "--max-age", "--min-age", "--max-parents", "--min-parents",
        )
        private val allowedLongOptions = optionsWithValues.map { it.removePrefix("--") }.toSet() + setOf(
            "quiet", "verbose", "porcelain", "short", "branch", "show-stash", "untracked-files",
            "ahead-behind", "no-ahead-behind", "renames", "no-renames", "ignored", "null", "stat", "summary",
            "name-only", "name-status", "numstat", "shortstat", "patch", "no-patch", "raw", "cached", "staged",
            "exit-code", "check", "binary", "full-index", "no-color", "color", "color-moved", "word-diff",
            "ignore-all-space", "ignore-space-change", "ignore-space-at-eol", "diff-algorithm",
            "oneline", "pretty", "decorate", "no-decorate", "graph", "all", "reverse", "first-parent", "no-merges",
            "merges", "topo-order", "date-order", "date", "ancestry-path", "follow", "abbrev-commit", "no-abbrev",
            "show-current", "list", "contains", "no-contains", "merged", "no-merged", "points-at", "no-track", "track",
            "bare", "mirror", "single-branch", "no-single-branch", "no-checkout", "no-tags", "tags", "progress", "no-progress",
            "prune", "prune-tags", "dry-run", "force", "force-with-lease", "force-if-includes", "set-upstream", "atomic",
            "delete", "no-edit", "no-gpg-sign", "ff", "ff-only", "no-ff", "squash", "no-commit", "abort", "continue",
            "skip", "amend", "allow-empty", "allow-empty-message", "signoff", "no-signoff", "no-verify-signatures",
            "update", "intent-to-add", "ignore-errors", "ignore-unmatch", "ours", "theirs", "conflict", "guess", "no-guess",
            "orphan", "detach", "keep", "hard", "soft", "mixed", "merge", "worktree", "index", "reject", "cached",
            "reverse", "no-renormalize", "refresh", "assume-unchanged", "no-assume-unchanged", "show-toplevel",
            "show-prefix", "is-inside-work-tree", "is-bare-repository", "is-inside-git-dir", "verify", "quiet", "short",
            "heads", "branches", "refs", "symref", "exit-code", "match", "exact-match", "always", "dirty", "broken",
            "abbrev", "object-only", "format", "all-match", "reflog", "no-reflog", "full-history", "simplify-merges",
        )

        fun optionConsumesValue(command: String, option: String): Boolean = option in optionsWithValues || when (option) {
            "--branch" -> command == "clone"
            "-e" -> command == "grep"
            "-b", "-B" -> command in setOf("clone", "checkout")
            "-m" -> command in setOf("commit", "merge", "tag")
            "-o" -> command == "clone"
            "-n" -> command in setOf("log", "show", "rev-list", "shortlog", "tag")
            "-X" -> command in setOf("merge", "pull", "cherry-pick", "revert")
            "-s" -> command in setOf("merge", "pull")
            else -> false
        }

        fun validateArguments(arguments: List<String>) {
            requireHost(arguments.isNotEmpty(), "GIT_ARGUMENT", "请指定 Git 子命令。")
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index++]
                when {
                    argument == "-C" || argument == "--git-dir" || argument == "--work-tree" -> {
                        requireHost(index < arguments.size, "GIT_ARGUMENT", "Git 路径参数缺少值。")
                        index++
                    }
                    argument.startsWith("-C") || argument.startsWith("--git-dir=") || argument.startsWith("--work-tree=") -> Unit
                    argument == "-c" || argument.startsWith("-c") && !argument.startsWith("--") -> {
                        val pair = if (argument == "-c") arguments.getOrNull(index++) else argument.substring(2)
                        val key = pair?.substringBefore('=')?.lowercase(Locale.ROOT)
                        requireHost(key in safeCommandConfig && (key !in setOf("commit.gpgsign", "tag.gpgsign") ||
                            pair?.substringAfter('=') == "false"), "GIT_OPTION_BLOCKED", "Git 配置键不在宿主执行白名单内。")
                    }
                    argument in setOf("--no-pager", "-P", "--literal-pathspecs", "--no-optional-locks") -> Unit
                    argument in setOf("--version", "-v") && index == arguments.size -> return
                    argument in allowedCommands -> {
                        validateSubcommand(argument, arguments.drop(index))
                        return
                    }
                    else -> throw HostToolException("GIT_OPTION_BLOCKED", "Git 子命令或全局选项不在宿主执行白名单内。")
                }
            }
            throw HostToolException("GIT_ARGUMENT", "请指定 Git 子命令。")
        }

        private fun validateSubcommand(command: String, arguments: List<String>) {
            if (command == "config") {
                val keys = setOf("user.name", "user.email")
                val args = arguments.dropWhile { it == "--local" }
                val allowed = when {
                    args.firstOrNull() in setOf("--get", "--get-all") -> args.size == 2 && args[1].lowercase(Locale.ROOT) in keys
                    args.size in 1..2 -> args.first().lowercase(Locale.ROOT) in keys && args.drop(1).none { it.startsWith('-') }
                    else -> false
                }
                requireHost(allowed, "GIT_OPTION_BLOCKED", "宿主 Git 仅开放仓库用户名和邮箱配置。")
                return
            }
            // 只有未被前一选项消耗的 -- 才结束选项解析。
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index++]
                if (argument == "--") {
                    requireHost(arguments.drop(index).none { it.startsWith('-') && it != "-" },
                        "GIT_OPTION_BLOCKED", "用 ./ 指定前导字符为 - 的 Git 文件名。")
                    break
                }
                val key = argument.substringBefore('=')
                if (key.startsWith("--")) {
                    val name = key.removePrefix("--")
                    requireHost(name in allowedLongOptions && (blocked.none { it.startsWith(name) } ||
                        name in setOf("refs", "stat", "summary", "short", "no-edit", "no-gpg-sign") || name == "verify" && command == "rev-parse"),
                        "GIT_OPTION_BLOCKED", "Git 选项可能读取非受管配置或启动外部程序。")
                }
                if (key.startsWith("-") && !key.startsWith("--")) {
                    requireHost('F' !in key && 'S' !in key && 'I' !in key && 'O' !in key &&
                        !(command == "grep" && 'f' in key) &&
                        !(command == "archive" && 'o' in key) &&
                        !(command in setOf("merge", "pull") && 's' in key && !key.startsWith("-s")) &&
                        !(command in setOf("clone", "fetch", "pull", "push", "ls-remote", "archive") && 'u' in key) &&
                        !(command in setOf("tag", "commit", "merge", "cherry-pick", "revert") && 'e' in key) &&
                        !(command == "tag" && 's' in key) &&
                        !(command == "clone" && 'c' in key),
                        "GIT_OPTION_BLOCKED", "Git 短选项可能启动外部程序。")
                }
                val value = if (argument == key && optionConsumesValue(command, key)) {
                    arguments.getOrNull(index++) ?: throw HostToolException("GIT_ARGUMENT", "Git 选项缺少值。")
                } else argument.substringAfter('=', "")
                if (key == "--strategy" || command in setOf("merge", "pull") && key.startsWith("-s")) {
                    val strategy = if (key.startsWith("-s") && key.length > 2) key.drop(2) else value
                    requireHost(strategy in setOf("ort", "recursive", "resolve", "octopus", "ours", "subtree"),
                        "GIT_OPTION_BLOCKED", "宿主 Git 仅允许内置合并策略。")
                }
            }
            requireHost(command != "worktree" || arguments.firstOrNull() == "list",
                "GIT_OPTION_BLOCKED", "宿主 Git 仅支持 worktree list。")
            requireHost(command != "remote" || arguments.firstOrNull() != "ext",
                "GIT_OPTION_BLOCKED", "宿主 Git 拒绝外部传输程序。")
        }

        private fun isLoopbackEndpoint(value: String): Boolean =
            value.startsWith("127.0.0.1:") && value.substringAfter(':').toIntOrNull() in 1..65535
    }
}

internal class HostToolException(val code: String, message: String) : IllegalArgumentException(message)

internal fun requireHost(condition: Boolean, code: String, message: String) {
    if (!condition) throw HostToolException(code, message)
}

private val toolEnvironmentKeys = setOf(
    "TERM", "NO_COLOR", "COLORTERM", "CLICOLOR", "CLICOLOR_FORCE", "TZ", "LANG", "LC_ALL", "LC_CTYPE",
    "COLUMNS", "LINES", "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE", "GIT_COMMITTER_NAME",
    "GIT_COMMITTER_EMAIL", "GIT_COMMITTER_DATE",
)

internal fun hostToolEnvironment(input: Map<String, String>): Map<String, String> =
    mapOf("PATH" to "/system/bin", "HOME" to "/dev/null", "LANG" to "C.UTF-8") + input.filter { (key, value) ->
        key in toolEnvironmentKeys && value.length <= 4096 && value.none(Char::isISOControl)
    }
