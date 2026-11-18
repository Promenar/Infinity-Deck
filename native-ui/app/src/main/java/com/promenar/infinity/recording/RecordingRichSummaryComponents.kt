package com.promenar.infinity.recording

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schema
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.ui.common.ContentSegment
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.normalizeLatexDelimiters
import com.promenar.infinity.ui.common.splitRichSegments
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlin.math.max

private val StandaloneSeekLineRegex = Regex("""^\s*(?:\[([^\]\n]+)\]\(recording://seek/(\d+)\)\s*)+$""")
private val SingleSeekLinkRegex = Regex("""\[([^\]\n]+)\]\(recording://seek/(\d+)\)""")
private val FenceStartRegex = Regex("""^\s*(```|~~~).*""")

internal sealed interface RecordingBodyBlock {
    data class MarkdownChunk(val markdown: String) : RecordingBodyBlock
    data class SeekRow(val links: List<Pair<String, Long>>) : RecordingBodyBlock
}

/**
 * 将整理稿拆分为普通富格式 Markdown 段与纯时间戳胶囊行，保证代码围栏内部不会被拆断。
 */
internal fun splitRecordingBodyBlocks(markdown: String): List<RecordingBodyBlock> {
    if (markdown.isBlank()) return emptyList()
    val result = mutableListOf<RecordingBodyBlock>()
    val mdBuffer = StringBuilder()
    var fenceMarker: String? = null

    fun flushMarkdown() {
        val text = mdBuffer.toString().trim('\n', '\r')
        if (text.isNotBlank()) {
            result += RecordingBodyBlock.MarkdownChunk(text)
        }
        mdBuffer.clear()
    }

    markdown.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        if (fenceMarker == null) {
            val fence = FenceStartRegex.matchEntire(trimmed)?.groupValues?.get(1)
            if (fence != null) {
                fenceMarker = fence
                mdBuffer.appendLine(line)
                return@forEach
            }
            if (StandaloneSeekLineRegex.matches(line)) {
                val links = SingleSeekLinkRegex.findAll(line).mapNotNull { m ->
                    val ms = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    m.groupValues[1] to ms
                }.toList()
                if (links.isNotEmpty()) {
                    flushMarkdown()
                    result += RecordingBodyBlock.SeekRow(links)
                    return@forEach
                }
            }
        } else if (trimmed.startsWith(fenceMarker!!)) {
            fenceMarker = null
        }
        mdBuffer.appendLine(line)
    }
    flushMarkdown()
    return result
}

private fun parseRecordingSeekMs(uri: String): Long? {
    if (!uri.startsWith("recording://seek/", ignoreCase = true)) return null
    return uri.substringAfter("recording://seek/").substringBefore('?').toLongOrNull()
}

