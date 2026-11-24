package com.promenar.infinity.data.exec

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale

/** 环境覆盖只管命令级配置。执行前还需约束 Git 自行读取的仓库配置与元数据指针。 */
internal class GitRepositoryGuard(private val translator: HostToolPathTranslator) {
    fun environment(command: HostToolPathTranslator.Command): Map<String, String> {
        val host = HostToolPathTranslator.forHost(translator.roots)
        var cwd = command.directory
        var gitDirectory: File? = null
        var workingTree: File? = null
        var index = 0
        while (index < command.arguments.size) {
            val arg = command.arguments[index++]
            if (!arg.startsWith('-')) break
            val name = arg.substringBefore('=')
            when {
                name == "-C" || arg.startsWith("-C") -> {
                    val path = if (arg == "-C") command.arguments[index++] else arg.substring(2)
                    if (path.isNotEmpty()) cwd = File(path)
                }
                name == "--git-dir" -> gitDirectory = File(if ('=' in arg) arg.substringAfter('=') else command.arguments[index++])
                name == "--work-tree" -> workingTree = File(if ('=' in arg) arg.substringAfter('=') else command.arguments[index++])
                arg == "-c" -> index++
            }
        }
        val operation = command.arguments.getOrNull(index - 1)
        val root = translator.roots.filter { cwd.toPath().startsWith(it.toPath()) }.maxBy { it.toPath().nameCount }
        val result = mutableMapOf<String, String>()
        // Git 从 cwd 向上发现仓库。为根本身保留发现能力，停止点设在其父目录。
        root.parentFile?.let { result["GIT_CEILING_DIRECTORIES"] = it.path }
        var repositoryWorkTree = workingTree ?: cwd
        var discoveredWorkTree: File? = null
        if (gitDirectory == null && operation !in setOf("init", "clone")) {
            for (directory in generateSequence(cwd) { it.parentFile }.takeWhile { it.toPath().startsWith(root.toPath()) }) {
                val dotGit = File(directory, ".git")
                if (Files.exists(dotGit.toPath(), NOFOLLOW_LINKS)) {
                    repositoryWorkTree = workingTree ?: directory
                    discoveredWorkTree = repositoryWorkTree
                    val checked = checkedPath(dotGit, host)
                    if (checked.isFile) {
                        val pointer = readSmall(checked, 4096).trimEnd()
                        requireHost(pointer.startsWith("gitdir: "), "GIT_REPOSITORY", "Git 工作树指针无效。")
                        gitDirectory = resolvePointer(directory, pointer.removePrefix("gitdir: "), host)
                    } else {
                        requireHost(checked.isDirectory, "GIT_REPOSITORY", "Git 元数据目录无效。")
                        gitDirectory = checked
                    }
                    break
                }
                if (File(directory, "HEAD").isFile && File(directory, "objects").isDirectory) {
                    gitDirectory = directory
                    break
                }
            }
        }
        if (gitDirectory != null && gitDirectory.exists()) {
            val common = audit(gitDirectory, host, repositoryWorkTree, operation)
            if (operation == "rebase") {
                val resuming = command.arguments.drop(index).any { it == "--continue" || it == "--skip" }
                auditRebaseState(gitDirectory, host, resuming)
                if (resuming) result["GIT_EDITOR"] = ":"
            }
            result["GIT_DIR"] = gitDirectory.path
            discoveredWorkTree?.let { result["GIT_WORK_TREE"] = it.path }
            if (common != gitDirectory) result["GIT_COMMON_DIR"] = common.path
        }
        return result
    }

