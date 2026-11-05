package com.promenar.infinity.data.rag

data class KgModelLimits(val maxOutputTokens: Int? = null, val contextTokens: Int? = null)

/** 窗口保留原始字符，便于复用断点并准确核对来源。 */
object KgWindowing {
    const val ALGORITHM_VERSION = 1

    fun effectiveOutputLimit(configured: Int, limits: KgModelLimits?): Int =
        limits?.maxOutputTokens?.takeIf { it > 0 }?.let { minOf(configured, it) } ?: configured

    fun effectiveWindowChars(configured: Int, limits: KgModelLimits?, outputLimit: Int, systemPrompt: String): Int {
        val context = limits?.contextTokens?.takeIf { it > 0 } ?: return configured
        val available = (context.toLong() - outputLimit - systemPrompt.length).coerceAtLeast(2000)
        return minOf(configured.toLong(), available).toInt()
    }

    fun split(text: String, maxChars: Int): List<String> {
        require(maxChars >= 2) { "图谱窗口至少包含两个字符" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun emit(part: String) {
            if (current.length + part.length > maxChars && current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
            current.append(part)
        }
        fun appendParagraph(paragraph: String) {
            if (paragraph.length <= maxChars) {
                emit(paragraph)
                return
            }
            var start = 0
            while (start < paragraph.length) {
                var end = minOf(start + maxChars, paragraph.length)
                if (end < paragraph.length) {
                    if (paragraph[end - 1].isHighSurrogate() && paragraph[end].isLowSurrogate()) end--
                    val sentence = (end downTo start + 1).firstOrNull { paragraph[it - 1] in "。！？.!?；;\n" }
                    if (sentence != null) end = sentence
                }
                emit(paragraph.substring(start, end))
                start = end
            }
        }
        var start = 0
        Regex("\\n[\\t \\r]*\\n").findAll(text).forEach { match ->
            val end = match.range.last + 1
            appendParagraph(text.substring(start, end))
            start = end
        }
        if (start < text.length) appendParagraph(text.substring(start))
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
