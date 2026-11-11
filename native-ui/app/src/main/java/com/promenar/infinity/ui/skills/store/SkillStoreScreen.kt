package com.promenar.infinity.ui.skills.store

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.skills.store.ConfirmReason
import com.promenar.infinity.data.skills.store.SkillChannel
import com.promenar.infinity.data.skills.store.SkillStoreSource
import com.promenar.infinity.data.skills.store.SourceStatus
import com.promenar.infinity.data.skills.store.StoreFilter
import com.promenar.infinity.data.skills.store.StoreInstallState
import com.promenar.infinity.data.skills.store.StoreOperation
import com.promenar.infinity.data.skills.store.StoreSkillItem
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.workspace.WorkspaceTagChip
import com.promenar.infinity.ui.workspace.WorkspaceTagTone
import kotlinx.coroutines.launch

/**
 * 技能商店：列表与单个技能的详情在同一页内切换（详情打开时返回键回到列表）。
 * 安装确认、卸载确认与操作结果提示都在这一层处理；内容函数无状态，供预览与截图测试使用。
 */
@Composable
fun SkillStoreScreen(onBack: () -> Unit, onOpenSources: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SkillStoreViewModel = viewModel(factory = SkillStoreViewModel.factory(context.applicationContext as Application))
    val state by viewModel.state.collectAsState()
    val event by viewModel.event.collectAsState()
    var openedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<String, ConfirmReason>?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    var snackbarType by remember { mutableStateOf(SnackbarType.SUCCESS) }

    // 事件消费后键变为 null 会取消本次效果，提示条因此放在独立作用域里显示。
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(event) {
        val current = event ?: return@LaunchedEffect
        viewModel.consumeEvent()
        val message = when (current) {
            is SkillStoreEvent.NeedsConfirm -> {
                pendingConfirm = current.key to current.reason
                null
            }
            is SkillStoreEvent.Installed -> context.getString(
                if (current.enabled) R.string.skill_store_event_installed else R.string.skill_store_event_installed_disabled,
                current.name,
            )
            is SkillStoreEvent.Updated -> context.getString(R.string.skill_store_event_updated, current.name)
            is SkillStoreEvent.Uninstalled -> context.getString(R.string.skill_store_event_uninstalled, current.name)
            is SkillStoreEvent.SourceAdded -> context.getString(R.string.skill_store_event_source_added)
            is SkillStoreEvent.Failed -> storeFailureText(context, current.failure, current.httpStatus)
        }
        if (message != null) {
            snackbarType = if (current is SkillStoreEvent.Failed) SnackbarType.ERROR else SnackbarType.SUCCESS
            snackbarScope.launch { snackbarHost.showSnackbar(message) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val opened = openedKey
        if (opened == null) {
            SkillStoreListContent(
                state = state,
                actions = SkillStoreListActions(
                    onBack = onBack,
                    onRefresh = viewModel::refresh,
                    onOpenSources = onOpenSources,
                    onQueryChange = viewModel::setQuery,
                    onFilterChange = viewModel::setFilter,
                    onCategoryChange = viewModel::setCategory,
                    onOpen = { openedKey = it },
                    onInstall = { viewModel.install(it) },
                    onCancel = viewModel::cancel,
                ),
            )
        } else {
            BackHandler { openedKey = null }
            val item by remember(opened) { viewModel.item(opened) }.collectAsState()
            val current = item
            if (current == null) {
                // 条目已从全部来源中消失（例如目录被移除）：回到列表。
                LaunchedEffect(opened) { openedKey = null }
            } else {
                SkillStoreDetailScreen(
                    item = current,
                    operation = state.operations[current.key],
                    onBack = { openedKey = null },
                    onInstall = { viewModel.install(current.key) },
                    onCancel = { viewModel.cancel(current.key) },
                    onUninstall = { viewModel.uninstall(current.key) },
                    loadSkillMarkdown = { viewModel.loadSkillMarkdown(current.key) },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data -> InfinitySnackbar(InfinitySnackbarData(data.visuals.message, snackbarType)) }
    }

    pendingConfirm?.let { (key, reason) ->
        // 条目键形如“目录地址#技能名”；筛选可能把条目藏起，名称直接从键取。
        val name = key.substringAfterLast('#')
        val (title, message, confirm) = when (reason) {
            ConfirmReason.NAME_TAKEN -> Triple(
                stringResource(R.string.skill_store_confirm_overwrite_title, name),
                stringResource(R.string.skill_store_confirm_overwrite_message, name),
                stringResource(R.string.skill_store_confirm_overwrite),
            )
            ConfirmReason.LOCAL_MODIFIED -> Triple(
                stringResource(R.string.skill_store_confirm_modified_title, name),
                stringResource(R.string.skill_store_confirm_modified_message, name),
                stringResource(R.string.skill_store_update),
            )
        }
        InfinityConfirmDialog(
            title = title,
            message = message,
            confirmText = confirm,
            onConfirm = {
                pendingConfirm = null
                viewModel.install(key, confirmed = true)
            },
            onCancel = { pendingConfirm = null },
            isDestructive = true,
            confirmButtonModifier = Modifier.testTag("skill_store_confirm"),
        )
    }
}

/** 商店列表的全部回调，集中传给无状态内容函数。 */
class SkillStoreListActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenSources: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onFilterChange: (StoreFilter) -> Unit,
    val onCategoryChange: (String?) -> Unit,
    val onOpen: (String) -> Unit,
    val onInstall: (String) -> Unit,
    val onCancel: (String) -> Unit,
)

/**
 * 商店列表：搜索框、单行筛选（“全部 / 已安装 / 可更新”分段加“分类”下拉）、读取失败的来源提示与技能列表。
 * 分类再多也只在下拉菜单里增长，筛选区始终一行。
 */
@Composable
fun SkillStoreListContent(state: SkillStoreUiState, actions: SkillStoreListActions) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.skill_store_title),
        onBack = actions.onBack,
        actions = {
            IconButton(onClick = actions.onRefresh, enabled = !state.refreshing, modifier = Modifier.testTag("skill_store_refresh")) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.skill_store_refresh))
            }
            IconButton(onClick = actions.onOpenSources, modifier = Modifier.testTag("skill_store_sources")) {
                Icon(Icons.Rounded.Dns, contentDescription = stringResource(R.string.skill_store_sources))
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("skill_store_list"),
            contentPadding = contentPadding,
        ) {
            item(key = "search") {
                InfinitySearchBar(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    placeholder = stringResource(R.string.skill_store_search),
                    modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small),
                )
            }
            item(key = "filters") {
                StoreFilterRow(
                    state = state,
                    onFilterChange = actions.onFilterChange,
                    onCategoryChange = actions.onCategoryChange,
                    modifier = Modifier.padding(bottom = InfinitySpacing.Medium),
                )
            }
            val failed = state.sources.filter { it.status is SourceStatus.Failed }
            itemsIndexed(failed, key = { _, source -> "failed:${source.url}" }) { _, source ->
                val status = source.status as SourceStatus.Failed
                InfinityStatusStrip(
                    kind = InfinityStatusKind.Warning,
                    message = stringResource(R.string.skill_store_source_failed, sourceName(source)) +
                        " · " + storeFailureText(status.failure, status.httpStatus),
                    modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small),
                )
            }
            when {
                state.items.isEmpty() && state.refreshing -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.XXLarge), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(if (state.filter == StoreFilter.UPDATES) R.string.skill_store_empty_updates else R.string.skill_store_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.XXLarge),
                    )
                }
                else -> itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
                    StoreSkillRow(
                        item = item,
                        operation = state.operations[item.key],
                        onOpen = { actions.onOpen(item.key) },
                        onInstall = { actions.onInstall(item.key) },
                        onCancel = { actions.onCancel(item.key) },
                        modifier = Modifier.bettboxListGroupItem(index, state.items.size),
                    )
                }
            }
            item(key = "bottom") { Box(Modifier.padding(bottom = InfinitySpacing.XXLarge)) }
        }
    }
}

