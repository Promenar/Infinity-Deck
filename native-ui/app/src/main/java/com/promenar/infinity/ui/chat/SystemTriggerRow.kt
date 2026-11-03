package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.domain.trigger.SystemTriggerMessage
import com.promenar.infinity.domain.trigger.SystemTurnKind
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 由定时任务或后台任务结束触发的一轮，起始消息不是用户亲自发送。 */
internal val Message.systemTrigger: SystemTriggerMessage.Parsed?
    get() = if (role == MessageRole.USER && name == SystemTriggerMessage.MESSAGE_NAME) SystemTriggerMessage.parse(content) else null

/**
 * 系统触发消息在时间线中显示为居中的一行：来源图标、名称、序号与触发时间；轻点展开发给模型的完整正文。
 * 它占据用户消息的位置，保持“一条触发消息对应一轮回复”的锚定关系。
 */
@Composable
internal fun SystemTriggerRow(trigger: SystemTriggerMessage.Parsed, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(trigger.sourceId, trigger.firedAt) { mutableStateOf(false) }
    val time = remember(trigger.firedAt) {
        trigger.firedAt.atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    val label = when (trigger.kind) {
        SystemTurnKind.SCHEDULE -> trigger.sequence?.let { stringResource(R.string.system_trigger_schedule_seq, trigger.title, it) }
            ?: stringResource(R.string.system_trigger_schedule, trigger.title)
        SystemTurnKind.JOB_EXIT -> stringResource(R.string.system_trigger_job_exit, trigger.title)
        SystemTurnKind.SUBAGENT_DONE -> stringResource(R.string.system_trigger_subagent_done, trigger.title)
        SystemTurnKind.SESSION_MESSAGE -> stringResource(R.string.branch_system_message, trigger.title)
        SystemTurnKind.SCRIPT_WAKE -> if (trigger.title.isBlank()) stringResource(R.string.system_trigger_script_wake)
            else stringResource(R.string.system_trigger_script_wake_titled, trigger.title)
    }
    val meta = if (trigger.catchUp) stringResource(R.string.system_trigger_catch_up, time) else time
    val hasBody = trigger.body.isNotBlank()
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(50))
                .then(
                    if (hasBody) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(if (expanded) R.string.system_trigger_collapse else R.string.system_trigger_expand),
                        ) { expanded = !expanded }
                    } else Modifier,
                )
                .testTag(UiTags.SYSTEM_TRIGGER_ROW),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Icon(
                    imageVector = when (trigger.kind) {
                        SystemTurnKind.SCHEDULE -> Icons.Rounded.Schedule
                        SystemTurnKind.SCRIPT_WAKE -> Icons.Rounded.Terminal
                        else -> Icons.Rounded.TaskAlt
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = InfinityTypography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(text = meta, style = InfinityTypography.labelSmall, maxLines = 1)
                if (hasBody) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = expanded && hasBody,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag(UiTags.SYSTEM_TRIGGER_BODY),
            ) {
                SelectionContainer {
                    Text(
                        text = trigger.body,
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(InfinitySpacing.Medium),
                    )
                }
            }
        }
    }
}