/**
 * 录音整理稿富格式渲染正文：
 * - 真机/运行态直接使用 [MarkdownText] 承载 GFM 表格、ECharts、Mermaid、KaTeX 与时间戳链接；
 * - Android Studio 预览及 `@PreviewTest`（JVM LayoutLib）环境因不可实例化 `WebView`，自动将图表与公式降级为纯 Compose 矢量卡片。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingRichSummaryBody(
    markdown: String,
    validSegmentStarts: Set<Long>,
    onPlayAtMs: (Long) -> Unit,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val inspecting = LocalInspectionMode.current
    val baseUriHandler = LocalUriHandler.current
    val seekUriHandler = remember(baseUriHandler, validSegmentStarts, onPlayAtMs) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val seekMs = parseRecordingSeekMs(uri)
                if (seekMs != null) {
                    if (validSegmentStarts.isEmpty() || seekMs in validSegmentStarts) {
                        onPlayAtMs(seekMs)
                    }
                    return
                }
                val scheme = runCatching { Uri.parse(uri).scheme?.lowercase() }.getOrNull()
                if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                    runCatching { baseUriHandler.openUri(uri) }
                }
            }
        }
    }
    val blocks = remember(markdown) { splitRecordingBodyBlocks(markdown) }
    val alphaModifier = if (alpha < 0.999f) Modifier.graphicsLayer { this.alpha = alpha } else Modifier

    CompositionLocalProvider(LocalUriHandler provides seekUriHandler) {
        Column(
            modifier = modifier.fillMaxWidth().then(alphaModifier),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is RecordingBodyBlock.SeekRow -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                        ) {
                            block.links.forEach { (label, ms) ->
                                val clickable = validSegmentStarts.isEmpty() || ms in validSegmentStarts
                                InfinityPillButton(
                                    text = label,
                                    onClick = { onPlayAtMs(ms) },
                                    tone = InfinityPillTone.NEUTRAL,
                                    enabled = clickable,
                                    icon = Icons.Rounded.PlayArrow,
                                    compact = true,
                                )
                            }
                        }
                    }
                    is RecordingBodyBlock.MarkdownChunk -> {
                        if (!inspecting) {
                            MarkdownText(
                                markdown = block.markdown,
                                fontSize = 15,
                                compactSpacing = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            InspectionRichMarkdownChunk(block.markdown)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionRichMarkdownChunk(markdown: String) {
    val segments = remember(markdown) { splitRichSegments(normalizeLatexDelimiters(markdown)) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Markdown -> {
                    if (segment.content.isNotBlank()) {
                        MarkdownText(
                            markdown = segment.content,
                            fontSize = 15,
                            compactSpacing = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is ContentSegment.ECharts -> RecordingEChartsPreviewCard(segment.content)
                is ContentSegment.Mermaid -> RecordingMermaidPreviewCard(segment.content)
                is ContentSegment.Latex -> RecordingLatexPreviewCard(segment.content)
                is ContentSegment.InlineLatex -> RecordingInlineLatexPreview(segment.content)
                is ContentSegment.PlantUml -> RecordingDiagramFallbackCard(
                    icon = Icons.Rounded.Schema,
                    badge = "PlantUML",
                    title = "结构图预览",
                    source = segment.content,
                )
            }
        }
    }
}

@Composable
private fun RecordingRichCardShell(
    icon: ImageVector,
    badge: String,
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = badge,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = InfinityTypography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                ) {
                    Text(
                        text = badge,
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun RecordingEChartsPreviewCard(json: String) {
    val parsed = remember(json) { parsePreviewECharts(json) }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    RecordingRichCardShell(
        icon = Icons.Rounded.BarChart,
        badge = "ECharts",
        title = parsed.title ?: stringResource(R.string.recording_preview_sample_echarts),
    ) {
        val values = parsed.values.ifEmpty { listOf(42f, 78f, 64f, 92f) }
        val labels = if (parsed.labels.size == values.size) parsed.labels else values.indices.map { "T${it + 1}" }
        val maxVal = max(1f, values.maxOrNull() ?: 100f)

        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val leftPad = 8.dp.toPx()
            val rightPad = 8.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 6.dp.toPx()
            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

            repeat(3) { idx ->
                val y = topPad + plotH * (idx / 2f)
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (idx < 2) dash else null,
                )
            }
            val slotW = plotW / values.size
            val barW = (slotW * 0.46f).coerceIn(10.dp.toPx(), 34.dp.toPx())
            values.forEachIndexed { idx, value ->
                val ratio = (value / maxVal).coerceIn(0.08f, 1f)
                val barH = plotH * ratio
                val x = leftPad + slotW * idx + (slotW - barW) / 2f
                val y = topPad + (plotH - barH)
                drawRoundRect(
                    color = if (idx % 2 == 0) primary else tertiary,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            labels.forEachIndexed { idx, label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = values.getOrNull(idx)?.let {
                            if (it % 1f == 0f) it.toInt().toString() else String.format("%.1f", it)
                        }.orEmpty(),
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingMermaidPreviewCard(code: String) {
    val nodes = remember(code) { parsePreviewMermaidNodes(code) }
    RecordingRichCardShell(
        icon = Icons.Rounded.AccountTree,
        badge = "Mermaid",
        title = stringResource(R.string.recording_preview_sample_mermaid),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            nodes.forEachIndexed { idx, nodeLabel ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (idx == 0) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        text = nodeLabel,
                        style = InfinityTypography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (idx < nodes.lastIndex) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingLatexPreviewCard(formula: String) {
    RecordingRichCardShell(
        icon = Icons.Rounded.Functions,
        badge = "KaTeX",
        title = stringResource(R.string.recording_preview_sample_latex),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Text(
                text = formatLatexPreviewText(formula),
                style = InfinityTypography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun RecordingInlineLatexPreview(formula: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = formatLatexPreviewText(formula),
            style = InfinityTypography.bodyMedium.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun RecordingDiagramFallbackCard(
    icon: ImageVector,
    badge: String,
    title: String,
    source: String,
) {
    RecordingRichCardShell(icon = icon, badge = badge, title = title) {
        Text(
            text = source.trim(),
            style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class PreviewEChartsModel(
    val title: String?,
    val labels: List<String>,
    val values: List<Float>,
)

private fun parsePreviewECharts(json: String): PreviewEChartsModel {
    val title = Regex(""""title"\s*:\s*\{[^}]*?"text"\s*:\s*"([^"]+)"""")
        .find(json)?.groupValues?.getOrNull(1)
    val labelsRaw = Regex(""""data"\s*:\s*\[\s*((?:"[^"]*"\s*,?\s*)+)\]""")
        .find(json)?.groupValues?.getOrNull(1).orEmpty()
    val labels = Regex(""""([^"]+)"""").findAll(labelsRaw).map { it.groupValues[1] }.take(6).toList()
    val numsRaw = Regex(""""data"\s*:\s*\[\s*((?:\d+(?:\.\d+)?\s*,?\s*)+)\]""")
        .find(json)?.groupValues?.getOrNull(1).orEmpty()
    val values = Regex("""\d+(?:\.\d+)?""").findAll(numsRaw).mapNotNull { it.value.toFloatOrNull() }.take(6).toList()
    return PreviewEChartsModel(title = title, labels = labels, values = values)
}

private fun parsePreviewMermaidNodes(code: String): List<String> {
    val labelById = linkedMapOf<String, String>()
    Regex("""([A-Za-z0-9_]+)\[([^\]]+)\]""").findAll(code).forEach { match ->
        labelById.putIfAbsent(match.groupValues[1], match.groupValues[2].trim())
    }
    val orderedIds = linkedSetOf<String>()
    val edgeRegex = Regex("""([A-Za-z0-9_]+)(?:\[[^\]]+\])?\s*(?:-->|---|\-.->|==>)\s*([A-Za-z0-9_]+)(?:\[[^\]]+\])?""")
    edgeRegex.findAll(code).forEach { match ->
        orderedIds += match.groupValues[1]
        orderedIds += match.groupValues[2]
    }
    val labels = orderedIds.map { id -> labelById[id] ?: id }
    return if (labels.isNotEmpty()) labels.take(5) else listOf("转写", "分析", "富格式输出")
}

private fun formatLatexPreviewText(formula: String): String = formula.trim()
    .replace("""\approx""", "≈")
    .replace("""\times""", "×")
    .replace("""\cdot""", "·")
    .replace("""\rightarrow""", "→")
    .replace("""\le""", "≤")
    .replace("""\ge""", "≥")
    .replace(Regex("""\\frac\{([^}]+)\}\{([^}]+)\}"""), "($1)/($2)")
    .replace(Regex("""\\text\{([^}]+)\}"""), "$1")
    .replace(Regex("""\\mathrm\{([^}]+)\}"""), "$1")

private enum class RecordingEditorTab { EDIT, PREVIEW }

/**
 * 带「编辑 / 预览」双模式切换的内联整理稿编辑器。
 */
