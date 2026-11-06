package com.promenar.infinity.ui.rag

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
import com.promenar.infinity.data.rag.KgDocumentOption
import com.promenar.infinity.data.rag.KgEntitySessionRef
import com.promenar.infinity.data.rag.KgEntitySources
import com.promenar.infinity.data.rag.KgSessionOption
import com.promenar.infinity.data.rag.KgSessionTurn
import com.promenar.infinity.ui.rag.canvas.KgEntityKind
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "图谱页 会话视角控件 深色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun knowledgeGraphSessionControlsDarkPreview() = KgPageFrame { SessionControls(selectedTurnId = "m6") }

@PreviewTest
@Preview(name = "Graph session controls English 360dp font130", widthDp = 360, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun knowledgeGraphSessionControlsEnglishPreview() = KgPageFrame(dark = false) { SessionControls(selectedTurnId = null) }

@PreviewTest
@Preview(name = "图谱页 实体详情 深色", widthDp = 411, heightDp = 460, locale = "zh-rCN")
@Composable
fun knowledgeGraphEntityPanelDarkPreview() = KgPageFrame(padded = false) { EntityPanel() }

@PreviewTest
@Preview(name = "图谱页 实体详情 浅色", widthDp = 411, heightDp = 460, locale = "zh-rCN")
@Composable
fun knowledgeGraphEntityPanelLightPreview() = KgPageFrame(dark = false, padded = false) { EntityPanel() }

@PreviewTest
@Preview(name = "图谱页 会话列表与空态 深色", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun knowledgeGraphSessionOptionsDarkPreview() = KgPageFrame {
    KnowledgeGraphSessionOptionsContent(
        options = listOf(
            KgSessionOption("s1", "Rust 异步运行时选型", turnCount = 12, entityCount = 24, updatedAt = 2L),
            KgSessionOption("s2", "", turnCount = 3, entityCount = 7, updatedAt = 1L),
        ),
        selectedSessionId = "s1",
        loadError = null,
        onRetry = {},
        onSelect = {},
    )
    KnowledgeGraphCanvasMessage(
        text = "选择会话",
        actionLabel = "选择会话",
        modifier = Modifier.padding(top = 24.dp),
    )
}

@Composable
private fun SessionControls(selectedTurnId: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KnowledgeGraphModeSelector(viewMode = KgViewMode.SESSION, onModeSelected = {})
        KnowledgeGraphScopeRow(
            viewMode = KgViewMode.SESSION,
            scopeLabel = "Rust 异步运行时选型",
            meta = listOf("12 轮 · 24 实体"),
            onPick = {},
        )
        KnowledgeGraphTypeFilterRow(
            counts = mapOf(KgEntityKind.ITEM to 8, KgEntityKind.CONCEPT to 9, KgEntityKind.PERSON to 2, KgEntityKind.ORGANIZATION to 2, KgEntityKind.DOCUMENT to 3),
            selected = setOf(KgEntityKind.CONCEPT),
            onToggle = {},
        )
        KnowledgeGraphTurnStrip(
            turns = sampleTurns,
            selectedTurnId = selectedTurnId,
            onSelectTurn = {},
        )
    }
}

@Composable
private fun EntityPanel() {
    KnowledgeGraphEntityPanel(
        name = "Tokio",
        kind = KgEntityKind.ITEM,
        detail = KgEntityDetail(
            nodeId = "tokio",
            isLoading = false,
            sources = KgEntitySources(
                documents = listOf(KgDocumentOption("d1", "架构说明.md")),
                sessionTurns = listOf(
                    KgEntitySessionRef("s1", "Rust 异步运行时选型", "m7", 7),
                    KgEntitySessionRef("s1", "Rust 异步运行时选型", "m1", 1),
                    KgEntitySessionRef("s1", "Rust 异步运行时选型", "m3", 3),
                ),
            ),
        ),
        relationCount = 9,
        relations = listOf(
            KgRelationItem("rust", "Rust", KgEntityKind.ITEM, "用…实现", outgoing = true),
            KgRelationItem("exec", "执行器", KgEntityKind.CONCEPT, "提供", outgoing = true),
            KgRelationItem("team", "Tokio 团队", KgEntityKind.ORGANIZATION, "维护", outgoing = false),
            KgRelationItem("cargo", "Cargo.toml", KgEntityKind.DOCUMENT, "依赖", outgoing = false),
        ),
        onClose = {},
        onOpenSession = {},
        onPickEntity = {},
    )
}

private val sampleTurns = listOf(3, 2, 4, 2, 2, 4, 3, 2, 3, 2, 2, 2).mapIndexed { index, count ->
    val ids = (0 until count).map { "n$index-$it" }
    KgSessionTurn("m${index + 1}", index + 1, index * 10L, ids, ids.take(1))
}

@Composable
private fun KgPageFrame(dark: Boolean = true, padded: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = if (padded) 16.dp else 0.dp)) {
                content()
            }
        }
    }
}
