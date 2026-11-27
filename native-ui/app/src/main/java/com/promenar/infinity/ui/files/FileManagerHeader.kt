package com.promenar.infinity.ui.files

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.LocalInfinitySettingsPaneRoot
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import androidx.compose.foundation.layout.PaddingValues
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuHeader
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.files.state.FileSearchState
import com.promenar.infinity.ui.files.state.FileTransferMode
import com.promenar.infinity.ui.files.state.FileViewMode
import com.promenar.infinity.ui.hub.HubExpandButton
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.rememberLastNonNull

/*
 * 资源管理器页头：浏览、选择模式上下文栏与搜索三种形态，同一时刻只显示一种。
 * 浏览页头最多 4 个图标（左侧按钮、搜索、更多），新功能只加菜单项。
 */

private val HeaderMinHeight = 64.dp
private val MenuIconSize = 20.dp

private enum class FileTopBarMode {
    BROWSE,
    SEARCH,
    SELECTION,
}

@Composable
internal fun FileManagerTopBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier = Modifier) {
    val mode = when {
        state.selecting -> FileTopBarMode.SELECTION
        state.search != null -> FileTopBarMode.SEARCH
        else -> FileTopBarMode.BROWSE
    }
    val lastSearch = rememberLastNonNull(state.search)
    AnimatedContent(
        targetState = mode,
        transitionSpec = { InfinityMotion.fadeThrough() },
        modifier = modifier,
        label = "fm_top_bar",
    ) { currentMode ->
        when (currentMode) {
            FileTopBarMode.SELECTION -> FileSelectionBar(state, actions, Modifier)
            FileTopBarMode.SEARCH -> lastSearch?.let { FileSearchBar(state, it, actions, Modifier) }
            FileTopBarMode.BROWSE -> FileBrowseBar(state, actions, Modifier)
        }
    }
}

/** 页头左侧按钮；展开 / 还原沿用对话页的 [HubExpandButton]。 */
@Composable
internal fun FileManagerLeadingButton(leading: FileManagerLeading) {
    when (leading) {
        is FileManagerLeading.Expand -> HubExpandButton(expanded = false, onClick = leading.onExpand)
        is FileManagerLeading.Restore -> HubExpandButton(expanded = true, onClick = leading.onRestore)
        is FileManagerLeading.Close -> IconButton(onClick = leading.onClose, modifier = Modifier.testTag(UiTags.FM_LEADING)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_close))
        }
        is FileManagerLeading.Leave -> IconButton(onClick = leading.onLeave, modifier = Modifier.testTag(UiTags.FM_LEADING)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_back))
        }
        FileManagerLeading.None -> Spacer(Modifier.width(InfinitySpacing.Small))
    }
}

