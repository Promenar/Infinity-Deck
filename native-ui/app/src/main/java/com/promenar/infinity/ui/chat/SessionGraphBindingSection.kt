package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.SessionGraphQueueStatus
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/** 可绑定图谱的分组，决定改绑面板中的分组顺序与副标题写法。 */
internal enum class GraphGroup { Session, Collection, Shared, OtherSession }

/** 图谱不可选的原因；保留在列表中并写明原因，用户才知道它存在、去哪里放开。 */
internal enum class GraphLock { AgentRestricted, NeedsGlobalMemory, OutOfReach }

internal data class GraphChoice(
    val id: String,
    val group: GraphGroup,
    val title: String,
    /** 作品图谱为文件夹路径，会话图谱为所属智能体；可为空。 */
    val detail: String? = null,
    val documents: Int = 0,
    val entities: Int = 0,
    val lock: GraphLock? = null,
)

/** 会话当前的图谱绑定；[contribution] 为本会话写入非本会话图谱的实体与关系数，无写入时为空。 */
internal data class SessionGraphBindingUi(
    val current: GraphChoice,
    val readOnly: Boolean,
    val contribution: Pair<Int, Int>? = null,
)

/**
 * 会话设置中的“知识图谱”分区：一个会话恰好绑定一张图谱。显示当前图谱、只读或读写，
 * 可改绑到其它图谱；读写绑定到非本会话图谱时可整体撤回本会话写入的内容。
 */
@Composable
internal fun SessionGraphBindingSection(
    enabled: Boolean,
    binding: SessionGraphBindingUi?,
    choices: List<GraphChoice>,
    onRebind: (GraphChoice) -> Unit,
    onReadOnlyChange: (Boolean) -> Unit,
    onRetract: () -> Unit,
    graphQueue: SessionGraphQueueStatus? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmRetract by rememberSaveable { mutableStateOf(false) }
    val footer = when {
        !enabled || binding == null -> null
        binding.readOnly -> stringResource(R.string.ks_graph_hint_read_only)
        binding.current.group == GraphGroup.Session -> null
        else -> stringResource(R.string.ks_graph_hint_merge, binding.current.title)
    }
    InfinitySettingsSection(title = stringResource(R.string.ks_graph_section), footer = footer) {
        if (!enabled || binding == null) {
            InfinityValueRow(
                title = stringResource(R.string.ks_graph_bound),
                value = stringResource(R.string.memory_scope_feature_off),
                enabled = false,
                onClick = {},
                modifier = Modifier.testTag("session_graph_binding_off"),
            )
            return@InfinitySettingsSection
        }
        AnimatedContent(
            targetState = binding.current,
            transitionSpec = { InfinityMotion.fadeThrough() },
            contentKey = { it.id },
            label = "graphBinding",
        ) { current ->
            InfinitySettingsRowLayout(
                title = current.title,
                subtitle = graphSubtitle(current),
                icon = Icons.Rounded.Hub,
                subtitleMaxLines = 2,
                marqueeTitle = true,
                modifier = Modifier.testTag("session_graph_binding_current"),
                trailingContent = {
                    FilledTonalButton(
                        onClick = { picking = true },
                        modifier = Modifier.testTag("session_graph_rebind"),
                    ) { Text(stringResource(R.string.ks_graph_rebind), maxLines = 1) }
                },
            )
        }
        ReadModeRow(readOnly = binding.readOnly, onChange = onReadOnlyChange)
        AnimatedVisibility(
            visible = !binding.readOnly && binding.current.group != GraphGroup.Session,
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            val (entities, relations) = binding.contribution ?: (0 to 0)
            InfinitySettingsItem(
                title = stringResource(R.string.ks_graph_retract),
                subtitle = if (binding.contribution == null) stringResource(R.string.ks_graph_retract_none)
                else stringResource(R.string.ks_graph_retract_count, entities, relations),
                subtitleMaxLines = 2,
                enabled = binding.contribution != null,
                showChevron = true,
                onClick = { confirmRetract = true },
                modifier = Modifier.testTag("session_graph_retract"),
            )
        }
        if (graphQueue != null && !binding.readOnly) {
            InfinitySettingsRowLayout(
                title = stringResource(R.string.session_graph_queue_title),
                subtitle = stringResource(R.string.session_graph_queue_summary, graphQueue.pending, graphQueue.failed,
                    graphQueue.todayTurns, graphQueue.todayCalls),
                modifier = Modifier.testTag("session_graph_queue"),
                subtitleMaxLines = 2,
            )
        }
    }
    if (picking && binding != null) {
        GraphRebindSheet(
            current = binding.current,
            choices = choices,
            onConfirm = { onRebind(it); picking = false },
            onDismiss = { picking = false },
        )
    }
    if (confirmRetract && binding != null) {
        val (entities, relations) = binding.contribution ?: (0 to 0)
        InfinityConfirmDialog(
            title = stringResource(R.string.ks_graph_retract_title),
            message = stringResource(R.string.ks_graph_retract_message, binding.current.title, entities, relations),
            confirmText = stringResource(R.string.ks_graph_retract_confirm),
            isDestructive = true,
            onConfirm = { confirmRetract = false; onRetract() },
            onCancel = { confirmRetract = false },
        )
    }
}

