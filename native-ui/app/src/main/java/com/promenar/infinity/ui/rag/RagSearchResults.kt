package com.promenar.infinity.ui.rag

import androidx.compose.foundation.clickable
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.Document
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing

internal object RagSearchContract {
    const val DEBOUNCE_MILLIS = 250L
    const val SNIPPET_MAX_CHARS = 180
}

enum class RagSearchMatchKind {
    FtsBody,
    TitleFallback,
}

data class RagSearchResult(
    val document: Document,
    val snippet: String? = null,
    val matchKind: RagSearchMatchKind,
)

enum class RagSearchErrorCode {
    FtsUnavailable,
}

sealed interface RagSearchUiState {
    data object Idle : RagSearchUiState

    data class Loading(val query: String) : RagSearchUiState

    data class Results(
        val query: String,
        val items: List<RagSearchResult>,
    ) : RagSearchUiState

    data class Warning(
        val query: String,
        val code: RagSearchErrorCode,
        val fallbackItems: List<RagSearchResult>,
        val technical: String?,
    ) : RagSearchUiState
}

internal fun buildRagSearchSnippet(
    content: String,
    query: String,
    maxChars: Int = RagSearchContract.SNIPPET_MAX_CHARS,
): String {
    val cleanContent = content.lines().joinToString("\n") { line ->
        line.replaceFirst(Regex("""^#{1,6}\s+"""), "")
    }
    if (cleanContent.length <= maxChars) return cleanContent
    val matchIndex = cleanContent.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val available = (maxChars - 2).coerceAtLeast(1)
    val start = (matchIndex - available / 3).coerceIn(0, (cleanContent.length - available).coerceAtLeast(0))
    val end = (start + available).coerceAtMost(cleanContent.length)
    return buildString(maxChars) {
        if (start > 0) append('…')
        append(cleanContent, start, end)
        if (end < cleanContent.length) append('…')
    }
}

@Composable
internal fun RagSearchResults(
    state: RagSearchUiState,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RagSearchUiState.Idle -> Unit
        is RagSearchUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is RagSearchUiState.Results -> {
            RagSearchResultList(
                items = state.items,
                onOpenDocument = onOpenDocument,
                modifier = modifier,
            )
        }
        is RagSearchUiState.Warning -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.RAG_SEARCH_WARNING),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.rag_search_fts_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(InfinitySpacing.Medium),
                    )
                }
                RagSearchResultList(
                    items = state.fallbackItems,
                    onOpenDocument = onOpenDocument,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val RAG_SEARCH_SNIPPET_MAX_LINES = 2

/**
 * 搜索结果行最小高度。列表各项等高：有摘要时按 Material 三行列表项排版——
 * 上下内边距 12dp×2 + 标题 bodyLarge 一行 + 摘要 bodyMedium 两行，默认 24 + 22 + 40 = 86dp，不低于规范的 88dp；
 * 行高随字号实算。没有任何摘要时保持 48dp 触控高度。
 */
@Composable
private fun ragSearchResultMinHeight(withSnippet: Boolean): Dp {
    if (!withSnippet) return InfinitySpacing.MinimumTouchTarget
    val typography = MaterialTheme.typography
    return with(LocalDensity.current) {
        maxOf(88.dp, 24.dp + typography.bodyLarge.lineHeight.toDp() + typography.bodyMedium.lineHeight.toDp() * RAG_SEARCH_SNIPPET_MAX_LINES)
    }
}

@Composable
private fun RagSearchResultList(
    items: List<RagSearchResult>,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.rag_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // 列表各项等高：惰性列表看不到屏幕外的行，按全部结果算出是否有摘要；有则无摘要的结果也按带摘要的高度排版。
    val rowMinHeight = ragSearchResultMinHeight(withSnippet = items.any { it.snippet != null })
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.RAG_SEARCH_RESULTS),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        items(items, key = { it.document.id }) { item ->
            val matchLabel = when (item.matchKind) {
                RagSearchMatchKind.FtsBody -> stringResource(R.string.rag_search_body_match)
                RagSearchMatchKind.TitleFallback -> stringResource(R.string.rag_search_title_fallback)
            }
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = rowMinHeight)
                    .semantics { contentDescription = "${item.document.title}, $matchLabel" }
                    .clickable(role = Role.Button) { onOpenDocument(item.document.id) }
                    .testTag(UiTags.RAG_SEARCH_RESULT_PREFIX + item.document.id),
                // 列表各项等高：标题限一行、摘要固定最多两行，完整标题见无障碍描述。
                headlineContent = {
                    Text(item.document.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = item.snippet?.let { snippet ->
                    {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodyMedium.forReading(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = RAG_SEARCH_SNIPPET_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingContent = {
                    Text(
                        text = matchLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(
                            when (item.matchKind) {
                                RagSearchMatchKind.FtsBody -> UiTags.RAG_SEARCH_BODY_MATCH
                                RagSearchMatchKind.TitleFallback -> UiTags.RAG_SEARCH_TITLE_FALLBACK
                            },
                        ),
                    )
                },
            )
        }
    }
}
