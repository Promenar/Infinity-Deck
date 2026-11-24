package com.promenar.infinity.ui.library

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.files.FileBreadcrumbs
import com.promenar.infinity.ui.files.gitLabel
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.common.keepsFocusOnTap
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.onSelectionIndicator
import com.promenar.infinity.ui.theme.selectionIndicator

/** 左列与外屏文件页的导航分组语义。 */
internal enum class LibraryNavGroupKind {
    VIEWS,
    BUILTIN_LOCATIONS,
    APP_WORKSPACES,
    EXTERNAL_PATHS,
    PINS,
    REMEMBERED,
}

/** 左列与图标条的一项。 */
@Immutable
internal data class LibraryNavEntry(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val location: FileLocation? = null,
    val pinRef: FileRef? = null,
)

@Immutable
internal data class LibraryNavGroup(
    val kind: LibraryNavGroupKind,
    val title: String,
    val entries: List<LibraryNavEntry>,
    val foldable: Boolean = false,
    val footer: String? = null,
    /** 组末的操作行（例如“添加外部文件夹”），不随折叠隐藏，点按执行 [onAction] 而不是打开页面。 */
    val action: LibraryNavEntry? = null,
    val onAction: () -> Unit = {},
)

/** 左列超过这个数量的位置折叠起来。 */
private const val VISIBLE_LOCATIONS = 6

internal fun FileLocation.navIcon(): ImageVector = when (storageKind) {
    FileLocationStorageKind.SHARED_STORAGE -> Icons.Rounded.SdStorage
    FileLocationStorageKind.EXTERNAL_MOUNT -> Icons.Rounded.FolderOpen
    FileLocationStorageKind.BRANCH_WORKTREE -> Icons.Rounded.AccountTree
    FileLocationStorageKind.LINUX_HOME -> Icons.Rounded.Terminal
    FileLocationStorageKind.APP_PROJECT, FileLocationStorageKind.RETAINED_SESSION -> Icons.Rounded.Work
    FileLocationStorageKind.BUILTIN -> when (kind) {
        FileLocationKind.KNOWLEDGE_BASE -> Icons.Rounded.LocalLibrary
        FileLocationKind.SESSION_WORKSPACE -> Icons.Rounded.FolderShared
        else -> Icons.Rounded.FolderOpen
    }
}

internal fun pinTitle(ref: FileRef): String = ref.path.trimEnd('/').substringAfterLast('/').ifEmpty { ref.path }

/**
 * 固定文件夹在侧栏的标题与副标题。会话文件夹不在位置列表里：会话根以会话名为标题、“会话工作区”为副标题；
 * 子文件夹的副标题为“会话工作区 · 会话名”。
 */
internal fun pinLabels(ref: FileRef, locations: LibraryLocations): Pair<String, String?> {
    val listed = locations.locations.firstOrNull { it.id == ref.locationId }
    val session = locations.sessionNames[ref.locationId]
    val parent = locations.locations.firstOrNull { it.id == "sessions" }?.title
    val root = ref.path.trimEnd('/').isEmpty()
    return when {
        listed != null -> (if (root) listed.title else pinTitle(ref)) to listed.title.takeUnless { root }
        session != null && root -> session to parent
        session != null -> pinTitle(ref) to listOfNotNull(parent, session).joinToString(" · ") // wrap-check: allow 单行省略号副标题
        else -> pinTitle(ref) to null
    }
}

@Composable
private fun FileLocation.navSubtitle(accessLostText: String): String? {
    if (accessLost) return accessLostText
    val git = gitLabel()
    val originAndName = when (storageKind) {
        FileLocationStorageKind.BUILTIN, FileLocationStorageKind.LINUX_HOME -> null
        FileLocationStorageKind.RETAINED_SESSION -> listOfNotNull(
            git ?: stringResource(R.string.library_location_retained_session),
            storageName,
        ).joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行横滑副标题
        FileLocationStorageKind.APP_PROJECT -> listOfNotNull(
            git ?: stringResource(R.string.library_location_app_workspace).takeIf { storageName == null || storageName == title },
            storageName?.takeIf { it != title || git != null },
        ).joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行横滑副标题
        FileLocationStorageKind.BRANCH_WORKTREE -> git ?: stringResource(R.string.library_location_branch_worktree)
        FileLocationStorageKind.SHARED_STORAGE -> listOfNotNull(git, storageName)
            .joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行横滑副标题
        FileLocationStorageKind.EXTERNAL_MOUNT -> listOfNotNull(
            git,
            storageName ?: stringResource(R.string.library_location_external_mount),
        ).joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行横滑副标题
    }
    return originAndName ?: git
}