@Composable
internal fun RecordingRichInlineEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    validSegmentStarts: Set<Long>,
    onPlayAtMs: (Long) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    initialPreviewMode: Boolean = false,
    fillHeight: Boolean = false,
) {
    var tab by rememberSaveable {
        mutableStateOf(if (initialPreviewMode) RecordingEditorTab.PREVIEW else RecordingEditorTab.EDIT)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(
                    value = RecordingEditorTab.EDIT,
                    label = stringResource(R.string.recording_editor_mode_edit),
                ),
                InfinitySegment(
                    value = RecordingEditorTab.PREVIEW,
                    label = stringResource(R.string.recording_editor_mode_preview),
                ),
            ),
            selected = tab,
            onSelect = { tab = it },
            fillWidth = true,
            compact = true,
        )

        val contentBoxModifier = if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()

        if (tab == RecordingEditorTab.EDIT) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = InfinityTypography.bodyMedium.copy(fontFamily = InfinityMonospace),
                minLines = 6,
                maxLines = if (fillHeight) Int.MAX_VALUE else 14,
                shape = RoundedCornerShape(16.dp),
                modifier = contentBoxModifier,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.recording_rich_format_hint),
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = contentBoxModifier,
            ) {
                if (draft.isBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.recording_rich_format_hint),
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val previewModifier = if (fillHeight) {
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)
                    } else {
                        Modifier.padding(14.dp)
                    }
                    RecordingRichSummaryBody(
                        markdown = draft,
                        validSegmentStarts = validSegmentStarts,
                        onPlayAtMs = onPlayAtMs,
                        modifier = previewModifier,
                    )
                }
            }
        }

        InfinityPillRow(
            modifier = Modifier.fillMaxWidth(),
            spacing = InfinitySpacing.Small,
            stackedSpacing = InfinitySpacing.XSmall,
        ) {
            InfinityPillButton(
                text = stringResource(R.string.recording_cancel),
                onClick = onCancel,
                tone = InfinityPillTone.NEUTRAL,
            )
            InfinityPillButton(
                text = stringResource(R.string.recording_save),
                onClick = onSave,
                enabled = draft.isNotBlank(),
                tone = InfinityPillTone.PRIMARY,
                icon = Icons.Rounded.Check,
            )
        }
    }
}

