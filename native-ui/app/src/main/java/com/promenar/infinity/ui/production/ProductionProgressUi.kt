package com.promenar.infinity.ui.production

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.AssetVersion
import com.promenar.infinity.data.production.ChainSegmentState
import com.promenar.infinity.data.production.ChainState
import com.promenar.infinity.data.production.MediaSpec
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionRun
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotChain
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeDerivation
import com.promenar.infinity.data.production.TakeSegment
import com.promenar.infinity.data.production.isActive
import com.promenar.infinity.data.production.isUnreported
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.delay

/* 进行中的尾帧衔接链与登记项、拼接候选的分段、派生候选的来源、未登记文件与配乐版本。面板只显示与取消，不发起生成。 */

// ---------- 展示模型（纯函数，单测覆盖） ----------

/** 镜头候选条上进行中的一项。 */
internal sealed interface ShotPlaceholder {
    val key: String
    data class Chain(val chain: ShotChain) : ShotPlaceholder { override val key: String get() = "chain:${chain.id}" }
    data class Run(val run: ProductionRun) : ShotPlaceholder { override val key: String get() = "run:${run.id}" }
}

/** 镜头上进行中的链与登记项：链在前，各自按开始时间排序。 */
internal fun Production.shotPlaceholders(episodeId: String, shotCode: String): List<ShotPlaceholder> {
    val shot = findShot(episodeId, shotCode)?.shot ?: return emptyList()
    return shot.chains.filter { it.isActive() }.sortedBy { it.createdAt }.map { ShotPlaceholder.Chain(it) } +
        runs.filter { it.isActive() && it.target.episodeId == episodeId && it.target.shotCode == shotCode }.sortedBy { it.startedAt }.map { ShotPlaceholder.Run(it) }
}

/** 总览“进行中”的链与登记项。 */
internal sealed interface ProgressItem {
    data class Chain(val location: ShotLocation, val chain: ShotChain) : ProgressItem
    data class Run(val run: ProductionRun) : ProgressItem
}

internal fun Production.progressItems(): List<ProgressItem> =
    shotLocations().flatMap { location -> location.shot.chains.filter { it.isActive() }.sortedBy { it.createdAt }.map { ProgressItem.Chain(location, it) } }.toList() +
        runs.filter { it.isActive() }.sortedBy { it.startedAt }.map { ProgressItem.Run(it) }

/** 未回报的登记项数；总览用它决定警示色。 */
internal fun Production.unreportedRuns(now: Long): Int = runs.count { it.isUnreported(now) }

internal fun ShotChain.completedSegments(): Int = segments.count { it.state == ChainSegmentState.COMPLETED }

/** 链的进度：已完成段数占比；拼接中视为全部段已完成。 */
internal fun ShotChain.progress(): Float? = segments.size.takeIf { it > 0 }?.let { completedSegments().toFloat() / it }

/** 当前段的序号，从 1 起。 */
internal fun ShotChain.currentNumber(): Int = (current + 1).coerceIn(1, segments.size.coerceAtLeast(1))

internal fun ProductionRun.progress(): Float? = steps?.takeIf { it > 0 }?.let { (completedSteps.toFloat() / it).coerceIn(0f, 1f) }

/** 链各段在成片里的起止秒数，按计划时长累加。 */
internal fun ShotChain.segmentRanges(): List<Pair<Double, Double>> {
    var start = 0.0
    return segments.sortedBy { it.index }.map { segment -> (start to start + segment.seconds).also { start += segment.seconds } }
}

/** 参数取值按键名排序，写成“键 值”。 */
internal fun TakeDerivation.paramSegments(): List<String> = params.entries.sortedBy { it.key }.map { (key, value) -> "$key ${value.content}" }

/** 未登记文件相对项目根的路径。 */
internal fun Production.unregisteredRelative(): List<String> = unregisteredFiles.map { it.removePrefix(rootPath).trimStart('/') }

/** 登记项的对象：镜头编号与标题，或资产名称。 */
internal fun Production.runTargetTitle(run: ProductionRun): String? {
    val target = run.target
    target.assetId?.let { id -> return findAsset(id)?.name ?: id }
    val episodeId = target.episodeId ?: return null
    val code = target.shotCode ?: return null
    return listOfNotNull(code, findShot(episodeId, code)?.shot?.title?.takeIf(String::isNotBlank)).joinToString(" ")
}

// ---------- 时钟 ----------

