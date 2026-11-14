package com.promenar.infinity.ui.library

import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.rag.KgDocumentSetting
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentCollectionInfo
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.common.ProvideGroupedPress
import com.promenar.infinity.ui.common.InfinitySettingsItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedContent
import android.app.Application
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.library.ConversationFragment
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.domain.library.DocumentInsight
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.keepsFocusOnTap
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.IndexingIndicator
import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.GraphStopReason
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.recording.LOCAL_RECORDING_MIME
import com.promenar.infinity.recording.LibraryRecordingPlayback
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.LocalInfinitySettingsLeading
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.memory.LongTermMemoryContent
import com.promenar.infinity.ui.memory.LongTermMemoryEditSheet
import com.promenar.infinity.ui.memory.LongTermMemorySourceLabel
import com.promenar.infinity.ui.memory.LongTermMemoryViewModel
import com.promenar.infinity.ui.rag.KnowledgeGraphScreen
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.InfinityPropertyDivider
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinityPropertyValueAligned

/** 内容宽度达到这个值时使用宽版（文档正文 + 检视器、概览两列卡片）。 */
private val WideContentWidth = 600.dp

/** 概览里待处理与最近各显示的条数。 */
private const val OVERVIEW_PENDING = 3
private const val OVERVIEW_RECENT = 5

@Composable
internal fun LibraryPageContent(
    page: LibraryPage,
    host: LibraryPageHost,
    leading: LibraryLeading,
    trail: LibraryTrail?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val title = libraryPageTitle(page, host)
    val crumbs = trail?.let { LibraryCrumbs(it.keys.map { key -> libraryPageTitle(LibraryPageKeys.parse(key), host) }, it.onGo) }
    when (page) {
        is LibraryPage.Browse -> LibraryBrowsePage(page.ref, host, leading)
        LibraryPage.Graph -> LibraryGraphPage(host, leading)
        LibraryPage.Memory -> LibraryMemoryPage(host, title, leading, crumbs)
        is LibraryPage.Document -> LibraryDocumentPage(page.documentId, host, title, leading, crumbs)
        is LibraryPage.FragmentSession -> LibraryFragmentSessionPage(page.sessionId, host, title, leading, crumbs)
        else -> LibraryPageFrame(title, leading, crumbs, Modifier.testTag(LibraryTags.page(page.javaClass.simpleName)), actions = actions) { padding ->
            when (page) {
                LibraryPage.Overview -> LibraryOverviewContent(host, padding, entryCards = false)
                LibraryPage.Recent -> LibraryRecentContent(host, padding)
                LibraryPage.Pending -> LibraryPendingContent(host, padding)
                LibraryPage.Cited -> LibraryCitedContent(host, padding)
                LibraryPage.Fragments -> LibraryFragmentsContent(host, padding)
                LibraryPage.Files -> LibraryFilesContent(host, padding)
                LibraryPage.Ask -> LibraryAskContent(host, padding)
                else -> Unit
            }
        }
    }
}

/** 页面在左列、图标条、页头与路径条里显示的名称。 */
@Composable
internal fun libraryPageTitle(page: LibraryPage, host: LibraryPageHost): String = when (page) {
    LibraryPage.Overview -> stringResource(R.string.library_page_overview)
    LibraryPage.Recent -> stringResource(R.string.library_page_recent)
    LibraryPage.Pending -> stringResource(R.string.library_page_pending)
    LibraryPage.Cited -> stringResource(R.string.library_page_cited)
    LibraryPage.Memory -> stringResource(R.string.library_page_memory)
    LibraryPage.Fragments -> stringResource(R.string.library_page_fragments)
    LibraryPage.Graph -> stringResource(R.string.library_page_graph)
    LibraryPage.Files -> stringResource(R.string.library_page_files)
    LibraryPage.Ask -> stringResource(R.string.library_page_ask)
    is LibraryPage.Document -> {
        val recent by host.viewModel.recent.collectAsStateWithLifecycle()
        val pending by host.viewModel.pending.collectAsStateWithLifecycle()
        recent.firstOrNull { it.documentId == page.documentId }?.name
            ?: pending.firstOrNull { it.documentId == page.documentId }?.name
            ?: stringResource(R.string.library_page_document)
    }
    is LibraryPage.FragmentSession -> {
        val groups by host.viewModel.fragmentGroups.collectAsStateWithLifecycle()
        groups.firstOrNull { it.sessionId == page.sessionId }?.sessionTitle
            ?: stringResource(R.string.library_fragments_deleted_session)
    }
    is LibraryPage.Browse -> {
        val locations by host.viewModel.locations.collectAsStateWithLifecycle()
        val location = locations.locations.firstOrNull { it.id == page.ref.locationId }
        if (page.ref.path == "/" || page.ref.path.isEmpty()) {
            location?.title ?: locations.sessionNames[page.ref.locationId] ?: stringResource(R.string.library_page_files)
        } else {
            pinTitle(page.ref)
        }
    }
}

/** 资料页自画页头的页面骨架；内容边距含底部导航栏留白。 */
@Composable
internal fun LibraryPageFrame(
    title: String,
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
    modifier: Modifier = Modifier,
    meta: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        LibraryPageHeader(title = title, leading = leading, crumbs = crumbs, meta = meta, actions = actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(libraryContentPadding())
        }
    }
}

@Composable
private fun libraryContentPadding(): PaddingValues = PaddingValues(
    start = InfinitySpacing.ScreenHorizontal,
    end = InfinitySpacing.ScreenHorizontal,
    top = InfinitySpacing.Small,
    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + InfinitySpacing.XLarge,
)

// ---------------------------------------------------------------------------------------------
// 外屏首页与概览

