package com.promenar.infinity.ui.common

import android.util.Log
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.webkit.WebView

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownExtendedSpans
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownRenderState
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText as MarkdownElementText
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.promenar.infinity.ui.renderer.isHtmlArtifact
import com.promenar.infinity.ui.renderer.isLikelyRenderableHtml
import com.promenar.infinity.ui.renderer.HtmlArtifactCard
import com.promenar.infinity.ui.renderer.HtmlArtifactsPopup
import com.promenar.infinity.ui.renderer.exportHtmlArtifactPng
import com.promenar.infinity.R
import com.promenar.infinity.ui.renderer.EChartsBlock
import com.promenar.infinity.ui.renderer.GfmAlertBlock
import com.promenar.infinity.ui.renderer.ImageLightbox
import com.promenar.infinity.ui.renderer.InlineLatexSpan
import com.promenar.infinity.ui.renderer.LatexBlock
import com.promenar.infinity.ui.renderer.MermaidBlock
import com.promenar.infinity.ui.renderer.InfinityTableWidget
import com.promenar.infinity.ui.renderer.PlantUmlBlock
import com.promenar.infinity.ui.renderer.infinityMarkdownColors
import com.promenar.infinity.ui.renderer.infinityMarkdownTypography
import com.promenar.infinity.ui.renderer.infinitySyntaxTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.PaddingValues
import com.promenar.infinity.ui.renderer.TableMeasurer
import com.promenar.infinity.ui.renderer.parseMarkdownTable
import com.promenar.infinity.ui.renderer.rememberTableMeasurer
import com.promenar.infinity.ui.renderer.parseGfmAlert
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityMonospaceTypeface
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.testing.UiTags
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlinx.coroutines.delay

private fun ASTNode.findLinkDestination(): ASTNode? {
    children.forEach { child ->
        if (child.type == MarkdownElementTypes.LINK_DESTINATION) return child
        child.findLinkDestination()?.let { return it }
    }
    return null
}

internal sealed class ContentSegment {
    data class Markdown(val content: String) : ContentSegment()
    data class Latex(val content: String) : ContentSegment()
    data class InlineLatex(val content: String) : ContentSegment()
    data class Mermaid(val content: String) : ContentSegment()
    data class ECharts(val content: String) : ContentSegment()
    data class PlantUml(val content: String) : ContentSegment()
}

/** 仅接管 AST 已确认是“单个 CODE_SPAN 子节点”的顶层段落，不自行重写 CommonMark 分隔符规则。 */
internal fun standaloneInlineCode(node: ASTNode, content: String): String? {
    if (node.type != MarkdownElementTypes.PARAGRAPH) return null
    val codeSpan = node.children.singleOrNull()
        ?.takeIf { it.type == MarkdownElementTypes.CODE_SPAN }
        ?: return null
    val opening = codeSpan.children.firstOrNull()
        ?.takeIf { it.type == MarkdownTokenTypes.BACKTICK }
        ?: return null
    val closing = codeSpan.children.lastOrNull()
        ?.takeIf { it.type == MarkdownTokenTypes.BACKTICK }
        ?: return null
    if (opening.endOffset > closing.startOffset) return null

    val rawCode = content.substring(opening.endOffset, closing.startOffset)
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
    return if (
        rawCode.length >= 2 &&
        rawCode.first() == ' ' &&
        rawCode.last() == ' ' &&
        rawCode.any { it != ' ' }
    ) {
        rawCode.substring(1, rawCode.lastIndex)
    } else {
        rawCode
    }
}

internal fun widestLogicalLineWidthPx(
    text: String,
    measureLine: (String) -> Int,
): Int = text.lineSequence().maxOfOrNull(measureLine) ?: 0

internal fun ASTNode.containsWideTableDescendant(): Boolean =
    type == GFMElementTypes.TABLE || children.any(ASTNode::containsWideTableDescendant)

internal fun ASTNode.containsWideContentDescendant(): Boolean =
    type == GFMElementTypes.TABLE || type == MarkdownElementTypes.CODE_FENCE || type == MarkdownElementTypes.CODE_BLOCK ||
        children.any(ASTNode::containsWideContentDescendant)

private fun nestedCodeSizingSample(content: String, root: ASTNode): Pair<String, Dp> {
    val samples = mutableListOf<String>()
    var maxDepth = 0
    fun visit(node: ASTNode, depth: Int) {
        if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK) {
            samples += content.substring(node.startOffset, node.endOffset)
            maxDepth = maxOf(maxDepth, depth)
            return
        }
        val nextDepth = depth + if (node.type == MarkdownElementTypes.BLOCK_QUOTE || node.type in readingListTypes) 1 else 0
        node.children.forEach { visit(it, nextDepth) }
    }
    visit(root, 0)
    return samples.joinToString("\n") to if (samples.isEmpty()) 0.dp else (32 + maxDepth * 48).dp
}

private fun nestedTableMinimumWidth(
    content: String,
    root: ASTNode,
    tableMeasurer: TableMeasurer,
    chromePerLevel: androidx.compose.ui.unit.Dp = 48.dp,
): androidx.compose.ui.unit.Dp? {
    fun visit(node: ASTNode, depth: Int): androidx.compose.ui.unit.Dp? {
        if (node.type == GFMElementTypes.TABLE) {
            return parseMarkdownTable(content, node)
                ?.let { tableMeasurer.layout(it).totalWidth }
                ?.plus(chromePerLevel * depth)
        }
        return node.children.mapNotNull { child -> visit(child, depth + 1) }.maxOrNull()
    }

    return visit(root, depth = 0)
}

internal fun stripBlockQuoteMarkers(text: String): String {
    return text.lineSequence()
        .map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                trimmed.removePrefix(">").let { if (it.startsWith(" ")) it.drop(1) else it }
            } else {
                line
            }
        }
        .joinToString("\n")
        .trim()
}

private data class PositionedContentSegment(val segment: ContentSegment, val start: Int, val end: Int) {
    val firstTextOffset = (segment as? ContentSegment.Markdown)?.content?.indexOfFirst { !it.isWhitespace() } ?: -1
}

internal fun splitRichSegments(text: String): List<ContentSegment> = splitPositionedRichSegments(text).map { it.segment }

private fun splitPositionedRichSegments(text: String): List<PositionedContentSegment> {
    val blockPattern = Regex(
        """(?s)```(mermaid|echarts|plantuml)\s*\n(.*?)\n```""",
        RegexOption.IGNORE_CASE
    )
    val latexPattern = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    // 闭合 `$` 后紧跟数字时视为货币金额（如 `$5/$10`），沿用 Pandoc 的 tex_math_dollars 约定。
    val inlineLatexPattern = Regex("""(?<![\\\$])\$(?!\$)(?!\s)([^\$\n]+?)(?<!\s)(?<!\\)\$(?!\$)(?!\d)""")
    // 行内代码、代码块与链接中的 `$` 是字面量，公式候选不得与其重叠。
    val literalRanges = if ('$' in text) markdownMathLiteralRanges(text) else emptyList()

    data class RichSpan(val start: Int, val end: Int, val segment: ContentSegment)

    val spans = mutableListOf<RichSpan>()

    for (m in blockPattern.findAll(text)) {
        val type = m.groupValues[1].lowercase()
        val content = m.groupValues[2].trim()
        val seg = when (type) {
            "mermaid" -> ContentSegment.Mermaid(content)
            "echarts" -> ContentSegment.ECharts(content)
            "plantuml" -> ContentSegment.PlantUml(content)
            else -> continue
        }
        spans.add(RichSpan(m.range.first, m.range.last + 1, seg))
    }

    for (m in latexPattern.findAllOutside(text, literalRanges)) {
        if (spans.none { it.start <= m.range.first && m.range.last < it.end }) {
            spans.add(RichSpan(m.range.first, m.range.last + 1,
                ContentSegment.Latex(m.groupValues[1].trim())))
        }
    }

    for (m in inlineLatexPattern.findAllOutside(text, literalRanges)) {
        if (spans.none { it.start <= m.range.first && m.range.last < it.end }) {
            spans.add(RichSpan(m.range.first, m.range.last + 1,
                ContentSegment.InlineLatex(m.groupValues[1].trim())))
        }
    }

    if (spans.isEmpty()) return listOf(PositionedContentSegment(ContentSegment.Markdown(text), 0, text.length))

    spans.sortBy { it.start }

    val result = mutableListOf<PositionedContentSegment>()
    var cursor = 0
    for (span in spans) {
        if (span.start > cursor) {
            result.add(PositionedContentSegment(ContentSegment.Markdown(text.substring(cursor, span.start)), cursor, span.start))
        }
        result.add(PositionedContentSegment(span.segment, span.start, span.end))
        cursor = span.end
    }
    if (cursor < text.length) {
        result.add(PositionedContentSegment(ContentSegment.Markdown(text.substring(cursor)), cursor, text.length))
    }
    return result
}

