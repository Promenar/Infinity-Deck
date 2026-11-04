package com.promenar.infinity.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class LogicalLineWidthCache {
    private var measuredLines = emptyMap<String, Int>()

    fun widestLogicalLineWidthPx(text: String, measureLine: (String) -> Int): Int {
        val currentLines = HashMap<String, Int>()
        var widest = 0
        for (line in text.lineSequence()) {
            val width = currentLines.getOrPut(line) { measuredLines[line] ?: measureLine(line) }
            widest = maxOf(widest, width)
        }
        // 不保存流式尾行的所有旧版本，缓存大小始终受当前内容约束。
        measuredLines = currentLines
        return widest
    }
}

internal data class CodeHighlightRequest(val code: String, val streaming: Boolean)

internal suspend fun collectCodeHighlights(
    requests: Flow<CodeHighlightRequest>,
    nowMillis: () -> Long,
    highlight: suspend (String) -> AnnotatedString,
    onHighlighted: (AnnotatedString) -> Unit,
) = coroutineScope {
    val pending = Channel<CodeHighlightRequest>(Channel.CONFLATED)
    launch {
        try {
            requests.collect { pending.send(it) }
        } finally {
            pending.close()
        }
    }
    var lastStartedAt: Long? = null
    var request = pending.receiveCatching().getOrNull()
    while (request != null) {
        // 等待窗口内合并增量；结束态可以立即排入最终全文，但不能取消正在高亮的一轮。
        while (request != null && request.streaming) {
            val remaining = lastStartedAt?.let { 250L - (nowMillis() - it) } ?: 0L
            if (remaining <= 0L) break
            val newer = withTimeoutOrNull(remaining) { pending.receiveCatching() } ?: break
            request = newer.getOrNull() ?: request
            if (newer.isClosed) {
                delay(remaining)
                break
            }
        }
        val current = request ?: break
        lastStartedAt = nowMillis()
        onHighlighted(highlight(current.code))
        request = pending.receiveCatching().getOrNull()
    }
}

internal fun highlightedCodeWithUnstyledTail(code: String, highlighted: AnnotatedString?): AnnotatedString {
    if (highlighted == null) return AnnotatedString(code)
    val covered = commonPrefixLength(code, highlighted.text)
    return buildAnnotatedString {
        append(highlighted.subSequence(0, covered))
        append(code, covered, code.length)
    }
}

internal class PreparedMarkdownContent(val source: String, val text: String) {
    private val unchanged = source == text
    private val offsets by lazy { markdownSourceOffsets(source, text) }

    fun reveal(frame: StreamRevealFrame): StreamRevealFrame {
        // 状态机提供的同一来源引用证明 text 是该目标的字形前缀；只在目标切换的过渡帧重新比较。
        val sameSource = frame.sourceText === source
        if (sameSource && unchanged) return frame
        val visibleSourceEnd = if (sameSource) minOf(frame.text.length, source.length) else commonPrefixLength(source, frame.text)
        val visibleEnd = if (visibleSourceEnd == source.length) text.length else offsets.ends[visibleSourceEnd]
        val ranges = frame.recentRanges.mapNotNull { range ->
            val start = offsets.starts[range.start.coerceIn(0, visibleSourceEnd)]
            val end = offsets.ends[range.end.coerceIn(0, visibleSourceEnd)]
            if (start < end) StreamRevealRange(start, end, range.revealedAtNanos) else null
        }
        return StreamRevealFrame(text.take(visibleEnd), frame.nowNanos, ranges, sourceText = text)
    }
}

private fun commonPrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var length = 0
    while (length < limit && first[length] == second[length]) length++
    return length
}

private data class MarkdownSourceOffsets(val starts: IntArray, val ends: IntArray)

/**
 * 预处理插删空白、规范化公式分隔符、补齐表格行末管道并移除数字列表前的多余粗体标记；正文字符顺序保持不变。
 * 两种边界分别将插入的空白归到下一字的起点和上一字的终点，避免渐显范围包含临时闭合标记。
 */
private fun markdownSourceOffsets(source: String, rendered: String): MarkdownSourceOffsets {
    val starts = IntArray(source.length + 1)
    val ends = IntArray(source.length + 1)
    var raw = 0
    var output = 0

    fun consume(rawLength: Int, renderedLength: Int) {
        val end = raw + rawLength
        for (index in raw until end) {
            starts[index] = output + (index - raw) * renderedLength / rawLength
            ends[index + 1] = output + (index - raw + 1) * renderedLength / rawLength
        }
        raw = end
        output += renderedLength
    }

    while (raw < source.length && output < rendered.length) {
        when {
            source[raw] == rendered[output] -> consume(1, 1)
            rendered[output] == '|' && source[raw] in "\r\n" && rendered.getOrNull(output + 1)?.let { it in "\r\n" } == true -> output++
            source[raw].isWhitespace() -> consume(1, 0)
            rendered[output].isWhitespace() -> output++
            // 预处理在中日韩标点与星号交界补入的零宽空格没有来源字符。
            rendered[output] == '​' && source[raw] != '​' -> output++
            (source.startsWith("\\[", raw) || source.startsWith("\\]", raw)) && rendered.startsWith("\$\$", output) -> consume(2, 2)
            (source.startsWith("\\(", raw) || source.startsWith("\\)", raw)) && rendered[output] == '$' -> consume(2, 1)
            source.startsWith("**", raw) && source.getOrNull(raw + 2)?.isDigit() == true -> consume(2, 0)
            // 未闭合的公式会暂时隐藏尾部，剩余来源位置都映射到可渲染前缀的终点。
            else -> break
        }
    }
    while (raw < source.length) consume(1, 0)
    starts[source.length] = output
    return MarkdownSourceOffsets(starts, ends)
}
