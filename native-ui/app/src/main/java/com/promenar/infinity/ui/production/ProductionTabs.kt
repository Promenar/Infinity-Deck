package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.DialogueAudioSource
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReferenceInputMode
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.coverPreviewPath
import com.promenar.infinity.data.production.effectiveReferenceInputMode
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberMediaActionRunner
import com.promenar.infinity.ui.media.rememberWorkspaceMediaResolution
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun episodeName(episode: Episode): List<String> {
    val number = stringResource(R.string.production_panel_episode_number, episode.number)
    return listOf(number, episode.title).filter(String::isNotBlank).distinctBy { it.filterNot(Char::isWhitespace).lowercase() }
}

/** 剧集与多集项目保留集数，电影单集只展示标题。 */
@Composable
internal fun episodeDisplayName(production: Production, episode: Episode): List<String> =
    if (production.showEpisodeNumbers()) episodeName(episode)
    else listOf(episode.title.ifBlank { production.title }).filter(String::isNotBlank)

// ---------- 总览辅助 ----------

/** 阶段可选状态：只有动态预演可跳过。 */
internal fun stageChoices(stage: ProductionStage): List<StageStatus> =
    if (stage == ProductionStage.ANIMATIC) StageStatus.entries else StageStatus.entries - StageStatus.SKIPPED

