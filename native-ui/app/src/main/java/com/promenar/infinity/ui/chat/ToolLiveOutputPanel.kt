package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.ui.theme.InfinityTypography

/** 运行中命令的实时输出存储；预览与测试中为空，此时不显示终端区。 */
val LocalToolLiveOutput = staticCompositionLocalOf<ToolLiveOutputStore?> { null }

/**
 * 按工具调用步骤 id 读取实时输出；命令结束后存储移除该项，终端区随之消失。
 * 宿主已有自己的停止控件时传 [showStop] = false，避免同一任务出现两个停止按钮。
 */
@Composable
internal fun RunningToolLiveOutput(
    stepId: String,
    showStop: Boolean = true,
    modifier: Modifier = Modifier.padding(bottom = ExpandedStepBottomPadding),
) {
    val store = LocalToolLiveOutput.current ?: return
    val initial = remember(store, stepId) { store.snapshot(stepId) }
    val snapshot by remember(store, stepId) { store.observe(stepId) }.collectAsState(initial = initial)
    val sessionId = com.promenar.infinity.ui.chat.components.LocalAgentActivity.current?.sessionId
    val control by remember(sessionId, stepId) {
        if (sessionId == null) kotlinx.coroutines.flow.flowOf(null)
        else com.promenar.infinity.data.exec.ToolExecutionControls.observeStep(sessionId, stepId)
    }.collectAsState(initial = null)
    // 只有支持后台接管的前台命令才给出“转入后台”；命令继续运行，结果改由后台任务承接。
    val moveToBackground = control?.takeIf {
        it.kind == com.promenar.infinity.data.exec.ToolExecutionControls.Kind.FOREGROUND && it.canMoveToBackground
    }?.let { state -> { com.promenar.infinity.data.exec.ToolExecutionControls.moveToBackground(state.sessionId, state.toolCallId); Unit } }
    snapshot?.let {
        ToolLiveOutputPanel(it, onStop = { store.requestStop(stepId) }, showStop = showStop,
            onMoveToBackground = moveToBackground, modifier = modifier)
    }
}

@Composable
internal fun ToolLiveOutputPanel(
    snapshot: ToolLiveOutputStore.Snapshot,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    showStop: Boolean = true,
    onMoveToBackground: (() -> Unit)? = null,
) {
    val tail = remember(snapshot.text) { liveOutputTail(snapshot.text) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, bottom = 10.dp)
            .testTag("CHAT_TOOL_LIVE_OUTPUT"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 有停止按钮时行高由 48dp 触控区决定，标题与按钮同行居中；没有按钮时保持紧凑。
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_tool_live_output_title),
                style = InfinityTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onMoveToBackground != null && !snapshot.stopRequested) {
                com.promenar.infinity.ui.common.InfinityPillButton(
                    text = stringResource(R.string.exec_steer_move_to_background),
                    onClick = onMoveToBackground,
                    compact = true,
                    modifier = Modifier.testTag(com.promenar.infinity.ui.testing.UiTags.CHAT_TOOL_MOVE_TO_BACKGROUND),
                )
            }
            if (showStop && snapshot.stoppable) {
                // 已请求停止时以文字说明进度，代替不可再点的图标按钮。
                if (snapshot.stopRequested) {
                    Text(
                        text = stringResource(R.string.chat_tool_live_stopping),
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 8.dp),
                    )
                } else {
                    IconButton(onClick = onStop, modifier = Modifier.testTag("CHAT_TOOL_LIVE_STOP")) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.chat_tool_live_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        val message = when {
            snapshot.unsupported -> stringResource(R.string.chat_tool_live_unsupported)
            tail.isEmpty() -> stringResource(R.string.chat_tool_live_waiting)
            else -> null
        }
        if (message != null) {
            Text(message, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
        } else {
            Text(
                text = tail,
                style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                maxLines = LIVE_OUTPUT_LINES,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

internal const val LIVE_OUTPUT_LINES = 12

/**
 * 终端区显示的尾部行：按换行切分，回车覆盖的进度行只留最后一次刷新，去掉 ANSI 控制序列与末尾空行。
 */
internal fun liveOutputTail(text: String, maxLines: Int = LIVE_OUTPUT_LINES): String {
    if (text.isEmpty()) return ""
    val lines = text.replace(ANSI_ESCAPE, "").split('\n')
        .map { line -> line.trimEnd('\r').substringAfterLast('\r') }
        .dropLastWhile(String::isBlank)
    return lines.takeLast(maxLines).joinToString("\n")
}

private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
