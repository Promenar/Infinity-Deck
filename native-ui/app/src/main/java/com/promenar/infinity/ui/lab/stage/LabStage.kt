package com.promenar.infinity.ui.lab.stage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.ImmersiveSystemBars
import com.promenar.infinity.ui.common.InfinitySlider
import com.promenar.infinity.ui.lab.timeline.LabPlayer
import com.promenar.infinity.ui.lab.timeline.SUPPORTED_LAB_SPEEDS
import com.promenar.infinity.ui.lab.timeline.rememberLabPlayer
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.delay

/**
 * 版本的整页舞台：场景铺满全屏，底部浮一条控制条。控制条 3 秒无操作自动隐藏，双指轻点唤出。
 * 录制模式隐藏控制条并进入应用级沉浸；工作流在录制开始时回到开头，停顿 [RECORD_LEAD_IN_MS] 后播放，
 * 留出录屏起步的空白。[record] 为 true 时直接以录制模式打开。
 */
@Composable
fun LabStage(scene: LabScene, record: Boolean, onExit: () -> Unit) {
    val scope = remember(onExit) { LabSceneScope(onExit) }
    var recording by rememberSaveable { mutableStateOf(record) }
    var barVisible by remember { mutableStateOf(!record) }
    var touches by remember { mutableIntStateOf(0) }
    var recordStarts by remember { mutableIntStateOf(if (record) 1 else 0) }
    ImmersiveSystemBars(recording)
    BackHandler(onBack = onExit)
    LaunchedEffect(barVisible, touches) {
        if (!barVisible) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        barVisible = false
    }
    val toggleRecording = {
        recording = !recording
        if (recording) {
            barVisible = false
            recordStarts++
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("lab_stage")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            barVisible = true
                            touches++
                            do {
                                val next = awaitPointerEvent(PointerEventPass.Initial)
                            } while (next.changes.any { it.pressed })
                        }
                    }
                }
            },
    ) {
        when (scene) {
            is LabStateScene -> {
                scene.content(scope)
                ControlBarSlot(barVisible) {
                    LabControlBar(player = null, recording = recording, onToggleRecording = toggleRecording, onTouch = { touches++ })
                }
            }
            is LabWorkflowScene<*> -> WorkflowStage(scene, scope, recording, recordStarts, barVisible, toggleRecording) { touches++ }
        }
    }
}

@Composable
private fun <S> WorkflowStage(
    scene: LabWorkflowScene<S>,
    scope: LabSceneScope,
    recording: Boolean,
    recordStarts: Int,
    barVisible: Boolean,
    onToggleRecording: () -> Unit,
    onTouch: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val timeline = remember(scene, resources, configuration) { scene.timeline(resources) }
    val player = rememberLabPlayer(timeline, autoPlay = recordStarts == 0)
    LaunchedEffect(player, recordStarts) {
        if (recordStarts == 0) return@LaunchedEffect
        player.seek(0)
        if (player.playback.playing) player.togglePlay()
        delay(RECORD_LEAD_IN_MS)
        if (!player.playback.playing) player.togglePlay()
    }
    scene.content(scope, player.state)
    Box(Modifier.fillMaxSize()) {
        ControlBarSlot(barVisible) {
            LabControlBar(player = player, recording = recording, onToggleRecording = onToggleRecording, onTouch = onTouch)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ControlBarSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Large),
    ) { content() }
}

@Composable
private fun LabControlBar(
    player: LabPlayer<*>?,
    recording: Boolean,
    onToggleRecording: () -> Unit,
    onTouch: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("lab_control_bar"),
    ) {
        Column(Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)) {
            if (player != null) {
                val duration = player.timeline.durationMs
                val position = player.playback.positionMs
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    Text(formatLabTime(position), style = MaterialTheme.typography.labelMedium)
                    InfinitySlider(
                        value = if (duration == 0L) 0f else position.toFloat() / duration,
                        onValueChange = { player.seek((it * duration).toLong()); onTouch() },
                        modifier = Modifier.weight(1f).testTag("lab_control_seek"),
                    )
                    Text(formatLabTime(duration), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (player != null) {
                    IconButton(onClick = { player.restart(); onTouch() }) {
                        Icon(Icons.Rounded.Replay, contentDescription = stringResource(R.string.lab_ctrl_restart))
                    }
                    IconButton(onClick = { player.togglePlay(); onTouch() }, modifier = Modifier.testTag("lab_control_play")) {
                        val playing = player.playback.playing
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.lab_ctrl_pause else R.string.lab_ctrl_play),
                        )
                    }
                    TextButton(onClick = {
                        val speeds = SUPPORTED_LAB_SPEEDS
                        player.setSpeed(speeds[(speeds.indexOf(player.playback.speed) + 1) % speeds.size])
                        onTouch()
                    }) {
                        Text(stringResource(R.string.lab_ctrl_speed, formatLabSpeed(player.playback.speed)))
                    }
                } else {
                    Text(
                        stringResource(R.string.lab_ctrl_state),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = InfinitySpacing.Small),
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = onToggleRecording, modifier = Modifier.testTag("lab_control_record")) {
                    Icon(if (recording) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam, contentDescription = null)
                    Text(
                        stringResource(if (recording) R.string.lab_ctrl_record_exit else R.string.lab_ctrl_record),
                        modifier = Modifier.padding(start = InfinitySpacing.XSmall),
                    )
                }
            }
            Text(
                stringResource(R.string.lab_ctrl_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = InfinitySpacing.Small, bottom = InfinitySpacing.XSmall),
            )
        }
    }
}

internal fun formatLabTime(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun formatLabSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

private const val AUTO_HIDE_MS = 3_000L
internal const val RECORD_LEAD_IN_MS = 1_500L
