package com.promenar.infinity.data.generation

/** 历史摘要段之后的取回提示；构建上下文与中途替换摘要共用同一文字。 */
internal const val HISTORY_RECALL_HINT =
    "Summarized turns are archived, not deleted. For exact wording, numbers, code, or details, use session_search with keywords or page through session_read with before/after; omitting id in either tool targets the current session."

/** 按已知摘要原文替换实际摘要段，避免误匹配自定义指令或检索资料中的标签示例。 */
internal fun replaceHistorySummary(systemPrompt: String, summary: String?, previousSummary: String?): String {
    fun section(content: String, english: Boolean) = buildString {
        append("\n## History Summary\n")
        appendLine(if (english) "Earlier turns of this conversation were condensed into the summary below." else "较早的会话已总结为以下摘要。")
        appendLine("<history_summary>")
        appendLine(content.trim())
        appendLine("</history_summary>")
    }
    val replacement = summary?.takeIf(String::isNotBlank)?.let { section(it, english = false) }.orEmpty()
    val previous = previousSummary?.takeIf(String::isNotBlank)?.let { content ->
        listOf(section(content, english = true), section(content, english = false))
            .map { it to systemPrompt.lastIndexOf(it) }.filter { it.second >= 0 }.maxByOrNull { it.second }
    }
    if (previous == null) {
        // 首次压缩发生在一轮中途时摘要段直接追加，取回提示随之补上。
        val hint = if (replacement.isNotEmpty() && HISTORY_RECALL_HINT !in systemPrompt) HISTORY_RECALL_HINT + "\n" else ""
        return systemPrompt + replacement + hint
    }
    val start = previous.second
    var end = start + previous.first.length
    // 移除摘要时一并移除紧随其后的取回提示。
    if (replacement.isEmpty() && systemPrompt.startsWith(HISTORY_RECALL_HINT + "\n", end)) end += HISTORY_RECALL_HINT.length + 1
    return systemPrompt.replaceRange(start, end, replacement)
}