/** 录音文档导出弹窗状态。 */
internal data class RecordingExportSheetState(
    val visible: Boolean = false,
    val sessionTitle: String = "",
    val durationLabel: String = "",
    val segmentCount: Int = 0,
    val hasOrganized: Boolean = true,
    val hasRaw: Boolean = true,
    val format: SessionExportFormat = SessionExportFormat.PNG,
    val scope: RecordingExportScope = RecordingExportScope.ORGANIZED_AND_RAW,
    val estimate: SessionExportEstimate? = null,
    val estimating: Boolean = false,
    val exporting: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun RecordingExportSheet(
    state: RecordingExportSheetState,
    onDismiss: () -> Unit,
    onFormatChange: (SessionExportFormat) -> Unit,
    onScopeChange: (RecordingExportScope) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancelExport: () -> Unit,
    popover: InfinityPopoverSpec? = null,
) {
    InfinityBottomSheet(
        show = state.visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.recording_export_sheet_title),
        height = InfinitySheetHeight.Content,
        scrollContent = false,
        dragGesturesEnabled = !state.exporting,
        dismissEnabled = !state.exporting,
        popover = popover,
    ) {
        RecordingExportSheetContent(
            state = state,
            onFormatChange = onFormatChange,
            onScopeChange = onScopeChange,
            onSave = onSave,
            onShare = onShare,
            onCancelExport = onCancelExport,
        )
    }
}