/** 左列与外屏文件页共用的分组导航：内置位置、应用内工作区、外部路径、已固定四分明晰。 */
@Composable
internal fun rememberLibraryNavGroups(host: LibraryPageHost): List<LibraryNavGroup> {
    val pending by host.viewModel.pending.collectAsStateWithLifecycle()
    val overview by host.viewModel.overview.collectAsStateWithLifecycle()
    val locations by host.viewModel.locations.collectAsStateWithLifecycle()
    val newBadge = overview?.newLongTermMemories?.takeIf { it > 0 }?.toString()
    val accessLost = stringResource(R.string.library_location_access_lost)
    val categorized = remember(locations.locations) { categorizeLibraryLocations(locations.locations) }

    @Composable
    fun toEntry(location: FileLocation) = LibraryNavEntry(
        key = LibraryPageKeys.browse(FileRef(location.id, "/")),
        icon = location.navIcon(),
        title = location.title,
        subtitle = location.navSubtitle(accessLost),
        location = location,
    )

    val builtinEntries = categorized.builtin.map { toEntry(it) }
    val appWorkspaceEntries = categorized.appWorkspaces.map { toEntry(it) }
    val externalEntries = categorized.externalPaths.map { toEntry(it) }
    val locationById = remember(locations.locations) { locations.locations.associateBy { it.id } }

    return listOf(
        LibraryNavGroup(
            kind = LibraryNavGroupKind.VIEWS,
            title = stringResource(R.string.library_group_views),
            entries = listOf(
                LibraryNavEntry(LibraryPageKeys.OVERVIEW, Icons.Rounded.Dashboard, stringResource(R.string.library_page_overview)),
                LibraryNavEntry(LibraryPageKeys.RECENT, Icons.Rounded.Schedule, stringResource(R.string.library_page_recent)),
                LibraryNavEntry(LibraryPageKeys.CITED, Icons.Rounded.Insights, stringResource(R.string.library_page_cited)),
                LibraryNavEntry(
                    LibraryPageKeys.PENDING,
                    Icons.Rounded.ReportProblem,
                    stringResource(R.string.library_page_pending),
                    badge = pending.size.takeIf { it > 0 }?.toString(),
                ),
            ),
        ),
        LibraryNavGroup(
            kind = LibraryNavGroupKind.BUILTIN_LOCATIONS,
            title = stringResource(R.string.library_group_builtin_locations),
            entries = builtinEntries,
        ),
        LibraryNavGroup(
            kind = LibraryNavGroupKind.APP_WORKSPACES,
            title = stringResource(R.string.library_group_app_workspaces),
            entries = appWorkspaceEntries,
            foldable = true,
            footer = stringResource(R.string.library_group_app_workspaces_footer),
        ),
        LibraryNavGroup(
            kind = LibraryNavGroupKind.EXTERNAL_PATHS,
            title = stringResource(R.string.library_group_external_paths),
            entries = externalEntries,
            foldable = true,
            // 资料页级外部文件夹：挂在资料页上，不随会话删除。
            action = LibraryNavEntry(
                key = LIBRARY_MOUNTS_ACTION_KEY,
                icon = Icons.Rounded.CreateNewFolder,
                title = stringResource(R.string.library_mounts_entry),
            ),
            onAction = host.onManageMounts,
        ),
        LibraryNavGroup(
            kind = LibraryNavGroupKind.PINS,
            title = stringResource(R.string.library_group_pins),
            entries = locations.pins.map { ref ->
                val (title, subtitle) = pinLabels(ref, locations)
                LibraryNavEntry(
                    key = LibraryPageKeys.browse(ref),
                    icon = Icons.Rounded.PushPin,
                    title = title,
                    subtitle = subtitle,
                    location = locationById[ref.locationId],
                    pinRef = ref,
                )
            },
        ),
        LibraryNavGroup(
            kind = LibraryNavGroupKind.REMEMBERED,
            title = stringResource(R.string.library_group_remembered),
            entries = listOf(
                LibraryNavEntry(LibraryPageKeys.MEMORY, Icons.Rounded.Psychology, stringResource(R.string.library_page_memory), badge = newBadge),
                LibraryNavEntry(LibraryPageKeys.FRAGMENTS, Icons.Rounded.Forum, stringResource(R.string.library_page_fragments)),
                LibraryNavEntry(LibraryPageKeys.GRAPH, Icons.Rounded.Hub, stringResource(R.string.library_page_graph)),
            ),
        ),
    ).filter { it.entries.isNotEmpty() || it.action != null }
}

