package com.promenar.infinity.data.termux

import java.io.File
import java.util.Base64

data class TermuxJobHandle(val directory: String, val pid: Int)

data class TermuxJobSnapshot(
    val exitCode: Int?,
    val alive: Boolean,
    val logTail: String,
    val truncated: Boolean,
    val timedOut: Boolean = false,
    val stopped: Boolean = false,
    /** 后端已提供的日志字节位置，用于识别内容相同的后续输出。 */
    val logBytes: Long? = null,
)

/** 每次调用只等待短 RUN_COMMAND；用户进程及超时看门狗均由 Termux 独立托管。 */
class TermuxBackgroundJobs(private val execute: suspend (TermuxCommandRequest) -> TermuxCommandResult) {
    suspend fun start(
        id: String,
        command: String,
        workdir: String?,
        stdin: String?,
        maxRuntimeMinutes: Int,
        sharedWorkspace: File?,
    ): TermuxJobHandle {
        require(maxRuntimeMinutes in 1..1440) { "最长运行时间必须为 1–1440 分钟" }
        // 复用前台执行的输入边界，生成后的脚本还会再次经过单参数大小检查。
        TermuxCommandRequest(command, workdir, stdin = stdin)
        val script = TermuxBackgroundJobScripts.start(
            id, command, workdir, stdin, maxRuntimeMinutes * 60, sharedWorkspace,
        )
        return TermuxBackgroundJobScripts.parseStarted(call(script, "启动").stdout)
    }

    suspend fun inspect(handle: TermuxJobHandle): TermuxJobSnapshot =
        TermuxBackgroundJobScripts.parseSnapshot(call(TermuxBackgroundJobScripts.inspect(handle), "查询").stdout)

    suspend fun recover(id: String, sharedWorkspace: File?): TermuxJobHandle? =
        TermuxBackgroundJobScripts.parseRecovered(call(TermuxBackgroundJobScripts.recover(id, sharedWorkspace), "恢复").stdout)

    suspend fun stopUnconfirmed(id: String, sharedWorkspace: File?): TermuxJobSnapshot? {
        val handle = TermuxBackgroundJobScripts.parseRecovered(
            call(TermuxBackgroundJobScripts.cancelAndLocate(id, sharedWorkspace), "停止").stdout,
        ) ?: return null
        return stop(handle)
    }

    suspend fun stop(handle: TermuxJobHandle): TermuxJobSnapshot =
        TermuxBackgroundJobScripts.parseSnapshot(call(TermuxBackgroundJobScripts.stop(handle), "停止").stdout)

    fun logPath(handle: TermuxJobHandle): String = TermuxBackgroundJobScripts.logPath(handle)

    /** 运行中的旧任务保留原位置；退出后才迁移，避免破坏看门狗持有的路径。 */
    suspend fun migrateCompleted(id: String, sharedWorkspace: File?, handle: TermuxJobHandle): TermuxJobHandle {
        if (sharedWorkspace == null) return handle
        return TermuxBackgroundJobScripts.parseRecovered(
            call(TermuxBackgroundJobScripts.migrateCompleted(id, sharedWorkspace), "日志迁移").stdout,
        ) ?: handle
    }

    private suspend fun call(script: String, action: String): TermuxCommandResult {
        val result = execute(TermuxCommandRequest(script, timeoutSeconds = 15))
        check(result.exitCode == 0 && result.errorCode == null && !result.timedOut && !result.stdoutTruncated) {
            val reason = result.errorCode ?: if (result.timedOut) "TERMUX_TIMEOUT" else "TERMUX_JOB_PROTOCOL_FAILED"
            val guidance = when (result.exitCode) {
                71 -> "请检查 Termux 对共享目录的读写权限，或使用 Termux 私有日志目录。"
                72 -> "任务号或日志已存在；请使用新的任务号，避免覆盖已有日志。"
                73 -> "启动回执不完整，远端任务可能已启动；请使用原任务号恢复查询或停止，勿重复提交命令。"
                74, 75 -> "任务文件缺失或 PID 身份不匹配；请刷新任务记录并检查 Termux 中的原任务，勿复用其它进程的 PID。"
                78 -> "任务号已有停止标记；如需运行命令，请创建新的任务号。"
                else -> "请检查 Termux 权限、共享目录读写权限，以及 bash、setsid、nohup 等工具是否可用。"
            }
            "Termux 后台任务${action}失败（$reason）；$guidance"
        }
        return result
    }
}