@Composable
internal fun RecordingExportSheetContent(
    state: RecordingExportSheetState,
    onFormatChange: (SessionExportFormat) -> Unit,
    onScopeChange: (RecordingExportScope) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        if (state.sessionTitle.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.recording_export_subtitle,
                    state.sessionTitle,
                    state.durationLabel.ifBlank { "00:00" },
                    state.segmentCount,
                ),
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ExportSectionCard(title = stringResource(R.string.session_export_section_format)) {
            InfinitySegmentedControl(
                segments = listOf(
                    SessionExportFormat.PNG,
                    SessionExportFormat.PDF,
                    SessionExportFormat.MARKDOWN,
                    SessionExportFormat.HTML,
                    SessionExportFormat.TXT,
                ).map { fmt ->
                    InfinitySegment(value = fmt, label = recordingExportFormatShortLabel(fmt))
                },
                selected = state.format,
                onSelect = onFormatChange,
                enabled = !state.exporting,
                fillWidth = true,
                compact = true,
            )
        }

        ExportSectionCard(title = stringResource(R.string.recording_export_section_scope)) {
            InfinitySegmentedControl(
                segments = RecordingExportScope.entries.map { scope ->
                    val enabledScope = when (scope) {
                        RecordingExportScope.ORGANIZED_ONLY -> state.hasOrganized
                        RecordingExportScope.ORGANIZED_AND_RAW -> state.hasOrganized && state.hasRaw
                        RecordingExportScope.RAW_ONLY -> state.hasRaw
                    }
                    InfinitySegment(
                        value = scope,
                        label = recordingExportScopeLabel(scope),
                        clickable = enabledScope,
                    )
                },
                selected = state.scope,
                onSelect = onScopeChange,
                enabled = !state.exporting,
                fillWidth = true,
                compact = true,
            )
        }

        RecordingExportEstimateBox(state = state)

        val footer = recordingExportFooterText(state.format)
        if (footer != null) {
            Text(
                text = footer,
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.exporting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.session_export_running, recordingExportFormatLongLabel(state.format)),
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        state.error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = message,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        RecordingExportFooterActions(
            exporting = state.exporting,
            enabled = state.hasOrganized || state.hasRaw,
            onSave = onSave,
            onShare = onShare,
            onCancel = onCancelExport,
        )
    }
}

@Composable
private fun ExportSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            SettingsSectionHeader(title = title)
            content()
        }
    }
}

