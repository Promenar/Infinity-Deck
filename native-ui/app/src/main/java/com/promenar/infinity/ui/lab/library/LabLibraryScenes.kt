package com.promenar.infinity.ui.lab.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentCollectionInfo
import com.promenar.infinity.data.rag.KgDocumentSetting
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.CitationSource
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.DocumentInsight
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.AdaptiveNavigationFrame
import com.promenar.infinity.ui.AppTab
import com.promenar.infinity.ui.MainTabsState
import com.promenar.infinity.ui.NavigationBody
import com.promenar.infinity.ui.bottomDockVisible
import com.promenar.infinity.ui.hub.HubNavigationRailWidth
import com.promenar.infinity.ui.hub.hubPaneGeometry
import com.promenar.infinity.ui.lab.chat.LAB_MODEL
import com.promenar.infinity.ui.lab.chat.isChinese
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.library.LibraryAskViewModel
import com.promenar.infinity.ui.library.LibraryLayout
import com.promenar.infinity.ui.library.LibraryPageKeys
import com.promenar.infinity.ui.library.LibraryScreenContent
import com.promenar.infinity.ui.library.LibraryViewModel
import com.promenar.infinity.ui.settings.settingsTwoPaneAvailable
import com.promenar.infinity.ui.shouldUseNavigationRail
import com.promenar.infinity.ui.theme.InfinityMotion
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 存储空间目录下的版本：问资料的答案与命中列表，以及从答案点进文档后的命中段定位。
 * 两个版本都是正式资料页 [LibraryScreenContent]：内屏为导航轨加两列，外屏为单页栈加底栏，
 * 数据来自 LabLibraryFakes.kt 里的假数据源，资料与记忆都围绕 starlog 的 JSON 输出设计，全部虚构。
 * 左列的长期记忆、知识图谱与各位置的文件浏览仍读取本机数据，录制时只点问资料、概览与文档。
 */
internal val labLibraryScenes: Map<String, () -> LabScene> = mapOf(
    "library.state.ask" to { LabStateScene { LabLibraryPage(openDocument = false) } },
    "library.state.locate" to { LabStateScene { LabLibraryPage(openDocument = true) } },
)

private const val DOC_ID = "lab-doc-json"
private const val NOTES_ID = "lab-doc-notes"
private const val BENCH_ID = "lab-doc-bench"
private const val SESSION_ID = "lab-session-starlog"

/**
 * 正式资料页加上与主标签页相同的导航外壳。问资料已按搜索键作答；[openDocument] 为真时再从答案打开文档，
 * 文档页按两处命中定位。开合屏时按新宽度重建层级，与正式页面从外屏首页或内屏概览进入的层级一致。
 */