/** 外屏首页：问资料、四张入口卡与概览。 */
@Composable
internal fun LibraryHome(host: LibraryPageHost, addMenu: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(LibraryTags.page("home"))) {
        InfinityTabHeader(title = stringResource(R.string.nav_tab_library)) { addMenu() }
        LibraryAskField(
            query = host.askQuery,
            onQueryChange = host.ask.onQueryChange,
            onSearch = host.ask.onSearch,
            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
            onFocused = host.ask.onOpen,
        )
        Box(Modifier.weight(1f)) {
            LibraryOverviewContent(host, libraryContentPadding(), entryCards = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryOverviewContent(host: LibraryPageHost, padding: PaddingValues, entryCards: Boolean) {
    val vm = host.viewModel
    val overview by vm.overview.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val busy by vm.busyDocuments.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideContentWidth
        val pendingCard: @Composable () -> Unit = {
            LibraryPendingCard(
                pending = pending,
                busy = busy,
                onViewAll = { host.navigator.openSection(LibraryPageKeys.PENDING) },
                onOpen = { host.navigator.push(LibraryPageKeys.document(it.documentId)) },
                onResolve = { vm.resolve(it) },
            )
        }
        val recentCard: @Composable () -> Unit = {
            LibraryRecentCard(
                recent = recent,
                onViewAll = { host.navigator.openSection(LibraryPageKeys.RECENT) },
                onOpen = { host.navigator.push(LibraryPageKeys.document(it.documentId)) },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            item(key = "kpi") {
                // 外屏首页：统计卡兼作四个入口，不再另放入口卡；内屏左列已有入口，统计卡选中对应项。
                LibraryKpiGrid(overview, entryCards, wide) { entry, section ->
                    if (entryCards) host.navigator.push(entry) else host.navigator.openSection(section)
                }
            }
            if (wide) {
                item(key = "columns") {
                    LibraryOverviewColumns(pending = pendingCard, cited = { RecentCitedCard(host) }, recent = recentCard)
                }
            } else {
                item(key = "pending") { pendingCard() }
                item(key = "recent") { recentCard() }
                item(key = "cited") { RecentCitedCard(host) }
            }
        }
    }
}

/**
 * 宽版概览排成两列：左列待处理、最近被引用，右列最近加入。
 * 两列各自从上往下堆叠，卡片高度随自身内容，不与另一列的卡片等高。
 */
@Composable
internal fun LibraryOverviewColumns(
    pending: @Composable () -> Unit,
    cited: @Composable () -> Unit,
    recent: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
            pending()
            cited()
        }
        Box(Modifier.weight(1f)) { recent() }
    }
}

/** 概览“待处理”卡：最多列出 [OVERVIEW_PENDING] 条，更多时给出“查看全部”。 */
@Composable
internal fun LibraryPendingCard(
    pending: List<PendingDocument>,
    busy: Set<String>,
    onViewAll: () -> Unit,
    onOpen: (PendingDocument) -> Unit,
    onResolve: (PendingDocument) -> Unit,
) {
    LibraryCard(
        title = stringResource(R.string.library_pending_title),
        count = pending.size,
        onViewAll = if (pending.size > OVERVIEW_PENDING) onViewAll else null,
    ) {
        if (pending.isEmpty()) {
            LibraryEmptyText(stringResource(R.string.library_pending_empty))
        } else {
            pending.take(OVERVIEW_PENDING).forEach { doc ->
                PendingRow(doc, busy = doc.documentId in busy, onOpen = { onOpen(doc) }, onResolve = { onResolve(doc) })
            }
        }
    }
}

/** 概览“最近加入”卡：最多列出 [OVERVIEW_RECENT] 条，更多时给出“查看全部”。 */
@Composable
internal fun LibraryRecentCard(
    recent: List<LibraryDocumentSummary>,
    onViewAll: () -> Unit,
    onOpen: (LibraryDocumentSummary) -> Unit,
) {
    LibraryCard(
        title = stringResource(R.string.library_recent_title),
        onViewAll = if (recent.size > OVERVIEW_RECENT) onViewAll else null,
    ) {
        if (recent.isEmpty()) {
            LibraryEmptyText(stringResource(R.string.library_recent_empty))
        } else {
            recent.take(OVERVIEW_RECENT).forEach { doc -> DocumentRow(doc, onOpen = { onOpen(doc) }) }
        }
    }
}

/** 概览统计卡：宽版一行四张，窄版两列等高；[entryCards] 时兼作入口，带页面图标与页面名。 */
@Composable
internal fun LibraryKpiGrid(
    overview: LibraryOverview?,
    entryCards: Boolean,
    wide: Boolean,
    open: (entry: String, section: String) -> Unit,
) {
    val o = overview
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { m ->
            KpiTile(
                label = stringResource(R.string.library_kpi_documents),
                value = o?.indexedDocuments,
                onClick = { open(LibraryPageKeys.FILES, LibraryPageKeys.RECENT) },
                entry = if (entryCards) KpiEntry(LibraryPageKeys.FILES, Icons.Rounded.Description, stringResource(R.string.library_page_files), showLabel = true) else null,
                modifier = m,
            )
        },
        { m ->
            KpiTile(
                label = stringResource(R.string.library_kpi_memories),
                value = o?.longTermMemories,
                detail = o?.newLongTermMemories?.takeIf { it > 0 }?.let { stringResource(R.string.library_kpi_memories_new, it) },
                onClick = { open(LibraryPageKeys.MEMORY, LibraryPageKeys.MEMORY) },
                entry = if (entryCards) KpiEntry(LibraryPageKeys.MEMORY, Icons.Rounded.Psychology, stringResource(R.string.library_page_memory)) else null,
                modifier = m,
            )
        },
        { m ->
            KpiTile(
                label = stringResource(R.string.library_kpi_graph),
                value = o?.graphEntities,
                detail = o?.graphRelations?.let { stringResource(R.string.library_kpi_graph_relations, it) },
                onClick = { open(LibraryPageKeys.GRAPH, LibraryPageKeys.GRAPH) },
                entry = if (entryCards) KpiEntry(LibraryPageKeys.GRAPH, Icons.Rounded.Hub, stringResource(R.string.library_page_graph), showLabel = true) else null,
                modifier = m,
            )
        },
        { m ->
            KpiTile(
                label = stringResource(R.string.library_kpi_fragments),
                value = o?.fragments,
                onClick = { open(LibraryPageKeys.FRAGMENTS, LibraryPageKeys.FRAGMENTS) },
                entry = if (entryCards) KpiEntry(LibraryPageKeys.FRAGMENTS, Icons.Rounded.Forum, stringResource(R.string.library_page_fragments)) else null,
                modifier = m,
            )
        },
    )
    // 固定列数逐行排布并按行等高，大字号下长标题换行而不是把卡片挤到单独一行。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        tiles.chunked(if (wide) 4 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** 统计卡兼作入口时的页面键、图标与页面名。 */
private data class KpiEntry(val key: String, val icon: ImageVector, val title: String, val showLabel: Boolean = false)

@Composable
private fun KpiTile(
    label: String,
    value: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    entry: KpiEntry? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 88.dp).then(entry?.let { Modifier.testTag(LibraryTags.navItem(it.key)) } ?: Modifier),
    ) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 图标单独一行，页面名占满卡片宽度，大字号英文长名按词换行而不是在词中断开。
            entry?.let {
                Icon(it.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    it.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XSmall),
                )
            }
            // 数值变化时纵向替换：增加自下而上，减少自上而下。
            AnimatedContent(
                targetState = value,
                transitionSpec = { InfinityMotion.sharedAxisY(forward = (targetState ?: 0) >= (initialState ?: 0)) },
                label = "kpi-value",
            ) { shown -> Text(shown?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall) }
            // 兼作入口时只在统计口径与页面名不同（已索引文档、图谱实体）时补一行说明。
            if (entry == null || entry.showLabel) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun LibraryCard(
    title: String,
    count: Int? = null,
    onViewAll: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    content: @Composable () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(InfinityMotion.contentSizeSpec()).padding(vertical = InfinitySpacing.Small)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = titleStyle ?: MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                count?.takeIf { it > 0 }?.let {
                    Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(InfinitySpacing.Small))
                }
                onViewAll?.let { TextButton(onClick = it) { Text(stringResource(R.string.library_view_all), maxLines = 1) } }
            }
            content()
        }
    }
}

