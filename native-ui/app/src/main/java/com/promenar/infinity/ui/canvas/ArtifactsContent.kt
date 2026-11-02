package com.promenar.infinity.ui.canvas

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.artifact.ArtifactCandidate
import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.artifact.ArtifactSummary
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/**
 * 产物页正文：顶部分类筛选，原型以卡片网格在前，页面、文档、图片、其它分组列出；
 * “全部”视图末尾是工作区里未收录的新文件，可收录或忽略。
 */
@Composable
internal fun ArtifactsContent(
    artifacts: List<ArtifactSummary>,
    candidates: List<ArtifactCandidate>,
    onOpen: (ArtifactSummary) -> Unit,
    onClaim: (ArtifactCandidate) -> Unit,
    onIgnore: (ArtifactCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(ArtifactFilter.ALL) }
    val filter = effectiveArtifactFilter(selected, artifacts)
    val view = artifactSections(artifacts, filter, candidates.size)
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(UiTags.CANVAS_LIST),
        contentPadding = PaddingValues(bottom = InfinitySpacing.XLarge),
    ) {
        if (artifacts.isEmpty() && candidates.isEmpty()) {
            item(key = "empty") { ArtifactsEmpty() }
            return@LazyColumn
        }
        if (artifacts.isNotEmpty()) item(key = "filters") {
            InfinitySegmentedControl(
                segments = artifactFilters(artifacts).map { (option, count) ->
                    InfinitySegment(option, stringResource(option.labelRes), tag = UiTags.artifactFilter(option.name), count = count)
                },
                selected = filter,
                onSelect = { selected = it },
                contentPadding = PaddingValues(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Small, bottom = InfinitySpacing.XSmall),
            )
        }
        if (view.prototypes.isNotEmpty()) {
            item(key = "prototype-header") { SectionHeader(stringResource(R.string.artifact_section_prototype), stringResource(R.string.artifact_prototype_hint)) }
            view.prototypes.chunked(2).forEachIndexed { row, pair ->
                item(key = "prototype-row-$row-${pair.first().id}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall),
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        pair.forEach { prototype -> PrototypeCard(prototype, onClick = { onOpen(prototype) }, modifier = Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        view.sections.forEach { section ->
            item(key = "section-${section.kind}") {
                SectionHeader(stringResource(section.kind.sectionRes), section.items.size.toString())
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large),
                ) {
                    Column {
                        section.items.forEach { artifact -> ArtifactRow(artifact, onClick = { onOpen(artifact) }) }
                    }
                }
            }
        }
        if (view.prototypes.isEmpty() && view.sections.isEmpty() && artifacts.isNotEmpty()) item(key = "category-empty") {
            Text(
                text = stringResource(R.string.artifact_category_empty),
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.XXLarge),
            )
        }
        if (view.showCandidates || (artifacts.isEmpty() && candidates.isNotEmpty())) item(key = "candidates") {
            CandidatesSection(candidates, onClaim, onIgnore)
        }
    }
}

private val ArtifactFilter.labelRes: Int
    get() = kind?.sectionRes ?: R.string.artifact_filter_all

internal val ArtifactKind.sectionRes: Int
    get() = when (this) {
        ArtifactKind.PROTOTYPE -> R.string.artifact_section_prototype
        ArtifactKind.PAGE -> R.string.artifact_section_page
        ArtifactKind.DOCUMENT -> R.string.artifact_section_document
        ArtifactKind.IMAGE -> R.string.artifact_section_image
        ArtifactKind.OTHER -> R.string.artifact_section_other
    }

internal val ArtifactKind.labelRes: Int
    get() = when (this) {
        ArtifactKind.PROTOTYPE -> R.string.artifact_kind_prototype
        ArtifactKind.PAGE -> R.string.artifact_kind_page
        ArtifactKind.DOCUMENT -> R.string.artifact_kind_document
        ArtifactKind.IMAGE -> R.string.artifact_kind_image
        ArtifactKind.OTHER -> R.string.artifact_kind_other
    }

internal val ArtifactKind.icon: ImageVector
    get() = when (this) {
        ArtifactKind.PROTOTYPE -> Icons.Rounded.Smartphone
        ArtifactKind.PAGE -> Icons.Rounded.Web
        ArtifactKind.DOCUMENT -> Icons.Rounded.Description
        ArtifactKind.IMAGE -> Icons.Rounded.Image
        ArtifactKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }

/** 各分类的图标底色与前景色，取自主题容器色，深浅色下都成对可读。 */
@Composable
internal fun ArtifactKind.tint(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        ArtifactKind.PROTOTYPE -> scheme.primaryContainer to scheme.onPrimaryContainer
        ArtifactKind.PAGE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ArtifactKind.DOCUMENT -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ArtifactKind.IMAGE -> scheme.surfaceContainerHighest to scheme.tertiary
        ArtifactKind.OTHER -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}

@Composable
internal fun ArtifactKindIcon(kind: ArtifactKind, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val (background, foreground) = kind.tint()
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(size * 0.5f))
    }
}

