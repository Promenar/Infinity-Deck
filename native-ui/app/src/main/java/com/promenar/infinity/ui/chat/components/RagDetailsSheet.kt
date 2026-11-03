// UNIT TEST EXEMPTION STATEMENT: 本文件仅涉及 Jetpack Compose 纯布局、UI 交互展现与跳转逻辑，不包含任何数据转换、判定、图算法或状态流转核心业务逻辑，故依全局开发规范 §4.2 予以单元测试豁免。
package com.promenar.infinity.ui.chat.components

import androidx.compose.ui.draw.clip
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.clickable
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.promenar.infinity.ui.common.InfinityCapsuleTabRow
import com.promenar.infinity.ui.common.InfinityTabItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.ui.testing.UiTags
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RagDetailsTab(val labelRes: Int) {
    Retrieved(R.string.rag_details_tab_retrieved),
    WebSearch(R.string.rag_details_tab_web_search),
    KnowledgeGraph(R.string.rag_details_tab_knowledge_graph),
}

internal fun ragReferenceStableKey(reference: RagReference): String {
    val explicitId = reference.id.trim()
    return if (explicitId.isNotEmpty()) {
        canonicalStableIdentity("reference-id", explicitId)
    } else {
        canonicalStableIdentity(
            "reference-content",
            reference.documentId.orEmpty(),
            reference.source,
            reference.chunkIndex.toString(),
            reference.content,
        )
    }
}

internal fun citationStableKey(citation: Citation): String = canonicalStableIdentity(
    "citation",
    citation.url.trim(),
    citation.title,
    citation.source.orEmpty(),
    citation.snippet.orEmpty(),
)

internal fun kgEdgeStableKey(edge: com.promenar.infinity.data.model.KgEdge): String =
    canonicalStableIdentity(
        "kg-edge",
        edge.sourceId,
        edge.targetId,
        edge.relation,
        edge.weight.toString(),
    )

internal fun kgPathStableKey(path: KgPath): String = canonicalStableIdentity(
    "kg-path",
    path.queryKeywords.sorted().joinToString(separator = "\u001f"),
    path.nodes
        .map { node ->
            canonicalStableIdentity("kg-node", node.id, node.label, node.type, node.metadata.orEmpty())
        }
        .sorted()
        .joinToString(separator = "\u001f"),
    path.sourceDocumentIds.sorted().joinToString(separator = "\u001f"),
    path.edges.map(::kgEdgeStableKey).sorted().joinToString(separator = "\u001f"),
    path.reasoning.orEmpty(),
)

internal fun stableOccurrenceKeys(baseIdentities: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return baseIdentities.map { identity ->
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        "$identity:occurrence:$occurrence"
    }
}

private data class StableKeyedItem<T>(val key: String, val value: T)

private fun <T> stableKeyedItems(items: List<T>, identity: (T) -> String): List<StableKeyedItem<T>> {
    val keys = stableOccurrenceKeys(items.map(identity))
    return items.mapIndexed { index, item -> StableKeyedItem(keys[index], item) }
}

