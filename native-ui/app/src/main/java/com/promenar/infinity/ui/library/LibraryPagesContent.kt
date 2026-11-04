package com.promenar.infinity.ui.library

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.keepsFocusOnTap
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.LocalInfinitySettingsLeading
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.memory.LongTermMemoryContent
import com.promenar.infinity.ui.memory.LongTermMemoryEditSheet
import com.promenar.infinity.ui.memory.LongTermMemorySourceLabel
import com.promenar.infinity.ui.memory.LongTermMemoryViewModel
import com.promenar.infinity.ui.rag.KnowledgeGraphScreen
import com.promenar.infinity.ui.rag.RagSearchResults
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.launch

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
    uplink: LibraryUplink?,
) {
    val title = libraryPageTitle(page, host)
    val uplinkTitle = uplink?.let { libraryPageTitle(LibraryPageKeys.parse(it.key), host) }
    val onUplink: () -> Unit = { uplink?.onClick?.invoke() }
    when (page) {
        is LibraryPage.Browse -> LibraryBrowsePage(page.ref, host, leading)
        LibraryPage.Trash -> LibraryTrashPage(host, leading)
        LibraryPage.Graph -> LibraryGraphPage(host, leading)
        LibraryPage.Memory -> LibraryMemoryPage(host, title, leading, uplinkTitle, onUplink)
        is LibraryPage.Document -> LibraryDocumentPage(page.documentId, host, title, leading, uplinkTitle, onUplink)
        is LibraryPage.FragmentSession -> LibraryFragmentSessionPage(page.sessionId, host, title, leading, uplinkTitle, onUplink)
        else -> LibraryPageFrame(title, leading, uplinkTitle, onUplink, Modifier.testTag(LibraryTags.page(page.javaClass.simpleName))) { padding ->
            when (page) {
                LibraryPage.Overview -> LibraryOverviewContent(host, padding, entryCards = false)
                LibraryPage.Recent -> LibraryRecentContent(host, padding)
                LibraryPage.Pending -> LibraryPendingContent(host, padding)
                LibraryPage.Fragments -> LibraryFragmentsContent(host, padding)
                LibraryPage.Files -> LibraryFilesContent(host, padding)
                LibraryPage.Ask -> LibraryAskContent(host, padding)
                else -> Unit
            }
        }
    }
}