/** 一行副标题：原型写屏数，文件写类型与大小，最后是版本。 */
@Composable
internal fun artifactMeta(artifact: ArtifactSummary): String {
    val context = LocalContext.current
    val version = stringResource(R.string.artifact_meta_version, artifact.currentVersion)
    val parts = buildList {
        if (artifact.kind == ArtifactKind.PROTOTYPE) add(pluralStringResource(R.plurals.artifact_screen_count, artifact.screenCount, artifact.screenCount))
        artifact.fileName?.substringAfterLast('.', "")?.takeIf(String::isNotEmpty)?.let { add(it.uppercase()) }
        artifact.sizeBytes?.let { add(Formatter.formatShortFileSize(context, it)) }
        add(version)
    }
    return parts.joinToString(" · ")  // wrap-check: allow 调用处均为单行省略
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = InfinitySpacing.Large + InfinitySpacing.XSmall, end = InfinitySpacing.Large, top = InfinitySpacing.Large, bottom = InfinitySpacing.Small),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Text(
            text = title,
            style = InfinityTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = detail, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrototypeCard(prototype: ArtifactSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.testTag(UiTags.artifactItem(prototype.id)),
    ) {
        Column {
            PrototypeStackThumbnail(Modifier.fillMaxWidth().height(104.dp))
            Column(Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small + InfinitySpacing.XSmall)) {
                Text(
                    text = prototype.title,
                    style = InfinityTypography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artifactMeta(prototype),
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 原型卡的示意图：三台错开的手机轮廓，不加载网页，列表滚动时不创建 WebView。 */
@Composable
internal fun PrototypeStackThumbnail(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.background(scheme.primaryContainer.copy(alpha = 0.55f)),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(-6f, 0f, 6f).forEach { angle ->
            val center = angle == 0f
            Column(
                modifier = Modifier
                    .padding(top = if (center) 0.dp else 8.dp)
                    .rotate(angle)
                    .size(width = if (center) 38.dp else 34.dp, height = if (center) 70.dp else 62.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.surface)
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(7.dp))
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(if (center) 16.dp else 12.dp).clip(RoundedCornerShape(3.dp)).background(if (center) scheme.primary else scheme.primaryContainer))
                repeat(3) { Box(Modifier.fillMaxWidth().height(3.dp).background(scheme.surfaceContainerHighest)) }
            }
        }
    }
}

@Composable
private fun ArtifactRow(artifact: ArtifactSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small + InfinitySpacing.XSmall)
            .testTag(UiTags.artifactItem(artifact.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        ArtifactKindIcon(artifact.kind)
        Column(Modifier.weight(1f)) {
            Text(text = artifact.title, style = InfinityTypography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = artifactMeta(artifact),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidatesSection(
    candidates: List<ArtifactCandidate>,
    onClaim: (ArtifactCandidate) -> Unit,
    onIgnore: (ArtifactCandidate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = InfinitySpacing.SectionGap)) {
        Column(Modifier.padding(horizontal = InfinitySpacing.Large + InfinitySpacing.XSmall)) {
            Text(
                text = stringResource(R.string.artifact_candidates_title),
                style = InfinityTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.artifact_candidates_description),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.Small),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Large)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            candidates.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.XSmall, top = InfinitySpacing.Small, bottom = InfinitySpacing.Small)
                        .testTag(UiTags.artifactCandidate(candidate.path)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = InfinitySpacing.XSmall)) {
                        Text(text = candidate.fileName, style = InfinityTypography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = candidate.path,
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val ignoreLabel = stringResource(R.string.artifact_candidate_ignore_cd, candidate.fileName)
                    InfinityPillButton(
                        text = stringResource(R.string.artifact_candidate_ignore),
                        onClick = { onIgnore(candidate) },
                        compact = true,
                        modifier = Modifier
                            .semantics { contentDescription = ignoreLabel }
                            .testTag(UiTags.artifactIgnore(candidate.path)),
                    )
                    val claimLabel = stringResource(R.string.artifact_candidate_claim_cd, candidate.fileName)
                    InfinityPillButton(
                        text = stringResource(R.string.artifact_candidate_claim),
                        onClick = { onClaim(candidate) },
                        tone = InfinityPillTone.PRIMARY,
                        compact = true,
                        modifier = Modifier
                            .padding(end = InfinitySpacing.XSmall)
                            .semantics { contentDescription = claimLabel }
                            .testTag(UiTags.artifactClaim(candidate.path)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactsEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(Icons.Rounded.Dashboard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(text = stringResource(R.string.canvas_empty_title), style = InfinityTypography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = stringResource(R.string.canvas_empty_description),
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
