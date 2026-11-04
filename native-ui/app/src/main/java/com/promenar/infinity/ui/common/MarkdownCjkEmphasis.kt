package com.promenar.infinity.ui.common

/**
 * 中日韩标点与星号强调定界符的交界修复。
 *
 * CommonMark 的定界规则把中文标点视作标点、把汉字视作字母：`神仙。**至于` 中的 `**` 左侧是标点、
 * 右侧是字母，不算右侧定界符，无法闭合；`这叫**“画符”` 中的 `**` 左侧是字母、右侧是标点，
 * 不算左侧定界符，无法开启。两种情况都会让星号原样显示。
 *
 * 修复参考 markdown-cjk-friendly 的思路，只处理至少一侧为中日韩字符的交界：在标点与定界符之间
 * 插入零宽空格（U+200B）。零宽空格既不是 CommonMark 空白也不是标点，插入后定界符的这一侧
 * 视同紧邻字母，与英文词内的 `a**b**c` 规则一致。为避免改动原本就能解析的写法，只在定界符
 * 处于本行同长度定界符的闭合位（奇数序号）时修补闭合，处于开启位（偶数序号）时修补开启。
 *
 * 下划线定界符不做处理：CommonMark 禁止词内下划线强调，零宽字符无法让它生效，
 * 只能插入可见空格，代价超过收益。
 *
 * 调用方负责先把代码、链接、公式与网址替换为占位符；占位符的 `\u0000` 被解析器视作空白，
 * 不会触发修补。
 */
internal fun bridgeCjkEmphasisDelimiters(text: String): String {
    if ('*' !in text) return text
    return text.split('\n').joinToString("\n", transform = ::bridgeCjkEmphasisInLine)
}

private const val ZERO_WIDTH_SPACE = '​'
private const val MAX_BRIDGED_RUN = 3

private fun bridgeCjkEmphasisInLine(line: String): String {
    if ('*' !in line) return line
    val result = StringBuilder(line.length + 4)
    // 按定界符长度分别计数，`**` 与 `*` 的开闭位互不干扰。
    val runCounts = IntArray(MAX_BRIDGED_RUN + 1)
    var index = 0
    while (index < line.length) {
        if (line[index] != '*') {
            result.append(line[index])
            index++
            continue
        }
        var end = index
        while (end < line.length && line[end] == '*') end++
        val run = line.substring(index, end)
        val before = line.getOrNull(index - 1)
        val after = line.getOrNull(end)
        index = end

        // 反斜杠转义的星号是普通字符；两侧都是空白的星号是列表符号、分隔线或乘号，不参与开闭计数。
        val isEscaped = before == '\\'
        val bothSidesBlank = before.isMarkdownWhitespace() && after.isMarkdownWhitespace()
        if (run.length > MAX_BRIDGED_RUN || isEscaped || bothSidesBlank) {
            result.append(run)
            continue
        }

        val closingPosition = runCounts[run.length] % 2 == 1
        runCounts[run.length]++
        val cjkBoundary = before.isCjk() || after.isCjk()
        when {
            closingPosition && cjkBoundary && before.isMarkdownPunctuation() && after.isMarkdownWordChar() ->
                result.append(ZERO_WIDTH_SPACE).append(run)
            !closingPosition && cjkBoundary && before.isMarkdownWordChar() && after.isMarkdownPunctuation() ->
                result.append(run).append(ZERO_WIDTH_SPACE)
            else -> result.append(run)
        }
    }
    return result.toString()
}

/** 与 JetBrains Markdown 解析器一致：行首行尾与 `\u0000` 也算空白。 */
private fun Char?.isMarkdownWhitespace(): Boolean =
    this == null || this == '\u0000' || Character.isSpaceChar(this) || isWhitespace()

/** 与 JetBrains Markdown 解析器一致的标点集合：Unicode 标点、数学符号以及 `$`、`^`、反引号。 */
private fun Char?.isMarkdownPunctuation(): Boolean {
    if (this == null) return false
    if (this == '$' || this == '^' || this == '`') return true
    return when (Character.getType(this).toByte()) {
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.CONNECTOR_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION -> true
        else -> false
    }
}

private fun Char?.isMarkdownWordChar(): Boolean = !isMarkdownWhitespace() && !isMarkdownPunctuation()

private fun Char?.isCjk(): Boolean {
    if (this == null) return false
    val script = Character.UnicodeScript.of(code)
    if (script == Character.UnicodeScript.HAN ||
        script == Character.UnicodeScript.HIRAGANA ||
        script == Character.UnicodeScript.KATAKANA ||
        script == Character.UnicodeScript.HANGUL ||
        script == Character.UnicodeScript.BOPOMOFO
    ) {
        return true
    }
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS ||
        block == Character.UnicodeBlock.VERTICAL_FORMS
}
