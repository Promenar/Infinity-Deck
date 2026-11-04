package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.common.InfinityMenuItem
import kotlinx.coroutines.delay

/** 输入栏上显示的一项限时免审批：类别名称与到期时间（epoch 毫秒）。 */
data class TimedGrantDisplay(val label: String, val expiresAtMillis: Long)

/**
 * 浮岛工具行上的执行模式切换：只显示盾牌图标，完全访问时换成警示图标与第三色，点按弹出两种模式。
 * 有限时免审批时图标右上角加主色圆点，菜单底部列出各类别剩余分钟并可恢复逐次审批。
 */
@Composable
internal fun ExecutionModeButton(
    mode: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
    timedGrants: List<TimedGrantDisplay> = emptyList(),
    onRevokeTimedGrants: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val modeDescription = stringResource(R.string.chat_execution_mode_cd, stringResource(mode.labelRes))
    val description = if (timedGrants.isEmpty()) modeDescription else stringResource(R.string.chat_execution_mode_timed_cd, modeDescription)
    // 只在菜单打开时每 10 秒刷新剩余分钟；到期项由上游流移除。
    val now by produceState(System.currentTimeMillis(), expanded, timedGrants) {
        value = System.currentTimeMillis()
        while (expanded && timedGrants.isNotEmpty()) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    val haptics = LocalInfinityHaptics.current
    Box(modifier) {
        ChatCompactIconButton(
            onClick = { haptics.perform(InfinityHapticEvent.SELECTION); expanded = true },
            modifier = Modifier
                .semantics { contentDescription = description }
                .testTag(UiTags.CHAT_EXECUTION_MODE),
        ) {
            Icon(
                imageVector = mode.icon(),
                contentDescription = null,
                tint = mode.tint(),
                modifier = Modifier.size(20.dp),
            )
            if (timedGrants.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-8).dp, y = 8.dp)
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExecutionMode.entries.forEach { option ->
                InfinityMenuItem(
                    text = {
                        // 浮层只放标题和一行短说明，完整解释在“Agent 可达范围”面板；说明须短到菜单宽度内放得下一行。
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(option.labelRes), style = InfinityTypography.labelLarge, maxLines = 1)
                            Text(
                                stringResource(option.hintRes()),
                                style = InfinityTypography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = { Icon(option.icon(), contentDescription = null, tint = option.tint()) },
                    trailingIcon = if (option == mode) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    haptic = InfinityHapticEvent.SELECTION.takeIf { option != mode },
                    onClick = {
                        expanded = false
                        if (option != mode) onSelect(option)
                    },
                    modifier = Modifier.testTag("${UiTags.CHAT_EXECUTION_MODE}_${option.name.lowercase()}"),
                )
            }
            if (timedGrants.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                timedGrants.forEach { grant ->
                    val minutes = ((grant.expiresAtMillis - now + 59_999) / 60_000).coerceIn(1, 10).toInt()
                    // 状态行不可点，不用菜单项，避免禁用态把文字压暗。
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 图标尺寸与间距同菜单项（24dp + 14dp），文字与上下菜单项左对齐。
                        Icon(Icons.Rounded.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Text(
                            stringResource(R.string.chat_timed_grant_active, grant.label, minutes),
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.chat_timed_grant_revoke), style = InfinityTypography.labelLarge, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Rounded.TimerOff, contentDescription = null) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    haptic = InfinityHapticEvent.SELECTION,
                    onClick = {
                        expanded = false
                        onRevokeTimedGrants()
                    },
                    modifier = Modifier.testTag(UiTags.CHAT_TIMED_GRANT_REVOKE),
                )
            }
        }
    }
}

private fun ExecutionMode.hintRes(): Int = when (this) {
    ExecutionMode.WORKSPACE -> R.string.chat_execution_mode_workspace_hint
    ExecutionMode.FULL_ACCESS -> R.string.chat_execution_mode_full_access_hint
}

private fun ExecutionMode.icon(): ImageVector = when (this) {
    ExecutionMode.WORKSPACE -> Icons.Outlined.GppGood
    ExecutionMode.FULL_ACCESS -> Icons.Rounded.GppMaybe
}

@Composable
private fun ExecutionMode.tint() = when (this) {
    ExecutionMode.WORKSPACE -> MaterialTheme.colorScheme.onSurfaceVariant
    ExecutionMode.FULL_ACCESS -> MaterialTheme.colorScheme.tertiary
}

/** 限时免审批类别在界面上的名称，审批卡按钮与盾牌菜单共用。 */
@Composable
internal fun timedGrantLabel(category: com.promenar.infinity.domain.tool.ApprovalGrantCategory): String = stringResource(
    when (category) {
        com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS -> R.string.chat_timed_grant_skills
        com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND -> R.string.chat_timed_grant_command
        com.promenar.infinity.domain.tool.ApprovalGrantCategory.LINUX_ROOTFS -> R.string.chat_timed_grant_linux_rootfs
    },
)