/** 页面在左列、图标条、页头与“‹ 上一页”里显示的名称。 */
@Composable
internal fun libraryPageTitle(page: LibraryPage, host: LibraryPageHost): String = when (page) {
    LibraryPage.Overview -> stringResource(R.string.library_page_overview)
    LibraryPage.Recent -> stringResource(R.string.library_page_recent)
    LibraryPage.Pending -> stringResource(R.string.library_page_pending)
    LibraryPage.Memory -> stringResource(R.string.library_page_memory)
    LibraryPage.Fragments -> stringResource(R.string.library_page_fragments)
    LibraryPage.Graph -> stringResource(R.string.library_page_graph)
    LibraryPage.Trash -> stringResource(R.string.library_page_trash)
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
            location?.title ?: stringResource(R.string.library_page_files)
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
    uplinkTitle: String?,
    onUplink: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        LibraryPageHeader(title = title, leading = leading, uplinkTitle = uplinkTitle, onUplink = onUplink, actions = actions)
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
internal fun LibraryHome(host: LibraryPageHost) {
    Column(Modifier.fillMaxSize().testTag(LibraryTags.page("home"))) {
        InfinityTabHeader(title = stringResource(R.string.nav_tab_library))
        LibraryAskField(
            query = host.askQuery,
            onQueryChange = host.ask.onQueryChange,
            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
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
        val sections: List<@Composable () -> Unit> = listOf(
            {
                LibraryCard(
                    title = stringResource(R.string.library_pending_title),
                    count = pending.size,
                    onViewAll = if (pending.size > OVERVIEW_PENDING) ({ host.navigator.openSection(LibraryPageKeys.PENDING) }) else null,
                ) {
                    if (pending.isEmpty()) {
                        LibraryEmptyText(stringResource(R.string.library_pending_empty))
                    } else {
                        pending.take(OVERVIEW_PENDING).forEach { doc ->
                            PendingRow(doc, busy = doc.documentId in busy, onOpen = { host.navigator.push(LibraryPageKeys.document(doc.documentId)) }, onResolve = { vm.resolve(doc) })
                        }
                    }
                }
            },
            {
                LibraryCard(
                    title = stringResource(R.string.library_recent_title),
                    onViewAll = if (recent.size > OVERVIEW_RECENT) ({ host.navigator.openSection(LibraryPageKeys.RECENT) }) else null,
                ) {
                    if (recent.isEmpty()) {
                        LibraryEmptyText(stringResource(R.string.library_recent_empty))
                    } else {
                        recent.take(OVERVIEW_RECENT).forEach { doc ->
                            DocumentRow(doc, onOpen = { host.navigator.push(LibraryPageKeys.document(doc.documentId)) })
                        }
                    }
                }
            },
            { CollectCard(host) },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            if (entryCards) {
                item(key = "entries") { LibraryEntryCards(host) }
            }
            item(key = "kpi") {
                val o = overview
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    maxItemsInEachRow = if (wide) 4 else 2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    KpiTile(
                        label = stringResource(R.string.library_kpi_documents),
                        value = o?.indexedDocuments,
                        onClick = { host.navigator.openSection(LibraryPageKeys.RECENT) },
                        modifier = Modifier.weight(1f).fillMaxRowHeight(),
                    )
                    KpiTile(
                        label = stringResource(R.string.library_kpi_memories),
                        value = o?.longTermMemories,
                        detail = o?.newLongTermMemories?.takeIf { it > 0 }?.let { stringResource(R.string.library_kpi_memories_new, it) },
                        onClick = { host.navigator.openSection(LibraryPageKeys.MEMORY) },
                        modifier = Modifier.weight(1f).fillMaxRowHeight(),
                    )
                    KpiTile(
                        label = stringResource(R.string.library_kpi_graph),
                        value = o?.graphEntities,
                        detail = o?.graphRelations?.let { stringResource(R.string.library_kpi_graph_relations, it) },
                        onClick = { host.navigator.openSection(LibraryPageKeys.GRAPH) },
                        modifier = Modifier.weight(1f).fillMaxRowHeight(),
                    )
                    KpiTile(
                        label = stringResource(R.string.library_kpi_fragments),
                        value = o?.fragments,
                        onClick = { host.navigator.openSection(LibraryPageKeys.FRAGMENTS) },
                        modifier = Modifier.weight(1f).fillMaxRowHeight(),
                    )
                }
            }
            if (wide) {
                // 宽版：待处理与最近并排，收集卡在下。
                item(key = "pair") {
                    Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
                        Box(Modifier.weight(1f)) { sections[0]() }
                        Box(Modifier.weight(1f)) { sections[1]() }
                    }
                }
                item(key = "collect") { sections[2]() }
            } else {
                item(key = "pending") { sections[0]() }
                item(key = "recent") { sections[1]() }
                item(key = "collect") { sections[2]() }
            }
        }
    }
}

