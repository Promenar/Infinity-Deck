package com.promenar.infinity.data.document

/**
 * 长 Markdown 的分块：查看器按块懒加载，只组合屏幕附近的块。
 * 只在块级边界切分（ATX 标题前、空行后且下一行不缩进），围栏代码块内部不切；
 * 各块用 "\n" 连接后与原文完全一致。
 */
object MarkdownChunks {
    /** 不超过该字符数时整篇作为一块渲染。 */
    const val WHOLE_LIMIT_CHARS = 256 * 1024
    /** 分块时每块的目标字符数。 */
    const val TARGET_CHARS = 16 * 1024

    fun split(text: String, wholeLimit: Int = WHOLE_LIMIT_CHARS, target: Int = TARGET_CHARS): List<String> {
        require(target > 0) { "分块目标必须为正数" }
        if (text.length <= wholeLimit) return listOf(text)
        val lines = text.split('\n')
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentLines = 0
        var fence: Fence? = null
        var previousBlank = false
        lines.forEachIndexed { index, line ->
            val outside = fence == null
            val boundary = outside && currentLines > 0 && (isHeading(line) || (previousBlank && !startsIndented(line) && line.isNotBlank()))
            // 一直遇不到块级边界时（例如超长段落），在围栏外的任意行首强制切开，避免单块过大。
            val forced = outside && currentLines > 0 && current.length >= target * FORCE_FACTOR
            if ((boundary && current.length >= target) || forced) {
                chunks += current.toString()
                current.setLength(0)
                currentLines = 0
            }
            if (currentLines > 0) current.append('\n')
            current.append(line)
            currentLines++
            fence = nextFence(fence, line)
            previousBlank = line.isBlank()
            if (index == lines.lastIndex) chunks += current.toString()
        }
        return chunks
    }

    private const val FORCE_FACTOR = 4

    private data class Fence(val marker: Char, val length: Int)

    private fun nextFence(open: Fence?, line: String): Fence? {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return open
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return open
        val run = trimmed.takeWhile { it == marker }.length
        if (run < 3) return open
        return when {
            open == null -> Fence(marker, run)
            open.marker == marker && run >= open.length && trimmed.substring(run).isBlank() -> null
            else -> open
        }
    }

    private fun isHeading(line: String): Boolean {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return false
        val hashes = trimmed.takeWhile { it == '#' }.length
        return hashes in 1..6 && (trimmed.length == hashes || trimmed[hashes] == ' ' || trimmed[hashes] == '\t')
    }

    private fun startsIndented(line: String): Boolean = line.startsWith(' ') || line.startsWith('\t')
}