internal object TermuxBackgroundJobScripts {
    private const val PROTOCOL = "INFINITY_JOB_V1"
    private const val TAIL_BYTES = 32_000
    private const val TAIL_CHARACTERS = 8_000
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

    fun start(
        id: String,
        command: String,
        workdir: String?,
        stdin: String?,
        maxRuntimeSeconds: Int,
        sharedWorkspace: File?,
    ): String {
        validateId(id)
        require(maxRuntimeSeconds in 1..86_400) { "最长运行时间必须为 1–86400 秒" }
        return """
            set -u
            umask 077
            for tool in bash setsid nohup base64 tr tail wc date sleep; do
                command -v "§tool" >/dev/null 2>&1 || exit 70
            done
            [ ! -L "§HOME/.infinity" ] && [ ! -L "§HOME/.infinity/jobs" ] && [ ! -L "§HOME/.nexara" ] && [ ! -L "§HOME/.nexara/jobs" ] || exit 74
            root=${root(null)}
            mkdir -p -- "§root" || exit 71
            root=§(cd -- "§root" && pwd -P) || exit 71
            directory="§root/$id"
            [ ! -e "§HOME/.nexara/jobs/$id" ] && [ ! -e "§HOME/.nexara/jobs/$id.cancelled" ] || exit 72
            [ ! -e "§directory.cancelled" ] || exit 78
            mkdir -- "§directory" || exit 72
            (set -C; : > "§directory.log") || { rmdir -- "§directory"; exit 72; }
            $identity
            abort_start() {
                if [ -r "§directory/pid" ] && [ -r "§directory/identity" ]; then
                    pid=§(cat "§directory/pid")
                    expected=§(cat "§directory/identity")
                    if [[ "§pid" =~ ^[0-9]+§ ]] && [ "§pid" -gt 1 ] && [ -n "§expected" ] && matches; then
                        kill -TERM -- "-§pid" 2>/dev/null || :
                        sleep 2
                        if matches; then kill -KILL -- "-§pid" 2>/dev/null || :; fi
                    fi
                fi
                exit 73
            }
            setsid nohup bash -c ${quote(worker)} infinity-job "§directory" ${quote(command)} ${quote(workdir.orEmpty())} ${quote(stdin.orEmpty())} ${if (stdin == null) "0" else "1"} $maxRuntimeSeconds </dev/null >>"§directory.log" 2>&1 &
            launch=§!
            for ((attempt=0; attempt<100; attempt++)); do
                [ -f "§directory/ready" ] && break
                kill -0 "§launch" 2>/dev/null || abort_start
                sleep 0.02
            done
            [ -f "§directory/ready" ] || abort_start
            pid=§(cat "§directory/pid") || abort_start
            [[ "§pid" =~ ^[0-9]+§ ]] && [ "§pid" -gt 1 ] || abort_start
            printf '$PROTOCOL\n'
            printf '%s' "§directory" | base64 | tr -d '\r\n'
            printf '\n%s\n' "§pid"
        """.trimIndent().shell()
    }

    fun migrateCompleted(id: String, sharedWorkspace: File): String {
        validateId(id)
        return """
            set -eu
            umask 077
            [ ! -L "§HOME/.infinity" ] && [ ! -L "§HOME/.infinity/jobs" ] && [ ! -L "§HOME/.nexara" ] && [ ! -L "§HOME/.nexara/jobs" ] || exit 74
            root=${root(null)}
            legacy=${quote(File(sharedWorkspace, "jobs/$id").path)}
            directory="§root/$id"
            [ ! -L ${quote(File(sharedWorkspace, "jobs").path)} ] && [ ! -L "§legacy" ] || exit 74
            # 没有退出文件的异常任务也按持久PID身份核验，活进程保持原路径。
            if [ -d "§legacy" ] && [ ! -f "§legacy.exit" ]; then
                pid=§(cat "§legacy/pid") || exit 74
                expected=§(cat "§legacy/identity") || exit 74
                [[ "§pid" =~ ^[0-9]+§ ]] && [ "§pid" -gt 1 ] && [ -n "§expected" ] || exit 75
                $identity
                if matches; then printf '$PROTOCOL\nNONE\n'; exit 0; fi
            fi
            if [ ! -d "§legacy" ] && [ ! -d "§directory" ]; then printf '$PROTOCOL\nNONE\n'; exit 0; fi
            mkdir -p -- "§root"
            root=§(cd -- "§root" && pwd -P) || exit 71
            directory="§root/$id"
            for suffix in .log .exit .cancelled; do
                source="§legacy§suffix"
                target="§directory§suffix"
                [ ! -L "§source" ] && [ ! -L "§target" ] || exit 74
                if [ -e "§source" ]; then
                    [ -f "§source" ] || exit 74
                    if [ -e "§target" ]; then cmp -s -- "§source" "§target" || exit 72
                    else
                        temporary=§(mktemp "§root/.migration-$id.XXXXXX") || exit 71
                        trap 'rm -f -- "§temporary"' EXIT
                        cat -- "§source" > "§temporary" || exit 71
                        cmp -s -- "§source" "§temporary" || exit 72
                        ln -- "§temporary" "§target" || { cmp -s -- "§source" "§target" || exit 72; }
                        rm -- "§temporary"
                        trap - EXIT
                    fi
                fi
            done
            if [ -d "§legacy" ]; then
                [ ! -L "§legacy" ] && [ ! -e "§directory" ] && [ ! -L "§directory" ] || exit 72
                mv -- "§legacy" "§directory" || exit 71
            fi
            for suffix in .log .exit .cancelled; do
                source="§legacy§suffix"
                target="§directory§suffix"
                if [ -f "§source" ]; then cmp -s -- "§source" "§target" && rm -- "§source" || exit 72; fi
            done
            pid=§(cat "§directory/pid")
            printf '$PROTOCOL\n'
            printf '%s' "§directory" | base64 | tr -d '\r\n'
            printf '\n%s\n' "§pid"
        """.trimIndent().shell()
    }

