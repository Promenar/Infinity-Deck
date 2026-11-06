package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.CitationSource
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 问资料：输入中、答案完成、未设快速模型、未设向量模型；文档页定位条与命中段；引用卡。
 * 深色中文 411dp 与浅色英文 360dp 1.3 倍字号各一。示例数据均为虚构。
 */


private fun hits(zh: Boolean) = listOf(
    AskHit(
        key = "v1", kind = AskHitKind.DOCUMENT,
        title = if (zh) "离线同步方案.md" else "offline-sync.md",
        location = if (zh) "存储空间 · 架构" else "Storage · architecture",
        snippet = if (zh) "写操作先进入本地离线队列，联网后按入队顺序重放；遇到同步冲突以服务端版本为准，本地副本另存。" else "Writes go to a local offline queue and replay in order once online; on a sync conflict the server wins and the local copy is kept.",
        highlightTerms = if (zh) listOf("离线队列", "同步冲突") else listOf("offline queue", "sync conflict"),
        match = AskMatch.BOTH, similarity = 0.86f, documentId = "d1",
    ),
    AskHit(
        key = "memory:m1", kind = AskHitKind.MEMORY,
        title = if (zh) "长期记忆 · 项目" else "Long-term memory · project",
        location = if (zh) "示例项目 · 2 天前" else "Sample project · 2 days ago",
        snippet = if (zh) "用户希望离线写入失败时不要静默丢弃，而是在界面上能看到“待同步”状态。" else "The user wants failed offline writes to show a pending state instead of being dropped.",
        highlightTerms = emptyList(), match = AskMatch.SEMANTIC, similarity = 0.74f, memoryId = "m1",
    ),
    AskHit(
        key = "file:d2", kind = AskHitKind.DOCUMENT,
        title = if (zh) "周会纪要 11-03.md" else "weekly-notes-11-03.md",
        location = if (zh) "工作区 Notes · 会议" else "Workspace Notes · meetings",
        snippet = if (zh) "离线同步重构排在下周，冲突处理方案待确认；先上线只读缓存。" else "The offline sync rework is next week; conflict handling is still open.",
        highlightTerms = if (zh) listOf("冲突处理") else listOf("conflict handling"),
        match = AskMatch.KEYWORD, similarity = null, documentId = "d2",
    ),
    AskHit(
        key = "entity:e1", kind = AskHitKind.ENTITY,
        title = if (zh) "图谱 · 同步冲突" else "Graph · sync conflict",
        location = if (zh) "2 篇文档 · 5 条关系" else "2 documents · 5 relations",
        snippet = if (zh) "同步冲突 —来自→ 离线队列；—解决于→ 服务端优先。" else "sync conflict —from→ offline queue; —resolved by→ server wins.",
        highlightTerms = emptyList(), match = AskMatch.SEMANTIC, similarity = 0.69f, entityId = "e1",
    ),
)

