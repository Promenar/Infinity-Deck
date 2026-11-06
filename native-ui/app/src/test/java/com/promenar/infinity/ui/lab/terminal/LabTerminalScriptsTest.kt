package com.promenar.infinity.ui.lab.terminal

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.LabScenes
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene
import org.junit.Test

class LabTerminalScriptsTest {
    private val placeholder = Regex("\\$[0-9$]")
    private val ipAddress = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")

    // ── ANSI 转换 ──

    @Test
    fun `logo 占位转为 ANSI 颜色且不残留占位符`() {
        val lines = fastfetchLogoToAnsi("\$1ab\$2cd\nef\$\$g\$9h", listOf("1;34", "31"))
        lines.forEach { assertThat(placeholder.containsMatchIn(it)).isFalse() }
        assertThat(lines.map(::ansiVisible)).containsExactly("abcd", "ef\$gh").inOrder()
        // 颜色跨行延续：第二行以 $2 设定的红色开头；超出配色表的 $9 回落到第一种颜色。
        assertThat(lines[1]).startsWith("\u001B[31m")
        assertThat(lines[1]).contains("\u001B[1;34mh")
        lines.forEach { assertThat(it).endsWith("\u001B[0m") }
    }

    @Test
    fun `Alpine logo 着蓝色且可见文本与原文一致`() {
        val logo = LabFastfetch.logoLines
        assertThat(logo).hasSize(20)
        assertThat(logo.map(::ansiVisible)).isEqualTo(ALPINE_LOGO_RAW.lines())
        logo.forEach {
            assertThat(it).startsWith("\u001B[1;34m")
            assertThat(placeholder.containsMatchIn(it)).isFalse()
        }
        assertThat(LabFastfetch.logoWidth).isEqualTo(40)
    }

    // ── fastfetch 并排与竖排 ──

    @Test
    fun `列数够放下 logo、留白和最长信息行时并排，否则竖排`() {
        val min = LabFastfetch.sideBySideMinColumns
        val longest = LabFastfetch.infoLines(FastfetchLayout.SIDE_BY_SIDE).maxOf(::ansiVisibleWidth)
        assertThat(min).isEqualTo(LabFastfetch.logoWidth + LabFastfetch.LOGO_PADDING_RIGHT + longest)
        assertThat(LabFastfetch.layoutFor(min)).isEqualTo(FastfetchLayout.SIDE_BY_SIDE)
        assertThat(LabFastfetch.layoutFor(min - 1)).isEqualTo(FastfetchLayout.STACKED)
        assertThat(LabFastfetch.layoutFor(120)).isEqualTo(FastfetchLayout.SIDE_BY_SIDE)
        assertThat(LabFastfetch.layoutFor(38)).isEqualTo(FastfetchLayout.STACKED)
    }

    @Test
    fun `并排时信息行紧跟 logo 且每行不超过列数`() {
        val columns = LabFastfetch.sideBySideMinColumns
        val lines = LabFastfetch.outputLines(FastfetchLayout.SIDE_BY_SIDE).map(::ansiVisible)
        assertThat(lines).hasSize(20)
        lines.forEach { assertThat(it.length).isAtMost(columns) }
        val infoColumn = LabFastfetch.logoWidth + LabFastfetch.LOGO_PADDING_RIGHT
        assertThat(lines[0].substring(infoColumn)).isEqualTo("root@localhost")
        assertThat(lines[2].substring(infoColumn)).isEqualTo("OS: Alpine Linux v3.24 aarch64")
        assertThat(lines[8].substring(infoColumn)).isEqualTo("Memory: 10.36 GiB / 14.76 GiB (70%)")
        assertThat(lines.last()).isEqualTo(ALPINE_LOGO_RAW.lines().last())
    }

