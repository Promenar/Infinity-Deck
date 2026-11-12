package com.promenar.infinity.ui.production

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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.DialogueAudioSource
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.StageState
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberMediaActionRunner
import com.promenar.infinity.ui.media.rememberWorkspaceMediaResolution
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
internal fun episodeName(episode: Episode): List<String> =
    listOf(stringResource(R.string.production_panel_episode_number, episode.number), episode.title).filter(String::isNotBlank)

// ---------- 总览 ----------

@Composable
internal fun StageRow(stage: StageState, current: Boolean, busy: Boolean, chip: ProductionChipSpec, onSet: (StageStatus) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ProductionRow(
            title = stage.stage.label(),
            icon = stage.stage.icon(),
            chip = chip,
            chevron = false,
            enabled = !busy,
            onClick = { menu = true },
        )
        InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            stageChoices(stage.stage).forEach { status ->
                InfinityMenuItem(
                    text = { Text(status.label()) },
                    selected = status == stage.status,
                    onClick = {
                        menu = false
                        if (status != stage.status) onSet(status)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

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

/** 工作区文件行：点按以外部应用打开文件。 */
@Composable
internal fun ProductionFileRow(path: String, title: String, icon: ImageVector, subtitle: String? = null, value: String? = null, chip: ProductionChipSpec? = null, trailing: (@Composable () -> Unit)? = null) {
    val environment = LocalWorkspaceMedia.current
    val mapped = environment?.let { rememberProductionPath(it, path) as? ProductionPathState.Ready }
    val resolution = if (environment != null && mapped != null) rememberWorkspaceMediaResolution(environment, mapped.path)?.resolution else null
    val runner = environment?.let { rememberMediaActionRunner(it) }
    val available = resolution as? MediaResolution.Available
    ProductionRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        value = value,
        chip = chip,
        chevron = false,
        trailing = trailing,
        enabled = true,
        onClick = if (available != null && runner != null) ({ runner.openWith(available) }) else null,
    )
}

// ---------- 剧本 ----------

@Composable
internal fun ProductionScriptTab(production: Production, busy: Boolean, onRun: (ProductionUserAction) -> Unit, onOpenEpisode: (String) -> Unit = {}) {
    val episodes = production.episodes()
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
                    val chip = if (episode.scriptLocked) ProductionChipSpec(stringResource(R.string.production_panel_stage_locked), ProductionTone.SUCCESS)
                    else ProductionChipSpec(stringResource(R.string.p2_content_editable), ProductionTone.NEUTRAL)
                    val lockButton: @Composable () -> Unit = {
                        IconButton(onClick = { onRun(ProductionUserAction.LockScript(episode.id, !episode.scriptLocked)) }, enabled = !busy, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (episode.scriptLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = stringResource(if (episode.scriptLocked) R.string.production_panel_unlock_script else R.string.production_panel_lock_script),
                                tint = if (episode.scriptLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val path = episode.scriptPath
                    if (path == null) ProductionRow(episodeName(episode).joinToString(" "), icon = Icons.Rounded.Description, subtitle = stringResource(R.string.production_panel_no_script), chevron = false)
                    else ProductionFileRow(path, episodeName(episode).joinToString(" "), Icons.Rounded.Description, subtitle = path, chip = chip, trailing = lockButton)
                }
            }
        }
        episodes.forEach { episode ->
            item("scenes-${episode.id}") {
                ProductionSection(if (episodes.size > 1) "${episodeName(episode).joinToString(" ")} · ${stringResource(R.string.p2_scenes)}" else stringResource(R.string.p2_scenes)) {
                    if (episode.scenes.isEmpty()) ProductionMutedRow(stringResource(R.string.production_panel_no_shots))
                    episode.scenes.forEach { scene ->
                        val chip = when {
                            scene.shots.isEmpty() -> ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                            scene.storyboardLocked -> ProductionChipSpec(stringResource(R.string.p2_board_locked), ProductionTone.SUCCESS)
                            else -> ProductionChipSpec(stringResource(R.string.p2_board_pending), ProductionTone.WARNING)
                        }
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
) {
    var confirming by remember { mutableStateOf<Scene?>(null) }
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
                Column {
                    val meta = listOf(
                        stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds })),
                        stringResource(if (scene.storyboardLocked) R.string.production_panel_stage_locked else R.string.p2_scene_pending),
                    )
                    ProductionSceneHeader(scene, meta, action = if (scene.shots.isEmpty()) null else ({
                        IconButton(
                            onClick = { if (scene.requiresLockConfirmation()) confirming = scene else onRun(ProductionUserAction.LockScene(episode.id, scene.code, !scene.storyboardLocked)) },
                            enabled = !busy,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (scene.storyboardLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = stringResource(if (scene.storyboardLocked) R.string.production_panel_unlock_storyboard else R.string.production_panel_lock_storyboard),
                                tint = if (scene.storyboardLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }))
                    BettboxListGroup {
                        Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
                            if (scene.shots.isEmpty()) ProductionRow(stringResource(R.string.p2_no_shots_split), titleColor = MaterialTheme.colorScheme.onSurfaceVariant, chip = ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL))
                            scene.shots.forEach { shot ->
                                StoryboardShotRow(shot, production, services, channels, channelOrder) { onOpenShot(episode.id, shot.code) }
                            }
                            if (!scene.storyboardLocked && services.onAddShot != null) {
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
    }
    confirming?.let { scene ->
        com.promenar.infinity.ui.common.InfinityConfirmDialog(
            title = stringResource(R.string.p2_lock_scene), message = stringResource(R.string.p2_lock_scene_message), confirmText = stringResource(R.string.p2_lock_scene),
            onConfirm = { episode?.let { onRun(ProductionUserAction.LockScene(it.id, scene.code, true)) }; confirming = null }, onCancel = { confirming = null },
        )
    }
}

/** 分镜里的文字镜头：编号、标题、景别机位运镜时长、画面与表演、对白、声音转场，末行是生成规划摘要。 */
@Composable
private fun StoryboardShotRow(shot: Shot, production: Production, services: ProductionPanelServices, channels: List<MediaChannel>, channelOrder: List<String>, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp).testTag(ProductionUiTags.SHOT_ROW),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(shot.code, style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.width(64.dp).padding(top = 2.dp), maxLines = 2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(shot.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            InfinityMetaLine(listOfNotNull(shot.size, shot.camera, shot.cameraMovement, secondsLabel(shot.durationSeconds)).filter(String::isNotBlank), style = MaterialTheme.typography.bodySmall, color = muted)
            listOfNotNull(shot.visual, shot.performance).filter(String::isNotBlank).joinToString("").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            shot.dialogue.forEach { line -> DialogueLine(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId, line.text, line.tone) }
            listOfNotNull(shot.sound, shot.transition).filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let { parts ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(14.dp), muted)
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            PlanLine(shot, production, services, channels, channelOrder)
        }
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
            tone?.takeIf(String::isNotBlank)?.let { withStyle(SpanStyle(color = muted)) { append(it) } }
        },
        style = textStyle,
        modifier = modifier,
    )
}

/** 生成规划摘要：导入资产小图、参考语音、通道、模式与入参方式。 */
@Composable
private fun PlanLine(shot: Shot, production: Production, services: ProductionPanelServices, channels: List<MediaChannel>, channelOrder: List<String>) {
    val channel = resolvedShotChannel(shot, channels, channelOrder)
    val channelName = (shot.channelId ?: channel?.id)?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel)
    val packing = if (shot.mode == ShotMode.REFERENCE && channel != null) stringResource(
        if (channel.capabilities.characterReferenceMode == CharacterReferenceMode.COMPOSITE) R.string.p2_composite else R.string.p2_separate,
    ) else null
    val paths = production.importedReferencePaths(shot).take(5)
    val voice = shot.dialogue.any { it.audioSource == DialogueAudioSource.NATIVE }
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        paths.forEach { ProductionThumbnail(it, Modifier.size(20.dp)) }
        if (voice) Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(13.dp), MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (paths.isNotEmpty() || voice) Spacer(Modifier.width(3.dp))
        Text(
            planSummary(channelName, shot.mode.label(), packing, shot.promptUserEdited, stringResource(R.string.p2_edited)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
) {
    val initialKind = AssetKind.entries.firstOrNull { kind -> production.assets.any { it.kind == kind } } ?: AssetKind.CHARACTER
    var kind by rememberSaveable(production.id) { mutableStateOf(initialKind) }
    var openAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = production.assets.filter { it.kind == kind }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = ProductionPagePadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("kind", span = { GridItemSpan(maxLineSpan) }) {
            InfinitySegmentedControl(AssetKind.entries.map { InfinitySegment(it, it.label()) }, selected = kind, onSelect = { kind = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        }
        if (assets.isEmpty()) {
            item("empty", span = { GridItemSpan(maxLineSpan) }) { ProductionSection(null) { ProductionMutedRow(stringResource(R.string.production_panel_no_assets)) } }
        }
        items(assets, key = { it.id }) { asset -> AssetCard(asset, onClick = { if (asset.kind == AssetKind.CHARACTER) onOpenCharacter(asset.id) else openAssetId = asset.id }) }
    }
    openAssetId?.let { id ->
        val asset = production.findAsset(id)
        if (asset == null) {
            // 资产被智能体移除时关闭详情。
            LaunchedEffect(id) { openAssetId = null }
        } else {
            ProductionAssetSheet(
                asset = asset,
                usage = production.shotsUsingAsset(asset.id),
                busy = busy,
                onDismiss = { openAssetId = null },
                onRun = onRun,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun AssetCard(asset: Asset, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(ProductionUiTags.ASSET_CARD),
        onClick = onClick,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                val path = asset.currentPath() ?: asset.looks.firstOrNull()?.slots?.firstOrNull { !it.path.isNullOrBlank() }?.path
                if (asset.kind == AssetKind.SOUND && path == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(44.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                } else {
                    ProductionThumbnail(path, Modifier.fillMaxSize(), contentDescription = asset.name)
                }
                if (asset.kind == AssetKind.CHARACTER) Row(Modifier.align(Alignment.BottomStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (asset.looks.isNotEmpty()) ClipBadge(stringResource(R.string.p2_look_count, asset.looks.size))
                    if (asset.referenceVoice?.path != null) ClipBadge(stringResource(R.string.p2_voice_badge))
                }
            }
            Text(asset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (asset.kind == AssetKind.CHARACTER && asset.looks.isNotEmpty()) asset.looks.joinToString(" · ") { it.name } else asset.kind.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(asset.status.label(), asset.status.tone())
            }
        }
    }
}
