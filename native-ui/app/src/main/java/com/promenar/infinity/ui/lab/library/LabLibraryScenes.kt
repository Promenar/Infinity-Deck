package com.promenar.infinity.ui.lab.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.ui.lab.chat.LAB_MODEL
import com.promenar.infinity.ui.lab.chat.isChinese
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.library.AskPhase
import com.promenar.infinity.ui.library.AskUiState
import com.promenar.infinity.ui.library.DocumentLocateBar
import com.promenar.infinity.ui.library.LibraryAskActions
import com.promenar.infinity.ui.library.LibraryAskField
import com.promenar.infinity.ui.library.LibraryAskResults
import com.promenar.infinity.ui.library.LibraryLeading
import com.promenar.infinity.ui.library.LibraryPageFrame
import com.promenar.infinity.ui.library.LocatedBodySegments
import com.promenar.infinity.ui.library.locateBody
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 存储空间目录下的版本：问资料的答案与命中列表，以及从答案点进文档后的命中段定位。
 * 资料与记忆都围绕 starlog 的 JSON 输出设计，全部虚构。两页按外屏的整页布局拼装（页头带返回），
 * 内屏打开时居中成最宽 640dp 的阅读列；内屏两列的资料页要等资料页可注入假数据后再接入。
 */
internal val labLibraryScenes: Map<String, () -> LabScene> = mapOf(
    "library.state.ask" to { LabStateScene { AskPage() } },
    "library.state.locate" to { LabStateScene { LocatePage() } },
)

private const val DOC_ID = "lab-doc-json"

@Composable
private fun rememberLibraryText(): LabLibraryText {
    val zh = isChinese(LocalContext.current.resources)
    return remember(zh) { LabLibraryText(zh) }
}

/** 外屏资料子页的外壳：页头避开状态栏，底部留白由页面内边距按导航栏计算。 */
private val PageInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** 外屏整页布局在内屏打开时最宽的阅读列；整页底色铺满，列居中。 */
private val PageMaxWidth = 640.dp

@Composable
private fun pageModifier(): Modifier = Modifier
    .fillMaxSize()
    .background(MaterialTheme.colorScheme.background)
    .windowInsetsPadding(PageInsets)
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = PageMaxWidth)

