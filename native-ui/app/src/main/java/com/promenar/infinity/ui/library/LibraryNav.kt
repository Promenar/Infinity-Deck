package com.promenar.infinity.ui.library

import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.keepsFocusOnTap
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.onSelectionIndicator
import com.promenar.infinity.ui.theme.selectionIndicator

/** 左列与图标条的一项。 */
@Immutable
internal data class LibraryNavEntry(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
)

@Immutable
internal data class LibraryNavGroup(val title: String, val entries: List<LibraryNavEntry>, val foldable: Boolean = false)

/** 左列超过这个数量的位置折叠起来。 */
private const val VISIBLE_LOCATIONS = 6

internal fun FileLocation.navIcon(): ImageVector = when (kind) {
    FileLocationKind.KNOWLEDGE_BASE -> Icons.Rounded.LocalLibrary
    FileLocationKind.SESSION_WORKSPACE -> Icons.Rounded.FolderShared
    FileLocationKind.PROJECT -> Icons.Rounded.Work
    FileLocationKind.LINUX_HOME -> Icons.Rounded.Terminal
    FileLocationKind.EXTERNAL_FOLDER -> Icons.Rounded.FolderOpen
    FileLocationKind.BRANCH_WORKTREE -> Icons.Rounded.AccountTree
}

internal fun pinTitle(ref: FileRef): String = ref.path.trimEnd('/').substringAfterLast('/').ifEmpty { ref.path }

/** 左列四组导航。“智能体常用”需要引用回链，等后续工作提供数据后再加入视图组。 */
@Composable
internal fun rememberLibraryNavGroups(host: LibraryPageHost): List<LibraryNavGroup> {
    val pending by host.viewModel.pending.collectAsStateWithLifecycle()
    val overview by host.viewModel.overview.collectAsStateWithLifecycle()
    val locations by host.viewModel.locations.collectAsStateWithLifecycle()
    val newBadge = overview?.newLongTermMemories?.takeIf { it > 0 }?.toString()
    val locationTitles = locations.locations.associate { it.id to it.title }
    val accessLost = stringResource(R.string.library_location_access_lost)
    return listOf(
        LibraryNavGroup(
            title = stringResource(R.string.library_group_views),
            entries = listOf(
                LibraryNavEntry(LibraryPageKeys.OVERVIEW, Icons.Rounded.Dashboard, stringResource(R.string.library_page_overview)),
                LibraryNavEntry(LibraryPageKeys.RECENT, Icons.Rounded.Schedule, stringResource(R.string.library_page_recent)),
                LibraryNavEntry(
                    LibraryPageKeys.PENDING,
                    Icons.Rounded.ReportProblem,
                    stringResource(R.string.library_page_pending),
                    badge = pending.size.takeIf { it > 0 }?.toString(),
                ),
            ),
        ),
        LibraryNavGroup(
            title = stringResource(R.string.library_group_locations),
            entries = locations.locations.map { location ->
                LibraryNavEntry(
                    key = LibraryPageKeys.browse(FileRef(location.id, "/")),
                    icon = location.navIcon(),
                    title = location.title,
                    subtitle = if (location.accessLost) accessLost else location.gitBranch,
                )
            },
            foldable = true,
        ),
        LibraryNavGroup(
            title = stringResource(R.string.library_group_pins),
            entries = locations.pins.map { ref ->
                LibraryNavEntry(
                    key = LibraryPageKeys.browse(ref),
                    icon = Icons.Rounded.PushPin,
                    title = pinTitle(ref),
                    subtitle = locationTitles[ref.locationId],
                )
            },
        ),
        LibraryNavGroup(
            title = stringResource(R.string.library_group_remembered),
            entries = listOf(
                LibraryNavEntry(LibraryPageKeys.MEMORY, Icons.Rounded.Psychology, stringResource(R.string.library_page_memory), badge = newBadge),
                LibraryNavEntry(LibraryPageKeys.FRAGMENTS, Icons.Rounded.Forum, stringResource(R.string.library_page_fragments)),
                LibraryNavEntry(LibraryPageKeys.GRAPH, Icons.Rounded.Hub, stringResource(R.string.library_page_graph)),
            ),
        ),
    ).filter { it.entries.isNotEmpty() }
}

