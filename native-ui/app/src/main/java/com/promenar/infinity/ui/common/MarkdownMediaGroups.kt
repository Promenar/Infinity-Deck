package com.promenar.infinity.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.ui.media.MarkdownImageTarget
import com.promenar.infinity.ui.media.classifyMarkdownImageTarget
import com.promenar.infinity.ui.media.mediaDisplayName
import com.promenar.infinity.ui.media.mediaKindForFileName
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** Agent 用围栏块选择的媒体排版。 */
internal enum class MarkdownMediaGroupLayout {
    /** ```gallery：矩阵排列。 */
    GALLERY,

    /** ```carousel：内嵌可滑动查看器。 */
    CAROUSEL,
}

/**
 * 一个媒体围栏块的解析结果。[items] 为进入矩阵或查看器的图片与视频；[trailing] 为音频与非媒体文件，
 * 在块下方按现有卡片逐个渲染。
 */
internal data class MarkdownMediaGroup(
    val layout: MarkdownMediaGroupLayout,
    val items: List<MarkdownMediaReference>,
    val trailing: List<MarkdownMediaReference> = emptyList(),
) {
    /** 可视项不足两个时不组成矩阵或查看器，按默认单列渲染。 */
    val rendersAsColumn: Boolean get() = items.size < 2
}

/** 围栏信息串的首个词（不区分大小写）为 `gallery` 或 `carousel` 时返回对应排版。 */
internal fun markdownMediaGroupLayout(info: String?): MarkdownMediaGroupLayout? =
    when (info?.trim()?.substringBefore(' ')?.substringBefore('\t')?.lowercase()) {
        "gallery" -> MarkdownMediaGroupLayout.GALLERY
        "carousel" -> MarkdownMediaGroupLayout.CAROUSEL
        else -> null
    }

private val fenceOpenPattern = Regex("^(`{3,}|~{3,})(.*)$")
/** `![说明](目标)` 独占一行；目标为 `<含空格的路径>` 或不含空白与括号的路径，可带引号标题。 */
private val imageLinePattern = Regex(
    "^!\\[((?:\\\\.|[^\\]\\\\])*)]\\(\\s*(<[^<>\\n]*>|[^\\s()<>]+)(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)$",
)
private val escapedPunctuation = Regex("\\\\([!-/:-@\\[-`{-~])")

/** 去掉列表缩进与引用块的 `>` 前缀，得到行内实际内容。 */
private fun stripContainerPrefix(line: String): String {
    var current = line.trimStart()
    while (current.startsWith(">")) current = current.removePrefix(">").trimStart()
    return current.trimEnd()
}

/**
 * 解析围栏原文（从开头的 ``` 行起，可以缺少结尾围栏）。信息串不是 gallery/carousel 时返回 null，
 * 普通代码块不受影响；块内每行一个 `![说明](工作区路径)`，无法解析或非工作区目标的行忽略。
 * 流式生成中围栏尚未闭合、末行尚未写完时，只返回已完整的行，渐进渲染。
 */
internal fun parseMarkdownMediaGroup(fenceText: String, legacyGeneratedRoots: List<String>): MarkdownMediaGroup? {
    val lines = fenceText.lines()
    val open = fenceOpenPattern.matchEntire(stripContainerPrefix(lines.firstOrNull() ?: return null)) ?: return null
    val fence = open.groupValues[1]
    // 反引号围栏的信息串不得含反引号（CommonMark）。
    if (fence.first() == '`' && open.groupValues[2].contains('`')) return null
    val layout = markdownMediaGroupLayout(open.groupValues[2]) ?: return null
    val items = mutableListOf<MarkdownMediaReference>()
    val trailing = mutableListOf<MarkdownMediaReference>()
    for (raw in lines.drop(1)) {
        val line = stripContainerPrefix(raw)
        if (line.length >= fence.length && line.all { it == fence.first() }) break
        val reference = parseMediaLine(line, legacyGeneratedRoots) ?: continue
        when (mediaKindForFileName(mediaDisplayName(reference.path))) {
            MediaKind.IMAGE, MediaKind.VIDEO -> items += reference
            MediaKind.AUDIO, MediaKind.FILE -> trailing += reference
        }
    }
    return MarkdownMediaGroup(layout, items, trailing)
}

private fun parseMediaLine(line: String, legacyGeneratedRoots: List<String>): MarkdownMediaReference? {
    val match = imageLinePattern.matchEntire(line) ?: return null
    val label = escapedPunctuation.replace(match.groupValues[1], "$1").trim().takeIf(String::isNotEmpty)
    val target = classifyMarkdownImageTarget(match.groupValues[2], legacyGeneratedRoots) as? MarkdownImageTarget.Workspace ?: return null
    return MarkdownMediaReference(target.path, label, isLink = false)
}

/**
 * 从代码围栏节点取媒体分组。信息串按完整解析文本读取（逐字放出时首行未放完也能识别，不闪出代码框），
 * 块内各行只取 [node] 可见范围内已放出的部分。
 */
internal fun markdownMediaGroupOf(node: ASTNode, content: String, legacyGeneratedRoots: List<String>): MarkdownMediaGroup? {
    if (node.type != MarkdownElementTypes.CODE_FENCE) return null
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    val headerEnd = content.indexOf('\n', start).let { if (it < 0) content.length else it }
    val text = if (headerEnd >= end) content.substring(start, headerEnd) else content.substring(start, end)
    return parseMarkdownMediaGroup(text, legacyGeneratedRoots)
}

/** 代码围栏的信息串为 gallery 或 carousel；只读首行，不解析块内各行。 */
internal fun isMarkdownMediaGroupFence(node: ASTNode, content: String): Boolean {
    if (node.type != MarkdownElementTypes.CODE_FENCE) return false
    val start = node.startOffset.coerceIn(0, content.length)
    val headerEnd = content.indexOf('\n', start).let { if (it < 0) content.length else it }
    val open = fenceOpenPattern.matchEntire(stripContainerPrefix(content.substring(start, headerEnd))) ?: return false
    return markdownMediaGroupLayout(open.groupValues[2]) != null
}

/** 会话界面提供工作区媒体环境时识别媒体围栏块；其它场景（预览、实验室）保持普通代码块。 */
@Composable
internal fun rememberMarkdownMediaGroup(node: ASTNode, content: String): MarkdownMediaGroup? {
    if (com.promenar.infinity.ui.media.LocalWorkspaceMedia.current == null) return null
    val roots = rememberLegacyGeneratedRoots()
    return remember(content, node.type, node.startOffset, node.endOffset, roots) {
        markdownMediaGroupOf(node, content, roots)
    }
}
