package com.promenar.infinity.domain.tool

import java.util.Locale

enum class CommandSafetyVerdict {
    /** 毁灭性根目录破坏命令：两档模式均直接硬拒绝，不可审批、不可限时放行。 */
    BLOCKED_CATASTROPHIC,

    /** 严格白名单只读查勘命令：按风险审批模式下免审批直接放行。 */
    SAFE_READ_ONLY,

    /** 其它命令：按风险审批模式下逐条审批（支持限时免审批），免批准模式下跳过。 */
    REQUIRES_APPROVAL,
}

data class CommandSafetyDecision(
    val verdict: CommandSafetyVerdict,
    val reason: String? = null,
    val blockedTarget: String? = null,
)

/**
 * 命令安全分类器（fail-closed）：
 * 1) 毁灭性根目录清空/格式化/改权命令一律硬拦截（两档模式均不可审批执行）；
 * 2) 仅对经词法解析确认零副作用、零重定向、零命令替换的严格只读命令白名单放行；
 * 3) 其余命令全部归入需审批档位。
 */
object ShellCommandSafetyClassifier {
    const val ERROR_CODE = "EXEC_BLOCKED_CATASTROPHIC_COMMAND"

    private const val MAX_NESTED_DEPTH = 4
    private const val SECRET_STORE_TOKEN = "infinity_encrypted_secrets"

    private val STATIC_PROTECTED_ROOTS = setOf(
        "/",
        // Android 共享存储根
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
        "/sdcard",
        "/mnt/sdcard",
        // Android 数据与本应用包根
        "/data",
        "/data/data",
        "/data/user",
        "/data/user/0",
        "/data/user/0/com.promenar.infinity",
        "/data/data/com.promenar.infinity",
        "/data/user/0/com.promenar.infinity/files",
        "/data/data/com.promenar.infinity/files",
        "/data/user/0/com.promenar.infinity/databases",
        "/data/data/com.promenar.infinity/databases",
        "/data/user/0/com.promenar.infinity/shared_prefs",
        "/data/data/com.promenar.infinity/shared_prefs",
        "/data/user/0/com.promenar.infinity/no_backup",
        "/data/data/com.promenar.infinity/no_backup",
        "/data/user/0/com.promenar.infinity/files/session_workspaces",
        "/data/data/com.promenar.infinity/files/session_workspaces",
        "/data/user/0/com.promenar.infinity/files/linux",
        "/data/data/com.promenar.infinity/files/linux",
        "/data/user/0/com.promenar.infinity/files/knowledge_base",
        "/data/data/com.promenar.infinity/files/knowledge_base",
        "/data/user/0/com.promenar.infinity/files/agent_skills",
        "/data/data/com.promenar.infinity/files/agent_skills",
        "/data/user/0/com.promenar.infinity/files/workspaces",
        "/data/data/com.promenar.infinity/files/workspaces",
        // Linux proot 根、核心挂载与系统目录
        "/workspace",
        "/home",
        "/home/session",
        "/mnt",
        "/mnt/skills",
        "/root",
        "/etc",
        "/usr",
        "/bin",
        "/sbin",
        "/lib",
        "/lib64",
        "/var",
        "/proc",
        "/sys",
        "/dev",
        "/opt",
        "/boot",
    )

    private val SHELL_WRAPPERS = setOf("sh", "bash", "ash", "dash", "zsh", "ksh", "su")
    private val DESTRUCTIVE_DELETE_CMDS = setOf("rm", "unlink", "rmdir", "shred", "wipefs")
    private val RECURSIVE_PERM_CMDS = setOf("chmod", "chown", "chgrp")

    private val PURE_READ_ONLY_CMDS = setOf(
        "pwd", "whoami", "id", "uname", "date", "hostname", "nproc", "arch", "true", "false",
        "ls", "dir", "stat", "file", "wc", "du", "df", "realpath", "readlink", "dirname", "basename",
        "md5sum", "sha1sum", "sha256sum", "sha512sum", "cksum", "sum", "cmp", "diff",
        "cat", "head", "tail", "nl", "od", "hexdump", "xxd", "strings", "uniq", "cut", "tr",
        "fold", "column", "rev", "tac", "paste", "comm", "join",
        "grep", "egrep", "fgrep", "echo", "printf", "which", "type", "test", "[",
    )

    private val GIT_READ_ONLY_SUBCOMMANDS = setOf(
        "status", "diff", "log", "show", "rev-parse", "ls-files", "ls-tree", "cat-file",
        "describe", "shortlog", "blame", "grep", "merge-base", "rev-list", "show-ref",
        "name-rev", "check-ignore",
    )

    private val GIT_READ_ONLY_BRANCH_FLAGS = setOf(
        "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "-l", "--list",
        "--show-current", "--contains", "--no-contains", "--merged", "--no-merged",
        "--no-color", "--color", "-i", "--ignore-case", "--abbrev", "--no-abbrev",
    )