/** 一行筛选：左侧分段滑块占满剩余宽度，右侧是带下拉箭头的“分类”标签。 */
@Composable
private fun StoreFilterRow(
    state: SkillStoreUiState,
    onFilterChange: (StoreFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(StoreFilter.ALL, stringResource(R.string.skill_store_filter_all), tag = "skill_store_filter_all"),
                InfinitySegment(
                    StoreFilter.INSTALLED,
                    stringResource(R.string.skill_store_filter_installed),
                    tag = "skill_store_filter_installed",
                    count = state.installedCount.takeIf { it > 0 },
                ),
                InfinitySegment(
                    StoreFilter.UPDATES,
                    stringResource(R.string.skill_store_filter_updates),
                    tag = "skill_store_filter_updates",
                    count = state.updateCount.takeIf { it > 0 },
                ),
            ),
            selected = state.filter,
            onSelect = onFilterChange,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        if (state.categories.isNotEmpty()) {
            StoreCategoryPicker(state = state, onCategoryChange = onCategoryChange)
        }
    }
}

@Composable
private fun StoreCategoryPicker(state: SkillStoreUiState, onCategoryChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.categories.firstOrNull { it.id == state.categoryId }
    Box {
        InfinityChip(
            label = selected?.title ?: stringResource(R.string.skill_store_category),
            onClick = { open = true },
            selected = selected != null,
            trailing = {
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.testTag("skill_store_category"),
        )
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CategoryMenuItem(
                title = stringResource(R.string.skill_store_category_all),
                count = state.categoryCounts[null],
                selected = state.categoryId == null,
                onClick = { open = false; onCategoryChange(null) },
            )
            state.categories.forEach { category ->
                CategoryMenuItem(
                    title = category.title,
                    count = state.categoryCounts[category.id],
                    selected = state.categoryId == category.id,
                    onClick = { open = false; onCategoryChange(category.id) },
                    modifier = Modifier.testTag("skill_store_category:${category.id}"),
                )
            }
        }
    }
}