internal fun replaceCodeInMarkdown(
    markdown: String,
    language: String?,
    oldCode: String,
    newCode: String
): String {
    val lang = language ?: ""
    val oldFence = "```$lang\n$oldCode\n```"
    val newFence = "```$lang\n$newCode\n```"
    val idx = markdown.indexOf(oldFence)
    if (idx >= 0) {
        return markdown.substring(0, idx) + newFence + markdown.substring(idx + oldFence.length)
    }
    val oldFenceAlt = "```${lang.trim()}\n$oldCode\n```"
    val idxAlt = markdown.indexOf(oldFenceAlt)
    if (idxAlt >= 0) {
        return markdown.substring(0, idxAlt) + newFence + markdown.substring(idxAlt + oldFenceAlt.length)
    }
    return markdown
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    showCursor: Boolean = true,
    fontSize: Int = 15,
    overrideColor: androidx.compose.ui.graphics.Color? = null,
    onContentChange: ((String) -> Unit)? = null,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    compactSpacing: Boolean = false
) {
    val prepared = remember(markdown, isStreaming) {
        PreparedMarkdownContent(markdown, prepareMarkdownForRendering(markdown, isStreaming))
    }
    val processed = prepared.text
    // 放出器始终比较原始累计文本；临时闭合与空白修复只影响解析视图和来源映射。
    val rawReveal = rememberSmoothStreamContent(content = markdown, isStreaming = isStreaming)
    val reveal = remember(prepared, rawReveal) { prepared.reveal(rawReveal) }
    // 只在上游文本变化时划分富媒体片段，帧更新只改变可见终点。
    val mergedSegments = remember(processed) { splitPositionedRichSegments(processed) }
    val visibleSegments = mergedSegments.filter { positioned ->
        when (val segment = positioned.segment) {
            is ContentSegment.Markdown -> positioned.firstTextOffset >= 0 && reveal.text.length > positioned.start + positioned.firstTextOffset
            else -> reveal.text.length >= positioned.end
        }
    }

    val currentStyle = LocalTextStyle.current
    val effectiveColor = overrideColor
        ?: currentStyle.color.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurface
    val m3Typography = MaterialTheme.typography.copy(
        bodyMedium = infinityMarkdownTypography(fontSize).text.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineLarge = infinityMarkdownTypography(fontSize).h1.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineMedium = infinityMarkdownTypography(fontSize).h2.copy(color = effectiveColor, fontStyle = fontStyle),
        headlineSmall = infinityMarkdownTypography(fontSize).h3.copy(color = effectiveColor, fontStyle = fontStyle),
        titleLarge = infinityMarkdownTypography(fontSize).h1.copy(color = effectiveColor, fontStyle = fontStyle),
        titleMedium = infinityMarkdownTypography(fontSize).h2.copy(color = effectiveColor, fontStyle = fontStyle),
        titleSmall = infinityMarkdownTypography(fontSize).h3.copy(color = effectiveColor, fontStyle = fontStyle),
        bodySmall = infinityMarkdownTypography(fontSize).code.copy(color = effectiveColor, fontStyle = fontStyle),
        labelSmall = InfinityTypography.labelSmall.copy(
            fontSize = 11.sp,
            color = effectiveColor.copy(alpha = 0.7f),
            fontStyle = fontStyle
        )
    )

    // 正文内工作区链接点开媒体查看器；未提供工作区环境时链接保持原处理。
    val workspaceMedia = com.promenar.infinity.ui.media.LocalWorkspaceMedia.current
    val baseUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var openedWorkspaceLink by remember { mutableStateOf<String?>(null) }
    val uriHandler = remember(baseUriHandler, workspaceMedia) {
        if (workspaceMedia == null) baseUriHandler else WorkspaceLinkUriHandler(baseUriHandler) { openedWorkspaceLink = it }
    }
    openedWorkspaceLink?.let { path ->
        com.promenar.infinity.ui.media.WorkspaceMediaViewer(path = path, onDismiss = { openedWorkspaceLink = null })
    }

    MaterialTheme(typography = m3Typography) {
        CompositionLocalProvider(
            LocalTextStyle provides m3Typography.bodyMedium,
            androidx.compose.material3.LocalContentColor provides effectiveColor,
            LocalImageTransformer provides InfinityMarkdownImageTransformer,
            androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
        ) {
            Column(
                modifier = modifier.fillMaxWidth(),
            ) {
                for ((index, positioned) in visibleSegments.withIndex()) {
                    if (index > 0) Spacer(Modifier.height(if (compactSpacing) 4.dp else 16.dp))
                    when (val segment = positioned.segment) {
                        is ContentSegment.Markdown -> MarkdownSafe(
                            content = segment.content,
                            visibleLength = (reveal.text.length - positioned.start).coerceIn(0, segment.content.length),
                            reveal = reveal.takeIf { positioned == visibleSegments.lastOrNull() },
                            sourceOffset = positioned.start,
                            streaming = isStreaming,
                            highlightStreaming = isStreaming || rawReveal.text != markdown,
                            fontSize = fontSize,
                            markdown = markdown,
                            onContentChange = onContentChange,
                            textColor = effectiveColor,
                            fontStyle = fontStyle,
                            compactSpacing = compactSpacing,
                        )
                        is ContentSegment.Latex -> LatexBlock(latex = segment.content, fontSize = fontSize)
                        is ContentSegment.InlineLatex -> InlineLatexSpan(latex = segment.content, fontSize = fontSize)
                        is ContentSegment.Mermaid -> MermaidBlock(code = segment.content, fontSize = fontSize)
                        is ContentSegment.ECharts -> EChartsBlock(optionJson = segment.content, fontSize = fontSize)
                        is ContentSegment.PlantUml -> PlantUmlBlock(code = segment.content, fontSize = fontSize)
                    }
                }

                if (isStreaming && showCursor) {
                    GenerationDots(Modifier.padding(start = 2.dp, top = 6.dp), dotSize = 5.dp, gap = 4.dp, lift = 3.dp)
                }
            }
        }
    }
}

internal const val INLINE_CODE_PADDING_ID = "infinity-inline-code-padding"

/**
 * 行内代码内边距占位的替代文本。默认的 U+FFFD 会进入无障碍文本被读屏念成替换字符；
 * 不换行空格只被当作空白，且与 U+FFFD 一样不在占位与代码之间产生断行机会。
 */