@Composable
private fun FileBrowseBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val host = actions.host
    if (host.settingsPage) {
        FileSettingsBrowseBar(state, actions, modifier)
        return
    }
    val inInspector = com.promenar.infinity.ui.common.LocalInfinityPanePresentation.current ==
        com.promenar.infinity.ui.common.InfinityPanePresentation.Inspector
    val hasLeading = host.leading != FileManagerLeading.None
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (inInspector) Modifier
                        .padding(top = com.promenar.infinity.ui.common.InspectorPanelTopGap)
                        .heightIn(min = com.promenar.infinity.ui.common.InspectorPanelHeaderHeight)
                        .padding(
                            start = if (hasLeading) InfinitySpacing.XSmall else InfinitySpacing.ScreenHorizontal,
                            end = InfinitySpacing.XSmall,
                        )
                    else Modifier
                        .heightIn(min = HeaderMinHeight)
                        .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
                )
                .testTag(UiTags.FM_HEADER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!inInspector || hasLeading) {
                FileManagerLeadingButton(host.leading)
            }
            FileHeaderTitle(state, actions, Modifier.weight(1f), inInspector = inInspector)
            // 内屏的合集信息在标题下的信息行里，外屏用小标签。
            host.folderBadge?.takeUnless { host.leading.isInnerScreen || inInspector }?.let { HostBadge(it) }
            host.headerActions?.invoke(this)
            IconButton(onClick = actions.controller::startSearch, modifier = Modifier.testTag(UiTags.FM_SEARCH)) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.fm_search))
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_MORE)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
                }
                InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    FolderMoreMenu(state, actions) { open = false }
                }
            }
        }
        if (inInspector) {
            val chrome = com.promenar.infinity.ui.common.LocalFrostedChromeMaterial.current
            androidx.compose.material3.HorizontalDivider(
                color = chrome?.edgeDivider?.let { it.copy(alpha = it.alpha * 0.7f) }
                    ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * 设置次级页形态的浏览页头（[FileManagerHostSpec.settingsPage]）：与设置子页同一顶栏，
 * 返回 · 当前文件夹名 · 搜索 · 新建 · 更多。子文件夹里返回先回上一级；右列根层且在根目录时不画返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSettingsBrowseBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val host = actions.host
    val controller = actions.controller
    val leave = (host.leading as? FileManagerLeading.Leave)?.onLeave
    val scheme = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            AnimatedContent(
                targetState = host.title ?: state.currentFolderTitle(),
                transitionSpec = { InfinityMotion.fadeThrough() },
                label = "fm_settings_title",
            ) { text ->
                InfinityHeaderTitle(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.FM_TITLE),
                )
            }
        },
        navigationIcon = {
            when {
                state.canGoUp -> InfinityBackButton(onClick = controller::goUp, modifier = Modifier.testTag(UiTags.FM_LEADING))
                leave != null && !LocalInfinitySettingsPaneRoot.current ->
                    InfinityBackButton(onClick = leave, modifier = Modifier.testTag(UiTags.FM_LEADING))
            }
        },
        actions = {
            IconButton(onClick = controller::startSearch, modifier = Modifier.testTag(UiTags.FM_SEARCH)) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.fm_search))
            }
            if (state.location != null && state.writable()) FileCreateButton(actions)
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_MORE)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
                }
                InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    FolderMoreMenu(state, actions) { open = false }
                }
            }
        },
        // 宿主页面已处理系统栏内边距。
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = scheme.onBackground,
            navigationIconContentColor = scheme.onSurfaceVariant,
            actionIconContentColor = scheme.onSurfaceVariant,
        ),
        modifier = modifier.testTag(UiTags.FM_HEADER),
    )
}

/**
 * 页头的 Git 副标题所指的分支：宿主支持打开 Git 面板且当前位置是 Git 仓库时返回分支标签，
 * 在检视器内统一并入 B 档双行轻页头，根目录不再额外重复展示 Git 状态条。
 */
@Composable
internal fun FileManagerUiState.gitHeaderBranch(host: FileManagerHostSpec): String? {
    if (host.onOpenGit == null) return null
    return location?.gitLabel()
}

/** 非 Git 普通文件夹在检视器双行页头第二行展示的目录来源/属性标签。 */
@StringRes
internal fun plainFolderSubtitleRes(location: FileLocation): Int = when (location.storageKind) {
    FileLocationStorageKind.EXTERNAL_MOUNT ->
        if (location.capabilities.writable) R.string.fm_source_external_folder
        else R.string.fm_source_external_folder_readonly
    FileLocationStorageKind.SFTP_REMOTE ->
        if (location.capabilities.writable) R.string.fm_source_sftp_remote
        else R.string.fm_source_sftp_remote_readonly
    FileLocationStorageKind.SHARED_STORAGE -> R.string.fm_source_shared_storage
    FileLocationStorageKind.APP_PROJECT -> R.string.fm_source_project_workspace
    FileLocationStorageKind.RETAINED_SESSION -> R.string.fm_source_retained_workspace
    FileLocationStorageKind.BRANCH_WORKTREE -> R.string.fm_source_worktree
    FileLocationStorageKind.LINUX_HOME -> R.string.fm_source_linux_home
    FileLocationStorageKind.BUILTIN -> when (location.kind) {
        FileLocationKind.KNOWLEDGE_BASE -> R.string.fm_source_storage
        else -> R.string.fm_source_local_workspace
    }
}