@Composable
internal fun LibraryEmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
    )
}

@Composable
private fun pendingStatusText(doc: PendingDocument): String = when (doc.kind) {
    PendingKind.FAILED -> doc.reason?.let { stringResource(R.string.library_notice_with_reason, stringResource(R.string.library_pending_failed), it) }
        ?: stringResource(R.string.library_pending_failed)
    PendingKind.GRAPH_ATTENTION -> doc.reason?.let { stringResource(R.string.library_notice_with_reason, stringResource(R.string.library_graph_attention), it) }
        ?: stringResource(R.string.library_graph_attention)
    PendingKind.QUEUED -> doc.queuePosition?.let { stringResource(R.string.library_pending_queued, it) } ?: stringResource(R.string.library_pending_queued_plain)
    PendingKind.INDEXING -> indexingStatusText(doc)
    PendingKind.STALE -> stringResource(R.string.library_pending_stale)
}

@Composable
private fun indexingStatusText(doc: PendingDocument): String {
    val processed = doc.processed
    val total = doc.total?.takeIf { it > 0 }
    return when (doc.stage) {
        PendingStage.VECTORIZING -> when {
            doc.lastError != null -> stringResource(R.string.library_pending_retrying, doc.lastError)
            total != null && processed != null -> stringResource(R.string.library_pending_vectorizing_progress, processed, total)
            else -> stringResource(R.string.library_pending_vectorizing)
        }
        PendingStage.EXTRACTING_GRAPH ->
            if (total != null && processed != null) stringResource(R.string.library_pending_graph_progress, processed, total)
            else stringResource(R.string.library_pending_graph)
        PendingStage.SAVING -> stringResource(R.string.library_pending_saving)
        PendingStage.PREPARING -> stringResource(R.string.library_pending_preparing)
        null -> stringResource(R.string.library_pending_indexing)
    }
}

@Composable
private fun PendingRow(doc: PendingDocument, busy: Boolean, onOpen: () -> Unit, onResolve: () -> Unit) {
    val icon = when (doc.kind) {
        PendingKind.FAILED -> Icons.Rounded.ErrorOutline
        PendingKind.GRAPH_ATTENTION -> Icons.Rounded.Hub
        PendingKind.QUEUED -> Icons.Rounded.HourglassTop
        PendingKind.INDEXING -> Icons.Rounded.Sync
        PendingKind.STALE -> Icons.Rounded.Edit
    }
    val tint = if (doc.kind == PendingKind.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Small, bottom = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(InfinitySpacing.RowIconSize))
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 状态单行：索引指示在前、文字在后，列表各项等高；完整错误进文档页查看。
            PendingStatusLine(doc, tint)
            Text(doc.folderPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val action = when (doc.kind) {
            PendingKind.FAILED -> R.string.library_action_retry
            PendingKind.GRAPH_ATTENTION -> if (doc.graphConfirmation) R.string.library_action_confirm_graph else R.string.library_action_retry_graph
            PendingKind.STALE -> R.string.library_action_rebuild
            else -> null
        }
        if (action != null) {
            FilledTonalButton(onClick = onResolve, enabled = !busy) { Text(stringResource(action), maxLines = 1) }
        }
    }
}