    private val FFMPEG_QUERY_FLAGS = setOf(
        "-version", "-buildconf", "-formats", "-muxers", "-demuxers", "-devices",
        "-codecs", "-decoders", "-encoders", "-bsfs", "-protocols", "-filters",
        "-pix_fmts", "-layouts", "-sample_fmts", "-hwaccels", "-h", "-help", "--help",
        "-L", "-license", "-hide_banner", "-v", "-loglevel",
    )

    private val PIP_READ_ONLY_SUBCOMMANDS = setOf("list", "show", "freeze", "check", "--version", "-V")

    private val UNSET_VAR_ROOT_PATTERN = Regex(
        """^\$(?:[A-Za-z_][A-Za-z0-9_]*|\{[A-Za-z_][A-Za-z0-9_]*\})/+(\*|\.\.?|\.\*)?/?$""",
    )
    private val PROTECTED_ENV_ROOT_PATTERN = Regex(
        """^\$(?:INFINITY_WORKSPACE|\{INFINITY_WORKSPACE\}|NEXARA_WORKSPACE|\{NEXARA_WORKSPACE\}|INFINITY_SKILLS|\{INFINITY_SKILLS\}|HOME|\{HOME\})(/+(\*|\.\.?|\.\*)?)?/?$""",
    )
    private val PYTHON_DANGEROUS_CALL_PATTERNS = listOf(
        Regex("""shutil\.rmtree\s*\(\s*(['"])([^'"]*)\1"""),
        Regex("""os\.(?:remove|unlink|rmdir|removedirs)\s*\(\s*(['"])([^'"]*)\1"""),
        Regex("""Path\s*\(\s*(['"])([^'"]*)\1\s*\)\s*\.\s*(?:rmdir|unlink)\s*\("""),
    )
    private val PYTHON_SHELL_EXEC_PATTERNS = listOf(
        Regex("""os\.(?:system|popen)\s*\(\s*(['"])([^'"]+)\1"""),
        Regex("""subprocess\.(?:run|call|check_call|check_output|Popen)\s*\(\s*(['"])([^'"]+)\1"""),
    )
    private val PYTHON_SUBPROCESS_LIST_PATTERN = Regex(
        """subprocess\.(?:run|call|check_call|check_output|Popen)\s*\(\s*\[([^\]]+)]""",
    )

    fun classifyShell(
        command: String,
        appDataDir: String? = null,
        @Suppress("UNUSED_PARAMETER") usesLinux: Boolean = false,
    ): CommandSafetyDecision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandSafetyDecision(CommandSafetyVerdict.REQUIRES_APPROVAL)
        val protectedRoots = buildProtectedRoots(appDataDir)

        // 第一道防线：毁灭性根目录破坏硬拦截（任何复杂语法、嵌套、引号或命令替换均扫描）。
        detectCatastrophicShell(trimmed, protectedRoots, depth = 0)?.let { return it }

