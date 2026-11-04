package com.promenar.infinity.ui.memory

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.ui.common.InfinityCapsuleTabRow
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinityPageSearch
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityTabItem
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.InfinityMetaLine
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.ui.theme.forInput

/** 长期记忆的标题与正文上限，与数据层校验一致。 */
internal const val LONG_TERM_MEMORY_TITLE_MAX = 80
internal const val LONG_TERM_MEMORY_CONTENT_MAX = 2000
internal const val LONG_TERM_MEMORY_LIMIT = 200

internal val LongTermMemoryKind.labelRes: Int
    get() = when (this) {
        LongTermMemoryKind.USER -> R.string.ltm_kind_user
        LongTermMemoryKind.FEEDBACK -> R.string.ltm_kind_feedback
        LongTermMemoryKind.PROJECT -> R.string.ltm_kind_project
        LongTermMemoryKind.REFERENCE -> R.string.ltm_kind_reference
    }

private val LongTermMemoryKind.shortLabelRes: Int
    get() = when (this) {
        LongTermMemoryKind.USER -> R.string.ltm_kind_user_short
        LongTermMemoryKind.FEEDBACK -> R.string.ltm_kind_feedback_short
        LongTermMemoryKind.PROJECT -> R.string.ltm_kind_project_short
        LongTermMemoryKind.REFERENCE -> R.string.ltm_kind_reference_short
    }

