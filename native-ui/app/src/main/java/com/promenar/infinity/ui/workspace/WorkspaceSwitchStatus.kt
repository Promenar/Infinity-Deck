package com.promenar.infinity.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 会话中打开工作区选择器时的会话状态：运行模式与权限模式两个只读标签、通往“智能体可达范围”的链接，
 * 以及切换受阻（生成进行中或有待审批）与切换失败的说明。
 * [onStopGeneration] 非 null 时，生成中的说明条右侧提供“停止生成”。
 */
data class WorkspaceSwitchStatus(
    val executionMode: ExecutionMode,
    val busy: Boolean,
    val failed: Boolean,
    val onOpenReach: () -> Unit,
    val onStopGeneration: (() -> Unit)? = null,
)

/** 说明条的内容：切换失败优先于生成中停用。 */
internal enum class SwitchHintKind { BUSY, FAILED }

internal data class SwitchHint(val kind: SwitchHintKind, val showStop: Boolean)

/** 切换失败时提示错误；生成中停用时说明原因，取得停止生成入口时带“停止生成”；其余情况不显示。 */
internal fun workspaceSwitchHint(status: WorkspaceSwitchStatus?): SwitchHint? = when {
    status == null -> null
    status.failed -> SwitchHint(SwitchHintKind.FAILED, showStop = false)
    status.busy -> SwitchHint(SwitchHintKind.BUSY, showStop = status.onStopGeneration != null)
    else -> null
}

/** 选择器首页顶部：两个只读标签与“可达范围”链接，下方是切换受阻或失败的说明条。 */
@Composable
internal fun WorkspaceSessionBar(status: WorkspaceSwitchStatus, runMode: SessionRunMode?) {
    val hint = workspaceSwitchHint(status)
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small).testTag("workspace_picker_session_bar"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        // 标签与链接同在一个流式行：宽度够时链接靠右，放不下时整体换到下一行，标签始终按全宽测量、不被截断。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (runMode != null) {
                ReachPill(
                    stringResource(if (runMode == SessionRunMode.LINUX) R.string.agent_reach_run_mode_linux else R.string.agent_reach_run_mode_builtin),
                    Modifier.testTag("workspace_picker_run_mode"),
                )
            }
            ReachPill(
                stringResource(
                    when (status.executionMode) {
                        ExecutionMode.WORKSPACE -> R.string.agent_reach_permission_workspace
                        ExecutionMode.FULL_ACCESS -> R.string.agent_reach_permission_full_access
                    },
                ),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = status.onOpenReach,
                modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("workspace_picker_reach"),
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.workspace_picker_reach_link), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        hint?.let { WorkspaceSwitchHintStrip(it, onStop = status.onStopGeneration) }
    }
}

/**
 * 说明条：圆角浅色容器，左侧图标、中间说明，生成中停用时右侧可带“停止生成”。
 * 切换失败用错误色并立即播报，生成中停用礼貌播报。
 */
@Composable
private fun WorkspaceSwitchHintStrip(hint: SwitchHint, onStop: (() -> Unit)?) {
    val failed = hint.kind == SwitchHintKind.FAILED
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.infinityDomainColors.info
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.infinityDomainColors.infoContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = if (hint.showStop && onStop != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .testTag("workspace_picker_switch_hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Schedule,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (failed) R.string.workspace_switch_failed else R.string.agent_reach_switch_busy_hint),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (hint.showStop && onStop != null) {
            IconButton(onClick = onStop, modifier = Modifier.testTag("workspace_picker_stop_generation")) {
                Icon(
                    Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.agent_reach_stop_generation),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