    @Test
    fun `竖排时 logo 在上信息在下`() {
        val lines = LabFastfetch.outputLines(FastfetchLayout.STACKED).map(::ansiVisible)
        assertThat(lines.take(20)).isEqualTo(ALPINE_LOGO_RAW.lines())
        assertThat(lines[20]).isEmpty()
        assertThat(lines[21]).isEqualTo("root@localhost")
        assertThat(lines).contains("Memory: 10.59 GiB / 14.76 GiB (72%)")
    }

    @Test
    fun `fastfetch 整屏含命令、全部信息与下一个提示符`() {
        listOf(120, 40).forEach { columns ->
            val visible = ansiVisible(LabFastfetch.script(columns))
            assertThat(visible).startsWith("root@localhost:~# fastfetch\r\n")
            assertThat(visible).endsWith("\r\nroot@localhost:~# ")
            listOf(
                "OS: Alpine Linux v3.24 aarch64",
                "Kernel: Linux 6.6.118-android15-8-gc4127a25dcf3k",
                "Packages: 52 (apk)",
                "Shell: bash 5.3.9",
                "Terminal: libnxproot.so",
                "CPU: Oryon 1*7 (7) @ 4.32 GHz",
                "Swap: 7.60 GiB / 12.00 GiB (63%)",
                "Locale: C.UTF-8",
            ).forEach { assertThat(visible).contains(it) }
        }
    }

    // ── apk add 工作流 ──

    private val timeline = LabApkAdd.timeline()
    private val typingEnd = LabApkAdd.TYPING_START_MS +
        Math.ceil(LabApkAdd.COMMAND.length / LabApkAdd.TYPING_CHARS_PER_SECOND.toDouble() * 1000).toLong()
    private val enterAt = typingEnd + LabApkAdd.ENTER_PAUSE_MS
    private fun lineAt(index: Int) = enterAt + LabApkAdd.LINE_DELAYS_MS.take(index + 1).sum()
    private fun textAt(t: Long, columns: Int = 120) = ansiVisible(LabApkAdd.script(timeline.stateAt(t), columns))

    @Test
    fun `开头只有提示符`() {
        assertThat(textAt(0)).isEqualTo("root@localhost:~# ")
        assertThat(textAt(LabApkAdd.TYPING_START_MS - 1)).isEqualTo("root@localhost:~# ")
    }

    @Test
    fun `打字中途按 18 字每秒显示部分命令`() {
        // 5 个字符需要 5 / 18 秒，约 278 ms。
        assertThat(textAt(LabApkAdd.TYPING_START_MS + 280)).isEqualTo("root@localhost:~# apk a")
        assertThat(textAt(enterAt - 1)).isEqualTo("root@localhost:~# apk add fastfetch")
    }

    @Test
    fun `回车后约 0_4 秒开始逐行输出`() {
        assertThat(textAt(enterAt)).isEqualTo("root@localhost:~# apk add fastfetch\r\n")
        assertThat(textAt(lineAt(0) - 1)).isEqualTo("root@localhost:~# apk add fastfetch\r\n")
        val middle = textAt(lineAt(1))
        assertThat(middle).contains("fetch https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main/aarch64/APKINDEX.tar.gz\r\n")
        assertThat(middle).contains("/community/aarch64/APKINDEX.tar.gz\r\n")
        assertThat(middle).doesNotContain("Installing")
        assertThat(middle).doesNotContain("OK:")
    }

    @Test
    fun `安装结束停在 OK 行和新提示符，停顿后再输入 fastfetch`() {
        val last = lineAt(LabApkAdd.OUTPUT.lastIndex)
        assertThat(last).isEqualTo(LabApkAdd.INSTALL_DONE_AT)
        assertThat(textAt(last - 1)).doesNotContain("OK:")
        val end = textAt(last)
        assertThat(end).contains("(1/2) Installing hwdata-pci (0.408-r0)\r\n")
        assertThat(end).contains("(2/2) Installing fastfetch (2.64.2-r0)\r\n")
        assertThat(end).endsWith("OK: 122.2 MiB in 52 packages\r\nroot@localhost:~# ")
        assertThat(textAt(LabApkAdd.FASTFETCH_TYPING_AT - 1)).isEqualTo(end)
    }

