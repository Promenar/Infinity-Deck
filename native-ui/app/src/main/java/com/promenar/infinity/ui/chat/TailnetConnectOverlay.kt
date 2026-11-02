package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.theme.InfinityMonospace
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.generation.TailnetWaitState
import com.promenar.infinity.data.tailnet.TailnetConnectProgress
import com.promenar.infinity.data.tailnet.TailnetConnectStep
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay

/** 连上后确认行停留的时长，之后交给标题栏下的进度胶囊。 */
internal const val TAILNET_CONNECTED_HOLD_MS = 600L

/**
 * 浮窗当前显示的内容：等待中的进度，或连上后的短暂确认。
 * 上游的 Connected 会在第一个生成快照到来时清除，这里自行保持 [TAILNET_CONNECTED_HOLD_MS]。
 */
internal fun nextTailnetOverlayContent(shown: TailnetWaitState?, upstream: TailnetWaitState?): TailnetWaitState? =
    when (upstream) {
        is TailnetWaitState.Waiting -> upstream
        TailnetWaitState.Connected -> if (shown != null) TailnetWaitState.Connected else null
        // 失败或取消时立即关闭；确认行由计时器收起。
        null -> shown?.takeIf { it == TailnetWaitState.Connected }
    }

/**
 * 发送到 tailnet 内的模型时、内置 Tailscale 尚未接入的等待浮窗：
 * 半透明遮罩 + 输入区上方的卡片，列出启动与接入步骤、等待时长和“取消发送”。
 * 连上后收成一行“已连接 Tailscale，正在发送”，[TAILNET_CONNECTED_HOLD_MS] 后淡出。
 */
@Composable
internal fun BoxScope.TailnetConnectOverlay(
    wait: TailnetWaitState?,
    onCancel: () -> Unit,
    bottomInset: Dp,
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    var shown by remember { mutableStateOf<TailnetWaitState?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    val next = nextTailnetOverlayContent(shown, wait)
    if (next != shown) {
        if (next is TailnetWaitState.Waiting && shown !is TailnetWaitState.Waiting) startedAt = elapsedClock()
        shown = next
    }
    LaunchedEffect(shown == TailnetWaitState.Connected) {
        if (shown == TailnetWaitState.Connected) {
            delay(TAILNET_CONNECTED_HOLD_MS)
            shown = null
        }
    }

    val waiting = shown is TailnetWaitState.Waiting
    AnimatedVisibility(
        visible = waiting,
        enter = fadeIn(tween(InfinityMotion.DurationMedium)),
        exit = fadeOut(tween(InfinityMotion.DurationShort)),
        modifier = Modifier.matchParentSize(),
    ) {
        // 等待期间遮住会话内容，点按遮罩不关闭浮窗，取消只能走“取消发送”。
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .clearAndSetSemantics {},
        )
    }

    val lastContent = remember { arrayOfNulls<TailnetWaitState>(1) }
    if (shown != null) lastContent[0] = shown
    AnimatedVisibility(
        visible = shown != null,
        enter = InfinityMotion.expandIn,
        exit = InfinityMotion.shrinkOut,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 8.dp)
            .padding(horizontal = 16.dp)
            .widthIn(max = InfinitySpacing.ChatMaxWidth)
            .fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = lastContent[0],
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "tailnetOverlay",
        ) { content ->
            when (content) {
                is TailnetWaitState.Waiting -> TailnetWaitingCard(
                    progress = content.progress,
                    startedAt = startedAt,
                    elapsedClock = elapsedClock,
                    onCancel = onCancel,
                )
                TailnetWaitState.Connected -> TailnetConnectedRow()
                null -> Unit
            }
        }
    }
}

@Composable
internal fun TailnetWaitingCard(
    progress: TailnetConnectProgress,
    startedAt: Long,
    elapsedClock: () -> Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(elapsedClock()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = elapsedClock()
            delay(1_000)
        }
    }
    val timeoutSeconds = (progress.timeoutMillis / 1_000).toInt().coerceAtLeast(1)
    val elapsedSeconds = ((now - startedAt) / 1_000).toInt().coerceIn(0, timeoutSeconds)
    val fraction by animateFloatAsState(
        targetValue = elapsedSeconds.toFloat() / timeoutSeconds,
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "tailnetWaitProgress",
    )
    val title = stringResource(R.string.tailnet_connect_title)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAILNET_CONNECT_OVERLAY_TAG)
            .semantics { paneTitle = title },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    )
                    if (rememberAnimationsEnabled()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.tailnet_connect_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val joining = progress.step == TailnetConnectStep.JOINING
                TailnetStepRow(
                    label = stringResource(R.string.tailnet_connect_step_start),
                    state = if (joining) StepState.DONE else StepState.ACTIVE,
                )
                TailnetStepRow(
                    label = stringResource(R.string.tailnet_connect_step_join),
                    state = if (joining) StepState.ACTIVE else StepState.PENDING,
                )
                TailnetStepRow(
                    label = stringResource(R.string.tailnet_connect_step_send, progress.host),
                    state = StepState.PENDING,
                    monospaceTail = progress.host,
                )
            }

            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    drawStopIndicator = {},
                )
                val waited = stringResource(R.string.tailnet_connect_waited, elapsedSeconds)
                val limit = stringResource(R.string.tailnet_connect_limit, timeoutSeconds)
                val progressDescription = stringResource(R.string.tailnet_connect_progress_cd, waited, limit)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = progressDescription },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(waited, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(limit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InfinityPillButton(
                    text = stringResource(R.string.tailnet_connect_cancel),
                    onClick = onCancel,
                    modifier = Modifier.testTag(TAILNET_CONNECT_CANCEL_TAG),
                )
            }
        }
    }
}

private enum class StepState { DONE, ACTIVE, PENDING }

@Composable
private fun TailnetStepRow(label: String, state: StepState, monospaceTail: String? = null) {
    val stateText = when (state) {
        StepState.DONE -> stringResource(R.string.tailnet_connect_state_done)
        StepState.ACTIVE -> stringResource(R.string.tailnet_connect_state_active)
        StepState.PENDING -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepMarker(state)
        val color = if (state == StepState.PENDING) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.let {
                when {
                    monospaceTail != null -> it.copy(fontFamily = InfinityMonospace)
                    state == StepState.ACTIVE -> it.copy(fontWeight = FontWeight.Medium)
                    else -> it
                }
            },
            color = color,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        stateText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = if (state == StepState.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepMarker(state: StepState) {
    val success = MaterialTheme.infinityDomainColors.success
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.DONE -> {
                Box(Modifier.size(20.dp).background(success, CircleShape))
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.infinityDomainColors.onSuccess,
                    modifier = Modifier.size(14.dp),
                )
            }
            StepState.ACTIVE -> {
                Box(Modifier.size(20.dp).border(2.dp, primary, CircleShape))
                Box(Modifier.size(8.dp).background(primary, CircleShape))
            }
            StepState.PENDING -> Box(Modifier.size(20.dp).border(2.dp, outline, CircleShape).background(Color.Transparent))
        }
    }
}

/** 连上后的确认行。 */
@Composable
internal fun TailnetConnectedRow(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAILNET_CONNECTED_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepMarker(StepState.DONE)
            Text(
                text = stringResource(R.string.tailnet_connect_connected),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal const val TAILNET_CONNECT_OVERLAY_TAG = "tailnet_connect_overlay"
internal const val TAILNET_CONNECT_CANCEL_TAG = "tailnet_connect_cancel"
internal const val TAILNET_CONNECTED_TAG = "tailnet_connect_connected"
