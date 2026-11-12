package com.promenar.infinity.ui.production

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
internal fun ProductionManagementTab(
    production: Production, tasks: List<MediaTask>, busy: Boolean, services: ProductionPanelServices,
    onOutput: (OutputFilter) -> Unit, onScene: (String) -> Unit, onAsset: (String) -> Unit,
    onRun: (ProductionUserAction) -> Unit, onQueue: (String, QueueAction) -> Unit,
) {
    var budgetSheet by remember { mutableStateOf(false) }
    val pending = production.overviewItems(tasks)
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        item("experiment") {
            ProductionValueRow(stringResource(R.string.p2_experimental), stringResource(if (production.experimental) R.string.p2_enabled else R.string.p2_disabled), enabled = !busy) {
                onRun(ProductionUserAction.SetExperimental(!production.experimental))
            }
            if (production.experimental) InfinityStatusStrip(kind = InfinityStatusKind.Warning, message = stringResource(R.string.p2_experimental_strip))
        }
        item("pending") {
            ProductionBlock(stringResource(R.string.production_panel_section_pending)) {
                if (pending.isEmpty()) Text(stringResource(R.string.production_panel_nothing_pending))
                pending.forEach { item -> when (item) {
                    is OverviewItem.Review -> ProductionValueRow(stringResource(R.string.p2_review_shots, item.count), onClick = { onOutput(OutputFilter.REVIEW) })
                    is OverviewItem.Approval -> ProductionTaskRow(item.task, services, busy, onQueue)
                    is OverviewItem.Redo -> ProductionValueRow(stringResource(R.string.p2_redo_shots, item.count), onClick = { onOutput(OutputFilter.REDO) })
                    is OverviewItem.SceneLock -> ProductionValueRow(stringResource(R.string.p2_scene_waiting, item.scene.number), item.scene.heading, onClick = { onScene(item.episode.id) })
                    is OverviewItem.ReferenceReview -> ProductionValueRow(item.asset.name, stringResource(R.string.p2_reference_review), onClick = { onAsset(item.asset.id) })
                    is OverviewItem.ChangeReview -> {
                        val request = item.request
                        ProductionValueRow(stringResource(R.string.p2_change_request), "${request.episodeId} · ${request.shotCode}")
                        request.changes.forEach { (field, change) ->
                            Text(stringResource(R.string.p2_change_diff, contentFieldLabel(field), change.oldValue.toString(), change.newValue.toString()), style = MaterialTheme.typography.bodySmall)
                        }
                        request.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        ProductionActionRow(listOf(
                            ProductionButton(stringResource(R.string.p2_reject), InfinityPillTone.NEUTRAL, !busy, "p2_change_reject") { onRun(changeApprovalAction(request, false)) },
                            ProductionButton(stringResource(R.string.p2_approve), InfinityPillTone.PRIMARY, !busy, "p2_change_approve") { onRun(changeApprovalAction(request, true)) },
                        ))
                    }
                } }
            }
        }
        item("stages") {
            ProductionBlock(stringResource(R.string.production_panel_section_stages)) {
                production.stages.forEach { stage ->
                    StageRow(stage, stage.stage == production.currentStage(), busy) { onRun(ProductionUserAction.SetStage(stage.stage, it)) }
                    Text(stageProgress(production, stage), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("tasks") {
            ProductionBlock(stringResource(R.string.p2_tasks)) {
                if (services.queue == null || services.projectSourcePrefix == null) Text(stringResource(R.string.p2_queue_unavailable), style = MaterialTheme.typography.bodySmall)
                val active = tasks.overviewQueueTasks(services.pendingRegistrations)
                if (active.isEmpty()) Text(stringResource(R.string.p2_empty_queue))
                active.forEach { ProductionTaskRow(it, services, busy, onQueue) }
            }
        }
        item("recent") {
            ProductionBlock(stringResource(R.string.p2_recent)) {
                val recent = tasks.recentQueueTasks(services.pendingRegistrations)
                if (recent.isEmpty()) Text(stringResource(R.string.p2_no_recent))
                recent.forEach { ProductionTaskRow(it, services, busy, onQueue) }
            }
        }
        item("budget") {
            ProductionBlock(stringResource(R.string.p2_budget)) {
                val spent = tasks.spentByCurrency().entries.joinToString(" · ") { "${it.key} ${it.value.setScale(2, java.math.RoundingMode.HALF_UP)}" }
                Text(spent.ifBlank { stringResource(R.string.p2_no_recorded_cost) }, style = MaterialTheme.typography.titleMedium)
                production.budgetCents?.let { Text(stringResource(R.string.p2_budget_amount, centsAmount(it))) }
                ProductionCommand(stringResource(if (production.budgetCents == null) R.string.p2_set_limit else R.string.p2_modify), enabled = !busy) { budgetSheet = true }
                Text(stringResource(if (production.budgetCents == null) R.string.p2_approval_each else R.string.p2_auto_budget), style = MaterialTheme.typography.bodySmall)
            }
        }
        production.seasons.forEach { season ->
            item("season-${season.id}") {
                ProductionBlock(season.title ?: stringResource(R.string.p2_season, season.number)) {
                    season.episodes.forEach { episode -> EpisodeProgressRow(episode) { onScene(episode.id) } }
                }
            }
        }
        item("models") {
            ProductionBlock {
                ProductionValueRow(stringResource(R.string.p2_media_models), enabled = services.onOpenMediaModels != null, onClick = services.onOpenMediaModels)
                if (services.onOpenMediaModels == null) Text(stringResource(R.string.p2_models_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (budgetSheet) ProductionOptionSheet(
        title = stringResource(R.string.p2_budget), options = listOf(null to stringResource(R.string.p2_no_limit), 10000L to "¥100.00", 30000L to "¥300.00"),
        selected = production.budgetCents, onDismiss = { budgetSheet = false }, onSelect = { onRun(ProductionUserAction.SetBudget(it)) },
    )
}

internal fun centsAmount(cents: Long) = java.math.BigDecimal.valueOf(cents, 2).toPlainString()

@Composable
private fun stageProgress(production: Production, state: StageState): String {
    val pair = production.stageProgressCounts(state.stage) ?: return state.note ?: state.status.label()
    return stringResource(R.string.p2_progress, pair.first, pair.second)
}

@Composable
internal fun ProductionTaskRow(task: MediaTask, services: ProductionPanelServices, busy: Boolean, onQueue: (String, QueueAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(task.request.sourceRef, style = MaterialTheme.typography.labelMedium)
        Text(services.channelLabel(task.request.channelId), style = MaterialTheme.typography.bodyMedium)
        val estimate: Money? = task.estimate
        estimate?.let { Text(stringResource(R.string.p2_estimate, "${it.currency} ${it.amount.toPlainString()}"), style = MaterialTheme.typography.bodySmall) }
        Text(task.stateLabel(services.pendingRegistrations), style = MaterialTheme.typography.labelSmall)
        if (task.isActiveTask()) {
            val progress = task.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.p2_percent, (progress * 100).toInt()), style = MaterialTheme.typography.labelSmall)
            }
        }
        task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ProductionActionRow(task.allowedQueueActions().map { action ->
            ProductionButton(stringResource(when (action) { QueueAction.APPROVE -> R.string.p2_approve; QueueAction.REJECT -> R.string.p2_reject; QueueAction.RETRY -> R.string.p2_retry }), if (action == QueueAction.APPROVE) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL, !busy && services.queue != null, "p2_task_${action.name}") { onQueue(task.id, action) }
        })
    }
}

@Composable
internal fun OutputFilter.label(): String = stringResource(when (this) {
    OutputFilter.ALL -> R.string.p2_filter_all
    OutputFilter.REVIEW -> R.string.p2_filter_review
    OutputFilter.REDO -> R.string.p2_filter_redo
    OutputFilter.LOCKED -> R.string.p2_filter_locked
    OutputFilter.TODO -> R.string.p2_filter_todo
})

@Composable
internal fun ProductionOutputTab(production: Production, filter: OutputFilter, onFilter: (OutputFilter) -> Unit, services: ProductionPanelServices, tasks: List<MediaTask>, channels: List<MediaChannel>, channelOrder: List<String>, onShot: (String, String) -> Unit, onScene: (String) -> Unit) {
    val rows = production.outputItems(filter)
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        item("filter") { InfinitySegmentedControl(OutputFilter.entries.map { InfinitySegment(it, it.label()) }, selected = filter, onSelect = onFilter) }
        if (rows.isEmpty()) item("empty") { Text(stringResource(R.string.p2_no_output)) }
        rows.forEach { row -> when (row) {
            is OutputItem.UnlockedScene -> item("${row.episode.id}/${row.scene.code}") {
                ProductionBlock { ProductionValueRow("${row.scene.code} · ${row.scene.heading}", stringResource(R.string.p2_lock_before_output), onClick = { onScene(row.episode.id) }) }
            }
            is OutputItem.ShotRow -> item("${row.location.episode.id}/${row.location.shot.code}") {
                val shot = row.location.shot
                val running = services.shotSourceRef?.invoke(production, row.location)?.let { ref -> tasks.lastOrNull { it.request.sourceRef == ref && it.isActiveTask() } }
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { onShot(row.location.episode.id, shot.code) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        ProductionThumbnail(shot.displayTake()?.path, Modifier.width(76.dp).aspectRatio(16f / 9f))
                        if (shot.takes.size > 1) ProductionStatusChip(shot.takes.size.toString(), ProductionTone.NEUTRAL, Modifier.align(Alignment.TopEnd))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${shot.code} · ${shot.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (shot.takes.isNotEmpty()) stringResource(R.string.p2_candidates_channel, shot.takes.size, shot.displayTake()!!.source.service) else listOf(resolvedShotChannel(shot, channels, channelOrder)?.id?.let(services.channelLabel) ?: shot.channelId?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel), shot.mode.label()).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        ProductionStatusChip(if (running != null) running.stateLabel() else shot.status.label(), shot.status.tone())
                        running?.progress?.let { progress ->
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.p2_percent, (progress * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } }
    }
}

/** 第 2 期提供时间线浏览；持久化版本和导出由成片层接入。 */
@Composable
internal fun ProductionCutTab(production: Production, services: ProductionPanelServices, onShot: (String, String) -> Unit) {
    var versionId by rememberSaveable { mutableStateOf("preview") }
    var playing by rememberSaveable { mutableStateOf(false) }
    var clipIndex by rememberSaveable { mutableStateOf(0) }
    val versions = listOf(
        ProductionCutVersion("preview", stringResource(R.string.p2_cut_preview), null, production.cutClips(true)),
        ProductionCutVersion("selected", stringResource(R.string.p2_cut_selected), null, production.cutClips(false)),
    ) + services.cutVersions.filter { it.id != "preview" && it.id != "selected" }.distinctBy { it.id }
    val version = versions.firstOrNull { it.id == versionId } ?: versions.first()
    val current = version.clips.getOrNull(clipIndex.coerceIn(0, (version.clips.size - 1).coerceAtLeast(0)))
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        item("versions") {
            InfinitySegmentedControl(versions.map { InfinitySegment(it.id, it.title) }, selected = version.id, onSelect = { versionId = it; clipIndex = 0; playing = false })
            ProductionCommand(stringResource(R.string.p2_new_cut), enabled = services.onCreateCut != null) { services.onCreateCut?.invoke() }
        }
        item("player") {
            val clip = version.path?.let { ProductionCutClip("", version.id, version.title, version.clips.sumOf { it.seconds }, it) } ?: current
            key(version.id, clip?.episodeId, clip?.shotCode, clip?.path) {
                ProductionCutPlayer(clip, playing, onComplete = {
                    if (version.path != null || clipIndex + 1 >= version.clips.size) playing = false else clipIndex++
                }, onStop = { playing = false })
            }
        }
        item("timeline") {
            ProductionBlock(stringResource(R.string.p2_timeline)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(version.clips, key = { "${it.episodeId}/${it.shotCode}" }) { clip ->
                        Column(Modifier.width((clip.seconds.toFloat() * 18).coerceIn(88f, 220f).dp).clickable(role = Role.Button) { onShot(clip.episodeId, clip.shotCode) }) {
                            ProductionThumbnail(clip.path, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            Text(clip.shotCode, style = MaterialTheme.typography.labelSmall)
                            Text(secondsLabel(clip.seconds), style = MaterialTheme.typography.labelSmall)
                            if (clip.path == null) Text(stringResource(if (version.id == "preview") R.string.p2_text_card else R.string.p2_gap), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                production.shotLocations().forEach { location ->
                    location.shot.dialogue.forEach { dialogue -> Text("${location.shot.code} · ${production.findAsset(dialogue.characterAssetId)?.name ?: dialogue.characterAssetId} · ${dialogue.text} · ${stringResource(if (dialogue.audioSource == DialogueAudioSource.NATIVE) R.string.p2_native else R.string.p2_dubbed)}", style = MaterialTheme.typography.bodySmall) }
                }
                ProductionValueRow(stringResource(R.string.p2_environment_track), production.shotLocations().mapNotNull { it.shot.sound }.distinct().joinToString(" · "))
                ProductionValueRow(stringResource(R.string.p2_music_track), production.assets.filter { it.kind == AssetKind.SOUND }.joinToString(" · ") { it.name })
            }
        }
        item("output") {
            ProductionBlock {
                ProductionValueRow(stringResource(R.string.p2_gaps), version.clips.count { it.path == null }.toString())
                ProductionValueRow(stringResource(R.string.p2_specs), version.specs ?: production.aspectRatio)
                version.path?.let { ProductionValueRow(stringResource(R.string.p2_output_path), it) }
                ProductionCommand(stringResource(if (playing) R.string.p2_stop_sequence else R.string.p2_play_sequence), enabled = version.path != null || version.clips.isNotEmpty()) { playing = !playing; clipIndex = 0 }
                if (playing) ProductionCommand(stringResource(R.string.p2_next_clip), enabled = clipIndex + 1 < version.clips.size) { clipIndex++ }
                ProductionCommand(stringResource(R.string.p2_export), enabled = services.onExportCut != null, primary = true) { services.onExportCut?.invoke(version.id) }
                if (services.onExportCut == null) Text(stringResource(R.string.p2_cut_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        }
        item("packaging") { ProductionBlock(stringResource(R.string.p2_packaging)) { ProductionValueRow(stringResource(R.string.p2_subtitles), stringResource(R.string.p2_subtitles_source)); ProductionValueRow(stringResource(R.string.p2_titles_effects), stringResource(R.string.p2_linux_composite)) } }
    }
}