/** 一行状态：有索引进度时先画与生成指示同风格的索引指示；行高固定，有无指示的行一样高。 */
@Composable
private fun PendingStatusLine(doc: PendingDocument, color: Color, style: TextStyle = MaterialTheme.typography.labelMedium) {
    Row(
        Modifier.heightIn(min = PendingStatusLineHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        doc.indexMotionState()?.let { IndexingIndicator(it) }
        Text(pendingStatusText(doc), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 最高的索引指示（两种 ∞）为 16dp。 */
private val PendingStatusLineHeight = 18.dp

@Composable
private fun DocumentRow(doc: LibraryDocumentSummary, onOpen: () -> Unit) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(InfinitySpacing.RowIconSize))
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            InfinityMetaLine(
                listOf(
                    doc.folderPath,
                    Formatter.formatShortFileSize(context, doc.sizeBytes),
                    DateUtils.getRelativeTimeSpanString(doc.lastModified, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                // 元数据单行：长路径不把个别行撑高，列表各项等高。
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 最近、待处理与外屏“文件”页

@Composable
private fun LibraryRecentContent(host: LibraryPageHost, padding: PaddingValues) {
    val recent by host.viewModel.recent.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (recent.isEmpty()) item(key = "empty") { LibraryEmptyText(stringResource(R.string.library_recent_empty)) }
        itemsIndexed(recent, key = { _, it -> it.documentId }) { index, doc ->
            ProvideGroupedPress {
                Box(Modifier.animateItem().bettboxListGroupItem(index, recent.size)) {
                    DocumentRow(doc, onOpen = { host.navigator.push(LibraryPageKeys.document(doc.documentId)) })
                }
            }
        }
    }
}

@Composable
private fun LibraryPendingContent(host: LibraryPageHost, padding: PaddingValues) {
    val pending by host.viewModel.pending.collectAsStateWithLifecycle()
    val busy by host.viewModel.busyDocuments.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (pending.isEmpty()) item(key = "empty") { LibraryEmptyText(stringResource(R.string.library_pending_empty)) }
        itemsIndexed(pending, key = { _, it -> it.documentId }) { index, doc ->
            ProvideGroupedPress {
                Box(Modifier.animateItem().bettboxListGroupItem(index, pending.size)) {
                    PendingRow(
                        doc,
                        busy = doc.documentId in busy,
                        onOpen = { host.navigator.push(LibraryPageKeys.document(doc.documentId)) },
                        onResolve = { host.viewModel.resolve(doc) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFilesContent(host: LibraryPageHost, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
        LibraryLocationRows(host)
    }
}

// ---------------------------------------------------------------------------------------------
// 文档详情：阅读优先，“智能体视角”在宽版为右侧检视器、窄版在正文下方。

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T?) : Loaded<T>
}

@Composable
private fun LibraryDocumentPage(
    documentId: String,
    host: LibraryPageHost,
    title: String,
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
) {
    val pendingList by host.viewModel.pending.collectAsStateWithLifecycle()
    val livePending = pendingList.firstOrNull { it.documentId == documentId }
    // 阶段变化（含离开待处理列表）时重读快照，图谱状态与实体随之更新；进度数字不触发重读。
    val insight by produceState<Loaded<DocumentInsight>>(Loaded.Loading, documentId, livePending?.kind, livePending?.stage) {
        value = Loaded.Ready(host.viewModel.insight(documentId))
    }
    val body by produceState<Loaded<DocumentBody>>(Loaded.Loading, documentId) {
        value = Loaded.Ready(host.viewModel.body(documentId))
    }
    val rootUuid by host.workspaceRootUuid.collectAsStateWithLifecycle()
    val resolvedTitle = (insight as? Loaded.Ready)?.value?.name ?: title
    // 从问资料结果进入：定位到本篇的命中段落；从对话引用进入：定位到被引用的段落。
    val askState by host.askViewModel.state.collectAsStateWithLifecycle()
    val fromAsk = LibraryPageKeys.ASK in host.navigator.stack
    val request = host.locateRequest?.takeIf { it.documentId == documentId }
        ?: if (fromAsk) DocumentLocateRequest.fromAsk(documentId, askState.hitsForDocument(documentId)) else null
    val readyBody = (body as? Loaded.Ready)?.value
    val located = remember(readyBody, request) {
        if (readyBody == null || request == null) null else locateRequest(readyBody, request, host.chunkLocator)
    }
    var focus by rememberSaveable(documentId, located?.hitCount) { mutableIntStateOf(0) }
    val bodyScroll = rememberScrollState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    val locateBar: @Composable () -> Unit = {
        if (located != null && request != null) {
            DocumentLocateBar(
                hitCount = located.hitCount,
                focus = focus,
                cited = !request.fromAsk,
                onMove = { delta -> focus = (focus + delta).mod(located.hitCount) },
            )
        }
    }
    val bodyModifier = Modifier.onGloballyPositioned { viewportTop = it.positionInRoot().y }.verticalScroll(bodyScroll)
    val readyInsight = (insight as? Loaded.Ready)?.value
    val notFound = insight is Loaded.Ready && readyInsight == null
    val busy by host.viewModel.busyDocuments.collectAsStateWithLifecycle()
    val attention = readyInsight?.let { documentAttention(documentPending(it, livePending)?.kind, it.graph) }
    // 提示条退场期间沿用最近一次的内容。
    val heldAttention = remember(documentId) { LatestValue<DocumentAttention>() }
    if (attention != null) heldAttention.value = attention
    var showDetails by rememberSaveable(documentId) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideContentWidth
        LibraryPageFrame(
            title = resolvedTitle,
            leading = leading,
            crumbs = crumbs,
            modifier = Modifier.testTag(LibraryTags.page("document")),
            meta = readyInsight?.let { info ->
                {
                    Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(documentGraphText(info.graph, livePending), softWrap = false, maxLines = 1)
                }
            },
            actions = {
                // 窄屏的详情与被引用收进底部面板，正文再长也能直接打开。
                if (!wide && !notFound) {
                    val attentionText = attention?.let { stringResource(documentAttentionText(it)) }
                    IconButton(onClick = { showDetails = true }, modifier = Modifier.testTag("library_doc_details")) {
                        BadgedBox(
                            badge = {
                                if (attentionText != null) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { contentDescription = attentionText },
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.library_doc_details))
                        }
                    }
                }
                if (!notFound) LibraryRecordingPlayback(documentId)
                val root = rootUuid
                if (root != null) {
                    IconButton(onClick = { host.onNavigateToSecondary(NavDestinations.docEditor(root, documentId)) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.library_doc_edit))
                    }
                }
            },
        ) { padding ->
            if (notFound) {
                LibraryEmptyText(stringResource(R.string.library_doc_not_found))
                return@LibraryPageFrame
            }
            val info = readyInsight
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f)) {
                        locateBar()
                        Column(Modifier.weight(1f).then(bodyModifier).padding(padding)) {
                            DocumentBodyView(body, located, focus, bodyScroll) { viewportTop }
                        }
                    }
                    Column(
                        Modifier
                            .width(280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = padding.calculateTopPadding(), end = InfinitySpacing.ScreenHorizontal, bottom = padding.calculateBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                    ) {
                        info?.let { DocumentDetailCard(it, livePending, host) }
                        DocumentCitationsCard(documentId, host, titleStyle = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    locateBar()
                    AnimatedVisibility(visible = attention != null) {
                        val shown = attention ?: heldAttention.value
                        if (info != null && shown != null) {
                            DocumentAttentionBar(
                                attention = shown,
                                enabled = info.documentId !in busy,
                                onAction = { host.viewModel.resolveAttention(shown, info) },
                                modifier = Modifier.padding(start = InfinitySpacing.ScreenHorizontal, end = InfinitySpacing.ScreenHorizontal, top = InfinitySpacing.Small),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).then(bodyModifier).padding(padding)) {
                        DocumentBodyView(body, located, focus, bodyScroll) { viewportTop }
                    }
                }
            }
        }
        InfinityBottomSheet(
            show = showDetails && !wide && !notFound,
            onDismiss = { showDetails = false },
            title = stringResource(R.string.library_doc_agent_view),
            scrollContent = true,
            dragGesturesEnabled = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
                readyInsight?.let { info ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = InfinitySpacing.Small)) {
                            DocumentDetailSheet(info, livePending, host)
                        }
                    }
                }
                DocumentCitationsCard(documentId, host, titleStyle = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun DocumentBodyView(
    body: Loaded<DocumentBody>,
    located: LocatedBody?,
    focus: Int,
    scroll: ScrollState,
    viewportTop: () -> Float,
) {
    AnimatedContent(targetState = body, contentKey = { it is Loaded.Loading }, transitionSpec = { InfinityMotion.fadeThrough() }, label = "doc-body") {
        DocumentBodyState(it, located, focus, scroll, viewportTop)
    }
}

@Composable
private fun DocumentBodyState(
    body: Loaded<DocumentBody>,
    located: LocatedBody?,
    focus: Int,
    scroll: ScrollState,
    viewportTop: () -> Float,
) {
    when (body) {
        Loaded.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            com.promenar.infinity.ui.common.InfinityLoadingIndicator()
        }
        is Loaded.Ready -> {
            val value = body.value
            if (value == null || value.text.isBlank()) {
                LibraryEmptyText(stringResource(R.string.library_doc_no_body))
                return
            }
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                if (value.extracted) {
                    Text(stringResource(R.string.library_doc_extracted), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SelectionContainer {
                    if (located != null) {
                        LocatedBodySegments(located, markdown = value.markdown && !located.plainText, focus = focus, scroll = scroll, viewportTop = viewportTop)
                    } else if (value.markdown) {
                        MarkdownText(markdown = value.text, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(value.text, style = MaterialTheme.typography.bodyMedium.forReading())
                    }
                }
                if (value.truncated) {
                    Text(stringResource(R.string.library_doc_truncated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 文档的索引状态：进度随待处理列表实时更新；列表里没有这篇时按快照显示。 */
private fun documentPending(info: DocumentInsight, livePending: PendingDocument?): PendingDocument? =
    livePending ?: info.pending?.let { PendingDocument(info.documentId, info.ref, info.name, info.folderPath, it, info.failureReason) }

/** 宽屏右栏的详情卡。 */
@Composable
private fun DocumentDetailCard(info: DocumentInsight, livePending: PendingDocument?, host: LibraryPageHost) {
    LibraryCard(title = stringResource(R.string.library_doc_agent_view), titleStyle = MaterialTheme.typography.titleMedium) {
        DocumentDetailSheet(info, livePending, host)
    }
}

/** 文档详情属性表，分索引、图谱与合集、文件三段；宽屏右栏与窄屏底部面板共用。 */
@Composable
private fun DocumentDetailSheet(info: DocumentInsight, livePending: PendingDocument?, host: LibraryPageHost) {
    val context = LocalContext.current
    val busy by host.viewModel.busyDocuments.collectAsStateWithLifecycle()
    val enabled = info.documentId !in busy
    val pending = documentPending(info, livePending)
    InfinityPropertySheet {
        DocumentStatusRow(info, pending, enabled, host)
        InfinityPropertyRow(stringResource(R.string.library_doc_chunks), info.chunkCount.toString())
        InfinityPropertyDivider()
        if (info.mimeType == LOCAL_RECORDING_MIME) {
            InfinityPropertyRow(
                label = stringResource(R.string.recording_library_ui_document),
                value = stringResource(R.string.recording_local_document),
            )
        } else {
            DocumentGraphRows(info, pending, enabled, host)
        }
        InfinityPropertyDivider()
        InfinityPropertyRow(
            label = stringResource(R.string.library_doc_location),
            value = info.folderPath,
            supporting = listOf(
                Formatter.formatShortFileSize(context, info.sizeBytes),
                DateUtils.formatDateTime(context, info.lastModified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
            ).joinToString(" $InfinityMetaSeparator "),
        )
        info.source?.let { InfinityPropertyRow(stringResource(R.string.library_doc_source), it) }
        if (info.versionCount > 0) InfinityPropertyRow(stringResource(R.string.library_doc_versions), info.versionCount.toString())
    }
}

/** 详情“状态”行的短状态；失败原因另起一行显示。 */
@Composable
private fun pendingStatusLabel(doc: PendingDocument): String = when (doc.kind) {
    PendingKind.FAILED -> stringResource(R.string.library_pending_failed)
    PendingKind.GRAPH_ATTENTION -> stringResource(R.string.library_graph_attention)
    else -> pendingStatusText(doc)
}

/** “状态”行：已索引或进度（带索引指示）；失败或过期时取值用 error 色，行尾放重试或重建。 */
@Composable
private fun DocumentStatusRow(info: DocumentInsight, pending: PendingDocument?, enabled: Boolean, host: LibraryPageHost) {
    val problem = pending?.kind == PendingKind.FAILED || pending?.kind == PendingKind.STALE
    val color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val action = when (pending?.kind) {
        PendingKind.FAILED -> R.string.library_action_retry
        PendingKind.STALE -> R.string.library_action_rebuild
        else -> null
    }
    val reason = pending?.reason?.takeIf {
        pending.kind == PendingKind.FAILED || pending.kind == PendingKind.STALE || pending.kind == PendingKind.GRAPH_ATTENTION
    }
    InfinityPropertyRow(
        label = stringResource(R.string.library_doc_status),
        valueContent = {
            if (pending == null) {
                Text(stringResource(R.string.library_doc_status_indexed), style = MaterialTheme.typography.bodyMedium, color = color)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    pending.indexMotionState()?.let { IndexingIndicator(it) }
                    Text(pendingStatusLabel(pending), style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
        },
        supporting = reason,
        trailing = if (action == null) null else {
            {
                FilledTonalButton(
                    onClick = { if (pending?.kind == PendingKind.FAILED) host.viewModel.retry(info.documentId) else host.viewModel.rebuild(info.documentId) },
                    enabled = enabled,
                ) { Text(stringResource(action), maxLines = 1) }
            }
        },
    )
}

/** 文档知识图谱状态的一行文字；文档详情“知识图谱”行与内屏页头信息行共用。 */
@Composable
internal fun documentGraphText(graph: DocumentGraphState, pending: PendingDocument?): String = when (graph) {
        DocumentGraphState.NotExtracted -> stringResource(R.string.library_graph_state_none)
        is DocumentGraphState.Extracting -> {
            val progress = liveGraphProgress(graph.processed, graph.total, pending)
            if (progress != null) stringResource(R.string.library_graph_state_extracting_progress, progress.first, progress.second)
            else stringResource(R.string.library_graph_state_extracting)
        }
        is DocumentGraphState.Complete -> stringResource(R.string.library_graph_state_complete, graph.entities, graph.relations)
        is DocumentGraphState.Partial -> stringResource(
            R.string.library_graph_state_partial,
            graph.processed,
            graph.total,
            stringResource(
                when (graph.reason) {
                    GraphStopReason.CALLS -> R.string.library_graph_budget_calls
                    GraphStopReason.OUTPUT_TOKENS -> R.string.library_graph_budget_output
                    GraphStopReason.DURATION -> R.string.library_graph_budget_duration
                },
            ),
        )
        is DocumentGraphState.ConfirmationRequired -> stringResource(R.string.library_graph_state_confirm)
        is DocumentGraphState.Failed -> graph.reason
    }

/**
 * 详情第二段：知识图谱状态与动作（抽取、确认用量或重试）、实体标签、所属合集与合集归属。
 * 抽取中的窗口数随待处理列表实时更新。
 */
@Composable
private fun DocumentGraphRows(info: DocumentInsight, pending: PendingDocument?, enabled: Boolean, host: LibraryPageHost) {
    val graph = info.graph
    val text = documentGraphText(graph, pending)
    val app = LocalContext.current.applicationContext as InfinityApplication
    var reextract by remember(info.documentId) { mutableStateOf(false) }
    var openSettings by remember(info.documentId) { mutableStateOf(false) }
    var openAssignment by remember(info.documentId) { mutableStateOf(false) }
    var assignmentVersion by remember(info.documentId) { mutableIntStateOf(0) }
    // 所属合集与篇序随图谱状态刷新：抽取完成后文档才写入合集成员。
    val collection by produceState<KgDocumentCollectionInfo?>(null, info.documentId, graph, assignmentVersion) {
        value = try {
            withContext(Dispatchers.IO) { host.collections.info(info.documentId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("[Library] 读取所属合集失败", failure)
            null
        }
    }
    // 合集归属在抽取前也可设置，下次抽取直接写入选定的合集。
    val assignment by produceState<KgDocumentAssignment?>(null, info.documentId, graph, assignmentVersion) {
        value = try {
            withContext(Dispatchers.IO) { host.collections.assignment(info.documentId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("[Library] 读取合集归属失败", failure)
            null
        }
    }
    // 文档还在排队、索引或解析失败时，先处理索引本身。
    val indexBusy = pending?.kind in setOf(PendingKind.QUEUED, PendingKind.INDEXING, PendingKind.FAILED)
    val action: Pair<Int, () -> Unit>? = if (indexBusy) null else when (graph) {
        DocumentGraphState.NotExtracted, is DocumentGraphState.ConfirmationRequired ->
            (if (graph is DocumentGraphState.ConfirmationRequired) R.string.library_action_confirm_graph else R.string.library_action_extract_graph) to
                { host.viewModel.requestGraphExtraction(info.documentId, info.name) }
        is DocumentGraphState.Partial, is DocumentGraphState.Failed ->
            R.string.library_action_retry_graph to { host.viewModel.retry(info.documentId) }
        // 换了模型或提示词后可重新抽取，与批量抽取走同一个用量确认；旧图谱在新结果提交前保留。
        is DocumentGraphState.Complete -> R.string.kgc_action_reextract to { reextract = host.collections.editable }
        is DocumentGraphState.Extracting -> null
    }
    InfinityPropertyRow(
        label = stringResource(R.string.library_graph_row),
        value = text,
        trailing = if (action == null) null else {
            {
                FilledTonalButton(onClick = action.second, enabled = enabled, modifier = Modifier.testTag("library_doc_graph_action")) {
                    Text(stringResource(action.first), maxLines = 1)
                }
            }
        },
    )
    // 尚未抽取时由上一行说明状态，不再重复一行。
    if (info.entities.isNotEmpty()) {
        InfinityPropertyValueAligned(Modifier.padding(bottom = InfinitySpacing.Small)) {
            DocumentEntityChips(info.entities)
        }
    }
    collection?.let { current ->
        val ordinal = current.ordinal
        InfinityPropertyRow(
            label = stringResource(R.string.kgc_doc_collection_row),
            value = if (ordinal != null) stringResource(R.string.kgc_doc_collection_value, current.title, ordinal) else current.title,
            onClick = { openSettings = host.collections.editable },
            modifier = Modifier.testTag("library_doc_collection"),
        )
        if (openSettings) {
            KgCollectionSettingsHost(app, current.title, folderId = null, collectionId = current.collectionId) { openSettings = false }
        }
    }
    assignment?.let { current ->
        InfinityPropertyRow(
            label = stringResource(R.string.kda_row),
            value = when (current.mode) {
                KgDocumentSetting.FOLLOW_FOLDER -> stringResource(R.string.kda_row_follow, current.folderCollectionTitle)
                KgDocumentSetting.COLLECTION -> collection?.title?.let { stringResource(R.string.kda_row_collection, it) }
                    ?: stringResource(R.string.kda_mode_collection)
                else -> stringResource(current.mode.rowLabel())
            },
            onClick = { openAssignment = host.collections.editable },
            modifier = Modifier.testTag("library_doc_assignment_row"),
        )
    }
    if (openAssignment) {
        DocumentCollectionAssignmentSheet(
            app = app,
            documentId = info.documentId,
            documentName = info.name,
            onChanged = { assignmentVersion++ },
            onDismiss = { openAssignment = false },
        )
    }
    if (reextract) {
        KgBatchExtractionHost(app, KgBatchTarget.Documents(listOf(info.documentId)), onQueued = host.viewModel::reportGraphQueued) {
            reextract = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentEntityChips(entities: List<String>) {
    InfinityTagScrollRow(spacing = InfinitySpacing.XSmall) {
        entities.forEach { entity ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    entity,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.XSmall),
                )
            }
        }
    }
}

/** 只保存最近一次非空值，供提示条退场动画期间继续显示原内容；不触发重组。 */
private class LatestValue<T : Any> {
    var value: T? = null
}

/** 窄屏正文上方的提示条：左侧状态文字，右侧对应动作；索引失败用 errorContainer 配色。 */
@Composable
private fun DocumentAttentionBar(attention: DocumentAttention, enabled: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val failed = attention == DocumentAttention.INDEX_FAILED
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().testTag("library_doc_attention"),
    ) {
        Row(
            Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(stringResource(documentAttentionText(attention)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = content),
                modifier = Modifier.testTag("library_doc_attention_action"),
            ) { Text(stringResource(documentAttentionAction(attention)), maxLines = 1) }
        }
    }
}

private fun documentAttentionText(attention: DocumentAttention): Int = when (attention) {
    DocumentAttention.INDEX_FAILED -> R.string.library_pending_failed
    DocumentAttention.STALE -> R.string.library_pending_stale
    DocumentAttention.GRAPH_CONFIRM -> R.string.library_graph_state_confirm
}

private fun documentAttentionAction(attention: DocumentAttention): Int = when (attention) {
    DocumentAttention.INDEX_FAILED -> R.string.library_action_retry
    DocumentAttention.STALE -> R.string.library_action_rebuild
    DocumentAttention.GRAPH_CONFIRM -> R.string.library_action_confirm_graph
}

/** 提示条动作复用文档页已有的 ViewModel 调用。 */
private fun LibraryViewModel.resolveAttention(attention: DocumentAttention, info: DocumentInsight) {
    when (attention) {
        DocumentAttention.INDEX_FAILED -> retry(info.documentId)
        DocumentAttention.STALE -> rebuild(info.documentId)
        DocumentAttention.GRAPH_CONFIRM -> requestGraphExtraction(info.documentId, info.name)
    }
}

// ---------------------------------------------------------------------------------------------
// 对话片段

@Composable
private fun LibraryFragmentsContent(host: LibraryPageHost, padding: PaddingValues) {
    val groups by host.viewModel.fragmentGroups.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "desc") {
            Text(
                stringResource(R.string.library_fragments_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = InfinitySpacing.Medium),
            )
        }
        if (groups.isEmpty()) item(key = "empty") { LibraryEmptyText(stringResource(R.string.library_fragments_empty)) }
        itemsIndexed(groups, key = { _, it -> it.sessionId }) { index, group ->
            ProvideGroupedPress {
                FragmentGroupRow(
                    group,
                    onOpen = { host.navigator.push(LibraryPageKeys.fragmentSession(group.sessionId)) },
                    modifier = Modifier.animateItem().bettboxListGroupItem(index, groups.size),
                )
            }
        }
    }
}

/** 对话片段的会话行：与设置页列表行相同的几何，副标题为智能体、片段数与最近时间。 */
@Composable
internal fun FragmentGroupRow(group: FragmentGroup, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember { System.currentTimeMillis() }
    InfinitySettingsItem(
        icon = Icons.Rounded.Forum,
        title = group.sessionTitle ?: stringResource(R.string.library_fragments_deleted_session),
        titleColor = if (group.sessionTitle == null) MaterialTheme.colorScheme.onSurfaceVariant else null,
        subtitleSegments = listOfNotNull(
            group.agentName,
            pluralStringResource(R.plurals.library_fragment_count, group.fragmentCount, group.fragmentCount),
            DateUtils.getRelativeTimeSpanString(group.lastAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        ),
        // 大字号下元数据折到第二行，不截掉时间。
        subtitleMaxLines = 2,
        onClick = onOpen,
        showChevron = true,
        modifier = modifier,
    )
}

@Composable
private fun LibraryFragmentSessionPage(
    sessionId: String,
    host: LibraryPageHost,
    title: String,
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
) {
    val fragments by remember(sessionId) { host.viewModel.fragments(sessionId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LibraryPageFrame(
        title = title,
        leading = leading,
        crumbs = crumbs,
        modifier = Modifier.testTag(LibraryTags.page("fragment_session")),
        actions = {
            if (fragments.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.library_fragments_clear), maxLines = 1, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            if (fragments.isEmpty()) item(key = "empty") { LibraryEmptyText(stringResource(R.string.library_fragments_empty)) }
            items(fragments, key = { it.id }) { fragment ->
                Box(Modifier.animateItem()) { FragmentRow(fragment, onDelete = { host.viewModel.deleteFragment(fragment.id) }) }
            }
        }
    }
    if (confirmClear) {
        InfinityConfirmDialog(
            title = stringResource(R.string.library_fragments_clear_title),
            message = stringResource(R.string.library_fragments_clear_message),
            confirmText = stringResource(R.string.library_fragments_clear),
            onConfirm = {
                confirmClear = false
                host.viewModel.clearFragments(sessionId)
                host.navigator.back()
            },
            onCancel = { confirmClear = false },
            isDestructive = true,
        )
    }
}

@Composable
private fun FragmentRow(fragment: ConversationFragment, onDelete: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = InfinitySpacing.Large, top = InfinitySpacing.Small, bottom = InfinitySpacing.Small), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = InfinitySpacing.Small)) {
                Text(
                    DateUtils.formatDateTime(context, fragment.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                SelectionContainer {
                    Text(fragment.content, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.library_fragment_delete))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 长期记忆与知识图谱

@Composable
private fun LibraryMemoryPage(
    host: LibraryPageHost,
    title: String,
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
) {
    val context = LocalContext.current
    val memory: LongTermMemoryViewModel = viewModel(factory = LongTermMemoryViewModel.factory(context.applicationContext as Application))
    val enabled by memory.enabled.collectAsStateWithLifecycle()
    val items by memory.items.collectAsStateWithLifecycle()
    val query by memory.query.collectAsStateWithLifecycle()
    val newIds by host.viewModel.newMemoryIds.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 离开页面时清除“新”标记：停留期间角标保持可见。
    DisposableEffect(Unit) { onDispose { host.viewModel.markMemoriesSeen() } }

    LibraryPageFrame(
        title = title,
        leading = leading,
        crumbs = crumbs,
        modifier = Modifier.testTag(LibraryTags.page("memory")),
        actions = {
            IconButton(onClick = {
                if (searching) memory.setQuery("")
                searching = !searching
            }) {
                Icon(
                    if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.library_memory_search),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = searching, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                OutlinedTextField(
                    value = query,
                    onValueChange = memory::setQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_memory_search), maxLines = 1) },
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small).keepsFocusOnTap(),
                )
            }
            if (!enabled) {
                Text(
                    stringResource(R.string.library_memory_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
                )
            }
            LongTermMemoryContent(
                enabled = enabled,
                items = items,
                query = query,
                onEnabledChange = memory::setEnabled,
                onOpen = { editingId = it.id },
                contentPadding = padding,
                modifier = Modifier.weight(1f),
                showToggle = false,
                newIds = newIds,
            )
        }
    }

    val editing = items.firstOrNull { it.memory.id == editingId }
    if (editingId != null && editing == null) editingId = null
    if (editing != null) {
        val source by produceState<LongTermMemorySourceLabel?>(null, editing.memory.id) {
            value = memory.sourceLabel(editing.memory, editing.agentName)
        }
        LongTermMemoryEditSheet(
            memory = editing.memory,
            source = source,
            onDismiss = { editingId = null },
            onSave = { kind, newTitle, content ->
                scope.launch { if (memory.save(editing.memory, kind, newTitle, content)) editingId = null }
            },
            onDelete = {
                editingId = null
                memory.delete(editing.memory)
            },
        )
    }
}

/** 知识图谱整页嵌入：沿用图谱页自己的顶栏与实体搜索，最左侧按钮换成资料页的展开 / 还原或返回。 */
@Composable
private fun LibraryGraphPage(host: LibraryPageHost, leading: LibraryLeading) {
    val leadingButton: @Composable () -> Unit = {
        when (leading) {
            is LibraryLeading.Expand -> LibraryExpandButton(leading.expanded, leading.onToggle)
            is LibraryLeading.Back -> InfinityBackButton(onClick = leading.onBack)
        }
    }
    Box(Modifier.fillMaxSize().testTag(LibraryTags.page("graph"))) {
        CompositionLocalProvider(LocalInfinitySettingsLeading provides leadingButton) {
            KnowledgeGraphScreen(
                onNavigateBack = { host.navigator.back() },
                onOpenSession = host.onOpenSession,
            )
        }
    }
}