internal const val INLINE_CODE_PADDING_ALTERNATE_TEXT = "\u00A0"
private val revealLiteralTypes = setOf(
    MarkdownTokenTypes.TEXT, MarkdownTokenTypes.SINGLE_QUOTE, MarkdownTokenTypes.DOUBLE_QUOTE,
    MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN, MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET,
    MarkdownTokenTypes.LT, MarkdownTokenTypes.GT, MarkdownTokenTypes.COLON, MarkdownTokenTypes.EXCLAMATION_MARK,
    MarkdownTokenTypes.BACKTICK, GFMTokenTypes.DOLLAR,
)

private val readingHeadingTypes = setOf(
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
)
private val readingListTypes = setOf(MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.UNORDERED_LIST)
private val readingMediaTypes = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.BLOCK_QUOTE, GFMElementTypes.TABLE)

internal fun markdownReadingGap(previous: IElementType, next: IElementType, compact: Boolean = false): Dp {
    if (compact) return 4.dp
    val gap = when {
        next in readingHeadingTypes -> 24
        previous in readingMediaTypes || next in readingMediaTypes -> 16
        previous in readingListTypes || next in readingListTypes -> 12
        previous in readingHeadingTypes -> 10
        else -> 16
    }
    // 列表每项上下各有 5dp；边界扣除首末项已有的内边距。
    return (gap - (if (previous in readingListTypes) 5 else 0) - (if (next in readingListTypes) 5 else 0)).dp
}

private val referenceLinkTypes = setOf(MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK)

/** 保留已解析节点，只有跨越可见终点的分支建立轻量视图，不重新运行 MarkdownParser。 */
internal fun visibleMarkdownNode(node: ASTNode, end: Int): ASTNode {
    if (node.endOffset <= end) return node
    // 渲染库按“首尾为方括号”截取引用标签，截断后会越界；未放完的引用链接按已放出的纯文本显示。
    if (node.type in referenceLinkTypes) return LeafASTNode(MarkdownTokenTypes.TEXT, node.startOffset, end)
    return object : ASTNode {
        override val type = node.type
        override val startOffset = node.startOffset.coerceAtMost(end)
        override val endOffset = node.endOffset.coerceAtMost(end)
        override val parent = node.parent
        override val children: List<ASTNode> by lazy {
            val visible = node.children.filter {
                val needsCompleteNode = it.type == MarkdownElementTypes.IMAGE ||
                    (type == MarkdownElementTypes.INLINE_LINK && it.type == MarkdownElementTypes.LINK_DESTINATION)
                it.startOffset < end && (!needsCompleteNode || it.endOffset <= end)
            }.map { visibleMarkdownNode(it, end) }
            when {
                type == MarkdownElementTypes.CODE_FENCE && visible.lastOrNull()?.type != MarkdownTokenTypes.CODE_FENCE_END ->
                    visible + LeafASTNode(MarkdownTokenTypes.CODE_FENCE_END, endOffset, endOffset)
                type == MarkdownElementTypes.CODE_SPAN && visible.lastOrNull()?.type != MarkdownTokenTypes.BACKTICK ->
                    visible + LeafASTNode(MarkdownTokenTypes.BACKTICK, endOffset, endOffset)
                type == MarkdownElementTypes.LINK_TEXT && visible.lastOrNull()?.type != MarkdownTokenTypes.RBRACKET ->
                    visible + LeafASTNode(MarkdownTokenTypes.RBRACKET, endOffset, endOffset)
                else -> visible
            }
        }
    }
}

/** 新字区间的显示样式：[alpha] 为不透明度系数，[tint] 为混入强调色的比例。 */
internal data class ReadingRevealRange(val start: Int, val end: Int, val alpha: Float, val tint: Float = 0f)
internal data class ReadingMarkdownAnnotators(val base: MarkdownAnnotator, val tail: MarkdownAnnotator)

@Composable
internal fun rememberReadingMarkdownAnnotators(
    inlineCodeStyle: SpanStyle,
    textColor: Color,
    sourceOffset: Int,
    reveal: StreamRevealFrame?,
    accent: Color = textColor,
): ReadingMarkdownAnnotators {
    val ranges = reveal?.recentRanges?.map {
        val age = reveal.nowNanos - it.revealedAtNanos
        ReadingRevealRange(it.start, it.end, streamRevealAlpha(age), streamRevealTint(age))
    }.orEmpty()
    val base = remember(inlineCodeStyle, textColor) { readingMarkdownAnnotator(inlineCodeStyle, textColor, emptyList(), 0) }
    val tail = remember(base, ranges, sourceOffset) {
        if (ranges.isEmpty()) base else readingMarkdownAnnotator(inlineCodeStyle, textColor, ranges, sourceOffset, accent)
    }
    return ReadingMarkdownAnnotators(base, tail)
}

internal fun readingMarkdownAnnotator(
    inlineCodeStyle: SpanStyle,
    textColor: Color,
    ranges: List<ReadingRevealRange>,
    sourceOffset: Int,
    accent: Color = textColor,
): MarkdownAnnotator = markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) { source, node ->
    when {
        node.type == MarkdownElementTypes.CODE_SPAN -> {
            pushStyle(inlineCodeStyle)
            appendInlineContent(INLINE_CODE_PADDING_ID, INLINE_CODE_PADDING_ALTERNATE_TEXT)
            node.children.drop(1).dropLast(1).forEach { child -> appendRevealedLeaf(source, child, ranges, sourceOffset, textColor, accent) }
            appendInlineContent(INLINE_CODE_PADDING_ID, INLINE_CODE_PADDING_ALTERNATE_TEXT)
            pop()
            true
        }
        node.children.isEmpty() && node.type in revealLiteralTypes -> {
            appendRevealedLeaf(source, node, ranges, sourceOffset, textColor, accent)
            true
        }
        // 真正的公式已在分段时拆为 InlineLatex；GFM 仍会把 `$5 and $` 等货币片段解析为行内数学节点，
        // 渲染库对该节点不输出任何文字，这里按原文逐个叶子显示。
        node.type == GFMElementTypes.INLINE_MATH -> {
            fun appendLiteral(child: ASTNode) {
                if (child.children.isEmpty()) {
                    appendRevealedLeaf(source, child, ranges, sourceOffset, textColor, accent)
                } else {
                    child.children.forEach(::appendLiteral)
                }
            }
            node.children.forEach(::appendLiteral)
            true
        }
        else -> false
    }
}

private fun AnnotatedString.Builder.appendRevealedLeaf(
    source: String,
    node: ASTNode,
    ranges: List<ReadingRevealRange>,
    sourceOffset: Int,
    textColor: Color,
    accent: Color,
) {
    val start = length
    val text = node.getUnescapedTextInNode(source)
    append(text)
    ranges.forEach { range ->
        val overlapStart = maxOf(node.startOffset, range.start - sourceOffset)
        val overlapEnd = minOf(node.endOffset, range.end - sourceOffset)
        if (overlapStart < overlapEnd) {
            fun renderedOffset(offset: Int): Int = if (text.length == node.endOffset - node.startOffset) {
                offset - node.startOffset
            } else {
                LeafASTNode(node.type, node.startOffset, offset).getUnescapedTextInNode(source).length.coerceIn(0, text.length)
            }
            val from = start + renderedOffset(overlapStart)
            val to = start + renderedOffset(overlapEnd)
            if (from < to) {
                val color = lerp(textColor, accent.copy(alpha = textColor.alpha), range.tint)
                addStyle(SpanStyle(color = color.copy(alpha = color.alpha * range.alpha)), from, to)
            }
        }
    }
}

internal data class ReadingInkInsets(val top: Int, val bottom: Int)