    fun inspect(handle: TermuxJobHandle): String = prepare(handle) + "\n" + snapshot

    fun recover(id: String, sharedWorkspace: File?): String = locate(id, sharedWorkspace, cancel = false)

    fun cancelAndLocate(id: String, sharedWorkspace: File?): String = locate(id, sharedWorkspace, cancel = true)

    fun stop(handle: TermuxJobHandle): String = prepare(handle) + "\n" + """
        if [ ! -f "§directory.exit" ] && matches; then
            if mkdir -- "§directory/finishing" 2>/dev/null; then
                : > "§directory/stopped"
                kill -TERM -- "-§pid" 2>/dev/null || :
                sleep 2
                if matches; then kill -KILL -- "-§pid" 2>/dev/null || :; fi
                printf '143\n' > "§directory.exit.tmp" && mv -- "§directory.exit.tmp" "§directory.exit"
            else
                for ((attempt=0; attempt<80; attempt++)); do
                    [ -f "§directory.exit" ] && break
                    sleep 0.05
                done
            fi
        fi
    """.trimIndent().shell() + "\n" + snapshot

    fun logPath(handle: TermuxJobHandle): String {
        validate(handle)
        return "${handle.directory}.log"
    }

    fun parseStarted(stdout: String): TermuxJobHandle {
        val fields = stdout.trimEnd('\n').split('\n')
        check(fields.size == 3 && fields[0] in setOf(PROTOCOL, "NEXARA_JOB_V1")) { malformed() }
        val directory = decode(fields[1])
        val pid = fields[2].toIntOrNull()
        check(directory.startsWith('/') && '\u0000' !in directory && pid != null && pid > 1) { malformed() }
        return TermuxJobHandle(directory, pid)
    }

    fun parseRecovered(stdout: String): TermuxJobHandle? =
        if (stdout in setOf("$PROTOCOL\nNONE\n", "NEXARA_JOB_V1\nNONE\n")) null else parseStarted(stdout)

    fun parseSnapshot(stdout: String): TermuxJobSnapshot {
        val fields = stdout.removeSuffix("\n").split('\n')
        check(fields.size == 7 && fields[0] in setOf(PROTOCOL, "NEXARA_JOB_V1")) { malformed() }
        val exit = if (fields[1] == "-") null else fields[1].toIntOrNull().also { check(it != null) { malformed() } }
        val alive = bit(fields[2])
        val timedOut = bit(fields[3])
        val stopped = bit(fields[4])
        val byteCount = fields[5].trim().toLongOrNull()
        check(byteCount != null && byteCount >= 0) { malformed() }
        val tail = decode(fields[6])
        return TermuxJobSnapshot(
            exit, alive, tail.takeLast(TAIL_CHARACTERS), byteCount > TAIL_BYTES || tail.length > TAIL_CHARACTERS,
            timedOut, stopped, logBytes = byteCount,
        )
    }

