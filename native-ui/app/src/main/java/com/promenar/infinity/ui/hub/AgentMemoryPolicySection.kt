package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.pluralStringResource
import com.promenar.infinity.data.agent.GlobalMemoryPolicy
import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.ui.common.CollectionCheckList
import com.promenar.infinity.ui.common.PermissionChoice
import com.promenar.infinity.ui.common.PermissionChoiceList
import com.promenar.infinity.ui.common.PermissionHeading
import com.promenar.infinity.ui.common.activeMemoryChoices
import com.promenar.infinity.ui.common.activeMemoryLabel
import com.promenar.infinity.ui.common.globalMemoryChoices
import com.promenar.infinity.ui.common.globalMemoryLabel
import com.promenar.infinity.ui.common.reachChoices
import com.promenar.infinity.ui.common.reachLabel
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.SettingsToggle

/**
 * 智能体检索权限：全局记忆（三档与可达上限）、主动记忆默认模式与可用资料。会话创建时继承，
 * 之后在这里的允许范围内调整；收紧时会话按上限执行、保存的选择不变，放宽后自动恢复。
 */
@Composable
internal fun AgentScopeSection(
    policy: AgentMemoryPolicy,
    onPolicyChange: ((AgentMemoryPolicy) -> AgentMemoryPolicy) -> Unit,
    onOpenKnowledgeSettings: (() -> Unit)? = null,
    collections: List<KgCollectionSummary> = emptyList(),
) {
    var editing by remember { mutableStateOf<AgentPermission?>(null) }
    InfinitySettingsSection(
        title = stringResource(R.string.agent_memory_section),
        linkedFooter = agentScopeFooter(onOpenKnowledgeSettings),
    ) {
        InfinityValueRow(
            title = stringResource(R.string.perm_global_memory),
            value = stringResource(globalMemoryLabel(policy.globalMemory)),
            subtitle = if (policy.globalMemory == GlobalMemoryPolicy.FORBID) null
                else stringResource(R.string.perm_reach_value, stringResource(reachLabel(policy.memoryCap))),
            onClick = { editing = AgentPermission.GlobalMemory },
            modifier = Modifier.testTag("agent_perm_global_memory"),
        )
        InfinityValueRow(
            title = stringResource(R.string.perm_active_memory),
            value = stringResource(activeMemoryLabel(policy.activeMemoryDefault)),
            onClick = { editing = AgentPermission.ActiveMemory },
            modifier = Modifier.testTag("agent_perm_active_memory"),
        )
        val allowed = policy.allowedCollectionIds
        InfinityValueRow(
            title = stringResource(R.string.perm_collections),
            value = if (allowed == null) stringResource(R.string.perm_collections_all)
                else pluralStringResource(R.plurals.perm_collections_count, allowed.size, allowed.size),
            subtitle = stringResource(
                R.string.perm_default_value,
                stringResource(if (policy.defaultCollectionIds?.isEmpty() == true) R.string.perm_default_none else R.string.perm_default_all),
            ),
            onClick = { editing = AgentPermission.Collections },
            modifier = Modifier.testTag("agent_perm_collections"),
        )
    }
    when (editing) {
        AgentPermission.GlobalMemory -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.perm_global_memory), scrollContent = true,
        ) {
            PermissionHeading(stringResource(R.string.perm_global_heading))
            PermissionChoiceList(
                choices = globalMemoryChoices,
                selected = policy.globalMemory,
                onSelect = { value -> onPolicyChange { it.copy(globalMemory = value) } },
                testTagPrefix = "agent_perm_global",
            )
            PermissionHeading(stringResource(R.string.perm_reach_heading))
            PermissionChoiceList(
                choices = reachChoices(),
                selected = if (policy.memoryCap == ConversationScope.ALL_AGENTS) ConversationScope.ALL_AGENTS else ConversationScope.AGENT,
                // 新会话的全局记忆范围取上限。
                onSelect = { value -> onPolicyChange { it.copy(memoryCap = value, memoryDefault = value).normalized() } },
                testTagPrefix = "agent_perm_reach",
                enabled = policy.globalMemory != GlobalMemoryPolicy.FORBID,
            )
            PermissionNote(stringResource(R.string.perm_global_footer))
        }
        AgentPermission.ActiveMemory -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.perm_active_memory), scrollContent = true,
        ) {
            PermissionChoiceList(
                choices = activeMemoryChoices,
                selected = policy.activeMemoryDefault,
                onSelect = { value -> onPolicyChange { it.copy(activeMemoryDefault = value) } },
                testTagPrefix = "agent_perm_active",
            )
            PermissionNote(stringResource(R.string.perm_active_switch_note))
        }
        AgentPermission.Collections -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.perm_collections), scrollContent = true,
        ) {
            val allowed = policy.allowedCollectionIds
            SettingsToggle(
                title = stringResource(R.string.perm_collections_all),
                description = stringResource(R.string.perm_collections_all_desc),
                checked = allowed == null,
                onCheckedChange = { all ->
                    onPolicyChange { it.copy(allowedCollectionIds = if (all) null else collections.map(KgCollectionSummary::id)) }
                },
                modifier = Modifier.testTag("agent_perm_collections_all"),
            )
            AnimatedVisibility(visible = allowed != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column {
                    PermissionHeading(stringResource(R.string.perm_collections_pick_heading))
                    CollectionCheckList(
                        collections = collections,
                        checked = allowed.orEmpty().toSet(),
                        onToggle = { id, on ->
                            onPolicyChange { current ->
                                val ids = current.allowedCollectionIds.orEmpty()
                                current.copy(allowedCollectionIds = if (on) (ids + id).distinct() else ids - id)
                            }
                        },
                        testTagPrefix = "agent_perm_collection",
                    )
                }
            }
            PermissionHeading(stringResource(R.string.perm_collections_default_heading))
            PermissionChoiceList(
                choices = listOf(
                    PermissionChoice(false, R.string.perm_default_all, R.string.perm_default_all_desc),
                    PermissionChoice(true, R.string.perm_default_none, R.string.perm_default_none_desc),
                ),
                selected = policy.defaultCollectionIds?.isEmpty() == true,
                onSelect = { none -> onPolicyChange { it.copy(defaultCollectionIds = if (none) emptyList() else null) } },
                testTagPrefix = "agent_perm_default",
            )
        }
        null -> Unit
    }
}

private enum class AgentPermission { GlobalMemory, ActiveMemory, Collections }

@Composable
private fun PermissionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

/** 页脚中的“知识库设置”可点；没有跳转时只显示文字。 */
@Composable
private fun agentScopeFooter(onOpenKnowledgeSettings: (() -> Unit)?): AnnotatedString {
    val linkText = stringResource(R.string.agent_scope_footer_link)
    val full = stringResource(R.string.agent_scope_footer, linkText)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(full, linkText, onOpenKnowledgeSettings, linkColor) {
        buildAnnotatedString {
            append(full)
            val start = full.indexOf(linkText)
            if (onOpenKnowledgeSettings != null && start >= 0) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "knowledge_settings",
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onOpenKnowledgeSettings() },
                    ),
                    start,
                    start + linkText.length,
                )
            }
        }
    }
}