@Composable
fun LongTermMemoryScreen(
    onNavigateBack: () -> Unit,
    /** 给出时（设置入口）只显示总开关与“在资料中管理”，记忆列表在资料页。 */
    onOpenLibrary: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewModel: LongTermMemoryViewModel = viewModel(
        factory = LongTermMemoryViewModel.factory(context.applicationContext as Application),
    )
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val deletedMessage = stringResource(R.string.ltm_deleted)
    val undoLabel = stringResource(R.string.ltm_undo)
    val saveFailed = stringResource(R.string.ltm_save_failed)
    LaunchedEffect(notice) {
        when (val current = notice) {
            is LongTermMemoryNotice.Deleted -> {
                viewModel.consumeNotice()
                val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(current.memory)
            }
            is LongTermMemoryNotice.SaveFailed -> {
                viewModel.consumeNotice()
                snackbar.showSnackbar(current.message.ifBlank { saveFailed })
            }
            null -> Unit
        }
    }

    if (onOpenLibrary != null) {
        LongTermMemorySwitchPage(
            enabled = enabled,
            onEnabledChange = viewModel::setEnabled,
            onNavigateBack = onNavigateBack,
            onOpenLibrary = onOpenLibrary,
        )
        return
    }

    InfinitySettingsPageLayout(
        title = stringResource(R.string.ltm_title),
        onBack = onNavigateBack,
        search = InfinityPageSearch(
            query = query,
            active = searchActive,
            onQueryChange = viewModel::setQuery,
            onActiveChange = { searchActive = it },
            placeholder = stringResource(R.string.ltm_search_placeholder),
        ),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LongTermMemoryContent(
                enabled = enabled,
                items = items,
                query = query,
                onEnabledChange = viewModel::setEnabled,
                onOpen = { editingId = it.id },
                contentPadding = padding,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }

    val editing = items.firstOrNull { it.memory.id == editingId }
    if (editingId != null && editing == null) editingId = null
    if (editing != null) {
        val source by produceState<LongTermMemorySourceLabel?>(null, editing.memory.id) {
            value = viewModel.sourceLabel(editing.memory, editing.agentName)
        }
        LongTermMemoryEditSheet(
            memory = editing.memory,
            source = source,
            onDismiss = { editingId = null },
            onSave = { kind, title, content ->
                scope.launch { if (viewModel.save(editing.memory, kind, title, content)) editingId = null }
            },
            onDelete = {
                editingId = null
                viewModel.delete(editing.memory)
            },
        )
    }
}

@Composable
internal fun LongTermMemoryContent(
    enabled: Boolean,
    items: List<LongTermMemoryListItem>,
    query: String,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: (LongTermMemory) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    /** 资料页里总开关留在设置，这里不重复显示。 */
    showToggle: Boolean = true,
    /** 近 7 天新增且未看过的记忆，行内标“新”。 */
    newIds: Set<String> = emptySet(),
) {
    val groups = remember(items, query) { groupLongTermMemories(items, query) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(UiTags.LTM_LIST),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
    ) {
        if (showToggle) item(key = "toggle") {
            InfinitySettingsSection(title = stringResource(R.string.ltm_section_switch), footer = stringResource(R.string.ltm_switch_footer)) {
                SettingsToggle(
                    title = stringResource(R.string.ltm_enabled),
                    description = stringResource(R.string.ltm_enabled_desc),
                    checked = enabled,
                    icon = Icons.Rounded.Bookmarks,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag(UiTags.LTM_ENABLED),
                )
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(if (query.isBlank()) R.string.ltm_empty else R.string.ltm_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Large),
                )
            }
        }
        groups.forEach { (kind, group) ->
            item(key = "group-${kind.name}") {
                InfinitySettingsSection(title = stringResource(R.string.ltm_group_title, stringResource(kind.labelRes), group.size)) {
                    group.forEach { item ->
                        LongTermMemoryRow(item, nowMs, isNew = item.memory.id in newIds, onClick = { onOpen(item.memory) })
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            item(key = "count") {
                Text(
                    text = pluralStringResource(R.plurals.ltm_count, items.size, items.size, LONG_TERM_MEMORY_LIMIT),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                )
            }
        }
    }
}

@Composable
private fun LongTermMemoryRow(item: LongTermMemoryListItem, nowMs: Long, isNew: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Medium)
            .testTag(UiTags.ltmItem(item.memory.id)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(
                item.memory.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isNew) {
                androidx.compose.material3.Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(stringResource(R.string.library_badge_new)) }
            }
        }
        Text(
            item.memory.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        InfinityMetaLine(
            listOfNotNull(item.agentName, relativeDay(item.memory.updatedAt, nowMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun relativeDay(timeMs: Long, nowMs: Long): String =
    DateUtils.getRelativeTimeSpanString(timeMs, nowMs, DateUtils.DAY_IN_MILLIS).toString()

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LongTermMemoryEditSheet(
    memory: LongTermMemory,
    source: LongTermMemorySourceLabel?,
    onDismiss: () -> Unit,
    onSave: (LongTermMemoryKind, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Content) {
        LongTermMemoryEditForm(memory, source, onSave, onDelete)
    }
}

@Composable
internal fun LongTermMemoryEditForm(
    memory: LongTermMemory,
    source: LongTermMemorySourceLabel?,
    onSave: (LongTermMemoryKind, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var kind by rememberSaveable(memory.id) { mutableStateOf(memory.kind) }
    var title by rememberSaveable(memory.id) { mutableStateOf(memory.title) }
    var content by rememberSaveable(memory.id) { mutableStateOf(memory.content) }
    val valid = title.isNotBlank() && content.isNotBlank() &&
        title.trim().length <= LONG_TERM_MEMORY_TITLE_MAX && content.trim().length <= LONG_TERM_MEMORY_CONTENT_MAX
    val changed = kind != memory.kind || title.trim() != memory.title || content.trim() != memory.content
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal)
            .padding(bottom = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Text(stringResource(R.string.ltm_edit_title), style = MaterialTheme.typography.titleLarge)
        InfinityCapsuleTabRow(
            tabs = LongTermMemoryKind.entries.map {
                InfinityTabItem(title = stringResource(it.shortLabelRes), testTag = UiTags.ltmKind(it.name))
            },
            selectedIndex = LongTermMemoryKind.entries.indexOf(kind),
            onTabSelected = { kind = LongTermMemoryKind.entries[it] },
            itemRole = Role.RadioButton,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(LONG_TERM_MEMORY_TITLE_MAX) },
            label = { Text(stringResource(R.string.ltm_field_title)) },
            textStyle = LocalTextStyle.current.forInput(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.LTM_EDIT_TITLE),
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it.take(LONG_TERM_MEMORY_CONTENT_MAX) },
            label = { Text(stringResource(R.string.ltm_field_content)) },
            textStyle = LocalTextStyle.current.forInput(),
            minLines = 3,
            maxLines = 8,
            supportingText = { Text("${content.length} / $LONG_TERM_MEMORY_CONTENT_MAX") },
            modifier = Modifier.fillMaxWidth().testTag(UiTags.LTM_EDIT_CONTENT),
        )
        source?.let {
            InfinityMetaLine(
                segments = sourceSegments(memory, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfinityPillRow(modifier = Modifier.fillMaxWidth()) {
            InfinityPillButton(
                text = stringResource(R.string.ltm_delete),
                onClick = onDelete,
                tone = InfinityPillTone.DANGER,
                modifier = Modifier.testTag(UiTags.LTM_EDIT_DELETE),
            )
            InfinityPillButton(
                text = stringResource(R.string.ltm_save),
                onClick = { onSave(kind, title.trim(), content.trim()) },
                tone = InfinityPillTone.PRIMARY,
                enabled = valid && changed,
                modifier = Modifier.testTag(UiTags.LTM_EDIT_SAVE),
            )
        }
    }
}

/** 来源分段：“来自”前缀并入第一段，其余为会话与时间。 */
@Composable
private fun sourceSegments(memory: LongTermMemory, source: LongTermMemorySourceLabel): List<String> {
    val time = DateUtils.formatDateTime(
        LocalContext.current,
        memory.createdAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val parts = listOfNotNull(
        source.agentName,
        source.sessionTitle?.takeIf(String::isNotBlank)?.let { stringResource(R.string.ltm_source_session, it) },
        time,
    )
    return listOf(stringResource(R.string.ltm_source, parts.first())) + parts.drop(1)
}

/** 设置里的长期记忆：只留总开关，查看与编辑在资料页。 */
@Composable
private fun LongTermMemorySwitchPage(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    InfinitySettingsPageLayout(title = stringResource(R.string.ltm_title), onBack = onNavigateBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(UiTags.LTM_LIST),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
        ) {
            item(key = "toggle") {
                InfinitySettingsSection(title = stringResource(R.string.ltm_section_switch), footer = stringResource(R.string.ltm_switch_footer)) {
                    SettingsToggle(
                        title = stringResource(R.string.ltm_enabled),
                        description = stringResource(R.string.ltm_enabled_desc),
                        checked = enabled,
                        icon = Icons.Rounded.Bookmarks,
                        onCheckedChange = onEnabledChange,
                        modifier = Modifier.testTag(UiTags.LTM_ENABLED),
                    )
                }
            }
            item(key = "manage") {
                InfinitySettingsSection(title = stringResource(R.string.library_memory_manage_section)) {
                    com.promenar.infinity.ui.common.InfinitySettingsItem(
                        title = stringResource(R.string.library_memory_manage),
                        subtitle = stringResource(R.string.library_memory_manage_desc),
                        onClick = onOpenLibrary,
                        showChevron = true,
                        modifier = Modifier.testTag(UiTags.LTM_OPEN_LIBRARY),
                    )
                }
            }
        }
    }
}
