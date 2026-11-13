package com.promenar.infinity.ui.production

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.data.cut.CutExportState
import com.promenar.infinity.data.production.CutVersion
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberMediaActionRunner
import com.promenar.infinity.ui.media.rememberWorkspaceMediaResolution
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val PREVIEW_ID = "preview"
private const val SELECTED_ID = "selected"

/** 剪辑页：即时视图“预演”“选定”与按集保存的“粗剪 vN”共用一条时间线；保存的版本可导出成片。 */
@Composable
internal fun ProductionCutTab(
    production: Production,
    services: ProductionPanelServices,
    sessionId: String,
    onShot: (String, String) -> Unit,
    onError: (String?) -> Unit,
    busy: Boolean = false,
    onRun: (ProductionUserAction) -> Unit = {},
    onSubmit: suspend (ProductionUserAction) -> String? = { null },
    focusCutId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
    val episodes = production.episodes()
    var episodeId by rememberSaveable(production.id) { mutableStateOf(production.defaultEpisodeId()) }
    val episode = episodes.firstOrNull { it.id == episodeId } ?: episodes.firstOrNull()
    var versionId by rememberSaveable(production.id) { mutableStateOf<String?>(null) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var clipIndex by rememberSaveable { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CutVersion?>(null) }
    var shareRequest by remember { mutableIntStateOf(0) }
    val controller = services.cutExport?.takeIf { sessionId.isNotBlank() }
    val states by (controller?.observe() ?: remember { MutableStateFlow(emptyMap()) }).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fallback = stringResource(R.string.production_panel_action_failed)
    fun launchAction(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { block(); onError(null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { onError(error.message ?: fallback) }
            catch (error: IllegalStateException) { onError(error.message ?: fallback) }
            catch (_: Exception) { onError(fallback) }
            finally { working = false }
        }
    }

    val shots = production.shotLocations().filter { it.episode.id == episode?.id }.associateBy { it.shot.code }
    val saved = production.cuts.filter { it.episodeId == episode?.id }.sortedBy { it.number }
    val savedTitle = stringResource(R.string.p3_cut_version)
    val versions = listOf(
        ProductionCutVersion(PREVIEW_ID, stringResource(R.string.p2_cut_preview), null, production.cutClips(true).filter { it.episodeId == episode?.id }),
        ProductionCutVersion(SELECTED_ID, stringResource(R.string.p2_cut_selected), null, production.cutClips(false).filter { it.episodeId == episode?.id }),
    ) + saved.map { cut ->
        ProductionCutVersion(
            id = cut.id,
            title = savedTitle.format(cut.number),
            path = cut.export?.videoPath,
            clips = cut.clips.map { ProductionCutClip(it.episodeId, it.shotCode, shots[it.shotCode]?.shot?.title.orEmpty(), it.seconds, it.path) },
        )
    }
    // 未手动选择时停在该集最新的粗剪版本。
    val version = versions.firstOrNull { it.id == (versionId ?: saved.lastOrNull()?.id) } ?: versions.first()
    val cut = saved.firstOrNull { it.id == version.id }
    val isPreview = version.id == PREVIEW_ID
    val state = cut?.let { states[it.id] }
    val running = state is CutExportState.Running
    val anyRunning = states.values.any { it is CutExportState.Running }
    val current = version.clips.getOrNull(clipIndex.coerceIn(0, (version.clips.size - 1).coerceAtLeast(0)))
    val totalSeconds = version.clips.sumOf { it.seconds }
    val select: (String) -> Unit = { versionId = it; clipIndex = 0; playing = false }
    LaunchedEffect(focusCutId) {
        if (focusCutId == null) return@LaunchedEffect
        production.cuts.firstOrNull { it.id == focusCutId }?.let { episodeId = it.episodeId; select(it.id) }
        onFocusHandled()
    }

    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        if (episode != null && episodes.size > 1) {
            item("episode") { EpisodePicker(episodes, episode) { episodeId = it; select(PREVIEW_ID) } }
        }
        item("versions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                val segments = androidx.compose.foundation.rememberScrollState()
                // 默认选中最新版本（位于末尾）时滚到末尾，避免选中段在窄屏上看不见。
                LaunchedEffect(version.id, segments.maxValue) { if (version.id == versions.last().id) segments.scrollTo(segments.maxValue) }
                InfinitySegmentedControl(versions.map { InfinitySegment(it.id, it.title) }, selected = version.id, onSelect = select, modifier = Modifier.weight(1f), scrollState = segments)
                if (controller != null && episode != null) {
                    IconButton(
                        onClick = { launchAction { select(controller.createVersion(sessionId, episode.id).id) } },
                        enabled = !working && saved.size < MAX_CUTS,
                        modifier = Modifier.size(48.dp).testTag(ProductionCutTags.NEW),
                    ) { Icon(Icons.Rounded.Add, stringResource(R.string.p2_new_cut)) }
                }
            }
        }
        item("player") {
            val clip = version.path?.let { ProductionCutClip("", version.id, version.title, totalSeconds, it) } ?: current
            key(version.id, clip?.episodeId, clip?.shotCode, clip?.path) {
                ProductionCutPlayer(clip, playing, onComplete = {
                    if (version.path != null || clipIndex + 1 >= version.clips.size) playing = false else clipIndex++
                }, onStop = { playing = false })
            }
        }
        item("timeline") {
            ProductionSection(null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        androidx.compose.material3.Text(episode?.let { episodeName(it).joinToString(" ") } ?: stringResource(R.string.p2_timeline), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Text(secondsLabel(totalSeconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CutTimeline(production, version, isPreview, onShot)
                }
            }
        }
        item("output") {
            ProductionSection(null) {
                val gaps = version.clips.count { it.path == null }
                ProductionRow(stringResource(R.string.p2_gaps), value = if (isPreview) stringResource(R.string.p2_gaps_placeholder) else stringResource(R.string.p2_gap_count, gaps))
                ProductionRow(stringResource(R.string.p2_specs), valueSegments = listOf(production.aspectRatio ?: "16:9", stringResource(R.string.p3_cut_codec)))
                if (cut != null) {
                    val record = cut.export
                    ProductionRow(stringResource(R.string.p2_output_path), value = (record?.videoPath ?: cutOutputPath(production, cut)).removePrefix(production.rootPath + "/"))
                    if (record != null) {
                        val context = LocalContext.current
                        ProductionRow(stringResource(R.string.p3_cut_file), valueSegments = listOf(secondsLabel(record.durationSeconds), Formatter.formatShortFileSize(context, record.sizeBytes)))
                    }
                    when (state) {
                        is CutExportState.Running -> Column(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                androidx.compose.material3.Text(stringResource(R.string.production_cut_exporting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                state.progress?.let { androidx.compose.material3.Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            ProductionProgressBar(state.progress, Modifier.testTag(ProductionCutTags.PROGRESS))
                        }
                        is CutExportState.Failed -> ProductionRow(stringResource(R.string.p3_cut_status), value = state.reason, chip = ProductionChipSpec(stringResource(R.string.production_cut_failed), ProductionTone.ERROR))
                        else -> Unit
                    }
                }
            }
        }
        item("actions") {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                val play = ProductionButton(stringResource(if (playing) R.string.p2_stop_sequence else R.string.p2_play_sequence), InfinityPillTone.NEUTRAL, version.path != null || version.clips.isNotEmpty(), "p2_cut_play") { playing = !playing; clipIndex = 0 }
                val actions = when {
                    controller == null || isPreview -> listOf(play)
                    running -> listOf(play, ProductionButton(stringResource(R.string.production_cut_cancel), InfinityPillTone.NEUTRAL, true, ProductionCutTags.CANCEL) { scope.launch { controller.cancel(requireNotNull(cut).id) } })
                    cut == null -> listOf(play, ProductionButton(stringResource(R.string.p2_export), InfinityPillTone.PRIMARY, !working && !anyRunning && episode != null && saved.size < MAX_CUTS, "p2_cut_export") {
                        launchAction {
                            val created = controller.createVersion(sessionId, requireNotNull(episode).id)
                            select(created.id)
                            controller.export(sessionId, created.id)
                        }
                    })
                    else -> listOfNotNull(
                        play,
                        cut.export?.let { ProductionButton(stringResource(R.string.p3_cut_share), InfinityPillTone.NEUTRAL, true, ProductionCutTags.SHARE) { shareRequest++ } },
                        ProductionButton(stringResource(if (cut.export == null) R.string.p2_export else R.string.p3_cut_reexport), InfinityPillTone.PRIMARY, !working && !anyRunning, "p2_cut_export") {
                            launchAction { controller.export(sessionId, cut.id) }
                        },
                    )
                }
                ProductionActionRow(actions)
                if (playing && version.path == null && clipIndex + 1 < version.clips.size) ProductionCommand(stringResource(R.string.p2_next_clip)) { clipIndex++ }
            }
        }
        // 保存的剪辑版本可以审核；预演与选定是即时视图，不审核。
        if (cut != null) item("review") {
            ProductionReviewBlock(production, ReviewTarget.cut(cut.id), listOf(version.title), busy, onRun, onSubmit)
        }
        item("packaging") {
            ProductionSection(stringResource(R.string.p2_packaging)) {
                ProductionRow(stringResource(R.string.p2_subtitles), icon = Icons.Rounded.Subtitles, value = stringResource(R.string.p2_subtitles_source))
                if (cut != null && controller != null) {
                    ProductionRow(
                        stringResource(R.string.p3_cut_burn_subtitles),
                        enabled = !working && !running,
                        role = androidx.compose.ui.semantics.Role.Switch,
                        chevron = false,
                        trailing = { InfinitySwitch(cut.burnSubtitles, onCheckedChange = null, enabled = !working && !running) },
                        modifier = Modifier.testTag(ProductionCutTags.BURN),
                        onClick = { launchAction { controller.setBurnSubtitles(sessionId, cut.id, !cut.burnSubtitles) } },
                    )
                }
                ProductionRow(stringResource(R.string.p2_titles_effects), icon = Icons.Rounded.Terminal, value = stringResource(R.string.p2_linux_composite))
            }
        }
        if (cut != null && controller != null) {
            item("delete") {
                ProductionSection(null) {
                    ProductionRow(
                        stringResource(R.string.p3_cut_delete),
                        titleColor = MaterialTheme.colorScheme.error,
                        enabled = !working && !running,
                        chevron = false,
                        modifier = Modifier.testTag(ProductionCutTags.DELETE),
                        onClick = { confirmDelete = cut },
                    )
                }
            }
        }
    }
    confirmDelete?.let { target ->
        InfinityConfirmDialog(
            title = stringResource(R.string.p3_cut_delete),
            message = savedTitle.format(target.number),
            confirmText = stringResource(R.string.p3_cut_delete),
            onConfirm = {
                confirmDelete = null
                controller?.let { c -> launchAction { c.deleteVersion(sessionId, target.id); select(SELECTED_ID) } }
            },
            onCancel = { confirmDelete = null },
            isDestructive = true,
        )
    }
    cut?.export?.let { record -> CutShareEffect(record.videoPath, shareRequest) }
}

/** 分享按钮递增 [request]；解析到工作区文件后对新的请求发起系统分享。 */
@Composable
private fun CutShareEffect(path: String, request: Int) {
    val environment = LocalWorkspaceMedia.current ?: return
    val mapped = rememberProductionPath(environment, path) as? ProductionPathState.Ready ?: return
    val resolution = rememberWorkspaceMediaResolution(environment, mapped.path)?.resolution as? MediaResolution.Available ?: return
    val runner = rememberMediaActionRunner(environment)
    var handled by remember(path) { mutableIntStateOf(request) }
    LaunchedEffect(request) {
        if (request != handled) { handled = request; runner.share(resolution) }
    }
}

private fun cutOutputPath(production: Production, cut: CutVersion) =
    com.promenar.infinity.data.production.ProductionCuts.videoPath(production, cut)

private const val MAX_CUTS = com.promenar.infinity.data.production.ProductionCuts.MAX_VERSIONS_PER_EPISODE

internal object ProductionCutTags {
    const val NEW = "p3_cut_new"
    const val SHARE = "p3_cut_share"
    const val CANCEL = "p3_cut_cancel"
    const val BURN = "p3_cut_burn"
    const val DELETE = "p3_cut_delete"
    const val PROGRESS = "p3_cut_progress"
}
