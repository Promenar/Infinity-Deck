package com.promenar.infinity.ui.lab.terminal

import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline

/*
 * 终端版本的脚本文本。全部是纯函数：同样的输入（状态、列数）总是得到同样的完整屏幕文本，
 * 渲染端每次清空终端缓冲后整段重放，因此拖动进度、重播都不会错乱。
 * 换行一律写成 CR LF：终端模拟器收到单独的 LF 只下移一行而不回到行首。
 */

internal const val ESC = "\u001B"
internal const val CRLF = "\r\n"
private const val RESET = "$ESC[0m"

/** 提示符：身份固定为 root@localhost，工作目录 ~；配色与终端截图参考一致。 */
internal const val LAB_PROMPT = "$ESC[32mroot@localhost$ESC[0m:$ESC[34m~$ESC[0m# "

private val ANSI_SEQUENCE = Regex("$ESC\\[[0-9;?]*[A-Za-z]")

/** 去掉 CSI 控制序列后的可见文本。 */
internal fun ansiVisible(text: String): String = text.replace(ANSI_SEQUENCE, "")

internal fun ansiVisibleWidth(text: String): Int = ansiVisible(text).length

private fun sgr(code: String) = "$ESC[${code}m"

/**
 * 把 fastfetch logo 文件的颜色占位（`$1`…`$9`）转成 ANSI 颜色；`$$` 是字面量 `$`。
 * 当前颜色跨行延续，每行以当前颜色开头、以复位结束，便于逐行拼接。
 * 占位序号超出 [colors] 时沿用第一种颜色。
 */
internal fun fastfetchLogoToAnsi(raw: String, colors: List<String>): List<String> {
    require(colors.isNotEmpty()) { "logo 至少需要一种颜色" }
    var current = colors[0]
    return raw.lines().map { line ->
        buildString {
            append(sgr(current))
            var index = 0
            while (index < line.length) {
                val char = line[index]
                val next = line.getOrNull(index + 1)
                when {
                    char == '$' && next == '$' -> { append('$'); index += 2 }
                    char == '$' && next != null && next in '1'..'9' -> {
                        current = colors.getOrElse(next - '1') { colors[0] }
                        append(RESET).append(sgr(current))
                        index += 2
                    }
                    else -> { append(char); index++ }
                }
            }
            append(RESET)
        }
    }
}

/** fastfetch 仓库 `src/logo/ascii/alpine.txt` 原文。 */
internal val ALPINE_LOGO_RAW: String = """
       .hddddddddddddddddddddddh.
      :dddddddddddddddddddddddddd:
     /dddddddddddddddddddddddddddd/
    +dddddddddddddddddddddddddddddd+
  `sdddddddddddddddddddddddddddddddds`
 `ydddddddddddd++hdddddddddddddddddddy`
.hddddddddddd+`  `+ddddh:-sdddddddddddh.
hdddddddddd+`      `+y:    .sddddddddddh
ddddddddh+`   `//`   `.`     -sddddddddd
ddddddh+`   `/hddh/`   `:s-    -sddddddd
ddddh+`   `/+/dddddh/`   `+s-    -sddddd
ddd+`   `/o` :dddddddh/`   `oy-    .yddd
hdddyo+ohddyosdddddddddho+oydddy++ohdddh
.hddddddddddddddddddddddddddddddddddddh.
 `yddddddddddddddddddddddddddddddddddy`
  `sdddddddddddddddddddddddddddddddds`
    +dddddddddddddddddddddddddddddd+
     /dddddddddddddddddddddddddddd/
      :dddddddddddddddddddddddddd:
       .hddddddddddddddddddddddh.
""".removePrefix("\n").removeSuffix("\n")

/** fastfetch 内置 Alpine 配色只有蓝色一种；默认 brightColor 以粗体输出。 */
private const val ALPINE_BLUE = "1;34"

// ── fastfetch 状态版本 ──

internal enum class FastfetchLayout { SIDE_BY_SIDE, STACKED }

internal object LabFastfetch {
    /** fastfetch 默认 logo 右侧留白。 */
    const val LOGO_PADDING_RIGHT = 4

    val logoLines: List<String> = fastfetchLogoToAnsi(ALPINE_LOGO_RAW, listOf(ALPINE_BLUE))
    val logoWidth: Int = logoLines.maxOf(::ansiVisibleWidth)

    private fun key(name: String, value: String) = "${sgr(ALPINE_BLUE)}$name$RESET: $value"

    /** fastfetch 按 50% / 80% 阈值给百分比着绿、黄、红色。 */
    private fun percent(value: Int): String {
        val color = when {
            value <= 50 -> "32"
            value <= 80 -> "33"
            else -> "31"
        }
        return "(${sgr(color)}$value%$RESET)"
    }

    private val COLOR_BLOCKS = listOf(
        (40..47).joinToString("") { "${sgr(it.toString())}   " } + RESET,
        (100..107).joinToString("") { "${sgr(it.toString())}   " } + RESET,
    )

