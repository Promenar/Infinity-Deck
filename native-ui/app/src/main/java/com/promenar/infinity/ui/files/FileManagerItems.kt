package com.promenar.infinity.ui.files

import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.common.IndexingIndicator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import com.promenar.infinity.domain.files.FileCollectionTag
import com.promenar.infinity.domain.files.FileGraphState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.FileSort
import com.promenar.infinity.domain.files.FileSortKey
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuHeader
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 资源管理器的条目级部件：类型徽标、资料状态徽标、面包屑、排序按钮、列表行、网格格子与“..”上一级。
 */

private val RowShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(16.dp)

/** 文件类型徽标：文件夹为图标，文件为扩展名缩写，没有扩展名时退回类别图标。 */
@Composable
internal fun FileTypeBadge(item: FileItem, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val kind = fileKindOf(item)
    val (container, content) = fileKindColors(kind)
    val label = fileBadgeLabel(item)
    Box(
        modifier = modifier
            .size(size)
            .background(container, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isEmpty() || kind == FileKind.FOLDER) {
            Icon(fileKindIcon(kind), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** “..”与文件夹共用的文件夹徽标。 */
@Composable
private fun FolderBadge(size: Dp = 40.dp) {
    val (container, content) = fileKindColors(FileKind.FOLDER)
    Box(
        modifier = Modifier.size(size).background(container, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun fileKindColors(kind: FileKind): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val d = MaterialTheme.infinityDomainColors
    return when (kind) {
        FileKind.FOLDER -> c.primaryContainer to c.onPrimaryContainer
        FileKind.PDF -> c.errorContainer to c.onErrorContainer
        FileKind.DOCUMENT, FileKind.TEXT -> c.secondaryContainer to c.onSecondaryContainer
        FileKind.CODE -> d.successContainer to d.onSuccessContainer
        FileKind.IMAGE -> c.tertiaryContainer to c.onTertiaryContainer
        FileKind.AUDIO -> d.warningContainer to d.onWarningContainer
        FileKind.VIDEO -> d.infoContainer to d.onInfoContainer
        FileKind.ARCHIVE, FileKind.OTHER -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

/** 资料状态徽标：只对解析失败、排队中、已改动待重建与索引中显示；排队与索引中用资料首页同款索引指示代替圆点。 */
@Composable
internal fun KnowledgeBadge(knowledge: FileKnowledge?, modifier: Modifier = Modifier) {
    val state = knowledge?.state ?: return
    val label = knowledgeBadgeLabel(state) ?: return
    val color = knowledgeStateColor(state)
    Row(
        modifier = modifier.padding(horizontal = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val motion = knowledge.indexMotionState()
        if (motion != null) IndexingIndicator(motion) else Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

/** 行尾状态位：索引需要留意时显示索引徽标，否则显示图谱状态（已抽取为图谱图标，其余为短语）。 */
@Composable
internal fun FileStatusBadge(knowledge: FileKnowledge?, modifier: Modifier = Modifier) {
    when (val status = knowledge?.rowStatus()) {
        null -> Unit
        is FileRowStatus.Index -> KnowledgeBadge(knowledge, modifier)
        is FileRowStatus.Graph -> GraphStatusBadge(status.state, modifier)
    }
}

@Composable
private fun GraphStatusBadge(state: FileGraphState, modifier: Modifier = Modifier) {
    val label = graphStatusLabel(state) ?: return
    if (state == FileGraphState.EXTRACTED) {
        Icon(
            Icons.Rounded.Hub,
            contentDescription = stringResource(label),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = modifier.padding(horizontal = InfinitySpacing.XSmall).size(18.dp),
        )
    } else {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.infinityDomainColors.warning,
            maxLines = 1,
            modifier = modifier.padding(horizontal = InfinitySpacing.XSmall),
        )
    }
}

/** 文档归属与所在文件夹不同时跟在文件名后的合集标签。 */
@Composable
internal fun FileCollectionTagChip(tag: FileCollectionTag, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    Surface(
        modifier = modifier.widthIn(max = 132.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = tag.label(resources),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun knowledgeStateColor(state: KnowledgeIndexState): Color = when (state) {
    KnowledgeIndexState.FAILED -> MaterialTheme.colorScheme.error
    KnowledgeIndexState.QUEUED -> MaterialTheme.infinityDomainColors.info
    KnowledgeIndexState.STALE -> MaterialTheme.infinityDomainColors.warning
    KnowledgeIndexState.INDEXING -> MaterialTheme.colorScheme.primary
    KnowledgeIndexState.INDEXED -> MaterialTheme.infinityDomainColors.success
    KnowledgeIndexState.NONE, KnowledgeIndexState.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 面包屑：逐级可点，始终单行。层级深、放不下时停在末级（当前目录），开头一侧渐隐，可横向拖动查看完整路径；
 * 进入下一级时自动滑到末尾。[crumbs] 含根目录，最后一级为当前目录，不可点。
 */
@Composable
internal fun FileBreadcrumbs(
    crumbs: List<FileCrumb>,
    onGo: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = UiTags.FM_CRUMB,
) {
    if (crumbs.isEmpty()) return
    val current = crumbs.last()
    InfinityScrollingLine(modifier = modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget), alignEnd = true) {
        crumbs.dropLast(1).forEach { crumb ->
            CrumbLink(crumb, onGo, tagPrefix)
            CrumbSeparator()
        }
        Box(Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = current.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun CrumbLink(crumb: FileCrumb, onGo: (String) -> Unit, tagPrefix: String) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { onGo(crumb.path) }
            .padding(horizontal = 4.dp)
            .testTag("$tagPrefix:${crumb.path}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = crumb.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun CrumbSeparator() {
    Box(Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget), contentAlignment = Alignment.Center) {
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 面包屑行右侧的排序按钮：显示当前排序键与方向，点开菜单切换。 */
@Composable
internal fun FileSortButton(
    sort: FileSort,
    onKey: (FileSortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleFoldersFirst: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val keyLabel = stringResource(sortKeyLabel(sort.key))
    val directionLabel = stringResource(if (sort.ascending) R.string.fm_sort_ascending else R.string.fm_sort_descending)
    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_sort_title)) { open = true }
                .padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(UiTags.FM_SORT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(keyLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(
                if (sort.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = directionLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InfinityMenuHeader(stringResource(R.string.fm_sort_title))
            FileSortKey.entries.forEach { key ->
                InfinityMenuItem(
                    text = { Text(stringResource(sortKeyLabel(key))) },
                    selected = sort.key == key,
                    onClick = {
                        open = false
                        if (sort.key != key) onKey(key)
                    },
                )
            }
            InfinityMenuDivider()
            InfinityMenuItem(
                text = { Text(stringResource(R.string.fm_sort_ascending)) },
                selected = sort.ascending,
                onClick = { if (!sort.ascending) onToggleDirection() },
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.fm_sort_descending)) },
                selected = !sort.ascending,
                onClick = { if (sort.ascending) onToggleDirection() },
            )
            InfinityMenuDivider()
            InfinityMenuItem(
                text = { Text(stringResource(R.string.fm_sort_folders_first)) },
                selected = sort.foldersFirst,
                onClick = onToggleFoldersFirst,
            )
        }
    }
}

/**
 * 条目元信息：文件为“大小 · 时间”，文件夹为“N 项 · 时间”（子项数未知时只显示时间）。
 * 存储空间文件夹有合集进度时，时间换成“作品 · 已抽取 n/m”。
 */
@Composable
internal fun fileMetaSegments(item: FileItem): List<String> {
    val time = formatFileTime(item.lastModified)
    val collection = item.knowledge?.folderCollection?.takeIf { item.isDirectory }
    if (collection != null) {
        val count = item.childCount
        val head = if (count != null) listOf(androidx.compose.ui.res.pluralStringResource(R.plurals.fm_item_count, count, count)) else emptyList()
        return head + collection.metaSegments(LocalContext.current.resources)
    }
    return if (item.isDirectory) {
        val count = item.childCount
        if (count != null) {
            listOf(androidx.compose.ui.res.pluralStringResource(R.plurals.fm_item_count, count, count), time)
        } else {
            listOf(time)
        }
    } else {
        listOf(formatFileSize(item.sizeBytes), time)
    }
}

/**
 * 列表行：类型徽标 + 名称 + 元信息 + 资料状态 + 行尾 ⋮。
 * 点行打开；长按或点行首徽标进入选择模式；选择模式下行首换成复选框，点行切换选中，不显示 ⋮。
 * [location] 非 null 时（搜索结果）副文字改为所在路径。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    item: FileItem,
    highlighted: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    location: String? = null,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        checked -> scheme.secondaryContainer
        highlighted -> scheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    val toggleLabel = stringResource(if (checked) R.string.fm_deselect_item else R.string.fm_select_item, item.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small)
            .clip(RowShape)
            .background(background)
            .combinedClickable(
                onClick = { if (selecting) onToggleSelection() else onClick() },
                onLongClick = if (location == null) onToggleSelection else null,
                onLongClickLabel = if (location == null) toggleLabel else null,
            )
            .semantics { if (selecting) selected = checked }
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(start = 4.dp, end = 4.dp)
            .testTag(UiTags.fmRow(item.ref.path)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .clip(CircleShape)
                .then(
                    if (location == null) {
                        Modifier.clickable(role = Role.Checkbox, onClickLabel = toggleLabel, onClick = onToggleSelection)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selecting) {
                Icon(
                    imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (checked) scheme.primary else scheme.onSurfaceVariant,
                )
            } else {
                FileTypeBadge(item)
            }
        }
        Spacer(Modifier.width(InfinitySpacing.Small))
        Column(Modifier.weight(1f).padding(vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val tag = item.knowledge?.collectionTag
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) FileCollectionTagChip(tag)
            }
            if (location != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                InfinityMetaLine(
                    segments = fileMetaSegments(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        FileStatusBadge(item.knowledge)
        if (menu != null && !selecting) {
            RowMenuButton(item, menu)
        } else {
            Spacer(Modifier.width(InfinitySpacing.Small))
        }
    }
}

@Composable
private fun RowMenuButton(item: FileItem, menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.fmRowMenu(item.ref.path))) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.fm_item_actions, item.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu { open = false }
        }
    }
}

/** 列表第一行“..”：上一级 · 父文件夹名。 */
@Composable
internal fun FileUpRow(parentTitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small)
            .clip(RowShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_up_level), onClick = onClick)
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(horizontal = 4.dp)
            .testTag(UiTags.FM_UP_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(InfinitySpacing.MinimumTouchTarget), contentAlignment = Alignment.Center) { FolderBadge() }
        Spacer(Modifier.width(InfinitySpacing.Small))
        Column(Modifier.weight(1f).padding(vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "..",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            InfinityMetaLine(
                segments = listOf(stringResource(R.string.fm_up_level), parentTitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** 网格格子：徽标在上，名称最多两行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTile(
    item: FileItem,
    highlighted: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val toggleLabel = stringResource(if (checked) R.string.fm_deselect_item else R.string.fm_select_item, item.name)
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(if (highlighted && !checked) scheme.surfaceContainerHighest else scheme.surfaceContainerLow)
            .then(if (checked) Modifier.border(2.dp, scheme.primary, TileShape) else Modifier)
            .combinedClickable(
                onClick = { if (selecting) onToggleSelection() else onClick() },
                onLongClick = onToggleSelection,
                onLongClickLabel = toggleLabel,
            )
            .semantics { if (selecting) selected = checked }
            .padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.Medium)
            .testTag(UiTags.fmRow(item.ref.path)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            FileTypeBadge(item, size = 48.dp)
            if (selecting) {
                Icon(
                    imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (checked) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).background(scheme.surface, RoundedCornerShape(4.dp)),
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        item.knowledge?.collectionTag?.let { FileCollectionTagChip(it) }
        FileStatusBadge(item.knowledge)
    }
}

/** 网格第一格“..”。 */
@Composable
internal fun FileUpTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fm_up_level), onClick = onClick)
            .padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.Medium)
            .testTag(UiTags.FM_UP_ROW),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        FolderBadge(size = 48.dp)
        Text(
            text = "..",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