        // 第二道防线：严格只读命令白名单（fail-closed，任一不确定语法直接落回需审批）。
        return if (isStrictReadOnlyShell(trimmed)) {
            CommandSafetyDecision(CommandSafetyVerdict.SAFE_READ_ONLY)
        } else {
            CommandSafetyDecision(CommandSafetyVerdict.REQUIRES_APPROVAL)
        }
    }

    fun classifyPython(code: String, appDataDir: String? = null): CommandSafetyDecision {
        if (code.isBlank()) return CommandSafetyDecision(CommandSafetyVerdict.REQUIRES_APPROVAL)
        return classifyPythonWithRoots(code, buildProtectedRoots(appDataDir))
    }

    private fun classifyPythonWithRoots(code: String, protectedRoots: Set<String>): CommandSafetyDecision {
        for (pattern in PYTHON_DANGEROUS_CALL_PATTERNS) {
            for (match in pattern.findAll(code)) {
                val target = match.groupValues[2].trim()
                if (isCatastrophicPathTarget(target, protectedRoots, checkEnvExpansion = false)) {
                    return blocked(target, "禁止在 Python 中删除系统或应用根目录（$target）")
                }
            }
        }
        for (pattern in PYTHON_SHELL_EXEC_PATTERNS) {
            for (match in pattern.findAll(code)) {
                val inner = match.groupValues[2]
                detectCatastrophicShell(inner, protectedRoots, depth = 1)?.let { return it }
            }
        }
        for (match in PYTHON_SUBPROCESS_LIST_PATTERN.findAll(code)) {
            val items = Regex("""(['"])([^'"]*)\1""").findAll(match.groupValues[1]).map { it.groupValues[2] }.toList()
            if (items.isNotEmpty()) {
                inspectSegment(items.map { Token(it, it) }, protectedRoots, depth = 1, upstream = emptyList(), trackedCwd = null)?.let { return it }
            }
        }
        return CommandSafetyDecision(CommandSafetyVerdict.REQUIRES_APPROVAL)
    }

    fun blockedGuidance(decision: CommandSafetyDecision): String =
        (decision.reason ?: "该命令试图删除或破坏系统、存储或应用关键根目录，已被安全门禁永久拦截。") +
            "此保护在所有审批模式下均强制生效且不可跳过；如确需清理临时文件，请明确指定工作区内的具体子目录路径。"

    private fun buildProtectedRoots(appDataDir: String?): Set<String> {
        if (appDataDir.isNullOrBlank()) return STATIC_PROTECTED_ROOTS
        val clean = normalizeLexicalPath(appDataDir) ?: return STATIC_PROTECTED_ROOTS
        val extra = linkedSetOf(clean)
        val suffixes = listOf(
            "files", "databases", "shared_prefs", "no_backup",
            "files/session_workspaces", "files/linux", "files/knowledge_base",
            "files/agent_skills", "files/workspaces",
        )
        suffixes.forEach { extra.add("$clean/$it") }
        val alias = when {
            clean.startsWith("/data/user/0/") -> "/data/data/" + clean.removePrefix("/data/user/0/")
            clean.startsWith("/data/data/") -> "/data/user/0/" + clean.removePrefix("/data/data/")
            else -> null
        }
        if (alias != null) {
            extra.add(alias)
            suffixes.forEach { extra.add("$alias/$it") }
        }
        return STATIC_PROTECTED_ROOTS + extra
    }

    private fun detectCatastrophicShell(
        command: String,
        protectedRoots: Set<String>,
        depth: Int,
    ): CommandSafetyDecision? {
        if (depth > MAX_NESTED_DEPTH) return null
        val lexed = lexShell(command)

        // 先递归检查所有 $(...) 与 `...` 命令替换体，防止通过内嵌替换绕过拦截。
        for (sub in lexed.substitutions) {
            detectCatastrophicShell(sub, protectedRoots, depth + 1)?.let { return it }
        }

        // 将 token 流按分隔符拆成若干命令段（并跟踪同管道内的上游段以及同命令内的 cd 目录切换）。
        val pipelines = splitIntoPipelines(lexed.items)
        var trackedCwd: String? = null
        for (pipeline in pipelines) {
            for (index in pipeline.indices) {
                val segment = pipeline[index]
                val upstream = pipeline.subList(0, index)
                inspectSegment(segment, protectedRoots, depth, upstream, trackedCwd)?.let { return it }
                trackedCwd = updateTrackedCwd(segment, trackedCwd)
            }
        }
        return null
    }

    private fun updateTrackedCwd(rawTokens: List<Token>, currentCwd: String?): String? {
        val tokens = stripLeadingAssignmentsAndWrappers(rawTokens)
        if (tokens.isEmpty() || commandBasename(tokens[0].value) != "cd") return currentCwd
        val dest = extractPositionalArgs(tokens.drop(1)).firstOrNull()?.value ?: return "/"
        return when {
            dest.startsWith('/') -> normalizeLexicalPath(dest)
            currentCwd != null -> normalizeLexicalPath("$currentCwd/$dest")
            else -> null
        }
    }

    private fun inspectSegment(
        rawTokens: List<Token>,
        protectedRoots: Set<String>,
        depth: Int,
        upstream: List<List<Token>>,
        trackedCwd: String? = null,
    ): CommandSafetyDecision? {
        val tokens = stripLeadingAssignmentsAndWrappers(rawTokens)
        if (tokens.isEmpty()) return null
        val cmdName = commandBasename(tokens[0].value)
        val args = tokens.drop(1)

        // eval / sh -c / bash -c / su -c 等嵌套 shell 解包
        if (cmdName == "eval") {
            val joined = args.joinToString(" ") { it.value }
            return detectCatastrophicShell(joined, protectedRoots, depth + 1)
        }
        if (cmdName in SHELL_WRAPPERS) {
            val cIndex = args.indexOfFirst { it.value == "-c" || (it.value.startsWith("-") && !it.value.startsWith("--") && 'c' in it.value) }
            if (cIndex >= 0 && cIndex + 1 < args.size) {
                return detectCatastrophicShell(args[cIndex + 1].value, protectedRoots, depth + 1)
            }
            return null
        }

        // python / python3 -c "..."
        if (cmdName in setOf("python", "python3")) {
            val cIndex = args.indexOfFirst { it.value == "-c" }
            if (cIndex >= 0 && cIndex + 1 < args.size) {
                val pyDecision = classifyPythonWithRoots(args[cIndex + 1].value, protectedRoots)
                if (pyDecision.verdict == CommandSafetyVerdict.BLOCKED_CATASTROPHIC) return pyDecision
            }
            return null
        }

        // xargs rm / shred / rmdir：同时检查 xargs 自身目标与上游 echo/printf/find 的目标
        if (cmdName == "xargs") {
            val subStart = findXargsCommandIndex(args)
            if (subStart >= 0) {
                val subTokens = args.subList(subStart, args.size)
                inspectSegment(subTokens, protectedRoots, depth + 1, emptyList(), trackedCwd)?.let { return it }
                val subCmd = commandBasename(subTokens[0].value)
                if (subCmd in DESTRUCTIVE_DELETE_CMDS) {
                    val lastUpstream = upstream.lastOrNull()?.let(::stripLeadingAssignmentsAndWrappers).orEmpty()
                    if (lastUpstream.isNotEmpty()) {
                        val upCmd = commandBasename(lastUpstream[0].value)
                        val upTargets = when (upCmd) {
                            "echo", "printf" -> lastUpstream.drop(1).filterNot { it.value.startsWith("-") }
                            "find" -> extractFindRoots(lastUpstream.drop(1))
                            else -> emptyList()
                        }
                        for (t in upTargets) {
                            if (isCatastrophicPathTarget(t.value, protectedRoots, trackedCwd = trackedCwd) ||
                                isCatastrophicPathTarget(t.raw, protectedRoots, trackedCwd = trackedCwd)) {
                                return blocked(t.value, "禁止通过管道与 xargs 对根目录执行破坏性删除（${t.value}）")
                            }
                        }
                    }
                }
            }
            return null
        }

        // rm / unlink / rmdir / shred / wipefs
        if (cmdName in DESTRUCTIVE_DELETE_CMDS) {
            val targets = extractPositionalArgs(args)
            for (target in targets) {
                if (isCatastrophicPathTarget(target.raw, protectedRoots, trackedCwd = trackedCwd) ||
                    isCatastrophicPathTarget(target.value, protectedRoots, trackedCwd = trackedCwd)) {
                    return blocked(target.value, "禁止对根目录或受保护关键根路径执行 $cmdName（${target.value}）")
                }
            }
            return null
        }

        // mkfs / mkfs.* / mke2fs
        if (cmdName == "mkfs" || cmdName.startsWith("mkfs.") || cmdName == "mke2fs") {
            return blocked(args.lastOrNull()?.value ?: cmdName, "禁止执行文件系统格式化命令 $cmdName")
        }

        // dd of=...
        if (cmdName == "dd") {
            for (arg in args) {
                if (arg.value.startsWith("of=")) {
                    val dest = arg.value.removePrefix("of=").trim()
                    if (dest != "/dev/null" && (dest.startsWith("/dev/") || isCatastrophicPathTarget(dest, protectedRoots, trackedCwd = trackedCwd))) {
                        return blocked(dest, "禁止使用 dd 覆盖设备或关键根目录（$dest）")
                    }
                }
            }
            return null
        }

        // find ... -delete / -exec rm ...
        if (cmdName == "find") {
            val hasDelete = args.any { it.value == "-delete" }
            val execDestructive = hasDestructiveFindExec(args)
            if (hasDelete || execDestructive) {
                val roots = extractFindRoots(args).ifEmpty { listOf(Token(".", ".")) }
                for (root in roots) {
                    if (isCatastrophicPathTarget(root.raw, protectedRoots, trackedCwd = trackedCwd) ||
                        isCatastrophicPathTarget(root.value, protectedRoots, trackedCwd = trackedCwd)) {
                        return blocked(root.value, "禁止使用 find 对根目录执行批量删除（${root.value}）")
                    }
                }
            }
            return null
        }

        // chmod / chown / chgrp 递归作用于根
        if (cmdName in RECURSIVE_PERM_CMDS) {
            val recursive = args.any { it.value == "--recursive" || (it.value.startsWith("-") && !it.value.startsWith("--") && 'R' in it.value) }
            if (recursive) {
                // 第一个非选项参数通常是 mode/owner，后续是路径
                val positionals = extractPositionalArgs(args).drop(1)
                for (target in positionals) {
                    if (isCatastrophicPathTarget(target.raw, protectedRoots, trackedCwd = trackedCwd) ||
                        isCatastrophicPathTarget(target.value, protectedRoots, trackedCwd = trackedCwd)) {
                        return blocked(target.value, "禁止对根目录递归执行 $cmdName（${target.value}）")
                    }
                }
            }
            return null
        }

        // mv 移走受保护根本身
        if (cmdName == "mv") {
            val positionals = extractPositionalArgs(args)
            val sources = if (args.any { it.value == "-t" || it.value.startsWith("--target-directory") }) {
                positionals
            } else {
                positionals.dropLast(1)
            }
            for (src in sources) {
                if (isCatastrophicPathTarget(src.raw, protectedRoots, trackedCwd = trackedCwd) ||
                    isCatastrophicPathTarget(src.value, protectedRoots, trackedCwd = trackedCwd)) {
                    return blocked(src.value, "禁止移动根目录或受保护关键根路径（${src.value}）")
                }
            }
            return null
        }

        return null
    }

    private fun isStrictReadOnlyShell(command: String): Boolean {
        val lexed = lexShell(command)
        if (!lexed.clean) return false
        if (lexed.substitutions.isNotEmpty()) return false
        if (lexed.hasDisallowedSyntax) return false
        if (command.contains(SECRET_STORE_TOKEN, ignoreCase = true)) return false

        // 仅允许简单命令、管道 | 以及 && 串联，且每段必须严格命中白名单
        val segments = mutableListOf<List<Token>>()
        var current = mutableListOf<Token>()
        for (item in lexed.items) {
            when (item) {
                is LexItem.Word -> current.add(item.token)
                is LexItem.Operator -> when (item.op) {
                    "|", "&&" -> {
                        if (current.isEmpty()) return false
                        segments.add(current)
                        current = mutableListOf()
                    }
                    else -> return false
                }
                is LexItem.Redirection -> {
                    // 仅放行完全无副作用的 stderr 静默/合并重定向
                    if (item.spec !in setOf("2>&1", "2>/dev/null")) return false
                }
            }
        }
        if (current.isEmpty()) return false
        segments.add(current)

        return segments.all(::isStrictReadOnlySegment)
    }

    private fun isStrictReadOnlySegment(tokens: List<Token>): Boolean {
        if (tokens.isEmpty()) return false
        // 拒绝带环境变量赋值或奇怪前缀的调用
        if (isEnvAssignment(tokens[0].value)) return false
        // 拒绝参数中引用密钥文件
        if (tokens.any { it.value.contains(SECRET_STORE_TOKEN, ignoreCase = true) }) return false

        val cmd = commandBasename(tokens[0].value)
        val args = tokens.drop(1).map { it.value }

        return when (cmd) {
            in PURE_READ_ONLY_CMDS -> true
            "sort" -> args.none { it == "-o" || it == "--output" || it.startsWith("--output=") || it.startsWith("--compress-program") }
            "tree" -> args.none { it == "-o" || it == "--output" || it.startsWith("--output=") }
            "rg" -> args.none { it == "--pre" || it.startsWith("--pre=") }
            "command" -> args.size == 2 && (args[0] == "-v" || args[0] == "-V")
            "find" -> args.none {
                it in setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
            }
            "git" -> isReadOnlyGit(args)
            "ffprobe" -> args.none { it == "-report" }
            "ffmpeg" -> isReadOnlyFfmpeg(args)
            "python", "python3" -> isReadOnlyPython(args)
            "pip", "pip3" -> args.firstOrNull() in PIP_READ_ONLY_SUBCOMMANDS
            else -> false
        }
    }

    private fun isReadOnlyGit(args: List<String>): Boolean {
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            val flag = args[i]
            when {
                flag == "-C" -> {
                    if (i + 1 >= args.size) return false
                    i += 2
                }
                flag == "--no-pager" || flag == "-P" || flag == "--literal-pathspecs" || flag == "--glob-pathspecs" -> i++
                flag == "--version" || flag == "-v" || flag == "--help" -> return i == args.lastIndex
                flag.startsWith("--git-dir=") || flag.startsWith("--work-tree=") -> i++
                else -> return false // 拒绝 -c / --exec-path 等可注入执行的全局参数
            }
        }
        if (i >= args.size) return false
        val sub = args[i]
        val rest = args.subList(i + 1, args.size)
        return when (sub) {
            in GIT_READ_ONLY_SUBCOMMANDS -> rest.none { it == "--output" || it.startsWith("--output=") || it.startsWith("--ext-diff") }
            "branch" -> isReadOnlyGitBranch(rest)
            "tag" -> rest.isEmpty() || (rest.all { it in setOf("-l", "--list", "-n") || !it.startsWith("-") } && rest.any { it == "-l" || it == "--list" })
            "remote" -> rest.isEmpty() || rest == listOf("-v") || rest == listOf("--verbose") || rest.firstOrNull() in setOf("show", "get-url")
            "config" -> rest.any { it in setOf("--get", "--get-all", "--list", "-l", "--show-origin") } &&
                rest.none { it in setOf("--unset", "--unset-all", "--add", "--replace-all", "--edit", "-e", "--rename-section", "--remove-section") }
            "stash" -> rest.firstOrNull() in setOf("list", "show")
            else -> false
        }
    }

    private fun isReadOnlyGitBranch(args: List<String>): Boolean {
        if (args.isEmpty()) return true
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in GIT_READ_ONLY_BRANCH_FLAGS -> i++
                a.startsWith("--format=") || a.startsWith("--sort=") || a.startsWith("--color=") || a.startsWith("--abbrev=") -> i++
                a in setOf("--contains", "--no-contains", "--merged", "--no-merged") -> i += 2
                else -> return false
            }
        }
        return true
    }

    private fun isReadOnlyFfmpeg(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        var hasQuery = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-v" || a == "-loglevel" -> {
                    if (i + 1 >= args.size) return false
                    i += 2
                }
                a == "-hide_banner" -> i++
                a in FFMPEG_QUERY_FLAGS -> {
                    hasQuery = true
                    i++
                }
                else -> return false
            }
        }
        return hasQuery
    }

    private fun isReadOnlyPython(args: List<String>): Boolean {
        if (args == listOf("--version") || args == listOf("-V")) return true
        return args.size >= 3 && args[0] == "-m" && args[1] == "py_compile" && args.drop(2).none { it.startsWith("-") }
    }

    private fun isCatastrophicPathTarget(
        rawTarget: String,
        protectedRoots: Set<String>,
        checkEnvExpansion: Boolean = true,
        trackedCwd: String? = null,
    ): Boolean {
        val cleaned = rawTarget.trim().trim('"', '\'')
        if (cleaned.isEmpty()) return false
        if (cleaned in setOf("~", "~/", "~/*", "~/.*", "~/.", "~/..")) return true
        if (checkEnvExpansion) {
            if (UNSET_VAR_ROOT_PATTERN.matches(cleaned)) return true
            if (PROTECTED_ENV_ROOT_PATTERN.matches(cleaned)) return true
        }
        // 处理同命令前序 `cd /` 或 `cd /sdcard` 后跟随 `rm -rf *` / `.` / `./*`
        val resolvedAgainstCwd = if (!cleaned.startsWith('/') && trackedCwd != null) {
            when (cleaned) {
                "*", ".*", ".", "./", "./*", "./.*", "./." -> trackedCwd
                else -> "$trackedCwd/$cleaned"
            }
        } else {
            cleaned
        }
        // 剥离结尾的通配符 /* 或 /.* 或 /. 等清空根下全部内容的写法
        var candidate = resolvedAgainstCwd
        while (candidate.endsWith("/*") || candidate.endsWith("/.*") || candidate.endsWith("/.")) {
            candidate = candidate.substringBeforeLast('/').ifEmpty { "/" }
        }
        if (candidate == "/*" || candidate == "/.*" || candidate == "/.") return true
        val normalized = normalizeLexicalPath(candidate) ?: return false
        return normalized in protectedRoots
    }

    internal fun normalizeLexicalPath(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith('/')) return null
        val stack = ArrayDeque<String>()
        for (part in trimmed.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
    }

    private fun stripLeadingAssignmentsAndWrappers(tokens: List<Token>): List<Token> {
        var i = 0
        while (i < tokens.size) {
            val v = tokens[i].value
            if (isEnvAssignment(v)) {
                i++
                continue
            }
            val base = commandBasename(v)
            when (base) {
                "command", "nohup" -> {
                    i++
                    while (i < tokens.size && tokens[i].value.startsWith("-")) i++
                }
                "env" -> {
                    i++
                    while (i < tokens.size && (tokens[i].value.startsWith("-") || isEnvAssignment(tokens[i].value))) i++
                }
                "busybox" -> i++
                "timeout" -> {
                    i++
                    while (i < tokens.size && tokens[i].value.startsWith("-")) {
                        val f = tokens[i].value
                        i += if (f in setOf("-k", "-s", "--kill-after", "--signal")) 2 else 1
                    }
                    if (i < tokens.size) i++ // duration
                }
                "nice" -> {
                    i++
                    while (i < tokens.size && tokens[i].value.startsWith("-")) {
                        i += if (tokens[i].value == "-n") 2 else 1
                    }
                }
                else -> break
            }
        }
        return tokens.subList(i, tokens.size)
    }

    private fun findXargsCommandIndex(args: List<Token>): Int {
        var i = 0
        while (i < args.size) {
            val v = args[i].value
            if (!v.startsWith("-")) return i
            if (v == "--") return if (i + 1 < args.size) i + 1 else -1
            i += if (v in setOf("-I", "-i", "-L", "-l", "-n", "-P", "-s", "-E", "-e", "-d", "-a")) 2 else 1
        }
        return -1
    }

    private fun extractPositionalArgs(args: List<Token>): List<Token> {
        val result = mutableListOf<Token>()
        var afterDoubleDash = false
        for (arg in args) {
            if (!afterDoubleDash && arg.value == "--") {
                afterDoubleDash = true
                continue
            }
            if (afterDoubleDash || !arg.value.startsWith("-") || arg.value == "-") {
                result.add(arg)
            }
        }
        return result
    }

    private fun extractFindRoots(args: List<Token>): List<Token> {
        val roots = mutableListOf<Token>()
        for (arg in args) {
            if (arg.value.startsWith("-") || arg.value in setOf("(", ")", "!", ",")) break
            roots.add(arg)
        }
        return roots
    }

    private fun hasDestructiveFindExec(args: List<Token>): Boolean {
        for (i in args.indices) {
            if (args[i].value in setOf("-exec", "-execdir", "-ok", "-okdir") && i + 1 < args.size) {
                val cmd = commandBasename(args[i + 1].value)
                if (cmd in DESTRUCTIVE_DELETE_CMDS || cmd in SHELL_WRAPPERS) return true
            }
        }
        return false
    }

    private fun isEnvAssignment(word: String): Boolean {
        val eq = word.indexOf('=')
        if (eq <= 0) return false
        val name = word.substring(0, eq)
        return (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun commandBasename(word: String): String =
        word.substringAfterLast('/').lowercase(Locale.ROOT)

    private fun blocked(target: String, reason: String) = CommandSafetyDecision(
        verdict = CommandSafetyVerdict.BLOCKED_CATASTROPHIC,
        reason = reason,
        blockedTarget = target,
    )

    private data class Token(val value: String, val raw: String)

    private sealed interface LexItem {
        data class Word(val token: Token) : LexItem
        data class Operator(val op: String) : LexItem
        data class Redirection(val spec: String) : LexItem
    }

    private data class LexResult(
        val items: List<LexItem>,
        val substitutions: List<String>,
        val clean: Boolean,
        val hasDisallowedSyntax: Boolean,
    )

    private fun splitIntoPipelines(items: List<LexItem>): List<List<List<Token>>> {
        val pipelines = mutableListOf<List<List<Token>>>()
        var currentPipeline = mutableListOf<List<Token>>()
        var currentSegment = mutableListOf<Token>()
        fun flushSegment() {
            if (currentSegment.isNotEmpty()) {
                currentPipeline.add(currentSegment)
                currentSegment = mutableListOf()
            }
        }
        fun flushPipeline() {
            flushSegment()
            if (currentPipeline.isNotEmpty()) {
                pipelines.add(currentPipeline)
                currentPipeline = mutableListOf()
            }
        }
        for (item in items) {
            when (item) {
                is LexItem.Word -> currentSegment.add(item.token)
                is LexItem.Redirection -> Unit
                is LexItem.Operator -> when (item.op) {
                    "|" -> flushSegment()
                    else -> flushPipeline()
                }
            }
        }
        flushPipeline()
        return pipelines
    }

    private fun lexShell(input: String): LexResult {
        val items = mutableListOf<LexItem>()
        val substitutions = mutableListOf<String>()
        var clean = true
        var disallowed = false
        val valueBuf = StringBuilder()
        val rawBuf = StringBuilder()
        var hasWord = false

        fun flushWord() {
            if (hasWord) {
                items.add(LexItem.Word(Token(valueBuf.toString(), rawBuf.toString())))
                valueBuf.clear()
                rawBuf.clear()
                hasWord = false
            }
        }

        var i = 0
        val n = input.length
        while (i < n) {
            val ch = input[i]
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> {
                    flushWord()
                    i++
                }
                ch == '#' && !hasWord -> {
                    while (i < n && input[i] != '\n') i++
                }
                ch == '\n' -> {
                    flushWord()
                    items.add(LexItem.Operator("\n"))
                    disallowed = true
                    i++
                }
                ch == '\'' -> {
                    hasWord = true
                    rawBuf.append('\'')
                    i++
                    var closed = false
                    while (i < n) {
                        val c = input[i]
                        rawBuf.append(c)
                        if (c == '\'') {
                            closed = true
                            i++
                            break
                        }
                        valueBuf.append(c)
                        i++
                    }
                    if (!closed) clean = false
                }
                ch == '"' -> {
                    hasWord = true
                    rawBuf.append('"')
                    i++
                    var closed = false
                    while (i < n) {
                        val c = input[i]
                        if (c == '\\' && i + 1 < n) {
                            val next = input[i + 1]
                            rawBuf.append(c).append(next)
                            if (next in charArrayOf('"', '\\', '$', '`', '\n')) {
                                if (next != '\n') valueBuf.append(next)
                            } else {
                                valueBuf.append(c).append(next)
                            }
                            i += 2
                            continue
                        }
                        if (c == '$' && i + 1 < n && input[i + 1] == '(') {
                            val end = findMatchingParen(input, i + 2)
                            if (end < 0) {
                                clean = false
                                i = n
                                break
                            }
                            val inner = input.substring(i + 2, end)
                            substitutions.add(inner)
                            val span = input.substring(i, end + 1)
                            valueBuf.append(span)
                            rawBuf.append(span)
                            i = end + 1
                            continue
                        }
                        if (c == '`') {
                            val end = findMatchingBacktick(input, i + 1)
                            if (end < 0) {
                                clean = false
                                i = n
                                break
                            }
                            val inner = input.substring(i + 1, end)
                            substitutions.add(inner)
                            val span = input.substring(i, end + 1)
                            valueBuf.append(span)
                            rawBuf.append(span)
                            i = end + 1
                            continue
                        }
                        rawBuf.append(c)
                        if (c == '"') {
                            closed = true
                            i++
                            break
                        }
                        valueBuf.append(c)
                        i++
                    }
                    if (!closed) clean = false
                }
                ch == '\\' -> {
                    hasWord = true
                    if (i + 1 < n) {
                        val next = input[i + 1]
                        rawBuf.append(ch).append(next)
                        if (next != '\n') valueBuf.append(next)
                        i += 2
                    } else {
                        clean = false
                        i++
                    }
                }
                ch == '$' && i + 1 < n && input[i + 1] == '{' -> {
                    hasWord = true
                    val close = input.indexOf('}', startIndex = i + 2)
                    if (close < 0) {
                        clean = false
                        val span = input.substring(i)
                        valueBuf.append(span)
                        rawBuf.append(span)
                        i = n
                    } else {
                        val span = input.substring(i, close + 1)
                        valueBuf.append(span)
                        rawBuf.append(span)
                        i = close + 1
                    }
                }
                ch == '$' && i + 1 < n && input[i + 1] == '(' -> {
                    hasWord = true
                    val end = findMatchingParen(input, i + 2)
                    if (end < 0) {
                        clean = false
                        break
                    }
                    val inner = input.substring(i + 2, end)
                    substitutions.add(inner)
                    val span = input.substring(i, end + 1)
                    valueBuf.append(span)
                    rawBuf.append(span)
                    i = end + 1
                }
                ch == '`' -> {
                    hasWord = true
                    val end = findMatchingBacktick(input, i + 1)
                    if (end < 0) {
                        clean = false
                        break
                    }
                    val inner = input.substring(i + 1, end)
                    substitutions.add(inner)
                    val span = input.substring(i, end + 1)
                    valueBuf.append(span)
                    rawBuf.append(span)
                    i = end + 1
                }
                !hasWord && (input.startsWith("2>&1", i) || input.startsWith("2>/dev/null", i)) -> {
                    val spec = if (input.startsWith("2>&1", i)) "2>&1" else "2>/dev/null"
                    val after = i + spec.length
                    if (after == n || input[after].isWhitespace() || input[after] in charArrayOf('|', '&', ';', ')', '\n')) {
                        items.add(LexItem.Redirection(spec))
                        i = after
                    } else {
                        hasWord = true
                        valueBuf.append(ch)
                        rawBuf.append(ch)
                        i++
                    }
                }
                ch == '|' || ch == '&' || ch == ';' -> {
                    flushWord()
                    val two = if (i + 1 < n) input.substring(i, i + 2) else ""
                    when (two) {
                        "&&", "||" -> {
                            items.add(LexItem.Operator(two))
                            if (two == "||") disallowed = true
                            i += 2
                        }
                        "&>" -> {
                            items.add(LexItem.Redirection(two))
                            disallowed = true
                            i += 2
                        }
                        else -> {
                            val op = ch.toString()
                            items.add(LexItem.Operator(op))
                            if (op != "|") disallowed = true
                            i++
                        }
                    }
                }
                ch == '(' || ch == ')' || ch == '{' || ch == '}' -> {
                    flushWord()
                    items.add(LexItem.Operator(ch.toString()))
                    disallowed = true
                    i++
                }
                ch == '>' || ch == '<' -> {
                    // 进程替换 <( / >( 或重定向
                    if (i + 1 < n && input[i + 1] == '(') {
                        val end = findMatchingParen(input, i + 2)
                        if (end >= 0) {
                            substitutions.add(input.substring(i + 2, end))
                            i = end + 1
                        } else {
                            clean = false
                            break
                        }
                        disallowed = true
                        continue
                    }
                    // 检查前面是否紧跟单数字 fd（如 1>、2>）
                    val fdPrefix = if (hasWord && valueBuf.length == 1 && valueBuf[0].isDigit()) {
                        val fd = valueBuf.toString()
                        valueBuf.clear()
                        rawBuf.clear()
                        hasWord = false
                        fd
                    } else {
                        flushWord()
                        ""
                    }
                    val opEnd = when {
                        input.startsWith("<<<", i) -> i + 3
                        input.startsWith(">>", i) || input.startsWith(">|", i) || input.startsWith("<>", i) ||
                            input.startsWith("<<", i) || input.startsWith(">&", i) -> i + 2
                        else -> i + 1
                    }
                    val op = fdPrefix + input.substring(i, opEnd)
                    i = opEnd
                    while (i < n && (input[i] == ' ' || input[i] == '\t')) i++
                    val targetStart = i
                    while (i < n && !input[i].isWhitespace() && input[i] !in charArrayOf('|', '&', ';', '(', ')', '\n', '>', '<')) i++
                    val target = input.substring(targetStart, i)
                    val spec = op + target
                    items.add(LexItem.Redirection(spec))
                    if (spec !in setOf("2>&1", "2>/dev/null")) disallowed = true
                }
                else -> {
                    hasWord = true
                    valueBuf.append(ch)
                    rawBuf.append(ch)
                    i++
                }
            }
        }
        flushWord()
        return LexResult(items, substitutions, clean, disallowed)
    }

    private fun findMatchingParen(input: String, start: Int): Int {
        var depth = 1
        var i = start
        var inSingle = false
        var inDouble = false
        while (i < input.length) {
            val c = input[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> {
                    if (c == '\\' && i + 1 < input.length) { i += 2; continue }
                    if (c == '"') inDouble = false
                }
                c == '\\' && i + 1 < input.length -> { i += 2; continue }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findMatchingBacktick(input: String, start: Int): Int {
        var i = start
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length) { i += 2; continue }
            if (input[i] == '`') return i
            i++
        }
        return -1
    }
}
