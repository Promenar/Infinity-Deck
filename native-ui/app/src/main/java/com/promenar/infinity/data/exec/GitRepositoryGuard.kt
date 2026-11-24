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
            val common = audit(gitDirectory, host, repositoryWorkTree)
            result["GIT_DIR"] = gitDirectory.path
            discoveredWorkTree?.let { result["GIT_WORK_TREE"] = it.path }
            if (common != gitDirectory) result["GIT_COMMON_DIR"] = common.path
        }
        return result
    }

    private fun audit(git: File, host: HostToolPathTranslator, cwd: File): File {
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
                if (Files.exists(config.toPath(), NOFOLLOW_LINKS)) auditConfig(readSmall(checkedPath(config, host), 256 * 1024), cwd, host)
            }
        }
        return common
    }

    private fun auditConfig(text: String, cwd: File, host: HostToolPathTranslator) {
        var section: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) continue
            requireHost(!trimmed.endsWith('\\'), "GIT_REPOSITORY", "宿主 Git 配置不接受续行。")
            if (trimmed.startsWith('[')) {
                section = sectionHeader.matchEntire(trimmed)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                requireHost(section in allowedSettings, "GIT_REPOSITORY", "仓库配置段不在宿主 Git 白名单内。")
                continue
            }
            val key = setting.matchEntire(trimmed)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            requireHost(key != null && key in allowedSettings[section].orEmpty(),
                "GIT_REPOSITORY", "仓库配置项不在宿主 Git 白名单内。")
            if (section == "remote" && key in setOf("url", "pushurl")) {
                val value = trimmed.substringAfter('=', "").trim()
                if (!HostToolPathTranslator.isNetworkGitUrl(value)) {
                    requireHost(value.isNotEmpty() && value.none { it in "\"\\:#;" },
                        "GIT_REPOSITORY", "仓库的本地远端路径需使用授权宿主目录。")
                    val path = if (value.startsWith('/')) File(value) else File(cwd, value)
                    checkedPath(path, host)
                }
            }
        }
    }

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
        val sectionHeader = Regex("""\[([A-Za-z0-9-]+)(?:\s+"(?:[^"\\]|\\.)*")?\]\s*(?:[#;].*)?""")
        val setting = Regex("""([A-Za-z][A-Za-z0-9-]*)(?:\s*=.*|\s*[#;].*)?""")
        val allowedSettings = mapOf(
            "core" to setOf("repositoryformatversion", "filemode", "bare", "logallrefupdates", "ignorecase",
                "precomposeunicode", "symlinks", "quotepath", "autocrlf", "eol", "safecrlf", "longpaths",
                "protectntfs", "protecthfs", "abbrev", "warnambiguousrefs"),
            "user" to setOf("name", "email", "useconfigonly"),
            "author" to setOf("name", "email"),
            "committer" to setOf("name", "email"),
            "remote" to setOf("url", "pushurl", "fetch", "push", "tagopt", "mirror", "prune", "prunetags"),
            "branch" to setOf("remote", "merge", "pushremote", "rebase", "description"),
            "extensions" to setOf("worktreeconfig", "objectformat", "refstorage"),
            "init" to setOf("defaultbranch"),
            "push" to setOf("default", "autosetupremote", "followtags"),
            "pull" to setOf("rebase", "ff"),
            "merge" to setOf("ff", "conflictstyle", "renames", "renamelimit"),
            "diff" to setOf("algorithm", "renames", "noprefix", "context", "interhunkcontext", "mnemonicprefix"),
            "status" to setOf("showuntrackedfiles", "short", "branch", "aheadbehind"),
            "gc" to setOf("auto", "autopacklimit"),
            "fetch" to setOf("prune", "prunetags", "tags"),
        )
    }
}