    /** 宣传素材原文：内屏一次录制的内存为 70%，外屏竖排那次为 72%。 */
    fun infoLines(layout: FastfetchLayout): List<String> {
        val memory = when (layout) {
            FastfetchLayout.SIDE_BY_SIDE -> "10.36 GiB / 14.76 GiB ${percent(70)}"
            FastfetchLayout.STACKED -> "10.59 GiB / 14.76 GiB ${percent(72)}"
        }
        val title = "${sgr(ALPINE_BLUE)}root$RESET@${sgr(ALPINE_BLUE)}localhost$RESET"
        return listOf(
            title,
            "-".repeat(ansiVisibleWidth(title)),
            key("OS", "Alpine Linux v3.24 aarch64"),
            key("Kernel", "Linux 6.6.118-android15-8-gc4127a25dcf3k"),
            key("Packages", "52 (apk)"),
            key("Shell", "bash 5.3.9"),
            key("Terminal", "libnxproot.so"),
            key("CPU", "Oryon 1*7 (7) @ 4.32 GHz"),
            key("Memory", memory),
            key("Swap", "7.60 GiB / 12.00 GiB ${percent(63)}"),
            key("Locale", "C.UTF-8"),
            "",
        ) + COLOR_BLOCKS
    }

    /** 左右并排所需的最少列数：logo、留白与最长信息行都完整放下。 */
    val sideBySideMinColumns: Int =
        logoWidth + LOGO_PADDING_RIGHT + infoLines(FastfetchLayout.SIDE_BY_SIDE).maxOf(::ansiVisibleWidth)

    fun layoutFor(columns: Int): FastfetchLayout =
        if (columns >= sideBySideMinColumns) FastfetchLayout.SIDE_BY_SIDE else FastfetchLayout.STACKED

    /** fastfetch 自身的输出行（不含命令行与提示符）。 */
    fun outputLines(layout: FastfetchLayout): List<String> {
        val info = infoLines(layout)
        return when (layout) {
            FastfetchLayout.STACKED -> logoLines + "" + info
            FastfetchLayout.SIDE_BY_SIDE -> (0 until maxOf(logoLines.size, info.size)).map { row ->
                val logo = logoLines.getOrNull(row)
                val text = info.getOrNull(row)
                val logoPart = logo.orEmpty() + " ".repeat(logoWidth - (logo?.let(::ansiVisibleWidth) ?: 0))
                if (text.isNullOrEmpty()) logo.orEmpty() else logoPart + " ".repeat(LOGO_PADDING_RIGHT) + text
            }
        }
    }

    /**
     * 整屏文本：提示符与命令、输出、下一个提示符。
     * fastfetch 默认在输出期间关闭自动换行（DECAWM），超宽行在右边界截断而不是折行。
     */
    fun script(columns: Int): String = buildString {
        append(LAB_PROMPT).append("fastfetch").append(CRLF)
        append("$ESC[?7l")
        append(outputLines(layoutFor(columns)).joinToString(CRLF))
        append("$ESC[?7h")
        append(CRLF).append(LAB_PROMPT)
    }
}

// ── apk add 工作流版本 ──

/** [typed] 为已打出的命令；[entered] 表示已回车；[outputLines] 为已出现的输出行数。 */
internal data class LabApkAddState(
    val typed: String = "",
    val entered: Boolean = false,
    val outputLines: Int = 0,
)

internal object LabApkAdd {
    const val COMMAND = "apk add fastfetch"
    const val TYPING_CHARS_PER_SECOND = 18f
    const val TYPING_START_MS = 600L
    const val ENTER_PAUSE_MS = 250L
    const val HOLD_MS = 2_000L

    private const val MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24"

    /** Alpine v3.24 aarch64 上 fastfetch 2.64.2-r0 的运行依赖只有 hwdata-pci（另需已装的 musl）。 */
    val OUTPUT: List<String> = listOf(
        "fetch $MIRROR/main/aarch64/APKINDEX.tar.gz",
        "fetch $MIRROR/community/aarch64/APKINDEX.tar.gz",
        "(1/2) Installing hwdata-pci (0.408-r0)",
        "(2/2) Installing fastfetch (2.64.2-r0)",
        "OK: 122.2 MiB in 52 packages",
    )

    /** 每行相对上一事件的延迟：第一项是回车后到首行输出，其余为行间隔（120–350 ms）。 */
    val LINE_DELAYS_MS: List<Long> = listOf(400L, 330L, 350L, 170L, 240L)

    init {
        check(LINE_DELAYS_MS.size == OUTPUT.size)
    }

    fun timeline(): LabTimeline<LabApkAddState> = labTimeline(LabApkAddState()) {
        stream(at = TYPING_START_MS, charsPerSecond = TYPING_CHARS_PER_SECOND, text = COMMAND) { state, partial ->
            state.copy(typed = partial)
        }
        after(ENTER_PAUSE_MS) { it.copy(entered = true) }
        LINE_DELAYS_MS.forEachIndexed { index, delay ->
            after(delay) { it.copy(outputLines = index + 1) }
        }
        hold(HOLD_MS)
    }

    /** 输出全部出现后，新提示符与最后一行同时出现，与真实 shell 一致。 */
    fun script(state: LabApkAddState): String = buildString {
        append(LAB_PROMPT).append(state.typed)
        if (!state.entered) return@buildString
        append(CRLF)
        val shown = state.outputLines.coerceIn(0, OUTPUT.size)
        OUTPUT.take(shown).forEach { append(it).append(CRLF) }
        if (shown == OUTPUT.size) append(LAB_PROMPT)
    }
}