@Composable
private fun LabSceneScope.AskPage() {
    val text = rememberLibraryText()
    var state by remember(text) { mutableStateOf(text.answered()) }
    val actions = remember(text) {
        LibraryAskActions(
            onSubmit = {},
            onRegenerate = {},
            onFilter = { kind -> state = state.copy(filter = kind) },
            onScope = {},
            onSelect = { index -> state = state.copy(selectedHit = index) },
            onOpenHit = {},
            onOpenSettings = {},
            onCopy = {},
        )
    }
    LibraryPageFrame(
        title = stringResource(R.string.library_page_ask),
        leading = LibraryLeading.Back(onExit),
        crumbs = null,
        modifier = pageModifier(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LibraryAskField(
                query = state.query,
                onQueryChange = {},
                onSearch = {},
                modifier = Modifier.padding(
                    horizontal = InfinitySpacing.ScreenHorizontal,
                    vertical = InfinitySpacing.Small,
                ),
            )
            LibraryAskResults(state, AskMode.FULL, actions, bottomPadding = padding.calculateBottomPadding(), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabSceneScope.LocatePage() {
    val text = rememberLibraryText()
    val located = remember(text) {
        val body = text.docBody
        val ranges = text.locateAnchors.map { anchor ->
            val start = body.indexOf(anchor)
            start until start + anchor.length
        }
        requireNotNull(locateBody(body, ranges, markdown = true))
    }
    var focus by remember(located) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    LibraryPageFrame(
        title = text.docTitle,
        leading = LibraryLeading.Back(onExit),
        crumbs = null,
        modifier = pageModifier(),
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            DocumentLocateBar(
                hitCount = located.hitCount,
                focus = focus,
                cited = false,
                onMove = { delta -> focus = (focus + delta).mod(located.hitCount) },
            )
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(padding)) {
                SelectionContainer {
                    LocatedBodySegments(located, markdown = true, focus = focus, scroll = scroll, viewportTop = { 0f })
                }
            }
        }
    }
}

/** 问资料与文档页的虚构数据；文件名、命令与字段名不随语言变化。 */
private class LabLibraryText(private val zh: Boolean) {
    val docTitle = if (zh) "starlog JSON 输出设计.md" else "starlog-json-output.md"

    private fun hits() = listOf(
        AskHit(
            key = "file:$DOC_ID", kind = AskHitKind.DOCUMENT,
            title = docTitle,
            location = if (zh) "存储空间 · starlog/docs" else "Storage · starlog/docs",
            snippet = if (zh) {
                "--json 输出 JSON Lines：每行一个对象，便于 jq 和流式处理；时间戳统一为 ISO 8601。"
            } else {
                "--json prints JSON Lines: one object per line, easy to pipe into jq and stream; timestamps are ISO 8601."
            },
            highlightTerms = listOf("JSON Lines", "ISO 8601"),
            match = AskMatch.BOTH, similarity = 0.88f, documentId = DOC_ID,
        ),
        AskHit(
            key = "memory:lab-m1", kind = AskHitKind.MEMORY,
            title = if (zh) "主动记忆 · starlog" else "Active memory · starlog",
            location = if (zh) "Infinity · 2 天前" else "Infinity · 2 days ago",
            snippet = if (zh) {
                "用户希望 starlog 的输出能直接交给 jq，不要整份读进内存再解析。"
            } else {
                "The user wants starlog's output to go straight into jq without loading the whole file first."
            },
            highlightTerms = emptyList(), match = AskMatch.SEMANTIC, similarity = 0.77f, memoryId = "lab-m1",
        ),
        AskHit(
            key = "file:lab-doc-notes", kind = AskHitKind.DOCUMENT,
            title = if (zh) "周会纪要 11-04.md" else "weekly-notes-11-04.md",
            location = if (zh) "工作区 starlog · notes" else "Workspace starlog · notes",
            snippet = if (zh) "JSON 输出排进 v0.4.0；字段名用小写，保留原始行便于排查。" else "JSON output goes into v0.4.0; lowercase field names, keep the raw line for debugging.",
            highlightTerms = if (zh) listOf("JSON 输出") else listOf("JSON output"),
            match = AskMatch.KEYWORD, similarity = null, documentId = "lab-doc-notes",
        ),
        AskHit(
            key = "entity:lab-e1", kind = AskHitKind.ENTITY,
            title = if (zh) "图谱 · JSON Lines" else "Graph · JSON Lines",
            location = if (zh) "2 篇文档 · 4 条关系" else "2 documents · 4 relations",
            snippet = if (zh) "starlog —输出格式→ JSON Lines；—时间格式→ ISO 8601。" else "starlog —output format→ JSON Lines; —time format→ ISO 8601.",
            highlightTerms = emptyList(), match = AskMatch.SEMANTIC, similarity = 0.71f, entityId = "lab-e1",
        ),
    )

    fun answered() = AskUiState(
        query = if (zh) "starlog 的 JSON 输出格式定了吗" else "What did we decide for starlog's JSON output",
        phase = AskPhase.DONE,
        askedQuery = if (zh) "starlog 的 JSON 输出格式定了吗" else "What did we decide for starlog's JSON output",
        hits = hits(),
        mode = AskMode.FULL,
        answer = if (zh) {
            "定了：--json 输出 JSON Lines，每行一个对象，时间戳用 ISO 8601。[1][4]字段为 timestamp、level、target 与原始行 raw，随 v0.4.0 发布。[1][3]你之前提过希望输出能直接交给 jq。[2]"
        } else {
            "Yes: --json prints JSON Lines, one object per line, with ISO 8601 timestamps.[1][4] Each record has timestamp, level, target and the raw line, shipping in v0.4.0.[1][3] You mentioned wanting the output to pipe straight into jq.[2]"
        },
        citedHitIndexes = listOf(0, 3, 2, 1),
        modelLabel = LAB_MODEL,
    )

    val docBody = if (zh) {
        "# starlog JSON 输出设计\n\n状态：已采纳，随 v0.4.0 发布。\n\n## 背景\n\n日志文件动辄几百万行，文本报表不便于再加工，用户希望直接交给 jq。\n\n## 决策\n\n--json 输出 JSON Lines：每行一个对象，便于 jq 和流式处理，不必整份读进内存。\n\n每条记录包含 timestamp、level、target 与 raw 四个字段，时间戳统一为 ISO 8601。\n\n## 后果\n\n文本报表保持默认；--json 与 --summary 同时使用时，汇总行作为最后一个对象输出。"
    } else {
        "# starlog JSON output\n\nStatus: accepted, shipping in v0.4.0.\n\n## Background\n\nLog files often run to millions of lines, and the text report is hard to post-process; users want to pipe it into jq.\n\n## Decision\n\n--json prints JSON Lines: one object per line, easy to pipe into jq and stream without loading the whole file.\n\nEach record has four fields, timestamp, level, target and raw, and timestamps are ISO 8601.\n\n## Consequences\n\nThe text report stays the default; with --json and --summary together, the summary is the last object."
    }

    /** 两处命中：决策段的两句。 */
    val locateAnchors = if (zh) {
        listOf("--json 输出 JSON Lines", "每条记录包含")
    } else {
        listOf("--json prints JSON Lines", "Each record has four fields")
    }
}