@Composable
internal fun EpisodeProgressRow(episode: Episode, onClick: () -> Unit) {
    val stats = remember(episode) { episode.shotStats() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.RowSingleLineHeight).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Text(episodeName(episode).joinToString(" "), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        ProductionProgressBar(stats.progress, Modifier.weight(1f))
        Text(
            when {
                stats.total > 0 -> stringResource(R.string.p2_progress_compact, stats.locked, stats.total)
                episode.scriptPath != null -> stringResource(R.string.production_panel_stage_script)
                else -> stringResource(R.string.p2_outline)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private const val MAX_PREVIEW_BYTES = 128 * 1024

private fun isTextPreviewPath(path: String, mimeType: String?): Boolean {
    if (mimeType?.startsWith("text/") == true) return true
    val lower = path.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") ||
        lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".fountain")
}

/** 工作区文件行：文本与 Markdown 支持应用内快速阅读，其余格式或回退目录使用外部/文件定位。 */
@Composable
internal fun ProductionFileRow(
    path: String,
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    value: String? = null,
    chip: ProductionChipSpec? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ProductionFileRowHost(path = path, title = title, subtitle = subtitle) { onOpen ->
        ProductionRow(
            title = title,
            icon = icon,
            subtitle = subtitle,
            subtitleMaxLines = 1,
            value = value,
            chip = chip,
            chevron = trailing == null && onOpen != null,
            trailing = trailing,
            enabled = true,
            onClick = onOpen,
        )
    }
}

/** 复用工作区文件解析与应用内文本/Markdown 预览弹层，支持单行和两段式剧本卡共用。 */
@Composable
private fun ProductionFileRowHost(
    path: String,
    title: String,
    subtitle: String? = null,
    content: @Composable (onOpen: (() -> Unit)?) -> Unit,
) {
    val environment = LocalWorkspaceMedia.current
    val mapped = environment?.let { rememberProductionPath(it, path) as? ProductionPathState.Ready }
    val resolution = if (environment != null && mapped != null) rememberWorkspaceMediaResolution(environment, mapped.path)?.resolution else null
    val runner = environment?.let { rememberMediaActionRunner(it) }
    val available = resolution as? MediaResolution.Available
    val isText = isTextPreviewPath(path, available?.ref?.mimeType)
    val locateDir = mapped?.path?.substringBeforeLast('/')?.ifBlank { "/" } ?: "/${path.trimStart('/').substringBeforeLast('/', "")}".ifBlank { "/" }
    var previewOpen by remember(path) { mutableStateOf(false) }

    val onOpen: (() -> Unit)? = when {
        available != null && isText -> ({ previewOpen = true })
        available != null && runner != null -> ({ runner.openWith(available) })
        environment?.onLocate != null -> ({ environment.onLocate.invoke(locateDir) })
        else -> null
    }
    content(onOpen)
    if (previewOpen && available != null && environment != null) {
        val loaded by produceState<Result<String>?>(null, available, environment) {
            this.value = withContext(Dispatchers.IO) {
                runCatching {
                    environment.resolver.open(available).use { stream ->
                        val buffer = ByteArray(MAX_PREVIEW_BYTES + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val n = stream.read(buffer, read, buffer.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        val truncated = read > MAX_PREVIEW_BYTES
                        val text = String(buffer, 0, minOf(read, MAX_PREVIEW_BYTES), Charsets.UTF_8)
                        if (truncated) "$text\n\n..." else text
                    }
                }
            }
        }
        InfinityBottomSheet(show = true, onDismiss = { previewOpen = false }, title = title, scrollContent = true) {
            Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    environment.onLocate?.let { locate ->
                        InfinityPillButton(
                            text = stringResource(R.string.p2_open_in_files),
                            onClick = { previewOpen = false; locate(locateDir) },
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.FolderOpen,
                            compact = true,
                        )
                    }
                    runner?.let { r ->
                        InfinityPillButton(
                            text = stringResource(R.string.p2_open_external),
                            onClick = { previewOpen = false; r.openWith(available) },
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            compact = true,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                when (val result = loaded) {
                    null -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
                    else -> result.fold(
                        onSuccess = { text ->
                            val lower = path.lowercase()
                            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                                MarkdownText(markdown = text, isStreaming = false, showCursor = false, fontSize = 14)
                            } else {
                                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace))
                            }
                        },
                        onFailure = {
                            Text(stringResource(R.string.p2_file_read_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            }
        }
    }
}

// ---------- 剧本 ----------

@Composable
internal fun ProductionScriptTab(production: Production, busy: Boolean, onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String? = { null }, onOpenEpisode: (String) -> Unit = {}) {
    val episodes = production.episodes()
    var reviewing by remember { mutableStateOf<Episode?>(null) }
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
        production.logline?.takeIf(String::isNotBlank)?.let { logline ->
            item("logline") {
                ProductionSection(stringResource(R.string.p2_logline)) {
                    Text(logline, style = MaterialTheme.typography.bodyMedium, modifier = ProductionCardInset)
                }
            }
        }
        item("scripts") {
            ProductionSection(if (production.logline.isNullOrBlank()) null else stringResource(R.string.production_panel_stage_script)) {
                if (episodes.isEmpty()) ProductionMutedRow(stringResource(R.string.production_panel_no_script))
                episodes.forEach { episode ->
                    val chip = production.reviewChipSpec(ReviewTarget.script(episode.id), settled = episode.scriptLocked)
                    val title = episodeDisplayName(production, episode).joinToString(" ")
                    val path = episode.scriptPath
                    if (path == null) ProductionRow(title, icon = Icons.Rounded.Description, subtitle = stringResource(R.string.production_panel_no_script), chevron = false)
                    else ProductionFileRowHost(path = path, title = title, subtitle = path) { onOpen ->
                        ScriptTwoTierRow(
                            title = title,
                            path = path,
                            chip = chip,
                            onOpen = onOpen,
                            reviewAction = {
                                InfinityPillButton(
                                    text = stringResource(R.string.p2_review_action),
                                    onClick = { reviewing = episode },
                                    tone = InfinityPillTone.NEUTRAL,
                                    icon = Icons.Rounded.RateReview,
                                    enabled = !busy,
                                    compact = true,
                                )
                            },
                        )
                    }
                }
            }
        }
        episodes.forEach { episode ->
            item("scenes-${episode.id}") {
                val scenes = stringResource(R.string.p2_scenes)
                val titleSegments = if (production.showEpisodeNumbers()) listOf(episodeDisplayName(production, episode).joinToString(" "), scenes) else null
                ProductionSection(scenes, titleSegments = titleSegments) {
                    if (episode.scenes.isEmpty()) ProductionMutedRow(stringResource(R.string.production_panel_no_shots))
                    episode.scenes.forEach { scene ->
                        val chip = if (scene.shots.isEmpty()) ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                        else production.reviewChipSpec(ReviewTarget.scene(episode.id, scene.code), settled = scene.storyboardLocked)
                        ProductionRow(
                            title = scene.heading,
                            subtitle = if (scene.shots.isEmpty()) stringResource(R.string.p2_no_shots_split) else stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds })),
                            leading = { Text(scene.number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            chip = chip,
                            chevron = false,
                            onClick = { onOpenEpisode(episode.id) },
                        )
                    }
                }
            }
        }
        if (production.bible.isNotEmpty()) {
            item("bible") {
                ProductionSection(stringResource(R.string.production_panel_section_bible)) {
                    production.bible.forEach { document -> ProductionFileRow(document.path, document.title, Icons.AutoMirrored.Rounded.MenuBook, subtitle = document.path) }
                }
            }
        }
    }
    reviewing?.let { episode ->
        ProductionReviewSheet(
            title = episodeDisplayName(production, episode).joinToString(" "),
            subtitle = episode.scriptPath,
            production = production,
            target = ReviewTarget.script(episode.id),
            busy = busy,
            onRun = onRun,
            onSubmit = onSubmit,
            onDismiss = { reviewing = null },
            settled = episode.scriptLocked,
        )
    }
}

/** 剧本两段式卡片行：首行左标题 + 右唯一状态徽章，次行缩进路径 + 右审核按钮，互不挤压。 */
@Composable
private fun ScriptTwoTierRow(
    title: String,
    path: String,
    chip: ProductionChipSpec?,
    onOpen: (() -> Unit)?,
    reviewAction: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            chip?.let { ProductionStatusChip(it.text, it.tone) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            reviewAction()
        }
    }
}

// ---------- 分镜 ----------

@Composable
internal fun ProductionStoryboardTab(
    production: Production,
    episodeId: String?,
    onSelectEpisode: (String) -> Unit,
    busy: Boolean,
    onOpenShot: (episodeId: String, shotCode: String) -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    services: ProductionPanelServices = ProductionPanelServices(),
    channels: List<MediaChannel> = emptyList(),
    channelOrder: List<String> = emptyList(),
    onSubmit: suspend (ProductionUserAction) -> String? = { null },
) {
    var reviewing by remember { mutableStateOf<Scene?>(null) }
    val episodes = production.episodes()
    val episode = episodes.firstOrNull { it.id == episodeId } ?: episodes.firstOrNull()
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
        if (episode == null || episode.scenes.isEmpty()) {
            item("empty") { ProductionSection(null) { ProductionMutedRow(stringResource(R.string.production_panel_no_shots)) } }
        }
        if (episode != null && episodes.size > 1) {
            item("episode") { EpisodePicker(episodes, episode, onSelectEpisode) }
        }
        episode?.scenes?.forEach { scene ->
            item(key = "scene-${scene.code}") {
                val chip = production.reviewChipSpec(ReviewTarget.scene(episode.id, scene.code), settled = scene.storyboardLocked)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProductionSceneHeader(
                        scene = scene,
                        meta = listOf(stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds }))),
                        statusChip = chip,
                        action = if (scene.shots.isEmpty()) null else ({
                            InfinityPillButton(
                                text = stringResource(R.string.p2_review_action),
                                onClick = { reviewing = scene },
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.RateReview,
                                enabled = !busy,
                                compact = true,
                            )
                        }),
                    )
                    if (scene.shots.isEmpty()) {
                        BettboxListGroup {
                            ProductionRow(stringResource(R.string.p2_no_shots_split), titleColor = MaterialTheme.colorScheme.onSurfaceVariant, chip = ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            scene.shots.forEach { shot ->
                                StoryboardShotRow(shot, production, services, channels, channelOrder) { onOpenShot(episode.id, shot.code) }
                            }
                        }
                    }
                    if (!scene.storyboardLocked && services.onAddShot != null) {
                        BettboxListGroup {
                            ProductionRow(
                                stringResource(R.string.p2_add_shot),
                                icon = Icons.Rounded.Add,
                                titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                chevron = false,
                                enabled = !busy,
                                onClick = { services.onAddShot.invoke(episode.id, scene.code) },
                            )
                        }
                    }
                }
            }
        }
    }
    reviewing?.let { scene ->
        if (episode != null) ProductionReviewSheet(
            title = stringResource(R.string.production_panel_scene_number, scene.number),
            subtitle = scene.heading,
            production = production,
            target = ReviewTarget.scene(episode.id, scene.code),
            busy = busy,
            onRun = onRun,
            onSubmit = onSubmit,
            onDismiss = { reviewing = null },
            settled = scene.storyboardLocked,
        )
    }
}

/** 分镜一级卡片：与详情页字段层级对齐，保留高效扫描与生成规划摘要。 */
@Composable
private fun StoryboardShotRow(shot: Shot, production: Production, services: ProductionPanelServices, channels: List<MediaChannel>, channelOrder: List<String>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(ProductionUiTags.SHOT_ROW),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(7.dp), color = scheme.surfaceContainerHighest) {
                    Text(
                        shot.code,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    shot.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                shot.size?.takeIf(String::isNotBlank)?.let { ShotCompactTag(it, Modifier.widthIn(max = 104.dp)) }
                ShotCompactTag(secondsLabel(shot.durationSeconds))
            }

            val visual = listOfNotNull(shot.visual, shot.performance).filter(String::isNotBlank).joinToString("")
            if (listOfNotNull(shot.camera, shot.cameraMovement, visual.takeIf(String::isNotBlank)).isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    shot.camera?.takeIf(String::isNotBlank)?.let { ShotCardFieldLine(stringResource(R.string.p2_field_camera_short), it) }
                    shot.cameraMovement?.takeIf(String::isNotBlank)?.let { ShotCardFieldLine(stringResource(R.string.p2_field_movement_short), it, maxLines = 2) }
                    visual.takeIf(String::isNotBlank)?.let { ShotCardFieldLine(stringResource(R.string.p2_field_visual), it, maxLines = 3, primaryTone = true) }
                }
            }

            if (shot.dialogue.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.p2_field_dialogue),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            shot.dialogue.forEach { line ->
                                DialogueLine(
                                    name = production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId,
                                    text = line.text,
                                    tone = line.tone,
                                )
                            }
                        }
                    }
                }
            }

            if (!shot.sound.isNullOrBlank() || !shot.transition.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    shot.sound?.takeIf(String::isNotBlank)?.let { sound ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(14.dp), muted)
                            Text(sound, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    shot.transition?.takeIf(String::isNotBlank)?.let { transition ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(14.dp), muted)
                            Text(transition, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            PlanLine(shot, production, services, channels, channelOrder)
        }
    }
}