private fun canonicalStableIdentity(namespace: String, vararg components: String): String {
    val canonical = buildString {
        append(namespace.length).append(':').append(namespace)
        components.forEach { component ->
            append('|').append(component.length).append(':').append(component)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$namespace:$digest"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDetailsSheet(
    references: List<RagReference>?,
    kgPaths: List<KgPath>?,
    citations: List<Citation>? = null,
    onDismissRequest: () -> Unit,
    openLink: ((String) -> Unit)? = null,
    trace: RagRetrievalTrace? = null,
    knowledgeSearch: KnowledgeSearchDetails? = null,
) {
    // 外框与内容同为 surfaceContainerLow，顶部拖动条一带不会露出另一层底色。
    InfinityModalSheet(onDismissRequest = onDismissRequest, height = InfinitySheetHeight.Full, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        RagDetailsSheetContent(
            references = references.orEmpty(),
            citations = citations.orEmpty(),
            kgPaths = kgPaths.orEmpty(),
            modifier = Modifier.weight(1f),
            openLink = openLink,
            trace = trace,
            knowledgeSearch = knowledgeSearch,
        )
    }
}

@Composable
fun RagDetailsSheetContent(
    references: List<RagReference>,
    citations: List<Citation>,
    kgPaths: List<KgPath>,
    modifier: Modifier = Modifier,
    trace: RagRetrievalTrace? = null,
    initialTab: RagDetailsTab = initialRagDetailsTab(references, citations, kgPaths, trace),
    openLink: ((String) -> Unit)? = null,
    /** 智能体调用知识检索工具的结果：页签上方显示查询词，条目显示引用编号与读取路径。 */
    knowledgeSearch: KnowledgeSearchDetails? = null,
) {
    var selectedTabIndex by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.ordinal) }
    val tabs = RagDetailsTab.entries
    val uriHandler = LocalUriHandler.current
    val linkOpener = openLink ?: uriHandler::openUri
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failureMessage = stringResource(R.string.rag_details_link_error)
    val retryLabel = stringResource(R.string.rag_details_retry)

    fun requestOpen(url: String) {
        scope.launch {
            try {
                linkOpener(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val result = snackbarHostState.showSnackbar(
                    message = failureMessage,
                    actionLabel = retryLabel,
                    withDismissAction = false,
                )
                if (result == SnackbarResult.ActionPerformed) requestOpen(url)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.RAG_DETAILS_ROOT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.testTag(UiTags.RAG_DETAILS_LINK_ERROR),
                    action = {
                        TextButton(
                            onClick = data::performAction,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(UiTags.RAG_DETAILS_LINK_RETRY),
                        ) {
                            Text(data.visuals.actionLabel.orEmpty())
                        }
                    },
                    dismissAction = null,
                ) {
                    Text(data.visuals.message)
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.rag_details_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            knowledgeSearch?.let { search ->
                Text(
                    text = stringResource(R.string.knowledge_search_query_line, search.query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp).testTag(KNOWLEDGE_SEARCH_QUERY_TAG),
                )
            }
            val detailTabs = tabs.map { tab ->
                InfinityTabItem(
                    title = stringResource(tab.labelRes),
                    testTag = tab.testTag(),
                )
            }
            InfinityCapsuleTabRow(
                tabs = detailTabs,
                selectedIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // 同级标签按左右顺序做共享 X 轴切换。
            androidx.compose.animation.AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState > initialState) },
                modifier = Modifier.fillMaxWidth(),
                label = "rag_details_tab",
            ) { index ->
                when (tabs[index.coerceIn(0, tabs.lastIndex)]) {
                    RagDetailsTab.Retrieved -> RetrievedReferencesList(references, trace, knowledgeSearch)
                    RagDetailsTab.WebSearch -> WebCitationsList(citations, ::requestOpen)
                    RagDetailsTab.KnowledgeGraph -> KnowledgeGraphList(kgPaths, references)
                }
            }
        }
    }
}

private fun initialRagDetailsTab(
    references: List<RagReference>,
    citations: List<Citation>,
    kgPaths: List<KgPath>,
    trace: RagRetrievalTrace?,
): RagDetailsTab = when {
    references.isNotEmpty() || trace != null -> RagDetailsTab.Retrieved
    citations.isNotEmpty() -> RagDetailsTab.WebSearch
    kgPaths.isNotEmpty() -> RagDetailsTab.KnowledgeGraph
    else -> RagDetailsTab.Retrieved
}

private fun RagDetailsTab.testTag(): String = when (this) {
    RagDetailsTab.Retrieved -> UiTags.RAG_DETAILS_TAB_RETRIEVED
    RagDetailsTab.WebSearch -> UiTags.RAG_DETAILS_TAB_WEB
    RagDetailsTab.KnowledgeGraph -> UiTags.RAG_DETAILS_TAB_KG
}