@Composable
private fun ReadModeRow(readOnly: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(true to R.string.ks_graph_mode_read_only, false to R.string.ks_graph_mode_read_write)
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp)
            .testTag("session_graph_mode"),
    ) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = readOnly == value,
                onClick = { if (readOnly != value) onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag(if (value) "session_graph_mode_ro" else "session_graph_mode_rw"),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

/** 副标题：分组名、篇数（作品与通用知识）与实体数。 */
@Composable
internal fun graphSubtitle(choice: GraphChoice): String {
    val parts = buildList {
        add(when (choice.group) {
            GraphGroup.Session -> stringResource(R.string.ks_graph_group_own)
            GraphGroup.Collection -> choice.detail ?: stringResource(R.string.ks_graph_group_collection)
            GraphGroup.Shared -> stringResource(R.string.ks_graph_group_shared)
            GraphGroup.OtherSession -> choice.detail ?: stringResource(R.string.ks_graph_group_other)
        })
        if (choice.group == GraphGroup.Collection || choice.group == GraphGroup.Shared) {
            add(pluralStringResource(R.plurals.ks_graph_documents, choice.documents, choice.documents))
        }
        add(pluralStringResource(R.plurals.ks_graph_entities, choice.entities, choice.entities))
    }
    return parts.joinToString(stringResource(R.string.chat_step_summary_separator))
}

/**
 * 改绑面板：按本会话、资料合集、通用知识、其它会话分组；先选再确认。
 * 绑定资料合集时提示其文档会自动加入资料范围。
 */
@Composable
internal fun GraphRebindSheet(
    current: GraphChoice,
    choices: List<GraphChoice>,
    onConfirm: (GraphChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingId by rememberSaveable { mutableStateOf(current.id) }
    val pending = remember(pendingId, choices) { choices.firstOrNull { it.id == pendingId } ?: current }
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = stringResource(R.string.ks_graph_rebind_title),
        scrollContent = true) {
        Column {
            Text(
                stringResource(R.string.ks_graph_rebind_desc).keepCjkTailTogether(),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            GraphGroup.entries.forEach { group ->
                val items = choices.filter { it.group == group }
                if (items.isEmpty()) return@forEach
                Text(
                    stringResource(groupLabel(group)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                items.forEach { choice ->
                    val locked = choice.lock != null
                    InfinitySettingsItem(
                        horizontalPadding = 0.dp,
                        title = choice.title,
                        subtitle = choice.lock?.let { stringResource(lockLabel(it)) } ?: graphSubtitle(choice),
                        subtitleMaxLines = 2,
                        role = Role.RadioButton,
                        selected = choice.id == pendingId,
                        enabled = !locked,
                        onClick = { pendingId = choice.id },
                        modifier = Modifier.testTag("graph_choice_${choice.id}"),
                        trailingContent = if (choice.id == current.id) {
                            { Text(stringResource(R.string.ks_graph_current), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                        } else null,
                    )
                }
            }
            AnimatedVisibility(
                visible = pending.group == GraphGroup.Collection && pending.id != current.id,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                Text(
                    stringResource(R.string.ks_graph_rebind_adds_docs, pending.title).keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_btn_cancel), maxLines = 1) }
                FilledTonalButton(
                    onClick = { onConfirm(pending) },
                    enabled = pending.id != current.id && pending.lock == null,
                    modifier = Modifier.testTag("graph_rebind_confirm"),
                ) {
                    Icon(Icons.Rounded.Hub, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.ks_graph_rebind_confirm), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun groupLabel(group: GraphGroup) = when (group) {
    GraphGroup.Session -> R.string.ks_graph_group_header_own
    GraphGroup.Collection -> R.string.ks_graph_group_header_collection
    GraphGroup.Shared -> R.string.ks_graph_group_header_shared
    GraphGroup.OtherSession -> R.string.ks_graph_group_header_other
}

private fun lockLabel(lock: GraphLock) = when (lock) {
    GraphLock.AgentRestricted -> R.string.ks_graph_lock_agent
    GraphLock.NeedsGlobalMemory -> R.string.ks_graph_lock_global
    GraphLock.OutOfReach -> R.string.ks_graph_lock_reach
}
