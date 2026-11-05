package com.promenar.infinity.data.library.ask

import com.promenar.infinity.domain.library.ChunkLocator

/** 把归一化后的命中位置映射回正文的 UTF-16 下标。 */
object DefaultChunkLocator : ChunkLocator {
    override fun locate(body: String, chunkContent: String, fallbackTerms: List<String>): IntRange? {
        if (chunkContent.isNotEmpty()) {
            val exact = body.indexOf(chunkContent)
            if (exact >= 0) return exact until exact + chunkContent.length
            val source = normalize(body)
            val target = normalize(chunkContent).text
            if (target.isNotEmpty()) {
                val start = source.text.indexOf(target)
                if (start >= 0) return source.starts[start]..source.ends[start + target.lastIndex]
            }
        }
        return fallbackTerms.asSequence().filter(String::isNotBlank).mapNotNull { term ->
            body.indexOf(term, ignoreCase = true).takeIf { it >= 0 }?.let { it until it + term.length }
        }.minByOrNull { it.first }
    }

    private data class Normalized(val text: String, val starts: List<Int>, val ends: List<Int>)

    private fun normalize(value: String): Normalized {
        val text = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var pendingSpace: Int? = null
        value.forEachIndexed { index, char ->
            if (char.isWhitespace()) {
                if (text.isNotEmpty() && pendingSpace == null) pendingSpace = index
            } else {
                pendingSpace?.let { start ->
                    text.append(' ')
                    starts += start
                    ends += index - 1
                }
                pendingSpace = null
                text.append(char)
                starts += index
                ends += index
            }
        }
        return Normalized(text.toString(), starts, ends)
    }
}
