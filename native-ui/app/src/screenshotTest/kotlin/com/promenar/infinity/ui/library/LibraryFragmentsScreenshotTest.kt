package com.promenar.infinity.ui.library

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
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.ui.common.ProvideGroupedPress
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 对话片段列表：会话行与设置页列表行同一几何，逐项拼成一个连续分组；含已删除会话与无智能体两种行。
 * 深色中文 411dp 与浅色英文 360dp 1.3 倍字号各一。
 */

private const val NOW = 1_762_300_000_000L

private fun groups(english: Boolean) = listOf(
    FragmentGroup("s1", if (english) "Trip planning for Kyoto" else "京都行程规划", if (english) "Planner" else "行程助手", 12, NOW - 3 * 60_000L),
    FragmentGroup("s2", if (english) "Refactor the sync service" else "重构同步服务", null, 3, NOW - 26 * 3_600_000L),
    FragmentGroup("s3", null, if (english) "Researcher" else "资料研究员", 1, NOW - 9 * 86_400_000L),
)

@Composable
private fun FragmentsSample(mode: InfinityThemeMode, english: Boolean) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val items = groups(english)
            Column(Modifier.padding(16.dp)) {
                items.forEachIndexed { index, group ->
                    ProvideGroupedPress {
                        FragmentGroupRow(group, onOpen = {}, modifier = Modifier.bettboxListGroupItem(index, items.size))
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "对话片段 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun libraryFragmentsDarkPreview() = FragmentsSample(InfinityThemeMode.DARK, english = false)

@PreviewTest
@Preview(name = "对话片段 浅英360 大字", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable
fun libraryFragmentsLightPreview() = FragmentsSample(InfinityThemeMode.LIGHT, english = true)