    private fun prepare(handle: TermuxJobHandle): String {
        validate(handle)
        return """
            set -u
            directory=${quote(handle.directory)}
            pid=${handle.pid}
            [ -d "§directory" ] && [ -r "§directory/pid" ] && [ -r "§directory/identity" ] || exit 74
            [ "§(cat "§directory/pid")" = "§pid" ] || exit 75
            expected=§(cat "§directory/identity")
            [ -n "§expected" ] || exit 75
            $identity
        """.trimIndent().shell()
    }

    private fun validate(handle: TermuxJobHandle) {
        require(handle.directory.startsWith('/') && handle.directory.length <= 4096 && '\u0000' !in handle.directory) {
            "Termux 任务目录无效，请检查持久化记录"
        }
        require(handle.pid > 1) { "Termux 任务 PID 无效，请检查持久化记录" }
    }

    private fun validateId(id: String) {
        require(ID.matches(id)) { "任务号只能包含字母、数字、下划线和连字符" }
    }

    private fun root(sharedWorkspace: File?): String {
        require(sharedWorkspace == null || sharedWorkspace.isAbsolute) { "共享工作区必须是绝对路径" }
        return sharedWorkspace?.let { quote(File(it, "jobs").path) } ?: "\"\${HOME:?}/.infinity/jobs\""
    }

    private fun locate(id: String, sharedWorkspace: File?, cancel: Boolean): String {
        validateId(id)
        val noJob = "printf '$PROTOCOL\\nNONE\\n'; exit 0"
        val initialize = if (cancel) "mkdir -p -- \"\$root\" || exit 71" else
            "if [ ! -e \"\$root\" ] && [ ! -L \"\$root\" ]; then $noJob; fi"
        val tombstone = if (cancel) ": > \"\$directory.cancelled\" || exit 71" else ""
        val incomplete = if (cancel) "if [ ! -e \"\$directory/pid\" ]; then $noJob; fi" else
            "[ -f \"\$directory/ready\" ] || exit 74"
        val cancellation = if (cancel) """mkdir -p -- "§root" || exit 71; : > "§root/$id.cancelled" || exit 71""".shell() else ""
        val legacy = sharedWorkspace?.let {
            """if [ ! -e "§root/$id" ] && [ -d ${quote(File(it, "jobs/$id").path)} ]; then root=${root(it)}; fi""".shell()
        }.orEmpty()
        return """
            set -u
            umask 077
            [ ! -L "§HOME/.infinity" ] && [ ! -L "§HOME/.infinity/jobs" ] && [ ! -L "§HOME/.nexara" ] && [ ! -L "§HOME/.nexara/jobs" ] || exit 74
            root=${root(null)}
            $cancellation
            if [ -d "§HOME/.nexara/jobs/$id" ] || [ -e "§HOME/.nexara/jobs/$id.cancelled" ]; then
                [ ! -e "§root/$id" ] || exit 72
                root="§HOME/.nexara/jobs"
            fi
            $legacy
            $initialize
            root=§(cd -- "§root" && pwd -P) || exit 71
            directory="§root/$id"
            $tombstone
            if [ ! -e "§directory" ] && [ ! -L "§directory" ]; then $noJob; fi
            [ -d "§directory" ] || exit 74
            $incomplete
            [ -r "§directory/pid" ] && [ -r "§directory/identity" ] || exit 74
            pid=§(cat "§directory/pid") || exit 74
            expected=§(cat "§directory/identity") || exit 74
            [[ "§pid" =~ ^[0-9]+§ ]] && [ "§pid" -gt 1 ] && [ -n "§expected" ] || exit 75
            printf '$PROTOCOL\n'
            printf '%s' "§directory" | base64 | tr -d '\r\n'
            printf '\n%s\n' "§pid"
        """.trimIndent().shell()
    }

    private fun bit(value: String): Boolean {
        check(value == "0" || value == "1") { malformed() }
        return value == "1"
    }

    private fun decode(value: String): String = try {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) { error(malformed()) }

    private fun malformed() = "Termux 后台任务回包无效；请检查 Termux 版本与后台任务文件是否完整。"

    private fun quote(value: String): String {
        require('\u0000' !in value) { "Termux 后台任务参数不能包含 NUL" }
        return "'${value.replace("'", "'\\''").replace("§", "'\$'\\302\\247''")}'"
    }

    private fun String.shell(): String = replace('§', '$')

    // Linux 使用内核启动时钟；macOS 测试宿主使用完整启动时间，终止前必须核对身份。
    private val identity = """
        process_identity() {
            local value remainder
            if [ -r "/proc/§1/stat" ]; then
                value=§(cat "/proc/§1/stat") || return 1
                remainder=§{value##*) }
                set -- §remainder
                [ "§#" -ge 20 ] || return 1
                printf '%s' "§{20}"
            else
                LC_ALL=C ps -p "§1" -o lstart= 2>/dev/null
            fi
        }
        matches() {
            kill -0 -- "-§pid" 2>/dev/null && [ "§(process_identity "§pid")" = "§expected" ]
        }
    """.trimIndent().shell()

