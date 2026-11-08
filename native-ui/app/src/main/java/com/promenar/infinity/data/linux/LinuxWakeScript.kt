package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger

/** 脚本与协议同包管理；占位符只用于避免 Kotlin 对 shell 变量插值。 */
object LinuxWakeScript {
    val text: String = """
        #!/bin/sh
        usage() {
            printf '%s\n' '用法：infinity-wake [--key KEY] [--title 标题] [--] [消息]' '消息缺省时从 stdin 读取，最多 64 KiB。'
        }
        invalid() { usage >&2; exit 2; }
        key=default
        title=
        while [ "§#" -gt 0 ]; do
            case "§1" in
                -h|--help) usage; exit 0 ;;
                --key) [ "§#" -ge 2 ] || invalid; key=§2; shift 2 ;;
                --title) [ "§#" -ge 2 ] || invalid; title=§2; shift 2 ;;
                --) shift; break ;;
                -*) invalid ;;
                *) break ;;
            esac
        done
        [ "§#" -le 1 ] || invalid
        LC_ALL=C
        export LC_ALL
        case "§key" in ''|*[!A-Za-z0-9._-]*) invalid ;; esac
        [ "§{#key}" -le @MAX_KEY@ ] || invalid
        dir=§{INFINITY_WAKE_DIR-}
        if [ -z "§dir" ] || [ ! -d "§dir" ] || [ ! -w "§dir" ]; then
            printf '%s\n' '只能在 Infinity Deck 的 Linux 会话内、应用运行期间使用。' >&2
            exit 3
        fi
        tmp=§(mktemp "§dir/.tmp-XXXXXXXXXX") || exit 4
        trap 'rm -f "§tmp"' 0
        trap 'exit 4' 1 2 3 15
        # od 将控制字节转成数字，避免不同 awk 实现丢失 NUL；编码后的正文也受长度限制。
        escape_json() {
            od -An -v -tu1 | awk -v mode="§1" '
                BEGIN { printf "\"" }
                function encoded(n) {
                    if (n==92) return "\\\\"
                    if (n==34) return "\\\""
                    if (n<32) return sprintf("\\u%04x", n)
                    return sprintf("%c", n)
                }
                {
                    for (i=1; i<=NF; i++) {
                        n=§i+0
                        if (mode=="title") {
                            if (n<128 || n>=192) count++
                            if (count>@MAX_TITLE@) continue
                            if (n==10 || n==13) { printf " "; continue }
                            printf "%s", encoded(n)
                        } else {
                            if (n<128 || n>=192) count += (n>=240 ? 2 : 1)
                            if (count<=@MAX_BODY@) body=body encoded(n)
                            if (count<=@BODY_PREFIX@) prefix=prefix encoded(n)
                        }
                    }
                }
                END {
                    if (mode!="title") printf "%s", (count>@MAX_BODY@ ? prefix "@TRUNCATED@" : body)
                    printf "\""
                }
            '
        }
        created=§(date -u '+%Y-%m-%dT%H:%M:%SZ') || exit 4
        stamp=§(date -u '+%s')000
        {
            printf '{"v":1,"key":"%s","title":' "§key"
            printf '%s' "§title" | escape_json title
            printf ',"body":'
            if [ "§#" -eq 1 ]; then
                printf '%s' "§1"
            else
                dd bs=1 count=@MAX_FILE@ 2>/dev/null
            fi | escape_json body
            printf ',"created_at":"%s"}\n' "§created"
        } > "§tmp" || exit 4
        random=§{tmp##*/.tmp-}
        mv "§tmp" "§dir/§stamp-§§-§random.json" || exit 4
        exit 0
    """.trimIndent().replace('§', '$')
        .replace("@MAX_KEY@", LinuxWakeLimits.MAX_KEY_CHARS.toString())
        .replace("@MAX_TITLE@", LinuxWakeLimits.MAX_TITLE_CHARS.toString())
        .replace("@MAX_BODY@", LinuxWakeLimits.MAX_BODY_CHARS.toString())
        .replace("@BODY_PREFIX@", (LinuxWakeLimits.MAX_BODY_CHARS - LinuxWakeLimits.TRUNCATED_MARKER.length).toString())
        .replace("@TRUNCATED@", LinuxWakeLimits.TRUNCATED_MARKER)
        .replace("@MAX_FILE@", LinuxWakeLimits.MAX_FILE_BYTES.toString()) + "\n"

    private val installed = mutableMapOf<String, Any>()

    /** 每个 rootfs 实体每进程成功校验一次；重建或失败后允许重试。由 IO 调用方执行。 */
    @Synchronized fun ensureInstalled(rootfs: File) {
        val path = rootfs.absoluteFile.normalize().path
        val attributes = Files.readAttributes(rootfs.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isDirectory)
        val identity = attributes.fileKey() to attributes.creationTime()
        if (installed[path] == identity) return
        install(rootfs)
        installed[path] = identity
    }

    internal fun install(rootfs: File) {
        val target = LinuxRootfsFiles.safePath(rootfs, LinuxWakeLimits.SCRIPT_PATH).toPath()
        Files.createDirectories(target.parent)
        val expected = text.toByteArray(Charsets.UTF_8)
        if (Files.exists(target, NOFOLLOW_LINKS)) require(Files.isRegularFile(target, NOFOLLOW_LINKS))
        if (Files.exists(target, NOFOLLOW_LINKS) && Files.size(target) == expected.size.toLong() && linuxWakeRead(target.toFile()).contentEquals(expected)) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            return
        }
        val temporary = Files.createTempFile(target.parent, ".infinity-wake-", ".tmp")
        try {
            Files.write(temporary, expected)
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"))
            LinuxRootfsFiles.safePath(rootfs, LinuxWakeLimits.SCRIPT_PATH)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun installForLaunch(rootfs: File) {
        try { ensureInstalled(rootfs) }
        catch (_: Exception) { Logger.getLogger("LinuxWake").warning("脚本唤醒命令安装失败，Linux 启动继续") }
    }
}
