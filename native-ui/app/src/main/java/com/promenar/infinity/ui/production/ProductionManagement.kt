package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

// ---------- 总览 ----------

@Composable
internal fun ProductionManagementTab(
    production: Production, tasks: List<MediaTask>, busy: Boolean, services: ProductionPanelServices,
    onScene: (String) -> Unit, onAsset: (String) -> Unit,
    onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String?, onCancelTask: (String) -> Unit,
    channels: List<MediaChannel> = emptyList(),
    costStats: ProductionCostStats = productionCostStats(production, tasks),
    onShot: (String, String) -> Unit = { _, _ -> },
    onOpenTab: (ProductionTab) -> Unit = {},
    onOpenAssets: (AssetKind?) -> Unit = {},
    onOpenPrevis: () -> Unit = { onOpenTab(ProductionTab.CUT) },
) {
    val pendingChanges = production.pendingChangeRequests()
    val active = tasks.overviewQueueTasks(services.pendingRegistrations)
    val recent = tasks.recentQueueTasks(services.pendingRegistrations)
    val progress = production.progressItems()
    val now = rememberProductionNow(progress.any { it is ProgressItem.Run })
    val stats = remember(production) { production.shotStats() }
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
        if (production.experimental) item("experiment") { ProductionTrialStrip() }
        if (pendingChanges.isNotEmpty()) item("changes") {
            ProductionSection(stringResource(R.string.p2_change_request)) {
                pendingChanges.forEach { request ->
                    ChangeRequestRow(request, production, busy, onRun, onSubmit)
                }
            }
        }
        item("stages") {
            ProductionSection(stringResource(R.string.p2_workflow_section)) {
                production.visibleStages().forEach { stage ->
                    WorkflowStageRow(
                        stage = stage,
                        production = production,
                        busy = busy,
                        onNavigate = {
                            if (stage.stage == ProductionStage.VISUAL_DEV) {
                                val firstVisualKind = production.visualAssets().firstOrNull()?.kind ?: AssetKind.CHARACTER
                                onOpenAssets(firstVisualKind)
                            } else if (stage.stage == ProductionStage.SOUND) {
                                onOpenAssets(AssetKind.SOUND)
                            } else {
                                onOpenTab(workflowStageTarget(stage.stage))
                            }
                        },
                        onToggleSkipAnimatic = { skipped ->
                            onRun(ProductionUserAction.SetStage(ProductionStage.ANIMATIC, if (skipped) StageStatus.SKIPPED else StageStatus.NOT_STARTED))
                        },
                        onOpenPrevis = onOpenPrevis,
                    )
                }
            }
        }
        item("tasks") {
            ProductionSection(stringResource(R.string.p2_tasks), trailing = stringResource(R.string.p2_tasks_active, active.count { it.isActiveTask() } + progress.size)) {
                if (services.queue == null || services.projectSourcePrefix == null) ProductionMutedRow(stringResource(R.string.p2_queue_unavailable))
                else if (active.isEmpty() && progress.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_empty_queue))
                progress.forEach { ProductionProgressRow(it, production, now, busy, onRun, onShot) }
                active.forEach { ProductionTaskRow(it, services, busy, onCancelTask, production.taskTitleSegments(it.request.sourceRef), channels) }
            }
        }
        if (recent.isNotEmpty()) item("recent") {
            ProductionSection(stringResource(R.string.p2_recent)) {
                recent.forEach { ProductionTaskRow(it, services, busy, onCancelTask, production.taskTitleSegments(it.request.sourceRef), channels) }
            }
        }
        item("cost") { ProductionCostSection(production, costStats, onShot, onAsset) }
        if (production.unregisteredFiles.isNotEmpty()) item("unregistered") { ProductionUnregisteredSection(production) }
        if (production.reviewRounds.isNotEmpty()) item("rounds") { ProductionReviewRounds(production.reviewRounds) }
        if (production.showEpisodeNumbers()) {
            production.seasons.filter { it.episodes.isNotEmpty() }.forEach { season ->
                item("season-${season.id}") {
                    ProductionSection(season.title ?: stringResource(R.string.p2_season, season.number)) {
                        season.episodes.forEach { episode -> EpisodeProgressRow(episode) { onScene(episode.id) } }
                    }
                }
            }
        } else if (stats.total > 0) {
            item("project-progress") {
                ProductionSection(stringResource(R.string.p2_project_progress)) {
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenTab(ProductionTab.STORYBOARD) }
                            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(production.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.p2_stage_shots_compact, stats.locked, stats.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        ProductionProgressBar(stats.progress)
                    }
                }
            }
        }
        item("models") {
            ProductionSection(null) {
                val counts = MediaGroup.entries.associateWith { group -> channels.count { it.group == group } }
                ProductionRow(
                    stringResource(R.string.p2_media_models),
                    icon = Icons.Rounded.Hub,
                    value = if (channels.isEmpty()) null else stringResource(R.string.p2_media_counts, counts.getValue(MediaGroup.IMAGE), counts.getValue(MediaGroup.VIDEO), counts.getValue(MediaGroup.AUDIO)),
                    enabled = services.onOpenMediaModels != null,
                    onClick = services.onOpenMediaModels ?: {},
                )
            }
        }
    }
}

