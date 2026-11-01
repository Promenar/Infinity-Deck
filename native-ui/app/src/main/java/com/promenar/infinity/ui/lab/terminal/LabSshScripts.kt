package com.promenar.infinity.ui.lab.terminal

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 修好之后在 web-01 上的复核：两个用户级服务都在运行，健康检查与星表接口返回 200。
 * 身份是演示账号 deploy@web-01，单元文件在 /etc/systemd/user，画面里没有家目录与网络地址。
 * [restartedAt] 是 starlog-api 重启的时刻，[now] 用于“多久之前”，二者都由场景按墙钟给出。
 */
internal object LabSshWeb01 {
    const val ALIAS = "web-01"
    private const val RESET = "$ESC[0m"
    private const val GREEN = "$ESC[32m"
    private const val GREEN_BOLD = "$ESC[1;32m"
    private val prompt = "$GREEN_BOLD" + "deploy@web-01$RESET:$ESC[1;34m~$RESET$ "
    // 服务器按 UTC 记时，与 systemd 在云主机上的常见输出一致。
    private val stamp = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    private fun ago(fromSeconds: Long): String {
        val minutes = fromSeconds / 60
        return if (minutes > 0) "${minutes}min ${fromSeconds % 60}s ago" else "${fromSeconds}s ago"
    }

    private fun unit(name: String, description: String, since: ZonedDateTime, now: ZonedDateTime, pid: Int, command: String) = listOf(
        "$GREEN●$RESET $name.service - $description",
        "     Loaded: loaded (/etc/systemd/user/$name.service; enabled; preset: enabled)",
        "     Active: $GREEN_BOLD" + "active (running)$RESET since ${stamp.format(since)}; ${ago(now.toEpochSecond() - since.toEpochSecond())}",
        "   Main PID: $pid ($command)",
    )

    fun lines(restartedAt: ZonedDateTime, now: ZonedDateTime): List<String> {
        val edgeSince = restartedAt.minusMinutes(43).minusSeconds(21)
        return buildList {
            add(prompt + "systemctl --user status starlog-api starlog-edge --no-pager")
            addAll(unit("starlog-api", "starlog API", restartedAt, now, 112476, "python3"))
            add("")
            addAll(unit("starlog-edge", "starlog edge proxy (:8080)", edgeSince, now, 108363, "python3"))
            add(prompt + "curl -s localhost:8080/api/stars; echo")
            add("[{\"name\": \"Sirius\", \"mag\": -1.46}, {\"name\": \"Canopus\", \"mag\": -0.74}, " +
                "{\"name\": \"Arcturus\", \"mag\": -0.05}, {\"name\": \"Vega\", \"mag\": 0.03}]")
            add(prompt + "curl -s -o /dev/null -w 'HTTP %{http_code}\\n' localhost:8080/health")
            add("HTTP 200")
            add(prompt)
        }
    }

    fun script(restartedAt: ZonedDateTime, now: ZonedDateTime): String = lines(restartedAt, now).joinToString(CRLF)
}