@Composable
private fun LabLibraryPage(openDocument: Boolean) {
    val context = LocalContext.current
    val zh = isChinese(context.resources)
    val titles = LocationTitles(
        library = stringResource(R.string.file_manager_location_library),
        sessions = stringResource(R.string.file_manager_location_sessions),
        linux = stringResource(R.string.file_manager_location_linux),
    )
    val text = remember(zh, titles) { LabLibraryText(zh, titles) }
    val viewModel = remember(text) {
        LibraryViewModel(LabLibraryKnowledge(text), LabLibraryFiles(text.locations, text.pins), LabLibraryCitations(text))
    }
    val askViewModel = remember(text) {
        LibraryAskViewModel(LabLibraryAsk(text)).apply {
            onQueryChange(text.query)
            submit()
        }
    }
    DisposableEffect(viewModel, askViewModel) {
        onDispose {
            viewModel.viewModelScope.cancel()
            askViewModel.viewModelScope.cancel()
        }
    }
    val rootUuid = remember { MutableStateFlow<String?>(LAB_ROOT_UUID) }
    val collections = remember(text) { LabLibraryCollections(text) }

    val configuration = LocalConfiguration.current
    val twoPane = settingsTwoPaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    val state = remember(twoPane, openDocument) {
        MainTabsState(AppTab.LIBRARY, emptyList(), "").apply {
            if (twoPane) openLibraryRoot(LibraryPageKeys.OVERVIEW)
            pushLibrary(LibraryPageKeys.ASK)
            if (openDocument) pushLibrary(LibraryPageKeys.document(DOC_ID))
        }
    }
    // 展开与还原：与主标签页相同的进度动画，导航轨随之左移推出。
    val expansion = remember(state) { Animatable(0f) }
    LaunchedEffect(state.libraryExpanded, twoPane) {
        val target = if (state.libraryExpanded && twoPane) 1f else 0f
        expansion.animateTo(target, tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
    }
    val railWidth = HubNavigationRailWidth + WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
        .asPaddingValues()
        .calculateStartPadding(LayoutDirection.Ltr)
    val shiftPx = with(LocalDensity.current) { hubPaneGeometry(railWidth, railWidth, expanded = false).shift.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AdaptiveNavigationFrame(
            expanded = shouldUseNavigationRail(maxWidth),
            selectedTab = AppTab.LIBRARY,
            onTabSelected = {},
            railShift = if (twoPane) ({ -shiftPx * expansion.value }) else null,
            dockVisible = bottomDockVisible(AppTab.LIBRARY, state.libraryStack, twoPane),
        ) {
            NavigationBody(fullBleed = twoPane) {
                LibraryScreenContent(
                    state = state,
                    layout = if (twoPane) LibraryLayout.TwoPane(railWidth, expansion) else LibraryLayout.Single,
                    viewModel = viewModel,
                    askViewModel = askViewModel,
                    workspaceRootUuid = rootUuid,
                    collections = collections,
                    onNavigateToSecondary = {},
                    onOpenSession = {},
                    onManageMounts = {},
                )
            }
        }
    }
}

private const val LAB_ROOT_UUID = "lab-storage-root"

internal data class LocationTitles(val library: String, val sessions: String, val linux: String)

/** 资料页的虚构数据；文件名、命令与字段名不随语言变化。时间相对版本打开的时刻计算。 */
internal class LabLibraryText(private val zh: Boolean, private val titles: LocationTitles) {
    private val now = System.currentTimeMillis()
    private fun hoursAgo(hours: Int) = now - hours * HOUR_MS

    val modelLabel = LAB_MODEL
    val docTitle = if (zh) "starlog JSON 输出设计.md" else "starlog-json-output.md"
    private val notesTitle = if (zh) "周会纪要 11-04.md" else "weekly-notes-11-04.md"
    private val benchTitle = if (zh) "大文件基准测试.md" else "large-file-benchmark.md"
    private val docsPath = "/starlog/docs"
    private val docsFolder = "${titles.library} › starlog › docs"
    private val notesFolder = "${titles.library} › starlog › notes"
    private val docRef = FileRef(KB, "$docsPath/$docTitle")
    private val notesRef = FileRef(KB, "/starlog/notes/$notesTitle")
    private val benchRef = FileRef(KB, "$docsPath/$benchTitle")

    // —— 位置与固定的文件夹 ——

    val locations = listOf(
        FileLocation(KB, FileLocationKind.KNOWLEDGE_BASE, titles.library, WRITABLE),
        FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, titles.sessions, READ_ONLY),
        FileLocation(
            "workspace:lab-starlog", FileLocationKind.PROJECT, "starlog",
            WRITABLE.copy(git = true, terminal = true), gitBranch = "main", gitChangeCount = 3,
        ),
        FileLocation("external:lab-papers", FileLocationKind.EXTERNAL_FOLDER, if (zh) "论文" else "Papers", WRITABLE),
        FileLocation("linux:home", FileLocationKind.LINUX_HOME, titles.linux, WRITABLE.copy(terminal = true)),
    )
    val pins = listOf(FileRef(KB, docsPath))

    // —— 概览、待处理、最近、片段与引用 ——

    val overview = LibraryOverview(
        indexedDocuments = 128, longTermMemories = 46, newLongTermMemories = 3,
        graphEntities = 812, graphRelations = 1_904, fragments = 57,
    )

    val pending = listOf(
        PendingDocument(
            documentId = BENCH_ID, ref = benchRef, name = benchTitle, folderPath = docsFolder,
            kind = PendingKind.INDEXING, stage = PendingStage.VECTORIZING, processed = 18, total = 42,
        ),
    )

    val recent = listOf(
        LibraryDocumentSummary(DOC_ID, docRef, docTitle, docsFolder, MARKDOWN, 3_412, hoursAgo(3)),
        LibraryDocumentSummary(BENCH_ID, benchRef, benchTitle, docsFolder, MARKDOWN, 18_960, hoursAgo(5)),
        LibraryDocumentSummary(NOTES_ID, notesRef, notesTitle, notesFolder, MARKDOWN, 2_180, hoursAgo(26)),
    )

    private val sessionTitle = if (zh) "给 starlog 加 --json 输出" else "Add --json output to starlog"

    val fragmentGroups = listOf(FragmentGroup(SESSION_ID, sessionTitle, "Infinity", 12, hoursAgo(2)))

    val citedDocuments = listOf(
        CitedDocument(DOC_ID, docTitle, 6, hoursAgo(2), sessionTitle, "Infinity"),
        CitedDocument(NOTES_ID, notesTitle, 3, hoursAgo(20), sessionTitle, "Infinity"),
    )

    fun citationsFor(documentId: String): List<DocumentCitation> = if (documentId != DOC_ID) emptyList() else listOf(
        DocumentCitation(
            documentId = DOC_ID, chunkIndex = 2, source = CitationSource.ASK, sessionId = null, sessionTitle = null,
            messageId = null, label = "1", askQuery = query, agentName = null, citedAt = now - 60_000L,
        ),
        DocumentCitation(
            documentId = DOC_ID, chunkIndex = 3, source = CitationSource.CHAT, sessionId = SESSION_ID, sessionTitle = sessionTitle,
            messageId = "lab-message-json", label = "K2", askQuery = null, agentName = "Infinity", citedAt = hoursAgo(2),
        ),
    )

    // —— 文档 ——

    fun documentIdFor(ref: FileRef): String? = when (ref) {
        docRef -> DOC_ID
        notesRef -> NOTES_ID
        benchRef -> BENCH_ID
        else -> null
    }

    fun insight(documentId: String): DocumentInsight? = when (documentId) {
        DOC_ID -> DocumentInsight(
            documentId = DOC_ID, ref = docRef, name = docTitle, folderPath = docsFolder, mimeType = MARKDOWN,
            sizeBytes = 3_412, lastModified = hoursAgo(3), pending = null, failureReason = null, chunkCount = 6,
            entities = listOf("starlog", "JSON Lines", "ISO 8601", "jq", "v0.4.0", "--summary"),
            source = if (zh) "手动上传" else "Uploaded", versionCount = 3,
            graph = DocumentGraphState.Complete(entities = 14, relations = 22),
        )
        NOTES_ID -> DocumentInsight(
            documentId = NOTES_ID, ref = notesRef, name = notesTitle, folderPath = notesFolder, mimeType = MARKDOWN,
            sizeBytes = 2_180, lastModified = hoursAgo(26), pending = null, failureReason = null, chunkCount = 3,
            entities = listOf("starlog", "v0.4.0"), source = if (zh) "从对话保存" else "Saved from chat", versionCount = 1,
            graph = DocumentGraphState.Complete(entities = 6, relations = 7),
        )
        BENCH_ID -> DocumentInsight(
            documentId = BENCH_ID, ref = benchRef, name = benchTitle, folderPath = docsFolder, mimeType = MARKDOWN,
            sizeBytes = 18_960, lastModified = hoursAgo(5), pending = PendingKind.INDEXING, failureReason = null, chunkCount = 0,
            entities = emptyList(), source = if (zh) "手动上传" else "Uploaded", versionCount = 1,
        )
        else -> null
    }

    fun body(documentId: String): DocumentBody? = when (documentId) {
        DOC_ID -> DocumentBody(docBody, markdown = true, extracted = false, truncated = false)
        NOTES_ID -> DocumentBody(notesBody, markdown = true, extracted = false, truncated = false)
        else -> null
    }

    private val decision = if (zh) {
        "--json 输出 JSON Lines：每行一个对象，便于 jq 和流式处理，不必整份读进内存。"
    } else {
        "--json prints JSON Lines: one object per line, easy to pipe into jq and stream without loading the whole file."
    }
    private val fields = if (zh) {
        "每条记录包含 timestamp、level、target 与 raw 四个字段，时间戳统一为 ISO 8601。"
    } else {
        "Each record has four fields, timestamp, level, target and raw, and timestamps are ISO 8601."
    }
    private val notesLine = if (zh) {
        "JSON 输出排进 v0.4.0；字段名用小写，保留原始行便于排查。"
    } else {
        "JSON output goes into v0.4.0; lowercase field names, keep the raw line for debugging."
    }

    private val docBody = if (zh) {
        "# starlog JSON 输出设计\n\n状态：已采纳，随 v0.4.0 发布。\n\n## 背景\n\n日志文件动辄几百万行，文本报表不便于再加工，用户希望直接交给 jq。\n\n## 决策\n\n$decision\n\n$fields\n\n## 后果\n\n文本报表保持默认；--json 与 --summary 同时使用时，汇总行作为最后一个对象输出。"
    } else {
        "# starlog JSON output\n\nStatus: accepted, shipping in v0.4.0.\n\n## Background\n\nLog files often run to millions of lines, and the text report is hard to post-process; users want to pipe it into jq.\n\n## Decision\n\n$decision\n\n$fields\n\n## Consequences\n\nThe text report stays the default; with --json and --summary together, the summary is the last object."
    }

    private val notesBody = if (zh) {
        "# 周会纪要 11-04\n\n## starlog\n\n$notesLine\n\n大文件基准测试下周补齐，目标是 400 万行在 30 秒内跑完。"
    } else {
        "# Weekly notes 11-04\n\n## starlog\n\n$notesLine\n\nThe large-file benchmark lands next week; the target is 4 million lines in under 30 seconds."
    }

    // —— 图谱合集：顶层文件夹 starlog 是一部作品，文档按篇序排列 ——

    private val collectionOrder = listOf(NOTES_ID, DOC_ID, BENCH_ID)

    fun collectionInfo(documentId: String) = KgDocumentCollectionInfo(
        collectionId = COLLECTION_ID, title = COLLECTION_TITLE,
        ordinal = collectionOrder.indexOf(documentId).takeIf { it >= 0 }?.plus(1),
        documentCount = collectionOrder.size, orderSource = null,
    )

    val collectionAssignment = KgDocumentAssignment(
        mode = KgDocumentSetting.FOLLOW_FOLDER, targetCollectionId = null,
        folderCollectionId = COLLECTION_ID, folderCollectionTitle = COLLECTION_TITLE,
    )

    // —— 问资料 ——

    val query = if (zh) "starlog 的 JSON 输出格式定了吗" else "What did we decide for starlog's JSON output"

    /** 命中按相关度排序，编号即下标加一；同一篇文档的两个分块各占一条。 */
    fun hits(): List<AskHit> = listOf(
        AskHit(
            key = "vector:$DOC_ID:2", kind = AskHitKind.DOCUMENT, title = docTitle,
            location = "${titles.library} · starlog/docs", // wrap-check: allow 实验室示例数据
            snippet = decision, highlightTerms = listOf("JSON Lines", "jq"),
            match = AskMatch.BOTH, similarity = 0.88f, documentId = DOC_ID, chunkIndex = 2, chunkContent = decision,
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
            key = "vector:$NOTES_ID:0", kind = AskHitKind.DOCUMENT, title = notesTitle,
            location = "${titles.library} · starlog/notes", // wrap-check: allow 实验室示例数据
            snippet = notesLine, highlightTerms = if (zh) listOf("JSON 输出") else listOf("JSON output"),
            match = AskMatch.KEYWORD, similarity = null, documentId = NOTES_ID, chunkIndex = 0, chunkContent = notesLine,
        ),
        AskHit(
            key = "entity:lab-e1", kind = AskHitKind.ENTITY,
            title = if (zh) "图谱 · JSON Lines" else "Graph · JSON Lines",
            location = if (zh) "2 篇文档 · 4 条关系" else "2 documents · 4 relations",
            snippet = if (zh) "starlog —输出格式→ JSON Lines；—时间格式→ ISO 8601。" else "starlog —output format→ JSON Lines; —time format→ ISO 8601.",
            highlightTerms = emptyList(), match = AskMatch.SEMANTIC, similarity = 0.71f, entityId = "lab-e1",
        ),
        AskHit(
            key = "vector:$DOC_ID:3", kind = AskHitKind.DOCUMENT, title = docTitle,
            location = "${titles.library} · starlog/docs", // wrap-check: allow 实验室示例数据
            snippet = fields, highlightTerms = listOf("ISO 8601"),
            match = AskMatch.SEMANTIC, similarity = 0.69f, documentId = DOC_ID, chunkIndex = 3, chunkContent = fields,
        ),
    )

    val answer = if (zh) {
        "定了：--json 输出 JSON Lines，每行一个对象，时间戳用 ISO 8601。[1][4]每条记录包含 timestamp、level、target 与原始行 raw，随 v0.4.0 发布。[5][3]你之前提过希望输出能直接交给 jq。[2]"
    } else {
        "Yes: --json prints JSON Lines, one object per line, with ISO 8601 timestamps.[1][4] Each record has timestamp, level, target and the raw line, shipping in v0.4.0.[5][3] You mentioned wanting the output to pipe straight into jq.[2]"
    }

    /** 答案里编号的首次出现顺序（下标从 0 起）。 */
    val citedHitIndexes = listOf(0, 3, 4, 2, 1)

    private companion object {
        const val KB = "kb"
        const val COLLECTION_ID = "lab-collection-starlog"
        const val COLLECTION_TITLE = "starlog"
        const val MARKDOWN = "text/markdown"
        const val HOUR_MS = 3_600_000L
        val WRITABLE = FileLocationCapabilities(writable = true, deletable = true, archive = true)
        val READ_ONLY = FileLocationCapabilities(writable = false, deletable = false, archive = false)
    }
}