@Composable
private fun CategoryMenuItem(title: String, count: Int?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    InfinityMenuItem(
        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (count != null) {
            {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 一个技能：标题、一行简介、一行来源与标签（单行，放不下横向滚动），行尾是安装、更新、进度或“已安装”。
 */
@Composable
private fun StoreSkillRow(
    item: StoreSkillItem,
    operation: StoreOperation?,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = item.entry
    InfinitySettingsItem(
        title = entry.title,
        onClick = onOpen,
        modifier = modifier.testTag("skill_store_row:${entry.name}"),
        subtitleContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.summary.isNotBlank()) {
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StoreMetaRow(item)
            }
        },
        trailingContent = { StoreRowAction(item, operation, onInstall, onCancel) },
    )
}

/** 来源、版本与大小，后接需要用户留意的标签；整行单行，放不下时横向滚动。 */
@Composable
private fun StoreMetaRow(item: StoreSkillItem) {
    val entry = item.entry
    val source = if (item.source.official) stringResource(R.string.skill_store_official) else item.source.host
    InfinityTagScrollRow {
        Text(
            listOf(source, entry.version, storeSizeText(entry.size)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        storeTags(item).forEach { (label, tone) -> WorkspaceTagChip(label = label, tone = tone) }
    }
}

/** 列表与详情共用的标签：含脚本与需更新应用为提醒色，其余为中性色。 */
@Composable
internal fun storeTags(item: StoreSkillItem): List<Pair<String, WorkspaceTagTone>> = buildList {
    if (item.entry.hasScripts) add(stringResource(R.string.skill_store_tag_scripts) to WorkspaceTagTone.CAUTION)
    if (SkillChannel.PLAY !in item.entry.channels) add(stringResource(R.string.skill_store_tag_sideload_only) to WorkspaceTagTone.NEUTRAL)
    if (!item.source.official) add(stringResource(R.string.skill_store_tag_third_party) to WorkspaceTagTone.NEUTRAL)
    if (item.installState == StoreInstallState.NameTaken) add(stringResource(R.string.skill_store_tag_name_taken) to WorkspaceTagTone.NEUTRAL)
    if (item.localModified) add(stringResource(R.string.skill_store_tag_modified) to WorkspaceTagTone.NEUTRAL)
    if (item.requiresAppUpdate) add(stringResource(R.string.skill_store_tag_needs_app_update) to WorkspaceTagTone.CAUTION)
}

@Composable
private fun StoreRowAction(item: StoreSkillItem, operation: StoreOperation?, onInstall: () -> Unit, onCancel: () -> Unit) {
    when {
        operation != null -> StoreProgress(operation, onCancel)
        item.requiresAppUpdate -> Unit
        else -> when (item.installState) {
            is StoreInstallState.UpdateAvailable -> InfinityPillButton(
                text = stringResource(R.string.skill_store_update),
                onClick = onInstall,
                tone = InfinityPillTone.PRIMARY,
                compact = true,
                modifier = Modifier.testTag("skill_store_update:${item.entry.name}"),
            )
            is StoreInstallState.Installed -> Text(
                stringResource(R.string.skill_store_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            StoreInstallState.NotInstalled, StoreInstallState.NameTaken -> InfinityPillButton(
                text = stringResource(R.string.skill_store_install),
                onClick = onInstall,
                compact = true,
                modifier = Modifier.testTag("skill_store_install:${item.entry.name}"),
            )
        }
    }
}

/** 下载显示确定进度，安装显示不定进度；点按圆环取消下载。 */
@Composable
internal fun StoreProgress(operation: StoreOperation, onCancel: () -> Unit) {
    val cancel = stringResource(R.string.common_btn_cancel)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(InfinitySpacing.MinimumTouchTarget)
            .clickable(enabled = operation is StoreOperation.Downloading, onClick = onCancel)
            .semantics { contentDescription = cancel },
    ) {
        when (operation) {
            is StoreOperation.Downloading -> {
                val progress = if (operation.totalBytes > 0) {
                    (operation.downloadedBytes.toFloat() / operation.totalBytes).coerceIn(0f, 1f)
                } else 0f
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            StoreOperation.Installing -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        }
    }
}

@Composable
internal fun sourceName(source: SkillStoreSource): String =
    if (source.official) stringResource(R.string.skill_store_official_catalog) else source.title ?: source.host

/** 设置“技能包”页顶部的商店入口；有可更新技能时行尾显示数量。 */
@Composable
fun SkillStoreEntryRow(updateCount: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    InfinitySettingsItem(
        icon = Icons.Rounded.Storefront,
        title = stringResource(R.string.skill_store_title),
        onClick = onOpen,
        showChevron = updateCount == 0,
        modifier = modifier.testTag("skill_store_entry"),
        trailingContent = if (updateCount > 0) {
            {
                WorkspaceTagChip(
                    label = pluralStringResource(R.plurals.skill_store_update_count, updateCount, updateCount),
                    tone = WorkspaceTagTone.NEUTRAL,
                )
            }
        } else null,
    )
}
