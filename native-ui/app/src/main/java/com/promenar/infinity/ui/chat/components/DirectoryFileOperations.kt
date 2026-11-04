package com.promenar.infinity.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 选择期间的底部操作栏；重命名只对单个条目可用，[onCopy] 为 null 的来源不显示复制。
 *
 * 各来源在 [topActions]（计数行右侧，如全选、权限）与 [extraActions]（删除前，如压缩、在终端打开）补充自身能力。
 */
@Composable
internal fun DirectorySelectionBar(
    count: Int,
    busy: Boolean,
    onCopy: (() -> Unit)?,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable RowScope.() -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) = Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
        Row(Modifier.fillMaxWidth().padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.directory_selected_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            topActions()
            IconButton(onClick = onClear, modifier = Modifier.testTag("directory_selection_clear")) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_btn_cancel))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XSmall)) {
            if (onCopy != null) DirectorySelectionAction(Icons.Rounded.ContentCopy, R.string.directory_action_copy, !busy, onCopy, "directory_action_copy")
            DirectorySelectionAction(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.directory_action_move, !busy, onMove, "directory_action_move")
            DirectorySelectionAction(Icons.Rounded.DriveFileRenameOutline, R.string.directory_action_rename, !busy && count == 1, onRename, "directory_action_rename")
            extraActions()
            DirectorySelectionAction(Icons.Rounded.DeleteOutline, R.string.directory_action_delete, !busy, onDelete, "directory_action_delete", danger = true)
        }
    }
}

/** 选择栏中的一项操作：各项等宽，长标签最多两行。 */
@Composable
internal fun RowScope.DirectorySelectionAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(tag),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** 剪贴板中有条目时的粘贴栏；目标是被剪切条目本身、其子目录或原目录时不能粘贴。 */
@Composable
internal fun DirectoryPasteBar(
    clipboard: DirectoryClipboard,
    canPaste: Boolean,
    busy: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) = Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Text(
            pluralStringResource(
                if (clipboard.cut) R.plurals.directory_clipboard_cut else R.plurals.directory_clipboard_copy,
                clipboard.paths.size,
                clipboard.paths.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InfinityPillRow(modifier = Modifier.fillMaxWidth()) {
            InfinityPillButton(
                text = stringResource(R.string.common_btn_cancel),
                onClick = onCancel,
                enabled = !busy,
            )
            InfinityPillButton(
                text = stringResource(R.string.directory_paste_here),
                onClick = onPaste,
                tone = InfinityPillTone.PRIMARY,
                enabled = canPaste && !busy,
                icon = Icons.Rounded.ContentPaste,
                modifier = Modifier.testTag("directory_paste"),
            )
        }
    }
}