@Composable
private fun ChangeRequestRow(
    request: ShotChangeRequest,
    production: Production,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    val target = ReviewTarget.change(request.id)
    ProductionRow(
        stringResource(R.string.p2_change_request),
        icon = Icons.Rounded.EditNote,
        subtitle = "${request.episodeId} · ${request.shotCode}", // wrap-check: allow 改动请求副标题固定单行横滑
        chip = production.reviewChipSpec(target),
        chevron = false,
    )
    Column(
        Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal + InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap, end = InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        request.changes.forEach { (field, change) ->
            Text(stringResource(R.string.p2_change_diff, contentFieldLabel(field), change.oldValue.toString(), change.newValue.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        request.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ProductionReviewNotes(production, target)
        ProductionReviewActions(production, target, listOf(request.shotCode, stringResource(R.string.p2_change_request)), busy, onRun, onSubmit, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WorkflowStageRow(
    stage: StageState,
    production: Production,
    busy: Boolean,
    onNavigate: () -> Unit,
    onToggleSkipAnimatic: (Boolean) -> Unit,
    onOpenPrevis: () -> Unit = onNavigate,
) {
    val episodes = production.episodes()
    val stats = production.shotStats()
    when (stage.stage) {
        ProductionStage.ANIMATIC -> {
            val previsEpisodes = episodes.filter { !it.previsPath.isNullOrBlank() }
            if (previsEpisodes.isNotEmpty()) {
                val previsShotCount = previsEpisodes.sumOf { ep -> ep.scenes.sumOf { sc -> sc.shots.size } }
                val totalCount = stats.total.coerceAtLeast(previsShotCount)
                ProductionRow(
                    title = stage.stage.label(),
                    icon = stage.stage.icon(),
                    subtitleSegments = listOf(
                        stringResource(R.string.p3_previs_overview_subtitle, previsShotCount, totalCount),
                        stringResource(R.string.p3_previs_clay),
                    ),
                    chip = ProductionChipSpec(stringResource(R.string.p3_previs_badge), ProductionTone.INFO),
                    enabled = !busy,
                    onClick = onOpenPrevis,
                )
            } else {
                val done = production.shotLocations().count { it.shot.previewPath != null }
                val skipped = stage.status == StageStatus.SKIPPED
                val subtitle = if (stats.total > 0) stringResource(R.string.p2_stage_animatic_optional, done, stats.total) else stringResource(R.string.p2_optional_stage)
                ProductionRow(
                    title = stage.stage.label(),
                    icon = stage.stage.icon(),
                    subtitle = subtitle,
                    chevron = false,
                    enabled = !busy,
                    role = Role.Switch,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.p2_skip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            InfinitySwitch(checked = skipped, onCheckedChange = null, enabled = !busy)
                        }
                    },
                    onClick = { onToggleSkipAnimatic(!skipped) },
                )
            }
        }
        ProductionStage.SCRIPT -> {
            val done = episodes.count { it.scriptLocked }
            val subtitle = when {
                episodes.isEmpty() -> stringResource(R.string.production_panel_no_script)
                episodes.size == 1 -> episodes.single().scriptPath?.substringAfterLast('/') ?: stringResource(R.string.production_panel_no_script)
                else -> pluralStringResource(R.plurals.production_panel_episode_count, episodes.size, episodes.size)
            }
            val chip = when {
                episodes.isEmpty() -> ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                episodes.size == 1 -> episodes.single().let { ep ->
                    production.reviewChipSpec(ReviewTarget.script(ep.id), settled = ep.scriptLocked)
                        ?: ProductionChipSpec(stringResource(R.string.production_review_pending), ProductionTone.WARNING)
                }
                done == episodes.size -> ProductionChipSpec(stringResource(R.string.production_review_approved), ProductionTone.SUCCESS)
                else -> ProductionChipSpec(stringResource(R.string.p2_stage_episodes, done, episodes.size), ProductionTone.WARNING)
            }
            ProductionRow(title = stage.stage.label(), icon = stage.stage.icon(), subtitle = subtitle, chip = chip, enabled = !busy, onClick = onNavigate)
        }
        ProductionStage.VISUAL_DEV -> {
            val visual = production.visualAssets()
            val breakdown = listOf(AssetKind.CHARACTER, AssetKind.LOCATION, AssetKind.PROP, AssetKind.STYLE).mapNotNull { kind ->
                visual.count { it.kind == kind }.takeIf { it > 0 }?.let { "${kind.label()} $it" }
            }.joinToString(" · ") // wrap-check: allow 资产各品类统计摘要，受单行省略限制
            val done = visual.count { it.status == AssetStatus.LOCKED }
            val review = visual.count { it.status == AssetStatus.REVIEW }
            val redo = visual.count { it.status == AssetStatus.REDO }
            val chip = when {
                visual.isEmpty() -> ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                done == visual.size -> ProductionChipSpec(stringResource(R.string.p2_stage_assets, done, visual.size), ProductionTone.SUCCESS)
                redo > 0 -> ProductionChipSpec(stringResource(R.string.p2_stage_count_redo, done, visual.size, redo), ProductionTone.ERROR)
                review > 0 -> ProductionChipSpec(stringResource(R.string.p2_stage_count_pending, done, visual.size, review), ProductionTone.WARNING)
                else -> ProductionChipSpec(stringResource(R.string.p2_stage_assets, done, visual.size), ProductionTone.INFO)
            }
            ProductionRow(
                title = stage.stage.label(),
                icon = stage.stage.icon(),
                subtitle = breakdown.ifBlank { stringResource(R.string.p2_stage_no_visual_assets) },
                chip = chip,
                enabled = !busy,
                onClick = onNavigate,
            )
        }
        ProductionStage.STORYBOARD -> {
            val scenes = episodes.flatMap { it.scenes }
            val lockedScenes = scenes.count { it.storyboardLocked }
            val totalSeconds = formatShotSeconds(production.shotLocations().sumOf { it.shot.durationSeconds })
            val subtitle = if (scenes.isEmpty()) stringResource(R.string.production_panel_no_shots)
            else stringResource(R.string.p2_workflow_storyboard_summary, scenes.size, stats.total, totalSeconds)
            val pending = scenes.size - lockedScenes
            val chip = when {
                scenes.isEmpty() -> ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                pending == 0 -> ProductionChipSpec(stringResource(R.string.p2_stage_scenes, lockedScenes, scenes.size), ProductionTone.SUCCESS)
                else -> ProductionChipSpec(stringResource(R.string.p2_stage_scenes_pending, pending), ProductionTone.WARNING)
            }
            ProductionRow(title = stage.stage.label(), icon = stage.stage.icon(), subtitle = subtitle, chip = chip, enabled = !busy, onClick = onNavigate)
        }
        ProductionStage.GENERATION -> {
            val scenes = episodes.flatMap { it.scenes }
            val unlockedAll = !production.experimental && scenes.isNotEmpty() && scenes.none { it.storyboardLocked } && stats.review == 0 && stats.locked == 0 && stats.generating == 0
            val subtitle = when {
                unlockedAll -> stringResource(R.string.p2_stage_unlock_after_board)
                stats.generating > 0 -> stringResource(R.string.p2_tasks_active, stats.generating)
                stats.total > 0 -> stringResource(R.string.p2_stage_shots, stats.locked, stats.total)
                else -> stringResource(R.string.production_panel_no_shots)
            }
            val chip = when {
                stats.total == 0 -> ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
                stats.locked == stats.total -> ProductionChipSpec(stringResource(R.string.production_review_approved), ProductionTone.SUCCESS)
                stats.redo > 0 -> ProductionChipSpec(stringResource(R.string.p2_stage_count_redo, stats.locked, stats.total, stats.redo), ProductionTone.ERROR)
                stats.review > 0 -> ProductionChipSpec(stringResource(R.string.p2_stage_count_pending, stats.locked, stats.total, stats.review), ProductionTone.WARNING)
                stats.generating > 0 -> ProductionChipSpec(stringResource(R.string.production_panel_shot_generating), ProductionTone.INFO)
                else -> ProductionChipSpec(stringResource(R.string.p2_stage_shots_compact, stats.locked, stats.total), ProductionTone.NEUTRAL)
            }
            ProductionRow(title = stage.stage.label(), icon = stage.stage.icon(), subtitle = subtitle, chip = chip, enabled = !busy, onClick = onNavigate)
        }
        ProductionStage.SOUND -> {
            val dialogueCount = production.shotLocations().sumOf { it.shot.dialogue.size }
            val sounds = production.assets.filter { it.kind == AssetKind.SOUND }
            val subtitle = stringResource(R.string.p2_stage_sound_summary, dialogueCount, sounds.size)
            val chip = when {
                sounds.any { it.status == AssetStatus.REVIEW } -> ProductionChipSpec(stringResource(R.string.production_review_pending), ProductionTone.WARNING)
                sounds.isNotEmpty() && sounds.all { it.status == AssetStatus.LOCKED } -> ProductionChipSpec(stringResource(R.string.production_review_approved), ProductionTone.SUCCESS)
                dialogueCount > 0 && sounds.isEmpty() -> ProductionChipSpec(stringResource(R.string.p2_stage_pending_fill), ProductionTone.NEUTRAL)
                else -> ProductionChipSpec(stage.status.label(), stage.status.tone())
            }
            ProductionRow(title = stage.stage.label(), icon = stage.stage.icon(), subtitle = subtitle, chip = chip, enabled = !busy, onClick = onNavigate)
        }
        ProductionStage.EDIT -> {
            val cuts = production.cuts
            val subtitle = if (cuts.isEmpty()) stringResource(R.string.p2_stage_no_cuts) else stringResource(R.string.p3_cut_version, cuts.maxOf { it.number })
            val chip = if (cuts.isEmpty()) ProductionChipSpec(stringResource(R.string.production_panel_stage_not_started), ProductionTone.NEUTRAL)
            else ProductionChipSpec(stringResource(R.string.p2_stage_cuts, cuts.size), ProductionTone.INFO)
            ProductionRow(title = stage.stage.label(), icon = stage.stage.icon(), subtitle = subtitle, chip = chip, enabled = !busy, onClick = onNavigate)
        }
    }
}

/** 成本：各阶段生成次数，以及达到提醒阈值的镜头与资产。只记录次数，费用在用量专区。 */
@Composable
private fun ProductionCostSection(production: Production, stats: ProductionCostStats, onShot: (String, String) -> Unit, onAsset: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        ProductionSection(stringResource(R.string.production_cost_section), trailing = stringResource(R.string.production_cost_unit)) {
            val stages = ProductionStage.entries.mapNotNull { stage -> stats.byStage[stage]?.takeIf { it.total > 0 }?.let { stage to it } }
            if (stages.isEmpty()) ProductionMutedRow(stringResource(R.string.production_cost_none))
            stages.forEach { (stage, count) -> ProductionRow(stage.label(), icon = stage.icon(), value = generationTimes(count.total), subtitle = count.failed.takeIf { it > 0 }?.let { stringResource(R.string.production_cost_failed, it) }, chevron = false) }
        }
        if (stats.alertShots.isNotEmpty() || stats.alertAssets.isNotEmpty()) ProductionSection(null) {
            stats.alertShots.forEach { (key, count) ->
                val shot = production.findShot(key.episodeId, key.shotCode)?.shot
                ProductionRow(
                    listOfNotNull(key.shotCode, shot?.title).joinToString(" "),
                    chip = ProductionChipSpec(generationTimes(count.total), ProductionTone.WARNING),
                    onClick = { onShot(key.episodeId, key.shotCode) },
                )
            }
            stats.alertAssets.forEach { (assetId, count) ->
                ProductionRow(
                    production.findAsset(assetId)?.name ?: assetId,
                    chip = ProductionChipSpec(generationTimes(count.total), ProductionTone.WARNING),
                    onClick = { onAsset(assetId) },
                )
            }
        }
    }
}

/** 生成次数：“N 次”，有失败时追加失败次数。 */
/** 次数达到提醒阈值：在总览与对象上用警示色标出。 */
internal fun costAlert(count: GenerationCount): Boolean = count.total >= PRODUCTION_COST_ALERT_COUNT

@Composable
internal fun generationTimes(total: Int): String = pluralStringResource(R.plurals.production_cost_times, total, total)

@Composable
internal fun generatedTimes(total: Int): String = pluralStringResource(R.plurals.production_cost_generated, total, total)

/** 审核记录：已发送的轮次，只读。 */
@Composable
private fun ProductionReviewRounds(rounds: List<ReviewRound>) {
    val context = LocalContext.current
    ProductionSection(stringResource(R.string.production_review_rounds)) {
        // 最新一轮在前。
        rounds.sortedByDescending { it.number }.take(REVIEW_ROUNDS_SHOWN).forEach { round ->
            ProductionRow(
                stringResource(R.string.production_review_round, round.number),
                subtitle = pluralStringResource(R.plurals.production_review_round_items, round.entries.size, round.entries.size),
                value = android.text.format.DateUtils.formatDateTime(context, round.sentAt,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH),
                chevron = false,
            )
        }
    }
}

private const val REVIEW_ROUNDS_SHOWN = 10

/** 试验项目提示条：页内置顶，带烧瓶图标。 */
@Composable
internal fun ProductionTrialStrip(modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    Surface(shape = RoundedCornerShape(14.dp), color = domain.warningContainer, contentColor = domain.onWarningContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Science, null, Modifier.size(18.dp))
            Text(stringResource(R.string.p2_experimental_strip), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 分组之间的间距：标题与上一组之间留出呼吸。 */
internal val ProductionSectionGap = 20.dp

internal fun ProductionStage.icon(): ImageVector = when (this) {
    ProductionStage.SCRIPT -> Icons.Rounded.Description
    ProductionStage.VISUAL_DEV -> Icons.Rounded.Palette
    ProductionStage.STORYBOARD -> Icons.AutoMirrored.Rounded.ViewList
    ProductionStage.ANIMATIC -> Icons.Rounded.Slideshow
    ProductionStage.GENERATION -> Icons.Rounded.Movie
    ProductionStage.SOUND -> Icons.Rounded.GraphicEq
    ProductionStage.EDIT -> Icons.Rounded.ContentCut
}

/** 阶段行右侧的进度标签：已定或已跳过直接显示状态，其余按阶段写出具体进度。 */
@Composable
internal fun stageChip(production: Production, state: StageState, services: ProductionPanelServices): ProductionChipSpec {
    if (state.status == StageStatus.LOCKED || state.status == StageStatus.SKIPPED) return ProductionChipSpec(state.status.label(), state.status.tone())
    val visual = production.visualAssets()
    val (done, total) = when (state.stage) {
        ProductionStage.VISUAL_DEV -> visual.count { it.status == AssetStatus.LOCKED } to visual.size
        ProductionStage.SOUND -> {
            val dialogue = production.shotLocations().sumOf { it.shot.dialogue.size }
            val sounds = production.assets.count { it.kind == AssetKind.SOUND }
            if (dialogue + sounds == 0) return ProductionChipSpec(state.note ?: state.status.label(), state.status.tone())
            return ProductionChipSpec(stringResource(R.string.p2_stage_sound, dialogue, sounds), if (state.status == StageStatus.IN_PROGRESS) ProductionTone.INFO else ProductionTone.NEUTRAL)
        }
        ProductionStage.EDIT -> {
            val versions = production.cuts.size
            return if (versions > 0) ProductionChipSpec(stringResource(R.string.p2_stage_cuts, versions), ProductionTone.INFO)
            else ProductionChipSpec(state.note ?: state.status.label(), state.status.tone())
        }
        else -> production.stageProgressCounts(state.stage) ?: (0 to 0)
    }
    if (total == 0) return ProductionChipSpec(state.note ?: state.status.label(), state.status.tone())
    val text = when (state.stage) {
        ProductionStage.SCRIPT -> stringResource(R.string.p2_stage_episodes, done, total)
        ProductionStage.VISUAL_DEV -> stringResource(R.string.p2_stage_assets, done, total)
        ProductionStage.STORYBOARD -> stringResource(R.string.p2_stage_scenes, done, total)
        ProductionStage.ANIMATIC -> stringResource(R.string.p2_stage_animatic, done, total)
        else -> stringResource(R.string.p2_stage_shots, done, total)
    }
    val tone = when {
        done == total -> ProductionTone.SUCCESS
        done > 0 || state.status == StageStatus.IN_PROGRESS -> ProductionTone.INFO
        else -> ProductionTone.NEUTRAL
    }
    return ProductionChipSpec(text, tone)
}

/** 任务行标题：镜头编号与标题，配音追加对白序号；无法解析时由调用方回退到原始引用。 */
internal fun Production.taskTitleSegments(sourceRef: String): List<String> {
    val ref = ProductionSourceRef.parse(sourceRef) ?: return emptyList()
    if (ref.productionId != id) return emptyList()
    val shot = shotLocations().firstOrNull { it.episode.id == ref.episodeId && it.shot.code == ref.shotCode }?.shot
    return listOfNotNull(shot?.code ?: ref.shotCode, shot?.title, ref.dialogueIndex?.let { "#${it + 1}" })
}

internal fun MediaTask.tone(pendingRegistrations: Set<String>): ProductionTone = when {
    awaitingRegistration(pendingRegistrations) -> ProductionTone.INFO
    else -> when (state) {
        MediaTaskState.AWAITING_APPROVAL, MediaTaskState.RESULT_UNKNOWN -> ProductionTone.WARNING
        MediaTaskState.QUEUED, MediaTaskState.SUBMITTING, MediaTaskState.CANCELED -> ProductionTone.NEUTRAL
        MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING -> ProductionTone.INFO
        MediaTaskState.COMPLETED -> ProductionTone.SUCCESS
        MediaTaskState.FAILED, MediaTaskState.DOWNLOAD_FAILED -> ProductionTone.ERROR
    }
}

@Composable
private fun MediaTask.kindLabel(channels: List<MediaChannel>): String = stringResource(
    when (request.mode) {
        GenerationMode.SPEECH -> R.string.p2_task_kind_speech
        GenerationMode.MUSIC -> R.string.p2_task_kind_music
        else -> when (channels.firstOrNull { it.id == request.channelId }?.group) {
            MediaGroup.IMAGE -> R.string.p2_task_kind_image
            MediaGroup.AUDIO -> R.string.p2_task_kind_audio
            else -> R.string.p2_task_kind_video
        }
    },
)

/** 任务行：对象、种类、通道与错误，右侧状态；进行中的任务只有“取消”。 */
@Composable
internal fun ProductionTaskRow(task: MediaTask, services: ProductionPanelServices, busy: Boolean, onCancel: (String) -> Unit, titleSegments: List<String> = emptyList(), channels: List<MediaChannel> = emptyList()) {
    val running = task.isActiveTask() && task.state != MediaTaskState.QUEUED
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = titleSegments.ifEmpty { listOf(task.request.sourceRef) } + task.kindLabel(channels)
                Text(
                    title.joinToString(" · "), // wrap-check: allow 任务标题固定单行省略，右侧为状态
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InfinityMetaLine(
                    segments = services.channelLabelSegments(task.request.channelId) + listOfNotNull(task.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            val percent = task.progress?.takeIf { running }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            ProductionStatusChip(task.stateLabel(services.pendingRegistrations) + percent, task.tone(services.pendingRegistrations))
        }
        if (running) ProductionProgressBar(task.progress)
        if (task.canCancel()) ProductionActionRow(listOf(
            ProductionButton(stringResource(R.string.production_task_cancel), InfinityPillTone.NEUTRAL, !busy && services.queue != null, ProductionUiTags.TASK_CANCEL) { onCancel(task.id) },
        ))
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

// ---------- 出片 ----------

@Composable
internal fun ProductionOutputTab(
    production: Production,
    filter: OutputFilter,
    onFilter: (OutputFilter) -> Unit,
    services: ProductionPanelServices,
    tasks: List<MediaTask>,
    channels: List<MediaChannel>,
    channelOrder: List<String>,
    onShot: (String, String) -> Unit,
    onScene: (String) -> Unit,
    busy: Boolean = false,
    onRun: (ProductionUserAction) -> Unit = {},
    onSubmit: suspend (ProductionUserAction) -> String? = { null },
) {
    val rows = production.outputItems(filter)
    val multiEpisode = production.showEpisodeNumbers()
    // 按场分组：同一场的镜头放进一张卡片，未锁定的场只占一行。
    val groups = rows.fold(mutableListOf<Pair<Pair<Episode, Scene>, MutableList<OutputItem>>>()) { acc, row ->
        val key = when (row) {
            is OutputItem.UnlockedScene -> row.episode to row.scene
            is OutputItem.ShotRow -> row.location.episode to row.location.scene
        }
        if (acc.lastOrNull()?.first?.let { it.first.id == key.first.id && it.second.code == key.second.code } == true) acc.last().second += row
        else acc += key to mutableListOf(row)
        acc
    }
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
        item("filter") { InfinitySegmentedControl(OutputFilter.entries.map { InfinitySegment(it, it.label()) }, selected = filter, onSelect = onFilter, modifier = Modifier.fillMaxWidth()) }
        if (groups.isEmpty()) item("empty") { ProductionSection(null) { ProductionMutedRow(stringResource(R.string.p2_no_output)) } }
        groups.forEach { (key, items) ->
            val (episode, scene) = key
            item("${episode.id}/${scene.code}") {
                val unlocked = items.any { it is OutputItem.UnlockedScene }
                val sceneTarget = ReviewTarget.scene(episode.id, scene.code)
                val chip = production.reviewChipSpec(sceneTarget, settled = scene.storyboardLocked)
                val sceneLabel = listOf(stringResource(R.string.production_panel_scene_number, scene.number), scene.heading)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProductionSceneHeader(
                        scene = scene,
                        meta = listOfNotNull(
                            episode.takeIf { multiEpisode }?.let { episodeName(it).joinToString(" ") },
                            stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds })),
                        ),
                        statusChip = chip,
                        action = if (unlocked && scene.shots.isNotEmpty()) ({
                            ProductionReviewActions(
                                production = production,
                                target = sceneTarget,
                                label = sceneLabel,
                                busy = busy,
                                onRun = onRun,
                                onSubmit = onSubmit,
                                settled = scene.storyboardLocked,
                                compact = true,
                            )
                        }) else null,
                    )
                    ProductionReviewNotes(production, sceneTarget)
                    BettboxListGroup {
                        Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
                            items.forEach { row ->
                                when (row) {
                                    is OutputItem.UnlockedScene -> ProductionRow(stringResource(R.string.p2_lock_then_output), titleColor = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onScene(row.episode.id) })
                                    is OutputItem.ShotRow -> OutputShotRow(production, row.location, services, tasks, channels, channelOrder, onShot)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputShotRow(production: Production, location: ShotLocation, services: ProductionPanelServices, tasks: List<MediaTask>, channels: List<MediaChannel>, channelOrder: List<String>, onShot: (String, String) -> Unit) {
    val shot = location.shot
    val running = services.shotSourceRef?.invoke(production, location)?.let { ref -> tasks.lastOrNull { it.request.sourceRef == ref && it.isActiveTask() } }
    val take = shot.displayTake()
    val chip = if (running != null) ProductionChipSpec(running.stateLabel() + (running.progress?.let { " ${(it * 100).toInt()}%" } ?: ""), ProductionTone.INFO) else ProductionChipSpec(shot.status.label(), shot.status.tone())
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onShot(location.episode.id, shot.code) }
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(64.dp).aspectRatio(16f / 9f)) {
            ProductionThumbnail(take?.path ?: shot.previewPath, Modifier.fillMaxSize().then(if (take == null) Modifier.alpha(0.5f) else Modifier))
            if (shot.takes.size > 1) ClipBadge(shot.takes.size.toString(), Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(shot.code, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(" ${shot.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val detail = if (take != null) {
                val channel = services.channelLabelSegments(take.source.service).formatChannelLabel()
                stringResource(R.string.p2_candidates_channel, shot.takes.size, channel)
            } else {
                val channelId = resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)?.id ?: shot.channelId
                val channel = channelId?.let(services.channelLabelSegments) ?: listOf(stringResource(R.string.p2_default_channel))
                (channel + shot.mode.label()).joinToString(" · ") // wrap-check: allow 产出列表副标题固定单行省略
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProductionStatusChip(chip.text, chip.tone)
    }
}

/** 结构化场头卡片：场号胶囊、状态、操作按钮与独立场景名行。 */
@Composable
internal fun ProductionSceneHeader(
    scene: Scene,
    meta: List<String>,
    modifier: Modifier = Modifier,
    statusChip: ProductionChipSpec? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = scheme.primaryContainer) {
                        Text(
                            "${stringResource(R.string.production_panel_scene_number, scene.number)} · ${scene.code}", // wrap-check: allow 场号胶囊固定单行
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    statusChip?.let { ProductionStatusChip(it.text, it.tone) }
                }
                action?.invoke(this)
            }
            Text(
                scene.heading,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                InfinityMetaLine(segments = meta, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- 剪辑 ----------

private val ClipDpPerSecond = 18.dp
/** 片段宽度按时长比例；过短的片段保留能完整显示镜号与角色简称的最小宽度。 */
private fun cutClipWidth(clip: ProductionCutClip): Dp = (ClipDpPerSecond * clip.seconds.toFloat()).coerceAtLeast(64.dp)

@Composable
internal fun CutTimeline(
    production: Production,
    version: ProductionCutVersion,
    isPreview: Boolean,
    onShot: (String, String) -> Unit,
    activeClipIndex: Int? = null,
    onSelectClip: ((Int) -> Unit)? = null,
) {
    val locations = production.shotLocations().toList()
    val clipWidths = version.clips.map(::cutClipWidth)
    val width = clipWidths.fold(0.dp) { sum, it -> sum + it }.coerceAtLeast(120.dp)
    val domain = MaterialTheme.infinityDomainColors
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(width + 3.dp * version.clips.size), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                version.clips.forEachIndexed { index, clip ->
                    val clipWidth = clipWidths[index]
                    val active = activeClipIndex == index
                    val shot = locations.firstOrNull { it.episode.id == clip.episodeId && it.shot.code == clip.shotCode }?.shot
                    val shape = RoundedCornerShape(7.dp)
                    val label = clip.shotCode.removePrefix("SC").ifBlank { clip.shotCode }
                    Box(
                        Modifier.width(clipWidth).height(54.dp).clip(shape)
                            .then(
                                when {
                                    clip.path != null && active -> Modifier.background(scheme.surfaceContainerHighest).border(1.5.dp, scheme.primary, shape)
                                    clip.path != null -> Modifier.background(scheme.surfaceContainerHighest)
                                    active -> Modifier.background(scheme.primary.copy(alpha = 0.12f)).border(1.5.dp, scheme.primary, shape)
                                    isPreview -> Modifier.background(scheme.surfaceContainerHighest)
                                    else -> Modifier.border(1.dp, scheme.outline, shape)
                                },
                            )
                            .clickable(role = Role.Button) {
                                if (onSelectClip != null) onSelectClip(index) else onShot(clip.episodeId, clip.shotCode)
                            },
                    ) {
                        when {
                            clip.path != null -> {
                                ProductionThumbnail(clip.path, Modifier.fillMaxSize())
                                val review = if (shot?.status == ShotStatus.REVIEW && !isPreview) stringResource(R.string.production_panel_shot_review) else null
                                val badge = listOfNotNull(label, review).joinToString(" · ") // wrap-check: allow 剪辑缩略图角标固定单行
                                ClipBadge(badge, Modifier.align(Alignment.BottomStart))
                            }
                            isPreview -> Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                                Text(
                                    clip.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    secondsLabel(clip.seconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) scheme.primary else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            else -> Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) scheme.primary else scheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
            // 对白轨：分镜对白，标明原生或配音。
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                version.clips.forEachIndexed { index, clip ->
                    val clipWidth = clipWidths[index]
                    val line = locations.firstOrNull { it.episode.id == clip.episodeId && it.shot.code == clip.shotCode }?.shot?.dialogue?.firstOrNull()
                    if (line == null) Spacer(Modifier.width(clipWidth).height(18.dp))
                    else TrackSpan(
                        listOf(
                            shortTrackSpeaker(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId),
                            stringResource(if (line.audioSource == DialogueAudioSource.NATIVE) R.string.p2_native else R.string.p2_dubbed),
                        ),
                        scheme.tertiaryContainer, scheme.onTertiaryContainer, clipWidth,
                    )
                }
            }
            val ambience = locations.mapNotNull { it.shot.sound }.flatMap { it.split(InfinityMetaSeparator) }.map(String::trim).filter(String::isNotBlank).distinct().take(3)
            TrackSpan(listOf(stringResource(R.string.p2_track_environment)) + ambience, domain.successContainer, domain.onSuccessContainer, width)
            // 与导出一致：第一个已锁定的声音资产作为音乐铺底。
            val music = production.assets.firstOrNull { it.kind == AssetKind.SOUND && it.status == AssetStatus.LOCKED }?.name ?: stringResource(R.string.p2_none)
            TrackSpan(listOf(stringResource(R.string.p2_track_music), music), domain.infoContainer, domain.onInfoContainer, width)
        }
    }
}

@Composable
private fun TrackSpan(segments: List<String>, container: Color, content: Color, width: Dp) {
    Box(Modifier.width(width).height(18.dp).clip(RoundedCornerShape(5.dp)).background(container).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
        val text = segments.joinToString(" · ") // wrap-check: allow 18dp 剪辑轨道固定单行省略
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ClipBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(3.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)).padding(horizontal = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.overlayContent, maxLines = 1)
    }
}