    @Test
    fun `接着运行 fastfetch，输出与静态版本一致并停留 3 秒`() {
        val typed = LabApkAdd.FASTFETCH_TYPING_AT + LabApkAdd.typingMs(LabApkAdd.FASTFETCH)
        assertThat(textAt(typed)).endsWith("52 packages\r\nroot@localhost:~# fastfetch")
        val outputAt = typed + LabApkAdd.ENTER_PAUSE_MS + LabApkAdd.FASTFETCH_OUTPUT_MS
        assertThat(textAt(outputAt - 1)).doesNotContain("Packages:")
        listOf(120, 36).forEach { columns ->
            val final = textAt(outputAt, columns)
            assertThat(final).endsWith(ansiVisible(LabFastfetch.script(columns)))
            assertThat(final).contains("Packages: 52 (apk)")
        }
        assertThat(timeline.durationMs).isEqualTo(outputAt + LabApkAdd.HOLD_MS)
        assertThat(textAt(timeline.durationMs)).isEqualTo(textAt(outputAt))
    }

    @Test
    fun `行间隔在 120 到 350 毫秒且任意跳转结果只由时刻决定`() {
        assertThat(LabApkAdd.LINE_DELAYS_MS.first()).isEqualTo(400)
        LabApkAdd.LINE_DELAYS_MS.drop(1).forEach { assertThat(it).isIn(120L..350L) }
        val times = listOf(timeline.durationMs, 0L, lineAt(2), LabApkAdd.TYPING_START_MS + 500, lineAt(2), timeline.durationMs)
        val texts = times.map(::textAt)
        assertThat(texts[2]).isEqualTo(texts[4])
        assertThat(texts[0]).isEqualTo(texts[5])
    }

    // ── 隐私与登记 ──

    @Test
    fun `画面不含真实用户名、家目录或 IP 地址`() {
        val all = listOf(LabFastfetch.script(120), LabFastfetch.script(36), LabApkAdd.script(timeline.stateAt(timeline.durationMs), 120))
        all.forEach { text ->
            assertThat(text).doesNotContain("promenar")
            assertThat(text).doesNotContain("/home/")
            assertThat(ipAddress.containsMatchIn(ansiVisible(text))).isFalse()
        }
    }

    @Test
    fun `web-01 复核显示两个服务运行中、四颗星与 200，且不含家目录与地址`() {
        val now = java.time.ZonedDateTime.of(2026, 11, 1, 20, 41, 43, 0, java.time.ZoneId.of("Asia/Shanghai"))
        val lines = LabSshWeb01.lines(now.minusMinutes(2).minusSeconds(4), now).map(::ansiVisible)
        assertThat(lines.count { it.contains("active (running)") }).isEqualTo(2)
        assertThat(lines.first { it.contains("starlog-api.service -") }).startsWith("● ")
        assertThat(lines.single { it.contains("Sirius") }).contains("Vega")
        assertThat(lines).contains("HTTP 200")
        assertThat(lines.first { it.contains("Active:") }).endsWith("2min 4s ago")
        assertThat(lines.last()).isEqualTo("deploy@web-01:~$ ")
        val text = LabSshWeb01.script(now.minusMinutes(2), now)
        assertThat(text).doesNotContain("promenar")
        assertThat(text).doesNotContain("/home/")
        assertThat(ipAddress.containsMatchIn(ansiVisible(text))).isFalse()
    }

    @Test
    fun `终端版本已登记为对应的场景类型`() {
        assertThat(LabScenes.scene("terminal.state.fastfetch")).isInstanceOf(LabStateScene::class.java)
        assertThat(LabScenes.scene("terminal.state.ssh-web01")).isInstanceOf(LabStateScene::class.java)
        assertThat(LabScenes.scene("terminal.workflow.apk-add")).isInstanceOf(LabWorkflowScene::class.java)
    }
}