private val NoActions = LibraryAskActions({}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun Themed(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun AskSample(mode: InfinityThemeMode, state: AskUiState, askMode: AskMode) = Themed(mode) {
    Column {
        LibraryAskField(query = state.query, onQueryChange = {}, onSearch = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LibraryAskResults(state, askMode, NoActions, bottomPadding = 16.dp)
    }
}

private fun answered(zh: Boolean) = AskUiState(
    query = if (zh) "离线写入冲突怎么处理" else "how are offline write conflicts handled",
    phase = AskPhase.DONE,
    askedQuery = "q",
    hits = hits(zh),
    mode = AskMode.FULL,
    answer = if (zh) {
        "离线写入会先排进本地队列，联网后按入队顺序重放[1]；发生冲突时以服务端版本为准，本地副本另存[1][4]。你之前提过失败时要能看到“待同步”状态[2]。"
    } else {
        "Offline writes queue locally and replay in order once online [1]. On a conflict the server wins and the local copy is kept [1][4]. You asked to see a pending state when a write fails [2]."
    },
    citedHitIndexes = listOf(0, 3, 1),
    modelLabel = if (zh) "示例模型 Flash" else "Sample Flash",
    selectedHit = 3,
)

@PreviewTest
@Preview(name = "问资料 答案 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Composable
fun libraryAskAnswerDarkPreview() = AskSample(InfinityThemeMode.DARK, answered(true), AskMode.FULL)

@PreviewTest
@Preview(name = "问资料 答案 浅英360 大字", widthDp = 360, heightDp = 1300, locale = "en", fontScale = 1.3f)
@Composable
fun libraryAskAnswerLightPreview() = AskSample(InfinityThemeMode.LIGHT, answered(false), AskMode.FULL)

@PreviewTest
@Preview(name = "问资料 输入中 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun libraryAskTypingDarkPreview() = AskSample(
    InfinityThemeMode.DARK,
    AskUiState(query = "离线写入冲突", keywordHits = hits(true).filter { it.match == AskMatch.KEYWORD || it.match == AskMatch.BOTH }),
    AskMode.FULL,
)

@PreviewTest
@Preview(name = "问资料 输入中 浅英360 大字", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun libraryAskTypingLightPreview() = AskSample(
    InfinityThemeMode.LIGHT,
    AskUiState(query = "offline conflict", keywordHits = hits(false).filter { it.match == AskMatch.KEYWORD || it.match == AskMatch.BOTH }),
    AskMode.NO_QUICK_MODEL,
)

@PreviewTest
@Preview(name = "问资料 未设快速模型 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun libraryAskNoQuickDarkPreview() = AskSample(
    InfinityThemeMode.DARK,
    AskUiState(query = "离线写入冲突怎么处理", phase = AskPhase.DONE, askedQuery = "q", hits = hits(true), mode = AskMode.NO_QUICK_MODEL),
    AskMode.NO_QUICK_MODEL,
)

@PreviewTest
@Preview(name = "问资料 只按关键词 浅英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun libraryAskKeywordLightPreview() = AskSample(
    InfinityThemeMode.LIGHT,
    AskUiState(query = "offline conflict", phase = AskPhase.DONE, askedQuery = "q", hits = hits(false).filter { it.match == AskMatch.KEYWORD }, mode = AskMode.KEYWORD_ONLY),
    AskMode.KEYWORD_ONLY,
)

private const val DOC_ZH = "# 离线同步方案\n\n状态：已采纳。地铁和电梯里经常断网，断网时发出的写操作会直接失败。\n\n## 决策\n\n写操作先进入本地离线队列，存在 pending_ops 表；联网后按入队顺序重放，失败退避重试。\n\n遇到同步冲突以服务端版本为准，本地副本另存，提示用户比对。\n\n## 后果\n\n写操作在断网时也能成功返回；队列过长时需要在设置里提示清理。"
private const val DOC_EN = "# Offline sync\n\nStatus: accepted. The network often drops in subways and elevators, and writes sent offline fail.\n\n## Decision\n\nWrites go to a local offline queue stored in pending_ops and replay in order once online, retrying with backoff.\n\nOn a sync conflict the server wins; the local copy is kept so the user can compare.\n\n## Consequences\n\nWrites succeed offline; a long queue prompts a cleanup in settings."

@Composable
private fun LocateSample(mode: InfinityThemeMode, zh: Boolean) = Themed(mode) {
    val body = if (zh) DOC_ZH else DOC_EN
    val a = body.indexOf(if (zh) "写操作先进入" else "Writes go to")
    val b = body.indexOf(if (zh) "遇到同步冲突" else "On a sync conflict")
    val located = locateBody(body, listOf(a..a + 3, b..b + 3), markdown = true)!!
    val scroll = rememberScrollState()
    Column {
        DocumentLocateBar(hitCount = located.hitCount, focus = 1, cited = false, onMove = {})
        Column(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 8.dp)) {
            LocatedBodySegments(located, markdown = true, focus = 1, scroll = scroll, viewportTop = { 0f })
        }
    }
}

@PreviewTest
@Preview(name = "文档定位 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun libraryLocateDarkPreview() = LocateSample(InfinityThemeMode.DARK, zh = true)

@PreviewTest
@Preview(name = "文档定位 浅英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun libraryLocateLightPreview() = LocateSample(InfinityThemeMode.LIGHT, zh = false)

@Composable
private fun CitedSample(mode: InfinityThemeMode, zh: Boolean) = Themed(mode) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DocumentCitationsCardContent(
            listOf(
                DocumentCitation("d1", 3, CitationSource.CHAT, "s1", if (zh) "离线同步重构" else "Offline sync rework", "m1", "K2", null, if (zh) "代码助手" else "Code helper", System.currentTimeMillis() - 3_600_000),
                DocumentCitation("d1", 4, CitationSource.ASK, null, null, null, "1", if (zh) "离线写入冲突怎么处理" else "how are offline write conflicts handled", null, System.currentTimeMillis() - 7_200_000),
            ),
            onOpenSession = {},
        )
        RecentCitedCardContent(
            listOf(
                CitedDocument("d1", if (zh) "离线同步方案.md" else "offline-sync.md", 3, System.currentTimeMillis() - 3_600_000, if (zh) "离线同步重构" else "Offline sync rework", if (zh) "代码助手" else "Code helper"),
                CitedDocument("d3", if (zh) "发布检查清单.pdf" else "release-checklist.pdf", 1, System.currentTimeMillis() - 86_400_000, null, null),
            ),
            onOpen = {},
            onViewAll = {},
        )
    }
}

@PreviewTest
@Preview(name = "引用卡 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun libraryCitedDarkPreview() = CitedSample(InfinityThemeMode.DARK, zh = true)

@PreviewTest
@Preview(name = "引用卡 浅英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun libraryCitedLightPreview() = CitedSample(InfinityThemeMode.LIGHT, zh = false)
