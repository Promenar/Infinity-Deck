package com.promenar.infinity.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.artifact.ArtifactCandidate
import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.artifact.ArtifactOrigin
import com.promenar.infinity.data.artifact.ArtifactSummary
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasScreenSource
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography

private fun previewMetadata(title: String, id: String = "canvas-preview-dashboard") = CanvasMetadata(
    id = id,
    title = title,
    createdAt = 1_792_000_000_000L,
    updatedAt = 1_792_000_060_000L,
    currentVersion = 3,
    createdByMessageId = "message-preview",
    versions = listOf(1, 2, 3),
)

@Composable
private fun CanvasPreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(InfinitySpacing.Large)) { content() }
        }
    }
}

/** 固定 Compose 图形代替网页，避免截图受到 WebView 初始化与脚本时序影响。 */
@Composable
private fun StaticChartPreview(english: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Text(
            text = if (english) "Quarterly revenue" else "季度营收",
            style = InfinityTypography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        listOf(0.52f, 0.73f, 0.92f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@PreviewTest
@Preview(name = "画布卡片 深中360", widthDp = 360, heightDp = 500, locale = "zh-rCN")
@Composable
fun canvasCardDarkChinesePreview() = CanvasPreviewFrame(dark = true) {
    CanvasCard(
        metadata = previewMetadata("季度经营看板"),
        version = 1,
        html = null,
        onOpen = {},
        previewContent = { StaticChartPreview(english = false) },
        workspacePath = "canvas/季度经营看板.html",
    )
}

@PreviewTest
@Preview(name = "画布卡片 浅英360 大字体", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun canvasCardLightEnglishLargeFontPreview() = CanvasPreviewFrame(dark = false) {
    CanvasCard(
        metadata = previewMetadata("Quarterly business dashboard"),
        version = 1,
        html = null,
        onOpen = {},
        previewContent = { StaticChartPreview(english = true) },
        workspacePath = "canvas/quarterly-business-dashboard.html",
        workspaceWarning = true,
    )
}

@PreviewTest
@Preview(name = "画布紧凑卡 浅中390", widthDp = 390, heightDp = 260, locale = "zh-rCN")
@Composable
fun canvasInlineCardLightChinesePreview() = CanvasPreviewFrame(dark = false) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        CanvasInlineCard(
            metadata = previewMetadata("Infinity Deck 能力探测矩阵").copy(currentVersion = 1, versions = listOf(1)),
            version = 1,
            html = null,
            onOpen = {},
            previewContent = { StaticChartPreview(english = false) },
        )
        CanvasInlineCard(
            metadata = previewMetadata("季度经营看板"),
            version = 1,
            html = null,
            onOpen = {},
            previewFailed = true,
        )
    }
}

@PreviewTest
@Preview(name = "画布紧凑卡 深英360 大字体", widthDp = 360, heightDp = 200, locale = "en", fontScale = 1.3f)
@Composable
fun canvasInlineCardDarkEnglishLargeFontPreview() = CanvasPreviewFrame(dark = true) {
    CanvasInlineCard(
        metadata = previewMetadata("Quarterly business dashboard with a long title"),
        version = 2,
        html = null,
        onOpen = {},
        previewContent = { StaticChartPreview(english = true) },
    )
}

private fun previewArtifact(id: String, kind: ArtifactKind, title: String, version: Int = 1, screens: Int = 0, fileName: String? = null, size: Long? = null) =
    ArtifactSummary(
        id = id, kind = kind, origin = if (fileName == null) ArtifactOrigin.CANVAS else ArtifactOrigin.DECLARED, title = title,
        createdAt = 1_792_000_000_000L, updatedAt = 1_792_000_060_000L, currentVersion = version, createdByMessageId = "message-preview",
        screenCount = screens, fileName = fileName, sizeBytes = size,
    )

private fun previewArtifacts(english: Boolean) = listOf(
    previewArtifact("p1", ArtifactKind.PROTOTYPE, if (english) "Budget app prototype" else "记账 App 原型", version = 2, screens = 5),
    previewArtifact("p2", ArtifactKind.PROTOTYPE, if (english) "Onboarding flow" else "首次引导流程", screens = 3),
    previewArtifact("g1", ArtifactKind.PAGE, if (english) "Quarterly business dashboard" else "季度经营看板", version = 3),
    previewArtifact("g2", ArtifactKind.PAGE, if (english) "Travel expense calculator" else "差旅费用计算器"),
    previewArtifact("d1", ArtifactKind.DOCUMENT, if (english) "Requirements.md" else "需求说明.md", fileName = "需求说明.md", size = 6_300),
    previewArtifact("i1", ArtifactKind.IMAGE, if (english) "Cover illustration.png" else "封面插画.png", fileName = "cover.png", size = 845_000),
    previewArtifact("o1", ArtifactKind.OTHER, if (english) "Sales detail.csv" else "销售明细.csv", version = 2, fileName = "sales.csv", size = 52_000),
)

private val previewCandidates = listOf(
    ArtifactCandidate("/home/session/out/chart_q3.png", "chart_q3.png", ArtifactKind.IMAGE, 120_000, "message-preview", 0),
    ArtifactCandidate("/home/session/draft_notes.md", "draft_notes.md", ArtifactKind.DOCUMENT, 2_000, "message-preview", 0),
)

@PreviewTest
@Preview(name = "产物分类 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun artifactsPageLightChinesePreview() = CanvasPreviewFrame(dark = false) {
    ArtifactsContent(
        artifacts = previewArtifacts(english = false),
        candidates = previewCandidates,
        onOpen = {}, onClaim = {}, onIgnore = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@PreviewTest
@Preview(name = "产物分类 深英360 大字体", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun artifactsPageDarkEnglishLargeFontPreview() = CanvasPreviewFrame(dark = true) {
    ArtifactsContent(
        artifacts = previewArtifacts(english = true),
        candidates = previewCandidates,
        onOpen = {}, onClaim = {}, onIgnore = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@PreviewTest
@Preview(name = "原型画板 浅中390", widthDp = 390, heightDp = 760, locale = "zh-rCN")
@Composable
fun prototypeBoardLightChinesePreview() = CanvasPreviewFrame(dark = false) {
    val metadata = previewMetadata("记账 App 原型").copy(kind = CanvasKind.PROTOTYPE)
    val screens = listOf("首页", "记一笔", "统计", "账单详情", "设置").mapIndexed { index, title ->
        CanvasScreenSource("s${index + 1}", title, "<p>$title</p>")
    }
    PrototypeBoardContent(
        document = CanvasDocument(metadata, screens.first().html, 3, screens),
        metadata = metadata,
        selectedVersion = null,
        onSelectVersion = {}, onCopy = {}, onClose = {}, onInsertText = {},
    )
}