    private fun audit(git: File, host: HostToolPathTranslator, cwd: File, operation: String?): File {
        val checked = checkedPath(git, host)
        requireHost(checked.isDirectory, "GIT_REPOSITORY", "Git 元数据目录无效。")
        val commonFile = File(checked, "commondir")
        val common = if (Files.exists(commonFile.toPath(), NOFOLLOW_LINKS)) {
            val pointer = readSmall(checkedPath(commonFile, host), 4096).trimEnd()
            checkedPath(if (pointer.startsWith('/')) File(pointer) else File(checked, pointer), host)
        } else checked
        requireHost(common.isDirectory && checkedPath(File(checked, "HEAD"), host).isFile &&
            checkedPath(File(common, "objects"), host).isDirectory && checkedPath(File(common, "refs"), host).isDirectory,
            "GIT_REPOSITORY", "Git 仓库元数据不完整。")
        val remotes = RemoteAudit()
        for (directory in setOf(checked, common)) {
            for (name in listOf("HEAD", "index", "refs", "objects", "packed-refs", "shallow", "logs", "objects/info")) {
                checkedPath(File(directory, name), host)
            }
            for (name in listOf("objects/info/alternates", "objects/info/http-alternates")) {
                requireHost(!Files.exists(File(directory, name).toPath(), NOFOLLOW_LINKS),
                    "GIT_REPOSITORY", "宿主 Git 不加载仓库外的对象目录。")
            }
            for (name in listOf("config", "config.worktree")) {
                val config = File(directory, name)
                if (Files.exists(config.toPath(), NOFOLLOW_LINKS)) {
                    auditConfig(readSmall(checkedPath(config, host), 256 * 1024), cwd, host, operation, remotes)
                }
            }
        }
        // 部分克隆会把 promisor 名称交给内部 fetch；先确保它有经过校验的 URL。
        requireHost(remotes.withUrl.containsAll(remotes.promisors), "GIT_REPOSITORY", "部分克隆远端缺少受管 URL。")
        return common
    }

    private class RemoteAudit {
        val withUrl = mutableSetOf<String>()
        val promisors = mutableSetOf<String>()
    }