@Composable
private fun RetrievedReferencesList(
    references: List<RagReference>,
    trace: RagRetrievalTrace?,
    knowledgeSearch: KnowledgeSearchDetails?,
) {
    val keyedReferences = remember(references) {
        stableKeyedItems(references, ::ragReferenceStableKey)
    }
    if (keyedReferences.isEmpty() && trace == null) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        trace?.let { retrievalTrace ->
            item(key = "rag_trace_summary") { RetrievalTraceSummary(retrievalTrace) }
        }
        itemsIndexed(
            items = keyedReferences,
            key = { _, keyedReference -> keyedReference.key },
        ) { index, keyedReference ->
            val reference = keyedReference.value
            RetrievedReferenceRow(reference, index, trace?.scoring, knowledgeSearch?.hits?.get(reference.id))
            if (index != keyedReferences.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun RetrievalTraceSummary(trace: RagRetrievalTrace) {
    val summary = ragTraceSummary(trace)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .testTag(UiTags.RAG_DETAILS_TRACE),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.rag_trace_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary.headline.resolve(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            summary.hint?.let {
                Text(
                    text = it.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.rows.isNotEmpty()) {
                RetrievalTraceRows(summary.rows, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 标签列按最宽的标签对齐，取值可多行；标签过宽时（大字号、长译文）以三分之一宽度为上限换行。 */
@Composable
private fun RetrievalTraceRows(rows: List<RagTraceRow>, modifier: Modifier = Modifier) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val labels = rows.map { stringResource(it.label) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = with(density) {
        labels.maxOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }.toDp()
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val labelWidth = widest.coerceAtMost(maxWidth / 3)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = labels[index],
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(labelWidth).alignByBaseline(),
                    )
                    Column(modifier = Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.lines.forEach { line ->
                            InfinityMetaLine(
                                segments = line.map { it.resolve() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetrievedReferenceRow(
    reference: RagReference,
    index: Int,
    scoring: String?,
    hit: com.promenar.infinity.data.knowledge.KnowledgeSearchHit? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.ragDetailsReferenceItem(index))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RankLabel(hit?.citationId ?: (index + 1).toString(), monospace = hit != null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfinityIdentifierText(
                    text = reference.source.substringAfterLast('/').ifBlank {
                        stringResource(R.string.rag_details_unknown_source)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val parts = ragReferenceParts(reference, scoring)
                val change = reference.rankChange
                if (parts.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (change != null && change != 0) {
                            Icon(
                                imageVector = if (change > 0) {
                                    Icons.AutoMirrored.Rounded.TrendingUp
                                } else {
                                    Icons.AutoMirrored.Rounded.TrendingDown
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        InfinityMetaLine(
                            segments = parts.map { it.resolve() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = reference.content,
            style = MaterialTheme.typography.bodyMedium.forReading(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        hit?.let { KnowledgeHitPathRow(it) }
    }
}

/** 文档条目的读取路径：已挂载显示路径；未挂载与其他会话工作区只说明原因；历史对话没有这一行。 */
@Composable
private fun KnowledgeHitPathRow(hit: com.promenar.infinity.data.knowledge.KnowledgeSearchHit) {
    val line = knowledgeHitPathLine(hit) ?: return
    Row(
        modifier = Modifier.fillMaxWidth().testTag(KNOWLEDGE_SEARCH_PATH_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (line.isPath) Icons.Rounded.FolderOpen else Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        if (line.isPath) {
            InfinityIdentifierText(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = stringResource(line.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WebCitationsList(citations: List<Citation>, onOpen: (String) -> Unit) {
    val keyedCitations = remember(citations) {
        stableKeyedItems(citations, ::citationStableKey)
    }
    if (keyedCitations.isEmpty()) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(
            items = keyedCitations,
            key = { _, keyedCitation -> keyedCitation.key },
        ) { index, keyedCitation ->
            WebCitationRow(keyedCitation.value, index, onOpen)
        }
    }
}

@Composable
private fun WebCitationRow(citation: Citation, index: Int, onOpen: (String) -> Unit) {
    val openLabel = stringResource(R.string.rag_details_open_link)
    val unknownSource = stringResource(R.string.rag_details_unknown_source)
    val usesStackedHeader = LocalDensity.current.fontScale >= 1.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                onClickLabel = openLabel,
                role = Role.Button,
                onClick = { onOpen(citation.url) },
            )
            .testTag(UiTags.ragDetailsWebItem(index)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (usesStackedHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankLabel((index + 1).toString())
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = citation.title.ifBlank { stringResource(R.string.rag_details_unknown_webpage) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RankLabel((index + 1).toString())
                    Text(
                        text = citation.title.ifBlank { stringResource(R.string.rag_details_unknown_webpage) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = citation.source?.takeIf(String::isNotBlank) ?: unknownSource,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            citation.snippet?.takeIf(String::isNotBlank)?.let { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = citation.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun KnowledgeGraphList(kgPaths: List<KgPath>, references: List<RagReference>) {
    val keyedPaths = remember(kgPaths) {
        stableKeyedItems(kgPaths.filter { it.edges.isNotEmpty() }, ::kgPathStableKey)
    }
    if (kgPaths.isEmpty()) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "kg-summary") {
            Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                kgSummarySegments(kgPaths).forEach { text ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        keyedPaths.forEachIndexed { pathIndex, keyedPath ->
            val pathKey = keyedPath.key
            val path = keyedPath.value
            val keyedEdges = stableKeyedItems(path.edges, ::kgEdgeStableKey)
            item(key = "$pathKey:header") {
                KgPathHeader(path, pathIndex, references)
            }
            itemsIndexed(
                items = keyedEdges,
                key = { _, keyedEdge -> "$pathKey:${keyedEdge.key}" },
            ) { edgeIndex, keyedEdge ->
                val edge = keyedEdge.value
                val source = path.nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
                val target = path.nodes.find { it.id == edge.targetId }?.label ?: edge.targetId
                KgRelationshipSentence(
                    source = source,
                    relation = edge.relation,
                    target = target,
                    modifier = Modifier.testTag(UiTags.ragDetailsKgRelation(pathIndex, edgeIndex)),
                )
            }
            path.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
                item(key = "$pathKey:reasoning") {
                    Row(
                        modifier = Modifier.padding(start = 36.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall.forReading(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (pathIndex != keyedPaths.lastIndex) {
                item(key = "$pathKey:divider") {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun KgPathHeader(path: KgPath, pathIndex: Int, references: List<RagReference>) {
    val persistentLabel = stringResource(R.string.kg_source_persistent)
    val jitLabel = stringResource(R.string.kg_source_jit)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.ragDetailsKgPath(pathIndex))
            .padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RankLabel((pathIndex + 1).toString())
            Text(
                text = stringResource(R.string.rag_details_path_label, pathIndex + 1),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = path.origins.joinToString(" + ") { origin ->
                    if (origin == "persistent") persistentLabel else jitLabel
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (path.sourceDocumentIds.isNotEmpty()) {
            val sources = path.sourceDocumentIds.map { id ->
                references.firstOrNull { it.documentId == id }?.source?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: id
            }
            InfinityIdentifierText(
                text = stringResource(R.string.rag_details_kg_sources, sources.joinToString("、")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp),
            )
        }
        Text(
            text = stringResource(R.string.rag_details_keywords, path.queryKeywords.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp),
        )
    }
}

@Composable
private fun KgRelationshipSentence(
    source: String,
    relation: String,
    target: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(relation, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RankLabel(label: String, monospace: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = label,
            style = if (monospace) MaterialTheme.typography.labelLarge.copy(fontFamily = com.promenar.infinity.ui.theme.InfinityMonospace)
            else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyDetailsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Source,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.rag_details_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 解析 [RagText]：复数资源按数量取文案，参数中的 [RagText] 先行本地化。 */
@Composable
internal fun RagText.resolve(): String {
    val resolvedArgs = args.map { if (it is RagText) it.resolve() else it }.toTypedArray()
    return quantity?.let { pluralStringResource(resId, it, *resolvedArgs) } ?: stringResource(resId, *resolvedArgs)
}