/** 外屏首页的四张入口卡：文件、长期记忆、知识图谱、对话片段。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryEntryCards(host: LibraryPageHost) {
    val entries = listOf(
        Triple(LibraryPageKeys.FILES, Icons.Rounded.Description, stringResource(R.string.library_page_files)),
        Triple(LibraryPageKeys.MEMORY, Icons.Rounded.Psychology, stringResource(R.string.library_page_memory)),
        Triple(LibraryPageKeys.GRAPH, Icons.Rounded.Hub, stringResource(R.string.library_page_graph)),
        Triple(LibraryPageKeys.FRAGMENTS, Icons.Rounded.Forum, stringResource(R.string.library_page_fragments)),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth(),
    ) {
        entries.forEach { (key, icon, title) ->
            Surface(
                onClick = { host.navigator.push(key) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f).fillMaxRowHeight().heightIn(min = 72.dp).testTag(LibraryTags.navItem(key)),
            ) {
                Row(
                    Modifier.padding(InfinitySpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun KpiTile(label: String, value: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 88.dp),
    ) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 数值变化时纵向替换：增加自下而上，减少自上而下。
            AnimatedContent(
                targetState = value,
                transitionSpec = { InfinityMotion.sharedAxisY(forward = (targetState ?: 0) >= (initialState ?: 0)) },
                label = "kpi-value",
            ) { shown -> Text(shown?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LibraryCard(
    title: String,
    count: Int? = null,
    onViewAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(InfinityMotion.contentSizeSpec()).padding(vertical = InfinitySpacing.Small)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
private fun LibraryEmptyText(text: String) {
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
    PendingKind.QUEUED -> doc.queuePosition?.let { stringResource(R.string.library_pending_queued, it) } ?: stringResource(R.string.library_pending_queued_plain)
    PendingKind.INDEXING -> stringResource(R.string.library_pending_indexing)
    PendingKind.STALE -> stringResource(R.string.library_pending_stale)
}

@Composable
private fun PendingRow(doc: PendingDocument, busy: Boolean, onOpen: () -> Unit, onResolve: () -> Unit) {
    val icon = when (doc.kind) {
        PendingKind.FAILED -> Icons.Rounded.ErrorOutline
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
            Text(pendingStatusText(doc), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(doc.folderPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val action = when (doc.kind) {
            PendingKind.FAILED -> R.string.library_action_retry
            PendingKind.STALE -> R.string.library_action_rebuild
            else -> null
        }
        if (action != null) {
            FilledTonalButton(onClick = onResolve, enabled = !busy) { Text(stringResource(action), maxLines = 1) }
        }
    }
}

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
            )
        }
    }
}

/** 收集卡：导入文件与新建笔记都放进资料库根目录。 */
@Composable
private fun CollectCard(host: LibraryPageHost) {
    val vm = host.viewModel
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importToLibrary(uris.map { it.toString() })
    }
    LibraryCard(title = stringResource(R.string.library_collect_title)) {
        Text(
            stringResource(R.string.library_collect_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            FilledTonalButton(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(InfinitySpacing.Small))
                Text(stringResource(R.string.library_collect_import), maxLines = 1)
            }
            FilledTonalButton(onClick = { noteOpen = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(InfinitySpacing.Small))
                Text(stringResource(R.string.library_collect_note), maxLines = 1)
            }
        }
    }
    if (noteOpen) {
        NoteDialog(
            onDismiss = { noteOpen = false },
            onSave = { name, text ->
                noteOpen = false
                vm.saveNote(name, text)
            },
        )
    }
}

@Composable
private fun NoteDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val defaultName = stringResource(R.string.library_note_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_note_name)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.library_note_body)) },
                    minLines = 4,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), text) }, enabled = name.isNotBlank() && text.isNotBlank()) {
                Text(stringResource(R.string.library_note_save), maxLines = 1)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_btn_cancel), maxLines = 1) } },
    )
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
// 问资料

