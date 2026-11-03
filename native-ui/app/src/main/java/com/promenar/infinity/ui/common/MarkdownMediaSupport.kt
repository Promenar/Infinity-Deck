package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.promenar.infinity.ui.media.MarkdownImageTarget
import com.promenar.infinity.ui.media.MarkdownLinkTarget
import com.promenar.infinity.ui.media.WorkspaceMediaReference
import com.promenar.infinity.ui.media.classifyMarkdownImageTarget
import com.promenar.infinity.ui.media.classifyMarkdownLinkTarget
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

/** 段落中的一个工作区媒体引用：图片 `![说明](路径)` 或链接 `[名称](路径)`。 */
internal data class MarkdownMediaReference(
    val path: String,
    val label: String?,
    val isLink: Boolean,
)

/** 旧生图目录 `filesDir/generated_images/`。 */
internal fun legacyGeneratedImageRoots(filesDir: java.io.File?): List<String> =
    listOfNotNull(filesDir?.let { java.io.File(it, "generated_images").absolutePath })

/**
 * 段落只由工作区媒体引用与空白组成时返回这些引用，由块级卡片整体接管；否则返回 null，
 * 段落仍走渲染库原有路径，文字排版不受影响。
 */
internal fun markdownMediaOnlyReferences(
    paragraph: ASTNode,
    content: String,
    legacyGeneratedRoots: List<String>,
): List<MarkdownMediaReference>? {
    if (paragraph.type != MarkdownElementTypes.PARAGRAPH) return null
    val references = mutableListOf<MarkdownMediaReference>()
    for (child in paragraph.children) {
        when (child.type) {
            MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> Unit
            MarkdownTokenTypes.TEXT -> if (child.getUnescapedTextInNode(content).isNotBlank()) return null
            MarkdownElementTypes.IMAGE -> {
                val link = child.findChildOfType(MarkdownElementTypes.INLINE_LINK) ?: return null
                val destination = link.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getUnescapedTextInNode(content) ?: return null
                val target = classifyMarkdownImageTarget(destination, legacyGeneratedRoots)
                    as? MarkdownImageTarget.Workspace ?: return null
                references += MarkdownMediaReference(target.path, link.linkLabel(content), isLink = false)
            }
            MarkdownElementTypes.INLINE_LINK -> {
                val destination = child.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getUnescapedTextInNode(content) ?: return null
                val target = classifyMarkdownLinkTarget(destination) as? MarkdownLinkTarget.Workspace ?: return null
                references += MarkdownMediaReference(target.path, child.linkLabel(content), isLink = true)
            }
            else -> return null
        }
    }
    return references.takeIf { it.isNotEmpty() }
}

private fun ASTNode.linkLabel(content: String): String? =
    findChildOfType(MarkdownElementTypes.LINK_TEXT)
        ?.getUnescapedTextInNode(content)
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

/** 块级媒体卡片组：每个引用一张卡片，纵向排列。 */
@Composable
internal fun MarkdownMediaBlock(references: List<MarkdownMediaReference>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        references.forEach { reference ->
            WorkspaceMediaReference(path = reference.path, alt = reference.label, asLink = reference.isLink)
        }
    }
}

/**
 * 媒体围栏块：```gallery 排成矩阵，```carousel 显示为内嵌查看器；可视项不足两个时按默认单列渲染。
 * 音频与非媒体文件不进入矩阵或查看器，在块下方按现有卡片逐个渲染。
 */
@Composable
internal fun MarkdownMediaGroupBlock(group: MarkdownMediaGroup, modifier: Modifier = Modifier) {
    if (group.rendersAsColumn) {
        val references = group.items + group.trailing
        if (references.isNotEmpty()) MarkdownMediaBlock(references, modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (group.layout) {
            MarkdownMediaGroupLayout.GALLERY -> com.promenar.infinity.ui.media.WorkspaceMediaGallery(group.items)
            MarkdownMediaGroupLayout.CAROUSEL -> com.promenar.infinity.ui.media.WorkspaceMediaCarousel(group.items)
        }
        group.trailing.forEach { reference ->
            WorkspaceMediaReference(path = reference.path, alt = reference.label, asLink = reference.isLink)
        }
    }
}

/** 占位画笔：工作区目标报告较大的固有尺寸，使渲染库把它提升为块级元素并交给 image 组件绘制卡片。 */
internal object WorkspaceMediaMarkerPainter : Painter() {
    override val intrinsicSize: Size = Size(1024f, 1024f)
    override fun DrawScope.onDraw() = Unit
}

/**
 * Markdown 图片加载：https、data:image 与旧生图路径沿用 Coil；工作区目标交给卡片；其它 scheme 不加载且不占位。
 */
internal object InfinityMarkdownImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val roots = rememberLegacyGeneratedRoots()
        return when (classifyMarkdownImageTarget(link, roots)) {
            is MarkdownImageTarget.Remote, is MarkdownImageTarget.LegacyGenerated -> Coil3ImageTransformerImpl.transform(link)
            is MarkdownImageTarget.Workspace -> ImageData(painter = WorkspaceMediaMarkerPainter, contentDescription = null)
            MarkdownImageTarget.Blocked -> null
        }
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size =
        if (painter === WorkspaceMediaMarkerPainter) painter.intrinsicSize else Coil3ImageTransformerImpl.intrinsicSize(painter)

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        // 被拦截的目标不加载，也不在行内留出空白。
        if (isBlockedImageTarget(link)) return PlaceholderConfig(Size(0f, 0f), PlaceholderVerticalAlign.Bottom)
        return Coil3ImageTransformerImpl.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
    }

    private fun isBlockedImageTarget(link: String): Boolean =
        // 旧生图路径以 `/` 开头，不会被判为拦截；此处无需应用目录。
        classifyMarkdownImageTarget(link, emptyList()) == MarkdownImageTarget.Blocked
}

@Composable
internal fun rememberLegacyGeneratedRoots(): List<String> {
    val context = LocalContext.current
    return remember(context) { legacyGeneratedImageRoots(runCatching { context.filesDir }.getOrNull()) }
}

/** 正文内工作区链接的点击交给 [onWorkspaceLink]，其它链接交回原处理器，行为不变。 */
internal class WorkspaceLinkUriHandler(
    private val delegate: UriHandler,
    private val onWorkspaceLink: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        when (val target = classifyMarkdownLinkTarget(uri)) {
            is MarkdownLinkTarget.Workspace -> onWorkspaceLink(target.path)
            MarkdownLinkTarget.External -> delegate.openUri(uri)
        }
    }
}
