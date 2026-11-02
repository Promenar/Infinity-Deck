package com.promenar.infinity.ui.rag

import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnowledgeGraphAccessibilitySheet(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    onDismiss: () -> Unit,
    totalNodeCount: Int = nodes.size,
    canExpand: Boolean = true,
    onExpandNode: ((String) -> Unit)? = null,
) {
    val nodesById = remember(nodes) { nodes.associateBy { it.id } }
    InfinityModalSheet(
        onDismissRequest = onDismiss,
        height = InfinitySheetHeight.Full,
        modifier = Modifier.testTag(UiTags.KG_ACCESSIBILITY_LIST),
    ) {
        Text(
            text = stringResource(R.string.kg_accessibility_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = InfinitySpacing.ScreenHorizontal,
                    vertical = InfinitySpacing.Medium,
                ),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Text(stringResource(R.string.kg_graph_displayed_count, nodes.size, totalNodeCount), modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal))
            }
            item {
                Text(
                    text = stringResource(R.string.kg_nodes_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        horizontal = InfinitySpacing.ScreenHorizontal,
                        vertical = InfinitySpacing.Small,
                    ),
                )
            }
            items(nodes, key = { "node:${it.id}" }) { node ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                        .semantics { contentDescription = "${node.label}, ${node.type}" },
                    headlineContent = { Text(node.label) },
                    supportingContent = { Text(node.type) },
                    trailingContent = if (onExpandNode == null) null else {
                        {
                            val description = stringResource(R.string.kg_graph_expand_entity, node.label)
                            InfinityPillButton(
                                text = stringResource(R.string.kg_graph_expand_neighbors),
                                onClick = { onExpandNode(node.id) },
                                enabled = canExpand,
                                compact = true,
                                modifier = Modifier.semantics { contentDescription = description },
                            )
                        }
                    },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.kg_relations_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        horizontal = InfinitySpacing.ScreenHorizontal,
                        vertical = InfinitySpacing.Small,
                    ),
                )
            }
            itemsIndexed(
                items = edges,
                key = { index, edge ->
                    "edge:$index:${edge.sourceId}:${edge.targetId}:${edge.relation}"
                },
            ) { index, edge ->
                val source = nodesById[edge.sourceId]?.label ?: edge.sourceId
                val target = nodesById[edge.targetId]?.label ?: edge.targetId
                val relationDescription = stringResource(
                    R.string.kg_relation_description,
                    source,
                    edge.relation,
                    target,
                )
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                        .semantics { contentDescription = relationDescription }
                        .testTag(
                            UiTags.KG_ACCESSIBILITY_RELATION_PREFIX +
                                "$index:${edge.sourceId}-${edge.targetId}",
                        ),
                    headlineContent = { Text(relationDescription) },
                )
            }
        }
    }
}