/** 当前时间；有进行中的登记项时每分钟刷新一次，用于判定未回报。 */
@Composable
internal fun rememberProductionNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        now = System.currentTimeMillis()
        while (ticking) {
            delay(NOW_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private const val NOW_TICK_MILLIS = 60_000L

// ---------- 状态词 ----------

@Composable
internal fun ShotChain.stateLabel(): String = when (state) {
    ChainState.PLANNED -> stringResource(R.string.production_chain_planned)
    ChainState.RUNNING -> stringResource(R.string.production_chain_segment_of, currentNumber(), segments.size)
    ChainState.HANDOFF -> stringResource(R.string.production_chain_handoff, currentNumber(), segments.size)
    ChainState.CONCATENATING -> stringResource(R.string.production_chain_concatenating)
    ChainState.COMPLETED -> stringResource(R.string.production_progress_completed)
    ChainState.FAILED -> stringResource(R.string.production_progress_failed)
    ChainState.CANCELED -> stringResource(R.string.production_progress_canceled)
}

internal fun ChainState.tone(): ProductionTone = when (this) {
    ChainState.PLANNED -> ProductionTone.NEUTRAL
    ChainState.RUNNING, ChainState.HANDOFF, ChainState.CONCATENATING -> ProductionTone.INFO
    ChainState.COMPLETED -> ProductionTone.SUCCESS
    ChainState.FAILED -> ProductionTone.ERROR
    ChainState.CANCELED -> ProductionTone.NEUTRAL
}

@Composable
internal fun ChainSegmentState.label(): String = stringResource(when (this) {
    ChainSegmentState.PENDING -> R.string.production_segment_pending
    ChainSegmentState.RUNNING -> R.string.production_segment_running
    ChainSegmentState.COMPLETED -> R.string.production_progress_completed
    ChainSegmentState.FAILED -> R.string.production_progress_failed
    ChainSegmentState.CANCELED -> R.string.production_progress_canceled
})

internal fun ChainSegmentState.tone(): ProductionTone = when (this) {
    ChainSegmentState.PENDING -> ProductionTone.NEUTRAL
    ChainSegmentState.RUNNING -> ProductionTone.INFO
    ChainSegmentState.COMPLETED -> ProductionTone.SUCCESS
    ChainSegmentState.FAILED -> ProductionTone.ERROR
    ChainSegmentState.CANCELED -> ProductionTone.NEUTRAL
}

/** 登记项的状态标签：未回报优先，其次是步数。 */
@Composable
internal fun ProductionRun.chipSpec(now: Long): ProductionChipSpec = when {
    isUnreported(now) -> ProductionChipSpec(stringResource(R.string.production_run_unreported), ProductionTone.WARNING)
    steps != null -> ProductionChipSpec(stringResource(R.string.production_run_steps, completedSteps, requireNotNull(steps)), ProductionTone.INFO)
    else -> ProductionChipSpec(stringResource(R.string.production_progress_running), ProductionTone.INFO)
}

@Composable
private fun ShotPlaceholder.title(): String = when (this) {
    is ShotPlaceholder.Chain -> stringResource(R.string.production_chain_title)
    is ShotPlaceholder.Run -> run.label
}

@Composable
private fun ShotPlaceholder.chip(now: Long): ProductionChipSpec = when (this) {
    is ShotPlaceholder.Chain -> ProductionChipSpec(chain.stateLabel(), chain.state.tone())
    is ShotPlaceholder.Run -> run.chipSpec(now)
}

@Composable
internal fun MediaSpec.segments(): List<String> = listOfNotNull(
    width?.let { w -> height?.let { h -> "$w×$h" } },
    fps?.let { stringResource(R.string.production_spec_fps, formatShotSeconds(it)) },
    durationSeconds?.let { secondsLabel(it) },
)

// ---------- 镜头候选页签 ----------

/** 候选条里的占位项：与候选缩略图同宽，显示名称与进度。 */
@Composable
internal fun PlaceholderThumb(item: ShotPlaceholder, viewing: Boolean, now: Long, onClick: () -> Unit) {
    val chip = item.chip(now)
    val title = item.title()
    Column(
        Modifier
            .width(112.dp)
            .semantics { this.selected = viewing }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag(ProductionUiTags.PLACEHOLDER),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Surface(
            shape = ProductionThumbShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (viewing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ProductionThumbShape) else Modifier),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (item is ShotPlaceholder.Chain) Icons.Rounded.Link else Icons.Rounded.HourglassTop,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (viewing) FontWeight.SemiBold else FontWeight.Normal,
            color = if (viewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            chip.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (chip.tone == ProductionTone.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 进行中的一项：进度、智能体的说明、分段与“取消”。 */
@Composable
internal fun ProductionPlaceholderCard(item: ShotPlaceholder, now: Long, busy: Boolean, onRun: (ProductionUserAction) -> Unit, episodeId: String, shotCode: String) {
    ProductionSection(null, modifier = Modifier.testTag(ProductionUiTags.PLACEHOLDER_CARD)) {
        val chip = item.chip(now)
        ProductionRow(
            item.title(),
            icon = if (item is ShotPlaceholder.Chain) Icons.Rounded.Link else Icons.Rounded.HourglassTop,
            chip = chip,
            chevron = false,
        )
        Column(
            Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            when (item) {
                is ShotPlaceholder.Chain -> {
                    if (item.chain.state != ChainState.PLANNED) ProductionProgressBar(item.chain.progress())
                    ChainSegmentStrip(item.chain)
                }
                is ShotPlaceholder.Run -> {
                    ProductionProgressBar(item.run.progress())
                    item.run.message?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            ProductionActionRow(listOf(
                ProductionButton(stringResource(R.string.production_task_cancel), InfinityPillTone.NEUTRAL, !busy, ProductionUiTags.PLACEHOLDER_CANCEL) {
                    onRun(when (item) {
                        is ShotPlaceholder.Chain -> ProductionUserAction.CancelChain(episodeId, shotCode, item.chain.id)
                        is ShotPlaceholder.Run -> ProductionUserAction.CancelRun(item.run.id)
                    })
                },
            ))
        }
    }
}

/** 进行中链的分段卡片：缩略图、时间范围、状态与失败原因。 */
@Composable
internal fun ChainSegmentStrip(chain: ShotChain) {
    val ranges = chain.segmentRanges()
    val ordered = chain.segments.sortedBy { it.index }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        items(ordered.size, key = { ordered[it].index }) { position ->
            val segment = ordered[position]
            val range = ranges.getOrNull(position)
            SegmentCard(
                number = segment.index + 1,
                path = segment.path ?: segment.firstFramePath,
                range = range,
                chip = ProductionChipSpec(segment.state.label(), segment.state.tone()),
                failure = segment.failure,
            )
        }
    }
}

/** 拼接候选的分段：各段缩略图与在成片里的时间范围。 */
@Composable
internal fun TakeSegmentsSection(take: Take) {
    if (take.segments.isEmpty()) return
    ProductionSection(stringResource(R.string.production_segments_title), trailing = take.segments.size.toString()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
        ) {
            items(take.segments.sortedBy { it.index }, key = { it.index }) { segment: TakeSegment ->
                SegmentCard(number = segment.index + 1, path = segment.path, range = segment.startSeconds to segment.endSeconds, chip = null, failure = null)
            }
        }
    }
}

@Composable
private fun SegmentCard(number: Int, path: String?, range: Pair<Double, Double>?, chip: ProductionChipSpec?, failure: String?) {
    Column(Modifier.width(128.dp).testTag(ProductionUiTags.SEGMENT), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        val label = stringResource(R.string.production_segment_number, number)
        ProductionThumbnail(path, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentDescription = label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
            chip?.let { ProductionStatusChip(it.text, it.tone) }
        }
        range?.let { (start, end) ->
            Text(
                stringResource(R.string.production_segment_range, formatShotSeconds(start), formatShotSeconds(end)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        failure?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 派生或登记产出的候选：来源候选、处理名称与参数、登记项与规格。 */
@Composable
internal fun TakeSourceSection(take: Take, shot: Shot, production: Production, onOpenTake: (String) -> Unit) {
    val derivation = take.derivedFrom
    val run = take.runId?.let { id -> production.runs.firstOrNull { it.id == id } }
    if (derivation == null && run == null && take.spec == null) return
    ProductionSection(stringResource(R.string.production_source_title), modifier = Modifier.testTag(ProductionUiTags.SOURCE_CARD)) {
        derivation?.let { source ->
            val index = shot.takes.indexOfFirst { it.id == source.sourceTakeId }
            val sourceTake = shot.takes.getOrNull(index)
            ProductionRow(
                stringResource(R.string.production_source_take),
                value = if (index >= 0) stringResource(R.string.production_panel_take_number, index + 1) else source.sourceTakeId,
                leading = sourceTake?.let { take -> @Composable { ProductionThumbnail(take.path, Modifier.width(56.dp).aspectRatio(16f / 9f)) } },
                enabled = sourceTake != null,
                onClick = sourceTake?.let { take -> { onOpenTake(take.id) } },
            )
            ProductionRow(stringResource(R.string.production_source_operation), value = source.operation, chevron = false)
            if (source.params.isNotEmpty()) ProductionRow(
                stringResource(R.string.production_source_params),
                subtitleSegments = source.paramSegments(),
                subtitleMaxLines = 3,
                chevron = false,
            )
        }
        run?.let { ProductionRow(stringResource(R.string.production_source_run), value = it.label, chevron = false) }
        take.spec?.segments()?.takeIf { it.isNotEmpty() }?.let { segments ->
            ProductionRow(stringResource(R.string.p2_specs), subtitleSegments = segments, chevron = false)
        }
    }
}

// ---------- 总览 ----------

/** 总览“进行中”里的一行：链或登记项，带进度与“取消”。 */
@Composable
internal fun ProductionProgressRow(item: ProgressItem, production: Production, now: Long, busy: Boolean, onRun: (ProductionUserAction) -> Unit, onShot: (String, String) -> Unit) {
    val (title, meta, chip, progress) = when (item) {
        is ProgressItem.Chain -> ProgressRowContent(
            listOf(item.location.shot.code, stringResource(R.string.production_chain_title)).joinToString(" "),
            listOfNotNull(item.location.shot.title.takeIf(String::isNotBlank)),
            ProductionChipSpec(item.chain.stateLabel(), item.chain.state.tone()),
            item.chain.progress(),
        )
        is ProgressItem.Run -> ProgressRowContent(
            item.run.label,
            listOfNotNull(production.runTargetTitle(item.run), item.run.message?.takeIf(String::isNotBlank)),
            item.run.chipSpec(now),
            item.run.progress(),
        )
    }
    val open: (() -> Unit)? = when (item) {
        is ProgressItem.Chain -> { { onShot(item.location.episode.id, item.location.shot.code) } }
        is ProgressItem.Run -> item.run.target.let { target -> if (target.episodeId != null && target.shotCode != null) ({ onShot(target.episodeId, target.shotCode) }) else null }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp)
            .testTag(ProductionUiTags.PROGRESS_ROW),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(segments = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            ProductionStatusChip(chip.text, chip.tone)
        }
        ProductionProgressBar(progress)
        ProductionActionRow(listOf(
            ProductionButton(stringResource(R.string.production_task_cancel), InfinityPillTone.NEUTRAL, !busy, ProductionUiTags.PLACEHOLDER_CANCEL) {
                onRun(when (item) {
                    is ProgressItem.Chain -> ProductionUserAction.CancelChain(item.location.episode.id, item.location.shot.code, item.chain.id)
                    is ProgressItem.Run -> ProductionUserAction.CancelRun(item.run.id)
                })
            },
        ))
    }
}

private data class ProgressRowContent(val title: String, val meta: List<String>, val chip: ProductionChipSpec, val progress: Float?)

/** 总览“未登记”：没有被候选、资产版本、链段、登记项或剪辑引用的媒体文件。 */
@Composable
internal fun ProductionUnregisteredSection(production: Production) {
    val files = production.unregisteredRelative()
    if (files.isEmpty()) return
    ProductionSection(stringResource(R.string.production_unregistered_title), trailing = files.size.toString(), modifier = Modifier.testTag(ProductionUiTags.UNREGISTERED)) {
        files.take(UNREGISTERED_VISIBLE).forEach { path ->
            ProductionRow(
                path.substringAfterLast('/'),
                icon = Icons.Rounded.InsertDriveFile,
                subtitle = path.substringBeforeLast('/', "").takeIf(String::isNotBlank),
                chevron = false,
            )
        }
        if (files.size > UNREGISTERED_VISIBLE) ProductionMutedRow(stringResource(R.string.production_unregistered_more, files.size - UNREGISTERED_VISIBLE))
    }
}

private const val UNREGISTERED_VISIBLE = 20

// ---------- 资产页的配乐版本 ----------

/** 配乐各版本：波形、时长与种子；点选后在上方试听并审核。 */
@Composable
internal fun ScoreVersionList(asset: Asset, production: Production, selected: Int?, onSelect: (Int) -> Unit) {
    ProductionSection(stringResource(R.string.production_panel_section_versions), trailing = asset.versions.size.toString()) {
        asset.versions.sortedByDescending { it.number }.forEach { version ->
            ScoreVersionRow(asset, version, production, version.number == selected) { onSelect(version.number) }
        }
    }
}

@Composable
private fun ScoreVersionRow(asset: Asset, version: AssetVersion, production: Production, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp)
            .testTag(ProductionUiTags.SCORE_VERSION),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            Text(
                stringResource(R.string.production_panel_version, version.number),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            production.reviewChipSpec(
                ReviewTarget.assetVersion(asset.id, version.number),
                settled = asset.status == AssetStatus.LOCKED && version.number == asset.currentVersion,
            )?.let { ProductionStatusChip(it.text, it.tone) }
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        }
        ProductionVoiceWaveform(version.path)
        InfinityMetaLine(
            segments = listOfNotNull(
                version.durationSeconds?.let { secondsLabel(it) },
                version.seed?.let { stringResource(R.string.production_score_seed, it.toString()) },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
