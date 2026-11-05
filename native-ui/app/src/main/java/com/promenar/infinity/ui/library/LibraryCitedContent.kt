package com.promenar.infinity.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.domain.library.CitationSource
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.ProvideGroupedPress
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinitySpacing

/*
 * 引用记录在资料页的三处呈现：文档页“智能体怎么用它”、概览“智能体最近用到”与视图“智能体常用”。
 */

/** 文档页：哪些回答引用过这篇文档；对话引用可点开会话。 */
@Composable
internal fun DocumentCitationsCard(documentId: String, host: LibraryPageHost) {
    val citations by remember(documentId) { host.viewModel.citationsFor(documentId) }.collectAsStateWithLifecycle(emptyList())
    DocumentCitationsCardContent(citations, onOpenSession = host.onOpenSession)
}

@Composable
internal fun DocumentCitationsCardContent(citations: List<DocumentCitation>, onOpenSession: (String) -> Unit) {
    LibraryCard(title = stringResource(R.string.library_doc_cited_title), count = citations.size) {
        if (citations.isEmpty()) {
            LibraryEmptyText(stringResource(R.string.library_doc_cited_empty))
        } else {
            citations.forEach { citation ->
                CitationRow(citation, onOpenSession = citation.sessionId?.let { id -> { onOpenSession(id) } })
            }
        }
    }
}

@Composable
private fun CitationRow(citation: DocumentCitation, onOpenSession: (() -> Unit)?) {
    val now = remember { System.currentTimeMillis() }
    val ask = citation.source == CitationSource.ASK
    val title = when {
        ask -> stringResource(R.string.library_cite_ask_title, citation.askQuery.orEmpty())
        else -> citation.sessionTitle ?: stringResource(R.string.library_fragments_deleted_session)
    }
    val label = if (ask) stringResource(R.string.library_cite_ask_label, citation.label) else stringResource(R.string.library_cite_label, citation.label)
    CitedRowLayout(
        icon = if (ask) Icons.Rounded.Search else Icons.Rounded.Forum,
        title = title,
        meta = listOfNotNull(label, citation.agentName, DateUtils.getRelativeTimeSpanString(citation.citedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()),
        onClick = if (ask || citation.sessionTitle == null) null else onOpenSession,
    )
}

/** 概览卡：最近被回答引用的文档。 */
@Composable
internal fun RecentCitedCard(host: LibraryPageHost) {
    val recent by host.viewModel.recentCited.collectAsStateWithLifecycle()
    RecentCitedCardContent(
        recent = recent,
        onOpen = { host.navigator.push(LibraryPageKeys.document(it)) },
        onViewAll = { host.navigator.openSection(LibraryPageKeys.CITED) },
    )
}

@Composable
internal fun RecentCitedCardContent(recent: List<CitedDocument>, onOpen: (String) -> Unit, onViewAll: () -> Unit) {
    LibraryCard(
        title = stringResource(R.string.library_cited_recent_title),
        onViewAll = if (recent.isNotEmpty()) onViewAll else null,
    ) {
        if (recent.isEmpty()) {
            LibraryEmptyText(stringResource(R.string.library_cited_empty))
        } else {
            recent.forEach { doc -> CitedDocumentRow(doc, onOpen = { onOpen(doc.documentId) }) }
        }
    }
}

@Composable
private fun CitedDocumentRow(doc: CitedDocument, onOpen: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    CitedRowLayout(
        icon = Icons.Rounded.Description,
        title = doc.name,
        identifier = true,
        meta = listOfNotNull(
            pluralStringResource(R.plurals.library_cited_count, doc.count, doc.count),
            doc.lastSessionTitle,
            doc.lastAgentName,
            DateUtils.getRelativeTimeSpanString(doc.lastCitedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        ),
        onClick = onOpen,
    )
}

@Composable
private fun CitedRowLayout(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    meta: List<String>,
    onClick: (() -> Unit)?,
    /** 标题是文件名：在分隔符处断行，不拆开单词。 */
    identifier: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(InfinitySpacing.RowIconSize))
        Column(Modifier.weight(1f)) {
            if (identifier) {
                InfinityIdentifierText(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            InfinityMetaLine(meta, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

/** 视图“智能体常用”：按被引用次数排列。 */
@Composable
internal fun LibraryCitedContent(host: LibraryPageHost, padding: PaddingValues) {
    val top by host.viewModel.topCited.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (top.isEmpty()) item(key = "empty") { LibraryEmptyText(stringResource(R.string.library_cited_empty)) }
        itemsIndexed(top, key = { _, it -> it.documentId }) { index, doc ->
            ProvideGroupedPress {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Description,
                    title = doc.name,
                    subtitleSegments = listOfNotNull(
                        pluralStringResource(R.plurals.library_cited_count, doc.count, doc.count),
                        doc.lastSessionTitle,
                        DateUtils.getRelativeTimeSpanString(doc.lastCitedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                    ),
                    subtitleMaxLines = 2,
                    onClick = { host.navigator.push(LibraryPageKeys.document(doc.documentId)) },
                    showChevron = true,
                    modifier = Modifier.animateItem().bettboxListGroupItem(index, top.size),
                )
            }
        }
    }
}
