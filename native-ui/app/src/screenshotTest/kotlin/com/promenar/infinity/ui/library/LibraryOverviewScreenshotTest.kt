package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 资料概览顶部：问资料搜索框与外屏统计入口卡（图标 + 页面名 + 数量，文档与图谱补统计口径）。
 * 深色中文 411dp 与浅色英文 360dp 1.3 倍字号各一。
 * 宽版四张卡：固定两列各自堆叠。待处理为空、最近加入有多条时，“最近被引用”紧接在待处理卡下方；
 * 两列条数相当的情况另取一张。示例数据均为虚构。
 */

private val SAMPLE = LibraryOverview(
    indexedDocuments = 128, longTermMemories = 42, newLongTermMemories = 3,
    graphEntities = 356, graphRelations = 1204, fragments = 87,
)

@Composable
private fun OverviewSample(mode: InfinityThemeMode, query: String) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LibraryAskField(query = query, onQueryChange = {}, onSearch = {})
                LibraryKpiGrid(SAMPLE, entryCards = true, wide = false) { _, _ -> }
            }
        }
    }
}

@PreviewTest
@Preview(name = "资料概览 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun libraryOverviewDarkPreview() = OverviewSample(InfinityThemeMode.DARK, query = "")

@PreviewTest
@Preview(name = "资料概览 浅英360 大字", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable
fun libraryOverviewLightPreview() = OverviewSample(InfinityThemeMode.LIGHT, query = "release notes")

private fun recentSample(zh: Boolean, count: Int): List<LibraryDocumentSummary> = listOf(
    (if (zh) "离线同步方案.md" else "offline-sync.md") to (if (zh) "存储空间 › 架构" else "Storage › architecture"),
    (if (zh) "周会纪要 11-03.md" else "weekly-notes-11-03.md") to (if (zh) "存储空间 › 会议" else "Storage › meetings"),
    (if (zh) "发布检查清单.pdf" else "release-checklist.pdf") to (if (zh) "存储空间" else "Storage"),
    (if (zh) "用户访谈：录音听写.md" else "interview-dictation.md") to (if (zh) "存储空间 › 产品调研" else "Storage › research"),
    (if (zh) "竞品功能对照表.xlsx" else "competitor-matrix.xlsx") to (if (zh) "存储空间 › 产品调研" else "Storage › research"),
).take(count).mapIndexed { index, (name, folder) ->
    LibraryDocumentSummary(
        documentId = "r$index", ref = FileRef("library", "/$name"), name = name, folderPath = folder,
        mimeType = null, sizeBytes = 18_400L * (index + 1), lastModified = System.currentTimeMillis() - 3_600_000L * (index + 2),
    )
}

private fun pendingSample(zh: Boolean): List<PendingDocument> = listOf(
    PendingDocument(
        documentId = "p1", ref = FileRef("library", "/p1"), name = if (zh) "季度复盘.docx" else "quarterly-review.docx",
        folderPath = if (zh) "存储空间 › 会议" else "Storage › meetings", kind = PendingKind.QUEUED, queuePosition = 1,
    ),
    PendingDocument(
        documentId = "p2", ref = FileRef("library", "/p2"), name = if (zh) "接口变更记录.md" else "api-changes.md",
        folderPath = if (zh) "存储空间 › 架构" else "Storage › architecture", kind = PendingKind.STALE,
    ),
)

private fun citedSample(zh: Boolean): List<CitedDocument> = listOf(
    CitedDocument(
        "d1", if (zh) "离线同步方案.md" else "offline-sync.md", 3, System.currentTimeMillis() - 3_600_000,
        if (zh) "离线同步重构" else "Offline sync rework", if (zh) "通用助手" else "Assistant",
    ),
    CitedDocument("d3", if (zh) "发布检查清单.pdf" else "release-checklist.pdf", 1, System.currentTimeMillis() - 86_400_000, null, null),
)

@Composable
private fun OverviewWideSample(mode: InfinityThemeMode, zh: Boolean, pending: List<PendingDocument>, recent: Int) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LibraryKpiGrid(SAMPLE, entryCards = false, wide = true) { _, _ -> }
                LibraryOverviewColumns(
                    pending = { LibraryPendingCard(pending, busy = emptySet(), onViewAll = {}, onOpen = {}, onResolve = {}) },
                    cited = { RecentCitedCardContent(citedSample(zh), onOpen = {}, onViewAll = {}) },
                    recent = { LibraryRecentCard(recentSample(zh, recent), onViewAll = {}, onOpen = {}) },
                    collect = { LibraryCollectCardContent(onImport = {}, onNote = {}) },
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "资料概览 宽版 待处理为空 浅中840", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun libraryOverviewWideEmptyPendingLightPreview() =
    OverviewWideSample(InfinityThemeMode.LIGHT, zh = true, pending = emptyList(), recent = 5)

@PreviewTest
@Preview(name = "资料概览 宽版 待处理为空 深英840 大字", widthDp = 840, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun libraryOverviewWideEmptyPendingDarkPreview() =
    OverviewWideSample(InfinityThemeMode.DARK, zh = false, pending = emptyList(), recent = 5)

@PreviewTest
@Preview(name = "资料概览 宽版 两列相当 深中840", widthDp = 840, heightDp = 760, locale = "zh-rCN")
@Composable
fun libraryOverviewWideBalancedPreview() =
    OverviewWideSample(InfinityThemeMode.DARK, zh = true, pending = pendingSample(zh = true), recent = 2)