@Composable
private fun RecordingExportEstimateBox(state: RecordingExportSheetState) {
    val context = LocalContext.current
    val text = when {
        state.estimating -> stringResource(R.string.session_export_estimate_loading)
        state.estimate == null -> stringResource(R.string.session_export_estimate_unavailable)
        else -> {
            val approxBytes = state.estimate.approxBytes
            val sizeText = Formatter.formatShortFileSize(context, approxBytes)
            when (state.format) {
                SessionExportFormat.PNG -> stringResource(
                    R.string.recording_export_estimate_single_png,
                    EXPORT_BASE_WIDTH_PX,
                    sizeText,
                )
                SessionExportFormat.PDF -> {
                    val pages = (state.estimate.approxPages ?: 1).coerceAtLeast(1)
                    val primary = pluralStringResource(R.plurals.recording_export_estimate_pages, pages, pages)
                    stringResource(
                        R.string.session_export_estimate_join,
                        primary,
                        stringResource(R.string.session_export_estimate_size, sizeText),
                    )
                }
                else -> {
                    val primary = pluralStringResource(
                        R.plurals.session_export_estimate_files,
                        state.estimate.primaryFileCount,
                        state.estimate.primaryFileCount,
                        ".${state.format.extension}",
                    )
                    stringResource(
                        R.string.session_export_estimate_join,
                        primary,
                        stringResource(R.string.session_export_estimate_size, sizeText),
                    )
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.session_export_estimate_title),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RecordingExportFooterActions(
    exporting: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    if (exporting) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.session_export_cancel))
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.session_export_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onShare,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.session_export_share), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun recordingExportFormatShortLabel(format: SessionExportFormat): String = when (format) {
    SessionExportFormat.PNG -> "PNG"
    SessionExportFormat.PDF -> "PDF"
    SessionExportFormat.MARKDOWN -> "MD"
    SessionExportFormat.HTML -> "HTML"
    SessionExportFormat.TXT -> "TXT"
}

@Composable
private fun recordingExportFormatLongLabel(format: SessionExportFormat): String = stringResource(
    when (format) {
        SessionExportFormat.MARKDOWN -> R.string.session_export_format_markdown
        SessionExportFormat.TXT -> R.string.session_export_format_txt
        SessionExportFormat.HTML -> R.string.session_export_format_html
        SessionExportFormat.PDF -> R.string.session_export_format_pdf
        SessionExportFormat.PNG -> R.string.session_export_format_png
    },
)

@Composable
private fun recordingExportScopeLabel(scope: RecordingExportScope): String = stringResource(
    when (scope) {
        RecordingExportScope.ORGANIZED_ONLY -> R.string.recording_export_scope_organized
        RecordingExportScope.ORGANIZED_AND_RAW -> R.string.recording_export_scope_both
        RecordingExportScope.RAW_ONLY -> R.string.recording_export_scope_raw
    },
)

@Composable
private fun recordingExportFooterText(format: SessionExportFormat): String? = when (format) {
    SessionExportFormat.PNG -> stringResource(R.string.recording_export_footer_png, EXPORT_BASE_WIDTH_PX)
    SessionExportFormat.PDF -> stringResource(R.string.recording_export_footer_pdf)
    SessionExportFormat.MARKDOWN -> stringResource(R.string.recording_export_footer_md)
    SessionExportFormat.HTML -> stringResource(R.string.recording_export_footer_html)
    SessionExportFormat.TXT -> stringResource(R.string.recording_export_footer_txt)
}

private const val EXPORT_BASE_WIDTH_PX = 1440

private val ProtoRichMarkdown = """
    ## 架构评审结论

    本次评审确认：工作区授权启用后，Agent 可直接读写录音文档 `.md` 并只读访问原始 `.wav` 音频；导出支持 `1440px` 基准宽度无上限 `1:1` 长图与自适应矢量 PDF。

    [00:12](recording://seek/12000) [02:48](recording://seek/168000) [05:30](recording://seek/330000)

    ### 模块分工与交付指标

    | 模块 | 状态 | 规格 |
    |---|---|---|
    | 资料库同步 | 已就绪 | CAS 写 |
    | 长图编码 | 已验证 | `1440px+` |
    | 矢量 PDF | 已验证 | 不拆块 |

    ```mermaid
    flowchart LR
        A[录音听写] --> B[云端富格式整理]
        B --> C[资料库双向同步]
        C --> D[无上限 1:1 导出]
    ```

    ```echarts
    {
      "title": { "text": "各环节耗时分布（ms）" },
      "xAxis": { "data": ["转写", "整理", "同步", "导出"] },
      "series": [{ "type": "bar", "data": [180, 420, 95, 260] }]
    }
    ```

    $$\text{ScaleRatio} = \frac{\text{CanvasWidth}_{\text{px}}}{720} \times 1.0, \quad W_{\text{base}} = 1440\text{px}$$
""".trimIndent()

private val ProtoEditorMarkdown = """
    ### 核心预算核算

    已核对 Q4 音频处理链路吞吐量，见 [01:15](recording://seek/75000)。

    | 指标 | 当前 | 目标 |
    |---|---|---|
    | 基准宽 | `1440px` | 无上限 |
    | 完整度 | 单文件 | `1:1` |

    $$\text{Memory}_{\text{band}} \approx 4 \times W \times 256\text{ B}$$
""".trimIndent()

private val ProtoSegmentStarts = setOf(12_000L, 75_000L, 168_000L, 330_000L)

private val ProtoOrganizeSource = RecordingOrganizeSource(
    cloud = true,
    provider = "OpenAI",
    model = "gpt-5.4",
    completedAt = 1_760_000_000_000L,
)

@Composable
private fun RecordingRichProtoFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

@Preview(name = "1. 富格式整理稿 窄屏深色中文", widthDp = 360, heightDp = 1220, locale = "zh-rCN")
@Composable
internal fun ProtoRichSummaryNarrowDarkPreview() {
    RecordingRichProtoFrame(dark = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "录音整理与导出升级评审",
                        style = InfinityTypography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Edit, contentDescription = "编辑整理稿", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.IosShare, contentDescription = "导出文档", modifier = Modifier.size(18.dp))
                    }
                }
                RecordingProvenanceRow(
                    source = ProtoOrganizeSource,
                    justUpdated = false,
                    manualEdit = false,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                RecordingRichSummaryBody(
                    markdown = ProtoRichMarkdown,
                    validSegmentStarts = ProtoSegmentStarts,
                    onPlayAtMs = {},
                )
            }
        }
    }
}