    private val snapshot = """
        code=-
        if [ -f "§directory.exit" ]; then code=§(cat "§directory.exit") || exit 74; fi
        alive=0
        matches && alive=1
        timed_out=0
        stopped=0
        [ -f "§directory/timed_out" ] && timed_out=1
        [ -f "§directory/stopped" ] && stopped=1
        [ -r "§directory.log" ] || exit 74
        count=§(wc -c < "§directory.log") || exit 74
        printf '$PROTOCOL\n%s\n%s\n%s\n%s\n%s\n' "§code" "§alive" "§timed_out" "§stopped" "§count"
        first=§((count > $TAIL_BYTES ? count - $TAIL_BYTES + 1 : 1))
        bytes=§((count < $TAIL_BYTES ? count : $TAIL_BYTES))
        if [ "§bytes" -gt 0 ]; then
            tail -c "+§first" -- "§directory.log" | head -c "§bytes" | base64 | tr -d '\r\n'
        fi
        printf '\n'
    """.trimIndent().shell()

    private val watchdog = """
        set -u
        directory=§1
        pid=§2
        expected=§3
        duration=§4
        $identity
        deadline=§((§(date +%s) + duration))
        printf '%s\n' "§deadline" > "§directory/deadline" || exit 77
        : > "§directory/watchdog_ready" || exit 77
        while [ ! -f "§directory.exit" ] && [ ! -d "§directory/finishing" ]; do
            matches || exit 0
            [ "§(date +%s)" -ge "§deadline" ] && break
            sleep 1
        done
        [ -f "§directory.exit" ] && exit 0
        mkdir -- "§directory/finishing" 2>/dev/null || exit 0
        if matches; then
            : > "§directory/timed_out"
            kill -TERM -- "-§pid" 2>/dev/null || :
            sleep 2
            if matches; then kill -KILL -- "-§pid" 2>/dev/null || :; fi
            printf '124\n' > "§directory.exit.tmp" && mv -- "§directory.exit.tmp" "§directory.exit"
        fi
    """.trimIndent().shell()

    private val worker = """
        set -u
        directory=§1
        command=§2
        workdir=§3
        input=§4
        has_input=§5
        duration=§6
        pid=§§
        $identity
        expected=§(process_identity "§pid")
        [ -n "§expected" ] || exit 76
        printf '%s\n' "§pid" > "§directory/pid" || exit 76
        printf '%s\n' "§expected" > "§directory/identity" || exit 76
        setsid nohup bash -c ${quote(watchdog)} infinity-watch "§directory" "§pid" "§expected" "§duration" </dev/null >/dev/null 2>&1 &
        watcher=§!
        for ((attempt=0; attempt<100; attempt++)); do
            [ -f "§directory/watchdog_ready" ] && break
            kill -0 "§watcher" 2>/dev/null || exit 77
            sleep 0.02
        done
        [ -f "§directory/watchdog_ready" ] || exit 77
        trap ':' TERM
        : > "§directory/ready" || exit 77
        case "§workdir" in
            '~/'*) workdir="§HOME/§{workdir#\~/}" ;;
            '§PREFIX/'*) workdir="§{PREFIX:?}/§{workdir#\§PREFIX/}" ;;
        esac
        code=0
        if [ -e "§directory.cancelled" ]; then
            : > "§directory/stopped"
            code=143
        elif [ -n "§workdir" ] && ! cd -- "§workdir"; then
            code=127
        else
            if [ "§has_input" = 1 ]; then
                printf '%s' "§input" | bash -c "§command" &
            else
                bash -c "§command" </dev/null &
            fi
            child=§!
            wait "§child"
            code=§?
        fi
        if mkdir -- "§directory/finishing" 2>/dev/null; then
            # 保留组长直到 KILL，避免 TERM 后 PID 复用，同时清理命令遗留的子进程。
            kill -TERM -- "-§pid" 2>/dev/null || :
            sleep 2
            printf '%s\n' "§code" > "§directory.exit.tmp" && mv -- "§directory.exit.tmp" "§directory.exit"
            kill -KILL -- "-§pid" 2>/dev/null || :
        else
            while :; do sleep 1; done
        fi
    """.trimIndent().shell()
}