/** 内屏左列：标题、添加入口、问资料与四组导航。 */
@Composable
internal fun LibraryNavColumn(host: LibraryPageHost, addMenu: @Composable () -> Unit) {
    val groups = rememberLibraryNavGroups(host)
    val selected = libraryRootKey(host.navigator.stack)
    var locationsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(LibraryTags.NAV_COLUMN),
    ) {
        InfinityTabHeader(title = stringResource(R.string.nav_tab_library)) { addMenu() }
        LibraryAskField(
            query = host.askQuery,
            onQueryChange = host.ask.onQueryChange,
            onSearch = host.ask.onSearch,
            modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = InfinitySpacing.XLarge),
        ) {
            groups.forEach { group ->
                item(key = "group-${group.kind}") { LibraryNavGroupTitle(group.title) }
                val folded = group.foldable && !locationsExpanded && group.entries.size > VISIBLE_LOCATIONS
                val visible = if (folded) group.entries.take(VISIBLE_LOCATIONS) else group.entries
                // 同组只要有一项带第二行（如 Git 分支），整组都按双行高排版。
                val twoLine = group.entries.any { it.subtitle != null }
                items(visible, key = { "nav-${group.kind}-${it.key}" }) { entry ->
                    val locationActions = host.locationActions
                    val hasContextMenu = locationActions != null && (entry.location != null || entry.pinRef != null)
                    var menuOpen by remember(entry.key) { mutableStateOf(false) }
                    Box(Modifier.animateItem()) {
                        LibraryNavRow(
                            entry,
                            selected = entry.key == selected,
                            twoLine = twoLine,
                            onClick = { host.navigator.openRoot(entry.key) },
                            onLongClick = if (hasContextMenu) ({ menuOpen = true }) else null,
                        )
                        if (hasContextMenu) {
                            InfinityDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                LibraryLocationContextMenuItems(
                                    entryTitle = entry.title,
                                    location = entry.location,
                                    pinRef = entry.pinRef,
                                    actions = locationActions,
                                    dismiss = { menuOpen = false },
                                )
                            }
                        }
                    }
                }
                if (group.foldable && group.entries.size > VISIBLE_LOCATIONS) {
                    item(key = "fold-${group.kind}") {
                        LibraryFoldRow(
                            modifier = Modifier.animateItem(),
                            twoLine = twoLine,
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
                group.action?.let { action ->
                    item(key = "action-${group.kind}") {
                        LibraryNavRow(action, selected = false, twoLine = twoLine, onClick = group.onAction, modifier = Modifier.animateItem())
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryNavRow(
    entry: LibraryNavEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    twoLine: Boolean = entry.subtitle != null,
    onLongClick: (() -> Unit)? = null,
) {
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
    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(role = Role.Tab, onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .then(clickMod)
            .heightIn(min = navRowHeight(twoLine))
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
private fun LibraryFoldRow(text: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, twoLine: Boolean = false) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(InfinityMotion.DurationMedium), label = "fold-arrow")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = navRowHeight(twoLine))
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

/** 展开后的图标条：浏览、首个内置位置与智能体记住的。 */
@Composable
internal fun LibraryStrip(host: LibraryPageHost) {
    val groups = rememberLibraryNavGroups(host)
    val selected = libraryRootKey(host.navigator.stack)
    val entries = groups.flatMap { group ->
        when (group.kind) {
            LibraryNavGroupKind.VIEWS, LibraryNavGroupKind.REMEMBERED -> group.entries
            LibraryNavGroupKind.BUILTIN_LOCATIONS -> group.entries.take(1)
            LibraryNavGroupKind.APP_WORKSPACES, LibraryNavGroupKind.EXTERNAL_PATHS, LibraryNavGroupKind.PINS -> emptyList()
        }
    }
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

/** 问资料输入框：与全站搜索同一原子组件，输入即在右列（外屏为整页）显示关键词结果，按搜索键语义检索并作答。 */
@Composable
internal fun LibraryAskField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /** 获得焦点时调用：外屏首页借此进入结果页。 */
    onFocused: (() -> Unit)? = null,
    /** 为 true 时进入组合后请求焦点并复位，用于从首页接过输入。 */
    focusOnEnter: MutableState<Boolean>? = null,
) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusOnEnter) {
        if (focusOnEnter?.value == true) {
            focusOnEnter.value = false
            runCatching { requester.requestFocus() }
        }
    }
    InfinitySearchBar(
        value = query,
        onValueChange = onQueryChange,
        // 按搜索键后收起键盘，让答案与结果露出来。
        onSearch = {
            focusManager.clearFocus()
            onSearch()
        },
        placeholder = stringResource(R.string.library_ask_placeholder),
        // 整条搜索框（含清空按钮）点按时保持焦点，框外点按才退出输入。
        modifier = modifier.keepsFocusOnTap(),
        fieldModifier = Modifier
            .testTag(LibraryTags.ASK_FIELD)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
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

/** 内屏右列的页面层级路径：[titles] 自根页面到当前页，点某一级回到那一层（下标同 [titles]）。 */
internal class LibraryCrumbs(val titles: List<String>, val onGo: (Int) -> Unit)

/**
 * 资料页右列与外屏子页的页头：最左侧为展开 / 还原（内屏）或返回（外屏），然后是标题。
 * [crumbs] 给出时页头下方是与文件夹列表相同的路径条，上级可点；内屏页头不放返回图标。
 */
@Composable
internal fun LibraryPageHeader(
    title: String,
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
    modifier: Modifier = Modifier,
    meta: (@Composable RowScope.() -> Unit)? = null,
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
            if (leading is LibraryLeading.Expand) {
                // 内屏右列是左列的下级：16sp 标题加信息行，视觉重心留给左列一级标题。
                InfinityPaneHeaderTitle(title = title, meta = meta, modifier = Modifier.weight(1f))
            } else {
                InfinityHeaderTitle(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            actions()
        }
        if (crumbs != null && crumbs.titles.size > 1) {
            FileBreadcrumbs(
                crumbs = crumbs.titles.mapIndexed { index, crumb -> FileCrumb(crumb, index.toString()) },
                onGo = { index -> index.toIntOrNull()?.let(crumbs.onGo) },
                modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.Small, end = InfinitySpacing.XSmall),
                tagPrefix = LibraryTags.CRUMB,
            )
        }
    }
}

/** 外屏“文件”页的分类位置与已固定项：四分组圆角列表，行尾保持小三角箭头，长按条目打开黑曜石管理菜单。 */
@Composable
internal fun LibraryLocationRows(host: LibraryPageHost) {
    val groups = rememberLibraryNavGroups(host)
    val locationActions = host.locationActions
    groups.filter {
        it.kind in setOf(
            LibraryNavGroupKind.BUILTIN_LOCATIONS,
            LibraryNavGroupKind.APP_WORKSPACES,
            LibraryNavGroupKind.EXTERNAL_PATHS,
            LibraryNavGroupKind.PINS,
        )
    }.forEach { group ->
        InfinitySettingsSection(title = group.title, footer = group.footer) {
            group.entries.forEach { entry ->
                val hasContextMenu = locationActions != null && (entry.location != null || entry.pinRef != null)
                var menuOpen by remember(entry.key) { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth()) {
                    InfinitySettingsItem(
                        icon = entry.icon,
                        title = entry.title,
                        subtitle = entry.subtitle,
                        onClick = { host.navigator.push(entry.key) },
                        onLongClick = if (hasContextMenu) ({ menuOpen = true }) else null,
                        showChevron = true,
                        modifier = Modifier.testTag(LibraryTags.navItem(entry.key)),
                    )
                    if (hasContextMenu) {
                        InfinityDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            LibraryLocationContextMenuItems(
                                entryTitle = entry.title,
                                location = entry.location,
                                pinRef = entry.pinRef,
                                actions = locationActions,
                                dismiss = { menuOpen = false },
                            )
                        }
                    }
                }
            }
            group.action?.let { action ->
                InfinitySettingsItem(
                    icon = action.icon,
                    title = action.title,
                    onClick = group.onAction,
                    showChevron = true,
                    modifier = Modifier.testTag(LibraryTags.navItem(action.key)),
                )
            }
        }
    }
}

/** 位置分组末“外部文件夹”操作行的标识，只用于测试标签。 */
internal const val LIBRARY_MOUNTS_ACTION_KEY = "action:mounts"

/** 导航行高：单行为最小触控高度；双行为标题加一行小字再加上下内边距。 */
private fun navRowHeight(twoLine: Boolean) = if (twoLine) NAV_ROW_TWO_LINE_HEIGHT else InfinitySpacing.MinimumTouchTarget

private val NAV_ROW_TWO_LINE_HEIGHT = 56.dp
