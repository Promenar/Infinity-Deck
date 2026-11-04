package com.promenar.infinity.ui.common

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

/** 行内代码在某一行上实际要画底色的字符区间。 */
internal data class InlineCodeSegment(val line: Int, val start: Int, val end: Int)

/**
 * 把 [start, end) 的行内代码按行切段，丢掉空段和只含左右留白占位的段。
 * 断行器可能把左留白单独留在上一行末、或把右留白推到下一行首，这些段画出来只是一小块空底色。
 */
internal fun inlineCodeBackgroundSegments(
    text: AnnotatedString,
    start: Int,
    end: Int,
    firstLine: Int,
    lastLine: Int,
    lineStart: (Int) -> Int,
    lineEnd: (Int) -> Int,
): List<InlineCodeSegment> {
    if (start >= end) return emptyList()
    return (firstLine..lastLine).mapNotNull { line ->
        val from = maxOf(start, lineStart(line))
        val to = minOf(end, lineEnd(line))
        if (from >= to || readingInkLineText(text, from, to).isEmpty()) null
        else InlineCodeSegment(line, from, to)
    }
}

/**
 * 行内代码底色绘制：与上游圆角底色一致（每行一块，首尾两端圆角，上下各收 1sp），
 * 但跳过只剩留白占位的行段，首尾圆角落在实际保留的首尾两段上。
 */
internal class InlineCodeSpanPainter(
    private val cornerRadius: TextUnit,
    private val topMargin: TextUnit = 1.sp,
    private val bottomMargin: TextUnit = 1.sp,
) : ExtendedSpanPainter() {
    private val path = Path()

    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder): SpanStyle {
        if (span.background.isUnspecified) return span
        builder.addStringAnnotation(TAG, span.background.value.toString(), start, end)
        return span.copy(background = Color.Unspecified)
    }

    override fun decorate(
        linkAnnotation: LinkAnnotation,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): LinkAnnotation {
        val styles = linkAnnotation.styles ?: return linkAnnotation
        val background = styles.style?.background ?: return linkAnnotation
        if (background.isUnspecified) return linkAnnotation
        builder.addStringAnnotation(TAG, background.value.toString(), start, end)
        val cleared = TextLinkStyles(
            style = styles.style?.copy(background = Color.Unspecified),
            focusedStyle = styles.focusedStyle,
            hoveredStyle = styles.hoveredStyle,
            pressedStyle = styles.pressedStyle,
        )
        return when (linkAnnotation) {
            is LinkAnnotation.Url -> LinkAnnotation.Url(linkAnnotation.url, cleared, linkAnnotation.linkInteractionListener)
            is LinkAnnotation.Clickable -> LinkAnnotation.Clickable(linkAnnotation.tag, cleared, linkAnnotation.linkInteractionListener)
            else -> linkAnnotation
        }
    }

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val annotations = text.getStringAnnotations(TAG, 0, text.length)
        return SpanDrawInstructions {
            if (annotations.isEmpty()) return@SpanDrawInstructions
            val radius = CornerRadius(cornerRadius.toPx())
            val top = topMargin.toPx()
            val bottom = bottomMargin.toPx()
            annotations.forEach { annotation ->
                val background = Color(annotation.item.toULong())
                layoutResult.inlineCodeBoxes(annotation.start, annotation.end).let { boxes ->
                    boxes.forEachIndexed { index, box ->
                        path.rewind()
                        path.addRoundRect(
                            RoundRect(
                                rect = box.copy(top = box.top + top, bottom = box.bottom - bottom),
                                topLeft = if (index == 0) radius else CornerRadius.Zero,
                                bottomLeft = if (index == 0) radius else CornerRadius.Zero,
                                topRight = if (index == boxes.lastIndex) radius else CornerRadius.Zero,
                                bottomRight = if (index == boxes.lastIndex) radius else CornerRadius.Zero,
                            ),
                        )
                        drawPath(path, background, style = Fill)
                    }
                }
            }
        }
    }

    private fun TextLayoutResult.inlineCodeBoxes(start: Int, end: Int): List<Rect> {
        if (start >= end || end > layoutInput.text.length) return emptyList()
        val firstLine = getLineForOffset(start)
        val lastLine = getLineForOffset(end)
        // 整段恰为一个或多个完整段落时画成一整块，与上游一致。
        if (firstLine != lastLine && getLineStart(firstLine) == start &&
            multiParagraph.getLineEnd(lastLine, visibleEnd = true) == end
        ) {
            return listOf(Rect(0f, getLineTop(firstLine), size.width.toFloat(), getLineBottom(lastLine)))
        }
        val ltr = multiParagraph.getParagraphDirection(layoutInput.text.lastIndex) == ResolvedTextDirection.Ltr
        return inlineCodeBackgroundSegments(
            layoutInput.text, start, end, firstLine, lastLine,
            lineStart = ::getLineStart, lineEnd = { getLineEnd(it) },
        ).map { segment ->
            Rect(
                left = if (segment.start == getLineStart(segment.line)) getLineLeft(segment.line)
                else getHorizontalPosition(segment.start, usePrimaryDirection = ltr),
                top = getLineTop(segment.line),
                // 段尾恰在行尾时取行右沿：此时 end 也是下一行起点，按偏移取位置会落到下一行。
                right = if (segment.end < getLineEnd(segment.line)) getHorizontalPosition(segment.end, usePrimaryDirection = ltr)
                else getLineRight(segment.line),
                bottom = getLineBottom(segment.line),
            )
        }
    }

    private companion object {
        const val TAG = "infinity_inline_code_background"
    }
}