    private fun auditRebaseState(git: File, host: HostToolPathTranslator, resuming: Boolean) {
        for (name in listOf("rebase-merge", "rebase-apply")) {
            val path = File(git, name).toPath()
            if (!Files.exists(path, NOFOLLOW_LINKS)) continue
            requireHost(Files.isDirectory(path, NOFOLLOW_LINKS), "GIT_REPOSITORY", "变基状态目录格式无效。")
            Files.newDirectoryStream(checkedPath(path.toFile(), host).toPath()).use { entries ->
                requireHost(entries.all { Files.isRegularFile(it, NOFOLLOW_LINKS) },
                    "GIT_REPOSITORY", "变基状态只接受普通文件。")
            }
        }
        if (!resuming) return
        fun stateFile(name: String, limit: Int = 4096): String? {
            val file = File(git, name)
            return if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) readSmall(checkedPath(file, host), limit) else null
        }
        // 继续和跳过会执行磁盘上的待办指令，单靠命令行白名单无法约束。
        for (name in listOf("git-rebase-todo", "done")) {
            val todo = stateFile("rebase-merge/$name", 1024 * 1024) ?: continue
            requireHost(todo.lineSequence().all { line ->
                val instruction = line.trimStart()
                val action = instruction.takeWhile { !it.isWhitespace() }
                val argument = instruction.drop(action.length).trimStart()
                action.isEmpty() || action.startsWith('#') || action in rebaseInstructions &&
                    !(action in rebaseMessageInstructions && argument.startsWith("-c"))
            }, "GIT_REPOSITORY", "变基待办包含宿主 Git 不允许执行的指令。")
        }
        val strategy = stateFile("rebase-merge/strategy")?.trim()
        requireHost(strategy == null || strategy in GitHostConfig.builtInStrategies,
            "GIT_REPOSITORY", "宿主 Git 仅允许继续使用内置合并策略的变基。")
        requireHost(listOf("rebase-merge", "rebase-apply").none {
            Files.exists(File(git, "$it/gpg_sign_opt").toPath(), NOFOLLOW_LINKS)
        },
            "GIT_REPOSITORY", "宿主 Git 不加载变基状态中的签名选项。")
        requireHost(stateFile("rebase-apply/apply-opt").isNullOrBlank(),
            "GIT_REPOSITORY", "宿主 Git 不加载变基状态中的额外补丁参数。")
    }

    private fun auditConfig(text: String, cwd: File, host: HostToolPathTranslator, operation: String?, remotes: RemoteAudit) {
        var section: String? = null
        var subsection: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) continue
            requireHost(!trimmed.endsWith('\\'), "GIT_REPOSITORY", "宿主 Git 配置不接受续行。")
            if (trimmed.startsWith('[')) {
                val header = sectionHeader.matchEntire(trimmed)
                section = header?.groups?.get(1)?.value?.lowercase(Locale.ROOT)
                subsection = header?.groups?.get(2)?.value?.removeSurrounding("\"")
                requireHost(section in allowedSettings && (subsection == null || section !in sectionsWithoutSubsections),
                    "GIT_REPOSITORY", "仓库配置段不在宿主 Git 白名单内。")
                continue
            }
            val entry = setting.matchEntire(trimmed)
                ?: throw HostToolException("GIT_REPOSITORY", "仓库配置项格式无效。")
            val key = entry.groupValues[1].lowercase(Locale.ROOT)
            val rawValue = entry.groups[2]?.value
            val allowed = when (section) {
                "advice" -> adviceKey.matches(key) && simpleConfigValue(rawValue) in GitHostConfig.booleanValues
                "branch", "remote" -> key in allowedSettings.getValue(section) || metadataKey.matches(key)
                else -> key in allowedSettings[section].orEmpty()
            }
            requireHost(allowed, "GIT_REPOSITORY", "仓库配置项不在宿主 Git 白名单内。")
            if (operation == "pull" && section in setOf("pull", "branch") && key == "rebase" && rawValue != null) {
                requireHost(simpleConfigValue(rawValue) in GitHostConfig.nonInteractiveRebaseModes,
                    "GIT_REPOSITORY", "宿主 Git 仅允许非交互式变基。")
            }
            if (section == "remote" && key == "promisor" && simpleConfigValue(rawValue) !in falseValues) {
                val name = subsection?.takeIf(promisorName::matches)
                    ?: throw HostToolException("GIT_REPOSITORY", "部分克隆远端名称无效。")
                remotes.promisors += name
            }
            if (section == "remote" && key in setOf("url", "pushurl")) {
                val value = rawValue.orEmpty().trim()
                if (!HostToolPathTranslator.isNetworkGitUrl(value)) {
                    requireHost(value.isNotEmpty() && value.none { it in "\"\\:#;" },
                        "GIT_REPOSITORY", "仓库的本地远端路径需使用授权宿主目录。")
                    val path = if (value.startsWith('/')) File(value) else File(cwd, value)
                    checkedPath(path, host)
                }
                if (key == "url") subsection?.let(remotes.withUrl::add)
            }
        }
    }

    private fun simpleConfigValue(value: String?): String? =
        value?.let(simpleValue::matchEntire)?.groupValues?.drop(1)
            ?.firstOrNull { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    private fun resolvePointer(base: File, path: String, host: HostToolPathTranslator): File {
        if (!path.startsWith('/')) return checkedPath(File(base, path), host)
        return runCatching { checkedPath(File(path), host) }.getOrElse { translator.path(path) }
    }

    private fun checkedPath(file: File, host: HostToolPathTranslator) = host.path(file.absolutePath)

    private fun readSmall(path: File, limit: Int): String {
        requireHost(Files.isRegularFile(path.toPath(), NOFOLLOW_LINKS) && path.length() <= limit,
            "GIT_REPOSITORY", "Git 元数据文件类型或大小不受支持。")
        val bytes = path.inputStream().use { input ->
            val buffer = ByteArray(limit + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        requireHost(bytes.size <= limit && 0.toByte() !in bytes, "GIT_REPOSITORY", "Git 元数据文件过大或格式无效。")
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        val sectionHeader = Regex("""\[([A-Za-z0-9-]+)(?:\s+("(?:[^"\\]|\\.)*"))?\]\s*(?:[#;].*)?""")
        val setting = Regex("""([A-Za-z][A-Za-z0-9-]*)(?:\s*=(.*)|\s*[#;].*)?""")
        val simpleValue = Regex("""\s*(?:([A-Za-z0-9]+)|"([A-Za-z0-9]+)")\s*(?:[#;].*)?""")
        val adviceKey = Regex("[a-z][a-z0-9-]*")
        val metadataKey = Regex("(?:vscode|gh|github|gk)-[a-z0-9-]+")
        val promisorName = Regex("[A-Za-z0-9_][A-Za-z0-9._/-]*")
        val falseValues = setOf("false", "no", "off", "0")
        val sectionsWithoutSubsections = setOf("gpg", "advice", "lfs")
        val rebaseInstructions = setOf("pick", "p", "fixup", "f", "squash", "s", "label", "l", "reset", "t",
            "merge", "m", "update-ref", "u", "noop", "drop", "d")
        val rebaseMessageInstructions = setOf("fixup", "f", "merge", "m")
        val allowedSettings = mapOf(
            "core" to setOf("repositoryformatversion", "filemode", "bare", "logallrefupdates", "ignorecase",
                "precomposeunicode", "symlinks", "quotepath", "autocrlf", "eol", "safecrlf", "longpaths",
                "protectntfs", "protecthfs", "abbrev", "warnambiguousrefs", "sharedrepository", "compression",
                "loosecompression", "bigfilethreshold", "packedgitlimit", "packedgitwindowsize", "deltabasecachelimit",
                "preloadindex", "fscache", "untrackedcache", "splitindex", "trustctime", "checkstat", "hidedotfiles",
                "whitespace", "commentchar", "sparsecheckout", "sparsecheckoutcone"),
            "user" to setOf("name", "email", "useconfigonly", "signingkey"),
            "author" to setOf("name", "email"),
            "committer" to setOf("name", "email"),
            "commit" to setOf("gpgsign", "verbose", "status", "cleanup"),
            "gpg" to setOf("format"),
            "remote" to setOf("url", "pushurl", "fetch", "push", "tagopt", "mirror", "prune", "prunetags",
                "promisor", "partialclonefilter", "gh-resolved", "gh-default-branch"),
            "branch" to setOf("remote", "merge", "pushremote", "rebase", "description", "autosetuprebase", "autosetupmerge"),
            "extensions" to setOf("worktreeconfig", "objectformat", "refstorage"),
            "init" to setOf("defaultbranch"),
            "push" to setOf("default", "autosetupremote", "followtags"),
            "pull" to setOf("rebase", "ff"),
            "merge" to setOf("ff", "conflictstyle", "renames", "renamelimit"),
            "diff" to setOf("algorithm", "renames", "noprefix", "context", "interhunkcontext", "mnemonicprefix"),
            "status" to setOf("showuntrackedfiles", "short", "branch", "aheadbehind"),
            "gc" to setOf("auto", "autopacklimit"),
            "fetch" to setOf("prune", "prunetags", "tags"),
            "rebase" to setOf("autostash", "autosquash", "updaterefs", "missingcommitscheck", "stat", "abbreviatecommands", "backend"),
            "rerere" to setOf("enabled", "autoupdate"),
            "pack" to setOf("windowmemory", "packsizelimit", "threads", "deltacachesize", "deltacachelimit", "compression",
                "depth", "window", "usebitmaps", "usesparse", "writebitmaphashcache"),
            "color" to setOf("ui", "branch", "diff", "interactive", "status", "pager", "grep", "decorate"),
            "advice" to emptySet(),
            "log" to setOf("date", "decorate", "abbrevcommit", "follow", "showroot", "mailmap"),
            "tag" to setOf("sort", "forcesignannotated", "gpgsign"),
            "submodule" to setOf("url", "active", "branch", "shallow", "ignore"),
            "lfs" to setOf("repositoryformatversion", "fetchexclude", "fetchinclude", "activitytimeout", "concurrenttransfers", "locksverify"),
            "checkout" to setOf("defaultremote", "workers", "guess"),
            "clone" to setOf("defaultremotename", "rejectshallow"),
            "grep" to setOf("linenumber", "patterntype", "extendedregexp"),
            "stash" to setOf("showpatch", "showstat", "showincludeuntracked"),
            "feature" to setOf("manyfiles", "experimental"),
            "index" to setOf("version", "threads", "sparse"),
            "receive" to setOf("denycurrentbranch", "denynonfastforwards"),
            "transfer" to setOf("fsckobjects", "unpacklimit"),
            "github" to setOf("user"),
        )
    }
}
