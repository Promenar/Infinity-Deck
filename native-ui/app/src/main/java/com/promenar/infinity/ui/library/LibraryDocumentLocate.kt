package com.promenar.infinity.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.ChunkLocator
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading

/** 要在文档页定位的分块：来自问资料命中或对话引用。 */
internal data class DocumentLocateRequest(
    val documentId: String,
    val chunks: List<LocateChunk>,
    /** 来自问资料结果；否则为对话引用。 */
    val fromAsk: Boolean,
) {
    internal data class LocateChunk(val content: String, val terms: List<String>)

    companion object {
        fun fromAsk(documentId: String, hits: List<AskHit>): DocumentLocateRequest? {
            val chunks = hits.mapNotNull { hit ->
                (hit.chunkContent ?: hit.snippet).takeIf { it.isNotBlank() }?.let { LocateChunk(it, hit.highlightTerms) }
            }
            return if (chunks.isEmpty()) null else DocumentLocateRequest(documentId, chunks, fromAsk = true)
        }
    }
}

/** 在正文里定位各分块并切段；一个都没找到时返回 null，文档页照常显示全文。 */
internal fun locateRequest(body: DocumentBody, request: DocumentLocateRequest, locator: ChunkLocator): LocatedBody? {
    val ranges = request.chunks.mapNotNull { locator.locate(body.text, it.content, it.terms) }
    return locateBody(body.text, ranges, body.markdown)
}

/** 文档页顶部的定位条：命中数、当前第几处，与上一处 / 下一处。 */
@Composable
internal fun DocumentLocateBar(hitCount: Int, focus: Int, cited: Boolean, onMove: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.primary.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.XSmall)
            .testTag(LibraryTags.page("locate_bar")),
    ) {
        Row(
            Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(start = InfinitySpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = if (cited) {
                stringResource(R.string.library_doc_locate_cited)
            } else {
                pluralStringResource(R.plurals.library_doc_locate_count, hitCount, hitCount, focus + 1)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hitCount > 1) {
                IconButton(onClick = { onMove(-1) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.library_doc_locate_prev), tint = scheme.primary)
                }
                IconButton(onClick = { onMove(1) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.library_doc_locate_next), tint = scheme.primary)
                }
            }
        }
    }
}

/**
 * 按片段渲染正文：命中段加底色，当前命中段加描边；当前命中段变化时把它滚到视口上部。
 * 位置取自各段的屏幕坐标，与视口顶部相减即为需要滚动的距离。
 */
@Composable
internal fun LocatedBodySegments(
    located: LocatedBody,
    markdown: Boolean,
    focus: Int,
    scroll: ScrollState,
    viewportTop: () -> Float,
) {
    val positions = remember(located) { HashMap<Int, Float>() }
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(located, focus) {
        // 等首帧布局给出位置，最多等约半秒。
        var frames = 0
        while (focus !in positions && frames < 30) {
            withFrameNanos { }
            frames++
        }
        val y = positions[focus] ?: return@LaunchedEffect
        scroll.animateScrollTo((scroll.value + y - viewportTop() - margin).toInt().coerceAtLeast(0))
    }
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        located.segments.forEach { segment ->
            val ordinal = segment.hitOrdinal
            if (ordinal == null) {
                SegmentText(segment.text, markdown)
            } else {
                val scheme = MaterialTheme.colorScheme
                val current = ordinal == focus
                val container by animateColorAsState(
                    if (current) scheme.primary.copy(alpha = 0.16f) else scheme.primary.copy(alpha = 0.07f),
                    label = "locate_segment",
                )
                Surface(
                    color = container,
                    contentColor = scheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                    border = if (current) BorderStroke(2.dp, scheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { positions[ordinal] = it.positionInRoot().y }
                        .testTag(LibraryTags.page("locate_hit_$ordinal")),
                ) {
                    Box(Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)) {
                        SegmentText(segment.text, markdown)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentText(text: String, markdown: Boolean) {
    if (markdown) {
        MarkdownText(markdown = text, modifier = Modifier.fillMaxWidth())
    } else {
        Text(text, style = MaterialTheme.typography.bodyMedium.forReading())
    }
}