@Preview(name = "2. 富格式整理稿 宽屏双栏浅色中文", widthDp = 840, heightDp = 960, locale = "zh-rCN")
@Composable
internal fun ProtoRichSummaryWideLightPreview() {
    RecordingRichProtoFrame(dark = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(0.95f).fillMaxHeight().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    Text("原始转写（共 4 段）", style = InfinityTypography.titleSmall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val transcripts = listOf(
                        Triple("00:12", "架构师", "授权开启后 Agent 应该能按作用域读取录音文档，并且同步只读访问原始音频。"),
                        Triple("02:48", "客户端", "导出图像基准宽度升到 1440px，长内容直接放开高度和宽度的双向上限。"),
                        Triple("05:30", "渲染组", "通过分带流式 PNG 编码与自适应分页 PDF，确保图表与数学公式不截断。"),
                    )
                    transcripts.forEachIndexed { idx, (time, speaker, text) ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (idx == 1) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    InfinityPillButton(
                                        text = time,
                                        onClick = {},
                                        tone = InfinityPillTone.NEUTRAL,
                                        icon = Icons.Rounded.PlayArrow,
                                        compact = true,
                                    )
                                    Text(
                                        text = speaker,
                                        style = InfinityTypography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(text = text, style = InfinityTypography.bodyMedium)
                            }
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1.15f).fillMaxHeight().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("整理稿", style = InfinityTypography.titleSmall, modifier = Modifier.weight(1f))
                        InfinityPillButton(
                            text = "导出文档",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.IosShare,
                            compact = true,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecordingProvenanceRow(
                        source = ProtoOrganizeSource,
                        justUpdated = true,
                        manualEdit = false,
                    )
                    RecordingRichSummaryBody(
                        markdown = ProtoRichMarkdown,
                        validSegmentStarts = ProtoSegmentStarts,
                        onPlayAtMs = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "3. 整理稿内联编辑器与预览 窄屏大字深色", widthDp = 360, heightDp = 900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
internal fun ProtoRichInlineEditorPreview() {
    RecordingRichProtoFrame(dark = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Text("编辑整理稿", style = InfinityTypography.titleLarge)
                RecordingRichInlineEditor(
                    draft = ProtoEditorMarkdown,
                    onDraftChange = {},
                    validSegmentStarts = ProtoSegmentStarts,
                    onPlayAtMs = {},
                    onCancel = {},
                    onSave = {},
                    initialPreviewMode = true,
                )
            }
        }
    }
}

@Composable
private fun RecordingExportProtoShell(
    title: String,
    state: RecordingExportSheetState,
    dark: Boolean,
) {
    RecordingRichProtoFrame(dark = dark) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .padding(vertical = 14.dp)
                                .width(40.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    }
                    Text(
                        text = title,
                        style = InfinityTypography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    RecordingExportSheetContent(
                        state = state,
                        onFormatChange = {},
                        onScopeChange = {},
                        onSave = {},
                        onShare = {},
                        onCancelExport = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "4. 录音导出面板 PNG长图 浅色中文", widthDp = 360, heightDp = 760, locale = "zh-rCN")
@Composable
internal fun ProtoRecordingExportPngLightZhPreview() {
    RecordingExportProtoShell(
        title = "导出录音文档",
        dark = false,
        state = RecordingExportSheetState(
            visible = true,
            sessionTitle = "产品架构与音频权限评审会",
            durationLabel = "14:32",
            segmentCount = 48,
            hasOrganized = true,
            hasRaw = true,
            format = SessionExportFormat.PNG,
            scope = RecordingExportScope.ORGANIZED_AND_RAW,
            estimate = SessionExportEstimate(
                primaryFileCount = 1,
                attachmentArchive = false,
                approxBytes = 2_650_000L,
            ),
        ),
    )
}

@Preview(name = "5. Recording Export Sheet PDF Dark EN", widthDp = 360, heightDp = 760, locale = "en")
@Composable
internal fun ProtoRecordingExportPdfDarkEnPreview() {
    RecordingExportProtoShell(
        title = "Export recording",
        dark = true,
        state = RecordingExportSheetState(
            visible = true,
            sessionTitle = "Audio Pipeline & Canvas Design Sync",
            durationLabel = "14:32",
            segmentCount = 48,
            hasOrganized = true,
            hasRaw = true,
            format = SessionExportFormat.PDF,
            scope = RecordingExportScope.ORGANIZED_AND_RAW,
            estimate = SessionExportEstimate(
                primaryFileCount = 1,
                attachmentArchive = false,
                approxBytes = 420_000L,
                approxPages = 4,
            ),
        ),
    )
}
