package com.promenar.infinity.ui.production

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.PrevisScene
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.defaultPrevisStillPath
import com.promenar.infinity.data.production.defaultPrevisVideoPath
import com.promenar.infinity.data.production.previsExportDimensions
import com.promenar.infinity.data.production.totalDurationSeconds
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySplitPillButton
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberMediaActionRunner
import com.promenar.infinity.ui.media.rememberWorkspaceMediaResolution
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val PREVIEW_ID = "preview"
private const val SELECTED_ID = "selected"

/** 剪辑页：即时视图“分镜预演/选定”与按集保存的“粗剪 vN”共用一体化监视器、常驻播控条与时间线。 */
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
    focusPreview: Boolean = false,
    onFocusPreviewHandled: () -> Unit = {},
    snapshotRevision: Long = 0L,
) {
    val context = LocalContext.current
    val globalNotice = LocalInfinityNoticeController.current
    val animationsEnabled = rememberAnimationsEnabled()
    val episodes = production.episodes()
    var episodeId by rememberSaveable(production.id) { mutableStateOf(production.defaultEpisodeId()) }
    val episode = episodes.firstOrNull { it.id == episodeId } ?: episodes.firstOrNull()
    var versionId by rememberSaveable(production.id) { mutableStateOf<String?>(null) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var clipIndex by rememberSaveable { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CutVersion?>(null) }
    var shareRequest by remember { mutableIntStateOf(0) }
    var sharePrevisRequest by remember { mutableIntStateOf(0) }

    // 3D 白模预演状态
    var previsScene by remember { mutableStateOf<PrevisScene?>(null) }
    var previsShotIndex by rememberSaveable(production.id, episode?.id) { mutableIntStateOf(0) }
    var previsLocalTime by remember { mutableDoubleStateOf(0.0) }
    var previsSeekGen by remember { mutableIntStateOf(0) }
    var previsGlobalTime by remember { mutableDoubleStateOf(0.0) }
    var previsViewMode by rememberSaveable { mutableStateOf(PrevisViewMode.CAMERA) }
    var previsShaderMode by rememberSaveable { mutableStateOf(PrevisShaderMode.CLAY) }
    var showThirdsGrid by rememberSaveable { mutableStateOf(true) }
    var showAxisGuide by rememberSaveable { mutableStateOf(true) }
    var fullscreenPrevis by remember { mutableStateOf(false) }
    var capturingStill by remember { mutableStateOf(false) }
    var exportingPrevisVideo by remember { mutableStateOf(false) }
    var previsExportProgress by remember { mutableFloatStateOf(0f) }
    var previsExportJob by remember { mutableStateOf<Job?>(null) }
    var previsVideoFailed by remember { mutableStateOf<String?>(null) }
    var exportedPrevisVideoPath by rememberSaveable(production.id, episode?.id) { mutableStateOf<String?>(null) }
    val previsController = remember { PrevisViewportController() }

    val controller = services.cutExport?.takeIf { sessionId.isNotBlank() }
    val states by (controller?.observe() ?: remember { MutableStateFlow(emptyMap()) }).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fallback = stringResource(R.string.production_panel_action_failed)
    fun showInfoNotice(msg: String) {
        if (globalNotice != null) globalNotice.show(InfinityNoticeSeverity.INFO, msg)
        else onError(null)
    }

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
    // 未手动选择时停在该集最新的粗剪版本（若无粗剪或从 3D 预演直达则落到 PREVIEW_ID）。
    val version = versions.firstOrNull { it.id == (versionId ?: saved.lastOrNull()?.id) } ?: versions.first()
    val cut = saved.firstOrNull { it.id == version.id }
    val isPreview = version.id == PREVIEW_ID
    val state = cut?.let { states[it.id] }
    val running = state is CutExportState.Running
    val anyRunning = states.values.any { it is CutExportState.Running }
    val current = version.clips.getOrNull(clipIndex.coerceIn(0, (version.clips.size - 1).coerceAtLeast(0)))
    val totalSeconds = version.clips.sumOf { it.seconds }
    val isWholeCutVideo = version.path != null
    val activeClipIndex = if (!isWholeCutVideo && version.clips.isNotEmpty()) clipIndex.coerceIn(0, version.clips.lastIndex) else null
    val select: (String) -> Unit = {
        versionId = it
        clipIndex = 0
        playing = false
        previsShotIndex = 0
        previsLocalTime = 0.0
        previsGlobalTime = 0.0
        previsSeekGen++
    }

    LaunchedEffect(focusCutId) {
        if (focusCutId == null) return@LaunchedEffect
        production.cuts.firstOrNull { it.id == focusCutId }?.let { episodeId = it.episodeId; select(it.id) }
        onFocusHandled()
    }

    LaunchedEffect(focusPreview) {
        if (!focusPreview) return@LaunchedEffect
        val targetEpisode = episodes.firstOrNull { !it.previsPath.isNullOrBlank() } ?: episode
        if (targetEpisode != null) episodeId = targetEpisode.id
        select(PREVIEW_ID)
        onFocusPreviewHandled()
    }

    val previsPath = episode?.previsPath
    val previsLoadErrorText = stringResource(R.string.p3_previs_load_failed)
    LaunchedEffect(sessionId, episode?.id, previsPath, snapshotRevision) {
        if (previsPath.isNullOrBlank() || sessionId.isBlank() || services.previsStore == null) {
            previsScene = null
            return@LaunchedEffect
        }
        previsScene = try {
            services.previsStore.load(sessionId, previsPath, episode = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error.message ?: previsLoadErrorText)
            null
        }
    }

    val activePrevis = previsScene?.takeIf { isPreview && !previsPath.isNullOrBlank() && it.shots.isNotEmpty() }
    val previsOffsets = remember(activePrevis) {
        val shotsList = activePrevis?.shots.orEmpty()
        var acc = 0.0
        shotsList.map { s -> acc.also { acc += s.duration } }
    }

    fun selectPrevisShot(targetIdx: Int, resetLocalTime: Boolean = true) {
        val list = activePrevis?.shots.orEmpty()
        if (list.isEmpty()) return
        val clamped = targetIdx.coerceIn(0, list.lastIndex)
        previsShotIndex = clamped
        if (resetLocalTime) {
            previsLocalTime = 0.0
            previsGlobalTime = previsOffsets.getOrElse(clamped) { 0.0 }
            previsSeekGen++
        }
        val matchIdx = version.clips.indexOfFirst { it.shotCode == list[clamped].code }
        if (matchIdx >= 0) clipIndex = matchIdx
    }

    val frameFailedText = stringResource(R.string.p3_previs_frame_failed)
    val savedFrameTemplate = stringResource(R.string.p3_previs_saved_frame)
    val attachedPreviewText = stringResource(R.string.p3_previs_attached_preview)
    val attachedDepthText = stringResource(R.string.p3_previs_attached_depth)
    val beautyRefLabel = stringResource(R.string.p3_previs_ref_beauty_label)
    val depthRefLabel = stringResource(R.string.p3_previs_ref_depth_label)
    val videoFailedText = stringResource(R.string.p3_previs_video_failed)
    val savedVideoTemplate = stringResource(R.string.p3_previs_saved_video)
    val suggestionAddedText = stringResource(R.string.p3_previs_suggest_added)

    fun captureAndHandleStill(mode: PrevisShaderMode, attachRole: InputRole?, attachAsPreview: Boolean) {
        val scene = activePrevis ?: return
        val ep = episode ?: return
        val previsStore = services.previsStore ?: return
        if (capturingStill || exportingPrevisVideo) return
        val shot = scene.shots.getOrNull(previsShotIndex.coerceIn(0, scene.shots.lastIndex)) ?: return
        val (w, h) = previsExportDimensions(production.aspectRatio)
        capturingStill = true
        scope.launch {
            try {
                val wasPlaying = playing
                if (wasPlaying) playing = false
                val png = previsController.captureStillPng(
                    shotIndex = previsShotIndex.coerceIn(0, scene.shots.lastIndex),
                    localTime = previsLocalTime,
                    shaderMode = mode,
                    width = w,
                    height = h,
                )
                val outPath = defaultPrevisStillPath(production.rootPath, ep.id, shot.code, isDepth = mode == PrevisShaderMode.DEPTH)
                previsStore.savePng(sessionId, outPath, png)
                onError(null)
                if (attachAsPreview) {
                    val err = onSubmit(ProductionUserAction.AttachPrevisFrame(ep.id, shot.code, outPath, inputRole = null))
                    if (err != null) onError(err) else showInfoNotice(attachedPreviewText)
                } else if (attachRole != null) {
                    val label = if (mode == PrevisShaderMode.DEPTH) depthRefLabel else beautyRefLabel
                    val err = onSubmit(ProductionUserAction.AttachPrevisFrame(ep.id, shot.code, outPath, inputRole = attachRole, label = label))
                    if (err != null) onError(err) else showInfoNotice(attachedDepthText)
                } else {
                    showInfoNotice(savedFrameTemplate.format(outPath.removePrefix(production.rootPath + "/")))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onError(e.message ?: frameFailedText)
            } finally {
                capturingStill = false
            }
        }
    }

    fun startOrCancelVideoExport() {
        if (exportingPrevisVideo) {
            previsExportJob?.cancel()
            previsExportJob = null
            exportingPrevisVideo = false
            return
        }
        val scene = activePrevis ?: return
        val ep = episode ?: return
        val previsStore = services.previsStore ?: return
        if (capturingStill) return
        val (w, h) = previsExportDimensions(production.aspectRatio)
        playing = false
        previsVideoFailed = null
        exportingPrevisVideo = true
        previsExportProgress = 0f
        previsExportJob = scope.launch {
            val tempFile = File(context.cacheDir, "previs-export-${ep.id}-${System.currentTimeMillis()}.mp4")
            try {
                previsController.exportEpisodeMp4(
                    scene = scene,
                    width = w,
                    height = h,
                    fps = 24,
                    outputFile = tempFile,
                    onProgress = { p -> previsExportProgress = p },
                )
                val outPath = defaultPrevisVideoPath(production.rootPath, ep.id)
                previsStore.saveVideo(sessionId, outPath, tempFile)
                exportedPrevisVideoPath = outPath
                onError(null)
                showInfoNotice(savedVideoTemplate.format(outPath.removePrefix(production.rootPath + "/")))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val msg = e.message ?: videoFailedText
                previsVideoFailed = msg
                onError(msg)
            } finally {
                exportingPrevisVideo = false
                previsExportJob = null
                runCatching { tempFile.delete() }
            }
        }
    }

    val clampedClipIdx = (activeClipIndex ?: 0).coerceIn(0, (version.clips.size - 1).coerceAtLeast(0))
    val monitorShotIdx = if (activePrevis != null) previsShotIndex.coerceIn(0, activePrevis.shots.lastIndex) else clampedClipIdx
    val monitorTotalShots = activePrevis?.shots?.size ?: if (isWholeCutVideo) 0 else version.clips.size
    val monitorTotalDur = activePrevis?.totalDurationSeconds() ?: totalSeconds
    val monitorCurDur = if (activePrevis != null) {
        previsGlobalTime.coerceIn(0.0, monitorTotalDur)
    } else if (isWholeCutVideo) {
        totalSeconds
    } else {
        version.clips.take(clampedClipIdx).sumOf { it.seconds }
    }
    val monitorCanPrev = if (activePrevis != null) monitorShotIdx > 0 else !isWholeCutVideo && clampedClipIdx > 0
    val monitorCanNext = if (activePrevis != null) monitorShotIdx + 1 < activePrevis.shots.size else !isWholeCutVideo && clampedClipIdx + 1 < version.clips.size
    val monitorPlayEnabled = if (activePrevis != null) !exportingPrevisVideo else (version.path != null || version.clips.isNotEmpty())

    val currentPrevisShot = activePrevis?.shots?.getOrNull(monitorShotIdx)
    val cameraTelemetry = currentPrevisShot?.let { pShot ->
        val lens = pShot.camera.focalLengthMm?.let { "${it.toInt()}mm" }
            ?: pShot.camera.fov?.let { "${it.toInt()}°" }
        val storyboardShot = shots[pShot.code]?.shot
        val modeLabel = if (previsShaderMode == PrevisShaderMode.DEPTH) stringResource(R.string.p3_previs_depth) else storyboardShot?.cameraMovement
        listOfNotNull(lens, pShot.label ?: storyboardShot?.title, modeLabel).joinToString(" · ") // wrap-check: allow 3D 视口左下角机位遥测胶囊固定单行省略
    }

    LazyColumn(contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        if (episode != null && episodes.size > 1) {
            item("episode") { EpisodePicker(episodes, episode) { episodeId = it; select(PREVIEW_ID) } }
        }
        item("versions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                val segments = androidx.compose.foundation.rememberScrollState()
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
            UnifiedCutMonitorCard(
                shotIndex = monitorShotIdx,
                totalShots = monitorTotalShots,
                playing = playing,
                localTimeSeconds = previsLocalTime,
                seekGeneration = previsSeekGen,
                currentTimeSeconds = monitorCurDur,
                totalDurationSeconds = monitorTotalDur,
                canPrev = monitorCanPrev,
                canNext = monitorCanNext,
                playEnabled = monitorPlayEnabled,
                onPrev = {
                    if (activePrevis != null) selectPrevisShot(monitorShotIdx - 1)
                    else if (clampedClipIdx > 0) clipIndex = clampedClipIdx - 1
                },
                onTogglePlay = { playing = !playing },
                onNext = {
                    if (activePrevis != null) selectPrevisShot(monitorShotIdx + 1)
                    else if (clampedClipIdx + 1 < version.clips.size) clipIndex = clampedClipIdx + 1
                },
                previsScene = activePrevis?.takeUnless { fullscreenPrevis },
                previsController = previsController,
                viewMode = previsViewMode,
                shaderMode = previsShaderMode,
                showThirdsGrid = showThirdsGrid,
                cameraTelemetry = cameraTelemetry,
                onToggleViewMode = {
                    previsViewMode = if (previsViewMode == PrevisViewMode.CAMERA) PrevisViewMode.ORBIT else PrevisViewMode.CAMERA
                },
                onToggleShaderMode = {
                    previsShaderMode = if (previsShaderMode == PrevisShaderMode.CLAY) PrevisShaderMode.DEPTH else PrevisShaderMode.CLAY
                },
                onToggleGrid = { showThirdsGrid = !showThirdsGrid },
                onOpenFullscreen = { fullscreenPrevis = true },
                onPrevisTick = { idx, local, global, isPlaying ->
                    previsShotIndex = idx
                    previsLocalTime = local
                    previsGlobalTime = global
                    playing = isPlaying
                    val code = activePrevis?.shots?.getOrNull(idx)?.code
                    if (code != null) {
                        val timelineIdx = version.clips.indexOfFirst { it.shotCode == code }
                        if (timelineIdx >= 0 && timelineIdx != clipIndex) clipIndex = timelineIdx
                    }
                },
                onPrevisComplete = { playing = false },
                onPrevisError = { onError(it) },
                fallbackContent = {
                    if (activePrevis != null) {
                        // 全屏弹窗打开时内联视口保持同尺寸 16:9 占位，不与全屏抢占 WebGL 上下文
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    } else {
                        key(version.id, clip?.episodeId, clip?.shotCode, clip?.path) {
                            ProductionCutPlayer(
                                clip = clip,
                                playing = playing,
                                onComplete = {
                                    if (version.path != null || clipIndex + 1 >= version.clips.size) playing = false else clipIndex++
                                },
                                onStop = { playing = false },
                                clipIndex = activeClipIndex ?: 0,
                                totalClips = if (isWholeCutVideo) 0 else version.clips.size,
                                isPreviewMode = isPreview,
                                embeddedInMonitor = true,
                            )
                        }
                    }
                },
            )
        }
        item("timeline") {
            ProductionSection(null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(episode?.let { episodeDisplayName(production, it).joinToString(" ") } ?: stringResource(R.string.p2_timeline), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(secondsLabel(monitorTotalDur), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CutTimeline(
                        production = production,
                        version = version,
                        isPreview = isPreview,
                        onShot = onShot,
                        activeClipIndex = activeClipIndex,
                        onSelectClip = if (activePrevis != null) { idx ->
                            if (idx != activeClipIndex) {
                                val targetCode = version.clips.getOrNull(idx)?.shotCode
                                val previsIdx = activePrevis.shots.indexOfFirst { it.code == targetCode }
                                if (previsIdx >= 0) selectPrevisShot(previsIdx)
                                clipIndex = idx
                                playing = false
                            } else {
                                val targetClip = version.clips.getOrNull(idx)
                                if (targetClip != null) onShot(targetClip.episodeId, targetClip.shotCode)
                            }
                        } else null,
                    )
                }
            }
        }
        item("output") {
            ProductionSection(null) {
                if (activePrevis != null) {
                    val (expW, expH) = previsExportDimensions(production.aspectRatio)
                    val coveredCount = version.clips.count { clip -> activePrevis.shots.any { it.code == clip.shotCode } }
                    val totalEpShots = version.clips.size.coerceAtLeast(activePrevis.shots.size)
                    ProductionRow(
                        title = stringResource(R.string.p3_previs_coverage),
                        value = stringResource(R.string.p3_previs_coverage_count, coveredCount, totalEpShots),
                    )
                    ProductionRow(
                        title = stringResource(R.string.p3_previs_export_specs),
                        valueSegments = listOf("${expW}×${expH}", "24fps", "H.264"),
                    )
                    exportedPrevisVideoPath?.let { path ->
                        ProductionRow(
                            title = stringResource(R.string.p3_previs_animatic_file),
                            value = path.removePrefix(production.rootPath + "/"),
                            trailing = {
                                IconButton(
                                    onClick = { sharePrevisRequest++ },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.Share, stringResource(R.string.p3_cut_share), modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = { sharePrevisRequest++ },
                        )
                    }
                    if (animationsEnabled) {
                        val progressEnter = expandVertically() + fadeIn()
                        val progressExit = shrinkVertically() + fadeOut()
                        AnimatedVisibility(
                            visible = exportingPrevisVideo,
                            enter = progressEnter,
                            exit = progressExit,
                        ) {
                            Column(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(stringResource(R.string.p3_previs_exporting_video), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${(previsExportProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ProductionProgressBar(previsExportProgress, Modifier.testTag(ProductionCutTags.PROGRESS))
                            }
                        }
                    } else if (exportingPrevisVideo) {
                        Column(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(stringResource(R.string.p3_previs_exporting_video), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("${(previsExportProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ProductionProgressBar(previsExportProgress, Modifier.testTag(ProductionCutTags.PROGRESS))
                        }
                    }
                    previsVideoFailed?.takeIf { !exportingPrevisVideo }?.let { reason ->
                        ProductionRow(
                            title = stringResource(R.string.p3_cut_status),
                            value = reason,
                            chip = ProductionChipSpec(stringResource(R.string.production_cut_failed), ProductionTone.ERROR),
                        )
                    }
                } else {
                    val gaps = version.clips.count { it.path == null }
                    ProductionRow(
                        stringResource(R.string.p2_gaps),
                        value = when {
                            isPreview && gaps > 0 -> stringResource(R.string.p2_gaps_preview_count, gaps)
                            isPreview -> stringResource(R.string.p2_gaps_placeholder)
                            else -> stringResource(R.string.p2_gap_count, gaps)
                        },
                    )
                    ProductionRow(stringResource(R.string.p2_specs), valueSegments = listOf(production.aspectRatio ?: "16:9", stringResource(R.string.p3_cut_codec)))
                    if (cut != null) {
                        val record = cut.export
                        ProductionRow(stringResource(R.string.p2_output_path), value = (record?.videoPath ?: cutOutputPath(production, cut)).removePrefix(production.rootPath + "/"))
                        if (record != null) {
                            ProductionRow(stringResource(R.string.p3_cut_file), valueSegments = listOf(secondsLabel(record.durationSeconds), Formatter.formatShortFileSize(context, record.sizeBytes)))
                        }
                        if (animationsEnabled) {
                            AnimatedVisibility(visible = running) {
                                Column(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row {
                                        Text(stringResource(R.string.production_cut_exporting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        (state as? CutExportState.Running)?.progress?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    ProductionProgressBar((state as? CutExportState.Running)?.progress, Modifier.testTag(ProductionCutTags.PROGRESS))
                                }
                            }
                        } else if (running) {
                            Column(Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(stringResource(R.string.production_cut_exporting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    (state as? CutExportState.Running)?.progress?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                ProductionProgressBar((state as? CutExportState.Running)?.progress, Modifier.testTag(ProductionCutTags.PROGRESS))
                            }
                        }
                        if (state is CutExportState.Failed) {
                            ProductionRow(stringResource(R.string.p3_cut_status), value = state.reason, chip = ProductionChipSpec(stringResource(R.string.production_cut_failed), ProductionTone.ERROR))
                        }
                    }
                }
            }
        }
        // 常驻等宽操作栏：播控已内置上方监视器 48dp 底栏，操作区只放帧/成片导出与分享，绝不出现单按钮右对齐孤悬或条件挤出。
        when {
            activePrevis != null -> item("actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfinitySplitPillButton(
                        text = stringResource(R.string.p3_previs_export_frame),
                        onClick = { captureAndHandleStill(PrevisShaderMode.CLAY, attachRole = null, attachAsPreview = false) },
                        moreContentDescription = stringResource(R.string.p3_previs_export_frame_more),
                        modifier = Modifier.weight(1f),
                        tone = InfinityPillTone.NEUTRAL,
                        enabled = !capturingStill && !exportingPrevisVideo,
                        icon = Icons.Rounded.PhotoCamera,
                    ) { dismiss ->
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.p3_previs_export_beauty_png)) },
                            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)) },
                            onClick = { dismiss(); captureAndHandleStill(PrevisShaderMode.CLAY, null, false) },
                        )
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.p3_previs_export_depth_png)) },
                            leadingIcon = { Icon(Icons.Rounded.Layers, null, Modifier.size(18.dp)) },
                            onClick = { dismiss(); captureAndHandleStill(PrevisShaderMode.DEPTH, null, false) },
                        )
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.p3_previs_attach_preview)) },
                            leadingIcon = { Icon(Icons.Rounded.Slideshow, null, Modifier.size(18.dp)) },
                            onClick = { dismiss(); captureAndHandleStill(PrevisShaderMode.CLAY, null, true) },
                        )
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.p3_previs_attach_depth)) },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                            onClick = { dismiss(); captureAndHandleStill(PrevisShaderMode.DEPTH, InputRole.REFERENCE, false) },
                        )
                    }
                    InfinityPillButton(
                        text = stringResource(if (exportingPrevisVideo) R.string.p3_previs_export_cancel else R.string.p3_previs_export_video),
                        onClick = { startOrCancelVideoExport() },
                        modifier = Modifier.weight(1f),
                        tone = if (exportingPrevisVideo) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                        enabled = exportingPrevisVideo || !capturingStill,
                        icon = Icons.Rounded.Movie,
                    )
                }
            }

            controller != null && !isPreview -> item("actions") {
                when {
                    running -> InfinityPillButton(
                        text = stringResource(R.string.production_cut_cancel),
                        onClick = { scope.launch { controller.cancel(requireNotNull(cut).id) } },
                        tone = InfinityPillTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth().testTag(ProductionCutTags.CANCEL),
                    )
                    cut == null -> InfinityPillButton(
                        text = stringResource(R.string.p2_export),
                        onClick = {
                            launchAction {
                                val created = controller.createVersion(sessionId, requireNotNull(episode).id)
                                select(created.id)
                                controller.export(sessionId, created.id)
                            }
                        },
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !working && !anyRunning && episode != null && saved.size < MAX_CUTS,
                        modifier = Modifier.fillMaxWidth().testTag("p2_cut_export"),
                    )
                    cut.export != null -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfinityPillButton(
                            text = stringResource(R.string.p3_cut_share),
                            onClick = { shareRequest++ },
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Share,
                            modifier = Modifier.weight(1f).testTag(ProductionCutTags.SHARE),
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.p3_cut_reexport),
                            onClick = { launchAction { controller.export(sessionId, cut.id) } },
                            tone = InfinityPillTone.PRIMARY,
                            enabled = !working && !anyRunning,
                            modifier = Modifier.weight(1f).testTag("p2_cut_export"),
                        )
                    }
                    else -> InfinityPillButton(
                        text = stringResource(R.string.p2_export),
                        onClick = { launchAction { controller.export(sessionId, cut.id) } },
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !working && !anyRunning,
                        modifier = Modifier.fillMaxWidth().testTag("p2_cut_export"),
                    )
                }
            }
        }
        // 保存的剪辑版本可以审核；预演与选定是即时视图，不审核。
        if (cut != null) item("review") {
            ProductionReviewCard(
                title = stringResource(R.string.production_review_card_title, version.title),
                production = production,
                target = ReviewTarget.cut(cut.id),
                label = listOf(version.title),
                busy = busy,
                onRun = onRun,
                onSubmit = onSubmit,
            )
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

    if (fullscreenPrevis && activePrevis != null) {
        ProductionPrevisFullscreenDialog(
            scene = activePrevis,
            controller = previsController,
            shotIndex = monitorShotIdx,
            localTimeSeconds = previsLocalTime,
            seekGeneration = previsSeekGen,
            playing = playing,
            currentTimeSeconds = monitorCurDur,
            viewMode = previsViewMode,
            shaderMode = previsShaderMode,
            showThirdsGrid = showThirdsGrid,
            showAxisGuide = showAxisGuide,
            busyCapture = capturingStill || exportingPrevisVideo,
            onDismiss = {
                fullscreenPrevis = false
                previsSeekGen++
            },
            onSelectShot = { selectPrevisShot(it) },
            onTogglePlay = { playing = !playing },
            onToggleViewMode = {
                previsViewMode = if (previsViewMode == PrevisViewMode.CAMERA) PrevisViewMode.ORBIT else PrevisViewMode.CAMERA
            },
            onToggleShaderMode = {
                previsShaderMode = if (previsShaderMode == PrevisShaderMode.CLAY) PrevisShaderMode.DEPTH else PrevisShaderMode.CLAY
            },
            onToggleGrid = { showThirdsGrid = !showThirdsGrid },
            onToggleAxis = { showAxisGuide = !showAxisGuide },
            onCaptureStill = { mode -> captureAndHandleStill(mode, attachRole = null, attachAsPreview = false) },
            onSubmitSuggestion = { code, note ->
                episode?.let { ep ->
                    onRun(ProductionUserAction.Review(ReviewTarget.shot(ep.id, code), ReviewKind.SUGGEST, note))
                    showInfoNotice(suggestionAddedText)
                }
            },
            onTick = { idx, local, global, isPlaying ->
                previsShotIndex = idx
                previsLocalTime = local
                previsGlobalTime = global
                playing = isPlaying
                val code = activePrevis.shots.getOrNull(idx)?.code
                if (code != null) {
                    val timelineIdx = version.clips.indexOfFirst { it.shotCode == code }
                    if (timelineIdx >= 0 && timelineIdx != clipIndex) clipIndex = timelineIdx
                }
            },
            onComplete = { playing = false },
            onEngineError = { onError(it) },
        )
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
    exportedPrevisVideoPath?.let { path -> CutShareEffect(path, sharePrevisRequest) }
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
    const val PREV_CLIP = "p3_cut_prev"
    const val NEXT_CLIP = "p3_cut_next"
}
