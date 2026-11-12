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
import androidx.compose.material.icons.rounded.RateReview
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
    onOutput: (OutputFilter) -> Unit, onScene: (String) -> Unit, onAsset: (String) -> Unit,
    onRun: (ProductionUserAction) -> Unit, onQueue: (String, QueueAction) -> Unit,
    channels: List<MediaChannel> = emptyList(),
) {
    var budgetSheet by remember { mutableStateOf(false) }
    val pending = production.overviewItems(tasks)
    val active = tasks.overviewQueueTasks(services.pendingRegistrations)
    val recent = tasks.recentQueueTasks(services.pendingRegistrations)
    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
        if (production.experimental) item("experiment") { ProductionTrialStrip() }
        item("pending") {
            ProductionSection(stringResource(R.string.production_panel_section_pending)) {
                if (pending.isEmpty()) ProductionMutedRow(stringResource(R.string.production_panel_nothing_pending))
                pending.forEach { item -> PendingRow(item, production, tasks, services, busy, onOutput, onScene, onAsset, onRun, onQueue, channels) }
            }
        }
        item("stages") {
            ProductionSection(stringResource(R.string.production_panel_section_stages)) {
                production.visibleStages().forEach { stage ->
                    StageRow(stage, stage.stage == production.currentStage(), busy, chip = stageChip(production, stage, services)) { onRun(ProductionUserAction.SetStage(stage.stage, it)) }
                }
            }
        }
        item("tasks") {
            ProductionSection(stringResource(R.string.p2_tasks), trailing = stringResource(R.string.p2_tasks_active, active.count { it.isActiveTask() })) {
                if (services.queue == null || services.projectSourcePrefix == null) ProductionMutedRow(stringResource(R.string.p2_queue_unavailable))
                else if (active.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_empty_queue))
                active.forEach { ProductionTaskRow(it, services, busy, onQueue, production.taskTitle(it.request.sourceRef), channels) }
            }
        }
        if (recent.isNotEmpty()) item("recent") {
            ProductionSection(stringResource(R.string.p2_recent)) {
                recent.forEach { ProductionTaskRow(it, services, busy, onQueue, production.taskTitle(it.request.sourceRef), channels) }
            }
        }
        item("budget") {
            ProductionSection(stringResource(R.string.p2_budget_section)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                        val spent = tasks.spentByCurrency()
                        val amount = if (spent.isEmpty()) moneyText("CNY", java.math.BigDecimal.ZERO) else spent.entries.joinToString(" · ") { moneyText(it.key, it.value) }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                            Text(amount, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                            production.budgetCents?.let {
                                Text(" / ${moneyText("CNY", java.math.BigDecimal.valueOf(it, 2))}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
                            }
                        }
                        InfinityPillButton(stringResource(if (production.budgetCents == null) R.string.p2_set_limit else R.string.p2_modify), onClick = { budgetSheet = true }, enabled = !busy, compact = true)
                    }
                    Text(
                        stringResource(if (production.budgetCents == null) R.string.p2_budget_each_detail else R.string.p2_auto_budget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        production.seasons.filter { it.episodes.isNotEmpty() }.forEach { season ->
            item("season-${season.id}") {
                ProductionSection(season.title ?: stringResource(R.string.p2_season, season.number)) {
                    season.episodes.forEach { episode -> EpisodeProgressRow(episode) { onScene(episode.id) } }
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
    if (budgetSheet) ProductionChoiceSheet(
        title = stringResource(R.string.p2_budget),
        options = listOf(
            ProductionChoice<Long?>(null, stringResource(R.string.p2_no_limit), stringResource(R.string.p2_approval_each)),
            ProductionChoice<Long?>(10000L, "¥100.00", stringResource(R.string.p2_auto_budget)),
            ProductionChoice<Long?>(30000L, "¥300.00", stringResource(R.string.p2_auto_budget)),
            ProductionChoice<Long?>(100000L, "¥1000.00", stringResource(R.string.p2_auto_budget)),
        ),
        selected = production.budgetCents, onDismiss = { budgetSheet = false }, onSelect = { onRun(ProductionUserAction.SetBudget(it)) },
    )
}

@Composable
private fun PendingRow(
    item: OverviewItem, production: Production, tasks: List<MediaTask>, services: ProductionPanelServices, busy: Boolean,
    onOutput: (OutputFilter) -> Unit, onScene: (String) -> Unit, onAsset: (String) -> Unit,
    onRun: (ProductionUserAction) -> Unit, onQueue: (String, QueueAction) -> Unit, channels: List<MediaChannel>,
) {
    when (item) {
        is OverviewItem.Review -> ProductionRow(stringResource(R.string.p2_review_shots, item.count), icon = Icons.Rounded.RateReview, onClick = { onOutput(OutputFilter.REVIEW) })
        is OverviewItem.Approval -> ProductionTaskRow(item.task, services, busy, onQueue, production.taskTitle(item.task.request.sourceRef), channels)
        is OverviewItem.Redo -> ProductionRow(stringResource(R.string.p2_redo_shots, item.count), icon = Icons.Rounded.Replay, onClick = { onOutput(OutputFilter.REDO) })
        is OverviewItem.SceneLock -> ProductionRow(
            stringResource(R.string.p2_scene_waiting, item.scene.number),
            icon = Icons.Rounded.LockOpen,
            subtitle = stringResource(R.string.p2_scene_pending_detail, item.scene.heading),
            onClick = { onScene(item.episode.id) },
        )
        is OverviewItem.ReferenceReview -> {
            val used = item.asset.referenceLocations(production).size
            ProductionRow(
                listOfNotNull(item.asset.name, item.asset.looks.firstOrNull()?.name).joinToString(" · "),
                icon = Icons.Rounded.Person,
                subtitle = stringResource(R.string.p2_reference_pending_detail, used),
                onClick = { onAsset(item.asset.id) },
            )
        }
        is OverviewItem.ChangeReview -> {
            val request = item.request
            ProductionRow(stringResource(R.string.p2_change_request), icon = Icons.Rounded.EditNote, subtitle = "${request.episodeId} · ${request.shotCode}")
            Column(Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal + InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap, end = InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                request.changes.forEach { (field, change) ->
                    Text(stringResource(R.string.p2_change_diff, contentFieldLabel(field), change.oldValue.toString(), change.newValue.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                request.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ProductionActionRow(listOf(
                    ProductionButton(stringResource(R.string.p2_reject), InfinityPillTone.NEUTRAL, !busy, "p2_change_reject") { onRun(changeApprovalAction(request, false)) },
                    ProductionButton(stringResource(R.string.p2_approve), InfinityPillTone.PRIMARY, !busy, "p2_change_approve") { onRun(changeApprovalAction(request, true)) },
                ), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

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
    val characters = production.assets.filter { it.kind == AssetKind.CHARACTER }
    val (done, total) = when (state.stage) {
        ProductionStage.VISUAL_DEV -> if (characters.isNotEmpty()) characters.count { it.status == AssetStatus.LOCKED } to characters.size else production.stageProgressCounts(state.stage) ?: (0 to 0)
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
        ProductionStage.VISUAL_DEV -> stringResource(if (characters.isNotEmpty()) R.string.p2_stage_characters else R.string.p2_stage_assets, done, total)
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
internal fun Production.taskTitle(sourceRef: String): String? {
    val ref = ProductionSourceRef.parse(sourceRef) ?: return null
    if (ref.productionId != id) return null
    val shot = shotLocations().firstOrNull { it.episode.id == ref.episodeId && it.shot.code == ref.shotCode }?.shot
    val base = shot?.let { "${it.code} · ${it.title}" } ?: ref.shotCode
    return ref.dialogueIndex?.let { "$base · #${it + 1}" } ?: base
}

internal fun centsAmount(cents: Long) = java.math.BigDecimal.valueOf(cents, 2).toPlainString()

/** 金额显示：人民币与美元用符号，其余币种写代码。 */
internal fun moneyText(currency: String, amount: java.math.BigDecimal): String {
    val value = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    return when (currency.uppercase()) {
        "CNY", "RMB" -> "¥$value"
        "USD" -> "$$value"
        else -> "$currency $value"
    }
}

internal fun MediaTask.tone(pendingRegistrations: Set<String>): ProductionTone = when {
    awaitingRegistration(pendingRegistrations) -> ProductionTone.INFO
    else -> when (state) {
        MediaTaskState.AWAITING_APPROVAL, MediaTaskState.RESULT_UNKNOWN -> ProductionTone.WARNING
        MediaTaskState.QUEUED, MediaTaskState.SUBMITTING -> ProductionTone.NEUTRAL
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

@Composable
internal fun ProductionTaskRow(task: MediaTask, services: ProductionPanelServices, busy: Boolean, onQueue: (String, QueueAction) -> Unit, title: String? = null, channels: List<MediaChannel> = emptyList()) {
    val running = task.isActiveTask() && task.state != MediaTaskState.QUEUED
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf(title ?: task.request.sourceRef, task.kindLabel(channels)).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(
                    segments = listOfNotNull(
                        services.channelLabel(task.request.channelId),
                        task.estimate?.takeIf { task.state == MediaTaskState.AWAITING_APPROVAL }?.let { stringResource(R.string.p2_estimate, moneyText(it.currency, it.amount)) },
                        task.error,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            val percent = task.progress?.takeIf { running }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            ProductionStatusChip(task.stateLabel(services.pendingRegistrations) + percent, task.tone(services.pendingRegistrations))
        }
        if (running) ProductionProgressBar(task.progress)
        val actions = task.allowedQueueActions()
        if (actions.isNotEmpty()) ProductionActionRow(actions.sortedBy { if (it == QueueAction.APPROVE) 1 else 0 }.map { action ->
            ProductionButton(
                stringResource(when (action) { QueueAction.APPROVE -> R.string.p2_approve; QueueAction.REJECT -> R.string.p2_reject; QueueAction.RETRY -> R.string.p2_retry }),
                if (action == QueueAction.APPROVE) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                !busy && services.queue != null, "p2_task_${action.name}",
            ) { onQueue(task.id, action) }
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

// ---------- 出片 ----------

@Composable
internal fun ProductionOutputTab(production: Production, filter: OutputFilter, onFilter: (OutputFilter) -> Unit, services: ProductionPanelServices, tasks: List<MediaTask>, channels: List<MediaChannel>, channelOrder: List<String>, onShot: (String, String) -> Unit, onScene: (String) -> Unit) {
    val rows = production.outputItems(filter)
    val multiEpisode = production.episodes().size > 1
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
                Column {
                    ProductionSceneHeader(
                        scene,
                        meta = listOfNotNull(
                            episode.takeIf { multiEpisode }?.let { episodeName(it).joinToString(" ") },
                            stringResource(R.string.p2_scene_shots_duration, scene.shots.size, formatShotSeconds(scene.shots.sumOf { it.durationSeconds })),
                            if (unlocked) stringResource(R.string.p2_scene_pending) else stringResource(R.string.p2_scene_locked_count, scene.shots.count { it.status == ShotStatus.LOCKED }),
                        ),
                    )
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
    val detail = if (take != null) stringResource(R.string.p2_candidates_channel, shot.takes.size, services.channelLabel(take.source.service))
    else listOf(resolvedShotChannel(shot, channels, channelOrder)?.id?.let(services.channelLabel) ?: shot.channelId?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel), shot.mode.label()).joinToString(" · ")
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
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProductionStatusChip(chip.text, chip.tone)
    }
}

/** 场标题：场次与场景名为主行，镜数、时长与锁定状态为副行，右侧可放锁定按钮。 */
@Composable
internal fun ProductionSceneHeader(scene: Scene, meta: List<String>, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = InfinitySpacing.RowHorizontal, end = if (action != null) 0.dp else InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            InfinityMetaLine(
                segments = listOf(stringResource(R.string.production_panel_scene_number, scene.number), scene.heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            InfinityMetaLine(segments = meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        action?.invoke(this)
    }
}

// ---------- 剪辑 ----------

private val ClipDpPerSecond = 14.dp
/** 片段宽度按时长比例；过短的片段保留能完整显示镜号角标的最小宽度。 */
private fun cutClipWidth(clip: ProductionCutClip): Dp = (ClipDpPerSecond * clip.seconds.toFloat()).coerceAtLeast(48.dp)

@Composable
internal fun CutTimeline(production: Production, version: ProductionCutVersion, isPreview: Boolean, onShot: (String, String) -> Unit) {
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
                    val shot = locations.firstOrNull { it.episode.id == clip.episodeId && it.shot.code == clip.shotCode }?.shot
                    val shape = RoundedCornerShape(7.dp)
                    val label = clip.shotCode.removePrefix("SC").ifBlank { clip.shotCode }
                    Box(
                        Modifier.width(clipWidth).height(54.dp).clip(shape)
                            .then(if (clip.path == null && !isPreview) Modifier.border(1.dp, scheme.outline, shape) else Modifier.background(scheme.surfaceContainerHighest))
                            .clickable(role = Role.Button) { onShot(clip.episodeId, clip.shotCode) },
                    ) {
                        when {
                            clip.path != null -> {
                                ProductionThumbnail(clip.path, Modifier.fillMaxSize())
                                ClipBadge(label + if (shot?.status == ShotStatus.REVIEW && !isPreview) " · ${stringResource(R.string.production_panel_shot_review)}" else "", Modifier.align(Alignment.BottomStart))
                            }
                            isPreview -> Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                                Text(clip.title, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(secondsLabel(clip.seconds), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                            }
                            else -> Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.align(Alignment.Center))
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
                        "${production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId} · ${stringResource(if (line.audioSource == DialogueAudioSource.NATIVE) R.string.p2_native else R.string.p2_dubbed)}",
                        scheme.tertiaryContainer, scheme.onTertiaryContainer, clipWidth,
                    )
                }
            }
            val ambience = locations.mapNotNull { it.shot.sound }.flatMap { it.split(" · ", "·") }.map(String::trim).filter(String::isNotBlank).distinct().take(3)
            TrackSpan(listOf(stringResource(R.string.p2_track_environment)).plus(ambience).joinToString(" · "), domain.successContainer, domain.onSuccessContainer, width)
            // 与导出一致：第一个已锁定的声音资产作为音乐铺底。
            val music = production.assets.firstOrNull { it.kind == AssetKind.SOUND && it.status == AssetStatus.LOCKED }?.name ?: stringResource(R.string.p2_none)
            TrackSpan(listOf(stringResource(R.string.p2_track_music), music).joinToString(" · "), domain.infoContainer, domain.onInfoContainer, width)
        }
    }
}

@Composable
private fun TrackSpan(text: String, container: Color, content: Color, width: Dp) {
    Box(Modifier.width(width).height(18.dp).clip(RoundedCornerShape(5.dp)).background(container).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ClipBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(3.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)).padding(horizontal = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.overlayContent, maxLines = 1)
    }
}