@Composable
private fun ShotCompactTag(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ShotCardFieldLine(label: String, value: String, maxLines: Int = 2, primaryTone: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 36.dp).padding(top = 1.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (primaryTone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 对白一行：角色名用强调色，台词用直角引号，语气弱化。 */
@Composable
internal fun DialogueLine(name: String, text: String, tone: String?, modifier: Modifier = Modifier, textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    val accent = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = accent)) { append(name) }
            append("「$text」")
            tone?.takeIf(String::isNotBlank)?.let { withStyle(SpanStyle(color = muted)) { append(" $it") } }
        },
        style = textStyle,
        modifier = modifier,
    )
}

/** 生成规划摘要：导入资产小图、参考语音、通道、模式与入参方式。 */
@Composable
private fun PlanLine(shot: Shot, production: Production, services: ProductionPanelServices, channels: List<MediaChannel>, channelOrder: List<String>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val channel = resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)
    val channelName = when {
        shot.channelId != null -> services.channelLabelSegments(shot.channelId)
        channel != null -> services.channelLabelSegments(channel.id)
        else -> listOf(stringResource(R.string.p2_waiting_channel))
    }
    val packing = if (shot.mode == ShotMode.REFERENCE && channel != null) stringResource(
        if (production.effectiveReferenceInputMode(channel) == ReferenceInputMode.SHEET) R.string.p2_composite else R.string.p2_separate,
    ) else null
    val paths = production.importedReferencePaths(shot).take(4)
    val voice = shot.dialogue.any { it.audioSource == DialogueAudioSource.NATIVE }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            paths.forEach { ProductionThumbnail(it, Modifier.size(22.dp)) }
            if (voice) Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(13.dp), MaterialTheme.colorScheme.onTertiaryContainer)
            }
            if (paths.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.p2_ref_count_compact, paths.size), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        val summary = if (channel == null && shot.channelId == null) {
            listOfNotNull(shot.mode.label(), stringResource(R.string.p2_waiting_channel), stringResource(R.string.p2_edited).takeIf { shot.promptUserEdited })
        } else {
            planSummarySegments(channelName, shot.mode.label(), packing, shot.promptUserEdited, stringResource(R.string.p2_edited))
        }
        Text(
            summary.joinToString(" · "), // wrap-check: allow 分镜规划摘要与缩略图同排，固定单行省略
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), muted)
    }
}

