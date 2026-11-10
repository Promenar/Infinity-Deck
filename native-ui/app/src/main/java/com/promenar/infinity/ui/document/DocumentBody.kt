package com.promenar.infinity.ui.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 正文阅读宽度上限，与会话正文一致。 */
private val ReadingMaxWidth = InfinitySpacing.ChatMaxWidth

/** 查看器正文的几种状态。 */
sealed interface DocumentBodyState {
    data object Loading : DocumentBodyState
    data class Message(val text: String) : DocumentBodyState
    /**
     * Markdown 正文。只有一块时整篇渲染；多块时按块惰性渲染，只组合视口附近的块，
     * 大文件不必一次性解析和排版全文。分块由 [com.promenar.infinity.data.document.MarkdownChunks] 产生。
     */
    data class Markdown(val chunks: List<String>) : DocumentBodyState
    /** 不按 Markdown 解析的纯文本，等宽显示。 */
    data class Plain(val text: String) : DocumentBodyState
}

@Composable
fun DocumentBody(state: DocumentBodyState, modifier: Modifier = Modifier) {
    Box(modifier.testTag(DocumentViewerTags.BODY)) {
        when (state) {
            DocumentBodyState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                InfinityLoadingIndicator()
            }
            is DocumentBodyState.Message -> Box(
                Modifier.fillMaxSize().padding(InfinitySpacing.XLarge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is DocumentBodyState.Markdown -> MarkdownBody(state.chunks)
            is DocumentBodyState.Plain -> ReadingColumn {
                SelectionContainer {
                    Text(
                        state.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownBody(chunks: List<String>) {
    if (chunks.size <= 1) {
        ReadingColumn {
            SelectionContainer { MarkdownText(chunks.firstOrNull().orEmpty()) }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        // 选择范围限在单块之内：跨块选择需要整篇在同一个选择容器里，与惰性渲染相冲突。
        itemsIndexed(chunks, key = { index, _ -> index }, contentType = { _, _ -> "markdown" }) { _, chunk ->
            SelectionContainer(Modifier.widthIn(max = ReadingMaxWidth).fillMaxWidth()) {
                MarkdownText(chunk)
            }
        }
    }
}

@Composable
private fun ReadingColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.widthIn(max = ReadingMaxWidth).fillMaxWidth()) { content() }
    }
}