@Composable
private fun LibraryAskContent(host: LibraryPageHost, padding: PaddingValues) {
    val searchState by host.ragViewModel.searchState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        if (!host.navigator.twoPane) {
            LibraryAskField(
                query = host.askQuery,
                onQueryChange = host.ask.onQueryChange,
                modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
            )
        }
        AnimatedContent(
            targetState = host.askQuery.isBlank(),
            transitionSpec = { InfinityMotion.fadeThrough() },
            modifier = Modifier.fillMaxSize(),
            label = "ask-results",
        ) { blank ->
            if (blank) {
                LibraryEmptyText(stringResource(R.string.library_ask_hint))
            } else {
                RagSearchResults(
                    state = searchState,
                    onOpenDocument = { docId -> host.navigator.push(LibraryPageKeys.document(docId)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
    uplinkTitle: String?,
    onUplink: () -> Unit,
) {
    val insight by produceState<Loaded<DocumentInsight>>(Loaded.Loading, documentId) {
        value = Loaded.Ready(host.viewModel.insight(documentId))
    }
    val body by produceState<Loaded<DocumentBody>>(Loaded.Loading, documentId) {
        value = Loaded.Ready(host.viewModel.body(documentId))
    }
    val rootUuid by host.ragViewModel.workspaceRootUuid.collectAsStateWithLifecycle()
    val resolvedTitle = (insight as? Loaded.Ready)?.value?.name ?: title
    LibraryPageFrame(
        title = resolvedTitle,
        leading = leading,
        uplinkTitle = uplinkTitle,
        onUplink = onUplink,
        modifier = Modifier.testTag(LibraryTags.page("document")),
        actions = {
            val root = rootUuid
            if (root != null) {
                IconButton(onClick = { host.onNavigateToSecondary(NavDestinations.docEditor(root, documentId)) }) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.library_doc_edit))
                }
            }
        },
    ) { padding ->
        val currentInsight = insight
        if (currentInsight is Loaded.Ready && currentInsight.value == null) {
            LibraryEmptyText(stringResource(R.string.library_doc_not_found))
            return@LibraryPageFrame
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WideContentWidth
            val info = (currentInsight as? Loaded.Ready)?.value
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(padding)) {
                        DocumentBodyView(body)
                    }
                    Column(
                        Modifier
                            .width(280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = padding.calculateTopPadding(), end = InfinitySpacing.ScreenHorizontal, bottom = padding.calculateBottomPadding()),
                    ) {
                        info?.let { DocumentInspector(it, host) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
                    DocumentBodyView(body)
                    info?.let { DocumentInspector(it, host) }
                }
            }
        }
    }
}

@Composable
private fun DocumentBodyView(body: Loaded<DocumentBody>) {
    AnimatedContent(targetState = body, contentKey = { it is Loaded.Loading }, transitionSpec = { InfinityMotion.fadeThrough() }, label = "doc-body") {
        DocumentBodyState(it)
    }
}

@Composable
private fun DocumentBodyState(body: Loaded<DocumentBody>) {
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
                    if (value.markdown) {
                        MarkdownText(markdown = value.text, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(value.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (value.truncated) {
                    Text(stringResource(R.string.library_doc_truncated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentInspector(info: DocumentInsight, host: LibraryPageHost) {
    val context = LocalContext.current
    val busy by host.viewModel.busyDocuments.collectAsStateWithLifecycle()
    LibraryCard(title = stringResource(R.string.library_doc_agent_view)) {
        val status = info.pending?.let {
            pendingStatusText(PendingDocument(info.documentId, info.ref, info.name, info.folderPath, it, info.failureReason))
        } ?: stringResource(R.string.library_doc_status_indexed)
        InspectorRow(stringResource(R.string.library_doc_status), status)
        val action = when (info.pending) {
            PendingKind.FAILED -> R.string.library_action_retry
            PendingKind.STALE -> R.string.library_action_rebuild
            else -> null
        }
        if (action != null) {
            FilledTonalButton(
                onClick = { if (info.pending == PendingKind.FAILED) host.viewModel.retry(info.documentId) else host.viewModel.rebuild(info.documentId) },
                enabled = info.documentId !in busy,
                modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
            ) { Text(stringResource(action), maxLines = 1) }
        }
        InspectorRow(stringResource(R.string.library_doc_chunks), info.chunkCount.toString())
        Column(Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
            Text(stringResource(R.string.library_doc_entities), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (info.entities.isEmpty()) {
                Text(stringResource(R.string.library_doc_entities_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                    info.entities.forEach { entity ->
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
        }
        InspectorRow(stringResource(R.string.library_doc_location), info.folderPath)
        InfinityMetaLine(
            listOf(
                Formatter.formatShortFileSize(context, info.sizeBytes),
                DateUtils.formatDateTime(context, info.lastModified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
        )
        info.source?.let { InspectorRow(stringResource(R.string.library_doc_source), it) }
        if (info.versionCount > 0) InspectorRow(stringResource(R.string.library_doc_versions), info.versionCount.toString())
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
    uplinkTitle: String?,
    onUplink: () -> Unit,
) {
    val fragments by remember(sessionId) { host.viewModel.fragments(sessionId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LibraryPageFrame(
        title = title,
        leading = leading,
        uplinkTitle = uplinkTitle,
        onUplink = onUplink,
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
    uplinkTitle: String?,
    onUplink: () -> Unit,
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
        uplinkTitle = uplinkTitle,
        onUplink = onUplink,
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
