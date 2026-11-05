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
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 资料概览顶部：问资料搜索框与外屏统计入口卡（图标 + 页面名 + 数量，文档与图谱补统计口径）。
 * 深色中文 411dp 与浅色英文 360dp 1.3 倍字号各一。
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
