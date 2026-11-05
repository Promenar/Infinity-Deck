package com.promenar.infinity.ui.library

/*
 * 文档页定位：把命中区间扩展到所在段落，切成“普通段 / 命中段”交替的片段，分别渲染并高亮命中段。
 * 按段落边界（空行）切分，Markdown 的标题、列表与表格不会被拦腰截断；
 * 切点落在代码围栏内部时不切 Markdown，交给调用方改用纯文本渲染。
 */

/** 正文片段：[hitOrdinal] 为命中段在正文中的先后序号（从 0 起），普通段为 null。 */
internal data class BodySegment(val text: String, val hitOrdinal: Int?)

/** 定位结果：片段与命中段数量；[plainText] 为真表示 Markdown 无法安全切分，按纯文本渲染。 */
internal data class LocatedBody(val segments: List<BodySegment>, val hitCount: Int, val plainText: Boolean)

/** 把 [range] 向两侧扩展到段落边界（空行或正文首尾），返回半开区间的起止下标。 */
internal fun paragraphBounds(body: String, range: IntRange): Pair<Int, Int> {
    val first = range.first.coerceIn(0, body.length)
    val last = range.last.coerceIn(first - 1, body.length - 1)
    val before = body.lastIndexOf("\n\n", (first - 1).coerceAtLeast(0))
    val start = if (first == 0 || before < 0) 0 else before + 2
    val after = body.indexOf("\n\n", (last + 1).coerceAtLeast(start))
    val end = if (after < 0) body.length else after
    return start to maxOf(end, start)
}

/**
 * 按命中区间切分正文。区间先扩展到段落，再按位置排序、合并落在同一段落的命中；没有有效区间时返回 null。
 * 相邻段落各自成为一处命中，定位条可以在它们之间切换。
 * [markdown] 为真且某个切点落在 ``` 围栏内部时，[LocatedBody.plainText] 置真，片段仍按同一切点给出。
 */
internal fun locateBody(body: String, ranges: List<IntRange>, markdown: Boolean): LocatedBody? {
    val bounds = ranges
        .filter { !it.isEmpty() && it.first < body.length }
        .map { paragraphBounds(body, it) }
        .sortedBy { it.first }
    if (bounds.isEmpty()) return null
    val merged = mutableListOf<Pair<Int, Int>>()
    for (bound in bounds) {
        val previous = merged.lastOrNull()
        if (previous != null && bound.first < previous.second) {
            merged[merged.lastIndex] = previous.first to maxOf(previous.second, bound.second)
        } else {
            merged += bound
        }
    }
    val segments = mutableListOf<BodySegment>()
    var cursor = 0
    merged.forEachIndexed { ordinal, (start, end) ->
        if (start > cursor) segments += BodySegment(body.substring(cursor, start).trimEnd('\n'), null)
        segments += BodySegment(body.substring(start, end), ordinal)
        cursor = end
    }
    if (cursor < body.length) segments += BodySegment(body.substring(cursor).trimStart('\n'), null)
    val cuts = merged.flatMap { listOf(it.first, it.second) }
    val unsafe = markdown && cuts.any { insideCodeFence(body, it) }
    return LocatedBody(segments.filter { it.hitOrdinal != null || it.text.isNotBlank() }, merged.size, unsafe)
}

/** [offset] 之前出现奇数个以 ``` 或 ~~~ 开头的行，即位于代码围栏内部。 */
internal fun insideCodeFence(body: String, offset: Int): Boolean {
    var fences = 0
    var lineStart = 0
    val limit = offset.coerceIn(0, body.length)
    while (lineStart < limit) {
        val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
        val line = body.substring(lineStart, minOf(lineEnd, limit)).trimStart()
        if (line.startsWith("```") || line.startsWith("~~~")) fences++
        lineStart = lineEnd + 1
    }
    return fences % 2 == 1
}