/** 内屏左列：标题、问资料与四组导航。新建与导入放在右侧内容区，页头不放按钮。 */
@Composable
internal fun LibraryNavColumn(host: LibraryPageHost) {
    val groups = rememberLibraryNavGroups(host)
    val selected = libraryRootKey(host.navigator.stack)
    var locationsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(LibraryTags.NAV_COLUMN),
    ) {
        InfinityTabHeader(title = stringResource(R.string.nav_tab_library))
        LibraryAskField(
            query = host.askQuery,
            onQueryChange = host.ask.onQueryChange,
            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = InfinitySpacing.XLarge),
        ) {
            groups.forEach { group ->
                item(key = "group-${group.title}") { LibraryNavGroupTitle(group.title) }
                val folded = group.foldable && !locationsExpanded && group.entries.size > VISIBLE_LOCATIONS
                val visible = if (folded) group.entries.take(VISIBLE_LOCATIONS) else group.entries
                items(visible, key = { "nav-${group.title}-${it.key}" }) { entry ->
                    LibraryNavRow(
                        entry,
                        selected = entry.key == selected,
                        onClick = { host.navigator.openRoot(entry.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (group.foldable && group.entries.size > VISIBLE_LOCATIONS) {
                    item(key = "fold-${group.title}") {
                        LibraryFoldRow(
                            modifier = Modifier.animateItem(),
                            text = if (locationsExpanded) {
                                stringResource(R.string.library_locations_less)
                            } else {
                                stringResource(R.string.library_locations_more, group.entries.size - VISIBLE_LOCATIONS)
                            },
                            expanded = locationsExpanded,
                            onClick = { locationsExpanded = !locationsExpanded },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryNavGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = InfinitySpacing.ScreenHorizontal + InfinitySpacing.Medium,
            end = InfinitySpacing.ScreenHorizontal,
            top = InfinitySpacing.Large,
            bottom = InfinitySpacing.XSmall,
        ),
    )
}

@Composable
internal fun LibraryNavRow(entry: LibraryNavEntry, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 选中高亮随切换渐变，与设置页左列一致。
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.selectionIndicator(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        tween(InfinityMotion.DurationShort),
        label = "nav-row-container",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSelectionIndicator else MaterialTheme.colorScheme.onSurface,
        tween(InfinityMotion.DurationShort),
        label = "nav-row-content",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(role = Role.Tab, onClick = onClick)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)
            .testTag(LibraryTags.navItem(entry.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(entry.icon, contentDescription = null, tint = content, modifier = Modifier.size(InfinitySpacing.RowIconSize))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        entry.badge?.let { Badge { Text(it) } }
    }
}

@Composable
private fun LibraryFoldRow(text: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(InfinityMotion.DurationMedium), label = "fold-arrow")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(horizontal = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(InfinitySpacing.RowIconSize).graphicsLayer { rotationZ = rotation },
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 展开后的图标条：视图、资料库与智能体记住的；位置在“资料库”页里切换。 */
@Composable
internal fun LibraryStrip(host: LibraryPageHost) {
    val groups = rememberLibraryNavGroups(host)
    val selected = libraryRootKey(host.navigator.stack)
    val entries = groups.flatMap { group ->
        if (group.foldable) group.entries.take(1) else group.entries
    }.filterNot { it.icon == Icons.Rounded.PushPin }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .testTag(LibraryTags.STRIP),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Spacer(Modifier.size(InfinitySpacing.Small))
        entries.forEach { entry ->
            val isSelected = entry.key == selected
            Box(
                Modifier
                    .size(InfinitySpacing.MinimumTouchTarget)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.selectionIndicator(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
                    )
                    .clickable(role = Role.Tab, onClick = { host.navigator.openRoot(entry.key) })
                    .semantics { contentDescription = entry.title }
                    .testTag(LibraryTags.navItem(entry.key)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onSelectionIndicator else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.badge?.let { Badge(Modifier.align(Alignment.TopEnd)) { Text(it) } }
            }
        }
    }
}

/** 问资料输入框：输入即在右列（外屏为整页）显示结果。 */
@Composable
internal fun LibraryAskField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().keepsFocusOnTap().testTag(LibraryTags.ASK_FIELD),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.forInput(),
        placeholder = { Text(stringResource(R.string.library_ask_placeholder), maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.library_ask_clear))
                }
            }
        } else null,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/** 展开 / 还原按钮，外观与对话页会话顶栏的展开按钮一致：未展开“←|”，展开后“|→”。 */
@Composable
internal fun LibraryExpandButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag(LibraryTags.EXPAND)) {
        Icon(
            imageVector = Icons.Rounded.Start,
            contentDescription = stringResource(if (expanded) R.string.hub_pane_restore else R.string.hub_pane_expand),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = if (expanded) 1f else -1f },
        )
    }
}

/**
 * 资料页右列与外屏子页的页头：最左侧为展开 / 还原（内屏）或返回（外屏），然后是标题；
 * [uplinkTitle] 给出时标题下方显示“‹ 上一页名称”。内屏页头不放返回图标。
 */
@Composable
internal fun LibraryPageHeader(
    title: String,
    leading: LibraryLeading,
    uplinkTitle: String?,
    onUplink: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (leading) {
                is LibraryLeading.Expand -> LibraryExpandButton(leading.expanded, leading.onToggle)
                is LibraryLeading.Back -> InfinityBackButton(onClick = leading.onBack)
            }
            Spacer(Modifier.width(InfinitySpacing.XSmall))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (uplinkTitle != null) {
            val description = stringResource(R.string.library_uplink, uplinkTitle)
            Row(
                Modifier
                    .padding(start = InfinitySpacing.Small)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClick = onUplink)
                    .semantics { contentDescription = description }
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .padding(end = InfinitySpacing.Medium)
                    .testTag(LibraryTags.UPLINK),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(InfinitySpacing.RowIconSize),
                )
                Text(
                    uplinkTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 外屏“文件”页的位置与固定项：与设置页相同的圆角分组列表，行尾带箭头。 */
@Composable
internal fun LibraryLocationRows(host: LibraryPageHost) {
    val groups = rememberLibraryNavGroups(host)
    groups.filter { it.foldable || it.entries.any { e -> e.icon == Icons.Rounded.PushPin } }.forEach { group ->
        InfinitySettingsSection(title = group.title) {
            group.entries.forEach { entry ->
                InfinitySettingsItem(
                    icon = entry.icon,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    onClick = { host.navigator.push(entry.key) },
                    showChevron = true,
                    modifier = Modifier.testTag(LibraryTags.navItem(entry.key)),
                )
            }
        }
    }
}