/** B 档双行轻页头的 Git 元信息行：分支树图标 + 等宽分支名 + 改动计数（暖琥珀）或干净态 + 跳转箭头。 */
@Composable
private fun ExplorerGitMetaRow(branch: String, changes: Int?) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Rounded.AccountTree,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = branch,
            style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
            color = scheme.onSurface,
            maxLines = 1,
            softWrap = false, // wrap-check: allow 共享页头信息行固定单行横滑
        )
        if (changes != null) {
            Text(
                text = "·",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.outline,
                maxLines = 1,
                softWrap = false, // wrap-check: allow 共享页头信息行分隔符
            )
            Text(
                text = if (changes > 0) {
                    pluralStringResource(R.plurals.fm_git_changes, changes, changes)
                } else {
                    stringResource(R.string.fm_git_clean)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (changes > 0) domain.onWarningContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false, // wrap-check: allow 共享页头信息行固定单行横滑
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 标题区统一收口为无卡片背景的轻页头：
 * - 检视器与含 Git 跳转的宿主采用与 Git/任务/状态页签一致的 B 档 56dp 大小字双行轻页头：
 *   - Git 仓库（单位置）：上行仓库名，下行「分支树 + 分支名 · 改动数 ›」，整块点按跳转 Git 页签；
 *   - Git 仓库（多位置）：上行位置名 + 主色 ▾ 切位置，下行 Git 元信息点按跳转 Git 页签；
 *   - 非 Git 普通文件夹：上行文件夹/工作区名（多位置带 ▾），下行展示目录属性/来源（避免与标题同名重复）。
 * - 非检视器普通页头保留原有单/双行与位置切换行为。
 */
@Composable
private fun FileHeaderTitle(
    state: FileManagerUiState,
    actions: FileManagerUiActions,
    modifier: Modifier,
    inInspector: Boolean = false,
) {
    val host = actions.host
    val gitBranch = state.gitHeaderBranch(host)
    val onOpenGit = host.onOpenGit
    val hasGitHeader = gitBranch != null && onOpenGit != null
    val title = if (inInspector || hasGitHeader) {
        host.title ?: state.location?.title?.takeIf { it.isNotBlank() } ?: state.currentFolderTitle()
    } else {
        host.title ?: state.currentFolderTitle()
    }
    val switchable = host.allowLocationSwitch && state.locations.size > 1
    val locationTitle = state.location?.title.orEmpty()
    val freeBytes = state.location?.freeBytes
    val plainInspectorSubtitle = if (inInspector && !hasGitHeader) {
        state.location?.let { loc ->
            val sourceLabel = stringResource(plainFolderSubtitleRes(loc))
            sourceLabel.takeUnless { it == title }
                ?: freeBytes?.let { stringResource(R.string.fm_free_space, formatFileSize(it)) }
                ?: stringResource(R.string.fm_source_local_folder)
        }
    } else {
        null
    }
    val subtitle = when {
        plainInspectorSubtitle != null -> plainInspectorSubtitle
        switchable -> locationTitle
        state.path != "/" -> null
        freeBytes != null -> stringResource(R.string.fm_free_space, formatFileSize(freeBytes))
        else -> null
    }
    var open by remember { mutableStateOf(false) }
    val inner = host.leading.isInnerScreen || inInspector || hasGitHeader
    val badge = host.folderBadge?.takeIf { inner && !hasGitHeader }
    val badgeClick = badge?.onClick
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        if (switchable && hasGitHeader) {
            // 多位置 + Git 仓库：上行切位置，下行跳 Git，维持标准 B 档 56dp 高度与零卡片外观。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .padding(horizontal = if (inInspector) 0.dp else InfinitySpacing.Small),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_switch_location)) { open = true }
                        .testTag(UiTags.FM_TITLE),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_git_view), onClick = onOpenGit)
                        .testTag(UiTags.FM_GIT_STRIP),
                ) {
                    ExplorerGitMetaRow(gitBranch, state.location?.gitChangeCount)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        when {
                            hasGitHeader -> Modifier
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_git_view), onClick = onOpenGit)
                                .testTag(UiTags.FM_GIT_STRIP)
                            switchable -> Modifier
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_switch_location)) { open = true }
                            badgeClick != null -> Modifier
                                .clickable(role = Role.Button, onClickLabel = badge.onClickLabel, onClick = badgeClick)
                            else -> Modifier
                        },
                    )
                    .padding(horizontal = if (inInspector) 0.dp else InfinitySpacing.Small)
                    .then(if (!hasGitHeader) Modifier.testTag(UiTags.FM_TITLE) else Modifier),
                verticalArrangement = Arrangement.Center,
            ) {
                if (inner) {
                    when {
                        hasGitHeader -> {
                            InfinityPaneHeaderTitle(
                                title = title,
                                modifier = Modifier.fillMaxWidth(),
                                meta = { ExplorerGitMetaRow(gitBranch, state.location?.gitChangeCount) },
                            )
                        }
                        switchable && inInspector -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (subtitle != null) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        else -> {
                            val metaText = badge?.description ?: subtitle
                            AnimatedContent(targetState = title to metaText, transitionSpec = { InfinityMotion.fadeThrough() }, label = "fm_title") { (text, meta) ->
                                InfinityPaneHeaderTitle(
                                    title = text,
                                    modifier = Modifier.fillMaxWidth(),
                                    meta = meta?.let {
                                        {
                                            badge?.icon?.let { icon ->
                                                Icon(icon, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = meta,
                                                color = if (switchable) scheme.primary else scheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false, // wrap-check: allow 共享页头信息行固定单行横滑
                                                modifier = Modifier.testTag(UiTags.FM_HOST_BADGE),
                                            )
                                            if (switchable) {
                                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else AnimatedContent(targetState = title, transitionSpec = { InfinityMotion.fadeThrough() }, label = "fm_title") { text ->
                    InfinityHeaderTitle(
                        text = text,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (subtitle != null && !inner) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (switchable) scheme.primary else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (switchable) {
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InfinityMenuHeader(stringResource(R.string.fm_locations_title))
            state.locations.forEach { location ->
                InfinityMenuItem(
                    text = { Text(location.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(locationKindIcon(location.kind), contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
                    selected = location.id == state.location?.id,
                    onClick = {
                        open = false
                        if (location.id != state.location?.id) actions.controller.openLocation(location.id)
                    },
                    modifier = Modifier.testTag(UiTags.fmLocation(location.id)),
                )
            }
        }
    }
}

/** 宿主的页头小标签：图标 + 短文字，可点时是按钮；触控区不小于最小触控尺寸。 */
@Composable
private fun HostBadge(badge: FileHeaderBadge) {
    val onClick = badge.onClick
    Box(
        modifier = Modifier
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = badge.onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = badge.description }
            .testTag(UiTags.FM_HOST_BADGE),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            badge.icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)) }
            Text(
                text = badge.text,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 页头“更多”：新建、导入、选择、视图、隐藏文件、固定、文件夹详情与宿主的文件夹动作。 */
@Composable
private fun ColumnScope.FolderMoreMenu(state: FileManagerUiState, actions: FileManagerUiActions, dismiss: () -> Unit) {
    val controller = actions.controller
    val writable = state.writable()
    // 设置次级页形态的新建在页头“＋”里，这里不再重复。
    if (writable && !actions.host.settingsPage) {
        MenuAction(Icons.Rounded.CreateNewFolder, R.string.fm_new_folder, UiTags.FM_MENU_NEW_FOLDER) {
            dismiss()
            actions.openDialog(FileManagerDialog.NewFolder)
        }
        MenuAction(Icons.AutoMirrored.Rounded.NoteAdd, R.string.fm_new_text_file, UiTags.FM_MENU_NEW_FILE) {
            dismiss()
            actions.openDialog(FileManagerDialog.NewTextFile)
        }
        actions.importFromDevice?.let { launch ->
            MenuAction(Icons.Rounded.UploadFile, R.string.fm_import, UiTags.FM_MENU_IMPORT) {
                dismiss()
                launch()
            }
        }
        InfinityMenuDivider()
    }
    InfinityMenuItem(
        text = { Text(stringResource(R.string.fm_select)) },
        leadingIcon = { Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
        enabled = state.items.isNotEmpty(),
        onClick = {
            dismiss()
            val first = state.items.firstOrNull { !it.isDirectory } ?: state.items.firstOrNull()
            if (first != null) controller.toggleSelection(first)
        },
        modifier = Modifier.testTag(UiTags.FM_MENU_SELECT),
    )
    val grid = state.viewMode == FileViewMode.GRID
    MenuAction(
        if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
        if (grid) R.string.fm_view_list else R.string.fm_view_grid,
        UiTags.FM_MENU_VIEW,
    ) {
        dismiss()
        controller.setViewMode(if (grid) FileViewMode.LIST else FileViewMode.GRID)
    }
    MenuAction(
        if (state.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
        if (state.showHidden) R.string.fm_hide_hidden else R.string.fm_show_hidden,
        UiTags.FM_MENU_HIDDEN,
    ) {
        dismiss()
        controller.toggleShowHidden()
    }
    InfinityMenuDivider()
    // 一级位置根目录本身已列在资料页侧栏中，不再提供重复“固定”；仅二级及以上子目录、具体会话目录与已有旧固定可切换。
    val showPinAction = shouldShowPinAction(actions.host.showPin, state)
    if (showPinAction) {
        MenuAction(Icons.Rounded.PushPin, if (state.pinned) R.string.fm_unpin else R.string.fm_pin, UiTags.FM_MENU_PIN) {
            dismiss()
            controller.togglePinCurrentFolder()
        }
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_folder_details, UiTags.FM_MENU_FOLDER_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.FolderDetails)
    }
    val locationId = state.location?.id
    if (locationId != null) {
        var destructiveSeparated = false
        actions.host.folderActions.forEach { action ->
            if (action.destructive && !destructiveSeparated) {
                InfinityMenuDivider()
                destructiveSeparated = true
            }
            InfinityMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
                destructive = action.destructive,
                onClick = {
                    dismiss()
                    action.onInvoke(locationId, state.path)
                },
                modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
            )
        }
    }
}

/** 一级位置根目录（非 session:*）本身已是导航入口，仅在已固定（清理旧数据）或处于子目录/具体会话目录时显示固定菜单项。 */
internal fun shouldShowPinAction(hostShowPin: Boolean, state: FileManagerUiState): Boolean {
    if (!hostShowPin) return false
    if (state.pinned) return true
    val locationId = state.location?.id ?: return false
    val isRoot = state.path.trimEnd('/').isEmpty()
    return !isRoot || locationId.startsWith("session:")
}

@Composable
internal fun MenuAction(
    icon: ImageVector,
    @StringRes label: Int,
    tag: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    InfinityMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
        enabled = enabled,
        destructive = destructive,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** 行尾 ⋮ 与预览页 ⋮ 共用的条目菜单。 */
@Composable
internal fun ColumnScope.FileItemMenu(item: FileItem, state: FileManagerUiState, actions: FileManagerUiActions, dismiss: () -> Unit) {
    val controller = actions.controller
    val writable = state.writable()
    // 会话工作区汇总根的条目是整个会话目录：只能打开或看详情，复制、移动与宿主动作都不适用。
    val sessionEntry = state.location?.id == SESSIONS_LOCATION_ID
    InfinityMenuHeader(item.name)
    if (item.isDirectory) {
        MenuAction(Icons.Rounded.FolderOpen, R.string.fm_open, UiTags.FM_ITEM_OPEN) {
            dismiss()
            controller.open(item)
        }
    } else {
        MenuAction(Icons.AutoMirrored.Rounded.OpenInNew, R.string.fm_open_with, UiTags.FM_ITEM_OPEN) {
            dismiss()
            actions.openWith(item)
        }
    }
    if (writable) {
        MenuAction(Icons.Rounded.DriveFileRenameOutline, R.string.fm_rename, UiTags.FM_ITEM_RENAME) {
            dismiss()
            actions.openDialog(FileManagerDialog.Rename(item))
        }
    }
    if (!sessionEntry) {
        MenuAction(Icons.Rounded.ContentCopy, R.string.fm_copy_to, UiTags.FM_ITEM_COPY) {
            dismiss()
            controller.openTargetPicker(FileTransferMode.COPY, listOf(item))
        }
    }
    if (writable) {
        MenuAction(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.fm_move_to, UiTags.FM_ITEM_MOVE) {
            dismiss()
            controller.openTargetPicker(FileTransferMode.MOVE, listOf(item))
        }
    }
    if (!item.isDirectory) {
        MenuAction(Icons.Rounded.Share, R.string.fm_share, UiTags.FM_ITEM_SHARE) {
            dismiss()
            actions.share(listOf(item))
        }
    }
    if (!sessionEntry) actions.host.actionsFor(listOf(item)).forEach { action ->
        InfinityMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
            onClick = {
                dismiss()
                action.onInvoke(listOf(item))
            },
            modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
        )
    }
    if (item.isZip() && writable && state.location?.capabilities?.archive != false) {
        MenuAction(Icons.Rounded.FolderZip, R.string.fm_extract, UiTags.FM_ITEM_EXTRACT) {
            dismiss()
            controller.extract(item)
        }
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_details, UiTags.FM_ITEM_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.Details(listOf(item)))
    }
    if (state.deletable()) {
        InfinityMenuDivider()
        MenuAction(Icons.Rounded.DeleteOutline, R.string.fm_delete, UiTags.FM_ITEM_DELETE, destructive = true) {
            dismiss()
            actions.openDialog(FileManagerDialog.Delete(listOf(item), fromSelection = false))
        }
    }
}

/** 选择模式的上下文栏：关闭、已选 N、复制到、移动到、分享、删除与更多。 */
@Composable
private fun FileSelectionBar(state: FileManagerUiState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    val selected = state.selectedItems()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = InfinitySpacing.XSmall)
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.primaryContainer)
            .heightIn(min = 56.dp)
            .padding(horizontal = InfinitySpacing.XSmall)
            .testTag(UiTags.FM_SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onPrimaryContainer) {
            IconButton(onClick = controller::clearSelection, modifier = Modifier.testTag(UiTags.FM_SELECTION_CLOSE)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_selection_close))
            }
            Text(
                text = pluralStringResource(R.plurals.fm_selected_count, state.selection.size, state.selection.size),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.XSmall),
            )
            IconButton(
                onClick = { controller.openTargetPicker(FileTransferMode.COPY) },
                modifier = Modifier.testTag(UiTags.FM_SELECTION_COPY),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.fm_copy_to))
            }
            if (state.writable()) {
                IconButton(
                    onClick = { controller.openTargetPicker(FileTransferMode.MOVE) },
                    modifier = Modifier.testTag(UiTags.FM_SELECTION_MOVE),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = stringResource(R.string.fm_move_to))
                }
            }
            IconButton(
                onClick = { actions.share(selected.filterNot { it.isDirectory }) },
                enabled = selected.any { !it.isDirectory },
                modifier = Modifier.testTag(UiTags.FM_SELECTION_SHARE),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.fm_share))
            }
            if (state.deletable()) {
                IconButton(
                    onClick = { actions.openDialog(FileManagerDialog.Delete(selected, fromSelection = true)) },
                    modifier = Modifier.testTag(UiTags.FM_SELECTION_DELETE),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.fm_delete))
                }
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.FM_SELECTION_MORE)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
                }
                InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SelectionMoreMenu(state, selected, actions) { open = false }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SelectionMoreMenu(
    state: FileManagerUiState,
    selected: List<FileItem>,
    actions: FileManagerUiActions,
    dismiss: () -> Unit,
) {
    val controller = actions.controller
    val writable = state.writable()
    MenuAction(Icons.Rounded.SelectAll, R.string.fm_select_all, UiTags.FM_SELECTION_ALL) {
        dismiss()
        controller.selectAll()
    }
    if (writable) {
        MenuAction(Icons.Rounded.DriveFileRenameOutline, R.string.fm_rename, UiTags.FM_ITEM_RENAME, enabled = selected.size == 1) {
            dismiss()
            selected.singleOrNull()?.let { actions.openDialog(FileManagerDialog.Rename(it)) }
        }
        if (state.location?.capabilities?.archive != false) {
            MenuAction(Icons.Rounded.FolderZip, R.string.fm_compress, UiTags.FM_SELECTION_COMPRESS) {
                dismiss()
                actions.openDialog(FileManagerDialog.Compress(selected, fromSelection = true))
            }
        }
    }
    actions.host.actionsFor(selected).forEach { action ->
        InfinityMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(MenuIconSize)) },
            onClick = {
                dismiss()
                action.onInvoke(selected)
            },
            modifier = Modifier.testTag(UiTags.fmHostAction(action.id)),
        )
    }
    MenuAction(Icons.Rounded.Info, R.string.fm_details, UiTags.FM_ITEM_DETAILS) {
        dismiss()
        actions.openDialog(FileManagerDialog.Details(selected))
    }
}

