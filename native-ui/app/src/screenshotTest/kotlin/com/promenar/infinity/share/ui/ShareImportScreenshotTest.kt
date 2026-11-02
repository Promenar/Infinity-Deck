package com.promenar.infinity.share.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportOptions
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareIndexStatus
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareLinuxAvailability
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.share.core.ShareTargetKind
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val Items = listOf(
    ShareImportItem(Uri.parse("content://preview/share/1"), "2026Q3 财报.pdf", "application/pdf", 3_250_586L),
    ShareImportItem(Uri.parse("content://preview/share/2"), "chat_backup.sqlite", "application/vnd.sqlite3", 13_002_342L, parseable = false),
    ShareImportItem(Uri.parse("content://preview/share/3"), "设计模式.epub", "application/epub+zip", 6_081_740L, parseable = false),
)

private val Options = ShareImportOptions(
    currentSession = ShareSessionChoice("session-1", "Linux 迁移方案讨论", 0L),
    knowledgeRootUuid = "kb-root",
    linux = ShareLinuxAvailability.Ready,
)

private val LinuxState = ShareImportUiState(
    visible = true,
    items = Items,
    options = Options,
    selectedKind = ShareTargetKind.Linux,
    linuxDirectory = "/root/inbox",
)

private val KnowledgeState = ShareImportUiState(
    visible = true,
    items = Items,
    options = Options.copy(linux = ShareLinuxAvailability.NeedsInstall),
    selectedKind = ShareTargetKind.KnowledgeBase,
    knowledgeFolder = ShareKnowledgeFolder("kb-finance", "财务", "/资料/财务"),
)

private val DoneState = LinuxState.copy(items = Items.map { it.copy(status = ShareImportStatus.Created) })

private val DoneIndexFailedState = KnowledgeState.copy(
    items = Items.mapIndexed { index, item ->
        item.copy(status = ShareImportStatus.Created, indexStatus = if (index == 0) ShareIndexStatus.Failed else null)
    },
)

@Composable
private fun Sheet(mode: InfinityThemeMode, state: ShareImportUiState) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth()) {
                ShareImportSheetContent(
                    state = state,
                    onSelectTarget = {},
                    onImport = {},
                    onRetry = {},
                    onClose = {},
                    onRequestCancel = {},
                    pickers = ShareImportPickerActions(),
                    onOpenLinuxPicker = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "分享导入 Linux 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "分享导入 Linux 深英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun shareImportLinuxPreview() = Sheet(InfinityThemeMode.DARK, LinuxState)

@PreviewTest
@Preview(name = "分享导入 资料库 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "分享导入 资料库 浅英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun shareImportKnowledgePreview() = Sheet(InfinityThemeMode.LIGHT, KnowledgeState)

@PreviewTest
@Preview(name = "分享导入 完成 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun shareImportDonePreview() = Sheet(InfinityThemeMode.DARK, DoneState)

@PreviewTest
@Preview(name = "分享导入 完成含索引失败 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "分享导入 完成含索引失败 浅英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun shareImportDoneIndexFailedPreview() = Sheet(InfinityThemeMode.LIGHT, DoneIndexFailedState)