internal fun readingInkInsets(height: Int, firstInkTop: Float, lastInkBottom: Float): ReadingInkInsets {
    val top = kotlin.math.floor(firstInkTop).toInt().coerceIn(0, height)
    val bottom = (height - kotlin.math.ceil(lastInkBottom).toInt()).coerceIn(0, height - top)
    return ReadingInkInsets(top, bottom)
}

internal fun readingInkLineText(text: AnnotatedString, start: Int, end: Int): String {
    val placeholders = text.getStringAnnotations(start, end)
        .filter { it.item == INLINE_CODE_PADDING_ID }
        .sortedBy { it.start }
    if (placeholders.isEmpty()) return text.text.substring(start, end)
    return buildString {
        var cursor = start
        placeholders.forEach { placeholder ->
            val from = placeholder.start.coerceIn(start, end)
            val to = placeholder.end.coerceIn(start, end)
            if (from > cursor) append(text.text, cursor, from)
            cursor = maxOf(cursor, to)
        }
        if (cursor < end) append(text.text, cursor, end)
    }
}

private data class ReadingTextEdges(val modifier: Modifier, val onTextLayout: (TextLayoutResult) -> Unit)

@Composable
private fun rememberReadingTextEdges(style: TextStyle): ReadingTextEdges {
    val density = LocalDensity.current
    val context = LocalContext.current
    val insets = remember(style, density.density, density.fontScale) { mutableStateOf(ReadingInkInsets(0, 0)) }
    val paint = remember(style, density.density, density.fontScale) {
        val family = if (style.fontFamily == InfinityMonospace) infinityMonospaceTypeface(context) else Typeface.SANS_SERIF
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = with(density) { style.fontSize.toPx() }
            typeface = if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(family, (style.fontWeight ?: FontWeight.Normal).weight, style.fontStyle == FontStyle.Italic)
            } else Typeface.create(family, if ((style.fontWeight ?: FontWeight.Normal).weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    return remember(paint, insets) {
        ReadingTextEdges(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val top = insets.value.top.coerceAtMost(placeable.height)
                val bottom = insets.value.bottom.coerceAtMost(placeable.height - top)
                val baselines = mutableMapOf<androidx.compose.ui.layout.AlignmentLine, Int>()
                for (line in listOf(FirstBaseline, LastBaseline)) {
                    val value = placeable[line]
                    if (value != androidx.compose.ui.layout.AlignmentLine.Unspecified) baselines[line] = value - top
                }
                layout(placeable.width, placeable.height - top - bottom, baselines) { placeable.placeRelative(0, -top) }
            },
            onTextLayout = { result ->
                if (result.lineCount > 0) {
                    val text = result.layoutInput.text
                    fun bounds(line: Int): Rect {
                        val value = readingInkLineText(text, result.getLineStart(line), result.getLineEnd(line, visibleEnd = true))
                        return Rect().also { paint.getTextBounds(value, 0, value.length, it) }
                    }
                    val first = bounds(0)
                    val last = bounds(result.lineCount - 1)
                    if (!first.isEmpty && !last.isEmpty) {
                        insets.value = readingInkInsets(result.size.height, result.firstBaseline + first.top, result.lastBaseline + last.bottom)
                    }
                }
            },
        )
    }
}

@Composable
private fun ReadingMarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val settings = annotatorSettings()
    val childNode = contentChildType?.let { node.findChildOfType(it) } ?: node
    val styled = remember(content, childNode, settings, style) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content, childNode, settings)
            pop()
        }
    }
    val edges = rememberReadingTextEdges(style)
    val hasImage = remember(node) {
        fun containsImage(current: ASTNode): Boolean = current.type == MarkdownElementTypes.IMAGE || current.children.any(::containsImage)
        containsImage(node)
    }
    MarkdownElementText(
        content = styled,
        node = node,
        modifier = modifier.then(if (hasImage) Modifier else edges.modifier),
        style = style,
        onTextLayout = if (hasImage) null else { result, _ -> edges.onTextLayout(result) },
        sourceContent = content,
        extendedSpans = LocalMarkdownExtendedSpans.current.extendedSpans?.invoke(),
    )
}

@Composable
private fun ReadingMarkdownList(model: MarkdownComponentModel, ordered: Boolean) {
    val orderedHandler = LocalOrderedListHandler.current
    val bulletHandler = LocalBulletListHandler.current
    val depth = model.listDepth
    val style = if (ordered) model.typography.ordered else model.typography.bullet
    val padding = LocalMarkdownPadding.current
    val unindented = remember(padding) {
        if (padding.listIndent == 0.dp) padding else object : MarkdownPadding by padding {
            override val listIndent = 0.dp
        }
    }
    CompositionLocalProvider(LocalMarkdownPadding provides unindented) {
        // 嵌套列表借用末项底部留白补齐父子间距；只移动位置，不增加测量高度。
        Box(Modifier.offset(y = if (depth > 0) padding.listItemBottom else 0.dp)) {
            MarkdownListItems(
                content = model.content,
                node = model.node,
                depth = depth,
                markerModifier = { Modifier.widthIn(min = 16.dp).alignByBaseline() },
                listModifier = { Modifier.alignByBaseline() },
            ) { index, listNumber, child ->
                val marker = if (ordered) {
                    orderedHandler.transform(MarkdownTokenTypes.LIST_NUMBER, child?.getUnescapedTextInNode(model.content), index, listNumber, depth)
                } else {
                    bulletHandler.transform(MarkdownTokenTypes.LIST_BULLET, child?.getUnescapedTextInNode(model.content), index, listNumber, depth)
                }
                val edges = rememberReadingTextEdges(style)
                Text(marker.trimEnd(), modifier = edges.modifier, style = style, onTextLayout = edges.onTextLayout)
            }
        }
    }
}

@Composable
private fun ReadingBlockQuote(model: MarkdownComponentModel, compactSpacing: Boolean) {
    val color = MaterialTheme.colorScheme.outlineVariant
    val description = LocalMarkdownA11yLabels.current.blockquote
    val components = LocalMarkdownComponents.current
    val blocks = model.node.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.BLOCK_QUOTE && it.type != MarkdownTokenTypes.WHITE_SPACE }
    Box(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Box(Modifier.matchParentSize()) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        }
        Column(Modifier.fillMaxWidth().padding(start = 15.dp)) {
            blocks.forEachIndexed { index, node ->
                if (index > 0) Spacer(Modifier.height(markdownReadingGap(blocks[index - 1].type, node.type, compactSpacing)))
                MarkdownElement(node, components, model.content, includeSpacer = false)
            }
        }
    }
}

@Composable
private fun ReadingCodeBlock(
    code: String,
    language: String?,
    fontSize: Int,
    onCodeChange: ((String) -> Unit)?,
    codeContent: @Composable () -> Unit,
) {
    val haptics = LocalInfinityHaptics.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var editedCode by remember(code) { mutableStateOf(code) }
    var copied by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val isRenderableHtml = remember(language, code) { isHtmlArtifact(language) && isLikelyRenderableHtml(code) }
    val languageName = language?.takeIf(String::isNotBlank) ?: stringResource(R.string.code_block_plain_text)
    val copyDescription = stringResource(if (copied) R.string.code_block_copied else R.string.code_block_copy)
    val editDescription = stringResource(if (editing) R.string.code_block_save else R.string.code_block_edit)
    val fullScreenDescription = stringResource(R.string.code_block_full_screen)
    val exportDescription = stringResource(R.string.code_block_export_png)
    val lineCount = code.lines().size
    val blockDescription = pluralStringResource(R.plurals.code_block_description, lineCount, languageName, lineCount)
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .semantics { contentDescription = blockDescription },
    ) {
        Box(Modifier.fillMaxWidth().height(36.dp).testTag("CHAT_MARKDOWN_CODE_HEADER")) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.Center).wrapContentHeight(unbounded = true).height(48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(languageName, Modifier.weight(1f), style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!editing && isRenderableHtml) {
                IconButton(onClick = { fullScreen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Fullscreen, fullScreenDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { webView?.let { exportHtmlArtifactPng(it, context) } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Download, exportDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onCodeChange != null) IconButton(
                onClick = { if (editing) onCodeChange(editedCode); editing = !editing },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, editDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true; haptics.perform(InfinityHapticEvent.CONFIRM) }, modifier = Modifier.size(48.dp)) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, copyDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
        if (editing) {
            OutlinedTextField(
                value = editedCode,
                onValueChange = { editedCode = it },
                textStyle = LocalMarkdownTypography.current.code.forInput(),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        } else Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).testTag("CHAT_MARKDOWN_CODE_BODY")) { codeContent() }
        if (!editing && isRenderableHtml) {
            HtmlArtifactCard(code, language, fontSize = fontSize, onWebViewCreated = { webView = it }, modifier = Modifier.padding(8.dp))
        }
    }
    if (fullScreen) HtmlArtifactsPopup(code, fontSize = fontSize, onDismiss = { fullScreen = false })
}

