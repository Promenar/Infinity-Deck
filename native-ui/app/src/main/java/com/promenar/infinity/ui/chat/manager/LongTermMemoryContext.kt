package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.memory.LongTermMemory
import java.util.Locale

/** 记忆是资料；正文按整条纳入预算，不能通过字段换行或标签改变资料边界。 */
internal fun longTermMemoryContext(memories: List<LongTermMemory>, canReadFullContent: Boolean): String {
    if (memories.isEmpty()) return ""
    return buildString {
        appendLine("### Active memory")
        var remainingContentChars = 6000
        var titlesOnly = false
        for (memory in memories.sortedByDescending { it.updatedAt }) {
            val content = memory.content.escapeMemoryMaterial()
            append("- [${memory.id.escapeMemoryMaterial()}] (${memory.kind.name.lowercase(Locale.ROOT)}) ${memory.title.escapeMemoryMaterial()}")
            if (!titlesOnly && content.length <= remainingContentChars) {
                append(": $content")
                remainingContentChars -= content.length
            } else {
                titlesOnly = true
            }
            appendLine()
        }
        if (titlesOnly) {
            if (canReadFullContent) appendLine("部分记忆仅列标题；需要正文时用 memory_read 按 id 读取。")
            else appendLine("部分记忆因正文预算限制仅列标题。")
        }
    }
}

private fun String.escapeMemoryMaterial(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
