package com.promenar.infinity.ui.common

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.hierarchicalTransition

/** 一次目录读取的快照；进入子目录时旧快照随退出动画保留，不会被新目录的加载状态覆盖。 */
private data class FolderListing(
    val path: String,
    val depth: Int,
    val entries: List<ExternalFolderEntry>,
    val loading: Boolean,
    val error: String?,
)

private enum class FolderListPhase { Pending, Error, Empty, Content }

/**
 * 目录内容的分组列表：进入子目录与返回上级做方向性共享轴切换，加载、空目录、错误与内容之间交叉淡入。
 * [error] 为已本地化的完整提示；[path] 变化即视为切换目录，[depth] 决定切换方向。
 */
@Composable
fun FolderEntriesList(
    path: String,
    depth: Int,
    entries: List<ExternalFolderEntry>,
    loading: Boolean,
    error: String?,
    emptyText: String,
    onEntryClick: (ExternalFolderEntry) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    listTag: String? = null,
    statusTag: String? = null,
    retryTag: String? = null,
    /** 非 null 时支持多选：长按进入选择，选择期间点按切换，行首显示勾选框。 */
    selection: Set<String>? = null,
    onEntryLongClick: ((ExternalFolderEntry) -> Unit)? = null,
    /** 为 true 时行与行之间画细分隔线。 */
    dividers: Boolean = false,
) = Column(modifier.fillMaxWidth()) {
    AnimatedVisibility(visible = loading, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall),
        )
    }
    AnimatedContent(
        targetState = FolderListing(path, depth, entries, loading, error),
        contentKey = { it.path },
        transitionSpec = { hierarchicalTransition { it.depth } },
        label = "folder_entries_path",
    ) { listing ->
        val phase = when {
            listing.error != null -> FolderListPhase.Error
            listing.entries.isNotEmpty() -> FolderListPhase.Content
            listing.loading -> FolderListPhase.Pending
            else -> FolderListPhase.Empty
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "folder_entries_phase",
        ) { current ->
            when (current) {
                FolderListPhase.Pending -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp))
                FolderListPhase.Error -> FolderStatusMessage(
                    text = listing.error.orEmpty(),
                    error = true,
                    modifier = Modifier.then(if (statusTag != null) Modifier.testTag(statusTag) else Modifier),
                ) {
                    if (onRetry != null) InfinityRetryButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .padding(top = InfinitySpacing.Small)
                            .then(if (retryTag != null) Modifier.testTag(retryTag) else Modifier),
                    )
                }
                FolderListPhase.Empty -> FolderStatusMessage(
                    text = emptyText,
                    modifier = Modifier.then(if (statusTag != null) Modifier.testTag(statusTag) else Modifier),
                )
                FolderListPhase.Content -> FolderEntryRows(listing.entries, onEntryClick, listTag, selection, onEntryLongClick, dividers)
            }
        }
    }
}

@Composable
private fun FolderEntryRows(
    entries: List<ExternalFolderEntry>,
    onEntryClick: (ExternalFolderEntry) -> Unit,
    listTag: String?,
    selection: Set<String>?,
    onEntryLongClick: ((ExternalFolderEntry) -> Unit)?,
    dividers: Boolean,
) {
    val context = LocalContext.current
    ProvideGroupedPress {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .bettboxListGroup()
                .then(if (listTag != null) Modifier.testTag(listTag) else Modifier),
            contentPadding = PaddingValues(vertical = InfinitySpacing.XSmall),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry -> Column {
                if (dividers && index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                val selecting = !selection.isNullOrEmpty()
                val checked = selection?.contains(entry.path) == true
                val subtitle = if (entry.isDirectory) null else Formatter.formatShortFileSize(context, entry.size)
                val leading: @Composable () -> Unit = {
                    Icon(
                        when {
                            checked -> Icons.Rounded.CheckCircle
                            selecting -> Icons.Rounded.RadioButtonUnchecked
                            entry.isDirectory -> Icons.Rounded.Folder
                            else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (checked || (!selecting && entry.isDirectory)) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onEntryLongClick == null) {
                    InfinitySettingsItem(
                        title = entry.name,
                        subtitle = subtitle,
                        onClick = { onEntryClick(entry) },
                        showChevron = entry.isDirectory,
                        leadingContent = leading,
                    )
                } else {
                    InfinitySettingsRowLayout(
                        title = entry.name,
                        subtitle = subtitle,
                        leadingContent = leading,
                        trailingContent = if (entry.isDirectory && !selecting) ({
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }) else null,
                        modifier = Modifier
                            .then(if (checked) Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) else Modifier)
                            .combinedClickable(
                                onClick = { onEntryClick(entry) },
                                onLongClick = { onEntryLongClick(entry) },
                            )
                            .semantics { if (selecting) selected = checked },
                    )
                }
            } }
        }
    }
}

/** 空列表与读取失败的居中提示，图标弱化，文字可读。 */
@Composable
fun FolderStatusMessage(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    icon: ImageVector = Icons.Rounded.FolderOff,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.XXLarge)
            .semantics { if (error) liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = InfinitySpacing.Medium),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.forReading(),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action()
    }
}
