package com.promenar.infinity.ui.rag

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.CircularProgressIndicator
import com.promenar.infinity.ui.common.InfinityChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.KgEntitySources
import com.promenar.infinity.data.rag.KgNode
import com.promenar.infinity.data.rag.KgSessionOption
import com.promenar.infinity.data.rag.KgSessionTurn
import com.promenar.infinity.ui.common.InfinityCapsuleTabRow
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinityTabItem
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.rag.canvas.KgEntityKind
import com.promenar.infinity.ui.rag.canvas.kgEntityPalette
import com.promenar.infinity.ui.rag.canvas.of
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.common.ProvideGroupedPress

/** 实体头像：与画布节点同一语言，类型色描边、淡色底、类型图标。 */
@Composable
internal fun KgEntityAvatar(kind: KgEntityKind, size: Dp, modifier: Modifier = Modifier) {
    val color = kgEntityPalette().of(kind)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
internal fun KnowledgeGraphModeSelector(
    viewMode: KgViewMode,
    onModeSelected: (KgViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        KgViewMode.GLOBAL to stringResource(R.string.kg_view_global),
        KgViewMode.DOCUMENT to stringResource(R.string.kg_view_document),
        KgViewMode.SESSION to stringResource(R.string.kg_view_session),
    )
    InfinityCapsuleTabRow(
        tabs = modes.map { (mode, label) -> InfinityTabItem(title = label, testTag = UiTags.kgMode(mode.name)) },
        selectedIndex = modes.indexOfFirst { it.first == viewMode },
        onTabSelected = { index -> onModeSelected(modes[index].first) },
        modifier = modifier.fillMaxWidth(),
    )
}

/** 范围行：当前图谱、文档或会话的选择入口与规模说明。 */
@Composable
internal fun KnowledgeGraphScopeRow(
    viewMode: KgViewMode,
    scopeLabel: String,
    meta: List<String>,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        val icon: ImageVector = when (viewMode) {
            KgViewMode.GLOBAL -> Icons.Rounded.Public
            KgViewMode.DOCUMENT -> Icons.Rounded.Description
            KgViewMode.SESSION -> Icons.Rounded.ChatBubbleOutline
        }
        val pickable = true
        Surface(
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.Transparent,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .then(
                    if (pickable) {
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button, onClick = onPick)
                            .testTag(
                                when (viewMode) {
                                    KgViewMode.GLOBAL -> UiTags.KG_COLLECTION_SELECTOR
                                    KgViewMode.DOCUMENT -> UiTags.KG_DOCUMENT_SELECTOR
                                    KgViewMode.SESSION -> UiTags.KG_SESSION_SELECTOR
                                },
                            )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .padding(horizontal = InfinitySpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = scopeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pickable) {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        InfinityMetaLine(
            segments = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 类型筛选：只列出当前图谱里出现的类型，多选；未选任何类型时全部显示。 */
@Composable
internal fun KnowledgeGraphTypeFilterRow(
    counts: Map<KgEntityKind, Int>,
    selected: Set<KgEntityKind>,
    onToggle: (KgEntityKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = kgEntityPalette()
    val kinds = KgEntityKind.entries.filter { (counts[it] ?: 0) > 0 }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.Large),
    ) {
        items(kinds, key = { it.name }) { kind ->
            val color = palette.of(kind)
            val isOn = kind in selected
            InfinityChip(
                label = stringResource(R.string.kg_type_filter_chip, stringResource(kind.label), counts[kind] ?: 0),
                onClick = { onToggle(kind) },
                selected = isOn,
                leading = { Icon(kind.icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.testTag(UiTags.kgTypeFilter(kind.name)),
            )
        }
    }
}

/** 会话轮次条：柱高表示该轮抽出的实体数，点一轮聚焦它，再点或“看全部”取消。 */
@Composable
internal fun KnowledgeGraphTurnStrip(
    turns: List<KgSessionTurn>,
    selectedTurnId: String?,
    onSelectTurn: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = turns.firstOrNull { it.messageId == selectedTurnId }
    val maxCount = turns.maxOfOrNull { it.nodeIds.size }?.coerceAtLeast(1) ?: 1
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) {
        // 最近的轮次最常被查看，打开时滚到末尾。
        if (turns.isNotEmpty()) listState.scrollToItem(turns.lastIndex)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.KG_TURN_STRIP),
    ) {
        Column(modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected == null) {
                        stringResource(R.string.kg_turns_all)
                    } else {
                        stringResource(R.string.kg_turn_selected, selected.turnNumber, selected.nodeIds.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (selected != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.kg_turn_show_all),
                        onClick = { onSelectTurn(null) },
                        compact = true,
                    )
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 4.dp
                // 轮次少时铺满宽度；多时保持最小可点宽度并横向滚动。
                val itemWidth = ((maxWidth - gap * (turns.size - 1)) / turns.size.coerceAtLeast(1)).coerceIn(24.dp, 56.dp)
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                ) {
                    items(turns, key = { it.messageId }) { turn ->
                        val isOn = turn.messageId == selectedTurnId
                        val barColor = when {
                            isOn -> MaterialTheme.colorScheme.primary
                            selectedTurnId == null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        val description = stringResource(R.string.kg_turn_cd, turn.turnNumber, turn.nodeIds.size)
                        Column(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(InfinitySpacing.MinimumTouchTarget + 8.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .selectable(selected = isOn, role = Role.Tab, onClick = { onSelectTurn(turn.messageId) })
                                .clearAndSetSemantics {
                                    contentDescription = description
                                    this.selected = isOn
                                    role = Role.Tab
                                }
                                .testTag(UiTags.kgTurn(turn.messageId)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp + 26.dp * (turn.nodeIds.size.toFloat() / maxCount))
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(barColor),
                            )
                            Text(
                                text = turn.turnNumber.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = InfinitySpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.kg_extraction_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一条与选中实体相连的关系；[outgoing] 表示选中实体是关系起点。 */
internal data class KgRelationItem(
    val otherId: String,
    val otherName: String,
    val otherKind: KgEntityKind,
    val relation: String,
    val outgoing: Boolean,
)

/** 选中实体的详情面板：来源文档、出现的会话轮次与关系，点关系跳到对应实体。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnowledgeGraphEntityPanel(
    name: String,
    kind: KgEntityKind,
    detail: KgEntityDetail,
    /** 画布中与它相连的关系总数；[relations] 只取前若干条展示。 */
    relationCount: Int,
    relations: List<KgRelationItem>,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
    onPickEntity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = MaterialTheme.shapes.extraSmall.bottomStart, bottomEnd = MaterialTheme.shapes.extraSmall.bottomEnd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.KG_ENTITY_DETAIL),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Medium, bottom = InfinitySpacing.Large),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KgEntityAvatar(kind, 40.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = InfinitySpacing.Medium),
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.kg_entity_meta, stringResource(kind.label), relationCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.kg_entity_close))
                }
            }
            KgPanelSectionLabel(R.string.kg_entity_sources)
            val sources = detail.sources
            when {
                detail.isLoading -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = InfinitySpacing.Small)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
                detail.failed || sources == null -> Text(
                    stringResource(R.string.kg_entity_sources_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> KgEntitySourceRows(sources, onOpenSession)
            }
            if (relations.isNotEmpty()) {
                KgPanelSectionLabel(R.string.kg_entity_relations)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    modifier = Modifier.padding(end = InfinitySpacing.Small),
                ) {
                    relations.forEach { item -> KgRelationChip(item, onPickEntity) }
                }
            }
        }
    }
}

@Composable
private fun KgPanelSectionLabel(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = InfinitySpacing.Medium, bottom = InfinitySpacing.XSmall),
    )
}

@Composable
private fun KgEntitySourceRows(sources: KgEntitySources, onOpenSession: (String) -> Unit) {
    sources.documents.forEach { document ->
        Row(
            modifier = Modifier.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(document.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    // 同一会话的多轮合并成一行，轮次升序列出。
    val untitled = stringResource(R.string.kg_session_untitled)
    sources.sessionTurns.groupBy { it.sessionId }.forEach { (sessionId, refs) ->
        val title = refs.first().sessionTitle.ifBlank { untitled }
        val turnList = refs.map { it.turnNumber }.distinct().sorted().joinToString("、")
        Row(
            modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.kg_entity_session_turns, title, turnList),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onOpenSession(sessionId) }) {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.kg_entity_open_conversation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KgRelationChip(item: KgRelationItem, onPickEntity: (String) -> Unit) {
    val color = kgEntityPalette().of(item.otherKind)
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .widthIn(max = 280.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button) { onPickEntity(item.otherId) }
            .padding(horizontal = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.relation.isNotBlank()) {
            Text(item.relation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(
            if (item.outgoing) Icons.AutoMirrored.Rounded.ArrowForward else Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(item.otherKind.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(item.otherName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 顶栏搜索的结果下拉：选中后在画布中定位该实体。 */
@Composable
internal fun KnowledgeGraphSearchResults(
    results: List<KgNode>,
    isSearching: Boolean,
    failed: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        val phase = when {
            isSearching -> KgSearchPhase.Searching
            failed -> KgSearchPhase.Failed
            results.isEmpty() -> KgSearchPhase.Empty
            else -> KgSearchPhase.Results
        }
        AnimatedContent(targetState = phase, transitionSpec = { InfinityMotion.fadeThrough() }, label = "kg_search_phase") { shown ->
            when (shown) {
                KgSearchPhase.Searching -> KgSearchMessage(stringResource(R.string.kg_graph_searching))
                KgSearchPhase.Failed -> KgSearchMessage(stringResource(R.string.kg_graph_search_failed), MaterialTheme.colorScheme.error)
                KgSearchPhase.Empty -> KgSearchMessage(stringResource(R.string.kg_graph_search_empty))
                KgSearchPhase.Results -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(results, key = { it.id }) { entity ->
                        val kind = KgEntityKind.of(entity.type)
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { onPick(entity.id) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { KgEntityAvatar(kind, 32.dp) },
                            headlineContent = { Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                Text(stringResource(kind.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class KgSearchPhase { Searching, Failed, Empty, Results }

@Composable
private fun KgSearchMessage(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(InfinitySpacing.Large))
}

/** 画布区的说明态：需要选文档或会话、尚无会话图谱、图谱为空。 */
@Composable
internal fun KnowledgeGraphCanvasMessage(
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(InfinitySpacing.Medium))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            InfinityPillButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = InfinitySpacing.Small),
            )
        }
    }
}

/** 图谱视角的图谱列表：作品图谱与通用知识图谱，单选。 */
@Composable
internal fun KnowledgeGraphCollectionOptionsContent(
    options: List<com.promenar.infinity.data.rag.KgCollectionSummary>,
    selectedCollectionId: String?,
    onSelect: (String) -> Unit,
) {
    val sharedTitle = stringResource(R.string.kg_spaces_shared_collection_title)
    ProvideGroupedPress {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .bettboxListGroup()
                .selectableGroup(),
        ) {
            if (options.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.ks_graph_page_options_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag(UiTags.KG_COLLECTION_OPTIONS_EMPTY)
                            .padding(InfinitySpacing.Large),
                    )
                }
            } else {
                items(options, key = { it.id }) { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                            .selectable(
                                selected = option.id == selectedCollectionId,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.id) },
                            )
                            .testTag(UiTags.kgCollectionOption(option.id)),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(kgCollectionTitle(option, sharedTitle), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(stringResource(R.string.ks_graph_page_option_meta, option.documentCount, option.nodeCount))
                        },
                        trailingContent = if (option.id == selectedCollectionId) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** 图谱显示名：通用知识图谱用本地化名称，作品图谱用文件夹名。 */
internal fun kgCollectionTitle(option: com.promenar.infinity.data.rag.KgCollectionSummary, sharedTitle: String): String =
    if (option.kind == "shared") sharedTitle else option.title.ifBlank { option.rootRef.orEmpty().substringAfterLast('/') }

@Composable
internal fun KnowledgeGraphSessionOptionsContent(
    options: List<KgSessionOption>,
    selectedSessionId: String?,
    loadError: KgDocumentOptionsError?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val untitled = stringResource(R.string.kg_session_untitled)
    ProvideGroupedPress {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .bettboxListGroup()
                .selectableGroup(),
        ) {
            when {
                loadError != null -> item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTags.KG_SESSION_OPTIONS_ERROR)
                            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                    ) {
                        Text(
                            text = stringResource(R.string.kg_session_options_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (loadError.canRetry) {
                            InfinityRetryButton(onClick = onRetry, modifier = Modifier.padding(top = InfinitySpacing.Small))
                        }
                    }
                }
                options.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.kg_session_options_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag(UiTags.KG_SESSION_OPTIONS_EMPTY)
                            .padding(InfinitySpacing.Large),
                    )
                }
                else -> items(options, key = { it.sessionId }) { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = InfinitySpacing.MinimumTouchTarget)
                            .selectable(
                                selected = option.sessionId == selectedSessionId,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.sessionId) },
                            )
                            .testTag(UiTags.kgSessionOption(option.sessionId)),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(option.title.ifBlank { untitled }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.kg_session_option_meta, option.turnCount, option.entityCount)) },
                    )
                }
            }
        }
    }
}

/** 与选中实体相连的关系列表，供详情面板使用。 */
internal fun kgRelationItems(
    selectedId: String,
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    limit: Int = 12,
): List<KgRelationItem> {
    val byId = nodes.associateBy { it.id }
    return edges.asSequence()
        .filter { it.sourceId == selectedId || it.targetId == selectedId }
        .mapNotNull { edge ->
            val outgoing = edge.sourceId == selectedId
            val other = byId[if (outgoing) edge.targetId else edge.sourceId] ?: return@mapNotNull null
            KgRelationItem(other.id, other.label, KgEntityKind.of(other.type), edge.relation, outgoing)
        }
        .distinctBy { Triple(it.otherId, it.relation, it.outgoing) }
        .take(limit)
        .toList()
}

/** 当前图谱中各类型的实体数。 */
internal fun kgTypeCounts(nodes: List<GraphNode>): Map<KgEntityKind, Int> =
    nodes.groupingBy { KgEntityKind.of(it.type) }.eachCount()