/** 搜索页头：返回 + 自动聚焦的输入框，下方是范围、类型与修改时间筛选。 */
@Composable
private fun FileSearchBar(state: FileManagerUiState, search: FileSearchState, actions: FileManagerUiActions, modifier: Modifier) {
    val controller = actions.controller
    var text by rememberSaveable { mutableStateOf(search.text) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(modifier.fillMaxWidth().testTag(UiTags.FM_SEARCH_BAR)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HeaderMinHeight)
                .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::closeSearch, modifier = Modifier.testTag(UiTags.FM_SEARCH_CLOSE)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_search_close))
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(end = InfinitySpacing.XSmall),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(start = InfinitySpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(InfinitySpacing.Small))
                    val placeholder = stringResource(R.string.fm_search_placeholder, state.location?.title.orEmpty())
                    BasicTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            controller.updateSearch { it.copy(text = value) }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface).forInput(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag(UiTags.FM_SEARCH_FIELD),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Box(
                        modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = text.isNotEmpty(),
                            enter = fadeIn(tween(InfinityMotion.DurationShort)),
                            exit = fadeOut(tween(InfinityMotion.DurationShort)),
                        ) {
                            IconButton(onClick = {
                                text = ""
                                controller.updateSearch { it.copy(text = "") }
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.fm_search_clear))
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.search != null,
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            FileSearchFilters(search, actions)
        }
    }
}