/** 第一方宿主负责横向滚动，代码正文只绘制文字，墨迹两侧由容器内边距保护。 */
@Composable
private fun ReadingHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: dev.snipme.highlights.Highlights.Builder,
    streaming: Boolean,
) {
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    fun highlight(source: String): AnnotatedString = synchronized(highlightsBuilder) {
        val syntax = language?.let(dev.snipme.highlights.model.SyntaxLanguage::getByName)
        val builder = highlightsBuilder.code(source)
        if (syntax != null) builder.language(syntax)
        val highlights = builder.build().getHighlights()
        buildAnnotatedString {
            append(source)
            highlights.forEach { highlight ->
                val span = when (highlight) {
                    is dev.snipme.highlights.model.ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                    is dev.snipme.highlights.model.BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                addStyle(span, highlight.location.start, highlight.location.end)
            }
        }
    }
    val highlighted = if (inspection) {
        remember(code, language, highlightsBuilder) { highlight(code) }
    } else {
        val currentRequest = rememberUpdatedState(CodeHighlightRequest(code, streaming))
        val result by androidx.compose.runtime.produceState<AnnotatedString?>(null, language, highlightsBuilder) {
            collectCodeHighlights(
                requests = androidx.compose.runtime.snapshotFlow { currentRequest.value },
                nowMillis = { System.nanoTime() / 1_000_000L },
                highlight = { source -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { highlight(source) } },
                onHighlighted = { value = it },
            )
        }
        result
    }
    val visibleCode = remember(code, highlighted) { highlightedCodeWithUnstyledTail(code, highlighted) }
    val edges = rememberReadingTextEdges(style)
    Text(visibleCode, style = style, softWrap = false, modifier = edges.modifier.testTag("CHAT_MARKDOWN_CODE_TEXT"), onTextLayout = edges.onTextLayout)
}

internal val markdownCodeIndent = Regex("^(?: {4}| {0,3}\\t)")

internal fun safeTrimIndent(text: String): String {
    if (text.lines().any { markdownCodeIndent.containsMatchIn(it) && it.trimStart().isNotEmpty() }) {
        return text
    }
    return text.trimIndent()
}

internal fun prepareMarkdownForRendering(text: String, isStreaming: Boolean = false): String {
    val normalized = normalizeLatexDelimiters(text)
    val raw = if (isStreaming) sanitizeStreamingMarkdown(normalized) else normalized
    return collapseMarkdownBlankLines(repairCompressedMarkdownBoundaries(safeTrimIndent(raw)))
}

internal fun repairCompressedMarkdownBoundaries(text: String): String {
    if (text.isBlank()) return text

    val protectedMap = mutableMapOf<String, String>()
    var counter = 0

    fun protect(pattern: Regex, input: String): String =
        pattern.replace(input) { match ->
            val key = "\u0000MD${counter++}\u0000"
            protectedMap[key] = match.value
            key
        }

    val protectedTypes = setOf(
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.IMAGE,
    )
    val protectedNodes = mutableListOf<ASTNode>()
    fun collectProtectedNodes(node: ASTNode) {
        if (node.type in protectedTypes) {
            protectedNodes += node
        } else {
            node.children.forEach(::collectProtectedNodes)
        }
    }

    // 由解析器确定边界，保护多反引号、围栏、缩进代码和带嵌套括号的链接。
    val hasProtectedSyntax = text.any { it == '`' || it == '~' || it == '[' || it == '<' } ||
        text.lineSequence().any { markdownCodeIndent.containsMatchIn(it) }
    if (hasProtectedSyntax) {
        collectProtectedNodes(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
    }
    var processed = StringBuilder(text).apply {
        protectedNodes.asReversed().forEach { node ->
            val key = "\u0000MD${counter++}\u0000"
            protectedMap[key] = text.substring(node.startOffset, node.endOffset)
            replace(node.startOffset, node.endOffset, key)
        }
    }.toString()
    // 公式作为整体保护，避免后续边界修复改变公式内部的 Markdown 字符。
    processed = protect(Regex("""\$\$[\s\S]*?\$\$|\$[^\$\n]+\$"""), processed)

    processed = processed.replace(Regex("""[ \t]+---(?=#{1,6}(?!#))""")) {
        "\n\n---\n\n"
    }

    // 只移除本行未配对的 `**`；`**3.标题**` 这类成对加粗属于正常强调。代码与链接已替换为占位符，不参与计数。
    processed = processed.replace(Regex("""(?m)(^|[^\S\n])\*\*(?=\d{1,2}\.(?!\d)[^\s])""")) { match ->
        val lineStart = processed.lastIndexOf('\n', match.range.first) + 1
        val lineEnd = processed.indexOf('\n', match.range.last).let { if (it < 0) processed.length else it }
        val boldMarkers = boldMarkerPattern.findAll(processed.substring(lineStart, lineEnd)).count()
        if (boldMarkers % 2 == 0) match.value else match.groupValues[1]
    }

    // 以竖线开头的行是表格行：单元格里的 `#`、编号和 `>` 是内容，拆行会破坏整张表格。
    fun inTableRow(index: Int): Boolean =
        processed.substring(processed.lastIndexOf('\n', index - 1) + 1, index).trimStart().startsWith('|')

    processed = processed.replace(Regex("""(?m)(^|(?<=[^\nA-Za-z0-9#]))[ \t]*(#{1,6})(?!#)[ \t]*([^\s#])""")) { match ->
        if (inTableRow(match.range.first)) return@replace match.value
        val needsBreak = match.range.first > 0 && processed[match.range.first - 1] != '\n'
        val prefix = if (needsBreak) "\n\n" else ""
        "$prefix${match.groupValues[2]} ${match.groupValues[3]}"
    }

    processed = processed.replace(Regex("""(?m)(^|(?<=[^\nA-Za-z0-9.]))[ \t]*(\d{1,2})\.(?!\d)[ \t]*([^\s])""")) { match ->
        // 编号前只有行首强调标记（可带列表或引用前缀）时是加粗的编号标题，拆行会让强调标记裸露。
        val linePrefix = processed.substring(processed.lastIndexOf('\n', match.range.first - 1) + 1, match.range.first)
        if (emphasizedLineStartPattern.matches(linePrefix) || inTableRow(match.range.first)) return@replace match.value
        val needsBreak = match.range.first > 0 && processed[match.range.first - 1] != '\n'
        val prefix = if (needsBreak) "\n" else ""
        "$prefix${match.groupValues[2]}. ${match.groupValues[3]}"
    }

    // 冒号后至少两个同类标记才提供压缩列表的结构证据；普通句、算式和已有列表保持原文。
    val compressedBulletList = Regex("""(?m)^([^:：\r\n]+[:：])[ \t]+([-*+])[ \t]+\S[^\r\n]*""")
    val bulletMarker = Regex("""(?<!\S)([-*+])[ \t]+(?=\S)""")
    processed = processed.replace(compressedBulletList) { list ->
        val line = list.value
        val prefix = list.groupValues[1]
        val body = line.substring(prefix.length).trimStart(' ', '\t')
        val markers = bulletMarker.findAll(body).toList()
        if (markers.size < 2 || markers.any { it.groupValues[1] != list.groupValues[2] }) {
            return@replace line
        }
        val items = markers.mapIndexed { index, marker ->
            val end = markers.getOrNull(index + 1)?.range?.first ?: body.length
            body.substring(marker.range.last + 1, end).trimEnd(' ', '\t')
        }
        if (items.any { it.isEmpty() || it.first().isDigit() || it.matches(Regex("""[\d.]+|[A-Za-z]""")) }) {
            return@replace line
        }
        prefix + items.joinToString(separator = "\n", prefix = "\n") { "${list.groupValues[2]} $it" }
    }

    processed = processed.replace(Regex("""```(javascript|js|typescript|ts|kotlin|java|python|swift|go|rust|json|html|css)(?=(function|fun|class|const|let|var|import|def|public|private|val|return|\{|\[))""")) { match ->
        "```${match.groupValues[1]}\n"
    }

    processed = processed.replace(Regex("""(#{1,6} [^|\n]+)(\|)""")) { match ->
        "${match.groupValues[1]}\n${match.groupValues[2]}"
    }
    if (processed.contains(Regex("""\|\|[-:\s]*\|""")) || processed.contains(Regex("""\|\|[^|\n]+\|"""))) {
        processed = processed.replace("||", "|\n|")
        processed = processed.lines().joinToString("\n") { line ->
            if (line.startsWith("|") && line.count { it == '|' } >= 2 && !line.endsWith("|")) {
                "$line|"
            } else {
                line
            }
        }
    }

    processed = processed.replace(Regex("""(?m)(?<![\n<>=-])[ \t]*(>)[ \t]*([^\s])""")) { match ->
        if (inTableRow(match.range.first)) return@replace match.value
        val prefix = if (match.range.first > 0) "\n" else ""
        "$prefix> ${match.groupValues[2]}"
    }

    for ((key, value) in protectedMap.entries.toList().asReversed()) {
        processed = processed.replace(key, value)
    }

    return processed
}

private val boldMarkerPattern = Regex("""(?<!\*)\*\*(?!\*)""")
private val emphasizedLineStartPattern = Regex("""[ \t]*(?:(?:[-+*>]|\d{1,2}[.)])[ \t]+)*[*_]{1,3}""")

/** 同步解析的片段长度上限；更长的历史片段仍在后台解析。 */
private const val SYNC_MARKDOWN_PARSE_MAX_CHARS = 12_000

@Composable
private fun MarkdownSafe(
    content: String,
    visibleLength: Int = content.length,
    reveal: StreamRevealFrame? = null,
    sourceOffset: Int = 0,
    streaming: Boolean = false,
    highlightStreaming: Boolean = streaming,
    fontSize: Int,
    markdown: String,
    onContentChange: ((String) -> Unit)?,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    compactSpacing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var renderError by remember(content) { mutableStateOf(false) }
    // 使用 rememberUpdatedState 避免回调变化导致 components 重建
    val currentMarkdown = rememberUpdatedState(markdown)
    val currentOnContentChange = rememberUpdatedState(onContentChange)
    val currentStreaming = rememberUpdatedState(highlightStreaming)

    if (renderError) {
        Text(
            text = content.take(visibleLength),
            modifier = modifier.fillMaxWidth(),
            style = LocalTextStyle.current
        )
        return
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlightsBuilder = remember(isDark) {
        dev.snipme.highlights.Highlights.Builder().theme(infinitySyntaxTheme(isDark))
    }

    val components = remember(fontSize, fontStyle, highlightsBuilder, compactSpacing) {
        markdownComponents(
            paragraph = { model -> ReadingMarkdownText(model.content, model.node, model.typography.paragraph) },
            orderedList = { model -> ReadingMarkdownList(model, ordered = true) },
            unorderedList = { model -> ReadingMarkdownList(model, ordered = false) },
            heading1 = anchoredHeading({ it.typography.h1 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            heading2 = anchoredHeading({ it.typography.h2 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            heading3 = anchoredHeading({ it.typography.h3 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            heading4 = anchoredHeading({ it.typography.h4 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            heading5 = anchoredHeading({ it.typography.h5 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            heading6 = anchoredHeading({ it.typography.h6 }, MarkdownTokenTypes.ATX_CONTENT, compactSpacing),
            setextHeading1 = anchoredHeading({ it.typography.h1 }, MarkdownTokenTypes.SETEXT_CONTENT, compactSpacing),
            setextHeading2 = anchoredHeading({ it.typography.h2 }, MarkdownTokenTypes.SETEXT_CONTENT, compactSpacing),
            blockQuote = { model ->
                val rawText = model.node.getUnescapedTextInNode(model.content)
                val stripped = stripBlockQuoteMarkers(rawText)
                if (parseGfmAlert(stripped) != null) {
                    GfmAlertBlock(
                        quoteContent = stripped,
                        fontSize = fontSize
                    )
                } else {
                    ReadingBlockQuote(model, compactSpacing)
                }
            },
            image = { model ->
                val link = model.node.findLinkDestination()
                    ?.getUnescapedTextInNode(model.content)

                val imageTarget = link?.let { com.promenar.infinity.ui.media.classifyMarkdownImageTarget(it, rememberLegacyGeneratedRoots()) }
                if (imageTarget is com.promenar.infinity.ui.media.MarkdownImageTarget.Workspace) {
                    // 渲染库把段落内的工作区图片提升为块级元素后交到这里，按媒体类别绘制卡片。
                    com.promenar.infinity.ui.media.WorkspaceMediaReference(
                        path = imageTarget.path,
                        alt = model.node.findChildOfType(MarkdownElementTypes.INLINE_LINK)
                            ?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                            ?.getUnescapedTextInNode(model.content)
                            ?.removePrefix("[")?.removeSuffix("]")?.trim(),
                    )
                } else if (link != null && imageTarget != com.promenar.infinity.ui.media.MarkdownImageTarget.Blocked) {
                    var showLightbox by remember(link) { mutableStateOf(false) }
                    val imageData = LocalImageTransformer.current.transform(link)
                    if (imageData != null) {
                        val press = remember { MutableInteractionSource() }
                        Image(
                            painter = imageData.painter,
                            contentDescription = imageData.contentDescription,
                            // 图片本身没有容器，按下时整体变暗，不在图上叠圆角状态层。
                            modifier = Modifier
                                .fillMaxWidth()
                                .dimOnPress(press)
                                .clickable(interactionSource = press, indication = null) { showLightbox = true },
                            alignment = imageData.alignment,
                            contentScale = imageData.contentScale,
                            alpha = imageData.alpha,
                            colorFilter = imageData.colorFilter,
                        )
                        if (showLightbox) {
                            ImageLightbox(
                                imageUrl = link,
                                onDismiss = { showLightbox = false }
                            )
                        }
                    }
                }
            },
            table = { model ->
                InfinityTableWidget(
                    model = model,
                    fontSize = fontSize,
                    modifier = Modifier
                        .fillMaxWidth()
                )
            },
            codeFence = { model ->
                MarkdownCodeFence(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                ) { code, language, style ->
                    ReadingCodeBlock(
                        code = code,
                        language = language,
                        fontSize = fontSize,
                        onCodeChange = if (currentOnContentChange.value != null) {
                            { newCode ->
                                currentOnContentChange.value?.invoke(
                                    replaceCodeInMarkdown(currentMarkdown.value, language, code, newCode)
                                )
                            }
                        } else null
                    ) {
                        ReadingHighlightedCode(
                            code = code,
                            language = language,
                            style = style,
                            highlightsBuilder = highlightsBuilder,
                            streaming = currentStreaming.value,
                        )
                    }
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                ) { code, language, style ->
                    ReadingCodeBlock(
                        code = code,
                        language = language,
                        fontSize = fontSize,
                        onCodeChange = if (currentOnContentChange.value != null) {
                            { newCode ->
                                currentOnContentChange.value?.invoke(
                                    replaceCodeInMarkdown(currentMarkdown.value, language, code, newCode)
                                )
                            }
                        } else null
                    ) {
                        ReadingHighlightedCode(
                            code = code,
                            language = language,
                            style = style,
                            highlightsBuilder = highlightsBuilder,
                            streaming = currentStreaming.value,
                        )
                    }
                }
            },
            horizontalRule = { _ ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = if (compactSpacing) 3.dp else 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            },
            custom = { type, model ->
                when (type) {
                    MarkdownElementTypes.HTML_BLOCK -> {
                        MarkdownElementText(
                            content = model.content,
                            node = model.node,
                            style = model.typography.text
                        )
                    }
                }
            }
        )
    }

    // 历史消息同步解析：异步解析会先以空正文布局，解析完成后条目增高，把已滚到底部的尾消息挤出视口。
    // 流式片段与超长片段仍在后台解析，避免逐帧或单帧阻塞主线程。
    val parseImmediately = !streaming && content.length <= SYNC_MARKDOWN_PARSE_MAX_CHARS
    val parsedState by rememberMarkdownState(
        content = content,
        retainState = true,
        immediate = parseImmediately,
    ).state.collectAsState()
    val compatiblePrefix = remember(parsedState, content) {
        (parsedState as? MarkdownRenderState.Success)?.content?.commonPrefixWith(content)?.length ?: 0
    }
    val visibleState = remember(parsedState, visibleLength, compatiblePrefix) {
        val success = parsedState as? MarkdownRenderState.Success
        if (success == null) parsedState else success.copy(node = visibleMarkdownNode(success.node, minOf(visibleLength, compatiblePrefix)))
    }
    val density = LocalDensity.current
    val inlinePadding = with(density) { 4.dp.toSp() }
    val inlineCorner = with(density) { 4.dp.toSp() }
    val inlineCodePaddingContent = remember(inlinePadding) {
        mapOf(INLINE_CODE_PADDING_ID to InlineTextContent(Placeholder(inlinePadding, 1.sp, PlaceholderVerticalAlign.Center)) {})
    }
    val inlineCodeStyle = infinityMarkdownTypography(fontSize, fontStyle).inlineCode.toSpanStyle().copy(
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        color = textColor,
    )
    val annotators = rememberReadingMarkdownAnnotators(inlineCodeStyle, textColor, sourceOffset, reveal, MaterialTheme.colorScheme.primary)
    val workspaceMedia = com.promenar.infinity.ui.media.LocalWorkspaceMedia.current
    val legacyRoots = rememberLegacyGeneratedRoots()

    Markdown(
        state = visibleState,
        annotator = annotators.base,
        imageTransformer = InfinityMarkdownImageTransformer,
        extendedSpans = markdownExtendedSpans {
            remember(inlineCorner) {
                ExtendedSpans(RoundedCornerSpanPainter(cornerRadius = inlineCorner, padding = RoundedCornerSpanPainter.TextPaddingValues()))
            }
        },
        inlineContent = markdownInlineContent(inlineCodePaddingContent),
        dimens = markdownDimens(blockQuoteThickness = 3.dp, codeBackgroundCornerSize = 12.dp),
        padding = markdownPadding(
            block = 0.dp,
            list = 0.dp,
            listItemTop = if (compactSpacing) 1.dp else 5.dp,
            listItemBottom = if (compactSpacing) 1.dp else 5.dp,
            listIndent = 16.dp,
            codeBlock = PaddingValues(0.dp),
            blockQuote = PaddingValues(0.dp),
            blockQuoteText = PaddingValues(0.dp),
            blockQuoteBar = PaddingValues.Absolute(0.dp),
        ),
        colors = infinityMarkdownColors(textColor = textColor),
        typography = infinityMarkdownTypography(fontSize, fontStyle = fontStyle),
        components = components,
        modifier = modifier.fillMaxWidth(),
        success = { state, resolvedComponents, successModifier ->
            Column(modifier = successModifier) {
                val renderContent = state.content
                val tableMeasurer = rememberTableMeasurer(fontSize)
                val blocks = state.node.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownElementTypes.LINK_DEFINITION }
                blocks.forEachIndexed { index, node ->
                    if (index > 0) Spacer(Modifier.height(markdownReadingGap(blocks[index - 1].type, node.type, compactSpacing)))
                    key(node.startOffset, node.type) {
                        val containsWideContent = remember(node) { node.containsWideContentDescendant() }
                        CompositionLocalProvider(LocalMarkdownAnnotator provides if (index == blocks.lastIndex) annotators.tail else annotators.base) {
                        when {
                            node.type == GFMElementTypes.TABLE -> {
                                val table = remember(renderContent, node.startOffset, node.endOffset) {
                                    parseMarkdownTable(renderContent, node)
                                }
                                if (table == null) {
                                    MarkdownElement(node, resolvedComponents, renderContent)
                                } else {
                                    val tableLayout = remember(table, tableMeasurer) { tableMeasurer.layout(table) }
                                    WideContentHost(
                                        text = "",
                                        textStyle = LocalMarkdownTypography.current.text,
                                        horizontalChrome = 0.dp,
                                        minimumContentWidth = tableLayout.totalWidth,
                                    ) {
                                        InfinityTableWidget(
                                            layout = tableLayout,
                                            measurer = tableMeasurer,
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                        )
                                    }
                                }
                            }

                            containsWideContent && node.type != MarkdownElementTypes.CODE_FENCE && node.type != MarkdownElementTypes.CODE_BLOCK -> {
                                val minimumWidth = remember(
                                    renderContent,
                                    node.startOffset,
                                    node.endOffset,
                                    tableMeasurer,
                                ) {
                                    nestedTableMinimumWidth(renderContent, node, tableMeasurer)
                                }
                                val (codeSample, codeChrome) = remember(renderContent, node) { nestedCodeSizingSample(renderContent, node) }
                                if (minimumWidth == null && codeSample.isEmpty()) {
                                    MarkdownElement(node, resolvedComponents, renderContent)
                                } else {
                                    WideAwareContainer(
                                        node = node,
                                        components = resolvedComponents,
                                        content = renderContent,
                                        minimumWidth = minimumWidth ?: 0.dp,
                                        codeSample = codeSample,
                                        codeChrome = codeChrome,
                                    )
                                }
                            }

                            node.type == MarkdownElementTypes.PARAGRAPH -> {
                                val mediaReferences = if (workspaceMedia == null) null else remember(renderContent, node, legacyRoots) {
                                    markdownMediaOnlyReferences(node, renderContent, legacyRoots)
                                }
                                val standaloneCode = if (mediaReferences == null) standaloneInlineCode(node, renderContent) else null
                                if (mediaReferences != null) {
                                    MarkdownMediaBlock(mediaReferences)
                                } else if (standaloneCode == null) {
                                    MarkdownElement(node, resolvedComponents, renderContent)
                                } else {
                                    val codeStyle = LocalMarkdownTypography.current.inlineCode.copy(
                                        color = textColor,
                                        fontFamily = InfinityMonospace,
                                    )
                                    WideContentHost(
                                        text = standaloneCode,
                                        textStyle = codeStyle,
                                        horizontalChrome = 8.dp,
                                    ) {
                                        Text(
                                            text = standaloneCode,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .padding(horizontal = 4.dp),
                                            style = codeStyle,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }

                            node.type == MarkdownElementTypes.CODE_FENCE -> MarkdownCodeFence(
                                content = renderContent,
                                node = node,
                                style = LocalMarkdownTypography.current.code,
                            ) { code, language, style ->
                                WideContentHost(
                                    text = code,
                                    textStyle = style,
                                    horizontalChrome = 32.dp,
                                ) {
                                    ReadingCodeBlock(
                                        code = code,
                                        language = language,
                                        fontSize = fontSize,
                                        onCodeChange = if (currentOnContentChange.value != null) {
                                            { newCode ->
                                                currentOnContentChange.value?.invoke(
                                                    replaceCodeInMarkdown(
                                                        currentMarkdown.value,
                                                        language,
                                                        code,
                                                        newCode,
                                                    ),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    ) {
                                        ReadingHighlightedCode(
                                            code = code,
                                            language = language,
                                            style = style,
                                            highlightsBuilder = highlightsBuilder,
                                            streaming = highlightStreaming,
                                        )
                                    }
                                }
                            }

                            node.type == MarkdownElementTypes.CODE_BLOCK -> MarkdownCodeBlock(
                                content = renderContent,
                                node = node,
                                style = LocalMarkdownTypography.current.code,
                            ) { code, language, style ->
                                WideContentHost(
                                    text = code,
                                    textStyle = style,
                                    horizontalChrome = 32.dp,
                                ) {
                                    ReadingCodeBlock(
                                        code = code,
                                        language = language,
                                        fontSize = fontSize,
                                        onCodeChange = if (currentOnContentChange.value != null) {
                                            { newCode ->
                                                currentOnContentChange.value?.invoke(
                                                    replaceCodeInMarkdown(
                                                        currentMarkdown.value,
                                                        language,
                                                        code,
                                                        newCode,
                                                    ),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    ) {
                                        ReadingHighlightedCode(
                                            code = code,
                                            language = language,
                                            style = style,
                                            highlightsBuilder = highlightsBuilder,
                                            streaming = highlightStreaming,
                                        )
                                    }
                                }
                            }

                            else -> MarkdownElement(node, resolvedComponents, renderContent)
                        }
                        }
                    }
                }
            }
        },
    )
}

/**
 * 为含任意深度 GFM 表格的完整容器提供第一方外层横向视口；容器正文仍交给官方 renderer，
 * 因而引用条、列表标记、缩进、遍历语义与普通 Markdown 路径保持一致。
 */
@Composable
private fun WideAwareContainer(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
    minimumWidth: androidx.compose.ui.unit.Dp,
    codeSample: String = "",
    codeChrome: Dp = 0.dp,
) {
    WideContentHost(
        text = codeSample,
        textStyle = LocalMarkdownTypography.current.code,
        horizontalChrome = codeChrome,
        minimumContentWidth = minimumWidth,
    ) {
        MarkdownElement(node, components, content)
    }
}

/**
 * 横向手势只在第一方顶层宿主注册；内部宽内容始终获得有限的显式宽度，避免向第三方组件回调传播无限约束。
 */
@Composable
private fun WideContentHost(
    text: String,
    textStyle: TextStyle,
    horizontalChrome: androidx.compose.ui.unit.Dp,
    minimumContentWidth: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val viewportWidth = maxWidth
        val lineWidths = remember(textStyle, density.density, density.fontScale, textMeasurer) { LogicalLineWidthCache() }
        val measuredTextWidthPx = remember(text, lineWidths) {
            lineWidths.widestLogicalLineWidthPx(text) { line ->
                textMeasurer.measure(
                    text = AnnotatedString(line),
                    style = textStyle,
                    softWrap = false,
                ).size.width
            }
        }
        val contentWidth = remember(
            measuredTextWidthPx,
            horizontalChrome,
            minimumContentWidth,
            density.density,
        ) {
            with(density) {
                maxOf(measuredTextWidthPx.toDp() + horizontalChrome, minimumContentWidth)
            }
        }
        val targetWidth = maxOf(viewportWidth, contentWidth)
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .testTag(UiTags.MARKDOWN_WIDE_CONTENT),
        ) {
            Box(modifier = Modifier.width(targetWidth)) {
                content()
            }
        }
    }
}

private val bracketBlockLatex = Regex("""\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL)
private val parenInlineLatex = Regex("""\\\((.*?)\\\)""")

/** 把 `\[...\]`、`\(...\)` 归一为美元定界符；代码与链接中的同形字符保持原文。 */
internal fun normalizeLatexDelimiters(text: String): String {
    var result = text
    if ("\\[" in result) {
        result = bracketBlockLatex.replaceOutside(result, markdownMathLiteralRanges(result)) {
            "$$\n${it.groupValues[1].trim()}\n$$"
        }
    }
    if ("\\(" in result) {
        result = parenInlineLatex.replaceOutside(result, markdownMathLiteralRanges(result)) {
            "$${it.groupValues[1].trim()}$"
        }
    }
    return result
}

internal fun sanitizeStreamingMarkdown(text: String): String {
    var result = text

    // Handle code block fences
    val fenceCount = Regex("""(?m)^```""").findAll(result).count()
    if (fenceCount % 2 != 0) {
        if (!result.endsWith("\n")) result += "\n"
        result += "```"
    }

    // 只统计代码与链接之外的美元符号，避免 `echo $$` 之类的字面量被当作未闭合公式截断后文。
    // 下方只删除尾部字符，已求出的区间在剩余文本中仍然有效。
    val literalRanges = if ('$' in result) markdownMathLiteralRanges(result) else emptyList()

    // Handle inline math $...$
    val inlineMathCount = Regex("""(?<!\$)\$(?!\$)""").findAllOutside(result, literalRanges).size
    if (inlineMathCount % 2 != 0) {
        if (result.endsWith("$")) {
            result = result.dropLast(1)
        }
    }

    // Handle block math $$...$$
    val blockMathMarkers = Regex("""\$\$""").findAllOutside(result, literalRanges)
    if (blockMathMarkers.size % 2 != 0) {
        result = result.substring(0, blockMathMarkers.last().range.first)
    }

    // Handle streaming bold spans such as "**预" before the closing marker arrives.
    val emphasisScan = Regex("""```[\s\S]*?```|`[^`]*`""").replace(result, "")
    val boldMarkerCount = Regex("""(?<!\*)\*\*(?!\*)""").findAll(emphasisScan).count()
    if (boldMarkerCount % 2 != 0) {
        result += "**"
    }

    return result
}


internal fun slugify(text: String): String {
    return text.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .lowercase()
        .replace(Regex("-+"), "-")
        .trim('-')
        .takeIf { it.isNotEmpty() } ?: "heading"
}

private fun anchoredHeading(
    getStyle: (MarkdownComponentModel) -> TextStyle,
    contentChildType: IElementType,
    compactSpacing: Boolean,
): MarkdownComponent = { model ->
    val headingText = model.node.getUnescapedTextInNode(model.content)
    val slug = slugify(headingText)
    val headingDescription = stringResource(R.string.chat_cd_heading, headingText)
    ReadingMarkdownText(
        content = model.content,
        node = model.node,
        style = getStyle(model),
        contentChildType = contentChildType,
        modifier = Modifier
            .testTag("heading-$slug")
            .semantics {
                heading()
                contentDescription = headingDescription
            }
    )
}
