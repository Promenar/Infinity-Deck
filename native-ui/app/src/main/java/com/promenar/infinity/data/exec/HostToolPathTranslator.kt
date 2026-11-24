package com.promenar.infinity.data.exec

import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import com.promenar.infinity.data.linux.LinuxRootfsFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths

internal class HostToolPathTranslator(
    mounts: Map<String, File>,
) {
    private val mounts = mounts.map { (guest, host) ->
        requireHost(guest.startsWith('/') && guest != "/" && guest == Paths.get(guest).normalize().toString(),
            "TOOL_PATH", "宿主工具挂载点无效。")
        val root = host.canonicalFile
        requireHost(root.isDirectory, "TOOL_PATH", "宿主工具挂载目录不可用。")
        Mount(Paths.get(guest), root.toPath())
    }.sortedByDescending { it.input.nameCount }

    val roots: List<File> get() = mounts.map { it.host.toFile() }.distinct()

    private data class Mount(val input: Path, val host: Path)
    data class Command(val directory: File, val arguments: List<String>)

    fun workingDirectory(value: String): File {
        requireHost(value.startsWith('/'), "TOOL_CWD", "宿主工具的当前目录必须是绝对路径。")
        val directory = resolve(value, directory = true)
        requireHost(directory.isDirectory, "TOOL_CWD", "宿主工具的当前目录不存在。")
        return directory
    }

    internal fun path(value: String): File = resolve(value, directory = true)

    private fun resolve(value: String, directory: Boolean = false, linkDepth: Int = 0): File {
        requireHost(value.isNotEmpty() && '\u0000' !in value, "TOOL_PATH", "宿主工具路径无效。")
        if (!directory && value in devices) return File(value)
        val path = Paths.get(value)
        val mount = mounts.firstOrNull { path.startsWith(it.input) }
            ?: throw HostToolException("TOOL_PATH", "宿主工具路径超出本次任务授权的挂载。")
        var current = mount.host
        for (component in mount.input.relativize(path)) {
            current = when (component.toString()) {
                "", "." -> current
                ".." -> checkNotNull(current.parent)
                else -> {
                    val next = current.resolve(component)
                    if (Files.isSymbolicLink(next)) {
                        requireHost(linkDepth < 40, "TOOL_PATH", "宿主工具符号链接循环或过深。")
                        val target = Files.readSymbolicLink(next)
                        if (target.isAbsolute && mounts.any { target.startsWith(it.input) }) {
                            resolve(target.toString(), directory = true, linkDepth = linkDepth + 1).toPath()
                        } else {
                            try { next.toRealPath() }
                            catch (_: java.io.IOException) {
                                throw HostToolException("TOOL_PATH", "宿主工具路径含无效的符号链接。")
                            }
                        }
                    } else next
                }
            }
            requireHost(current.startsWith(mount.host), "TOOL_PATH", "宿主工具路径或符号链接越过授权挂载根。")
        }
        // 若相对路径穿过另一个 guest 挂载点，按最终最深挂载点解析。
        val normalized = path.normalize()
        val nested = mounts.firstOrNull { normalized.startsWith(it.input) }
        return if (nested != null && nested != mount) resolve(normalized.toString(), directory) else current.toFile()
    }

    fun translate(tool: String, cwd: String, argv: List<String>, threads: Int): Command {
        val workingDirectory = workingDirectory(cwd)
        requireHost(argv.none { '\u0000' in it }, "TOOL_ARGUMENT", "宿主工具参数含空字符。")
        val translate: (String) -> String = { resolveArgument(cwd, it) }
        val arguments = when (tool) {
            "git" -> git(cwd, argv)
            "rg" -> parse(argv, rg, translate, defaultArguments = listOf("-j", threads.toString()), maxThreads = threads)
            "fd" -> fd(cwd, argv, threads)
            "jq" -> listOf("-L", "/dev/null") + parse(argv, jq, translate)
            "sqlite3" -> listOf("--safe") + parse(argv, sqlite, translate)
            "zstd" -> parse(argv, zstd, translate, defaultArguments = listOf("-T$threads"), maxThreads = threads)
            "bsdtar" -> tar(cwd, argv, translate)
            "rsync" -> parse(argv, rsync, translate = {
                requireHost(':' !in it, "TOOL_OPTION_BLOCKED", "宿主 rsync 只允许本地路径。")
                translate(it)
            })
            "ffmpeg", "ffprobe" -> media(tool, argv, translate, threads)
            else -> throw HostToolException("TOOL_UNAVAILABLE", "宿主工具不在白名单内。")
        }
        return Command(workingDirectory, arguments)
    }

    private fun resolveArgument(cwd: String, value: String): String {
        if (value == "-") return value
        val absolute = if (value.startsWith('/')) value else "$cwd/$value"
        val file = resolve(absolute)
        return file.path + if (value.endsWith('/') && file.path != "/") "/" else ""
    }

    private fun git(cwd: String, argv: List<String>): List<String> {
        GitHostConfig.validateArguments(argv)
        var directory = cwd
        var global = true
        var pathspec = false
        var configValue = false
        var pendingPath: String? = null
        var command = ""
        var position = 0
        var gitOptionValue = false
        fun path(value: String, move: Boolean): String {
            if (move && value.isEmpty()) return value
            val absolute = if (value.startsWith('/')) value else "$directory/$value"
            val file = resolve(absolute, directory = true)
            if (move) {
                requireHost(file.isDirectory, "TOOL_CWD", "Git -C 指定的目录不存在。")
                directory = absolute
            }
            return file.path
        }
        return argv.map { argument ->
            val pending = pendingPath
            when {
                pending != null -> {
                    pendingPath = null
                    path(argument, pending == "-C")
                }
                configValue -> argument.also { configValue = false }
                global && argument in setOf("-C", "--git-dir", "--work-tree") -> argument.also { pendingPath = it }
                global && argument.startsWith("-C") -> "-C" + path(argument.drop(2), true)
                global && argument == "-c" -> argument.also { configValue = true }
                global && (argument.startsWith("--git-dir=") || argument.startsWith("--work-tree=")) ->
                    argument.substringBefore('=') + "=" + path(argument.substringAfter('='), false)
                global && argument.startsWith('-') -> argument
                global -> argument.also { command = it; global = false }
                command == "config" -> argument
                gitOptionValue -> argument.also { gitOptionValue = false }
                argument == "--" -> argument.also { pathspec = true }
                !pathspec && command == "blame" && argument == "-S" -> argument.also { pendingPath = it }
                !pathspec && command == "blame" && argument.startsWith("-S") -> "-S" + path(argument.drop(2), false)
                !pathspec && argument.startsWith('-') -> argument.also { gitOptionValue = GitHostConfig.optionConsumesValue(command, argument) }
                else -> {
                    position++
                    if (!pathspec && argument.startsWith("file:")) {
                        requireHost(command in gitRemoteCommands, "GIT_ARGUMENT", "Git 文件 URL 仅用于本地远端。")
                        val uri = runCatching { java.net.URI(argument) }.getOrNull()
                        requireHost(uri?.scheme == "file" && uri.authority in setOf(null, "", "localhost") && uri.path?.startsWith('/') == true,
                            "TOOL_PATH", "Git 文件 URL 无效。")
                        return@map resolveArgument(directory, checkNotNull(uri?.path))
                    }
                    val remotePath = position == 1 && command in setOf("fetch", "pull", "push", "ls-remote") &&
                        ('/' in argument || Files.exists(workingDirectory(directory).toPath().resolve(argument), NOFOLLOW_LINKS))
                    val local = !isNetworkGitUrl(argument) && (remotePath || argument.startsWith('/') || argument.startsWith("./") ||
                        argument.startsWith("../") || argument == ".." || pathspec ||
                        command in setOf("init", "apply", "clone") ||
                        command in setOf("add", "check-ignore", "rm", "mv", "clean"))
                    if (!local) argument else {
                        val translated = resolveArgument(directory, argument)
                        // Git 的 pathspec 和相对修订语义依赖 cwd；校验后保留相对拼写。
                        val relativeHost = workingDirectory(directory).toPath().resolve(argument).normalize()
                        if (argument.startsWith('/') || remotePath || command in setOf("clone", "init", "apply") ||
                            Paths.get(translated).normalize() != relativeHost) translated else argument
                    }
                }
            }
        }
    }

    private fun fd(cwd: String, argv: List<String>, threads: Int): List<String> {
        var base = cwd
        var listsDetails = false
        var stripsCwdPrefix = false
        val translate: (String) -> String = { resolveArgument(base, it) }
        val translated = parse(argv, fd, translate, defaultArguments = listOf("-j", threads.toString()), typedPath = { option, value ->
            if (option == "--base-directory") {
                base = if (value.startsWith('/')) value else "$cwd/$value"
                workingDirectory(base).path
            } else translate(value)
        }, deferredPathOptions = setOf("--search-path", "--ignore-file"), onOption = { option ->
            if (option == "-l" || option == "--list-details") listsDetails = true
            if (option == "--strip-cwd-prefix") stripsCwdPrefix = true
        }, maxThreads = threads)
        // 详细列表调用固定的系统 ls，保留路径前缀以免文件名被识别为选项。
        requireHost(!listsDetails || !stripsCwdPrefix, "TOOL_OPTION_BLOCKED", "fd 详细列表需保留路径前缀。")
        return translated
    }

    /** 每个工具显式声明选项的值与位置参数的语义。未知选项按失败处理。 */
    private fun parse(
        argv: List<String>,
        grammar: Grammar,
        translate: (String) -> String,
        defaultArguments: List<String> = emptyList(),
        typedPath: (String, String) -> String = { _, value -> translate(value) },
        deferredPathOptions: Set<String> = emptySet(),
        onOption: (String) -> Unit = {},
        maxThreads: Int = 1,
    ): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        var position = 0
        val patternPositions = mutableListOf<Int>()
        val deferredPaths = mutableListOf<Pair<Int, String>>()
        var explicitPattern = false
        var explicitThreads = false
        var positional = false
        fun convert(option: String, value: String, type: Value): String = type.convert(value, maxThreads) { path ->
            if (option in deferredPathOptions) {
                deferredPaths += result.size to option
                path
            } else typedPath(option, path)
        }
        while (index < argv.size) {
            val arg = argv[index++]
            if (!positional && arg == "--") {
                result += arg
                positional = true
            } else if (!positional && arg.startsWith('-') && arg != "-") {
                val singleLong = grammar.singleDashLong && arg.length > 2 && !arg.startsWith("--")
                val grouped = arg.startsWith('-') && !arg.startsWith("--") && arg.length > 2 && !singleLong
                val key = arg.substringBefore('=')
                if (grouped) {
                    var offset = 1
                    while (offset < arg.length) {
                        val short = "-" + arg[offset++]
                        val spec = grammar.options[short] ?: blockedOption()
                        onOption(short)
                        result += short
                        explicitPattern = explicitPattern || spec.suppliesPattern
                        explicitThreads = explicitThreads || spec.threads
                        if (spec.values.isNotEmpty()) {
                            requireHost(spec.values.size == 1, "TOOL_ARGUMENT", "宿主工具选项不能合并书写。")
                            val value = arg.substring(offset).takeIf(String::isNotEmpty)
                                ?: argv.getOrNull(index++) ?: missingValue()
                            val converted = convert(short, value, spec.values.single())
                            if (short == "-T" && grammar == zstd) result[result.lastIndex] = "$short$converted"
                            else result += converted
                            break
                        }
                    }
                } else {
                    val spec = grammar.options[key] ?: blockedOption()
                    onOption(key)
                    explicitPattern = explicitPattern || spec.suppliesPattern
                    explicitThreads = explicitThreads || spec.threads
                    result += key
                    val inline = arg.substringAfter('=', "").takeIf { '=' in arg }
                    requireHost(inline == null || spec.values.size == 1, "TOOL_ARGUMENT", "宿主工具选项的值不合法。")
                    spec.values.forEachIndexed { offset, type ->
                        val value = (if (offset == 0) inline else null) ?: argv.getOrNull(index++) ?: missingValue()
                        val converted = convert(key, value, type)
                        if (key == "-T" && grammar == zstd) result[result.lastIndex] = "$key$converted"
                        else result += converted
                    }
                }
            } else {
                result += when (grammar.position) {
                    Position.PATHS -> translate(arg)
                    Position.FIRST_PATTERN -> arg.also { patternPositions += result.size }
                    Position.SQLITE_DATABASE -> if (position == 0 && arg != ":memory:") translate(arg) else arg
                }
                position++
            }
        }
        for ((resultIndex, option) in deferredPaths) result[resultIndex] = typedPath(option, result[resultIndex])
        for ((positionIndex, resultIndex) in patternPositions.withIndex()) {
            val value = result[resultIndex]
            if (explicitPattern || positionIndex != 0) result[resultIndex] = translate(value)
            else if (grammar == jq) requireJqProgram(value)
        }
        return (if (explicitThreads) emptyList() else defaultArguments) + result
    }

    private fun media(tool: String, argv: List<String>, translate: (String) -> String, threads: Int): List<String> {
        val paths: (String) -> String = {
            requireHost(':' !in it || it in setOf("pipe:0", "pipe:1", "pipe:2"), "TOOL_OPTION_BLOCKED", "媒体工具只允许本地文件或标准流。")
            if (it.startsWith("pipe:")) it else translate(it)
        }
        val args = parse(argv, media, paths, listOf("-threads", threads.toString()), maxThreads = threads)
        val output = mutableListOf<String>()
        if (tool == "ffprobe") output += mediaWhitelist
        var index = 0
        while (index < args.size) {
            val arg = args[index++]
            if (arg == "-i" && tool == "ffmpeg") output += mediaWhitelist
            output += arg
            val option = media.options[arg]
            if (option != null) repeat(option.values.size) {
                val value = args[index++]
                if (arg == "-f") requireHost(value in mediaFormats, "TOOL_OPTION_BLOCKED", "媒体格式可能读取额外文件或打开协议。")
                output += value
            }
        }
        return output
    }

    private fun tar(cwd: String, argv: List<String>, initialPath: (String) -> String): List<String> {
        val expanded = expandTar(argv)
        var selecting = false
        var directory = cwd
        var autoCompress = false
        var archive: String? = null
        fun entry(value: String): String {
            requireHost(!value.startsWith('@'), "TOOL_OPTION_BLOCKED", "宿主 bsdtar 不从其他归档添加成员。")
            if (selecting) {
                requireHost(!value.startsWith('/') && value.split('/').none { it == ".." },
                    "TOOL_PATH", "归档成员选择器必须位于归档根内。")
                return value
            }
            val translated = resolveArgument(directory, value)
            val ordinary = workingDirectory(directory).toPath().resolve(value).normalize()
            return if (value.startsWith('/') || Paths.get(translated).normalize() != ordinary) translated else value
        }
        val translated = parse(expanded, tar, ::entry, typedPath = { option, value ->
            if (option in setOf("-C", "--directory")) {
                directory = if (value.startsWith('/')) value else "$directory/$value"
                workingDirectory(directory).path
            } else initialPath(value).also { archive = it }
        }, onOption = { option ->
            if (option in setOf("-t", "-x", "--list", "--extract")) selecting = true
            if (option == "-a" || option == "--auto-compress") autoCompress = true
        })
        val archivePath = archive
        requireHost(!autoCompress || archivePath == null || archivePath == "-" || autoArchiveSuffixes.any(archivePath::endsWith),
            "TOOL_OPTION_BLOCKED", "bsdtar 自动压缩仅支持宿主已打包的格式。")
        return translated
    }

    private fun expandTar(argv: List<String>): List<String> {
        val flags = argv.firstOrNull() ?: return argv
        return if (flags.matches(Regex("[actxrvzjJfC]+"))) listOf("-$flags") + argv.drop(1) else argv
    }

    private enum class Position { PATHS, FIRST_PATTERN, SQLITE_DATABASE }
    private enum class Value {
        TEXT, PATH, NUMBER, FILTER, JQ_FILE;
        fun convert(value: String, maxThreads: Int, translate: (String) -> String): String = when (this) {
            TEXT -> value
            PATH -> translate(value)
            JQ_FILE -> translate(value).also { path ->
                val file = File(path)
                requireHost(file.isFile && file.length() <= 512 * 1024, "TOOL_PATH", "jq 程序文件必须是授权目录中的普通文件。")
                val bytes = file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 512 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                requireHost(bytes.size <= 512 * 1024, "TOOL_PATH", "jq 程序文件过大。")
                requireJqProgram(bytes.toString(Charsets.UTF_8))
            }
            NUMBER -> {
                val requested = value.toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw HostToolException("TOOL_ARGUMENT", "宿主工具的线程数无效。")
                (if (requested == 0) maxThreads else minOf(requested, maxThreads)).toString()
            }
            FILTER -> value.also {
                requireHost(it.matches(Regex("(scale|crop|fps|format|transpose|setsar|setdar)=[a-zA-Z0-9_:./+*()=-]+")),
                    "TOOL_OPTION_BLOCKED", "媒体滤镜可能读取额外文件或打开协议。")
            }
        }
    }
    private data class Option(val values: List<Value>, val suppliesPattern: Boolean = false, val threads: Boolean = false)
    private class Grammar(position: Position = Position.PATHS, val singleDashLong: Boolean = false, block: MutableMap<String, Option>.() -> Unit) {
        val position = position
        val options = buildMap(block)
    }

    companion object {
        private val gitRemoteCommands = setOf("clone", "fetch", "pull", "push", "ls-remote", "remote")
        private val devices = setOf("/dev/null", "/dev/stdin", "/dev/stdout")
        private val remote = Regex("(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9._-]+:.+")
        internal fun isNetworkGitUrl(value: String): Boolean =
            value.startsWith("https://") || value.startsWith("http://") || value.startsWith("ssh://") ||
                remote.matches(value) && "::" !in value && "://" !in value
        private fun blockedOption(): Nothing = throw HostToolException("TOOL_OPTION_BLOCKED", "宿主工具选项不在安全白名单内。")
        private val jqModule = Regex("""\b(?:import|include|modulemeta)\b""")
        private fun requireJqProgram(program: String) {
            requireHost(!jqModule.containsMatchIn(program), "TOOL_OPTION_BLOCKED", "宿主 jq 程序不能加载模块。")
        }
        private fun missingValue(): Nothing = throw HostToolException("TOOL_ARGUMENT", "宿主工具选项缺少参数。")
        private fun MutableMap<String, Option>.options(names: String, vararg types: Value, pattern: Boolean = false, threads: Boolean = false) {
            names.split(' ').forEach { put(it, Option(types.toList(), pattern, threads)) }
        }

        private val rg = Grammar(Position.FIRST_PATTERN) {
            options("-i -s -S -v -w -x -F -n -N -l -c -H -h -o -q -a -U -u --hidden --no-ignore --no-ignore-vcs --files --files-with-matches --files-without-match --json --version --help --line-number --no-heading --heading --no-config --fixed-strings --ignore-case --smart-case --invert-match --count --no-filename --with-filename --only-matching --null")
            options("--column --vimgrep --stats --multiline --multiline-dotall --type-list --trim --passthru --no-line-number --no-messages --quiet --text --case-sensitive --word-regexp --line-regexp --no-ignore-dot --no-ignore-global --no-ignore-parent")
            options("-A -B -C -m -M -g -t -T --glob --iglob --color --max-depth --max-count --type --type-not --encoding --context --sort --sortr", Value.TEXT)
            options("--after-context --before-context --max-filesize --context-separator --field-context-separator --field-match-separator", Value.TEXT)
            options("-e --regexp", Value.TEXT, pattern = true)
            options("-f --file", Value.PATH, pattern = true)
            options("--ignore-file", Value.PATH)
            options("-j --threads", Value.NUMBER, threads = true)
            put("--files", Option(emptyList(), suppliesPattern = true))
        }
        private val fd = Grammar(Position.FIRST_PATTERN) {
            options("-H -I -u -s -i -g -F -a -p -0 -h -V --hidden --no-ignore --no-ignore-vcs --glob --regex --fixed-strings --absolute-path --full-path --print0 --help --version --strip-cwd-prefix")
            options("-l --list-details --case-sensitive --ignore-case --unrestricted --prune --show-errors --one-file-system")
            options("-d -t -e -E -S --max-depth --min-depth --exact-depth --type --extension --exclude --size --color --max-results", Value.TEXT)
            options("--changed-within --changed-before --and", Value.TEXT)
            options("--ignore-file --base-directory --search-path", Value.PATH)
            options("-j --threads", Value.NUMBER, threads = true)
        }
        private val jq = Grammar(Position.FIRST_PATTERN) {
            options("-n -r -R -s -c -C -M -e -S -a -j -h -V --null-input --raw-output --raw-input --slurp --compact-output --color-output --monochrome-output --exit-status --sort-keys --ascii-output --stream --stream-errors --seq --unbuffered --tab --version --help")
            options("--indent", Value.TEXT)
            options("-f --from-file", Value.JQ_FILE, pattern = true)
            options("--arg --argjson", Value.TEXT, Value.TEXT)
            options("--slurpfile --rawfile --argfile", Value.TEXT, Value.PATH)
        }
        private val sqlite = Grammar(Position.SQLITE_DATABASE, singleDashLong = true) {
            options("-batch --batch -bail --bail -noheader -header -csv -column -json -line -list -readonly -safe --safe -version --version -help --help")
            options("-separator -nullvalue -newline -cmd", Value.TEXT)
            options("-init", Value.PATH)
        }
        private val zstd = Grammar {
            options("-d -c -f -k -q -v -t -l -z -h -V --decompress --stdout --force --keep --quiet --verbose --test --list --compress --help --version --long --no-progress --single-thread -1 -2 -3 -4 -5 -6 -7 -8 -9")
            options("-o --output -D", Value.PATH)
            options("-T --threads", Value.NUMBER, threads = true)
            put("--single-thread", Option(emptyList(), threads = true))
        }
        private val tar = Grammar {
            options("-c -t -x -r -u -v -z -j -J -k -O -a --create --list --extract --append --verbose --gzip --bzip2 --xz --zstd --auto-compress --keep-old-files --to-stdout --no-same-owner --no-same-permissions --numeric-owner --version --help")
            options("-f --file -C --directory", Value.PATH)
            options("--exclude --strip-components --format", Value.TEXT)
        }
        private val autoArchiveSuffixes = setOf(".tar", ".tgz", ".tar.gz", ".tbz", ".tbz2", ".tar.bz2", ".txz", ".tar.xz",
            ".tzst", ".tar.zst", ".zip", ".7z")
        private val rsync = Grammar {
            options("-a -r -v -q -h -n -c -t -p -o -g -l -z -u -i -P -W --archive --recursive --verbose --quiet --dry-run --checksum --times --perms --owner --group --links --compress --update --itemize-changes --progress --partial --whole-file --delete --delete-excluded --ignore-existing --existing --safe-links --stats --version --help")
            options("--exclude --include --out-format --max-size --min-size --bwlimit", Value.TEXT)
            options("--exclude-from --include-from", Value.PATH)
        }
        private val media = Grammar(singleDashLong = true) {
            options("-y -n -hide_banner -nostdin -stats -nostats -vn -an -sn -dn -version --version -h -help -show_format -show_streams -show_packets -show_frames -show_chapters -show_programs -count_frames -count_packets")
            options("-i", Value.PATH)
            options("-f -v -loglevel -c -c:v -c:a -codec -codec:v -codec:a -vcodec -acodec -preset -crf -b:v -b:a -pix_fmt -r -s -ss -t -to -ar -ac -map -map_metadata -frames:v -frames:a -movflags -show_entries -select_streams -of -print_format -read_intervals", Value.TEXT)
            options("-threads -threads:v -threads:a", Value.NUMBER, threads = true)
            options("-vf -filter:v", Value.FILTER)
        }
        private const val mediaInputFormats = "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,mp3,wav,flac,aac,ogg,opus,image2,png_pipe,jpeg_pipe,webp_pipe,gif,mpegts"
        private val mediaFormats = mediaInputFormats.split(',').toSet() + setOf("null", "rawvideo", "s16le", "f32le")
        private val mediaWhitelist = listOf("-protocol_whitelist", "file,pipe", "-format_whitelist", mediaInputFormats + ",rawvideo,s16le,f32le")

        fun forLinux(spec: LinuxLaunchSpec, rootfs: File) = HostToolPathTranslator(buildMap {
            spec.workspace?.let { put("/workspace", it) }
            spec.sessionHome?.let { put("/home/session", it) }
            for (mount in spec.mounts) put(mount.guestPath, mount.host)
            for (name in listOf("root", "tmp")) put("/$name", LinuxRootfsFiles.safePath(rootfs, name))
        })

        fun forHost(roots: Collection<File>) = HostToolPathTranslator(roots.associateBy { it.canonicalPath })
    }
}