/** 修改时间筛选档位：不限、24 小时内、7 天内、30 天内。 */
private val ModifiedBuckets = listOf(
    R.string.fm_modified_any to null,
    R.string.fm_modified_day to 1,
    R.string.fm_modified_week to 7,
    R.string.fm_modified_month to 30,
)

@Composable
private fun FileSearchFilters(search: FileSearchState, actions: FileManagerUiActions) {
    val controller = actions.controller
    var scopeOpen by remember { mutableStateOf(false) }
    var typeOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var bucket by rememberSaveable { mutableIntStateOf(0) }
    val activeBucket = if (search.modifiedAfter == null) 0 else bucket.coerceIn(1, ModifiedBuckets.lastIndex)
    // 范围、类型、修改时间各是一个下拉标签，单行排列，放不下时整行横向滚动。
    InfinityTagScrollRow(
        spacing = InfinitySpacing.Small,
        contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium),
    ) {
        Box {
            FilterChip(
                selected = search.withinCurrentFolder,
                onClick = { scopeOpen = true },
                label = {
                    Text(
                        stringResource(if (search.withinCurrentFolder) R.string.fm_search_scope_folder else R.string.fm_search_scope_location),
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(UiTags.FM_SEARCH_SCOPE),
            )
            InfinityDropdownMenu(expanded = scopeOpen, onDismissRequest = { scopeOpen = false }) {
                listOf(
                    Triple(false, R.string.fm_search_scope_location, UiTags.FM_SEARCH_SCOPE_ALL),
                    Triple(true, R.string.fm_search_scope_folder, UiTags.FM_SEARCH_SCOPE_FOLDER),
                ).forEach { (withinFolder, label, tag) ->
                    InfinityMenuItem(
                        text = { Text(stringResource(label)) },
                        selected = search.withinCurrentFolder == withinFolder,
                        onClick = {
                            scopeOpen = false
                            controller.updateSearch { it.copy(withinCurrentFolder = withinFolder) }
                        },
                        modifier = Modifier.testTag(tag),
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = search.types.isNotEmpty(),
                onClick = { typeOpen = true },
                label = {
                    Text(
                        text = if (search.types.isEmpty()) {
                            stringResource(R.string.fm_search_type)
                        } else {
                            stringResource(R.string.fm_search_type_count, search.types.size)
                        },
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(UiTags.FM_SEARCH_TYPE),
            )
            InfinityDropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                FileTypeFilter.entries.forEach { filter ->
                    InfinityMenuItem(
                        text = { Text(stringResource(typeFilterLabel(filter))) },
                        selected = filter in search.types,
                        onClick = {
                            controller.updateSearch {
                                it.copy(types = if (filter in it.types) it.types - filter else it.types + filter)
                            }
                        },
                    )
                }
                if (search.types.isNotEmpty()) {
                    InfinityMenuDivider()
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.fm_search_type_clear)) },
                        onClick = {
                            typeOpen = false
                            controller.updateSearch { it.copy(types = emptySet()) }
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = activeBucket != 0,
                onClick = { timeOpen = true },
                label = {
                    Text(
                        text = stringResource(if (activeBucket == 0) R.string.fm_search_modified else ModifiedBuckets[activeBucket].first),
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(UiTags.FM_SEARCH_MODIFIED),
            )
            InfinityDropdownMenu(expanded = timeOpen, onDismissRequest = { timeOpen = false }) {
                ModifiedBuckets.forEachIndexed { index, (label, days) ->
                    InfinityMenuItem(
                        text = { Text(stringResource(label)) },
                        selected = index == activeBucket,
                        onClick = {
                            timeOpen = false
                            bucket = index
                            val after = days?.let { System.currentTimeMillis() - it * DayMillis }
                            controller.updateSearch { it.copy(modifiedAfter = after) }
                        },
                    )
                }
            }
        }
    }
}

private const val DayMillis = 24L * 60 * 60 * 1000

internal fun FileManagerUiState.writable(): Boolean = location?.capabilities?.writable ?: true

/** 只有可写且支持删除的位置才提供删除操作。 */
internal fun FileManagerUiState.deletable(): Boolean =
    writable() && (location?.capabilities?.deletable ?: true)

internal fun FileManagerUiState.selectedItems(): List<FileItem> = items.filter { it.ref.path in selection }
