package com.promenar.infinity.ui.production

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 方案 A 动态「后处理」页签（匹配已确认 v1.3 原型）：
 * 与「出片」页签同构，不放顶部说明卡；展示原片与派生（DERIVE）候选规格对比并复用镜头审片页完成 A/B 验收。
 */
@Composable
internal fun ProductionPostProcessTab(
    production: Production,
    filter: PostProcessFilter,
    onFilter: (PostProcessFilter) -> Unit,
    onShot: (String, String) -> Unit,
) {
    val allRows = remember(production) { production.postProcessRows(PostProcessFilter.ALL) }
    val filtered = remember(allRows, filter) {
        when (filter) {
            PostProcessFilter.ALL -> allRows
            PostProcessFilter.PENDING -> allRows.filter { it.status == PostProcessShotStatus.PENDING }
            PostProcessFilter.REVIEW -> allRows.filter { it.status == PostProcessShotStatus.REVIEW }
            PostProcessFilter.LOCKED -> allRows.filter { it.status == PostProcessShotStatus.LOCKED }
        }
    }
    val groups = remember(filtered) {
        filtered.fold(mutableListOf<Pair<Pair<Episode, Scene>, MutableList<PostProcessShotRow>>>()) { acc, row ->
            val key = row.location.episode to row.location.scene
            if (acc.lastOrNull()?.first?.let { it.first.id == key.first.id && it.second.code == key.second.code } == true) {
                acc.last().second += row
            } else {
                acc += key to mutableListOf(row)
            }
            acc
        }
    }
    val multiEpisode = production.showEpisodeNumbers()
    val pendingCount = allRows.count { it.status == PostProcessShotStatus.PENDING }
    val reviewCount = allRows.count { it.status == PostProcessShotStatus.REVIEW }
    val lockedCount = allRows.count { it.status == PostProcessShotStatus.LOCKED }

    LazyColumn(
        contentPadding = ProductionPagePadding,
        verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
    ) {
        item("filter") {
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(PostProcessFilter.ALL, "${stringResource(R.string.p2_post_filter_all)} ${allRows.size}"),
                    InfinitySegment(PostProcessFilter.PENDING, "${stringResource(R.string.p2_post_filter_pending)} $pendingCount"),
                    InfinitySegment(PostProcessFilter.REVIEW, "${stringResource(R.string.p2_post_filter_review)} $reviewCount"),
                    InfinitySegment(PostProcessFilter.LOCKED, "${stringResource(R.string.p2_post_filter_locked)} $lockedCount"),
                ),
                selected = filter,
                onSelect = onFilter,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (groups.isEmpty()) {
            item("empty") {
                ProductionSection(null) {
                    ProductionMutedRow(stringResource(R.string.p2_post_empty))
                }
            }
        }
        groups.forEach { (key, items) ->
            val (episode, scene) = key
            item("${episode.id}/${scene.code}") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProductionSceneHeader(
                        scene = scene,
                        meta = listOfNotNull(
                            episode.takeIf { multiEpisode }?.let { episodeName(it).joinToString(" ") },
                            stringResource(
                                R.string.p2_scene_shots_duration,
                                items.size,
                                formatShotSeconds(items.sumOf { it.location.shot.durationSeconds }),
                            ),
                        ),
                    )
                    BettboxListGroup {
                        Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
                            items.forEachIndexed { index, row ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                                    )
                                }
                                PostProcessShotItemCard(
                                    row = row,
                                    onOpenShot = { onShot(row.location.episode.id, row.location.shot.code) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostProcessShotItemCard(
    row: PostProcessShotRow,
    onOpenShot: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shot = row.location.shot
    val chip = when (row.status) {
        PostProcessShotStatus.PENDING -> ProductionChipSpec(stringResource(R.string.p2_post_status_pending), ProductionTone.NEUTRAL)
        PostProcessShotStatus.REVIEW -> ProductionChipSpec(stringResource(R.string.p2_post_status_review), ProductionTone.WARNING)
        PostProcessShotStatus.LOCKED -> ProductionChipSpec(stringResource(R.string.p2_post_status_locked), ProductionTone.SUCCESS)
    }
    val sourceSegments = row.sourceTake?.let { take ->
        takeSpecSegments(
            prefix = stringResource(R.string.p2_post_source_prefix, shot.takeNumber(take)),
            take = take,
            fallbackDuration = shot.durationSeconds,
        )
    }.orEmpty()
    val derivedSegments = row.derivedTake?.let { take ->
        takeSpecSegments(
            prefix = stringResource(R.string.p2_post_derived_prefix, shot.takeNumber(take)),
            take = take,
            operation = take.derivedFrom?.operation?.takeIf(String::isNotBlank),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpenShot)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = shot.code,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
            Text(
                text = shot.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ProductionStatusChip(chip.text, chip.tone)
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sourceSegments.isNotEmpty()) {
                    InfinityMetaLine(
                        segments = sourceSegments,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (derivedSegments != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        InfinityMetaLine(
                            segments = derivedSegments,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.p2_post_no_derived),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (row.derivedTake != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InfinityPillButton(
                    text = stringResource(if (row.status == PostProcessShotStatus.REVIEW) R.string.p2_post_compare_review else R.string.p2_post_view_result),
                    onClick = onOpenShot,
                    tone = if (row.status == PostProcessShotStatus.REVIEW) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.RateReview,
                    compact = true,
                )
            }
        }
    }
}

private fun com.promenar.infinity.data.production.Shot.takeNumber(take: Take): Int =
    (takes.indexOfFirst { it.id == take.id }.takeIf { it >= 0 } ?: 0) + 1

/** 候选规格紧凑文案：优先像素宽高（如 `2688×1536`），否则回退规划分辨率（如 `1080p`）。 */
internal fun Take.compactSpecLabel(): String? {
    val width = spec?.width ?: planSnapshot?.specs?.width
    val height = spec?.height ?: planSnapshot?.specs?.height
    if (width != null && height != null) return "${width}×${height}"
    return planSnapshot?.specs?.resolution?.takeIf(String::isNotBlank)
}

@Composable
private fun takeSpecSegments(
    prefix: String,
    take: Take,
    operation: String? = null,
    fallbackDuration: Double? = null,
): List<String> = buildList {
    add(prefix)
    take.compactSpecLabel()?.let(::add)
    val duration = take.spec?.durationSeconds ?: fallbackDuration
    if (duration != null && operation == null) add(secondsLabel(duration))
    operation?.let(::add)
}
