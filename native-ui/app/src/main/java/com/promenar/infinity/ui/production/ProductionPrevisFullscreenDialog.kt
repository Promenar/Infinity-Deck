package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.data.production.PrevisScene
import com.promenar.infinity.data.production.totalDurationSeconds
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput

/**
 * 全屏 3D 白模预演与自由视角/180° 轴线检查弹窗。
 * 视口模态固定采用深色暗房主题以突出 3D 素模与线性深度灰阶对比，建议输入弹窗保持宿主主题。
 */
@Composable
internal fun ProductionPrevisFullscreenDialog(
    scene: PrevisScene,
    controller: PrevisViewportController,
    shotIndex: Int,
    localTimeSeconds: Double,
    seekGeneration: Int,
    playing: Boolean,
    currentTimeSeconds: Double,
    viewMode: PrevisViewMode,
    shaderMode: PrevisShaderMode,
    showThirdsGrid: Boolean,
    showAxisGuide: Boolean,
    busyCapture: Boolean,
    onDismiss: () -> Unit,
    onSelectShot: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleShaderMode: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleAxis: () -> Unit,
    onCaptureStill: (PrevisShaderMode) -> Unit,
    onSubmitSuggestion: (shotCode: String, note: String) -> Unit,
    onTick: (Int, Double, Double, Boolean) -> Unit,
    onComplete: () -> Unit,
    onEngineError: (String) -> Unit,
) {
    val shots = scene.shots
    val clampedIndex = shotIndex.coerceIn(0, (shots.size - 1).coerceAtLeast(0))
    val currentShot = shots.getOrNull(clampedIndex)
    var suggestDialogShot by remember { mutableStateOf<String?>(null) }
    var suggestText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        InfinityTheme(
            preferences = InfinityThemePreferences(
                mode = InfinityThemeMode.DARK,
                colorSource = InfinityColorSource.INFINITY,
            ),
        ) {
            val scheme = MaterialTheme.colorScheme
            Surface(
                color = scheme.background,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 顶栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.p3_previs_exit_fullscreen),
                                tint = scheme.onSurface,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            val prefix = stringResource(
                                R.string.p3_previs_shot_label_prefix,
                                clampedIndex + 1,
                                shots.size.coerceAtLeast(1),
                            )
                            val titleSegments = listOfNotNull(
                                prefix,
                                currentShot?.code,
                                currentShot?.label,
                            )
                            InfinityMetaLine(
                                segments = titleSegments,
                                style = InfinityTypography.titleSmall,
                                color = scheme.onSurface,
                            )
                            val lensLabel = currentShot?.camera?.focalLengthMm?.let { "${it.toInt()}mm" }
                                ?: currentShot?.camera?.fov?.let { "${it.toInt()}° FOV" }
                            val meta = listOfNotNull(
                                lensLabel,
                                currentShot?.duration?.let { secondsLabel(it) },
                                if (viewMode == PrevisViewMode.ORBIT && showAxisGuide) {
                                    stringResource(R.string.p3_previs_axis)
                                } else null,
                            )
                            if (meta.isNotEmpty()) {
                                InfinityMetaLine(
                                    segments = meta,
                                    style = InfinityTypography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrevisOverlayPill(
                                text = stringResource(
                                    if (viewMode == PrevisViewMode.ORBIT) R.string.p3_previs_orbit_view else R.string.p3_previs_camera_view,
                                ),
                                highlighted = viewMode == PrevisViewMode.ORBIT,
                                onClick = onToggleViewMode,
                            )
                            PrevisOverlayPill(
                                text = stringResource(
                                    if (shaderMode == PrevisShaderMode.DEPTH) R.string.p3_previs_depth_short else R.string.p3_previs_clay_short,
                                ),
                                highlighted = shaderMode == PrevisShaderMode.DEPTH,
                                onClick = onToggleShaderMode,
                            )
                            PrevisOverlayPill(
                                text = stringResource(R.string.p3_previs_axis),
                                highlighted = viewMode == PrevisViewMode.ORBIT && showAxisGuide,
                                onClick = onToggleAxis,
                            )
                            PrevisOverlayIconButton(
                                icon = Icons.Rounded.GridView,
                                active = showThirdsGrid,
                                contentDescription = stringResource(R.string.p3_previs_grid),
                                onClick = onToggleGrid,
                            )
                        }
                    }

                    // 中央 3D 视口
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    ) {
                        PrevisStageViewport(
                            scene = scene,
                            controller = controller,
                            shotIndex = clampedIndex,
                            localTime = localTimeSeconds,
                            seekGeneration = seekGeneration,
                            playing = playing,
                            viewMode = viewMode,
                            shaderMode = shaderMode,
                            showThirdsGrid = showThirdsGrid,
                            showAxisGuide = showAxisGuide,
                            onTick = onTick,
                            onComplete = onComplete,
                            onEngineError = onEngineError,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (viewMode == PrevisViewMode.ORBIT) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                            ) {
                                PrevisOverlayPill(text = stringResource(R.string.p3_previs_orbit_hint))
                            }
                        }
                    }

                    // 底部常驻分镜切换条 + 播控 + 快捷抓取/提意见
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            shots.forEachIndexed { idx, shot ->
                                val selected = idx == clampedIndex
                                Surface(
                                    onClick = { onSelectShot(idx) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        width = if (selected) 1.dp else 0.5.dp,
                                        color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.45f),
                                    ),
                                    modifier = Modifier
                                        .widthIn(min = 96.dp)
                                        .height(36.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                    ) {
                                        Text(
                                            text = "${idx + 1} ${shot.label ?: shot.code} ${secondsLabel(shot.duration)}",
                                            style = InfinityTypography.labelMedium,
                                            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        val canPrev = clampedIndex > 0
                        val canNext = clampedIndex + 1 < shots.size
                        val totalDur = scene.totalDurationSeconds()
                        val playLabel = if (playing) {
                            "${stringResource(R.string.p3_previs_stop_short)} · ${formatTimecodeRatio(currentTimeSeconds, totalDur)}" // wrap-check: allow 全屏停止胶囊固定单行时间码
                        } else {
                            stringResource(R.string.p3_previs_play_short)
                        }

                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val singleRow = maxWidth >= 560.dp
                            if (singleRow) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    IconButton(
                                        onClick = { if (canPrev) onSelectShot(clampedIndex - 1) },
                                        enabled = canPrev,
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipPrevious,
                                            contentDescription = stringResource(R.string.p3_previs_prev_shot),
                                            tint = if (canPrev) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                                        )
                                    }
                                    InfinityPillButton(
                                        text = playLabel,
                                        onClick = onTogglePlay,
                                        tone = InfinityPillTone.PRIMARY,
                                        icon = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        compact = true,
                                    )
                                    IconButton(
                                        onClick = { if (canNext) onSelectShot(clampedIndex + 1) },
                                        enabled = canNext,
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = stringResource(R.string.p2_next_clip),
                                            tint = if (canNext) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    InfinityPillButton(
                                        text = stringResource(R.string.p3_previs_capture_beauty),
                                        onClick = { onCaptureStill(PrevisShaderMode.CLAY) },
                                        enabled = !busyCapture,
                                        icon = Icons.Rounded.PhotoCamera,
                                        compact = true,
                                    )
                                    InfinityPillButton(
                                        text = stringResource(R.string.p3_previs_capture_depth),
                                        onClick = { onCaptureStill(PrevisShaderMode.DEPTH) },
                                        enabled = !busyCapture,
                                        icon = Icons.Rounded.Layers,
                                        compact = true,
                                    )
                                    InfinityPillButton(
                                        text = stringResource(R.string.p3_previs_suggest),
                                        onClick = { currentShot?.let { suggestDialogShot = it.code } },
                                        enabled = currentShot != null,
                                        icon = Icons.Rounded.RateReview,
                                        compact = true,
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        IconButton(
                                            onClick = { if (canPrev) onSelectShot(clampedIndex - 1) },
                                            enabled = canPrev,
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SkipPrevious,
                                                contentDescription = stringResource(R.string.p3_previs_prev_shot),
                                                tint = if (canPrev) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                                            )
                                        }
                                        InfinityPillButton(
                                            text = stringResource(if (playing) R.string.p3_previs_stop_short else R.string.p3_previs_play_short),
                                            onClick = onTogglePlay,
                                            tone = InfinityPillTone.PRIMARY,
                                            icon = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                            compact = true,
                                        )
                                        IconButton(
                                            onClick = { if (canNext) onSelectShot(clampedIndex + 1) },
                                            enabled = canNext,
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SkipNext,
                                                contentDescription = stringResource(R.string.p2_next_clip),
                                                tint = if (canNext) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            text = formatTimecodeRatio(currentTimeSeconds, totalDur),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = InfinityMonospace,
                                                fontFeatureSettings = "tnum",
                                            ),
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        InfinityPillButton(
                                            text = stringResource(R.string.p3_previs_capture_beauty),
                                            onClick = { onCaptureStill(PrevisShaderMode.CLAY) },
                                            enabled = !busyCapture,
                                            icon = Icons.Rounded.PhotoCamera,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        InfinityPillButton(
                                            text = stringResource(R.string.p3_previs_capture_depth),
                                            onClick = { onCaptureStill(PrevisShaderMode.DEPTH) },
                                            enabled = !busyCapture,
                                            icon = Icons.Rounded.Layers,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        InfinityPillButton(
                                            text = stringResource(R.string.p3_previs_suggest),
                                            onClick = { currentShot?.let { suggestDialogShot = it.code } },
                                            enabled = currentShot != null,
                                            icon = Icons.Rounded.RateReview,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suggestDialogShot?.let { targetShotCode ->
        InfinityBottomSheet(
            show = true,
            onDismiss = { suggestDialogShot = null },
            title = stringResource(R.string.p3_previs_suggest_title),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = targetShotCode,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = suggestText,
                    onValueChange = { suggestText = it.take(500) },
                    label = { Text(stringResource(R.string.p3_previs_suggest)) },
                    placeholder = { Text(stringResource(R.string.p3_previs_suggest_hint)) },
                    textStyle = LocalTextStyle.current.forInput(),
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    InfinityPillButton(
                        text = stringResource(R.string.p3_previs_cancel),
                        onClick = { suggestDialogShot = null },
                        modifier = Modifier.weight(1f),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.p3_previs_suggest_confirm),
                        onClick = {
                            val note = suggestText.trim()
                            if (note.isNotEmpty()) {
                                onSubmitSuggestion(targetShotCode, note)
                                suggestText = ""
                                suggestDialogShot = null
                            }
                        },
                        enabled = suggestText.trim().isNotEmpty(),
                        tone = InfinityPillTone.PRIMARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
