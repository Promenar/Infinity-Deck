package com.promenar.infinity.ui.production

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.StageState
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.ui.common.InfinityChoice
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.theme.InfinitySpacing

private val TabContentPadding = PaddingValues(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XLarge)
private val CardShape = RoundedCornerShape(16.dp)

@Composable
private fun ProductionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) { content() }
    }
}

@Composable
internal fun episodeName(episode: Episode): List<String> =
    listOf(stringResource(R.string.production_panel_episode_number, episode.number), episode.title).filter(String::isNotBlank)

// ---------- 总览 ----------

@Composable
internal fun StageRow(stage: StageState, current: Boolean, busy: Boolean, progress: String? = null, inset: Boolean = true, onSet: (StageStatus) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = !busy, role = Role.Button, onClickLabel = stringResource(R.string.production_panel_stage_menu)) { menu = true }
                .padding(horizontal = if (inset) InfinitySpacing.Large else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stage.stage.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                progress?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            ProductionStatusChip(stage.status.label(), stage.status.tone())
        }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            InfinityMetaLine(segments = episodeName(episode), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.production_panel_locked_progress, stats.locked, stats.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        LinearProgressIndicator(
            progress = { stats.progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

// ---------- 剧本 ----------

@Composable
internal fun ProductionScriptTab(production: Production, busy: Boolean, onRun: (ProductionUserAction) -> Unit, onOpenEpisode: (String) -> Unit = {}) {
    val episodes = production.episodes()
    LazyColumn(contentPadding = TabContentPadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        if (production.bible.isNotEmpty()) {
            item("bible") {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    ProductionSectionTitle(stringResource(R.string.production_panel_section_bible))
                    production.bible.forEach { document ->
                        ProductionMediaReference(path = document.path, alt = document.title, asLink = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        items(episodes, key = { it.id }) { episode ->
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    InfinityMetaLine(
                        segments = episodeName(episode),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (episode.scriptLocked) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                val path = episode.scriptPath
                if (path == null) {
                    Text(stringResource(R.string.production_panel_no_script), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ProductionMediaReference(path = path, alt = episode.title, asLink = true, modifier = Modifier.fillMaxWidth())
                }
                if (episode.scenes.isNotEmpty()) {
                    ProductionCard {
                        episode.scenes.forEach { scene -> SceneHeadingLine(scene) { onOpenEpisode(episode.id) } }
                    }
                }
                if (path != null) {
                    InfinityPillButton(
                        text = stringResource(if (episode.scriptLocked) R.string.production_panel_unlock_script else R.string.production_panel_lock_script),
                        onClick = { onRun(ProductionUserAction.LockScript(episode.id, !episode.scriptLocked)) },
                        enabled = !busy,
                        icon = if (episode.scriptLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        compact = true,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneHeadingLine(scene: Scene, onClick: () -> Unit) {
    InfinityMetaLine(
        segments = listOf(stringResource(R.string.production_panel_scene_number, scene.number), scene.heading, stringResource(if (scene.storyboardLocked) R.string.p2_content_locked else R.string.p2_content_editable), stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds }))),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
    )
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
    channels: List<com.promenar.infinity.domain.mediagen.MediaChannel> = emptyList(),
    channelOrder: List<String> = emptyList(),
) {
    var confirming by remember { mutableStateOf<Scene?>(null) }
    val episodes = production.episodes()
    val episode = episodes.firstOrNull { it.id == episodeId } ?: episodes.firstOrNull()
    if (episode == null) {
        EmptyTabText(stringResource(R.string.production_panel_no_shots))
        return
    }
    LazyColumn(contentPadding = TabContentPadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        if (episodes.size > 1) {
            item("episode") { EpisodePicker(episodes, episode, onSelectEpisode) }
        }
        if (episode.scenes.isEmpty()) {
            item("empty") { EmptyTabText(stringResource(R.string.production_panel_no_shots)) }
        }
        episode.scenes.forEach { scene ->
            item(key = "scene-${scene.code}") {
                SceneHeader(scene, busy = busy, onToggleLock = { if (scene.requiresLockConfirmation()) confirming = scene else onRun(ProductionUserAction.LockScene(episode.id, scene.code, !scene.storyboardLocked)) })
            }
            item(key = "shots-${scene.code}") {
                ProductionCard {
                    scene.shots.forEach { shot ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenShot(episode.id, shot.code) }.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${shot.code} · ${shot.title}", style = MaterialTheme.typography.titleSmall)
                            ProductionShotText(shot, production)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                production.importedReferencePaths(shot).take(5).forEach { ProductionThumbnail(it, Modifier.size(28.dp)) }
                            }
                            val channel = resolvedShotChannel(shot, channels, channelOrder)
                            ShotPlanSummary(shot, channel, (shot.channelId ?: channel?.id)?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel))
                        }
                    }
                    if (!scene.storyboardLocked) {
                        androidx.compose.material3.TextButton(onClick = { services.onAddShot?.invoke(episode.id, scene.code) }, enabled = !busy && services.onAddShot != null, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.p2_add_shot), maxLines = 1)
                        }
                        if (services.onAddShot == null) Text(stringResource(R.string.p2_add_shot_unavailable), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(InfinitySpacing.Medium))
                    }
                }
            }
        }
    }
    confirming?.let { scene ->
        com.promenar.infinity.ui.common.InfinityConfirmDialog(
            title = stringResource(R.string.p2_lock_scene), message = stringResource(R.string.p2_lock_scene_message), confirmText = stringResource(R.string.p2_lock_scene),
            onConfirm = { onRun(ProductionUserAction.LockScene(episode.id, scene.code, true)); confirming = null }, onCancel = { confirming = null },
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
                .padding(vertical = InfinitySpacing.Small),
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

@Composable
internal fun SceneHeader(scene: Scene, busy: Boolean, onToggleLock: () -> Unit) {
    val stats = remember(scene) { scene.shotStats() }
    Row(
        Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Column(Modifier.weight(1f)) {
            InfinityMetaLine(
                segments = listOf(stringResource(R.string.production_panel_scene_number, scene.number), scene.heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.production_panel_locked_progress, stats.locked, stats.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleLock, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(
                if (scene.storyboardLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = stringResource(if (scene.storyboardLocked) R.string.production_panel_unlock_storyboard else R.string.production_panel_lock_storyboard),
                tint = if (scene.storyboardLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTabText(text: String) {
    Box(Modifier.fillMaxWidth().padding(InfinitySpacing.XLarge), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var kind by rememberSaveable { mutableStateOf<AssetKind?>(null) }
    var openAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = production.assets.filter { kind == null || it.kind == kind }
    val choices = listOf(InfinityChoice<AssetKind?>(null, stringResource(R.string.production_panel_asset_all))) +
        AssetKind.entries.map { InfinityChoice<AssetKind?>(it, it.label()) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 144.dp),
        contentPadding = TabContentPadding,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        if (production.assets.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyTabText(stringResource(R.string.production_panel_no_assets)) }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ProductionChoiceRow(choices = choices, selected = kind, onSelect = { kind = it })
            }
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
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag(ProductionUiTags.ASSET_CARD),
        onClick = onClick,
    ) {
        Column(Modifier.padding(InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProductionThumbnail(asset.currentPath(), Modifier.fillMaxWidth().aspectRatio(1f), contentDescription = asset.name)
            if (asset.kind == AssetKind.CHARACTER) {
                Text(stringResource(R.string.p2_look_count, asset.looks.size), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(if (asset.referenceVoice?.path != null) R.string.p2_has_voice else R.string.p2_no_voice), style = MaterialTheme.typography.labelSmall)
            }
            Text(asset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    asset.kind.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(asset.status.label(), asset.status.tone())
            }
        }
    }
}