@Composable
internal fun EpisodePicker(episodes: List<Episode>, current: Episode, onSelect: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.DropdownList) { menu = true }
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        ) {
            InfinityMetaLine(segments = episodeName(current), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            episodes.forEach { episode ->
                InfinityMenuItem(
                    text = { InfinityMetaLine(segments = episodeName(episode), maxLines = 1) },
                    selected = episode.id == current.id,
                    onClick = { menu = false; onSelect(episode.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

// ---------- 资产 ----------

@Composable
internal fun ProductionAssetsTab(
    production: Production,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    onOpenCharacter: (String) -> Unit = {},
    requestedKind: AssetKind? = null,
    onRequestedKindHandled: () -> Unit = {},
) {
    val initialKind = AssetKind.entries.firstOrNull { kind -> production.assets.any { it.kind == kind } } ?: AssetKind.CHARACTER
    var kind by rememberSaveable(production.id) { mutableStateOf(initialKind) }
    LaunchedEffect(requestedKind) {
        if (requestedKind != null) {
            kind = requestedKind
            onRequestedKindHandled()
        }
    }
    var openAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = production.assets.filter { it.kind == kind }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = ProductionPagePadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("kind", span = { GridItemSpan(maxLineSpan) }) {
            InfinitySegmentedControl(
                segments = AssetKind.entries.map { itemKind ->
                    InfinitySegment(itemKind, itemKind.label(), count = production.assets.count { it.kind == itemKind })
                },
                selected = kind,
                onSelect = { kind = it },
                compact = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        if (assets.isEmpty()) {
            item("empty", span = { GridItemSpan(maxLineSpan) }) { ProductionSection(null) { ProductionMutedRow(stringResource(R.string.production_panel_no_assets)) } }
        }
        items(assets, key = { it.id }) { asset ->
            AssetCard(asset, production, usageCount = production.shotsUsingAsset(asset.id), modifier = Modifier.animateItem(), onClick = {
                if (asset.kind != AssetKind.SOUND) onOpenCharacter(asset.id) else openAssetId = asset.id
            })
        }
    }
    openAssetId?.let { id ->
        val asset = production.findAsset(id)
        if (asset == null) {
            // 资产被智能体移除时关闭详情。
            LaunchedEffect(id) { openAssetId = null }
        } else {
            ProductionAssetSheet(
                asset = asset,
                production = production,
                busy = busy,
                onDismiss = { openAssetId = null },
                onRun = onRun,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun AssetCard(asset: Asset, production: Production, usageCount: Int = 0, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val versionNumber = asset.currentVersion ?: asset.versions.maxByOrNull { it.number }?.number
    val chip = when {
        asset.kind == AssetKind.CHARACTER && asset.looks.isNotEmpty() -> {
            val primaryLook = asset.looks.first()
            production.reviewChipSpec(
                ReviewTarget.assetLook(asset.id, primaryLook.id),
                settled = primaryLook.locked || asset.status == AssetStatus.LOCKED,
            )
        }
        asset.kind != AssetKind.SOUND && asset.referenceViews.isNotEmpty() && versionNumber == null ->
            production.reviewChipSpec(
                ReviewTarget.assetViews(asset.id),
                settled = asset.viewsLocked || asset.status == AssetStatus.LOCKED,
            )
        versionNumber != null -> production.reviewChipSpec(
            ReviewTarget.assetVersion(asset.id, versionNumber),
            settled = asset.status == AssetStatus.LOCKED && versionNumber == asset.currentVersion,
        )
        else -> null
    } ?: ProductionChipSpec(asset.status.label(), asset.status.tone())
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag(ProductionUiTags.ASSET_CARD),
        onClick = onClick,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp))) {
                val path = asset.coverPreviewPath()
                if (asset.kind == AssetKind.SOUND && path == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(44.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                } else {
                    ProductionThumbnail(path, Modifier.fillMaxSize(), contentDescription = asset.name)
                }
                ProductionStatusChip(
                    chip.text,
                    chip.tone,
                    overlay = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
                if (asset.kind == AssetKind.CHARACTER) Row(Modifier.align(Alignment.BottomStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (asset.looks.isNotEmpty()) ClipBadge(stringResource(R.string.p2_look_count, asset.looks.size))
                    if (asset.referenceVoice?.path != null) ClipBadge(stringResource(R.string.p2_voice_badge))
                } else if (asset.kind != AssetKind.SOUND && asset.referenceViews.isNotEmpty()) {
                    Row(Modifier.align(Alignment.BottomStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ClipBadge(stringResource(R.string.p2_views_count, asset.referenceViews.size))
                    }
                }
            }
            Text(
                asset.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val metaParts = listOfNotNull(
                versionNumber?.let { stringResource(R.string.production_panel_version, it) },
                stringResource(R.string.p2_views_count, asset.referenceViews.size).takeIf { versionNumber == null && asset.referenceViews.isNotEmpty() },
                usageCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.production_panel_asset_usage, it, it) },
            )
            val subtitle = if (metaParts.isNotEmpty()) metaParts.joinToString(" · ") // wrap-check: allow 资产卡片底栏版本号与引用数固定单行省略
            else if (asset.kind == AssetKind.CHARACTER && asset.looks.isNotEmpty()) asset.looks.joinToString(" · ") { it.name } // wrap-check: allow 素材网格说明固定单行省略，右侧为状态
            else asset.kind.label()
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            )
        }
    }
}
